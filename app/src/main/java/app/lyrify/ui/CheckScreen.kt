package app.lyrify.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToLong

/** Whether plain lyrics picked on the check list get synced: the AI syncs them when it is on, a phone it does not run on never. */
enum class PlainTiming {
    AI_ON,
    AI_OFF,
    NO_AI,
    ;

    companion object {
        /** [supported]: the AI runs on this phone (its chip and Android version, as the AI tab says); [enabled]: AI lyrics are on. */
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
 * The answers on the check list, run in [scope] (the app's): leaving the screen or turning the phone neither cancels one
 * nor loses what it says. [busy] holds the songs with an answer out; what each answer says comes out of [messages], for
 * the app's messages.
 */
class CheckAnswers(private val scope: CoroutineScope) {
    private val _busy = MutableStateFlow(emptySet<String>())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _messages = Channel<Int>(Channel.BUFFERED, BufferOverflow.DROP_OLDEST)

    /** Each answer's message, taken once: kept until a screen is there to show it. */
    val messages: Flow<Int> = _messages.receiveAsFlow()

    /**
     * Runs [action] for the song at [path], unless an answer for it is out (null then); [timing] says what becomes of plain
     * lyrics. The result is for the screen that asked, while it is there. An answer that fails is logged by [scope]'s
     * handler, and its result is cancelled.
     */
    fun answer(path: String, timing: PlainTiming, action: suspend () -> Checked): Deferred<Checked>? {
        if (path in _busy.getAndUpdate { it + path }) return null
        val answered = CompletableDeferred<Checked>()
        scope.launch {
            try {
                val result = action()
                _messages.trySend(checkedMessage(result, timing))
                answered.complete(result)
            } finally {
                _busy.update { it - path }
                answered.cancel()
            }
        }
        return answered
    }
}

/**
 * "Check these": the songs Lyrify found lyrics for but is not sure about ([checkList]), one per page, opened at
 * [initialPath] when it comes from a song. "Use" adds those lyrics, "None of these" leaves the song to the AI, "Search
 * myself" looks for others in a sheet. Answers run in [answers], so the app's message says how ([timing]: what becomes
 * of plain lyrics) even if the screen closes; while it is up, the next song slides in. Lyrics shown stay in memory only.
 */
@Composable
fun CheckScreen(ledger: LedgerState, timing: PlainTiming, actions: CheckActions, answers: CheckAnswers, initialPath: String?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // A few songs out of a few thousand entries: one pass, cheap enough for the main thread.
    val songs = remember(ledger) { checkList(ledger) }
    val latest by rememberUpdatedState(songs)
    val pager = rememberPagerState(initialPage = songs.indexOfFirst { it.path == initialPath }.coerceAtLeast(0)) { songs.size }
    // Only the slide runs in the screen's scope: a song leaves the pager as soon as its answer is in the ledger.
    val scope = rememberCoroutineScope()
    val busy by answers.busy.collectAsStateWithLifecycle()
    var info by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf<LedgerEntry?>(null) }
    val current = songs.getOrNull(pager.currentPage)

    fun act(path: String, action: suspend () -> Checked) {
        val answer = answers.answer(path, timing, action) ?: return
        scope.launch {
            val result = answer.await()
            // The answered song slides away, unless the ledger already took it off the list.
            val settled = result is Checked.Done || result == Checked.Changed
            val page = pager.currentPage
            if (settled && latest.getOrNull(page)?.path == path && page + 1 < latest.size) pager.animateScrollToPage(page + 1)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LyrifyTopBar(
                title = { Text(stringResource(R.string.check_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
                actions = {
                    if (songs.isNotEmpty()) {
                        Text(
                            stringResource(R.string.check_position, (pager.currentPage + 1).coerceAtMost(songs.size), songs.size),
                            style = MaterialTheme.typography.labelLarge.tabular(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    ChromeIconButton(R.drawable.ic_info, stringResource(R.string.check_info_cd), { info = true })
                },
            )
        },
        bottomBar = {
            // The message docks above the buttons, so it never covers them.
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                ToastSlot(Modifier.padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 8.dp))
                if (current != null) {
                    val idle = current.path !in busy
                    GlassSurface(null, CircleShape, Modifier.padding(horizontal = ScreenPadding).fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(6.dp)) {
                            LyrifyButton(
                                stringResource(R.string.check_none),
                                onClick = { act(current.path) { actions.decline(current.path) } },
                                modifier = Modifier.weight(1f),
                                kind = ButtonKind.SECONDARY,
                                enabled = idle,
                            )
                            LyrifyButton(
                                stringResource(R.string.check_search_myself),
                                onClick = { searching = current },
                                modifier = Modifier.weight(1f),
                                kind = ButtonKind.SECONDARY,
                                icon = R.drawable.ic_search,
                                enabled = idle,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (current != null && current.path in busy) SungBar(null, Modifier.fillMaxWidth(), height = 3.dp)
            if (songs.isEmpty()) {
                EmptyState(EmptyArt.Icon(R.drawable.ic_task_alt, Tone.SYNCED), stringResource(R.string.check_empty_title), text = stringResource(R.string.check_empty))
            } else {
                HorizontalPager(state = pager, key = { songs[it].path }, modifier = Modifier.fillMaxSize()) { page ->
                    val entry = songs[page]
                    CheckPage(entry, busy = entry.path in busy, preview = actions.preview) { id -> act(entry.path) { actions.choose(entry.path, id) } }
                }
            }
        }
    }

    if (info) HelpSheet(stringResource(R.string.check_title), stringResource(R.string.check_info)) { info = false }
    searching?.let { entry ->
        SearchMyselfSheet(entry, busy = entry.path in busy, actions = actions, onDismiss = { searching = null }) { id ->
            searching = null
            act(entry.path) { actions.choose(entry.path, id) }
        }
    }
}

/** One song: its tags and file as found, and its matches. */
@Composable
private fun CheckPage(entry: LedgerEntry, busy: Boolean, preview: suspend (Long) -> LyricsPreview, onChoose: (Long) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SongCover(SongArt(entry.path), size = 72.dp, shape = MaterialTheme.shapes.medium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.check_artist_length, artistOf(entry), songLength(entry.durationMs)), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                FileChip(File(entry.path).name)
            }
        }
        Text(stringResource(R.string.check_matches), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.semantics { heading() })
        entry.candidates.forEach { candidate -> CandidateCard(candidate, enabled = !busy, preview = preview) { onChoose(candidate.id) } }
    }
}

/** The song's file name in a chip; TalkBack hears "File <name>". */
@Composable
private fun FileChip(name: String) {
    val description = stringResource(R.string.check_file_cd, name)
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)) {
            Icon(painterResource(R.drawable.ic_folder_open), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
    }
}

/** "Search myself": a field for the lyrics service's search, and up to ten results of the song's length. "Use" answers. */
@Composable
private fun SearchMyselfSheet(entry: LedgerEntry, busy: Boolean, actions: CheckActions, onDismiss: () -> Unit, onChoose: (Long) -> Unit) {
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

    LyrifySheet(onDismiss = onDismiss, spacing = 12.dp) {
        Text(stringResource(R.string.check_search_myself), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LyrifyField(
                value = text,
                onValueChange = { text = it },
                label = stringResource(R.string.check_search_hint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.weight(1f),
            )
            LyrifyButton(stringResource(R.string.check_search), onClick = ::search, enabled = !searchingNow && text.isNotBlank(), compact = true)
        }
        Text(stringResource(R.string.check_search_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (searchingNow) SungBar(null, Modifier.fillMaxWidth())
        when (val result = found) {
            null -> Unit
            is Searched.Unavailable -> Text(stringResource(R.string.check_busy), style = MaterialTheme.typography.bodyMedium)
            is Searched.Found ->
                if (result.candidates.isEmpty()) {
                    Text(stringResource(R.string.check_search_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    result.candidates.forEach { candidate -> CandidateCard(candidate, enabled = !busy, preview = actions.preview) { onChoose(candidate.id) } }
                }
        }
    }
}

/**
 * A match: its kind (synced or plain), title, "Synced · artist · album · 4:06", "2 lines differ" when its lyrics are
 * nearly those of a match above, a preview toggle and "Use".
 */
@Composable
private fun CandidateCard(candidate: Candidate, enabled: Boolean, preview: suspend (Long) -> LyricsPreview, onChoose: () -> Unit) {
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

    val tone = if (candidate.synced) Tone.SYNCED else Tone.PLAIN
    val kind = stringResource(if (candidate.synced) R.string.filter_synced else R.string.check_not_timed)
    LyrifyCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
            val toneColors = LocalStatusColors.current[tone]
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).clip(CircleShape).background(toneColors.container)) {
                Icon(painterResource(if (candidate.synced) R.drawable.ic_lyrics_fill else R.drawable.ic_notes), contentDescription = null, tint = toneColors.onContainer, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                val length = songLength(candidate.duration.roundToLong() * MS_PER_SECOND)
                Text(candidate.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    candidate.album?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.check_candidate_album, kind, candidate.artist, it, length) }
                        ?: stringResource(R.string.check_candidate, kind, candidate.artist, length),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                candidate.differingLines?.let { lines ->
                    Box(Modifier.padding(top = 4.dp)) {
                        SmallPill(R.drawable.ic_difference, pluralStringResource(R.plurals.check_lines_differ, lines, lines), colors.surfaceContainerHighest, colors.onSurfaceVariant)
                    }
                }
            }
            ChromeIconButton(
                if (shown) R.drawable.ic_visibility_off else R.drawable.ic_visibility,
                stringResource(if (shown) R.string.check_hide_lyrics else R.string.check_show_lyrics),
                ::toggle,
                enabled = enabled,
                glass = false,
                iconSize = 24.dp,
            )
            LyrifyButton(stringResource(R.string.check_this_one), onClick = onChoose, enabled = enabled, compact = true)
        }
        AnimatedVisibility(visible = shown) {
            val style = MaterialTheme.typography.bodyMedium
            val box = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp).clip(MaterialTheme.shapes.medium).background(colors.surfaceContainerHigh).padding(12.dp)
            when (val text = lyrics) {
                null -> Text(stringResource(R.string.check_lyrics_loading), style = style, color = colors.onSurfaceVariant, modifier = box)
                is LyricsPreview.Lines -> Text(text.lines.joinToString("\n"), style = style, modifier = box)
                LyricsPreview.None -> Text(stringResource(R.string.check_gone), style = style, color = colors.onSurfaceVariant, modifier = box)
                is LyricsPreview.Unavailable -> Text(stringResource(R.string.check_lyrics_failed), style = style, color = colors.onSurfaceVariant, modifier = box)
            }
        }
    }
}

/** What the message says after an answer; picked plain lyrics show nothing until they are synced, so [timing] says when. */
@StringRes
fun checkedMessage(result: Checked, timing: PlainTiming): Int = when (result) {
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

private const val MS_PER_SECOND: Long = 1_000
