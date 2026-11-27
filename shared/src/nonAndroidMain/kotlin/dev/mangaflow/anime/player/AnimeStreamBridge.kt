package dev.mangaflow.anime.player

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.limitAnimeHlsMaster
import dev.mangaflow.anime.AnimeSubtitle
import dev.mangaflow.anime.downloads.parseAnimeHlsDownload
import dev.mangaflow.anime.sources.animeMediaUrl
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.NetworkResponse
import dev.mangaflow.net.Request
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.atomicfu.atomic
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
import okio.IOException
import kotlin.time.Instant

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
    cache: AnimeStreamCache?,
    private val preparationOnly: Boolean,
) {
    private enum class Kind { AUTO, MANIFEST, MEDIA, KEY, SUBTITLE }
    private data class Resource(
        val url: HttpUrl,
        val headers: Map<String, String>,
        val credentialOrigin: HttpUrl,
        val kind: Kind,
        val variables: Map<String, String> = emptyMap(),
        val imagePrefixedTransport: Boolean = false,
    )
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val client = NetworkClient(acceptCompression = false, route = asset.route, publicMediaOnly = true)
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
    private class ReadAttempt
    private var attempt = ReadAttempt()
    private val upstreamMediaBytes = atomic(0L)
    val receivedNetworkBytes: Long get() = upstreamMediaBytes.value
    private val startupOriginUntil = atomic(0L)
    private var lastStartupOriginAt: Long? = null
    private val releaseCache = runCatching { cache?.retain(asset) }.getOrNull()
    private val cache = cache.takeIf { releaseCache != null }
    private val bypassedCacheResources = mutableSetOf<String>()

    fun beginAttempt() = synchronized(lock) {
        attempt = ReadAttempt()
        failure = null
        failureStatus = null
        failureRetryable = null
        startupOriginUntil.value = 0L
        lastStartupOriginAt = null
    }

    fun observeStartupOrigin(until: Long) { startupOriginUntil.value = until }

    fun observedRecentStartupOrigin(): Boolean = synchronized(lock) {
        lastStartupOriginAt?.let { monotonicTimeMillis() - it in 0L..3_000L } == true
    }

    private fun observeStartupResponse(ownedAttempt: ReadAttempt) {
        val until = startupOriginUntil.value
        if (until == 0L) return
        val now = monotonicTimeMillis()
        if (now > until) {
            startupOriginUntil.compareAndSet(until, 0L)
            return
        }
        synchronized(lock) { if (ownedAttempt === attempt) lastStartupOriginAt = now }
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
        releaseCache?.invoke()
    }

    private fun register(resource: Resource): String = synchronized(lock) {
        checkedUrl(resource.url.toString())
        val existing = resources.entries.firstOrNull { it.value == resource }
        val filename = resource.url.encodedPath.substringAfterLast('/')
        val suffix = when (resource.kind) {
            Kind.MANIFEST -> "playlist.m3u8"
            Kind.KEY -> "key.bin"
            else -> when {
                resource.imagePrefixedTransport &&
                    filename.substringAfterLast('.', "").lowercase() in setOf("", "png", "jpg", "jpeg", "bin") -> "segment.ts"
                filename.isNotEmpty() && filename.length < 120 &&
                    filename.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } -> filename
                else -> "stream"
            }
        }
        val path = existing?.key ?: "/$token/${randomUuid()}/$suffix".also { resources[it] = resource }
        "http://127.0.0.1:${server.port}$path"
    }

    private suspend fun handle(connection: AnimeLoopbackConnection) {
        val output = ResponseWriter(connection)
        val ownedAttempt = synchronized(lock) { attempt }
        var cacheWriter: AnimeStreamCacheWriter? = null
        fun save(bytes: ByteArray, count: Int = bytes.size) {
            val writer = cacheWriter ?: return
            try { writer.append(bytes, count) }
            catch (_: Exception) { runCatching { writer.close() }; cacheWriter = null }
        }
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
            val requestedRange = request.headers["range"]
            require(requestedRange == null || Regex("bytes=(?:[0-9]+-[0-9]*|-[0-9]+)").matches(requestedRange)) {
                "播放器发送了不支持的 Range 请求"
            }
            // For an independent HLS segment, the normalized response can have a
            // different byte coordinate system. Serve the complete representation.
            val normalize = resource.imagePrefixedTransport
            val range = requestedRange.takeUnless { normalize }
            val resourceKey = resource.url.toString()
            val disk = if (resource.kind in setOf(Kind.MEDIA, Kind.AUTO) && '?' !in request.target &&
                synchronized(lock) { resourceKey !in bypassedCacheResources }) {
                try { cache?.read(asset, resourceKey, range) }
                catch (_: Exception) { synchronized(lock) { bypassedCacheResources += resourceKey }; null }
            } else null
            if (disk != null) {
                try {
                    val headers = linkedMapOf("Content-Type" to disk.contentType,
                        "Content-Length" to (disk.end - disk.start + 1L).toString(),
                        "Accept-Ranges" to "bytes", "Cache-Control" to "private, no-store")
                    if (range != null) headers["Content-Range"] = "bytes ${disk.start}-${disk.end}/${disk.total}"
                    val segment = if (normalize) PlainHlsSegmentResponse(output, headers, request.method) else null
                    if (segment == null) output.headers(if (range == null) 200 else 206, headers)
                    if (request.method == "GET") {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = try { disk.read(buffer) } catch (_: Exception) {
                                synchronized(lock) { bypassedCacheResources += resourceKey }
                                // A response already in flight cannot be replaced. The existing native IO retry
                                // opens a new bridge request, which now bypasses this exact damaged resource.
                                throw IOException("番剧缓存读取失败，将重新读取原始视频")
                            }
                            if (count < 0) break
                            warmReads?.admit(count)
                            if (segment == null) output.bytes(buffer, count) else segment.write(buffer, count)
                        }
                    }
                    segment?.finish()
                } finally { disk.close() }
                return
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
                if (normalize) {
                    val segment = PlainHlsSegmentResponse(output, headers, request.method)
                    if (request.method == "GET") segment.write(buffered.bytes, count)
                    segment.finish()
                } else {
                    output.headers(if (range == null) 200 else 206, headers)
                    if (request.method == "GET") connection.write(buffered.bytes, start.toInt(), count)
                }
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
                var segment: PlainHlsSegmentResponse? = null
                val prefix = Buffer()
                val manifestBody = Buffer()
                client.downloadWithHeaders(
                    requestBuilder.build(), Long.MAX_VALUE,
                    onResponse = { received ->
                        response = received
                        // Only existing origin media GETs count, not address lookup, cache, HEAD,
                        // redirects, subtitles or keys. A closed local adapter is not reachability.
                        if (received.code !in 300..399 && request.method == "GET" &&
                            (resource.kind == Kind.AUTO || resource.kind == Kind.MEDIA || resource.kind == Kind.MANIFEST)) {
                            observeStartupResponse(ownedAttempt)
                        }
                        when {
                            received.code in setOf(301, 302, 303, 307, 308) -> {
                                redirect = checkedRedirect(url, received.header("Location"))
                                false
                            }
                            received.code !in 200..299 -> {
                                synchronized(lock) {
                                    if (ownedAttempt === attempt) {
                                        failure = "来源返回 HTTP ${received.code}，可刷新线路或选择其他来源"
                                        failureStatus = received.code
                                        failureRetryable = received.code >= 500 || received.code == 408
                                    }
                                }
                                output.error(received.code, "Upstream rejected this resource")
                                false
                            }
                            request.method == "HEAD" -> {
                                val forwarded = responseHeaders(received,
                                    manifest || received.header("Content-Type").orEmpty().contains("mpegurl", true))
                                if (normalize) PlainHlsSegmentResponse(output, forwarded, "HEAD").finish()
                                else output.headers(received.code, forwarded)
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
                                if (classified && !manifest) {
                                    val forwarded = responseHeaders(received, false)
                                    if (normalize) {
                                        require(received.code == 200) { "来源没有返回完整的 HLS 媒体段" }
                                        segment = PlainHlsSegmentResponse(output, forwarded, request.method)
                                    } else output.headers(received.code, forwarded)
                                    if (resource.kind == Kind.MEDIA) cacheWriter = runCatching {
                                        cache?.write(asset, resource.url.toString(), received)
                                    }.getOrNull()
                                }
                                true
                            }
                        }
                    },
                    onChunk = { bytes, count ->
                        warmReads?.admit(count)
                        val unclassifiedBytes = prefix.size
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
                                    cacheWriter = runCatching { cache?.write(asset, resource.url.toString(), response!!) }.getOrNull()
                                    val bytes = prefix.readByteArray()
                                    save(bytes)
                                    output.bytes(bytes)
                                }
                            }
                        } else if (manifest) {
                            manifestBody.write(bytes, 0, count)
                            require(manifestBody.size <= MAX_MANIFEST_BYTES) { "播放清单超过可解析大小" }
                        } else {
                            save(bytes, count)
                            val normalized = segment
                            if (normalized == null) output.bytes(bytes, count) else normalized.write(bytes, count)
                        }
                        if (classified && !manifest && count > 0 && (resource.kind == Kind.MEDIA || resource.kind == Kind.AUTO)) {
                            upstreamMediaBytes.addAndGet(unclassifiedBytes + count)
                        }
                    },
                )
                if (redirect != null) {
                    require(++redirects <= 8) { "播放线路重定向次数过多" }
                    url = redirect!!
                    continue
                }
                if (request.method == "HEAD" || output.finished) return
                segment?.finish()
                if (!classified && prefix.size > 0) {
                    val sample = prefix.snapshot().utf8().trimStart('\uFEFF', ' ', '\r', '\n', '\t')
                    manifest = sample.startsWith("#EXTM3U")
                    if (manifest) manifestBody.writeAll(prefix)
                    else {
                        if (resource.kind == Kind.AUTO) upstreamMediaBytes.addAndGet(prefix.size)
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
                if (ownedAttempt === attempt) {
                    failure = readablePlayerError(error)
                    failureStatus = null
                    failureRetryable = error !is IllegalArgumentException && error !is IllegalStateException
                }
            }
            if (!output.started) runCatching { output.error(502, "Unable to read this playback resource") }
        } finally {
            runCatching { cacheWriter?.close() }
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

    private fun rewriteManifest(resource: Resource, base: HttpUrl, upstream: String): String {
        val text = limitAnimeHlsMaster(upstream, asset.preferredAdaptiveHeight)
        require(text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("#EXTM3U")) { "来源返回的内容不是有效 HLS 清单" }
        require(!text.contains("#EXT-X-CONTENT-STEERING:")) { "当前线路使用了内容转向服务，请选择其他线路" }
        if (preparationOnly) {
            val lines = text.lineSequence().map(String::trim).toList()
            val variants = lines.count { it.startsWith("#EXT-X-STREAM-INF:") }
            require(variants <= 1) { "多画质清单不能确认预处理与当前解码使用同一视频轨" }
            require(variants == 1 || "#EXT-X-ENDLIST" in lines) { "在线预处理需要时间轴已封闭的点播片源" }
            require(lines.none { it == "#EXT-X-DISCONTINUITY" || it.startsWith("#EXT-X-GAP") ||
                it.startsWith("#EXT-X-KEY:") && it.substringAfter(':').trim() != "METHOD=NONE" }) {
                "当前片源存在时间轴中断、缺段或加密，原画继续播放"
            }
        }
        val variables = resource.variables.toMutableMap()
        text.lineSequence().filter { it.startsWith("#EXT-X-DEFINE:") }.forEach { line ->
            val attributes = Regex("([A-Z0-9-]+)=\"([^\"]*)\"").findAll(line)
                .associate { it.groupValues[1] to it.groupValues[2] }
            attributes["NAME"]?.let { name -> attributes["VALUE"]?.let { variables[name] = it } }
            attributes["QUERYPARAM"]?.let { name -> base.queryParameter(name)?.let { variables[name] = it } }
        }
        // Observe the original playlist before registering the segment representation.
        // Byte-range, fragmented MP4, encrypted and low-latency resources use raw bytes.
        val inspectPlainSegments = usesImagePrefixedAnimeHls(asset.sourceId) && isIndependentAnimeHlsSegmentPlaylist(text)
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
            return register(Resource(remote, resource.headers, resource.credentialOrigin, kind, variables.toMap(),
                imagePrefixedTransport = inspectPlainSegments && kind == Kind.MEDIA))
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

    private suspend fun readWarmResource(resource: Resource, maximumBytes: Long, audio: Boolean = false): WarmResponse? {
        if (maximumBytes <= 0L) return null
        fun verified(bytes: ByteArray) = resource.kind !in setOf(Kind.MEDIA, Kind.AUTO) ||
            isAnimeStartupMediaPrefix(bytes, audio = audio, imagePrefixedTransport = resource.imagePrefixedTransport)
        val stored = runCatching { cache?.read(asset, resource.url.toString(), null) }.getOrNull()
        if (stored != null) {
            try {
                if (stored.total > maximumBytes) return null
                val body = Buffer()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = stored.read(chunk)
                    if (count < 0) break
                    body.write(chunk, 0, count)
                }
                val bytes = body.readByteArray()
                if (bytes.size.toLong() != stored.total || !verified(bytes)) return null
                return WarmResponse(resource.url, bytes, stored.contentType)
            } finally { stored.close() }
        }
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
                url = checkedRedirect(url, response.header("Location"))
            } else {
                if (!accepted || body.size == 0L ||
                    response.header("Content-Length")?.toLongOrNull()?.let { it != body.size } == true) return null
                val bytes = body.readByteArray()
                if (!verified(bytes)) return null
                if (resource.kind in setOf(Kind.MEDIA, Kind.AUTO)) {
                    // A fully received chunked resource has a known byte length even without an upstream header.
                    val complete = response.copy(headers = response.headers.filterKeys { !it.equals("Content-Length", true) } +
                        ("Content-Length" to bytes.size.toString()))
                    runCatching { cache?.write(asset, resource.url.toString(), complete)?.let { writer ->
                        try { writer.append(bytes, bytes.size) } finally { writer.close() }
                    } }
                }
                return WarmResponse(url, bytes, response.header("Content-Type"))
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
        val text = response.bytes.decodeToString()
        if (text.contains("#EXT-X-STREAM-INF:")) {
            val rewritten = rewriteManifest(resource, response.url, text)
            val variant = rewritten.lineSequence().firstOrNull { it.startsWith("http://127.0.0.1:") } ?: return null
            val path = variant.substringAfter("http://127.0.0.1:${server.port}")
            resource = synchronized(lock) { resources[path] }?.takeIf { it.kind == Kind.MANIFEST } ?: return null
            response = readWarmResource(resource, minOf(playlistBudget, remaining)) ?: return null
            remaining -= response.bytes.size
        }
        val mediaPlaylist = response.bytes.decodeToString()
        val segment = parseAnimeHlsDownload(response.url, mediaPlaylist).resources.firstOrNull { it.name.startsWith("segment-") }
            ?: return null
        val segmentUrl = checkedUrl(segment.url)
        val media = readWarmResource(resource.copy(url = segmentUrl, kind = Kind.MEDIA,
            imagePrefixedTransport = usesImagePrefixedAnimeHls(asset.sourceId) &&
                isIndependentAnimeHlsSegmentPlaylist(mediaPlaylist)), remaining) ?: return null
        return AnimeBufferedStartup(segmentUrl, media.bytes, media.contentType ?: "application/octet-stream", validUntil)
    }

    /** A duration is reported only after every required video/init/default-audio resource is resident. */
    private suspend fun warmRange(request: AnimeStartupCacheRequest): AnimeStartupCacheResult {
        val owner = cache ?: return AnimeStartupCacheResult(0L, detail = "当前平台没有可复用的原视频磁盘缓存")
        fun current() = monotonicTimeMillis() < request.validUntil &&
            asset.expiresAt?.let { it > dev.mangaflow.platform.wallTimeMillis() } != false
        if (!current() || request.maximumBytes <= 0L || request.positionMs < 0L || request.targetDurationMs <= 0L)
            return AnimeStartupCacheResult(0L)
        val primary = checkedUrl(asset.url)
        val hls = asset.mimeType?.contains("mpegurl", true) == true || primary.encodedPath.endsWith(".m3u8", true) ||
            primary.encodedPath.endsWith(".m3u", true)
        if (!hls) {
            if (request.positionMs != 0L) return AnimeStartupCacheResult(0L,
                detail = "视频文件的续播位置由原生预解码读取；没有按文件大小推算缓存秒数")
            val range = "bytes=0-${request.maximumBytes - 1L}"
            val initial = owner.read(asset, primary.toString(), range)
            if (initial != null) {
                try {
                    val prefix = Buffer()
                    val chunk = ByteArray(ANIME_STARTUP_MEDIA_PREFIX_BYTES)
                    while (prefix.size < ANIME_STARTUP_MEDIA_PREFIX_BYTES) {
                        currentCoroutineContext().ensureActive()
                        val count = initial.read(chunk)
                        if (count < 0) break
                        prefix.write(chunk, 0, count)
                    }
                    return if (isAnimeStartupMediaPrefix(prefix.readByteArray()))
                        AnimeStartupCacheResult(initial.end - initial.start + 1L)
                    else AnimeStartupCacheResult(0L, detail = "已有文件片段的媒体头无法核对，交由播放器准备")
                }
                finally { initial.close() }
            }
            var url = primary
            repeat(9) {
                var writer: AnimeStreamCacheWriter? = null
                var accepted = false
                var representation: NetworkResponse? = null
                val prefix = Buffer()
                var verified = false
                fun writeVerifiedPrefix() {
                    val bytes = prefix.readByteArray()
                    require(isAnimeStartupMediaPrefix(bytes)) { "未识别到可预读的视频文件，未写入准备缓存" }
                    verified = true
                    writer = owner.write(asset, primary.toString(), checkNotNull(representation))
                    writer?.append(bytes, bytes.size)
                }
                try {
                    val builder = Request.Builder().url(url).get().header("Accept-Encoding", "identity").header("Range", range)
                    scopedHeaders(Resource(primary, asset.headers, primary, Kind.MEDIA), url).forEach { (name, value) -> builder.header(name, value) }
                    val response = client.downloadWithHeaders(builder.build(), request.maximumBytes, onResponse = { response ->
                        val length = response.header("Content-Length")?.toLongOrNull()
                        accepted = response.code in setOf(200, 206) && length != null && length in 1L..request.maximumBytes &&
                            response.header("Content-Encoding")?.equals("identity", true) != false
                        if (accepted) representation = response
                        accepted
                    }, onChunk = { bytes, count ->
                        check(current()) { "预读身份已过期" }
                        if (verified) writer?.append(bytes, count)
                        else {
                            prefix.write(bytes, 0, count)
                            if (prefix.size >= ANIME_STARTUP_MEDIA_PREFIX_BYTES) writeVerifiedPrefix()
                        }
                    })
                    if (accepted && !verified && prefix.size > 0L) writeVerifiedPrefix()
                    writer?.close(); writer = null
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        url = checkedRedirect(url, response.header("Location"))
                    } else {
                        val retained = (if (accepted && current()) owner.read(asset, primary.toString(), range) else null)
                            ?: return AnimeStartupCacheResult(0L, detail = "原视频字节未进入可复用缓存")
                        try { return AnimeStartupCacheResult(retained.end - retained.start + 1L) }
                        finally { retained.close() }
                    }
                } finally { writer?.close() }
            }
            return AnimeStartupCacheResult(0L)
        }
        var remaining = request.maximumBytes
        var bytes = 0L
        var resource = Resource(primary, asset.headers, primary, Kind.MANIFEST)
        suspend fun playlist(value: Resource): WarmResponse? {
            if (!current()) return null
            val allowance = minOf(256L * 1024L, remaining)
            remaining -= allowance
            val response = readWarmResource(value, allowance) ?: return null
            remaining += allowance - response.bytes.size
            return response
        }
        var response = playlist(resource) ?: return AnimeStartupCacheResult(0L)
        val text = limitAnimeHlsMaster(response.bytes.decodeToString(), asset.preferredAdaptiveHeight)
        val lines = text.trimStart('\uFEFF').lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.firstOrNull() != "#EXTM3U")
            return AnimeStartupCacheResult(0L, detail = "来源没有返回可预读的 HLS 清单")
        if (lines.any { it.startsWith("#EXT-X-SESSION-KEY:") || it.startsWith("#EXT-X-CONTENT-STEERING:") })
            return AnimeStartupCacheResult(0L, detail = "当前 HLS 含受保护内容或动态分流，交由播放器准备")
        var audio: Resource? = null
        var exactNativeSelection = true
        if (text.contains("#EXT-X-STREAM-INF:")) {
            val variants = lines.withIndex().filter { it.value.startsWith("#EXT-X-STREAM-INF:") }
            if (variants.size != 1) return AnimeStartupCacheResult(0L,
                detail = "多画质主清单由播放器选择；当前不把任意画质的片段算作下一集已备")
            fun attribute(line: String, name: String) = Regex("(?:^|,)$name=\"([^\"]+)\"")
                .find(line.substringAfter(':'))?.groupValues?.get(1)
            val variant = variants.single()
            if (attribute(variant.value, "VIDEO") != null)
                return AnimeStartupCacheResult(0L, detail = "当前 HLS 使用独立视频轨，交由播放器准备")
            attribute(variant.value, "AUDIO")?.let { group ->
                val candidates = lines.filter { it.startsWith("#EXT-X-MEDIA:") &&
                    it.contains("TYPE=AUDIO") && attribute(it, "GROUP-ID") == group }
                if (candidates.size > 1) exactNativeSelection = false
                val selected = candidates.filter { it.contains("DEFAULT=YES") }.singleOrNull()
                    ?: candidates.singleOrNull()
                    ?: return AnimeStartupCacheResult(0L, detail = "未能确定下一集的默认音轨")
                attribute(selected, "URI")?.let { uri ->
                    val url = response.url.resolve(uri)?.let { checkedUrl(it.toString()) } ?: return AnimeStartupCacheResult(0L)
                    audio = resource.copy(url = url)
                }
            }
            val uri = lines.getOrNull(variant.index + 1)?.takeUnless { it.startsWith('#') }
                ?: return AnimeStartupCacheResult(0L)
            val url = response.url.resolve(uri)?.let { checkedUrl(it.toString()) } ?: return AnimeStartupCacheResult(0L)
            resource = resource.copy(url = url)
            response = playlist(resource) ?: return AnimeStartupCacheResult(0L)
        }
        val resident = mutableSetOf<String>()
        suspend fun retain(media: Resource, audio: Boolean = false): Boolean {
            if (!current()) return false
            if (media.url.toString() in resident) return true
            // Failed/invalid reads may already have consumed data. Do not refund
            // unknown bytes and spend the same allowance again on another track.
            val allowance = remaining
            remaining = 0L
            val body = readWarmResource(media, allowance, audio) ?: return false
            remaining = allowance - body.bytes.size
            val actual = owner.read(asset, media.url.toString(), null) ?: return false
            try {
                if (actual.total != body.bytes.size.toLong()) return false
                bytes += actual.total
                resident += media.url.toString()
                return true
            } finally { actual.close() }
        }
        suspend fun mediaWindow(resource: Resource, response: WarmResponse, positionMs: Long,
            audio: Boolean = false): Long {
            val mediaPlaylist = response.bytes.decodeToString()
            val plan = parseAnimeHlsDownload(response.url, mediaPlaylist)
            val inspectImagePrefix = usesImagePrefixedAnimeHls(asset.sourceId) &&
                isIndependentAnimeHlsSegmentPlaylist(mediaPlaylist)
            val byName = plan.resources.associateBy { it.name }
            var initialization: String? = null
            var cursor = 0L
            var duration = 0L
            var preparedUntil = positionMs
            val targetEnd = positionMs + minOf(request.targetDurationMs, Long.MAX_VALUE - positionMs)
            for (line in plan.playlist.lineSequence()) {
                when {
                    // The download plan preserves discontinuities but does not map their
                    // native timestamps. Only the preceding continuous range is proved.
                    line == "#EXT-X-DISCONTINUITY" -> return (preparedUntil - positionMs).coerceAtLeast(0L)
                    line.startsWith("#EXT-X-MAP:") -> initialization = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                    line.startsWith("#EXTINF:") -> duration = (line.substringAfter(':').substringBefore(',').toDouble() * 1000).toLong()
                    line.isNotBlank() && !line.startsWith('#') -> {
                        if (duration <= 0L || duration > Long.MAX_VALUE - cursor)
                            return (preparedUntil - positionMs).coerceAtLeast(0L)
                        val end = cursor + duration
                        if (end > positionMs && cursor < targetEnd) {
                            if (initialization != null) {
                                val init = byName[initialization] ?: return preparedUntil - positionMs
                                if (!retain(resource.copy(url = checkedUrl(init.url), kind = Kind.MEDIA))) return preparedUntil - positionMs
                            }
                            val segment = byName[line] ?: return preparedUntil - positionMs
                            if (!retain(resource.copy(url = checkedUrl(segment.url), kind = Kind.MEDIA,
                                    imagePrefixedTransport = inspectImagePrefix), audio)) return preparedUntil - positionMs
                            preparedUntil = end
                        }
                        cursor = end
                        if (cursor >= targetEnd) break
                    }
                }
            }
            return (preparedUntil - positionMs).coerceAtLeast(0L)
        }
        // Read both manifests before assigning their bounded media-byte allowance.
        // A rendition's EXTINF sum alone is not a shared audio/video time origin.
        val audioList = audio?.let { playlist(it) }
        val audioPosition = audioList?.let { track -> shiftedStartupPosition(request.positionMs,
            startupTimelineOrigin(response.bytes.decodeToString()), startupTimelineOrigin(track.bytes.decodeToString())) }
        val alignedAudio = audio == null || audioPosition != null
        var duration = mediaWindow(resource, response, request.positionMs)
        if (audio != null) {
            val track = checkNotNull(audio)
            duration = minOf(duration, if (audioList == null) 0L else
                mediaWindow(track, audioList, audioPosition ?: request.positionMs, audio = true))
        }
        return if (current()) AnimeStartupCacheResult(bytes, duration.takeIf { exactNativeSelection && alignedAudio },
            when {
                !alignedAudio -> "已缓存候选原片；分离音轨的共同时间轴尚不能核对，实际续看位置交由首帧预解码确认"
                !exactNativeSelection -> "已缓存候选音轨；实际选轨由播放器决定，可播放时长仍未知"
                duration < request.targetDurationMs -> "实际已备范围受连续时间轴、字节额度或可用存储限制"
                else -> null
            })
        else AnimeStartupCacheResult(0L, detail = "预读身份已过期")
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

        suspend fun warmStartup(request: AnimeStartupCacheRequest, scope: CoroutineScope,
            cache: AnimeStreamCache): AnimeStartupCacheResult {
            val bridge = create(request.asset, scope, cache = cache)
            try { return bridge.warmRange(request) }
            finally { bridge.close() }
        }

        suspend fun create(asset: AnimePlaybackAsset, scope: CoroutineScope, startup: AnimeBufferedStartup? = null,
            startupByteLimit: Long? = null, cache: AnimeStreamCache? = null,
            preparationOnly: Boolean = false): AnimeStreamBridge {
            var pending: AnimeStreamBridge? = null
            return try {
                withContext(Dispatchers.IO) {
                    val primary = checkedUrl(asset.url)
                    require(asset.mimeType != "application/dash+xml" && !primary.encodedPath.endsWith(".mpd", true)) {
                        "当前平台的统一路由暂不支持 DASH，请选择 HLS 或视频文件线路"
                    }
                    require(!primary.encodedPath.endsWith(".pls", true)) { "当前线路不是受支持的视频文件或 HLS 清单" }
                    val bridge = AnimeStreamBridge(asset, scope, startup, startupByteLimit, cache, preparationOnly)
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
                        bridge.releaseCache?.invoke()
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

/** A date before the first segment describes the playlist's actual zero point. */
private fun startupTimelineOrigin(text: String): Long? {
    for (raw in text.trimStart('\uFEFF').lineSequence()) {
        val line = raw.trim()
        if (line.startsWith("#EXT-X-PROGRAM-DATE-TIME:"))
            return runCatching { Instant.parse(line.substringAfter(':')).toEpochMilliseconds() }.getOrNull()
        if (line.isNotBlank() && !line.startsWith('#')) return null
    }
    return null
}

private fun shiftedStartupPosition(positionMs: Long, videoOrigin: Long?, audioOrigin: Long?): Long? {
    if (videoOrigin == null || audioOrigin == null) return null
    if (audioOrigin > 0L && videoOrigin < Long.MIN_VALUE + audioOrigin ||
        audioOrigin < 0L && videoOrigin > Long.MAX_VALUE + audioOrigin) return null
    val shift = videoOrigin - audioOrigin
    if (shift > 0L && positionMs > Long.MAX_VALUE - shift) return null
    return (positionMs + shift).takeIf { it >= 0L }
}

private fun checkedUrl(value: String): HttpUrl {
    return animeMediaUrl(value)
}

private fun checkedRedirect(origin: HttpUrl, location: String?): HttpUrl {
    val target = location?.let(origin::resolve)?.let { checkedUrl(it.toString()) }
        ?: error("播放线路重定向无效")
    require(!origin.isHttps || target.isHttps) { "播放线路不允许从 HTTPS 跳转到 HTTP，请重新解析线路" }
    return target
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
    fun bytes(bytes: ByteArray, count: Int = bytes.size, offset: Int = 0) { connection.write(bytes, offset, count) }
    fun error(status: Int, message: String) {
        val bytes = message.encodeToByteArray()
        headers(status, mapOf("Content-Type" to "text/plain", "Content-Length" to bytes.size.toString()))
        this.bytes(bytes)
        finished = true
    }
}

/**
 * One full, unencrypted HLS segment served to a native media client.
 * The bridge continues storing the unmodified upstream bytes.
 */
internal class PlainHlsSegmentResponse(
    private val output: ResponseWriter,
    private val headers: Map<String, String>,
    private val method: String,
) {
    private var pending = ByteArray(8)
    private var pendingSize = 0

    fun write(bytes: ByteArray, count: Int) {
        var offset = 0
        while (!output.started && offset < count) {
            val take = minOf(count - offset, pending.size - pendingSize)
            bytes.copyInto(pending, pendingSize, offset, offset + take)
            pendingSize += take
            offset += take
            if (pending.size == 8 && pendingSize == 8) {
                if (!animeHlsImageSignature(pending)) start(0)
                else pending = pending.copyOf(ANIME_HLS_IMAGE_INSPECTION_LIMIT)
            } else if (pending.size > 8) {
                val transportOffset = animeHlsTransportOffset(pending, pendingSize)
                if (transportOffset > 0 || pendingSize == pending.size) start(transportOffset)
            }
        }
        if (offset < count) output.bytes(bytes, count - offset, offset)
    }

    fun finish() {
        if (!output.started) start(0)
    }

    private fun start(transportOffset: Int) {
        val changed = transportOffset > 0
        val forwarded = headers.filterKeys { key ->
            key.lowercase() !in setOf("accept-ranges", "content-range", "etag") &&
                (!changed || !key.equals("Content-Type", true)) &&
                (method == "GET" && !changed || !key.equals("Content-Length", true))
        }.toMutableMap()
        val byteLength = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toLongOrNull()
        if (changed) {
            forwarded["Content-Type"] = "video/mp2t"
            byteLength?.takeIf { it > transportOffset }?.let {
                forwarded["Content-Length"] = (it - transportOffset).toString()
            }
        }
        forwarded["Accept-Ranges"] = "none"
        output.headers(200, forwarded)
        if (method == "GET" && pendingSize > transportOffset) {
            output.bytes(pending, pendingSize - transportOffset, transportOffset)
        }
    }
}

private fun responseHeaders(response: NetworkResponse, manifest: Boolean): Map<String, String> =
    response.headers.filterKeys {
        it.lowercase() in setOf("content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag") &&
            (!manifest || !it.equals("Content-Length", true))
    }
