package app.lyrify.ai

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import ai.onnxruntime.OrtHardwareDevice
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.system.Os
import android.util.Log
import app.lyrify.LOG_TAG
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The NPU side of the engine, ported from lyrify-npu-spike (npu/WhisperNpu.kt), which measured it on the phone: the QNN
 * execution provider plugin on the HTP, and AI Hub's Whisper encoder and decoder run with their I/O contract
 * ([WhisperContract], NpuContract.kt), float or w8a16. The spike's probes, profiling and phased mode are left out: the
 * app keeps its sessions resident.
 */

/** The process-wide ONNX Runtime environment, and the session options of the models that run on the CPU. */
object Ort {
    val env: OrtEnvironment by lazy {
        OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING, "Lyrify").also {
            // onnxruntime-android ships 1DS telemetry. The manifest removes its initializer; this switches the native
            // events off as well. Never worth failing a run over.
            runCatching { it.setTelemetry(false) }.onFailure { e -> Log.w(LOG_TAG, "could not disable ONNX Runtime telemetry", e) }
        }
    }

    /** CPU execution provider, [threads] intra-op threads, one inter-op thread, memory arena off (as the CPU spike). */
    fun cpuSessionOptions(threads: Int): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setIntraOpNumThreads(threads)
            options.setInterOpNumThreads(1)
            options.setCPUArenaAllocator(false)
            options.addCPU(false)
        } catch (e: Exception) {
            options.close()
            throw e
        }
        return options
    }
}

/**
 * The plugin QNN execution provider (com.qualcomm.qti:onnxruntime-android-qnn): registered into ONNX Runtime by the
 * path of libonnxruntime_providers_qnn.so, then selected through its NPU (HTP) device.
 */
class QnnEp private constructor(private val npuDevices: List<OrtEpDevice>, private val nativeLibDir: String) {
    /**
     * Provider options: AI Hub's QNN EP defaults (qai_hub_apps_utils.onnxruntime_qnn) in burst mode, the HTP backend
     * given by absolute path so this APK's QAIRT 2.50 libQnnHtp.so is the one loaded. [extra] adds or overrides options
     * (the separation model's).
     */
    fun providerOptions(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("backend_path", File(nativeLibDir, HTP_BACKEND).path)
        put("htp_performance_mode", "burst")
        put("enable_htp_fp16_precision", "1")
        put("htp_graph_finalization_optimization_mode", "3")
        put("offload_graph_io_quantization", "1")
        putAll(extra)
    }

    /**
     * A session on the QNN EP's NPU device. ONNX Runtime refuses to create it when any node would run on the CPU
     * (`session.disable_cpu_ep_fallback`), so a model either runs whole on the NPU or not at all. [symbolicDims] fixes
     * named free dimensions (a model's dynamic batch), since the HTP needs static shapes.
     */
    fun createSession(model: File, providerOptions: Map<String, String>, symbolicDims: Map<String, Long> = emptyMap()): OrtSession {
        OrtSession.SessionOptions().use { so ->
            symbolicDims.forEach { (name, value) -> so.setSymbolicDimensionValue(name, value) }
            so.addConfigEntry(DISABLE_CPU_FALLBACK, "1")
            so.addExecutionProvider(npuDevices, providerOptions)
            // By path, not bytes: an EPContext node points at its context binary relative to the model file.
            return Ort.env.createSession(model.path, so)
        }
    }

    companion object {
        const val NAME = "QNNExecutionProvider"
        const val PLUGIN_LIBRARY = "libonnxruntime_providers_qnn.so"
        const val HTP_BACKEND = "libQnnHtp.so"
        const val DISABLE_CPU_FALLBACK = "session.disable_cpu_ep_fallback"
        const val ADSP_LIBRARY_PATH = "ADSP_LIBRARY_PATH"
        private val SYSTEM_DSP_DIRS = listOf("/vendor/lib/rfsa/adsp", "/vendor/dsp/cdsp", "/system/lib/rfsa/adsp", "/system/vendor/lib/rfsa/adsp", "/dsp")

        /** The value [register] sets ADSP_LIBRARY_PATH to: [nativeLibDir] first, then [old] or the system folders. */
        fun adspLibraryPath(nativeLibDir: String, old: String?): String {
            val rest = if (old.isNullOrBlank()) SYSTEM_DSP_DIRS else old.split(';').filter { it.isNotBlank() && it != nativeLibDir }
            return (listOf(nativeLibDir) + rest).joinToString(";")
        }

        /** The plugin's registration in this process's ONNX Runtime environment: once, kept for the process's life. */
        private val registration = OnceRegistration(
            isRegistered = { Ort.env.epDevices.any { it.epName == NAME } },
            register = { path -> Ort.env.registerExecutionProviderLibrary(NAME, path) },
        )

        /**
         * Prepends [nativeLibDir] to ADSP_LIBRARY_PATH (';'-separated), so FastRPC finds the Hexagon skel library among
         * the APK's extracted libraries (QNN error 1008 otherwise), registers the plugin once per process
         * ([OnceRegistration]) and finds its NPU devices. Throws when the library or an NPU device is missing.
         */
        fun register(nativeLibDir: String): QnnEp {
            Os.setenv(ADSP_LIBRARY_PATH, adspLibraryPath(nativeLibDir, Os.getenv(ADSP_LIBRARY_PATH)), true)
            val library = File(nativeLibDir, PLUGIN_LIBRARY)
            require(library.isFile) { "missing $PLUGIN_LIBRARY in the app's native libraries" }
            registration.ensure(library.path)
            val npu = Ort.env.epDevices.filter { it.epName == NAME && it.device.type == OrtHardwareDevice.OrtHardwareDeviceType.NPU }
            check(npu.isNotEmpty()) { "the QNN EP registered but offers no NPU device" }
            return QnnEp(npu, nativeLibDir)
        }
    }
}

/**
 * An execution provider library registered once per process. ONNX Runtime refuses a second registration under the same
 * name ("already registered"), and its docs allow unregistering only once every session using the library is gone, so
 * the library is registered on first use and never unregistered: the sessions come and go, the registration stays.
 * [ensure] is idempotent and thread safe; a registration that throws while the library is in fact registered (by an
 * earlier attempt whose outcome was lost) counts as done, any other failure is thrown and the next call tries again.
 */
class OnceRegistration(private val isRegistered: () -> Boolean, private val register: (String) -> Unit) {
    private var done = false

    @Synchronized
    fun ensure(libraryPath: String) {
        if (done) return
        if (!isRegistered()) {
            try {
                register(libraryPath)
            } catch (e: Exception) {
                if (!isRegistered()) throw e
            }
        }
        done = true
    }
}

/** A session's inputs or outputs as contract specs. */
fun specs(info: Map<String, NodeInfo>): List<TensorSpec> = info.values.map { node ->
    val t = node.info as? TensorInfo
    TensorSpec(
        name = node.name,
        dtype = t?.onnxType?.name?.removePrefix("ONNX_TENSOR_ELEMENT_DATA_TYPE_") ?: node.info.toString(),
        shape = t?.shape?.toList() ?: emptyList(),
    )
}

/**
 * ONNX Runtime's private members that [IoTensors] calls are not what this build expects: one is missing, of another
 * type, or refuses the call. A uint16 tensor cannot be made, so the w8a16 Whisper cannot run: the engine reports the
 * NPU as unavailable ([NpuUnavailableException]) instead of failing songs or crashing.
 */
class OrtInternalsException(cause: Throwable) : Exception("ONNX Runtime's internals are not as expected: ${cause.javaClass.simpleName}: ${cause.message}", cause)

/**
 * Tensors over direct buffers (native order) in the element types of the exports' I/O. ONNX Runtime's Java API cannot
 * make a uint16 tensor: OnnxJavaType stops at int16 and a ShortBuffer becomes an int16 tensor, which a uint16 input
 * refuses. The w8a16 export takes uint16 mel and attention mask inputs, so for those the tensor is made the way every
 * OnnxTensor.createTensor makes one (the private native createTensorFromBuffer, here with the uint16 element type),
 * through reflection into ONNX Runtime 1.30.0's Java classes (ported from the spike, which ran it on the phone).
 * IoTensorsTest runs it with the JVM build of the same version; proguard-rules.pro keeps the members.
 *
 * Fails closed: members that are not there or refuse the call throw [OrtInternalsException], never an Error.
 */
object IoTensors {
    fun create(env: OrtEnvironment, dtype: String, shape: LongArray, data: ByteBuffer): OnnxTensor {
        require(data.isDirect && data.order() == ByteOrder.nativeOrder()) { "a direct buffer in native order is needed" }
        return when (dtype) {
            TensorIo.FLOAT16 -> OnnxTensor.createTensor(env, data.asShortBuffer(), shape, OnnxJavaType.FLOAT16)
            TensorIo.UINT8 -> OnnxTensor.createTensor(env, data, shape, OnnxJavaType.UINT8)
            TensorIo.UINT16 -> uint16(env, data, shape)
            else -> throw IllegalArgumentException("no tensor of $dtype")
        }
    }

    /** Throws [OrtInternalsException] unless this build's ONNX Runtime has the members a uint16 tensor needs. */
    fun requireUint16() {
        members()
    }

    private fun members(): Reflected = when (val found = lookup) {
        is Lookup.Found -> found.members
        is Lookup.Missing -> throw OrtInternalsException(found.cause)
    }

    private fun uint16(env: OrtEnvironment, data: ByteBuffer, shape: LongArray): OnnxTensor {
        val bytes = shape.fold(2L) { a, b -> a * b }
        require(data.capacity().toLong() == bytes) { "buffer of ${data.capacity()} B for a uint16 ${shape.toList()} tensor" }
        val m = members()
        return calling {
            val allocator = m.allocatorHandle.getLong(m.defaultAllocator.get(env))
            // The same TensorInfo ONNX Runtime gives a uint16 output: Java type int16, ONNX type uint16. Made before the
            // native tensor, so a failure here leaves nothing native behind.
            val info = m.tensorInfo.newInstance(shape, OnnxJavaType.INT16, UINT16_TYPE)
            val handle = m.createTensorFromBuffer.invoke(null, m.apiHandle.getLong(null), allocator, data, 0, bytes, shape, UINT16_TYPE.value) as Long
            // Not a copy: the tensor reads [data] at every run (the decoder's attention mask is rewritten in place).
            m.tensor.newInstance(handle, allocator, info, data, false)
        }
    }

    /**
     * [call], a reflective call into ONNX Runtime's internals: whatever it throws is an [OrtInternalsException], ONNX
     * Runtime's own errors thrown through the call included (an OrtException, an OutOfMemoryError from the native side,
     * a native method that is not there). So a failure here is never taken for a bad model file, which would delete the
     * verified Whisper folder (the mask tensor is made while Whisper loads), nor for the song's.
     */
    internal fun <T> calling(call: () -> T): T = try {
        call()
    } catch (e: InvocationTargetException) {
        throw OrtInternalsException(e.targetException)
    } catch (e: ReflectiveOperationException) {
        throw OrtInternalsException(e) // IllegalAccessException, InstantiationException
    } catch (e: IllegalArgumentException) {
        throw OrtInternalsException(e) // arguments the member found does not take
    } catch (e: ClassCastException) {
        throw OrtInternalsException(e)
    }

    private val UINT16_TYPE = TensorInfo.OnnxTensorType.ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16

    /** The members, looked up once per process. */
    private val lookup: Lookup by lazy { lookUp(::Reflected) }

    /** The members [find] looks up, or why they are not there: never an exception, so a failed lookup fails closed. */
    internal fun lookUp(find: () -> Reflected): Lookup = try {
        Lookup.Found(find())
    } catch (e: ReflectiveOperationException) {
        Lookup.Missing(e) // a class, method, field or constructor that is not there
    } catch (e: RuntimeException) {
        Lookup.Missing(e) // not accessible (SecurityException, InaccessibleObjectException), or of another type
    } catch (e: LinkageError) {
        Lookup.Missing(e) // a class that does not load
    }

    internal sealed interface Lookup {
        class Found(val members: Reflected) : Lookup

        class Missing(val cause: Throwable) : Lookup
    }

    /**
     * The package-private and private members of ONNX Runtime 1.30.0's Java API that [uint16] uses, checked against
     * java/src/main/java/ai/onnxruntime at the v1.30.0 tag; proguard-rules.pro keeps exactly these.
     */
    internal class Reflected {
        private val long = java.lang.Long.TYPE
        private val int = Integer.TYPE

        val createTensorFromBuffer: Method = OnnxTensor::class.java
            .getDeclaredMethod("createTensorFromBuffer", long, long, Buffer::class.java, int, long, LongArray::class.java, int)
            .apply { isAccessible = true }
        val apiHandle: Field = Class.forName("ai.onnxruntime.OnnxRuntime").getDeclaredField("ortApiHandle").apply { isAccessible = true }
        val defaultAllocator: Field = OrtEnvironment::class.java.getDeclaredField("defaultAllocator").apply { isAccessible = true }
        val allocatorHandle: Field = Class.forName("ai.onnxruntime.OrtAllocator").getDeclaredField("handle").apply { isAccessible = true }
        val tensorInfo: Constructor<TensorInfo> = TensorInfo::class.java
            .getDeclaredConstructor(LongArray::class.java, OnnxJavaType::class.java, TensorInfo.OnnxTensorType::class.java)
            .apply { isAccessible = true }
        val tensor: Constructor<OnnxTensor> = OnnxTensor::class.java
            .getDeclaredConstructor(long, long, TensorInfo::class.java, Buffer::class.java, java.lang.Boolean.TYPE)
            .apply { isAccessible = true }

        init {
            // Same names, other types would pass the lookup and fail at the first call: refused here instead.
            check(Modifier.isStatic(createTensorFromBuffer.modifiers) && createTensorFromBuffer.returnType == long) { "createTensorFromBuffer is not static long" }
            check(Modifier.isStatic(apiHandle.modifiers) && apiHandle.type == long) { "ortApiHandle is not a static long" }
            check(defaultAllocator.type == allocatorHandle.declaringClass) { "defaultAllocator is not an OrtAllocator" }
            check(allocatorHandle.type == long) { "OrtAllocator.handle is not a long" }
        }
    }
}

/** The encoder's cross-attention caches for one window: its own output tensors, in the decoder's input names, valid until closed. */
class CrossKv internal constructor(private val result: OrtSession.Result, contract: WhisperContract) : AutoCloseable {
    val tensors: Map<String, OnnxTensorLike> = (0 until contract.layers).flatMap { i ->
        listOf(WhisperContract.kCross(i), WhisperContract.vCross(i))
    }.associateWith { name -> result.get(name).get() as OnnxTensor }

    override fun close() = result.close()
}

/**
 * Runs an AI Hub Whisper export's encoder and decoder sessions with its I/O [contract]: mel, attention mask and logits
 * in float16 or quantized as the export declares them, the caches passed on as the sessions return them (the encoder's
 * cross caches to every decoder call, each call's self caches `_out` to the next call's `_in`). One thread at a time.
 */
class WhisperNpu(
    private val contract: WhisperContract,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val env: OrtEnvironment = Ort.env,
) : AutoCloseable {
    // Reused decoder inputs: the tensors wrap direct buffers, so writing a buffer changes the next run's input.
    private val ids = direct(4).asIntBuffer()
    private val positions = direct(4).asIntBuffer()
    private val maskIo = contract.attentionMaskIo
    private val mask = direct(contract.maskLen * maskIo.bytesPerElement)

    /** Every tensor made here, closed by [close] (or at once when the next one cannot be made). */
    private val owned = ArrayList<OnnxTensor>()
    private val maskTensor: OnnxTensor
    private val idsTensor: OnnxTensor
    private val positionsTensor: OnnxTensor

    /** The first call's self caches: 0 in the caches' encoding (the zero point when quantized), one tensor per encoding. */
    private val zeroSelf: Map<String, OnnxTensor>

    init {
        try {
            maskTensor = own(IoTensors.create(env, maskIo.spec.dtype, longArrayOf(1, 1, 1, contract.maskLen.toLong()), mask))
            idsTensor = own(OnnxTensor.createTensor(env, ids, longArrayOf(1, 1)))
            positionsTensor = own(OnnxTensor.createTensor(env, positions, longArrayOf(1)))
            val shared = HashMap<Triple<String, Quant?, List<Long>>, OnnxTensor>()
            zeroSelf = (0 until contract.layers).flatMap { listOf(WhisperContract.kSelfIn(it), WhisperContract.vSelfIn(it)) }.associateWith { name ->
                val io = contract.io.getValue(name)
                shared.getOrPut(Triple(io.spec.dtype, io.quant, io.spec.shape)) { own(zeros(io)) }
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** Mel (float, mel bins x 3000) to the encoder's input encoding, encoder run, then the cross caches (its output tensors). */
    fun encode(mel: FloatArray): CrossKv {
        val io = contract.inputFeatures
        val data = direct(mel.size * io.bytesPerElement)
        io.write(mel, data)
        val input = IoTensors.create(env, io.spec.dtype, longArrayOf(1, contract.nMels.toLong(), contract.nFrames.toLong()), data)
        return CrossKv(input.use { encoder.run(mapOf(WhisperContract.INPUT_FEATURES to it)) }, contract)
    }

    /** A decoder for one window. Close it before the [cross] it reads. */
    fun decoderStep(cross: CrossKv): NpuDecoderStep = NpuDecoderStep(cross)

    inner class NpuDecoderStep(private val cross: CrossKv) : DecoderStep, AutoCloseable {
        private var previous: OrtSession.Result? = null

        /** The logits of the token fed at [position], dequantized (or float16 decoded) before the caller's argmax. */
        override fun logits(token: Int, position: Int): FloatArray {
            ids.put(0, token)
            positions.put(0, position)
            maskIo.write(contract.attentionMask(position), mask)
            val inputs = HashMap<String, OnnxTensorLike>(3 + 4 * contract.layers)
            inputs[WhisperContract.INPUT_IDS] = idsTensor
            inputs[WhisperContract.POSITION_IDS] = positionsTensor
            inputs[WhisperContract.ATTENTION_MASK] = maskTensor
            val prev = previous
            for (i in 0 until contract.layers) {
                val k = WhisperContract.kSelfIn(i)
                val v = WhisperContract.vSelfIn(i)
                inputs[k] = prev?.let { it.get(WhisperContract.kSelfOut(i)).get() as OnnxTensor } ?: zeroSelf.getValue(k)
                inputs[v] = prev?.let { it.get(WhisperContract.vSelfOut(i)).get() as OnnxTensor } ?: zeroSelf.getValue(v)
            }
            inputs.putAll(cross.tensors)
            val result = decoder.run(inputs)
            prev?.close()
            previous = result
            return contract.logits.read((result.get(WhisperContract.LOGITS).get() as OnnxTensor).byteBuffer)
        }

        override fun close() {
            previous?.close()
            previous = null
        }
    }

    /** Closes the reused input tensors; the sessions belong to the caller. */
    override fun close() {
        owned.forEach { runCatching { it.close() } }
        owned.clear()
    }

    private fun own(tensor: OnnxTensor): OnnxTensor = tensor.also { owned += it }

    /** A tensor of [io]'s shape with every element 0 in its encoding. */
    private fun zeros(io: TensorIo): OnnxTensor {
        val shape = io.spec.shape.toLongArray()
        val count = shape.fold(1L) { a, b -> a * b }.toInt()
        val data = direct(count * io.bytesPerElement)
        io.write(FloatArray(count), data)
        return IoTensors.create(env, io.spec.dtype, shape, data)
    }

    private companion object {
        fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    }
}
