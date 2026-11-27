@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package dev.mangaflow.ui.motion

import androidx.compose.ui.backhandler.BackHandler as ComposeBackHandler
import androidx.compose.ui.backhandler.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.abs

// The public event protocol uses 0/1; its Android companion is an internal typealias.
private const val LEFT_BACK_EDGE = 0

/** The platform dispatcher owns native Android/iOS gestures and desktop Escape. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = ComposeBackHandler(enabled, onBack)

/** One route owns its preview until its outgoing drawing is disposed. Navigation owns data. */
internal class MotionBackState {
    val progress = Animatable(0f)
    var fromLeft by mutableStateOf(true)
    var gesturing by mutableStateOf(false)
    var completed by mutableStateOf(false)
    var settling: Job? = null
    var gesture: Any? = null

    fun settle(scope: CoroutineScope, target: Float, velocity: Float) {
        settling?.cancel()
        settling = scope.launch {
            // A reversing finger can still be travelling away from the destination on release.
            // Carry that velocity into the spring so an interrupted cancellation can be picked up again.
            val speed = if (velocity.isFinite()) velocity.coerceIn(-5f, 5f) else 0f
            val stiffness = if (target == 0f) MangaMotion.GestureStiffness else MangaMotion.NavigationStiffness
            progress.animateTo(target, spring(MangaMotion.NavigationDamping, stiffness), initialVelocity = speed)
        }
    }
}

internal val LocalMotionBackState = staticCompositionLocalOf<MotionBackState?> { null }

@Composable
internal fun MotionBackHandler(
    enabled: Boolean,
    state: MotionBackState? = LocalMotionBackState.current,
    onBack: () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val scope = rememberCoroutineScope()
    val latestBack by rememberUpdatedState(onBack)
    val latestEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(enabled, state, motion) {
        if (state != null && (!motion || enabled && state.completed)) {
            state.gesture = null
            state.settling?.cancel()
            state.gesturing = false
            state.completed = false
            state.progress.snapTo(0f)
        }
    }
    if (!motion || state == null) {
        BackHandler(enabled) { latestBack() }
        return
    }
    PredictiveBackHandler(enabled) { events ->
        val gesture = Any()
        state.gesture = gesture
        val velocity = VelocityTracker()
        var hasProgress = false
        try {
            state.settling?.cancel()
            state.progress.stop()
            val origin = state.progress.value * if (state.fromLeft) 1f else -1f
            events.collect { event ->
                if (state.gesture !== gesture) return@collect
                hasProgress = true
                state.gesturing = true
                val p = event.progress.coerceIn(0f, 1f)
                val signed = origin * (1f - p) + p * if (event.swipeEdge == LEFT_BACK_EDGE) 1f else -1f
                state.fromLeft = signed >= 0f
                velocity.addPosition(monotonicTimeMillis(), Offset(abs(signed), 0f))
                state.progress.snapTo(abs(signed))
            }
            if (state.gesture !== gesture) return@PredictiveBackHandler
            state.gesturing = false
            if (latestEnabled) {
                // A three-button/keyboard back has no preview and keeps the shared-cover return.
                if (hasProgress) {
                    state.completed = true
                    state.settle(scope, 1f, velocity.calculateVelocity().x)
                }
                latestBack()
            } else {
                state.settle(scope, 0f, velocity.calculateVelocity().x)
            }
        } catch (cancelled: CancellationException) {
            if (state.gesture === gesture) {
                state.gesturing = false
                state.settle(scope, 0f, velocity.calculateVelocity().x)
            }
            throw cancelled
        } finally {
            if (state.gesture === gesture) state.gesture = null
        }
    }
}

internal fun Modifier.motionBackPreview(state: MotionBackState): Modifier = graphicsLayer {
    val p = state.progress.value.coerceIn(0f, 1f)
    transformOrigin = TransformOrigin(if (state.fromLeft) 1f else 0f, .5f)
    translationX = minOf(size.width * .055f, 30.dp.toPx()) * p * if (state.fromLeft) 1f else -1f
    scaleX = 1f - .035f * p
    scaleY = scaleX
    clip = p > 0f
    shape = RoundedCornerShape((24f * p).dp)
}
