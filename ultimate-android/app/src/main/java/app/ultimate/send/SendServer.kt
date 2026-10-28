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

data class Offered(val uri: Uri, val name: String, val bytes: Long, val mime: String)
data class Received(val name: String, val bytes: Long, val at: Long)

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
    data class State(val running: Boolean = false, val address: String? = null, val offered: List<Offered> = emptyList(), val received: List<Received> = emptyList(), val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var scope: CoroutineScope? = null
    private var socket: ServerSocket? = null
    private var code = SendHttp.newCode()

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
                launch { runCatching { client.use { handle(it) } } }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
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
        if (!req.path.startsWith(prefix)) return respond(out, 404, "text/plain", "Not found".toByteArray())
        val rest = req.path.removePrefix(prefix)
        when {
            req.method == "GET" && (rest == "" || rest == "/") -> respond(out, 200, "text/html; charset=utf-8", page().toByteArray())
            req.method == "GET" && rest.startsWith("/f/") -> {
                val f = _state.value.offered.getOrNull(rest.removePrefix("/f/").toIntOrNull() ?: -1) ?: return respond(out, 404, "text/plain", "Gone".toByteArray())
                context.contentResolver.openInputStream(f.uri)?.use { src ->
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: ${f.mime}\r\n" + (if (f.bytes > 0) "Content-Length: ${f.bytes}\r\n" else "") +
                        "Content-Disposition: attachment; filename*=UTF-8''${java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")}\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray()); src.copyTo(out); out.flush()
                    context.track("send.download")
                }
            }
            req.method == "PUT" && rest == "/upload" -> {
                val name = SendHttp.safeName(req.query["name"] ?: "file")
                val length = req.headers["content-length"]?.toLongOrNull() ?: return respond(out, 411, "text/plain", "Length required".toByteArray())
                save(name, req.headers["content-type"] ?: "application/octet-stream", input, length)
                _state.update { it.copy(received = listOf(Received(name, length, System.currentTimeMillis())) + it.received) }
                context.track("send.upload")
                respond(out, 200, "text/plain", "OK".toByteArray())
            }
            else -> respond(out, 404, "text/plain", "Not found".toByteArray())
        }
    }

    private fun save(name: String, mime: String, input: InputStream, length: Long) {
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

    private fun page(): String {
        val files = _state.value.offered.mapIndexed { i, f ->
            "<a class=f href=\"/$code/f/$i\" download><b>${SendHttp.escape(f.name)}</b><span>${app.ultimate.core.formatBytes(f.bytes)}</span></a>"
        }.joinToString("").ifEmpty { "<p class=m>Nothing offered yet. Pick files on the phone.</p>" }
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>Ultimate</title>
<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:40px auto;padding:0 16px;background:#111;color:#eee}h1{font-size:24px}
.f{display:flex;justify-content:space-between;padding:14px 16px;margin:8px 0;border-radius:14px;background:#222;color:#eee;text-decoration:none}.f span,.m{color:#999}
#drop{border:2px dashed #555;border-radius:16px;padding:32px;text-align:center;margin-top:24px;cursor:pointer}#drop.on{border-color:#9b8cff;background:#1b1830}
#log div{padding:6px 0;color:#9be59b}</style></head><body><h1>From your phone</h1>$files
<h1>To your phone</h1><div id=drop>Drop files here or click to choose<input id=pick type=file multiple hidden></div><div id=log></div>
<script>const d=document.getElementById('drop'),p=document.getElementById('pick'),log=document.getElementById('log');
d.onclick=()=>p.click();p.onchange=()=>send(p.files);d.ondragover=e=>{e.preventDefault();d.classList.add('on')};d.ondragleave=()=>d.classList.remove('on');
d.ondrop=e=>{e.preventDefault();d.classList.remove('on');send(e.dataTransfer.files)};
async function send(files){for(const f of files){const r=document.createElement('div');r.textContent='Sending '+f.name+'…';log.prepend(r);
const res=await fetch('/$code/upload?name='+encodeURIComponent(f.name),{method:'PUT',body:f,headers:{'Content-Type':f.type||'application/octet-stream'}});
r.textContent=(res.ok?'✓ ':'✗ ')+f.name+(res.ok?' is on your phone in Downloads/Ultimate':' failed');}}</script></body></html>"""
    }

    private fun wifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    companion object { const val PORT = 8686 }
}
