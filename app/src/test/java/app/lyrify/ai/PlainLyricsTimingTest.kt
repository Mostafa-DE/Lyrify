package app.lyrify.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Timing known lyrics: S4 gated by S2c. The goldens come from the bench's own code on synthetic, made-up text: S1's from
 * `s1_starts`, `gain` and rapidfuzz (tools/s1_golden.py), the whole method's from `npu_timing.py` and
 * `npu_timing_report.py` on synthetic emissions (tools/timing_golden.py).
 */
class PlainLyricsTimingTest {
    private val golden = Json.parseToJsonElement(File("src/test/resources/npu/s1_golden.json").readText()).jsonObject

    private fun JsonObject.texts(key: String) = getValue(key).jsonArray.map { it.jsonPrimitive.content }

    // S1: lines to segments

    @Test
    fun `S1 line starts match the bench's s1_starts on synthetic songs, to the bit`() {
        val cases = golden.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(8, cases.size)
        cases.forEachIndexed { k, case ->
            val segments = case.getValue("segments").jsonArray.map { s ->
                val o = s.jsonObject
                Segment(o.getValue("start").jsonPrimitive.double, o.getValue("end").jsonPrimitive.double, o.getValue("text").jsonPrimitive.content)
            }
            val starts = SegmentAlignment.lineStarts(case.texts("lines"), segments, case.getValue("duration_sec").jsonPrimitive.double)
            assertEquals("case $k", case.getValue("expected_starts").jsonArray.map { it.jsonPrimitive.double }, starts.starts)
            assertEquals("case $k", case.getValue("expected_anchored").jsonArray.map { it.jsonPrimitive.boolean }, starts.anchored)
        }
    }

    @Test
    fun `the similarity is rapidfuzz's ratio over code points, and the gain the bench's`() {
        golden.getValue("ratios").jsonArray.map { it.jsonObject }.forEach { r ->
            val a = r.getValue("a").jsonPrimitive.content
            val b = r.getValue("b").jsonPrimitive.content
            assertEquals("$a | $b", r.getValue("ratio").jsonPrimitive.double, SegmentAlignment.similarity(a, b), 1e-12)
        }
        golden.getValue("gains").jsonArray.map { it.jsonObject }.forEach { g ->
            val x = g.getValue("x").jsonPrimitive.content.codePoints().toArray()
            val y = g.getValue("y").jsonPrimitive.content.codePoints().toArray()
            val lcs = Lcs(x).also { l -> y.forEach(l::feed) }.length()
            assertEquals(g.getValue("gain").jsonPrimitive.double, SegmentAlignment.gain(x.size, y.size, lcs), 1e-9)
        }
    }

    @Test
    fun `the bit-parallel LCS is the textbook one, across 64-bit words`() {
        val rnd = java.util.Random(11)
        repeat(200) {
            val a = IntArray(rnd.nextInt(150)) { rnd.nextInt(4) }
            val b = IntArray(rnd.nextInt(150)) { rnd.nextInt(4) }
            val table = Array(a.size + 1) { IntArray(b.size + 1) }
            for (i in 1..a.size) for (j in 1..b.size) {
                table[i][j] = if (a[i - 1] == b[j - 1]) table[i - 1][j - 1] + 1 else maxOf(table[i - 1][j], table[i][j - 1])
            }
            assertEquals(table[a.size][b.size], Lcs(a).also { l -> b.forEach(l::feed) }.length())
        }
    }

    @Test
    fun `lines with no segment are interpolated by word position, and with no segment at all start at 0`() {
        val lines = listOf("ka lo", "mi ren tas vo", "qui zan")
        val segments = listOf(Segment(10.0, 12.0, " ka lo"), Segment(20.0, 22.0, " qui zan"))
        val starts = SegmentAlignment.lineStarts(lines, segments, 60.0)
        assertEquals(listOf(true, false, true), starts.anchored)
        // Words before each line: 0, 2, 6; the middle line sits at 2 / 6 of the way.
        assertEquals(listOf(10.0, 10.0 + 2.0 / 6 * 10.0, 20.0), starts.starts)
        assertEquals(listOf(0.0, 0.0, 0.0), SegmentAlignment.lineStarts(lines, emptyList(), 60.0).starts)
    }

    // The whole method: S4 gated by S2c

    @Test
    fun `S4, S2c, the agreement and the verdict match the bench on synthetic songs, to the bit`() {
        val songs = timing.getValue("songs").jsonArray.map { it.jsonObject }
        assertEquals(5, songs.size)
        songs.forEach { song ->
            val name = song.getValue("name").jsonPrimitive.content
            val lines = song.texts("lines")
            val segments = song.getValue("segments").jsonArray.map { s ->
                val o = s.jsonObject
                Segment(o.getValue("start").jsonPrimitive.double, o.getValue("end").jsonPrimitive.double, o.getValue("text").jsonPrimitive.content)
            }
            val duration = song.getValue("duration_sec").jsonPrimitive.double
            val emissions = emissionsOf(song)
            assertEquals(name, song.numbers("expected_s4"), Ctc.alignLines(emissions, lines))
            val s1 = SegmentAlignment.lineStarts(lines, segments.filter { it.text != " ♪" }, duration).starts
            assertEquals(name, song.numbers("expected_s1"), s1)
            val s2c = Ctc.placedOn(emissions, lines, Ctc.windows(s1, duration), context = 1).mapIndexed { k, t -> t ?: s1[k] }
            assertEquals(name, song.numbers("expected_s2c"), s2c)

            val timed = PlainLyricsTiming.time(segments, duration, lines) { emissions }

            assertEquals(name, song.numbers("expected_times"), timed.times)
            assertEquals(name, song.getValue("expected_agreement").jsonPrimitive.double, timed.confidence, 0.0)
            val monotonic = song.getValue("expected_monotonic").jsonPrimitive.double
            val expected = when {
                song.getValue("expected_accepted").jsonPrimitive.boolean -> null
                timed.confidence < 0.8 -> RejectReason.LOW_AGREEMENT
                else -> RejectReason.NOT_MONOTONIC.also { assertTrue(name, monotonic < 0.9) }
            }
            assertEquals(name, expected, timed.rejected)
        }
    }

    @Test
    fun `a long song's S4 made a block at a time gives the same times and verdict, to the bit`() {
        timing.getValue("songs").jsonArray.map { it.jsonObject }.forEach { song ->
            val (lines, segments, duration) = inputs(song)
            val emissions = emissionsOf(song)
            val states = 2 * Ctc.encode(lines).tokens.size + 1
            val oneTable = PlainLyricsTiming.time(segments, duration, lines) { emissions }
            assertEquals(Trellis(emissions.frames, states), oneTable.trellis)
            // 50 frames per block: dozens of blocks per song, each but the last computed again on the way back.
            assertTrue(emissions.frames > 20 * 50 && Ctc.blockFrames(emissions.frames, states, 50L * states) == 50)
            val blocks = PlainLyricsTiming.time(segments, duration, lines, budget = Ctc.Budget(tableCells = 50L * states)) { emissions }
            assertEquals(song.getValue("name").jsonPrimitive.content, oneTable, blocks)
        }
    }

    @Test
    fun `a song past the CPU cap is rejected without an alignment, its size noted`() {
        val song = timing.getValue("songs").jsonArray.first().jsonObject
        val (lines, segments, duration) = inputs(song)
        val emissions = emissionsOf(song)
        val cells = emissions.frames.toLong() * (2 * Ctc.encode(lines).tokens.size + 1)
        val over = PlainLyricsTiming.time(segments, duration, lines, budget = Ctc.Budget(maxCells = cells - 1)) { emissions }
        assertEquals(RejectReason.TOO_LONG, over.rejected)
        assertEquals(0.0, over.confidence, 0.0)
        assertEquals(cells, over.trellis?.cells)
        assertNull(PlainLyricsTiming.time(segments, duration, lines, budget = Ctc.Budget(maxCells = cells)) { emissions }.rejected)
    }

    @Test
    fun `the whole song's emissions are the bench's 30 s windows every 25 s, trimmed and joined`() {
        timing.getValue("whole_song").jsonArray.map { it.jsonObject }.forEach { case ->
            val samples = case.getValue("samples").jsonPrimitive.int
            val stem = FloatArray(samples) { it.toFloat() }
            val steps = mutableListOf<Pair<Int, Int>>()
            // A stand-in for wav2vec2 (the bench's Probe): frames as its conv stack makes them, class 0 says which.
            val whole = Ctc.wholeSong(stem, { done, total -> steps += done to total }) { window ->
                val frames = maxOf(0, (window.size - 400) / 320 + 1)
                Emissions(frames, Ctc.CLASSES, DoubleArray(frames * Ctc.CLASSES) { i -> if (i % Ctc.CLASSES == 0) window[0] / 320.0 + i / Ctc.CLASSES else 0.0 })
            }
            val expected = case.getValue("expected_id_runs").jsonArray.flatMap { r ->
                val (first, count) = r.jsonArray.map { it.jsonPrimitive.int }
                (first until first + count).map(Int::toDouble)
            }
            assertEquals("$samples samples", expected, (0 until whole.frames).map { whole[it, 0] })
            val windows = Ctc.songWindows(samples.toDouble() / Rates.SPEECH).size
            assertEquals((1..windows).map { it to windows }, steps)
        }
    }

    @Test
    fun `the numbers are recommendation_json's`() {
        val rec = Json.parseToJsonElement(File("src/test/resources/npu/timing_recommendation.json").readText()).jsonObject
        assertEquals("2026-11-03", rec.getValue("version").jsonPrimitive.content)
        val align = rec.getValue("align_mode").jsonObject
        assertEquals("S4_global_ctc_gated_by_S2c", align.getValue("method").jsonPrimitive.content)
        val aligner = align.getValue("aligner").jsonObject
        // The recommendation was made with Xenova's fp32 wav2vec2; the app ships its q4 file instead, validated in the
        // bench's model size test (2026-11-04, part 1, the method unchanged): the same 10 of 12 songs accepted, 57% / 71% /
        // 84% of their lines within 0.3 / 0.5 / 1.0 s (fp32: 57% / 71% / 85%).
        assertEquals("e46614273f03ff4b87923a965e417fa72004825522cb007c9c25633b8475490d", aligner.getValue("sha256").jsonPrimitive.content)
        assertEquals("337db946188e4b3d0b4a2641dc30727a4792f70aeb1750f65a20fae471f9f217", app.lyrify.models.ModelSpec.WAV2VEC2.sha256)
        assertTrue(app.lyrify.models.ModelSpec.WAV2VEC2.url.endsWith("/a19f851b3d42865797e410752b4c570c871e4825/onnx/model_q4.onnx"))
        assertEquals(aligner.getValue("frame_sec").jsonPrimitive.double, Ctc.FRAME_SEC, 0.0)
        val em = align.getValue("emissions").jsonObject
        assertEquals(em.getValue("window_sec").jsonPrimitive.double, Ctc.SONG_WINDOW_SEC, 0.0)
        assertEquals(em.getValue("hop_sec").jsonPrimitive.double, Ctc.SONG_HOP_SEC, 0.0)
        assertEquals(em.getValue("trim_sec_inner_edges").jsonPrimitive.double, (Ctc.SONG_WINDOW_SEC - Ctc.SONG_HOP_SEC) / 2, 0.0)
        val p = PlainLyricsTiming.PARAMS
        assertEquals(PlainLyricsTiming.RECOMMENDED, p)
        assertEquals(align.getValue("line_times").jsonObject.getValue("offset_sec").jsonPrimitive.double, p.offsetSec, 0.0)
        val conf = align.getValue("confidence").jsonObject
        val s1 = conf.getValue("segments_to_lines_S1").jsonObject
        assertEquals(s1.getValue("tau").jsonPrimitive.double, SegmentAlignment.TAU, 0.0)
        assertEquals(s1.getValue("max_lines_per_segment").jsonPrimitive.int, SegmentAlignment.MAX_LINES_PER_SEGMENT)
        assertEquals(s1.getValue("max_segments_per_line").jsonPrimitive.int, SegmentAlignment.MAX_SEGMENTS_PER_LINE)
        assertEquals(s1.getValue("offset_sec").jsonPrimitive.double, 0.0, 0.0)
        val windows = conf.getValue("windows_S2c").jsonObject
        assertEquals(windows.getValue("group_lines").jsonPrimitive.int, Ctc.LINES_PER_WINDOW)
        assertEquals(windows.getValue("context_lines").jsonPrimitive.int, p.contextLines)
        assertEquals(windows.getValue("margin_sec").jsonPrimitive.double, Ctc.MARGIN_SEC, 0.0)
        assertEquals(windows.getValue("max_window_sec").jsonPrimitive.double, Ctc.MAX_WINDOW_SEC, 0.0)
        assertEquals(1.0, Ctc.MIN_WINDOW_SEC, 0.0) // "a window under 1 s ... keeps S1 for its lines"
        assertEquals(conf.getValue("agreement_window_sec").jsonPrimitive.double, p.agreementWindowSec, 0.0)
        val accept = align.getValue("accept").jsonObject
        assertEquals(accept.getValue("agreement_min").jsonPrimitive.double, p.minAgreement, 0.0)
        assertEquals(accept.getValue("monotonic_min").jsonPrimitive.double, p.minMonotonic, 0.0)
        assertEquals(accept.getValue("monotonic_step_sec").jsonPrimitive.double, p.minStepSec, 0.0)
        assertEquals(rec.getValue("transcribe_mode").jsonObject.getValue("offset_sec").jsonPrimitive.double, p.transcribeOffsetSec, 0.0)
        assertEquals(0.45, SegmentAlignment.SEC_PER_WORD, 0.0) // "before the first / after the last placed line 0.45 s per word"
    }

    @Test
    fun `the confidence is the share of lines S4 and S2c agree on within 0_5 s, accepted from 0_8`() {
        val s4 = (0 until 10).map { 10.0 + 3 * it }
        val twoOff = s4.mapIndexed { k, t -> if (k < 2) t + 0.6 else t + 0.5 } // 0.5 s still agrees
        assertEquals(TimedLines(s4, 0.8, null), PlainLyricsTiming.gate(s4, twoOff))
        val threeOff = s4.mapIndexed { k, t -> if (k < 3) t - 0.51 else t }
        assertEquals(TimedLines(s4, 0.7, RejectReason.LOW_AGREEMENT), PlainLyricsTiming.gate(s4, threeOff))
    }

    @Test
    fun `S4 times that do not rise by 0_2 s on 90 percent of the steps reject the song even when all agree`() {
        val s4 = (0 until 11).map { 10.0 + 3 * it }.toMutableList()
        s4[5] = s4[4] + 0.2 // one step of 0.2 s rises, ten steps: 100%
        assertNull(PlainLyricsTiming.gate(s4, s4).rejected)
        s4[5] = s4[4] + 0.19 // 9 of 10
        assertNull(PlainLyricsTiming.gate(s4, s4).rejected)
        s4[8] = s4[7] // 8 of 10
        assertEquals(RejectReason.NOT_MONOTONIC, PlainLyricsTiming.gate(s4, s4).rejected)
        assertNull(PlainLyricsTiming.gate(listOf(5.0), listOf(5.1)).rejected)
    }

    @Test
    fun `a line without letters takes the next placed line's start, at the end the previous one's`() {
        assertEquals(listOf(1.0, 3.0, 3.0, 3.0, 7.0, 7.0), PlainLyricsTiming.filled(listOf(1.0, null, null, 3.0, 7.0, null)))
        assertEquals(listOf(2.0, 2.0), PlainLyricsTiming.filled(listOf(null, 2.0)))
    }

    @Test
    fun `the offset moves every S4 start`() {
        val song = timing.getValue("songs").jsonArray.first().jsonObject
        val lines = song.texts("lines")
        val segments = song.getValue("segments").jsonArray.map { s ->
            val o = s.jsonObject
            Segment(o.getValue("start").jsonPrimitive.double, o.getValue("end").jsonPrimitive.double, o.getValue("text").jsonPrimitive.content)
        }
        val emissions = emissionsOf(song)
        val duration = song.getValue("duration_sec").jsonPrimitive.double
        val later = PlainLyricsTiming.time(segments, duration, lines, PlainLyricsTiming.PARAMS.copy(offsetSec = 0.25)) { emissions }
        song.numbers("expected_times").zip(later.times).forEach { (t, moved) -> assertEquals(checkNotNull(t) + 0.25, moved, 1e-12) }
    }

    @Test
    fun `nothing heard to place the lyrics by is rejected before wav2vec2 runs`() {
        var ran = false
        val timed = PlainLyricsTiming.time(listOf(Segment(3.0, 5.0, " ¶¶")), 20.0, listOf("ka lo", "mi ren")) {
            ran = true
            error("not needed")
        }
        assertEquals(RejectReason.NO_WORDS, timed.rejected)
        assertEquals(false, ran)
    }

    @Test
    fun `lyrics the aligner cannot place at all are rejected`() {
        val silence = Emissions.of(List(100) { DoubleArray(Ctc.CLASSES) { c -> if (c == Ctc.BLANK) 0.0 else -50.0 } })
        // Whisper heard the digits, but they have no letter for the aligner.
        assertEquals(RejectReason.NO_ALIGNMENT, PlainLyricsTiming.time(listOf(Segment(0.5, 1.5, " 42 42")), 2.0, listOf("42", "4 2")) { silence }.rejected)
        // 2 s of audio (100 frames) cannot hold 120 letters.
        val long = List(20) { "kalomi" }.joinToString(" ")
        assertEquals(RejectReason.NO_ALIGNMENT, PlainLyricsTiming.time(listOf(Segment(0.0, 2.0, " $long")), 2.0, listOf(long)) { silence }.rejected)
    }

    private fun inputs(song: JsonObject): Triple<List<String>, List<Segment>, Double> = Triple(
        song.texts("lines"),
        song.getValue("segments").jsonArray.map { s ->
            val o = s.jsonObject
            Segment(o.getValue("start").jsonPrimitive.double, o.getValue("end").jsonPrimitive.double, o.getValue("text").jsonPrimitive.content)
        },
        song.getValue("duration_sec").jsonPrimitive.double,
    )

    private fun emissionsOf(song: JsonObject): Emissions {
        val rows = codebook
        val frames = song.getValue("frames").jsonArray.flatMap { r ->
            val (index, count) = r.jsonArray.map { it.jsonPrimitive.int }
            List(count) { rows[index] }
        }
        return Emissions.of(frames)
    }

    private val timing = Json.parseToJsonElement(File("src/test/resources/npu/timing_golden.json").readText()).jsonObject

    private val codebook: List<DoubleArray> by lazy {
        timing.getValue("codebook").jsonArray.map { r -> r.jsonArray.map { it.jsonPrimitive.double }.toDoubleArray() }
    }

    private fun JsonObject.numbers(key: String): List<Double?> = getValue(key).jsonArray.map { it.jsonPrimitive.doubleOrNull }
}
