package app.lyrify.ai

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Sample rates the models take. */
object Rates {
    /** The MDX vocal model, and the vocal gate's frames. */
    const val MIX: Int = 44_100

    /** Whisper and wav2vec2. */
    const val SPEECH: Int = 16_000
}

/** The mean of a stereo frame's channels as numpy's `x.mean(0)` computes it in float32: (0 + left + right) x 0.5. */
fun channelMean(left: Float, right: Float): Float {
    var acc = 0f
    acc += left
    acc += right
    return acc * 0.5f
}

/**
 * RMS of each whole frame of [frame] samples (a trailing partial frame is dropped), rounded to 6 decimals when
 * [rounded]: lyrify-bench `phone_demucs.py` `frame_rms` (rounded, the gate's 1 s frames) and the 100 ms frames of
 * `phone_wcpp_variants.py` `vocal_chunks` (not rounded). Computed in double where numpy uses float32.
 */
fun frameRms(mono: FloatArray, frame: Int, rounded: Boolean): DoubleArray {
    require(frame > 0)
    return DoubleArray(mono.size / frame) { f ->
        var sum = 0.0
        for (i in f * frame until (f + 1) * frame) sum += mono[i].toDouble() * mono[i]
        val rms = sqrt(sum / frame)
        if (rounded) (rms * 1e6).roundToLong() / 1e6 else rms
    }
}

/**
 * Band-limited rational resampler: a Kaiser-windowed sinc low-pass evaluated as a polyphase filter bank (the spike's,
 * measured on the phone).
 *
 * With g = gcd(inRate, outRate), L = outRate / g and M = inRate / g, output sample n sits at input position
 * t = n * M / L = i + p / L, where i = floor(n * M / L) and p = (n * M) mod L. Each of the L phases p has its own
 * precomputed taps h_p[j] = h(j + p / L), so y[n] = sum over j of x[i - j] * h_p[j]. Samples outside the input
 * count as silence.
 *
 * The cutoff is [CUTOFF] times the lower of the two Nyquist frequencies. With [ZERO_CROSSINGS] zero crossings
 * per side and Kaiser beta [KAISER_BETA] (about 87 dB stopband), the transition band ends just below the output
 * Nyquist frequency, so nothing aliases into the output band: 44.1 kHz to 16 kHz is flat to about 6.7 kHz.
 * Every phase is normalised to unity gain at DC.
 */
class Resampler(val inRate: Int, val outRate: Int) {
    private val up: Int
    private val down: Int
    private val half: Int
    private val taps: Int
    private val bank: FloatArray

    init {
        require(inRate > 0 && outRate > 0) { "sample rates must be positive: $inRate -> $outRate" }
        val g = gcd(inRate, outRate)
        up = outRate / g
        down = inRate / g
        require(up <= MAX_PHASES) { "$inRate -> $outRate needs $up filter phases (max $MAX_PHASES)" }

        val cutoff = CUTOFF * min(1.0, outRate.toDouble() / inRate) // relative to the input Nyquist frequency
        val halfWidth = ZERO_CROSSINGS / cutoff // filter half-length in input samples
        half = ceil(halfWidth).toInt()
        taps = 2 * half + 1
        bank = FloatArray(up * taps)
        val i0Beta = besselI0(KAISER_BETA)
        val row = DoubleArray(taps)
        for (p in 0 until up) {
            var sum = 0.0
            for (k in 0 until taps) {
                val d = (k - half) + p.toDouble() / up // distance t - x index, in input samples
                val x = d / halfWidth
                row[k] = if (abs(x) >= 1.0) {
                    0.0
                } else {
                    cutoff * sinc(cutoff * d) * besselI0(KAISER_BETA * sqrt(1.0 - x * x)) / i0Beta
                }
                sum += row[k]
            }
            for (k in 0 until taps) bank[p * taps + k] = (row[k] / sum).toFloat()
        }
    }

    /** ceil(inputLength * outRate / inRate): the output covers the same duration as the input. */
    fun outputLength(inputLength: Int): Int = ((inputLength.toLong() * up + down - 1) / down).toInt()

    fun process(input: FloatArray): FloatArray {
        if (inRate == outRate) return input.copyOf()
        val n = input.size
        val out = FloatArray(outputLength(n))
        for (o in out.indices) {
            val position = o.toLong() * down
            val i = (position / up).toInt()
            val base = (position % up).toInt() * taps + half
            // x[i - j] exists for i - n + 1 <= j <= i.
            val jFrom = max(-half, i - n + 1)
            val jTo = min(half, i)
            var acc = 0f
            for (j in jFrom..jTo) acc += input[i - j] * bank[base + j]
            out[o] = acc
        }
        return out
    }

    /**
     * [process] for a signal that arrives in pieces, one channel: the same sums in the same order, so the same output.
     * An output sample is made once the input it needs has arrived ([push]); [finish] makes the rest once the input has
     * ended. Only the input still needed is kept.
     */
    inner class Stream {
        private var x = FloatArray(4096)
        private var x0 = 0L
        private var xn = 0
        private var next = 0L

        /** [count] samples of [input] from [from]; the outputs made go to [out]. */
        fun push(input: FloatArray, from: Int, count: Int, out: Floats) {
            if (inRate == outRate) return out.addAll(input, from, count)
            xn = append(x, x0, xn, input, from, count, keepFrom = next * down / up - half).let { (array, start, length) ->
                x = array
                x0 = start
                length
            }
            while (true) {
                val i = next * down / up
                if (i + half >= x0 + xn) break
                out.add(sample(i, (next * down % up).toInt(), Long.MAX_VALUE))
                next++
            }
        }

        /** The input has ended: the outputs left, up to [outputLength] of all that was pushed. */
        fun finish(out: Floats) {
            if (inRate == outRate) return
            val n = x0 + xn
            val total = outputLength(n.toInt())
            while (next < total) {
                out.add(sample(next * down / up, (next * down % up).toInt(), n))
                next++
            }
        }

        /** [process]'s sum for output position i = [i] + phase / L over an input of [n] samples (unknown: no end yet). */
        private fun sample(i: Long, phase: Int, n: Long): Float {
            val base = phase * taps + half
            val jFrom = max(-half.toLong(), i - n + 1).toInt()
            val jTo = min(half.toLong(), i).toInt()
            var acc = 0f
            for (j in jFrom..jTo) acc += x[(i - j - x0).toInt()] * bank[base + j]
            return acc
        }
    }

    companion object {
        const val ZERO_CROSSINGS = 32
        const val KAISER_BETA = 8.6
        const val CUTOFF = 0.92
        const val MAX_PHASES = 4096

        private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** Modified Bessel function of the first kind, order 0 (power series). */
        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val q = x * x / 4.0
            var k = 1
            while (term > 1e-12 * sum) {
                term *= q / (k.toDouble() * k)
                sum += term
                k++
            }
            return sum
        }
    }
}

/**
 * julius.resample_frac (julius 0.2.8, `ResampleFrac`), ported from lyrify-npu-spike (pipeline/VocalPipeline.kt): how the
 * bench makes the 16 kHz vocal stem Whisper hears (phone_demucs.py, sep_mdx.py). Windowed-sinc resampling from [oldSr]
 * to [newSr] after dividing both by their gcd (44100 -> 16000 is 441 -> 160), [zeros] zero crossings, a low-pass at
 * [rolloff] x the lower Nyquist. One kernel per output phase over `2 * width + old` input samples, the input padded by
 * repeating its edge samples, output length `floor(new * n / old)` (through float32, as julius does). The kernels are
 * computed in float32 like julius's; each keeps only its taps inside the zero crossings (the clamped ones outside are
 * about 1e-23). Float32 products, accumulated in float32 as torch's conv1d does.
 */
class ResampleFrac(oldSr: Int, newSr: Int, val zeros: Int = 24, val rolloff: Double = 0.945) {
    val old: Int
    val new: Int
    val width: Int
    private val kernels: Array<FloatArray>

    /** Index of each phase's first kept tap in the `2 * width + old` window. */
    private val firstTap: IntArray

    init {
        require(oldSr > 0 && newSr > 0)
        val g = gcd(oldSr, newSr)
        old = oldSr / g
        new = newSr / g
        val sr = min(new, old) * rolloff
        width = if (old == new) 0 else ceil(zeros * old / sr).toInt()
        val taps = 2 * width + old
        firstTap = IntArray(new)
        kernels = Array(new) { phase ->
            val kernel = FloatArray(taps)
            var lo = taps
            var hi = -1
            var sum = 0.0
            for (k in 0 until taps) {
                val raw = ((-phase.toDouble() / new).toFloat() + (k - width).toFloat() / old.toFloat()) * sr.toFloat()
                if (raw > -zeros && raw < zeros) {
                    lo = min(lo, k)
                    hi = max(hi, k)
                }
                val t = raw.coerceIn(-zeros.toFloat(), zeros.toFloat()) * PI.toFloat()
                val c = cos(t / zeros / 2)
                kernel[k] = (if (t == 0f) 1f else sin(t) / t) * (c * c)
                sum += kernel[k]
            }
            val s = sum.toFloat()
            for (k in 0 until taps) kernel[k] /= s
            firstTap[phase] = lo
            kernel.copyOfRange(lo, hi + 1)
        }
    }

    /** julius's default output length: `floor(new * n / old)`, the quotient rounded to float32 first. */
    fun outputLength(n: Int): Int = floor((new.toLong() * n / old.toDouble()).toFloat().toDouble()).toInt()

    fun resample(x: FloatArray): FloatArray {
        if (old == new) return x.copyOf()
        val n = x.size
        val out = FloatArray(outputLength(n))
        for (o in out.indices) {
            val phase = o % new
            val kernel = kernels[phase]
            // Input index of the first kept tap; the padded signal starts `width` samples before x[0].
            val start = (o / new) * old + firstTap[phase] - width
            var acc = 0f
            if (start >= 0 && start + kernel.size <= n) {
                for (k in kernel.indices) acc += kernel[k] * x[start + k]
            } else {
                for (k in kernel.indices) acc += kernel[k] * x[(start + k).coerceIn(0, n - 1)]
            }
            out[o] = acc
        }
        return out
    }

    /** The lowest first tap of any phase: no later output reads below its block's start plus this. */
    private val lowestTap: Int = firstTap.minOrNull() ?: 0

    /**
     * [resample] for a signal that arrives in pieces: the same sums in the same order, so the same output. An output
     * sample is made once all of its window has arrived ([push]); [finish] makes the rest, the end's edge repeated as
     * julius pads it, once the input has ended. Only the input still needed is kept.
     */
    inner class Stream {
        private var x = FloatArray(4096)
        private var x0 = 0L
        private var xn = 0
        private var next = 0L

        /** [count] samples of [input] from [from]; the outputs made go to [out]. */
        fun push(input: FloatArray, from: Int, count: Int, out: Floats) {
            if (old == new) return out.addAll(input, from, count)
            xn = append(x, x0, xn, input, from, count, keepFrom = (next / new) * old + lowestTap - width).let { (array, start, length) ->
                x = array
                x0 = start
                length
            }
            while (true) {
                val phase = (next % new).toInt()
                val start = (next / new) * old + firstTap[phase] - width
                if (start + kernels[phase].size > x0 + xn) break
                out.add(sample(start, phase, Long.MAX_VALUE))
                next++
            }
        }

        /** The input has ended: the outputs left, up to [outputLength] of all that was pushed. */
        fun finish(out: Floats) {
            if (old == new) return
            val n = x0 + xn
            val total = outputLength(n.toInt())
            while (next < total) {
                val phase = (next % new).toInt()
                out.add(sample((next / new) * old + firstTap[phase] - width, phase, n))
                next++
            }
        }

        /** [resample]'s sum for the window from input [start] of an input of [n] samples (unknown: no end yet). */
        private fun sample(start: Long, phase: Int, n: Long): Float {
            val kernel = kernels[phase]
            var acc = 0f
            if (start >= 0 && start + kernel.size <= n) {
                val at = (start - x0).toInt()
                for (k in kernel.indices) acc += kernel[k] * x[at + k]
            } else {
                for (k in kernel.indices) acc += kernel[k] * x[((start + k).coerceIn(0, n - 1) - x0).toInt()]
            }
            return acc
        }
    }

    private companion object {
        tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

/** A growing list of floats, without boxing. */
class Floats(capacity: Int = 1024) {
    var data: FloatArray = FloatArray(capacity)
        private set
    var size: Int = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun addAll(values: FloatArray, from: Int, count: Int) {
        if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
        values.copyInto(data, size, from, from + count)
        size += count
    }

    /** Drops the first [count] values. */
    fun drop(count: Int) {
        data.copyInto(data, 0, count, size)
        size -= count
    }

    fun clear() {
        size = 0
    }
}

/**
 * A stream's input buffer [x] (input samples [x0] until x0 + [xn]) with [count] samples of [input] from [from] added,
 * after dropping the samples below [keepFrom] (never below 0, where the stream may still clamp): the buffer (grown when
 * needed), its first sample's index and its length.
 */
private fun append(x: FloatArray, x0: Long, xn: Int, input: FloatArray, from: Int, count: Int, keepFrom: Long): Triple<FloatArray, Long, Int> {
    val drop = (keepFrom.coerceAtLeast(0) - x0).coerceIn(0L, xn.toLong()).toInt()
    var buffer = x
    if (drop > 0) buffer.copyInto(buffer, 0, drop, xn)
    val kept = xn - drop
    if (kept + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, kept + count))
    input.copyInto(buffer, kept, from, from + count)
    return Triple(buffer, x0 + drop, kept + count)
}

/**
 * The bench's 16 kHz stem file, in place: `np.clip(x, -1, 1)` written as PCM 16 by soundfile (libsndfile 1.2.2:
 * `floor(x * 32768)`, saturated to 32767; checked against soundfile in the spike's tools/make_full_goldens.py) and read
 * back as float32 (`s / 32768`). The phone does what the bench measured.
 */
fun pcm16RoundTrip(x: FloatArray) {
    for (i in x.indices) {
        val v = x[i].coerceIn(-1f, 1f)
        val s = floor(v * 32768f).coerceIn(-32768f, 32767f)
        x[i] = s / 32768f
    }
}
