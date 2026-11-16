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
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.json.JSONTokener
import dev.mangaflow.net.FormBody
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.Request
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Adapted public-page/search/chapterfun parsing from Keiyoushi's Dm5, Apache-2.0:
 * https://github.com/keiyoushi/extensions-source/blob/9080fbfd1d02ce2cad0f7466996fa19816f39401/lib-multisrc/dm5/src/eu/kanade/tachiyomi/multisrc/dm5/Dm5.kt
 * The Manhuaren page fields/endpoints were also checked against venera-configs
 * a04e05591f7832f7166d6660546613c114d6738a/manhuaren.js as a protocol reference.
 * Modified for commonMain, source-qualified identities and bounded data-only unpacking.
 * No account, adult cookie, comments, arbitrary JS execution or optional engine is used.
 */
class ManhuarenBodySource : BodySource {
    override val info = BodySourceInfo("manhuaren", "漫画人", "简中", HOME, "$HOME/favicon.ico",
        revision = "public-web-9080fbfd-1")
    private val http = SourceHttp(sourceId = info.id)
    private val slots = Semaphore(2)
    private val starts = Mutex()
    private var nextStart = 0L

    override suspend fun capabilities() = SourceCapabilities(loginRequired = false, passwordLogin = false)

    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        checkedPage(page)
        val url = "$HOME/manhua-list-s2/dm5.ashx".toHttpUrl()
        val body = FormBody.Builder().add("action", "getclasscomics")
            .add("pageindex", (page + 1).toString()).add("pagesize", LIST_SIZE.toString())
            .add("categoryid", "0").add("tagid", "0").add("status", "0")
            .add("usergroup", "0").add("pay", "-1").add("areaid", "0")
            .add("sort", "2").add("iscopyright", "0").build()
        val request = request(url, "application/json, text/javascript, */*;q=0.01", "$HOME/manhua-list-s2/")
            .header("Origin", HOME).header("X-Requested-With", "XMLHttpRequest").post(body).build()
        val raw = fetch(request)
        val data = try { JSONObject(raw) } catch (failure: Exception) {
            throw SourceException("漫画人目录未返回公开列表，可能需要站点验证。", url.toString(), cause = failure)
        }
        val entries = data.optJSONArray("UpdateComicItems")
            ?: unavailable("目录缺少作品列表", url.toString())
        if (entries.length() > 100) unavailable("目录单页过大", url.toString())
        val values = (0 until entries.length()).map { index ->
            val row = entries.getJSONObject(index)
            val key = row.bodyText("UrlKey")
            if (key.isBlank()) unavailable("作品缺少公开地址", url.toString())
            val id = mangaIdFromLink("/${key.trim('/')}/", HOME)
            val title = row.bodyText("Title").ifBlank { unavailable("作品缺少标题", url.toString()) }
            val authors = row.optJSONArray("Author")?.let { array ->
                (0 until array.length()).map { array.optString(it).trim() }.filter(String::isNotBlank)
            }.orEmpty()
            Manga(SourceKeys.manga(info.id, id), title,
                imageUrl(row.bodyText("ShowPicUrlB").ifBlank { row.bodyText("ShowConver") }, HOME),
                authors = authors, description = row.bodyText("Content"),
                sourceUrl = mangaUrl(id), sourceId = info.id, sourceMangaId = id, sourceName = info.name)
        }.distinctBy(Manga::id)
        val total = data.optLong("Count", -1L)
        val more = if (total >= 0L) (page.toLong() + 1L) * LIST_SIZE < total else entries.length() == LIST_SIZE
        if (values.isEmpty() && (more || total > page.toLong() * LIST_SIZE)) unavailable("目录分页资料不完整", url.toString())
        MangaPage(values, more && page < MAX_BROWSE_PAGE, if (more && page < MAX_BROWSE_PAGE) page + 1 else null)
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(query.trim().length in 1..120) { "请输入1至120个字符。" }
        checkedPage(page)
        val url = "$HOME/search".toHttpUrl().newBuilder().addQueryParameter("title", query.trim())
            .addQueryParameter("language", "1").addQueryParameter("page", (page + 1).toString()).build()
        val document = document(url)
        val rows = document.select(".book-list > li, ul.mh-list > li, div.banner_detail_form")
        if (rows.isEmpty() && document.selectFirst(".book-list, ul.mh-list") == null &&
            !NO_RESULTS.containsMatchIn(document.text())) unavailable("搜索没有返回可识别的结果", url.toString())
        val mangas = rows.map { row ->
            val anchor = row.selectFirst(".book-list-info > a, .title > a")
                ?: unavailable("搜索结果缺少作品地址", url.toString())
            val id = mangaIdFromLink(anchor.attr("href"), url.toString())
            val title = row.selectFirst(".book-list-info-title")?.text()?.trim()
                .orEmpty().ifBlank { anchor.attr("title").ifBlank { anchor.text() }.trim() }
            if (title.isBlank()) unavailable("搜索结果缺少标题", url.toString())
            val image = row.selectFirst(".book-list-cover-img, img")
            val cover = image?.attr("data-src")?.ifBlank { image.attr("src") }.orEmpty()
            Manga(SourceKeys.manga(info.id, id), title, imageUrl(cover, url.toString()),
                description = row.selectFirst(".book-list-info-desc")?.text().orEmpty(),
                tags = row.select(".book-list-info-bottom-item").map { it.text().trim() }.filter(String::isNotBlank),
                status = row.selectFirst(".book-list-info-bottom-right-font")?.text().orEmpty(),
                sourceUrl = mangaUrl(id), sourceId = info.id, sourceMangaId = id, sourceName = info.name)
        }.distinctBy(Manga::id)
        // The public search contract ends on an empty page when no next-page control is rendered.
        val pagination = document.selectFirst(".page-pagination")
        val more = page < MAX_BROWSE_PAGE && if (pagination != null)
            pagination.select("a").any { it.text().trim() in setOf(">", "下一页", "下一頁") || it.attr("rel") == "next" }
        else mangas.isNotEmpty()
        MangaPage(mangas, more, if (more) page + 1 else null)
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        catalog(nativeManga(mangaId)).detail
    }

    private data class Catalog(val detail: MangaDetail, val numericId: String?)

    private suspend fun catalog(id: String, fresh: Boolean = false): Catalog {
        val url = mangaUrl(id)
        val doc = document(url.toHttpUrl(), fresh)
        doc.selectFirst("link[rel=canonical]")?.attr("href")?.takeIf(String::isNotBlank)?.let {
            if (mangaIdFromLink(it, url) != id) unavailable("详情页与当前作品不一致", url)
        }
        val title = doc.selectFirst("p.detail-main-info-title, .banner_detail_form p.title")?.ownText()?.trim().orEmpty()
        if (title.isBlank()) unavailable("详情缺少作品标题", url)
        val cover = doc.selectFirst(".detail-main-cover img, .banner_detail_form img")
        val authors = doc.select(".detail-main-info-author a, .banner_detail_form p.subtitle > a")
            .map { it.text().trim() }.filter(String::isNotBlank).distinct()
        val manga = Manga(SourceKeys.manga(info.id, id), title,
            imageUrl(cover?.attr("data-src")?.ifBlank { cover.attr("src") }.orEmpty(), url),
            authors = authors,
            description = doc.selectFirst(".detail-desc, .banner_detail_form p.content")?.text().orEmpty(),
            status = doc.selectFirst(".detail-list-title-1, .banner_detail_form p.tip > span > span")?.text().orEmpty(),
            tags = doc.select(".detail-main-info-class a, .banner_detail_form p.tip a")
                .map { it.text().trim() }.filter(String::isNotBlank).distinct(),
            sourceUrl = url, sourceId = info.id, sourceMangaId = id, sourceName = info.name)
        doc.selectFirst(".warning-bar")?.text()?.takeIf(String::isNotBlank)?.let {
            throw SourceException("漫画人要求先在原站确认访问条件：${it.take(240)}", url)
        }
        val groups = linkedMapOf<String, Pair<String, List<Element>>>()
        doc.select(".detail-selector .detail-selector-item").forEach { selector ->
            val name = selector.text().trim()
            if (name.isBlank() || name.contains("评论") || name.contains("評論")) return@forEach
            val targetId = GROUP_TARGET.find(selector.attr("onclick"))?.groupValues?.get(1) ?: return@forEach
            val target = doc.getElementById(targetId) ?: unavailable("章节分组尚未完整返回", url)
            val rows = target.select("a.chapteritem[href]").toList()
            val declaredSize = GROUP_COUNT.find(name)?.groupValues?.get(1)?.toIntOrNull()
            if (rows.isEmpty() && declaredSize != 0) unavailable("章节分组没有完整返回公开目录", url)
            if (declaredSize != null && rows.map { chapterIdFromLink(it.attr("href"), url) }.distinct().size != declaredSize)
                unavailable("章节分组数量与来源声明不一致，未返回残缺目录", url)
            groups[targetId] = name to rows
        }
        if (groups.isEmpty()) {
            val modern = doc.select("a.chapteritem[href]")
            if (modern.isNotEmpty()) groups["public"] = "连载" to modern.toList()
            else {
                val lists = doc.select("#chapterlistload > ul")
                val names = doc.select(".detail-list-title > a.block").map { it.text().substringBefore('（').trim() }
                lists.forEachIndexed { index, list ->
                    groups["legacy-$index"] = names.getOrNull(index).orEmpty().ifBlank { "连载" } to list.select("li > a[href]").toList()
                }
            }
        }
        val ascending = doc.selectFirst("div.detail-list-title a.order")?.text()?.trim() == "正序"
        val chapters = linkedMapOf<String, Chapter>()
        for ((_, section) in groups) {
            val (group, rows) = section
            for (row in if (ascending) rows else rows.reversed()) {
                val chapter = chapterIdFromLink(row.attr("href"), url)
                val titleValue = row.selectFirst("p.title")?.text()?.trim().orEmpty()
                    .ifBlank { row.attr("title").ifBlank { row.text() }.trim() }
                if (titleValue.isBlank()) unavailable("章节缺少标题", url)
                val volume = VOLUME.containsMatchIn(titleValue) || VOLUME_GROUP.containsMatchIn(group)
                val edition = if (volume) group.takeUnless { it == "连载" } ?: "单行本" else group + "版"
                val count = PAGE_COUNT.find(row.text())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..MAX_PAGES }
                val variant = ChapterVariant(info.id, info.name, id, chapter, titleValue, info.language,
                    edition = edition, url = chapterUrl(chapter), pageCount = count)
                val previous = chapters[chapter]
                if (previous != null) {
                    if (previous.title != titleValue) unavailable("同一原生章节返回了不同标题，请刷新目录", url)
                    continue
                }
                chapters[chapter] = Chapter(variant.storageId, titleValue, chapters.size, group,
                    variants = listOf(variant), sourceId = info.id, sourceMangaId = id, sourceChapterId = chapter)
                if (chapters.size > MAX_CHAPTERS) unavailable("章节目录超过支持范围", url)
            }
        }
        if (chapters.isEmpty()) unavailable("没有返回公开章节，可能需要原站确认访问条件", url)
        if (doc.select("a.chapteritem[href]").any { chapterIdFromLink(it.attr("href"), url) !in chapters })
            unavailable("有原生章节未能归入完整分组，请刷新目录", url)
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        return Catalog(MangaDetail(manga, chapters.values.toList()), number(scripts, "DM5_MID", "COMIC_MID", "DM5_COMIC_MID"))
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val id = nativeManga(mangaId)
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id && ref.mangaId == id && DIGITS.matches(ref.chapterId)) { "漫画人章节与作品来源不一致。" }
        // Verify the native catalogue; no extra hidden TTL or cross-source chapter-number guess.
        val catalog = catalog(id, forceRefresh)
        val chapter = catalog.detail.chapters.firstOrNull { it.sourceChapterId == ref.chapterId }
            ?: unavailable("当前作品目录中没有这个原生章节，请刷新目录", mangaUrl(id))
        val url = chapterUrl(ref.chapterId)
        val doc = document(url.toHttpUrl(), forceRefresh)
        val scripts = doc.select("script").map { it.data() }
        val metadata = scripts.joinToString("\n")
        number(metadata, "DM5_CID")?.let { if (it != ref.chapterId) unavailable("阅读页与原生章节不一致", url) }
        number(metadata, "DM5_MID", "COMIC_MID")?.let { returned ->
            if (catalog.numericId != null && returned != catalog.numericId) unavailable("阅读页与当前作品不一致", url)
        }
        val declaredCount = number(metadata, "DM5_IMAGE_COUNT")?.toIntOrNull()
            ?: chapter.variants.single().pageCount
        if (declaredCount != null && declaredCount !in 1..MAX_PAGES) unavailable("章节没有有效的公开图片页数", url)
        val displayed = doc.select("div#barChapter > img.load-src[data-src]")
        val imageUrls = fullImageArray(scripts, url) ?: if (declaredCount != null && displayed.size == declaredCount)
            displayed.map { imageUrl(it.attr("data-src"), url) }
            else chapterImages(ref.chapterId, metadata, declaredCount, url, forceRefresh)
        if (imageUrls.size !in 1..MAX_PAGES || imageUrls.any(String::isBlank)) unavailable("没有返回完整的公开章节图片", url)
        if (declaredCount != null && imageUrls.size != declaredCount) unavailable("图片清单与来源声明的页数不一致，未返回残缺章节", url)
        val headers = imageHeaders(mangaId, chapterId)
        imageUrls.mapIndexed { index, image -> Page(index, image, headers = headers, sourceRevision = info.revision) }
    }

    private fun fullImageArray(scripts: List<String>, url: String): List<String>? {
        val found = mutableListOf<List<String>>()
        for (script in scripts.filter { it.contains("eval(function") }.take(16)) {
            val unpacked = try { SourcePackedScript.unpack(script) } catch (_: IllegalArgumentException) { continue }
            for (match in IMAGE_ARRAY.findAll(unpacked).take(16)) {
                val array = runCatching { JSONTokener(match.value).nextValue() as? JSONArray }.getOrNull() ?: continue
                if (array.length() !in 1..MAX_PAGES) continue
                val values = (0 until array.length()).mapNotNull { array.opt(it) as? String }
                if (values.size != array.length()) continue
                if (values.all { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("//") })
                    found += values.map { imageUrl(it, url) }
            }
        }
        val distinct = found.distinct()
        if (distinct.size > 1) unavailable("阅读页包含多个不一致的图片清单，未猜测正文", url)
        return distinct.singleOrNull()
    }

    private suspend fun chapterImages(chapter: String, metadata: String, count: Int?, url: String, fresh: Boolean): List<String> {
        val mid = number(metadata, "DM5_MID")
        val sign = literal(metadata, "DM5_VIEWSIGN")
        val at = literal(metadata, "DM5_VIEWSIGN_DT")
        if (mid == null || sign == null || at == null || count == null)
            throw SourceException("漫画人未提供公开图片资料；付费、登录受限或站点验证章节不能匿名读取。", url)
        val result = ArrayList<String>(count)
        var first = 1
        while (first <= count) {
            val end = minOf(count, first + 1)
            result += coroutineScope {
                (first..end).map { page -> async {
                    val endpoint = url + "chapterfun.ashx"
                    val target = endpoint.toHttpUrl().newBuilder().addQueryParameter("cid", chapter)
                        .addQueryParameter("page", page.toString()).addQueryParameter("key", "")
                        .addQueryParameter("language", "1").addQueryParameter("gtk", "6")
                        .addQueryParameter("_cid", chapter).addQueryParameter("_mid", mid)
                        .addQueryParameter("_dt", at).addQueryParameter("_sign", sign).build()
                    val raw = fetch(request(target, "application/javascript,*/*;q=0.8", url)
                        .apply { if (fresh) header("Cache-Control", "no-cache") }.build())
                    val unpacked = try { SourcePackedScript.unpack(raw) } catch (failure: IllegalArgumentException) {
                        throw SourceException("漫画人本页公开图片资料暂不兼容。", url, cause = failure)
                    }
                    val prefix = literal(unpacked, "pix") ?: unavailable("本页缺少图片服务器", url)
                    val values = VALUE_ARRAY.find(unpacked)?.groupValues?.get(1)?.let {
                        runCatching { JSONTokener(it).nextValue() as? JSONArray }.getOrNull()
                    } ?: unavailable("本页缺少图片路径", url)
                    if (values.length() !in 1..16) unavailable("本页图片路径不完整", url)
                    val path = values.opt(0) as? String ?: unavailable("本页图片路径无效", url)
                    val suffix = IMAGE_SUFFIX.find(unpacked)?.groupValues?.get(1)?.let(::stringLiteral)
                        ?: unavailable("本页图片签名格式不兼容", url)
                    imageUrl(prefix + path + suffix, url)
                } }.awaitAll()
            }
            first = end + 1
        }
        return result
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        val owner = nativeManga(mangaId)
        if (chapterId.isBlank()) return mapOf("User-Agent" to USER_AGENT, "Referer" to mangaUrl(owner),
            "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8")
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id && ref.mangaId == owner && DIGITS.matches(ref.chapterId)) { "漫画人图片来源不一致。" }
        return mapOf("User-Agent" to USER_AGENT, "Referer" to chapterUrl(ref.chapterId), "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8")
    }

    private suspend fun document(url: HttpUrl, fresh: Boolean = false): Document {
        val doc = Ksoup.parse(fetch(request(url).apply { if (fresh) header("Cache-Control", "no-cache") }.build()), url.toString())
        if (doc.selectFirst("#challenge-form, #cf-chl-widget, input[name=captcha]") != null ||
            CHALLENGE.containsMatchIn(doc.title())) unavailable("需要先完成原站人机验证，匿名适配器不会自动处理验证", url.toString())
        return doc
    }

    private fun request(url: HttpUrl, accept: String = "text/html,application/xhtml+xml", referer: String = "$HOME/"): Request.Builder {
        checkedHost(url)
        return Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9").header("Referer", referer)
    }

    private suspend fun fetch(request: Request): String = slots.withPermit {
        starts.withLock {
            val wait = nextStart - monotonicTimeMillis()
            if (wait > 0L) delay(wait)
            nextStart = monotonicTimeMillis() + 200L
        }
        http.request(request, info.name, HOME).text
    }

    private fun nativeManga(value: String): String {
        val ref = SourceKeys.decodeManga(value)
        require(!SourceKeys.isEncoded(value) || ref.sourceId == info.id) { "漫画人作品来源不一致。" }
        require(MANGA_ID.matches(ref.mangaId)) { "漫画人作品编号无效。" }
        return ref.mangaId
    }

    private fun mangaIdFromLink(value: String, base: String): String {
        val target = siteTarget(value, base)
        return target.encodedPath.trim('/').also { if (!MANGA_ID.matches(it)) unavailable("公开作品地址格式不兼容", base) }
    }

    private fun chapterIdFromLink(value: String, base: String): String {
        val target = siteTarget(value, base)
        return CHAPTER_PATH.matchEntire(target.encodedPath)?.groupValues?.get(1) ?: unavailable("原生章节地址格式不兼容", base)
    }

    private fun siteTarget(value: String, base: String): HttpUrl {
        val resolved = base.toHttpUrl().resolve(value) ?: unavailable("站点地址无效", base)
        val secure = if (resolved.scheme == "http" && resolved.port == 80)
            resolved.newBuilder().scheme("https").port(443).build() else resolved
        checkedHost(secure)
        return secure
    }

    private fun imageUrl(value: String, base: String): String {
        if (value.isBlank()) return ""
        val resolved = base.toHttpUrl().resolve(value.trim()) ?: unavailable("图片地址无效", base)
        val secure = if (resolved.scheme == "http" && resolved.port == 80)
            resolved.newBuilder().scheme("https").port(443).build() else resolved
        if (!secure.isHttps || secure.username.isNotEmpty() || secure.password.isNotEmpty()) unavailable("图片没有提供有效的HTTPS地址", base)
        return secure.toString()
    }

    private fun checkedHost(url: HttpUrl) {
        require(url.isHttps && url.host in setOf("www.manhuaren.com", "manhuaren.com") &&
            url.username.isEmpty() && url.password.isEmpty()) { "漫画人返回了站外或非HTTPS页面。" }
    }

    private fun number(script: String, vararg names: String): String? {
        for (name in names) {
            val value = Regex("""\b${Regex.escape(name)}\s*=\s*["']?([0-9]{1,15})["']?\s*;""").find(script)?.groupValues?.get(1)
            if (value != null) return value
        }
        return null
    }

    private fun literal(script: String, name: String): String? = Regex("""\b${Regex.escape(name)}\s*=\s*($JS_STRING)""")
        .find(script)?.groupValues?.get(1)?.let(::stringLiteral)
    private fun stringLiteral(value: String): String? = runCatching { JSONTokener(value).nextValue() as? String }.getOrNull()
    private fun mangaUrl(id: String): String = "$HOME/$id/"
    private fun chapterUrl(id: String): String = "$HOME/m$id/"
    private fun checkedPage(page: Int) = require(page in 0..MAX_BROWSE_PAGE) { "漫画人分页超出范围。" }
    private fun unavailable(message: String, url: String): Nothing = throw SourceException("漫画人$message。", url)

    private companion object {
        const val HOME = "https://www.manhuaren.com"
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36"
        const val LIST_SIZE = 21
        const val MAX_BROWSE_PAGE = 499
        const val MAX_CHAPTERS = 20_000
        const val MAX_PAGES = 1200
        const val JS_STRING = "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"
        val MANGA_ID = Regex("manhua-[A-Za-z0-9_%.-]{1,240}")
        val DIGITS = Regex("[0-9]{1,15}")
        val CHAPTER_PATH = Regex("/m([0-9]{1,15})/?")
        val GROUP_TARGET = Regex("""titleSelect\([^,]*,[^,]*,\s*['"]([^'"]+)['"]""")
        val GROUP_COUNT = Regex("[（(]\\s*([0-9]+)\\s*[)）]")
        val VOLUME = Regex("(?:第\\s*)?[0-9]+(?:\\.[0-9]+)?\\s*[卷巻册冊]")
        val VOLUME_GROUP = Regex("单行本|單行本|卷版|卷册|卷冊")
        val PAGE_COUNT = Regex("[（(]\\s*([0-9]+)\\s*[pP]\\s*[)）]")
        val NO_RESULTS = Regex("没有找到|沒有找到|未找到|没有相关|沒有相關|暂无相关|暫無相關")
        val CHALLENGE = Regex("Just a moment|Checking your browser|人机验证|人機驗證|验证码|驗證碼", RegexOption.IGNORE_CASE)
        val IMAGE_ARRAY = Regex("""\[\s*(?:$JS_STRING)(?:\s*,\s*(?:$JS_STRING))*\s*]""")
        val VALUE_ARRAY = Regex("""\bpvalue\s*=\s*(\[\s*(?:$JS_STRING)(?:\s*,\s*(?:$JS_STRING))*\s*])""")
        val IMAGE_SUFFIX = Regex("""\bpix\s*\+\s*pvalue\[[^]]+]\s*\+\s*($JS_STRING)""")
    }
}
