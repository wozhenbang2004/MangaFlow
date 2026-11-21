package dev.mangaflow.anime.sources

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSourceCatalog
import dev.mangaflow.anime.AnimeSourceEpisode
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.anime.sources.lanerc.LanercCapabilityException
import dev.mangaflow.anime.sources.lanerc.decodeLanercBytes
import dev.mangaflow.anime.sources.lanerc.encodeLanercBytes
import dev.mangaflow.anime.sources.lanerc.lanercPlatformCrypto
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.platform.ConcurrentMap
import dev.mangaflow.platform.randomUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.encodeUtf8

/**
 * Independent native adapter for the Youke 3.0 wire contract in https://js.z1i.cn/js/yzx.js,
 * SHA-256 6056afbb33ffbc7bbb71e2632a31c66fe6d575389983b4f2076dcae599a14e16 (2026-11-18).
 * The older community Baiyun 2.5 mirror describes a different package and signing protocol.
 * No remote script or borrowed account/device credential is bundled. AES-GCM calls the
 * existing platform crypto primitive directly; it does not initialize a JavaScript engine.
 */
internal class NativeYzxProvider(override val revision: String) : AnimeProvider {
    override val id = "anime-native-yzx"
    override val name = "yzx（优可）"
    override val homeUrl = DEFAULT_HOST + "/"
    override val supportsLatest = true
    override val supportsSearchPagination = true

    override suspend fun latest(page: Int): List<AnimeSourceTitle> {
        require(page >= 1)
        val http = AnimeHttp(id)
        return try {
            withConfig(http, AnimeSourceStage.SEARCH) { config ->
                val url = animePublicUrl(config.host + "/cache/zhaopian/").newBuilder()
                    .addPathSegment(CHANNEL).addPathSegment("全部").addPathSegment("全部")
                    .addPathSegment("全部").addPathSegment("最新").addPathSegment(page.toString() + ".json").build()
                val rows = read(http, url, AnimeSourceStage.SEARCH) as? JSONArray
                    ?: throw malformed(AnimeSourceStage.SEARCH)
                titles(rows, config.host, filterType = false)
            }
        } finally { http.close() }
    }

    override suspend fun search(query: String, page: Int): List<AnimeSourceTitle> {
        require(query.isNotBlank() && query.length <= 512 && page >= 1)
        val http = AnimeHttp(id)
        return try {
            withConfig(http, AnimeSourceStage.SEARCH) { config ->
                val url = animePublicUrl(config.host + "/vc/api/search/").newBuilder()
                    .addPathSegment(query.trim()).addPathSegment(page.toString() + ".json")
                    .addQueryParameter("version", config.version).build()
                val document = read(http, url, AnimeSourceStage.SEARCH)
                val rows = when (document) {
                    is JSONArray -> document
                    is JSONObject -> document.optJSONObject("data")?.optJSONArray("videos")
                        ?: document.optJSONArray("data") ?: document.optJSONArray("videos")
                    else -> null
                } ?: throw malformed(AnimeSourceStage.SEARCH)
                titles(rows, config.host, filterType = true)
            }
        } finally { http.close() }
    }

    override suspend fun catalog(title: AnimeSourceTitle): AnimeSourceCatalog {
        // A saved binding may predate a source revision; re-read its exact work before playback.
        requireTitle(title, currentRevision = false)
        val http = AnimeHttp(id)
        return try {
            withConfig(http, AnimeSourceStage.CATALOG) { config ->
                val data = detail(http, config, title.id, AnimeSourceStage.CATALOG)
                val episodes = episodes(title.id, data)
                if (episodes.isEmpty()) throw AnimeSourceException(id, AnimeSourceStage.CATALOG,
                    "优可未返回实际可选剧集，未按更新文字或总集数补造。")
                AnimeSourceCatalog(title.copy(
                    title = data.text("videoName").ifEmpty { title.title },
                    coverUrl = sourceCover(config.host + "/", data.text("fengmiantu", "dahengtu")).ifEmpty { title.coverUrl },
                    year = year(data) ?: title.year,
                    updateText = data.text("serialDesc", "remarks").ifEmpty { title.updateText },
                    sourceRevision = revision,
                ), episodes, notice = if (episodes.size < data.requiredArray("playUrlList", id, AnimeSourceStage.CATALOG).length())
                    "部分条目暂未提供播放标识，已保留实际可选剧集。" else null)
            }
        } finally { http.close() }
    }

    override suspend fun resolve(catalog: AnimeSourceCatalog, episode: AnimeSourceEpisode): AnimePlaybackAsset {
        requireTitle(catalog.title)
        require(catalog.episodes.any { it == episode })
        val selected = JSONObject(requireNotNull(episode.playbackData))
        val work = workId(selected.getString("work"))
        val ji = animeRemoteId(selected.getString("ji"))
        val index = selected.getInt("index").also { require(it in 0 until 20_000) }
        require(work == catalog.title.id &&
            episode == sourceEpisode(work, ji, index, selected.getString("group"), selected.getString("label")))
        val http = AnimeHttp(id)
        return try {
            withConfig(http, AnimeSourceStage.RESOLVE) { config ->
                // jiIndex is part of the upstream request. Re-read the actual list before using
                // a saved index so an insertion/reordering cannot silently play a different item.
                val current = episodes(work, detail(http, config, work, AnimeSourceStage.RESOLVE), AnimeSourceStage.RESOLVE)
                    .singleOrNull { it.id == episode.id }
                if (current != episode) throw AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                    "优可剧集或线路已变化，请刷新选集后重新选择。")
                val stream = stream(http, config, work, ji, index)
                checkRoute(http, AnimeSourceStage.RESOLVE)
                AnimePlaybackAsset(
                    id = id + ":" + animeHash(episode.id + "\n" + animeContentIdentity(stream.url)),
                    sourceId = id, sourceEpisodeId = episode.id, url = stream.url, route = http.route,
                    title = episode.title, headers = mapOf("User-Agent" to USER_AGENT),
                    mimeType = stream.mimeType, edition = episode.edition, sourceRevision = revision,
                )
            }
        } finally { http.close() }
    }

    private fun requireTitle(title: AnimeSourceTitle, currentRevision: Boolean = true) {
        require(title.sourceId == id && title.url == detailUrl(title.id))
        if (currentRevision) require(title.sourceRevision == revision)
    }

    private fun workId(value: String): String = value.also {
        require(it.matches(Regex("[1-9][0-9]{0,18}")) && it.toLongOrNull() != null) { "优可条目标识无效。" }
    }

    private fun detailUrl(work: String, host: String = DEFAULT_HOST): String =
        host + "/cache/videos/" + (workId(work).toLong() / 1000) + "/" + work + ".json"

    private fun year(row: JSONObject): Int? = row.text("year").toIntOrNull()?.takeIf { it in 1900..2100 }

    private fun objects(array: JSONArray): List<JSONObject> {
        require(array.length() <= 20_000) { "优可返回的目录过大。" }
        return (0 until array.length()).map(array::getJSONObject)
    }

    private fun titles(array: JSONArray, host: String, filterType: Boolean): List<AnimeSourceTitle> =
        objects(array).filter { !filterType || it.text("typeName") == CHANNEL }.map { row ->
            val work = workId(row.text("videoId"))
            AnimeSourceTitle(
                sourceId = id, id = work, title = row.text("videoName").also { require(it.isNotEmpty()) },
                url = detailUrl(work), coverUrl = sourceCover(host + "/", row.text("fengmiantu", "dahengtu", "xiaohengtu")),
                year = year(row), updateText = row.text("serialDesc", "newchapter"), sourceRevision = revision,
            )
        }.distinctBy { it.id }

    private suspend fun detail(http: AnimeHttp, config: Config, work: String, stage: AnimeSourceStage): JSONObject {
        val url = animePublicUrl(detailUrl(work, config.host))
        val cached = try { read(http, url, stage) as? JSONObject }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        val data = cached?.takeIf { !it.isNull("videoId") } ?: run {
            val versioned = url.newBuilder().addQueryParameter("version", config.version)
                .addQueryParameter("baoming", PACKAGE).addQueryParameter("channel", "fenxiang").build()
            read(http, versioned, stage) as? JSONObject ?: throw malformed(stage)
        }
        require(data.text("videoId") == work) { "优可返回了其他条目的详情。" }
        require(data.text("typeName").let { it.isEmpty() || it == CHANNEL }) { "优可返回的条目不是动漫。" }
        return data
    }

    private fun episodes(work: String, data: JSONObject, stage: AnimeSourceStage = AnimeSourceStage.CATALOG): List<AnimeSourceEpisode> {
        val rows = objects(data.requiredArray("playUrlList", id, stage))
        val notes = rows.map { it.text("beizhu") }.filter(String::isNotEmpty)
        val groupedLines = notes.groupingBy { it }.eachCount().values.any { it > 1 }
        return rows.mapIndexedNotNull { index, row ->
            val rawJi = row.text("ji").ifEmpty { return@mapIndexedNotNull null }
            val ji = animeRemoteId(rawJi)
            val note = row.text("beizhu")
            val label = (if (!groupedLines && note.isNotEmpty() && note != "播放") note else row.text("name"))
                .ifEmpty { "剧集（$ji）" }
            val group = if (groupedLines) note.ifEmpty { "播放" } else "播放"
            sourceEpisode(work, ji, index, group, label)
        }
    }

    private fun sourceEpisode(work: String, ji: String, index: Int, group: String, label: String): AnimeSourceEpisode {
        val flag = JSONObject().put("work", work).put("ji", ji).put("index", index).put("group", group).put("label", label)
        val identity = JSONObject().put("ji", ji).put("index", index).put("group", group).toString()
        val url = animePublicUrl(DEFAULT_HOST + "/vc/api/video/playurl").newBuilder()
            .addQueryParameter("sid", work).addQueryParameter("ji", ji).addQueryParameter("jiIndex", index.toString()).build()
        return AnimeSourceEpisode(
            id = work + ":" + animeHash(identity), title = label, url = url.toString(),
            number = sourceEpisodeNumber(label), kind = sourceEpisodeKind(label), group = group, playbackData = flag.toString(),
        )
    }

    private suspend fun stream(http: AnimeHttp, config: Config, work: String, ji: String, index: Int): Stream {
        var sessionDevice = device(http, config)
        var refreshed = false
        var direct: Stream? = null
        var imageFallback: Stream? = null
        var lastFailure: Exception? = null
        // These are delivery modes for the same selected ji/index, not other episodes or source groups.
        for (mode in listOf(3, 2, 0)) {
            checkRoute(http, AnimeSourceStage.RESOLVE)
            try {
                var result = play(http, config, sessionDevice, work, ji, index, mode)
                if (result.text("code") in setOf("10301", "10303") && !refreshed) {
                    sessionDevice = device(http, config, rejected = sessionDevice)
                    refreshed = true
                    result = play(http, config, sessionDevice, work, ji, index, mode)
                }
                if (result.text("code") != "0") {
                    lastFailure = AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                        "优可暂未允许读取此集播放地址，请稍后重试。")
                    continue
                }
                val data = result.requiredObject("data", id, AnimeSourceStage.RESOLVE)
                val urls = playUrls(data)
                val transport = data.text("yuanType")
                if (transport == "png") {
                    if (imageFallback == null) urls.firstOrNull()?.let { imageFallback = Stream(it, HLS_MIME) }
                    continue
                }
                urls.firstOrNull { animeMediaType(it) == HLS_MIME || transport == "ts" }
                    ?.let { return Stream(it, HLS_MIME) }
                if (direct == null) urls.firstOrNull()?.let { direct = Stream(it, animeMediaType(it)) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { checkRoute(http, AnimeSourceStage.RESOLVE); lastFailure = failure }
        }
        direct?.let { return it }
        // Preserve the upstream's last HLS candidate after ordinary HLS and direct media.
        // yuanType alone does not prove the bytes' layout. Android's existing HLS extractor
        // accepts only a complete PNG/JPEG prefix followed by confirmed TS packets; other
        // platforms keep their own normal HLS handling and honest unsupported-format errors.
        imageFallback?.let { return it }
        throw lastFailure ?: AnimeSourceException(id, AnimeSourceStage.RESOLVE,
            "优可没有返回此集可用的公共视频地址，请重试或更换来源。")
    }

    private suspend fun play(
        http: AnimeHttp, config: Config, device: Device, work: String, ji: String, index: Int, mode: Int,
    ): JSONObject {
        val url = animePublicUrl(config.host + "/vc/api/video/playurl").newBuilder()
            .addQueryParameter("sid", work).addQueryParameter("ji", ji).addQueryParameter("jiIndex", index.toString())
            .addQueryParameter("t", "0").addQueryParameter("y", mode.toString()).addQueryParameter("isjiid", if (ji == "0") "0" else "1")
            .addQueryParameter("androidId", device.code).addQueryParameter("modelName", MODEL_NAME)
            .addQueryParameter("newDeviceCode", device.newCode).addQueryParameter("b", device.b)
            .addQueryParameter("version", config.version).addQueryParameter("baoming", PACKAGE)
            .addQueryParameter("channel", "fenxiang").build()
        val raw = withTimeoutOrNull(8_000L) {
            http.request(url, AnimeSourceStage.RESOLVE, sameOriginRedirects = true,
                headers = mapOf("User-Agent" to USER_AGENT, "vuk" to (work + PLAY_PROTOCOL_KEY).encodeUtf8().md5().hex())).text
        } ?: throw AnimeSourceException(id, AnimeSourceStage.RESOLVE, "优可播放地址响应超时，请重试。")
        checkRoute(http, AnimeSourceStage.RESOLVE)
        return decode(raw, PLAY_PROTOCOL_KEY, AnimeSourceStage.RESOLVE) as? JSONObject
            ?: throw malformed(AnimeSourceStage.RESOLVE)
    }

    private fun playUrls(data: JSONObject): List<String> {
        val alternatives = data.optJSONArray("urlarr")?.let { array ->
            require(array.length() <= 128)
            (0 until array.length()).map { index ->
                when (val value = array.opt(index)) {
                    is String -> value
                    is JSONObject -> value.text("url", "playUrl", "src")
                    else -> ""
                }
            }
        }.orEmpty()
        val primary = listOf(data.text("url"))
        val values = if ((data.optJSONArray("duo")?.length() ?: 0) > 0) alternatives + primary else primary + alternatives
        return values.mapNotNull { raw -> runCatching { animeMediaUrl(raw) }.getOrNull() }
            .filterNot { url ->
                (url.host == "v.trgfd.cn" && url.encodedPath == "/66.m3u8") ||
                    (url.host == "tc.trgfd.cn" && url.encodedPath.startsWith("/123/"))
            }.map { it.toString() }.distinct()
    }

    private suspend fun device(http: AnimeHttp, config: Config, rejected: Device? = null): Device {
        val scope = DeviceScope(http.route, config.host, config.version)
        if (devices.size >= 8 && scope !in devices) devices.keys.firstOrNull()?.let(devices::remove)
        val session = devices.getOrPut(scope) { DeviceSession() }
        return session.mutex.withLock {
            session.value?.let { if (rejected == null || it !== rejected) return@withLock it }
            // Only this application's new random identifier is used. Server-issued values stay
            // in memory and are never replayed to a changed API origin, version or route.
            val payload = JSONObject().put("packageName", PACKAGE).put("baoming", PACKAGE)
                .put("version", config.version).put("channel", "fenxiang").put("uid", "")
                .put("userName", "").put("modelName", MODEL_NAME).put("isOld000", 0)
                .put("deviceCode", session.code).put("newDeviceCode", "")
            val response = withTimeoutOrNull(8_000L) {
                http.json(animePublicUrl(config.host + "/vc/api/device/isnew"), AnimeSourceStage.RESOLVE,
                    method = "POST", payload = payload, sameOriginRedirects = true,
                    headers = mapOf("User-Agent" to USER_AGENT))
            } ?: throw AnimeSourceException(id, AnimeSourceStage.RESOLVE, "优可设备会话响应超时，请重试。")
            val data = response.requiredObject("data", id, AnimeSourceStage.RESOLVE)
            val newCode = sessionValue(data.text("newDeviceCode"))
            val b = sessionValue(data.text("b"))
            if (newCode.isEmpty() || b.isEmpty()) throw AnimeSourceException(id, AnimeSourceStage.RESOLVE,
                "优可未完成本机播放会话，请稍后重试或更换来源。")
            checkRoute(http, AnimeSourceStage.RESOLVE)
            Device(session.code, newCode, b).also { session.value = it }
        }
    }

    private fun sessionValue(value: String): String = value.also {
        require(it.length <= 4096 && it.none { char -> char.code < 0x20 || char.code == 0x7f })
    }

    private suspend fun read(http: AnimeHttp, url: HttpUrl, stage: AnimeSourceStage): Any {
        val body = withTimeoutOrNull(15_000L) {
            http.request(url, stage, sameOriginRedirects = true, headers = mapOf("User-Agent" to USER_AGENT)).text
        } ?: throw AnimeSourceException(id, stage, "优可响应超时，请重试或更换来源。")
        checkRoute(http, stage)
        return decode(body, CONTENT_PROTOCOL_KEY, stage)
    }

    private suspend fun decode(body: String, key: String, stage: AnimeSourceStage): Any = withContext(Dispatchers.Default) {
        try {
            val raw = body.trim().removePrefix("\uFEFF").trimStart()
            val plain = if (raw.startsWith("{") || raw.startsWith("[")) raw else {
                val bytes = decodeLanercBytes(raw, "base64")
                require(bytes.size in 29..8 * 1024 * 1024)
                val request = JSONObject().put("operation", "aes").put("encrypt", false)
                    .put("text", encodeLanercBytes(bytes.copyOfRange(12, bytes.size), "base64")).put("key", key)
                    .put("options", JSONObject().put("mode", "GCM").put("padding", "NoPadding")
                        .put("input", "base64").put("keyFormat", "utf8").put("ivFormat", "hex")
                        .put("iv", encodeLanercBytes(bytes.copyOfRange(0, 12), "hex")).put("tagLen", 128))
                lanercPlatformCrypto(request).decodeToString(throwOnInvalidSequence = true).trim()
            }
            when {
                plain.startsWith("{") -> JSONObject(plain)
                plain.startsWith("[") -> JSONArray(plain)
                else -> throw malformed(stage)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (unsupported: LanercCapabilityException) {
            throw AnimeSourceException(id, stage, "当前平台尚不支持优可所需的响应解码。", cause = unsupported)
        } catch (failure: Exception) {
            throw AnimeSourceException(id, stage, "优可返回的数据无法读取，接口可能已变化，请更新来源或换源。", cause = failure)
        }
    }

    private suspend fun config(http: AnimeHttp, stage: AnimeSourceStage, refresh: Boolean = false): Config {
        val saved = configs[http.route]
        if (!refresh && saved != null) return saved
        val result = try {
            val document = read(http, animePublicUrl(BOOTSTRAP), stage) as? JSONObject ?: throw malformed(stage)
            val rawHost = document.text("textURL").ifEmpty { document.optJSONObject("app")?.text("textURL").orEmpty() }
            val host = animePublicUrl(rawHost).also { require(it.query == null) }.toString().trimEnd('/')
            val channels = document.optJSONArray("qudao")?.let(::objects).orEmpty()
            val version = channels.firstOrNull { it.text("qudaoming") == "fenxiang" }?.text("banben")
                ?.takeIf(String::isNotEmpty) ?: channels.firstOrNull()?.text("banben")?.takeIf(String::isNotEmpty)
                ?: document.text("latestVersion").ifEmpty { DEFAULT_VERSION }
            require(version.matches(Regex("3\\.[0-9A-Za-z._-]{1,30}"))) { "优可接口版本已变化。" }
            Config(host, version)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { saved ?: Config(DEFAULT_HOST, DEFAULT_VERSION) }
        checkRoute(http, stage)
        if (configs.size >= 8 && http.route !in configs) configs.keys.firstOrNull()?.let(configs::remove)
        configs[http.route] = result
        return result
    }

    private suspend fun <T> withConfig(http: AnimeHttp, stage: AnimeSourceStage, action: suspend (Config) -> T): T {
        val current = config(http, stage)
        val result = try { action(current) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            checkRoute(http, stage)
            val updated = config(http, stage, refresh = true)
            if (updated == current) throw failure
            action(updated)
        }
        checkRoute(http, stage)
        return result
    }

    private fun checkRoute(http: AnimeHttp, stage: AnimeSourceStage) {
        if (http.route != DownloadRoutingStore.activeSourceSnapshot(id, DownloadCategory.ANIME))
            throw AnimeSourceException(id, stage, "动漫来源路由已变化，请重新读取。")
    }

    private fun malformed(stage: AnimeSourceStage) =
        AnimeSourceException(id, stage, "优可的数据结构已变化，请更新来源或更换线路。")

    private data class Config(val host: String, val version: String)
    private data class DeviceScope(val route: DownloadRouteSnapshot, val origin: String, val version: String)
    private class Device(val code: String, val newCode: String, val b: String)
    private class DeviceSession {
        val code = randomUuid().replace("-", "").lowercase().take(16)
        val mutex = Mutex()
        var value: Device? = null
    }
    private data class Stream(val url: String, val mimeType: String?)

    companion object {
        private const val CHANNEL = "动漫"
        private const val PACKAGE = "com.youkevideo.app"
        private const val DEFAULT_HOST = "https://js.trgfd.cn"
        private const val DEFAULT_VERSION = "3.0.0"
        private const val BOOTSTRAP = "https://ss.trgfd.cn/cache/index/com.youkevideo.app.json"
        private const val USER_AGENT = "okhttp/4.12.0"
        private const val HLS_MIME = "application/vnd.apple.mpegurl"
        // This public protocol model label is not a hardware identifier from any user's device.
        private const val MODEL_NAME = "Xiaomi 24129PN74C"
        // Public response/signing constants; server-issued device credentials are never embedded.
        private const val CONTENT_PROTOCOL_KEY = "JPT5fjptd0qi1TZZxu53cA7XI2TvQkol"
        private const val PLAY_PROTOCOL_KEY = "8uiju6talkolp98lll7VH7yuDxih7ujh"
        private val configs = ConcurrentMap<DownloadRouteSnapshot, Config>()
        private val devices = ConcurrentMap<DeviceScope, DeviceSession>()
    }
}
