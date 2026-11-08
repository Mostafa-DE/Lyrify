package app.lyrify.media

import android.media.session.PlaybackState
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
    private fun row(path: String, title: String = "Paper Comet", artist: String? = "Mira Solen", durationMs: Long = 276_000, album: String? = "Night Kites") =
        AudioRow(path, title, artist, album, durationMs)

    private val logs = mutableListOf<String>()

    private fun candidates(rows: List<AudioRow>, track: SessionTrack) = SessionTracks.candidates(rows, track) { logs += it }

    // Files for a session track

    @Test
    fun `The Glass Ferns - both copies of a song in two folders are candidates`() {
        val kjb = row("/SdCardBackUp/Music/kjb/02. Amber Compass.mp3", "Amber Compass", "The Glass Ferns", 341_000, "The Glass Ferns")
        val de = row("/SdCardBackUp/Music/de/the-glass-ferns/02. Amber Compass.mp3", "Amber Compass", "The Glass Ferns", 341_000, "The Glass Ferns")
        val queueItem = SessionTrack("Amber Compass", "The Glass Ferns")
        assertEquals(listOf(kjb, de), candidates(listOf(kjb, row("/x.mp3", "Velvet Orchard", "The Glass Ferns"), de), queueItem))
    }

    @Test
    fun `titles and artists are compared normalized`() {
        val rows = listOf(row("/a.mp3", title = "PAPER COMET "), row("/b.mp3", artist = "Mira Solen - Topic"), row("/c.mp3", artist = "mira solen"))
        assertEquals(rows, candidates(rows, SessionTrack("paper comet", "Mira Solen")))
    }

    @Test
    fun `rows of another artist or title are not candidates`() {
        val rows = listOf(row("/a.mp3", artist = "The Quiet Ferns"), row("/b.mp3", title = "Paper Comet 2"), row("/c.mp3", artist = null))
        assertEquals(emptyList<AudioRow>(), candidates(rows, SessionTrack("Paper Comet", "Mira Solen")))
    }

    @Test
    fun `without an artist in the session any artist is a candidate, within the duration`() {
        val rows = listOf(row("/a.mp3", artist = "Mira Solen"), row("/b.mp3", artist = null))
        val live = row("/c.mp3", durationMs = 310_000)
        assertEquals(rows, candidates(rows + live, SessionTrack("Paper Comet", null, durationMs = 276_000)))
        assertEquals(rows, candidates(rows + live, SessionTrack("Paper Comet", "<unknown>", durationMs = 277_500)))
    }

    @Test
    fun `a title alone, with no artist and no duration, matches no file`() {
        val rows = listOf(row("/a.mp3", artist = "Mira Solen"), row("/b.mp3", artist = null))
        assertEquals(emptyList<AudioRow>(), candidates(rows, SessionTrack("Paper Comet", null)))
        assertEquals(emptyList<AudioRow>(), candidates(rows, SessionTrack("Paper Comet", "<unknown>")))
    }

    @Test
    fun `the current track must also be within two seconds of the session's duration`() {
        val close = row("/a.mp3", durationMs = 277_900)
        val live = row("/b.mp3", durationMs = 310_000)
        assertEquals(listOf(close), candidates(listOf(close, live), SessionTrack("Paper Comet", "Mira Solen", durationMs = 276_000)))
        assertEquals(listOf(close, live), candidates(listOf(close, live), SessionTrack("Paper Comet", "Mira Solen")))
    }

    @Test
    fun `more than four candidates are skipped as too generic, and the log names no track`() {
        val intros = (1..5).map { row("/$it/Intro.mp3", title = "Intro", artist = "Band $it") }
        val intro = SessionTrack("Intro", null, durationMs = 276_000)
        assertEquals(emptyList<AudioRow>(), candidates(intros, intro))
        assertEquals(listOf("5 files match the track: too generic, skipped"), logs)
        assertEquals(intros.take(SessionTracks.MAX_CANDIDATES), candidates(intros.take(4), intro))
    }

    @Test
    fun `a blank title has no candidates`() {
        assertEquals(emptyList<AudioRow>(), candidates(listOf(row("/a.mp3", title = "")), SessionTrack(" ", null)))
    }

    @Test
    fun `a row becomes a local track with its own tags`() {
        assertEquals(LocalTrack("/m/1.mp3", "Paper Comet", "Mira Solen", "Night Kites", 276_000), row("/m/1.mp3").toLocalTrack())
    }

    // Finding the files: the player's id, then title and artist, then title and length alone

    /**
     * MediaStore as a list of rows, their `_ID`s 101, 102 and on; titles compared as SQLite's NOCASE does (ASCII letters
     * only). Records which queries were asked.
     */
    private class Library(private val rows: List<AudioRow>) : AudioLibrary {
        val asked = mutableListOf<String>()

        override fun byPath(path: String): AudioRow? = rows.firstOrNull { it.path == path }.also { asked += "path" }

        override fun byId(id: Long): AudioRow? = rows.getOrNull((id - FIRST_ID).toInt()).also { asked += "id" }

        override fun byTitle(title: String): List<AudioRow> = rows.filter { nocase(it.title) == nocase(title) }.also { asked += "title" }

        override fun byDuration(fromMs: Long, toMs: Long): List<AudioRow> = rows.filter { it.durationMs in fromMs..toMs }.also { asked += "duration" }

        private fun nocase(text: String) = text.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")

        companion object {
            const val FIRST_ID: Long = 101
        }
    }

    /** The paths [track] resolves to in [rows]; files in [gone] are no longer on disk. */
    private fun resolve(track: SessionTrack, rows: List<AudioRow>, gone: Set<String> = emptySet(), library: Library = Library(rows)): List<String> =
        SessionTracks.resolve(track, library, { it !in gone }) { logs += it }.map { it.path }

    private val playing = SessionTrack("Paper Comet", "Mira Solen", durationMs = 276_000)
    private val first = row("/music/a/Paper Comet.mp3")
    private val second = row("/music/b/Paper Comet.mp3")
    private val other = row("/music/c/Glass Harbour.mp3", title = "Glass Harbour")

    @Test
    fun `a media id that is a file's path finds that file, only when its title is the song's`() {
        assertEquals(listOf(second.path), resolve(playing.copy(mediaId = second.path), listOf(first, second)))
        assertEquals("resolve: mediaId=hit rule=mediaId", logs.last())
        // A path to a song of another title: the id means something else; the title and artist decide.
        assertEquals(listOf(first.path, second.path), resolve(playing.copy(mediaId = other.path), listOf(first, second, other)))
        assertEquals("resolve: mediaId=mismatch title=2 durationWindow=3 rule=title", logs.last())
    }

    @Test
    fun `a MediaStore audio URI finds that row, only when its title is the song's`() {
        val uri = "content://media/external/audio/media/102"
        assertEquals(listOf(second.path), resolve(playing.copy(mediaUri = uri), listOf(first, second)))
        assertEquals(listOf(first.path), resolve(playing.copy(mediaUri = uri), listOf(first, other)))
        assertEquals("resolve: mediaId=mismatch title=1 durationWindow=2 rule=title", logs.last())
        // Not an audio row's URI: never asked about.
        val library = Library(listOf(first, second))
        assertEquals(listOf(first.path, second.path), resolve(playing.copy(mediaUri = "content://media/external/images/media/102"), emptyList(), library = library))
        assertEquals(listOf("title", "duration"), library.asked)
    }

    @Test
    fun `a media id that is a MediaStore _ID finds that row, only when its title is the song's`() {
        assertEquals(listOf(second.path), resolve(playing.copy(mediaId = "102"), listOf(first, second)))
        assertEquals(listOf(first.path), resolve(playing.copy(mediaId = "102"), listOf(first, other)))
        assertEquals(listOf(first.path), resolve(playing.copy(mediaId = "109"), listOf(first)))
        assertEquals("resolve: mediaId=miss title=1 durationWindow=1 rule=title", logs.last())
    }

    @Test
    fun `a title that differs only by punctuation or accents is found among the files of its length`() {
        val accents = row("/music/Cafe.mp3", title = "Café Ceiling (Part 2)", durationMs = 276_500)
        val apostrophe = row("/music/Lanterns.mp3", title = "Don\u2019t Wake the Lanterns", durationMs = 275_000)
        val longer = row("/music/Cafe live.mp3", title = "Cafe Ceiling, Part 2", durationMs = 290_000)
        val rows = listOf(accents, apostrophe, longer)
        assertEquals(listOf(accents.path), resolve(SessionTrack("Cafe Ceiling, Part 2", "Mira Solen", durationMs = 276_000), rows))
        assertEquals("resolve: mediaId=none title=1 durationWindow=2 rule=title", logs.last())
        assertEquals(listOf(apostrophe.path), resolve(SessionTrack("Don't Wake the Lanterns", "Mira Solen", durationMs = 276_000), rows))
    }

    @Test
    fun `with no match by artist, the one file of the song's title and length is taken`() {
        val featured = row("/music/Paper Comet.mp3", artist = "Mira Solen; The Night Kites", durationMs = 277_000)
        assertEquals(listOf(featured.path), resolve(playing, listOf(featured, other)))
        assertEquals("resolve: mediaId=none title=1 durationWindow=2 rule=artistFallback", logs.last())
    }

    @Test
    fun `with no match by artist, two files of the song's title and length are not guessed between`() {
        val rows = listOf(first.copy(artist = "The Night Kites"), second.copy(artist = "Glass Harbour"))
        assertEquals(emptyList<String>(), resolve(playing, rows))
        assertEquals("resolve: mediaId=none title=2 durationWindow=2 rule=none reason=ambiguous", logs.last())
    }

    @Test
    fun `without a duration there is no length to search by, and no match without the artist`() {
        val library = Library(listOf(first.copy(artist = "The Night Kites")))
        assertEquals(emptyList<String>(), resolve(playing.copy(durationMs = null), emptyList(), library = library))
        assertEquals(listOf("title"), library.asked)
        assertEquals("resolve: mediaId=none title=1 durationWindow=0 rule=none reason=noMatch", logs.last())
    }

    @Test
    fun `a streamed song finds no file, and the log names no track`() {
        val streamed = SessionTrack("Lanterns Over Water", "The Quiet Harbour", durationMs = 201_000, mediaId = "spotify:track:0aB1", mediaUri = "https://example.com/a.mp3")
        assertEquals(emptyList<String>(), resolve(streamed, listOf(first, other)))
        assertEquals("resolve: mediaId=none title=0 durationWindow=0 rule=none reason=noRows", logs.last())
        resolve(playing.copy(mediaId = second.path), listOf(first, second))
        assertTrue(logs.none { "Paper" in it || "Mira" in it || "/music" in it || "Lanterns" in it || "spotify" in it })
    }

    @Test
    fun `files gone from disk and rows without a duration still never count`() {
        val noLength = row("/music/c/Paper Comet.mp3", durationMs = 0)
        assertEquals(listOf(second.path), resolve(playing, listOf(first, second), gone = setOf(first.path)))
        assertEquals(emptyList<String>(), resolve(playing.copy(durationMs = null), listOf(noLength)))
        assertEquals("resolve: mediaId=none title=1 durationWindow=0 rule=none reason=gone", logs.last())
        // A media id whose file is gone is no match either: the title and artist decide.
        assertEquals(listOf(second.path), resolve(playing.copy(mediaId = first.path), listOf(first, second), gone = setOf(first.path)))
        assertEquals(listOf(first.path, second.path), resolve(playing.copy(mediaId = "103", durationMs = null), listOf(first, second, noLength)))
    }

    @Test
    fun `the rules go in order - the player's id, then title and artist, then title and length alone`() {
        val stranger = row("/music/d/Paper Comet.mp3", artist = "The Night Kites")
        val library = Library(listOf(first, stranger))
        // By id: that file alone, nothing else asked.
        assertEquals(listOf(stranger.path), resolve(playing.copy(mediaId = "102"), emptyList(), library = library))
        assertEquals(listOf("id"), library.asked)
        // By title and artist: the file of another artist is not added as it would be alone.
        assertEquals(listOf(first.path), resolve(playing, listOf(first, stranger)))
        assertEquals(listOf(stranger.path), resolve(playing, listOf(stranger)))
        assertEquals(listOf("rule=mediaId", "rule=title", "rule=artistFallback"), logs.map { it.substringAfter("rule=").let { rule -> "rule=$rule" } })
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

    @Test
    fun `a new cover for the same track counts, the same cover handed over again does not`() = runTest {
        // A player hands over a new bitmap on every read: covers are told apart by their pixels.
        class Cover(val pixels: String)
        val signals = MutableStateFlow(0L)
        var pixels = "none"
        val seen = mutableListOf<Pair<Long, String>>()
        val read = { "Paper Comet" to Cover(pixels) }
        backgroundScope.launch {
            SessionTracks.settled(signals, read, quietMs = 1_000) { a, b -> a.first == b.first && a.second.pixels == b.second.pixels }
                .collect { seen += testScheduler.currentTime to it.second.pixels }
        }
        runCurrent()

        advanceTimeBy(1_001)
        // The player sets the cover after the title.
        pixels = "cover"
        signals.update { it + 1 }
        advanceTimeBy(1_001)
        // The metadata again, with the same cover in a new bitmap.
        repeat(3) {
            signals.update { it + 1 }
            advanceTimeBy(500)
        }
        advanceTimeBy(2_000)

        assertEquals(listOf(1_000L to "none", 2_001L to "cover"), seen)
    }

    @Test
    fun `an unusable cover counts as changed and is cleared, even for the same object`() {
        class Cover(var usable: Boolean = true)
        val cover = Cover()
        cover.usable = false
        val compared = SessionTracks.compareArt(cover, cover, { it.usable }) { _, _ -> error("must not compare") }
        assertFalse(compared.same)
        assertNull(compared.art)
    }

    @Test
    fun `a throwing cover comparison clears art and later session updates continue`() = runTest {
        val signals = MutableStateFlow(0L)
        var broken = false
        val seen = mutableListOf<String?>()
        val read = { if (broken) "broken" else "cover" }
        backgroundScope.launch {
            SessionTracks.settled(signals, read, quietMs = 10) { a, b ->
                SessionTracks.compareArt(a, b, { true }) { _, _ -> throw IllegalStateException("unusable") }.same
            }.collect { art ->
                seen += SessionTracks.compareArt("previous", art, { true }) { _, _ ->
                    if (broken) throw IllegalStateException("unusable") else false
                }.art
            }
        }
        advanceTimeBy(11)
        broken = true
        signals.update { it + 1 }
        advanceTimeBy(11)
        broken = false
        signals.update { it + 1 }
        advanceTimeBy(11)
        assertEquals(listOf("cover", null, "cover"), seen)
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


    // Choosing the session to follow

    /**
     * A media controller. [token] tells one app's sessions apart (a player may replace its session by a new one), and
     * the app gives a new controller for the same session each time it lists them ([copy]).
     */
    private data class Controller(val app: String, val token: Int = 0, val copy: Int = 0)

    private val samsung = Controller("com.sec.android.app.music")
    private val poweramp = Controller("com.maxmpz.audioplayer")
    private val spotify = Controller("com.spotify.music")
    private val podcasts = Controller("com.podcasts")

    /** Samsung Music's next session: a new token for the same app. */
    private val samsungNext = samsung.copy(token = 1)

    private val grace = ActiveSessions.BETWEEN_TRACKS_MS

    /** Each session's playback as the app reads it (when it is listed or checked again); stopped unless set. */
    private val playbackNow = mutableMapOf<Pair<String, Int>, Playback>()

    private fun playingNow(controller: Controller, playback: Playback = Playback.PLAYING) {
        playbackNow[controller.app to controller.token] = playback
    }

    /** Elapsed realtime. */
    private var now = 0L

    private val sessions = ActiveSessions<Controller>(
        sameSession = { a, b -> a.app == b.app && a.token == b.token },
        sameApp = { a, b -> a.app == b.app },
        playback = { playbackNow[it.app to it.token] ?: Playback.STOPPED },
        register = { events += "+${it.app}#${it.copy}" },
        unregister = { events += "-${it.app}#${it.copy}" },
        clock = { now },
    )

    /** [controller]'s session reports [playback] (by default: it plays) at [at]. */
    private fun play(controller: Controller, at: Long, playback: Playback = Playback.PLAYING): Boolean {
        now = at
        return sessions.playbackChanged(controller, playback)
    }

    private fun chosen(followed: Controller? = null): String? = sessions.choose(followed)?.app

    @Test
    fun `the session playing is followed, whichever app it is, and a paused one never takes over`() {
        sessions.start()
        playingNow(poweramp)
        sessions.update(listOf(samsung, poweramp, podcasts))

        assertEquals(poweramp.app, chosen())
        assertEquals(poweramp.app, chosen(followed = samsung))
    }

    @Test
    fun `of several sessions playing, the one that started last is followed`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify, podcasts))
        play(samsung, at = 1_000)
        play(spotify, at = 5_000)

        assertEquals(spotify.app, chosen(followed = samsung))

        play(samsung, at = 7_000, Playback.STOPPED)
        play(samsung, at = 7_000 + grace)
        assertEquals(samsung.app, chosen(followed = spotify))
    }

    @Test
    fun `another session's change does not swap the session followed for another one playing`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify, podcasts, poweramp))
        play(samsung, at = 1_000)
        play(spotify, at = 5_000)
        assertEquals(spotify.app, chosen(followed = samsung))

        // A position update every second while playing, a paused session stopping, the list again, a session ending.
        assertFalse(play(samsung, at = 9_000))
        assertFalse(play(podcasts, at = 9_500, Playback.STOPPED))
        sessions.update(listOf(podcasts, samsung.copy(copy = 1), spotify.copy(copy = 1), poweramp))
        sessions.ended(poweramp)

        assertEquals(spotify.app, chosen(followed = spotify))
    }

    @Test
    fun `a session that starts playing takes over, and the one before comes back once it has stopped for good`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify, podcasts))
        play(samsung, at = 1_000)
        play(spotify, at = 5_000)

        assertTrue(play(podcasts, at = 9_000))
        assertEquals(podcasts.app, chosen(followed = spotify))

        assertTrue(play(podcasts, at = 9_500, Playback.STOPPED))
        assertEquals(podcasts.app, chosen(followed = podcasts))
        now = 9_500 + grace
        assertEquals(spotify.app, chosen(followed = podcasts))
    }

    @Test
    fun `a session listed while it plays counts as starting then`() {
        sessions.start()
        now = 1_000
        playingNow(samsung)
        sessions.update(listOf(samsung))
        assertEquals(samsung.app, chosen())

        now = 5_000
        playingNow(spotify)
        sessions.update(listOf(spotify, samsung))
        assertEquals(spotify.app, chosen(followed = samsung))
    }

    @Test
    fun `at connect the session already playing is followed at once, with no callback needed`() {
        // As NowPlayingListener.onListenerConnected does: start, list the sessions with their playback now, choose.
        val follower = SessionFollower<Controller>(sameSession = { a, b -> a == b }, register = {}, unregister = {})
        follower.start()
        sessions.start()
        now = 1_000
        playingNow(samsung)
        playingNow(podcasts, Playback.MOVING)

        sessions.update(listOf(podcasts, poweramp, samsung))
        follower.follow(sessions.choose(follower.followed))

        assertEquals(samsung, follower.followed)
    }

    @Test
    fun `a session newly listed has its callback before its playback is read, so a start in between is not missed`() {
        val order = mutableListOf<String>()
        val listed = ActiveSessions<Controller>(
            sameSession = { a, b -> a == b },
            sameApp = { a, b -> a.app == b.app },
            playback = {
                order += "read ${it.app}"
                Playback.PLAYING
            },
            register = { order += "+${it.app}" },
            unregister = {},
            clock = { 0 },
        )
        listed.start()

        listed.update(listOf(samsung))

        assertEquals(listOf("+${samsung.app}", "read ${samsung.app}"), order)
        assertEquals(samsung, listed.choose(followed = null))
    }

    @Test
    fun `a start or stop that no callback reported is found when the sessions are read again`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify))
        assertNull(chosen())

        now = 2_000
        playingNow(samsung)
        assertTrue(sessions.refresh())
        assertEquals(samsung.app, chosen())
        assertFalse(sessions.refresh())

        now = 3_000
        playingNow(samsung, Playback.STOPPED)
        playingNow(spotify)
        assertTrue(sessions.refresh())
        assertEquals(spotify.app, chosen())
    }

    @Test
    fun `buffering, connecting, skipping and seeking are neither a start nor a stop`() {
        assertEquals(Playback.PLAYING, Playback.of(PlaybackState.STATE_PLAYING))
        listOf(
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS, PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
            PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
        ).forEach { assertEquals("state $it", Playback.MOVING, Playback.of(it)) }
        listOf(PlaybackState.STATE_NONE, PlaybackState.STATE_STOPPED, PlaybackState.STATE_PAUSED, PlaybackState.STATE_ERROR, null)
            .forEach { assertEquals("state $it", Playback.STOPPED, Playback.of(it)) }
    }

    @Test
    fun `buffering, skipping or a short stop between tracks keeps the session followed`() {
        sessions.start()
        sessions.update(listOf(spotify, samsung))
        play(spotify, at = 1_000)
        play(samsung, at = 2_000)
        assertEquals(samsung.app, chosen(followed = spotify))

        assertFalse(play(samsung, at = 3_000, Playback.MOVING))
        assertEquals(samsung.app, chosen(followed = samsung))

        // The next track: a stop, then playing again within the grace.
        assertTrue(play(samsung, at = 4_000, Playback.STOPPED))
        assertEquals(samsung.app, chosen(followed = samsung))
        now = 4_000 + grace - 1
        assertEquals(samsung.app, chosen(followed = samsung))
        assertTrue(play(samsung, at = 4_000 + grace - 1))
        assertEquals(samsung.app, chosen(followed = samsung))

        // A real pause: the other session playing is followed once the grace is over.
        play(samsung, at = 20_000, Playback.STOPPED)
        now = 20_000 + grace
        assertEquals(spotify.app, chosen(followed = samsung))
    }

    @Test
    fun `a short stop between tracks is no new start`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify))
        play(samsung, at = 1_000)
        play(spotify, at = 5_000)

        play(samsung, at = 6_000, Playback.STOPPED)
        play(samsung, at = 6_500)

        assertEquals(spotify.app, chosen(followed = spotify))
    }

    @Test
    fun `another app that starts playing still takes over, even while the one followed is between tracks`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify, podcasts, poweramp))
        play(samsung, at = 1_000)

        play(samsung, at = 2_000, Playback.MOVING)
        assertTrue(play(spotify, at = 2_100))
        assertEquals(spotify.app, chosen(followed = samsung))

        play(spotify, at = 3_000, Playback.STOPPED)
        assertTrue(play(podcasts, at = 3_100))
        assertEquals(podcasts.app, chosen(followed = spotify))

        // While the session followed is held after its end, too.
        now = 4_000
        sessions.ended(podcasts)
        assertEquals(podcasts.app, chosen(followed = podcasts))
        assertTrue(play(poweramp, at = 4_100))
        assertEquals(poweramp.app, chosen(followed = podcasts))
    }

    @Test
    fun `a new session of the app followed that plays is followed at once`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify))
        play(spotify, at = 1_000)
        play(samsung, at = 2_000)

        now = 3_000
        playingNow(samsungNext)
        sessions.update(listOf(samsungNext, samsung, spotify))

        assertEquals(samsungNext, sessions.choose(followed = samsung))
    }

    @Test
    fun `the session followed that ends while playing is held until its app's new session comes, playing or not yet`() {
        val follower = SessionFollower<Controller>(sameSession = { a, b -> a == b }, register = {}, unregister = {})
        val followed = mutableListOf<Controller?>()
        fun chooseNow() {
            if (follower.follow(sessions.choose(follower.followed))) followed += follower.followed
        }
        follower.start()
        sessions.start()
        sessions.update(listOf(samsung, spotify))
        play(spotify, at = 1_000)
        play(samsung, at = 2_000)
        chooseNow()

        // A song started from the player's own search: its session ends, then a new one comes, buffering first.
        now = 3_000
        sessions.ended(samsung)
        chooseNow()
        assertEquals(samsung, follower.followed)

        now = 3_500
        playingNow(samsungNext, Playback.MOVING)
        sessions.update(listOf(samsungNext, spotify))
        chooseNow()
        assertEquals(samsungNext, follower.followed)

        play(samsungNext, at = 4_000)
        chooseNow()
        now = 3_000 + grace
        chooseNow()

        assertEquals(listOf(samsung, samsungNext), followed)
    }

    @Test
    fun `the session followed that ended is let go once the grace is over, if its app lists no new one`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify))
        play(spotify, at = 1_000)
        play(samsung, at = 2_000)

        now = 3_000
        sessions.ended(samsung)
        now = 3_000 + grace - 1
        assertEquals(samsung.app, chosen(followed = samsung))
        now = 3_000 + grace
        assertEquals(spotify.app, chosen(followed = samsung))
        sessions.ended(spotify)
        now = 3_000 + 2 * grace
        assertNull(chosen(followed = spotify))
    }

    @Test
    fun `a session that leaves the list for a moment keeps its place when it comes back`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify))
        play(spotify, at = 1_000)
        play(samsung, at = 2_000)

        now = 3_000
        sessions.update(listOf(spotify))
        assertEquals(samsung.app, chosen(followed = samsung))

        now = 3_500
        playingNow(samsung, Playback.MOVING)
        sessions.update(listOf(samsung.copy(copy = 1), spotify))
        assertEquals(samsung.copy(copy = 1), sessions.choose(followed = samsung))
        now = 3_000 + grace
        assertEquals(spotify.app, chosen(followed = samsung))
    }

    @Test
    fun `sessions that started at the same moment keep the one followed, else the system's first`() {
        sessions.start()
        playingNow(samsung)
        playingNow(spotify)
        sessions.update(listOf(samsung, spotify))

        assertEquals(spotify.app, chosen(followed = spotify))
        assertEquals(samsung.app, chosen())
    }

    @Test
    fun `with none playing, the newest session of the app followed wins over its older ones`() {
        sessions.start()
        sessions.update(listOf(samsung, spotify))
        play(samsung, at = 1_000)
        play(samsung, at = 2_000, Playback.STOPPED)
        now = 2_000 + grace
        assertEquals(samsung, sessions.choose(followed = samsung))

        // The player makes a new session, buffering, while its old one, stopped, is still listed first.
        now = 10_000
        playingNow(samsungNext, Playback.MOVING)
        sessions.update(listOf(samsung, samsungNext, spotify))

        assertEquals(samsungNext, sessions.choose(followed = samsung))
        assertEquals(samsungNext, sessions.choose(followed = samsungNext))
    }

    @Test
    fun `appearance order is kept through reordered lists and later playback of an older session`() {
        sessions.start()
        sessions.update(listOf(samsung))
        playingNow(samsungNext, Playback.MOVING)
        sessions.update(listOf(samsung, samsungNext))
        assertEquals(samsungNext, sessions.choose(samsung))
        play(samsung, 1_000)
        play(samsung, 2_000, Playback.STOPPED)
        now += grace
        sessions.update(listOf(samsungNext, samsung))
        assertEquals(samsungNext, sessions.choose(samsung))
    }

    @Test
    fun `the session followed that plays stays followed over another of its app that started earlier`() {
        sessions.start()
        sessions.update(listOf(samsungNext, samsung))
        play(samsungNext, at = 1_000)
        play(samsung, at = 2_000)

        assertEquals(samsung, sessions.choose(followed = samsung))
    }

    @Test
    fun `with none playing, the session followed stays followed while it is there`() {
        sessions.start()
        sessions.update(listOf(poweramp, samsung, spotify))
        play(samsung, at = 1_000)
        play(samsung, at = 2_000, Playback.STOPPED)

        assertEquals(samsung.app, chosen(followed = samsung))
        now = 2_000 + grace
        assertEquals(samsung.app, chosen(followed = samsung))
        assertNull(chosen())
        sessions.update(listOf(poweramp, spotify))
        assertNull(chosen(followed = samsung))
        sessions.update(emptyList())
        assertNull(chosen(followed = samsung))
    }

    @Test
    fun `a session that ended is not followed any more, and loses its callback`() {
        sessions.start()
        sessions.update(listOf(samsung, poweramp))
        events.clear()

        sessions.ended(samsung)

        assertNull(chosen(followed = samsung))
        assertFalse(play(samsung, at = 1_000))
        assertEquals(listOf("-${samsung.app}#0"), events)
    }

    @Test
    fun `each active session has one callback, on the controller it was registered on`() {
        sessions.start()
        sessions.update(listOf(samsung, poweramp))
        sessions.update(listOf(spotify, samsung.copy(copy = 1), poweramp.copy(copy = 1)))
        sessions.update(listOf(spotify.copy(copy = 2)))

        assertEquals(
            listOf("+${samsung.app}#0", "+${poweramp.app}#0", "+${spotify.app}#0", "-${samsung.app}#0", "-${poweramp.app}#0"),
            events,
        )
        // The session chosen is the controller with the callback, not the newer copy.
        play(spotify.copy(copy = 3), at = 1_000)
        assertEquals(spotify, sessions.choose(followed = null))
    }

    @Test
    fun `no session is watched or chosen after a stop, even by a callback queued before it`() {
        sessions.start()
        playingNow(samsung)
        sessions.update(listOf(samsung, poweramp))
        sessions.stop()

        sessions.update(listOf(samsung, poweramp, spotify))
        assertFalse(play(spotify, at = 1_000))
        assertFalse(sessions.refresh())

        assertNull(chosen())
        assertEquals(listOf("+${samsung.app}#0", "+${poweramp.app}#0", "-${samsung.app}#0", "-${poweramp.app}#0"), events)
        sessions.start()
        assertNull(chosen())
        assertNull(chosen(followed = samsung))
    }
}
