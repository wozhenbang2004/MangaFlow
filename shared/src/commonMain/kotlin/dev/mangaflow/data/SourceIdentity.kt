@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.data

import kotlin.io.encoding.Base64

data class SourceMangaRef(val sourceId: String, val mangaId: String)
data class SourceChapterRef(val sourceId: String, val mangaId: String, val chapterId: String)

/** Encoded identities are durable routing information, never an in-memory lookup token. */
object SourceKeys {
    private const val PREFIX = "@source/"
    private val sourcePattern = Regex("[a-z][a-z0-9_-]{0,39}")
    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
    private fun encode(value: String): String = codec.encode(value.encodeToByteArray())
    private fun decode(value: String): String = codec.decode(value).decodeToString()
        .also { require(it.isNotBlank() && it.length <= 4096) { "来源编号不完整" } }
    private fun checkedSource(sourceId: String): String = sourceId.also {
        require(sourcePattern.matches(it)) { "来源编号不受支持" }
    }

    fun manga(sourceId: String, mangaId: String): String {
        checkedSource(sourceId)
        require(mangaId.isNotBlank() && mangaId.length <= 4096)
        return if (sourceId == "copy" || sourceId == "local") mangaId
        else "$PREFIX$sourceId/${encode(mangaId)}"
    }

    fun chapter(sourceId: String, mangaId: String, chapterId: String): String {
        checkedSource(sourceId)
        require(mangaId.isNotBlank() && mangaId.length <= 4096 && chapterId.isNotBlank() && chapterId.length <= 4096)
        return if (sourceId == "copy" || sourceId == "local") chapterId
        else "$PREFIX$sourceId/${encode(mangaId)}/${encode(chapterId)}"
    }

    fun isEncoded(id: String): Boolean = id.startsWith(PREFIX)

    fun decodeManga(id: String): SourceMangaRef {
        if (!isEncoded(id)) return SourceMangaRef("copy", id)
        val parts = id.removePrefix(PREFIX).split('/')
        require(parts.size == 2) { "作品来源编号损坏" }
        return SourceMangaRef(checkedSource(parts[0]), decode(parts[1]))
    }

    fun decodeChapter(workId: String, chapterId: String): SourceChapterRef {
        if (!isEncoded(chapterId)) {
            val manga = decodeManga(workId)
            return SourceChapterRef(manga.sourceId, manga.mangaId, chapterId)
        }
        val parts = chapterId.removePrefix(PREFIX).split('/')
        require(parts.size == 3) { "章节来源编号损坏" }
        return SourceChapterRef(checkedSource(parts[0]), decode(parts[1]), decode(parts[2]))
    }
}

data class ChapterVariant(
    val sourceId: String,
    val sourceName: String,
    val mangaId: String,
    val chapterId: String,
    val title: String,
    val language: String = "简中",
    val edition: String = "连载版",
    val scanlator: String = "",
    val url: String = "",
    val pageCount: Int? = null,
) {
    val storageId: String get() = SourceKeys.chapter(sourceId, mangaId, chapterId)
}

/** Network ownership can differ from the canonical work; storage/history keys remain unchanged. */
fun chapterSourceMangaId(workId: String, chapter: Chapter): String =
    if (chapter.sourceMangaId.isBlank()) workId else SourceKeys.manga(chapter.sourceId, chapter.sourceMangaId)

fun resolveChapter(chapter: Chapter, variant: ChapterVariant): Chapter = chapter.copy(
    id = variant.storageId,
    logicalId = chapter.logicalId.ifBlank { chapter.id },
    sourceId = variant.sourceId,
    sourceMangaId = variant.mangaId,
    sourceChapterId = variant.chapterId,
)

fun sourceDisplayName(sourceId: String): String = when (sourceId) {
    "copy" -> "拷贝"
    "baozi" -> "包子"
    "komiic" -> "Komiic"
    "mangadex" -> "MangaDex"
    "manhuagui" -> "漫画柜"
    "mangabz" -> "MangaBZ"
    "local" -> "本地"
    else -> sourceId
}
