package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences
import dev.mangaflow.ui.motion.MangaMotion

/** The pointer always gets a full lane. Only the painted grip narrows at rest. */
@Composable
private fun DesktopScrollRail(adapter: ScrollbarAdapter, scrolling: Boolean, modifier: Modifier,
    navigationRail: Boolean = false) {
    val input = remember(adapter) { MutableInteractionSource() }
    val hovered by input.collectIsHoveredAsState()
    val dragged by input.collectIsDraggedAsState()
    val motion = LocalMotionEnabled.current
    val strongContrast = LocalPlatformAccessibilityPreferences.current.increaseContrast
    val opened = animateFloatAsState(if (hovered || dragged) 1f else if (scrolling) .42f else 0f,
        if (motion) tween(if (hovered || dragged || scrolling) MangaMotion.Quick else MangaMotion.Content,
            easing = MangaMotion.EnterEasing) else snap(), label = "scrollbar-grip")
    val ink = MaterialTheme.colorScheme.onSurface
    val style = remember(ink, strongContrast, navigationRail, motion) { ScrollbarStyle(
        minimalHeight = if (navigationRail) 26.dp else 34.dp,
        thickness = if (navigationRail) 10.dp else 12.dp,
        shape = RoundedCornerShape(50),
        hoverDurationMillis = if (motion) MangaMotion.Quick else 0,
        unhoverColor = ink.copy(alpha = if (strongContrast) .82f else .27f),
        hoverColor = ink.copy(alpha = if (strongContrast) 1f else .58f),
    ) }
    VerticalScrollbar(adapter = adapter, interactionSource = input, style = style, modifier = modifier.drawWithContent {
        if (size.width <= 0f) return@drawWithContent
        val amount = opened.value
        if (amount > 0f) drawRoundRect(ink.copy(alpha = amount * if (strongContrast) .12f else .046f),
            cornerRadius = CornerRadius(size.width / 2f))
        val idle = if (strongContrast) 5.dp else if (navigationRail) 2.5.dp else 3.dp
        val wide = if (strongContrast) 8.dp else if (navigationRail) 5.dp else 7.dp
        val grip = (idle.toPx() + (wide - idle).toPx() * amount).coerceAtMost(size.width)
        scale(scaleX = grip / size.width, scaleY = 1f, pivot = center) { this@drawWithContent.drawContent() }
    })
}

@Composable
internal actual fun DesktopVerticalScrollbar(state: ScrollState, modifier: Modifier) {
    if (state.canScrollBackward || state.canScrollForward) DesktopScrollRail(
        rememberScrollbarAdapter(state), state.isScrollInProgress, modifier, LocalDesktopNavigationScrollbar.current)
}

@Composable
internal actual fun DesktopVerticalScrollbar(state: LazyListState, modifier: Modifier) {
    if (state.canScrollBackward || state.canScrollForward) DesktopScrollRail(
        rememberScrollbarAdapter(state), state.isScrollInProgress, modifier)
}

@Composable
internal actual fun DesktopVerticalScrollbar(state: LazyGridState, modifier: Modifier) {
    if (state.canScrollBackward || state.canScrollForward) DesktopScrollRail(
        rememberScrollbarAdapter(state), state.isScrollInProgress, modifier)
}
