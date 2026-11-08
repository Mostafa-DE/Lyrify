package app.lyrify.ui

import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.media.CurrentTrack
import app.lyrify.media.PlayingStatus
import app.lyrify.media.SweepProgress
import app.lyrify.media.playingStatus
import coil3.compose.AsyncImage

/** The Now tab's status pill: "does this song have lyrics?" */
sealed interface NowPill {
    /** Nothing playing: no pill; the hero shows the empty state. */
    data object Idle : NowPill

    /** A neutral pill with the moving glyph while the file is found or lyrics are looked up. */
    data class Busy(@StringRes val label: Int) : NowPill

    /** A neutral pill for a song that is no file on the phone; its sheet says why. */
    data object NotOnPhone : NowPill {
        @StringRes val label: Int = R.string.playing_not_in_library

        @DrawableRes val icon: Int = R.drawable.ic_music_note

        @StringRes val sentence: Int = R.string.playing_not_in_library_detail
    }

    /** A neutral pill while the song list cannot be read: nothing is known about the song; the banner says why. */
    data object Unreadable : NowPill {
        @StringRes val label: Int = R.string.ledger_unreadable_title

        @DrawableRes val icon: Int = R.drawable.ic_warning
    }

    /** The song's status, as everywhere; it opens the song sheet. */
    data class Done(val style: StatusStyle) : NowPill
}

fun nowPill(status: PlayingStatus): NowPill = when (status) {
    PlayingStatus.Idle -> NowPill.Idle
    PlayingStatus.FindingFile -> NowPill.Busy(R.string.playing_finding_file)
    PlayingStatus.LookingUp -> NowPill.Busy(R.string.playing_looking_up)
    PlayingStatus.NotInLibrary -> NowPill.NotOnPhone
    PlayingStatus.Unreadable -> NowPill.Unreadable
    is PlayingStatus.Done -> NowPill.Done(status.status.style)
}

/** The playing song's cover: the player's picture, else the first copy's own; null for a song that is no file. */
fun nowCover(track: CurrentTrack?): Any? = track?.art ?: track?.paths?.firstOrNull()?.let(::SongArt)

/** What the dock's Now orb shows: the hero's cover, the status of the first copy with one, the title; idle when nothing plays. */
fun nowOrb(track: CurrentTrack?, ledger: LedgerState): NowOrb = NowOrb(
    cover = nowCover(track),
    status = track?.paths?.firstNotNullOfOrNull { ledger.entries[it]?.status },
    title = track?.title,
    idle = track == null,
)

/**
 * The Now tab: the song playing over its blurred cover, with one pill that answers "does it have lyrics?", "Wrong
 * lyrics" and the queue; then what needs the user (to check, the AI), the Library in numbers and the Recent covers.
 * [contentPadding] is the bars' room: the hero's backdrop reaches under the top bar.
 */
@Composable
fun NowScreen(
    ledger: LedgerState,
    track: CurrentTrack?,
    sweep: SweepProgress?,
    connected: Boolean,
    timing: PlainTiming,
    checks: Int,
    aiTile: AiTile?,
    removal: RemovalState,
    contentPadding: PaddingValues,
    banner: @Composable () -> Unit,
    onRetryFailed: () -> Unit,
    onOpenChecks: (String?) -> Unit,
    onOpenGroup: (SongGroup) -> Unit,
    onOpenAi: () -> Unit,
    onSeeAll: () -> Unit,
) {
    val counts = remember(ledger) { ledger.counts() }
    val recent = remember(ledger) { ledger.recent(RECENT_SONGS) }
    val retrying = sweep?.retrying == true
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val status = playingStatus(track, ledger)
    // The entry the pill speaks for: the first copy of the song with a status.
    val playingEntry = track?.paths?.firstOrNull { ledger.entries[it] != null }
    val removableNow = track?.paths.orEmpty().filter { removal.canRemove(ledger.entries[it]) }
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "hero") {
            NowHero(
                track = track,
                pill = nowPill(status),
                sweep = sweep,
                canRemove = removableNow.isNotEmpty(),
                top = contentPadding.calculateTopPadding(),
                banner = banner,
                onPill = { playingEntry?.let { sheet = it } },
                onWrongLyrics = { removal.ask(removableNow) },
            )
        }
        val ai = aiTile
        if (checks > 0 || ai != null) {
            item(key = "tiles") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    if (checks > 0) CheckTile(checks, Modifier.weight(1f)) { onOpenChecks(null) }
                    if (ai != null) AiTileCard(ai, Modifier.weight(1f), onOpenAi)
                }
            }
        }
        item(key = "library") {
            LibraryCard(counts, retrying, canRetry = connected, onRetryFailed, onOpenGroup, Modifier.padding(horizontal = ScreenPadding))
        }
        if (recent.isNotEmpty()) item(key = "recent") { RecentCarousel(recent, onSeeAll) { sheet = it } }
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
                onOpenChecks(entry.path)
            },
            onDismiss = { sheet = null },
        )
    }
}

@Composable
private fun NowHero(
    track: CurrentTrack?,
    pill: NowPill,
    sweep: SweepProgress?,
    canRemove: Boolean,
    top: Dp,
    banner: @Composable () -> Unit,
    onPill: () -> Unit,
    onWrongLyrics: () -> Unit,
) {
    val cover = nowCover(track)
    var why by remember { mutableStateOf(false) }
    Box {
        CoverBackdrop(cover, top + BACKDROP_HEIGHT)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = top + 4.dp),
        ) {
            banner()
            if (pill == NowPill.Idle) {
                EmptyState(EmptyArt.Glyph, stringResource(R.string.playing_idle), text = stringResource(R.string.playing_idle_detail))
                return@Column
            }
            PlayerChip(track)
            Crossfade(targetState = cover, animationSpec = tween(COVER_FADE_MS), label = "cover") {
                val shape = RoundedCornerShape(28.dp)
                SongCover(it, size = 208.dp, shape = shape, modifier = Modifier.shadow(16.dp, shape))
            }
            if (track != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(track.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    track.artist?.let {
                        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnimatedContent(
                    targetState = pill,
                    transitionSpec = { fadeIn(tween(PILL_MS)) togetherWith fadeOut(tween(PILL_MS)) },
                    label = "pill",
                ) { shown ->
                    // The live region is the pill itself, clickable or not, so what TalkBack hears includes its label.
                    val live = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                    when (shown) {
                        NowPill.Idle -> Unit
                        is NowPill.Busy -> StatusPill(stringResource(shown.label), tone = null, modifier = live, leading = { BrandGlyph(width = 20.dp, sweeping = true) })
                        NowPill.NotOnPhone -> StatusPill(stringResource(NowPill.NotOnPhone.label), tone = null, modifier = live, icon = NowPill.NotOnPhone.icon, onClick = { why = true })
                        NowPill.Unreadable -> StatusPill(stringResource(NowPill.Unreadable.label), tone = null, modifier = live, icon = NowPill.Unreadable.icon)
                        is NowPill.Done -> StatusPill(shown.style.status, live, onClick = onPill)
                    }
                }
                if (canRemove) {
                    LyrifyButton(stringResource(R.string.wrong_lyrics), onClick = onWrongLyrics, kind = ButtonKind.SECONDARY, icon = R.drawable.ic_flag, compact = true)
                }
            }
            sweep?.takeIf { it.total > 0 }?.let { QueueLine(it) }
        }
    }
    if (why) {
        HelpSheet(stringResource(NowPill.NotOnPhone.label), stringResource(NowPill.NotOnPhone.sentence)) { why = false }
    }
}

/**
 * Behind the top bar and the cover: the cover, blurred (Android 12 and later), fading into the surface before the
 * title, so text never sits on the image. Without a cover, or on Android 11, a soft gradient from the primary container.
 */
@Composable
private fun CoverBackdrop(cover: Any?, height: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(height)) {
        Crossfade(targetState = cover?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }, animationSpec = tween(COVER_FADE_MS), label = "backdrop") { shown ->
            if (shown != null) {
                AsyncImage(model = shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(48.dp).alpha(BACKDROP_ALPHA))
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.surface))))
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to colors.surface.copy(alpha = 0.8f),
                    0.3f to colors.surface.copy(alpha = 0.3f),
                    0.75f to colors.surface.copy(alpha = 0.55f),
                    1f to colors.surface,
                ),
            ),
        )
    }
}

/** "Playing in <app>" with the app's icon when the phone gives it, else a generic play icon. */
@Composable
private fun PlayerChip(track: CurrentTrack?) {
    val player = track?.player
    val icon = rememberAppIcon(player?.packageName)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(min = 28.dp).padding(start = 6.dp, end = 12.dp)) {
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(16.dp).clip(RoundedCornerShape(5.dp)))
            } else {
                Icon(painterResource(R.drawable.ic_play_circle), contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Text(
                if (player != null) stringResource(R.string.playing_in, player.name) else stringResource(R.string.now_playing),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The player's app icon; null when the phone does not show Lyrify that app. */
@Composable
private fun rememberAppIcon(packageName: String?): ImageBitmap? {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { 16.dp.roundToPx() }
    return remember(packageName, px) {
        packageName?.let {
            try {
                context.packageManager.getApplicationIcon(it).toBitmap(px, px).asImageBitmap()
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
}

/** The queue as a sung line and "34/120"; paused shows a pause icon, "Paused" and a grey bar. */
@Composable
private fun QueueLine(sweep: SweepProgress) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val progress by animateFloatAsState(sweep.checked.toFloat() / sweep.total.coerceAtLeast(1), label = "queue")
    val description = stringResource(if (sweep.paused) R.string.sweep_paused else R.string.sweep_progress, sweep.checked, sweep.total)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(painterResource(if (sweep.paused) R.drawable.ic_pause else R.drawable.ic_queue_music), contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
        if (sweep.paused) Text(stringResource(R.string.sweep_paused_short), style = MaterialTheme.typography.labelMedium, color = muted)
        SungBar(progress, Modifier.width(120.dp), paused = sweep.paused)
        Text(stringResource(R.string.sweep_count, sweep.checked, sweep.total), style = MaterialTheme.typography.labelMedium.tabular(), color = muted)
    }
}

/** "2 To check": opens Check these. */
@Composable
private fun CheckTile(count: Int, modifier: Modifier, onOpen: () -> Unit) {
    val tone = LocalStatusColors.current[Tone.CHECK]
    val description = pluralStringResource(R.plurals.check_card, count, count)
    LyrifyCard(modifier.semantics { contentDescription = description }, color = tone.container, contentColor = tone.onContainer, onClick = onOpen) {
        TileContent(chevron = true) {
            ToneCircle(R.drawable.ic_help_fill, Tone.CHECK)
            Column(Modifier.weight(1f)) {
                Text(formatCount(count), style = MaterialTheme.typography.titleLarge.tabular(), maxLines = 1)
                Text(stringResource(R.string.group_to_check), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/** The AI at a glance: a mini ring, a big value and a two-word label; opens the AI tab. */
@Composable
private fun AiTileCard(tile: AiTile, modifier: Modifier, onOpen: () -> Unit) {
    LyrifyCard(modifier, color = MaterialTheme.colorScheme.surfaceContainerHigh, onClick = onOpen) {
        TileContent(chevron = true) {
            AiRing(tile.ring, Centre.Glyph, size = 40.dp)
            Column(Modifier.weight(1f)) {
                Text(tileText(tile.value), style = MaterialTheme.typography.titleLarge.tabular(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(tile.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TileContent(chevron: Boolean, content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(min = 72.dp).padding(start = 12.dp, end = 8.dp)) {
        content()
        if (chevron) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

/** The Library in numbers: a bar and three stats that open Songs filtered, and the retry row while lookups failed. */
@Composable
private fun LibraryCard(
    counts: Map<LyricsStatus, Int>,
    retrying: Boolean,
    canRetry: Boolean,
    onRetryFailed: () -> Unit,
    onOpenGroup: (SongGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = groupCounts(counts)
    val total = groups.values.sum()
    val tones = LocalStatusColors.current
    LyrifyCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
                Text(formatCount(total), style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Decorative: the stats carry the numbers.
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).clearAndSetSemantics {},
            ) {
                SongGroup.entries.filter { groups.getValue(it) > 0 }.forEach { group ->
                    // A sliver stays visible however small the group.
                    Box(Modifier.weight(maxOf(groups.getValue(group).toFloat(), total * MIN_SEGMENT)).fillMaxHeight().background(tones[group.tone].accent))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(SongGroup.HAS_LYRICS, SongGroup.WAITING, SongGroup.NO_LYRICS).forEach { group ->
                    GroupStat(group, groups.getValue(group), Modifier.weight(1f)) { onOpenGroup(group) }
                }
            }
            val failed = counts[LyricsStatus.ERROR] ?: 0
            if (failed > 0) RetryRow(failed, retrying, canRetry, onRetryFailed)
        }
    }
}

@Composable
private fun GroupStat(group: SongGroup, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val label = stringResource(group.label)
    val description = stringResource(R.string.filter_count, label, formatCount(count))
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(group.icon), contentDescription = null, tint = LocalStatusColors.current[group.tone].accent, modifier = Modifier.size(18.dp))
            Text(formatCount(count), style = MaterialTheme.typography.titleLarge.tabular(), maxLines = 1)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "3 lookups failed · Retry": Lyrify retries by itself, this does it at once. */
@Composable
private fun RetryRow(failed: Int, retrying: Boolean, canRetry: Boolean, onRetryFailed: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(R.drawable.ic_sync_problem), contentDescription = null, tint = LocalStatusColors.current[Tone.ERROR].accent, modifier = Modifier.size(20.dp))
        Text(pluralStringResource(R.plurals.retry_title, failed, failed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        LyrifyButton(
            stringResource(if (retrying) R.string.retrying else R.string.retry_failed),
            onClick = onRetryFailed,
            kind = ButtonKind.GHOST,
            icon = R.drawable.ic_refresh,
            enabled = canRetry && !retrying,
            compact = true,
        )
    }
}

/** The last songs Lyrify handled, as covers with their status badge; "See all" opens Songs, recent first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentCarousel(recent: List<LedgerEntry>, onSeeAll: () -> Unit, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = ScreenPadding, end = 4.dp)) {
            Text(stringResource(R.string.recent_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            LyrifyButton(stringResource(R.string.recent_all), onClick = onSeeAll, kind = ButtonKind.GHOST, compact = true)
        }
        val openLabel = stringResource(R.string.song_open)
        HorizontalMultiBrowseCarousel(
            state = rememberCarouselState { recent.size },
            preferredItemWidth = RECENT_ITEM,
            itemSpacing = 8.dp,
            contentPadding = PaddingValues(horizontal = ScreenPadding),
            modifier = Modifier.fillMaxWidth(),
        ) { index ->
            val entry = recent[index]
            val description = listOf(entry.title, stringResource(entry.status.style.label)).joinToString(", ")
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clickable(onClickLabel = openLabel) { onOpen(entry.path) }
                    .semantics { contentDescription = description },
            ) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).maskClip(MaterialTheme.shapes.large)) {
                    SongCover(SongArt(entry.path), modifier = Modifier.fillMaxSize(), size = RECENT_ITEM, shape = RoundedCornerShape(0.dp))
                    StatusBadge(entry.status, Modifier.align(Alignment.BottomEnd).padding(6.dp), size = 22.dp)
                }
                Text(entry.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val BACKDROP_ALPHA = 0.6f
private val BACKDROP_HEIGHT = 300.dp
private const val COVER_FADE_MS = 300
private const val PILL_MS = 220
private const val RECENT_SONGS = 12
private val RECENT_ITEM = 108.dp

/** The smallest share of the Library bar a group with songs gets. */
private const val MIN_SEGMENT = 0.006f
