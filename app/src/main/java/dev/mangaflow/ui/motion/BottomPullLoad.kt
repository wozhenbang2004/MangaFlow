/*
 * Adapted from AndroidX Material3 1.3.2 PullToRefresh.kt.
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.mangaflow.ui.motion

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class BottomPullLoadInputs(
    val enabled: Boolean,
    val loading: Boolean,
    val motion: Boolean,
    val canScrollForward: () -> Boolean,
    val onLoadMore: () -> Unit,
    val actionLabel: String,
)

/** A footer pull keeps the list stationary and uses only its unconsumed drag distance. */
@Stable
internal class BottomPullLoadState private constructor(
    private val thresholdPx: Float,
    private val scope: CoroutineScope,
    private val inputs: () -> BottomPullLoadInputs,
) {
    var progress by mutableFloatStateOf(0f)
        private set
    val loading: Boolean get() = inputs().loading
    val actionLabel: String get() = inputs().actionLabel

    private var distancePulled = 0f
    private var triggeredForGesture = false
    private var dispatchPending = false
    private var disposed = false
    private var settleJob: Job? = null
    private var dispatchJob: Job? = null

    private val canRequest: Boolean
        get() = !disposed && inputs().enabled && !loading && !dispatchPending
    internal val loadActionAvailable: Boolean get() = canRequest

    internal val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || !canRequest || available.y <= 0f ||
                distancePulled <= 0f || loading || triggeredForGesture) return Offset.Zero
            // Reverse the unfinished pull before allowing the list to move back up.
            settleJob?.cancel()
            val consumed = minOf(available.y, distancePulled)
            distancePulled -= consumed
            progress = (distancePulled / thresholdPx).coerceIn(0f, 1f)
            return Offset(0f, consumed)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y >= 0f || !canRequest ||
                triggeredForGesture || inputs().canScrollForward()) return Offset.Zero
            if (settleJob?.isActive == true) distancePulled = progress * thresholdPx
            settleJob?.cancel()
            // Bottom loading reverses Material3's pull direction; fling deltas never accumulate.
            val previous = distancePulled
            distancePulled = (distancePulled - available.y).coerceAtMost(thresholdPx)
            // The displayed fraction is this gesture's actual distance, without a trailing animation.
            progress = (distancePulled / thresholdPx).coerceIn(0f, 1f)
            val taken = distancePulled - previous
            if (progress >= 1f) {
                triggeredForGesture = true
                requestMore()
            }
            return Offset(0f, -taken)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val hadPull = distancePulled > 0f || triggeredForGesture
            distancePulled = 0f
            triggeredForGesture = false
            if (!loading) settle()
            // Foundation also dispatches a zero-velocity fling on drag cancellation.
            return Velocity(0f, if (hadPull) available.y else 0f)
        }
    }

    internal fun accessibilityLoadMore(): Boolean = requestMore()

    private fun requestMore(): Boolean {
        if (!canRequest) return false
        dispatchPending = true
        // Keep a same-frame accessibility repeat from racing the caller's loading state.
        dispatchJob?.cancel()
        dispatchJob = scope.launch {
            withFrameNanos { }
            dispatchPending = false
        }
        inputs().onLoadMore()
        return true
    }

    internal fun updatePresentation() {
        if (loading) {
            settleJob?.cancel()
            distancePulled = 0f
            progress = 1f
        } else if (!inputs().enabled || triggeredForGesture || distancePulled == 0f) {
            distancePulled = 0f
            settle()
        }
    }

    private fun settle() {
        settleJob?.cancel()
        if (disposed || progress == 0f) return
        if (!inputs().motion) {
            progress = 0f
            return
        }
        settleJob = scope.launch {
            animate(initialValue = progress, targetValue = 0f,
                animationSpec = tween(MangaMotion.Exit, easing = MangaMotion.EnterEasing)) { value, _ ->
                progress = value
            }
        }
    }

    internal fun dispose() {
        disposed = true
        settleJob?.cancel()
        dispatchJob?.cancel()
    }

    internal companion object {
        @Composable
        fun rememberState(
            key: Any,
            enabled: Boolean,
            loading: Boolean,
            canScrollForward: () -> Boolean,
            onLoadMore: () -> Unit,
            actionLabel: String,
        ): BottomPullLoadState {
            val inputs = rememberUpdatedState(BottomPullLoadInputs(
                enabled, loading, LocalMotionEnabled.current, canScrollForward, onLoadMore, actionLabel))
            val threshold = with(LocalDensity.current) { 96.dp.toPx() }
            val scope = rememberCoroutineScope()
            val state = remember(key, threshold) { BottomPullLoadState(threshold, scope) { inputs.value } }
            LaunchedEffect(state, loading, enabled, inputs.value.motion) { state.updatePresentation() }
            DisposableEffect(state) { onDispose { state.dispose() } }
            return state
        }
    }
}

@Composable
internal fun rememberBottomPullLoadState(
    key: Any,
    enabled: Boolean,
    loading: Boolean,
    canScrollForward: () -> Boolean,
    onLoadMore: () -> Unit,
    actionLabel: String = "加载更多",
): BottomPullLoadState = BottomPullLoadState.rememberState(
    key, enabled, loading, canScrollForward, onLoadMore, actionLabel)

internal fun Modifier.bottomPullLoad(state: BottomPullLoadState): Modifier =
    nestedScroll(state.connection).semantics {
        customActions = if (state.loadActionAvailable)
            listOf(CustomAccessibilityAction(state.actionLabel, state::accessibilityLoadMore)) else emptyList()
    }

/** The caller reserves a footer slot; no drag-dependent size or list offset is introduced. */
@Composable
internal fun BottomPullLoadIndicator(
    state: BottomPullLoadState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val indicator = modifier.size(22.dp).semantics {
        contentDescription = state.actionLabel
        progressBarRangeInfo = if (state.loading) ProgressBarRangeInfo.Indeterminate
            else ProgressBarRangeInfo(state.progress, 0f..1f)
    }
    if (state.loading && LocalMotionEnabled.current) {
        CircularProgressIndicator(modifier = indicator, color = color, strokeWidth = 2.dp)
    } else {
        Canvas(indicator) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            val radius = ((size.minDimension - stroke.width) / 2f).coerceAtLeast(0f)
            drawCircle(color.copy(alpha = .12f), radius = radius, style = stroke)
            val fraction = if (state.loading) 1f else state.progress.coerceIn(0f, 1f)
            if (fraction > 0f) drawArc(
                color = color, startAngle = -90f, sweepAngle = 360f * fraction,
                useCenter = false, topLeft = center - Offset(radius, radius),
                size = Size(radius * 2f, radius * 2f), style = stroke,
            )
        }
    }
}
