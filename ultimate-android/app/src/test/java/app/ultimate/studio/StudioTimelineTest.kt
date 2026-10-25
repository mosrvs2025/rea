package app.ultimate.studio

import org.junit.Assert.*
import org.junit.Test

class StudioTimelineTest {
    private fun clip(speed: Float = 1f) = StudioClip(id = "a", uri = "content://video/1", name = "Clip", sourceMs = 10000, startMs = 1000, endMs = 9000, speed = speed)
    @Test fun splitMapsOutputClockBackToTrimmedSource() {
        val c = clip(2f)
        val parts = Timeline.split(c, 1500)
        assertEquals(4000, parts[0].endMs)
        assertEquals(4000, parts[1].startMs)
        assertEquals(c.endMs, parts[1].endMs)
        assertEquals(c.durationMs, parts.sumOf { it.durationMs })
        assertNotEquals(parts[0].id, parts[1].id)
    }
    @Test(expected = IllegalArgumentException::class) fun splitCannotCreateEmptyClip() { Timeline.split(clip(), 0) }
    @Test fun offsetsUseEditedDurations() {
        val a = clip(2f); val b = clip().copy(id = "b")
        assertEquals(4000, Timeline.offset(StudioProject(clips = listOf(a, b)), "b"))
    }
    @Test fun undoRedoPreserveEveryEditAndNewEditsInvalidateRedo() {
        val h = StudioHistory(); val a = StudioProject(name = "A"); val b = a.copy(name = "B")
        h.record(a); assertEquals(a, h.undo(b)); assertEquals(b, h.redo(a))
        h.undo(b); h.record(a); assertFalse(h.canRedo)
    }
    @Test fun beatsDoNotAccumulateIntegerRoundingDrift() {
        assertEquals(60000, Timeline.beatMs(126, 126))
        assertEquals(2500, Timeline.nearestBeat(2400, 120, 500))
    }
    @Test fun beatCutsRespectSpeedAndTrim() {
        val c = clip(2f); val p = StudioProject(clips = listOf(c), bpm = 120)
        val result = Timeline.cutToBeats(p, 4)
        assertEquals(2000, result.durationMs)
        assertEquals(5000, result.clips[0].endMs)
    }
    @Test(expected = IllegalArgumentException::class) fun shortSourceDoesNotSilentlyBreakBeatGrid() {
        Timeline.cutToBeats(StudioProject(clips = listOf(clip().copy(endMs = 1500))), 4)
    }
    @Test(expected = IllegalArgumentException::class) fun roundedDurationCannotOverrunSource() {
        Timeline.cutToBeats(StudioProject(clips = listOf(clip(2f).copy(endMs = 4999)), bpm = 120), 4)
    }
    @Test fun beatOffsetIsPreservedInFirstCut() {
        val result = Timeline.cutToBeats(StudioProject(clips = listOf(clip()), bpm = 120, beatOffsetMs = 500), 4)
        assertEquals(2500, result.durationMs)
    }
    @Test fun canvasDimensionsAreEvenAndMatchRatio() {
        val p = StudioProject(shortSide = 1080)
        assertEquals(1080, p.width); assertEquals(1920, p.height)
        assertEquals(1920, p.copy(shape = CanvasShape.Landscape).width)
        assertEquals(1080, p.copy(shape = CanvasShape.Square).height)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidTrimIsRefusedBeforeRendering() { Timeline.validate(StudioProject(clips = listOf(clip().copy(endMs = 11000)))) }
    @Test(expected = IllegalArgumentException::class) fun invalidSpeedIsRefusedBeforeRendering() { Timeline.validate(StudioProject(clips = listOf(clip(Float.NaN)))) }
}
