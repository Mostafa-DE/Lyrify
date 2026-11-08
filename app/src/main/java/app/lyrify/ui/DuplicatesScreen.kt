package app.lyrify.ui

import android.app.Activity
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.dupes.AudioFile
import app.lyrify.dupes.Confidence
import app.lyrify.dupes.Confirmation
import app.lyrify.dupes.DuplicateGroup
import app.lyrify.dupes.Duplicates
import app.lyrify.dupes.DuplicatesState
import app.lyrify.dupes.MediaStoreTrash
import app.lyrify.dupes.Restored
import app.lyrify.dupes.ScanResults
import app.lyrify.dupes.ScanStatus
import app.lyrify.dupes.TrashStep
import app.lyrify.dupes.Trashed
import app.lyrify.dupes.TrashedSong
import app.lyrify.dupes.duplicates
import app.lyrify.lyrics.TrackNames
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale

// What the tab shows

/** The Duplicates tab's one view of its engine's state. */
sealed interface DupesView {
    /** The stored results are being read: nothing shows yet. */
    data object Loading : DupesView

    /** Before the first scan: "Find duplicates". */
    data object FirstScan : DupesView

    /** The ring and "Scanning 412/1,358"; [total] is 0 while the list of songs is read. */
    data class Scanning(val done: Int, val total: Int) : DupesView {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    /** The songs could not be read, and there are no results to show. */
    data object Failed : DupesView

    /** The last scan's [results] and the songs in the phone's trash; [failed]: a scan since could not read the songs. */
    data class Results(val results: ScanResults, val trashed: List<TrashedSong>, val failed: Boolean) : DupesView
}

fun dupesView(state: DuplicatesState): DupesView {
    val scan = state.scan
    val results = state.results
    return when {
        !state.loaded -> DupesView.Loading
        scan is ScanStatus.Running -> DupesView.Scanning(scan.done, scan.total)
        results == null -> if (scan == ScanStatus.Failed) DupesView.Failed else DupesView.FirstScan
        else -> DupesView.Results(results, state.trashed, failed = scan == ScanStatus.Failed)
    }
}

/** When the last scan was, for "Scanned today 18:40". */
enum class ScannedDay(@StringRes val label: Int) {
    TODAY(R.string.dupes_scanned_today),
    YESTERDAY(R.string.dupes_scanned_yesterday),
    EARLIER(R.string.dupes_scanned_on),
}

fun scannedDay(scannedAt: Long, now: Long, zone: ZoneId): ScannedDay {
    val day = Instant.ofEpochMilli(scannedAt).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> ScannedDay.TODAY
        today.minusDays(1) -> ScannedDay.YESTERDAY
        else -> ScannedDay.EARLIER
    }
}

/** The last quarter a scan of [total] files passed at [done]: 25, 50 or 75 (percent), announced once each; null before. */
fun scanMilestone(done: Int, total: Int): Int? {
    if (total <= 0) return null
    val quarters = (done.toLong() * QUARTERS / total).toInt()
    return (quarters * PERCENT_PER_QUARTER).takeIf { quarters in 1 until QUARTERS }
}

@StringRes
fun sectionTitle(confidence: Confidence): Int = when (confidence) {
    Confidence.IDENTICAL -> R.string.dupes_section_identical
    Confidence.SAME_SONG -> R.string.dupes_section_same
    Confidence.MAYBE -> R.string.dupes_section_maybe
}

/** The last words of a group card's TalkBack description. */
@StringRes
fun levelDescription(confidence: Confidence): Int = when (confidence) {
    Confidence.IDENTICAL -> R.string.dupes_level_identical
    Confidence.SAME_SONG -> R.string.dupes_level_same
    Confidence.MAYBE -> R.string.dupes_level_maybe
}

/** The group sheet's sentence: why these files are together, and for a maybe, to check first. */
@StringRes
fun groupSentence(confidence: Confidence): Int = when (confidence) {
    Confidence.IDENTICAL -> R.string.dupes_sheet_identical
    Confidence.SAME_SONG -> R.string.dupes_sheet_same
    Confidence.MAYBE -> R.string.dupes_sheet_maybe
}

/** Where a file is, as the user knows it: its folder and name ("kjb/03 Song.mp3"), or the name alone in the top folder. */
fun fileLocation(file: AudioFile): String {
    val folder = file.relativePath?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    return if (folder != null) "$folder/${file.displayName}" else file.displayName
}

/** The file's format as a short name: its extension ("MP3", "FLAC"), else the MIME type's ("audio/ogg": "OGG"). */
fun fileFormat(file: AudioFile): String =
    (file.displayName.substringAfterLast('.', "").ifEmpty { null } ?: file.mimeType?.substringAfterLast('/').orEmpty()).uppercase(Locale.ROOT)

/** The bitrate MediaStore knows, in whole kbps; null when it has none. */
fun fileKbps(file: AudioFile): Int? = file.bitrate?.takeIf { it > 0 }?.let { ((it + BPS_PER_KBPS / 2) / BPS_PER_KBPS).toInt() }

/** "Move to trash" with [checked] of a group's [copies] checked: at least one, and never every copy. */
fun canMove(checked: Int, copies: Int): Boolean = checked in 1 until copies

/** Every copy is checked: "Keep at least one copy." shows, and "Move to trash" waits. */
fun mustKeepOne(checked: Int, copies: Int): Boolean = copies > 0 && checked >= copies

// The tab

/** The app's Duplicates engine, for the screens. */
@Composable
fun rememberDuplicates(): Duplicates {
    val context = LocalContext.current
    return remember(context) { duplicates(context) }
}

/** The dock's badge: groups not opened yet. */
@Composable
fun duplicatesBadge(): Int {
    val state by rememberDuplicates().state.collectAsStateWithLifecycle()
    return state.badge
}

/**
 * Opens the phone's own trash dialog for MediaStore ids (to the trash, or with trash false back out of it) and hands the
 * answer to the engine, whichever of its dialogs was open; the result shows in [toasts].
 */
@Composable
fun rememberPhoneTrash(engine: Duplicates, toasts: ToastState): (List<Long>, Boolean) -> Unit {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val approved = result.resultCode == Activity.RESULT_OK
        scope.launch {
            if (engine.state.value.trash is TrashStep.Restoring) {
                if (engine.restoreAnswered(approved) == Restored.Back) toasts.say(resources, R.string.dupes_restored)
            } else {
                val moved = engine.systemAnswered(approved) as? Trashed.Moved
                if (moved != null && moved.count > 0) toasts.show(messageToast(R.plurals.dupes_moved, resources.getQuantityString(R.plurals.dupes_moved, moved.count, moved.count)))
            }
        }
    }
    return remember(launcher, engine) {
        { ids, trash ->
            when (val request = MediaStoreTrash(context.contentResolver).request(ids, trash)) {
                is MediaStoreTrash.Request.Ready -> launcher.launch(IntentSenderRequest.Builder(request.intent).build())
                is MediaStoreTrash.Request.Unavailable -> scope.launch {
                    if (trash) engine.systemAnswered(approved = false) else engine.restoreAnswered(approved = false)
                    toasts.say(resources, R.string.dupes_trash_failed)
                }
            }
        }
    }
}

/** The Duplicates tab's top bar: its name and More options (Scan again, Trashed songs, Settings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesTopBar(scrollBehavior: TopAppBarScrollBehavior, backdrop: GlassBackdrop, onSettings: () -> Unit) {
    val engine = rememberDuplicates()
    val state by engine.state.collectAsStateWithLifecycle()
    val phoneTrash = rememberPhoneTrash(engine, LocalToasts.current)
    var menu by remember { mutableStateOf(false) }
    var trashed by rememberSaveable { mutableStateOf(false) }
    LyrifyTopBar(
        title = { Text(stringResource(R.string.dupes_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        under = scrollBehavior.contentUnder(),
        backdrop = backdrop,
        actions = {
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { menu = true })
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                    if (state.results != null && state.scan !is ScanStatus.Running) {
                        MenuRow(
                            stringResource(R.string.dupes_scan_again),
                            R.drawable.ic_refresh,
                            onClick = {
                                menu = false
                                engine.scan()
                            },
                        )
                    }
                    if (state.trashed.isNotEmpty()) {
                        MenuRow(
                            stringResource(R.string.dupes_menu_trashed),
                            R.drawable.ic_restore_from_trash,
                            onClick = {
                                menu = false
                                trashed = true
                            },
                        )
                    }
                    MenuRow(
                        stringResource(R.string.menu_settings),
                        R.drawable.ic_settings,
                        onClick = {
                            menu = false
                            onSettings()
                        },
                    )
                }
            }
        },
    )
    if (trashed) {
        TrashedSheet(state.trashed, onRestore = { song -> engine.restore(song).takeIf { it.isNotEmpty() }?.let { phoneTrash(it, false) } }) { trashed = false }
    }
}

/**
 * The Duplicates tab: before the first scan, the scan's ring, or the groups found by confidence and the songs in the
 * phone's trash. A group opens its sheet; moving copies to the trash asks twice, Lyrify's dialog and then the phone's.
 */
@Composable
fun DuplicatesScreen(contentPadding: PaddingValues, toasts: ToastState) {
    val engine = rememberDuplicates()
    val state by engine.state.collectAsStateWithLifecycle()
    val phoneTrash = rememberPhoneTrash(engine, toasts)
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(engine) { engine.refreshTrashed() }

    Box(Modifier.fillMaxSize()) {
        when (val view = dupesView(state)) {
            DupesView.Loading -> Unit
            DupesView.FirstScan -> CenteredState(contentPadding) {
                EmptyState(EmptyArt.Glyph, stringResource(R.string.dupes_empty_title), text = stringResource(R.string.dupes_empty), action = stringResource(R.string.dupes_scan), onAction = engine::scan)
            }
            is DupesView.Scanning -> CenteredState(contentPadding) { ScanningView(view, engine::cancelScan) }
            DupesView.Failed -> CenteredState(contentPadding) {
                EmptyState(
                    EmptyArt.Icon(R.drawable.ic_error, Tone.ERROR),
                    stringResource(R.string.dupes_failed_title),
                    text = stringResource(R.string.dupes_failed),
                    action = stringResource(R.string.dupes_try_again),
                    onAction = engine::scan,
                )
            }
            is DupesView.Results -> ResultsList(
                view = view,
                contentPadding = contentPadding,
                onScan = engine::scan,
                onOpen = { group ->
                    engine.opened(group)
                    open = group.key
                },
                onRestore = { song -> engine.restore(song).takeIf { it.isNotEmpty() }?.let { phoneTrash(it, false) } },
            )
        }
        ScanAnnouncer(state)
    }

    val group = open?.let { key -> state.results?.groups?.firstOrNull { it.key == key } }
    // A group gone meanwhile (moved to the trash, rescanned): its sheet closes.
    LaunchedEffect(open, group == null, state.loaded) { if (group == null && state.loaded) open = null }
    group?.let {
        GroupSheet(
            group = it,
            stale = it.key in state.stale,
            onMove = { ids -> engine.askTrash(it, ids) },
            onNotDuplicates = {
                engine.notDuplicates(it)
                open = null
            },
            onDismiss = { open = null },
        )
    }
    (state.trash as? TrashStep.Confirm)?.let { confirm ->
        TrashDialog(
            count = confirm.files.size,
            onCancel = engine::cancelTrash,
            onConfirm = {
                // The group shows again with what is left; the message shows above the dock.
                open = null
                scope.launch {
                    when (val confirmation = engine.confirmTrash()) {
                        is Confirmation.Ask -> phoneTrash(confirmation.ids, true)
                        Confirmation.Changed -> toasts.say(resources, R.string.dupes_changed)
                        Confirmation.Unavailable -> toasts.say(resources, R.string.dupes_trash_failed)
                        Confirmation.NotAsked -> Unit
                    }
                }
            },
        )
    }
}

/** A state in the middle of the tab, scrolling when the phone's text is large. */
@Composable
private fun CenteredState(contentPadding: PaddingValues, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()),
    ) { content() }
}

/** The AI's ring, filling as files are looked at, "Scanning 412/1,358" and Cancel. */
@Composable
private fun ScanningView(view: DupesView.Scanning, onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 32.dp),
    ) {
        AiRing(Ring(view.fraction, RingColor.HIGHLIGHT), Centre.Value(CentreValue.Percent((view.fraction * 100).toInt())), size = 200.dp)
        Text(
            if (view.total > 0) stringResource(R.string.dupes_scanning, formatCount(view.done), formatCount(view.total)) else stringResource(R.string.dupes_scanning_start),
            style = MaterialTheme.typography.titleMedium.tabular(),
            textAlign = TextAlign.Center,
        )
        LyrifyButton(stringResource(R.string.dupes_cancel), onClick = onCancel, kind = ButtonKind.SECONDARY, compact = true)
    }
}

/**
 * Says the scan's progress to TalkBack at each quarter and once it is done, without a word for every file: a polite live
 * region that only changes at those moments.
 */
@Composable
private fun ScanAnnouncer(state: DuplicatesState) {
    val scan = state.scan
    var wasRunning by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    val milestone = (scan as? ScanStatus.Running)?.let { scanMilestone(it.done, it.total) }
    val groups = state.results?.groups?.size ?: 0
    val doneText = if (groups > 0) pluralStringResource(R.plurals.dupes_scan_done, groups, groups) else stringResource(R.string.dupes_scan_done_none)
    LaunchedEffect(scan is ScanStatus.Running) {
        val running = scan is ScanStatus.Running
        if (wasRunning && !running && scan == ScanStatus.Idle) done = doneText
        if (running) done = null
        wasRunning = running
    }
    val text = milestone?.let { stringResource(R.string.dupes_scan_milestone, it) } ?: done ?: return
    Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = text })
}

@Composable
private fun ResultsList(
    view: DupesView.Results,
    contentPadding: PaddingValues,
    onScan: () -> Unit,
    onOpen: (DuplicateGroup) -> Unit,
    onRestore: (TrashedSong) -> Unit,
) {
    val results = view.results
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item(key = "summary") { Summary(results, view.failed, onScan) }
        if (results.groups.isEmpty()) {
            item(key = "none") {
                EmptyState(
                    EmptyArt.Icon(R.drawable.ic_check_circle, Tone.SYNCED),
                    stringResource(R.string.dupes_none_title),
                    text = stringResource(R.string.dupes_none),
                    action = stringResource(R.string.dupes_scan_again),
                    onAction = onScan,
                )
            }
        }
        Confidence.entries.forEach { confidence ->
            val groups = results.section(confidence)
            if (groups.isNotEmpty()) {
                item(key = "section $confidence") { SectionHeader(stringResource(sectionTitle(confidence)), groups.size) }
                items(groups, key = { it.key }) { group -> GroupCard(group, Modifier.animateItem()) { onOpen(group) } }
            }
        }
        if (view.trashed.isNotEmpty()) {
            item(key = "section trashed") { SectionHeader(stringResource(R.string.dupes_section_trashed), view.trashed.size) }
            items(view.trashed, key = { "trashed ${it.file.id}" }) { song -> TrashedRow(song, Modifier.animateItem()) { onRestore(song) } }
        }
    }
}

/** "12 groups · 1.4 GB" and "Scanned today 18:40"; above them, when the last scan failed, a banner to try again. */
@Composable
private fun Summary(results: ScanResults, failed: Boolean, onScan: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (failed) {
            Banner(
                R.drawable.ic_warning,
                stringResource(R.string.dupes_failed_title),
                stringResource(R.string.dupes_try_again),
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
                onAction = onScan,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (results.groups.isNotEmpty()) {
            val groups = pluralStringResource(R.plurals.dupes_groups, results.groups.size, results.groups.size)
            Text(
                stringResource(R.string.dupes_summary, groups, Formatter.formatShortFileSize(context, results.extraBytes)),
                style = MaterialTheme.typography.titleLarge.tabular(),
            )
        }
        val day = scannedDay(results.scannedAt, System.currentTimeMillis(), ZoneId.systemDefault())
        val time = if (day == ScannedDay.EARLIER) {
            DateUtils.formatDateTime(context, results.scannedAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
        } else {
            DateFormat.getTimeFormat(context).format(Date(results.scannedAt))
        }
        Text(stringResource(day.label, time), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A section's name and its count, a heading for TalkBack. */
@Composable
private fun SectionHeader(title: String, count: Int) {
    SectionTitle(
        stringResource(R.string.dupes_section, title, formatCount(count)),
        Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp, bottom = 8.dp),
    )
}

/** One TalkBack stop: "title, artist, 2 copies, identical"; the cover is decorative. */
@Composable
private fun GroupCard(group: DuplicateGroup, modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val keep = group.keep
    val artist = keep.artist?.takeIf { TrackNames.cleanArtist(it) != null } ?: stringResource(R.string.check_no_artist)
    val copies = pluralStringResource(R.plurals.dupes_copies, group.copies, group.copies, Formatter.formatShortFileSize(context, group.extraBytes))
    val description = stringResource(
        R.string.dupes_group_cd,
        keep.title,
        artist,
        pluralStringResource(R.plurals.dupes_copies_cd, group.copies, group.copies),
        stringResource(levelDescription(group.confidence)),
    )
    LyrifyCard(
        modifier.fillMaxWidth().padding(horizontal = ScreenPadding, vertical = 4.dp).semantics { contentDescription = description },
        onClick = onOpen,
        onClickLabel = stringResource(R.string.dupes_open_group),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.heightIn(min = 72.dp).padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            // Copies at a glance: a second cover tile behind the first, turned a little.
            Box {
                Box(
                    Modifier
                        .offset(x = 6.dp)
                        .size(56.dp)
                        .graphicsLayer { rotationZ = 6f }
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
                )
                SongCover(SongArt(keep.path), size = 56.dp)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(keep.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(copies, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A song Lyrify moved to the phone's trash: its title, where it was and until when it can come back, and Restore. */
@Composable
fun TrashedRow(song: TrashedSong, modifier: Modifier = Modifier, onRestore: () -> Unit) {
    val context = LocalContext.current
    val until = DateUtils.formatDateTime(context, song.expiresAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    val restore = stringResource(R.string.dupes_restore_cd, song.file.title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = ScreenPadding, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        SongCover(null, size = 48.dp)
        Column(Modifier.weight(1f)) {
            Text(song.file.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.dupes_trashed_facts, fileLocation(song.file), until),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        LyrifyButton(stringResource(R.string.dupes_restore), onClick = onRestore, modifier = Modifier.semantics { contentDescription = restore }, kind = ButtonKind.GHOST, compact = true)
    }
}

private const val QUARTERS = 4
private const val PERCENT_PER_QUARTER = 25
private const val BPS_PER_KBPS = 1_000L
