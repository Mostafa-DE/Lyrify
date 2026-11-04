package app.lyrify.ai

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Locale

/** The Whisper tokenizer's files, bundled in the app's assets (`whisper/`): openai/whisper-large-v3-turbo's own. */
class WhisperAssets(val vocabJson: String, val addedTokensJson: String, val generationConfigJson: String)

/**
 * The engine's stages on the phone, as lyrify-npu-spike's full stage ran them: MediaCodec decoding, UVR-MDX-NET-Voc_FT
 * on the NPU (STFT and iSTFT on [threads] CPU threads), AI Hub's Whisper encoder and decoder on the NPU (log-mel, greedy
 * decoding and the tokenizer on the CPU), and wav2vec2 on the CPU for timing known lyrics.
 *
 * The Whisper export is whichever the phone's chip got (w8a16, or float): its I/O contract is read from the zip's own
 * metadata.json ([appContract]) and every session is checked against it.
 *
 * Sessions are created on first use and stay resident until [close]: one night's run keeps the QNN EP, the MDX, encoder
 * and decoder sessions (about 600-700 MB at peak on the phone, measured) and, once a song is timed, wav2vec2's. A model
 * that does not load throws [ModelLoadException] naming it; a QNN EP that does not register, or ONNX Runtime internals
 * the w8a16 export needs that are not there ([OrtInternalsException]), throw [NpuUnavailableException]. Not thread safe:
 * one song at a time.
 */
class NpuStages(
    private val models: AiModels,
    /** `ApplicationInfo.nativeLibraryDir`: the QNN plugin and the Hexagon libraries, extracted on install. */
    private val nativeLibDir: String,
    private val assets: WhisperAssets,
    private val threads: Int,
    /** One line per Whisper window (tokens, stop reason, time) and per session created; no lyrics. */
    private val log: (String) -> Unit = {},
) : AiStages, AutoCloseable {
    private var contract: WhisperContract? = null
    private var qnn: QnnEp? = null
    private var mdx: Mdx? = null
    private var whisper: Whisper? = null
    private var ctc: CtcModel? = null

    override fun decode(file: File): MixSource = MixDecoder(file)

    override fun separateVocals(mix: MixSource, vocals: VocalSink, progress: Progress) {
        val out = mdx().separator.separate(mix, vocals, progress)
        if (out.nonFinite > 0) log("MDX: ${out.nonFinite} NaN or infinite values zeroed in ${out.segments} segments")
    }

    override fun transcribe(stem: FloatArray, chunk: VocalChunk): List<Segment> {
        val w = whisper()
        val decoded = try {
            w.npu.encode(w.mel.compute(chunk.slice(stem))).use { cross ->
                w.npu.decoderStep(cross).use { w.decoder.decode(it) }
            }
        } catch (e: OrtInternalsException) {
            throw NpuUnavailableException(e)
        }
        val ids = decoded.tokens.map { it.id }
        log(
            "whisper window %.1f-%.1f s: %d tokens (%d timestamps), %s".format(
                Locale.ROOT, chunk.start, chunk.end, ids.size, ids.count(WhisperTokenizer::isTimestamp), decoded.stopReason,
            ),
        )
        return segments(ids, w.tokenizer, chunk.start).map { it.within(chunk) }
    }

    override fun emissions(stem: FloatArray, progress: Progress): Emissions {
        val model = ctc ?: loading(models.wav2vec2) { CtcModel(models.wav2vec2, threads) }.also { ctc = it }
        return Ctc.wholeSong(stem, progress, model::emissions)
    }

    /** Releases every session. The QNN EP library stays registered for the process ([OnceRegistration]). */
    override fun close() {
        listOfNotNull(whisper, mdx, ctc).forEach { runCatching { it.close() } }
        whisper = null
        mdx = null
        ctc = null
        qnn = null
    }

    /** The Whisper export's I/O contract, from its metadata.json; one the app cannot run fails as that model. */
    private fun contract(): WhisperContract = contract ?: loading(models.whisper) {
        appContract(models.metadata.readText())
    }.also { contract = it }

    /**
     * The QNN EP, registered on first use. Before it and any session: a w8a16 export needs ONNX Runtime internals for its
     * uint16 tensors ([IoTensors]), so a build without them stops here, before a song is separated.
     */
    private fun qnn(): QnnEp = qnn ?: run {
        val needsUint16 = contract().needsUint16
        try {
            if (needsUint16) IoTensors.requireUint16()
            QnnEp.register(nativeLibDir).also { qnn = it }
        } catch (e: Exception) {
            throw NpuUnavailableException(e)
        }
    }

    private fun mdx(): Mdx = mdx ?: run {
        val ep = qnn()
        timed("MDX") { loading(models.mdx) { Mdx(ep, models.mdx, threads) } }.also { mdx = it }
    }

    private fun whisper(): Whisper = whisper ?: run {
        val ep = qnn()
        val contract = contract()
        val tokenizer = WhisperTokenizer.fromJson(assets.vocabJson, assets.addedTokensJson)
        val suppression = Suppression.fromGenerationConfig(assets.generationConfigJson)
        timed("Whisper ${contract.modelId} ${contract.precision}") {
            loading(models.whisper) { Whisper(ep, models, contract, tokenizer, suppression) }
        }.also { whisper = it }
    }

    private inline fun <T> timed(what: String, create: () -> T): T {
        val start = System.nanoTime()
        return create().also { log("$what sessions created in ${(System.nanoTime() - start) / 1_000_000} ms") }
    }

    /** [load] of [model]; whatever it throws (ONNX Runtime's or QNN's error, a model not as expected) is [loadFailure]. */
    private inline fun <T> loading(model: File, load: () -> T): T = try {
        load()
    } catch (e: Exception) {
        throw loadFailure(model, e)
    }

    /**
     * The MDX session on the NPU with the separation stage's options (spike README "QNN EP options"): graph
     * finalization mode 3 (about 30 s to create, once a night) and the fp16 overflow clamp of plugin 2.5.0+.
     */
    private class Mdx(qnn: QnnEp, model: File, threads: Int) : AutoCloseable {
        private val session: OrtSession = qnn.createSession(
            model,
            qnn.providerOptions(mapOf("htp_graph_finalization_optimization_mode" to "3", "enable_htp_fp16_clamp_overflow" to "1")),
            MdxSpec.VOC_FT.freeDimensionOverrides,
        )
        private val segmentModel: OrtSegmentModel
        val separator: MdxSeparator

        init {
            try {
                val spec = MdxSpec.VOC_FT
                val input = specs(session.inputInfo).firstOrNull { it.name == spec.inputName }
                val output = specs(session.outputInfo).firstOrNull { it.name == spec.outputName }
                val shape = listOf(1L, 4L, spec.dimF.toLong(), spec.dimT.toLong())
                require(input != null && output != null && input.dtype == "FLOAT" && output.dtype == "FLOAT") {
                    "unexpected MDX model I/O: ${specs(session.inputInfo)} -> ${specs(session.outputInfo)}"
                }
                require(input.shape.size == 4 && input.shape.indices.all { input.shape[it] == shape[it] || input.shape[it] < 0 }) {
                    "MDX input ${input.shape}, expected $shape"
                }
                segmentModel = OrtSegmentModel(session, spec.inputName, shape.toLongArray(), spec.outputName)
                separator = MdxSeparator(segmentModel, spec, threads)
            } catch (e: Exception) {
                session.close()
                throw e
            }
        }

        override fun close() {
            runCatching { separator.close() }
            runCatching { segmentModel.close() }
            session.close()
        }
    }

    /** The resident encoder and decoder sessions, checked against the export's I/O [contract], with the CPU side. */
    private class Whisper(
        qnn: QnnEp,
        models: AiModels,
        contract: WhisperContract,
        val tokenizer: WhisperTokenizer,
        suppression: Suppression,
    ) : AutoCloseable {
        private val encoder: OrtSession = contracted(qnn, models.encoder, contract.encoderInputs, contract.encoderOutputs)
        private val decoderSession: OrtSession
        val npu: WhisperNpu

        init {
            try {
                decoderSession = contracted(qnn, models.decoder, contract.decoderInputs, contract.decoderOutputs)
            } catch (e: Exception) {
                encoder.close()
                throw e
            }
            npu = try {
                WhisperNpu(contract, encoder, decoderSession)
            } catch (e: Exception) {
                decoderSession.close()
                encoder.close()
                throw e
            }
        }

        val mel = LogMel(contract.nMels)
        val decoder = GreedyDecoder(suppression)

        override fun close() {
            runCatching { npu.close() }
            runCatching { encoder.close() }
            decoderSession.close()
        }

        private companion object {
            fun contracted(qnn: QnnEp, model: File, inputs: List<TensorSpec>, outputs: List<TensorSpec>): OrtSession {
                val session = qnn.createSession(model, qnn.providerOptions())
                val problems = WhisperContract.mismatches(inputs, specs(session.inputInfo)) + WhisperContract.mismatches(outputs, specs(session.outputInfo))
                if (problems.isNotEmpty()) {
                    session.close()
                    error("${model.name} I/O differs from its metadata.json: $problems")
                }
                return session
            }
        }
    }
}

/** One MDX segment through ONNX Runtime: a direct input buffer shared with one tensor, refilled for every run. */
private class OrtSegmentModel(
    private val session: OrtSession,
    private val inputName: String,
    override val inputShape: LongArray,
    private val outputName: String,
) : SegmentModel, AutoCloseable {
    override val input: FloatBuffer = ByteBuffer
        .allocateDirect(inputShape.fold(1L) { a, b -> a * b }.toInt() * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private val tensor = OnnxTensor.createTensor(Ort.env, input, inputShape)

    override fun run(): FloatBuffer =
        session.run(mapOf(inputName to tensor), setOf(outputName)).use { (it.get(0) as OnnxTensor).floatBuffer }

    override fun close() = tensor.close()
}

/**
 * CTC emissions from Xenova/wav2vec2-base-960h `onnx/model_q4.onnx` (revision a19f851b), on the CPU: input
 * `input_values` (batch, samples) float32 at 16 kHz, any length, output `logits` (batch, frames, 32) float32. Both are
 * bound by those names, else the model's first input and output. A float16 output is read as well (ONNX Runtime
 * converts it); the copy is sized by the output's shape.
 *
 * q4 (90 MB instead of fp32's 378 MB): only the linear layers are 4-bit (74 `com.microsoft:MatMulNBits` nodes, block 32,
 * no zero points, computed in float32); the 8 Convs stay fp32. The bench's model size test (2026-11-04, part 1) checked
 * that every op has an ONNX Runtime 1.30.0 CPU kernel on arm64, and that the timing method accepts the same songs with
 * it as with fp32. Not the fp16 file: that graph computes in float16, and on arm64 ONNX Runtime runs its 16-group
 * positional Conv in `FusedConvFp16`, which pre-packs the weights past its buffer (the native crash on the phone) and
 * gives every group the first group's bias (microsoft/onnxruntime#33598, in no release up to 1.31.1).
 */
private class CtcModel(model: File, threads: Int) : AutoCloseable {
    private val options = Ort.cpuSessionOptions(threads)
    private val session: OrtSession = try {
        Ort.env.createSession(model.path, options)
    } catch (e: Exception) {
        options.close()
        throw e
    }
    private val inputName: String = bind(session.inputNames, INPUT)
    private val outputName: String = bind(session.outputNames, OUTPUT)

    init {
        val length = (session.inputInfo.getValue(inputName).info as? TensorInfo)?.shape?.lastOrNull()
        if (length != null && length >= 0) fail("the wav2vec2 model takes a fixed length ($length); a dynamic one is needed")
    }

    /** The log-probabilities of one window of the stem, normalised over the window first. */
    fun emissions(window: FloatArray): Emissions {
        val x = Ctc.normalizeAudio(window)
        return OnnxTensor.createTensor(Ort.env, FloatBuffer.wrap(x), longArrayOf(1, x.size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor), setOf(outputName)).use { result ->
                val logits = result.get(0) as OnnxTensor
                val info = logits.info as TensorInfo
                val shape = info.shape
                check(shape.size == 3 && shape[0] == 1L && shape[2] == Ctc.CLASSES.toLong() && shape[1] in 0..x.size.toLong()) {
                    "unexpected wav2vec2 output ${shape.contentToString()}"
                }
                check(info.type == OnnxJavaType.FLOAT || info.type == OnnxJavaType.FLOAT16) { "unexpected wav2vec2 output type ${info.type}" }
                val values = shape[1].toInt() * Ctc.CLASSES
                val buffer = checkNotNull(logits.floatBuffer) { "no float view of the wav2vec2 output" }
                check(buffer.remaining() >= values) { "wav2vec2 output holds ${buffer.remaining()} values, its shape $values" }
                Emissions.fromLogits(FloatArray(values).also { buffer.get(it) }, Ctc.CLASSES)
            }
        }
    }

    /** [wanted] when the model has it, else its first name. */
    private fun bind(names: Set<String>, wanted: String): String =
        if (wanted in names) wanted else names.firstOrNull() ?: fail("the wav2vec2 model has no $wanted or other name to bind")

    /** Closes the model and throws: a model file that is not what the engine expects. */
    private fun fail(problem: String): Nothing {
        close()
        throw IllegalArgumentException(problem)
    }

    override fun close() {
        try {
            session.close()
        } finally {
            options.close()
        }
    }

    private companion object {
        const val INPUT = "input_values"
        const val OUTPUT = "logits"
    }
}

/**
 * The I/O contract of the Whisper export in [metadata] (its zip's metadata.json, [WhisperContract.fromMetadata]), when
 * the app can run it: the app bundles Whisper-Large-V3-Turbo's tokenizer, makes 128-bin mels of 3000 frames and decodes
 * up to 200 tokens, so an export of other sizes (another Whisper) is refused like any model that is not as expected.
 */
internal fun appContract(metadata: String): WhisperContract {
    val c = WhisperContract.fromMetadata(metadata)
    require(c.vocab == WhisperTokenizer.VOCAB_SIZE && c.nMels == LogMel.N_MELS && c.nFrames == LogMel.N_FRAMES && c.maskLen == GreedyDecoder.MAX_LENGTH) {
        "${c.modelId} has ${c.vocab} tokens, ${c.nMels} x ${c.nFrames} mels and a ${c.maskLen}-token cap; the app runs " +
            "${WhisperTokenizer.VOCAB_SIZE}, ${LogMel.N_MELS} x ${LogMel.N_FRAMES} and ${GreedyDecoder.MAX_LENGTH}"
    }
    return c
}

/**
 * What [e], thrown while loading [model], means: ONNX Runtime internals that are not as this build expects
 * ([OrtInternalsException], [IoTensors]) are the phone's NPU path, the same on every model and every night
 * ([NpuUnavailableException]: nothing is deleted, the AI reports the NPU unavailable); anything else is that model's
 * ([ModelLoadException]: it is deleted and downloaded again once).
 */
internal fun loadFailure(model: File, e: Exception): Exception =
    if (e is OrtInternalsException) NpuUnavailableException(e) else ModelLoadException(model, e)
