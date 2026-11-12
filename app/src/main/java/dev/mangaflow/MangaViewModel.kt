package dev.mangaflow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.CopyMangaRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.update.UpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseState(
    val query: String = "",
    val items: List<Manga> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
)

data class DetailState(
    val manga: Manga,
    val detail: MangaDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val reviews: List<ReviewSource> = emptyList(),
    val reviewsLoading: Boolean = true,
)

data class ReadingSession(val manga: Manga, val chapters: List<Chapter>, val chapterId: String)

class MangaViewModel(application: Application) : AndroidViewModel(application) {
    val repository = CopyMangaRepository()
    val library = LibraryStore(application)
    val downloads = DownloadStore.get(application)
    val updates = UpdateManager.get(application)
    private val reviews = ReviewRepository()
    private val _browse = MutableStateFlow(BrowseState())
    val browse = _browse.asStateFlow()
    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail = _detail.asStateFlow()
    private val _reading = MutableStateFlow<ReadingSession?>(null)
    val reading = _reading.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private var browseJob: Job? = null
    private var detailJob: Job? = null
    private var reviewJob: Job? = null
    private val preparingDownloads = mutableSetOf<String>()

    init { refresh() }

    fun setQuery(query: String) {
        if (query == _browse.value.query) return
        browseJob?.cancel()
        _browse.value = BrowseState(query = query)
        browseJob = viewModelScope.launch {
            if (query.isNotBlank()) delay(450)
            loadBrowse(query.trim(), 0)
        }
    }

    fun refresh() {
        browseJob?.cancel()
        _browse.update { it.copy(loading = true, error = null, loadingMore = false) }
        browseJob = viewModelScope.launch { loadBrowse(_browse.value.query.trim(), 0) }
    }

    fun loadMore() {
        val state = _browse.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        _browse.update { it.copy(loadingMore = true, error = null) }
        browseJob = viewModelScope.launch { loadBrowse(state.query.trim(), state.page + 1) }
    }

    private suspend fun loadBrowse(query: String, page: Int) {
        try {
            val result = if (query.isBlank()) repository.discover(page) else repository.search(query, page)
            _browse.update { current ->
                current.copy(
                    items = (if (page == 0) result.items else current.items + result.items).distinctBy(Manga::id),
                    loading = false, loadingMore = false, error = null, hasMore = result.hasMore, page = page,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            _browse.update { it.copy(loading = false, loadingMore = false, error = failure.message ?: "暂时无法读取漫画") }
        }
    }

    fun openManga(manga: Manga, continueChapterId: String? = null) {
        detailJob?.cancel()
        reviewJob?.cancel()
        _detail.value = DetailState(manga)
        detailJob = viewModelScope.launch {
            try {
                val loaded = repository.detail(manga.id)
                _detail.update { it?.copy(manga = loaded.manga, detail = loaded, loading = false) }
                if (continueChapterId != null) {
                    loaded.chapters.firstOrNull { it.id == continueChapterId }?.let { readChapter(it) }
                }
                loadReviews(loaded.manga)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                val offline = downloadedChapters(manga.id)
                val message = if (offline.isEmpty()) failure.message ?: "暂时无法读取详情"
                else "暂时无法读取线上目录，下面显示已下载章节。"
                _detail.update {
                    it?.copy(loading = false, error = message, reviewsLoading = false,
                        detail = if (offline.isEmpty()) null else MangaDetail(manga, offline))
                }
                if (continueChapterId != null && offline.any { it.id == continueChapterId }) {
                    _reading.value = ReadingSession(manga, offline, continueChapterId)
                }
            }
        }
    }

    private fun loadReviews(manga: Manga) {
        reviewJob = viewModelScope.launch {
            try {
                val sources = reviews.lookup(manga)
                _detail.update { if (it?.manga?.id == manga.id) it.copy(reviews = sources, reviewsLoading = false) else it }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _detail.update { it?.copy(reviewsLoading = false) }
            }
        }
    }

    fun retryDetail() { _detail.value?.manga?.let { openManga(it) } }

    fun closeDetail() {
        detailJob?.cancel()
        reviewJob?.cancel()
        _detail.value = null
    }

    fun readChapter(chapter: Chapter) {
        val current = _detail.value ?: return
        val chapters = current.detail?.chapters ?: downloadedChapters(current.manga.id)
        if (chapters.none { it.id == chapter.id }) return
        _reading.value = ReadingSession(current.manga, chapters, chapter.id)
    }

    fun openDownload(entry: DownloadEntry) {
        if (entry.status != DownloadStatus.COMPLETED) return
        val chapters = downloadedChapters(entry.manga.id)
        _reading.value = ReadingSession(entry.manga, chapters, entry.chapter.id)
    }

    fun closeReader() { _reading.value = null }

    fun downloadChapter(chapter: Chapter) {
        val manga = _detail.value?.manga ?: return
        val key = "${manga.id}/${chapter.id}"
        if (!preparingDownloads.add(key)) return
        viewModelScope.launch {
            try {
                messages.send("正在准备 ${chapter.title}")
                val pages = repository.pages(manga.id, chapter.id)
                downloads.enqueue(manga, chapter, pages, repository.imageHeaders(manga.id, chapter.id))
                messages.send("已加入下载，可在下载页查看进度")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                messages.send(failure.message ?: "未能加入下载，请重试")
            } finally {
                preparingDownloads.remove(key)
            }
        }
    }

    private fun downloadedChapters(mangaId: String): List<Chapter> = downloads.entries.value
        .filter { it.manga.id == mangaId && it.status == DownloadStatus.COMPLETED }
        .map(DownloadEntry::chapter).distinctBy(Chapter::id).sortedBy(Chapter::index)
}
