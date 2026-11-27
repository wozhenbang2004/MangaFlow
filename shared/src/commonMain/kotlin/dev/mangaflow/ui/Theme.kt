package dev.mangaflow.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import dev.mangaflow.typography.MangaFontScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences

/** Native score, scale and heat digits share the selected numeric scene and a readable minimum. */
@Composable
fun mangaMetricValueStyle(size: TextUnit = 12.sp, lineHeight: TextUnit = 16.sp): TextStyle = TextStyle(
    fontFamily = mangaFontFamily(MangaFontScene.NUMBERS), fontWeight = FontWeight.Medium,
    fontSize = if (size.value < 12f) 12.sp else size,
    lineHeight = lineHeight, letterSpacing = 0.sp, fontFeatureSettings = "tnum",
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF44677C), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2EDF2), onPrimaryContainer = Color(0xFF233F50),
    secondary = Color(0xFF556467), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF0EF), onSecondaryContainer = Color(0xFF344547),
    tertiary = Color(0xFF78604D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2E9DF), onTertiaryContainer = Color(0xFF4F3B2C),
    background = Color(0xFFF7F8F6), onBackground = Color(0xFF20272A),
    surface = Color(0xFFFDFEFC), onSurface = Color(0xFF20272A),
    surfaceVariant = Color(0xFFEBEFED), onSurfaceVariant = Color(0xFF556165),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F5F2),
    surfaceContainer = Color(0xFFEEF1EE), surfaceContainerHigh = Color(0xFFE9EDEB),
    surfaceContainerHighest = Color(0xFFE3E8E6), surfaceBright = Color(0xFFFDFEFC),
    surfaceDim = Color(0xFFDCE2DF), outline = Color(0xFF758185), outlineVariant = Color(0xFFDBE2DF),
    inverseSurface = Color(0xFF2D3538), inverseOnSurface = Color(0xFFF4F7F5),
    inversePrimary = Color(0xFFACCDE0), scrim = Color(0xFF263337),
    error = Color(0xFFA33F3C), onError = Color.White,
    errorContainer = Color(0xFFF8E6E2), onErrorContainer = Color(0xFF692C29),
)
internal val MangaDarkColorScheme = darkColorScheme(
    primary = Color(0xFFACCBDA), onPrimary = Color(0xFF17333F),
    primaryContainer = Color(0xFF293F4B), onPrimaryContainer = Color(0xFFDDEDF4),
    secondary = Color(0xFFB2C4C2), onSecondary = Color(0xFF213230),
    secondaryContainer = Color(0xFF2A3736), onSecondaryContainer = Color(0xFFDDE9E7),
    tertiary = Color(0xFFD9BDA6), onTertiary = Color(0xFF3F3026),
    tertiaryContainer = Color(0xFF46382F), onTertiaryContainer = Color(0xFFF3E1D2),
    background = Color(0xFF15191B), onBackground = Color(0xFFECF0ED),
    surface = Color(0xFF1D2325), onSurface = Color(0xFFECF0ED),
    surfaceVariant = Color(0xFF2C3437), onSurfaceVariant = Color(0xFFB1BFBE),
    surfaceContainerLowest = Color(0xFF111618), surfaceContainerLow = Color(0xFF1A2022),
    surfaceContainer = Color(0xFF23292C), surfaceContainerHigh = Color(0xFF293134),
    surfaceContainerHighest = Color(0xFF333C3F), surfaceBright = Color(0xFF384144),
    surfaceDim = Color(0xFF14191B), outline = Color(0xFF849392), outlineVariant = Color(0xFF374244),
    inverseSurface = Color(0xFFE2E9E6), inverseOnSurface = Color(0xFF283134),
    inversePrimary = Color(0xFF44677C), scrim = Color(0xFF080D0F),
    error = Color(0xFFFFB3AC), onError = Color(0xFF591E1C),
    errorContainer = Color(0xFF542B2A), onErrorContainer = Color(0xFFFFDBD5),
)

@Composable
internal fun mangaOverlayScrim(): Color {
    val colors = MaterialTheme.colorScheme
    val light = colors.surface.luminance() > .5f
    val strong = LocalPlatformAccessibilityPreferences.current.increaseContrast
    return colors.scrim.copy(alpha = if (strong) .34f else if (light) .16f else .26f)
}

/** Small evidence-backed status marks; selection and actions keep the theme's primary. */
@Immutable
internal data class MangaStatusColors(
    val success: Color,
    val activity: Color,
    val waiting: Color,
    val error: Color,
    val neutral: Color,
)

@Composable
internal fun mangaStatusColors(): MangaStatusColors {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < .5f
    return MangaStatusColors(
        success = if (dark) Color(0xFF8FD1A8) else Color(0xFF32754F),
        activity = if (dark) Color(0xFF79CAD0) else Color(0xFF246D7B),
        waiting = if (dark) Color(0xFFE2BB78) else Color(0xFF8D641F),
        error = colors.error,
        neutral = colors.onSurfaceVariant,
    )
}

/** UI chrome retains its own family; titles, prose and numeric text opt into their scene at the leaf. */
private fun readerTypography(family: FontFamily): Typography {
    fun text(size: Int, height: Int, weight: FontWeight) = TextStyle(
        fontFamily = family, fontWeight = weight, fontSize = size.sp,
        lineHeight = height.sp, letterSpacing = 0.sp)
    fun heading(size: Int, height: Int) = text(size, height, FontWeight.SemiBold)
        .copy(letterSpacing = (-.25f).sp, lineBreak = LineBreak.Heading)
    return Typography(
        displayLarge = heading(36, 44),
        displayMedium = heading(32, 40),
        displaySmall = heading(28, 36),
        headlineLarge = heading(28, 36),
        headlineMedium = heading(24, 32),
        headlineSmall = heading(22, 30),
        titleLarge = heading(20, 27),
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
        val dark = isSystemInDarkTheme()
        val highContrast = LocalPlatformAccessibilityPreferences.current.increaseContrast
        val colors = remember(dark, highContrast) {
            val base = if (dark) MangaDarkColorScheme else LightColors
            if (!highContrast) base else base.copy(
                onSurface = if (dark) Color.White else Color(0xFF11191C),
                onSurfaceVariant = if (dark) Color(0xFFDBE6E3) else Color(0xFF364449),
                outline = if (dark) Color(0xFFB6C4C1) else Color(0xFF526166),
            )
        }
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}
