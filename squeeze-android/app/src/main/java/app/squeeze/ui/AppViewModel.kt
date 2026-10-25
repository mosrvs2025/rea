package app.squeeze.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.squeeze.SqueezeApp
import app.squeeze.engine.CompressionManager
import app.squeeze.engine.Probed
import app.squeeze.engine.VideoProbe
import app.squeeze.model.Plan
import app.squeeze.model.Planner
import app.squeeze.model.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A picked video with its plan under the current settings. [probed].source is null when the file cannot be read. */
data class Entry(val probed: Probed, val plan: Plan?)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val squeeze = app as SqueezeApp
    private val manager = squeeze.manager
    val capabilities = squeeze.capabilities

    private val picked = MutableStateFlow<List<Probed>>(emptyList())
    private val probing = MutableStateFlow(0)
    val settings = MutableStateFlow(Settings())
    val queue = manager.state
    private val store = squeeze.library
    val library = store.entries
    val tab = MutableStateFlow(Tab.Compress)
    val detailId = MutableStateFlow<String?>(null)
    val isProbing: StateFlow<Boolean> = probing.map { it > 0 }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val entries: StateFlow<List<Entry>> = combine(picked, settings) { list, s ->
        list.map { p -> Entry(p, p.source?.let { Planner.plan(it, s, capabilities) }) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun add(uris: List<Uri>) {
        val fresh = uris.distinct().filter { u -> picked.value.none { it.uri == u } }
        if (fresh.isEmpty()) return
        probing.update { it + fresh.size }
        viewModelScope.launch {
            for (u in fresh) {
                val probed = withContext(Dispatchers.IO) { VideoProbe.probe(getApplication(), u) }
                picked.update { it + probed }
                probing.update { it - 1 }
            }
        }
    }

    fun remove(uri: Uri) = picked.update { list -> list.filterNot { it.uri == uri } }

    fun clear() {
        picked.value = emptyList()
    }

    fun update(change: (Settings) -> Settings) = settings.update(change)

    fun start() {
        val pending = entries.value.mapNotNull { e ->
            val src = e.probed.source ?: return@mapNotNull null
            val plan = e.plan ?: return@mapNotNull null
            CompressionManager.Pending(e.probed.uri, e.probed.name, src, plan)
        }
        manager.submit(pending, settings.value)
        picked.value = emptyList()
    }

    fun frameFile(id: String, before: Boolean) = if (before) store.beforeFrame(id) else store.afterFrame(id)

    fun clipFile(id: String) = store.clipFile(id)

    fun openDetail(id: String) {
        detailId.value = id
    }

    fun closeDetail() {
        detailId.value = null
    }

    suspend fun refreshLibrary() = store.refresh()

    suspend fun removeFromLibrary(id: String, deleteFile: Boolean) = store.remove(id, deleteFile)

    fun cancel() = manager.cancel()

    fun dismissResults() = manager.dismiss()
}
