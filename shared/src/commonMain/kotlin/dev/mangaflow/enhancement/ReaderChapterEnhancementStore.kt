package dev.mangaflow.enhancement

import dev.mangaflow.data.Page
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.sha256File
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.reader.readerFilePath
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ReaderChapterEnhancementTask(
    val id: String,
    val content: EnhancementContent,
    val mangaId: String,
    val chapterId: String,
    val mangaTitle: String,
    val chapterTitle: String,
    val downloadCreatedAt: Long,
    val policy: EnhancementPolicy,
    val totalPages: Int,
    val completedPages: Int = 0,
    val phase: EnhancementTaskPhase = EnhancementTaskPhase.QUEUED,
    val reason: String? = null,
    val generation: Long = 0L,
    val activeTaskId: String? = null,
    val automatic: Boolean = false,
    val createdAt: Long = wallTimeMillis(),
)

/** Durable page-boundary recovery. Completed pages use the same verified cache as the live reader. */
class ReaderChapterEnhancementStore(context: PlatformContext, private val center: EnhancementCenter) {
    private val app = applicationContext(context)
    private val cache = EnhancedAssetCache.get(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = StorageLock()
    private val diskWriter = Mutex()
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val records = linkedMapOf<String, ReaderChapterEnhancementTask>()
    private val jobs = mutableMapOf<String, Job>()
    private var attached = false
    private var downloads: DownloadStore? = null
    private val mutableState = MutableStateFlow<List<ReaderChapterEnhancementTask>>(emptyList())
    val state: StateFlow<List<ReaderChapterEnhancementTask>> = mutableState.asStateFlow()

    init {
        readTasks().forEach { records[it.id] = it }
        publishLocked()
        scope.launch { for (ignored in writes) runCatching { persist() } }
        scope.launch { center.scheduler.state.collect { tasks ->
            synchronized(lock) {
                var changed = false
                records.entries.forEach { (id, record) ->
                    if (record.phase in TERMINAL) return@forEach
                    val active = tasks.firstOrNull { it.id == record.activeTaskId } ?: return@forEach
                    val phase = active.phase.takeIf { it !in TERMINAL } ?: record.phase
                    if (phase != record.phase || record.reason != active.reason) {
                        records[id] = record.copy(phase = phase, reason = active.reason); changed = true
                    }
                }
                if (changed) publishLocked()
            }
        } }
    }

    /** The caller supplies the authoritative content namespace from the registered source. */
    fun attach(downloadStore: DownloadStore, contentFor: (DownloadEntry) -> EnhancementContent?) {
        synchronized(lock) {
            if (attached) return
            attached = true; downloads = downloadStore
        }
        scope.launch {
            downloadStore.awaitReady()
            var previous: Set<String>? = null
            downloadStore.entries.collect { entries ->
                val complete = entries.filter { it.status == DownloadStatus.COMPLETED }
                val ids = complete.map { "${it.id}:${it.createdAt}" }.toSet()
                val prior = previous
                previous = ids
                // Restoring a saved chapter is not a newly completed download.
                if (prior == null) return@collect
                complete.filter { "${it.id}:${it.createdAt}" !in prior }.forEach { entry ->
                    val content = contentFor(entry) ?: return@forEach
                    val policy = center.settings.policy(content)
                    if (policy.enhanceAfterDownload && policy.tier != QualityTier.ORIGINAL)
                        enqueue(entry, content, policy, automatic = true)
                }
            }
        }
    }

    fun enqueue(entry: DownloadEntry, content: EnhancementContent,
        policy: EnhancementPolicy = center.settings.policy(content), automatic: Boolean = false): String {
        require(content != EnhancementContent.ANIME)
        require(entry.status == DownloadStatus.COMPLETED && entry.totalPages > 0 && entry.completedPages == entry.totalPages) {
            "原始下载完成并校验后才能整章增强"
        }
        require(policy.tier != QualityTier.ORIGINAL) { "请先在画质与性能选择增强档位" }
        policy.checked()
        val id = "chapter-enhancement:${content.directory}:" + EnhancedAssetIdentity.digest(
            listOf(entry.id, entry.createdAt, policy.tier, policy.scale, policy.style, policy.modelId).joinToString("\u0000"))
        synchronized(lock) {
            val existing = records[id]
            if (jobs[id]?.isActive == true) return id
            records[id] = ReaderChapterEnhancementTask(id, content, entry.manga.id, entry.chapter.id,
                entry.manga.title, entry.chapter.title, entry.createdAt, policy, entry.totalPages,
                completedPages = existing?.completedPages ?: 0, generation = (existing?.generation ?: -1L) + 1,
                automatic = automatic)
            trimLocked(); publishLocked()
        }
        start(id)
        return id
    }

    fun pause(id: String) = stop(id, EnhancementTaskPhase.PAUSED, "已暂停；继续时复用已完成页面")
    fun cancel(id: String) = stop(id, EnhancementTaskPhase.CANCELLED, "已取消；原文件与已完成增强结果保留")

    fun resume(id: String) {
        synchronized(lock) {
            val old = records[id] ?: return
            if (jobs[id]?.isActive == true || old.phase == EnhancementTaskPhase.COMPLETED) return
            records[id] = old.copy(phase = EnhancementTaskPhase.QUEUED, reason = null,
                generation = old.generation + 1, activeTaskId = null)
            publishLocked()
        }
        start(id)
    }

    private fun stop(id: String, phase: EnhancementTaskPhase, reason: String) {
        val target = synchronized(lock) {
            val old = records[id] ?: return
            if (old.phase == EnhancementTaskPhase.COMPLETED) return
            records[id] = old.copy(phase = phase, reason = reason, generation = old.generation + 1, activeTaskId = null)
            publishLocked()
            jobs[id] to old.activeTaskId
        }
        target.first?.cancel()
        target.second?.let(center.scheduler::cancel)
        writes.trySend(Unit)
    }

    private fun start(id: String) {
        val job = synchronized(lock) {
            if (jobs[id]?.isActive == true) return
            val record = records[id] ?: return
            scope.launch(start = CoroutineStart.LAZY) { process(record) }.also { jobs[id] = it }
        }
        job.start()
    }

    private fun isCurrent(id: String, generation: Long): Boolean {
        val task = state.value.firstOrNull { it.id == id } ?: return false
        if (task.generation != generation || task.phase in TERMINAL) return false
        // Read the published download snapshot, never take a download lock from a queue callback.
        val current = (downloads ?: DownloadStore.get(app)).entries.value.firstOrNull {
            it.manga.id == task.mangaId && it.chapter.id == task.chapterId
        } ?: return false
        return current.status == DownloadStatus.COMPLETED && current.createdAt == task.downloadCreatedAt &&
            current.totalPages == task.totalPages && current.completedPages == task.totalPages
    }

    private suspend fun process(task: ReaderChapterEnhancementTask) {
        val ownerJob = currentCoroutineContext()[Job]
        try {
            persist() // A failed durable write must not start a job that disappears after app exit.
            val store = downloads ?: DownloadStore.get(app)
            store.awaitReady()
            val entry = store.entryFor(task.mangaId, task.chapterId)
            check(entry != null && entry.status == DownloadStatus.COMPLETED && entry.createdAt == task.downloadCreatedAt) {
                "原始下载已改变或不再完整，请重新选择章节"
            }
            val pages = store.storedPages(task.mangaId, task.chapterId)
                ?: error("原始下载目录不可用")
            check(pages.size == task.totalPages) { "原始下载页数已改变" }
            update(task) { it.copy(completedPages = 0, phase = EnhancementTaskPhase.QUEUED, reason = null) }
            for ((index, page) in pages.withIndex()) {
                currentCoroutineContext().ensureActive()
                check(isCurrent(task.id, task.generation)) { "任务已改变" }
                val currentDownload = store.entryFor(task.mangaId, task.chapterId)
                check(currentDownload?.status == DownloadStatus.COMPLETED && currentDownload.createdAt == task.downloadCreatedAt) {
                    "原始下载已改变；已完成增强结果保留"
                }
                val local = store.resolvePrefetchPage(task.mangaId, task.chapterId, page)
                    ?: error("第 ${index + 1} 页原始文件校验未通过，请先修复下载")
                val path = readerFilePath(local.url) ?: error("第 ${index + 1} 页原图尚未完成下载")
                val engineTask = "${task.id}:${task.generation}:$index"
                update(task) { it.copy(activeTaskId = engineTask, phase = EnhancementTaskPhase.QUEUED, reason = null) }
                try {
                    center.scheduler.submit(engineTask, task.content,
                        if (task.automatic) EnhancementPriority.P3 else EnhancementPriority.P2,
                        sessionKey = task.id, generation = task.generation, estimatedBytes = 16L * 1024 * 1024,
                        isCurrent = { isCurrent(task.id, task.generation) },
                        title = "${task.mangaTitle} · ${task.chapterTitle} · 第 ${index + 1} 页") { work ->
                        processPage(task, local, path, local.sourceRouteId.ifBlank { entry.chapter.sourceId }, index, work)
                    }.await()
                } finally {
                    if (!currentCoroutineContext().isActive) center.scheduler.cancel(engineTask)
                }
                update(task) { it.copy(completedPages = index + 1, activeTaskId = null) }
                persist() // Only page boundaries are durable resume points.
            }
            update(task) { it.copy(phase = EnhancementTaskPhase.COMPLETED, reason = "全部页面处理完成；原始下载保留", activeTaskId = null) }
            cache.trimToBytes(task.policy.cacheMiB.toLong() * 1024 * 1024, task.content.directory)
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) update(task) { it.copy(phase = EnhancementTaskPhase.CANCELLED,
                reason = "原始下载或处理任务已改变；原图与已完成结果保留", activeTaskId = null) }
            throw cancelled
        }
        catch (error: Exception) {
            update(task) { it.copy(phase = EnhancementTaskPhase.FAILED,
                reason = error.message ?: "增强未完成，原始下载保留", activeTaskId = null) }
        } finally {
            withContext(NonCancellable) { runCatching { persist() } }
            synchronized(lock) { if (jobs[task.id] === ownerJob) jobs.remove(task.id) }
        }
    }

    private suspend fun processPage(task: ReaderChapterEnhancementTask, page: Page, sourcePath: String,
        sourceId: String, index: Int, job: EnhancementJobContext): EngineOutput {
        job.checkpoint()
        val source = StorageFile(sourcePath)
        val stamp = source.length() to source.lastModified()
        val info = center.engine.describeImage(sourcePath)
        val content = when (task.policy.style) {
            ImageStyle.AUTO -> info.content
            ImageStyle.MONOCHROME -> EngineContent.BLACK_WHITE
            ImageStyle.COLOR -> EngineContent.COLOR
            ImageStyle.WEBTOON -> EngineContent.WEBTOON
        }
        val model = selectReaderEnhancementModel(center, task.policy, content)
        val parameters = readerEnhancementParameters(center, model.engine, content)
        val digest = sha256File(sourcePath)
        check(stamp == (source.length() to source.lastModified())) { "原图在处理前发生变化" }
        val identity = EnhancedAssetIdentity(task.content.directory, sourceId,
            readerEnhancementSourceVersion(page), task.mangaId, task.chapterId,
            index.toString(), digest, model.model?.id ?: EnhancementEngineIds.LOCAL_DETAIL,
            model.model?.version ?: EnhancementEngineIds.LOCAL_DETAIL_VERSION,
            model.model?.sha256 ?: EnhancementEngineIds.LOCAL_DETAIL_DIGEST, task.policy.scale,
            parameters.cacheIdentity, "png")
        cache.acquire(identity)?.use { lease ->
            job.checkpoint()
            center.telemetry.recordCacheLookup(task.id, task.content, true)
            return EngineOutput(lease.file.path, lease.width, lease.height, lease.outputDigest, lease.file.length(),
                model.engine, parameters.engineVersion, identity.modelDigest, 0L)
        }
        center.telemetry.recordCacheLookup(task.id, task.content, false)
        val temporary = cache.prepare(identity)
        try {
            val input = StorageFile(temporary.directory, "original.${source.extension.takeIf { it.isNotBlank() } ?: "image"}")
            source.copyTo(input)
            check(sha256File(input.path) == digest) { "原图在复制时发生变化" }
            job.checkpoint()
            val output = center.engine.processImage(EngineImageRequest(input.path, temporary.output.path,
                model.engine, content, task.policy.scale, model.model,
                tileSize = parameters.tileSize, overlap = parameters.overlap,
                maximumOutputBytes = minOf(center.resources.state.value.runtime.maxImageBytes,
                    task.policy.cacheMiB.toLong() * 1024 * 1024).coerceAtLeast(1)), job.progress)
            job.checkpoint()
            check(output.engineId == parameters.engineId &&
                output.modelSha256 == identity.modelDigest && StorageFile(output.path) == temporary.output &&
                output.width.toLong() == info.width.toLong() * task.policy.scale &&
                output.height.toLong() == info.height.toLong() * task.policy.scale) { "增强输出身份或倍率不符" }
            cache.publish(temporary, output.width, output.height) { isCurrent(task.id, task.generation) }.close()
            center.telemetry.recordImage(task.id, task.content, output)
            return output
        } finally { withContext(NonCancellable) { cache.abandon(temporary) } }
    }

    private fun update(task: ReaderChapterEnhancementTask, transform: (ReaderChapterEnhancementTask) -> ReaderChapterEnhancementTask) {
        synchronized(lock) {
            val old = records[task.id] ?: return
            if (old.generation != task.generation) return
            records[task.id] = transform(old); publishLocked()
        }
        writes.trySend(Unit)
    }

    private fun publishLocked() { mutableState.value = records.values.toList().sortedByDescending { it.createdAt } }
    private fun trimLocked() {
        for (content in listOf(EnhancementContent.MANGA, EnhancementContent.ADULT_MANGA)) {
            records.values.filter { it.content == content && it.phase in TERMINAL }.sortedByDescending { it.createdAt }
                .drop(80).forEach { records.remove(it.id) }
        }
    }

    private suspend fun persist() = diskWriter.withLock {
        val snapshot = synchronized(lock) { records.values.toList() }
        for (content in listOf(EnhancementContent.MANGA, EnhancementContent.ADULT_MANGA)) {
            val values = JSONArray()
            snapshot.filter { it.content == content }.forEach { item ->
                values.put(JSONObject().put("id", item.id).put("mangaId", item.mangaId).put("chapterId", item.chapterId)
                    .put("mangaTitle", item.mangaTitle).put("chapterTitle", item.chapterTitle)
                    .put("downloadCreatedAt", item.downloadCreatedAt).put("totalPages", item.totalPages)
                    .put("completedPages", item.completedPages).put("phase", item.phase.name)
                    .put("reason", item.reason).put("generation", item.generation).put("automatic", item.automatic)
                    .put("createdAt", item.createdAt).put("tier", item.policy.tier.name).put("scale", item.policy.scale)
                    .put("style", item.policy.style.name).put("modelId", item.policy.modelId).put("cacheMiB", item.policy.cacheMiB))
            }
            check(preferences(app, "enhancement_chapters_${content.directory}").edit()
                .putString("tasks_v1", values.toString()).commit()) { "增强任务未能保存，请检查存储空间" }
        }
    }

    private fun readTasks(): List<ReaderChapterEnhancementTask> = buildList {
        for (content in listOf(EnhancementContent.MANGA, EnhancementContent.ADULT_MANGA)) {
            val raw = preferences(app, "enhancement_chapters_${content.directory}").getString("tasks_v1", null) ?: continue
            if (raw.length > 2_000_000) continue
            runCatching {
                val array = JSONArray(raw)
                repeat(minOf(array.length(), 240)) { index -> runCatching {
                    val item = array.getJSONObject(index)
                    val savedPhase = EnhancementTaskPhase.valueOf(item.getString("phase"))
                    val interrupted = savedPhase !in TERMINAL && savedPhase != EnhancementTaskPhase.PAUSED
                    val policy = EnhancementPolicy(tier = QualityTier.valueOf(item.getString("tier")),
                        scale = item.getInt("scale"), style = ImageStyle.valueOf(item.getString("style")),
                        modelId = item.optString("modelId").takeIf { it.isNotBlank() && it != "null" },
                        preparation = setOf(PreparationMode.COMPLETE), cacheMiB = item.optInt("cacheMiB", 1024)).checked()
                    ReaderChapterEnhancementTask(item.getString("id"), content, item.getString("mangaId"),
                        item.getString("chapterId"), item.optString("mangaTitle"), item.optString("chapterTitle"),
                        item.getLong("downloadCreatedAt"), policy, item.getInt("totalPages"), item.optInt("completedPages"),
                        phase = if (interrupted) EnhancementTaskPhase.PAUSED else savedPhase,
                        reason = if (interrupted) "应用曾退出；可从已完成页面继续" else item.optString("reason").takeIf { it.isNotBlank() },
                        generation = item.optLong("generation") + 1L, automatic = item.optBoolean("automatic"),
                        createdAt = item.optLong("createdAt"))
                }.getOrNull()?.let(::add) }
            }
        }
    }

    private companion object {
        val TERMINAL = setOf(EnhancementTaskPhase.COMPLETED, EnhancementTaskPhase.FAILED, EnhancementTaskPhase.CANCELLED)
    }
}
