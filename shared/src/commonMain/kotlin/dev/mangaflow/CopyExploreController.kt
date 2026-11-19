package dev.mangaflow

import dev.mangaflow.data.CopyCatalogSelection
import dev.mangaflow.data.CopyFilterDirectory
import dev.mangaflow.data.CopyMangaRepository
import dev.mangaflow.data.CopySearchTags
import dev.mangaflow.data.CopyTopic
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaTag
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.foregroundRequest
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

enum class CopyExploreSection { CATALOG, TOPICS }

data class CopyExploreState(
    val section: CopyExploreSection = CopyExploreSection.CATALOG,
    val themes: List<MangaTag> = CopySearchTags,
    val directory: CopyFilterDirectory = CopyFilterDirectory(),
    val directoryLoading: Boolean = false,
    val directoryError: String? = null,
    val themeError: String? = null,
    val directoryUpdatedAt: Long = 0,
    val selection: CopyCatalogSelection = CopyCatalogSelection(),
    val topic: CopyTopic? = null,
    val topics: List<CopyTopic> = emptyList(),
    val items: List<Manga> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val failedPage: Int? = null,
    val updatedAt: Long = 0,
) {
    val contentKey: String get() = when {
        section == CopyExploreSection.TOPICS -> topic?.let { "copy-topic-v1:${it.type}:${it.path}" } ?: "copy-topics-v1"
        else -> "copy-catalog-v1:" + listOf(selection.theme?.path.orEmpty(), selection.scope?.path.orEmpty(),
            selection.ordering, selection.original?.path.orEmpty()).joinToString("|") { "${it.length}:$it" }
    }
}

/** The Copy directory owns its native order and cursors, separate from federated keyword search. */
class CopyExploreController(
    private val repository: CopyMangaRepository,
    private val scope: CoroutineScope,
    private val enabled: () -> Boolean,
) {
    private val _state = MutableStateFlow(CopyExploreState())
    val state = _state.asStateFlow()
    private val retained = LinkedHashMap<String, CopyExploreState>()
    private var request = 0L
    private var directoryRequest = 0L
    private var job: Job? = null
    private var directoryJob: Job? = null
    private var route = DownloadRoutingStore.sourceRevision("copy")
    private var directoryAttemptedAt = 0L
    private var topicSelection = CopyExploreState(section = CopyExploreSection.TOPICS)
    private var catalogSelection = CopyExploreState()

    fun start() {
        updateRoute()
        ensureDirectory()
        if (job?.isActive == true) return
        val current = _state.value
        if (!fresh(current.updatedAt)) load(0, replaceRange = true)
    }

    fun ensureDirectory() {
        updateRoute()
        if (directoryJob?.isActive == true) return
        if (fresh(directoryAttemptedAt)) return
        refreshDirectory()
    }

    fun refreshDirectory() {
        updateRoute()
        directoryJob?.cancel()
        val token = ++directoryRequest
        val capturedRoute = route
        directoryAttemptedAt = wallTimeMillis()
        _state.update { it.copy(directoryLoading = true, directoryError = null, themeError = null) }
        directoryJob = scope.launch {
            try {
                requireEnabled()
                val results = supervisorScope {
                    val themes = async { directoryResult { repository.themeDirectory() } }
                    val filters = async { directoryResult { repository.filterDirectory() } }
                    themes.await() to filters.await()
                }
                currentCoroutineContext().ensureActive()
                if (token != directoryRequest) return@launch
                if (capturedRoute != DownloadRoutingStore.sourceRevision("copy") || !enabled()) {
                    _state.update { it.copy(directoryLoading = false, directoryError = "来源设置已变化，请刷新分类。",
                        themeError = "来源设置已变化，请刷新标签。") }
                    return@launch
                }
                val themes = results.first.getOrNull()
                val filters = results.second.getOrNull()
                _state.update { old ->
                    // Names and identifiers from a successful response supersede the bundled snapshot.
                    val counts = themes?.associateBy(MangaTag::path).orEmpty()
                    val entries = (old.themes + filters?.themes.orEmpty() + themes.orEmpty())
                        .associateBy(MangaTag::path).values.map { tag -> counts[tag.path] ?: tag }
                    val failures = listOfNotNull(results.first.exceptionOrNull()?.message,
                        results.second.exceptionOrNull()?.message).distinct()
                    old.copy(themes = entries, directory = filters ?: old.directory,
                        directoryLoading = false, directoryError = failures.takeIf { it.isNotEmpty() }?.joinToString("；"),
                        themeError = results.first.exceptionOrNull()?.message,
                        directoryUpdatedAt = if (themes != null || filters != null) wallTimeMillis() else old.directoryUpdatedAt)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (token == directoryRequest) _state.update { it.copy(directoryLoading = false,
                    directoryError = failure.message ?: "分类暂时无法读取，请重试。",
                    themeError = failure.message ?: "标签暂时无法读取，请重试。") }
            }
        }
    }

    private suspend fun <T> directoryResult(block: suspend () -> T): Result<T> = try {
        Result.success(foregroundRequest("拷贝分类") { block() })
    } catch (cancel: CancellationException) { throw cancel }
      catch (failure: Exception) { Result.failure(failure) }

    fun selectSection(section: CopyExploreSection) {
        val old = _state.value
        if (old.section == section) return
        if (old.section == CopyExploreSection.CATALOG) catalogSelection = CopyExploreState(selection = old.selection)
        else topicSelection = CopyExploreState(section = CopyExploreSection.TOPICS, topic = old.topic)
        show(if (section == CopyExploreSection.CATALOG) catalogSelection else topicSelection)
    }

    fun selectTheme(theme: MangaTag?) = changeCatalog { it.copy(theme = theme) }
    fun applyScopeAndOrdering(scope: MangaTag?, ordering: String) = changeCatalog {
        it.copy(scope = scope, ordering = ordering)
    }
    fun resetFilters() = changeCatalog { CopyCatalogSelection() }

    fun openOriginal(original: MangaTag) {
        if (original.path.isBlank()) return
        show(CopyExploreState(selection = CopyCatalogSelection(original = original)))
    }

    private fun changeCatalog(change: (CopyCatalogSelection) -> CopyCatalogSelection) {
        val old = _state.value
        val selected = change(old.selection)
        val next = CopyExploreState(section = CopyExploreSection.CATALOG, selection = selected)
        if (old.contentKey != next.contentKey) show(next)
    }

    fun openTopic(topic: CopyTopic) {
        if (topic.type != 1) return
        show(CopyExploreState(section = CopyExploreSection.TOPICS, topic = topic))
    }

    fun closeTopic() {
        if (_state.value.topic != null) show(CopyExploreState(section = CopyExploreSection.TOPICS))
    }

    private fun show(selection: CopyExploreState) {
        updateRoute()
        val old = _state.value
        retain(old)
        job?.cancel()
        request++
        val saved = retained[selection.contentKey]?.takeIf { retainedEnough(it.updatedAt) }
        _state.value = (saved ?: selection).copy(themes = old.themes, directory = old.directory,
            directoryLoading = old.directoryLoading, directoryError = old.directoryError,
            themeError = old.themeError, directoryUpdatedAt = old.directoryUpdatedAt,
            loading = false, loadingMore = false, refreshing = false)
        ensureDirectory()
        if (!fresh(_state.value.updatedAt) || _state.value.error != null) load(0, replaceRange = true)
    }

    fun refresh() {
        updateRoute()
        load(0, replaceRange = true)
    }

    fun retry() {
        val failed = _state.value.failedPage
        if (failed == null || failed == 0) refresh() else load(failed, replaceRange = false)
    }

    fun loadMore() {
        val current = _state.value
        if (!current.hasMore || current.loading || current.refreshing || current.loadingMore || current.error != null) return
        load(current.page + 1, replaceRange = false)
    }

    private fun load(page: Int, replaceRange: Boolean) {
        job?.cancel()
        val token = ++request
        val before = _state.value
        val key = before.contentKey
        val capturedRoute = route
        val hasRows = before.items.isNotEmpty() || before.topics.isNotEmpty()
        _state.update { it.copy(loading = page == 0 && !hasRows, refreshing = page == 0 && hasRows,
            loadingMore = page > 0, error = null, failedPage = null) }
        job = scope.launch {
            try {
                requireEnabled()
                val loaded = foregroundRequest("拷贝内容") {
                    val topic = before.topic?.let { item ->
                        if (page != 0) item else repository.topic(item.path).let { detail ->
                            detail.copy(coverUrl = detail.coverUrl.ifBlank { item.coverUrl },
                                brief = detail.brief.ifBlank { item.brief }, intro = detail.intro.ifBlank { item.intro },
                                period = detail.period.ifBlank { item.period }, date = detail.date.ifBlank { item.date })
                        }
                    }
                    val comics = mutableListOf<Manga>()
                    val topics = mutableListOf<CopyTopic>()
                    var cursor = page
                    var more: Boolean
                    var loadedPage: Int
                    do {
                        currentCoroutineContext().ensureActive()
                        if (before.section == CopyExploreSection.TOPICS && topic == null) {
                            val result = repository.topics(cursor)
                            topics += result.items
                            more = result.hasMore
                            loadedPage = result.nextPage?.minus(1) ?: cursor
                        } else {
                            val result = if (topic != null) repository.topicContents(topic, cursor)
                                else repository.browseCatalog(before.selection, cursor)
                            comics += result.items
                            more = result.hasMore
                            loadedPage = result.nextPage?.minus(1) ?: cursor
                        }
                        if (loadedPage < cursor) throw SourceException("来源返回了无效分页，请刷新。")
                        cursor = loadedPage + 1
                    } while (replaceRange && more && loadedPage < before.page)
                    before.copy(topic = topic, items = comics.distinctBy(Manga::id), topics = topics.distinctBy(CopyTopic::path),
                        page = loadedPage, hasMore = more)
                }
                currentCoroutineContext().ensureActive()
                if (token != request || key != _state.value.contentKey) return@launch
                if (capturedRoute != DownloadRoutingStore.sourceRevision("copy") || !enabled()) {
                    _state.update { it.copy(loading = false, refreshing = false, loadingMore = false,
                        error = "来源设置已变化，请刷新后继续。", failedPage = 0) }
                    return@launch
                }
                _state.update { current -> current.copy(topic = loaded.topic,
                    items = (if (page == 0) loaded.items else current.items + loaded.items).distinctBy(Manga::id),
                    topics = (if (page == 0) loaded.topics else current.topics + loaded.topics).distinctBy(CopyTopic::path),
                    page = loaded.page, hasMore = loaded.hasMore, loading = false, loadingMore = false,
                    refreshing = false, error = null, failedPage = null,
                    updatedAt = if (page == 0) wallTimeMillis() else current.updatedAt) }
                retain(_state.value)
            } catch (cancel: CancellationException) { throw cancel }
              catch (failure: Exception) {
                if (token == request && key == _state.value.contentKey) _state.update { it.copy(
                    loading = false, refreshing = false, loadingMore = false,
                    error = failure.message ?: "内容暂时无法读取，请重试。", failedPage = page) }
            }
        }
    }

    fun clearCache() {
        request++
        directoryRequest++
        job?.cancel()
        directoryJob?.cancel()
        retained.clear()
        directoryAttemptedAt = 0
        val old = _state.value
        _state.value = CopyExploreState(section = old.section, selection = old.selection, topic = old.topic)
        catalogSelection = CopyExploreState(selection = old.selection)
        topicSelection = CopyExploreState(section = CopyExploreSection.TOPICS, topic = old.topic)
    }

    private fun updateRoute() {
        val current = DownloadRoutingStore.sourceRevision("copy")
        if (current != route) { clearCache(); route = current }
    }

    private fun requireEnabled() {
        if (!enabled()) throw SourceException("拷贝来源已停用，请先在来源设置中启用。")
    }

    private fun fresh(at: Long): Boolean = repository.contentCachePolicy().let {
        it.enabled && at > 0 && wallTimeMillis() - at in 0L until it.freshMillis(ContentCacheKind.DISCOVERY)
    }

    private fun retainedEnough(at: Long): Boolean = repository.contentCachePolicy().let {
        it.enabled && at > 0 && wallTimeMillis() - at in 0L until it.retentionMillis
    }

    private fun retain(value: CopyExploreState) {
        if (!retainedEnough(value.updatedAt)) return
        retained.remove(value.contentKey)
        retained[value.contentKey] = value.copy(loading = false, refreshing = false, loadingMore = false)
        while (retained.size > 12) retained.remove(retained.keys.first())
    }
}
