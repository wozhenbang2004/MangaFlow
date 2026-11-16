package dev.mangaflow.data.sources

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceHttp
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.Request
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Modified from Keiyoushi Guazimanhua at 9080fbfd1d02ce2cad0f7466996fa19816f39401
 * (Apache-2.0). Public HTML only; no account, optional engine or page-image rewriting.
 * See third_party/multi-source/ADAPTATION.md and KEIYOUSHI-APACHE-2.0.txt.
 */
class GuaziBodySource : BodySource {
    override val info = BodySourceInfo("guazi", "瓜子漫画", "简中", HOME, "",
        revision = "guazi-anonymous-1")
    private val http = SourceHttp(sourceId = info.id)
    private val requests = Semaphore(2)
    private val starts = Mutex()
    private var nextStartAt = 0L

    override suspend fun capabilities() = SourceCapabilities(passwordLogin = false)

    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        listing("", page, "update")
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(query.trim().length in 1..120) { "请输入1至120个字符。" }
        listing(query.trim(), page, "hits")
    }

    private suspend fun listing(query: String, page: Int, sort: String): MangaPage {
        require(page in 0..999) { "瓜子分页超出范围。" }
        val url = "$HOME/category.php".toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort).addQueryParameter("page", (page + 1).toString())
            .apply { if (query.isNotEmpty()) addQueryParameter("keyword", query) }.build()
        val doc = document(url)
        val cards = doc.select("article.card")
        if (cards.isEmpty()) {
            if (EMPTY.containsMatchIn(doc.text())) return MangaPage(emptyList(), false)
            unavailable("列表结构缺失，可能需要站点验证或来源已改版", url)
        }
        val items = cards.map { card ->
            val title = card.selectFirst("h3 a")?.text()?.trim().orEmpty()
            if (title.isBlank()) unavailable("列表作品标题不完整", url)
            val href = card.selectFirst("a.cover-wrap")?.attr("href").orEmpty()
            val target = sourceUrl(href, url, "/comic.php")
            val native = nativePath(target)
            val cover = card.selectFirst("img.cover")?.attr("src").orEmpty()
            val author = card.selectFirst("div.meta")?.ownText()?.substringBefore(" · ")?.trim().orEmpty()
            Manga(SourceKeys.manga(info.id, native), title, imageUrl(cover, url, optional = true),
                authors = listOf(author).filter(String::isNotBlank), sourceUrl = target.toString(),
                sourceId = info.id, sourceMangaId = native, sourceName = info.name)
        }.distinctBy(Manga::id)
        val more = doc.select("nav.pager a").any { it.text().trim() == ">" }
        return MangaPage(items, more, (page + 1).takeIf { more })
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val native = mangaNativeId(mangaId)
        val url = sourceUrl(native, HOME.toHttpUrl(), "/comic.php")
        val doc = document(url)
        val title = doc.selectFirst("div.mobile-comic-title")?.text()?.trim().orEmpty()
        if (title.isBlank()) unavailable("详情标题缺失，可能需要站点验证", url)
        val meta = doc.selectFirst("p.mobile-comic-meta")?.text().orEmpty()
        val author = doc.select("div.cinema-strip > div").firstOrNull {
            it.selectFirst("span")?.text()?.trim() == "作者"
        }?.selectFirst("b")?.text()?.trim().orEmpty()
        val manga = Manga(SourceKeys.manga(info.id, native), title,
            imageUrl(doc.selectFirst("img.mobile-comic-cover")?.attr("src").orEmpty(), url, optional = true),
            authors = listOf(author).filter(String::isNotBlank),
            description = doc.selectFirst("p.mobile-comic-desc")?.text().orEmpty(),
            status = when { meta.contains("完结") -> "已完结"; meta.contains("连载") -> "连载中"; else -> "" },
            tags = doc.selectFirst("p.mobile-comic-tags")?.text()?.split(' ', '，', ',')
                ?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
            sourceUrl = url.toString(), sourceId = info.id, sourceMangaId = native, sourceName = info.name)
        val links = doc.select("section.mobile-comic-all-chapters div.mobile-chapter-grid a")
        if (links.isEmpty()) unavailable("没有取得完整章节目录，未把空目录当作成功", url)
        if (links.size > 20_000) unavailable("章节目录超出当前支持范围", url)
        // The upstream reader consumes newest-first chapters; our indices run oldest first.
        val chapters = links.map { link ->
            val chapterUrl = sourceUrl(link.attr("href"), url, "/chapter.php")
            checkChapterOwner(url, chapterUrl)
            val label = link.text().trim()
            if (label.isBlank()) unavailable("章节名称不完整", url)
            val key = nativePath(chapterUrl)
            val volume = VOLUME.containsMatchIn(label)
            val special = SPECIAL.containsMatchIn(label)
            val group = if (volume) "单行本" else if (special) "番外" else "连载"
            val variant = ChapterVariant(info.id, info.name, native, key, label, info.language,
                edition = if (volume) "单行本" else if (special) "番外" else "连载版",
                url = chapterUrl.toString())
            Chapter(variant.storageId, label, 0, group, variants = listOf(variant),
                sourceId = info.id, sourceMangaId = native, sourceChapterId = key)
        }.distinctBy(Chapter::id).reversed().mapIndexed { index, chapter -> chapter.copy(index = index) }
        MangaDetail(manga, chapters)
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> =
        withContext(Dispatchers.IO) {
            val native = mangaNativeId(mangaId)
            val ref = SourceKeys.decodeChapter(mangaId, chapterId)
            require(ref.sourceId == info.id && ref.mangaId == native) { "章节和瓜子作品来源不一致。" }
            val url = sourceUrl(ref.chapterId, HOME.toHttpUrl(), "/chapter.php")
            checkChapterOwner(sourceUrl(native, HOME.toHttpUrl(), "/comic.php"), url)
            val doc = document(url, forceRefresh)
            val images = doc.select("section.reader-images img")
            if (images.isEmpty()) unavailable("未取得正文图片，可能为站点验证、受限章节或App提示页", url)
            if (images.size > 5_000) unavailable("本章图片数超出当前支持范围", url)
            images.mapIndexed { index, image ->
                val target = imageUrl(image.attr("src"), url)
                val label = image.attr("alt") + " " + image.attr("title")
                if (PLACEHOLDER.containsMatchIn(target.substringBefore('?')) || APP_HINT.containsMatchIn(label))
                    unavailable("本章返回占位图片，未作为正文保存", url)
                Page(index, target, sourceRevision = info.revision)
            }
        }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        val native = mangaNativeId(mangaId)
        val referer = if (chapterId.isBlank()) sourceUrl(native, HOME.toHttpUrl(), "/comic.php") else {
            val ref = SourceKeys.decodeChapter(mangaId, chapterId)
            require(ref.sourceId == info.id && ref.mangaId == native) { "章节和瓜子来源不一致。" }
            sourceUrl(ref.chapterId, HOME.toHttpUrl(), "/chapter.php")
        }
        return mapOf("User-Agent" to USER_AGENT, "Referer" to referer.toString())
    }

    private suspend fun document(url: HttpUrl, fresh: Boolean = false): Document = requests.withPermit {
        starts.withLock {
            val remaining = nextStartAt - monotonicTimeMillis()
            if (remaining > 0L) delay(remaining)
            nextStartAt = monotonicTimeMillis() + 200L
        }
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Referer", "$HOME/").header("Accept", "text/html,application/xhtml+xml")
        if (fresh) request.header("Cache-Control", "no-cache")
        val response = http.request(request.build(),
            "瓜子漫画", url.toString())
        if (response.text.length > 4_194_304) unavailable("来源页面过大", url)
        if (CHALLENGE.containsMatchIn(response.text)) unavailable("站点要求访问验证，当前未返回漫画内容", url)
        Ksoup.parse(response.text, url.toString())
    }

    private fun mangaNativeId(value: String): String {
        val native = if (SourceKeys.isEncoded(value)) {
            val ref = SourceKeys.decodeManga(value)
            require(ref.sourceId == info.id) { "作品不属于瓜子漫画。" }
            ref.mangaId
        } else value
        return nativePath(sourceUrl(native, HOME.toHttpUrl(), "/comic.php"))
    }

    private fun sourceUrl(value: String, base: HttpUrl, path: String): HttpUrl {
        if (value.isBlank() || value.length > 4_096) unavailable("来源链接不完整", base)
        return base.resolve(value)?.takeIf {
            it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
                it.host in setOf("www.guazimanhua.com", "guazimanhua.com") && it.encodedPath == path && !it.query.isNullOrBlank()
        }?.newBuilder()?.host("www.guazimanhua.com")?.fragment(null)?.build()
            ?: unavailable("来源返回了不属于本源的作品或章节链接", base)
    }

    private fun nativePath(url: HttpUrl) = url.encodedPath + (url.query?.let { "?$it" } ?: "")

    private fun imageUrl(value: String, base: HttpUrl, optional: Boolean = false): String {
        if (value.isBlank() && optional) return ""
        val url = base.resolve(value.trim()) ?: unavailable("图片地址缺失", base)
        return checkedBodyUrl(url.toString()).toString()
    }

    private fun checkChapterOwner(manga: HttpUrl, chapter: HttpUrl) {
        val work = manga.queryParameter("id") ?: manga.queryParameter("comic_id")
        val owner = chapter.queryParameter("comic_id") ?: chapter.queryParameter("manga_id")
        require(owner == null || work == null || owner == work) { "来源返回了其他作品的章节，已停止读取。" }
    }

    private fun unavailable(reason: String, url: HttpUrl): Nothing = throw SourceException("瓜子：$reason。", url.toString())

    private companion object {
        const val HOME = "https://www.guazimanhua.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        val EMPTY = Regex("暂无(?:相关|搜索)?(?:漫画|结果|数据)|没有找到|未找到|无搜索结果")
        val CHALLENGE = Regex("cf-chl-|challenge-platform|Just a moment|验证您是人类|访问验证", RegexOption.IGNORE_CASE)
        val PLACEHOLDER = Regex("/(?:404|error|noimage|app-download|download-app)\\.(?:png|jpg|webp)$", RegexOption.IGNORE_CASE)
        val APP_HINT = Regex("下载\\s*app|下載\\s*app|打开\\s*app", RegexOption.IGNORE_CASE)
        val VOLUME = Regex("^(?:第\\s*)?[0-9]+(?:\\.[0-9]+)?\\s*[卷巻册冊]")
        val SPECIAL = Regex("番外|附录|附錄|后记|後記|特典|短篇|公式书|公式書")
    }
}
