package dev.mangaflow.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.data.sources.SourceFavoriteFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A site tab projects the selected source account's collection without importing it. */
internal class ProfileSiteShelf(
    val source: BodySourceInfo,
    private val repository: UnifiedMangaRepository,
) {
    var accountAvailable by mutableStateOf<Boolean?>(null)
        private set
    var folders by mutableStateOf<List<SourceFavoriteFolder>>(emptyList())
        private set
    var folderId by mutableStateOf<String?>(null)
        private set
    var books by mutableStateOf<List<Manga>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var nextPage by mutableStateOf<Int?>(null)
        private set
    private var owner: SiteOwner? = null
    private var loaded = false
    private var failedRead by mutableStateOf<ReadRequest?>(null)
    val collectionError: String?
        get() {
            val attempt = failedRead as? ReadRequest.Collection ?: return null
            val otherFolder = folders.firstOrNull { it.id == attempt.folder && it.id != folderId }
            return when {
                otherFolder != null -> "「${otherFolder.title}」暂未读取"
                loaded -> "站点收藏暂未更新"
                else -> "站点收藏暂未读取"
            }
        }
    val nextPageFailed: Boolean get() = failedRead is ReadRequest.More

    private sealed interface ReadRequest {
        data class Collection(val folder: String? = null, val refreshFolders: Boolean = true) : ReadRequest
        data class More(val folder: String, val page: Int) : ReadRequest
    }

    private data class SiteOwner(val displayName: String, val session: String)

    private suspend fun currentOwner(): SiteOwner? {
        val account = repository.sourceAccountState(source.id)
        if (!account.signedIn) return null
        return SiteOwner(account.displayName, repository.sourceProbeIdentity(source.id))
    }

    private fun clearCollection() {
        folders = emptyList()
        folderId = null
        books = emptyList()
        nextPage = null
        loaded = false
        failedRead = null
    }

    suspend fun activate() = read()

    suspend fun load(folder: String? = folderId, refreshFolders: Boolean = false) =
        read(ReadRequest.Collection(folder, refreshFolders))

    suspend fun loadMore() {
        val folder = folderId ?: return
        val page = nextPage ?: return
        read(ReadRequest.More(folder, page))
    }

    suspend fun retryRead() {
        failedRead?.let { read(it) }
    }

    private suspend fun read(request: ReadRequest? = null) {
        var attempted = request
        operate(onFailure = { changed ->
            if (accountAvailable != false) {
                failedRead = if (changed) ReadRequest.Collection() else attempted ?: failedRead ?: ReadRequest.Collection()
            }
        }) { current, changed ->
            val selectedRequest = when {
                changed -> ReadRequest.Collection()
                request != null -> request
                !loaded && failedRead == null -> ReadRequest.Collection()
                else -> return@operate
            }
            attempted = selectedRequest
            when (selectedRequest) {
                is ReadRequest.Collection -> readCollection(current, selectedRequest)
                is ReadRequest.More -> {
                    if (selectedRequest.folder != folderId || selectedRequest.page != nextPage) return@operate
                    loadingMore = true
                    val result = repository.sourceFavorites(source.id, selectedRequest.folder, selectedRequest.page)
                    requireCurrent(current)
                    val merged = (books + validatedBooks(result)).distinctBy(Manga::id)
                    nextPage = if (merged.size > books.size) result.validNextPage(selectedRequest.page) else null
                    books = merged
                }
            }
            failedRead = null
        }
    }

    private suspend fun readCollection(current: SiteOwner, request: ReadRequest.Collection) {
        val folder = request.folder
        val selectedFolders = if (!request.refreshFolders && folder != null && folders.any { it.id == folder }) folders
            else repository.sourceFavoriteFolders(source.id).distinctBy { it.id }
        val selected = selectedFolders.firstOrNull { it.id == folder } ?: selectedFolders.firstOrNull()
        val result = selected?.let { repository.sourceFavorites(source.id, it.id, 0) }
        requireCurrent(current)
        val firstPage = validatedBooks(result).distinctBy(Manga::id)
        folders = selectedFolders
        folderId = selected?.id
        books = firstPage
        nextPage = result?.validNextPage(0)
        loaded = true
    }

    private fun validatedBooks(result: MangaPage?): List<Manga> {
        val fetched = result?.items.orEmpty()
        check(fetched.all { it.sourceId == source.id && SourceKeys.decodeManga(it.id).sourceId == source.id })
        return fetched
    }

    private fun MangaPage.validNextPage(page: Int): Int? =
        (nextPage ?: (page + 1)).takeIf { hasMore && it in (page + 1)..100_000 }

    /** Returns an action-specific notice only when the result could not be confirmed. */
    suspend fun remove(book: Manga): String? {
        val selected = folderId ?: return null
        val expected = owner ?: return null
        if (book.sourceId != source.id || books.none { it.id == book.id }) return null
        var notice: String? = null
        operate(onFailure = { changed ->
            if (changed && accountAvailable == true) failedRead = ReadRequest.Collection()
            notice = "未能确认「${book.title}」已移出站点收藏。"
        }) { current, changed ->
            if (current != expected || folderId != selected) {
                if (changed) failedRead = ReadRequest.Collection()
                return@operate
            }
            repository.sourceSetFavorite(source.id, book.id, selected, false)
            requireCurrent(current)
            books = books.filterNot { it.id == book.id }
        }
        return notice
    }

    private suspend fun requireCurrent(expected: SiteOwner) {
        currentCoroutineContext().ensureActive()
        check(expected == currentOwner()) { "来源账号已变化" }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun operate(onFailure: (ownerChanged: Boolean) -> Unit, action: suspend (SiteOwner, Boolean) -> Unit) {
        if (busy) return
        busy = true
        try {
            val current = currentOwner()
            val changed = current != owner
            if (changed) clearCollection()
            owner = current
            accountAvailable = current != null
            if (current != null) action(current, changed)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            val latest = try {
                currentOwner().also { accountAvailable = it != null }
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { owner }
            val changed = latest != owner
            if (changed || latest == null) clearCollection()
            owner = latest
            onFailure(changed)
        } finally {
            loadingMore = false
            busy = false
        }
    }
}
