package dev.mangaflow.data.sources

import dev.mangaflow.platform.*

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceHttp
import dev.mangaflow.data.SourceKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.Request
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONTokener
import com.fleeksoft.ksoup.Ksoup as Jsoup
import com.fleeksoft.ksoup.nodes.Document

/**
 * Adapted from Keiyoushi's Mangabz/MangabzTheme at 535ffdb (Apache-2.0).
 * Public HTTPS pages and chapterimage batches only; no account or HTTP mirror.
 * The bounded string unpacker decodes the page's public data without executing JS.
 */
class MangaBzBodySource : BodySource {
    override val info = BodySourceInfo("mangabz", "MangaBZ", "简中", HOME, "$HOME/favicon.ico")
    private val http = SourceHttp()
    private val networkSlots = Semaphore(2)
    private val rateGate = Mutex()
    private var nextRequestNanos = 0L
    private val chapterLocks = Array(8) { Mutex() }
    private data class Catalog(val at: Long, val detail: MangaDetail)
    private data class ChapterImages(var at: Long, val urls: Array<String?>)
    private val catalogs = LinkedHashMap<String, Catalog>()
    private val imageLists = LinkedHashMap<String, ChapterImages>()

    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        checkedPage(page)
        parseMangas(document("$HOME/manga-list-p${page + 1}/"), page)
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(query.trim().length in 1..120) { "请输入完整书名，最多120字。" }
        checkedPage(page)
        val url = "$HOME/search".toHttpUrl().newBuilder()
            .addQueryParameter("title", query.trim()).addQueryParameter("page", (page + 1).toString()).build()
        parseMangas(document(url.toString()), page)
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        loadDetail(nativeManga(mangaId), force = true)
    }

    private fun parseMangas(document: Document, page: Int): MangaPage {
        val documentUrl = document.location().orEmpty().ifBlank { "$HOME/" }
        val container = document.selectFirst(".mh-list") ?: run {
            if (Regex("没有找到|沒有找到|未找到|没有相关|沒有相關").containsMatchIn(document.text()))
                return MangaPage(emptyList(), false)
            throw SourceException("MangaBZ 搜索页面暂不能解析，可能需要原站验证。", documentUrl)
        }
        val mangas = container.children().mapNotNull { row ->
            val anchor = row.selectFirst("a[href]") ?: return@mapNotNull null
            val id = mangaIdFromUrl(documentUrl.toHttpUrl().resolve(anchor.attr("href"))
                ?: throw SourceException("MangaBZ 返回无效作品链接。", HOME))
            val title = row.selectFirst("h2")?.text()?.trim().orEmpty()
            if (title.isBlank()) throw SourceException("MangaBZ 搜索结果缺少书名。", HOME)
            Manga(SourceKeys.manga(info.id, id), title,
                row.selectFirst("img")?.let { image -> imageUrl(document, image.attr("data-src").ifBlank { image.attr("src") }) }.orEmpty(),
                sourceUrl = mangaUrl(id), sourceId = info.id, sourceMangaId = id)
        }.distinctBy(Manga::id)
        val more = document.selectFirst(".page-pagination")?.select("a")?.lastOrNull()?.text()?.trim() == ">"
        return MangaPage(mangas, more, if (more) page + 1 else null)
    }

    private suspend fun loadDetail(id: String, force: Boolean): MangaDetail {
        if (!force) synchronized(mangaBzBodySourceStateLock) {
            catalogs[id]?.takeIf { (monotonicTimeMillis() * 1_000_000L) - it.at in 0L until CATALOG_TTL }?.detail
        }?.let { return it }
        val document = document(mangaUrl(id))
        val title = document.selectFirst(".detail-info-title")?.ownText()?.trim().orEmpty()
        val tip = document.selectFirst(".detail-info-tip")?.children()
        val chapterList = document.selectFirst("#chapterlistload")
        if (title.isBlank() || tip == null || chapterList == null)
            throw SourceException("MangaBZ 目录页面缺少资料，可能需要验证或作品暂不可用。", mangaUrl(id))
        val authors = tip.getOrNull(0)?.children()?.map { it.ownText().trim() }?.filter(String::isNotBlank).orEmpty()
        val tags = tip.getOrNull(2)?.children()?.map { it.ownText().trim() }?.filter(String::isNotBlank).orEmpty()
        val status = tip.getOrNull(1)?.text().orEmpty().let {
            when { it.contains("完结") || it.contains("完結") -> "完结"
                it.contains("连载") || it.contains("連載") -> "连载"; else -> "" }
        }
        val manga = Manga(SourceKeys.manga(info.id, id), title,
            document.selectFirst(".detail-info-cover")?.let { imageUrl(document, it.attr("src")) }.orEmpty(),
            authors = authors, description = document.selectFirst(".detail-info-content")?.text().orEmpty(),
            status = status, tags = tags, sourceUrl = mangaUrl(id), sourceId = info.id, sourceMangaId = id)
        val chapters = chapterList.select("a[href]").reversed().distinctBy { it.attr("href") }.mapIndexed { index, row ->
            val url = document.location().orEmpty().ifBlank { mangaUrl(id) }.toHttpUrl().resolve(row.attr("href"))
                ?: throw SourceException("MangaBZ 章节地址无效。", mangaUrl(id))
            checkedHost(url)
            val chapterId = CHAPTER_PATH.matchEntire(url.encodedPath)?.groupValues?.get(1)
                ?: throw SourceException("MangaBZ 章节编号不兼容。", mangaUrl(id))
            val chapterTitle = row.ownText().trim().ifBlank { row.clone().also { it.select("span").remove() }.text().trim() }
            if (chapterTitle.isBlank()) throw SourceException("MangaBZ 章节缺少标题。", mangaUrl(id))
            val count = PAGE_COUNT.find(row.text())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..MAX_PAGES }
            val volume = VOLUME.containsMatchIn(chapterTitle)
            val special = SPECIAL.containsMatchIn(chapterTitle)
            val edition = (if (volume) "单行本" else "连载版") +
                if (chapterTitle.contains("试看") || chapterTitle.contains("試看")) " · 试看" else ""
            val variant = ChapterVariant(info.id, info.name, id, chapterId, chapterTitle,
                language = info.language, edition = edition, url = "$HOME/m$chapterId/", pageCount = count)
            Chapter(variant.storageId, chapterTitle, index, when { volume -> "单行本"; special -> "番外"; else -> "连载" },
                variants = listOf(variant), sourceId = info.id, sourceMangaId = id, sourceChapterId = chapterId)
        }
        if (chapters.isEmpty()) throw SourceException("MangaBZ 暂无公开可读目录。", mangaUrl(id))
        val result = MangaDetail(manga.copy(latestChapter = chapters.last().title), chapters)
        synchronized(mangaBzBodySourceStateLock) {
            catalogs.remove(id); catalogs[id] = Catalog((monotonicTimeMillis() * 1_000_000L), result)
            while (catalogs.size > 24) catalogs.remove(catalogs.keys.first())
        }
        return result
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        val manga = nativeManga(mangaId)
        require(ref.sourceId == info.id && ref.mangaId == manga && DIGITS.matches(ref.chapterId)) { "MangaBZ 章节与作品来源不一致。" }
        val key = SourceKeys.chapter(info.id, manga, ref.chapterId)
        chapterLocks[(key.hashCode() and Int.MAX_VALUE) % chapterLocks.size].withLock {
            val catalog = loadDetail(manga, forceRefresh)
            val chapter = catalog.chapters.firstOrNull { it.sourceChapterId == ref.chapterId }
                ?: throw SourceException("这个章节不属于当前 MangaBZ 作品，请刷新目录。", mangaUrl(manga))
            val count = chapter.variants.single().pageCount
                ?: throw SourceException("MangaBZ 没有返回本章页数，未生成不完整的图片清单。", "$HOME/m${ref.chapterId}/")
            val now = (monotonicTimeMillis() * 1_000_000L)
            val cached = synchronized(mangaBzBodySourceStateLock) {
                imageLists[key]?.takeUnless { forceRefresh || it.urls.size != count || now - it.at !in 0L until IMAGE_TTL }
            }
            val images = cached ?: ChapterImages(now, arrayOfNulls(count)).also { rememberImages(key, it) }
            // The upstream endpoint returns 2 images (or 15 when its cache is ready).
            // Preserve completed batches on cancellation, but never expose missing pages to the reader.
            while (images.urls.any { it == null }) {
                currentCoroutineContext().ensureActive()
                val first = images.urls.indexOfFirst { it == null }
                val second = (first + 2 until count).firstOrNull { images.urls[it] == null }
                val starts = listOfNotNull(first, second)
                val batches = coroutineScope {
                    starts.map { start -> async { start to imageBatch(ref.chapterId, start + 1) } }.awaitAll()
                }
                for ((start, urls) in batches) {
                    if (urls.isEmpty() || start + urls.size > count)
                        throw SourceException("MangaBZ 图片数量与目录不一致，请刷新本章。", "$HOME/m${ref.chapterId}/")
                    urls.forEachIndexed { offset, url -> images.urls[start + offset] = url }
                }
                images.at = (monotonicTimeMillis() * 1_000_000L)
                rememberImages(key, images)
            }
            images.urls.mapIndexed { index, url -> Page(index, requireNotNull(url)) }
        }
    }

    private suspend fun imageBatch(chapterId: String, page: Int): List<String> {
        val url = "$HOME/m$chapterId/chapterimage.ashx".toHttpUrl().newBuilder()
            .addQueryParameter("cid", chapterId).addQueryParameter("page", page.toString()).build()
        val raw = get(url, "application/javascript,*/*;q=0.8", "$HOME/m$chapterId/")
        val unpacked = try { SourcePackedScript.unpack(raw) } catch (failure: IllegalArgumentException) {
            throw SourceException("MangaBZ 图片资料暂不兼容，请更新来源后重试。", "$HOME/m$chapterId/", cause = failure)
        }
        val prefixLiteral = PREFIX.find(unpacked)?.groupValues?.get(1)
            ?: throw SourceException("MangaBZ 没有返回图片服务器。", "$HOME/m$chapterId/")
        val prefix = JSONTokener(prefixLiteral).nextValue() as? String
            ?: throw SourceException("MangaBZ 图片地址格式无效。", HOME)
        val paths = IMAGE_ARRAY.find(unpacked)?.value?.let(::JSONArray)
            ?: throw SourceException("MangaBZ 没有返回本批图片。", "$HOME/m$chapterId/")
        if (paths.length() !in 1..15) throw SourceException("MangaBZ 图片批次长度不兼容。", HOME)
        return (0 until paths.length()).map { index ->
            val path = paths.getString(index)
            require(path.isNotBlank() && path.length <= 8192) { "MangaBZ 图片路径无效。" }
            checkedImage(prefix + path)
        }
    }

    private fun rememberImages(key: String, images: ChapterImages) = synchronized(mangaBzBodySourceStateLock) {
        imageLists.remove(key); imageLists[key] = images
        while (imageLists.size > 10) imageLists.remove(imageLists.keys.first())
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id && DIGITS.matches(ref.chapterId))
        return mapOf("User-Agent" to USER_AGENT, "Referer" to "$HOME/m${ref.chapterId}/", "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8")
    }

    private suspend fun document(url: String): Document = Jsoup.parse(get(url.toHttpUrl()), url)

    private suspend fun get(url: HttpUrl, accept: String = "text/html,application/xhtml+xml", referer: String = "$HOME/"): String =
        networkSlots.withPermit {
            rateGate.withLock {
                val wait = nextRequestNanos - (monotonicTimeMillis() * 1_000_000L)
                if (wait > 0) delay((wait + 999_999L) / 1_000_000L)
                nextRequestNanos = (monotonicTimeMillis() * 1_000_000L) + 200_000_000L
            }
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Referer", referer)
                .header("Accept", accept).header("Cookie", "mangabz_lang=2").build()
            http.request(request, "MangaBZ", HOME).text
        }

    private fun imageUrl(document: Document, raw: String): String = if (raw.isBlank()) "" else
        checkedImage(document.location().orEmpty().ifBlank { "$HOME/" }.toHttpUrl().resolve(raw)?.toString()
            ?: throw SourceException("MangaBZ 封面地址无效。", HOME))

    private fun checkedImage(value: String): String {
        val url = checkedBodyUrl(if (value.startsWith("//")) "https:$value" else value)
        if (url.encodedPath.endsWith("/404.jpg", true)) throw SourceException("MangaBZ 图片暂不存在。", HOME)
        return url.toString()
    }

    private fun nativeManga(value: String): String {
        val ref = SourceKeys.decodeManga(value)
        require(!SourceKeys.isEncoded(value) || ref.sourceId == info.id) { "作品来源不一致。" }
        return ref.mangaId.also { require(DIGITS.matches(it)) { "MangaBZ 作品编号无效。" } }
    }

    private fun mangaIdFromUrl(url: HttpUrl): String {
        checkedHost(url)
        return MANGA_PATH.matchEntire(url.encodedPath)?.groupValues?.get(1)
            ?: throw SourceException("MangaBZ 作品编号不兼容。", HOME)
    }

    private fun checkedHost(url: HttpUrl) {
        require(url.isHttps && url.host in setOf("mangabz.com", "www.mangabz.com") &&
            url.username.isEmpty() && url.password.isEmpty()) { "MangaBZ 返回了站外地址。" }
    }

    private fun mangaUrl(id: String) = "$HOME/${id}bz/"
    private fun checkedPage(page: Int) = require(page in 0..499) { "MangaBZ 分页超出范围。" }

    companion object {
        private const val HOME = "https://mangabz.com"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:143.0) Gecko/20100101 Firefox/143.0"
        private const val MAX_PAGES = 1200
        private const val CATALOG_TTL = 300_000_000_000L
        private const val IMAGE_TTL = 120_000_000_000L
        private val DIGITS = Regex("[0-9]{1,15}")
        private val MANGA_PATH = Regex("/([0-9]{1,15})bz/?")
        private val CHAPTER_PATH = Regex("/m([0-9]{1,15})/?")
        private val PAGE_COUNT = Regex("[（(]\\s*([0-9]+)\\s*[Pp][)）]")
        private val VOLUME = Regex("第\\s*[0-9]+(?:\\.[0-9]+)?\\s*[卷巻册冊]")
        private val SPECIAL = Regex("番外|特别|特別|外传|外傳|附录|附錄|角色书|角色書|短篇|后记|後記|序章")
        private val PREFIX = Regex("""\bpix\s*=\s*("(?:\\.|[^"\\])*")""")
        private val IMAGE_ARRAY = Regex("""\[\s*"(?:\\.|[^"\\])*"(?:\s*,\s*"(?:\\.|[^"\\])*")*\s*]""")
    }
}

private val mangaBzBodySourceStateLock = SynchronizedObject()
