package app.ultimate.watch

import java.net.URI

/** A validated server origin such as https://server:8443: HTTPS only, no path, query, fragment or credentials. */
class Endpoint private constructor(val origin: String) {
    override fun toString() = origin

    companion object {
        fun parse(input: String): Result<Endpoint> = runCatching {
            val text = input.trim().trimEnd('/')
            val uri = URI(text)
            require(uri.scheme == "https") { "Use an https:// address" }
            require(!uri.host.isNullOrEmpty()) { "The address needs a host name" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
                "Use just the server address, like https://host:8443"
            }
            Endpoint(text)
        }
    }
}

object CameraNames {
    private val valid = Regex("[A-Za-z0-9_-]{1,32}")
    fun isValid(name: String) = valid.matches(name)
}

const val MIN_PASSWORD_LENGTH = 16

/** How much recorded video the phone keeps. Every recording is bounded by all of these. */
data class StorageLimits(
    val segmentMinutes: Int = 5,
    val maxTotalMb: Int = 2048,
    val maxAgeDays: Int = 7,
    val minFreeMb: Int = 500,
) {
    val segmentMs get() = segmentMinutes * 60_000L
    val maxTotalBytes get() = maxTotalMb * MB
    val maxAgeMs get() = maxAgeDays * DAY_MS
    val minFreeBytes get() = minFreeMb * MB

    /** One file never takes more than a quarter of the cap (and never more than 512 MB), so retention always has older files to drop. */
    val maxSegmentBytes get() = minOf(512L * MB, maxTotalBytes / 4).coerceAtLeast(16L * MB)

    /** Clamps values typed by a person to ranges that make sense. */
    fun coerced() = StorageLimits(
        segmentMinutes.coerceIn(1, 60), maxTotalMb.coerceIn(64, 200_000), maxAgeDays.coerceIn(1, 365), minFreeMb.coerceIn(50, 100_000),
    )

    private companion object {
        const val MB = 1024L * 1024L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** Everything the service needs to start. The password lives only in memory. */
class StartRequest(val endpoint: Endpoint, val camera: String, val password: String, val record: Boolean, val limits: StorageLimits) {
    override fun toString() = "StartRequest(endpoint=$endpoint, camera=$camera, record=$record)"
}

/** Hands a request from the activity to the service inside this process, so the password never travels in an Intent or touches disk. */
object PendingStart {
    @Volatile private var request: StartRequest? = null
    fun set(r: StartRequest) { request = r }
    fun take(): StartRequest? = request.also { request = null }
}
