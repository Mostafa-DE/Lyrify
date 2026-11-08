package app.lyrify.lyrics

import app.lyrify.data.LyricsStatus
import app.lyrify.data.recorded
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A song the user moved to the phone's trash from the Duplicates tab ([LyricsPipeline.trashed]). */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class LyricsPipelineTrashTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()

    /** Placeholder words, never real lyrics. */
    private val synced = "[00:01.00]w0001\n[00:02.00]w0002\n[00:03.00]w0003\n[00:04.00]w0004\n[00:05.00]w0005\n[00:06.00]w0006"

    private fun record(id: Long = 1) = LrclibRecord(id, "Song A", "Band A", "Album A", 200.0, plainLyrics = "w0001", syncedLyrics = synced)

    private val logs = mutableListOf<String>()

    private fun TestScope.pipeline() =
        LyricsPipeline(client = client, ledger = ledger, clock = { BASE + testScheduler.currentTime }, log = { logs += it }, warn = { logs += it })

    private fun audio(name: String = "Song A.mp3"): LocalTrack {
        val file = File(folder.root, name)
        file.writeBytes(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 5, 6, 7))
        return LocalTrack(file.path, "Song A", "Band A", null, 200_000)
    }

    private fun sidecar(track: LocalTrack) = File(LrcFormat.sidecarPath(track.path) ?: error("no sidecar"))

    /** The song with lyrics Lyrify wrote, as a lookup leaves it. */
    private suspend fun LyricsPipeline.withLyrics(track: LocalTrack = audio()): LocalTrack {
        client.onGet = { LrclibResult.Found(listOf(record())) }
        assertEquals(LyricsStatus.SYNCED, process(track)?.status)
        assertTrue(sidecar(track).isFile)
        return track
    }

    /** As MediaProvider trashes a file: renamed to `.trashed-<expiry>-<name>` in its folder. */
    private fun trashOnDisk(track: LocalTrack) {
        val audio = File(track.path)
        assertTrue(audio.renameTo(File(audio.parentFile, ".trashed-1765000000-${audio.name}")))
    }

    @Test
    fun `a trashed song loses the lyrics Lyrify wrote and its ledger entry`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.withLyrics()
        trashOnDisk(track)
        assertEquals(Removal.REMOVED, pipeline.trashed(track.path))
        assertFalse(sidecar(track).exists())
        assertNull(ledger[track.path])
    }

    @Test
    fun `a lyrics file Lyrify did not write stays, and the entry goes`() = runTest {
        val track = audio()
        sidecar(track).writeText("[00:01.00]mine")
        ledger.put(recorded(null, track, LyricsStatus.EXISTING, null, BASE))
        trashOnDisk(track)
        assertEquals(Removal.NOT_REMOVABLE, pipeline().trashed(track.path))
        assertEquals("[00:01.00]mine", sidecar(track).readText())
        assertNull(ledger[track.path])
    }

    @Test
    fun `lyrics Lyrify wrote and someone changed since stay, and the entry goes`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.withLyrics()
        sidecar(track).appendText("[00:09.00]edited by hand\n")
        val edited = sidecar(track).readBytes()
        trashOnDisk(track)
        assertEquals(Removal.CHANGED, pipeline.trashed(track.path))
        assertArrayEquals(edited, sidecar(track).readBytes())
        assertNull(ledger[track.path])
    }

    @Test
    fun `lyrics already gone only drop the entry`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.withLyrics()
        sidecar(track).delete()
        trashOnDisk(track)
        assertEquals(Removal.ALREADY_GONE, pipeline.trashed(track.path))
        assertNull(ledger[track.path])
    }

    @Test
    fun `lyrics that cannot be deleted keep the entry, so they can still be removed from the app`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.withLyrics()
        trashOnDisk(track)
        assumeTrue(folder.root.setWritable(false) && !folder.root.canWrite())
        try {
            assertEquals(Removal.FAILED, pipeline.trashed(track.path))
        } finally {
            folder.root.setWritable(true)
        }
        assertTrue(sidecar(track).isFile)
        assertNotNull(ledger[track.path])
        assertTrue(canRemove(ledger[track.path] ?: error("no entry")))
    }

    @Test
    fun `the log of a trashed song names no song and no lyrics file`() = runTest {
        val pipeline = pipeline()
        val removed = pipeline.withLyrics(audio("Song A.mp3"))
        val changed = pipeline.withLyrics(audio("Song B.mp3"))
        val stuck = pipeline.withLyrics(audio("Song C.mp3"))
        sidecar(changed).appendText("[00:09.00]edited by hand\n")
        listOf(removed, changed, stuck).forEach(::trashOnDisk)
        logs.clear()
        assertEquals(Removal.REMOVED, pipeline.trashed(removed.path))
        assertEquals(Removal.CHANGED, pipeline.trashed(changed.path))
        assumeTrue(folder.root.setWritable(false) && !folder.root.canWrite())
        try {
            assertEquals(Removal.FAILED, pipeline.trashed(stuck.path))
        } finally {
            folder.root.setWritable(true)
        }
        assertTrue(logs.isNotEmpty())
        for (line in logs) assertFalse(line, line.contains("Song") || line.contains(".lrc") || line.contains(folder.root.path))
    }

    @Test
    fun `confirmed trash cleans up even while the audio remains at its path`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.withLyrics()
        assertEquals(Removal.REMOVED, pipeline.trashed(track.path))
        assertFalse(sidecar(track).exists())
        assertTrue(File(track.path).isFile)
        assertNull(ledger[track.path])
    }

    @Test
    fun `a song the ledger does not know changes nothing`() = runTest {
        val track = audio()
        trashOnDisk(track)
        assertEquals(Removal.NOT_REMOVABLE, pipeline().trashed(track.path))
        assertNull(ledger[track.path])
    }

    @Test
    fun `a song restored from the trash is new to Lyrify and gets its lyrics again`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.withLyrics()
        trashOnDisk(track)
        pipeline.trashed(track.path)
        assertTrue(File(folder.root, ".trashed-1765000000-Song A.mp3").renameTo(File(track.path)))
        val asked = client.requests
        assertEquals(LyricsStatus.SYNCED, pipeline.process(track)?.status)
        assertTrue(client.requests > asked)
        assertTrue(sidecar(track).isFile)
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000
    }
}
