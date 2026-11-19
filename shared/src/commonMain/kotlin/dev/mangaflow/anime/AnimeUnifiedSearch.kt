package dev.mangaflow.anime

import dev.mangaflow.anime.sources.AnimeMetadataRepository
import dev.mangaflow.anime.sources.AnimeProvider
import dev.mangaflow.anime.sources.AnimeSourceRepository
import dev.mangaflow.anime.sources.lanerc.LanercProvider
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Presentation grouping only: never grants a canonical binding or an episode mapping. */
data class AnimeSearchGroup(
    val key: String,
    val subject: AnimeSubject? = null,
    val sources: List<AnimeSourceTitle> = emptyList(),
)

internal data class AnimeSearchIndex(
    val items: List<AnimeSubject> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val canLoadMore: Boolean get() = hasMore && !loading && error == null
}

internal val AnimeSourceResult.canLoadMoreSearch: Boolean
    get() = hasMore && !loading && !loadingMore && error == null

internal data class AnimeUnifiedSearchState(
    val query: String = "",
    val indexOnly: Boolean = false,
    val index: AnimeSearchIndex = AnimeSearchIndex(),
    val sources: List<AnimeSourceResult> = emptyList(),
    val groups: List<AnimeSearchGroup> = emptyList(),
)

/** Application-main-scope owner. A query has one independent cursor and request per provider. */
internal class AnimeUnifiedSearch(
    private val scope: CoroutineScope,
    private val metadata: AnimeMetadataRepository,
    private val repository: AnimeSourceRepository,
) {
    private data class Route(val snapshot: DownloadRouteSnapshot?, val error: String? = null) {
        fun requireAvailable() = checkNotNull(snapshot) { error ?: "网络线路不可用。" }
    }
    private data class Source(val revision: String, val route: Route)

    private val mutableState = MutableStateFlow(AnimeUnifiedSearchState())
    val state = mutableState.asStateFlow()
    private val permits = Semaphore(6)
    // The script engine can run two requests. Its queue should not consume native search permits.
    private val scriptPermits = Semaphore(2)
    private val sourceJobs = mutableMapOf<String, Job>()
    private val overlappingPages = mutableSetOf<String>()
    private var sourceKeys = emptyMap<String, Source>()
    private var indexRoute: Route? = null
    private var indexJob: Job? = null
    private var generation = 0L

    fun search(query: String, indexOnly: Boolean) {
        val current = mutableState.value
        if (query != current.query) {
            generation++
            indexJob?.cancel()
            sourceJobs.values.forEach { it.cancel() }
            sourceJobs.clear()
            overlappingPages.clear()
            sourceKeys = emptyMap()
            indexRoute = null
            mutableState.value = AnimeUnifiedSearchState(query = query, indexOnly = indexOnly)
        } else if (indexOnly != current.indexOnly) mutableState.value = current.copy(indexOnly = indexOnly)
        reconcile()
    }

    fun reconcile() {
        val search = mutableState.value
        if (search.query.isBlank()) return
        val route = routeFor(INDEX_ID)
        val refreshIndex = indexRoute != route
        val changedIndexRoute = indexRoute != null && refreshIndex
        if (refreshIndex) {
            indexJob?.cancel()
            indexRoute = route
        }
        val available = if (search.indexOnly) emptyList() else repository.providers
        val providers = available.mapNotNull { provider ->
            sourceKey(provider)?.let { provider to it }
        }
        val nextKeys = providers.associate { (provider, key) -> provider.id to key }
        val refreshed = nextKeys.filter { (id, key) -> sourceKeys[id] != key }.keys
        val removed = sourceKeys.keys - nextKeys.keys
        if (!refreshIndex && refreshed.isEmpty() && removed.isEmpty()) return
        (refreshed + removed).forEach { sourceJobs.remove(it)?.cancel() }
        overlappingPages.removeAll(refreshed + removed)
        sourceKeys = nextKeys
        val current = mutableState.value
        val retained = current.sources.associateBy { it.providerId }
        publish(current.copy(index = if (refreshIndex) AnimeSearchIndex() else current.index,
            sources = providers.map { (provider, _) ->
                retained[provider.id]?.takeIf { provider.id !in refreshed }
                    ?: AnimeSourceResult(provider.id, provider.name)
            }), regroup = true)
        if (refreshIndex) requestIndex(forceRefresh = changedIndexRoute)
        providers.filter { (provider, _) -> provider.id in refreshed }.forEach { (provider, _) -> requestSource(provider) }
    }

    fun loadMore() {
        val state = mutableState.value
        if (state.index.canLoadMore) requestIndex()
        if (state.indexOnly) return
        val ready = state.sources.filter { it.canLoadMoreSearch }.mapTo(mutableSetOf()) { it.providerId }
        repository.providers.filter { it.id in ready }.forEach(::requestSource)
    }

    fun retryIndex() = requestIndex(forceRefresh = true)

    fun retrySource(sourceId: String) {
        repository.provider(sourceId)?.let(::requestSource)
    }

    private fun requestIndex(forceRefresh: Boolean = false) {
        val state = mutableState.value
        val route = indexRoute ?: return
        if (state.query.isBlank() || state.index.loading) return
        val owner = generation
        val previous = state.index
        val page = nextPage(previous.page) ?: return
        publish(state.copy(index = previous.copy(loading = true, error = null)))
        indexJob = scope.launch {
            fun current(): Boolean {
                if (owner != generation || route != indexRoute) return false
                if (route == routeFor(INDEX_ID)) return true
                reconcile()
                return false
            }
            try {
                if (page == 1) {
                    val cached = metadata.cachedBrowse(AnimeBrowseMode.POPULAR, state.query, page, null, "", null)
                    if (!current()) return@launch
                    if (cached != null && cached.page == page) {
                        val live = mutableState.value
                        publish(live.copy(index = live.index.copy(items = cached.items)), regroup = true)
                    }
                }
                route.requireAvailable()
                val result = withTimeoutOrNull(SEARCH_TIMEOUT_MILLIS) {
                    metadata.browse(AnimeBrowseMode.POPULAR, state.query, page, null, "", null, forceRefresh = forceRefresh)
                } ?: error("番剧资料搜索超时，请重试。")
                check(result.page == page) { "番剧资料返回了不同页码。" }
                if (current()) publish(mutableState.value.copy(index = AnimeSearchIndex(
                    items = ((if (page == 1) emptyList() else previous.items) + result.items).distinctBy(AnimeSubject::id),
                    page = page, hasMore = result.hasMore)), regroup = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (current()) {
                    val live = mutableState.value
                    publish(live.copy(index = live.index.copy(loading = false,
                        error = failure.message ?: "番剧资料未能更新。")))
                }
            }
        }
    }

    private fun requestSource(provider: AnimeProvider) {
        val state = mutableState.value
        val key = sourceKeys[provider.id] ?: return
        val previous = state.sources.firstOrNull { it.providerId == provider.id } ?: return
        if (state.query.isBlank() || previous.loading || previous.loadingMore) return
        val owner = generation
        val page = nextPage(previous.page) ?: return
        publishSource(previous.copy(loading = page == 1, loadingMore = page > 1, error = null))
        // Register the exact job before it can start on the immediate main dispatcher.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            fun current(): Boolean {
                if (owner != generation || sourceKeys[provider.id] != key) return false
                if (repository.provider(provider.id)?.let(::sourceKey) == key) return true
                reconcile()
                return false
            }
            try {
                key.route.requireAvailable()
                suspend fun find() = permits.withPermit {
                    if (!current()) return@withPermit null
                    withTimeoutOrNull(SEARCH_TIMEOUT_MILLIS) { provider.search(state.query, page) }
                        ?: error("搜索超时，请重试。")
                }
                val found = (if (provider is LanercProvider) scriptPermits.withPermit { find() } else find())
                    ?: return@launch
                if (!current()) return@launch
                check(found.all { it.sourceId == provider.id && (it.sourceRevision == null || it.sourceRevision == key.revision) }) {
                    "来源返回的条目身份已变化，请重试。"
                }
                val known = previous.items.map { it.id to it.url }.toHashSet()
                val added = found.filter { known.add(it.id to it.url) }.map { it.copy(sourceRevision = key.revision) }
                if (added.isNotEmpty()) overlappingPages.remove(provider.id)
                // A provider may return one overlapping page when new episodes shift its results.
                val hasMore = provider.supportsSearchPagination && found.isNotEmpty() && page < Int.MAX_VALUE &&
                    (added.isNotEmpty() || overlappingPages.add(provider.id))
                publishSource(previous.copy(items = previous.items + added, page = page,
                    hasMore = hasMore, error = null), regroup = added.isNotEmpty())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (current()) publishSource(previous.copy(error = failure.message ?: "这个来源暂未响应。"))
            }
        }
        sourceJobs[provider.id] = job
        job.start()
    }

    private fun publishSource(row: AnimeSourceResult, regroup: Boolean = false) {
        val state = mutableState.value
        publish(state.copy(sources = state.sources.map { if (it.providerId == row.providerId) row else it }), regroup)
    }

    private fun publish(state: AnimeUnifiedSearchState, regroup: Boolean = false) {
        mutableState.value = if (regroup) state.copy(groups = groupAnimeSearch(state.index.items, state.sources, state.groups)) else state
    }

    private fun sourceKey(provider: AnimeProvider): Source? = runCatching {
        Source(provider.revision, routeFor(provider.id))
    }.getOrNull()

    private fun routeFor(sourceId: String): Route = try {
        Route(DownloadRoutingStore.activeSourceSnapshot(sourceId, DownloadCategory.ANIME))
    } catch (failure: IllegalStateException) {
        Route(null, failure.message)
    }

    private fun nextPage(page: Int): Int? = page.takeIf { it < Int.MAX_VALUE }?.plus(1)

    private companion object {
        const val INDEX_ID = "anime-bangumi"
        const val SEARCH_TIMEOUT_MILLIS = 7_000L
    }
}

internal fun groupAnimeSearch(
    subjects: List<AnimeSubject>, results: List<AnimeSourceResult>, previous: List<AnimeSearchGroup> = emptyList(),
): List<AnimeSearchGroup> {
    val groups = subjects.distinctBy { it.id }.map { AnimeSearchGroup("subject:${it.id}", it) }.toMutableList()
    // Only whitespace/case are cosmetic. Keep numbers, punctuation, season/film/special suffixes.
    fun normalized(title: String) = title.trim().lowercase().filterNot { it.isWhitespace() }
    val groupsByName = mutableMapOf<String, MutableList<Int>>()
    groups.forEachIndexed { index, group ->
        val subject = requireNotNull(group.subject)
        listOf(subject.title, subject.originalTitle).map(::normalized).filter(String::isNotBlank).distinct().forEach { name ->
            groupsByName.getOrPut(name) { mutableListOf() }.add(index)
        }
    }
    fun matchesIdentity(title: AnimeSourceTitle, group: AnimeSearchGroup): Boolean {
        // A source must not collapse two distinct native records into one result card.
        if (group.sources.any { it.sourceId == title.sourceId }) return false
        val subject = group.subject
        val reference = group.sources.firstOrNull()
        val identities = listOfNotNull(subject?.externalIds) + group.sources.map { it.externalIds }
        if (identities.any { ids -> title.externalIds.any { (key, value) ->
            value.isNotBlank() && ids[key]?.takeIf { it.isNotBlank() }?.let { it != value } == true
        } }) return false
        val years = listOfNotNull(subject?.year) + group.sources.mapNotNull { it.year }
        if (title.year != null && years.any { it != title.year }) return false
        val year = subject?.year ?: reference?.year
        val sharedId = identities.any { ids -> title.externalIds.any { (key, value) ->
            value.isNotBlank() && ids[key] == value
        } }
        // Unknown year/title-only similarity is not sufficient evidence, especially for remakes.
        return sharedId || (year != null && title.year == year)
    }
    fun AnimeSourceTitle.identity() = Triple(sourceId, id, url)
    results.flatMap { it.items }.distinctBy { it.identity() }.forEach { title ->
        val name = normalized(title.title)
        val candidates = groupsByName[name].orEmpty().filter { matchesIdentity(title, groups[it]) }
        val index = candidates.singleOrNull()
        if (index == null) {
            if (name.isNotBlank()) groupsByName.getOrPut(name) { mutableListOf() }.add(groups.size)
            groups += AnimeSearchGroup("source:${title.sourceId}:${title.id.length}:${title.id}:${title.url}", sources = listOf(title))
        } else groups[index] = groups[index].copy(sources = groups[index].sources + title)
    }
    // When a later response enriches a visible native card, keep its key and position.
    // If a group splits, only one successor can keep its old key.
    val oldBySubject = previous.mapNotNull { group -> group.subject?.id?.let { it to group.key } }.toMap()
    val oldBySource = previous.flatMap { group -> group.sources.map { it.identity() to group.key } }.toMap()
    val oldSourceOrder = previous.flatMap { it.sources }.mapIndexed { index, title -> title.identity() to index }.toMap()
    val order = previous.mapIndexed { index, group -> group.key to index }.toMap()
    val baselineKeys = groups.mapTo(mutableSetOf()) { it.key }
    fun existingCanonicalKey(group: AnimeSearchGroup) = group.subject?.id?.let(oldBySubject::get)
        ?.takeIf { it == group.key || it !in baselineKeys }
    val canonicalKeys = groups.mapNotNull(::existingCanonicalKey).toSet()
    val claimed = mutableSetOf<String>()
    return groups.map { group ->
        val inherited = existingCanonicalKey(group) ?: group.sources.mapNotNull { oldBySource[it.identity()] }
            .filter { it !in canonicalKeys && (it == group.key || it !in baselineKeys) && it !in claimed }
            .minByOrNull { order.getValue(it) }
        val key = inherited?.takeIf(claimed::add) ?: group.key
        group.copy(key = key, sources = group.sources.sortedBy { oldSourceOrder[it.identity()] ?: Int.MAX_VALUE })
    }.sortedBy { order[it.key] ?: Int.MAX_VALUE }
}
