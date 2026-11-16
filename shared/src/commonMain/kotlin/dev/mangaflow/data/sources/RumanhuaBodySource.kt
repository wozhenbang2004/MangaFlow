package dev.mangaflow.data.sources

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceHttp
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.FormBody
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.Request
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Public-page adapter adapted from Keiyoushi's Rumanhua/MMLook (Apache-2.0),
 * 9080fbfd1d02ce2cad0f7466996fa19816f39401. The upstream certificate bypass
 * and HTTP URL workaround are not incorporated; ordinary system TLS stays authoritative.
 */
class RumanhuaBodySource : BodySource {
    override val info = BodySourceInfo("rumanhua", "如漫画", "中文", HOME, "$HOME/favicon.ico",
        revision = "mmlook-9080fbf-public-v1")
    private val http = SourceHttp(sourceId = info.id)
    private val requests = Semaphore(2)
    private val starts = Mutex()
    private var lastRequestAt = 0L

    override suspend fun capabilities() = SourceCapabilities(
        features = setOf(SourceFeature.DISCOVERY, SourceFeature.SEARCH, SourceFeature.RANKING),
        loginRequired = false, passwordLogin = false,
    )

    override suspend fun feeds() = listOf(
        SourceFeed("latest", "最近更新", SourceFeedKind.LATEST),
        SourceFeed("popular", "人气排行", SourceFeedKind.RANKING),
    )

    override suspend fun browse(request: SourceBrowseRequest): MangaPage {
        require(request.options.isEmpty()) { "如漫画不支持这个浏览条件。" }
        return when (request.feedId) {
            "latest" -> discover(request.page)
            "popular" -> ranking(request.page, "1")
            else -> throw SourceException("如漫画不支持这个榜单。", HOME)
        }
    }

    override suspend fun discover(page: Int): MangaPage = ranking(page, "5")

    private suspend fun ranking(page: Int, rankingId: String): MangaPage = withContext(Dispatchers.IO) {
        require(page in 0..999)
        // The upstream publishes one complete ranking; there is no fabricated next-page request.
        if (page != 0) return@withContext MangaPage(emptyList(), false)
        val url = "$DESKTOP/rank/$rankingId"
        val document = document(url)
        val rows = document.select(".likedata")
        if (rows.isEmpty()) throw SourceException("如漫画榜单未返回公开作品列表，可能需要网站验证。", url)
        require(rows.size <= 1000) { "如漫画榜单资料过大。" }
        MangaPage(rows.map { rankingCard(it, url) }.distinctBy(Manga::id), false)
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(query.trim().length in 1..120 && page in 0..999)
        if (page != 0) return@withContext MangaPage(emptyList(), false)
        val url = "$DESKTOP/s"
        // MMLook's public search accepts at most twelve characters and has no pagination.
        val response = text(url, FormBody.Builder().add("k", query.trim().take(12)).build())
        val document = Ksoup.parse(response, url)
        rejectChallenge(document, url)
        val rows = document.select(".item-data > div")
        if (rows.isEmpty() && document.selectFirst(".item-data") == null)
            throw SourceException("如漫画搜索页面结构已变化或需要网站验证。", url)
        require(rows.size <= 1000) { "如漫画搜索结果过大。" }
        MangaPage(rows.map { searchCard(it, url) }.distinctBy(Manga::id), false)
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val id = nativeMangaId(mangaId)
        val url = "$DESKTOP/$id/"
        val document = document(url)
        val infoElement = document.selectFirst(".comicInfo")
            ?: throw SourceException("如漫画未返回公开作品详情，可能需要网站验证。", url)
        val details = infoElement.selectFirst(".detinfo")
            ?: throw SourceException("如漫画作品详情格式已变化。", url)
        val title = details.selectFirst("h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) throw SourceException("如漫画作品标题不完整。", url)
        var authors = emptyList<String>()
        var tags = emptyList<String>()
        var status = ""
        for (span in details.select("span")) {
            val text = span.text().trim()
            when {
                text.startsWith("作 者：") -> authors = listOf(text.removePrefix("作 者：").trim()).filter(String::isNotBlank)
                text.startsWith("标 签：") -> tags = text.removePrefix("标 签：").trim()
                    .split(Regex("\\s+")).filter(String::isNotBlank).distinct()
                text.startsWith("状 态：") -> status = text.removePrefix("状 态：").trim()
            }
        }
        val chapters = chapterList(document, id, url)
        val manga = Manga(SourceKeys.manga(info.id, id), title, cover(infoElement.selectFirst("img"), url),
            authors, description = details.selectFirst(".content")?.text().orEmpty(), status = status,
            tags = tags, latestChapter = chapters.lastOrNull()?.title.orEmpty(),
            sourceUrl = "$HOME/$id/", sourceId = info.id, sourceMangaId = id, sourceName = info.name)
        MangaDetail(manga, chapters)
    }

    private suspend fun chapterList(document: Document, mangaId: String, publicUrl: String): List<Chapter> {
        val container = document.selectFirst(".chapterlistload")
            ?: throw SourceException("如漫画未返回公开章节目录。", publicUrl)
        val initial = container.children().firstOrNull()?.children().orEmpty()
        if (initial.size > 20_000) throw SourceException("如漫画章节目录过大。", publicUrl)
        val chapters = mutableListOf<Pair<String, String>>()
        for (element in initial) {
            val anchor = if (element.tagName() == "a") element else element.selectFirst("a[href]")
                ?: throw SourceException("如漫画章节目录结构已变化。", publicUrl)
            val path = siteUrl(anchor.attr("href"), publicUrl).encodedPath
            val match = CHAPTER_PATH.matchEntire(path)
                ?: throw SourceException("如漫画章节编号无效。", publicUrl)
            if (match.groupValues[1] != mangaId) throw SourceException("如漫画返回了其他作品的章节。", publicUrl)
            val title = anchor.text().trim()
            if (title.isBlank()) throw SourceException("如漫画章节名称不完整。", publicUrl)
            chapters += identity(match.groupValues[2]) to title
        }
        if (container.selectFirst(".chaplist-more") != null) {
            val json = try { JSONObject(text("$DESKTOP/morechapter",
                FormBody.Builder().add("id", mangaId).build(), publicUrl, xhr = true)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: SourceException) { throw failure }
            catch (failure: Exception) { throw SourceException("如漫画完整目录返回格式已变化。", publicUrl, cause = failure) }
            val extra = json.optJSONArray("data")
                ?: throw SourceException("如漫画没有返回完整章节目录。", publicUrl)
            if (extra.length() > 20_000) throw SourceException("如漫画章节目录过大。", publicUrl)
            for (index in 0 until extra.length()) {
                val row = extra.getJSONObject(index)
                val chapter = identity(row.bodyText("chapterid"))
                val title = row.bodyText("chaptername")
                if (title.isBlank()) throw SourceException("如漫画完整目录的章节名称不完整。", publicUrl)
                chapters += chapter to title
            }
        }
        if (chapters.isEmpty()) throw SourceException("如漫画暂未返回公开可读章节。", publicUrl)
        if (chapters.size > 20_000) throw SourceException("如漫画章节目录过大。", publicUrl)
        // Upstream Tachiyomi lists are newest first; MangaFlow indices follow reading order.
        return chapters.distinctBy { it.first }.asReversed().mapIndexed { index, (chapterId, title) ->
            val volume = VOLUME_TITLE.containsMatchIn(title)
            val special = SPECIAL_TITLE.containsMatchIn(title)
            val group = if (volume) "单行本" else if (special) "番外" else "连载"
            val variant = ChapterVariant(info.id, info.name, mangaId, chapterId, title, info.language,
                edition = if (volume) "单行本" else "连载版", url = chapterUrl(mangaId, chapterId))
            Chapter(variant.storageId, title, index, group, variants = listOf(variant),
                sourceId = info.id, sourceMangaId = mangaId, sourceChapterId = chapterId)
        }
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id && identity(ref.mangaId) == nativeMangaId(mangaId)) {
            "如漫画章节不属于当前来源作品。"
        }
        val url = chapterUrl(ref.mangaId, identity(ref.chapterId))
        val document = document(url, fresh = forceRefresh)
        val keyIndex = document.selectFirst(".readerContainer")?.attr("data-id")?.toIntOrNull()
            ?.takeIf { it in 0..9 }
            ?: throw SourceException("如漫画没有返回公开阅读资料，可能需要网站验证或原站权限。", url)
        val script = document.select("script").firstOrNull { it.data().contains("eval") }?.data()
            ?: throw SourceException("如漫画公开阅读资料格式已变化。", url)
        val images = try { RumanhuaPayload.images(script, keyIndex) }
        catch (failure: Exception) { throw SourceException("如漫画整章图片资料暂时无法解析，请更新来源后重试。", url, cause = failure) }
        images.mapIndexed { index, image ->
            val target = url.toHttpUrl().resolve(image)
                ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }
                ?: throw SourceException("如漫画返回了非HTTPS或无效图片地址，当前来源不降低传输安全。", url)
            Page(index, target.toString(), sourceRevision = info.revision)
        }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        val id = nativeMangaId(mangaId)
        val referer = if (chapterId.isBlank()) "$HOME/$id/" else {
            val ref = SourceKeys.decodeChapter(mangaId, chapterId)
            require(ref.sourceId == info.id && identity(ref.mangaId) == id) { "如漫画图片来源归属不一致。" }
            chapterUrl(id, identity(ref.chapterId))
        }
        return mapOf("User-Agent" to USER_AGENT, "Referer" to referer,
            "Accept" to "image/jpeg,image/png,image/webp,image/*;q=0.8,*/*;q=0.1")
    }

    private fun rankingCard(element: Element, base: String): Manga {
        val anchor = element.selectFirst("a[href]") ?: throw SourceException("如漫画榜单作品地址不完整。", base)
        val title = element.selectFirst(".le-t")?.text()?.trim().orEmpty()
        val author = element.selectFirst(".likeinfo > p")?.text()?.removePrefix("作者：")?.trim().orEmpty()
        return card(anchor, title, element.selectFirst("img"), base, author,
            element.selectFirst(".le-j")?.text().orEmpty())
    }

    private fun searchCard(element: Element, base: String): Manga {
        val anchor = element.selectFirst("a[href]") ?: throw SourceException("如漫画搜索作品地址不完整。", base)
        val title = element.selectFirst(".e-title, .title")?.text()?.trim().orEmpty()
        return card(anchor, title, element.selectFirst("img"), base,
            element.selectFirst(".tip")?.text()?.trim().orEmpty())
    }

    private fun card(anchor: Element, title: String, image: Element?, base: String,
        author: String, description: String = ""): Manga {
        val path = siteUrl(anchor.attr("href"), base).encodedPath
        val id = MANGA_PATH.matchEntire(path)?.groupValues?.get(1)?.let(::identity)
            ?: throw SourceException("如漫画作品编号无效。", base)
        if (title.isBlank()) throw SourceException("如漫画作品标题不完整。", base)
        return Manga(SourceKeys.manga(info.id, id), title, cover(image, base),
            authors = listOf(author).filter(String::isNotBlank), description = description,
            sourceUrl = "$HOME/$id/", sourceId = info.id, sourceMangaId = id, sourceName = info.name)
    }

    private fun cover(element: Element?, base: String): String {
        val value = element?.let { it.attr("data-src").ifBlank { it.attr("src") } }.orEmpty()
        if (value.isBlank()) return ""
        return base.toHttpUrl().resolve(value)
            ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }
            ?.toString().orEmpty()
    }

    private suspend fun document(url: String, fresh: Boolean = false): Document = Ksoup.parse(text(url, fresh = fresh), url).also { rejectChallenge(it, url) }

    private suspend fun text(url: String, form: FormBody? = null, referer: String = "$DESKTOP/", xhr: Boolean = false,
        fresh: Boolean = false): String = requests.withPermit {
            val target = siteUrl(url, DESKTOP)
            starts.withLock {
                val now = monotonicTimeMillis()
                if (lastRequestAt > 0L) delay((1_000L - (now - lastRequestAt)).coerceAtLeast(0L))
                lastRequestAt = monotonicTimeMillis()
            }
            val builder = Request.Builder().url(target).header("User-Agent", USER_AGENT)
                .header("Accept-Language", "zh-CN,zh;q=0.9").header("Referer", referer)
            if (form != null) builder.header("Origin", DESKTOP).post(form)
            if (xhr) builder.header("X-Requested-With", "XMLHttpRequest")
            if (fresh) builder.header("Cache-Control", "no-cache")
            http.request(builder.build(), info.name, url).text
        }

    private fun rejectChallenge(document: Document, url: String) {
        if (document.selectFirst("#challenge-form, #cf-challenge-running, .g-recaptcha, #turnstile-wrapper") != null ||
            document.title().contains("Just a moment", true) || document.title().contains("Attention Required", true))
            throw SourceException("如漫画需要原站访问验证，匿名来源暂不可用。", url)
    }

    private fun siteUrl(value: String, base: String): HttpUrl = base.toHttpUrl().resolve(value)
        ?.takeIf { it.isHttps && it.host in HOSTS && it.port == 443 && it.username.isEmpty() &&
            it.password.isEmpty() && it.query == null && it.fragment == null }
        ?: throw SourceException("如漫画页面地址不属于已确认的HTTPS来源。", HOME)

    private fun nativeMangaId(value: String): String {
        val ref = SourceKeys.decodeManga(value)
        require(!SourceKeys.isEncoded(value) || ref.sourceId == info.id) { "如漫画作品来源归属不一致。" }
        return identity(ref.mangaId)
    }
    private fun identity(value: String): String = value.also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "如漫画作品或章节编号无效。" }
    }
    private fun chapterUrl(mangaId: String, chapterId: String) = "$HOME/${identity(mangaId)}/${identity(chapterId)}.html"

    companion object {
        private const val HOME = "https://m.rumanhua2.com"
        private const val DESKTOP = "https://www.rumanhua2.com"
        private val HOSTS = setOf("m.rumanhua2.com", "www.rumanhua2.com")
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        private val MANGA_PATH = Regex("/([A-Za-z0-9_-]{1,128})/")
        private val CHAPTER_PATH = Regex("/([A-Za-z0-9_-]{1,128})/([A-Za-z0-9_-]{1,128})\\.html")
        private val VOLUME_TITLE = Regex("^(?:第\\s*)?[0-9]+(?:\\.[0-9]+)?\\s*[卷巻册冊]")
        private val SPECIAL_TITLE = Regex("番外|附录|附錄|后记|後記|特典|短篇|公式书|公式書")
    }
}
