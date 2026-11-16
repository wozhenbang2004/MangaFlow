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
import dev.mangaflow.experience.ExperienceAdmissionDeferred
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
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

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
        val retryWhenWritable: Boolean = false,
        val at: Long = monotonicTimeMillis())
    private data class SourceAttempt(val variant: ChapterVariant, val earliest: Long = 0L,
        val deadline: Long = 0L, val revision: Long = 0L, val onePass: Boolean = false,
        val restore: Boolean = false)
    private val mutableState = MutableStateFlow<List<ReaderSourceStandby>>(emptyList())
    val state = mutableState.asStateFlow()
    private val mutableAvailability = MutableStateFlow(ReaderSourceStandbyAvailability())
    val availability = mutableAvailability.asStateFlow()
    private val position = MutableStateFlow(Position())
    private val retryRevision = MutableStateFlow(0L)
    private var job: Job? = null
    private var key: String? = null
    private var request = 0L
    private var retryOne: ((String) -> Boolean)? = null

    fun clear() {
        request++
        retryOne = null
        key = null
        job?.cancel()
        job = null
        mutableState.value = emptyList()
        mutableAvailability.value = ReaderSourceStandbyAvailability()
    }

    /** A failed look-ahead may wake recovery, but cannot replace the live reader's cursor. */
    fun nudge(workId: String, chapterId: String) {
        val current = availability.value
        if (key != null && current.workId == workId && current.chapterId == chapterId) retryRevision.value++
    }

    fun retry(workId: String, chapterId: String, variantId: String): Boolean {
        val identity = availability.value
        val budget = ExperiencePolicyStore.get(context).state.value
        val row = state.value.firstOrNull { it.variantId == variantId } ?: return false
        if (identity.workId != workId || identity.chapterId != chapterId ||
            !budget.foreground || budget.standbySources <= 0 || budget.standbyPrefetchBytes <= 0L ||
            row.workId != workId || row.chapterId != chapterId ||
            row.phase != ReaderSourceStandbyPhase.FAILED && row.failedPages == 0) return false
        return retryOne?.invoke(variantId) == true
    }

    fun warm(
        sessionKey: String, workId: String, chapter: Chapter, candidates: List<ChapterVariant>,
        pageIndex: Int, pageCount: Int, availability: ReaderSourceStandbyAvailability,
        current: (ChapterVariant) -> Boolean,
    ) {
        val index = pageIndex.coerceAtLeast(0)
        val previous = position.value
        position.value = Position(index, pageCount.coerceAtLeast(0), when {
            key != sessionKey -> 1
            index < previous.index -> -1
            index > previous.index -> 1
            else -> previous.direction
        })
        if (key == sessionKey && job?.isActive == true) {
            mutableAvailability.value = availability
            return
        }
        clear()
        mutableAvailability.value = availability
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
                val ordered = select(eligible, policy, eligible.size)
                val allowed = limits.foreground && limits.sources > 0 && limits.bytes > 0L
                mutableState.value = eligible.map { variant -> standby(workId, chapter, variant).let { row ->
                    if (allowed) row.copy(reason = "等待备用来源准备名额") else row.copy(phase = ReaderSourceStandbyPhase.DEFERRED,
                        manifestQueued = false,
                        reason = when {
                            !limits.foreground -> "阅读不在前台，暂停可选备用准备"
                            limits.sources <= 0 -> "备用来源数量已设为零"
                            limits.bytes <= 0L -> "备用来源容量已设为零"
                            else -> "备用来源准备暂缓"
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
                    val queue = ordered.map { SourceAttempt(it) }.toMutableList()
                    val running = mutableMapOf<String, Job>()
                    val activeAttempts = mutableMapOf<String, SourceAttempt>()
                    val attempts = mutableMapOf<String, Int>()
                    val restart = mutableSetOf<String>()
                    val yielding = mutableSetOf<String>()
                    val paused = mutableSetOf<String>()
                    val wake = MutableStateFlow(0L)
                    val workers = minOf(limits.sources, ordered.size)
                    fun enqueue(entry: SourceAttempt, first: Boolean = false) {
                        queue.removeAll { it.variant.storageId == entry.variant.storageId }
                        if (first) queue.add(0, entry) else queue.add(entry)
                        wake.value++
                    }
                    fun makeRoom() {
                        if (running.size < workers || yielding.isNotEmpty()) return
                        val desired = queue.firstOrNull { it.onePass && current(it.variant) &&
                            policy.sourceCooldownRemainingMillis(it.variant.sourceId) <= 0L } ?: return
                        val victim = running.keys.lastOrNull { it != desired.variant.storageId &&
                            activeAttempts[it]?.onePass != true && running[it]?.isCancelled == false } ?: return
                        yielding += victim
                        running[victim]?.cancel()
                    }
                    val retry: (String) -> Boolean = retry@{ id ->
                        val variant = ordered.firstOrNull { it.storageId == id } ?: return@retry false
                        if (generation != request || !current(variant)) return@retry false
                        attempts[id] = 0
                        if (running[id] != null) { restart += id; running[id]?.cancel() }
                        else enqueue(SourceAttempt(variant, onePass = true), first = true)
                        mutableState.update { rows -> if (generation != request) rows else rows.map { row ->
                            if (row.variantId == id) row.copy(phase = ReaderSourceStandbyPhase.QUEUED,
                                failedPages = 0, manifestQueued = true, reason = "已请求重试，等待旧请求结算与准备名额") else row
                        } }
                        makeRoom()
                        wake.value++
                        true
                    }
                    retryOne = retry
                    val observations = launch {
                        while (isActive && generation == request) {
                            delay(2_000L)
                            paused.toList().forEach { id ->
                                val variant = ordered.firstOrNull { it.storageId == id } ?: return@forEach
                                if (id !in running && current(variant)) try {
                                    refreshPaused(generation, variant, retained.getOrPut(id) { linkedMapOf() },
                                        limits, current) { id !in running && id in paused }
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (_: Exception) {
                                    mutableState.update { rows -> if (generation != request || id in running || !current(variant)) rows else rows.map { row ->
                                        if (row.variantId == id) row.copy(targetPrepared = false, targetOriginalPresent = false,
                                            memoryWarmed = false, targetPreviewResident = false, cacheInspectedAtMillis = null,
                                            reason = "暂停窗口的缓存暂未核实") else row
                                    } }
                                }
                            }
                            makeRoom()
                        }
                    }
                    try {
                        val tasks = List(workers) { launch {
                            while (isActive && generation == request) {
                                val before = wake.value to retryRevision.value
                                val now = monotonicTimeMillis()
                                fun remaining(entry: SourceAttempt): Long {
                                    val retryWait = if (entry.deadline == 0L) 0L else if (retryRevision.value != entry.revision)
                                        (entry.earliest - now).coerceAtLeast(0L) else (entry.deadline - now).coerceAtLeast(0L)
                                    return maxOf(retryWait, policy.sourceCooldownRemainingMillis(entry.variant.sourceId))
                                }
                                queue.removeAll { !current(it.variant) }
                                val entry = queue.withIndex().filter { remaining(it.value) == 0L }
                                    .minWithOrNull(compareBy<IndexedValue<SourceAttempt>> {
                                        when { it.value.onePass -> 0; it.value.restore -> 1; it.value.deadline == 0L -> 2; else -> 3 }
                                    }.thenBy { it.index })?.value
                                if (entry == null) {
                                    val wait = queue.minOfOrNull(::remaining)
                                    if (wait == null) combine(wake, retryRevision) { a, b -> a to b }.first { it != before }
                                    else withTimeoutOrNull(wait.coerceAtLeast(1L)) {
                                        combine(wake, retryRevision) { a, b -> a to b }.first { it != before }
                                    }
                                    continue
                                }
                                queue.remove(entry)
                                val variant = entry.variant
                                val id = variant.storageId
                                paused.remove(id)
                                val number = (attempts[id] ?: 0) + if (entry.restore) 0 else 1
                                attempts[id] = number
                                mutableState.update { rows -> if (generation != request) rows else rows.map { row ->
                                    if (row.variantId == id) row.copy(phase = ReaderSourceStandbyPhase.QUEUED,
                                        manifestQueued = true, manifestActive = false, reason = "准备此来源目录") else row
                                } }
                                val revision = retryRevision.value
                                val child = launch(start = CoroutineStart.LAZY) {
                                    prepareSource(generation, variant, ordered.indexOf(variant), limits, bytes,
                                        retained.getOrPut(id) { linkedMapOf() }, current,
                                        refreshManifest = entry.onePass || number > 1 && !entry.restore, onePass = entry.onePass)
                                }
                                running[id] = child
                                activeAttempts[id] = entry
                                try { child.start(); child.join() }
                                finally {
                                    withContext(NonCancellable) { child.cancelAndJoin() }
                                    if (running[id] === child) { running.remove(id); activeAttempts.remove(id) }
                                    wake.value++
                                }
                                currentCoroutineContext().ensureActive()
                                if (generation != request || !current(variant)) continue
                                paused += id
                                val wasYielded = yielding.remove(id)
                                if (restart.remove(id)) enqueue(SourceAttempt(variant, onePass = true), first = true)
                                else if (wasYielded) enqueue(SourceAttempt(variant, restore = true))
                                else if (!entry.onePass && number < 2) {
                                    val at = monotonicTimeMillis()
                                    enqueue(SourceAttempt(variant, at + 5_000L, at + 30_000L, revision))
                                }
                                mutableState.update { rows -> if (generation != request) rows else rows.map { row ->
                                    if (row.variantId == id) row.copy(activePages = 0, manifestActive = false,
                                        manifestQueued = queue.any { it.variant.storageId == id }) else row
                                } }
                            }
                        } }
                        tasks.forEach { it.join() }
                    } finally {
                        if (retryOne === retry) retryOne = null
                        observations.cancel()
                    }
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
        prepared: LinkedHashMap<Int, Prepared>, current: (ChapterVariant) -> Boolean, refreshManifest: Boolean = false,
        onePass: Boolean = false,
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
            val pages = try { withTimeout(25_000L) {
                (if (refreshManifest) null else repository.cachedPages(owner, variant.storageId)) ?: policy.withPrefetchAdmission(
                    variant.sourceId, 1_048_576L, { false },
                    onWaiting = { wait -> update { it.copy(
                        phase = if (wait == null) ReaderSourceStandbyPhase.RESOLVING else ReaderSourceStandbyPhase.QUEUED,
                        manifestQueued = wait != null, manifestActive = wait == null,
                        reason = wait?.let(::waitingReason)) } },
                ) {
                    update { it.copy(phase = ReaderSourceStandbyPhase.RESOLVING,
                        manifestQueued = false, manifestActive = true, reason = null) }
                    try { withTimeout(15_000L) { repository.pages(owner, variant.storageId, forceRefresh = refreshManifest) } }
                    catch (timeout: TimeoutCancellationException) {
                        sourceRequestTimedOut = true
                        throw timeout
                    }
                }
            } } finally {
                // Retire this permit observation even if its source epoch just became invalid.
                // A replacement reader generation owns another row and must never be touched.
                mutableState.update { rows -> if (generation != request) rows else rows.map { row ->
                    if (row.variantId == variant.storageId) row.copy(manifestQueued = false, manifestActive = false) else row
                } }
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
                val failures = mutableMapOf<Int, Int>()
                val reservations = mutableMapOf<Int, PrefetchByteBudget>()
                val workTokens = mutableMapOf<Int, PrefetchWork>()
                val pageStages = mutableMapOf<Int, ReaderSourceStandbyPhase>()
                var writable = policy.canWritePrefetch()
                val writeRecovery = mutableSetOf<Int>()
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
                            stopped[index] = Stopped("原图缓存已回收或更新，等待重新准备", false, retryOnRelease = true)
                        } else if (prepared[index] === saved) {
                            prepared[index] = saved.copy(inspectedAt = wallTimeMillis())
                        }
                    }
                }
                suspend fun updateWindow(at: Position) = windowLock.withLock {
                    if (!active()) return@withLock
                    val nextWindow = windowAt(at, pages.size, limits.pages)
                    val target = nextWindow.target
                    val wanted = nextWindow.indices
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
                    failures.keys.filter { it !in wanted && it !in pending }.forEach { failures.remove(it) }
                    refreshRetained(wanted)
                    currentCoroutineContext().ensureActive()
                    if (!active()) return@withLock
                    for (index in wanted) {
                        val saved = prepared[index]
                        val warm = limits.warmMemory && order < limits.decodedPages && index == target
                        if (index in pending) continue
                        val stop = stopped[index]
                        if (saved == null && stop?.failed == true && (failures[index] ?: 0) >= 2) continue
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
                                                    stopped[index] = Stopped(failureReason(fetched.failure, catalog = false), true)
                                                    null
                                                }
                                                else -> {
                                                    stopped[index] = Stopped("当前资源或来源限制，稍后准备", false,
                                                        retryWhenWritable = fetched.deferred)
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
                                        failures.remove(index)
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
                            catch (deferred: ExperienceAdmissionDeferred) {
                                stopped[index] = Stopped(waitingReason(deferred.wait), false, retryWhenWritable = true)
                            }
                            catch (_: PrefetchBudgetExceeded) {
                                // A body larger than this allowance must not be redownloaded on
                                // every status tick. A later real reading window can try again.
                                stopped[index] = Stopped("备用窗口容量不足，调大容量或继续翻页后再准备", false)
                            } catch (_: OutOfMemoryError) {
                                stopped[index] = Stopped("内存保护暂停了小图准备", false)
                            } catch (failure: Exception) {
                                stopped[index] = Stopped(failureReason(failure, catalog = false), true)
                            } finally {
                                if (pending[index] === task) pending.remove(index)
                                val wantedNow = index in window?.indices.orEmpty()
                                if (stopped[index]?.failed == true && index !in prepared && !task.isCancelled)
                                    failures[index] = (failures[index] ?: 0) + 1
                                if (!wantedNow || index !in prepared) {
                                    if (reservations[index] === ticket) reservations.remove(index)
                                    ticket.close()
                                    if (!wantedNow) capacityRevision.value++
                                }
                                if (!wantedNow && workTokens[index] === token) {
                                    workTokens.remove(index)
                                    pageStages.remove(index)
                                    failures.remove(index)
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
                    combine(position, capacityRevision, retryRevision) { at, _, _ -> at }.collect { updateWindow(it) }
                }
                try {
                    var inspectedAt = monotonicTimeMillis()
                    while (currentCoroutineContext().isActive && active()) {
                        delay(if (pending.isEmpty()) 2_000L else 250L)
                        var resumeWrite = false
                        windowLock.withLock {
                            val writableNow = policy.canWritePrefetch()
                            val wanted = window?.indices.orEmpty()
                            if (!writable && writableNow) writeRecovery.addAll(wanted)
                            if (!writableNow) writeRecovery.clear()
                            writable = writableNow
                            for (index in writeRecovery.toList()) {
                                if (index in wanted && index in pending) continue
                                if (index in wanted && stopped[index]?.retryWhenWritable == true && stopped[index]?.failed == false) {
                                    stopped.remove(index)
                                    resumeWrite = true
                                }
                                writeRecovery.remove(index)
                            }
                            if (monotonicTimeMillis() - inspectedAt >= 2_000L) {
                                refreshRetained(window?.indices.orEmpty())
                                inspectedAt = monotonicTimeMillis()
                            }
                            publish()
                        }
                        val target = window?.target
                        if (target != null && target !in pending && target !in prepared &&
                            stopped[target]?.failed == true && (failures[target] ?: 0) >= 2) return@coroutineScope
                        if (onePass && pending.isEmpty() && window != null && window!!.indices.all {
                                it in prepared || it in stopped
                            }) return@coroutineScope
                        if (resumeWrite || bytes.remaining > 0L && stopped.any { (index, stop) ->
                                index in window?.indices.orEmpty() && (stop.retryOnRelease ||
                                    stop.failed && (failures[index] ?: 0) < 2 && monotonicTimeMillis() - stop.at >= 30_000L)
                            })
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
        } catch (deferred: ExperienceAdmissionDeferred) {
            update { it.copy(phase = ReaderSourceStandbyPhase.DEFERRED, reason = waitingReason(deferred.wait)) }
        } catch (_: OutOfMemoryError) {
            update { it.copy(phase = ReaderSourceStandbyPhase.DEFERRED, reason = "内存保护暂缓了备用目录准备") }
        } catch (failure: Exception) {
            update { it.copy(phase = ReaderSourceStandbyPhase.FAILED, reason = failureReason(failure, catalog = true)) }
        }
    }

    private fun windowAt(at: Position, pageCount: Int, limit: Int): Window {
        val target = approximateReaderPage(at.index, at.count, pageCount)
        val count = minOf(limit, pageCount)
        return Window(target, buildList {
            add(target)
            for (offset in 1 until count) {
                val next = target + offset * at.direction
                if (next in 0 until pageCount) add(next) else break
            }
            for (offset in 1 until count) {
                val previous = target - offset * at.direction
                if (size >= count || previous !in 0 until pageCount) break
                add(previous)
            }
        })
    }

    /** Yielding a worker preserves cache artifacts, with continued cache-only evidence checks. */
    private suspend fun refreshPaused(generation: Long, variant: ChapterVariant,
        prepared: LinkedHashMap<Int, Prepared>, limits: Limits, current: (ChapterVariant) -> Boolean,
        stillPaused: () -> Boolean,
    ) {
        fun valid() = generation == request && current(variant) && stillPaused()
        if (!valid()) return
        val previous = state.value.firstOrNull { it.variantId == variant.storageId } ?: return
        val total = previous.totalPages?.takeIf { it > 0 } ?: return
        val at = position.value
        val wanted = windowAt(at, total, limits.pages)
        val headers = repository.imageHeaders(SourceKeys.manga(variant.sourceId, variant.mangaId), variant.storageId).toMap()
        val views = wanted.indices.map { index ->
            var saved = prepared[index]
            if (saved != null && stamp(saved.original, saved.cacheKey, headers, variant.sourceId) != saved.stamp) {
                if (prepared[index] === saved) prepared.remove(index)
                saved = null
            }
            val old = previous.windowPages.firstOrNull { it.index == index }
            val resident = saved?.previewKey?.let { exact ->
                SingletonImageLoader.get(imagePlatformContext(context)).memoryCache?.keys?.contains(exact)
            } == true
            val phase = when { saved != null -> ReaderSourceStandbyPhase.READY
                old?.phase == ReaderSourceStandbyPhase.FAILED -> ReaderSourceStandbyPhase.FAILED
                else -> ReaderSourceStandbyPhase.DEFERRED }
            ReaderSourceStandbyPage(index, phase, saved != null, resident, saved?.stamp?.bytes ?: 0L,
                old?.receivedBytes ?: 0L, old?.expectedBytes, saved?.let { wallTimeMillis() },
                if (phase == ReaderSourceStandbyPhase.FAILED) old?.reason else "准备名额暂让出，原图按实际缓存核验")
        }
        if (!valid() || at != position.value) return
        val target = views.first { it.index == wanted.target }
        mutableState.update { rows -> if (!valid() || at != position.value) rows else rows.map { row ->
            if (row.variantId != variant.storageId) row else row.copy(phase = target.phase,
                targetPageIndex = wanted.target, requestedPages = views.size,
                preparedPages = views.count { it.originalPresent }, decodedPages = views.count { prepared[it.index]?.decoded == true },
                memoryWarmed = target.previewResident, targetPrepared = target.originalPresent,
                activePages = 0, manifestActive = false, queuedPages = 0, resolvingPages = 0, downloadingPages = 0,
                checkingPages = 0, settlingPages = 0, failedPages = views.count { it.phase == ReaderSourceStandbyPhase.FAILED },
                deferredPages = views.count { it.phase == ReaderSourceStandbyPhase.DEFERRED },
                cachedOriginalBytes = views.sumOf { it.cachedOriginalBytes }, previewResidentPages = views.count { it.previewResident },
                targetOriginalPresent = target.originalPresent, targetPreviewResident = target.previewResident,
                receivedBytes = views.sumOf { it.receivedBytes },
                expectedBytes = views.map { it.expectedBytes }.takeIf { values -> values.all { it != null } }?.sumOf { it ?: 0L },
                cacheInspectedAtMillis = views.mapNotNull { it.cacheInspectedAtMillis }.minOrNull(), windowPages = views,
                windowId = row.windowId + if (row.targetPageIndex != wanted.target ||
                    row.windowPages.map { it.index } != wanted.indices) 1L else 0L,
                reason = if (target.originalPresent) "原图仍在缓存，准备名额暂让出" else target.reason)
        } }
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
            variant.sourceName, ReaderSourceStandbyPhase.QUEUED, manifestQueued = true)

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

    /** Redacted classifications only; provider messages may contain request URLs or credentials. */
    private fun failureReason(failure: Throwable, catalog: Boolean): String {
        val scope = if (catalog) "备用目录" else "备用图片"
        val status = (failure as? SourceException)?.httpStatus
        return when {
            status == 401 || status == 209 || status == 210 || failure.message.orEmpty().contains("登录") -> "$scope 需要登录或账号授权"
            status == 429 || (failure as? SourceException)?.retryAfterSeconds?.let { it > 0 } == true -> "$scope 被来源限流，等待退避后再试"
            status != null -> "$scope 请求未成功 · HTTP $status"
            Regex("(?i)timeout|connect|dns|network|超时|连接|网络").containsMatchIn(failure.message.orEmpty()) -> "$scope 网络连接未成功"
            Regex("不支持|不兼容|能力").containsMatchIn(failure.message.orEmpty()) -> "$scope 当前能力不兼容"
            Regex("解析|格式|JSON|目录为空|图片处理").containsMatchIn(failure.message.orEmpty()) -> "$scope 解析或格式检查失败"
            else -> "$scope 读取失败，可在切源后重试"
        }
    }
}
