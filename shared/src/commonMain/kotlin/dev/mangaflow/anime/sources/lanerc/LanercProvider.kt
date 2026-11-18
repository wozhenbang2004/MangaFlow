package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeResolution
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.AnimeSubtitle
import dev.mangaflow.anime.sources.AnimeProvider
import dev.mangaflow.anime.sources.animeContentIdentity
import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.anime.sources.animeMediaType
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.anime.sources.sourceCover
import dev.mangaflow.anime.sources.sourceEpisodeKind
import dev.mangaflow.anime.sources.sourceEpisodeNumber
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull

internal class LanercProvider(private val store: LanercSourceStore, private val descriptor: LanercSourceDescriptor) : AnimeProvider {
    override val id: String = descriptor.id
    override val name: String = descriptor.name
    override val homeUrl: String = descriptor.homeUrl
    override val revision: String get() = store.snapshot(id)?.revision
        ?: throw LanercCapabilityException("该来源未安装或已停用，请先启用来源。")
    // Enable pagination only where the audited search actually sends a page or offset:
    // ani skip, yzx path, sd pn, AppV7 pg, Cain page, 23 protobuf field 2, LMM85 page path.
    // LMM85's bounded client fallback explicitly ends after page one.
    override val supportsSearchPagination: Boolean get() = id in setOf(
        "anime-lanerc-ani", "anime-lanerc-yzx", "anime-lanerc-sd",
        "anime-lanerc-sx", "anime-lanerc-gugu", "anime-lanerc-manguo", "anime-lanerc-nmeng", "anime-lanerc-07",
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
        val mapped = (0 until episodes.length()).mapNotNull { index ->
            val item = episodes.optJSONObject(index) ?: return@mapNotNull null
            val flag = item.optString("url")
            val label = item.optString("name").trim()
            if (flag.isBlank() || label.isBlank()) return@mapNotNull null
            require(flag.length <= 262_144 && label.length <= 1000) { "来源剧集参数过大。" }
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
        if (mapped.isEmpty()) throw LanercCapabilityException("此条目没有返回可选剧集，可能尚未更新或来源协议已变化。")
        return AnimeSourceCatalog(title.copy(title = detail.optString("name").ifBlank { title.title },
            coverUrl = sourceCover(homeUrl, detail.optString("pic")).ifBlank { title.coverUrl },
            year = lanercYear(detail.optString("year")) ?: title.year), mapped)
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        require(catalog.title.sourceId == id && catalog.episodes.any { it == episode }) { "所选剧集已变化，请刷新后重新选择。" }
        val ticket = episode.playbackData?.let(::JSONObject) ?: throw LanercCapabilityException("该剧集缺少来源播放标记，请刷新剧集。")
        require(ticket.optString("catalogId") == catalog.title.id && ticket.optString("group") == episode.group) { "剧集和线路不匹配，请重新选择。" }
        val reply = store.runtime.call(id, "play", JSONArray(listOf(ticket.getString("flag"))), ticket.getString("revision"))
        val result = reply.value as? JSONObject ?: throw LanercCapabilityException("来源未返回有效的播放结果。")
        if (result.optBoolean("needLogin")) throw LanercCapabilityException("此来源要求先登录账号；当前兼容入口尚未接入它的登录流程。")
        if (result.optBoolean("needBind")) throw LanercCapabilityException("此来源要求先完成验证绑定；当前兼容入口尚未接入该验证流程。")
        if (result.optJSONArray("images") != null || result.optString("type").lowercase() in setOf("comic", "image", "images"))
            throw LanercCapabilityException("来源返回的是漫画图片，不能交给视频播放器。")
        // The pinned Youke script explicitly requests no Referer with this protocol sentinel.
        if (id == "anime-lanerc-yzx" && reply.digest == "6056afbb33ffbc7bbb71e2632a31c66fe6d575389983b4f2076dcae599a14e16" &&
            result.optString("referer") == "never") result.remove("referer")
        val sharedHeaders = lanercHeaders(result)
        val resolutions = result.optJSONArray("resolutions")?.let { list ->
            require(list.length() <= 100) { "来源清晰度选项过多。" }
            (0 until list.length()).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val checked = checkedMedia(url)
                val label = item.optString("name").ifBlank { "清晰度 ${index + 1}" }
                AnimeResolution(animeHash(label + "\n" + animeContentIdentity(checked)), label, checked,
                    lanercMediaType(item.optString("type"), checked), sharedHeaders + lanercHeaders(item))
            }.distinctBy { it.url }
        }.orEmpty()
        val url = result.optString("url").takeIf(String::isNotBlank)?.let(::checkedMedia)
            ?: resolutions.firstOrNull()?.url ?: throw LanercCapabilityException("来源没有提供可播放地址，可重试或选择其他线路。")
        val subtitles = result.optJSONArray("subtitles")?.let { list ->
            require(list.length() <= 64)
            (0 until list.length()).mapNotNull { index ->
                val item = list.optJSONObject(index) ?: return@mapNotNull null
                val address = item.optString("url").takeIf(String::isNotBlank) ?: return@mapNotNull null
                AnimeSubtitle(animeHash(address), checkedMedia(address), item.optString("name").ifBlank { "字幕 ${index + 1}" },
                    item.optString("language").takeIf(String::isNotBlank), item.optString("mimeType").takeIf(String::isNotBlank), lanercHeaders(item))
            }
        }.orEmpty()
        val start = result.optDouble("startMs").takeIf { it.isFinite() && it > 0 }
            ?: result.optDouble("startSec").takeIf { it.isFinite() && it > 0 }?.times(1000)
            ?: result.optDouble("start").takeIf { it.isFinite() && it > 0 }?.times(1000)
        return AnimePlaybackAsset(
            id = "$id:" + animeHash(episode.id + "\n" + episode.edition + "\n" + animeContentIdentity(url)),
            sourceId = id, sourceEpisodeId = episode.id, url = url, route = reply.route,
            title = episode.title, headers = sharedHeaders, mimeType = lanercMediaType(result.optString("type"), url),
            edition = episode.edition, subtitles = subtitles,
            sourceRevision = reply.revision, resolutions = resolutions,
            startPositionMs = start?.takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }?.toLong(),
        )
    }
}

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
        AnimeSourceTitle(descriptor.id, id, name, url, sourceCover(descriptor.homeUrl, item.optString("pic")),
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

private fun checkedMedia(url: String): String = try { animePublicUrl(url).toString() }
    catch (_: Exception) { throw LanercCapabilityException("来源返回的媒体地址不受支持；需要有效的公共 HTTPS 地址。") }
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
private fun lanercYear(value: String): Int? = Regex("(?:19|20)\\d{2}").find(value)?.value?.toIntOrNull()
private fun lanercMediaType(type: String, url: String): String? = when (type.lowercase()) {
    "hls", "m3u8", "application/x-mpegurl", "application/vnd.apple.mpegurl" -> "application/x-mpegURL"
    "mp4", "video/mp4" -> "video/mp4"
    "dash", "mpd", "application/dash+xml" -> "application/dash+xml"
    "flv", "video/x-flv" -> "video/x-flv"
    else -> animeMediaType(url)
}
private fun lanercHeaders(value: JSONObject): Map<String, String> {
    val raw = value.opt("headers")
    val objectValue = when (raw) {
        is JSONObject -> raw
        is String -> if (raw.isBlank()) JSONObject() else try { JSONObject(raw) }
            catch (_: Exception) { throw LanercCapabilityException("来源播放请求头格式不受支持。") }
        else -> JSONObject()
    }
    val headers = objectValue.keys().asSequence().associateWith { objectValue.optString(it) }.toMutableMap()
    value.optString("referer").takeIf(String::isNotBlank)?.let { headers["Referer"] = it }
    headers.keys.filter { it.equals("Referer", true) }.forEach { name -> headers[name] = checkedReferer(headers.getValue(name)) }
    value.optString("userAgent").ifBlank { value.optString("ua") }.takeIf(String::isNotBlank)?.let { headers["User-Agent"] = it }
    require(headers.size <= 64 && headers.entries.sumOf { it.key.length + it.value.length } <= 32_768 &&
        headers.all { (name, text) -> name.matches(Regex("[A-Za-z0-9!#%&'*+.^_`|~-]{1,128}")) &&
            name.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding") &&
            text.none { it == '\r' || it == '\n' || it.code == 0 } }) { "来源播放请求头无效。" }
    return headers
}
