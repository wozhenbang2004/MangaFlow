package dev.mangaflow.platform

import androidx.compose.runtime.compositionLocalOf

data class WindowFullscreen(
    val active: Boolean,
    val setActive: (Boolean) -> Unit,
    val onPlaybackVisibilityChange: (Boolean) -> Unit,
    val onPlaybackTitleChange: (String?) -> Unit = {},
)

val LocalWindowFullscreen = compositionLocalOf<WindowFullscreen?> { null }
