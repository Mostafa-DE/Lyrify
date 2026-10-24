package app.lyrify.ui

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.Removal
import app.lyrify.lyrics.canRemove
import app.lyrify.media.CurrentTrack
import app.lyrify.media.PlayingStatus
import app.lyrify.media.SweepProgress
import app.lyrify.media.playingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val ScreenPadding = 20.dp

/** LazyColumn index of the Songs title, where a tapped counter scrolls to. */
private const val SONGS_INDEX: Int = 3

@Composable
fun HomeScreen(
    ledger: LedgerState,
    track: CurrentTrack?,
    sweep: SweepProgress?,
    connected: Boolean,
    retrying: Boolean,
    versionName: String,
    onRetryFailed: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onRemove: suspend (String) -> Removal,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SongFilter.ALL) }
    // Up to a few thousand songs: filtered off the main thread.
    val songs by produceState(SongList(emptyList(), emptyMap(), listOf(SongFilter.ALL)), ledger, query, filter) {
        value = withContext(Dispatchers.Default) { songList(ledger, query, filter) }
    }
    val counts = remember(ledger) { ledger.counts() }
    val now = remember(ledger) { System.currentTimeMillis() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmRemoval by remember { mutableStateOf<List<String>?>(null) }
    val removedText = stringResource(R.string.removal_done)
    val changedText = stringResource(R.string.removal_changed)
    val failedText = stringResource(R.string.removal_failed)
    val goneText = stringResource(R.string.removal_gone)
    // Lyrics marked removed whose file is still there (its delete and undo failed) can be removed again; that takes a look at the disk.
    val leftOnDisk by produceState(emptySet<String>(), ledger) {
        value = withContext(Dispatchers.IO) {
            ledger.entries.values.filter { it.status == LyricsStatus.REMOVED && canRemove(it) }.mapTo(mutableSetOf()) { it.path }
        }
    }
    fun removable(path: String): Boolean = ledger.entries[path]?.removable == true || path in leftOnDisk
    val removableNow = track?.paths.orEmpty().filter(::removable)

    fun remove(paths: List<String>) {
        scope.launch {
            val results = paths.map { onRemove(it) }
            val message = when {
                Removal.CHANGED in results -> changedText
                Removal.FAILED in results -> failedText
                Removal.REMOVED in results -> removedText
                Removal.ALREADY_GONE in results -> goneText
                else -> failedText
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header() }
            item {
                NowPlayingCard(
                    track = track,
                    status = playingStatus(track, ledger),
                    sweep = sweep,
                    connected = connected,
                    canRemove = removableNow.isNotEmpty(),
                    onWrongLyrics = { confirmRemoval = removableNow },
                    onOpenNotificationAccess = onOpenNotificationAccess,
                )
            }
            item {
                // Retries run in the listener's queue sweep.
                CountsCard(counts, retrying, canRetry = connected, onRetryFailed) { status ->
                    query = ""
                    filter = SongFilter.of(status)
                    scope.launch { listState.animateScrollToItem(SONGS_INDEX) }
                }
            }
            item { Text(stringResource(R.string.songs_title), style = MaterialTheme.typography.titleMedium) }
            item { SearchField(query) { query = it } }
            item { FilterChips(songs, filter) { filter = it } }
            if (songs.songs.isEmpty()) {
                item {
                    Text(
                        stringResource(if (ledger.entries.isEmpty()) R.string.songs_empty else R.string.songs_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(songs.songs, key = { it.path }) { entry -> SongRow(entry, now, removable(entry.path)) { confirmRemoval = listOf(entry.path) } }
            item {
                Text(
                    text = stringResource(R.string.version_label, versionName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }

    confirmRemoval?.let { paths ->
        AlertDialog(
            onDismissRequest = { confirmRemoval = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoval = null
                        remove(paths)
                    },
                ) { Text(stringResource(R.string.remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemoval = null }) { Text(stringResource(R.string.remove_cancel)) } },
            title = { Text(stringResource(R.string.remove_title)) },
            text = { Text(stringResource(R.string.remove_text)) },
        )
    }
}

@Composable
fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(BrandInk),
        ) {
            // The launcher foreground's shapes fill the middle half of its 108-unit canvas.
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(56.dp))
        }
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun NowPlayingCard(
    track: CurrentTrack?,
    status: PlayingStatus,
    sweep: SweepProgress?,
    connected: Boolean,
    canRemove: Boolean,
    onWrongLyrics: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.primaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.now_playing), style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
            if (track != null) {
                Text(track.title, style = MaterialTheme.typography.headlineSmall, color = colors.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                track.artist?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = colors.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            when (status) {
                is PlayingStatus.Done -> StatusPill(status.status, statusLabel(status.status), Modifier.padding(top = 6.dp))
                else -> Text(stringResource(playingLabel(status)), style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
            }
            if (canRemove) {
                OutlinedButton(onClick = onWrongLyrics) { Text(stringResource(R.string.wrong_lyrics)) }
            }
            sweep?.takeIf { it.total > 0 }?.let {
                Text(
                    stringResource(if (it.paused) R.string.sweep_paused else R.string.sweep_progress, it.checked, it.total),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onPrimaryContainer,
                )
            }
            if (!connected) {
                Text(stringResource(R.string.listener_not_connected), style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
                TextButton(onClick = onOpenNotificationAccess) { Text(stringResource(R.string.setup_notifications_action)) }
            }
        }
    }
}

/** A row per status with its count; tapping one shows those songs. */
@Composable
private fun CountsCard(
    counts: Map<LyricsStatus, Int>,
    retrying: Boolean,
    canRetry: Boolean,
    onRetryFailed: () -> Unit,
    onShow: (LyricsStatus) -> Unit,
) {
    val failed = counts[LyricsStatus.ERROR] ?: 0
    // A write in progress lasts a moment: its row only shows while there is one.
    val shown = LyricsStatus.entries.filter { it != LyricsStatus.WRITING || (counts[it] ?: 0) > 0 }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            shown.forEachIndexed { index, status ->
                if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onShow(status) }.padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(pillColors(status).content))
                    Text(stringResource(statusLabel(status)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    Text((counts[status] ?: 0).toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
            Button(
                onClick = onRetryFailed,
                enabled = failed > 0 && !retrying && canRetry,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).fillMaxWidth(),
            ) {
                Text(stringResource(if (retrying) R.string.retrying else R.string.retry_failed))
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        trailingIcon = if (query.isEmpty()) {
            null
        } else {
            {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painterResource(R.drawable.ic_clear), contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FilterChips(songs: SongList, selected: SongFilter, onSelect: (SongFilter) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        songs.chips.forEach { chip ->
            FilterChip(
                selected = chip == selected,
                onClick = { onSelect(chip) },
                label = { Text(stringResource(R.string.filter_count, stringResource(filterLabel(chip)), songs.counts[chip] ?: 0)) },
            )
        }
    }
}

@Composable
private fun SongRow(entry: LedgerEntry, now: Long, removable: Boolean, onRemove: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        entry.artist?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(entry.status, filterLabel(SongFilter.of(entry.status)))
            Text(
                DateUtils.getRelativeTimeSpanString(entry.updatedAt, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            removable -> TextButton(onClick = onRemove) { Text(stringResource(R.string.remove_lyrics)) }
            entry.status == LyricsStatus.SYNCED ->
                Text(stringResource(R.string.not_removable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusPill(status: LyricsStatus, @StringRes label: Int, modifier: Modifier = Modifier) {
    val colors = pillColors(status)
    Surface(color = colors.container, contentColor = colors.content, shape = CircleShape, modifier = modifier) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@StringRes
private fun statusLabel(status: LyricsStatus): Int = when (status) {
    LyricsStatus.SYNCED -> R.string.status_synced
    LyricsStatus.EXISTING -> R.string.status_existing
    LyricsStatus.PLAIN_ONLY -> R.string.status_plain_only
    LyricsStatus.NOT_FOUND -> R.string.status_not_found
    LyricsStatus.INSTRUMENTAL -> R.string.status_instrumental
    LyricsStatus.DURATION_MISMATCH -> R.string.status_duration_mismatch
    LyricsStatus.ERROR -> R.string.status_error
    LyricsStatus.UNIDENTIFIED -> R.string.status_unidentified
    LyricsStatus.REMOVED -> R.string.status_removed
    LyricsStatus.WRITING -> R.string.status_writing
}

/** The short wording of the chips, also on song rows. */
@StringRes
private fun filterLabel(filter: SongFilter): Int = when (filter) {
    SongFilter.ALL -> R.string.filter_all
    SongFilter.SYNCED -> R.string.filter_synced
    SongFilter.PLAIN_ONLY -> R.string.filter_plain_only
    SongFilter.NOT_FOUND -> R.string.filter_not_found
    SongFilter.OTHER_VERSION -> R.string.filter_other_version
    SongFilter.INSTRUMENTAL -> R.string.filter_instrumental
    SongFilter.RETRYING -> R.string.filter_retrying
    SongFilter.NO_ARTIST -> R.string.filter_no_artist
    SongFilter.EXISTING -> R.string.filter_existing
    SongFilter.REMOVED -> R.string.filter_removed
    SongFilter.WRITING -> R.string.filter_writing
}

@StringRes
private fun playingLabel(status: PlayingStatus): Int = when (status) {
    PlayingStatus.Idle -> R.string.playing_idle
    PlayingStatus.FindingFile -> R.string.playing_finding_file
    PlayingStatus.NotInLibrary -> R.string.playing_not_in_library
    PlayingStatus.LookingUp -> R.string.playing_looking_up
    is PlayingStatus.Done -> statusLabel(status.status)
}
