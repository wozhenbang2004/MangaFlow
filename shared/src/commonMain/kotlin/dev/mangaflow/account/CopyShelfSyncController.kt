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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
)

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
    val differences: List<CopySyncDifference> = emptyList(),
    val revision: Long = 0L,
    val progressUnavailableCount: Int = 0,
    val error: String? = null,
    val message: String? = null,
) {
    val busy: Boolean get() = checking || importing || uploading
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
    private val disk = preferences(applicationContext(context), "mangaflow_copy_shelf_sync")
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
    private var differenceSignature = ""
    private var lastLocalSignature = ""
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
        operationId++
        operation = null
        backgroundCheckOperation = false
        generation++
        owner = nextOwner
        session = nextSession
        snapshot = emptyList()
        remoteProgress = emptyMap()
        observedProgress = emptyMap()
        differenceSignature = ""
        lastLocalSignature = ""
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

    fun check() = checkSnapshot(background = false)
    fun checkInBackground() = checkSnapshot(background = true)

    private fun checkSnapshot(background: Boolean) = runOperation(checking = true, background = background) { lease ->
        library.awaitReady()
        requireOwner(lease)
        validateLocal()
        val remote = accountRepository.completeSyncSnapshot()
        requireOwner(lease)
        snapshot = remote.favorites
        remoteProgress = observedProgress + remote.progress
        reconcileSubmittedAdds(snapshot)
        previewRevision = localRevision
        publish(checked = true)
    }

    fun prepareUpload() = check()

    /** Local state changes update the preview without issuing account requests. */
    fun onLocalReadingChanged() {
        accountChanged()
        if (owner == null) return
        val current = localSignature()
        if (current == lastLocalSignature) return
        localRevision++
        publish()
    }

    /** Reuse the current detail's already validated, read-only account response. */
    fun observeCloudProgress(manga: Manga, chapterId: String, chapterTitle: String) {
        accountChanged()
        if (owner == null || !nativeCopy(manga) || !nativeChapter(chapterId)) return
        val entry = CloudHistoryEntry(manga, chapterId, chapterTitle.take(160))
        observedProgress = observedProgress + (manga.id to entry)
        remoteProgress = remoteProgress + (manga.id to entry)
        publish()
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
        differenceSignature = ""
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
        operationId++
        operation = null
        backgroundCheckOperation = false
        mutableState.value = mutableState.value.copy(checking = false, importing = false, uploading = false,
            message = "已停止本次同步，已保存的本机收藏与阅读记录会保留")
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
        val expectedLocal = localRevision
        validateLocal()
        val fresh = accountRepository.completeSyncSnapshot()
        requireOwner(lease)
        snapshot = fresh.favorites
        remoteProgress = observedProgress + fresh.progress
        // A detail-only observation can outlive a full history response. Re-read only
        // these explicitly selected works; passive checks never fan out per book.
        selected.filter { it.first.row.kind == CopySyncDifferenceKind.PROGRESS && it.first.row.manga.id !in fresh.progress }
            .forEach { (evidence, _) ->
                val manga = evidence.row.manga
                val current = accountRepository.cloudProgress(manga.id)
                requireOwner(lease)
                if (current != null && nativeChapter(current.chapterId)) {
                    val value = current.copy(manga = manga,
                        chapterTitle = current.chapterTitle.ifBlank { evidence.cloudChapter?.chapterTitle.orEmpty() })
                    observedProgress = observedProgress + (manga.id to value)
                    remoteProgress = remoteProgress + (manga.id to value)
                } else {
                    observedProgress = observedProgress - manga.id
                    remoteProgress = remoteProgress - manga.id
                }
            }
        publish(checked = true)
        val current = differences().associateBy { it.row.key }
        if (expectedLocal != localRevision || revision != mutableState.value.revision || selected.any {
                current[it.first.row.key]?.fingerprint != it.first.fingerprint
            }) {
            previewRevision = localRevision
            throw SourceException("本机或拷贝在检查期间发生变化，请确认新的差异后再合并。")
        }
        var succeeded = 0
        val failures = mutableListOf<String>()
        for ((evidence, choice) in selected) {
            requireOwner(lease)
            try {
                val actual = differences(includeReviewed = true).firstOrNull { it.row.key == evidence.row.key }
                if (actual == null || actual.fingerprint != evidence.fingerprint || actual.localSnapshot != evidence.localSnapshot) {
                    throw SourceException("这一项已变化，请重新比较。")
                }
                when (evidence.row.kind) {
                    CopySyncDifferenceKind.FAVORITE -> if (choice == CopyMergeChoice.USE_LOCAL) {
                        if (!baseline.imported || !validateLocal()) throw SourceException("请先完整导入当前账号的拷贝收藏。")
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
        if (comicUuid.isBlank() || !baseline.imported) throw SourceException("尚未确认拷贝作品身份或完整导入状态。")
        if (differences(includeReviewed = true).firstOrNull { it.row.key == evidence.row.key }?.fingerprint != evidence.fingerprint) {
            throw SourceException("本机收藏已变化，请重新比较。")
        }
        baseline.intents[manga.id] = favorite
        baseline.expectedLocal = localCopyIds()
        // The fresh complete read and explicit removal choice establish this managed identity.
        if (evidence.cloudFavorite == true) baseline.managed += manga.id
        if (favorite) baseline.submittedAdds += manga.id
        persist()
        if (!accountRepository.setFavorite(manga.id, comicUuid, favorite)) throw SourceException("拷贝尚未确认这项收藏变更。")
        requireOwner(lease)
        if (baseline.intents[manga.id] == favorite) baseline.intents.remove(manga.id)
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
            uploading = uploading, error = null, message = null)
        operation = scope.launch {
            try { block(lease) }
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

    private data class ReadingSide(val chapterId: String, val label: String)

    private fun localReading(mangaId: String): ReadingSide {
        val target = library.resumeTargets.value[mangaId]?.takeIf { it.accountOwner == owner }
        if (target != null) return ReadingSide(target.chapterId, target.chapterTitle.ifBlank { "已选择章节" })
        val recent = library.history.value.firstOrNull { it.manga.id == mangaId && nativeCopy(it.manga) }
            ?: return ReadingSide("", "暂无本机进度")
        return ReadingSide(recent.chapterId, recent.chapterTitle.ifBlank { "已保存章节" } +
            if (recent.pageCount > 0) " · ${recent.pageIndex + 1}/${recent.pageCount}" else "")
    }

    private fun differenceFingerprint(kind: String, mangaId: String, local: String, cloud: String): String = JSONObject()
        .put("kind", kind).put("manga", mangaId).put("local", local).put("cloud", cloud)
        .toString()

    private fun differences(checked: Boolean = mutableState.value.checked, includeReviewed: Boolean = false): List<DifferenceEvidence> {
        val local = library.favorites.value.filter(::nativeCopy).associateBy(Manga::id)
        val cloud = snapshot.filter(::nativeCopy).associateBy(Manga::id)
        val result = mutableListOf<DifferenceEvidence>()
        if (checked) (local.keys + cloud.keys).sorted().forEach { id ->
            val inLocal = id in local
            val inCloud = id in cloud
            if (inLocal != inCloud) {
                val manga = local[id] ?: cloud.getValue(id)
                val row = CopySyncDifference("favorite:$id", CopySyncDifferenceKind.FAVORITE, manga,
                    if (inLocal) "已收藏" else "未收藏", if (inCloud) "已收藏" else "未收藏",
                    if (inLocal) "加入拷贝" else "移出拷贝", if (inCloud) "加入本机" else "移出本机收藏",
                    canUseLocal = baseline.imported, canUseCopy = true,
                    detail = if (baseline.imported) null else "完整导入当前账号收藏后，才能向拷贝提交更改。")
                result += DifferenceEvidence(row, differenceFingerprint("favorite", id, inLocal.toString(), inCloud.toString()),
                    localFavorite = inLocal, cloudFavorite = inCloud)
            }
        }
        remoteProgress.entries.sortedBy { it.key }.forEach { (id, entry) ->
            if (!nativeCopy(entry.manga) || !nativeChapter(entry.chapterId)) return@forEach
            val reading = localReading(id)
            if (reading.chapterId == entry.chapterId) return@forEach
            val cloudLabel = entry.chapterTitle.ifBlank { "拷贝已保存章节" }
            val fingerprint = differenceFingerprint("progress", id, reading.chapterId, entry.chapterId)
            if (!includeReviewed && baseline.reviewedProgress[id] == fingerprint) return@forEach
            val row = CopySyncDifference("progress:$id", CopySyncDifferenceKind.PROGRESS,
                local[id] ?: cloud[id] ?: entry.manga, reading.label, cloudLabel,
                "保留本机", "采用拷贝", canUseLocal = true, canUseCopy = true,
                detail = "拷贝提供章节进度；每章页内位置保留在本机。")
            result += DifferenceEvidence(row, fingerprint, cloudChapter = entry, localSnapshot = reading.label)
        }
        return result
    }

    private fun localSignature(): String = buildString {
        fun part(value: String) { append(value.length).append(':').append(value) }
        library.favorites.value.filter(::nativeCopy).map(Manga::id).sorted().forEach(::part)
        library.history.value.filter { nativeCopy(it.manga) }.sortedBy { it.manga.id }.forEach {
            part(it.manga.id); part(it.chapterId); part(it.chapterTitle); part(it.pageIndex.toString()); part(it.pageCount.toString())
        }
        library.resumeTargets.value.values.filter { it.accountOwner == owner }.sortedBy { it.mangaId }.forEach {
            part(it.mangaId); part(it.chapterId); part(it.chapterTitle)
        }
    }

    private fun unavailableProgressCount(): Int = library.history.value.filter { nativeCopy(it.manga) }
        .map { it.manga.id }.plus(library.resumeTargets.value.values.filter { it.accountOwner == owner }.map { it.mangaId })
        .distinct().count { it !in remoteProgress }

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
        val changes = differences(checked)
        val signature = changes.joinToString("\n") { it.fingerprint + "/" + it.row.canUseLocal + "/" + it.row.canUseCopy }
        val revision = mutableState.value.revision + if (signature != differenceSignature) 1L else 0L
        differenceSignature = signature
        lastLocalSignature = localSignature()
        mutableState.value = mutableState.value.copy(owner = owner, hasFullImport = baseline.imported,
            checked = checked, cloudFavorites = snapshot,
            cloudAdded = snapshot.filter { it.id !in copy && baseline.intents[it.id] != false },
            toAdd = copy.values.filter { baseline.intents[it.id] == true && it.id !in cloud },
            toRemove = snapshot.filter { it.id in baseline.managed && baseline.intents[it.id] == false && it.id !in copy },
            localOnlyCount = local.count { !nativeCopy(it) }, differences = changes.map { it.row }, revision = revision,
            progressUnavailableCount = if (checked) unavailableProgressCount() else 0,
            message = message, error = error)
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

    private fun persist() {
        val name = owner ?: return
        try {
            val intents = JSONObject()
            baseline.intents.forEach { (id, desired) -> intents.put(id, desired) }
            val reviewed = JSONObject()
            baseline.reviewedProgress.forEach { (id, fingerprint) -> reviewed.put(id, fingerprint) }
            val json = JSONObject().put("imported", baseline.imported)
                .put("managed", JSONArray(baseline.managed.sorted())).put("local", JSONArray(baseline.expectedLocal.sorted()))
                .put("intents", intents).put("submittedAdds", JSONArray(baseline.submittedAdds.sorted()))
                .put("reviewedProgress", reviewed)
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

    private fun nativeChapter(id: String) = CHAPTER_ID.matches(id) && id.any { it != '0' && it != '-' }

    private companion object {
        val COPY_ID = Regex("[A-Za-z0-9_-]{1,160}")
        val CHAPTER_ID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
