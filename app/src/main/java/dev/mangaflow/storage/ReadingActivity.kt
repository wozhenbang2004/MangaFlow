package dev.mangaflow.storage

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import org.json.JSONArray
import org.json.JSONObject

/** A picture collection points to original image indexes, independently from reading history. */
data class PageCollection(
    val manga: Manga,
    val chapter: Chapter,
    val firstPage: Int,
    val lastPage: Int = firstPage,
    val previewUrls: List<String> = emptyList(),
    val mode: ReadingMode = ReadingMode.RIGHT_TO_LEFT,
    val coverAlone: Boolean = true,
    val singlePages: Set<Int> = emptySet(),
    val savedAt: Long = System.currentTimeMillis(),
) {
    val key: String get() = "${manga.id.length}:${manga.id}${chapter.id.length}:${chapter.id}:$firstPage:$lastPage"
    val spread: Boolean get() = lastPage > firstPage
}

data class ReadingActivity(
    val manga: Manga,
    val totalMillis: Long = 0L,
    val lastReadAt: Long = 0L,
    val days: Map<String, Long> = emptyMap(),
)

internal fun PageCollection.toJson(): JSONObject = JSONObject()
    .put("manga", manga.toJson()).put("chapter", chapter.toJson())
    .put("firstPage", firstPage).put("lastPage", lastPage)
    .put("previewUrls", JSONArray(previewUrls)).put("mode", mode.name)
    .put("coverAlone", coverAlone).put("singlePages", JSONArray(singlePages.sorted()))
    .put("savedAt", savedAt)

internal fun readPageCollection(json: JSONObject): PageCollection {
    val first = json.getInt("firstPage")
    val last = json.optInt("lastPage", first)
    require(first >= 0 && last in first..first + 1)
    val urls = json.optJSONArray("previewUrls") ?: JSONArray()
    val singles = json.optJSONArray("singlePages") ?: JSONArray()
    return PageCollection(
        manga = readManga(json.getJSONObject("manga")), chapter = readChapter(json.getJSONObject("chapter")),
        firstPage = first, lastPage = last,
        previewUrls = (0 until urls.length()).map { urls.optString(it) }.take(2),
        mode = runCatching { ReadingMode.valueOf(json.optString("mode")) }.getOrDefault(ReadingMode.RIGHT_TO_LEFT),
        coverAlone = json.optBoolean("coverAlone", true),
        singlePages = (0 until singles.length()).map { singles.optInt(it, -1) }.filter { it >= 0 }.toSet(),
        savedAt = json.optLong("savedAt").coerceAtLeast(0L),
    )
}

internal fun ReadingActivity.toJson(): JSONObject = JSONObject()
    .put("manga", manga.toJson()).put("totalMillis", totalMillis).put("lastReadAt", lastReadAt)
    .put("days", JSONObject(days))

internal fun readReadingActivity(json: JSONObject): ReadingActivity {
    val days = json.optJSONObject("days") ?: JSONObject()
    return ReadingActivity(readManga(json.getJSONObject("manga")),
        json.optLong("totalMillis").coerceAtLeast(0L), json.optLong("lastReadAt").coerceAtLeast(0L),
        days.keys().asSequence().associateWith { days.optLong(it).coerceAtLeast(0L) })
}
