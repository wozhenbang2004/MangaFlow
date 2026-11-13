package dev.mangaflow.storage

import dev.mangaflow.reader.panels.PanelDirection
import dev.mangaflow.reader.panels.PanelEngine

enum class ReadingMode { VERTICAL, LEFT_TO_RIGHT, RIGHT_TO_LEFT }

enum class ReaderTheme { INK, PAPER, SEPIA }

data class ReaderPreferences(
    val mode: ReadingMode = ReadingMode.VERTICAL,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val keepScreenOn: Boolean = true,
    val pageGap: Boolean = false,
    val doublePage: Boolean = false,
    val coverAlone: Boolean = true,
    val dimAmount: Float = 0f,
    // App-wide browse presentation: 1 is a list; 2–4 are cover columns.
    val discoverColumns: Int = 3,
    val updateColumns: Int = 3,
    // Desktop keeps its own explicit automatic/fixed density; phone preferences stay 1–4.
    val desktopDiscoverColumns: Int = 0,
    val desktopUpdateColumns: Int = 0,
    val panelEngine: PanelEngine = PanelEngine.OFF,
    val panelDirection: PanelDirection = PanelDirection.RIGHT_TO_LEFT,
    val panelLearnedOrder: Boolean = false,
)
