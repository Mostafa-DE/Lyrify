package app.lyrify.lyrics

import app.lyrify.data.LyricsSource
import app.lyrify.data.LyricsStatus
import app.lyrify.data.Retry
import app.lyrify.data.recorded
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** The wider lookup (file names, loose searches) and the "Check these" list: candidates, the user's pick, the re-check. */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class LyricsPipelineCheckTest {
    @get:Rule
    val folder = TemporaryFolder()

    private object MiddleRandom : Random() {
        override fun nextBits(bitCount: Int): Int = 0

        override fun nextDouble(): Double = 0.5
    }

    private val client = FakeLrclib()
    private val ledger = MemoryLedger()
    private val logs = mutableListOf<String>()

    /** Placeholder words, never real lyrics. */
    private val synced = "[00:01.00]w0001\n[00:02.00]w0002\n[00:03.00]w0003\n[00:04.00]w0004\n[00:05.00]w0005\n[00:06.00]w0006"
    private val plain = "w0001\nw0002\nw0003"

    private fun record(id: Long, track: String = "Song A", artist: String = "Band A", duration: Double = 200.0, synced: String? = this.synced) =
        LrclibRecord(id, track, artist, "Album A", duration, plainLyrics = plain, syncedLyrics = synced)

    private fun TestScope.pipeline() = LyricsPipeline(
        client = client,
        ledger = ledger,
        clock = { BASE + testScheduler.currentTime },
        random = MiddleRandom,
        log = { logs += it },
    )

    private val audioBytes = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 5, 6, 7)

    private fun audio(name: String, title: String, artist: String?): LocalTrack {
        val file = File(folder.root, name)
        file.writeBytes(audioBytes)
        return LocalTrack(file.path, title, artist, null, 200_000)
    }

    /** "05- Song A" with no artist anywhere: only a loose search can find it. */
    private fun untagged(): LocalTrack = audio("05- Song A.mp3", "05- Song A", "<unknown>")

    private fun sidecar(track: LocalTrack) = File(LrcFormat.sidecarPath(track.path) ?: error("no sidecar"))

    /** Searches by title alone (`q=`) answer [records]; searches with an artist and exact lookups find nothing. */
    private fun looseFinds(vararg records: LrclibRecord) {
        client.onSearch = { if (it.artist == null) LrclibResult.Found(records.toList()) else LrclibResult.Found(emptyList()) }
    }

    private suspend fun LyricsPipeline.toCheck(track: LocalTrack = untagged()): LocalTrack {
        looseFinds(record(1), record(2, artist = "Band B", synced = null), record(3, duration = 230.0))
        assertEquals(LyricsStatus.NEEDS_CHECK, process(track)?.status)
        return track
    }

    // Loose searches and candidates

    @Test
    fun `a song without an artist gets candidates from a search by title, and waits for a check`() = runTest {
        val track = untagged()
        looseFinds(record(1), record(2, artist = "Band B", synced = null), record(3, duration = 230.0), record(4, track = "Song B"))

        assertEquals(Processed(LyricsStatus.NEEDS_CHECK), pipeline().process(track))

        assertEquals(emptyList<TrackQuery>(), client.gets)
        assertEquals(listOf(TrackQuery(null, "Song A")), client.searches)
        val entry = ledger[track.path]
        assertEquals(
            listOf(Candidate(1, "Song A", "Band A", "Album A", 200.0, synced = true), Candidate(2, "Song A", "Band B", "Album A", 200.0, synced = false)),
            entry?.candidates,
        )
        assertEquals(LOOKUP_REV, entry?.lookupRev)
        assertEquals(BASE + 30 * DAY, entry?.nextAttemptAt)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `nothing to offer leaves a song without an artist unidentified, after one search`() = runTest {
        val track = untagged()
        looseFinds(record(3, duration = 230.0))

        assertEquals(LyricsStatus.UNIDENTIFIED, pipeline().process(track)?.status)

        assertEquals(1, client.requests)
        assertEquals(emptyList<Candidate>(), ledger[track.path]?.candidates)
    }

    @Test
    fun `an uploader as the artist tag gets candidates, never lyrics written by itself`() = runTest {
        val track = audio("Band A - Song A (Official Video).mp3", "Band A - Song A (Official Video)", "Uploader")
        client.onGet = { LrclibResult.Found(listOf(record(1))) }
        looseFinds(record(1))

        assertEquals(LyricsStatus.NEEDS_CHECK, pipeline().process(track)?.status)

        assertEquals(listOf(TrackQuery("Uploader", "Band A - Song A")), client.gets)
        assertEquals(TrackQuery(null, "Band A Song A"), client.searches.last())
        assertEquals(listOf(1L), ledger[track.path]?.candidates?.map { it.id })
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `the file name finds lyrics the tags could not, and they are written by themselves`() = runTest {
        val track = audio("07 - Band A - Song A (MP3_320K).mp3", "Song A", null)
        client.onGet = { if (it == TrackQuery("Band A", "Song A")) LrclibResult.Found(listOf(record(1))) else LrclibResult.NotFound }

        assertEquals(LyricsStatus.SYNCED, pipeline().process(track)?.status)

        assertEquals(TrackQuery("Band A", "Song A"), client.gets.first())
        assertTrue(sidecar(track).exists())
    }

    @Test
    fun `a lookup asks LRCLIB at most twelve times, and never the same search twice`() = runTest {
        // Artist and title in the title, another pair in the file name, nothing found anywhere.
        val track = audio("Band B - Song B.mp3", "Band A - Song A (Live)", null)

        assertEquals(LyricsStatus.NOT_FOUND, pipeline().process(track)?.status)

        assertEquals(12, TrackNames.MAX_REQUESTS)
        assertEquals(10, client.requests)
        assertEquals(listOf("Band A - Song A", "Band B Song B"), client.searches.filter { it.artist == null }.map { it.title })
    }

    @Test
    fun `a busy loose search is an error, looked up again later`() = runTest {
        val track = untagged()
        client.onSearch = { LrclibResult.Transient("HTTP 503") }

        assertEquals(Processed(LyricsStatus.ERROR, lrclibBusy = true), pipeline().process(track))

        assertEquals(Retry.SESSION_ATTEMPTS, client.searches.size)
        assertEquals(BASE + 14_000 + 30 * MINUTE, ledger[track.path]?.nextAttemptAt)
    }

    @Test
    fun `this recording's plain lyrics are not second-guessed by a loose search`() = runTest {
        val track = audio("Song A.mp3", "Song A", "Band A")
        client.onGet = { LrclibResult.Found(listOf(record(1, synced = null))) }
        looseFinds(record(2, artist = "Band B"))

        assertEquals(LyricsStatus.PLAIN_ONLY, pipeline().process(track)?.status)

        assertTrue(client.searches.all { it.artist != null })
    }

    @Test
    fun `a version of another length can still be offered one of this length`() = runTest {
        val track = audio("Song A.mp3", "Song A", "Band A")
        client.onGet = { LrclibResult.Found(listOf(record(1, duration = 260.0))) }
        looseFinds(record(2, track = "Song A (Radio Edit)", duration = 201.0))

        assertEquals(LyricsStatus.NEEDS_CHECK, pipeline().process(track)?.status)

        assertEquals(listOf(2L), ledger[track.path]?.candidates?.map { it.id })
    }

    // The user's pick

    @Test
    fun `this one writes the synced lyrics through the exclusive create`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.toCheck()
        client.onId = { LrclibResult.Found(listOf(record(it))) }

        assertEquals(Checked.Done(LyricsStatus.SYNCED), pipeline.choose(track.path, 1))

        assertEquals(LrcFormat.render(LrcFormat.parseLrclib(synced)), sidecar(track).readText())
        val entry = ledger[track.path]
        assertEquals(LyricsStatus.SYNCED, entry?.status)
        assertEquals(1L, entry?.lrclibId)
        assertEquals(LyricsSource.LRCLIB, entry?.source)
        assertEquals(emptyList<Candidate>(), entry?.candidates)
        assertEquals(listOf(1L), client.ids)
        assertEquals(Lane.HIGH, client.lanes.last())
        assertArrayEquals(audioBytes, File(track.path).readBytes())
    }

    @Test
    fun `this one on plain lyrics keeps the record for the AI to time`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.toCheck()
        client.onId = { LrclibResult.Found(listOf(record(it, artist = "Band B", synced = null))) }

        assertEquals(Checked.Done(LyricsStatus.PLAIN_ONLY), pipeline.choose(track.path, 2))

        val entry = ledger[track.path]
        assertEquals(2L, entry?.lrclibId)
        assertEquals(true, entry?.durationMatched)
        assertTrue(entry?.status?.waitsForAi == true)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `a pick that is gone, or no longer of this length, changes nothing`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.toCheck()

        client.onId = { LrclibResult.NotFound }
        assertEquals(Checked.Gone, pipeline.choose(track.path, 1))
        client.onId = { LrclibResult.Found(listOf(record(it, duration = 230.0))) }
        assertEquals(Checked.Gone, pipeline.choose(track.path, 1))
        client.onId = { LrclibResult.Found(listOf(record(it).copy(instrumental = true))) }
        assertEquals(Checked.Gone, pipeline.choose(track.path, 1))

        assertEquals(LyricsStatus.NEEDS_CHECK, ledger[track.path]?.status)
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `a lyrics file that appears while plain lyrics are fetched is kept as it is`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.toCheck()
        client.onId = {
            sidecar(track).writeText("theirs")
            LrclibResult.Found(listOf(record(it, artist = "Band B", synced = null)))
        }

        assertEquals(Checked.Done(LyricsStatus.EXISTING), pipeline.choose(track.path, 2))

        assertEquals("theirs", sidecar(track).readText())
        assertEquals(LyricsStatus.EXISTING, ledger[track.path]?.status)
    }

    @Test
    fun `while the ledger cannot be read, nothing is looked up or written`() = runTest {
        val track = untagged()
        ledger.becomeUnreadable()
        val pipeline = pipeline()

        assertEquals(null, pipeline.process(track, Lane.LOW, force = true))

        assertEquals(0, client.requests)
        assertEquals(emptyList<LocalTrack>(), pipeline.staleLookups())
        assertFalse(sidecar(track).exists())
    }

    @Test
    fun `a pick while LRCLIB is busy changes nothing, after one try`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.toCheck()
        client.onId = { LrclibResult.Transient("HTTP 503") }

        assertEquals(Checked.Unavailable("HTTP 503"), pipeline.choose(track.path, 1))

        assertEquals(1, client.ids.size)
        assertEquals(LyricsStatus.NEEDS_CHECK, ledger[track.path]?.status)
    }

    @Test
    fun `a song that no longer waits for a check is left as it is`() = runTest {
        val track = untagged()
        ledger.put(recorded(null, track, LyricsStatus.NOT_FOUND, null, BASE))
        val pipeline = pipeline()

        assertEquals(Checked.Changed, pipeline.choose(track.path, 1))
        assertEquals(Checked.Changed, pipeline.decline(track.path))
        assertEquals(Checked.Changed, pipeline.choose("/nowhere.mp3", 1))

        assertEquals(0, client.requests)
        assertEquals(LyricsStatus.NOT_FOUND, ledger[track.path]?.status)
    }

    @Test
    fun `a lyrics file that appeared meanwhile is kept as it is`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.toCheck()
        val requests = client.requests
        sidecar(track).writeText("theirs")

        assertEquals(Checked.Done(LyricsStatus.EXISTING), pipeline.choose(track.path, 1))

        assertEquals("theirs", sidecar(track).readText())
        assertEquals(requests, client.requests)
    }

    @Test
    fun `none of these leaves the song to the AI and never offers it again`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.toCheck()

        assertEquals(Checked.Done(LyricsStatus.NOT_FOUND), pipeline.decline(track.path))

        val entry = ledger[track.path]
        assertEquals(LyricsStatus.NOT_FOUND, entry?.status)
        assertTrue(entry?.declined == true)
        assertFalse(entry?.lookupStale == true)
        assertTrue(entry?.status?.waitsForAi == true)
        assertEquals(emptyList<Candidate>(), entry?.candidates)
        // Looked up again: the same candidates are there, but the song is not offered again.
        val searches = client.searches.size
        assertEquals(LyricsStatus.NOT_FOUND, pipeline.process(track, force = true)?.status)
        assertEquals(searches, client.searches.size)
    }

    @Test
    fun `show lyrics gives the first lines, and logs and keeps none of them`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.toCheck()
        val before = ledger[track.path]
        client.onId = { LrclibResult.Found(listOf(record(it))) }

        assertEquals(LyricsPreview.Lines(listOf("w0001", "w0002", "w0003", "w0004")), pipeline.preview(1))

        client.onId = { LrclibResult.Found(listOf(record(it, synced = null))) }
        assertEquals(LyricsPreview.Lines(listOf("w0001", "w0002", "w0003")), pipeline.preview(2))
        client.onId = { LrclibResult.NotFound }
        assertEquals(LyricsPreview.None, pipeline.preview(3))
        client.onId = { LrclibResult.Transient("timeout") }
        assertEquals(LyricsPreview.Unavailable("timeout"), pipeline.preview(4))
        assertEquals(before, ledger[track.path])
        assertTrue(logs.none { "w000" in it })
    }

    @Test
    fun `search myself shows songs of this length, whatever their title`() = runTest {
        val pipeline = pipeline()
        val track = pipeline.toCheck()
        client.onSearch = { LrclibResult.Found(listOf(record(7, track = "Other Name", artist = "Band C"), record(8, duration = 230.0))) }

        val found = pipeline.search(track, "  other name band c ")

        assertEquals(TrackQuery(null, "other name band c"), client.searches.last())
        assertEquals(Searched.Found(listOf(Candidate(7, "Other Name", "Band C", "Album A", 200.0, synced = true))), found)
        assertEquals(Searched.Found(emptyList()), pipeline.search(track, "   "))
        client.onSearch = { LrclibResult.Transient("HTTP 429") }
        assertEquals(Searched.Unavailable("HTTP 429"), pipeline.search(track, "other"))
        assertEquals(Lane.HIGH, client.lanes.last())
    }

    // Looking up again what an older lookup found nothing for

    @Test
    fun `songs an older lookup found nothing for are due at once, and listed for the sweep while their file is there`() = runTest {
        val notFound = audio("Song A.mp3", "Song A", "Band A")
        val noArtist = untagged()
        val gone = LocalTrack(File(folder.root, "Gone.mp3").path, "Gone", "Band A", null, 200_000)
        val current = audio("Song C.mp3", "Song C", "Band C")
        listOf(notFound, noArtist, gone).forEachIndexed { index, track ->
            ledger.put(recorded(null, track, if (track == noArtist) LyricsStatus.UNIDENTIFIED else LyricsStatus.NOT_FOUND, null, BASE + index).copy(lookupRev = 0))
        }
        ledger.put(recorded(null, current, LyricsStatus.NOT_FOUND, null, BASE))
        val pipeline = pipeline()

        assertEquals(listOf(noArtist, notFound), pipeline.staleLookups())

        // Not due for 30 days, but looked up all the same, once.
        client.onGet = { LrclibResult.Found(listOf(record(1))) }
        assertEquals(LyricsStatus.SYNCED, pipeline.process(notFound, Lane.LOW)?.status)
        assertEquals(LyricsStatus.NOT_FOUND, pipeline.process(current, Lane.LOW)?.status)
        assertEquals(LyricsStatus.UNIDENTIFIED, pipeline.process(noArtist, Lane.LOW)?.status)
        val requests = client.requests
        assertEquals(LyricsStatus.UNIDENTIFIED, pipeline.process(noArtist, Lane.LOW)?.status)
        assertEquals(requests, client.requests)
        assertEquals(emptyList<LocalTrack>(), pipeline.staleLookups())
    }

    @Test
    fun `a look up again that is cut short stays to do, and the next sweep does it`() = runTest {
        val track = audio("Song A.mp3", "Song A", "Band A")
        ledger.put(recorded(null, track, LyricsStatus.NOT_FOUND, null, BASE).copy(lookupRev = 0))
        client.answerMs = 1_000
        val pipeline = pipeline()

        val lookup = async { pipeline.process(track, Lane.LOW) }
        delay(1_500)
        lookup.cancel()

        assertTrue(ledger[track.path]?.lookupStale == true)
        assertEquals(listOf(track), pipeline.staleLookups())
        assertEquals(LyricsStatus.NOT_FOUND, pipeline.process(track, Lane.LOW)?.status)
        assertEquals(emptyList<LocalTrack>(), pipeline.staleLookups())
    }

    private companion object {
        const val BASE: Long = 1_800_000_000_000
        const val MINUTE: Long = 60_000
        const val DAY: Long = 24 * 60 * MINUTE
    }
}
