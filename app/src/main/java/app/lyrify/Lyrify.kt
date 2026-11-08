package app.lyrify

import android.app.Application
import android.content.Context
import android.util.Log
import app.lyrify.data.LedgerRepository
import app.lyrify.lyrics.Checked
import app.lyrify.lyrics.HttpLrclibClient
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LyricsPipeline
import app.lyrify.lyrics.LyricsPreview
import app.lyrify.lyrics.Removal
import app.lyrify.lyrics.Searched
import app.lyrify.models.ModelDownloads
import app.lyrify.overnight.AiLyrics
import app.lyrify.ui.CheckAnswers
import app.lyrify.ui.songImageLoader
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Logcat tag (`adb logcat -s Lyrify`). Song titles and file paths appear in it; it never leaves the phone. */
const val LOG_TAG: String = "Lyrify"

/** Extra diagnostics: `adb shell setprop log.tag.Lyrify VERBOSE`. */
fun verbose(): Boolean = Log.isLoggable(LOG_TAG, Log.VERBOSE)

/** Logs a coroutine failure instead of crashing the process. */
val logFailures: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e -> Log.e(LOG_TAG, "background work failed", e) }

/** The process: one image loader for the covers, set before any screen asks for one. */
class LyrifyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe(::songImageLoader)
    }
}

/**
 * Process-wide objects, built by hand on first use: one pipeline, so one LRCLIB client
 * (one request at a time app-wide) and one lookup per file at a time whoever asks.
 */
object Lyrify {
    private var pipeline: LyricsPipeline? = null

    /** Work started from the screen that must finish even if the screen closes. */
    private val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures)

    /** "Check these" answers: they finish, and the snackbar says how, even if the screen closes or the phone turns. */
    val checkAnswers: CheckAnswers = CheckAnswers(background)

    private val _deletingModels = MutableStateFlow(false)

    /** "Delete models" is waiting for the AI to stop, then deleting: the card says "Stopping…" meanwhile. */
    val deletingModels: StateFlow<Boolean> = _deletingModels.asStateFlow()

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

    /** "This one" on the check list: writes LRCLIB record [id]'s lyrics for [audioPath], or keeps its plain lyrics for the AI. */
    suspend fun choose(context: Context, audioPath: String, id: Long): Checked =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).choose(audioPath, id) }

    /** "None of these" on the check list. */
    suspend fun decline(context: Context, audioPath: String): Checked =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).decline(audioPath) }

    /** "Show lyrics" on the check list: the first lines of record [id], never stored. */
    suspend fun preview(context: Context, id: Long): LyricsPreview =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).preview(id) }

    /** "Search myself" on the check list. */
    suspend fun search(context: Context, track: LocalTrack, text: String): Searched =
        withContext(Dispatchers.IO) { pipeline(context.applicationContext).search(track, text) }

    /**
     * "Delete models": turns the AI off and deletes its models once a run in progress has actually stopped and closed its
     * sessions (ONNX Runtime and the NPU may still be reading them), finishing even if the screen closes. A run that does not stop in time
     * keeps them; the user can tap again.
     */
    fun deleteModels(context: Context) {
        val app = context.applicationContext
        if (!_deletingModels.compareAndSet(expect = false, update = true)) return
        background.launch {
            try {
                if (AiLyrics.afterStopping(app) { ModelDownloads.delete(app).also { AiLyrics.modelsDeleted(app) } } == null) {
                    Log.w(LOG_TAG, "models: the AI did not stop in time; nothing deleted")
                }
            } finally {
                _deletingModels.value = false
            }
        }
    }
}
