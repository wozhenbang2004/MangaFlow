package dev.mangaflow.account

import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.CopyMangaRepository
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.storage.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CopyShelfSyncState(
    val owner: String? = null,
    val hasFullImport: Boolean = false,
    val checking: Boolean = false,
    val importing: Boolean = false,
    val uploading: Boolean = false,
    val checked: Boolean = false,
    val cloudFavorites: List<Manga> = emptyList(),
    val cloudAdded: List<Manga> = emptyList(),
    val toAdd: List<Manga> = emptyList(),
    val toRemove: List<Manga> = emptyList(),
    val localOnlyCount: Int = 0,
    val error: String? = null,
    val message: String? = null,
) {
    val busy: Boolean get() = checking || importing || uploading
}

/** Explicit collection membership only. This controller never reads or writes reading progress. */
class CopyShelfSyncController(
    context: PlatformContext,
    private val accountRepository: CopyAccountRepository,
    private val library: LibraryStore,
    private val repository: MangaRepository,
    private val scope: CoroutineScope,
) {
    // Separate from library backup data: an old restored backup must not restore cloud deletion authority.
    private val disk = preferences(applicationContext(context), "mangaflow_copy_shelf_sync")
    private val mutableState = MutableStateFlow(CopyShelfSyncState())
    val state: StateFlow<CopyShelfSyncState> = mutableState.asStateFlow()
    private var owner: String? = null
    private var session = -1L
    private var generation = 0L
    private var operation: Job? = null
    private var snapshot = emptyList<Manga>()
    private var baseline = Baseline()
    private var localRevision = 0L
    private var previewRevision = -1L
    private var restoreInvalidated = false

    private data class Baseline(
        var imported: Boolean = false,
        val managed: MutableSet<String> = mutableSetOf(),
        var expectedLocal: Set<String> = emptySet(),
        val intents: MutableMap<String, Boolean> = mutableMapOf(),
        val submittedAdds: MutableSet<String> = mutableSetOf(),
    )

    init { accountChanged() }

    fun accountChanged() {
        val nextOwner = accountRepository.profile.value?.username
        val nextSession = accountRepository.sessionVersion
        if (owner == nextOwner && session == nextSession) return
        operation?.cancel()
        generation++
        owner = nextOwner
        session = nextSession
        snapshot = emptyList()
        localRevision++
        previewRevision = -1L
        baseline = if (restoreInvalidated) Baseline() else nextOwner?.let(::readBaseline) ?: Baseline()
        mutableState.value = CopyShelfSyncState(owner = nextOwner)
        val lease = generation
        if (nextOwner != null) scope.launch {
            try {
                library.awaitReady()
                if (!owns(lease)) return@launch
                validateLocal()
                publish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (owns(lease)) mutableState.value = mutableState.value.copy(error = failure.message ?: "收藏同步状态暂时无法恢复。")
            }
        }
    }

    /** The caller invokes this after an actual user favorite operation, never after backup/import. */
    fun onLocalFavoriteChanged(manga: Manga, favorite: Boolean) {
        accountChanged()
        if (owner == null || !nativeCopy(manga)) return
        localRevision++
        if (baseline.imported) {
            // A new add can already be in flight before it becomes a managed cloud item.
            // Its later local cancellation must replace the old true intent as well.
            baseline.intents[manga.id] = favorite
            baseline.expectedLocal = localCopyIds()
            try { persist() } catch (failure: Exception) {
                mutableState.value = mutableState.value.copy(error = failure.message ?: "收藏更改尚未保存到同步队列。")
            }
        }
        publish()
    }

    fun check() = runOperation(checking = true) { lease ->
        library.awaitReady()
        requireOwner(lease)
        validateLocal()
        snapshot = accountRepository.completeFavorites()
        requireOwner(lease)
        reconcileSubmittedAdds(snapshot)
        previewRevision = localRevision
        publish(checked = true)
    }

    fun prepareUpload() = check()

    /** Library restoration must never resurrect deletion authority, even if its IDs look unchanged. */
    fun invalidateAfterRestore() {
        operation?.cancel()
        generation++
        localRevision++
        previewRevision = -1L
        snapshot = emptyList()
        baseline = Baseline()
        restoreInvalidated = true
        val cleared = runCatching { disk.edit().clear().commit() }.getOrDefault(false)
        if (cleared) restoreInvalidated = false
        mutableState.value = CopyShelfSyncState(owner = owner,
            message = "本机备份已恢复，请重新完整导入拷贝收藏后再提交更改",
            error = if (cleared) null else "尚未清理旧同步状态，请检查存储空间后重新完整导入。")
    }

    /** An interrupted write is re-read before the next submission, never blindly replayed. */
    fun cancel() {
        operation?.cancel()
        mutableState.value = mutableState.value.copy(message = "已停止本次同步，已保存的本机收藏会保留")
    }

    fun importFavorites() = runOperation(importing = true) { lease ->
        library.awaitReady()
        requireOwner(lease)
        if (restoreInvalidated) {
            if (!runCatching { disk.edit().clear().commit() }.getOrDefault(false)) {
                throw SourceException("尚未清理备份恢复前的同步状态，请检查本机存储空间。")
            }
            restoreInvalidated = false
        }
        validateLocal()
        val remote = accountRepository.completeFavorites()
        requireOwner(lease)
        val initial = !baseline.imported
        val before = library.favorites.value.filter(::nativeCopy).associateBy(Manga::id)
        // Keep an explicit local removal, and persist the merged shelf once rather than once per book.
        val eligible = remote.filter { baseline.intents[it.id] != false }
        requireOwner(lease)
        val added = library.mergeFavorites(eligible)
        if (!library.flushPendingWrites()) throw SourceException("收藏已合入本机，但尚未确认保存；请重试完整导入。")
        requireOwner(lease)
        if (initial) {
            baseline.intents.clear()
            val remoteIds = remote.mapTo(mutableSetOf(), Manga::id)
            before.keys.filterNot { it in remoteIds }.forEach { baseline.intents[it] = true }
        }
        baseline.managed.addAll(remote.map(Manga::id))
        baseline.expectedLocal = localCopyIds()
        baseline.imported = true
        snapshot = remote
        reconcileSubmittedAdds(remote)
        persist()
        previewRevision = localRevision
        publish(checked = true, message = if (added == 0) "拷贝收藏已完整导入，本机原有收藏已保留" else "已导入 $added 本，本机原有收藏已保留")
    }

    fun upload(selectedRemovalIds: Set<String>) = runOperation(uploading = true) { lease ->
        library.awaitReady()
        requireOwner(lease)
        if (!validateLocal() || !baseline.imported) throw SourceException("请先完整导入当前账号的拷贝收藏。")
        if (!mutableState.value.checked || previewRevision != localRevision) {
            throw SourceException("本机书架已变化，请重新检查并确认同步内容。")
        }
        val previewCloudIds = snapshot.mapTo(mutableSetOf(), Manga::id)
        val expectedRevision = localRevision
        // Re-read before mutation. Cloud additions absent locally never become deletion requests.
        val remote = accountRepository.completeFavorites()
        requireOwner(lease)
        snapshot = remote
        reconcileSubmittedAdds(remote)
        publish(checked = true)
        if (expectedRevision != localRevision || remote.mapTo(mutableSetOf(), Manga::id) != previewCloudIds) {
            previewRevision = localRevision
            throw SourceException("书架在检查期间发生变化，请确认更新后的列表再同步。")
        }
        val additions = mutableState.value.toAdd
        val removals = mutableState.value.toRemove.filter { it.id in selectedRemovalIds }
        var succeeded = 0
        val failures = mutableListOf<String>()
        for ((manga, favorite) in additions.map { it to true } + removals.map { it to false }) {
            requireOwner(lease)
            if (!baseline.imported) throw SourceException("收藏同步状态已失效，请先重新完整导入。")
            // Edits made while the preview/request was active must not be replayed as stale intent.
            if (baseline.intents[manga.id] != favorite || library.isFavorite(manga.id) != favorite) continue
            try {
                val comicUuid = manga.sourceUuid.ifBlank {
                    when (repository) {
                        is UnifiedMangaRepository -> repository.comicUuid(manga.id)
                        is CopyMangaRepository -> repository.comicUuid(manga.id)
                        else -> repository.detail(manga.id).manga.takeIf { nativeCopy(it) && it.id == manga.id }?.sourceUuid.orEmpty()
                    }
                }
                requireOwner(lease)
                if (comicUuid.isBlank()) {
                    throw SourceException("未确认拷贝作品身份，已保留本机收藏。")
                }
                if (baseline.intents[manga.id] != favorite || library.isFavorite(manga.id) != favorite) continue
                if (!baseline.imported) throw SourceException("收藏同步状态已失效，请先重新完整导入。")
                if (favorite) {
                    // Persist before sending: after cancellation, a later read can reconcile an
                    // add that reached the server without returning its confirmation to this app.
                    baseline.submittedAdds += manga.id
                    persist()
                }
                val confirmed = accountRepository.setFavorite(manga.id, comicUuid, favorite)
                requireOwner(lease)
                if (!confirmed) throw SourceException("拷贝尚未确认这项收藏变更。")
                if (baseline.intents[manga.id] == favorite) baseline.intents.remove(manga.id)
                if (favorite) {
                    baseline.managed += manga.id
                    baseline.submittedAdds -= manga.id
                    snapshot = (snapshot.filterNot { it.id == manga.id } + manga)
                } else {
                    baseline.managed -= manga.id
                    baseline.submittedAdds -= manga.id
                    snapshot = snapshot.filterNot { it.id == manga.id }
                }
                baseline.expectedLocal = localCopyIds()
                persist()
                succeeded++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!baseline.imported) throw failure
                failures += manga.title + "：" + (failure.message ?: "尚未确认成功")
            }
        }
        // Already absent removals/already present additions are confirmed by the fresh full read.
        val remoteIds = snapshot.mapTo(mutableSetOf(), Manga::id)
        baseline.intents.entries.toList().forEach { (id, desired) ->
            if (desired && id in remoteIds || !desired && id !in remoteIds) baseline.intents.remove(id)
        }
        baseline.expectedLocal = localCopyIds()
        persist()
        previewRevision = localRevision
        publish(checked = true,
            message = if (failures.isEmpty()) "已同步 $succeeded 项收藏变更" else "已完成 $succeeded 项，${failures.size} 项未完成，可重试",
            error = if (failures.isEmpty()) null else "未完成：" + failures.take(5).joinToString("、"))
    }

    private fun runOperation(checking: Boolean = false, importing: Boolean = false, uploading: Boolean = false,
        block: suspend (Long) -> Unit) {
        accountChanged()
        if (owner == null) { mutableState.value = mutableState.value.copy(error = "请先连接拷贝账号。"); return }
        if (operation?.isActive == true) return
        val lease = generation
        mutableState.value = mutableState.value.copy(checking = checking, importing = importing,
            uploading = uploading, error = null, message = null)
        operation = scope.launch {
            try { block(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (owns(lease)) mutableState.value = mutableState.value.copy(error = failure.message ?: "收藏同步未完成，请重试。")
            } finally {
                if (owns(lease)) {
                    publish()
                    mutableState.value = mutableState.value.copy(checking = false, importing = false, uploading = false)
                }
            }
        }
    }

    private fun localCopyIds() = library.favorites.value.filter(::nativeCopy).mapTo(mutableSetOf(), Manga::id)

    private fun reconcileSubmittedAdds(remote: List<Manga>) {
        val confirmed = remote.map(Manga::id).filter { it in baseline.submittedAdds }
        if (confirmed.isEmpty()) return
        baseline.managed.addAll(confirmed)
        baseline.submittedAdds.removeAll(confirmed.toSet())
        persist()
    }

    /** A restore/unobserved bulk mutation is not evidence that the user deleted cloud books. */
    private fun validateLocal(): Boolean {
        if (!baseline.imported || baseline.expectedLocal == localCopyIds()) return true
        baseline = Baseline(expectedLocal = localCopyIds())
        persist()
        mutableState.value = mutableState.value.copy(error = "本机书架发生批量变化，请重新完整导入后再向拷贝同步。")
        return false
    }

    private fun publish(checked: Boolean = mutableState.value.checked,
        message: String? = mutableState.value.message, error: String? = mutableState.value.error) {
        val local = library.favorites.value
        val copy = local.filter(::nativeCopy).associateBy(Manga::id)
        val cloud = snapshot.associateBy(Manga::id)
        mutableState.value = mutableState.value.copy(owner = owner, hasFullImport = baseline.imported,
            checked = checked, cloudFavorites = snapshot,
            cloudAdded = snapshot.filter { it.id !in copy && baseline.intents[it.id] != false },
            toAdd = copy.values.filter { baseline.intents[it.id] == true && it.id !in cloud },
            toRemove = snapshot.filter { it.id in baseline.managed && baseline.intents[it.id] == false && it.id !in copy },
            localOnlyCount = local.count { !nativeCopy(it) }, message = message, error = error)
    }

    private fun owns(lease: Long) = generation == lease && owner != null &&
        accountRepository.sessionVersion == session && accountRepository.profile.value?.username == owner

    private suspend fun requireOwner(lease: Long) {
        currentCoroutineContext().ensureActive()
        if (!owns(lease)) throw CancellationException("拷贝账号已变更")
    }

    private fun storageKey(name: String) = "account:${name.length}:$name"

    private fun readBaseline(name: String): Baseline = runCatching {
        val json = JSONObject(disk.getString(storageKey(name), "{}") ?: "{}")
        fun strings(key: String): Set<String> = json.optJSONArray(key)?.let { a ->
            (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet()
        }.orEmpty()
        val intents = mutableMapOf<String, Boolean>()
        json.optJSONObject("intents")?.let { entries ->
            val keys = entries.keys()
            while (keys.hasNext()) { val id = keys.next(); (entries.opt(id) as? Boolean)?.let { intents[id] = it } }
        }
        Baseline(json.optBoolean("imported", false), strings("managed").toMutableSet(), strings("local"), intents,
            strings("submittedAdds").toMutableSet())
    }.getOrDefault(Baseline())

    private fun persist() {
        val name = owner ?: return
        try {
            val intents = JSONObject()
            baseline.intents.forEach { (id, desired) -> intents.put(id, desired) }
            val json = JSONObject().put("imported", baseline.imported)
                .put("managed", JSONArray(baseline.managed.sorted())).put("local", JSONArray(baseline.expectedLocal.sorted()))
                .put("intents", intents).put("submittedAdds", JSONArray(baseline.submittedAdds.sorted()))
            if (disk.edit().putString(storageKey(name), json.toString()).commit()) return
        } catch (_: Exception) {
            // False returns and thrown storage/encoding failures invalidate the same authority.
        }
        baseline.imported = false
        previewRevision = -1L
        mutableState.value = mutableState.value.copy(hasFullImport = false, checked = false, message = null)
        throw SourceException("尚未保存收藏同步状态，请检查本机可用空间后重新导入。")
    }

    private fun nativeCopy(manga: Manga) = manga.sourceId == "copy" && !SourceKeys.isEncoded(manga.id) &&
        manga.sourceUrl != LOCAL_SOURCE_URL && COPY_ID.matches(manga.id)

    private companion object { val COPY_ID = Regex("[A-Za-z0-9_-]{1,160}") }
}
