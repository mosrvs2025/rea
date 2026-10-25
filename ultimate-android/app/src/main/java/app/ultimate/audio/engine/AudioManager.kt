package app.ultimate.audio.engine

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.ultimate.audio.model.AudioPlanner
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.AudioSource
import app.ultimate.core.WorkHub
import app.ultimate.core.WorkSnapshot
import app.ultimate.core.WorkSource
import app.ultimate.core.formatBytes
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AudioStatus {
    data object Queued : AudioStatus
    data class Running(val progress: Float, val peaks: List<Float>, val speedX: Float?) : AudioStatus
    data class Done(val entry: AudioEntry) : AudioStatus
    data class Failed(val message: String) : AudioStatus
    data object Cancelled : AudioStatus
}

data class AudioJob(val id: Int, val uri: Uri, val name: String, val source: AudioSource, val status: AudioStatus = AudioStatus.Queued)

data class AudioQueue(val jobs: List<AudioJob> = emptyList(), val active: Boolean = false) {
    val done: List<AudioEntry> get() = jobs.mapNotNull { (it.status as? AudioStatus.Done)?.entry }
}

/** Owns the conversion queue; the shared work service only keeps the process alive and mirrors [work]. */
class AudioManager(private val app: Context, private val store: AudioStore, private val hub: WorkHub) : WorkSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val converter = AudioConverter(app)
    private val _state = MutableStateFlow(AudioQueue())
    val state: StateFlow<AudioQueue> = _state.asStateFlow()
    private var runner: Job? = null

    override val workId = "audio"
    override val work: StateFlow<WorkSnapshot> = _state.map(::snapshot).stateIn(scope, SharingStarted.Eagerly, WorkSnapshot())
    override fun cancelWork() = cancel()

    private fun snapshot(s: AudioQueue): WorkSnapshot {
        if (s.active) {
            val total = s.jobs.size.coerceAtLeast(1)
            val finished = s.jobs.count { it.status !is AudioStatus.Queued && it.status !is AudioStatus.Running }
            val current = s.jobs.firstOrNull { it.status is AudioStatus.Running }
            val p = (current?.status as? AudioStatus.Running)?.progress ?: 0f
            return WorkSnapshot(
                active = true, title = "Converting ${minOf(finished + 1, total)} of $total", detail = current?.name,
                percent = ((finished + p) / total * 100).toInt().coerceIn(0, 100),
            )
        }
        if (s.jobs.isEmpty()) return WorkSnapshot()
        val n = s.done.size
        return WorkSnapshot(
            doneTitle = "Audio ready",
            doneText = if (n == 0) "Nothing was converted" else "$n file${if (n == 1) "" else "s"} · ${formatBytes(s.done.sumOf { it.bytes })} in Music/Ultimate",
        )
    }

    fun submit(videos: List<PickedVideo>, settings: AudioSettings) {
        if (_state.value.active) return
        val jobs = videos.mapNotNull { v -> v.audio?.let { AudioJob(0, v.uri, v.name, it) } }.mapIndexed { i, j -> j.copy(id = i) }
        if (jobs.isEmpty()) return
        _state.value = AudioQueue(jobs, active = true)
        hub.start()
        // Trimming only makes sense for a single video, so it is ignored for batches.
        val effective = if (jobs.size == 1) settings else settings.copy(trimStartMs = 0, trimEndMs = -1)
        runner = scope.launch { runAll(effective) }
    }

    fun cancel() {
        runner?.cancel()
    }

    /** Leaves the results screen. No-op while work is running. */
    fun dismiss() {
        if (!_state.value.active) _state.value = AudioQueue()
    }

    private suspend fun runAll(settings: AudioSettings) {
        try {
            for (i in _state.value.jobs.indices) {
                val job = _state.value.jobs[i]
                val started = SystemClock.elapsedRealtime()
                val status = try {
                    update(i, AudioStatus.Running(0f, emptyList(), null))
                    val kept = AudioPlanner.keptMs(job.source, settings)
                    val out = converter.convert(job.uri, job.name, job.source, settings) { fraction, peaks ->
                        val elapsed = SystemClock.elapsedRealtime() - started
                        val speed = if (fraction > 0.03f && elapsed > 0) kept * fraction / elapsed else null
                        update(i, AudioStatus.Running(fraction, peaks, speed))
                    }
                    val entry = AudioEntry(
                        UUID.randomUUID().toString(), System.currentTimeMillis(), job.name, out.displayName, out.uri.toString(), out.bytes,
                        out.durationMs, out.format, out.bitrate, out.sampleRate, out.channels, out.peaks,
                    )
                    store.add(entry)
                    AudioStatus.Done(entry)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AudioStatus.Failed(e.message?.take(160) ?: "Could not convert this video")
                }
                update(i, status)
            }
        } catch (e: CancellationException) {
            _state.update { s ->
                s.copy(jobs = s.jobs.map { if (it.status is AudioStatus.Queued || it.status is AudioStatus.Running) it.copy(status = AudioStatus.Cancelled) else it })
            }
            throw e
        } finally {
            _state.update { it.copy(active = false) }
        }
    }

    private fun update(index: Int, status: AudioStatus) = _state.update { s ->
        s.copy(jobs = s.jobs.mapIndexed { i, j -> if (i == index) j.copy(status = status) else j })
    }
}
