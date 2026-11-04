package app.lyrify.ai

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import app.lyrify.BuildConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InaccessibleObjectException
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [IoTensors] on ONNX Runtime 1.30.0's JVM build (the same Java API as the Android AAR, whose classes declare the same
 * members; run on the laptop's CPU), ported from lyrify-npu-spike. uint_io.onnx is the spike's
 * (tools/make_variant_goldens.py): uint16 and uint8 inputs, given back as they are and cast to int32.
 */
class IoTensorsTest {
    private val env = OrtEnvironment.getEnvironment()
    private val model = File("src/test/resources/npu/uint_io.onnx").path

    private fun direct(bytes: Int) = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    @Test
    fun `ONNX Runtime is 1_30_0, the version whose private members IoTensors uses`() {
        // A version bump fails here on purpose. Before changing this: check IoTensors.Reflected and the keep rules in
        // proguard-rules.pro against java/src/main/java/ai/onnxruntime at the new tag, then run the w8a16 Whisper on a phone.
        assertEquals("1.30.0", BuildConfig.ORT_VERSION)
        // These tests run that version's JVM build (gradle/libs.versions.toml: onnxruntime-jvm takes the same version).
        assertEquals("1.30.0", env.version)
        assertTrue(IoTensors.lookUp(IoTensors::Reflected) is IoTensors.Lookup.Found)
        IoTensors.requireUint16()
    }

    @Test
    fun `uint16 and uint8 tensors reach the model unsigned, and outputs feed back as inputs`() {
        val a = intArrayOf(0, 1, 32767, 32768, 65534, 65535)
        val b = intArrayOf(0, 1, 127, 128, 254, 255)
        val shape = longArrayOf(2, 3)
        val aData = direct(12).also { buf -> a.forEachIndexed { i, v -> buf.putShort(2 * i, v.toShort()) } }
        val bData = direct(6).also { buf -> b.forEachIndexed { i, v -> buf.put(i, v.toByte()) } }
        OrtSession.SessionOptions().use { so ->
            env.createSession(model, so).use { session ->
                val aTensor = IoTensors.create(env, TensorIo.UINT16, shape, aData)
                val bTensor = IoTensors.create(env, TensorIo.UINT8, shape, bData)
                assertEquals("ONNX_TENSOR_ELEMENT_DATA_TYPE_UINT16", aTensor.info.onnxType.name)
                aTensor.use { at ->
                    bTensor.use { bt ->
                        session.run(mapOf("a" to at, "b" to bt)).use { r ->
                            assertArrayEquals(a, ints(r, "a_int32"))
                            assertArrayEquals(b, ints(r, "b_int32"))
                            val aOut = r.get("a_out").get() as OnnxTensor
                            val bOut = r.get("b_out").get() as OnnxTensor
                            assertArrayEquals(a, aOut.byteBuffer.let { s -> IntArray(6) { s.getShort(2 * it).toInt() and 0xFFFF } })
                            assertArrayEquals(b, bOut.byteBuffer.let { s -> IntArray(6) { s.get(it).toInt() and 0xFF } })
                            // As the decoder's uint8 self caches are: the output tensors are the next run's inputs, unchanged.
                            session.run(mapOf("a" to aOut, "b" to bOut)).use { again ->
                                assertArrayEquals(a, ints(again, "a_int32"))
                                assertArrayEquals(b, ints(again, "b_int32"))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `a uint16 tensor reads its buffer at every run, as the decoder's attention mask is rewritten`() {
        val data = direct(12)
        val mask = TensorIo(TensorSpec("a", TensorIo.UINT16, listOf(2, 3)), Quant(100f / 65535, 65535))
        val tensor = IoTensors.create(env, TensorIo.UINT16, longArrayOf(2, 3), data)
        val b = IoTensors.create(env, TensorIo.UINT8, longArrayOf(2, 3), direct(6))
        OrtSession.SessionOptions().use { so ->
            env.createSession(model, so).use { session ->
                for (value in listOf(0f, WhisperContract.MASK_NEG, -50f)) {
                    mask.write(FloatArray(6) { value }, data)
                    session.run(mapOf("a" to tensor, "b" to b)).use { assertArrayEquals(IntArray(6) { mask.encode(value) }, ints(it, "a_int32")) }
                }
            }
        }
        tensor.close()
        b.close()
    }

    @Test
    fun `buffers that are not direct, native order or the tensor's size are refused`() {
        assertThrows(IllegalArgumentException::class.java) { IoTensors.create(env, TensorIo.UINT16, longArrayOf(2, 3), ByteBuffer.allocate(12)) }
        val otherOrder = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        assertThrows(IllegalArgumentException::class.java) { IoTensors.create(env, TensorIo.UINT16, longArrayOf(2, 3), ByteBuffer.allocateDirect(12).order(otherOrder)) }
        assertThrows(IllegalArgumentException::class.java) { IoTensors.create(env, TensorIo.UINT16, longArrayOf(2, 3), direct(10)) }
        assertThrows(IllegalArgumentException::class.java) { IoTensors.create(env, TensorIo.INT32, longArrayOf(1), direct(4)) }
    }

    // Failing closed

    @Test
    fun `members that are not there or not reachable fail closed, as a lookup result and never an exception or Error`() {
        val failures: List<Throwable> = listOf(
            NoSuchMethodException("createTensorFromBuffer"),
            NoSuchFieldException("ortApiHandle"),
            ClassNotFoundException("ai.onnxruntime.OrtAllocator"),
            SecurityException("no access"),
            InaccessibleObjectException("module ai.onnxruntime does not open"),
            IllegalStateException("createTensorFromBuffer is not static long"),
            NoClassDefFoundError("ai/onnxruntime/OrtAllocator"),
        )
        failures.forEach { failure ->
            val found = IoTensors.lookUp { throw failure }
            assertTrue(failure.toString(), found is IoTensors.Lookup.Missing && found.cause === failure)
        }
    }

    @Test
    fun `whatever a reflective call throws is the internals', never the model's, so the verified Whisper folder stays`() {
        assertEquals(7, IoTensors.calling { 7 })
        val whisper = File("/models", AiModels.WHISPER)
        val thrown: List<Throwable> = listOf(
            InvocationTargetException(OrtException("createTensorFromBuffer failed")),
            InvocationTargetException(OutOfMemoryError("native tensor")),
            InvocationTargetException(UnsatisfiedLinkError("createTensorFromBuffer")),
            IllegalAccessException("OnnxTensor.<init>"),
            InstantiationException("TensorInfo"),
            IllegalArgumentException("wrong number of arguments"),
            ClassCastException("java.lang.Integer cannot be cast to java.lang.Long"),
        )
        thrown.forEach { failure ->
            val e = assertThrows(OrtInternalsException::class.java) { IoTensors.calling<Unit> { throw failure } }
            val expectedCause = if (failure is InvocationTargetException) failure.targetException else failure
            assertTrue(failure.toString(), e.cause === expectedCause)
            // What NpuStages makes of it while loading Whisper (the mask tensor is made in WhisperNpu's constructor).
            assertTrue(failure.toString(), loadFailure(whisper, e) is NpuUnavailableException)
        }
    }

    @Test
    fun `missing internals make the NPU unavailable for the night, while any other load failure is the model's`() {
        val whisper = File("/models", AiModels.WHISPER)
        val internals = OrtInternalsException(NoSuchMethodException("createTensorFromBuffer"))
        val npu = loadFailure(whisper, internals)
        assertTrue(npu.toString(), npu is NpuUnavailableException && npu.cause === internals)
        val model = loadFailure(whisper, IllegalStateException("QNN graph finalize failed"))
        assertTrue(model.toString(), model is ModelLoadException && model.model == whisper)
    }

    private fun ints(result: OrtSession.Result, name: String): IntArray =
        (result.get(name).get() as OnnxTensor).intBuffer.let { b -> IntArray(b.remaining()) { b.get(it) } }
}
