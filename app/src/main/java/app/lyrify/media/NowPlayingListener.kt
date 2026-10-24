package app.lyrify.media

import android.content.ComponentName
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.logFailures
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.verbose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Follows Samsung Music's media session. Notification access is what lets an app read other
 * apps' media sessions; notifications themselves are not used. The system binds this
 * service while access is granted, so no foreground service is needed.
 *
 * When the track or the queue changes (debounced, and only for real changes), the whole
 * queue goes to a [QueueSweep]: the current track and the next few first, then the rest,
 * so `.lrc` files exist before songs start (Samsung reads lyrics when a track loads).
 */
class NowPlayingListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var sessions: MediaSessionManager? = null
    private var connection: ListenerConnection? = null

    /** Main thread only. Playback state also changes on play, pause and seek; only this part matters. */
    private var activeQueueId: Long? = null

    /**
     * Counts session callbacks; each one reads a snapshot, and only a changed snapshot starts
     * work. A StateFlow, so a change signalled before the collector subscribes is not lost.
     */
    private val changes = MutableStateFlow(0L)

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        follow(controllers.orEmpty())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()

        override fun onQueueChanged(queue: List<MediaSession.QueueItem>?) = changed()

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Players may update the state every second while playing; only a new active item matters.
            val active = state?.activeQueueItemId
            if (active == activeQueueId) return
            activeQueueId = active
            changed()
        }

        override fun onSessionDestroyed() = follow(emptyList())
    }

    /** Main thread only; ignores everything between a shutdown and the next connect. */
    private val follower = SessionFollower<MediaController>(
        sameSession = { a, b -> a.sessionToken == b.sessionToken },
        register = { it.registerCallback(callback, handler) },
        unregister = { it.unregisterCallback(callback) },
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        shutdown()
        val connected = NowPlaying.connect().also { connection = it }
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures).also { scope = it }
        val pipeline = Lyrify.pipeline(this)
        val sweep = sweep(connected, pipeline)
        serviceScope.launch { sweep.run() }
        serviceScope.launch { sweep.progress.collect { NowPlaying.setSweep(connected, it) } }
        serviceScope.launch { NowPlaying.retryRequests.collect { sweep.retry(pipeline.failed()) } }
        serviceScope.launch {
            var shown: SessionTrack? = null
            SessionTracks.settled(changes, ::snapshot, DEBOUNCE_MS).collect { snapshot ->
                // Reading the session blocks; the listener may have gone meanwhile.
                ensureActive()
                if (snapshot.current != shown) {
                    shown = snapshot.current
                    NowPlaying.setTrack(connected, snapshot.current?.let { CurrentTrack(it.title, it.artist) })
                }
                if (Environment.isExternalStorageManager()) sweep.submit(snapshot) else Log.w(LOG_TAG, "All files access is off: no lookups")
            }
        }

        follower.start()
        val manager = getSystemService(MediaSessionManager::class.java)
        val component = ComponentName(this, NowPlayingListener::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
            sessions = manager
            follow(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "media sessions not readable: ${e.message}")
        }
        Log.d(LOG_TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        shutdown()
        Log.d(LOG_TAG, "listener disconnected")
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** Undoes onListenerConnected; safe to call more than once. */
    private fun shutdown() {
        follower.stop()
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        sessions = null
        scope?.cancel()
        scope = null
        connection?.let(NowPlaying::disconnect)
        connection = null
    }

    /** The queue sweep of one connection; files are matched in MediaStore, lyrics fetched by the app's pipeline. */
    private fun sweep(connected: ListenerConnection, pipeline: LyricsPipeline): QueueSweep {
        val resolver = TrackResolver(contentResolver)
        return QueueSweep(
            files = resolver::resolve,
            process = pipeline::process,
            dueErrors = pipeline::dueErrors,
            // Without All files access the files cannot be judged; the next connection settles them.
            settleInterrupted = { if (Environment.isExternalStorageManager()) pipeline.finishInterrupted() },
            onCurrent = { track, files ->
                NowPlaying.setTrack(connected, CurrentTrack(track.title, track.artist, files.map { it.path }, resolved = true))
            },
            log = { Log.d(LOG_TAG, it) },
        )
    }

    /** Main thread: switches to Samsung Music's session if it is not the one followed already. */
    private fun follow(controllers: List<MediaController>) {
        val samsung = controllers.firstOrNull { it.packageName == SAMSUNG_MUSIC }
        if (!follower.follow(samsung)) return
        activeQueueId = samsung?.playbackState?.activeQueueItemId
        Log.d(LOG_TAG, if (samsung != null) "following Samsung Music" else "no Samsung Music session")
        changed()
    }

    private fun changed() {
        if (follower.running) changes.update { it + 1 }
    }

    private fun snapshot(): SessionSnapshot {
        val controller = follower.followed ?: return SessionSnapshot.EMPTY
        val metadata = controller.metadata
        val items = controller.queue.orEmpty()
        val activeId = controller.playbackState?.activeQueueItemId ?: SessionTracks.UNKNOWN_QUEUE_ID
        if (verbose()) logSession(metadata, items, activeId)
        val queue = items.map { QueueEntry(it.queueId, sessionTrack(it.description)) }
        return SessionSnapshot(metadata?.let(::sessionTrack), activeId, queue)
    }

    private fun sessionTrack(metadata: MediaMetadata): SessionTrack? {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        return SessionTrack(
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
        )
    }

    /** By convention a queue item's subtitle is the artist; it carries no duration. */
    private fun sessionTrack(description: MediaDescription): SessionTrack = SessionTrack(
        title = description.title?.toString().orEmpty(),
        artist = description.subtitle?.toString(),
        mediaId = description.mediaId,
    )

    /** What Samsung Music puts in its session, for diagnostics. */
    private fun logSession(metadata: MediaMetadata?, items: List<MediaSession.QueueItem>, activeId: Long) {
        if (metadata != null) {
            Log.v(
                LOG_TAG,
                "metadata keys=${metadata.keySet().sorted()} " +
                    "mediaId=${metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)} " +
                    "title=${metadata.getString(MediaMetadata.METADATA_KEY_TITLE)} " +
                    "duration=${metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)}",
            )
        }
        val active = items.indexOfFirst { it.queueId == activeId }
        Log.v(LOG_TAG, "queue size=${items.size} activeQueueItemId=$activeId at index $active")
        items.drop(maxOf(active, 0)).take(SessionTracks.PREFETCH_COUNT + 1).forEach { item ->
            val d = item.description
            Log.v(LOG_TAG, "queue item id=${item.queueId} mediaId=${d.mediaId} title=${d.title} subtitle=${d.subtitle}")
        }
    }

    private companion object {
        const val SAMSUNG_MUSIC: String = "com.sec.android.app.music"
        const val DEBOUNCE_MS: Long = 1_000
    }
}
