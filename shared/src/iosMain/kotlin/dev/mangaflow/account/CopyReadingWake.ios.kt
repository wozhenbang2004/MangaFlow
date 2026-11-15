package dev.mangaflow.account

import dev.mangaflow.platform.PlatformContext

// Execution uses the finite UIKit lease in the common runner; there is no killed-app daemon.
internal actual fun scheduleCopyReadingWake(context: PlatformContext, delayMillis: Long) = Unit
internal actual fun cancelCopyReadingWake(context: PlatformContext) = Unit
