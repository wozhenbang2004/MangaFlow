package dev.mangaflow.anime

import dev.mangaflow.anime.sources.AnimeMetadataRepository
import dev.mangaflow.anime.sources.DoubanAnimeRepository
import dev.mangaflow.anime.sources.DOUBAN_PROVIDER
import dev.mangaflow.anime.sources.BANGUMI_PROVIDER
import dev.mangaflow.anime.sources.AnimeProvider
import dev.mangaflow.anime.sources.AnimeSourceRepository
import dev.mangaflow.anime.sources.lanerc.LanercProvider
import dev.mangaflow.data.bodySourceSearchTerms
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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
    /** Stable completed prefix; a retried partial page replaces its own rows in remote order. */
    val completedItems: List<AnimeSubject> = emptyList(),
) {
    val canLoadMore: Boolean get() = hasMore && !loading && error == null
}

internal val AnimeSourceResult.canLoadMoreSearch: Boolean
    get() = hasMore && !loading && !loadingMore && error == null

internal data class AnimeUnifiedSearchState(
    val query: String = "",
    val indexOnly: Boolean = true,
    val catalogProvider: AnimeDiscoveryProvider = AnimeDiscoveryProvider.DOUBAN,
    val index: AnimeSearchIndex = AnimeSearchIndex(),
    val sources: List<AnimeSourceResult> = emptyList(),
    val groups: List<AnimeSearchGroup> = emptyList(),
)

/** Application-main-scope owner. A query has one independent cursor and request per provider. */
internal class AnimeUnifiedSearch(
    private val scope: CoroutineScope,
    private val metadata: AnimeMetadataRepository,
    private val repository: AnimeSourceRepository,
    private val douban: DoubanAnimeRepository,
) {
    private data class Route(val snapshot: DownloadRouteSnapshot?, val error: String? = null) {
        fun requireAvailable() = checkNotNull(snapshot) { error ?: "网络线路不可用。" }
    }
    private data class Source(val revision: String, val route: Route)
    private data class SearchPageResult(
        val matchedTerm: String,
        val items: List<AnimeSourceTitle>,
    )

    private val mutableState = MutableStateFlow(AnimeUnifiedSearchState())
    val state = mutableState.asStateFlow()
    private val permits = Semaphore(6)
    // Keep queued script searches from consuming the native search permits.
    private val scriptPermits = Semaphore(2)
    private val sourceJobs = mutableMapOf<String, Job>()
    private val sourceTerms = mutableMapOf<String, String>()
    private val firstPageAttempts = mutableMapOf<String, Set<String>>()
    private val overlappingPages = mutableSetOf<String>()
    private var sourceKeys = emptyMap<String, Source>()
    private var indexRoute: Route? = null
    private var indexJob: Job? = null
    private var firstPageIndexTerms = emptyList<String>()
    private var firstPageIndexSubject: AnimeSubject? = null
    private var generation = 0L

    fun search(query: String, indexOnly: Boolean, provider: AnimeDiscoveryProvider = AnimeDiscoveryProvider.DOUBAN) {
        require(provider == AnimeDiscoveryProvider.DOUBAN || provider == AnimeDiscoveryProvider.BANGUMI)
        val current = mutableState.value
        if (query != current.query || provider != current.catalogProvider) {
            generation++
            indexJob?.cancel()
            sourceJobs.values.forEach { it.cancel() }
            sourceJobs.clear()
            sourceTerms.clear()
            firstPageAttempts.clear()
            overlappingPages.clear()
            sourceKeys = emptyMap()
            indexRoute = null
            mutableState.value = AnimeUnifiedSearchState(query = query, indexOnly = indexOnly, catalogProvider = provider)
        } else if (indexOnly != current.indexOnly) mutableState.value = current.copy(indexOnly = indexOnly)
        reconcile()
    }

    fun reconcile() {
        val search = mutableState.value
        if (search.query.isBlank()) return
        val route = routeFor(indexId(search.catalogProvider))
        val refreshIndex = indexRoute != route
        val changedIndexRoute = indexRoute != null && refreshIndex
        if (refreshIndex) {
            indexJob?.cancel()
            indexRoute = route
            firstPageIndexTerms = emptyList()
            firstPageIndexSubject = null
        }
        val available = if (search.indexOnly) emptyList() else repository.providers
        val providers = available.mapNotNull { provider ->
            sourceKey(provider)?.let { provider to it }
        }
        val nextKeys = providers.associate { (provider, key) -> provider.id to key }
        val refreshed = nextKeys.filter { (id, key) -> sourceKeys[id] != key }.keys
        val removed = sourceKeys.keys - nextKeys.keys
        if (!refreshIndex && refreshed.isEmpty() && removed.isEmpty()) return
        (refreshed + removed).forEach {
            sourceJobs.remove(it)?.cancel()
            sourceTerms.remove(it)
            firstPageAttempts.remove(it)
        }
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
                if (route == routeFor(indexId(state.catalogProvider))) return true
                reconcile()
                return false
            }
            try {
                if (page == 1) {
                    val cached = if (state.catalogProvider == AnimeDiscoveryProvider.DOUBAN)
                        douban.cachedSearch(state.query, page)
                    else metadata.cachedBrowse(AnimeBrowseMode.POPULAR, state.query, page, null, "", null)
                    if (!current()) return@launch
                    if (cached != null && cached.page == page) {
                        val live = mutableState.value
                        publish(live.copy(index = live.index.copy(items = cached.items)), regroup = true)
                    }
                }
                route.requireAvailable()
                val result = withTimeoutOrNull(if (state.catalogProvider == AnimeDiscoveryProvider.DOUBAN)
                    CATALOG_TIMEOUT_MILLIS else SEARCH_TIMEOUT_MILLIS) {
                    if (state.catalogProvider == AnimeDiscoveryProvider.DOUBAN)
                        douban.search(state.query, page, forceRefresh)
                    else metadata.browse(AnimeBrowseMode.POPULAR, state.query, page, null, "", null, forceRefresh = forceRefresh)
                } ?: error("番剧资料搜索超时，请重试。")
                check(result.page == page) { "番剧资料返回了不同页码。" }
                val subject = if (page == 1) withContext(Dispatchers.Default) {
                    exactIndexSubject(state.query, result.items)
                } else null
                if (!current()) return@launch
                if (page == 1) {
                    firstPageIndexSubject = subject
                    firstPageIndexTerms = bodySourceSearchTerms(state.query,
                        subject?.let { listOf(it.title, it.originalTitle) }.orEmpty())
                }
                val prefix = if (page == 1) emptyList() else previous.completedItems
                val shown = (prefix + result.items).distinctBy(AnimeSubject::id)
                publish(mutableState.value.copy(index = AnimeSearchIndex(
                    items = shown,
                    page = if (result.error == null) page else previous.page,
                    hasMore = result.hasMore, error = result.error,
                    completedItems = if (result.error == null) shown else prefix)), regroup = true)
                if (page == 1) firstPageAttempts.keys.toList().forEach { sourceId ->
                    repository.provider(sourceId)?.let(::retryWithIndexTerms)
                }
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

    private fun requestSource(provider: AnimeProvider) = requestSource(provider, emptyList())

    // Publish the source's first page while the index is still loading. A confirmed
    // alternate title can recover an empty first page or a page with only other works.
    private fun retryWithIndexTerms(provider: AnimeProvider) {
        val attempted = firstPageAttempts[provider.id] ?: return
        val previous = mutableState.value.sources.firstOrNull { it.providerId == provider.id } ?: return
        if (previous.items.isNotEmpty()) {
            val subject = firstPageIndexSubject ?: return
            if (previous.items.any { autoPlaySubjectCandidate(subject, it) }) return
        }
        val newTerms = firstPageIndexTerms.filter { it !in attempted }
        if (newTerms.isNotEmpty()) requestSource(provider, newTerms)
    }

    private fun requestSource(provider: AnimeProvider, newTerms: List<String>) {
        val state = mutableState.value
        val key = sourceKeys[provider.id] ?: return
        val previous = state.sources.firstOrNull { it.providerId == provider.id } ?: return
        if (state.query.isBlank() || previous.loading || previous.loadingMore) return
        val owner = generation
        val lateRetry = newTerms.isNotEmpty()
        if (lateRetry && previous.page > 1) return
        val page = if (lateRetry) 1 else nextPage(previous.page) ?: return
        val attempted = if (lateRetry) firstPageAttempts[provider.id].orEmpty().toMutableSet() else mutableSetOf()
        if (page == 1 && !lateRetry) firstPageAttempts.remove(provider.id)
        val term = sourceTerms[provider.id] ?: state.query
        val expected = firstPageIndexSubject.takeIf { lateRetry }
        val waiting = previous.copy(loading = page == 1, loadingMore = page > 1, error = null, queued = true)
        publishSource(waiting)
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
                    publishSource(waiting.copy(queued = false))
                    withTimeoutOrNull(SEARCH_TIMEOUT_MILLIS) {
                        searchPage(provider, term, page, newTerms, expected, attempted)
                    }
                        ?: error("搜索超时，请重试。")
                }
                val (matchedTerm, found) =
                    (if (provider is LanercProvider) scriptPermits.withPermit { find() } else find())
                    ?: return@launch
                if (!current()) return@launch
                check(found.all { it.sourceId == provider.id && (it.sourceRevision == null || it.sourceRevision == key.revision) }) {
                    "来源返回的条目身份已变化，请重试。"
                }
                if (lateRetry && found.isEmpty() && (previous.items.isNotEmpty() || previous.error != null)) {
                    publishSource(previous)
                    return@launch
                }
                if (page == 1 && found.isNotEmpty()) sourceTerms[provider.id] = matchedTerm
                val itemsByIdentity = previous.items.associateByTo(linkedMapOf()) { it.id to it.url }
                val added = found.any { (it.id to it.url) !in itemsByIdentity }
                for (incoming in found) {
                    val identity = incoming.id to incoming.url
                    val existing = itemsByIdentity[identity]
                    // A later alias/page may enrich the same native catalogue. Retain its
                    // position and other results instead of discarding the newer evidence.
                    itemsByIdentity[identity] = incoming.copy(
                        coverUrl = incoming.coverUrl.ifBlank { existing?.coverUrl.orEmpty() },
                        year = incoming.year ?: existing?.year,
                        episodeCount = incoming.episodeCount ?: existing?.episodeCount,
                        externalIds = existing?.externalIds.orEmpty() + incoming.externalIds
                            .mapValues { it.value.trim() }.filterValues(String::isNotBlank),
                        updateText = incoming.updateText.ifBlank { existing?.updateText.orEmpty() },
                        ratingText = incoming.ratingText.ifBlank { existing?.ratingText.orEmpty() },
                        sourceRevision = key.revision,
                    )
                }
                val items = itemsByIdentity.values.toList()
                if (added) overlappingPages.remove(provider.id)
                // A provider may return one overlapping page when new episodes shift its results.
                val hasMore = provider.supportsSearchPagination && found.isNotEmpty() && page < Int.MAX_VALUE &&
                    (added || overlappingPages.add(provider.id))
                publishSource(previous.copy(items = items, page = page,
                    hasMore = hasMore, error = null), regroup = items != previous.items)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (current()) {
                    if (lateRetry && (previous.items.isNotEmpty() || previous.error != null)) publishSource(previous)
                    else publishSource(previous.copy(error = failure.message ?: "这个来源暂未响应。"))
                }
            } finally {
                if (page == 1 && attempted.isNotEmpty() && current()) {
                    firstPageAttempts[provider.id] = attempted.toSet()
                    if (!lateRetry) retryWithIndexTerms(provider)
                }
            }
        }
        sourceJobs[provider.id] = job
        job.start()
    }

    private suspend fun searchPage(
        provider: AnimeProvider, term: String, page: Int, newTerms: List<String>, expected: AnimeSubject?,
        attempted: MutableSet<String>,
    ): SearchPageResult {
        val firstRequest = newTerms.isEmpty()
        if (firstRequest) {
            attempted += term
            val found = provider.search(term, page)
            if (page > 1 || found.isNotEmpty()) return SearchPageResult(term, found)
        }
        val alternatives = if (firstRequest) {
            val index = mutableState.value.index.items
            withContext(Dispatchers.Default) { searchTermsFromIndex(term, index) }
        } else newTerms
        var answered = firstRequest
        var failure: Exception? = null
        for (candidate in alternatives) {
            if (!attempted.add(candidate)) continue
            val found = try {
                withTimeoutOrNull(SEARCH_ALIAS_TIMEOUT_MILLIS) { provider.search(candidate, page) }
                    ?: error("来源别名搜索超时，请重试。")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure = error
                continue
            }
            answered = true
            if (found.isNotEmpty() && (expected == null || found.any { autoPlaySubjectCandidate(expected, it) }))
                return SearchPageResult(candidate, found)
        }
        if (!answered) failure?.let { throw it }
        return SearchPageResult(term, emptyList())
    }

    private fun exactIndexSubject(term: String, index: List<AnimeSubject>): AnimeSubject? {
        val key = animeWorkTitleKey(term)
        return index.singleOrNull {
            key.isNotBlank() && (animeWorkTitleKey(it.title) == key || animeWorkTitleKey(it.originalTitle) == key)
        }
    }

    private fun searchTermsFromIndex(term: String, index: List<AnimeSubject>): List<String> {
        val subject = exactIndexSubject(term, index)
        val aliases = subject?.let { listOf(it.title, it.originalTitle) }.orEmpty()
        return bodySourceSearchTerms(term, aliases)
    }

    private fun publishSource(row: AnimeSourceResult, regroup: Boolean = false) {
        val state = mutableState.value
        publish(state.copy(sources = state.sources.map { if (it.providerId == row.providerId) row else it }), regroup)
    }

    private fun publish(state: AnimeUnifiedSearchState, regroup: Boolean = false) {
        mutableState.value = if (regroup) state.copy(groups = if (state.indexOnly)
            state.index.items.map { AnimeSearchGroup("subject:${it.id}", it) }
        else groupAnimeSearch(state.index.items, state.sources, state.groups, state.query)) else state
    }

    private fun sourceKey(provider: AnimeProvider): Source? = runCatching {
        Source(provider.revision, routeFor(provider.id))
    }.getOrNull()

    private fun indexId(provider: AnimeDiscoveryProvider): String = when (provider) {
        AnimeDiscoveryProvider.DOUBAN -> DOUBAN_PROVIDER
        AnimeDiscoveryProvider.BANGUMI -> BANGUMI_PROVIDER
        AnimeDiscoveryProvider.ANILIST -> error("此资料源不支持主目录搜索。")
    }

    private fun routeFor(sourceId: String): Route = try {
        Route(DownloadRoutingStore.activeSourceSnapshot(sourceId, DownloadCategory.ANIME))
    } catch (failure: IllegalStateException) {
        Route(null, failure.message)
    }

    private fun nextPage(page: Int): Int? = page.takeIf { it < Int.MAX_VALUE }?.plus(1)

    private companion object {
        const val CATALOG_TIMEOUT_MILLIS = 24_000L
        const val SEARCH_TIMEOUT_MILLIS = 7_000L
        const val SEARCH_ALIAS_TIMEOUT_MILLIS = 2_250L
    }
}

internal fun groupAnimeSearch(
    subjects: List<AnimeSubject>, results: List<AnimeSourceResult>,
    previous: List<AnimeSearchGroup> = emptyList(), query: String = "",
): List<AnimeSearchGroup> = groupAnimeWorks(subjects, results.flatMap { it.items }, previous,
    keepPreviousOrder = true, query = query)

internal fun groupAnimeTitles(
    titles: List<AnimeSourceTitle>, previous: List<AnimeSearchGroup> = emptyList(),
): List<AnimeSearchGroup> = groupAnimeWorks(emptyList(), titles, previous, keepPreviousOrder = false)

private fun groupAnimeWorks(
    subjects: List<AnimeSubject>, titles: List<AnimeSourceTitle>, previous: List<AnimeSearchGroup>,
    keepPreviousOrder: Boolean, query: String = "",
): List<AnimeSearchGroup> {
    fun AnimeSourceTitle.identity() = Triple(sourceId, id, url)
    val names = mutableMapOf<String, String>()
    fun normalized(title: String) = names.getOrPut(title) { animeWorkTitleKey(title) }
    val displayNames = mutableMapOf<String, Set<String>>()
    fun displayKeys(title: String) = displayNames.getOrPut(title) { animeWorkDisplayKeys(title) }
    val oldSourceOrder = previous.flatMap { it.sources }.mapIndexed { index, title -> title.identity() to index }.toMap()
    // Establish known identities and their aliases before attaching title-only records.
    // Within that evidence level, an arriving duplicate must not displace an older match.
    val distinctTitles = titles.distinctBy { it.identity() }.sortedWith(
        compareByDescending<AnimeSourceTitle> { animeAuthoritativeIds(it.externalIds).isNotEmpty() }
            .thenByDescending { it.year != null }
            .thenBy { oldSourceOrder[it.identity()] ?: Int.MAX_VALUE },
    )
    val groups = subjects.distinctBy { it.id }.map { AnimeSearchGroup("subject:${it.id}", it) }.toMutableList()
    val mergedGroups = mutableSetOf<Int>()
    val groupsByName = mutableMapOf<String, MutableSet<Int>>()
    val groupsById = mutableMapOf<Pair<String, String>, MutableSet<Int>>()
    fun addName(index: Int, name: String) {
        displayKeys(name).forEach { groupsByName.getOrPut(it) { linkedSetOf() }.add(index) }
    }
    fun addIdentities(index: Int, ids: Map<String, String>) {
        animeAuthoritativeIds(ids).forEach { identity ->
            groupsById.getOrPut(identity) { linkedSetOf() }.add(index)
        }
    }
    groups.forEachIndexed { index, group ->
        val subject = requireNotNull(group.subject)
        addName(index, subject.title)
        addName(index, subject.originalTitle)
        addIdentities(index, animeSubjectIds(subject))
    }
    fun groupNames(group: AnimeSearchGroup) =
        listOfNotNull(group.subject?.title, group.subject?.originalTitle) + group.sources.map { it.title }
    fun groupIds(group: AnimeSearchGroup) =
        (listOfNotNull(group.subject?.let(::animeSubjectIds)) + group.sources.map { it.externalIds })
            .flatMap(::animeAuthoritativeIds).toSet()
    fun groupYears(group: AnimeSearchGroup) = listOfNotNull(group.subject?.year) + group.sources.mapNotNull { it.year }
    fun canShareCard(title: AnimeSourceTitle, group: AnimeSearchGroup): Boolean {
        val titleIds = animeAuthoritativeIds(title.externalIds)
        val identities = groupIds(group)
        if (conflictingAnimeIds(titleIds, identities)) return false
        if (title.year != null && groupYears(group).any { it != title.year }) return false
        val sharedId = titleIds.intersect(identities).isNotEmpty()
        val workNames = groupNames(group)
        if (animeWorkTitlesConflict(workNames, listOf(title.title), requireMatchingEdition = !sharedId)) return false
        // Multiple native catalogues from one source remain distinct below this card.
        // Full-title display grouping never creates an episode or playback binding.
        return sharedId || workNames.any { displayKeys(it).intersect(displayKeys(title.title)).isNotEmpty() }
    }
    distinctTitles.forEach { title ->
        val identified = animeAuthoritativeIds(title.externalIds).flatMap { groupsById[it].orEmpty() }
            .distinct().filterNot(mergedGroups::contains)
        val candidates = identified.ifEmpty { displayKeys(title.title).flatMap { groupsByName[it].orEmpty() }.distinct() }
            .filter { it !in mergedGroups && canShareCard(title, groups[it]) }
        val canonical = candidates.filter { groups[it].subject != null }
        // An ID bridge can connect previously separate language aliases. Merge the whole
        // component only when every identity, known year and explicit version agrees.
        val bridge = identified.isNotEmpty() && candidates.size > 1 && canonical.size <= 1 &&
            !conflictingAnimeIds(candidates.flatMap { groupIds(groups[it]) }.toSet(), emptySet()) &&
            candidates.flatMap { groupYears(groups[it]) }.distinct().size <= 1 &&
            candidates.all { left -> candidates.all { right ->
                !animeWorkTitlesConflict(groupNames(groups[left]), groupNames(groups[right]))
            } }
        val index = if (bridge) canonical.singleOrNull() ?: candidates.first()
            else if (canonical.isEmpty()) candidates.singleOrNull() else canonical.singleOrNull()
        if (bridge && index != null) {
            val sources = candidates.flatMap { groups[it].sources }.distinctBy { it.identity() }
            groups[index] = groups[index].copy(sources = sources)
            candidates.filterNot { it == index }.forEach(mergedGroups::add)
            sources.forEach { source -> addName(index, source.title); addIdentities(index, source.externalIds) }
        }
        val target = index ?: groups.size
        if (index == null)
            groups += AnimeSearchGroup("source:${title.sourceId}:${title.id.length}:${title.id}:${title.url}", sources = listOf(title))
        else groups[index] = groups[index].copy(sources = groups[index].sources + title)
        addName(target, title.title)
        addIdentities(target, title.externalIds)
    }
    // When a later response enriches a visible native card, keep its key and position.
    // If a group splits, only one successor can keep its old key.
    val oldBySubject = previous.mapNotNull { group -> group.subject?.id?.let { it to group.key } }.toMap()
    val oldBySource = previous.flatMap { group -> group.sources.map { it.identity() to group.key } }.toMap()
    val order = previous.mapIndexed { index, group -> group.key to index }.toMap()
    val activeGroups = groups.filterIndexed { index, _ -> index !in mergedGroups }
    val baselineKeys = activeGroups.mapTo(mutableSetOf()) { it.key }
    fun existingCanonicalKey(group: AnimeSearchGroup) = group.subject?.id?.let(oldBySubject::get)
        ?.takeIf { it == group.key || it !in baselineKeys }
    val canonicalKeys = activeGroups.mapNotNull(::existingCanonicalKey).toSet()
    val claimed = mutableSetOf<String>()
    val retained = activeGroups.map { group ->
        val inherited = existingCanonicalKey(group) ?: group.sources.mapNotNull { oldBySource[it.identity()] }
            .filter { it !in canonicalKeys && (it == group.key || it !in baselineKeys) && it !in claimed }
            .minByOrNull { order.getValue(it) }
        val key = inherited?.takeIf(claimed::add) ?: group.key
        group.copy(key = key, sources = group.sources.sortedBy { oldSourceOrder[it.identity()] ?: Int.MAX_VALUE })
    }
    if (!keepPreviousOrder) return retained
    val exactQuery = normalized(query)
    return retained.sortedWith(compareByDescending<AnimeSearchGroup> { group ->
        exactQuery.isNotBlank() && (group.subject?.let {
            normalized(it.title) == exactQuery || normalized(it.originalTitle) == exactQuery
        } == true || group.sources.any { normalized(it.title) == exactQuery })
    }.thenBy { order[it.key] ?: Int.MAX_VALUE })
}
