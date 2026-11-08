package app.lyrify.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.lyrify.R

/** Where a first-run step stands: done ones say "On", the first one not done is the card, later ones wait quietly. */
private enum class StepState { DONE, OPEN, LATER }

/** Shown until notification access and All files access are both on: the brand hero, two steps and an optional third on a rail. */
@Composable
fun OnboardingScreen(
    access: Access,
    onOpenNotificationAccess: () -> Unit,
    onOpenAllFilesAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    LightStatusBarIcons(false)
    var help by rememberSaveable { mutableStateOf<String?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        val heroHeight = maxHeight * HERO_SHARE
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // The ink hero, the same in both themes: the karaoke line sweeps while the user reads one line.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                modifier = Modifier.fillMaxWidth().heightIn(min = heroHeight).background(BrandInk).statusBarsPadding().padding(bottom = PANEL_OVERLAP + 16.dp, top = 16.dp),
            ) {
                BrandGlyph(width = 120.dp, onInk = true, sweeping = true)
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = BrandLilac, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                Text(stringResource(R.string.setup_intro), style = MaterialTheme.typography.bodyLarge, color = BrandLilac.copy(alpha = 0.7f))
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = PANEL_OVERLAP, topEnd = PANEL_OVERLAP),
                modifier = Modifier.fillMaxWidth().offset(y = -PANEL_OVERLAP),
            ) {
                Column(Modifier.navigationBarsPadding().padding(start = ScreenPadding, end = ScreenPadding, top = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(stringResource(R.string.onboarding_steps))
                    val notifications = if (access.notifications) StepState.DONE else StepState.OPEN
                    val files = when {
                        access.allFiles -> StepState.DONE
                        access.notifications -> StepState.OPEN
                        else -> StepState.LATER
                    }
                    Column {
                        Step(1, R.string.setup_notifications_title, R.string.setup_notifications_detail, notifications) {
                            AllowButton(notifications, onOpenNotificationAccess)
                            if (notifications != StepState.DONE) {
                                LyrifyButton(stringResource(R.string.setup_restricted_title), onClick = { help = RESTRICTED }, kind = ButtonKind.GHOST, compact = true)
                            }
                        }
                        Step(2, R.string.setup_files_title, R.string.setup_files_detail, files) {
                            AllowButton(files, onOpenAllFilesAccess)
                        }
                        Step(
                            3,
                            R.string.setup_battery_title,
                            R.string.setup_battery_detail,
                            StepState.OPEN,
                            optional = true,
                            last = true,
                            onHelp = { help = BATTERY },
                        ) {
                            LyrifyButton(stringResource(R.string.open_app_info), onClick = onOpenAppInfo, kind = ButtonKind.GHOST, compact = true)
                        }
                    }
                    Fact(R.drawable.ic_lock, stringResource(R.string.setup_privacy), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    when (help) {
        RESTRICTED -> HelpSheet(
            stringResource(R.string.setup_restricted_title),
            stringResource(R.string.setup_restricted_detail),
            action = stringResource(R.string.open_app_info),
            onAction = onOpenAppInfo,
        ) { help = null }
        BATTERY -> HelpSheet(
            stringResource(R.string.setup_battery_title),
            stringResource(R.string.setup_battery_sheet),
            action = stringResource(R.string.open_app_info),
            onAction = onOpenAppInfo,
        ) { help = null }
    }
}

/** "Allow": primary on the step to do now, quiet on a later one; nothing once it is on. */
@Composable
private fun AllowButton(state: StepState, onClick: () -> Unit) {
    when (state) {
        StepState.OPEN -> LyrifyButton(stringResource(R.string.setup_allow), onClick = onClick, compact = true)
        StepState.LATER -> LyrifyButton(stringResource(R.string.setup_allow), onClick = onClick, kind = ButtonKind.SECONDARY, compact = true)
        StepState.DONE -> Unit
    }
}

/**
 * One step on the rail: its circle (1, 2, 3; a coral check once done) and the rail down to the next step, coral below a
 * done step, so the sung line runs down as steps are done. Done: the title and "On", one line. The step to do now: a
 * raised card with the title, the detail and [actions]. Later: the title, muted, and its button. [optional]: its tag,
 * the detail and the help circle, no card.
 */
@Composable
private fun Step(
    number: Int,
    @StringRes title: Int,
    @StringRes detail: Int,
    state: StepState,
    optional: Boolean = false,
    last: Boolean = false,
    onHelp: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = LocalBrandColors.current.highlight
    val done = state == StepState.DONE
    val current = state == StepState.OPEN && !optional
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxHeight().clearAndSetSemantics {}) {
            StepCircle(number, state, current)
            if (!last) Box(Modifier.width(2.dp).weight(1f).padding(vertical = 4.dp).background(if (done) highlight else colors.outlineVariant, CircleShape))
        }
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 16.dp)) {
            val heading = Modifier.heightIn(min = STEP_CIRCLE).semantics(mergeDescendants = true) { heading() }
            when {
                done -> Row(verticalAlignment = Alignment.CenterVertically, modifier = heading) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.setup_done), style = MaterialTheme.typography.labelLarge, color = highlight)
                }
                current -> {
                    val shape = RoundedCornerShape(24.dp)
                    val chrome = LocalChromeColors.current
                    LyrifyCard(Modifier.fillMaxWidth().shadow(12.dp, shape, ambientColor = chrome.shadow, spotColor = chrome.shadow), color = chrome.sheet) {
                        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                            Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            StepActions(actions)
                        }
                    }
                }
                optional -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = heading.weight(1f)) {
                            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            Text(
                                stringResource(R.string.setup_optional),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.background(colors.surfaceContainerHighest, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        if (onHelp != null) ChromeIconButton(R.drawable.ic_help, stringResource(R.string.setup_battery_help), onHelp, size = 36.dp, iconSize = 20.dp)
                    }
                    Text(stringResource(detail), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    StepActions(actions)
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant, modifier = heading.weight(1f).wrapContentHeight())
                    actions()
                }
            }
        }
    }
}

@Composable
private fun StepActions(actions: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) { actions() }
}

/** The step's number in a ring (coral on the step to do now), or a coral disc with a check once it is done. Decorative. */
@Composable
private fun StepCircle(number: Int, state: StepState, current: Boolean) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val ring = if (current) brand.highlight else colors.outlineVariant
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(STEP_CIRCLE)
            .then(if (state == StepState.DONE) Modifier.background(brand.highlight, CircleShape) else Modifier.border(2.dp, ring, CircleShape)),
    ) {
        if (state == StepState.DONE) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = brand.onHighlight, modifier = Modifier.size(18.dp))
        } else {
            Text(formatCount(number), style = MaterialTheme.typography.labelLarge.tabular(), color = if (current) brand.highlight else colors.onSurfaceVariant)
        }
    }
}

/** The status bar's icons dark ([light] true) or light while this screen shows; the ink hero needs light ones in both themes. */
@Composable
private fun LightStatusBarIcons(light: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, light) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val before = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = light
        onDispose { if (before != null) controller.isAppearanceLightStatusBars = before }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val RESTRICTED = "restricted"
private const val BATTERY = "battery"

/** The hero's share of the screen's height. */
private const val HERO_SHARE = 0.4f
private val PANEL_OVERLAP = 24.dp
private val STEP_CIRCLE = 32.dp
