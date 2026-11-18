package dev.mangaflow.account

import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.UnifiedMangaRepository
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Explicit JM-only reconciliation in the adult profile.
 * Read-only checks can run on opening the shelf; writes require row-by-row approval.
 * This controller never accesses the Copy account or includes an ordinary manga.
 */
enum class JmFavoriteChoice { SKIP, IMPORT_LOCAL, UPLOAD_REMOTE, REMOVE_LOCAL, REMOVE_REMOTE }

data class JmFavoriteDifference(
    val manga: Manga,
    val inLocal: Boolean,
    val inJm: Boolean,
) {
    val key: String get() = manga.id
}

data class JmShelfSyncState(
    val owner: String? = null,
    val revision: Long = 0L,
    val checking: Boolean = false,
    val applying: Boolean = false,
    val checked: Boolean = false,
    val differences: List<JmFavoriteDifference> = emptyList(),
    val localCount: Int = 0,
    val remoteCount: Int = 0,
    val error: String? = null,
    val completed: String? = null,
    val checkedAt: Long = 0L,
) {
    val busy: Boolean get() = checking || applying
}

internal class JmShelfSyncController(
    private val repository: UnifiedMangaRepository,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
) {
    private val operations = Mutex()
    private val mutableState = MutableStateFlow(JmShelfSyncState())
    val state: StateFlow<JmShelfSyncState> = mutableState.asStateFlow()
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
        if (manga.sourceId != JM || !SourceKeys.isEncoded(manga.id)) return false
        val ref = runCatching { SourceKeys.decodeManga(manga.id) }.getOrNull() ?: return false
        return ref.sourceId == JM && ref.mangaId.matches(Regex("[0-9]{1,14}")) &&
            manga.sourceMangaId.let { it.isEmpty() || it == ref.mangaId }
    }

    private suspend fun readRemote(): LinkedHashMap<String, Manga> {
        val items = LinkedHashMap<String, Manga>()
        for (index in 0 until MAX_REMOTE_PAGES) {
            val page = withTimeout(30_000L) { repository.sourceFavorites(JM, "0", index) }
            require(page.items.all(::validNative)) {
                "禁漫收藏含有不属于 JM 的作品，已停止同步。"
            }
            val previous = items.size
            page.items.forEach { items[it.id] = it }
            require(items.size <= MAX_REMOTE_ITEMS) { "禁漫收藏量超过安全读取上限，未执行同步。" }
            if (!page.hasMore) return items
            require(items.size > previous) { "禁漫收藏分页重复，无法确认远端完整列表。" }
            require(page.nextPage == null || page.nextPage == index + 1) { "禁漫收藏分页顺序异常。" }
        }
        error("禁漫收藏未能完整读取，已停止同步。")
    }

    private suspend fun readSnapshot(): Snapshot {
        library.awaitReady()
        require(JM in repository.enabledSources()) { "请先在成人区启用禁漫来源。" }
        val account = repository.sourceAccountState(JM)
        require(account.signedIn && account.displayName.isNotBlank()) { "请先登录禁漫账号。" }
        val local = library.favorites.value.filter(::validNative).associateByTo(LinkedHashMap(), Manga::id)
        val remote = readRemote()
        val again = repository.sourceAccountState(JM)
        require(again.signedIn && again.displayName == account.displayName) { "禁漫账号已更换，请重新检查。" }
        return Snapshot(account.displayName, repository.sourceProbeIdentity(JM), local, remote)
    }

    private fun publish(value: Snapshot, notice: String? = null) {
        snapshot = value
        val keys = (value.local.keys + value.remote.keys).distinct()
        val differences = keys.mapNotNull { id ->
            val local = value.local[id]
            val cloud = value.remote[id]
            if ((local != null) == (cloud != null)) null
            else JmFavoriteDifference(local ?: cloud!!, local != null, cloud != null)
        }
        mutableState.value = JmShelfSyncState(
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
        mutableState.value = JmShelfSyncState(revision = mutableState.value.revision + 1)
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
        mutableState.value = JmShelfSyncState(revision = mutableState.value.revision + 1, checking = true)
        job = scope.launch(Dispatchers.IO) {
            operations.withLock {
                try {
                    val fetched = readSnapshot()
                    if (lease == sequence) publish(fetched)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (lease == sequence) mutableState.value = mutableState.value.copy(
                        checking = false, checked = false,
                        error = failure.message?.take(200) ?: "禁漫收藏检查失败。")
                }
            }
        }
    }

    fun apply(revision: Long, selections: Map<String, JmFavoriteChoice>) {
        val preview = snapshot ?: return
        val current = mutableState.value
        if (!current.checked || current.busy || revision != current.revision || selections.isEmpty()) return
        val allowed = current.differences.associateBy(JmFavoriteDifference::key)
        val chosen = selections.filterValues { it != JmFavoriteChoice.SKIP }
        if (chosen.isEmpty() || chosen.any { (id, option) ->
            val row = allowed[id]
            row == null || option !in (if (row.inLocal)
                setOf(JmFavoriteChoice.UPLOAD_REMOTE, JmFavoriteChoice.REMOVE_LOCAL)
                else setOf(JmFavoriteChoice.IMPORT_LOCAL, JmFavoriteChoice.REMOVE_REMOTE))
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
                        "本机收藏或 JM 账号内容已变化，请重新检查差异。"
                    }
                    for ((id, option) in chosen) {
                        if (lease != sequence) throw CancellationException("同步已取消")
                        val manga = allowed.getValue(id).manga
                        require(validNative(manga)) { "收藏来源身份无效，已停止同步。" }
                        val account = repository.sourceAccountState(JM)
                        require(account.signedIn && account.displayName == preview.owner &&
                            repository.sourceProbeIdentity(JM) == preview.session) {
                            "禁漫账号会话已更换，已停止同步。"
                        }
                        when (option) {
                            JmFavoriteChoice.IMPORT_LOCAL -> {
                                require(!library.isFavorite(id)) { "本机收藏已变化。" }
                                library.mergeFavorites(listOf(manga))
                            }
                            JmFavoriteChoice.UPLOAD_REMOTE -> {
                                require(library.isFavorite(id)) { "本机收藏已变化。" }
                                repository.sourceSetFavorite(JM, id, "0", true)
                            }
                            JmFavoriteChoice.REMOVE_LOCAL -> {
                                require(library.isFavorite(id)) { "本机收藏已变化。" }
                                library.removeFavorite(id)
                            }
                            JmFavoriteChoice.REMOVE_REMOTE -> {
                                repository.sourceSetFavorite(JM, id, "0", false)
                            }
                            JmFavoriteChoice.SKIP -> Unit
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
                            error = (if (succeeded > 0) "已有 $succeeded 项操作完成，其余未确认；请重新检查。 " else "") +
                                (failure.message?.take(160) ?: "禁漫同步失败。"))
                    }
                }
            }
        }
    }

    private companion object {
        const val JM = "jmcomic"
        const val MAX_REMOTE_PAGES = 500
        const val MAX_REMOTE_ITEMS = 20_000
    }
}
