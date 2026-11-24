package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.enhancement.ui.EnhancementPanelHost
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.MangaDarkColorScheme

/** Null outside the real Windows player window; false selects a readable non-optical material. */
internal val LocalWindowsVideoDock = compositionLocalOf<Boolean?> { null }

@Composable
fun ProvideWindowsVideoDock(supportsLiveInput: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWindowsVideoDock provides supportsLiveInput, content = content)
}

internal val WindowsVideoDockSurface = Color(0xFFEFF2F4)
internal val WindowsVideoDockInk = Color(0xFF151A20)

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
            MaterialTheme(colorScheme = if (overVideo) MangaDarkColorScheme else MaterialTheme.colorScheme) {
                AnimePlaybackSurface(artwork.takeIf { overVideo }, modifier, overVideo = overVideo,
                    panel = true, cornerRadius = 22.dp, content = body)
            }
        }, content = content)
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
    val base = if (windowsVideoDock) WindowsVideoDockSurface else if (overVideo) Color(0xFF14171D) else colors.surface
    val ink = if (windowsVideoDock) WindowsVideoDockInk else if (overVideo) Color(0xFFFAFAFC) else colors.onSurface
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    CompositionLocalProvider(LocalContentColor provides ink) {
        Box(modifier.clip(shape), propagateMinConstraints = true) {
            if (artwork != null && !accessibility.reduceTransparency && !accessibility.increaseContrast) {
                LiquidGlassSurface(artwork, Modifier.matchParentSize(),
                    role = when {
                        windowsVideoDock -> GlassSurfaceRole.VIDEO_DOCK
                        panel -> GlassSurfaceRole.PANEL
                        else -> GlassSurfaceRole.READING_DOCK
                    },
                    cornerRadius = cornerRadius, elevation = 0.dp, surfaceColor = base, contentColor = ink,
                    preferSource = true, softCard = false) { }
            } else if (windowsVideoDock) {
                // No frame is captured here. Keep dark controls legible over even the darkest video.
                val opaque = accessibility.reduceTransparency || accessibility.increaseContrast
                Box(Modifier.matchParentSize().background(base.copy(alpha = if (opaque) 1f else .60f))
                    .then(if (accessibility.showBorders || accessibility.increaseContrast)
                        Modifier.border(1.dp, ink.copy(alpha = .55f), shape) else Modifier))
            } else {
                val light = base.luminance() > .5f
                val rim = remember(light, accessibility.increaseContrast) {
                    val edge = if (light) Color.Black else Color.White
                    if (accessibility.increaseContrast) Brush.linearGradient(listOf(edge.copy(alpha = .58f), edge.copy(alpha = .50f)))
                    else Brush.linearGradient(listOf(Color.White.copy(alpha = if (light) .96f else .38f),
                        edge.copy(alpha = .09f), edge.copy(alpha = if (light) .18f else .24f)))
                }
                val opacity = when {
                    accessibility.reduceTransparency -> 1f
                    accessibility.increaseContrast -> .97f
                    panel && overVideo -> .84f
                    overVideo -> .76f
                    else -> .90f
                }
                Box(Modifier.matchParentSize().background(base.copy(alpha = opacity)).border(1.dp, rim, shape))
            }
            // Controls, focused items and scroll state survive the transition to the first native frame.
            content()
        }
    }
}
