package app.lyrify.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.R
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.tones.ActiveDefaults
import app.lyrify.tones.AndroidSystemSounds
import app.lyrify.tones.DefaultOutcome
import app.lyrify.tones.PreviewSource
import app.lyrify.tones.SavedTone
import app.lyrify.tones.ToneDefaults
import app.lyrify.tones.ToneEdit
import app.lyrify.tones.ToneFiles
import app.lyrify.tones.ToneKind
import app.lyrify.tones.ToneListStore
import app.lyrify.tones.TonePlayer
import app.lyrify.tones.TonesState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Songs list in picker mode: a tap picks the song ([onPick]); [label] is what TalkBack says a tap does. */
@Immutable
data class SongPicker(@StringRes val label: Int, val onPick: (LedgerEntry) -> Unit)

@StringRes
fun kindLabel(kind: ToneKind): Int = when (kind) {
    ToneKind.RINGTONE -> R.string.tone_kind_ringtone
    ToneKind.ALARM -> R.string.tone_kind_alarm
    ToneKind.NOTIFICATION -> R.string.tone_kind_notification
}

@DrawableRes
fun kindIcon(kind: ToneKind): Int = when (kind) {
    ToneKind.RINGTONE -> R.drawable.ic_ring_volume
    ToneKind.ALARM -> R.drawable.ic_alarm
    ToneKind.NOTIFICATION -> R.drawable.ic_notifications
}

/** The row menu's "Set as ringtone", "Set as alarm", "Set as notification". */
@StringRes
fun setAsLabel(kind: ToneKind): Int = when (kind) {
    ToneKind.RINGTONE -> R.string.tones_set_as_ringtone
    ToneKind.ALARM -> R.string.tones_set_as_alarm
    ToneKind.NOTIFICATION -> R.string.tones_set_as_notification
}

/** The set-default sheet for tone [toneId] as a [kind] sound, at [step]. Kept across a turn of the phone. */
@Immutable
data class DefaultSheet(val toneId: Long, val kind: ToneKind, val step: Step) {
    enum class Step {
        /** Just saved: "Set as your ringtone now?" */
        ASK,

        /** Set was tapped without "Modify system settings": the one-line reason and Open settings. */
        PERMISSION,

        /** That settings screen is open; back in Lyrify, the sound is set if it was allowed. */
        WAITING,
        DONE,
        NOT_SET,

        /** No settings screen to allow it on this phone. */
        NO_SCREEN,
    }

    /** Set (or Open settings) tapped: allowed, the sound is set now; not allowed, Set shows the reason first. */
    fun tapped(defaults: ToneDefaults, tone: SavedTone): DefaultSheet = when (step) {
        Step.ASK -> if (defaults.canWrite()) copy(step = stepAfter(defaults.set(tone, kind))) else copy(step = Step.PERMISSION)
        Step.PERMISSION, Step.WAITING -> copy(step = stepAfter(defaults.set(tone, kind)))
        Step.DONE, Step.NOT_SET, Step.NO_SCREEN -> this
    }

    /** Back in Lyrify: a sheet waiting for the permission sets the sound once allowed, else shows the reason again. */
    fun resumed(defaults: ToneDefaults, tone: SavedTone): DefaultSheet = when {
        step != Step.WAITING -> this
        defaults.canWrite() -> copy(step = stepAfter(defaults.set(tone, kind)))
        else -> copy(step = Step.PERMISSION)
    }

    companion object {
        val Saver: Saver<DefaultSheet?, Any> = listSaver(
            save = { sheet -> sheet?.let { listOf(it.toneId, it.kind.name, it.step.name) }.orEmpty() },
            restore = { saved -> if (saved.isEmpty()) null else DefaultSheet(saved[0] as Long, ToneKind.valueOf(saved[1] as String), Step.valueOf(saved[2] as String)) },
        )
    }
}

/** Where the sheet goes after tapping Set. */
fun stepAfter(outcome: DefaultOutcome): DefaultSheet.Step = when (outcome) {
    DefaultOutcome.AskedPermission -> DefaultSheet.Step.WAITING
    DefaultOutcome.NoPermissionScreen -> DefaultSheet.Step.NO_SCREEN
    is DefaultOutcome.Set, is DefaultOutcome.AlarmSet -> DefaultSheet.Step.DONE
    DefaultOutcome.NotSet -> DefaultSheet.Step.NOT_SET
}

/** The sheet's one button besides its dismiss. */
enum class SheetAction(@StringRes val label: Int) {
    SET(R.string.tones_set),
    OPEN_SETTINGS(R.string.tones_permission_open),
    OPEN_SOUND_SETTINGS(R.string.tones_open_sound_settings),
    OPEN_CLOCK(R.string.tones_open_clock),
}

/** A sheet's words: a title, maybe a sentence (formatted with the tone's name), maybe a button, and its dismiss. */
data class SheetCopy(@StringRes val title: Int, @StringRes val text: Int?, val action: SheetAction?, @StringRes val dismiss: Int)

/**
 * The sheet's copy at [step] for a [kind] sound. A ringtone adds the second SIM's line (no official way sets it from an
 * app); an alarm sound says the Clock may keep its own sound per alarm (Samsung Clock does).
 */
fun sheetCopy(kind: ToneKind, step: DefaultSheet.Step): SheetCopy = when (step) {
    DefaultSheet.Step.ASK -> SheetCopy(askTitle(kind), R.string.tones_saved_as, SheetAction.SET, R.string.tones_not_now)
    DefaultSheet.Step.PERMISSION, DefaultSheet.Step.WAITING -> SheetCopy(askTitle(kind), R.string.tones_permission_why, SheetAction.OPEN_SETTINGS, R.string.tones_not_now)
    DefaultSheet.Step.DONE -> when (kind) {
        ToneKind.RINGTONE -> SheetCopy(R.string.tones_done_ringtone, R.string.tones_two_sims, SheetAction.OPEN_SOUND_SETTINGS, R.string.tones_done)
        ToneKind.ALARM -> SheetCopy(R.string.tones_done_alarm, R.string.tones_alarm_clock, SheetAction.OPEN_CLOCK, R.string.tones_done)
        ToneKind.NOTIFICATION -> SheetCopy(R.string.tones_done_notification, null, null, R.string.tones_done)
    }
    DefaultSheet.Step.NOT_SET -> SheetCopy(R.string.tones_not_set_title, R.string.tones_not_set, null, R.string.tones_done)
    DefaultSheet.Step.NO_SCREEN -> SheetCopy(R.string.tones_not_set_title, R.string.tones_no_permission_screen, SheetAction.OPEN_SOUND_SETTINGS, R.string.tones_done)
}

@StringRes
private fun askTitle(kind: ToneKind): Int = when (kind) {
    ToneKind.RINGTONE -> R.string.tones_ask_ringtone
    ToneKind.ALARM -> R.string.tones_ask_alarm
    ToneKind.NOTIFICATION -> R.string.tones_ask_notification
}

/** What the message says after Delete tone. */
@StringRes
fun deleteMessage(edit: ToneEdit): Int = when (edit) {
    is ToneEdit.Done -> R.string.tones_deleted
    ToneEdit.Gone -> R.string.tones_gone
    ToneEdit.Failed -> R.string.tones_not_deleted
}

/** What the message says after Rename; nothing when it worked (the list shows the new name). */
@StringRes
fun renameMessage(edit: ToneEdit): Int? = when (edit) {
    is ToneEdit.Done -> null
    ToneEdit.Gone -> R.string.tones_gone
    ToneEdit.Failed -> R.string.tones_not_renamed
}

/**
 * A full screen over the tabs and the navigation bar, as a dialog window drawn edge to edge; back or [onDismiss]
 * closes it. The system bars' icons follow the theme.
 */
@Composable
fun FullScreen(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val dark = LocalDarkTheme.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** The Tones tab's top bar: its name and More options (Settings). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TonesTopBar(scrollBehavior: TopAppBarScrollBehavior, backdrop: GlassBackdrop, onSettings: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    LyrifyTopBar(
        title = { Text(stringResource(R.string.tones_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        under = scrollBehavior.contentUnder(),
        backdrop = backdrop,
        actions = {
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { menu = true })
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
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
}

/**
 * The Tones tab: "Pick a song" (the Songs list as a picker, then the editor), and "Your tones", each with its preview,
 * the defaults it is (pills) and a menu: set it as a default sound, rename it, delete it. Tones deleted outside Lyrify
 * leave the list when the tab comes back. After a save, a sheet offers to set the tone as the phone's sound. Messages go
 * to the app's [toasts], which show them above the dock or in the open sheet.
 */
@Composable
fun TonesScreen(ledger: LedgerState, contentPadding: PaddingValues, toasts: ToastState) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val files = remember(context) { ToneFiles.of(context.applicationContext) }
    val defaults = remember(context) { ToneDefaults(AndroidSystemSounds(context)) }
    val player = remember(context, scope) { TonePlayer(context, scope) }
    val state by remember(context) { ToneListStore.of(context).state }.collectAsStateWithLifecycle(TonesState())
    val playing by player.playing.collectAsStateWithLifecycle()
    var active by remember { mutableStateOf(ActiveDefaults(emptyMap())) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable(stateSaver = DefaultSheet.Saver) { mutableStateOf<DefaultSheet?>(null) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val tones = remember(state) { state.newestFirst() }
    fun refreshDefaults() {
        scope.launch { active = withContext(Dispatchers.IO) { defaults.active() } }
    }
    fun say(@StringRes message: Int) {
        scope.launch { toasts.say(resources, message) }
    }

    LifecycleResumeEffect(Unit) {
        val job = scope.launch {
            files.prune()
            active = withContext(Dispatchers.IO) { defaults.active() }
        }
        sheet?.let { shown -> state.tones[shown.toneId]?.let { sheet = shown.resumed(defaults, it) } }
        onPauseOrDispose {
            job.cancel()
            player.stop()
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "pick") {
            LyrifyButton(
                stringResource(R.string.tones_pick_song),
                onClick = { picking = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenPadding),
                icon = R.drawable.ic_library_music,
            )
        }
        if (tones.isEmpty()) {
            item(key = "empty") { EmptyState(EmptyArt.Glyph, stringResource(R.string.tones_empty_title), text = stringResource(R.string.tones_empty)) }
        } else {
            item(key = "title") { SectionTitle(stringResource(R.string.tones_yours), Modifier.padding(start = ScreenPadding, end = ScreenPadding, top = 24.dp, bottom = 8.dp)) }
            items(tones, key = { it.id }) { tone ->
                val preview = PreviewSource.Tone(tone.id)
                ToneRow(
                    tone = tone,
                    pills = active.of(tone),
                    playing = playing?.key == preview.key,
                    onPlay = { if (playing?.key == preview.key) player.stop() else player.play(preview) { say(R.string.tones_not_played) } },
                    onSetAs = { kind ->
                        sheet = DefaultSheet(tone.id, kind, DefaultSheet.Step.ASK).tapped(defaults, tone)
                        refreshDefaults()
                    },
                    onRename = { renaming = tone.id },
                    onDelete = { deleting = tone.id },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }

    if (picking) {
        TonePicker(ledger, onDismiss = { picking = false }) {
            picking = false
            editing = it.path
        }
    }
    editing?.let { ledger.entries[it] }?.let { song ->
        ToneEditor(
            song = song,
            files = files,
            player = player,
            onSaved = { tone ->
                editing = null
                sheet = DefaultSheet(tone.id, tone.kind, DefaultSheet.Step.ASK)
            },
            onClose = { editing = null },
        )
    }
    // A tone just saved shows once the list has it.
    sheet?.let { shown -> state.tones[shown.toneId]?.let { shown to it } }?.let { (shown, tone) ->
        DefaultSheetView(
            sheet = shown,
            tone = tone,
            onAction = { action ->
                when (action) {
                    SheetAction.SET, SheetAction.OPEN_SETTINGS -> {
                        sheet = shown.tapped(defaults, tone)
                        refreshDefaults()
                    }
                    SheetAction.OPEN_SOUND_SETTINGS -> defaults.openSoundSettings()
                    // Shows in this sheet, above its buttons.
                    SheetAction.OPEN_CLOCK -> if (!defaults.openClock()) say(R.string.tones_clock_not_opened)
                }
            },
            onDismiss = { sheet = null },
        )
    }
    renaming?.let { state.tones[it] }?.let { tone ->
        RenameDialog(tone, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch { renameMessage(files.rename(tone, name))?.let(::say) }
        }
    }
    deleting?.let { state.tones[it] }?.let { tone ->
        DeleteDialog(onDismiss = { deleting = null }) {
            deleting = null
            player.stop()
            scope.launch { say(deleteMessage(files.delete(tone))) }
        }
    }
}

/**
 * A tone: the song's cover, the tone's name, "Ringtone · 0:30 from 1:12", a pill per default sound it is, the preview
 * button and the menu. The texts are one TalkBack stop.
 */
@Composable
private fun ToneRow(
    tone: SavedTone,
    pills: Set<ToneKind>,
    playing: Boolean,
    onPlay: () -> Unit,
    onSetAs: (ToneKind) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = ScreenPadding, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        SongCover(SongArt(tone.songPath))
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tone.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.tones_row_summary, stringResource(kindLabel(tone.kind)), toneTime(tone.lengthMs), toneTime(tone.startMs)),
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pills.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    ToneKind.entries.filter { it in pills }.forEach { DefaultPill(it) }
                }
            }
        }
        ChromeIconButton(
            if (playing) R.drawable.ic_pause_circle else R.drawable.ic_play_circle,
            stringResource(if (playing) R.string.tones_stop_cd else R.string.tones_play_cd, tone.name),
            onPlay,
            glass = false,
            iconSize = 28.dp,
            tint = if (playing) LocalBrandColors.current.highlight else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            ChromeIconButton(
                R.drawable.ic_more_horiz,
                stringResource(R.string.tones_more_cd, tone.name),
                { menu = true },
                glass = false,
                iconSize = 24.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                ToneKind.entries.forEach { kind ->
                    MenuRow(
                        stringResource(setAsLabel(kind)),
                        kindIcon(kind),
                        onClick = {
                            menu = false
                            onSetAs(kind)
                        },
                    )
                }
                MenuRow(
                    stringResource(R.string.tones_rename),
                    R.drawable.ic_edit,
                    onClick = {
                        menu = false
                        onRename()
                    },
                )
                MenuRow(
                    stringResource(R.string.tones_delete),
                    R.drawable.ic_delete,
                    onClick = {
                        menu = false
                        onDelete()
                    },
                    destructive = true,
                )
            }
        }
    }
}

/** "Ringtone", "Alarm" or "Notification": this tone is the phone's default sound of that kind. */
@Composable
private fun DefaultPill(kind: ToneKind) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer, shape = CircleShape) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
            Icon(painterResource(kindIcon(kind)), contentDescription = null, modifier = Modifier.size(14.dp))
            Text(stringResource(kindLabel(kind)), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The song picker: the Songs list with its search, filters and covers, full screen; a tap picks the song. */
@Composable
private fun TonePicker(ledger: LedgerState, onDismiss: () -> Unit, onPick: (LedgerEntry) -> Unit) {
    FullScreen(onDismiss) {
        var query by rememberSaveable(stateSaver = SongQuery.Saver) { mutableStateOf(SongQuery()) }
        val focus = remember { FocusRequester() }
        // The song sheet never opens in picker mode, so nothing is ever removed from here.
        val removal = remember { RemovalState(leftOnDisk = { emptySet() }) {} }
        Column(Modifier.fillMaxSize()) {
            SongSearchBar(
                query,
                { query = it },
                focus,
                onSettings = null,
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.tones_back), onDismiss) },
            )
            Box(Modifier.weight(1f)) {
                SongsScreen(
                    ledger = ledger,
                    query = query,
                    onQuery = { query = it },
                    timing = PlainTiming.AI_OFF,
                    removal = removal,
                    retrying = false,
                    connected = true,
                    reselect = 0,
                    onRetryFailed = {},
                    onPickLyrics = {},
                    banner = {},
                    picker = SongPicker(R.string.tones_choose_song, onPick),
                )
            }
            // Messages take their own room under the list, above the keyboard.
            ToastSlot(Modifier.navigationBarsPadding().imePadding().padding(horizontal = ScreenPadding), padding = PaddingValues(top = 8.dp, bottom = 12.dp))
        }
    }
}

/** The set-default sheet: its title, its sentence with the tone's name, and its dismiss and button side by side. */
@Composable
private fun DefaultSheetView(sheet: DefaultSheet, tone: SavedTone, onAction: (SheetAction) -> Unit, onDismiss: () -> Unit) {
    val copy = sheetCopy(sheet.kind, sheet.step)
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        footer = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                LyrifyButton(stringResource(copy.dismiss), onClick = onDismiss, modifier = Modifier.weight(1f), kind = ButtonKind.SECONDARY)
                copy.action?.let { action ->
                    LyrifyButton(
                        stringResource(action.label),
                        onClick = { onAction(action) },
                        modifier = Modifier.weight(1f),
                        kind = if (action == SheetAction.SET) ButtonKind.PRIMARY else ButtonKind.SECONDARY,
                    )
                }
            }
        },
    ) {
        Text(stringResource(copy.title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        copy.text?.let { Text(stringResource(it, tone.name), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RenameDialog(tone: SavedTone, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable(tone.id) { mutableStateOf(tone.name) }
    ConfirmDialog(
        title = stringResource(R.string.tones_rename_title),
        confirm = stringResource(R.string.tones_rename_confirm),
        dismiss = stringResource(R.string.tones_cancel),
        onConfirm = { onRename(name) },
        onDismiss = onDismiss,
        icon = R.drawable.ic_edit,
        destructive = false,
        confirmEnabled = name.isNotBlank(),
    ) {
        LyrifyField(value = name, onValueChange = { name = it }, label = stringResource(R.string.tones_rename_label), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DeleteDialog(onDismiss: () -> Unit, onDelete: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.tones_delete_title),
        text = stringResource(R.string.tones_delete_text),
        confirm = stringResource(R.string.tones_delete_confirm),
        dismiss = stringResource(R.string.tones_cancel),
        onConfirm = onDelete,
        onDismiss = onDismiss,
    )
}
