package app.lyrify.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.math.max
import kotlin.math.sin

/**
 * The steps between the separation and Whisper, with lyrify-npu-spike's goldens (pipeline/VocalPipelineTest.kt, from its
 * tools/make_full_goldens.py: lyrify-bench's own vocal_chunks and v2_frac, julius 0.2.8 and soundfile, on synthetic
 * signals). The gate and the chunks are the app's own ports ([GateFrames], [VocalChunks]), held to the same goldens.
 */
class VocalPipelineTest {
    private val golden = Json.parseToJsonElement(File("src/test/resources/npu/full_pipeline.json").readText()).jsonObject

    private fun doubles(key: String) = golden.getValue(key).jsonArray.map { it.jsonPrimitive.double }.toDoubleArray()

    /** make_full_goldens.py resample_signal. */
    private fun resampleSignal(n: Int) = FloatArray(n) { i ->
        val t = i / 44100.0
        if (t >= 0.5 && t < 0.6) {
            0f
        } else {
            var v = 0.4 * sin(2 * PI * 440 * t) + 0.3 * sin(2 * PI * (100 * t + 1500 * t * t)) + 0.1 * sin(2 * PI * 10000 * t)
            if (t >= 1.5) v += 0.2
            v.toFloat()
        }
    }

    /** make_full_goldens.py stem_signal. */
    private fun stemSignal(seconds: Int) = FloatArray(seconds * 16000) { i ->
        val t = i / 16000.0
        (0.3 * sin(2 * PI * 220 * t) * (0.05 + abs(sin(PI * t / 9.73)))).toFloat()
    }

    @Test
    fun `the resampler matches julius resample_frac 44100 to 16000`() {
        val r = ResampleFrac(44100, 16000)
        assertEquals(listOf(441, 160, golden.getValue("resample_width").jsonPrimitive.int), listOf(r.old, r.new, r.width))
        val bytes = GZIPInputStream(File("src/test/resources/npu/full_resample.f32.gz").inputStream()).use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val expected = FloatArray(fb.remaining()).also { fb.get(it) }
        val y = r.resample(resampleSignal(89434))
        assertEquals(expected.size, y.size)
        var diff = 0.0
        for (i in y.indices) diff = max(diff, abs(y[i] - expected[i]).toDouble())
        assertTrue("max abs diff $diff", diff < 1e-5)
    }

    @Test
    fun `the resampler's output lengths are julius's`() {
        val r = ResampleFrac(44100, 16000)
        for (pair in golden.getValue("resample_lengths").jsonArray) {
            val (n, expected) = (pair as JsonArray).map { it.jsonPrimitive.int }
            assertEquals("length $n", expected, r.outputLength(n))
        }
        assertEquals(159, r.resample(FloatArray(440) { 0.25f }).size)
        // A constant stays constant (each kernel sums to 1), edges included.
        assertTrue(r.resample(FloatArray(5000) { 0.25f }).all { abs(it - 0.25f) < 1e-6f })
    }

    @Test
    fun `vocal chunks are the bench's vocal_chunks`() {
        val stem = stemSignal(golden.getValue("stem_sec").jsonPrimitive.int)
        val chunks = VocalChunks.of(GateFrames(doubles("vocal_rms"), doubles("mix_rms")), stem)
        val expected = golden.getValue("chunks").jsonArray.map { pair -> pair.jsonArray.map { it.jsonPrimitive.double } }
        assertEquals(expected.size, chunks.size)
        for ((c, e) in chunks.zip(expected)) {
            assertEquals(e[0], c.start, 1e-9)
            assertEquals(e[1], c.end, 1e-9)
        }
        assertTrue(chunks.all { it.durationSec <= 30.0 + 1e-9 })
    }

    @Test
    fun `the frame share is the bench's v2_frac`() {
        val gate = GateFrames(doubles("vocal_rms"), doubles("mix_rms")).gate()
        assertEquals(golden.getValue("gate_share").jsonPrimitive.double, gate.share, 1e-12)
        assertEquals(130, gate.frames)
        assertEquals(127, gate.validFrames) // 3 frames of silent mix
    }

    @Test
    fun `the PCM 16 round trip is soundfile's`() {
        val (values, expected) = golden.getValue("pcm16").jsonArray.map { row -> row.jsonArray.map { it.jsonPrimitive.double.toFloat() } }
        val x = values.toFloatArray()
        pcm16RoundTrip(x)
        assertArrayEquals(expected.toFloatArray(), x, 0f)
    }

    @Test
    fun `the chain the engine runs (gate, channel mean, 16 kHz, PCM 16, chunks) fits together on a synthetic song`() {
        // 40 s at 44.1 kHz: "vocals" (a 300 Hz tone) from 5 to 20 s and 24 to 38 s over a quieter "band" (a 90 Hz tone).
        val rate = 44100
        val n = 40 * rate + 1234
        val vocals = Array(2) { c -> FloatArray(n) { i -> val t = i / rate.toDouble(); if (t in 5.0..20.0 || t in 24.0..38.0) (0.3 * sin(2 * PI * 300 * t + c)).toFloat() else 0f } }
        val mix = Array(2) { c -> FloatArray(n) { i -> vocals[c][i] + (0.2 * sin(2 * PI * 90 * i / rate.toDouble())).toFloat() } }
        val frames = WholeArrays.gateFrames(vocals, mix)
        assertEquals(40, frames.size)
        assertTrue("share ${frames.gate().share}", frames.gate().share > 0.5)
        val stem = WholeArrays.stem(vocals)
        assertEquals(n * 160 / 441, stem.size)
        // Frames 5-19 and 24-37 are vocal; padded by 1 s they are 2 s apart, so they merge into 4-39 s (35 s), which
        // is cut at the first silent 100 ms frame of the 16 kHz stem in 9-34 s: 20.1 s.
        assertEquals(listOf(VocalChunk(4.0, 20.1), VocalChunk(20.1, 39.0)), VocalChunks.of(frames, stem))
    }

    @Test
    fun `the stem made piece by piece is the one made from whole arrays, to the bit`() {
        val rnd = java.util.Random(5)
        val n = 44100 * 3 + 77
        val vocals = Array(2) { FloatArray(n) { (rnd.nextGaussian() * 0.2).toFloat() } }
        val mix = Array(2) { c -> FloatArray(n) { vocals[c][it] + (rnd.nextGaussian() * 0.1).toFloat() } }
        val stem = VocalStem()
        // The mix is read and the vocals arrive in pieces of any size, the vocals behind the mix.
        val reader = stem.listening(ArrayMix(mix, piece = 1_234))
        val l = FloatArray(n)
        val r = FloatArray(n)
        var at = 0
        while (true) at += reader.read(l, r, at, minOf(5_000, n - at)).takeIf { it > 0 } ?: break
        var given = 0
        for (piece in generateSequence(1) { it * 3 + 7 }) {
            val count = minOf(piece, n - given)
            if (count == 0) break
            stem.accept(vocals[0].copyOfRange(given, given + count), vocals[1].copyOfRange(given, given + count), count)
            given += count
        }
        val built = stem.build()
        assertArrayEquals(WholeArrays.stem(vocals), built.stem, 0f)
        val expected = WholeArrays.gateFrames(vocals, mix)
        assertArrayEquals(expected.vocalRms, built.frames.vocalRms, 0.0)
        assertArrayEquals(expected.mixRms, built.frames.mixRms, 0.0)
        assertEquals(3, built.frames.size)
    }

    @Test
    fun `the streamed separation and gate give what the whole arrays gave, vocals, stem, frame share and chunks`() {
        // Voc_FT's real sizes (5.9 s chunks every 4.4 s): several segments with an odd length, and one shorter than a chunk.
        for (seconds in listOf(20.0 + 1.0 / 44100, 9.31, 1.5)) {
            val n = (seconds * 44100).toInt()
            val mix = song(n)
            val whole = WholeArrays.separate(LoudColumns(), MdxSpec.VOC_FT, mix, threads = 2)

            val stem = VocalStem()
            val vocals = CollectedVocals()
            val stats = MdxSeparator(LoudColumns(), MdxSpec.VOC_FT, stftThreads = 2).use { separator ->
                separator.separate(stem.listening(ArrayMix(mix, piece = 4_099)), { l, r, count -> vocals.accept(l, r, count); stem.accept(l, r, count) })
            }
            val built = stem.build()

            assertEquals(n, stats.frames)
            assertArrayEquals("$n frames, left", whole[0], vocals.stereo()[0], 0f)
            assertArrayEquals("$n frames, right", whole[1], vocals.stereo()[1], 0f)
            assertArrayEquals(WholeArrays.stem(whole), built.stem, 0f)
            val frames = WholeArrays.gateFrames(whole, mix)
            assertArrayEquals(frames.vocalRms, built.frames.vocalRms, 0.0)
            assertArrayEquals(frames.mixRms, built.frames.mixRms, 0.0)
            assertEquals(frames.gate(), built.frames.gate())
            assertEquals(VocalChunks.of(frames, WholeArrays.stem(whole)), VocalChunks.of(built.frames, built.stem))
            if (seconds > 10) assertTrue("${frames.gate()}", frames.gate().share in 0.2..0.9)
        }
    }

    /** A band (a 90 Hz tone) under a "voice" (a gliding tone) sung in about half of every 4 s; another per channel. */
    private fun song(n: Int): Array<FloatArray> = Array(2) { c ->
        FloatArray(n) { i ->
            val t = i / 44100.0
            val voice = if ((t % 4.0) < 2.2) 0.3 * sin(2 * PI * (300.0 + 40 * c) * t + 80 * t * t) else 0.0
            (0.05 * sin(2 * PI * 90 * t) + voice).toFloat()
        }
    }

    /** A stand-in for MDX: keeps the spectrum's loud frames (columns), mutes the quiet ones; it depends on what it hears. */
    private class LoudColumns : SegmentModel {
        override val inputShape = longArrayOf(1, 4, MdxSpec.VOC_FT.dimF.toLong(), MdxSpec.VOC_FT.dimT.toLong())
        override val input: java.nio.FloatBuffer = java.nio.FloatBuffer.allocate(4 * MdxSpec.VOC_FT.dimF * MdxSpec.VOC_FT.dimT)
        private val output = java.nio.FloatBuffer.allocate(input.capacity())

        override fun run(): java.nio.FloatBuffer {
            val rows = 4 * MdxSpec.VOC_FT.dimF
            val frames = MdxSpec.VOC_FT.dimT
            for (t in 0 until frames) {
                var energy = 0.0
                for (row in 0 until rows) energy += input.get(row * frames + t).toDouble().let { it * it }
                val gain = if (energy > 1e5) 0.6f else 0.02f
                for (row in 0 until rows) output.put(row * frames + t, input.get(row * frames + t) * gain)
            }
            return output
        }
    }
}
