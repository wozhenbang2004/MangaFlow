package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext

/** The shared application runner owns preparation while the desktop process exists. */
internal actual class ChapterPreparationScheduler actual constructor(context: PlatformContext) {
    actual val systemManaged: Boolean = false
    actual fun schedule(workId: String) = Unit
    actual suspend fun cancel(workId: String) = Unit
}
