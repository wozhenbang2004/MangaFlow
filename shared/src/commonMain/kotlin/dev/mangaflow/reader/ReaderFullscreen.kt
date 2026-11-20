package dev.mangaflow.reader

import androidx.compose.runtime.compositionLocalOf

data class ReaderFullscreen(val active: Boolean, val toggle: () -> Unit)

val LocalReaderFullscreen = compositionLocalOf<ReaderFullscreen?> { null }
