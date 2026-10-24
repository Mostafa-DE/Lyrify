package app.lyrify.media

import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class) // testScheduler: virtual time
class SessionTracksTest {
    private fun row(path: String, title: String = "Nemo", artist: String? = "Nightwish", durationMs: Long = 276_000, album: String? = "Once") =
        AudioRow(path, title, artist, album, durationMs)

    private val logs = mutableListOf<String>()

    private fun candidates(rows: List<AudioRow>, track: SessionTrack) = SessionTracks.candidates(rows, track) { logs += it }

    // Files for a session track

    @Test
    fun `Camel - both copies of a song in two folders are candidates`() {
        val kjb = row("/SdCardBackUp/Music/kjb/02. Mystic Queen.mp3", "Mystic Queen", "Camel", 341_000, "Camel")
        val de = row("/SdCardBackUp/Music/de/camel/02. Mystic Queen.mp3", "Mystic Queen", "Camel", 341_000, "Camel")
        val queueItem = SessionTrack("Mystic Queen", "Camel")
        assertEquals(listOf(kjb, de), candidates(listOf(kjb, row("/x.mp3", "Lady Fantasy", "Camel"), de), queueItem))
    }

    @Test
    fun `titles and artists are compared normalized`() {
        val rows = listOf(row("/a.mp3", title = "NEMO "), row("/b.mp3", artist = "Nightwish - Topic"), row("/c.mp3", artist = "nightwish"))
        assertEquals(rows, candidates(rows, SessionTrack("nemo", "Nightwish")))
    }

    @Test
    fun `rows of another artist or title are not candidates`() {
        val rows = listOf(row("/a.mp3", artist = "Metallica"), row("/b.mp3", title = "Nemo 2"), row("/c.mp3", artist = null))
        assertEquals(emptyList<AudioRow>(), candidates(rows, SessionTrack("Nemo", "Nightwish")))
    }

    @Test
    fun `without an artist in the session any artist is a candidate`() {
        val rows = listOf(row("/a.mp3", artist = "Nightwish"), row("/b.mp3", artist = null))
        assertEquals(rows, candidates(rows, SessionTrack("Nemo", null)))
        assertEquals(rows, candidates(rows, SessionTrack("Nemo", "<unknown>")))
    }

    @Test
    fun `the current track must also be within two seconds of the session's duration`() {
        val close = row("/a.mp3", durationMs = 277_900)
        val live = row("/b.mp3", durationMs = 310_000)
        assertEquals(listOf(close), candidates(listOf(close, live), SessionTrack("Nemo", "Nightwish", durationMs = 276_000)))
        assertEquals(listOf(close, live), candidates(listOf(close, live), SessionTrack("Nemo", "Nightwish")))
    }

    @Test
    fun `more than four candidates are skipped as too generic`() {
        val intros = (1..5).map { row("/$it/Intro.mp3", title = "Intro", artist = "Band $it") }
        assertEquals(emptyList<AudioRow>(), candidates(intros, SessionTrack("Intro", null)))
        assertTrue(logs.single().contains("Intro"))
        assertEquals(intros.take(SessionTracks.MAX_CANDIDATES), candidates(intros.take(4), SessionTrack("Intro", null)))
    }

    @Test
    fun `a blank title has no candidates`() {
        assertEquals(emptyList<AudioRow>(), candidates(listOf(row("/a.mp3", title = "")), SessionTrack(" ", null)))
    }

    @Test
    fun `a row becomes a local track with its own tags`() {
        assertEquals(LocalTrack("/m/1.mp3", "Nemo", "Nightwish", "Once", 276_000), row("/m/1.mp3").toLocalTrack())
    }

    // Sweep order

    private fun queue(vararg ids: Long) = ids.map { QueueEntry(it, SessionTrack(title = "T$it", artist = "A", mediaId = "m$it")) }

    private fun SweepOrder.titles() = high.map { it.track.title } to low.map { it.track.title }

    @Test
    fun `the current track, then the next five, then the rest of the queue from there`() {
        val current = SessionTrack("T12", "A", durationMs = 200_000)
        val order = SessionTracks.sweepOrder(queue(10, 11, 12, 13, 14, 15, 16, 17, 18, 19), activeQueueId = 12, current = current)
        assertEquals(listOf("T12", "T13", "T14", "T15", "T16", "T17") to listOf("T18", "T19", "T10", "T11"), order.titles())
        assertEquals(QueueEntry(12, current), order.high.first())
    }

    @Test
    fun `near the end of the queue the next items are fewer and the rest wraps around`() {
        val order = SessionTracks.sweepOrder(queue(1, 2, 3, 4), activeQueueId = 3, current = SessionTrack("T3", "A"))
        assertEquals(listOf("T3", "T4") to listOf("T1", "T2"), order.titles())
    }

    @Test
    fun `without metadata the active queue item is the current track`() {
        val order = SessionTracks.sweepOrder(queue(1, 2, 3), activeQueueId = 2, current = null)
        assertEquals(listOf("T2", "T3") to listOf("T1"), order.titles())
    }

    @Test
    fun `without an active id the current track is found in the queue by media id or name`() {
        val byMediaId = SessionTrack(title = "T2", artist = "A", mediaId = "m2")
        assertEquals(listOf("T2", "T3", "T4") to listOf("T1"), SessionTracks.sweepOrder(queue(1, 2, 3, 4), -1, byMediaId).titles())
        val byName = SessionTrack(title = "t2", artist = "a")
        assertEquals(listOf("t2", "T3") to listOf("T1"), SessionTracks.sweepOrder(queue(1, 2, 3), -1, byName).titles())
    }

    @Test
    fun `a current track not in the queue goes first and the whole queue follows`() {
        val twice = queue(1, 2) + QueueEntry(3, SessionTrack(title = "T1", artist = "A"))
        val order = SessionTracks.sweepOrder(twice, activeQueueId = -1, current = SessionTrack("T1", "A"))
        assertEquals(listOf("T1") to listOf("T1", "T2", "T1"), order.titles())
        assertEquals(SessionTracks.UNKNOWN_QUEUE_ID, order.high.single().queueId)
        assertEquals(SweepOrder(emptyList(), queue(1, 2)), SessionTracks.sweepOrder(queue(1, 2), activeQueueId = 99, current = null))
    }

    // Snapshots

    @Test
    fun `a change counts once it has stood for the quiet time`() = runTest {
        val signals = MutableStateFlow(0L)
        var session = "A"
        val seen = mutableListOf<Pair<Long, String>>()
        backgroundScope.launch { SessionTracks.settled(signals, { session }, quietMs = 1_000).collect { seen += testScheduler.currentTime to it } }
        runCurrent()

        advanceTimeBy(1_001)
        session = "B"
        signals.update { it + 1 }
        advanceTimeBy(300)
        session = "C"
        signals.update { it + 1 }
        advanceTimeBy(1_001)

        assertEquals(listOf(1_000L to "A", 2_301L to "C"), seen)
    }

    @Test
    fun `signals that change nothing neither repeat nor delay the work`() = runTest {
        val signals = MutableStateFlow(0L)
        var session = "A"
        val seen = mutableListOf<Pair<Long, String>>()
        backgroundScope.launch { SessionTracks.settled(signals, { session }, quietMs = 1_000).collect { seen += testScheduler.currentTime to it } }
        runCurrent()

        repeat(9) {
            advanceTimeBy(100)
            signals.update { it + 1 }
        }
        advanceTimeBy(2_000)
        assertEquals(listOf(1_000L to "A"), seen)

        session = "B"
        signals.update { it + 1 }
        repeat(5) {
            advanceTimeBy(300)
            signals.update { it + 1 }
        }
        advanceTimeBy(1_000)
        assertEquals(listOf(1_000L to "A", 3_900L to "B"), seen)
    }

    // Following a session

    private val events = mutableListOf<String>()
    private val follower = SessionFollower<String>(
        sameSession = { a, b -> a == b },
        register = { events += "+$it" },
        unregister = { events += "-$it" },
    )

    @Test
    fun `a session is followed once and switched cleanly`() {
        follower.start()
        assertTrue(follower.follow("samsung"))
        assertFalse(follower.follow("samsung"))
        assertTrue(follower.follow("samsung2"))
        assertTrue(follower.follow(null))
        assertNull(follower.followed)
        assertEquals(listOf("+samsung", "-samsung", "+samsung2", "-samsung2"), events)
    }

    @Test
    fun `nothing is followed after a shutdown, even by a callback queued before it`() {
        follower.start()
        follower.follow("samsung")
        follower.stop()

        assertFalse(follower.follow("samsung2"))

        assertNull(follower.followed)
        assertFalse(follower.running)
        assertEquals(listOf("+samsung", "-samsung"), events)
    }

    @Test
    fun `nothing is followed before the start, and a restart follows again`() {
        assertFalse(follower.follow("samsung"))
        follower.start()
        follower.stop()
        follower.start()
        assertTrue(follower.follow("samsung"))
        assertEquals(listOf("+samsung"), events)
    }
}
