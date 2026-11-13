package dev.mangaflow.data

import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import com.fleeksoft.ksoup.Ksoup as Jsoup
import com.fleeksoft.ksoup.nodes.Document

/**
 * Public comic HTML only. Search titles come from the complete title attribute.
 * Saved source documents, read without running their projects or fixtures:
 * https://github.com/Kinremtus/mediatracker/blob/a338f11d6e333d9bb2e0a65354a1a5d999ba6af4/tests/fixtures/acqq_search.html
 * https://github.com/lidavidm/rssfly/blob/3266e4375ba931f4b8899826d324cd56a868b929/test_data/acqq.646277.html
 * The search capture was committed in October 2026; the detail capture is from
 * January 2021. Current visible fields were researched, but today's DOM and
 * this adapter have not been exercised. Missing structure is an error.
 * The detail capture leaves the voting widget to an external script. It proves
 * the displayed value and voter count, not the maximum: scale 0 means unknown.
 */
internal class TencentReviewProvider(private val http: ReviewProviderHttp) : ExternalReviewProvider {
    override val id = "tencent"
    override val name = "腾讯动漫"
    override val homeUrl = "https://ac.qq.com"
    override val hasWorkReviews = false

    override suspend fun search(query: String): List<ProviderCandidate> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        if (term.length > MAX_TEXT_LENGTH) throw SourceException("搜索词过长，请缩短后重试。", homeUrl)
        val normalizedTerm = normalizeReviewTitle(term)
        if (normalizedTerm.isBlank()) return emptyList()
        val url = "$homeUrl/Comic/searchList/search/".toHttpUrl().newBuilder()
            .addPathSegment(term).build().toString()
        val document = document(url)
        val list = document.selectFirst("ul.mod_book_list.mod_all_works_list")
        val explicitlyEmpty = document.select(".all_total_num").any { it.text().trim() == "0" } &&
            document.select(".ssjg").any { it.text().contains("搜索结果") }
        if (list == null) {
            if (explicitlyEmpty) return emptyList()
            throw SourceException("搜索页面格式已变化，暂时无法读取作品。", url)
        }
        val anchors = list.select(".mod_book_name a[href]")
        if (anchors.isEmpty()) {
            if (explicitlyEmpty) return emptyList()
            throw SourceException("搜索页面缺少作品信息，请稍后重试。", url)
        }
        if (explicitlyEmpty || anchors.size > MAX_SEARCH_HITS) {
            throw SourceException("搜索页面的作品列表不完整，请稍后重试。", url)
        }
        val hits = anchors.map { anchor ->
            val comicId = linkedId(anchor.attr("href"), COMIC_PATH)
                ?: throw SourceException("搜索结果包含无法确认的漫画编号。", url)
            val title = requiredText(anchor.attr("title"), "搜索结果缺少完整作品标题。", url)
            TencentHit(comicId, title)
        }
        if (hits.groupBy(TencentHit::id).values.any { rows -> rows.map(TencentHit::title).distinct().size > 1 }) {
            throw SourceException("搜索结果的作品编号与标题不一致，请稍后重试。", url)
        }
        val uniqueHits = hits.distinctBy(TencentHit::id)
        val exact = uniqueHits.filter { normalizeReviewTitle(it.title) == normalizedTerm }
        // Detail supplies authors; never fetch every row or choose a match here.
        val selected = (exact.ifEmpty { uniqueHits }).take(MAX_DETAILS_PER_SEARCH)
        return selected.map { hit ->
            val detail = detail(hit.id)
            if (normalizeReviewTitle(detail.title) != normalizeReviewTitle(hit.title)) {
                throw SourceException("搜索结果与作品详情不一致，请稍后重试。", comicUrl(hit.id))
            }
            ProviderCandidate(
                match = ReviewMatch(hit.id, detail.title, detail.authors.joinToString(" · "), comicUrl(hit.id)),
                titles = listOf(hit.title, detail.title).distinct(), authors = detail.authors,
            )
        }
    }

    override suspend fun subject(id: String, includeComments: Boolean): ReviewSource {
        val comicId = requireId(id)
        val detail = detail(comicId)
        return ReviewSource(
            id = this.id, name = name, status = ReviewStatus.READY,
            url = comicUrl(comicId), matchedTitle = detail.title, subjectId = comicId,
            aliases = listOf(detail.title), score = detail.score, scoreScale = 0,
            scoreLabel = "原站评分", ratingCount = detail.ratingCount,
            message = if (detail.insufficientRatings) "评分人数不足，原站尚未公布评分。" else "",
            hasWorkReviews = false,
        )
    }

    override suspend fun page(id: String, page: Int): ReviewPage =
        throw SourceException("这个来源目前只提供评分，不支持读取评论。", comicUrl(requireId(id)))

    private suspend fun detail(id: String): TencentDetail {
        val url = comicUrl(id)
        val document = document(url)
        verifyIdentity(document, id, url)
        val titles = document.select(".works-intro-title strong")
        if (titles.size != 1) throw SourceException("作品详情缺少可确认的标题。", url)
        val title = requiredText(titles.single().text(), "作品详情缺少标题。", url)
        val authors = document.select(".works-author-name").map { author ->
            requiredText(author.attr("title").ifBlank { author.text() }, "作品详情缺少作者。", url)
        }.distinct()
        if (authors.isEmpty()) throw SourceException("作品详情缺少作者，暂时无法确认对应关系。", url)
        val rating = rating(document, url)
        return TencentDetail(title, authors, rating.score, rating.count, rating.insufficient)
    }

    private fun verifyIdentity(document: Document, expected: String, url: String) {
        val identities = mutableListOf<String>()
        document.select(".works-cover > a[href], .works-intro-view[href]").forEach { link ->
            identities += linkedId(link.attr("href"), CHAPTER_PATH)
                ?: throw SourceException("作品详情的漫画编号无法确认。", url)
        }
        document.select("link[rel=canonical][href]").forEach { canonical ->
            identities += linkedId(canonical.attr("href"), COMIC_PATH)
                ?: throw SourceException("作品详情的地址与漫画不一致。", url)
        }
        // Read a literal from the saved page's script shape; never execute JS.
        document.select("script:not([src])").forEach { script ->
            INLINE_COMIC_ID.findAll(script.data()).forEach { match -> identities += match.groupValues[1] }
        }
        if (identities.isEmpty() || identities.any { it != expected }) {
            throw SourceException("返回的作品详情不属于当前漫画，请重新选择。", url)
        }
    }

    private fun rating(document: Document, url: String): TencentRating {
        val sections = document.select(".works-score")
        if (sections.size != 1) throw SourceException("作品详情缺少评分区域，请稍后重试。", url)
        val section = sections.single()
        val insufficient = section.text().let { it.contains("评分人数过少") && it.contains("暂未计入评分") }
        val paragraphs = section.children().filter { it.tagName() == "p" }
        if (paragraphs.size > 1 || (paragraphs.isEmpty() && !insufficient)) {
            throw SourceException("评分页面格式已变化，请稍后重试。", url)
        }
        val paragraph = paragraphs.singleOrNull()
        val countNodes = paragraph?.select("span")?.toList().orEmpty()
        if (countNodes.size > 1) throw SourceException("评分人数格式已变化，请稍后重试。", url)
        val count = countNodes.singleOrNull()?.let { node ->
            val raw = node.text().trim()
            if (!DIGITS.matches(raw) || paragraph?.text()?.contains("人评分") != true) {
                throw SourceException("评分人数格式已变化，请稍后重试。", url)
            }
            raw.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw SourceException("评分人数超出可读取范围。", url)
        }
        if (insufficient || count == 0) return TencentRating(null, count, insufficient)
        if (count == null) throw SourceException("作品详情缺少评分人数，请稍后重试。", url)
        val scoreNodes = paragraph?.select("strong")?.toList().orEmpty()
        val rawScore = scoreNodes.singleOrNull()?.text()?.trim().orEmpty()
        val score = rawScore.takeIf { SCORE.matches(it) }?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: throw SourceException("作品评分格式已变化，请稍后重试。", url)
        return TencentRating(score, count, false)
    }

    private suspend fun document(url: String): Document {
        val html = http.text(url, "作品评分", url,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml"), minIntervalMillis = 1_000L)
        if (html.isBlank() || html.length > MAX_HTML_LENGTH) {
            throw SourceException("作品页面为空或超出可读取范围，请稍后重试。", url)
        }
        return Jsoup.parse(html, url)
    }

    private fun linkedId(href: String, path: Regex): String? {
        val url = homeUrl.toHttpUrl().resolve(href) ?: return null
        if (!url.isHttps || url.host != "ac.qq.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        return path.matchEntire(url.encodedPath)?.groupValues?.get(1)?.takeIf(ID::matches)
    }

    private fun requireId(raw: String): String = raw.trim().takeIf(ID::matches)
        ?: throw SourceException("漫画编号无效，请重新选择。", homeUrl)

    private fun requiredText(raw: String, error: String, url: String): String = raw
        .replace('\u00a0', ' ').replace(WHITESPACE, " ").trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH }
        ?: throw SourceException(error, url)

    private fun comicUrl(id: String) = "$homeUrl/Comic/comicInfo/id/$id"

    private data class TencentHit(val id: String, val title: String)
    private data class TencentDetail(
        val title: String, val authors: List<String>, val score: Double?,
        val ratingCount: Int?, val insufficientRatings: Boolean,
    )
    private data class TencentRating(val score: Double?, val count: Int?, val insufficient: Boolean)

    private companion object {
        const val MAX_DETAILS_PER_SEARCH = 6
        const val MAX_SEARCH_HITS = 100
        const val MAX_TEXT_LENGTH = 512
        const val MAX_HTML_LENGTH = 2_000_000
        val ID = Regex("[1-9][0-9]{0,63}")
        val DIGITS = Regex("[0-9]+")
        val SCORE = Regex("[0-9]+(?:\\.[0-9]+)?")
        val WHITESPACE = Regex("\\s+")
        val COMIC_PATH = Regex("/Comic/comicInfo/id/([0-9]{1,64})/?", RegexOption.IGNORE_CASE)
        val CHAPTER_PATH = Regex("/ComicView/index/id/([0-9]{1,64})/cid/[0-9]+/?", RegexOption.IGNORE_CASE)
        val INLINE_COMIC_ID = Regex("""\bvar\s+comicId\s*=\s*["']([0-9]{1,64})["']\s*;""")
    }
}
