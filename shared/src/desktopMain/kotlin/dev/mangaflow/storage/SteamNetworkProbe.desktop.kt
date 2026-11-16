package dev.mangaflow.storage

import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.platform.PlatformContext

actual suspend fun probeSteamNetwork(
    context: PlatformContext,
    route: DownloadRouteSnapshot,
): SteamNetworkProbeResult {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("win"))
        return SteamNetworkProbeResult(false, supported = false,
            message = "当前桌面平台不支持 Steam 连接组件。")
    return runJvmSteamNetworkProbe(route) { loadInstalledSteamNative(context) }
}
