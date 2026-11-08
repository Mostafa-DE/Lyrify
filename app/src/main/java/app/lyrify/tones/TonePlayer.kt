package app.lyrify.tones

import android.content.ContentUris
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import app.lyrify.LOG_TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume

/** What a preview plays: a window of a song file, with its fades, or a saved tone file whole. */
sealed interface PreviewSource {
    /** Tells the screens which preview is playing. */
    val key: String

    data class Window(val songPath: String, val window: ToneWindow, val fadeInMs: Long, val fadeOutMs: Long) : PreviewSource {
        override val key: String get() = "song:$songPath"
    }

    data class Tone(val id: Long) : PreviewSource {
        override val key: String get() = "tone:$id"
    }
}

/** A preview playing: which one, and how far into it (0 to 1). */
data class PreviewPlaying(val key: String, val progress: Float)

/** The volume of a window preview at [positionMs] into the window: the saved tone's fades, so the preview sounds like it. Pure. */
fun previewGain(positionMs: Long, window: ToneWindow, fadeInMs: Long, fadeOutMs: Long): Float =
    FadeEnvelope.of(window, fadeInMs, fadeOutMs).gain(ToneAudio.frames(positionMs.coerceIn(0, (window.lengthMs - 1).coerceAtLeast(0))))

/**
 * Plays previews with MediaPlayer on [scope] (the screen's), one at a time, looping off: a window of a song (sought to
 * its exact start, its fades as volume, stopped at its end, moved by [jump] while it plays) or a saved tone. It holds transient audio focus while it
 * plays, so music pauses, and stops when another app takes focus. The song file is only read. A screen that goes away
 * calls [stop].
 */
class TonePlayer(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }
        .build()
    private val state = MutableStateFlow<PreviewPlaying?>(null)
    private var job: Job? = null
    private var jumpTo: PreviewSource.Window? = null

    val playing: StateFlow<PreviewPlaying?> = state.asStateFlow()

    /** Plays [source], stopping any other preview first; [onFailed] hears when the file could not be played. */
    fun play(source: PreviewSource, onFailed: () -> Unit) {
        stop()
        state.value = PreviewPlaying(source.key, 0f)
        job = scope.launch {
            val player = MediaPlayer()
            try {
                player.setAudioAttributes(attributes)
                withContext(Dispatchers.IO) {
                    when (source) {
                        is PreviewSource.Window -> player.setDataSource(source.songPath)
                        is PreviewSource.Tone -> player.setDataSource(context, toneUri(source.id))
                    }
                    player.prepare()
                }
                if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    // A call or another app holds the audio.
                    onFailed()
                    return@launch
                }
                try {
                    when (source) {
                        is PreviewSource.Window -> playWindow(player, source)
                        is PreviewSource.Tone -> playWhole(player, source.key)
                    }
                } finally {
                    audio.abandonAudioFocusRequest(focus)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(LOG_TAG, "preview not played: ${e.javaClass.simpleName}")
                onFailed()
            } catch (e: IllegalStateException) {
                Log.w(LOG_TAG, "preview not played: ${e.javaClass.simpleName}")
                onFailed()
            } finally {
                player.release()
                // A newer preview owns the state once this one was stopped for it.
                if (job === currentCoroutineContext()[Job]) state.value = null
            }
        }
    }

    /**
     * Moves the window preview playing now to [source]: on from its start, with its length and fades, without stopping.
     * Nothing when no preview of that song plays (it ended meanwhile).
     */
    fun jump(source: PreviewSource.Window) {
        if (state.value?.key == source.key) jumpTo = source
    }

    fun stop() {
        job?.cancel()
        job = null
        jumpTo = null
        state.value = null
    }

    private suspend fun playWindow(player: MediaPlayer, first: PreviewSource.Window) {
        // A part changed while the file opened plays from its own start.
        var source = jumpTo ?: first
        jumpTo = null
        player.setGain(previewGain(0, source.window, source.fadeInMs, source.fadeOutMs))
        seek(player, source.window.startMs)
        player.start()
        while (currentCoroutineContext().isActive) {
            jumpTo?.let { next ->
                // The part changed while it played: on from its new start, the file stays open.
                jumpTo = null
                source = next
                player.setGain(previewGain(0, next.window, next.fadeInMs, next.fadeOutMs))
                seek(player, next.window.startMs)
            }
            val window = source.window
            val at = player.currentPosition - window.startMs
            if (at >= window.lengthMs || !player.isPlaying) break
            player.setGain(previewGain(at, window, source.fadeInMs, source.fadeOutMs))
            state.value = PreviewPlaying(source.key, (at.toFloat() / window.lengthMs).coerceIn(0f, 1f))
            delay(TICK_MS)
        }
        player.stop()
    }

    private suspend fun playWhole(player: MediaPlayer, key: String) {
        player.start()
        val length = player.duration.coerceAtLeast(1)
        while (currentCoroutineContext().isActive && player.isPlaying) {
            state.value = PreviewPlaying(key, (player.currentPosition.toFloat() / length).coerceIn(0f, 1f))
            delay(TICK_MS)
        }
    }

    /** Seeks to the exact frame ([MediaPlayer.SEEK_CLOSEST]) and waits until it is there. */
    private suspend fun seek(player: MediaPlayer, ms: Long) = suspendCancellableCoroutine<Unit> { done ->
        player.setOnSeekCompleteListener {
            player.setOnSeekCompleteListener(null)
            if (done.isActive) done.resume(Unit)
        }
        // Stopped mid-seek: the player is released next, and nothing waits for the seek any more.
        done.invokeOnCancellation { player.setOnSeekCompleteListener(null) }
        player.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
    }

    private fun MediaPlayer.setGain(gain: Float) = setVolume(gain, gain)

    private fun toneUri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)

    private companion object {
        /** Volume and progress steps: smooth enough for a fade, light on the main thread. */
        const val TICK_MS: Long = 30
    }
}
