package app.astra.audiotext.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.astra.audiotext.settings.ThemeMode

// Palette: ink-blue for structure, saffron for emphasis, warm paper background.
private val Ink = Color(0xFF1E2A3A)
private val InkLight = Color(0xFF34507A)
private val Saffron = Color(0xFFE09A1F)
private val Paper = Color(0xFFF7F4EE)
private val Teal = Color(0xFF2E7D7A)

private val LightColors = lightColorScheme(
    primary = InkLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2F2),
    onPrimaryContainer = Ink,
    secondary = Saffron,
    onSecondary = Color(0xFF241A00),
    secondaryContainer = Color(0xFFFBE3B8),
    onSecondaryContainer = Color(0xFF3A2A00),
    tertiary = Teal,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEBE8),
    onTertiaryContainer = Color(0xFF0D3432),
    background = Paper,
    onBackground = Ink,
    surface = Color(0xFFFFFCF7),
    onSurface = Ink,
    surfaceVariant = Color(0xFFEAE4D8),
    onSurfaceVariant = Color(0xFF4A4A46),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF8A877F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFC6EC),
    onPrimary = Color(0xFF0E2747),
    primaryContainer = Color(0xFF2A4166),
    onPrimaryContainer = Color(0xFFD8E2F2),
    secondary = Color(0xFFF2C46B),
    onSecondary = Color(0xFF3A2A00),
    secondaryContainer = Color(0xFF5A4300),
    onSecondaryContainer = Color(0xFFFBE3B8),
    tertiary = Color(0xFF8ED1CC),
    onTertiary = Color(0xFF003734),
    tertiaryContainer = Color(0xFF1F4F4C),
    onTertiaryContainer = Color(0xFFCDEBE8),
    background = Color(0xFF12161C),
    onBackground = Color(0xFFE4E2DC),
    surface = Color(0xFF181D24),
    onSurface = Color(0xFFE4E2DC),
    surfaceVariant = Color(0xFF2A2F37),
    onSurfaceVariant = Color(0xFFC6C3BA),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = Color(0xFF8F8C84),
)

private val AstraTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun AstraTheme(mode: ThemeMode, textScale: Float, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val base = LocalDensity.current
    // Extra in-app text scaling on top of the system font size (accessibility).
    CompositionLocalProvider(LocalDensity provides Density(base.density, base.fontScale * textScale.coerceIn(0.85f, 1.6f))) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AstraTypography, content = content)
    }
}

/** Semantic colours for states (never a "green shield": approval is shown in blue, not as "safe"). */
object StateColors {
    val pending = Color(0xFFE09A1F)
    val approved = Color(0xFF34507A)
    val recording = Color(0xFFC62828)
    val uncertain = Color(0x33E09A1F)
}
