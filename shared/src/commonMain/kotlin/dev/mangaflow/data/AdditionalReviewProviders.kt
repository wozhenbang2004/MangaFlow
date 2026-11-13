package dev.mangaflow.data

import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject

/**
 * Public manga topics from Shikimori's GraphQL and comments REST APIs.
 * Contract: shikimori/shikimori, app/graphql/{queries/mangas_query,types/manga_type}.rb,
 * app/controllers/api/v1/comments_controller.rb and app/query_objects/comments/api_query.rb.
 * The API's score is imported from MAL; local scores_stats must not be paired with it.
 */
internal class ShikimoriReviewProvider(private val http: ReviewProviderHttp) : ExternalReviewProvider {
    override val id = "shikimori"
    override val name = "Shikimori"
    override val homeUrl = "https://shikimori.io"

    override suspend fun search(query: String): List<ProviderCandidate> {
        if (query.isBlank()) return emptyList()
        val rows = mangas(SEARCH_QUERY, JSONObject().put("search", query.trim()), homeUrl)
        return rows.reviewObjects().mapNotNull { item ->
            if (item.reviewText("kind") !in MANGA_KINDS) return@mapNotNull null
            val subjectId = positiveId(item.reviewId("id")) ?: return@mapNotNull null
            val names = titles(item)
            val title = names.firstOrNull() ?: return@mapNotNull null
            ProviderCandidate(
                match = ReviewMatch(subjectId, title,
                    kindLabel(item.reviewText("kind")),
                    sourceUrl(item.reviewText("url")).ifBlank { mangaUrl(subjectId) }),
                titles = names,
            )
        }.distinctBy { it.match.id }
    }

    override suspend fun subject(id: String, includeComments: Boolean): ReviewSource {
        val item = profile(id)
        val subjectId = requireId(item.reviewId("id"))
        val publicUrl = sourceUrl(item.reviewText("url")).ifBlank { mangaUrl(subjectId) }
        val names = titles(item)
        val malId = positiveId(item.reviewId("malId"))
        val hasNoTopic = item.has("topic") && item.isNull("topic")
        val total = if (hasNoTopic) 0 else item.optJSONObject("topic")?.reviewCount("commentsCount")
        val source = ReviewSource(
            id = this.id,
            name = name,
            status = ReviewStatus.READY,
            url = publicUrl,
            matchedTitle = names.first(),
            subjectId = subjectId,
            reviewCount = total,
            aliases = names,
            externalIds = if (malId != null) mapOf("myanimelist" to malId) else emptyMap(),
            message = "作品讨论 · 以俄语为主",
        )
        return if (includeComments && !hasNoTopic) providerFirstPage(source) {
            commentPage(topicId(item), 0, publicUrl, total)
        } else source
    }

    override suspend fun page(id: String, page: Int): ReviewPage {
        requirePage(page)
        val item = profile(id)
        if (item.has("topic") && item.isNull("topic")) return ReviewPage(emptyList(), total = 0)
        val publicUrl = sourceUrl(item.reviewText("url")).ifBlank { mangaUrl(requireId(id)) }
        return commentPage(topicId(item), page, publicUrl, item.optJSONObject("topic")?.reviewCount("commentsCount"))
    }

    private suspend fun profile(id: String): JSONObject {
        val subjectId = requireId(id)
        val publicUrl = mangaUrl(subjectId)
        val item = mangas(SUBJECT_QUERY, JSONObject().put("ids", subjectId), publicUrl).optJSONObject(0)
            ?: throw SourceException("Shikimori 未返回所选漫画。", publicUrl)
        if (positiveId(item.reviewId("id")) != subjectId || item.reviewText("kind") !in MANGA_KINDS) {
            throw SourceException("Shikimori 返回的条目不是所选漫画。", publicUrl)
        }
        if (titles(item).isEmpty()) throw SourceException("Shikimori 未返回漫画名称。", publicUrl)
        return item
    }

    private suspend fun commentPage(topicId: String, page: Int, publicUrl: String, total: Int?): ReviewPage {
        requirePage(page)
        val url = "$homeUrl/api/comments".toHttpUrl().newBuilder()
            .addQueryParameter("commentable_type", "Topic")
            .addQueryParameter("commentable_id", topicId)
            .addQueryParameter("page", (page + 1).toString())
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("desc", "1")
            .build().toString()
        val rows = JSONArray(request(url, publicUrl))
        // The server offsets by the requested limit and returns limit + 1 as a next-page sentinel.
        val reviews = rows.reviewObjects().take(PAGE_SIZE).mapNotNull { row ->
            if (row.reviewText("commentable_type") != "Topic" ||
                positiveId(row.reviewId("commentable_id")) != topicId) return@mapNotNull null
            val reviewId = positiveId(row.reviewId("id")) ?: return@mapNotNull null
            val user = row.optJSONObject("user") ?: return@mapNotNull null
            val author = reviewPlainText(user.reviewText("nickname"))
            val raw = row.reviewText("body")
            val html = row.reviewText("html_body")
            val content = reviewPlainText(html.ifBlank { raw })
            if (author.isBlank() || content.isBlank()) return@mapNotNull null
            val avatar = user.optJSONObject("image")?.reviewText("x80").orEmpty()
                .ifBlank { user.reviewText("avatar") }
            MangaReview(
                id = reviewId,
                author = author,
                summary = content.take(360),
                body = content,
                url = "$homeUrl/comments/$reviewId",
                date = reviewDate(row.reviewText("created_at").ifBlank { row.reviewText("updated_at") }),
                spoiler = SPOILER_BBCODE.containsMatchIn(raw) || SPOILER_HTML.containsMatchIn(html),
                kind = ReviewKind.COMMENT,
                authorUrl = sourceUrl(user.reviewText("url")),
                avatarUrl = reviewAvatar(sourceUrl(avatar), SOURCE_DOMAINS),
            )
        }
        return ReviewPage(reviews, total = total, nextPage = if (rows.length() > PAGE_SIZE) page + 1 else null)
    }

    private suspend fun mangas(query: String, variables: JSONObject, publicUrl: String): JSONArray {
        val result = http.json("$homeUrl/api/graphql", name, publicUrl,
            body = JSONObject().put("query", query).put("variables", variables), minIntervalMillis = 750L)
        if (result.optJSONArray("errors")?.length()?.let { it > 0 } == true) {
            throw SourceException("Shikimori 暂时无法完成漫画查询。", publicUrl)
        }
        return result.optJSONObject("data")?.optJSONArray("mangas")
            ?: throw SourceException("Shikimori 未返回有效的漫画资料。", publicUrl)
    }

    private suspend fun request(url: String, publicUrl: String): String = http.text(
        url, "Shikimori", publicUrl, minIntervalMillis = 750L,
    )

    private fun titles(item: JSONObject): List<String> = buildList {
        listOf("name", "russian", "licenseNameRu", "english", "japanese").forEach { add(item.reviewText(it)) }
        val values = item.optJSONArray("synonyms")
        if (values != null) {
            for (index in 0 until values.length()) add((values.opt(index) as? String).orEmpty().trim())
        }
    }.filter(String::isNotBlank).distinct()

    private fun topicId(item: JSONObject): String = positiveId(item.optJSONObject("topic")?.reviewId("id").orEmpty())
        ?: throw SourceException("Shikimori 未提供这部漫画的公开讨论编号。", mangaUrl(item.reviewId("id")))

    private fun sourceUrl(raw: String): String {
        if (raw.isBlank()) return ""
        val url = homeUrl.toHttpUrl().resolve(raw) ?: return ""
        if (url.username.isNotEmpty() || url.password.isNotEmpty() ||
            SOURCE_DOMAINS.none { url.host == it || url.host.endsWith(".$it") } ||
            url.port != (if (url.isHttps) 443 else 80)) return ""
        return url.newBuilder().scheme("https").port(443).build().toString()
    }

    private fun positiveId(raw: String): String? = raw.toLongOrNull()?.takeIf { it > 0 }?.toString()
    private fun requireId(raw: String): String = positiveId(raw)
        ?: throw SourceException("Shikimori 漫画编号无效。", homeUrl)
    private fun requirePage(page: Int) {
        if (page < 0 || page == Int.MAX_VALUE) throw SourceException("Shikimori 评论页码无效。", homeUrl)
    }
    private fun mangaUrl(subjectId: String) = "$homeUrl/mangas/$subjectId"

    private fun kindLabel(kind: String): String = when (kind) {
        "manhwa" -> "韩漫"
        "manhua" -> "国漫"
        "one_shot" -> "短篇漫画"
        "doujin" -> "同人漫画"
        else -> "漫画"
    }

    private companion object {
        const val PAGE_SIZE = 20
        val MANGA_KINDS = setOf("manga", "manhwa", "manhua", "one_shot", "doujin")
        val SOURCE_DOMAINS = setOf("shikimori.io", "shikimori.one", "shikimori.org")
        val SPOILER_BBCODE = Regex("\\[spoiler(?:_block|_v1)?(?:=|\\s|])", RegexOption.IGNORE_CASE)
        val SPOILER_HTML = Regex("\\bb-spoiler(?:[_\\s\"']|$)", RegexOption.IGNORE_CASE)
        val SEARCH_QUERY = """
            query MangaFlowFindManga(${'$'}search: String!) {
              mangas(search: ${'$'}search, page: 1, limit: 20, kind: "manga,manhwa,manhua,one_shot,doujin") {
                id name russian english japanese synonyms licenseNameRu kind url
              }
            }
        """.trimIndent()
        val SUBJECT_QUERY = """
            query MangaFlowManga(${'$'}ids: String!) {
              mangas(ids: ${'$'}ids, limit: 1) {
                id malId name russian english japanese synonyms licenseNameRu kind url
                topic { id commentsCount }
              }
            }
        """.trimIndent()
    }
}
