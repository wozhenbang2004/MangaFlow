package dev.mangaflow.anime.sources

import com.fleeksoft.ksoup.Ksoup
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeResolution
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.platform.ConcurrentMap
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.encodeUtf8

/**
 * Native wire adapter, independently implemented from the public protocol described by
 * https://js.z1i.cn/js/jinpaiapp.js, SHA-256
 * 6f924a30e23fac63681e3d4d23ee5adb5ad76c10459b2e063d442a7a96286dfe (2026-11-18).
 * This is not a bundled copy of that script and makes no claim about its redistribution license.
 * Uses actual vodId/nid identities, the anime-only API, and all returned quality variants.
 */
internal class NativeJinpaiProvider(override val revision: String) : AnimeProvider {
    override val id = "anime-native-jinpai"
    override val name = "金牌"
    override val homeUrl = HOSTS.first() + "/"
    override val supportsLatest = true
    override val supportsSearchPagination = false

    override suspend fun latest(page: Int): List<AnimeSourceTitle> {
        require(page >= 1)
        val http = AnimeHttp(id)
        return try {
            api(http, AnimeSourceStage.SEARCH, "video/list",
                listOf("type1" to "4", "pageNum" to page.toString(), "area" to "", "year" to "")) { result, host ->
                val rows = result.requiredObject("data", id, AnimeSourceStage.SEARCH)
                    .requiredArray("list", id, AnimeSourceStage.SEARCH)
                titles(rows, host, filterType = false)
            }
        } finally { http.close() }
    }

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        require(query.isNotBlank() && query.length <= 512 && page >= 1)
        // The published keyword endpoint always requests pageNum=1, pageSize=8.
        if (page > 1) return emptyList()
        val http = AnimeHttp(id)
        return try {
            api(http, AnimeSourceStage.SEARCH, "video/searchByWord",
                listOf("keyword" to query.trim(), "pageNum" to "1", "pageSize" to "8")) { result, host ->
                val rows = result.requiredObject("data", id, AnimeSourceStage.SEARCH)
                    .requiredObject("result", id, AnimeSourceStage.SEARCH)
                    .requiredArray("list", id, AnimeSourceStage.SEARCH)
                titles(rows, host, filterType = true)
            }
        } finally { http.close() }
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        // A saved binding may predate a source revision; re-read its exact work before playback.
        requireTitle(title, currentRevision = false)
        val http = AnimeHttp(id)
        return try {
            api(http, AnimeSourceStage.CATALOG, "video/detail", listOf("id" to title.id)) { result, host ->
                val data = result.requiredObject("data", id, AnimeSourceStage.CATALOG)
                require(data.text("vodId").let { it.isEmpty() || it == title.id }) { "金牌返回了其他条目的详情。" }
                val rows = objects(data.requiredArray("episodeList", id, AnimeSourceStage.CATALOG))
                val episodes = rows.mapNotNull { row ->
                    val rawNid = row.text("nid").ifEmpty { return@mapNotNull null }
                    val nid = remoteId(rawNid)
                    val label = clean(row.text("name")).ifEmpty { "剧集（$nid）" }
                    AnimeSourceEpisode(
                        id = episodeId(title.id, nid), title = label, url = episodeUrl(title.id, nid),
                        number = sourceEpisodeNumber(label), kind = sourceEpisodeKind(label), group = GROUP,
                        playbackData = JSONObject().put("work", title.id).put("nid", nid).toString(),
                    )
                }
                require(episodes.map { it.id }.distinct().size == episodes.size) { "金牌返回重复剧集标识，请刷新目录。" }
                if (episodes.isEmpty()) throw AnimeSourceException(id, AnimeSourceStage.CATALOG,
                    "金牌未返回实际可选剧集，未按更新文字或总集数补造。")
                val updated = title.copy(
                    title = clean(data.text("vodName")).ifEmpty { title.title },
                    coverUrl = sourceCover(host + "/", data.text("vodPic")).ifEmpty { title.coverUrl },
                    year = year(data) ?: title.year,
                    updateText = clean(data.text("vodRemarks")).ifEmpty { title.updateText },
                    sourceRevision = revision,
                )
                AnimeSourceCatalog(updated, episodes, notice = if (episodes.size < rows.size)
                    "部分条目暂未提供播放标识，已保留实际可选剧集。" else null)
            }
        } finally { http.close() }
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        requireTitle(catalog.title)
        require(catalog.episodes.any { it == episode })
        val selected = JSONObject(requireNotNull(episode.playbackData))
        val work = remoteId(selected.getString("work"))
        val nid = remoteId(selected.getString("nid"))
        require(work == catalog.title.id && episode.id == episodeId(work, nid) &&
            episode.url == episodeUrl(work, nid) && episode.group == GROUP)
        val http = AnimeHttp(id)
        return try {
            api(http, AnimeSourceStage.RESOLVE, "v2/video/episode/url",
                listOf("id" to work, "nid" to nid)) { result, host ->
                val data = result.requiredObject("data", id, AnimeSourceStage.RESOLVE)
                val headers = mapOf("Origin" to host, "Referer" to host, "User-Agent" to MEDIA_USER_AGENT)
                val resolutions = objects(data.requiredArray("list", id, AnimeSourceStage.RESOLVE))
                    .sortedByDescending(::resolutionRank).mapNotNull { row ->
                        val url = runCatching { animeMediaUrl(row.text("url")).toString() }.getOrNull()
                            ?: return@mapNotNull null
                        val label = row.text("resolutionName").ifEmpty {
                            row.positiveInt("resolution")?.let { it.toString() + "P" } ?: "默认"
                        }
                        AnimeResolution(
                            id = animeHash(label + "\n" + animeContentIdentity(url)),
                            label = label, url = url, mimeType = animeMediaType(url), headers = headers,
                        )
                    }.distinctBy { it.url }
                val best = resolutions.firstOrNull() ?: throw AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                    "金牌未返回此集可用的公共视频地址，请重试或更换来源。")
                AnimePlaybackAsset(
                    id = id + ":" + animeHash(episode.id + "\n" + animeContentIdentity(best.url)),
                    sourceId = id, sourceEpisodeId = episode.id, url = best.url, route = http.route,
                    title = episode.title, headers = headers, mimeType = best.mimeType,
                    edition = episode.edition, sourceRevision = revision, resolutions = resolutions,
                )
            }
        } finally { http.close() }
    }

    private fun requireTitle(title: AnimeSourceTitle, currentRevision: Boolean = true) {
        require(title.sourceId == id && title.url == detailUrl(title.id))
        if (currentRevision) require(title.sourceRevision == revision)
    }

    private fun titles(array: JSONArray, host: String, filterType: Boolean): List<AnimeSourceTitle> =
        objects(array).filter { !filterType || it.optInt("typeId1") == 4 }.map { row ->
            val work = remoteId(row.text("vodId"))
            val label = clean(row.text("vodName")).also { require(it.isNotEmpty()) }
            AnimeSourceTitle(
                sourceId = id, id = work, title = label, url = detailUrl(work),
                coverUrl = sourceCover(host + "/", row.text("vodPic")), year = year(row),
                updateText = clean(row.text("vodRemarks")), sourceRevision = revision,
            )
        }.distinctBy { it.id }

    private fun objects(array: JSONArray): List<JSONObject> {
        require(array.length() <= 20_000) { "金牌返回的目录过大。" }
        return (0 until array.length()).map(array::getJSONObject)
    }

    private fun clean(value: String): String = Ksoup.parseBodyFragment(value).text().trim()
    private fun year(row: JSONObject): Int? =
        sequenceOf(row.text("vodYear"), row.text("vodPubdate").take(4))
            .mapNotNull { it.toIntOrNull()?.takeIf { value -> value in 1900..2100 } }.firstOrNull()
    private fun remoteId(value: String): String = animeRemoteId(value)
    private fun detailUrl(work: String): String =
        apiUrl(HOSTS.first(), "video/detail", listOf("id" to remoteId(work))).toString()
    private fun episodeUrl(work: String, nid: String): String =
        apiUrl(HOSTS.first(), "v2/video/episode/url", listOf("id" to remoteId(work), "nid" to remoteId(nid))).toString()
    private fun episodeId(work: String, nid: String): String = work + ":" + animeHash(nid)

    private fun resolutionRank(row: JSONObject): Long {
        val tier = when (row.text("resolutionName").lowercase()) {
            "4k" -> 6; "蓝光" -> 5; "超清" -> 4; "高清" -> 3; "标清" -> 2; "流畅" -> 1; else -> 0
        }
        return tier * 100_000L + row.optInt("resolution").coerceIn(0, 99_999)
    }

    private fun apiUrl(host: String, path: String, parameters: List<Pair<String, String>>): HttpUrl {
        val builder = animePublicUrl(host + "/").newBuilder().addPathSegments("api/mw-movie/anonymous/" + path)
        parameters.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private suspend fun <T> api(
        http: AnimeHttp,
        stage: AnimeSourceStage,
        path: String,
        parameters: List<Pair<String, String>>,
        decode: (JSONObject, String) -> T,
    ): T {
        val hosts = (listOfNotNull(hostsByRoute[http.route]) + HOSTS).distinct()
        var lastFailure: Exception? = null
        for (host in hosts) {
            checkRoute(http, stage)
            try {
                val result = withTimeoutOrNull(8_000L) {
                    val time = wallTimeMillis().toString()
                    // The signature covers sorted, unescaped parameter values; URL escaping is separate.
                    val raw = parameters.sortedBy { it.first }.joinToString("&") { it.first + "=" + it.second } +
                        "&key=" + PROTOCOL_KEY + "&t=" + time
                    val sign = raw.encodeUtf8().md5().hex().encodeUtf8().sha1().hex()
                    val body = http.json(apiUrl(host, path, parameters), stage, sameOriginRedirects = true,
                        headers = mapOf("sign" to sign, "T" to time, "Deviceid" to "Deviceid", "User-Agent" to "okhttp/3.15"))
                    withContext(Dispatchers.Default) { decode(body, host) }
                }
                checkRoute(http, stage)
                if (result != null) {
                    if (hostsByRoute.size >= 8 && http.route !in hostsByRoute) hostsByRoute.keys.firstOrNull()?.let(hostsByRoute::remove)
                    hostsByRoute[http.route] = host
                    return result
                }
                lastFailure = AnimeSourceException(id, stage, "金牌线路响应超时，请重试或更换来源。")
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                checkRoute(http, stage)
                lastFailure = failure
            }
        }
        throw lastFailure ?: AnimeSourceException(id, stage, "金牌的备用地址暂时不可用，请稍后重试。")
    }

    private fun checkRoute(http: AnimeHttp, stage: AnimeSourceStage) {
        if (http.route != DownloadRoutingStore.activeSourceSnapshot(id, DownloadCategory.ANIME))
            throw AnimeSourceException(id, stage, "动漫来源路由已变化，请重新读取。")
    }

    companion object {
        private const val GROUP = "在线播放"
        // Public protocol constant from the pinned reference, not an account or device credential.
        private const val PROTOCOL_KEY = "cb808529bae6b6be45ecfab29a4889bc"
        private const val MEDIA_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36"
        private val HOSTS = listOf("https://y2s52n7.com", "https://m.hkybqufgh.com", "https://m.sizhengxt.com",
            "https://m.9zhoukj.com", "https://m.jiabaide.cn", "https://www.hkybqufgh.com")
        private val hostsByRoute = ConcurrentMap<DownloadRouteSnapshot, String>()
    }
}
