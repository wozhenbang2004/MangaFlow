package dev.mangaflow.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener

internal interface DwmCaption : StdCallLibrary {
    fun DwmSetWindowAttribute(window: Pointer, attribute: Int, value: Pointer, size: Int): Int
}

private val captionApi by lazy {
    runCatching { Native.load("dwmapi", DwmCaption::class.java) }.getOrNull()
}

private const val HIGH_CONTRAST = "win.highContrast.on"
private const val DEFAULT_COLOR = -1 // DWMWA_COLOR_DEFAULT
private const val HIDDEN_BORDER = -2 // DWMWA_COLOR_NONE

private data class CaptionColors(val dark: Boolean, val background: Int, val foreground: Int,
    val border: Int, val fullscreen: Boolean)

private fun Color.colorRef(): Int {
    val argb = toArgb()
    return ((argb and 0xff) shl 16) or (argb and 0x00ff00) or ((argb ushr 16) and 0xff)
}

/** Uses the native frame so Windows drag, Snap, resize and caption buttons remain native. */
@Composable
internal fun DesktopWindowChrome(window: Window, background: Color, foreground: Color,
    fullscreen: Boolean, animePlaybackVisible: Boolean) {
    val toolkit = remember { Toolkit.getDefaultToolkit() }
    var systemPalette by remember(toolkit) { mutableStateOf(toolkit.getDesktopProperty(HIGH_CONTRAST) != false) }
    val caption = if (animePlaybackVisible) Color(0xFF101014) else background
    val text = if (animePlaybackVisible) Color(0xFFFAF8FF) else foreground
    // Rejoin the OS palette when high contrast is on (or unknown), even when it
    // changes after the app has already assigned its own colours to this HWND.
    val colors = if (systemPalette) CaptionColors(true, DEFAULT_COLOR, DEFAULT_COLOR, DEFAULT_COLOR, fullscreen)
    else CaptionColors(caption.luminance() < .5f, caption.colorRef(), text.colorRef(),
        if (fullscreen || animePlaybackVisible) HIDDEN_BORDER else background.colorRef(), fullscreen)
    val latestColors = rememberUpdatedState(colors)
    val controller = remember(window) { NativeCaptionController(window) }
    SideEffect { controller.apply(colors) }
    DisposableEffect(window, controller) {
        val listener = object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) { controller.apply(latestColors.value, force = true) }
            override fun windowActivated(event: WindowEvent) { controller.apply(latestColors.value, force = true) }
        }
        val contrastListener = PropertyChangeListener { systemPalette = it.newValue != false }
        toolkit.addPropertyChangeListener(HIGH_CONTRAST, contrastListener)
        systemPalette = toolkit.getDesktopProperty(HIGH_CONTRAST) != false
        window.addWindowListener(listener)
        onDispose {
            toolkit.removePropertyChangeListener(HIGH_CONTRAST, contrastListener)
            window.removeWindowListener(listener)
        }
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
                set(33, if (colors.fullscreen) 1 else 0) // DWMWA_WINDOW_CORNER_PREFERENCE
                set(34, colors.border) // DWMWA_BORDER_COLOR
            }
            lastHandle = address
            lastColors = colors
        }
    }
}
