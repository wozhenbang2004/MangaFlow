package dev.mangaflow.reader.panels

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageFile

internal actual fun panelModelDirectory(context: PlatformContext): StorageFile =
    StorageFile(java.io.File(context.applicationContext.noBackupFilesDir, "panel-models").path)
