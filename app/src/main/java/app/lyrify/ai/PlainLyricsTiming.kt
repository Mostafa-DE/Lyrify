package app.lyrify.ai

import kotlin.math.abs
import kotlin.math.min

/**
 * Known plain lyrics timed on the recording: a time per line, the timing's [confidence] (0 to 1), and [rejected] when not
 * good enough to write. [trellis] is the size of the whole-song alignment, once the emissions were made.
 */
data class TimedLines(val times: List<Double>, val confidence: Double, val rejected: RejectReason?, val trellis: Trellis? = null)

/** The whole-song alignment's size: [frames] of emissions x [states] (twice the letters and separators, plus 1). */
data class Trellis(val frames: Int, val states: Int) {
    val cells: Long get() = frames.toLong() * states
}

/**
 * The numbers of the timing method, as lyrify-bench's timing re-validation recommends them
 * (handoff/npu-timing/recommendation.json, version 2026-11-03): [PlainLyricsTiming.PARAMS] is the one in use. The window
 * sizes are [Ctc]'s and the segment-to-line DP's [SegmentAlignment]'s.
 */
data class TimingParams(
    /** Where the numbers come from; the run log names it. */
    val source: String,
    /** Added to every S4 line start (`align_mode.line_times.offset_sec`). */
    val offsetSec: Double,
    /** Lines on each side of an S2c window's group aligned with it, their times discarded (`windows_S2c.context_lines`). */
    val contextLines: Int,
    /** S4 and S2c agree on a line within this (`confidence.agreement_window_sec`); the share of lines that agree is the confidence. */
    val agreementWindowSec: Double,
    /** The confidence a song needs to be written (`accept.agreement_min`). */
    val minAgreement: Double,
    /** The share of consecutive S4 line starts that must rise by at least [minStepSec] (`accept.monotonic_min`, `monotonic_step_sec`). */
    val minMonotonic: Double,
    val minStepSec: Double,
    /** The transcribe job: added to each kept segment's start for its LRC line (`transcribe_mode.offset_sec`; at least 0 in the end). */
    val transcribeOffsetSec: Double,
)

/**
 * Times known plain lyrics on the vocal stem's wav2vec2 emissions, with the NPU Whisper's segments as the confidence:
 * method "S4 gated by S2c" of lyrify-bench's timing re-validation (results/summary.md "NPU timing re-validation
 * (2026-11-03)", scripts/npu_timing.py and npu_timing_report.py).
 *
 * 1. S4, the line starts: one CTC forced alignment ([Ctc.alignLines]) of every lyric line over the whole song's
 *    emissions ([Ctc.wholeSong]); a line's start is its first token's first frame, plus [TimingParams.offsetSec]. A line
 *    with no letter for the aligner takes the next placed line's start (the previous one's at the end).
 * 2. S1 ([SegmentAlignment]): the lines are matched to the heard segments by text, each getting a coarse start.
 * 3. S2c: the lines again, in windows of [Ctc.LINES_PER_WINDOW] lines placed by S1 ([Ctc.windows]) with
 *    [TimingParams.contextLines] more on each side, aligned on slices of the same emissions ([Ctc.placedOn]); a line it
 *    does not place keeps its S1 start.
 * 4. The confidence is the share of lines whose S4 and S2c starts lie within [TimingParams.agreementWindowSec]. A song
 *    is written when it is at least [TimingParams.minAgreement] and [TimingParams.minMonotonic] of the S4 starts rise by
 *    at least [TimingParams.minStepSec].
 *
 * Whisper does not move a line; it only says whether the lines sit where it heard them. One pure unit: the segments, the
 * emissions and the lines in, the line times and the confidence out.
 *
 * Long songs (not in the recommendation): its fallback for a song whose back-pointer table is past the memory budget
 * ("use the S2c times, keep the gate") cannot keep the gate, which is S4's agreement with S2c. So S4 is made for every
 * song: past [Ctc.Budget.tableCells] its table is made a block of frames at a time, the same path ([Ctc.forcedAlign]).
 * [progress] hears the whole-song alignment as it goes and may throw to stop it (a stopped run).
 * Past [Ctc.Budget.maxCells] (the CPU cap) the song is rejected ([RejectReason.TOO_LONG]).
 */
object PlainLyricsTiming {
    val RECOMMENDED: TimingParams = TimingParams(
        source = "S4 gated by S2c (recommendation 2026-11-03)",
        offsetSec = 0.0,
        contextLines = 1,
        agreementWindowSec = 0.5,
        minAgreement = 0.80,
        minMonotonic = 0.90,
        minStepSec = 0.2,
        transcribeOffsetSec = 0.44,
    )

    /** The numbers in use. */
    val PARAMS: TimingParams = RECOMMENDED

    /**
     * [lines] (from [LyricsText.lines]) timed on a vocal stem of [durationSec] with the Whisper [segments] heard in it (in
     * song time). [emissions] gives wav2vec2's emissions of the whole stem ([Ctc.wholeSong]); it is not called when no
     * line matches what Whisper heard (the song is rejected first).
     */
    fun time(
        segments: List<Segment>,
        durationSec: Double,
        lines: List<String>,
        params: TimingParams = PARAMS,
        budget: Ctc.Budget = Ctc.Budget(),
        progress: Progress = Progress { _, _ -> },
        emissions: () -> Emissions,
    ): TimedLines {
        require(lines.isNotEmpty()) { "no lines to time" }
        // The bench's song_segments: only segments with a letter or digit place lyrics.
        val heard = segments.filter { s -> s.text.codePoints().anyMatch(LyricsText::isAlnum) }
        val s1 = SegmentAlignment.lineStarts(lines, heard, durationSec)
        if (s1.anchored.none { it }) return TimedLines(s1.starts, 0.0, RejectReason.NO_WORDS)
        val whole = emissions()
        val target = Ctc.encode(lines)
        val trellis = Trellis(whole.frames, 2 * target.tokens.size + 1)
        fun rejected(reason: RejectReason) = TimedLines(s1.starts, 0.0, reason, trellis)
        if (target.tokens.isEmpty()) return rejected(RejectReason.NO_ALIGNMENT)
        if (trellis.cells > budget.maxCells) return rejected(RejectReason.TOO_LONG)
        // A song too short for its text has no path.
        val global = Ctc.align(whole, target, budget, progress) ?: return rejected(RejectReason.NO_ALIGNMENT)
        val s4 = filled(global).map { it + params.offsetSec }
        val s2c = Ctc.placedOn(whole, lines, Ctc.windows(s1.starts, durationSec), params.contextLines)
            .mapIndexed { k, t -> t ?: s1.starts[k] }
        return gate(s4, s2c, params).copy(trellis = trellis)
    }

    /** S4's [times] judged by their agreement with [s2c], as the bench's report does (`agreement`, `song_conf`, `accepted`). */
    fun gate(times: List<Double>, s2c: List<Double>, params: TimingParams = PARAMS): TimedLines {
        require(times.size == s2c.size && times.isNotEmpty()) { "${times.size} S4 times, ${s2c.size} S2c times" }
        val agreement = times.zip(s2c).count { (a, b) -> abs(a - b) <= params.agreementWindowSec }.toDouble() / times.size
        val steps = times.zipWithNext().count { (a, b) -> b - a >= params.minStepSec }
        val monotonic = if (times.size > 1) steps.toDouble() / (times.size - 1) else 1.0
        val rejected = when {
            agreement < params.minAgreement - EPSILON -> RejectReason.LOW_AGREEMENT
            monotonic < params.minMonotonic -> RejectReason.NOT_MONOTONIC
            else -> null
        }
        return TimedLines(times, agreement, rejected)
    }

    /**
     * A line the aligner gave no time (no letter in it) takes the next placed line's start, or the previous one's when
     * none follows (`line_without_letters`). At least one line is placed.
     */
    fun filled(starts: List<Double?>): List<Double> = starts.mapIndexed { i, t ->
        t ?: starts.subList(i + 1, starts.size).firstOrNull { it != null } ?: checkNotNull(starts.subList(0, i).lastOrNull { it != null })
    }

    /** The report accepts at `agreement >= threshold - 1e-9`. */
    private const val EPSILON = 1e-9
}

/**
 * S1 of the timing re-validation (lyrify-bench scripts/npu_timing.py `s1_starts`), ported line by line: a monotonic
 * alignment of lyric lines to Whisper segments by text. Beads are p lines on 1 segment (p up to [MAX_LINES_PER_SEGMENT])
 * or 1 line on q segments (q up to [MAX_SEGMENTS_PER_LINE]), scored by the similarity of their normalised texts
 * ([LyricsText.normalize]; lines joined by a space): `(|x| + |y|) / 2 * (sim - TAU) / (1 - TAU)`, where sim is
 * rapidfuzz's `fuzz.ratio` / 100, the Indel similarity `1 - (|x| + |y| - 2 LCS) / (|x| + |y|)` over code points. Skipping
 * a line or a segment scores 0. The best path's first line of a bead starts at its first segment's start; the other
 * lines of a 1-segment bead are placed by their share of the bead's characters (each line's length plus 1) across the
 * segment. Lines left out are interpolated by lyric word position (`fill`, run_match's rule).
 */
object SegmentAlignment {
    const val TAU: Double = 0.5
    const val MAX_LINES_PER_SEGMENT: Int = 16
    const val MAX_SEGMENTS_PER_LINE: Int = 4

    /** Lines before the first or after the last placed line are spread at this many seconds a word (`run_match.py`). */
    const val SEC_PER_WORD: Double = 0.45

    /** Each line's start, and whether it was anchored on a segment (the others are interpolated). */
    class Starts(val starts: List<Double>, val anchored: List<Boolean>)

    /** A transition into a cell: from (i, j), with p lines and q segments; p = 0 is a skip. */
    private class Step(val i: Int, val j: Int, val p: Int, val q: Int)

    fun lineStarts(lines: List<String>, segments: List<Segment>, durationSec: Double): Starts {
        val l = lines.map { LyricsText.normalize(it).codePoints().toArray() }
        val s = segments.map { LyricsText.normalize(it.text).codePoints().toArray() }
        val n = l.size
        val m = s.size
        val width = m + 1
        val best = DoubleArray((n + 1) * width) { Double.NEGATIVE_INFINITY }
        val back = arrayOfNulls<Step>((n + 1) * width)
        best[0] = 0.0
        fun relax(i2: Int, j2: Int, v: Double, how: Step) {
            if (v > best[i2 * width + j2]) {
                best[i2 * width + j2] = v
                back[i2 * width + j2] = how
            }
        }
        for (i in 0..n) {
            for (j in 0..m) {
                val v = best[i * width + j]
                if (v == Double.NEGATIVE_INFINITY) continue
                if (i < n) relax(i + 1, j, v, Step(i, j, 0, 0))
                if (j < m) {
                    relax(i, j + 1, v, Step(i, j, 0, 0))
                    // p lines on segment j: the pattern is the segment, the text the lines joined, read prefix by prefix.
                    val lineGains = prefixGains(s[j], l, i, min(MAX_LINES_PER_SEGMENT, n - i))
                    for (p in 1..lineGains.size) relax(i + p, j + 1, v + lineGains[p - 1], Step(i, j, p, 1))
                    if (i < n) {
                        // Line i on q >= 2 segments: the pattern is the line, the text the segments joined.
                        val segmentGains = prefixGains(l[i], s, j, min(MAX_SEGMENTS_PER_LINE, m - j))
                        for (q in 2..segmentGains.size) relax(i + 1, j + q, v + segmentGains[q - 1], Step(i, j, 1, q))
                    }
                }
            }
        }
        val starts = arrayOfNulls<Double>(n)
        var i = n
        var j = m
        while (i != 0 || j != 0) {
            val step = checkNotNull(back[i * width + j]) { "no path to ($i, $j)" }
            if (step.p > 0) {
                val segment = segments[step.j]
                val end = segment.end ?: segment.start
                val chars = (step.i until step.i + step.p).map { l[it].size + 1 }
                val total = chars.sum()
                var before = 0
                for (k in 0 until step.p) {
                    val share = if (step.q == 1) before.toDouble() / total else 0.0
                    starts[step.i + k] = segment.start + share * (end - segment.start)
                    before += chars[k]
                }
            }
            i = step.i
            j = step.j
        }
        val anchored = starts.map { it != null }
        val words = lines.map { LyricsText.tokens(it).size }
        val position = words.runningFold(0) { a, b -> a + b }
        return Starts(fill(starts.toList(), position, durationSec), anchored)
    }

    /** run_match's interpolation of the lines without a start, by lyric word position; all 0 when none has one. */
    private fun fill(starts: List<Double?>, position: List<Int>, durationSec: Double): List<Double> {
        val known = starts.indices.filter { starts[it] != null }
        if (known.isEmpty()) return List(starts.size) { 0.0 }
        return starts.indices.map { i ->
            starts[i] ?: run {
                val prev = known.lastOrNull { it < i }
                val next = known.firstOrNull { it > i }
                val prevStart = prev?.let(starts::get)
                val nextStart = next?.let(starts::get)
                when {
                    prev != null && next != null && prevStart != null && nextStart != null && position[next] > position[prev] ->
                        prevStart + (position[i] - position[prev]).toDouble() / (position[next] - position[prev]) * (nextStart - prevStart)
                    prev != null && prevStart != null -> min(durationSec, prevStart + (position[i] - position[prev]) * SEC_PER_WORD)
                    else -> maxOf(0.0, checkNotNull(nextStart) - (position[checkNotNull(next)] - position[i]) * SEC_PER_WORD)
                }
            }
        }
    }

    /**
     * The gains of [pattern] against [texts] from [from] joined by spaces, for the first 1 to [count] of them: one pass of
     * the bit-parallel LCS ([Lcs]) over the joined text, read at the end of each text.
     */
    private fun prefixGains(pattern: IntArray, texts: List<IntArray>, from: Int, count: Int): DoubleArray {
        val lcs = Lcs(pattern)
        var length = 0
        return DoubleArray(count) { k ->
            val text = texts[from + k]
            if (k > 0) {
                lcs.feed(SPACE)
                length++
            }
            for (c in text) lcs.feed(c)
            length += text.size
            gain(pattern.size, length, lcs.length())
        }
    }

    /** `(|x| + |y|) / 2 * (fuzz.ratio(x, y) / 100 - TAU) / (1 - TAU)`, with ratio computed as rapidfuzz does. */
    fun gain(x: Int, y: Int, lcs: Int): Double {
        val total = x + y
        val similarity = if (total == 0) 1.0 else 1.0 - (total - 2 * lcs).toDouble() / total
        return total / 2.0 * (similarity * 100.0 / 100.0 - TAU) / (1 - TAU)
    }

    /** rapidfuzz `fuzz.ratio(a, b) / 100` on code points, for tests. */
    fun similarity(a: String, b: String): Double {
        val x = a.codePoints().toArray()
        val y = b.codePoints().toArray()
        val lcs = Lcs(x).also { l -> y.forEach(l::feed) }.length()
        val total = x.size + y.size
        return if (total == 0) 1.0 else 1.0 - (total - 2 * lcs).toDouble() / total
    }

    private const val SPACE: Int = ' '.code
}

/**
 * Length of the longest common subsequence of a fixed [pattern] and a text fed one code point at a time (Hyyrö's
 * bit-parallel algorithm, as rapidfuzz computes it): bit k of V is 0 where pattern position k is matched. For each text
 * symbol c with match mask M[c]: U = V and M[c], V = (V + U) or (V - U). The LCS so far is the number of 0 bits.
 */
class Lcs(private val pattern: IntArray) {
    private val words = (pattern.size + 63) / 64
    private val masks = HashMap<Int, LongArray>()
    private val v = LongArray(words) { -1L }
    private val u = LongArray(words)

    init {
        pattern.forEachIndexed { k, c ->
            val mask = masks.getOrPut(c) { LongArray(words) }
            mask[k / 64] = mask[k / 64] or (1L shl (k % 64))
        }
    }

    fun feed(c: Int) {
        val m = masks[c] ?: return // no match: U = 0, V stays
        var carry = 0L
        for (w in 0 until words) {
            u[w] = v[w] and m[w]
            // V + U with carry across words, then (V + U) or (V - U); V - U = V and not U since U is a subset of V.
            val sum = v[w] + u[w] + carry
            carry = if (java.lang.Long.compareUnsigned(sum, v[w]) < 0 || (carry != 0L && sum == v[w])) 1L else 0L
            v[w] = sum or (v[w] and u[w].inv())
        }
    }

    /** Zero bits of V within the pattern's length. */
    fun length(): Int {
        var ones = 0
        for (w in 0 until words) {
            val bits = if (w == words - 1 && pattern.size % 64 != 0) v[w] and ((1L shl (pattern.size % 64)) - 1) else v[w]
            ones += java.lang.Long.bitCount(bits)
        }
        return pattern.size - ones
    }
}
