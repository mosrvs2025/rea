package app.ultimate.audio.ui

import android.net.Uri
import app.ultimate.UltimateApp
import app.ultimate.audio.engine.AudioProbe
import app.ultimate.audio.engine.PickedVideo
import app.ultimate.audio.model.AudioSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-lifetime state for the Video to Audio tool. */
class AudioController(private val app: UltimateApp) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = app.audioManager

    val picked = MutableStateFlow<List<PickedVideo>>(emptyList())
    val probing = MutableStateFlow(0)
    val settings = MutableStateFlow(AudioSettings())
    val queue = manager.state

    fun add(uris: List<Uri>) {
        val fresh = uris.distinct().filter { u -> picked.value.none { it.uri == u } }
        if (fresh.isEmpty()) return
        probing.update { it + fresh.size }
        scope.launch {
            for (u in fresh) {
                val video = withContext(Dispatchers.IO) { AudioProbe.probe(app, u) }
                picked.update { it + video }
                probing.update { it - 1 }
            }
        }
    }

    fun remove(uri: Uri) = picked.update { list -> list.filterNot { it.uri == uri } }

    fun clear() {
        picked.value = emptyList()
    }

    fun update(change: (AudioSettings) -> AudioSettings) = settings.update(change)

    fun start() {
        manager.submit(picked.value, settings.value)
        picked.value = emptyList()
        // Trim points belong to one video; do not carry them into the next pick.
        settings.update { it.copy(trimStartMs = 0, trimEndMs = -1) }
    }

    val log get() = manager.log

    fun cancel() = manager.cancel()
    fun dismissResults() = manager.dismiss()
}
