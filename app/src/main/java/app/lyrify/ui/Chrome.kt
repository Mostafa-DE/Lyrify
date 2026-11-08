package app.lyrify.ui

import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.lyrify.BuildConfig
import app.lyrify.R
import app.lyrify.data.LyricsStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

// Glass

/** What glass chrome blurs: the screen's content, recorded by [glassSource]. */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * Records this content for the glass over it, and draws it as usual. On Android 12 and later the layer is a RenderNode,
 * so a scrolling list updates the glass without the glass recording again. Without blur ([enabled] false: Android 11,
 * Battery Saver, raised contrast) nothing is recorded: the glass is solid then and the content draws directly.
 */
fun Modifier.glassSource(backdrop: GlassBackdrop, enabled: Boolean): Modifier = if (!enabled) {
    this
} else {
    this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }
}

/**
 * Frosted glass: the content under it blurred (when [LocalGlassBlur]) under the translucent glass tint, a hairline and a
 * soft shadow; without blur the tint is nearly opaque, so text on it always has its contrast.
 */
@Composable
fun GlassSurface(
    backdrop: GlassBackdrop?,
    shape: Shape,
    modifier: Modifier = Modifier,
    elevation: Dp = GLASS_SHADOW,
    content: @Composable BoxScope.() -> Unit,
) {
    val chrome = LocalChromeColors.current
    val under = MaterialTheme.colorScheme.background
    val source = backdrop?.takeIf { LocalGlassBlur.current }
    val blurred = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { GLASS_BLUR.toPx() }
    val blur = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = chrome.shadow, spotColor = chrome.shadow) else Modifier)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                if (source != null) {
                    // This window of the content, blurred, on the screen's own background (a list draws none of its own).
                    drawRect(under)
                    blurred.renderEffect = blur
                    val offset = origin - source.origin
                    blurred.record { translate(-offset.x, -offset.y) { drawLayer(source.layer) } }
                    drawLayer(blurred)
                    drawRect(chrome.glass)
                } else {
                    drawRect(chrome.glassSolid)
                }
            }
            .border(1.dp, chrome.glassEdge, shape),
        content = content,
    )
}

// The dock

/**
 * A tab's badge: a count (songs to check, groups to review) or, with [count] null, a dot (AI needs attention).
 * [description] says it for TalkBack: a plural taking the count, else a string.
 */
@Immutable
data class TabBadge(val count: Int?, val description: Int)

/** The dock's badges: the songs to check on Now, the groups not opened yet on Duplicates, a dot on AI when it needs the user. */
fun tabBadges(checks: Int, duplicates: Int, aiAttention: Boolean): Map<Tab, TabBadge> = buildMap {
    if (checks > 0) put(Tab.NOW, TabBadge(checks, R.plurals.nav_check_badge))
    if (duplicates > 0) put(Tab.DUPLICATES, TabBadge(duplicates, R.plurals.nav_duplicates_badge))
    if (aiAttention) put(Tab.AI, TabBadge(null, R.string.nav_ai_attention))
}

/** The tabs in the dock's capsule; Now is the orb beside it. */
val CAPSULE_TABS: List<Tab> = Tab.entries - Tab.NOW

/** Where the dock marks the selected tab: a coral ring round the orb on Now ([orb]), else the sung line under place [line] of the capsule. */
data class DockMark(val orb: Boolean, val line: Int?)

fun dockMark(selected: Tab): DockMark =
    if (selected == Tab.NOW) DockMark(orb = true, line = null) else DockMark(orb = false, line = CAPSULE_TABS.indexOf(selected))

/** A dock item's name for TalkBack: its label, then what plays and its badge when there are. */
fun dockItemName(vararg parts: String?): String = parts.filterNotNull().joinToString(", ")

/** What the Now orb shows: the playing song's cover, its status, its title for TalkBack; the brand mark when [idle]. */
@Immutable
data class NowOrb(val cover: Any?, val status: LyricsStatus?, val title: String?, val idle: Boolean)

/**
 * The orb dock: the Now orb (the playing song's cover, its status, the songs to check) and a glass capsule with the
 * other tabs, each an icon over its label, the selected one marked by the sung line.
 */
@Composable
fun OrbDock(selected: Tab, orb: NowOrb, badges: Map<Tab, TabBadge>, backdrop: GlassBackdrop?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val mark = dockMark(selected)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup(),
    ) {
        NowOrbButton(orb, mark.orb, badges[Tab.NOW], backdrop) { onSelect(Tab.NOW) }
        GlassSurface(backdrop, CircleShape, Modifier.weight(1f)) {
            TabCapsule(selected, mark.line, badges, onSelect)
        }
    }
}

/** The four tabs at equal widths, and the sung line under the selected one; its leading end travels faster, so it stretches on the way. */
@Composable
private fun TabCapsule(selected: Tab, line: Int?, badges: Map<Tab, TabBadge>, onSelect: (Tab) -> Unit) {
    val highlight = LocalBrandColors.current.highlight
    val reduced = LocalReducedMotion.current
    val start = remember { Animatable((line ?: 0).toFloat()) }
    val end = remember { Animatable((line ?: 0).toFloat()) }
    // On Now the line fades out where it was.
    val shown by animateFloatAsState(if (line != null) 1f else 0f, motion(tween(SUNG_FADE_MS), reduced), label = "line shown")
    LaunchedEffect(line, reduced) {
        val target = line?.toFloat() ?: return@LaunchedEffect
        if (reduced) {
            start.snapTo(target)
            end.snapTo(target)
            return@LaunchedEffect
        }
        val forward = target > start.value
        launch { start.animateTo(target, if (forward) LINE_TRAIL else LINE_LEAD) }
        launch { end.animateTo(target, if (forward) LINE_LEAD else LINE_TRAIL) }
    }
    Row(
        Modifier
            .heightIn(min = 64.dp)
            .padding(horizontal = 4.dp)
            .drawBehind {
                if (shown <= 0f) return@drawBehind
                val item = size.width / CAPSULE_TABS.size
                val width = SUNG_LINE_WIDTH.toPx()
                val height = SUNG_LINE_HEIGHT.toPx()
                val left = min(start.value, end.value) * item + (item - width) / 2
                val right = max(start.value, end.value) * item + (item + width) / 2
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - right else left
                drawRoundRect(
                    highlight.copy(alpha = shown.coerceIn(0f, 1f)),
                    Offset(x, size.height - LINE_BOTTOM.toPx() - height),
                    Size(right - left, height),
                    CornerRadius(height / 2),
                )
            },
    ) {
        CAPSULE_TABS.forEach { tab -> CapsuleItem(tab, tab == selected, badges[tab], Modifier.weight(1f)) { onSelect(tab) } }
    }
}

@Composable
private fun CapsuleItem(tab: Tab, selected: Boolean, badge: TabBadge?, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(tab.label)
    val name = dockItemName(label, badge?.words())
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .selectable(selected, interactionSource = null, indication = PressIndication(RoundedCornerShape(20.dp), 0.92f), role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = name }
            .heightIn(min = 56.dp)
            .padding(top = 8.dp, bottom = 14.dp),
    ) {
        BadgedIcon(if (selected) tab.selectedIcon else tab.icon, badge, tint = if (selected) LocalBrandColors.current.highlight else colors.onSurfaceVariant)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
        )
    }
}

/** The Now orb: the playing song's cover in glass with its status, the songs to check; a coral ring on the Now tab. */
@Composable
private fun NowOrbButton(orb: NowOrb, selected: Boolean, checks: TabBadge?, backdrop: GlassBackdrop?, onClick: () -> Unit) {
    val highlight = LocalBrandColors.current.highlight
    val ring by animateFloatAsState(if (selected) 1f else 0f, motion(Motion.indicator, LocalReducedMotion.current), label = "orb ring")
    val name = dockItemName(stringResource(Tab.NOW.label), orb.title, checks?.words())
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ORB)
            .selectable(selected, interactionSource = null, indication = PressIndication(CircleShape, 0.92f), role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = name }
            .drawBehind {
                if (ring > 0f) {
                    val stroke = ORB_RING.toPx()
                    // The ring grows into place as it fades in.
                    val radius = (size.minDimension / 2 - stroke / 2) * (0.9f + 0.1f * ring)
                    drawCircle(highlight, radius = radius, alpha = ring.coerceIn(0f, 1f), style = Stroke(stroke))
                }
            },
    ) {
        GlassSurface(backdrop, CircleShape, Modifier.size(56.dp)) {
            if (orb.idle) {
                BrandMark(Modifier.align(Alignment.Center), size = 40.dp)
            } else {
                SongCover(orb.cover, Modifier.align(Alignment.Center), size = 48.dp, shape = CircleShape)
            }
        }
        // Outside the glass, which is round and would cut the corners off.
        orb.status?.let { StatusBadge(it, Modifier.align(Alignment.BottomEnd).padding(4.dp)) }
        checks?.count?.let { CountBadge(it, Modifier.align(Alignment.TopEnd)) }
    }
}

/** The 24 dp icon with a [CountBadge] or [DotBadge] on its top-end corner; both are in the tab's name, not read again. */
@Composable
private fun BadgedIcon(@DrawableRes icon: Int, badge: TabBadge?, tint: Color) {
    Box {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        val count = badge?.count
        when {
            count != null -> CountBadge(count, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp))
            badge != null -> DotBadge(Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp))
        }
    }
}

/** A count on a dock item: a coral capsule ringed in the glass colour, so it reads as cut out of the dock. Decorative. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val brand = LocalBrandColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clearAndSetSemantics {}
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .padding(2.dp)
            .background(brand.highlight, CircleShape)
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .padding(horizontal = 5.dp),
    ) {
        Text(formatCount(count), style = MaterialTheme.typography.labelSmall.tabular(), fontWeight = FontWeight.Bold, color = brand.onHighlight, maxLines = 1)
    }
}

/** An 8 dp coral dot with the same ring, for "AI needs attention". Decorative. */
@Composable
fun DotBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clearAndSetSemantics {}
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .padding(2.dp)
            .size(8.dp)
            .background(LocalBrandColors.current.highlight, CircleShape),
    )
}

/** A badge's words: "3 songs to check", "AI needs attention". */
@Composable
private fun TabBadge.words(): String = count?.let { pluralStringResource(description, it, it) } ?: stringResource(description)

// Top bars

/**
 * Lyrify's top bar: the title in Bricolage, its navigation and actions as glass circles. Clear at rest; once content is
 * [under] it, a glass band and a hairline fade in. No colour flip.
 */
@Composable
fun LyrifyTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    under: Boolean = false,
    backdrop: GlassBackdrop? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopBand(under, backdrop, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.statusBarsPadding().heightIn(min = BAR_HEIGHT).padding(horizontal = 12.dp)) {
            navigation?.let {
                it()
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.weight(1f).padding(start = 4.dp).semantics(mergeDescendants = true) { heading() }) {
                ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** The band behind a top bar: clear at rest, glass and a hairline while content is [under] it. */
@Composable
fun TopBand(under: Boolean, backdrop: GlassBackdrop?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val band by animateFloatAsState(if (under) 1f else 0f, motion(tween(BAND_FADE_MS), LocalReducedMotion.current), label = "band")
    Box(modifier.fillMaxWidth()) {
        if (band > 0f) GlassSurface(backdrop, RectangleShape, Modifier.matchParentSize().graphicsLayer { alpha = band }, elevation = 0.dp) {}
        content()
    }
}

/**
 * Whether the list has scrolled under the top bar. The bar keeps the scroll state's limit, as Material's bar does: it is
 * what lets the state say how much content is under it. Changes only when the answer does, not on every scrolled pixel.
 */
@ExperimentalMaterial3Api
@Composable
fun TopAppBarScrollBehavior.contentUnder(): Boolean {
    val limit = with(LocalDensity.current) { -BAR_HEIGHT.toPx() }
    SideEffect { if (state.heightOffsetLimit != limit) state.heightOffsetLimit = limit }
    val under by remember(state) { derivedStateOf { state.overlappedFraction > 0.01f } }
    return under
}

/**
 * A round icon button: a 40 dp glass circle in top bars (back, close, actions), or with [glass] false a plain one
 * (inside a field, on a row). The tap, and what TalkBack outlines, take a target of at least 48 dp round it; the press
 * shows on the circle.
 */
@Composable
fun ChromeIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glass: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    tint: Color = LocalContentColor.current,
) {
    val chrome = LocalChromeColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .minimumInteractiveComponentSize(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .indication(source, PressIndication(CircleShape, 0.9f))
                .then(if (glass) Modifier.background(chrome.glassSolid, CircleShape).border(1.dp, chrome.glassEdge, CircleShape) else Modifier),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA), modifier = Modifier.size(iconSize))
        }
    }
}

/** The search field of Songs and the Tone picker: a 52 dp capsule with the search icon, a coral caret and focus ring. */
@Composable
fun LyrifySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    onSearch: () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    LyrifyField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
        placeholder = placeholder,
        shape = CircleShape,
        ring = 1.5.dp,
        leading = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = trailing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

// Menus

/** Lyrify's menu: DropdownMenu (its anchoring, dismiss and focus) as a 20 dp card with a hairline and a soft shadow. */
@Composable
fun LyrifyMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, LocalChromeColors.current.glassEdge),
        content = content,
    )
}

/** A menu item: 48 dp, inset from the menu's edge; the selected one on the brand's container with a check, a destructive one in red. */
@Composable
fun MenuRow(text: String, @DrawableRes icon: Int?, onClick: () -> Unit, selected: Boolean = false, destructive: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val content = when {
        destructive -> colors.error
        selected -> brand.onHighlightContainer
        else -> colors.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .fillMaxWidth()
            .sizeIn(minWidth = 160.dp, maxWidth = 280.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) brand.highlightContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .padding(horizontal = 12.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = content, modifier = Modifier.weight(1f))
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}

// Toasts

/** A short message: its text, maybe a status icon on its tone, maybe one action. */
@Immutable
data class Toast(val text: String, @DrawableRes val icon: Int? = null, val tone: Tone? = null, val action: String? = null)

/**
 * Lyrify's messages, in place of SnackbarHostState: one at a time, in order. [show] waits until the message has gone
 * and says whether its action was tapped. A message shows in the newest [ToastSlot] on screen (the shell's above the
 * dock, else the open sheet's or full screen's above its buttons), so it never sits on a control.
 */
@Stable
class ToastState private constructor(private val live: Boolean) {
    constructor() : this(live = true)

    private val mutex = Mutex()
    private val slots = mutableStateListOf<Any>()

    /** The message on screen; [dismiss] takes it off, [acted] when its action was tapped. */
    class Shown internal constructor(val toast: Toast) {
        internal val result = CompletableDeferred<Boolean>()

        fun dismiss(acted: Boolean = false) {
            result.complete(acted)
        }
    }

    var current: Shown? by mutableStateOf(null)
        private set

    suspend fun show(toast: Toast): Boolean = if (!live) false else mutex.withLock {
        val shown = Shown(toast)
        current = shown
        try {
            shown.result.await()
        } finally {
            current = null
        }
    }

    suspend fun show(text: String): Boolean = show(Toast(text))

    internal fun register(slot: Any) {
        slots += slot
    }

    internal fun unregister(slot: Any) {
        slots -= slot
    }

    /** Whether [slot] shows the message: the newest slot on screen does, so one raised in an open sheet takes it. */
    fun showsIn(slot: Any): Boolean = slots.lastOrNull() === slot

    internal companion object {
        /** Stands in for the app's state where none was provided (release builds only): drops every message at once. */
        val Detached = ToastState(live = false)
    }
}

/** Shows the string [message] with the icon and tone that fit it ([messageToast]). */
suspend fun ToastState.say(resources: Resources, @StringRes message: Int, vararg args: Any): Boolean =
    show(messageToast(message, resources.getString(message, *args)))

/** The app's one [ToastState]; the activity provides it, so the shell, its sheets and full screens find it. */
val LocalToasts = staticCompositionLocalOf { missingToasts(BuildConfig.DEBUG) }

/**
 * No [ToastState] was provided: a mistake. A debug build fails loud; a release build drops the messages rather than
 * crash, or keep a caller waiting for a message nothing would ever show.
 */
internal fun missingToasts(debug: Boolean): ToastState {
    check(!debug) { "No ToastState: provide LocalToasts above this composable (the activity does)." }
    return ToastState.Detached
}

/** How long a message stays: 4 s, 6 s with an action or two lines (TalkBack and a longer timeout stretch it). */
fun toastMillis(toast: Toast): Long = if (toast.action != null || toast.text.length > TWO_LINES) LONG_MS else SHORT_MS

/** [text], the words of the string or plural [message], with the icon and tone that fit it: failures in the error tone. */
fun messageToast(message: Int, text: String): Toast = when (message) {
    R.string.check_done_synced -> Toast(text, R.drawable.ic_lyrics, Tone.SYNCED)
    R.string.removal_done, R.string.removal_done_samsung, R.plurals.dupes_moved, R.string.tones_deleted -> Toast(text, R.drawable.ic_delete, Tone.NONE)
    R.string.dupes_restored -> Toast(text, R.drawable.ic_restore_from_trash, Tone.SYNCED)
    R.string.check_not_saved, R.string.check_busy -> Toast(text, R.drawable.ic_sync_problem, Tone.ERROR)
    R.string.removal_failed, R.string.dupes_trash_failed, R.string.tones_not_saved, R.string.tones_song_unreadable,
    R.string.tones_not_deleted, R.string.tones_not_renamed, R.string.tones_not_played, R.string.tones_clock_not_opened,
    -> Toast(text, R.drawable.ic_error, Tone.ERROR)
    else -> Toast(text)
}

/**
 * Where the app's message shows in this window, docked above the window's bottom row. Each slot registers while it is
 * composed and only the newest shows the message, so one raised under an open sheet appears in the sheet. Takes no
 * space while empty; grows and shrinks with its message ([padding] round it while it shows).
 */
@Composable
fun ToastSlot(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(0.dp), state: ToastState = LocalToasts.current) {
    val key = remember { Any() }
    DisposableEffect(state) {
        state.register(key)
        onDispose { state.unregister(key) }
    }
    val shown = state.current?.takeIf { state.showsIn(key) }
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            // The slot grows up from its bottom edge and clips the pill while it does, so no frame of it lands on the
            // control under the slot (the dock, a sheet's buttons, Save).
            val enter = if (reduced) {
                fadeIn()
            } else {
                fadeIn(tween(TOAST_IN_MS)) + scaleIn(Motion.snappy, initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f))
            }
            (enter togetherWith fadeOut(tween(TOAST_OUT_MS))).using(SizeTransform(clip = true) { _, _ -> motion(TOAST_SIZE, reduced) })
        },
        contentAlignment = Alignment.BottomCenter,
        label = "toast",
        modifier = modifier.fillMaxWidth(),
    ) { message ->
        if (message != null) ToastPill(message, Modifier.padding(padding))
    }
}

/** The ink pill: its icon, up to three lines, its action; hold it to keep it, swipe it away, TalkBack can dismiss it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToastPill(message: ToastState.Shown, modifier: Modifier = Modifier) {
    val toast = message.toast
    val chrome = LocalChromeColors.current
    val a11y = LocalAccessibilityManager.current
    var held by remember(message) { mutableStateOf(false) }
    LaunchedEffect(message, held) {
        if (held) return@LaunchedEffect
        val base = toastMillis(toast)
        delay(a11y?.calculateRecommendedTimeoutMillis(base, containsIcons = toast.icon != null, containsText = true, containsControls = toast.action != null) ?: base)
        message.dismiss()
    }
    val shape = RoundedCornerShape(26.dp)
    SwipeToDismissBox(
        state = rememberSwipeToDismissBoxState(),
        backgroundContent = {},
        modifier = modifier,
        onDismiss = { message.dismiss() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(12.dp, shape, ambientColor = chrome.shadow, spotColor = chrome.shadow)
                .background(chrome.toast, shape)
                .then(if (chrome.toastEdge.alpha > 0f) Modifier.border(1.dp, chrome.toastEdge, shape) else Modifier)
                .pointerInput(message) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        held = true
                        waitForUpOrCancellation()
                        held = false
                    }
                }
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    dismiss {
                        message.dismiss()
                        true
                    }
                }
                .heightIn(min = 52.dp)
                .padding(start = if (toast.icon != null) 12.dp else 20.dp, end = if (toast.action != null) 6.dp else 20.dp, top = 8.dp, bottom = 8.dp),
        ) {
            toast.icon?.let { ToneCircle(it, toast.tone ?: Tone.NONE, size = 28.dp) }
            Text(toast.text, style = MaterialTheme.typography.bodyMedium, color = chrome.onToast, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            toast.action?.let {
                LyrifyButton(it, onClick = { message.dismiss(acted = true) }, kind = ButtonKind.GHOST, compact = true, contentColor = chrome.toastAction)
            }
        }
    }
}

private val GLASS_BLUR = 24.dp
private val GLASS_SHADOW = 16.dp
private val BAR_HEIGHT = 64.dp
private const val BAND_FADE_MS = 180
private val ORB = 64.dp
private val ORB_RING = 2.5.dp

/** The sung line sits this far above the capsule's bottom edge, under the labels. */
private val LINE_BOTTOM = 8.dp
private const val SUNG_FADE_MS = 160
private val LINE_LEAD = spring(dampingRatio = 0.8f, stiffness = 700f, visibilityThreshold = 0.001f)
private val LINE_TRAIL = spring(dampingRatio = 0.8f, stiffness = 260f, visibilityThreshold = 0.001f)
private const val DISABLED_ALPHA = 0.38f

private const val SHORT_MS = 4_000L
private const val LONG_MS = 6_000L

/** About two lines of a toast at the default font size. */
private const val TWO_LINES = 48
private const val TOAST_IN_MS = 160
private const val TOAST_OUT_MS = 120
private val TOAST_SIZE = spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntSize.VisibilityThreshold)
