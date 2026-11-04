package app.lyrify.ai

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocalGateTest {
    private fun frames(vocals: DoubleArray, mix: DoubleArray = DoubleArray(vocals.size) { 1.0 }) = GateFrames(vocals, mix)

    // The gate

    @Test
    fun `share counts active frames over valid frames and runs break on any inactive frame`() {
        val g = frames(doubleArrayOf(0.3, 0.1, 0.25, 1.0, 0.21, 0.5), doubleArrayOf(1.0, 1.0, 1.0, 0.0005, 1.0, 1.0)).gate()
        assertEquals(5, g.validFrames) // frame 3 is silent mix
        assertEquals(4, g.activeFrames)
        assertEquals(0.8, g.share, 1e-12)
        assertEquals(2, g.longestRunSec) // the silent frame ends the run 0..2
        assertEquals(6, g.frames)
    }

    @Test
    fun `vocals must exceed 0_2 times the mix, not equal it`() {
        assertEquals(1, frames(doubleArrayOf(0.2, 0.2000001)).gate().activeFrames)
    }

    @Test
    fun `mix RMS must exceed 1e-3 for a frame to count`() {
        val g = frames(doubleArrayOf(0.5, 0.5), doubleArrayOf(0.001, 0.0011)).gate()
        assertEquals(1, g.validFrames)
        assertEquals(1.0, g.share, 0.0)
    }

    @Test
    fun `silence gives a zero share instead of dividing by zero`() {
        val g = frames(doubleArrayOf(0.0, 0.0), doubleArrayOf(0.0, 0.0)).gate()
        assertEquals(0.0, g.share, 0.0)
        assertEquals(0, g.longestRunSec)
    }

    @Test
    fun `Whisper's words per minute of song decide, above 9_8`() {
        assertFalse(VocalGate.isVocal(0.0))
        assertFalse(VocalGate.isVocal(9.8))
        assertTrue(VocalGate.isVocal(9.81))
        assertTrue(VocalGate.isVocal(40.0))
    }

    @Test
    fun `words per minute count the kept segments' words over the whole song, not junk or fillers over non-vocal audio`() {
        // 60 s; frames 0-29 vocal, 30-59 not.
        val frames = GateFrames(DoubleArray(60) { if (it < 30) 0.5 else 0.0 }, DoubleArray(60) { 1.0 })
        val segments = listOf(
            Segment(2.0, 5.0, " Ka lo, mi-ren tas!"), // 5 words, as the bench's normalize_text splits them
            Segment(6.0, 8.0, " ¶¶"), // junk
            Segment(10.0, null, " vo qui"), // no end: kept on its start's frame
            Segment(40.0, 43.0, " Thank you."), // a filler over non-vocal frames
            Segment(20.0, 23.0, " Thank you."), // the same over singing counts
        )
        assertEquals(9.0, VocalGate.wordsPerMinute(segments, frames, durationSec = 60.0), 1e-12)
        assertEquals(18.0, VocalGate.wordsPerMinute(segments, frames, durationSec = 30.0), 1e-12)
        assertEquals(0.0, VocalGate.wordsPerMinute(emptyList(), frames, durationSec = 60.0), 0.0)
        assertEquals(0.0, VocalGate.wordsPerMinute(segments, frames, durationSec = 0.0), 0.0)
    }

    @Test
    fun `the active share of a time range counts every frame from int(start) to ceil(end)`() {
        val f = frames(doubleArrayOf(0.5, 0.5, 0.1, 0.1, 0.5), doubleArrayOf(1.0, 1.0, 1.0, 0.0, 1.0))
        assertEquals(2.0 / 3, f.activeShare(0.4, 2.2), 1e-12) // frames 0, 1, 2
        assertEquals(0.0, f.activeShare(2.0, 2.0), 0.0) // at least one frame: 2
        assertEquals(0.5, f.activeShare(3.5, 9.0), 0.0) // frames 3 (invalid counts as inactive) and 4, cut at the end
        assertEquals(0.0, f.activeShare(7.0, 9.0), 0.0) // past the end
    }

    // Vocal chunks. Every expected list below is what lyrify-bench phone_wcpp_variants.py vocal_chunks returns for
    // the same frames and stem (the spike checked them by running it on these inputs).

    @Test
    fun `a vocal run is padded by 1 s on both sides`() {
        assertEquals(listOf(VocalChunk(4.0, 9.0)), chunks(active(20, 5, 6, 7), seconds = 20.0))
    }

    @Test
    fun `runs at most 2 s apart after padding are joined`() {
        assertEquals(listOf(VocalChunk(1.0, 10.0)), chunks(active(20, 2, 3, 7, 8), seconds = 20.0))
    }

    @Test
    fun `separate runs are packed together while the span stays within 30 s`() {
        assertEquals(listOf(VocalChunk(1.0, 12.0)), chunks(active(20, 2, 10), seconds = 20.0))
        assertEquals(listOf(VocalChunk(4.0, 7.0), VocalChunk(69.0, 72.0)), chunks(active(80, 5, 70), seconds = 80.0))
    }

    @Test
    fun `no vocals, or vocals over a silent mix, give no chunks`() {
        assertEquals(emptyList<VocalChunk>(), chunks(active(20), seconds = 20.0))
        val mix = DoubleArray(20) { if (it == 5) 0.001 else 1.0 }
        assertEquals(emptyList<VocalChunk>(), chunks(active(20, 5), seconds = 20.0, mix = mix))
    }

    @Test
    fun `padding stops at the start and the end of the stem`() {
        assertEquals(listOf(VocalChunk(0.0, 10.5)), chunks(active(10, 0, 9), seconds = 10.5))
    }

    @Test
    fun `a run over 30 s is cut at the quietest 100 ms, both pieces at least 3 s`() {
        val all50 = active(50, *IntArray(50) { it })
        assertEquals(listOf(VocalChunk(0.0, 27.0), VocalChunk(27.0, 50.0)), chunks(all50, 50.0, quiet = listOf(27.0 to 27.1)))
        val all40 = active(40, *IntArray(40) { it })
        // No quiet spot: the first frame of the search window (10 s to 30 s) wins.
        assertEquals(listOf(VocalChunk(0.0, 10.0), VocalChunk(10.0, 40.0)), chunks(all40, 40.0))
    }

    @Test
    fun `a run over 60 s is cut between 15 and 30 s from its start, repeatedly`() {
        val all70 = active(70, *IntArray(70) { it })
        assertEquals(
            listOf(VocalChunk(0.0, 20.0), VocalChunk(20.0, 45.5), VocalChunk(45.5, 70.0)),
            chunks(all70, 70.0, quiet = listOf(20.0 to 20.1, 45.5 to 45.6)),
        )
    }

    @Test
    fun `a cut piece is not packed with a neighbour when the span would pass 30 s`() {
        val frames = active(45, *(IntArray(33) { it } + 40))
        assertEquals(
            listOf(VocalChunk(0.0, 4.0), VocalChunk(4.0, 34.0), VocalChunk(39.0, 42.0)),
            chunks(frames, 45.0, quiet = listOf(4.0 to 4.1)),
        )
    }

    @Test
    fun `a chunk's samples are the stem from int(start x rate) to int(end x rate)`() {
        val stem = FloatArray(100) { it.toFloat() }
        assertArrayEquals(floatArrayOf(12f, 13f, 14f), VocalChunk(1.23, 1.5).slice(stem, 10), 0f)
        assertEquals(5, VocalChunk(9.5, 10.5).slice(stem, 10).size) // never past the end
    }

    // Golden: the bench's own gate frames, 100 ms stem RMS and chunks of three set A songs (gate_chunks.json).

    @Test
    fun `gate share and vocal chunks match the bench on songs b48d33c2794b, 4760f17a9f0e and f50d3de0df5d`() {
        golden.forEach { song ->
            val frames = GateFrames(song.doubles("vocal_rms").toDoubleArray(), song.doubles("mix_rms").toDoubleArray())
            assertEquals(song.str("id"), song.num("expected_share"), frames.gate().share, 0.0)
            val expected = song.getValue("expected_chunks").pairs().map { (a, b) -> VocalChunk(a, b) }
            val duration = song.num("stem_samples") / Rates.SPEECH
            assertEquals(song.str("id"), expected, VocalChunks.plan(frames, song.float32s("rms_100ms"), duration))
        }
    }

    @Test
    fun `the vocal share under each Whisper segment matches the bench`() {
        golden.forEach { song ->
            val frames = GateFrames(song.doubles("vocal_rms").toDoubleArray(), song.doubles("mix_rms").toDoubleArray())
            song.objects("segments").forEach { s ->
                assertEquals("${song.str("id")} at ${s.num("start")} s", s.num("vocal_share"), frames.activeShare(s.num("start"), s.num("end")), 0.0)
            }
        }
    }

    /** Vocal-stem frame RMS 0.5 where [on], 0.1 elsewhere, against a mix RMS of 1. */
    private fun active(frames: Int, vararg on: Int) = DoubleArray(frames) { if (it in on) 0.5 else 0.1 }

    private fun chunks(
        vocalRms: DoubleArray,
        seconds: Double,
        quiet: List<Pair<Double, Double>> = emptyList(),
        mix: DoubleArray = DoubleArray(vocalRms.size) { 1.0 },
    ): List<VocalChunk> {
        val rate = Rates.SPEECH
        val stem = FloatArray((seconds * rate).toInt()) { 0.5f }
        for ((a, b) in quiet) stem.fill(0.01f, Math.round(a * rate).toInt(), Math.round(b * rate).toInt())
        return VocalChunks.of(GateFrames(vocalRms, mix), stem)
    }

    private companion object {
        val golden: List<JsonObject> by lazy { Fixtures.songs("gate_chunks.json") }
    }
}
