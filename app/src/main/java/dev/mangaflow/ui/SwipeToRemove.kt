package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.LocalMotionEnabled

/** Physical left swipe, committed on release by distance; a short fast fling cannot delete. */
@Composable
internal fun SwipeToRemove(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "删除",
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val remove by rememberUpdatedState(onRemove)
    val swipeEnabled by rememberUpdatedState(enabled)
    val motion = LocalMotionEnabled.current
    val position = animateFloatAsState(offset,
        if (dragging || !motion) snap() else spring(dampingRatio = .86f, stiffness = 550f),
        label = "record-swipe")
    val fraction = if (width > 0) (-offset / width).coerceIn(0f, 1f) else 0f
    val armed = fraction >= .55f
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(armed) {
        if (armed && dragging) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width }
        .then(if (enabled) Modifier.clip(RoundedCornerShape(12.dp)) else Modifier)
        .semantics {
            customActions = listOf(CustomAccessibilityAction(label) { remove(); true })
        }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = {
                    if (swipeEnabled) { offset = position.value.coerceAtMost(0f); dragging = true }
                },
                onDragCancel = { dragging = false; offset = 0f },
                onDragEnd = {
                    val commit = swipeEnabled && width > 0 && -offset >= width * .55f
                    dragging = false
                    offset = 0f
                    if (commit) remove()
                },
                onHorizontalDrag = { change, amount ->
                    if (swipeEnabled) {
                        val next = (offset + amount).coerceIn(-width.toFloat(), 0f)
                        if (offset < 0f || next < 0f) change.consume()
                        offset = next
                    }
                },
            )
        }) {
        if (offset < 0f || position.value < -.5f) Box(
            Modifier.matchParentSize().background(colors.error.copy(alpha = if (armed) .13f else .065f)),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(20.dp), tint = colors.error)
                Text(if (armed) "松手删除" else "左滑删除", color = colors.error,
                    style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(Modifier.fillMaxWidth().graphicsLayer {
            translationX = if (dragging) offset else position.value
        }.background(colors.background)) { content() }
    }
}
