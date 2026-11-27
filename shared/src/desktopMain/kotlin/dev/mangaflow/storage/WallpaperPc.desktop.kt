package dev.mangaflow.storage

import dev.mangaflow.APP_DISPLAY_NAME
import dev.mangaflow.platform.PlatformContext
import java.awt.Toolkit

actual fun createWallpaperPcClient(context: PlatformContext): WallpaperPcClient {
    val size = runCatching { Toolkit.getDefaultToolkit().screenSize }.getOrNull()
    return JvmWallpaperPcClient(context, "$APP_DISPLAY_NAME Windows",
        size?.width ?: 1920, size?.height ?: 1080)
}
