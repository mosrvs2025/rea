package app.pluck.engine

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.pluck.model.AppItem
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface JobStatus {
    data object Queued : JobStatus
    data class Running(val done: Long, val total: Long) : JobStatus
    data class Done(val entry: SavedEntry) : JobStatus
    data class Failed(val message: String) : JobStatus
    data object Cancelled : JobStatus
}

data class ExtractJob(val app: AppItem, val status: JobStatus = JobStatus.Queued)

data class ExtractState(val jobs: List<ExtractJob> = emptyList(), val active: Boolean = false) {
    val saved: List<SavedEntry> get() = jobs.mapNotNull { (it.status as? JobStatus.Done)?.entry }
}

/** Owns the extraction queue. The foreground service only keeps the process alive and mirrors this state. */
class ExtractManager(private val app: Context, private val history: HistoryStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val extractor = Extractor(app)
    private val _state = MutableStateFlow(ExtractState())
    val state: StateFlow<ExtractState> = _state.asStateFlow()
    private var runner: Job? = null

    fun submit(apps: List<AppItem>) {
        if (_state.value.active || apps.isEmpty()) return
        _state.value = ExtractState(apps.map { ExtractJob(it) }, active = true)
        ContextCompat.startForegroundService(app, Intent(app, ExtractService::class.java))
        runner = scope.launch { runAll() }
    }

    fun cancel() {
        runner?.cancel()
    }

    /** Leaves the results screen. No-op while work is running. */
    fun dismiss() {
        if (!_state.value.active) _state.value = ExtractState()
    }

    private suspend fun runAll() {
        try {
            for (i in _state.value.jobs.indices) {
                val job = _state.value.jobs[i]
                val status = try {
                    update(i, JobStatus.Running(0, job.app.apkBytes))
                    val out = extractor.extract(job.app) { done, total -> update(i, JobStatus.Running(done, total)) }
                    val entry = SavedEntry(
                        UUID.randomUUID().toString(), System.currentTimeMillis(), job.app.packageName, job.app.label, job.app.versionName,
                        out.uri.toString(), out.displayName, out.bytes, out.splitCount, out.sha256,
                    )
                    history.add(entry)
                    JobStatus.Done(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    JobStatus.Failed(e.message?.take(160) ?: "Could not extract this app")
                }
                update(i, status)
            }
        } catch (e: CancellationException) {
            _state.update { s ->
                s.copy(jobs = s.jobs.map { if (it.status is JobStatus.Queued || it.status is JobStatus.Running) it.copy(status = JobStatus.Cancelled) else it })
            }
            throw e
        } finally {
            _state.update { it.copy(active = false) }
        }
    }

    private fun update(index: Int, status: JobStatus) = _state.update { s ->
        s.copy(jobs = s.jobs.mapIndexed { i, j -> if (i == index) j.copy(status = status) else j })
    }
}
