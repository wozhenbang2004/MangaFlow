package dev.mangaflow.data

import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.json.JSONObject
import com.fleeksoft.ksoup.Ksoup as Jsoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element

/**
 * Anonymous metadata and read-only vote totals. No account or voting requests.
 * Static contracts, not an official open API or evidence of current availability:
 * https://github.com/Aoi-hosizora/manhuagui_api/tree/f6fe73d1f37d5d5b8b9881b92614a09b1319ab77
 * internal/pkg/static/url.go: search and act=get URLs;
 * internal/service/search.go and author.go: full titles, comic IDs and authors;
 * internal/service/manga.go and http.go: detail metadata, anonymous s1..s5 reads.
 * Search score/author DOM also appears in:
 * https://github.com/venera-app/venera-configs/blob/a04e05591f7832f7166d6660546613c114d6738a/manhuagui.js
 * The saved detail HTML supplies literal bInfo.id/url and the rating placeholders:
 * https://github.com/YIDA-max/node-koa-mysql/blob/cfc2dbb755ef37b4b3c1c46a49cc0a8504f88710/src/routes/comics/html/itemInfo.html
 * Current pages, responses and this adapter have not been exercised.
 */
internal class ManhuaguiReviewProvider(private val http: ReviewProviderHttp) : ExternalReviewProvider {
    override val id = "manhuagui"
    override val name = "漫画柜"
    override val homeUrl = "https://www.manhuagui.com"
    override val hasWorkReviews = false

    override suspend fun search(query: String): List<ProviderCandidate> {
        val input = query.trim()
        if (input.isEmpty()) return emptyList()
        inputId(input)?.let { return listOf(candidate(detail(it))) }
        if (input.all(Char::isDigit) || input.contains("://") || input.startsWith("//")) invalidId()
        val normalizedQuery = normalizeReviewTitle(input)
        if (input.length > MAX_TEXT_LENGTH || normalizedQuery.isBlank()) {
            throw SourceException("请输入完整作品名称、数字编号或有效作品地址。", homeUrl)
        }
        val searchUrl = homeUrl.toHttpUrl().newBuilder().addPathSegment("s")
            .addPathSegment("${input}_p1.html").build().toString()
        val document = document(searchUrl, "作品检索")
        val rows = document.select("div.book-result li.cf")
        val explicitlyEmpty = document.text().contains("很抱歉，没有查找到与")
        if (rows.isEmpty()) {
            if (explicitlyEmpty) return emptyList()
            throw SourceException("搜索页面缺少作品资料，暂时无法解析。", searchUrl)
        }
        if (explicitlyEmpty) throw SourceException("搜索页面的作品资料不一致，请稍后重试。", searchUrl)
        val hits = rows.map { row ->
            val anchor = row.select(".book-detail dl dt a").singleOrNull()
                ?: throw SourceException("搜索结果缺少完整作品标题。", searchUrl)
            val target = searchUrl.toHttpUrl().resolve(anchor.attr("href"))
            val subjectId = target?.let(::canonicalId)
                ?: throw SourceException("搜索结果包含无法确认的漫画编号。", searchUrl)
            val title = requiredText(anchor.attr("title").ifBlank { anchor.text() }, searchUrl)
            val authors = row.select(".tags a[href*='/author/']").map { authorName(it, searchUrl) }.distinct()
            SearchHit(subjectId, title, authors)
        }
        if (hits.groupBy(SearchHit::id).values.any { group ->
            group.map { normalizeReviewTitle(it.title) }.distinct().size > 1
        }) throw SourceException("搜索结果的作品编号与标题不一致。", searchUrl)
        val unique = hits.distinctBy(SearchHit::id)
        val exact = unique.filter { normalizeReviewTitle(it.title) == normalizedQuery }
        // The repository resolves matching and explicit choices; only enrich a bounded set.
        val selected = (exact.ifEmpty { unique }).take(MAX_DETAILS)
        return selected.map { hit ->
            val details = detail(hit.id)
            if (details.titles.none { normalizeReviewTitle(it) == normalizeReviewTitle(hit.title) }) {
                throw SourceException("搜索结果与作品详情的标题不一致，请重新选择。", mangaUrl(hit.id))
            }
            val knownAuthors = hit.authors.map(::normalizeReviewTitle).toSet()
            if (knownAuthors.isNotEmpty() && details.authors.none { normalizeReviewTitle(it) in knownAuthors }) {
                throw SourceException("搜索结果与作品详情的作者不一致，请重新选择。", mangaUrl(hit.id))
            }
            candidate(details)
        }
    }

    override suspend fun subject(id: String, includeComments: Boolean): ReviewSource {
        val subjectId = inputId(id.trim()) ?: invalidId()
        val details = detail(subjectId)
        val publicUrl = mangaUrl(subjectId)
        // These are the only score operation and parameters used by this provider.
        val scoreUrl = "$homeUrl/tools/vote.ashx".toHttpUrl().newBuilder()
            .addQueryParameter("act", "get").addQueryParameter("bid", subjectId).build().toString()
        val response = http.json(scoreUrl, "作品评分", publicUrl, minIntervalMillis = 1_000L)
        if (response.opt("success") != true) invalidRating(publicUrl)
        val data = response.optJSONObject("data") ?: invalidRating(publicUrl)
        val buckets = (1..5).map { bucket -> voteCount(data, "s$bucket", publicUrl) }
        val total = buckets.sum()
        if (total > Int.MAX_VALUE) {
            throw SourceException("评分人数超出可读取范围，请稍后重试。", publicUrl)
        }
        // Five native star buckets are displayed on a ten-point scale by the source.
        // This is their arithmetic vote mean, not a Bayesian or undisclosed weighted score.
        val weighted = buckets.mapIndexed { index, count -> (index + 1L) * count }.sum()
        val score = if (total == 0L) null else weighted.toDouble() * 2.0 / total
        return ReviewSource(
            id = this.id, name = name, status = ReviewStatus.READY,
            url = publicUrl, matchedTitle = details.titles.first(), subjectId = subjectId,
            aliases = details.titles, externalIds = mapOf(this.id to subjectId),
            score = score, scoreScale = 10, scoreLabel = "投票均分", ratingCount = total.toInt(),
            hasWorkReviews = false,
            message = if (score == null) "暂无投票评分。" else "",
        )
    }

    override suspend fun page(id: String, page: Int): ReviewPage =
        throw SourceException("目前只接入作品评分，未接入评论正文。", homeUrl)

    private suspend fun detail(subjectId: String): SubjectDetails {
        val publicUrl = mangaUrl(subjectId)
        val document = document(publicUrl, "作品资料")
        verifyIdentity(document, subjectId, publicUrl)
        val title = document.select("div.book-title h1").singleOrNull()?.text()
            ?: throw SourceException("作品资料缺少完整标题，请稍后重试。", publicUrl)
        val titles = (listOf(requiredText(title, publicUrl)) +
            document.select("div.book-title h2").map { it.text().trim() } +
            document.select("ul.detail-list li:nth-child(3) span:nth-child(1) a").map { it.text().trim() })
            .filter { it.isNotBlank() && it !in UNKNOWN_VALUES }.distinct()
        val authorField = document.select("ul.detail-list li:nth-child(2) span:nth-child(2)").singleOrNull()
            ?: throw SourceException("作品资料缺少漫画作者，暂时无法确认身份。", publicUrl)
        val authorLabel = authorField.select("strong").singleOrNull()?.text().orEmpty()
        if (normalizeReviewTitle(authorLabel) !in AUTHOR_LABELS) {
            throw SourceException("作品资料未能确认漫画身份，请稍后重试。", publicUrl)
        }
        val authors = authorField.select("a").map { authorName(it, publicUrl) }.distinct()
        if (authors.isEmpty()) throw SourceException("作品资料缺少作者，暂时无法确认对应关系。", publicUrl)
        return SubjectDetails(subjectId, titles, authors)
    }

    private fun verifyIdentity(document: Document, expected: String, publicUrl: String) {
        // Read only literal identity fields from the observed inline object; do not execute JS.
        val info = document.select("script:not([src])").flatMap { script ->
            BOOK_INFO.findAll(script.data()).map { it.groupValues[1] }.toList()
        }.singleOrNull() ?: throw SourceException("作品页面缺少可确认的漫画编号。", publicUrl)
        val inlineId = BOOK_ID.findAll(info).toList().singleOrNull()?.groupValues?.get(1)
        val inlineUrl = BOOK_URL.findAll(info).toList().singleOrNull()?.groupValues?.get(2)
        val linkedId = inlineUrl?.let { publicUrl.toHttpUrl().resolve(it) }?.let(::canonicalId)
        if (inlineId != expected || linkedId != expected) {
            throw SourceException("返回的作品详情不属于当前漫画，请重新选择。", publicUrl)
        }
        for (canonical in document.select("link[rel=canonical]")) {
            val target = publicUrl.toHttpUrl().resolve(canonical.attr("href"))
            if (target?.let(::canonicalId) != expected) {
                throw SourceException("作品页面地址与漫画编号不一致，请重新选择。", publicUrl)
            }
        }
    }

    private fun candidate(details: SubjectDetails) = ProviderCandidate(
        match = ReviewMatch(details.id, details.titles.first(), details.authors.joinToString(" · "), mangaUrl(details.id)),
        titles = details.titles, authors = details.authors,
    )

    private suspend fun document(url: String, operation: String): Document {
        val html = http.text(url, operation, url, headers = mapOf("Accept" to "text/html"),
            minIntervalMillis = 1_000L)
        val document = Jsoup.parse(html, url)
        if (document.select("a#checkAdult").isNotEmpty() ||
            document.text().contains("版权方的要求") || document.text().contains("根据中国法律法规")) {
            throw SourceException("来源限制了该作品的访问，暂时无法读取评分。", url)
        }
        return document
    }

    private fun authorName(anchor: Element, publicUrl: String): String {
        val target = publicUrl.toHttpUrl().resolve(anchor.attr("href"))
        if (target == null || !isPublicOrigin(target) || !AUTHOR_PATH.matches(target.encodedPath) ||
            target.query != null || !target.fragment.isNullOrEmpty()) {
            throw SourceException("作品资料的作者身份暂时无法确认。", publicUrl)
        }
        return requiredText(anchor.attr("title").ifBlank { anchor.text() }, publicUrl)
    }

    private fun voteCount(data: JSONObject, key: String, publicUrl: String): Long {
        val value = (data.opt(key) as? Number)?.toDouble() ?: invalidRating(publicUrl)
        if (!value.isFinite() || value < 0 || value > Int.MAX_VALUE || value % 1.0 != 0.0) {
            invalidRating(publicUrl)
        }
        return value.toLong()
    }

    private fun requiredText(value: String, publicUrl: String): String = value.trim()
        .takeIf { it.isNotBlank() && it.length <= MAX_TEXT_LENGTH && it !in UNKNOWN_VALUES }
        ?: throw SourceException("作品资料缺少完整标题或作者，请稍后重试。", publicUrl)

    private fun inputId(value: String): String? = value.takeIf(POSITIVE_ID::matches)
        ?: value.toHttpUrlOrNull()?.let(::canonicalId)

    private fun canonicalId(url: HttpUrl): String? {
        if (!isPublicOrigin(url) || url.query != null || !url.fragment.isNullOrEmpty()) return null
        return COMIC_PATH.matchEntire(url.encodedPath)?.groupValues?.get(1)
    }

    private fun isPublicOrigin(url: HttpUrl): Boolean =
        url.isHttps && url.host == HOST && url.port == 443 && url.username.isEmpty() && url.password.isEmpty()

    private fun mangaUrl(subjectId: String) = "$homeUrl/comic/$subjectId/"
    private fun invalidId(): Nothing = throw SourceException("漫画编号或作品地址无效，请重新输入。", homeUrl)
    private fun invalidRating(publicUrl: String): Nothing =
        throw SourceException("评分资料不完整或格式已变化，请稍后重试。", publicUrl)

    private data class SearchHit(val id: String, val title: String, val authors: List<String>)
    private data class SubjectDetails(val id: String, val titles: List<String>, val authors: List<String>)

    private companion object {
        const val HOST = "www.manhuagui.com"
        const val MAX_DETAILS = 6
        const val MAX_TEXT_LENGTH = 512
        val POSITIVE_ID = Regex("[1-9][0-9]{0,63}")
        val COMIC_PATH = Regex("/comic/([1-9][0-9]{0,63})/?")
        val AUTHOR_PATH = Regex("/author/[1-9][0-9]{0,63}/?")
        val BOOK_INFO = Regex("""\bvar\s+bInfo\s*=\s*\{(.*?)\}\s*;""", RegexOption.DOT_MATCHES_ALL)
        val BOOK_ID = Regex("""(?:^|,)\s*id\s*:\s*([1-9][0-9]{0,63})\s*(?=,|$)""")
        val BOOK_URL = Regex("""(?:^|,)\s*url\s*:\s*(["'])(/comic/[1-9][0-9]{0,63}/?)\1\s*(?=,|$)""")
        val AUTHOR_LABELS = setOf("漫画作者", "漫畫作者")
        val UNKNOWN_VALUES = setOf("暂无", "暫無", "未知", "不明", "--", "-")
    }
}
