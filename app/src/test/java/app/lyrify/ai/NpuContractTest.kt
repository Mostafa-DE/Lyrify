package app.lyrify.ai

import app.lyrify.models.NpuChip
import app.lyrify.models.NpuChips
import app.lyrify.models.WhisperPrecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The export's I/O contract read from AI Hub's metadata.json (ported from lyrify-npu-spike's NpuContractTest). The
 * metadata of every supported chip's export, float and w8a16, is in src/test/resources/npu: each zip's own file, the
 * w8a16 ones fetched from the zips' central directories on 2026-11-04.
 */
class NpuContractTest {
    private fun metadata(precision: WhisperPrecision, chip: NpuChip) =
        File("src/test/resources/npu/aihub_metadata_${precision.id}_${chip.aiHub}.json").readText()

    private val tested = NpuChips.ALL.first { it.tested }
    private val float = WhisperContract.fromMetadata(metadata(WhisperPrecision.FLOAT, tested))
    private val w8a16 = WhisperContract.fromMetadata(metadata(WhisperPrecision.W8A16, tested))

    /** The tensors listed in AI Hub's metadata.json for one model file. */
    private fun aiHub(text: String, model: String, side: String): List<TensorSpec> {
        val tensors = Json.parseToJsonElement(text).jsonObject.getValue("model_files").jsonObject.getValue(model).jsonObject.getValue(side).jsonObject
        return tensors.map { (name, spec) ->
            val o = spec.jsonObject
            TensorSpec(name, o.getValue("dtype").jsonPrimitive.content.uppercase(), o.getValue("shape").jsonArray.map { it.jsonPrimitive.long })
        }
    }

    // The float path: what the app ran before, unchanged

    @Test
    fun `the turbo float contract read from metadata is the one the float path always used, on every chip`() {
        NpuChips.ALL.forEach { chip ->
            val text = metadata(WhisperPrecision.FLOAT, chip)
            val c = WhisperContract.fromMetadata(text)
            assertEquals(chip.aiHub, listOf(4, 20, 64, 1500, 200, 51866, 128, 3000), listOf(c.layers, c.heads, c.headDim, c.audioCtx, c.maskLen, c.vocab, c.nMels, c.nFrames))
            assertEquals("whisper_large_v3_turbo" to "float", c.modelId to c.precision)
            assertEquals(emptyList<String>(), WhisperContract.mismatches(c.encoderInputs, aiHub(text, "encoder.onnx", "inputs")))
            assertEquals(emptyList<String>(), WhisperContract.mismatches(c.encoderOutputs, aiHub(text, "encoder.onnx", "outputs")))
            assertEquals(emptyList<String>(), WhisperContract.mismatches(c.decoderInputs, aiHub(text, "decoder.onnx", "inputs")))
            assertEquals(emptyList<String>(), WhisperContract.mismatches(c.decoderOutputs, aiHub(text, "decoder.onnx", "outputs")))
            assertFalse(c.needsUint16)
        }
        val c = float
        assertEquals(19, c.decoderInputs.size)
        assertEquals(9, c.decoderOutputs.size)
        // The previous hand-written contract: every float float16, ids and positions int32, no quantization.
        assertEquals(TensorSpec("input_features", "FLOAT16", listOf(1, 128, 3000)), c.encoderInputs.single())
        assertEquals(TensorSpec("k_cache_cross_3", "FLOAT16", listOf(20, 1, 64, 1500)), c.encoderOutputs[6])
        assertEquals(TensorSpec("v_cache_self_0_in", "FLOAT16", listOf(20, 1, 199, 64)), c.decoderInputs[4])
        assertEquals(TensorSpec("logits", "FLOAT16", listOf(1, 51866, 1, 1)), c.decoderOutputs.first())
        assertEquals(listOf("INT32", "INT32"), c.decoderInputs.take(2).map { it.dtype })
        assertTrue(c.io.values.all { it.quant == null })
        assertTrue(c.io.values.filter { it.spec.dtype != "INT32" }.all { it.spec.dtype == "FLOAT16" })
        assertEquals(c.io.size, 1 + 8 + 3 + 8 + 1 + 8)
    }

    @Test
    fun `float16 tensors encode and decode as Half, and a float self cache starts at 0 bits`() {
        val io = float.inputFeatures
        for (x in listOf(-1.5f, 0f, 0.123f, 1.75f, -100f)) {
            assertEquals(Half.fromFloat(x).toInt() and 0xFFFF, io.encode(x))
            assertEquals(Half.toFloat(Half.fromFloat(x)), io.decode(io.encode(x)), 0f)
        }
        assertEquals(Half.toFloat(0xC000.toShort()), float.logits.decode(0xC000), 0f)
        assertEquals(0, float.io.getValue("k_cache_self_0_in").encode(0f))
        assertEquals(0xD640, float.attentionMaskIo.encode(WhisperContract.MASK_NEG)) // -100 in float16
        assertEquals(2, float.attentionMaskIo.bytesPerElement)
    }

    // The w8a16 path

    @Test
    fun `Whisper-Large-V3-Turbo w8a16 has the float model's sizes with quantized I O, on every chip`() {
        NpuChips.ALL.forEach { chip ->
            val text = metadata(WhisperPrecision.W8A16, chip)
            val c = WhisperContract.fromMetadata(text)
            assertEquals(chip.aiHub, listOf(4, 20, 64, 1500, 200, 51866, 128, 3000), listOf(c.layers, c.heads, c.headDim, c.audioCtx, c.maskLen, c.vocab, c.nMels, c.nFrames))
            assertEquals("whisper_large_v3_turbo_quantized" to "w8a16", c.modelId to c.precision)
            assertEquals(emptyList<String>(), WhisperContract.mismatches(c.decoderInputs, aiHub(text, "decoder.onnx", "inputs")))
            assertEquals(emptyList<String>(), WhisperContract.mismatches(c.decoderOutputs, aiHub(text, "decoder.onnx", "outputs")))
            assertTrue(c.needsUint16)
            // The same AIMET encodings on every chip.
            assertEquals(chip.aiHub, w8a16.io, c.io)
        }
        val c = w8a16
        assertEquals(TensorIo(TensorSpec("input_features", "UINT16", listOf(1, 128, 3000)), Quant(4.746108970721252e-05f, 31605)), c.inputFeatures)
        assertEquals(Quant(0.0015259021893143654f, 65535), c.attentionMaskIo.quant)
        assertEquals("UINT16", c.attentionMaskIo.spec.dtype)
        assertEquals(TensorIo(TensorSpec("logits", "UINT16", listOf(1, 51866, 1, 1)), Quant(0.0007861469639465213f, 23109)), c.logits)
        val caches = (0 until 4).flatMap { listOf(WhisperContract.kCross(it), WhisperContract.vCross(it), WhisperContract.kSelfIn(it), WhisperContract.vSelfOut(it)) }
        assertEquals(setOf("UINT8"), caches.map { c.io.getValue(it).spec.dtype }.toSet())
        assertEquals(setOf(128), caches.map { c.io.getValue(it).quant?.zeroPoint }.toSet())
        assertEquals(c.io.getValue("v_cache_self_3_in").quant, c.io.getValue("v_cache_self_3_out").quant)
        assertEquals(TensorSpec("k_cache_self_0_in", "UINT8", listOf(20, 1, 64, 199)), c.decoderInputs[3])
        assertEquals(1, c.io.getValue("k_cache_cross_0").bytesPerElement)
    }

    @Test
    fun `quantization rounds half to even and saturates, as ONNX QuantizeLinear`() {
        val q = Quant(0.5f, 10)
        assertEquals(10, q.quantize(0.25f, 255)) // 0.5 -> 0, +10
        assertEquals(12, q.quantize(0.75f, 255)) // 1.5 -> 2
        assertEquals(12, q.quantize(1.25f, 255)) // 2.5 -> 2
        assertEquals(8, q.quantize(-0.75f, 255)) // -1.5 -> -2, +10
        assertEquals(15, q.quantize(2.5f, 255))
        assertEquals(0, q.quantize(-100f, 255))
        assertEquals(255, q.quantize(1000f, 255))
        assertEquals(65535, q.quantize(1e6f, 65535))
        assertEquals(-5f, q.dequantize(0), 0f)
        assertEquals(2.5f, q.dequantize(15), 0f)
    }

    @Test
    fun `the w8a16 mask is 0 and 65535, a uint8 cache starts at its zero point, the mel keeps its range`() {
        // The exports' attention mask: 0 and MASK_NEG land exactly on the ends of uint16.
        val mask = w8a16.attentionMaskIo
        assertEquals(65535, mask.encode(0f))
        assertEquals(0, mask.encode(WhisperContract.MASK_NEG))
        assertEquals(0f, mask.decode(65535), 0f)
        assertEquals(-100f, mask.decode(0), 1e-3f)
        // A uint8 self cache starts at its zero point: what the AI Hub loop's float zeros quantize to.
        (0 until 4).flatMap { listOf(WhisperContract.kSelfIn(it), WhisperContract.vSelfIn(it)) }.forEach {
            assertEquals(it, 128, w8a16.io.getValue(it).encode(0f))
        }
        // Mel: the silence floor -1.5 and a loud 1.4 survive within half a step; above the calibrated range saturates.
        val mel = w8a16.inputFeatures
        val step = 4.746108970721252e-05f
        for (x in listOf(-1.5f, 0f, 0.37f, 1.4f)) assertEquals(x, mel.decode(mel.encode(x)), step / 2)
        assertEquals(65535, mel.encode(3f))
        assertEquals(0, mel.encode(-3f))
    }

    @Test
    fun `w8a16 logits are dequantized before the argmax, which keeps its token`() {
        val logits = w8a16.logits
        assertTrue(logits.decode(40000) > logits.decode(39999))
        assertEquals(0f, logits.decode(23109), 0f) // the zero point is 0
        assertEquals((40000 - 23109) * 0.0007861469639465213f, logits.decode(40000), 1e-6f)
        // The bits the decoder gives back, read as the decoder step does: the argmax of the real values is the
        // argmax of the bits, and below the zero point a logit is negative.
        val bits = intArrayOf(23109, 23000, 41000, 40999, 65535 - 1, 0)
        val buffer = ByteBuffer.allocate(2 * bits.size).order(ByteOrder.nativeOrder())
        bits.forEachIndexed { i, b -> buffer.putShort(2 * i, b.toShort()) }
        val real = logits.read(buffer)
        assertEquals(bits.size, real.size)
        assertEquals(bits.indices.maxBy { bits[it] }, real.indices.maxBy { real[it] })
        assertTrue(real[1] < 0f && real[5] < 0f)
        assertArrayEquals(FloatArray(bits.size) { logits.decode(bits[it]) }, real, 0f)
    }

    @Test
    fun `values are written and read back in each element type, at their byte positions`() {
        val values = floatArrayOf(-100f, -1.5f, 0f, 0.37f, 1.4f)
        // uint16 (mel), uint8 (a cache), float16 (the float export's mel)
        for (io in listOf(w8a16.inputFeatures, w8a16.io.getValue("k_cache_self_0_in"), float.inputFeatures)) {
            val buffer = ByteBuffer.allocateDirect(values.size * io.bytesPerElement).order(ByteOrder.nativeOrder())
            io.write(values, buffer)
            assertEquals(io.spec.name, 0, buffer.position())
            assertArrayEquals(io.spec.name, FloatArray(values.size) { io.decode(io.encode(values[it])) }, io.read(buffer), 0f)
        }
        val uint8 = w8a16.io.getValue("k_cache_cross_0")
        val one = ByteBuffer.allocateDirect(3).order(ByteOrder.nativeOrder())
        uint8.write(floatArrayOf(0f, 1000f, -1000f), one)
        assertEquals(listOf(128, 255, 0), (0 until 3).map { one.get(it).toInt() and 0xFF })
        assertThrows(IllegalArgumentException::class.java) { uint8.write(floatArrayOf(0f, 0f), ByteBuffer.allocateDirect(1)) }
    }

    // What the contract refuses

    @Test
    fun `metadata the app cannot run is refused`() {
        val text = metadata(WhisperPrecision.W8A16, tested)
        fun refused(edit: (String) -> String) {
            val edited = edit(text)
            assertTrue("the edit changed nothing", edited != text)
            assertThrows(IllegalArgumentException::class.java) { WhisperContract.fromMetadata(edited) }
        }
        // A self cache whose _out is quantized unlike its _in could not be fed back as it is.
        refused { it.replace(Regex("(\"k_cache_self_0_out\":\\s*\\{[^}]*\"scale\":\\s*)[0-9.e-]+")) { m -> m.groupValues[1] + "0.5" } }
        // A quantized tensor without its parameters, an element type the app does not convert, a missing tensor.
        refused { it.replace(Regex("\"dtype\": \"uint16\",\\s*\"quantization_parameters\": \\{[^}]*\\}"), "\"dtype\": \"uint16\"") }
        refused { it.replace("\"dtype\": \"uint8\"", "\"dtype\": \"int8\"") }
        refused { it.replace("\"k_cache_self_3_out\"", "\"k_cache_self_4_out\"") }
        assertThrows(IllegalArgumentException::class.java) {
            WhisperContract.fromMetadata(metadata(WhisperPrecision.FLOAT, tested).replace("\"float16\"", "\"float32\""))
        }
    }

    @Test
    fun `the app runs turbo's sizes only, either precision`() {
        assertEquals(w8a16.io, appContract(metadata(WhisperPrecision.W8A16, tested)).io)
        assertEquals(float.io, appContract(metadata(WhisperPrecision.FLOAT, tested)).io)
        // Another Whisper parses as a contract but is not one the app's tokenizer and mel run: Small's vocabulary and mels.
        val text = metadata(WhisperPrecision.W8A16, tested)
        val small = text.replace("51866", "51865").replace("128,\n", "80,\n")
        val contract = WhisperContract.fromMetadata(small)
        assertEquals(51865 to 80, contract.vocab to contract.nMels)
        assertThrows(IllegalArgumentException::class.java) { appContract(small) }
    }

    // Shared helpers, unchanged from the float-only engine

    @Test
    fun `each chip's metadata names its HTP version and the QAIRT build of the QNN runtime in the APK`() {
        NpuChips.ALL.forEach { chip ->
            WhisperPrecision.entries.forEach { precision ->
                val root = Json.parseToJsonElement(metadata(precision, chip)).jsonObject
                val attributes = root.getValue("chipset_attributes").jsonObject
                assertEquals(chip.aiHub, chip.hexagon.toLong(), attributes.getValue("htp_version").jsonPrimitive.long)
                assertEquals(chip.aiHub, "qualcomm-" + chip.aiHub.replace('_', '-'), attributes.getValue("name").jsonPrimitive.content)
                assertEquals(chip.aiHub, "2.50.0.260828221209", root.getValue("tool_versions").jsonObject.getValue("qairt").jsonPrimitive.content)
                assertEquals(chip.aiHub, precision.model, root.getValue("model_id").jsonPrimitive.content)
            }
        }
    }

    @Test
    fun `mismatches name what is missing, unexpected or different`() {
        val expected = listOf(TensorSpec("a", "FLOAT16", listOf(1, 2)), TensorSpec("b", "INT32", listOf(1)))
        val actual = listOf(TensorSpec("a", "FLOAT", listOf(1, 2)), TensorSpec("c", "INT32", listOf(1)))
        assertEquals(listOf("a: FLOAT[1, 2], expected FLOAT16[1, 2]", "missing b", "unexpected c"), WhisperContract.mismatches(expected, actual))
    }

    @Test
    fun `the attention mask opens from the end, one more position per token, as the AI Hub loop`() {
        val m0 = float.attentionMask(0)
        assertEquals(200, m0.size)
        assertEquals(0f, m0[199])
        assertTrue((0 until 199).all { m0[it] == -100f })
        val m5 = float.attentionMask(5)
        assertTrue((194..199).all { m5[it] == 0f })
        assertEquals(-100f, m5[193])
        assertTrue(float.attentionMask(199).all { it == 0f })
        assertThrows(IllegalArgumentException::class.java) { float.attentionMask(200) }
        assertTrue(w8a16.attentionMask(7).contentEquals(float.attentionMask(7)))
        // Encoded for the w8a16 decoder: 65535 for the attended positions, 0 for the rest.
        val bits = w8a16.attentionMask(5).map { w8a16.attentionMaskIo.encode(it) }
        assertEquals(List(194) { 0 } + List(6) { 65535 }, bits)
    }

    @Test
    fun `half precision conversion rounds to nearest even and round-trips every half`() {
        fun bits(f: Float) = Half.fromFloat(f).toInt() and 0xFFFF
        assertEquals(0x0000, bits(0f))
        assertEquals(0x8000, bits(-0f))
        assertEquals(0x3C00, bits(1f))
        assertEquals(0xC000, bits(-2f))
        assertEquals(0xD640, bits(-100f))
        assertEquals(0x7BFF, bits(65504f))
        assertEquals(0x7C00, bits(65520f)) // rounds up to infinity
        assertEquals(0x7C00, bits(Float.POSITIVE_INFINITY))
        assertEquals(0x0001, bits(5.9604645e-8f)) // smallest subnormal
        assertEquals(0x0000, bits(2.9802322e-8f)) // half of it: tie to even (0)
        assertEquals(0x3C00, bits(1f + 1f / 2048)) // tie between 1 and 1 + 2^-10: even (1)
        assertEquals(0x3C02, bits(1f + 3f / 2048)) // tie between 1 + 2^-10 and 1 + 2^-9: even
        assertTrue(Half.toFloat(Half.fromFloat(Float.NaN)).isNaN())
        for (h in 0..0xFFFF) {
            val exp = (h ushr 10) and 0x1F
            if (exp == 0x1F && (h and 0x3FF) != 0) continue // NaN payloads
            val f = Half.toFloat(h.toShort())
            assertEquals("half 0x%04X".format(h), h, Half.fromFloat(f).toInt() and 0xFFFF)
        }
        assertEquals(-1.5f, Half.toFloat(Half.fromFloat(-1.5f)))
        assertEquals(1.4332f, Half.toFloat(Half.fromFloat(1.4332f)), 1e-3f)
    }

    @Test
    fun `ADSP_LIBRARY_PATH puts the app's libraries first, keeping what was there`() {
        val lib = "/data/app/x/lib/arm64"
        assertEquals(
            "$lib;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp",
            QnnEp.adspLibraryPath(lib, null),
        )
        assertEquals("$lib;/odm/lib/rfsa/adsp;/dsp", QnnEp.adspLibraryPath(lib, "/odm/lib/rfsa/adsp;$lib;;/dsp"))
    }

    // Registering the QNN EP library: once per process

    @Test
    fun `the EP library is registered once, later runs reuse it, and a lost registration counts as done`() {
        var registered = false
        val calls = mutableListOf<String>()
        val once = OnceRegistration({ registered }, { path -> calls += path; registered = true })
        once.ensure("/lib/a.so")
        once.ensure("/lib/a.so")
        assertEquals(listOf("/lib/a.so"), calls)

        // Registered already (an earlier attempt's outcome lost): ONNX Runtime refuses again, which is fine.
        val refused = OnceRegistration({ true }, { error("already registered") })
        refused.ensure("/lib/a.so")

        // A real failure is thrown, and the next run tries again.
        var tries = 0
        val failing = OnceRegistration({ false }, { tries++; if (tries == 1) error("dlopen failed") })
        assertThrows(IllegalStateException::class.java) { failing.ensure("/lib/a.so") }
        failing.ensure("/lib/a.so")
        failing.ensure("/lib/a.so")
        assertEquals(2, tries)
    }
}
