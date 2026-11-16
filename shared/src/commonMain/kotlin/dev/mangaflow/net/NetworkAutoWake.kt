package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext

internal expect fun scheduleNetworkAutoWake(context: PlatformContext, intervalMinutes: Int)
internal expect fun cancelNetworkAutoWake(context: PlatformContext)
