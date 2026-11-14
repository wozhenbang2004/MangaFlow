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
import dev.mangaflow.data.sources.BodySourceInfo
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

/**
 * UI-scoped state for an explicitly supplied set of installed sources.
 * Calls only the repository's existing opaque browse/title-search operations.
 */
internal class ScopedBrowseController(
    private val repository: UnifiedMangaRepository,
    private val scope: CoroutineScope,
) : BrowseUiActions {
    private data class SelectionKey(val source: String, val revision: String, val selection: String)

    private val mutableState = MutableStateFlow(emptyState())
    override val state: StateFlow<BrowseState> = mutableState.asStateFlow()
    var sourceId: String = ""
        private set
    private var sources = emptyList<BodySourceInfo>()
    private var unavailableReason: String? = null
    private var active = false
    private var disposed = false
    private var generation = 0L
    private var requestJob: Job? = null
    private val snapshots = linkedMapOf<SelectionKey, BrowseState>()
    private val nextPages = mutableMapOf<SelectionKey, Int>()
    private val selections = mutableMapOf<String, BrowseState>()
    private val suspendedSearches = mutableMapOf<String, BrowseState>()

    /** Save only UI selection; original manga data remains in the source/repository stores. */
    fun savedSelection(): List<String> = state.value.let {
        listOf(sourceId, it.query, it.searchTarget.name, it.mode.name, it.period.name, it.audience.name)
    }

    fun restoreSelection(saved: List<String>) {
        if (disposed || active || sources.isNotEmpty()) return
        sourceId = saved.getOrNull(0).orEmpty()
        val restored = emptyState().copy(
            query = saved.getOrNull(1).orEmpty(),
            // The opaque source contract provides only catalogue browsing and title search.
            // Older UI selections must not restore an unreachable ordinary-source operation.
            searchTarget = SearchTarget.TITLE,
            mode = DiscoveryMode.RECOMMENDED,
            period = RankPeriod.entries.firstOrNull { it.name == saved.getOrNull(4) } ?: RankPeriod.WEEK,
            audience = RankAudience.entries.firstOrNull { it.name == saved.getOrNull(5) } ?: RankAudience.MALE,
            sourceScope = scopeKey(),
        )
        if (sourceId.isNotBlank()) selections[sourceId] = restored
        mutableState.value = restored
    }

    fun updateSources(available: List<BodySourceInfo>, unavailableReason: String? = null) {
        if (disposed) return
        val next = available.filter { it.id.isNotBlank() }.distinctBy { it.id }
        val reason = unavailableReason?.takeIf { it.isNotBlank() }
        if (next == sources && this.unavailableReason == reason) return
        this.unavailableReason = reason
        retainCurrent()
        cancelRequest()
        val old = sources.associateBy { it.id }
        sources = next
        val current = next.associateBy { it.id }
        val invalid = old.keys.filter { id ->
            current[id] == null || current[id]?.revision != old[id]?.revision
        }.toSet()
        snapshots.keys.filter { it.source in invalid }.forEach {
            snapshots.remove(it)
            nextPages.remove(it)
        }
        invalid.forEach { selections.remove(it); suspendedSearches.remove(it) }
        val chosen = sourceId.takeIf { id -> next.any { it.id == id } } ?: next.firstOrNull()?.id.orEmpty()
        sourceId = chosen
        val selection = selections[chosen] ?: emptyState()
        show(selection, retain = false)
    }

    fun selectSource(id: String) {
        if (disposed || id == sourceId || sources.none { it.id == id }) return
        retainCurrent()
        cancelRequest()
        sourceId = id
        show(selections[id] ?: emptyState(), retain = false)
    }

    override fun setActive(active: Boolean) {
        if (disposed || this.active == active) return
        this.active = active
        if (!active) {
            retainCurrent()
            cancelRequest()
        } else show(selection(state.value), retain = false)
    }

    fun dispose() {
        if (disposed) return
        active = false
        cancelRequest()
        disposed = true
        snapshots.clear()
        nextPages.clear()
        selections.clear()
        suspendedSearches.clear()
        mutableState.value = emptyState()
    }

    override fun setQuery(query: String) {
        if (disposed || query == state.value.query) return
        show(selection(state.value).copy(query = query), debounce = query.isNotBlank())
    }

    override fun submitSearch() = refresh()

    override fun setSearchTarget(target: SearchTarget) {
        if (disposed || target != SearchTarget.TITLE || target == state.value.searchTarget) return
        show(selection(state.value).copy(searchTarget = target))
    }

    override fun toggleSearchTag(tag: MangaTag) {
        if (disposed || tag.path.isBlank() || tag.name.isBlank()) return
        val current = state.value
        val tags = if (current.selectedTags.any { it.path == tag.path })
            current.selectedTags.filterNot { it.path == tag.path } else current.selectedTags + tag
        show(selection(current).copy(selectedTags = tags))
    }

    override fun retrySearchTags() {
        if (disposed) return
        mutableState.value = state.value.copy(availableTags = emptyList(), tagsLoading = false,
            tagsError = "当前来源未提供标签检索。")
    }

    override fun clearSearch() {
        if (disposed) return
        suspendedSearches.remove(sourceId)
        show(homeSelection(state.value))
    }

    override fun suspendBrowseSearch() {
        if (disposed) return
        val current = state.value
        if (current.isSearch) suspendedSearches[sourceId] = selection(current)
        show(homeSelection(current))
    }

    override fun resumeBrowseSearch() {
        if (disposed) return
        suspendedSearches.remove(sourceId)?.let { show(it) }
    }

    override fun selectDiscovery(
        mode: DiscoveryMode, period: RankPeriod?, audience: RankAudience?, clearQuery: Boolean,
    ) {
        if (disposed) return
        val current = state.value
        // This source contract exposes a catalogue, not ordinary-source rankings/updates.
        // Old or delayed navigation requests return to that real catalogue operation.
        val next = (if (clearQuery) homeSelection(current) else selection(current)).copy(
            mode = DiscoveryMode.RECOMMENDED, period = period ?: current.period,
            audience = audience ?: current.audience)
        if (selection(current) != next) show(next)
    }

    override fun refresh() {
        if (!active || disposed || requestJob?.isActive == true) return
        show(selection(state.value), force = true)
    }

    override fun retryBrowse() {
        if (!active || disposed || requestJob?.isActive == true) return
        val current = state.value
        val failed = current.failedPage
        if (failed == null) show(selection(current), force = true)
        else startLoad(current, failed, forceRefresh = true)
    }

    override fun loadMore() {
        val current = state.value
        if (!active || disposed || requestJob?.isActive == true || current.loading ||
            current.refreshing || current.loadingMore || !current.hasMore || current.error != null) return
        val page = nextPages[key(current)] ?: current.page + 1
        if (page > current.page) startLoad(current, page)
    }

    override fun browseBackground(selection: BrowseState): BrowseState {
        if (!selection.isSearch) return selection
        val home = homeSelection(selection).copy(sourceScope = scopeKey())
        return snapshots[key(home)] ?: home
    }

    private fun selection(value: BrowseState) = BrowseState(
        query = value.query, searchTarget = value.searchTarget, selectedTags = value.selectedTags,
        mode = DiscoveryMode.RECOMMENDED, period = value.period, audience = value.audience,
        availableTags = emptyList(), loading = false, sourceScope = scopeKey(),
    )

    private fun homeSelection(value: BrowseState) = BrowseState(
        mode = DiscoveryMode.RECOMMENDED, period = value.period, audience = value.audience,
        availableTags = emptyList(), loading = false, sourceScope = scopeKey(),
    )

    private fun scopeKey() = "scoped:" + sourceId
    private fun key(value: BrowseState) = SelectionKey(sourceId,
        sources.firstOrNull { it.id == sourceId }?.revision.orEmpty(), value.cacheKey)

    private fun unsupported(value: BrowseState): String? = when {
        sourceId.isEmpty() || sources.none { it.id == sourceId } ->
            unavailableReason ?: "本分区暂无已启用的来源。"
        value.searchTarget == SearchTarget.TAG || value.selectedTags.isNotEmpty() -> "当前来源未提供标签检索。"
        value.searchTarget == SearchTarget.AUTHOR -> "当前来源未提供作者检索。"
        value.query.trim().length > 120 -> "请输入不超过 120 个字的标题。"
        !value.isSearch && value.mode == DiscoveryMode.RANKING -> "当前来源未提供热榜。"
        !value.isSearch && value.mode == DiscoveryMode.LATEST -> "当前来源未提供更新列表。"
        else -> null
    }

    private fun show(value: BrowseState, debounce: Boolean = false, force: Boolean = false, retain: Boolean = true) {
        if (disposed) return
        if (retain) retainCurrent()
        cancelRequest()
        val configured = selection(value)
        val failure = unsupported(configured)
        if (failure != null) {
            mutableState.value = configured.copy(error = failure,
                tagsError = failure.takeIf { configured.searchTarget == SearchTarget.TAG })
            return
        }
        val selectedKey = key(configured)
        val cached = snapshots.remove(selectedKey)?.also { snapshots[selectedKey] = it }
        mutableState.value = cached?.copy(
            query = configured.query, searchTarget = configured.searchTarget,
            selectedTags = configured.selectedTags, sourceScope = scopeKey(),
            loading = false, loadingMore = false, refreshing = false,
        ) ?: configured
        if (!active) return
        val fresh = cached != null && cached.error == null && cached.updatedAt > 0 &&
            (wallTimeMillis() - cached.updatedAt in 0L until 60_000L || cached.page > 0)
        // A previous failure is not a fresh result. Returning from source/account settings
        // can retry once on activation, while the repository keeps real source cooldowns.
        if (!force && fresh) return
        val resumePage = if (force) 0 else cached?.failedPage?.takeIf { it > 0 } ?: 0
        startLoad(state.value, resumePage, forceRefresh = force || cached?.error != null, debounce = debounce)
    }

    private fun startLoad(value: BrowseState, page: Int, forceRefresh: Boolean = false, debounce: Boolean = false) {
        if (!active || disposed || unsupported(value) != null) return
        cancelRequest()
        val requested = generation
        val requestedSource = sourceId
        val selectedKey = key(value)
        val sourceName = sources.firstOrNull { it.id == requestedSource }?.name.orEmpty()
        val search = value.isFederatedSearch
        mutableState.value = value.copy(
            loading = page == 0 && value.items.isEmpty(),
            refreshing = page == 0 && value.items.isNotEmpty(), loadingMore = page > 0,
            error = null, failedPage = null,
            searchSources = if (search) mapOf(requestedSource to FederatedSearchSource(
                requestedSource, sourceName, loading = true)) else emptyMap(),
        )
        requestJob = scope.launch {
            try {
                if (debounce) delay(450)
                val result = withTimeout(20_000L) {
                    if (value.isSearch) repository.searchSource(requestedSource, value.query.trim(), page, forceRefresh)
                    else repository.browseSource(requestedSource, page)
                }
                currentCoroutineContext().ensureActive()
                if (requested != generation || !active || sourceId != requestedSource) return@launch
                val providedNext = result.nextPage
                val next = providedNext?.takeIf { it > page } ?: (page + 1)
                if (result.hasMore && providedNext != null && providedNext <= page)
                    throw SourceException("来源返回的下一页无效，请重试或刷新。")
                val items = (if (page == 0) result.items else value.items + result.items).distinctBy { it.id }
                if (result.hasMore) nextPages[selectedKey] = next else nextPages.remove(selectedKey)
                mutableState.value = state.value.copy(
                    items = items, page = page, hasMore = result.hasMore,
                    loading = false, loadingMore = false, refreshing = false, error = null, failedPage = null,
                    updatedAt = if (page == 0 || value.updatedAt <= 0) wallTimeMillis() else value.updatedAt,
                    sourceMatches = if (search) items.associate { it.id to listOf(it) } else emptyMap(),
                    sourceChoiceIds = emptySet(),
                    searchSources = if (search) mapOf(requestedSource to FederatedSearchSource(
                        requestedSource, sourceName, hasMore = result.hasMore,
                        nextPage = next.takeIf { result.hasMore })) else emptyMap(),
                )
                retainCurrent()
            } catch (failure: Exception) {
                if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                if (requested != generation || !active || sourceId != requestedSource) return@launch
                val message = if (failure is TimeoutCancellationException) "请求超时，请重试或更换来源。"
                    else failure.message?.take(300) ?: "当前来源暂时无法读取，请重试。"
                mutableState.value = state.value.copy(
                    loading = false, loadingMore = false, refreshing = false, error = message, failedPage = page,
                    searchSources = if (search) mapOf(requestedSource to FederatedSearchSource(
                        requestedSource, sourceName, error = message, failedPage = page)) else emptyMap(),
                )
                retainCurrent()
            } finally {
                if (requested == generation) requestJob = null
            }
        }
    }

    private fun retainCurrent() {
        if (sourceId.isEmpty()) return
        val current = state.value.copy(loading = false, refreshing = false, loadingMore = false,
            searchSources = state.value.searchSources.mapValues { (_, source) -> source.copy(loading = false) })
        selections[sourceId] = selection(current)
        if (current.updatedAt <= 0 && current.items.isEmpty() && current.error == null) return
        val selectedKey = key(current)
        snapshots.remove(selectedKey)
        snapshots[selectedKey] = current
        while (snapshots.size > 12) {
            val oldest = snapshots.keys.first()
            snapshots.remove(oldest)
            nextPages.remove(oldest)
        }
    }

    private fun cancelRequest() {
        generation++
        requestJob?.cancel()
        requestJob = null
        mutableState.value = state.value.copy(loading = false, refreshing = false, loadingMore = false,
            searchSources = state.value.searchSources.mapValues { (_, source) -> source.copy(loading = false) })
    }

    private companion object {
        fun emptyState() = BrowseState(availableTags = emptyList(), loading = false)
    }
}
