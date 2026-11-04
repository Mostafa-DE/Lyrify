package app.lyrify.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Loads the tokenizer and suppression lists from the app's assets, as the app does. */
object TestAssets {
    val tokenizer: WhisperTokenizer by lazy {
        WhisperTokenizer.fromJson(
            File("src/main/assets/whisper/vocab.json").readText(),
            File("src/main/assets/whisper/added_tokens.json").readText(),
        )
    }
    val suppression: Suppression by lazy {
        Suppression.fromGenerationConfig(File("src/main/assets/whisper/generation_config.json").readText())
    }
}

class WhisperTokenizerTest {
    private val tokenizer = TestAssets.tokenizer
    private val golden = Json.parseToJsonElement(File("src/test/resources/npu/tokenizer_golden.json").readText()).jsonObject

    @Test
    fun `decodes like transformers' WhisperTokenizer, specials and timestamps skipped`() {
        val cases = golden.getValue("cases").jsonArray
        assertEquals(6, cases.size)
        for (case in cases) {
            val ids = case.jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.int }
            val text = case.jsonObject.getValue("text").jsonPrimitive.content
            assertEquals(text, tokenizer.decode(ids))
        }
    }

    @Test
    fun `special tokens have transformers' names`() {
        golden.getValue("specials").jsonObject.forEach { (id, name) ->
            assertEquals(name.jsonPrimitive.content, tokenizer.tokenText(id.toInt()))
        }
        assertEquals(51866, tokenizer.vocabSize)
    }

    @Test
    fun `timestamps are 20 ms apart from 50365, text tokens are below end of text`() {
        assertEquals(0.0, WhisperTokenizer.timestampSec(50365), 0.0)
        assertEquals(0.02, WhisperTokenizer.timestampSec(50366), 0.0)
        assertEquals(3.0, WhisperTokenizer.timestampSec(50365 + 150), 0.0)
        assertEquals(30.0, WhisperTokenizer.timestampSec(51865), 0.0)
        assertTrue(WhisperTokenizer.isText(50256))
        assertFalse(WhisperTokenizer.isText(50257))
        assertFalse(WhisperTokenizer.isTimestamp(50364))
        assertTrue(WhisperTokenizer.isTimestamp(51865))
    }

    @Test
    fun `a lone byte of a multi-byte character decodes to the replacement character`() {
        // The golden sentence with Japanese text has tokens that hold part of a character.
        val ids = golden.getValue("cases").jsonArray[3].jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.int }
        val partial = ids.map { tokenizer.tokenText(it) }.filter { '�' in it }
        assertTrue("expected at least one partial-character token", partial.isNotEmpty())
    }

    @Test
    fun `standard suppression from generation_config, plus no-timestamps`() {
        val s = TestAssets.suppression
        assertEquals(setOf(220, 50257), s.atBegin)
        assertTrue(WhisperTokenizer.NO_TIMESTAMPS in s.always)
        // task and control tokens are never sampled
        listOf(50358 + 1, 50360, 50361, 50362, 50363, 50258).forEach { assertTrue("$it", it in s.always) }
        // end of text, language tokens and timestamps stay allowed
        listOf(50257, 50259, 50365, 51865).forEach { assertFalse("$it", it in s.always) }
        assertEquals(89, s.always.size) // 88 suppress_tokens + <|notimestamps|>
    }
}
