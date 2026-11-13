package dev.mangaflow.data.sources

import dev.mangaflow.platform.*

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceHttp
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.normalizedBodySourceTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import com.fleeksoft.ksoup.Ksoup as Jsoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import dev.mangaflow.net.Request

/**
 * Modified from Keiyoushi's Baozi HTML contract at fc7a8277 (Apache-2.0).
 * Uses the public app-reader route; no Gatekeeper solver, login, banner cropping,
 * pixel rewriting or remote JavaScript execution is incorporated.
 */
class BaoziBodySource : BodySource {
    override val info = BodySourceInfo("baozi", "包子", "简中", SITE, "$HOME/favicon.ico")
    private val http = SourceHttp()
    private val requests = Semaphore(2)
    private val requestStarts = Mutex()
    private var nextRequestAt = 0L

    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(page in 0..999) { "包子分页超出范围。" }
        val url = "$SITE/classify".toHttpUrl().newBuilder()
            .addQueryParameter("page", (page + 1).toString()).build()
        val values = cards(document(url))
        val hasMore = values.size >= 36
        MangaPage(values, hasMore, if (hasMore) page + 1 else null)
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(query.trim().length in 1..120) { "请输入1至120个字符。" }
        require(page >= 0)
        if (page > 0) return@withContext MangaPage(emptyList(), false)
        val url = "$SITE/search".toHttpUrl().newBuilder().addQueryParameter("q", query.trim()).build()
        val normalizedQuery = normalizedBodySourceTitle(query)
        // The full-page selector can include unrelated cards. No unverified search-container
        // selector is assumed; only cards containing the full query survive.
        MangaPage(cards(document(url)).filter {
            normalizedQuery.isNotBlank() && normalizedBodySourceTitle(it.title).contains(normalizedQuery)
        }, false)
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val slug = sourceMangaId(mangaId)
        val url = "$SITE/comic/".toHttpUrl().newBuilder().addPathSegment(slug).build()
        val doc = document(url)
        val title = doc.selectFirst("h1.comics-detail__title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() && !it.contains("{{") }
            ?: incompatible("详情页缺少作品标题", url)
        doc.selectFirst("link[rel=canonical]")?.attr("href")?.let { canonical ->
            comicSlug(canonical)?.let { if (it != slug) incompatible("详情页作品归属不一致", url) }
        }
        val cover = doc.selectFirst("meta[name=og:image], meta[property=og:image]")?.attr("content")
            ?.takeIf(String::isNotBlank)?.let { coverUrl(it, url) }.orEmpty()
        val tags = doc.select("div.tag-list > span.tag").map { it.text().trim() }.filter(String::isNotBlank)
        val manga = Manga(
            id = SourceKeys.manga(info.id, slug), title = title, coverUrl = cover,
            authors = doc.selectFirst("h2.comics-detail__author")?.text()?.split(',', '，')
                ?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
            description = doc.selectFirst("p.comics-detail__desc")?.text().orEmpty(),
            status = tags.firstOrNull().orEmpty(), tags = tags.drop(1),
            sourceUrl = url.toString(), sourceId = info.id, sourceMangaId = slug,
        )
        val primary = doc.select("#chapter-items .comics-chapters, #chapters_other_list .comics-chapters")
        val elements = if (primary.isNotEmpty()) primary.reversed() else doc.select(".comics-chapters")
        if (elements.isEmpty()) incompatible("来源没有返回完整章节目录", url)
        val seen = hashSetOf<String>()
        val chapters = elements.mapNotNull { element ->
            val chapterTitle = element.text().trim()
            if (chapterTitle.isBlank() || chapterTitle.contains("{{")) return@mapNotNull null
            val target = chapterTarget(element)
                ?: incompatible("章节链接暂不兼容", url)
            if (target.slug != slug) incompatible("目录包含其他作品的章节，已停止关联", url)
            if (!seen.add(target.key)) return@mapNotNull null
            val book = VOLUME.find(chapterTitle) != null && !INNER_CHAPTER.containsMatchIn(chapterTitle)
            val extra = EXTRA.containsMatchIn(chapterTitle)
            val group = when { book -> "单行本"; extra -> "番外"; else -> "连载" }
            // Nonzero sections may be parallel editions. Retain the upstream section
            // rather than asserting that equal numbers in different sections are equal.
            val edition = when {
                book -> "单行本"
                extra -> "番外"
                target.section == "0" -> "连载版"
                else -> "章节分区${target.section}"
            }
            val variant = ChapterVariant(
                info.id, info.name, slug, target.key, chapterTitle, info.language,
                edition = edition, url = chapterUrl(target).toString(),
            )
            Chapter(variant.storageId, chapterTitle, 0, group, variants = listOf(variant),
                sourceId = info.id, sourceMangaId = slug, sourceChapterId = target.key)
        }.mapIndexed { index, chapter -> chapter.copy(index = index) }
        if (chapters.isEmpty()) incompatible("来源章节目录为空或仅有客户端模板", url)
        MangaDetail(manga, chapters)
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id) { "章节和包子来源不一致。" }
        val target = fromKey(slug(ref.mangaId), ref.chapterId)
        val url = chapterUrl(target)
        val doc = document(url)
        doc.selectFirst("link[rel=canonical]")?.attr("href")?.let { canonical ->
            parseChapterHref(canonical)?.let {
                if (it.slug != target.slug || it.key != target.key) incompatible("正文章节归属不一致", url)
            }
        }
        val images = doc.select(".comic-contain img[data-src]")
        if (images.isEmpty()) incompatible("未取得正文原图，可能需要站点验证或当前页面只提供App下载提示", url)
        images.mapIndexed { index, image ->
            val raw = image.attr("data-src").trim().takeIf(String::isNotBlank)
                ?: incompatible("图片地址缺失", url)
            val resolved = url.resolve(raw) ?: incompatible("图片地址无效", url)
            val path = resolved.encodedPath.lowercase()
            val label = (image.attr("alt") + " " + image.attr("title")).lowercase()
            if (path.endsWith("/404.jpg") || path.endsWith("/404.png") ||
                APP_PLACEHOLDER.containsMatchIn(path) || label.contains("下载app") || label.contains("下載app")) {
                incompatible("本章返回了缺图或App下载占位，未作为漫画保存", url)
            }
            val checked = checkedBodyUrl(resolved.toString())
            val host = if (checked.host.endsWith(".baozicdn.com"))
                checked.host.removeSuffix(".baozicdn.com") + ".baozimh.com" else checked.host
            Page(index, checked.newBuilder().host(host).build().toString())
        }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id) { "章节和包子来源不一致。" }
        val target = fromKey(slug(ref.mangaId), ref.chapterId)
        return mapOf("User-Agent" to USER_AGENT, "Referer" to chapterUrl(target).toString(),
            "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8")
    }

    private suspend fun document(url: HttpUrl): Document = requests.withPermit {
        requestStarts.withLock {
            val remaining = nextRequestAt - (monotonicTimeMillis() * 1_000_000L)
            if (remaining > 0L) delay((remaining + 999_999L) / 1_000_000L)
            nextRequestAt = (monotonicTimeMillis() * 1_000_000L) + 500_000_000L
        }
        val response = try {
            http.request(Request.Builder().url(url).header("User-Agent", USER_AGENT)
                .header("Referer", "$SITE/").header("Accept", "text/html,application/xhtml+xml").build(),
                "包子漫画", url.toString())
        } catch (failure: SourceException) {
            if (failure.httpStatus == 403 || failure.httpStatus == 401) throw SourceException(
                "包子要求站点验证或拒绝当前访问；当前来源没有自动验证能力。", url.toString(),
                failure.retryAfterSeconds, failure, failure.httpStatus)
            throw failure
        }
        if (CHALLENGE.containsMatchIn(response.text)) throw SourceException(
            "包子要求先完成站点验证，未把验证页当成空目录。", url.toString())
        Jsoup.parse(response.text, url.toString())
    }

    private fun cards(doc: Document): List<Manga> {
        val documentUrl = doc.location().orEmpty().ifBlank { "$SITE/" }
        val elements = doc.select("div.comics-card")
        if (elements.isEmpty()) {
            if (NO_RESULTS.containsMatchIn(doc.text())) return emptyList()
            throw SourceException("包子搜索或分类页面暂不兼容，未把缺失结构当成无结果。", documentUrl)
        }
        val result = elements.mapNotNull { card ->
            val title = card.selectFirst(".comics-card__title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() && !it.contains("{{") } ?: return@mapNotNull null
            val poster = card.selectFirst(".comics-card__poster") ?: return@mapNotNull null
            val nativeId = poster.attr("href").takeIf(String::isNotBlank)?.let(::comicSlug)
                ?: COMIC_ONCLICK.find(poster.attr("onclick"))?.groupValues?.get(1)?.let(::slug)
                ?: return@mapNotNull null
            val image = card.selectFirst("amp-img, img")
            val cover = image?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?.takeIf(String::isNotBlank)?.let { coverUrl(it, documentUrl.toHttpUrl()) }.orEmpty()
            Manga(SourceKeys.manga(info.id, nativeId), title, cover,
                sourceUrl = "$SITE/comic/".toHttpUrl().newBuilder().addPathSegment(nativeId).build().toString(),
                sourceId = info.id, sourceMangaId = nativeId)
        }.distinctBy(Manga::id)
        if (result.isEmpty()) throw SourceException("包子列表只有客户端模板或失效链接，请更新来源后重试。", documentUrl)
        return result
    }

    private fun chapterTarget(element: Element): ChapterTarget? {
        val href = (if (element.tagName() == "a" && element.hasAttr("href")) element
            else element.selectFirst("a[href]"))?.attr("href")
        href?.let(::parseChapterHref)?.let { return it }
        val onclick = element.attr("onclick").ifBlank { element.selectFirst("[onclick]")?.attr("onclick").orEmpty() }
        val match = CHAPTER_ONCLICK.find(onclick) ?: return null
        return ChapterTarget(slug(match.groupValues[1]), slot(match.groupValues[2]), slot(match.groupValues[3]))
    }

    private fun comicSlug(href: String): String? {
        val url = publicUrl(href) ?: return null
        val at = url.pathSegments.indexOf("comic")
        if (at < 0) return null
        val value = url.pathSegments.getOrNull(at + 1)?.takeUnless { it == "chapter" } ?: return null
        return slug(value)
    }

    private fun parseChapterHref(href: String): ChapterTarget? {
        val url = publicUrl(href) ?: return null
        val queryId = url.queryParameter("comic_id")
        if (!queryId.isNullOrBlank()) {
            val section = url.queryParameter("section_slot") ?: return null
            val chapter = url.queryParameter("chapter_slot") ?: return null
            return ChapterTarget(slug(queryId), slot(section), slot(chapter))
        }
        val at = url.pathSegments.indexOf("chapter")
        if (at < 0) return null
        val nativeId = url.pathSegments.getOrNull(at + 1) ?: return null
        val key = url.pathSegments.getOrNull(at + 2)?.substringBefore('#')?.removeSuffix(".html") ?: return null
        return fromKey(slug(nativeId), key)
    }

    private fun publicUrl(href: String): HttpUrl? = SITE.toHttpUrl().resolve(href)?.takeIf { url ->
        url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
            DOMAINS.any { url.host == it || url.host.endsWith(".$it") }
    }

    private fun coverUrl(raw: String, base: HttpUrl): String {
        val value = NESTED_COVER.find(raw)?.groupValues?.get(1) ?: raw
        return checkedBodyUrl(base.resolve(value)?.toString()
            ?: throw SourceException("包子封面地址无效。", base.toString())).toString()
    }

    private fun chapterUrl(target: ChapterTarget): HttpUrl = "$SITE/comic/chapter/".toHttpUrl().newBuilder()
        .addPathSegment(target.slug).addPathSegment("${target.key}.html").build()

    private fun sourceMangaId(value: String): String {
        val ref = SourceKeys.decodeManga(value)
        require(!SourceKeys.isEncoded(value) || ref.sourceId == info.id) { "作品和包子来源不一致。" }
        return slug(ref.mangaId)
    }

    private fun fromKey(slug: String, key: String): ChapterTarget {
        val match = CHAPTER_KEY.matchEntire(key) ?: throw SourceException("包子章节编号无效。", SITE)
        return ChapterTarget(slug, slot(match.groupValues[1]), slot(match.groupValues[2]))
    }

    private fun slot(value: String): String {
        require(value.matches(Regex("[0-9]{1,12}"))) { "包子章节位置无效。" }
        return value.toLong().toString()
    }

    private fun slug(value: String): String = value.also {
        require(it.isNotBlank() && it.length <= 512 && it.none { c -> c == '/' || c == '\\' || c == '?' || c == '#' || c.isISOControl() }) {
            "包子作品编号无效。"
        }
    }

    private fun incompatible(reason: String, url: HttpUrl): Nothing =
        throw SourceException("包子$reason，请重试或更新来源。", url.toString())

    private data class ChapterTarget(val slug: String, val section: String, val chapter: String) {
        val key: String get() = "${section}_$chapter"
    }

    companion object {
        private const val HOME = "https://appcn.baozimh.com"
        private const val SITE = "$HOME/baozimhapp"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"
        private val DOMAINS = setOf("baozimh.com", "webmota.com", "kukuc.co", "twmanga.com", "dinnerku.com")
        private val COMIC_ONCLICK = Regex("""\['comic',\s*'([^']+)'""")
        private val CHAPTER_ONCLICK = Regex("""\['chapter',\s*'([^']+)',\s*(\d+),\s*(\d+)\]""")
        private val CHAPTER_KEY = Regex("""(\d{1,12})_(\d{1,12})""")
        private val NESTED_COVER = Regex("""/cover/(https?://[^?]+)""")
        private val VOLUME = Regex("""^(?:第\s*)?\d+(?:\.\d+)?\s*(?:卷|巻|册|冊)|^vol(?:ume)?\.?\s*\d+""", RegexOption.IGNORE_CASE)
        private val INNER_CHAPTER = Regex("""(?:卷|巻|册|冊)\s*(?:第\s*)?\d+\s*[话話章回]""")
        private val EXTRA = Regex("""番外|特别篇|特別篇|外传|外傳|后记|後記|special|extra|omake""", RegexOption.IGNORE_CASE)
        private val APP_PLACEHOLDER = Regex("""(?:^|[/_.-])(?:download[_-]?app|app[_-]?download|downloadapp|appdownload)(?:[/_.-]|$)""")
        private val CHALLENGE = Regex("""cf-chl-|challenge-platform|checking your browser|id=["']challenge-form["']""", RegexOption.IGNORE_CASE)
        private val NO_RESULTS = Regex("""没有找到|沒有找到|未找到|暂无搜索结果|暫無搜尋結果|無搜尋結果""")
    }
}
