package dev.mangaflow.enhancement

import androidx.compose.runtime.staticCompositionLocalOf
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import dev.mangaflow.data.Page
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.experience.ExperienceBudget
import dev.mangaflow.experience.readerPageCacheKey
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.sha256File
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.reader.readerFilePath
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.concurrent.Volatile
import kotlin.math.abs

data class ReaderEnhancedPage(
    val identity: EnhancedAssetIdentity,
    val fileUri: String,
    val width: Int,
    val height: Int,
    val sourceCacheIdentity: String,
    val elapsedMillis: Long,
    val cacheHit: Boolean,
)

data class ReaderEnhancementState(
    val currentPage: Int = 0,
    val currentEnhanced: Boolean = false,
    val nextReady: Boolean = false,
    val queued: Int = 0,
    val running: Int = 0,
    val detail: String? = null,
    val elapsedP50Millis: Double? = null,
    val elapsedP95Millis: Double? = null,
    val cacheHits: Long = 0L,
    val cacheLookups: Long = 0L,
    val assets: Map<Int, ReaderEnhancedPage> = emptyMap(),
)

/**
 * A reader owns identities and the useful page window; the application scheduler owns
 * every CPU/GPU job. This controller only consumes originals the existing reader fetched.
 */
internal class ReaderEnhancementController(
    private val context: PlatformContext,
    val content: EnhancementContent,
    private val workId: String,
    private val chapterId: String,
    private val sourceId: String,
    parentScope: CoroutineScope,
) : AutoCloseable {
    val sessionKey = "reader-enhancement:${content.directory}:${randomUuid()}"
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val center = EnhancementCenter.get(context)
    private val cache = EnhancedAssetCache.get(context)
    private val loader = SingletonImageLoader.get(imagePlatformContext(context))
    private val lock = StorageLock()
    private val originals = mutableMapOf<Int, Original>()
    private val pending = mutableMapOf<Int, Pending>()
    private val failed = mutableMapOf<Int, String>()
    private val cacheMisses = mutableMapOf<Int, String>()
    private val held = mutableMapOf<Int, EnhancedAssetLease>()
    private val assets = mutableMapOf<Int, ReaderEnhancedPage>()
    private val durations = ArrayDeque<Long>()
    private var generation = 0L
    private var policy = EnhancementPolicy()
    private var wanted = emptySet<Int>()
    private var current = 0
    private var direction = 1
    private var closed = false
    private var currentPageAt = 0L
    private var pagesPerSecond = 0.0
    private var cacheHits = 0L
    private var cacheLookups = 0L
    private var detail: String? = null
    private val mutableState = MutableStateFlow(ReaderEnhancementState())
    val state: StateFlow<ReaderEnhancementState> = mutableState.asStateFlow()

    private data class Original(val page: Page, val headers: Map<String, String>, val source: String,
        val diskKey: String, val identity: String)
    private data class Pending(val id: String, val original: Original, val generation: Long,
        val policy: EnhancementPolicy, val job: Job)
    private data class Validity(val closed: Boolean = false, val generation: Long = 0L,
        val wanted: Set<Int> = emptySet(), val originalIds: Map<Int, String> = emptyMap(),
        val pendingIds: Map<Int, String> = emptyMap())
    // Scheduler callbacks must never acquire a reader lock while holding the queue lock.
    @Volatile private var validity = Validity()

    private class CacheOnlyMiss : CancellationException("当前页没有已完成的增强结果")

    init {
        scope.launch { center.scheduler.state.collect { synchronized(lock) { if (!closed) publishLocked() } } }
        scope.launch { center.inventory.collect { synchronized(lock) { if (!closed) { detail = null; failed.clear() } }; retryUseful() } }
        scope.launch { cache.revision.map { it[content.directory] }.distinctUntilChanged().collect {
            synchronized(lock) { cacheMisses.clear() }
            retryUseful()
        } }
    }

    private fun retryUseful() = synchronized(lock) { wanted.toList() }.forEach(::queueIfUseful)

    /** Changing direction or jumping retires work; it never deletes a completed derivative. */
    fun updateWindow(page: Int, readDirection: Int, totalPages: Int, active: Boolean,
        selected: EnhancementPolicy, budget: ExperienceBudget) {
        val restart = synchronized(lock) {
            if (closed) return
            val now = monotonicTimeMillis()
            if (page != current && currentPageAt > 0L) {
                val elapsed = (now - currentPageAt).coerceAtLeast(1L)
                val observed = abs(page - current).toDouble() * 1000 / elapsed
                pagesPerSecond = if (elapsed > 10_000) observed else pagesPerSecond * .6 + observed * .4
            }
            if (page != current) currentPageAt = now
            if (page != current) failed.remove(page)
            current = page; direction = if (readDirection < 0) -1 else 1
            if (selected != policy) {
                generation++
                pending.values.forEach { center.scheduler.cancel(it.id); it.job.cancel() }
                pending.clear()
                held.values.forEach(EnhancedAssetLease::close); held.clear(); assets.clear()
                detail = null; policy = selected; failed.clear(); cacheMisses.clear()
            }
            // Completed assets remain useful even when every live processing mode is off.
            val enabled = active && selected.tier != QualityTier.ORIGINAL
            val ahead = if (PreparationMode.SMART !in selected.preparation) 0 else when {
                budget.protections.isNotEmpty() || budget.prefetchAhead <= 0 -> 0
                pagesPerSecond > 3.0 -> 1
                durations.size >= 3 && percentile(.95)!! > 1_500 -> 1
                cacheLookups >= 6 && cacheHits.toDouble() / cacheLookups < .25 && pagesPerSecond > 1.5 -> 1
                else -> minOf(2, budget.prefetchAhead)
            }
            wanted = if (!enabled) emptySet() else buildSet {
                if (page in 0 until totalPages) add(page)
                if (PreparationMode.SMART in selected.preparation) {
                    (1..ahead).map { page + direction * it }.filter { it in 0 until totalPages }.forEach(::add)
                    (page - direction).takeIf { it in 0 until totalPages }?.let(::add)
                }
            }
            pending.keys.filter { it !in wanted }.forEach { index ->
                pending.remove(index)?.let { center.scheduler.cancel(it.id); it.job.cancel() }
            }
            held.keys.filter { it !in wanted }.forEach { index -> held.remove(index)?.close(); assets.remove(index) }
            // Keep only nearby identity observations. The original prefetcher remains responsible for fetching.
            originals.keys.filter { abs(it - page) > 16 }.forEach(originals::remove)
            val relevant = wanted.sortedBy { if (it == page) -1 else abs(it - page) }
            publishLocked()
            relevant
        }
        restart.forEach(::queueIfUseful)
    }

    /** Called with the exact materialized resource, after original bytes become available. */
    fun originalReady(index: Int, page: Page, headers: Map<String, String>, actualSource: String,
        diskCacheKey: String? = null) {
        val ownedSource = page.sourceRouteId.ifBlank { actualSource.ifBlank { sourceId } }
        val diskKey = diskCacheKey ?: readerPageCacheKey(page, headers, ownedSource)
        val original = Original(page, headers.toMap(), ownedSource, diskKey,
            readerEnhancementOriginalIdentity(page, diskKey, ownedSource))
        synchronized(lock) {
            if (closed) return
            if (originals[index]?.identity != original.identity) {
                pending.remove(index)?.let { center.scheduler.cancel(it.id); it.job.cancel() }
                held.remove(index)?.close(); assets.remove(index)
                failed.remove(index)
                cacheMisses.remove(index)
            }
            originals[index] = original
            publishLocked()
        }
        queueIfUseful(index)
    }

    fun invalidate(index: Int) = synchronized(lock) {
        pending.remove(index)?.let { center.scheduler.cancel(it.id); it.job.cancel() }
        originals.remove(index); held.remove(index)?.close(); assets.remove(index)
        failed.remove(index)
        cacheMisses.remove(index)
        publishLocked()
    }

    private fun queueIfUseful(index: Int) {
        val job = synchronized(lock) {
            if (closed || index !in wanted || index in pending || index in assets) return
            val original = originals[index] ?: return
            if (failed[index] == original.identity || cacheMisses[index] == original.identity) return
            val selected = policy
            val epoch = generation
            val id = "$sessionKey:$index:$epoch:${original.identity}"
            val scheduledJob = scope.launch(start = CoroutineStart.LAZY) { prepare(index, id, epoch, selected, original) }
            pending[index] = Pending(id, original, epoch, selected, scheduledJob)
            publishLocked()
            scheduledJob
        }
        job.start()
    }

    private fun valid(index: Int, id: String, epoch: Long, original: Original): Boolean = validity.let {
        !it.closed && index in it.wanted && it.generation == epoch &&
            it.originalIds[index] == original.identity && it.pendingIds[index] == id
    }

    private suspend fun prepare(index: Int, id: String, epoch: Long, selected: EnhancementPolicy, original: Original) {
        var completedLease: EnhancedAssetLease? = null
        var cacheHit = false
        try {
            val priority = synchronized(lock) { if (index == current) EnhancementPriority.P0 else EnhancementPriority.P1 }
            val result = center.scheduler.submit(id, content, priority, sessionKey, epoch,
                estimatedBytes = 16L * 1024 * 1024,
                isCurrent = { valid(index, id, epoch, original) }, title = "第 ${index + 1} 页") { task ->
                task.checkpoint()
                val source = withContext(Dispatchers.IO) { openOriginal(original) }
                    ?: throw IllegalStateException("等待原图缓存，继续显示原画")
                var work: EnhancedAssetWork? = null
                try {
                    val sourceFile = StorageFile(source.path)
                    val before = withContext(Dispatchers.IO) { sourceFile.length() to sourceFile.lastModified() }
                    val info = center.engine.describeImage(source.path)
                    task.checkpoint()
                    val imageContent = when (selected.style) {
                        ImageStyle.AUTO -> info.content
                        ImageStyle.MONOCHROME -> EngineContent.BLACK_WHITE
                        ImageStyle.COLOR -> EngineContent.COLOR
                        ImageStyle.WEBTOON -> EngineContent.WEBTOON
                    }
                    val selection = selectReaderEnhancementModel(center, selected, imageContent)
                    val parameters = readerEnhancementParameters(center, selection.engine, imageContent)
                    val originalDigest = withContext(Dispatchers.IO) { sha256File(source.path) }
                    check(withContext(Dispatchers.IO) { sourceFile.length() to sourceFile.lastModified() } == before) {
                        "原图已改变，继续显示最新原画"
                    }
                    val identity = EnhancedAssetIdentity(content.directory, original.source,
                        readerEnhancementSourceVersion(original.page),
                        workId, chapterId, index.toString(), originalDigest,
                        selection.model?.id ?: EnhancementEngineIds.LOCAL_DETAIL,
                        selection.model?.version ?: EnhancementEngineIds.LOCAL_DETAIL_VERSION,
                        selection.model?.sha256 ?: EnhancementEngineIds.LOCAL_DETAIL_DIGEST,
                        selected.scale, parameters.cacheIdentity, "png")
                    val cached = cache.acquire(identity)
                    synchronized(lock) { cacheLookups++; if (cached != null) cacheHits++; publishLocked() }
                    center.telemetry.recordCacheLookup(sessionKey, content, cached != null)
                    if (cached != null) {
                        completedLease?.close(); completedLease = cached; cacheHit = true
                        task.checkpoint()
                        return@submit EngineOutput(cached.file.path, cached.width, cached.height, cached.outputDigest,
                            withContext(Dispatchers.IO) { cached.file.length() }, selection.engine,
                            parameters.engineVersion,
                            identity.modelDigest, elapsedMillis = 0L)
                    }
                    if (selected.preparation.none { it == PreparationMode.IMMEDIATE || it == PreparationMode.SMART }) {
                        synchronized(lock) { if (valid(index, id, epoch, original)) {
                            cacheMisses[index] = original.identity
                            detail = "尚无已完成的增强结果；原图可直接阅读"; publishLocked()
                        } }
                        // End a cache lookup normally from the queue's perspective: there is
                        // no EngineOutput to fabricate, and this is not a recoverable GPU wait.
                        center.scheduler.cancel(id)
                        throw CacheOnlyMiss()
                    }
                    val currentWork = cache.prepare(identity).also { work = it }
                    val input = StorageFile(currentWork.directory, "original.${sourceFile.extension.takeIf { it.isNotBlank() } ?: "image"}")
                    withContext(Dispatchers.IO) {
                        sourceFile.copyTo(input)
                        check(sha256File(input.path) == originalDigest) { "原图在复制时发生变化" }
                    }
                    source.close()
                    task.checkpoint()
                    val output = center.engine.processImage(EngineImageRequest(input.path, currentWork.output.path,
                        engineId = selection.engine, content = imageContent, scale = selected.scale, model = selection.model,
                        tileSize = parameters.tileSize, overlap = parameters.overlap,
                        maximumOutputBytes = minOf(center.resources.state.value.runtime.maxImageBytes,
                            selected.cacheMiB.toLong() * 1024 * 1024).coerceAtLeast(1L)), task.progress)
                    task.checkpoint()
                    check(output.engineId == parameters.engineId &&
                        output.modelSha256 == identity.modelDigest && StorageFile(output.path) == currentWork.output) {
                        "增强模型或输出身份不匹配"
                    }
                    check(output.width.toLong() == info.width.toLong() * selected.scale &&
                        output.height.toLong() == info.height.toLong() * selected.scale) { "增强输出倍率与请求不符" }
                    completedLease?.close()
                    completedLease = cache.publish(currentWork, output.width, output.height) { valid(index, id, epoch, original) }
                    work = null
                    output
                } finally {
                    source.close()
                    withContext(NonCancellable) { work?.let { cache.abandon(it) } }
                }
            }.await()
            currentCoroutineContext().ensureActive()
            val lease = completedLease ?: error("增强结果尚未发布")
            if (!valid(index, id, epoch, original)) return
            synchronized(lock) {
                if (!valid(index, id, epoch, original)) return
                held.remove(index)?.close(); held[index] = lease; completedLease = null
                assets[index] = ReaderEnhancedPage(lease.identity, lease.file.toURI(), lease.width, lease.height,
                    original.diskKey, result.elapsedMillis, cacheHit)
                if (!cacheHit) { durations.addLast(result.elapsedMillis); while (durations.size > 128) durations.removeFirst() }
                detail = null; publishLocked()
            }
            center.telemetry.recordImage(sessionKey, content, result, cacheHit)
            cache.trimToBytes(selected.cacheMiB.toLong() * 1024 * 1024, content.directory)
        } catch (cancelled: CancellationException) {
            if (synchronized(lock) { cacheMisses[index] != original.identity }) throw cancelled
        }
        catch (failure: Exception) {
            synchronized(lock) { if (valid(index, id, epoch, original)) {
                failed[index] = original.identity
                detail = failure.message ?: "增强失败，原画仍可阅读"; publishLocked()
            } }
        } finally {
            completedLease?.close()
            synchronized(lock) {
                if (pending[index]?.id == id) pending.remove(index)
                publishLocked()
            }
        }
    }

    private class OriginalFile(val path: String, private var snapshot: DiskCache.Snapshot?) : AutoCloseable {
        override fun close() { snapshot?.close(); snapshot = null }
    }
    private fun openOriginal(original: Original): OriginalFile? {
        val local = readerFilePath(original.page.url)
        if (local != null) return local.takeIf { StorageFile(it).isFile }?.let { OriginalFile(it, null) }
        val snapshot = loader.diskCache?.openSnapshot(original.diskKey) ?: return null
        return OriginalFile(snapshot.data.toString(), snapshot)
    }

    private fun percentile(fraction: Double): Double? = durations.takeIf { it.isNotEmpty() }?.sorted()?.let {
        it[(kotlin.math.ceil(it.size * fraction).toInt() - 1).coerceIn(it.indices)].toDouble()
    }
    private fun publishLocked() {
        validity = Validity(closed, generation, wanted, originals.mapValues { it.value.identity }, pending.mapValues { it.value.id })
        val currentEnhanced = assets[current] != null
        val nextReady = assets[current + direction] != null
        val tasks = center.scheduler.state.value.filter { it.sessionKey == sessionKey }
        mutableState.value = ReaderEnhancementState(current, currentEnhanced, nextReady,
            tasks.count { it.phase in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING, EnhancementTaskPhase.PAUSED) },
            tasks.count { it.phase == EnhancementTaskPhase.RUNNING }, detail,
            percentile(.5), percentile(.95), cacheHits, cacheLookups, assets.toMap())
        if (!closed) {
            val visible = assets[current]
            val model = visible?.identity?.modelId?.let { id ->
                center.inventory.value.models.firstOrNull { it.id == id }?.title
                    ?: center.inventory.value.engines.firstOrNull { it.id == id }?.title ?: id
            }
            center.telemetry.setReaderState(sessionKey, content, currentEnhanced, nextReady, detail,
                model = model, modelDigest = visible?.identity?.modelDigest,
                outputWidth = visible?.width, outputHeight = visible?.height)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true; generation++
            validity = validity.copy(closed = true, generation = generation)
            center.scheduler.cancelSession(sessionKey)
            held.values.forEach(EnhancedAssetLease::close); held.clear(); assets.clear(); originals.clear()
            pending.values.forEach { it.job.cancel() }; pending.clear()
            scope.cancel()
            center.settings.setSessionOverride(sessionKey, content, null)
            center.telemetry.endSession(sessionKey)
        }
    }
}

internal val LocalReaderEnhancement = staticCompositionLocalOf<ReaderEnhancementController?> { null }

internal data class ReaderEnhancementModel(val engine: String, val model: EngineModel?)

internal data class ReaderEnhancementParameters(
    val engineId: String,
    val engineVersion: String,
    val tileSize: Int,
    val overlap: Int,
    val cacheIdentity: String,
)

/** One normalized request and identity for both live pages and completed-download batches. */
internal fun readerEnhancementParameters(center: EnhancementCenter, engineId: String,
    content: EngineContent): ReaderEnhancementParameters {
    val engine = center.inventory.value.engines.firstOrNull { it.id == engineId }
        ?: error("无法确认当前增强引擎版本，请刷新模型详情")
    check(engine.version.isNotBlank()) { "增强引擎版本尚未确认" }
    val tile = 256
    val overlap = if (engine.id == EnhancementEngineIds.MANGAJANAI) 128 else 32
    val identity = listOf("algorithm" to "reader-image-v1", "engine" to engine.id,
        "engineVersion" to engine.version, "content" to content.name,
        "tile" to tile.toString(), "overlap" to overlap.toString()).joinToString("") { (key, value) ->
        "${key.length}:$key${value.length}:$value"
    }
    return ReaderEnhancementParameters(engine.id, engine.version, tile, overlap, identity)
}

internal fun selectReaderEnhancementModel(center: EnhancementCenter, selected: EnhancementPolicy,
    image: EngineContent): ReaderEnhancementModel {
    val inventory = center.inventory.value
    val explicit = selected.modelId?.let { id -> inventory.models.firstOrNull { it.id == id }
        ?: error("所选模型尚未安装，请打开画质与性能安装模型") }
    val model = explicit ?: if (selected.tier in setOf(QualityTier.AUTO, QualityTier.BALANCED) && selected.scale > 1)
        inventory.models.firstOrNull { it.nativeScale == selected.scale && image in it.contents } else null
    if (model != null) {
        require(image in model.contents && model.nativeScale == selected.scale) { "模型不支持当前图片类型或倍率" }
        val engine = inventory.engines.firstOrNull { it.id == model.engineId } ?: error("增强引擎尚未安装")
        check(engine.readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL) &&
            selected.scale in engine.supportedScales) { engine.detail }
        return ReaderEnhancementModel(model.engineId, model)
    }
    check(selected.tier in setOf(QualityTier.LIGHT, QualityTier.AUTO)) { "请在模型详情选择已安装的${selected.tier.label}模型" }
    check(selected.scale == 1) { "轻量修复支持 1×；当前倍率需要兼容的 AI 模型" }
    return ReaderEnhancementModel(EnhancementEngineIds.LOCAL_DETAIL, null)
}

internal fun readerEnhancementOriginalIdentity(page: Page, diskKey: String, source: String): String =
    EnhancedAssetIdentity.digest(listOf(source, diskKey, page.sourceRevision, page.sourceSessionEpoch,
        page.sourceImageToken, page.transform, page.index).joinToString("\u0000"))

/** Content bytes are hashed separately; a validated download's local URL is not a new content version. */
internal fun readerEnhancementSourceVersion(page: Page): String = EnhancedAssetIdentity.digest(
    listOf(page.sourceRevision, page.sourceSessionEpoch, page.sourceImageToken, page.headerSecretRef,
        page.headerSecretBinding, page.transform).joinToString("") { "${it.length}:$it" })
