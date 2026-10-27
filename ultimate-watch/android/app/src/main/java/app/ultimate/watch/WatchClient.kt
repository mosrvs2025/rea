package app.ultimate.watch

import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocketFactory

/** Why talking to the server failed, in the terms that decide what to do next. */
sealed class WatchError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Retrying cannot help: the password, address or certificate must be fixed by a person. */
    class Fatal(message: String, cause: Throwable? = null) : WatchError(message, cause)
    /** The server no longer knows our session (it restarted); log in again straight away. */
    class SessionExpired : WatchError("Session expired")
    /** The server refused one frame; drop it and carry on. */
    class FrameRejected(code: Int) : WatchError("Server refused a frame ($code)")
    /** The network or server is unavailable right now; retry with backoff. */
    class Transient(message: String, cause: Throwable? = null) : WatchError(message, cause)
}

/** Maps anything thrown while talking to the server onto [WatchError]. Messages never contain the password or request bodies. */
fun classify(t: Throwable): WatchError = when {
    t is WatchError -> t
    isCertificateProblem(t) ->
        WatchError.Fatal("The server's certificate is not trusted on this phone. Install your certificate authority in Android settings, then start again.", t)
    t is IOException -> WatchError.Transient(t.message?.take(80) ?: t.javaClass.simpleName, t)
    else -> WatchError.Transient(t.javaClass.simpleName, t)
}

/** True only for real trust or host-name failures; a connection dropped mid-handshake is also an SSLException but is worth retrying. */
private fun isCertificateProblem(t: Throwable): Boolean = generateSequence(t) { it.cause }.take(8).any {
    it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException
}

/** Talks to the Ultimate Watch server: log in once, then post JPEG frames with the session cookie. */
class WatchClient(
    private val endpoint: Endpoint,
    private val camera: String,
    private val password: String,
    private val tls: SSLSocketFactory? = null,
) {
    @Volatile private var cookie: String? = null

    fun forgetSession() {
        cookie = null
    }

    @Throws(WatchError::class)
    fun login() {
        try {
            val c = open("/login")
            try {
                c.requestMethod = "POST"
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body = ("password=" + URLEncoder.encode(password, "UTF-8")).toByteArray()
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
                when (val code = c.responseCode) {
                    200 -> cookie = c.headerFields["Set-Cookie"].orEmpty().firstOrNull { it.startsWith("watch_session=") }?.substringBefore(';')
                        ?: throw WatchError.Transient("Server sent no session")
                    401 -> throw WatchError.Fatal("The server rejected the password")
                    403 -> throw WatchError.Fatal("The server refused this address (origin check)")
                    else -> throw WatchError.Transient("Server answered $code")
                }
            } finally {
                c.disconnect()
            }
        } catch (t: Throwable) {
            throw classify(t)
        }
    }

    @Throws(WatchError::class)
    fun postFrame(jpeg: ByteArray) {
        try {
            val c = open("/api/frame/$camera")
            try {
                c.requestMethod = "POST"
                c.setRequestProperty("Content-Type", "image/jpeg")
                c.doOutput = true
                c.setFixedLengthStreamingMode(jpeg.size)
                c.outputStream.use { it.write(jpeg) }
                when (val code = c.responseCode) {
                    204 -> Unit
                    401 -> throw WatchError.SessionExpired()
                    400, 413, 415 -> throw WatchError.FrameRejected(code)
                    403 -> throw WatchError.Fatal("The server refused this address (origin check)")
                    else -> throw WatchError.Transient("Server answered $code")
                }
            } finally {
                c.disconnect()
            }
        } catch (t: Throwable) {
            throw classify(t)
        }
    }

    private fun open(path: String): HttpsURLConnection {
        val c = URL(endpoint.origin + path).openConnection() as HttpsURLConnection
        tls?.let { c.sslSocketFactory = it }
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.instanceFollowRedirects = false
        // The server rejects state-changing requests whose Origin differs from its own address.
        c.setRequestProperty("Origin", endpoint.origin)
        cookie?.let { c.setRequestProperty("Cookie", it) }
        return c
    }
}
