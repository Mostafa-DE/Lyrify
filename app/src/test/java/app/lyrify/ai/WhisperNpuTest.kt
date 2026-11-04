package app.lyrify.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.lyrify.models.WhisperPrecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [WhisperNpu] end to end on ONNX Runtime 1.30.0's JVM build, for the float and the w8a16 export: the app's encode and
 * decoder step against stand-in models with the export's exact I/O (tools/whisper_io_models.py, made from SM8850's
 * metadata.json files). The stand-ins compute nothing of Whisper; their outputs show what WhisperNpu fed them:
 *
 * - the encoder fills every cross cache with one value from the mel's largest element (w8a16: its uint16 code / 256 as
 *   uint8; float: 10 x its value as float16), so the decoder sees whether the mel was encoded as the contract says and
 *   whether the cross caches reached it;
 * - the decoder returns its self caches + 1 and puts one hot logit at
 *   1000 + sum(self - zero point) + sum(cross - zero point) + 100 x attended mask positions + 7 x position + 2 x token,
 *   so the argmax shows whether the caches started at their zero point and were passed on, and the mask, position and
 *   token were fed; the hot code is 40000 (w8a16) or 5.0 (float), every other logit 0 in real terms.
 */
class WhisperNpuTest {
    private val env = OrtEnvironment.getEnvironment()
    private val resources = "src/test/resources/npu"

    private fun contract(precision: WhisperPrecision) =
        appContract(File("$resources/aihub_metadata_${precision.id}_snapdragon_8_elite_gen5_for_galaxy.json").readText())

    private fun session(precision: WhisperPrecision, part: String): OrtSession = OrtSession.SessionOptions().use {
        env.createSession(File("$resources/whisper_standin_${precision.id}_$part.onnx").path, it)
    }

    @Test
    fun `w8a16 - the uint16 mel and mask are quantized, the uint8 caches start at 128 and pass on, the logits are dequantized`() {
        val contract = contract(WhisperPrecision.W8A16)
        // The mel's largest value, 0.5, is code 42140: the cross caches hold 42140 / 256 = 164, 36 above their zero point.
        val code = contract.inputFeatures.encode(0.5f)
        assertEquals(42140, code)
        val cross = code / 256 - 128
        val hot = contract.logits.decode(40000)
        assertEquals((40000 - 23109) * 0.0007861469639465213f, hot, 1e-6f)
        decodes(WhisperPrecision.W8A16, contract, cross, hot)
    }

    @Test
    fun `float - the same steps in float16, the float path the app always ran`() {
        decodes(WhisperPrecision.FLOAT, contract(WhisperPrecision.FLOAT), cross = 5, hot = 5f)
    }

    /** Two windows of five decoder calls each through [WhisperNpu], checking every call's logits. */
    private fun decodes(precision: WhisperPrecision, contract: WhisperContract, cross: Int, hot: Float) {
        session(precision, "encoder").use { encoder ->
            session(precision, "decoder").use { decoder ->
                // The sessions NpuStages would accept: their I/O is the contract's.
                assertEquals(emptyList<String>(), WhisperContract.mismatches(contract.encoderInputs, specs(encoder.inputInfo)))
                assertEquals(emptyList<String>(), WhisperContract.mismatches(contract.encoderOutputs, specs(encoder.outputInfo)))
                assertEquals(emptyList<String>(), WhisperContract.mismatches(contract.decoderInputs, specs(decoder.inputInfo)))
                assertEquals(emptyList<String>(), WhisperContract.mismatches(contract.decoderOutputs, specs(decoder.outputInfo)))
                WhisperNpu(contract, encoder, decoder, env).use { npu ->
                    val mel = FloatArray(contract.nMels * contract.nFrames) { if (it == 7) 0.5f else -1.5f }
                    repeat(2) { window ->
                        // A new window's decoder starts from the zero caches again (the shared tensors stay as they were).
                        npu.encode(mel).use { kv ->
                            npu.decoderStep(kv).use { step ->
                                for (position in 0 until 5) {
                                    val token = 10 + position
                                    val logits = step.logits(token, position)
                                    val selfCalls = 8 * position // each of the 8 self caches is +1 per earlier call
                                    val expected = 1000 + selfCalls + 8 * cross + 100 * (position + 1) + 7 * position + 2 * token
                                    assertEquals(contract.vocab, logits.size)
                                    assertEquals("window $window, position $position", expected, logits.indices.maxBy { logits[it] })
                                    assertEquals(hot, logits[expected], 0f)
                                    assertTrue(logits.indices.all { it == expected || logits[it] == 0f })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
