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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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

/** One application owner retains source browsing when a screen or title preview is dismissed. */
class AnimeSourceBrowseController(
    private val context: PlatformContext,
    private val scope: CoroutineScope,
    private val sources: AnimeSourceRepository,
) {
    private val routing = DownloadRoutingStore.get(context)
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<AnimeSourceBrowseState> = mutableState.asStateFlow()
    private val mutableLatest = MutableStateFlow(AnimeSourceLatestState())
    val latest: StateFlow<AnimeSourceLatestState> = mutableLatest.asStateFlow()

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
    private var latestRoute = routeFor(AGE_SOURCE)
    private var latestRequested = false
    private var latestGeneration = 0L
    private var latestRequest: Deferred<List<AnimeSourceTitle>>? = null

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
                if (current == null || current != previous) {
                    switchSource(current ?: preferredSource(available))
                }
            }
        }
        scope.launch(Dispatchers.Main.immediate) {
            routing.state.collect {
                val route = routeFor(AGE_SOURCE)
                if (route != latestRoute) {
                    latestRoute = route
                    latestGeneration++
                    latestRequest?.cancel()
                    latestRequest = null
                    mutableLatest.value = AnimeSourceLatestState()
                    if (latestRequested) latestPage(force = true)
                }
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
        loadHome(selection)
        if ("latest" in selection.source.capabilities || mutableState.value.categoryKey != null) loadPage(1, force = true)
    }

    fun loadMore() = command { loadNextPage() }

    fun retryPage() = command {
        val selection = selection() ?: return@command
        val before = mutableState.value
        if (before.homeError != null) loadHome(selection)
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

    /** The home summary and AGE first page share this request/cache, regardless of source selection. */
    fun refreshLatest(force: Boolean = false) = command { latestPage(force) }

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

    private fun preferredSource(available: List<AnimeBrowseSource>) =
        available.firstOrNull { it.id == AGE_SOURCE } ?: available.firstOrNull()

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

    private fun loadHome(selection: Selection) {
        if ("homeSections" !in selection.source.capabilities) return
        val request = ++homeGeneration
        homeJob?.cancel()
        mutableState.value = mutableState.value.copy(homeLoading = true, homeError = null)
        homeJob = scope.launch(Dispatchers.Main.immediate) {
            try {
                requireRoute(selection)
                val sections = sources.extensions.readHomeSections(selection.source.id, selection.source.revision)
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
                    if (selection.source.id == AGE_SOURCE && page == 1) latestPage(force).await()
                    else {
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

    private fun latestPage(force: Boolean): Deferred<List<AnimeSourceTitle>> {
        latestRequested = true
        val route = routeFor(AGE_SOURCE)
        // A route change may be observed here before the routing collector resumes.
        if (route != latestRoute) {
            latestRoute = route
            latestGeneration++
            latestRequest?.cancel()
            latestRequest = null
            mutableLatest.value = AnimeSourceLatestState()
        }
        latestRequest?.takeIf { it.isActive }?.let { return it }
        val cached = mutableLatest.value
        val policy = contentCachePreferences(context)
        val age = cached.fetchedAt?.let { wallTimeMillis() - it }
        if (!force && policy.enabled && cached.error == null && age != null && age >= 0 &&
            age < policy.freshMillis(ContentCacheKind.DISCOVERY)) return CompletableDeferred(cached.items)
        val request = ++latestGeneration
        mutableLatest.value = mutableLatest.value.copy(loading = true, error = null)
        val deferred = scope.async(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
            fun ownsLatest() = request == latestGeneration && route == routeFor(AGE_SOURCE)
            try {
                if (routing.sourceSnapshot(AGE_SOURCE, DownloadCategory.ANIME) != route)
                    throw CancellationException("Anime latest source route changed")
                val provider = sources.provider(AGE_SOURCE)?.takeIf { it.supportsLatest }
                    ?: throw LanercCapabilityException("最近更新来源暂不可用。")
                val revision = provider.revision
                val cards = provider.latest(1)
                currentCoroutineContext().ensureActive()
                if (!ownsLatest()) throw CancellationException("Anime latest source route changed")
                checkCards(AnimeBrowseSource(provider.id, provider.name, revision, true, setOf("latest")), cards)
                mutableLatest.value = AnimeSourceLatestState(cards, fetchedAt = wallTimeMillis())
                cards
            } catch (cancelled: CancellationException) {
                if (ownsLatest()) mutableLatest.value = mutableLatest.value.copy(loading = false)
                throw cancelled
            } catch (failure: Exception) {
                if (ownsLatest()) mutableLatest.value = mutableLatest.value.copy(loading = false, error = sourceError(failure))
                throw failure
            } finally {
                if (request == latestGeneration) latestRequest = null
            }
        }
        latestRequest = deferred
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
        const val AGE_SOURCE = "anime-age"
        val BROWSE_CAPABILITIES = setOf("homeSections", "categories", "searchFiltered")
    }
}
