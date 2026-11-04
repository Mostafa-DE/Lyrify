package app.lyrify.models

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lyrify.BuildConfig
import app.lyrify.LOG_TAG
import app.lyrify.R
import app.lyrify.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the AI's models ([ModelDownloader]) while the phone is on an unmetered network: a long-running worker in a
 * foreground service of type dataSync, with the progress in its notification, so it is not cut off after the 10 minutes
 * of an ordinary job (dataSync may run 6 hours in 24 from Android 15; opening the app resets that). When the phone does
 * not allow the foreground service (started from the background without an exemption, or that time used up), it runs
 * as an ordinary job. A dropped connection that got further goes on at once ([retryWhileProgressing]); a stopped run
 * (the network changed, a time limit) keeps its parts, and the next run goes on from there.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // A phone whose chip the AI does not run on downloads nothing.
        val store = ModelDownloads.store(applicationContext) ?: return Result.success()
        // Models in place (switching the AI on starts this too): nothing to fetch, no foreground service.
        if (store.isReady()) return Result.success()
        val downloader = ModelDownloader(
            store = store,
            userAgent = "Lyrify/${BuildConfig.VERSION_NAME} (personal use)",
            log = { Log.d(LOG_TAG, "models: $it") },
        )
        val inForeground = foreground(store.bytesOnDisk(), store.totalBytes)
        // A download replaced a moment ago may still be writing its part: this one goes on once it has stopped.
        val result = ModelDownloads.lock.withLock {
            retryWhileProgressing(store::bytesOnDisk) {
                downloader.downloadAll { done, total ->
                    setProgress(workDataOf(ModelDownloads.DONE to done, ModelDownloads.TOTAL to total))
                    if (inForeground) foreground(done, total)
                }
            }
        }
        return when (result) {
            Download.Done -> {
                Log.i(LOG_TAG, "models: all downloaded and verified")
                Result.success()
            }
            is Download.Interrupted -> {
                Log.w(LOG_TAG, "models: download stopped, goes on later: ${result.reason}")
                Result.retry()
            }
            is Download.Failed -> {
                Log.w(LOG_TAG, "models: download failed: ${result.reason}")
                Result.failure()
            }
        }
    }

    /** Runs (or keeps running) in the foreground showing [done] of [total] bytes; false when the phone does not allow it now. */
    private suspend fun foreground(done: Long, total: Long): Boolean =
        inForeground({ setForeground(ModelDownloads.foregroundInfo(applicationContext, done, total)) }) { e ->
            // The download goes on as an ordinary job.
            Log.w(LOG_TAG, "models: not allowed to download in the foreground: ${e.message}")
        }
}

/**
 * Runs [setForeground] (a worker's `setForeground`) and tells whether the work now runs in the foreground: false, after
 * telling [refused], when the phone does not allow it now. WorkManager's setForeground throws IllegalStateException then
 * (ForegroundServiceStartNotAllowedException, Android 12+, is one). Coroutine cancellation is an IllegalStateException
 * too (kotlinx's CancellationException is java.util.concurrent's): a worker cancelled meanwhile is rethrown as cancelled,
 * never taken for a refusal. Both workers go into the foreground through this.
 */
internal suspend fun inForeground(setForeground: suspend () -> Unit, refused: (IllegalStateException) -> Unit): Boolean = try {
    setForeground()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: IllegalStateException) {
    refused(e)
    false
}

/** Starting, deleting and watching the model download. */
object ModelDownloads {
    private const val WORK = "ai-models"
    internal const val DONE = "done"
    internal const val TOTAL = "total"
    private const val CHANNEL = "ai_models"
    private const val NOTIFICATION_ID = 3

    /** Held while a download runs, so a delete waits until it has stopped writing. */
    internal val lock = Mutex()

    /** Bumped after a delete: the files changed without the work changing. */
    private val deleted = MutableStateFlow(0)

    /** The app-private models folder. */
    fun folder(context: Context): File = File(context.applicationContext.filesDir, MODELS)

    /**
     * The Android version the AI runs on: 15 (API 35). The overnight run is a long-running worker in a foreground
     * service of type mediaProcessing, a type Android 15 added (with its daily time limit, which the overnight worker handles); on Android 12
     * to 14 the chip is known (`Build.SOC_MODEL`, API 31) but the run never starts, so the models are not downloaded.
     */
    const val MIN_SDK: Int = Build.VERSION_CODES.VANILLA_ICE_CREAM

    /** The chip the AI runs on, on Android [sdk] with [chip]: none before [MIN_SDK] or on another chip. */
    fun chipFor(sdk: Int, chip: NpuChip?): NpuChip? = chip?.takeIf { sdk >= MIN_SDK }

    /** The models for this phone's chip ([NpuChips.device]); null when the AI does not run on it ([chipFor]). */
    fun store(context: Context): ModelStore? = chipFor(Build.VERSION.SDK_INT, NpuChips.device())?.let { chip ->
        val app = context.applicationContext
        ModelStore(folder(app), ModelSpec.forChip(chip), File(app.cacheDir, MODELS))
    }

    /**
     * Starts the download anew: on an unmetered network only (Wi-Fi), with storage not low, retried with backoff after a
     * network error that got nowhere. A download under way or stopping is replaced; the new one waits until it has
     * stopped writing and goes on from its parts (HTTP Range). Models already in place are not fetched again.
     */
    fun start(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

    /**
     * Starts the download unless one is under way or waiting: for models the switch needs that are not all in place
     * (an update that pins a new file), without restarting a download every time the app is opened.
     */
    fun startIfIdle(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
    }

    /** Stops a download and deletes the models and their parts; true when none is left. */
    suspend fun delete(context: Context): Boolean {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        val gone = lock.withLock {
            withContext(Dispatchers.IO) { store(context)?.delete() ?: ModelStore.removeRetired(folder(context)) }
        }
        deleted.update { it + 1 }
        if (!gone) Log.w(LOG_TAG, "models: not every file could be deleted")
        return gone
    }

    /** A model that did not load: deletes that file only (see [ModelStore.discard]); the models are not ready then. */
    suspend fun discard(context: Context, model: File) {
        val gone = lock.withLock { withContext(Dispatchers.IO) { store(context)?.discard(model) ?: false } }
        deleted.update { it + 1 }
        if (!gone) Log.w(LOG_TAG, "models: ${model.name} could not be deleted")
    }

    /**
     * The card's model state: the files on disk and the free space, read again whenever the download work changes (and
     * whenever the card collects it again). Null on a phone whose chip the AI does not run on.
     */
    fun state(context: Context): Flow<ModelState?> {
        val store = store(context) ?: return flowOf(null)
        return combine(WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK), deleted) { infos, _ ->
            val info = infos.firstOrNull()
            modelState(
                ready = store.isReady(),
                onDisk = store.bytesOnDisk(),
                total = store.totalBytes,
                phase = info?.state?.let(::phase),
                doneBytes = info?.progress?.getLong(DONE, -1)?.takeIf { it >= 0 },
                neededBytes = store.bytesNeeded(),
                // What an earlier version left is deleted before the download checks the room: it counts as free.
                freeBytes = freeBytes(store) + store.retiredBytes(),
            )
        }.flowOn(Dispatchers.IO).distinctUntilChanged()
    }

    /**
     * Free space where the models go, as the downloader measures it ([ModelDownloader]'s `File.getUsableSpace`), so the
     * card and the download agree. Not `StorageManager.getAllocatableBytes`: clearing cached data to make room could
     * clear the archive being downloaded into the cache.
     */
    @SuppressLint("UsableSpace")
    private fun freeBytes(store: ModelStore): Long = store.folder.parentFile?.usableSpace ?: Long.MAX_VALUE

    /** The download's notification: "Downloading the AI models: 42% (412 MB of 981 MB)" over a progress bar. */
    internal fun foregroundInfo(context: Context, done: Long, total: Long): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.ai_models_channel), NotificationManager.IMPORTANCE_LOW))
        val percent = (done * PERCENT / total.coerceAtLeast(1)).toInt()
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.ai_title))
            .setContentText(
                context.getString(
                    R.string.ai_models_downloading,
                    percent,
                    Formatter.formatShortFileSize(context, done),
                    Formatter.formatShortFileSize(context, total),
                ),
            )
            .setProgress(PERCENT, percent, false)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun phase(state: WorkInfo.State): DownloadPhase? = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadPhase.WAITING
        WorkInfo.State.RUNNING -> DownloadPhase.RUNNING
        WorkInfo.State.FAILED -> DownloadPhase.FAILED
        WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> null
    }

    private const val RETRY_MINUTES = 1L
    private const val MODELS = "models"
    private const val PERCENT = 100
}
