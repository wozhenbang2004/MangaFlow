package dev.mangaflow.ui.motion

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.ui.DesktopIconTooltip
import kotlinx.coroutines.launch

internal enum class MotionTabTreatment { Capsule, Soft, Underline }

/** Vertical adaptation of the same Ovio two-edge motion; labels and hit cells stay stationary. */
@Composable
internal fun MotionNavigationColumn(
    selected: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    selectionVisible: Boolean = true,
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
    Column(modifier.fillMaxWidth().padding(vertical = 3.dp).drawWithCache {
        if (!selectionVisible || heights.size != itemCount) return@drawWithCache onDrawBehind { }
        var y = 0f
        val gap = 6.dp.roundToPx().toFloat()
        val starts = FloatArray(itemCount) { i ->
            y.also { y += heights.getValue(i) + gap }
        }
        val ends = FloatArray(itemCount) { i -> starts[i] + heights.getValue(i) }
        val restingHeight = heights.getValue(index).toFloat().coerceAtLeast(1f)
        val cornerRadius = CornerRadius(14.dp.toPx())
        fun interpolate(position: Float, values: FloatArray): Float {
            val value = position.coerceIn(0f, itemCount - 1f)
            val from = value.toInt()
            val to = (from + 1).coerceAtMost(itemCount - 1)
            return values[from] + (values[to] - values[from]) * (value - from)
        }
        onDrawBehind {
            val start = interpolate(top.value, starts)
            val end = interpolate(bottom.value - 1f, ends)
            // A long jump should travel between rows, not turn into a full-column selection panel.
            val height = (end - start).coerceIn(restingHeight * .9f, restingHeight * 1.22f)
            val offset = ((start + end - height) / 2f).coerceIn(0f, (size.height - height).coerceAtLeast(0f))
            drawRoundRect(indicator, topLeft = Offset(0f, offset), size = Size(size.width, height),
                cornerRadius = cornerRadius)
        }
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
    selectionVisible: Boolean = true,
    tooltips: List<String>? = null,
    selectionBackdrop: HazeState? = null,
    scrubPosition: State<Float?>? = null,
    itemContent: (@Composable (index: Int, selected: Boolean, color: Color) -> Unit)? = null,
) {
    if (labels.isEmpty()) return
    val index = selected.coerceIn(labels.indices)
    val inGlassGroup = selectionBackdrop != null
    val motion = LocalMotionEnabled.current
    val selectionAlpha = animateFloatAsState(if (selectionVisible) 1f else 0f,
        if (motion) tween(MangaMotion.Quick) else snap(), label = "tab-selection-presence")
    val feedback = rememberFeelFeedback()
    val accessibility = if (inGlassGroup) LocalGlassAccessibilitySettings.current else null
    val surface = MaterialTheme.colorScheme.surface
    val groupTint = when {
        !inGlassGroup -> indicator
        accessibility?.increaseContrast == true -> lerp(surface, foreground, .36f)
        accessibility?.reduceTransparency == true -> lerp(surface, foreground, .18f)
        surface.luminance() > .5f -> Color.White.copy(alpha = .20f)
        else -> foreground.copy(alpha = .15f)
    }
    val inactiveInk = when {
        accessibility?.increaseContrast == true -> foreground
        inGlassGroup -> foreground.copy(alpha = .90f)
        else -> muted
    }
    val movingGroupFill = inGlassGroup && !compact && scrubPosition == null && treatment != MotionTabTreatment.Underline
    val selectionInteractions = remember(labels.size) { List(labels.size) { MutableInteractionSource() } }
    val groupPresses = if (movingGroupFill) selectionInteractions.map { it.collectIsPressedAsState() } else emptyList()
    val tabWidths = remember(labels.size) { mutableStateMapOf<Int, Int>() }
    val textMeasurer = rememberTextMeasurer()
    val textStyle = LocalTextStyle.current.copy(fontSize = fontSize)
    val density = LocalDensity.current
    val wrapLabels = controlHeight != null && treatment != MotionTabTreatment.Underline &&
        itemContent == null && density.fontScale > 1.3f
    val height = maxOf(controlHeight ?: if (compact) 38.dp else 48.dp,
        with(density) { fontSize.toDp() * (if (wrapLabels) 2.6f else 1.3f) + 16.dp })
    // Keep font resolution observable while reusing metrics through spring/color frames.
    val labelWidths by remember(labels, textMeasurer, textStyle, selectedFontWeight, unselectedFontWeight) {
        derivedStateOf {
            labels.map { label ->
                maxOf(
                    textMeasurer.measure(label, textStyle.copy(fontWeight = selectedFontWeight), maxLines = 1).size.width,
                    textMeasurer.measure(label, textStyle.copy(fontWeight = unselectedFontWeight), maxLines = 1).size.width,
                ).toFloat()
            }
        }
    }
    val frame = modifier.fillMaxWidth().height(height)
        .clip(if (compact) RectangleShape else RoundedCornerShape(14.dp)).background(track)
    val selectionInk = if (movingGroupFill) Modifier else {
        val latest = rememberUpdatedState(index)
        val left = remember { Animatable(index.toFloat()) }
        val right = remember { Animatable(index + 1f) }
        // A scrub rail owns its one settling animation; the default tabs keep the two-edge motion.
        if (scrubPosition == null) LaunchedEffect(motion, labels.size) {
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
        Modifier.drawWithCache {
            val slot = size.width / labels.size
            if (compact && tabWidths.size != labels.size) return@drawWithCache onDrawBehind { }
            var x = if (compact) 10.dp.toPx() else 0f
            val centers = FloatArray(labels.size) { i ->
                if (compact) {
                    val width = tabWidths.getValue(i).toFloat()
                    (x + width / 2f).also { x += width + 22.dp.toPx() }
                } else slot * (i + .5f)
            }
            fun interpolate(position: Float, values: FloatArray): Float {
                val value = position.coerceIn(0f, labels.lastIndex.toFloat())
                val from = value.toInt()
                val to = (from + 1).coerceAtMost(labels.lastIndex)
                return values[from] + (values[to] - values[from]) * (value - from)
            }
            val widths = FloatArray(labels.size) { i ->
                (labelWidths[i] + (if (treatment == MotionTabTreatment.Underline) 4.dp else 24.dp).toPx()).coerceAtMost(
                    if (compact) labelWidths[i] + 24.dp.toPx() else (slot - 6.dp.toPx()).coerceAtLeast(1f),
                )
            }
            val inset = 4.dp.toPx().coerceAtMost(slot / 2f)
            val fillSegment = fullWidthIndicator && !compact
            onDrawBehind {
                // Only the indicator follows the live drag. Labels and semantics own the settled tab.
                val scrub = scrubPosition?.value
                val leading = scrub ?: if (scrubPosition != null) index.toFloat() else left.value
                val trailing = scrub?.plus(1f) ?: if (scrubPosition != null) index + 1f else right.value
                val alpha = if (scrubPosition == null) selectionAlpha.value
                    else if (scrub != null || selectionVisible) 1f else 0f
                val start = if (fillSegment) leading.coerceIn(0f, labels.size.toFloat()) * slot + inset
                    else interpolate(leading, centers) - interpolate(leading, widths) / 2f
                val end = if (fillSegment) trailing.coerceIn(0f, labels.size.toFloat()) * slot - inset
                    else interpolate(trailing - 1f, centers) + interpolate(trailing - 1f, widths) / 2f
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
                val tint = if (inGlassGroup) groupTint
                else when (treatment) {
                    MotionTabTreatment.Underline, MotionTabTreatment.Soft -> indicator
                    MotionTabTreatment.Capsule -> if (underline || compact) foreground.copy(alpha = .065f) else indicator
                }
                drawRoundRect(tint,
                    topLeft = topLeft, size = pillSize, cornerRadius = radius, alpha = alpha)
            }
        }
    }
    GlassTabRail(frame, if (movingGroupFill) selectionBackdrop else null, labels, index, selectionVisible,
        groupTint, selectionAlpha, pressed = selectionVisible && groupPresses.getOrNull(index)?.value == true,
        onSelect = { target -> feedback.perform(FeelEvent.Selection); onSelect(target) }) { rowFrame ->
        Row(rowFrame.then(selectionInk).padding(horizontal = if (compact) 10.dp else 0.dp).selectableGroup(),
            horizontalArrangement = if (compact) Arrangement.spacedBy(22.dp) else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { i, label ->
                val isSelected = selectionVisible && i == index
                val interaction = selectionInteractions[i]
                val pressed = if (movingGroupFill) groupPresses[i].value else interaction.collectIsPressedAsState().value
                val focused = inGlassGroup && interaction.collectIsFocusedAsState().value
                val hovered = inGlassGroup && interaction.collectIsHoveredAsState().value
                val pressAlpha = animateFloatAsState(if (pressed) .06f else if (hovered) .035f else 0f,
                    if (motion) tween(MangaMotion.Quick) else snap(), label = "tab-press")
                val labelColor by animateColorAsState(if (isSelected) foreground else inactiveInk,
                    if (motion) tween(MangaMotion.Quick) else snap(), label = "tab-label")
                // Reserve both weights so selecting a compact tab cannot shift its neighbours.
                val labelWidth = if (compact && itemContent == null) with(density) {
                    labelWidths[i].toDp()
                } else 0.dp
                val tab = if (compact) Modifier.onSizeChanged { tabWidths[i] = it.width }
                    else Modifier.weight(1f)
                Box(tab.height(height).selectable(
                    selected = isSelected, role = role, interactionSource = interaction, indication = null,
                    onClick = { if (!isSelected) feedback.perform(FeelEvent.Selection); onSelect(i) },
                ).then(if (movingGroupFill) Modifier else Modifier.motionPress(interaction)).drawBehind {
                    val touchAlpha = if (!movingGroupFill || !isSelected) pressAlpha.value else 0f
                    if (touchAlpha > 0f || focused) {
                        val x = if (movingGroupFill) GlassTabHorizontalInset.toPx() else 0f
                        val y = if (movingGroupFill) GlassTabVerticalInset.toPx() else 0f
                        val start = Offset(x, y)
                        val area = Size((size.width - 2 * x).coerceAtLeast(0f), (size.height - 2 * y).coerceAtLeast(0f))
                        val pill = CornerRadius(area.height / 2f)
                        if (touchAlpha > 0f) drawRoundRect(foreground.copy(alpha = touchAlpha), start, area,
                            cornerRadius = if (movingGroupFill) pill else CornerRadius(10.dp.toPx()))
                        if (focused) drawRoundRect(foreground.copy(alpha = .72f), start, area,
                            cornerRadius = pill, style = Stroke(1.dp.toPx()))
                    }
                }.then(if (compact) Modifier.widthIn(min = labelWidth + 6.dp).padding(horizontal = 3.dp) else Modifier),
                    contentAlignment = Alignment.Center) {
                    if (itemContent != null) itemContent(i, isSelected, labelColor)
                    else Text(label, color = labelColor, fontSize = fontSize,
                            modifier = if (controlHeight != null) Modifier.padding(
                                horizontal = if (treatment == MotionTabTreatment.Capsule) 8.dp else 2.dp) else Modifier,
                            textAlign = TextAlign.Center,
                            fontWeight = if (isSelected) selectedFontWeight else unselectedFontWeight,
                            maxLines = if (wrapLabels) 2 else 1, overflow = TextOverflow.Ellipsis)
                    DesktopIconTooltip(tooltips?.getOrNull(i), interaction, tooltips != null)
                }
            }
        }
    }
}
