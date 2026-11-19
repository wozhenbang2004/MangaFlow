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
import dev.mangaflow.data.SourceStage
import dev.mangaflow.data.sourceFailureSummary
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.*
import dev.mangaflow.data.Manga
import dev.mangaflow.data.normalizedBodySourceTitle
import dev.mangaflow.json.JSONArray
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

    fun savedSelection(): List<String> = state.value.let { listOf(SAVED_SELECTION_V2, sourceId, it.query,
        it.searchTarget.name, it.mode.name, it.period.name, it.audience.name,
        controls.value.feedId, JSONArray(it.selectedTags.map { tag -> listOf(tag.name, tag.path) }).toString()) +
        controls.value.options }

    fun restoreSelection(saved: List<String>) {
        if (disposed || active || sources.isNotEmpty()) return
        // Legacy lists have no tag field: every entry after the feed belongs to its options.
        val versioned = saved.firstOrNull() == SAVED_SELECTION_V2
        val values = if (versioned) saved.drop(1) else saved
        sourceId = values.getOrNull(0).orEmpty().ifBlank { ALL }
        val target = values.getOrNull(2)?.let { runCatching { SearchTarget.valueOf(it) }.getOrNull() }
            ?.takeUnless { it == SearchTarget.TRANSLATION_GROUP } ?: SearchTarget.TITLE
        val tags = if (versioned) restoreTags(values.getOrNull(7)) else emptyList()
        mutableControls.value = ScopedSourceControls(selectedSource = sourceId,
            feedId = values.getOrNull(6).orEmpty(), options = values.drop(if (versioned) 8 else 7))
        val restored = emptyState().copy(query = values.getOrNull(1).orEmpty(),
            searchTarget = target, selectedTags = tags,
            mode = values.getOrNull(3)?.let { runCatching { DiscoveryMode.valueOf(it) }.getOrNull() }
                ?: DiscoveryMode.RECOMMENDED,
            period = values.getOrNull(4)?.let { runCatching { RankPeriod.valueOf(it) }.getOrNull() }
                ?: RankPeriod.WEEK,
            audience = values.getOrNull(5)?.let { runCatching { RankAudience.valueOf(it) }.getOrNull() }
                ?: RankAudience.MALE,
            sourceScope = scopeKey())
        selections[sourceId] = restored
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

    fun selectSource(id: String, keepSearch: Boolean = false) {
        if (disposed || id == sourceId || id != ALL && sources.none { it.id == id }) return
        val previous = state.value
        retainCurrent(); cancelRequest(); cancelControls()
        sourceControls[sourceId] = controls.value
        sourceId = id
        mutableControls.value = (sourceControls[id] ?: ScopedSourceControls(selectedSource = id)).copy(sources = sources)
        val destination = selections[id] ?: emptyState().copy(mode = previous.mode, period = previous.period,
            audience = previous.audience)
        // A source change from search narrows the current request. Native feed/filters
        // and results still belong to the destination source and its revision.
        val request = if (keepSearch) destination.copy(query = previous.query,
            searchTarget = previous.searchTarget, selectedTags = previous.selectedTags) else destination
        if (request.isSearch != destination.isSearch) {
            val current = controls.value
            val filters = if (request.isSearch) current.searchFilters
                else current.feeds.firstOrNull { it.id == current.feedId }?.filters.orEmpty()
            val options = optionSelections["$id:${current.feedId}:${request.isSearch}"].orEmpty()
            mutableControls.value = current.copy(options = normalizeOptions(filters, options))
        }
        show(request, retain = false)
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
        val mode = when (feed.kind) {
            SourceFeedKind.RANKING -> DiscoveryMode.RANKING
            SourceFeedKind.LATEST -> DiscoveryMode.LATEST
            else -> DiscoveryMode.RECOMMENDED
        }
        show(homeSelection(state.value).copy(mode = mode), retain = false)
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
                val desired = when (state.value.mode) {
                    DiscoveryMode.RANKING -> SourceFeedKind.RANKING
                    DiscoveryMode.LATEST -> SourceFeedKind.LATEST
                    else -> null
                }
                val feed = feeds.firstOrNull { it.id == requestedFeed && (desired == null || it.kind == desired) }
                    ?: feeds.firstOrNull { it.kind == desired }?.takeIf { desired != null }
                    ?: feeds.firstOrNull()
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
        val isSearch = query.isNotBlank() || state.value.searchTarget != SearchTarget.TITLE ||
            state.value.selectedTags.isNotEmpty()
        if (wasSearch != isSearch) {
            retainCurrent(); cancelControls()
            val current = controls.value
            optionSelections["$sourceId:${current.feedId}:$wasSearch"] = current.options
            val filters = if (isSearch) current.searchFilters else current.feeds.firstOrNull { it.id == current.feedId }?.filters.orEmpty()
            mutableControls.value = current.copy(options = normalizeOptions(filters, optionSelections["$sourceId:${current.feedId}:$isSearch"].orEmpty()))
        }
        show(selection(state.value).copy(query = query), debounce = query.isNotBlank(), retain = wasSearch == isSearch)
    }
    override fun submitSearch() {
        val current = state.value
        if (current.searchTarget == SearchTarget.TAG && current.query.isNotBlank()) {
            val label = current.query.trim().take(120)
            if (label.isNotBlank()) toggleSearchTag(MangaTag(label, "label:" + normalizedBodySourceTitle(label)))
        } else refresh()
    }
    override fun setSearchTarget(target: SearchTarget) {
        if (target == SearchTarget.TRANSLATION_GROUP) {
            cancelRequest()
            mutableState.value = state.value.copy(error = "汉化组搜索仅在普通漫画区的拷贝来源可用。")
            return
        }
        if (target != state.value.searchTarget) show(selection(state.value).copy(searchTarget = target), retain = false)
    }
    override fun toggleSearchTag(tag: MangaTag) {
        if (tag.name.isBlank() || tag.name.length > 120) return
        val current = state.value
        val chosen = if (current.selectedTags.any { it.path == tag.path })
            current.selectedTags.filterNot { it.path == tag.path }
        else (current.selectedTags + tag).take(12)
        show(selection(current).copy(query = "", searchTarget = SearchTarget.TAG, selectedTags = chosen), retain = false)
    }
    override fun retrySearchTags() = Unit
    override fun clearSearch() {
        suspendedSearches.remove(sourceId)
        show(homeSelection(state.value).copy(searchTarget = SearchTarget.TITLE, selectedTags = emptyList()), retain = false)
    }
    override fun suspendBrowseSearch() {
        if (state.value.isSearch) {
            suspendedSearches[sourceId] = selection(state.value)
            show(homeSelection(state.value).copy(searchTarget = SearchTarget.TITLE,
                selectedTags = emptyList()), retain = false)
        }
    }
    override fun resumeBrowseSearch() { suspendedSearches.remove(sourceId)?.let { show(it, retain = false) } }
    override fun selectDiscovery(mode: DiscoveryMode, period: RankPeriod?, audience: RankAudience?, clearQuery: Boolean) {
        if (disposed || !active) return
        val before = state.value
        val next = before.copy(query = if (clearQuery) "" else before.query,
            mode = mode, period = period ?: before.period, audience = audience ?: before.audience)
        if (before.mode == next.mode && before.period == next.period && before.audience == next.audience &&
            before.query == next.query) return
        retainCurrent()
        if (clearQuery) suspendedSearches.remove(sourceId)
        if (sourceId != ALL && !next.isSearch) {
            val wanted = when (mode) {
                DiscoveryMode.RANKING -> SourceFeedKind.RANKING
                DiscoveryMode.LATEST -> SourceFeedKind.LATEST
                else -> null
            }
            val chosen = controls.value.feeds.firstOrNull { it.kind == wanted }
            if (wanted != null && chosen != null) mutableControls.value = controls.value.copy(
                feedId = chosen.id, options = normalizeOptions(chosen.filters,
                    optionSelections["$sourceId:${chosen.id}:false"].orEmpty()))
        }
        show(selection(next), retain = false)
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
    private fun selection(value: BrowseState) = BrowseState(query = value.query, searchTarget = value.searchTarget,
        selectedTags = value.selectedTags, mode = value.mode, period = value.period, audience = value.audience,
        availableTags = value.availableTags, loading = false, sourceScope = scopeKey())
    private fun homeSelection(value: BrowseState) = selection(value).copy(query = "")
    private fun scopeKey() = "scoped:" + sourceId + ":" + controls.value.feedId + ":" + controls.value.options.joinToString("\u001f")
    private fun key(value: BrowseState) = scopeKey() + ":" + sources.joinToString("|") { it.id + ":" + it.revision } + ":" + value.cacheKey
    private fun selectedSources() = if (sourceId == ALL) sources else sources.filter { it.id == sourceId }

    /** Rankings are independent source-native lists. Never mix their score scales or cursors. */
    private fun nativeRankingOptions(feed: SourceFeed, period: RankPeriod, remembered: List<String>): List<String>? {
        val aliases = when (period) {
            RankPeriod.DAY -> listOf("H24", "t", "day", "d")
            RankPeriod.WEEK -> listOf("D7", "w", "week")
            RankPeriod.MONTH -> listOf("D30", "m", "month")
            RankPeriod.TOTAL -> listOf("a", "all", "total")
        }
        val normalized = mutableListOf<String>()
        feed.filters.forEachIndexed { index, filter ->
            val choice = when (filter.id) {
                "period" -> filter.choices.firstOrNull { it.id in aliases }?.id ?: return null
                "sort" -> filter.choices.firstOrNull { it.id == "mv" }?.id
                    ?: remembered.getOrNull(index)
                else -> remembered.getOrNull(index)
            }
            normalized += filter.normalize(choice)
        }
        return normalized
    }

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
            val distinct = rows.distinctBy { it.id }
            val visible = if (!value.isSearch && value.mode == DiscoveryMode.RANKING)
                distinct.sortedWith(compareBy { item -> sources.indexOfFirst { it.id == item.sourceId }.let {
                    index -> if (index < 0) Int.MAX_VALUE else index
                } }) else distinct
            val observedTags = (value.availableTags + rows.flatMap { it.tags }.filter {
                it.length in 1..48
            }.map { name -> MangaTag(name, "label:" + normalizedBodySourceTitle(name)) })
                .distinctBy { normalizedBodySourceTitle(it.name) }.take(160)
            mutableState.value = selection(value).copy(items = visible, availableTags = observedTags,
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
                                    val required = when {
                                        value.isSearch -> SourceFeature.SEARCH
                                        value.mode == DiscoveryMode.RANKING -> SourceFeature.RANKING
                                        else -> SourceFeature.DISCOVERY
                                    }
                                    if (!repository.sourceCapabilities(id).supports(required)) return@withTimeout null
                                    if (value.isSearch) {
                                        val phrase = if (value.searchTarget == SearchTarget.TAG)
                                            value.selectedTags.firstOrNull()?.name.orEmpty().ifBlank { value.query.trim() }
                                        else value.query.trim()
                                        if (phrase.isBlank()) return@withTimeout null
                                        val found = if (value.searchTarget == SearchTarget.TITLE &&
                                            value.selectedTags.isEmpty())
                                            repository.sourceFilteredSearch(id, phrase, page,
                                                if (selectedSource == ALL) emptyList() else selectedControls.searchFilters.mapIndexed { index, filter ->
                                                    filter.normalize(selectedControls.options.getOrNull(index)) },
                                                forceRefresh = force)
                                        else repository.sourceTargetSearch(id, phrase, value.searchTarget, page)
                                        if (value.selectedTags.isEmpty()) found
                                        else {
                                            val names = value.selectedTags.map { normalizedBodySourceTitle(it.name) }.toSet()
                                            found.copy(items = found.items.filter { manga ->
                                                val actual = (manga.tags + manga.tagPaths.keys)
                                                    .map(::normalizedBodySourceTitle).toSet()
                                                names.all { it in actual }
                                            })
                                        }
                                    } else {
                                        val request = when (value.mode) {
                                            DiscoveryMode.RECOMMENDED ->
                                                selectedControls.feedId.takeIf { selectedSource != ALL && it.isNotEmpty() }
                                                    ?.let { SourceBrowseRequest(it, page, selectedControls.options) }
                                            DiscoveryMode.RANKING, DiscoveryMode.LATEST -> {
                                                val kind = if (value.mode == DiscoveryMode.RANKING)
                                                    SourceFeedKind.RANKING else SourceFeedKind.LATEST
                                                val feeds = if (selectedSource != ALL && selectedControls.feeds.isNotEmpty())
                                                    selectedControls.feeds else repository.sourceFeeds(id)
                                                val feed = (if (selectedSource != ALL) feeds.firstOrNull {
                                                    it.id == selectedControls.feedId && it.kind == kind
                                                } else null) ?: feeds.firstOrNull { it.kind == kind }
                                                    ?: return@withTimeout null
                                                val remembered = if (selectedSource != ALL && selectedControls.feedId == feed.id)
                                                    selectedControls.options else emptyList()
                                                val options = if (kind == SourceFeedKind.RANKING)
                                                    nativeRankingOptions(feed, value.period, remembered) ?: return@withTimeout null
                                                else feed.filters.mapIndexed { index, filter -> filter.normalize(remembered.getOrNull(index)) }
                                                SourceBrowseRequest(feed.id, page, options)
                                            }
                                        }
                                        repository.browseSource(id, page, request)
                                    }
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
                                val stage = if (value.isSearch) SourceStage.SEARCH else SourceStage.CATALOGUE
                                val message = sourceFailureSummary(stage, failure).message
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
        const val SAVED_SELECTION_V2 = "scoped-browse-selection:v2"
        fun restoreTags(encoded: String?): List<MangaTag> = runCatching {
            val value = encoded?.takeIf { it.length <= 8192 } ?: return@runCatching emptyList()
            val tags = JSONArray(value)
            (0 until minOf(tags.length(), 12)).mapNotNull { index ->
                val entry = tags.optJSONArray(index) ?: return@mapNotNull null
                val name = entry.opt(0) as? String ?: return@mapNotNull null
                val path = entry.opt(1) as? String ?: return@mapNotNull null
                if (name.isBlank() || name.length > 120 || name.any { it.code < 0x20 || it.code in 0x7f..0x9f })
                    return@mapNotNull null
                val label = normalizedBodySourceTitle(name)
                if (label.isBlank() || path != "label:$label") return@mapNotNull null
                MangaTag(name, path)
            }.distinctBy(MangaTag::path)
        }.getOrDefault(emptyList())
        fun emptyState() = BrowseState(availableTags = emptyList(), loading = false)
    }
}
