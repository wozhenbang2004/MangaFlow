package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext

// iOS background time is OS-managed; the shared timer catches up when resumed.
internal actual fun scheduleNetworkAutoWake(context: PlatformContext, intervalMinutes: Int) = Unit
internal actual fun cancelNetworkAutoWake(context: PlatformContext) = Unit
