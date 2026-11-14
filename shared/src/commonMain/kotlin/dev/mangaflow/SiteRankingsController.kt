package dev.mangaflow

import dev.mangaflow.data.*
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SiteRankingsState(
    val selection: SiteRankingSelection = SiteRankingSelection(),
    val entries: List<SiteRankingEntry> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val page: Int = 0,
    val fetchedAt: Long = 0,
    val error: String? = null,
    val linkedIds: Set<String> = emptySet(),
    val failedPage: Int? = null,
    val restoringSelection: Boolean = false,
    val preferenceError: String? = null,
)
data class RankedReadingSource(val manga: Manga, val verified: Boolean)

/** Session positions live in the existing save-state owner; each query owns its complete snapshot. */
class SiteRankingsController(
    val repository: SiteRankingsRepository,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(SiteRankingsState(restoringSelection = true))
    val state = mutable.asStateFlow()
    private val snapshots = LinkedHashMap<String, SiteRankingsState>()
    private val selections = mutableMapOf<RankingSite, SiteRankingSelection>()
    private val readingLinks = mutableMapOf<String, Manga>()
    fun cachedLink(work: SiteRankedWork): Manga? = readingLinks[work.key]
    private var request = 0L
    private var job: Job? = null
    private var visible = false

    private var restored = false
    private var selectionChanges = 0L
    private var preferenceWrite: Job? = null

    init {
        scope.launch {
            val saved = try { repository.readViewState() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
            if (selectionChanges == 0L && saved != null) {
                selections.putAll(saved.perSite)
                mutable.value = SiteRankingsState(saved.selected)
            }
            restored = true
            mutable.update { it.copy(restoringSelection = false) }
            if (visible && (mutable.value.entries.isEmpty() || stale(mutable.value))) load()
        }
    }

    private fun persistSelection(selection: SiteRankingSelection) {
        preferenceWrite?.cancel()
        val saved = SiteRankingViewState(selection, selections.toMap() + (selection.site to selection))
        preferenceWrite = scope.launch {
            try {
                repository.writeViewState(saved)
                mutable.update { if (it.selection == selection) it.copy(preferenceError = null) else it }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                mutable.update { if (it.selection == selection)
                    it.copy(preferenceError = "当前选择尚未保存，重启后可能恢复上次选择。") else it }
            }
        }
    }

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        if (!value) {
            request++
            job?.cancel()
            mutable.update { it.copy(refreshing = false, loadingMore = false) }
        } else if (restored && (mutable.value.entries.isEmpty() || stale(mutable.value))) load()
    }

    fun selectSite(site: RankingSite) {
        val selection = selections[site] ?: SiteRankingSelection(site, SiteRankingKind.SCORE, site.scopes.first())
        select(selection)
    }
    fun selectKind(kind: SiteRankingKind) = select(mutable.value.selection.copy(kind = kind))
    fun selectScope(value: SiteRankingScope) = select(mutable.value.selection.copy(scope = value))

    private fun select(selection: SiteRankingSelection) {
        if (!selection.valid() || selection == mutable.value.selection) return
        selectionChanges++
        restored = true
        val old = mutable.value
        if (old.entries.isNotEmpty() && old.entries.size <= 600)
            snapshots[old.selection.key] = old.copy(loading = false, refreshing = false, loadingMore = false, error = null)
        while (snapshots.size > 12) snapshots.remove(snapshots.keys.first())
        selections[selection.site] = selection
        request++
        job?.cancel()
        mutable.value = snapshots[selection.key] ?: SiteRankingsState(selection)
        persistSelection(selection)
        if (visible && (mutable.value.entries.isEmpty() || stale(mutable.value))) load()
    }

    fun refresh() = load(force = true)
    fun retry() {
        if ((mutable.value.failedPage ?: 0) > 0) more() else load(force = true)
    }

    private fun stale(state: SiteRankingsState): Boolean {
        val ttl = if (state.selection.kind == SiteRankingKind.TRENDING) 300_000L else 1_800_000L
        return wallTimeMillis() - state.fetchedAt !in 0 until ttl
    }

    private fun load(force: Boolean = false) {
        if (!visible || !restored) return
        val token = ++request
        job?.cancel()
        val selected = mutable.value.selection
        mutable.update { it.copy(loading = it.entries.isEmpty(), refreshing = it.entries.isNotEmpty(), error = null) }
        job = scope.launch {
            try {
                val epoch = repository.cacheGeneration()
                if (mutable.value.entries.isEmpty() && !force) {
                    val cached = repository.readCache(selected)
                    if (token != request) return@launch
                    if (cached != null) {
                        mutable.value = SiteRankingsState(selected, cached.entries, loading = false,
                            hasMore = cached.hasMore, page = cached.page, fetchedAt = cached.fetchedAt,
                            preferenceError = mutable.value.preferenceError)
                        publishLinks(token, cached.entries)
                        if (!stale(mutable.value)) return@launch
                        mutable.update { it.copy(refreshing = true) }
                    }
                }
                // Replace a refresh only after its entire retained page range has arrived.
                val lastPage = mutable.value.page.takeIf { mutable.value.entries.isNotEmpty() } ?: 0
                val rows = mutableListOf<SiteRankingEntry>()
                var hasMore = true
                var receivedPage = 0
                for (page in 0..lastPage) {
                    if (!hasMore) break
                    val result = repository.page(selected, page)
                    if (token != request) return@launch
                    rows.addAll(result.entries)
                    hasMore = result.hasMore
                    receivedPage = page
                }
                val snapshot = SiteRankingSnapshot(selected, rows.distinctBy { it.work.key },
                    receivedPage, hasMore, wallTimeMillis())
                mutable.value = SiteRankingsState(selected, snapshot.entries, loading = false,
                    hasMore = hasMore, page = receivedPage, fetchedAt = snapshot.fetchedAt,
                    preferenceError = mutable.value.preferenceError)
                publishLinks(token, snapshot.entries)
                save(snapshot, epoch)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (token == request) mutable.update {
                    it.copy(loading = false, refreshing = false, error = failure.message ?: "榜单暂时无法读取，请重试。", failedPage = 0)
                }
            }
        }
    }

    fun more() {
        val current = mutable.value
        if (!visible || current.loading || current.refreshing || current.loadingMore || !current.hasMore) return
        val token = ++request
        job?.cancel()
        mutable.update { it.copy(loadingMore = true, error = null) }
        job = scope.launch {
            try {
                val epoch = repository.cacheGeneration()
                val result = repository.page(current.selection, current.page + 1)
                if (token != request) return@launch
                val snapshot = SiteRankingSnapshot(current.selection,
                    (current.entries + result.entries).distinctBy { it.work.key },
                    current.page + 1, result.hasMore, current.fetchedAt)
                mutable.update { it.copy(entries = snapshot.entries, page = snapshot.page,
                    hasMore = result.hasMore, loadingMore = false, error = null, failedPage = null) }
                publishLinks(token, snapshot.entries)
                save(snapshot, epoch)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (token == request) mutable.update { it.copy(loadingMore = false,
                    error = failure.message ?: "下一页暂时无法读取，请重试。", failedPage = current.page + 1) }
            }
        }
    }

    fun recordLink(work: SiteRankedWork, manga: Manga) {
        readingLinks[work.key] = manga
        mutable.update { it.copy(linkedIds = it.linkedIds + work.key) }
    }

    private suspend fun publishLinks(token: Long, rows: List<SiteRankingEntry>) {
        val links = rows.mapNotNull { row -> repository.linked(row.work)?.let { row.work.key to it } }.toMap()
        if (token == request) {
            readingLinks.putAll(links)
            mutable.update { it.copy(linkedIds = links.keys) }
        }
    }

    private suspend fun save(snapshot: SiteRankingSnapshot, epoch: Long) {
        try { repository.writeCache(snapshot, epoch) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* A disposable cache write cannot hide a successful native list. */ }
    }

    suspend fun clearCache() {
        request++
        job?.cancel()
        repository.clearCache()
        snapshots.clear()
        mutable.value = SiteRankingsState(mutable.value.selection)
        if (visible) load(force = true)
    }
}
