package app.squeeze.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Video codecs Squeeze can encode with. [efficiency] is bits needed relative to H.264 for equal visual quality. */
enum class Codec(val mime: String, val label: String, val efficiency: Double) {
    H264("video/avc", "H.264", 1.0),
    HEVC("video/hevc", "HEVC", 0.70),
    AV1("video/av01", "AV1", 0.58),
}

enum class CodecChoice(val label: String) { Auto("Auto"), H264("H.264"), Hevc("HEVC"), Av1("AV1") }

enum class Mode { Preset, TargetSize }

/**
 * Quality tiers. [bpp] is the H.264-equivalent bits per pixel per frame at 1080p; the planner scales it by
 * codec efficiency and resolution, and never asks for more than the source already spends.
 */
enum class Preset(
    val label: String,
    val blurb: String,
    val bpp: Double,
    val maxShortSide: Int,
    val fpsCap: Int,
) {
    Balanced("Balanced", "Looks like the original. Keeps resolution.", 0.100, 2160, 60),
    Small("Small", "Great for sharing. Up to 1080p, 30 fps.", 0.065, 1080, 30),
    Tiny("Tiny", "Smallest. Up to 720p, 30 fps.", 0.040, 720, 30),
}

data class Settings(
    val mode: Mode = Mode.Preset,
    val preset: Preset = Preset.Balanced,
    val targetMb: Int = 25,
    val codec: CodecChoice = CodecChoice.Auto,
    val maxShortSide: Int? = null,
    val maxFps: Int? = null,
    val muteAudio: Boolean = false,
)

/** Which encoders this device offers in hardware. H.264 is assumed everywhere. */
data class Capabilities(val hevc: Boolean, val av1: Boolean)

/** Everything the planner needs to know about a source file, in display orientation. */
data class Source(
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val hasAudio: Boolean,
    val isHdr: Boolean,
    val mime: String,
) {
    val shortSide: Int get() = min(width, height)
}

data class Plan(
    val codec: Codec,
    val width: Int,
    val height: Int,
    val resize: Boolean,
    /** Target frame rate when frames must be dropped, otherwise null to keep the source rate. */
    val fps: Int?,
    val videoBitrate: Int,
    val audioBitrate: Int,
    val estimatedBytes: Long,
    /** Non-null when re-encoding is not worthwhile; the original is kept. */
    val skipReason: String? = null,
    val warning: String? = null,
) {
    val shortSide: Int get() = min(width, height)
}

/**
 * Chooses codec, resolution, frame rate and bitrate for one video.
 *
 * Strategy, in order of impact:
 *  1. Prefer the most efficient hardware codec (hardware HEVC ≈ 30% fewer bits than H.264 at equal quality).
 *  2. Budget bits per pixel instead of a fixed bitrate, so quality is consistent across resolutions and frame
 *     rates; larger frames need fewer bits per pixel, smaller ones more.
 *  3. Never spend more than the source does, and skip files that would not shrink meaningfully.
 *  4. In target-size mode, solve for the bitrate that hits the size, then trade frame rate and resolution
 *     (not blocky pixels) until the bits-per-pixel budget stays above a watchability floor.
 */
object Planner {
    const val MIN_SAVING = 0.10
    private const val SOURCE_BITRATE_CEILING = 0.90
    private const val TARGET_HEADROOM = 0.93
    private const val MIN_VIDEO_BPS = 120_000
    private const val DEFAULT_AUDIO_BPS = 128_000
    private const val TARGET_FLOOR_BPP = 0.045
    private val LADDER = intArrayOf(2160, 1440, 1080, 720, 540, 480, 360, 240)

    fun resolveCodec(choice: CodecChoice, caps: Capabilities): Codec = when (choice) {
        CodecChoice.H264 -> Codec.H264
        CodecChoice.Av1 -> if (caps.av1) Codec.AV1 else if (caps.hevc) Codec.HEVC else Codec.H264
        CodecChoice.Hevc, CodecChoice.Auto -> if (caps.hevc) Codec.HEVC else Codec.H264
    }

    fun plan(src: Source, s: Settings, caps: Capabilities): Plan {
        val codec = resolveCodec(s.codec, caps)
        val audioBps = when {
            !src.hasAudio || s.muteAudio -> 0
            src.audioBitrate > 0 -> src.audioBitrate
            else -> DEFAULT_AUDIO_BPS
        }
        val seconds = max(src.durationMs / 1000.0, 0.001)
        return when (s.mode) {
            Mode.Preset -> byPreset(src, s, codec, audioBps, seconds)
            Mode.TargetSize -> byTarget(src, s, codec, audioBps, seconds)
        }
    }

    private fun byPreset(src: Source, s: Settings, codec: Codec, audioBps: Int, seconds: Double): Plan {
        val p = s.preset
        val side = listOfNotNull(src.shortSide, p.maxShortSide, s.maxShortSide).min()
        val (w, h) = scaled(src, side)
        val fps = effectiveFps(src, min(p.fpsCap, s.maxFps ?: Int.MAX_VALUE))
        val rate = fps ?: src.fps.coerceIn(1f, 120f).toDouble().roundToInt()

        val wanted = p.bpp * codec.efficiency * resolutionFactor(w, h) * w * h * rate
        // Spend at most what the source spends, translated to the new codec's efficiency.
        val ceiling = if (src.videoBitrate > 0) src.videoBitrate * (codec.efficiency / sourceEfficiency(src.mime)) * SOURCE_BITRATE_CEILING else Double.MAX_VALUE
        val video = min(wanted, ceiling).roundToInt().coerceAtLeast(MIN_VIDEO_BPS)
        return finish(src, codec, w, h, fps, video, audioBps, seconds, warning = null)
    }

    private fun byTarget(src: Source, s: Settings, codec: Codec, audioBps: Int, seconds: Double): Plan {
        val targetBytes = s.targetMb * 1_000_000L
        if (targetBytes >= src.sizeBytes * (1 - MIN_SAVING)) {
            return keep(src, codec, audioBps, "Already smaller than the ${s.targetMb} MB target")
        }
        val budgetBps = targetBytes * 8 * TARGET_HEADROOM / seconds
        val video = ((budgetBps - audioBps).roundToInt()).coerceAtLeast(MIN_VIDEO_BPS)

        val sourceFps = src.fps.coerceIn(1f, 120f)
        val maxSide = listOfNotNull(src.shortSide, s.maxShortSide).min()
        val sides = (listOf(maxSide) + LADDER.filter { it < maxSide }).distinct()
        val rates = listOfNotNull(
            effectiveFps(src, s.maxFps ?: Int.MAX_VALUE) ?: sourceFps.roundToInt(),
            30.takeIf { sourceFps > 30.5f && (s.maxFps ?: 30) >= 30 },
            24.takeIf { sourceFps > 24.5f && (s.maxFps ?: 24) >= 24 },
        ).distinct()

        for (side in sides) {
            val (w, h) = scaled(src, side)
            for (rate in rates) {
                val bpp = video / (w.toDouble() * h * rate)
                if (bpp >= TARGET_FLOOR_BPP * codec.efficiency * resolutionFactor(w, h)) {
                    return finish(src, codec, w, h, fpsFor(src, rate), video, audioBps, seconds, warning = null)
                        .warnIfOver(targetBytes, s.targetMb)
                }
            }
        }
        val (w, h) = scaled(src, sides.last())
        val rate = rates.last()
        return finish(
            src, codec, w, h, fpsFor(src, rate), video, audioBps, seconds,
            warning = "${s.targetMb} MB is very small for this video; expect visible blockiness",
        ).warnIfOver(targetBytes, s.targetMb)
    }

    /** The bitrate floor can make the smallest watchable encode larger than the request; say so instead of missing silently. */
    private fun Plan.warnIfOver(targetBytes: Long, targetMb: Int): Plan =
        if (skipReason == null && estimatedBytes > targetBytes * 1.05) {
            copy(warning = "Can't reach $targetMb MB. Smallest practical size is about ${(estimatedBytes + 500_000) / 1_000_000} MB")
        } else {
            this
        }

    private fun sourceEfficiency(mime: String): Double = when (mime.lowercase()) {
        Codec.HEVC.mime -> Codec.HEVC.efficiency
        Codec.AV1.mime -> Codec.AV1.efficiency
        "video/x-vnd.on2.vp9" -> 0.65
        else -> Codec.H264.efficiency
    }

    private fun finish(
        src: Source, codec: Codec, w: Int, h: Int, fps: Int?, video: Int, audioBps: Int, seconds: Double, warning: String?,
    ): Plan {
        val estimate = ((video + audioBps) * seconds / 8).toLong()
        val resize = w != evenRound(src.width.toDouble()) || h != evenRound(src.height.toDouble())
        val plan = Plan(codec, w, h, resize, fps, video, audioBps, estimate, null, warning)
        val saving = 1 - estimate.toDouble() / max(src.sizeBytes, 1)
        return if (saving < MIN_SAVING) {
            plan.copy(skipReason = "Already efficient. It would save under ${(MIN_SAVING * 100).toInt()}%", estimatedBytes = src.sizeBytes)
        } else {
            plan
        }
    }

    private fun keep(src: Source, codec: Codec, audioBps: Int, reason: String) = Plan(
        codec, src.width, src.height, resize = false, fps = null,
        videoBitrate = src.videoBitrate, audioBitrate = audioBps,
        estimatedBytes = src.sizeBytes, skipReason = reason,
    )

    /** Frame-drop target if [cap] is meaningfully below the source rate; null means keep every frame. */
    private fun effectiveFps(src: Source, cap: Int): Int? = fpsFor(src, min(cap, 120))

    private fun fpsFor(src: Source, rate: Int): Int? = if (rate < src.fps - 0.5f) rate else null

    /** Bigger frames hide artifacts better, so they need fewer bits per pixel than 1080p; smaller ones need more. */
    private fun resolutionFactor(w: Int, h: Int): Double =
        (1920.0 * 1080.0 / (w.toDouble() * h)).pow(0.25).coerceIn(0.6, 1.5)

    private fun scaled(src: Source, shortSide: Int): Pair<Int, Int> {
        if (shortSide >= src.shortSide) return evenRound(src.width.toDouble()) to evenRound(src.height.toDouble())
        val k = shortSide.toDouble() / src.shortSide
        return evenRound(src.width * k) to evenRound(src.height * k)
    }

    private fun evenRound(v: Double): Int = max(2, (v / 2).roundToInt() * 2)
}
