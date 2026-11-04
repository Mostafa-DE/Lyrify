package app.lyrify.ai

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTextTest {
    // Text normalisation (the bench's normalize_text)

    @Test
    fun `normalisation lowercases, folds apostrophes and dashes, and drops punctuation`() {
        assertEquals("isn't it odd now", LyricsText.normalize("Isn’t IT \u2014 odd, now!"))
        assertEquals("fish n chips", LyricsText.normalize("fish-'n'-chips"))
        assertEquals("café 100", LyricsText.normalize("  Café 100.  "))
        assertEquals("i m", LyricsText.normalize("I''m")) // apostrophes not inside a word go
        assertEquals("hey you", LyricsText.normalize("[Verse 1] hey [x2] you"))
        assertEquals("", LyricsText.normalize("(echo of the line)"))
        assertEquals("a b", LyricsText.normalize("a_b"))
    }

    @Test
    fun `normalisation matches the bench's normalize_text on synthetic edge cases`() {
        Fixtures.load("normalize_text.json").getValue("normalize_text").jsonArray.forEach { pair ->
            val (text, expected) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(text, expected, LyricsText.normalize(text))
        }
    }

    @Test
    fun `plain lyrics lines skip blanks and headers, keep sung lines in parentheses, lose control characters`() {
        val plain = "[Verse 1]\r\nFirst line​, of words\n\n  (echo of the line)  \n...\n[Chorus] Last line\n"
        assertEquals(listOf("First line, of words", "(echo of the line)", "[Chorus] Last line"), LyricsText.lines(plain))
    }
}
