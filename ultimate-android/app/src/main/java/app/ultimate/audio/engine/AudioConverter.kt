package app.ultimate.audio.engine

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat as PlatformAudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.audio.model.OutFormat
import app.ultimate.audio.model.PcmMixer
import app.ultimate.audio.model.PeakTracker
import de.sciss.jump3r.lowlevel.LameEncoder
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.sound.sampled.AudioFormat
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class Converted(
    val uri: Uri,
    val displayName: String,
    val bytes: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val peaks: List<Float>,
    val format: OutFormat,
)

/**
 * Turns the audio track of a video into MP3, WAV or an untouched M4A, saved to Music/Ultimate.
 * MP3 is encoded with a Java port of LAME; the video's audio is decoded with the phone's own decoders.
 */
class AudioConverter(private val context: Context) {

    suspend fun convert(
        input: Uri,
        inputName: String,
        src: AudioSource,
        settings: AudioSettings,
        onProgress: (fraction: Float, peaks: List<Float>) -> Unit,
    ): Converted = withContext(Dispatchers.IO) {
        val format = AudioPlanner.effectiveFormat(src, settings)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, baseName(inputName) + "." + format.ext)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Ultimate")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create the output file")
        try {
            val kept = AudioPlanner.keptMs(src, settings)
            if (kept <= 0) throw IOException("Nothing left after trimming")
            val outChannels = AudioPlanner.outputChannels(src, settings)
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IOException("Could not open the output file")
            val facts = pfd.use { fd ->
                when (format) {
                    OutFormat.Mp3 -> toMp3(input, src, settings, outChannels, fd, onProgress)
                    OutFormat.Wav -> toWav(input, src, settings, outChannels, fd, onProgress)
                    OutFormat.M4a -> copyAac(input, src, settings, fd, onProgress)
                }
            }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            val bytes = size(uri)
            Converted(
                uri, displayName(uri) ?: (baseName(inputName) + "." + format.ext), bytes, kept, facts.sampleRate, facts.channels,
                if (kept > 0) (bytes * 8000 / kept).toInt() else 0, facts.peaks, format,
            )
        } catch (e: Throwable) {
            // Includes cancellation: never leave a half-written file behind.
            withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
            throw e
        }
    }

    private data class Facts(val sampleRate: Int, val channels: Int, val peaks: List<Float>)

    // ─────────────────────────────── MP3 ───────────────────────────────

    private suspend fun toMp3(
        input: Uri, src: AudioSource, s: AudioSettings, outChannels: Int, fd: ParcelFileDescriptor,
        onProgress: (Float, List<Float>) -> Unit,
    ): Facts {
        val rate = AudioPlanner.mp3Rate(src.sampleRate)
        var encoder: LameEncoder? = null
        var mixer: PcmMixer? = null
        var pending = ByteArray(0)
        var pendingLen = 0
        var mp3 = ByteArray(0)
        var effectiveRate = rate
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            fun flushChunk(final: Boolean) {
                val enc = encoder ?: return
                if (pendingLen > 0) {
                    val n = enc.encodeBuffer(pending, 0, pendingLen, mp3)
                    out.write(mp3, 0, n)
                    pendingLen = 0
                }
                if (final) out.write(mp3, 0, enc.encodeFinish(mp3))
            }
            val tracker = decode(input, src, s, onProgress) { pcm, count, srcRate, srcChannels ->
                if (encoder == null) {
                    effectiveRate = AudioPlanner.mp3Rate(srcRate)
                    val format = AudioFormat(effectiveRate.toFloat(), 16, outChannels, true, false)
                    encoder = LameEncoder(
                        format, s.kbps,
                        if (outChannels == 1) LameEncoder.CHANNEL_MODE_MONO else LameEncoder.CHANNEL_MODE_AUTO,
                        LameEncoder.QUALITY_MIDDLE, false,
                    ).also {
                        pending = ByteArray(it.getPCMBufferSize())
                        mp3 = ByteArray(it.getMP3BufferSize())
                    }
                    mixer = PcmMixer(srcChannels, outChannels, srcRate, effectiveRate)
                }
                val (samples, n) = mixer!!.process(pcm, count)
                var i = 0
                while (i < n) {
                    if (pendingLen + 2 > pending.size) flushChunk(false)
                    val room = (pending.size - pendingLen) / 2
                    val take = min(room, n - i)
                    for (k in 0 until take) {
                        val v = samples[i + k].toInt()
                        pending[pendingLen++] = v.toByte()
                        pending[pendingLen++] = (v shr 8).toByte()
                    }
                    i += take
                }
            }
            flushChunk(true)
            encoder?.close()
            return Facts(effectiveRate, outChannels, tracker.snapshot())
        }
    }

    // ─────────────────────────────── WAV ───────────────────────────────

    private suspend fun toWav(
        input: Uri, src: AudioSource, s: AudioSettings, outChannels: Int, fd: ParcelFileDescriptor,
        onProgress: (Float, List<Float>) -> Unit,
    ): Facts {
        var rate = src.sampleRate
        var dataBytes = 0L
        var mixer: PcmMixer? = null
        ParcelFileDescriptor.AutoCloseOutputStream(fd).use { out ->
            out.write(ByteArray(44)) // header is filled in once the length is known
            val bytes = ByteArray(64 * 1024)
            val tracker = decode(input, src, s, onProgress) { pcm, count, srcRate, srcChannels ->
                if (mixer == null) {
                    rate = srcRate
                    mixer = PcmMixer(srcChannels, outChannels, srcRate, srcRate)
                }
                val (samples, n) = mixer!!.process(pcm, count)
                var i = 0
                while (i < n) {
                    val take = min(bytes.size / 2, n - i)
                    for (k in 0 until take) {
                        val v = samples[i + k].toInt()
                        bytes[2 * k] = v.toByte()
                        bytes[2 * k + 1] = (v shr 8).toByte()
                    }
                    out.write(bytes, 0, take * 2)
                    dataBytes += take * 2
                    i += take
                }
            }
            out.channel.position(0)
            out.write(wavHeader(dataBytes, rate, outChannels))
            return Facts(rate, outChannels, tracker.snapshot())
        }
    }

    private fun wavHeader(dataBytes: Long, rate: Int, channels: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(dataBytes.toInt())
        return b.array()
    }

    // ─────────────────────────── M4A (copy) ────────────────────────────

    /** Draws the waveform from a quick decode, then copies the compressed AAC frames across unchanged. */
    private suspend fun copyAac(
        input: Uri, src: AudioSource, s: AudioSettings, fd: ParcelFileDescriptor,
        onProgress: (Float, List<Float>) -> Unit,
    ): Facts {
        val tracker = decode(input, src, s, { f, p -> onProgress(f * 0.9f, p) }) { _, _, _, _ -> }
        val peaks = tracker.snapshot()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(context, input, null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val startUs = s.trimStartMs.coerceAtLeast(0) * 1000
            val endUs = if (s.trimEndMs > 0) s.trimEndMs * 1000 else Long.MAX_VALUE
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            muxer = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val out = muxer.addTrack(format)
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(max(1 shl 16, if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0))
            val info = MediaCodec.BufferInfo()
            var base = -1L
            while (true) {
                coroutineContext.ensureActive()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                if (t > endUs) break
                if (base < 0) base = t
                info.set(0, size, t - base, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(out, buffer, info)
                extractor.advance()
            }
            onProgress(1f, peaks)
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            extractor.release()
        }
        return Facts(src.sampleRate, src.channels, peaks)
    }

    // ────────────────────────────── decoding ───────────────────────────

    /**
     * Decodes the audio track between the trim points and hands interleaved 16-bit PCM to [sink].
     * Returns the waveform of everything that was decoded.
     */
    private suspend fun decode(
        input: Uri,
        src: AudioSource,
        s: AudioSettings,
        onProgress: (Float, List<Float>) -> Unit,
        sink: (pcm: ShortArray, count: Int, sampleRate: Int, channels: Int) -> Unit,
    ): PeakTracker {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, input, null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val startUs = s.trimStartMs.coerceAtLeast(0) * 1000
            val endUs = if (s.trimEndMs > 0) min(s.trimEndMs, src.durationMs) * 1000 else src.durationMs * 1000
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val tracker = PeakTracker(totalFrames = (endUs - startUs) * rate / 1_000_000)
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            dec.configure(format, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            var scratch = ShortArray(0)
            var inputDone = false
            var outputDone = false
            var lastReport = 0L
            val span = max(endUs - startUs, 1)
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val i = dec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(dec.getInputBuffer(i)!!, 0)
                        val t = extractor.sampleTime
                        if (n < 0 || t > endUs) {
                            dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(i, 0, n, t, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = dec.dequeueOutputBuffer(info, 5_000)
                when {
                    o >= 0 -> {
                        if (info.size > 0) {
                            val buf = dec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset).limit(info.offset + info.size)
                            val total = if (floatPcm) info.size / 4 else info.size / 2
                            val frames = total / channels
                            // Decoding starts at the previous sync frame, so drop whatever precedes the trim start,
                            // and whatever runs past the trim end.
                            val skip = if (info.presentationTimeUs < startUs) min(frames.toLong(), (startUs - info.presentationTimeUs) * rate / 1_000_000).toInt() else 0
                            val room = ((endUs - info.presentationTimeUs).coerceAtLeast(0) * rate / 1_000_000).toInt()
                            val keep = max(0, min(frames - skip, room))
                            if (keep > 0) {
                                val shorts = keep * channels
                                if (scratch.size < shorts) scratch = ShortArray(shorts)
                                if (floatPcm) {
                                    val fb = buf.asFloatBuffer()
                                    fb.position(skip * channels)
                                    for (k in 0 until shorts) scratch[k] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                                } else {
                                    val sb = buf.asShortBuffer()
                                    sb.position(skip * channels)
                                    sb.get(scratch, 0, shorts)
                                }
                                tracker.add(scratch, shorts, channels)
                                sink(scratch, shorts, rate, channels)
                            }
                            val now = System.nanoTime()
                            if (now - lastReport > 120_000_000L) {
                                lastReport = now
                                onProgress(((info.presentationTimeUs - startUs).toFloat() / span).coerceIn(0f, 1f), tracker.snapshot())
                            }
                        }
                        dec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = dec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == PlatformAudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            }
            onProgress(1f, tracker.snapshot())
            return tracker
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun displayName(uri: Uri): String? = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun size(uri: Uri): Long = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
        ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    companion object {
        fun baseName(original: String): String =
            original.substringBeforeLast('.', original).map { if (it.isLetterOrDigit() || it in " ._-+()") it else '_' }.joinToString("").trim().ifBlank { "audio" }.take(120)
    }
}
