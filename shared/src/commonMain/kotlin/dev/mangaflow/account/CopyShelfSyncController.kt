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
import dev.mangaflow.storage.SavedResumeTarget
import dev.mangaflow.storage.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CopySyncDifferenceKind { FAVORITE, PROGRESS }
enum class CopyMergeChoice { USE_LOCAL, USE_COPY }

data class CopySyncDifference(
    val key: String,
    val kind: CopySyncDifferenceKind,
    val manga: Manga,
    val localValue: String,
    val cloudValue: String,
    val localActionLabel: String,
    val cloudActionLabel: String,
    val canUseLocal: Boolean,
    val canUseCopy: Boolean,
    val detail: String? = null,
    val reviewKey: String = "",
)

data class CopyShelfSyncState(
    val owner: String? = null,
    val hasFullImport: Boolean = false,
    val checking: Boolean = false,
    val importing: Boolean = false,
    val uploading: Boolean = false,
    val checked: Boolean = false,
    val favoritesReady: Boolean = false,
    val progressReady: Boolean = false,
    val favoritesError: String? = null,
    val progressError: String? = null,
    val mergeCompleted: Int = 0,
    val mergeTotal: Int = 0,
    val cloudFavorites: List<Manga> = emptyList(),
    val cloudAdded: List<Manga> = emptyList(),
    val toAdd: List<Manga> = emptyList(),
    val toRemove: List<Manga> = emptyList(),
    val localOnlyCount: Int = 0,
    val differences: List<CopySyncDifference> = emptyList(),
    val revision: Long = 0L,
    val progressUnavailableCount: Int = 0,
    val error: String? = null,
    val message: String? = null,
    val projecting: Boolean = false,
) {
    val busy: Boolean get() = checking || importing || uploading || projecting
}

/** Read-only comparison; every merge is explicit and cloud reading is written only by the reader. */
class CopyShelfSyncController(
    context: PlatformContext,
    private val accountRepository: CopyAccountRepository,
    private val library: LibraryStore,
    private val repository: MangaRepository,
    private val scope: CoroutineScope,
) {
    // Separate from library backup data: an old restored backup must not restore cloud deletion authority.
    private val disk by lazy { preferences(applicationContext(context), "mangaflow_copy_shelf_sync") }
    private val mutableState = MutableStateFlow(CopyShelfSyncState())
    val state: StateFlow<CopyShelfSyncState> = mutableState.asStateFlow()
    private var owner: String? = null
    private var session = -1L
    private var generation = 0L
    private var operation: Job? = null
    private var operationId = 0L
    private var backgroundCheckOperation = false
    private var snapshot = emptyList<Manga>()
    private var remoteProgress = emptyMap<String, CloudHistoryEntry>()
    private var observedProgress = emptyMap<String, CloudHistoryEntry>()
    private var verifiedProgressIds = emptySet<String>()
    private var differenceSignature = ""
    private var projectionJob: Job? = null
    private var projectionVersion = 0L
    private var evidenceRevision = 0L
    private val projectionGate = Mutex()
    private val persistenceGate = Mutex()
    private val mutationGate = Mutex()
    private var persistedImported = false
    private var localValidated = false
    private var restoration: Job? = null
    private var restored = CompletableDeferred(Unit)
    private var localChanges: Job? = null
    private var lastHistory: List<ReadingProgress>? = null
    private var lastTargets: Map<String, SavedResumeTarget>? = null
    private var lastFavorites: List<Manga>? = null
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
        val reviewedProgress: MutableMap<String, String> = mutableMapOf(),
    )

    init { accountChanged() }

    fun accountChanged() {
        val nextOwner = accountRepository.profile.value?.username
        val nextSession = accountRepository.sessionVersion
        if (owner == nextOwner && session == nextSession) return
        operation?.cancel()
        restoration?.cancel()
        localChanges?.cancel()
        projectionJob?.cancel()
        operationId++
        projectionVersion++
        operation = null
        localChanges = null
        backgroundCheckOperation = false
        generation++
        owner = nextOwner
        session = nextSession
        snapshot = emptyList()
        remoteProgress = emptyMap()
        observedProgress = emptyMap()
        verifiedProgressIds = emptySet()
        differenceSignature = ""
        lastHistory = null; lastTargets = null; lastFavorites = null
        localRevision++
        previewRevision = -1L
        baseline = Baseline()
        persistedImported = false
        localValidated = false
        mutableState.value = CopyShelfSyncState(owner = nextOwner)
        val ready = CompletableDeferred<Unit>()
        restored = ready
        val lease = generation
        if (nextOwner == null) { ready.complete(Unit); return }
        restoration = scope.launch {
            try {
                val loaded = if (restoreInvalidated) Baseline() else withContext(Dispatchers.IO) {
                    persistenceGate.withLock { readBaseline(nextOwner) }
                }
                requireOwner(lease)
                evidenceRevision++
                baseline = loaded
                persistedImported = loaded.imported
                ready.complete(Unit)
                library.awaitReady()
                awaitLocalChanges(lease)
                mutationGate.withLock { requireOwner(lease); validateLocal(); publish() }
            } catch (cancelled: CancellationException) {
                ready.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                ready.complete(Unit)
                if (owns(lease)) mutableState.value = mutableState.value.copy(error = failure.message ?: "收藏同步状态暂时无法恢复。")
            }
        }
    }

    /** Capture a real local tap immediately; durable bookkeeping is serialized away from input. */
    fun onLocalFavoriteChanged(manga: Manga, favorite: Boolean) {
        accountChanged()
        if (owner == null || !nativeCopy(manga)) return
        val lease = generation
        val previous = localChanges
        localRevision++
        localChanges = scope.launch {
            previous?.join()
            restored.await()
            library.awaitReady()
            requireOwner(lease)
            mutationGate.withLock {
                requireOwner(lease)
                if (baseline.imported) {
                    evidenceRevision++
                    baseline.intents[manga.id] = favorite
                    baseline.expectedLocal = localCopyIds()
                    try { persist() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        if (owns(lease)) mutableState.value = mutableState.value.copy(error = failure.message ?: "收藏更改尚未保存到同步队列。")
                    }
                }
                if (owns(lease)) publish()
            }
        }
    }

    private suspend fun awaitLocalChanges(lease: Long) {
        while (true) {
            val pending = localChanges
            pending?.join()
            requireOwner(lease)
            if (pending === localChanges) return
        }
    }

    fun check(kind: CopySyncDifferenceKind? = null) = checkSnapshot(background = false, kind = kind)
    fun checkInBackground() = checkSnapshot(background = true)

    private fun checkSnapshot(background: Boolean, kind: CopySyncDifferenceKind? = null) =
        runOperation(checking = true, background = background) { lease ->
            library.awaitReady()
            requireOwner(lease)
            validateLocal()
            val favorites = kind != CopySyncDifferenceKind.PROGRESS
            val progress = kind != CopySyncDifferenceKind.FAVORITE
            val remote = accountRepository.completeSyncSnapshot(includeFavorites = favorites, includeProgress = progress) { partial, domain ->
                requireOwner(lease)
                acceptSnapshot(partial, domain == CopyAccountSyncDomain.FAVORITES, domain == CopyAccountSyncDomain.PROGRESS)
                publish(checked = true)
            }
            requireOwner(lease)
            acceptSnapshot(remote, favorites, progress)
            previewRevision = localRevision
            publish(checked = true)
        }

    /** Recheck exactly one known Copy work without treating an absent cursor as unread. */
    fun checkProgress(manga: Manga) {
        if (!nativeCopy(manga)) return
        runOperation(checking = true) { lease ->
            library.awaitReady()
            requireOwner(lease)
            try {
                val current = accountRepository.cloudProgress(manga.id)
                requireOwner(lease)
                evidenceRevision++
                if (current != null && current.manga.id == manga.id && nativeCopy(current.manga) &&
                    nativeChapter(current.chapterId)) {
                    val previous = remoteProgress[manga.id]?.takeIf { it.chapterId == current.chapterId }
                    val value = current.copy(manga = manga,
                        chapterTitle = current.chapterTitle.ifBlank { previous?.chapterTitle.orEmpty() })
                    observedProgress = observedProgress + (manga.id to value)
                    remoteProgress = remoteProgress + (manga.id to value)
                    verifiedProgressIds = verifiedProgressIds + manga.id
                    publish(checked = true, message = "已重新核对这部作品的拷贝进度。")
                } else {
                    verifiedProgressIds = verifiedProgressIds - manga.id
                    publish(checked = true, message = "拷贝未返回这部作品可比较的章节，上次进度仅供查看；这不表示未读。")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                requireOwner(lease)
                evidenceRevision++
                verifiedProgressIds = verifiedProgressIds - manga.id
                publish(checked = true, error = failure.message ?: "这部作品的拷贝进度尚未重新核实，请重试。")
            }
        }
    }

    private suspend fun acceptSnapshot(remote: CopyAccountSyncSnapshot, favorites: Boolean, progress: Boolean) {
        evidenceRevision++
        if (favorites && remote.favoritesReady) {
            snapshot = remote.favorites
            reconcileSubmittedAdds(snapshot)
        }
        if (progress) {
            verifiedProgressIds = if (remote.progressReady) remote.progress.keys else emptySet()
            if (remote.progressReady) remoteProgress = observedProgress + remote.progress
        }
        mutableState.value = mutableState.value.copy(
            favoritesReady = if (favorites) remote.favoritesReady else mutableState.value.favoritesReady,
            progressReady = if (progress) remote.progressReady else mutableState.value.progressReady,
            favoritesError = if (favorites) remote.favoritesError else mutableState.value.favoritesError,
            progressError = if (progress) remote.progressError else mutableState.value.progressError,
        )
    }

    private suspend fun readCompleteFavorites(lease: Long): List<Manga> {
        return try {
            val favorites = accountRepository.completeFavorites()
            requireOwner(lease)
            evidenceRevision++
            snapshot = favorites
            mutableState.value = mutableState.value.copy(favoritesReady = true, favoritesError = null)
            favorites
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            requireOwner(lease)
            evidenceRevision++
            mutableState.value = mutableState.value.copy(favoritesReady = false,
                favoritesError = failure.message ?: "拷贝收藏暂时无法读取，请重试。")
            throw failure
        }
    }

    fun prepareUpload() = check()

    /** Local state changes update the preview without issuing account requests. */
    fun onLocalReadingChanged() {
        accountChanged()
        if (owner == null) return
        val history = library.history.value
        val targets = library.resumeTargets.value
        val favorites = library.favorites.value
        if (history === lastHistory && targets === lastTargets && favorites === lastFavorites) return
        lastHistory = history; lastTargets = targets; lastFavorites = favorites
        localRevision++
        publish()
    }

    /** Reuse the current detail's already validated, read-only account response. */
    fun observeCloudProgress(manga: Manga, chapterId: String, chapterTitle: String) {
        accountChanged()
        if (owner == null || !nativeCopy(manga) || !nativeChapter(chapterId)) return
        evidenceRevision++
        val entry = CloudHistoryEntry(manga, chapterId, chapterTitle.take(160))
        observedProgress = observedProgress + (manga.id to entry)
        remoteProgress = remoteProgress + (manga.id to entry)
        verifiedProgressIds = verifiedProgressIds + manga.id
        publish(checked = true)
    }

    /** The foreground owner may yield a passive check without interrupting explicit merges. */
    fun pauseCheck() {
        if (!backgroundCheckOperation || !mutableState.value.checking) return
        operationId++
        operation?.cancel()
        operation = null
        backgroundCheckOperation = false
        mutableState.value = mutableState.value.copy(checking = false)
    }

    /** Library restoration must never resurrect deletion authority, even if its IDs look unchanged. */
    fun invalidateAfterRestore() {
        operation?.cancel()
        operationId++
        operation = null
        backgroundCheckOperation = false
        generation++
        localRevision++
        previewRevision = -1L
        snapshot = emptyList()
        remoteProgress = emptyMap()
        observedProgress = emptyMap()
        verifiedProgressIds = emptySet()
        differenceSignature = ""
        baseline = Baseline()
        persistedImported = false
        localValidated = false
        restoreInvalidated = true
        restoration?.cancel()
        localChanges?.cancel()
        projectionJob?.cancel()
        localChanges = null
        val lease = generation
        val ready = CompletableDeferred<Unit>()
        restored = ready
        mutableState.value = CopyShelfSyncState(owner = owner,
            message = "本机备份已恢复，请重新完整导入拷贝收藏后再提交更改")
        restoration = scope.launch {
            try {
                val cleared = withContext(Dispatchers.IO) { persistenceGate.withLock { disk.edit().clear().commit() } }
                requireOwner(lease)
                if (cleared) restoreInvalidated = false
                else mutableState.value = mutableState.value.copy(error = "尚未清理旧同步状态，请检查存储空间后重新完整导入。")
            } catch (cancelled: CancellationException) { throw cancelled }
            finally { ready.complete(Unit) }
        }
    }

    /** An interrupted write is re-read before the next submission, never blindly replayed. */
    fun cancel() {
        if (operation?.isActive != true && mutableState.value.projecting) {
            projectionJob?.cancel()
            projectionVersion++
            mutableState.value = mutableState.value.copy(projecting = false, checked = false,
                message = "已停止整理差异，可重新检查后继续。")
            return
        }
        val interruptedMerge = mutableState.value.uploading
        operation?.cancel()
        operationId++
        operation = null
        backgroundCheckOperation = false
        if (interruptedMerge) { evidenceRevision++; verifiedProgressIds = emptySet() }
        mutableState.value = mutableState.value.copy(checking = false, importing = false, uploading = false,
            favoritesReady = mutableState.value.favoritesReady && !interruptedMerge,
            progressReady = mutableState.value.progressReady && !interruptedMerge,
            message = "已停止本次同步，已保存的本机收藏与阅读记录会保留")
        publish()
    }

    fun importFavorites() = runOperation(importing = true) { lease ->
        library.awaitReady()
        requireOwner(lease)
        if (restoreInvalidated) {
            if (!withContext(Dispatchers.IO) { persistenceGate.withLock { disk.edit().clear().commit() } }) {
                throw SourceException("尚未清理备份恢复前的同步状态，请检查本机存储空间。")
            }
            requireOwner(lease)
            restoreInvalidated = false
        }
        validateLocal()
        val remote = readCompleteFavorites(lease)
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
        evidenceRevision++
        snapshot = remote
        reconcileSubmittedAdds(remote)
        persist()
        previewRevision = localRevision
        publish(checked = true, message = if (added == 0) "拷贝收藏已完整导入，本机原有收藏已保留" else "已导入 $added 本，本机原有收藏已保留")
    }

    fun upload(selectedRemovalIds: Set<String>) = runOperation(uploading = true) { lease ->
        library.awaitReady()
        requireOwner(lease)
        if (!validateLocal() || !hasImportedAuthority) throw SourceException("请先完整导入当前账号的拷贝收藏。")
        if (!mutableState.value.checked || !mutableState.value.favoritesReady || previewRevision != localRevision) {
            throw SourceException("本机书架已变化，请重新检查并确认同步内容。")
        }
        val previewCloudIds = snapshot.mapTo(mutableSetOf(), Manga::id)
        val expectedRevision = localRevision
        // Re-read before mutation. Cloud additions absent locally never become deletion requests.
        val remote = readCompleteFavorites(lease)
        requireOwner(lease)
        evidenceRevision++
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
            if (!hasImportedAuthority) throw SourceException("收藏同步状态已失效，请先重新完整导入。")
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
                if (!hasImportedAuthority) throw SourceException("收藏同步状态已失效，请先重新完整导入。")
                if (favorite) {
                    // Persist before sending: after cancellation, a later read can reconcile an
                    // add that reached the server without returning its confirmation to this app.
                    baseline.submittedAdds += manga.id
                    persist()
                }
                requireFavoriteIntent(lease, manga.id, favorite)
                val confirmed = accountRepository.setFavorite(manga.id, comicUuid, favorite) {
                    withContext(Dispatchers.Main.immediate) { requireFavoriteIntent(lease, manga.id, favorite) }
                }
                requireOwner(lease)
                if (!confirmed) throw SourceException("拷贝尚未确认这项收藏变更。")
                if (baseline.intents[manga.id] == favorite) baseline.intents.remove(manga.id)
                evidenceRevision++
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
                if (!hasImportedAuthority) throw failure
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

    /** The preview revision and every selected field are revalidated before an explicit merge. */
    fun applyMerge(revision: Long, choices: Map<String, CopyMergeChoice>) = runOperation(uploading = true) { lease ->
        library.awaitReady()
        requireOwner(lease)
        if (!mutableState.value.checked || revision != mutableState.value.revision) {
            throw SourceException("差异已变化，请重新查看后选择。")
        }
        if (choices.isEmpty()) return@runOperation
        val original = differences().associateBy { it.row.key }
        val selected = choices.map { (key, choice) ->
            val evidence = original[key] ?: throw SourceException("所选差异已变化，请重新查看。")
            if (choice == CopyMergeChoice.USE_LOCAL && !evidence.row.canUseLocal ||
                choice == CopyMergeChoice.USE_COPY && !evidence.row.canUseCopy) {
                throw SourceException("所选操作当前不可用，请先完整导入或重新检查。")
            }
            evidence to choice
        }.sortedWith(compareBy({ it.first.row.kind.ordinal }, { it.first.row.key }))
        validateLocal()
        mutableState.value = mutableState.value.copy(mergeTotal = selected.size, mergeCompleted = 0)
        val favorites = selected.any { it.first.row.kind == CopySyncDifferenceKind.FAVORITE }
        val progress = selected.any { it.first.row.kind == CopySyncDifferenceKind.PROGRESS }
        val unselectedProgress = verifiedProgressIds - selected.filter {
            it.first.row.kind == CopySyncDifferenceKind.PROGRESS
        }.map { it.first.row.manga.id }.toSet()
        val fresh = accountRepository.completeSyncSnapshot(includeFavorites = favorites, includeProgress = progress)
        requireOwner(lease)
        acceptSnapshot(fresh, favorites, progress)
        // This merge only authorizes the selected works; a failed history fallback
        // does not revoke an unrelated work's previously verified detail observation.
        if (progress && !fresh.progressReady) {
            evidenceRevision++
            verifiedProgressIds = verifiedProgressIds + unselectedProgress
        }
        val preflightFailures = mutableMapOf<String, String>()
        selected.forEach { (evidence, _) ->
            if (evidence.row.kind == CopySyncDifferenceKind.FAVORITE && !fresh.favoritesReady) {
                preflightFailures[evidence.row.key] = fresh.favoritesError ?: "拷贝收藏尚未重新核实。"
            }
            if (evidence.row.kind == CopySyncDifferenceKind.PROGRESS && !fresh.progressReady) {
                preflightFailures[evidence.row.key] = fresh.progressError ?: "拷贝进度尚未重新核实。"
            }
        }
        // Only explicitly selected works receive individual fallback reads when history
        // is unavailable or missing their cursor; passive checks never fan out per book.
        val progressLookups = selected.filter {
            it.first.row.kind == CopySyncDifferenceKind.PROGRESS &&
                (!fresh.progressReady || it.first.row.manga.id !in fresh.progress)
        }
        progressLookups.forEachIndexed { index, (evidence, _) ->
            val manga = evidence.row.manga
            try {
                val current = accountRepository.cloudProgress(manga.id)
                requireOwner(lease)
                evidenceRevision++
                if (current != null && current.manga.id == manga.id && nativeCopy(current.manga) &&
                    nativeChapter(current.chapterId)) {
                    val previous = evidence.cloudChapter?.takeIf { it.chapterId == current.chapterId }
                    val value = current.copy(manga = manga,
                        chapterTitle = current.chapterTitle.ifBlank { previous?.chapterTitle.orEmpty() })
                    observedProgress = observedProgress + (manga.id to value)
                    remoteProgress = remoteProgress + (manga.id to value)
                    verifiedProgressIds = verifiedProgressIds + manga.id
                    preflightFailures.remove(evidence.row.key)
                } else {
                    verifiedProgressIds = verifiedProgressIds - manga.id
                    preflightFailures[evidence.row.key] = "拷贝未返回可比较的章节，上次进度仅供查看；这不表示未读。"
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                requireOwner(lease)
                evidenceRevision++
                verifiedProgressIds = verifiedProgressIds - manga.id
                preflightFailures[evidence.row.key] = failure.message ?: "这一部的拷贝进度尚未重新核实。"
            }
            mutableState.value = mutableState.value.copy(
                message = "正在核对所选进度 ${index + 1}/${progressLookups.size}")
        }
        publish(checked = true, message = null)
        var succeeded = 0
        val failures = mutableListOf<String>()
        for ((evidence, choice) in selected) {
            requireOwner(lease)
            try {
                preflightFailures[evidence.row.key]?.let { throw SourceException(it) }
                if (!mutableState.value.checked) throw SourceException("同步状态已失效，请重新检查。")
                val actual = differences(includeReviewed = true).firstOrNull { it.row.key == evidence.row.key }
                if (actual == null || actual.fingerprint != evidence.fingerprint || actual.localSnapshot != evidence.localSnapshot ||
                    choice == CopyMergeChoice.USE_LOCAL && !actual.row.canUseLocal ||
                    choice == CopyMergeChoice.USE_COPY && !actual.row.canUseCopy) {
                    throw SourceException("这一项已变化，请重新比较。")
                }
                when (evidence.row.kind) {
                    CopySyncDifferenceKind.FAVORITE -> if (choice == CopyMergeChoice.USE_LOCAL) {
                        if (!hasImportedAuthority || !validateLocal()) throw SourceException("请先完整导入当前账号的拷贝收藏。")
                        writeRemoteFavorite(evidence, lease)
                    } else {
                        val manga = evidence.row.manga
                        if (evidence.cloudFavorite == true) library.mergeFavorites(listOf(manga))
                        else {
                            // Membership and continuation are separate merge fields.
                            val target = library.resumeTargets.value[manga.id]
                            library.removeFavorite(manga.id)
                            if (target != null) library.adoptResumeTarget(target)
                        }
                        if (!library.flushPendingWrites()) throw SourceException("本机收藏已更改，尚未确认保存，请重新检查。")
                        requireOwner(lease)
                        if (library.isFavorite(manga.id) != evidence.cloudFavorite) {
                            throw SourceException("本机收藏在保存期间发生变化，已保留新的操作，请重新比较。")
                        }
                        baseline.intents.remove(manga.id)
                        if (evidence.cloudFavorite == true) baseline.managed += manga.id else baseline.managed -= manga.id
                        baseline.expectedLocal = localCopyIds()
                        localRevision++
                        persist()
                    }
                    CopySyncDifferenceKind.PROGRESS -> {
                        val manga = evidence.row.manga
                        if (choice == CopyMergeChoice.USE_COPY) {
                            val chapter = requireNotNull(evidence.cloudChapter)
                            library.adoptResumeTarget(SavedResumeTarget(manga.id, chapter.chapterId, chapter.chapterTitle, requireNotNull(owner)))
                            if (!library.flushPendingWrites()) throw SourceException("继续阅读目标已更新，尚未确认保存，请重新检查。")
                            requireOwner(lease)
                            val saved = library.resumeTargets.value[manga.id]
                            if (saved == null || saved.accountOwner != owner || saved.chapterId != chapter.chapterId) {
                                throw SourceException("本机阅读状态已变化，已保留新的记录，请重新比较。")
                            }
                            baseline.reviewedProgress.remove(manga.id)
                        } else {
                            // Keep exactly the continuation shown on the local side, including
                            // a previously chosen cloud target; this is only an acknowledgement.
                            val retained = differences(includeReviewed = true).firstOrNull { it.row.key == evidence.row.key }
                            if (retained == null) baseline.reviewedProgress.remove(manga.id)
                            else baseline.reviewedProgress[manga.id] = retained.fingerprint
                        }
                        localRevision++
                        persist()
                    }
                }
                succeeded++
                publish(checked = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                failures += evidence.row.manga.title + "：" + (failure.message ?: "尚未确认完成")
                if (!mutableState.value.checked) throw failure
            } finally {
                if (owns(lease)) mutableState.value = mutableState.value.copy(
                    mergeCompleted = (mutableState.value.mergeCompleted + 1).coerceAtMost(selected.size))
            }
        }
        previewRevision = localRevision
        publish(checked = true, message = "已应用 $succeeded 项" + if (failures.isEmpty()) "" else "，${failures.size} 项需重新检查",
            error = failures.takeIf { it.isNotEmpty() }?.take(5)?.joinToString("、"))
    }

    private suspend fun writeRemoteFavorite(evidence: DifferenceEvidence, lease: Long) {
        val manga = evidence.row.manga
        val favorite = requireNotNull(evidence.localFavorite)
        val comicUuid = manga.sourceUuid.ifBlank {
            when (repository) {
                is UnifiedMangaRepository -> repository.comicUuid(manga.id)
                is CopyMangaRepository -> repository.comicUuid(manga.id)
                else -> repository.detail(manga.id).manga.takeIf { nativeCopy(it) && it.id == manga.id }?.sourceUuid.orEmpty()
            }
        }
        requireOwner(lease)
        if (comicUuid.isBlank() || !hasImportedAuthority) throw SourceException("尚未确认拷贝作品身份或完整导入状态。")
        if (differences(includeReviewed = true).firstOrNull { it.row.key == evidence.row.key }?.fingerprint != evidence.fingerprint) {
            throw SourceException("本机收藏已变化，请重新比较。")
        }
        baseline.intents[manga.id] = favorite
        baseline.expectedLocal = localCopyIds()
        // The fresh complete read and explicit removal choice establish this managed identity.
        if (evidence.cloudFavorite == true) baseline.managed += manga.id
        if (favorite) baseline.submittedAdds += manga.id
        persist()
        requireFavoriteIntent(lease, manga.id, favorite)
        if (differences(includeReviewed = true).firstOrNull { it.row.key == evidence.row.key }?.fingerprint != evidence.fingerprint)
            throw SourceException("所选收藏差异已变化，请重新比较。")
        if (!accountRepository.setFavorite(manga.id, comicUuid, favorite) {
            withContext(Dispatchers.Main.immediate) { requireFavoriteIntent(lease, manga.id, favorite) }
        }) throw SourceException("拷贝尚未确认这项收藏变更。")
        requireOwner(lease)
        if (baseline.intents[manga.id] == favorite) baseline.intents.remove(manga.id)
        evidenceRevision++
        if (favorite) {
            baseline.managed += manga.id
            snapshot = snapshot.filterNot { it.id == manga.id } + manga
        } else {
            baseline.managed -= manga.id
            snapshot = snapshot.filterNot { it.id == manga.id }
        }
        baseline.submittedAdds -= manga.id
        baseline.expectedLocal = localCopyIds()
        persist()
    }

    private suspend fun requireFavoriteIntent(lease: Long, mangaId: String, favorite: Boolean) {
        requireOwner(lease)
        if (!hasImportedAuthority || baseline.intents[mangaId] != favorite || library.isFavorite(mangaId) != favorite)
            throw SourceException("本机收藏已变化，已停止过期的云端操作，请重新比较。")
    }

    private fun runOperation(checking: Boolean = false, importing: Boolean = false, uploading: Boolean = false,
        background: Boolean = false,
        block: suspend (Long) -> Unit) {
        accountChanged()
        if (owner == null) { mutableState.value = mutableState.value.copy(error = "请先连接拷贝账号。"); return }
        if (operation?.isActive == true) {
            if (!background && backgroundCheckOperation) {
                if (checking) {
                    // Opening the diff promotes the same read; a delayed idle cleanup must not cancel it.
                    backgroundCheckOperation = false
                    return
                }
                pauseCheck()
            } else return
        }
        val lease = generation
        val operationLease = ++operationId
        backgroundCheckOperation = background
        mutableState.value = mutableState.value.copy(checking = checking, importing = importing,
            uploading = uploading, mergeCompleted = 0, mergeTotal = 0, error = null, message = null)
        operation = scope.launch {
            try {
                restored.await()
                awaitLocalChanges(lease)
                while (uploading) {
                    val pending = projectionJob
                    pending?.join()
                    requireOwner(lease)
                    if (pending === projectionJob) break
                }
                mutationGate.withLock { requireOwner(lease); block(lease) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (owns(lease) && operationLease == operationId) mutableState.value = mutableState.value.copy(error = failure.message ?: "拷贝比较或合并未完成，请重试。")
            } finally {
                if (owns(lease) && operationLease == operationId) {
                    publish()
                    mutableState.value = mutableState.value.copy(checking = false, importing = false, uploading = false)
                    backgroundCheckOperation = false
                    operation = null
                }
            }
        }
    }

    private data class DifferenceEvidence(
        val row: CopySyncDifference,
        val fingerprint: String,
        val localFavorite: Boolean? = null,
        val cloudFavorite: Boolean? = null,
        val cloudChapter: CloudHistoryEntry? = null,
        val localSnapshot: String? = null,
    )

    private data class ReadingSide(val chapterId: String, val label: String, val snapshot: String)

    private data class ProjectionInput(
        val owner: String?, val session: Long, val local: List<Manga>, val history: List<ReadingProgress>,
        val targets: Map<String, SavedResumeTarget>, val cloud: List<Manga>,
        val progress: Map<String, CloudHistoryEntry>, val verified: Set<String>,
        val imported: Boolean, val intents: Map<String, Boolean>, val managed: Set<String>,
        val reviewed: Map<String, String>, val favoritesReady: Boolean,
    )

    private fun projectionInput() = ProjectionInput(owner, session, library.favorites.value,
        library.history.value, library.resumeTargets.value, snapshot,
        remoteProgress.toMap(), verifiedProgressIds.toSet(), hasImportedAuthority,
        baseline.intents.toMap(), baseline.managed.toSet(), baseline.reviewedProgress.toMap(),
        mutableState.value.favoritesReady)

    private fun localReading(mangaId: String, input: ProjectionInput,
        history: Map<String, ReadingProgress>): ReadingSide {
        val target = input.targets[mangaId]?.takeIf { it.accountOwner == input.owner }
        val recent = history[mangaId]
        val snapshot = JSONObject()
        if (target != null) {
            snapshot.put("target", target.chapterId).put("title", target.chapterTitle).put("owner", target.accountOwner)
            if (recent != null && recent.chapterId == target.chapterId) snapshot
                .put("page", recent.pageIndex).put("count", recent.pageCount).put("offset", recent.scrollOffset)
                .put("panel", recent.panelIndex).put("layout", recent.panelLayout)
            return ReadingSide(target.chapterId, target.chapterTitle.ifBlank { "已选择章节" }, snapshot.toString())
        }
        if (recent == null) return ReadingSide("", "暂无本机进度", snapshot.toString())
        snapshot.put("chapter", recent.chapterId).put("title", recent.chapterTitle)
            .put("page", recent.pageIndex).put("count", recent.pageCount).put("offset", recent.scrollOffset)
            .put("panel", recent.panelIndex).put("layout", recent.panelLayout)
        return ReadingSide(recent.chapterId, recent.chapterTitle.ifBlank { "已保存章节" } +
            if (recent.pageCount > 0) " · ${recent.pageIndex + 1}/${recent.pageCount}" else "", snapshot.toString())
    }

    /** An old account/session selection cannot match a new account's otherwise identical diff. */
    private fun differenceKey(kind: String, mangaId: String, input: ProjectionInput): String = input.owner.orEmpty().let {
        it.length.toString() + ":" + it + ":" + input.session + ":" + kind + ":" + mangaId
    }

    private fun differenceFingerprint(kind: String, mangaId: String, local: String, cloud: String): String = JSONObject()
        .put("kind", kind).put("manga", mangaId).put("local", local).put("cloud", cloud)
        .toString()

    private suspend fun differences(checked: Boolean = mutableState.value.checked,
        includeReviewed: Boolean = false): List<DifferenceEvidence> {
        val lease = generation
        val revision = localRevision
        val evidence = evidenceRevision
        val input = projectionInput()
        val result = withContext(Dispatchers.Default) { computeDifferences(input, checked, includeReviewed) }
        requireOwner(lease)
        if (revision != localRevision || evidence != evidenceRevision)
            throw SourceException("比较依据已变化，请重新查看差异。")
        return result
    }

    private fun computeDifferences(input: ProjectionInput, checked: Boolean,
        includeReviewed: Boolean = false): List<DifferenceEvidence> {
        val local = input.local.filter(::nativeCopy).associateBy(Manga::id)
        val cloud = input.cloud.filter(::nativeCopy).associateBy(Manga::id)
        val history = input.history.asReversed().filter { nativeCopy(it.manga) }.associateBy { it.manga.id }
        val result = mutableListOf<DifferenceEvidence>()
        if (checked && input.favoritesReady) (local.keys + cloud.keys).sorted().forEach { id ->
            val inLocal = id in local
            val inCloud = id in cloud
            if (inLocal != inCloud) {
                val manga = local[id] ?: cloud.getValue(id)
                val fingerprint = differenceFingerprint("favorite", id, inLocal.toString(), inCloud.toString())
                val row = CopySyncDifference(differenceKey("favorite", id, input), CopySyncDifferenceKind.FAVORITE, manga,
                    if (inLocal) "已收藏" else "未收藏", if (inCloud) "已收藏" else "未收藏",
                    if (inLocal) "加入拷贝" else "移出拷贝", if (inCloud) "加入本机" else "移出本机收藏",
                    canUseLocal = input.imported, canUseCopy = true,
                    detail = if (input.imported) null else "完整导入当前账号收藏后，才能向拷贝提交更改。",
                    reviewKey = fingerprint)
                result += DifferenceEvidence(row, fingerprint,
                    localFavorite = inLocal, cloudFavorite = inCloud)
            }
        }
        input.progress.entries.sortedBy { it.key }.forEach { (id, entry) ->
            if (!nativeCopy(entry.manga) || !nativeChapter(entry.chapterId)) return@forEach
            val reading = localReading(id, input, history)
            if (reading.chapterId == entry.chapterId) return@forEach
            val cloudLabel = entry.chapterTitle.ifBlank { "拷贝已保存章节" }
            val fingerprint = differenceFingerprint("progress", id, reading.chapterId, entry.chapterId)
            if (!includeReviewed && input.reviewed[id] == fingerprint) return@forEach
            val verified = id in input.verified
            val row = CopySyncDifference(differenceKey("progress", id, input), CopySyncDifferenceKind.PROGRESS,
                local[id] ?: cloud[id] ?: entry.manga, reading.label, cloudLabel,
                "保留本机", "采用拷贝", canUseLocal = verified, canUseCopy = verified,
                detail = if (verified) "拷贝提供章节进度；每章页内位置保留在本机。"
                    else "此进度来自上次读取，重查本书后再选择。",
                reviewKey = JSONObject().put("comparison", fingerprint).put("localSnapshot", reading.snapshot).toString())
            result += DifferenceEvidence(row, fingerprint, cloudChapter = entry, localSnapshot = reading.snapshot)
        }
        return result
    }

    private fun unavailableProgressCount(input: ProjectionInput): Int = input.history.filter { nativeCopy(it.manga) }
        .map { it.manga.id }.plus(input.targets.values.filter { it.accountOwner == input.owner }.map { it.mangaId })
        .distinct().count { it !in input.verified }

    private fun localCopyIds() = library.favorites.value.filter(::nativeCopy).mapTo(mutableSetOf(), Manga::id)

    private suspend fun reconcileSubmittedAdds(remote: List<Manga>) {
        val confirmed = remote.map(Manga::id).filter { it in baseline.submittedAdds }
        if (confirmed.isEmpty()) return
        baseline.managed.addAll(confirmed)
        baseline.submittedAdds.removeAll(confirmed.toSet())
        persist()
    }

    /** A restore/unobserved bulk mutation is not evidence that the user deleted cloud books. */
    private suspend fun validateLocal(): Boolean {
        if (!baseline.imported || !persistedImported || baseline.expectedLocal == localCopyIds()) {
            if (!localValidated) evidenceRevision++
            localValidated = true
            return true
        }
        localValidated = false
        evidenceRevision++
        baseline = Baseline(expectedLocal = localCopyIds())
        persist()
        localValidated = true
        mutableState.value = mutableState.value.copy(error = "本机书架发生批量变化，请重新完整导入后再向拷贝同步。")
        return false
    }

    private fun publish(checked: Boolean = mutableState.value.checked,
        message: String? = mutableState.value.message, error: String? = mutableState.value.error) {
        val lease = generation
        val projection = ++projectionVersion
        projectionJob?.cancel()
        mutableState.value = mutableState.value.copy(checked = checked, message = message, error = error,
            projecting = owner != null)
        if (owner == null) return
        projectionJob = scope.launch {
            try {
                // Conflate bursts of page-position updates before taking an immutable input.
                delay(40L)
                val input = projectionInput()
                val evidence = evidenceRevision
                val local = localRevision
                val calculated = withContext(Dispatchers.Default) { projectionGate.withLock {
                    val copy = input.local.filter(::nativeCopy).associateBy(Manga::id)
                    val cloud = input.cloud.associateBy(Manga::id)
                    val changes = computeDifferences(input, checked)
                    val signature = changes.joinToString("\n") { it.row.reviewKey + "/" + it.row.canUseLocal + "/" + it.row.canUseCopy }
                    ProjectionResult(changes.map { it.row }, signature,
                        if (input.favoritesReady) input.cloud.filter { it.id !in copy && input.intents[it.id] != false } else emptyList(),
                        if (input.favoritesReady) copy.values.filter { input.intents[it.id] == true && it.id !in cloud } else emptyList(),
                        if (input.favoritesReady) input.cloud.filter { it.id in input.managed && input.intents[it.id] == false && it.id !in copy } else emptyList(),
                        input.local.count { !nativeCopy(it) }, if (checked) unavailableProgressCount(input) else 0)
                } }
                if (!owns(lease) || projection != projectionVersion || evidence != evidenceRevision || local != localRevision)
                    return@launch
                val revision = mutableState.value.revision + if (calculated.signature != differenceSignature) 1L else 0L
                differenceSignature = calculated.signature
                mutableState.value = mutableState.value.copy(owner = owner, hasFullImport = input.imported,
                    cloudFavorites = input.cloud, cloudAdded = calculated.added, toAdd = calculated.toAdd,
                    toRemove = calculated.toRemove, localOnlyCount = calculated.localOnly,
                    differences = calculated.differences, revision = revision,
                    progressUnavailableCount = calculated.unavailable, projecting = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (owns(lease) && projection == projectionVersion) mutableState.value = mutableState.value.copy(
                    projecting = false, error = failure.message ?: "差异暂时无法整理，请重新检查。")
            }
        }
    }

    private data class ProjectionResult(val differences: List<CopySyncDifference>, val signature: String,
        val added: List<Manga>, val toAdd: List<Manga>, val toRemove: List<Manga>,
        val localOnly: Int, val unavailable: Int)

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
        val reviewed = mutableMapOf<String, String>()
        json.optJSONObject("reviewedProgress")?.let { entries ->
            val keys = entries.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val value = entries.opt(id) as? String
                if (COPY_ID.matches(id) && value != null && value.length <= 2048) reviewed[id] = value
            }
        }
        Baseline(json.optBoolean("imported", false), strings("managed").toMutableSet(), strings("local"), intents,
            strings("submittedAdds").toMutableSet(), reviewed)
    }.getOrDefault(Baseline())

    private suspend fun persist() {
        val name = owner ?: return
        val lease = generation
        val frozen = baseline.copy(managed = baseline.managed.toMutableSet(),
            expectedLocal = baseline.expectedLocal.toSet(), intents = baseline.intents.toMutableMap(),
            submittedAdds = baseline.submittedAdds.toMutableSet(), reviewedProgress = baseline.reviewedProgress.toMutableMap())
        val saved = try {
            withContext(Dispatchers.IO) {
                persistenceGate.withLock {
                    val intents = JSONObject()
                    frozen.intents.forEach { (id, desired) -> intents.put(id, desired) }
                    val reviewed = JSONObject()
                    frozen.reviewedProgress.forEach { (id, fingerprint) -> reviewed.put(id, fingerprint) }
                    val json = JSONObject().put("imported", frozen.imported)
                        .put("managed", JSONArray(frozen.managed.sorted())).put("local", JSONArray(frozen.expectedLocal.sorted()))
                        .put("intents", intents).put("submittedAdds", JSONArray(frozen.submittedAdds.sorted()))
                        .put("reviewedProgress", reviewed)
                    disk.edit().putString(storageKey(name), json.toString()).commit()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        requireOwner(lease)
        evidenceRevision++
        if (saved) { persistedImported = frozen.imported && baseline.imported; return }
        persistedImported = false
        localValidated = false
        baseline.imported = false
        previewRevision = -1L
        verifiedProgressIds = emptySet()
        mutableState.value = mutableState.value.copy(hasFullImport = false, checked = false,
            favoritesReady = false, progressReady = false, message = null)
        throw SourceException("尚未保存收藏同步状态，请检查本机可用空间后重新导入。")
    }

    private val hasImportedAuthority: Boolean get() = baseline.imported && persistedImported && localValidated

    private fun nativeCopy(manga: Manga) = manga.sourceId == "copy" && !SourceKeys.isEncoded(manga.id) &&
        manga.sourceUrl != LOCAL_SOURCE_URL && COPY_ID.matches(manga.id)

    private fun nativeChapter(id: String) = CHAPTER_ID.matches(id) && id.any { it != '0' && it != '-' }

    private companion object {
        val COPY_ID = Regex("[A-Za-z0-9_-]{1,160}")
        val CHAPTER_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
