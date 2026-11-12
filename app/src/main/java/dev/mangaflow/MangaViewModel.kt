package dev.mangaflow

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mangaflow.account.AccountProfile
import dev.mangaflow.account.CloudHistoryEntry
import dev.mangaflow.account.CopyAccountRepository
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.CopyMangaRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.SourceException
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReadingProgress
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
    val failedPage: Int? = null,
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

data class AccountUiState(
    val profile: AccountProfile? = null,
    val busy: Boolean = true,
    val error: String? = null,
    val canRetryConnection: Boolean = false,
    val favorites: List<Manga> = emptyList(),
    val favoritesLoading: Boolean = false,
    val favoritesHasMore: Boolean = false,
    val favoritesError: String? = null,
    val history: List<CloudHistoryEntry> = emptyList(),
    val historyLoading: Boolean = false,
    val historyHasMore: Boolean = false,
    val historyError: String? = null,
    val syncMessage: String? = null,
    val canRetrySync: Boolean = false,
    val syncingFavorites: Set<String> = emptySet(),
)

class MangaViewModel(application: Application) : AndroidViewModel(application) {
    val repository = CopyMangaRepository()
    val library = LibraryStore(application)
    val downloads = DownloadStore.get(application)
    val updates = UpdateManager.get(application)
    private val reviews = ReviewRepository()
    private val accountRepository = CopyAccountRepository(application)
    private val _account = MutableStateFlow(AccountUiState())
    val account = _account.asStateFlow()
    private val _browse = MutableStateFlow(BrowseState())
    val browse = _browse.asStateFlow()
    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail = _detail.asStateFlow()
    private val _reading = MutableStateFlow<ReadingSession?>(null)
    val reading = _reading.asStateFlow()
    private val _refreshingDownloads = MutableStateFlow<Set<String>>(emptySet())
    val refreshingDownloads = _refreshingDownloads.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()
    private var browseJob: Job? = null
    private var detailJob: Job? = null
    private var reviewJob: Job? = null
    private val preparingDownloads = mutableSetOf<String>()
    private var accountJob: Job? = null
    private var accountRequestId = 0L
    private var accountCleanupFailed = false
    private var cloudFavoritesJob: Job? = null
    private var cloudHistoryJob: Job? = null
    private var chapterSyncJob: Job? = null
    private val favoriteSyncJobs = mutableMapOf<String, Job>()
    private val pendingFavoriteSync = mutableMapOf<String, Pair<Manga, Boolean>>()
    private var pendingChapterSync: Pair<Manga, Chapter>? = null
    private var cloudFavoritesPage = -1
    private var cloudHistoryPage = -1
    private var cloudFavoritesFailedPage: Int? = null
    private var cloudHistoryFailedPage: Int? = null
    private var lastChapterSync: String? = null
    private var visibleReading: Pair<Manga, Chapter>? = null

    init { refresh(); restoreAccount() }

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
        _browse.update { it.copy(loading = true, error = null, failedPage = null, loadingMore = false) }
        browseJob = viewModelScope.launch { loadBrowse(_browse.value.query.trim(), 0) }
    }

    fun loadMore() {
        val state = _browse.value
        if (state.loading || state.loadingMore || !state.hasMore) return
        _browse.update { it.copy(loadingMore = true, error = null, failedPage = null) }
        browseJob = viewModelScope.launch { loadBrowse(state.query.trim(), state.page + 1) }
    }

    fun retryBrowse() {
        val state = _browse.value
        if (state.loading || state.loadingMore) return
        val page = state.failedPage ?: return
        _browse.update { it.copy(loading = page == 0, loadingMore = page > 0, error = null, failedPage = null) }
        browseJob = viewModelScope.launch { loadBrowse(state.query.trim(), page) }
    }

    private suspend fun loadBrowse(query: String, page: Int) {
        try {
            val result = if (query.isBlank()) repository.discover(page) else repository.search(query, page)
            _browse.update { current ->
                current.copy(
                    items = (if (page == 0) result.items else current.items + result.items).distinctBy(Manga::id),
                    loading = false, loadingMore = false, error = null, failedPage = null,
                    hasMore = result.hasMore, page = page,
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            _browse.update { it.copy(loading = false, loadingMore = false,
                error = failure.message ?: "暂时无法读取漫画", failedPage = page) }
        }
    }

    fun openManga(manga: Manga, continueChapterId: String? = null) {
        detailJob?.cancel()
        reviewJob?.cancel()
        _detail.value = DetailState(manga)
        loadReviews(manga)
        detailJob = viewModelScope.launch {
            try {
                val loaded = repository.detail(manga.id) { metadata ->
                    _detail.update { if (it?.manga?.id == manga.id) it.copy(manga = metadata) else it }
                }
                _detail.update { it?.copy(manga = loaded.manga, detail = loaded, loading = false) }
                if (continueChapterId != null) {
                    val chapter = loaded.chapters.firstOrNull { it.id == continueChapterId }
                    if (chapter != null) readChapter(chapter)
                    else messages.send("阅读记录中的章节不在当前目录中，请从目录选择章节。")
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                val offline = downloadedChapters(manga.id)
                val message = if (offline.isEmpty()) failure.message ?: "暂时无法读取详情"
                else "暂时无法读取线上目录，下面显示已下载章节。"
                _detail.update { current ->
                    if (current?.manga?.id != manga.id) current
                    else current.copy(loading = false, error = message,
                        detail = if (offline.isEmpty()) null else MangaDetail(current.manga, offline))
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
                val sources = reviews.lookup(manga) { source ->
                    _detail.update { current ->
                        if (current?.manga?.id != manga.id) current
                        else current.copy(reviews = (current.reviews.filterNot { it.id == source.id } + source)
                            .sortedBy { if (it.id == "bangumi") 0 else 1 })
                    }
                }
                _detail.update { if (it?.manga?.id == manga.id) it.copy(reviews = sources, reviewsLoading = false) else it }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _detail.update { if (it?.manga?.id == manga.id) it.copy(reviewsLoading = false) else it }
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
        visibleReading = null
        _reading.value = ReadingSession(current.manga, chapters, chapter.id)
    }

    fun openDownload(entry: DownloadEntry) {
        if (entry.status != DownloadStatus.COMPLETED) return
        val chapters = downloadedChapters(entry.manga.id)
        visibleReading = null
        _reading.value = ReadingSession(entry.manga, chapters, entry.chapter.id)
    }

    fun continueReading(progress: ReadingProgress) {
        viewModelScope.launch {
            downloads.awaitReady()
            val offline = downloads.entries.value.firstOrNull {
                it.manga.id == progress.manga.id && it.chapter.id == progress.chapterId && it.status == DownloadStatus.COMPLETED
            }
            if (offline != null) openDownload(offline)
            else openManga(progress.manga, progress.chapterId)
        }
    }

    fun retryDownload(entry: DownloadEntry) {
        if (!entry.needsSourceRefresh) {
            downloads.retry(entry.id)
            return
        }
        val key = "${entry.manga.id}/${entry.chapter.id}"
        if (!preparingDownloads.add(key)) return
        _refreshingDownloads.update { it + entry.id }
        viewModelScope.launch {
            try {
                val pages = repository.pages(entry.manga.id, entry.chapter.id)
                val current = downloads.entryFor(entry.manga.id, entry.chapter.id)
                if (current == null || current.createdAt != entry.createdAt || !current.needsSourceRefresh ||
                    current.status in setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.COMPLETED)) return@launch
                downloads.enqueue(entry.manga, entry.chapter, pages, repository.imageHeaders(entry.manga.id, entry.chapter.id))
                messages.send("图片地址已刷新，继续下载")
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                messages.send(failure.message ?: "暂时无法刷新章节图片，请稍后重试")
            } finally {
                preparingDownloads.remove(key)
                _refreshingDownloads.update { it - entry.id }
            }
        }
    }

    fun closeReader() {
        _reading.value = null
        visibleReading = null
        chapterSyncJob?.cancel()
        lastChapterSync = null
    }

    fun loginWithCookie(input: String) = authenticate { accountRepository.loginWithCookie(input) }

    fun login(username: String, password: String) = authenticate { accountRepository.login(username, password) }

    private fun restoreAccount() = authenticate(restoring = true) { accountRepository.restore() }

    private fun authenticate(restoring: Boolean = false, action: suspend () -> AccountProfile?) {
        accountJob?.cancel()
        cancelAccountRequests()
        val requestId = ++accountRequestId
        accountCleanupFailed = false
        cloudFavoritesPage = -1
        cloudHistoryPage = -1
        cloudFavoritesFailedPage = null
        cloudHistoryFailedPage = null
        _account.value = AccountUiState(busy = true)
        accountJob = viewModelScope.launch {
            try {
                val profile = action()
                if (requestId != accountRequestId) return@launch
                _account.value = AccountUiState(profile = profile, busy = false)
                cloudFavoritesPage = -1
                cloudHistoryPage = -1
                if (profile != null) {
                    loadCloudFavorites(refresh = true)
                    loadCloudHistory(refresh = true)
                    visibleReading?.let { (manga, chapter) -> onChapterVisible(manga, chapter) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (requestId == accountRequestId) {
                    _account.value = AccountUiState(profile = accountRepository.profile.value, busy = false,
                        error = accountError(failure, "登录暂时未完成，请稍后重试。"), canRetryConnection = restoring)
                }
            }
        }
    }

    fun logout() {
        accountRequestId++
        accountJob?.cancel()
        cancelAccountRequests()
        cloudFavoritesPage = -1
        cloudHistoryPage = -1
        cloudFavoritesFailedPage = null
        cloudHistoryFailedPage = null
        try {
            accountRepository.logout()
            accountCleanupFailed = false
            _account.value = AccountUiState(busy = false)
        } catch (failure: Exception) {
            accountCleanupFailed = true
            _account.value = AccountUiState(busy = false,
                error = accountError(failure, "账号已退出，但设备凭据清理未完成，请重试。"), canRetryConnection = true)
        }
    }

    fun refreshAccount() {
        if (_account.value.busy) return
        if (accountCleanupFailed) logout()
        else if (_account.value.profile == null) restoreAccount()
        else {
            _account.update { it.copy(error = null) }
            loadCloudFavorites(refresh = true)
            loadCloudHistory(refresh = true)
        }
    }

    fun moreCloudFavorites() = loadCloudFavorites(refresh = false, retryPage = cloudFavoritesFailedPage)

    private fun loadCloudFavorites(refresh: Boolean, retryPage: Int? = null) {
        val state = _account.value
        if (state.profile == null || state.busy || (!refresh && (state.favoritesLoading ||
            (retryPage == null && !state.favoritesHasMore && cloudFavoritesPage >= 0)))) return
        cloudFavoritesJob?.cancel()
        val version = accountRepository.sessionVersion
        val page = retryPage ?: if (refresh) 0 else cloudFavoritesPage + 1
        cloudFavoritesFailedPage = null
        _account.update { it.copy(favoritesLoading = true, favoritesError = null) }
        cloudFavoritesJob = viewModelScope.launch {
            try {
                val result = accountRepository.favorites(page)
                if (!isCurrentAccount(version)) return@launch
                cloudFavoritesPage = page
                _account.update { it.copy(
                    favorites = (if (page == 0) result.items else it.favorites + result.items).distinctBy(Manga::id),
                    favoritesLoading = false, favoritesHasMore = result.hasMore, favoritesError = null,
                ) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version)) {
                    cloudFavoritesFailedPage = page
                    _account.update { it.copy(favoritesLoading = false,
                        favoritesError = accountError(failure, "云端收藏暂时无法读取，请重试。")) }
                }
            }
        }
    }

    fun moreCloudHistory() = loadCloudHistory(refresh = false, retryPage = cloudHistoryFailedPage)

    private fun loadCloudHistory(refresh: Boolean, retryPage: Int? = null) {
        val state = _account.value
        if (state.profile == null || state.busy || (!refresh && (state.historyLoading ||
            (retryPage == null && !state.historyHasMore && cloudHistoryPage >= 0)))) return
        cloudHistoryJob?.cancel()
        val version = accountRepository.sessionVersion
        val page = retryPage ?: if (refresh) 0 else cloudHistoryPage + 1
        cloudHistoryFailedPage = null
        _account.update { it.copy(historyLoading = true, historyError = null) }
        cloudHistoryJob = viewModelScope.launch {
            try {
                val result = accountRepository.history(page)
                if (!isCurrentAccount(version)) return@launch
                cloudHistoryPage = page
                _account.update { it.copy(
                    history = (if (page == 0) result.items else it.history + result.items).distinctBy { row -> row.manga.id },
                    historyLoading = false, historyHasMore = result.hasMore, historyError = null,
                ) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version)) {
                    cloudHistoryFailedPage = page
                    _account.update { it.copy(historyLoading = false,
                        historyError = accountError(failure, "云端记录暂时无法读取，请重试。")) }
                }
            }
        }
    }

    fun continueCloudHistory(entry: CloudHistoryEntry) {
        viewModelScope.launch {
            downloads.awaitReady()
            val offline = downloads.entries.value.firstOrNull {
                it.manga.id == entry.manga.id && it.chapter.id == entry.chapterId && it.status == DownloadStatus.COMPLETED
            }
            if (offline != null) openDownload(offline)
            else openManga(entry.manga, entry.chapterId.takeIf(String::isNotBlank))
        }
    }

    fun toggleFavorite(manga: Manga) {
        if (manga.id in _account.value.syncingFavorites) return
        library.toggleFavorite(manga)
        if (_account.value.profile != null && !_account.value.busy) {
            setCloudFavorite(manga, library.favorites.value.any { it.id == manga.id })
        }
    }

    fun setCloudFavorite(manga: Manga, favorite: Boolean) {
        val state = _account.value
        if (state.profile == null || state.busy || manga.id in state.syncingFavorites) return
        val version = accountRepository.sessionVersion
        pendingFavoriteSync.remove(manga.id)
        _account.update { it.copy(syncingFavorites = it.syncingFavorites + manga.id,
            syncMessage = "正在同步收藏…", canRetrySync = hasPendingSync()) }
        favoriteSyncJobs[manga.id] = viewModelScope.launch {
            try {
                val uuid = repository.comicUuid(manga.id)
                if (!isCurrentAccount(version)) return@launch
                val confirmed = accountRepository.setFavorite(manga.id, uuid, favorite)
                if (!isCurrentAccount(version)) return@launch
                if (!confirmed) throw SourceException("来源尚未确认收藏同步，请稍后重试。")
                _account.update { it.copy(syncMessage = if (favorite) "已加入云端收藏" else "已从云端收藏移除") }
                loadCloudFavorites(refresh = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version)) {
                    val message = accountError(failure, "云端收藏同步失败，请稍后重试。")
                    pendingFavoriteSync[manga.id] = manga to favorite
                    _account.update { it.copy(syncMessage = "$message 本地书架不受影响。", canRetrySync = true) }
                    messages.send("$message 本地书架不受影响。")
                }
            } finally {
                if (isCurrentAccount(version)) _account.update { it.copy(syncingFavorites = it.syncingFavorites - manga.id) }
                if (accountRepository.sessionVersion == version) favoriteSyncJobs.remove(manga.id)
            }
        }
    }

    fun retryAccountSync() {
        if (_account.value.profile == null || _account.value.busy) return
        pendingFavoriteSync.values.toList().forEach { (manga, favorite) -> setCloudFavorite(manga, favorite) }
        pendingChapterSync?.let { (manga, chapter) ->
            if (chapterSyncJob?.isActive != true) syncChapterProgress(manga, chapter, accountRepository.sessionVersion)
        }
    }

    /** Called after a visible image succeeds; public reading never awaits this side channel. */
    fun onChapterVisible(manga: Manga, chapter: Chapter) {
        if (_reading.value?.manga?.id != manga.id) return
        visibleReading = manga to chapter
        if (_account.value.profile == null || _account.value.busy) return
        val version = accountRepository.sessionVersion
        val key = "$version/${manga.id}/${chapter.id}"
        if (lastChapterSync == key) return
        lastChapterSync = key
        syncChapterProgress(manga, chapter, version)
    }

    private fun syncChapterProgress(manga: Manga, chapter: Chapter, version: Long) {
        chapterSyncJob?.cancel()
        pendingChapterSync = null
        _account.update { it.copy(canRetrySync = hasPendingSync()) }
        chapterSyncJob = viewModelScope.launch {
            try {
                delay(700)
                if (!isCurrentAccount(version)) return@launch
                accountRepository.syncChapter(manga.id, chapter.id)
                if (!isCurrentAccount(version)) return@launch
                _account.update { it.copy(
                    syncMessage = if (pendingFavoriteSync.isEmpty()) "云端记录已同步至 ${chapter.title}；页内位置保存在本机。" else it.syncMessage,
                    canRetrySync = hasPendingSync(),
                ) }
                loadCloudHistory(refresh = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (isCurrentAccount(version)) {
                    pendingChapterSync = manga to chapter
                    _account.update { it.copy(syncMessage = accountError(failure, "云端章节记录暂未同步。") +
                        " 本机阅读进度已保留。", canRetrySync = true) }
                }
            }
        }
    }

    private fun isCurrentAccount(version: Long) = accountRepository.sessionVersion == version &&
        _account.value.profile != null && !_account.value.busy

    private fun hasPendingSync() = pendingFavoriteSync.isNotEmpty() || pendingChapterSync != null

    private fun cancelAccountRequests() {
        cloudFavoritesJob?.cancel()
        cloudHistoryJob?.cancel()
        chapterSyncJob?.cancel()
        favoriteSyncJobs.values.toList().forEach { it.cancel() }
        favoriteSyncJobs.clear()
        pendingFavoriteSync.clear()
        pendingChapterSync = null
        lastChapterSync = null
    }

    private fun accountError(failure: Exception, fallback: String) =
        (failure as? SourceException)?.message ?: fallback

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
