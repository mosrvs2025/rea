package app.ultimate.checkup.engine

import android.content.Context
import app.ultimate.BuildConfig
import app.ultimate.UltimateApp
import app.ultimate.checkup.model.Checkup
import app.ultimate.checkup.model.Scan
import app.ultimate.pluck.engine.SdkKind
import app.ultimate.pluck.engine.TrackerScanner
import app.ultimate.pluck.model.Replacements
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs the checkup and keeps the latest result for as long as the app is alive; the score is remembered between launches. */
class CheckupManager(private val app: UltimateApp) {
    sealed interface State {
        data object Idle : State
        data class Scanning(val step: String, val done: Int, val total: Int) : State
        data class Done(val scan: Scan) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val collector = Collector(app)
    private val prefs = app.getSharedPreferences("checkup", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _last = MutableStateFlow(prefs.getInt("score", -1).takeIf { it >= 0 }?.let { it to prefs.getLong("at", 0) })
    /** Last score and when it was taken, for the Home tile. */
    val last: StateFlow<Pair<Int, Long>?> = _last.asStateFlow()
    private var job: Job? = null

    /** Our own editions are not judged as third-party apps. */
    val own: Set<String> = Replacements.ours + app.packageName

    fun usageAccess() = collector.hasUsageAccess()

    fun scan() {
        if (_state.value is State.Scanning) return
        job = scope.launch {
            try {
                _state.value = State.Scanning("Reading this phone", 0, 0)
                val usage = collector.hasUsageAccess()
                val device = withContext(Dispatchers.IO) { collector.device() }
                val ads = if (BuildConfig.HAS_PLUCK) adSdks() else null
                val apps = withContext(Dispatchers.IO) {
                    collector.apps(ads, usage) { done, total -> _state.value = State.Scanning("Checking apps", done, total) }
                }
                _state.value = State.Scanning("Weighing it up", apps.size, apps.size)
                val now = System.currentTimeMillis()
                val scan = Scan(now, device, apps, usage, Checkup.findings(device, apps, now, usage, own))
                _state.value = State.Done(scan)
                _last.value = scan.score to now
                prefs.edit().putInt("score", scan.score).putLong("at", now).apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Ad SDKs per app from Pluck's code scan, or null when it has never run (unknown, not "none"). */
    private fun adSdks(): Map<String, List<String>>? {
        val results = app.pluckScans.results.value
        if (results.isEmpty()) return null
        val ads = TrackerScanner.catalog.filter { it.kind == SdkKind.Ads }.map { it.name }.toSet()
        return results.mapValues { (_, r) -> r.sdks.filter { it in ads } }
    }
}
