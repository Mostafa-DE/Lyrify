package app.lyrify.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.lyrify.R
import kotlin.math.cos
import kotlin.math.sin

// Buttons

/**
 * What a button does: [PRIMARY] acts (ink; lilac in dark), [SECONDARY] the quieter choice, [DESTRUCTIVE] removes or
 * trashes, [DANGER] only confirms a destructive dialog, [GHOST] is a coral word (See all, Retry, a toast's action).
 */
enum class ButtonKind { PRIMARY, SECONDARY, DESTRUCTIVE, DANGER, GHOST }

/**
 * Lyrify's one button: a capsule 52 dp tall (full-width rows, sheet footers, docks) or 40 dp [compact] (cards, banners,
 * inline); either way the tap, and what TalkBack outlines, take at least 48 dp. A press squashes the capsule. Disabled, it is a faint fill with 38% text. The label is its name; the
 * icon is decorative. [contentColor] overrides the kind's (the toast's coral action on ink).
 */
@Composable
fun LyrifyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
    contentColor: Color = Color.Unspecified,
) {
    val colors = MaterialTheme.colorScheme
    val chrome = LocalChromeColors.current
    val fill = when {
        kind == ButtonKind.GHOST -> Color.Transparent
        !enabled -> colors.onSurface.copy(alpha = DISABLED_FILL)
        kind == ButtonKind.PRIMARY -> chrome.action
        kind == ButtonKind.SECONDARY -> colors.surfaceContainerHigh
        kind == ButtonKind.DESTRUCTIVE -> colors.errorContainer
        else -> colors.error
    }
    val content = when {
        !enabled -> colors.onSurface.copy(alpha = DISABLED_CONTENT)
        contentColor.isSpecified -> contentColor
        kind == ButtonKind.PRIMARY -> chrome.onAction
        kind == ButtonKind.SECONDARY -> colors.onSurface
        kind == ButtonKind.DESTRUCTIVE -> colors.onErrorContainer
        kind == ButtonKind.DANGER -> colors.onError
        else -> LocalBrandColors.current.highlight
    }
    val source = remember { MutableInteractionSource() }
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .minimumInteractiveComponentSize()
            .indication(source, PressIndication(CircleShape, 0.96f))
            .background(fill, CircleShape)
            .heightIn(min = if (compact) 40.dp else 52.dp)
            .padding(horizontal = if (compact && kind == ButtonKind.GHOST) 12.dp else 20.dp, vertical = 6.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Chips and segmented controls

/**
 * A capsule chip, 36 dp tall with a tap of at least 48 dp, no border at rest. Selected, a status chip ([tone]) takes its tone's
 * container and a ring in its accent (a filter keeps its status meaning); any other chip the brand's container and a
 * coral ring. No check mark. [role]: [Role.Checkbox] toggles (status filters), [Role.RadioButton] is one choice of a
 * few, else a button ([chevron]: it opens something).
 */
@Composable
fun LyrifyChip(
    selected: Boolean,
    onClick: () -> Unit,
    role: Role,
    modifier: Modifier = Modifier,
    tone: Tone? = null,
    @DrawableRes icon: Int? = null,
    iconTint: Color = Color.Unspecified,
    count: Int? = null,
    chevron: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val toneColors = tone?.let { LocalStatusColors.current[it] }
    val reduced = LocalReducedMotion.current
    val fill by animateColorAsState(
        when {
            !selected -> colors.surfaceContainerHigh
            toneColors != null -> toneColors.container
            else -> brand.highlightContainer
        },
        motion(tween(CHIP_FADE_MS), reduced),
        label = "chip fill",
    )
    val contentColor by animateColorAsState(
        when {
            !selected -> colors.onSurface
            toneColors != null -> toneColors.onContainer
            else -> brand.onHighlightContainer
        },
        motion(tween(CHIP_FADE_MS), reduced),
        label = "chip content",
    )
    val source = remember { MutableInteractionSource() }
    val action = when (role) {
        Role.Checkbox -> Modifier.toggleable(selected, interactionSource = source, indication = null, role = role, onValueChange = { onClick() })
        Role.RadioButton -> Modifier.selectable(selected, interactionSource = source, indication = null, role = role, onClick = onClick)
        else -> Modifier.clickable(interactionSource = source, indication = null, role = role, onClick = onClick)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .then(action)
            .minimumInteractiveComponentSize()
            .indication(source, PressIndication(CircleShape, 0.96f))
            .background(fill, CircleShape)
            .then(if (selected) Modifier.border(1.5.dp, toneColors?.accent ?: brand.highlight, CircleShape) else Modifier)
            .heightIn(min = 36.dp)
            .padding(start = if (icon != null) 10.dp else 14.dp, end = if (count != null || chevron) 6.dp else 14.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor, LocalTextStyle provides MaterialTheme.typography.labelLarge) {
            if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = iconTint.takeOrElse { contentColor }, modifier = Modifier.size(18.dp))
            content()
            if (count != null) {
                Text(
                    formatCount(count),
                    style = MaterialTheme.typography.labelLarge.tabular(),
                    modifier = Modifier
                        .background(if (selected) contentColor.copy(alpha = 0.12f) else colors.surfaceContainerHighest, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (chevron) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.padding(end = 4.dp).size(16.dp))
        }
    }
}

/**
 * One choice of a few, as a capsule track with a sliding thumb and the sung line under the selected option. The thumb is
 * drawn behind the options, so the control grows with the text (no fixed height). Each option's tap is the track's full
 * 48 dp height; its press shows on the pill inside.
 */
@Composable
fun LyrifySegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val chrome = LocalChromeColors.current
    val colors = MaterialTheme.colorScheme
    val highlight = LocalBrandColors.current.highlight
    val at by animateFloatAsState(selected.toFloat(), motion(Motion.indicator, LocalReducedMotion.current), label = "thumb")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(colors.surfaceContainerHigh, CircleShape)
            .drawBehind {
                val inset = 4.dp.toPx()
                val item = (size.width - 2 * inset) / options.size
                val start = inset + item * at
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - start - item else start
                val thumb = Size(item, size.height - 2 * inset)
                val corner = CornerRadius(thumb.height / 2)
                drawRoundRect(chrome.shadow, Offset(x, inset + 1.dp.toPx()), thumb, corner)
                drawRoundRect(chrome.sheet, Offset(x, inset), thumb, corner)
                val line = Size(SUNG_LINE_WIDTH.toPx(), SUNG_LINE_HEIGHT.toPx())
                drawRoundRect(highlight, Offset(x + (item - line.width) / 2, inset + thumb.height - line.height - 4.dp.toPx()), line, CornerRadius(line.height / 2))
            }
            .padding(horizontal = 4.dp)
            .selectableGroup(),
    ) {
        options.forEachIndexed { i, label ->
            val source = remember { MutableInteractionSource() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .selectable(i == selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = { onSelect(i) })
                    .heightIn(min = 48.dp)
                    .padding(vertical = 4.dp)
                    .clip(CircleShape)
                    .indication(source, LocalIndication.current)
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (i == selected) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// Switch and checkbox: drawn only; the row around them is toggleable with the role and the name.

/**
 * A 52 by 32 dp capsule with a 26 dp knob: off on the outline colour, on in coral. The knob travels with a small
 * overshoot, widens while [pressed], mirrors in right-to-left languages; disabled, all of it at 38%.
 */
@Composable
fun LyrifySwitch(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, pressed: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val reduced = LocalReducedMotion.current
    val at by animateFloatAsState(if (checked) 1f else 0f, motion(Motion.indicator, reduced), label = "knob")
    val squash by animateFloatAsState(if (pressed && !reduced) 1f else 0f, motion(Motion.press, reduced), label = "knob press")
    val track by animateColorAsState(if (checked) brand.highlight else colors.outline, motion(tween(CHIP_FADE_MS), reduced), label = "track")
    val knob = if (checked && LocalDarkTheme.current) brand.onHighlight else Color.White
    val shadow = LocalChromeColors.current.shadow
    Canvas(modifier.size(52.dp, 32.dp).alpha(if (enabled) 1f else DISABLED_CONTENT)) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        val knobSize = KNOB.toPx()
        val inset = (size.height - knobSize) / 2
        val width = knobSize + (KNOB_PRESSED - KNOB).toPx() * squash
        val start = inset + (size.width - 2 * inset - width) * at
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - start - width else start
        val corner = CornerRadius(knobSize / 2)
        drawRoundRect(shadow, Offset(x, inset + 1.dp.toPx()), Size(width, knobSize), corner)
        drawRoundRect(knob, Offset(x, inset), Size(width, knobSize), corner)
    }
}

/** A 24 dp circle: off a ring; on a coral disc that pops in with a check that draws itself. */
@Composable
fun LyrifyCheckbox(checked: Boolean, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val brand = LocalBrandColors.current
    val reduced = LocalReducedMotion.current
    val drawn by animateFloatAsState(if (checked) 1f else 0f, motion(tween(CHECK_DRAW_MS), reduced), label = "check")
    val pop by animateFloatAsState(if (checked) 1f else 0.8f, motion(Motion.indicator, reduced), label = "disc")
    val tick = remember { Path() }
    val part = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier.size(24.dp)) {
        val radius = size.minDimension / 2
        if (drawn < 1f) drawCircle(outline, radius = radius - 1.dp.toPx(), alpha = 1f - drawn, style = Stroke(2.dp.toPx()))
        if (drawn > 0f) {
            drawCircle(brand.highlight, radius = radius * pop, alpha = drawn.coerceIn(0f, 1f))
            tick.reset()
            tick.moveTo(size.width * 0.28f, size.height * 0.52f)
            tick.lineTo(size.width * 0.44f, size.height * 0.67f)
            tick.lineTo(size.width * 0.73f, size.height * 0.36f)
            measure.setPath(tick, false)
            part.reset()
            measure.getSegment(0f, measure.length * drawn.coerceIn(0f, 1f), part, true)
            drawPath(part, brand.onHighlight, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// Text fields

/**
 * Lyrify's text field: a 52 dp filled box, no outline, the [label] above it (inside the field's semantics, so TalkBack
 * reads it with the field); a coral caret, and a coral [ring] while focused. [isError] swaps the ring for red and adds a
 * line under it.
 */
@Composable
fun LyrifyField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    shape: Shape = RoundedCornerShape(16.dp),
    ring: Dp = 2.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = LocalBrandColors.current.highlight
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(highlight),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { field ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (label != null) Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .background(colors.surfaceContainerHigh, shape)
                        .then(if (isError || focused) Modifier.border(ring, if (isError) colors.error else highlight, shape) else Modifier)
                        .padding(start = if (leading != null) 14.dp else 16.dp, end = if (trailing != null) 2.dp else 16.dp),
                ) {
                    leading?.invoke()
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        field()
                    }
                    trailing?.invoke(this)
                }
                if (isError) Box(Modifier.padding(start = 16.dp).width(24.dp).height(2.dp).background(colors.error, CircleShape))
            }
        },
    )
}

// Progress

/**
 * The sung ring, in place of the circular progress indicator: a faint coral track (dotted while nothing has started,
 * so an empty ring never looks like a stalled spinner) and the arc in [color] from 12 o'clock with round caps. With
 * [decorated] (the big ring) a soft glow under the arc and a dot at its end, the playhead; no glow with reduced motion.
 */
@Composable
fun SungRing(fill: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 14.dp, decorated: Boolean = true) {
    val brand = LocalBrandColors.current
    val glow = decorated && !LocalReducedMotion.current
    Canvas(modifier) {
        val width = stroke.toPx()
        val diameter = size.minDimension - (if (glow) width * 2 else width)
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val arc = Size(diameter, diameter)
        val track = brand.highlight.copy(alpha = RING_TRACK_ALPHA)
        if (fill <= 0f) {
            drawArc(track, 0f, 360f, false, topLeft, arc, style = dotted(width * DOT_SHARE))
        } else {
            drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(width))
        }
        val sweep = 360f * fill.coerceIn(0f, 1f)
        if (sweep <= 0f) return@Canvas
        if (glow) drawArc(color.copy(alpha = GLOW_ALPHA), -90f, sweep, false, topLeft, arc, style = Stroke(width * 2, cap = StrokeCap.Round))
        drawArc(color, -90f, sweep, false, topLeft, arc, style = Stroke(width, cap = StrokeCap.Round))
        if (decorated && sweep < 360f) {
            val angle = Math.toRadians((sweep - 90f).toDouble())
            val r = diameter / 2
            val centre = Offset(size.width / 2 + r * cos(angle).toFloat(), size.height / 2 + r * sin(angle).toFloat())
            drawCircle(brand.onHighlight, radius = 3.dp.toPx(), center = centre)
        }
    }
}

/**
 * The sung bar, in place of the linear progress indicator: 6 dp with capsule ends on a faint coral track; [paused] it is
 * the outline colour on a dotted track. With [fill] null it is busy: a coral segment sweeps across, still at 65% with
 * reduced motion.
 */
@Composable
fun SungBar(fill: Float?, modifier: Modifier = Modifier, paused: Boolean = false, height: Dp = 6.dp) {
    val brand = LocalBrandColors.current
    val outline = MaterialTheme.colorScheme.outline
    val sweeping = fill == null && !LocalReducedMotion.current
    val sweep = if (sweeping) {
        rememberInfiniteTransition(label = "bar").animateFloat(0f, 1f, infiniteRepeatable(tween(SWEEP_MS, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "sweep")
    } else {
        null
    }
    val semantics = if (fill != null) Modifier.progressSemantics(fill.coerceIn(0f, 1f)) else Modifier.progressSemantics()
    Canvas(modifier.then(semantics).height(height)) {
        val h = size.height
        val corner = CornerRadius(h / 2)
        if (paused) {
            drawLine(brand.highlight.copy(alpha = BAR_TRACK_ALPHA), Offset(h / 2, h / 2), Offset(size.width - h / 2, h / 2), strokeWidth = h, cap = StrokeCap.Round, pathEffect = dots(h))
        } else {
            drawRoundRect(brand.highlight.copy(alpha = BAR_TRACK_ALPHA), cornerRadius = corner)
        }
        val (from, to) = when {
            fill != null -> 0f to fill.coerceIn(0f, 1f)
            sweep != null -> {
                val at = sweep.value * (1f + SEGMENT) - SEGMENT
                at.coerceAtLeast(0f) to (at + SEGMENT).coerceAtMost(1f)
            }
            else -> 0f to RESTING_FILL
        }
        if (to <= from) return@Canvas
        val left = from * size.width
        val width = (to - from) * size.width
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - left - width else left
        drawRoundRect(if (paused) outline else brand.highlight, Offset(x, 0f), Size(width, h), corner)
    }
}

/** A dotted stroke [width] wide: round dots 9 dp apart. */
private fun DrawScope.dotted(width: Float): Stroke = Stroke(width, cap = StrokeCap.Round, pathEffect = dots(width))

private fun DrawScope.dots(width: Float): PathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), width + DOT_GAP.toPx()))

private const val DISABLED_FILL = 0.08f
private const val DISABLED_CONTENT = 0.38f
private const val CHIP_FADE_MS = 150
private const val CHECK_DRAW_MS = 160
private val KNOB = 26.dp
private val KNOB_PRESSED = 30.dp
private const val RING_TRACK_ALPHA = 0.14f
private const val BAR_TRACK_ALPHA = 0.16f
private const val GLOW_ALPHA = 0.16f

/** The dotted track's dots, as a share of the ring's stroke. */
private const val DOT_SHARE = 0.45f
private val DOT_GAP = 9.dp

/** The busy bar's segment, a share of its width, and one sweep across. */
private const val SEGMENT = 0.3f
private const val SWEEP_MS = 1_400
