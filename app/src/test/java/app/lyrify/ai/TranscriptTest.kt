package app.lyrify.ai

import app.lyrify.lyrics.LrcLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which segments are lyrics, and the transcribe job's lines. All text here is made up. */
class TranscriptTest {
    // Segment filters

    @Test
    fun `segments with no letter or digit are junk`() {
        listOf("¶¶", ".", "♪", "...", " - ", "", "♪ ♪").forEach { assertTrue(it, SegmentFilters.isJunk(it)) }
        listOf("Hello.", "99 boxes", "É", "x").forEach { assertFalse(it, SegmentFilters.isJunk(it)) }
    }

    @Test
    fun `a whole annotation in brackets or asterisks is junk, as in the bench`() {
        listOf("[Music]", "*music*", "(upbeat music)", " [Applause] ").forEach { assertTrue(it, SegmentFilters.isJunk(it)) }
        listOf("Music box on the shelf", "Oh (oh) yeah", "[Music] la la la la").forEach { assertFalse(it, SegmentFilters.isJunk(it)) }
    }

    @Test
    fun `classic fillers are recognised after normalisation`() {
        listOf("Thank you.", "THANK YOU!", "Thanks for watching!", "Thank you for watching.", "Thank you so much for listening",
            "you", "Bye bye.", "Subscribe to my channel", "*music*").forEach { assertTrue(it, SegmentFilters.isFiller(it)) }
        listOf("Thank you, sir", "I see you", "Goodbye to the harbour", "[Thank you]", "").forEach { assertFalse(it, SegmentFilters.isFiller(it)) }
    }

    @Test
    fun `a filler is dropped only over mostly non-vocal audio, anything else only when junk`() {
        // Frames 0-3 vocal, 4-9 not.
        val frames = GateFrames(DoubleArray(10) { if (it < 4) 0.5 else 0.0 }, DoubleArray(10) { 1.0 })
        fun keep(text: String, start: Double, end: Double?) = SegmentFilters.keep(Segment(start, end, text), frames)
        assertFalse(keep("Thank you.", 5.0, 8.0)) // 0% vocal
        assertFalse(keep("Thank you.", 2.0, 7.0)) // frames 2-6: 2 of 5 vocal
        assertTrue(keep("Thank you.", 1.0, 4.5)) // frames 1-4: 3 of 4 vocal
        assertTrue(keep("Thank you, sir", 5.0, 8.0)) // not a filler
        assertFalse(keep("¶¶", 0.0, 3.0)) // junk even over singing
        // Without an end, the frame of its start decides.
        assertTrue(keep("Thank you.", 2.5, null))
        assertFalse(keep("Thank you.", 6.5, null))
    }

    // Transcription lines

    @Test
    fun `each kept segment becomes a line at its start plus the offset, sorted, never before 0`() {
        val frames = GateFrames(DoubleArray(30) { 0.5 }, DoubleArray(30) { 1.0 })
        val segments = listOf(
            Segment(10.24, 13.0, " Second line"),
            Segment(0.04, 3.0, " First​ line "),
            Segment(5.0, 6.0, "¶¶"),
            Segment(20.0, null, "Open ended"),
        )
        assertEquals(
            listOf(LrcLine(40, "First line"), LrcLine(10_240, "Second line"), LrcLine(20_000, "Open ended")),
            Transcription.lines(segments, frames, offsetSec = 0.0),
        )
        assertEquals(
            listOf(LrcLine(0, "First line"), LrcLine(10_140, "Second line"), LrcLine(19_900, "Open ended")),
            Transcription.lines(segments, frames, offsetSec = -0.1),
        )
    }

    // Segments as the timing re-validation takes them

    @Test
    fun `a segment is cut to its chunk, its open end at the chunk's end, its times to the millisecond`() {
        val chunk = VocalChunk(20.1, 35.0)
        assertEquals(Segment(20.1, 24.42, "a"), Segment(20.1, 24.420000000000002, "a").within(chunk))
        assertEquals(Segment(30.0, 35.0, "b"), Segment(30.0, null, "b").within(chunk))
        // Greedy decoding can put a timestamp past a short chunk's audio.
        assertEquals(Segment(35.0, 35.0, "c"), Segment(48.1, 50.1, "c").within(chunk))
        assertEquals(Segment(33.0, 35.0, "d"), Segment(33.0, 49.0, "d").within(chunk))
        // An end before the start is the start.
        assertEquals(Segment(31.0, 31.0, "e"), Segment(31.0, 30.0, "e").within(chunk))
    }

    @Test
    fun `LRC milliseconds round half to even like Python`() {
        assertEquals(12_340L, lrcMillis(12.34))
        assertEquals(0L, lrcMillis(-0.06))
        assertEquals(62L, lrcMillis(0.0625)) // 62.5 ms
        assertEquals(188L, lrcMillis(0.1875)) // 187.5 ms
    }
}
