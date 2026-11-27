package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.animation.core.MutableTransitionState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.anchoredMenuReveal
import dev.mangaflow.ui.motion.surfaceReveal
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
    alignToStart: Boolean = false,
    maximumHeight: Dp = Dp.Infinity,
    scrollContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val presence = remember { MutableTransitionState(false) }
    presence.targetState = visible
    val transition = rememberTransition(presence, label = "reader-actions")
    val reveal = transition.surfaceReveal(motion, compact = true)
    val present = presence.currentState || presence.targetState || !presence.isIdle
    val reportPresence by rememberUpdatedState(onPresenceChange)
    var origin by remember(direction, aboveAnchor, alignToStart) {
        mutableStateOf(TransformOrigin(
            if ((direction == LayoutDirection.Ltr) == alignToStart) 0f else 1f,
            if (aboveAnchor) 1f else 0f))
    }
    SideEffect { reportPresence(present) }
    DisposableEffect(Unit) { onDispose { reportPresence(false) } }
    if (!present) return
    var anchorRoom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val position = remember(density, aboveAnchor, alignToStart) {
        val gap = with(density) { 8.dp.roundToPx() }
        val margin = with(density) { 12.dp.roundToPx() }
        val minimumListHeight = with(density) { 96.dp.roundToPx() }
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val anchorStart = layoutDirection == LayoutDirection.Ltr
                val x = if (anchorStart == alignToStart) anchorBounds.left
                    else anchorBounds.right - popupContentSize.width
                val below = anchorBounds.bottom + gap
                val above = anchorBounds.top - popupContentSize.height - gap
                val spaceAbove = (anchorBounds.top - gap - margin).coerceAtLeast(0)
                val spaceBelow = (windowSize.height - below - margin).coerceAtLeast(0)
                val largerSpace = maxOf(spaceAbove, spaceBelow)
                // A roomy window can keep a scrolling menu clear of its trigger.
                // In a short window both sides may be too small even for two actions.
                anchorRoom = if (largerSpace >= minimumListHeight) largerSpace
                    else (windowSize.height - 2 * margin).coerceAtLeast(1)
                val placeAbove = when {
                    aboveAnchor && popupContentSize.height <= spaceAbove -> true
                    !aboveAnchor && popupContentSize.height <= spaceBelow -> false
                    spaceAbove != spaceBelow -> spaceAbove > spaceBelow
                    else -> aboveAnchor
                }
                val desiredY = if (placeAbove) above else below
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
                .motionInputGate(visible).anchoredMenuReveal(reveal, 20.dp) { origin },
            cornerRadius = 20.dp, role = GlassSurfaceRole.POPOVER, softCard = true, preferSource = true,
            materialExpansion = { reveal.value }) {
            val windowHeight = (LocalMangaWindow.current.height - 64.dp).coerceAtLeast(1.dp)
            val height = if (scrollContent) minOf(windowHeight, maximumHeight, with(density) { anchorRoom.toDp() })
                else minOf(windowHeight, maximumHeight)
            val scroll = if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(Modifier.heightIn(max = height.coerceAtLeast(1.dp)).then(scroll).padding(7.dp),
                content = content)
        }
    }
}

@Composable
internal fun CompactMenuAction(icon: ImageVector, label: String, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val ink = if (destructive) colors.error else colors.onSurface
    val iconTint = if (destructive) colors.error else colors.primary
    val alpha = if (enabled) 1f else .40f
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
        .clickable(enabled = enabled, interactionSource = interaction, indication = null,
            role = Role.Button, onClick = onClick)
        .motionPress(interaction, enabled, pressedScale = .98f)
        .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = iconTint.copy(alpha = alpha))
        Text(label, Modifier.padding(start = 14.dp), color = ink.copy(alpha = alpha),
            style = MaterialTheme.typography.bodyMedium)
    }
}
