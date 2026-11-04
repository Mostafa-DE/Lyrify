package app.lyrify.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class LogMelTest {
    /** tools/make_goldens.py signal(): chirp 100 Hz -> 6 kHz, 440 Hz, AM 3 kHz, silence 5.0-5.5 s; 12.345 s. */
    private fun syntheticSignal(): FloatArray {
        val n = 197_520
        val sr = 16000
        val total = n.toDouble() / sr
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            if (t >= 5.0 && t < 5.5) return@FloatArray 0f
            val chirp = 0.3 * sin(2.0 * PI * (100.0 * t + (6000.0 - 100.0) * t * t / (2.0 * total)))
            val tone = 0.1 * sin(2.0 * PI * 440.0 * t)
            val am = 0.05 * sin(2.0 * PI * 3000.0 * t) * (0.5 + 0.5 * sin(2.0 * PI * 0.7 * t))
            (chirp + tone + am).toFloat()
        }
    }

    private fun golden(): FloatArray {
        val bytes = GZIPInputStream(File("src/test/resources/npu/mel_golden_chirp.f32.gz").inputStream()).use { it.readBytes() }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    @Test
    fun `matches transformers' WhisperFeatureExtractor within 1e-3 on a synthetic signal`() {
        val expected = golden()
        val actual = LogMel().compute(syntheticSignal())
        assertEquals(128 * 3000, expected.size)
        assertEquals(expected.size, actual.size)
        var maxDiff = 0.0
        var worst = -1
        for (i in expected.indices) {
            val d = abs(expected[i] - actual[i]).toDouble()
            if (d > maxDiff) {
                maxDiff = d
                worst = i
            }
        }
        println("log-mel golden: max abs diff %.3e at mel %d, frame %d".format(maxDiff, worst / 3000, worst % 3000))
        assertTrue("max abs diff $maxDiff", maxDiff < 1e-3)
    }

    @Test
    fun `silence gives the floor everywhere, (log10(1e-10) + 4) over 4`() {
        val mel = LogMel().compute(FloatArray(0))
        assertEquals(128 * 3000, mel.size)
        assertTrue(mel.all { it == -1.5f })
    }

    @Test
    fun `audio longer than 30 s is cut to its first 30 s`() {
        val long = FloatArray(LogMel.N_SAMPLES + 16000) { sin(it * 0.01).toFloat() * 0.1f }
        assertArrayEquals(LogMel().compute(long.copyOf(LogMel.N_SAMPLES)), LogMel().compute(long), 0f)
    }

    @Test
    fun `the FFT matches a direct DFT for mixed radices`() {
        val rnd = java.util.Random(7)
        for (n in listOf(1, 2, 3, 4, 5, 7, 8, 12, 16, 25, 49, 100, 400)) {
            val re = DoubleArray(n) { rnd.nextGaussian() }
            val im = DoubleArray(n) { rnd.nextGaussian() }
            val expRe = DoubleArray(n)
            val expIm = DoubleArray(n)
            for (k in 0 until n) for (j in 0 until n) {
                val a = -2.0 * PI * k * j / n
                expRe[k] += re[j] * cos(a) - im[j] * sin(a)
                expIm[k] += re[j] * sin(a) + im[j] * cos(a)
            }
            Fft(n).transform(re, im)
            var err = 0.0
            for (k in 0 until n) err = max(err, max(abs(re[k] - expRe[k]), abs(im[k] - expIm[k])))
            assertTrue("n=$n error $err", err < 1e-9 * n)
        }
    }

    @Test
    fun `mel filters are Slaney-normalised triangles from 0 to 8 kHz`() {
        val f = MelFilters(128, 400, 16000)
        for (m in 0 until 128) {
            val weights = (0 until 201).map { f.weight(m, it) }
            assertTrue("filter $m is empty", weights.any { it > 0 })
            assertTrue("filter $m has a negative weight", weights.all { it >= 0 })
            // Slaney norm: the triangle's area in Hz is 1, so its 40 Hz bins sum to about 1 / 40. The low filters are
            // narrower than a bin, so their sampled area strays (0.49 to 1.67 in transformers' own bank).
            val area = weights.sum() * 40.0
            assertTrue("filter $m area $area", area in 0.45..1.7)
        }
        assertEquals(0.0, MelFilters.hertzToMel(0.0), 0.0)
        assertEquals(15.0, MelFilters.hertzToMel(1000.0), 1e-12)
        assertEquals(8000.0, MelFilters.melToHertz(MelFilters.hertzToMel(8000.0)), 1e-9)
        val l = MelFilters.linspace(0.0, 1.0, 11)
        assertEquals(1.0, l.last(), 0.0)
        assertEquals(0.3, l[3], 1e-15)
    }
}
