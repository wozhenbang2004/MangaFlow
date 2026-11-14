package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

actual fun createSteamWallpaperClient(context: PlatformContext): SteamWallpaperClient {
    val runtimes = PanelRuntimeStore.get(context)
    val ownsTransfer = AtomicBoolean()
    return JvmSteamWallpaperClient(context,
        prepareNative = { allowDownload, progress ->
            suspend fun loadInstalled(): Boolean = try {
                runtimes.ensureLoaded(PanelRuntimeId.STEAM)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }
            if (loadInstalled()) true
            else if (!allowDownload) false
            else {
                try {
                    if (runtimes.states.value.getValue(PanelRuntimeId.STEAM).downloading.not()) {
                        ownsTransfer.set(true)
                        runtimes.download(PanelRuntimeId.STEAM)
                    }
                    val result = runtimes.states.map { it.getValue(PanelRuntimeId.STEAM) }.first {
                        progress(it.bytes, it.totalBytes)
                        it.installed || !it.downloading
                    }
                    result.installed && loadInstalled()
                } finally {
                    if (ownsTransfer.getAndSet(false) &&
                        !runtimes.states.value.getValue(PanelRuntimeId.STEAM).installed) {
                        runtimes.cancel(PanelRuntimeId.STEAM)
                    }
                }
            }
        },
        cancelPreparation = { if (ownsTransfer.getAndSet(false)) runtimes.cancel(PanelRuntimeId.STEAM) },
    )
}
