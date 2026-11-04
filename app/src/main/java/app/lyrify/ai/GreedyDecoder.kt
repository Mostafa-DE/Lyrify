package app.lyrify.ai

import kotlin.math.exp
import kotlin.math.ln

/** One decoder call: feed [token] at [position], get the next-token logits (vocabulary size). */
fun interface DecoderStep {
    fun logits(token: Int, position: Int): FloatArray
}

enum class StopReason { EOT, MAX_LENGTH, REPETITION }

/** A sampled token and its probability after suppression (softmax over the allowed tokens). */
data class SampledToken(val id: Int, val p: Double)

data class WindowDecode(
    val tokens: List<SampledToken>,
    val stopReason: StopReason,
    /** Decoder calls made, prompt included. */
    val decoderCalls: Int,
    /** For [StopReason.REPETITION]: the repeated n-gram's length in text tokens. */
    val repeatedNgram: Int? = null,
)

/**
 * Greedy decoding of one 30 s window with a fixed prompt and no previous text, as the AI Hub app's loop
 * (`HfWhisperApp._transcribe_single_chunk`) does, plus Whisper's standard suppression and a repetition guard.
 *
 * - The prompt (`<|startoftranscript|><|en|><|transcribe|>`, no `<|notimestamps|>`) is fed at positions 0, 1, 2.
 * - Then each step takes the argmax of the logits with [Suppression.always] masked, and [Suppression.atBegin]
 *   masked too for the first sampled token.
 * - Decoding stops at `<|endoftext|>`; when prompt plus sampled tokens reach [maxLength] (200, the export's
 *   attention mask length: the decoder is called at most 199 times, as in the AI Hub loop); or when the text tokens
 *   end with the same n-gram (n >= [ngramMin]) [repeats] times in a row.
 */
class GreedyDecoder(
    private val suppression: Suppression,
    val prompt: List<Int> = listOf(WhisperTokenizer.SOT, WhisperTokenizer.EN, WhisperTokenizer.TRANSCRIBE),
    val maxLength: Int = MAX_LENGTH,
    private val ngramMin: Int = 3,
    private val repeats: Int = 3,
) {
    init {
        require(prompt.isNotEmpty() && prompt.size < maxLength) { "prompt must be shorter than maxLength" }
    }

    fun decode(step: DecoderStep): WindowDecode {
        var position = 0
        var logits = FloatArray(0)
        for (token in prompt) logits = step.logits(token, position++)
        val sampled = ArrayList<SampledToken>()
        val text = ArrayList<Int>()
        while (true) {
            val token = sample(logits, first = sampled.isEmpty())
            sampled += token
            if (token.id == WhisperTokenizer.EOT) return WindowDecode(sampled, StopReason.EOT, position)
            if (WhisperTokenizer.isText(token.id)) {
                text += token.id
                repeatedNgram(text, ngramMin, repeats)?.let { n ->
                    return WindowDecode(sampled, StopReason.REPETITION, position, repeatedNgram = n)
                }
            }
            if (prompt.size + sampled.size >= maxLength) return WindowDecode(sampled, StopReason.MAX_LENGTH, position)
            logits = step.logits(token.id, position++)
        }
    }

    private fun sample(logits: FloatArray, first: Boolean): SampledToken {
        fun allowed(id: Int) = id !in suppression.always && !(first && id in suppression.atBegin)
        var best = -1
        var bestLogit = Double.NEGATIVE_INFINITY
        for (id in logits.indices) {
            val v = logits[id].toDouble()
            if (allowed(id) && v > bestLogit) {
                best = id
                bestLogit = v
            }
        }
        check(best >= 0) { "every token is suppressed or the logits are NaN" }
        var sum = 0.0
        for (id in logits.indices) if (allowed(id)) sum += exp(logits[id] - bestLogit)
        return SampledToken(best, exp(-ln(sum)))
    }

    companion object {
        /** The export's decode length: attention_mask is (1, 1, 1, 200), the self-attention caches hold 199. */
        const val MAX_LENGTH = 200

        /**
         * The length of an n-gram (n >= [min]) that ends [tokens] [repeats] times in a row, the shortest such n, or
         * null when there is none.
         */
        fun repeatedNgram(tokens: List<Int>, min: Int, repeats: Int): Int? {
            for (n in min..tokens.size / repeats) {
                val end = tokens.size
                var all = true
                loop@ for (r in 1 until repeats) for (i in 0 until n) {
                    if (tokens[end - 1 - i] != tokens[end - 1 - i - r * n]) {
                        all = false
                        break@loop
                    }
                }
                if (all) return n
            }
            return null
        }
    }
}

/** A text span between two timestamp tokens of one window, in song time. */
data class Segment(val start: Double, val end: Double?, val text: String)

/**
 * Segments of a window from its timestamp tokens: text between `<|t0|>` and `<|t1|>` becomes (offset + t0,
 * offset + t1, text). Text before the first timestamp starts at the window start; text after the last one has no end.
 */
fun segments(ids: List<Int>, tokenizer: WhisperTokenizer, offsetSec: Double): List<Segment> {
    val out = ArrayList<Segment>()
    var start: Double? = null
    val pending = ArrayList<Int>()
    fun flush(end: Double?) {
        val text = tokenizer.decode(pending)
        if (text.isNotBlank()) out += Segment(start ?: offsetSec, end, text)
        pending.clear()
    }
    for (id in ids) {
        when {
            WhisperTokenizer.isTimestamp(id) -> {
                val t = offsetSec + WhisperTokenizer.timestampSec(id)
                if (pending.isNotEmpty()) flush(t)
                start = t
            }
            WhisperTokenizer.isText(id) -> pending += id
        }
    }
    flush(null)
    return out
}
