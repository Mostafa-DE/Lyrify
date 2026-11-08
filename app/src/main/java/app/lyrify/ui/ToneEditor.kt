package app.lyrify.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyrify.LOG_TAG
import app.lyrify.R
import app.lyrify.ai.MixDecoder
import app.lyrify.data.LedgerEntry
import app.lyrify.lyrics.LrcLine
import app.lyrify.lyrics.TrackNames
import app.lyrify.tones.Peaks
import app.lyrify.tones.PeaksCache
import app.lyrify.tones.PreviewCommand
import app.lyrify.tones.PreviewSource
import app.lyrify.tones.PreviewState
import app.lyrify.tones.PreviewStep
import app.lyrify.tones.SaveFailure
import app.lyrify.tones.SaveResult
import app.lyrify.tones.SavedTone
import app.lyrify.tones.ToneAudio
import app.lyrify.tones.ToneFiles
import app.lyrify.tones.ToneKind
import app.lyrify.tones.ToneLyrics
import app.lyrify.tones.TonePlayer
import app.lyrify.tones.ToneRequest
import app.lyrify.tones.ToneWindow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * What the editor holds for a song of [songDurationMs]: the start, the length (a chip, or the kind's default until one
 * is picked), "End of line", the fades and the kind. Only the first [ToneWindow.MAX_SOURCE_MS] of a longer song can be
 * used ([limited]). [window] is what is previewed and saved.
 */
@Immutable
data class ToneDraft(
    val songDurationMs: Long,
    val startMs: Long = 0,
    val lengthMs: Long = ToneKind.RINGTONE.defaultLengthMs,
    val lengthPicked: Boolean = false,
    val lineEnd: Boolean = false,
    val fromLyric: Boolean = false,
    val fadeIn: Boolean = true,
    val fadeOut: Boolean = true,
    val kind: ToneKind = ToneKind.RINGTONE,
) {
    val durationMs: Long get() = ToneWindow.usableDurationMs(songDurationMs)
    val limited: Boolean get() = songDurationMs > durationMs
    val fadeInMs: Long get() = if (fadeIn) FADE_MS else 0
    val fadeOutMs: Long get() = if (fadeOut) FADE_MS else 0

    fun window(lines: List<LrcLine>): ToneWindow =
        (if (lineEnd) ToneWindow.toLineEnd(startMs, lengthMs, lines, durationMs) else null) ?: ToneWindow.from(startMs, lengthMs, durationMs)

    /** "End of line" is offered once a lyric line set the start and a line ends in reach. */
    fun lineEndOffered(lines: List<LrcLine>): Boolean = fromLyric && ToneWindow.toLineEnd(startMs, lengthMs, lines, durationMs) != null

    /** The kind sets the length too, until the user picked one. */
    fun withKind(kind: ToneKind): ToneDraft = copy(kind = kind, lengthMs = if (lengthPicked) lengthMs else kind.defaultLengthMs)

    fun withLength(ms: Long): ToneDraft = copy(lengthMs = ms, lengthPicked = true, lineEnd = false)

    fun withLineEnd(): ToneDraft = copy(lineEnd = true)

    /** The start set by a lyric line ([fromLyric]), the start handle or TalkBack; the length shortens near the end. */
    fun startingAt(ms: Long, fromLyric: Boolean = false): ToneDraft =
        copy(startMs = ToneWindow.from(ms, lengthMs, durationMs).startMs, fromLyric = fromLyric, lineEnd = lineEnd && fromLyric)

    /** The whole window dragged: its length is kept and it moves to fit. */
    fun movedTo(ms: Long): ToneDraft = copy(startMs = ToneWindow.moved(ms, lengthMs, durationMs).startMs, fromLyric = false, lineEnd = false)

    fun nudged(deltaMs: Long): ToneDraft = startingAt(startMs + deltaMs)

    fun request(song: LedgerEntry, lines: List<LrcLine>): ToneRequest = ToneRequest(
        songPath = song.path,
        songTitle = TrackNames.cleanTitle(song.title),
        window = window(lines),
        fadeInMs = fadeInMs,
        fadeOutMs = fadeOutMs,
        kind = kind,
    )

    companion object {
        /** Each fade, when on. */
        const val FADE_MS: Long = 1_500

        /** A turn of the phone keeps the draft. */
        val Saver: Saver<ToneDraft, Any> = listSaver(
            save = { listOf(it.songDurationMs, it.startMs, it.lengthMs, it.lengthPicked, it.lineEnd, it.fromLyric, it.fadeIn, it.fadeOut, it.kind.name) },
            restore = {
                ToneDraft(
                    songDurationMs = it[0] as Long,
                    startMs = it[1] as Long,
                    lengthMs = it[2] as Long,
                    lengthPicked = it[3] as Boolean,
                    lineEnd = it[4] as Boolean,
                    fromLyric = it[5] as Boolean,
                    fadeIn = it[6] as Boolean,
                    fadeOut = it[7] as Boolean,
                    kind = ToneKind.valueOf(it[8] as String),
                )
            },
        )
    }
}

/** A time in a song as "1:12". */
fun toneTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Where a save is: kept by the process, so turning the phone neither stops it nor loses its result. */
sealed interface SaveState {
    data object Idle : SaveState

    data class Saving(val progress: Float) : SaveState

    data class Finished(val result: SaveResult) : SaveState
}

/** The one tone save that can run at a time, outside any screen's lifetime; cancelling leaves no file. */
object ToneSaving {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val current = MutableStateFlow<SaveState>(SaveState.Idle)
    private var job: Job? = null

    val state: StateFlow<SaveState> = current.asStateFlow()

    fun start(files: ToneFiles, request: ToneRequest) {
        if (current.value is SaveState.Saving) return
        current.value = SaveState.Saving(0f)
        job = scope.launch {
            val result = files.save(request) { current.value = SaveState.Saving(it) }
            current.value = SaveState.Finished(result)
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        current.value = SaveState.Idle
    }

    /** The screen showed the result. */
    fun consume() {
        if (current.value is SaveState.Finished) current.value = SaveState.Idle
    }
}

/** What the message says when a save failed. */
fun saveFailureMessage(failure: SaveFailure): Int = when (failure) {
    SaveFailure.SONG_UNREADABLE -> R.string.tones_song_unreadable
    SaveFailure.NOT_SAVED -> R.string.tones_not_saved
}

/**
 * The editor, full screen over the tabs: the song, its waveform with the tone's part (drag it to move it, drag its
 * start to fine-tune, TalkBack actions a second either way), "Start at a lyric", the length chips, the fades, the
 * preview, the kind and Save tone. While the preview plays, a changed part plays on from its new start ([PreviewState]).
 * A saved tone goes to [onSaved]; leaving stops the preview and cancels a save.
 */
@Composable
fun ToneEditor(song: LedgerEntry, files: ToneFiles, player: TonePlayer, onSaved: (SavedTone) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val toasts = LocalToasts.current
    var draft by rememberSaveable(song.path, stateSaver = ToneDraft.Saver) { mutableStateOf(ToneDraft(songDurationMs = song.durationMs)) }
    val lines by produceState(emptyList<LrcLine>(), song.path) { value = withContext(Dispatchers.IO) { ToneLyrics.read(song.path) } }
    val peaks by produceState<FloatArray?>(null, song.path) { value = loadPeaks(context, song.path, draft.durationMs) }
    val saving by ToneSaving.state.collectAsStateWithLifecycle()
    val playing by player.playing.collectAsStateWithLifecycle()
    val window = draft.window(lines)
    val preview = PreviewSource.Window(song.path, window, draft.fadeInMs, draft.fadeOutMs)
    val previewing = playing?.takeIf { it.key == preview.key }
    var previewState by remember { mutableStateOf(PreviewState()) }
    var dragging by remember { mutableStateOf(false) }
    fun follow(step: PreviewStep) {
        previewState = step.state
        when (val command = step.command) {
            is PreviewCommand.Play -> player.play(command.source) { scope.launch { toasts.say(resources, R.string.tones_not_played) } }
            is PreviewCommand.Jump -> player.jump(command.source)
            PreviewCommand.Stop -> player.stop()
            null -> Unit
        }
    }
    val close = {
        if (saving is SaveState.Saving) ToneSaving.cancel()
        player.stop()
        onClose()
    }

    // While the preview plays, a changed part plays on from its new start: during a drag every 150 ms, and where it is let go.
    LaunchedEffect(preview, dragging) { follow(previewState.onPartChanged(preview, SystemClock.uptimeMillis(), dragging)) }
    // The player stopped by itself: the part's end, another app's sound, a file it could not play.
    LaunchedEffect(previewing == null) { if (previewing == null) follow(previewState.onEnded()) }
    // Opening the editor stops a tone's preview from the list; leaving it stops this one.
    DisposableEffect(Unit) {
        player.stop()
        onDispose { player.stop() }
    }
    LaunchedEffect(saving) {
        when (val shown = saving) {
            is SaveState.Finished -> {
                ToneSaving.consume()
                when (val result = shown.result) {
                    is SaveResult.Saved -> onSaved(result.tone)
                    is SaveResult.Failed -> toasts.say(resources, saveFailureMessage(result.reason))
                }
            }
            else -> Unit
        }
    }

    FullScreen(onDismiss = close) {
        Scaffold(
            topBar = {
                LyrifyTopBar(
                    title = { Text(stringResource(R.string.tones_editor_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigation = { ChromeIconButton(R.drawable.ic_close, stringResource(R.string.tones_close), close) },
                )
            },
            bottomBar = {
                // The message docks above the save dock, so it never covers Save.
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
                    ToastSlot(Modifier.padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 8.dp))
                    SaveDock(saving, enabled = window.lengthMs > 0, onSave = { ToneSaving.start(files, draft.request(song, lines)) }, onCancel = ToneSaving::cancel)
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            ) {
                SongHeader(song)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
                    Waveform(
                        peaks = peaks,
                        durationMs = draft.durationMs,
                        window = window,
                        progress = previewing?.progress,
                        onMove = { draft = draft.movedTo(it) },
                        onStart = { draft = draft.startingAt(it) },
                        onNudge = { draft = draft.nudged(it) },
                        onDrag = { dragging = it },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PreviewButton(previewing != null) {
                            follow(if (previewing != null) previewState.onStop() else previewState.onPlay(preview))
                        }
                        Text(
                            stringResource(R.string.tones_row_summary, stringResource(kindLabel(draft.kind)), toneTime(window.lengthMs), toneTime(window.startMs)),
                            style = MaterialTheme.typography.titleMedium.tabular(),
                        )
                    }
                    if (draft.limited) Fact(R.drawable.ic_info, stringResource(R.string.tones_first_20_minutes))
                }
                val starts = remember(lines, draft.durationMs) { ToneLyrics.starts(lines, draft.durationMs) }
                if (starts.isNotEmpty()) LyricStrip(starts, draft) { draft = draft.startingAt(it, fromLyric = true) }
                LengthChips(draft, lines) { draft = it }
                Column {
                    SwitchRow(stringResource(R.string.tones_fade_in), draft.fadeIn) { draft = draft.copy(fadeIn = it) }
                    SwitchRow(stringResource(R.string.tones_fade_out), draft.fadeOut) { draft = draft.copy(fadeOut = it) }
                }
                UseAs(draft.kind) { draft = draft.withKind(it) }
            }
        }
    }
}

/** The waveform from the cache, else decoded and cached; empty when the song cannot be decoded (the bar stays plain). */
private suspend fun loadPeaks(context: Context, path: String, durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
    val song = File(path)
    val cache = PeaksCache(File(context.cacheDir, PEAKS_DIR))
    cache.read(song)?.let { return@withContext it }
    val peaks = try {
        MixDecoder(song).use { Peaks.read(it, maxFrames = ToneAudio.frames(durationMs)) {} }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        // The class only: a decoder's message can hold the file name.
        Log.w(LOG_TAG, "waveform not read: ${e.javaClass.simpleName}")
        return@withContext FloatArray(0)
    }
    try {
        cache.write(song, peaks)
    } catch (e: IOException) {
        Log.w(LOG_TAG, "waveform not cached: ${e.javaClass.simpleName}")
    }
    peaks
}

@Composable
private fun SongHeader(song: LedgerEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = ScreenPadding).semantics(mergeDescendants = true) {},
    ) {
        SongCover(SongArt(song.path), size = 64.dp, shape = MaterialTheme.shapes.medium)
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Text(artistOf(song), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The song's waveform with the tone's part over it: the part in the highlight, its start a handle with a knob, the
 * preview's place a thin line. Drag the part to move it, drag near its start to move only the start, tap to move it
 * there; [onDrag] hears a drag start and end. Time runs left to right in every language (a media timeline). TalkBack hears "Tone part, From 1:12, 30
 * seconds" and has actions a second earlier or later.
 */
@Composable
private fun Waveform(
    peaks: FloatArray?,
    durationMs: Long,
    window: ToneWindow,
    progress: Float?,
    onMove: (Long) -> Unit,
    onStart: (Long) -> Unit,
    onNudge: (Long) -> Unit,
    onDrag: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = LocalBrandColors.current.highlight
    val shownWindow by rememberUpdatedState(window)
    val name = stringResource(R.string.tones_part_cd)
    val seconds = (window.lengthMs / 1_000).toInt()
    val state = stringResource(R.string.tones_part_state, toneTime(window.startMs), pluralStringResource(R.plurals.tones_seconds, seconds, seconds))
    val earlier = stringResource(R.string.tones_start_earlier)
    val later = stringResource(R.string.tones_start_later)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .height(WAVEFORM_HEIGHT)
            .clip(MaterialTheme.shapes.medium)
            .semantics {
                contentDescription = name
                stateDescription = state
                customActions = listOf(
                    CustomAccessibilityAction(earlier) {
                        onNudge(-NUDGE_MS)
                        true
                    },
                    CustomAccessibilityAction(later) {
                        onNudge(NUDGE_MS)
                        true
                    },
                )
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset -> onMove((offset.x / size.width * durationMs).toLong()) }
            }
            .pointerInput(durationMs) {
                var handle = false
                var from = 0L
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val startX = shownWindow.startMs.toFloat() / durationMs.coerceAtLeast(1) * size.width
                        handle = abs(offset.x - startX) < HANDLE_SLOP.toPx()
                        from = shownWindow.startMs
                        dragged = 0f
                        onDrag(true)
                    },
                    onDragEnd = { onDrag(false) },
                    onDragCancel = { onDrag(false) },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragged += amount
                        val ms = from + (dragged / size.width * durationMs).toLong()
                        if (handle) onStart(ms) else onMove(ms)
                    },
                )
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(colors.surfaceContainerLow)
            val total = durationMs.coerceAtLeast(1).toFloat()
            val startX = window.startMs / total * size.width
            val endX = window.endMs / total * size.width
            drawRect(highlight.copy(alpha = 0.16f), Offset(startX, 0f), Size((endX - startX).coerceAtLeast(1f), size.height))
            val shown = peaks
            if (shown != null && shown.isNotEmpty()) {
                val loudest = (shown.maxOrNull() ?: 0f).coerceAtLeast(MIN_PEAK)
                val middle = size.height / 2
                val bar = 2.dp.toPx()
                shown.forEachIndexed { i, peak ->
                    val x = (i + 0.5f) / shown.size * size.width
                    val half = (peak / loudest).coerceIn(0.02f, 1f) * (middle - 6.dp.toPx())
                    val inside = x in startX..endX
                    drawLine(if (inside) highlight else colors.onSurfaceVariant.copy(alpha = 0.45f), Offset(x, middle - half), Offset(x, middle + half), strokeWidth = bar)
                }
            }
            val handleWidth = 3.dp.toPx()
            drawRect(highlight, Offset(startX - handleWidth / 2, 0f), Size(handleWidth, size.height))
            drawRect(highlight.copy(alpha = 0.6f), Offset(endX - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
            drawCircle(highlight, radius = 7.dp.toPx(), center = Offset(startX, 7.dp.toPx()))
            progress?.let {
                val x = startX + it * (endX - startX)
                drawRoundRect(colors.onSurface, Offset(x - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height), CornerRadius(1.dp.toPx()))
            }
        }
        if (peaks == null) {
            Text(stringResource(R.string.tones_reading_song), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
    }
}

/** The big preview button: plays exactly the tone's part with its fades, or stops it. */
@Composable
private fun PreviewButton(playing: Boolean, onClick: () -> Unit) {
    ChromeIconButton(
        if (playing) R.drawable.ic_pause_circle else R.drawable.ic_play_circle,
        stringResource(if (playing) R.string.tones_preview_stop else R.string.tones_preview_play),
        onClick,
        glass = false,
        size = 64.dp,
        iconSize = 56.dp,
        tint = LocalChromeColors.current.action,
    )
}

/** "Start at a lyric": the song's lines with their times; tapping one starts the tone there. */
@Composable
private fun LyricStrip(starts: List<LrcLine>, draft: ToneDraft, onStart: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.tones_start_at_lyric), Modifier.padding(horizontal = ScreenPadding))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = ScreenPadding), modifier = Modifier.selectableGroup()) {
            items(starts, key = { it.timeMs }) { line ->
                LyrifyChip(selected = draft.fromLyric && draft.startMs == line.timeMs, onClick = { onStart(line.timeMs) }, role = Role.RadioButton) {
                    Text(toneTime(line.timeMs), style = MaterialTheme.typography.labelLarge.tabular())
                    Text(line.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                }
            }
        }
    }
}

/** 15, 30, 45 and 60 seconds, and "End of line" once a lyric set the start and a line ends in reach. */
@Composable
private fun LengthChips(draft: ToneDraft, lines: List<LrcLine>, onDraft: (ToneDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.tones_length), Modifier.padding(horizontal = ScreenPadding))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = ScreenPadding), modifier = Modifier.selectableGroup()) {
            items(ToneWindow.LENGTHS_MS, key = { it }) { ms ->
                LyrifyChip(selected = !draft.lineEnd && draft.lengthMs == ms, onClick = { onDraft(draft.withLength(ms)) }, role = Role.RadioButton) {
                    Text(stringResource(R.string.tones_length_s, (ms / 1_000).toInt()))
                }
            }
            if (draft.lineEndOffered(lines)) {
                item(key = "line end") {
                    LyrifyChip(selected = draft.lineEnd, onClick = { onDraft(draft.withLineEnd()) }, role = Role.RadioButton, icon = R.drawable.ic_lyrics) {
                        Text(stringResource(R.string.tones_length_line_end))
                    }
                }
            }
        }
    }
}

/** A switch row: one TalkBack stop with the switch's role and the label as its name. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, interactionSource = source, indication = LocalIndication.current, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = ScreenPadding),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        LyrifySwitch(checked, pressed = pressed)
    }
}

/** Ringtone, Alarm or Notification: the folder it is saved in, and its length until one is picked. */
@Composable
private fun UseAs(kind: ToneKind, onKind: (ToneKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
        SectionTitle(stringResource(R.string.tones_use_as))
        LyrifySegmented(
            options = ToneKind.entries.map { stringResource(kindLabel(it)) },
            selected = ToneKind.entries.indexOf(kind),
            onSelect = { onKind(ToneKind.entries[it]) },
        )
    }
}

/** The floating save dock: Save tone, or while it saves, its progress and Cancel. */
@Composable
private fun SaveDock(saving: SaveState, enabled: Boolean, onSave: () -> Unit, onCancel: () -> Unit) {
    GlassSurface(null, CircleShape, Modifier.padding(horizontal = ScreenPadding).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(6.dp)) {
            if (saving is SaveState.Saving) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.heightIn(min = 52.dp).padding(start = 18.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.tones_saving), style = MaterialTheme.typography.labelLarge)
                        SungBar(saving.progress, Modifier.fillMaxWidth())
                    }
                    LyrifyButton(stringResource(R.string.tones_cancel), onClick = onCancel, kind = ButtonKind.SECONDARY, compact = true)
                }
            } else {
                LyrifyButton(stringResource(R.string.tones_save), onClick = onSave, modifier = Modifier.fillMaxWidth(), icon = R.drawable.ic_save, enabled = enabled)
            }
        }
    }
}

private val WAVEFORM_HEIGHT = 112.dp

/** How near the start a drag moves only the start. */
private val HANDLE_SLOP = 24.dp

/** TalkBack's step for the start. */
private const val NUDGE_MS: Long = 1_000

/** A quiet song's waveform is scaled up to its loudest point, but never more than this allows. */
private const val MIN_PEAK = 0.05f

private const val PEAKS_DIR = "tone-peaks"
