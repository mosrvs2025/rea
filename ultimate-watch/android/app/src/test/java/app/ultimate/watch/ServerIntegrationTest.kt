package app.ultimate.watch

import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Drives [WatchClient] against the real Python server over real TLS, with the test certificate as the only trusted authority.
 * Skipped when python3 with aiohttp or openssl is unavailable.
 */
class ServerIntegrationTest {
    private val password = "a strong testing password"
    private lateinit var dir: File
    private lateinit var tls: SSLSocketFactory
    private var port = 0
    private var server: Process? = null

    companion object {
        @BeforeClass @JvmStatic fun allowOriginHeader() {
            // The JDK's HttpURLConnection drops "Origin" unless told otherwise; Android's implementation sends it.
            System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
        }
        private fun ok(vararg cmd: String) = runCatching { ProcessBuilder(*cmd).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0 }.getOrDefault(false)
    }

    @Before fun setUp() {
        val serverPy = File("../../server.py").canonicalFile
        assumeTrue("server.py not found", serverPy.exists())
        assumeTrue("python3 with aiohttp needed", ok("python3", "-c", "import aiohttp"))
        assumeTrue("openssl needed", ok("openssl", "version"))
        dir = kotlin.io.path.createTempDirectory("watch-it").toFile()
        val cert = File(dir, "c.pem"); val key = File(dir, "k.pem")
        assumeTrue("could not create a test certificate", ok("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key.path, "-out", cert.path, "-days", "1",
            "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1"))
        val chain = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cert.readBytes()))
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("test", chain) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        tls = SSLContext.getInstance("TLS").apply { init(null, tm.trustManagers, null) }.socketFactory
        port = ServerSocket(0).use { it.localPort }
        launchServer(serverPy, cert, key)
    }

    private fun launchServer(serverPy: File, cert: File = File(dir, "c.pem"), key: File = File(dir, "k.pem")) {
        server = ProcessBuilder("python3", serverPy.path, "--cert", cert.path, "--key", key.path, "--port", port.toString())
            .redirectErrorStream(true).redirectOutput(File(dir, "server.log")).apply { environment()["WATCH_PASSWORD"] = password }.start()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess) return
            Thread.sleep(100)
        }
        fail("server did not start: " + File(dir, "server.log").readText())
    }

    @After fun tearDown() {
        server?.destroy(); server?.waitFor()
        if (::dir.isInitialized) dir.deleteRecursively()
    }

    private fun client(pw: String = password, camera: String = "porch") =
        WatchClient(Endpoint.parse("https://localhost:$port").getOrThrow(), camera, pw, tls)

    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())

    @Test fun logsInAndPostsFrames() {
        val c = client(); c.login(); c.postFrame(jpeg); c.postFrame(jpeg)
    }

    @Test fun wrongPasswordIsFatalAndNotRetryable() {
        val e = runCatching { client("x".repeat(20)).login() }.exceptionOrNull()
        assertTrue("was $e", e is WatchError.Fatal)
    }

    @Test fun postingWithoutLoginIsSessionExpired() {
        val e = runCatching { client().postFrame(jpeg) }.exceptionOrNull()
        assertTrue("was $e", e is WatchError.SessionExpired)
    }

    @Test fun invalidJpegIsRejectedWithoutKillingTheSession() {
        val c = client(); c.login()
        val e = runCatching { c.postFrame(byteArrayOf(1, 2, 3)) }.exceptionOrNull()
        assertTrue("was $e", e is WatchError.FrameRejected)
        c.postFrame(jpeg)
    }

    @Test fun untrustedCertificateIsFatal() {
        val e = runCatching { WatchClient(Endpoint.parse("https://localhost:$port").getOrThrow(), "porch", password).login() }.exceptionOrNull()
        assertTrue("was $e", e is WatchError.Fatal)
    }

    @Test fun serverRestartIsSurvivedByLoggingInAgain() {
        val c = client(); c.login(); c.postFrame(jpeg)
        server!!.destroy(); server!!.waitFor()
        val down = runCatching { c.postFrame(jpeg) }.exceptionOrNull()
        assertTrue("while down: $down", down is WatchError.Transient)
        launchServer(File("../../server.py").canonicalFile)
        val expired = runCatching { c.postFrame(jpeg) }.exceptionOrNull()
        assertTrue("after restart: $expired", expired is WatchError.SessionExpired)
        c.forgetSession(); c.login(); c.postFrame(jpeg)
        assertEquals(0, 0)
    }
}
