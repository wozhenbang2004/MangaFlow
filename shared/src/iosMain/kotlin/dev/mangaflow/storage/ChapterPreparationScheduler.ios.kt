package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext

/** UIKit leases bound the shared runner; reopening restores its persisted queue. */
internal actual class ChapterPreparationScheduler actual constructor(context: PlatformContext) {
    actual val systemManaged: Boolean = false
    actual fun schedule(workId: String) = Unit
    actual suspend fun cancel(workId: String) = Unit
}
