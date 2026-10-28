package app.ultimate.send

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Camera access starts only from the phone UI and ends when this screen leaves the foreground. */
@Composable
fun CompanionCamera(server: SendServer, front: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, front) {
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var analysis: ImageAnalysis? = null
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (!disposed) try {
                val cameraProvider = future.get(); provider = cameraProvider
                val stream = ImageAnalysis.Builder().setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
                analysis = stream
                var last = 0L
                stream.setAnalyzer(executor) { image ->
                    try {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - last >= 120) {
                            last = now
                            val plane = image.planes[0]
                            val paddedWidth = plane.rowStride / plane.pixelStride
                            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                            padded.copyPixelsFromBuffer(plane.buffer)
                            val crop = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                            val rotated = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
                            val bytes = ByteArrayOutputStream().use { output -> rotated.compress(Bitmap.CompressFormat.JPEG, 65, output); output.toByteArray() }
                            server.frame(bytes)
                            if (rotated !== crop && rotated !== padded) rotated.recycle()
                            if (crop !== padded) crop.recycle()
                            padded.recycle()
                        }
                    } catch (_: Exception) { /* A dropped frame does not end the session. */ }
                    finally { image.close() }
                }
                cameraProvider.bindToLifecycle(owner, if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA, stream)
            } catch (_: Exception) { server.camera(false) }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            analysis?.let { it.clearAnalyzer(); provider?.unbind(it) }
            executor.shutdownNow()
        }
    }
}
