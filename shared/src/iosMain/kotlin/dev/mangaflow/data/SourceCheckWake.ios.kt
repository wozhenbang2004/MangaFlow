package dev.mangaflow.data

import dev.mangaflow.platform.PlatformContext

// UIKit supplies a finite execution lease in the shared executor, not a killed-app timer.
internal actual fun scheduleSourceCheckWake(context: PlatformContext, intervalMinutes: Int) = Unit
internal actual fun cancelSourceCheckWake(context: PlatformContext) = Unit
