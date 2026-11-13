package dev.mangaflow.data.sources

import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceHttp
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class BodySourceInfo(
    val id: String,
    val name: String,
    val language: String,
    val homeUrl: String,
    val iconUrl: String,
)

interface BodySource : MangaRepository {
    val info: BodySourceInfo
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
