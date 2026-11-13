package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import java.awt.Toolkit

actual fun createWallpaperPcClient(context: PlatformContext): WallpaperPcClient {
    val size = runCatching { Toolkit.getDefaultToolkit().screenSize }.getOrNull()
    return JvmWallpaperPcClient(context, "MangaFlow Windows",
        size?.width ?: 1920, size?.height ?: 1080)
}
