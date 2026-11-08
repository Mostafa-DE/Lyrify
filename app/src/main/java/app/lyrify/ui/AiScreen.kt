package app.lyrify.ui

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.models.ModelState
import app.lyrify.models.NpuChip
import app.lyrify.overnight.AiHold
import app.lyrify.overnight.AiProgress
import app.lyrify.overnight.NightStatus
import app.lyrify.overnight.nightStatus

/** What the AI tab shows, gathered by the activity; [models] is null until the files were looked at. */
@Immutable
data class AiState(
    /** This phone's AI chip ([app.lyrify.models.NpuChips.device]); null when the AI does not run on this phone. */
    val chip: NpuChip?,
    /** The AI runs on Android 15 and later (its mediaProcessing foreground service). */
    val newEnough: Boolean,
    val models: ModelState?,
    val enabled: Boolean,
    val progress: AiProgress?,
    val hold: AiHold?,
    val pluggedIn: Boolean,
    /** Lyrify is exempt from battery optimisation, so the phone lets it start in the background. */
    val unrestricted: Boolean,
    /** "Delete download" waits for the AI to stop: the button says "Stopping…", and it and the switch wait. */
    val stopping: Boolean,
) {
    /** The AI runs on this phone: its chip and its software. */
    val supported: Boolean get() = chip != null && newEnough

    /** Tonight, from the run, the files, the charger and holds ([waiting] songs from the ledger). */
    fun night(waiting: Int): NightStatus = nightStatus(enabled, models is ModelState.Ready, progress, hold, pluggedIn, waiting)
}

/** What the AI tab can do. [readLog]: the last lines of the AI's run log. */
@Immutable
data class AiActions(
    val onSwitch: (Boolean) -> Unit,
    val onDownload: () -> Unit,
    val onDelete: () -> Unit,
    val onOpenAppInfo: () -> Unit,
    val readLog: suspend () -> String,
)

// The glance: one ring, one line, at most one action

/** The ring's colour: the sung-line highlight while it moves, outline while paused, error, or synced when all is done. */
enum class RingColor { HIGHLIGHT, OUTLINE, ERROR, SYNCED }

/** [fill] of the ring drawn in [color]; 0 shows the empty track. */
data class Ring(val fill: Float, val color: RingColor)

/** A value a text or the ring's centre shows, formatted where it is drawn. */
sealed interface GlanceArg {
    data class Bytes(val bytes: Long) : GlanceArg

    data class Text(val text: String) : GlanceArg

    data class Number(val number: Int) : GlanceArg
}

/** A string ([quantity] set: a plural) and its arguments. */
data class GlanceText(val res: Int, val args: List<GlanceArg> = emptyList(), val quantity: Int? = null)

/** A value in the ring's centre. */
sealed interface CentreValue {
    data class Bytes(val bytes: Long) : CentreValue

    data class Count(val count: Int) : CentreValue

    data class Percent(val percent: Int) : CentreValue

    data class Fraction(val done: Int, val total: Int) : CentreValue
}

/** The ring's centre: the glyph (AI off), an icon with maybe a value under it, or a big value with maybe a label. */
sealed interface Centre {
    data object Glyph : Centre

    data class Icon(@DrawableRes val icon: Int, val value: CentreValue? = null, val tint: RingColor? = null) : Centre

    data class Value(val value: CentreValue, @StringRes val label: Int? = null) : Centre
}

enum class AiActionKind { DOWNLOAD, RESUME, TRY_AGAIN, OPEN_APP_INFO }

data class GlanceAction(val kind: AiActionKind, val label: GlanceText)

/** The AI tab at a glance: [ring] (null: an illustration), [centre], one [line], the [sheet] behind it, at most one [action]. */
@Immutable
data class AiGlance(val ring: Ring?, val centre: Centre, val line: GlanceText, val sheet: GlanceText?, val action: GlanceAction?)

private val EMPTY_RING = Ring(0f, RingColor.HIGHLIGHT)

/** The spec's state table (5.6): the files while they are not ready, else tonight. [waiting]: songs waiting for the AI. */
fun aiGlance(state: AiState, waiting: Int): AiGlance {
    if (state.chip == null) return AiGlance(null, Centre.Icon(R.drawable.ic_memory), GlanceText(R.string.ai_no_chip), GlanceText(R.string.ai_no_chip_detail), null)
    if (!state.newEnough) {
        return AiGlance(null, Centre.Icon(R.drawable.ic_system_update), GlanceText(R.string.ai_unsupported), GlanceText(R.string.ai_unsupported_detail), null)
    }
    if (!state.enabled) return AiGlance(null, Centre.Glyph, GlanceText(R.string.ai_off), GlanceText(R.string.ai_intro), null)
    return when (val night = state.night(waiting)) {
        NightStatus.Off, NightStatus.NeedsModels -> downloadGlance(state.models)
        is NightStatus.Working -> AiGlance(
            Ring(fraction(night.done, night.total), RingColor.HIGHLIGHT),
            Centre.Value(CentreValue.Fraction(night.done, night.total), R.string.ai_tonight_label),
            night.title?.let { GlanceText(R.string.ai_now, listOf(GlanceArg.Text(it))) } ?: GlanceText(R.string.ai_notification_starting),
            null,
            null,
        )
        is NightStatus.Hot -> AiGlance(
            Ring(fraction(night.done, night.total), RingColor.OUTLINE),
            Centre.Icon(R.drawable.ic_thermostat, CentreValue.Fraction(night.done, night.total)),
            GlanceText(R.string.ai_hot),
            GlanceText(R.string.ai_hot_detail),
            null,
        )
        is NightStatus.WaitingForCharger -> AiGlance(
            EMPTY_RING,
            Centre.Icon(R.drawable.ic_power, CentreValue.Count(night.waiting)),
            GlanceText(R.string.ai_waiting_charger),
            GlanceText(R.plurals.ai_waiting_charger_detail, listOf(GlanceArg.Number(night.waiting)), quantity = night.waiting),
            null,
        )
        is NightStatus.Waiting -> AiGlance(
            EMPTY_RING,
            Centre.Icon(R.drawable.ic_schedule, CentreValue.Count(night.waiting)),
            GlanceText(R.string.ai_waiting),
            GlanceText(R.plurals.ai_waiting_detail, listOf(GlanceArg.Number(night.waiting)), quantity = night.waiting),
            null,
        )
        is NightStatus.Held -> when (night.reason) {
            AiHold.NOT_ALLOWED -> AiGlance(
                Ring(1f, RingColor.ERROR),
                Centre.Icon(R.drawable.ic_block, tint = RingColor.ERROR),
                GlanceText(R.string.ai_held_not_allowed),
                GlanceText(R.string.ai_held_not_allowed_detail),
                GlanceAction(AiActionKind.OPEN_APP_INFO, GlanceText(R.string.open_app_info)),
            )
            AiHold.DAILY_LIMIT -> held(RingColor.OUTLINE, R.drawable.ic_pause_circle, R.string.ai_held_limit, R.string.ai_held_limit_detail)
            AiHold.MODEL_FAILED -> held(RingColor.ERROR, R.drawable.ic_error, R.string.ai_held_model, R.string.ai_held_model_detail)
            AiHold.CRASHED -> held(RingColor.ERROR, R.drawable.ic_error, R.string.ai_held_crashed, R.string.ai_held_crashed_detail)
            AiHold.NO_NPU -> held(RingColor.OUTLINE, R.drawable.ic_memory, R.string.ai_held_npu, R.string.ai_held_npu_detail)
        }
        NightStatus.AllDone -> AiGlance(
            Ring(1f, RingColor.SYNCED),
            Centre.Icon(R.drawable.ic_check, tint = RingColor.SYNCED),
            GlanceText(R.string.ai_all_done),
            GlanceText(R.string.ai_all_done_detail),
            null,
        )
    }
}

private fun held(color: RingColor, @DrawableRes icon: Int, @StringRes line: Int, @StringRes sheet: Int) =
    AiGlance(Ring(1f, color), Centre.Icon(icon, tint = color.takeIf { it == RingColor.ERROR }), GlanceText(line), GlanceText(sheet), null)

/** The AI files' states; null (not looked at yet) shows an empty ring and "Download needed" without a size. */
private fun downloadGlance(models: ModelState?): AiGlance = when (models) {
    null -> AiGlance(EMPTY_RING, Centre.Icon(R.drawable.ic_download), GlanceText(R.string.ai_models_missing), null, null)
    is ModelState.Missing -> if (models.doneBytes > 0) {
        AiGlance(
            Ring(fraction(models.doneBytes, models.totalBytes), RingColor.OUTLINE),
            Centre.Value(CentreValue.Percent(percent(models.doneBytes, models.totalBytes))),
            GlanceText(R.string.ai_models_partial),
            GlanceText(R.string.ai_models_partial_detail, listOf(GlanceArg.Bytes(models.doneBytes), GlanceArg.Bytes(models.totalBytes))),
            GlanceAction(AiActionKind.RESUME, GlanceText(R.string.ai_resume)),
        )
    } else {
        AiGlance(
            EMPTY_RING,
            Centre.Icon(R.drawable.ic_download, CentreValue.Bytes(models.totalBytes)),
            GlanceText(R.string.ai_models_missing),
            GlanceText(R.string.ai_models_missing_detail, listOf(GlanceArg.Bytes(models.totalBytes))),
            GlanceAction(AiActionKind.DOWNLOAD, GlanceText(R.string.ai_download, listOf(GlanceArg.Bytes(models.totalBytes)))),
        )
    }
    is ModelState.Downloading -> if (models.waiting) {
        AiGlance(
            Ring(models.percent / PERCENT, RingColor.OUTLINE),
            Centre.Icon(R.drawable.ic_wifi, CentreValue.Percent(models.percent)),
            GlanceText(R.string.ai_models_waiting),
            GlanceText(R.string.ai_models_waiting_detail, listOf(GlanceArg.Bytes(models.doneBytes), GlanceArg.Bytes(models.totalBytes))),
            null,
        )
    } else {
        AiGlance(
            Ring(models.percent / PERCENT, RingColor.HIGHLIGHT),
            Centre.Value(CentreValue.Percent(models.percent)),
            GlanceText(R.string.ai_downloading, listOf(GlanceArg.Bytes(models.doneBytes), GlanceArg.Bytes(models.totalBytes))),
            null,
            null,
        )
    }
    is ModelState.NoRoom -> AiGlance(
        Ring(1f, RingColor.OUTLINE),
        Centre.Icon(R.drawable.ic_storage),
        GlanceText(R.string.ai_models_no_room, listOf(GlanceArg.Bytes(models.neededBytes))),
        GlanceText(R.string.ai_models_no_room_detail),
        null,
    )
    is ModelState.Ready -> AiGlance(Ring(1f, RingColor.SYNCED), Centre.Icon(R.drawable.ic_check, tint = RingColor.SYNCED), GlanceText(R.string.ai_all_done), null, null)
    is ModelState.Failed -> AiGlance(
        Ring(1f, RingColor.ERROR),
        Centre.Icon(R.drawable.ic_error, tint = RingColor.ERROR),
        GlanceText(R.string.ai_models_failed),
        GlanceText(R.string.ai_models_failed_detail),
        GlanceAction(AiActionKind.TRY_AGAIN, GlanceText(R.string.ai_try_again)),
    )
}

/** The value on the Now tab's AI tile. */
sealed interface TileValue {
    data class Fraction(val done: Int, val total: Int) : TileValue

    data class Count(val count: Int) : TileValue

    data class Percent(val percent: Int) : TileValue

    data class Text(@StringRes val res: Int) : TileValue
}

/** The Now tab's AI tile: a mini [ring], a big [value] and a two-word [label]. */
data class AiTile(val ring: Ring?, val value: TileValue, @StringRes val label: Int)

/** The AI tile for [state]; null while AI lyrics are off or do not run on this phone. */
fun aiTile(state: AiState, waiting: Int): AiTile? {
    if (!state.supported || !state.enabled) return null
    val ring = aiGlance(state, waiting).ring
    return when (val night = state.night(waiting)) {
        NightStatus.Off, NightStatus.NeedsModels -> {
            val done = when (val models = state.models) {
                is ModelState.Missing -> models.doneBytes
                is ModelState.Downloading -> models.doneBytes
                is ModelState.NoRoom -> models.doneBytes
                is ModelState.Failed -> models.doneBytes
                is ModelState.Ready, null -> 0
            }
            val total = state.models?.totalBytes ?: 0
            AiTile(ring, if (done > 0) TileValue.Percent(percent(done, total)) else TileValue.Text(R.string.ai_tile_get), R.string.ai_tile_download)
        }
        is NightStatus.Working -> AiTile(ring, TileValue.Fraction(night.done, night.total), R.string.ai_tile_tonight)
        is NightStatus.Hot -> AiTile(ring, TileValue.Fraction(night.done, night.total), R.string.ai_tile_hot)
        is NightStatus.WaitingForCharger -> AiTile(ring, TileValue.Count(night.waiting), R.string.ai_tile_charger)
        is NightStatus.Waiting -> AiTile(ring, TileValue.Count(night.waiting), R.string.ai_tile_soon)
        is NightStatus.Held -> AiTile(
            ring,
            TileValue.Text(R.string.ai_tile_paused),
            when (night.reason) {
                AiHold.DAILY_LIMIT -> R.string.ai_tile_today
                AiHold.NOT_ALLOWED -> R.string.ai_tile_battery
                AiHold.MODEL_FAILED, AiHold.CRASHED -> R.string.ai_tile_update
                AiHold.NO_NPU -> R.string.ai_tile_chip
            },
        )
        NightStatus.AllDone -> AiTile(ring, TileValue.Text(R.string.ai_all_done), R.string.ai_title)
    }
}

/** The AI's statuses as "Results" lists them. */
private val AI_RESULTS = listOf(LyricsStatus.AI_ALIGNED, LyricsStatus.AI_TRANSCRIBED, LyricsStatus.AI_REJECTED, LyricsStatus.AI_INSTRUMENTAL, LyricsStatus.AI_FAILED)

/** The AI statuses with songs, and how many ([counts] per status from the ledger). */
fun aiResults(counts: Map<LyricsStatus, Int>): List<Pair<LyricsStatus, Int>> = AI_RESULTS.mapNotNull { status -> (counts[status] ?: 0).takeIf { it > 0 }?.let { status to it } }

/**
 * The dot on the AI tab: the phone refused to start the AI (battery use can fix it), or the download failed or has no
 * room; a full phone counts only once the user asked for the AI.
 */
fun aiAttention(state: AiState, waiting: Int): Boolean {
    if (!state.supported) return false
    val models = state.models
    return (state.night(waiting) as? NightStatus.Held)?.reason == AiHold.NOT_ALLOWED ||
        models is ModelState.Failed ||
        (models is ModelState.NoRoom && (state.enabled || models.doneBytes > 0))
}

private fun fraction(done: Number, total: Number): Float = if (total.toLong() <= 0) 0f else done.toFloat() / total.toFloat()

private fun percent(done: Long, total: Long): Int = (done * 100 / total.coerceAtLeast(1)).toInt()

// The screen

/**
 * The AI tab: the switch bar, the ring with one line and at most one button, the results, and rows that open sheets. On
 * a phone the AI does not run on, only the ring area. A result opens Songs filtered to it ([onOpenStatus]).
 */
@Composable
fun AiScreen(
    state: AiState,
    counts: Map<LyricsStatus, Int>,
    waiting: Int,
    doneToday: Int,
    actions: AiActions,
    contentPadding: PaddingValues,
    onOpenStatus: (LyricsStatus) -> Unit,
    banner: @Composable () -> Unit,
) {
    val glance = aiGlance(state, waiting)
    var sheet by remember { mutableStateOf<GlanceText?>(null) }
    var downloadSheet by remember { mutableStateOf(false) }
    var howItWorks by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val results = aiResults(counts)
    LazyColumn(
        state = rememberLazyListState(),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "switch") {
            Column {
                banner()
                if (state.supported) AiSwitchBar(state.enabled, enabled = !state.stopping, actions.onSwitch)
            }
        }
        item(key = "glance") {
            AiGlanceView(glance, onLine = { sheet = glance.sheet }) {
                when (it) {
                    AiActionKind.DOWNLOAD, AiActionKind.RESUME, AiActionKind.TRY_AGAIN -> actions.onDownload()
                    AiActionKind.OPEN_APP_INFO -> actions.onOpenAppInfo()
                }
            }
        }
        if (state.supported) {
            if (results.isNotEmpty()) item(key = "results") { AiResults(results, doneToday, onOpenStatus) }
            item(key = "rows") {
                AiRows(
                    state = state,
                    onDownload = { downloadSheet = true },
                    onBattery = actions.onOpenAppInfo,
                    onHowItWorks = { howItWorks = true },
                )
            }
        }
    }
    sheet?.let { text -> HelpSheet(glanceText(glance.line), glanceText(text)) { sheet = null } }
    if (howItWorks) AiInfoSheet { howItWorks = false }
    val models = state.models
    if (downloadSheet && models != null) {
        AiDownloadSheet(models, state.stopping, onDelete = { confirmDelete = true }) { downloadSheet = false }
    }
    if (confirmDelete) {
        DeleteDownloadDialog(models, onDismiss = { confirmDelete = false }) {
            confirmDelete = false
            actions.onDelete()
        }
    }
}

/** "Use AI lyrics": one control with a name for TalkBack ("Use AI lyrics, switch, on"), a capsule with the coral switch. */
@Composable
private fun AiSwitchBar(on: Boolean, enabled: Boolean, onSwitch: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .clip(CircleShape)
            .toggleable(value = on, interactionSource = source, indication = LocalIndication.current, enabled = enabled, role = Role.Switch, onValueChange = onSwitch)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .heightIn(min = 64.dp)
            .padding(start = 24.dp, end = 16.dp),
    ) {
        Text(stringResource(R.string.ai_switch), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        LyrifySwitch(on, enabled = enabled, pressed = pressed)
    }
}

/** The ring, its centre, the status line (tap it for the reason) and at most one button. */
@Composable
private fun AiGlanceView(glance: AiGlance, onLine: () -> Unit, onAction: (AiActionKind) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = ScreenPadding)) {
        AiRing(glance.ring, glance.centre, size = RING_SIZE)
        val line = glanceText(glance.line)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(CircleShape)
                .then(if (glance.sheet != null) Modifier.clickable(onClick = onLine) else Modifier)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Text(line, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (glance.sheet != null) Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        glance.action?.let { action ->
            LyrifyButton(
                glanceText(action.label),
                onClick = { onAction(action.kind) },
                icon = if (action.kind == AiActionKind.DOWNLOAD) R.drawable.ic_download else null,
            )
        }
    }
}

/** The sung ring in the highlight (outline paused, error held, synced done) around [centre], dotted while waiting; no ring, an illustration. */
@Composable
fun AiRing(ring: Ring?, centre: Centre, size: Dp, modifier: Modifier = Modifier) {
    val small = size < SMALL_RING
    val description = ((centre as? Centre.Value)?.value as? CentreValue.Fraction)?.let {
        pluralStringResource(R.plurals.ai_tonight_cd, it.total, it.done, it.total)
    }
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
        if (ring != null) {
            val fill by animateFloatAsState(ring.fill, label = "ring")
            // On the ring's own node, with its progress range: the one stop TalkBack focuses.
            SungRing(
                fill = fill,
                color = ringColor(ring.color),
                stroke = if (small) 4.dp else 14.dp,
                decorated = !small,
                modifier = Modifier
                    .fillMaxSize()
                    .progressSemantics(fill.coerceIn(0f, 1f))
                    .then(if (description != null) Modifier.semantics { stateDescription = description } else Modifier),
            )
        }
        if (!small) RingCentre(centre, size)
    }
}

@Composable
private fun RingCentre(centre: Centre, size: Dp) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (centre) {
        Centre.Glyph -> Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size * 0.6f).clip(CircleShape).background(BrandInk)) {
            BrandGlyph(width = size * 0.3f, onInk = true)
        }
        is Centre.Icon -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(painterResource(centre.icon), contentDescription = null, tint = centre.tint?.let { ringColor(it) } ?: muted, modifier = Modifier.size(size / 5))
            centre.value?.let { Text(centreText(it), style = MaterialTheme.typography.titleMedium.tabular(), color = muted) }
        }
        is Centre.Value -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = size / 6)) {
            BasicText(
                centreText(centre.value),
                style = MaterialTheme.typography.displayMedium.tabular().copy(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = MaterialTheme.typography.displayMedium.fontSize),
            )
            centre.label?.let { Text(stringResource(it), style = MaterialTheme.typography.labelLarge, color = muted) }
        }
    }
}

@Composable
private fun centreText(value: CentreValue): String = when (value) {
    is CentreValue.Bytes -> size(value.bytes)
    is CentreValue.Count -> formatCount(value.count)
    is CentreValue.Percent -> stringResource(R.string.ai_percent, value.percent)
    is CentreValue.Fraction -> stringResource(R.string.ai_tonight_count, value.done, value.total)
}

@Composable
fun ringColor(color: RingColor): Color = when (color) {
    RingColor.HIGHLIGHT -> LocalBrandColors.current.highlight
    RingColor.OUTLINE -> MaterialTheme.colorScheme.outline
    RingColor.ERROR -> MaterialTheme.colorScheme.error
    RingColor.SYNCED -> LocalStatusColors.current[Tone.SYNCED].accent
}

/** "Results": chips with counts, each opening Songs filtered to that status. */
@Composable
private fun AiResults(results: List<Pair<LyricsStatus, Int>>, doneToday: Int, onOpenStatus: (LyricsStatus) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ai_results_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            if (doneToday > 0) {
                Text(pluralStringResource(R.plurals.ai_done_today, doneToday, doneToday), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            results.forEach { (status, count) ->
                val style = status.style
                val label = stringResource(style.label)
                val description = stringResource(R.string.filter_count, label, formatCount(count))
                LyrifyChip(
                    selected = false,
                    onClick = { onOpenStatus(status) },
                    role = Role.Button,
                    icon = style.icon,
                    iconTint = LocalStatusColors.current[style.tone].accent,
                    count = count,
                    chevron = true,
                    modifier = Modifier.semantics { contentDescription = description },
                ) { Text(label) }
            }
        }
    }
}

/** The AI download, battery use, the chip and how it works: grouped rows, sheets behind them. */
@Composable
private fun AiRows(state: AiState, onDownload: () -> Unit, onBattery: () -> Unit, onHowItWorks: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val tones = LocalStatusColors.current
    val chevron: @Composable () -> Unit = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) }
    val models = state.models
    GroupedRows {
        if (models != null) {
            GroupedRow(
                R.drawable.ic_download,
                stringResource(R.string.ai_row_download),
                tile = brand.highlightContainer,
                onTile = brand.onHighlightContainer,
                first = true,
                supporting = { Text(glanceText(downloadLine(models))) },
                onClick = onDownload,
                trailing = chevron,
            )
        }
        val battery = tones[if (state.unrestricted) Tone.SYNCED else Tone.ERROR]
        GroupedRow(
            R.drawable.ic_battery_full,
            stringResource(R.string.ai_row_battery),
            tile = battery.container,
            onTile = battery.onContainer,
            first = models == null,
            supporting = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (state.unrestricted) {
                        Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.settings_unrestricted))
                    } else {
                        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = colors.error, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.settings_restricted), color = colors.error)
                    }
                }
            },
            onClick = onBattery,
            trailing = chevron,
        )
        state.chip?.let { chip ->
            GroupedRow(
                R.drawable.ic_memory,
                stringResource(R.string.ai_row_chip),
                tile = colors.surfaceContainerHighest,
                onTile = colors.onSurfaceVariant,
                supporting = { Text(stringResource(if (chip.tested) R.string.ai_chip else R.string.ai_chip_untested, chip.name)) },
            )
        }
        GroupedRow(
            R.drawable.ic_info,
            stringResource(R.string.ai_row_how),
            tile = colors.surfaceContainerHighest,
            onTile = colors.onSurfaceVariant,
            onClick = onHowItWorks,
            trailing = chevron,
        )
    }
}

/** The download row's line: "980 MB · Ready", else the download state's line. */
private fun downloadLine(models: ModelState): GlanceText =
    if (models is ModelState.Ready) GlanceText(R.string.ai_models_ready, listOf(GlanceArg.Bytes(models.totalBytes))) else downloadGlance(models).line

/** The AI download: its size, its state, and "Delete download" in red while something is on the phone. */
@Composable
private fun AiDownloadSheet(models: ModelState, stopping: Boolean, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val style = downloadGlance(models)
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        footer = if (onDisk(models) <= 0) {
            null
        } else {
            {
                LyrifyButton(
                    stringResource(if (stopping) R.string.ai_stopping else R.string.ai_delete),
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    kind = ButtonKind.DESTRUCTIVE,
                    icon = R.drawable.ic_delete,
                    enabled = !stopping,
                )
            }
        },
    ) {
        Text(stringResource(R.string.ai_row_download), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Fact(R.drawable.ic_storage, size(models.totalBytes))
        Fact(if (models is ModelState.Ready) R.drawable.ic_check_circle else (style.centre as? Centre.Icon)?.icon ?: R.drawable.ic_download, glanceText(downloadLine(models)))
    }
}

/** "How AI lyrics work": what it does, and when it runs. From the row and the top bar's info button. */
@Composable
fun AiInfoSheet(onDismiss: () -> Unit) {
    HelpSheet(stringResource(R.string.ai_info_title), stringResource(R.string.ai_intro), stringResource(R.string.ai_charging_note), onDismiss = onDismiss)
}

@Composable
private fun DeleteDownloadDialog(models: ModelState?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    ConfirmDialog(
        title = stringResource(R.string.ai_delete_title),
        text = stringResource(R.string.ai_delete_text, size(onDisk(models))),
        confirm = stringResource(R.string.ai_delete_confirm),
        dismiss = stringResource(R.string.remove_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** The last lines of the AI's run log, to see why a night went as it did, newest at the bottom. Opened from the AI tab's menu. */
@Composable
fun RunLogSheet(text: String, onDismiss: () -> Unit) {
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        // Opens at the end: the latest lines.
        scroll = rememberScrollState(Int.MAX_VALUE),
        footer = { LyrifyButton(stringResource(R.string.ai_log_close), onClick = onDismiss, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.SECONDARY) },
    ) {
        Text(stringResource(R.string.ai_log_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        SelectionContainer {
            Text(text.ifEmpty { stringResource(R.string.ai_log_empty) }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

/** [text] as the phone shows it: sizes as "980 MB", numbers as numbers. */
@Composable
fun glanceText(text: GlanceText): String {
    val args = text.args.map<GlanceArg, Any> { arg ->
        when (arg) {
            is GlanceArg.Bytes -> size(arg.bytes)
            is GlanceArg.Text -> arg.text
            is GlanceArg.Number -> arg.number
        }
    }.toTypedArray()
    return text.quantity?.let { pluralStringResource(text.res, it, *args) } ?: stringResource(text.res, *args)
}

/** The Now tab's AI tile value. */
@Composable
fun tileText(value: TileValue): String = when (value) {
    is TileValue.Fraction -> stringResource(R.string.ai_tonight_count, value.done, value.total)
    is TileValue.Count -> formatCount(value.count)
    is TileValue.Percent -> stringResource(R.string.ai_percent, value.percent)
    is TileValue.Text -> stringResource(value.res)
}

/** Bytes as the phone shows sizes, e.g. "1.1 GB". */
@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

private fun onDisk(models: ModelState?): Long = when (models) {
    is ModelState.Missing -> models.doneBytes
    is ModelState.Downloading -> models.doneBytes
    is ModelState.Failed -> models.doneBytes
    is ModelState.NoRoom -> models.doneBytes
    is ModelState.Ready -> models.totalBytes
    null -> 0
}

private const val PERCENT = 100f
private val RING_SIZE = 200.dp

/** Below this the ring is the Now tile's mini ring: thin, no centre. */
private val SMALL_RING = 64.dp
