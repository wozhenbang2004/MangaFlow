package dev.mangaflow.data.sources

import dev.mangaflow.data.MangaRepository
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

interface BodySource : MangaRepository {
    val info: BodySourceInfo
    suspend fun capabilities(): SourceCapabilities = SourceCapabilities()
    suspend fun feeds(): List<SourceFeed> = listOf(SourceFeed("latest", "最近更新", SourceFeedKind.LATEST))
    suspend fun feedFilters(feedId: String): List<SourceFilter> = feeds().firstOrNull { it.id == feedId }?.filters.orEmpty()
    suspend fun browse(request: SourceBrowseRequest): dev.mangaflow.data.MangaPage {
        require(request.feedId == "latest" && request.options.isEmpty()) { "来源不支持这个浏览条件。" }
        return discover(request.page)
    }
    suspend fun searchFiltered(query: String, page: Int, options: List<String>): dev.mangaflow.data.MangaPage {
        require(options.isEmpty()) { "来源不支持这个搜索条件。" }
        return search(query, page)
    }
    suspend fun searchFilters(): List<SourceFilter> = emptyList()
    suspend fun settings(): List<SourceSetting> = emptyList()
    suspend fun saveSetting(key: String, value: String): Unit = unsupportedSourceFeature()
    suspend fun accountState(): SourceAccountState = SourceAccountState()
    suspend fun login(username: String, password: String): Unit = unsupportedSourceFeature()
    suspend fun loginWithCookies(values: List<String>): Unit = unsupportedSourceFeature()
    suspend fun logout(): Unit = unsupportedSourceFeature()
    suspend fun favoriteFolders(): List<SourceFavoriteFolder> = unsupportedSourceFeature()
    suspend fun favorites(folderId: String, page: Int): dev.mangaflow.data.MangaPage = unsupportedSourceFeature()
    suspend fun setFavorite(mangaId: String, folderId: String, favorite: Boolean): Unit = unsupportedSourceFeature()
    suspend fun comments(mangaId: String, page: Int): SourceCommentPage = unsupportedSourceFeature()
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
