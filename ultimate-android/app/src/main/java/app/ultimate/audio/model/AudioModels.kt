package app.ultimate.audio.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class OutFormat(val label: String, val ext: String, val mime: String, val blurb: String) {
    Mp3("MP3", "mp3", "audio/mpeg", "Plays everywhere."),
    M4a("M4A", "m4a", "audio/mp4", "The video's own audio, untouched and instant."),
    Wav("WAV", "wav", "audio/x-wav", "Uncompressed. Large, but ideal for editing."),
}

data class AudioSettings(
    val format: OutFormat = OutFormat.Mp3,
    val kbps: Int = 192,
    val mono: Boolean = false,
    val trimStartMs: Long = 0,
    /** Exclusive end of the part to keep, or -1 for "to the end". */
    val trimEndMs: Long = -1,
)

/** What a video's audio track looks like. */
data class AudioSource(
    val durationMs: Long,
    val mime: String,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
) {
    /** AAC can be copied into an M4A without re-encoding. */
    val canCopy: Boolean get() = mime.equals(AAC, ignoreCase = true)

    companion object {
        const val AAC = "audio/mp4a-latm"
    }
}

object AudioPlanner {
    val MP3_BITRATES = listOf(64, 96, 128, 192, 256, 320)

    /** The format that will actually be produced: copying needs AAC, so anything else falls back to MP3. */
    fun effectiveFormat(src: AudioSource, s: AudioSettings): OutFormat =
        if (s.format == OutFormat.M4a && !src.canCopy) OutFormat.Mp3 else s.format

    /** Milliseconds that will be kept after trimming. */
    fun keptMs(src: AudioSource, s: AudioSettings): Long {
        val end = if (s.trimEndMs > 0) min(s.trimEndMs, src.durationMs) else src.durationMs
        return max(end - s.trimStartMs.coerceAtLeast(0), 0)
    }

    fun outputChannels(src: AudioSource, s: AudioSettings): Int = if (s.mono || src.channels <= 1) 1 else 2

    fun estimateBytes(src: AudioSource, s: AudioSettings): Long {
        val seconds = keptMs(src, s) / 1000.0
        return when (effectiveFormat(src, s)) {
            OutFormat.Mp3 -> (s.kbps * 1000 / 8.0 * seconds).toLong()
            OutFormat.M4a -> ((if (src.bitrate > 0) src.bitrate else 128_000) / 8.0 * seconds).toLong()
            OutFormat.Wav -> (src.sampleRate * outputChannels(src, s) * 2.0 * seconds).toLong() + 44
        }
    }

    fun bitrateLabel(kbps: Int) = when (kbps) {
        64 -> "Voice"
        96 -> "Compact"
        128 -> "Good"
        192 -> "Great"
        256 -> "Excellent"
        320 -> "Maximum"
        else -> ""
    }

    /** Sample rates the MP3 encoder accepts; anything else is resampled to 44.1 kHz. */
    val MP3_RATES = setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    fun mp3Rate(sourceRate: Int): Int = if (sourceRate in MP3_RATES) sourceRate else 44100

    fun peakBuckets(): Int = 160
}

/** Converts interleaved 16-bit PCM between channel layouts and sample rates. */
class PcmMixer(private val srcChannels: Int, private val dstChannels: Int, srcRate: Int, dstRate: Int) {
    private val ratio = srcRate.toDouble() / dstRate
    private val resample = srcRate != dstRate
    private var pos = 0.0
    private var prev = ShortArray(dstChannels)
    private var started = false
    private var scratch = ShortArray(0)

    /** Returns the converted samples in a reused buffer, and how many of them are valid. */
    fun process(input: ShortArray, inputShorts: Int): Pair<ShortArray, Int> {
        val frames = inputShorts / srcChannels
        if (scratch.size < frames * dstChannels) scratch = ShortArray(frames * dstChannels)
        for (f in 0 until frames) {
            val base = f * srcChannels
            when {
                srcChannels == dstChannels -> for (c in 0 until dstChannels) scratch[f * dstChannels + c] = input[base + c]
                dstChannels == 1 -> scratch[f] = if (srcChannels >= 2) ((input[base] + input[base + 1]) / 2).toShort() else input[base]
                else -> for (c in 0 until dstChannels) scratch[f * dstChannels + c] = input[base + min(c, srcChannels - 1)]
            }
        }
        if (!resample) return scratch to frames * dstChannels
        return resampled(frames)
    }

    private var resampleOut = ShortArray(0)

    private fun resampled(frames: Int): Pair<ShortArray, Int> {
        if (frames == 0) return scratch to 0
        if (!started) {
            for (c in 0 until dstChannels) prev[c] = scratch[c]
            started = true
        }
        val maxOut = ((frames / ratio).toInt() + 2) * dstChannels
        if (resampleOut.size < maxOut) resampleOut = ShortArray(maxOut)
        var p = pos
        var n = 0
        // Virtual stream: frame 0 is the last frame of the previous buffer, frame k (k >= 1) is this buffer's frame k-1.
        while (true) {
            val i = p.toInt()
            if (i + 1 > frames) break
            val frac = p - i
            for (c in 0 until dstChannels) {
                val a = if (i == 0) prev[c].toInt() else scratch[(i - 1) * dstChannels + c].toInt()
                val b = scratch[i * dstChannels + c].toInt()
                resampleOut[n++] = (a + (b - a) * frac).roundToInt().toShort()
            }
            p += ratio
        }
        pos = p - frames
        for (c in 0 until dstChannels) prev[c] = scratch[(frames - 1) * dstChannels + c]
        return resampleOut to n
    }
}

/** Tracks the loudest sample in each of [buckets] equal slices of the output, for drawing a waveform while converting. */
class PeakTracker(totalFrames: Long, private val buckets: Int = AudioPlanner.peakBuckets()) {
    private val perBucket = max(1L, totalFrames / buckets)
    private val peaks = ArrayList<Float>(buckets)
    private var current = 0
    private var inBucket = 0L

    /** Feeds interleaved samples; every [channels]-th sample starts a new frame. */
    fun add(samples: ShortArray, count: Int, channels: Int) {
        var f = 0
        while (f + channels <= count) {
            for (c in 0 until channels) current = max(current, kotlin.math.abs(samples[f + c].toInt()))
            f += channels
            if (++inBucket >= perBucket && peaks.size < buckets) {
                peaks += (current / 32768f).coerceIn(0f, 1f)
                current = 0
                inBucket = 0
            }
        }
    }

    fun snapshot(): List<Float> = peaks.toList() + if (inBucket > 0 && peaks.size < buckets) listOf((current / 32768f).coerceIn(0f, 1f)) else emptyList()
}
