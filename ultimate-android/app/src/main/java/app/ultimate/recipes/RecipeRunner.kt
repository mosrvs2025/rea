package app.ultimate.recipes

import android.content.Context
import android.net.Uri
import app.ultimate.UltimateApp
import app.ultimate.audio.engine.AudioProbe
import app.ultimate.audio.engine.AudioStatus
import app.ultimate.audio.model.AudioSettings
import app.ultimate.audio.model.OutFormat
import app.ultimate.photos.PhotoCrop
import app.ultimate.photos.PhotoSettings
import app.ultimate.photos.PhotoSize
import app.ultimate.speech.Captions
import app.ultimate.speech.Transcriber
import app.ultimate.squeeze.engine.CompressionManager
import app.ultimate.squeeze.engine.Status
import app.ultimate.squeeze.engine.VideoProbe
import app.ultimate.squeeze.model.Planner
import app.ultimate.squeeze.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Runs a recipe over files, one step after another, handing each step's result to the next. Each step is the real tool doing
 * its job, so progress, notifications and Library entries are the same as doing it by hand.
 */
class RecipeRunner(private val context: Context) {
    sealed interface State {
        data object Idle : State
        data class Running(val recipe: Recipe, val file: Int, val files: Int, val step: Int) : State
        data class Done(val recipe: Recipe, val lines: List<String>) : State
    }

    private val app get() = context.applicationContext as UltimateApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = context.getSharedPreferences("recipes", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _custom = MutableStateFlow(loadCustom())
    val custom: StateFlow<List<Recipe>> = _custom.asStateFlow()
    private var job: Job? = null

    val all get() = RecipeRules.builtIns + _custom.value

    fun save(name: String, steps: List<Step>) {
        _custom.value = _custom.value + Recipe(UUID.randomUUID().toString(), name.ifBlank { "My recipe" }, steps)
        persist()
    }

    fun delete(id: String) { _custom.value = _custom.value.filterNot { it.id == id }; persist() }

    fun cancel() { job?.cancel(); _state.value = State.Idle }
    fun dismiss() { if (_state.value is State.Done) _state.value = State.Idle }

    fun run(recipe: Recipe, uris: List<Uri>) {
        if (_state.value is State.Running || uris.isEmpty()) return
        app.insights.count("recipe.${if (recipe.builtIn) recipe.id else "custom"}")
        job = scope.launch {
            val lines = mutableListOf<String>()
            uris.forEachIndexed { f, start ->
                var current = start
                for ((i, step) in recipe.steps.withIndex()) {
                    _state.value = State.Running(recipe, f, uris.size, i)
                    val result = runCatching { perform(step, current) }
                    result.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; lines += "${step.label}: ${it.message ?: "failed"}"; app.insights.problem("Recipe ${recipe.name}", "${step.label}: ${it.message}") }
                    val (next, line) = result.getOrNull() ?: break
                    lines += line
                    if (next != null) current = next
                }
            }
            _state.value = State.Done(recipe, lines)
        }
    }

    /** Does one step and returns the file it made (null when it made none) and a line for the summary. */
    private suspend fun perform(step: Step, uri: Uri): Pair<Uri?, String> = when (step) {
        Step.Shrink -> {
            val m = app.squeezeManager
            val probed = withContext(Dispatchers.IO) { VideoProbe.probe(app, uri) }
            val src = probed.source ?: error("can't read this video")
            val settings = Settings()
            check(!m.state.value.active) { "Squeeze is busy with something else" }
            m.submit(listOf(CompressionManager.Pending(uri, probed.name, src, Planner.plan(src, settings, app.capabilities))), settings)
            val done = m.state.first { !it.active && it.jobs.isNotEmpty() }
            when (val st = done.jobs.firstOrNull()?.status) {
                is Status.Done -> st.output to "Made smaller: ${probed.name}"
                is Status.Skipped -> null to "Already small: ${probed.name}"
                else -> error("Squeeze didn't finish")
            }
        }
        Step.Mp3, Step.Podcast -> {
            val m = app.audioManager
            val picked = withContext(Dispatchers.IO) { AudioProbe.probe(app, uri) }
            if (picked.audio == null) error("no sound in ${picked.name}")
            check(!m.state.value.active) { "Video to audio is busy" }
            val s = if (step == Step.Podcast) AudioSettings(format = OutFormat.Mp3, kbps = 96, mono = true, normalize = true) else AudioSettings(format = OutFormat.Mp3, kbps = 192)
            m.submit(listOf(picked), s)
            val done = m.state.first { !it.active && it.jobs.isNotEmpty() }
            when (val st = done.jobs.firstOrNull()?.status) {
                is AudioStatus.Done -> Uri.parse(st.entry.uri) to "MP3: ${st.entry.displayName}"
                is AudioStatus.Failed -> error(st.message)
                else -> error("conversion didn't finish")
            }
        }
        Step.Transcribe -> {
            val segments = Transcriber(app).transcribe(uri)
            app.transcripts.put(uri.toString(), Captions.transcript(segments))
            null to if (segments.isEmpty()) "No speech found" else "Transcript: ${segments.size} lines (in the Library)"
        }
        Step.PhotoPost, Step.PhotoSmall -> {
            val m = app.photos
            val picked = withContext(Dispatchers.IO) { m.probe(uri) } ?: error("can't read this photo")
            check(!m.run.value.active) { "Photos is busy" }
            val s = if (step == Step.PhotoPost) PhotoSettings(size = PhotoSize.Hd, crop = PhotoCrop.Portrait, quality = 88) else PhotoSettings(size = PhotoSize.Web, quality = 75)
            m.process(listOf(picked), s)
            val done = m.run.first { !it.active && it.total > 0 }
            val r = done.results.firstOrNull() ?: error("photo couldn't be saved")
            m.dismiss()
            Uri.parse(r.uri) to "Photo: ${r.name}"
        }
    }

    private fun loadCustom(): List<Recipe> = runCatching {
        val a = JSONArray(prefs.getString("custom", "[]"))
        List(a.length()) { a.getJSONObject(it) }.map { o -> Recipe(o.getString("id"), o.getString("name"), o.getJSONArray("steps").let { s -> List(s.length()) { Step.valueOf(s.getString(it)) } }) }
    }.getOrDefault(emptyList())

    private fun persist() = prefs.edit().putString("custom", JSONArray().apply {
        _custom.value.forEach { r -> put(JSONObject().put("id", r.id).put("name", r.name).put("steps", JSONArray(r.steps.map { it.name }))) }
    }.toString()).apply()
}
