package app.lyrify.lyrics

import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStats
import app.lyrify.ai.RejectReason
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LyricsSource
import app.lyrify.data.LyricsStatus
import app.lyrify.data.recorded
import app.lyrify.overnight.AiJob
import app.lyrify.overnight.AiQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import kotlin.random.Random

/** The AI's results going through the pipeline's write path: the same marker, exclusive create, hash and removal as LRCLIB lyrics. */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class LyricsPipelineAiTest {
    @get:Rule
    val folder = TemporaryFolder()

    private object MiddleRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = 0.5
    }

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()
    private val stats = AiStats(audioSec = 200.0, vocalShare = 0.6, wordsPerMinute = 80.0, chunks = 4, transcribedSec = 120.0, segments = 5, lines = 4, stageMs = emptyMap())

    /** Placeholder lines, never real lyrics. */
    private val lrc = "[00:05.000]placeholder one\n[00:09.500]placeholder two\n[00:14.250]placeholder three\n[00:20.000]placeholder four\n"

    private fun TestScope.pipeline(writeBytes: (FileChannel, ByteBuffer) -> Unit = SidecarFile::writeFully) = LyricsPipeline(
        client = client,
        ledger = ledger,
        clock = { BASE + testScheduler.currentTime },
        random = MiddleRandom,
        writeBytes = writeBytes,
    )

    private val audioBytes = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 9, 8, 7)

    private fun audio(name: String = "Song A.mp3"): LocalTrack {
        val file = File(folder.root, name)
        file.writeBytes(audioBytes)
        file.setLastModified(AUDIO_MTIME)
        return LocalTrack(file.path, "Song A", "Artist A", "Album A", 200_000)
    }

    private fun sidecar(track: LocalTrack) = File(LrcFormat.sidecarPath(track.path) ?: error("no sidecar"))

    private fun assertAudioUntouched(track: LocalTrack) {
        val file = File(track.path)
        assertArrayEquals(audioBytes, file.readBytes())
        assertEquals(AUDIO_MTIME, file.lastModified())
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private suspend fun waiting(track: LocalTrack, status: LyricsStatus = LyricsStatus.NOT_FOUND, id: Long? = null, matched: Boolean? = null): LedgerEntry {
        val entry = recorded(null, track, status, id, BASE - DAY, durationMatched = matched)
        ledger.put(entry)
        return entry
    }

    // Lyrics the AI made

    @Test
    fun `heard lyrics are written next to the audio file and recorded as AI lyrics`() = runTest {
        val track = audio()
        waiting(track)

        assertEquals(LyricsStatus.AI_TRANSCRIBED, pipeline().recordAi(track, AiResult.Transcribed(lrc, stats)))

        val bytes = sidecar(track).readBytes()
        assertEquals(lrc, bytes.decodeToString())
        val entry = ledger[track.path]
        assertEquals(LyricsStatus.AI_TRANSCRIBED, entry?.status)
        assertEquals(LyricsSource.AI, entry?.source)
        assertEquals(sha256(bytes), entry?.sha256)
        assertEquals(bytes.size.toLong(), entry?.size)
        assertNull(entry?.pendingText)
        assertNull(entry?.nextAttemptAt)
        assertTrue(entry?.removable == true)
        assertEquals(setOf("Song A.mp3", "Song A.lrc"), folder.root.list().orEmpty().toSet())
        assertAudioUntouched(track)
    }

    @Test
    fun `timed lyrics keep the agreement and the LRCLIB id`() = runTest {
        val track = audio()
        waiting(track, LyricsStatus.PLAIN_ONLY, id = 7, matched = true)

        assertEquals(LyricsStatus.AI_ALIGNED, pipeline().recordAi(track, AiResult.Aligned(lrc, 0.82, stats)))

        val entry = ledger[track.path]
        assertEquals(LyricsStatus.AI_ALIGNED, entry?.status)
        assertEquals(0.82, entry?.agreement ?: 0.0, 0.0)
        assertEquals(7L, entry?.lrclibId)
        assertEquals(true, entry?.durationMatched)
        assertEquals(lrc, sidecar(track).readText())
    }

    @Test
    fun `the write is marked in the ledger before the file is made, with the AI as source`() = runTest {
        val track = audio()
        waiting(track)
        val marks = mutableListOf<LedgerEntry>()
        ledger.accepts = { entry ->
            if (entry.status == LyricsStatus.WRITING) {
                marks += entry
                assertFalse("no file before the marker", sidecar(track).exists())
            }
            true
        }

        pipeline().recordAi(track, AiResult.Aligned(lrc, 0.7, stats))

        assertEquals(1, marks.size)
        assertEquals(LyricsSource.AI, marks[0].source)
        assertEquals(0.7, marks[0].agreement ?: 0.0, 0.0)
        assertEquals(lrc, marks[0].pendingText)
        assertEquals(LyricsStatus.AI_ALIGNED, marks[0].writtenStatus())
    }

    @Test
    fun `no file is made when the write marker cannot be stored`() = runTest {
        val track = audio()
        waiting(track)
        ledger.accepts = { it.status != LyricsStatus.WRITING }

        assertEquals(LyricsStatus.AI_FAILED, pipeline().recordAi(track, AiResult.Transcribed(lrc, stats)))

        assertFalse(sidecar(track).exists())
        assertEquals(LyricsStatus.AI_FAILED, ledger[track.path]?.status)
    }

    @Test
    fun `a failed write leaves no partial file and counts as a failed run`() = runTest {
        val track = audio()
        waiting(track)
        val failing = pipeline { channel, buffer ->
            channel.write(ByteBuffer.wrap(buffer.array(), 0, 5))
            throw IOException("No space left on device")
        }

        assertEquals(LyricsStatus.AI_FAILED, failing.recordAi(track, AiResult.Transcribed(lrc, stats)))

        assertFalse(sidecar(track).exists())
        assertEquals(1, ledger[track.path]?.attempts)
    }

    @Test
    fun `an existing lrc is never overwritten`() = runTest {
        val track = audio()
        waiting(track)
        sidecar(track).writeText("the user's own file")

        assertEquals(LyricsStatus.EXISTING, pipeline().recordAi(track, AiResult.Transcribed(lrc, stats)))

        assertEquals("the user's own file", sidecar(track).readText())
        assertEquals(LyricsStatus.EXISTING, ledger[track.path]?.status)
    }

    @Test
    fun `AI lyrics Samsung Music would not show are rejected, not written`() = runTest {
        val track = audio()
        waiting(track)
        val twoLines = "[00:05.000]placeholder one\n[00:09.500]placeholder two\n"

        assertEquals(LyricsStatus.AI_REJECTED, pipeline().recordAi(track, AiResult.Transcribed(twoLines, stats)))

        assertEquals("INVALID_LRC", ledger[track.path]?.aiReason)
        assertFalse(sidecar(track).exists())
    }

    // Songs that changed while the AI ran

    @Test
    fun `a song removed by the user gets nothing`() = runTest {
        val track = audio()
        ledger.put(recorded(null, track, LyricsStatus.SYNCED, 5, BASE - DAY, sha256 = "ab").removed(BASE - DAY))

        assertEquals(LyricsStatus.REMOVED, pipeline().recordAi(track, AiResult.Transcribed(lrc, stats)))

        assertFalse(sidecar(track).exists())
        assertEquals(LyricsStatus.REMOVED, ledger[track.path]?.status)
    }

    @Test
    fun `a song that got lyrics meanwhile is left as it is`() = runTest {
        val track = audio()
        ledger.put(recorded(null, track, LyricsStatus.SYNCED, 5, BASE - DAY, sha256 = "ab", source = LyricsSource.LRCLIB))
        sidecar(track).writeText("lyrics from LRCLIB")

        assertEquals(LyricsStatus.SYNCED, pipeline().recordAi(track, AiResult.Aligned(lrc, 0.9, stats)))

        assertEquals("lyrics from LRCLIB", sidecar(track).readText())
    }

    @Test
    fun `a song the ledger does not know is left alone`() = runTest {
        val track = audio()
        assertNull(pipeline().recordAi(track, AiResult.Transcribed(lrc, stats)))
        assertFalse(sidecar(track).exists())
        assertNull(ledger[track.path])
    }

    @Test
    fun `the AI waits for a lookup of the same song, then leaves the lyrics it wrote`() = runTest {
        val track = audio()
        waiting(track, LyricsStatus.PLAIN_ONLY, id = 7, matched = true)
        val synced = "[00:01.00]placeholder a\n[00:02.00]placeholder b\n[00:03.00]placeholder c"
        client.answerMs = 1_000
        client.onGet = { LrclibResult.Found(listOf(LrclibRecord(7, "Song A", "Artist A", "Album A", 200.0, syncedLyrics = synced))) }
        val pipeline = pipeline()

        val lookup = async { pipeline.process(track, Lane.HIGH, force = true) }
        runCurrent()
        val ai = async { pipeline.recordAi(track, AiResult.Aligned(lrc, 0.9, stats)) }

        assertEquals(LyricsStatus.SYNCED, lookup.await()?.status)
        assertEquals(LyricsStatus.SYNCED, ai.await())
        assertEquals(LrcFormat.render(LrcFormat.parseLrclib(synced)), sidecar(track).readText())
        assertEquals(LyricsSource.LRCLIB, ledger[track.path]?.source)
    }

    // LRCLIB first: the AI waits for the user's check and for this version's lookup

    @Test
    fun `the AI's lyrics are not recorded for a song waiting for the user's check`() = runTest {
        val track = audio()
        val candidate = Candidate(5, "Song A", "Artist A", null, 200.0, synced = true)
        ledger.put(recorded(null, track, LyricsStatus.NEEDS_CHECK, null, BASE - DAY, candidates = listOf(candidate)))

        assertEquals(LyricsStatus.NEEDS_CHECK, pipeline().recordAi(track, AiResult.Transcribed(lrc, stats)))

        assertEquals(listOf(candidate), ledger[track.path]?.candidates)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `the AI's lyrics are not recorded for a song an older lookup found nothing for`() = runTest {
        val track = audio()
        val old = recorded(null, track, LyricsStatus.NOT_FOUND, null, BASE - DAY).copy(lookupRev = LOOKUP_REV - 1)
        ledger.put(old)

        assertEquals(LyricsStatus.NOT_FOUND, pipeline().recordAi(track, AiResult.Transcribed(lrc, stats)))

        assertEquals(old, ledger[track.path])
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `plain lyrics the user picked are timed by the AI with that record`() = runTest {
        val track = audio()
        val candidate = Candidate(5, "Song A", "Artist A", null, 200.0, synced = false)
        ledger.put(recorded(null, track, LyricsStatus.NEEDS_CHECK, null, BASE - DAY, candidates = listOf(candidate)))
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it, "Song A", "Artist A", null, 200.0, plainLyrics = "placeholder one\nplaceholder two"))) }
        val pipeline = pipeline()

        assertEquals(Checked.Done(LyricsStatus.PLAIN_ONLY), pipeline.choose(track.path, 5))
        assertEquals(AiJob(track, lrclibId = 5), AiQueue.of(ledger.ledger.value, BASE, canTake = { true }).single())
        assertEquals(LyricsStatus.AI_ALIGNED, pipeline.recordAi(track, AiResult.Aligned(lrc, 0.9, stats)))

        assertEquals(5L, ledger[track.path]?.lrclibId)
        assertEquals(lrc, sidecar(track).readText())
    }

    // Verdicts without a file

    @Test
    fun `an instrumental song is recorded and nothing is written`() = runTest {
        val track = audio()
        waiting(track)

        assertEquals(LyricsStatus.AI_INSTRUMENTAL, pipeline().recordAi(track, AiResult.Instrumental(0.12)))

        assertFalse(sidecar(track).exists())
        assertNull(ledger[track.path]?.nextAttemptAt)
    }

    @Test
    fun `rejected timing is recorded with its agreement`() = runTest {
        val track = audio()
        waiting(track, LyricsStatus.PLAIN_ONLY, id = 7, matched = true)

        assertEquals(LyricsStatus.AI_REJECTED, pipeline().recordAi(track, AiResult.Rejected(RejectReason.LOW_AGREEMENT, 0.41)))

        val entry = ledger[track.path]
        assertEquals(0.41, entry?.agreement ?: 0.0, 0.0)
        assertEquals("LOW_AGREEMENT", entry?.aiReason)
        assertEquals(7L, entry?.lrclibId)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `a song too long for the AI is rejected with that reason, for a later version to try again`() = runTest {
        val track = audio()
        waiting(track)

        assertEquals(LyricsStatus.AI_REJECTED, pipeline().recordAi(track, AiResult.Rejected(RejectReason.TOO_LONG, agreement = null)))

        assertEquals("TOO_LONG", ledger[track.path]?.aiReason)
        assertNull(ledger[track.path]?.agreement)
    }

    @Test
    fun `a failed run is counted and tried again later`() = runTest {
        val track = audio()
        waiting(track)
        val pipeline = pipeline()

        pipeline.recordAi(track, AiResult.Failed("IOException: unreadable"))
        val first = ledger[track.path]
        pipeline.recordAi(track, AiResult.Failed("IOException: unreadable"))

        assertEquals(1, first?.attempts)
        assertEquals(BASE + DAY, first?.aiRetryAt)
        assertEquals(2, ledger[track.path]?.attempts)
        assertFalse(sidecar(track).exists())
    }

    // Afterwards

    @Test
    fun `AI lyrics can be removed, and then the AI never tries the song again`() = runTest {
        val track = audio()
        waiting(track)
        val pipeline = pipeline()
        pipeline.recordAi(track, AiResult.Transcribed(lrc, stats))

        assertEquals(Removal.REMOVED, pipeline.remove(track.path))

        assertFalse(sidecar(track).exists())
        val entry = ledger[track.path]
        assertEquals(LyricsStatus.REMOVED, entry?.status)
        assertFalse(entry?.status?.waitsForAi == true)
        assertEquals(LyricsStatus.REMOVED, pipeline.recordAi(track, AiResult.Transcribed(lrc, stats)))
        assertFalse(sidecar(track).exists())
        assertAudioUntouched(track)
    }

    @Test
    fun `changed AI lyrics are not removed`() = runTest {
        val track = audio()
        waiting(track)
        val pipeline = pipeline()
        pipeline.recordAi(track, AiResult.Transcribed(lrc, stats))
        sidecar(track).appendText("[00:30.000]added by hand\n")

        assertEquals(Removal.CHANGED, pipeline.remove(track.path))

        assertTrue(sidecar(track).exists())
        assertEquals(LyricsStatus.AI_TRANSCRIBED, ledger[track.path]?.status)
    }

    @Test
    fun `AI lyrics on disk stay as they are when the song comes up again`() = runTest {
        val track = audio()
        waiting(track)
        val pipeline = pipeline()
        pipeline.recordAi(track, AiResult.Transcribed(lrc, stats))

        assertEquals(LyricsStatus.AI_TRANSCRIBED, pipeline.process(track)?.status)

        assertEquals(0, client.requests)
        assertEquals(LyricsStatus.AI_TRANSCRIBED, ledger[track.path]?.status)
    }

    @Test
    fun `AI verdicts are not looked up on LRCLIB again`() = runTest {
        val track = audio()
        waiting(track, LyricsStatus.PLAIN_ONLY, id = 7, matched = true)
        val pipeline = pipeline()
        pipeline.recordAi(track, AiResult.Rejected(RejectReason.LOW_AGREEMENT, 0.3))

        assertEquals(LyricsStatus.AI_REJECTED, pipeline.process(track)?.status)

        assertEquals(0, client.requests)
    }

    @Test
    fun `an AI write the app's death cut short is settled as AI lyrics`() = runTest {
        val heard = audio("Heard.mp3")
        val timed = audio("Timed.mp3")
        listOf(heard to null, timed to 0.8).forEach { (track, agreement) ->
            val bytes = LrcFormat.encode(lrc)
            ledger.put(
                recorded(
                    null, track, LyricsStatus.WRITING, null, BASE - DAY,
                    sha256 = sha256(bytes), size = bytes.size.toLong(), pendingText = lrc, source = LyricsSource.AI, agreement = agreement,
                ),
            )
            sidecar(track).writeBytes(bytes)
        }
        val pipeline = pipeline()

        assertEquals(LyricsStatus.AI_TRANSCRIBED, pipeline.process(heard)?.status)
        assertEquals(LyricsStatus.AI_ALIGNED, pipeline.process(timed)?.status)

        assertEquals(LyricsSource.AI, ledger[timed.path]?.source)
        assertEquals(0.8, ledger[timed.path]?.agreement ?: 0.0, 0.0)
        assertTrue(ledger[heard.path]?.removable == true)
        assertEquals(0, client.requests)
    }

    @Test
    fun `LRCLIB lyrics are recorded with LRCLIB as source`() = runTest {
        val track = audio()
        val synced = "[00:01.00]placeholder a\n[00:02.00]placeholder b\n[00:03.00]placeholder c"
        client.onGet = { LrclibResult.Found(listOf(LrclibRecord(9, "Song A", "Artist A", "Album A", 200.0, syncedLyrics = synced))) }

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track)?.status)

        assertEquals(LyricsSource.LRCLIB, ledger[track.path]?.source)
    }

    // Plain lyrics for the AI to time

    @Test
    fun `plain lyrics are fetched by id in the background lane and never stored`() = runTest {
        val track = audio()
        val entry = waiting(track, LyricsStatus.PLAIN_ONLY, id = 7, matched = true)
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it, "Song A", "Artist A", plainLyrics = "placeholder one\nplaceholder two"))) }

        assertEquals(PlainLyrics.Found("placeholder one\nplaceholder two"), pipeline().plainLyrics(7))

        assertEquals(listOf(7L), client.ids)
        assertEquals(listOf(Lane.LOW), client.lanes)
        assertEquals(entry, ledger[track.path])
    }

    @Test
    fun `a record gone, without plain lyrics or instrumental gives none`() = runTest {
        val pipeline = pipeline()
        client.onId = { LrclibResult.NotFound }
        assertEquals(PlainLyrics.None, pipeline.plainLyrics(7))
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it, plainLyrics = " "))) }
        assertEquals(PlainLyrics.None, pipeline.plainLyrics(7))
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it, instrumental = true, plainLyrics = "placeholder"))) }
        assertEquals(PlainLyrics.None, pipeline.plainLyrics(7))
        client.onId = { LrclibResult.Found(listOf(LrclibRecord(it + 1, plainLyrics = "placeholder"))) }
        assertEquals(PlainLyrics.None, pipeline.plainLyrics(7))
    }

    @Test
    fun `LRCLIB busy is asked again after 2, 4 and 8 s, then unavailable`() = runTest {
        val asked = mutableListOf<Long>()
        client.onId = {
            asked += testScheduler.currentTime
            LrclibResult.Transient("HTTP 503")
        }

        assertTrue(pipeline().plainLyrics(7) is PlainLyrics.Unavailable)

        assertEquals(listOf(0L, 2_000L, 6_000L, 14_000L), asked)
    }

    private companion object {
        const val BASE = 1_800_000_000_000L
        const val DAY = 24 * 60 * 60_000L
        const val AUDIO_MTIME = 1_600_000_000_000L
    }
}
