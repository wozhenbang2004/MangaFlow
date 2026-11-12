package dev.mangaflow.data

import java.io.IOException

data class Manga(
    val id: String,
    val title: String,
    val coverUrl: String,
    val authors: List<String> = emptyList(),
    val description: String = "",
    val status: String = "",
    val tags: List<String> = emptyList(),
    val latestChapter: String = "",
    val sourceUrl: String = "",
)

data class Chapter(
    val id: String,
    val title: String,
    val index: Int,
    val group: String = "默认",
)

data class MangaPage(val items: List<Manga>, val hasMore: Boolean)
data class MangaDetail(val manga: Manga, val chapters: List<Chapter>)
data class Page(val index: Int, val url: String, val width: Int = 0, val height: Int = 0)

interface MangaRepository {
    /** Zero-based pages. Source adapters own their upstream page size. */
    suspend fun discover(page: Int = 0): MangaPage
    suspend fun search(query: String, page: Int = 0): MangaPage
    suspend fun detail(mangaId: String): MangaDetail
    suspend fun pages(mangaId: String, chapterId: String): List<Page>
    fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> = emptyMap()
}

class SourceException(
    message: String,
    val sourceUrl: String = "",
    val retryAfterSeconds: Long = 0,
    cause: Throwable? = null,
) : IOException(message, cause)

enum class ReviewStatus { READY, NO_MATCH, UNAVAILABLE }

data class MangaReview(
    val id: String,
    val author: String,
    val summary: String,
    val url: String,
    val score: Double? = null,
    val scoreScale: Int = 100,
)

data class ReviewSource(
    val id: String,
    val name: String,
    val status: ReviewStatus,
    val url: String,
    val matchedTitle: String = "",
    val score: Double? = null,
    val scoreScale: Int = 10,
    val ratingCount: Int? = null,
    val reviews: List<MangaReview> = emptyList(),
    val message: String = "",
)
