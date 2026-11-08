package app.lyrify.tones

import app.lyrify.lyrics.LrcLine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ToneWindowTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val fourMinutes = 240_000L

    // The kinds

    @Test
    fun `a ringtone is 30 seconds, an alarm 60 and a notification sound 15 until the user picks another length`() {
        assertEquals(30_000L, ToneKind.RINGTONE.defaultLengthMs)
        assertEquals(60_000L, ToneKind.ALARM.defaultLengthMs)
        assertEquals(15_000L, ToneKind.NOTIFICATION.defaultLengthMs)
        ToneKind.entries.forEach { assertTrue(it.name, it.defaultLengthMs in ToneWindow.LENGTHS_MS) }
    }

    @Test
    fun `the length chips are 15, 30, 45 and 60 seconds`() {
        assertEquals(listOf(15_000L, 30_000L, 45_000L, 60_000L), ToneWindow.LENGTHS_MS)
    }

    @Test
    fun `each kind has its shared folder and its RingtoneManager type`() {
        assertEquals("Ringtones", ToneKind.RINGTONE.folder)
        assertEquals("Alarms", ToneKind.ALARM.folder)
        assertEquals("Notifications", ToneKind.NOTIFICATION.folder)
        // RingtoneManager.TYPE_RINGTONE, TYPE_ALARM, TYPE_NOTIFICATION
        assertEquals(1, ToneKind.RINGTONE.ringtoneType)
        assertEquals(4, ToneKind.ALARM.ringtoneType)
        assertEquals(2, ToneKind.NOTIFICATION.ringtoneType)
    }

    @Test
    fun `a song longer than the decoder reads offers its first 20 minutes less a second`() {
        assertEquals(1_199_000L, ToneWindow.MAX_SOURCE_MS)
        assertEquals(fourMinutes, ToneWindow.usableDurationMs(fourMinutes))
        assertEquals(1_199_000L, ToneWindow.usableDurationMs(25 * 60_000L))
    }

    // The window from its start: the start the user chose wins, the length shortens

    @Test
    fun `a window inside the song is kept as asked`() {
        assertEquals(ToneWindow(72_000, 30_000), ToneWindow.from(72_000, 30_000, fourMinutes))
    }

    @Test
    fun `a start before the song is moved to its beginning`() {
        assertEquals(ToneWindow(0, 30_000), ToneWindow.from(-5_000, 30_000, fourMinutes))
    }

    @Test
    fun `a start past the end is clamped so a second of the song is left`() {
        assertEquals(ToneWindow(239_000, 1_000), ToneWindow.from(300_000, 30_000, fourMinutes))
    }

    @Test
    fun `a length that runs past the end shortens`() {
        assertEquals(ToneWindow(225_000, 15_000), ToneWindow.from(225_000, 30_000, fourMinutes))
    }

    @Test
    fun `a length is kept between 1 and 60 seconds`() {
        assertEquals(1_000L, ToneWindow.from(0, 200, fourMinutes).lengthMs)
        assertEquals(60_000L, ToneWindow.from(0, 90_000, fourMinutes).lengthMs)
    }

    @Test
    fun `a song shorter than a second is the whole window, an unknown length an empty one`() {
        assertEquals(ToneWindow(0, 800), ToneWindow.from(300, 30_000, 800))
        assertEquals(ToneWindow(0, 0), ToneWindow.from(10_000, 30_000, 0))
    }

    @Test
    fun `a start a second earlier or later stays in the song`() {
        assertEquals(ToneWindow(0, 30_000), ToneWindow.from(0 - 1_000, 30_000, fourMinutes))
        assertEquals(ToneWindow(73_000, 30_000), ToneWindow.from(72_000 + 1_000, 30_000, fourMinutes))
    }

    @Test
    fun `the end is the start plus the length`() {
        assertEquals(102_000L, ToneWindow(72_000, 30_000).endMs)
    }

    // The whole window dragged: the length is kept, the window moves

    @Test
    fun `a window dragged past the end moves back so its length fits`() {
        assertEquals(ToneWindow(210_000, 30_000), ToneWindow.moved(225_000, 30_000, fourMinutes))
    }

    @Test
    fun `a window dragged before the beginning moves to it`() {
        assertEquals(ToneWindow(0, 30_000), ToneWindow.moved(-3_000, 30_000, fourMinutes))
    }

    @Test
    fun `a window longer than the song covers the whole song`() {
        assertEquals(ToneWindow(0, 20_000), ToneWindow.moved(5_000, 30_000, 20_000))
    }

    // Lyric lines: made-up placeholder text, never real lyrics

    private val lines = listOf(
        LrcLine(0, ""),
        LrcLine(12_000, "line a"),
        LrcLine(15_500, "line b"),
        LrcLine(15_500, "line b, same time"),
        LrcLine(21_000, ""),
        LrcLine(30_000, "line c"),
        LrcLine(41_000, "line d"),
        LrcLine(44_000, "line e"),
        LrcLine(120_000, "line f"),
        LrcLine(239_500, "line g, under a second before the end"),
        LrcLine(250_000, "line h, past the end"),
    )

    @Test
    fun `the strip lists the lines with text inside the song, once per time`() {
        assertEquals(
            listOf(
                LrcLine(12_000, "line a"),
                LrcLine(15_500, "line b"),
                LrcLine(30_000, "line c"),
                LrcLine(41_000, "line d"),
                LrcLine(44_000, "line e"),
                LrcLine(120_000, "line f"),
            ),
            ToneLyrics.starts(lines, fourMinutes),
        )
    }

    @Test
    fun `tapping a line starts the window at its time`() {
        val line = ToneLyrics.starts(lines, fourMinutes)[2]
        assertEquals(ToneWindow(30_000, 30_000), ToneWindow.from(line.timeMs, ToneKind.RINGTONE.defaultLengthMs, fourMinutes))
    }

    // To the end of the line

    @Test
    fun `to the end of the line lets the line playing at the window's end finish`() {
        // From 12 s for 30 s ends at 42 s, inside the line from 41 s, which ends when the next starts at 44 s.
        assertEquals(ToneWindow(12_000, 32_000), ToneWindow.toLineEnd(12_000, 30_000, lines, fourMinutes))
    }

    @Test
    fun `a window that ends where a line starts already ends a line`() {
        assertEquals(ToneWindow(12_000, 29_000), ToneWindow.toLineEnd(12_000, 29_000, lines, fourMinutes))
    }

    @Test
    fun `a break counts as the end of the line before it`() {
        // From 15.5 s for 5 s ends at 20.5 s, inside line b, which ends at the break at 21 s.
        assertEquals(ToneWindow(15_500, 5_500), ToneWindow.toLineEnd(15_500, 5_000, lines, fourMinutes))
    }

    @Test
    fun `a line that would run past 60 seconds ends at the last line end that fits`() {
        // From 30 s for 60 s ends at 90 s, inside line e (44 s to 120 s): too long, so it ends with line d at 44 s.
        assertEquals(ToneWindow(30_000, 14_000), ToneWindow.toLineEnd(30_000, 60_000, lines, fourMinutes))
    }

    @Test
    fun `the last line ends with the song`() {
        val last = listOf(LrcLine(10_000, "line a"), LrcLine(200_000, "line b"))
        assertEquals(ToneWindow(200_000, 40_000), ToneWindow.toLineEnd(200_000, 30_000, last, fourMinutes))
    }

    @Test
    fun `without a line end in reach there is no such length`() {
        val sparse = listOf(LrcLine(0, "line a"), LrcLine(100_000, "line b"))
        assertNull(ToneWindow.toLineEnd(0, 30_000, sparse, fourMinutes))
        assertNull(ToneWindow.toLineEnd(0, 30_000, emptyList(), fourMinutes))
    }

    // The song's lyrics file

    @Test
    fun `the lines come from the lrc next to the song, which stays as it was`() {
        val song = folder.newFile("Paper Lanterns.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val lrc = File(folder.root, "Paper Lanterns.lrc").apply { writeText("[00:12.000]line a\n[00:15.500]line b\n[00:21.000]\n") }
        val lrcBefore = lrc.readBytes()

        assertEquals(listOf(LrcLine(12_000, "line a"), LrcLine(15_500, "line b"), LrcLine(21_000, "")), ToneLyrics.read(song.path))
        assertArrayEquals(byteArrayOf(1, 2, 3), song.readBytes())
        assertArrayEquals(lrcBefore, lrc.readBytes())
    }

    @Test
    fun `a song without an lrc, without an extension or with an oversized one has no lines`() {
        val song = folder.newFile("Glass Harbor.mp3")
        assertEquals(emptyList<LrcLine>(), ToneLyrics.read(song.path))
        assertEquals(emptyList<LrcLine>(), ToneLyrics.read(folder.newFile("no-extension").path))

        val big = folder.newFile("Low Orbit.mp3")
        File(folder.root, "Low Orbit.lrc").writeText("[00:01.00]x\n".repeat((ToneLyrics.MAX_LRC_BYTES / 12 + 1).toInt()))
        assertEquals(emptyList<LrcLine>(), ToneLyrics.read(big.path))
    }
}
