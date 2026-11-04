package app.lyrify.ai

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The gate's verdict on a song: the share of its 1 s frames with vocals, and the longest run of such frames. */
data class GateResult(
    val share: Double,
    val longestRunSec: Int,
    val activeFrames: Int,
    val validFrames: Int,
    val frames: Int,
)

/**
 * The V2 vocal gate's 1 s frames (lyrify-bench `evaluate.py` `v2_features`, frames from `phone_demucs.py`
 * `frame_rms`): RMS of the mono vocals stem and of the mono mix, both at 44.1 kHz, rounded to 6 decimals. A frame is
 * valid when the mix RMS is above [VocalGate.MIN_MIX_RMS], and active (vocal) when it is valid and the vocals RMS
 * exceeds [VocalGate.RATIO] times the mix RMS.
 */
class GateFrames(val vocalRms: DoubleArray, val mixRms: DoubleArray) {
    init {
        require(vocalRms.size == mixRms.size) { "frame counts differ: ${vocalRms.size} vs ${mixRms.size}" }
    }

    val size: Int get() = mixRms.size

    fun isValid(frame: Int): Boolean = mixRms[frame] > VocalGate.MIN_MIX_RMS

    fun isActive(frame: Int): Boolean = isValid(frame) && vocalRms[frame] > VocalGate.RATIO * mixRms[frame]

    /** Active over valid frames; any inactive frame (valid or not) ends a run. */
    fun gate(): GateResult {
        var valid = 0
        var active = 0
        var run = 0
        var longest = 0
        for (i in 0 until size) {
            if (isValid(i)) valid++
            if (isActive(i)) active++
            run = if (isActive(i)) run + 1 else 0
            longest = max(longest, run)
        }
        return GateResult(active.toDouble() / max(1, valid), longest, active, valid, size)
    }

    /**
     * Share of active frames under `[startSec, endSec)`, `phone_step5_score.py` `vocal_share`: frames `int(start)`
     * to `max(int(start) + 1, ceil(end))`, cut at the last frame; every frame counts, valid or not. 0 when none.
     */
    fun activeShare(startSec: Double, endSec: Double): Double {
        val from = startSec.toInt()
        val to = min(max(from + 1, ceil(endSec).toInt()), size)
        if (from >= to) return 0.0
        return (from until to).count(::isActive).toDouble() / (to - from)
    }

    companion object {
        /** The gate's frames of a song: [vocals] and [mix] are mono at 44.1 kHz. */
        fun of(vocals: FloatArray, mix: FloatArray): GateFrames =
            GateFrames(frameRms(vocals, Rates.MIX, rounded = true), frameRms(mix, Rates.MIX, rounded = true))
    }
}

/**
 * Whether a song is sung. The V2 frame rule ([RATIO], [MIN_MIX_RMS]) only builds the vocal chunks: with
 * UVR-MDX-NET-Voc_FT, lead guitar and violin of instrumentals leak into the vocal stem, so its frame share no longer
 * tells songs apart (set B: AUC 0.960, leave-one-out 75%, against 0.990 and 98% with HTDemucs). A song with no vocal frame
 * at all has nothing to transcribe and is an instrumental; otherwise Whisper decides: the words it writes over the
 * chunks per minute of song ([wordsPerMinute]) are vocal above [VOCAL_ABOVE_WORDS_PER_MINUTE] (set B: AUC 1.000,
 * leave-one-out 100%). Source: lyrify-bench results/summary.md "Separation test (2026-11-03)", gate table row "Whisper on
 * the Voc_FT V2 chunks: words per minute", computed by scripts/sep_eval.py `whisper_gate` (C_ct2_vocft, the NPU-loop
 * replica on the Voc_FT V2 chunks) and `evaluate.best_threshold`.
 */
object VocalGate {
    const val RATIO: Double = 0.2
    const val MIN_MIX_RMS: Double = 1e-3

    /**
     * The bench's best threshold on set B (`best_threshold`: vocal when `score > t`): 9.8003 in results/separation_test.json
     * (`gate.whisper_on_vocft.words_per_min`), the midpoint between the busiest instrumental (7.2 words per minute) and
     * the quietest vocal song (12.4). The bench counted every word of the transcript; here junk and fillers over non-vocal
     * audio are left out first ([SegmentFilters]), so "Thank you." hallucinations over an instrumental do not count. That
     * only lowers instrumentals' rates.
     */
    const val VOCAL_ABOVE_WORDS_PER_MINUTE: Double = 9.8

    /**
     * Words Whisper heard per minute of song: the words ([LyricsText.tokens], the bench's `normalize_text` split) of the
     * [segments] [SegmentFilters] keeps, over the song's [durationSec] (`sep_eval.py`: words / (audio_sec / 60)).
     */
    fun wordsPerMinute(segments: List<Segment>, frames: GateFrames, durationSec: Double): Double {
        if (durationSec <= 0) return 0.0
        val words = segments.filter { SegmentFilters.keep(it, frames) }.sumOf { LyricsText.tokens(it.text).size }
        return words / (durationSec / 60)
    }

    fun isVocal(wordsPerMinute: Double): Boolean = wordsPerMinute > VOCAL_ABOVE_WORDS_PER_MINUTE
}

/**
 * What a run keeps of the separation, made while the vocals come out of it ([MdxSeparator.separate]): the gate's 1 s
 * frames of the mix and of the vocals ([GateFrames]), and the 16 kHz mono vocal stem Whisper and wav2vec2 hear, made as
 * the bench makes them from whole arrays (each frame's channel mean, julius' resample_frac, the PCM 16 file round trip)
 * and to the same bits. Neither the 44.1 kHz mix nor the vocals are held: the mix is counted as it is read
 * ([listening]), the vocals as they arrive ([accept]), and the stem is kept at 16 bits until [build].
 */
class VocalStem : VocalSink {
    private val mixRms = FrameRms(Rates.MIX)
    private val vocalRms = FrameRms(Rates.MIX)
    private val toSpeech = TO_SPEECH.Stream()
    private var mono = FloatArray(0)
    private val speech = Floats()
    private val stem = Pcm16()

    /** [mix] as it is read, each frame counted for the gate. */
    fun listening(mix: MixSource): MixSource = object : MixSource {
        override val expectedFrames: Int get() = mix.expectedFrames

        override fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int =
            mix.read(left, right, offset, count).also { n -> for (i in offset until offset + n) mixRms.add(channelMean(left[i], right[i])) }
    }

    override fun accept(left: FloatArray, right: FloatArray, count: Int) {
        if (mono.size < count) mono = FloatArray(count)
        for (i in 0 until count) {
            val m = channelMean(left[i], right[i])
            vocalRms.add(m)
            mono[i] = m
        }
        toSpeech.push(mono, 0, count, speech)
        keep()
    }

    /** The gate's frames and the 16 kHz stem, once the separation has given every frame. */
    fun build(): Vocals {
        toSpeech.finish(speech)
        keep()
        return Vocals(GateFrames(vocalRms.frames(), mixRms.frames()), stem.floats())
    }

    private fun keep() {
        for (i in 0 until speech.size) stem.add(speech.data[i])
        speech.clear()
    }

    /** RMS of each whole frame of [frame] samples, rounded to 6 decimals, from samples given one at a time ([frameRms]). */
    private class FrameRms(private val frame: Int) {
        private val values = ArrayList<Double>()
        private var sum = 0.0
        private var inFrame = 0

        fun add(v: Float) {
            sum += v.toDouble() * v
            if (++inFrame == frame) {
                values += (sqrt(sum / frame) * 1e6).roundToLong() / 1e6
                sum = 0.0
                inFrame = 0
            }
        }

        fun frames(): DoubleArray = values.toDoubleArray()
    }

    /**
     * The stem as the bench's PCM 16 file holds it ([pcm16RoundTrip]: `floor(x * 32768)` of x clipped to [-1, 1],
     * saturated), in blocks, until [floats] reads it back as `s / 32768`.
     */
    private class Pcm16 {
        private val blocks = ArrayList<ShortArray>()
        private var size = 0

        fun add(v: Float) {
            if (size % BLOCK == 0) blocks += ShortArray(BLOCK)
            blocks[size / BLOCK][size % BLOCK] = floor(v.coerceIn(-1f, 1f) * 32768f).coerceIn(-32768f, 32767f).toInt().toShort()
            size++
        }

        fun floats(): FloatArray = FloatArray(size) { i -> blocks[i / BLOCK][i % BLOCK] / 32768f }

        private companion object {
            const val BLOCK = 1 shl 16
        }
    }

    private companion object {
        /** 44.1 kHz to 16 kHz as the bench's stem is made: julius' resample_frac. */
        val TO_SPEECH by lazy { ResampleFrac(Rates.MIX, Rates.SPEECH) }
    }
}

/** A span of the vocal stem, in seconds from the start of the song, that Whisper transcribes on its own. */
data class VocalChunk(val start: Double, val end: Double) {
    val durationSec: Double get() = end - start

    /** The chunk's samples, `stem[int(start * rate):int(end * rate)]` as the bench slices them. */
    fun slice(stem: FloatArray, sampleRate: Int = Rates.SPEECH): FloatArray {
        val from = min((start * sampleRate).toInt(), stem.size)
        return stem.copyOfRange(from, min(max((end * sampleRate).toInt(), from), stem.size))
    }
}

/**
 * Cuts the 16 kHz vocal stem into chunks of at most 30 s that hold singing: a straight port of lyrify-bench
 * `phone_wcpp_variants.py` `vocal_chunks` (step 5, variant (d), the one chosen for the phone).
 *
 *  1. A 1 s frame is vocal by the gate's rule ([GateFrames.isActive]).
 *  2. Runs of vocal frames are padded by [PAD_SEC] on both sides (within the stem); runs whose padded ends are at
 *     most [MERGE_GAP_SEC] apart are joined.
 *  3. A run longer than [MAX_CHUNK_SEC] is cut at the quietest 100 ms frame of the stem between 15 and 30 s after
 *     its start, repeatedly; once it is under 60 s the last cut keeps both pieces between [MIN_PIECE_SEC] and 30 s.
 *  4. Neighbouring pieces are packed together while the packed span stays within 30 s (Whisper's encoder always
 *     processes 30 s, so fewer chunks are cheaper). A packed chunk includes the gap between its pieces.
 */
object VocalChunks {
    const val PAD_SEC = 1.0
    const val MERGE_GAP_SEC = 2.0
    const val MAX_CHUNK_SEC = 30.0
    const val MIN_PIECE_SEC = 3.0

    /** Frames of the stem's RMS that the cuts look at: 100 ms. */
    const val QUIET_FRAME: Int = Rates.SPEECH / 10

    /** The chunks of [stem], mono at 16 kHz. */
    fun of(frames: GateFrames, stem: FloatArray): List<VocalChunk> =
        plan(frames, frameRms(stem, QUIET_FRAME, rounded = false), stem.size.toDouble() / Rates.SPEECH)

    /**
     * The chunks of a stem of [durationSec] whose 100 ms frames have the RMS [quietRms]. The RMS is numpy's float32
     * in the bench and double here, so a near tie between two quiet frames could pick the other one.
     */
    fun plan(frames: GateFrames, quietRms: DoubleArray, durationSec: Double): List<VocalChunk> {
        val pieces = runs(frames, durationSec).flatMap { cut(it, quietRms) }
        return pieces.fold(emptyList()) { packed, piece ->
            val last = packed.lastOrNull()
            if (last != null && piece.end - last.start <= MAX_CHUNK_SEC) packed.dropLast(1) + VocalChunk(last.start, piece.end) else packed + piece
        }
    }

    private fun runs(frames: GateFrames, durationSec: Double): List<VocalChunk> {
        val runs = ArrayList<VocalChunk>()
        var i = 0
        while (i < frames.size) {
            if (!frames.isActive(i)) {
                i++
                continue
            }
            var j = i
            while (j < frames.size && frames.isActive(j)) j++
            val a = max(0.0, i - PAD_SEC)
            val b = min(durationSec, j + PAD_SEC)
            val last = runs.lastOrNull()
            if (last != null && a - last.end <= MERGE_GAP_SEC) runs[runs.lastIndex] = VocalChunk(last.start, b) else runs += VocalChunk(a, b)
            i = j
        }
        return runs
    }

    private fun cut(run: VocalChunk, quietRms: DoubleArray): List<VocalChunk> {
        val pieces = ArrayList<VocalChunk>()
        var a = run.start
        val b = run.end
        while (b - a > MAX_CHUNK_SEC) {
            val (loSec, hiSec) = if (b - a > 2 * MAX_CHUNK_SEC) {
                a + MAX_CHUNK_SEC / 2 to a + MAX_CHUNK_SEC // leave more than one chunk's worth behind
            } else {
                max(b - MAX_CHUNK_SEC, a + MIN_PIECE_SEC) to min(a + MAX_CHUNK_SEC, b - MIN_PIECE_SEC)
            }
            val lo = ceil(loSec * 10).toInt()
            val hi = min((hiSec * 10).toInt(), quietRms.size)
            val cut = if (hi > lo) (lo + argmin(quietRms, lo, hi)) / 10.0 else (a + b) / 2
            pieces += VocalChunk(a, cut)
            a = cut
        }
        pieces += VocalChunk(a, b)
        return pieces
    }

    /** Offset from [from] of the first smallest value in [from, to): numpy's argmin on the slice. */
    private fun argmin(x: DoubleArray, from: Int, to: Int): Int {
        var best = from
        for (k in from + 1 until to) if (x[k] < x[best]) best = k
        return best - from
    }
}
