package dev.mangaflow.anime.sources

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.MediaType.Companion.toMediaType
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.NetworkFailureKind
import dev.mangaflow.net.Request
import dev.mangaflow.net.RequestBody.Companion.toRequestBody
import dev.mangaflow.net.networkFailureKind
import dev.mangaflow.net.retryAfterMillis
import dev.mangaflow.platform.ConcurrentMap
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil

enum class AnimeSourceStage { SEARCH, CATALOG, RESOLVE, METADATA, TRACKING, AUTH }

open class AnimeSourceException(
    val sourceId: String,
    val stage: AnimeSourceStage,
    message: String,
    val httpStatus: Int? = null,
    val retryAfterSeconds: Long = 0,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal data class AnimeHttpResponse(val url: HttpUrl, val code: Int, val text: String, val bytes: ByteArray = byteArrayOf())

/**
 * Uses the application's routed, cancellable Ktor transport. One operation keeps one route
 * across redirects, catalogue refreshes and stream extraction. Credentials never cross origins.
 * Successful 204 responses are deliberately supported for collection mutations.
 */
internal class AnimeHttp(
    val sourceId: String,
    val route: DownloadRouteSnapshot = DownloadRoutingStore.activeSourceSnapshot(sourceId, DownloadCategory.ANIME),
) : AutoCloseable {
    private val client = NetworkClient(route = route)

    suspend fun request(
        url: HttpUrl,
        stage: AnimeSourceStage,
        method: String = "GET",
        payload: JSONObject? = null,
        token: String? = null,
        referer: String? = null,
        allowNotFound: Boolean = false,
        sameOriginRedirects: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): AnimeHttpResponse {
        // Source signatures are scoped to one origin, including every followed redirect.
        require(headers.isEmpty() || sameOriginRedirects) { "来源协议请求头只能用于同站点接口。" }
        require(headers.size <= 32 && headers.keys.map { it.lowercase() }.distinct().size == headers.size)
        headers.forEach { (name, value) ->
            require(name.isNotEmpty() && name.length <= 128 && name.all { it.isLetterOrDigit() && it.code < 128 || it in "!#$%&'*+-.^_\u0060|~" })
            require(name.lowercase() !in RESERVED_HEADERS && value.length <= 8192 &&
                value.none { it.code < 0x20 && it != '\t' || it.code == 0x7f }) { "来源协议请求头无效。" }
        }
        var current = animePublicUrl(url.toString())
        val origin = "${current.scheme}://${current.host}:${current.port}"
        var remainingRedirects = 5
        while (true) {
            val pauseKey = "${current.scheme}://${current.host}:${current.port}"
            val remaining = (pauses[pauseKey] ?: 0L) - wallTimeMillis()
            if (remaining > 0) throw AnimeSourceException(sourceId, stage,
                "来源请求过于频繁，请稍后重试。", retryAfterSeconds = ceil(remaining / 1000.0).toLong())
            val request = Request.Builder().url(current)
                .header("User-Agent", if (sourceId == "anime-bangumi") API_USER_AGENT else USER_AGENT)
                .header("Accept", "application/json, text/html;q=0.9, */*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            referer?.let { request.header("Referer", animePublicUrl(it).toString()) }
            token?.let { request.header("Authorization", "Bearer $it") }
            if (method != "GET") request.header("Origin", "${current.scheme}://${current.host}")
            headers.forEach { (name, value) -> request.header(name, value) }
            request.method(method, payload?.toString()?.toRequestBody("application/json".toMediaType()))
            val response = try {
                client.execute(request.build(), maximumBytes = 8L * 1024 * 1024)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: DownloadRouteException) {
                throw failure
            } catch (failure: Exception) {
                val kind = networkFailureKind(failure)
                val detail = when (kind) {
                    NetworkFailureKind.TRANSFER_TIMEOUT, NetworkFailureKind.REQUEST_TIMEOUT -> "读取来源数据超时，请稍后重试"
                    null -> "来源连接或数据读取未完成，请稍后重试"
                    else -> kind.message
                }
                throw AnimeSourceException(sourceId, stage, "$detail。", cause = failure)
            }
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                if (method != "GET" || remainingRedirects-- <= 0)
                    throw AnimeSourceException(sourceId, stage, "来源重定向未完成，请重新获取链接。", response.code)
                val target = response.header("Location")?.let(current::resolve)
                    ?: throw AnimeSourceException(sourceId, stage, "来源返回了无效重定向。", response.code)
                current = animePublicUrl(target.toString())
                if ((token != null || sameOriginRedirects) && "${current.scheme}://${current.host}:${current.port}" != origin)
                    throw AnimeSourceException(sourceId, stage, "受限接口跳转到了其他站点，已停止请求。", response.code)
                continue
            }
            if (response.code == 429 || response.code == 503) {
                val retry = (retryAfterMillis(response.header("Retry-After")) ?: 60_000L).coerceIn(1_000L, 86_400_000L)
                val now = wallTimeMillis()
                val until = pauses.merge(pauseKey, now + retry) { currentUntil, incoming -> maxOf(currentUntil, incoming) }
                throw AnimeSourceException(sourceId, stage,
                    if (response.code == 429) "来源请求过于频繁，请稍后重试。" else "来源暂时繁忙，请稍后重试。",
                    response.code, ceil((until - now) / 1000.0).toLong())
            }
            if (response.code == 404 && allowNotFound)
                return AnimeHttpResponse(current, response.code, response.text(), response.bytes)
            if (!response.isSuccessful || response.code in setOf(209, 210)) {
                val message = when (response.code) {
                    401 -> if (token != null) "账号授权已失效，请重新连接账号。" else "来源要求登录后访问。"
                    403 -> "来源拒绝了请求，可能需要原站验证或当前线路不可用。"
                    404 -> "来源链接不存在，请刷新剧集或手动更正链接。"
                    else -> "来源请求失败（HTTP ${response.code}）。"
                }
                throw AnimeSourceException(sourceId, stage, message, response.code)
            }
            return AnimeHttpResponse(current, response.code, response.text(), response.bytes)
        }
    }

    suspend fun json(
        url: HttpUrl,
        stage: AnimeSourceStage,
        method: String = "GET",
        payload: JSONObject? = null,
        token: String? = null,
        referer: String? = null,
        sameOriginRedirects: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val response = request(url, stage, method, payload, token, referer,
            sameOriginRedirects = sameOriginRedirects, headers = headers)
        return try { withContext(Dispatchers.Default) { JSONObject(response.text) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            throw AnimeSourceException(sourceId, stage, "来源没有返回预期的数据，接口可能已变化或触发了访问验证。", cause = failure)
        }
    }

    override fun close() = client.close()

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        const val API_USER_AGENT = "MangaFlow/1.0 (https://github.com/wozhenbang2004/MangaFlow)"
        private val pauses = ConcurrentMap<String, Long>()
        private val RESERVED_HEADERS = setOf(
            "host", "content-length", "transfer-encoding", "connection", "proxy-connection",
            "keep-alive", "upgrade", "te", "trailer", "proxy-authorization", "proxy-authenticate",
            "authorization", "cookie", "set-cookie", "origin", "referer",
        )
    }
}

/** Public source and CDN URLs only; no credentials, local paths or TLS downgrade. */
internal fun animePublicUrl(value: String): HttpUrl {
    val url = value.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("请输入完整的 HTTPS 来源链接。")
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) { "来源链接必须使用 HTTPS 且不能包含账号密码。" }
    val host = url.host.lowercase().removeSurrounding("[", "]").trimEnd('.')
    require(host.contains('.') && !host.endsWith(".local") && !host.endsWith(".localhost") &&
        !host.endsWith(".internal") && host != "localhost" && !host.contains(':')) { "不能使用本机或内网地址作为动漫来源。" }
    val ipv4 = host.split('.').map { it.toIntOrNull() }
    if (ipv4.size == 4 && ipv4.all { it != null }) {
        val a = ipv4[0]!!; val b = ipv4[1]!!
        require(ipv4.all { it!! in 0..255 } && a !in setOf(0, 10, 127) &&
            !(a == 169 && b == 254) && !(a == 192 && b == 168) &&
            !(a == 172 && b in 16..31) && !(a == 100 && b in 64..127) && a < 224) {
            "不能使用本机或内网地址作为动漫来源。"
        }
    }
    return url.newBuilder().fragment(null).build()
}

internal fun animeAbsolute(base: String, value: String): String =
    animePublicUrl(animePublicUrl(base).resolve(value.trim())?.toString()
        ?: throw IllegalArgumentException("来源返回了无效链接。")).toString()

internal fun animeHash(value: String): String =
    sha256(value.encodeToByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONObject.text(vararg names: String): String =
    names.firstNotNullOfOrNull { optString(it).trim().takeIf(String::isNotEmpty) }.orEmpty()
internal fun JSONObject.positiveInt(key: String): Int? = optInt(key).takeIf { it > 0 }
internal fun JSONObject.finiteNumber(key: String): Double? = optDouble(key).takeIf { it.isFinite() }
internal fun JSONObject.requiredArray(key: String, sourceId: String, stage: AnimeSourceStage): JSONArray =
    optJSONArray(key) ?: throw AnimeSourceException(sourceId, stage, "来源的数据结构已变化，请稍后更新来源或切换线路。")
internal fun JSONObject.requiredObject(key: String, sourceId: String, stage: AnimeSourceStage): JSONObject =
    optJSONObject(key) ?: throw AnimeSourceException(sourceId, stage, "来源的数据结构已变化，请稍后更新来源或切换线路。")
internal fun animeRemoteId(value: String): String = value.trim().also {
    require(it.isNotEmpty() && it.length <= 256 && it.none { char -> char <= ' ' || char in "/?#\\" }) { "来源条目标识无效。" }
}
internal fun animeNumericId(value: String): Int = value.toIntOrNull()?.takeIf { it > 0 }
    ?: throw IllegalArgumentException("请选择正确的远端动漫条目 ID。")
