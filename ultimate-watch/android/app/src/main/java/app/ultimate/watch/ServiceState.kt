package app.ultimate.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Phase { Idle, Starting, Live, Reconnecting, Stopping, Failed }

/** What the service is really doing, for the screen and the notification. Never holds a password. */
data class WatchState(
    val phase: Phase = Phase.Idle,
    val detail: String = "",
    val framesSent: Long = 0,
    /** Human text about local recording, or null when not recording. */
    val recording: String? = null,
    /** Something worth attention that does not stop monitoring, such as heat or a stalled camera. */
    val warning: String? = null,
) {
    val busy get() = phase != Phase.Idle && phase != Phase.Failed

    /** Short text for the persistent notification; stable while live so it is not re-posted every second. */
    fun headline(camera: String?): String = when (phase) {
        Phase.Idle -> "Not monitoring"
        Phase.Starting -> "Starting…"
        Phase.Live -> "Live" + (camera?.let { " · $it" } ?: "")
        Phase.Reconnecting -> "Reconnecting · $detail"
        Phase.Stopping -> "Stopping…"
        Phase.Failed -> "Stopped · $detail"
    }
}

/** Process-wide status. The service writes it; the activity reads it, so the screen shows the service's real state rather than a request. */
object ServiceState {
    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()
    fun set(s: WatchState) { _state.value = s }
    fun update(f: (WatchState) -> WatchState) = _state.update(f)
}
