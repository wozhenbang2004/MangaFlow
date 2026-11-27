package dev.mangaflow.ui.motion

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.roundToInt

/** A horizontal scrub previews logical slot coordinates and releases in slots per second. */
@Composable
internal fun Modifier.glassTabScrub(
    labels: List<String>,
    selected: Int,
    currentPosition: () -> Float,
    onPreview: (Float) -> Unit,
    onFinish: (position: Float, target: Int?, velocity: Float) -> Unit,
): Modifier {
    if (labels.size < 2 || LocalTouchExplorationEnabled.current) return this
    val direction = LocalLayoutDirection.current
    val positionNow by rememberUpdatedState(currentPosition)
    val preview by rememberUpdatedState(onPreview)
    val finish by rememberUpdatedState(onFinish)
    return pointerInput(labels, selected, direction) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial)
            if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val bounds = size
            if (bounds.width <= 0 || bounds.height <= 0) return@awaitEachGesture
            val slot = bounds.width.toFloat() / labels.size
            val sign = if (direction == LayoutDirection.Rtl) -1f else 1f
            val logicalX = if (direction == LayoutDirection.Rtl) bounds.width - down.position.x else down.position.x
            val downPosition = logicalX / slot - .5f
            val grabbedThumb = abs(downPosition - positionNow()) <= .5f
            val slop = viewConfiguration.touchSlop
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            val cancelAtDown = finish
            var anchor = downPosition
            var position = downPosition
            var dragging = false
            var target: Int? = null
            var abandoned: PointerEvent? = null
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.position - down.position
                    val vertical = !dragging && abs(delta.y) > slop && abs(delta.y) >= abs(delta.x)
                    if (size != bounds || vertical || change.position.y !in -slop..(bounds.height + slop) ||
                        event.changes.any { it.id != down.id && (it.pressed || it.previousPressed) }) {
                        abandoned = event
                        break
                    }
                    if (change.isConsumed) break
                    if (change.position != change.previousPosition || change.changedToUp()) {
                        velocity.addPosition(change.uptimeMillis, change.position)
                    }
                    if (!change.pressed) {
                        if (dragging && change.changedToUp()) {
                            position = (anchor + sign * delta.x / slot).coerceIn(0f, labels.lastIndex.toFloat())
                            target = position.roundToInt()
                            change.consume()
                        }
                        break
                    }
                    if (!dragging && abs(delta.x) > slop && abs(delta.x) > abs(delta.y)) {
                        // Keep the grab offset if the thumb was moving. Starting on another item
                        // instead tracks the point under the finger, including either end slot.
                        if (grabbedThumb) anchor = positionNow()
                        dragging = true
                    }
                    if (dragging) {
                        change.consume()
                        position = (anchor + sign * delta.x / slot).coerceIn(0f, labels.lastIndex.toFloat())
                        preview(position)
                    } else {
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.any { it.isConsumed && it.position != it.previousPosition }) break
                    }
                }
            } finally {
                if (dragging) {
                    if (target == null) {
                        cancelAtDown(position, null, 0f)
                    } else {
                        val slotsPerSecond = sign * velocity.calculateVelocity().x / slot
                        finish(position, target, if (slotsPerSecond.isFinite()) slotsPerSecond.coerceIn(-8f, 8f) else 0f)
                    }
                }
            }
            abandoned?.let { suppressAbandonedTabTap(it) }
        }
    }
}

/** A short vertical cancellation inside a tab must not subsequently click that tab on UP. */
private suspend fun AwaitPointerEventScope.suppressAbandonedTabTap(first: PointerEvent) {
    var event = first
    while (true) {
        event.changes.forEach { if (it.changedToUp()) it.consume() }
        if (event.changes.none { it.pressed }) return
        // A parent that takes the movement already cancels the selectable and owns the release.
        val final = awaitPointerEvent(PointerEventPass.Final)
        if (final.changes.any { it.isConsumed && it.position != it.previousPosition }) return
        event = awaitPointerEvent(PointerEventPass.Initial)
    }
}
