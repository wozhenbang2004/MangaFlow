package dev.mangaflow.account

import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.SourceStage
import dev.mangaflow.data.SourceFailureKind
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sourceFailureSummary
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit reconciliation for one native provider in the adult profile.
 * Read-only checks can run on opening the shelf; writes require row-by-row approval.
 * This controller never accesses the Copy account or includes an ordinary manga.
 */
enum class AdultFavoriteChoice { SKIP, IMPORT_LOCAL, UPLOAD_REMOTE, REMOVE_LOCAL, REMOVE_REMOTE }

internal enum class AdultShelfProvider(val sourceId: String, val label: String, val folder: String, val workId: Regex) {
    JM("jmcomic", "禁漫", "0", Regex("[0-9]{1,14}")),
    PICACG("picacg", "哔咔", "default", Regex("[a-zA-Z0-9_-]{6,80}")),
}

data class AdultFavoriteDifference(
    val manga: Manga,
    val inLocal: Boolean,
    val inSite: Boolean,
) {
    val key: String get() = manga.id
}

data class AdultShelfSyncState(
    val owner: String? = null,
    val revision: Long = 0L,
    val checking: Boolean = false,
    val applying: Boolean = false,
    val checked: Boolean = false,
    val differences: List<AdultFavoriteDifference> = emptyList(),
    val localCount: Int = 0,
    val remoteCount: Int = 0,
    val error: String? = null,
    val completed: String? = null,
    val checkedAt: Long = 0L,
    val loginRequired: Boolean = false,
) {
    val busy: Boolean get() = checking || applying
}

internal class AdultShelfSyncController(
    private val repository: UnifiedMangaRepository,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
    val provider: AdultShelfProvider,
) {
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(AdultShelfSyncState())
    val state: StateFlow<AdultShelfSyncState> = mutableState.asStateFlow()
    @kotlin.concurrent.Volatile private var sequence = 0L
    private var job: Job? = null
    @kotlin.concurrent.Volatile private var snapshot: Snapshot? = null

    private data class Snapshot(
        val owner: String,
        val session: String,
        val local: Map<String, Manga>,
        val remote: Map<String, Manga>,
    )

    private fun validNative(manga: Manga): Boolean {
        if (manga.sourceId != provider.sourceId || !SourceKeys.isEncoded(manga.id)) return false
        val ref = runCatching { SourceKeys.decodeManga(manga.id) }.getOrNull() ?: return false
        return ref.sourceId == provider.sourceId && ref.mangaId.matches(provider.workId) &&
            manga.sourceMangaId.let { it.isEmpty() || it == ref.mangaId }
    }

    private suspend fun readRemote(session: String): LinkedHashMap<String, Manga> {
        val items = LinkedHashMap<String, Manga>()
        for (index in 0 until MAX_REMOTE_PAGES) {
            requireCurrent(session)
            val page = withTimeoutOrNull(30_000L) { repository.sourceFavorites(provider.sourceId, provider.folder, index) }
                ?: error("收藏读取超时，请重新检查。")
            requireCurrent(session)
            require(page.items.all(::validNative)) {
                "${provider.label}收藏返回了其他来源的作品，已停止同步。"
            }
            val previous = items.size
            page.items.forEach { items[it.id] = it }
            require(items.size <= MAX_REMOTE_ITEMS) { "收藏量超过完整读取上限，未执行同步。" }
            if (!page.hasMore) return items
            require(page.items.isEmpty() || items.size > previous) { "收藏分页重复，无法确认站点完整清单。" }
            require(page.nextPage == null || page.nextPage == index + 1) { "收藏分页顺序异常。" }
        }
        error("收藏未能完整读取，已停止同步。")
    }

    private fun requireCurrent(session: String) {
        check(provider.sourceId in repository.enabledSources(adult = true)) { "请先在成人区启用${provider.label}来源。" }
        check(repository.sourceProbeIdentity(provider.sourceId) == session) { "账号会话已变化，请重新检查。" }
    }

    private suspend fun readSnapshot(): Snapshot {
        library.awaitReady()
        val session = repository.sourceProbeIdentity(provider.sourceId)
        requireCurrent(session)
        val account = repository.sourceAccountState(provider.sourceId)
        require(account.signedIn) { "请先登录${provider.label}账号。" }
        val local = library.favorites.value.filter(::validNative).associateByTo(LinkedHashMap(), Manga::id)
        val remote = readRemote(session)
        val again = repository.sourceAccountState(provider.sourceId)
        require(again.signedIn && again.displayName == account.displayName) { "账号已更换，请重新检查。" }
        requireCurrent(session)
        return Snapshot(account.displayName.ifBlank { "${provider.label}账号" }, session, local, remote)
    }

    private fun publish(value: Snapshot, notice: String? = null) {
        snapshot = value
        val keys = (value.local.keys + value.remote.keys).distinct()
        val differences = keys.mapNotNull { id ->
            val local = value.local[id]
            val cloud = value.remote[id]
            if ((local != null) == (cloud != null)) null
            else AdultFavoriteDifference(local ?: cloud!!, local != null, cloud != null)
        }
        mutableState.value = AdultShelfSyncState(
            owner = value.owner,
            revision = mutableState.value.revision + 1,
            checked = true,
            differences = differences,
            localCount = value.local.size,
            remoteCount = value.remote.size,
            checkedAt = wallTimeMillis(),
            completed = notice,
        )
    }

    fun accountChanged() {
        sequence++
        job?.cancel()
        job = null
        snapshot = null
        mutableState.value = AdultShelfSyncState(revision = mutableState.value.revision + 1)
    }

    fun cancel() {
        sequence++
        job?.cancel()
        job = null
        snapshot = null
        mutableState.value = mutableState.value.copy(checking = false, applying = false,
            checked = false, differences = emptyList(), error = "同步已取消，可重新检查。")
    }

    fun check() {
        val lease = ++sequence
        job?.cancel()
        snapshot = null
        mutableState.value = AdultShelfSyncState(revision = mutableState.value.revision + 1, checking = true)
        job = scope.launch(Dispatchers.IO) {
            operations.withLock {
                try {
                    val fetched = readSnapshot()
                    if (lease == sequence) publish(fetched)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (lease == sequence) mutableState.value = mutableState.value.copy(
                        checking = false, checked = false,
                        error = failure.safeNotice(),
                        loginRequired = sourceFailureSummary(SourceStage.FAVORITES, failure).kind == SourceFailureKind.LOGIN)
                }
            }
        }
    }

    fun apply(revision: Long, selections: Map<String, AdultFavoriteChoice>) {
        val preview = snapshot ?: return
        val current = mutableState.value
        if (!current.checked || current.busy || revision != current.revision || selections.isEmpty()) return
        val allowed = current.differences.associateBy(AdultFavoriteDifference::key)
        val chosen = selections.filterValues { it != AdultFavoriteChoice.SKIP }
        if (chosen.isEmpty() || chosen.any { (id, option) ->
            val row = allowed[id]
            row == null || option !in (if (row.inLocal)
                setOf(AdultFavoriteChoice.UPLOAD_REMOTE, AdultFavoriteChoice.REMOVE_LOCAL)
                else setOf(AdultFavoriteChoice.IMPORT_LOCAL, AdultFavoriteChoice.REMOVE_REMOTE))
        }) return

        val lease = ++sequence
        job?.cancel()
        mutableState.value = current.copy(applying = true, error = null, completed = null)
        job = scope.launch(Dispatchers.IO) {
            operations.withLock {
                var succeeded = 0
                try {
                    // A preview is a proposal, never permission to act on stale remote data.
                    val live = readSnapshot()
                    require(lease == sequence && live.owner == preview.owner &&
                        live.session == preview.session && live.remote.keys == preview.remote.keys &&
                        live.local.keys == preview.local.keys) {
                        "本机或${provider.label}收藏已变化，请重新检查差异。"
                    }
                    for ((id, option) in chosen) {
                        if (lease != sequence) throw CancellationException("同步已取消")
                        val manga = allowed.getValue(id).manga
                        require(validNative(manga)) { "收藏来源身份无效，已停止同步。" }
                        val account = repository.sourceAccountState(provider.sourceId)
                        require(account.signedIn) { "请先登录${provider.label}账号。" }
                        requireCurrent(preview.session)
                        when (option) {
                            AdultFavoriteChoice.IMPORT_LOCAL -> {
                                require(!library.isFavorite(id)) { "本机收藏已变化。" }
                                library.mergeFavorites(listOf(manga))
                            }
                            AdultFavoriteChoice.UPLOAD_REMOTE -> {
                                require(library.isFavorite(id)) { "本机收藏已变化。" }
                                repository.sourceSetFavorite(provider.sourceId, id, provider.folder, true, preview.session)
                            }
                            AdultFavoriteChoice.REMOVE_LOCAL -> {
                                require(library.isFavorite(id)) { "本机收藏已变化。" }
                                library.removeFavorite(id)
                            }
                            AdultFavoriteChoice.REMOVE_REMOTE -> {
                                repository.sourceSetFavorite(provider.sourceId, id, provider.folder, false, preview.session)
                            }
                            AdultFavoriteChoice.SKIP -> Unit
                        }
                        succeeded++
                    }
                    val result = readSnapshot()
                    if (lease == sequence) publish(result, "已应用 $succeeded 项变更。")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (lease == sequence) {
                        snapshot = null
                        mutableState.value = mutableState.value.copy(
                            checking = false, applying = false, checked = false,
                            differences = emptyList(),
                            loginRequired = sourceFailureSummary(SourceStage.FAVORITES, failure).kind == SourceFailureKind.LOGIN,
                            error = (if (succeeded > 0) "已有 $succeeded 项操作完成，其余未确认；请重新检查。 " else "") +
                                failure.safeNotice())
                    }
                }
            }
        }
    }

    private fun Exception.safeNotice(): String = if (this is IllegalArgumentException || this is IllegalStateException)
        message?.takeIf { it.startsWith("请先") || it.startsWith("收藏") || it.startsWith("账号") || it.startsWith("本机") }
            ?: sourceFailureSummary(SourceStage.FAVORITES, this).message
    else sourceFailureSummary(SourceStage.FAVORITES, this).message

    private companion object {
        const val MAX_REMOTE_PAGES = 500
        const val MAX_REMOTE_ITEMS = 20_000
    }
}
