package app.ultimate.pluck.engine

import app.ultimate.core.*
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.ultimate.pluck.model.AppItem
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class Extracted(val uri: Uri, val displayName: String, val bytes: Long, val splitCount: Int, val sha256: String)

/**
 * Copies an app's APKs into Downloads/Pluck. A single APK is saved as-is; an app with split APKs is saved as one `.apks`
 * archive holding every split, which split installers can read. The SHA-256 is of the base APK as installed.
 */
class Extractor(private val context: Context) {

    suspend fun extract(app: AppItem, onProgress: (done: Long, total: Long) -> Unit): Extracted = withContext(Dispatchers.IO) {
        val bundle = app.isSplit
        val name = fileName(app) + if (bundle) ".apks" else ".apk"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, if (bundle) "application/octet-stream" else "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Pluck")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create the output file")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val progress = Progress(app.apkBytes, onProgress)
            resolver.openOutputStream(uri)?.use { out ->
                if (bundle) writeBundle(app, out, digest, progress) else copy(File(app.apkPaths.first()), out, digest, progress)
            } ?: throw IOException("Could not open the output file")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Extracted(uri, displayName(uri) ?: name, outputSize(uri), app.apkPaths.size, AppRepository.hex(digest.digest()))
        } catch (e: Throwable) {
            // Includes cancellation: never leave a half-written file behind.
            withContext(NonCancellable) { runCatching { resolver.delete(uri, null, null) } }
            throw e
        }
    }

    private suspend fun writeBundle(app: AppItem, out: OutputStream, baseDigest: MessageDigest, progress: Progress) {
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION) // APKs are already compressed
            app.apkPaths.forEachIndexed { i, path ->
                val file = File(path)
                zip.putNextEntry(ZipEntry(file.name))
                copy(file, zip, if (i == 0) baseDigest else null, progress)
                zip.closeEntry()
            }
        }
    }

    private suspend fun copy(file: File, out: OutputStream, digest: MessageDigest?, progress: Progress) {
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest?.update(buffer, 0, n)
                out.write(buffer, 0, n)
                progress.add(n)
            }
        }
    }

    private fun displayName(uri: Uri): String? = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun outputSize(uri: Uri): Long = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
        ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L

    private class Progress(private val total: Long, private val report: (Long, Long) -> Unit) {
        private var done = 0L
        fun add(n: Int) {
            done += n
            report(done, total)
        }
    }

    companion object {
        /** "Label_1.2.3": keeps readable characters, replaces anything a filesystem or share target could choke on. */
        fun fileName(app: AppItem): String {
            val raw = "${app.label}_${app.versionName}".trim()
            val cleaned = raw.map { if (it.isLetterOrDigit() || it in "._-+()") it else '_' }.joinToString("").trim('_', '.')
            return cleaned.ifBlank { app.packageName }.take(120)
        }
    }
}
