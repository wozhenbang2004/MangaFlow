package dev.mangaflow.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Desktop shares the actual content state; mobile keeps its existing gesture and layout tree. */
@Composable
internal expect fun DesktopVerticalScrollbar(state: ScrollState, modifier: Modifier = Modifier)

@Composable
internal expect fun DesktopVerticalScrollbar(state: LazyListState, modifier: Modifier = Modifier)

@Composable
internal expect fun DesktopVerticalScrollbar(state: LazyGridState, modifier: Modifier = Modifier)
