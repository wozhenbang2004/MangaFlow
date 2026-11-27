package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.enhancement.ui.EnhancementPanelHost
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.glass.softSurfaceShadow

/** Null outside the real Windows player window; false selects a readable non-optical material. */
internal val LocalWindowsVideoDock = compositionLocalOf<Boolean?> { null }

@Composable
fun ProvideWindowsVideoDock(supportsLiveInput: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWindowsVideoDock provides supportsLiveInput, content = content)
}

private val VideoDockPearl = Color(0xFFF2F4F4)
private val VideoDockSlate = Color(0xFF20272B)

@Composable
internal fun AnimePlaybackPanel(
    visible: Boolean,
    overVideo: Boolean,
    artwork: HazeState?,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    content: @Composable () -> Unit,
) {
    EnhancementPanelHost(visible, artwork, onDismiss, onClosed, maximumWidth = 456.dp, playbackOverlay = true,
        surface = { modifier, body ->
            AnimePlaybackSurface(artwork.takeIf { overVideo }, modifier, overVideo = overVideo,
                panel = true, cornerRadius = 24.dp, content = body)
        }, content = content)
}

@Composable
internal fun AnimePlaybackTopActionSurface(
    artwork: HazeState?,
    liveVideo: HazeState?,
    modifier: Modifier,
    overVideo: Boolean,
    windowsVideo: Boolean,
    content: @Composable () -> Unit,
) {
    AnimePlaybackSurface(if (windowsVideo) liveVideo else artwork, modifier, overVideo = overVideo,
        windowsVideoDock = windowsVideo, content = content)
}

/** A material can only sample a separately supplied Compose source, never an assumed native backdrop. */
@Composable
internal fun AnimePlaybackSurface(
    artwork: HazeState?,
    modifier: Modifier = Modifier,
    overVideo: Boolean = true,
    panel: Boolean = false,
    cornerRadius: Dp = 26.dp,
    windowsVideoDock: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accessibility = LocalGlassAccessibilitySettings.current
    val compactVideo = overVideo && !panel
    val pale = windowsVideoDock || !isSystemInDarkTheme()
    val base = if (compactVideo) (if (pale) VideoDockPearl else VideoDockSlate) else colors.surface
    val ink = if (compactVideo) (if (pale) VideoDockSlate else VideoDockPearl) else colors.onSurface
    val controls = remember(colors, base, ink, compactVideo, accessibility.increaseContrast) {
        if (compactVideo) colors.copy(surface = base, onSurface = ink,
            onSurfaceVariant = if (accessibility.increaseContrast) ink else lerp(ink, base, .20f),
            primary = ink, onPrimary = base, inverseSurface = ink, inverseOnSurface = base)
        else colors
    }
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val fallback = remember(base, compactVideo, overVideo, accessibility.reduceTransparency, accessibility.increaseContrast) {
        val opacity = when {
            accessibility.reduceTransparency || accessibility.increaseContrast -> 1f
            compactVideo -> .79f
            overVideo -> .93f
            else -> .96f
        }
        Brush.verticalGradient(listOf(base.copy(alpha = minOf(1f, opacity + .035f)), base.copy(alpha = opacity)))
    }
    MaterialTheme(colorScheme = controls) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            Box(modifier.softSurfaceShadow(shape, base.luminance() > .5f, strength = .65f,
                radius = if (panel) 12.dp else 7.dp).clip(shape), propagateMinConstraints = true) {
                if (artwork != null && !accessibility.reduceTransparency && !accessibility.increaseContrast) {
                    LiquidGlassSurface(artwork, Modifier.matchParentSize(),
                        role = when {
                            compactVideo && pale -> GlassSurfaceRole.VIDEO_DOCK
                            panel -> GlassSurfaceRole.PANEL
                            else -> GlassSurfaceRole.READING_DOCK
                        },
                        cornerRadius = cornerRadius, elevation = 0.dp, surfaceColor = base, contentColor = ink,
                        preferSource = true, softCard = false) { }
                } else {
                    // Native video may not supply pixels to Haze. Keep support local to the control.
                    Box(Modifier.matchParentSize().background(fallback)
                        .then(if (accessibility.showBorders || accessibility.increaseContrast)
                            Modifier.border(1.dp, ink.copy(alpha = .55f), shape) else Modifier))
                }
                // Controls, focused items and scroll state survive the transition to the first native frame.
                content()
            }
        }
    }
}
