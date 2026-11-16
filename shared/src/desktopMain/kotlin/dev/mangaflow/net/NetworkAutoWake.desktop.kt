package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext

// Desktop uses the shared in-process timer and checks overdue work on next launch.
internal actual fun scheduleNetworkAutoWake(context: PlatformContext, intervalMinutes: Int) = Unit
internal actual fun cancelNetworkAutoWake(context: PlatformContext) = Unit
