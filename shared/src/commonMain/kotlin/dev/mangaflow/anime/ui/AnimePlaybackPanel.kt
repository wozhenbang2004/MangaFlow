package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
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

internal val LocalClearVideoGlass = staticCompositionLocalOf { false }
private val VideoControlGlass = Color(0xFFF8FBFE)
private val VideoControlInk = Color(0xFF182027)
private val VideoControlSecondaryInk = Color(0xFF2B353D)
private val VideoGlassWash = Brush.verticalGradient(
    0f to Color.White.copy(alpha = .30f),
    .20f to Color.White.copy(alpha = .16f),
    .66f to Color.White.copy(alpha = .10f),
    1f to VideoControlGlass.copy(alpha = .21f),
)
private val VideoControlRim = Brush.linearGradient(
    0f to Color.White.copy(alpha = .78f),
    .34f to Color.White.copy(alpha = .21f),
    .70f to Color(0xFF45525C).copy(alpha = .16f),
    1f to Color.White.copy(alpha = .56f),
)
private val VideoPlayWash = Brush.verticalGradient(listOf(Color.White.copy(alpha = .78f), VideoControlGlass.copy(alpha = .54f)))
private val GlyphEdge = Color.White.copy(alpha = .91f)
private val GlyphEdgeFilter = ColorFilter.tint(GlyphEdge)

/** Outline the already shaped glyphs so black bars and bright footage can share a clear lens. */
@Composable
internal fun AnimeGlassLabel(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.labelMedium,
    color: Color = LocalContentColor.current, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) {
    val layout = if (LocalClearVideoGlass.current) remember { mutableStateOf<TextLayoutResult?>(null) } else null
    val edge = if (layout == null) Modifier else Modifier.drawWithCache {
        val stroke = Stroke(width = 1.6.dp.toPx(), join = StrokeJoin.Round)
        onDrawBehind {
            layout.value?.takeIf { it.layoutInput.text.text == text }?.let { drawText(it, color = GlyphEdge, drawStyle = stroke) }
        }
    }
    Text(text, modifier.then(edge), color = color, style = style, maxLines = maxLines, overflow = overflow,
        onTextLayout = { layout?.value = it })
}

@Composable
internal fun AnimeGlassIcon(image: ImageVector, description: String?, modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current) {
    val painter = rememberVectorPainter(image)
    val edge = if (!LocalClearVideoGlass.current) Modifier else Modifier.drawWithCache {
        val inset = .8.dp.toPx()
        onDrawBehind {
            with(painter) {
                translate(left = -inset) { draw(size, colorFilter = GlyphEdgeFilter) }
                translate(left = inset) { draw(size, colorFilter = GlyphEdgeFilter) }
                translate(top = -inset) { draw(size, colorFilter = GlyphEdgeFilter) }
                translate(top = inset) { draw(size, colorFilter = GlyphEdgeFilter) }
            }
        }
    }
    Icon(painter, description, modifier.then(edge), tint)
}

@Composable
internal fun AnimePlayFace(modifier: Modifier, content: @Composable (Color) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val clear = LocalClearVideoGlass.current
    val face = if (clear) Modifier.background(VideoPlayWash, CircleShape).border(Dp.Hairline, VideoControlRim, CircleShape)
        else Modifier.background(colors.onSurface, CircleShape)
    Box(modifier.then(face), contentAlignment = Alignment.Center) {
        content(if (clear) VideoControlInk else colors.surface)
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
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accessibility = LocalGlassAccessibilitySettings.current
    val compactVideo = overVideo && !panel
    val solid = accessibility.reduceTransparency || accessibility.increaseContrast
    val clearVideo = compactVideo && !solid
    val source = artwork.takeUnless { solid }
    val base = if (clearVideo) VideoControlGlass else colors.surface
    val ink = if (clearVideo) VideoControlInk else colors.onSurface
    val controls = remember(colors, compactVideo, clearVideo, accessibility.increaseContrast) {
        if (compactVideo) colors.copy(surface = base, onSurface = ink,
            surfaceContainerHighest = if (clearVideo) VideoControlGlass else colors.surfaceContainerHighest,
            onSurfaceVariant = when {
                accessibility.increaseContrast -> ink
                clearVideo -> VideoControlSecondaryInk
                else -> colors.onSurfaceVariant
            },
            primary = ink, onPrimary = base, inverseSurface = ink, inverseOnSurface = base)
        else colors
    }
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    val fallback = remember(base, overVideo, solid, clearVideo) {
        val opacity = when {
            solid -> 1f
            overVideo -> .93f
            else -> .96f
        }
        if (clearVideo) VideoGlassWash
        else Brush.verticalGradient(listOf(base.copy(alpha = minOf(1f, opacity + .035f)), base.copy(alpha = opacity)))
    }
    val edge = when {
        accessibility.showBorders || accessibility.increaseContrast -> Modifier.border(1.dp, ink.copy(alpha = .65f), shape)
        clearVideo -> Modifier.border(Dp.Hairline, VideoControlRim, shape)
        else -> Modifier
    }
    MaterialTheme(colorScheme = controls) {
        CompositionLocalProvider(LocalContentColor provides ink, LocalClearVideoGlass provides clearVideo) {
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
                // Keep a white surface reflection when moving between a native frame and a sampleable source.
                if (clearVideo || source == null) Box(Modifier.matchParentSize().background(fallback))
                // Controls, focused items and scroll state survive the transition to the first native frame.
                content()
            }
        }
    }
}
