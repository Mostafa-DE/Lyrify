package app.lyrify.tones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TonePreviewTest {
    private val first = part(startMs = 12_000)
    private val playing = PreviewState().onPlay(first).state

    // Play and stop

    @Test
    fun `play plays the part from its start`() {
        val step = PreviewState().onPlay(first)
        assertEquals(PreviewCommand.Play(first), step.command)
        assertEquals(first, step.state.playing)
    }

    @Test
    fun `stop stops, and a change afterwards plays nothing`() {
        val stopped = playing.onStop()
        assertEquals(PreviewCommand.Stop, stopped.command)
        assertNull(stopped.state.playing)
        assertNull(stopped.state.onPartChanged(part(startMs = 40_000), nowMs = 1_000, dragging = false).command)
    }

    @Test
    fun `a preview that ended by itself stays ended when the part changes`() {
        val ended = playing.onEnded()
        assertNull(ended.command)
        assertNull(ended.state.playing)
        assertNull(ended.state.onPartChanged(part(startMs = 40_000), nowMs = 1_000, dragging = false).command)
    }

    // Changes while stopped

    @Test
    fun `a stopped preview does not start when the part changes`() {
        val idle = PreviewState()
        for (dragging in listOf(false, true)) {
            val step = idle.onPartChanged(part(startMs = 40_000), nowMs = 1_000, dragging = dragging)
            assertNull(step.command)
            assertEquals(idle, step.state)
        }
    }

    // Changes while playing

    @Test
    fun `a new start jumps there at once and keeps playing (a lyric line, a second earlier or later, a tap)`() {
        val moved = part(startMs = 47_500)
        val step = playing.onPartChanged(moved, nowMs = 1_000, dragging = false)
        assertEquals(PreviewCommand.Jump(moved), step.command)
        assertEquals(moved, step.state.playing)
        val nudged = part(startMs = 48_500)
        assertEquals(PreviewCommand.Jump(nudged), step.state.onPartChanged(nudged, nowMs = 1_010, dragging = false).command)
    }

    @Test
    fun `another length or fade jumps to the start with them`() {
        val shorter = part(startMs = 12_000, lengthMs = 15_000)
        assertEquals(PreviewCommand.Jump(shorter), playing.onPartChanged(shorter, nowMs = 1_000, dragging = false).command)
        val noFade = part(startMs = 12_000, fadeInMs = 0)
        assertEquals(PreviewCommand.Jump(noFade), playing.onPartChanged(noFade, nowMs = 1_000, dragging = false).command)
    }

    @Test
    fun `the same part again changes nothing`() {
        val step = playing.onPartChanged(first, nowMs = 1_000, dragging = false)
        assertNull(step.command)
        assertEquals(playing, step.state)
    }

    // Drags

    @Test
    fun `a drag jumps at once, then at most every 150 ms`() {
        var state = playing
        val jumps = listOf(0L, 40L, 149L, 150L, 220L, 300L, 310L).mapNotNull { t ->
            val step = state.onPartChanged(part(startMs = 20_000 + t), nowMs = 5_000 + t, dragging = true)
            state = step.state
            (step.command as? PreviewCommand.Jump)?.let { t }
        }
        assertEquals(listOf(0L, 150L, 300L), jumps)
        assertEquals(part(startMs = 20_300), state.playing)
    }

    @Test
    fun `letting go jumps to where the drag ended`() {
        val dragged = playing.onPartChanged(part(startMs = 20_000), nowMs = 5_000, dragging = true).state
        val held = dragged.onPartChanged(part(startMs = 21_000), nowMs = 5_060, dragging = true)
        assertNull(held.command)
        val released = held.state.onPartChanged(part(startMs = 21_000), nowMs = 5_070, dragging = false)
        assertEquals(PreviewCommand.Jump(part(startMs = 21_000)), released.command)
        assertEquals(part(startMs = 21_000), released.state.playing)
    }

    @Test
    fun `letting go where the last jump already went changes nothing`() {
        val dragged = playing.onPartChanged(part(startMs = 20_000), nowMs = 5_000, dragging = true).state
        assertNull(dragged.onPartChanged(part(startMs = 20_000), nowMs = 5_010, dragging = false).command)
    }

    @Test
    fun `the first drag event after play jumps at once`() {
        val step = PreviewState().onPlay(first).state.onPartChanged(part(startMs = 20_000), nowMs = 0, dragging = true)
        assertEquals(PreviewCommand.Jump(part(startMs = 20_000)), step.command)
    }

    private fun part(startMs: Long, lengthMs: Long = 30_000, fadeInMs: Long = 1_500, fadeOutMs: Long = 1_500) =
        PreviewSource.Window(SONG, ToneWindow(startMs, lengthMs), fadeInMs, fadeOutMs)

    private companion object {
        const val SONG = "/storage/emulated/0/Music/Tin Kettle Parade.mp3"
    }
}
