/*
 * Tracking/value-view adaptation of Ramotion Fluid Slider 1.1.0:
 * https://github.com/Ramotion/fluid-slider/tree/98c787c0d4cf841213111242ee42897b8542ec6a
 * Slider.swift: clamped fraction/position and begin/end/cancel tracking.
 * ValueView.swift: a tracking-only value, spring lift and 0.22s return.
 * The Compose port uses a small vector neck instead of CoreImage blur/threshold;
 * it does not claim identical iOS optics. Thumb stretch follows the saved
 * press-stretch switch adaptation already used by MotionSwitchThumb.
 * Ramotion: https://www.ramotion.com/
 *
 * MIT License
 * Copyright (c) 2017 Ramotion
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.mangaflow.ui.motion

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.ui.mangaNumberText
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Controlled value: the caller owns preview updates and commits on finish.
 * Material supplies input, cancellation, RTL, keyboard and progress semantics;
 * its default track, thumb, gaps and stop marker are never rendered here.
 * The tracking bubble draws outside the stable 48dp control; callers should not clip it.
 * Optional buffering is an observed interval in valueRange units, independent of a dragged value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MotionSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    formatValue: (Float) -> String = { ((it * 100f).roundToInt() / 100f).toString() },
    bufferedRange: ClosedFloatingPointRange<Float>? = null,
    trackOutline: Color = Color.Unspecified,
    showValueBubble: Boolean = true,
) {
    require(valueRange.start.isFinite() && valueRange.endInclusive.isFinite() &&
        valueRange.start <= valueRange.endInclusive) { "Slider range must be finite and ordered" }
    val current = if (value.isFinite()) value.coerceIn(valueRange) else valueRange.start
    val fraction = if (valueRange.start < valueRange.endInclusive)
        ((current - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f) else 0f
    val buffered = bufferedRange?.takeIf {
        it.start.isFinite() && it.endInclusive.isFinite() && it.start < it.endInclusive &&
            valueRange.start < valueRange.endInclusive
    }?.let { it.start.coerceIn(valueRange)..it.endInclusive.coerceIn(valueRange) }
        ?.takeIf { it.start < it.endInclusive }
    val available = enabled && valueRange.start < valueRange.endInclusive
    val outlined = trackOutline != Color.Unspecified
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tracking = available && (pressed || dragged)
    val desktop = platformKind == PlatformKind.DESKTOP
    var adjustmentKeyDown by remember(interaction) { mutableStateOf(false) }
    var keyboardChange by remember(interaction, valueRange) { mutableStateOf<Any?>(null) }
    if (desktop && showValueBubble) LaunchedEffect(keyboardChange, focused, tracking, available) {
        if (!focused || tracking || !available) adjustmentKeyDown = false
        if (keyboardChange != null) {
            if (focused && !tracking && available) delay(900L)
            keyboardChange = null
        }
    }
    val inspectValue = showValueBubble && (tracking || desktop && focused && available && keyboardChange != null)
    val motion = LocalMotionEnabled.current
    val gesture = updateTransition(inspectValue, label = "slider-contact")
    val lift = gesture.animateFloat(transitionSpec = {
        if (!motion) snap() else spring(dampingRatio = if (targetState) .86f else 1f,
            stiffness = if (targetState) MangaMotion.GestureStiffness else MangaMotion.PanelStiffness)
    }, label = "slider-value-lift") { if (it) 1f else 0f }
    val stretch = animateFloatAsState(if (tracking && motion) 1f else 0f,
        if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(), label = "slider-thumb-pressure")
    val showValue = available && (inspectValue || gesture.currentState || gesture.isRunning)
    val feedback = rememberFeelFeedback()
    var edge by remember(interaction, valueRange, tracking) {
        mutableIntStateOf(if (fraction <= .025f) -1 else if (fraction >= .975f) 1 else 0)
    }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val label = formatValue(current)
    var lastLabel by remember(valueRange) { mutableStateOf(label) }
    var lastFraction by remember(valueRange) { mutableFloatStateOf(fraction) }
    if (inspectValue) SideEffect { lastLabel = label; lastFraction = fraction }
    val follow = if (motion && (tracking || showValue)) animateFloatAsState(if (tracking || inspectValue) fraction else lastFraction,
        spring(.92f, MangaMotion.NavigationStiffness, visibilityThreshold = .0005f),
        label = "slider-value-follow") else null
    val accessibilityLabel = buffered?.let {
        "$label，已缓冲 ${formatValue(it.start)} 至 ${formatValue(it.endInclusive)}"
    } ?: label

    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        val sliderWidth = with(density) { maxWidth.toPx() }
        val labelWidth = with(density) { (maxWidth - 28.dp).roundToPx().coerceAtLeast(1) }
        // Playback and buffering change while idle. Measuring text and running the follow
        // spring are only needed while the user can actually see this label.
        val labelLayout = if (showValue) {
            val textMeasurer = rememberTextMeasurer()
            val text = mangaNumberText(if (inspectValue) label else lastLabel)
            val style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium)
            remember(text, style, textMeasurer, labelWidth) { textMeasurer.measure(text, style = style, maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = labelWidth)) }
        } else null
        val link = if (showValue) remember { Path() } else null
        Slider(value = current, onValueChange = {
            val next = it.coerceIn(valueRange)
            if (tracking && available) {
                val limit = when (next) {
                    valueRange.start -> -1
                    valueRange.endInclusive -> 1
                    else -> 0
                }
                val position = (next - valueRange.start) / (valueRange.endInclusive - valueRange.start)
                if (limit != 0) {
                    if (limit != edge && next != current) feedback.perform(FeelEvent.Threshold)
                    edge = limit
                } else if (position >= .025f && position <= .975f) edge = 0
            }
            if (desktop && showValueBubble && adjustmentKeyDown && !tracking && next != current) keyboardChange = Any()
            onValueChange(next)
        },
            valueRange = valueRange, enabled = available,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().then(if (!desktop || !showValueBubble) Modifier else Modifier.onPreviewKeyEvent { event ->
                adjustmentKeyDown = event.type == KeyEventType.KeyDown && when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
                    Key.MoveHome, Key.MoveEnd, Key.PageUp, Key.PageDown -> true
                    else -> false
                }
                false
            }).semantics { stateDescription = accessibilityLabel },
            thumb = { _ ->
                Canvas(Modifier.size(28.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val strength = if (available) 1f else .38f
                    val pressure = stretch.value.coerceIn(0f, 1f)
                    val travelWidth = (sliderWidth - size.width).coerceAtLeast(0f)
                    val lag = ((follow?.value ?: fraction) - fraction) * travelWidth * (if (rtl) -1f else 1f)
                    val bend = lag.coerceIn(-10.dp.toPx(), 10.dp.toPx()) * pressure
                    val thumbWidth = (16f + pressure * 2f).dp.toPx() + abs(bend) * .22f
                    val thumbHeight = (16f - pressure).dp.toPx()
                    val thumbTop = Offset(center.x - thumbWidth / 2f, center.y - thumbHeight / 2f)
                    if (available && focused) {
                        val focusTop = thumbTop - Offset(4.dp.toPx(), 4.dp.toPx())
                        val focusSize = Size(thumbWidth + 8.dp.toPx(), thumbHeight + 8.dp.toPx())
                        val radius = CornerRadius(12.dp.toPx())
                        if (outlined) drawRoundRect(trackOutline, focusTop, focusSize, radius, style = Stroke(3.dp.toPx()))
                        drawRoundRect(colors.onSurface.copy(alpha = if (outlined) .9f else .16f),
                            focusTop, focusSize, radius, style = Stroke(1.dp.toPx()))
                    }
                    if (outlined) drawRoundRect(trackOutline.copy(alpha = .86f * strength),
                        topLeft = thumbTop - Offset(1.dp.toPx(), 1.dp.toPx()),
                        size = Size(thumbWidth + 2.dp.toPx(), thumbHeight + 2.dp.toPx()),
                        cornerRadius = CornerRadius(thumbHeight / 2f + 1.dp.toPx()))
                    drawRoundRect(colors.onSurface.copy(alpha = .08f * strength),
                        topLeft = thumbTop + Offset(0f, 1.5.dp.toPx()),
                        size = Size(thumbWidth, thumbHeight),
                        cornerRadius = CornerRadius(thumbHeight / 2f))
                    drawRoundRect(colors.onSurface.copy(alpha = .18f * strength),
                        topLeft = thumbTop, size = Size(thumbWidth, thumbHeight),
                        cornerRadius = CornerRadius(thumbHeight / 2f))
                    drawRoundRect(colors.surface.copy(alpha = strength),
                        topLeft = thumbTop + Offset(.8.dp.toPx(), .8.dp.toPx()),
                        size = Size(thumbWidth - 1.6.dp.toPx(), thumbHeight - 1.6.dp.toPx()),
                        cornerRadius = CornerRadius(thumbHeight / 2f))

                    val progress = lift.value.coerceIn(0f, 1.12f)
                    val alpha = progress.coerceIn(0f, 1f)
                    if (alpha <= .001f || labelLayout == null || link == null) return@Canvas
                    val bubbleWidth = minOf(labelLayout.size.width + 16.dp.toPx(), sliderWidth.coerceAtLeast(1f))
                    val bubbleHeight = maxOf(24.dp.toPx(), labelLayout.size.height + 8.dp.toPx())
                    val physicalFraction = if (rtl) 1f - fraction else fraction
                    val globalCenter = size.width / 2f + travelWidth * physicalFraction
                    val half = bubbleWidth / 2f
                    val margin = minOf(4.dp.toPx(), (sliderWidth - bubbleWidth).coerceAtLeast(0f) / 2f)
                    val minCenter = half + margin
                    val maxCenter = (sliderWidth - half - margin).coerceAtLeast(minCenter)
                    val bubbleX = center.x + (globalCenter + bend).coerceIn(minCenter, maxCenter) - globalCenter
                    val travel = bubbleHeight / 2f + thumbHeight / 2f + 8.dp.toPx()
                    val bubbleY = center.y - travel * progress
                    val bubbleTop = Offset(bubbleX - half, bubbleY - bubbleHeight / 2f)
                    val bubbleBottom = bubbleY + bubbleHeight / 2f
                    val bubbleColor = (if (desktop) colors.surfaceContainerHighest else colors.inverseSurface).copy(alpha = alpha)
                    // Bounded vector counterpart of the source's blurred/thresholded connection.
                    link.apply {
                        reset()
                        moveTo(center.x - 3.dp.toPx(), center.y - 3.dp.toPx())
                        cubicTo(center.x - 3.dp.toPx(), center.y - 12.dp.toPx(),
                            bubbleX - 5.dp.toPx(), bubbleBottom + 4.dp.toPx(),
                            bubbleX - 5.dp.toPx(), bubbleBottom - 2.dp.toPx())
                        lineTo(bubbleX + 5.dp.toPx(), bubbleBottom - 2.dp.toPx())
                        cubicTo(bubbleX + 5.dp.toPx(), bubbleBottom + 4.dp.toPx(),
                            center.x + 3.dp.toPx(), center.y - 12.dp.toPx(),
                            center.x + 3.dp.toPx(), center.y - 3.dp.toPx())
                        close()
                    }
                    drawPath(link, bubbleColor.copy(alpha = alpha * .72f))
                    if (desktop) drawRoundRect(Color.Black.copy(alpha = .10f * alpha),
                        topLeft = bubbleTop + Offset(0f, 1.5.dp.toPx()), size = Size(bubbleWidth, bubbleHeight),
                        cornerRadius = CornerRadius(bubbleHeight / 2f))
                    drawRoundRect(bubbleColor, topLeft = bubbleTop,
                        size = Size(bubbleWidth, bubbleHeight), cornerRadius = CornerRadius(bubbleHeight / 2f))
                    drawText(labelLayout, (if (desktop) colors.onSurface else colors.inverseOnSurface).copy(alpha = alpha),
                        topLeft = Offset(bubbleX - labelLayout.size.width / 2f,
                            bubbleY - labelLayout.size.height / 2f))
                }
            },
            track = { _ ->
                Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                    val stroke = (3f + stretch.value.coerceIn(0f, 1f)).dp.toPx()
                    val start = Offset(if (rtl) size.width else 0f, size.height / 2f)
                    val end = Offset(if (rtl) 0f else size.width, size.height / 2f)
                    val thumb = Offset(start.x + (end.x - start.x) * fraction, start.y)
                    if (outlined) drawLine(trackOutline.copy(alpha = .84f), start, end,
                        strokeWidth = stroke + 2.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(colors.onSurface.copy(alpha = if (!available) .055f else if (outlined) .36f else .10f),
                        start, end, strokeWidth = stroke, cap = StrokeCap.Round)
                    buffered?.let { range ->
                        val extent = valueRange.endInclusive - valueRange.start
                        val from = (range.start - valueRange.start) / extent
                        val until = (range.endInclusive - valueRange.start) / extent
                        drawLine(colors.onSurface.copy(alpha = if (!available) .10f else if (outlined) .52f else .28f),
                            Offset(start.x + (end.x - start.x) * from, start.y),
                            Offset(start.x + (end.x - start.x) * until, start.y),
                            strokeWidth = stroke, cap = StrokeCap.Round)
                    }
                    drawLine(colors.onSurface.copy(alpha = if (!available) .20f else if (outlined) .94f else .62f),
                        start, thumb, strokeWidth = stroke, cap = StrokeCap.Round)
                }
            })
    }
}
