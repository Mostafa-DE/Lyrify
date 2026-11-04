package app.lyrify.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream

/**
 * Decode-only tokenizer of openai/whisper-large-v3-turbo, from its vocab.json (byte-level BPE pieces, ids 0 to
 * 50256) and added_tokens.json (specials and timestamps, 50257 to 51865). Text is decoded like transformers'
 * `decode(ids, skip_special_tokens=True, clean_up_tokenization_spaces=False)`: the byte-level pieces of the text
 * tokens joined, mapped back to bytes, read as UTF-8.
 */
class WhisperTokenizer private constructor(private val pieces: Array<String>) {
    val vocabSize: Int get() = pieces.size

    /** The raw piece: byte-level characters for a text token, e.g. "<|0.02|>" for a special one. */
    fun piece(id: Int): String = pieces[id]

    /** Text of the text tokens in [ids]; special and timestamp tokens are skipped. */
    fun decode(ids: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) if (isText(id)) appendBytes(pieces[id], bytes)
        return bytes.toString(Charsets.UTF_8.name())
    }

    /** One token as text: its UTF-8 (a lone byte of a multi-byte character shows as U+FFFD), or a special's name. */
    fun tokenText(id: Int): String = if (isText(id)) decode(listOf(id)) else pieces[id]

    private fun appendBytes(piece: String, out: ByteArrayOutputStream) {
        for (ch in piece) {
            val b = BYTE_OF_CHAR[ch.code]
            require(b >= 0) { "character U+%04X is not in the byte-level alphabet".format(ch.code) }
            out.write(b)
        }
    }

    companion object {
        const val EOT = 50257
        const val SOT = 50258
        const val EN = 50259
        const val TRANSLATE = 50359
        const val TRANSCRIBE = 50360
        const val SOT_LM = 50361
        const val SOT_PREV = 50362
        const val NO_SPEECH = 50363
        const val NO_TIMESTAMPS = 50364
        const val TIMESTAMP_BEGIN = 50365
        const val TIMESTAMP_LAST = 51865
        const val VOCAB_SIZE = 51866
        const val SECONDS_PER_TIMESTAMP = 0.02

        fun isText(id: Int): Boolean = id in 0 until EOT
        fun isTimestamp(id: Int): Boolean = id in TIMESTAMP_BEGIN..TIMESTAMP_LAST

        /** Seconds from the window start for a timestamp token, e.g. 50365 -> 0.0, 50366 -> 0.02. */
        fun timestampSec(id: Int): Double {
            require(isTimestamp(id)) { "$id is not a timestamp token" }
            return Math.round((id - TIMESTAMP_BEGIN) * SECONDS_PER_TIMESTAMP * 100) / 100.0
        }

        private val json = Json { ignoreUnknownKeys = true }

        /** Builds the tokenizer from vocab.json and added_tokens.json, and checks the special ids this app uses. */
        fun fromJson(vocabJson: String, addedTokensJson: String): WhisperTokenizer {
            val all = HashMap<Int, String>()
            json.parseToJsonElement(vocabJson).jsonObject.forEach { (piece, id) -> all[id.jsonPrimitive.int] = piece }
            json.parseToJsonElement(addedTokensJson).jsonObject.forEach { (piece, id) -> all[id.jsonPrimitive.int] = piece }
            require(all.size == VOCAB_SIZE && all.keys.all { it in 0 until VOCAB_SIZE }) {
                "expected ids 0 to ${VOCAB_SIZE - 1}, got ${all.size} tokens"
            }
            val pieces = Array(VOCAB_SIZE) { all.getValue(it) }
            val expected = mapOf(
                EOT to "<|endoftext|>", SOT to "<|startoftranscript|>", EN to "<|en|>", TRANSLATE to "<|translate|>",
                TRANSCRIBE to "<|transcribe|>", SOT_LM to "<|startoflm|>", SOT_PREV to "<|startofprev|>",
                NO_SPEECH to "<|nospeech|>", NO_TIMESTAMPS to "<|notimestamps|>", TIMESTAMP_BEGIN to "<|0.00|>",
                TIMESTAMP_LAST to "<|30.00|>",
            )
            expected.forEach { (id, name) -> require(pieces[id] == name) { "token $id is ${pieces[id]}, expected $name" } }
            return WhisperTokenizer(pieces)
        }

        /** GPT-2's bytes_to_unicode, inverted: byte value for each character code of the byte-level alphabet. */
        private val BYTE_OF_CHAR: IntArray = IntArray(512) { -1 }.also { map ->
            val printable = (33..126) + (161..172) + (174..255)
            printable.forEach { map[it] = it }
            var n = 0
            for (b in 0..255) if (b !in printable) map[256 + n++] = b
        }
    }
}

/** Whisper's standard token suppression, from the model's generation_config.json. */
class Suppression(
    /** Never sampled: non-speech symbols and task/control tokens (`suppress_tokens`). */
    val always: Set<Int>,
    /** Not sampled as the first token of a window: a lone space and end of text (`begin_suppress_tokens`). */
    val atBegin: Set<Int>,
) {
    companion object {
        /**
         * Reads generation_config.json. `<|notimestamps|>` is added to [always]: the spike decodes with timestamps
         * allowed, as Whisper does whenever it predicts timestamps.
         */
        fun fromGenerationConfig(text: String): Suppression {
            val o = Json.parseToJsonElement(text).jsonObject
            val always = o.getValue("suppress_tokens").jsonArray.map { it.jsonPrimitive.int }.toSet()
            val begin = o.getValue("begin_suppress_tokens").jsonArray.map { it.jsonPrimitive.int }.toSet()
            val noTimestamps = o.getValue("no_timestamps_token_id").jsonPrimitive.int
            require(noTimestamps == WhisperTokenizer.NO_TIMESTAMPS) { "unexpected no_timestamps_token_id $noTimestamps" }
            return Suppression(always + noTimestamps, begin)
        }
    }
}
