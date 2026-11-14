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
import dev.mangaflow.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.Request
import com.fleeksoft.ksoup.Ksoup as Jsoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element

/**
 * Public-page adapter adapted from Keiyoushi Manhuagui (Apache-2.0),
 * fc7a827769f612dfa6dccb51e819f5b4eecaeab5. Adult cookies/hidden catalogues,
 * account calls and arbitrary script execution are deliberately not incorporated.
 */
class ManhuaguiBodySource : BodySource {
    override val info = BodySourceInfo("manhuagui", "漫画柜", "中文", HOME, "$HOME/favicon.ico")
    private val http = SourceHttp()
    private val requests = Mutex()
    private var lastRequestAt = 0L

    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(page in 0..999)
        val document = document("$HOME/list/view_p${page + 1}.html".toHttpUrl())
        val rows = document.select("ul#contList > li")
        if (rows.isEmpty()) throw SourceException("漫画柜目录页面结构已变化，请稍后重试。", HOME)
        val more = document.selectFirst("span.current + a") != null
        MangaPage(rows.map { mangaFromCard(it, document.location().orEmpty().ifBlank { HOME }, false) }.distinctBy(Manga::id),
            more, if (more) page + 1 else null)
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(query.trim().length in 1..120 && page in 0..999)
        val url = HOME.toHttpUrl().newBuilder().addPathSegment("s")
            .addPathSegment("${query.trim()}_p${page + 1}.html").build()
        val document = document(url)
        val rows = document.select("div.book-result > ul > li")
        if (rows.isEmpty()) {
            val text = document.text()
            if (text.contains("很抱歉，没有查找到与") || text.contains("很抱歉，沒有查找到與") ||
                Regex("共查找到\\s*0\\s*部").containsMatchIn(text)) return@withContext MangaPage(emptyList(), false)
            throw SourceException("漫画柜搜索没有返回可识别的作品列表，可能需要网站验证。", url.toString())
        }
        val more = document.selectFirst("span.current + a") != null
        MangaPage(rows.map { mangaFromCard(it, document.location().orEmpty().ifBlank { url.toString() }, true) }.distinctBy(Manga::id),
            more, if (more) page + 1 else null)
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val id = nativeMangaId(mangaId)
        val url = mangaUrl(id)
        val document = document(url.toHttpUrl())
        rejectHiddenContent(document, url)
        val title = document.selectFirst("div.book-title > h1")?.text()?.trim().orEmpty()
        if (title.isBlank()) throw SourceException("漫画柜没有返回作品详情，可能需要网站验证。", url)
        val authors = document.select("span:contains(漫画作者) > a, span:contains(漫畫作者) > a")
            .map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val tags = document.select("span:contains(漫画剧情) > a, span:contains(漫畫劇情) > a")
            .map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val status = document.selectFirst("div.book-detail > ul.detail-list > li.status > span > span")?.text().orEmpty()
        val cover = document.selectFirst("p.hcover > img")?.let { image ->
            image.attr("src").ifBlank { image.attr("data-src") }
        }.orEmpty()
        val manga = Manga(SourceKeys.manga(info.id, id), title, imageUrl(url, cover), authors,
            description = document.selectFirst("div#intro-all")?.text().orEmpty(),
            status = status, tags = tags,
            latestChapter = document.selectFirst("li.status a.blue")?.text().orEmpty(),
            sourceUrl = url, sourceId = info.id, sourceMangaId = id)
        val chapters = mutableListOf<Chapter>()
        document.select("[id^=chapter-list-]").forEach { section ->
            val sectionName = section.previousElementSibling()?.text().orEmpty()
            section.select("ul").reversed().forEach { list ->
                list.select("li > a.status0").forEach { anchor ->
                    val target = url.toHttpUrl().resolve(anchor.attr("href"))
                        ?: throw SourceException("漫画柜章节地址无效。", url)
                    val match = CHAPTER_PATH.matchEntire(target.encodedPath)
                        ?: throw SourceException("漫画柜章节编号格式已变化。", url)
                    if (match.groupValues[1] != id) throw SourceException("漫画柜目录包含不属于本作品的章节。", url)
                    val chapterId = numeric(match.groupValues[2])
                    val name = anchor.attr("title").ifBlank {
                        anchor.selectFirst("span")?.ownText() ?: anchor.text()
                    }.trim()
                    if (name.isBlank()) throw SourceException("漫画柜章节名称不完整。", url)
                    val volume = VOLUME_TITLE.containsMatchIn(name) || sectionName.contains("单行本") || sectionName.contains("單行本")
                    val special = SPECIAL_TITLE.containsMatchIn(name) || sectionName.contains("番外")
                    val group = if (volume) "单行本" else if (special) "番外" else "连载"
                    val variant = ChapterVariant(info.id, info.name, id, chapterId, name, info.language,
                        edition = if (volume) "单行本" else "连载版", url = target.toString(),
                        pageCount = PAGE_COUNT.find(anchor.text())?.groupValues?.get(1)?.toIntOrNull())
                    chapters.add(Chapter(variant.storageId, name, chapters.size, group,
                        variants = listOf(variant), sourceId = info.id, sourceMangaId = id, sourceChapterId = chapterId))
                }
            }
        }
        val unique = chapters.distinctBy(Chapter::id).mapIndexed { index, chapter -> chapter.copy(index = index) }
        if (unique.isEmpty()) throw SourceException("漫画柜暂未返回公开可读章节，可能受地区或访问限制。", url)
        MangaDetail(manga, unique)
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val reference = SourceKeys.decodeChapter(mangaId, chapterId)
        require(reference.sourceId == info.id)
        val owner = nativeMangaId(mangaId)
        val id = numeric(reference.mangaId)
        require(owner == id) { "漫画柜章节不属于当前来源作品。" }
        val chapter = numeric(reference.chapterId)
        val url = "$HOME/comic/$id/$chapter.html"
        val document = document(url.toHttpUrl())
        rejectHiddenContent(document, url)
        val data = try { ManhuaguiPayload.imageData(document.outerHtml()) } catch (failure: Exception) {
            throw SourceException("漫画柜图片资料暂时无法解析，请更新来源或稍后重试。", url, cause = failure)
        }
        listOf("bid", "bId").forEach { key ->
            data.opt(key)?.takeUnless { it == JSONObject.NULL }?.let {
                if (it.toString() != id) throw SourceException("漫画柜图片资料与作品不匹配。", url)
            }
        }
        data.opt("cid")?.takeUnless { it == JSONObject.NULL }?.let {
            if (it.toString() != chapter) throw SourceException("漫画柜图片资料与本章不匹配。", url)
        }
        val files = data.optJSONArray("files") ?: throw SourceException("漫画柜没有返回图片清单。", url)
        val path = data.bodyText("path")
        val signature = data.optJSONObject("sl") ?: throw SourceException("漫画柜图片签名不完整。", url)
        val expires = signature.opt("e")?.toString()?.takeIf { it.matches(Regex("[0-9]{1,20}")) }
            ?: throw SourceException("漫画柜图片有效期资料不完整。", url)
        val token = signature.bodyText("m").takeIf { it.length in 1..1024 }
            ?: throw SourceException("漫画柜图片签名不完整。", url)
        require(path.startsWith('/') && !path.contains("..") && !path.contains('?') && !path.contains('#')) { "漫画柜图片路径无效。" }
        if (files.length() !in 1..10_000) throw SourceException("漫画柜没有返回有效的本章图片。", url)
        (0 until files.length()).map { index ->
            val file = files.getString(index)
            require(file.isNotBlank() && !file.startsWith('/') && !file.contains("..") &&
                !file.contains('?') && !file.contains('#') && !file.contains('\\')) { "漫画柜图片文件名无效。" }
            val originalFile = if (ORIGINAL_WEBP_SUFFIX.containsMatchIn(file)) file.dropLast(5) else file
            val image = IMAGE_SERVER.toHttpUrl().resolve(path + originalFile)
                ?.takeIf { it.host == "i.hamreus.com" && it.isHttps }
                ?: throw SourceException("漫画柜图片地址无效。", url)
            Page(index, image.newBuilder().addQueryParameter("e", expires).addQueryParameter("m", token).build().toString())
        }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        val reference = SourceKeys.decodeChapter(mangaId, chapterId)
        require(reference.sourceId == info.id)
        return mapOf("User-Agent" to USER_AGENT, "Referer" to "$HOME/comic/${numeric(reference.mangaId)}/${numeric(reference.chapterId)}.html",
            "Accept" to "image/jpeg,image/png,image/*;q=0.5,*/*;q=0.1")
    }

    private fun mangaFromCard(element: Element, base: String, search: Boolean): Manga {
        val anchor = element.selectFirst(if (search) "div.book-detail dl > dt > a" else "a.bcover")
            ?: throw SourceException("漫画柜作品卡片结构已变化。", base)
        val target = base.toHttpUrl().resolve(anchor.attr("href"))
        val id = target?.encodedPath?.let { MANGA_PATH.matchEntire(it)?.groupValues?.get(1) }
            ?: throw SourceException("漫画柜作品编号无效。", base)
        val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
        if (title.isBlank()) throw SourceException("漫画柜作品标题不完整。", base)
        val image = element.selectFirst(if (search) "div.book-cover img" else "a.bcover img")
        val poster = image?.let { it.attr("src").ifBlank { it.attr("data-src") } }.orEmpty()
        val authors = element.select(".tags a[href*='/author/']").map { it.text().trim() }.filter(String::isNotBlank).distinct()
        return Manga(SourceKeys.manga(info.id, id), title, imageUrl(base, poster), authors,
            sourceUrl = mangaUrl(id), sourceId = info.id, sourceMangaId = id)
    }

    private suspend fun document(url: HttpUrl): Document = requests.withLock {
        val elapsed = monotonicTimeMillis()
        if (lastRequestAt > 0) delay((1_000L - (elapsed - lastRequestAt)).coerceAtLeast(0L))
        lastRequestAt = monotonicTimeMillis()
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Accept-Language", "zh-CN,zh;q=0.9").header("Referer", "$HOME/").build()
        val response = http.request(request, info.name, HOME)
        Jsoup.parse(response.text, url.toString())
    }

    private fun rejectHiddenContent(document: Document, url: String) {
        if (document.selectFirst("#__VIEWSTATE, #erroraudit_show") != null)
            throw SourceException("漫画柜此作品需要站点额外访问条件，当前公开来源不提供。", url)
    }

    private fun imageUrl(base: String, value: String): String {
        if (value.isBlank()) return ""
        val url = base.toHttpUrl().resolve(value) ?: return ""
        return url.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }?.toString().orEmpty()
    }

    private fun nativeMangaId(value: String): String {
        val reference = SourceKeys.decodeManga(value)
        require(!SourceKeys.isEncoded(value) || reference.sourceId == info.id)
        return numeric(reference.mangaId)
    }
    private fun numeric(value: String): String = value.also { require(it.matches(Regex("[0-9]{1,20}"))) { "漫画柜编号无效。" } }
    private fun mangaUrl(id: String) = "$HOME/comic/${numeric(id)}/"

    companion object {
        private const val HOME = "https://www.manhuagui.com"
        private const val IMAGE_SERVER = "https://i.hamreus.com"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        private val MANGA_PATH = Regex("/comic/([0-9]{1,20})/?")
        private val CHAPTER_PATH = Regex("/comic/([0-9]{1,20})/([0-9]{1,20})\\.html")
        private val VOLUME_TITLE = Regex("^(?:第\\s*)?[0-9]+(?:\\.[0-9]+)?\\s*[卷巻册冊]")
        private val SPECIAL_TITLE = Regex("番外|附录|附錄|后记|後記|特典|短篇|公式书|公式書")
        private val ORIGINAL_WEBP_SUFFIX = Regex("\\.(?:jpe?g|png)\\.webp$", RegexOption.IGNORE_CASE)
        private val PAGE_COUNT = Regex("[（(]\\s*([0-9]+)\\s*[pP]\\s*[）)]")
    }
}
