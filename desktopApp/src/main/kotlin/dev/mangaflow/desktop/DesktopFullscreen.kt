package dev.mangaflow.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser.*
import java.awt.EventQueue
import java.awt.Window

/** Changes the Win32 frame in place so the Compose scene and reader position stay alive. */
internal class DesktopFullscreen {
    var window: Window? = null
    var active by mutableStateOf(false)
        private set
    var restoring by mutableStateOf(false)
        private set
    private var windowed: WindowedPlacement? = null
    private var enteredInReader = false

    fun toggle(inReader: Boolean) {
        if (restoring) return
        if (active) exit() else enter(inReader)
    }

    fun onReaderClosed() {
        if (enteredInReader) exit()
    }

    private fun enter(inReader: Boolean) {
        val window = window?.takeIf { it.isShowing } ?: return
        runCatching {
            val handle = HWND(Native.getWindowPointer(window))
            val user32 = User32.INSTANCE
            val placement = WINDOWPLACEMENT().apply { length = size() }
            val monitor = MONITORINFO().apply { cbSize = size() }
            check(user32.GetWindowPlacement(handle, placement).booleanValue())
            check(user32.GetMonitorInfo(user32.MonitorFromWindow(handle, MONITOR_DEFAULTTONEAREST), monitor).booleanValue())
            val style = user32.GetWindowLong(handle, GWL_STYLE)
            check(style != 0)
            val previous = WindowedPlacement(handle, style, placement)
            windowed = previous
            enteredInReader = inReader
            active = true
            val bounds = monitor.rcMonitor
            check(user32.SetWindowLong(handle, GWL_STYLE,
                (style and (WS_OVERLAPPEDWINDOW or WS_MAXIMIZE).inv()) or WS_POPUP) != 0)
            check(user32.SetWindowPos(handle, null, bounds.left, bounds.top,
                bounds.right - bounds.left, bounds.bottom - bounds.top, SWP_NOOWNERZORDER or SWP_FRAMECHANGED))
        }.onFailure {
            System.err.println("漫流无法进入全屏：${it.message}")
            exit()
        }
    }

    fun exit() {
        val previous = windowed ?: return
        if (restoring) return
        restoring = true
        runCatching {
            val user32 = User32.INSTANCE
            check(user32.SetWindowLong(previous.handle, GWL_STYLE, previous.style and WS_MAXIMIZE.inv()) != 0)
            val positioned = user32.SetWindowPlacement(previous.handle, previous.placement).booleanValue()
            val framed = user32.SetWindowPos(previous.handle, null, 0, 0, 0, 0,
                SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOOWNERZORDER or SWP_FRAMECHANGED)
            check(positioned && framed)
        }.onFailure { System.err.println("漫流无法恢复窗口：${it.message}") }
        windowed = null
        enteredInReader = false
        active = false
        // Let the native resize events update Compose before saving windowed geometry again.
        EventQueue.invokeLater { restoring = false }
    }

    private data class WindowedPlacement(val handle: HWND, val style: Int, val placement: WINDOWPLACEMENT)
}
