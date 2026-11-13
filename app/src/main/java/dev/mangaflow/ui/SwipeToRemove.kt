package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.rememberFeelFeedback
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
    val position = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    var returning by remember { mutableStateOf(false) }
    var thresholdAnnounced by remember { mutableStateOf(false) }
    val currentMotion by rememberUpdatedState(motion)
    fun drawnOffset() = if (returning) position.value.coerceIn(-width.toFloat(), 0f) else offset
    fun returnToRest(velocity: Float = 0f) {
        settleJob?.cancel()
        if (!currentMotion) { returning = false; offset = 0f; return }
        val start = offset
        settleJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            position.snapTo(start)
            returning = true
            position.animateTo(0f, spring(MangaMotion.GestureDamping, MangaMotion.GestureStiffness),
                initialVelocity = velocity.coerceIn(-width * 8f, width * 8f))
            offset = 0f
            returning = false
        }
    }
    val fraction = if (width > 0) (-offset / width).coerceIn(0f, 1f) else 0f
    val armed = enabled && fraction >= .55f
    val readiness = animateFloatAsState(if (armed) 1f else 0f,
        if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(),
        label = "record-delete-ready")
    val haptics = rememberFeelFeedback()
    val colors = MaterialTheme.colorScheme
    val wallpaper = LocalWallpaperActive.current
    LaunchedEffect(fraction, dragging) {
        if (armed && dragging && !thresholdAnnounced) {
            thresholdAnnounced = true
            haptics.perform(FeelEvent.Threshold)
        } else if (!dragging || fraction < .47f) thresholdAnnounced = false
    }
    LaunchedEffect(enabled, motion) {
        if (!enabled || !motion) {
            settleJob?.cancel()
            returning = false
            if (!enabled || !dragging) offset = 0f
            if (!enabled) dragging = false
        }
    }
    val gesture = if (enabled) Modifier.pointerInput(width) {
        detectHorizontalDragGestures(
            onDragStart = {
                if (swipeEnabled) {
                    val caught = if (returning) position.value else offset
                    settleJob?.cancel()
                    returning = false
                    offset = caught.coerceIn(-width.toFloat(), 0f)
                    thresholdAnnounced = false
                    velocityTracker.resetTracking()
                    dragging = true
                }
            },
            onDragCancel = { dragging = false; returnToRest() },
            onDragEnd = {
                val commit = swipeEnabled && width > 0 && -offset >= width * .55f
                dragging = false
                if (commit) {
                    // The parent keyed item's exit owns removal; undo/storage never waits for it.
                    offset = 0f
                    remove()
                } else returnToRest(velocityTracker.calculateVelocity().x)
            },
            onHorizontalDrag = { change, amount ->
                if (swipeEnabled) {
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val next = (offset + amount).coerceIn(-width.toFloat(), 0f)
                    if (offset < 0f || next < 0f) change.consume()
                    offset = next
                }
            },
        )
    } else Modifier
    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width }
        .then(if (enabled && (dragging || returning || offset < 0f))
            Modifier.clip(RoundedCornerShape(12.dp)) else Modifier)
        .semantics {
            customActions = if (enabled) listOf(CustomAccessibilityAction(label) { remove(); true }) else emptyList()
        }
        .then(gesture)) {
        if (enabled && (offset < 0f || returning)) Box(
            Modifier.matchParentSize().drawWithContent {
                // A wallpaper row has no opaque backing to mask the action underneath.
                // Keep the complete reveal (including icons) inside the exposed strip.
                val left = (size.width + drawnOffset()).coerceIn(0f, size.width)
                clipRect(left = left) { this@drawWithContent.drawContent() }
            }.drawBehind {
                val reveal = (-drawnOffset()).coerceIn(0f, size.width)
                val progress = (reveal / (size.width * .55f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                val eased = if (motion) MangaMotion.SearchEasing.transform(progress) else progress
                val edge = size.width - reveal
                val bend = if (motion) minOf(18.dp.toPx(), reveal * .22f) * eased else 0f
                // Only the reveal surface bends. The translated content and its text keep their geometry.
                val shape = Path().apply {
                    moveTo(size.width, 0f)
                    lineTo(edge + bend, 0f)
                    cubicTo(edge, size.height * .22f, edge, size.height * .78f, edge + bend, size.height)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(shape, colors.error.copy(alpha = .055f + .085f * eased))
            },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(22.dp).graphicsLayer {
                    if (motion) {
                        val reveal = (-drawnOffset()).coerceAtLeast(0f)
                        val p = (reveal / (width * .55f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val eased = MangaMotion.SearchEasing.transform(p)
                        rotationZ = -18f * (1f - eased) + sin(eased * PI).toFloat() * 4f
                        val scale = .72f + .28f * eased + .06f * readiness.value
                        scaleX = scale
                        scaleY = scale
                        translationX = 8.dp.toPx() * (1f - eased)
                        translationY = -1.5.dp.toPx() * readiness.value
                    }
                }, tint = colors.error)
                Text(if (armed) "松手删除" else "左滑删除", color = colors.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.graphicsLayer {
                        val reveal = (-drawnOffset()).coerceAtLeast(0f)
                        val p = (reveal / (width * .55f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        alpha = (p * 3f).coerceIn(0f, 1f)
                        translationX = if (motion) 6.dp.toPx() * (1f - MangaMotion.SearchEasing.transform(p)) else 0f
                    })
            }
        }
        Box(Modifier.fillMaxWidth().graphicsLayer {
            translationX = if (!enabled) 0f else drawnOffset()
        }.background(if (wallpaper) Color.Transparent else colors.background)) { content() }
    }
}

