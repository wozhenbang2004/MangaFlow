package dev.mangaflow.data

import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import dev.mangaflow.data.plugins.prefetchScriptPage
import dev.mangaflow.data.plugins.scriptSourceImageEpoch
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.PrefetchBudgetExceeded
import dev.mangaflow.experience.PrefetchByteBudget
import dev.mangaflow.experience.PrefetchWork
import dev.mangaflow.experience.PrefetchStage
import dev.mangaflow.experience.AdmissionWait
import dev.mangaflow.experience.AdmissionWaitReason
import dev.mangaflow.experience.ReaderPagePreflightStatus
import dev.mangaflow.experience.prefetchOriginalPageResult
import dev.mangaflow.experience.preflightReaderPage
import dev.mangaflow.experience.readerPageCacheKey
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.reader.readerFilePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One optional owner; the repository and image cache remain the sole manifest/byte caches. */
internal class ReaderSourceStandbyController(
    private val context: PlatformContext,
    private val repository: UnifiedMangaRepository,
    private val scope: CoroutineScope,
) {
    private data class Limits(val sources: Int, val bytes: Long, val delayMillis: Long,
        val foreground: Boolean, val decodedPages: Int, val pages: Int, val warmMemory: Boolean)
    private data class Position(val index: Int = 0, val count: Int = 0, val direction: Int = 1)
    private data class Window(val target: Int, val indices: List<Int>)
    private data class Stamp(val bytes: Long, val modifiedAt: Long?)
    private data class Prepared(val manifest: Page, val original: Page, val cacheKey: String?,
        val stamp: Stamp, val decoded: Boolean, val previewKey: MemoryCache.Key? = null,
        val inspectedAt: Long = wallTimeMillis())
    private data class Stopped(val reason: String, val failed: Boolean, val retryOnRelease: Boolean = false,
        val at: Long = monotonicTimeMillis())
    private val mutableState = MutableStateFlow<List<ReaderSourceStandby>>(emptyList())
    val state = mutableState.asStateFlow()
    private val position = MutableStateFlow(Position())
    private var job: Job? = null
    private var key: String? = null
    private var request = 0L

    fun clear() {
        request++
        key = null
        job?.cancel()
        job = null
        mutableState.value = emptyList()
    }

    fun warm(
        sessionKey: String, workId: String, chapter: Chapter, candidates: List<ChapterVariant>,
        pageIndex: Int, pageCount: Int, current: (ChapterVariant) -> Boolean,
    ) {
        val index = pageIndex.coerceAtLeast(0)
        val previous = position.value
        position.value = Position(index, pageCount.coerceAtLeast(0), when {
            key != sessionKey -> 1
            index < previous.index -> -1
            index > previous.index -> 1
            else -> previous.direction
        })
        if (key == sessionKey && job?.isActive == true) return
        clear()
        if (candidates.isEmpty()) return
        key = sessionKey
        val generation = request
        val policy = ExperiencePolicyStore.get(context)
        job = scope.launch {
            // One shared allowance for the live standby windows. Only settled off-window
            // page tickets release capacity; duplicate warm callbacks never reset it.
            var maximum = 0L
            var bytes = PrefetchByteBudget(0L)
            val retained = mutableMapOf<String, LinkedHashMap<Int, Prepared>>()
            policy.state.map { Limits(it.standbySources, it.standbyPrefetchBytes,
                it.sourceHedgeDelayMillis, it.foreground, it.decodedPrefetchPages,
                it.standbyPages, it.standbyMemoryWarmup) }
                .distinctUntilChanged().collectLatest { limits ->
                if (generation != request) return@collectLatest
                val eligible = candidates.filter(current)
                val selected = select(eligible, policy, limits.sources.coerceAtLeast(0))
                val allowed = limits.foreground && limits.sources > 0 && limits.bytes > 0L
                mutableState.value = eligible.map { variant -> standby(workId, chapter, variant).let { row ->
                    if (allowed && variant in selected) row else row.copy(phase = ReaderSourceStandbyPhase.DEFERRED,
                        reason = when {
                            !limits.foreground -> "阅读不在前台，暂停可选备用准备"
                            limits.sources <= 0 -> "备用来源数量已设为零"
                            limits.bytes <= 0L -> "备用来源容量已设为零"
                            else -> "超出本轮备用来源数量，优先准备其他来源"
                        })
                } }
                if (!allowed) return@collectLatest
                if (maximum != limits.bytes) {
                    maximum = limits.bytes
                    bytes = PrefetchByteBudget(maximum)
                }
                // One common debounce, followed by simultaneous source starts. Shared admission
                // still reserves the foreground lane and enforces actual source/request ceilings.
                delay(limits.delayMillis)
                if (generation != request) return@collectLatest
                coroutineScope {
                    selected.forEachIndexed { order, variant -> launch {
                        val saved = retained.getOrPut(variant.storageId) { linkedMapOf() }
                        prepareSource(generation, variant, order, limits,
                            bytes, saved, current)
                    } }
                }
            }
        }
    }

    private fun select(candidates: List<ChapterVariant>, policy: ExperiencePolicyStore, count: Int): List<ChapterVariant> {
        val health = repository.sourceHealth.value
        val now = wallTimeMillis()
        val stages = setOf(SourceStage.PAGES, SourceStage.IMAGE_RESOLVE,
            SourceStage.IMAGE_READ, SourceStage.IMAGE_DECODE)
        return candidates.sortedWith(compareBy<ChapterVariant> {
            policy.sourceCooldownRemainingMillis(it.sourceId) > 0L
        }.thenBy { candidate ->
            val observed = health[candidate.sourceId]
            when {
                observed == null || observed.stage !in stages -> 1
                observed.failure != null && now - observed.lastAttemptAt in 0L..60_000L -> 2
                observed.failure == null && observed.lastSuccessAt > 0L -> 0
                else -> 1
            }
        }.thenBy { candidate ->
            if (repository.cachedPages(SourceKeys.manga(candidate.sourceId, candidate.mangaId),
                    candidate.storageId) != null) 0 else 1
        }).take(count)
    }

    private suspend fun prepareSource(generation: Long, variant: ChapterVariant, order: Int, limits: Limits, bytes: PrefetchByteBudget,
        prepared: LinkedHashMap<Int, Prepared>, current: (ChapterVariant) -> Boolean,
    ) {
        val policy = ExperiencePolicyStore.get(context)
        val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
        val sourceEpoch = scriptSourceImageEpoch(context, variant.sourceId)
        fun active() = generation == request && current(variant) &&
            scriptSourceImageEpoch(context, variant.sourceId) == sourceEpoch
        fun update(change: (ReaderSourceStandby) -> ReaderSourceStandby) {
            if (!active()) return
            mutableState.update { rows -> if (!active()) rows else rows.map {
                if (it.variantId == variant.storageId) change(it) else it
            } }
        }
        var sourceRequestTimedOut = false
        try {
            if (!active()) return
            val pages = withTimeout(25_000L) {
                repository.cachedPages(owner, variant.storageId) ?: policy.withPrefetchAdmission(
                    variant.sourceId, 1_048_576L, { false },
                    onWaiting = { wait -> update { it.copy(
                        phase = if (wait == null) ReaderSourceStandbyPhase.RESOLVING else ReaderSourceStandbyPhase.QUEUED,
                        reason = wait?.let(::waitingReason)) } },
                ) {
                    update { it.copy(phase = ReaderSourceStandbyPhase.RESOLVING, reason = null) }
                    try { withTimeout(15_000L) { repository.pages(owner, variant.storageId) } }
                    catch (timeout: TimeoutCancellationException) {
                        sourceRequestTimedOut = true
                        throw timeout
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!active()) return
            if (pages.isEmpty()) {
                update { it.copy(phase = ReaderSourceStandbyPhase.FAILED, reason = "来源没有返回本章图片") }
                return
            }
            // A refreshed manifest cannot inherit readiness from an older image at the same index.
            prepared.entries.removeAll { (index, saved) -> pages.getOrNull(index) != saved.manifest }
            val headers = repository.imageHeaders(owner, variant.storageId).toMap()
            coroutineScope {
                val pending = mutableMapOf<Int, Job>()
                val stopped = mutableMapOf<Int, Stopped>()
                val reservations = mutableMapOf<Int, PrefetchByteBudget>()
                val workTokens = mutableMapOf<Int, PrefetchWork>()
                val pageStages = mutableMapOf<Int, ReaderSourceStandbyPhase>()
                val capacityRevision = MutableStateFlow(0L)
                val windowLock = Mutex()
                var window: Window? = null
                var windowId = 0L
                fun resident(saved: Prepared?): Boolean = saved?.previewKey?.let { exact ->
                    // Inspection must not touch the cache access clock and keep idle previews alive.
                    SingletonImageLoader.get(imagePlatformContext(context)).memoryCache?.keys?.contains(exact)
                } == true
                fun publish() {
                    val visible = window ?: return
                    val views = visible.indices.map { index ->
                        val saved = prepared[index]
                        val problem = stopped[index]
                        val task = pending[index]
                        val work = workTokens[index]?.snapshot()
                        val phase = when {
                            task != null && !task.isCancelled -> when {
                                work?.admitted == true -> when (work.stage) {
                                    PrefetchStage.QUEUED -> ReaderSourceStandbyPhase.QUEUED
                                    PrefetchStage.RESOLVING -> ReaderSourceStandbyPhase.RESOLVING
                                    PrefetchStage.DOWNLOADING -> ReaderSourceStandbyPhase.DOWNLOADING
                                    PrefetchStage.CHECKING -> ReaderSourceStandbyPhase.CHECKING
                                }
                                work?.wait != null -> ReaderSourceStandbyPhase.QUEUED
                                else -> pageStages[index] ?: ReaderSourceStandbyPhase.QUEUED
                            }
                            saved != null -> ReaderSourceStandbyPhase.READY
                            task != null -> ReaderSourceStandbyPhase.QUEUED
                            problem?.failed == true -> ReaderSourceStandbyPhase.FAILED
                            problem != null -> ReaderSourceStandbyPhase.DEFERRED
                            else -> ReaderSourceStandbyPhase.QUEUED
                        }
                        ReaderSourceStandbyPage(index, phase, saved != null, resident(saved),
                            saved?.stamp?.bytes ?: 0L, work?.receivedBytes ?: 0L, work?.expectedBytes,
                            saved?.inspectedAt, when {
                                task?.isCancelled == true -> "等待上一次窗口请求结算"
                                work?.wait != null && task != null -> waitingReason(work.wait)
                                problem != null -> problem.reason
                                saved != null && !limits.warmMemory -> "原图已缓存，小图预解码已关闭"
                                saved != null && !resident(saved) -> "原图已缓存，小图当前未驻留"
                                else -> null
                            })
                    }
                    val target = views.first { it.index == visible.target }
                    update { it.copy(phase = target.phase, targetPageIndex = visible.target, totalPages = pages.size,
                        requestedPages = visible.indices.size,
                        preparedPages = views.count { page -> page.originalPresent },
                        decodedPages = visible.indices.count { index -> prepared[index]?.decoded == true },
                        memoryWarmed = target.previewResident, targetPrepared = target.originalPresent,
                        activePages = visible.indices.count { index -> pending[index]?.isCancelled == false &&
                            workTokens[index]?.snapshot()?.admitted == true },
                        reason = target.reason, windowId = windowId,
                        queuedPages = views.count { page -> page.phase == ReaderSourceStandbyPhase.QUEUED },
                        resolvingPages = views.count { page -> page.phase == ReaderSourceStandbyPhase.RESOLVING },
                        downloadingPages = views.count { page -> page.phase == ReaderSourceStandbyPhase.DOWNLOADING },
                        checkingPages = views.count { page -> page.phase == ReaderSourceStandbyPhase.CHECKING },
                        failedPages = views.count { page -> page.phase == ReaderSourceStandbyPhase.FAILED },
                        deferredPages = views.count { page -> page.phase == ReaderSourceStandbyPhase.DEFERRED },
                        settlingPages = pending.count { (index, _) -> index !in visible.indices },
                        cachedOriginalBytes = views.sumOf { page -> page.cachedOriginalBytes },
                        previewResidentPages = views.count { page -> page.previewResident },
                        targetOriginalPresent = target.originalPresent, targetPreviewResident = target.previewResident,
                        receivedBytes = views.sumOf { page -> page.receivedBytes },
                        expectedBytes = views.map { page -> page.expectedBytes }.takeIf { values -> values.all { value -> value != null } }
                            ?.sumOf { value -> value ?: 0L },
                        cacheInspectedAtMillis = views.mapNotNull { page -> page.cacheInspectedAtMillis }.minOrNull(),
                        windowPages = views) }
                }
                suspend fun refreshRetained(indices: List<Int>) {
                    for (index in indices) {
                        val saved = prepared[index] ?: continue
                        val currentStamp = stamp(saved.original, saved.cacheKey, headers, variant.sourceId)
                        if (currentStamp != saved.stamp && prepared[index] === saved) {
                            prepared.remove(index)
                            if (index !in pending) reservations.remove(index)?.close()
                            stopped[index] = Stopped("原图缓存已回收或更新，等待重新准备", false)
                        } else if (prepared[index] === saved) {
                            prepared[index] = saved.copy(inspectedAt = wallTimeMillis())
                        }
                    }
                }
                suspend fun updateWindow(at: Position) = windowLock.withLock {
                    if (!active()) return@withLock
                    val target = approximateReaderPage(at.index, at.count, pages.size)
                    val wanted = buildList {
                        add(target)
                        for (offset in 1 until limits.pages) {
                            val next = target + offset * at.direction
                            if (next in pages.indices) add(next)
                        }
                        for (offset in 1 until limits.pages) {
                            val previous = target - offset * at.direction
                            if (size < limits.pages && previous in pages.indices) add(previous)
                        }
                    }
                    val nextWindow = Window(target, wanted)
                    if (window != nextWindow) windowId++
                    window = nextWindow
                    // Keep overlap alive; fast page turns only cancel work that really left the window.
                    pending.filterKeys { it !in wanted }.values.toList().forEach { it.cancel() }
                    reservations.keys.filter { it !in wanted && it !in pending }.forEach { index ->
                        // A cancelled child still owns its ticket until its transfer finally block
                        // completes; its own finally block releases the captured ticket.
                        reservations.remove(index)?.close()
                    }
                    workTokens.keys.filter { it !in wanted && it !in pending }.forEach { workTokens.remove(it); pageStages.remove(it) }
                    refreshRetained(wanted)
                    currentCoroutineContext().ensureActive()
                    if (!active()) return@withLock
                    for (index in wanted) {
                        val saved = prepared[index]
                        val warm = limits.warmMemory && order < limits.decodedPages && index == target
                        if (index in pending) continue
                        val stop = stopped[index]
                        if (saved == null && stop != null && !stop.retryOnRelease &&
                            monotonicTimeMillis() - stop.at < 30_000L) continue
                        val ticket = reservations[index] ?: bytes.child().also { child ->
                            if (saved != null) try { child.accept(saved.stamp.bytes) }
                            catch (_: PrefetchBudgetExceeded) {
                                child.close()
                                stopped[index] = Stopped("备用窗口容量不足，等待其他页退出窗口", false, retryOnRelease = true)
                            }
                        }
                        if (ticket.remaining == 0L && ticket.used == 0L) {
                            ticket.close()
                            stopped[index] = Stopped("备用窗口容量不足，等待其他页退出窗口", false, retryOnRelease = true)
                            continue
                        }
                        reservations[index] = ticket
                        if (saved != null && (!warm || resident(saved))) continue
                        val token = PrefetchWork()
                        workTokens[index] = token
                        pageStages[index] = ReaderSourceStandbyPhase.QUEUED
                        lateinit var task: Job
                        task = launch(start = CoroutineStart.LAZY) {
                            try {
                                stopped.remove(index)
                                val input = pages[index]
                                val old = prepared[index]
                                val original = if (old != null) old.original else {
                                    // Native cache-only inspection reuses existing originals before
                                    // deciding whether this window needs another actual transfer.
                                    pageStages[index] = ReaderSourceStandbyPhase.CHECKING
                                    publish()
                                    val cached = if (!variant.sourceId.startsWith("js_")) preflightReaderPage(
                                        context, input, headers, variant.sourceId, 512, 1024, warmMemory = false,
                                        work = token,
                                    ) else null
                                    if (cached?.dimensions != null) input else {
                                        if (ticket.remaining <= 0L) throw PrefetchBudgetExceeded()
                                        pageStages[index] = ReaderSourceStandbyPhase.QUEUED
                                        publish()
                                        if (variant.sourceId == "jmcomic") {
                                            stopped[index] = Stopped("此来源暂不支持后台原图准备，切源后按需加载", false)
                                            null
                                        } else if (variant.sourceId.startsWith("js_")) {
                                            withTimeout(25_000L) { prefetchScriptPage(context, owner,
                                                variant.storageId, input, ticket, token) }
                                        } else {
                                            val fetched = withTimeout(25_000L) { prefetchOriginalPageResult(
                                                context, input, headers, variant.sourceId, ticket, token) }
                                            when {
                                                fetched.received -> input
                                                fetched.failure != null -> {
                                                    stopped[index] = Stopped("来源图片请求失败，可在切源后重试", true)
                                                    null
                                                }
                                                else -> {
                                                    stopped[index] = Stopped("当前资源或来源限制，稍后准备", false)
                                                    null
                                                }
                                            }
                                        }
                                    }
                                }
                                currentCoroutineContext().ensureActive()
                                if (!active() || index !in window?.indices.orEmpty() || original == null) return@launch
                                pageStages[index] = ReaderSourceStandbyPhase.CHECKING
                                publish()
                                val checked = withTimeout(15_000L) { preflightReaderPage(context,
                                    original, headers, variant.sourceId, 512, 1024,
                                    old?.cacheKey, warmMemory = false, work = token) }
                                currentCoroutineContext().ensureActive()
                                if (!active() || index !in window?.indices.orEmpty()) return@launch
                                val local = stamp(original, checked.diskCacheKey, headers, variant.sourceId)
                                currentCoroutineContext().ensureActive()
                                if (!active() || index !in window?.indices.orEmpty()) return@launch
                                when {
                                    checked.status == ReaderPagePreflightStatus.FAILED -> {
                                        prepared.remove(index)
                                        stopped[index] = Stopped("已缓存图片未通过读取检查，可在切源后重试", true)
                                    }
                                    local != null && checked.dimensions != null -> {
                                        if (ticket.used < local.bytes) ticket.accept(local.bytes - ticket.used)
                                        else ticket.release(ticket.used - local.bytes)
                                        prepared.remove(index)
                                        prepared[index] = Prepared(input, original, checked.diskCacheKey,
                                            local, old?.decoded == true, old?.previewKey)
                                        while (prepared.size > 32) {
                                            val oldest = prepared.keys.firstOrNull { it !in window?.indices.orEmpty() } ?: break
                                            prepared.remove(oldest)
                                        }
                                        stopped.remove(index)
                                        // Original readiness does not wait for optional preview decoding.
                                        publish()
                                        if (warm && !resident(prepared[index])) {
                                            val preview = withTimeout(15_000L) { preflightReaderPage(context,
                                                original, headers, variant.sourceId, 512, 1024,
                                                checked.diskCacheKey, warmMemory = true, work = token) }
                                            currentCoroutineContext().ensureActive()
                                            if (!active() || index !in window?.indices.orEmpty()) return@launch
                                            val saved = prepared[index]
                                            if (saved != null && stamp(original, saved.cacheKey, headers, variant.sourceId) == saved.stamp) {
                                                if (preview.status == ReaderPagePreflightStatus.READY) {
                                                    prepared[index] = saved.copy(decoded = true, previewKey = preview.memoryCacheKey,
                                                        inspectedAt = wallTimeMillis())
                                                } else stopped[index] = Stopped(when (preview.status) {
                                                    ReaderPagePreflightStatus.FAILED -> "原图已缓存，小图预解码失败，切源后按需读取"
                                                    else -> "原图已缓存，当前资源限制暂缓小图预解码"
                                                }, false)
                                            } else {
                                                prepared.remove(index)
                                                stopped[index] = Stopped("原图缓存已回收或更新，等待重新准备", false)
                                            }
                                        }
                                    }
                                    else -> {
                                        prepared.remove(index)
                                        stopped[index] = Stopped("原图尚未通过缓存读取检查，暂不能确认备用就绪", false)
                                    }
                                }
                            } catch (timeout: TimeoutCancellationException) {
                                currentCoroutineContext().ensureActive()
                                stopped[index] = Stopped("备用图片准备超时，当前来源继续阅读", false)
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: PrefetchBudgetExceeded) {
                                // A body larger than this allowance must not be redownloaded on
                                // every status tick. A later real reading window can try again.
                                stopped[index] = Stopped("备用窗口容量不足，调大容量或继续翻页后再准备", false)
                            } catch (_: OutOfMemoryError) {
                                stopped[index] = Stopped("内存保护暂停了小图准备", false)
                            } catch (_: Exception) {
                                stopped[index] = Stopped("来源图片暂不可用，可在切源后重试", true)
                            } finally {
                                if (pending[index] === task) pending.remove(index)
                                val wantedNow = index in window?.indices.orEmpty()
                                if (!wantedNow || index !in prepared) {
                                    if (reservations[index] === ticket) reservations.remove(index)
                                    ticket.close()
                                    if (!wantedNow) capacityRevision.value++
                                }
                                if (!wantedNow && workTokens[index] === token) {
                                    workTokens.remove(index)
                                    pageStages.remove(index)
                                }
                                // An off-window cancellation may settle after the reader already
                                // returned. Restart that desired page only after its old owner is
                                // gone; ordinary failures retain their bounded stopped state.
                                if (wantedNow && task.isCancelled && stopped[index] == null)
                                    capacityRevision.value++
                                publish()
                            }
                        }
                        pending[index] = task
                        task.start()
                    }
                    publish()
                }
                val positions = launch {
                    combine(position, capacityRevision) { at, _ -> at }.collect { updateWindow(it) }
                }
                try {
                    var inspectedAt = monotonicTimeMillis()
                    while (currentCoroutineContext().isActive && active()) {
                        delay(if (pending.isEmpty()) 2_000L else 250L)
                        windowLock.withLock {
                            if (monotonicTimeMillis() - inspectedAt >= 2_000L) {
                                refreshRetained(window?.indices.orEmpty())
                                inspectedAt = monotonicTimeMillis()
                            }
                            publish()
                        }
                        if (bytes.remaining > 0L && stopped.values.any { it.retryOnRelease })
                            updateWindow(position.value)
                    }
                    if (generation == request) mutableState.update { rows -> rows.filterNot { it.variantId == variant.storageId } }
                } finally {
                    positions.cancel()
                    val tasks = pending.values.toList()
                    tasks.forEach { it.cancel() }
                    // Retained originals release their logical window reservation only after all
                    // active children have settled; physical bytes remain in the shared cache.
                    withContext(NonCancellable) {
                        positions.join()
                        tasks.forEach { it.join() }
                        reservations.values.toList().forEach { it.close() }
                        reservations.clear()
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            update { it.copy(phase = if (sourceRequestTimedOut) ReaderSourceStandbyPhase.FAILED
                else ReaderSourceStandbyPhase.DEFERRED, reason = "备用来源目录准备超时") }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: PrefetchBudgetExceeded) {
            update { it.copy(phase = ReaderSourceStandbyPhase.DEFERRED, reason = "当前资源预算暂停备用来源") }
        } catch (_: Exception) {
            update { it.copy(phase = ReaderSourceStandbyPhase.FAILED, reason = "备用来源目录暂不可用") }
        }
    }

    /** Read existing storage only. A cancelled/evicted/replaced original cannot retain READY. */
    private suspend fun stamp(page: Page, cacheKey: String?, headers: Map<String, String>, source: String): Stamp? =
        withContext(Dispatchers.IO) {
            try {
                val local = readerFilePath(page.url)
                if (local != null) StorageFile(local).takeIf { it.isFile && it.length() > 0L }?.let {
                    Stamp(it.length(), it.lastModified())
                } else {
                    val cache = SingletonImageLoader.get(imagePlatformContext(context)).diskCache
                    cache?.let { owner ->
                        owner.openSnapshot(cacheKey ?: readerPageCacheKey(page, headers, source))?.use {
                            owner.fileSystem.metadata(it.data).let { metadata ->
                                metadata.size?.takeIf { size -> size > 0L }?.let { size -> Stamp(size, metadata.lastModifiedAtMillis) }
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }

    private fun standby(workId: String, chapter: Chapter, variant: ChapterVariant) =
        ReaderSourceStandby(workId, chapter.id, variant.storageId, variant.sourceId,
            variant.sourceName, ReaderSourceStandbyPhase.QUEUED)

    private fun waitingReason(wait: AdmissionWait): String = when (wait.reason) {
        AdmissionWaitReason.NO_NETWORK -> "等待网络恢复"
        AdmissionWaitReason.STORAGE_LOW -> "可用空间不足，等待释放容量"
        AdmissionWaitReason.THERMAL -> "设备温度保护，暂缓备用准备"
        AdmissionWaitReason.MEMORY_PRESSURE -> "内存保护，暂缓小图准备"
        AdmissionWaitReason.BACKGROUND_RESTRICTED -> "等待阅读回到前台"
        AdmissionWaitReason.READING_PRIORITY -> "当前阅读优先，备用请求排队"
        AdmissionWaitReason.SOURCE_BACKOFF -> "来源暂退避，等待重试窗口"
        AdmissionWaitReason.SOURCE_LIMIT -> "等待此来源的并发名额"
        AdmissionWaitReason.GLOBAL_LIMIT -> "等待共享预读名额或容量"
        AdmissionWaitReason.CHAPTER_LIMIT -> "等待本章并发名额"
        AdmissionWaitReason.FAIR_QUEUE -> "按公平队列等待其他页面"
    }
}
