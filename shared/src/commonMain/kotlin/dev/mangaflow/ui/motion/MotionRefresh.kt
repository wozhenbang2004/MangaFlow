package dev.mangaflow.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.lerp
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
    showRefreshingIndicator: Boolean = true,
    actionLabel: String = "刷新漫画",
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    val feedback = rememberFeelFeedback()
    val motion = LocalMotionEnabled.current
    val color = MaterialTheme.colorScheme.onSurface
    val actionColor = MaterialTheme.colorScheme.primary
    var pointerDown by remember { mutableStateOf(false) }
    var initiatedPull by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(state, enabled, refreshing) {
        var announced = false
        var previous = state.distanceFraction
        snapshotFlow { pointerDown to state.distanceFraction }.collect { (down, fraction) ->
            val extending = fraction > previous
            previous = fraction
            if (enabled && down && !refreshing && extending) initiatedPull = true
            if (refreshing || !enabled || (!down && fraction <= .001f)) initiatedPull = false
            if (enabled && down && !refreshing) armed = fraction >= 1f
            else if (fraction < .88f) armed = false
            if (!down || fraction < .88f) announced = false
            if (down && enabled && !refreshing && extending && fraction >= 1f && !announced) {
                announced = true
                feedback.perform(FeelEvent.Threshold)
            }
        }
    }
    val readiness = animateFloatAsState(if (armed) 1f else 0f,
        if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(), label = "refresh-ready")
    // In-place refresh hands the released check back to the toolbar. An aborted pull simply
    // rides the platform return; an unrelated programmatic refresh never reveals a gesture.
    val overlay = animateFloatAsState(if (showRefreshingIndicator || initiatedPull) 1f else 0f,
        if (motion && !initiatedPull) tween(MangaMotion.Quick) else snap(), label = "refresh-handoff")
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
            customActions = if (enabled && !refreshing) listOf(CustomAccessibilityAction(actionLabel) {
                onRefresh(); true
            }) else emptyList()
        }) {
        content()
        // Only the feedback follows the finger; do not translate or rescale the reading/list scene.
        Box(Modifier.align(Alignment.TopCenter).padding(top = indicatorTop).size(32.dp)
            .graphicsLayer {
                val p = state.distanceFraction.coerceIn(0f, 1.2f)
                alpha = overlay.value * if (refreshing && showRefreshingIndicator) 1f else p.coerceIn(0f, 1f)
                translationY = if (motion) 18.dp.toPx() * p else 0f
                val scale = if (motion) .82f + .18f * p.coerceAtMost(1f) else 1f
                scaleX = scale; scaleY = scale
            }, contentAlignment = Alignment.Center) {
            if (refreshing && showRefreshingIndicator) MotionBusyIndicator(Modifier.size(22.dp), color, 1.75.dp)
            else Canvas(Modifier.size(22.dp)) {
                drawPullRing(lerp(color, actionColor, readiness.value.coerceIn(0f, 1f)),
                    state.distanceFraction, readiness.value, 1.75.dp,
                    horizontal = false, towardStart = true, motion = motion)
            }
        }
    }
}
