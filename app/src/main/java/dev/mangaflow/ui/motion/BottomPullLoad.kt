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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val onThresholdReached: () -> Unit,
    val actionLabel: String,
)

/** Unconsumed finger distance owns the threshold; a separate drawing layer supplies resistance. */
@Stable
internal class BottomPullLoadState private constructor(
    private val thresholdPx: Float,
    private val scope: CoroutineScope,
    private val inputs: () -> BottomPullLoadInputs,
) {
    var progress by mutableFloatStateOf(0f)
        private set
    private var feedback by mutableFloatStateOf(0f)
    private var gestureFeedbackOrigin = 0f
    internal val feedbackFraction: Float get() = feedback
    internal val contentOffsetPx: Float
        get() = if (inputs().motion) -thresholdPx * .42f * feedback.coerceIn(-.06f, 1.25f) else 0f
    val loading: Boolean get() = inputs().loading
    val actionLabel: String get() = inputs().actionLabel

    private var distancePulled = 0f
    private var pointerDown = false
    private var thresholdAnnounced = false
    private var consumeReleaseVelocity = false
    private var dispatchPending = false
    private var disposed = false
    private var settleJob: Job? = null
    private var dispatchJob: Job? = null

    private val canRequest: Boolean
        get() = !disposed && inputs().enabled && !loading && !dispatchPending
    internal val loadActionAvailable: Boolean get() = canRequest

    internal val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || !pointerDown || !canRequest ||
                available.y <= 0f || distancePulled <= 0f) return Offset.Zero
            // Reverse the unfinished pull before allowing the list to move back up.
            settleJob?.cancel()
            val consumed = minOf(available.y, distancePulled)
            distancePulled -= consumed
            updateGesturePresentation()
            if (distancePulled < thresholdPx) thresholdAnnounced = false
            return Offset(0f, consumed)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || !pointerDown || available.y >= 0f ||
                !canRequest || inputs().canScrollForward()) return Offset.Zero
            settleJob?.cancel()
            // Bottom loading reverses Material3's pull direction; fling deltas never accumulate.
            val previous = distancePulled
            distancePulled = (distancePulled - available.y).coerceAtMost(thresholdPx * 1.35f)
            // The displayed fraction is this gesture's actual distance, without a trailing animation.
            updateGesturePresentation()
            if (distancePulled >= thresholdPx && !thresholdAnnounced) {
                thresholdAnnounced = true
                inputs().onThresholdReached()
            } else if (distancePulled < thresholdPx) {
                thresholdAnnounced = false
            }
            val taken = distancePulled - previous
            return Offset(0f, -taken)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val hadPull = consumeReleaseVelocity || distancePulled > 0f
            // A cancelled child scroll can dispatch a zero-velocity fling while still pressed.
            // Only the pointer observer can confirm a real release; fling never loads content.
            if (pointerDown) finishGesture(released = false)
            consumeReleaseVelocity = false
            return Velocity(0f, if (hadPull) available.y else 0f)
        }
    }

    internal fun beginGesture() {
        pointerDown = true
        thresholdAnnounced = false
        consumeReleaseVelocity = false
        distancePulled = 0f
        settleJob?.cancel()
        gestureFeedbackOrigin = feedback.coerceIn(0f, 1f)
        if (!loading) progress = 0f
    }

    private fun updateGesturePresentation() {
        val fraction = distancePulled / thresholdPx
        progress = fraction.coerceIn(0f, 1f)
        // A new gesture catches the returning surface, but its threshold starts from zero.
        val resisted = if (fraction <= 1f) fraction else 1f + (fraction - 1f) * .5f
        feedback = gestureFeedbackOrigin + (1f - gestureFeedbackOrigin) * resisted
    }

    internal fun finishGesture(released: Boolean) {
        if (!pointerDown) return
        val ready = released && distancePulled >= thresholdPx && !inputs().canScrollForward()
        consumeReleaseVelocity = distancePulled > 0f
        pointerDown = false
        thresholdAnnounced = false
        distancePulled = 0f
        if (ready && requestMore()) {
            progress = 1f
            settle(resetProgress = false)
        } else settle(resetProgress = !loading)
    }

    internal fun accessibilityLoadMore(): Boolean {
        finishGesture(released = false)
        return requestMore()
    }

    private fun requestMore(): Boolean {
        if (!canRequest) return false
        dispatchPending = true
        // Keep a same-frame accessibility repeat from racing the caller's loading state.
        dispatchJob?.cancel()
        dispatchJob = scope.launch {
            withFrameNanos { }
            dispatchPending = false
            if (!loading && !pointerDown) settle()
        }
        inputs().onLoadMore()
        return true
    }

    internal fun updatePresentation() {
        if (loading) {
            thresholdAnnounced = false
            distancePulled = 0f
            progress = 1f
            settle(resetProgress = false)
        } else if (!inputs().enabled || distancePulled == 0f) {
            thresholdAnnounced = false
            distancePulled = 0f
            settle()
        }
    }

    private fun settle(resetProgress: Boolean = true) {
        settleJob?.cancel()
        if (disposed || (feedback == 0f && (!resetProgress || progress == 0f))) return
        if (!inputs().motion) {
            feedback = 0f
            if (resetProgress) progress = 0f
            return
        }
        val initialFeedback = feedback
        val initialProgress = progress
        settleJob = scope.launch {
            // The saved Jelly material and beUI press roles separate direct manipulation from
            // a short damped release. Only the drawing layer rebounds; labels never stretch.
            animate(initialValue = 1f, targetValue = 0f,
                animationSpec = spring(dampingRatio = .78f, stiffness = 420f)) { value, _ ->
                feedback = initialFeedback * value
                if (resetProgress) progress = (initialProgress * value).coerceIn(0f, 1f)
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
            val haptics = LocalHapticFeedback.current
            val inputs = rememberUpdatedState(BottomPullLoadInputs(
                enabled = enabled,
                loading = loading,
                motion = LocalMotionEnabled.current,
                canScrollForward = canScrollForward,
                onLoadMore = onLoadMore,
                onThresholdReached = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                actionLabel = actionLabel,
            ))
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
    nestedScroll(state.connection).pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.beginGesture()
            var released = false
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.count { it.pressed } > 1) state.finishGesture(released = false)
                    if (event.changes.none { it.pressed }) {
                        // Compose marks synthetic cancellation ups consumed before the Initial pass.
                        // Observe the real up without consuming any taps or list drag events.
                        released = event.changes.any { it.changedToUp() }
                    }
                } while (event.changes.any { it.pressed })
            } finally {
                state.finishGesture(released)
            }
        }
    }.semantics {
        customActions = if (state.loadActionAvailable)
            listOf(CustomAccessibilityAction(state.actionLabel, state::accessibilityLoadMore)) else emptyList()
    }.graphicsLayer { translationY = state.contentOffsetPx }

/** The fixed footer slot contains the moving ring/arrow; progress follows actual drag distance. */
@Composable
internal fun BottomPullLoadIndicator(
    state: BottomPullLoadState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val motion = LocalMotionEnabled.current
    val ready = animateFloatAsState(
        if (!state.loading && state.progress >= 1f) 1f else 0f,
        if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else snap(),
        label = "bottom-pull-ready",
    )
    val indicator = modifier.size(32.dp).semantics {
        contentDescription = state.actionLabel
        progressBarRangeInfo = if (state.loading) ProgressBarRangeInfo.Indeterminate
            else ProgressBarRangeInfo(state.progress, 0f..1f)
    }.graphicsLayer {
        val pull = if (motion) state.feedbackFraction.coerceIn(0f, 1f) else 0f
        scaleX = 1f + .08f * pull
        scaleY = 1f - .04f * pull
    }
    if (state.loading && motion) {
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
            val checked = ready.value.coerceIn(0f, 1f)
            val unit = (size.minDimension * .16f).coerceAtLeast(1f)
            fun point(x: Float, y: Float) = center + Offset(x * unit, y * unit)
            fun morph(arrow: Offset, check: Offset) = arrow + (check - arrow) * checked
            val a = morph(point(-.65f, -.05f), point(-.85f, -.05f))
            val b = morph(point(0f, -.7f), point(-.2f, .55f))
            val c = morph(point(.65f, -.05f), point(.9f, -.65f))
            rotate(if (motion) -18f * fraction * (1f - checked) else 0f, pivot = center) {
                val glyph = color.copy(alpha = .62f + .38f * fraction)
                drawLine(glyph, a, b, 1.7.dp.toPx(), StrokeCap.Round)
                drawLine(glyph, b, c, 1.7.dp.toPx(), StrokeCap.Round)
                if (checked < 1f) drawLine(glyph.copy(alpha = glyph.alpha * (1f - checked)),
                    point(0f, -.7f), point(0f, .8f), 1.7.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}
