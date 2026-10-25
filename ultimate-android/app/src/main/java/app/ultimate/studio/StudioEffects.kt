package app.ultimate.studio

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer

@androidx.annotation.OptIn(UnstableApi::class)
object StudioEffects {
    fun mediaItem(c: StudioClip): MediaItem = MediaItem.Builder().setUri(c.uri)
        .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.startMs).setEndPositionMs(c.endMs).build()).build()
    fun video(c: StudioClip, p: StudioProject, exporting: Boolean): List<Effect> = buildList {
        if (c.rotation != 0) add(ScaleAndRotateTransformation.Builder().setRotationDegrees(c.rotation.toFloat()).build())
        when (c.look) {
            Look.Natural -> Unit
            Look.Cinema -> { add(Contrast(0.18f)); add(RgbAdjustment.Builder().setRedScale(1.04f).setBlueScale(0.94f).build()) }
            Look.Warm -> add(RgbAdjustment.Builder().setRedScale(1.12f).setGreenScale(1.02f).setBlueScale(0.88f).build())
            Look.Noir -> { add(RgbFilter.createGrayscaleFilter()); add(Contrast(0.25f)) }
        }
        if (c.brightness != 0f) add(Brightness(c.brightness))
        add(Presentation.createForWidthAndHeight(p.width, p.height, if (p.fillCanvas) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT))
        if (c.title.isNotBlank()) {
            val wrapped = c.title.lineSequence().flatMap { line -> line.chunked(26).asSequence() }.joinToString("\n")
            val text = SpannableString(wrapped).apply {
                setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(BackgroundColorSpan(0xA6000000.toInt()), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val y = when(c.titlePosition) { 1 -> 0.65f; 2 -> -0.65f; else -> 0f }
            val settings = OverlaySettings.Builder().setScale(p.width / 2000f, p.width / 2000f).setBackgroundFrameAnchor(0f, y).build()
            add(OverlayEffect(ImmutableList.of<TextureOverlay>(TextOverlay.createStaticTextOverlay(text, settings))))
        }
        if (exporting && c.speed != 1f) add(SpeedChangeEffect(c.speed))
        if (exporting) add(FrameDropEffect.createDefaultFrameDropEffect(30f))
    }
    fun audio(speed: Float, volume: Float): List<AudioProcessor> = buildList {
        if (speed != 1f) add(SonicAudioProcessor().apply { setSpeed(speed); setPitch(1f) })
        if (volume != 1f) add(GainProcessor(volume))
    }
    fun effects(c: StudioClip, p: StudioProject) = Effects(audio(c.speed, c.volume), video(c, p, true))
}

/** Gain applied to interleaved PCM; exports use the same gain shown by the preview slider. */
@androidx.annotation.OptIn(UnstableApi::class)
class GainProcessor(private val gain: Float) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) while (inputBuffer.remaining() >= 4) out.putFloat((inputBuffer.float * gain).coerceIn(-1f, 1f))
        else while (inputBuffer.remaining() >= 2) out.putShort((inputBuffer.short * gain).toInt().coerceIn(-32768, 32767).toShort())
        out.flip()
    }
}
