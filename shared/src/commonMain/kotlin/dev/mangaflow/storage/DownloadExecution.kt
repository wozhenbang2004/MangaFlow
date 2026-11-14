package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.data.sources.JmDownloadSession
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.sources.createJmDownloadSession
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.NetworkFailureKind
import dev.mangaflow.net.networkFailureKind
import dev.mangaflow.experience.AdmissionWait
import dev.mangaflow.experience.ExperienceAdmissionDeferred
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.maximumBackgroundPageParallelism
import dev.mangaflow.platform.storage.StorageLock
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.IOException

internal enum class DownloadOutcome { SUCCESS, RETRY, FAILURE }

/** WorkManager and native app sessions execute this exact same persisted chapter protocol. */
internal suspend fun runChapterDownload(context: PlatformContext, id: String, workId: String, attempt: Int,
    progress: suspend (DownloadEntry) -> Unit = {}): DownloadOutcome = withContext(Dispatchers.IO) {
    val store = DownloadStore.get(context)
    store.awaitReady()
    val initial = store.record(id) ?: return@withContext DownloadOutcome.SUCCESS
    if (initial.workId != workId || initial.entry.status in setOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETED))
        return@withContext DownloadOutcome.SUCCESS
    if (initial.entry.needsSourceRefresh) {
        runCatching { store.update(id, workId) { it.copy(status = DownloadStatus.FAILED, error = "图片地址需要刷新，请刷新并重试") } }
        return@withContext DownloadOutcome.FAILURE
    }
    // Android may wake before a long Retry-After elapses. Do not start a request or charge an attempt.
    if (initial.entry.progress.retryAt?.let { it > wallTimeMillis() } == true) {
        progress(initial.entry)
        return@withContext DownloadOutcome.RETRY
    }
    val experience = ExperiencePolicyStore.get(context)
    val releaseExperience = experience.startObserving()
    val observations = launch {
        store.entries.map { entries -> entries.firstOrNull { it.id == id } }.distinctUntilChanged().collectLatest { entry ->
            if (entry != null) {
                do {
                    if (store.record(id)?.workId != workId) break
                    progress(entry)
                    if (entry.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.PAUSED, DownloadStatus.FAILED)) break
                    // Refresh the presentation clock during silence so notifications can
                    // expire an old speed sample without inventing a new transfer event.
                    delay(1_000L)
                } while (currentCoroutineContext().isActive)
            }
        }
    }
    fun waiting(token: String, wait: AdmissionWait?) {
        store.admissionWait(id, workId, token, wait?.let { DownloadWaitReason.valueOf(it.reason.name) },
            wait?.retryAfterMillis?.let { wallTimeMillis() + it.coerceAtLeast(0L) })
    }
    try {
        experience.withChapterAdmission(id, maxWaitMillis = 30_000L, onWaiting = { waiting("chapter", it) }) {
            currentCoroutineContext().ensureActive()
            val record = store.beginTransfer(id, workId,
                retryAttempt = attempt > 0 || initial.entry.status == DownloadStatus.WAITING) ?: return@withChapterAdmission
            var client: NetworkClient? = null
            var jm: JmDownloadSession? = null
            try {
                store.prepareExecution(id, workId)
                val source = SourceKeys.decodeChapter(record.entry.manga.id, record.entry.chapter.id).sourceId
                val route = checkNotNull(record.route) { "下载线路尚未准备，请重试。" }
                jm = createJmDownloadSession(context, record.entry.manga.id, record.entry.chapter.id, route)
                if (jm == null) client = NetworkClient(
                    connectTimeoutMillis = 20_000, readTimeoutMillis = 45_000, callTimeoutMillis = 90_000,
                    acceptCompression = false, route = route,
                    onTransfer = { observed -> experience.recordTransfer(source, observed.code, observed.bytes,
                        observed.elapsedMillis, observed.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value,
                        observed.failed) },
                )
                store.prepareChapter(id)
                store.directory(id).mkdirs()
                store.update(id, workId) { it.copy(status = DownloadStatus.RUNNING, error = "") }
                val selection = Mutex()
                val visited = mutableSetOf<Int>()
                var deferredWait: ExperienceAdmissionDeferred? = null
                coroutineScope {
                    repeat(maximumBackgroundPageParallelism) { slot ->
                        launch {
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                if (selection.withLock { deferredWait != null }) break
                                try {
                                    // Only new optional requests wait. In-flight pages finish safely even
                                    // when a sibling returns its chapter to the durable scheduler.
                                    val downloaded = experience.withBackgroundPageAdmission(source,
                                        maxWaitMillis = 30_000L, onWaiting = { waiting("page-$slot", it) }) {
                                        val index = selection.withLock {
                                            val current = store.record(id)
                                            if (deferredWait != null || current == null || current.workId != workId ||
                                                current.entry.status in setOf(DownloadStatus.PAUSED, DownloadStatus.COMPLETED, DownloadStatus.FAILED)) null
                                            else store.nextPageIndex(id, workId, visited)?.also { visited += it }
                                        } ?: return@withBackgroundPageAdmission false
                                        store.downloadPage(id, workId, index, client, jm)
                                            ?: return@withBackgroundPageAdmission false
                                        true
                                    }
                                    if (!downloaded) break
                                } catch (deferred: ExperienceAdmissionDeferred) {
                                    selection.withLock { if (deferredWait == null) deferredWait = deferred }
                                    break
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val finished = store.record(id) ?: return@withChapterAdmission
                if (finished.workId != workId || finished.entry.status in setOf(DownloadStatus.PAUSED, DownloadStatus.FAILED)) return@withChapterAdmission
                if (finished.entry.completedPages != finished.pages.size) {
                    deferredWait?.let { throw it }
                    throw IOException("本地章节尚未下载完整")
                }
                store.update(id, workId) { it.copy(status = DownloadStatus.COMPLETED, error = "") }
                store.record(id)?.takeIf { it.workId == workId }?.let { progress(it.entry) }
            } finally {
                store.endTransfer(id, workId) {
                    runCatching { client?.close() }
                    runCatching { jm?.close() }
                }
            }
        }
        DownloadOutcome.SUCCESS
    } catch (deferred: ExperienceAdmissionDeferred) {
        val now = wallTimeMillis()
        val wait = deferred.wait
        runCatching { store.update(id, workId) { it.copy(status = DownloadStatus.WAITING, error = "",
            progress = it.progress.copy(phase = DownloadPhase.WAITING,
                phaseStartedAt = if (it.progress.phase == DownloadPhase.WAITING &&
                    it.progress.waitingReason?.name == wait.reason.name && it.progress.phaseStartedAt > 0L) it.progress.phaseStartedAt else now, updatedAt = now,
                activePages = 0, pageIndex = null, receivedBytes = 0L, expectedBytes = null, bytesPerSecond = null,
                waitingReason = DownloadWaitReason.valueOf(wait.reason.name),
                retryAt = now + (wait.retryAfterMillis ?: 30_000L).coerceAtLeast(30_000L))) } }
        DownloadOutcome.RETRY
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) {
            runCatching { store.update(id, workId) {
                if (it.status == DownloadStatus.FAILED) it else it.copy(status = DownloadStatus.WAITING, error = "",
                    progress = it.progress.copy(phase = DownloadPhase.WAITING, bytesPerSecond = null,
                        waitingReason = DownloadWaitReason.SYSTEM_SUSPENDED, retryAt = null,
                        phaseStartedAt = wallTimeMillis(), updatedAt = wallTimeMillis()))
            } }
        }
        throw cancelled
    } catch (failure: Exception) {
        val httpStatus = failure.downloadHttpStatus()
        val attempts = store.record(id)?.takeIf { it.workId == workId }?.networkAttempts ?: initial.networkAttempts
        val retryable = failure.canRetryDownload() && attempts < 3
        val now = wallTimeMillis()
        val delay = maxOf(failure.downloadRetryDelayMillis(), 30_000L * (1L shl attempts.coerceIn(0, 3)))
        val wait = if (httpStatus in setOf(429, 503)) DownloadWaitReason.SOURCE_BACKOFF
            else if (networkFailureKind(failure) == NetworkFailureKind.NO_NETWORK) DownloadWaitReason.NO_NETWORK
            else DownloadWaitReason.NETWORK_RETRY
        runCatching { store.update(id, workId, networkFailure = failure.canRetryDownload()) {
            it.copy(status = if (retryable) DownloadStatus.WAITING else DownloadStatus.FAILED,
                error = failure.message ?: "下载未完成",
                needsSourceRefresh = failure.downloadNeedsSourceRefresh(),
                routingError = failure is DownloadRouteException,
                progress = it.progress.copy(phase = if (retryable) DownloadPhase.WAITING else DownloadPhase.FAILED,
                    activePages = 0, pageIndex = null, receivedBytes = 0L, expectedBytes = null, bytesPerSecond = null,
                    phaseStartedAt = now, updatedAt = now, waitingReason = if (retryable) wait else null,
                    retryAt = if (retryable) now + delay else null,
                    failureReason = failure.downloadFailureReason(), httpStatus = httpStatus))
        } }
        if (retryable) DownloadOutcome.RETRY else DownloadOutcome.FAILURE
    } finally {
        observations.cancel()
        releaseExperience()
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
            var execution = 0
            while (currentCoroutineContext().isActive) {
                synchronized(lock) { states[workId] = DownloadTaskState.RUNNING }
                val release = beginDownloadExecution { current.cancel(CancellationException("系统暂停后台下载")) }
                val outcome = try { runChapterDownload(context, id, workId, execution++) }
                    finally { release() }
                if (outcome != DownloadOutcome.RETRY) break
                synchronized(lock) { states[workId] = DownloadTaskState.BLOCKED }
                val retryAt = DownloadStore.get(context).record(id)?.takeIf { it.workId == workId }?.entry?.progress?.retryAt
                // Waiting owns no platform execution lease; iOS may suspend here and restore on foreground.
                delay(retryAt?.let { (it - wallTimeMillis()).coerceAtLeast(1_000L) } ?: 30_000L)
            }
        }
        val accepted = synchronized(lock) {
            if (jobs[workId]?.isCompleted == false) false
            else { jobs[workId] = job; states[workId] = DownloadTaskState.ENQUEUED; true }
        }
        if (!accepted) { job.cancel(); return }
        job.invokeOnCompletion { synchronized(lock) {
            if (jobs[workId] === job) { jobs.remove(workId); states.remove(workId) }
        } }
        job.start()
    }
    suspend fun cancel(workId: String) { synchronized(lock) { jobs[workId] }?.cancelAndJoin() }
    fun state(workId: String): DownloadTaskState? = synchronized(lock) { states[workId] }
}
