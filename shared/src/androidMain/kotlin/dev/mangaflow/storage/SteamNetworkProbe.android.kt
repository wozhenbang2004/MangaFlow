package dev.mangaflow.storage

import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import kotlinx.coroutines.CancellationException

actual suspend fun probeSteamNetwork(
    context: PlatformContext,
    route: DownloadRouteSnapshot,
): SteamNetworkProbeResult {
    val runtimes = PanelRuntimeStore.get(context)
    if (runtimes.abi == null) return SteamNetworkProbeResult(false, supported = false,
        message = "当前进程架构不支持 Steam 连接组件。")
    return runJvmSteamNetworkProbe(route) {
        try {
            // ensureLoaded verifies an installed signed component; it never downloads one.
            runtimes.ensureLoaded(PanelRuntimeId.STEAM)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LinkageError) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
