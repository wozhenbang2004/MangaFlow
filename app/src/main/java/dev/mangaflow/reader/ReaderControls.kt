package dev.mangaflow.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion

/** Reader controls follow the app theme, independently from the artwork background preference. */
@Composable
internal fun ReaderSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = shape,
        color = colors.surface.copy(alpha = .97f),
        contentColor = colors.onSurface,
        border = BorderStroke(.5.dp, colors.outlineVariant.copy(alpha = .45f)),
        shadowElevation = 2.dp,
        tonalElevation = 0.dp,
    ) {
        Box(Modifier.background(Brush.verticalGradient(listOf(
            colors.surface.copy(alpha = .35f), Color.Transparent,
        )))) { content() }
    }
}

@Composable
internal fun ReaderDialogSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setDimAmount(.12f) }
    ReaderSurface(modifier = modifier, content = content)
}

/** Keeps Material's seek, keyboard and accessibility behavior, with a quiet track and round thumb. */
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
    val motion = LocalMotionEnabled.current
    val engagement = animateFloatAsState(
        if (pressed || dragged) 1f else 0f,
        tween(if (motion) MangaMotion.Quick else 0), label = "reader-slider-engagement",
    )
    val fraction = ((value - valueRange.start) /
        (valueRange.endInclusive - valueRange.start).coerceAtLeast(.001f)).coerceIn(0f, 1f)
    val foreground = colors.onSurface.copy(alpha = if (enabled) .72f else .24f)
    Slider(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        valueRange = valueRange,
        interactionSource = interactions,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = label },
        thumb = {
            // The measured slot stays fixed; only the quiet halo grows during interaction.
            Canvas(Modifier.size(20.dp)) {
                drawCircle(foreground.copy(alpha = .10f * engagement.value), radius = 10.dp.toPx())
                drawCircle(colors.surface, radius = 7.dp.toPx())
                drawCircle(foreground, radius = (5f + engagement.value).dp.toPx())
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
