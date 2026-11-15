package dev.mangaflow.account

import dev.mangaflow.platform.PlatformContext

// The live application owns execution; reopening restores the durable outbox.
internal actual fun scheduleCopyReadingWake(context: PlatformContext, delayMillis: Long) = Unit
internal actual fun cancelCopyReadingWake(context: PlatformContext) = Unit
