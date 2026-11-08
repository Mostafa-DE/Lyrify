package app.lyrify.media

import android.graphics.Bitmap
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app whose media session Lyrify follows, with [name] as the phone shows it. */
data class Player(val packageName: String, val name: String) {
    /** The player Lyrify's `.lrc` files and its advice were checked against: advice about Samsung Music is for it only. */
    val isSamsungMusic: Boolean get() = packageName == SAMSUNG_MUSIC

    companion object {
        const val SAMSUNG_MUSIC: String = "com.sec.android.app.music"
    }
}

/**
 * The track playing now, in [player]. [resolved] is false while its files are being looked
 * up in MediaStore; [paths] stays empty when no file was found (a streamed song, or a file
 * MediaStore does not list), and holds every copy when the song is in several folders. [art] is the
 * cover the player put in its session, if any (the screen falls back to the file's own).
 */
data class CurrentTrack(
    val title: String,
    val artist: String?,
    val paths: List<String> = emptyList(),
    val resolved: Boolean = false,
    val player: Player? = null,
    val art: Bitmap? = null,
)

/** The now-playing card's line. */
sealed interface PlayingStatus {
    data object Idle : PlayingStatus

    data object FindingFile : PlayingStatus

    /** No file was found for the track: a streamed song, or a file MediaStore does not list. */
    data object NotInLibrary : PlayingStatus

    data object LookingUp : PlayingStatus

    /** A local file, but the song list cannot be read: nothing is known about it, and lookups wait. */
    data object Unreadable : PlayingStatus

    data class Done(val status: LyricsStatus) : PlayingStatus
}

fun playingStatus(track: CurrentTrack?, ledger: LedgerState): PlayingStatus = when {
    track == null -> PlayingStatus.Idle
    !track.resolved -> PlayingStatus.FindingFile
    track.paths.isEmpty() -> PlayingStatus.NotInLibrary
    !ledger.readable -> PlayingStatus.Unreadable
    else -> track.paths.firstNotNullOfOrNull { ledger.entries[it]?.status }?.let(PlayingStatus::Done) ?: PlayingStatus.LookingUp
}

/** One connection of a [NowPlayingListener]; only the newest one can disconnect or change what is shown. */
class ListenerConnection internal constructor()

/**
 * Process-wide: whether a [NowPlayingListener] is connected, the track it sees and how far its
 * queue sweep is; and "Retry failed now" presses, for that sweep.
 */
object NowPlaying {
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _track = MutableStateFlow<CurrentTrack?>(null)
    val track: StateFlow<CurrentTrack?> = _track.asStateFlow()

    private val _sweep = MutableStateFlow<SweepProgress?>(null)
    val sweep: StateFlow<SweepProgress?> = _sweep.asStateFlow()

    private val _retryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Handled by the connected listener's sweep; a press with no listener connected is dropped. */
    val retryRequests: SharedFlow<Unit> = _retryRequests.asSharedFlow()

    private var current: ListenerConnection? = null

    /** "Retry failed now". */
    fun requestRetry() {
        _retryRequests.tryEmit(Unit)
    }

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
