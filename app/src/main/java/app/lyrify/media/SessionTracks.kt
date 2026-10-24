package app.lyrify.media

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
 * A track as Samsung Music's media session describes it: the metadata of the current track
 * (with a duration) or a queue item's description (title and artist only). [mediaId] is
 * Samsung's own id, not a MediaStore `_ID`; it only helps place the current track in the queue.
 */
data class SessionTrack(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val mediaId: String? = null,
)

data class QueueEntry(val queueId: Long, val track: SessionTrack)

/** What the session shows at one moment. Equal snapshots need no new work. */
data class SessionSnapshot(val current: SessionTrack?, val activeQueueId: Long, val queue: List<QueueEntry>) {
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
     * aside), and within [DURATION_TOLERANCE_MS] when the session gives a duration. Several
     * copies of a song all count; each gets lyrics checked against itself. More than
     * [MAX_CANDIDATES]: none, with a line for [log].
     */
    fun candidates(rows: List<AudioRow>, track: SessionTrack, log: (String) -> Unit = {}): List<AudioRow> {
        val title = TrackNames.normalize(track.title)
        if (title.isEmpty()) return emptyList()
        val artist = artistKey(track.artist)
        val duration = track.durationMs
        val matching = rows.filter { row ->
            TrackNames.normalize(row.title) == title &&
                (artist == null || artistKey(row.artist) == artist) &&
                (duration == null || abs(row.durationMs - duration) <= DURATION_TOLERANCE_MS)
        }
        if (matching.size > MAX_CANDIDATES) {
            log("${matching.size} files match \"${track.title}\" by ${track.artist ?: "no artist"}: too generic, skipped")
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
     * The session as worth acting on: [read] runs on every signal, a snapshot equal to the
     * one before is dropped (players fire callbacks that change nothing), and a new one counts
     * once it has stood for [quietMs].
     */
    fun <T> settled(signals: Flow<*>, read: () -> T, quietMs: Long): Flow<T> = channelFlow {
        signals.map { read() }.distinctUntilChanged().collectLatest {
            delay(quietMs)
            send(it)
        }
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
