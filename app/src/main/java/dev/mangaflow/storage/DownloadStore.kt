package dev.mangaflow.storage

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipInputStream
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val restoredMetadata = mutableSetOf<String>()
    private val validationLocks = ConcurrentHashMap<String, Mutex>()
    private val validatedGenerations = mutableMapOf<String, String>()
    private val pageWriteVersions = mutableMapOf<String, MutableMap<Int, Long>>()
    private var entryOrder = emptyList<String>()
    private val transferSlots = Semaphore(3)
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
                    if (directory.name.startsWith(IMPORT_DIRECTORY_PREFIX)) {
                        directory.deleteRecursively()
                        return@forEach
                    }
                    restoreChapter(directory.name, publishRestored = false)
                }
                synchronized(lock) { publish() }
                // Global readiness covers manifests only. Image validation follows on IO;
                // a reader can restore its one manifest even before this scan finishes.
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

    fun observeManga(mangaId: String): Flow<List<DownloadEntry>> = entries
        .map { all -> all.filter { it.manga.id == mangaId } }.distinctUntilChanged()

    fun observeChapter(mangaId: String, chapterId: String): Flow<DownloadEntry?> = entries
        .map { all -> all.firstOrNull { it.manga.id == mangaId && it.chapter.id == chapterId } }
        .distinctUntilChanged()

    private suspend fun restoreChapter(id: String, publishRestored: Boolean = true) = withContext(Dispatchers.IO) {
        if (!id.matches(Regex("[a-f0-9]{64}"))) return@withContext
        chapterChanges.getOrPut(id) { Mutex() }.withLock {
            if (synchronized(lock) { id in restoredMetadata || id in records }) return@withLock
            val folder = directory(id)
            // This is done before a restored worker can run, never during validation of live transfers.
            folder.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
            val restored = runCatching {
                val json = JSONObject(AtomicFile(File(folder, "manifest.json")).openRead().bufferedReader().use { it.readText() })
                readRecord(json).takeIf { it.entry.id == id }
            }.getOrNull()
            // File metadata is enough for the initial index. Decode only a requested page now;
            // all remaining image headers are checked by background validation.
            val present = restored?.pages?.indices?.filter {
                val file = pageFile(id, it)
                file.isFile && file.length() > 0L
            }.orEmpty().toMutableSet()
            val bytes = present.sumOf { pageFile(id, it).length() }
            synchronized(lock) {
                restoredMetadata += id
                if (restored != null && id !in records) {
                    records[id] = restored.copy(entry = restored.entry.copy(
                        completedPages = present.size, downloadedBytes = bytes))
                    availablePages[id] = present
                    if (publishRestored) publish()
                }
            }
        }
    }

    /** Only workers and background reconciliation validate a whole chapter. Readers validate one page. */
    internal suspend fun prepareChapter(id: String) = withContext(Dispatchers.IO) {
        restoreChapter(id)
        validationLocks.getOrPut(id) { Mutex() }.withLock {
            val snapshot = synchronized(lock) {
                records[id]?.takeUnless { validatedGenerations[id] == it.generation }
            } ?: return@withLock
            val versions = synchronized(lock) { pageWriteVersions[id]?.toMap().orEmpty() }
            val present = snapshot.pages.indices.filter { isReadableImage(pageFile(id, it)) }.toSet()
            synchronized(lock) {
                val current = records[id]?.takeIf { it.generation == snapshot.generation } ?: return@synchronized
                val available = availablePages.getOrPut(id) { mutableSetOf() }
                for (index in snapshot.pages.indices) {
                    // A reader may save a validated replacement while this scan is decoding.
                    if (pageWriteVersions[id]?.get(index) == versions[index]) {
                        if (index in present) available.add(index) else available.remove(index)
                    }
                }
                validatedGenerations[id] = current.generation
                val bytes = available.sumOf { pageFile(id, it).length() }
                if (current.entry.completedPages != available.size || current.entry.downloadedBytes != bytes) {
                    // Startup verification is a cache/index correction, not a durable user intent.
                    // Avoid rewriting every manifest and requiring free disk just to read a chapter.
                    records[id] = current.copy(entry = current.entry.copy(completedPages = available.size, downloadedBytes = bytes))
                    publish(reorder = false)
                }
            }
        }
    }

    fun availableStorageBytes(): Long = root.usableSpace.coerceAtLeast(0L)

    suspend fun importArchive(uri: Uri, mangaTitle: String, chapterTitle: String): DownloadEntry =
        importLocalChapter(mangaTitle, chapterTitle) { staging ->
            extractArchive(uri, staging)
        }

    suspend fun importImages(uris: List<Uri>, mangaTitle: String, chapterTitle: String): DownloadEntry =
        importLocalChapter(mangaTitle, chapterTitle) { staging ->
            val documents = uris.distinct().map { uri ->
                retainReadPermission(uri)
                LocalDocument(uri, documentName(uri))
            }.sortedWith(compareByNatural(LocalDocument::name))
            copyDocuments(documents, staging)
        }

    suspend fun importFolder(treeUri: Uri, mangaTitle: String, chapterTitle: String): DownloadEntry =
        importLocalChapter(mangaTitle, chapterTitle) { staging ->
            retainReadPermission(treeUri)
            copyDocuments(collectTreeImages(treeUri), staging)
        }

    private suspend fun importLocalChapter(
        mangaTitle: String,
        chapterTitle: String,
        writePages: suspend (File) -> List<ImportedPage>,
    ): DownloadEntry = withContext(Dispatchers.IO) {
        awaitReady()
        val cleanMangaTitle = mangaTitle.trim().take(120)
        val cleanChapterTitle = chapterTitle.trim().take(120)
        require(cleanMangaTitle.isNotBlank()) { "请填写漫画名称" }
        require(cleanChapterTitle.isNotBlank()) { "请填写章节名称" }

        val mangaId = "local:${stableDigest(cleanMangaTitle.lowercase(Locale.ROOT))}"
        val chapterId = "local:${stableDigest("$mangaId\u0000$cleanChapterTitle\u0000${System.currentTimeMillis()}\u0000${UUID.randomUUID()}")}"
        val id = chapterKey(mangaId, chapterId)
        val staging = File(root, "$IMPORT_DIRECTORY_PREFIX$id-${UUID.randomUUID()}")
        val target = directory(id)
        try {
            require(staging.mkdirs()) { "无法创建导入空间，请检查存储空间" }
            val imported = writePages(staging)
            require(imported.isNotEmpty()) { "没有找到可读取的漫画图片" }
            require(imported.size <= MAX_IMPORT_PAGES) { "图片数量超过 $MAX_IMPORT_PAGES 页" }

            val chapterIndex = synchronized(lock) {
                records.values.asSequence()
                    .filter { it.entry.manga.id == mangaId }
                    .map { it.entry.chapter.index }
                    .maxOrNull()?.plus(1) ?: 0
            }
            val chapter = Chapter(chapterId, cleanChapterTitle, chapterIndex, "本地")
            val manga = Manga(
                id = mangaId,
                title = cleanMangaTitle,
                coverUrl = pageFile(id, 0).toURI().toString(),
                tags = listOf("本地"),
                latestChapter = cleanChapterTitle,
                sourceUrl = LOCAL_SOURCE_URL,
                sourceUuid = LOCAL_SOURCE_UUID,
            )
            val pages = imported.mapIndexed { index, page ->
                Page(index, "$LOCAL_PAGE_SCHEME$id/$index", page.width, page.height)
            }
            val bytes = staging.listFiles().orEmpty().sumOf(File::length)
            val entry = DownloadEntry(
                id = id,
                manga = manga,
                chapter = chapter,
                status = DownloadStatus.COMPLETED,
                completedPages = pages.size,
                totalPages = pages.size,
                downloadedBytes = bytes,
            )
            val record = DownloadRecord(entry, pages, emptyMap())
            // Publish an import only after both its images and manifest can move into place together.
            // A process death before the rename leaves a disposable staging directory; after it, startup
            // can reconstruct the chapter from the already-complete manifest.
            writeManifest(staging, record)

            changeChapter(id) {
                require(!target.exists()) { "导入目标已存在，请重试" }
                if (!staging.renameTo(target)) throw IOException("无法保存导入的漫画")
                synchronized(lock) {
                    records[id] = record
                    availablePages[id] = pages.indices.toMutableSet()
                    publish()
                }
            }
            entry
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun extractArchive(uri: Uri, staging: File): List<ImportedPage> {
        retainReadPermission(uri)
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取压缩包")
        val raw = mutableListOf<RawImportedPage>()
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(input)).use { archive ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && isImageName(entry.name)) {
                    require(raw.size < MAX_IMPORT_PAGES) { "压缩包图片超过 $MAX_IMPORT_PAGES 页" }
                    val temporary = File(staging, ".raw-${raw.size.toString().padStart(5, '0')}")
                    val copied = copyLimited(archive, temporary, MAX_IMPORT_PAGE_BYTES)
                    totalBytes += copied
                    require(totalBytes <= MAX_IMPORT_BYTES) { "压缩包解压后超过可导入大小" }
                    val bounds = imageBounds(temporary)
                        ?: throw IOException("${entry.name.substringAfterLast('/')} 不是可读取的图片")
                    raw += RawImportedPage(entry.name.replace('\\', '/'), temporary, bounds.first, bounds.second)
                }
                archive.closeEntry()
            }
        }
        require(raw.isNotEmpty()) { "压缩包中没有找到漫画图片" }
        return raw.sortedWith(compareByNatural(RawImportedPage::name)).mapIndexed { index, page ->
            val destination = File(staging, "${index.toString().padStart(5, '0')}.img")
            if (!page.file.renameTo(destination)) throw IOException("无法整理导入图片")
            ImportedPage(page.width, page.height)
        }
    }

    private suspend fun copyDocuments(documents: List<LocalDocument>, staging: File): List<ImportedPage> {
        require(documents.isNotEmpty()) { "没有找到可读取的漫画图片" }
        require(documents.size <= MAX_IMPORT_PAGES) { "图片数量超过 $MAX_IMPORT_PAGES 页" }
        var totalBytes = 0L
        return documents.mapIndexed { index, document ->
            currentCoroutineContext().ensureActive()
            retainReadPermission(document.uri)
            val destination = File(staging, "${index.toString().padStart(5, '0')}.img")
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IOException("无法读取 ${document.name}")
            val copied = input.use { copyLimited(it, destination, MAX_IMPORT_PAGE_BYTES) }
            totalBytes += copied
            require(totalBytes <= MAX_IMPORT_BYTES) { "导入内容超过可用的大小限制" }
            val bounds = imageBounds(destination)
                ?: throw IOException("${document.name} 不是可读取的图片")
            ImportedPage(bounds.first, bounds.second)
        }
    }

    private suspend fun collectTreeImages(treeUri: Uri): List<LocalDocument> {
        val resolver = context.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val documents = mutableListOf<LocalDocument>()
        suspend fun visit(parentId: String, path: String, depth: Int) {
            currentCoroutineContext().ensureActive()
            require(depth <= MAX_IMPORT_DEPTH) { "文件夹层级过深" }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val childDirectories = mutableListOf<Pair<String, String>>()
            resolver.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ), null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getString(idColumn)
                    val name = cursor.getString(nameColumn).orEmpty().ifBlank { id.substringAfterLast('/') }
                    val childPath = if (path.isBlank()) name else "$path/$name"
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        childDirectories += id to childPath
                    } else if (mime.startsWith("image/") || isImageName(name)) {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        documents += LocalDocument(uri, childPath)
                        require(documents.size <= MAX_IMPORT_PAGES) { "文件夹图片超过 $MAX_IMPORT_PAGES 页" }
                    }
                }
            } ?: throw IOException("无法读取所选文件夹")
            // Some document providers allow only a small number of active cursors. Release the
            // parent cursor before descending so large nested libraries do not exhaust the provider.
            childDirectories.sortedWith(compareByNatural { it.second }).forEach { (id, childPath) ->
                visit(id, childPath, depth + 1)
            }
        }
        visit(rootId, "", 0)
        return documents.sortedWith(compareByNatural(LocalDocument::name))
    }

    private fun retainReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun documentName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty().ifBlank { "图片" } }

    private suspend fun copyLimited(input: java.io.InputStream, destination: File, limit: Long): Long {
        var copied = 0L
        BufferedOutputStream(destination.outputStream()).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 2)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                require(copied <= limit) { "单张图片超过可导入大小" }
                output.write(buffer, 0, count)
            }
        }
        require(copied > 0L) { "图片文件为空" }
        return copied
    }

    private fun imageBounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /** Only explicit download actions create records; opening a reader reuses existing ones. */
    suspend fun enqueue(
        manga: Manga, chapter: Chapter, pages: List<Page>, headers: Map<String, String>,
    ): String = enqueueInternal(manga, chapter, pages, headers)
        ?: chapterKey(manga.id, chapter.id)

    /** Refresh only the exact entry that requested metadata; a later pause/delete/retry wins. */
    suspend fun refreshReadingDownload(
        manga: Manga, chapter: Chapter, pages: List<Page>, headers: Map<String, String>, expectedEntry: DownloadEntry,
    ): Boolean = enqueueInternal(manga, chapter, pages, headers, expectedEntry = expectedEntry) != null

    private suspend fun enqueueInternal(
        manga: Manga, chapter: Chapter, pages: List<Page>, headers: Map<String, String>,
        expectedEntry: DownloadEntry? = null,
    ): String? = withContext(Dispatchers.IO) {
        awaitReady()
        require(pages.isNotEmpty()) { "本章没有可下载的图片" }
        require(pages.all { it.url.startsWith("https://") }) { "仅支持安全的 HTTPS 图片地址" }
        val id = chapterKey(manga.id, chapter.id)
        changeChapter(id) {
            val change = synchronized(lock) {
                val old = records[id]
                if (expectedEntry != null && old?.entry !== expectedEntry) return@changeChapter null
                if (old?.entry?.status in ACTIVE_STATUSES) return@changeChapter id
                old?.let { detachWriter(it) }
            }
            if (change != null) stopWriter(change)
            synchronized(lock) {
                // A later pause/retry/removal supersedes a source refresh that was waiting for I/O.
                if (records[id] !== change?.detached) return@changeChapter null
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
                    if (record.entry.manga.sourceUrl == LOCAL_SOURCE_URL) {
                        if (record.entry.status == DownloadStatus.COMPLETED) return@changeChapter
                        throw IOException("本地文件已损坏，请删除后重新导入")
                    }
                    if (record.entry.needsSourceRefresh || record.entry.status in setOf(DownloadStatus.COMPLETED, DownloadStatus.RUNNING, DownloadStatus.QUEUED) ||
                        (!includePaused && record.entry.status == DownloadStatus.PAUSED)) return@changeChapter
                    generation = record.generation
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
                validatedGenerations.remove(id)
                pageWriteVersions.remove(id)
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
        restoreChapter(chapterKey(mangaId, chapterId))
        synchronized(lock) { records[chapterKey(mangaId, chapterId)]?.pages?.takeIf { it.isNotEmpty() } }
    }

    /** Read-only preview: prefer a saved first image, never start a transfer or enqueue a job. */
    suspend fun coverPage(mangaId: String, chapterId: String, preferLocal: Boolean = true): Page? = withContext(Dispatchers.IO) {
        val id = chapterKey(mangaId, chapterId)
        restoreChapter(id)
        val page = synchronized(lock) { records[id]?.pages?.firstOrNull() } ?: return@withContext null
        val file = pageFile(id, 0)
        if (preferLocal && file.isFile && file.length() > 0L) page.copy(url = file.toURI().toString()) else page
    }

    /** Called for an image when it is needed, never on every download-progress publication. */
    suspend fun resolveReaderPage(mangaId: String, chapterId: String, page: Page): Page = withContext(Dispatchers.IO) {
        val id = chapterKey(mangaId, chapterId)
        restoreChapter(id)
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

    suspend fun prioritizeReadingPage(mangaId: String, chapterId: String, index: Int) = withContext(Dispatchers.IO) {
        val requestContext = currentCoroutineContext()
        synchronized(lock) {
            // A completed transfer may hold this lock while persisting its manifest.
            requestContext.ensureActive()
            val id = chapterKey(mangaId, chapterId)
            if (records[id] != null) readerPositions[id] = index.coerceAtLeast(0)
        }
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
                        error = if (current.entry.manga.sourceUrl == LOCAL_SOURCE_URL)
                            "本地文件已损坏，请删除后重新导入" else "本地章节不完整，继续下载可修复",
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
                val versions = pageWriteVersions.getOrPut(key.id) { mutableMapOf() }
                versions[key.index] = (versions[key.index] ?: 0L) + 1L
                val updatedPages = current.pages.mapIndexed { position, value ->
                    if (position == key.index) value.copy(width = bounds.first, height = bounds.second) else value
                }
                val complete = available.size == current.pages.size && validatedGenerations[key.id] == current.generation
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
    private suspend fun reconcileRestoredRecords() {
        val ids = synchronized(lock) { records.keys.sortedBy { if (it in readerPositions) 0 else 1 } }
        for (id in ids) {
            prepareChapter(id)
            val snapshot = synchronized(lock) { records[id] } ?: continue
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
                            needsSourceRefresh = snapshot.pages.isEmpty() && snapshot.entry.manga.sourceUrl != LOCAL_SOURCE_URL,
                            error = if (snapshot.entry.manga.sourceUrl == LOCAL_SOURCE_URL)
                                "本地文件已损坏，请删除后重新导入" else "本地章节不完整，继续下载可修复",
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
        writeManifest(directory(record.entry.id), record)
        val previous = records[record.entry.id]
        records[record.entry.id] = record
        publish(reorder = previous == null || previous.entry.createdAt != record.entry.createdAt)
    }

    private fun writeManifest(targetDirectory: File, record: DownloadRecord) {
        try {
            targetDirectory.mkdirs()
            val atomic = AtomicFile(File(targetDirectory, "manifest.json"))
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
    }

    private fun publish(reorder: Boolean = true) {
        if (reorder || entryOrder.size != records.size) {
            entryOrder = records.values.sortedByDescending { it.entry.createdAt }.map { it.entry.id }
        }
        _entries.value = entryOrder.mapNotNull { records[it]?.entry }
    }

    private fun memoryFailure(id: String, error: Exception, generation: String? = null) = synchronized(lock) {
        val record = records[id] ?: return@synchronized
        if (generation != null && record.generation != generation) return@synchronized
        records[id] = record.copy(entry = record.entry.copy(status = DownloadStatus.FAILED, error = error.message ?: "无法保存下载状态"))
        publish()
    }

    companion object {
        const val DOWNLOAD_TAG = "mangaflow-chapter-download"
        const val LOCAL_SOURCE_URL = "local://import"
        const val LOCAL_SOURCE_UUID = "local-import"
        private const val LOCAL_PAGE_SCHEME = "local-import://"
        private const val IMPORT_DIRECTORY_PREFIX = ".import-"
        private const val MAX_IMPORT_PAGES = 5_000
        private const val MAX_IMPORT_DEPTH = 16
        private const val MAX_IMPORT_PAGE_BYTES = 128L * 1024L * 1024L
        private const val MAX_IMPORT_BYTES = 1_500L * 1024L * 1024L
        private val ACTIVE_STATUSES = setOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
        internal val transferGate = Mutex()
        @Volatile private var instance: DownloadStore? = null
        fun get(context: Context): DownloadStore = instance ?: synchronized(this) {
            instance ?: DownloadStore(context).also { instance = it }
        }
        private fun workName(id: String) = "mangaflow-download-$id"
        private fun chapterKey(mangaId: String, chapterId: String) = MessageDigest.getInstance("SHA-256")
            .digest("$mangaId\u0000$chapterId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        private fun stableDigest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

private data class ImportedPage(val width: Int, val height: Int)
private data class RawImportedPage(val name: String, val file: File, val width: Int, val height: Int)
private data class LocalDocument(val uri: Uri, val name: String)

private fun isImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf(
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "avif",
)

private fun <T> compareByNatural(selector: (T) -> String): Comparator<T> = Comparator { left, right ->
    naturalCompare(selector(left), selector(right))
}

private fun naturalCompare(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftChar = left[leftIndex]
        val rightChar = right[rightIndex]
        if (leftChar.isDigit() && rightChar.isDigit()) {
            val leftStart = leftIndex
            val rightStart = rightIndex
            while (leftIndex < left.length && left[leftIndex].isDigit()) leftIndex++
            while (rightIndex < right.length && right[rightIndex].isDigit()) rightIndex++
            val leftNumber = left.substring(leftStart, leftIndex).trimStart('0')
            val rightNumber = right.substring(rightStart, rightIndex).trimStart('0')
            val normalizedLeft = leftNumber.ifEmpty { "0" }
            val normalizedRight = rightNumber.ifEmpty { "0" }
            val lengthOrder = normalizedLeft.length.compareTo(normalizedRight.length)
            if (lengthOrder != 0) return lengthOrder
            val numberOrder = normalizedLeft.compareTo(normalizedRight)
            if (numberOrder != 0) return numberOrder
            val zeroOrder = (leftIndex - leftStart).compareTo(rightIndex - rightStart)
            if (zeroOrder != 0) return zeroOrder
        } else {
            val charOrder = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
            if (charOrder != 0) return charOrder
            leftIndex++
            rightIndex++
        }
    }
    return (left.length - leftIndex).compareTo(right.length - rightIndex)
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
