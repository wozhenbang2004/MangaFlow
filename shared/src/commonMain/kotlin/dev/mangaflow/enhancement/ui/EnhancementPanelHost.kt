package dev.mangaflow.enhancement.ui

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
import dev.mangaflow.ui.mangaOverlayScrim
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.surfaceReveal
import dev.mangaflow.ui.motion.surfaceRevealAlpha
import dev.mangaflow.ui.motion.MotionDialogWindowPolicy
import dev.mangaflow.ui.motion.motionInputGate

/** Retains the active page until dismissal completes, including a pending external action. */
@Composable
internal fun EnhancementPanelHost(
    visible: Boolean,
    backdrop: HazeState?,
    onDismiss: () -> Unit,
    onClosed: (() -> Unit)? = null,
    maximumWidth: Dp = 440.dp,
    playbackOverlay: Boolean = false,
    surface: (@Composable (Modifier, @Composable () -> Unit) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val endDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val fallbackBackdrop = rememberHazeState()
    val motion = LocalMotionEnabled.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = visible
    val transition = rememberTransition(presence, label = "enhancement-panel")
    val closed = rememberUpdatedState(onClosed)
    LaunchedEffect(presence.currentState, presence.targetState, presence.isIdle) {
        if (presence.isIdle && !presence.currentState && !presence.targetState) closed.value?.invoke()
    }
    val reveal = transition.surfaceReveal(motion)
    if (presence.currentState || presence.targetState || !presence.isIdle) {
        Dialog(
            onDismissRequest = { if (visible) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            MotionDialogWindowPolicy(visible, backgroundDim = 0f)
            val focus = LocalFocusManager.current
            SideEffect { if (!visible) focus.clearFocus(force = true) }
            Box(Modifier.fillMaxSize()) {
                val scrim = if (playbackOverlay) Color.Transparent else mangaOverlayScrim()
                val backdropInput = if (playbackOverlay) Modifier.clearAndSetSemantics { }
                    .focusProperties { canFocus = false } else Modifier
                Box(Modifier.fillMaxSize().drawBehind {
                    if (scrim.alpha > 0f) drawRect(scrim, alpha = reveal.value.coerceIn(0f, 1f))
                }
                    .then(backdropInput)
                    .clickable(enabled = visible, indication = null, interactionSource = remember { MutableInteractionSource() }) {
                        onDismiss()
                    })
                BoxWithConstraints(
                    Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                        .padding(horizontal = if (desktop) 12.dp else 8.dp, vertical = 8.dp),
                ) {
                    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                    val sidePanel = playbackOverlay && maxWidth >= 520.dp && maxWidth > maxHeight
                    val maximumHeight = when {
                        sidePanel -> maxHeight.coerceAtMost(800.dp)
                        desktop -> maxHeight * .94f
                        else -> maxHeight * .88f
                    }
                    val panelWidth = if (sidePanel) (maxWidth * .42f)
                        .coerceAtLeast(280.dp * fontScale.coerceAtMost(1.3f))
                        .coerceAtMost(minOf(maxWidth * .55f, maximumWidth)) else minOf(maxWidth, maximumWidth)
                    val alignment = if (sidePanel || desktop) Alignment.CenterEnd else Alignment.BottomCenter
                    val window = MangaWindowSize(panelWidth, maximumHeight, fontScale)
                    CompositionLocalProvider(LocalMangaWindow provides window) {
                        val panelModifier = Modifier.align(alignment).widthIn(max = panelWidth).fillMaxWidth().heightIn(max = maximumHeight)
                            .motionInputGate(visible)
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                            .graphicsLayer {
                                val progress = reveal.value.coerceIn(0f, 1f)
                                val rest = 1f - progress
                                alpha = surfaceRevealAlpha(progress)
                                if (sidePanel || desktop) {
                                    translationX = endDirection * minOf(size.width * .14f, 48.dp.toPx()) * rest
                                    translationY = 0f
                                } else {
                                    translationX = 0f
                                    translationY = minOf(size.height * .15f, 40.dp.toPx()) * rest
                                }
                            }
                        if (surface != null) surface(panelModifier, content)
                        else LiquidGlassSurface(
                            backdrop ?: fallbackBackdrop, panelModifier,
                            cornerRadius = 24.dp,
                            role = GlassSurfaceRole.PANEL,
                        ) {
                            Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) { content() }
                        }
                    }
                }
            }
        }
    }
}
