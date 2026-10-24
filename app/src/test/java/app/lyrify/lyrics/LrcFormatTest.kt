package app.lyrify.lyrics

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcFormatTest {
    private val threeMinutes = 180_000L

    private fun ready(synced: String, durationMs: Long = threeMinutes): String {
        val result = LrcFormat.convert(synced, durationMs)
        assertTrue("expected Ready, got $result", result is Conversion.Ready)
        return (result as Conversion.Ready).text
    }

    // LRCLIB parsing

    @Test
    fun `centiseconds become milliseconds`() {
        assertEquals(listOf(LrcLine(17_130, "I feel")), LrcFormat.parseLrclib("[00:17.13] I feel"))
    }

    @Test
    fun `tenths and milliseconds are read as written`() {
        assertEquals(LrcLine(1_500, "a"), LrcFormat.parseLrclib("[00:01.5]a").single())
        assertEquals(LrcLine(1_234, "a"), LrcFormat.parseLrclib("[00:01.234]a").single())
    }

    @Test
    fun `a timestamp without a fraction is whole seconds`() {
        assertEquals(LrcLine(65_000, "a"), LrcFormat.parseLrclib("[01:05]a").single())
    }

    @Test
    fun `several timestamps on one line become separate lines`() {
        assertEquals(
            listOf(LrcLine(12_000, "Chorus"), LrcLine(90_500, "Chorus")),
            LrcFormat.parseLrclib("[00:12.00][01:30.50]Chorus"),
        )
    }

    @Test
    fun `lines are sorted by time, keeping the order of equal times`() {
        val text = "[00:30.00]c\n[00:10.00]a\n[00:20.00]b1\n[00:20.00]b2"
        assertEquals(
            listOf(LrcLine(10_000, "a"), LrcLine(20_000, "b1"), LrcLine(20_000, "b2"), LrcLine(30_000, "c")),
            LrcFormat.parseLrclib(text),
        )
    }

    @Test
    fun `metadata tags and untimed text are dropped`() {
        val text = "[ar:Nightwish]\n[ti:Nemo]\n[offset:+200]\n[length: 04:36]\nplain words\n[00:01.00]a"
        assertEquals(listOf(LrcLine(1_000, "a")), LrcFormat.parseLrclib(text))
    }

    @Test
    fun `empty timed lines are kept as breaks`() {
        assertEquals(listOf(LrcLine(5_000, "")), LrcFormat.parseLrclib("[00:05.00] "))
    }

    @Test
    fun `windows line endings and a byte order mark are handled`() {
        assertEquals(
            listOf(LrcLine(1_000, "a"), LrcLine(2_000, "b")),
            LrcFormat.parseLrclib("\uFEFF[00:01.00]a\r\n[00:02.00]b\r\n"),
        )
    }

    // Rendering

    @Test
    fun `rendering writes three digit milliseconds and newline endings`() {
        assertEquals(
            "[00:17.130]I feel\n[01:05.000]x\n",
            LrcFormat.render(listOf(LrcLine(17_130, "I feel"), LrcLine(65_000, "x"))),
        )
    }

    @Test
    fun `minutes past an hour keep counting`() {
        assertEquals("[75:01.002]late\n", LrcFormat.render(listOf(LrcLine(75 * 60_000L + 1_002, "late"))))
    }

    @Test
    fun `conversion turns LRCLIB centiseconds into Samsung milliseconds`() {
        val text = ready("[00:01.13]one\n[00:02]two\n[00:03.5]three")
        assertEquals("[00:01.130]one\n[00:02.000]two\n[00:03.500]three\n", text)
    }

    @Test
    fun `conversion expands and sorts`() {
        val text = ready("[00:03.00]c\n[00:01.00][00:04.00]a\n[00:02.00]b")
        assertEquals("[00:01.000]a\n[00:02.000]b\n[00:03.000]c\n[00:04.000]a\n", text)
    }

    @Test
    fun `encoded output is UTF-8 without a byte order mark`() {
        val bytes = LrcFormat.encode(ready("[00:01.00]Größe\n[00:02.00]è\n[00:03.00]c"))
        val expected = "[00:01.000]Größe\n".toByteArray(Charsets.UTF_8)
        assertEquals('['.code.toByte(), bytes[0])
        assertArrayEquals(expected, bytes.copyOfRange(0, expected.size))
    }

    // Samsung parser model

    @Test
    fun `the Samsung model reads the fraction as raw milliseconds`() {
        assertEquals(listOf(LrcLine(1_013, "a")), LrcFormat.parseLikeSamsung("[00:01.13]a"))
        assertEquals(listOf(LrcLine(1_005, "a")), LrcFormat.parseLikeSamsung("[00:01.5]a"))
        assertEquals(listOf(LrcLine(1_500, "a")), LrcFormat.parseLikeSamsung("[00:01.500]a"))
    }

    @Test
    fun `the Samsung model drops what Samsung drops`() {
        val text = "[00:01]no fraction\n[00:02,50]comma\n[ar:Artist]\n [00:03.000]leading space\nuntimed"
        assertEquals(emptyList<LrcLine>(), LrcFormat.parseLikeSamsung(text))
        assertEquals(emptyList<LrcLine>(), LrcFormat.parseLikeSamsung("\uFEFF[00:01.000]bom"))
    }

    @Test
    fun `the Samsung model uses the first timestamp and merges equal times`() {
        assertEquals(
            listOf(LrcLine(1_000, "a"), LrcLine(2_000, "b\nc")),
            LrcFormat.parseLikeSamsung("[00:01.000][00:09.000]a\n[00:02.000]b\n[00:02.000]c\n"),
        )
    }

    // Validation

    @Test
    fun `plain text is rejected`() {
        assertEquals(LrcProblem.TOO_FEW_LINES, LrcFormat.validate("just\nplain\nwords\n", threeMinutes))
        assertEquals(Conversion.Rejected(LrcProblem.TOO_FEW_LINES), LrcFormat.convert("just\nplain\nwords", threeMinutes))
    }

    @Test
    fun `fewer than three timed lines are rejected`() {
        assertEquals(LrcProblem.TOO_FEW_LINES, LrcFormat.validate("[00:01.000]a\n[00:02.000]b\n", threeMinutes))
    }

    @Test
    fun `lines that are all blank are rejected`() {
        val text = "[00:01.000]\n[00:02.000] \n[00:03.000]\n[00:04.000]a\n"
        assertEquals(LrcProblem.TOO_FEW_LINES, LrcFormat.validate(text, threeMinutes))
    }

    @Test
    fun `timestamps past the end of the track are rejected`() {
        val text = "[00:01.000]a\n[00:02.000]b\n[03:05.001]c\n"
        assertEquals(LrcProblem.PAST_DURATION, LrcFormat.validate(text, threeMinutes))
        assertNull(LrcFormat.validate("[00:01.000]a\n[00:02.000]b\n[03:05.000]c\n", threeMinutes))
    }

    @Test
    fun `times out of order are rejected`() {
        val text = "[00:02.000]b\n[00:01.000]a\n[00:03.000]c\n"
        assertEquals(LrcProblem.NOT_ASCENDING, LrcFormat.validate(text, threeMinutes))
    }

    @Test
    fun `a byte order mark is rejected`() {
        val text = "\uFEFF[00:01.000]a\n[00:02.000]b\n[00:03.000]c\n"
        assertEquals(LrcProblem.BYTE_ORDER_MARK, LrcFormat.validate(text, threeMinutes))
    }

    @Test
    fun `a good file is valid`() {
        assertNull(LrcFormat.validate("[00:01.000]a\n[00:02.000]\n[00:03.000]b\n[00:04.000]c\n", threeMinutes))
    }

    @Test
    fun `conversion rejects lyrics longer than the track`() {
        val text = "[00:01.00]a\n[00:02.00]b\n[04:00.00]c"
        assertEquals(Conversion.Rejected(LrcProblem.PAST_DURATION), LrcFormat.convert(text, threeMinutes))
    }

    // Sidecar path

    @Test
    fun `the sidecar replaces the extension after the last dot`() {
        assertEquals("/storage/emulated/0/Music/Song.lrc", LrcFormat.sidecarPath("/storage/emulated/0/Music/Song.mp3"))
        assertEquals("/m/A.B. Song.lrc", LrcFormat.sidecarPath("/m/A.B. Song.FLAC"))
        assertEquals("/m.dir/Song.lrc", LrcFormat.sidecarPath("/m.dir/Song.m4a"))
    }

    @Test
    fun `a file without an extension has no sidecar`() {
        assertNull(LrcFormat.sidecarPath("/m.dir/Song"))
        assertNull(LrcFormat.sidecarPath("/m/.hidden"))
    }
}
