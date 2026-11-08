package app.lyrify.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Songs tab's top bar: a capsule search field that filters in place (no expanded search view), with Clear while
 * there is text, else Sort and More options. Clear at rest; a glass band fades in while the list is [under] it.
 * [navigation]: the Tone picker's back button before the field.
 */
@Composable
fun SongSearchBar(
    query: SongQuery,
    onQuery: (SongQuery) -> Unit,
    focus: FocusRequester,
    onSettings: (() -> Unit)?,
    under: Boolean = false,
    backdrop: GlassBackdrop? = null,
    navigation: (@Composable () -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    TopBand(under, backdrop) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.statusBarsPadding().heightIn(min = 64.dp).padding(start = if (navigation != null) 12.dp else ScreenPadding, end = ScreenPadding, top = 4.dp, bottom = 8.dp),
        ) {
            navigation?.let {
                it()
                Spacer(Modifier.width(4.dp))
            }
            LyrifySearchField(
                value = query.text,
                onValueChange = { onQuery(query.copy(text = it)) },
                placeholder = stringResource(R.string.search_hint),
                focus = focus,
                onSearch = { keyboard?.hide() },
                modifier = Modifier.weight(1f),
            ) {
                if (query.text.isNotEmpty()) {
                    ChromeIconButton(R.drawable.ic_close, stringResource(R.string.search_clear), { onQuery(query.copy(text = "")) }, glass = false, size = 36.dp)
                } else {
                    Box {
                        ChromeIconButton(R.drawable.ic_sort, stringResource(R.string.sort_cd), { sortMenu = true }, glass = false, size = 36.dp)
                        SortMenu(sortMenu, query.sort, onDismiss = { sortMenu = false }) {
                            sortMenu = false
                            onQuery(query.copy(sort = it))
                        }
                    }
                    // No More options in the Tones song picker (onSettings null).
                    if (onSettings != null) Box {
                        ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { moreMenu = true }, glass = false, size = 36.dp)
                        LyrifyMenu(expanded = moreMenu, onDismiss = { moreMenu = false }) {
                            MenuRow(
                                stringResource(R.string.menu_settings),
                                R.drawable.ic_settings,
                                onClick = {
                                    moreMenu = false
                                    onSettings()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Recent first, Title A to Z, Artist A to Z; the selected one on the brand's container with a check. */
@Composable
private fun SortMenu(expanded: Boolean, selected: SongSort, onDismiss: () -> Unit, onSort: (SongSort) -> Unit) {
    LyrifyMenu(expanded = expanded, onDismiss = onDismiss) {
        SongSort.entries.forEach { sort -> MenuRow(stringResource(sort.label), icon = null, onClick = { onSort(sort) }, selected = sort == selected) }
    }
}

/**
 * The Songs tab under its search bar: the four-part status bar, the status chips of a group with several statuses, the
 * result line, and the songs in sections (only the banner while the song list cannot be read). A song opens its sheet,
 * or with [picker] (the Tones song picker) is picked. [reselect] grows when the tab is tapped again: back to the top.
 */
@Composable
fun SongsScreen(
    ledger: LedgerState,
    query: SongQuery,
    onQuery: (SongQuery) -> Unit,
    timing: PlainTiming,
    removal: RemovalState,
    retrying: Boolean,
    connected: Boolean,
    reselect: Int,
    onRetryFailed: () -> Unit,
    onPickLyrics: (String) -> Unit,
    banner: @Composable () -> Unit,
    picker: SongPicker? = null,
) {
    // Up to a few thousand songs: filtered off the main thread. Null until the first list is ready.
    val list by produceState<SongList?>(null, ledger, query) {
        value = withContext(Dispatchers.Default) { songList(ledger, query) }
    }
    val listState = rememberLazyListState()
    // A new filter or order starts at the top; coming back to the tab keeps the place.
    var shownQuery by rememberSaveable(stateSaver = SongQuery.Saver) { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != shownQuery) {
            shownQuery = query
            listState.scrollToItem(0)
        }
    }
    // Only a new tap goes to the top: coming back to the tab composes this again with the same count.
    var handledReselect by rememberSaveable { mutableIntStateOf(reselect) }
    LaunchedEffect(reselect) {
        if (reselect != handledReselect) {
            handledReselect = reselect
            listState.animateScrollToItem(0)
        }
    }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.songs_title)

    Column(Modifier.fillMaxSize().semantics { paneTitle = title }) {
        banner()
        // An unreadable song list is not an empty library: nothing is known, so the banner alone says why.
        list?.takeIf { ledger.readable }?.let { songs ->
            StatusBar(songs, query, onQuery, Modifier.padding(horizontal = ScreenPadding))
            if (songs.statuses.isNotEmpty()) StatusChips(songs, query, onQuery)
            ResultLine(songs.shown, query, onQuery)
            SongSections(songs, ledger.entries.isEmpty(), query, onQuery, listState, picker?.label ?: R.string.song_open) { path ->
                if (picker != null) ledger.entries[path]?.let(picker.onPick) else sheet = path
            }
        }
    }

    sheet?.let { ledger.entries[it] }?.let { entry ->
        SongSheet(
            entry = entry,
            timing = timing,
            removal = removal,
            canRetry = connected,
            retrying = retrying,
            onRetryFailed = onRetryFailed,
            onPickLyrics = {
                sheet = null
                onPickLyrics(entry.path)
            },
            onDismiss = { sheet = null },
        )
    }
}

/** Four equal segments, one per group: icon and count over the label; tap to filter, tap again to clear. Empty groups wait. */
@Composable
private fun StatusBar(list: SongList, query: SongQuery, onQuery: (SongQuery) -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(24.dp)).padding(4.dp),
    ) {
        SongGroup.entries.forEach { group ->
            val count = list.groupCounts.getValue(group)
            val selected = query.group == group
            StatusSegment(group, count, selected, Modifier.weight(1f)) {
                onQuery(query.copy(group = if (selected) null else group, status = null))
            }
        }
    }
}

@Composable
private fun StatusSegment(group: SongGroup, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val tone = LocalStatusColors.current[group.tone]
    val highlight = LocalBrandColors.current.highlight
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(group.label)
    val description = stringResource(R.string.filter_count, label, formatCount(count))
    val enabled = count > 0 || selected
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) tone.container else Color.Transparent)
            .drawBehind {
                // The sung line, centred under the selected segment.
                if (selected) {
                    val line = Size(SUNG_LINE_WIDTH.toPx(), SUNG_LINE_HEIGHT.toPx())
                    drawRoundRect(highlight, Offset((size.width - line.width) / 2, size.height - line.height - 3.dp.toPx()), line, CornerRadius(line.height / 2))
                }
            }
            .selectable(selected = selected, enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .heightIn(min = 56.dp)
            .padding(start = 2.dp, end = 2.dp, top = 6.dp, bottom = 9.dp),
    ) {
        val content = when {
            selected -> tone.onContainer
            enabled -> MaterialTheme.colorScheme.onSurface
            else -> muted.copy(alpha = DISABLED)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(painterResource(group.icon), contentDescription = null, tint = if (selected) tone.onContainer else tone.accent.copy(alpha = if (enabled) 1f else DISABLED), modifier = Modifier.size(17.dp))
            Text(formatCount(count), style = MaterialTheme.typography.titleSmall.tabular(), color = content, maxLines = 1)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) tone.onContainer else muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The statuses of the selected group with songs; none selected means the whole group. */
@Composable
private fun StatusChips(list: SongList, query: SongQuery, onQuery: (SongQuery) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = ScreenPadding), modifier = Modifier.padding(top = 8.dp)) {
        items(list.statuses, key = { it.name }) { status ->
            val style = status.style
            val selected = query.status == status
            val label = stringResource(style.label)
            val count = list.statusCounts.getValue(status)
            val description = stringResource(R.string.filter_count, label, formatCount(count))
            LyrifyChip(
                selected = selected,
                onClick = { onQuery(query.copy(status = if (selected) null else status)) },
                role = Role.Checkbox,
                tone = style.tone,
                icon = style.icon,
                iconTint = LocalStatusColors.current[style.tone].accent,
                count = count,
                modifier = Modifier.semantics { contentDescription = description },
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** "1,345 songs", and Clear while a search or filter narrows the list. */
@Composable
private fun ResultLine(shown: Int, query: SongQuery, onQuery: (SongQuery) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = ScreenPadding, end = 4.dp)) {
        Text(
            pluralStringResource(R.plurals.songs_count, shown, formatCount(shown)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (query.filtered) LyrifyButton(stringResource(R.string.songs_clear_filters), onClick = { onQuery(SongQuery(sort = query.sort)) }, kind = ButtonKind.GHOST, compact = true)
    }
}

@Composable
private fun SongSections(
    list: SongList,
    noSongs: Boolean,
    query: SongQuery,
    onQuery: (SongQuery) -> Unit,
    state: LazyListState,
    @StringRes openLabel: Int,
    onOpen: (String) -> Unit,
) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (list.sections.isEmpty()) {
            item(key = "empty") {
                if (noSongs) {
                    EmptyState(EmptyArt.Glyph, stringResource(R.string.songs_empty_title), text = stringResource(R.string.songs_empty))
                } else {
                    EmptyState(
                        EmptyArt.Icon(R.drawable.ic_search_off),
                        stringResource(R.string.songs_no_match),
                        action = stringResource(R.string.songs_clear_all),
                        onAction = { onQuery(SongQuery(sort = query.sort)) },
                    )
                }
            }
        }
        list.sections.forEach { section ->
            stickyHeader(key = "section ${section.section}") { SectionHeader(section.section) }
            items(section.songs, key = { it.path }) { entry -> SongRow(entry, Modifier.animateItem(), openLabel) { onOpen(entry.path) } }
        }
    }
}

@Composable
private fun SectionHeader(section: Section) {
    val text = when (section) {
        Section.Today -> stringResource(R.string.header_today)
        Section.Yesterday -> stringResource(R.string.header_yesterday)
        Section.ThisWeek -> stringResource(R.string.header_week)
        Section.Earlier -> stringResource(R.string.header_earlier)
        is Section.Letter -> section.letter
    }
    // A small capsule at the start, over the rows as they scroll under it.
    Box(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 6.dp)) {
        SectionTitle(
            text,
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                .heightIn(min = 28.dp)
                .wrapContentHeight()
                .padding(horizontal = 12.dp),
        )
    }
}

/** One TalkBack stop: "title, artist, status"; the cover and its badge are decorative. */
@Composable
private fun SongRow(entry: LedgerEntry, modifier: Modifier, @StringRes openLabel: Int, onOpen: () -> Unit) {
    val artist = artistOf(entry)
    val description = listOf(entry.title, artist, stringResource(entry.status.style.label)).joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            // The press tints a pill inside the gutter, not a band across the screen.
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClickLabel = stringResource(openLabel), onClick = onOpen)
            .semantics { contentDescription = description }
            .heightIn(min = 64.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        SongCover(SongArt(entry.path), status = entry.status)
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val DISABLED = 0.38f
