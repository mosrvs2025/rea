package app.ultimate.audio.ui

import android.net.Uri
import java.io.File
import app.ultimate.UltimateApp
import app.ultimate.audio.engine.AudioConverter
import app.ultimate.audio.engine.AudioProbe
import app.ultimate.audio.engine.ConvertInput
import app.ultimate.audio.engine.PickedVideo
import app.ultimate.audio.model.AudioSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    /** A short before/after render of the single picked file; its own converter so it never touches a running job. */
    data class Preview(val original: Uri? = null, val edited: Uri? = null, val rendering: Boolean = false, val error: String? = null)
    val preview = MutableStateFlow(Preview())
    private val previewer = AudioConverter(app)
    private var previewJob: Job? = null

    fun renderPreview() {
        val v = picked.value.singleOrNull()?.takeIf { it.audio != null } ?: return
        val s = settings.value
        previewJob?.cancel()
        preview.update { it.copy(rendering = true, error = null) }
        previewJob = scope.launch {
            val dir = File(app.cacheDir, "audio-preview").apply { deleteRecursively(); mkdirs() }
            val stamp = System.currentTimeMillis()
            val input = ConvertInput(v.uri, v.name, v.audio!!)
            val base = AudioSettings(trimStartMs = s.trimStartMs, trimEndMs = s.trimEndMs, mono = s.mono)
            preview.value = runCatching {
                val a = previewer.preview(input, base, File(dir, "original-$stamp.wav"))
                val b = previewer.preview(input, s, File(dir, "edited-$stamp.wav"))
                Preview(Uri.fromFile(a), Uri.fromFile(b))
            }.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else Preview(error = it.message ?: "Preview failed") }
        }
    }

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

    /** Moves a file one place earlier, for the order of a joined file. */
    fun moveUp(uri: Uri) = picked.update { list ->
        val i = list.indexOfFirst { it.uri == uri }
        if (i <= 0) list else list.toMutableList().apply { add(i - 1, removeAt(i)) }
    }

    fun clear() {
        previewJob?.cancel()
        preview.value = Preview()
        picked.value = emptyList()
    }

    fun update(change: (AudioSettings) -> AudioSettings) = settings.update(change)

    fun start() {
        manager.submit(picked.value, settings.value)
        previewJob?.cancel()
        preview.value = Preview()
        picked.value = emptyList()
        // Trim points belong to one file; do not carry them into the next pick.
        settings.update { it.copy(trimStartMs = 0, trimEndMs = -1) }
    }

    val log get() = manager.log

    fun cancel() = manager.cancel()
    fun dismissResults() = manager.dismiss()
}
