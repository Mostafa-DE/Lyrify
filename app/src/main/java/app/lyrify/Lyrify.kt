package app.lyrify

import android.content.Context
import android.util.Log
import app.lyrify.data.LedgerRepository
import app.lyrify.lyrics.HttpLrclibClient
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.Removal
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Logcat tag (`adb logcat -s Lyrify`). Song titles and file paths appear in it; it never leaves the phone. */
const val LOG_TAG: String = "Lyrify"

/** Extra diagnostics: `adb shell setprop log.tag.Lyrify VERBOSE`. */
fun verbose(): Boolean = Log.isLoggable(LOG_TAG, Log.VERBOSE)

/** Logs a coroutine failure instead of crashing the process. */
val logFailures: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e -> Log.e(LOG_TAG, "background work failed", e) }

/**
 * Process-wide objects, built by hand on first use: one pipeline, so one LRCLIB client
 * (one request at a time app-wide) and one lookup per file at a time whoever asks.
 */
object Lyrify {
    private var pipeline: LyricsPipeline? = null

    /** Work started from the screen that must finish even if the screen closes. */
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)

    /**
     * Built once; on first use it also adopts the files v1 wrote, so they can be removed from the
     * app. Writes the app's death interrupted are settled by the listener's sweep, on every connect.
     */
    @Synchronized
    fun pipeline(context: Context): LyricsPipeline = pipeline ?: LyricsPipeline(
        client = HttpLrclibClient(userAgent = "Lyrify/${BuildConfig.VERSION_NAME} (personal use)"),
        ledger = LedgerRepository(context),
        clock = System::currentTimeMillis,
        log = { Log.d(LOG_TAG, it) },
        warn = { Log.w(LOG_TAG, it) },
    ).also { created ->
        pipeline = created
        background.launch { created.adoptLegacy() }
    }

    /** "Wrong lyrics" / "Remove lyrics": deletes the `.lrc` Lyrify wrote next to [audioPath], if unchanged. */
    suspend fun remove(context: Context, audioPath: String): Removal =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).remove(audioPath) }
}
