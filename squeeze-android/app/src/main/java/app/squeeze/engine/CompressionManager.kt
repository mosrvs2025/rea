package app.squeeze.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.squeeze.model.LibraryEntry
import app.squeeze.model.Mode
import app.squeeze.model.Plan
import app.squeeze.model.Planner
import app.squeeze.model.Settings
import app.squeeze.model.Source
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Status {
    data object Queued : Status
    data class Running(val progress: Float, val etaMs: Long?, val speedX: Float?) : Status
    data class Done(val output: Uri, val outputBytes: Long, val libraryId: String?) : Status
    data class Skipped(val reason: String) : Status
    data class Failed(val message: String) : Status
    data object Cancelled : Status
}

data class Item(val id: Int, val uri: Uri, val name: String, val source: Source, val plan: Plan, val status: Status = Status.Queued)

data class QueueState(val jobs: List<Item> = emptyList(), val active: Boolean = false) {
    val finished get() = !active && jobs.isNotEmpty()
    val savedBytes get() = jobs.sumOf { (it.status as? Status.Done)?.let { d -> it.source.sizeBytes - d.outputBytes } ?: 0L }
}

/** Owns the compression queue. The foreground service only keeps the process alive and mirrors this state. */
class CompressionManager(private val app: Context, private val library: LibraryStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val compressor = VideoCompressor(app)
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()
    private var runner: Job? = null

    private val workDir get() = File(app.cacheDir, "work").apply { mkdirs() }

    fun submit(items: List<Pending>, settings: Settings) {
        if (_state.value.active || items.isEmpty()) return
        workDir.listFiles()?.forEach { it.delete() }
        val jobs = items.mapIndexed { i, p ->
            val skip = p.plan.skipReason
            Item(i, p.uri, p.name, p.source, p.plan, if (skip != null) Status.Skipped(skip) else Status.Queued)
        }
        _state.value = QueueState(jobs, active = true)
        ContextCompat.startForegroundService(app, Intent(app, CompressionService::class.java))
        runner = scope.launch { runAll(settings) }
    }

    fun cancel() {
        runner?.cancel()
    }

    /** Leaves the results screen. No-op while work is running. */
    fun dismiss() {
        if (!_state.value.active) _state.value = QueueState()
    }

    private suspend fun runAll(settings: Settings) {
        try {
            for (job in _state.value.jobs) {
                if (job.status !is Status.Queued) continue
                val result = try {
                    runOne(job, settings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Status.Failed(e.message?.take(160) ?: "Could not compress this video")
                }
                setStatus(job.id, result)
            }
        } catch (e: CancellationException) {
            _state.update { s ->
                s.copy(jobs = s.jobs.map { if (it.status is Status.Queued || it.status is Status.Running) it.copy(status = Status.Cancelled) else it })
            }
            throw e
        } finally {
            _state.update { it.copy(active = false) }
        }
    }

    private suspend fun runOne(job: Item, settings: Settings): Status {
        val tmp = File(workDir, "job${job.id}.mp4")
        val started = SystemClock.elapsedRealtime()
        try {
            compressor.compress(job.uri, tmp, job.plan, job.source.isHdr, settings.muteAudio) { p ->
                val elapsed = SystemClock.elapsedRealtime() - started
                val eta = if (p > 0.03f) (elapsed / p * (1 - p)).toLong() else null
                val speed = if (p > 0.03f && elapsed > 0) job.source.durationMs * p / elapsed else null
                setStatus(job.id, Status.Running(p, eta, speed))
            }
            val outBytes = tmp.length()
            if (outBytes <= 0) throw IOException("Encoder produced no output")
            // Guard: a lossy re-encode that is not clearly smaller is worse than the original.
            if (outBytes > job.source.sizeBytes * (1 - MIN_ACTUAL_SAVING)) {
                return Status.Skipped("Result was not smaller, so your original was kept")
            }
            val encodeMs = SystemClock.elapsedRealtime() - started
            val uri = saveToGallery(tmp, outputName(job.name))
            val id = recordInLibrary(job, settings, uri, outBytes, encodeMs)
            return Status.Done(uri, outBytes, id)
        } finally {
            withContext(NonCancellable) { tmp.delete() }
        }
    }

    /** Library bookkeeping must never turn a successful compression into a failure. */
    private suspend fun recordInLibrary(job: Item, settings: Settings, output: Uri, outBytes: Long, encodeMs: Long): String? = try {
        val id = UUID.randomUUID().toString()
        val measured = withContext(Dispatchers.IO) { VideoProbe.probe(app, output).source }
        val after = measured?.copy(sizeBytes = outBytes) ?: job.source.copy(
            sizeBytes = outBytes, width = job.plan.width, height = job.plan.height,
            videoBitrate = job.plan.videoBitrate, mime = job.plan.codec.mime,
        )
        val hasFrames = library.saveFrames(id, job.uri, output, job.source.durationMs)
        val mode = if (settings.mode == Mode.Preset) settings.preset.label else "≤ ${settings.targetMb} MB"
        library.add(
            LibraryEntry(id, System.currentTimeMillis(), job.name, output.toString(), job.source, after, encodeMs, job.plan.estimatedBytes, "$mode · ${job.plan.codec.label}", hasFrames),
        )
        id
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun setStatus(id: Int, status: Status) = _state.update { s ->
        s.copy(jobs = s.jobs.map { if (it.id == id) it.copy(status = status) else it })
    }

    private suspend fun saveToGallery(file: File, displayName: String): Uri = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Squeeze")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create the output file")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Could not open the output file")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        uri
    }

    private fun outputName(original: String): String = original.substringBeforeLast('.', original).ifBlank { "video" } + "_squeezed.mp4"

    data class Pending(val uri: Uri, val name: String, val source: Source, val plan: Plan)

    private companion object {
        const val MIN_ACTUAL_SAVING = 0.03
    }
}
