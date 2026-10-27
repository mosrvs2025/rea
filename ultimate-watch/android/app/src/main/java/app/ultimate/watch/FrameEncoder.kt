package app.ultimate.watch

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** Turns a camera frame into an upright JPEG. */
object FrameEncoder {
    fun jpeg(image: ImageProxy, quality: Int = 65): ByteArray {
        // toBitmap() honours the row padding of RGBA_8888 planes, so no stripe appears at the right edge.
        val bitmap = image.toBitmap()
        val degrees = image.imageInfo.rotationDegrees
        val upright = if (degrees == 0) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        val out = ByteArrayOutputStream(64 * 1024)
        upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (upright !== bitmap) upright.recycle()
        bitmap.recycle()
        return out.toByteArray()
    }
}
