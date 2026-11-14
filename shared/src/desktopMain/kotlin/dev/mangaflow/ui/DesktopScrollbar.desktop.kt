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
import dev.mangaflow.ui.motion.MangaMotion

/** Styling only: Compose's real adapters retain scrolling, thumb dragging and track hit handling. */
@Composable
private fun desktopScrollbarStyle(): ScrollbarStyle {
    val ink = MaterialTheme.colorScheme.onSurface
    return ScrollbarStyle(
        minimalHeight = 24.dp,
        thickness = 7.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = if (LocalMotionEnabled.current) MangaMotion.Quick else 0,
        unhoverColor = ink.copy(alpha = .18f),
        hoverColor = ink.copy(alpha = .46f),
    )
}

@Composable
internal actual fun DesktopVerticalScrollbar(state: ScrollState, modifier: Modifier) {
    if (state.canScrollBackward || state.canScrollForward) VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state), modifier = modifier, style = desktopScrollbarStyle())
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
