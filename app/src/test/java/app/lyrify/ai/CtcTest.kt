package app.lyrify.ai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

class CtcTest {
    // Text encoding (ctc_align.words_of / encode_lines)

    @Test
    fun `lines encode to the bench's token ids`() {
        val fixture = Fixtures.load("ctc_viterbi.json")
        fixture.getValue("encodings").jsonArray.map { it.jsonObject }.forEach { e ->
            val line = e.str("line")
            assertEquals(line, e.strings("words"), Ctc.wordsOf(line))
            val target = Ctc.encode(listOf(line))
            assertEquals(line, e.ints("tokens"), target.tokens.toList())
            assertEquals(line, e.nullableInts("first_token_of_line"), target.firstTokenOfLine)
        }
        val together = fixture.getValue("together").jsonObject
        val target = Ctc.encode(together.strings("lines"))
        assertEquals(together.ints("tokens"), target.tokens.toList())
        assertEquals(together.nullableInts("first_token_of_line"), target.firstTokenOfLine)
    }

    @Test
    fun `letters only, apostrophes kept inside words, hyphens split, accents folded`() {
        assertEquals(listOf("ISN'T", "ODD"), Ctc.wordsOf("isn’t-odd!"))
        assertEquals(listOf("NAIVE", "STRASSE"), Ctc.wordsOf("naïve straße 42"))
        assertEquals(listOf("TIL"), Ctc.wordsOf("'til"))
        val target = Ctc.encode(listOf("a b", "", "c"))
        assertEquals(listOf(7, Ctc.SEPARATOR, 24, Ctc.SEPARATOR, 19), target.tokens.toList())
        assertEquals(listOf(0, null, 4), target.firstTokenOfLine)
    }

    // Viterbi (ctc_align.forced_align, checked against torchaudio by the export script)

    @Test
    fun `the Viterbi path and line starts match the bench on a real wav2vec2 window`() {
        // Song b48d33c2794b, two lines; the target is stored as token ids only. The log-probs are stored emissions (the
        // bench's int8-matmul-pc export made them), so this checks the aligner, whichever wav2vec2 file the app ships
        // (q4 since the model size test): no unit test runs the model itself.
        val window = Fixtures.load("ctc_viterbi.json").getValue("window").jsonObject
        val emissions = Emissions.of(window.getValue("log_probs").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray() })
        val target = Ctc.Target(window.ints("tokens").toIntArray(), window.nullableInts("first_token_of_line"))
        assertEquals(window.ints("expected_path"), Ctc.forcedAlign(emissions, target.tokens)?.toList())
        assertEquals(window.nullableDoubles("expected_starts_sec"), Ctc.align(emissions, target))
    }

    @Test
    fun `a window with fewer frames than the text needs has no alignment`() {
        val emissions = uniform(frames = 3)
        assertNotNull(Ctc.forcedAlign(emissions, intArrayOf(7, 7))) // 2 tokens + 1 repeat need 3 frames: fits
        assertNull(Ctc.forcedAlign(emissions, intArrayOf(7, 7, 8)))
        assertNull(Ctc.alignLines(emissions, listOf("aab")))
    }

    @Test
    fun `a clear emission gives its path, repeated letters separated by a blank`() {
        // Frames: blank, A, A, blank, A, B, blank.
        val labels = intArrayOf(Ctc.BLANK, 7, 7, Ctc.BLANK, 7, 24, Ctc.BLANK)
        val path = requireNotNull(Ctc.forcedAlign(confident(labels), intArrayOf(7, 7, 24)))
        assertArrayEquals(labels, path)
        assertEquals(listOf(TokenSpan(7, 1, 3), TokenSpan(7, 4, 5), TokenSpan(24, 5, 6)), Ctc.mergeTokens(path))
    }

    @Test
    fun `lines without letters get no time and the others start at their first letter`() {
        // "a" at frame 2, separator at 4, "b" at frame 6.
        val labels = intArrayOf(0, 0, 7, 0, Ctc.SEPARATOR, 0, 24, 0)
        assertEquals(listOf(2 * Ctc.FRAME_SEC, null, 6 * Ctc.FRAME_SEC), Ctc.alignLines(confident(labels), listOf("a", "42", "b")))
        assertEquals(listOf(null), Ctc.alignLines(confident(labels), listOf("!!")))
    }

    // Windows (phone_ctc.windows, G=6, M=5) and line times

    @Test
    fun `windows match the bench on all 12 set A songs`() {
        Fixtures.songs("consensus.json").forEach { song ->
            val expected = song.getValue("expected_windows").jsonArray.map { w ->
                val v = w.jsonArray.map { it.jsonPrimitive.content }
                CtcWindow(v[0].toInt(), v[1].toInt(), v[2].toDouble(), v[3].toDouble())
            }
            assertEquals(song.str("id"), expected, Ctc.windows(song.doubles("nwsub"), song.num("stem_sec")))
        }
    }

    @Test
    fun `a window runs from 5 s before its first line to 5 s after the next group's first line, at most 60 s`() {
        val coarse = listOf(10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 100.0, 101.0)
        assertEquals(listOf(CtcWindow(0, 6, 5.0, 65.0), CtcWindow(6, 8, 95.0, 102.0)), Ctc.windows(coarse, durationSec = 102.0))
    }

    @Test
    fun `placed lines take the aligner's time in song seconds, the others none`() {
        val lines = listOf("a", "b", "c")
        val windows = listOf(CtcWindow(0, 2, 0.5, 4.0), CtcWindow(2, 3, 25.0, 35.0))
        val first = confident(intArrayOf(0, 7, 0, Ctc.SEPARATOR, 24, 0))
        assertEquals(listOf(0.5 + 1 * Ctc.FRAME_SEC, 0.5 + 4 * Ctc.FRAME_SEC, null), Ctc.placed(lines, windows, listOf(first, null)))
        // A window too short for its text places none of its lines.
        assertEquals(listOf(null, null, null), Ctc.placed(lines, windows, listOf(confident(intArrayOf(0, 7)), null)))
    }

    @Test
    fun `with context lines a window also aligns its neighbours' text, and keeps only its own lines' times`() {
        val lines = listOf("a", "b", "c")
        val windows = listOf(CtcWindow(1, 2, 0.5, 4.0))
        // Frames: blank, A, blank, |, B, |, C: "a b c" aligned together, only "b" kept.
        val em = confident(intArrayOf(0, 7, 0, Ctc.SEPARATOR, 24, Ctc.SEPARATOR, 19, 0))
        assertEquals(listOf(null, 0.5 + 4 * Ctc.FRAME_SEC, null), Ctc.placed(lines, windows, listOf(em), context = 1))
        // Without them, "b" alone is spread over the same frames.
        assertEquals(1, Ctc.placed(lines, windows, listOf(em)).count { it != null })
    }

    @Test
    fun `a window's samples are the stem from round(start x rate) to round(end x rate)`() {
        val stem = FloatArray(32_000) { it.toFloat() }
        val slice = Ctc.samples(stem, 0.50003, 1.00004)
        assertEquals(8000f, slice.first(), 0f)
        assertEquals(8001, slice.size) // samples 8000 until 16001
        assertEquals(0, Ctc.samples(stem, 3.0, 4.0).size) // past the end
    }

    @Test
    fun `the whole song's windows start every 25 s and the last one ends with the song`() {
        assertEquals(listOf(0.0 to 12.3), Ctc.songWindows(12.3))
        assertEquals(listOf(0.0 to 30.0), Ctc.songWindows(30.0))
        assertEquals(listOf(0.0 to 30.0, 25.0 to 30.01), Ctc.songWindows(30.01))
        assertEquals(listOf(0.0 to 30.0, 25.0 to 55.0, 50.0 to 61.0), Ctc.songWindows(61.0))
    }

    // A long song's trellis, a block of frames at a time

    @Test
    fun `a trellis read back in blocks gives the same path as one table, bit for bit`() {
        val rnd = java.util.Random(5)
        repeat(40) { round ->
            val frames = 20 + rnd.nextInt(300)
            val emissions = Emissions.of(
                List(frames) {
                    val z = DoubleArray(Ctc.CLASSES) { c -> rnd.nextGaussian() * 2 + if (c == Ctc.BLANK) 1.5 else 0.0 }
                    val top = z.max()
                    val lse = top + ln(z.sumOf { exp(it - top) })
                    DoubleArray(Ctc.CLASSES) { z[it] - lse }
                },
            )
            // Repeats on purpose: few distinct letters.
            val tokens = IntArray(1 + rnd.nextInt(frames / 3)) { 5 + rnd.nextInt(4) }
            val states = 2 * tokens.size + 1
            assertEquals(frames, Ctc.blockFrames(frames, states, Ctc.MAX_TABLE_CELLS)) // one table
            val whole = Ctc.forcedAlign(emissions, tokens)
            for (block in listOf(1, 2, 7)) {
                // Frames 20 and up: at least 3 blocks, each but the last computed again on the way back.
                assertTrue(Ctc.blockFrames(frames, states, block.toLong() * states) == block && frames / block >= 2)
                assertArrayEquals("round $round, $block frames per block", whole, Ctc.forcedAlign(emissions, tokens, Ctc.Budget(tableCells = block.toLong() * states)))
            }
        }
    }

    @Test
    fun `one table up to the budget, blocks from one cell past it, the same path either way`() {
        assertEquals(64_000_000L, Ctc.MAX_TABLE_CELLS)
        assertEquals(17_357, Ctc.blockFrames(17_357, 3_245, Ctc.MAX_TABLE_CELLS)) // set A's largest song: one table
        assertEquals(12_797, Ctc.blockFrames(36_000, 5_001, Ctc.MAX_TABLE_CELLS)) // a 12-minute song: three blocks
        // 100 frames x 5 states = 500 cells.
        assertEquals(100, Ctc.blockFrames(100, 5, 500))
        assertEquals(99, Ctc.blockFrames(100, 5, 499))
        val emissions = Emissions.of(List(100) { t -> DoubleArray(Ctc.CLASSES) { c -> -((t * 7 + c * 13) % 17) / 3.0 } })
        val one = Ctc.forcedAlign(emissions, intArrayOf(7, 8), Ctc.Budget(tableCells = 500))
        assertNotNull(one)
        assertArrayEquals(one, Ctc.forcedAlign(emissions, intArrayOf(7, 8), Ctc.Budget(tableCells = 499)))
    }

    @Test
    fun `a long alignment can be stopped while it runs, in the forward pass and while it reads blocks back`() {
        val emissions = uniform(frames = 3_000)
        val tokens = IntArray(100) { 5 + it % 4 }
        val heard = mutableListOf<Int>()
        Ctc.forcedAlign(emissions, tokens, Ctc.Budget(tableCells = 700L * 201), progress = { done, total ->
            assertEquals(3_000, total)
            heard += done
        })
        // Every 256 frames of the forward pass (1 to 2999), then again for the blocks read back (5 blocks of 700, 4 again).
        assertEquals((256 until 3_000 step 256).toList(), heard.take(11))
        assertTrue(heard.size > 11)
        // A progress that throws stops the alignment at once.
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            Ctc.forcedAlign(emissions, tokens, progress = { _, _ -> check(++calls < 3) { "stopped" } })
        }
        assertEquals(3, calls)
    }

    @Test
    fun `a trellis past the cost cap has no alignment`() {
        val emissions = uniform(frames = 100)
        assertNotNull(Ctc.forcedAlign(emissions, intArrayOf(7, 8), Ctc.Budget(maxCells = 500))) // 100 frames x 5 states
        assertNull(Ctc.forcedAlign(emissions, intArrayOf(7, 8), Ctc.Budget(maxCells = 499)))
    }

    // Model input and output

    @Test
    fun `a window is scaled to zero mean and unit variance`() {
        val x = Ctc.normalizeAudio(FloatArray(16_000) { (0.3 * sin(it * 0.01) + 0.1).toFloat() })
        val mean = x.average()
        assertEquals(0.0, mean, 1e-5)
        assertEquals(1.0, x.sumOf { (it - mean) * (it - mean) } / x.size, 1e-4)
        Ctc.normalizeAudio(FloatArray(8)).forEach { assertEquals(0f, it, 0f) } // silence stays silent (epsilon)
    }

    @Test
    fun `logits become log-probabilities per frame`() {
        val e = Emissions.fromLogits(floatArrayOf(1f, 2f, 3f, 0f, 0f, 0f), classes = 3)
        assertEquals(2, e.frames)
        assertEquals(1.0, (0 until 3).sumOf { exp(e[0, it]) }, 1e-12)
        assertEquals(ln(1.0 / 3), e[1, 2], 1e-12)
        assertEquals(1.0, e[0, 2] - e[0, 1], 1e-12)
    }

    /** Emissions where each frame's label in [labels] has probability 0.97. */
    private fun confident(labels: IntArray): Emissions = Emissions.of(
        labels.map { label -> DoubleArray(Ctc.CLASSES) { c -> if (c == label) ln(0.97) else ln(0.03 / (Ctc.CLASSES - 1)) } },
    )

    private fun uniform(frames: Int): Emissions = Emissions.of(List(frames) { DoubleArray(Ctc.CLASSES) { ln(1.0 / Ctc.CLASSES) } })
}
