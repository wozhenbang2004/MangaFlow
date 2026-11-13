package dev.mangaflow.data

import dev.mangaflow.platform.*

import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import com.fleeksoft.ksoup.Ksoup as Jsoup
import com.fleeksoft.ksoup.nodes.Document

/**
 * Public ACG pages, with no account credentials or private API calls.
 * Metadata selectors/labels: github.com/XuHaoJun/gamer-crawler/blob/master/index.js
 * Rating DOM: gist.github.com/david082321/b5f998e609b3b4fb72c771a71763528c
 * Search URL and numeric identity: wikidata.org/wiki/Property:P6367
 * Search links use the known detail URL format, not the old catalogue's DOM.
 */
internal class BahamutReviewProvider(private val http: ReviewProviderHttp) : ExternalReviewProvider {
    override val id = "bahamut"
    override val name = "巴哈姆特"
    override val homeUrl = "https://acg.gamer.com.tw"
    override val hasWorkReviews = false

    override suspend fun search(query: String): List<ProviderCandidate> {
        val input = query.trim()
        if (input.isEmpty()) return emptyList()
        inputId(input)?.let { subjectId ->
            val details = details(subjectId)
            requireComic(details)
            return listOf(candidate(details))
        }
        if (input.all(Char::isDigit) || input.contains("://") || input.startsWith("//")) invalidId()
        val normalizedQuery = normalizeReviewTitle(input)
        if (normalizedQuery.isBlank()) {
            throw SourceException("请输入完整作品名称、数字编号或有效作品地址。", homeUrl)
        }
        val searchUrl = "$homeUrl/search.php".toHttpUrl().newBuilder()
            .addQueryParameter("kw", input).build()
        val document = document(searchUrl.toString(), "作品检索")
        val candidates = document.select("a[href]").asSequence().mapNotNull { anchor ->
            val target = searchUrl.resolve(anchor.attr("href")) ?: return@mapNotNull null
            val subjectId = canonicalId(target) ?: return@mapNotNull null
            // Keep the complete title: prefixes, truncated snippets and nearby text are not aliases.
            val names = listOf(anchor.attr("title"), anchor.text()).map(String::trim)
                .filter(String::isNotBlank)
            subjectId.takeIf { names.any { normalizeReviewTitle(it) == normalizedQuery } }
        }.distinct().take(MAX_CANDIDATES).toList()
        if (candidates.isEmpty()) {
            throw SourceException("暂未能解析搜索结果，请尝试作品原名、数字编号或完整作品地址。", searchUrl.toString())
        }
        val matches = ArrayList<ProviderCandidate>()
        for (subjectId in candidates) {
            // Transport errors, including refusal/rate limits, stop this lookup immediately.
            val details = details(subjectId)
            if (details.platform != "漫畫") continue
            if (details.titles.none { normalizeReviewTitle(it) == normalizedQuery }) {
                throw SourceException("搜索条目的标题与作品资料不一致，请核对作品编号。", mangaUrl(subjectId))
            }
            matches += candidate(details)
        }
        return matches
    }

    override suspend fun subject(id: String, includeComments: Boolean): ReviewSource {
        val subjectId = inputId(id.trim()) ?: invalidId()
        val details = details(subjectId)
        requireComic(details)
        val publicUrl = mangaUrl(subjectId)
        val scoreText = metricText(details.document, ".score-overall-number", publicUrl)
        val countText = metricText(details.document, ".score-overall-people", publicUrl)
        val count = RATING_COUNT.matchEntire(countText)?.groupValues?.get(1)
            ?.replace(",", "")?.toIntOrNull()
        if (count == null && countText != "統計中") {
            throw SourceException("评分人数暂时无法解析，请稍后重试。", publicUrl)
        }
        val score = if (scoreText == "--") null else {
            if (!RATING_SCORE.matches(scoreText)) malformedRating(publicUrl)
            scoreText.toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..5.0 }
                ?: malformedRating(publicUrl)
        }
        if (score != null && (count == null || count == 0)) malformedRating(publicUrl)
        return ReviewSource(
            id = this.id,
            name = name,
            status = ReviewStatus.READY,
            url = publicUrl,
            matchedTitle = details.titles.first(),
            subjectId = subjectId,
            score = score,
            scoreScale = 5,
            scoreLabel = if (score == null) "" else "平均评分",
            ratingCount = count,
            aliases = details.titles,
            externalIds = mapOf(this.id to subjectId),
            hasWorkReviews = false,
            message = if (score == null) "暂未形成评分；评论暂未接入。" else "用户平均评分 · 满分 5 分",
        )
    }

    override suspend fun page(id: String, page: Int): ReviewPage =
        throw SourceException("目前只接入作品评分，未接入评论正文。", homeUrl)

    private suspend fun details(subjectId: String): SubjectDetails {
        val publicUrl = mangaUrl(subjectId)
        val document = document(publicUrl, "作品评分")
        val container = document.select(DETAIL_CONTAINER).singleOrNull()
            ?: throw SourceException("作品资料暂时无法解析，请稍后重试。", publicUrl)
        // Check standard HTML identity if present; SourceHttp also rejects redirects.
        for (canonical in document.select("link[rel=canonical]")) {
            val target = publicUrl.toHttpUrl().resolve(canonical.attr("href"))
            if (target == null || canonicalId(target) != subjectId) {
                throw SourceException("返回的作品地址与所选条目不一致，请重新选择。", publicUrl)
            }
        }
        val title = container.children().filter { it.tagName() == "h1" }.singleOrNull()?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: throw SourceException("作品资料缺少明确标题，请稍后重试。", publicUrl)
        val titles = (listOf(title) + container.children().filter { it.tagName() == "h2" }
            .map { it.text().trim() }).filter(String::isNotBlank).distinct()
        val fields = container.children().filter { it.tagName() == "ul" }
            .flatMap { it.children() }.mapNotNull { element ->
                val text = element.text().trim()
                val separator = text.indexOf('：')
                if (separator <= 0) null else text.substring(0, separator).trim() to
                    text.substring(separator + 1).trim()
            }.groupBy({ it.first }, { it.second })
        val platform = fields["作品平台"].orEmpty().distinct().singleOrNull()?.takeIf(String::isNotBlank)
            ?: throw SourceException("作品资料缺少明确媒介类型，暂时无法确认漫画身份。", publicUrl)
        val authors = listOf("漫畫作者", "原著作者").flatMap { fields[it].orEmpty() }
            .filter { it.isNotBlank() && it !in UNKNOWN_VALUES }.distinct()
        if (platform == "漫畫" && authors.isEmpty()) {
            throw SourceException("作品资料缺少作者信息，暂时无法确认漫画身份。", publicUrl)
        }
        return SubjectDetails(subjectId, platform, titles, authors, document)
    }

    private fun candidate(details: SubjectDetails) = ProviderCandidate(
        match = ReviewMatch(details.id, details.titles.first(), details.authors.joinToString(" / "), mangaUrl(details.id)),
        titles = details.titles,
        authors = details.authors,
    )

    private fun requireComic(details: SubjectDetails) {
        if (details.platform != "漫畫") {
            throw SourceException("所选条目不是漫画，请重新选择。", mangaUrl(details.id))
        }
    }

    private suspend fun document(url: String, operation: String): Document = Jsoup.parse(
        http.text(url, operation, url, headers = mapOf("Accept" to "text/html"), minIntervalMillis = 1_000L), url,
    )

    private fun metricText(document: Document, selector: String, publicUrl: String): String {
        val value = document.select(selector).singleOrNull()?.text()?.trim()?.takeIf(String::isNotBlank)
            ?: throw SourceException("评分资料缺少必要字段，请稍后重试。", publicUrl)
        return normalizeNfkc(value).trim()
    }

    private fun inputId(value: String): String? = positiveId(value)
        ?: value.toHttpUrlOrNull()?.let(::canonicalId)

    private fun canonicalId(url: HttpUrl): String? {
        if (url.host != HOST || url.port != (if (url.isHttps) 443 else 80) ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/acgDetail.php" || url.querySize != 1 ||
            url.queryParameterNames != setOf("s") || !url.fragment.isNullOrEmpty()) return null
        return positiveId(url.queryParameter("s").orEmpty())
    }

    private fun positiveId(value: String): String? = value.takeIf {
        POSITIVE_ID.matches(it) && it.toLongOrNull()?.let { number -> number > 0 } == true
    }

    private fun mangaUrl(subjectId: String) = "$homeUrl/acgDetail.php?s=$subjectId"
    private fun invalidId(): Nothing = throw SourceException("作品编号或地址无效，请重新输入。", homeUrl)
    private fun malformedRating(publicUrl: String): Nothing =
        throw SourceException("评分资料不完整或格式已变化，请稍后重试。", publicUrl)

    private data class SubjectDetails(
        val id: String,
        val platform: String,
        val titles: List<String>,
        val authors: List<String>,
        val document: Document,
    )

    private companion object {
        const val HOST = "acg.gamer.com.tw"
        const val MAX_CANDIDATES = 6
        const val DETAIL_CONTAINER = "#BH-master > div.BH-lbox.ACG-mster_box1.hreview-aggregate.hreview"
        val POSITIVE_ID = Regex("[1-9][0-9]{0,18}")
        val RATING_SCORE = Regex("[0-9]+(?:\\.[0-9]+)?")
        val RATING_COUNT = Regex("([0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)\\s*人\\s*評價")
        val UNKNOWN_VALUES = setOf("不明", "未知", "未定", "--", "-")
    }
}
