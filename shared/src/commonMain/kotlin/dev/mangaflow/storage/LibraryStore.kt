package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.plugins.SourcePluginStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.DateTimeUnit

data class ReadingProgress(
    val manga: Manga,
    val chapterId: String,
    val chapterTitle: String,
    val pageIndex: Int,
    val pageCount: Int,
    val scrollOffset: Int = 0,
    val updatedAt: Long = wallTimeMillis(),
    val panelIndex: Int = -1,
    val panelLayout: String = "",
)

data class ChapterProgress(
    val pageIndex: Int,
    val pageCount: Int,
    val scrollOffset: Int = 0,
    val chapterTitle: String = "",
    val updatedAt: Long = 0L,
    val panelIndex: Int = -1,
    val panelLayout: String = "",
)

enum class ShelfState { NORMAL, FOLLOWING, HOARDING, PAUSED }

data class ReaderBookmark(
    val pageIndex: Int,
    val note: String = "",
    val updatedAt: Long = wallTimeMillis(),
)

data class LibraryBackupInfo(
    val createdAt: Long,
    val favorites: Int,
    val history: Int,
    val bookmarks: Int,
)

/** Device-local reading state. Neither favorites nor reading history require a source account. */
class LibraryStore(context: Context) {
    private val application = context.applicationContext
    private val disk by lazy { application.getSharedPreferences("mangaflow_library", PRIVATE_STORAGE) }
    private val lock = StorageLock()
    private val backupLock = Mutex()
    private var restoreLegacyLongImages = false
    private val _favorites = MutableStateFlow<List<Manga>>(emptyList())
    private val _history = MutableStateFlow<List<ReadingProgress>>(emptyList())
    private val _chapterHistoryRevision = MutableStateFlow(0L)
    private val _backupRestored = MutableStateFlow(0L)
    val backupRestored: StateFlow<Long> = _backupRestored.asStateFlow()
    private val _shelfStates = MutableStateFlow<Map<String, ShelfState>>(emptyMap())
    private val _pageCollections = MutableStateFlow<List<PageCollection>>(emptyList())
    private val _readingActivity = MutableStateFlow<List<ReadingActivity>>(emptyList())
    private val _preferences = MutableStateFlow(ReaderPreferences())
    private val _mangaPreferences = MutableStateFlow<Map<String, ReaderPreferences>>(emptyMap())
    private val _ready = MutableStateFlow(false)
    private val _restoreError = MutableStateFlow<String?>(null)
    private var stateRestored = false
    private var restoreJob: Job? = null
    private val deferredMutations = mutableListOf<() -> Unit>()
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingChapterPositions = mutableMapOf<String, ChapterProgress>()
    private val pendingLongImagePositions = mutableMapOf<String, Float>()
    private val pendingProgressRemovals = mutableMapOf<String, Long>()
    private var removalSequence = 0L
    private var progressWrite: ProgressWrite? = null
    private var progressWriterRunning = false
    private var progressEpoch = 0L
    private var ignoreStoredProgress = false
    private var readingActivityWrites = 0
    private var readingActivityRevision = 0L
    private var writesIdle = CompletableDeferred(Unit)

    val favorites: StateFlow<List<Manga>> = _favorites.asStateFlow()
    val history: StateFlow<List<ReadingProgress>> = _history.asStateFlow()
    val chapterHistoryRevision: StateFlow<Long> = _chapterHistoryRevision.asStateFlow()
    val shelfStates: StateFlow<Map<String, ShelfState>> = _shelfStates.asStateFlow()
    val pageCollections: StateFlow<List<PageCollection>> = _pageCollections.asStateFlow()
    val readingActivity: StateFlow<List<ReadingActivity>> = _readingActivity.asStateFlow()
    val preferences: StateFlow<ReaderPreferences> = _preferences.asStateFlow()
    val mangaPreferences: StateFlow<Map<String, ReaderPreferences>> = _mangaPreferences.asStateFlow()
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    val restoreError: StateFlow<String?> = _restoreError.asStateFlow()

    init { retryRestore() }

    /** Preference loading and JSON parsing never block the first frame. */
    fun retryRestore(): Unit = synchronized(lock) {
        if (stateRestored || restoreJob?.isActive == true) return@synchronized
        _restoreError.value = null
        restoreJob = progressScope.launch(start = CoroutineStart.LAZY) {
            try {
                val legacy = readLegacyLongImageMigration()
                val favorites = readArray("favorites", ::readManga).distinctBy(Manga::id)
                val history = readArray("history", ::readProgress).distinctBy { it.manga.id }
                val states = readShelfStates()
                val pictures = readArray(PAGE_COLLECTIONS_KEY, ::readPageCollection).distinctBy(PageCollection::key)
                val activity = readArray(READING_ACTIVITY_KEY, ::readReadingActivity).distinctBy { it.manga.id }
                val preferences = readPreferences()
                val perManga = readMangaPreferences()
                synchronized(lock) {
                    restoreLegacyLongImages = legacy
                    _favorites.value = favorites
                    _history.value = history
                    _shelfStates.value = states
                    _pageCollections.value = pictures
                    _readingActivity.value = activity
                    _preferences.value = preferences
                    _mangaPreferences.value = perManga
                    stateRestored = true
                    // Replay early user intent against restored data, never against an empty shelf.
                    val pending = deferredMutations.toList()
                    deferredMutations.clear()
                    try { pending.forEach { it() } }
                    finally { _ready.value = true }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                _restoreError.value = failure.message ?: "本地记录暂时无法恢复，请重试"
            }
        }.also { it.start() }
    }

    suspend fun awaitReady() { ready.first { it } }

    /** Await the actual library writers before flushing the platform preference write queue. */
    suspend fun flushPendingWrites(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) { restoreJob }?.join()
        val restored = synchronized(lock) {
            if (stateRestored && progressWrite != null && !progressWriterRunning) startProgressWriter()
            stateRestored
        }
        if (!restored) return@withContext false
        waitForProgressWriter()
        val libraryPersisted = synchronized(lock) {
            if (progressWriterRunning || readingActivityWrites > 0 || progressWrite != null) false
            else runCatching {
                // Also commit the latest in-memory time snapshot if an earlier async encoding
                // failed; commit waits for preceding Android apply() operations as well.
                val activity = JSONArray(_readingActivity.value.map { it.toJson() }).toString()
                disk.edit().putString(READING_ACTIVITY_KEY, activity).commit()
            }.getOrDefault(false)
        }
        val preferencesPersisted = flushLocalPreferences()
        libraryPersisted && preferencesPersisted
    }

    /** Called under lock, in the same order as user actions arriving during restoration. */
    private fun deferUntilReady(action: () -> Unit): Boolean {
        if (stateRestored) return false
        deferredMutations += action
        return true
    }

    fun isFavorite(mangaId: String): Boolean = _favorites.value.any { it.id == mangaId }

    fun toggleFavorite(manga: Manga): Unit = synchronized(lock) {
        if (deferUntilReady { toggleFavorite(manga) }) return@synchronized
        val removing = isFavorite(manga.id)
        val next = if (removing) _favorites.value.filterNot { it.id == manga.id }
        else listOf(manga) + _favorites.value
        _favorites.value = next
        disk.edit().putString("favorites", JSONArray(next.map { it.toJson() }).toString()).apply()
        if (removing) removeShelfStateLocked(manga.id)
    }

    /** Idempotent removal: a stale item menu can never add the manga back. */
    fun removeFavorite(mangaId: String): Unit = synchronized(lock) {
        if (deferUntilReady { removeFavorite(mangaId) }) return@synchronized
        val next = _favorites.value.filterNot { it.id == mangaId }
        if (next.size == _favorites.value.size) return@synchronized
        _favorites.value = next
        disk.edit().putString("favorites", JSONArray(next.map { it.toJson() }).toString()).apply()
        removeShelfStateLocked(mangaId)
    }

    fun setShelfState(mangaId: String, state: ShelfState): Unit = synchronized(lock) {
        if (deferUntilReady { setShelfState(mangaId, state) }) return@synchronized
        if (_favorites.value.none { it.id == mangaId }) return@synchronized
        val next = LinkedHashMap(_shelfStates.value)
        if (state == ShelfState.NORMAL) next.remove(mangaId) else next[mangaId] = state
        if (next == _shelfStates.value) return@synchronized
        _shelfStates.value = next
        persistShelfStates(next)
    }

    private fun removeShelfStateLocked(mangaId: String) {
        if (mangaId !in _shelfStates.value) return
        val next = LinkedHashMap(_shelfStates.value)
        next.remove(mangaId)
        _shelfStates.value = next
        persistShelfStates(next)
    }

    private fun persistShelfStates(states: Map<String, ShelfState>) {
        val json = JSONObject()
        states.forEach { (id, state) -> json.put(id, state.name) }
        disk.edit().putString(SHELF_STATES_KEY, json.toString()).apply()
    }

    private fun readShelfStates(): Map<String, ShelfState> = runCatching {
        val json = JSONObject(disk.getString(SHELF_STATES_KEY, "{}") ?: "{}")
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                ShelfState.entries.firstOrNull { it.name == json.optString(id) }?.let { put(id, it) }
            }
        }
    }.getOrDefault(emptyMap())

    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        backupLock.withLock {
            awaitReady()
            val sourceStore = SourcePluginStore.get(application)
            sourceStore.ready.await()
            val sourcePackages = sourceStore.exportBackup()
            waitForProgressWriter()
            val root = synchronized(lock) {
                val entries = JSONArray()
                disk.all.entries.sortedBy { it.key }.forEach { (key, value) ->
                    val entry = JSONObject().put("key", key)
                    when (value) {
                        is String -> entry.put("type", "string").put("value", value)
                        is Boolean -> entry.put("type", "boolean").put("value", value)
                        is Int -> entry.put("type", "int").put("value", value)
                        is Long -> entry.put("type", "long").put("value", value)
                        is Float -> entry.put("type", "float").put("value", value.toDouble())
                        is Set<*> -> entry.put("type", "stringSet").put("value",
                            JSONArray(value.filterIsInstance<String>().sorted()))
                        else -> error("无法备份字段 $key")
                    }
                    entries.put(entry)
                }
                JSONObject()
                    .put("format", BACKUP_FORMAT)
                    .put("version", BACKUP_VERSION)
                    .put("createdAt", wallTimeMillis())
                    .put("entries", entries)
            }
            root.put("sourcePackages", sourcePackages).toString().also {
                requireBackupSize(it, "阅读备份（含漫画源代码）超过 8 MiB，已停止导出。")
            }
        }
    }

    suspend fun inspectBackup(encoded: String): LibraryBackupInfo = withContext(Dispatchers.IO) {
        backupInfo(decodeBackup(encoded))
    }

    suspend fun restoreBackup(encoded: String): LibraryBackupInfo = withContext(Dispatchers.IO) {
        backupLock.withLock {
            awaitReady()
            // Validate every source package before either store changes. Validation never runs JS.
            val decoded = decodeBackup(encoded)
            val info = backupInfo(decoded)
            waitForProgressWriter()
            currentCoroutineContext().ensureActive()
            // Once the validated disk writes start, leaving the screen must not cancel
            // between the source index and library preferences.
            withContext(NonCancellable) {
                decoded.sourcePackages?.let { packages ->
                    try {
                        SourcePluginStore.get(application).restoreBackup(packages)
                    } catch (failure: Exception) {
                        throw IllegalStateException("漫画源恢复未完成，书架与阅读设置未替换。" +
                            failure.message.orEmpty(), failure)
                    }
                }
                waitForProgressWriter()
                synchronized(lock) {
                    val previous = disk.all.toMap()
                    val writeFailure = try {
                        if (writeBackupValues(decoded.values)) null
                        else IllegalStateException("无法写入备份，请检查存储空间。")
                    } catch (failure: Exception) { failure }
                    if (writeFailure != null) {
                        // SharedPreferences can update memory even when its disk commit fails.
                        // Put back the original snapshot and report the source store separately.
                        val restored = runCatching { writeBackupValues(previous) }.getOrDefault(false)
                        val libraryState = if (restored) "书架与阅读设置未替换，原数据已保留。"
                            else "书架与阅读设置未能完整写入，状态可能部分更新，请检查存储空间后重试。"
                        throw IllegalStateException(if (decoded.sourcePackages != null)
                            "漫画源已恢复；$libraryState" else libraryState, writeFailure)
                    }

                    progressEpoch++
                    readingActivityRevision++
                    progressWrite = null
                    pendingChapterPositions.clear()
                    pendingLongImagePositions.clear()
                    pendingProgressRemovals.clear()
                    ignoreStoredProgress = false
                    restoreLegacyLongImages = readLegacyLongImageMigration()
                    _favorites.value = readArray("favorites") { readManga(it) }.distinctBy(Manga::id)
                    _history.value = readArray("history", ::readProgress).distinctBy { it.manga.id }
                    _chapterHistoryRevision.value++
                    _shelfStates.value = readShelfStates()
                    _pageCollections.value = readArray(PAGE_COLLECTIONS_KEY, ::readPageCollection).distinctBy(PageCollection::key)
                    _readingActivity.value = readArray(READING_ACTIVITY_KEY, ::readReadingActivity).distinctBy { it.manga.id }
                    _preferences.value = readPreferences()
                    _mangaPreferences.value = readMangaPreferences()
                    _backupRestored.value++
                    info
                }
            }
        }
    }

    private fun writeBackupValues(values: Map<String, Any?>): Boolean {
        val editor = disk.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("备份字段 $key 的值不支持")
            }
        }
        return UnifiedChapterStore.withLibraryRestore { editor.commit() }
    }

    private suspend fun waitForProgressWriter() {
        while (true) {
            val pending = synchronized(lock) {
                if (progressWriterRunning || readingActivityWrites > 0) writesIdle else null
            } ?: return
            pending.await()
        }
    }

    /** Called under lock; the same completion spans overlapping progress and time writers. */
    private fun markWritesBusy() {
        if (writesIdle.isCompleted) writesIdle = CompletableDeferred()
    }

    private fun markWritesIdle() {
        if (!progressWriterRunning && readingActivityWrites == 0) writesIdle.complete(Unit)
    }

    private data class DecodedBackup(
        val createdAt: Long,
        val values: Map<String, Any>,
        val sourcePackages: JSONArray?,
    )

    private suspend fun decodeBackup(encoded: String): DecodedBackup {
        requireBackupSize(encoded, "阅读备份超过 8 MiB，已停止读取。")
        val root = JSONObject(encoded)
        require(root.optString("format") == BACKUP_FORMAT) { "这不是 MangaFlow 备份文件" }
        require(root.optInt("version") == BACKUP_VERSION) { "备份版本暂不支持" }
        val array = root.optJSONArray("entries") ?: error("备份内容不完整")
        val values = linkedMapOf<String, Any>()
        repeat(array.length()) { index ->
            val entry = array.optJSONObject(index) ?: error("备份字段损坏")
            val key = entry.optString("key").takeIf(String::isNotBlank) ?: error("备份字段缺少名称")
            require(key.length <= 512) { "备份字段名称异常" }
            val value: Any = when (entry.optString("type")) {
                "string" -> entry.optString("value")
                "boolean" -> entry.optBoolean("value")
                "int" -> entry.optInt("value")
                "long" -> entry.optLong("value")
                "float" -> entry.optDouble("value").toFloat()
                "stringSet" -> {
                    val set = entry.optJSONArray("value") ?: JSONArray()
                    (0 until set.length()).mapNotNull { set.optString(it).takeIf(String::isNotBlank) }.toSet()
                }
                else -> error("备份字段类型不支持")
            }
            values[key] = value
        }
        (values[UnifiedChapterStore.KEY] as? String)?.let { UnifiedChapterStore.validateBackup(it) }
        (values[UnifiedChapterStore.ANCHOR_KEY] as? String)?.let { UnifiedChapterStore.validateAnchors(it) }
        val sourcePackages = if (root.has("sourcePackages")) {
            val packages = root.optJSONArray("sourcePackages") ?: error("备份中的漫画源列表格式无效。")
            require(packages.length() <= MAX_BACKUP_SOURCE_PACKAGES) { "备份中的漫画源超过 64 个。" }
            var packageBytes = 0L
            repeat(packages.length()) { index ->
                val item = packages.optJSONObject(index) ?: error("备份中的第 ${index + 1} 个漫画源格式无效。")
                packageBytes += item.toString().encodeToByteArray().size
                require(packageBytes <= MAX_BACKUP_BYTES) { "备份中的漫画源内容超过 8 MiB。" }
            }
            val sourceStore = SourcePluginStore.get(application)
            sourceStore.ready.await()
            sourceStore.validateBackup(packages)
            packages
        } else null
        return DecodedBackup(root.optLong("createdAt").coerceAtLeast(0L), values, sourcePackages)
    }

    private fun requireBackupSize(encoded: String, message: String) {
        require(encoded.length <= MAX_BACKUP_BYTES && encoded.encodeToByteArray().size <= MAX_BACKUP_BYTES) {
            message
        }
    }

    private fun backupInfo(decoded: DecodedBackup): LibraryBackupInfo {
        fun arraySize(key: String): Int = (decoded.values[key] as? String)?.let { value ->
            runCatching { JSONArray(value).length() }.getOrDefault(0)
        } ?: 0
        return LibraryBackupInfo(
            createdAt = decoded.createdAt,
            favorites = arraySize("favorites"),
            history = arraySize("history"),
            bookmarks = arraySize(PAGE_COLLECTIONS_KEY) + decoded.values.keys
                .filter { it.startsWith(BOOKMARK_PREFIX) }
                .sumOf { key ->
                    val done = (decoded.values["picture_migrated:$key"] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
                    val old = runCatching { JSONArray(decoded.values[key] as? String ?: "[]") }.getOrDefault(JSONArray())
                    (0 until old.length()).count { index ->
                        old.optJSONObject(index)?.optInt("pageIndex", -1)?.let { it >= 0 && it.toString() !in done } == true
                    }
                },
        )
    }

    fun progressFor(mangaId: String): ReadingProgress? = _history.value.firstOrNull { it.manga.id == mangaId }

    fun progressFor(mangaId: String, chapterId: String): ChapterProgress? = synchronized(lock) {
        if (!stateRestored) return@synchronized null
        val key = chapterProgressKey(mangaId, chapterId)
        pendingChapterPositions[key]?.let { return@synchronized it }
        val stored = if (ignoreStoredProgress || mangaId in pendingProgressRemovals) null else disk.getString(key, null)?.let { encoded ->
            runCatching {
                val json = JSONObject(encoded)
                ChapterProgress(
                    pageIndex = json.getInt("pageIndex").coerceAtLeast(0),
                    pageCount = json.getInt("pageCount").coerceAtLeast(0),
                    scrollOffset = json.optInt("scrollOffset").coerceAtLeast(0),
                    chapterTitle = json.optString("chapterTitle").take(160),
                    updatedAt = json.optLong("updatedAt").coerceAtLeast(0L),
                    panelIndex = json.optInt("panelIndex", -1).coerceIn(-1, 127),
                    panelLayout = json.optString("panelLayout").take(160),
                )
            }.getOrNull()
        }
        stored ?: progressFor(mangaId)?.takeIf { it.chapterId == chapterId }?.chapterProgress()
    }

    /** Read compact per-chapter keys on IO; manga metadata is shared, never copied to each key. */
    suspend fun chapterHistoryFor(manga: Manga): List<ReadingProgress> = withContext(Dispatchers.IO) {
        awaitReady()
        val prefix = "$CHAPTER_PROGRESS_PREFIX${manga.id.length}:${manga.id}"
        val (encoded, pending, recent) = synchronized(lock) {
            val stored = if (ignoreStoredProgress || manga.id in pendingProgressRemovals) emptyMap() else
                disk.all.mapNotNull { (key, value) ->
                    if (key.startsWith(prefix) && key.length > prefix.length && value is String)
                        key.removePrefix(prefix) to value else null
                }.toMap()
            Triple(stored, pendingChapterPositions.filterKeys { it.startsWith(prefix) && it.length > prefix.length }
                .mapKeys { it.key.removePrefix(prefix) }, progressFor(manga.id))
        }
        // Parse away from the reader's lock. The UI owns cancellation via the revision above.
        val positions = linkedMapOf<String, ChapterProgress>()
        encoded.forEach { (chapterId, value) ->
            runCatching {
                val json = JSONObject(value)
                ChapterProgress(json.getInt("pageIndex").coerceAtLeast(0),
                    json.getInt("pageCount").coerceAtLeast(0), json.optInt("scrollOffset").coerceAtLeast(0),
                    json.optString("chapterTitle").take(160), json.optLong("updatedAt").coerceAtLeast(0L),
                    json.optInt("panelIndex", -1).coerceIn(-1, 127), json.optString("panelLayout").take(160))
            }.getOrNull()?.let { positions[chapterId] = it }
        }
        positions.putAll(pending)
        recent?.let {
            val saved = positions[it.chapterId]
            if (saved == null) positions[it.chapterId] = it.chapterProgress()
            else if (saved.chapterTitle.isBlank()) positions[it.chapterId] = saved.copy(
                chapterTitle = it.chapterTitle, updatedAt = maxOf(saved.updatedAt, it.updatedAt))
        }
        positions.mapNotNull { (chapterId, position) ->
            if (position.pageCount <= 0) null else ReadingProgress(manga, chapterId, position.chapterTitle,
                position.pageIndex.coerceIn(0, position.pageCount - 1), position.pageCount,
                position.scrollOffset, position.updatedAt, position.panelIndex, position.panelLayout)
        }.sortedWith(compareByDescending<ReadingProgress> { it.updatedAt }.thenBy { it.chapterTitle }.thenBy { it.chapterId })
    }

    fun longImagePositionFor(mangaId: String, chapterId: String, pageIndex: Int): Float? = synchronized(lock) {
        if (!stateRestored) return@synchronized null
        if (!restoreLegacyLongImages) return@synchronized null
        val key = longImagePositionKey(mangaId, chapterId, pageIndex)
        pendingLongImagePositions[key]?.let { return@synchronized it }
        if (ignoreStoredProgress || mangaId in pendingProgressRemovals) return@synchronized null
        if (!disk.contains(key)) return@synchronized null
        runCatching { disk.getFloat(key, 0f) }.getOrNull()?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
    }

    fun saveProgress(
        manga: Manga, chapter: Chapter, pageIndex: Int, pageCount: Int, scrollOffset: Int = 0,
        clearLegacyLongImagePosition: Boolean = false,
        updateRecent: Boolean = true,
        panelIndex: Int = -1,
        panelLayout: String = "",
    ) {
        if (pageCount <= 0) return
        synchronized(lock) {
            if (deferUntilReady { saveProgress(manga, chapter, pageIndex, pageCount, scrollOffset,
                clearLegacyLongImagePosition, updateRecent, panelIndex, panelLayout) }) return@synchronized
            val progress = ReadingProgress(
                manga = manga,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                pageIndex = pageIndex.coerceIn(0, pageCount - 1),
                pageCount = pageCount,
                scrollOffset = scrollOffset.coerceAtLeast(0),
                panelIndex = panelIndex.coerceIn(-1, 127),
                panelLayout = panelLayout.take(160),
            )
            val recent = progressFor(manga.id)
            val replaceRecent = updateRecent || recent == null || recent.chapterId == chapter.id
            val write = pendingWrite()
            // Retain the 0.1.2 resume point before another chapter replaces it in recent history.
            progressFor(manga.id)?.let { previous ->
                val previousKey = chapterProgressKey(manga.id, previous.chapterId)
                val saved = progressFor(manga.id, previous.chapterId)
                if ((previousKey !in pendingChapterPositions && (ignoreStoredProgress || !disk.contains(previousKey))) ||
                    saved == null || saved.chapterTitle.isBlank() || saved.updatedAt <= 0L) {
                    val position = (saved ?: previous.chapterProgress()).copy(
                        chapterTitle = previous.chapterTitle.take(160), updatedAt = previous.updatedAt)
                    pendingChapterPositions[previousKey] = position
                    write.chapters[previousKey] = position
                }
            }
            val key = chapterProgressKey(manga.id, chapter.id)
            val position = progress.chapterProgress()
            pendingChapterPositions[key] = position
            write.chapters[key] = position
            if (clearLegacyLongImagePosition && (longImagePositionFor(manga.id, chapter.id, progress.pageIndex) ?: 0f) > 0f) {
                // Persist the converted outer offset and consume the old inner pan in one batch.
                val legacyKey = longImagePositionKey(manga.id, chapter.id, progress.pageIndex)
                pendingLongImagePositions[legacyKey] = 0f
                write.longImages[legacyKey] = 0f
            }
            if (replaceRecent) {
                val next = (listOf(progress) + _history.value.filterNot { it.manga.id == manga.id }).take(500)
                _history.value = next
                write.history = next
            }
            _chapterHistoryRevision.value++
            startProgressWriter()
        }
    }

    fun clearHistory(): Unit = synchronized(lock) {
        if (deferUntilReady { clearHistory() }) return@synchronized
        _history.value = emptyList()
        _chapterHistoryRevision.value++
        pendingChapterPositions.clear()
        pendingLongImagePositions.clear()
        pendingProgressRemovals.clear()
        // Queue clearing after any in-flight write. Readers must not see its old disk values
        // while that write finishes, and new reading positions can join the clearing batch.
        progressEpoch++
        ignoreStoredProgress = true
        progressWrite = ProgressWrite(progressEpoch, clear = true)
        startProgressWriter()
    }

    /** Queue deletion after older writes so an in-flight save cannot restore a removed record. */
    fun removeHistory(mangaId: String): Unit = synchronized(lock) {
        if (deferUntilReady { removeHistory(mangaId) }) return@synchronized
        val next = _history.value.filterNot { it.manga.id == mangaId }
        _history.value = next
        _chapterHistoryRevision.value++
        pendingChapterPositions.keys.removeAll { isProgressKeyFor(it, mangaId) }
        pendingLongImagePositions.keys.removeAll { isProgressKeyFor(it, mangaId) }
        val write = pendingWrite()
        write.chapters.keys.removeAll { isProgressKeyFor(it, mangaId) }
        write.longImages.keys.removeAll { isProgressKeyFor(it, mangaId) }
        val sequence = ++removalSequence
        pendingProgressRemovals[mangaId] = sequence
        write.removedManga[mangaId] = sequence
        write.history = next
        startProgressWriter()
    }

    /** Called under [lock]; multiple pending saves share one latest history snapshot. */
    private fun pendingWrite(): ProgressWrite = progressWrite ?: ProgressWrite(progressEpoch).also { progressWrite = it }

    private fun startProgressWriter() {
        if (progressWriterRunning) return
        markWritesBusy()
        progressWriterRunning = true
        progressScope.launch { drainProgressWrites() }
    }

    private fun drainProgressWrites() {
        while (true) {
            val write = synchronized(lock) {
                val next = progressWrite
                if (next == null) {
                    progressWriterRunning = false
                    markWritesIdle()
                    return
                }
                progressWrite = null
                next
            }
            // JSON and disk I/O never hold the UI-facing lock. commit() runs only on IO,
            // avoiding apply() finishers that Android waits for during lifecycle changes.
            val persisted = runCatching {
                val editor = disk.edit()
                if (write.clear) {
                    editor.remove("history")
                    disk.all.keys.filter {
                        it.startsWith(CHAPTER_PROGRESS_PREFIX) || it.startsWith(LONG_IMAGE_POSITION_PREFIX)
                    }.forEach { editor.remove(it) }
                }
                if (write.removedManga.isNotEmpty()) {
                    disk.all.keys.filter { key -> write.removedManga.keys.any { isProgressKeyFor(key, it) } }
                        .forEach { editor.remove(it) }
                }
                write.chapters.forEach { (key, position) -> editor.putString(key, position.toJson().toString()) }
                write.longImages.forEach { (key, position) -> editor.putFloat(key, position) }
                write.history?.let { history -> editor.putString("history", JSONArray(history.map { it.toJson() }).toString()) }
                editor.commit()
            }.getOrDefault(false)
            synchronized(lock) {
                if (write.epoch == progressEpoch) {
                    if (persisted) {
                        if (write.clear) ignoreStoredProgress = false
                        write.removedManga.forEach { (mangaId, sequence) ->
                            if (pendingProgressRemovals[mangaId] == sequence) pendingProgressRemovals.remove(mangaId)
                        }
                        write.chapters.forEach { (key, position) ->
                            if (pendingChapterPositions[key] == position) pendingChapterPositions.remove(key)
                        }
                        write.longImages.forEach { (key, position) ->
                            if (pendingLongImagePositions[key] == position) pendingLongImagePositions.remove(key)
                        }
                    } else {
                        // Retain unsaved positions for the next save; do not spin on a full disk.
                        val newer = progressWrite
                        progressWrite = write.apply {
                            clear = clear || newer?.clear == true
                            history = newer?.history ?: history
                            newer?.removedManga?.forEach { (mangaId, sequence) ->
                                chapters.keys.removeAll { isProgressKeyFor(it, mangaId) }
                                longImages.keys.removeAll { isProgressKeyFor(it, mangaId) }
                                removedManga[mangaId] = sequence
                            }
                            newer?.chapters?.let(chapters::putAll)
                            newer?.longImages?.let(longImages::putAll)
                        }
                        progressWriterRunning = false
                        markWritesIdle()
                        return
                    }
                }
            }
        }
    }

    private data class ProgressWrite(
        val epoch: Long,
        var clear: Boolean = false,
        var history: List<ReadingProgress>? = null,
        val chapters: MutableMap<String, ChapterProgress> = linkedMapOf(),
        val longImages: MutableMap<String, Float> = linkedMapOf(),
        val removedManga: MutableMap<String, Long> = linkedMapOf(),
    )

    fun togglePageCollection(collection: PageCollection): Unit = synchronized(lock) {
        if (deferUntilReady { togglePageCollection(collection) }) return@synchronized
        val present = _pageCollections.value.any { it.key == collection.key }
        val next = _pageCollections.value.filterNot { it.key == collection.key }
        persistPageCollections(if (present) next else listOf(collection) + next)
    }

    fun removePageCollection(key: String): Unit = synchronized(lock) {
        if (deferUntilReady { removePageCollection(key) }) return@synchronized
        persistPageCollections(_pageCollections.value.filterNot { it.key == key })
    }

    private fun persistPageCollections(values: List<PageCollection>) {
        _pageCollections.value = values
        disk.edit().putString(PAGE_COLLECTIONS_KEY, JSONArray(values.map { it.toJson() }).toString()).apply()
    }

    /** Old explicit page markers become pictures once their real chapter images are available. */
    fun migratePageBookmarks(manga: Manga, chapter: Chapter, pages: List<Page>): Unit = synchronized(lock) {
        if (deferUntilReady { migratePageBookmarks(manga, chapter, pages) }) return@synchronized
        val migrated = "picture_migrated:" + bookmarkKey(manga.id, chapter.id)
        val done = disk.getStringSet(migrated, emptySet()).orEmpty()
        val old = bookmarksFor(manga.id, chapter.id).filterNot { it.pageIndex.toString() in done }
            .mapNotNull { bookmark ->
                pages.getOrNull(bookmark.pageIndex)?.let { page ->
                    PageCollection(manga, chapter, bookmark.pageIndex,
                        previewUrls = listOf(page.url), savedAt = bookmark.updatedAt)
                }
            }
        if (old.isNotEmpty()) {
            persistPageCollections((_pageCollections.value + old)
                .distinctBy(PageCollection::key).sortedByDescending(PageCollection::savedAt))
            disk.edit().putStringSet(migrated, done + old.map { it.firstPage.toString() }).apply()
        }
    }

    /** Monotonic foreground time is allocated to local calendar days, including midnight/DST. */
    fun recordReadingTime(manga: Manga, elapsedMillis: Long, endedAt: Long = wallTimeMillis()) {
        if (elapsedMillis <= 0L) return
        synchronized(lock) {
            if (deferUntilReady { recordReadingTime(manga, elapsedMillis, endedAt) }) return@synchronized
            val previous = _readingActivity.value.firstOrNull { it.manga.id == manga.id }
                ?: ReadingActivity(manga)
            val days = previous.days.toMutableMap()
            val zone = TimeZone.currentSystemDefault()
            var cursor = endedAt - elapsedMillis
            while (cursor < endedAt) {
                val date = Instant.fromEpochMilliseconds(cursor).toLocalDateTime(zone).date
                val boundary = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone).toEpochMilliseconds()
                val end = minOf(endedAt, boundary)
                val amount = end - cursor
                if (amount <= 0) break
                val old = days[date.toString()] ?: 0L
                days[date.toString()] = old + amount.coerceAtMost(Long.MAX_VALUE - old)
                cursor = end
            }
            val next = ReadingActivity(manga,
                previous.totalMillis + elapsedMillis.coerceAtMost(Long.MAX_VALUE - previous.totalMillis),
                endedAt, days.toMap())
            val values = listOf(next) + _readingActivity.value.filterNot { it.manga.id == manga.id }
            _readingActivity.value = values
            val revision = ++readingActivityRevision
            markWritesBusy()
            readingActivityWrites++
            progressScope.launch {
                try {
                    val encoded = JSONArray(values.map { it.toJson() }).toString()
                    synchronized(lock) {
                        if (revision == readingActivityRevision) {
                            disk.edit().putString(READING_ACTIVITY_KEY, encoded).apply()
                        }
                    }
                } finally {
                    synchronized(lock) { readingActivityWrites--; markWritesIdle() }
                }
            }
        }
    }

    /** Explicit page bookmarks survive ordinary history cleanup because the user created them deliberately. */
    fun bookmarksFor(mangaId: String, chapterId: String): List<ReaderBookmark> = synchronized(lock) {
        if (!stateRestored) return@synchronized emptyList()
        val encoded = disk.getString(bookmarkKey(mangaId, chapterId), "[]") ?: "[]"
        runCatching {
            val array = JSONArray(encoded)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { json ->
                    val page = json.optInt("pageIndex", -1)
                    if (page < 0) null else ReaderBookmark(
                        pageIndex = page,
                        note = json.optString("note"),
                        updatedAt = json.optLong("updatedAt", 0L),
                    )
                }
            }.distinctBy(ReaderBookmark::pageIndex).sortedBy(ReaderBookmark::pageIndex)
        }.getOrDefault(emptyList())
    }

    fun saveBookmark(mangaId: String, chapterId: String, pageIndex: Int, note: String): List<ReaderBookmark> =
        synchronized(lock) {
            if (deferUntilReady { saveBookmark(mangaId, chapterId, pageIndex, note) }) return@synchronized emptyList()
            if (pageIndex < 0) return@synchronized bookmarksFor(mangaId, chapterId)
            val next = (bookmarksFor(mangaId, chapterId).filterNot { it.pageIndex == pageIndex } +
                ReaderBookmark(pageIndex, note.trim().take(240)))
                .sortedBy(ReaderBookmark::pageIndex)
            persistBookmarks(mangaId, chapterId, next)
            next
        }

    fun removeBookmark(mangaId: String, chapterId: String, pageIndex: Int): List<ReaderBookmark> =
        synchronized(lock) {
            if (deferUntilReady { removeBookmark(mangaId, chapterId, pageIndex) }) return@synchronized emptyList()
            val next = bookmarksFor(mangaId, chapterId).filterNot { it.pageIndex == pageIndex }
            persistBookmarks(mangaId, chapterId, next)
            next
        }

    private fun persistBookmarks(mangaId: String, chapterId: String, bookmarks: List<ReaderBookmark>) {
        val key = bookmarkKey(mangaId, chapterId)
        val editor = disk.edit()
        if (bookmarks.isEmpty()) editor.remove(key)
        else editor.putString(key, JSONArray(bookmarks.map { bookmark ->
            JSONObject().apply {
                put("pageIndex", bookmark.pageIndex)
                put("note", bookmark.note)
                put("updatedAt", bookmark.updatedAt)
            }
        }).toString())
        editor.apply()
    }

    /** Explicit spread corrections live separately from reading history and source image data. */
    fun spreadSinglesFor(mangaId: String, chapterId: String): Set<Int> = synchronized(lock) {
        if (!stateRestored) return@synchronized emptySet()
        val value = disk.getString(spreadKey(mangaId, chapterId), "[]") ?: "[]"
        runCatching {
            val json = JSONArray(value)
            (0 until json.length()).mapNotNull { json.optInt(it, -1).takeIf { index -> index >= 0 } }.toSet()
        }.getOrDefault(emptySet())
    }

    fun saveSpreadSingles(mangaId: String, chapterId: String, pages: Set<Int>): Unit = synchronized(lock) {
        if (deferUntilReady { saveSpreadSingles(mangaId, chapterId, pages) }) return@synchronized
        val key = spreadKey(mangaId, chapterId)
        val cleaned = pages.filter { it >= 0 }.sorted()
        val editor = disk.edit()
        if (cleaned.isEmpty()) editor.remove(key)
        else editor.putString(key, JSONArray(cleaned).toString())
        editor.apply()
    }

    fun updatePreferences(preferences: ReaderPreferences): Unit = synchronized(lock) {
        if (deferUntilReady { updatePreferences(preferences) }) return@synchronized
        val next = preferences.sanitized()
        _preferences.value = next
        disk.edit().putString("reader_preferences", next.toJson().toString()).apply()
    }

    /**
     * Reading layout is remembered per work; glass material follows the component's role,
     * not a global or per-manga preference.
     */
    fun updateMangaPreferences(mangaId: String, preferences: ReaderPreferences): Unit = synchronized(lock) {
        if (deferUntilReady { updateMangaPreferences(mangaId, preferences) }) return@synchronized
        if (mangaId.isBlank()) return@synchronized
        val next = preferences.sanitized()
        val updated = LinkedHashMap(_mangaPreferences.value)
        updated[mangaId] = next
        _mangaPreferences.value = updated
        val root = JSONObject()
        updated.forEach { (id, value) -> root.put(id, value.toJson()) }
        disk.edit().putString(MANGA_READER_PREFERENCES_KEY, root.toString()).apply()
    }

    private fun readPreferences(): ReaderPreferences = runCatching {
        JSONObject(disk.getString("reader_preferences", "{}") ?: "{}").toReaderPreferences()
    }.getOrDefault(ReaderPreferences())

    private fun readMangaPreferences(): Map<String, ReaderPreferences> = runCatching {
        val root = JSONObject(disk.getString(MANGA_READER_PREFERENCES_KEY, "{}") ?: "{}")
        buildMap {
            val keys = root.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                root.optJSONObject(id)?.let { put(id, it.toReaderPreferences()) }
            }
        }
    }.getOrDefault(emptyMap())

    private fun JSONObject.toReaderPreferences(): ReaderPreferences {
        val storedMode = optString("mode")
        return ReaderPreferences(
            mode = when (storedMode) {
                "WEBTOON" -> ReadingMode.VERTICAL
                else -> ReadingMode.entries.firstOrNull { it.name == storedMode } ?: ReadingMode.VERTICAL
            },
            theme = ReaderTheme.entries.firstOrNull { it.name == optString("theme") } ?: ReaderTheme.PAPER,
            keepScreenOn = optBoolean("keepScreenOn", true),
            // The former webtoon choice always rendered without page gaps.
            pageGap = storedMode != "WEBTOON" && optBoolean("pageGap", false),
            doublePage = optBoolean("doublePage", false),
            coverAlone = optBoolean("coverAlone", true),
            dimAmount = optDouble("dimAmount", 0.0).toFloat().coerceIn(0f, 0.65f),
            discoverColumns = optInt("discoverColumns", 3).coerceIn(1, 4),
            updateColumns = optInt("updateColumns", 3).coerceIn(1, 4),
            desktopDiscoverColumns = optInt("desktopDiscoverColumns", 0).coerceIn(0, 8),
            desktopUpdateColumns = optInt("desktopUpdateColumns", 0).coerceIn(0, 8),
            panelEngine = dev.mangaflow.reader.panels.PanelEngine.entries.firstOrNull {
                it.name == optString("panelEngine")
            } ?: dev.mangaflow.reader.panels.PanelEngine.OFF,
            panelDirection = dev.mangaflow.reader.panels.PanelDirection.entries.firstOrNull {
                it.name == optString("panelDirection")
            } ?: dev.mangaflow.reader.panels.PanelDirection.RIGHT_TO_LEFT,
            panelLearnedOrder = optBoolean("panelLearnedOrder", false),
        )
    }

    private fun ReaderPreferences.sanitized(): ReaderPreferences =
        copy(dimAmount = dimAmount.coerceIn(0f, 0.65f),
            discoverColumns = discoverColumns.coerceIn(1, 4), updateColumns = updateColumns.coerceIn(1, 4),
            desktopDiscoverColumns = desktopDiscoverColumns.coerceIn(0, 8),
            desktopUpdateColumns = desktopUpdateColumns.coerceIn(0, 8))

    private fun ReaderPreferences.toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        put("theme", theme.name)
        put("keepScreenOn", keepScreenOn)
        put("pageGap", pageGap)
        put("doublePage", doublePage)
        put("coverAlone", coverAlone)
        put("dimAmount", dimAmount.toDouble())
        put("discoverColumns", discoverColumns)
        put("updateColumns", updateColumns)
        put("desktopDiscoverColumns", desktopDiscoverColumns)
        put("desktopUpdateColumns", desktopUpdateColumns)
        put("panelEngine", panelEngine.name)
        put("panelDirection", panelDirection.name)
        put("panelLearnedOrder", panelLearnedOrder)
    }

    private fun readLegacyLongImageMigration(): Boolean {
        val key = "restore_legacy_vertical_positions"
        if (disk.contains(key)) return disk.getBoolean(key, true)
        val storedMode = runCatching {
            JSONObject(disk.getString("reader_preferences", "{}") ?: "{}").optString("mode")
        }.getOrDefault("")
        // Webtoon already saved an outer offset; older inner-pan values must not be added to it.
        val restore = storedMode != "WEBTOON"
        disk.edit().putBoolean(key, restore).apply()
        return restore
    }

    private fun <T> readArray(key: String, read: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(disk.getString(key, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            runCatching { read(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun chapterProgressKey(mangaId: String, chapterId: String) =
        "$CHAPTER_PROGRESS_PREFIX${mangaId.length}:$mangaId$chapterId"

    private fun isProgressKeyFor(key: String, mangaId: String): Boolean =
        key.startsWith("$CHAPTER_PROGRESS_PREFIX${mangaId.length}:$mangaId") ||
            key.startsWith("$LONG_IMAGE_POSITION_PREFIX${mangaId.length}:$mangaId")

    private fun longImagePositionKey(mangaId: String, chapterId: String, pageIndex: Int) =
        "$LONG_IMAGE_POSITION_PREFIX${mangaId.length}:$mangaId${chapterId.length}:$chapterId:$pageIndex"

    private fun spreadKey(mangaId: String, chapterId: String) =
        "${SPREAD_PREFIX}${mangaId.length}:$mangaId${chapterId.length}:$chapterId"

    private fun bookmarkKey(mangaId: String, chapterId: String) =
        "${BOOKMARK_PREFIX}${mangaId.length}:$mangaId${chapterId.length}:$chapterId"

    private companion object {
        const val BACKUP_FORMAT = "MangaFlowLibraryBackup"
        const val BACKUP_VERSION = 1
        const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
        const val MAX_BACKUP_SOURCE_PACKAGES = 64
        const val SHELF_STATES_KEY = "shelf_states"
        const val PAGE_COLLECTIONS_KEY = "page_collections"
        const val READING_ACTIVITY_KEY = "reading_activity"
        const val MANGA_READER_PREFERENCES_KEY = "reader_preferences_by_manga"
        const val BOOKMARK_PREFIX = "reader_bookmarks:"
        const val SPREAD_PREFIX = "spread_singles:"
        const val CHAPTER_PROGRESS_PREFIX = "chapter_progress:"
        const val LONG_IMAGE_POSITION_PREFIX = "long_image_position:"
    }
}

private fun ReadingProgress.chapterProgress() = ChapterProgress(pageIndex, pageCount, scrollOffset,
    chapterTitle.take(160), updatedAt, panelIndex, panelLayout)

private fun ChapterProgress.toJson(): JSONObject = JSONObject().apply {
    put("pageIndex", pageIndex)
    put("pageCount", pageCount)
    put("scrollOffset", scrollOffset)
    if (chapterTitle.isNotBlank()) put("chapterTitle", chapterTitle.take(160))
    if (updatedAt > 0L) put("updatedAt", updatedAt)
    if (panelIndex >= 0) { put("panelIndex", panelIndex); put("panelLayout", panelLayout) }
}

internal fun Manga.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("coverUrl", coverUrl)
    put("authors", JSONArray(authors))
    put("authorPaths", JSONObject(authorPaths))
    put("tagPaths", JSONObject(tagPaths))
    put("description", description)
    put("status", status)
    put("tags", JSONArray(tags))
    put("latestChapter", latestChapter)
    put("sourceUrl", sourceUrl)
    put("sourceUuid", sourceUuid)
    put("sourceId", sourceId)
    put("sourceMangaId", sourceMangaId)
    put("sourceName", sourceName)
    if (alternativeTitles.isNotEmpty()) put("alternativeTitles", JSONArray(alternativeTitles))
    if (authorAliases.isNotEmpty()) put("authorAliases", JSONArray(authorAliases))
    val identities = externalIds.filter { (key, value) ->
        key.isNotBlank() && key.length <= 80 && value.isNotBlank() && value.length <= 2048 &&
            value.none { it < ' ' || it == '\u007f' }
    }
    if (identities.isNotEmpty()) put("externalIds", JSONObject(identities))
}

internal fun readManga(json: JSONObject): Manga = Manga(
    id = json.getString("id"),
    title = json.getString("title"),
    coverUrl = json.optString("coverUrl"),
    authors = json.optJSONArray("authors").strings(),
    authorPaths = json.optJSONObject("authorPaths")?.let { paths ->
        paths.keys().asSequence().mapNotNull { key -> (paths.opt(key) as? String)?.let { key to it } }.toMap()
    }.orEmpty(),
    tagPaths = json.optJSONObject("tagPaths")?.let { paths ->
        paths.keys().asSequence().mapNotNull { key -> (paths.opt(key) as? String)?.let { key to it } }.toMap()
    }.orEmpty(),
    description = json.optString("description"),
    status = json.optString("status"),
    tags = json.optJSONArray("tags").strings(),
    latestChapter = json.optString("latestChapter"),
    sourceUrl = json.optString("sourceUrl"),
    sourceUuid = json.optString("sourceUuid"),
    sourceId = json.optString("sourceId").ifBlank {
        if (json.optString("sourceUrl") == DownloadStore.LOCAL_SOURCE_URL) "local"
        else if (SourceKeys.isEncoded(json.getString("id"))) SourceKeys.decodeManga(json.getString("id")).sourceId
        else "copy"
    },
    sourceMangaId = json.optString("sourceMangaId"),
    sourceName = json.optString("sourceName"),
    alternativeTitles = json.optJSONArray("alternativeTitles").strings().distinct(),
    authorAliases = json.optJSONArray("authorAliases").strings().distinct(),
    externalIds = json.optJSONObject("externalIds")?.let { identities ->
        identities.keys().asSequence().mapNotNull { key ->
            (identities.opt(key) as? String)?.takeIf { value ->
                key.isNotBlank() && key.length <= 80 && value.isNotBlank() && value.length <= 2048 &&
                    value.none { it < ' ' || it == '\u007f' }
            }?.let { key to it }
        }.toMap()
    }.orEmpty(),
)

internal fun Chapter.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("index", index)
    put("group", group)
    put("logicalId", logicalId)
    put("sourceId", sourceId)
    put("sourceMangaId", sourceMangaId)
    put("sourceChapterId", sourceChapterId)
    put("variants", JSONArray(variants.map { variant -> JSONObject().apply {
        put("sourceId", variant.sourceId)
        put("sourceName", variant.sourceName)
        put("mangaId", variant.mangaId)
        put("chapterId", variant.chapterId)
        put("title", variant.title)
        put("language", variant.language)
        put("edition", variant.edition)
        put("scanlator", variant.scanlator)
        put("url", variant.url)
        variant.pageCount?.let { put("pageCount", it) }
    } }))
}

internal fun readChapter(json: JSONObject): Chapter = Chapter(
    id = json.getString("id"),
    title = json.getString("title"),
    index = json.optInt("index"),
    group = json.optString("group", "默认"),
    logicalId = json.optString("logicalId"),
    sourceId = json.optString("sourceId", "copy"),
    sourceMangaId = json.optString("sourceMangaId"),
    sourceChapterId = json.optString("sourceChapterId"),
    variants = json.optJSONArray("variants")?.let { array ->
        (0 until array.length()).map { i ->
            val variant = array.getJSONObject(i)
            ChapterVariant(variant.getString("sourceId"), variant.getString("sourceName"),
                variant.getString("mangaId"), variant.getString("chapterId"), variant.getString("title"),
                variant.optString("language", "简中"), variant.optString("edition", "连载版"),
                variant.optString("scanlator"), variant.optString("url"),
                variant.optInt("pageCount", -1).takeIf { it >= 0 })
        }
    }.orEmpty(),
)

internal fun Page.toJson(): JSONObject = JSONObject().apply {
    put("index", index)
    put("url", url)
    put("width", width)
    put("height", height)
}

internal fun readPage(json: JSONObject): Page = Page(
    index = json.getInt("index"),
    url = json.getString("url"),
    width = json.optInt("width"),
    height = json.optInt("height"),
)

private fun ReadingProgress.toJson(): JSONObject = JSONObject().apply {
    put("manga", manga.toJson())
    put("chapterId", chapterId)
    put("chapterTitle", chapterTitle)
    put("pageIndex", pageIndex)
    put("pageCount", pageCount)
    put("scrollOffset", scrollOffset)
    put("updatedAt", updatedAt)
    if (panelIndex >= 0) { put("panelIndex", panelIndex); put("panelLayout", panelLayout) }
}

private fun readProgress(json: JSONObject): ReadingProgress = ReadingProgress(
    manga = readManga(json.getJSONObject("manga")),
    chapterId = json.getString("chapterId"),
    chapterTitle = json.optString("chapterTitle"),
    pageIndex = json.optInt("pageIndex").coerceAtLeast(0),
    pageCount = json.optInt("pageCount").coerceAtLeast(0),
    scrollOffset = json.optInt("scrollOffset").coerceAtLeast(0),
    updatedAt = json.optLong("updatedAt"),
    panelIndex = json.optInt("panelIndex", -1).coerceIn(-1, 127),
    panelLayout = json.optString("panelLayout").take(160),
)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList()
else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
