package dev.mangaflow.anime

import dev.mangaflow.anime.sources.AnimeCachedDiscoveryPage
import dev.mangaflow.anime.sources.AnimeDiscoveryRepository
import dev.mangaflow.anime.sources.discoveryRetryDelay
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Each visible list projects its own source pages. A provider owns its own cursor and retry. */
internal class AnimeDiscoveryLoads(
    private val scope: CoroutineScope,
    private val repository: AnimeDiscoveryRepository,
    private val currentFeed: (String) -> AnimeDiscoveryFeed?,
    private val publishFeed: (String, AnimeDiscoveryFeed) -> Unit,
) {
    private class SourceLoad(val query: AnimeDiscoveryQuery) {
        var feed = AnimeDiscoveryFeed(query)
        var requested = false
        var route: DownloadRouteSnapshot? = null
        var generation = 0L
        var retryAt: Long? = null
        var recoveryAttempts = 0
        var retryRequest: RetryRequest? = null
        var retryAfterVisit: Long? = null
        var revalidatePages = 1
        var job: Job? = null
        var recovery: Job? = null
        var nextCachedPage: Deferred<AnimeCachedDiscoveryPage?>? = null
    }

    private data class RetryRequest(val page: Long, val append: Boolean, val lastPage: Long, val error: String)
    private class Slot(val query: AnimeDiscoveryQuery, val sources: List<SourceLoad>, val cacheEpoch: Long)
    private data class RetainedKey(val key: String, val query: AnimeDiscoveryQuery)
    private data class VisibleFeed(val query: AnimeDiscoveryQuery, val visit: Long)
    private val slots = mutableMapOf<String, Slot>()
    private val retained = linkedMapOf<RetainedKey, Slot>()
    private var visibleFeeds = emptyMap<String, VisibleFeed>()
    private var visitSequence = 0L

    fun visible(keys: Set<String>) {
        val previous = visibleFeeds
        visibleFeeds = buildMap {
            keys.forEach { key ->
                val query = currentFeed(key)?.query ?: return@forEach
                put(key, previous[key]?.takeIf { it.query == query } ?: VisibleFeed(query, ++visitSequence))
            }
        }
        val leaving = previous.keys - visibleFeeds.keys
        leaving.forEach { key ->
            slots[key]?.let { slot ->
                slot.sources.forEach { source ->
                    source.nextCachedPage?.cancel()
                    source.nextCachedPage = null
                    source.recovery?.cancel()
                    source.recovery = null
                    source.feed = source.feed.copy(retryScheduled = false,
                        retryAttempt = source.feed.retryAttempt.takeIf { source.job?.isActive == true } ?: 0)
                }
                publish(key, slot)
            }
        }
    }

    fun reset(key: String) {
        val previous = slots.remove(key) ?: return
        previous.sources.forEach { source ->
            val interrupted = source.retryRequest?.takeIf { source.job?.isActive == true }
            if (interrupted != null) {
                // Changing filters may interrupt a retry; returning keeps its remaining budget.
                rememberRecovery(key, source, interrupted)
                source.feed = source.feed.copy(error = if (interrupted.append) source.feed.error else interrupted.error,
                    moreError = interrupted.error.takeIf { interrupted.append })
            }
            ++source.generation
            source.job?.cancel()
            source.recovery?.cancel()
            source.nextCachedPage?.cancel()
            source.job = null
            source.recovery = null
            source.nextCachedPage = null
            source.feed = source.feed.copy(loading = false, loadingMore = false, retryScheduled = false, retryAttempt = 0)
        }
        if (previous.cacheEpoch != repository.cacheEpoch) return
        val identity = RetainedKey(key, previous.query)
        retained.remove(identity)
        retained[identity] = previous
        while (retained.size > 24) retained.remove(retained.keys.first())
    }

    fun ensure(key: String) {
        val slot = slot(key) ?: return
        slot.sources.forEach { source ->
            val feed = source.feed
            val routeChanged = source.requested && source.route != routeFor(source.query)
            val needsFetch = when {
                routeChanged -> true
                source.job?.isActive == true -> false
                feed.error != null || feed.moreError != null -> false
                else -> feed.fetchedAt?.let { !repository.isFresh(source.query, it) } ?: true
            }
            if (needsFetch) load(key, slot, source, append = false, force = false,
                preserveRange = !routeChanged && feed.items.isNotEmpty() && feed.page > 1)
            else scheduleRecovery(key, slot, source)
        }
    }

    fun refresh(key: String) {
        val slot = slot(key) ?: return
        slot.sources.forEach { load(key, slot, it, append = false, force = true) }
    }

    fun retry(key: String) {
        val slot = loadedSlot(key) ?: return
        val failed = slot.sources.filter { it.feed.error != null || it.feed.moreError != null }
        failed.filter { it.job?.isActive != true }.forEach { source ->
            load(key, slot, source, append = source.feed.moreError != null, force = true,
                preserveRange = source.revalidatePages > 1)
        }
    }

    fun more(key: String) {
        val slot = loadedSlot(key) ?: return
        val eligible = slot.sources.filter { it.feed.hasMore && it.job?.isActive != true && it.feed.error == null }
        val ready = if (slot.sources.size == 1) eligible else eligible.filter { it.feed.moreError == null }
        ready.forEach { source ->
            load(key, slot, source, append = true, force = source.feed.moreError != null)
        }
    }

    private fun loadedSlot(key: String): Slot? {
        val slot = slot(key) ?: return null
        if (slot.sources.any { it.requested }) return slot
        // The displayed retry or pagination action may predate a cache clear. Restart at page one.
        if (visibleFeeds[key]?.query == slot.query) ensure(key)
        return null
    }

    /** Approaching the end of a touch grid may decode one existing page per source. Only more() advances it. */
    fun prepareCachedPage(key: String) {
        val slot = slots[key] ?: return
        if (visibleFeeds[key]?.query != slot.query || currentFeed(key)?.query != slot.query ||
            slot.cacheEpoch != repository.cacheEpoch) return
        slot.sources.forEach { source ->
            val feed = source.feed
            val route = source.route ?: return@forEach
            if (source.nextCachedPage != null || source.job?.isActive == true || !feed.hasMore ||
                feed.page !in 1 until Int.MAX_VALUE || feed.error != null || feed.moreError != null ||
                routeFor(source.query) != route) return@forEach
            source.nextCachedPage = scope.async {
                try { repository.cachedPage(source.query, feed.page + 1, route, freshOnly = true) }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
            }
        }
    }

    private fun slot(key: String): Slot? {
        val query = currentFeed(key)?.query ?: return null
        slots[key]?.takeIf { it.query == query && it.cacheEpoch == repository.cacheEpoch }?.let { return it }
        reset(key)
        val queries = listOf(query.copy(combined = false)) + listOfNotNull(animeDiscoveryPeerQuery(query))
        val saved = retained.remove(RetainedKey(key, query))?.takeIf { candidate ->
            candidate.cacheEpoch == repository.cacheEpoch && candidate.sources.map { it.query } == queries && candidate.sources.all { source ->
                !source.requested || source.route == routeFor(source.query)
            }
        }
        return (saved ?: Slot(query, queries.map(::SourceLoad), repository.cacheEpoch)).also {
            slots[key] = it
            if (saved != null) publish(key, it)
        }
    }

    private fun routeFor(query: AnimeDiscoveryQuery) = runCatching { repository.route(query) }.getOrNull()

    private fun load(key: String, slot: Slot, source: SourceLoad, append: Boolean, force: Boolean,
        preserveRange: Boolean = false, recoveryRequest: RetryRequest? = null) {
        val query = source.query
        val routeResult = runCatching { repository.route(query) }
        val route = routeResult.getOrNull()
        val routeChanged = source.requested && source.route != route
        val paging = append && !routeChanged
        val before = if (routeChanged) AnimeDiscoveryFeed(query) else source.feed
        val page = if (paging) before.page.toLong() + 1 else 1L
        val lastPage = if (!paging && !routeChanged && preserveRange) before.page.coerceAtLeast(1).toLong() else page
        val reopening = recoveryRequest != null && source.recoveryAttempts >= RETRY_DELAYS.size
        if (recoveryRequest != null && (routeChanged || source.retryRequest !== recoveryRequest ||
                (reopening && !mayReloadOnReturn(key, slot, source, recoveryRequest)) || page != recoveryRequest.page ||
                paging != recoveryRequest.append || lastPage != recoveryRequest.lastPage)) return
        if (recoveryRequest == null) {
            source.recoveryAttempts = 0
            source.retryRequest = null
            source.retryAfterVisit = null
        } else if (!reopening) source.recoveryAttempts++
        val request = ++source.generation
        val prepared = source.nextCachedPage?.takeIf { paging && !force }
        if (prepared == null) source.nextCachedPage?.cancel()
        source.nextCachedPage = null
        source.requested = true
        source.route = route
        source.retryAt = null
        if (!paging) source.revalidatePages = lastPage.toInt()
        source.recovery?.cancel()
        source.recovery = null
        source.job?.cancel()
        source.feed = before.copy(loading = !paging, loadingMore = paging,
            error = if (paging) before.error else null, moreError = null,
            retryScheduled = false, retryAttempt = if (reopening) 1 else source.recoveryAttempts)
        publish(key, slot)
        fun ownsRequest() = slots[key] === slot && slot.cacheEpoch == repository.cacheEpoch && currentFeed(key)?.query == slot.query &&
            source.generation == request && routeFor(query) == route
        fun update(transform: (AnimeDiscoveryFeed) -> AnimeDiscoveryFeed) {
            if (!ownsRequest()) return
            source.feed = transform(source.feed)
            publish(key, slot)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var cancelled = false
            try {
                require(page in 1..Int.MAX_VALUE.toLong()) { "分页位置已超出来源支持范围。" }
                val cached = when {
                    prepared != null -> prepared.await()
                    !paging && before.items.isEmpty() -> repository.cachedPage(query, page.toInt(), route)
                    else -> null
                }
                if (!ownsRequest()) return@launch
                val fresh = cached?.takeIf { !force && repository.canReuse(it, query, page.toInt(), route) }?.value
                if (!paging && fresh == null && cached != null) {
                    val preview = cached.value
                    update { it.copy(items = preview.items, page = preview.page, hasMore = preview.hasMore,
                        fetchedAt = preview.fetchedAt) }
                }
                var result = fresh ?: repository.browse(query, page.toInt(), forceRefresh = force, route = routeResult.getOrThrow())
                check(result.page == page.toInt()) { "来源返回了不同的页码。" }
                val replacement = result.items.toMutableList()
                var fetchedAt = result.fetchedAt
                // Revalidate the loaded range as one snapshot; an incomplete refresh keeps the old range.
                while (!paging && result.hasMore && result.page.toLong() < lastPage) {
                    if (!ownsRequest()) return@launch
                    val nextPage = result.page + 1
                    result = repository.browse(query, nextPage, forceRefresh = force, route = routeResult.getOrThrow())
                    check(result.page == nextPage) { "来源返回了不同的页码。" }
                    replacement += result.items
                    fetchedAt = minOf(fetchedAt, result.fetchedAt)
                }
                update { feed ->
                    val items = if (paging) feed.items + result.items else replacement
                    feed.copy(items = items.distinctBy { it.key }, page = result.page, hasMore = result.hasMore,
                        loading = false, loadingMore = false, error = null, moreError = null,
                        retryScheduled = false, retryAttempt = 0,
                        fetchedAt = if (paging) feed.fetchedAt else fetchedAt)
                }
                if (ownsRequest()) {
                    source.revalidatePages = 1
                    source.retryRequest = null
                    source.retryAt = null
                    source.retryAfterVisit = null
                    source.recoveryAttempts = 0
                }
            } catch (cancel: CancellationException) {
                cancelled = true
                if (ownsRequest()) {
                    source.retryAt = null
                    source.retryRequest = null
                    source.retryAfterVisit = null
                }
                update { it.copy(loading = false, loadingMore = false, retryScheduled = false, retryAttempt = 0) }
                throw cancel
            } catch (failure: Exception) {
                if (!ownsRequest()) return@launch
                val message = failure.message ?: "这一栏暂未取得，可单独重试。"
                rememberRecovery(key, source, RetryRequest(page, paging, lastPage, message), failure)
                update { it.copy(loading = false, loadingMore = false,
                    error = if (paging) it.error else message, moreError = message.takeIf { paging },
                    retryScheduled = false, retryAttempt = 0) }
            } finally {
                prepared?.cancel()
                if (source.generation == request) {
                    source.job = null
                    if (!cancelled && slots[key] === slot && slot.cacheEpoch != repository.cacheEpoch &&
                        currentFeed(key)?.query == slot.query && visibleFeeds[key]?.query == slot.query) ensure(key)
                    else if (!cancelled && ownsRequest()) scheduleRecovery(key, slot, source)
                }
            }
        }
        source.job = job
        job.start()
    }

    private fun rememberRecovery(key: String, source: SourceLoad, request: RetryRequest, failure: Exception? = null) {
        val remaining = RETRY_DELAYS.getOrNull(source.recoveryAttempts)
        // An empty HOME page with a known transient failure must not become permanently
        // manual-only. Once the normal budget is exhausted, allow one read on a new visit.
        // A cancelled retry was already eligible; a new failure still needs typed evidence.
        val canReopen = remaining == null && isEmptyHomeRead(key, source, request)
        val backoff = remaining ?: RETRY_DELAYS.last().takeIf { canReopen }
        val wait = backoff?.let {
            if (failure == null) it else discoveryRetryDelay(failure, transientDelay = it)?.coerceAtLeast(it)
        }
        source.retryAt = wait?.let { monotonicTimeMillis() + it }
        source.retryRequest = request.takeIf { wait != null }
        source.retryAfterVisit = visitSequence.takeIf { wait != null && canReopen }
    }

    private fun isEmptyHomeRead(key: String, source: SourceLoad, request: RetryRequest): Boolean =
        key == animeDiscoveryFeedKey(AnimeDiscoveryTab.HOME) && !request.append && request.page == 1L &&
            request.lastPage == 1L && source.feed.items.isEmpty()

    private fun mayReloadOnReturn(key: String, slot: Slot, source: SourceLoad, request: RetryRequest): Boolean {
        val view = visibleFeeds[key]?.takeIf { it.query == slot.query } ?: return false
        return isEmptyHomeRead(key, source, request) && source.retryAfterVisit?.let { view.visit > it } == true
    }

    private fun scheduleRecovery(key: String, slot: Slot, source: SourceLoad) {
        val deadline = source.retryAt ?: return
        val retry = source.retryRequest ?: return
        val view = visibleFeeds[key]?.takeIf { it.query == slot.query } ?: return
        val reopening = source.recoveryAttempts >= RETRY_DELAYS.size
        if (source.job?.isActive == true || source.recovery != null ||
            (reopening && !mayReloadOnReturn(key, slot, source, retry))) return
        val generation = source.generation
        lateinit var recovery: Job
        recovery = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay((deadline - monotonicTimeMillis()).coerceAtLeast(1L))
                if (slots[key] === slot && currentFeed(key)?.query == slot.query && source.generation == generation &&
                    source.route == routeFor(source.query) && source.retryRequest === retry && visibleFeeds[key] === view) {
                    source.recovery = null
                    source.feed = source.feed.copy(retryScheduled = false, retryAttempt = 0)
                    publish(key, slot)
                    load(key, slot, source, append = retry.append, force = true,
                        preserveRange = !retry.append && retry.lastPage > 1, recoveryRequest = retry)
                }
            } finally {
                if (source.recovery === recovery) {
                    source.recovery = null
                    source.feed = source.feed.copy(retryScheduled = false, retryAttempt = 0)
                    if (slots[key] === slot && source.generation == generation) publish(key, slot)
                }
            }
        }
        source.recovery = recovery
        source.feed = source.feed.copy(retryScheduled = true, retryAttempt = if (reopening) 1 else source.recoveryAttempts + 1)
        publish(key, slot)
        if (!recovery.start() && source.recovery === recovery) {
            source.recovery = null
            source.feed = source.feed.copy(retryScheduled = false, retryAttempt = 0)
            publish(key, slot)
        }
    }

    private fun publish(key: String, slot: Slot) {
        val previous = currentFeed(key)?.takeIf { it.query == slot.query } ?: return
        val primary = slot.sources.first().feed
        val peer = slot.sources.getOrNull(1)?.feed
        if (peer == null) {
            publishFeed(key, primary.copy(query = slot.query,
                items = combineAnimeDiscoveryItems(primary.items, emptyList(), previous.items)))
            return
        }
        val items = combineAnimeDiscoveryItems(primary.items, peer.items, previous.items)
        val loading = primary.loading || peer.loading
        val sourceErrors = slot.sources.mapNotNull { source ->
            (source.feed.error ?: source.feed.moreError)?.let { source.query.provider to it }
        }.toMap()
        val scheduled = slot.sources.filter { it.feed.retryScheduled }
        publishFeed(key, previous.copy(
            items = items, page = maxOf(primary.page, peer.page),
            hasMore = slot.sources.any { it.feed.hasMore && it.feed.error == null && it.feed.moreError == null },
            loading = loading, loadingMore = primary.loadingMore || peer.loadingMore,
            error = sourceErrors.values.firstOrNull().takeIf { items.isEmpty() && !loading },
            moreError = primary.moreError ?: peer.moreError,
            fetchedAt = listOfNotNull(primary.fetchedAt, peer.fetchedAt).minOrNull(),
            sourceErrors = sourceErrors,
            retryScheduled = scheduled.isNotEmpty(),
            retryAttempt = (scheduled.ifEmpty { slot.sources }).maxOf { it.feed.retryAttempt },
        ))
    }

    private companion object {
        val RETRY_DELAYS = longArrayOf(2_000L, 5_000L, 12_000L)
    }
}
