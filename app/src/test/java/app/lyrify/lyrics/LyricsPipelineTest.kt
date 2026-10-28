package app.lyrify.lyrics

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsStatus
import app.lyrify.data.Retry
import app.lyrify.data.recorded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class LyricsPipelineTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** Jitter in the middle, so session retries wait exactly 2, 4 and 8 s. */
    private object MiddleRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = 0.5
    }

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()
    private val logs = mutableListOf<String>()
    private val warnings = mutableListOf<String>()

    private val synced = "[00:01.00]Beloved\n[00:02.50]Nemo\n[00:04.13][01:00.00]Sailing home"
    private val expectedLrc = "[00:01.000]Beloved\n[00:02.500]Nemo\n[00:04.130]Sailing home\n[01:00.000]Sailing home\n"

    private fun record(id: Long = 42, duration: Double = 276.0, synced: String? = this.synced, plain: String? = "Beloved\nNemo") =
        LrclibRecord(id, "Nemo", "Nightwish", "Once", duration, instrumental = false, plainLyrics = plain, syncedLyrics = synced)

    private fun TestScope.pipeline(writeBytes: (FileChannel, ByteBuffer) -> Unit = SidecarFile::writeFully) = LyricsPipeline(
        client = client,
        ledger = ledger,
        clock = { BASE + testScheduler.currentTime },
        random = MiddleRandom,
        log = { logs += it },
        warn = { warnings += it },
        writeBytes = writeBytes,
    )

    private suspend fun LyricsPipeline.status(track: LocalTrack, lane: Lane = Lane.HIGH, force: Boolean = false): LyricsStatus? =
        process(track, lane, force)?.status

    private val audioBytes = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 1, 2, 3)

    private fun audio(name: String = "Nemo.mp3", title: String = "Nemo"): LocalTrack {
        val file = File(folder.root, name)
        file.writeBytes(audioBytes)
        file.setLastModified(AUDIO_MTIME)
        return LocalTrack(file.path, title, "Nightwish", "Once", 276_000)
    }

    private fun sidecar(track: LocalTrack) = File(LrcFormat.sidecarPath(track.path) ?: error("no sidecar"))

    private fun assertAudioUntouched(track: LocalTrack) {
        val file = File(track.path)
        assertArrayEquals(audioBytes, file.readBytes())
        assertEquals(AUDIO_MTIME, file.lastModified())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A symbolic link, or the test is skipped where the file system has none. */
    private fun symlink(link: File, target: File) {
        val created = try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IOException) {
            false
        }
        assumeTrue("symbolic links unavailable", created)
    }

    // Writing

    @Test
    fun `synced lyrics are written next to the audio file`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record())) }

        assertEquals(LyricsStatus.SYNCED, pipeline().status(track))

        assertEquals(expectedLrc, sidecar(track).readText(Charsets.UTF_8))
        assertEquals(LyricsStatus.SYNCED, ledger[track.path]?.status)
        assertEquals(42L, ledger[track.path]?.lrclibId)
        assertEquals(setOf("Nemo.mp3", "Nemo.lrc"), folder.root.list().orEmpty().toSet())
        assertAudioUntouched(track)
    }

    @Test
    fun `the ledger keeps the sha256 of the bytes written and when`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record())) }
        delay(5_000)

        pipeline().process(track)

        assertEquals(sha256(sidecar(track).readBytes()), ledger[track.path]?.sha256)
        assertEquals(BASE + 5_000, ledger[track.path]?.writtenAt)
    }

    @Test
    fun `the duration is sent in whole seconds`() = runTest {
        val track = audio().copy(durationMs = 275_600)
        pipeline().process(track)
        assertEquals(276L, client.durations.first())
    }

    @Test
    fun `requests go out in the lane of the work`() = runTest {
        val pipeline = pipeline()
        pipeline.process(audio(), Lane.LOW)
        assertEquals(setOf(Lane.LOW), client.lanes.toSet())
        pipeline.process(audio("Other.mp3", "Other"), Lane.HIGH)
        assertEquals(Lane.HIGH, client.lanes.last())
    }

    @Test
    fun `lyrics are written once`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record())) }
        val pipeline = pipeline()
        pipeline.process(track)
        sidecar(track).setLastModified(AUDIO_MTIME)
        val requests = client.requests

        assertEquals(LyricsStatus.SYNCED, pipeline.status(track, force = true))

        assertEquals(requests, client.requests)
        assertEquals(AUDIO_MTIME, sidecar(track).lastModified())
        assertEquals(expectedLrc, sidecar(track).readText())
    }

    @Test
    fun `an existing sidecar is never overwritten or looked up`() = runTest {
        val track = audio()
        sidecar(track).writeText("[00:01.000]mine\n")
        client.onGet = { LrclibResult.Found(listOf(record())) }

        assertEquals(LyricsStatus.EXISTING, pipeline().status(track))

        assertEquals("[00:01.000]mine\n", sidecar(track).readText())
        assertEquals(0, client.requests)
        assertEquals(LyricsStatus.EXISTING, ledger[track.path]?.status)
        assertAudioUntouched(track)
    }

    @Test
    fun `a sidecar that appears during the lookup is kept`() = runTest {
        val track = audio()
        client.onGet = {
            sidecar(track).writeText("[00:01.000]someone else\n")
            LrclibResult.Found(listOf(record()))
        }

        assertEquals(LyricsStatus.EXISTING, pipeline().status(track))

        assertEquals("[00:01.000]someone else\n", sidecar(track).readText())
        assertEquals(setOf("Nemo.mp3", "Nemo.lrc"), folder.root.list().orEmpty().toSet())
        assertAudioUntouched(track)
    }

    @Test
    fun `a symbolic link at the sidecar path counts as a sidecar, even a dangling one`() = runTest {
        val track = audio()
        val target = File(folder.root, "elsewhere.txt")
        symlink(sidecar(track), target)
        client.onGet = { LrclibResult.Found(listOf(record())) }

        assertEquals(LyricsStatus.EXISTING, pipeline().status(track))

        assertEquals(0, client.requests)
        assertTrue(Files.isSymbolicLink(sidecar(track).toPath()))
        assertFalse(target.exists())
        assertAudioUntouched(track)
    }

    @Test
    fun `a symbolic link planted during the lookup is not followed`() = runTest {
        val track = audio()
        val victim = File(folder.root, "victim.txt").apply { writeText("keep me") }
        client.onGet = {
            symlink(sidecar(track), victim)
            LrclibResult.Found(listOf(record()))
        }

        assertEquals(LyricsStatus.EXISTING, pipeline().status(track))

        assertEquals("keep me", victim.readText())
        assertTrue(Files.isSymbolicLink(sidecar(track).toPath()))
        assertAudioUntouched(track)
    }

    @Test
    fun `a write that fails after the create leaves nothing behind`() {
        val path = File(folder.root, "Nemo.lrc").toPath()
        val bytes = expectedLrc.encodeToByteArray()

        val created = SidecarFile.create(path, bytes, log = { logs += it }) { channel, _ ->
            channel.write(ByteBuffer.wrap(bytes, 0, 5))
            throw IOException("No space left on device")
        }

        assertEquals(Created.FAILED, created)
        assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `anything thrown during the write deletes the partial file and is passed on`() {
        val path = File(folder.root, "Nemo.lrc").toPath()
        val bytes = expectedLrc.encodeToByteArray()

        listOf(OutOfMemoryError("simulated"), CancellationException("listener gone")).forEach { failure ->
            val thrown = assertThrows(Throwable::class.java) {
                SidecarFile.create(path, bytes, log = { logs += it }) { channel, _ ->
                    channel.write(ByteBuffer.wrap(bytes, 0, 5))
                    throw failure
                }
            }

            assertSame(failure, thrown)
            assertFalse(Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `creating over an existing file changes nothing`() {
        val path = File(folder.root, "Nemo.lrc").apply { writeText("mine") }.toPath()

        assertEquals(Created.ALREADY_THERE, SidecarFile.create(path, expectedLrc.encodeToByteArray(), log = { logs += it }))

        assertEquals("mine", path.toFile().readText())
    }

    @Test
    fun `a folder the app cannot write to is an error without leftovers`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record())) }
        assumeTrue(folder.root.setWritable(false) && !folder.root.canWrite())
        try {
            assertEquals(LyricsStatus.ERROR, pipeline().status(track))
        } finally {
            folder.root.setWritable(true)
        }
        assertFalse(sidecar(track).exists())
        assertEquals(setOf("Nemo.mp3"), folder.root.list().orEmpty().toSet())
        assertAudioUntouched(track)
    }

    // Writes the app's death interrupts

    private val lrcBytes: ByteArray get() = expectedLrc.encodeToByteArray()

    /** The ledger as a write of [expectedLrc] for [track], begun at [BASE], left it when the app died. */
    private suspend fun interrupted(track: LocalTrack): LocalTrack {
        val marker = recorded(null, track, LyricsStatus.WRITING, 42, BASE, sha256 = sha256(lrcBytes), size = lrcBytes.size.toLong(), pendingText = expectedLrc)
        ledger.put(marker)
        return track
    }

    private fun File.write(bytes: ByteArray, modifiedAt: Long) {
        writeBytes(bytes)
        setLastModified(modifiedAt)
    }

    @Test
    fun `the write is marked in the ledger before the file is created`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record())) }
        delay(5_000)
        var marker: LedgerEntry? = null
        ledger.accepts = { entry ->
            if (entry.status == LyricsStatus.WRITING) {
                assertFalse(sidecar(track).exists())
                marker = entry
            }
            true
        }

        pipeline().process(track)

        assertEquals(sha256(lrcBytes), marker?.sha256)
        assertEquals(lrcBytes.size.toLong(), marker?.size)
        assertEquals(BASE + 5_000, marker?.writtenAt)
        assertEquals(42L, marker?.lrclibId)
        assertEquals(expectedLrc, marker?.pendingText)
        assertEquals(LyricsStatus.SYNCED, ledger[track.path]?.status)
        assertEquals(lrcBytes.size.toLong(), ledger[track.path]?.size)
        assertNull(ledger[track.path]?.pendingText)
    }

    @Test
    fun `no file is created when the write cannot be marked in the ledger`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record())) }
        ledger.accepts = { it.status != LyricsStatus.WRITING }

        assertEquals(LyricsStatus.ERROR, pipeline().status(track))

        assertFalse(sidecar(track).exists())
        assertEquals(LyricsStatus.ERROR, ledger[track.path]?.status)
        assertAudioUntouched(track)
    }

    @Test
    fun `a write whose partial file cannot be deleted keeps its marker, and the next look finishes it`() = runTest {
        val track = audio()
        assumeTrue(folder.root.setWritable(false) && !folder.root.canWrite())
        folder.root.setWritable(true)
        client.onGet = { LrclibResult.Found(listOf(record())) }
        var failing = true
        val pipeline = pipeline { channel, buffer ->
            if (failing) {
                failing = false
                channel.write(ByteBuffer.wrap(lrcBytes, 0, 20))
                folder.root.setWritable(false) // so the partial file cannot be deleted
                throw IOException("No space left on device")
            }
            while (buffer.hasRemaining()) channel.write(buffer)
        }
        val status = try {
            pipeline.status(track)
        } finally {
            folder.root.setWritable(true)
        }

        assertEquals(LyricsStatus.WRITING, status)
        assertEquals(LyricsStatus.WRITING, ledger[track.path]?.status)
        assertArrayEquals(lrcBytes.copyOf(20), sidecar(track).readBytes())

        assertEquals(LyricsStatus.SYNCED, pipeline.status(track))
        assertEquals(expectedLrc, sidecar(track).readText())
        assertAudioUntouched(track)
    }

    @Test
    fun `a file that appeared during the lookup is never deleted later, even if the ledger fails to record it`() = runTest {
        val track = audio()
        client.onGet = {
            sidecar(track).writeBytes(ByteArray(0)) // empty: a prefix of any lyrics
            LrclibResult.Found(listOf(record()))
        }
        ledger.accepts = { it.status != LyricsStatus.EXISTING }
        val pipeline = pipeline()

        assertEquals(LyricsStatus.EXISTING, pipeline.status(track))
        assertEquals(LyricsStatus.WRITING, ledger[track.path]?.status)
        assertNull(ledger[track.path]?.pendingText)

        ledger.accepts = { true }
        assertEquals(LyricsStatus.EXISTING, pipeline.status(track))

        assertTrue(sidecar(track).exists())
        assertEquals(0L, sidecar(track).length())
        assertEquals(1, client.requests)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `a foreign file the ledger cannot record at all is warned about`() = runTest {
        val track = audio()
        client.onGet = {
            sidecar(track).writeBytes(ByteArray(0))
            LrclibResult.Found(listOf(record()))
        }
        ledger.accepts = { it.status != LyricsStatus.EXISTING && !(it.status == LyricsStatus.WRITING && it.pendingText == null) }

        assertEquals(LyricsStatus.EXISTING, pipeline().status(track))

        assertTrue(warnings.any { "Nemo" in it })
        assertTrue(sidecar(track).exists())
    }

    @Test
    fun `an interrupted write whose file never appeared is looked up again`() = runTest {
        val track = interrupted(audio())
        client.onGet = { LrclibResult.Found(listOf(record())) }

        assertEquals(LyricsStatus.SYNCED, pipeline().status(track))

        assertEquals(1, client.requests)
        assertEquals(expectedLrc, sidecar(track).readText())
        assertAudioUntouched(track)
    }

    @Test
    fun `a complete file whose final ledger entry was lost is recorded as written, without asking LRCLIB`() = runTest {
        val track = interrupted(audio())
        sidecar(track).write(lrcBytes, BASE)

        assertEquals(LyricsStatus.SYNCED, pipeline().status(track))

        assertEquals(0, client.requests)
        assertEquals(expectedLrc, sidecar(track).readText())
        assertEquals(BASE, sidecar(track).lastModified())
        assertEquals(42L, ledger[track.path]?.lrclibId)
        assertTrue(ledger[track.path]?.removable == true)
        assertNull(ledger[track.path]?.pendingText)
        assertAudioUntouched(track)
    }

    @Test
    fun `a lost final ledger entry is recovered the next time the track is seen`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record())) }
        ledger.accepts = { it.status != LyricsStatus.SYNCED }
        val pipeline = pipeline()
        pipeline.process(track)
        assertEquals(LyricsStatus.WRITING, ledger[track.path]?.status)

        ledger.accepts = { true }
        assertEquals(LyricsStatus.SYNCED, pipeline.status(track))

        assertEquals(1, client.requests)
        assertEquals(expectedLrc, sidecar(track).readText())
    }

    @Test
    fun `an exact prefix of an interrupted write, however old, is deleted and written again`() = runTest {
        val track = interrupted(audio())
        sidecar(track).write(lrcBytes.copyOf(20), BASE - DAY)
        client.onGet = { LrclibResult.Found(listOf(record())) }

        assertEquals(LyricsStatus.SYNCED, pipeline().status(track))

        assertEquals(1, client.requests)
        assertEquals(expectedLrc, sidecar(track).readText())
        assertEquals(setOf("Nemo.mp3", "Nemo.lrc"), folder.root.list().orEmpty().toSet())
        assertNull(ledger[track.path]?.pendingText)
        assertAudioUntouched(track)
    }

    @Test
    fun `an empty file of an interrupted write is a prefix too`() = runTest {
        val track = interrupted(audio())
        sidecar(track).write(ByteArray(0), BASE)
        client.onGet = { LrclibResult.Found(listOf(record())) }

        assertEquals(LyricsStatus.SYNCED, pipeline().status(track))

        assertEquals(expectedLrc, sidecar(track).readText())
        assertAudioUntouched(track)
    }

    @Test
    fun `a smaller foreign file made after an interrupted write is left alone`() = runTest {
        // The app died after the WRITING entry, before the create; then someone else made a .lrc.
        val track = interrupted(audio())
        val theirs = "[00:01.000]mine\n".encodeToByteArray()
        sidecar(track).write(theirs, BASE + MINUTE)

        assertEquals(LyricsStatus.EXISTING, pipeline().status(track))

        assertArrayEquals(theirs, sidecar(track).readBytes())
        assertEquals(BASE + MINUTE, sidecar(track).lastModified())
        assertNull(ledger[track.path]?.pendingText)
        assertEquals(0, client.requests)
        assertAudioUntouched(track)
    }

    @Test
    fun `an interrupted write without its text cannot tell its own partial file, so keeps it`() = runTest {
        val track = audio()
        ledger.put(recorded(null, track, LyricsStatus.WRITING, 42, BASE, sha256 = sha256(lrcBytes), size = lrcBytes.size.toLong()))
        sidecar(track).write(lrcBytes.copyOf(20), BASE)

        assertEquals(LyricsStatus.EXISTING, pipeline().status(track))

        assertArrayEquals(lrcBytes.copyOf(20), sidecar(track).readBytes())
    }

    @Test
    fun `a file an interrupted write cannot have left is kept as existing`() = runTest {
        val changed = lrcBytes.copyOf().also { it[1] = '9'.code.toByte() }
        val cases = listOf(
            Triple("Changed", changed, BASE),
            Triple("Longer", (expectedLrc + "[01:10.000]more\n").encodeToByteArray(), BASE),
            Triple("Shorter", changed.copyOf(20), BASE),
        )
        client.onGet = { LrclibResult.Found(listOf(record())) }
        val pipeline = pipeline()

        cases.forEach { (name, bytes, modifiedAt) ->
            val track = interrupted(audio("$name.mp3", name))
            sidecar(track).write(bytes, modifiedAt)

            assertEquals(name, LyricsStatus.EXISTING, pipeline.status(track))

            assertArrayEquals(name, bytes, sidecar(track).readBytes())
            assertEquals(name, modifiedAt, sidecar(track).lastModified())
            assertAudioUntouched(track)
        }
        assertEquals(0, client.requests)
    }

    @Test
    fun `a link where an interrupted write was is kept as existing, even to the right bytes`() = runTest {
        val track = interrupted(audio())
        val target = File(folder.root, "elsewhere.lrc").apply { write(lrcBytes, BASE) }
        symlink(sidecar(track), target)

        assertEquals(LyricsStatus.EXISTING, pipeline().status(track))

        assertTrue(Files.isSymbolicLink(sidecar(track).toPath()))
        assertArrayEquals(lrcBytes, target.readBytes())
        assertEquals(0, client.requests)
    }

    @Test
    fun `writes interrupted by the app's death are settled at startup`() = runTest {
        val complete = interrupted(audio("Complete.mp3"))
        sidecar(complete).write(lrcBytes, BASE)
        val partial = interrupted(audio("Partial.mp3"))
        sidecar(partial).write(lrcBytes.copyOf(7), BASE)
        val settled = audio("Settled.mp3")
        ledger.put(recorded(null, settled, LyricsStatus.NOT_FOUND, null, BASE))
        client.onGet = { LrclibResult.Found(listOf(record())) }

        assertEquals(2, pipeline().finishInterrupted())

        assertEquals(LyricsStatus.SYNCED, ledger[complete.path]?.status)
        assertEquals(LyricsStatus.SYNCED, ledger[partial.path]?.status)
        assertEquals(expectedLrc, sidecar(partial).readText())
        assertEquals(LyricsStatus.NOT_FOUND, ledger[settled.path]?.status)
        assertEquals(listOf(Lane.LOW), client.lanes)
    }

    // LRCLIB busy

    @Test
    fun `transient errors back off 2 then 4 seconds and then succeed`() = runTest {
        val track = audio()
        var failures = 2
        client.onGet = { if (failures-- > 0) LrclibResult.Transient("HTTP 503") else LrclibResult.Found(listOf(record())) }

        assertEquals(LyricsStatus.SYNCED, pipeline().status(track))

        assertEquals(6_000L, testScheduler.currentTime)
        assertEquals(3, client.gets.size)
        assertEquals(expectedLrc, sidecar(track).readText())
    }

    @Test
    fun `a lookup that keeps failing is recorded as an error and retried later`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Transient("HTTP 503") }
        val pipeline = pipeline()

        assertEquals(Processed(LyricsStatus.ERROR, lrclibBusy = true), pipeline.process(track))

        assertEquals(Retry.SESSION_ATTEMPTS, client.gets.size)
        assertEquals(0, client.searches.size)
        assertEquals(14_000L, testScheduler.currentTime)
        val entry = ledger[track.path]
        assertEquals(LyricsStatus.ERROR, entry?.status)
        assertEquals(1, entry?.attempts)
        assertEquals(BASE + 14_000 + 30 * MINUTE, entry?.nextAttemptAt)
        assertFalse(sidecar(track).exists())

        // Seen again before it is due: nothing is asked, and LRCLIB was not busy this time.
        assertEquals(Processed(LyricsStatus.ERROR, lrclibBusy = false), pipeline.process(track))
        assertEquals(Retry.SESSION_ATTEMPTS, client.gets.size)

        // Due again: LRCLIB is back.
        delay(30 * MINUTE)
        client.onGet = { LrclibResult.Found(listOf(record())) }
        assertEquals(LyricsStatus.SYNCED, pipeline.status(track))
        assertEquals(0, ledger[track.path]?.attempts)
    }

    @Test
    fun `failed tracks are listed for retry failed now, whose forced lookup ignores the wait`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Transient("timeout") }
        val pipeline = pipeline()
        pipeline.process(track)
        client.onGet = { LrclibResult.Found(listOf(record())) }
        val requests = client.requests

        assertEquals(listOf(track), pipeline.failed())
        assertEquals(LyricsStatus.ERROR, pipeline.status(track, Lane.LOW))
        assertEquals(requests, client.requests)
        assertEquals(LyricsStatus.SYNCED, pipeline.status(track, Lane.LOW, force = true))

        assertEquals(expectedLrc, sidecar(track).readText())
        assertEquals(Lane.LOW, client.lanes.last())
        assertEquals(emptyList<LocalTrack>(), pipeline.failed())
    }

    @Test
    fun `errors due again are listed for the sweep, with their tags, while their audio file is there`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Transient("HTTP 503") }
        val pipeline = pipeline()
        pipeline.process(track)

        assertEquals(emptyList<LocalTrack>(), pipeline.dueErrors())
        delay(30 * MINUTE)
        assertEquals(listOf(track), pipeline.dueErrors())

        assertTrue(File(track.path).delete())
        assertEquals(emptyList<LocalTrack>(), pipeline.dueErrors())
        assertEquals(LyricsStatus.ERROR, ledger[track.path]?.status)
    }

    // One file at a time, not one track at a time

    @Test
    fun `the same file is never processed twice at once`() = runTest {
        val track = audio()
        client.answerMs = 1_000
        client.onGet = { LrclibResult.Found(listOf(record())) }
        val pipeline = pipeline()

        val first = async { pipeline.status(track) }
        val second = async { pipeline.status(track) }

        assertEquals(LyricsStatus.SYNCED, first.await())
        assertEquals(LyricsStatus.SYNCED, second.await())
        assertEquals(1, client.requests)
    }

    @Test
    fun `a file waiting out LRCLIB's backoff does not hold up another file`() = runTest {
        val busy = audio()
        val other = audio("Other.mp3", "Other")
        client.onGet = { if (it.title == "Nemo") LrclibResult.Transient("HTTP 503") else LrclibResult.NotFound }
        val pipeline = pipeline()

        val slow = async { pipeline.status(busy) }
        delay(100)
        assertEquals(LyricsStatus.NOT_FOUND, pipeline.status(other))

        assertTrue(testScheduler.currentTime < 2_000)
        assertEquals(LyricsStatus.ERROR, slow.await())
    }

    // What LRCLIB has

    @Test
    fun `not found asks every query and is checked again after 30 days`() = runTest {
        val track = audio(title = "04 Nemo (Live)")
        val pipeline = pipeline()

        assertEquals(LyricsStatus.NOT_FOUND, pipeline.status(track))

        assertEquals(listOf("Nemo (Live)", "Nemo"), client.gets.map { it.title })
        assertEquals(listOf("Nemo (Live)", "Nemo"), client.searches.map { it.title })
        assertEquals(BASE + 30 * DAY, ledger[track.path]?.nextAttemptAt)
        val requests = client.requests
        pipeline.process(track)
        assertEquals(requests, client.requests)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `search results are used when the exact lookup misses`() = runTest {
        val track = audio()
        client.onSearch = { LrclibResult.Found(listOf(record(id = 7, duration = 300.0), record(id = 8, duration = 277.0))) }

        assertEquals(LyricsStatus.SYNCED, pipeline().status(track))

        assertEquals(8L, ledger[track.path]?.lrclibId)
    }

    @Test
    fun `a track without an artist is not looked up`() = runTest {
        val track = audio(title = "Intro").copy(artist = "<unknown>")

        assertEquals(LyricsStatus.UNIDENTIFIED, pipeline().status(track))

        assertEquals(0, client.requests)
        assertEquals(BASE + 30 * DAY, ledger[track.path]?.nextAttemptAt)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `an artist in the title is enough to look a track up`() = runTest {
        val track = audio(title = "Nightwish - Nemo").copy(artist = null)
        client.onGet = { LrclibResult.Found(listOf(record())) }

        assertEquals(LyricsStatus.SYNCED, pipeline().status(track))

        assertEquals(TrackQuery("Nightwish", "Nemo"), client.gets.first())
    }

    @Test
    fun `plain lyrics only keep the id and write nothing`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record(id = 99, synced = null))) }

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().status(track))

        assertEquals(99L, ledger[track.path]?.lrclibId)
        assertEquals(true, ledger[track.path]?.durationMatched)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `plain lyrics of another length are kept, flagged as not matching`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record(id = 98, synced = null, duration = 300.0))) }

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().status(track))

        assertEquals(98L, ledger[track.path]?.lrclibId)
        assertEquals(false, ledger[track.path]?.durationMatched)
    }

    @Test
    fun `Light Your Cigar - the exact recording's plain lyrics are kept over a synced live version`() = runTest {
        val file = File(folder.root, "Light Your Cigar.mp3").apply { writeBytes(audioBytes) }
        val track = LocalTrack(file.path, "Light Your Cigar", "Millenium", "Millenium", 448_200)
        fun cigar(id: Long, duration: Double, name: String = "Light Your Cigar", synced: String? = null) =
            LrclibRecord(id, name, "Millenium", "Millenium", duration, plainLyrics = "Light\nyour\ncigar", syncedLyrics = synced)
        client.onSearch = {
            LrclibResult.Found(listOf(cigar(4, 377.0, "Light Your Cigar (live version)", synced), cigar(2, 369.0), cigar(3, 376.0), cigar(1, 449.0)))
        }

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().status(track))

        assertEquals(1L, ledger[track.path]?.lrclibId)
        assertEquals(true, ledger[track.path]?.durationMatched)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `lyrics of a different version are not written`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record(duration = 290.0))) }

        assertEquals(LyricsStatus.DURATION_MISMATCH, pipeline().status(track))

        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `synced lyrics Samsung would show as nothing fall back to the next candidate`() = runTest {
        val track = audio()
        val broken = record(id = 1, duration = 276.0, synced = "[00:01.00]only one line")
        val good = record(id = 2, duration = 278.0)
        client.onGet = { LrclibResult.Found(listOf(broken)) }
        client.onSearch = { LrclibResult.Found(listOf(broken, good)) }

        assertEquals(LyricsStatus.SYNCED, pipeline().status(track))

        assertEquals(2L, ledger[track.path]?.lrclibId)
        assertEquals(expectedLrc, sidecar(track).readText())
    }

    @Test
    fun `unusable synced lyrics with plain text count as plain lyrics, with that record's id`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record(id = 5, synced = "[00:01.00]a\n[09:00.00]b\n[09:30.00]c"))) }

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().status(track))

        assertEquals(5L, ledger[track.path]?.lrclibId)
        assertTrue(logs.any { "PAST_DURATION" in it })
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `unusable synced lyrics without plain text are not found`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record(id = 6, synced = "[00:01.00]only one line", plain = null))) }

        assertEquals(LyricsStatus.NOT_FOUND, pipeline().status(track))

        assertTrue(logs.any { "TOO_FEW_LINES" in it })
    }

    @Test
    fun `an instrumental title is recorded without asking LRCLIB`() = runTest {
        val track = audio(title = "Nemo (Instrumental)")

        assertEquals(LyricsStatus.INSTRUMENTAL, pipeline().status(track))

        assertEquals(0, client.requests)
    }

    @Test
    fun `an audio file without an extension is skipped`() = runTest {
        val file = File(folder.root, "Nemo")
        file.writeBytes(audioBytes)
        assertNull(pipeline().process(LocalTrack(file.path, "Nemo", "Nightwish", null, 276_000)))
        assertEquals(0, client.requests)
    }

    @Test
    fun `a sidecar of ours that was deleted is looked up again`() = runTest {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record())) }
        val pipeline = pipeline()
        pipeline.process(track)
        sidecar(track).delete()

        assertEquals(LyricsStatus.SYNCED, pipeline.status(track))

        assertEquals(expectedLrc, sidecar(track).readText())
    }

    // Removing wrong lyrics

    private suspend fun TestScope.written(): Pair<LyricsPipeline, LocalTrack> {
        val track = audio()
        client.onGet = { LrclibResult.Found(listOf(record())) }
        val pipeline = pipeline()
        pipeline.process(track)
        return pipeline to track
    }

    @Test
    fun `removing lyrics Lyrify wrote deletes exactly that file`() = runTest {
        val (pipeline, track) = written()
        delay(MINUTE)

        assertEquals(Removal.REMOVED, pipeline.remove(track.path))

        assertFalse(sidecar(track).exists())
        assertEquals(setOf("Nemo.mp3"), folder.root.list().orEmpty().toSet())
        val entry = ledger[track.path]
        assertEquals(LyricsStatus.REMOVED, entry?.status)
        assertEquals(42L, entry?.lrclibId)
        assertEquals(BASE + MINUTE, entry?.updatedAt)
        assertAudioUntouched(track)
    }

    @Test
    fun `a lyrics file changed outside Lyrify is left alone`() = runTest {
        val (pipeline, track) = written()
        sidecar(track).appendText("[01:10.000]my own line\n")

        assertEquals(Removal.CHANGED, pipeline.remove(track.path))

        assertEquals("$expectedLrc[01:10.000]my own line\n", sidecar(track).readText())
        assertEquals(LyricsStatus.SYNCED, ledger[track.path]?.status)
        assertAudioUntouched(track)
    }

    @Test
    fun `a lyrics file replaced by a link is left alone`() = runTest {
        val (pipeline, track) = written()
        val copy = File(folder.root, "copy.lrc").apply { writeBytes(sidecar(track).readBytes()) }
        sidecar(track).delete()
        symlink(sidecar(track), copy)

        assertEquals(Removal.CHANGED, pipeline.remove(track.path))

        assertTrue(Files.isSymbolicLink(sidecar(track).toPath()))
        assertEquals(expectedLrc, copy.readText())
    }

    @Test
    fun `removing a lyrics file that is gone records it as removed, so it is not added again`() = runTest {
        val (pipeline, track) = written()
        sidecar(track).delete()
        val requests = client.requests

        assertEquals(Removal.ALREADY_GONE, pipeline.remove(track.path))

        assertEquals(LyricsStatus.REMOVED, ledger[track.path]?.status)
        assertEquals(LyricsStatus.REMOVED, pipeline.status(track))
        assertEquals(requests, client.requests)
        assertEquals(setOf("Nemo.mp3"), folder.root.list().orEmpty().toSet())
        assertAudioUntouched(track)
    }

    @Test
    fun `removed is stored before the file is deleted`() = runTest {
        val (pipeline, track) = written()
        var fileAtRemoved: Boolean? = null
        ledger.accepts = { entry ->
            if (entry.status == LyricsStatus.REMOVED) fileAtRemoved = sidecar(track).exists()
            true
        }

        assertEquals(Removal.REMOVED, pipeline.remove(track.path))

        assertEquals(true, fileAtRemoved)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `a removal the ledger cannot store deletes nothing`() = runTest {
        val (pipeline, track) = written()
        ledger.accepts = { it.status != LyricsStatus.REMOVED }

        assertEquals(Removal.FAILED, pipeline.remove(track.path))

        assertEquals(expectedLrc, sidecar(track).readText())
        assertEquals(LyricsStatus.SYNCED, ledger[track.path]?.status)
        assertAudioUntouched(track)
    }

    @Test
    fun `a file changed between storing removed and deleting is left alone, and its entry restored`() = runTest {
        val (pipeline, track) = written()
        val before = ledger[track.path]
        ledger.accepts = { entry ->
            if (entry.status == LyricsStatus.REMOVED) sidecar(track).appendText("[01:10.000]my own line\n")
            true
        }

        assertEquals(Removal.CHANGED, pipeline.remove(track.path))

        assertEquals("$expectedLrc[01:10.000]my own line\n", sidecar(track).readText())
        assertEquals(before, ledger[track.path])
        assertAudioUntouched(track)
    }

    @Test
    fun `a file gone between storing removed and deleting is already gone, and not written again`() = runTest {
        val (pipeline, track) = written()
        ledger.accepts = { entry ->
            if (entry.status == LyricsStatus.REMOVED) sidecar(track).delete()
            true
        }
        val requests = client.requests

        assertEquals(Removal.ALREADY_GONE, pipeline.remove(track.path))

        assertEquals(LyricsStatus.REMOVED, ledger[track.path]?.status)
        assertEquals(LyricsStatus.REMOVED, pipeline.status(track))
        assertFalse(sidecar(track).exists())
        assertEquals(requests, client.requests)
    }

    @Test
    fun `deleting a file that is not there counts as already gone`() {
        assertEquals(Removal.ALREADY_GONE, SidecarFile.delete(File(folder.root, "Nemo.lrc").toPath(), log = { logs += it }))
    }

    @Test
    fun `lyrics marked removed whose file is still there can be removed again`() = runTest {
        val (pipeline, track) = written()
        // As left when the delete and the undo of the REMOVED entry both failed.
        val stuck = (ledger[track.path] ?: error("not written")).removed(BASE)
        ledger.put(stuck)
        assertTrue(canRemove(stuck))

        assertEquals(Removal.REMOVED, pipeline.remove(track.path))

        assertFalse(sidecar(track).exists())
        assertEquals(LyricsStatus.REMOVED, ledger[track.path]?.status)
        assertFalse(canRemove(ledger[track.path] ?: error("no entry")))
        assertEquals(Removal.NOT_REMOVABLE, pipeline.remove(track.path))
        assertAudioUntouched(track)
    }

    @Test
    fun `a file that cannot be deleted gets its ledger entry back`() = runTest {
        val (pipeline, track) = written()
        val before = ledger[track.path]
        assumeTrue(folder.root.setWritable(false) && !folder.root.canWrite())
        val removal = try {
            pipeline.remove(track.path)
        } finally {
            folder.root.setWritable(true)
        }

        assertEquals(Removal.FAILED, removal)
        assertEquals(before, ledger[track.path])
        assertEquals(expectedLrc, sidecar(track).readText())
        assertAudioUntouched(track)
    }

    @Test
    fun `lyrics Lyrify did not write cannot be removed`() = runTest {
        val track = audio()
        sidecar(track).writeText("[00:01.000]mine\n")
        val pipeline = pipeline()
        pipeline.process(track)

        assertEquals(Removal.NOT_REMOVABLE, pipeline.remove(track.path))
        assertEquals(Removal.NOT_REMOVABLE, pipeline.remove("/nowhere.mp3"))

        assertEquals("[00:01.000]mine\n", sidecar(track).readText())
    }

    @Test
    fun `removed lyrics are never added again by the sweep, a retry or the 30-day check`() = runTest {
        val (pipeline, track) = written()
        pipeline.remove(track.path)
        val requests = client.requests

        assertEquals(LyricsStatus.REMOVED, pipeline.status(track, Lane.LOW))
        assertEquals(LyricsStatus.REMOVED, pipeline.status(track, force = true))
        assertEquals(emptyList<LocalTrack>(), pipeline.failed())
        delay(31 * DAY)
        assertEquals(LyricsStatus.REMOVED, pipeline.status(track))
        assertEquals(emptyList<LocalTrack>(), pipeline.dueErrors())

        assertEquals(requests, client.requests)
        assertFalse(sidecar(track).exists())
    }

    // Files written by v1, which kept no hash

    private suspend fun TestScope.legacy(fileAgeMs: Long): LocalTrack {
        val track = audio()
        val updatedAt = BASE - DAY
        ledger.put(recorded(null, track, LyricsStatus.SYNCED, 42, updatedAt))
        sidecar(track).writeText(expectedLrc)
        sidecar(track).setLastModified(updatedAt + fileAgeMs)
        return track
    }

    @Test
    fun `a v1 file written with its ledger entry is adopted`() = runTest {
        val track = legacy(fileAgeMs = 1_000)

        assertEquals(1, pipeline().adoptLegacy())

        assertEquals(sha256(expectedLrc.encodeToByteArray()), ledger[track.path]?.sha256)
        assertEquals(BASE - DAY + 1_000, ledger[track.path]?.writtenAt)
        assertEquals(BASE - DAY, ledger[track.path]?.updatedAt)
        assertTrue(ledger[track.path]?.removable == true)
        assertAudioUntouched(track)
    }

    @Test
    fun `adoption allows 120 seconds either way`() = runTest {
        val track = legacy(fileAgeMs = -120_000)
        assertEquals(1, pipeline().adoptLegacy())
        assertTrue(ledger[track.path]?.removable == true)
    }

    @Test
    fun `a v1 file changed later is not adopted and cannot be removed`() = runTest {
        val track = legacy(fileAgeMs = 121_000)
        val pipeline = pipeline()

        assertEquals(0, pipeline.adoptLegacy())

        assertNull(ledger[track.path]?.sha256)
        assertEquals(Removal.NOT_REMOVABLE, pipeline.remove(track.path))
        assertEquals(expectedLrc, sidecar(track).readText())
    }

    @Test
    fun `a v1 entry whose file is gone is not adopted`() = runTest {
        val track = legacy(fileAgeMs = 0)
        sidecar(track).delete()

        assertEquals(0, pipeline().adoptLegacy())

        assertNull(ledger[track.path]?.sha256)
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000
        const val AUDIO_MTIME: Long = 1_700_000_000_000
        const val MINUTE: Long = 60_000
        const val DAY: Long = 24 * 60 * MINUTE
    }
}
