package app.lyrify.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyrify.R

/** Settings: the access Lyrify has (each with a way to turn it on), battery use, and About. */
@Composable
fun SettingsScreen(
    access: Access,
    unrestricted: Boolean,
    versionName: String,
    onOpenNotificationAccess: () -> Unit,
    onOpenAllFilesAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LyrifyTopBar(
                title = { Text(stringResource(R.string.settings_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigation = { ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack) },
            )
        },
        // Messages dock at the bottom, above the gesture area.
        bottomBar = { ToastSlot(Modifier.navigationBarsPadding().padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 12.dp)) },
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item { SectionTitle(stringResource(R.string.settings_access), Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 8.dp, bottom = 8.dp)) }
            item {
                GroupedRows {
                    AccessRow(
                        R.drawable.ic_notifications, R.string.setup_notifications_title, R.string.settings_notifications,
                        on = access.notifications, R.string.setup_done, R.string.settings_turn_on, onOpenNotificationAccess, first = true,
                    )
                    AccessRow(
                        R.drawable.ic_folder_open, R.string.setup_files_title, R.string.settings_files,
                        on = access.allFiles, R.string.setup_done, R.string.settings_turn_on, onOpenAllFilesAccess,
                    )
                    AccessRow(
                        R.drawable.ic_battery_full, R.string.setup_battery_title, R.string.settings_battery,
                        on = unrestricted, R.string.settings_unrestricted, R.string.open_app_info, onOpenAppInfo,
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.settings_about), Modifier.padding(start = ScreenPadding + 4.dp, end = ScreenPadding, top = 24.dp, bottom = 8.dp)) }
            item {
                GroupedRows {
                    AboutRow(R.drawable.ic_info, stringResource(R.string.version_label, versionName), first = true)
                    AboutRow(R.drawable.ic_lyrics, stringResource(R.string.settings_source))
                }
            }
        }
    }
}

/** A grant: its icon, title and setting, then [onLabel] with a check while it is on, else a button that opens it. */
@Composable
private fun AccessRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    @StringRes setting: Int,
    on: Boolean,
    @StringRes onLabel: Int,
    @StringRes action: Int,
    onAction: () -> Unit,
    first: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val synced = LocalStatusColors.current[Tone.SYNCED]
    val highlight = LocalBrandColors.current.highlight
    GroupedRow(
        icon,
        stringResource(title),
        tile = if (on) synced.container else colors.surfaceContainerHighest,
        onTile = if (on) synced.onContainer else colors.onSurfaceVariant,
        first = first,
        supporting = { Text(stringResource(setting)) },
        trailing = {
            if (on) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = highlight, modifier = Modifier.size(18.dp))
                    Text(stringResource(onLabel), style = MaterialTheme.typography.labelLarge, color = highlight)
                }
            } else {
                LyrifyButton(stringResource(action), onClick = onAction, kind = ButtonKind.SECONDARY, compact = true)
            }
        },
    )
}

@Composable
private fun AboutRow(@DrawableRes icon: Int, text: String, first: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    GroupedRow(icon, text, tile = colors.surfaceContainerHighest, onTile = colors.onSurfaceVariant, first = first)
}
