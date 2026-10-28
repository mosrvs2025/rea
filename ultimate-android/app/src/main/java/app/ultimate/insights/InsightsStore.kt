package app.ultimate.insights

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What Ultimate remembers about its own use: opens and minutes per tool, and recent crashes. All of it stays on the phone until
 * the user exports a report.
 */
class InsightsStore(context: Context, private val version: String) {
    private val prefs = context.getSharedPreferences("insights", Context.MODE_PRIVATE)
    private val crashFile = File(context.filesDir, "crashes.json")
    private var current: String? = null
    private var startedAt = 0L

    fun entered(toolId: String) {
        left()
        current = toolId
        startedAt = SystemClock.elapsedRealtime()
        prefs.edit().putInt("opens.$toolId", prefs.getInt("opens.$toolId", 0) + 1).putLong("last.$toolId", System.currentTimeMillis()).apply()
    }

    fun left() {
        val id = current ?: return
        current = null
        val ms = (SystemClock.elapsedRealtime() - startedAt).coerceIn(0, 6 * 3_600_000L)
        prefs.edit().putLong("ms.$id", prefs.getLong("ms.$id", 0) + ms).apply()
    }

    private var paused: String? = null

    /** The app went to the background: stop the clock without counting a new open when it comes back. */
    fun pause() { paused = current; left() }
    fun resume() { paused?.let { current = it; startedAt = SystemClock.elapsedRealtime() }; paused = null }

    /** When counting began, so a report can tell "never opened" from "not counted yet". */
    val since: Long get() = prefs.getLong("since", 0L).takeIf { it > 0 } ?: System.currentTimeMillis().also { prefs.edit().putLong("since", it).apply() }

    fun opens(id: String) = prefs.getInt("opens.$id", 0)
    fun minutes(id: String) = (prefs.getLong("ms.$id", 0) + if (id == current) SystemClock.elapsedRealtime() - startedAt else 0) / 60_000
    fun lastAt(id: String) = prefs.getLong("last.$id", 0)

    fun crashes(): List<Crash> = runCatching {
        val a = JSONArray(crashFile.readText())
        List(a.length()) { a.getJSONObject(it) }.map { o ->
            Crash(o.getLong("at"), o.getString("version"), o.getString("type"), o.optString("message").ifEmpty { null }, o.getJSONArray("frames").let { f -> List(f.length()) { f.getString(it) } })
        }
    }.getOrDefault(emptyList())

    fun clearCrashes() { crashFile.delete() }

    /** Keeps the last few crashes, with only Ultimate's own stack frames, then lets Android handle the crash as usual. */
    fun installCrashCatcher() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val root = generateSequence(e) { it.cause }.last()
                val frames = (e.stackTrace + root.stackTrace).filter { it.className.startsWith("app.ultimate") }.distinct().take(12).map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                val o = JSONObject().put("at", System.currentTimeMillis()).put("version", version).put("type", root.javaClass.name).put("message", root.message ?: "").put("frames", JSONArray(frames))
                val list = runCatching { JSONArray(crashFile.readText()) }.getOrDefault(JSONArray())
                val kept = JSONArray().apply { val start = maxOf(0, list.length() - 4); for (k in start until list.length()) put(list.get(k)); put(o) }
                crashFile.writeText(kept.toString())
            }
            previous?.uncaughtException(thread, e)
        }
    }
}
