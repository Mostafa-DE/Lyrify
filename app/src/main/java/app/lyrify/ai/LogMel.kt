package app.lyrify.ai

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Whisper's log-mel spectrogram for large-v3 (128 bins), as transformers' WhisperFeatureExtractor computes it with
 * the default call (`padding="max_length"`): the audio is zero-padded or cut to 30 s (480000 samples at 16 kHz),
 * STFT with a 400-point periodic Hann window, hop 160, centred with reflect padding, power spectrum, Slaney-style mel
 * filter bank (0 to 8 kHz, Slaney norm), the last frame dropped (3000 frames), log10 with a 1e-10 floor, clamped to
 * 8 below the maximum and scaled by (x + 4) / 4. Computed in double precision; the golden test compares it with the
 * Python extractor.
 *
 * Not thread safe: one instance per thread (it keeps FFT buffers).
 */
class LogMel(val nMels: Int = N_MELS) {
    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2.0 * PI * it / N_FFT) }
    private val filters = MelFilters(nMels, N_FFT, SAMPLE_RATE)
    private val fft = Fft(N_FFT)
    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)
    private val power = DoubleArray(N_BINS)

    /** [nMels] x [N_FRAMES] values, row-major (mel bin, then frame), ready for the encoder's (1, nMels, 3000) input. */
    fun compute(audio: FloatArray): FloatArray {
        val x = DoubleArray(N_SAMPLES)
        for (i in 0 until min(audio.size, N_SAMPLES)) x[i] = audio[i].toDouble()
        // center=True: reflect-pad by N_FFT / 2 on both sides (numpy "reflect", the edge sample is not repeated).
        val pad = N_FFT / 2
        val padded = DoubleArray(N_SAMPLES + 2 * pad)
        System.arraycopy(x, 0, padded, pad, N_SAMPLES)
        for (i in 0 until pad) {
            padded[i] = x[pad - i]
            padded[pad + N_SAMPLES + i] = x[N_SAMPLES - 2 - i]
        }
        val mel = DoubleArray(nMels * N_FRAMES)
        for (t in 0 until N_FRAMES) {
            val offset = t * HOP
            for (n in 0 until N_FFT) {
                re[n] = padded[offset + n] * window[n]
                im[n] = 0.0
            }
            fft.transform(re, im)
            for (k in 0 until N_BINS) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until nMels) mel[m * N_FRAMES + t] = filters.apply(m, power)
        }
        var maxLog = Double.NEGATIVE_INFINITY
        for (i in mel.indices) {
            mel[i] = log10(max(mel[i], MEL_FLOOR))
            maxLog = max(maxLog, mel[i])
        }
        val floor = maxLog - 8.0
        return FloatArray(mel.size) { ((max(mel[it], floor) + 4.0) / 4.0).toFloat() }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val N_FFT = 400
        const val HOP = 160
        const val N_MELS = 128
        const val N_SAMPLES = 30 * SAMPLE_RATE
        const val N_FRAMES = N_SAMPLES / HOP
        const val N_BINS = N_FFT / 2 + 1
        private const val MEL_FLOOR = 1e-10
    }
}

/**
 * transformers' `mel_filter_bank(num_frequency_bins=201, num_mel_filters=nMels, min_frequency=0, max_frequency=8000,
 * sampling_rate=16000, norm="slaney", mel_scale="slaney")`, stored sparsely (each triangle covers a few bins).
 */
class MelFilters(nMels: Int, nFft: Int, sampleRate: Int) {
    private val first = IntArray(nMels)
    private val weights: Array<DoubleArray>

    init {
        val bins = nFft / 2 + 1
        val melMax = hertzToMel(sampleRate / 2.0)
        val melFreqs = linspace(hertzToMel(0.0), melMax, nMels + 2)
        val filterFreqs = DoubleArray(nMels + 2) { melToHertz(melFreqs[it]) }
        val fftFreqs = linspace(0.0, (sampleRate / 2).toDouble(), bins)
        weights = Array(nMels) { m ->
            val dense = DoubleArray(bins) { k ->
                val down = (fftFreqs[k] - filterFreqs[m]) / (filterFreqs[m + 1] - filterFreqs[m])
                val up = (filterFreqs[m + 2] - fftFreqs[k]) / (filterFreqs[m + 2] - filterFreqs[m + 1])
                max(0.0, min(down, up)) * 2.0 / (filterFreqs[m + 2] - filterFreqs[m])
            }
            val lo = dense.indexOfFirst { it > 0.0 }.let { if (it < 0) 0 else it }
            val hi = dense.indexOfLast { it > 0.0 }
            first[m] = lo
            if (hi < lo) DoubleArray(0) else dense.copyOfRange(lo, hi + 1)
        }
    }

    /** The full weight of filter [m] at FFT bin [k] (0 outside its triangle). */
    fun weight(m: Int, k: Int): Double = weights[m].getOrElse(k - first[m]) { 0.0 }

    fun apply(m: Int, power: DoubleArray): Double {
        val w = weights[m]
        val lo = first[m]
        var sum = 0.0
        for (i in w.indices) sum += w[i] * power[lo + i]
        return sum
    }

    companion object {
        private const val MIN_LOG_HERTZ = 1000.0
        private const val MIN_LOG_MEL = 15.0
        /** Mels per natural-log unit of Hz above 1 kHz. */
        private val LOG_STEP = 27.0 / ln(6.4)

        fun hertzToMel(hz: Double): Double =
            if (hz >= MIN_LOG_HERTZ) MIN_LOG_MEL + ln(hz / MIN_LOG_HERTZ) * LOG_STEP else 3.0 * hz / 200.0

        fun melToHertz(mel: Double): Double =
            if (mel >= MIN_LOG_MEL) MIN_LOG_HERTZ * exp((mel - MIN_LOG_MEL) / LOG_STEP) else 200.0 * mel / 3.0

        /** numpy.linspace: start + i * step, with the last value exactly [stop]. */
        fun linspace(start: Double, stop: Double, n: Int): DoubleArray {
            val step = (stop - start) / (n - 1)
            return DoubleArray(n) { if (it == n - 1) stop else start + it * step }
        }
    }
}
