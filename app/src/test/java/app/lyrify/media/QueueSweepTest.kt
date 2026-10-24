package app.lyrify.media

import app.lyrify.data.LyricsStatus
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class QueueSweepTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** One file handed to the pipeline: which, in which lane, and when. */
    private data class Started(val title: String, val lane: Lane, val at: Long)

    private val started = mutableListOf<Started>()
    private val finished = mutableListOf<String>()
    private var workMs = 1_000L
    private var busy: (LocalTrack, Lane) -> Boolean = { _, _ -> false }
    private var due: Set<String> = emptySet()
    private val current = mutableListOf<Pair<String, List<String>>>()

    private fun file(track: SessionTrack) = LocalTrack("/m/${track.title}.mp3", track.title, track.artist, null, 200_000)

    private fun TestScope.sweep(): QueueSweep {
        val sweep = QueueSweep(
            files = { listOf(file(it)) },
            process = { track, lane ->
                started += Started(track.title, lane, testScheduler.currentTime)
                delay(workMs)
                finished += track.title
                val failed = busy(track, lane)
                Processed(if (failed) LyricsStatus.ERROR else LyricsStatus.NOT_FOUND, lrclibBusy = failed)
            },
            dueErrors = { due },
            onCurrent = { track, files -> current += track.title to files.map { it.path } },
        )
        backgroundScope.launch { sweep.run() }
        return sweep
    }

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

    // With the real pipeline

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()
    private val synced = "[00:01.00]Stars fall\n[00:02.00]from the sky\n[00:03.00]Mystic Queen"

    private fun TestScope.realSweep(rows: List<AudioRow>): QueueSweep {
        val pipeline = LyricsPipeline(client, ledger, clock = { BASE + testScheduler.currentTime })
        val sweep = QueueSweep(
            files = { track -> SessionTracks.candidates(rows, track).map(AudioRow::toLocalTrack) },
            process = { track, lane -> pipeline.process(track, lane) },
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
