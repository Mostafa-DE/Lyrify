package app.lyrify.media

import android.media.session.PlaybackState
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.TrackNames
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * A track as a player's media session describes it: the metadata of the current track
 * (with a duration) or a queue item's description (title and artist only). [mediaId] is
 * the player's own id (Samsung Music's is not a MediaStore `_ID`); it only helps place the
 * current track in the queue.
 */
data class SessionTrack(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val mediaId: String? = null,
)

data class QueueEntry(val queueId: Long, val track: SessionTrack)

/** What the session of [player] shows at one moment. Equal snapshots need no new work. */
data class SessionSnapshot(val current: SessionTrack?, val activeQueueId: Long, val queue: List<QueueEntry>, val player: Player? = null) {
    companion object {
        val EMPTY: SessionSnapshot = SessionSnapshot(null, SessionTracks.UNKNOWN_QUEUE_ID, emptyList())
    }
}

/** One `MediaStore.Audio.Media` row. */
data class AudioRow(
    val path: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

/** The file with its own tags: each copy of a song is looked up and checked against itself. */
fun AudioRow.toLocalTrack(): LocalTrack = LocalTrack(path, title, artist, album, durationMs)

/** The sweep's two lanes: [high] is the current track then the next queue items, [low] the rest of the queue. */
data class SweepOrder(val high: List<QueueEntry>, val low: List<QueueEntry>)

/** Matching session tracks to MediaStore rows, and the order the queue is swept in. */
object SessionTracks {
    /** Queue items after the current one that go in the HIGH lane. */
    const val PREFETCH_COUNT: Int = 5

    /** A file must be this close to the current track's duration, when the session gives one. */
    const val DURATION_TOLERANCE_MS: Long = 2_000

    /** More files than this with one name ("Intro", no artist) are not worth guessing between. */
    const val MAX_CANDIDATES: Int = 4

    /** `MediaSession.QueueItem.UNKNOWN_ID`: no active queue item. */
    const val UNKNOWN_QUEUE_ID: Long = -1

    /**
     * Every row that may be the file behind [track]: the same title and, when the session
     * names an artist, the same artist (both compared normalized, " - Topic" and the like
     * aside), and within [DURATION_TOLERANCE_MS] when the session gives a duration. Without
     * an artist the duration must match: a title alone (a queue item with no artist) matches
     * none. Several copies of a song all count; each gets lyrics checked against itself. More
     * than [MAX_CANDIDATES]: none, with a line for [log].
     */
    fun candidates(rows: List<AudioRow>, track: SessionTrack, log: (String) -> Unit = {}): List<AudioRow> {
        val title = TrackNames.normalize(track.title)
        val artist = artistKey(track.artist)
        val duration = track.durationMs
        if (title.isEmpty() || (artist == null && duration == null)) return emptyList()
        val matching = rows.filter { row ->
            TrackNames.normalize(row.title) == title &&
                (artist == null || artistKey(row.artist) == artist) &&
                (duration == null || abs(row.durationMs - duration) <= DURATION_TOLERANCE_MS)
        }
        if (matching.size > MAX_CANDIDATES) {
            log("${matching.size} files match the track: too generic, skipped")
            return emptyList()
        }
        return matching
    }

    /**
     * The current track, the next [PREFETCH_COUNT] queue items, then the rest of the queue
     * from there on, wrapping round to the start (with shuffle off, that is the play order).
     * The current item is the one with [activeQueueId]; without one (or an id not in the
     * queue), the single item with [current]'s media id, else its title and artist. A
     * current track not in the queue goes first and the whole queue follows.
     */
    fun sweepOrder(queue: List<QueueEntry>, activeQueueId: Long, current: SessionTrack?): SweepOrder {
        val index = queue.indexOfFirst { it.queueId == activeQueueId }.takeIf { it >= 0 }
            ?: current?.let { indexOf(queue, it) }
        if (index == null) return SweepOrder(listOfNotNull(current?.let { QueueEntry(UNKNOWN_QUEUE_ID, it) }), queue)
        val playing = QueueEntry(queue[index].queueId, current ?: queue[index].track)
        val after = queue.drop(index + 1)
        return SweepOrder(listOf(playing) + after.take(PREFETCH_COUNT), after.drop(PREFETCH_COUNT) + queue.take(index))
    }

    /**
     * The session as worth acting on: [read] runs on every signal, a reading the [same] as the
     * one before is dropped (players fire callbacks that change nothing), and a new one counts
     * once it has stood for [quietMs].
     */
    fun <T> settled(signals: Flow<*>, read: () -> T, quietMs: Long, same: (T, T) -> Boolean = { a, b -> a == b }): Flow<T> = channelFlow {
        signals.map { read() }.distinctUntilChanged(same).collectLatest {
            delay(quietMs)
            send(it)
        }
    }

    /** A failed cover comparison means changed, with no art; it must never end session updates. */
    internal fun <T : Any> compareArt(a: T?, b: T?, usable: (T) -> Boolean, same: (T, T) -> Boolean): ArtComparison<T> = try {
        if ((a != null && !usable(a)) || (b != null && !usable(b))) {
            ArtComparison(false, null)
        } else {
            ArtComparison(a === b || (a != null && b != null && same(a, b)), b)
        }
    } catch (_: RuntimeException) {
        ArtComparison(false, null)
    }

    private fun indexOf(queue: List<QueueEntry>, current: SessionTrack): Int? {
        val byMediaId = current.mediaId?.let { id -> queue.indices.filter { queue[it].track.mediaId == id } }.orEmpty()
        if (byMediaId.size == 1) return byMediaId.single()
        return queue.indices.singleOrNull { sameSong(queue[it].track, current) }
    }

    private fun sameSong(a: SessionTrack, b: SessionTrack): Boolean =
        TrackNames.normalize(a.title) == TrackNames.normalize(b.title) && artistKey(a.artist) == artistKey(b.artist)

    private fun artistKey(artist: String?): String? = TrackNames.cleanArtist(artist)?.let(TrackNames::normalize)
}

/** The cover to show after comparison, and whether nothing changed. */
internal data class ArtComparison<T>(val same: Boolean, val art: T?)

/** A media session's playback state, as far as choosing the session to follow goes. */
internal enum class Playback {
    PLAYING,

    /** Buffering, connecting, skipping or seeking: on its way between tracks or positions, neither a start nor a stop. */
    MOVING,

    /** Paused, stopped, none or an error. */
    STOPPED,
    ;

    companion object {
        /** [state] is a `PlaybackState` state; none (null) is [STOPPED]. */
        fun of(state: Int?): Playback = when (state) {
            PlaybackState.STATE_PLAYING -> PLAYING
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            -> MOVING
            else -> STOPPED
        }
    }
}

/**
 * Every active media session, to choose the one the user listens to ([C] is a MediaController
 * in the app). Main thread only. Each active session has a callback ([register]) that reports
 * its playback ([playbackChanged]): the system lists the sessions again when one starts or
 * ends, not when one starts playing. Between [stop] and [start] there are none, as for
 * [SessionFollower].
 *
 * A player between tracks is not a player that stopped: buffering and the like change nothing,
 * a stop shorter than [BETWEEN_TRACKS_MS] is no stop, and a session that ended while playing
 * is kept that long, since a player may replace its session by a new one (a song started from
 * its own search).
 */
internal class ActiveSessions<C : Any>(
    private val sameSession: (C, C) -> Boolean,
    /** Whether two sessions are of the same app. */
    private val sameApp: (C, C) -> Boolean,
    /** A session's playback now: read when it is listed, and when the sessions are read again ([refresh]). */
    private val playback: (C) -> Playback,
    private val register: (C) -> Unit,
    private val unregister: (C) -> Unit,
    /** Elapsed realtime. */
    private val clock: () -> Long,
) {
    /**
     * A session, the controller its callback is on; whether it plays, since when (its last start
     * after a real stop, or when it was listed playing), and when it last stopped.
     */
    private data class Watched<C>(val session: C, val playing: Boolean, val since: Long, val appeared: Long, val stoppedAt: Long? = null) {
        /** Playing, or stopped too short a time ago to count. */
        fun plays(now: Long): Boolean = playing || stoppedAt?.let { now - it < BETWEEN_TRACKS_MS } == true

        /** After [playback] at [now]: playing again within [BETWEEN_TRACKS_MS] of a stop keeps [since]. */
        fun with(playback: Playback, now: Long): Watched<C> = when {
            playback == Playback.PLAYING && !playing -> copy(playing = true, since = if (plays(now)) since else now, stoppedAt = null)
            playback == Playback.STOPPED && playing -> copy(playing = false, stoppedAt = now)
            else -> this
        }
    }

    /** In the system's priority order. */
    private var sessions: List<Watched<C>> = emptyList()

    /** Sessions no longer listed that stopped playing less than [BETWEEN_TRACKS_MS] ago; their callbacks are gone. */
    private var gone: List<Watched<C>> = emptyList()
    private var running: Boolean = false
    private var appearance: Long = 0

    fun start() {
        running = true
    }

    /**
     * [active] are the sessions now, most important first: a callback for each new one, none any
     * more for those gone. A session listed again keeps the controller its callback is on; one
     * back within [BETWEEN_TRACKS_MS] of leaving keeps its place.
     */
    fun update(active: List<C>) {
        if (!running) return
        val now = clock()
        appearance++
        val left = sessions.filter { old -> active.none { sameSession(it, old.session) } }
        left.forEach { unregister(it.session) }
        val recent = gone + left.map { it.with(Playback.STOPPED, now) }
        sessions = active.map { session ->
            sessions.firstOrNull { sameSession(it.session, session) } ?: run {
                val before = recent.firstOrNull { sameSession(it.session, session) } ?: Watched(session, playing = false, since = now, appeared = appearance)
                before.copy(session = session.also(register)).with(playback(session), now)
            }
        }
        gone = recent.filter { old -> old.plays(now) && sessions.none { sameSession(it.session, old.session) } }
    }

    /** [session] ended, before the system lists the sessions again. */
    fun ended(session: C) = update(sessions.map { it.session }.filterNot { sameSession(it, session) })

    /**
     * [session] reports [playback]; true when it started or stopped playing. Other changes of its
     * state (a new position every second, buffering, paused to stopped) change nothing.
     */
    fun playbackChanged(session: C, playback: Playback): Boolean {
        val index = sessions.indexOfFirst { sameSession(it.session, session) }
        if (!running || index < 0) return false
        val watched = sessions[index].with(playback, clock())
        if (watched === sessions[index]) return false
        sessions = sessions.mapIndexed { i, old -> if (i == index) watched else old }
        return true
    }

    /** Reads every session's playback again, for a start or stop no callback reported; true when there was one. */
    fun refresh(): Boolean = sessions.map { it.session }.filter { playbackChanged(it, playback(it)) }.isNotEmpty()

    /**
     * The session to follow: of the sessions playing, the one that started last, whichever app it
     * is (a tie keeps [followed], else goes by the system's order), so the session followed stays
     * followed while it plays, until another one starts. [followed] keeps its place between tracks
     * (see [ActiveSessions]), and a new session of its app carries on from it. With none playing,
     * [followed] (or its app's new session) while it is active. A session followed that ended
     * while playing is chosen still, though no longer listed, until [BETWEEN_TRACKS_MS] have
     * passed or its app lists a new session. Null when there is none.
     */
    fun choose(followed: C?): C? {
        val now = clock()
        val own = followed?.let { f -> (sessions + gone).firstOrNull { sameSession(it.session, f) } }
        val heir = followed?.let { f ->
            val app = sessions.filter { sameApp(it.session, f) }
            app.firstOrNull { it === own && it.playing }
                ?: app.filter { it.playing }.maxByOrNull { it.since }
                ?: app.maxByOrNull { it.appeared }
        }
        val followedSince = listOfNotNull(own, heir).filter { it.plays(now) }.maxOfOrNull { it.since }
        val playing = sessions.filter { it.playing && it !== heir }
        val latest = playing.maxOfOrNull { it.since }
        return when {
            followedSince != null && (latest == null || followedSince >= latest) -> (heir ?: own)?.session
            latest != null -> playing.first { it.since == latest }.session
            else -> heir?.session
        }
    }

    fun stop() {
        running = false
        sessions.forEach { unregister(it.session) }
        sessions = emptyList()
        gone = emptyList()
    }

    companion object {
        /** A stop or an end shorter than this is a player between tracks. */
        const val BETWEEN_TRACKS_MS: Long = 5_000
    }
}

/**
 * Which media session the listener follows ([C] is a MediaController in the app). Main thread
 * only, except [followed]. Between [stop] and [start] nothing is followed: a callback queued on
 * the main thread before a stop must not register a new session callback that nothing would
 * unregister.
 */
internal class SessionFollower<C : Any>(
    private val sameSession: (C, C) -> Boolean,
    private val register: (C) -> Unit,
    private val unregister: (C) -> Unit,
) {
    /** Read by snapshots on a background thread. */
    @Volatile
    var followed: C? = null
        private set

    var running: Boolean = false
        private set

    fun start() {
        running = true
    }

    /** Follows [session] (null: none) instead of the current one; true when that changed anything. */
    fun follow(session: C?): Boolean {
        if (!running) return false
        val current = followed
        if (current == null && session == null) return false
        if (current != null && session != null && sameSession(current, session)) return false
        current?.let(unregister)
        followed = session
        session?.let(register)
        return true
    }

    fun stop() {
        running = false
        followed?.let(unregister)
        followed = null
    }
}
