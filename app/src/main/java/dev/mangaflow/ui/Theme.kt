package dev.mangaflow.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF28282A), onPrimary = Color(0xFFFAFAF9),
    primaryContainer = Color(0xFFECECEA), onPrimaryContainer = Color(0xFF252527),
    secondary = Color(0xFF626267), secondaryContainer = Color(0xFFEEEEEC),
    onSecondaryContainer = Color(0xFF343436),
    background = Color(0xFFF8F8F6), onBackground = Color(0xFF242426),
    surface = Color(0xFFFCFCFA), onSurface = Color(0xFF242426),
    surfaceVariant = Color(0xFFF0F0EE), onSurfaceVariant = Color(0xFF66666B),
    outline = Color(0xFF9B9B9E), outlineVariant = Color(0xFFE7E7E4),
    error = Color(0xFFA74442), onError = Color.White,
    surfaceContainer = Color(0xFFF0F0EE), surfaceContainerLow = Color(0xFFF5F5F2),
    surfaceContainerHigh = Color(0xFFECECE9),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFEAEAE7), onPrimary = Color(0xFF242426),
    primaryContainer = Color(0xFF303033), onPrimaryContainer = Color(0xFFEAEAE7),
    secondary = Color(0xFFB7B7BB), secondaryContainer = Color(0xFF303033),
    onSecondaryContainer = Color(0xFFEAEAE7),
    background = Color(0xFF171719), onBackground = Color(0xFFEAEAE7),
    surface = Color(0xFF202023), onSurface = Color(0xFFEAEAE7),
    surfaceVariant = Color(0xFF2A2A2D), onSurfaceVariant = Color(0xFFB0B0B4),
    outline = Color(0xFF77777C), outlineVariant = Color(0xFF36363A),
    error = Color(0xFFFFB3B0), onError = Color(0xFF571715),
    surfaceContainer = Color(0xFF242427), surfaceContainerLow = Color(0xFF1D1D20),
    surfaceContainerHigh = Color(0xFF2B2B2F),
)

private val ReaderTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun MangaFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, typography = ReaderTypography, content = content)
}
