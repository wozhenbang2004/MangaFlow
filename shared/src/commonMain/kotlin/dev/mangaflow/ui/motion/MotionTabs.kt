package dev.mangaflow.ui.motion

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

internal enum class MotionTabTreatment { Capsule, Soft, Underline }

/** Vertical adaptation of the same Ovio two-edge motion; labels and hit cells stay stationary. */
@Composable
internal fun MotionNavigationColumn(
    selected: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    item: @Composable (Int, Modifier) -> Unit,
) {
    if (itemCount <= 0) return
    val index = selected.coerceIn(0, itemCount - 1)
    val latest = rememberUpdatedState(index)
    val top = remember { Animatable(index.toFloat()) }
    val bottom = remember { Animatable(index + 1f) }
    val heights = remember(itemCount) { mutableStateMapOf<Int, Int>() }
    val motion = LocalMotionEnabled.current
    val indicator = MaterialTheme.colorScheme.onSurface.copy(alpha = .075f)
    LaunchedEffect(motion, itemCount) {
        snapshotFlow { latest.value }.collect { target ->
            val down = target > top.value
            val topVelocity = top.velocity
            val bottomVelocity = bottom.velocity
            launch {
                if (!motion) top.snapTo(target.toFloat())
                else top.animateTo(target.toFloat(), spring(
                    dampingRatio = if (down) MangaMotion.TabTrailingDamping else MangaMotion.TabLeadingDamping,
                    stiffness = if (down) MangaMotion.TabTrailingStiffness else MangaMotion.TabLeadingStiffness,
                ), initialVelocity = topVelocity)
            }
            launch {
                if (!motion) bottom.snapTo(target + 1f)
                else bottom.animateTo(target + 1f, spring(
                    dampingRatio = if (down) MangaMotion.TabLeadingDamping else MangaMotion.TabTrailingDamping,
                    stiffness = if (down) MangaMotion.TabLeadingStiffness else MangaMotion.TabTrailingStiffness,
                ), initialVelocity = bottomVelocity)
            }
        }
    }
    Column(modifier.fillMaxWidth().padding(vertical = 3.dp).drawBehind {
        if (heights.size != itemCount) return@drawBehind
        var y = 0f
        val gap = 6.dp.roundToPx().toFloat()
        val starts = (0 until itemCount).map { i ->
            y.also { y += heights.getValue(i) + gap }
        }
        val ends = starts.mapIndexed { i, start -> start + heights.getValue(i) }
        fun interpolate(position: Float, values: List<Float>): Float {
            val value = position.coerceIn(0f, itemCount - 1f)
            val from = value.toInt()
            val to = (from + 1).coerceAtMost(itemCount - 1)
            return values[from] + (values[to] - values[from]) * (value - from)
        }
        val start = interpolate(top.value, starts)
        val end = interpolate(bottom.value - 1f, ends)
        val restingHeight = heights.getValue(index).toFloat().coerceAtLeast(1f)
        // A long jump should travel between rows, not turn into a full-column selection panel.
        val height = (end - start).coerceIn(restingHeight * .9f, restingHeight * 1.22f)
        val offset = ((start + end - height) / 2f).coerceIn(0f, (size.height - height).coerceAtLeast(0f))
        drawRoundRect(indicator, topLeft = Offset(0f, offset), size = Size(size.width, height),
            cornerRadius = CornerRadius(14.dp.toPx()))
    }.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(itemCount) { i -> item(i, Modifier.onSizeChanged { heights[i] = it.height }) }
    }
}

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
    fullWidthIndicator: Boolean = false,
    controlHeight: Dp? = null,
    treatment: MotionTabTreatment = MotionTabTreatment.Capsule,
    fontSize: TextUnit = 13.sp,
    indicatorHeight: Dp? = null,
    indicatorCornerRadius: Dp? = null,
    selectedFontWeight: FontWeight = FontWeight.Medium,
    unselectedFontWeight: FontWeight = FontWeight.Medium,
    role: Role = Role.Tab,
    itemContent: (@Composable (index: Int, selected: Boolean, color: Color) -> Unit)? = null,
) {
    if (labels.isEmpty()) return
    val index = selected.coerceIn(labels.indices)
    val latest = rememberUpdatedState(index)
    val left = remember { Animatable(index.toFloat()) }
    val right = remember { Animatable(index + 1f) }
    val motion = LocalMotionEnabled.current
    val feedback = rememberFeelFeedback()
    // Capsule treatment owns its restrained optical indicator; no user-selectable glass skin.
    val liquid = treatment == MotionTabTreatment.Capsule
    val light = MaterialTheme.colorScheme.surface.luminance() > .5f
    val tabWidths = remember(labels.size) { mutableStateMapOf<Int, Int>() }
    val textMeasurer = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current.copy(fontSize = fontSize)
    val density = LocalDensity.current
    val wrapLabels = controlHeight != null && treatment != MotionTabTreatment.Underline &&
        itemContent == null && density.fontScale > 1.3f
    val height = maxOf(controlHeight ?: if (compact) 38.dp else 48.dp,
        with(density) { fontSize.toDp() * (if (wrapLabels) 2.6f else 1.3f) + 16.dp })
    val labelWidths = labels.map { label ->
        maxOf(
            textMeasurer.measure(label, textStyle.copy(fontWeight = selectedFontWeight), maxLines = 1).size.width,
            textMeasurer.measure(label, textStyle.copy(fontWeight = unselectedFontWeight), maxLines = 1).size.width,
        ).toFloat()
    }
    LaunchedEffect(motion, labels.size) {
        snapshotFlow { latest.value }.collect { target ->
            val toRight = target > left.value
            // Capture velocity before animateTo cancels its predecessor. A reverse tap continues here.
            val leftVelocity = left.velocity
            val rightVelocity = right.velocity
            launch {
                if (!motion) left.snapTo(target.toFloat())
                else left.animateTo(target.toFloat(), spring(
                    dampingRatio = if (toRight) MangaMotion.TabTrailingDamping else MangaMotion.TabLeadingDamping,
                    stiffness = if (toRight) MangaMotion.TabTrailingStiffness else MangaMotion.TabLeadingStiffness,
                ), initialVelocity = leftVelocity)
            }
            launch {
                if (!motion) right.snapTo(target + 1f)
                else right.animateTo(target + 1f, spring(
                    dampingRatio = if (toRight) MangaMotion.TabLeadingDamping else MangaMotion.TabTrailingDamping,
                    stiffness = if (toRight) MangaMotion.TabLeadingStiffness else MangaMotion.TabTrailingStiffness,
                ), initialVelocity = rightVelocity)
            }
        }
    }
    Row(modifier.fillMaxWidth().height(height)
        .clip(if (compact) RectangleShape else RoundedCornerShape(14.dp)).background(track)
        .drawBehind {
            val slot = size.width / labels.size
            if (compact && tabWidths.size != labels.size) return@drawBehind
            var x = if (compact) 10.dp.toPx() else 0f
            val centers = labels.indices.map { i ->
                if (compact) {
                    val width = tabWidths.getValue(i).toFloat()
                    (x + width / 2f).also { x += width + 22.dp.toPx() }
                } else slot * (i + .5f)
            }
            fun interpolate(position: Float, values: List<Float>): Float {
                val value = position.coerceIn(0f, labels.lastIndex.toFloat())
                val from = value.toInt()
                val to = (from + 1).coerceAtMost(labels.lastIndex)
                return values[from] + (values[to] - values[from]) * (value - from)
            }
            val widths = labels.indices.map { i ->
                (labelWidths[i] + (if (treatment == MotionTabTreatment.Underline) 4.dp else 24.dp).toPx()).coerceAtMost(
                    if (compact) labelWidths[i] + 24.dp.toPx() else (slot - 6.dp.toPx()).coerceAtLeast(1f),
                )
            }
            val inset = 4.dp.toPx().coerceAtMost(slot / 2f)
            val fillSegment = fullWidthIndicator && !compact
            val start = if (fillSegment) left.value.coerceIn(0f, labels.size.toFloat()) * slot + inset
                else interpolate(left.value, centers) - interpolate(left.value, widths) / 2f
            val end = if (fillSegment) right.value.coerceIn(0f, labels.size.toFloat()) * slot - inset
                else interpolate(right.value - 1f, centers) + interpolate(right.value - 1f, widths) / 2f
            val width = (end - start).coerceAtLeast(2.dp.toPx())
            val restingWidth = if (fillSegment) slot - inset * 2f else widths[index]
            val stretch = (width / restingWidth.coerceAtLeast(1f) - 1f).coerceAtLeast(0f)
            val h = (indicatorHeight?.toPx() ?: if (treatment == MotionTabTreatment.Underline) 2.dp.toPx()
                else if (fillSegment) size.height - 8.dp.toPx()
                else (if (compact) 28.dp else 32.dp).toPx()).coerceAtLeast(1f) *
                (1f - .06f * stretch).coerceAtLeast(.8f)
            val topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - end else start,
                if (treatment == MotionTabTreatment.Underline) size.height - h - 5.dp.toPx() else (size.height - h) / 2)
            val pillSize = Size(width, h)
            val radius = CornerRadius(indicatorCornerRadius?.toPx()
                ?: if (treatment == MotionTabTreatment.Soft) 10.dp.toPx() else h / 2)
            val tint = when (treatment) {
                MotionTabTreatment.Underline, MotionTabTreatment.Soft -> indicator
                MotionTabTreatment.Capsule -> if (underline || compact) foreground.copy(alpha = .065f) else indicator
            }
            drawRoundRect(tint,
                topLeft = topLeft, size = pillSize, cornerRadius = radius)
            // A small glass rim on the flat navigation surface; text stays outside the material.
            // Backdrop blur/refraction remains in the existing floating controls over artwork.
            if (liquid && treatment == MotionTabTreatment.Capsule) {
                drawRoundRect(Brush.linearGradient(
                    listOf(Color.White.copy(alpha = if (light) .52f else .10f), Color.Transparent,
                        foreground.copy(alpha = .025f)),
                    start = topLeft, end = topLeft + Offset(width * .35f, h)),
                    topLeft = topLeft, size = pillSize, cornerRadius = radius)
            }
            if (treatment == MotionTabTreatment.Capsule) drawRoundRect(foreground.copy(alpha = if (liquid) .07f else .04f),
                topLeft = topLeft, size = pillSize, cornerRadius = radius, style = Stroke(.6.dp.toPx()))
        }.padding(horizontal = if (compact) 10.dp else 0.dp).selectableGroup(),
        horizontalArrangement = if (compact) Arrangement.spacedBy(22.dp) else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { i, label ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val pressAlpha = animateFloatAsState(if (pressed) .06f else 0f,
                if (motion) tween(MangaMotion.Quick) else snap(), label = "tab-press")
            val labelColor by animateColorAsState(if (i == index) foreground else muted,
                if (motion) tween(MangaMotion.Quick) else snap(), label = "tab-label")
            // Reserve both weights so selecting a compact tab cannot shift its neighbours.
            val labelWidth = if (compact && itemContent == null) with(density) {
                maxOf(
                    textMeasurer.measure(label, textStyle.copy(fontWeight = selectedFontWeight), maxLines = 1).size.width,
                    textMeasurer.measure(label, textStyle.copy(fontWeight = unselectedFontWeight), maxLines = 1).size.width,
                ).toDp()
            } else 0.dp
            val tab = if (compact) Modifier.onSizeChanged { tabWidths[i] = it.width }
                else Modifier.weight(1f)
            Box(tab.height(height).selectable(
                selected = i == index, role = role, interactionSource = interaction, indication = null,
                onClick = { if (i != index) feedback.perform(FeelEvent.Selection); onSelect(i) },
            ).drawBehind {
                drawRoundRect(foreground.copy(alpha = pressAlpha.value), cornerRadius = CornerRadius(10.dp.toPx()))
            }.then(if (compact) Modifier.widthIn(min = labelWidth + 6.dp).padding(horizontal = 3.dp) else Modifier),
                contentAlignment = Alignment.Center) {
                if (itemContent != null) itemContent(i, i == index, labelColor)
                else Text(label, color = labelColor, fontSize = fontSize,
                        modifier = if (controlHeight != null) Modifier.padding(
                            horizontal = if (treatment == MotionTabTreatment.Capsule) 8.dp else 2.dp) else Modifier,
                        textAlign = TextAlign.Center,
                        fontWeight = if (i == index) selectedFontWeight else unselectedFontWeight,
                        maxLines = if (wrapLabels) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
