package app.lyrify.dupes

import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.FakeLrclib
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrclibRecord
import app.lyrify.lyrics.LrclibResult
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.MemoryLedger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Moving copies to the phone's trash through the real lyrics pipeline and ledger, with the phone faked: its trash renames
 * the file as MediaProvider does, and its dialog's answer is what the test says.
 */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler, advanceUntilIdle
class DuplicatesTrashTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()

    /** The phone's trash: the ids in it, with when the phone deletes each. */
    private val phoneTrash = mutableMapOf<Long, Long?>()

    /** Placeholder words, never real lyrics. */
    private val synced = "[00:01.00]w0001\n[00:02.00]w0002\n[00:03.00]w0003\n[00:04.00]w0004\n[00:05.00]w0005\n[00:06.00]w0006"

    private fun TestScope.pipeline() = LyricsPipeline(client = client, ledger = ledger, clock = { BASE + testScheduler.currentTime })

    private fun copy(id: Long, folderName: String): AudioFile {
        val file = File(folder.root, "Music/$folderName/Song A.mp3")
        file.parentFile?.mkdirs()
        file.writeText("the same audio bytes")
        return AudioFile(
            id = id, path = file.path, relativePath = "Music/$folderName/", displayName = file.name, title = "Song A", artist = "Band A",
            album = null, durationMs = 200_000, size = file.length(), mimeType = "audio/mpeg", bitrate = 128_000, addedAt = BASE + id,
        )
    }

    private fun lrc(file: AudioFile) = File(LrcFormat.sidecarPath(file.path) ?: error("no sidecar"))

    /** Keep 1 and copy 2 have lyrics Lyrify wrote; copy 3 has the user's own `.lrc`. */
    private suspend fun TestScope.library(): List<AudioFile> {
        val files = listOf(copy(1, "a"), copy(2, "b"), copy(3, "c"))
        lrc(files[2]).writeText("[00:01.00]the user's own")
        client.onGet = { LrclibResult.Found(listOf(LrclibRecord(1, "Song A", "Band A", null, 200.0, plainLyrics = "w0001", syncedLyrics = synced))) }
        val pipeline = pipeline()
        val statuses = files.map { pipeline.process(LocalTrack(it.path, it.title, it.artist, it.album, it.durationMs))?.status }
        assertEquals(listOf(LyricsStatus.SYNCED, LyricsStatus.SYNCED, LyricsStatus.EXISTING), statuses)
        return files
    }

    private fun TestScope.engine(files: List<AudioFile>, pipeline: LyricsPipeline) = Duplicates(
        library = object : AudioLibrary {
            override fun read(): LibraryRead = LibraryRead.Files(files)

            override fun rows(ids: Collection<Long>): Map<Long, AudioFile> = files.filter { it.id in ids }.associateBy { it.id }
        },
        store = DuplicatesStore(File(folder.root, "app/duplicates.json")),
        cleanup = { pipeline.trashed(it) },
        phoneTrash = { ids -> phoneTrash.filterKeys { it in ids } },
        scope = this,
        io = StandardTestDispatcher(testScheduler),
        clock = { BASE + testScheduler.currentTime },
        hasher = ContentHasher(sliceBytes = 4, fullLimit = 64),
    )

    /** The phone's trash, as MediaProvider does it: the file renamed to `.trashed-<expiry>-<name>`. */
    private fun trashOnDisk(file: AudioFile) {
        val audio = File(file.path)
        assertTrue(audio.renameTo(File(audio.parentFile, ".trashed-1765000000-${audio.name}")))
        phoneTrash[file.id] = BASE + 30 * DAY
    }

    /** Every `.lrc` with its text, and every ledger entry. */
    private fun snapshot(files: List<AudioFile>) = files.map { lrc(it).takeIf(File::isFile)?.readText() to ledger[it.path] }

    private suspend fun TestScope.scannedGroup(engine: Duplicates): DuplicateGroup {
        engine.scan()
        advanceUntilIdle()
        val group = engine.state.value.results?.groups?.single() ?: error("no group")
        assertEquals(1L, group.keep.id)
        return group
    }

    @Test
    fun `the phone's OK moves the checked copies, and only the lyrics Lyrify wrote for them go`() = runTest {
        val files = library()
        val engine = engine(files, pipeline())
        val group = scannedGroup(engine)
        val before = snapshot(files)

        assertTrue(engine.askTrash(group, setOf(2L, 3L)))
        assertEquals(Confirmation.Ask(listOf(2L, 3L)), engine.confirmTrash())
        trashOnDisk(files[1])
        trashOnDisk(files[2])
        // Nothing is cleaned up before the phone's answer, though the files are in its trash already.
        assertEquals(before, snapshot(files))

        assertEquals(Trashed.Moved(2), engine.systemAnswered(approved = true))
        assertTrue(File(files[0].path).isFile)
        assertEquals(before[0], snapshot(files)[0])
        assertFalse(lrc(files[1]).exists())
        assertNull(ledger[files[1].path])
        assertEquals("[00:01.00]the user's own", lrc(files[2]).readText())
        assertNull(ledger[files[2].path])
        assertTrue(engine.state.value.results?.groups.orEmpty().isEmpty())
    }

    @Test
    fun `the phone's cancel changes nothing`() = runTest {
        val files = library()
        val engine = engine(files, pipeline())
        val group = scannedGroup(engine)
        val before = snapshot(files)
        engine.askTrash(group, setOf(2L, 3L))
        engine.confirmTrash()
        assertEquals(Trashed.Unchanged, engine.systemAnswered(approved = false))
        assertEquals(before, snapshot(files))
        assertTrue(files.all { File(it.path).isFile })
        assertEquals(group, engine.state.value.results?.groups?.single())
    }

    @Test
    fun `cancel in Lyrify's dialog changes nothing and asks the phone nothing`() = runTest {
        val files = library()
        val engine = engine(files, pipeline())
        val group = scannedGroup(engine)
        val before = snapshot(files)
        engine.askTrash(group, setOf(2L))
        engine.cancelTrash()
        assertEquals(Confirmation.NotAsked, engine.confirmTrash())
        assertEquals(Trashed.Unchanged, engine.systemAnswered(approved = true))
        assertEquals(before, snapshot(files))
    }

    @Test
    fun `a song restored from the trash gets the lyrics Lyrify wrote for it again`() = runTest {
        val files = library()
        val pipeline = pipeline()
        val engine = engine(files, pipeline)
        engine.askTrash(scannedGroup(engine), setOf(2L))
        engine.confirmTrash()
        trashOnDisk(files[1])
        engine.systemAnswered(approved = true)
        assertFalse(lrc(files[1]).exists())

        val trashed = engine.state.value.trashed.single()
        assertEquals(listOf(2L), engine.restore(trashed))
        val audio = File(files[1].path)
        assertTrue(File(audio.parentFile, ".trashed-1765000000-${audio.name}").renameTo(audio))
        phoneTrash.remove(2L)
        assertEquals(Restored.Back, engine.restoreAnswered(approved = true))
        val track = files[1].let { LocalTrack(it.path, it.title, it.artist, it.album, it.durationMs) }
        assertEquals(LyricsStatus.SYNCED, pipeline.process(track)?.status)
        assertTrue(lrc(files[1]).isFile)
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000
        const val DAY: Long = 24 * 60 * 60_000L
    }
}
