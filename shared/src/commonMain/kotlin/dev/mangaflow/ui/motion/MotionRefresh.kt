package dev.mangaflow.ui.motion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** AndroidX owns slop, top-edge arbitration, resistance, reversal and release-only dispatch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MotionRefreshBox(
    enabled: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    indicatorTop: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val feedback = rememberFeelFeedback()
    val motion = LocalMotionEnabled.current
    val color = MaterialTheme.colorScheme.onSurface
    var pointerDown by remember { mutableStateOf(false) }
    LaunchedEffect(state, enabled, refreshing) {
        var announced = false
        snapshotFlow { pointerDown to state.distanceFraction }.collect { (down, fraction) ->
            if (!down || fraction < .88f) announced = false
            if (down && enabled && !refreshing && fraction >= 1f && !announced) {
                announced = true
                feedback.perform(FeelEvent.Threshold)
            }
        }
    }
    Box(Modifier.fillMaxSize()
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pointerDown = true
                try {
                    do { val event = awaitPointerEvent(PointerEventPass.Final) }
                    while (event.changes.any { it.pressed })
                } finally { pointerDown = false }
            }
        }
        .pullToRefresh(isRefreshing = refreshing, state = state,
            enabled = enabled, threshold = 80.dp, onRefresh = onRefresh)
        .semantics {
            customActions = if (enabled && !refreshing) listOf(CustomAccessibilityAction("刷新漫画") {
                onRefresh(); true
            }) else emptyList()
        }) {
        content()
        // Only the feedback follows the finger; do not translate or rescale the reading/list scene.
        Box(Modifier.align(Alignment.TopCenter).padding(top = indicatorTop).size(32.dp)
            .graphicsLayer {
                val p = state.distanceFraction.coerceIn(0f, 1.2f)
                alpha = if (refreshing) 1f else p.coerceIn(0f, 1f)
                translationY = if (motion) 18.dp.toPx() * p else 0f
                val scale = if (motion) .82f + .18f * p.coerceAtMost(1f) else 1f
                scaleX = scale; scaleY = scale
            }, contentAlignment = Alignment.Center) {
            if (refreshing) MotionBusyIndicator(Modifier.size(22.dp), color, 1.75.dp)
            else Canvas(Modifier.size(22.dp)) {
                val stroke = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round)
                val origin = Offset(stroke.width / 2f, stroke.width / 2f)
                val bounds = Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(color.copy(alpha = .12f), 0f, 360f, false, origin, bounds, style = stroke)
                drawArc(color, -90f, 360f * state.distanceFraction.coerceIn(0f, 1f),
                    false, origin, bounds, style = stroke)
            }
        }
    }
}
