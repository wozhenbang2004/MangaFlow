package dev.mangaflow.storage

import dev.mangaflow.net.writeRelayBinding
import dev.mangaflow.net.readRelayBinding

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.data.foregroundRequest
import dev.mangaflow.experience.AdmissionWait
import dev.mangaflow.experience.AdmissionWaitReason
import dev.mangaflow.experience.ExperienceAdmissionDeferred
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadProxyEndpoint
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.checked
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.dataDirectory
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class ChapterPreparationOutcome { SUCCESS, RETRY, FAILURE }

/** OS wake-up is separate from the one application-owned, persisted preparation protocol. */
internal expect class ChapterPreparationScheduler(context: PlatformContext) {
    val systemManaged: Boolean
    fun schedule(workId: String)
    suspend fun cancel(workId: String)
}

/**
 * A confirmed download enters this journal before requesting its chapter manifest. Pages and
 * authenticated image headers still belong exclusively to DownloadStore after the handoff.
 */
class ChapterPreparationStore(
    context: PlatformContext,
    private val repository: MangaRepository,
    private val downloads: DownloadStore,
    private val scope: CoroutineScope,
    private val awaitSourcesReady: suspend () -> Unit = {},
) {
    private val app = applicationContext(context)
    private val directory = StorageFile(dataDirectory(app), "chapter-preparations")
    private val scheduler = ChapterPreparationScheduler(app)
    private val lock = StorageLock()
    private val mutation = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val records = linkedMapOf<String, Preparation>()
    private val cancelled = mutableSetOf<String>()
    private val localRuns = mutableMapOf<String, Job>()
    private val executing = mutableMapOf<String, Job>()
    private val gates = Array(16) { Mutex() }
    private var sequence = 0L
    private val mutableState = MutableStateFlow<List<ChapterPreparationState>>(emptyList())
    val state = mutableState.asStateFlow()
    private val mutableRefreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshingDownloads = mutableRefreshing.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val persistenceError = mutableError.asStateFlow()

    private data class Target(val entryId: String, val generation: String, val workId: String)
    private class JournalFailure(cause: Exception) : Exception("章节准备状态尚未保存", cause)
    private data class Preparation(
        val state: ChapterPreparationState,
        val workId: String,
        val target: Target? = null,
        val retryAt: Long = 0L,
        val attempts: Int = 0,
    )

    init {
        scope.launch(Dispatchers.IO) {
            try {
                check(directory.isDirectory || directory.mkdirs()) { "无法恢复章节准备队列，请检查本机存储。" }
                directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }.forEach { file ->
                    try {
                        require(file.length() in 1..2_097_152L)
                        val restored = readPreparation(JSONObject(StorageAtomicFile(file).openRead().bufferedReader().use { it.readText() }))
                        require(file.name == digest(restored.state.id) + ".json")
                        val settled = if (restored.state.phase == ChapterPreparationPhase.FAILED) restored else restored.copy(
                            state = restored.state.copy(phase = ChapterPreparationPhase.WAITING,
                                error = "准备任务已恢复，等待执行"))
                        synchronized(lock) {
                            records[settled.state.id] = settled
                            sequence = maxOf(sequence, settled.state.generation)
                        }
                    } catch (_: Exception) {
                        // Retain an unreadable journal instead of erasing a user's frozen intent.
                        mutableError.value = "部分章节准备记录无法恢复，请检查本机存储；原记录已保留。"
                    }
                }
                synchronized(lock) { publish() }
            } catch (failure: Exception) {
                mutableError.value = failure.message ?: "章节准备队列暂时无法恢复。"
            } finally {
                mutableReady.value = true
                initialized.complete(Unit)
            }
            resumePending()
        }
    }

    fun enqueue(
        manga: Manga, chapter: Chapter, routeMode: DownloadRouteMode? = null,
        refreshingEntry: DownloadEntry? = null, frozenRoute: DownloadRouteSnapshot? = null,
    ) {
        // Capture before waiting for disk, preparation capacity or a platform execution slot.
        val route = runCatching { frozenRoute?.checked() ?: DownloadRoutingStore.get(app).sourceSnapshot(
            SourceKeys.decodeChapter(chapterSourceMangaId(manga.id, chapter), chapter.id).sourceId,
            DownloadCategory.CHAPTERS, routeMode) }
        scope.launch(Dispatchers.IO) {
            initialized.await()
            downloads.awaitReady()
            val id = ChapterPreparationState.key(manga.id, chapter.id)
            var accepted: Preparation? = null
            mutation.withLock {
                if (synchronized(lock) { records[id]?.state?.phase?.let { it != ChapterPreparationPhase.FAILED } == true })
                    return@withLock
                val old = refreshingEntry ?: downloads.entryFor(manga.id, chapter.id)
                if (old?.status in SETTLED_DOWNLOADS) return@withLock
                val current = old?.let { downloads.record(it.id) }
                val changed = old != null && current?.entry !== old
                val generation = synchronized(lock) { ++sequence }
                val problem = when {
                    changed -> "下载任务已变化，请重新选择准备操作。"
                    route.isFailure -> preparationError(route.exceptionOrNull())
                    else -> ""
                }
                val state = ChapterPreparationState(id, generation, manga, chapter, routeMode,
                    route.getOrNull(), if (problem.isEmpty()) ChapterPreparationPhase.WAITING else ChapterPreparationPhase.FAILED,
                    error = problem.ifBlank { if (scheduler.systemManaged) "等待系统网络调度" else "等待准备" },
                    refreshingEntryId = current?.entry?.id)
                val next = Preparation(state, randomUuid(), current?.let { Target(it.entry.id, it.generation, it.workId) })
                try {
                    write(next)
                    synchronized(lock) {
                        records[id]?.let { cancelled.remove(it.workId) }
                        records[id] = next
                        publish()
                    }
                    if (state.phase != ChapterPreparationPhase.FAILED) accepted = next
                } catch (_: Exception) {
                    mutableError.value = "章节准备尚未保存，请检查本机存储后重试。"
                    synchronized(lock) {
                        records[id] = next.copy(state = state.copy(phase = ChapterPreparationPhase.FAILED,
                            error = "章节准备尚未保存，尚未开始网络请求。"))
                        publish()
                    }
                }
            }
            accepted?.let { schedule(it) }
        }
    }

    fun cancel(id: String, generation: Long) {
        val owned = synchronized(lock) {
            records[id]?.takeIf { it.state.generation == generation }?.also {
                cancelled += it.workId
                executing[it.workId]?.cancel()
                localRuns[it.workId]?.cancel()
            }
        } ?: return
        scope.launch(Dispatchers.IO) {
            mutation.withLock {
                if (synchronized(lock) { records[id]?.workId != owned.workId }) return@withLock
                try {
                    removeFile(id)
                    synchronized(lock) { records.remove(id); cancelled.remove(owned.workId); publish() }
                } catch (_: Exception) {
                    mutableError.value = "取消状态尚未保存，请检查本机存储后重试。"
                    synchronized(lock) {
                        records[id]?.takeIf { it.workId == owned.workId }?.let {
                            records[id] = it.copy(state = it.state.copy(phase = ChapterPreparationPhase.FAILED,
                                error = "取消状态尚未保存，请重试取消；当前进程已停止此任务。"))
                            publish()
                        }
                    }
                }
            }
            runCatching { scheduler.cancel(owned.workId) }
        }
    }

    fun retry(id: String, generation: Long, useLatestRoute: Boolean = false) {
        val current = synchronized(lock) { records[id]?.takeIf { it.state.generation == generation &&
            it.state.phase == ChapterPreparationPhase.FAILED } } ?: return
        if (!useLatestRoute && current.state.route == null) return
        val expected = current.target?.let { downloads.entryFor(current.state.manga.id, current.state.chapter.id) }
        if (current.target != null && expected?.id != current.target.entryId) {
            cancel(id, generation)
            return
        }
        enqueue(current.state.manga, current.state.chapter,
            routeMode = if (useLatestRoute) null else current.state.routeMode, refreshingEntry = expected,
            frozenRoute = if (useLatestRoute) null else current.state.route)
    }

    fun resumePending() {
        scope.launch(Dispatchers.IO) {
            initialized.await()
            val pending = synchronized(lock) { records.values.filter {
                it.state.phase != ChapterPreparationPhase.FAILED && it.workId !in cancelled
            }.toList() }
            pending.forEach { schedule(it) }
        }
    }

    /** WorkManager and native application sessions enter exactly this same journal protocol. */
    suspend fun runPending(workId: String): ChapterPreparationOutcome = withContext(Dispatchers.IO) {
        initialized.await()
        val gate = gates[(workId.hashCode() and Int.MAX_VALUE) % gates.size]
        gate.withLock execution@{
            val task = synchronized(lock) { records.values.firstOrNull { it.workId == workId && it.workId !in cancelled } }
                ?: return@execution ChapterPreparationOutcome.SUCCESS
            if (task.state.phase == ChapterPreparationPhase.FAILED) return@execution ChapterPreparationOutcome.FAILURE
            if (task.retryAt > wallTimeMillis()) return@execution ChapterPreparationOutcome.RETRY
            val job = currentCoroutineContext()[Job] ?: return@execution ChapterPreparationOutcome.RETRY
            synchronized(lock) { executing[workId] = job }
            val experience = ExperiencePolicyStore.get(app)
            val releaseResources = experience.startObserving()
            var endLease: () -> Unit = {}
            var releaseSlot: () -> Unit = {}
            val sourceTimeoutSeconds = experience.state.value.runtime.chapterPreparationTimeoutSeconds
            var sourceTimedOut = false
            try {
                endLease = beginDownloadExecution { job.cancel(CancellationException("等待系统允许继续准备")) }
                releaseSlot = experience.acquirePreparationPermit(30_000L) { wait ->
                    if (wait != null) presentation(task, waitText(wait))
                }
                downloads.awaitReady()
                val existing = downloads.entryFor(task.state.manga.id, task.state.chapter.id)
                if (existing?.status in SETTLED_DOWNLOADS) {
                    finish(task)
                    return@execution ChapterPreparationOutcome.SUCCESS
                }
                val target = task.target
                val actual = target?.let { downloads.record(it.entryId) }
                if (target != null && (actual?.generation != target.generation || actual?.workId != target.workId) ||
                    target == null && existing != null) {
                    fail(task, "下载任务已变化，已保留当前任务；可重新准备。")
                    return@execution ChapterPreparationOutcome.FAILURE
                }
                withTimeout(15_000L) { awaitSourcesReady() }
                val source = SourceKeys.decodeChapter(chapterSourceMangaId(task.state.manga.id, task.state.chapter),
                    task.state.chapter.id).sourceId
                val route = task.state.route?.checked() ?: throw DownloadRouteException("原下载线路不可用，请重新选择线路。")
                // Resource/queue waits are bounded independently from the source response.
                experience.withChapterAdmission("prepare:$workId", maxWaitMillis = 30_000L,
                    onWaiting = { wait -> if (wait != null) presentation(task, waitText(wait)) }) {
                    experience.withBackgroundPageAdmission(source, maxWaitMillis = 30_000L,
                        onWaiting = { wait -> if (wait != null) presentation(task, waitText(wait)) }) {
                        update(task) { it.copy(state = it.state.copy(phase = ChapterPreparationPhase.PREPARING, error = ""),
                            attempts = it.attempts + 1) }
                        val prepared = try { withTimeout(sourceTimeoutSeconds.toLong() * 1_000L) {
                            foregroundRequest("下载章节图片") {
                                repository.prepareDownload(app, chapterSourceMangaId(task.state.manga.id, task.state.chapter),
                                    task.state.chapter.id, route, forceRefresh = target != null)
                            }
                        } } catch (timeout: TimeoutCancellationException) { sourceTimedOut = true; throw timeout }
                        currentCoroutineContext().ensureActive()
                        if (!owns(task)) return@withBackgroundPageAdmission ChapterPreparationOutcome.SUCCESS
                        update(task) { it.copy(state = it.state.copy(phase = ChapterPreparationPhase.COMMITTING, error = "")) }
                        // Settle the existing atomic download handoff before honoring a late cancel;
                        // otherwise cancellation at an IO return could hide a newly committed writer.
                        val committed = withContext(NonCancellable) { mutation.withLock {
                            if (!owns(task)) return@withLock null
                            downloads.enqueuePreparedChapter(task.state.manga, task.state.chapter, prepared.pages,
                                prepared.headers, route, target?.entryId, target?.generation, target?.workId)
                        }
                        }
                        // A cancel that arrived during the atomic handoff owns only this new writer.
                        if (committed != null && !owns(task)) downloads.pausePreparedChapter(committed)
                        if (committed == null && owns(task)) {
                            fail(task, "下载任务已变化，已保留当前状态；可重新准备。")
                            ChapterPreparationOutcome.FAILURE
                        } else {
                            finish(task)
                            ChapterPreparationOutcome.SUCCESS
                        }
                    }
                }
            } catch (_: JournalFailure) {
                ChapterPreparationOutcome.FAILURE
            } catch (deferred: ExperienceAdmissionDeferred) {
                waiting(task, waitText(deferred.wait), deferred.wait.retryAfterMillis ?: 30_000L)
                ChapterPreparationOutcome.RETRY
            } catch (timeout: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                if (sourceTimedOut) {
                    fail(task, "来源未在 ${sourceTimeoutSeconds} 秒内返回章节信息，可重试或更换下载线路。")
                    ChapterPreparationOutcome.FAILURE
                } else {
                    waiting(task, "等待下载准备资源", 30_000L)
                    ChapterPreparationOutcome.RETRY
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { waiting(task, "等待系统允许继续准备", 30_000L) }
                throw cancelled
            } catch (failure: Exception) {
                val attempts = synchronized(lock) { records[task.state.id]?.attempts ?: task.attempts }
                if (failure is SourceException && failure.httpStatus in listOf(429, 503) &&
                    failure.retryAfterSeconds > 0L && attempts < 3) {
                    waiting(task, "来源限制请求，等待冷却后继续",
                        failure.retryAfterSeconds.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L)
                    ChapterPreparationOutcome.RETRY
                } else {
                    fail(task, preparationError(failure))
                    ChapterPreparationOutcome.FAILURE
                }
            } finally {
                releaseSlot()
                endLease()
                releaseResources()
                synchronized(lock) { if (executing[workId] === job) executing.remove(workId) }
            }
        }
    }

    private fun schedule(task: Preparation) {
        if (!owns(task) || task.state.phase == ChapterPreparationPhase.FAILED) return
        if (scheduler.systemManaged) {
            try { scheduler.schedule(task.workId) }
            catch (_: Exception) { presentation(task, "等待系统调度恢复，可重新打开应用继续") }
            return
        }
        synchronized(lock) {
            if (localRuns[task.workId]?.isActive == true) return
            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[Job]
                try {
                    while (owns(task)) {
                        val result = runPending(task.workId)
                        if (result != ChapterPreparationOutcome.RETRY) break
                        if (platformKind == PlatformKind.IOS && !ExperiencePolicyStore.get(app).state.value.foreground) break
                        val next = synchronized(lock) { records[task.state.id]?.retryAt ?: 0L }
                        delay((next - wallTimeMillis()).coerceIn(1_000L, 300_000L))
                    }
                } finally {
                    synchronized(lock) { if (localRuns[task.workId] === owner) localRuns.remove(task.workId) }
                }
            }
            localRuns[task.workId] = job
            job.start()
        }
    }

    private fun owns(task: Preparation): Boolean = synchronized(lock) {
        records[task.state.id]?.workId == task.workId && task.workId !in cancelled
    }

    private fun presentation(task: Preparation, message: String) = synchronized(lock) {
        if (!owns(task)) return@synchronized
        records[task.state.id]?.let { records[task.state.id] = it.copy(state = it.state.copy(
            phase = ChapterPreparationPhase.WAITING, error = message)) }
        publish()
    }

    private suspend fun waiting(task: Preparation, message: String, delayMillis: Long) {
        try { update(task) {
            val now = wallTimeMillis()
            val delay = delayMillis.coerceAtLeast(1_000L)
            it.copy(state = it.state.copy(phase = ChapterPreparationPhase.WAITING, error = message),
                retryAt = if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay)
        } } catch (_: JournalFailure) { /* The in-memory failure already prevents another request. */ }
    }

    private suspend fun fail(task: Preparation, message: String) {
        try { update(task) {
            it.copy(state = it.state.copy(phase = ChapterPreparationPhase.FAILED, error = message), retryAt = 0L)
        } } catch (_: JournalFailure) { /* The preserved journal may be recovered after storage is repaired. */ }
    }

    private suspend fun update(task: Preparation, transform: (Preparation) -> Preparation) {
        mutation.withLock {
            val current = synchronized(lock) { records[task.state.id]?.takeIf { owns(task) } } ?: return@withLock
            val next = transform(current)
            try { write(next) }
            catch (failure: Exception) { journalFailed(task); throw JournalFailure(failure) }
            synchronized(lock) { if (owns(task)) { records[task.state.id] = next; publish() } }
        }
    }

    private suspend fun finish(task: Preparation) {
        mutation.withLock {
            if (!owns(task)) return@withLock
            try { removeFile(task.state.id) }
            catch (failure: Exception) { journalFailed(task); throw JournalFailure(failure) }
            synchronized(lock) { if (owns(task)) { records.remove(task.state.id); publish() } }
        }
    }

    private fun publish() {
        mutableState.value = records.values.map { it.state }
        mutableRefreshing.value = records.values.filter { it.state.phase != ChapterPreparationPhase.FAILED && it.workId !in cancelled }
            .mapNotNull { it.target?.entryId }.toSet()
    }

    private fun journalFailed(task: Preparation) = synchronized(lock) {
        mutableError.value = "章节准备状态尚未保存，请检查本机存储后重试。"
        records[task.state.id]?.takeIf { owns(task) }?.let {
            records[task.state.id] = it.copy(state = it.state.copy(phase = ChapterPreparationPhase.FAILED,
                error = "任务状态尚未保存，已停止新的请求；请检查存储后重试。"))
            publish()
        }
    }

    private fun write(task: Preparation) {
        check(directory.isDirectory || directory.mkdirs()) { "无法保存章节准备队列。" }
        val atomic = StorageAtomicFile(StorageFile(directory, digest(task.state.id) + ".json"))
        val output = atomic.startWrite()
        try { output.write(task.toJson().toString().encodeToByteArray()); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    private fun removeFile(id: String) {
        val atomic = StorageAtomicFile(StorageFile(directory, digest(id) + ".json"))
        atomic.delete()
        check(!atomic.baseFile.exists()) { "无法保存章节准备任务的取消状态。" }
    }

    private fun Preparation.toJson() = JSONObject().apply {
        put("schema", 1); put("id", state.id); put("generation", state.generation); put("workId", workId)
        put("manga", state.manga.toJson()); put("chapter", state.chapter.toJson())
        put("phase", state.phase.name); put("error", state.error); put("retryAt", retryAt); put("attempts", attempts)
        state.routeMode?.let { put("routeMode", it.name) }
        state.route?.let { route -> put("route", JSONObject().apply {
            put("mode", route.mode.name)
            route.writeRelayBinding(this)
            route.proxy?.let { put("host", it.host); put("port", it.port); put("type", it.type.name) }
        }) }
        target?.let { put("target", JSONObject().apply {
            put("id", it.entryId); put("generation", it.generation); put("workId", it.workId)
        }) }
    }

    private fun readPreparation(json: JSONObject): Preparation {
        require(json.optInt("schema") == 1)
        val manga = readManga(json.getJSONObject("manga"))
        val chapter = readChapter(json.getJSONObject("chapter"))
        val id = ChapterPreparationState.key(manga.id, chapter.id)
        require(json.getString("id") == id)
        val work = json.getString("workId")
        require(WORK_ID.matches(work))
        val target = json.optJSONObject("target")?.let {
            Target(it.getString("id"), it.getString("generation"), it.getString("workId"))
                .also { expected -> require(expected.entryId == digest(id) && expected.generation.isNotBlank()) }
        }
        val route = json.optJSONObject("route")?.let { saved ->
            val mode = DownloadRouteMode.valueOf(saved.getString("mode"))
            DownloadRouteSnapshot(mode, if (mode == DownloadRouteMode.PROXY && !saved.has("relay"))
                DownloadProxyEndpoint(saved.getString("host"), saved.getInt("port"),
                    DownloadProxyType.valueOf(saved.getString("type"))) else null, readRelayBinding(saved)).checked()
        }
        val generation = json.getLong("generation").also { require(it > 0L) }
        val state = ChapterPreparationState(id, generation, manga, chapter,
            json.optString("routeMode").takeIf { it.isNotBlank() }?.let(DownloadRouteMode::valueOf), route,
            ChapterPreparationPhase.valueOf(json.getString("phase")), json.optString("error"), target?.entryId)
        require(state.phase == ChapterPreparationPhase.FAILED || route != null)
        return Preparation(state, work, target, json.optLong("retryAt"), json.optInt("attempts").coerceIn(0, 3))
    }

    private fun waitText(wait: AdmissionWait): String = when (wait.reason) {
        AdmissionWaitReason.NO_NETWORK -> "等待网络连接"
        AdmissionWaitReason.STORAGE_LOW -> "等待可用存储空间"
        AdmissionWaitReason.THERMAL -> "设备温度较高，等待恢复"
        AdmissionWaitReason.MEMORY_PRESSURE -> "等待可用内存"
        AdmissionWaitReason.BACKGROUND_RESTRICTED -> "后台准备已限制，等待回到应用"
        AdmissionWaitReason.READING_PRIORITY -> "当前阅读优先，等待准备"
        AdmissionWaitReason.SOURCE_BACKOFF -> "来源限制请求，等待冷却"
        AdmissionWaitReason.SOURCE_LIMIT -> "等待来源请求许可"
        else -> "等待下载准备资源"
    }

    private fun preparationError(failure: Throwable?): String = when {
        failure is DownloadRouteException -> failure.message ?: "下载线路不可用，请检查设置后重试。"
        failure is SourceException && failure.retryAfterSeconds > 0 -> "来源暂时限制请求，请在 ${failure.retryAfterSeconds} 秒后重试。"
        failure is SourceException && failure.httpStatus in listOf(401, 403) -> "来源拒绝获取章节信息，请检查来源账号或下载线路。"
        failure is SourceException && failure.httpStatus != null -> "来源未能返回章节信息（HTTP ${failure.httpStatus}），请稍后重试。"
        else -> "未能准备本章下载，请检查下载线路或稍后重试。"
    }

    private companion object {
        val SETTLED_DOWNLOADS = setOf(DownloadStatus.COMPLETED, DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
        val WORK_ID = Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        fun digest(value: String) = sha256(value.encodeToByteArray()).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
    }
}
