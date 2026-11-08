package app.lyrify.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.Removal
import app.lyrify.lyrics.TrackNames
import app.lyrify.lyrics.canRemove
import app.lyrify.media.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Removing lyrics, from the Now tab ("Wrong lyrics") or the song sheet: which songs can be, the confirm dialog
 * ([RemoveLyricsDialog]) and the message after.
 */
@Stable
class RemovalState internal constructor(private val leftOnDisk: () -> Set<String>, private val remove: (List<String>) -> Unit) {
    /** The songs the open dialog asks about; null while it is closed. */
    var confirming: List<String>? by mutableStateOf(null)
        private set

    /** What asked, closed once the removal is confirmed: the song sheet. */
    private var onConfirmed: () -> Unit = {}

    /** Lyrify wrote [entry]'s lyrics file and it is unchanged, or a removal left the file behind. */
    fun canRemove(entry: LedgerEntry?): Boolean = entry != null && (entry.removable || entry.path in leftOnDisk())

    fun ask(paths: List<String>, onConfirmed: () -> Unit = {}) {
        this.onConfirmed = onConfirmed
        confirming = paths
    }

    fun dismiss() {
        confirming = null
    }

    fun confirm() {
        confirming?.let { paths ->
            onConfirmed()
            remove(paths)
        }
        confirming = null
    }
}

/** A [RemovalState] for [ledger]; the result shows in [toasts]. The Samsung Music hint names [player] while it is the one followed. */
@Composable
fun rememberRemoval(ledger: LedgerState, player: Player?, onRemove: suspend (String) -> Removal, toasts: ToastState): RemovalState {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val currentPlayer by rememberUpdatedState(player)
    val currentRemove by rememberUpdatedState(onRemove)
    // Lyrics marked removed whose file is still there (its delete and undo failed) can be removed again; that takes a look at the disk.
    val leftOnDisk by produceState(emptySet<String>(), ledger) {
        value = withContext(Dispatchers.IO) {
            ledger.entries.values.filter { it.status == LyricsStatus.REMOVED && canRemove(it) }.mapTo(mutableSetOf()) { it.path }
        }
    }
    return remember(scope, toasts, resources) {
        RemovalState(leftOnDisk = { leftOnDisk }) { paths ->
            scope.launch {
                val results = paths.map { currentRemove(it) }
                val samsung = currentPlayer?.takeIf { it.isSamsungMusic }
                val message = removalMessage(results, samsungMusic = samsung != null)
                if (samsung != null) toasts.say(resources, message, samsung.name) else toasts.say(resources, message)
            }
        }
    }
}

/** What the message says after removing lyrics: the worst outcome first. The restart hint is for Samsung Music only. */
fun removalMessage(results: List<Removal>, samsungMusic: Boolean = false): Int = when {
    Removal.CHANGED in results -> R.string.removal_changed
    Removal.FAILED in results -> R.string.removal_failed
    Removal.REMOVED in results -> if (samsungMusic) R.string.removal_done_samsung else R.string.removal_done
    Removal.ALREADY_GONE in results -> R.string.removal_gone
    else -> R.string.removal_failed
}

/** "Remove these lyrics?" while [removal] asks. */
@Composable
fun RemoveLyricsDialog(removal: RemovalState) {
    if (removal.confirming == null) return
    ConfirmDialog(
        title = stringResource(R.string.remove_title),
        text = stringResource(R.string.remove_text),
        confirm = stringResource(R.string.remove_confirm),
        dismiss = stringResource(R.string.remove_cancel),
        onConfirm = removal::confirm,
        onDismiss = removal::dismiss,
    )
}

/** The song's artist, or "No artist" when its tags name none. */
@Composable
fun artistOf(entry: LedgerEntry): String = entry.artist?.takeIf { TrackNames.cleanArtist(it) != null } ?: stringResource(R.string.check_no_artist)

/** A length as "3:26". */
fun songLength(ms: Long): String = DateUtils.formatElapsedTime(ms / MS_PER_SECOND)

/** Where the details went: the status's sentence, three facts, and the one action that fits, pinned under them. */
@Composable
fun SongSheet(
    entry: LedgerEntry,
    timing: PlainTiming,
    removal: RemovalState,
    canRetry: Boolean,
    retrying: Boolean,
    onRetryFailed: () -> Unit,
    onPickLyrics: () -> Unit,
    onDismiss: () -> Unit,
) {
    val style = entry.status.style
    val now = remember(entry) { System.currentTimeMillis() }
    val removable = removal.canRemove(entry)
    val pick = entry.status == LyricsStatus.NEEDS_CHECK
    val retry = entry.status == LyricsStatus.ERROR
    LyrifySheet(
        onDismiss = onDismiss,
        footer = if (!removable && !pick && !retry) {
            null
        } else {
            {
                val full = Modifier.fillMaxWidth()
                if (removable) {
                    LyrifyButton(
                        stringResource(R.string.remove_lyrics),
                        onClick = { removal.ask(listOf(entry.path), onConfirmed = onDismiss) },
                        modifier = full,
                        kind = ButtonKind.DESTRUCTIVE,
                        icon = R.drawable.ic_delete,
                    )
                }
                if (pick) LyrifyButton(stringResource(R.string.check_open), onClick = onPickLyrics, modifier = full)
                if (retry) {
                    LyrifyButton(
                        stringResource(if (retrying) R.string.retrying else R.string.retry_now),
                        onClick = onRetryFailed,
                        modifier = full,
                        kind = ButtonKind.SECONDARY,
                        icon = R.drawable.ic_refresh,
                        enabled = canRetry && !retrying,
                    )
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SongCover(SongArt(entry.path), size = 88.dp, shape = MaterialTheme.shapes.large)
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(artistOf(entry), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val length = songLength(entry.durationMs)
                Text(
                    entry.album?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.song_album_length, it, length) } ?: length,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(entry.status)
            Text(stringResource(style.sentence(timing)), style = MaterialTheme.typography.bodyLarge)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact(R.drawable.ic_history, stringResource(R.string.song_updated, relative(entry.updatedAt, now)))
            (entry.nextAttemptAt ?: entry.aiRetryAt)?.let { Fact(R.drawable.ic_schedule, stringResource(R.string.song_next_try, relative(it, now))) }
            val file = File(entry.path).name
            val fileDescription = stringResource(R.string.check_file_cd, file)
            Fact(
                R.drawable.ic_folder_open,
                file,
                style = MaterialTheme.typography.bodySmall,
                middleEllipsis = true,
                modifier = Modifier.clearAndSetSemantics { contentDescription = fileDescription },
            )
        }
        if (!removable && entry.status.written) Fact(R.drawable.ic_info, stringResource(R.string.not_removable))
    }
}

/** "14 hours ago", "in 4 weeks". */
private fun relative(time: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

private const val MS_PER_SECOND: Long = 1_000
