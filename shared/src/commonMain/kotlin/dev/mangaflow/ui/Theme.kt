package dev.mangaflow.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.mangaflow.typography.MangaFontScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

/** Native score, scale and heat digits share the selected numeric scene and a readable minimum. */
@Composable
fun mangaMetricValueStyle(size: TextUnit = 12.sp, lineHeight: TextUnit = 16.sp): TextStyle = TextStyle(
    fontFamily = mangaFontFamily(MangaFontScene.NUMBERS), fontWeight = FontWeight.Medium,
    fontSize = if (size.value < 12f) 12.sp else size,
    lineHeight = lineHeight, letterSpacing = 0.sp, fontFeatureSettings = "tnum",
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF586F8D), onPrimary = Color(0xFFFAFAFC),
    primaryContainer = Color(0xFFE7ECF2), onPrimaryContainer = Color(0xFF293C54),
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
    primary = Color(0xFFADBFD5), onPrimary = Color(0xFF203148),
    primaryContainer = Color(0xFF2B3848), onPrimaryContainer = Color(0xFFDFE8F3),
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

/** UI chrome retains its own family; titles, prose and numeric text opt into their scene at the leaf. */
private fun readerTypography(family: FontFamily): Typography {
    fun text(size: Int, height: Int, weight: FontWeight) = TextStyle(
        fontFamily = family, fontWeight = weight, fontSize = size.sp,
        lineHeight = height.sp, letterSpacing = 0.sp)
    return Typography(
        displayLarge = text(24, 32, FontWeight.SemiBold),
        displayMedium = text(24, 32, FontWeight.SemiBold),
        displaySmall = text(20, 28, FontWeight.SemiBold),
        headlineLarge = text(24, 32, FontWeight.SemiBold),
        headlineMedium = text(24, 32, FontWeight.SemiBold),
        headlineSmall = text(20, 28, FontWeight.SemiBold),
        titleLarge = text(20, 28, FontWeight.SemiBold),
        titleMedium = text(16, 24, FontWeight.Medium),
        titleSmall = text(14, 20, FontWeight.Medium),
        bodyLarge = text(16, 26, FontWeight.Normal),
        bodyMedium = text(14, 22, FontWeight.Normal),
        bodySmall = text(12, 18, FontWeight.Normal),
        labelLarge = text(14, 20, FontWeight.Medium),
        labelMedium = text(12, 18, FontWeight.Medium),
        labelSmall = text(12, 18, FontWeight.Medium),
    )
}

@Composable
fun MangaFlowTheme(content: @Composable () -> Unit) {
    MangaFontProvider {
        val family = mangaFontFamily(MangaFontScene.INTERFACE)
        val typography = remember(family) { readerTypography(family) }
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
            typography = typography, content = content)
    }
}
