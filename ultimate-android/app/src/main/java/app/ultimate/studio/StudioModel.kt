package app.ultimate.studio

import java.util.UUID
import kotlin.math.roundToLong

enum class CanvasShape(val label: String, val ratio: Float) { Portrait("9:16", 9f / 16), Landscape("16:9", 16f / 9), Square("1:1", 1f) }
enum class Look(val label: String) { Natural("Natural"), Cinema("Cinema"), Warm("Warm"), Noir("Noir") }
data class StudioClip(
    val id: String = UUID.randomUUID().toString(), val uri: String, val name: String,
    val sourceMs: Long, val startMs: Long = 0, val endMs: Long = sourceMs,
    val speed: Float = 1f, val volume: Float = 1f, val look: Look = Look.Natural,
    val brightness: Float = 0f, val rotation: Int = 0, val title: String = "", val titlePosition: Int = 0,
) { val durationMs: Long get() = ((endMs - startMs) / speed.toDouble()).roundToLong() }
data class StudioMusic(val uri: String, val name: String, val durationMs: Long, val startMs: Long = 0, val volume: Float = 0.5f)
data class StudioProject(
    val id: String = UUID.randomUUID().toString(), val name: String = "Untitled film",
    val updatedAt: Long = System.currentTimeMillis(), val clips: List<StudioClip> = emptyList(),
    val shape: CanvasShape = CanvasShape.Portrait, val fillCanvas: Boolean = false,
    val music: StudioMusic? = null, val bpm: Int = 120, val beatOffsetMs: Long = 0,
    val shortSide: Int = 720,
) {
    val durationMs get() = clips.sumOf { it.durationMs }
    val width get() = if (shape == CanvasShape.Landscape) even(shortSide * 16 / 9) else shortSide
    val height get() = if (shape == CanvasShape.Portrait) even(shortSide * 16 / 9) else shortSide
    private fun even(n: Int) = n / 2 * 2
}
data class StudioExport(val id: String, val name: String, val uri: String, val createdAt: Long, val durationMs: Long, val bytes: Long, val width: Int, val height: Int)

object Timeline {
    const val MIN_CLIP_MS = 100L
    fun validate(project: StudioProject) {
        require(project.clips.isNotEmpty()) { "Add at least one video" }
        require(project.shortSide in listOf(720, 1080)) { "Choose a supported export size" }
        project.clips.forEach { c ->
            require(c.uri.isNotBlank() && c.startMs >= 0 && c.endMs <= c.sourceMs && c.endMs - c.startMs >= MIN_CLIP_MS) { "Invalid trim in ${c.name}" }
            require(c.speed in 0.25f..4f && c.speed.isFinite()) { "Invalid playback speed" }
            require(c.volume in 0f..1f && c.volume.isFinite()) { "Invalid clip volume" }
            require(c.brightness in -0.5f..0.5f && c.rotation % 90 == 0) { "Invalid picture adjustment" }
        }
        project.music?.let { require(it.startMs >= 0 && it.durationMs - it.startMs >= MIN_CLIP_MS && it.volume in 0f..1f) { "Invalid music trim or volume" } }
    }
    fun offset(project: StudioProject, id: String): Long = project.clips.takeWhile { it.id != id }.sumOf { it.durationMs }
    fun split(clip: StudioClip, outputPositionMs: Long): List<StudioClip> {
        val sourceAt = clip.startMs + (outputPositionMs * clip.speed).roundToLong()
        require(sourceAt - clip.startMs >= MIN_CLIP_MS && clip.endMs - sourceAt >= MIN_CLIP_MS) { "Move the playhead away from the clip edges" }
        return listOf(clip.copy(endMs = sourceAt), clip.copy(id = UUID.randomUUID().toString(), startMs = sourceAt))
    }
    fun beatMs(index: Int, bpm: Int, offsetMs: Long = 0): Long {
        require(bpm in 30..300 && index >= 0)
        return offsetMs + (index * 60000.0 / bpm).roundToLong()
    }
    fun nearestBeat(timeMs: Long, bpm: Int, offsetMs: Long): Long {
        require(bpm in 30..300)
        val index = ((timeMs - offsetMs) * bpm / 60000.0).roundToLong().coerceAtLeast(0)
        return offsetMs + (index * 60000.0 / bpm).roundToLong()
    }
    /** Cuts at an explicit BPM grid. This is not audio beat detection. */
    fun cutToBeats(project: StudioProject, beatsPerClip: Int): StudioProject {
        require(beatsPerClip in 1..32 && project.bpm in 30..300)
        var cursor = 0L
        val clips = project.clips.mapIndexed { i, c ->
            val target = beatMs((i + 1) * beatsPerClip, project.bpm, project.beatOffsetMs)
            val length = target - cursor
            val sourceLength = (length * c.speed).roundToLong()
            require(length >= MIN_CLIP_MS && c.endMs - c.startMs >= sourceLength) { "${c.name} is too short for $beatsPerClip beats. Trim or slow it down first." }
            cursor = target
            c.copy(endMs = c.startMs + sourceLength)
        }
        return project.copy(clips = clips)
    }
}

/** Pure timeline history: edits are undoable, selection/playback aren't edits. */
class StudioHistory {
    private val past = ArrayDeque<StudioProject>()
    private val future = ArrayDeque<StudioProject>()
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()
    fun record(before: StudioProject) { past.addLast(before); if (past.size > 80) past.removeFirst(); future.clear() }
    fun undo(current: StudioProject): StudioProject? = if (past.isEmpty()) null else { future.addLast(current); past.removeLast() }
    fun redo(current: StudioProject): StudioProject? = if (future.isEmpty()) null else { past.addLast(current); future.removeLast() }
    fun clear() { past.clear(); future.clear() }
}
