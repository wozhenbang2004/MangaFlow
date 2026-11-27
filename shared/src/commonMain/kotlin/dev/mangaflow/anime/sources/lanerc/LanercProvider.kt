package dev.mangaflow.anime.sources.lanerc

import com.fleeksoft.ksoup.Ksoup
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeResolution
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.AnimeSubtitle
import dev.mangaflow.anime.sources.AnimeHttp
import dev.mangaflow.anime.sources.AnimeProvider
import dev.mangaflow.anime.sources.AnimeSourceException
import dev.mangaflow.anime.sources.AnimeStreamResolver
import dev.mangaflow.anime.sources.animeContentIdentity
import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.anime.sources.animeMediaType
import dev.mangaflow.anime.sources.animeMediaUrl
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.anime.sources.sourceCover
import dev.mangaflow.anime.sources.sourceEpisodeKind
import dev.mangaflow.anime.sources.sourceEpisodeNumber
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.withTimeoutOrNull

internal class LanercProvider(private val store: LanercSourceStore, private val descriptor: LanercSourceDescriptor) : AnimeProvider {
    override val id: String = descriptor.id
    override val name: String = descriptor.name
    override val homeUrl: String = descriptor.homeUrl
    override val revision: String get() = store.snapshot(id)?.revision
        ?: throw LanercCapabilityException("该来源未安装或已停用，请先启用来源。")
    // Enable pagination only where the audited search actually sends a page or offset:
    // ani skip, sd pn, AppV7 pg, Cain page, 23 protobuf field 2, LMM85 page path.
    // LMM85's bounded client fallback explicitly ends after page one.
    override val supportsSearchPagination: Boolean get() = id in APP_V7_SOURCE_IDS || id in setOf(
        "anime-lanerc-ani", "anime-lanerc-sd",
        "anime-lanerc-ain", "anime-lanerc-23", "anime-lanerc-lmm85",
    )

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        require(query.isNotBlank() && query.length <= 500 && page >= 1) { "请输入名称并使用有效页码。" }
        if (page > 1 && !supportsSearchPagination) return emptyList()
        val reply = store.runtime.call(id, "search", JSONArray(listOf(query.trim(), page)))
        return lanercCards(descriptor, reply.value, reply.revision)
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        require(title.sourceId == id && title.id.isNotBlank()) { "条目不属于所选动漫来源。" }
        val reply = store.runtime.call(id, "detail", JSONArray(listOf(title.id)))
        val detail = reply.value as? JSONObject ?: throw LanercCapabilityException("来源详情格式不兼容，请更新来源。")
        val reportedId = detail.optString("id").ifBlank { title.id }
        require(reportedId == title.id) { "来源返回了其他条目的详情，请重新选择。" }
        val episodes = detail.optJSONArray("episodes") ?: JSONArray()
        require(episodes.length() <= 20_000) { "来源剧集数量超出范围。" }
        var lmm85NumericCatalogue = id == "anime-lanerc-lmm85" && reply.digest == descriptor.observedDigest
        var sameWorkRoutes = id in APP_V7_SOURCE_IDS && reply.digest == descriptor.observedDigest &&
            detail.optString("id") == title.id
        val mapped = (0 until episodes.length()).mapNotNull { index ->
            val item = episodes.optJSONObject(index) ?: return@mapNotNull null
            val flag = item.optString("url")
            val label = item.optString("name").trim()
            if (flag.isBlank() || label.isBlank()) return@mapNotNull null
            require(flag.length <= 262_144 && label.length <= 1000) { "来源剧集参数过大。" }
            if (lmm85NumericCatalogue && (!LMM85_EPISODE_FLAG.matches(flag) || flag.substringBefore('_') != title.id))
                lmm85NumericCatalogue = false
            if (sameWorkRoutes && !flag.startsWith("av7:") && !flag.startsWith("av7u:"))
                sameWorkRoutes = false
            val group = item.optString("route").trim().ifBlank { "默认线路" }
            val number = item.optDouble("number").takeIf { it.isFinite() && it >= 0 } ?: sourceEpisodeNumber(label)
            val ticket = JSONObject().put("flag", flag).put("revision", reply.revision)
                .put("catalogId", title.id).put("group", group).toString()
            AnimeSourceEpisode(
                id = "lanerc:" + animeHash(title.id + "\n" + group + "\n" + animeContentIdentity(flag)),
                title = label,
                // Only navigable URLs enter URL fields; the full opaque flag stays provider-owned.
                url = runCatching { animePublicUrl(flag).toString() }.getOrDefault(title.url),
                number = number, kind = sourceEpisodeKind(label), group = group,
                edition = item.optString("edition"), playbackData = ticket,
            )
        }.distinctBy { it.id }
        val description = detail.optString("desc").ifBlank { detail.optString("description") }.trim().take(32_768)
        if (mapped.isEmpty() && detail.optString("name").isBlank() && description.isBlank())
            throw LanercCapabilityException("来源未返回可识别的作品详情或剧集，可能需要更新来源。")
        return AnimeSourceCatalog(title.copy(title = detail.optString("name").ifBlank { title.title },
            coverUrl = sourceCover(homeUrl, lanercArtworkUrl(id, detail.optString("pic"))).ifBlank { title.coverUrl },
            year = lanercYear(detail.optString("year")) ?: title.year,
            episodeCount = detail.optString("episodeCount").toIntOrNull()?.takeIf { it > 0 } ?: title.episodeCount,
            updateText = detail.optString("remarks").trim().ifBlank { title.updateText },
            ratingText = detail.optString("score").trim().ifBlank { title.ratingText },
            externalIds = if (lmm85NumericCatalogue && mapped.isNotEmpty()) title.externalIds + ("lmm85" to title.id) else title.externalIds,
            sourceRevision = reply.revision), mapped,
            notice = if (mapped.isEmpty()) "已读取作品资料，来源暂未返回可选剧集。" else null,
            description = if ('<' in description || '&' in description) Ksoup.parse(description).text() else description,
            sameWorkRoutes = sameWorkRoutes && mapped.isNotEmpty())
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        require(catalog.title.sourceId == id && catalog.episodes.any { it == episode }) { "所选剧集已变化，请刷新后重新选择。" }
        val ticket = episode.playbackData?.let(::JSONObject) ?: throw LanercCapabilityException("该剧集缺少来源播放标记，请刷新剧集。")
        require(ticket.optString("catalogId") == catalog.title.id && ticket.optString("group") == episode.group) { "剧集和线路不匹配，请重新选择。" }
        val flag = ticket.getString("flag")
        val expectedRevision = ticket.getString("revision")
        val reply = try {
            store.runtime.play(id, catalog.title.id, episode.group, flag, expectedRevision)
        } catch (unsupported: LanercMediaSniffingUnsupported) {
            return resolveDeclaredLmm85Media(catalog, episode, flag, expectedRevision) ?: throw unsupported
        }
        val result = reply.value as? JSONObject ?: throw LanercCapabilityException("来源未返回有效的播放结果。")
        if (result.optBoolean("needLogin")) throw LanercCapabilityException("此来源要求先登录账号；当前兼容入口尚未接入它的登录流程。")
        if (result.optBoolean("needBind")) throw LanercCapabilityException("此来源要求先完成验证绑定；当前兼容入口尚未接入该验证流程。")
        if (result.optJSONArray("images") != null || result.optString("type").lowercase() in setOf("comic", "image", "images"))
            throw LanercCapabilityException("来源返回的是漫画图片，不能交给视频播放器。")
        val primary = result.optString("url").takeIf(String::isNotBlank)
        val checkedPrimary = primary?.let { runCatching { checkedMedia(it) } }
        val primaryUrl = checkedPrimary?.getOrNull()
        val primaryAddress = primaryUrl?.toHttpUrlOrNull()
        val checkedSharedHeaders = runCatching { lanercHeaders(result) }
        val sharedHeaders = checkedSharedHeaders.getOrNull().orEmpty()
        val portableHeaders = sharedHeaders.filterKeys { it.lowercase() in LANERC_PORTABLE_MEDIA_HEADERS }
        val resolutions = result.optJSONArray("resolutions")?.let { list ->
            (0 until minOf(list.length(), 100)).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val checked = runCatching { checkedMedia(url) }.getOrNull() ?: return@mapNotNull null
                val variantHeaders = runCatching { lanercHeaders(item) }.getOrNull() ?: return@mapNotNull null
                val address = checked.toHttpUrlOrNull() ?: return@mapNotNull null
                val sameOrigin = primaryAddress?.let {
                    it.scheme == address.scheme && it.host == address.host && it.port == address.port
                } == true
                val inherited = if (sameOrigin) sharedHeaders else portableHeaders
                val overrides = variantHeaders.keys.map { it.lowercase() }.toSet()
                val headers = inherited.filterKeys { it.lowercase() !in overrides } + variantHeaders
                val label = item.optString("name").ifBlank { "清晰度 ${index + 1}" }
                AnimeResolution(animeHash(label + "\n" + animeContentIdentity(checked)), label, checked,
                    lanercMediaType(item.optString("type"), checked), headers)
            }.distinctBy { it.url }
        }.orEmpty()
        if (primary == null && resolutions.isEmpty() && id == "anime-lanerc-sd" && reply.digest == descriptor.observedDigest) {
            val error = when (result.optString("error")) {
                "闪电接口要求滑块校验，本集暂时没有可用线路，请换源" -> "闪电需要站点验证，当前无可用线路。"
                "闪电播放地址不可用" -> "闪电返回的本集地址无法读取。"
                "闪电播放地址为空" -> "闪电目录里有本集，但播放接口没有提供地址。"
                else -> null
            }
            if (error != null) throw LanercCapabilityException(error)
        }
        val resolvedVariant = resolutions.firstOrNull { it.url == primaryUrl }
            ?: resolutions.firstOrNull().takeIf { primaryUrl == null || checkedSharedHeaders.isFailure }
        val url = resolvedVariant?.url ?: checkedPrimary?.getOrThrow()
            ?: throw LanercCapabilityException("来源没有提供可播放地址，可重试或选择其他线路。")
        val subtitles = result.optJSONArray("subtitles")?.let { list ->
            (0 until minOf(list.length(), 64)).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                val address = item.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val checked = runCatching { checkedMedia(address) }.getOrNull() ?: return@mapNotNull null
                val headers = runCatching { lanercHeaders(item) }.getOrNull() ?: return@mapNotNull null
                AnimeSubtitle(animeHash(checked), checked, item.optString("name").ifBlank { "字幕 ${index + 1}" },
                    item.optString("language").takeIf(String::isNotBlank), item.optString("mimeType").takeIf(String::isNotBlank), headers)
            }
        }.orEmpty()
        val start = result.optDouble("startMs").takeIf { it.isFinite() && it > 0 }
            ?: result.optDouble("startSec").takeIf { it.isFinite() && it > 0 }?.times(1000)
            ?: result.optDouble("start").takeIf { it.isFinite() && it > 0 }?.times(1000)
        return AnimePlaybackAsset(
            id = "$id:" + animeHash(episode.id + "\n" + episode.edition + "\n" + animeContentIdentity(url)),
            sourceId = id, sourceEpisodeId = episode.id, url = url, route = reply.route,
            title = episode.title, headers = resolvedVariant?.headers ?: checkedSharedHeaders.getOrThrow(),
            mimeType = resolvedVariant?.mimeType ?: lanercMediaType(result.optString("type").takeIf { url == primaryUrl }.orEmpty(), url),
            edition = episode.edition, subtitles = subtitles,
            sourceRevision = reply.revision, resolutions = resolutions,
            startPositionMs = start?.takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }?.toLong(),
        )
    }

    /** A reviewed LMM85 flag can identify an ordinary page with declared video or player frames. */
    private suspend fun resolveDeclaredLmm85Media(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode,
        flag: String, revision: String): AnimePlaybackAsset? {
        if (id != "anime-lanerc-lmm85" || !LMM85_EPISODE_FLAG.matches(flag) || flag.substringBefore('_') != catalog.title.id)
            return null
        val bundle = store.snapshot(id)?.takeIf { it.revision == revision && it.digest == descriptor.observedDigest } ?: return null
        val options = JSONObject(bundle.optionsJson)
        if (options.optString("site").isNotBlank() || options.optString("playMode").isNotBlank()) return null
        val http = AnimeHttp(id)
        val stream = try {
            withTimeoutOrNull(2_000L) {
                AnimeStreamResolver(http, sameOriginPages = true).resolve("${homeUrl.trimEnd('/')}/play/$flag.html", homeUrl)
            }
        } catch (failure: AnimeSourceException) {
            if (failure.httpStatus != null) throw failure
            null
        } finally { http.close() } ?: return null
        if (!store.isCurrent(bundle)) throw LanercCapabilityException("来源版本或参数已改变，请刷新剧集后重新选择。")
        return AnimePlaybackAsset(
            id = "$id:" + animeHash(episode.id + "\n" + episode.edition + "\n" + animeContentIdentity(stream.url)),
            sourceId = id, sourceEpisodeId = episode.id, url = stream.url, route = http.route, title = episode.title,
            headers = mapOf("Referer" to stream.referer, "User-Agent" to AnimeHttp.USER_AGENT),
            mimeType = stream.mimeType, edition = episode.edition, subtitles = stream.subtitles, sourceRevision = revision,
            resolutions = stream.resolutions,
        )
    }
}

private val LMM85_EPISODE_FLAG = Regex("[0-9]{1,24}_[0-9]{1,8}_[0-9]{1,8}")
private val APP_V7_SOURCE_IDS = setOf(
    "anime-lanerc-sx", "anime-lanerc-gugu", "anime-lanerc-manguo", "anime-lanerc-nmeng", "anime-lanerc-07",
)

/** The Lanerc script appends its image request options to its display URL. */
internal fun lanercArtworkUrl(sourceId: String?, value: String): String = if (sourceId == "anime-lanerc-lanerc")
    value.substringBefore("@Referer=").substringBefore("@Headers=") else value

internal fun lanercCards(descriptor: LanercSourceDescriptor, value: Any?, revision: String): List<AnimeSourceTitle> {
    val list = value as? JSONArray ?: throw LanercCapabilityException("来源列表格式不兼容，请更新来源。")
    require(list.length() <= 2000) { "来源单页结果过多。" }
    require(revision.isNotBlank()) { "来源列表缺少版本标识。" }
    val cards = (0 until list.length()).mapNotNull { index ->
        val item = list.optJSONObject(index) ?: return@mapNotNull null
        val id = item.optString("id").trim()
        val name = item.optString("name").trim()
        if (id.isBlank() || name.isBlank() || id.length > 16_384 || name.length > 1000) return@mapNotNull null
        val url = item.optString("url").ifBlank { id }.let { raw ->
            runCatching { animePublicUrl(raw).toString() }.getOrDefault(descriptor.scriptUrl)
        }
        AnimeSourceTitle(descriptor.id, id, name, url, sourceCover(descriptor.homeUrl, lanercArtworkUrl(descriptor.id, item.optString("pic"))),
            year = lanercYear(item.optString("year")),
            episodeCount = item.opt("episodeCount")?.toString()?.toIntOrNull()?.takeIf { it >= 0 },
            externalIds = if (descriptor.id == "anime-lanerc-douban" && id.toLongOrNull()?.let { it > 0 } == true)
                mapOf("douban" to id) else emptyMap(),
            updateText = item.optString("remarks").trim().ifBlank { item.optString("uptodate").trim() },
            ratingText = item.optString("score").trim(), sourceRevision = revision)
    }.distinctBy { it.id }
    if (list.length() > 0 && cards.isEmpty())
        throw LanercCapabilityException("来源列表缺少有效条目标识或名称，请更新来源。")
    return cards
}

private fun checkedMedia(url: String): String = try { animeMediaUrl(url).toString() }
    catch (_: Exception) { throw LanercCapabilityException("来源返回的媒体地址不受支持；需要有效的公共 HTTP 或 HTTPS 地址。") }
/** Referer is a header value; validating it must not rewrite its declared HTTP scheme. */
private fun checkedReferer(value: String): String = try {
    require(value.none { it.code < 0x20 || it.code in 0x7f..0x9f })
    val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException()
    require('@' !in value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#'))
    // Reuse the source host/userinfo checks on a validation copy, never as a network target.
    animePublicUrl(url.newBuilder().scheme("https").build().toString())
    val host = url.host.removeSurrounding("[", "]").trimEnd('.')
    require(listOf("local", "localhost", "internal", "test", "invalid", "example", "onion", "alt",
        "arpa", "example.com", "example.net", "example.org").none { host == it || host.endsWith(".$it") })
    val parts = host.split('.')
    if (parts.last().all(Char::isDigit) || parts.all { it.startsWith("0x", true) }) {
        val address = parts.map { it.toIntOrNull() }
        require(address.size == 4 && address.all { it != null && it in 0..255 } &&
            parts.indices.all { parts[it] == address[it].toString() })
        val a = address[0]!!; val b = address[1]!!; val c = address[2]!!
        require(!(a == 192 && (b == 0 && c in setOf(0, 2) || b == 88 && c == 99)) &&
            !(a == 198 && (b in 18..19 || b == 51 && c == 100)) &&
            !(a == 203 && b == 0 && c == 113))
    }
    url.newBuilder().fragment(null).build().toString()
} catch (_: Exception) {
    throw LanercCapabilityException("来源播放 Referer 格式不受支持；需要有效的公共 HTTP 或 HTTPS 地址。")
}
private fun checkedOrigin(value: String): String = try {
    val url = value.toHttpUrlOrNull() ?: throw IllegalArgumentException()
    checkedReferer(value)
    require(url.encodedPath == "/" && url.query == null && url.fragment == null)
    url.toString().removeSuffix("/")
} catch (_: Exception) {
    throw LanercCapabilityException("来源播放 Origin 格式不受支持；需要有效的公共 HTTP 或 HTTPS 站点。")
}
private fun lanercYear(value: String): Int? = Regex("(?:19|20)\\d{2}").find(value)?.value?.toIntOrNull()
private fun lanercMediaType(type: String, url: String): String? = when (type.lowercase()) {
    "hls", "m3u8", "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "application/x-mpegURL"
    "mp4", "video/mp4" -> "video/mp4"
    "dash", "mpd", "application/dash+xml" -> "application/dash+xml"
    "flv", "video/x-flv" -> "video/x-flv"
    else -> animeMediaType(url)
}
private val LANERC_PORTABLE_MEDIA_HEADERS = setOf("user-agent", "referer", "origin", "accept", "accept-language")

private fun lanercHeaders(value: JSONObject): Map<String, String> {
    val raw = value.opt("headers")
    val objectValue = when (raw) {
        is JSONObject -> raw
        is String -> if (raw.isBlank()) JSONObject() else try { JSONObject(raw) }
            catch (_: Exception) { throw LanercCapabilityException("来源播放请求头格式不受支持。") }
        else -> JSONObject()
    }
    // HTTP names are case-insensitive. Top-level protocol fields explicitly override the
    // header object, without leaving duplicate Referer/User-Agent keys for transports.
    val headers = linkedMapOf<String, String>()
    objectValue.keys().asSequence().forEach { name -> headers[name.lowercase()] = objectValue.optString(name) }
    value.optString("referer").takeIf(String::isNotBlank)?.let { headers["referer"] = it }
    value.optString("userAgent").ifBlank { value.optString("ua") }.takeIf(String::isNotBlank)?.let { headers["user-agent"] = it }
    headers["referer"]?.let { headers["referer"] = checkedReferer(it) }
    headers["origin"]?.let { headers["origin"] = checkedOrigin(it) }
    require(headers.size <= 64 && headers.entries.sumOf { it.key.length + it.value.length } <= 32_768 &&
        headers.all { (name, text) -> name.matches(Regex("[A-Za-z0-9!#%&'*+.^_`|~-]{1,128}")) &&
            name.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding") &&
            text.none { it == '\r' || it == '\n' || it.code == 0 } }) { "来源播放请求头无效。" }
    return headers
}
