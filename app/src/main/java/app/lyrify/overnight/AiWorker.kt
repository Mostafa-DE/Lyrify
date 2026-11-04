package app.lyrify.overnight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.R
import app.lyrify.ai.AiModels
import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStage
import app.lyrify.ai.LyricsFromAudio
import app.lyrify.ai.NpuStages
import app.lyrify.ai.WhisperAssets
import app.lyrify.data.LedgerRepository
import app.lyrify.lyrics.LocalTrack
import app.lyrify.models.ModelDownloads
import app.lyrify.models.ModelSpec
import app.lyrify.models.ModelStore
import app.lyrify.models.NpuChips
import app.lyrify.models.inForeground
import app.lyrify.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The overnight AI run ([AiRunner]): a long-running WorkManager worker, looked at every hour while the battery is not
 * low and storage is not low, that runs only while the phone is plugged in (charging or not, see [AiLyrics.schedule]),
 * in a foreground service of type mediaProcessing, the type Android 15+ prescribes for long work on media files. That
 * type may run 6 hours in 24 (the time resets when the user opens the app); at the limit WorkManager's service gets
 * `onTimeout` and stops this worker ([WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT]), and the AI then holds off for a
 * day or until the app is opened. Unplugged ([AiRunner] watches the cable) or cancelled, the engine finishes the step it
 * is on and stops; nothing is recorded for that song. It goes into the foreground before it looks at any song's files.
 * The engine's NPU sessions stay loaded for the whole run and are released at its end. What happens goes to the AI's
 * run log ([AiLyrics.runLog]) as well as logcat. Android 15+ only, on the supported chips ([NpuChips]) only.
 */
class AiWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) night() else Result.success()

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun night(): Result {
        val context = applicationContext
        // A phone whose chip the AI does not run on: nothing to do.
        val store = ModelDownloads.store(context) ?: return Result.success()
        val now = System.currentTimeMillis()
        if (!store.isReady()) {
            // Not all in place (an update can pin another file): the download goes on by itself, over Wi-Fi.
            if (AiLyrics.enabled(context).value) ModelDownloads.startIfIdle(context)
            return Result.success()
        }
        val loadFailures = AiLyrics.loadFailures(context)
        if (loadFailures.paused()) {
            // A model did not load again after it was downloaded again: nothing runs until the next version or new models.
            Log.i(LOG_TAG, "AI: paused, a model did not load again after it was downloaded again")
            AiLyrics.held(AiHold.MODEL_FAILED)
            return Result.success()
        }
        // No access to the music, or not plugged in: the next period looks again.
        if (!Environment.isExternalStorageManager() || !AiLyrics.pluggedIn(context).first()) return Result.success()
        if (AiLyrics.limitHolds(context, now)) {
            Log.i(LOG_TAG, "AI: the daily limit for long work was reached; waiting")
            return Result.success()
        }
        val ledger = LedgerRepository(context)
        val state = ledger.ledger.first()
        if (!state.readable) {
            // Storage trouble, not "no song waiting": the night is not lost, WorkManager's backoff tries again.
            Log.w(LOG_TAG, "AI: the song list cannot be read; trying again later")
            return Result.retry()
        }
        // The ledger alone (no file is looked at): with no song waiting, no foreground service.
        if (state.entries.values.none { AiQueue.wants(it, now) }) return Result.success()
        val runLog = AiLyrics.runLog(context)
        // Into the foreground first; the run then builds its queue, looking at each song's files.
        if (!foreground(null)) {
            runLog.append("AI: the phone did not let the run start in the background (foreground service refused)")
            AiLyrics.held(AiHold.NOT_ALLOWED)
            return Result.retry()
        }
        AiLyrics.held(null)
        val power = context.getSystemService(PowerManager::class.java)
        runLog.append(
            "AI: run started: ${AiLyrics.battery(context)}, thermal status ${power.currentThermalStatus}, " +
                "${NpuChips.device()?.socModel} NPU, ONNX Runtime ${BuildConfig.ORT_VERSION}, QNN EP ${BuildConfig.QNN_EP_VERSION}, " +
                "QNN ${BuildConfig.QNN_RUNTIME_VERSION}, $AI_THREADS CPU threads, Lyrify ${BuildConfig.VERSION_NAME}",
        )
        val log: (String) -> Unit = {
            Log.i(LOG_TAG, it)
            runLog.append(it)
        }
        val pipeline = Lyrify.pipeline(context)
        val runner = AiRunner(
            ledger = ledger,
            plainLyrics = pipeline::plainLyrics,
            record = pipeline::recordAi,
            openEngine = { models -> engine(context, models, runLog) },
            models = store::models,
            thermalStatus = { power.currentThermalStatus },
            pluggedIn = AiLyrics.pluggedIn(context),
            clock = System::currentTimeMillis,
            guard = AiLyrics.crashGuard(context, log),
            loadFailures = loadFailures,
            engineContext = Dispatchers.Default,
            aiPaths = pipeline.aiPaths,
            inUse = AiLyrics.inUse,
            badModel = { model ->
                ModelDownloads.discard(context, model)
                if (AiLyrics.enabled(context).value) ModelDownloads.start(context)
            },
            onProgress = { progress ->
                AiLyrics.show(progress)
                foreground(progress)
            },
            log = log,
        )
        val started = SystemClock.elapsedRealtime()
        return try {
            val end = runner.run()
            log("AI: run ended: $end after ${(SystemClock.elapsedRealtime() - started) / 60_000} min")
            if (end == AiRunEnd.CRASHED) AiLyrics.held(AiHold.CRASHED)
            if (end == AiRunEnd.MODEL_FAILED) AiLyrics.held(AiHold.MODEL_FAILED)
            if (end == AiRunEnd.NO_NPU) AiLyrics.held(AiHold.NO_NPU)
            // Each end waits for the next period: unplugged, the next hourly look finds the cable again.
            Result.success()
        } catch (e: CancellationException) {
            runLog.append("AI: run stopped by the system after ${(SystemClock.elapsedRealtime() - started) / 60_000} min (WorkManager stop reason $stopReason)")
            throw e
        } finally {
            AiLyrics.show(null)
            if (stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
                log("AI: the daily limit for long work is used up (onTimeout); stopped after the current step")
                AiLyrics.limitReached(context, System.currentTimeMillis())
            }
        }
    }

    /** Runs (or keeps running) in the foreground showing [progress] (null: getting ready); false when the phone does not allow it now. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun foreground(progress: AiProgress?): Boolean =
        inForeground({ setForeground(AiLyrics.foregroundInfo(applicationContext, progress)) }) { e ->
            // Started from the background without an exemption (see the card's battery tip), or the day's time for long
            // work used up. A cancelled run is rethrown, not taken for this.
            Log.w(LOG_TAG, "AI: not allowed to start in the background: ${e.message}")
        }

    /**
     * The engine on the NPU for [models]: [NpuStages] (the QNN EP and its sessions, created on first use and released
     * by [SongEngine.close]) under [LyricsFromAudio]. Its notes go to the run log: stages, times, counts.
     */
    private fun engine(context: Context, models: AiModels, runLog: AiRunLog): SongEngine {
        val stages = NpuStages(
            models,
            context.applicationInfo.nativeLibraryDir,
            AiLyrics.whisperAssets(context),
            AI_THREADS,
            log = { runLog.append("AI:   $it") },
        )
        val lyrics = LyricsFromAudio(
            stages,
            SystemClock::elapsedRealtime,
            log = { Log.d(LOG_TAG, "AI: $it") },
            notes = { runLog.append("AI:   $it") },
            traces = BuildConfig.DEBUG,
        )
        return object : SongEngine {
            override suspend fun run(track: LocalTrack, plainLyrics: String?, onStage: (AiStage) -> Unit, beforeChunk: suspend () -> Unit): AiResult =
                lyrics.run(track, plainLyrics, onStage, beforeChunk)

            override fun close() = stages.close()
        }
    }
}

/**
 * Switching the AI on and off, and what the AI card shows about it. Process-wide. The user's choice is stored and is
 * what the switch shows; WorkManager's schedule follows it ([reconcile]).
 */
object AiLyrics {
    private const val WORK = "ai-lyrics"
    private const val CHANNEL = "ai_lyrics"
    private const val NOTIFICATION_ID = 2
    private const val PREFERENCES = "ai_lyrics"
    private const val LIMIT_REACHED_AT = "limit_reached_at"
    private const val ENABLED = "enabled"
    private const val LOAD_FAILURES = "model_load_failures"
    private const val RUN_EVERY_HOURS = 1L
    private const val RETRY_MINUTES = 15L
    private const val DAY_MS = 24 * 60 * 60_000L
    private const val RUN_LOG = "ai-run.log"
    private const val CRASH_GUARD = "ai-crash-guard.json"

    /** Where 2.0's CPU engine kept its per-song checkpoints (app-private cache); deleted, as songs now take under a minute. */
    private const val OLD_RESUME_CACHE = "ai-resume"
    private const val PERCENT = 100

    /** How long "Delete models" waits for a run to stop: the step it is on takes seconds; creating the MDX session, about 30 s. */
    private const val STOP_TIMEOUT_MS = 5 * 60_000L

    /** Held by a run while it may use the models ([AiRunner]); a delete waits for it ([afterStopping]). */
    internal val inUse = Mutex()

    private var stored: MutableStateFlow<Boolean>? = null

    private var runLog: AiRunLog? = null

    private val _progress = MutableStateFlow<AiProgress?>(null)

    /** The run in progress, while there is one in this process. */
    val progress: StateFlow<AiProgress?> = _progress.asStateFlow()

    private val _hold = MutableStateFlow<AiHold?>(null)

    /** Why the last start did not happen, if it did not. */
    val hold: StateFlow<AiHold?> = _hold.asStateFlow()

    /** The user's choice, as stored: the switch shows it at once, without waiting for WorkManager. */
    @Synchronized
    private fun choice(context: Context): MutableStateFlow<Boolean> =
        stored ?: MutableStateFlow(preferences(context).getBoolean(ENABLED, false)).also { stored = it }

    private fun choose(context: Context, on: Boolean) {
        preferences(context).edit { putBoolean(ENABLED, on) }
        choice(context).value = on
    }

    /**
     * On: stored as the user's choice, downloads the models if needed (over Wi-Fi; models in place are not fetched
     * again) and schedules the run, looked at every hour while the battery is not low and storage is not low; it runs
     * when the phone is plugged in.
     */
    fun turnOn(context: Context) {
        choose(context, true)
        ModelDownloads.start(context)
        schedule(context)
    }

    /**
     * The periodic run. UPDATE gives a schedule that is there the current constraints from its next run on (a run in
     * progress goes on, the period is kept), and replaces one that was cancelled, even while that run is still finishing
     * its step ([inUse] keeps the two apart).
     *
     * Not `setRequiresCharging`: in WorkManager 2.12 (BatteryChargingTracker, and JobScheduler for the start) that is
     * Android's charging state, `BatteryManager.ACTION_CHARGING` / `ACTION_DISCHARGING`, not the cable. On the user's
     * phone it came 18 and 19 minutes after plugging in, never in a 13-minute charge, and went off at 63% with the cable
     * still in; a battery Samsung's "Protect battery" holds at 80-85% stops charging the same way. The worker checks the
     * cable itself ([pluggedIn], any `EXTRA_PLUGGED` source) and [AiRunner] stops when it comes off.
     */
    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<AiWorker>(RUN_EVERY_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** Off, stored as the user's choice: a run in progress finishes the step it is on and stops. The models stay. */
    fun turnOff(context: Context) {
        choose(context, false)
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }

    /** Whether the AI is on: the user's stored choice. */
    fun enabled(context: Context): StateFlow<Boolean> = choice(context).asStateFlow()

    /**
     * When the app starts: on, with nothing scheduled (the schedule was lost) or a schedule an earlier version made
     * (waiting for charging, see [schedule]), the run is scheduled again; models not all in place (a new version can
     * pin another file) are downloaded unless a download is under way. A schedule from before the choice was stored (an
     * earlier build) counts as on.
     */
    suspend fun reconcile(context: Context) {
        withContext(Dispatchers.IO) { removeOldFiles(context) }
        val store = ModelDownloads.store(context) ?: return
        val pending = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK).first().filterNot { it.state.isFinished }
        if (pending.isNotEmpty() && !preferences(context).contains(ENABLED)) choose(context, true)
        if (!choice(context).value) return
        if (loadFailures(context).paused()) _hold.value = AiHold.MODEL_FAILED
        if (pending.isEmpty() || pending.any { it.constraints.requiresCharging() }) {
            Log.i(LOG_TAG, "AI: on; scheduled again")
            schedule(context)
        }
        if (!withContext(Dispatchers.IO) { store.isReady() }) ModelDownloads.startIfIdle(context)
    }

    /**
     * What earlier versions left in app-private storage and this one no longer uses: the models they downloaded
     * ([ModelSpec.RETIRED], and on this phone's chip a Whisper folder of another archive, [ModelStore.removeRetired]) and
     * 2.0's per-song checkpoints in the cache. Nothing else.
     */
    private fun removeOldFiles(context: Context) {
        val gone = ModelDownloads.store(context)?.removeRetired() ?: ModelStore.removeRetired(ModelDownloads.folder(context))
        if (!gone) Log.w(LOG_TAG, "AI: a model an earlier version used could not be deleted")
        val checkpoints = File(context.cacheDir, OLD_RESUME_CACHE)
        if (checkpoints.exists() && !checkpoints.deleteRecursively()) Log.w(LOG_TAG, "AI: the old checkpoints could not all be deleted")
    }

    /** The crash guard's records (app-private files, see [CrashGuard]). */
    internal fun crashGuard(context: Context, log: (String) -> Unit): CrashGuard =
        CrashGuard(File(context.applicationContext.filesDir, CRASH_GUARD), System::currentTimeMillis, BuildConfig.VERSION_CODE, log)

    /** The Whisper tokenizer's files from the app's assets. */
    internal fun whisperAssets(context: Context): WhisperAssets {
        fun asset(name: String) = context.assets.open("whisper/$name").use { it.readBytes().toString(Charsets.UTF_8) }
        return WhisperAssets(asset("vocab.json"), asset("added_tokens.json"), asset("generation_config.json"))
    }

    /**
     * Off, then [block] once the AI has actually stopped: WorkManager no longer runs it and its engine has let go of
     * the models (a cancelled run finishes the step it is on first). Null when that takes longer than
     * [STOP_TIMEOUT_MS]: [block] did not run.
     */
    suspend fun <T> afterStopping(context: Context, block: suspend () -> T): T? {
        turnOff(context)
        val running = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        return whenStopped(running, inUse, STOP_TIMEOUT_MS, block)
    }

    /**
     * Whether the phone is plugged in (any `EXTRA_PLUGGED` source: AC, USB, wireless, dock), charging or not, from the
     * battery broadcast.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun pluggedIn(context: Context): Flow<Boolean> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(plugged(intent))
            }
        }
        // A system broadcast: delivered although the receiver is not exported. The sticky intent is the state now.
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)?.let { trySend(plugged(it)) }
        awaitClose { context.unregisterReceiver(receiver) }
    }.conflate().distinctUntilChanged()

    private fun plugged(intent: Intent): Boolean = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

    /** The battery now, for the run log: "plugged in (usb), not charging, 81%". */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED) ?: return "battery unknown"
        val plugged = when (val source = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            0 -> "not plugged in"
            BatteryManager.BATTERY_PLUGGED_AC -> "plugged in (ac)"
            BatteryManager.BATTERY_PLUGGED_USB -> "plugged in (usb)"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "plugged in (wireless)"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "plugged in (dock)"
            else -> "plugged in ($source)"
        }
        val status = when (val code = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            else -> "status $code"
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * PERCENT / intent.getIntExtra(BatteryManager.EXTRA_SCALE, PERCENT).coerceAtLeast(1)
        return "$plugged, $status, $level%"
    }

    /** The AI's run log (app-private files, see [AiRunLog]); the AI card shows its last lines on a long press. */
    @Synchronized
    fun runLog(context: Context): AiRunLog =
        runLog ?: AiRunLog(File(context.applicationContext.filesDir, RUN_LOG), System::currentTimeMillis).also { runLog = it }

    internal fun show(progress: AiProgress?) {
        _progress.value = progress
    }

    internal fun held(hold: AiHold?) {
        _hold.value = hold
    }

    /**
     * The mediaProcessing time was used up at [now]: no new run for a day, unless the app is opened (which resets it).
     * Written at once (commit): the process may be killed right after the timeout.
     */
    internal fun limitReached(context: Context, now: Long) {
        runLog(context).append("AI: holding off for a day or until Lyrify is opened (daily limit for long media work)")
        preferences(context).edit(commit = true) { putLong(LIMIT_REACHED_AT, now) }
        _hold.value = AiHold.DAILY_LIMIT
    }

    internal fun limitHolds(context: Context, now: Long): Boolean {
        val at = preferences(context).getLong(LIMIT_REACHED_AT, 0)
        return at > 0 && now - at < DAY_MS
    }

    /** The models' load failures ([ModelLoadFailures]), in the AI's preferences, written at once (commit). */
    internal fun loadFailures(context: Context): ModelLoadFailures {
        val store = ModelDownloads.store(context)
        val preferences = preferences(context)
        return ModelLoadFailures(
            version = BuildConfig.VERSION_CODE,
            pinned = { model -> store?.specs?.firstOrNull { store.file(it) == model }?.sha256 },
            read = { preferences.getString(LOAD_FAILURES, null) },
            write = { value -> preferences.edit(commit = true) { if (value == null) remove(LOAD_FAILURES) else putString(LOAD_FAILURES, value) } },
        )
    }

    /** The user deleted the models: a model that did not load is tried again once they are downloaded anew. */
    fun modelsDeleted(context: Context) {
        loadFailures(context).clear()
        _hold.compareAndSet(AiHold.MODEL_FAILED, null)
    }

    /** The app was opened: Android gives a foreground app its mediaProcessing time back. */
    fun appOpened(context: Context) {
        preferences(context).edit { remove(LIMIT_REACHED_AT) }
        _hold.compareAndSet(AiHold.DAILY_LIMIT, null)
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    internal fun foregroundInfo(context: Context, progress: AiProgress?): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.ai_channel), NotificationManager.IMPORTANCE_LOW))
        val resources = context.resources
        val text = when {
            progress == null -> resources.getString(R.string.ai_notification_starting)
            progress.hot -> resources.getString(R.string.ai_notification_hot, progress.done, progress.total)
            progress.current != null -> resources.getString(R.string.ai_notification_song, progress.current, progress.done, progress.total)
            else -> resources.getString(R.string.ai_notification_starting)
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.ai_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
    }
}
