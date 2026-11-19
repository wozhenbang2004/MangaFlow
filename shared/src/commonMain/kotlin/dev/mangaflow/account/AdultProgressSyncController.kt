package dev.mangaflow.account

import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.storage.AdultProgressDocument
import dev.mangaflow.storage.AdultProgressSnapshot
import dev.mangaflow.storage.AdultProgressWork
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.WebDavBackupStore
import dev.mangaflow.storage.WebDavCompanionDocument
import dev.mangaflow.storage.WebDavTransferSnapshot
import dev.mangaflow.storage.adultProgressChapterMatches
import dev.mangaflow.storage.sameLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AdultProgressPreference { DEVICE, WEBDAV }

internal data class AdultProgressDifference(val workId: String, val device: AdultProgressWork?, val webdav: AdultProgressWork?) {
    val title: String get() = (device ?: requireNotNull(webdav)).manga.title
    val chapterDifferences: List<String> = run {
        val local = device?.chapters.orEmpty()
        val remote = webdav?.chapters.orEmpty()
        (local.keys + remote.keys).filter { local[it]?.sameLocation(remote[it]) != true }
    }
}

internal data class AdultProgressSyncState(
    val revision: Long = 0L,
    val reading: Boolean = false,
    val applying: Boolean = false,
    val compared: Boolean = false,
    val differences: List<AdultProgressDifference> = emptyList(),
    val unavailableWorks: Int = 0,
    val error: String? = null,
    val completed: String? = null,
) {
    val busy: Boolean get() = reading || applying
}

/** Device records and the configured WebDAV companion are compared before any scoped writes. */
internal class AdultProgressSyncController(
    context: PlatformContext,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
    private val sourceIds: () -> Set<String>,
) {
    private val webdav = WebDavBackupStore.get(context)
    private val routes = DownloadRoutingStore.get(context)
    val configuration = webdav.config
    private val mutableState = MutableStateFlow(AdultProgressSyncState())
    val state = mutableState.asStateFlow()
    private var sequence = 0L
    private var job: Job? = null
    private var snapshot: Snapshot? = null

    private data class Snapshot(
        val transfer: WebDavTransferSnapshot,
        val sources: Set<String>,
        val device: AdultProgressSnapshot,
        val cloud: Map<String, AdultProgressWork>,
        val raw: WebDavCompanionDocument,
    )

    fun compare() {
        if (state.value.busy) return
        val generation = ++sequence
        snapshot = null
        mutableState.value = AdultProgressSyncState(generation, reading = true)
        job = scope.launch {
            try {
                val transfer = webdav.captureTransfer(routes.snapshot(DownloadCategory.WEBDAV))
                val sources = sourceIds()
                val device = library.captureAdultProgress(sources)
                val raw = webdav.readCompanion(DOCUMENT_NAME, transfer)
                val cloud = withContext(Dispatchers.Default) { AdultProgressDocument.decode(raw.payload) }
                check(sourceIds() == sources) { "成人来源范围已变更，请重新比较。" }
                if (generation != sequence) return@launch
                snapshot = Snapshot(transfer, sources, device, cloud, raw)
                val (differences, available) = withContext(Dispatchers.Default) {
                    val accessible = cloud.filter { (id, work) -> work.chapters.keys.all {
                        adultProgressChapterMatches(id, it, sources)
                    } }
                    val differences = (device.works.keys + accessible.keys).mapNotNull { id ->
                        val local = device.works[id]
                        val remote = accessible[id]
                        if (id in cloud && id !in accessible || local?.sameLocations(remote) == true) null
                        else AdultProgressDifference(id, local, remote)
                    }.sortedBy(AdultProgressDifference::title)
                    differences to accessible.size
                }
                if (generation != sequence) return@launch
                mutableState.value = AdultProgressSyncState(generation, compared = true, differences = differences,
                    unavailableWorks = cloud.size - available)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == sequence) mutableState.value = AdultProgressSyncState(generation,
                    error = failure.message ?: "读取阅读进度失败，请重试。")
            }
        }
    }

    fun apply(revision: Long, preferences: Map<String, AdultProgressPreference>) {
        val review = state.value.takeIf { it.revision == revision && it.compared && !it.busy } ?: return
        val before = snapshot ?: return
        val merged = review.differences.mapNotNull { difference ->
            val choice = preferences[difference.workId] ?: return@mapNotNull null
            val device = difference.device
            val cloud = difference.webdav
            val work = when {
                device == null -> requireNotNull(cloud)
                cloud == null -> device
                choice == AdultProgressPreference.DEVICE -> cloud.mergePreferred(device)
                else -> device.mergePreferred(cloud)
            }
            difference.workId to work
        }.toMap()
        if (merged.isEmpty()) return
        val generation = ++sequence
        snapshot = null
        mutableState.value = review.copy(applying = true, compared = false, completed = null, error = null)
        job = scope.launch {
            var uploaded = false
            try {
                fun scopeCurrent() = sourceIds() == before.sources
                check(scopeCurrent()) { "成人来源范围已变更，请重新比较。" }
                val local = library.captureAdultProgress(before.sources)
                check(local.backupRevision == before.device.backupRevision && merged.keys.all { id ->
                    local.works[id]?.sameLocations(before.device.works[id]) ?: (id !in before.device.works)
                }) { "本机阅读位置已更新，请重新比较。" }
                val remote = webdav.readCompanion(DOCUMENT_NAME, before.transfer)
                check(remote == before.raw) { "另一设备已更新远端阅读位置，请重新比较。" }
                check(scopeCurrent()) { "成人来源范围已变更，请重新比较。" }
                val cloudChanges = merged.filterValues { work -> !work.sameLocations(before.cloud[work.manga.id]) }
                if (cloudChanges.isNotEmpty()) {
                    val payload = withContext(Dispatchers.Default) { AdultProgressDocument.encode(before.cloud + cloudChanges) }
                    check(scopeCurrent()) { "成人来源范围已变更，请重新比较。" }
                    webdav.writeCompanion(DOCUMENT_NAME, payload, remote, before.transfer)
                    uploaded = true
                }
                webdav.checkCompanionConnection(before.transfer)
                library.applyAdultProgress(before.device, merged, before.sources, ::scopeCurrent)
                if (generation == sequence) mutableState.value = AdultProgressSyncState(generation,
                    completed = "已同步 ${merged.size} 部作品的阅读位置。")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (generation == sequence) mutableState.value = AdultProgressSyncState(generation,
                    error = (if (uploaded) "云端已保存。" else "") +
                        (failure.message ?: "阅读位置未能完整同步，请重新比较。"))
            }
        }
    }

    /** Leaving the comparison discards its authority. An accepted apply finishes under the app owner. */
    fun dismiss() {
        if (state.value.applying) return
        sequence++
        job?.cancel()
        snapshot = null
        mutableState.value = AdultProgressSyncState(sequence)
    }

    private companion object { const val DOCUMENT_NAME = "adult-reading-progress-v1.json" }
}
