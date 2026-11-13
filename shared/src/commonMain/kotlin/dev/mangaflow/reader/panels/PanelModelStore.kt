package dev.mangaflow.reader.panels

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.Request
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.SYSTEM
import okio.HashingSink
import okio.HashingSource
import okio.buffer

internal expect fun panelModelDirectory(context: PlatformContext): StorageFile

/** Optional weights retain their exact bytes, source revision and digest on every platform. */
class PanelModelStore private constructor(context: PlatformContext) {
    private val directory = panelModelDirectory(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = SynchronizedObject()
    private val downloads = mutableMapOf<PanelModelId, Job>()
    private val clients = mutableMapOf<PanelModelId, NetworkClient>()
    private val removing = mutableSetOf<PanelModelId>()
    private val ready = CompletableDeferred<Unit>()
    private val mutableStates = MutableStateFlow(PANEL_MODEL_SPECS.mapValues { (_, spec) -> PanelModelState(totalBytes = spec.bytes) })
    val states: StateFlow<Map<PanelModelId, PanelModelState>> = mutableStates.asStateFlow()
    val revisionIdentity = PANEL_MODEL_SPECS.values.joinToString("|") { "${it.id}:${it.sha256}" }

    init {
        scope.launch {
            try {
                check(directory.isDirectory || directory.mkdirs()) { "无法创建模型目录" }
                for ((id, spec) in PANEL_MODEL_SPECS) {
                    partFile(spec).delete()
                    val file = StorageFile(directory, spec.fileName)
                    val valid = file.isFile && file.length() == spec.bytes && checksum(file) == spec.sha256
                    if (file.exists() && !valid) file.delete()
                    mutableStates.update { it + (id to PanelModelState(installed = valid,
                        bytes = if (valid) spec.bytes else 0, totalBytes = spec.bytes)) }
                }
            } catch (_: Exception) { /* A requested download reports actual storage errors. */ }
            finally { ready.complete(Unit) }
        }
    }

    fun spec(id: PanelModelId): PanelModelSpec = PANEL_MODEL_SPECS.getValue(id)
    fun modelFile(id: PanelModelId): StorageFile? {
        if (states.value[id]?.installed != true) return null
        return StorageFile(directory, spec(id).fileName).takeIf { it.isFile && it.length() == spec(id).bytes }
    }
    internal suspend fun installedFile(id: PanelModelId): StorageFile {
        ready.await()
        return modelFile(id) ?: error("请先下载${spec(id).title}")
    }
    suspend fun download(id: PanelModelId) {
        synchronized(control) {
            if (id in removing || id in downloads || modelFile(id) != null) return
            val job = scope.launch(start = CoroutineStart.LAZY) { downloadModel(id) }
            downloads[id] = job
            job.invokeOnCompletion {
                synchronized(control) {
                    if (downloads[id] === job) {
                        downloads.remove(id)
                        clients.remove(id)?.close()
                        partFile(spec(id)).delete()
                        mutableStates.update { it + (id to it.getValue(id).copy(downloading = false, progress = null)) }
                    }
                }
            }
            mutableStates.update { it + (id to it.getValue(id).copy(downloading = true, progress = 0f, error = null)) }
            job.start()
        }
    }
    fun cancel(id: PanelModelId) = synchronized(control) {
        downloads[id]?.cancel()
        clients[id]?.cancelAll()
        Unit
    }
    suspend fun remove(id: PanelModelId) = withContext(NonCancellable + Dispatchers.IO) {
        val job = synchronized(control) {
            if (!removing.add(id)) return@withContext
            clients[id]?.cancelAll()
            downloads[id]?.also { it.cancel() }
        }
        try {
            ready.await()
            job?.cancelAndJoin()
            val spec = spec(id)
            partFile(spec).delete()
            val file = StorageFile(directory, spec.fileName)
            val removed = !file.exists() || file.delete()
            mutableStates.update { it + (id to PanelModelState(installed = !removed,
                error = if (removed) null else "暂时无法删除模型，请稍后重试",
                bytes = if (removed) 0 else spec.bytes, totalBytes = spec.bytes)) }
        } finally { synchronized(control) { removing.remove(id) } }
    }

    private suspend fun downloadModel(id: PanelModelId) {
        val spec = spec(id)
        val ownJob = currentCoroutineContext()[Job]
        val part = partFile(spec)
        var installed = false
        var error: String? = null
        try {
            ready.await()
            if (modelFile(id) != null) { installed = true; return }
            check(directory.isDirectory || directory.mkdirs()) { "无法创建模型目录" }
            val client = synchronized(control) { clients.getOrPut(id) {
                NetworkClient(connectTimeoutMillis = 20_000, readTimeoutMillis = 30_000, callTimeoutMillis = 900_000)
            } }
            var address = spec.downloadUrl.toHttpUrl()
            var completed = false
            for (redirect in 0..5) {
                currentCoroutineContext().ensureActive()
                check(address.scheme == "https" && (address.host == "huggingface.co" ||
                    address.host.endsWith(".huggingface.co") || address.host.endsWith(".hf.co"))) { "模型下载地址不受信任" }
                var received = 0L
                var lastProgress = 0L
                val hashing = HashingSink.sha256(FileSystem.SYSTEM.sink(part.nativePath))
                val response = hashing.buffer().use { output ->
                    client.download(Request.Builder().url(address).build(), spec.bytes,
                        onChunk = { bytes, count -> output.write(bytes, 0, count); received += count },
                        onProgress = { bytes, _ ->
                            val now = monotonicTimeMillis()
                            if (now - lastProgress >= 100L) {
                                lastProgress = now
                                mutableStates.update { it + (id to it.getValue(id).copy(
                                    progress = bytes.toFloat() / spec.bytes, bytes = bytes)) }
                            }
                        })
                }
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    check(redirect < 5) { "模型下载重定向次数过多" }
                    address = address.resolve(response.header("Location") ?: error("模型下载地址缺失"))
                        ?: error("模型下载地址无效")
                    part.delete()
                    continue
                }
                check(response.isSuccessful) { "模型下载失败（${response.code}），可重试" }
                check(received == spec.bytes && hashing.hash.hex() == spec.sha256) { "模型校验失败，请重新下载" }
                currentCoroutineContext().ensureActive()
                check(part.renameTo(StorageFile(directory, spec.fileName))) { "模型保存失败，请重试" }
                completed = true
                break
            }
            check(completed) { "模型下载未完成，请重试" }
            installed = true
        } catch (_: CancellationException) { /* User cancellation leaves no partial model. */ }
        catch (failure: Exception) {
            error = if (!currentCoroutineContext().isActive) null else failure.message ?: "模型下载失败，请检查网络或存储空间后重试"
        } finally {
            part.delete()
            synchronized(control) {
                if (downloads[id] === ownJob) {
                    downloads.remove(id)
                    clients.remove(id)?.close()
                    mutableStates.update { it + (id to PanelModelState(installed = installed,
                        progress = if (installed) 1f else null, error = error,
                        bytes = if (installed) spec.bytes else 0, totalBytes = spec.bytes)) }
                }
            }
        }
    }
    private fun partFile(spec: PanelModelSpec) = StorageFile(directory, "${spec.fileName}.part")
    private suspend fun checksum(file: StorageFile): String {
        val hashing = HashingSource.sha256(FileSystem.SYSTEM.source(file.nativePath))
        hashing.buffer().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) >= 0) currentCoroutineContext().ensureActive()
        }
        return hashing.hash.hex()
    }
    companion object {
        private val lock = SynchronizedObject()
        private var instance: PanelModelStore? = null
        fun get(context: PlatformContext): PanelModelStore = synchronized(lock) {
            instance ?: PanelModelStore(context).also { instance = it }
        }
    }
}
