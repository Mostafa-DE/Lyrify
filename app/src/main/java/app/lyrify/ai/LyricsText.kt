package app.lyrify.ai

import java.text.Normalizer

/** Lyric text as the bench compares it. */
object LyricsText {
    private val BRACKETED_LINE = Regex("""[\[({][^\n]*[\])}]""")
    private val TAG = Regex("""\[[^\]]*]""")
    private val CURLY_APOSTROPHES = Regex("[‘’‛`´]")
    private val DASHES = Regex("[\\-‐-―]")
    private val LONE_APOSTROPHE = Regex("(?<![a-z0-9])'|'(?![a-z0-9])")
    private val UNPRINTABLE = Regex("""[\p{Cc}\p{Cf}]""")

    /**
     * The bench's `normalize_text` (`common.py`, Jam-ALT style) of one line: a line that is a bracket from start to
     * end ("[Chorus]", "(echo of the line)") is dropped, [tags] are removed, then NFKC, lowercase, curly apostrophes
     * and backticks to "'", dashes to spaces, everything but word characters, whitespace and "'" to spaces, apostrophes
     * not inside a word to spaces, "_" to a space, and single spaces between words.
     *
     * Python's `\w` (letters, digits and other numbers, "_"; not combining marks) and `str.isspace` are matched
     * code point by code point; Kotlin's lowercase is Unicode's, as Python's.
     */
    fun normalize(line: String): String {
        val stripped = pyStrip(line)
        if (BRACKETED_LINE.matches(stripped)) return ""
        val folded = Normalizer.normalize(TAG.replace(stripped, " "), Normalizer.Form.NFKC).lowercase()
        val dashless = DASHES.replace(CURLY_APOSTROPHES.replace(folded, "'"), " ")
        val kept = buildString {
            dashless.codePoints().forEach { cp -> if (isAlnum(cp) || cp == '_'.code || isPySpace(cp) || cp == '\''.code) appendCodePoint(cp) else append(' ') }
        }
        return pySplit(LONE_APOSTROPHE.replace(kept, " ").replace('_', ' ')).joinToString(" ")
    }

    /** The words of [line] the bench matches on: [normalize]d and split. */
    fun tokens(line: String): List<String> = pySplit(normalize(line))

    /**
     * The lines of plain lyrics to time: every line with a letter or digit (`common.py` `parse_lrc` keeps those) once
     * its [tags] are removed. A line that is only a tag, such as "[Chorus]", is a header, not sung, and is left out; a
     * line in parentheses, such as "(echo of the line)", is sung and kept. Control and format characters are
     * removed, ends trimmed.
     */
    fun lines(plain: String): List<String> =
        plain.lines().map(::clean).filter { line -> TAG.replace(line, " ").codePoints().anyMatch(::isAlnum) }

    /** Text for an LRC line: no control or format characters, no surrounding whitespace. */
    fun clean(text: String): String = pyStrip(UNPRINTABLE.replace(text, ""))

    /** Python's `str.isalnum` for one code point: letters, decimal digits and other numbers. */
    fun isAlnum(codePoint: Int): Boolean = Character.isLetter(codePoint) ||
        when (Character.getType(codePoint).toByte()) {
            Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }

    /** Python's `str.isspace` for one code point. */
    fun isPySpace(codePoint: Int): Boolean =
        Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85

    /** Python's `str.strip()`. */
    fun pyStrip(text: String): String = text.trim { isPySpace(it.code) }

    /** Python's `str.split()`: runs of whitespace separate, no empty pieces. */
    fun pySplit(text: String): List<String> = buildList {
        val word = StringBuilder()
        text.codePoints().forEach { cp ->
            if (isPySpace(cp)) {
                if (word.isNotEmpty()) add(word.toString())
                word.setLength(0)
            } else {
                word.appendCodePoint(cp)
            }
        }
        if (word.isNotEmpty()) add(word.toString())
    }
}
