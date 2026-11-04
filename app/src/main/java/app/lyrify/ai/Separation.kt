package app.lyrify.ai

import java.nio.FloatBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/*
 * Vocal separation with UVR-MDX-NET-Voc_FT on the NPU, ported from lyrify-npu-spike (separation/Separation.kt): the
 * graph maps a spectrogram to the vocals' spectrogram, and the STFT and iSTFT run here on the CPU with the fast FFT.
 * HTDemucs is not ported: it cannot run on the NPU (QNN MEM_ALLOC; its CPU fallback dies of out-of-memory).
 */

/**
 * One model call over a fixed-size segment, whatever runs it (ONNX Runtime on the QNN EP on the phone, a fake in the
 * unit tests). The separator fills [input] in the model's layout, then [run] returns the output in the model's layout.
 */
interface SegmentModel {
    /** (1, 4, dim_f, dim_t) for MDX-Net. */
    val inputShape: LongArray

    /** The input, filled from position 0 before every [run]. */
    val input: FloatBuffer

    /** Runs the model on [input]. The returned buffer is valid until the next run. */
    fun run(): FloatBuffer
}

/** Where the separation puts the vocals: planar stereo at the mix's rate, in order, each sample once and final. */
fun interface VocalSink {
    fun accept(left: FloatArray, right: FloatArray, count: Int)
}

/** How a separation went: the mix's [frames], the [segments] (model runs) and the NaN/Inf model outputs zeroed. */
class SeparationStats(val frames: Int, val segments: Int, val nonFinite: Long)

/**
 * Pre- and post-processing of an MDX-Net vocal model. The fields follow python-audio-separator's MDXSeparator
 * (v0.48.0): model data `mdx_n_fft_scale_set` = [nFft], `mdx_dim_f_set` = [dimF], `2 ** mdx_dim_t_set` = [dimT], arch
 * config `hop_length`, `overlap`, and the common `normalization_threshold` ([normalizeMaxPeak]).
 */
data class MdxSpec(
    val name: String = "mdx",
    val sampleRate: Int = 44100,
    val nFft: Int,
    val hopLength: Int = 1024,
    val dimF: Int,
    val dimT: Int,
    /** Share of a chunk the next one overlaps; chunks are weighted by np.hanning(chunk) when it is not 0. */
    val overlap: Double = 0.25,
    /** `spek[:, :, :3, :] *= 0` before the model. */
    val zeroLowBins: Int = 3,
    /** `spec_utils.normalize(mix, max_peak)`: a mix whose peak is above this is scaled down to it; null = never. */
    val normalizeMaxPeak: Double? = 0.9,
    /** `source = demix(mix) * peak` (MDXSeparator.separate): the output times the original mix peak. */
    val multiplyOutputByInputPeak: Boolean = true,
    /** ONNX input and output names. */
    val inputName: String = "input",
    val outputName: String = "output",
    /** Values for symbolic dimensions (the batch of UVR models), so the QNN EP sees static shapes. */
    val freeDimensionOverrides: Map<String, Long> = emptyMap(),
) {
    val trim: Int get() = nFft / 2
    val chunkSize: Int get() = hopLength * (dimT - 1)
    val genSize: Int get() = chunkSize - 2 * trim
    val step: Int get() = ((1 - overlap) * chunkSize).toInt()

    fun check(): MdxSpec {
        require(nFft >= 4 && hopLength > 0 && dimT >= 2) { "n_fft, hop_length and dim_t are too small" }
        require(dimF in 1..(nFft / 2 + 1)) { "dim_f must be 1 to n_fft / 2 + 1" }
        require(genSize > 0) { "hop_length * (dim_t - 1) must exceed n_fft" }
        require(overlap >= 0 && overlap < 1 && step > 0) { "overlap must be in [0, 1)" }
        require(zeroLowBins in 0..dimF) { "zero_low_bins must be 0 to dim_f" }
        return this
    }

    companion object {
        /**
         * UVR-MDX-NET-Voc_FT as the phone ran it (lyrify-bench handoff/separation-test/mdx-uvr_mdxnet_voc_ft.json, the
         * separation test's spec, checked against this by a unit test): n_fft 7680, hop 1024, 3072 of the 3841 bins,
         * 256 frames per 5.9 s chunk, audio-separator's "ov25" chunking (overlap 0.25), bins 0-2 zeroed, the mix fed as
         * decoded and the stem taken as the model gives it (no peak normalisation: it changes only the stem's gain),
         * the string batch dimension fixed to 1.
         */
        val VOC_FT: MdxSpec = MdxSpec(
            name = "uvr_mdxnet_voc_ft",
            sampleRate = 44_100,
            nFft = 7680,
            hopLength = 1024,
            dimF = 3072,
            dimT = 256,
            overlap = 0.25,
            zeroLowBins = 3,
            normalizeMaxPeak = null,
            multiplyOutputByInputPeak = false,
            inputName = "input",
            outputName = "output",
            freeDimensionOverrides = mapOf("batch_size" to 1L),
        ).check()
    }
}

/** `np.hanning(m)`: the symmetric Hann window, 1 for m = 1. */
fun hanning(m: Int): FloatArray =
    if (m == 1) floatArrayOf(1f) else FloatArray(m) { (0.5 - 0.5 * cos(2.0 * PI * it / (m - 1))).toFloat() }

/**
 * MDXSeparator.separate + demix + run_model (python-audio-separator v0.48.0, batch size 1, no denoise): optional peak
 * normalisation; the mix padded with `trim = n_fft / 2` zeros in front and `gen + trim - total % gen` behind; chunks of
 * `hop * (dim_t - 1)` samples every `int((1 - overlap) * chunk)`, the last one zero-padded; per chunk STFT, bins below
 * [MdxSpec.zeroLowBins] zeroed, the model, iSTFT, weighted by `np.hanning(actual chunk length)`; the sum divided by the
 * summed windows, `trim` cut from the front, cut to the mix's length, times the original peak. Close it to stop the
 * STFT threads.
 *
 * Streamed: the mix is read a chunk ahead ([MixSource]) and the weighted sums are kept for one chunk of positions only.
 * Once no later chunk reaches a position (one step behind the current chunk's start), its value is final and goes to
 * the [VocalSink]. Every position gets the same sums in the same order as with whole arrays, so the same values; the
 * memory is a few chunks, whatever the song's length.
 */
class MdxSeparator(private val model: SegmentModel, private val spec: MdxSpec, stftThreads: Int = 1) : AutoCloseable {
    private val stft = Stft(spec.nFft, spec.hopLength, stftThreads)
    private val values = 4 * spec.dimF * spec.dimT

    /** np.hanning(chunk), every segment's window but a short last one. */
    private val chunkWindow = if (spec.overlap != 0.0) hanning(spec.chunkSize) else FloatArray(spec.chunkSize) { 1f }

    init {
        spec.check()
        val expected = listOf(1L, 4L, spec.dimF.toLong(), spec.dimT.toLong())
        require(model.inputShape.toList() == expected) { "expected a $expected input, got ${model.inputShape.toList()}" }
        require(stft.frames(spec.chunkSize) == spec.dimT)
    }

    private fun paddedLength(frames: Int): Int = spec.trim + frames + spec.genSize + spec.trim - frames % spec.genSize

    /** Segments (model runs) a mix of [frames] takes. */
    fun segmentCount(frames: Int): Int = (paddedLength(frames) + spec.step - 1) / spec.step

    /**
     * Separates [mix], read to its end, into [vocals]. A spec that normalises by the mix's peak or scales the output by
     * it needs that [peak] up front (a streamed mix cannot say it before it ends); Voc_FT needs none. [progress] is told
     * after each segment (the total from [MixSource.expectedFrames] until the mix has ended) and may throw to stop.
     */
    fun separate(mix: MixSource, vocals: VocalSink, progress: Progress = Progress { _, _ -> }, peak: Float? = null): SeparationStats {
        val max = spec.normalizeMaxPeak
        require((max == null && !spec.multiplyOutputByInputPeak) || peak != null) { "${spec.name} needs the mix's peak up front" }
        val scale = if (max != null && peak != null && peak > max) (max / peak).toFloat() else 1f
        val gain = if (spec.multiplyOutputByInputPeak && peak != null) peak else 1f
        val trim = spec.trim
        val chunk = spec.chunkSize
        val step = spec.step
        // Segment k's input, position j = mix frame k * step - trim + j (zero before the mix and past its end).
        val left = FloatArray(chunk)
        val right = FloatArray(chunk)
        // The weighted sums and summed windows of output positions k * step - trim + j.
        val sumL = FloatArray(chunk)
        val sumR = FloatArray(chunk)
        val divider = FloatArray(chunk)
        val outL = FloatArray(step)
        val outR = FloatArray(step)
        val specIn = FloatArray(values)
        val specOut = FloatArray(values)
        val wavL = FloatArray(chunk)
        val wavR = FloatArray(chunk)
        var total = -1 // the mix's length, once it has ended
        var read = 0
        var nonFinite = 0L
        var k = 0
        while (true) {
            val start = k * step
            if (k > 0) {
                shift(left, step)
                shift(right, step)
            }
            // Read the mix frames this segment adds, up to its end or the mix's.
            var at = read - (start - trim)
            while (total < 0 && at < chunk) {
                val n = mix.read(left, right, at, chunk - at)
                if (n == 0) {
                    total = read
                    break
                }
                if (scale != 1f) for (j in at until at + n) {
                    left[j] *= scale
                    right[j] *= scale
                }
                at += n
                read += n
            }
            // Once the mix has ended, segment k always exists: the padded mix runs past what has been read by more
            // than trim, so past this segment's start.
            val count = if (total < 0) -1 else segmentCount(total)
            stft.forwardStereo(left, right, spec.dimF, specIn)
            for (ch in 0 until 4) for (f in 0 until spec.zeroLowBins) specIn.fill(0f, (ch * spec.dimF + f) * spec.dimT, (ch * spec.dimF + f + 1) * spec.dimT)
            val input = model.input
            input.clear()
            input.put(specIn)
            input.rewind()
            val y = model.run()
            require(y.limit() >= values) { "output has ${y.limit()} values, expected $values" }
            y.position(0)
            y.get(specOut)
            for (i in specOut.indices) {
                if (!specOut[i].isFinite()) {
                    specOut[i] = 0f
                    nonFinite++
                }
            }
            stft.inverseStereo(specOut, spec.dimF, spec.dimT, wavL, wavR)
            // Until the mix has ended, a segment always has its whole chunk inside the padded mix.
            val actual = if (total < 0) chunk else min(start + chunk, paddedLength(total)) - start
            val window = when {
                actual == chunk -> chunkWindow
                spec.overlap != 0.0 -> hanning(actual)
                else -> FloatArray(actual) { 1f }
            }
            val first = start - trim // output position of sums[0]
            for (j in 0 until actual) {
                val o = first + j
                if (o < 0 || (total >= 0 && o >= total)) continue
                sumL[j] += wavL[j] * window[j]
                sumR[j] += wavR[j] * window[j]
                divider[j] += window[j]
            }
            // Positions before the next segment's first are final; after the last segment, all up to the mix's end
            // (fewer than a step: the padding behind the mix is longer than trim). A segment run before the mix is
            // known to have ended is never the last: when the mix ends exactly as its chunk is full, the padding behind
            // it (more than trim) puts the next segment's start before the padded end.
            val last = k == count - 1
            val until = if (last) total - first else minOf(step, if (total < 0) step else total - first)
            val from = maxOf(0, -first)
            if (until > from) {
                for (j in from until until) {
                    outL[j - from] = if (divider[j] > 0f) sumL[j] / divider[j] * gain else 0f
                    outR[j - from] = if (divider[j] > 0f) sumR[j] / divider[j] * gain else 0f
                }
                vocals.accept(outL, outR, until - from)
            }
            progress.step(k + 1, if (count >= 0) count else maxOf(k + 1, segmentCount(mix.expectedFrames)))
            if (last) break
            shift(sumL, step)
            shift(sumR, step)
            shift(divider, step)
            k++
        }
        return SeparationStats(total, segmentCount(total), nonFinite)
    }

    override fun close() = stft.close()

    private companion object {
        /** Moves [values] [by] places to the front, zeros behind. */
        fun shift(values: FloatArray, by: Int) {
            values.copyInto(values, 0, by, values.size)
            values.fill(0f, values.size - by, values.size)
        }
    }
}

/**
 * torch.stft / torch.istft as python-audio-separator's `uvr_lib_v5/stft.py` calls them: periodic Hann window of
 * [nFft], hop [hop], `center=True` with reflect padding, one-sided, unnormalised. Both channels of a stereo signal go
 * through one complex FFT per frame (left as the real part, right as the imaginary part), split by Hermitian symmetry.
 *
 * The spectrum layout is the model's: (left re, left im, right re, right im) x bins below `keepBins` x frames,
 * row-major. Computed in double precision with [Fft] (mixed-radix Stockham, precomputed twiddles); the window, the
 * synthesis window (window / n_fft) and the inverse of the summed squared window are computed once.
 *
 * Speed (spike README "Fast STFT"): frames go [BLOCK] at a time through a frame-major block buffer, so the model layout,
 * where one frame is a column, is written and read in runs of [BLOCK] consecutive values instead of one value per
 * row; and with [threads] > 1 the frames are split into contiguous ranges, one per thread (the caller takes the
 * first), each thread with its own FFT, buffers and, for the inverse, its own overlap-add accumulator that the caller
 * adds up at the end. Buffers are sized on the first call and kept. One call at a time; [close] stops the threads.
 */
class Stft(val nFft: Int, val hop: Int, val threads: Int = 1) : AutoCloseable {
    val bins = nFft / 2 + 1
    private val window = DoubleArray(nFft) { 0.5 - 0.5 * cos(2.0 * PI * it / nFft) }
    private val synthesis = DoubleArray(nFft) { window[it] / nFft }
    private val workers = List(threads) { Worker() }
    private val pool: ExecutorService? = if (threads > 1) {
        Executors.newFixedThreadPool(threads - 1) { r -> Thread(r, "Lyrify-stft").apply { isDaemon = true } }
    } else {
        null
    }
    private var acc = Array(2) { DoubleArray(0) }

    /** 1 / (overlap-added squared window) over the kept samples, for [envelopeFrames] frames. */
    private var inverseEnvelope = DoubleArray(0)
    private var envelopeFrames = -1

    init {
        require(threads in 1..MAX_THREADS) { "threads must be 1 to $MAX_THREADS" }
    }

    /** One thread's FFT and buffers. */
    private inner class Worker {
        val fft = Fft(nFft)
        val re = DoubleArray(nFft)
        val im = DoubleArray(nFft)

        /** [BLOCK] frames of the model layout, frame-major: block[b * rows + row], row = channel part x bin. */
        var block = FloatArray(0)

        /** Inverse: this range's overlap-add, from sample `first frame * hop`. */
        val acc = Array(2) { DoubleArray(0) }

        fun block(rows: Int): FloatArray {
            if (block.size < BLOCK * rows) block = FloatArray(BLOCK * rows)
            return block
        }
    }

    /** Frames of a signal of [samples] with `center=True`. */
    fun frames(samples: Int): Int = 1 + samples / hop

    fun forwardStereo(left: FloatArray, right: FloatArray, keepBins: Int, out: FloatArray) {
        val n = left.size
        require(right.size == n) { "channels differ in length" }
        require(n > nFft / 2) { "reflect padding needs more than ${nFft / 2} samples" }
        require(keepBins in 1..bins)
        val frames = frames(n)
        require(out.size >= 4 * keepBins * frames) { "output too small" }
        parallel(frames) { w, from, to -> forwardRange(w, left, right, keepBins, frames, out, from, to) }
    }

    private fun forwardRange(w: Worker, left: FloatArray, right: FloatArray, keepBins: Int, frames: Int, out: FloatArray, from: Int, to: Int) {
        val n = left.size
        val pad = nFft / 2
        val rows = 4 * keepBins
        val block = w.block(rows)
        val re = w.re
        val im = w.im
        var t0 = from
        while (t0 < to) {
            val count = min(BLOCK, to - t0)
            for (b in 0 until count) {
                val offset = (t0 + b) * hop - pad
                if (offset >= 0 && offset + nFft <= n) {
                    for (j in 0 until nFft) {
                        val g = window[j]
                        re[j] = left[offset + j] * g
                        im[j] = right[offset + j] * g
                    }
                } else {
                    for (j in 0 until nFft) {
                        var i = offset + j
                        if (i < 0) i = -i else if (i >= n) i = 2 * (n - 1) - i // numpy "reflect"
                        re[j] = left[i] * window[j]
                        im[j] = right[i] * window[j]
                    }
                }
                w.fft.transform(re, im)
                // Z = FFT(l + i r): L[k] = (Z[k] + conj(Z[n-k])) / 2, R[k] = (Z[k] - conj(Z[n-k])) / 2i.
                val o = b * rows
                for (k in 0 until keepBins) {
                    val m = if (k == 0) 0 else nFft - k
                    val zr = re[k]
                    val zi = im[k]
                    val cr = re[m]
                    val ci = im[m]
                    block[o + k] = ((zr + cr) * 0.5).toFloat()
                    block[o + keepBins + k] = ((zi - ci) * 0.5).toFloat()
                    block[o + 2 * keepBins + k] = ((zi + ci) * 0.5).toFloat()
                    block[o + 3 * keepBins + k] = ((cr - zr) * 0.5).toFloat()
                }
            }
            for (row in 0 until rows) {
                val dst = row * frames + t0
                for (b in 0 until count) out[dst + b] = block[b * rows + row]
            }
            t0 += count
        }
    }

    /**
     * The inverse: bins at and above [keepBins] are zero, the imaginary parts of the DC and Nyquist bins are ignored
     * (as a C2R FFT does), windowed overlap-add divided by the summed squared window, `nFft / 2` cut from each end:
     * `hop * (frames - 1)` samples per channel.
     */
    fun inverseStereo(spec: FloatArray, keepBins: Int, frames: Int, outLeft: FloatArray, outRight: FloatArray) {
        require(keepBins in 1..bins && spec.size >= 4 * keepBins * frames)
        val samples = hop * (frames - 1)
        require(outLeft.size >= samples && outRight.size >= samples) { "output too small" }
        val length = nFft + hop * (frames - 1)
        if (envelopeFrames != frames) envelope(frames, samples)
        val ranges = parallel(frames) { w, from, to -> inverseRange(w, spec, keepBins, frames, from, to) }
        val sum = if (ranges.size == 1) {
            ranges[0].first.acc // one range from frame 0: already the whole overlap-add
        } else {
            if (acc[0].size != length) acc = Array(2) { DoubleArray(length) }
            acc[0].fill(0.0)
            acc[1].fill(0.0)
            for ((w, from, to) in ranges) {
                val base = from * hop
                val count = nFft + hop * (to - from - 1)
                for (c in 0 until 2) {
                    val src = w.acc[c]
                    val dst = acc[c]
                    for (i in 0 until count) dst[base + i] += src[i]
                }
            }
            acc
        }
        val pad = nFft / 2
        val l = sum[0]
        val r = sum[1]
        for (i in 0 until samples) {
            val e = inverseEnvelope[i]
            outLeft[i] = (l[pad + i] * e).toFloat()
            outRight[i] = (r[pad + i] * e).toFloat()
        }
    }

    private fun inverseRange(w: Worker, spec: FloatArray, keepBins: Int, frames: Int, from: Int, to: Int) {
        val count = nFft + hop * (to - from - 1)
        if (w.acc[0].size < count) {
            w.acc[0] = DoubleArray(count)
            w.acc[1] = DoubleArray(count)
        } else {
            w.acc[0].fill(0.0, 0, count)
            w.acc[1].fill(0.0, 0, count)
        }
        val accL = w.acc[0]
        val accR = w.acc[1]
        val rows = 4 * keepBins
        val block = w.block(rows)
        val re = w.re
        val im = w.im
        val nyquist = if (nFft % 2 == 0) nFft / 2 else -1
        // Bins keepBins .. n - keepBins are zero; the loop below writes the low bins and their mirrors.
        val zeroFrom = keepBins
        val zeroTo = nFft - keepBins + 1
        var t0 = from
        while (t0 < to) {
            val n = min(BLOCK, to - t0)
            for (row in 0 until rows) {
                val src = row * frames + t0
                for (b in 0 until n) block[b * rows + row] = spec[src + b]
            }
            for (b in 0 until n) {
                if (zeroFrom < zeroTo) {
                    re.fill(0.0, zeroFrom, zeroTo)
                    im.fill(0.0, zeroFrom, zeroTo)
                }
                val o = b * rows
                for (k in 0 until keepBins) {
                    val lr = block[o + k].toDouble()
                    val rr = block[o + 2 * keepBins + k].toDouble()
                    if (k == 0 || k == nyquist) {
                        re[k] = lr
                        im[k] = -rr
                    } else {
                        val li = block[o + keepBins + k].toDouble()
                        val ri = block[o + 3 * keepBins + k].toDouble()
                        // conj(Z) with Z = L + iR and the Hermitian halves L[n-k] = conj(L[k]), R[n-k] = conj(R[k]).
                        re[k] = lr - ri
                        im[k] = -(li + rr)
                        re[nFft - k] = lr + ri
                        im[nFft - k] = li - rr
                    }
                }
                // Inverse FFT by conjugation: x = conj(FFT(conj(Z))) / n; then l = Re x, r = Im x.
                w.fft.transform(re, im)
                val base = (t0 + b - from) * hop
                for (j in 0 until nFft) {
                    val g = synthesis[j]
                    accL[base + j] += re[j] * g
                    accR[base + j] -= im[j] * g
                }
            }
            t0 += n
        }
    }

    /**
     * Splits frames 0 until [frames] into up to [threads] contiguous ranges (multiples of [BLOCK]), runs [task] on
     * them (the first on the calling thread) and returns the ranges with their workers once all are done.
     */
    private fun parallel(frames: Int, task: (Worker, Int, Int) -> Unit): List<Triple<Worker, Int, Int>> {
        val per = ((frames + threads - 1) / threads + BLOCK - 1) / BLOCK * BLOCK
        val ranges = (0 until threads).map { i -> Triple(workers[i], min(frames, i * per), min(frames, (i + 1) * per)) }
            .filter { it.second < it.third }
        val pool = pool
        if (pool == null || ranges.size == 1) {
            ranges.forEach { (w, from, to) -> task(w, from, to) }
            return ranges
        }
        val futures = ranges.drop(1).map { (w, from, to) -> pool.submit { task(w, from, to) } }
        try {
            ranges[0].let { (w, from, to) -> task(w, from, to) }
        } finally {
            for (f in futures) {
                try {
                    f.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        }
        return ranges
    }

    private fun envelope(frames: Int, samples: Int) {
        val sum = DoubleArray(nFft + hop * (frames - 1))
        for (t in 0 until frames) for (j in 0 until nFft) sum[t * hop + j] += window[j] * window[j]
        val pad = nFft / 2
        inverseEnvelope = DoubleArray(samples) { i ->
            val e = sum[pad + i]
            check(e > 1e-11) { "window envelope is zero at $i: hop too large for n_fft" }
            1.0 / e
        }
        envelopeFrames = frames
    }

    override fun close() {
        pool?.shutdownNow()
    }

    companion object {
        /** Frames per block of the layout transpose: 16 floats = one 64-byte cache line per row. */
        const val BLOCK = 16
        const val MAX_THREADS = 8
    }
}
