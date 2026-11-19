package dev.mangaflow.enhancement.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.MangaWindowSize
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionDialogWindowPolicy
import dev.mangaflow.ui.motion.motionInputGate

/** One retained surface: bottom on touch devices, compact right edge on desktop. */
@Composable
internal fun EnhancementPanelHost(
    visible: Boolean,
    backdrop: HazeState?,
    onDismiss: () -> Unit,
    onClosed: (() -> Unit)? = null,
    maximumWidth: Dp = 440.dp,
    surface: (@Composable (Modifier, @Composable () -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val fallbackBackdrop = rememberHazeState()
    val motion = LocalMotionEnabled.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = visible
    val transition = rememberTransition(presence, label = "enhancement-panel")
    val closed = rememberUpdatedState(onClosed)
    LaunchedEffect(presence.currentState, presence.targetState, presence.isIdle) {
        if (presence.isIdle && !presence.currentState && !presence.targetState) closed.value?.invoke()
    }
    val alpha = transition.animateFloat(
        transitionSpec = { tween(if (motion) MangaMotion.Content else 0) },
        label = "enhancement-panel-alpha",
    ) { if (it) 1f else 0f }
    val offset = transition.animateFloat(transitionSpec = {
        if (!motion) snap()
        else if (targetState) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness)
        else tween(MangaMotion.Exit, easing = MangaMotion.ExitEasing)
    }, label = "enhancement-panel-position") { if (it) 0f else 1f }
    if (presence.currentState || presence.targetState || !presence.isIdle) {
        Dialog(
            onDismissRequest = { if (visible) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            MotionDialogWindowPolicy(visible)
            val focus = LocalFocusManager.current
            SideEffect { if (!visible) focus.clearFocus(force = true) }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .14f * alpha.value))
                    .clickable(enabled = visible, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        onDismiss()
                    })
                BoxWithConstraints(
                    Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                        .padding(horizontal = if (desktop) 12.dp else 8.dp, vertical = 8.dp),
                    contentAlignment = if (desktop) Alignment.CenterEnd else Alignment.BottomCenter,
                ) {
                    val maximumHeight = maxHeight * if (desktop) .94f else .88f
                    val panelWidth = minOf(maxWidth, maximumWidth)
                    val window = MangaWindowSize(panelWidth, maximumHeight, LocalDensity.current.fontScale.coerceAtLeast(1f))
                    CompositionLocalProvider(LocalMangaWindow provides window) {
                        val panelModifier = Modifier.widthIn(max = panelWidth).fillMaxWidth().heightIn(max = maximumHeight)
                            .motionInputGate(visible)
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                            .graphicsLayer {
                                this.alpha = alpha.value
                                if (desktop) translationX = 24.dp.toPx() * offset.value
                                else translationY = 24.dp.toPx() * offset.value
                            }
                        if (surface != null) surface(panelModifier, content)
                        else LiquidGlassSurface(
                            backdrop ?: fallbackBackdrop, panelModifier,
                            cornerRadius = 24.dp,
                            role = GlassSurfaceRole.PANEL,
                        ) {
                            // Keep the existing frosted shell while isolating dense text from comic ink.
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
                                propagateMinConstraints = true) { content() }
                        }
                    }
                }
            }
        }
    }
}
