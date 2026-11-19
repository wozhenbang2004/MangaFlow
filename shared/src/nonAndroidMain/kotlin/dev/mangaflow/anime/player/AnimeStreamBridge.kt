package dev.mangaflow.anime.player

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSubtitle
import dev.mangaflow.anime.downloads.parseAnimeHlsDownload
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.NetworkResponse
import dev.mangaflow.net.Request
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okio.Buffer

internal interface AnimeLoopbackConnection {
    fun read(buffer: ByteArray): Int
    fun write(buffer: ByteArray, offset: Int = 0, count: Int = buffer.size)
    fun close()
}

internal expect class AnimeLoopbackServer(
    scope: CoroutineScope,
    handler: suspend (AnimeLoopbackConnection) -> Unit,
) {
    val port: Int
    fun close()
}

internal data class AnimeBufferedStartup(
    val url: HttpUrl,
    val bytes: ByteArray,
    val contentType: String,
    val validUntil: Long,
)

/**
 * Native backends only receive unguessable loopback URLs. Remote media traffic
 * stays in the same NetworkClient route as source resolution. This is a stream
 * transport, not an offline download or a decoded-frame cache.
 */
internal class AnimeStreamBridge private constructor(
    private val asset: AnimePlaybackAsset,
    parentScope: CoroutineScope,
    private val startup: AnimeBufferedStartup?,
    startupByteLimit: Long?,
) {
    private enum class Kind { AUTO, MANIFEST, MEDIA, KEY, SUBTITLE }
    private data class Resource(
        val url: HttpUrl,
        val headers: Map<String, String>,
        val credentialOrigin: HttpUrl,
        val kind: Kind,
        val variables: Map<String, String> = emptyMap(),
    )
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val client = NetworkClient(acceptCompression = false, route = asset.route)
    private val lock = SynchronizedObject()
    private val warmReads = startupByteLimit?.let(::StartupReads)
    private val resources = linkedMapOf<String, Resource>()
    private val token = randomUuid().replace("-", "") + randomUuid().replace("-", "")
    private lateinit var server: AnimeLoopbackServer
    private var failure: String? = null
    val lastFailure: String? get() = synchronized(lock) { failure }
    private var failureStatus: Int? = null
    val lastFailureStatus: Int? get() = synchronized(lock) { failureStatus }
    private var failureRetryable: Boolean? = null
    val lastFailureRetryable: Boolean? get() = synchronized(lock) { failureRetryable }
    private var attempt = 0L

    fun beginAttempt() = synchronized(lock) {
        attempt++
        failure = null
        failureStatus = null
        failureRetryable = null
    }
    lateinit var mediaUrl: String
        private set
    private val subtitles = linkedMapOf<String, String>()

    fun subtitleUrl(subtitle: AnimeSubtitle): String = requireNotNull(subtitles[subtitle.id])

    fun parkStartupReads() { warmReads?.park() }
    fun promoteStartupReads() { warmReads?.promote() }

    fun close() {
        server.close()
        client.close()
        scope.cancel()
        synchronized(lock) { resources.clear() }
    }

    private fun register(resource: Resource): String = synchronized(lock) {
        checkedUrl(resource.url.toString())
        val existing = resources.entries.firstOrNull { it.value == resource }
        val suffix = when (resource.kind) {
            Kind.MANIFEST -> "playlist.m3u8"
            Kind.KEY -> "key.bin"
            else -> resource.url.encodedPath.substringAfterLast('/').takeIf {
                it.isNotEmpty() && it.length < 120 && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' }
            } ?: "stream"
        }
        val path = existing?.key ?: "/$token/${randomUuid()}/$suffix".also { resources[it] = resource }
        "http://127.0.0.1:${server.port}$path"
    }

    private suspend fun handle(connection: AnimeLoopbackConnection) {
        val output = ResponseWriter(connection)
        val ownedAttempt = synchronized(lock) { attempt }
        try {
            val request = readRequest(connection)
            if (request.method !in setOf("GET", "HEAD")) {
                output.error(405, "Method not allowed")
                return
            }
            val path = request.target.substringBefore('?')
            val resource = synchronized(lock) { resources[path] }
            if (resource == null) {
                output.error(404, "Unknown playback session")
                return
            }
            val range = request.headers["range"]
            require(range == null || Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)").matches(range)) {
                "播放器发送了不支持的 Range 请求"
            }
            val buffered = startup?.takeIf {
                monotonicTimeMillis() < it.validUntil && resource.kind == Kind.MEDIA &&
                    resource.url == it.url && '?' !in request.target
            }
            if (buffered != null) {
                val size = buffered.bytes.size.toLong()
                val requested = if (range == null) 0L to (size - 1L) else animeByteRange(range, size)
                if (requested == null) {
                    output.headers(416, mapOf("Content-Range" to "bytes */$size", "Content-Length" to "0"))
                    return
                }
                val (start, end) = requested
                val count = (end - start + 1L).toInt()
                val headers = linkedMapOf("Content-Type" to buffered.contentType, "Content-Length" to count.toString(),
                    "Accept-Ranges" to "bytes", "Cache-Control" to "private, no-store")
                if (range != null) headers["Content-Range"] = "bytes $start-$end/$size"
                output.headers(if (range == null) 200 else 206, headers)
                if (request.method == "GET") connection.write(buffered.bytes, start.toInt(), count)
                return
            }
            var url = resource.url
            // Native HLS clients may append only these reload parameters.
            request.target.substringAfter('?', "").takeIf { it.isNotEmpty() }?.let { query ->
                val parsed = ("https://localhost/?$query").toHttpUrl()
                val builder = url.newBuilder()
                parsed.queryParameterNames.forEach { name ->
                    require(name in setOf("_HLS_msn", "_HLS_part", "_HLS_skip")) { "无效的播放请求参数" }
                    builder.setQueryParameter(name, parsed.queryParameter(name))
                }
                url = builder.build()
            }
            var redirects = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                warmReads?.admit(0)
                val requestBuilder = Request.Builder().url(url).method(request.method, null)
                    .header("Accept-Encoding", "identity")
                scopedHeaders(resource, url).forEach { (name, value) -> requestBuilder.header(name, value) }
                if (range != null && resource.kind != Kind.MANIFEST) requestBuilder.header("Range", range)
                var redirect: HttpUrl? = null
                var response: NetworkResponse? = null
                var manifest = resource.kind == Kind.MANIFEST
                var classified = manifest || resource.kind in setOf(Kind.KEY, Kind.SUBTITLE, Kind.MEDIA)
                val prefix = Buffer()
                val manifestBody = Buffer()
                client.downloadWithHeaders(
                    requestBuilder.build(), Long.MAX_VALUE,
                    onResponse = { received ->
                        response = received
                        when {
                            received.code in setOf(301, 302, 303, 307, 308) -> {
                                redirect = received.header("Location")?.let(url::resolve)
                                    ?.let { checkedUrl(it.toString()) }
                                    ?: error("播放线路重定向无效")
                                false
                            }
                            received.code !in 200..299 -> {
                                synchronized(lock) {
                                    if (ownedAttempt == attempt) {
                                        failure = "来源返回 HTTP ${received.code}，可刷新线路或选择其他来源"
                                        failureStatus = received.code
                                        failureRetryable = received.code >= 500 || received.code == 408
                                    }
                                }
                                output.error(received.code, "Upstream rejected this resource")
                                false
                            }
                            request.method == "HEAD" -> {
                                output.headers(received.code, responseHeaders(received,
                                    manifest || received.header("Content-Type").orEmpty().contains("mpegurl", true)))
                                false
                            }
                            else -> {
                                val contentType = received.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                                require(contentType != "application/dash+xml") {
                                    "桌面和 iOS 的统一路由目前需要 HLS 或视频文件线路；请更换 DASH 线路"
                                }
                                require(received.header("Content-Encoding")?.let { it.equals("identity", true) } != false) {
                                    "来源未按 Range 播放要求返回原始数据"
                                }
                                require(contentType != "audio/x-scpls") { "当前线路不是受支持的视频清单" }
                                if (contentType.contains("mpegurl")) { manifest = true; classified = true }
                                if (classified && !manifest) output.headers(received.code, responseHeaders(received, false))
                                true
                            }
                        }
                    },
                    onChunk = { bytes, count ->
                        warmReads?.admit(count)
                        if (!classified) {
                            prefix.write(bytes, 0, count)
                            if (prefix.size >= 256) {
                                val sample = prefix.snapshot().utf8().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                                manifest = sample.startsWith("#EXTM3U")
                                require(!sample.startsWith("<?xml") && !sample.startsWith("<MPD")) {
                                    "当前线路返回 XML 清单，请选择 HLS 或视频文件线路"
                                }
                                classified = true
                                if (manifest) manifestBody.writeAll(prefix)
                                else {
                                    output.headers(response!!.code, responseHeaders(response!!, false))
                                    output.bytes(prefix.readByteArray())
                                }
                            }
                        } else if (manifest) {
                            manifestBody.write(bytes, 0, count)
                            require(manifestBody.size <= MAX_MANIFEST_BYTES) { "播放清单超过可解析大小" }
                        } else output.bytes(bytes, count)
                    },
                )
                if (redirect != null) {
                    require(++redirects <= 8) { "播放线路重定向次数过多" }
                    url = redirect!!
                    continue
                }
                if (request.method == "HEAD" || output.finished) return
                if (!classified && prefix.size > 0) {
                    val sample = prefix.snapshot().utf8().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                    manifest = sample.startsWith("#EXTM3U")
                    if (manifest) manifestBody.writeAll(prefix)
                    else {
                        output.headers(response!!.code, responseHeaders(response!!, false))
                        output.bytes(prefix.readByteArray())
                    }
                }
                if (manifest) {
                    require(manifestBody.size <= MAX_MANIFEST_BYTES) { "播放清单超过可解析大小" }
                    val rewritten = rewriteManifest(resource, url, manifestBody.readUtf8()).encodeToByteArray()
                    output.headers(200, mapOf(
                        "Content-Type" to "application/vnd.apple.mpegurl",
                        "Content-Length" to rewritten.size.toString(),
                        "Cache-Control" to "no-store",
                    ))
                    output.bytes(rewritten)
                } else if (!output.started) output.headers(response?.code ?: 502, emptyMap())
                return
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            synchronized(lock) {
                if (ownedAttempt == attempt) {
                    failure = readablePlayerError(error)
                    failureStatus = null
                    failureRetryable = error !is IllegalArgumentException && error !is IllegalStateException
                }
            }
            if (!output.started) runCatching { output.error(502, "Unable to read this playback resource") }
        } finally {
            connection.close()
        }
    }

    /** Serve only the bounded preroll while the extra decoder holds optional admission. */
    private class StartupReads(limit: Long) {
        private enum class Phase { WARMING, PARKED, PROMOTED }
        private val phase = MutableStateFlow(Phase.WARMING)
        private val lock = SynchronizedObject()
        private var remaining = limit

        fun park() = synchronized(lock) { phase.value = Phase.PARKED }
        fun promote() = synchronized(lock) { phase.value = Phase.PROMOTED }

        suspend fun admit(bytes: Int) {
            while (true) {
                val admitted = synchronized(lock) {
                    when (phase.value) {
                        Phase.WARMING -> {
                            check(bytes <= remaining) { "桌面预解码已用完本次媒体字节额度" }
                            remaining -= bytes
                            true
                        }
                        Phase.PARKED -> false
                        Phase.PROMOTED -> true
                    }
                }
                if (admitted) return
                phase.first { it != Phase.PARKED }
            }
        }
    }

    private fun rewriteManifest(resource: Resource, base: HttpUrl, text: String): String {
        require(text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) { "来源返回的内容不是有效 HLS 清单" }
        require(!text.contains("#EXT-X-CONTENT-STEERING:")) { "当前线路使用了内容转向服务，请选择其他线路" }
        val variables = resource.variables.toMutableMap()
        text.lineSequence().filter { it.startsWith("#EXT-X-DEFINE:") }.forEach { line ->
            val attributes = Regex("([A-Z0-9-]+)=\"([^\"]*)\"").findAll(line)
                .associate { it.groupValues[1] to it.groupValues[2] }
            attributes["NAME"]?.let { name -> attributes["VALUE"]?.let { variables[name] = it } }
            attributes["QUERYPARAM"]?.let { name -> base.queryParameter(name)?.let { variables[name] = it } }
        }
        fun local(uri: String, kind: Kind): String {
            if (uri.startsWith("data:")) {
                require(kind == Kind.KEY) { "当前播放器不支持内联的远程清单" }
                return uri
            }
            val expanded = Regex("\\{\\$([A-Za-z0-9_-]+)\\}").replace(uri) {
                variables[it.groupValues[1]] ?: error("播放清单引用了未定义的变量")
            }
            val remote = base.resolve(expanded)?.let { checkedUrl(it.toString()) }
                ?: error("播放清单包含无效的资源地址")
            return register(Resource(remote, resource.headers, resource.credentialOrigin, kind, variables.toMap()))
        }
        var nextIsPlaylist = false
        return text.lineSequence().joinToString("\n") { original ->
            val line = original.trimEnd('\r')
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> { nextIsPlaylist = true; line }
                line.startsWith("#") && line.contains("URI=\"") -> {
                    val kind = when {
                        line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:") -> Kind.KEY
                        line.startsWith("#EXT-X-MEDIA:") || line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") ||
                            line.startsWith("#EXT-X-RENDITION-REPORT:") -> Kind.MANIFEST
                        else -> Kind.AUTO
                    }
                    Regex("URI=\"([^\"]*)\"").replace(line) { match -> "URI=\"${local(match.groupValues[1], kind)}\"" }
                }
                line.isBlank() || line.startsWith("#") -> line
                else -> {
                    val kind = if (nextIsPlaylist) Kind.MANIFEST else Kind.MEDIA
                    nextIsPlaylist = false
                    local(line.trim(), kind)
                }
            }
        }
    }

    private fun scopedHeaders(resource: Resource, target: HttpUrl): Map<String, String> {
        val sameOrigin = resource.credentialOrigin.host == target.host &&
            resource.credentialOrigin.port == target.port && resource.credentialOrigin.scheme == target.scheme
        return resource.headers.filter { (name, _) ->
            name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding",
                "proxy-authorization", "proxy-authenticate", "upgrade", "range", "accept-encoding") &&
                (sameOrigin || name.lowercase() in PUBLIC_HEADERS)
        }
    }

    private data class WarmResponse(val url: HttpUrl, val bytes: ByteArray, val contentType: String?)

    private suspend fun readWarmResource(resource: Resource, maximumBytes: Long): WarmResponse? {
        if (maximumBytes <= 0L) return null
        var url = resource.url
        repeat(9) {
            val body = Buffer()
            val request = Request.Builder().url(url).get().header("Accept-Encoding", "identity")
            scopedHeaders(resource, url).forEach { (name, value) -> request.header(name, value) }
            var accepted = false
            val response = client.downloadWithHeaders(request.build(), maximumBytes, onResponse = { received ->
                val length = received.header("Content-Length")?.toLongOrNull()
                accepted = received.code == 200 && (length == null || length in 1L..maximumBytes) &&
                    received.header("Content-Encoding")?.equals("identity", true) != false
                accepted
            }, onChunk = { bytes, count -> body.write(bytes, 0, count) })
            if (response.code in setOf(301, 302, 303, 307, 308)) {
                url = response.header("Location")?.let(url::resolve)?.let { checkedUrl(it.toString()) } ?: return null
            } else {
                if (!accepted || body.size == 0L ||
                    response.header("Content-Length")?.toLongOrNull()?.let { it != body.size } == true) return null
                return WarmResponse(url, body.readByteArray(), response.header("Content-Type"))
            }
        }
        return null
    }

    private suspend fun warmHls(byteLimit: Long, validUntil: Long): AnimeBufferedStartup? {
        val primary = checkedUrl(asset.url)
        if (asset.mimeType?.contains("mpegurl", true) != true &&
            !primary.encodedPath.endsWith(".m3u8", true) && !primary.encodedPath.endsWith(".m3u", true)) return null
        var resource = Resource(primary, asset.headers, primary, Kind.MANIFEST)
        val playlistBudget = minOf(256 * 1024L, byteLimit / 4L)
        var remaining = byteLimit
        var response = readWarmResource(resource, playlistBudget) ?: return null
        remaining -= response.bytes.size
        var text = response.bytes.decodeToString()
        if (text.contains("#EXT-X-STREAM-INF:")) {
            val rewritten = rewriteManifest(resource, response.url, text)
            val variant = rewritten.lineSequence().firstOrNull { it.startsWith("http://127.0.0.1:") } ?: return null
            val path = variant.substringAfter("http://127.0.0.1:${server.port}")
            resource = synchronized(lock) { resources[path] }?.takeIf { it.kind == Kind.MANIFEST } ?: return null
            response = readWarmResource(resource, minOf(playlistBudget, remaining)) ?: return null
            remaining -= response.bytes.size
            text = response.bytes.decodeToString()
        }
        val segment = parseAnimeHlsDownload(response.url, text).resources.firstOrNull { it.name.startsWith("segment-") }
            ?: return null
        val segmentUrl = checkedUrl(segment.url)
        val media = readWarmResource(resource.copy(url = segmentUrl, kind = Kind.MEDIA), remaining) ?: return null
        if (!isAnimeStartupVideoPrefix(media.bytes)) return null
        return AnimeBufferedStartup(segmentUrl, media.bytes, media.contentType ?: "application/octet-stream", validUntil)
    }

    companion object {
        private const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
        private val PUBLIC_HEADERS = setOf("user-agent", "referer", "origin", "accept", "accept-language")
        suspend fun warmStartup(asset: AnimePlaybackAsset, scope: CoroutineScope, byteLimit: Long,
            validUntil: Long): AnimeBufferedStartup? {
            val bridge = create(asset, scope)
            try { return bridge.warmHls(byteLimit, validUntil) }
            finally { bridge.close() }
        }

        suspend fun create(asset: AnimePlaybackAsset, scope: CoroutineScope, startup: AnimeBufferedStartup? = null,
            startupByteLimit: Long? = null): AnimeStreamBridge {
            var pending: AnimeStreamBridge? = null
            return try {
                withContext(Dispatchers.IO) {
                    val primary = checkedUrl(asset.url)
                    require(asset.mimeType != "application/dash+xml" && !primary.encodedPath.endsWith(".mpd", true)) {
                        "当前平台的统一路由暂不支持 DASH，请选择 HLS 或视频文件线路"
                    }
                    require(!primary.encodedPath.endsWith(".pls", true)) { "当前线路不是受支持的视频文件或 HLS 清单" }
                    val bridge = AnimeStreamBridge(asset, scope, startup, startupByteLimit)
                    try {
                        bridge.server = AnimeLoopbackServer(bridge.scope, bridge::handle)
                        pending = bridge
                        val kind = if (asset.mimeType?.contains("mpegurl", true) == true ||
                            primary.encodedPath.endsWith(".m3u8", true) || primary.encodedPath.endsWith(".m3u", true)) Kind.MANIFEST else Kind.AUTO
                        bridge.mediaUrl = bridge.register(Resource(primary, asset.headers, primary, kind))
                        asset.subtitles.forEach { subtitle ->
                            val url = checkedUrl(subtitle.url)
                            val mayInheritCredentials = primary.host == url.host &&
                                primary.port == url.port && primary.scheme == url.scheme
                            val inherited = asset.headers.filter { (name, _) ->
                                mayInheritCredentials || name.lowercase() in PUBLIC_HEADERS
                            }
                            // Explicit subtitle credentials remain scoped to this URL's
                            // origin; cross-origin primary credentials are not inherited.
                            bridge.subtitles[subtitle.id] = bridge.register(
                                Resource(url, inherited + subtitle.headers, url, Kind.SUBTITLE),
                            )
                        }
                        bridge
                    } catch (failure: Throwable) {
                        if (bridge::server.isInitialized) bridge.server.close()
                        bridge.client.close()
                        bridge.scope.cancel()
                        throw failure
                    }
                }
            } catch (failure: Throwable) {
                pending?.close()
                throw failure
            }
        }
    }
}

private fun checkedUrl(value: String): HttpUrl {
    return animePublicUrl(value)
}

internal data class LoopbackRequest(val method: String, val target: String, val headers: Map<String, String>)

internal fun readRequest(connection: AnimeLoopbackConnection): LoopbackRequest {
    val bytes = Buffer()
    val chunk = ByteArray(2048)
    while (true) {
        val count = connection.read(chunk)
        require(count > 0) { "播放连接已关闭" }
        bytes.write(chunk, 0, count)
        require(bytes.size <= 16 * 1024) { "播放请求头过长" }
        val text = bytes.snapshot().utf8()
        val end = text.indexOf("\r\n\r\n")
        if (end < 0) continue
        val lines = text.substring(0, end).split("\r\n")
        val head = lines.first().split(' ')
        require(head.size == 3 && head[2].startsWith("HTTP/1.") && head[1].startsWith('/')) { "无效的播放请求" }
        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val index = line.indexOf(':')
            require(index > 0) { "无效的播放请求头" }
            val name = line.substring(0, index).trim().lowercase()
            require(name !in headers) { "重复的播放请求头" }
            headers[name] = line.substring(index + 1).trim()
        }
        return LoopbackRequest(head[0], head[1], headers)
    }
}

internal class ResponseWriter(private val connection: AnimeLoopbackConnection) {
    var started = false
        private set
    var finished = false
        private set
    fun headers(status: Int, headers: Map<String, String>) {
        check(!started)
        started = true
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(" Response\r\nConnection: close\r\n")
            headers.forEach { (name, value) ->
                if (value.none { it == '\r' || it == '\n' }) append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        connection.write(header.encodeToByteArray())
    }
    fun bytes(bytes: ByteArray, count: Int = bytes.size) { connection.write(bytes, 0, count) }
    fun error(status: Int, message: String) {
        val bytes = message.encodeToByteArray()
        headers(status, mapOf("Content-Type" to "text/plain", "Content-Length" to bytes.size.toString()))
        this.bytes(bytes)
        finished = true
    }
}

private fun responseHeaders(response: NetworkResponse, manifest: Boolean): Map<String, String> =
    response.headers.filterKeys {
        it.lowercase() in setOf("content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag") &&
            (!manifest || !it.equals("Content-Length", true))
    }
