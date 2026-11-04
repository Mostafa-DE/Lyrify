package app.lyrify.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The MDX separation's CPU side, ported with lyrify-npu-spike's tests (separation/SeparationTest.kt): goldens from the
 * spike's tools/make_separation_goldens.py (numpy ports of torch.stft/istft and of python-audio-separator, and the
 * bench's own sep_mdx.py), on synthetic tones and chirps. No audio is stored.
 */
class SeparationTest {
    /** tools/make_separation_goldens.py signal(n): synthetic stereo tones and a chirp at 44.1 kHz, peak above 0.9. */
    private fun signal(n: Int): Array<FloatArray> {
        val sr = 44100
        val total = n.toDouble() / sr
        val l = FloatArray(n)
        val r = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            if (t >= 0.05 && t < 0.06) continue
            val left = 0.6 * sin(2.0 * PI * 220.0 * t) +
                0.5 * sin(2.0 * PI * (200.0 * t + (9000.0 - 200.0) * t * t / (2.0 * total)))
            val right = 0.3 * sin(2.0 * PI * 330.0 * t) +
                0.25 * sin(2.0 * PI * 5000.0 * t) * (0.5 + 0.5 * sin(2.0 * PI * 3.0 * t)) + 0.3 * left
            l[i] = left.toFloat()
            r[i] = right.toFloat()
        }
        return arrayOf(l, r)
    }

    private fun golden(name: String): FloatArray {
        val bytes = GZIPInputStream(File("src/test/resources/npu/$name").inputStream()).use { it.readBytes() }
        val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(floats.remaining()).also { floats.get(it) }
    }

    /** Largest |a - b| divided by the largest |b|. */
    private fun relativeError(a: FloatArray, b: FloatArray): Double {
        assertEquals(b.size, a.size)
        var diff = 0.0
        var scale = 0.0
        for (i in a.indices) {
            diff = max(diff, abs(a[i] - b[i]).toDouble())
            scale = max(scale, abs(b[i]).toDouble())
        }
        return diff / scale
    }

    /** A model that returns its input times [gain] (MDX: spectrum in, spectrum out), NaN at [nanAt]. */
    private class ScaleModel(override val inputShape: LongArray, private val gain: Float, private val nanAt: Int = -1) : SegmentModel {
        override val input: FloatBuffer = FloatBuffer.allocate(inputShape.fold(1L) { a, b -> a * b }.toInt())
        var runs = 0

        override fun run(): FloatBuffer {
            runs++
            val out = FloatBuffer.allocate(input.capacity())
            for (i in 0 until input.capacity()) out.put(i, if (i == nanAt) Float.NaN else input.get(i) * gain)
            return out
        }
    }

    @Test
    fun `STFT matches numpy (torch stft semantics) on a synthetic stereo signal`() {
        val (l, r) = signal(4096)
        val stft = Stft(1536, 256)
        assertEquals(17, stft.frames(4096))
        val out = FloatArray(4 * 512 * 17)
        stft.forwardStereo(l, r, 512, out)
        val err = relativeError(out, golden("separation_stft.f32.gz"))
        assertTrue("relative error $err", err < 1e-5)
    }

    @Test
    fun `iSTFT matches numpy (torch istft semantics) with the low bins zeroed and the top bins cut`() {
        val spec = golden("separation_stft.f32.gz")
        for (ch in 0 until 4) for (f in 0 until 3) spec.fill(0f, (ch * 512 + f) * 17, (ch * 512 + f + 1) * 17)
        val l = FloatArray(4096)
        val r = FloatArray(4096)
        Stft(1536, 256).inverseStereo(spec, 512, 17, l, r)
        val expected = golden("separation_istft.f32.gz")
        val err = relativeError(l + r, expected.copyOfRange(0, 4096) + expected.copyOfRange(4096, 8192))
        assertTrue("relative error $err", err < 1e-5)
    }

    @Test
    fun `iSTFT of the STFT gives the signal back at the real MDX size, on 1 and 4 threads`() {
        val rnd = java.util.Random(3)
        val n = 1024 * 20
        val l = FloatArray(n) { (rnd.nextGaussian() * 0.3).toFloat() }
        val r = FloatArray(n) { sin(it * 0.01).toFloat() * 0.5f }
        for (threads in listOf(1, 4)) {
            Stft(7680, 1024, threads).use { stft ->
                val frames = stft.frames(n)
                val spec = FloatArray(4 * stft.bins * frames)
                stft.forwardStereo(l, r, stft.bins, spec)
                val bl = FloatArray(n)
                val br = FloatArray(n)
                stft.inverseStereo(spec, stft.bins, frames, bl, br)
                val err = max(relativeError(bl, l), relativeError(br, r))
                assertTrue("$threads threads: relative error $err", err < 1e-5)
            }
        }
    }

    @Test
    fun `the MDX demix matches python-audio-separator's, ported to numpy, with a fake model`() {
        val spec = MdxSpec(nFft = 1536, hopLength = 256, dimF = 512, dimT = 32, overlap = 0.25, zeroLowBins = 3, normalizeMaxPeak = 0.9)
        val model = ScaleModel(longArrayOf(1, 4, 512, 32), 0.5f)
        val separator = MdxSeparator(model, spec)
        val steps = ArrayList<Int>()
        val mix = ArrayMix(signal(20000), piece = 1_000)
        val vocals = CollectedVocals()
        // The spec normalises by the peak (above 0.9 here), which a streamed mix gives up front.
        val out = separator.separate(mix, vocals, { done, total ->
            assertEquals(5, total)
            steps += done
        }, peak = mix.peak())
        // trim 768, chunk 7936, gen 6400, pad 6400 + 768 - 20000 % 6400 = 6368, padded 27136, step 5952: 5 chunks.
        assertEquals(5, separator.segmentCount(20000))
        assertEquals(5, model.runs)
        assertEquals((1..5).toList(), steps)
        assertEquals(0L, out.nonFinite)
        assertEquals(20000, out.frames)
        val stereo = vocals.stereo()
        val err = relativeError(stereo[0] + stereo[1], golden("separation_mdx.f32.gz"))
        assertTrue("relative error $err", err < 1e-4)
        // Equal to the whole-array demix, to the bit.
        val whole = WholeArrays.separate(ScaleModel(longArrayOf(1, 4, 512, 32), 0.5f), spec, signal(20000))
        assertArrayEquals(whole[0], stereo[0], 0f)
        assertArrayEquals(whole[1], stereo[1], 0f)
        assertThrows(IllegalArgumentException::class.java) { separator.separate(ArrayMix(signal(20000)), CollectedVocals()) }
    }

    @Test
    fun `MDX zeroes NaN outputs and counts them, and a progress that throws stops it`() {
        val spec = MdxSpec(nFft = 1536, hopLength = 256, dimF = 512, dimT = 32, normalizeMaxPeak = null, multiplyOutputByInputPeak = false)
        val model = ScaleModel(longArrayOf(1, 4, 512, 32), 0.5f, nanAt = 5000)
        val vocals = CollectedVocals()
        val out = MdxSeparator(model, spec).separate(ArrayMix(signal(20000)), vocals)
        assertEquals(5L, out.nonFinite) // one per segment
        assertTrue(vocals.stereo().all { c -> c.size == 20000 && c.all { it.isFinite() } })

        val stopped = ScaleModel(longArrayOf(1, 4, 512, 32), 0.5f)
        assertThrows(IllegalStateException::class.java) {
            MdxSeparator(stopped, spec).separate(ArrayMix(signal(20000)), CollectedVocals(), { done, _ -> check(done < 2) { "stop" } })
        }
        assertEquals(2, stopped.runs)
    }

    @Test
    fun `the vocals come out a step at a time, each position once and final, and a short mix in one go`() {
        // Fed as decoded and taken as the model gives it, as Voc_FT: nothing needs the peak up front.
        val spec = MdxSpec(nFft = 1536, hopLength = 256, dimF = 512, dimT = 32, normalizeMaxPeak = null, multiplyOutputByInputPeak = false)
        // chunk 7936, trim 768, gen 6400, step 5952. Boundaries: 7168 and 13120 end exactly as segment 0's and segment 1's
        // chunks fill; 6400 and 12800 are whole gens (total % gen == 0); 7936 is one chunk; 5951 is a step less one.
        for (n in listOf(20000, 5951, 7936 - 768, 13120, 6400, 12800, 7936, 1, 0)) {
            val vocals = CollectedVocals()
            val out = MdxSeparator(ScaleModel(longArrayOf(1, 4, 512, 32), 0.5f), spec).separate(ArrayMix(signal(n), piece = 333), vocals)
            val whole = WholeArrays.separate(ScaleModel(longArrayOf(1, 4, 512, 32), 0.5f), spec, signal(n))
            assertEquals(n, out.frames)
            assertArrayEquals("$n frames", whole[0], vocals.stereo()[0], 0f)
            assertArrayEquals("$n frames", whole[1], vocals.stereo()[1], 0f)
            // Never more than a step (5952) at a time.
            assertTrue(vocals.calls <= out.segments)
        }
    }

    @Test
    fun `an MDX model with the wrong input shape or a bad spec is refused`() {
        val spec = MdxSpec(nFft = 1536, hopLength = 256, dimF = 512, dimT = 32)
        assertThrows(IllegalArgumentException::class.java) { MdxSeparator(ScaleModel(longArrayOf(1, 4, 512, 64), 1f), spec) }
        assertThrows(IllegalArgumentException::class.java) { MdxSpec(nFft = 1536, dimF = 2000, dimT = 32).check() }
        assertThrows(IllegalArgumentException::class.java) { MdxSpec(nFft = 7680, hopLength = 1024, dimF = 3072, dimT = 4).check() }

    }

    @Test
    fun `the Voc_FT constants are the separation test's spec of the model (mdx-uvr_mdxnet_voc_ft json)`() {
        val root = Json.parseToJsonElement(File("src/test/resources/npu/mdx-uvr_mdxnet_voc_ft.json").readText()).jsonObject
        fun obj(o: JsonObject, key: String) = o.getValue(key).jsonObject
        fun int(o: JsonObject, key: String) = o.getValue(key).jsonPrimitive.int
        val stft = obj(root, "stft")
        val chunking = obj(root, "chunking")
        val ov25 = obj(chunking, "ov25")
        val onnx = obj(root, "onnx")
        val spec = MdxSpec.VOC_FT
        assertEquals(root.getValue("name").jsonPrimitive.content, spec.name)
        assertEquals(int(obj(root, "audio_in"), "sample_rate"), spec.sampleRate)
        assertEquals(int(stft, "n_fft"), spec.nFft)
        assertEquals(int(stft, "hop"), spec.hopLength)
        assertEquals(int(stft, "bins_kept"), spec.dimF)
        assertEquals(int(stft, "frames"), spec.dimT)
        assertEquals("ov25", chunking.getValue("mode").jsonPrimitive.content)
        assertEquals(int(chunking, "chunk_samples"), spec.chunkSize)
        assertEquals(int(ov25, "trim"), spec.trim)
        assertEquals(int(ov25, "gen"), spec.genSize)
        assertEquals(int(ov25, "step"), spec.step)
        assertEquals(1.0 - int(ov25, "step").toDouble() / int(chunking, "chunk_samples"), spec.overlap, 0.0)
        assertEquals("set bins f = 0, 1, 2 to 0 in all 4 channels before running the model", obj(root, "model_input").getValue("zero_bins").jsonPrimitive.content)
        assertEquals(3, spec.zeroLowBins)
        // Fed as decoded, taken as the model gives it (notes.normalisation).
        assertEquals(null, spec.normalizeMaxPeak)
        assertEquals(false, spec.multiplyOutputByInputPeak)
        assertEquals(obj(onnx, "input").getValue("name").jsonPrimitive.content, spec.inputName)
        assertEquals(obj(onnx, "output").getValue("name").jsonPrimitive.content, spec.outputName)
        val inputShape = obj(onnx, "input").getValue("shape").jsonArray
        assertEquals(mapOf(inputShape[0].jsonPrimitive.content to 1L), spec.freeDimensionOverrides)
        assertEquals(listOf(4L, spec.dimF.toLong(), spec.dimT.toLong()), inputShape.drop(1).map { it.jsonPrimitive.long })
        // The model file the app downloads is the one the spec describes.
        val model = obj(root, "model")
        assertEquals(model.getValue("url").jsonPrimitive.content, app.lyrify.models.ModelSpec.MDX.url)
        assertEquals(model.getValue("sha256").jsonPrimitive.content, app.lyrify.models.ModelSpec.MDX.sha256)
        assertEquals(model.getValue("bytes").jsonPrimitive.long, app.lyrify.models.ModelSpec.MDX.bytes)
    }

    @Test
    fun `the MDX demix matches the bench's own sep_mdx demix ov25 at the Voc_FT size, on 1 and 4 threads`() {
        val expected = golden("separation_mdx_vocft.f32.gz")
        for (threads in listOf(1, 4)) {
            val model = ScaleModel(longArrayOf(1, 4, 3072, 256), 0.5f)
            val vocals = CollectedVocals()
            MdxSeparator(model, MdxSpec.VOC_FT, threads).use { separator ->
                assertEquals(2, separator.segmentCount(50000))
                separator.separate(ArrayMix(signal(50000), piece = 7_777), vocals)
            }
            assertEquals(2, model.runs)
            val out = vocals.stereo()
            val decimated = FloatArray(10000) { i -> out[i / 5000][(i % 5000) * 10] }
            val err = relativeError(decimated, expected)
            assertTrue("$threads threads: relative error $err", err < 1e-4)
        }
    }

    @Test
    fun `the FFT matches a direct DFT for every radix path`() {
        // 7680 = 8^3 x 3 x 5, 6144 = 8^3 x 4 x 3, 400 = 8 x 2 x 5^2, 1536, 77 = 7 x 11 (direct-DFT radices), small sizes.
        for (n in listOf(1, 2, 3, 4, 5, 8, 16, 77, 400, 1536, 7680)) {
            val rnd = java.util.Random(n.toLong())
            val xr = DoubleArray(n) { rnd.nextGaussian() }
            val xi = DoubleArray(n) { rnd.nextGaussian() }
            val re = xr.copyOf()
            val im = xi.copyOf()
            Fft(n).transform(re, im)
            var diff = 0.0
            var scale = 0.0
            for (k in 0 until n) {
                var sr = 0.0
                var si = 0.0
                for (j in 0 until n) {
                    val a = -2.0 * PI * ((j.toLong() * k) % n) / n
                    sr += xr[j] * cos(a) - xi[j] * sin(a)
                    si += xr[j] * sin(a) + xi[j] * cos(a)
                }
                diff = max(diff, max(abs(re[k] - sr), abs(im[k] - si)))
                scale = max(scale, max(abs(sr), abs(si)))
            }
            assertTrue("n $n: relative error ${diff / scale}", diff / scale < 1e-12)
        }
    }

    @Test
    fun `hanning matches numpy`() {
        assertArrayEquals(floatArrayOf(1f), hanning(1), 0f)
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f, 0.5f, 0f), hanning(5), 1e-7f) // np.hanning(5)
        assertEquals((0.5 - 0.5 * cos(2 * PI * 3 / 9)).toFloat(), hanning(10)[3], 0f)
    }
}
