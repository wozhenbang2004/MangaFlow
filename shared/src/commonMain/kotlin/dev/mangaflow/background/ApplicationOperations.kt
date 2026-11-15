package dev.mangaflow.background

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.beginDownloadExecution
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ApplicationOperationKind {
    LOCAL_IMPORT, CACHE_CLEAR, BACKUP_EXPORT, BACKUP_INSPECT, BACKUP_RESTORE,
    WEBDAV_SAVE, WEBDAV_UPLOAD, WEBDAV_READ, SOURCE_INSTALL,
}

enum class ApplicationOperationPhase {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED;
    val active: Boolean get() = this == QUEUED || this == RUNNING
}

data class ApplicationOperation(
    val id: String,
    /** An opaque digest; callers use [ApplicationOperations.find] with their original key. */
    val key: String,
    val kind: ApplicationOperationKind,
    val title: String,
    val phase: ApplicationOperationPhase,
    val message: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val statusDurable: Boolean = true,
) {
    val active: Boolean get() = phase.active
    val cancellable: Boolean get() = active && (phase == ApplicationOperationPhase.QUEUED ||
        kind != ApplicationOperationKind.BACKUP_RESTORE && kind != ApplicationOperationKind.SOURCE_INSTALL)
}

class ApplicationOperationContext internal constructor(val id: String, val kind: ApplicationOperationKind)

data class ApplicationOperationHandle<T>(val id: String, val result: Deferred<T>, val accepted: Boolean)

/**
 * Application ownership for already-confirmed operations, not another download queue.
 * Only bounded status metadata survives process death. Work, results, credentials and backup
 * payloads stay in memory; interrupted destructive/remote operations are never replayed.
 */
class ApplicationOperations private constructor(context: PlatformContext) {
    private val application = applicationContext(context)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = StorageLock()
    private val writer = Mutex()
    private val restored = CompletableDeferred<Unit>()
    private val disk by lazy { preferences(application, "mangaflow_application_operations") }
    private val records = LinkedHashMap<String, ApplicationOperation>()
    private val results = mutableMapOf<String, Deferred<*>>()
    private val mutableState = MutableStateFlow<List<ApplicationOperation>>(emptyList())
    val state = mutableState.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()

    init {
        scope.launch {
            try {
                val previous = withContext(Dispatchers.IO) { readStatuses() }
                synchronized(lock) {
                    previous.filter { old -> records.values.none { it.key == old.key } }
                        .forEach { records[it.id] = it }
                    publishLocked()
                }
            } finally {
                mutableReady.value = true
                restored.complete(Unit)
            }
        }
    }

    fun find(key: String): ApplicationOperation? {
        val identity = identity(key)
        return synchronized(lock) { records.values.lastOrNull { it.key == identity } }
    }

    fun operation(id: String): ApplicationOperation? = synchronized(lock) { records[id] }

    @Suppress("UNCHECKED_CAST")
    fun <T> result(id: String): Deferred<T>? = synchronized(lock) { results[id] as? Deferred<T> }

    /** Drop large inspected/exported payloads immediately after their UI handoff. */
    fun consumeResult(id: String) = synchronized(lock) {
        if (records[id]?.active != true) results.remove(id)
        Unit
    }

    fun cancel(id: String) {
        synchronized(lock) { results[id]?.takeIf { records[id]?.cancellable == true } }?.cancel()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> submit(
        key: String,
        kind: ApplicationOperationKind,
        title: String,
        onTerminal: suspend () -> Unit = {},
        work: suspend ApplicationOperationContext.() -> T,
    ): ApplicationOperationHandle<T> {
        val identity = identity(key)
        val id = randomUuid()
        lateinit var task: Deferred<T>
        synchronized(lock) {
            records.values.lastOrNull { it.key == identity && it.active }?.let { existing ->
                if (existing.kind != kind) return ApplicationOperationHandle(existing.id,
                    CompletableDeferred<T>().also { it.completeExceptionally(IllegalStateException("已有操作正在执行")) }, false)
                val running = results[existing.id] as? Deferred<T>
                if (running != null) return ApplicationOperationHandle(existing.id, running, false)
            }
            records.values.filter { it.key == identity }.map { it.id }.forEach {
                records.remove(it)
                results.remove(it)
            }
            records[id] = ApplicationOperation(id, identity, kind, title.take(80),
                ApplicationOperationPhase.QUEUED, startedAt = wallTimeMillis())
            task = scope.async(start = CoroutineStart.LAZY) {
                val release = beginDownloadExecution { task.cancel(OperationSystemInterrupted()) }
                try {
                    restored.await()
                    // Persist acceptance before performing a remote write or replacing local records.
                    requireAcceptedStatus(id, kind)
                    synchronized(lock) {
                        records[id]?.let { records[id] = it.copy(phase = ApplicationOperationPhase.RUNNING) }
                        publishLocked()
                    }
                    requireAcceptedStatus(id, kind)
                    work(ApplicationOperationContext(id, kind))
                } finally {
                    release()
                }
            }
            results[id] = task
            publishLocked()
        }
        task.invokeOnCompletion { failure ->
            // Completion is application-owned even when cancellation happened before work began.
            scope.launch(NonCancellable) {
                val cleanupFailed = try { onTerminal(); false } catch (_: Exception) { true }
                val phase = when (failure) {
                    null -> ApplicationOperationPhase.SUCCEEDED
                    is OperationSystemInterrupted -> ApplicationOperationPhase.INTERRUPTED
                    is TimeoutCancellationException -> ApplicationOperationPhase.FAILED
                    is CancellationException -> ApplicationOperationPhase.CANCELLED
                    else -> ApplicationOperationPhase.FAILED
                }
                val message = when (phase) {
                    ApplicationOperationPhase.SUCCEEDED -> if (cleanupFailed) "已完成；临时授权清理未完成" else "已完成"
                    ApplicationOperationPhase.CANCELLED -> "已取消"
                    ApplicationOperationPhase.INTERRUPTED -> "系统暂停了操作，请确认保存结果后重新操作"
                    else -> when (failure) {
                        is OperationStatusPersistenceFailure -> "任务状态未保存，已停止操作；请检查可用空间"
                        is TimeoutCancellationException -> "操作超时，请重新操作"
                        else -> "未完成，请重新操作"
                    }
                }
                val completed = synchronized(lock) { records[id]?.copy(phase = phase, message = message,
                    finishedAt = wallTimeMillis(), statusDurable = true) } ?: return@launch
                // Publish completion once, with the durability qualification already known.
                val durable = persistStatuses(completed)
                synchronized(lock) {
                    if (records[id] != null) records[id] = if (durable) completed else completed.copy(
                        statusDurable = false, message = "$message；操作状态未保存，重启后请确认结果")
                    publishLocked()
                }
            }
        }
        task.start()
        return ApplicationOperationHandle(id, task, true)
    }

    private fun publishLocked() {
        val expired = records.values.filter { !it.active }.sortedBy { it.finishedAt ?: it.startedAt }
            .dropLast(TERMINAL_LIMIT).map { it.id }
        expired.forEach { records.remove(it); results.remove(it) }
        mutableState.value = records.values.toList()
    }

    private suspend fun requireAcceptedStatus(id: String, kind: ApplicationOperationKind) {
        if (persistStatuses()) {
            synchronized(lock) {
                records[id]?.takeIf { !it.statusDurable }?.let {
                    records[id] = it.copy(statusDurable = true, message = null)
                    publishLocked()
                }
            }
            return
        }
        if (kind != ApplicationOperationKind.CACHE_CLEAR) throw OperationStatusPersistenceFailure()
        // Cleanup is still useful when a full disk prevents saving its own status.
        synchronized(lock) {
            records[id]?.let { records[id] = it.copy(statusDurable = false,
                message = "状态暂未保存；缓存清理会继续，可离开此页") }
            publishLocked()
        }
    }

    private suspend fun persistStatuses(replacement: ApplicationOperation? = null): Boolean {
        restored.await()
        return writer.withLock {
            val snapshot = synchronized(lock) { records.values.map { item ->
                replacement?.takeIf { it.id == item.id } ?: item
            } }
            try { withContext(Dispatchers.IO) {
                val items = JSONArray()
                snapshot.forEach { item -> items.put(JSONObject()
                    .put("id", item.id).put("key", item.key).put("kind", item.kind.name)
                    .put("title", operationTitle(item.kind)).put("phase", item.phase.name)
                    .put("startedAt", item.startedAt).put("finishedAt", item.finishedAt ?: 0L)) }
                disk.edit().putString("statuses", items.toString()).commit()
            } } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { false }
        }
    }

    private fun readStatuses(): List<ApplicationOperation> = runCatching {
        val encoded = disk.getString("statuses", "[]").orEmpty()
        if (encoded.length > 64 * 1024) return@runCatching emptyList()
        val items = JSONArray(encoded)
        (maxOf(0, items.length() - 32) until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val kind = ApplicationOperationKind.entries.firstOrNull { it.name == item.optString("kind") }
                ?: return@mapNotNull null
            val phase = ApplicationOperationPhase.entries.firstOrNull { it.name == item.optString("phase") }
                ?: return@mapNotNull null
            val interrupted = phase.active
            ApplicationOperation(item.optString("id"), item.optString("key"), kind,
                operationTitle(kind), if (interrupted) ApplicationOperationPhase.INTERRUPTED else phase,
                if (interrupted || phase == ApplicationOperationPhase.INTERRUPTED)
                    "上次操作被系统中断，请确认保存结果后重新操作" else null,
                item.optLong("startedAt"), item.optLong("finishedAt").takeIf { it > 0L })
        }.sortedByDescending { it.startedAt }.sortedByDescending { it.phase == ApplicationOperationPhase.INTERRUPTED }
            .take(TERMINAL_LIMIT).sortedBy { it.startedAt }
    }.getOrDefault(emptyList())

    private fun identity(key: String): String = sha256(key.encodeToByteArray()).joinToString("") {
        (it.toInt() and 255).toString(16).padStart(2, '0')
    }

    companion object {
        private const val TERMINAL_LIMIT = 8
        private val instanceLock = StorageLock()
        private var instance: ApplicationOperations? = null
        fun get(context: PlatformContext): ApplicationOperations = synchronized(instanceLock) {
            instance ?: ApplicationOperations(context).also { instance = it }
        }
    }
}

private class OperationSystemInterrupted : CancellationException("系统暂停了后台操作")
private class OperationStatusPersistenceFailure : IllegalStateException("任务状态未能保存")

private fun operationTitle(kind: ApplicationOperationKind) = when (kind) {
    ApplicationOperationKind.LOCAL_IMPORT -> "导入本地漫画"
    ApplicationOperationKind.CACHE_CLEAR -> "清理缓存"
    ApplicationOperationKind.BACKUP_EXPORT -> "准备阅读备份"
    ApplicationOperationKind.BACKUP_INSPECT -> "读取阅读备份"
    ApplicationOperationKind.BACKUP_RESTORE -> "恢复阅读记录"
    ApplicationOperationKind.WEBDAV_SAVE -> "更新备份连接"
    ApplicationOperationKind.WEBDAV_UPLOAD -> "上传阅读备份"
    ApplicationOperationKind.WEBDAV_READ -> "读取远端备份"
    ApplicationOperationKind.SOURCE_INSTALL -> "安装漫画源"
}
