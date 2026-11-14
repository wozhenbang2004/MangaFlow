package dev.mangaflow.data

import dev.mangaflow.data.plugins.prefetchScriptPage
import dev.mangaflow.data.plugins.scriptSourceImageEpoch
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.PrefetchBudgetExceeded
import dev.mangaflow.experience.PrefetchByteBudget
import dev.mangaflow.experience.PrefetchWork
import dev.mangaflow.experience.ReaderPagePreflightStatus
import dev.mangaflow.experience.prefetchOriginalPageResult
import dev.mangaflow.experience.preflightReaderPage
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** One optional owner; the repository and image cache remain the sole manifest/byte caches. */
internal class ReaderSourceStandbyController(
    private val context: PlatformContext,
    private val repository: UnifiedMangaRepository,
    private val scope: CoroutineScope,
) {
    private data class Limits(val sources: Int, val bytes: Long, val delayMillis: Long,
        val foreground: Boolean, val decodedPages: Int)
    private val mutableState = MutableStateFlow<List<ReaderSourceStandby>>(emptyList())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var key: String? = null
    private var request = 0L
    private var position = 0 to 0

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
        position = pageIndex.coerceAtLeast(0) to pageCount.coerceAtLeast(0)
        if (key == sessionKey && job?.isActive == true) return
        clear()
        if (candidates.isEmpty()) return
        key = sessionKey
        val generation = request
        val policy = ExperiencePolicyStore.get(context)
        job = scope.launch {
            policy.state.map { Limits(it.standbySources, it.standbyPrefetchBytes,
                it.sourceHedgeDelayMillis, it.foreground, it.decodedPrefetchPages) }
                .distinctUntilChanged().collectLatest { limits ->
                if (generation != request) return@collectLatest
                mutableState.value = emptyList()
                if (!limits.foreground || limits.sources <= 0 || limits.bytes <= 0L) return@collectLatest
                // Debounce the chapter entry, rather than racing every source on every page turn.
                delay(limits.delayMillis)
                if (generation != request) return@collectLatest
                val health = repository.sourceHealth.value
                val now = wallTimeMillis()
                val imageStages = setOf(SourceStage.PAGES, SourceStage.IMAGE_RESOLVE,
                    SourceStage.IMAGE_READ, SourceStage.IMAGE_DECODE)
                val selected = candidates.filter(current).sortedWith(
                    compareBy<ChapterVariant> { policy.sourceCooldownRemainingMillis(it.sourceId) > 0L }
                        .thenBy { candidate ->
                            val observed = health[candidate.sourceId]
                            when {
                                observed == null || observed.stage !in imageStages -> 1
                                observed.failure != null && now - observed.lastAttemptAt in 0L..60_000L -> 2
                                observed.failure == null && observed.lastSuccessAt > 0L -> 0
                                else -> 1
                            }
                        }.thenBy { candidate ->
                            if (repository.cachedPages(SourceKeys.manga(candidate.sourceId, candidate.mangaId),
                                    candidate.storageId) != null) 0 else 1
                        },
                ).take(limits.sources)
                val bytes = PrefetchByteBudget(limits.bytes)
                val (fromIndex, fromCount) = position
                mutableState.value = selected.map { candidate -> standby(workId, chapter, candidate,
                    ReaderSourceStandbyPhase.PREPARING) }
                coroutineScope {
                    selected.forEachIndexed { order, variant -> launch {
                        if (order > 0) delay(limits.delayMillis * order)
                        fun active() = generation == request && current(variant)
                        fun publish(phase: ReaderSourceStandbyPhase, target: Int? = null, count: Int? = null) {
                            if (!active()) return
                            mutableState.value = mutableState.value.map { row ->
                                if (row.variantId == variant.storageId) row.copy(phase = phase,
                                    targetPageIndex = target, totalPages = count) else row
                            }
                        }
                        val owner = SourceKeys.manga(variant.sourceId, variant.mangaId)
                        val sourceEpoch = scriptSourceImageEpoch(context, variant.sourceId)
                        var sourceRequestTimedOut = false
                        try {
                            if (!active()) return@launch
                            val pages = withTimeout(25_000L) {
                                repository.cachedPages(owner, variant.storageId) ?: policy.withPrefetchAdmission(
                                    variant.sourceId, 1_048_576L, { false },
                                ) {
                                    // Manifest waiting and its actual request have separate bounds.
                                    try { withTimeout(15_000L) { repository.pages(owner, variant.storageId) } }
                                    catch (timeout: TimeoutCancellationException) {
                                        sourceRequestTimedOut = true
                                        throw timeout
                                    }
                                }
                            }
                            if (!active() || scriptSourceImageEpoch(context, variant.sourceId) != sourceEpoch) return@launch
                            if (pages.isEmpty()) {
                                publish(ReaderSourceStandbyPhase.FAILED)
                                return@launch
                            }
                            val target = approximateReaderPage(fromIndex, fromCount, pages.size)
                            val page = pages[target]
                            val headers = repository.imageHeaders(owner, variant.storageId).toMap()
                            publish(ReaderSourceStandbyPhase.MANIFEST_READY, target, pages.size)
                            if (bytes.remaining <= 0L) return@launch
                            var stoppedPhase: ReaderSourceStandbyPhase? = null
                            val result = withTimeout(25_000L) {
                                var cacheKey: String? = null
                                val original = if (variant.sourceId.startsWith("js_")) {
                                    prefetchScriptPage(context, owner, variant.storageId, page, bytes, PrefetchWork())
                                } else {
                                    val fetched = prefetchOriginalPageResult(context, page, headers, variant.sourceId, bytes)
                                    cacheKey = fetched.diskCacheKey
                                    stoppedPhase = when {
                                        fetched.deferred -> ReaderSourceStandbyPhase.DEFERRED
                                        fetched.failure != null -> ReaderSourceStandbyPhase.FAILED
                                        else -> null
                                    }
                                    page.takeIf { fetched.received }
                                }
                                if (original == null) return@withTimeout null
                                if (!active() || scriptSourceImageEpoch(context, variant.sourceId) != sourceEpoch)
                                    return@withTimeout null
                                // A small sampled decode proves availability without decoding a huge original.
                                preflightReaderPage(context, original, headers, variant.sourceId,
                                    viewportWidthPx = 512, viewportHeightPx = 1024,
                                    diskCacheKey = cacheKey, warmMemory = order < limits.decodedPages)
                            }
                            if (!active() || scriptSourceImageEpoch(context, variant.sourceId) != sourceEpoch) return@launch
                            when (result?.status) {
                                ReaderPagePreflightStatus.READY -> publish(ReaderSourceStandbyPhase.READY, target, pages.size)
                                ReaderPagePreflightStatus.FAILED -> publish(ReaderSourceStandbyPhase.FAILED, target, pages.size)
                                ReaderPagePreflightStatus.DEFERRED -> publish(ReaderSourceStandbyPhase.DEFERRED, target, pages.size)
                                else -> stoppedPhase?.let { publish(it, target, pages.size) }
                            }
                        } catch (timeout: TimeoutCancellationException) {
                            currentCoroutineContext().ensureActive()
                            publish(if (sourceRequestTimedOut)
                                ReaderSourceStandbyPhase.FAILED else ReaderSourceStandbyPhase.DEFERRED)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: PrefetchBudgetExceeded) {
                            publish(ReaderSourceStandbyPhase.DEFERRED)
                        } catch (_: Exception) {
                            publish(ReaderSourceStandbyPhase.FAILED)
                        }
                    } }
                }
                // Retain the result until policy/chapter/session changes; per-page callbacks
                // do not cancel an in-flight image or replenish a chapter's speculative budget.
                // Source login changes have no catalogue event; inspect only their local epoch.
                while (generation == request) {
                    delay(1_000L)
                    val valid = selected.filter(current).mapTo(mutableSetOf()) { it.storageId }
                    mutableState.value = mutableState.value.filter { it.variantId in valid }
                    if (valid.isEmpty()) break
                }
            }
        }
    }

    private fun standby(workId: String, chapter: Chapter, variant: ChapterVariant,
        phase: ReaderSourceStandbyPhase) = ReaderSourceStandby(workId, chapter.id, variant.storageId,
        variant.sourceId, variant.sourceName, phase)
}
