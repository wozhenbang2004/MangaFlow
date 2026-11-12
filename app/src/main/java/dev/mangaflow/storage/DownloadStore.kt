package dev.mangaflow.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.Page
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

enum class DownloadStatus { QUEUED, RUNNING, WAITING, PAUSED, COMPLETED, FAILED }

data class DownloadEntry(
    val id: String,
    val manga: Manga,
    val chapter: Chapter,
    val status: DownloadStatus,
    val completedPages: Int,
    val totalPages: Int,
    val downloadedBytes: Long,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val needsSourceRefresh: Boolean = false,
)

internal data class DownloadRecord(
    val entry: DownloadEntry,
    val pages: List<Page>,
    val headers: Map<String, String>,
    val workId: String = "",
    val generation: String = UUID.randomUUID().toString(),
)

/** Durable chapter manifests paired with WorkManager's persistent scheduler. */
class DownloadStore private constructor(context: Context) {
    private val context = context.applicationContext
    private val root = File(this.context.filesDir, "chapter-downloads")
    private val workManager = WorkManager.getInstance(this.context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val records = linkedMapOf<String, DownloadRecord>()
    private val writers = mutableMapOf<String, ChapterWriter>()
    private val pageTransfers = mutableMapOf<PageTransferKey, Deferred<Page?>>()
    private val transferFailures = mutableMapOf<String, TransferFailure>()
    private val chapterChanges = ConcurrentHashMap<String, Mutex>()
    private val availablePages = mutableMapOf<String, MutableSet<Int>>()
    private val readerPositions = mutableMapOf<String, Int>()
    private val transferSlots = Semaphore(3)
    private val readingChoices = this.context.getSharedPreferences("reading-downloads", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()
    private val initialized = CompletableDeferred<Unit>()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        scope.launch {
            try {
                root.mkdirs()
                root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
                    directory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                    val record = runCatching {
                        val json = JSONObject(AtomicFile(File(directory, "manifest.json")).openRead().bufferedReader().use { it.readText() })
                        readRecord(json)
                    }.getOrNull()
                    if (record != null && record.entry.id == directory.name) {
                        val present = record.pages.indices.filter { isReadableImage(pageFile(record.entry.id, it)) }.toMutableSet()
                        synchronized(lock) {
                            records[record.entry.id] = record.copy(entry = record.entry.copy(
                                completedPages = present.size,
                                downloadedBytes = present.sumOf { pageFile(record.entry.id, it).length() },
                            ))
                            availablePages[record.entry.id] = present
                        }
                    }
                }
                synchronized(lock) { publish() }
                // Saved reading can start before scheduler reconciliation finishes in the background.
                initialized.complete(Unit)
                _loading.value = false
                reconcileRestoredRecords()
            } finally {
                initialized.complete(Unit)
                _loading.value = false
            }
        }
    }

    internal suspend fun awaitReady() = initialized.await()

    suspend fun enqueue(
        manga: Manga, chapter: Chapter, pages: List<Page>, headers: Map<String, String>,
        isEnabled: () -> Boolean = { true },
    ): String = enqueueInternal(manga, chapter, pages, headers, automatic = false, isEnabled = isEnabled)
        ?: chapterKey(manga.id, chapter.id)

    /** An opened chapter is the only automatic unit; paused, failed and removed downloads stay so. */
    suspend fun ensureReadingDownload(
        manga: Manga, chapter: Chapter, pages: List<Page>, headers: Map<String, String>,
        isEnabled: () -> Boolean = { true },
    ): String? = enqueueInternal(manga, chapter, pages, headers, automatic = true, isEnabled = isEnabled)

    /** Refresh only the exact entry that requested metadata; a later pause/delete/retry wins. */
    suspend fun refreshReadingDownload(
        manga: Manga, chapter: Chapter, pages: List<Page>, headers: Map<String, String>, expectedEntry: DownloadEntry,
        isEnabled: () -> Boolean = { true },
    ): Boolean = enqueueInternal(manga, chapter, pages, headers, automatic = false,
        expectedEntry = expectedEntry, isEnabled = isEnabled) != null

    private suspend fun enqueueInternal(
        manga: Manga, chapter: Chapter, pages: List<Page>, headers: Map<String, String>, automatic: Boolean,
        expectedEntry: DownloadEntry? = null,
        isEnabled: () -> Boolean = { true },
    ): String? = withContext(Dispatchers.IO) {
        awaitReady()
        require(pages.isNotEmpty()) { "本章没有可下载的图片" }
        require(pages.all { it.url.startsWith("https://") }) { "仅支持安全的 HTTPS 图片地址" }
        val id = chapterKey(manga.id, chapter.id)
        changeChapter(id) {
            val change = synchronized(lock) {
                val old = records[id]
                if (!isEnabled()) return@changeChapter null
                if (expectedEntry != null && old?.entry !== expectedEntry) return@changeChapter null
                if (automatic && (old != null || readingChoices.getBoolean(id, false))) return@changeChapter old?.entry?.id
                if (!automatic) readingChoices.edit().remove(id).apply()
                if (old?.entry?.status in ACTIVE_STATUSES) return@changeChapter id
                old?.let { detachWriter(it) }
            }
            if (change != null) stopWriter(change)
            synchronized(lock) {
                // A later pause/retry/removal supersedes a source refresh that was waiting for I/O.
                if (records[id] !== change?.detached) return@changeChapter null
                if (!isEnabled()) return@changeChapter null
                val old = change?.previous
                val sameContent = old?.pages?.map { it.url.substringBefore('?') } == pages.map { it.url.substringBefore('?') }
                if (old != null && !sameContent) directory(id).listFiles()?.filter { it.name != "manifest.json" }?.forEach {
                    if (!it.delete() && it.exists()) throw IOException("无法替换本地章节图片")
                }
                val present = pages.indices.filter { isReadableImage(pageFile(id, it)) }.toMutableSet()
                val files = present.map { pageFile(id, it) }
                availablePages[id] = present
                val complete = files.size == pages.size
                val entry = DownloadEntry(
                    id = id,
                    manga = manga,
                    chapter = chapter,
                    status = if (complete) DownloadStatus.COMPLETED else DownloadStatus.QUEUED,
                    completedPages = files.size,
                    totalPages = pages.size,
                    downloadedBytes = files.sumOf(File::length),
                    createdAt = old?.entry?.createdAt ?: System.currentTimeMillis(),
                )
                save(DownloadRecord(entry, pages, headers))
                if (!complete) schedule(id)
            }
            id
        }
    }

    fun retry(id: String) {
        scope.launch {
            try { resumeDownload(id, includePaused = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* resumeDownload has already published its failure. */ }
        }
    }

    private suspend fun resumeDownload(id: String, includePaused: Boolean) = withContext(Dispatchers.IO) {
        awaitReady()
        changeChapter(id) {
            var generation: String? = null
            try {
                val change = synchronized(lock) {
                    val record = records[id] ?: return@changeChapter
                    if (record.entry.needsSourceRefresh || record.entry.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.RUNNING, DownloadStatus.QUEUED) ||
                        (!includePaused && record.entry.status == DownloadStatus.PAUSED)) return@changeChapter
                    generation = record.generation
                    readingChoices.edit().remove(id).apply()
                    detachWriter(record)
                }
                generation = change.detached.generation
                stopWriter(change)
                synchronized(lock) {
                    if (records[id] !== change.detached) return@changeChapter
                    save(change.detached.copy(entry = change.detached.entry.copy(status = DownloadStatus.QUEUED, error = "")))
                    schedule(id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                memoryFailure(id, failure, generation)
                throw failure
            }
        }
    }

    fun pause(id: String) {
        scope.launch {
            awaitReady()
            changeChapter(id) {
                var generation: String? = null
                try {
                    val change = synchronized(lock) {
                        val record = records[id] ?: return@changeChapter
                        if (record.entry.status == DownloadStatus.COMPLETED) return@changeChapter
                        generation = record.generation
                        detachWriter(record)
                    }
                    generation = change.detached.generation
                    stopWriter(change)
                } catch (error: Exception) {
                    memoryFailure(id, error, generation)
                }
            }
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        awaitReady()
        changeChapter(id) {
            val change = synchronized(lock) {
                // A visible page or a new reader composition must not recreate a chapter the user removed.
                readingChoices.edit().putBoolean(id, true).apply()
                records[id]?.let { detachWriter(it) }
            } ?: return@changeChapter
            stopWriter(change)
            synchronized(lock) {
                if (records[id] !== change.detached) return@changeChapter
                val target = directory(id)
                if (!target.deleteRecursively() && target.exists()) throw IOException("本地章节未完全删除，请重试")
                records.remove(id)
                availablePages.remove(id)
                readerPositions.remove(id)
                publish()
            }
        }
    }

    /** Once invalidated, finish the affected file transaction even if its UI request is replaced. */
    private suspend fun <T> changeChapter(id: String, action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO + NonCancellable) {
            chapterChanges.getOrPut(id) { Mutex() }.withLock { action() }
        }
    }

    /** Returns only a complete offline chapter; an incomplete download never impersonates a complete one. */
    fun downloadedPages(mangaId: String, chapterId: String): List<Page>? = synchronized(lock) {
        val id = chapterKey(mangaId, chapterId)
        val record = records[id] ?: return@synchronized null
        if (record.entry.status != DownloadStatus.COMPLETED) return@synchronized null
        if (record.pages.indices.any { !pageFile(id, it).isFile || pageFile(id, it).length() == 0L }) return@synchronized null
        record.pages.mapIndexed { index, page -> page.copy(url = pageFile(id, index).toURI().toString()) }
    }

    /** Keep the original page order and remote URLs, even when only some images are saved. */
    suspend fun storedPages(mangaId: String, chapterId: String): List<Page>? = withContext(Dispatchers.IO) {
        awaitReady()
        synchronized(lock) { records[chapterKey(mangaId, chapterId)]?.pages?.takeIf { it.isNotEmpty() } }
    }

    /** Called for an image when it is needed, never on every download-progress publication. */
    suspend fun resolveReaderPage(mangaId: String, chapterId: String, page: Page): Page = withContext(Dispatchers.IO) {
        awaitReady()
        val id = chapterKey(mangaId, chapterId)
        val snapshot = chapterChanges.getOrPut(id) { Mutex() }.withLock {
            synchronized(lock) { records[id] }
        } ?: return@withContext page
        val stored = snapshot.pages.getOrNull(page.index) ?: return@withContext page
        if (stored.url.substringBefore('?') != page.url.substringBefore('?')) return@withContext page
        try {
            requestPage(snapshot, page.index) ?: page
        } catch (cancelled: CancellationException) {
            // Pausing/removing cancels the shared transfer, not the still-visible reader.
            currentCoroutineContext().ensureActive()
            page
        } catch (_: DownloadStorageException) {
            // Only local persistence failure falls back to the normal image path. Source refusal,
            // rate limits and network errors stay visible and never trigger an immediate second GET.
            page
        }
    }

    suspend fun retryReaderPage(mangaId: String, chapterId: String, page: Page): Page {
        resumeDownload(chapterKey(mangaId, chapterId), includePaused = false)
        return resolveReaderPage(mangaId, chapterId, page)
    }

    fun prioritizeReadingPage(mangaId: String, chapterId: String, index: Int) = synchronized(lock) {
        val id = chapterKey(mangaId, chapterId)
        if (records[id] != null) readerPositions[id] = index.coerceAtLeast(0)
    }

    internal fun nextPageIndex(id: String, workId: String, visited: Set<Int>): Int? = synchronized(lock) {
        val record = records[id] ?: return@synchronized null
        if (record.workId != workId || record.entry.status !in ACTIVE_STATUSES || record.pages.isEmpty()) return@synchronized null
        val preferred = readerPositions[id]?.coerceIn(0, record.pages.lastIndex)
        if (preferred != null) {
            (preferred..minOf(preferred + 2, record.pages.lastIndex)).firstOrNull { it !in visited }
                ?.let { return@synchronized it }
        }
        record.pages.indices.firstOrNull { it !in visited }
    }

    internal suspend fun downloadPage(id: String, workId: String, index: Int): Page? {
        val snapshot = synchronized(lock) { records[id]?.takeIf { it.workId == workId } } ?: return null
        return requestPage(snapshot, index)
    }

    private suspend fun requestPage(snapshot: DownloadRecord, index: Int): Page? {
        localPage(snapshot, index)?.let { return it }
        val key = PageTransferKey(snapshot.entry.id, snapshot.generation, index)
        val transfer = synchronized(lock) {
            val current = records[key.id] ?: return null
            if (current.generation != key.generation || index !in current.pages.indices) return null
            transferFailures[key.id]?.takeIf { it.generation == key.generation }?.let { throw it.error }
            if (current.entry.needsSourceRefresh) throw PermanentDownloadException(current.entry.error.ifBlank { "图片地址需要刷新，请重试" }, true)
            if (current.entry.status in setOf(DownloadStatus.FAILED, DownloadStatus.WAITING) && current.entry.error.isNotBlank()) {
                throw IOException(current.entry.error)
            }
            if (current.entry.status !in ACTIVE_STATUSES) return null
            pageTransfers[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    transferSlots.withPermit { transferPage(current, key) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    synchronized(lock) {
                        val latest = records[key.id]
                        if (latest?.generation == key.generation && latest.entry.status in ACTIVE_STATUSES) {
                            val permanent = failure as? PermanentDownloadException
                            transferFailures[key.id] = TransferFailure(key.generation, failure)
                            val failed = latest.copy(entry = latest.entry.copy(
                                status = if (permanent != null || failure is DownloadStorageException) DownloadStatus.FAILED else DownloadStatus.WAITING,
                                error = failure.message ?: "下载未完成",
                                needsSourceRefresh = permanent?.needsSourceRefresh == true,
                            ))
                            try { save(failed) }
                            catch (_: DownloadStorageException) {
                                records[key.id] = failed
                                publish()
                            }
                        }
                    }
                    throw failure
                }
            }.also { deferred ->
                pageTransfers[key] = deferred
                deferred.invokeOnCompletion {
                    synchronized(lock) { if (pageTransfers[key] === deferred) pageTransfers.remove(key) }
                }
                deferred.start()
            }
        }
        return transfer.await()
    }

    private fun localPage(snapshot: DownloadRecord, index: Int): Page? {
        val page = snapshot.pages.getOrNull(index) ?: return null
        val file = pageFile(snapshot.entry.id, index)
        if (!isReadableImage(file)) {
            synchronized(lock) {
                val current = records[snapshot.entry.id]
                val present = availablePages[snapshot.entry.id]
                if (current?.generation == snapshot.generation && present?.remove(index) == true) {
                    save(current.copy(entry = current.entry.copy(
                        completedPages = present.size,
                        downloadedBytes = present.sumOf { pageFile(snapshot.entry.id, it).length() },
                        status = if (current.entry.status == DownloadStatus.COMPLETED) DownloadStatus.FAILED else current.entry.status,
                        error = "本地章节不完整，继续下载可修复",
                    )))
                }
            }
            return null
        }
        return synchronized(lock) {
            if (records[snapshot.entry.id]?.generation != snapshot.generation) null
            else page.copy(url = file.toURI().toString())
        }
    }

    private suspend fun transferPage(snapshot: DownloadRecord, key: PageTransferKey): Page? {
        localPage(snapshot, key.index)?.let { return it }
        val page = snapshot.pages[key.index]
        val destination = pageFile(key.id, key.index)
        val partial = File(destination.parentFile, "${destination.name}.${key.generation}.part")
        try {
            val canWrite = synchronized(lock) {
                records[key.id]?.let { it.generation == key.generation && it.entry.status in ACTIVE_STATUSES } == true
            }
            if (!canWrite) return null
            destination.parentFile?.mkdirs()
            val bounds = downloadPageFile(page.url, snapshot.headers, partial)
            currentCoroutineContext().ensureActive()
            return synchronized(lock) {
                val current = records[key.id] ?: return@synchronized null
                if (current.generation != key.generation || current.entry.status !in ACTIVE_STATUSES) return@synchronized null
                // Only validated, closed files are published; readers can never see a partial write.
                if (destination.exists() && !destination.delete()) throw DownloadStorageException("无法替换本地图片")
                if (!partial.renameTo(destination)) throw DownloadStorageException("无法保存本地图片")
                val available = availablePages.getOrPut(key.id) { mutableSetOf() }.apply { add(key.index) }
                val updatedPages = current.pages.mapIndexed { position, value ->
                    if (position == key.index) value.copy(width = bounds.first, height = bounds.second) else value
                }
                val complete = available.size == current.pages.size
                save(current.copy(pages = updatedPages, entry = current.entry.copy(
                    completedPages = available.size,
                    downloadedBytes = available.sumOf { pageFile(key.id, it).length() },
                    status = if (complete) DownloadStatus.COMPLETED else current.entry.status,
                    error = "",
                )))
                updatedPages[key.index].copy(url = destination.toURI().toString())
            }
        } finally {
            partial.delete()
        }
    }

    fun entryFor(mangaId: String, chapterId: String): DownloadEntry? = synchronized(lock) {
        records[chapterKey(mangaId, chapterId)]?.entry
    }

    internal fun record(id: String): DownloadRecord? = synchronized(lock) { records[id] }

    /** Registration and invalidation share the state lock, so a late worker cannot start writing. */
    internal fun beginTransfer(id: String, workId: String, retryAttempt: Boolean): DownloadRecord? = synchronized(lock) {
        val record = records[id] ?: return@synchronized null
        if (record.workId != workId || record.entry.needsSourceRefresh ||
            record.entry.status !in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)) return@synchronized null
        val failure = transferFailures[id]?.takeIf { it.generation == record.generation }
        if (retryAttempt) transferFailures.remove(id)
        else if (failure != null) throw failure.error
        writers[id] = ChapterWriter(workId)
        record
    }

    internal fun endTransfer(id: String, workId: String) {
        val writer = synchronized(lock) {
            writers[id]?.takeIf { it.workId == workId }?.also { writers.remove(id) }
        }
        writer?.finished?.complete(Unit)
    }

    private fun detachWriter(record: DownloadRecord): FileChange {
        val writer = writers[record.entry.id]
        val detached = record.copy(workId = "", generation = UUID.randomUUID().toString(), entry = record.entry.copy(status = DownloadStatus.PAUSED, error = ""))
        save(detached)
        transferFailures.remove(record.entry.id)
        val transfers = pageTransfers.filterKeys { it.id == record.entry.id }.values.toList()
        return FileChange(record, detached, listOfNotNull(record.workId.takeIf(String::isNotBlank), writer?.workId).distinct(), writer, transfers)
    }

    private suspend fun stopWriter(change: FileChange) {
        change.transfers.forEach { it.cancel() }
        try {
            for (workId in change.workIds) {
                val uuid = runCatching { UUID.fromString(workId) }.getOrNull() ?: continue
                workManager.cancelWorkById(uuid).result.get(20, TimeUnit.SECONDS)
            }
            // Only the affected chapter is awaited. A timeout leaves its files and invalidated
            // generation intact; callers never proceed to removal/replacement with a live writer.
            withTimeout(30_000) {
                change.writer?.finished?.await()
                change.transfers.forEach { it.join() }
            }
        } catch (failure: TimeoutCancellationException) {
            throw IOException("下载尚未完全暂停，请稍后重试", failure)
        } catch (failure: TimeoutException) {
            throw IOException("下载调度尚未响应，请稍后重试", failure)
        }
    }

    private class ChapterWriter(val workId: String, val finished: CompletableDeferred<Unit> = CompletableDeferred())
    private data class PageTransferKey(val id: String, val generation: String, val index: Int)
    private data class TransferFailure(val generation: String, val error: Exception)
    private data class FileChange(
        val previous: DownloadRecord, val detached: DownloadRecord, val workIds: List<String>,
        val writer: ChapterWriter?, val transfers: List<Deferred<Page?>>,
    )

    internal fun update(id: String, workId: String, transform: (DownloadEntry) -> DownloadEntry) = synchronized(lock) {
        val record = records[id] ?: return@synchronized
        if (record.workId != workId || record.entry.status == DownloadStatus.PAUSED) return@synchronized
        val next = transform(record.entry)
        // A page requested by the reader can finish the chapter while the worker is updating its
        // notification. Late cancellation/bookkeeping must not turn those saved images into failure.
        if (record.entry.status == DownloadStatus.COMPLETED && next.status != DownloadStatus.COMPLETED) return@synchronized
        if (record.entry.status == DownloadStatus.FAILED && record.entry.needsSourceRefresh && !next.needsSourceRefresh) return@synchronized
        try { save(record.copy(entry = next)) }
        catch (error: Exception) { memoryFailure(id, error); throw error }
    }

    internal fun pageFile(id: String, index: Int): File = File(directory(id), "${index.toString().padStart(5, '0')}.img")
    internal fun directory(id: String): File {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return File(root, id)
    }

    /** Reconcile persisted intent against the scheduler without holding the state lock during reads. */
    private fun reconcileRestoredRecords() {
        val snapshots = synchronized(lock) { records.values.toList() }
        for (snapshot in snapshots) {
            if (snapshot.entry.status == DownloadStatus.PAUSED) continue
            val active = snapshot.entry.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
            if (!active && snapshot.entry.status != DownloadStatus.COMPLETED) continue
            val files = synchronized(lock) { availablePages[snapshot.entry.id].orEmpty().map { pageFile(snapshot.entry.id, it) } }
            val complete = snapshot.pages.isNotEmpty() && files.size == snapshot.pages.size
            // A failed scheduler read is unknown, never evidence that a worker disappeared.
            val workInfo = if (active && snapshot.workId.isNotBlank()) {
                try {
                    workManager.getWorkInfoById(UUID.fromString(snapshot.workId)).get(20, TimeUnit.SECONDS)
                } catch (_: IllegalArgumentException) { null }
                  catch (_: Exception) { continue }
            } else null
            val hasLiveWork = workInfo != null && workInfo.state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
            synchronized(lock) {
                // A pause, retry, refresh, deletion, or worker progress supersedes this snapshot.
                if (records[snapshot.entry.id] !== snapshot) return@synchronized
                try {
                    when {
                        snapshot.entry.status == DownloadStatus.COMPLETED && !complete -> save(snapshot.copy(entry = snapshot.entry.copy(
                            status = DownloadStatus.FAILED,
                            completedPages = files.size,
                            downloadedBytes = files.sumOf(File::length),
                            needsSourceRefresh = snapshot.pages.isEmpty(),
                            error = "本地章节不完整，继续下载可修复",
                        )))
                        active && !hasLiveWork && complete -> save(snapshot.copy(entry = snapshot.entry.copy(
                            status = DownloadStatus.COMPLETED,
                            completedPages = files.size,
                            downloadedBytes = files.sumOf(File::length),
                            needsSourceRefresh = false,
                            error = "",
                        )))
                        active && !hasLiveWork && (snapshot.entry.needsSourceRefresh || snapshot.pages.isEmpty()) -> save(snapshot.copy(entry = snapshot.entry.copy(
                            status = DownloadStatus.FAILED,
                            needsSourceRefresh = true,
                            error = "图片地址需要刷新，请刷新并重试",
                        )))
                        active && !hasLiveWork -> {
                            save(snapshot.copy(entry = snapshot.entry.copy(
                                status = DownloadStatus.QUEUED,
                                completedPages = files.size,
                                downloadedBytes = files.sumOf(File::length),
                                error = "",
                            )))
                            schedule(snapshot.entry.id)
                        }
                        snapshot.entry.status == DownloadStatus.RUNNING && workInfo?.state == WorkInfo.State.ENQUEUED -> save(snapshot.copy(entry = snapshot.entry.copy(
                            status = DownloadStatus.WAITING, error = "等待系统恢复下载",
                        )))
                        else -> Unit
                    }
                } catch (error: Exception) { memoryFailure(snapshot.entry.id, error) }
            }
        }
    }

    private fun isReadableImage(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0
    }

    private fun schedule(id: String) {
        val work = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setInputData(workDataOf(ChapterDownloadWorker.KEY_CHAPTER to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(DOWNLOAD_TAG)
            .build()
        val record = records[id] ?: return
        // A cancelled older worker must not overwrite the state of its replacement.
        save(record.copy(workId = work.id.toString()))
        try { workManager.enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, work).result.get() }
        catch (error: Exception) { memoryFailure(id, error); throw error }
    }

    private fun save(record: DownloadRecord) {
        try {
            val directory = directory(record.entry.id).apply { mkdirs() }
            val atomic = AtomicFile(File(directory, "manifest.json"))
            val stream = atomic.startWrite()
            try {
                stream.write(record.toJson().toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
        } catch (failure: IOException) {
            throw DownloadStorageException("无法保存下载状态，请检查可用空间", failure)
        }
        records[record.entry.id] = record
        publish()
    }

    private fun publish() { _entries.value = records.values.map(DownloadRecord::entry).sortedByDescending(DownloadEntry::createdAt) }

    private fun memoryFailure(id: String, error: Exception, generation: String? = null) = synchronized(lock) {
        val record = records[id] ?: return@synchronized
        if (generation != null && record.generation != generation) return@synchronized
        records[id] = record.copy(entry = record.entry.copy(status = DownloadStatus.FAILED, error = error.message ?: "无法保存下载状态"))
        publish()
    }

    companion object {
        const val DOWNLOAD_TAG = "mangaflow-chapter-download"
        private val ACTIVE_STATUSES = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
        internal val transferGate = Mutex()
        @Volatile private var instance: DownloadStore? = null
        fun get(context: Context): DownloadStore = instance ?: synchronized(this) {
            instance ?: DownloadStore(context).also { instance = it }
        }
        private fun workName(id: String) = "mangaflow-download-$id"
        private fun chapterKey(mangaId: String, chapterId: String) = MessageDigest.getInstance("SHA-256")
            .digest("$mangaId\u0000$chapterId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

private fun DownloadRecord.toJson() = JSONObject().apply {
    put("schema", 1)
    put("id", entry.id)
    put("manga", entry.manga.toJson())
    put("chapter", entry.chapter.toJson())
    put("status", entry.status.name)
    put("completedPages", entry.completedPages)
    put("totalPages", entry.totalPages)
    put("downloadedBytes", entry.downloadedBytes)
    put("error", entry.error)
    put("needsSourceRefresh", entry.needsSourceRefresh)
    put("createdAt", entry.createdAt)
    put("workId", workId)
    put("generation", generation)
    put("pages", JSONArray(pages.map { it.toJson() }))
    put("headers", JSONObject(headers.filterKeys { it.equals("Referer", true) || it.equals("User-Agent", true) || it.equals("Accept", true) }))
}

private fun readRecord(json: JSONObject): DownloadRecord {
    val pages = json.getJSONArray("pages").let { array -> (0 until array.length()).map { readPage(array.getJSONObject(it)) } }
    val headers = json.optJSONObject("headers") ?: JSONObject()
    return DownloadRecord(
        entry = DownloadEntry(
            id = json.getString("id"),
            manga = readManga(json.getJSONObject("manga")),
            chapter = readChapter(json.getJSONObject("chapter")),
            status = DownloadStatus.entries.firstOrNull { it.name == json.optString("status") } ?: DownloadStatus.FAILED,
            completedPages = json.optInt("completedPages"),
            totalPages = pages.size,
            downloadedBytes = json.optLong("downloadedBytes"),
            error = json.optString("error"),
            needsSourceRefresh = json.optBoolean("needsSourceRefresh", false),
            createdAt = json.optLong("createdAt"),
        ),
        pages = pages,
        headers = headers.keys().asSequence().associateWith(headers::getString),
        workId = json.optString("workId"),
        generation = json.optString("generation").ifBlank { UUID.randomUUID().toString() },
    )
}
