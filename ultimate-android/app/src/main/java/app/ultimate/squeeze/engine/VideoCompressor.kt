package app.ultimate.squeeze.engine

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.ultimate.squeeze.model.Plan
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs one hardware-accelerated transcode according to a [Plan]. Must be called from a coroutine; it hops to the main looper. */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoCompressor(private val context: Context) {

    suspend fun compress(
        input: Uri,
        output: File,
        plan: Plan,
        sourceIsHdr: Boolean,
        muteAudio: Boolean,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val encoders = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(plan.videoBitrate)
                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        .setiFrameIntervalSeconds(2f)
                        .build(),
                )
                .setEnableFallback(true)
                .build()

            lateinit var poll: Runnable
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(plan.codec.mime)
                .setEncoderFactory(encoders)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        handler.removeCallbacks(poll)
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()

            val effects = buildList<Effect> {
                plan.fps?.let { add(FrameDropEffect.createDefaultFrameDropEffect(it.toFloat())) }
                if (plan.resize) add(Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT))
            }
            val item = EditedMediaItem.Builder(MediaItem.fromUri(input))
                .setRemoveAudio(muteAudio)
                .setEffects(Effects(emptyList(), effects))
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build())
                .apply { if (sourceIsHdr) setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
                .build()

            val holder = ProgressHolder()
            poll = Runnable {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                handler.postDelayed(poll, 400)
            }

            transformer.start(composition, output.absolutePath)
            handler.post(poll)
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                }
            }
        }
    }
}
