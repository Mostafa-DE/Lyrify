package app.lyrify.ui

import android.animation.ValueAnimator
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.lyrify.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/** Deep ink violet of the launcher icon background: the brand mark tile and the onboarding hero, in both themes. */
val BrandInk: Color = Color(0xFF2E2466)

/** Lilac of the glyph's lines on ink. */
val BrandLilac: Color = Color(0xFFF4F1FF)

/** The hairline that keeps the ink tile visible on a dark background. */
val BrandInkBorder: Color = Color(0xFF5A50AE)

/** The launcher's coral: the sung part of the glyph on ink, and a toast's action. */
val LauncherCoral: Color = Color(0xFFFF8A70)

/** Side margin of every screen (M3 compact window margin). */
val ScreenPadding = 16.dp

/** The Lyrify palette, for phones without dynamic colour (Android 11): ink as primary, coral as tertiary. */
internal val LightColors = lightColorScheme(
    primary = Color(0xFF4F44A6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF1A0F5C),
    inversePrimary = Color(0xFFC8BFFF),
    secondary = Color(0xFF5E5A71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4DFF9),
    onSecondaryContainer = Color(0xFF1C1832),
    tertiary = Color(0xFFA2402A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBD1),
    onTertiaryContainer = Color(0xFF3B0A00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1A23),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1A23),
    surfaceVariant = Color(0xFFE6E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FC),
    surfaceContainer = Color(0xFFF1ECF7),
    surfaceContainerHigh = Color(0xFFEBE6F2),
    surfaceContainerHighest = Color(0xFFE5E0EC),
    surfaceDim = Color(0xFFDDD8E3),
    surfaceBright = Color(0xFFFCF8FF),
    outline = Color(0xFF7A7581),
    outlineVariant = Color(0xFFCBC4D2),
    inverseSurface = Color(0xFF312F38),
    inverseOnSurface = Color(0xFFF4EFF9),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFC8BFFF),
    onPrimary = Color(0xFF2E2466),
    primaryContainer = Color(0xFF3F3590),
    onPrimaryContainer = Color(0xFFE5DEFF),
    inversePrimary = Color(0xFF4F44A6),
    secondary = Color(0xFFC8C3DC),
    onSecondary = Color(0xFF312D42),
    secondaryContainer = Color(0xFF474359),
    onSecondaryContainer = Color(0xFFE4DFF9),
    tertiary = Color(0xFFFFB4A1),
    onTertiary = Color(0xFF5F1605),
    tertiaryContainer = Color(0xFF7F2B17),
    onTertiaryContainer = Color(0xFFFFDBD1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF13121A),
    onBackground = Color(0xFFE6E1EC),
    surface = Color(0xFF13121A),
    onSurface = Color(0xFFE6E1EC),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF0E0D14),
    surfaceContainerLow = Color(0xFF1B1A22),
    surfaceContainer = Color(0xFF201E27),
    surfaceContainerHigh = Color(0xFF2A2832),
    surfaceContainerHighest = Color(0xFF35333D),
    surfaceDim = Color(0xFF13121A),
    surfaceBright = Color(0xFF3A3841),
    outline = Color(0xFF948F9A),
    outlineVariant = Color(0xFF49454F),
    inverseSurface = Color(0xFFE6E1EC),
    inverseOnSurface = Color(0xFF312F38),
)

/** A status tone's colours: a pill is [container] with [onContainer]; [accent] marks plain surfaces (bars, dots, icons). */
@Immutable
data class ToneColors(val container: Color, val onContainer: Color, val accent: Color)

/**
 * The status tones, fixed rather than dynamic: each comes with its own container, so it reads on any wallpaper scheme.
 * On-container text is 7:1 or more on its container, accents 3:1 or more on the container and on the surface.
 */
@Immutable
class StatusColors(private val tones: Map<Tone, ToneColors>) {
    operator fun get(tone: Tone): ToneColors = tones.getValue(tone)

    /** Every tone nudged toward a wallpaper scheme's [primary] ([harmonize]). */
    fun harmonized(primary: Color): StatusColors = StatusColors(
        tones.mapValues { (_, it) -> ToneColors(harmonize(it.container, primary), harmonize(it.onContainer, primary), harmonize(it.accent, primary)) },
    )
}

private fun tone(container: Long, onContainer: Long, accent: Long) = ToneColors(Color(container), Color(onContainer), Color(accent))

private val LightStatusColors = StatusColors(
    mapOf(
        Tone.SYNCED to tone(0xFFD3F3DF, 0xFF0B4F30, 0xFF1D7A4A),
        Tone.AI_SYNCED to tone(0xFFCDEFF1, 0xFF084A50, 0xFF12767E),
        Tone.AI_WRITTEN to tone(0xFFF6DAF3, 0xFF5A1456, 0xFF97338F),
        Tone.PLAIN to tone(0xFFF8E9B8, 0xFF4F3D00, 0xFF856600),
        Tone.CHECK to tone(0xFFFFDCC2, 0xFF5C2A00, 0xFFA35200),
        Tone.NONE to tone(0xFFE7E4ED, 0xFF45424E, 0xFF696574),
        Tone.INSTRUMENTAL to tone(0xFFDCE5EE, 0xFF293A48, 0xFF4B6477),
        Tone.ERROR to tone(0xFFFFDAD3, 0xFF6D150B, 0xFFB3261E),
    ),
)

private val DarkStatusColors = StatusColors(
    mapOf(
        Tone.SYNCED to tone(0xFF12392A, 0xFFA9EBC4, 0xFF72D69E),
        Tone.AI_SYNCED to tone(0xFF0F383C, 0xFFA4E3E8, 0xFF5ECED6),
        Tone.AI_WRITTEN to tone(0xFF43183E, 0xFFF5BBEE, 0xFFE68BDB),
        Tone.PLAIN to tone(0xFF3D3105, 0xFFF2D47C, 0xFFE1BB42),
        Tone.CHECK to tone(0xFF4A2406, 0xFFFFC597, 0xFFFF9F58),
        Tone.NONE to tone(0xFF2D2B35, 0xFFCCC7D5, 0xFF9E99A9),
        Tone.INSTRUMENTAL to tone(0xFF21303B, 0xFFBCCDDB, 0xFF8FA7BB),
        Tone.ERROR to tone(0xFF4C1711, 0xFFFFB4A8, 0xFFFF8A7C),
    ),
)

/** The status tones of the active theme, picked by the same dark flag as the colour scheme. */
val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** Whether the active theme is dark (the brand tile gets a border there). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Lyrify's accent on top of either scheme: [highlight] is the "sung line" coral (the glyph's sung part, the queue bar, the
 * AI ring, the active filter's underline); [highlightContainer] is for brand surfaces only, never a status.
 */
@Immutable
data class BrandColors(val highlight: Color, val onHighlight: Color, val highlightContainer: Color, val onHighlightContainer: Color)

internal val LightBrandColors = BrandColors(Color(0xFFC9472C), Color(0xFFFFFFFF), Color(0xFFFFDBD1), Color(0xFF3B0A00))
internal val DarkBrandColors = BrandColors(Color(0xFFFF9A80), Color(0xFF4A1205), Color(0xFF7F2B17), Color(0xFFFFDBD1))

val LocalBrandColors = staticCompositionLocalOf { LightBrandColors }

/** [color] with its hue moved toward [toward]'s by half their difference, at most 15 degrees (Material's harmonize rule). */
fun harmonize(color: Color, toward: Color): Color {
    val from = FloatArray(3).also { ColorUtils.colorToHSL(color.toArgb(), it) }
    val to = FloatArray(3).also { ColorUtils.colorToHSL(toward.toArgb(), it) }
    val diff = ((to[0] - from[0] + 540f) % 360f) - 180f
    from[0] = (from[0] + diff.coerceIn(-30f, 30f) / 2f + 360f) % 360f
    return Color(ColorUtils.HSLToColor(from))
}

/** The brand colours nudged toward a wallpaper scheme's [primary], so the coral sits inside it and stays Lyrify's. */
private fun BrandColors.harmonized(primary: Color) = copy(highlight = harmonize(highlight, primary), highlightContainer = harmonize(highlightContainer, primary))

/** Bricolage Grotesque (SIL OFL 1.1), the variable font at weight 600: titles and big numbers only. */
private val Bricolage = FontFamily(
    Font(R.font.bricolage_grotesque, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)

private fun display(size: Int, lineHeight: Int, tracking: Double = 0.0) =
    TextStyle(fontFamily = Bricolage, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = lineHeight.sp, letterSpacing = tracking.sp)

/**
 * Bricolage for the ring's count, the wordmark, the now-playing title, top bar and sheet titles and big counts; the
 * phone's own font, which follows the user's font choice, for the rest.
 */
private val AppTypography: Typography = Typography().let { base ->
    base.copy(
        displayMedium = display(44, 52, -0.5),
        displaySmall = display(36, 44),
        headlineSmall = display(26, 32),
        titleLarge = display(22, 28),
    )
}

/** Counts line up: tabular figures. */
const val TABULAR: String = "tnum"

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Dynamic colour from the wallpaper on Android 12 and later (the brand accent and status tones harmonised toward it),
 * the Lyrify palette before; light or dark as the phone is. Lyrify's chrome on top: its colours, its press feedback in
 * place of the ripple, reduced motion and the glass switch, read again each time the app comes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyrifyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val dynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        dynamic -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val status = if (dark) DarkStatusColors else LightStatusColors
    val brand = if (dark) DarkBrandColors else LightBrandColors
    var reduced by remember { mutableStateOf(!ValueAnimator.areAnimatorsEnabled()) }
    var glass by remember { mutableStateOf(glassOn(context)) }
    LifecycleResumeEffect(context) {
        reduced = !ValueAnimator.areAnimatorsEnabled()
        glass = glassOn(context)
        onPauseOrDispose { }
    }
    val brandColors = if (dynamic) remember(colors.primary, brand) { brand.harmonized(colors.primary) } else brand
    CompositionLocalProvider(
        LocalStatusColors provides if (dynamic) remember(colors.primary, status) { status.harmonized(colors.primary) } else status,
        LocalBrandColors provides brandColors,
        LocalDarkTheme provides dark,
    ) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography) {
            CompositionLocalProvider(
                // Every plain clickable, toggleable and selectable; no ripple in the Material parts that stay.
                LocalIndication provides PressIndication(),
                LocalRippleConfiguration provides null,
                LocalChromeColors provides remember(colors, brandColors, dark) { chromeColors(colors, brandColors, dark) },
                LocalReducedMotion provides reduced,
                LocalGlassBlur provides glass,
                content = content,
            )
        }
    }
}

// The chrome

/**
 * Lyrify's chrome on either scheme: ink [action] for primary buttons, the ink [toast] with a coral [toastAction], the
 * frosted [glass] of the dock and top bar (as opaque as it must be for its text over any content, [glassAlpha];
 * [glassSolid] when nothing blurs under it), the soft [shadow] of floating chrome, the brand-tinted [scrim] behind sheets
 * and dialogs, and their [sheet] card.
 */
@Immutable
data class ChromeColors(
    val action: Color,
    val onAction: Color,
    val toast: Color,
    val onToast: Color,
    val toastAction: Color,
    val toastEdge: Color,
    val glass: Color,
    val glassSolid: Color,
    val glassEdge: Color,
    val shadow: Color,
    val scrim: Color,
    val sheet: Color,
)

internal fun chromeColors(scheme: ColorScheme, brand: BrandColors, dark: Boolean) = ChromeColors(
    action = if (dark) Color(0xFFE5DEFF) else BrandInk,
    onAction = if (dark) Color(0xFF1A0F5C) else BrandLilac,
    toast = BrandInk,
    onToast = BrandLilac,
    toastAction = LauncherCoral,
    toastEdge = if (dark) BrandInkBorder else Color.Transparent,
    // The worst content under the glass: white under dark glass, black under light glass.
    glass = scheme.surfaceContainer.copy(alpha = glassAlpha(scheme.surfaceContainer, scheme.onSurfaceVariant, brand.highlight, if (dark) Color.White else Color.Black)),
    glassSolid = scheme.surfaceContainer.copy(alpha = GLASS_SOLID_ALPHA),
    glassEdge = Color.White.copy(alpha = if (dark) 0.09f else 0.6f),
    shadow = if (dark) Color.Black.copy(alpha = 0.5f) else ShadowInk.copy(alpha = 0.18f),
    scrim = if (dark) Color.Black.copy(alpha = 0.56f) else ShadowInk.copy(alpha = 0.36f),
    sheet = if (dark) scheme.surfaceContainer else scheme.surfaceContainerLowest,
)

val LocalChromeColors = staticCompositionLocalOf { chromeColors(LightColors, LightBrandColors, dark = false) }

/** The contrast ratio of two opaque colours, as WCAG counts it: 1 to 21. */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * How opaque frosted [glass] has to be: at least the spec's 72%, and enough that the muted [text] on it keeps 4.5:1 and
 * the coral [mark] (the selected tab, the sung line) 3:1 even with [behind], the worst content, blurred under it.
 */
fun glassAlpha(glass: Color, text: Color, mark: Color, behind: Color): Float {
    var alpha = GLASS_ALPHA
    while (alpha < 1f) {
        val tint = glass.copy(alpha = alpha).compositeOver(behind)
        if (contrast(text, tint) >= MIN_TEXT_CONTRAST && contrast(mark, tint) >= MIN_MARK_CONTRAST) return alpha
        alpha += GLASS_STEP
    }
    return 1f
}

/** Whether animations are off (Remove animations, animator scale 0, Battery Saver): springs snap, slides become fades. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** Whether glass chrome blurs what is under it; else it is nearly opaque. */
val LocalGlassBlur = staticCompositionLocalOf { false }

/** Blur needs Android 12; it is off in Battery Saver and when the user raised contrast (Android 14 and later). */
fun glassBlurs(sdk: Int, powerSave: Boolean, contrast: Float): Boolean = sdk >= Build.VERSION_CODES.S && !powerSave && contrast <= 0f

private fun glassOn(context: Context): Boolean {
    val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    val contrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) context.getSystemService(UiModeManager::class.java)?.contrast ?: 0f else 0f
    return glassBlurs(Build.VERSION.SDK_INT, powerSave, contrast)
}

/** The sung line, Lyrify's one "selected" mark: the dock, the segmented control and the Songs status bar draw it. */
internal val SUNG_LINE_WIDTH = 18.dp
internal val SUNG_LINE_HEIGHT = 3.dp

// Motion

/** Lyrify's springs; with reduced motion every one of them is snap() ([motion]). */
object Motion {
    val press: SpringSpec<Float> = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium)

    /** A small overshoot: the sung line lands. */
    val indicator: SpringSpec<Float> = spring(dampingRatio = 0.72f, stiffness = 500f)

    /** Toasts, menus, dialogs. */
    val snappy: SpringSpec<Float> = spring(dampingRatio = 0.85f, stiffness = 600f)

    /** Sizes and content that moves. */
    val settle: SpringSpec<Float> = spring(dampingRatio = 1f, stiffness = 300f)
}

/** [spec], or with reduced motion snap(): the value lands at once. */
fun <T> motion(spec: FiniteAnimationSpec<T>, reduced: Boolean): FiniteAnimationSpec<T> = if (reduced) snap() else spec

/** How far a press squashes: [scale], or not at all with reduced motion (the tint still shows). */
fun pressScale(scale: Float, reduced: Boolean): Float = if (reduced) 1f else scale

/**
 * Lyrify's press feedback in place of the ripple: the pressed thing squashes a little toward its centre and takes a soft
 * tint of the content colour over [shape]. With [scale] 1 (rows, and always with reduced motion) only the tint shows.
 * Put the clickable before the background, so the fill squashes with the content.
 */
class PressIndication(private val shape: Shape = RectangleShape, private val scale: Float = 1f) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, shape, scale)

    override fun equals(other: Any?): Boolean = other is PressIndication && other.shape == shape && other.scale == scale

    override fun hashCode(): Int = 31 * shape.hashCode() + scale.hashCode()
}

private class PressNode(private val source: InteractionSource, private val shape: Shape, private val pressedScale: Float) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val pressed = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressed.animateTo(1f, spring(stiffness = Spring.StiffnessHigh))
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        // A quick tap still shows: reach half way before letting go.
                        if (pressed.value < 0.5f) pressed.animateTo(0.5f, tween(PRESS_MIN_MS))
                        pressed.animateTo(0f, Motion.press)
                    }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = pressed.value
        val squash = pressScale(pressedScale, currentValueOf(LocalReducedMotion))
        scale(1f - (1f - squash) * p) {
            this@draw.drawContent()
            if (p > 0f) drawOutline(shape.createOutline(size, layoutDirection, this), currentValueOf(LocalContentColor).copy(alpha = PRESS_TINT * p))
        }
    }
}

private val ShadowInk = Color(0xFF1A143C)
private const val GLASS_ALPHA = 0.72f
private const val GLASS_STEP = 0.01f
private const val MIN_TEXT_CONTRAST = 4.5f
private const val MIN_MARK_CONTRAST = 3f
private const val GLASS_SOLID_ALPHA = 0.97f
private const val PRESS_TINT = 0.10f
private const val PRESS_MIN_MS = 60
