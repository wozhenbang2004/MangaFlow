package dev.mangaflow.data.sources

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
 * Modified from Keiyoushi Zaimanhua.kt / ZaimanhuaDto.kt at
 * 9080fbfd1d02ce2cad0f7466996fa19816f39401 (Apache-2.0).
 * https://github.com/keiyoushi/extensions-source/tree/9080fbfd1d02ce2cad0f7466996fa19816f39401/src/zh/zaimanhua
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Adapted to common Kotlin, source-owned routing and explicit native identities. Public
 * search/catalogue/images only: no account, authorization, subscription check or login retry.
 * Hidden/restricted catalogues fail explicitly; source-local HD originals are kept unchanged.
 */
class ZaimanhuaBodySource : BodySource {
    override val info = BodySourceInfo("zaimanhua", "再漫画", "简中", HOME, "$HOME/favicon.ico",
        revision = "keiyoushi-9080fbf-anonymous-1")
    private val http = SourceHttp(sourceId = info.id)
    private val requests = Semaphore(2)
    private val starts = Mutex()
    private var nextStartAt = 0L

    override suspend fun capabilities() = SourceCapabilities(loginRequired = false, passwordLogin = false)

    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        checkedPage(page)
        val url = "$API/comic/update/list/0/${page + 1}".toHttpUrl()
        val response = api(url)
        val array = response.optJSONArray("data") ?: incompatible("更新列表结构已变化", url)
        val items = list(array, url)
        // This endpoint supplies no total/cursor; an empty next page is its actual terminator.
        MangaPage(items, items.isNotEmpty(), if (items.isNotEmpty()) page + 1 else null)
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(query.trim().length in 1..120) { "请输入1至120个字符。" }
        checkedPage(page)
        val upstreamPage = page + 1
        val url = "$API/search/index".toHttpUrl().newBuilder()
            .addQueryParameter("source", "0").addQueryParameter("size", PAGE_SIZE.toString())
            .addQueryParameter("keyword", query.trim()).addQueryParameter("page", upstreamPage.toString()).build()
        val data = api(url).optJSONObject("data") ?: incompatible("搜索结果结构已变化", url)
        val total = data.optLong("total", -1L)
        if (total < 0L) incompatible("搜索缺少结果数量", url)
        val rows = data.optJSONArray("list") ?: if (total == 0L) JSONArray()
            else incompatible("搜索缺少作品列表", url)
        val items = list(rows, url)
        val returnedPage = data.optInt("page", upstreamPage)
        val size = data.optInt("size", PAGE_SIZE)
        if (returnedPage != upstreamPage || size !in 1..MAX_LIST_ITEMS)
            incompatible("搜索分页与请求不一致", url)
        if (items.isEmpty() && (upstreamPage - 1L) * size < total)
            incompatible("来源没有返回应有的搜索结果", url)
        val more = items.isNotEmpty() && upstreamPage.toLong() * size < total
        MangaPage(items, more, if (more) page + 1 else null)
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val native = nativeMangaId(mangaId)
        val url = "$API/comic/detail/$native".toHttpUrl().newBuilder()
            .addQueryParameter("_v", API_VERSION).build()
        // The current upstream uses the pc platform's complete grouped catalogue. It is
        // still the public app endpoint; no authenticated pc fallback is attempted.
        val data = unwrap(api(url, platform = "pc"), url)
        if (number(data, "id") != native) incompatible("详情作品归属与请求不一致", url)
        if (data.optInt("isHideChapter") == 1)
            throw SourceException("再漫画隐藏了本作品的章节目录，匿名访问不能确认完整目录。", detailUrl(native))
        if (data.has("canRead") && !data.isNull("canRead") && !data.optBoolean("canRead"))
            throw SourceException("再漫画限制了本作品的匿名访问，请查看原站权限说明。", detailUrl(native))
        val groups = data.optJSONArray("chapters") ?: data.optJSONArray("chapterList")
            ?: incompatible("未返回公开章节目录，可能需要原站权限", url)
        if (groups.length() !in 1..MAX_GROUPS) incompatible("公开章节目录为空或分组过多", url)
        val chapters = mutableListOf<Chapter>()
        val seen = mutableSetOf<String>()
        for (groupIndex in 0 until groups.length()) {
            val group = groups.optJSONObject(groupIndex) ?: incompatible("章节分组格式已变化", url)
            val title = group.bodyText("title").ifBlank { "连载" }
            val rows = group.optJSONArray("data") ?: incompatible("章节分组缺少目录", url)
            if (chapters.size + rows.length() > MAX_CHAPTERS) incompatible("章节数量超过支持范围", url)
            // The public API returns newest first inside each edition. Keep editions separate.
            for (index in rows.length() - 1 downTo 0) {
                val row = rows.optJSONObject(index) ?: incompatible("章节资料格式已变化", url)
                val chapter = number(row, "chapter_id")
                if (!seen.add(chapter)) incompatible("章节编号在多个分组中重复", url)
                val name = row.bodyText("chapter_title").takeIf { it.isNotBlank() && it.length <= 300 }
                    ?: incompatible("章节标题缺失", url)
                val variant = ChapterVariant(info.id, info.name, native, chapter, name, info.language,
                    edition = title, url = chapterUrl(native, chapter))
                chapters += Chapter(variant.storageId, name, chapters.size, group = title,
                    variants = listOf(variant), sourceId = info.id, sourceMangaId = native,
                    sourceChapterId = chapter)
            }
        }
        if (chapters.isEmpty()) incompatible("公开章节目录为空，未作为完整作品返回", url)
        MangaDetail(manga(data, native, url), chapters)
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val owner = nativeMangaId(mangaId)
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id && id(ref.mangaId) == owner) { "章节和再漫画作品归属不一致。" }
        val nativeChapter = id(ref.chapterId)
        val url = "$API/comic/chapter/$owner/$nativeChapter".toHttpUrl().newBuilder()
            .addQueryParameter("_v", API_VERSION).build()
        val data = unwrap(api(url, platform = "h5", fresh = forceRefresh), url)
        if (!data.has("canRead") || data.isNull("canRead"))
            incompatible("正文缺少阅读权限状态", url)
        if (!data.optBoolean("canRead")) throw SourceException(
            "再漫画本章不允许匿名阅读，请查看原站等级或章节权限说明。", chapterUrl(owner, nativeChapter))
        data.opt("comic_id")?.takeIf { it !== JSONObject.NULL }?.let {
            if (number(data, "comic_id") != owner) incompatible("正文作品归属不一致", url)
        }
        data.opt("chapter_id")?.takeIf { it !== JSONObject.NULL }?.let {
            if (number(data, "chapter_id") != nativeChapter) incompatible("正文章节归属不一致", url)
        }
        val hd = data.optJSONArray("page_url_hd")
        val images = hd?.takeIf { it.length() > 0 } ?: data.optJSONArray("page_url")
            ?: incompatible("未取得本章公开原图", url)
        if (images.length() !in 1..MAX_IMAGES) incompatible("本章原图为空或页数超过支持范围", url)
        (0 until images.length()).map { index ->
            val raw = images.opt(index) as? String ?: incompatible("正文图片地址格式已变化", url)
            Page(index, imageUrl(raw, url), sourceRevision = info.revision)
        }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        val owner = nativeMangaId(mangaId)
        val referer = if (chapterId.isBlank()) detailUrl(owner) else {
            val ref = SourceKeys.decodeChapter(mangaId, chapterId)
            require(ref.sourceId == info.id && id(ref.mangaId) == owner) { "图片和再漫画章节归属不一致。" }
            chapterUrl(owner, id(ref.chapterId))
        }
        return mapOf("User-Agent" to USER_AGENT, "Referer" to referer,
            "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8")
    }

    private suspend fun api(url: HttpUrl, platform: String? = null, fresh: Boolean = false): JSONObject = requests.withPermit {
        starts.withLock {
            val now = monotonicTimeMillis()
            if (nextStartAt > now) delay(nextStartAt - now)
            nextStartAt = monotonicTimeMillis() + 200L
        }
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            .header("Accept", "application/json").header("Referer", "$MOBILE/")
        if (platform != null) request.header("Platform", platform)
        if (fresh) request.header("Cache-Control", "no-cache")
        val result = http.bodyJson(request.build(), "再漫画", url.toString())
        val message = result.bodyText("errmsg")
        if (result.optInt("errno") != 0 || message.isNotBlank()) throw SourceException(
            "再漫画返回：" + message.take(220).ifBlank { "公开接口拒绝本次请求（${result.optInt("errno")}）" },
            url.toString())
        result
    }

    private fun unwrap(response: JSONObject, url: HttpUrl): JSONObject {
        val data = response.optJSONObject("data") ?: incompatible("来源返回资料不完整", url)
        return data.optJSONObject("data") ?: data.optJSONObject("comicInfo")
            ?: incompatible("来源资料结构已变化", url)
    }

    private fun list(rows: JSONArray, url: HttpUrl): List<Manga> {
        if (rows.length() > MAX_LIST_ITEMS) incompatible("列表作品数量超过支持范围", url)
        return (0 until rows.length()).map { index ->
            val row = rows.optJSONObject(index) ?: incompatible("作品列表格式已变化", url)
            val native = sequenceOf("comic_id", "id").mapNotNull { name ->
                row.opt(name)?.takeIf { it !== JSONObject.NULL }?.toString()?.takeIf { it != "0" && it.isNotBlank() }
            }.firstOrNull()?.let(::id) ?: incompatible("作品编号缺失", url)
            manga(row, native, url)
        }.distinctBy(Manga::id)
    }

    private fun manga(row: JSONObject, native: String, url: HttpUrl): Manga {
        val title = row.bodyText("title").ifBlank { row.bodyText("name") }
        if (title.isBlank() || title.length > 500) incompatible("作品标题缺失", url)
        val authors = names(row, "authors")
        val status = names(row, "status").firstOrNull().orEmpty()
        val tags = names(row, "types")
        val cover = row.bodyText("cover").takeIf(String::isNotBlank)?.let { imageUrl(it, url) }.orEmpty()
        return Manga(SourceKeys.manga(info.id, native), title, cover, authors = authors,
            description = row.bodyText("description"), status = status, tags = tags,
            latestChapter = row.bodyText("last_update_chapter_name").ifBlank { row.bodyText("last_name") },
            sourceUrl = detailUrl(native), sourceId = info.id, sourceMangaId = native, sourceName = info.name)
    }

    private fun names(row: JSONObject, field: String): List<String> {
        val array = row.optJSONArray(field)
        if (array != null) {
            require(array.length() <= 200) { "再漫画标签资料过多。" }
            return (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.bodyText("tag_name")?.takeIf(String::isNotBlank)
            }.distinct()
        }
        return row.bodyText(field).split('/', '|', '、', '，', ',').map(String::trim).filter(String::isNotBlank).distinct()
    }

    private fun nativeMangaId(value: String): String {
        if (!SourceKeys.isEncoded(value)) return id(value)
        val ref = SourceKeys.decodeManga(value)
        require(ref.sourceId == info.id) { "作品和再漫画来源不一致。" }
        return id(ref.mangaId)
    }

    private fun number(row: JSONObject, name: String): String = id(row.bodyId(name))
    private fun id(value: String): String = value.also {
        require(it.matches(Regex("[1-9][0-9]{0,29}"))) { "再漫画原始编号无效。" }
    }
    private fun checkedPage(page: Int) { require(page in 0..9_999) { "再漫画分页超出支持范围。" } }
    private fun detailUrl(id: String) = "$MOBILE/pages/comic/detail?id=$id"
    private fun chapterUrl(id: String, chapter: String) = "$MOBILE/pages/comic/page?comic_id=$id&chapter_id=$chapter"
    private fun imageUrl(value: String, context: HttpUrl): String {
        val raw = value.trim().let { if (it.startsWith("//")) "https:$it" else it }
        return try { checkedBodyUrl(raw).toString() }
        catch (_: Exception) { incompatible("来源返回的原图地址不是有效的HTTPS地址", context) }
    }
    private fun incompatible(reason: String, url: HttpUrl): Nothing = throw SourceException("再漫画：$reason。", url.toString())

    private companion object {
        const val HOME = "https://manhua.zaimanhua.com"
        const val MOBILE = "https://m.zaimanhua.com"
        const val API = "https://v4api.zaimanhua.com/app/v1"
        const val API_VERSION = "2.2.5"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Mobile"
        const val PAGE_SIZE = 20
        const val MAX_LIST_ITEMS = 200
        const val MAX_GROUPS = 100
        const val MAX_CHAPTERS = 40_000
        const val MAX_IMAGES = 10_000
    }
}
