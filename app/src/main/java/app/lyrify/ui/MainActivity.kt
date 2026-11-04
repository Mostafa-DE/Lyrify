package app.lyrify.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lyrify.BuildConfig
import app.lyrify.Lyrify
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerState
import app.lyrify.data.LedgerStore
import app.lyrify.logFailures
import app.lyrify.media.NowPlaying
import app.lyrify.models.ModelDownloads
import app.lyrify.models.NpuChips
import app.lyrify.overnight.AiLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The stored AI switch is the truth: a schedule that got lost is made again.
        lifecycleScope.launch(logFailures) { AiLyrics.reconcile(applicationContext) }
        val ledger = LedgerRepository(this)
        setContent {
            LyrifyTheme {
                App(ledger)
            }
        }
    }
}

@Composable
private fun App(ledger: LedgerStore) {
    val context = LocalContext.current
    var access by remember { mutableStateOf(Access.of(context)) }
    var unrestricted by remember { mutableStateOf(isUnrestricted(context)) }
    // The "Check these" screen over the home screen; system back returns.
    var checking by rememberSaveable { mutableStateOf(false) }

    // The grants are switched in Settings; check again whenever the user comes back.
    LifecycleResumeEffect(Unit) {
        access = Access.of(context)
        unrestricted = isUnrestricted(context)
        // An app in the foreground gets its daily time for long media work back.
        AiLyrics.appOpened(context)
        onPauseOrDispose { }
    }

    if (!access.complete) {
        SetupScreen(
            access = access,
            onOpenNotificationAccess = { context.startFirst(notificationAccessIntents(context)) },
            onOpenAllFilesAccess = { context.startFirst(allFilesAccessIntents(context)) },
            onOpenAppInfo = { context.startFirst(appInfoIntents(context)) },
        )
    } else {
        val state by ledger.ledger.collectAsStateWithLifecycle(initialValue = LedgerState())
        if (checking) {
            val aiEnabled by remember(context) { AiLyrics.enabled(context) }.collectAsStateWithLifecycle()
            val aiSupported = remember { NpuChips.device() != null && Build.VERSION.SDK_INT >= ModelDownloads.MIN_SDK }
            CheckScreen(
                ledger = state,
                timing = PlainTiming.of(aiSupported, aiEnabled),
                actions = remember(context) {
                    CheckActions(
                        choose = { path, id -> Lyrify.choose(context, path, id) },
                        decline = { Lyrify.decline(context, it) },
                        preview = { Lyrify.preview(context, it) },
                        search = { track, text -> Lyrify.search(context, track, text) },
                    )
                },
                onBack = { checking = false },
            )
            return
        }
        val track by NowPlaying.track.collectAsStateWithLifecycle()
        val sweep by NowPlaying.sweep.collectAsStateWithLifecycle()
        val connected by NowPlaying.connected.collectAsStateWithLifecycle()
        val models by remember(context) { ModelDownloads.state(context) }.collectAsStateWithLifecycle(initialValue = null)
        val aiEnabled by remember(context) { AiLyrics.enabled(context) }.collectAsStateWithLifecycle()
        val deletingModels by Lyrify.deletingModels.collectAsStateWithLifecycle()
        val aiProgress by AiLyrics.progress.collectAsStateWithLifecycle()
        val aiHold by AiLyrics.hold.collectAsStateWithLifecycle()
        val pluggedIn by remember(context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AiLyrics.pluggedIn(context) else flowOf(false)
        }.collectAsStateWithLifecycle(initialValue = false)
        HomeScreen(
            ledger = state,
            track = track,
            sweep = sweep,
            connected = connected,
            retrying = sweep?.retrying == true,
            versionName = BuildConfig.VERSION_NAME,
            ai = AiCardState(
                chip = remember { NpuChips.device() },
                newEnough = Build.VERSION.SDK_INT >= ModelDownloads.MIN_SDK,
                models = models,
                enabled = aiEnabled,
                progress = aiProgress,
                hold = aiHold,
                pluggedIn = pluggedIn,
                unrestricted = unrestricted,
                stopping = deletingModels,
            ),
            aiActions = remember(context) {
                AiActions(
                    onSwitch = { on -> if (on) AiLyrics.turnOn(context) else AiLyrics.turnOff(context) },
                    onDownload = { ModelDownloads.start(context) },
                    onDelete = { Lyrify.deleteModels(context) },
                    onOpenAppInfo = { context.startFirst(appInfoIntents(context)) },
                    readLog = { withContext(Dispatchers.IO) { AiLyrics.runLog(context).tail(RUN_LOG_LINES).joinToString("\n") } },
                )
            },
            onRetryFailed = NowPlaying::requestRetry,
            onOpenNotificationAccess = { context.startFirst(notificationAccessIntents(context)) },
            onRemove = { Lyrify.remove(context, it) },
            onOpenChecks = { checking = true },
        )
    }
}

/** Lines of the AI's run log its dialog shows: a night or two. */
private const val RUN_LOG_LINES = 300
