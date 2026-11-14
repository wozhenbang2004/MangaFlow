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

/**
 * Built-in PicACG source: no imported script, shared album/reader IDs, native account session.
 * Protocol shapes checked against l2studio/picacomic-api (Apache-2.0) and niuhuan/pica-rust (MIT).
 * Access permissions remain controlled by the upstream service.
 */
class PicacgBodySource(private val context: PlatformContext) : BodySource {
    override val info = BodySourceInfo("picacg", "哔咔", "简中", HOME, "", adult = true)
    private val network = SourceHttp()
    private val accountLock = Mutex()
    private var session: String = readSecret(context, SECRET_KEY).orEmpty()
    private val _signedIn = MutableStateFlow(session.isNotBlank())
    val signedIn = _signedIn.asStateFlow()

    suspend fun login(user: String, password: String): Unit = accountLock.withLock {
        require(user.trim().isNotEmpty() && password.isNotBlank()) { "请输入哔咔账号和密码。" }
        require(user.length <= 160 && password.length <= 512) { "账号输入长度无效。" }
        val payload = JSONObject().put("email", user.trim()).put("password", password)
        val token = call("auth/sign-in", "POST", payload, requireLogin = false)
            .optJSONObject("data")?.bodyText("token").orEmpty()
        require(token.length in 8..8192) { "登录接口没有返回有效会话。" }
        writeSecret(context, SECRET_KEY, token)
        session = token
        _signedIn.value = true
    }

    suspend fun logout() = accountLock.withLock {
        deleteSecret(context, SECRET_KEY)
        session = ""
        _signedIn.value = false
    }

    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        checkPage(page)
        val data = call("comics?page=${page + 1}&s=dd").getJSONObject("data").getJSONObject("comics")
        listing(data)
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        checkPage(page)
        require(query.trim().length in 1..120) { "请输入关键词。" }
        val args = JSONObject().put("keyword", query.trim()).put("categories", dev.mangaflow.json.JSONArray()).put("s", "dd")
        val data = call("comics/advanced-search?page=${page + 1}", "POST", args)
            .getJSONObject("data").getJSONObject("comics")
        listing(data)
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val id = safeId(SourceKeys.decodeManga(mangaId).mangaId)
        val body = call("comics/$id").getJSONObject("data").getJSONObject("comic")
        val manga = toManga(body)
        val chapters = ArrayList<Chapter>()
        var page = 1
        while (page <= 150) {
            val data = call("comics/$id/eps?page=$page").getJSONObject("data").getJSONObject("eps")
            val docs = data.optJSONArray("docs") ?: throw SourceException("哔咔章节目录字段暂不兼容。")
            for (index in 0 until docs.length()) {
                val row = docs.getJSONObject(index)
                val order = row.optInt("order", -1)
                if (order < 0) continue
                val title = row.bodyText("title").ifBlank { "第${order + 1}话" }
                val variant = ChapterVariant(info.id, info.name, id, order.toString(), title, info.language, "连载版")
                if (chapters.none { it.sourceChapterId == order.toString() })
                    chapters += Chapter(variant.storageId, title, chapters.size, "连载",
                        variants = listOf(variant), sourceId = info.id,
                        sourceMangaId = id, sourceChapterId = order.toString())
            }
            if (page >= data.optInt("pages", 1) || docs.length() == 0) break
            page++
        }
        if (chapters.isEmpty()) throw SourceException("哔咔没有提供可阅读章节。")
        MangaDetail(manga, chapters)
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id) { "章与漫画源不一致。" }
        val id = safeId(ref.mangaId)
        val order = ref.chapterId.toIntOrNull()?.takeIf { it in 0..100_000 }
            ?: throw SourceException("哔咔章节编号无效。")
        val pages = ArrayList<Page>()
        var page = 1
        while (page <= 500) {
            val result = call("comics/$id/order/$order/pages?page=$page")
                .getJSONObject("data").getJSONObject("pages")
            val rows = result.optJSONArray("docs") ?: throw SourceException("哔咔图片清单暂不兼容。")
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val media = row.optJSONObject("media") ?: continue
                pages += Page(pages.size, mediaUrl(media))
                if (pages.size > 10_000) throw SourceException("本章图片数量超过安全上限。")
            }
            if (page >= result.optInt("pages", 1) || rows.length() == 0) break
            page++
        }
        if (pages.isEmpty()) throw SourceException("哔咔没有返回可阅读图片。")
        pages
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT, "Referer" to "$HOME/", "Accept" to "image/avif,image/webp,image/*,*/*;q=0.8")

    private fun checkPage(page: Int) = require(page in 0..499) { "哔咔分页超出范围。" }

    private fun listing(data: JSONObject): MangaPage {
        val docs = data.optJSONArray("docs") ?: throw SourceException("哔咔作品列表暂不兼容。")
        val works = (0 until minOf(docs.length(), 200)).mapNotNull { i ->
            docs.optJSONObject(i)?.let { row -> runCatching { toManga(row) }.getOrNull() }
        }.distinctBy(Manga::id)
        val totalPages = data.optInt("pages", 1)
        val current = data.optInt("page", 1)
        val more = current < totalPages && works.isNotEmpty()
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
        val relative = path.trimStart('/')
        val url = "$API/$relative".toHttpUrl()
        val timestamp = (wallTimeMillis() / 1_000L).toString()
        val nonce = randomUuid().replace("-", "")
        val signable = (relative + timestamp + nonce + method + API_KEY).lowercase()
        val signature = hmacSha256(SIGNING_KEY.encodeToByteArray(), signable.encodeToByteArray())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val request = Request.Builder().url(url)
            .header("api-key", API_KEY).header("accept", "application/vnd.picacomic.com.v1+json")
            .header("app-channel", "2").header("app-version", "2.2.1.2.3.3")
            .header("app-uuid", "defaultUuid").header("app-platform", "android")
            .header("app-build-version", "44").header("image-quality", "original")
            .header("time", timestamp).header("nonce", nonce).header("signature", signature)
            .header("User-Agent", USER_AGENT)
        if (requireLogin && session.isNotBlank()) request.header("authorization", session)
        val built = if (method == "POST") request.post((body ?: JSONObject()).toString()
            .toRequestBody("application/json; charset=UTF-8".toMediaType())).build() else request.get().build()
        val result = network.bodyJson(built, "哔咔", HOME)
        if (result.optString("code") == "401") throw SourceException("哔咔会话过期，请重新登录。")
        return result
    }

    companion object {
        private const val SECRET_KEY = "adult_picacg_session"
        private const val HOME = "https://www.picacomic.com"
        private const val API = "https://picaapi.picacomic.com"
        private const val USER_AGENT = "okhttp/3.8.1"
        // Original public client protocol constants (l2studio/picacomic-api, Apache-2.0).
        private const val API_KEY = "C69BAF41DA5ABD1FFEDC6D2FEA56B"
        private const val SIGNING_KEY = "~d}\$Q7\$eIni=V)9\\RK/P.RM4;9[7|@/CA}b~OW!3?EV`:<>M7pddUBL5n|0/*Cn"
    }
}
