package dev.mangaflow.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.awt.Toolkit
import java.beans.PropertyChangeListener
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun readToolkitPreferences(toolkit: Toolkit): PlatformAccessibilityPreferences {
    val highContrast = toolkit.getDesktopProperty("win.highContrast.on") == true ||
        (toolkit.getDesktopProperty("gnome.Net/ThemeName") as? String)?.contains("HighContrast", true) == true
    return PlatformAccessibilityPreferences(
        motionEnabled = toolkit.getDesktopProperty("win.menu.animate") != false &&
            toolkit.getDesktopProperty("gnome.Gtk/EnableAnimations") != false,
        touchExploration = !System.getProperty("javax.accessibility.assistive_technologies").isNullOrBlank(),
        reduceTransparency = highContrast,
        increaseContrast = highContrast,
    )
}

/** Read the host's documented preferences off the UI thread; absence keeps the host defaults. */
private fun preferenceCommand(vararg arguments: String): Boolean? {
    val process = runCatching { ProcessBuilder(*arguments).redirectErrorStream(true).start() }.getOrNull() ?: return null
    return try {
        if (!process.waitFor(300, TimeUnit.MILLISECONDS)) null
        else if (process.exitValue() != 0) null
        else when (process.inputStream.bufferedReader().use { it.readText() }.trim().lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    } finally { if (process.isAlive) process.destroyForcibly() }
}

@Composable
internal actual fun rememberPlatformAccessibilityPreferences(): PlatformAccessibilityPreferences {
    val toolkit = remember { Toolkit.getDefaultToolkit() }
    var preferences by remember(toolkit) { mutableStateOf(readToolkitPreferences(toolkit)) }
    DisposableEffect(toolkit) {
        val listener = PropertyChangeListener { preferences = readToolkitPreferences(toolkit) }
        val names = listOf("win.highContrast.on", "win.menu.animate", "gnome.Net/ThemeName", "gnome.Gtk/EnableAnimations")
        names.forEach { toolkit.addPropertyChangeListener(it, listener) }
        onDispose { names.forEach { toolkit.removePropertyChangeListener(it, listener) } }
    }
    LaunchedEffect(toolkit) {
        val host = System.getProperty("os.name", "").lowercase()
        val mapped = withContext(Dispatchers.IO) {
            when {
                host.contains("mac") -> preferences.copy(
                    motionEnabled = preferenceCommand("/usr/bin/defaults", "read", "com.apple.universalaccess", "reduceMotion") != true,
                    reduceTransparency = preferenceCommand("/usr/bin/defaults", "read", "com.apple.universalaccess", "reduceTransparency") == true,
                    increaseContrast = preferenceCommand("/usr/bin/defaults", "read", "com.apple.universalaccess", "increaseContrast") == true,
                )
                host.contains("linux") -> preferences.copy(
                    motionEnabled = preferenceCommand("gsettings", "get", "org.gnome.desktop.interface", "enable-animations") ?: preferences.motionEnabled,
                )
                else -> preferences
            }
        }
        preferences = mapped
    }
    return preferences
}

@Composable
internal actual fun rememberNativeFeelFeedback(): (FeelEvent) -> Boolean = remember {
    // Desktop pointer/key input has no portable actuator. The real shared press and
    // selection animation remain; no sound or artificial vibration is substituted.
    { _: FeelEvent -> false }
}

@Composable
internal actual fun MotionDialogWindowPolicy(visible: Boolean) {
    // Compose's desktop dialog uses the shared drawing/focus/input presence policy;
    // there is no Android window animation to disable.
}
