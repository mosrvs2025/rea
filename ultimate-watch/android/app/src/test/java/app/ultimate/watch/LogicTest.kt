package app.ultimate.watch

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test fun endpointAcceptsOnlyBareHttpsOrigins() {
        assertEquals("https://nas.local:8443", Endpoint.parse(" https://nas.local:8443/ ").getOrThrow().origin)
        listOf("http://nas.local", "https://", "https://user:pw@nas.local", "https://nas.local/path", "https://nas.local?x=1", "https://nas.local#f", "nas.local", "").forEach {
            assertTrue("should reject '$it'", Endpoint.parse(it).isFailure)
        }
    }

    @Test fun cameraNamesMatchTheServerRule() {
        assertTrue(CameraNames.isValid("s10-porch_1"))
        listOf("", "a b", "a/b", "é", "x".repeat(33)).forEach { assertFalse(it, CameraNames.isValid(it)) }
    }

    @Test fun startRequestNeverPrintsThePassword() {
        val r = StartRequest(Endpoint.parse("https://h").getOrThrow(), "cam", "super-secret-password", true, StorageLimits())
        assertFalse(r.toString().contains("super-secret"))
    }

    @Test fun pendingStartIsConsumedOnce() {
        PendingStart.set(StartRequest(Endpoint.parse("https://h").getOrThrow(), "cam", "p".repeat(16), false, StorageLimits()))
        assertTrue(PendingStart.take() != null)
        assertEquals(null, PendingStart.take())
    }

    @Test fun backoffGrowsCapsAndResets() {
        val b = Backoff(1_000, 60_000) { it }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), List(8) { b.nextDelayMs() })
        assertEquals(8, b.attempts)
        b.reset()
        assertEquals(1_000L, b.nextDelayMs())
    }

    @Test fun backoffJitterStaysBelowTheCapAndAboveEightyPercent() {
        val b = Backoff(1_000, 60_000)
        repeat(30) { val d = b.nextDelayMs(); assertTrue(d in 800L..60_000L) }
    }

    @Test fun limitsAreClampedAndSegmentsStayWellBelowTheCap() {
        val c = StorageLimits(0, 1, 0, 0).coerced()
        assertEquals(1, c.segmentMinutes); assertEquals(64, c.maxTotalMb); assertEquals(1, c.maxAgeDays); assertEquals(50, c.minFreeMb)
        val l = StorageLimits(maxTotalMb = 2048)
        assertEquals(512L * 1024 * 1024, l.maxSegmentBytes)
        assertEquals(16L * 1024 * 1024, StorageLimits(maxTotalMb = 64).maxSegmentBytes)
    }

    private val mb = 1024L * 1024L
    private val day = 24L * 60 * 60 * 1000
    private fun f(name: String, sizeMb: Long, ageDays: Double, now: Long) = RecFile(name, sizeMb * mb, now - (ageDays * day).toLong())

    @Test fun retentionDropsExpiredThenOldestUntilUnderTheCap() {
        val now = 100 * day
        val limits = StorageLimits(maxTotalMb = 250, maxAgeDays = 7)
        val files = listOf(f("a", 100, 10.0, now), f("b", 100, 3.0, now), f("c", 100, 2.0, now), f("d", 100, 1.0, now))
        assertEquals(listOf("a", "b"), Retention.plan(files, null, now, limits).map { it.name })
    }

    @Test fun retentionNeverDeletesTheFileBeingWritten() {
        val now = 100 * day
        val limits = StorageLimits(maxTotalMb = 100, maxAgeDays = 1)
        val files = listOf(f("old-active", 500, 30.0, now), f("x", 50, 0.5, now))
        val doomed = Retention.plan(files, "old-active", now, limits).map { it.name }
        assertFalse("old-active" in doomed)
        assertEquals(listOf("x"), doomed)
    }

    @Test fun retentionDoesNothingWithinLimits() {
        val now = 100 * day
        assertTrue(Retention.plan(listOf(f("a", 10, 1.0, now)), null, now, StorageLimits()).isEmpty())
    }

    @Test fun errorsAreSortedIntoTheRightRecoveryPath() {
        assertTrue(classify(SocketTimeoutException("timeout")) is WatchError.Transient)
        assertTrue(classify(IOException("reset")) is WatchError.Transient)
        assertTrue(classify(SSLHandshakeException("Connection reset during handshake")) is WatchError.Transient)
        assertTrue(classify(SSLHandshakeException("bad").apply { initCause(CertPathValidatorException("Trust anchor not found")) }) is WatchError.Fatal)
        assertTrue(classify(RuntimeException("boom")) is WatchError.Transient)
        assertTrue(classify(WatchError.SessionExpired()) is WatchError.SessionExpired)
    }

    @Test fun statusHeadlineIsStableWhileLive() {
        val a = WatchState(Phase.Live, framesSent = 1).headline("porch")
        val b = WatchState(Phase.Live, framesSent = 999).headline("porch")
        assertEquals(a, b)
    }
}
