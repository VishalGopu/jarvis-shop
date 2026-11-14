package com.chitragupta.shop.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Sky = Color(0xFF00BAF2)
    val Navy = Color(0xFF012B72)
    val Success = Color(0xFF21C17C)
    val Warning = Color(0xFFF5A623)
    val Danger = Color(0xFFE5484D)
}

/** Status colours Material 3 has no role for. */
@Immutable
data class StatusColors(
    val success: Color, val successContainer: Color, val onSuccessContainer: Color,
    val warning: Color, val warningContainer: Color, val onWarningContainer: Color,
)

private val LightStatus = StatusColors(
    Palette.Success, Color(0xFFE3F8EE), Color(0xFF0B6B45),
    Palette.Warning, Color(0xFFFFF3DC), Color(0xFF7A4B00),
)
private val DarkStatus = StatusColors(
    Palette.Success, Color(0xFF0F3B2B), Color(0xFF7BE0B3),
    Palette.Warning, Color(0xFF45330F), Color(0xFFFFD58A),
)
val LocalStatusColors = staticCompositionLocalOf { LightStatus }

val MaterialTheme.status: StatusColors @Composable @ReadOnlyComposable get() = LocalStatusColors.current

private val Light: ColorScheme = lightColorScheme(
    primary = Palette.Sky, onPrimary = Palette.Navy,
    primaryContainer = Color(0xFFD9F4FD), onPrimaryContainer = Palette.Navy,
    secondary = Palette.Navy, onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F7), onSecondaryContainer = Palette.Navy,
    background = Color(0xFFF5F9FC), onBackground = Color(0xFF0B1B33),
    surface = Color.White, onSurface = Color(0xFF0B1B33),
    surfaceVariant = Color(0xFFE8F1F8), onSurfaceVariant = Color(0xFF5B6B80),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F9FC),
    surfaceContainer = Color(0xFFEEF4FA), surfaceContainerHigh = Color(0xFFE8F1F8), surfaceContainerHighest = Color(0xFFE1ECF5),
    outline = Color(0xFF8A9BB0), outlineVariant = Color(0xFFD5E1EC),
    error = Palette.Danger, onError = Color.White, errorContainer = Color(0xFFFDE8E9), onErrorContainer = Color(0xFF8A1F24),
)
private val Dark: ColorScheme = darkColorScheme(
    primary = Palette.Sky, onPrimary = Palette.Navy,
    primaryContainer = Color(0xFF0A3A55), onPrimaryContainer = Color(0xFFBFEBFB),
    secondary = Color(0xFF7FB2FF), onSecondary = Palette.Navy,
    secondaryContainer = Color(0xFF16294A), onSecondaryContainer = Color(0xFFD5E4FF),
    background = Color(0xFF07111F), onBackground = Color(0xFFE6EEF7),
    surface = Color(0xFF0E1D33), onSurface = Color(0xFFE6EEF7),
    surfaceVariant = Color(0xFF16294A), onSurfaceVariant = Color(0xFF93A4BA),
    surfaceContainerLowest = Color(0xFF07111F), surfaceContainerLow = Color(0xFF0B1829),
    surfaceContainer = Color(0xFF0E1D33), surfaceContainerHigh = Color(0xFF16294A), surfaceContainerHighest = Color(0xFF1C3358),
    outline = Color(0xFF6E83A0), outlineVariant = Color(0xFF23395E),
    error = Color(0xFFFF6B70), onError = Color(0xFF3B0A0C), errorContainer = Color(0xFF4A1417), onErrorContainer = Color(0xFFFFD9DA),
)

// Four sizes (32 / 20 / 16 / 12) and two weights (Regular, SemiBold). System font on purpose.
private val Family = FontFamily.Default
private fun ts(size: Int, line: Int, semi: Boolean, tnum: Boolean = false) = TextStyle(
    fontFamily = Family, fontWeight = if (semi) FontWeight.SemiBold else FontWeight.Normal,
    fontSize = size.sp, lineHeight = line.sp, fontFeatureSettings = if (tnum) "tnum" else null,
)
val ChitraguptaTypography = Typography(
    displayLarge = ts(32, 40, true, true), displayMedium = ts(32, 40, true, true), displaySmall = ts(32, 40, true, true),
    headlineLarge = ts(20, 28, true), headlineMedium = ts(20, 28, true), headlineSmall = ts(20, 28, true),
    titleLarge = ts(20, 28, true), titleMedium = ts(16, 24, true), titleSmall = ts(16, 24, true),
    bodyLarge = ts(16, 24, false), bodyMedium = ts(16, 24, false), bodySmall = ts(12, 16, false),
    labelLarge = ts(16, 24, true), labelMedium = ts(12, 16, true), labelSmall = ts(12, 16, false),
)

val ChitraguptaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(16.dp), medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(28.dp),
)

object Spacing { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp; val huge = 48.dp }

@Composable
fun ChitraguptaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // No dynamic colour: the brand palette has to look the same on every phone.
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = ChitraguptaTypography, shapes = ChitraguptaShapes, content = content)
    }
}
