package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.enhancement.ui.EnhancementPanelHost
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.glass.softSurfaceShadow

/** Only the Windows Direct3D player window supplies a sampleable live video layer. */
internal val LocalWindowsVideoDock = compositionLocalOf { false }

@Composable
fun ProvideWindowsVideoDock(supportsLiveInput: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalWindowsVideoDock provides supportsLiveInput, content = content)
}

private val VideoControlShade = Color(0xFF080E15)
private val VideoControlInk = Color(0xFFFBFCFE)
private val VideoControlSecondaryInk = Color(0xFFF1F3F5)
private val VideoControlBubble = Color(0xFF293139)
// The middle supports small white labels even over a white frame. The edges transmit more of the film.
private val VideoControlScrim = Brush.verticalGradient(
    0f to VideoControlShade.copy(alpha = .23f),
    .12f to VideoControlShade.copy(alpha = .60f),
    .88f to VideoControlShade.copy(alpha = .60f),
    1f to VideoControlShade.copy(alpha = .26f),
)
private val VideoControlRim = Brush.verticalGradient(
    0f to Color.White.copy(alpha = .28f),
    .45f to Color.White.copy(alpha = .07f),
    1f to Color.White.copy(alpha = .12f),
)
private val TimelineWash = SolidColor(VideoControlShade.copy(alpha = .08f))

/** Follows each measured label or action. The space allocated to the seek track stays clear. */
internal val AnimeTimelineInk = Modifier.drawWithCache {
    val origin = Offset(size.width / 2f, size.height / 2f)
    val radiusX = size.width * .65f + 5.dp.toPx()
    val radiusY = size.height * .60f + 4.dp.toPx()
    val shade = Brush.radialGradient(
        0f to VideoControlShade.copy(alpha = .62f),
        .75f to VideoControlShade.copy(alpha = .62f),
        1f to VideoControlShade.copy(alpha = 0f),
        center = Offset.Zero, radius = 1f,
    )
    onDrawBehind {
        withTransform({
            translate(origin.x, origin.y)
            scale(radiusX, radiusY, Offset.Zero)
        }) { drawCircle(shade, radius = 1f, center = Offset.Zero) }
    }
}

@Composable
internal fun AnimePlaybackPanel(
    visible: Boolean,
    overVideo: Boolean,
    artwork: HazeState?,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    content: @Composable () -> Unit,
) = AnimePlaybackPanel(visible, overVideo, artwork, onDismiss, onClosed, 456.dp, onDismiss, content)

@Composable
internal fun AnimePlaybackPanel(
    visible: Boolean,
    overVideo: Boolean,
    artwork: HazeState?,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    maximumWidth: Dp,
    onWindowBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    EnhancementPanelHost(visible, artwork, onDismiss, onClosed, maximumWidth = maximumWidth,
        playbackOverlay = true, onWindowBack = onWindowBack,
        surface = { modifier, body ->
            AnimePlaybackSurface(artwork.takeIf { overVideo }, modifier, overVideo = overVideo,
                panel = true, cornerRadius = 24.dp, content = body)
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
    localizedTimelineInk: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accessibility = LocalGlassAccessibilitySettings.current
    val compactVideo = overVideo && !panel
    val solid = accessibility.reduceTransparency || accessibility.increaseContrast
    val clearVideo = compactVideo && !solid
    val source = artwork.takeUnless { solid }
    val base = if (clearVideo) VideoControlShade else colors.surface
    val ink = if (clearVideo) VideoControlInk else colors.onSurface
    val controls = remember(colors, compactVideo, clearVideo, accessibility.increaseContrast) {
        if (compactVideo) colors.copy(surface = base, onSurface = ink,
            surfaceContainerHighest = if (clearVideo) VideoControlBubble else colors.surfaceContainerHighest,
            onSurfaceVariant = when {
                accessibility.increaseContrast -> ink
                clearVideo -> VideoControlSecondaryInk
                else -> colors.onSurfaceVariant
            },
            primary = ink, onPrimary = base, inverseSurface = ink, inverseOnSurface = base)
        else colors
    }
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val fallback = remember(base, overVideo, solid, clearVideo, localizedTimelineInk) {
        val opacity = when {
            solid -> 1f
            overVideo -> .93f
            else -> .96f
        }
        if (clearVideo && localizedTimelineInk) TimelineWash
        else if (clearVideo) VideoControlScrim
        else Brush.verticalGradient(listOf(base.copy(alpha = minOf(1f, opacity + .035f)), base.copy(alpha = opacity)))
    }
    val edge = when {
        accessibility.showBorders || accessibility.increaseContrast -> Modifier.border(1.dp, ink.copy(alpha = .65f), shape)
        clearVideo && source == null -> Modifier.border(Dp.Hairline, VideoControlRim, shape)
        else -> Modifier
    }
    MaterialTheme(colorScheme = controls) {
        CompositionLocalProvider(LocalContentColor provides ink) {
            val shell = if (compactVideo) modifier else modifier.softSurfaceShadow(shape, base.luminance() > .5f,
                strength = .65f, radius = if (panel) 12.dp else 7.dp)
            Box(shell.clip(shape).then(edge), propagateMinConstraints = true) {
                if (source != null) {
                    LiquidGlassSurface(source, Modifier.matchParentSize(),
                        role = when {
                            compactVideo -> GlassSurfaceRole.VIDEO_DOCK
                            panel -> GlassSurfaceRole.PANEL
                            else -> GlassSurfaceRole.READING_DOCK
                        },
                        cornerRadius = cornerRadius, elevation = 0.dp, surfaceColor = base, contentColor = ink,
                        preferSource = true, softCard = false) { }
                }
                // Android's native SurfaceView shows through this layer without a video readback.
                // Applying the same scrim over live glass keeps its ink stable across source changes.
                if (clearVideo || source == null) Box(Modifier.matchParentSize().background(fallback))
                // Controls, focused items and scroll state survive the transition to the first native frame.
                content()
            }
        }
    }
}
