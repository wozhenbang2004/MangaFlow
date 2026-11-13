package dev.mangaflow.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.KeyEvent

/** Desktop injects this configuration; mobile readers have no keyboard interceptor. */
enum class ReaderKeyboardAction(val label: String) {
    PREVIOUS_PAGE("上一页"), NEXT_PAGE("下一页"),
    SCROLL_UP("向上滚动"), SCROLL_DOWN("向下滚动"),
    FIRST_PAGE("本章首页"), LAST_PAGE("本章末页"), TOGGLE_CONTROLS("显示／隐藏控制栏"),
}

class ReaderKeyboardConfiguration(
    val actionFor: (KeyEvent, Boolean) -> ReaderKeyboardAction?,
    val settings: @Composable (Boolean) -> Unit,
)

val LocalReaderKeyboard = staticCompositionLocalOf<ReaderKeyboardConfiguration?> { null }

internal data class ReaderKeyboardRequest(val serial: Int, val action: ReaderKeyboardAction)
