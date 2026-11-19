package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.sources.lanerc.LanercBrowseSection
import dev.mangaflow.anime.sources.lanerc.LanercCapabilityException
import dev.mangaflow.anime.sources.lanerc.LanercCategory
import dev.mangaflow.anime.sources.lanerc.LanercSourceKind
import dev.mangaflow.anime.sources.lanerc.readCategories
import dev.mangaflow.anime.sources.lanerc.readFiltered
import dev.mangaflow.anime.sources.lanerc.readHomeSections
import dev.mangaflow.anime.sources.lanerc.readRelated
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.experience.contentCachePreferences
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

data class AnimeBrowseSource(
    val id: String,
    val name: String,
    val revision: String,
    val videoSource: Boolean,
    val capabilities: Set<String>,
)

data class AnimeSourceBrowseState(
    val sources: List<AnimeBrowseSource> = emptyList(),
    val sourceId: String? = null,
    val sourceRevision: String? = null,
    val categoryKey: String? = null,
    val filters: Map<String, String> = emptyMap(),
    val home: List<LanercBrowseSection> = emptyList(),
    val categories: List<LanercCategory> = emptyList(),
    val items: List<AnimeSourceTitle> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val homeLoading: Boolean = false,
    val categoriesLoading: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val homeError: String? = null,
    val categoryError: String? = null,
    val error: String? = null,
    val moreError: String? = null,
    val selectedTitle: AnimeSourceTitle? = null,
    val related: List<AnimeSourceTitle> = emptyList(),
    val relatedLoading: Boolean = false,
    val relatedError: String? = null,
)

data class AnimeSourceLatestState(
    val items: List<AnimeSourceTitle> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val fetchedAt: Long? = null,
)

data class AnimeSourceHomeFeed(
    val source: AnimeBrowseSource,
    val sections: List<LanercBrowseSection> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val fetchedAt: Long? = null,
    val queued: Boolean = false,
)

/** One application owner retains source browsing when a screen or title preview is dismissed. */
class AnimeSourceBrowseController(
    private val context: PlatformContext,
    private val scope: CoroutineScope,
    private val sources: AnimeSourceRepository,
) {
    private val routing = DownloadRoutingStore.get(context)
    private val homeCache = AnimeSourceHomeCache(context)
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<AnimeSourceBrowseState> = mutableState.asStateFlow()
    // Retain the existing observation API; source-specific sections are the authoritative home state.
    private val mutableLatest = MutableStateFlow(AnimeSourceLatestState())
    val latest: StateFlow<AnimeSourceLatestState> = mutableLatest.asStateFlow()
    private val mutableHomeFeeds = MutableStateFlow<Map<String, AnimeSourceHomeFeed>>(emptyMap())
    val homeFeeds: StateFlow<Map<String, AnimeSourceHomeFeed>> = mutableHomeFeeds.asStateFlow()

    private var activated = false
    private var sourceGeneration = 0L
    private var homeGeneration = 0L
    private var categoryGeneration = 0L
    private var pageGeneration = 0L
    private var relatedGeneration = 0L
    private var homeJob: Job? = null
    private var categoryJob: Job? = null
    private var pageJob: Job? = null
    private var relatedJob: Job? = null
    private var browseRoute = mutableState.value.sourceId?.let(::routeFor)
    private var homeFeedsRequested = false
    private val feedRoutes = mutableMapOf<String, DownloadRouteSnapshot?>()
    private val feedGenerations = mutableMapOf<String, Long>()
    private val feedRequests = mutableMapOf<String, HomeFeedRequest>()
    private val nativeHomeSlots = Semaphore(2)
    // QuickJS has two shared slots. Let background Home use at most one of them.
    private val scriptHomeSlots = Semaphore(1)

    private class HomeAdmission(var foreground: Boolean, var force: Boolean) {
        var waiting = false
    }

    private data class HomeFeedRequest(
        val admission: HomeAdmission,
        val job: Deferred<Result<List<LanercBrowseSection>>>,
    )

    private data class Selection(
        val source: AnimeBrowseSource,
        val generation: Long,
        val route: DownloadRouteSnapshot?,
    )

    init {
        scope.launch(Dispatchers.Main.immediate) {
            sources.extensions.ready.await()
            sources.extensions.state.collect {
                val available = availableSources()
                val before = mutableState.value
                val previous = before.sources.firstOrNull { it.id == before.sourceId }
                val current = available.firstOrNull { it.id == before.sourceId }
                mutableState.value = before.copy(sources = available)
                val changed = reconcileHomeFeeds()
                if (homeFeedsRequested) changed.forEach { source -> homeFeed(source) }
                if (current == null || current != previous) {
                    switchSource(current ?: preferredSource(available))
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            routing.state.collect {
                val changed = reconcileHomeFeeds()
                if (homeFeedsRequested) changed.forEach { source -> homeFeed(source) }
                val selected = selectedSource()
                if (selected != null && routeFor(selected.id) != browseRoute) {
                    switchSource(selected, keepQuery = true)
                }
            }
        }
    }

    fun activate(sourceId: String? = null) = command {
        val available = availableSources()
        val selected = sourceId?.let { id -> available.firstOrNull { it.id == id } }
            ?: available.firstOrNull { it.id == mutableState.value.sourceId }
            ?: preferredSource(available)
        val wasActive = activated
        activated = true
        mutableState.value = mutableState.value.copy(sources = available)
        if (!wasActive || selected?.id != mutableState.value.sourceId ||
            selected?.revision != mutableState.value.sourceRevision) switchSource(selected)
    }

    fun selectSource(id: String) = command {
        val available = availableSources()
        val selected = available.firstOrNull { it.id == id } ?: return@command
        val changed = !activated || selected.id != mutableState.value.sourceId ||
            selected.revision != mutableState.value.sourceRevision
        activated = true
        mutableState.value = mutableState.value.copy(sources = available)
        if (changed) switchSource(selected)
    }

    fun selectCategory(key: String?) = command { changeCategory(key) }

    fun setFilter(key: String, value: String?) = command {
        val before = mutableState.value
        val category = before.categories.firstOrNull { it.key == before.categoryKey } ?: return@command
        val filter = category.filters.firstOrNull { it.key == key } ?: return@command
        if (value != null && filter.options.none { it.value == value }) return@command
        val filters = if (value == null) before.filters - key else before.filters + (key to value)
        if (filters == before.filters) return@command
        resetPage()
        mutableState.value = mutableState.value.copy(filters = filters)
        loadPage(1)
    }

    /** Refresh replaces the first page only after success; failed refreshes retain useful content. */
    fun refresh() = command {
        val selection = selection() ?: return@command
        loadCategories(selection)
        loadHome(selection, force = true)
        if ("latest" in selection.source.capabilities || mutableState.value.categoryKey != null) loadPage(1, force = true)
    }

    fun loadMore() = command { loadNextPage() }

    fun retryPage() = command {
        val selection = selection() ?: return@command
        val before = mutableState.value
        if (before.homeError != null) loadHome(selection, force = true)
        if (before.categoryError != null) loadCategories(selection)
        when {
            before.error != null -> loadPage(1, force = true)
            before.moreError != null -> loadNextPage()
        }
    }

    fun openTitle(title: AnimeSourceTitle) = command {
        val selection = selection() ?: return@command
        if (!current(selection) || title.sourceId != selection.source.id ||
            title.sourceRevision != selection.source.revision || title.id.isBlank()) return@command
        clearTitle()
        mutableState.value = mutableState.value.copy(selectedTitle = title)
        loadRelated(selection, title)
    }

    fun closeTitle() = command { clearTitle() }

    fun retryRelated() = command {
        val selection = selection() ?: return@command
        val title = mutableState.value.selectedTitle ?: return@command
        loadRelated(selection, title)
    }

    /** Each available source publishes independently; opening Home does not choose a playback source. */
    fun refreshLatest(force: Boolean = false) = command {
        homeFeedsRequested = true
        reconcileHomeFeeds()
        homeSources().forEach { homeFeed(it, force) }
    }

    fun refreshHomeSource(id: String) = refreshHomeSources(setOf(id))

    fun refreshHomeSources(ids: Set<String>) = command {
        val changed = reconcileHomeFeeds()
        if (homeFeedsRequested) changed.filter { it.id !in ids }.forEach { homeFeed(it) }
        homeSources().filter { it.id in ids }.forEach { homeFeed(it, force = true, foreground = ids.size == 1) }
    }

    fun prioritizeHomeSource(id: String) = command {
        homeSources().firstOrNull { it.id == id }?.let { homeFeed(it, foreground = true) }
    }

    private fun command(action: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { action() }
    }

    private fun initialState(): AnimeSourceBrowseState {
        val available = availableSources()
        val first = preferredSource(available)
        return AnimeSourceBrowseState(sources = available, sourceId = first?.id, sourceRevision = first?.revision)
    }

    private fun availableSources(): List<AnimeBrowseSource> =
        sources.providers.filter { it.supportsLatest }.map {
            AnimeBrowseSource(it.id, it.name, it.revision, true, setOf("latest"))
        } + sources.extensions.state.value.filter {
            it.installed && it.enabled && it.revision != null &&
                it.kind in setOf(LanercSourceKind.VIDEO, LanercSourceKind.BROWSE) &&
                it.capabilities.any { capability -> capability in BROWSE_CAPABILITIES }
        }.map {
            AnimeBrowseSource(it.id, it.name, requireNotNull(it.revision), it.kind == LanercSourceKind.VIDEO, it.capabilities)
        }

    private fun preferredSource(available: List<AnimeBrowseSource>) = available.firstOrNull()

    private fun selectedSource() = mutableState.value.let { value ->
        value.sources.firstOrNull { it.id == value.sourceId && it.revision == value.sourceRevision }
    }

    private fun selection(): Selection? = selectedSource()?.let {
        Selection(it, sourceGeneration, routeFor(it.id))
    }

    // Invalid configured routes stay failed; observing them must not crash the discovery owner.
    private fun routeFor(id: String): DownloadRouteSnapshot? = runCatching { routing.sourceSnapshot(id, DownloadCategory.ANIME) }.getOrNull()

    private fun requireRoute(selection: Selection) {
        if (routing.sourceSnapshot(selection.source.id, DownloadCategory.ANIME) != selection.route)
            throw CancellationException("Anime source route changed")
    }

    private fun current(selection: Selection): Boolean =
        sourceGeneration == selection.generation && mutableState.value.sourceId == selection.source.id &&
            mutableState.value.sourceRevision == selection.source.revision &&
            browseRoute == selection.route &&
            routeFor(selection.source.id) == selection.route &&
            availableSources().any { it == selection.source }

    private fun switchSource(source: AnimeBrowseSource?, keepQuery: Boolean = false) {
        val before = mutableState.value
        sourceGeneration++
        homeGeneration++
        categoryGeneration++
        pageGeneration++
        relatedGeneration++
        homeJob?.cancel()
        categoryJob?.cancel()
        pageJob?.cancel()
        relatedJob?.cancel()
        browseRoute = source?.id?.let(::routeFor)
        mutableState.value = AnimeSourceBrowseState(
            sources = before.sources, sourceId = source?.id, sourceRevision = source?.revision,
            categoryKey = if (keepQuery) before.categoryKey else null,
            filters = if (keepQuery) before.filters else emptyMap(),
        )
        if (!activated) return
        val selection = selection() ?: return
        loadCategories(selection)
        loadHome(selection)
        if ("latest" in selection.source.capabilities || mutableState.value.categoryKey != null) loadPage(1)
    }

    private fun changeCategory(key: String?) {
        val selection = selection() ?: return
        val before = mutableState.value
        if (before.categoryKey == key) return
        if (key != null && ("searchFiltered" !in selection.source.capabilities || before.categories.none { it.key == key })) return
        resetPage()
        mutableState.value = mutableState.value.copy(categoryKey = key, filters = emptyMap())
        if (key != null || "latest" in selection.source.capabilities) loadPage(1)
        else if (mutableState.value.home.isEmpty() && !mutableState.value.homeLoading) loadHome(selection)
    }

    private fun resetPage() {
        pageGeneration++
        pageJob?.cancel()
        clearTitle()
        mutableState.value = mutableState.value.copy(items = emptyList(), page = 0, hasMore = false,
            loading = false, loadingMore = false, error = null, moreError = null)
    }

    private fun loadHome(selection: Selection, force: Boolean = false) {
        if ("homeSections" !in selection.source.capabilities) return
        val request = ++homeGeneration
        homeJob?.cancel()
        mutableState.value = mutableState.value.copy(homeLoading = true, homeError = null)
        homeJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                requireRoute(selection)
                val sections = awaitHomeFeed(selection.source, force) { cached ->
                    if (current(selection) && request == homeGeneration) {
                        mutableState.value = mutableState.value.copy(home = cached)
                    }
                }
                currentCoroutineContext().ensureActive()
                if (current(selection) && request == homeGeneration) {
                    sections.forEach { checkCards(selection.source, it.items) }
                    mutableState.value = mutableState.value.copy(home = sections, homeLoading = false, homeError = null)
                }
            } catch (cancelled: CancellationException) {
                if (current(selection) && request == homeGeneration)
                    mutableState.value = mutableState.value.copy(homeLoading = false)
                throw cancelled
            } catch (failure: Exception) {
                if (current(selection) && request == homeGeneration)
                    mutableState.value = mutableState.value.copy(homeLoading = false, homeError = sourceError(failure))
            }
        }
    }

    private fun loadCategories(selection: Selection) {
        if ("categories" !in selection.source.capabilities) return
        val request = ++categoryGeneration
        categoryJob?.cancel()
        mutableState.value = mutableState.value.copy(categoriesLoading = true, categoryError = null)
        categoryJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                requireRoute(selection)
                val categories = sources.extensions.readCategories(selection.source.id, selection.source.revision)
                currentCoroutineContext().ensureActive()
                if (current(selection) && request == categoryGeneration) {
                    val before = mutableState.value
                    val selected = before.categoryKey
                    val category = categories.firstOrNull { it.key == selected }
                    val filters = if (category == null) before.filters else before.filters.filter { (key, value) ->
                        category.filters.any { filter -> filter.key == key && filter.options.any { it.value == value } }
                    }
                    val filtersChanged = filters != before.filters
                    if (filtersChanged) resetPage()
                    mutableState.value = mutableState.value.copy(categories = categories, filters = filters,
                        categoriesLoading = false, categoryError = if (filtersChanged)
                            "来源筛选已变化，已移除失效选项并重新读取。" else null)
                    if (selected != null && category == null) {
                        changeCategory(null)
                        mutableState.value = mutableState.value.copy(categoryError = "来源分类已变化，已移除失效分类及其筛选。")
                    } else if (filtersChanged) loadPage(1)
                    if (mutableState.value.categoryKey == null && "homeSections" !in selection.source.capabilities &&
                        "searchFiltered" in selection.source.capabilities) categories.firstOrNull()?.let { changeCategory(it.key) }
                }
            } catch (cancelled: CancellationException) {
                if (current(selection) && request == categoryGeneration)
                    mutableState.value = mutableState.value.copy(categoriesLoading = false)
                throw cancelled
            } catch (failure: Exception) {
                if (current(selection) && request == categoryGeneration)
                    mutableState.value = mutableState.value.copy(categoriesLoading = false, categoryError = sourceError(failure))
            }
        }
    }

    private fun loadNextPage() {
        val before = mutableState.value
        if (!before.loading && !before.loadingMore && before.hasMore && before.page in 1 until Int.MAX_VALUE)
            loadPage(before.page + 1)
    }

    private fun loadPage(page: Int, force: Boolean = false) {
        val selection = selection() ?: return
        if (!current(selection)) return
        val before = mutableState.value
        val category = before.categoryKey
        val filters = before.filters.toMap()
        val native = "latest" in selection.source.capabilities
        if (!native && (category == null || "searchFiltered" !in selection.source.capabilities)) return
        val append = page > 1
        val request = ++pageGeneration
        pageJob?.cancel()
        mutableState.value = before.copy(loading = !append, loadingMore = append,
            error = if (append) before.error else null, moreError = null)
        pageJob = scope.launch(Dispatchers.Main.immediate) {
            fun ownsPage() = current(selection) && request == pageGeneration &&
                mutableState.value.categoryKey == category && mutableState.value.filters == filters
            try {
                requireRoute(selection)
                val cards = if (native) {
                    if (page == 1) {
                        awaitHomeFeed(selection.source, force) { cached ->
                            if (ownsPage() && before.page <= 1) {
                                val initial = cached.single().items
                                mutableState.value = mutableState.value.copy(
                                    items = initial, page = 1, hasMore = initial.isNotEmpty())
                            }
                        }.single().items
                    } else {
                        val provider = sources.provider(selection.source.id)
                            ?.takeIf { it.supportsLatest && it.revision == selection.source.revision }
                            ?: throw LanercCapabilityException("所选来源已改变，请重新选择来源。")
                        provider.latest(page)
                    }
                } else {
                    val encoded = JSONObject().also { json -> filters.forEach { (key, value) -> json.put(key, value) } }.toString()
                    sources.extensions.readFiltered(selection.source.id, requireNotNull(category), encoded, page, selection.source.revision)
                }
                currentCoroutineContext().ensureActive()
                if (ownsPage()) {
                    checkCards(selection.source, cards)
                    val existing = if (append) mutableState.value.items else emptyList()
                    val merged = (existing + cards).distinctBy { it.id }
                    mutableState.value = mutableState.value.copy(items = merged, page = page,
                        // These source contracts expose a page list, not an authoritative total.
                        // A changing catalogue can repeat IDs without exhausting later pages.
                        hasMore = cards.isNotEmpty(),
                        loading = false, loadingMore = false, error = null, moreError = null)
                }
            } catch (cancelled: CancellationException) {
                if (ownsPage()) mutableState.value = mutableState.value.copy(loading = false, loadingMore = false)
                throw cancelled
            } catch (failure: Exception) {
                if (ownsPage()) mutableState.value = mutableState.value.copy(loading = false, loadingMore = false,
                    error = if (append) mutableState.value.error else sourceError(failure),
                    moreError = if (append) sourceError(failure) else null)
            }
        }
    }

    private fun clearTitle() {
        relatedGeneration++
        relatedJob?.cancel()
        mutableState.value = mutableState.value.copy(selectedTitle = null, related = emptyList(),
            relatedLoading = false, relatedError = null)
    }

    private fun loadRelated(selection: Selection, title: AnimeSourceTitle) {
        if ("related" !in selection.source.capabilities) return
        val request = ++relatedGeneration
        relatedJob?.cancel()
        mutableState.value = mutableState.value.copy(relatedLoading = true, relatedError = null)
        relatedJob = scope.launch(Dispatchers.Main.immediate) {
            fun ownsTitle() = current(selection) && request == relatedGeneration && mutableState.value.selectedTitle == title
            try {
                requireRoute(selection)
                val related = sources.extensions.readRelated(selection.source.id, title.id, selection.source.revision)
                currentCoroutineContext().ensureActive()
                if (ownsTitle()) {
                    checkCards(selection.source, related)
                    mutableState.value = mutableState.value.copy(related = related.filter { it.id != title.id },
                        relatedLoading = false, relatedError = null)
                }
            } catch (cancelled: CancellationException) {
                if (ownsTitle()) mutableState.value = mutableState.value.copy(relatedLoading = false)
                throw cancelled
            } catch (failure: Exception) {
                if (ownsTitle()) mutableState.value = mutableState.value.copy(relatedLoading = false, relatedError = sourceError(failure))
            }
        }
    }

    private fun homeSources(): List<AnimeBrowseSource> = availableSources().filter {
        "latest" in it.capabilities || "homeSections" in it.capabilities
    }.distinctBy { it.id }

    /** A changed source/revision/route invalidates only that source's observation and request. */
    private fun reconcileHomeFeeds(): List<AnimeBrowseSource> {
        val available = homeSources()
        val ids = available.mapTo(mutableSetOf()) { it.id }
        val before = mutableHomeFeeds.value
        (before.keys + feedRequests.keys).filter { it !in ids }.forEach { id ->
            feedGenerations[id] = (feedGenerations[id] ?: 0L) + 1L
            feedRequests.remove(id)?.job?.cancel()
            feedRoutes.remove(id)
        }
        val changed = mutableListOf<AnimeBrowseSource>()
        val next = available.associate { source ->
            val route = routeFor(source.id)
            val previous = before[source.id]
            if (previous?.source != source || feedRoutes[source.id] != route) {
                feedGenerations[source.id] = (feedGenerations[source.id] ?: 0L) + 1L
                feedRequests.remove(source.id)?.job?.cancel()
                feedRoutes[source.id] = route
                changed += source
                source.id to AnimeSourceHomeFeed(source)
            } else source.id to requireNotNull(previous)
        }
        mutableHomeFeeds.value = next
        publishLatest()
        return changed
    }

    private fun publishFeed(value: AnimeSourceHomeFeed) {
        mutableHomeFeeds.value = mutableHomeFeeds.value + (value.source.id to value)
        publishLatest()
    }

    private fun publishLatest() {
        val feeds = mutableHomeFeeds.value.values.filter { it.source.videoSource }
        val complete = feeds.isNotEmpty() && feeds.all { it.fetchedAt != null && !it.loading && it.error == null }
        mutableLatest.value = AnimeSourceLatestState(
            items = feeds.flatMap { feed -> feed.sections.flatMap { it.items } }
                .distinctBy { it.sourceId to it.id },
            loading = feeds.any { it.loading },
            error = feeds.firstOrNull { it.error != null }?.let { "${it.source.name}：${it.error}" },
            fetchedAt = if (complete) feeds.mapNotNull { it.fetchedAt }.minOrNull() else null,
        )
    }

    private fun isFresh(feed: AnimeSourceHomeFeed): Boolean {
        val age = feed.fetchedAt?.let { wallTimeMillis() - it } ?: return false
        return feed.error == null && age in 0 until contentCachePreferences(context).freshMillis(ContentCacheKind.DISCOVERY)
    }

    private suspend fun awaitHomeFeed(
        source: AnimeBrowseSource,
        force: Boolean,
        onSnapshot: (List<LanercBrowseSection>) -> Unit,
    ): List<LanercBrowseSection> = coroutineScope {
        val request = homeFeed(source, force, foreground = true)
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            homeFeeds.collect { feeds ->
                val feed = feeds[source.id]
                if (feed != null && feed.source == source && feed.fetchedAt != null) onSnapshot(feed.sections)
            }
        }
        try {
            request.await().getOrThrow()
        } finally {
            observer.cancel()
        }
    }

    /** Home and the selected source browser share the same first-page request and cache. */
    private fun homeFeed(source: AnimeBrowseSource, force: Boolean = false,
        foreground: Boolean = false): Deferred<Result<List<LanercBrowseSection>>> {
        val id = source.id
        val route = routeFor(id)
        val before = mutableHomeFeeds.value[id]
        if (before?.source != source || feedRoutes[id] != route) {
            feedGenerations[id] = (feedGenerations[id] ?: 0L) + 1L
            feedRequests.remove(id)?.job?.cancel()
            feedRoutes[id] = route
            publishFeed(AnimeSourceHomeFeed(source))
        }
        val running = feedRequests[id]?.takeIf { it.job.isActive }
        if (running != null && (!foreground || !running.admission.waiting)) {
            if (foreground) running.admission.foreground = true
            if (force) running.admission.force = true
            return running.job
        }
        val cached = requireNotNull(mutableHomeFeeds.value[id])
        if (running == null && !force && isFresh(cached)) return CompletableDeferred(Result.success(cached.sections))
        val request = (feedGenerations[id] ?: 0L) + 1L
        feedGenerations[id] = request
        // Re-admit a queued source immediately. The old request loses authority before cancellation.
        running?.job?.cancel()
        val admission = HomeAdmission(foreground, force || running?.admission?.force == true)
        val cacheGeneration = homeCache.generation
        publishFeed(cached.copy(loading = true, error = null, queued = false))
        val deferred = scope.async(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            fun ownsFeed() = request == feedGenerations[id] && route == feedRoutes[id] &&
                route == routeFor(id) && homeSources().any { it == source }
            try {
                val currentRoute = routing.sourceSnapshot(id, DownloadCategory.ANIME)
                if (currentRoute != route)
                    throw CancellationException("Anime home source route changed")
                if (cached.fetchedAt == null) {
                    val stored = homeCache.read(source, currentRoute)
                    currentCoroutineContext().ensureActive()
                    if (!ownsFeed()) throw CancellationException("Anime home source changed")
                    if (stored != null && cacheGeneration == homeCache.generation) {
                        val refresh = admission.force || !isFresh(stored)
                        publishFeed(stored.copy(loading = refresh))
                        if (!refresh) return@async Result.success(stored.sections)
                    }
                }
                val native = "latest" in source.capabilities
                suspend fun loadSections(): List<LanercBrowseSection> {
                    if (!native) return sources.extensions.readHomeSections(id, source.revision)
                    val provider = sources.provider(id)?.takeIf { it.supportsLatest && it.revision == source.revision }
                        ?: throw LanercCapabilityException("所选来源已改变，请刷新来源内容。")
                    return listOf(LanercBrowseSection("latest", "最近更新", provider.latest(1)))
                }
                val slots = if (native) nativeHomeSlots else scriptHomeSlots
                val sections = if (admission.foreground) loadSections() else {
                    admission.waiting = true
                    if (slots.availablePermits == 0) mutableHomeFeeds.value[id]?.let { publishFeed(it.copy(queued = true)) }
                    slots.withPermit {
                        admission.waiting = false
                        if (!ownsFeed()) throw CancellationException("Anime home source changed")
                        mutableHomeFeeds.value[id]?.takeIf { it.queued }?.let { publishFeed(it.copy(queued = false)) }
                        coroutineScope {
                            val remote = async { loadSections() }
                            val withinBudget = withTimeoutOrNull(BACKGROUND_HOME_TIMEOUT_MILLIS) { remote.await() }
                            withinBudget ?: if (admission.foreground) remote.await()
                            else throw LanercCapabilityException("来源更新等待过久，可单独重试。")
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!ownsFeed()) throw CancellationException("Anime home source changed")
                sections.forEach { checkCards(source, it.items) }
                val observed = AnimeSourceHomeFeed(source, sections, fetchedAt = wallTimeMillis())
                publishFeed(observed)
                homeCache.write(observed, currentRoute, cacheGeneration)
                Result.success(sections)
            } catch (cancelled: CancellationException) {
                if (ownsFeed()) mutableHomeFeeds.value[id]?.let { publishFeed(it.copy(loading = false, queued = false)) }
                throw cancelled
            } catch (failure: Exception) {
                if (ownsFeed()) mutableHomeFeeds.value[id]?.let {
                    publishFeed(it.copy(loading = false, error = sourceError(failure), queued = false))
                }
                // A failed source is data for its observer, not cancellation of sibling feeds.
                Result.failure(failure)
            } finally {
                if (request == feedGenerations[id]) feedRequests.remove(id)
            }
        }
        feedRequests[id] = HomeFeedRequest(admission, deferred)
        deferred.start()
        return deferred
    }

    private fun checkCards(source: AnimeBrowseSource, cards: List<AnimeSourceTitle>) {
        if (cards.any { it.sourceId != source.id || it.sourceRevision != source.revision || it.id.isBlank() })
            throw LanercCapabilityException("来源条目的身份或版本已变化，请刷新后重新选择。")
    }

    private fun sourceError(failure: Exception): String = when (failure) {
        is AnimeSourceException, is LanercCapabilityException, is DownloadRouteException ->
            failure.message ?: "来源内容暂时无法读取，请重试。"
        else -> "来源内容未能完成读取或解析，请重试或更新来源。"
    }

    private companion object {
        val BROWSE_CAPABILITIES = setOf("homeSections", "categories", "searchFiltered")
        const val BACKGROUND_HOME_TIMEOUT_MILLIS = 15_000L
    }
}
