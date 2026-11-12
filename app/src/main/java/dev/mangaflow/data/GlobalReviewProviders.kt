package dev.mangaflow.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Public MangaUpdates v1 records; a series ID is a decimal int64, never an Android Int. */
internal class MangaUpdatesReviewProvider(private val http: ReviewProviderHttp) : ExternalReviewProvider {
    override val id = "mangaupdates"
    override val name = "MangaUpdates"
    override val homeUrl = "https://www.mangaupdates.com"

    // These are pagination bounds only. The repository owns content and association caches.
    private val pagination = ConcurrentHashMap<String, MuPagination>()
    private val reviewSubjects = ConcurrentHashMap<String, String>()

    override fun clearCache() {
        pagination.clear()
        reviewSubjects.clear()
    }

    override suspend fun search(query: String): List<ProviderCandidate> {
        if (query.isBlank()) return emptyList()
        val response = request("/series/search", homeUrl, JSONObject()
            .put("search", query.trim()).put("stype", "title").put("page", 1)
            .put("perpage", 20).put("type", JSONArray(COMIC_TYPES)))
        return response.requiredArray("results", name, homeUrl).reviewObjects().mapNotNull { hit ->
            val record = hit.optJSONObject("record") ?: return@mapNotNull null
            if (record.reviewText("type") !in COMIC_TYPES) return@mapNotNull null
            val seriesId = decimalIdOrNull(record.reviewId("series_id")) ?: return@mapNotNull null
            val title = reviewPlainText(record.reviewText("title"))
            if (title.isBlank()) return@mapNotNull null
            val titles = listOf(title, reviewPlainText(hit.reviewText("hit_title"))).cleanTitles()
            ProviderCandidate(
                match = ReviewMatch(seriesId, title,
                    listOf(record.reviewText("type"), record.reviewText("year")).filter(String::isNotBlank).joinToString(" · "),
                    trustedReviewUrl(record.reviewText("url"), HOSTS).ifBlank { seriesUrl(seriesId) }),
                titles = titles,
            )
        }.distinctBy { it.match.id }
    }

    override suspend fun subject(id: String, includeComments: Boolean): ReviewSource {
        val seriesId = requireDecimalId(id, name, homeUrl)
        val record = request("/series/$seriesId", seriesUrl(seriesId))
        if (record.reviewId("series_id") != seriesId || record.reviewText("type") !in COMIC_TYPES) {
            throw SourceException("MangaUpdates 返回的条目不是已确认的漫画。", seriesUrl(seriesId))
        }
        val title = reviewPlainText(record.reviewText("title"))
        if (title.isBlank()) throw SourceException("MangaUpdates 返回了缺少标题的条目。", seriesUrl(seriesId))
        val aliases = (listOf(title) + record.optJSONArray("associated")?.reviewObjects().orEmpty()
            .map { reviewPlainText(it.reviewText("title")) }).cleanTitles()
        val source = ReviewSource(
            id = this.id, name = name, status = ReviewStatus.READY,
            url = trustedReviewUrl(record.reviewText("url"), HOSTS).ifBlank { seriesUrl(seriesId) },
            matchedTitle = title, subjectId = seriesId, aliases = aliases,
            score = record.reviewScore("bayesian_rating", 10), scoreScale = 10,
            ratingCount = record.reviewCount("rating_votes"), scoreLabel = "贝叶斯评分",
            supportsReplies = true,
        )
        return if (includeComments) providerFirstPage(source) { page(seriesId, 0) } else source
    }

    override suspend fun page(id: String, page: Int): ReviewPage = coroutineScope {
        val seriesId = requireDecimalId(id, name, homeUrl)
        requireReviewPage(page, name, seriesUrl(seriesId))
        val previous = if (page == 0) MuPagination().also { pagination.remove(seriesId) }
            else pagination[seriesId] ?: MuPagination()
        val comments = async {
            if (previous.comments?.containsPage(page) == false) MuStream(bounds = previous.comments)
            else readComments(seriesId, page)
        }
        val reviews = async {
            if (previous.reviews?.containsPage(page) == false) MuStream(bounds = previous.reviews)
            else readReviews(seriesId, page)
        }
        val shortPage = comments.await()
        val longPage = reviews.await()
        val current = MuPagination(shortPage.bounds ?: previous.comments, longPage.bounds ?: previous.reviews)
        pagination.rememberBounded(seriesId, current)
        val errors = listOfNotNull(shortPage.error, longPage.error).joinToString("\n").ifBlank { null }
        val total = if (current.comments != null && current.reviews != null) {
            safeReviewTotal(current.comments.total, current.reviews.total)
        } else null
        ReviewPage(
            items = (shortPage.items + longPage.items).distinctBy(MangaReview::id).sortedByDescending(MangaReview::date),
            total = total,
            nextPage = when {
                errors != null -> page
                shortPage.hasMore || longPage.hasMore -> page + 1
                else -> null
            },
            error = errors,
        )
    }

    override suspend fun replies(subjectId: String, review: MangaReview, page: Int): ReviewPage {
        val seriesId = requireDecimalId(subjectId, name, homeUrl)
        requireReviewPage(page, name, review.url)
        if (review.kind != ReviewKind.REVIEW || !review.id.startsWith("review:")) {
            throw SourceException("MangaUpdates 的作品短评没有独立回复。", review.url)
        }
        val reviewId = requireDecimalId(review.id.removePrefix("review:"), name, review.url)
        val knownSeriesId = reviewSubjects[reviewId]
        if (knownSeriesId != null && knownSeriesId != seriesId) {
            throw SourceException("这篇 MangaUpdates 长评不属于当前漫画。", review.url)
        }
        if (knownSeriesId == null) {
            val parent = request("/reviews/$reviewId", reviewUrl(reviewId))
            val series = parent.optJSONObject("series")
            if (parent.reviewId("id") != reviewId || series?.reviewId("series_id") != seriesId || series?.reviewText("type").orEmpty() !in COMIC_TYPES) {
                throw SourceException("这篇 MangaUpdates 长评不属于当前漫画。", review.url)
            }
            reviewSubjects.rememberBounded(reviewId, seriesId)
        }
        val response = request("/reviews/$reviewId/comments/search", reviewUrl(reviewId), pageBody(page, COMMENTS_PER_PAGE))
        val rows = response.requiredArray("results", name, review.url).reviewObjects()
        val bounds = response.muBounds(COMMENTS_PER_PAGE, review.url)
        val items = rows.mapNotNull { row ->
            val record = row.optJSONObject("record") ?: throw SourceException("MangaUpdates 回复格式已变化。", review.url)
            if (record.reviewId("review_id") != reviewId) throw SourceException("MangaUpdates 返回了不属于这篇长评的回复。", review.url)
            val replyId = decimalIdOrNull(record.reviewId("id")) ?: return@mapNotNull null
            val raw = record.reviewText("content")
            val body = reviewPlainText(raw)
            val title = reviewPlainText(record.reviewText("subject"))
            if (body.isBlank() && title.isBlank()) return@mapNotNull null
            val author = record.optJSONObject("author")
            val user = author?.optJSONObject("user_info")
            MangaReview(
                id = "reply:$replyId", author = muAuthor(author, user),
                summary = title, body = body, url = reviewUrl(reviewId),
                date = muDate(record), replyTo = review.author, spoiler = hasReviewSpoiler(raw),
                authorUrl = trustedReviewUrl(user?.reviewText("url").orEmpty(), HOSTS),
                avatarUrl = muAvatar(user),
                // A review comment's `rating` rates the review, not the manga.
            )
        }
        return ReviewPage(items, bounds.total.asReviewCount(), if (bounds.hasMore(page, rows.size)) page + 1 else null)
    }

    private suspend fun readComments(seriesId: String, page: Int): MuStream = try {
        val url = seriesUrl(seriesId)
        val response = request("/series/$seriesId/comments/search", url,
            pageBody(page, COMMENTS_PER_PAGE).put("method", "time_added"))
        val rows = response.requiredArray("results", name, url).reviewObjects()
        val bounds = response.muBounds(COMMENTS_PER_PAGE, url)
        val items = rows.mapNotNull { row ->
            val record = row.optJSONObject("record") ?: throw SourceException("MangaUpdates 短评格式已变化。", url)
            if (record.reviewId("series_id") != seriesId) throw SourceException("MangaUpdates 返回了不属于这部漫画的短评。", url)
            val commentId = decimalIdOrNull(record.reviewId("id")) ?: return@mapNotNull null
            val raw = record.reviewText("content")
            val body = reviewPlainText(raw)
            val title = reviewPlainText(record.reviewText("subject"))
            if (body.isBlank() && title.isBlank()) return@mapNotNull null
            val author = record.optJSONObject("author")
            val user = author?.optJSONObject("user_info")
            MangaReview(
                id = "comment:$commentId", author = muAuthor(author, user), summary = title,
                body = body, url = "$url#comments", kind = ReviewKind.COMMENT,
                score = row.optJSONObject("metadata")?.reviewScore("author_series_rating", 10), scoreScale = 10,
                date = muDate(record), spoiler = hasReviewSpoiler(raw),
                authorUrl = trustedReviewUrl(user?.reviewText("url").orEmpty(), HOSTS),
                avatarUrl = muAvatar(user), upVotes = record.reviewCount("useful"), positiveVoteLabel = "有用",
            )
        }
        MuStream(items, bounds, bounds.hasMore(page, rows.size))
    } catch (cancel: CancellationException) { throw cancel }
      catch (failure: Exception) { MuStream(error = "短评：${failure.reviewMessage("暂时无法载入，请重试。")}") }

    private suspend fun readReviews(seriesId: String, page: Int): MuStream {
        val url = seriesUrl(seriesId)
        val response: JSONObject
        val rows: List<JSONObject>
        val bounds: MuBounds
        try {
            response = request("/reviews/search", url,
                pageBody(page, REVIEWS_PER_PAGE).put("series_id", seriesId.toLong()))
            rows = response.requiredArray("results", name, url).reviewObjects()
            bounds = response.muBounds(REVIEWS_PER_PAGE, url)
        } catch (cancel: CancellationException) { throw cancel }
          catch (failure: Exception) { return MuStream(error = "长评：${failure.reviewMessage("暂时无法载入，请重试。")}") }
        val items = mutableListOf<MangaReview>()
        val failures = mutableListOf<String>()
        for (row in rows) {
            try {
                val hit = row.optJSONObject("record") ?: throw SourceException("MangaUpdates 长评格式已变化。", url)
                if (hit.optJSONObject("series")?.reviewId("series_id") != seriesId) {
                    throw SourceException("MangaUpdates 返回了不属于这部漫画的长评。", url)
                }
                val reviewId = requireDecimalId(hit.reviewId("id"), name, url)
                // Search contains only body_excerpt; never present that excerpt as the complete review.
                val record = request("/reviews/$reviewId?unrenderedFields=true", reviewUrl(reviewId))
                if (record.reviewId("id") != reviewId || record.optJSONObject("series")?.reviewId("series_id") != seriesId) {
                    throw SourceException("MangaUpdates 长评的条目关联不一致。", url)
                }
                reviewSubjects.rememberBounded(reviewId, seriesId)
                if (record.opt("body") !is String) throw SourceException("MangaUpdates 没有返回长评正文。", reviewUrl(reviewId))
                val raw = record.reviewText("body")
                val body = reviewPlainText(raw)
                if (body.isBlank()) continue
                val author = record.optJSONObject("author")
                items += MangaReview(
                    id = "review:$reviewId", author = author?.reviewText("name").orEmpty().ifBlank { "匿名读者" },
                    summary = reviewPlainText(record.reviewText("title")), body = body, url = reviewUrl(reviewId),
                    score = record.optJSONObject("review")?.reviewScore("overall", 10), scoreScale = 10,
                    date = muDate(record), spoiler = hasReviewSpoiler(raw), kind = ReviewKind.REVIEW,
                    authorUrl = trustedReviewUrl(author?.reviewText("url").orEmpty(), HOSTS),
                    canLoadReplies = true,
                )
            } catch (cancel: CancellationException) { throw cancel }
              catch (failure: Exception) { failures += failure.reviewMessage("部分长评正文暂时无法载入，请重试。") }
        }
        return MuStream(items, bounds, bounds.hasMore(page, rows.size),
            failures.distinct().joinToString("；").takeIf(String::isNotBlank)?.let { "长评：$it" })
    }

    private suspend fun request(path: String, publicUrl: String, body: JSONObject? = null): JSONObject =
        http.json("$API$path", name, publicUrl, body, minIntervalMillis = 1_000)

    private fun seriesUrl(id: String) = "$homeUrl/series/${id.toLong().toString(36)}"
    private fun reviewUrl(id: String) = "$homeUrl/reviews.html?id=$id"
    private fun pageBody(page: Int, size: Int) = JSONObject().put("page", page + 1).put("perpage", size)
    private fun muAuthor(author: JSONObject?, user: JSONObject?): String =
        user?.reviewText("username").orEmpty().ifBlank { author?.reviewText("name").orEmpty() }.ifBlank { "匿名读者" }
    private fun muAvatar(user: JSONObject?) = reviewAvatar(user?.optJSONObject("avatar")?.reviewText("url").orEmpty(), HOSTS)
    private fun muDate(record: JSONObject): String = record.optJSONObject("time_added")?.let { time ->
        time.nonNegativeReviewLong("timestamp")?.let(::reviewDate)
            ?.ifBlank { reviewDate(time.reviewText("as_rfc3339")) }
            ?: reviewDate(time.reviewText("as_rfc3339"))
    }.orEmpty()
    private fun JSONObject.muBounds(requestedSize: Int, url: String): MuBounds {
        val total = nonNegativeReviewLong("total_hits") ?: throw SourceException("MangaUpdates 没有返回评论分页总数。", url)
        val size = reviewCount("per_page")?.takeIf { it > 0 } ?: requestedSize
        return MuBounds(total, size)
    }

    private data class MuPagination(val comments: MuBounds? = null, val reviews: MuBounds? = null)
    private data class MuBounds(val total: Long, val size: Int) {
        fun containsPage(page: Int) = page.toLong() * size < total
        fun hasMore(page: Int, received: Int) = received > 0 && (page.toLong() + 1) * size < total
    }
    private data class MuStream(
        val items: List<MangaReview> = emptyList(), val bounds: MuBounds? = null,
        val hasMore: Boolean = false, val error: String? = null,
    )

    private companion object {
        const val API = "https://api.mangaupdates.com/v1"
        const val COMMENTS_PER_PAGE = 20
        const val REVIEWS_PER_PAGE = 3
        val HOSTS = setOf("mangaupdates.com")
        val COMIC_TYPES = setOf("Doujinshi", "Filipino", "Indonesian", "Manga", "Manhwa", "Manhua", "OEL",
            "Thai", "Vietnamese", "Malaysian", "Nordic", "French", "Spanish", "German")
    }
}

/** Kitsu edge JSON:API: manga ratings and the current, 140-character media reactions. */
internal class KitsuReviewProvider(private val http: ReviewProviderHttp) : ExternalReviewProvider {
    override val id = "kitsu"
    override val name = "Kitsu"
    override val homeUrl = "https://kitsu.app"

    override suspend fun search(query: String): List<ProviderCandidate> {
        if (query.isBlank()) return emptyList()
        val url = "$API/manga".toHttpUrl().newBuilder()
            .addQueryParameter("filter[text]", query.trim())
            .addQueryParameter("include", "staff.person")
            .addQueryParameter("page[limit]", PAGE_SIZE.toString()).addQueryParameter("page[offset]", "0").build().toString()
        val response = request(url, homeUrl)
        val included = response.kitsuIncluded()
        return response.requiredArray("data", name, homeUrl).reviewObjects().mapNotNull { record ->
            val attributes = record.optJSONObject("attributes") ?: return@mapNotNull null
            if (!isKitsuComic(record, attributes)) return@mapNotNull null
            val mangaId = decimalIdOrNull(record.reviewId("id")) ?: return@mapNotNull null
            val titles = kitsuTitles(attributes)
            if (titles.isEmpty()) return@mapNotNull null
            val authors = record.relationshipRecords("staff", included).filter {
                it.optJSONObject("attributes")?.reviewText("role").orEmpty().lowercase(Locale.ROOT) in AUTHOR_ROLES
            }.mapNotNull { staff ->
                staff.relationshipRecord("person", included)?.optJSONObject("attributes")?.reviewText("name")
            }.cleanTitles()
            ProviderCandidate(ReviewMatch(mangaId, titles.first(),
                listOf(attributes.reviewText("subtype"), attributes.reviewText("startDate").take(4)).filter(String::isNotBlank).joinToString(" · "),
                mangaUrl(mangaId)), titles, authors)
        }.distinctBy { it.match.id }
    }

    override suspend fun subject(id: String, includeComments: Boolean): ReviewSource {
        val mangaId = requireDecimalId(id, name, homeUrl)
        val response = request("$API/manga/$mangaId?include=mappings", mangaUrl(mangaId))
        val record = response.optJSONObject("data") ?: throw SourceException("Kitsu 没有返回漫画条目。", mangaUrl(mangaId))
        val attributes = record.optJSONObject("attributes") ?: throw SourceException("Kitsu 漫画资料格式已变化。", mangaUrl(mangaId))
        if (record.reviewId("id") != mangaId || !isKitsuComic(record, attributes)) {
            throw SourceException("Kitsu 返回的条目不是已确认的漫画。", mangaUrl(mangaId))
        }
        val titles = kitsuTitles(attributes)
        if (titles.isEmpty()) throw SourceException("Kitsu 返回了缺少标题的条目。", mangaUrl(mangaId))
        val mappings = record.relationshipRecords("mappings", response.kitsuIncluded())
            .mapNotNull { it.optJSONObject("attributes") }
            .mapNotNull { mapping ->
                val provider = when (mapping.reviewText("externalSite")) {
                    "myanimelist/manga" -> "myanimelist"
                    "anilist/manga" -> "anilist"
                    // Kitsu's legacy MangaUpdates mappings do not establish a current v1 int64 ID.
                    else -> return@mapNotNull null
                }
                decimalIdOrNull(mapping.reviewId("externalId"))?.let { provider to it }
            }.groupBy({ it.first }, { it.second }).mapNotNull { (provider, ids) ->
                ids.distinct().singleOrNull()?.let { provider to it }
            }.toMap()
        val source = ReviewSource(
            id = this.id, name = name, status = ReviewStatus.READY, url = mangaUrl(mangaId),
            subjectId = mangaId, matchedTitle = titles.first(), aliases = titles, externalIds = mappings,
            score = attributes.reviewScore("averageRating", 100), scoreScale = 100,
            ratingCount = kitsuRatingCount(attributes.optJSONObject("ratingFrequencies")), scoreLabel = "贝叶斯评分",
            message = "短评没有剧透标记，默认折叠。",
        )
        return if (includeComments) providerFirstPage(source) { page(mangaId, 0) } else source
    }

    override suspend fun page(id: String, page: Int): ReviewPage {
        val mangaId = requireDecimalId(id, name, homeUrl)
        requireReviewPage(page, name, mangaUrl(mangaId))
        val offset = page.toLong() * PAGE_SIZE
        val url = "$API/media-reactions".toHttpUrl().newBuilder()
            .addQueryParameter("filter[mangaId]", mangaId)
            .addQueryParameter("include", "user,manga")
            .addQueryParameter("fields[users]", "name,slug,avatar")
            .addQueryParameter("fields[manga]", "canonicalTitle,subtype")
            .addQueryParameter("sort", "-createdAt")
            .addQueryParameter("page[limit]", PAGE_SIZE.toString()).addQueryParameter("page[offset]", offset.toString()).build().toString()
        val response = request(url, mangaUrl(mangaId))
        val rows = response.requiredArray("data", name, mangaUrl(mangaId)).reviewObjects()
        val included = response.kitsuIncluded()
        val items = rows.mapNotNull { record ->
            if (record.reviewText("type") != "mediaReactions") throw SourceException("Kitsu 短评类型与接口不一致。", mangaUrl(mangaId))
            val media = record.relationshipData("manga") ?: throw SourceException("Kitsu 短评缺少漫画关联。", mangaUrl(mangaId))
            if (media.reviewText("type") != "manga" || media.reviewId("id") != mangaId) {
                throw SourceException("Kitsu 返回了不属于这部漫画的短评。", mangaUrl(mangaId))
            }
            val reactionId = decimalIdOrNull(record.reviewId("id")) ?: return@mapNotNull null
            val attributes = record.optJSONObject("attributes") ?: return@mapNotNull null
            val body = reviewPlainText(attributes.reviewText("reaction"))
            if (body.isBlank()) return@mapNotNull null
            val userRecord = record.relationshipRecord("user", included)
            val user = userRecord?.optJSONObject("attributes")
            val avatar = user?.optJSONObject("avatar")
            val avatarUrl = listOf("small", "medium", "original").firstNotNullOfOrNull { size ->
                reviewAvatar(avatar?.reviewText(size).orEmpty(), HOSTS).takeIf(String::isNotBlank)
            }.orEmpty()
            val authorSlug = user?.reviewText("slug").orEmpty().ifBlank { userRecord?.reviewId("id").orEmpty() }
            MangaReview(
                id = "reaction:$reactionId", author = user?.reviewText("name").orEmpty().ifBlank { "匿名读者" },
                summary = "", body = body,
                url = trustedReviewUrl(record.optJSONObject("links")?.reviewText("self").orEmpty(), HOSTS).ifBlank { mangaUrl(mangaId) },
                date = reviewDate(attributes.reviewText("createdAt")), kind = ReviewKind.COMMENT,
                spoiler = true, upVotes = attributes.reviewCount("upVotesCount"),
                authorUrl = if (authorSlug.isNotBlank()) "$homeUrl/users".toHttpUrl().newBuilder().addPathSegment(authorSlug).build().toString() else "",
                avatarUrl = avatarUrl,
            )
        }
        val total = response.optJSONObject("meta")?.nonNegativeReviewLong("count")
        val hasMore = rows.isNotEmpty() && if (total != null) offset + rows.size < total
            else response.optJSONObject("links")?.reviewText("next")?.isNotBlank() == true
        return ReviewPage(items, total?.asReviewCount(), if (hasMore) page + 1 else null)
    }

    private suspend fun request(url: String, publicUrl: String): JSONObject = http.json(url, name, publicUrl,
        headers = mapOf("Accept" to "application/vnd.api+json"))
    private fun mangaUrl(id: String) = "$homeUrl/manga/$id"
    private fun isKitsuComic(record: JSONObject, attributes: JSONObject) =
        record.reviewText("type") == "manga" && attributes.reviewText("subtype") in COMIC_TYPES
    private fun kitsuTitles(attributes: JSONObject): List<String> {
        val titles = attributes.optJSONObject("titles")
        return (listOf(attributes.reviewText("canonicalTitle")) +
            titles?.let { values -> values.keys().asSequence().map(values::reviewText).toList() }.orEmpty() +
            attributes.optJSONArray("abbreviatedTitles").reviewStrings()).cleanTitles()
    }
    private fun kitsuRatingCount(frequencies: JSONObject?): Int? {
        if (frequencies == null || frequencies.length() == 0) return null
        var count = 0L
        for (key in frequencies.keys()) {
            val rating = key.toIntOrNull() ?: return null
            if (rating !in 2..20) return null
            val votes = frequencies.nonNegativeReviewLong(key) ?: return null
            if (votes > Int.MAX_VALUE - count) return null
            count += votes
        }
        return count.toInt()
    }

    private companion object {
        const val API = "https://kitsu.app/api/edge"
        const val PAGE_SIZE = 20
        val HOSTS = setOf("kitsu.app", "kitsu.io")
        val COMIC_TYPES = setOf("manga", "manhua", "oneshot", "doujin", "manhwa", "oel")
        val AUTHOR_ROLES = setOf("story", "art", "story & art", "story and art", "original creator", "original story", "author", "artist", "illustrator")
    }
}

/** Attribution remains MyAnimeList; Tenrai is the anonymous transport, not another rating source. */
internal class MyAnimeListReviewProvider(private val http: ReviewProviderHttp) : ExternalReviewProvider {
    override val id = "myanimelist"
    override val name = "MyAnimeList"
    override val homeUrl = "https://myanimelist.net"

    override suspend fun search(query: String): List<ProviderCandidate> {
        if (query.isBlank()) return emptyList()
        val url = "$API/manga".toHttpUrl().newBuilder().addQueryParameter("q", query.trim().take(300))
            .addQueryParameter("page", "1").addQueryParameter("limit", "20").build().toString()
        return request(url, homeUrl).requiredArray("data", name, homeUrl).reviewObjects().mapNotNull { record ->
            if (!isMalComic(record)) return@mapNotNull null
            val mangaId = decimalIdOrNull(record.reviewId("mal_id")) ?: return@mapNotNull null
            val titles = malTitles(record)
            if (titles.isEmpty()) return@mapNotNull null
            val authors = record.optJSONArray("authors")?.reviewObjects().orEmpty().map { it.reviewText("name") }.cleanTitles()
            ProviderCandidate(ReviewMatch(mangaId, titles.first(),
                listOf(record.reviewText("type"), authors.joinToString("、")).filter(String::isNotBlank).joinToString(" · "),
                trustedReviewUrl(record.reviewText("url"), HOSTS).ifBlank { mangaUrl(mangaId) }), titles, authors)
        }.distinctBy { it.match.id }
    }

    override suspend fun subject(id: String, includeComments: Boolean): ReviewSource {
        val mangaId = requireDecimalId(id, name, homeUrl)
        val record = request("$API/manga/$mangaId", mangaUrl(mangaId)).optJSONObject("data")
            ?: throw SourceException("MyAnimeList 数据通道没有返回漫画条目。", mangaUrl(mangaId))
        if (record.reviewId("mal_id") != mangaId || !isMalComic(record)) {
            throw SourceException("MyAnimeList 返回的条目不是已确认的漫画。", mangaUrl(mangaId))
        }
        val titles = malTitles(record)
        if (titles.isEmpty()) throw SourceException("MyAnimeList 返回了缺少标题的条目。", mangaUrl(mangaId))
        val source = ReviewSource(
            id = this.id, name = name, status = ReviewStatus.READY,
            url = trustedReviewUrl(record.reviewText("url"), HOSTS).ifBlank { mangaUrl(mangaId) },
            matchedTitle = titles.first(), subjectId = mangaId, aliases = titles,
            score = record.reviewScore("score", 10), scoreScale = 10, ratingCount = record.reviewCount("scored_by"),
            message = "MyAnimeList 评分与长评，由 Tenrai 提供数据。",
        )
        return if (includeComments) providerFirstPage(source) { page(mangaId, 0) } else source
    }

    override suspend fun page(id: String, page: Int): ReviewPage {
        val mangaId = requireDecimalId(id, name, homeUrl)
        if (page !in 0 until 20_000) throw SourceException("已达到 MyAnimeList 数据通道的分页上限。", mangaUrl(mangaId))
        val url = "$API/manga/$mangaId/reviews".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page + 1).toString()).addQueryParameter("sort", "newest")
            .addQueryParameter("spoilers", "true").addQueryParameter("preliminary", "true").build().toString()
        val response = request(url, mangaUrl(mangaId))
        val rows = response.requiredArray("data", name, mangaUrl(mangaId)).reviewObjects()
        val items = rows.mapNotNull { record ->
            if (!record.reviewText("type").equals("manga", ignoreCase = true)) {
                throw SourceException("MyAnimeList 返回的评论类型不是漫画。", mangaUrl(mangaId))
            }
            val reviewId = decimalIdOrNull(record.reviewId("mal_id")) ?: return@mapNotNull null
            val body = reviewPlainText(record.reviewText("review"))
            if (body.isBlank()) return@mapNotNull null
            val user = record.optJSONObject("user")
            val images = user?.optJSONObject("images")
            val avatar = listOf("webp", "jpg").firstNotNullOfOrNull { format ->
                reviewAvatar(images?.optJSONObject(format)?.reviewText("image_url").orEmpty(), IMAGE_HOSTS).takeIf(String::isNotBlank)
            }.orEmpty()
            MangaReview(
                id = reviewId, author = user?.reviewText("username").orEmpty().ifBlank { "匿名读者" },
                summary = "", body = body,
                url = trustedReviewUrl(record.reviewText("url"), HOSTS).ifBlank { mangaUrl(mangaId) },
                score = record.reviewScore("score", 10), scoreScale = 10,
                date = reviewDate(record.reviewText("date")), spoiler = record.optBoolean("is_spoiler", false),
                kind = ReviewKind.REVIEW, authorUrl = trustedReviewUrl(user?.reviewText("url").orEmpty(), HOSTS), avatarUrl = avatar,
                // chapters_read is progress, not review scope; reactions.overall includes non-positive reactions.
            )
        }
        val paging = response.optJSONObject("pagination") ?: throw SourceException("MyAnimeList 长评分页资料缺失。", mangaUrl(mangaId))
        if (paging.opt("has_next_page") !is Boolean) throw SourceException("MyAnimeList 长评分页资料格式已变化。", mangaUrl(mangaId))
        return ReviewPage(items, nextPage = if (paging.optBoolean("has_next_page") && page < 19_999 && rows.isNotEmpty()) page + 1 else null)
    }

    private suspend fun request(url: String, publicUrl: String) = http.json(url, "MyAnimeList（Tenrai）", publicUrl)
    private fun mangaUrl(id: String) = "$homeUrl/manga/$id"
    private fun isMalComic(record: JSONObject) = record.reviewText("type").lowercase(Locale.ROOT).filter(Char::isLetter) in COMIC_TYPES
    private fun malTitles(record: JSONObject): List<String> = (listOf(record.reviewText("title"), record.reviewText("title_english"), record.reviewText("title_japanese")) +
        record.optJSONArray("titles")?.reviewObjects().orEmpty().map { it.reviewText("title") } +
        record.optJSONArray("title_synonyms").reviewStrings()).cleanTitles()

    private companion object {
        const val API = "https://api.tenrai.org/v1"
        val HOSTS = setOf("myanimelist.net")
        val IMAGE_HOSTS = setOf("myanimelist.net", "myanimelist.cdn-dena.com")
        val COMIC_TYPES = setOf("manga", "oneshot", "doujinshi", "manhwa", "manhua")
    }
}

private fun decimalIdOrNull(raw: String): String? = raw.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) && it.toLongOrNull() != null }
private fun requireDecimalId(raw: String, name: String, url: String): String =
    decimalIdOrNull(raw) ?: throw SourceException("$name 的条目标识无效，请重新关联。", url)
private fun requireReviewPage(page: Int, name: String, url: String) {
    if (page < 0 || page == Int.MAX_VALUE) throw SourceException("$name 的分页位置无效，请刷新。", url)
}
private fun List<String>.cleanTitles() = map(::reviewPlainText).filter(String::isNotBlank).distinct()
private fun JSONArray?.reviewStrings(): List<String> = if (this == null) emptyList()
    else (0 until length()).mapNotNull { opt(it) as? String }
private fun JSONObject.requiredArray(key: String, name: String, url: String): JSONArray =
    optJSONArray(key) ?: throw SourceException("$name 返回的数据格式已变化，请稍后重试。", url)
private fun trustedReviewUrl(raw: String, domains: Set<String>) = reviewAvatar(raw, domains)
private fun hasReviewSpoiler(raw: String) = Regex("(?i)\\[spoiler(?:=|\\]|\\s)|class\\s*=\\s*[\"'][^\"']*spoiler").containsMatchIn(raw)
private fun JSONObject.nonNegativeReviewLong(key: String): Long? = when (val value = opt(key)) {
    is Byte, is Short, is Int, is Long -> (value as Number).toLong()
    is String -> value.toLongOrNull()
    else -> null
}?.takeIf { it >= 0 }
private fun Long.asReviewCount(): Int? = takeIf { it <= Int.MAX_VALUE }?.toInt()
private fun safeReviewTotal(left: Long, right: Long): Int? =
    if (left <= Int.MAX_VALUE && right <= Int.MAX_VALUE && left + right <= Int.MAX_VALUE) (left + right).toInt() else null
private fun JSONObject.kitsuIncluded(): Map<String, JSONObject> = optJSONArray("included")?.reviewObjects().orEmpty()
    .associateBy { "${it.reviewText("type")}:${it.reviewId("id")}" }
private fun JSONObject.relationshipData(name: String): JSONObject? = optJSONObject("relationships")?.optJSONObject(name)?.optJSONObject("data")
private fun JSONObject.relationshipRecord(name: String, included: Map<String, JSONObject>): JSONObject? =
    relationshipData(name)?.let { included["${it.reviewText("type")}:${it.reviewId("id")}"] }
private fun JSONObject.relationshipRecords(name: String, included: Map<String, JSONObject>): List<JSONObject> =
    optJSONObject("relationships")?.optJSONObject(name)?.optJSONArray("data")?.reviewObjects().orEmpty()
        .mapNotNull { included["${it.reviewText("type")}:${it.reviewId("id")}"] }
private fun <V : Any> ConcurrentHashMap<String, V>.rememberBounded(key: String, value: V) {
    synchronized(this) {
        if (!containsKey(key) && size >= 128) keys.firstOrNull()?.let { remove(it) }
        put(key, value)
    }
}
