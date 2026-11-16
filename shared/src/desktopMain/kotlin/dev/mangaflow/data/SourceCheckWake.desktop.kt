package dev.mangaflow.data

import dev.mangaflow.platform.PlatformContext

// The application timer owns live execution; reopening checks persisted overdue work.
internal actual fun scheduleSourceCheckWake(context: PlatformContext, intervalMinutes: Int) = Unit
internal actual fun cancelSourceCheckWake(context: PlatformContext) = Unit
