package app.lyrify.overnight

import app.lyrify.ai.AiModels
import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStage
import app.lyrify.ai.AiStats
import app.lyrify.ai.RejectReason
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerJson
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsSource
import app.lyrify.data.LyricsStatus
import app.lyrify.data.recorded
import app.lyrify.lyrics.FakeLrclib
import app.lyrify.lyrics.LOOKUP_REV
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrclibRecord
import app.lyrify.lyrics.LrclibResult
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.MemoryLedger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class AiNightTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60_000L
    private val canAll: (String) -> Boolean = { true }

    private fun track(name: String) = LocalTrack("/music/$name.mp3", name, "Artist", null, 200_000)

    private fun entry(name: String, status: LyricsStatus, updatedAt: Long, id: Long? = null, matched: Boolean? = null): LedgerEntry =
        recorded(null, track(name), status, id, updatedAt, durationMatched = matched)

    private fun ledgerOf(vararg entries: LedgerEntry): LedgerState = entries.fold(LedgerState()) { state, entry -> state.put(entry) }

    // The queue

    @Test
    fun `songs whose plain lyrics can be timed go first, then the rest, most recently played first`() {
        val ledger = ledgerOf(
            entry("a", LyricsStatus.PLAIN_ONLY, now - 5, id = 1, matched = true),
            entry("b", LyricsStatus.PLAIN_ONLY, now - 1, id = 2, matched = true),
            entry("c", LyricsStatus.NOT_FOUND, now),
            entry("d", LyricsStatus.PLAIN_ONLY, now - 3, id = 4, matched = false),
            entry("e", LyricsStatus.PLAIN_ONLY, now - 2, id = 5, matched = null),
        )

        val jobs = AiQueue.of(ledger, now, canAll)

        assertEquals(listOf("b", "a", "c", "e", "d"), jobs.map { it.track.title })
        assertEquals(listOf(2L, 1L, null, null, null), jobs.map { it.lrclibId })
    }

    @Test
    fun `only songs without lyrics and without an AI verdict are queued`() {
        val ledger = ledgerOf(*LyricsStatus.entries.mapIndexed { i, status -> entry(status.name, status, now - i, id = 9, matched = true) }.toTypedArray())

        assertEquals(listOf("PLAIN_ONLY", "NOT_FOUND"), AiQueue.of(ledger, now, canAll).map { it.track.title })
    }

    @Test
    fun `songs waiting for the user's check, or for this version's lookup, are not queued`() {
        val ledger = ledgerOf(
            entry("to check", LyricsStatus.NEEDS_CHECK, now - 1),
            entry("older lookup", LyricsStatus.NOT_FOUND, now - 2).copy(lookupRev = LOOKUP_REV - 1),
            entry("looked up", LyricsStatus.NOT_FOUND, now - 3),
            entry("none of these", LyricsStatus.NOT_FOUND, now - 4).copy(declined = true),
            entry("plain, older lookup", LyricsStatus.PLAIN_ONLY, now - 5, id = 4, matched = true).copy(lookupRev = 0),
        )

        assertEquals(listOf("plain, older lookup", "looked up", "none of these"), AiQueue.of(ledger, now, canAll).map { it.track.title })
        assertFalse(AiQueue.wants(ledger.entries.getValue("/music/older lookup.mp3"), now))
    }

    @Test
    fun `songs with an lrc or without their audio file are left out`() {
        val ledger = ledgerOf(entry("kept", LyricsStatus.NOT_FOUND, now), entry("gone", LyricsStatus.NOT_FOUND, now - 1))

        assertEquals(listOf("kept"), AiQueue.of(ledger, now, canTake = { it.endsWith("kept.mp3") }).map { it.track.title })
    }

    @Test
    fun `a failed run comes back after its wait, behind the songs never tried`() {
        val once = recorded(entry("once", LyricsStatus.NOT_FOUND, now - 3 * day), track("once"), LyricsStatus.AI_FAILED, null, now - 2 * day)
        val thrice = (1..3).fold(entry("thrice", LyricsStatus.PLAIN_ONLY, now - 9 * day, id = 3, matched = true)) { previous, _ ->
            recorded(previous, track("thrice"), LyricsStatus.AI_FAILED, previous.lrclibId, now - day, durationMatched = previous.durationMatched)
        }
        val ledger = ledgerOf(once, thrice, entry("fresh", LyricsStatus.NOT_FOUND, now - 10 * day))

        assertEquals(listOf("fresh", "once"), AiQueue.of(ledger, now, canAll).map { it.track.title })
        assertEquals(listOf("fresh", "once", "thrice"), AiQueue.of(ledger, now + 4 * day, canAll).map { it.track.title })
        assertEquals(3L, AiQueue.of(ledger, now + 4 * day, canAll).last().lrclibId)
    }

    @Test
    fun `the AI takes an audio file only when it is there and has no lrc`() {
        val audio = folder.newFile("Song.mp3")
        assertTrue(audioWithoutLyrics(audio.path))
        File(folder.root, "Song.lrc").writeText("[00:01.000]placeholder")
        assertFalse(audioWithoutLyrics(audio.path))
        assertFalse(audioWithoutLyrics(File(folder.root, "Missing.mp3").path))
        assertFalse(audioWithoutLyrics(folder.newFile("NoExtension").path))
    }

    @Test
    fun `a dangling link where the lrc goes counts as an lrc`() {
        val audio = folder.newFile("Linked.mp3")
        val created = try {
            Files.createSymbolicLink(File(folder.root, "Linked.lrc").toPath(), File(folder.root, "nowhere").toPath())
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IOException) {
            false
        }
        assumeTrue("symbolic links unavailable", created)
        assertFalse(audioWithoutLyrics(audio.path))
    }

    @Test
    fun `songs to time can be left waiting while the others are queued`() {
        val failedTimed = recorded(entry("retimed", LyricsStatus.PLAIN_ONLY, now - 9 * day, id = 3, matched = true), track("retimed"), LyricsStatus.AI_FAILED, 3, now - 9 * day, durationMatched = true)
        val ledger = ledgerOf(
            entry("timed", LyricsStatus.PLAIN_ONLY, now, id = 1, matched = true),
            entry("heard", LyricsStatus.NOT_FOUND, now - 1),
            entry("untimed", LyricsStatus.PLAIN_ONLY, now - 2, id = 2, matched = false),
            failedTimed,
        )

        assertEquals(listOf("timed", "heard", "untimed", "retimed"), AiQueue.of(ledger, now, canAll).map { it.track.title })
        assertEquals(listOf("heard", "untimed"), AiQueue.of(ledger, now, canAll, align = false).map { it.track.title })
    }

    @Test
    fun `songs that got AI lyrics or a final AI verdict in the last day are counted, failed runs are not`() {
        val ledger = ledgerOf(
            recorded(null, track("a"), LyricsStatus.AI_TRANSCRIBED, null, now - 1_000, source = LyricsSource.AI),
            recorded(null, track("b"), LyricsStatus.AI_REJECTED, null, now - 2_000),
            recorded(null, track("c"), LyricsStatus.AI_ALIGNED, null, now - 2 * day, source = LyricsSource.AI),
            recorded(null, track("d"), LyricsStatus.SYNCED, null, now - 1_000),
            recorded(null, track("e"), LyricsStatus.AI_INSTRUMENTAL, null, now - 3_000),
            recorded(null, track("f"), LyricsStatus.AI_FAILED, null, now - 1_000),
        )
        assertEquals(3, AiQueue.doneSince(ledger, now - day))
    }

    // The run

    private val memory = MemoryLedger()
    private val client = FakeLrclib()
    private val plugged = MutableStateFlow(true)
    private var thermal: () -> Int = { 0 }
    private var hasModels = true
    private val progress = mutableListOf<AiProgress>()
    private val logs = mutableListOf<String>()

    /** What the worker holds while a run uses the models; a delete waits for it. */
    private val inUse = Mutex()
    private val discarded = mutableListOf<File>()
    private var discard: () -> Unit = {}
    private lateinit var pipeline: LyricsPipeline
    private val stats = AiStats(200.0, 0.6, 80.0, 4, 120.0, 5, 4, emptyMap())

    /** One engine call: the song, the plain lyrics it got, threads, when it started and ended, and the chunks it ran. */
    private data class Call(val title: String, val plain: String?, val threads: Int, val start: Long, var end: Long? = null, var chunksRun: Int = 0)

    /** The crash guard's records. */
    private val guard by lazy { CrashGuard(File(folder.root, "ai-crash-guard.json"), clock = { now }, version = 5) }

    /** Engines opened and closed: the runner keeps one for the whole run. */
    private var opened = 0
    private var closed = 0

    /** The models' load failures, as the app's preferences keep them. */
    private var savedFailures: String? = null

    private fun failures(version: Int = 5, pin: () -> String = { "pinned" }) =
        ModelLoadFailures(version, pinned = { pin() }, read = { savedFailures }, write = { savedFailures = it })

    private val loadFailures by lazy { failures() }

    private val calls = mutableListOf<Call>()

    /** Placeholder lines, never real lyrics. */
    private fun lrc(tag: String) = (1..4).joinToString("") { "[00:0$it.000]placeholder $tag $it\n" }

    private var results: (LocalTrack) -> AiResult = { AiResult.Transcribed(lrc(it.title), stats) }
    private var chunks = 3
    private var chunkMs = 60_000L
    private var duringRun: suspend (LocalTrack) -> Unit = {}

    private fun audio(name: String): LocalTrack {
        val file = File(folder.root, "$name.mp3")
        file.writeBytes(byteArrayOf(1, 2, 3))
        return LocalTrack(file.path, name, "Artist", null, 200_000)
    }

    private suspend fun waiting(name: String, status: LyricsStatus = LyricsStatus.NOT_FOUND, updatedAt: Long = now - day, id: Long? = null): LocalTrack {
        val track = audio(name)
        memory.put(recorded(null, track, status, id, updatedAt, durationMatched = id?.let { true }))
        return track
    }

    private fun TestScope.runner(): AiRunner {
        pipeline = LyricsPipeline(
            client, memory, clock = { now + testScheduler.currentTime }, random = Random(1), log = { logs += it }, warn = { logs += it },
        )
        return AiRunner(
            ledger = memory,
            plainLyrics = pipeline::plainLyrics,
            record = pipeline::recordAi,
            // Like the real engine: each chunk waits its turn and runs to its end once started, and a stop is noticed
            // between chunks.
            openEngine = { _ ->
                opened++
                object : SongEngine {
                    override suspend fun run(track: LocalTrack, plainLyrics: String?, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult {
                        check(closed < opened) { "the engine was closed" }
                        val call = Call(track.title, plainLyrics, AI_THREADS, testScheduler.currentTime)
                        calls += call
                        duringRun(track)
                        onStage(AiStage.TRANSCRIBE)
                        for (k in 0 until chunks) {
                            beforeChunk()
                            withContext(NonCancellable) { delay(chunkMs) }
                            call.chunksRun++
                            currentCoroutineContext().ensureActive()
                        }
                        call.end = testScheduler.currentTime
                        return results(track)
                    }

                    override fun close() {
                        closed++
                    }
                }
            },
            models = { if (hasModels) AiModels.inFolder(folder.root) else null },
            thermalStatus = { thermal() },
            pluggedIn = plugged,
            clock = { now + testScheduler.currentTime },
            engineContext = EmptyCoroutineContext,
            aiPaths = pipeline.aiPaths,
            guard = guard,
            loadFailures = loadFailures,
            inUse = inUse,
            badModel = {
                discarded += it
                discard()
            },
            onProgress = { progress += it },
            log = { logs += it },
        )
    }

    private fun sidecar(track: LocalTrack) = File(LrcFormat.sidecarPath(track.path) ?: error("no sidecar"))

    @Test
    fun `every waiting song runs with 4 threads and its result is written through the pipeline`() = runTest {
        val heard = waiting("Heard", updatedAt = now - 1)
        val timed = waiting("Timed", LyricsStatus.PLAIN_ONLY, updatedAt = now - 2, id = 7)
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it, "Timed", "Artist", plainLyrics = "placeholder one\nplaceholder two"))) }
        results = { if (it.title == "Timed") AiResult.Aligned(lrc("t"), 0.8, stats) else AiResult.Transcribed(lrc("h"), stats) }

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(listOf("Timed", "Heard"), calls.map { it.title })
        assertEquals(listOf("placeholder one\nplaceholder two", null), calls.map { it.plain })
        assertEquals(listOf(4, 4), calls.map { it.threads })
        assertEquals(LyricsStatus.AI_ALIGNED, memory[timed.path]?.status)
        assertEquals(LyricsStatus.AI_TRANSCRIBED, memory[heard.path]?.status)
        assertEquals(lrc("t"), sidecar(timed).readText())
        assertEquals(lrc("h"), sidecar(heard).readText())
        assertEquals(AiProgress(0, 2, "Timed"), progress.first())
        assertEquals(AiProgress(1, 2, "Heard"), progress.last())
    }

    @Test
    fun `verdicts without lyrics are recorded and the run goes on`() = runTest {
        val first = waiting("First", updatedAt = now - 1)
        val second = waiting("Second", updatedAt = now - 2)
        val third = waiting("Third", updatedAt = now - 3)
        results = {
            when (it.title) {
                "First" -> AiResult.Failed("IOException: unreadable")
                "Second" -> AiResult.Instrumental(0.1)
                else -> AiResult.Rejected(RejectReason.INVALID_LRC, null)
            }
        }

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(LyricsStatus.AI_FAILED, memory[first.path]?.status)
        assertEquals(LyricsStatus.AI_INSTRUMENTAL, memory[second.path]?.status)
        assertEquals(LyricsStatus.AI_REJECTED, memory[third.path]?.status)
        listOf(first, second, third).forEach { assertFalse(sidecar(it).exists()) }
    }

    @Test
    fun `between songs it waits while the phone is warmer than light`() = runTest {
        waiting("Song")
        var polls = 0
        thermal = { if (polls++ < 2) 3 else 1 }

        runner().run()

        assertEquals(2 * AiRunner.THERMAL_POLL_MS, calls.single().start)
        assertTrue(progress.first().hot)
        assertFalse(progress.last().hot)
    }

    @Test
    fun `between chunks it waits while the phone is warmer than light, and logs how long`() = runTest {
        waiting("Song")
        var polls = 0
        // Cool before the song; warm for two polls once the first chunk is done.
        thermal = { if (testScheduler.currentTime >= chunkMs && polls++ < 2) 3 else 0 }

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(3 * chunkMs + 2 * AiRunner.THERMAL_POLL_MS, calls.single().end)
        assertTrue(progress.toString(), progress.any { it.hot && it.done == 0 })
        assertEquals(AiProgress(0, 1, "Song"), progress.last())
        assertTrue(logs.toString(), logs.any { "cooled down after 2 min" in it })
    }

    @Test
    fun `unplugged, the current chunk finishes, the run stops and nothing is recorded`() = runTest {
        val song = waiting("Song")
        val before = memory[song.path]
        var ended: AiRunEnd? = null
        launch { ended = runner().run() }

        advanceTimeBy(90_000)
        plugged.value = false
        runCurrent()
        assertNull(ended)
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(AiRunEnd.UNPLUGGED, ended)
        assertEquals(before, memory[song.path])
        assertFalse(sidecar(song).exists())
    }

    @Test
    fun `unplugged while it waits for the heat, it stops`() = runTest {
        waiting("Song")
        thermal = { 4 }
        var ended: AiRunEnd? = null
        launch { ended = runner().run() }

        advanceTimeBy(AiRunner.THERMAL_POLL_MS * 3)
        plugged.value = false
        runCurrent()

        assertEquals(AiRunEnd.UNPLUGGED, ended)
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `not plugged in, nothing runs`() = runTest {
        waiting("Song")
        plugged.value = false

        assertEquals(AiRunEnd.UNPLUGGED, runner().run())

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `cancelled, the current chunk finishes first and nothing is recorded`() = runTest {
        val song = waiting("Song")
        val before = memory[song.path]
        val run = launch { runner().run() }

        advanceTimeBy(90_000)
        run.cancelAndJoin()

        assertEquals(120_000L, testScheduler.currentTime)
        assertEquals(before, memory[song.path])
        assertNull(calls.single().end)
        assertFalse(song.path in pipeline.aiPaths)
    }

    @Test
    fun `without models nothing runs`() = runTest {
        waiting("Song")
        hasModels = false

        assertEquals(AiRunEnd.NO_MODELS, runner().run())

        assertTrue(calls.isEmpty())
    }

    @Test
    fun `when LRCLIB cannot give the plain lyrics, the song waits and the others go on`() = runTest {
        val timed = waiting("Timed", LyricsStatus.PLAIN_ONLY, updatedAt = now - 1, id = 7)
        waiting("Heard", updatedAt = now - 2)
        client.onId = { LrclibResult.Transient("HTTP 503") }

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(listOf("Heard"), calls.map { it.title })
        assertEquals(LyricsStatus.PLAIN_ONLY, memory[timed.path]?.status)
    }

    @Test
    fun `when the plain lyrics are gone, the song is transcribed`() = runTest {
        waiting("Timed", LyricsStatus.PLAIN_ONLY, id = 7)
        client.onId = { LrclibResult.NotFound }

        runner().run()

        assertNull(calls.single().plain)
    }

    @Test
    fun `a song that changed during the run is skipped`() = runTest {
        waiting("First", updatedAt = now - 1)
        val second = waiting("Second", updatedAt = now - 2)
        duringRun = { if (it.title == "First") memory.put((memory[second.path] ?: error("no entry")).removed(now)) }

        runner().run()

        assertEquals(listOf("First"), calls.map { it.title })
        assertEquals(LyricsStatus.REMOVED, memory[second.path]?.status)
        assertTrue(progress.none { it.current == "Second" })
    }

    @Test
    fun `tonight's progress counts only songs that got lyrics or a final verdict`() = runTest {
        waiting("Failing", updatedAt = now - 1)
        val changing = waiting("Changing", updatedAt = now - 2)
        waiting("Third", updatedAt = now - 3)
        waiting("Fourth", updatedAt = now - 4)
        results = { if (it.title == "Failing") AiResult.Failed("IOException: unreadable") else AiResult.Instrumental(0.1) }
        duringRun = { if (it.title == "Failing") memory.put((memory[changing.path] ?: error("no entry")).removed(now)) }

        runner().run()

        assertEquals(listOf(AiProgress(0, 4, "Failing"), AiProgress(0, 4, "Third"), AiProgress(1, 4, "Fourth")), progress)
    }

    // The engine's lifetime

    @Test
    fun `the engine is opened for the first song that runs, kept across songs, and closed at the end`() = runTest {
        waiting("First", updatedAt = now - 1)
        waiting("Second", updatedAt = now - 2)

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(listOf("First", "Second"), calls.map { it.title })
        assertEquals(1, opened)
        assertEquals(1, closed)
    }

    @Test
    fun `with nothing to run no engine is opened`() = runTest {
        assertEquals(AiRunEnd.DONE, runner().run())
        plugged.value = false
        waiting("Song")
        assertEquals(AiRunEnd.UNPLUGGED, runner().run())

        assertEquals(0, opened)
    }

    @Test
    fun `a cancelled run closes its engine once the step it is on has finished`() = runTest {
        waiting("Song")
        val run = launch { runner().run() }
        advanceTimeBy(90_000)
        run.cancelAndJoin()

        assertEquals(120_000L, testScheduler.currentTime)
        assertEquals(1, opened)
        assertEquals(1, closed)
    }

    @Test
    fun `an NPU that cannot be used ends the run without recording anything`() = runTest {
        val song = waiting("Song", updatedAt = now - 1)
        waiting("Next", updatedAt = now - 2)
        val before = memory[song.path]
        results = { AiResult.Failed("NpuUnavailableException: no NPU device", npu = true) }

        assertEquals(AiRunEnd.NO_NPU, runner().run())

        assertEquals(listOf("Song"), calls.map { it.title })
        assertEquals(before, memory[song.path])
        assertEquals(0, guard.open(checkNotNull(before)).deaths)
        assertEquals(1, closed)
    }

    // Songs the app died in

    @Test
    fun `a run stopped midway does the song again from the start next time`() = runTest {
        val song = waiting("Song")
        var ended: AiRunEnd? = null
        launch { ended = runner().run() }
        advanceTimeBy(90_000)
        plugged.value = false
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(AiRunEnd.UNPLUGGED, ended)

        plugged.value = true
        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(listOf(2, 3), calls.map { it.chunksRun })
        assertEquals(LyricsStatus.AI_TRANSCRIBED, memory[song.path]?.status)
        assertEquals(emptySet<String>(), guard.paths())
        assertEquals(0, guard.open(checkNotNull(memory[song.path])).deaths)
    }

    @Test
    fun `while the ledger cannot be read, the AI runs nothing and keeps its crash records`() = runTest {
        val song = waiting("song")
        guard.open(checkNotNull(memory[song.path]))
        memory.becomeUnreadable()

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(0, opened)
        assertEquals(emptyList<Call>(), calls)
        assertEquals(setOf(song.path), guard.paths())
    }

    @Test
    fun `a song the app died in once runs again`() = runTest {
        val song = waiting("Song")
        guard.open(memory[song.path] ?: error("no entry")).entering(AiStage.TRANSCRIBE)

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(3, calls.single().chunksRun)
        assertEquals(LyricsStatus.AI_TRANSCRIBED, memory[song.path]?.status)
        assertTrue(logs.toString(), logs.any { "Song" in it && "died" in it && "transcribe" in it })
    }

    @Test
    fun `a song the app died in twice is recorded as failed without running it again, and the others go on`() = runTest {
        val dying = waiting("Dying", updatedAt = now - 1)
        val next = waiting("Next", updatedAt = now - 2)
        val entry = memory[dying.path] ?: error("no entry")
        guard.open(entry).entering(AiStage.SEPARATE)
        guard.collectDeaths() // the first death, found by an earlier run
        guard.open(entry).entering(AiStage.SEPARATE) // the second

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(listOf("Next"), calls.map { it.title })
        assertEquals(LyricsStatus.AI_FAILED, memory[dying.path]?.status)
        assertFalse(sidecar(dying).exists())
        assertEquals(LyricsStatus.AI_TRANSCRIBED, memory[next.path]?.status)
        assertEquals(emptySet<String>(), guard.paths())
    }

    @Test
    fun `after three deaths in a row while timing lyrics, songs to time wait and the others run`() = runTest {
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it, "Song", "Artist", plainLyrics = "placeholder one\nplaceholder two"))) }
        val timed = (1..3).map { waiting("Timed $it", LyricsStatus.PLAIN_ONLY, updatedAt = now - it, id = it.toLong()) }
        val heard = waiting("Heard", updatedAt = now - 9)
        timed.forEach { track ->
            guard.open(memory[track.path] ?: error("no entry")).entering(AiStage.ALIGN)
            guard.collectDeaths()
        }

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(listOf("Heard"), calls.map { it.title })
        timed.forEach { assertEquals(LyricsStatus.PLAIN_ONLY, memory[it.path]?.status) }
        assertEquals(LyricsStatus.AI_TRANSCRIBED, memory[heard.path]?.status)
        assertTrue(logs.toString(), logs.any { "timing" in it && "off" in it })
    }

    @Test
    fun `once timing is off, songs run to the end do not turn it back on`() = runTest {
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it, "Song", "Artist", plainLyrics = "placeholder one\nplaceholder two"))) }
        val timed = (1..3).map { waiting("Timed $it", LyricsStatus.PLAIN_ONLY, updatedAt = now - it, id = it.toLong()) }
        waiting("Heard", updatedAt = now - 9)
        timed.forEach { track ->
            guard.open(memory[track.path] ?: error("no entry")).entering(AiStage.ALIGN)
            guard.collectDeaths()
        }
        assertEquals(AiRunEnd.DONE, runner().run())
        waiting("Later", updatedAt = now - 10)

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(listOf("Heard", "Later"), calls.map { it.title })
        timed.forEach { assertEquals(LyricsStatus.PLAIN_ONLY, memory[it.path]?.status) }
    }

    // A song whose audio file changes during its run

    @Test
    fun `a song whose audio file is replaced while the run asks LRCLIB is not written`() = runTest {
        val song = waiting("Timed", LyricsStatus.PLAIN_ONLY, id = 7)
        val before = memory[song.path] ?: error("no entry")
        client.onId = {
            File(song.path).writeBytes(byteArrayOf(4, 5, 6, 7)) // a music sync replaced it
            LrclibResult.Found(listOf(LrclibRecord(it, "Timed", "Artist", plainLyrics = "placeholder one\nplaceholder two")))
        }

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(before, memory[song.path])
        assertFalse(sidecar(song).exists())
        assertEquals(emptySet<String>(), guard.paths())
        assertTrue(logs.toString(), logs.any { "Timed" in it && "audio file changed" in it })
    }

    @Test
    fun `a song whose audio file is replaced during its run is not written, and the run goes on`() = runTest {
        val song = waiting("Song", updatedAt = now - 1)
        val next = waiting("Next", updatedAt = now - 2)
        val before = memory[song.path]
        results = {
            if (it.title == "Song") File(it.path).writeBytes(byteArrayOf(4, 5, 6, 7))
            AiResult.Transcribed(lrc(it.title), stats)
        }

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(listOf(3, 3), calls.map { it.chunksRun })
        assertEquals(before, memory[song.path])
        assertFalse(sidecar(song).exists())
        assertEquals(LyricsStatus.AI_TRANSCRIBED, memory[next.path]?.status)
        assertEquals(emptySet<String>(), guard.paths())
        assertTrue(logs.toString(), logs.any { "Song" in it && "audio file changed" in it })
    }

    @Test
    fun `after three deaths in a row in another stage the AI holds`() = runTest {
        (1..3).map { waiting("Song $it", updatedAt = now - it) }.forEach { track ->
            guard.open(memory[track.path] ?: error("no entry")).entering(AiStage.SEPARATE)
            guard.collectDeaths()
        }

        assertEquals(AiRunEnd.CRASHED, runner().run())

        assertTrue(calls.isEmpty())
    }

    // The queue sweep and the AI on the same song

    @Test
    fun `the AI holds a song from its check to its write, and the write still checks for an lrc`() = runTest {
        val song = waiting("Song")
        val held = mutableListOf<Boolean>()
        duringRun = {
            held += it.path in pipeline.aiPaths
            // Made meanwhile by something else: the AI's write must leave it alone.
            sidecar(it).writeText("[00:01.000]placeholder from elsewhere\n")
        }
        val runner = runner()
        assertFalse(song.path in pipeline.aiPaths)

        runner.run()

        assertEquals(listOf(true), held)
        assertFalse(song.path in pipeline.aiPaths)
        assertEquals(LyricsStatus.EXISTING, memory[song.path]?.status)
        assertEquals("[00:01.000]placeholder from elsewhere\n", sidecar(song).readText())
    }

    // Plain lyrics stay out of the ledger and the log

    @Test
    fun `plain lyrics fetched to time a song are neither stored in the ledger nor logged`() = runTest {
        val plain = listOf("placeholder plain alpha", "placeholder plain beta", "placeholder plain gamma", "placeholder plain delta")
        val timed = waiting("Timed", LyricsStatus.PLAIN_ONLY, updatedAt = now - 1, id = 7)
        val rejected = waiting("Rejected", LyricsStatus.PLAIN_ONLY, updatedAt = now - 2, id = 8)
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it, "Song", "Artist", plainLyrics = plain.joinToString("\n")))) }
        // As the engine does: the timed lines are the plain lyrics, and the .lrc is where they end up.
        results = { track ->
            if (track.title == "Timed") {
                AiResult.Aligned(plain.mapIndexed { k, line -> "[00:0${k + 1}.000]$line\n" }.joinToString(""), 0.8, stats)
            } else {
                AiResult.Rejected(RejectReason.LOW_AGREEMENT, 0.4)
            }
        }

        assertEquals(AiRunEnd.DONE, runner().run())

        assertEquals(listOf(7L, 8L), client.ids)
        assertEquals(listOf(plain.joinToString("\n"), plain.joinToString("\n")), calls.map { it.plain })
        assertEquals(LyricsStatus.AI_ALIGNED, memory[timed.path]?.status)
        assertEquals(LyricsStatus.AI_REJECTED, memory[rejected.path]?.status)
        val stored = LedgerJson.encode(memory.ledger.value)
        val logged = logs.joinToString("\n")
        assertTrue(logged.isNotEmpty())
        plain.forEach { line ->
            assertFalse("ledger holds \"$line\"", line in stored)
            assertFalse("log holds \"$line\"", line in logged)
        }
    }

    // A model that does not load

    @Test
    fun `a model that does not load is discarded, the song keeps its status and the run ends`() = runTest {
        val first = waiting("First", updatedAt = now - 1)
        waiting("Second", updatedAt = now - 2)
        val before = memory[first.path]
        val whisper = File(folder.root, AiModels.WHISPER)
        results = { AiResult.Failed("ModelLoadException: ${AiModels.WHISPER} could not be loaded", model = whisper) }
        var closedWhenDiscarded: Int? = null
        discard = { closedWhenDiscarded = closed }

        assertEquals(AiRunEnd.BAD_MODEL, runner().run())

        assertEquals(listOf(whisper), discarded)
        assertEquals(1, closedWhenDiscarded) // its engine let go of the model first
        assertEquals(before, memory[first.path])
        assertEquals(listOf("First"), calls.map { it.title })
        assertEquals(1, guard.open(checkNotNull(before)).deaths)
    }

    @Test
    fun `a model that does not load again after it was downloaded again is kept, and the AI pauses`() = runTest {
        val first = waiting("First", updatedAt = now - 1)
        waiting("Second", updatedAt = now - 2)
        val before = memory[first.path] ?: error("no entry")
        val whisper = File(folder.root, AiModels.WHISPER)
        results = { AiResult.Failed("ModelLoadException: ${AiModels.WHISPER} could not be loaded", model = whisper) }
        assertEquals(AiRunEnd.BAD_MODEL, runner().run())

        // Downloaded again: the same file.
        assertEquals(AiRunEnd.MODEL_FAILED, runner().run())

        assertEquals(listOf(whisper), discarded)
        assertEquals(listOf("First", "First"), calls.map { it.title })
        assertEquals(before, memory[first.path])
        assertTrue(loadFailures.paused())
        assertTrue(logs.toString(), logs.any { AiModels.WHISPER in it && "paused" in it })
        // Each counts as a death of the song in the stage it was in, so the song is not run forever: once the models
        // are deleted and downloaded anew, it is given up and the next song runs.
        assertEquals(2, guard.open(before).deaths)
        loadFailures.clear()
        assertEquals(AiRunEnd.BAD_MODEL, runner().run())
        assertEquals(listOf("First", "First", "Second"), calls.map { it.title })
        assertEquals(LyricsStatus.AI_FAILED, memory[first.path]?.status)
    }

    @Test
    fun `a model file that does not load is downloaded again once, then the AI pauses`() {
        val whisper = File(folder.root, AiModels.WHISPER)
        assertFalse(loadFailures.paused())

        assertTrue(loadFailures.failed(whisper))
        assertFalse(loadFailures.paused())
        assertFalse(loadFailures.failed(whisper))

        assertTrue(loadFailures.paused())
        assertTrue("kept across runs", failures().paused())
    }

    @Test
    fun `load failures count per model file, and start over with a new version or new models`() {
        val whisper = File(folder.root, AiModels.WHISPER)
        var pin = "first"
        val failures = failures { pin }
        assertTrue(failures.failed(whisper))
        assertTrue(failures.failed(File(folder.root, AiModels.MDX)))
        pin = "second" // another file pinned for the same model
        assertTrue(failures.failed(whisper))
        assertFalse(failures.paused())
        assertFalse(failures.failed(whisper))
        assertTrue(failures.paused())

        assertFalse(failures(version = 6, pin = { pin }).paused())
        failures.clear()
        assertFalse(failures.paused())
        assertTrue(failures.failed(whisper))
    }

    // Stopping before the models are deleted

    @Test
    fun `deleting the models waits until a cancelled run has finished its step`() = runTest {
        waiting("Song")
        val run = launch { runner().run() }
        advanceTimeBy(90_000) // halfway through the second chunk
        run.cancel()
        var deletedAt: Long? = null

        // WorkManager counts cancelled work as stopped at once; the run's hold on the models says when it really has.
        val result = whenStopped(MutableStateFlow(false), inUse, timeoutMs = 5 * 60_000) {
            deletedAt = testScheduler.currentTime
            true
        }

        assertEquals(true, result)
        assertEquals(120_000L, deletedAt)
        assertFalse(inUse.isLocked)
    }

    @Test
    fun `models a run does not let go of in time are not deleted`() = runTest {
        waiting("Song")
        chunkMs = 10 * 60_000
        val run = launch { runner().run() }
        advanceTimeBy(60_000)
        run.cancel()
        var deleted = false

        assertNull(whenStopped(MutableStateFlow(false), inUse, timeoutMs = 5 * 60_000) { deleted = true })

        assertFalse(deleted)
        assertEquals(6 * 60_000L, testScheduler.currentTime)
        run.join()
        assertFalse(inUse.isLocked)
    }

    /**
     * The models' hold, taken by a delete just before its timeout or a cancellation comes: on a real dispatcher either can
     * land between the lock and the end of the wait. Here the lock returns only after it, so that it always does.
     */
    private fun lateLock(models: Mutex) = object : Mutex by models {
        override suspend fun lock(owner: Any?) {
            models.lock(owner)
            awaitCancellation()
        }
    }

    @Test
    fun `a delete that times out right after it took the models' hold lets go of it`() = runTest {
        var deleted = false

        assertNull(whenStopped(MutableStateFlow(false), lateLock(inUse), timeoutMs = 60_000) { deleted = true })

        assertFalse(deleted)
        assertEquals(60_000L, testScheduler.currentTime)
        assertFalse(inUse.isLocked)
    }

    @Test
    fun `a delete cancelled right after it took the models' hold lets go of it`() = runTest {
        val deleting = launch { whenStopped(MutableStateFlow(false), lateLock(inUse), timeoutMs = 5 * 60_000) {} }
        advanceTimeBy(1_000)
        assertTrue(inUse.isLocked)

        deleting.cancelAndJoin()

        assertFalse(inUse.isLocked)
    }

    @Test
    fun `deleting the models waits until WorkManager no longer runs the AI`() = runTest {
        val running = MutableStateFlow(true)
        launch {
            delay(5_000)
            running.value = false
        }
        var deletedAt: Long? = null

        whenStopped(running, inUse, timeoutMs = 60_000) { deletedAt = testScheduler.currentTime }

        assertEquals(5_000L, deletedAt)
    }

    @Test
    fun `a run started while a cancelled one finishes its chunk waits for it`() = runTest {
        waiting("Song")
        val first = launch { runner().run() }
        advanceTimeBy(90_000)
        first.cancel()

        launch { runner().run() }.join()

        assertEquals(listOf(0L, 120_000L), calls.map { it.start })
    }

    // The card's progress line

    @Test
    fun `the progress line`() {
        val working = AiProgress(3, 41, "Song")
        assertEquals(NightStatus.Off, nightStatus(enabled = false, modelsReady = true, progress = working, hold = null, pluggedIn = true, waiting = 5))
        assertEquals(NightStatus.Working(3, 41, "Song"), nightStatus(true, true, working, AiHold.DAILY_LIMIT, pluggedIn = false, waiting = 5))
        assertEquals(NightStatus.Hot(3, 41), nightStatus(true, true, working.copy(hot = true), null, true, 5))
        assertEquals(NightStatus.NeedsModels, nightStatus(true, modelsReady = false, progress = null, hold = null, pluggedIn = true, waiting = 5))
        assertEquals(NightStatus.AllDone, nightStatus(true, true, null, AiHold.NOT_ALLOWED, pluggedIn = false, waiting = 0))
        assertEquals(NightStatus.WaitingForCharger(5), nightStatus(true, true, null, AiHold.DAILY_LIMIT, pluggedIn = false, waiting = 5))
        assertEquals(NightStatus.Held(5, AiHold.DAILY_LIMIT), nightStatus(true, true, null, AiHold.DAILY_LIMIT, pluggedIn = true, waiting = 5))
        // Paused until the next version or new models: shown plugged in or not.
        assertEquals(NightStatus.Held(5, AiHold.MODEL_FAILED), nightStatus(true, true, null, AiHold.MODEL_FAILED, pluggedIn = false, waiting = 5))
        assertEquals(NightStatus.Waiting(5), nightStatus(true, true, null, null, pluggedIn = true, waiting = 5))
    }
}
