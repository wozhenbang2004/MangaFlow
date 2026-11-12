package dev.mangaflow.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.glass.softSurfaceShadow
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlin.math.roundToInt

/** Reader controls follow the app theme, independently from the artwork background preference. */
@Composable
internal fun ReaderSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.softSurfaceShadow(shape, colors.surface.luminance() > .5f),
        shape = shape,
        color = lerp(colors.background, colors.surface, .8f),
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
    ) { content() }
}

@Composable
internal fun ReaderDialogSurface(
    modifier: Modifier = Modifier,
    backdrop: HazeState,
    content: @Composable () -> Unit,
) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(.12f) }
    LiquidGlassSurface(backdrop, modifier, role = GlassSurfaceRole.PANEL, softCard = true) { content() }
}

/** Keeps Material's seek, keyboard and accessibility behavior, with a quiet track and stable thumb slot. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    label: String,
    interactions: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colors = MaterialTheme.colorScheme
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val engaged = enabled && (pressed || dragged)
    val motion = LocalMotionEnabled.current
    val engagement = animateFloatAsState(
        if (engaged) 1f else 0f,
        tween(if (motion) MangaMotion.Quick else 0), label = "reader-slider-engagement",
    )
    val fraction = ((value - valueRange.start) /
        (valueRange.endInclusive - valueRange.start).coerceAtLeast(.001f)).coerceIn(0f, 1f)
    val foreground = colors.primary.copy(alpha = if (enabled) .86f else .24f)
    val pageCount = remember(label) {
        Regex("""共\s*(\d+)\s*页""").find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
    val previewText = pageCount?.let { count ->
        "${value.roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0)) + 1} / $count"
    } ?: "${(fraction * 100f).roundToInt()}%"
    val haptics = LocalHapticFeedback.current
    var lastStep by remember { mutableIntStateOf(value.roundToInt()) }
    LaunchedEffect(engaged, value, pageCount) {
        val step = value.roundToInt()
        val count = pageCount
        if (!engaged || count == null) {
            lastStep = step
        } else if (step != lastStep) {
            val stride = when {
                count <= 48 -> 1
                count <= 120 -> 2
                else -> 5
            }
            if (step == 0 || step == count - 1 || step / stride != lastStep / stride) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            lastStep = step
        }
    }
    val layoutDirection = LocalLayoutDirection.current
    val visualFraction = if (layoutDirection == LayoutDirection.Rtl) 1f - fraction else fraction
    BoxWithConstraints(modifier.height(48.dp)) {
        val bubbleWidth = 68.dp
        val travel = (maxWidth - 24.dp).coerceAtLeast(0.dp)
        val bubbleLimit = (maxWidth - bubbleWidth).coerceAtLeast(0.dp)
        val bubbleX = (12.dp + travel * visualFraction - bubbleWidth / 2f).coerceIn(0.dp, bubbleLimit)
        Surface(
            modifier = Modifier.width(bubbleWidth).height(24.dp).align(Alignment.TopStart)
                .offset(x = bubbleX, y = (-16).dp)
                .graphicsLayer {
                    alpha = engagement.value
                    val scale = .94f + engagement.value * .06f
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - engagement.value) * 4.dp.toPx()
                }
                .clearAndSetSemantics { },
            shape = RoundedCornerShape(9.dp),
            color = colors.surface.copy(alpha = .96f),
            contentColor = colors.onSurface,
            shadowElevation = if (engaged) 2.dp else 0.dp,
            tonalElevation = 0.dp,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(previewText, color = colors.onSurface.copy(alpha = .86f),
                    fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1)
            }
        }
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            valueRange = valueRange,
            interactionSource = interactions,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).align(Alignment.BottomCenter)
                .semantics { contentDescription = label },
            thumb = {
                // A stable hit slot surrounds a small solid thumb; the track has no glass optics.
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(20.dp)) {
                        drawCircle(foreground.copy(alpha = .10f * engagement.value), radius = 10.dp.toPx())
                        drawCircle(colors.surface, radius = 7.dp.toPx())
                        drawCircle(foreground, radius = (4.5f + engagement.value).dp.toPx())
                    }
                }
            },
            track = {
                Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                    val middle = size.height / 2f
                    val start = if (layoutDirection == LayoutDirection.Rtl) size.width else 0f
                    val end = if (layoutDirection == LayoutDirection.Rtl) 0f else size.width
                    val width = (2f + engagement.value * .5f).dp.toPx()
                    drawLine(colors.onSurface.copy(alpha = .12f), Offset(start, middle),
                        Offset(end, middle), width, StrokeCap.Round)
                    if (fraction > 0f) drawLine(foreground, Offset(start, middle),
                        Offset(start + (end - start) * fraction, middle), width, StrokeCap.Round)
                }
            },
        )
    }
}

@Composable
internal fun ReaderToggle(
    label: String,
    value: Boolean,
    description: String? = null,
    update: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val position = animateFloatAsState(if (value) 1f else 0f,
        tween(if (motion) MangaMotion.Quick else 0, easing = MangaMotion.EnterEasing), label = "reader-toggle")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .toggleable(value = value, role = Role.Switch, onValueChange = update)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, color = colors.onSurface, fontSize = 14.sp)
            if (description != null) Text(description, color = colors.onSurfaceVariant, fontSize = 11.sp,
                lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Box(Modifier.width(36.dp).height(22.dp).drawBehind {
            val p = position.value
            drawRoundRect(colors.onSurface.copy(alpha = .13f + .70f * p),
                cornerRadius = CornerRadius(size.height / 2f), size = size)
            val radius = 8.dp.toPx()
            val x = 3.dp.toPx() + radius + (size.width - 6.dp.toPx() - radius * 2f) *
                (if (layoutDirection == LayoutDirection.Rtl) (1f - p) else p)
            drawCircle(colors.surface, radius, Offset(x, size.height / 2f))
        })
    }
}
