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
import dev.mangaflow.json.JSONObject
import dev.mangaflow.json.JSONArray
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.MediaType.Companion.toMediaType
import dev.mangaflow.net.Request
import dev.mangaflow.net.RequestBody.Companion.toRequestBody
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.hmacSha256
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.deleteSecret
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * Built-in PicACG source: no imported script, shared album/reader IDs, native account session.
 * Protocol shapes checked against l2studio/picacomic-api (Apache-2.0) and niuhuan/pica-rust (MIT).
 * Access permissions remain controlled by the upstream service.
 */
class PicacgBodySource(private val context: PlatformContext) : BodySource {
    override val info = BodySourceInfo("picacg", "哔咔", "简中", HOME, "", adult = true)
    private val network = SourceHttp()
    private val accountLock = Mutex()
    @Volatile private var session: String = readSecret(context, SECRET_KEY).orEmpty()
    private val _signedIn = MutableStateFlow(session.isNotBlank())
    val signedIn = _signedIn.asStateFlow()

    override suspend fun login(username: String, password: String): Unit = accountLock.withLock {
        require(username.trim().isNotEmpty() && password.isNotBlank()) { "请输入哔咔账号和密码。" }
        require(username.length <= 160 && password.length <= 512) { "账号输入长度无效。" }
        val payload = JSONObject().put("email", username.trim()).put("password", password)
        val token = call("auth/sign-in", "POST", payload, requireLogin = false)
            .optJSONObject("data")?.bodyText("token").orEmpty()
        require(token.length in 8..8192) { "登录接口没有返回有效会话。" }
        writeSecret(context, SECRET_KEY, token)
        session = token
        _signedIn.value = true
    }

    override suspend fun logout() = accountLock.withLock {
        deleteSecret(context, SECRET_KEY)
        session = ""
        _signedIn.value = false
    }

    override suspend fun capabilities() = SourceCapabilities(setOf(SourceFeature.DISCOVERY, SourceFeature.SEARCH,
        SourceFeature.CATEGORIES, SourceFeature.RANKING, SourceFeature.ACCOUNT, SourceFeature.FAVORITES,
        SourceFeature.COMMENTS), loginRequired = true)

    override suspend fun accountState() = SourceAccountState(_signedIn.value)

    override suspend fun feeds(): List<SourceFeed> = withContext(Dispatchers.IO) {
        val categories = call("categories").getJSONObject("data").getJSONArray("categories")
        listOf(SourceFeed("latest", "最近更新", SourceFeedKind.LATEST, listOf(sortFilter)),
            SourceFeed("ranking", "热榜", SourceFeedKind.RANKING, listOf(SourceFilter("period", "时间",
                listOf(SourceChoice("H24", "日榜"), SourceChoice("D7", "周榜"), SourceChoice("D30", "月榜")), "H24")))) +
            (0 until minOf(categories.length(), 200)).mapNotNull { index ->
                val category = categories.getJSONObject(index)
                val title = category.bodyText("title")
                if (title.isBlank() || category.optBoolean("isWeb", false)) null
                else SourceFeed("category:$title", title, SourceFeedKind.CATEGORY, listOf(sortFilter))
            }.distinctBy(SourceFeed::id)
    }

    override suspend fun browse(request: SourceBrowseRequest): MangaPage = withContext(Dispatchers.IO) {
        checkPage(request.page)
        require(request.options.size <= 1) { "哔咔浏览条件无效。" }
        if (request.feedId == "ranking") {
            if (request.page != 0) return@withContext MangaPage(emptyList(), false)
            val period = request.options.firstOrNull().orEmpty().ifBlank { "H24" }
            require(period in setOf("H24", "D7", "D30")) { "哔咔排行时间无效。" }
            val rows = call("comics/leaderboard?tt=$period&ct=VC").getJSONObject("data").getJSONArray("comics")
            return@withContext MangaPage(rows.bodyObjects().map(::toManga).distinctBy(Manga::id), false)
        }
        val sort = checkedSort(request.options)
        val builder = "$API/comics".toHttpUrl().newBuilder()
            .addQueryParameter("page", (request.page + 1).toString()).addQueryParameter("s", sort)
        if (request.feedId.startsWith("category:")) {
            val category = request.feedId.removePrefix("category:")
            require(category.length in 1..160) { "哔咔分类无效。" }
            builder.addQueryParameter("c", category)
        } else require(request.feedId == "latest") { "哔咔不支持这个浏览入口。" }
        listing(call(builder.build().toString().removePrefix("$API/")).getJSONObject("data")
            .getJSONObject("comics"), request.page + 1)
    }

    override suspend fun searchFilters() = listOf(sortFilter)

    override suspend fun searchFiltered(query: String, page: Int, options: List<String>): MangaPage = withContext(Dispatchers.IO) {
        checkPage(page)
        require(query.trim().length in 1..120) { "请输入关键词。" }
        val args = JSONObject().put("keyword", query.trim()).put("categories", JSONArray()).put("s", checkedSort(options))
        listing(call("comics/advanced-search?page=${page + 1}", "POST", args)
            .getJSONObject("data").getJSONObject("comics"), page + 1)
    }

    override suspend fun favoriteFolders() = listOf(SourceFavoriteFolder("default", "站点收藏"))

    override suspend fun favorites(folderId: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(folderId == "default") { "哔咔只提供一个收藏夹。" }
        checkPage(page)
        listing(call("users/favourite?page=${page + 1}&s=dd").getJSONObject("data").getJSONObject("comics"), page + 1)
    }

    override suspend fun setFavorite(mangaId: String, folderId: String, favorite: Boolean): Unit = accountLock.withLock {
        require(folderId == "default") { "哔咔只提供一个收藏夹。" }
        val id = nativeMangaId(mangaId)
        val current = call("comics/$id").getJSONObject("data").getJSONObject("comic")
        require(current.has("isFavourite")) { "来源未返回实际收藏状态，未修改站点收藏。" }
        // The upstream API toggles. Read its current state before the single explicit mutation.
        if (current.optBoolean("isFavourite", false) != favorite) call("comics/$id/favourite", "POST")
    }

    override suspend fun comments(mangaId: String, page: Int): SourceCommentPage = withContext(Dispatchers.IO) {
        checkPage(page)
        val data = call("comics/${nativeMangaId(mangaId)}/comments?page=${page + 1}")
            .getJSONObject("data").getJSONObject("comments")
        val rows = data.getJSONArray("docs")
        require(data.optInt("page", page + 1) == page + 1) { "哔咔评论页码不一致。" }
        val comments = rows.bodyObjects().map { row ->
            val user = row.getJSONObject("_user")
            SourceComment(row.bodyId("_id"), user.bodyText("name"), row.bodyText("content"),
                user.optJSONObject("avatar")?.let { runCatching { mediaUrl(it) }.getOrDefault("") }.orEmpty(),
                row.bodyText("created_at"), row.optInt("likesCount", 0).coerceAtLeast(0))
        }
        SourceCommentPage(comments, page + 1 < data.optInt("pages", 1))
    }

    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        checkPage(page)
        val data = call("comics?page=${page + 1}&s=dd").getJSONObject("data").getJSONObject("comics")
        listing(data, page + 1)
    }

    override suspend fun search(query: String, page: Int): MangaPage = searchFiltered(query, page, emptyList())

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val id = nativeMangaId(mangaId)
        val body = call("comics/$id").getJSONObject("data").getJSONObject("comic")
        require(body.bodyText("_id") == id) { "哔咔作品编号不一致，已停止加载。" }
        val manga = toManga(body)
        val chapters = ArrayList<Chapter>()
        var page = 1
        var completed = false
        while (page <= 150) {
            val data = call("comics/$id/eps?page=$page").getJSONObject("data").getJSONObject("eps")
            val docs = data.optJSONArray("docs") ?: throw SourceException("哔咔章节目录字段暂不兼容。")
            for (index in 0 until docs.length()) {
                val row = docs.getJSONObject(index)
                val order = row.optInt("order", -1)
                if (order < 0) continue
                val title = row.bodyText("title").ifBlank { "第${order.coerceAtLeast(1)}话" }
                val variant = ChapterVariant(info.id, info.name, id, order.toString(), title, info.language, "连载版")
                if (chapters.none { it.sourceChapterId == order.toString() })
                    chapters += Chapter(variant.storageId, title, chapters.size, "连载",
                        variants = listOf(variant), sourceId = info.id,
                        sourceMangaId = id, sourceChapterId = order.toString())
            }
            if (page >= data.optInt("pages", 1)) { completed = true; break }
            require(docs.length() > 0) { "哔咔章节目录中途缺页，请重试。" }
            page++
        }
        require(completed) { "哔咔章节目录超过读取上限，未返回不完整目录。" }
        if (chapters.isEmpty()) throw SourceException("哔咔没有提供可阅读章节。")
        MangaDetail(manga, chapters.sortedBy { it.sourceChapterId.toInt() }.mapIndexed { index, chapter -> chapter.copy(index = index) })
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id) { "章与漫画源不一致。" }
        val id = safeId(ref.mangaId)
        val order = ref.chapterId.toIntOrNull()?.takeIf { it in 0..100_000 }
            ?: throw SourceException("哔咔章节编号无效。")
        val pages = ArrayList<Page>()
        var page = 1
        var completed = false
        while (page <= 500) {
            val result = call("comics/$id/order/$order/pages?page=$page")
                .getJSONObject("data").getJSONObject("pages")
            val rows = result.optJSONArray("docs") ?: throw SourceException("哔咔图片清单暂不兼容。")
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val media = row.optJSONObject("media") ?: throw SourceException("哔咔第${pages.size + 1}页缺少图片地址。")
                pages += Page(pages.size, mediaUrl(media))
                if (pages.size > 10_000) throw SourceException("本章图片数量超过安全上限。")
            }
            if (page >= result.optInt("pages", 1)) { completed = true; break }
            require(rows.length() > 0) { "哔咔图片清单中途缺页，请重试。" }
            page++
        }
        require(completed) { "哔咔图片清单超过读取上限，未返回不完整章节。" }
        if (pages.isEmpty()) throw SourceException("哔咔没有返回可阅读图片。")
        pages
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT, "Referer" to "$HOME/", "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8")

    private fun checkPage(page: Int) = require(page in 0..499) { "哔咔分页超出范围。" }

    private fun listing(data: JSONObject, expectedPage: Int): MangaPage {
        val docs = data.optJSONArray("docs") ?: throw SourceException("哔咔作品列表暂不兼容。")
        val works = (0 until minOf(docs.length(), 200)).mapNotNull { i ->
            docs.optJSONObject(i)?.let { row -> runCatching { toManga(row) }.getOrNull() }
        }.distinctBy(Manga::id)
        if (docs.length() > 0 && works.isEmpty())
            throw SourceException("来源返回的作品列表无法识别，请更新来源后重试。")
        val totalPages = data.optInt("pages", -1)
        val current = data.optInt("page", -1)
        if (current != expectedPage || current !in 1..500 || totalPages !in 0..100_000 ||
            (totalPages > 0 && current > totalPages) || (totalPages == 0 && works.isNotEmpty()))
            throw SourceException("来源返回的分页信息无效，请刷新后重试。")
        // A valid empty page can precede later pages. Keep its real forward cursor.
        val more = current < minOf(totalPages, 500)
        return MangaPage(works, more, if (more) current else null)
    }

    private fun toManga(row: JSONObject): Manga {
        val id = safeId(row.bodyText("_id"))
        val title = row.bodyText("title").ifBlank { throw SourceException("哔咔作品标题缺失。") }
        val categories = row.optJSONArray("categories")
        val tags = buildList {
            if (categories != null) for (index in 0 until categories.length()) {
                val value = categories.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
            val native = row.optJSONArray("tags")
            if (native != null) for (index in 0 until native.length()) {
                val value = native.optString(index).trim()
                if (value.isNotBlank()) add(value)
            }
        }.distinct()
        return Manga(
            id = SourceKeys.manga(info.id, id), title = title,
            coverUrl = row.optJSONObject("thumb")?.let { runCatching { mediaUrl(it) }.getOrDefault("") }.orEmpty(),
            authors = listOfNotNull(row.bodyText("author").takeIf(String::isNotBlank)),
            description = row.bodyText("description"),
            status = if (row.optBoolean("finished", false)) "完结" else "连载",
            tags = tags, sourceUrl = "$HOME/comics/$id", sourceId = info.id, sourceMangaId = id,
            sourceName = info.name,
        )
    }

    private fun safeId(value: String): String = value.takeIf {
        it.matches(Regex("[a-zA-Z0-9_-]{6,80}"))
    } ?: throw SourceException("哔咔作品标识不兼容。")

    private fun nativeMangaId(value: String): String {
        val ref = SourceKeys.decodeManga(value)
        require(!SourceKeys.isEncoded(value) || ref.sourceId == info.id) { "作品与哔咔来源不一致。" }
        return safeId(ref.mangaId)
    }

    private fun checkedSort(options: List<String>): String {
        require(options.size <= 1) { "哔咔搜索条件无效。" }
        return options.firstOrNull().orEmpty().ifBlank { "dd" }.also {
            require(it in setOf("dd", "da", "ld", "vd")) { "哔咔排序无效。" }
        }
    }

    private fun mediaUrl(media: JSONObject): String {
        val path = media.bodyText("path")
        val server = media.bodyText("fileServer").trimEnd('/')
        require(server.isNotBlank() && path.isNotBlank() && !path.contains("..")) { "图片没有有效文件位置。" }
        val prefix = when {
            path.startsWith("tobs/") -> "/static/" + path.removePrefix("tobs/")
            path.startsWith("tobeimg/") -> "/" + path.removePrefix("tobeimg/")
            else -> "/static/" + path.trimStart('/')
        }
        val host = server.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
            ?: throw SourceException("图片服务器不是可信的 HTTPS 地址。")
        return checkedBodyUrl(host.toString().trimEnd('/') + prefix).toString()
    }

    private suspend fun call(
        path: String, method: String = "GET", body: JSONObject? = null,
        requireLogin: Boolean = true,
    ): JSONObject {
        if (requireLogin && session.isBlank()) throw SourceException("请先登录哔咔账号。")
        val url = "$API/${path.trimStart('/')}".toHttpUrl()
        val relative = url.toString().removePrefix("$API/")
        val requestSession = session
        val timestamp = (wallTimeMillis() / 1_000L).toString()
        val nonce = randomUuid().replace("-", "")
        val signable = (relative + timestamp + nonce + method + API_KEY).lowercase()
        val signature = hmacSha256(SIGNING_KEY.encodeToByteArray(), signable.encodeToByteArray())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val request = Request.Builder().url(url)
            .header("api-key", API_KEY).header("accept", "application/vnd.picacomic.com.v1+json")
            .header("app-channel", "3").header("app-version", "2.2.1.3.3.4")
            .header("app-uuid", "defaultUuid").header("app-platform", "android")
            .header("app-build-version", "45").header("image-quality", "original")
            .header("time", timestamp).header("nonce", nonce).header("signature", signature)
            .header("User-Agent", USER_AGENT)
        if (requireLogin && requestSession.isNotBlank()) request.header("authorization", requestSession)
        val built = if (method == "POST") request.post((body ?: JSONObject()).toString()
            .toRequestBody("application/json; charset=UTF-8".toMediaType())).build() else request.get().build()
        val response = network.request(built, "哔咔", HOME, readAccountErrors = true)
        val result = try { JSONObject(response.text) } catch (failure: Exception) {
            throw SourceException("哔咔返回格式暂不兼容。", HOME, cause = failure)
        }
        if (response.statusCode == 401 || result.optString("code") == "401") {
            if (requireLogin && session == requestSession) { session = ""; _signedIn.value = false }
            throw SourceException("哔咔会话过期，请重新登录。", HOME, httpStatus = 401)
        }
        val code = result.optInt("code", response.statusCode)
        if (response.statusCode !in 200..299 || code !in 200..299)
            throw SourceException("哔咔请求失败（${response.statusCode}）：${result.bodyText("message").take(180)}", HOME,
                httpStatus = response.statusCode)
        return result
    }

    companion object {
        private const val SECRET_KEY = "adult_picacg_session"
        private const val HOME = "https://www.picacomic.com"
        private const val API = "https://picaapi.picacomic.com"
        private const val USER_AGENT = "okhttp/3.8.1"
        private val sortFilter = SourceFilter("sort", "排序", listOf(SourceChoice("dd", "最新"),
            SourceChoice("da", "最早"), SourceChoice("ld", "最多喜欢"), SourceChoice("vd", "最多浏览")), "dd")
        // Original public client protocol constants (l2studio/picacomic-api, Apache-2.0).
        private const val API_KEY = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
        private const val SIGNING_KEY = "~d}\$Q7\$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"
    }
}
