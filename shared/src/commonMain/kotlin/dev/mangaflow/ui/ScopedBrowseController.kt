package dev.mangaflow.ui

import dev.mangaflow.BrowseState
import dev.mangaflow.MangaViewModel
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.data.FederatedSearchSource
import dev.mangaflow.data.MangaTag
import dev.mangaflow.data.RankAudience
import dev.mangaflow.data.RankPeriod
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.*
import dev.mangaflow.data.Manga
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** The shared discovery/search view does not own a source or its request lifetime. */
internal interface BrowseUiActions {
    val state: StateFlow<BrowseState>
    fun setActive(active: Boolean)
    fun setQuery(query: String)
    fun submitSearch()
    fun setSearchTarget(target: SearchTarget)
    fun toggleSearchTag(tag: MangaTag)
    fun retrySearchTags()
    fun clearSearch()
    fun suspendBrowseSearch()
    fun resumeBrowseSearch()
    fun selectDiscovery(
        mode: DiscoveryMode, period: RankPeriod? = null,
        audience: RankAudience? = null, clearQuery: Boolean = false,
    )
    fun refresh()
    fun retryBrowse()
    fun loadMore()
    fun browseBackground(selection: BrowseState): BrowseState
}

/** Keep the ordinary ViewModel's cache and complete source semantics unchanged. */
internal class StandardBrowseActions(private val model: MangaViewModel) : BrowseUiActions {
    override val state: StateFlow<BrowseState> = model.browse
    private var active = true

    // The ViewModel owns its jobs. This gates new UI requests; it does not cancel those jobs.
    override fun setActive(active: Boolean) { this.active = active }
    override fun setQuery(query: String) { if (active) model.setQuery(query) }
    override fun submitSearch() { if (active) model.submitSearch() }
    override fun setSearchTarget(target: SearchTarget) { if (active) model.setSearchTarget(target) }
    override fun toggleSearchTag(tag: MangaTag) { if (active) model.toggleSearchTag(tag) }
    override fun retrySearchTags() { if (active) model.retrySearchTags() }
    override fun clearSearch() { if (active) model.clearSearch() }
    override fun suspendBrowseSearch() { if (active) model.suspendBrowseSearch() }
    override fun resumeBrowseSearch() { if (active) model.resumeBrowseSearch() }
    override fun selectDiscovery(
        mode: DiscoveryMode, period: RankPeriod?, audience: RankAudience?, clearQuery: Boolean,
    ) {
        if (active) model.selectDiscovery(mode, period ?: state.value.period,
            audience ?: state.value.audience, clearQuery)
    }
    override fun refresh() { if (active) model.refresh() }
    override fun retryBrowse() { if (active) model.retryBrowse() }
    override fun loadMore() { if (active) model.loadMore() }
    override fun browseBackground(selection: BrowseState): BrowseState = model.browseBackground(selection)
}

data class ScopedSourceControls(
    val sources: List<BodySourceInfo> = emptyList(), val selectedSource: String = "*",
    val capabilities: SourceCapabilities = SourceCapabilities(), val feeds: List<SourceFeed> = emptyList(),
    val feedId: String = "", val options: List<String> = emptyList(), val searchFilters: List<SourceFilter> = emptyList(),
    val loading: Boolean = false, val error: String? = null,
)

/** One shared browse journey; every source retains its own cursor and failure. */
internal class ScopedBrowseController(private val repository: UnifiedMangaRepository, private val scope: CoroutineScope) : BrowseUiActions {
    private val mutableState = MutableStateFlow(emptyState())
    override val state: StateFlow<BrowseState> = mutableState.asStateFlow()
    private val mutableControls = MutableStateFlow(ScopedSourceControls())
    val controls: StateFlow<ScopedSourceControls> = mutableControls.asStateFlow()
    var sourceId: String = ALL
        private set
    private var sources = emptyList<BodySourceInfo>()
    private var unavailableReason: String? = null
    private var active = false
    private var disposed = false
    private var generation = 0L
    private var controlsGeneration = 0L
    private var requestJob: Job? = null
    private var controlsJob: Job? = null
    private var controlsOwner: String? = null
    private data class PendingLoad(val force: Boolean, val debounce: Boolean)
    private var pendingLoad: PendingLoad? = null
    private val readyMetadata = mutableSetOf<String>()
    private val feedFilterCache = mutableMapOf<String, List<SourceFilter>>()
    private val snapshots = linkedMapOf<String, BrowseState>()
    private val selections = mutableMapOf<String, BrowseState>()
    private val sourceControls = mutableMapOf<String, ScopedSourceControls>()
    private val optionSelections = mutableMapOf<String, List<String>>()
    private val suspendedSearches = mutableMapOf<String, BrowseState>()
    private val backgrounds = mutableMapOf<String, BrowseState>()
    private val requestSlots = Semaphore(3)

    fun savedSelection(): List<String> = state.value.let { listOf(sourceId, it.query,
        it.searchTarget.name, it.mode.name, it.period.name, it.audience.name,
        controls.value.feedId) + controls.value.options }

    fun restoreSelection(saved: List<String>) {
        if (disposed || active || sources.isNotEmpty()) return
        sourceId = saved.getOrNull(0).orEmpty().ifBlank { ALL }
        val restored = emptyState().copy(query = saved.getOrNull(1).orEmpty(), sourceScope = scopeKey())
        selections[sourceId] = restored
        mutableControls.value = ScopedSourceControls(selectedSource = sourceId,
            feedId = saved.getOrNull(6).orEmpty(), options = saved.drop(7))
        sourceControls[sourceId] = controls.value
        mutableState.value = restored
    }

    fun updateSources(available: List<BodySourceInfo>, unavailableReason: String? = null) {
        if (disposed) return
        val next = available.distinctBy { it.id }
        if (next == sources && unavailableReason == this.unavailableReason) return
        retainCurrent(); cancelRequest(); cancelControls()
        this.unavailableReason = unavailableReason
        val old = sources.associateBy { it.id }
        sources = next
        val invalid = old.keys.filter { id -> next.none { it.id == id && it.revision == old[id]?.revision } }.toSet()
        if (invalid.isNotEmpty()) {
            snapshots.clear(); backgrounds.clear()
            invalid.forEach { id ->
                readyMetadata.removeAll { it.startsWith("$id:") }
                feedFilterCache.keys.filter { it.startsWith("$id:") }.forEach(feedFilterCache::remove)
                if (next.none { it.id == id }) { selections.remove(id); sourceControls.remove(id) }
            }
        }
        if (sourceId != ALL && next.none { it.id == sourceId }) sourceId = ALL
        mutableControls.value = (sourceControls[sourceId] ?: ScopedSourceControls(selectedSource = sourceId)).copy(sources = sources)
        show(selections[sourceId] ?: emptyState(), retain = false)
    }

    fun selectSource(id: String) {
        if (disposed || id == sourceId || id != ALL && sources.none { it.id == id }) return
        retainCurrent(); cancelRequest(); cancelControls()
        sourceControls[sourceId] = controls.value
        sourceId = id
        mutableControls.value = (sourceControls[id] ?: ScopedSourceControls(selectedSource = id)).copy(sources = sources)
        show(selections[id] ?: emptyState(), retain = false)
    }

    fun invalidateSource(id: String) {
        snapshots.keys.filter { it.startsWith("scoped:$id:") || it.startsWith("scoped:$ALL:") }.forEach(snapshots::remove)
        backgrounds.remove(id); backgrounds.remove(ALL)
        readyMetadata.removeAll { it.startsWith("$id:") }
        feedFilterCache.keys.filter { it.startsWith("$id:") }.forEach(feedFilterCache::remove)
        if (sourceId == id || sourceId == ALL) {
            cancelRequest(); cancelControls()
            mutableState.value = state.value.copy(updatedAt = 0)
            if (active) show(selection(state.value), force = true, retain = false)
        }
    }

    fun selectFeed(id: String) {
        val current = controls.value
        val feed = current.feeds.firstOrNull { it.id == id } ?: return
        if (id == current.feedId) return
        retainCurrent(); cancelControls()
        mutableControls.value = current.copy(feedId = id, options = normalizeOptions(feed.filters, optionSelections["$sourceId:$id:false"].orEmpty()))
        show(homeSelection(state.value), retain = false)
    }

    fun selectOption(index: Int, id: String) {
        val current = controls.value
        if (current.loading) return
        val filters = if (state.value.isSearch) current.searchFilters else current.feeds.firstOrNull { it.id == current.feedId }?.filters.orEmpty()
        val filter = filters.getOrNull(index) ?: return
        if (!filter.accepts(id) || filter.normalize(id) == current.options.getOrNull(index)) return
        retainCurrent()
        val values = filters.mapIndexed { i, item -> item.normalize(if (i == index) id else current.options.getOrNull(i)) }
        optionSelections["$sourceId:${current.feedId}:${state.value.isSearch}"] = values
        mutableControls.value = current.copy(options = values)
        show(selection(state.value), retain = false)
    }

    private fun metadataKey(id: String = sourceId): String = id + ":" + sources.firstOrNull { it.id == id }?.revision.orEmpty()
    private fun filterKey(id: String, feed: String): String = metadataKey(id) + ":" + feed
    private fun controlsReady(searching: Boolean): Boolean = sourceId == ALL ||
        metadataKey() in readyMetadata && (searching || controls.value.feedId.isEmpty() ||
            filterKey(sourceId, controls.value.feedId) in feedFilterCache)
    private fun normalizeOptions(filters: List<SourceFilter>, saved: List<String>): List<String> = filters.mapIndexed { index, filter ->
        filter.normalize(saved.getOrNull(index))
    }
    private fun cancelControls() {
        controlsGeneration++; controlsJob?.cancel(); controlsJob = null; controlsOwner = null; pendingLoad = null
        mutableControls.value = controls.value.copy(loading = false)
    }

    /** Resolve only the selected feed. A list request never races its unfinished optionLoader. */
    private fun prepareControls() {
        if (!active || disposed || sourceId == ALL || sources.none { it.id == sourceId }) return
        val searching = state.value.isSearch
        val requestedFeed = controls.value.feedId
        val id = sourceId
        val revision = metadataKey(id)
        val owner = revision + ":" + requestedFeed + ":" + searching
        if (controlsOwner == owner && controlsJob?.isActive == true) return
        controlsGeneration++; controlsJob?.cancel()
        val expected = controlsGeneration
        controlsOwner = owner
        mutableControls.value = controls.value.copy(loading = true, error = null)
        fun current(): Boolean = expected == controlsGeneration && active && !disposed &&
            sourceId == id && metadataKey(id) == revision && controls.value.feedId == requestedFeed && state.value.isSearch == searching
        controlsJob = scope.launch {
            try {
                val old = controls.value
                val metadataReady = revision in readyMetadata
                val capabilities = if (metadataReady) old.capabilities else repository.sourceCapabilities(id)
                val feeds = if (metadataReady) old.feeds else if (capabilities.supports(SourceFeature.DISCOVERY) ||
                    capabilities.supports(SourceFeature.CATEGORIES) || capabilities.supports(SourceFeature.RANKING)) repository.sourceFeeds(id) else emptyList()
                val searchFilters = if (metadataReady) old.searchFilters else if (capabilities.supports(SourceFeature.SEARCH))
                    repository.sourceSearchFilters(id) else emptyList()
                if (!current()) return@launch
                val feed = feeds.firstOrNull { it.id == requestedFeed } ?: feeds.firstOrNull()
                val feedKey = feed?.let { filterKey(id, it.id) }
                val selectedFilters = if (searching || feed == null) emptyList() else
                    feedFilterCache[filterKey(id, feed.id)] ?: if (feed.filters.isNotEmpty()) feed.filters else repository.sourceFeedFilters(id, feed.id)
                if (!current()) return@launch
                // Cache actual resolved choices only after the exact source/feed request survives cancellation.
                if (!searching && feedKey != null) feedFilterCache[feedKey] = selectedFilters
                val resolvedFeeds = feeds.map { item ->
                    val known = feedFilterCache[filterKey(id, item.id)]
                    if (known == null) item else item.copy(filters = known)
                }
                val filters = if (searching) searchFilters else selectedFilters
                val saved = optionSelections["$id:${feed?.id.orEmpty()}:$searching"]
                    ?: old.options.takeIf { old.feedId == feed?.id.orEmpty() }.orEmpty()
                val options = normalizeOptions(filters, saved)
                // Publish metadata and the selected feed atomically; then send its one intended list request.
                retainCurrent()
                mutableControls.value = old.copy(capabilities = capabilities, feeds = resolvedFeeds, searchFilters = searchFilters,
                    feedId = feed?.id.orEmpty(), options = options, loading = false, error = null)
                sourceControls[id] = controls.value
                optionSelections["$id:${feed?.id.orEmpty()}:$searching"] = options
                readyMetadata.add(revision)
                val pending = pendingLoad ?: PendingLoad(false, false)
                pendingLoad = null
                show(selection(state.value), force = pending.force, debounce = pending.debounce, retain = false)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                if (current()) {
                    mutableControls.value = controls.value.copy(loading = false, error = "来源筛选读取失败，可重试")
                    mutableState.value = state.value.copy(loading = false, refreshing = false, loadingMore = false,
                        error = if (state.value.items.isEmpty()) "来源筛选未能读取，请重试。" else state.value.error)
                }
            } finally { if (expected == controlsGeneration) { controlsJob = null; controlsOwner = null } }
        }
    }

    override fun setActive(active: Boolean) {
        if (disposed || this.active == active) return
        this.active = active
        if (!active) { retainCurrent(); cancelRequest(); cancelControls() }
        else show(selection(state.value), retain = false)
    }
    fun dispose() { if (!disposed) { setActive(false); disposed = true; snapshots.clear(); selections.clear(); sourceControls.clear() } }
    override fun setQuery(query: String) {
        if (disposed || query == state.value.query) return
        val wasSearch = state.value.isSearch
        val isSearch = query.isNotBlank()
        if (wasSearch != isSearch) {
            retainCurrent(); cancelControls()
            val current = controls.value
            optionSelections["$sourceId:${current.feedId}:$wasSearch"] = current.options
            val filters = if (isSearch) current.searchFilters else current.feeds.firstOrNull { it.id == current.feedId }?.filters.orEmpty()
            mutableControls.value = current.copy(options = normalizeOptions(filters, optionSelections["$sourceId:${current.feedId}:$isSearch"].orEmpty()))
        }
        show(selection(state.value).copy(query = query), debounce = query.isNotBlank(), retain = wasSearch == isSearch)
    }
    override fun submitSearch() = refresh()
    override fun setSearchTarget(target: SearchTarget) { if (target == SearchTarget.TITLE && target != state.value.searchTarget) show(selection(state.value).copy(searchTarget = target)) }
    override fun toggleSearchTag(tag: MangaTag) = Unit
    override fun retrySearchTags() = Unit
    override fun clearSearch() { suspendedSearches.remove(sourceId); setQuery("") }
    override fun suspendBrowseSearch() {
        if (state.value.isSearch) { suspendedSearches[sourceId] = selection(state.value); setQuery("") }
    }
    override fun resumeBrowseSearch() { suspendedSearches.remove(sourceId)?.let { setQuery(it.query) } }
    override fun selectDiscovery(mode: DiscoveryMode, period: RankPeriod?, audience: RankAudience?, clearQuery: Boolean) {
        if (clearQuery) clearSearch()
    }
    override fun refresh() { if (active && !disposed && requestJob?.isActive != true) show(selection(state.value), force = true) }
    override fun retryBrowse() {
        val failed = state.value.searchSources.values.filter { it.error != null }.associate { it.sourceId to (it.failedPage ?: 0) }
        if (failed.isEmpty()) refresh() else startLoad(state.value, failed)
    }
    fun retrySource(id: String) {
        val lane = state.value.searchSources[id] ?: return
        if (lane.error != null && !lane.loading && requestJob?.isActive != true) startLoad(state.value, mapOf(id to (lane.failedPage ?: 0)))
    }
    override fun loadMore() {
        val current = state.value
        if (!active || disposed || requestJob?.isActive == true || current.loading || current.refreshing || current.loadingMore) return
        val pages = current.searchSources.values.filter { it.error == null && it.hasMore }.associate { it.sourceId to (it.nextPage ?: 0) }
        if (pages.isNotEmpty()) startLoad(current, pages)
    }
    override fun browseBackground(selection: BrowseState): BrowseState = if (!selection.isSearch) selection else backgrounds[sourceId] ?: homeSelection(selection)
    private fun selection(value: BrowseState) = BrowseState(query = value.query, searchTarget = SearchTarget.TITLE,
        mode = DiscoveryMode.RECOMMENDED, availableTags = emptyList(), loading = false, sourceScope = scopeKey())
    private fun homeSelection(value: BrowseState) = selection(value).copy(query = "")
    private fun scopeKey() = "scoped:" + sourceId + ":" + controls.value.feedId + ":" + controls.value.options.joinToString("\u001f")
    private fun key(value: BrowseState) = scopeKey() + ":" + sources.joinToString("|") { it.id + ":" + it.revision } + ":" + value.cacheKey
    private fun selectedSources() = if (sourceId == ALL) sources else sources.filter { it.id == sourceId }

    private fun show(value: BrowseState, debounce: Boolean = false, force: Boolean = false, retain: Boolean = true) {
        if (disposed) return
        if (retain) retainCurrent()
        cancelRequest()
        val configured = selection(value)
        if (sources.isEmpty()) { mutableState.value = configured.copy(error = unavailableReason ?: "本分区暂无已启用的来源。"); return }
        val cached = snapshots[key(configured)]
        mutableState.value = cached?.copy(loading = false, loadingMore = false, refreshing = false, sourceScope = scopeKey()) ?: configured
        if (!active) return
        if (!controlsReady(configured.isSearch)) {
            pendingLoad = PendingLoad(force, debounce)
            mutableState.value = state.value.copy(loading = state.value.items.isEmpty(), refreshing = state.value.items.isNotEmpty())
            prepareControls()
            return
        }
        if (!force && cached != null && cached.error == null && cached.searchSources.values.none { it.error != null } &&
            wallTimeMillis() - cached.updatedAt in 0L until 60_000L) return
        startLoad(state.value, selectedSources().associate { it.id to 0 }, reset = true, debounce = debounce, force = force)
    }

    private fun startLoad(value: BrowseState, pages: Map<String, Int>, reset: Boolean = false, debounce: Boolean = false, force: Boolean = false) {
        if (!active || disposed || pages.isEmpty()) return
        if (!controlsReady(value.isSearch)) { show(selection(value), force = force, debounce = debounce, retain = false); return }
        cancelRequest()
        val expected = generation; val owner = scopeKey(); val selectedKey = key(value)
        val selectedSource = sourceId; val selectedControls = controls.value
        val infos = selectedSources().associateBy { it.id }
        val requested = pages.filterKeys { it in infos }
        if (requested.isEmpty()) return
        val rows = (if (reset) emptyList() else value.items).toMutableList()
        val lanes = (if (reset) emptyMap() else value.searchSources).toMutableMap()
        requested.forEach { (id, _) -> lanes[id] = (lanes[id] ?: FederatedSearchSource(id, infos.getValue(id).name)).copy(loading = true, error = null) }
        fun publish() {
            if (expected != generation || !active || owner != scopeKey()) return
            val loading = lanes.values.any { it.loading }
            val successes = lanes.values.any { !it.loading && it.error == null }
            mutableState.value = selection(value).copy(items = rows.distinctBy { it.id },
                page = if (reset) 0 else value.page + 1, loading = loading && rows.isEmpty(),
                refreshing = loading && reset && rows.isNotEmpty(), loadingMore = loading && !reset,
                hasMore = lanes.values.any { it.error == null && it.hasMore },
                error = if (!loading && !successes && rows.isEmpty()) "当前来源暂时无法读取，请查看来源状态并重试。" else null,
                failedPage = if (!loading && lanes.values.any { it.error != null }) 0 else null,
                updatedAt = wallTimeMillis(), searchSources = lanes.toMap(),
                sourceNextPages = lanes.mapNotNull { (id, lane) -> lane.nextPage?.let { id to it } }.toMap(),
                sourceMatches = if (value.isSearch) rows.associate { it.id to listOf(it) } else emptyMap())
        }
        // Keep visible content while an explicit refresh replaces source lanes independently.
        if (reset) rows.addAll(value.items)
        publish()
        requestJob = scope.launch {
            try {
                if (debounce) delay(450)
                supervisorScope {
                    requested.map { (id, page) -> async {
                        requestSlots.withPermit {
                            try {
                                val result = withTimeout(25_000L) {
                                    if (selectedSource == ALL) {
                                        val feature = if (value.isSearch) SourceFeature.SEARCH else SourceFeature.DISCOVERY
                                        if (!repository.sourceCapabilities(id).supports(feature)) return@withTimeout null
                                    }
                                    if (value.isSearch) repository.sourceFilteredSearch(id, value.query.trim(), page,
                                        if (selectedSource == ALL) emptyList() else selectedControls.searchFilters.mapIndexed { index, filter ->
                                            filter.normalize(selectedControls.options.getOrNull(index)) }, forceRefresh = force)
                                    else repository.browseSource(id, page, selectedControls.feedId.takeIf { selectedSource != ALL && it.isNotEmpty() }
                                        ?.let { SourceBrowseRequest(it, page, selectedControls.options) })
                                }
                                currentCoroutineContext().ensureActive()
                                if (expected != generation || !active || owner != scopeKey()) return@withPermit
                                if (result == null) {
                                    lanes.remove(id)
                                    rows.removeAll { it.sourceId == id }
                                    publish()
                                    return@withPermit
                                }
                                val next = result.nextPage ?: page + 1
                                require(!result.hasMore || next > page) { "来源返回的下一页无效。" }
                                if (reset || page == 0) rows.removeAll { it.sourceId == id }
                                rows.addAll(result.items)
                                lanes[id] = FederatedSearchSource(id, infos.getValue(id).name, hasMore = result.hasMore, nextPage = next.takeIf { result.hasMore })
                            } catch (failure: Exception) {
                                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                                if (expected != generation || !active || owner != scopeKey()) return@withPermit
                                val message = repository.sourceHealth.value[id]?.message?.takeIf { it.isNotBlank() } ?: "来源读取失败，请重试"
                                lanes[id] = FederatedSearchSource(id, infos.getValue(id).name, error = message, failedPage = page)
                            }
                            publish()
                        }
                    } }.awaitAll()
                }
                if (expected == generation && owner == scopeKey()) { snapshots[selectedKey] = state.value; retainCurrent() }
            } finally { if (expected == generation) requestJob = null }
        }
    }
    private fun retainCurrent() {
        selections[sourceId] = selection(state.value)
        sourceControls[sourceId] = controls.value
        val current = state.value.copy(loading = false, loadingMore = false, refreshing = false,
            searchSources = state.value.searchSources.mapValues { (_, lane) -> lane.copy(loading = false) })
        if (current.items.isEmpty() && current.updatedAt <= 0 && current.error == null) return
        if (!current.isSearch) backgrounds[sourceId] = current
        snapshots[key(current)] = current
        while (snapshots.size > 16) snapshots.remove(snapshots.keys.first())
    }
    private fun cancelRequest() {
        generation++; requestJob?.cancel(); requestJob = null
        mutableState.value = state.value.copy(loading = false, refreshing = false, loadingMore = false)
    }
    private companion object {
        const val ALL = "*"
        fun emptyState() = BrowseState(availableTags = emptyList(), loading = false)
    }
}
