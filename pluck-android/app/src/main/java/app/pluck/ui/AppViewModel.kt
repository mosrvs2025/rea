package app.pluck.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pluck.PluckApp
import app.pluck.engine.ScanStore
import app.pluck.engine.TrackerScanner
import app.pluck.model.AppDetail
import app.pluck.model.AppItem
import app.pluck.model.Filter
import app.pluck.model.Sort
import app.pluck.model.accepts
import app.pluck.model.matches
import app.pluck.model.sortedBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

enum class Tab { Apps, Insights, Saved }

data class ScanProgress(val done: Int, val total: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val pluck = app as PluckApp
    private val repository = pluck.repository

    val apps = MutableStateFlow<List<AppItem>>(emptyList())
    val loading = MutableStateFlow(true)
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(Filter.User)
    val sort = MutableStateFlow(Sort.Name)
    val selection = MutableStateFlow<Set<String>>(emptySet())
    val tab = MutableStateFlow(Tab.Apps)

    val visible: StateFlow<List<AppItem>> = combine(apps, query, filter, sort) { all, q, f, s ->
        all.filter { f.accepts(it) && it.matches(q) }.sortedBy(s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val detailPackage = MutableStateFlow<String?>(null)
    val detail = MutableStateFlow<AppDetail?>(null)

    val extraction = pluck.manager.state
    val saved = pluck.history.entries
    val scans = pluck.scans.results
    val scanProgress = MutableStateFlow<ScanProgress?>(null)
    private var scanJob: Job? = null
    val shareWhenDone = MutableStateFlow(false)

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            apps.value = repository.loadAll()
            loading.value = false
            val present = apps.value.map { it.packageName }.toSet()
            selection.update { it.filter(present::contains).toSet() }
            if (detailPackage.value != null && detailPackage.value !in present) closeDetail()
        }
    }

    fun toggleSelected(pkg: String) = selection.update { if (pkg in it) it - pkg else it + pkg }
    fun selectAllVisible() = selection.update { visible.value.map { a -> a.packageName }.toSet() }
    fun clearSelection() {
        selection.value = emptySet()
    }

    fun openDetail(pkg: String) {
        detailPackage.value = pkg
        detail.value = null
        val item = apps.value.firstOrNull { it.packageName == pkg } ?: return
        viewModelScope.launch {
            // The app can vanish mid-load (uninstalled elsewhere); fall back to the list rather than crash.
            runCatching { repository.detail(item) }.onSuccess { detail.value = it }.onFailure { closeDetail() }
        }
    }

    fun closeDetail() {
        detailPackage.value = null
        detail.value = null
    }

    fun extract(items: List<AppItem>, share: Boolean) {
        shareWhenDone.value = share
        pluck.manager.submit(items)
        clearSelection()
    }

    fun cancelExtraction() = pluck.manager.cancel()
    fun dismissResults() {
        shareWhenDone.value = false
        pluck.manager.dismiss()
    }

    suspend fun refreshSaved() = pluck.history.refresh()
    suspend fun removeSaved(id: String, deleteFile: Boolean) = pluck.history.remove(id, deleteFile)

    /** Scans one app, reusing a cached result for the same version. */
    fun scanOne(item: AppItem) {
        viewModelScope.launch { scanCached(item, pluck.scans) ; pluck.scans.flush() }
    }

    /** Scans every user app with two workers; results are cached per app version. */
    fun scanAll() {
        if (scanJob?.isActive == true) return
        val targets = apps.value.filter { !it.isSystem }
        scanProgress.value = ScanProgress(0, targets.size)
        scanJob = viewModelScope.launch {
            val done = AtomicInteger()
            val gate = Semaphore(2)
            targets.map { item ->
                async(Dispatchers.Default) {
                    gate.withPermit { scanCached(item, pluck.scans) }
                    scanProgress.value = ScanProgress(done.incrementAndGet(), targets.size)
                }
            }.awaitAll()
            pluck.scans.flush()
            scanProgress.value = null
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanProgress.value = null
        viewModelScope.launch { pluck.scans.flush() }
    }

    private suspend fun scanCached(item: AppItem, store: ScanStore) {
        if (store.cached(item.packageName, item.versionCode, item.updateTime) != null) return
        val sdks = withContext(Dispatchers.IO) { TrackerScanner.scan(item.apkPaths) }
        store.put(item.packageName, ScanStore.Record(item.versionCode, item.updateTime, sdks.map { it.name }))
    }
}
