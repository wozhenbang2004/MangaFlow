package dev.mangaflow.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun DesktopVerticalScrollbar(state: ScrollState, modifier: Modifier) = Unit

@Composable
internal actual fun DesktopVerticalScrollbar(state: LazyListState, modifier: Modifier) = Unit

@Composable
internal actual fun DesktopVerticalScrollbar(state: LazyGridState, modifier: Modifier) = Unit
