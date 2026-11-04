package app.lyrify.ai

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** wav2vec2 CTC log-probabilities of one window, row-major: [frames] x [classes]. */
class Emissions(val frames: Int, val classes: Int, private val logProbs: DoubleArray) {
    init {
        require(logProbs.size == frames * classes) { "${logProbs.size} values for $frames x $classes" }
    }

    operator fun get(frame: Int, label: Int): Double = logProbs[frame * classes + label]

    /** Frames [from] until [to] (numpy's `lp[from:to]`: both clamped to the frames, empty when [to] is not past [from]). */
    fun slice(from: Int, to: Int): Emissions {
        val a = from.coerceIn(0, frames)
        val b = to.coerceIn(a, frames)
        return Emissions(b - a, classes, logProbs.copyOfRange(a * classes, b * classes))
    }

    companion object {
        /** [parts] one after the other (numpy's `concatenate`); they must share the class count. */
        fun concat(parts: List<Emissions>): Emissions {
            val classes = parts.firstOrNull()?.classes ?: Ctc.CLASSES
            require(parts.all { it.classes == classes }) { "class counts differ: ${parts.map { it.classes }}" }
            val out = DoubleArray(parts.sumOf { it.frames } * classes)
            var at = 0
            for (part in parts) {
                part.logProbs.copyInto(out, at)
                at += part.logProbs.size
            }
            return Emissions(out.size / classes, classes, out)
        }

        /** Log-softmax over each frame of raw [logits] (frames x classes, float32), computed in double as the bench does. */
        fun fromLogits(logits: FloatArray, classes: Int): Emissions {
            val frames = logits.size / classes
            val out = DoubleArray(frames * classes)
            for (t in 0 until frames) {
                val row = t * classes
                var top = Double.NEGATIVE_INFINITY
                for (c in 0 until classes) top = max(top, logits[row + c].toDouble())
                var sum = 0.0
                for (c in 0 until classes) sum += exp(logits[row + c] - top)
                val lse = ln(sum)
                for (c in 0 until classes) out[row + c] = logits[row + c] - top - lse
            }
            return Emissions(frames, classes, out)
        }

        fun of(rows: List<DoubleArray>): Emissions {
            val classes = rows.firstOrNull()?.size ?: 0
            return Emissions(rows.size, classes, DoubleArray(rows.size * classes) { rows[it / classes][it % classes] })
        }
    }
}

/** One aligner window: lyric lines [firstLine] until [endLine], placed inside `[startSec, endSec)` of the stem. */
data class CtcWindow(val firstLine: Int, val endLine: Int, val startSec: Double, val endSec: Double) {
    val durationSec: Double get() = endSec - startSec
}

/** One emitted token of a CTC path: frames [start] until [end] carry [token]. */
data class TokenSpan(val token: Int, val start: Int, val end: Int)

/**
 * CTC forced alignment of known lyric lines to wav2vec2-base-960h character emissions: a port of lyrify-bench
 * `ctc_align.py` (the same algorithm as torchaudio's `forced_align` CPU kernel, tie-breaking included) and of the
 * windowing in `phone_ctc.py` / `npu_timing.py` (6 lines per window, 5 s margins around the coarse line times).
 *
 * Text model (vocab.json at rev 22aad52d435e): uppercase A-Z, the apostrophe, and "|" as the word separator. Lines
 * are NFKD-folded and uppercased; curly apostrophes become "'", hyphens split words, everything else is dropped
 * (digits, punctuation, accents). A line with no usable letters gets no time; it keeps its coarse time.
 */
object Ctc {
    const val BLANK: Int = 0
    const val SEPARATOR: Int = 4
    const val CLASSES: Int = 32

    /** 20 ms per frame: the product of the conv strides (5 x 2^6) at 16 kHz. */
    const val FRAME_SEC: Double = 320.0 / 16000

    const val LINES_PER_WINDOW: Int = 6
    const val MARGIN_SEC: Double = 5.0
    const val MAX_WINDOW_SEC: Double = 60.0

    /** A window shorter than this places none of its lines. */
    const val MIN_WINDOW_SEC: Double = 1.0

    /** The whole song's emissions ([wholeSong]): wav2vec2 on 30 s of the stem every 25 s. */
    const val SONG_WINDOW_SEC: Double = 30.0
    const val SONG_HOP_SEC: Double = 25.0

    /**
     * The back-pointers [forcedAlign] keeps at once, one byte per frame and state: 64 MB, so that a 20-minute song's
     * timing (its 16 kHz stem, its emissions and this table) stays far below the heap ([AiMemory]). The largest song of
     * the bench's set A needs 17357 frames x 3245 states = 56 M, one table as the bench makes it. A longer song's table
     * is made a block of frames at a time ([blockFrames]): the same path, for one more forward pass.
     */
    const val MAX_TABLE_CELLS: Long = 64_000_000

    /** [forcedAlign] tells its progress (which may stop it) every this many frames. */
    const val PROGRESS_FRAMES: Int = 256

    /**
     * The most frames x states one alignment may cost (about 2 G additions and comparisons with the second pass): a
     * 30-minute track with 3,000 words of lyrics. A bigger one is not aligned.
     */
    const val MAX_CELLS: Long = 1_000_000_000

    /** What one alignment may cost: [tableCells] back-pointers kept at once, at most [maxCells] frames x states in all. */
    data class Budget(val tableCells: Long = MAX_TABLE_CELLS, val maxCells: Long = MAX_CELLS)

    private const val STAY: Byte = 0
    private const val STEP: Byte = 1
    private const val SKIP: Byte = 2

    private val LETTERS: Map<Char, Int> = mapOf(
        'E' to 5, 'T' to 6, 'A' to 7, 'O' to 8, 'N' to 9, 'I' to 10, 'H' to 11, 'S' to 12, 'R' to 13, 'D' to 14,
        'L' to 15, 'U' to 16, 'M' to 17, 'W' to 18, 'C' to 19, 'F' to 20, 'G' to 21, 'Y' to 22, 'P' to 23, 'B' to 24,
        'V' to 25, 'K' to 26, '\'' to 27, 'X' to 28, 'J' to 29, 'Q' to 30, 'Z' to 31,
    )

    /** The target of a group of lines: token ids, and the index of each line's first token (null: no usable letter). */
    data class Target(val tokens: IntArray, val firstTokenOfLine: List<Int?>) {
        override fun equals(other: Any?): Boolean =
            other is Target && tokens.contentEquals(other.tokens) && firstTokenOfLine == other.firstTokenOfLine

        override fun hashCode(): Int = 31 * tokens.contentHashCode() + firstTokenOfLine.hashCode()
    }

    /** Uppercase words made only of vocabulary letters and apostrophes; empty words are dropped. */
    fun wordsOf(line: String): List<String> {
        val folded = Normalizer.normalize(line, Normalizer.Form.NFKD).replace('’', '\'').replace('‘', '\'').uppercase()
        return LyricsText.pySplit(folded.replace('-', ' '))
            .map { raw -> raw.filter { it in LETTERS }.trim('\'') }
            .filter { it.isNotEmpty() }
    }

    /** Token ids for all [lines] (words and lines joined by the separator) and each line's first token index. */
    fun encode(lines: List<String>): Target {
        val tokens = ArrayList<Int>()
        val first = lines.map { line ->
            val words = wordsOf(line)
            if (words.isEmpty()) return@map null
            if (tokens.isNotEmpty()) tokens += SEPARATOR
            val start = tokens.size
            words.forEachIndexed { k, word ->
                if (k > 0) tokens += SEPARATOR
                word.forEach { tokens += LETTERS.getValue(it) }
            }
            start
        }
        return Target(tokens.toIntArray(), first)
    }

    /**
     * The audio a window gives wav2vec2: zero mean and unit variance over the window, as the Hugging Face feature
     * extractor (`do_normalize=True`, eps 1e-7) and `ctc_align.normalize_audio` do; computed in double.
     */
    fun normalizeAudio(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples.copyOf()
        val mean = samples.sumOf { it.toDouble() } / samples.size
        val variance = samples.sumOf { (it - mean) * (it - mean) } / samples.size
        val scale = 1.0 / sqrt(variance + 1e-7)
        return FloatArray(samples.size) { ((samples[it] - mean) * scale).toFloat() }
    }

    /**
     * The most likely CTC path that emits exactly [tokens]: the label of every frame (blank where none), or null when
     * the window has fewer frames than the target needs (L plus one per repeated pair) or no path exists.
     *
     * Trellis: S = 2L + 1 states, labels blank, t1, blank, t2, ..., tL, blank. Frame 0 may start in state 0 or 1.
     * For frame t >= 1 and state s, with a = the previous frame's scores: stay a[s], step a[s-1], skip a[s-2] (s odd,
     * s >= 3, label[s] != label[s-2]); SKIP if skip > step and skip > stay, else STEP if step > stay and step > skip,
     * else STAY; missing predecessors are -infinity. The path ends in state S-1 if its score is strictly higher than
     * state S-2's, else in S-2, and is read back through the back-pointers.
     *
     * The back-pointers are kept for at most [Budget.tableCells] frames x states at once. A longer trellis is read back
     * a block of frames at a time ([blockFrames]): the forward pass keeps the scores before each block, and each block's
     * back-pointers are computed again from them, with the same additions, so the path is the same. Null as well past
     * [Budget.maxCells]. [progress] hears every [PROGRESS_FRAMES] frames of each pass and may throw to stop it.
     */
    fun forcedAlign(emissions: Emissions, tokens: IntArray, budget: Budget = Budget(), progress: Progress = Progress { _, _ -> }): IntArray? {
        require(tokens.isNotEmpty()) { "empty target" }
        require(BLANK !in tokens) { "targets must not contain the blank" }
        val frames = emissions.frames
        val repeats = (1 until tokens.size).count { tokens[it] == tokens[it - 1] }
        if (frames < tokens.size + repeats) return null

        val states = 2 * tokens.size + 1
        if (frames.toLong() * states > budget.maxCells) return null
        val labels = IntArray(states) { s -> if (s % 2 == 1) tokens[s / 2] else BLANK }
        val canSkip = BooleanArray(states) { s -> s >= 3 && s % 2 == 1 && labels[s] != labels[s - 2] }
        val block = blockFrames(frames, states, budget.tableCells)
        val back = ByteArray(block * states)
        // The scores of the frame before each block (none for the first: it starts from frame 0's).
        val before = arrayOfNulls<DoubleArray>((frames + block - 1) / block)

        fun first(): DoubleArray = DoubleArray(states) { Double.NEGATIVE_INFINITY }.also {
            it[0] = emissions[0, labels[0]]
            it[1] = emissions[0, labels[1]]
        }

        /** Frames [from] until [to] from the scores [start] of frame from - 1: their back-pointers, the last scores. */
        fun forward(from: Int, to: Int, start: DoubleArray, keep: Boolean): DoubleArray {
            var alpha = start
            var next = DoubleArray(states)
            for (t in from until to) {
                if (t % PROGRESS_FRAMES == 0) progress.step(t, frames)
                if (keep && t % block == 0) before[t / block] = alpha.copyOf()
                val row = (t % block) * states
                for (s in 0 until states) {
                    val stay = alpha[s]
                    val step = if (s >= 1) alpha[s - 1] else Double.NEGATIVE_INFINITY
                    val skip = if (canSkip[s]) alpha[s - 2] else Double.NEGATIVE_INFINITY
                    val choice = when {
                        skip > step && skip > stay -> SKIP
                        step > stay && step > skip -> STEP
                        else -> STAY
                    }
                    back[row + s] = choice
                    next[s] = alpha[s - choice] + emissions[t, labels[s]]
                }
                val done = alpha
                alpha = next
                next = done
            }
            return alpha
        }

        val last = forward(1, frames, first(), keep = true)
        var state = if (last[states - 1] > last[states - 2]) states - 1 else states - 2
        if (!last[state].isFinite()) return null
        val path = IntArray(frames)
        for (b in before.indices.reversed()) {
            val from = b * block
            val to = min(frames, from + block)
            // The forward pass ended in the last block, so its back-pointers are still there.
            if (to < frames) forward(max(1, from), to, before[b] ?: first(), keep = false)
            for (t in to - 1 downTo from) {
                path[t] = labels[state]
                if (t > 0) state -= back[(t % block) * states + state]
            }
        }
        return path
    }

    /** Frames per block of [forcedAlign]'s back-pointers: all [frames] (one table) when they fit in [tableCells]. */
    fun blockFrames(frames: Int, states: Int, tableCells: Long): Int = (tableCells / states).coerceIn(1L, frames.toLong()).toInt()

    /** One span per emitted token: runs of the same non-blank label (a valid path has a blank between equal tokens). */
    fun mergeTokens(path: IntArray): List<TokenSpan> {
        val spans = ArrayList<TokenSpan>()
        var t = 0
        while (t < path.size) {
            if (path[t] == BLANK) {
                t++
                continue
            }
            var u = t
            while (u < path.size && path[u] == path[t]) u++
            spans += TokenSpan(path[t], t, u)
            t = u
        }
        return spans
    }

    /**
     * Start of every line in seconds from the window start, null for lines without usable letters; null as a whole
     * when the window is too short for the text (the bench then keeps the coarse times of the whole group) or its trellis
     * is past [Budget.maxCells].
     */
    fun alignLines(emissions: Emissions, lines: List<String>, budget: Budget = Budget()): List<Double?>? = align(emissions, encode(lines), budget)

    /** [alignLines] for an encoded [target]; [progress] as [forcedAlign]'s. */
    fun align(emissions: Emissions, target: Target, budget: Budget = Budget(), progress: Progress = Progress { _, _ -> }): List<Double?>? {
        if (target.tokens.isEmpty()) return target.firstTokenOfLine.map { null }
        val path = forcedAlign(emissions, target.tokens, budget, progress) ?: return null
        val spans = mergeTokens(path)
        if (spans.size != target.tokens.size) return null
        return target.firstTokenOfLine.map { k -> k?.let { spans[it].start * FRAME_SEC } }
    }

    /**
     * wav2vec2's emissions of the whole [stem] (16 kHz mono), as the timing recommendation makes them (`npu_timing.py`
     * `global_lp`): window k is `[25 k, 25 k + 30)` s of the stem, the last one cut at its end, and [emissions] runs on
     * each ([samples]: normalised per window by the model's caller). Of each window the frames `[125, 1375)` are kept,
     * the first window's from frame 0 and the last one's to its end, so frame f of the result is f x [FRAME_SEC] of the
     * song. [progress] hears each window done.
     */
    fun wholeSong(stem: FloatArray, progress: Progress = Progress { _, _ -> }, emissions: (FloatArray) -> Emissions): Emissions {
        val windows = songWindows(stem.size.toDouble() / Rates.SPEECH)
        val perHop = Math.rint(SONG_HOP_SEC / FRAME_SEC).toInt()
        val trim = Math.rint((SONG_WINDOW_SEC - SONG_HOP_SEC) / 2 / FRAME_SEC).toInt()
        return Emissions.concat(
            windows.mapIndexed { k, (w0, w1) ->
                val window = emissions(samples(stem, w0, w1))
                progress.step(k + 1, windows.size)
                window.slice(if (k == 0) 0 else trim, if (k == windows.lastIndex) window.frames else min(window.frames, trim + perHop))
            },
        )
    }

    /** [wholeSong]'s windows of a stem of [durationSec], in seconds: `[25 k, 25 k + 30)`, until one reaches the end. */
    fun songWindows(durationSec: Double): List<Pair<Double, Double>> = buildList {
        var k = 0
        while (true) {
            val w0 = k * SONG_HOP_SEC
            val w1 = min(durationSec, w0 + SONG_WINDOW_SEC)
            add(w0 to w1)
            if (w1 >= durationSec - 1e-6) return@buildList
            k++
        }
    }

    /** `wav[int(round(w0 * SR)): int(round(w1 * SR))]`, as the timing re-validation (`npu_timing.py`) slices the 16 kHz stem. */
    fun samples(stem: FloatArray, startSec: Double, endSec: Double): FloatArray {
        val from = min(Math.rint(startSec * Rates.SPEECH).toInt(), stem.size)
        return stem.copyOfRange(from, min(max(Math.rint(endSec * Rates.SPEECH).toInt(), from), stem.size))
    }

    /**
     * The windows around coarse line times ([coarse]): lines in groups of [LINES_PER_WINDOW]; each window runs from
     * (coarse start of its first line - margin) to (coarse start of the next group's first line, or the end, + margin),
     * within the song and at most [MAX_WINDOW_SEC] long. `phone_ctc.py` `windows`; a window shorter than
     * [MIN_WINDOW_SEC] is not aligned.
     */
    fun windows(coarse: List<Double>, durationSec: Double): List<CtcWindow> =
        coarse.indices.step(LINES_PER_WINDOW).map { a ->
            val b = min(a + LINES_PER_WINDOW, coarse.size)
            val w0 = max(0.0, coarse[a] - MARGIN_SEC)
            val w1 = minOf(durationSec, (if (b < coarse.size) coarse[b] else durationSec) + MARGIN_SEC, w0 + MAX_WINDOW_SEC)
            CtcWindow(a, b, w0, w1)
        }

    /**
     * Where the aligner placed each line in its window, in seconds of the song; null for a line it did not place (no
     * usable letters, or a window not run or too short for its text), as `npu_timing.py` `windowed` gives S2. With
     * [context] > 0, that many lines before and after each window's group are aligned in the same window too, so the
     * singing in its margins is explained by its own text; their times are discarded. [emissions] holds one entry per
     * window of [windows], null for windows not run.
     */
    fun placed(lines: List<String>, windows: List<CtcWindow>, emissions: List<Emissions?>, context: Int = 0): List<Double?> {
        require(windows.size == emissions.size) { "${windows.size} windows, ${emissions.size} emissions" }
        val placed: Map<Int, Double> = windows.zip(emissions).flatMap { (window, em) ->
            val lo = max(0, window.firstLine - context)
            val hi = min(lines.size, window.endLine + context)
            val starts = em?.let { alignLines(it, lines.subList(lo, hi)) }.orEmpty()
            starts.mapIndexedNotNull { k, start ->
                start?.takeIf { lo + k in window.firstLine until window.endLine }?.let { lo + k to it + window.startSec }
            }
        }.toMap()
        return lines.indices.map { placed[it] }
    }

    /**
     * [placed] on slices of the whole song's emissions ([wholeSong]) instead of a wav2vec2 run per window
     * (`npu_timing.py` `windowed_on`): a window of at least [MIN_WINDOW_SEC] gets the frames `round(start / 0.02)` until
     * `round(end / 0.02)` (within the song), and its lines' times count from its first frame.
     */
    fun placedOn(whole: Emissions, lines: List<String>, windows: List<CtcWindow>, context: Int): List<Double?> {
        val sliced = windows.map { w ->
            if (w.durationSec < MIN_WINDOW_SEC) return@map w to null
            val f0 = Math.rint(w.startSec / FRAME_SEC).toInt()
            val f1 = min(whole.frames, Math.rint(w.endSec / FRAME_SEC).toInt())
            w.copy(startSec = f0 * FRAME_SEC) to whole.slice(f0, f1)
        }
        return placed(lines, sliced.map { it.first }, sliced.map { it.second }, context)
    }
}
