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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Adapted GraphQL/volume/page contract from Keiyoushi Komiic c3bc55d5 (Apache-2.0).
 * Modified for MangaFlow's anonymous requests, cancellable I/O and explicit source identities.
 * No login/token refresh, private cookies or account mutations are performed here.
 */
class KomiicBodySource(private val selectedBaseUrl: () -> String = { BASE_URLS.first() }) : BodySource {
    private val home: String get() = selectedBaseUrl().takeIf { it in BASE_URLS } ?: BASE_URLS.first()
    override val info get() = BodySourceInfo("komiic", "Komiic", "繁中", home, "$home/favicon.ico")
    private val http = SourceHttp()

    override suspend fun discover(page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(page in 0..333)
        val variables = JSONObject().put("pagination", JSONObject().put("limit", 30).put("offset", page * 30)
            .put("orderBy", "DATE_UPDATED").put("status", "").put("asc", false))
        val data = graphQl("commonQuery", "query commonQuery(\$pagination: Pagination!) { comics: recentUpdate(pagination: \$pagination) $COMIC }", variables)
        val rows = data.getJSONArray("comics").bodyObjects().map(::manga)
        MangaPage(rows, rows.size == 30, if (rows.size == 30) page + 1 else null)
    }

    override suspend fun search(query: String, page: Int): MangaPage = withContext(Dispatchers.IO) {
        require(query.trim().length in 1..120)
        if (page != 0) return@withContext MangaPage(emptyList(), false)
        val data = graphQl("searchComicsAndAuthors",
            "query searchComicsAndAuthors(\$keyword: String!) { searchComicsAndAuthors(keyword: \$keyword) { comics $COMIC } }",
            JSONObject().put("keyword", query.trim()))
        val rows = data.getJSONObject("searchComicsAndAuthors").getJSONArray("comics").bodyObjects().map(::manga)
        MangaPage(rows, false)
    }

    override suspend fun detail(mangaId: String): MangaDetail = withContext(Dispatchers.IO) {
        val id = nativeId(mangaId)
        val data = graphQl("mangaQuery",
            "query mangaQuery(\$comicId: ID!) { comicById(comicId: \$comicId) $COMIC chaptersByComicId(comicId: \$comicId) { id serial type size dateCreated } }",
            JSONObject().put("comicId", id))
        val current = manga(data.getJSONObject("comicById"))
        val chapters = data.getJSONArray("chaptersByComicId").bodyObjects()
            .sortedWith(compareBy<JSONObject> { it.bodyText("type") != "chapter" }
                .thenBy { it.bodyText("serial").toBigDecimalOrNull() ?: BigDecimal.valueOf(Long.MAX_VALUE) }
                .thenBy { it.bodyText("serial") })
            .mapIndexed { index, row ->
                val nativeChapterId = row.bodyId("id")
                val serial = row.bodyText("serial")
                val book = row.bodyText("type") == "book"
                val title = if (book) "第${serial}卷" else "第${serial}话"
                val variant = ChapterVariant(
                    sourceId = info.id, sourceName = info.name, mangaId = id, chapterId = nativeChapterId,
                    title = title, language = info.language, edition = if (book) "单行本" else "连载版",
                    url = "$home/comic/$id/chapter/$nativeChapterId/images/all",
                    pageCount = row.optInt("size").takeIf { it > 0 },
                )
                Chapter(variant.storageId, title, index, if (book) "单行本" else "连载",
                    variants = listOf(variant), sourceId = info.id, sourceMangaId = id, sourceChapterId = nativeChapterId)
            }
        MangaDetail(current, chapters)
    }

    override suspend fun pages(mangaId: String, chapterId: String, forceRefresh: Boolean): List<Page> = withContext(Dispatchers.IO) {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        require(ref.sourceId == info.id)
        val data = graphQl("imagesByChapterId",
            "query imagesByChapterId(\$chapterId: ID!) { imagesByChapterId(chapterId: \$chapterId) { kid } }",
            JSONObject().put("chapterId", id(ref.chapterId)))
        data.getJSONArray("imagesByChapterId").bodyObjects().mapIndexed { index, image ->
            val key = image.bodyId("kid")
            require(key.length <= 1024) { "来源图片标识无效。" }
            Page(index, "$home/api/image/".toHttpUrl().newBuilder().addPathSegment(key).build().toString())
        }.also { if (it.isEmpty()) throw SourceException("Komiic 暂未提供本章图片，可能需要登录或已达访问额度。", home) }
    }

    override fun imageHeaders(mangaId: String, chapterId: String): Map<String, String> {
        val ref = SourceKeys.decodeChapter(mangaId, chapterId)
        return mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36",
            "Referer" to "$home/comic/${id(ref.mangaId)}/chapter/${id(ref.chapterId)}/images/all", "Accept" to "*/*")
    }

    private fun manga(row: JSONObject): Manga {
        val id = id(row.bodyId("id"))
        return Manga(
            id = SourceKeys.manga(info.id, id), title = row.bodyText("title"),
            coverUrl = row.bodyText("imageUrl").takeIf(String::isNotBlank)?.let { imageUrl(it) }.orEmpty(),
            authors = row.optJSONArray("authors")?.bodyObjects()?.map { it.bodyText("name") }?.filter(String::isNotBlank).orEmpty(),
            description = row.bodyText("description"),
            status = when (row.bodyText("status")) { "ONGOING" -> "连载"; "END" -> "完结"; else -> "" },
            tags = row.optJSONArray("categories")?.bodyObjects()?.map { it.bodyText("name") }?.filter(String::isNotBlank).orEmpty(),
            sourceUrl = "$home/comic/$id", sourceId = info.id, sourceMangaId = id,
        )
    }

    private suspend fun graphQl(operation: String, query: String, variables: JSONObject): JSONObject {
        val json = JSONObject().put("operationName", operation).put("query", query).put("variables", variables)
        val request = Request.Builder().url("$home/api/query").header("Referer", "$home/")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36")
            .header("Origin", home).post(json.toString().toRequestBody("application/json".toMediaType())).build()
        val result = http.bodyJson(request, "Komiic", home)
        result.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let { errors ->
            val message = errors.optJSONObject(0)?.bodyText("message").orEmpty()
            val explanation = if (message.contains("token", true) || message.contains("login", true) || message.contains("auth", true))
                "Komiic 要求登录后访问；当前公开来源没有附加账号。" else "Komiic 返回：${message.take(180)}"
            throw SourceException(explanation, home)
        }
        return result.optJSONObject("data") ?: throw SourceException("Komiic 返回的资料不完整。", home)
    }

    private fun imageUrl(value: String): String {
        val url = checkedBodyUrl(value)
        val mirror = home.toHttpUrl().host
        return if (mirror != "komiic.com" && (url.host == "komiic.com" || url.host.endsWith(".komiic.com")))
            url.newBuilder().host(url.host.removeSuffix("komiic.com") + mirror).build().toString()
        else url.toString()
    }

    private fun nativeId(value: String): String = id(SourceKeys.decodeManga(value).mangaId)
    private fun id(value: String): String = value.also { require(it.matches(Regex("[0-9]{1,30}"))) { "Komiic 标识无效。" } }

    companion object {
        val BASE_URLS = listOf("https://komiic.com", "https://komiic.cc")
        private const val COMIC = "{ id title description status imageUrl authors { id name } categories { id name } warnings }"
    }
}
