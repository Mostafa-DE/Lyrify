package app.lyrify.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.lyrify.data.LyricsStatus

/** Deep ink violet of the launcher icon background, used behind the header mark in both themes. */
val BrandInk: Color = Color(0xFF2E2466)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B2E7E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E0FA),
    onPrimaryContainer = Color(0xFF22184F),
    secondary = Color(0xFFD9573F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE1D9),
    onSecondaryContainer = Color(0xFF4A1409),
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF1B1A22),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1A22),
    surfaceVariant = Color(0xFFECE9F5),
    onSurfaceVariant = Color(0xFF5D5870),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F0F8),
    surfaceContainerHighest = Color(0xFFE5E2EE),
    outline = Color(0xFF8B869C),
    outlineVariant = Color(0xFFDDD9E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC3B6FF),
    onPrimary = Color(0xFF241A5A),
    primaryContainer = Color(0xFF3A2F78),
    onPrimaryContainer = Color(0xFFE6E0FF),
    secondary = Color(0xFFFFB4A3),
    onSecondary = Color(0xFF5C1A0C),
    secondaryContainer = Color(0xFF7A2E1E),
    onSecondaryContainer = Color(0xFFFFDAD2),
    background = Color(0xFF121118),
    onBackground = Color(0xFFE7E3F0),
    surface = Color(0xFF1B1A22),
    onSurface = Color(0xFFE7E3F0),
    surfaceVariant = Color(0xFF26242F),
    onSurfaceVariant = Color(0xFFC9C4D6),
    surfaceContainerLowest = Color(0xFF121118),
    surfaceContainerLow = Color(0xFF1B1A22),
    surfaceContainer = Color(0xFF1B1A22),
    surfaceContainerHigh = Color(0xFF26242F),
    surfaceContainerHighest = Color(0xFF312E3B),
    outline = Color(0xFF938EA2),
    outlineVariant = Color(0xFF34313E),
)

private val AppShapes = Shapes(
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun LyrifyTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = base.copy(titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold)),
        content = content,
    )
}

/** Background and text colour of a status pill. */
@Immutable
data class PillColors(val container: Color, val content: Color)

/** Green for lyrics on disk, amber for maybe later, grey for nothing (or not yet), red for a retry pending. */
@Composable
fun pillColors(status: LyricsStatus): PillColors {
    val dark = isSystemInDarkTheme()
    val (light, night) = when (status) {
        LyricsStatus.SYNCED -> PillColors(Color(0xFFD5F2E3), Color(0xFF0B5133)) to PillColors(Color(0xFF173D2C), Color(0xFFA6E6C5))
        LyricsStatus.EXISTING -> PillColors(Color(0xFFDDE8FA), Color(0xFF1C3F73)) to PillColors(Color(0xFF1D2D47), Color(0xFFB3CCF5))
        LyricsStatus.PLAIN_ONLY, LyricsStatus.DURATION_MISMATCH ->
            PillColors(Color(0xFFFCEBC6), Color(0xFF5A3D00)) to PillColors(Color(0xFF45360F), Color(0xFFF5D58E))
        LyricsStatus.NOT_FOUND, LyricsStatus.INSTRUMENTAL, LyricsStatus.UNIDENTIFIED, LyricsStatus.REMOVED, LyricsStatus.WRITING ->
            PillColors(Color(0xFFECE9F5), Color(0xFF4A4659)) to PillColors(Color(0xFF2E2B38), Color(0xFFC9C4D6))
        LyricsStatus.ERROR -> PillColors(Color(0xFFFFE1D9), Color(0xFF7A1F0E)) to PillColors(Color(0xFF4F1E14), Color(0xFFFFB4A3))
    }
    return if (dark) night else light
}
