package dev.mangaflow.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.LocalPlatformAccessibilityPreferences
import dev.mangaflow.ui.motion.MangaMotion

/** Styling only: Compose's real adapters retain scrolling, thumb dragging and track hit handling. */
@Composable
private fun desktopScrollbarStyle(navigationRail: Boolean = false): ScrollbarStyle {
    val ink = MaterialTheme.colorScheme.onSurface
    val strongContrast = navigationRail && LocalPlatformAccessibilityPreferences.current.increaseContrast
    return ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = if (navigationRail) 5.dp else 7.dp,
        shape = RoundedCornerShape(if (navigationRail) 3.dp else 4.dp),
        hoverDurationMillis = if (LocalMotionEnabled.current) MangaMotion.Quick else 0,
        unhoverColor = ink.copy(alpha = if (strongContrast) .85f else if (navigationRail) .15f else .18f),
        hoverColor = ink.copy(alpha = if (strongContrast) 1f else if (navigationRail) .35f else .46f),
    )
}

@Composable
internal actual fun DesktopVerticalScrollbar(state: ScrollState, modifier: Modifier) {
    if (state.canScrollBackward || state.canScrollForward) VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state), modifier = modifier,
        style = desktopScrollbarStyle(LocalDesktopNavigationScrollbar.current))
}

@Composable
internal actual fun DesktopVerticalScrollbar(state: LazyListState, modifier: Modifier) {
    if (state.canScrollBackward || state.canScrollForward) VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state), modifier = modifier, style = desktopScrollbarStyle())
}

@Composable
internal actual fun DesktopVerticalScrollbar(state: LazyGridState, modifier: Modifier) {
    if (state.canScrollBackward || state.canScrollForward) VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state), modifier = modifier, style = desktopScrollbarStyle())
}
