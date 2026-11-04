package app.lyrify.ui

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.Candidate
import app.lyrify.lyrics.Checked
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LyricsPreview
import app.lyrify.lyrics.Searched
import app.lyrify.lyrics.TrackNames
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToLong

/** Whether plain lyrics picked on the check list get timed: the AI times them when it is on, a phone it does not run on never. */
enum class PlainTiming {
    AI_ON,
    AI_OFF,
    NO_AI,
    ;

    companion object {
        /** [supported]: the AI runs on this phone (its chip and Android version, as the AI card says); [enabled]: AI lyrics are on. */
        fun of(supported: Boolean, enabled: Boolean): PlainTiming = when {
            !supported -> NO_AI
            enabled -> AI_ON
            else -> AI_OFF
        }
    }
}

/** What the "Check these" screen can do (the app's pipeline); each one asks LRCLIB once. */
@Immutable
data class CheckActions(
    val choose: suspend (path: String, id: Long) -> Checked,
    val decline: suspend (path: String) -> Checked,
    val preview: suspend (id: Long) -> LyricsPreview,
    val search: suspend (track: LocalTrack, text: String) -> Searched,
)

/**
 * "Check these": the songs Lyrify found lyrics for but is not sure about ([checkList]), each with its tags as found and
 * its candidates. "This one" adds those lyrics, "None of these" leaves the song to the AI, "Search myself" looks for
 * others. A song leaves the list once it is settled, and a snackbar says how ([timing]: what becomes of plain lyrics).
 * Lyrics shown stay in memory only.
 */
@Composable
fun CheckScreen(ledger: LedgerState, timing: PlainTiming, actions: CheckActions, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // A few songs out of a few thousand entries: one pass, cheap enough for the main thread.
    val songs = remember(ledger) { checkList(ledger) }
    // Actions run in the screen's scope: a song's card leaves as soon as its answer is in the ledger.
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var busy by remember { mutableStateOf(emptySet<String>()) }

    fun act(path: String, action: suspend () -> Checked) {
        if (path in busy) return
        busy = busy + path
        scope.launch {
            val result = try {
                action()
            } finally {
                busy = busy - path
            }
            snackbar.showSnackbar(resources.getString(message(result, timing)))
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.check_back))
                    }
                    Text(stringResource(R.string.check_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                }
            }
            item {
                Text(stringResource(R.string.check_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (songs.isEmpty()) {
                item { Text(stringResource(R.string.check_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(songs, key = { it.path }) { entry ->
                SongToCheck(
                    entry = entry,
                    busy = entry.path in busy,
                    actions = actions,
                    onChoose = { id -> act(entry.path) { actions.choose(entry.path, id) } },
                    onDecline = { act(entry.path) { actions.decline(entry.path) } },
                )
            }
        }
    }
}

/** One song: its tags as found, its candidates, "None of these" and "Search myself". */
@Composable
private fun SongToCheck(entry: LedgerEntry, busy: Boolean, actions: CheckActions, onChoose: (Long) -> Unit, onDecline: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var searching by rememberSaveable(entry.path) { mutableStateOf(false) }
    Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            val artist = entry.artist?.takeIf { TrackNames.cleanArtist(it) != null } ?: stringResource(R.string.check_no_artist)
            Text(
                listOf(artist, length(entry.durationMs / MS_PER_SECOND)).joinToString(SEPARATOR),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Text(stringResource(R.string.check_file, File(entry.path).name), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            entry.candidates.forEach { candidate ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
                CandidateRow(candidate, enabled = !busy, preview = actions.preview) { onChoose(candidate.id) }
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDecline, enabled = !busy) { Text(stringResource(R.string.check_none)) }
                TextButton(onClick = { searching = !searching }) { Text(stringResource(R.string.check_search_myself)) }
            }
            if (searching) SearchMyself(entry, busy, actions, onChoose)
        }
    }
}

/** "Search myself": a text field for LRCLIB's search, and up to ten results of the song's length to pick from. */
@Composable
private fun SearchMyself(entry: LedgerEntry, busy: Boolean, actions: CheckActions, onChoose: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var text by rememberSaveable(entry.path) { mutableStateOf(TrackNames.cleanTitle(entry.title)) }
    var searchingNow by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<Searched?>(null) }

    fun search() {
        if (searchingNow || text.isBlank()) return
        searchingNow = true
        scope.launch {
            found = try {
                actions.search(entry.track, text)
            } finally {
                searchingNow = false
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.check_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = ::search, enabled = !searchingNow && text.isNotBlank()) { Text(stringResource(R.string.check_search)) }
    }
    Text(stringResource(R.string.check_search_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    when (val result = found) {
        null -> Unit
        is Searched.Unavailable -> Text(stringResource(R.string.check_busy), style = MaterialTheme.typography.bodyMedium)
        is Searched.Found ->
            if (result.candidates.isEmpty()) {
                Text(stringResource(R.string.check_search_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                result.candidates.forEach { candidate ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.outlineVariant)
                    CandidateRow(candidate, enabled = !busy, preview = actions.preview) { onChoose(candidate.id) }
                }
            }
    }
}

/** A candidate: title, artist, album, length and kind of lyrics; "This one", and "Show lyrics" (fetched when asked, kept in memory). */
@Composable
private fun CandidateRow(candidate: Candidate, enabled: Boolean, preview: suspend (Long) -> LyricsPreview, onChoose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var shown by remember(candidate.id) { mutableStateOf(false) }
    var lyrics by remember(candidate.id) { mutableStateOf<LyricsPreview?>(null) }
    var fetch by remember(candidate.id) { mutableStateOf<Job?>(null) }

    fun toggle() {
        shown = !shown
        // One fetch at a time; one that found nothing or failed is tried again the next time the lyrics are shown.
        if (shown && lyrics !is LyricsPreview.Lines && fetch?.isActive != true) {
            lyrics = null
            fetch = scope.launch { lyrics = preview(candidate.id) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(candidate.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(candidate.artist, candidate.album?.takeIf { it.isNotBlank() }, length(candidate.duration.roundToLong())).joinToString(SEPARATOR),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Coloured as the song list's status pills.
        val pill = pillColors(if (candidate.synced) LyricsStatus.SYNCED else LyricsStatus.PLAIN_ONLY)
        Surface(color = pill.container, contentColor = pill.content, shape = CircleShape) {
            Text(
                stringResource(if (candidate.synced) R.string.filter_synced else R.string.check_not_timed),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onChoose, enabled = enabled) { Text(stringResource(R.string.check_this_one)) }
            TextButton(onClick = ::toggle) { Text(stringResource(if (shown) R.string.check_hide_lyrics else R.string.check_show_lyrics)) }
        }
        if (shown) {
            val style = MaterialTheme.typography.bodyMedium
            when (val text = lyrics) {
                null -> Text(stringResource(R.string.check_lyrics_loading), style = style, color = colors.onSurfaceVariant)
                is LyricsPreview.Lines -> Text(text.lines.joinToString("\n"), style = style)
                LyricsPreview.None -> Text(stringResource(R.string.check_gone), style = style, color = colors.onSurfaceVariant)
                is LyricsPreview.Unavailable -> Text(stringResource(R.string.check_lyrics_failed), style = style, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** What the snackbar says after an answer; picked plain lyrics show nothing until they are timed, so [timing] says when. */
@StringRes
private fun message(result: Checked, timing: PlainTiming): Int = when (result) {
    is Checked.Done -> when (result.status) {
        LyricsStatus.SYNCED -> R.string.check_done_synced
        LyricsStatus.PLAIN_ONLY -> when (timing) {
            PlainTiming.AI_ON -> R.string.check_done_plain_ai_on
            PlainTiming.AI_OFF -> R.string.check_done_plain_ai_off
            PlainTiming.NO_AI -> R.string.check_done_plain_no_ai
        }
        LyricsStatus.NOT_FOUND -> R.string.check_done_none
        LyricsStatus.EXISTING -> R.string.check_done_existing
        else -> R.string.check_changed
    }
    Checked.Changed -> R.string.check_changed
    Checked.Gone -> R.string.check_gone
    is Checked.Unavailable -> R.string.check_busy
    Checked.NotSaved -> R.string.check_not_saved
}

/** A length as "3:26". */
private fun length(seconds: Long): String = DateUtils.formatElapsedTime(seconds)

private const val MS_PER_SECOND: Long = 1_000
private const val SEPARATOR: String = " · "
