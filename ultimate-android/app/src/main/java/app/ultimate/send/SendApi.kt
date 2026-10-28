package app.ultimate.send

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp

import app.ultimate.files.FileModel
import app.ultimate.files.hasAllFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The companion page's data: phone status, the Library, clipboard both ways, and, only when switched on in the app,
 * notifications and the phone's files. Every request has already passed the address name or a remembered-computer cookie.
 */
class SendApi(private val context: Context, private val server: SendServer) {
    private val app get() = context.applicationContext as UltimateApp
    private val prefs = context.getSharedPreferences("send", Context.MODE_PRIVATE)
    private val root: File get() = Environment.getExternalStorageDirectory()

    var shareNotifications: Boolean
        get() = prefs.getBoolean("shareNotifications", false)
        set(v) = prefs.edit().putBoolean("shareNotifications", v).apply()
    var shareFiles: Boolean
        get() = prefs.getBoolean("shareFiles", true)
        set(v) = prefs.edit().putBoolean("shareFiles", v).apply()

    fun handle(req: SendHttp.Request, path: String, input: InputStream, out: OutputStream) {
        fun json(o: Any) = server.respond(out, 200, "application/json; charset=utf-8", o.toString().toByteArray())
        fun deny(why: String) = server.respond(out, 403, "application/json", JSONObject().put("error", why).toString().toByteArray())
        when {
            req.method == "GET" && path == "/phone" -> json(phone())
            req.method == "GET" && path == "/library" -> json(library())
            req.method == "GET" && path == "/library/file" -> {
                val item = app.tools.flatMap { it.library.value }.firstOrNull { it.id == req.query["id"] && it.uri != null } ?: return deny("not found")
                val uri = Uri.parse(item.uri)
                val bytes = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it > 0 } ?: item.bytes
                server.stream(out, req, item.title, item.mimeType, bytes) { context.contentResolver.openInputStream(uri) }
            }
            req.method == "GET" && path == "/clips" -> json(JSONArray().apply { app.clips.clips.value.take(200).forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at).put("kind", it.kind.label).put("pinned", it.pinned)) } })
            req.method == "POST" && path == "/clips" -> {
                val text = body(req, input, 200_000) ?: return deny("too long")
                app.clips.add(text, "computer")
                // Writing the clipboard is allowed from the background (only reading is not), so the phone can paste it right away.
                Handler(Looper.getMainLooper()).post { runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From computer", text)) } }
                json(JSONObject().put("ok", true))
            }
            req.method == "GET" && path == "/notifications" -> {
                if (!shareNotifications || !BuildConfig.HAS_INBOX) return deny("Turn on \"Show notifications on computer\" in Ultimate first.")
                json(JSONArray().apply { app.notificationLog.search(req.query["q"].orEmpty(), null, false, 300).forEach { n ->
                    put(JSONObject().put("app", n.app).put("pkg", n.pkg).put("at", n.at).put("title", n.title).put("text", n.text).put("pulled", n.removedByApp))
                } })
            }
            path.startsWith("/files") -> {
                if (!shareFiles || !hasAllFiles(context)) return deny("Allow file browsing in Ultimate's Send to computer, and all files access in Files.")
                val target = SendApiPaths.resolve(root, req.query["path"].orEmpty()) ?: return deny("outside storage")
                when {
                    req.method == "GET" && path == "/files" -> json(listing(target))
                    req.method == "GET" && path == "/files/raw" && target.isFile -> server.stream(out, req, target.name, FileModel.mimeOf(target), target.length()) { target.inputStream() }
                    req.method == "PUT" && path == "/files/upload" && target.isDirectory -> {
                        val name = SendHttp.safeName(req.query["name"] ?: "file")
                        val length = req.headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return deny("length required")
                        val dest = File(target, FileModel.freeName(target, name))
                        dest.outputStream().use { o -> val buf = ByteArray(256 * 1024); var left = length; while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n }; check(left == 0L) { "upload cut short" } }
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
                        json(JSONObject().put("ok", true).put("name", dest.name))
                    }
                    else -> deny("bad request")
                }
            }
            else -> server.respond(out, 404, "application/json", "{}".toByteArray())
        }
    }

    private fun body(req: SendHttp.Request, input: InputStream, max: Int): String? {
        val n = req.headers["content-length"]?.toIntOrNull() ?: return null
        if (n > max) return null
        val buf = ByteArray(n); var read = 0
        while (read < n) { val r = input.read(buf, read, n - read); if (r < 0) break; read += r }
        return String(buf, 0, read)
    }

    private fun phone(): JSONObject {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val stat = StatFs(Environment.getDataDirectory().path)
        return JSONObject().put("model", "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}")
            .put("battery", level).put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            .put("free", stat.availableBytes).put("total", stat.totalBytes).put("version", BuildConfig.VERSION_NAME)
            .put("notifications", shareNotifications && BuildConfig.HAS_INBOX).put("files", shareFiles && hasAllFiles(context))
            .put("clips", BuildConfig.HAS_CLIPS)
    }

    private fun library() = JSONArray().apply {
        app.tools.forEach { t -> t.library.value.filter { it.uri != null }.forEach { i ->
            put(JSONObject().put("id", i.id).put("tool", t.title).put("title", i.title).put("subtitle", i.subtitle).put("mime", i.mimeType).put("bytes", i.bytes).put("at", i.createdAt))
        } }
    }

    private fun listing(dir: File) = JSONObject().put("path", dir.relativeTo(root).path).put("entries", JSONArray().apply {
        dir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).take(2000).forEach { f ->
            put(JSONObject().put("name", f.name).put("dir", f.isDirectory).put("bytes", if (f.isFile) f.length() else 0).put("at", f.lastModified())
                .put("kind", FileModel.kindOf(f).name).put("mime", if (f.isFile) FileModel.mimeOf(f) else ""))
        }
    })
}

/** Keeps every browser path inside shared storage, whatever "../" tricks it contains. */
object SendApiPaths {
    fun resolve(root: File, relative: String): File? {
        val f = File(root, relative.trimStart('/')).canonicalFile
        return f.takeIf { FileModel.isInside(it, root.canonicalFile) }
    }
}

