package dev.mangaflow.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

internal interface DwmCaption : StdCallLibrary {
    fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: Pointer, size: Int): Int
}

private val captionApi by lazy {
    runCatching { Native.load("dwmapi", DwmCaption::class.java) }.getOrNull()
}

private data class CaptionColors(val dark: Boolean, val background: Int, val foreground: Int)

private fun Color.colorRef(): Int {
    val argb = toArgb()
    return ((argb and 0xff) shl 16) or (argb and 0x00ff00) or ((argb ushr 16) and 0xff)
}

/** Uses the native frame so Windows drag, Snap, resize and caption buttons remain native. */
@Composable
internal fun DesktopWindowChrome(window: Window, background: Color, foreground: Color) {
    val colors = CaptionColors(background.luminance() < .5f, background.colorRef(), foreground.colorRef())
    val latestColors = rememberUpdatedState(colors)
    val controller = remember(window) { NativeCaptionController(window) }
    SideEffect { controller.apply(colors) }
    DisposableEffect(window, controller) {
        val listener = object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) { controller.apply(latestColors.value, force = true) }
            override fun windowActivated(event: WindowEvent) { controller.apply(latestColors.value, force = true) }
        }
        window.addWindowListener(listener)
        onDispose { window.removeWindowListener(listener) }
    }
}

private class NativeCaptionController(private val window: Window) {
    private var lastColors: CaptionColors? = null
    private var lastHandle: Long = 0

    fun apply(colors: CaptionColors, force: Boolean = false) {
        if (!window.isDisplayable) return
        val api = captionApi ?: return
        // Older Windows can reject individual appearance attributes; keep its normal frame.
        runCatching {
            val handle = Native.getWindowPointer(window)
            val address = Pointer.nativeValue(handle)
            if (address == 0L) return
            if (!force && colors == lastColors && address == lastHandle) return
            Memory(4).use { value ->
                fun set(attribute: Int, number: Int) {
                    value.setInt(0, number)
                    api.DwmSetWindowAttribute(handle, attribute, value, 4)
                }
                set(20, if (colors.dark) 1 else 0) // DWMWA_USE_IMMERSIVE_DARK_MODE
                set(35, colors.background) // DWMWA_CAPTION_COLOR
                set(36, colors.foreground) // DWMWA_TEXT_COLOR
                set(34, colors.background) // DWMWA_BORDER_COLOR
            }
            lastHandle = address
            lastColors = colors
        }
    }
}
