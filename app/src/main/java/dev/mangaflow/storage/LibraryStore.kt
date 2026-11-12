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
    private val _favorites = MutableStateFlow(readArray("favorites") { readManga(it) }.distinctBy(Manga::id))
    private val _history = MutableStateFlow(readArray("history", ::readProgress).distinctBy { it.manga.id })
    private val _preferences = MutableStateFlow(readPreferences())
    private val progressScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingChapterPositions = mutableMapOf<String, ChapterProgress>()
    private val pendingLongImagePositions = mutableMapOf<String, Float>()
    private var progressWrite: ProgressWrite? = null
    private var progressWriterRunning = false
    private var progressEpoch = 0L
    private var ignoreStoredProgress = false

    val favorites: StateFlow<List<Manga>> = _favorites.asStateFlow()
    val history: StateFlow<List<ReadingProgress>> = _history.asStateFlow()
    val preferences: StateFlow<ReaderPreferences> = _preferences.asStateFlow()

    fun isFavorite(mangaId: String): Boolean = _favorites.value.any { it.id == mangaId }

    fun toggleFavorite(manga: Manga) = synchronized(lock) {
        val next = if (isFavorite(manga.id)) _favorites.value.filterNot { it.id == manga.id }
        else listOf(manga) + _favorites.value
        _favorites.value = next
        disk.edit().putString("favorites", JSONArray(next.map { it.toJson() }).toString()).apply()
    }

    fun progressFor(mangaId: String): ReadingProgress? = _history.value.firstOrNull { it.manga.id == mangaId }

    fun progressFor(mangaId: String, chapterId: String): ChapterProgress? = synchronized(lock) {
        val key = chapterProgressKey(mangaId, chapterId)
        pendingChapterPositions[key]?.let { return@synchronized it }
        val stored = if (ignoreStoredProgress) null else disk.getString(key, null)?.let { encoded ->
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
        val key = longImagePositionKey(mangaId, chapterId, pageIndex)
        pendingLongImagePositions[key]?.let { return@synchronized it }
        if (ignoreStoredProgress) return@synchronized null
        if (!disk.contains(key)) return@synchronized null
        runCatching { disk.getFloat(key, 0f) }.getOrNull()?.takeIf(Float::isFinite)?.coerceIn(0f, 1f)
    }

    fun saveLongImagePosition(mangaId: String, chapterId: String, pageIndex: Int, position: Float) {
        if (pageIndex < 0 || !position.isFinite()) return
        synchronized(lock) {
            val key = longImagePositionKey(mangaId, chapterId, pageIndex)
            val next = position.coerceIn(0f, 1f)
            pendingLongImagePositions[key] = next
            pendingWrite().longImages[key] = next
            startProgressWriter()
        }
    }

    fun saveProgress(manga: Manga, chapter: Chapter, pageIndex: Int, pageCount: Int, scrollOffset: Int = 0) {
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
            _history.value = next
            write.history = next
            startProgressWriter()
        }
    }

    fun clearHistory() = synchronized(lock) {
        _history.value = emptyList()
        pendingChapterPositions.clear()
        pendingLongImagePositions.clear()
        // Queue clearing after any in-flight write. Readers must not see its old disk values
        // while that write finishes, and new reading positions can join the clearing batch.
        progressEpoch++
        ignoreStoredProgress = true
        progressWrite = ProgressWrite(progressEpoch, clear = true)
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
                write.chapters.forEach { (key, position) -> editor.putString(key, position.toJson().toString()) }
                write.longImages.forEach { (key, position) -> editor.putFloat(key, position) }
                write.history?.let { history -> editor.putString("history", JSONArray(history.map { it.toJson() }).toString()) }
                editor.commit()
            }.getOrDefault(false)
            synchronized(lock) {
                if (write.epoch == progressEpoch) {
                    if (persisted) {
                        if (write.clear) ignoreStoredProgress = false
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
    )

    fun updatePreferences(preferences: ReaderPreferences) = synchronized(lock) {
        val next = preferences.copy(dimAmount = preferences.dimAmount.coerceIn(0f, 0.65f))
        _preferences.value = next
        disk.edit().putString("reader_preferences", JSONObject().apply {
            put("mode", next.mode.name)
            put("theme", next.theme.name)
            put("keepScreenOn", next.keepScreenOn)
            put("pageGap", next.pageGap)
            put("dimAmount", next.dimAmount.toDouble())
        }.toString()).apply()
    }

    private fun readPreferences(): ReaderPreferences = runCatching {
        val json = JSONObject(disk.getString("reader_preferences", "{}") ?: "{}")
        ReaderPreferences(
            mode = ReadingMode.entries.firstOrNull { it.name == json.optString("mode") } ?: ReadingMode.VERTICAL,
            theme = ReaderTheme.entries.firstOrNull { it.name == json.optString("theme") } ?: ReaderTheme.INK,
            keepScreenOn = json.optBoolean("keepScreenOn", true),
            pageGap = json.optBoolean("pageGap", false),
            dimAmount = json.optDouble("dimAmount", 0.0).toFloat().coerceIn(0f, 0.65f),
        )
    }.getOrDefault(ReaderPreferences())

    private fun <T> readArray(key: String, read: (JSONObject) -> T): List<T> = runCatching {
        val array = JSONArray(disk.getString(key, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            runCatching { read(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun chapterProgressKey(mangaId: String, chapterId: String) =
        "$CHAPTER_PROGRESS_PREFIX${mangaId.length}:$mangaId$chapterId"

    private fun longImagePositionKey(mangaId: String, chapterId: String, pageIndex: Int) =
        "$LONG_IMAGE_POSITION_PREFIX${mangaId.length}:$mangaId${chapterId.length}:$chapterId:$pageIndex"

    private companion object {
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
