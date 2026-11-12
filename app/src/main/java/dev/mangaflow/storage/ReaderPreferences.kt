package dev.mangaflow.storage

enum class ReadingMode { VERTICAL, LEFT_TO_RIGHT, RIGHT_TO_LEFT }

enum class ReaderTheme { INK, PAPER, SEPIA }

data class ReaderPreferences(
    val mode: ReadingMode = ReadingMode.VERTICAL,
    val theme: ReaderTheme = ReaderTheme.INK,
    val keepScreenOn: Boolean = true,
    val pageGap: Boolean = false,
    val dimAmount: Float = 0f,
)
