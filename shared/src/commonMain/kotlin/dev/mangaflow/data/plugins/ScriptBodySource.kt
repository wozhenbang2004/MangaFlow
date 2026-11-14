package dev.mangaflow.data.plugins

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.sources.BodySource
import dev.mangaflow.data.sources.BodySourceInfo
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

/** Real Mangayomi calls, translated into the existing source-qualified reader contract. */
class ScriptBodySource(bundle: ScriptSourcePackage, private val runtime: JavascriptSourceRuntime) : BodySource {
    val bundle = bundle.copy(metadata = JSONObject(bundle.metadata.toString()))
    val digest: String get() = bundle.digest
    override val info = BodySourceInfo(bundle.sourceId, bundle.metadata.getString("name"),
        bundle.metadata.getString("lang"), publicPluginUrl(bundle.metadata.getString("baseUrl")).toString(), "", revision = bundle.digest,
        adult = adultSourceMetadata(bundle.metadata))
    private val headerLock = SynchronizedObject()
    private val chapterHeaders = LinkedHashMap<String, Map<String, String>>()
    @Volatile private var publicHeaders: Map<String, String> = manifestHeaders(bundle.metadata)

    internal fun forDownload(): ScriptBodySource = ScriptBodySource(bundle, runtime)

    override suspend fun discover(page: Int): MangaPage = listing("getPopular", JSONArray().put(upstreamPage(page)))

    override suspend fun search(query: String, page: Int): MangaPage {
        require(query.trim().length in 1..120) { "请输入 1–120 个字的标题。" }
        return listing("search", JSONArray().put(query.trim()).put(upstreamPage(page)).put(JSONArray()))
    }

    private suspend fun listing(method: String, args: JSONArray): MangaPage = withContext(Dispatchers.IO) {
        val envelope = call(method, args)
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
        val upstream = if (method == "search") args.getInt(1) else args.getInt(0)
        MangaPage(rows, hasMore, if (hasMore) upstream else null)
    }

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
        val envelope = call("getPageList", JSONArray().put(ref.chapterId))
        val values = envelope.get("result") as? JSONArray ?: throw SourceException("源没有返回真实图片清单。")
        require(values.length() in 1..10_000) { "源图片清单为空或超过可读范围。" }
        val baseHeaders = headers(envelope.optJSONObject("headers"))
        var common: Map<String, String>? = null
        val pages = (0 until values.length()).map { index ->
            val item = values.get(index)
            val raw = when (item) { is String -> item; is JSONObject -> opaque(item, "url"); else -> throw SourceException("图片清单条目格式不兼容。") }
            val perPage = if (item is JSONObject) headers(item.optJSONObject("headers")) else emptyMap()
            val actualHeaders = baseHeaders + perPage
            if (common == null) common = actualHeaders else require(common == actualHeaders) {
                "此源每页使用不同请求头，当前下载与阅读链路暂不支持。"
            }
            Page(index, imageUrl(raw), (item as? JSONObject)?.optInt("width", 0)?.coerceAtLeast(0) ?: 0,
                (item as? JSONObject)?.optInt("height", 0)?.coerceAtLeast(0) ?: 0)
        }
        synchronized(headerLock) {
            chapterHeaders.remove(chapterId)
            chapterHeaders[chapterId] = common.orEmpty()
            while (chapterHeaders.size > 64) chapterHeaders.remove(chapterHeaders.keys.first())
        }
        pages
    }

    /** No JS runs during composition/image header lookup. */
    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> =
        synchronized(headerLock) { chapterHeaders[chapterId] } ?: publicHeaders

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
        fun headers(value: JSONObject?): Map<String, String> = value?.keys()?.asSequence()?.associate { key ->
            val name = when (key.lowercase()) {
                "referer" -> "Referer"; "user-agent" -> "User-Agent"; "accept" -> "Accept"
                else -> throw SourceException("此源要求 $key 图片请求头，当前仅支持 Referer、User-Agent、Accept。")
            }
            val content = value.getString(key)
            require(content.length <= 8192 && '\r' !in content && '\n' !in content) { "源图片请求头无效。" }
            name to content
        }.orEmpty()
    }
}
