package dev.mangaflow.ui.motion

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.abs

/** One route owns its preview until its outgoing drawing is disposed. Navigation owns data. */
internal class MotionBackState {
    val progress = Animatable(0f)
    var fromLeft by mutableStateOf(true)
    var gesturing by mutableStateOf(false)
    var completed by mutableStateOf(false)
    var settling: Job? = null
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
        if (enabled && state != null && (state.completed || !motion)) {
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
        state.settling?.cancel()
        state.progress.stop()
        val origin = state.progress.value * if (state.fromLeft) 1f else -1f
        var hasProgress = false
        try {
            events.collect { event ->
                hasProgress = true
                state.gesturing = true
                val p = event.progress.coerceIn(0f, 1f)
                val signed = origin * (1f - p) + p * if (event.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                state.fromLeft = signed >= 0f
                state.progress.snapTo(abs(signed))
            }
            state.gesturing = false
            if (latestEnabled) {
                // A three-button/keyboard back has no preview and keeps the shared-cover return.
                if (hasProgress) {
                    state.completed = true
                    state.settling = scope.launch {
                        state.progress.animateTo(1f, spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness))
                    }
                }
                latestBack()
            } else {
                state.settling = scope.launch {
                    state.progress.animateTo(0f, spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness))
                }
            }
        } catch (cancelled: CancellationException) {
            state.gesturing = false
            state.settling = scope.launch {
                state.progress.animateTo(0f, spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness))
            }
            throw cancelled
        }
    }
}

internal fun Modifier.motionBackPreview(state: MotionBackState): Modifier = graphicsLayer {
    val p = state.progress.value.coerceIn(0f, 1f)
    translationX = size.width * .08f * p * if (state.fromLeft) 1f else -1f
    scaleX = 1f - .03f * p
    scaleY = scaleX
    clip = p > 0f
    shape = RoundedCornerShape((24f * p).dp)
}
