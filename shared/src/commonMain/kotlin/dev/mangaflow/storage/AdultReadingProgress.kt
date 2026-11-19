package dev.mangaflow.storage

import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject

internal data class AdultProgressWork(
    val manga: Manga,
    val chapters: Map<String, ChapterProgress>,
    val recentChapterId: String? = null,
) {
    fun sameLocations(other: AdultProgressWork?): Boolean =
        other != null && recentChapterId == other.recentChapterId && chapters.size == other.chapters.size &&
            chapters.all { (chapterId, position) -> position.sameLocation(other.chapters[chapterId]) }

    /** A reviewed side wins exact collisions. Unique chapters and variants from both sides survive. */
    fun mergePreferred(preferred: AdultProgressWork): AdultProgressWork =
        AdultProgressWork(preferred.manga, chapters + preferred.chapters, preferred.recentChapterId ?: recentChapterId)
}

internal fun ChapterProgress.sameLocation(other: ChapterProgress?): Boolean = other != null &&
    pageIndex == other.pageIndex && pageCount == other.pageCount && scrollOffset == other.scrollOffset &&
    panelIndex == other.panelIndex && panelLayout == other.panelLayout && legacyLongImageFraction == other.legacyLongImageFraction

internal data class AdultProgressSnapshot(val works: Map<String, AdultProgressWork>, val backupRevision: Long)

internal fun adultProgressChapterMatches(workId: String, chapterId: String, sourceIds: Set<String>? = null): Boolean {
    if (!SourceKeys.isEncoded(workId) || !SourceKeys.isEncoded(chapterId)) return false
    return runCatching {
        val work = SourceKeys.decodeManga(workId)
        val chapter = SourceKeys.decodeChapter(workId, chapterId)
        (chapter.sourceId != work.sourceId || chapter.mangaId == work.mangaId) &&
            (sourceIds == null || work.sourceId in sourceIds && chapter.sourceId in sourceIds)
    }.getOrDefault(false)
}

/** Only native identities and reading positions are put into this dedicated document. */
internal object AdultProgressDocument {
    private const val FORMAT = "mangaflow-adult-reading-progress-v1"

    fun encode(works: Map<String, AdultProgressWork>): String = JSONObject().put("format", FORMAT)
        .put("works", JSONArray(works.entries.sortedBy { it.key }.map { (workId, work) ->
            JSONObject().put("id", workId).put("title", work.manga.title.take(256))
                .put("recent", work.recentChapterId.orEmpty())
                .put("chapters", JSONArray(work.chapters.entries.sortedBy { it.key }.map { (chapterId, position) ->
                    JSONObject().put("id", chapterId).put("title", position.chapterTitle.take(160))
                        .put("page", position.pageIndex).put("pages", position.pageCount)
                        .put("offset", position.scrollOffset).put("time", position.updatedAt)
                        .put("panel", position.panelIndex).put("layout", position.panelLayout)
                        .apply { position.legacyLongImageFraction?.let { put("longImage", it.toDouble()) } }
                }))
        })).toString()

    fun decode(payload: String?): Map<String, AdultProgressWork> {
        if (payload == null) return emptyMap()
        val json = JSONObject(payload)
        require(json.optString("format") == FORMAT) { "远端阅读进度格式不兼容。" }
        val works = json.getJSONArray("works")
        return buildMap {
            for (index in 0 until works.length()) {
                val entry = works.getJSONObject(index)
                val workId = entry.getString("id")
                val ref = SourceKeys.decodeManga(workId)
                val chapters = entry.getJSONArray("chapters")
                require(workId !in this && chapters.length() > 0) { "远端阅读进度含重复作品或空目录。" }
                val progress = buildMap {
                    for (chapterIndex in 0 until chapters.length()) {
                        val row = chapters.getJSONObject(chapterIndex)
                        val id = row.getString("id")
                        require(id !in this && adultProgressChapterMatches(workId, id)) { "远端章节或版本编号不一致。" }
                        val fraction = if (row.has("longImage")) row.optDouble("longImage").toFloat().also {
                            require(it > 0f && it <= 1f) { "远端长图位置无效。" }
                        } else null
                        val value = ChapterProgress(row.getInt("page"), row.getInt("pages"), row.getInt("offset"),
                            row.optString("title").take(160), row.optLong("time"),
                            row.optInt("panel", -1), row.optString("layout").take(160), fraction)
                        require(value.pageIndex in 0 until value.pageCount && value.scrollOffset >= 0 &&
                            value.updatedAt >= 0L && value.panelIndex in -1..127 &&
                            (fraction == null || value.scrollOffset == 0)) { "远端阅读位置无效。" }
                        put(id, value)
                    }
                }
                val title = entry.optString("title").take(256).ifBlank { ref.mangaId }
                val recent = entry.optString("recent").takeIf(String::isNotBlank)
                require(recent == null || recent in progress) { "远端继续阅读所用版本不存在。" }
                put(workId, AdultProgressWork(Manga(workId, title, "", sourceId = ref.sourceId,
                    sourceMangaId = ref.mangaId), progress, recent))
            }
        }
    }
}
