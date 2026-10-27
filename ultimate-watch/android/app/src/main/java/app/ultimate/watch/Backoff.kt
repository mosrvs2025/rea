package app.ultimate.watch

import kotlin.random.Random

/** Exponential retry delays that cap out, with jitter so several phones don't all retry in the same second. */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val jitter: (Long) -> Long = { raw -> (raw * 0.8).toLong() + Random.nextLong(raw / 5 + 1) },
) {
    var attempts = 0
        private set

    fun nextDelayMs(): Long {
        val raw = minOf(maxMs, baseMs shl minOf(attempts, 20))
        attempts++
        return jitter(raw)
    }

    fun reset() {
        attempts = 0
    }
}
