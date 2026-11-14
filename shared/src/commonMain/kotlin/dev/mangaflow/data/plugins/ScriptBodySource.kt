package dev.mangaflow.data.plugins

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.sources.*
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Upstream adult declarations are private by default, even without manual assignment. */
internal fun adultSourceMetadata(metadata: JSONObject): Boolean =
    metadata.optBoolean("isNsfw", false) || metadata.optBoolean("isAdult", false) ||
        metadata.optBoolean("nsfw", false) || metadata.optBoolean("adult", false) ||
        metadata.optString("contentRating").lowercase() in setOf("adult", "mature", "r18", "18+")

/** Script protocols translated into the existing source-qualified reader contract. */
class ScriptBodySource(bundle: ScriptSourcePackage, private val runtime: JavascriptSourceRuntime,
    private val context: PlatformContext? = null) : BodySource {
    val bundle = bundle.copy(metadata = JSONObject(bundle.metadata.toString()))
    val digest: String get() = bundle.digest
    override val info = BodySourceInfo(bundle.sourceId, bundle.metadata.getString("name"),
        bundle.metadata.getString("lang"), publicPluginUrl(bundle.metadata.getString("baseUrl")).toString(), "", revision = bundle.digest,
        adult = adultSourceMetadata(bundle.metadata))
    private val headerLock = SynchronizedObject()
    private val chapterHeaders = LinkedHashMap<String, Map<String, String>>()
    private val coverHeaders = LinkedHashMap<String, Map<String, String>>()
    private val venera: Boolean get() = bundle.metadata.optString("protocol") == "venera"
    private val capabilityLock = Mutex()
    private val paginationLock = Mutex()
    private data class CursorChain(val next: LinkedHashMap<Int, String> = linkedMapOf())
    private val cursorChains = LinkedHashMap<String, CursorChain>()
    @Volatile private var sessionGeneration = 0L
    @Volatile private var declaredCapabilities: SourceCapabilities? = null
    @Volatile private var publicHeaders: Map<String, String> = manifestHeaders(bundle.metadata)

    internal fun forDownload(): ScriptBodySource = ScriptBodySource(bundle, runtime, context)

    private fun clearSessionImages() {
        declaredCapabilities = null
        synchronized(headerLock) {
            sessionGeneration++
            cursorChains.clear()
            chapterHeaders.clear()
            coverHeaders.clear()
            publicHeaders = manifestHeaders(bundle.metadata)
        }
        context?.let { invalidateScriptSourceImages(it, info.id) }
    }

    override suspend fun capabilities(): SourceCapabilities = capabilityLock.withLock {
        declaredCapabilities?.let { return@withLock it }
        val value = call("capabilities", JSONArray()).getJSONObject("result")
        val features = value.getJSONArray("features")
        SourceCapabilities((0 until features.length()).mapNotNull { index ->
            SourceFeature.entries.firstOrNull { it.name == features.getString(index) }
        }.toSet(), value.optBoolean("loginRequired", false), value.optBoolean("passwordLogin", true),
            value.optJSONArray("cookieFields")?.let { fields ->
                require(fields.length() <= 16) { "来源登录字段过多。" }
                (0 until fields.length()).map { fields.getString(it).also { name -> require(name.length in 1..160) } }
            }.orEmpty()).also { declaredCapabilities = it }
    }

    override suspend fun feeds(): List<SourceFeed> {
        if (!venera) return super.feeds()
        val values = call("feeds", JSONArray()).getJSONArray("result")
        require(values.length() <= 2_000) { "源浏览入口过多。" }
        return (0 until values.length()).map { index ->
            val row = values.getJSONObject(index)
            SourceFeed(row.getString("id"), row.getString("title"),
                SourceFeedKind.entries.firstOrNull { it.name == row.optString("kind") } ?: SourceFeedKind.EXPLORE,
                filters(row.optJSONArray("filters")))
        }
    }

    override suspend fun feedFilters(feedId: String): List<SourceFilter> = if (!venera) super.feedFilters(feedId)
        else filters(call("feedFilters", JSONArray().put(feedId)).getJSONArray("result"))

    override suspend fun browse(request: SourceBrowseRequest): MangaPage = if (!venera) super.browse(request)
        else listing("browse", JSONArray().put(request.feedId).put(upstreamPage(request.page)).put(JSONArray(request.options)))

    override suspend fun searchFilters(): List<SourceFilter> = if (!venera) emptyList()
        else filters(call("searchFilters", JSONArray()).getJSONArray("result"))

    override suspend fun searchFiltered(query: String, page: Int, options: List<String>): MangaPage {
        if (!venera) return super.searchFiltered(query, page, options)
        require(query.trim().length in 1..120) { "请输入 1–120 个字的标题。" }
        return listing("search", JSONArray().put(query.trim()).put(upstreamPage(page)).put(JSONArray(options)))
    }

    override suspend fun accountState(): SourceAccountState {
        if (!venera) return super.accountState()
        val value = call("accountState", JSONArray()).getJSONObject("result")
        return SourceAccountState(value.optBoolean("signedIn", false), value.optString("displayName"))
    }

    override suspend fun login(username: String, password: String) {
        require(venera) { "此脚本源未提供账号能力。" }
        require(username.trim().length in 1..160 && password.length in 1..512) { "账号或密码长度无效。" }
        clearSessionImages()
        try { call("login", JSONArray().put(username.trim()).put(password)) }
        finally { clearSessionImages() }
    }

    override suspend fun logout() {
        require(venera) { "此脚本源未提供账号能力。" }
        clearSessionImages()
        try { call("logout", JSONArray()) }
        finally { clearSessionImages() }
    }

    override suspend fun loginWithCookies(values: List<String>) {
        require(venera && values.size in 1..16 && values.any(String::isNotBlank) &&
            values.all { it.length <= 8_192 && it.none { char -> char == '\r' || char == '\n' || char == ';' } }) { "Cookie 登录信息无效。" }
        clearSessionImages()
        try { call("loginCookies", JSONArray().put(JSONArray(values))) }
        finally { clearSessionImages() }
    }

    override suspend fun favoriteFolders(): List<SourceFavoriteFolder> {
        require(venera) { "此脚本源未提供站点收藏。" }
        val values = call("favoriteFolders", JSONArray()).getJSONArray("result")
        require(values.length() <= 500) { "源收藏夹过多。" }
        return (0 until values.length()).map { index -> values.getJSONObject(index).let {
            SourceFavoriteFolder(it.getString("id"), it.getString("title"))
        } }
    }

    override suspend fun favorites(folderId: String, page: Int): MangaPage {
        require(venera) { "此脚本源未提供站点收藏。" }
        return listing("favorites", JSONArray().put(folderId).put(upstreamPage(page)))
    }

    override suspend fun setFavorite(mangaId: String, folderId: String, favorite: Boolean) {
        require(venera) { "此脚本源未提供站点收藏。" }
        call("setFavorite", JSONArray().put(nativeManga(mangaId)).put(folderId).put(favorite))
    }

    override suspend fun comments(mangaId: String, page: Int): SourceCommentPage {
        require(venera) { "此脚本源未提供站点评论。" }
        val result = call("comments", JSONArray().put(nativeManga(mangaId)).put(upstreamPage(page))).getJSONObject("result")
        val values = result.getJSONArray("items")
        require(values.length() <= 1_000) { "源评论条目过多。" }
        return SourceCommentPage((0 until values.length()).map { index ->
            val row = values.getJSONObject(index)
            SourceComment(row.getString("id"), row.optString("author"), row.optString("content").take(40_000),
                row.optString("avatar").takeIf(String::isNotBlank)?.let(::imageUrl).orEmpty(),
                row.optString("time"), row.optInt("likes", 0))
        }, result.optBoolean("hasMore", false))
    }

    override suspend fun settings(): List<SourceSetting> {
        if (!venera) return emptyList()
        val values = call("settings", JSONArray()).getJSONArray("result")
        require(values.length() <= 128) { "源设置条目过多。" }
        return (0 until values.length()).map { index ->
            val row = values.getJSONObject(index)
            SourceSetting(row.getString("key"), row.getString("title"), row.optString("value"),
                row.optString("type", "input"), choices(row.optJSONArray("choices")))
        }
    }

    override suspend fun saveSetting(key: String, value: String) {
        require(venera) { "此脚本源未提供设置。" }
        require(key.length in 1..160 && value.length <= 4_096) { "源设置值无效。" }
        clearSessionImages()
        try { call("saveSetting", JSONArray().put(key).put(value)) }
        finally { clearSessionImages() }
    }

    private fun choices(values: JSONArray?): List<SourceChoice> {
        if (values == null) return emptyList()
        require(values.length() <= 2_000) { "源筛选选项过多。" }
        return (0 until values.length()).map { index -> values.getJSONObject(index).let {
            SourceChoice(it.getString("id"), it.getString("title"))
        } }
    }

    private fun filters(values: JSONArray?): List<SourceFilter> {
        if (values == null) return emptyList()
        require(values.length() <= 64) { "源筛选组过多。" }
        return (0 until values.length()).map { index -> values.getJSONObject(index).let {
            SourceFilter(it.getString("id"), it.getString("title"), choices(it.optJSONArray("choices")),
                it.optString("defaultValue"), it.optBoolean("multiple", false))
        } }
    }

    override suspend fun discover(page: Int): MangaPage = listing("getPopular", JSONArray().put(upstreamPage(page)))

    override suspend fun search(query: String, page: Int): MangaPage {
        require(query.trim().length in 1..120) { "请输入 1–120 个字的标题。" }
        return listing("search", JSONArray().put(query.trim()).put(upstreamPage(page)).put(JSONArray()))
    }

    private fun paginationSession(): String = "$sessionGeneration:" + context?.let { scriptSourceImageEpoch(it, info.id) }.orEmpty()

    /** Logical pages stay in the app; opaque continuations never become a manga or chapter id. */
    private suspend fun listing(method: String, args: JSONArray): MangaPage = withContext(Dispatchers.IO) { paginationLock.withLock {
        val upstream = if (method in setOf("search", "browse", "favorites")) args.getInt(1) else args.getInt(0)
        val session = paginationSession()
        val lane = JSONArray().put(method).put(if (method == "getPopular") "" else args.opt(0))
            .put(if (method in setOf("search", "browse")) args.opt(2) else JSONObject.NULL).toString()
        val key = session + ":" + lane
        val cursor = synchronized(headerLock) {
            if (upstream == 1) cursorChains.remove(key)
            val chain = cursorChains[key]
            if (venera && upstream > 1 && chain != null && chain.next[upstream] == null)
                throw SourceException("列表分页已失效，请刷新后继续。")
            chain?.next?.get(upstream)
        }
        if (venera) {
            while (args.length() < 3) args.put(JSONObject.NULL)
            args.put(cursor ?: JSONObject.NULL)
        }
        val envelope = call(method, args)
        currentCoroutineContext().ensureActive()
        if (session != paginationSession()) throw CancellationException("来源账号或设置已变化，请刷新列表。")
        val result = envelope.get("result")
        val list = when (result) {
            is JSONArray -> result
            is JSONObject -> result.optJSONArray("list") ?: result.optJSONArray("mangas")
                ?: throw SourceException("${info.name} 没有返回真实作品列表。")
            else -> throw SourceException("${info.name} 的作品列表格式不兼容。")
        }
        require(list.length() <= 2_000) { "源返回的单页作品过多。" }
        val rows = (0 until list.length()).map { manga(list.getJSONObject(it)) }.distinctBy(Manga::id)
        val hasMore = (result as? JSONObject)?.optBoolean("hasNextPage", false) ?: false
        if (venera && result is JSONObject && result.optBoolean("cursorMode", false)) synchronized(headerLock) {
            if (session != paginationSession()) throw CancellationException("来源账号或设置已变化，请刷新列表。")
            val chain = cursorChains.remove(key) ?: CursorChain()
            if (hasMore) {
                val next = result.getString("nextCursor")
                require(next.isNotBlank() && next.length <= 8_192 && next.none(Char::isISOControl) &&
                    chain.next.none { (page, value) -> page <= upstream && value == next }) { "来源返回了重复或无效的分页游标。" }
                if (chain.next[upstream + 1] != next) chain.next.keys.filter { it > upstream }.forEach(chain.next::remove)
                chain.next[upstream + 1] = next
                while (chain.next.size > 64) chain.next.remove(chain.next.keys.first())
            }
            cursorChains[key] = chain
            while (cursorChains.size > 16) cursorChains.remove(cursorChains.keys.first())
        }
        MangaPage(rows, hasMore, if (hasMore) upstream else null)
    } }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val native = nativeManga(mangaId)
        val result = call("getDetail", JSONArray().put(native)).getJSONObject("result")
        val manga = manga(result, native)
        val values = result.optJSONArray("chapters") ?: result.optJSONArray("episodes")
            ?: throw SourceException("${info.name} 没有返回真实章节目录。")
        require(values.length() in 1..20_000) { "源章节目录为空或超过可读范围。" }
        val chapters = (0 until values.length()).map { index ->
            val row = values.getJSONObject(index)
            val id = opaque(row, "url", "link", "id")
            val title = text(row, "name", "title").takeIf(String::isNotBlank)
                ?: throw SourceException("源章节缺少实际标题。")
            val scanlator = text(row, "scanlator")
            // An unknown edition stays independent in the unified catalogue.
            val edition = text(row, "edition").ifBlank { "未标明版别" }
            val language = text(row, "language", "lang").ifBlank { info.language }
            val variant = ChapterVariant(info.id, info.name, native, id, title, language,
                edition, scanlator, url = id.takeIf { it.startsWith("https://") }.orEmpty())
            Chapter(variant.storageId, title, index, text(row, "group").ifBlank { "默认" },
                variants = listOf(variant), sourceId = info.id, sourceMangaId = native, sourceChapterId = id)
        }.distinctBy(Chapter::id)
        MangaDetail(manga, chapters)
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id) { "章节和脚本来源不一致。" }
        val sessionEpoch = context?.let { scriptSourceImageEpoch(it, info.id) }.orEmpty()
        val arguments = JSONArray().put(ref.chapterId)
        if (venera) arguments.put(ref.mangaId)
        val envelope = call("getPageList", arguments)
        if (context != null && scriptSourceImageEpoch(context, info.id) != sessionEpoch)
            throw CancellationException("来源账号或设置已变化，请刷新本章。")
        val values = envelope.get("result") as? JSONArray ?: throw SourceException("源没有返回真实图片清单。")
        require(values.length() in 1..10_000) { "源图片清单为空或超过可读范围。" }
        val baseHeaders = headers(envelope.optJSONObject("headers"))
        val pages = (0 until values.length()).map { index ->
            val item = values.get(index)
            if (venera && item is JSONObject && item.has("sourceImageToken")) {
                val token = item.getString("sourceImageToken")
                require(token.isNotBlank() && token.length <= 8_192 && token.none(Char::isISOControl)) { "源图片编号无效。" }
                val imageIdentity = sha256((ref.mangaId + "\u0000" + ref.chapterId + "\u0000" + token + "\u0000" + sessionEpoch).encodeToByteArray())
                    .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
                Page(index, "mangaflow-script://${info.id}/${bundle.digest}/$imageIdentity/$index",
                    sourceImageToken = token, sourceRevision = bundle.digest, sourceSessionEpoch = sessionEpoch)
            } else {
                val raw = when (item) { is String -> item; is JSONObject -> opaque(item, "url"); else -> throw SourceException("图片清单条目格式不兼容。") }
                val perPage = if (item is JSONObject) headers(item.optJSONObject("headers")) else emptyMap()
                Page(index, imageUrl(raw), (item as? JSONObject)?.optInt("width", 0)?.coerceAtLeast(0) ?: 0,
                    (item as? JSONObject)?.optInt("height", 0)?.coerceAtLeast(0) ?: 0,
                    headers = baseHeaders + perPage, sourceSessionEpoch = sessionEpoch)
            }
        }
        synchronized(headerLock) {
            chapterHeaders.remove(chapterId)
            chapterHeaders[chapterId] = baseHeaders
            while (chapterHeaders.size > 64) chapterHeaders.remove(chapterHeaders.keys.first())
        }
        pages
    }

    /** No JS runs during composition/image header lookup. */
    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> =
        synchronized(headerLock) {
            if (chapterId.isBlank()) coverHeaders[SourceKeys.decodeManga(mangaId).mangaId] else chapterHeaders[chapterId]
        } ?: publicHeaders

    private suspend fun call(method: String, args: JSONArray): JSONObject {
        val value = JSONObject(runtime.call(bundle, method, args))
        require(value.has("result") && !value.isNull("result")) { "${info.name} 的 $method 没有返回结果。" }
        val received = headers(value.optJSONObject("headers"))
        if (received.isNotEmpty()) publicHeaders = received
        return value
    }

    private fun manga(row: JSONObject, fallbackId: String? = null): Manga {
        val native = fallbackId ?: optionalOpaque(row, "link", "url", "id") ?: throw SourceException("作品缺少实际来源编号。")
        val title = text(row, "name", "title").takeIf(String::isNotBlank) ?: throw SourceException("作品缺少实际标题。")
        val author = row.opt("author") ?: row.opt("authors")
        val authors = when (author) {
            is JSONArray -> (0 until author.length()).map { author.getString(it) }
            is String -> author.split(Regex("[/、,，;；\\n]")).map(String::trim).filter(String::isNotBlank)
            else -> emptyList()
        }
        val genre = row.opt("genre") ?: row.opt("genres")
        val tags = when (genre) {
            is JSONArray -> (0 until genre.length()).map { genre.getString(it) }
            is String -> genre.split(',').map(String::trim).filter(String::isNotBlank)
            else -> emptyList()
        }
        val cover = text(row, "imageUrl", "coverUrl", "cover").takeIf(String::isNotBlank)?.let(::imageUrl).orEmpty()
        if (row.has("headers")) synchronized(headerLock) {
            coverHeaders.remove(native)
            coverHeaders[native] = headers(row.optJSONObject("headers"))
            while (coverHeaders.size > 500) coverHeaders.remove(coverHeaders.keys.first())
        }
        return Manga(SourceKeys.manga(info.id, native), title, cover, authors,
            description = text(row, "description"), status = text(row, "status"), tags = tags,
            latestChapter = text(row, "latestChapter"), sourceUrl = native.takeIf { it.startsWith("https://") }.orEmpty(),
            sourceId = info.id, sourceMangaId = native, sourceName = info.name)
    }

    private fun nativeManga(value: String): String {
        val ref = SourceKeys.decodeManga(value)
        require(!SourceKeys.isEncoded(value) || ref.sourceId == info.id) { "作品和脚本来源不一致。" }
        return ref.mangaId
    }
    private fun imageUrl(value: String): String = publicPluginUrl(
        if (value.startsWith("//")) "https:$value" else info.homeUrl.toHttpUrlOrNull()?.resolve(value)?.toString() ?: value
    ).toString()

    companion object {
        fun upstreamPage(page: Int): Int { require(page in 0..100_000); return page + 1 }
        fun text(row: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull {
            (row.opt(it) as? String)?.trim()?.takeIf(String::isNotBlank)
        }.orEmpty()
        fun optionalOpaque(row: JSONObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            when (val value = row.opt(key)) { is String -> value; is Number -> value.toString(); else -> null }
                ?.takeIf { it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl) }
        }
        fun opaque(row: JSONObject, vararg keys: String): String = optionalOpaque(row, *keys)
            ?: throw SourceException("源条目缺少真实编号。")
        internal fun manifestHeaders(metadata: JSONObject): Map<String, String> = when (val value = metadata.opt("headers")) {
            null, JSONObject.NULL -> emptyMap()
            is JSONObject -> headers(value)
            is String -> if (value.isBlank()) emptyMap() else headers(JSONObject(value))
            else -> throw SourceException("源公共图片请求头格式不兼容。")
        }
        fun headers(value: JSONObject?): Map<String, String> {
            if (value == null) return emptyMap()
            val excluded = setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection", "accept-encoding")
            val result = value.keys().asSequence().filter { it.lowercase() !in excluded }.associate { key ->
                require(key.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}"))) { "源图片请求头名称无效。" }
                val content = value.getString(key)
                require(content.length <= 8_192 && '\r' !in content && '\n' !in content) { "源图片请求头无效。" }
                key to content
            }
            require(result.size <= 64 && result.entries.sumOf { it.key.length + it.value.length } <= 32_768) { "源图片请求头过多。" }
            return result
        }
    }
}
