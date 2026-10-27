package app.ultimate.watch

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Recordings {
    fun dir(context: Context): File =
        (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "recordings")).also { it.mkdirs() }

    fun newName(): String = "watch-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".mp4"

    fun isRecording(file: File) = file.isFile && file.name.startsWith("watch-") && file.name.endsWith(".mp4")

    fun list(dir: File): List<RecFile> =
        dir.listFiles().orEmpty().filter(::isRecording).map { RecFile(it.name, it.length(), it.lastModified()) }
}

/**
 * Records silent video as a series of short files, so a crash or a full disk loses minutes rather than everything.
 * Each file is bounded by duration and size; old files are deleted to honour the storage and age caps, and recording
 * pauses (rather than failing) when free space runs low. Must be used from the main thread.
 */
class SegmentRecorder(
    private val context: Context,
    private val dir: File,
    private val limits: StorageLimits,
    private val report: (String?) -> Unit,
) {
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .build()
    val useCase: VideoCapture<Recorder> = VideoCapture.withOutput(recorder)

    private var current: Recording? = null
    var currentFile: File? = null
        private set
    private var wanted = false
    private var suspendedReason: String? = null
    private var failures = 0
    private var onStopped: (() -> Unit)? = null
    private val retry = Runnable { next() }

    fun start() {
        wanted = true
        failures = 0
        next()
    }

    /** Stops the open segment, waits for it to be finalized (so it is playable) and then calls [done]. */
    fun stop(done: () -> Unit) {
        wanted = false
        handler.removeCallbacks(retry)
        val open = current
        if (open == null) {
            report(null)
            done()
        } else {
            onStopped = done
            open.stop()
        }
    }

    /** Pauses or resumes recording without stopping monitoring, for example while the phone is too hot. */
    fun suspend(reason: String?) {
        suspendedReason = reason
        if (reason != null) current?.stop() else next()
    }

    /** Periodic upkeep: apply retention, notice low space, and restart recording if it was waiting. */
    fun housekeeping() {
        enforce()
        if (current != null && dir.usableSpace < limits.minFreeBytes / 2) current?.stop() else if (current == null) next()
    }

    private fun next() {
        handler.removeCallbacks(retry)
        if (!wanted || current != null) return
        suspendedReason?.let { report("Recording paused: $it"); return }
        enforce()
        if (dir.usableSpace < limits.minFreeBytes) {
            report("Recording paused: storage low (${dir.usableSpace / MB} MB free)")
            handler.postDelayed(retry, 30_000)
            return
        }
        dir.mkdirs()
        val file = File(dir, Recordings.newName())
        val options = FileOutputOptions.Builder(file)
            .setDurationLimitMillis(limits.segmentMs)
            .setFileSizeLimit(limits.maxSegmentBytes)
            .build()
        currentFile = file
        current = recorder.prepareRecording(context, options).start(main, ::onEvent)
        report("Recording · ${file.name}")
    }

    private fun onEvent(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        val file = currentFile
        current = null
        currentFile = null
        val error = event.error
        val expected = error == VideoRecordEvent.Finalize.ERROR_NONE ||
            error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED ||
            error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED
        if (!expected && (file?.length() ?: 0L) < MIN_VALID_BYTES) file?.delete()
        onStopped?.let { onStopped = null; report(null); it(); return }
        if (!wanted) { report(null); return }
        when {
            expected -> { failures = 0; enforce(); next() }
            error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> {
                report("Recording paused: storage full")
                enforce()
                handler.postDelayed(retry, 30_000)
            }
            ++failures >= MAX_FAILURES -> { wanted = false; report("Recording stopped after repeated errors (code $error)") }
            else -> { report("Recording problem (code $error); retrying"); handler.postDelayed(retry, 5_000L * failures) }
        }
    }

    private fun enforce() {
        val doomed = Retention.plan(Recordings.list(dir), currentFile?.name, System.currentTimeMillis(), limits)
        doomed.forEach { File(dir, it.name).delete() }
    }

    private companion object {
        const val MB = 1024L * 1024L
        const val MIN_VALID_BYTES = 1024L
        const val MAX_FAILURES = 5
    }
}
