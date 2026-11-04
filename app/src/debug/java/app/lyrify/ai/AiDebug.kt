package app.lyrify.ai

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.logFailures
import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

private const val EXTRA_PATH = "path"
private const val EXTRA_MODELS = "models"
private const val EXTRA_LYRICS = "lyrics"
private const val EXTRA_THREADS = "threads"
private const val DEFAULT_THREADS = 4
private const val MAX_THREADS = 8
private const val USAGE = "adb shell am broadcast --allow-fgs-start-reason 316 -n app.lyrify/.ai.AiDebugReceiver " +
    "--es path <song> --es models <folder> [--es lyrics <plain lyrics file>] [--ei threads 4]"

/**
 * Debug builds only: runs the AI engine on one song from adb and logs each stage's time and the result, with each LRC
 * line's time and length (never its text), to logcat (tag Lyrify). Writes nothing: no `.lrc`, no ledger entry, no file
 * anywhere.
 *
 * ```
 * adb shell am broadcast --allow-fgs-start-reason 316 -n app.lyrify/.ai.AiDebugReceiver \
 *     --es path "/storage/emulated/0/Music/Song.mp3" --es models /sdcard/Download/lyrify-models \
 *     [--es lyrics /sdcard/Download/plain.txt] [--ei threads 4]
 * ```
 *
 * `models` is required: the folder holding what [AiModels] names (the `whisper-npu` folder with AI Hub's five Whisper
 * files for this phone's chip, the MDX model and, to time lyrics, wav2vec2). `lyrics` is a text file with plain lyrics
 * to time (the align job); without it the song is transcribed. `threads` (CPU threads for the STFT and wav2vec2) is
 * clamped to 1-8.
 *
 * The receiver checks the arguments and starts [AiDebugService], which runs the engine. Android lets a receiver in the
 * background start a foreground service only with an exemption: `--allow-fgs-start-reason 316` makes `am` give the
 * app a 10 s allowance (316 is PowerExemptionManager.REASON_SHELL; `am` has the option from Android 15). An app the
 * user exempted from battery optimisation (`adb shell dumpsys deviceidle whitelist +app.lyrify`) needs none.
 */
class AiDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.w(LOG_TAG, "AI debug: needs Android 15 or later (a mediaProcessing foreground service)")
            return
        }
        if (AiDebugRequest.of(intent) == null) {
            Log.w(LOG_TAG, "AI debug: give a song and a models folder. $USAGE")
            return
        }
        start(context, intent)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun start(context: Context, intent: Intent) {
        try {
            context.startForegroundService(Intent(context, AiDebugService::class.java).putExtras(intent))
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(LOG_TAG, "AI debug: Android refused to start the run in the background ($e). $USAGE")
        }
    }
}

/**
 * Debug builds only: runs one [AiDebugReceiver] request as a foreground service, so the run (under a minute, plus about
 * 30 s to create the NPU sessions) keeps the process alive in the background, which a coroutine started from a receiver
 * does not (the process is cached and frozen). Type mediaProcessing, the one Android 15+ prescribes for long work on media files: the system allows 6
 * hours of it in 24, shared by all of the app's mediaProcessing services (the overnight AI worker included) and reset
 * when the user opens the app. At the limit [onTimeout] cancels the run and stops the service within the few seconds
 * Android gives. A partial wake lock keeps the CPU running with the screen off, as in the spike. One run at a time.
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class AiDebugService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + logFailures)
    private var run: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService needs a startForeground, also for a request that is then refused.
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        val request = intent?.let(AiDebugRequest::of)
        when {
            run?.isActive == true -> Log.w(LOG_TAG, "AI debug: a run is already going")
            request == null -> {
                Log.w(LOG_TAG, "AI debug: give a song and a models folder. $USAGE")
                stop()
            }
            else -> run = scope.launch {
                val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lyrify:aiDebug")
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                try {
                    execute(request)
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                    withContext(NonCancellable + Dispatchers.Main) { stop() }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(LOG_TAG, "AI debug: the mediaProcessing time limit (6 h a day) is used up; stopping the run")
        run?.cancel()
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "AI debug run", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Lyrify AI debug run")
            .setContentText("Results go to logcat, tag Lyrify")
            .setOngoing(true)
            .build()
    }

    private suspend fun execute(request: AiDebugRequest) {
        val song = request.song
        val durationMs = MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(song.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
        if (durationMs == null) {
            Log.w(LOG_TAG, "AI debug: no duration for ${song.path}")
            return
        }
        val lyrics = request.lyrics?.readText()
        Log.i(
            LOG_TAG,
            "AI debug: ${song.path} (${durationMs / 1000} s), ${if (lyrics == null) "transcribe" else "align"}, ${request.threads} threads, " +
                "models in ${request.models.path}; ONNX Runtime ${BuildConfig.ORT_VERSION}, QNN EP ${BuildConfig.QNN_EP_VERSION}, " +
                "QNN ${BuildConfig.QNN_RUNTIME_VERSION}",
        )
        val assets = WhisperAssets(asset("vocab.json"), asset("added_tokens.json"), asset("generation_config.json"))
        val start = SystemClock.elapsedRealtime()
        val track = LocalTrack(song.path, song.nameWithoutExtension, artist = null, album = null, durationMs = durationMs)
        val stages = NpuStages(AiModels.inFolder(request.models), applicationInfo.nativeLibraryDir, assets, request.threads) {
            Log.i(LOG_TAG, "AI debug: $it")
        }
        val result = try {
            LyricsFromAudio(stages, SystemClock::elapsedRealtime, notes = { Log.i(LOG_TAG, "AI debug: $it") }).run(track, lyrics)
        } finally {
            stages.close()
        }
        Log.i(LOG_TAG, "AI debug: done in ${(SystemClock.elapsedRealtime() - start) / 1000} s: ${describe(result)}")
        val lrc = when (result) {
            is AiResult.Transcribed -> result.lrc
            is AiResult.Aligned -> result.lrc
            else -> null
        }
        // Times and lengths only: lyrics never go to a log.
        lrc?.lineSequence()?.filter { it.isNotEmpty() }?.forEach { line ->
            Log.i(LOG_TAG, "AI debug: ${line.substringBefore(']')}] (${line.substringAfter(']').length} characters)")
        }
    }

    private fun asset(name: String): String = assets.open("whisper/$name").use { it.readBytes().toString(Charsets.UTF_8) }

    private fun describe(result: AiResult): String = when (result) {
        is AiResult.Transcribed -> "transcribed, ${stats(result.stats)}"
        is AiResult.Aligned -> "aligned, agreement %.2f, %s".format(Locale.ROOT, result.agreement, stats(result.stats))
        is AiResult.Instrumental -> "instrumental, %.1f words per minute".format(Locale.ROOT, result.wordsPerMinute)
        is AiResult.Rejected -> "rejected: ${result.reason}" + (result.agreement?.let { " (agreement %.2f)".format(Locale.ROOT, it) } ?: "") +
            (result.problem?.let { " ($it)" } ?: "")
        is AiResult.Failed -> "failed: ${result.reason}"
    }

    private fun stats(s: AiStats): String =
        "%d lines, %.1f words per minute, vocal frames %.3f, %d chunks with %.0f of %.0f s, %d segments, stages %s".format(
            Locale.ROOT, s.lines, s.wordsPerMinute, s.vocalShare, s.chunks, s.transcribedSec, s.audioSec, s.segments,
            s.stageMs.entries.joinToString { (stage, ms) -> "${stage.name.lowercase()} %.1f s".format(Locale.ROOT, ms / 1000.0) },
        )

    private companion object {
        const val CHANNEL = "ai_debug"
        const val NOTIFICATION_ID = 1

        /** A song takes under a minute on the NPU; this is a generous bound. */
        const val WAKE_LOCK_TIMEOUT_MS = 30L * 60 * 1000
    }
}

/** One run's arguments from the broadcast's extras; null without a song or a models folder. */
private class AiDebugRequest(val song: File, val models: File, val lyrics: File?, val threads: Int) {
    companion object {
        fun of(intent: Intent): AiDebugRequest? {
            val path = intent.getStringExtra(EXTRA_PATH) ?: return null
            val models = intent.getStringExtra(EXTRA_MODELS) ?: return null
            return AiDebugRequest(
                song = File(path),
                models = File(models),
                lyrics = intent.getStringExtra(EXTRA_LYRICS)?.let(::File),
                threads = intent.getIntExtra(EXTRA_THREADS, DEFAULT_THREADS).coerceIn(1, MAX_THREADS),
            )
        }
    }
}
