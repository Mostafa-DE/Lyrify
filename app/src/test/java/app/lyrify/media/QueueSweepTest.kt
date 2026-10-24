package app.lyrify.media

import app.lyrify.data.LyricsStatus
import app.lyrify.data.Retry
import app.lyrify.data.recorded
import app.lyrify.lyrics.FakeLrclib
import app.lyrify.lyrics.Lane
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrclibRecord
import app.lyrify.lyrics.LrclibResult
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.MemoryLedger
import app.lyrify.lyrics.Processed
import app.lyrify.lyrics.SidecarFile
import app.lyrify.lyrics.TrackQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class QueueSweepTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** One file handed to the pipeline: which, in which lane, when, and whether forced (a retry the user asked for). */
    private data class Started(val title: String, val lane: Lane, val at: Long, val force: Boolean = false)

    private val started = mutableListOf<Started>()
    private val finished = mutableListOf<String>()
    private var workMs = 1_000L
    private var busy: (LocalTrack, Lane) -> Boolean = { _, _ -> false }
    private var due: Set<String> = emptySet()
    private val current = mutableListOf<Pair<String, List<String>>>()
    private val logs = mutableListOf<String>()

    private fun file(track: SessionTrack) = LocalTrack("/m/${track.title}.mp3", track.title, track.artist, null, 200_000)

    private var resolve: (SessionTrack) -> List<LocalTrack> = { listOf(file(it)) }

    private fun TestScope.sweep(): QueueSweep {
        val sweep = QueueSweep(
            files = { resolve(it) },
            process = { track, lane, force ->
                started += Started(track.title, lane, testScheduler.currentTime, force)
                delay(workMs)
                finished += track.title
                val failed = busy(track, lane)
                Processed(if (failed) LyricsStatus.ERROR else LyricsStatus.NOT_FOUND, lrclibBusy = failed)
            },
            dueErrors = { due },
            onCurrent = { track, files -> current += track.title to files.map { it.path } },
            log = { logs += it },
        )
        backgroundScope.launch { sweep.run() }
        return sweep
    }

    private fun retried(title: String) = LocalTrack("/r/$title.mp3", title, "A", null, 200_000)

    private fun queue(size: Int, prefix: String = "T") = (0 until size).map { QueueEntry(100L + it, SessionTrack("$prefix$it", "A")) }

    private fun snapshot(queue: List<QueueEntry>, index: Int) =
        SessionSnapshot(queue[index].track.copy(durationMs = 200_000), queue[index].queueId, queue)

    private fun titles(lane: Lane? = null) = started.filter { lane == null || it.lane == lane }.map { it.title }

    @Test
    fun `the current track goes first, then the next five, then the rest of the queue`() = runTest {
        val sweep = sweep()
        val queue = queue(12)

        sweep.submit(snapshot(queue, 2))
        advanceTimeBy(60_000)

        assertEquals(listOf("T2", "T3", "T4", "T5", "T6", "T7"), titles(Lane.HIGH))
        assertEquals(listOf("T8", "T9", "T10", "T11", "T0", "T1"), titles(Lane.LOW))
        assertEquals(titles(Lane.HIGH) + titles(Lane.LOW), titles())
        assertEquals(listOf("T2" to listOf("/m/T2.mp3")), current)
        assertEquals(SweepProgress(checked = 12, total = 12, paused = false), sweep.progress.first())
    }

    @Test
    fun `a track change takes over from the sweep at once`() = runTest {
        workMs = 10_000
        val sweep = sweep()
        val queue = queue(12)
        sweep.submit(snapshot(queue, 2))
        advanceTimeBy(65_000) // high lane done at 60 s; T8 (low) is half done

        sweep.submit(snapshot(queue, 9))
        advanceTimeBy(200_000)

        val changeAt = started.filter { it.lane == Lane.HIGH }.drop(6)
        assertEquals(listOf("T9", "T10", "T11"), changeAt.map { it.title })
        assertEquals(65_000L, changeAt.first().at)
        assertEquals(1, finished.count { it == "T8" })
        assertEquals(listOf("T8", "T8", "T9", "T10", "T11", "T0", "T1"), titles(Lane.LOW))
    }

    @Test
    fun `a new queue replaces the low lane and nothing is swept twice`() = runTest {
        val sweep = sweep()
        sweep.submit(snapshot(queue(12), 0))
        advanceTimeBy(6_500) // high lane done at 6 s; T6, the first low item, is half done

        val next = queue(10, prefix = "U")
        sweep.submit(snapshot(next, 0))
        advanceTimeBy(60_000)

        assertEquals(listOf("T6"), titles(Lane.LOW).filter { it.startsWith("T") })
        assertEquals((0 until 10).map { "U$it" }, titles().filter { it.startsWith("U") })
        assertFalse("T7" in titles())
        assertEquals(SweepProgress(checked = 10, total = 10, paused = false), sweep.progress.first())
    }

    @Test
    fun `the same queue with another current track keeps the low lane as it is`() = runTest {
        val sweep = sweep()
        val queue = queue(12)
        sweep.submit(snapshot(queue, 0))
        advanceTimeBy(6_500) // T6 (low) is half done

        sweep.submit(snapshot(queue, 1)) // T6 is now one of the next five: done in the high lane
        advanceTimeBy(60_000)

        assertEquals(listOf("T1", "T2", "T3", "T4", "T5", "T6"), titles(Lane.HIGH).drop(6))
        assertEquals(listOf("T6", "T7", "T8", "T9", "T10", "T11"), titles(Lane.LOW))
        assertEquals(1, finished.count { it == "T6" })
    }

    @Test
    fun `three busy answers in a row pause the low lane for two minutes, the high lane keeps going`() = runTest {
        busy = { _, lane -> lane == Lane.LOW }
        val sweep = sweep()
        val queue = queue(12)
        sweep.submit(snapshot(queue, 0))
        advanceTimeBy(9_500) // high: T0-T5 by 6 s; low: T6, T7, T8 busy by 9 s

        assertEquals(listOf("T6", "T7", "T8"), titles(Lane.LOW))
        assertTrue(sweep.progress.first().paused)

        advanceTimeBy(50_000)
        sweep.submit(snapshot(queue, 3))
        advanceTimeBy(10_000)
        assertEquals(listOf(59_500L), started.filter { it.lane == Lane.HIGH && it.title == "T3" }.drop(1).map { it.at })
        assertEquals(listOf("T6", "T7", "T8"), titles(Lane.LOW))

        advanceTimeBy(60_000) // two minutes after the pause began
        assertEquals(listOf("T6", "T7", "T8", "T9"), titles(Lane.LOW))
        assertEquals(129_000L, started.last { it.title == "T9" }.at)
        assertFalse(sweep.progress.first().paused)
    }

    @Test
    fun `an answer in between resets the count of busy answers`() = runTest {
        busy = { track, lane -> lane == Lane.LOW && track.title in setOf("T6", "T7", "T9", "T10") }
        val sweep = sweep()
        sweep.submit(snapshot(queue(12), 0))
        advanceTimeBy(13_500)

        assertEquals(listOf("T6", "T7", "T8", "T9", "T10", "T11"), titles(Lane.LOW))
        assertFalse(sweep.progress.first().paused)
    }

    @Test
    fun `failed queue items are tried again every 30 minutes once due`() = runTest {
        val sweep = sweep()
        sweep.submit(snapshot(queue(12), 0))
        advanceTimeBy(60_000)
        assertEquals(1, titles().count { it == "T9" })
        due = setOf("/m/T9.mp3", "/m/not-in-the-queue.mp3")

        advanceTimeBy(30 * MINUTE)

        assertEquals(listOf(30 * MINUTE), started.filter { it.title == "T9" }.drop(1).map { it.at })
        assertEquals(Lane.LOW, started.last().lane)
        assertEquals(13, started.size)

        due = emptySet()
        advanceTimeBy(30 * MINUTE)
        assertEquals(13, started.size)
    }

    @Test
    fun `an item that throws is logged and skipped, and the sweep goes on`() = runTest {
        resolve = { if (it.title == "T3") throw IllegalStateException("MediaStore went away") else listOf(file(it)) }
        busy = { track, _ -> if (track.title == "T6") throw IOException("disk gone") else false }
        sweep().submit(snapshot(queue(8), 0))
        advanceTimeBy(60_000)

        assertEquals(listOf("T0", "T1", "T2", "T4", "T5", "T6", "T7"), titles())
        assertTrue(logs.any { "MediaStore went away" in it })
        assertTrue(logs.any { "disk gone" in it })
    }

    // Retry failed now

    @Test
    fun `retries wait for HIGH work, then go first in the LOW lane, forced and once each`() = runTest {
        val sweep = sweep()
        sweep.submit(snapshot(queue(8), 0))
        advanceTimeBy(500)

        sweep.retry(listOf(retried("R1"), retried("R2"), retried("R1")))
        runCurrent()
        assertTrue(sweep.progress.first().retrying)
        advanceTimeBy(60_000)

        assertEquals(listOf("T0", "T1", "T2", "T3", "T4", "T5", "R1", "R2", "T6", "T7"), titles())
        assertEquals(listOf("R1", "R2"), started.filter { it.force }.map { it.title })
        assertEquals(setOf(Lane.LOW), started.filter { it.force }.map { it.lane }.toSet())
        assertFalse(sweep.progress.first().retrying)
    }

    @Test
    fun `HIGH work takes over from a retry, which is done again afterwards`() = runTest {
        workMs = 10_000
        val sweep = sweep()
        sweep.retry(listOf(retried("R1")))
        advanceTimeBy(5_000) // R1 half done
        assertTrue(sweep.progress.first().retrying)

        sweep.submit(snapshot(queue(3), 0))
        advanceTimeBy(100_000)

        assertEquals(listOf("R1", "T0", "T1", "T2", "R1"), titles())
        assertEquals(listOf(Lane.LOW, Lane.HIGH, Lane.HIGH, Lane.HIGH, Lane.LOW), started.map { it.lane })
        assertEquals(1, finished.count { it == "R1" })
        assertFalse(sweep.progress.first().retrying)
    }

    @Test
    fun `retries run while the LOW lane is paused, and do not pause it again`() = runTest {
        busy = { _, lane -> lane == Lane.LOW }
        val sweep = sweep()
        sweep.submit(snapshot(queue(12), 0))
        advanceTimeBy(9_500) // T6, T7, T8 found LRCLIB busy: paused at 9 s
        assertTrue(sweep.progress.first().paused)

        sweep.retry(listOf(retried("R1"), retried("R2"), retried("R3")))
        advanceTimeBy(5_000)

        assertEquals(listOf("T6", "T7", "T8", "R1", "R2", "R3"), titles(Lane.LOW))
        assertEquals(9_500L, started.first { it.title == "R1" }.at)
        assertTrue(sweep.progress.first().paused)
        assertEquals(1, logs.count { "paused" in it })
        advanceTimeBy(120_000)
        assertEquals(129_000L, started.first { it.title == "T9" }.at)
    }

    // With the real pipeline

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()
    private val synced = "[00:01.00]Stars fall\n[00:02.00]from the sky\n[00:03.00]Mystic Queen"

    private fun TestScope.realSweep(rows: List<AudioRow>): QueueSweep {
        val pipeline = LyricsPipeline(client, ledger, clock = { BASE + testScheduler.currentTime }, random = Random(0))
        val sweep = QueueSweep(
            files = { track -> SessionTracks.candidates(rows, track).map(AudioRow::toLocalTrack) },
            process = { track, lane, force -> pipeline.process(track, lane, force) },
            dueErrors = { pipeline.dueErrors() },
        )
        backgroundScope.launch { sweep.run() }
        return sweep
    }

    private fun audioFile(path: String): String {
        val file = File(folder.root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        return file.path
    }

    @Test
    fun `Camel - both copies get lyrics, each looked up with its own tags`() = runTest {
        val kjb = AudioRow(audioFile("kjb/02. Mystic Queen.mp3"), "Mystic Queen", "Camel", "Camel", 341_000)
        val de = AudioRow(audioFile("de/camel/02. Mystic Queen.mp3"), "Mystic Queen", "Camel", "Mirage", 341_600)
        client.onGet = { query ->
            LrclibResult.Found(listOf(LrclibRecord(1, query.title, query.artist.orEmpty(), query.album, client.durations.last().toDouble(), syncedLyrics = synced)))
        }
        val queue = listOf(QueueEntry(1, SessionTrack("Lady Fantasy", "Camel")), QueueEntry(2, SessionTrack("Mystic Queen", "Camel")))
        val sweep = realSweep(listOf(kjb, de))

        sweep.submit(SessionSnapshot(SessionTrack("Lady Fantasy", "Camel", durationMs = 760_000), 1, queue))
        advanceTimeBy(10_000)

        assertEquals(listOf(341L, 342L), client.durations)
        assertEquals(listOf(null, "Mirage"), client.gets.map { it.album })
        listOf(kjb, de).forEach { row ->
            assertTrue(File(LrcFormat.sidecarPath(row.path) ?: error("no sidecar")).isFile)
            assertEquals(LyricsStatus.SYNCED, ledger[row.path]?.status)
            assertEquals(row.album, ledger[row.path]?.album)
            assertEquals(row.durationMs, ledger[row.path]?.durationMs)
        }
        assertEquals(setOf(Lane.HIGH), client.lanes.toSet())
    }

    @Test
    fun `settled tracks are skipped without asking LRCLIB`() = runTest {
        val done = AudioRow(audioFile("Done.mp3"), "Done", "A", null, 200_000)
        val hasLrc = AudioRow(audioFile("Has.mp3"), "Has", "A", null, 200_000)
        val fresh = AudioRow(audioFile("Fresh.mp3"), "Fresh", "A", null, 200_000)
        ledger.put(recorded(null, done.toLocalTrack(), LyricsStatus.NOT_FOUND, null, BASE))
        File(LrcFormat.sidecarPath(hasLrc.path) ?: error("no sidecar")).writeText("[00:01.000]mine\n")
        val queue = listOf(done, hasLrc, fresh).mapIndexed { i, row -> QueueEntry(i.toLong(), SessionTrack(row.title, row.artist)) }
        val sweep = realSweep(listOf(done, hasLrc, fresh))

        sweep.submit(SessionSnapshot(null, -1, queue))
        advanceTimeBy(30_000)

        assertEquals(listOf("Fresh"), (client.gets + client.searches).map { it.title }.distinct())
        assertEquals(LyricsStatus.EXISTING, ledger[hasLrc.path]?.status)
        assertEquals(SweepProgress(checked = 3, total = 3, paused = false), sweep.progress.first())
    }

    // A track change and the HIGH lane

    /** LRCLIB requests by title, with their virtual time. */
    private val asked = mutableListOf<Pair<String, Long>>()

    private fun askedAt(title: String): List<Long> = asked.filter { it.first == title }.map { it.second }

    /** Twelve songs Q0 to Q11 on disk, in a queue in that order. */
    private fun songs(): Pair<List<AudioRow>, List<QueueEntry>> {
        val rows = (0 until 12).map { AudioRow(audioFile("Q$it.mp3"), "Q$it", "A", null, 200_000) }
        return rows to rows.mapIndexed { i, row -> QueueEntry(i.toLong(), SessionTrack(row.title, row.artist)) }
    }

    private fun playing(queue: List<QueueEntry>, index: Int) =
        SessionSnapshot(queue[index].track.copy(durationMs = 200_000), queue[index].queueId, queue)

    private fun TestScope.answer(busyTitle: String?, found: (TrackQuery) -> LrclibResult = { LrclibResult.NotFound }) {
        client.onGet = { query ->
            asked += query.title to testScheduler.currentTime
            if (query.title == busyTitle) LrclibResult.Transient("HTTP 503") else found(query)
        }
    }

    @Test
    fun `a track change drops HIGH work for a song no longer coming up, even in its backoff`() = runTest {
        val (rows, queue) = songs()
        answer(busyTitle = "Q0")
        val sweep = realSweep(rows)
        sweep.submit(playing(queue, 0))
        advanceTimeBy(1_000) // Q0 asked at 0 s, now waiting out LRCLIB's 503

        sweep.submit(playing(queue, 8))
        advanceTimeBy(120_000)

        assertEquals(listOf(0L), askedAt("Q0"))
        assertEquals(1_000L, askedAt("Q8").first())
        assertNull(ledger[rows[0].path])
        assertEquals(LyricsStatus.NOT_FOUND, ledger[rows[8].path]?.status)
    }

    @Test
    fun `HIGH work for a song still among the next five goes on after a track change`() = runTest {
        val (rows, queue) = songs()
        answer(busyTitle = "Q2")
        val sweep = realSweep(rows)
        sweep.submit(playing(queue, 2))
        advanceTimeBy(1_000)

        sweep.submit(playing(queue, 1)) // Q2 is now the next song
        advanceTimeBy(120_000)

        assertEquals(Retry.SESSION_ATTEMPTS, askedAt("Q2").size)
        assertEquals(LyricsStatus.ERROR, ledger[rows[2].path]?.status)
        assertTrue(asked.indexOfFirst { it.first == "Q1" } > asked.indexOfLast { it.first == "Q2" })
    }

    @Test
    fun `a track change during a write lets the file and its ledger entries finish`() = runTest {
        val (rows, queue) = songs()
        answer(busyTitle = null) { LrclibResult.Found(listOf(LrclibRecord(1, it.title, "A", null, 200.0, syncedLyrics = synced))) }
        ledger.putMs = 1_000
        val sweep = realSweep(rows)
        sweep.submit(playing(queue, 0))
        advanceTimeBy(500) // Q0's WRITING entry is being stored

        sweep.submit(playing(queue, 8))
        advanceTimeBy(120_000)

        assertEquals(LyricsStatus.SYNCED, ledger[rows[0].path]?.status)
        assertEquals(
            "[00:01.000]Stars fall\n[00:02.000]from the sky\n[00:03.000]Mystic Queen\n",
            File(LrcFormat.sidecarPath(rows[0].path) ?: error("no sidecar")).readText(),
        )
        assertEquals(2_000L, askedAt("Q8").first())
    }

    @Test
    fun `every connection's sweep settles writes the app's death interrupted`() = runTest {
        val pipeline = LyricsPipeline(client, ledger, clock = { BASE + testScheduler.currentTime })
        fun TestScope.connect() = backgroundScope.launch {
            QueueSweep(
                files = { emptyList() },
                process = pipeline::process,
                dueErrors = { emptySet() },
                settleInterrupted = pipeline::finishInterrupted,
            ).run()
        }
        val text = "[00:01.000]Stars fall\n[00:02.000]from the sky\n[00:03.000]Mystic Queen\n"
        val bytes = LrcFormat.encode(text)
        /** A complete `.lrc` whose SYNCED entry the app's death lost. */
        suspend fun interrupted(name: String): String {
            val path = audioFile("$name.mp3")
            val track = LocalTrack(path, name, "A", null, 200_000)
            ledger.put(recorded(null, track, LyricsStatus.WRITING, 1, BASE, sha256 = SidecarFile.sha256(bytes), size = bytes.size.toLong(), pendingText = text))
            File(LrcFormat.sidecarPath(path) ?: error("no sidecar")).writeBytes(bytes)
            return path
        }

        val first = interrupted("First")
        val connection = connect()
        runCurrent()
        assertEquals(LyricsStatus.SYNCED, ledger[first]?.status)
        connection.cancel()

        val second = interrupted("Second")
        connect()
        runCurrent()
        assertEquals(LyricsStatus.SYNCED, ledger[second]?.status)
        assertEquals(0, client.requests)
    }

    @Test
    fun `nothing happens before a queue is submitted`() = runTest {
        sweep()
        runCurrent()
        advanceTimeBy(10_000)
        assertEquals(emptyList<Started>(), started)
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000
        const val MINUTE: Long = 60_000
    }
}
