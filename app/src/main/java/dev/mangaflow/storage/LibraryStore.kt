package dev.mangaflow.storage

import android.content.Context
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.Page
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ReadingProgress(
    val manga: Manga,
    val chapterId: String,
    val chapterTitle: String,
    val pageIndex: Int,
    val pageCount: Int,
    val scrollOffset: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class ChapterProgress(
    val pageIndex: Int,
    val pageCount: Int,
    val scrollOffset: Int = 0,
)

/** Device-local reading state. Neither favorites nor reading history require a source account. */
class LibraryStore(context: Context) {
    private val disk = context.applicationContext.getSharedPreferences("mangaflow_library", Context.MODE_PRIVATE)
    private val lock = Any()
    private val restoreLegacyLongImages = readLegacyLongImageMigration()
    private val _favorites = MutableStateFlow(readArray("favorites") { readManga(it) }.distinctBy(Manga::id))
    private val _history = MutableStateFlow(readArray("history", ::readProgress).distinctBy { it.manga.id })
    private val _preferences = MutableStateFlow(readPreferences())
    private val _mangaPreferences = MutableStateFlow(readMangaPreferences())
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingChapterPositions = mutableMapOf<String, ChapterProgress>()
    private val pendingLongImagePositions = mutableMapOf<String, Float>()
    private val pendingProgressRemovals = mutableMapOf<String, Long>()
    private var removalSequence = 0L
    private var progressWrite: ProgressWrite? = null
    private var progressWriterRunning = false
    private var progressEpoch = 0L
    private var ignoreStoredProgress = false

    val favorites: StateFlow<List<Manga>> = _favorites.asStateFlow()
    val history: StateFlow<List<ReadingProgress>> = _history.asStateFlow()
    val preferences: StateFlow<ReaderPreferences> = _preferences.asStateFlow()
    val mangaPreferences: StateFlow<Map<String, ReaderPreferences>> = _mangaPreferences.asStateFlow()

    fun isFavorite(mangaId: String): Boolean = _favorites.value.any { it.id == mangaId }

    fun toggleFavorite(manga: Manga) = synchronized(lock) {
        val next = if (isFavorite(manga.id)) _favorites.value.filterNot { it.id == manga.id }
        else listOf(manga) + _favorites.value
        _favorites.value = next
        disk.edit().putString("favorites", JSONArray(next.map { it.toJson() }).toString()).apply()
    }

    /** Idempotent removal: a stale item menu can never add the manga back. */
    fun removeFavorite(mangaId: String) = synchronized(lock) {
        val next = _favorites.value.filterNot { it.id == mangaId }
        if (next.size == _favorites.value.size) return@synchronized
        _favorites.value = next
        disk.edit().putString("favorites", JSONArray(next.map { it.toJson() }).toString()).apply()
    }

    fun progressFor(mangaId: String): ReadingProgress? = _history.value.firstOrNull { it.manga.id == mangaId }

    fun progressFor(mangaId: String, chapterId: String): ChapterProgress? = synchronized(lock) {
        val key = chapterProgressKey(mangaId, chapterId)
        pendingChapterPositions[key]?.let { return@synchronized it }
        val stored = if (ignoreStoredProgress || mangaId in pendingProgressRemovals) null else disk.getString(key, null)?.let { encoded ->
            runCatching {
                val json = JSONObject(encoded)
                ChapterProgress(
                    pageIndex = json.getInt("pageIndex").coerceAtLeast(0),
                    pageCount = json.getInt("pageCount").coerceAtLeast(0),
                    scrollOffset = json.optInt("scrollOffset").coerceAtLeast(0),
                )
            }.getOrNull()
        }
        stored ?: progressFor(mangaId)?.takeIf { it.chapterId == chapterId }?.chapterProgress()
    }

    fun longImagePositionFor(mangaId: String, chapterId: String, pageIndex: Int): Float? = synchronized(lock) {
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
    ) {
        if (pageCount <= 0) return
        synchronized(lock) {
            val progress = ReadingProgress(
                manga = manga,
                chapterId = chapter.id,
                chapterTitle = chapter.title,
                pageIndex = pageIndex.coerceIn(0, pageCount - 1),
                pageCount = pageCount,
                scrollOffset = scrollOffset.coerceAtLeast(0),
            )
            val next = (listOf(progress) + _history.value.filterNot { it.manga.id == manga.id }).take(500)
            val write = pendingWrite()
            // Retain the 0.1.2 resume point before another chapter replaces it in recent history.
            progressFor(manga.id)?.let { previous ->
                val previousKey = chapterProgressKey(manga.id, previous.chapterId)
                if (previousKey !in pendingChapterPositions && (ignoreStoredProgress || !disk.contains(previousKey))) {
                    val position = previous.chapterProgress()
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
            _history.value = next
            write.history = next
            startProgressWriter()
        }
    }

    fun clearHistory() = synchronized(lock) {
        _history.value = emptyList()
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
    fun removeHistory(mangaId: String) = synchronized(lock) {
        val next = _history.value.filterNot { it.manga.id == mangaId }
        _history.value = next
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
        progressWriterRunning = true
        progressScope.launch { drainProgressWrites() }
    }

    private fun drainProgressWrites() {
        while (true) {
            val write = synchronized(lock) {
                val next = progressWrite
                if (next == null) {
                    progressWriterRunning = false
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

    /** Explicit spread corrections live separately from reading history and source image data. */
    fun spreadSinglesFor(mangaId: String, chapterId: String): Set<Int> = synchronized(lock) {
        val value = disk.getString(spreadKey(mangaId, chapterId), "[]") ?: "[]"
        runCatching {
            val json = JSONArray(value)
            (0 until json.length()).mapNotNull { json.optInt(it, -1).takeIf { index -> index >= 0 } }.toSet()
        }.getOrDefault(emptySet())
    }

    fun saveSpreadSingles(mangaId: String, chapterId: String, pages: Set<Int>) = synchronized(lock) {
        val key = spreadKey(mangaId, chapterId)
        val cleaned = pages.filter { it >= 0 }.sorted()
        val editor = disk.edit()
        if (cleaned.isEmpty()) editor.remove(key)
        else editor.putString(key, JSONArray(cleaned).toString())
        editor.apply()
    }

    fun updatePreferences(preferences: ReaderPreferences) = synchronized(lock) {
        val next = preferences.sanitized()
        _preferences.value = next
        disk.edit().putString("reader_preferences", next.toJson().toString()).apply()
    }

    /**
     * Reading layout is remembered per work. Glass remains a single app-wide skin so opening
     * another manga never changes the navigation/search material behind the reader.
     */
    fun updateMangaPreferences(mangaId: String, preferences: ReaderPreferences) = synchronized(lock) {
        if (mangaId.isBlank()) return@synchronized
        val next = preferences.sanitized()
        if (next.glassSkin != _preferences.value.glassSkin) {
            val global = _preferences.value.copy(glassSkin = next.glassSkin)
            _preferences.value = global
            disk.edit().putString("reader_preferences", global.toJson().toString()).apply()
        }
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
            glassSkin = GlassSkin.entries.firstOrNull { it.name == optString("glassSkin") } ?: GlassSkin.LIQUID,
        )
    }

    private fun ReaderPreferences.sanitized(): ReaderPreferences =
        copy(dimAmount = dimAmount.coerceIn(0f, 0.65f))

    private fun ReaderPreferences.toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        put("theme", theme.name)
        put("keepScreenOn", keepScreenOn)
        put("pageGap", pageGap)
        put("doublePage", doublePage)
        put("coverAlone", coverAlone)
        put("dimAmount", dimAmount.toDouble())
        put("glassSkin", glassSkin.name)
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

    private companion object {
        const val MANGA_READER_PREFERENCES_KEY = "reader_preferences_by_manga"
        const val SPREAD_PREFIX = "spread_singles:"
        const val CHAPTER_PROGRESS_PREFIX = "chapter_progress:"
        const val LONG_IMAGE_POSITION_PREFIX = "long_image_position:"
    }
}

private fun ReadingProgress.chapterProgress() = ChapterProgress(pageIndex, pageCount, scrollOffset)

private fun ChapterProgress.toJson(): JSONObject = JSONObject().apply {
    put("pageIndex", pageIndex)
    put("pageCount", pageCount)
    put("scrollOffset", scrollOffset)
}

internal fun Manga.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("coverUrl", coverUrl)
    put("authors", JSONArray(authors))
    put("description", description)
    put("status", status)
    put("tags", JSONArray(tags))
    put("latestChapter", latestChapter)
    put("sourceUrl", sourceUrl)
}

internal fun readManga(json: JSONObject): Manga = Manga(
    id = json.getString("id"),
    title = json.getString("title"),
    coverUrl = json.optString("coverUrl"),
    authors = json.optJSONArray("authors").strings(),
    description = json.optString("description"),
    status = json.optString("status"),
    tags = json.optJSONArray("tags").strings(),
    latestChapter = json.optString("latestChapter"),
    sourceUrl = json.optString("sourceUrl"),
)

internal fun Chapter.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("index", index)
    put("group", group)
}

internal fun readChapter(json: JSONObject): Chapter = Chapter(
    id = json.getString("id"),
    title = json.getString("title"),
    index = json.optInt("index"),
    group = json.optString("group", "默认"),
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
}

private fun readProgress(json: JSONObject): ReadingProgress = ReadingProgress(
    manga = readManga(json.getJSONObject("manga")),
    chapterId = json.getString("chapterId"),
    chapterTitle = json.optString("chapterTitle"),
    pageIndex = json.optInt("pageIndex").coerceAtLeast(0),
    pageCount = json.optInt("pageCount").coerceAtLeast(0),
    scrollOffset = json.optInt("scrollOffset").coerceAtLeast(0),
    updatedAt = json.optLong("updatedAt"),
)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList()
else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
