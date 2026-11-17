package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.data.plugins.prepareJavascriptEngine
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Share the existing same-signer, ABI/hash-verified component owner; never fetch native code here. */
internal object LanercEnginePreparation {
    private val preparation = Mutex()

    suspend fun awaitReady(context: PlatformContext) {
        if (platformKind != PlatformKind.ANDROID) return
        preparation.withLock {
            val runtimes = PanelRuntimeStore.get(context)
            val id = PanelRuntimeId.QUICKJS
            try {
                val current = runtimes.states.value.getValue(id)
                if (current.installed && !current.downloading && !current.pendingRemoval && !current.requiresUpdate) {
                    prepareJavascriptEngine(context)
                    return@withLock
                }
                // download() awaits runtime discovery and deduplicates installed/queued work. It
                // also respects pending removal, unsupported ABIs and already-loaded components.
                // Keep the OS-owned job alive if this caller stops observing it.
                if (runtimes.states.value.getValue(id).downloading.not()) runtimes.download(id)
                // Source operations use the application scope. Keep this install pending until
                // its shared dependency finishes, rather than discarding intent after two minutes.
                val ready = runtimes.states.first { !it.getValue(id).downloading }.getValue(id)
                if (!ready.installed || ready.pendingRemoval || ready.requiresUpdate)
                    throw LanercCapabilityException(ready.error ?: "脚本组件尚未准备完成，请在组件管理中查看下载状态。")
                prepareJavascriptEngine(context)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: LanercCapabilityException) { throw failure }
            catch (_: Exception) {
                throw LanercCapabilityException(runtimes.states.value.getValue(id).error
                    ?: "脚本组件未能准备，请检查组件下载状态后重试；原来源仍保留。")
            }
        }
    }
}
