package dev.mangaflow.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.*

/** Local adapters of the existing settings disclosure and beUI/Ovio motion roles. */
@Composable
internal fun CopyShelfReveal(
    visible: Boolean, modifier: Modifier = Modifier, onHidden: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    // Known content participates in the initial layout without an artificial collapsed pass.
    val presence = remember { MutableTransitionState(visible) }
    presence.targetState = visible
    val hidden = rememberUpdatedState(onHidden)
    if (onHidden != null) LaunchedEffect(presence.currentState, presence.targetState, presence.isIdle) {
        if (presence.isIdle && !presence.currentState && !presence.targetState) hidden.value?.invoke()
    }
    AnimatedVisibility(presence, modifier,
        enter = if (motion) fadeIn(tween(MangaMotion.ListEnter, easing = MangaMotion.ListFadeEasing)) +
            expandVertically(spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness), expandFrom = Alignment.Top) else EnterTransition.None,
        exit = if (motion) fadeOut(tween(MangaMotion.Quick)) +
            shrinkVertically(tween(MangaMotion.Exit, easing = MangaMotion.ExitEasing), shrinkTowards = Alignment.Top) else ExitTransition.None) {
        Box(Modifier.fillMaxWidth().motionInputGate(visible)) { content() }
    }
}

@Composable
internal fun CopyShelfTool(visible: Boolean, content: @Composable () -> Unit) {
    val motion = LocalMotionEnabled.current
    AnimatedVisibility(visible,
        enter = if (motion) fadeIn(tween(MangaMotion.Quick)) +
            expandHorizontally(spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness), expandFrom = Alignment.End) else EnterTransition.None,
        exit = if (motion) fadeOut(tween(MangaMotion.Quick)) +
            shrinkHorizontally(tween(MangaMotion.Exit, easing = MangaMotion.ExitEasing), shrinkTowards = Alignment.End) else ExitTransition.None) {
        Box(Modifier.motionInputGate(visible), contentAlignment = Alignment.Center) { content() }
    }
}

/** Retain only this local message/caption until its exit; the account owns the containing key. */
@Composable
internal fun <T : Any> CopyShelfValue(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    var retained by remember { mutableStateOf(value) }
    SideEffect { if (value != null) retained = value }
    CopyShelfReveal(value != null, modifier, onHidden = {
        if (value == null) retained = null
    }) {
        val shown = value ?: retained
        if (shown != null) MotionTabContent(shown, contentKey = { it }, order = { 0 },
            fillContent = false, soft = true, content = content)
    }
}

@Composable
internal fun CopyShelfArrival(identity: String?, active: Boolean, content: @Composable BoxScope.() -> Unit) {
    val motion = LocalMotionEnabled.current
    val progress = remember(identity) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(identity, active, motion) {
        if (!motion) progress.snapTo(1f)
        else if (active) {
            progress.animateTo(1f, tween(MangaMotion.ListEnter, easing = MangaMotion.EnterEasing))
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        alpha = .92f + .08f * progress.value
        translationY = 6.dp.toPx() * (1f - progress.value)
    }, content = content)
}
