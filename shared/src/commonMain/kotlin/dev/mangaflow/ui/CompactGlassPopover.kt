package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress

/** Reuse DetailCommentFilter's retained glass presence for compact contextual action menus. */
@Composable
internal fun CompactGlassPopover(
    visible: Boolean,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onPresenceChange: (Boolean) -> Unit = {},
    aboveAnchor: Boolean = false,
    maximumWidth: Dp = 264.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = visible
    val transition = rememberTransition(presence, label = "reader-actions")
    val alpha = transition.animateFloat(transitionSpec = {
        tween(if (motion) if (targetState) MangaMotion.Content else MangaMotion.Quick else 0)
    }, label = "reader-actions-opacity") { if (it) 1f else 0f }
    val scale = transition.animateFloat(transitionSpec = {
        if (motion) spring(MangaMotion.PanelDamping, MangaMotion.PanelStiffness) else snap()
    }, label = "reader-actions-scale") { if (it) 1f else .94f }
    val present = presence.currentState || presence.targetState || !presence.isIdle
    val reportPresence by rememberUpdatedState(onPresenceChange)
    var origin by remember { mutableStateOf(TransformOrigin(if (direction == LayoutDirection.Ltr) 1f else 0f, if (aboveAnchor) 1f else 0f)) }
    SideEffect { reportPresence(present) }
    DisposableEffect(Unit) { onDispose { reportPresence(false) } }
    if (!present) return
    val position = remember(density, aboveAnchor) {
        val gap = with(density) { 8.dp.roundToPx() }
        val margin = with(density) { 12.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = if (layoutDirection == LayoutDirection.Ltr)
                    anchorBounds.right - popupContentSize.width else anchorBounds.left
                val below = anchorBounds.bottom + gap
                val above = anchorBounds.top - popupContentSize.height - gap
                val desiredY = if (aboveAnchor || below + popupContentSize.height > windowSize.height - margin) above else below
                val placedX = x.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                val placedY = desiredY.coerceIn(margin, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(margin))
                origin = TransformOrigin(
                    ((anchorBounds.center.x - placedX).toFloat() / popupContentSize.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                    ((anchorBounds.center.y - placedY).toFloat() / popupContentSize.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                )
                return IntOffset(placedX, placedY)
            }
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = { if (visible) onDismiss() },
        properties = PopupProperties(focusable = visible)) {
        LiquidGlassSurface(backdrop,
            Modifier.width((LocalMangaWindow.current.width - 48.dp).coerceAtLeast(1.dp).coerceAtMost(maximumWidth))
                .motionInputGate(visible).graphicsLayer {
                    this.alpha = alpha.value
                    scaleX = scale.value; scaleY = scale.value
                    transformOrigin = origin
                    translationY = (if (origin.pivotFractionY >= .5f) 6.dp.toPx() else -6.dp.toPx()) * (1f - alpha.value)
                }, cornerRadius = 18.dp, role = GlassSurfaceRole.POPOVER, softCard = true,
            materialExpansion = { alpha.value }) {
            Column(Modifier.heightIn(max = (LocalMangaWindow.current.height - 64.dp).coerceAtLeast(1.dp))
                .verticalScroll(rememberScrollState()).padding(6.dp), content = content)
        }
    }
}

@Composable
internal fun CompactMenuAction(icon: ImageVector, label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        .copy(alpha = if (enabled) .86f else .30f)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, interactionSource = interaction, indication = null,
            role = Role.Button, onClick = onClick)
        .motionPress(interaction, enabled, pressedScale = .98f)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(19.dp), tint = color)
        Text(label, Modifier.padding(start = 12.dp), color = color, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
