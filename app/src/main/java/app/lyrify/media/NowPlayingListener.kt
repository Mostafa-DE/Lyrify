package app.lyrify.media

import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * Follows the media session of the app the user plays music in, as [ActiveSessions] chooses
 * it: Samsung Music or any other player (a streaming app too: its songs are no files on the
 * phone, so they cost nothing). Notification access is what lets an app read other apps'
 * media sessions; notifications themselves are not used. The system binds this service while
 * access is granted, so no foreground service is needed.
 *
 * When the track or the queue changes (debounced, and only for real changes), the whole
 * queue goes to a [QueueSweep]: the current track and the next few first, then the rest,
 * so `.lrc` files exist before songs start (Samsung reads lyrics when a track loads). A
 * player without a queue gets the song it plays looked up whenever its metadata changes.
 */
class NowPlayingListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var sessions: MediaSessionManager? = null
    private var connection: ListenerConnection? = null

    /** Main thread only. Playback state also changes on play, pause and seek; only this part matters. */
    private var activeQueueId: Long? = null

    /** The callback on each active session ([activeSessions]); main thread only. */
    private val watches = mutableMapOf<MediaSession.Token, MediaController.Callback>()

    /** The app of the session followed, looked up once per app; read by snapshots. */
    @Volatile
    private var player: Player? = null

    /** The app of the track shown, for its files once found; written by the snapshot collector. */
    @Volatile
    private var shownPlayer: Player? = null

    /**
     * Counts session callbacks; each one reads a snapshot, and only a changed snapshot starts
     * work. A StateFlow, so a change signalled before the collector subscribes is not lost.
     */
    private val changes = MutableStateFlow(0L)

    /** Called when a session starts or ends; not when one starts playing (the callbacks in [watches] see that). */
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        if (follower.running) {
            activeSessions.update(controllers.orEmpty())
            chooseNowAndLater()
        }
    }

    /** [readAgain], posted on the main thread; a shutdown removes it. */
    private val recheck = Runnable(::readAgain)

    private val callback: MediaController.Callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = changed()

        override fun onQueueChanged(queue: List<MediaSession.QueueItem>?) = changed()

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (!follower.running) return
            // Players may update the state every second while playing; only a new active item matters.
            val active = state?.activeQueueItemId
            if (active == activeQueueId) return
            activeQueueId = active
            changed()
        }
        // An ended session: its callback in [watches] chooses again.
    }

    /** Main thread only; ignores everything between a shutdown and the next connect. */
    private val follower = SessionFollower<MediaController>(
        sameSession = { a, b -> a.sessionToken == b.sessionToken },
        register = { it.registerCallback(callback, handler) },
        unregister = { it.unregisterCallback(callback) },
    )

    /** Main thread only, and like [follower] empty between a shutdown and the next connect. */
    private val activeSessions = ActiveSessions<MediaController>(
        sameSession = { a, b -> a.sessionToken == b.sessionToken },
        sameApp = { a, b -> a.packageName == b.packageName },
        playback = { Playback.of(it.playbackState?.state) },
        register = { controller ->
            val watch = watch(controller)
            watches[controller.sessionToken] = watch
            controller.registerCallback(watch, handler)
        },
        unregister = { controller -> watches.remove(controller.sessionToken)?.let(controller::unregisterCallback) },
        clock = SystemClock::elapsedRealtime,
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        shutdown()
        val connected = NowPlaying.connect().also { connection = it }
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures).also { scope = it }
        val pipeline = Lyrify.pipeline(this)
        // The track shown: set by the session's readings, its files added by the sweep; one flow, so neither undoes the other.
        val shownTrack = MutableStateFlow<CurrentTrack?>(null)
        val sweep = sweep(shownTrack, pipeline)
        serviceScope.launch { sweep.run() }
        serviceScope.launch { sweep.progress.collect { NowPlaying.setSweep(connected, it) } }
        serviceScope.launch { shownTrack.collect { NowPlaying.setTrack(connected, it) } }
        serviceScope.launch { NowPlaying.retryRequests.collect { sweep.retry(pipeline.failed()) } }
        serviceScope.launch {
            var shown: SessionTrack? = null
            var submitted: SessionSnapshot? = null
            SessionTracks.settled(changes, ::read, DEBOUNCE_MS, Reading::sameAs).collect { (snapshot, art) ->
                // Reading the session blocks; the listener may have gone meanwhile.
                ensureActive()
                if (snapshot.current != shown || snapshot.player != shownPlayer) {
                    shown = snapshot.current
                    shownPlayer = snapshot.player
                    shownTrack.value = snapshot.current?.let { CurrentTrack(it.title, it.artist, player = snapshot.player, art = compareArt(null, art).art) }
                } else {
                    // The same track with a new cover (a player may set it after the title): the files found stay.
                    shownTrack.update { track ->
                        if (track == null) null else {
                            val compared = compareArt(track.art, art)
                            if (compared.same) track else track.copy(art = compared.art)
                        }
                    }
                }
                if (snapshot == submitted) return@collect
                submitted = snapshot
                if (Environment.isExternalStorageManager()) sweep.submit(snapshot) else Log.w(LOG_TAG, "All files access is off: no lookups")
            }
        }

        follower.start()
        activeSessions.start()
        val manager = getSystemService(MediaSessionManager::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component(), handler)
            sessions = manager
            val listed = manager.getActiveSessions(component())
            // Counts only, never which apps: tells a list read too early from a session that plays unseen.
            Log.d(LOG_TAG, "media sessions at connect: ${listed.size}, playing: ${listed.count { Playback.of(it.playbackState?.state) == Playback.PLAYING }}")
            activeSessions.update(listed)
            choose()
            // The bind follows an app update or an access grant, and the list read then may be behind: read again soon, not only on a callback.
            handler.postDelayed(recheck, CONNECT_RECHECK_MS)
            handler.postDelayed(recheck, ActiveSessions.BETWEEN_TRACKS_MS)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "media sessions not readable: ${e.javaClass.simpleName}")
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
        handler.removeCallbacks(recheck)
        activeSessions.stop()
        sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        sessions = null
        scope?.cancel()
        scope = null
        connection?.let(NowPlaying::disconnect)
        connection = null
    }

    /** The queue sweep of one connection; files are matched in MediaStore, lyrics fetched by the app's pipeline. */
    private fun sweep(shownTrack: MutableStateFlow<CurrentTrack?>, pipeline: LyricsPipeline): QueueSweep {
        val resolver = TrackResolver(contentResolver)
        return QueueSweep(
            files = resolver::resolve,
            process = pipeline::process,
            // Failed lookups due again, then songs an older lookup found nothing for (looked up once more, in LOW).
            dueErrors = { pipeline.dueErrors() + pipeline.staleLookups() },
            // Without All files access the files cannot be judged; the next connection settles them.
            settleInterrupted = { if (Environment.isExternalStorageManager()) pipeline.finishInterrupted() },
            heldByAi = pipeline.aiPaths::contains,
            onCurrent = { track, files ->
                shownTrack.update { CurrentTrack(track.title, track.artist, files.map { it.path }, resolved = true, player = shownPlayer, art = it?.art) }
            },
            log = { Log.d(LOG_TAG, "queue sweep: 1 diagnostic event") },
        )
    }

    /** Main thread: follows the session [activeSessions] chooses, if it is not the one followed already. */
    private fun choose() {
        if (!follower.running) return
        val chosen = activeSessions.choose(follower.followed)
        if (!follower.follow(chosen)) return
        activeQueueId = chosen?.playbackState?.activeQueueItemId
        Log.d(LOG_TAG, if (chosen != null) "following 1 media session" else "no media session followed")
        changed()
    }

    /**
     * Main thread: chooses now, and again once a stop or an end seen now has outlasted [ActiveSessions.BETWEEN_TRACKS_MS].
     * Nothing after a shutdown: a callback queued before it must not post a recheck that nothing would remove.
     */
    private fun chooseNowAndLater() {
        if (!follower.running) return
        choose()
        handler.postDelayed(recheck, ActiveSessions.BETWEEN_TRACKS_MS)
    }

    /** Main thread: lists the sessions and reads their playback again (a callback may have been missed), then chooses. */
    private fun readAgain() {
        val manager = sessions?.takeIf { follower.running } ?: return
        try {
            activeSessions.update(manager.getActiveSessions(component()))
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "media sessions not readable: ${e.javaClass.simpleName}")
            return
        }
        if (activeSessions.refresh()) Log.d(LOG_TAG, "a media session started or stopped playing without a callback")
        choose()
    }

    private fun component(): ComponentName = ComponentName(this, NowPlayingListener::class.java)

    /** The callback on an active session: starting or stopping to play, or its end, may change the session to follow. */
    private fun watch(controller: MediaController): MediaController.Callback = object : MediaController.Callback() {
        // Players may update the state every second while playing (the position); only starting or stopping counts.
        override fun onPlaybackStateChanged(playback: PlaybackState?) {
            if (!follower.running) return
            if (activeSessions.playbackChanged(controller, Playback.of(playback?.state))) chooseNowAndLater()
        }

        override fun onSessionDestroyed() {
            if (!follower.running) return
            activeSessions.ended(controller)
            chooseNowAndLater()
        }
    }

    private fun changed() {
        if (follower.running) changes.update { it + 1 }
    }

    /** The session followed now; its cover is read on every signal, so one set after the title is seen too. */
    private fun read(): Reading {
        val controller = follower.followed ?: return Reading(SessionSnapshot.EMPTY, null)
        val metadata = controller.metadata
        val items = controller.queue.orEmpty()
        val activeId = controller.playbackState?.activeQueueItemId ?: SessionTracks.UNKNOWN_QUEUE_ID
        if (verbose()) logSession(metadata, items, activeId)
        val queue = items.map { QueueEntry(it.queueId, sessionTrack(it.description)) }
        val art = metadata?.let(::art)
        return Reading(SessionSnapshot(metadata?.let(::sessionTrack), activeId, queue, player(controller.packageName)), art)
    }

    /** The app [packageName] is, named as the phone shows it; its package name when Lyrify cannot see the app. */
    private fun player(packageName: String): Player = player?.takeIf { it.packageName == packageName } ?: run {
        val name = try {
            // Deprecated from Android 13 for the ApplicationInfoFlags overload; the same lookup.
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            // The exception names the package: not logged.
            Log.d(LOG_TAG, "the player's app name is not visible to Lyrify; its package name stands in")
            packageName
        }
        Player(packageName, name).also { player = it }
    }

    private fun sessionTrack(metadata: MediaMetadata): SessionTrack? {
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        return SessionTrack(
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 },
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            mediaUri = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
        )
    }

    /** The cover the player put in its session: the album art, else the art, else the display icon. */
    private fun art(metadata: MediaMetadata): Bitmap? = try {
        val bitmap = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        compareArt(null, bitmap).art
    } catch (_: RuntimeException) {
        null
    }

    /** By convention a queue item's subtitle is the artist; it carries no duration. */
    private fun sessionTrack(description: MediaDescription): SessionTrack = SessionTrack(
        title = description.title?.toString().orEmpty(),
        artist = description.subtitle?.toString(),
        mediaId = description.mediaId,
        mediaUri = description.mediaUri?.toString(),
    )

    /**
     * What the player puts in its session, for diagnostics: which standard keys and how many items, never what they
     * say. Titles, ids and a player's own keys (named after its package) stay out of the log.
     */
    private fun logSession(metadata: MediaMetadata?, items: List<MediaSession.QueueItem>, activeId: Long) {
        if (metadata != null) {
            Log.v(LOG_TAG, "metadata keys=${metadata.keySet().size}")
        }
        val active = items.indexOfFirst { it.queueId == activeId }
        val withSubtitle = items.count { it.description.subtitle != null }
        Log.v(LOG_TAG, "queue size=${items.size}, ${if (active >= 0) "active item at index $active" else "no active item"}, $withSubtitle with a subtitle")
    }

    /** One read of the session followed: its snapshot, and the cover in its metadata. */
    private data class Reading(val snapshot: SessionSnapshot, val art: Bitmap?) {
        /** Nothing new: the same snapshot, and the same cover by its pixels (a player hands over a new bitmap on every read). */
        fun sameAs(other: Reading): Boolean = snapshot == other.snapshot && compareArt(art, other.art).same
    }

    private companion object {
        const val DEBOUNCE_MS: Long = 1_000

        /** The sessions are read again this long after a connect, and after [ActiveSessions.BETWEEN_TRACKS_MS]. */
        const val CONNECT_RECHECK_MS: Long = 1_000

        /** Whether two covers are usable and the same picture. */
        fun compareArt(a: Bitmap?, b: Bitmap?): ArtComparison<Bitmap> =
            SessionTracks.compareArt(a, b, usable = { !it.isRecycled }, same = Bitmap::sameAs)
    }
}
