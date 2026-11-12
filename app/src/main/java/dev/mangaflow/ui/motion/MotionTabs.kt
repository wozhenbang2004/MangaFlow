package dev.mangaflow.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Ovio Minimal Liquid Tabs, 81a1bcb: two independent edges, with stationary labels/hit regions. */
@Composable
internal fun MotionTabs(
    selected: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    muted: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    track: Color = Color.Transparent,
    indicator: Color = foreground.copy(alpha = .10f),
    underline: Boolean = false,
    compact: Boolean = false,
    fontSize: TextUnit = 13.sp,
    indicatorHeight: Dp? = null,
    selectedFontWeight: FontWeight = FontWeight.Medium,
    unselectedFontWeight: FontWeight = FontWeight.Medium,
) {
    if (labels.isEmpty()) return
    val index = selected.coerceIn(labels.indices)
    val latest = rememberUpdatedState(index)
    val left = remember { Animatable(index.toFloat()) }
    val right = remember { Animatable(index + 1f) }
    val motion = LocalMotionEnabled.current
    val tabWidths = remember(labels.size) { mutableStateMapOf<Int, Int>() }
    val height = if (compact) 38.dp else 48.dp
    LaunchedEffect(motion, labels.size) {
        snapshotFlow { latest.value }.collect { target ->
            val toRight = target > left.value
            // Capture velocity before animateTo cancels its predecessor. A reverse tap continues here.
            val leftVelocity = left.velocity
            val rightVelocity = right.velocity
            launch {
                if (!motion) left.snapTo(target.toFloat())
                else left.animateTo(target.toFloat(), spring(
                    dampingRatio = if (toRight) .989f else 1.009f,
                    stiffness = if (toRight) 230f else 520f,
                ), initialVelocity = leftVelocity)
            }
            launch {
                if (!motion) right.snapTo(target + 1f)
                else right.animateTo(target + 1f, spring(
                    dampingRatio = if (toRight) 1.009f else .989f,
                    stiffness = if (toRight) 520f else 230f,
                ), initialVelocity = rightVelocity)
            }
        }
    }
    Row(modifier.fillMaxWidth().height(height)
        .clip(if (compact) RectangleShape else RoundedCornerShape(14.dp)).background(track)
        .drawBehind {
            val slot = size.width / labels.size
            val inset = if (underline) (slot * .26f).coerceAtMost(24.dp.toPx()) else 4.dp.toPx()
            val start: Float
            val end: Float
            if (compact) {
                if (tabWidths.size != labels.size) return@drawBehind
                var x = 0f
                val centers = labels.indices.map { i ->
                    val width = tabWidths.getValue(i).toFloat()
                    (x + width / 2f).also { x += width + 22.dp.toPx() }
                }
                fun centerAt(position: Float): Float {
                    val value = position.coerceIn(0f, labels.lastIndex.toFloat())
                    val from = value.toInt()
                    val to = (from + 1).coerceAtMost(labels.lastIndex)
                    return centers[from] + (centers[to] - centers[from]) * (value - from)
                }
                start = centerAt(left.value) - 16.dp.toPx()
                end = centerAt(right.value - 1f) + 16.dp.toPx()
            } else {
                start = left.value.coerceIn(0f, labels.size.toFloat()) * slot + inset
                end = right.value.coerceIn(0f, labels.size.toFloat()) * slot - inset
            }
            val width = (end - start).coerceAtLeast(2.dp.toPx())
            val stretch = (width / (slot - 2 * inset).coerceAtLeast(1f) - 1f).coerceAtLeast(0f)
            val h = if (underline || compact) 2.dp.toPx() else
                (indicatorHeight?.toPx() ?: (size.height - 8.dp.toPx())) * (1f - .06f * stretch).coerceAtLeast(.8f)
            val drawWidth = if (underline || compact) width else width.coerceAtLeast(h)
            drawRoundRect(indicator,
                topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - start - drawWidth else start,
                    if (underline || compact) size.height - h else (size.height - h) / 2),
                size = Size(drawWidth, h), cornerRadius = CornerRadius(h / 2))
        }.selectableGroup(),
        horizontalArrangement = if (compact) Arrangement.spacedBy(22.dp) else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val interaction = remember { MutableInteractionSource() }
            val tab = if (compact) Modifier.onSizeChanged { tabWidths[i] = it.width }
                else Modifier.weight(1f)
            Box(tab.height(height).selectable(
                selected = i == index, role = Role.Tab, interactionSource = interaction, indication = null,
                onClick = { onSelect(i) },
            ).then(if (compact) Modifier.padding(horizontal = 3.dp) else Modifier), contentAlignment = Alignment.Center) {
                Text(label, color = if (i == index) foreground else muted, fontSize = fontSize,
                    fontWeight = if (i == index) selectedFontWeight else unselectedFontWeight,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
