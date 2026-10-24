package app.lyrify.media

import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Samsung Music track playing now. [resolved] is false while its files are being looked
 * up in MediaStore; [paths] stays empty when it is not a local file Lyrify can match, and
 * holds every copy when the song is in several folders.
 */
data class CurrentTrack(
    val title: String,
    val artist: String?,
    val paths: List<String> = emptyList(),
    val resolved: Boolean = false,
)

/** The now-playing card's line. */
sealed interface PlayingStatus {
    data object Idle : PlayingStatus

    data object FindingFile : PlayingStatus

    data object NotInLibrary : PlayingStatus

    data object LookingUp : PlayingStatus

    data class Done(val status: LyricsStatus) : PlayingStatus
}

fun playingStatus(track: CurrentTrack?, ledger: LedgerState): PlayingStatus = when {
    track == null -> PlayingStatus.Idle
    !track.resolved -> PlayingStatus.FindingFile
    track.paths.isEmpty() -> PlayingStatus.NotInLibrary
    else -> track.paths.firstNotNullOfOrNull { ledger.entries[it]?.status }?.let(PlayingStatus::Done) ?: PlayingStatus.LookingUp
}

/** One connection of a [NowPlayingListener]; only the newest one can disconnect or change what is shown. */
class ListenerConnection internal constructor()

/** Process-wide: whether a [NowPlayingListener] is connected, the track it sees and how far its queue sweep is. */
object NowPlaying {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _track = MutableStateFlow<CurrentTrack?>(null)
    val track: StateFlow<CurrentTrack?> = _track.asStateFlow()

    private val _sweep = MutableStateFlow<SweepProgress?>(null)
    val sweep: StateFlow<SweepProgress?> = _sweep.asStateFlow()

    private var current: ListenerConnection? = null

    @Synchronized
    internal fun connect(): ListenerConnection {
        val connection = ListenerConnection()
        current = connection
        _connected.value = true
        return connection
    }

    /**
     * Android can connect a new listener instance before it destroys the old one, so only
     * the newest connection's end counts.
     */
    @Synchronized
    internal fun disconnect(connection: ListenerConnection) {
        if (current !== connection) return
        current = null
        _connected.value = false
        _track.value = null
        _sweep.value = null
    }

    /** Ignored unless [connection] is the newest one: work that outlives its listener must not show. */
    @Synchronized
    internal fun setTrack(connection: ListenerConnection, track: CurrentTrack?) {
        if (current === connection) _track.value = track
    }

    @Synchronized
    internal fun setSweep(connection: ListenerConnection, progress: SweepProgress) {
        if (current === connection) _sweep.value = progress
    }
}
