package dev.mangaflow.anime

import dev.mangaflow.anime.sources.AnimeDiscoveryRepository
import dev.mangaflow.anime.sources.discoveryRetryDelay
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
        var recoveryAvailable = false
        var revalidatePages = 1
        var job: Job? = null
        var recovery: Job? = null
    }

    private class Slot(val query: AnimeDiscoveryQuery, val sources: List<SourceLoad>, val cacheEpoch: Long)
    private data class RetainedKey(val key: String, val query: AnimeDiscoveryQuery)
    private val slots = mutableMapOf<String, Slot>()
    private val retained = linkedMapOf<RetainedKey, Slot>()
    private var visibleKeys = emptySet<String>()

    fun visible(keys: Set<String>) {
        (visibleKeys - keys).forEach { key ->
            slots[key]?.sources?.forEach { source ->
                source.recovery?.cancel()
                source.recovery = null
            }
        }
        visibleKeys = keys
    }

    fun reset(key: String) {
        val previous = slots.remove(key) ?: return
        previous.sources.forEach { source ->
            ++source.generation
            source.job?.cancel()
            source.recovery?.cancel()
            source.job = null
            source.recovery = null
            source.feed = source.feed.copy(loading = false, loadingMore = false)
        }
        val identity = RetainedKey(key, previous.query)
        retained.remove(identity)
        retained[identity] = previous
        while (retained.size > 24) retained.remove(retained.keys.first())
    }

    fun restorePreview(fromKey: String, key: String) {
        val from = slots[fromKey] ?: return
        val now = slot(key) ?: return
        if (now.query != from.query || from.cacheEpoch != repository.cacheEpoch) return
        now.sources.zip(from.sources).forEach { (target, source) ->
            // A one-row home preview must not replace a previously loaded multi-page list.
            if (target.requested && target.route == source.route && target.feed.items.isNotEmpty() &&
                target.feed.page >= source.feed.page) return@forEach
            target.feed = source.feed.copy(loading = false, loadingMore = false, moreError = null)
            target.requested = source.requested
            target.route = source.route
            target.retryAt = source.retryAt
            target.recoveryAvailable = source.recoveryAvailable
            target.revalidatePages = source.revalidatePages
        }
        publish(key, now)
    }

    fun ensure(key: String) {
        val slot = slot(key) ?: return
        slot.sources.forEach { source ->
            val feed = source.feed
            val routeChanged = source.requested && source.route != routeFor(source.query)
            val needsFetch = when {
                routeChanged -> true
                source.job?.isActive == true -> false
                feed.error != null -> source.retryAt?.let { monotonicTimeMillis() >= it } == true
                else -> feed.fetchedAt?.let { !repository.isFresh(source.query, it) } ?: true
            }
            if (needsFetch) load(key, slot, source, append = false, force = !routeChanged && feed.error != null,
                preserveRange = !routeChanged && feed.items.isNotEmpty() && feed.page > 1)
            else scheduleRecovery(key, slot, source)
        }
    }

    fun refresh(key: String) {
        val slot = slot(key) ?: return
        slot.sources.forEach { load(key, slot, it, append = false, force = true) }
    }

    fun retry(key: String) {
        val slot = slot(key) ?: return
        val failed = slot.sources.filter { it.feed.error != null || it.feed.moreError != null }
        failed.filter { it.job?.isActive != true }.forEach { source ->
            load(key, slot, source, append = source.feed.moreError != null, force = true,
                preserveRange = source.revalidatePages > 1)
        }
    }

    fun more(key: String) {
        val slot = slot(key) ?: return
        val eligible = slot.sources.filter { it.feed.hasMore && it.job?.isActive != true && it.feed.error == null }
        val ready = if (slot.sources.size == 1) eligible else eligible.filter { it.feed.moreError == null }
        ready.forEach { source ->
            load(key, slot, source, append = true, force = source.feed.moreError != null)
        }
    }

    private fun slot(key: String): Slot? {
        val query = currentFeed(key)?.query ?: return null
        slots[key]?.takeIf { it.query == query }?.let { return it }
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
        allowRecovery: Boolean = true, preserveRange: Boolean = false) {
        val query = source.query
        val routeResult = runCatching { repository.route(query) }
        val route = routeResult.getOrNull()
        val routeChanged = source.requested && source.route != route
        val paging = append && !routeChanged
        val before = if (routeChanged) AnimeDiscoveryFeed(query) else source.feed
        val page = if (paging) before.page.toLong() + 1 else 1L
        val lastPage = if (!paging && !routeChanged && preserveRange) before.page.coerceAtLeast(1).toLong() else page
        val request = ++source.generation
        source.requested = true
        source.route = route
        source.retryAt = null
        source.recoveryAvailable = false
        if (!paging) source.revalidatePages = lastPage.toInt()
        source.recovery?.cancel()
        source.recovery = null
        source.job?.cancel()
        source.feed = before.copy(loading = !paging, loadingMore = paging,
            error = if (paging) before.error else null, moreError = null)
        publish(key, slot)
        fun ownsRequest() = slots[key] === slot && currentFeed(key)?.query == slot.query &&
            source.generation == request && routeFor(query) == route
        fun update(transform: (AnimeDiscoveryFeed) -> AnimeDiscoveryFeed) {
            if (!ownsRequest()) return
            source.feed = transform(source.feed)
            publish(key, slot)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                require(page in 1..Int.MAX_VALUE.toLong()) { "分页位置已超出来源支持范围。" }
                if (!paging && before.items.isEmpty()) repository.cached(query, route = route)?.let { cached ->
                    update { it.copy(items = cached.items, page = cached.page, hasMore = cached.hasMore,
                        fetchedAt = cached.fetchedAt) }
                }
                var result = repository.browse(query, page.toInt(), forceRefresh = force, route = routeResult.getOrThrow())
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
                        loading = false, loadingMore = false, moreError = null,
                        fetchedAt = if (paging) feed.fetchedAt else fetchedAt)
                }
                if (ownsRequest()) source.revalidatePages = 1
            } catch (cancel: CancellationException) {
                update { it.copy(loading = false, loadingMore = false) }
                throw cancel
            } catch (failure: Exception) {
                if (!ownsRequest()) return@launch
                val message = failure.message ?: "这一栏暂未取得，可单独重试。"
                val wait = discoveryRetryDelay(failure)
                if (!paging) {
                    source.retryAt = wait?.let { monotonicTimeMillis() + it }
                    source.recoveryAvailable = allowRecovery && wait != null && wait <= 60_000L
                }
                update { it.copy(loading = false, loadingMore = false,
                    error = if (paging) it.error else message, moreError = message.takeIf { paging }) }
                scheduleRecovery(key, slot, source)
            } finally {
                if (source.generation == request) source.job = null
            }
        }
        source.job = job
        job.start()
    }

    private fun scheduleRecovery(key: String, slot: Slot, source: SourceLoad) {
        val deadline = source.retryAt ?: return
        if (!source.recoveryAvailable || source.recovery?.isActive == true || key !in visibleKeys) return
        val generation = source.generation
        source.recovery = scope.launch {
            delay((deadline - monotonicTimeMillis()).coerceAtLeast(1L))
            if (slots[key] === slot && currentFeed(key)?.query == slot.query && source.generation == generation &&
                source.route == routeFor(source.query) && key in visibleKeys) {
                source.recovery = null
                load(key, slot, source, append = false, force = true, allowRecovery = false,
                    preserveRange = source.revalidatePages > 1)
            }
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
        publishFeed(key, previous.copy(
            items = items, page = maxOf(primary.page, peer.page),
            hasMore = slot.sources.any { it.feed.hasMore && it.feed.error == null && it.feed.moreError == null },
            loading = loading, loadingMore = primary.loadingMore || peer.loadingMore,
            error = sourceErrors.values.firstOrNull().takeIf { items.isEmpty() && !loading },
            moreError = primary.moreError ?: peer.moreError,
            fetchedAt = listOfNotNull(primary.fetchedAt, peer.fetchedAt).minOrNull(),
            sourceErrors = sourceErrors,
        ))
    }
}
