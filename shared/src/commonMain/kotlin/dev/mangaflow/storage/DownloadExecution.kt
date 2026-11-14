package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.storage.StorageLock
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException

internal enum class DownloadOutcome { SUCCESS, RETRY, FAILURE }

internal val backgroundDownloadParallelism = if (platformKind == PlatformKind.DESKTOP) 4 else 2

/** WorkManager and native app sessions execute this exact same persisted chapter protocol. */
internal suspend fun runChapterDownload(context: PlatformContext, id: String, workId: String, attempt: Int,
    progress: suspend (DownloadEntry) -> Unit = {}): DownloadOutcome = withContext(Dispatchers.IO) {
    val store = DownloadStore.get(context)
    store.awaitReady()
    val initial = store.record(id) ?: return@withContext DownloadOutcome.SUCCESS
    if (initial.workId != workId || initial.entry.status in setOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETED)) return@withContext DownloadOutcome.SUCCESS
    if (initial.entry.needsSourceRefresh) {
        runCatching { store.update(id, workId) { it.copy(status = DownloadStatus.FAILED, error = "图片地址需要刷新，请刷新并重试") } }
        return@withContext DownloadOutcome.FAILURE
    }
    try {
        DownloadStore.transferGate.withLock {
            currentCoroutineContext().ensureActive()
            store.beginTransfer(id, workId, retryAttempt = attempt > 0) ?: return@withLock
            try {
                val selection = Mutex()
                val progressUpdates = Mutex()
                suspend fun publishProgress(): Unit = progressUpdates.withLock {
                    val current = store.record(id)?.takeIf { it.workId == workId }
                    if (current != null) progress(current.entry)
                }
                publishProgress()
                store.prepareChapter(id)
                store.directory(id).mkdirs()
                store.update(id, workId) { it.copy(status = DownloadStatus.RUNNING, error = "") }
                val visited = mutableSetOf<Int>()
                coroutineScope {
                    repeat(backgroundDownloadParallelism) {
                        launch {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val index = selection.withLock {
                                    val current = store.record(id)
                                    if (current == null || current.workId != workId ||
                                        current.entry.status in setOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETED, DownloadStatus.FAILED)) null
                                    else store.nextPageIndex(id, workId, visited)?.also { visited += it }
                                } ?: break
                                store.downloadPage(id, workId, index) ?: break
                                publishProgress()
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val finished = store.record(id) ?: return@withLock
                if (finished.workId != workId || finished.entry.status == DownloadStatus.PAUSED || finished.entry.status == DownloadStatus.FAILED) return@withLock
                if (finished.entry.completedPages != finished.pages.size) throw IOException("本地章节尚未下载完整")
                store.update(id, workId) { it.copy(status = DownloadStatus.COMPLETED, error = "") }
            } finally { store.endTransfer(id, workId) }
        }
        DownloadOutcome.SUCCESS
    } catch (cancelled: CancellationException) {
        runCatching { store.update(id, workId) { it.copy(status = DownloadStatus.WAITING, error = "等待系统恢复下载") } }
        throw cancelled
    } catch (failure: Exception) {
        val networkFailure = failure is IOException || failure is kotlinx.io.IOException
        val retryable = networkFailure && failure !is PermanentDownloadException && failure !is DownloadStorageException && attempt < 3
        runCatching { store.update(id, workId) { it.copy(status = if (retryable) DownloadStatus.WAITING else DownloadStatus.FAILED,
            error = failure.message ?: "下载未完成", needsSourceRefresh = (failure as? PermanentDownloadException)?.needsSourceRefresh == true) } }
        if (retryable) DownloadOutcome.RETRY else DownloadOutcome.FAILURE
    }
}

/** Native app sessions resume their durable manifests on every application start. */
internal class SessionDownloadScheduler(private val context: PlatformContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = StorageLock()
    private val jobs = mutableMapOf<String, Job>()
    private val states = mutableMapOf<String, DownloadTaskState>()
    fun schedule(id: String, workId: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val current = currentCoroutineContext()[Job]!!
            val release = beginDownloadExecution { current.cancel(CancellationException("系统暂停后台下载")) }
            try {
                for (attempt in 0..3) {
                    synchronized(lock) { states[workId] = DownloadTaskState.RUNNING }
                    if (runChapterDownload(context, id, workId, attempt) != DownloadOutcome.RETRY) break
                    synchronized(lock) { states[workId] = DownloadTaskState.BLOCKED }
                    delay(30_000L * (1L shl attempt))
                }
            } finally { release() }
        }
        synchronized(lock) { jobs[workId] = job; states[workId] = DownloadTaskState.ENQUEUED }
        job.invokeOnCompletion { synchronized(lock) { jobs.remove(workId); states.remove(workId) } }
        job.start()
    }
    suspend fun cancel(workId: String) { synchronized(lock) { jobs[workId] }?.cancelAndJoin() }
    fun state(workId: String): DownloadTaskState? = synchronized(lock) { states[workId] }
}
