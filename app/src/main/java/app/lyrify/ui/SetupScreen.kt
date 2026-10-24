package app.lyrify.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lyrify.R

/** Shown until notification access and All files access are both on. */
@Composable
fun SetupScreen(
    access: Access,
    onOpenNotificationAccess: () -> Unit,
    onOpenAllFilesAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge)
            StepCard(
                number = 1,
                title = R.string.setup_notifications_title,
                detail = R.string.setup_notifications_detail,
                action = R.string.setup_notifications_action,
                done = access.notifications,
                onAction = onOpenNotificationAccess,
            ) {
                if (!access.notifications) {
                    Hint(R.string.setup_restricted_title, R.string.setup_restricted_detail, onOpenAppInfo)
                }
            }
            StepCard(
                number = 2,
                title = R.string.setup_files_title,
                detail = R.string.setup_files_detail,
                action = R.string.setup_files_action,
                done = access.allFiles,
                onAction = onOpenAllFilesAccess,
            )
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(20.dp)) {
                    Hint(R.string.setup_samsung_title, R.string.setup_samsung_detail, onOpenAppInfo)
                }
            }
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    @StringRes title: Int,
    @StringRes detail: Int,
    @StringRes action: Int,
    done: Boolean,
    onAction: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (done) colors.primary else colors.primaryContainer),
            ) {
                if (done) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.setup_done), tint = colors.onPrimary)
                } else {
                    Text(number.toString(), color = colors.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                if (done) {
                    Text(stringResource(R.string.setup_done), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                } else {
                    FilledTonalButton(onClick = onAction) { Text(stringResource(action)) }
                }
                extra()
            }
        }
    }
}

@Composable
private fun Hint(@StringRes title: Int, @StringRes detail: Int, onOpenAppInfo: () -> Unit) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenAppInfo, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.open_app_info)) }
    }
}
