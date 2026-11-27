package dev.mangaflow.desktop

import java.io.File

/** Update only personal default links after the updater relaunches the installed app. */
internal fun refreshUserShortcutNames() {
    runCatching {
        val launcher = System.getProperty("jpackage.app-path")?.let(::File)?.canonicalFile ?: return
        if (!launcher.name.equals("MangaFlow.exe", ignoreCase = true) ||
            !File(launcher.parentFile, "app/MangaFlow.cfg").isFile) return
        val systemRoot = System.getenv("SystemRoot") ?: return
        val powershell = File(systemRoot, "System32/WindowsPowerShell/v1.0/powershell.exe")
        val script = DesktopFullscreen::class.java.getResourceAsStream("/rename-user-shortcuts.ps1")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return
        val command = ProcessBuilder(powershell.path, "-NoProfile", "-NonInteractive",
            "-WindowStyle", "Hidden", "-Command", script)
        command.environment()["KAYO_SHORTCUT_TARGET"] = launcher.path
        command.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        command.redirectError(ProcessBuilder.Redirect.DISCARD)
        command.start().outputStream.close()
    }
}
