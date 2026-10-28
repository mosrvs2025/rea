package app.ultimate.send

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.ultimate.insights.track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

data class Offered(val uri: Uri, val name: String, val bytes: Long, val mime: String) {
    val id: String get() = java.security.MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
data class Received(val name: String, val bytes: Long, val at: Long, val uri: String = "", val mime: String = "application/octet-stream")

/** Pure pieces of the tiny web server, tested on their own. */
object SendHttp {
    /** A code that is hard to guess, so other people on the same Wi-Fi can't browse what is offered. */
    fun newCode(random: java.util.Random = SecureRandom()): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return String(CharArray(6) { alphabet[random.nextInt(alphabet.length)] })
    }

    data class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    fun parseHead(lines: List<String>): Request? {
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return Request(parts[0], path, query, headers)
    }

    /** A file name safe to save: no folders, no control characters. */
    fun safeName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\').filter { it >= ' ' && it !in "<>:\"|?*" }.trim().take(120).ifBlank { "file" }

    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

/**
 * A small web server on the phone's Wi-Fi address. A computer's browser opens the address shown on the phone to download what
 * the phone offers and to send files back, which land in Downloads/Ultimate. It runs only while turned on, and every page
 * needs the random code in its address.
 */
class SendServer(private val context: Context) {
    data class State(val running: Boolean = false, val address: String? = null, val offered: List<Offered> = emptyList(), val received: List<Received> = emptyList(), val error: String? = null, val camera: Boolean = false, val playRequest: Received? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    private var code = SendHttp.newCode()
    @Volatile private var cameraFrame: ByteArray? = null
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    fun camera(enabled: Boolean) { cameraFrame = null; _state.update { it.copy(camera = enabled) } }
    fun frame(bytes: ByteArray) { if (_state.value.camera) cameraFrame = bytes }
    fun dismissPlay() { _state.update { it.copy(playRequest = null) } }
    private fun status(): String {
        val app = context.applicationContext as app.ultimate.UltimateApp
        return org.json.JSONObject().apply {
            put("device", android.os.Build.MODEL); put("camera", _state.value.camera)
            put("files", org.json.JSONArray().apply { _state.value.offered.forEach { f -> put(org.json.JSONObject().put("id", f.id).put("name", f.name).put("bytes", f.bytes).put("mime", f.mime)) } })
            put("jobs", org.json.JSONArray().apply { app.work.sources.forEach { source -> val w = source.work.value
                put(org.json.JSONObject().put("name", source.workTitle).put("active", w.active).put("title", if (w.active) w.title else w.doneTitle ?: "Ready").put("percent", w.percent ?: -1))
            } })
        }.toString()
    }

    fun start() {
        if (_state.value.running) return
        val ip = wifiAddress() ?: run { _state.update { it.copy(error = "Connect to Wi-Fi first; your computer must be on the same network.") }; return }
        code = SendHttp.newCode()
        val server = runCatching { ServerSocket(PORT) }.recoverCatching { ServerSocket(0) }.getOrElse { _state.update { s -> s.copy(error = "Couldn't open a port: ${it.message}") }; return }
        socket = server
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        _state.update { it.copy(running = true, address = "http://$ip:${server.localPort}/$code", error = null) }
        s.launch {
            while (isActive) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                clients.add(client)
                launch { try { runCatching { client.use { handle(it) } } } finally { clients.remove(client) } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        clients.forEach { runCatching { it.close() } }; clients.clear()
        camera(false)
        scope?.cancel(); scope = null; socket = null
        _state.update { it.copy(running = false, address = null) }
    }

    fun offer(uris: List<Uri>) {
        val add = uris.mapNotNull { u ->
            runCatching {
                var name = "file"; var size = 0L
                context.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) } }
                Offered(u, name, size, context.contentResolver.getType(u) ?: "application/octet-stream")
            }.getOrNull()
        }
        _state.update { it.copy(offered = it.offered + add.filter { a -> it.offered.none { o -> o.uri == a.uri } }) }
    }

    fun unoffer(uri: Uri) = _state.update { it.copy(offered = it.offered.filterNot { o -> o.uri == uri }) }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val req = SendHttp.parseHead(readHead(input)) ?: return
        val prefix = "/$code"
        if (req.path != prefix && !req.path.startsWith("$prefix/")) return respond(out, 404, "text/plain", "Not found".toByteArray())
        val rest = req.path.removePrefix(prefix)
        when {
            req.method == "GET" && (rest == "" || rest == "/") -> respond(out, 200, "text/html; charset=utf-8", context.assets.open("companion.html").use { it.readBytes() })
            req.method == "GET" && rest == "/status" -> respond(out, 200, "application/json", status().toByteArray())
            req.method == "GET" && rest == "/camera" -> {
                if (!_state.value.camera) return respond(out, 409, "text/plain", "Enable camera on the phone first".toByteArray())
                out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                while (_state.value.running && _state.value.camera && !client.isClosed) {
                    cameraFrame?.let { jpeg ->
                        out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray()); out.write(jpeg); out.write("\r\n".toByteArray()); out.flush()
                    }
                    Thread.sleep(125)
                }
            }
            (req.method == "GET" || req.method == "HEAD") && rest.startsWith("/f/") -> {
                val f = _state.value.offered.firstOrNull { it.id == rest.removePrefix("/f/") } ?: return respond(out, 404, "text/plain", "Gone".toByteArray())
                val range = SendRange.parse(req.headers["range"], f.bytes)
                if (range == null) return respond(out, 416, "text/plain", "Range not satisfiable".toByteArray())
                context.contentResolver.openInputStream(f.uri)?.use { src ->
                    val partial = req.headers["range"] != null && f.bytes > 0
                    val previewable = f.mime.startsWith("video/") || f.mime.startsWith("audio/") || (f.mime.startsWith("image/") && f.mime != "image/svg+xml")
                    val disposition = if (req.query["download"] == "1" || !previewable) "attachment" else "inline"
                    val length = if (f.bytes > 0) range.last - range.first + 1 else -1
                    val head = "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: ${f.mime}\r\n" +
                        (if (length >= 0) "Content-Length: $length\r\nAccept-Ranges: bytes\r\n" else "") +
                        (if (partial) "Content-Range: bytes ${range.first}-${range.last}/${f.bytes}\r\n" else "") +
                        "Content-Disposition: $disposition; filename*=UTF-8''${java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")}\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray())
                    if (req.method != "HEAD") {
                        var skip = range.first
                        while (skip > 0) { val n = src.skip(skip); if (n > 0) skip -= n else { check(src.read() >= 0); skip-- } }
                        if (length < 0) src.copyTo(out) else {
                            var left = length; val buffer = ByteArray(64 * 1024)
                            while (left > 0) { val n = src.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt()); if (n < 0) break; out.write(buffer, 0, n); left -= n }
                        }
                    }
                    out.flush()
                    if (req.method == "GET" && range.first == 0L) context.track("send.download")
                } ?: respond(out, 404, "text/plain", "File unavailable".toByteArray())
            }
            req.method == "PUT" && rest == "/upload" -> {
                val name = SendHttp.safeName(req.query["name"] ?: "file")
                val length = req.headers["content-length"]?.toLongOrNull() ?: return respond(out, 411, "text/plain", "Length required".toByteArray())
                if (length < 0) return respond(out, 400, "text/plain", "Invalid length".toByteArray())
                val mime = req.headers["content-type"] ?: "application/octet-stream"
                val uri = save(name, mime, input, length)
                val received = Received(name, length, System.currentTimeMillis(), uri.toString(), mime)
                context.track("send.upload")
                _state.update { it.copy(received = (listOf(received) + it.received).take(100), playRequest = if (req.query["play"] == "1" && (mime.startsWith("audio/") || mime.startsWith("video/"))) received else it.playRequest) }
                respond(out, 200, "text/plain", "OK".toByteArray())
            }
            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun save(name: String, mime: String, input: InputStream, length: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Ultimate"); put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no storage")
        try {
            resolver.openOutputStream(uri)!!.use { dst ->
                val buf = ByteArray(256 * 1024); var left = length
                while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; dst.write(buf, 0, n); left -= n }
                check(left == 0L) { "upload cut short" }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }

    private fun readHead(input: InputStream): List<String> {
        val lines = mutableListOf<String>(); val line = StringBuilder()
        while (lines.size < 100) {
            val b = input.read(); if (b < 0) break
            if (b == '\n'.code) { val l = line.toString().trimEnd('\r'); if (l.isEmpty()) break; lines += l; line.clear() } else if (line.length < 8192) line.append(b.toChar())
        }
        return lines
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray) {
        out.write("HTTP/1.1 $status OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    private fun wifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    companion object { const val PORT = 8686 }
}
