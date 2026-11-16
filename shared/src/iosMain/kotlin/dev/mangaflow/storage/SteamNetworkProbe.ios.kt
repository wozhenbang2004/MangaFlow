package dev.mangaflow.storage

import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.platform.PlatformContext

actual suspend fun probeSteamNetwork(
    context: PlatformContext,
    route: DownloadRouteSnapshot,
): SteamNetworkProbeResult = SteamNetworkProbeResult(false, supported = false,
    message = "iOS 暂不支持 Steam CM 连接检查。")
