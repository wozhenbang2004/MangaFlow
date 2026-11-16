@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.anime.danmaku

import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.data.plugins.JavascriptHttpSession
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.deleteSecret
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.io.encoding.Base64

/** Protocol: https://doc.dandanplay.com/open/ and official swagger/v2/swagger.json, read 2026-11-16.
 * App credentials are supplied by this app's owner, never copied from another client or bundled here.
 */
internal class DandanplayRepository(private val context: PlatformContext) {
    suspend fun configured(): Boolean = credentials() != null
    suspend fun configure(appId: String, appSecret: String) = withContext(Dispatchers.IO) {
        require(appId.length in 1..128 && appSecret.length in 1..4096 &&
            (appId + appSecret).none { it.code < 32 || it.code == 127 }) { "请输入有效的弹幕服务应用凭据。" }
        writeSecret(context, SECRET, JSONObject().put("appId", appId).put("secret", appSecret).toString())
    }
    suspend fun clearConfiguration() = withContext(Dispatchers.IO) { deleteSecret(context, SECRET) }

    suspend fun search(query: String, episode: String): AnimeDanmakuSearchResult {
        require(query.trim().length in 2..200 && episode.length <= 30) { "弹幕搜索名称至少需要两个字，集数请使用简短编号。" }
        val url = animePublicUrl(BASE).newBuilder().addPathSegments("api/v2/search/episodes")
            .addQueryParameter("anime", query.trim()).addQueryParameter("v2", "true")
            .apply { if (episode.isNotBlank()) addQueryParameter("episode", episode.trim()) }.build().toString()
        val root = get(url)
        if (root.has("success") && !root.optBoolean("success")) throw AnimeDanmakuException("弹幕服务没有完成搜索，请稍后重试。")
        val animes = root.optJSONArray("animes") ?: throw AnimeDanmakuException("弹幕搜索返回格式已变化。")
        require(animes.length() <= 1000)
        val results = mutableListOf<AnimeDanmakuCandidate>()
        for (index in 0 until animes.length()) {
            val anime = animes.optJSONObject(index) ?: continue
            val animeId = anime.optLong("animeId")
            val title = anime.optString("animeTitle").take(500)
            if (animeId <= 0 || title.isBlank()) continue
            val episodes = anime.optJSONArray("episodes") ?: continue
            require(episodes.length() <= 10_000 && results.size + episodes.length() <= 20_000)
            for (at in 0 until episodes.length()) {
                val item = episodes.optJSONObject(at) ?: continue
                val id = item.optLong("episodeId")
                val label = item.optString("episodeTitle").take(500)
                if (id > 0 && label.isNotBlank()) results += AnimeDanmakuCandidate(id, animeId, title, label)
            }
        }
        return AnimeDanmakuSearchResult(results.distinctBy { it.episodeId }, root.optBoolean("hasMore"))
    }

    suspend fun comments(episodeId: Long): List<AnimeDanmakuComment> {
        require(episodeId > 0)
        val url = animePublicUrl(BASE).newBuilder().addPathSegments("api/v2/comment").addPathSegment(episodeId.toString())
            .addQueryParameter("withRelated", "true").addQueryParameter("chConvert", "0").build().toString()
        val comments = get(url).optJSONArray("comments") ?: throw AnimeDanmakuException("弹幕服务未返回完整的弹幕列表。")
        return parseDandanplayComments(comments)
    }

    private suspend fun credentials(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val raw = readSecret(context, SECRET) ?: return@withContext null
        val value = JSONObject(raw)
        val id = value.optString("appId")
        val secret = value.optString("secret")
        if (id.isBlank() || secret.isBlank()) null else id to secret
    }

    private suspend fun get(address: String): JSONObject {
        val credential = credentials() ?: throw AnimeDanmakuException("请先配置此应用自己的弹弹play凭据，或选择已安装来源的站内弹幕。")
        val route = DownloadRoutingStore.get(context).sourceSnapshot(SOURCE_ID, DownloadCategory.ANIME)
        val transport = JavascriptHttpSession(true, route, 12 * 1024 * 1024)
        return try {
            withTimeout(30_000L) {
                var url = animePublicUrl(address)
                require(url.host == animePublicUrl(BASE).host)
                val timestamp = (wallTimeMillis() / 1000).toString()
                val signature = Base64.encode(sha256((credential.first + timestamp + url.encodedPath + credential.second).encodeToByteArray()))
                var headers = JSONObject().put("Accept", "application/json").put("X-AppId", credential.first)
                    .put("X-Timestamp", timestamp).put("X-Signature", signature)
                repeat(4) { hop ->
                    currentCoroutineContext().ensureActive()
                    if (DownloadRoutingStore.get(context).sourceSnapshot(SOURCE_ID, DownloadCategory.ANIME) != route)
                        throw AnimeDanmakuException("弹幕网络路由已改变，请重新加载。")
                    val reply = JSONObject(transport.request(JSONObject().put("url", url.toString()).put("method", "GET")
                        .put("headers", headers).put("followRedirects", false).toString()))
                    currentCoroutineContext().ensureActive()
                    if (DownloadRoutingStore.get(context).sourceSnapshot(SOURCE_ID, DownloadCategory.ANIME) != route)
                        throw AnimeDanmakuException("弹幕网络路由已改变，请重新加载。")
                    val status = reply.optInt("statusCode")
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        require(hop < 3)
                        val responseHeaders = reply.optJSONObject("headers") ?: JSONObject()
                        val location = responseHeaders.keys().asSequence().firstOrNull { it.equals("Location", true) }
                            ?.let(responseHeaders::optString) ?: throw AnimeDanmakuException("弹幕加速服务没有返回有效地址。")
                        url = animePublicUrl(url.resolve(location)?.toString() ?: throw AnimeDanmakuException("弹幕加速地址无效。"))
                        // The official comment endpoint redirects to a CDN. Never forward app identity/signatures.
                        headers = JSONObject().put("Accept", "application/json")
                    } else {
                        if (status == 401 || status == 403) throw AnimeDanmakuException("弹幕服务未认可此应用凭据，请检查配置与接入状态。")
                        if (status !in 200..299) throw AnimeDanmakuException("弹幕服务请求失败，请稍后重试。")
                        return@withTimeout JSONObject(reply.getString("body"))
                    }
                }
                throw AnimeDanmakuException("弹幕服务重定向过多。")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: AnimeDanmakuException) { throw known }
        catch (_: Exception) { throw AnimeDanmakuException("弹幕请求或数据解析未完成，请检查网络和服务配置。") }
        finally { transport.close() }
    }

    private companion object {
        const val BASE = "https://api.dandanplay.net/"
        const val SOURCE_ID = "anime-danmaku-dandanplay"
        const val SECRET = "anime-danmaku:dandanplay-credentials"
    }
}

/** Official p = seconds, mode, RGB integer, user id; user identifiers are not retained. */
internal fun parseDandanplayComments(list: JSONArray): List<AnimeDanmakuComment> {
    require(list.length() <= 150_000) { "弹幕数量超出本地处理范围。" }
    return (0 until list.length()).mapNotNull { index ->
        val item = list.optJSONObject(index) ?: return@mapNotNull null
        val parts = item.optString("p").split(',')
        if (parts.size < 3) return@mapNotNull null
        val seconds = parts[0].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() / 1000.0 } ?: return@mapNotNull null
        val mode = when (parts[1].toIntOrNull()) {
            1 -> AnimeDanmakuMode.SCROLL; 4 -> AnimeDanmakuMode.BOTTOM; 5 -> AnimeDanmakuMode.TOP
            else -> return@mapNotNull null
        }
        val color = parts[2].toLongOrNull()?.takeIf { it in 0L..0xFFFFFFL }?.toInt() ?: 0xFFFFFF
        val text = item.optString("m").trim().take(500)
        if (text.isBlank()) return@mapNotNull null
        val time = (seconds * 1000).toLong()
        val id = item.optString("cid").ifBlank { animeHash("$time\n$color\n$mode\n$text") }
        AnimeDanmakuComment(id, time, text, color, mode)
    }.distinctBy { it.id }.sortedBy { it.timeMs }
}

/** Lanerc's normalized station protocol uses 0=scroll, 1=top, 2=bottom. */
internal fun parseLanercComments(raw: String): List<AnimeDanmakuComment> {
    val list = JSONArray(raw)
    require(list.length() <= 150_000)
    return (0 until list.length()).mapNotNull { index ->
        val item = list.optJSONObject(index) ?: return@mapNotNull null
        val seconds = item.optDouble("time").takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() / 1000.0 } ?: return@mapNotNull null
        val text = item.optString("text").trim().take(500)
        if (text.isBlank()) return@mapNotNull null
        val mode = when (item.optInt("mode")) {
            0 -> AnimeDanmakuMode.SCROLL; 1 -> AnimeDanmakuMode.TOP; 2 -> AnimeDanmakuMode.BOTTOM
            else -> return@mapNotNull null
        }
        val color = item.optLong("color", 0xFFFFFF).takeIf { it in 0L..0xFFFFFFL }?.toInt() ?: 0xFFFFFF
        val time = (seconds * 1000).toLong()
        AnimeDanmakuComment(animeHash("$time\n$color\n$mode\n$text"), time, text, color, mode)
    }.distinctBy { it.id }.sortedBy { it.timeMs }
}
