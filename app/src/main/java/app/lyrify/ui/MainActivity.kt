package app.lyrify.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.BuildConfig
import app.lyrify.Lyrify
import app.lyrify.data.LedgerRepository
import app.lyrify.data.LedgerState
import app.lyrify.data.LedgerStore
import app.lyrify.media.NowPlaying

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

    // Both grants are switched in Settings; check again whenever the user comes back.
    LifecycleResumeEffect(Unit) {
        access = Access.of(context)
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
        val track by NowPlaying.track.collectAsStateWithLifecycle()
        val sweep by NowPlaying.sweep.collectAsStateWithLifecycle()
        val connected by NowPlaying.connected.collectAsStateWithLifecycle()
        val retrying by Lyrify.retrying.collectAsStateWithLifecycle()
        HomeScreen(
            ledger = state,
            track = track,
            sweep = sweep,
            connected = connected,
            retrying = retrying,
            versionName = BuildConfig.VERSION_NAME,
            onRetryFailed = { Lyrify.retryFailed(context) },
            onOpenNotificationAccess = { context.startFirst(notificationAccessIntents(context)) },
            onRemove = { Lyrify.remove(context, it) },
        )
    }
}
