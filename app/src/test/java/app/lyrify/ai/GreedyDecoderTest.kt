package app.lyrify.ai

import app.lyrify.ai.WhisperTokenizer.Companion.EN
import app.lyrify.ai.WhisperTokenizer.Companion.EOT
import app.lyrify.ai.WhisperTokenizer.Companion.NO_TIMESTAMPS
import app.lyrify.ai.WhisperTokenizer.Companion.SOT
import app.lyrify.ai.WhisperTokenizer.Companion.TIMESTAMP_BEGIN
import app.lyrify.ai.WhisperTokenizer.Companion.TRANSCRIBE
import app.lyrify.ai.WhisperTokenizer.Companion.VOCAB_SIZE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class GreedyDecoderTest {
    private val decoder = GreedyDecoder(TestAssets.suppression)

    /**
     * A fake decoder: after the prompt it prefers [script] in order (logit 10, the rest 0 unless [extra] says
     * otherwise), records every (token, position) fed, and prefers end of text once the script is done.
     */
    private class Scripted(
        private val script: List<Int>,
        private val extra: (step: Int, logits: FloatArray) -> Unit = { _, _ -> },
    ) : DecoderStep {
        val fed = ArrayList<Pair<Int, Int>>()

        override fun logits(token: Int, position: Int): FloatArray {
            fed += token to position
            val step = fed.size - 3 // index of the token to sample next, once the prompt is in
            val logits = FloatArray(VOCAB_SIZE)
            if (step >= 0) logits[script.getOrElse(step) { EOT }] = 10f
            extra(step, logits)
            return logits
        }
    }

    @Test
    fun `the prompt is fed at positions 0 to 2, then each sampled token at the next position`() {
        val model = Scripted(listOf(TIMESTAMP_BEGIN, 1000, 2000, TIMESTAMP_BEGIN + 50))
        val d = decoder.decode(model)
        assertEquals(listOf(SOT to 0, EN to 1, TRANSCRIBE to 2, TIMESTAMP_BEGIN to 3, 1000 to 4, 2000 to 5, TIMESTAMP_BEGIN + 50 to 6), model.fed)
        assertEquals(listOf(TIMESTAMP_BEGIN, 1000, 2000, TIMESTAMP_BEGIN + 50, EOT), d.tokens.map { it.id })
        assertEquals(StopReason.EOT, d.stopReason)
        assertEquals(7, d.decoderCalls)
    }

    @Test
    fun `a blank or end of text is not sampled first, suppressed tokens never`() {
        // First step: blank (220) is best, then end of text; the third best, 500, wins.
        val first = Scripted(listOf(220)) { step, l ->
            if (step == 0) {
                l[EOT] = 9f
                l[500] = 8f
            }
        }
        assertEquals(500, decoder.decode(first).tokens.first().id)
        // <|notimestamps|> and <|translate|> are always suppressed; 600 is sampled instead, later 220 is fine.
        val later = Scripted(listOf(600, 220)) { step, l ->
            if (step == 0) {
                l[NO_TIMESTAMPS] = 20f
                l[50359] = 15f
            }
        }
        assertEquals(listOf(600, 220, EOT), decoder.decode(later).tokens.map { it.id })
    }

    @Test
    fun `stops at 200 tokens of prompt plus output, after 199 decoder calls, as the export allows`() {
        val model = Scripted(List(500) { 1000 + it })
        val d = decoder.decode(model)
        assertEquals(StopReason.MAX_LENGTH, d.stopReason)
        assertEquals(197, d.tokens.size)
        assertEquals(199, d.decoderCalls)
        assertEquals(198, model.fed.last().second)
    }

    @Test
    fun `stops when a 3-token n-gram repeats 3 times, timestamps in between ignored`() {
        val loop = listOf(11, 12, 13, TIMESTAMP_BEGIN + 10, 11, 12, 13, TIMESTAMP_BEGIN + 20, 11, 12, 13, 14, 15)
        val d = decoder.decode(Scripted(loop))
        assertEquals(StopReason.REPETITION, d.stopReason)
        assertEquals(3, d.repeatedNgram)
        assertEquals(loop.take(11), d.tokens.map { it.id })
        // Twice is fine.
        assertEquals(StopReason.EOT, decoder.decode(Scripted(listOf(11, 12, 13, 11, 12, 13, 14))).stopReason)
    }

    @Test
    fun `repeated n-grams are found at the end only, shortest first`() {
        assertEquals(3, GreedyDecoder.repeatedNgram(listOf(9, 1, 2, 3, 1, 2, 3, 1, 2, 3), 3, 3))
        assertNull(GreedyDecoder.repeatedNgram(listOf(1, 2, 3, 1, 2, 3, 1, 2, 3, 9), 3, 3))
        assertNull(GreedyDecoder.repeatedNgram(listOf(5, 5, 5, 5, 5, 5, 5, 5), 3, 3)) // 8 tokens: no 3 x 3
        assertEquals(3, GreedyDecoder.repeatedNgram(List(9) { 5 }, 3, 3))
        // A 2-token loop shows up as a 4-gram after 12 tokens.
        assertNull(GreedyDecoder.repeatedNgram(List(10) { if (it % 2 == 0) 1 else 2 }, 3, 3))
        assertEquals(4, GreedyDecoder.repeatedNgram(List(12) { if (it % 2 == 0) 1 else 2 }, 3, 3))
    }

    @Test
    fun `the probability is the softmax over the allowed tokens`() {
        val model = Scripted(listOf(700)) { step, l ->
            if (step == 0) {
                l[701] = 9f
                l[NO_TIMESTAMPS] = 50f // suppressed: no share of the probability
            }
        }
        val p = decoder.decode(model).tokens.first().p
        // 700 at 10, 701 at 9, every other allowed token at 0.
        val allowed = VOCAB_SIZE - TestAssets.suppression.always.size - 2 // minus 700, 701
        val begin = TestAssets.suppression.atBegin.size // also masked at the first token
        val expected = exp(10.0) / (exp(10.0) + exp(9.0) + (allowed - begin) * 1.0)
        assertEquals(expected, p, 1e-9)
        assertTrue(p < 1.0)
    }

    @Test
    fun `segments come from timestamp pairs, in song time`() {
        val tok = TestAssets.tokenizer
        val hello = listOf(2425, 13) // " Hello."
        val ids = listOf(TIMESTAMP_BEGIN) + hello + listOf(TIMESTAMP_BEGIN + 100, TIMESTAMP_BEGIN + 100) + hello +
            listOf(TIMESTAMP_BEGIN + 250) + hello + listOf(EOT)
        val s = segments(ids, tok, 10.0)
        assertEquals(3, s.size)
        assertEquals(Segment(10.0, 12.0, tok.decode(hello)), s[0])
        assertEquals(Segment(12.0, 15.0, tok.decode(hello)), s[1])
        assertEquals(Segment(15.0, null, tok.decode(hello)), s[2])
        // No timestamps: one segment from the window start.
        assertEquals(listOf(Segment(3.5, null, tok.decode(hello))), segments(hello + EOT, tok, 3.5))
    }
}
