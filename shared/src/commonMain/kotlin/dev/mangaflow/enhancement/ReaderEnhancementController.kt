package dev.mangaflow.enhancement

import androidx.compose.runtime.staticCompositionLocalOf
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import dev.mangaflow.data.Page
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.experience.ExperienceBudget
import dev.mangaflow.experience.OriginalPagePrefetchResult
import dev.mangaflow.experience.PrefetchByteBudget
import dev.mangaflow.experience.PrefetchWork
import dev.mangaflow.experience.prefetchOriginalPageResult
import dev.mangaflow.experience.readerPageCacheKey
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.sha256File
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.reader.readerFilePath
import dev.mangaflow.reader.readerPrefetchErrorLabel
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
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
    internal val attemptId: String,
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
    val currentTask: EnhancementTask? = null,
    val manuallyStoppedTaskIds: Set<String> = emptySet(),
    val visiblePages: Set<Int> = emptySet(),
    val aheadPages: Set<Int> = emptySet(),
    val nextVisiblePages: Set<Int> = emptySet(),
    val preparationDetail: String? = null,
    val currentFailure: String? = null,
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
    workSourceId: String = sourceId,
) : AutoCloseable {
    val sessionKey = "reader-enhancement:${content.directory}:${randomUuid()}"
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val center = EnhancementCenter.get(context)
    private val cache = EnhancedAssetCache.get(context)
    private val loader = SingletonImageLoader.get(imagePlatformContext(context))
    private val lock = StorageLock()
    private val originals = mutableMapOf<Int, Original>()
    private val pending = mutableMapOf<Int, Pending>()
    private val lastAttempts = mutableMapOf<Int, Attempt>()
    private val manuallyStopped = mutableMapOf<Int, Attempt>()
    private val failed = mutableMapOf<Int, PageFailure>()
    private val cacheMisses = mutableMapOf<Int, String>()
    private val waitingOriginals = mutableMapOf<Int, String>()
    private val originalRecoveryAttempts = mutableMapOf<Int, Original>()
    private val details = mutableMapOf<Int, String>()
    private val held = mutableMapOf<Int, EnhancedAssetLease>()
    private val heldBytes = mutableMapOf<Int, Long>()
    private val pageOutputBytes = mutableMapOf<Int, Long>()
    private val budgetDeferred = mutableSetOf<Int>()
    private var budgetBlocked = false
    private val cacheWaitDetail = "前方增强等待缓存额度，翻页或增加增强缓存后继续"
    private val assets = mutableMapOf<Int, ReaderEnhancedPage>()
    private val durations = ArrayDeque<Long>()
    private var elapsedP50Millis: Double? = null
    private var elapsedP95Millis: Double? = null
    private var generation = 0L
    private var nextAttempt = 0L
    private var policy = EnhancementPolicy()
    private var wanted = emptySet<Int>()
    private var visible = emptySet<Int>()
    private var ahead = emptySet<Int>()
    private var nextVisible = emptySet<Int>()
    private var queueOrder = emptyMap<Int, Int>()
    private var current = 0
    private var direction = 1
    private var closed = false
    private var cacheHits = 0L
    private var cacheLookups = 0L
    private var imageWorkingLimitMiB = center.resources.state.value.runtime.materializationMiB
    private var encodedImageLimitMiB = center.resources.state.value.runtime.maxImageMiB
    private val mutableState = MutableStateFlow(ReaderEnhancementState())
    val state: StateFlow<ReaderEnhancementState> = mutableState.asStateFlow()

    private data class Original(val page: Page, val headers: Map<String, String>, val source: String,
        val diskKey: String, val identity: String)
    private data class Pending(val id: String, val original: Original, val generation: Long,
        val policy: EnhancementPolicy, val job: Job)
    private data class Attempt(val id: String, val originalIdentity: String, val generation: Long)
    private data class PageFailure(val originalIdentity: String, val retryWhenVisible: Boolean)
    private data class Validity(val closed: Boolean = false, val generation: Long = 0L,
        val wanted: Set<Int> = emptySet(), val originalIds: Map<Int, String> = emptyMap(),
        val pendingIds: Map<Int, String> = emptyMap())
    // Scheduler callbacks must never acquire a reader lock while holding the queue lock.
    @Volatile private var validity = Validity()

    private class CacheOnlyMiss : CancellationException("当前页没有已完成的增强结果")

    init {
        center.settings.registerReaderSession(sessionKey, content, workId, workSourceId)
        scope.launch {
            center.scheduler.state.map { tasks -> tasks.filter { it.sessionKey == sessionKey } }
                .distinctUntilChanged().collect { synchronized(lock) { if (!closed) publishLocked() } }
        }
        scope.launch { center.inventory.collect {
            synchronized(lock) { if (!closed) clearFailuresLocked() }
            retryUseful()
        } }
        scope.launch { cache.revision.map { it[content.directory] }.distinctUntilChanged().collect {
            synchronized(lock) { cacheMisses.clear() }
            retryUseful()
        } }
        scope.launch {
            center.resources.state.map { it.runtime.materializationMiB to it.runtime.maxImageMiB }
                .distinctUntilChanged().collect { (workingLimit, encodedLimit) ->
                    val retry = synchronized(lock) {
                        if (closed || imageWorkingLimitMiB == workingLimit && encodedImageLimitMiB == encodedLimit) false
                        else {
                            imageWorkingLimitMiB = workingLimit
                            encodedImageLimitMiB = encodedLimit
                            clearFailuresLocked()
                            true
                        }
                    }
                    if (retry) retryUseful()
                }
        }
    }

    private fun retryUseful() = synchronized(lock) { wanted.toList() }.forEach(::queueIfUseful)

    private fun cancelPendingLocked(index: Int, reason: String) {
        val previous = pending.remove(index) ?: return
        // Invalidate the worker before cancellation can resume either coroutine.
        validity = validity.copy(pendingIds = validity.pendingIds - index)
        center.scheduler.cancel(previous.id, reason)
        previous.job.cancel(CancellationException(reason))
    }

    private fun clearFailuresLocked() {
        failed.keys.forEach { details.remove(it) }
        failed.clear()
    }

    private fun cacheBudgetBytes(): Long = synchronized(lock) { policy.cacheMiB.toLong() * 1024 * 1024 }

    private fun releaseHeldLocked(index: Int) {
        held.remove(index)?.close(); heldBytes.remove(index); assets.remove(index)
    }

    private fun clearBudgetWaitsLocked() {
        budgetDeferred.toList().forEach {
            cancelPendingLocked(it, "增强缓存窗口已更新")
            details.remove(it)
        }
        budgetDeferred.clear(); budgetBlocked = false
    }

    /** Bound retained optional files by actual bytes; active visible files keep their leases. */
    private fun retainWithinBudgetLocked() {
        val maximum = policy.cacheMiB.toLong() * 1024 * 1024
        var retained = 0L
        held.keys.sortedWith(compareBy<Int> { if (it in visible) 0 else if (it in ahead) 1 else 2 }
            .thenBy { abs(it - current) }).forEach { index ->
            val bytes = heldBytes[index] ?: 0L
            if (index in visible || bytes <= (maximum - retained).coerceAtLeast(0L)) retained += bytes
            else {
                // Dropping a window lease does not delete the completed file. Do not immediately
                // reacquire it from the cache: wait for a changed window or an explicit budget.
                releaseHeldLocked(index)
                budgetDeferred += index
                details[index] = cacheWaitDetail
                budgetBlocked = true
            }
        }
        if (retained >= maximum) budgetBlocked = true
    }

    /** Changing direction or jumping retires work; it never deletes a completed derivative. */
    fun updateWindow(page: Int, readDirection: Int, totalPages: Int, active: Boolean,
        selected: EnhancementPolicy, budget: ExperienceBudget,
        visiblePages: Set<Int> = setOf(page), nextVisiblePages: Set<Int> = emptySet(),
        aheadWindow: Set<Int>? = null) {
        val restart = synchronized(lock) {
            if (closed) return
            val previousVisible = visible
            val previousWanted = wanted
            val previousDirection = direction
            current = page; direction = if (readDirection < 0) -1 else 1
            visible = visiblePages.filterTo(linkedSetOf()) { it in 0 until totalPages }
                .ifEmpty { setOf(page).filterTo(linkedSetOf()) { it in 0 until totalPages } }
            originalRecoveryAttempts.keys.retainAll(visible)
            (visible - previousVisible).forEach { index ->
                if (failed[index]?.retryWhenVisible == true) {
                    failed.remove(index)
                    details.remove(index)
                }
            }
            val leadingEdge = if (direction > 0) visible.maxOrNull() else visible.minOrNull()
            val trailingEdge = if (direction > 0) visible.minOrNull() else visible.maxOrNull()
            nextVisible = nextVisiblePages.filterTo(linkedSetOf()) { index ->
                index in 0 until totalPages && leadingEdge != null && (index - leadingEdge) * direction > 0
            }.ifEmpty {
                setOfNotNull(leadingEdge?.plus(direction)?.takeIf { it in 0 until totalPages })
            }
            val pixelsChanged = selected.tier != policy.tier || selected.scale != policy.scale ||
                selected.style != policy.style || selected.modelId != policy.modelId
            val processingChanged = selected.allowsReaderProcessing() != policy.allowsReaderProcessing()
            val cacheBudgetChanged = selected.cacheMiB != policy.cacheMiB
            // Scheduling, cache and unrelated video options are still saved, without discarding pixels.
            policy = selected
            if (pixelsChanged) {
                generation++
                pending.keys.toList().forEach { cancelPendingLocked(it, "画质设置已变更") }
                held.values.forEach(EnhancedAssetLease::close); held.clear(); heldBytes.clear(); assets.clear()
                details.clear(); failed.clear(); cacheMisses.clear(); waitingOriginals.clear()
                pageOutputBytes.clear()
                lastAttempts.clear(); manuallyStopped.clear()
            } else if (processingChanged) {
                pending.keys.toList().forEach { cancelPendingLocked(it, "即时处理模式已变更") }
                // Completed assets have the same pixel identity and remain usable in cache-only mode.
                details.keys.filter { it !in manuallyStopped }.forEach(details::remove)
                failed.clear(); cacheMisses.clear(); waitingOriginals.clear()
            } else if (cacheBudgetChanged) {
                // A larger budget can make a failed output possible; running work keeps its attempt.
                clearFailuresLocked()
            }
            // Completed assets remain useful even when every live processing mode is off.
            val enabled = active && selected.tier != QualityTier.ORIGINAL
            if (!enabled || pixelsChanged || processingChanged) originalRecoveryAttempts.clear()
            // The shared reading budget already applies manual values and device protection.
            // Do not replace it with a private two-page/slow-model cap, or count the second
            // visible page as the next page. Admission can pause this window without erasing it.
            val aheadCount = if (PreparationMode.SMART in selected.preparation)
                budget.prefetchAhead.coerceIn(0, totalPages.coerceAtLeast(0)) else 0
            ahead = if (!enabled || leadingEdge == null || aheadCount == 0) emptySet()
                else if (aheadWindow != null) aheadWindow.filterTo(linkedSetOf()) {
                    it in 0 until totalPages && it !in visible && (it - leadingEdge) * direction > 0
                } else buildSet {
                    (1..aheadCount).map { leadingEdge + direction * it }
                        .filter { it in 0 until totalPages }.forEach(::add)
                    addAll(nextVisible)
                }
            wanted = if (!enabled) emptySet() else buildSet {
                addAll(visible)
                addAll(ahead)
                if (PreparationMode.SMART in selected.preparation && budget.prefetchBehind > 0) {
                    trailingEdge?.minus(direction)?.takeIf { it in 0 until totalPages }?.let(::add)
                }
            }
            if (previousVisible != visible || previousWanted != wanted || previousDirection != direction ||
                cacheBudgetChanged || pixelsChanged || processingChanged) clearBudgetWaitsLocked()
            manuallyStopped.keys.filter { it !in wanted }.forEach { index ->
                manuallyStopped.remove(index); details.remove(index)
            }
            pending.keys.filter { it !in wanted }.forEach { index ->
                cancelPendingLocked(index, if (!active) "阅读暂不活跃" else "页面已离开处理范围")
            }
            held.keys.filter { it !in wanted }.forEach(::releaseHeldLocked)
            retainWithinBudgetLocked()
            // Keep only nearby identity observations. The original prefetcher remains responsible for fetching.
            originals.keys.filter { it !in wanted && abs(it - page) > 16 }.forEach { index ->
                originals.remove(index); lastAttempts.remove(index); failed.remove(index); pageOutputBytes.remove(index)
                cacheMisses.remove(index); waitingOriginals.remove(index); details.remove(index); manuallyStopped.remove(index)
            }
            val relevant = wanted.sortedWith(compareBy<Int> {
                when (it) {
                    current -> 0
                    in visible -> 1
                    in nextVisible -> 2
                    in ahead -> 3
                    else -> 4
                }
            }.thenBy { abs(it - current) })
            queueOrder = relevant.withIndex().associate { (order, index) -> index to order }
            publishLocked()
            center.scheduler.updateReaderPositions(pending.map { (index, entry) ->
                entry.id to positionLocked(index)
            }.toMap())
            relevant
        }
        restart.forEach(::queueIfUseful)
    }

    private fun positionLocked(index: Int) = ReaderQueuePosition(
        if (index in visible) EnhancementPriority.P0 else EnhancementPriority.P1,
        queueOrder[index] ?: Int.MAX_VALUE,
    )

    /** Give presentation its own lease while the exact current page still belongs to this reader. */
    fun retainForDisplay(asset: ReaderEnhancedPage): EnhancedAssetLease? = synchronized(lock) {
        val index = asset.identity.pageId.toIntOrNull() ?: return@synchronized null
        if (closed || assets[index] !== asset) return@synchronized null
        held[index]?.fork()
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
                cancelPendingLocked(index, "原图身份已更新")
                releaseHeldLocked(index)
                pageOutputBytes.remove(index)
                failed.remove(index); cacheMisses.remove(index); waitingOriginals.remove(index)
                originalRecoveryAttempts.remove(index)
                lastAttempts.remove(index); details.remove(index); manuallyStopped.remove(index)
            } else if (waitingOriginals.remove(index) != null) {
                // The same exact original can reappear after cache eviction. A cache wait is
                // not a model failure and must not poison this page's later preparation.
                cancelPendingLocked(index, "原图已准备，继续增强")
                details.remove(index)
            }
            originals[index] = original
            publishLocked()
        }
        queueIfUseful(index)
    }

    /**
     * A visible desktop preview can outlive the original in Coil's disk cache.
     * The renderer calls this after confirming that the exact disk entry is missing.
     * Restore it outside the enhancement queue so a transfer cannot occupy the model slot.
     */
    suspend fun recoverMissingVisibleOriginal(index: Int, page: Page, diskKey: String) {
        val (original, epoch) = synchronized(lock) {
            val current = originals[index] ?: return
            if (closed || index !in visible || index !in wanted || !policy.allowsReaderProcessing() ||
                current.page != page || current.diskKey != diskKey || waitingOriginals[index] != current.identity ||
                originalRecoveryAttempts[index]?.identity == current.identity ||
                platformKind != PlatformKind.DESKTOP || current.source == "jmcomic" ||
                !(page.url.startsWith("https:") || page.url.startsWith("http:"))) return
            originalRecoveryAttempts[index] = current
            details[index] = "正在补取本页原图，先显示预览"
            publishLocked()
            current to generation
        }
        try {
            val timeout = center.resources.state.value.recovery.imageTimeoutSeconds.coerceAtLeast(1).toLong() * 1000L
            val work = PrefetchWork().also(PrefetchWork::promote)
            val result = try {
                withTimeoutOrNull(timeout) {
                    // This is the foreground page, so it does not consume the speculative byte window.
                    prefetchOriginalPageResult(context, page, original.headers, original.source,
                        PrefetchByteBudget(0L), work, diskCacheKey = diskKey)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OriginalPagePrefetchResult(false, failure = error)
            }
            val available = withContext(Dispatchers.IO) {
                try { openOriginal(original)?.use { true } == true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            }
            val retry = synchronized(lock) {
                if (closed || index !in visible || index !in wanted || epoch != generation ||
                    originalRecoveryAttempts[index] !== original || originals[index]?.identity != original.identity ||
                    waitingOriginals[index] != original.identity) return
                if (available) {
                    waitingOriginals.remove(index)
                    cancelPendingLocked(index, "原图已准备，继续增强")
                    details.remove(index)
                } else {
                    details[index] = when {
                        result == null -> "补取原图超时，当前显示预览"
                        result.failure != null -> "补取原图失败 · ${readerPrefetchErrorLabel(result.failure)}"
                        result.deferred -> "本页原图暂不可写入缓存，当前显示预览"
                        else -> "本页原图未能保存在缓存中，当前显示预览"
                    }
                }
                publishLocked()
                available
            }
            if (retry) queueIfUseful(index)
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (originalRecoveryAttempts[index] === original) {
                    originalRecoveryAttempts.remove(index)
                    if (waitingOriginals[index] == original.identity) {
                        details[index] = "等待原图缓存，先显示预览"
                        publishLocked()
                    }
                }
            }
            throw cancelled
        }
    }

    fun invalidate(index: Int) = synchronized(lock) {
        cancelPendingLocked(index, "本页正在重新加载")
        originals.remove(index); releaseHeldLocked(index); pageOutputBytes.remove(index)
        failed.remove(index); cacheMisses.remove(index); waitingOriginals.remove(index)
        originalRecoveryAttempts.remove(index)
        lastAttempts.remove(index); details.remove(index); manuallyStopped.remove(index)
        publishLocked()
    }

    /** A user stop survives cache/inventory observations for this exact page request. */
    fun cancelTask(taskId: String): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        val (index, previous) = pending.entries.firstOrNull { it.value.id == taskId } ?: return@synchronized false
        val attempt = lastAttempts[index]?.takeIf { it.id == taskId } ?: return@synchronized false
        if (!attemptMatchesLocked(index, attempt) || previous.original.identity != attempt.originalIdentity ||
            previous.generation != generation) return@synchronized false
        val task = center.scheduler.state.value.firstOrNull { it.id == taskId && it.sessionKey == sessionKey }
        if (task == null || task.phase !in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING,
                EnhancementTaskPhase.RUNNING, EnhancementTaskPhase.PAUSED)) return@synchronized false
        manuallyStopped[index] = attempt
        center.scheduler.cancel(taskId)
        if (center.scheduler.state.value.firstOrNull { it.id == taskId }?.phase != EnhancementTaskPhase.CANCELLED) {
            // Completion may win the race; keep its original waiter and report that cancellation expired.
            manuallyStopped.remove(index)
            publishLocked()
            return@synchronized false
        }
        cancelPendingLocked(index, "用户取消")
        details[index] = "本页增强已取消，可在任务中重试"
        publishLocked()
        true
    }

    private fun attemptMatchesLocked(index: Int, attempt: Attempt): Boolean =
        index in wanted && attempt.generation == generation &&
            attempt.originalIdentity == originals[index]?.identity

    /** A retry creates a new reader-owned waiter, not an expired scheduler work closure. */
    fun retryTask(taskId: String): Boolean {
        val job = synchronized(lock) {
            if (closed) return false
            val (index, attempt) = lastAttempts.entries.firstOrNull { it.value.id == taskId } ?: return false
            if (!attemptMatchesLocked(index, attempt) || index in assets) return false
            val task = center.scheduler.state.value.firstOrNull { it.id == taskId && it.sessionKey == sessionKey }
            val stopped = task?.phase == EnhancementTaskPhase.CANCELLED && manuallyStopped[index]?.id == taskId
            if (task?.phase != EnhancementTaskPhase.FAILED && !stopped) return false
            val previous = pending[index]
            if (previous != null && previous.id != taskId) return false
            // Failure publication can reach the UI just before the previous waiter's finally block.
            cancelPendingLocked(index, "重新尝试当前页")
            failed.remove(index); cacheMisses.remove(index); details.remove(index); manuallyStopped.remove(index)
            createPendingLocked(index) ?: return false
        }
        job.start()
        return true
    }

    private fun queueIfUseful(index: Int) {
        val job = synchronized(lock) { createPendingLocked(index) } ?: return
        job.start()
    }

    private fun createPendingLocked(index: Int): Job? {
        if (closed || index !in wanted || index in pending || index in assets) return null
        val original = originals[index] ?: return null
        if (manuallyStopped[index]?.let { attemptMatchesLocked(index, it) } == true) return null
        if (failed[index]?.originalIdentity == original.identity || cacheMisses[index] == original.identity ||
            waitingOriginals[index] == original.identity) return null
        val cacheBytes = policy.cacheMiB.toLong() * 1024 * 1024
        if (index !in visible && (budgetBlocked || (pageOutputBytes[index] ?: 0L) > cacheBytes)) {
            budgetDeferred += index; details[index] = cacheWaitDetail
            publishLocked()
            return null
        }
        val selected = policy
        val epoch = generation
        val id = "$sessionKey:$index:$epoch:${original.identity}:${++nextAttempt}"
        val job = scope.launch(start = CoroutineStart.LAZY) { prepare(index, id, epoch, selected, original) }
        pending[index] = Pending(id, original, epoch, selected, job)
        lastAttempts[index] = Attempt(id, original.identity, epoch)
        details.remove(index)
        publishLocked()
        return job
    }

    private fun valid(index: Int, id: String, epoch: Long, original: Original): Boolean = validity.let {
        !it.closed && index in it.wanted && it.generation == epoch &&
            it.originalIds[index] == original.identity && it.pendingIds[index] == id
    }

    private suspend fun prepare(index: Int, id: String, epoch: Long, selected: EnhancementPolicy, original: Original) {
        val owner = currentCoroutineContext()[Job]
        var completedLease: EnhancedAssetLease? = null
        var cacheHit = false
        try {
            val inventoryFailure = try {
                center.awaitImageInventory()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
            suspend fun process(task: EnhancementJobContext): EngineOutput {
                task.checkpoint()
                if (inventoryFailure != null && center.inventory.value.engines.isEmpty()) throw inventoryFailure
                val cacheWait = synchronized(lock) {
                    (index !in visible && budgetBlocked).also { wait -> if (wait && valid(index, id, epoch, original)) {
                        budgetDeferred += index; details[index] = cacheWaitDetail; publishLocked()
                    } }
                }
                if (cacheWait) {
                    center.scheduler.cancel(id, cacheWaitDetail)
                    throw CancellationException(cacheWaitDetail)
                }
                task.progress(EngineProgress(EngineStage.VERIFY, detail = "检查原图与增强缓存"))
                val source = withContext(Dispatchers.IO) { openOriginal(original) } ?: run {
                    synchronized(lock) { if (valid(index, id, epoch, original)) {
                        // A newer ready callback can arrive while the cache-only open is in flight.
                        // Let finally queue that receipt; otherwise wait for the existing reader.
                        if (originals[index] === original) waitingOriginals[index] = original.identity
                        details[index] = "等待原图缓存，继续显示原画"
                        publishLocked()
                    } }
                    center.scheduler.cancel(id, "等待原图缓存")
                    throw CancellationException("等待原图缓存")
                }
                var work: EnhancedAssetWork? = null
                return try {
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
                    val cached = cache.acquireForReader(identity)
                    synchronized(lock) { cacheLookups++; if (cached != null) cacheHits++; publishLocked() }
                    center.telemetry.recordCacheLookup(sessionKey, content, cached != null)
                    if (cached != null) {
                        completedLease?.close(); completedLease = cached; cacheHit = true
                        task.checkpoint()
                        return EngineOutput(cached.file.path, cached.width, cached.height, cached.outputDigest,
                            withContext(Dispatchers.IO) { cached.file.length() }, selection.engine,
                            parameters.engineVersion,
                            identity.modelDigest, elapsedMillis = 0L)
                    }
                    if (!selected.allowsReaderProcessing()) {
                        synchronized(lock) { if (valid(index, id, epoch, original)) {
                            cacheMisses[index] = original.identity
                            details[index] = "尚无已完成的增强结果；原图可直接阅读"; publishLocked()
                        } }
                        // End a cache lookup normally from the queue's perspective: there is
                        // no EngineOutput to fabricate, and this is not a recoverable GPU wait.
                        center.scheduler.cancel(id, "当前页没有已完成的增强结果")
                        throw CacheOnlyMiss()
                    }
                    val currentWork = cache.prepare(identity).also { work = it }
                    // A Coil snapshot pins these exact bytes until close. Local/import paths
                    // can be replaced and need a private, content-checked copy for inference.
                    val input = if (source.pinned) sourceFile else {
                        val copy = StorageFile(currentWork.directory,
                            "original.${sourceFile.extension.takeIf { it.isNotBlank() } ?: "image"}")
                        copyReaderEnhancementInput(sourceFile, copy, originalDigest)
                        copy
                    }
                    task.checkpoint()
                    val workingBytes = readerEnhancementWorkingBytes(center)
                    task.checkpoint()
                    val output = center.engine.processImage(EngineImageRequest(input.path, currentWork.output.path,
                        engineId = selection.engine, content = imageContent, scale = selected.scale, model = selection.model,
                        tileSize = parameters.tileSize, overlap = parameters.overlap,
                        maximumOutputBytes = center.resources.state.value.runtime.maxImageBytes,
                        maximumWorkingBytes = workingBytes, expectedSourceSha256 = originalDigest), task.progress)
                    task.checkpoint()
                    check(output.engineId == parameters.engineId &&
                        output.modelSha256 == identity.modelDigest && StorageFile(output.path) == currentWork.output) {
                        "增强模型或输出身份不匹配"
                    }
                    check(output.width.toLong() == info.width.toLong() * selected.scale &&
                        output.height.toLong() == info.height.toLong() * selected.scale) { "增强输出倍率与请求不符" }
                    completedLease?.close()
                    completedLease = cache.publish(currentWork, output.width, output.height, cacheBudgetBytes()) {
                        valid(index, id, epoch, original)
                    }
                    work = null
                    output
                } finally {
                    source.close()
                    withContext(NonCancellable) { work?.let { cache.abandon(it) } }
                }
            }
            val ticket = synchronized(lock) {
                if (!valid(index, id, epoch, original)) return
                val position = positionLocked(index)
                center.scheduler.submit(id, content, position.priority, sessionKey, epoch,
                    estimatedBytes = 16L * 1024 * 1024, isCurrent = { valid(index, id, epoch, original) },
                    title = "第 ${index + 1} 页", queueOrder = position.order, work = ::process)
            }
            val result = ticket.await()
            currentCoroutineContext().ensureActive()
            val lease = completedLease ?: error("增强结果尚未发布")
            if (!valid(index, id, epoch, original)) return
            synchronized(lock) {
                if (!valid(index, id, epoch, original)) return
                val bytes = result.bytes.coerceAtLeast(0L)
                pageOutputBytes[index] = bytes
                held.remove(index)?.close(); held[index] = lease; heldBytes[index] = bytes
                completedLease = null
                assets[index] = ReaderEnhancedPage(lease.identity, lease.file.toURI(), lease.width, lease.height,
                    original.diskKey, result.elapsedMillis, cacheHit, id)
                if (!cacheHit) {
                    durations.addLast(result.elapsedMillis)
                    if (durations.size > 128) durations.removeFirst()
                    val ordered = durations.sorted()
                    fun percentile(fraction: Double) =
                        ordered[(kotlin.math.ceil(ordered.size * fraction).toInt() - 1).coerceIn(ordered.indices)].toDouble()
                    elapsedP50Millis = percentile(.5)
                    elapsedP95Millis = percentile(.95)
                }
                details.remove(index)
                retainWithinBudgetLocked()
                publishLocked()
            }
            center.telemetry.recordImage(sessionKey, content, result, cacheHit)
            // A full live window must not create an acquire/evict/infer loop. The storage
            // owner may reclaim unleased files independently; this handoff never deletes one.
            if (synchronized(lock) { !budgetBlocked }) cache.trimToBytes(cacheBudgetBytes(), content.directory)
        } catch (cancelled: CancellationException) {
            if (synchronized(lock) { cacheMisses[index] != original.identity }) throw cancelled
        }
        catch (failure: Exception) {
            synchronized(lock) { if (valid(index, id, epoch, original)) {
                failed[index] = PageFailure(original.identity, retryWhenVisible = failure !is IncompatibleReaderModel)
                details[index] = failure.message ?: "增强失败，原画仍可阅读"; publishLocked()
            } }
        } finally {
            completedLease?.close()
            val retryOriginal = synchronized(lock) {
                val refreshed = valid(index, id, epoch, original) && originals[index] !== original &&
                    waitingOriginals[index] != original.identity
                // A cancelled attempt may finish after a new attempt for the same original started.
                if (pending[index]?.let { it.id == id && it.job === owner } == true) pending.remove(index)
                publishLocked()
                refreshed
            }
            if (retryOriginal) queueIfUseful(index)
        }
    }

    private class OriginalFile(val path: String, private var snapshot: DiskCache.Snapshot?) : AutoCloseable {
        val pinned: Boolean get() = snapshot != null
        override fun close() { snapshot?.close(); snapshot = null }
    }
    private fun openOriginal(original: Original): OriginalFile? {
        val local = readerFilePath(original.page.url)
        if (local != null) return local.takeIf { StorageFile(it).isFile }?.let { OriginalFile(it, null) }
        val snapshot = loader.diskCache?.openSnapshot(original.diskKey) ?: return null
        return OriginalFile(snapshot.data.toString(), snapshot)
    }

    private fun publishLocked() {
        validity = Validity(closed, generation, wanted, originals.mapValues { it.value.identity }, pending.mapValues { it.value.id })
        val currentEnhanced = visible.isNotEmpty() && visible.all { it in assets }
        val nextReady = nextVisible.isNotEmpty() && nextVisible.all { it in assets }
        val tasks = center.scheduler.state.value.filter { it.sessionKey == sessionKey }
        val focus = visible.sortedWith(compareBy<Int> { if (it == current) 0 else 1 }.thenBy { abs(it - current) })
            .firstOrNull { it !in assets } ?: current
        val pendingTask = pending[focus]?.id?.let { id -> tasks.firstOrNull { it.id == id } }
        val failedOriginal = failed[focus]?.originalIdentity
        val failure = lastAttempts[focus]?.takeIf { it.generation == generation &&
            it.originalIdentity == originals[focus]?.identity && failedOriginal == it.originalIdentity
        }?.let { attempt -> tasks.firstOrNull { it.id == attempt.id && it.phase == EnhancementTaskPhase.FAILED } }
        val stopped = manuallyStopped.filter { (index, attempt) -> attemptMatchesLocked(index, attempt) }
        val stoppedTask = stopped[focus]?.let { attempt ->
            tasks.firstOrNull { it.id == attempt.id && it.phase == EnhancementTaskPhase.CANCELLED }
        }
        val currentTask = pendingTask ?: failure ?: stoppedTask
        val detail = details[focus]?.let { if (visible.size > 1) "第 ${focus + 1} 页：$it" else it }
            ?: if (focus in wanted && focus !in originals) "等待第 ${focus + 1} 页原图，继续显示原画" else null
        mutableState.value = ReaderEnhancementState(current, currentEnhanced, nextReady,
            tasks.count { it.phase in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING, EnhancementTaskPhase.PAUSED) },
            tasks.count { it.phase == EnhancementTaskPhase.RUNNING }, detail,
            elapsedP50Millis, elapsedP95Millis, cacheHits, cacheLookups, assets.toMap(), currentTask,
            stopped.values.map { it.id }.toSet(), visible.toSet(), ahead.toSet(), nextVisible.toSet(),
            cacheWaitDetail.takeIf { budgetBlocked && ahead.any { it !in assets } },
            detail.takeIf { failedOriginal != null && failedOriginal == originals[focus]?.identity })
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
            center.scheduler.cancelSession(sessionKey, "阅读已结束")
            held.values.forEach(EnhancedAssetLease::close); held.clear(); heldBytes.clear(); assets.clear(); originals.clear()
            budgetDeferred.clear(); pageOutputBytes.clear()
            pending.values.forEach { it.job.cancel() }; pending.clear()
            lastAttempts.clear(); failed.clear(); cacheMisses.clear(); waitingOriginals.clear()
            originalRecoveryAttempts.clear()
            details.clear(); manuallyStopped.clear()
            scope.cancel()
            center.settings.clearSession(sessionKey)
            center.telemetry.endSession(sessionKey)
        }
    }
}

private fun EnhancementPolicy.allowsReaderProcessing(): Boolean =
    PreparationMode.IMMEDIATE in preparation || PreparationMode.SMART in preparation

internal val LocalReaderEnhancement = staticCompositionLocalOf<ReaderEnhancementController?> { null }

internal data class ReaderEnhancementModel(val engine: String, val model: EngineModel?)

internal data class ReaderEnhancementParameters(
    val engineId: String,
    val engineVersion: String,
    val tileSize: Int,
    val overlap: Int,
    val cacheIdentity: String,
)

/** Resolve only for new inference, after cache lookup; a limit is not a memory-use receipt. */
internal suspend fun readerEnhancementWorkingBytes(center: EnhancementCenter): Long {
    val resources = center.resources
    if (resources.state.value.runtime.materializationMiB == null &&
        resources.usage.value.resources.sampledAtMillis == null) {
        resources.refreshResources()
        check(withTimeoutOrNull(3_000L) {
            resources.usage.first { it.resources.sampledAtMillis != null }
        } != null) { "尚未取得本机内存信息，原图可继续阅读；稍后重试或在体验策略设置图片处理内存" }
    }
    return resources.state.value.materializationBytes.coerceAtLeast(0L)
}

/** One normalized request and identity for both live pages and completed-download batches. */
internal fun readerEnhancementParameters(center: EnhancementCenter, engineId: String,
    content: EngineContent): ReaderEnhancementParameters {
    val engine = center.inventory.value.engines.firstOrNull { it.id == engineId }
        ?: error("无法确认当前增强引擎版本，请刷新模型详情")
    check(engine.version.isNotBlank()) { "增强引擎版本尚未确认" }
    val tile = when (engine.id) {
        EnhancementEngineIds.ANDROID_CUGAN -> EnhancementEngineIds.ANDROID_CUGAN_TILE_SIZE
        EnhancementEngineIds.MANGAJANAI -> 0 // The CLI sizes tiles for the GPU's memory.
        else -> 256
    }
    val overlap = when (engine.id) {
        EnhancementEngineIds.ANDROID_CUGAN -> EnhancementEngineIds.ANDROID_CUGAN_PADDING
        EnhancementEngineIds.MANGAJANAI -> 128
        else -> 32
    }
    val identity = listOf("algorithm" to "reader-image-v2-adaptive-tiles", "engine" to engine.id,
        "engineVersion" to engine.version, "content" to content.name,
        "tile" to tile.toString(), "overlap" to overlap.toString()).joinToString("") { (key, value) ->
        "${key.length}:$key${value.length}:$value"
    }
    return ReaderEnhancementParameters(engine.id, engine.version, tile, overlap, identity)
}

internal fun selectReaderEnhancementModel(center: EnhancementCenter, selected: EnhancementPolicy,
    image: EngineContent): ReaderEnhancementModel = selectReaderEnhancementModel(center.inventory.value, selected, image)

internal class IncompatibleReaderModel(message: String, val pageSpecific: Boolean = false) :
    IllegalArgumentException(message)

internal fun selectReaderEnhancementModel(inventory: EngineInventory, selected: EnhancementPolicy,
    image: EngineContent): ReaderEnhancementModel {
    val explicit = selected.modelId?.let { id -> inventory.models.firstOrNull { it.id == id }
        ?: error("所选模型尚未安装，请打开画质与性能安装模型") }
    val automaticModel = selected.tier in setOf(QualityTier.FINE, QualityTier.ULTRA) ||
        selected.tier in setOf(QualityTier.AUTO, QualityTier.BALANCED) && selected.scale > 1
    val compatible = if (explicit == null && automaticModel)
        inventory.models.filter { it.nativeScale == selected.scale && image in it.contents } else emptyList()
    val ready = compatible.filter { candidate ->
        inventory.engines.any { it.id == candidate.engineId && image in it.contents &&
            it.readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL) &&
            selected.scale in it.supportedScales }
    }
    val preferVulkan = selected.tier == QualityTier.AUTO && selected.scale == 2 &&
        (image == EngineContent.COLOR || image == EngineContent.WEBTOON)
    val model = explicit ?: (if (preferVulkan) ready.firstOrNull { it.engineId == EnhancementEngineIds.ANDROID_CUGAN } else null)
        ?: ready.firstOrNull()
    if (model != null) {
        if (image !in model.contents) {
            val content = when (image) {
                EngineContent.BLACK_WHITE -> "黑白漫画"
                EngineContent.COLOR -> "彩色漫画"
                EngineContent.WEBTOON -> "条漫"
                EngineContent.VIDEO -> "视频"
            }
            throw IncompatibleReaderModel("${model.title} 不支持${content}，这页继续显示原图", pageSpecific = true)
        }
        if (model.nativeScale != selected.scale) {
            throw IncompatibleReaderModel("${model.title} 支持 ${model.nativeScale}×，当前设为 ${selected.scale}×")
        }
        val engine = inventory.engines.firstOrNull { it.id == model.engineId } ?: error("增强引擎尚未安装")
        check(engine.readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL) &&
            selected.scale in engine.supportedScales) { engine.detail }
        return ReaderEnhancementModel(model.engineId, model)
    }
    if (automaticModel) {
        if (compatible.isNotEmpty()) {
            val engine = inventory.engines.firstOrNull { it.id == compatible.first().engineId }
            val reason = when {
                engine == null -> "请先准备对应的增强引擎"
                selected.scale !in engine.supportedScales -> "引擎不支持 ${selected.scale}× 倍率"
                image !in engine.contents -> "引擎不支持当前图片类型"
                else -> engine.detail
            }
            error("兼容模型已安装，但当前增强引擎不可用：$reason")
        }
        error("尚无支持当前图片类型与 ${selected.scale}× 倍率的可用模型，请打开画质与性能准备兼容模型")
    }
    check(selected.tier in setOf(QualityTier.LIGHT, QualityTier.AUTO, QualityTier.BALANCED)) {
        "请先选择并安装兼容的${selected.tier.label}模型"
    }
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
