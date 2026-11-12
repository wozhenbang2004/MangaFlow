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
    val sourceUuid: String = "",
    val popularity: Long? = null,
    val rank: Int? = null,
    val totalPopularity: Long? = null,
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
    suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean = false): List<Page>
    fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> = emptyMap()
}

class SourceException(
    message: String,
    val sourceUrl: String = "",
    val retryAfterSeconds: Long = 0,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
) : IOException(message, cause)

enum class ReviewStatus { READY, NO_MATCH, UNAVAILABLE }

data class MangaReview(
    val id: String,
    val author: String,
    val summary: String,
    val url: String,
    val score: Double? = null,
    val scoreScale: Int = 100,
    val body: String = "",
    val date: String = "",
    val replyCount: Int? = null,
    val replyTo: String = "",
    val spoiler: Boolean = false,
    val kind: ReviewKind = ReviewKind.COMMENT,
    val authorUrl: String = "",
    val avatarUrl: String = "",
    val upVotes: Int? = null,
    val downVotes: Int? = null,
    val positiveVoteLabel: String = "赞",
    val canLoadReplies: Boolean = false,
)

/** Site-native values. A rank period's heat never enters this overall metadata record. */
data class MangaMetric(
    val sourceId: String,
    val name: String,
    val score: Double? = null,
    /** Zero preserves a native score whose maximum has not been established. */
    val scoreScale: Int = 10,
    val ratingCount: Int? = null,
    val popularity: Long? = null,
    val scoreLabel: String = "",
)

data class ReviewSource(
    val id: String,
    val name: String,
    val status: ReviewStatus,
    val url: String,
    val matchedTitle: String = "",
    val score: Double? = null,
    /** Zero means an unknown maximum, never a normalized or zero-point scale. */
    val scoreScale: Int = 10,
    val ratingCount: Int? = null,
    val reviews: List<MangaReview> = emptyList(),
    val message: String = "",
    val subjectId: String = "",
    val popularity: Long? = null,
    val reviewCount: Int? = null,
    val nextPage: Int? = null,
    val reviewsError: String? = null,
    val loadingMore: Boolean = false,
    val candidates: List<ReviewMatch> = emptyList(),
    val aliases: List<String> = emptyList(),
    val externalIds: Map<String, String> = emptyMap(),
    val supportsReplies: Boolean = false,
    val scoreLabel: String = "",
    val hasWorkReviews: Boolean = true,
)

enum class ReviewKind { COMMENT, REVIEW, CHAPTER }

data class ReviewMatch(val id: String, val title: String, val subtitle: String, val url: String)

data class ReviewPage(
    val items: List<MangaReview>,
    val total: Int? = null,
    val nextPage: Int? = null,
    val error: String? = null,
)

enum class DiscoveryMode(val label: String) {
    RECOMMENDED("推荐"), RANKING("热榜"), LATEST("更新"),
}

enum class RankPeriod(val value: String, val label: String) {
    DAY("day", "日榜"), WEEK("week", "周榜"), MONTH("month", "月榜"), TOTAL("total", "总榜"),
}

enum class RankAudience(val value: String, val label: String) {
    MALE("male", "男频"), FEMALE("female", "女频"),
}
