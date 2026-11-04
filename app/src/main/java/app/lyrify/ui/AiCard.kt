package app.lyrify.ui

import android.text.format.Formatter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.models.ModelState
import app.lyrify.models.NpuChip
import app.lyrify.overnight.AiHold
import app.lyrify.overnight.AiProgress
import app.lyrify.overnight.NightStatus
import app.lyrify.overnight.nightStatus
import kotlinx.coroutines.launch

/** What the AI card shows, gathered by the activity; [models] is null until the files were looked at. */
@Immutable
data class AiCardState(
    /** This phone's AI chip ([NpuChips.device]); null when the AI does not run on this phone. */
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
    /** "Delete models" waits for the AI to stop: the button says "Stopping…", and it and the switch wait. */
    val stopping: Boolean,
)

/** What the AI card can do. [readLog]: the last lines of the AI's run log. */
@Immutable
data class AiActions(
    val onSwitch: (Boolean) -> Unit,
    val onDownload: () -> Unit,
    val onDelete: () -> Unit,
    val onOpenAppInfo: () -> Unit,
    val readLog: suspend () -> String,
)

/**
 * The "AI lyrics" card: the models, the switch, tonight's progress. [waiting] and [doneToday] come from the ledger. A
 * long press on its title shows the last lines of the AI's run log, to see why a night went as it did.
 */
@Composable
fun AiCard(state: AiCardState, waiting: Int, doneToday: Int, actions: AiActions) {
    val colors = MaterialTheme.colorScheme
    var confirmDelete by remember { mutableStateOf(false) }
    var runLog by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.ai_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.pointerInput(actions) {
                            detectTapGestures(onLongPress = { scope.launch { runLog = actions.readLog() } })
                        },
                    )
                    Text(stringResource(R.string.ai_switch), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Switch(checked = state.enabled, onCheckedChange = actions.onSwitch, enabled = state.chip != null && state.newEnough && !state.stopping)
            }
            Text(stringResource(R.string.ai_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            val chip = state.chip
            if (chip == null) {
                Text(stringResource(R.string.ai_no_chip), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            if (!state.newEnough) {
                Text(stringResource(R.string.ai_unsupported), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(
                stringResource(if (chip.tested) R.string.ai_chip else R.string.ai_chip_untested, chip.name),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            state.models?.let { Models(it, state.stopping, actions.onDownload) { confirmDelete = true } }
            if (state.enabled) {
                Night(nightStatus(state.enabled, state.models is ModelState.Ready, state.progress, state.hold, state.pluggedIn, waiting))
                if (doneToday > 0) {
                    Text(pluralStringResource(R.plurals.ai_done_today, doneToday, doneToday), style = MaterialTheme.typography.bodyMedium)
                }
                if (!state.unrestricted) {
                    Text(stringResource(R.string.ai_battery_tip), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    TextButton(onClick = actions.onOpenAppInfo) { Text(stringResource(R.string.open_app_info)) }
                }
            }
            Text(stringResource(R.string.ai_charging_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        actions.onDelete()
                    },
                ) { Text(stringResource(R.string.ai_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.remove_cancel)) } },
            title = { Text(stringResource(R.string.ai_delete_title)) },
            text = { Text(stringResource(R.string.ai_delete_text, size(onDisk(state.models)))) },
        )
    }

    runLog?.let { text ->
        AlertDialog(
            onDismissRequest = { runLog = null },
            confirmButton = { TextButton(onClick = { runLog = null }) { Text(stringResource(R.string.ai_log_close)) } },
            title = { Text(stringResource(R.string.ai_log_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text.ifEmpty { stringResource(R.string.ai_log_empty) },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState(Int.MAX_VALUE)),
                    )
                }
            },
        )
    }
}

@Composable
private fun Models(models: ModelState, stopping: Boolean, onDownload: () -> Unit, onDelete: () -> Unit) {
    val total = size(models.totalBytes)
    val style = MaterialTheme.typography.bodyMedium
    when (models) {
        is ModelState.Missing -> {
            Text(
                if (models.doneBytes > 0) {
                    stringResource(R.string.ai_models_partial, size(models.doneBytes), total)
                } else {
                    stringResource(R.string.ai_models_missing, total)
                },
                style = style,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.ai_download)) }
                if (models.doneBytes > 0) DeleteButton(stopping, onDelete)
            }
        }
        is ModelState.Downloading -> {
            Text(
                if (models.waiting) {
                    stringResource(R.string.ai_models_waiting, size(models.doneBytes), total)
                } else {
                    stringResource(R.string.ai_models_downloading, models.percent, size(models.doneBytes), total)
                },
                style = style,
            )
            LinearProgressIndicator(progress = { models.percent / PERCENT }, modifier = Modifier.fillMaxWidth())
            DeleteButton(stopping, onDelete)
        }
        is ModelState.NoRoom -> {
            Text(stringResource(R.string.ai_models_no_room, size(models.neededBytes)), style = style)
            if (models.doneBytes > 0) DeleteButton(stopping, onDelete)
        }
        is ModelState.Ready -> {
            Text(stringResource(R.string.ai_models_ready, total), style = style)
            DeleteButton(stopping, onDelete)
        }
        is ModelState.Failed -> {
            Text(stringResource(R.string.ai_models_failed), style = style)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.ai_try_again)) }
                if (models.doneBytes > 0) DeleteButton(stopping, onDelete)
            }
        }
    }
}

/** "Delete models"; "Stopping…" and disabled while a delete waits for the AI to stop. */
@Composable
private fun DeleteButton(stopping: Boolean, onDelete: () -> Unit) {
    TextButton(onClick = onDelete, enabled = !stopping) {
        Text(stringResource(if (stopping) R.string.ai_stopping else R.string.ai_delete))
    }
}

@Composable
private fun Night(status: NightStatus) {
    val style = MaterialTheme.typography.bodyMedium
    when (status) {
        NightStatus.Off -> Unit
        NightStatus.NeedsModels -> Text(stringResource(R.string.ai_needs_models), style = style)
        is NightStatus.Working -> {
            Text(pluralStringResource(R.plurals.ai_tonight, status.total, status.done, status.total), style = style)
            status.title?.let { Text(stringResource(R.string.ai_now, it), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        is NightStatus.Hot -> Text(pluralStringResource(R.plurals.ai_hot, status.total, status.done, status.total), style = style)
        is NightStatus.WaitingForCharger -> Text(pluralStringResource(R.plurals.ai_waiting_charger, status.waiting, status.waiting), style = style)
        is NightStatus.Held -> Text(
            stringResource(
                when (status.reason) {
                    AiHold.NOT_ALLOWED -> R.string.ai_held_not_allowed
                    AiHold.DAILY_LIMIT -> R.string.ai_held_limit
                    AiHold.CRASHED -> R.string.ai_held_crashed
                    AiHold.MODEL_FAILED -> R.string.ai_held_model
                    AiHold.NO_NPU -> R.string.ai_held_npu
                },
            ),
            style = style,
        )
        is NightStatus.Waiting -> Text(pluralStringResource(R.plurals.ai_waiting, status.waiting, status.waiting), style = style)
        NightStatus.AllDone -> Text(stringResource(R.string.ai_all_done), style = style)
    }
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
