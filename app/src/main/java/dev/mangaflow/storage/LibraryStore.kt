package dev.mangaflow.storage

import android.content.Context
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.Page
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/** Device-local reading state. Neither favorites nor reading history require a source account. */
class LibraryStore(context: Context) {
    private val disk = context.applicationContext.getSharedPreferences("mangaflow_library", Context.MODE_PRIVATE)
    private val lock = Any()
    private val _favorites = MutableStateFlow(readArray("favorites") { readManga(it) }.distinctBy(Manga::id))
    private val _history = MutableStateFlow(readArray("history", ::readProgress).distinctBy { it.manga.id })
    private val _preferences = MutableStateFlow(readPreferences())

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
            _history.value = next
            disk.edit().putString("history", JSONArray(next.map { it.toJson() }).toString()).apply()
        }
    }

    fun clearHistory() = synchronized(lock) {
        _history.value = emptyList()
        disk.edit().remove("history").apply()
    }

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
