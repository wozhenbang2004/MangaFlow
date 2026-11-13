package dev.mangaflow.data.plugins

import dev.mangaflow.data.SourceException
import dev.mangaflow.json.JSONArray
import dev.mangaflow.platform.PlatformContext
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val UNSUPPORTED = "此平台暂不支持导入或运行 JavaScript 漫画源；可继续使用内置漫画源。"

/** The native JavaScript host has not been ported. Never report an empty execution as success. */
actual class SourcePluginStore actual constructor(context: PlatformContext, actual val runtime: JavascriptSourceRuntime) {
    actual val installed: StateFlow<List<ScriptSourcePackage>> = MutableStateFlow(emptyList<ScriptSourcePackage>()).asStateFlow()
    actual val loadError: StateFlow<String?> = MutableStateFlow<String?>(UNSUPPORTED).asStateFlow()
    actual val ready: Deferred<Unit> = CompletableDeferred(Unit)
    actual suspend fun preview(codeOrJson: String, origin: String): List<SourcePluginCandidate> = unsupported()
    actual suspend fun fetchPreview(url: String): List<SourcePluginCandidate> = unsupported()
    actual suspend fun prepare(candidate: SourcePluginCandidate): ScriptSourcePackage = unsupported()
    actual suspend fun install(bundle: ScriptSourcePackage): Unit = unsupported()
    actual suspend fun remove(sourceId: String): Unit = unsupported()
    actual suspend fun exportBackup(): JSONArray = JSONArray()
    actual suspend fun validateBackup(array: JSONArray) {
        if (array.length() != 0) unsupported()
    }
    actual suspend fun restoreBackup(array: JSONArray) { validateBackup(array) }

    actual companion object {
        private val lock = SynchronizedObject()
        private var instance: SourcePluginStore? = null
        actual fun get(context: PlatformContext): SourcePluginStore = synchronized(lock) {
            instance ?: SourcePluginStore(context, JavascriptSourceRuntime(context)).also { instance = it }
        }
    }
}

actual class JavascriptSourceRuntime actual constructor(context: PlatformContext) {
    actual suspend fun validate(bundle: ScriptSourcePackage): Unit = unsupported()
    actual suspend fun call(bundle: ScriptSourcePackage, method: String, args: JSONArray): String = unsupported()
}

private fun unsupported(): Nothing = throw SourceException(UNSUPPORTED)
