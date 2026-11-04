package app.lyrify.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Golden fixtures exported from lyrify-bench by `scripts/export_kotlin_fixtures.py` (src/test/resources/ai): the
 * bench's Python reference run on real bench data. The Kotlin ports must reproduce them.
 */
object Fixtures {
    fun load(name: String): JsonObject {
        val url = checkNotNull(Fixtures::class.java.getResource("/ai/$name")) { "missing fixture $name" }
        return Json.parseToJsonElement(url.readText()).jsonObject
    }

    fun songs(name: String): List<JsonObject> = load(name).getValue("songs").jsonArray.map { it.jsonObject }
}

fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

fun JsonObject.num(key: String): Double = getValue(key).jsonPrimitive.double

fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean

fun JsonObject.doubles(key: String): List<Double> = getValue(key).jsonArray.map { it.jsonPrimitive.double }

fun JsonObject.nullableDoubles(key: String): List<Double?> = getValue(key).jsonArray.map { it.jsonPrimitive.doubleOrNull }

fun JsonObject.ints(key: String): List<Int> = getValue(key).jsonArray.map { it.jsonPrimitive.int }

fun JsonObject.nullableInts(key: String): List<Int?> = getValue(key).jsonArray.map { it.jsonPrimitive.intOrNull }

fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }

fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }

/** Values the bench wrote as the shortest decimal of a float32: read back as that float32, exactly. */
fun JsonObject.float32s(key: String): DoubleArray = getValue(key).jsonArray.map { it.jsonPrimitive.content.toFloat().toDouble() }.toDoubleArray()

fun JsonElement.pairs(): List<Pair<Double, Double>> = jsonArray.map { it.jsonArray[0].jsonPrimitive.double to it.jsonArray[1].jsonPrimitive.double }

/**
 * A mix held whole, read as the decoder gives it: at most [piece] frames per read (a decoder hands out what it has),
 * then 0 at the end. [closed] tells whether the reader closed it.
 */
class ArrayMix(private val left: FloatArray, private val right: FloatArray = left, private val piece: Int = Int.MAX_VALUE) : MixSource {
    private var at = 0
    var closed = false
        private set

    constructor(stereo: Array<FloatArray>, piece: Int = Int.MAX_VALUE) : this(stereo[0], stereo[1], piece)

    override val expectedFrames: Int get() = left.size

    override fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int {
        val n = minOf(count, piece, this.left.size - at)
        this.left.copyInto(left, offset, at, at + n)
        this.right.copyInto(right, offset, at, at + n)
        at += n
        return n
    }

    override fun close() {
        closed = true
    }

    /** The largest absolute sample: the peak a spec that normalises needs up front. */
    fun peak(): Float = maxOf(left.maxOf { kotlin.math.abs(it) }, right.maxOf { kotlin.math.abs(it) })
}

/** The vocals a separation gives, gathered back into whole arrays. */
class CollectedVocals : VocalSink {
    private val left = Floats()
    private val right = Floats()
    var calls = 0
        private set

    override fun accept(left: FloatArray, right: FloatArray, count: Int) {
        this.left.addAll(left, 0, count)
        this.right.addAll(right, 0, count)
        calls++
    }

    fun stereo(): Array<FloatArray> = arrayOf(left.data.copyOf(left.size), right.data.copyOf(right.size))
}

/**
 * The engine's separation and gate before they were streamed, kept as the reference the streamed ones must equal: the
 * whole-array MDX demix (MdxSeparator.separate as it was), numpy's channel mean (downmix) and the gate's frame RMS of it
 * (meanFrameRms), and the 16 kHz stem made from the whole vocals.
 */
object WholeArrays {
    fun separate(model: SegmentModel, spec: MdxSpec, mix: Array<FloatArray>, threads: Int = 1): Array<FloatArray> = Stft(spec.nFft, spec.hopLength, threads).use { stft ->
        val values = 4 * spec.dimF * spec.dimT
        val chunkWindow = if (spec.overlap != 0.0) hanning(spec.chunkSize) else FloatArray(spec.chunkSize) { 1f }
        val total = mix[0].size
        var peak = 0f
        for (c in mix) for (v in c) peak = maxOf(peak, kotlin.math.abs(v))
        val max = spec.normalizeMaxPeak
        val scale = if (max != null && peak > max) (max / peak).toFloat() else 1f
        val trim = spec.trim
        val chunk = spec.chunkSize
        val length = trim + total + spec.genSize + trim - total % spec.genSize
        val count = (length + spec.step - 1) / spec.step
        val out = Array(2) { FloatArray(total) }
        val divider = FloatArray(total)
        val left = FloatArray(chunk)
        val right = FloatArray(chunk)
        val specIn = FloatArray(values)
        val specOut = FloatArray(values)
        val wavL = FloatArray(chunk)
        val wavR = FloatArray(chunk)
        for (k in 0 until count) {
            val start = k * spec.step
            val end = minOf(start + chunk, length)
            val first = maxOf(0, start - trim)
            val last = minOf(total, end - trim)
            left.fill(0f)
            right.fill(0f)
            if (last > first) {
                val at = first - start + trim
                for (j in 0 until last - first) {
                    left[at + j] = mix[0][first + j] * scale
                    right[at + j] = mix[1][first + j] * scale
                }
            }
            stft.forwardStereo(left, right, spec.dimF, specIn)
            for (ch in 0 until 4) for (f in 0 until spec.zeroLowBins) specIn.fill(0f, (ch * spec.dimF + f) * spec.dimT, (ch * spec.dimF + f + 1) * spec.dimT)
            val input = model.input
            input.clear()
            input.put(specIn)
            input.rewind()
            val y = model.run()
            y.position(0)
            y.get(specOut)
            for (i in specOut.indices) if (!specOut[i].isFinite()) specOut[i] = 0f
            stft.inverseStereo(specOut, spec.dimF, spec.dimT, wavL, wavR)
            val actual = end - start
            val window = when {
                actual == chunk -> chunkWindow
                spec.overlap != 0.0 -> hanning(actual)
                else -> FloatArray(actual) { 1f }
            }
            for (j in 0 until actual) {
                val o = start + j - trim
                if (o !in 0 until total) continue
                out[0][o] += wavL[j] * window[j]
                out[1][o] += wavR[j] * window[j]
                divider[o] += window[j]
            }
        }
        val gain = if (spec.multiplyOutputByInputPeak) peak else 1f
        for (c in out) for (i in c.indices) c[i] = if (divider[i] > 0f) c[i] / divider[i] * gain else 0f
        out
    }

    /** numpy's `x.mean(0)`: the float sum in channel order, times 1 / channels. */
    fun downmix(channels: Array<FloatArray>): FloatArray {
        val n = channels[0].size
        val out = FloatArray(n)
        for (c in channels) for (i in 0 until n) out[i] += c[i]
        val scale = 1f / channels.size
        for (i in 0 until n) out[i] *= scale
        return out
    }

    /** The gate's frames as the engine made them: frame RMS of the channel mean. */
    fun gateFrames(vocals: Array<FloatArray>, mix: Array<FloatArray>): GateFrames =
        GateFrames(frameRms(downmix(vocals), Rates.MIX, rounded = true), frameRms(downmix(mix), Rates.MIX, rounded = true))

    /** The 16 kHz stem as the engine made it: julius' resample_frac of the channel mean, through PCM 16. */
    fun stem(vocals: Array<FloatArray>): FloatArray = ResampleFrac(Rates.MIX, Rates.SPEECH).resample(downmix(vocals)).also(::pcm16RoundTrip)
}
