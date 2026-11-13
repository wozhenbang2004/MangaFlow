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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.roundToInt

/**
 * Controlled value: the caller owns preview updates and commits on finish.
 * Material supplies input, cancellation, RTL, keyboard and progress semantics;
 * its default track, thumb, gaps and stop marker are never rendered here.
 * The tracking bubble draws outside the stable 48dp control; callers should not clip it.
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
) {
    require(valueRange.start.isFinite() && valueRange.endInclusive.isFinite() &&
        valueRange.start <= valueRange.endInclusive) { "Slider range must be finite and ordered" }
    val current = if (value.isFinite()) value.coerceIn(valueRange) else valueRange.start
    val fraction = if (valueRange.start < valueRange.endInclusive)
        ((current - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f) else 0f
    val available = enabled && valueRange.start < valueRange.endInclusive
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val dragged by interaction.collectIsDraggedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tracking = available && (pressed || dragged)
    val motion = LocalMotionEnabled.current
    val lift = animateFloatAsState(if (tracking) 1f else 0f,
        if (!motion) snap() else if (tracking)
            spring(MangaMotion.SwitchDamping, MangaMotion.GestureStiffness)
        else tween(220, easing = MangaMotion.ExitEasing), label = "slider-value-lift")
    val stretch = animateFloatAsState(if (tracking && motion) 1f else 0f,
        if (motion) spring(MangaMotion.SwitchDamping, MangaMotion.SwitchStiffness) else snap(),
        label = "slider-thumb-pressure")
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.copy(
        fontSize = 11.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
    val label = formatValue(current)

    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        val sliderWidth = with(density) { maxWidth.toPx() }
        val labelWidth = with(density) { (maxWidth - 28.dp).roundToPx().coerceAtLeast(1) }
        val labelLayout = remember(label, labelStyle, density, labelWidth) {
            textMeasurer.measure(label, style = labelStyle, maxLines = 1,
                overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = labelWidth))
        }
        Slider(value = current, onValueChange = { onValueChange(it.coerceIn(valueRange)) },
            valueRange = valueRange, enabled = available,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = label },
            thumb = { _ ->
                Canvas(Modifier.size(28.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val strength = if (available) 1f else .38f
                    val pressure = stretch.value.coerceIn(0f, 1f)
                    val thumbWidth = (16f + pressure * 3f).dp.toPx()
                    val thumbHeight = (16f - pressure).dp.toPx()
                    val thumbTop = Offset(center.x - thumbWidth / 2f, center.y - thumbHeight / 2f)
                    if (available && focused) drawRoundRect(
                        colors.onSurface.copy(alpha = .16f),
                        topLeft = thumbTop - Offset(4.dp.toPx(), 4.dp.toPx()),
                        size = Size(thumbWidth + 8.dp.toPx(), thumbHeight + 8.dp.toPx()),
                        cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))
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
                    if (alpha <= .001f) return@Canvas
                    val bubbleWidth = labelLayout.size.width + 16.dp.toPx()
                    val bubbleHeight = maxOf(24.dp.toPx(), labelLayout.size.height + 8.dp.toPx())
                    val physicalFraction = if (rtl) 1f - fraction else fraction
                    val globalCenter = size.width / 2f +
                        (sliderWidth - size.width).coerceAtLeast(0f) * physicalFraction
                    val half = bubbleWidth / 2f
                    val minCenter = half + 4.dp.toPx()
                    val maxCenter = (sliderWidth - half - 4.dp.toPx()).coerceAtLeast(minCenter)
                    val bubbleX = center.x + globalCenter.coerceIn(minCenter, maxCenter) - globalCenter
                    val travel = bubbleHeight / 2f + thumbHeight / 2f + 8.dp.toPx()
                    val bubbleY = center.y - travel * progress
                    val bubbleTop = Offset(bubbleX - half, bubbleY - bubbleHeight / 2f)
                    val bubbleBottom = bubbleY + bubbleHeight / 2f
                    val bubbleColor = colors.inverseSurface.copy(alpha = alpha)
                    // Bounded vector counterpart of the source's blurred/thresholded connection.
                    val neck = Path().apply {
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
                    drawPath(neck, bubbleColor.copy(alpha = alpha * .72f))
                    drawRoundRect(bubbleColor, topLeft = bubbleTop,
                        size = Size(bubbleWidth, bubbleHeight), cornerRadius = CornerRadius(bubbleHeight / 2f))
                    drawText(labelLayout, colors.inverseOnSurface.copy(alpha = alpha),
                        topLeft = Offset(bubbleX - labelLayout.size.width / 2f,
                            bubbleY - labelLayout.size.height / 2f))
                }
            },
            track = { _ ->
                Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                    val start = Offset(if (rtl) size.width else 0f, size.height / 2f)
                    val end = Offset(if (rtl) 0f else size.width, size.height / 2f)
                    val thumb = Offset(start.x + (end.x - start.x) * fraction, start.y)
                    drawLine(colors.onSurface.copy(alpha = if (available) .10f else .055f),
                        start, end, strokeWidth = size.height, cap = StrokeCap.Round)
                    drawLine(colors.onSurface.copy(alpha = if (available) .62f else .20f),
                        start, thumb, strokeWidth = size.height, cap = StrokeCap.Round)
                }
            })
    }
}
