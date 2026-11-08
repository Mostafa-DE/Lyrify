package app.lyrify.ui

import app.lyrify.R
import app.lyrify.dupes.AudioFile
import app.lyrify.dupes.Confidence
import app.lyrify.dupes.DuplicateGroup
import app.lyrify.dupes.DuplicatesMemory
import app.lyrify.dupes.DuplicatesState
import app.lyrify.dupes.ScanResults
import app.lyrify.dupes.ScanStatus
import app.lyrify.dupes.TrashedSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class DuplicatesViewTest {
    private val zone = ZoneId.of("Asia/Amman")

    private fun file(id: Long, size: Long = 1_000, folder: String? = "Music/kjb/", name: String = "03 Paper Comet.mp3", mimeType: String? = "audio/mpeg", bitrate: Long? = 320_000) =
        AudioFile(
            id = id, path = "/storage/emulated/0/${folder.orEmpty()}$name", relativePath = folder, displayName = name, title = "Paper Comet",
            artist = "Mira Solen", durationMs = 214_000, size = size, mimeType = mimeType, bitrate = bitrate, addedAt = 0,
        )

    private val identical = DuplicateGroup(Confidence.IDENTICAL, listOf(file(1), file(2)))
    private val maybe = DuplicateGroup(Confidence.MAYBE, listOf(file(3), file(4)))
    private val results = ScanResults(scannedAt = 1, scanned = 1_358, groups = listOf(identical, maybe))
    private val trashed = TrashedSong(file(5), trashedAt = 1, expiresAt = 2)

    private fun state(scan: ScanStatus = ScanStatus.Idle, results: ScanResults? = null, reviewed: Set<String> = emptySet(), loaded: Boolean = true) =
        DuplicatesState(loaded = loaded, scan = scan, memory = DuplicatesMemory(results = results, reviewed = reviewed, trashed = listOf(trashed)))

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    // Each state

    @Test
    fun `nothing shows until the stored results are read`() {
        assertEquals(DupesView.Loading, dupesView(state(loaded = false)))
    }

    @Test
    fun `before the first scan the tab asks to scan`() {
        assertEquals(DupesView.FirstScan, dupesView(state()))
    }

    @Test
    fun `a running scan shows its ring, with or without results before`() {
        assertEquals(DupesView.Scanning(412, 1_358), dupesView(state(scan = ScanStatus.Running(412, 1_358))))
        assertEquals(DupesView.Scanning(0, 0), dupesView(state(scan = ScanStatus.Running(0, 0), results = results)))
        assertEquals(0.5f, DupesView.Scanning(679, 1_358).fraction)
        assertEquals(0f, DupesView.Scanning(0, 0).fraction)
    }

    @Test
    fun `a failed scan without results says so, and with results keeps them`() {
        assertEquals(DupesView.Failed, dupesView(state(scan = ScanStatus.Failed)))
        assertEquals(DupesView.Results(results, listOf(trashed), failed = true), dupesView(state(scan = ScanStatus.Failed, results = results)))
    }

    @Test
    fun `results come with the songs in the trash`() {
        assertEquals(DupesView.Results(results, listOf(trashed), failed = false), dupesView(state(results = results)))
    }

    // Badge and sections

    @Test
    fun `the badge counts the groups not opened yet`() {
        assertEquals(0, state().badge)
        assertEquals(2, state(results = results).badge)
        assertEquals(1, state(results = results, reviewed = setOf(identical.key)).badge)
        assertEquals(0, state(results = results, reviewed = setOf(identical.key, maybe.key)).badge)
    }

    @Test
    fun `sections count their groups, in the order of confidence`() {
        assertEquals(mapOf(Confidence.IDENTICAL to 1, Confidence.SAME_SONG to 0, Confidence.MAYBE to 1), results.counts())
        assertEquals(listOf(R.string.dupes_section_identical, R.string.dupes_section_same, R.string.dupes_section_maybe), Confidence.entries.map(::sectionTitle))
    }

    @Test
    fun `each level has its own words for TalkBack and the sheet`() {
        assertEquals(listOf(R.string.dupes_level_identical, R.string.dupes_level_same, R.string.dupes_level_maybe), Confidence.entries.map(::levelDescription))
        assertEquals(listOf(R.string.dupes_sheet_identical, R.string.dupes_sheet_same, R.string.dupes_sheet_maybe), Confidence.entries.map(::groupSentence))
    }

    // The group sheet

    @Test
    fun `move to trash needs at least one copy checked and at least one left`() {
        assertEquals(listOf(false, true, true, false), (0..3).map { canMove(checked = it, copies = 3) })
        assertEquals(listOf(false, false, false, true), (0..3).map { mustKeepOne(checked = it, copies = 3) })
        assertEquals(false, canMove(checked = 2, copies = 2))
        assertEquals(true, mustKeepOne(checked = 2, copies = 2))
    }

    // Copy

    @Test
    fun `the last scan reads today, yesterday or its date`() {
        val now = at(2026, 11, 6, 20, 15)
        assertEquals(ScannedDay.TODAY, scannedDay(at(2026, 11, 6, 0, 1), now, zone))
        assertEquals(ScannedDay.YESTERDAY, scannedDay(at(2026, 11, 5, 23, 59), now, zone))
        assertEquals(ScannedDay.EARLIER, scannedDay(at(2026, 11, 4, 18, 40), now, zone))
        assertEquals(R.string.dupes_scanned_today, ScannedDay.TODAY.label)
        assertEquals(R.string.dupes_scanned_yesterday, ScannedDay.YESTERDAY.label)
        assertEquals(R.string.dupes_scanned_on, ScannedDay.EARLIER.label)
    }

    @Test
    fun `TalkBack hears each quarter of a scan once, never every file`() {
        assertNull(scanMilestone(0, 1_358))
        assertNull(scanMilestone(339, 1_358))
        assertEquals(25, scanMilestone(340, 1_358))
        assertEquals(25, scanMilestone(678, 1_358))
        assertEquals(50, scanMilestone(679, 1_358))
        assertEquals(75, scanMilestone(1_357, 1_358))
        assertNull(scanMilestone(1_358, 1_358))
        assertNull(scanMilestone(0, 0))
    }

    @Test
    fun `a file reads as its folder and name, its format and its bitrate`() {
        assertEquals("kjb/03 Paper Comet.mp3", fileLocation(file(1)))
        assertEquals("03 Paper Comet.mp3", fileLocation(file(1, folder = null)))
        assertEquals("de/03 Paper Comet.mp3", fileLocation(file(1, folder = "Music/de/")))
        assertEquals("MP3", fileFormat(file(1)))
        assertEquals("FLAC", fileFormat(file(1, name = "Paper Comet.flac")))
        assertEquals("OGG", fileFormat(file(1, name = "Paper Comet", mimeType = "audio/ogg")))
        assertEquals(320, fileKbps(file(1)))
        assertEquals(256, fileKbps(file(1, bitrate = 255_600)))
        assertNull(fileKbps(file(1, bitrate = null)))
        assertNull(fileKbps(file(1, bitrate = 0)))
    }
}
