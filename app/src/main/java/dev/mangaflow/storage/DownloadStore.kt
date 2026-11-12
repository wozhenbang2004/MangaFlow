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
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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
                    val record = runCatching {
                        val json = JSONObject(AtomicFile(File(directory, "manifest.json")).openRead().bufferedReader().use { it.readText() })
                        readRecord(json)
                    }.getOrNull()
                    if (record != null && record.entry.id == directory.name) synchronized(lock) {
                        records[record.entry.id] = record
                    }
                }
                synchronized(lock) { publish() }
                reconcileRestoredRecords()
            } finally {
                initialized.complete(Unit)
                _loading.value = false
            }
        }
    }

    internal suspend fun awaitReady() = initialized.await()

    suspend fun enqueue(manga: Manga, chapter: Chapter, pages: List<Page>, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        awaitReady()
        require(pages.isNotEmpty()) { "本章没有可下载的图片" }
        require(pages.all { it.url.startsWith("https://") }) { "仅支持安全的 HTTPS 图片地址" }
        val id = chapterKey(manga.id, chapter.id)
        val change = synchronized(lock) {
            val old = records[id]
            if (old?.entry?.status == DownloadStatus.RUNNING || old?.entry?.status == DownloadStatus.QUEUED) return@withContext id
            old?.let { detachWriter(it) }
        }
        if (change != null) stopWriter(change)
        synchronized(lock) {
            // A later pause/retry/removal supersedes a source refresh that was waiting for I/O.
            if (records[id] !== change?.detached) return@withContext id
            val old = change?.previous
            val sameContent = old?.pages?.map { it.url.substringBefore('?') } == pages.map { it.url.substringBefore('?') }
            if (old != null && !sameContent) directory(id).listFiles()?.filter { it.name != "manifest.json" }?.forEach {
                if (!it.delete() && it.exists()) throw IOException("无法替换本地章节图片")
            }
            val files = pages.indices.map { pageFile(id, it) }.filter(::isReadableImage)
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

    fun retry(id: String) {
        scope.launch {
            awaitReady()
            try {
                synchronized(lock) {
                    val record = records[id] ?: return@launch
                    if (record.entry.needsSourceRefresh || record.entry.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.RUNNING, DownloadStatus.QUEUED)) return@launch
                    save(record.copy(entry = record.entry.copy(status = DownloadStatus.QUEUED, error = "")))
                    schedule(id)
                }
            } catch (error: Exception) {
                memoryFailure(id, error)
            }
        }
    }

    fun pause(id: String) {
        scope.launch {
            awaitReady()
            try {
                synchronized(lock) {
                    val record = records[id] ?: return@launch
                    if (record.entry.status == DownloadStatus.COMPLETED) return@launch
                    save(record.copy(entry = record.entry.copy(status = DownloadStatus.PAUSED, error = "")))
                    workManager.cancelUniqueWork(workName(id))
                }
            } catch (error: Exception) {
                memoryFailure(id, error)
                workManager.cancelUniqueWork(workName(id))
            }
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        awaitReady()
        val change = synchronized(lock) { records[id]?.let { detachWriter(it) } } ?: return@withContext
        stopWriter(change)
        synchronized(lock) {
            if (records[id] !== change.detached) return@withContext
            val target = directory(id)
            if (!target.deleteRecursively() && target.exists()) throw IOException("本地章节未完全删除，请重试")
            records.remove(id)
            publish()
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

    fun entryFor(mangaId: String, chapterId: String): DownloadEntry? = synchronized(lock) {
        records[chapterKey(mangaId, chapterId)]?.entry
    }

    internal fun record(id: String): DownloadRecord? = synchronized(lock) { records[id] }

    /** Registration and invalidation share the state lock, so a late worker cannot start writing. */
    internal fun beginTransfer(id: String, workId: String): DownloadRecord? = synchronized(lock) {
        val record = records[id] ?: return@synchronized null
        if (record.workId != workId || record.entry.needsSourceRefresh ||
            record.entry.status !in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)) return@synchronized null
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
        val detached = record.copy(workId = "", entry = record.entry.copy(status = DownloadStatus.PAUSED))
        save(detached)
        return FileChange(record, detached, listOfNotNull(record.workId.takeIf(String::isNotBlank), writer?.workId).distinct(), writer)
    }

    private suspend fun stopWriter(change: FileChange) {
        for (workId in change.workIds) {
            val uuid = runCatching { UUID.fromString(workId) }.getOrNull() ?: continue
            workManager.cancelWorkById(uuid).result.get()
        }
        // Cancellation acknowledgement alone does not close the socket or the old .part file.
        // This waits only for the affected chapter; unrelated queued chapters stay responsive.
        change.writer?.finished?.await()
    }

    private class ChapterWriter(val workId: String, val finished: CompletableDeferred<Unit> = CompletableDeferred())
    private data class FileChange(val previous: DownloadRecord, val detached: DownloadRecord, val workIds: List<String>, val writer: ChapterWriter?)

    internal fun update(id: String, workId: String, transform: (DownloadEntry) -> DownloadEntry) = synchronized(lock) {
        val record = records[id] ?: return@synchronized
        if (record.workId != workId || record.entry.status == DownloadStatus.PAUSED) return@synchronized
        try { save(record.copy(entry = transform(record.entry))) }
        catch (error: Exception) { memoryFailure(id, error); throw error }
    }

    internal fun finishPage(id: String, workId: String, index: Int, width: Int, height: Int, completed: Int, bytes: Long) = synchronized(lock) {
        val record = records[id] ?: return@synchronized
        if (record.workId != workId || record.entry.status == DownloadStatus.PAUSED) return@synchronized
        val pages = record.pages.mapIndexed { position, page -> if (position == index) page.copy(width = width, height = height) else page }
        try { save(record.copy(pages = pages, entry = record.entry.copy(completedPages = completed, downloadedBytes = bytes))) }
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
            val files = snapshot.pages.indices.map { pageFile(snapshot.entry.id, it) }.filter(::isReadableImage)
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
        records[record.entry.id] = record
        publish()
    }

    private fun publish() { _entries.value = records.values.map(DownloadRecord::entry).sortedByDescending(DownloadEntry::createdAt) }

    private fun memoryFailure(id: String, error: Exception) = synchronized(lock) {
        val record = records[id] ?: return@synchronized
        records[id] = record.copy(entry = record.entry.copy(status = DownloadStatus.FAILED, error = error.message ?: "无法保存下载状态"))
        publish()
    }

    companion object {
        const val DOWNLOAD_TAG = "mangaflow-chapter-download"
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
    )
}
