package dev.mangaflow.data.sources

import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.data.MangaPage
import dev.mangaflow.data.normalizedBodySourceAuthor
import dev.mangaflow.data.normalizedBodySourceTitle
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceHttp
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.Request
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject

data class BodySourceInfo(
    val id: String,
    val name: String,
    val language: String,
    val homeUrl: String,
    val iconUrl: String,
    /** Applied adapter revision; imported sources use the installed code/metadata digest. */
    val revision: String = "",
    /** Adult catalogues never enter the ordinary discovery scope. */
    val adult: Boolean = false,
)

/** Opaque, in-memory comparison token; consumers never receive account credentials. */
interface BodySourceProbeIdentity { fun connectionProbeIdentity(): String }

interface BodySource : MangaRepository {
    val info: BodySourceInfo
    val supportsNativeGenres: Boolean get() = false
    suspend fun genreDirectory(): List<SourceChoice> = emptyList()
    suspend fun searchGenres(ids: List<String>, page: Int): MangaPage = unsupportedSourceFeature()
    suspend fun capabilities(): SourceCapabilities = SourceCapabilities()
    suspend fun feeds(): List<SourceFeed> = listOf(SourceFeed("latest", "最近更新", SourceFeedKind.LATEST))
    suspend fun primaryFeed(kind: SourceFeedKind): SourceFeed? = feeds().firstOrNull { it.kind == kind }
    suspend fun feedFilters(feedId: String): List<SourceFilter> = feeds().firstOrNull { it.id == feedId }?.filters.orEmpty()
    suspend fun browse(request: SourceBrowseRequest): dev.mangaflow.data.MangaPage {
        require(request.feedId == "latest" && request.options.isEmpty()) { "来源不支持这个浏览条件。" }
        return discover(request.page)
    }
    suspend fun searchFiltered(query: String, page: Int, options: List<String>): dev.mangaflow.data.MangaPage {
        require(options.isEmpty()) { "来源不支持这个搜索条件。" }
        return search(query, page)
    }
    /**
     * A generic keyword endpoint is not automatically an author/tag index.
     * Fall back to verified metadata in the returned page only; retain upstream paging
     * and never claim a complete site-wide author/tag search for this fallback.
     */
    suspend fun searchTarget(query: String, target: SearchTarget, page: Int): MangaPage {
        if (target == SearchTarget.TITLE) return search(query, page)
        val candidate = query.trim().takeIf { it.length in 1..120 }
            ?: throw SourceException("请输入有效的作者或标签名称。")
        val result = search(candidate, page)
        val normalized = if (target == SearchTarget.AUTHOR)
            normalizedBodySourceAuthor(candidate) else normalizedBodySourceTitle(candidate)
        val matches = result.items.filter { manga ->
            if (target == SearchTarget.AUTHOR) manga.authors.any {
                normalizedBodySourceAuthor(it) == normalized
            } else (manga.tags + manga.tagPaths.keys).any {
                normalizedBodySourceTitle(it) == normalized
            }
        }
        return result.copy(items = matches)
    }
    suspend fun searchFilters(): List<SourceFilter> = emptyList()
    suspend fun hotTags(): List<String> = emptyList()
    suspend fun settings(): List<SourceSetting> = emptyList()
    suspend fun saveSetting(key: String, value: String): Unit = unsupportedSourceFeature()
    suspend fun accountState(): SourceAccountState = SourceAccountState()
    suspend fun login(username: String, password: String): Unit = unsupportedSourceFeature()
    suspend fun register(request: SourceRegistrationRequest): Unit = unsupportedSourceFeature()
    suspend fun loginWithCookies(values: List<String>): Unit = unsupportedSourceFeature()
    suspend fun logout(): Unit = unsupportedSourceFeature()
    suspend fun favoriteFolders(): List<SourceFavoriteFolder> = unsupportedSourceFeature()
    suspend fun favorites(folderId: String, page: Int): dev.mangaflow.data.MangaPage = unsupportedSourceFeature()
    suspend fun setFavorite(mangaId: String, folderId: String, favorite: Boolean): Unit = unsupportedSourceFeature()
    suspend fun setFavoriteForAccount(mangaId: String, folderId: String, favorite: Boolean, expectedAccount: String): Unit =
        unsupportedSourceFeature()
    suspend fun comments(mangaId: String, page: Int): SourceCommentPage = unsupportedSourceFeature()
    suspend fun commentReplies(mangaId: String, parentId: String, page: Int): SourceCommentPage = unsupportedSourceFeature()
}

internal const val BODY_USER_AGENT = "MangaFlow/1.0 (+https://github.com/wozhenbang2004/MangaFlow)"

internal fun JSONArray.bodyObjects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.bodyText(name: String): String = (opt(name) as? String)?.trim().orEmpty()
internal fun JSONObject.bodyId(name: String): String = when (val value = opt(name)) {
    is String -> value.trim()
    is Number -> value.toString()
    else -> ""
}.also { if (it.isBlank()) throw SourceException("来源没有返回有效的$name。") }

internal fun checkedBodyUrl(value: String): HttpUrl = value.toHttpUrlOrNull()
    ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
    ?: throw SourceException("来源返回的图片地址无效。")

internal suspend fun SourceHttp.bodyJson(request: Request, name: String, publicUrl: String): JSONObject {
    val result = this.request(request, name, publicUrl)
    return try {
        JSONObject(result.text)
    } catch (failure: Exception) {
        throw SourceException("$name 返回格式暂不兼容，请更新来源后重试。", publicUrl, cause = failure)
    }
}
