package app.lyrify.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.ByteBuffer

/*
 * The I/O contract of AI Hub's Whisper exports, ported from lyrify-npu-spike (npu/NpuContract.kt), which ran the float and
 * the w8a16 exports on the phone with it (model size test, 2026-11-04). The spike's results.json records and its profiling
 * reader are left out.
 */

/** A tensor of the I/O contract: name, ONNX element type name and shape. */
data class TensorSpec(val name: String, val dtype: String, val shape: List<Long>)

/**
 * Linear quantization of a quantized export's I/O tensor, as metadata.json gives it and ONNX's QuantizeLinear and
 * DequantizeLinear define it: real = (q - zeroPoint) x scale, q = saturate(round_half_even(x / scale) + zeroPoint).
 */
data class Quant(val scale: Float, val zeroPoint: Int) {
    /** [x] quantized and saturated to 0..[max] (255 for uint8, 65535 for uint16); x / scale in float, as ONNX Runtime. */
    fun quantize(x: Float, max: Int): Int = (Math.rint((x / scale).toDouble()) + zeroPoint).coerceIn(0.0, max.toDouble()).toInt()

    fun dequantize(q: Int): Float = (q - zeroPoint) * scale
}

/** One tensor of an export's I/O as metadata.json declares it: its spec and, for a uint8 or uint16 one, its quantization. */
data class TensorIo(val spec: TensorSpec, val quant: Quant? = null) {
    /** The element encoding, resolved once: [encode] and [decode] run per element (51,866 logits per decoder token). */
    private val element: Element = when {
        spec.dtype == FLOAT16 -> Element.Float16
        spec.dtype == UINT8 && quant != null -> Element.Quantized(quant, UINT8_MAX)
        spec.dtype == UINT16 && quant != null -> Element.Quantized(quant, UINT16_MAX)
        else -> Element.Other
    }

    /** Bytes per element of a float16, uint8 or uint16 tensor. */
    val bytesPerElement: Int get() = if (spec.dtype == UINT8) 1 else 2

    /** The element bits of [x] in this tensor: float16, or the quantized uint8 or uint16 value. */
    fun encode(x: Float): Int = when (val e = element) {
        Element.Float16 -> Half.fromFloat(x).toInt() and 0xFFFF
        is Element.Quantized -> e.quant.quantize(x, e.max)
        Element.Other -> error("${spec.name}: no float encoding for ${spec.dtype}")
    }

    /** The real value of the element bits [bits] (unsigned). */
    fun decode(bits: Int): Float = when (val e = element) {
        Element.Float16 -> Half.toFloat(bits.toShort())
        is Element.Quantized -> e.quant.dequantize(bits)
        Element.Other -> error("${spec.name}: no float decoding for ${spec.dtype}")
    }

    /** [values] encoded into [into] from its byte 0 (absolute writes: its position stays), one element after the other. */
    fun write(values: FloatArray, into: ByteBuffer) {
        require(into.capacity() >= values.size * bytesPerElement) { "${spec.name}: ${into.capacity()} B for ${values.size} elements" }
        if (bytesPerElement == 1) {
            for (i in values.indices) into.put(i, encode(values[i]).toByte())
        } else {
            for (i in values.indices) into.putShort(2 * i, encode(values[i]).toShort())
        }
    }

    /** The real values of the elements in [from] (a tensor's bytes in native order, from byte 0 to its capacity). */
    fun read(from: ByteBuffer): FloatArray = if (bytesPerElement == 1) {
        FloatArray(from.capacity()) { decode(from.get(it).toInt() and UINT8_MAX) }
    } else {
        FloatArray(from.capacity() / 2) { decode(from.getShort(2 * it).toInt() and UINT16_MAX) }
    }

    private sealed interface Element {
        data object Float16 : Element

        class Quantized(val quant: Quant, val max: Int) : Element

        data object Other : Element
    }

    companion object {
        const val FLOAT16 = "FLOAT16"
        const val UINT8 = "UINT8"
        const val UINT16 = "UINT16"
        const val INT32 = "INT32"
        private const val UINT8_MAX = 0xFF
        private const val UINT16_MAX = 0xFFFF
    }
}

/**
 * The I/O contract of an AI Hub Whisper precompiled_qnn_onnx export (qai-hub-models v0.65.0 templates/hf_whisper), read
 * from the metadata.json of its zip, which the spike's tools/inspect_aihub_model.py found equal to the ONNX files. Every
 * variant has the same structure; the sizes and element types differ:
 *
 * - encoder: `input_features` (1, mel bins, 3000) to `k_cache_cross_{i}` (heads, 1, head dim, 1500) and
 *   `v_cache_cross_{i}` (heads, 1, 1500, head dim) for each decoder layer i;
 * - decoder, one token per call: `input_ids` (1, 1) and `position_ids` (1) int32, `attention_mask` (1, 1, 1, 200), the
 *   self caches `k_cache_self_{i}_in` / `v_cache_self_{i}_in` (199 positions) and the cross caches, to `logits`
 *   (1, vocabulary, 1, 1) and the self caches `_out`.
 *
 * The float export (Whisper-Large-V3-Turbo) uses float16 everywhere; the w8a16 one (Whisper-Large-V3-Turbo-Quantized)
 * takes and gives quantized values (uint16 mel, mask and logits, uint8 caches) with the scale and zero point of
 * metadata.json. [fromMetadata] refuses any other shape or type, and any cache whose encoder output and decoder input
 * (cross) or `_out` and `_in` (self) are quantized differently: the app passes those tensors on as they are.
 */
class WhisperContract private constructor(
    /** metadata.json `model_id` and `precision`, e.g. whisper_large_v3_turbo_quantized, w8a16. */
    val modelId: String,
    val precision: String,
    val layers: Int,
    val heads: Int,
    val headDim: Int,
    val audioCtx: Int,
    /** attention_mask length: prompt plus sampled tokens per window (the token cap, 200). */
    val maskLen: Int,
    val vocab: Int,
    val nMels: Int,
    val nFrames: Int,
    /** Every tensor of both models by name, as metadata.json declares it. */
    val io: Map<String, TensorIo>,
) {
    val selfLen: Int get() = maskLen - 1
    val inputFeatures: TensorIo get() = io.getValue(INPUT_FEATURES)
    val attentionMaskIo: TensorIo get() = io.getValue(ATTENTION_MASK)
    val logits: TensorIo get() = io.getValue(LOGITS)

    /** A uint16 tensor is part of the I/O (the w8a16 export's mel, mask and logits): only [IoTensors] makes one. */
    val needsUint16: Boolean get() = io.values.any { it.spec.dtype == TensorIo.UINT16 }

    private val kCrossShape = listOf(heads.toLong(), 1, headDim.toLong(), audioCtx.toLong())
    private val vCrossShape = listOf(heads.toLong(), 1, audioCtx.toLong(), headDim.toLong())
    private val kSelfShape = listOf(heads.toLong(), 1, headDim.toLong(), selfLen.toLong())
    private val vSelfShape = listOf(heads.toLong(), 1, selfLen.toLong(), headDim.toLong())

    /** A spec of the structure with the element type metadata.json gives that name ("MISSING" when it has none). */
    private fun spec(name: String, shape: List<Long>) = TensorSpec(name, io[name]?.spec?.dtype ?: "MISSING", shape)

    val encoderInputs = listOf(spec(INPUT_FEATURES, listOf(1, nMels.toLong(), nFrames.toLong())))
    val encoderOutputs = (0 until layers).flatMap { listOf(spec(kCross(it), kCrossShape), spec(vCross(it), vCrossShape)) }
    val decoderInputs = listOf(
        spec(INPUT_IDS, listOf(1, 1)),
        spec(POSITION_IDS, listOf(1)),
        spec(ATTENTION_MASK, listOf(1, 1, 1, maskLen.toLong())),
    ) + (0 until layers).flatMap { listOf(spec(kSelfIn(it), kSelfShape), spec(vSelfIn(it), vSelfShape)) } + encoderOutputs
    val decoderOutputs = listOf(spec(LOGITS, listOf(1, vocab.toLong(), 1, 1))) +
        (0 until layers).flatMap { listOf(spec(kSelfOut(it), kSelfShape), spec(vSelfOut(it), vSelfShape)) }

    /**
     * attention_mask for the token fed at [position] (0-based), as the AI Hub loop builds it: the newest entry is
     * at the end, so positions maskLen - 1 - position .. maskLen - 1 are 0 (the current token and the [position]
     * cached ones) and the rest is [MASK_NEG].
     */
    fun attentionMask(position: Int): FloatArray {
        require(position in 0 until maskLen) { "position $position is outside the export's $maskLen" }
        return FloatArray(maskLen) { if (it >= maskLen - 1 - position) 0f else MASK_NEG }
    }

    companion object {
        /** AI Hub's MASK_NEG: masked positions of attention_mask hold -100, attended ones 0. */
        const val MASK_NEG = -100f
        const val INPUT_FEATURES = "input_features"
        const val INPUT_IDS = "input_ids"
        const val POSITION_IDS = "position_ids"
        const val ATTENTION_MASK = "attention_mask"
        const val LOGITS = "logits"

        fun kCross(i: Int) = "k_cache_cross_$i"
        fun vCross(i: Int) = "v_cache_cross_$i"
        fun kSelfIn(i: Int) = "k_cache_self_${i}_in"
        fun vSelfIn(i: Int) = "v_cache_self_${i}_in"
        fun kSelfOut(i: Int) = "k_cache_self_${i}_out"
        fun vSelfOut(i: Int) = "v_cache_self_${i}_out"

        /**
         * Differences between what a session reports and [expected], by name (order is not part of the contract);
         * empty when they match.
         */
        fun mismatches(expected: List<TensorSpec>, actual: List<TensorSpec>): List<String> {
            val byName = actual.associateBy { it.name }
            val out = ArrayList<String>()
            for (e in expected) {
                val a = byName[e.name]
                when {
                    a == null -> out += "missing ${e.name}"
                    a.dtype != e.dtype || a.shape != e.shape -> out += "${e.name}: ${a.dtype}${a.shape}, expected ${e.dtype}${e.shape}"
                }
            }
            actual.filter { a -> expected.none { it.name == a.name } }.forEach { out += "unexpected ${it.name}" }
            return out
        }

        /** Reads an export's metadata.json; throws IllegalArgumentException when it is not a Whisper export this app runs. */
        fun fromMetadata(text: String): WhisperContract {
            val root = Json.parseToJsonElement(text).jsonObject
            val files = root.getValue("model_files").jsonObject
            fun side(model: String, side: String): Map<String, TensorIo> =
                files.getValue(model).jsonObject.getValue(side).jsonObject.mapValues { (name, value) ->
                    val o = value.jsonObject
                    val q = o["quantization_parameters"]?.jsonObject?.let {
                        Quant(it.getValue("scale").jsonPrimitive.float, it.getValue("zero_point").jsonPrimitive.int)
                    }
                    TensorIo(TensorSpec(name, o.getValue("dtype").jsonPrimitive.content.uppercase(), o.getValue("shape").jsonArray.map { it.jsonPrimitive.long }), q)
                }
            val encIn = side("encoder.onnx", "inputs")
            val encOut = side("encoder.onnx", "outputs")
            val decIn = side("decoder.onnx", "inputs")
            val decOut = side("decoder.onnx", "outputs")
            fun shape(map: Map<String, TensorIo>, name: String, rank: Int): List<Int> {
                val s = requireNotNull(map[name]) { "metadata.json has no $name" }.spec.shape
                require(s.size == rank) { "$name has shape $s, expected rank $rank" }
                return s.map { it.toInt() }
            }
            val features = shape(encIn, INPUT_FEATURES, 3)
            val k0 = shape(encOut, kCross(0), 4)
            val mask = shape(decIn, ATTENTION_MASK, 4)
            val logits = shape(decOut, LOGITS, 4)
            val contract = WhisperContract(
                modelId = root["model_id"]?.jsonPrimitive?.content ?: "?",
                precision = root["precision"]?.jsonPrimitive?.content ?: "?",
                layers = encOut.keys.count { it.startsWith("k_cache_cross_") },
                heads = k0[0],
                headDim = k0[2],
                audioCtx = k0[3],
                maskLen = mask[3],
                vocab = logits[1],
                nMels = features[1],
                nFrames = features[2],
                io = encIn + encOut + decIn + decOut,
            )
            val problems = mismatches(contract.encoderInputs, encIn.values.map { it.spec }) +
                mismatches(contract.encoderOutputs, encOut.values.map { it.spec }) +
                mismatches(contract.decoderInputs, decIn.values.map { it.spec }) +
                mismatches(contract.decoderOutputs, decOut.values.map { it.spec })
            require(problems.isEmpty()) { "metadata.json is not the Whisper export structure: $problems" }
            for ((name, t) in contract.io) {
                val ok = when (name) {
                    INPUT_IDS, POSITION_IDS -> t.spec.dtype == TensorIo.INT32 && t.quant == null
                    else -> (t.spec.dtype == TensorIo.FLOAT16 && t.quant == null) ||
                        (t.spec.dtype in setOf(TensorIo.UINT8, TensorIo.UINT16) && t.quant != null && t.quant.scale > 0f)
                }
                require(ok) { "$name: ${t.spec.dtype}${t.quant?.let { " $it" } ?: ""} is not an element type the app runs" }
            }
            for (i in 0 until contract.layers) {
                for (name in listOf(kCross(i), vCross(i))) {
                    require(encOut.getValue(name) == decIn.getValue(name)) { "$name: the encoder output and decoder input differ" }
                }
                for ((input, output) in listOf(kSelfIn(i) to kSelfOut(i), vSelfIn(i) to vSelfOut(i))) {
                    val a = decIn.getValue(input)
                    val b = decOut.getValue(output)
                    require(a.spec.dtype == b.spec.dtype && a.quant == b.quant) { "$output is not quantized like $input" }
                }
            }
            return contract
        }
    }
}

/** IEEE 754 half precision, for the float export's float16 tensors. */
object Half {
    /** Rounds to the nearest half (ties to even), with overflow to infinity and gradual underflow. */
    fun fromFloat(value: Float): Short {
        val bits = java.lang.Float.floatToRawIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exp = (bits ushr 23) and 0xFF
        var mant = bits and 0x7FFFFF
        if (exp == 0xFF) return (sign or 0x7C00 or (if (mant != 0) 0x200 else 0)).toShort()
        val e = exp - 127 + 15
        if (e >= 0x1F) return (sign or 0x7C00).toShort()
        if (e <= 0) {
            if (e < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - e
            val half = mant ushr shift
            val rem = mant and ((1 shl shift) - 1)
            val mid = 1 shl (shift - 1)
            val rounded = if (rem > mid || (rem == mid && (half and 1) == 1)) half + 1 else half
            return (sign or rounded).toShort()
        }
        val half = (e shl 10) or (mant ushr 13)
        val rem = mant and 0x1FFF
        val rounded = if (rem > 0x1000 || (rem == 0x1000 && (half and 1) == 1)) half + 1 else half
        return (sign or rounded).toShort() // a carry into the exponent is correct, up to infinity
    }

    fun toFloat(half: Short): Float {
        val h = half.toInt() and 0xFFFF
        val sign = (h and 0x8000) shl 16
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when {
            exp == 0 && mant == 0 -> sign
            exp == 0 -> { // subnormal: normalise
                var m = mant
                var e = -14
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e--
                }
                sign or ((e + 127) shl 23) or ((m and 0x3FF) shl 13)
            }
            exp == 0x1F -> sign or 0x7F800000 or (mant shl 13)
            else -> sign or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }
}
