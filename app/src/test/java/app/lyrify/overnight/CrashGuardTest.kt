package app.lyrify.overnight

import app.lyrify.ai.AiStage
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.data.recorded
import app.lyrify.lyrics.LocalTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The overnight run's crash guard. All text here is made up. */
class CrashGuardTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private val day = 24 * 60 * 60_000L
    private val file: File get() = File(folder.root, "ai-crash-guard.json")

    private val logs = mutableListOf<String>()

    private fun guard(version: Int = 6) = CrashGuard(file, clock = { now }, version = version, log = { logs += it })

    private fun song(name: String, status: LyricsStatus = LyricsStatus.NOT_FOUND): LedgerEntry {
        val audio = File(folder.root, "$name.mp3")
        if (!audio.exists()) audio.writeBytes(byteArrayOf(1, 2, 3))
        return recorded(null, LocalTrack(audio.path, name, "Artist", null, 200_000), status, null, now)
    }

    /** A run of [entry] the app died in during [stage], found by the next run. */
    private fun die(entry: LedgerEntry, stage: AiStage, guard: CrashGuard = guard()) {
        guard.open(entry).entering(stage)
        guard.collectDeaths()
    }

    @Test
    fun `a run the app died in counts once, at the next run, with its stage`() {
        val entry = song("Song")
        guard().open(entry).entering(AiStage.SEPARATE)

        assertEquals(listOf(Death(entry.path, "Song", AiStage.SEPARATE, 1)), guard().collectDeaths())
        assertEquals(emptyList<Death>(), guard().collectDeaths())
        val again = guard().open(entry)
        assertEquals(1, again.deaths)
        assertEquals(AiStage.SEPARATE, again.diedIn)
    }

    @Test
    fun `a run that ended, finished or stopped, is no death`() {
        val entry = song("Song")
        guard().open(entry).run {
            entering(AiStage.TRANSCRIBE)
            ended()
        }

        assertEquals(emptyList<Death>(), guard().collectDeaths())
        assertEquals(0, guard().open(entry).deaths)
    }

    @Test
    fun `a run that failed as a crash would counts at once as a death in the stage it was in`() {
        val entry = song("Song")
        guard().open(entry).run {
            entering(AiStage.TRANSCRIBE)
            ended()
            died()
        }

        assertEquals(emptyList<Death>(), guard().collectDeaths())
        val again = guard().open(entry)
        assertEquals(1, again.deaths)
        assertEquals(AiStage.TRANSCRIBE, again.diedIn)
    }

    @Test
    fun `deaths are counted per song`() {
        val entry = song("Song")
        die(entry, AiStage.ALIGN)
        die(entry, AiStage.ALIGN)

        assertEquals(2, guard().open(entry).deaths)
        assertEquals(0, guard().open(song("Other")).deaths)
    }

    @Test
    fun `a run an update of the app killed is no death, and deaths count within one version`() {
        val entry = song("Song")
        die(entry, AiStage.SEPARATE, guard(version = 5))
        guard(version = 5).open(entry).entering(AiStage.TRANSCRIBE)

        assertEquals(emptyList<Death>(), guard(version = 6).collectDeaths())
        assertEquals(0, guard(version = 6).open(entry).deaths)
    }

    @Test
    fun `a song whose audio file or status changed starts with no deaths`() {
        val entry = song("Song")
        die(entry, AiStage.SEPARATE)
        assertEquals(1, guard().open(entry).deaths)

        assertEquals(0, guard().open(entry.copy(status = LyricsStatus.PLAIN_ONLY)).deaths)
        die(entry, AiStage.SEPARATE)
        File(entry.path).writeBytes(byteArrayOf(4, 5, 6, 7))
        assertEquals(0, guard().open(entry).deaths)
    }

    @Test
    fun `a record knows when its song's audio file was replaced after it was opened`() {
        val entry = song("Song")
        val record = guard().open(entry)
        assertFalse(record.audioChanged())
        File(entry.path).writeBytes(byteArrayOf(4, 5, 6, 7))
        assertTrue(record.audioChanged())
        File(entry.path).delete()
        assertTrue(record.audioChanged())
    }

    @Test
    fun `a song run to the end starts the deaths in a row over`() {
        die(song("A"), AiStage.ALIGN)
        die(song("A"), AiStage.ALIGN)

        guard().completed(song("C").path)
        die(song("B"), AiStage.ALIGN)

        assertEquals(GuardVerdict.NONE, guard().verdict())
    }

    @Test
    fun `three deaths in a row while timing lyrics turn timing off until the next version, songs run to the end or not`() {
        die(song("A"), AiStage.ALIGN)
        die(song("A"), AiStage.ALIGN)
        assertEquals(GuardVerdict.NONE, guard().verdict())
        die(song("B"), AiStage.ALIGN)
        assertEquals(GuardVerdict.NO_ALIGN, guard().verdict())

        guard().completed(song("C").path)
        guard().completed(song("D").path)

        assertEquals(GuardVerdict.NO_ALIGN, guard().verdict())
        assertEquals(GuardVerdict.NONE, guard(version = 7).verdict())
    }

    @Test
    fun `a stage turned off stays off while the other stages' deaths in a row start over`() {
        (1..3).forEach { die(song("Timed $it"), AiStage.ALIGN) }
        die(song("A"), AiStage.SEPARATE)
        die(song("B"), AiStage.SEPARATE)

        guard().completed(song("C").path)
        die(song("D"), AiStage.SEPARATE)

        // Timing stays off; separating has one death in a row, not three.
        assertEquals(GuardVerdict.NO_ALIGN, guard().verdict())
    }

    @Test
    fun `three deaths in a row in another stage hold the AI until the next version`() {
        die(song("A"), AiStage.SEPARATE)
        die(song("B"), AiStage.ALIGN)
        die(song("C"), AiStage.SEPARATE)
        assertEquals(GuardVerdict.NONE, guard().verdict())
        die(song("D"), AiStage.SEPARATE)

        assertEquals(GuardVerdict.HOLD, guard().verdict())
        guard().completed(song("E").path)
        assertEquals(GuardVerdict.HOLD, guard().verdict())
        assertEquals(GuardVerdict.NONE, guard(version = 7).verdict())
    }

    @Test
    fun `records of songs settled or changed, gone from the ledger or a week old are forgotten`() {
        val settled = song("Settled")
        val changed = song("Changed", LyricsStatus.PLAIN_ONLY)
        val gone = song("Gone")
        val old = song("Old")
        val waiting = song("Waiting")
        val guard = guard()
        guard.open(old)
        now += 8 * day
        listOf(settled, changed, gone, waiting).forEach { guard.open(it) }
        val ledger = LedgerState(
            entries = listOf(
                settled.copy(status = LyricsStatus.AI_TRANSCRIBED),
                changed.copy(status = LyricsStatus.NOT_FOUND),
                old,
                waiting,
            ).associateBy { it.path },
        )

        guard.prune(ledger)

        assertEquals(setOf(waiting.path), guard.paths())
        now += 8 * day
        guard.prune(ledger)
        assertEquals(emptySet<String>(), guard.paths())
    }

    @Test
    fun `a completed or dropped song's record goes`() {
        val a = song("A")
        val b = song("B")
        guard().open(a)
        guard().open(b)

        guard().completed(a.path)
        guard().drop(b.path)

        assertEquals(emptySet<String>(), guard().paths())
    }

    @Test
    fun `a file cut short or of another format is read as no record, and written whole again`() {
        file.writeText("{\"version\": 6, \"songs\": {")
        assertEquals(GuardVerdict.NONE, guard().verdict())
        die(song("A"), AiStage.ALIGN)
        assertEquals(1, guard().open(song("A")).deaths)
        assertFalse(File(file.path + ".tmp").exists())
    }
}
