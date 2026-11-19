package dev.mangaflow.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.mangaflow.data.Manga
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
    var message by mutableStateOf<String?>(null)
        private set
    var nextPage by mutableStateOf<Int?>(null)
        private set
    private var owner: SiteOwner? = null
    private var loaded = false

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
    }

    suspend fun activate() = operate("暂时无法读取站点收藏。") { current ->
        if (!loaded) readCollection(current, folder = null, append = false, refreshFolders = true)
    }

    suspend fun load(folder: String? = folderId, append: Boolean = false, refreshFolders: Boolean = false) =
        operate("暂时无法读取站点收藏。") { current ->
            readCollection(current, folder, append, refreshFolders)
        }

    private suspend fun readCollection(current: SiteOwner, folder: String?, append: Boolean, refreshFolders: Boolean) {
        val selectedFolders = if (!refreshFolders && folder != null && folders.any { it.id == folder }) folders
            else repository.sourceFavoriteFolders(source.id).distinctBy { it.id }
        val selected = selectedFolders.firstOrNull { it.id == folder } ?: selectedFolders.firstOrNull()
        val page = if (append && selected?.id == folderId) nextPage ?: return else 0
        val result = selected?.let { repository.sourceFavorites(source.id, it.id, page) }
        requireCurrent(current)
        val fetched = result?.items.orEmpty()
        check(fetched.all { it.sourceId == source.id && SourceKeys.decodeManga(it.id).sourceId == source.id })
        val merged = if (page == 0) fetched.distinctBy(Manga::id) else (books + fetched).distinctBy(Manga::id)
        val more = result?.takeIf { it.hasMore && (page == 0 || merged.size > books.size) }
            ?.let { (it.nextPage ?: (page + 1)).takeIf { next -> next in (page + 1)..100_000 } }
        folders = selectedFolders
        folderId = selected?.id
        books = merged
        nextPage = more
        loaded = true
    }

    suspend fun remove(book: Manga) {
        val selected = folderId ?: return
        val expected = owner ?: return
        if (book.sourceId != source.id || books.none { it.id == book.id }) return
        operate("暂时无法移出这项站点收藏。") { current ->
            if (current != expected || folderId != selected) return@operate
            repository.sourceSetFavorite(source.id, book.id, selected, false)
            requireCurrent(current)
            books = books.filterNot { it.id == book.id }
        }
    }

    private suspend fun requireCurrent(expected: SiteOwner) {
        currentCoroutineContext().ensureActive()
        check(expected == currentOwner()) { "来源账号已变化" }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun operate(failureMessage: String, action: suspend (SiteOwner) -> Unit) {
        if (busy) return
        busy = true
        message = null
        try {
            val current = currentOwner()
            if (current != owner) clearCollection()
            owner = current
            accountAvailable = current != null
            if (current != null) action(current)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            val latest = try { currentOwner() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { owner }
            if (latest != owner || latest == null) clearCollection()
            owner = latest
            if (accountAvailable != null || latest != null) accountAvailable = latest != null
            message = failureMessage
        } finally {
            busy = false
        }
    }
}
