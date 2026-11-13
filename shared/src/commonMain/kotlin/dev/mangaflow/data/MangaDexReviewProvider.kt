package dev.mangaflow.data

import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.json.JSONObject

/**
 * MangaDex's public manga/statistics contract supplies ratings and identity links.
 * Its documented comments field is a forum pointer/count, not comment bodies:
 * https://gitlab.com/mangadex-pub/mangadex-api-docs/blob/main/01-concepts/comments.md
 */
internal class MangaDexReviewProvider(private val http: ReviewProviderHttp) : ExternalReviewProvider {
    override val id = "mangadex"
    override val name = "MangaDex"
    override val homeUrl = "https://mangadex.org"
    override val hasWorkReviews = false

    override suspend fun search(query: String): List<ProviderCandidate> {
        if (query.isBlank()) return emptyList()
        val url = "$API/manga".toHttpUrl().newBuilder()
            .addQueryParameter("title", query.trim())
            .addQueryParameter("limit", "10")
            .addQueryParameter("order[relevance]", "desc")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist")
            .build().toString()
        val result = request(url)
        val rows = result.optJSONArray("data") ?: malformed()
        return rows.reviewObjects().mapNotNull { row ->
            if (row.reviewText("type") != "manga") return@mapNotNull null
            val mangaId = mangaId(row.reviewText("id")) ?: return@mapNotNull null
            val attributes = row.optJSONObject("attributes") ?: return@mapNotNull null
            val titles = titles(attributes)
            if (titles.isEmpty()) return@mapNotNull null
            val authors = row.optJSONArray("relationships")?.reviewObjects().orEmpty()
                .filter { it.reviewText("type") in setOf("author", "artist") }
                .mapNotNull { it.optJSONObject("attributes")?.reviewText("name") }
                .filter(String::isNotBlank).map(::reviewPlainText).distinct()
            ProviderCandidate(
                match = ReviewMatch(mangaId, titles.first(), authors.joinToString(" / "), "$homeUrl/title/$mangaId"),
                titles = titles,
                authors = authors,
            )
        }.distinctBy { it.match.id }.also {
            if (rows.length() > 0 && it.isEmpty()) malformed()
        }
    }

    override suspend fun subject(id: String, includeComments: Boolean): ReviewSource {
        val normalizedId = mangaId(id) ?: throw SourceException("MangaDex 作品标识无效。", homeUrl)
        val publicUrl = "$homeUrl/title/$normalizedId"
        val item = request("$API/manga/$normalizedId").optJSONObject("data") ?: malformed()
        if (item.reviewText("type") != "manga" || mangaId(item.reviewText("id")) != normalizedId) malformed()
        val attributes = item.optJSONObject("attributes") ?: malformed()
        val titles = titles(attributes)
        if (titles.isEmpty()) malformed()
        val statistics = request("$API/statistics/manga/$normalizedId")
            .optJSONObject("statistics")?.optJSONObject(normalizedId) ?: malformed()
        val rating = statistics.optJSONObject("rating") ?: malformed()
        val average = rating.reviewScore("average", 10)
        val weighted = rating.reviewScore("bayesian", 10)
        val count = ratingCount(rating.optJSONObject("distribution"))
        // A population prior can exist without any votes for this manga.
        val score = if (average != null && count != 0) weighted ?: average else null
        val links = attributes.optJSONObject("links")
        val externalIds = buildMap {
            put("mangadex", normalizedId)
            listOf("al" to "anilist", "mal" to "myanimelist", "kt" to "kitsu").forEach { (key, provider) ->
                links?.reviewText(key)?.toLongOrNull()?.takeIf { it > 0 }?.let { put(provider, it.toString()) }
            }
        }
        return ReviewSource(
            id = this.id,
            name = name,
            status = ReviewStatus.READY,
            url = publicUrl,
            matchedTitle = titles.first(),
            subjectId = normalizedId,
            score = score,
            scoreScale = 10,
            scoreLabel = if (score == null) "" else if (weighted != null) "贝叶斯评分" else "平均评分",
            ratingCount = count,
            aliases = titles,
            externalIds = externalIds,
            hasWorkReviews = false,
            message = if (score != null && weighted != null) "MangaDex 加权评分，满分 10 分。"
                else if (score != null) "MangaDex 用户平均评分，满分 10 分。"
                else "MangaDex 尚无可用评分；论坛讨论正文暂未接入。",
        )
    }

    override suspend fun page(id: String, page: Int): ReviewPage =
        throw SourceException("MangaDex 目前只接入作品评分，尚无已核实的评论正文接口。", homeUrl)

    private suspend fun request(url: String): JSONObject {
        val result = http.json(url, "MangaDex", homeUrl)
        if (result.reviewText("result") != "ok") malformed()
        return result
    }

    private fun titles(attributes: JSONObject): List<String> = buildList {
        attributes.optJSONObject("title")?.let { main ->
            listOf("zh", "zh-hk", "en", "ja", "ja-ro", "ko").forEach { key ->
                main.reviewText(key).takeIf(String::isNotBlank)?.let(::add)
            }
            main.keys().forEach { key -> main.reviewText(key).takeIf(String::isNotBlank)?.let(::add) }
        }
        attributes.optJSONArray("altTitles")?.reviewObjects().orEmpty().forEach { title ->
            title.keys().forEach { key -> title.reviewText(key).takeIf(String::isNotBlank)?.let(::add) }
        }
    }.map(::reviewPlainText).filter(String::isNotBlank).distinct()

    private fun ratingCount(distribution: JSONObject?): Int? {
        if (distribution == null) return null
        var total = 0L
        for (value in 1..10) {
            val count = distribution.opt(value.toString())?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
                ?: return null
            if (count > Int.MAX_VALUE - total) return null
            total += count
        }
        return total.toInt()
    }

    private fun mangaId(value: String): String? = value.trim().takeIf { UUID.matches(it) }?.lowercase()
    private fun malformed(): Nothing = throw SourceException("MangaDex 返回的数据结构无法识别，请稍后重试。", homeUrl)

    private companion object {
        const val API = "https://api.mangadex.org"
        val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
