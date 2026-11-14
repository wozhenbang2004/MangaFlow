@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.data.plugins

import dev.mangaflow.data.SourceException
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.checked
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.wallTimeMillis
import io.ktor.http.encodeURLParameter
import io.ktor.http.fromHttpToGmtDate
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import okio.Buffer
import okio.GzipSource
import okio.buffer
import okio.use
import platform.CFNetwork.*
import platform.CoreFoundation.*
import platform.darwin.dispatch_get_global_queue
import platform.posix.*
import kotlin.math.ceil

/** Public-IP socket + original TLS peer name; the proxy never resolves the destination. */
internal actual class JavascriptHttpSession actual constructor(
    private val enabled: Boolean,
    private val route: DownloadRouteSnapshot?,
    private val maximumBytes: Int,
    private val allowRedirects: Boolean,
) {
    private val operations = atomic(0)
    private val slots = Semaphore(2)
    private val lock = SynchronizedObject()
    private val openStreams = mutableListOf<Pair<CFReadStreamRef, CFWriteStreamRef>>()
    private var closed = false

    actual fun close() = synchronized(lock) {
        closed = true
        openStreams.forEach { (reader, writer) -> CFReadStreamClose(reader); CFWriteStreamClose(writer) }
    }

    actual suspend fun request(raw: String): String = slots.withPermit {
        check(enabled) { "验证漫画源时不允许网络请求。" }
        require(raw.length <= 2 * 1024 * 1024 && operations.incrementAndGet() <= 32) { "漫画源请求超出限制。" }
        val input = JSONObject(raw)
        var method = input.getString("method")
        require(method in setOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH")) { "漫画源 HTTP 方法暂不支持。" }
        var url = publicPluginUrl(input.getString("url"))
        var headers = nativeScriptHeaders(input.optJSONObject("headers") ?: JSONObject())
        val bodyValue = input.opt("body")
        if (bodyValue != null && bodyValue != JSONObject.NULL && headers.keys.none { it.equals("Content-Type", true) })
            headers = headers + ("Content-Type" to when (bodyValue) { is JSONObject -> "application/x-www-form-urlencoded"; is JSONArray -> "application/octet-stream"; else -> "text/plain; charset=utf-8" })
        var body = nativeScriptBody(bodyValue, headers)
        if (method in setOf("GET", "HEAD")) body = ByteArray(0)
        repeat(4) { redirects ->
            currentCoroutineContext().ensureActive()
            val pause = synchronized(nativePauseLock) { (nativePauses[url.origin()] ?: 0L) - wallTimeMillis() }
            if (pause > 0) throw SourceException("漫画源请求暂时受限，请稍后重试。", retryAfterSeconds = ceil(pause / 1000.0).toLong())
            reserveNativeOrigin(url)
            val result = withContext(Dispatchers.IO) { response(url, method, headers, body) }
            val retry = result.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.let { value ->
                value.toLongOrNull()?.coerceIn(0, 86_400) ?: runCatching { ceil((value.fromHttpToGmtDate().timestamp - wallTimeMillis()) / 1000.0).toLong().coerceIn(0, 86_400) }.getOrDefault(0)
            } ?: if (result.status == 429) 60L else 0L
            if (retry > 0) synchronized(nativePauseLock) {
                nativePauses[url.origin()] = wallTimeMillis() + retry * 1000L
                while (nativePauses.size > 128) nativePauses.remove(nativePauses.keys.first())
            }
            if (result.status == 429) throw SourceException("漫画源请求受限，请稍后重试。", retryAfterSeconds = retry)
            if (result.status in setOf(301, 302, 303, 307, 308)) {
                require(allowRedirects) { "来源登录请求发生重定向，请更新来源地址后重试。" }
                require(!input.optBoolean("rejectRedirects", false)) { "源文件地址发生重定向，请使用最终 HTTPS 直链。" }
                require(redirects < 3) { "漫画源重定向次数过多。" }
                val location = result.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value ?: error("漫画源重定向缺少目标。")
                val target = url.resolve(location)?.let { publicPluginUrl(it.toString()) } ?: error("漫画源重定向地址无效。")
                if (url.origin() != target.origin()) {
                    require(body.isEmpty()) { "不允许跨站点重定向请求正文，请更新来源地址后重试。" }
                    headers = headers.filterKeys { !it.equals("Authorization", true) && !it.equals("Cookie", true) }
                }
                if (result.status == 303 || method == "POST" && result.status in setOf(301, 302)) { method = "GET"; body = ByteArray(0) }
                url = target
            } else return@withPermit JSONObject().put("body", result.body.decodeToString()).put("statusCode", result.status)
                .put("headers", JSONObject(result.headers)).put("url", url.toString()).toString()
        }
        error("漫画源重定向次数过多。")
    }

    internal suspend fun imageBytes(value: String, imageHeaders: Map<String, String>): ByteArray {
        var url = publicPluginUrl(value)
        var headers = nativeScriptHeaders(JSONObject(imageHeaders))
        repeat(4) { index ->
            currentCoroutineContext().ensureActive()
            reserveNativeOrigin(url)
            val result = withContext(Dispatchers.IO) { response(url, "GET", headers, ByteArray(0), 45_000L) }
            if (result.status in setOf(301, 302, 303, 307, 308)) {
                require(index < 3) { "图片重定向次数过多。" }
                val target = result.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value
                    ?.let { url.resolve(it) }?.let { publicPluginUrl(it.toString()) } ?: error("图片重定向地址无效。")
                if (url.origin() != target.origin()) headers = headers.filterKeys { !it.equals("Authorization", true) && !it.equals("Cookie", true) }
                url = target
            } else {
                if (result.status !in 200..299) throw SourceException("来源图片暂时无法读取。", httpStatus = result.status)
                require(result.body.isNotEmpty()) { "来源图片为空。" }
                return result.body
            }
        }
        error("图片重定向次数过多。")
    }

    private suspend fun response(url: HttpUrl, method: String, headers: Map<String, String>, body: ByteArray, timeoutMillis: Long = 15_000L): NativeScriptResponse {
        val selected = route?.checked()
        if (selected?.mode == DownloadRouteMode.PROXY && selected.proxy?.type != DownloadProxyType.SOCKS)
            throw DownloadRouteException("第三方脚本来源在 iOS 上仅支持指定 SOCKS5 代理，暂不支持 HTTP 代理。")
        if (selected == null || selected.mode == DownloadRouteMode.SYSTEM) checkNativeSystemProxy()
        val addresses = nativePublicAddresses(url.host, url.port)
        // Never retry POST or replay credentials against another address.
        val ip = addresses.firstOrNull() ?: error("漫画源没有可连接的公开地址。")
        return memScoped {
            val reader = alloc<CFReadStreamRefVar>().apply { value = null }; val writer = alloc<CFWriteStreamRefVar>().apply { value = null }
            val host = nativeCfString(ip)
            try { CFStreamCreatePairWithSocketToHost(null, host, url.port.toUInt(), reader.ptr, writer.ptr) }
            finally { CFRelease(host) }
            val read = reader.value ?: error("无法创建漫画源读取连接。")
            val write = writer.value ?: run { CFRelease(read); error("无法创建漫画源写入连接。") }
            try {
                val ssl = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
                val peer = nativeCfString(url.host)
                try {
                    CFDictionarySetValue(ssl, kCFStreamSSLPeerName, peer)
                    CFDictionarySetValue(ssl, kCFStreamSSLValidatesCertificateChain, kCFBooleanTrue)
                    CFDictionarySetValue(ssl, kCFStreamSSLLevel, kCFStreamSocketSecurityLevelNegotiatedSSL)
                    check(CFReadStreamSetProperty(read, kCFStreamPropertySSLSettings, ssl) && CFWriteStreamSetProperty(write, kCFStreamPropertySSLSettings, ssl)) { "无法启用漫画源 TLS 校验。" }
                } finally { CFRelease(ssl); CFRelease(peer) }
                if (selected?.mode == DownloadRouteMode.PROXY) {
                    val endpoint = selected.proxy ?: error("代理未配置。")
                    val proxy = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)!!
                    val proxyHost = nativeCfString(endpoint.host)
                    val number = alloc<IntVar>().apply { value = endpoint.port }
                    val proxyPort = CFNumberCreate(null, kCFNumberIntType, number.ptr)!!
                    try {
                        CFDictionarySetValue(proxy, kCFStreamPropertySOCKSProxyHost, proxyHost)
                        CFDictionarySetValue(proxy, kCFStreamPropertySOCKSProxyPort, proxyPort)
                        CFDictionarySetValue(proxy, kCFStreamPropertySOCKSVersion, kCFStreamSocketSOCKSVersion5)
                        check(CFReadStreamSetProperty(read, kCFStreamPropertySOCKSProxy, proxy) && CFWriteStreamSetProperty(write, kCFStreamPropertySOCKSProxy, proxy)) { "无法应用漫画源 SOCKS5 路由。" }
                    } finally { CFRelease(proxy); CFRelease(proxyHost); CFRelease(proxyPort) }
                }
                synchronized(lock) { check(!closed) { "漫画源请求已取消。" }; openStreams += read to write }
                // Let Core Foundation advance TLS/socket state while coroutines poll bounded reads.
                val queue = dispatch_get_global_queue(0, 0uL)
                CFReadStreamSetDispatchQueue(read, queue); CFWriteStreamSetDispatchQueue(write, queue)
                check(CFReadStreamOpen(read) && CFWriteStreamOpen(write)) { "漫画源 TLS 连接失败。" }
                val deadline = monotonicTimeMillis() + timeoutMillis
                val authority = url.host + if (url.port == 443) "" else ":${url.port}"
                val requestHead = buildString {
                    append(method).append(' ').append(url.encodedPath).append(url.query?.let { "?$it" }.orEmpty()).append(" HTTP/1.1\r\n")
                    append("Host: ").append(authority).append("\r\nConnection: close\r\nAccept-Encoding: gzip\r\n")
                    headers.filterKeys { !it.equals("Accept-Encoding", true) }.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
                    if (method !in setOf("GET", "HEAD")) append("Content-Length: ").append(body.size).append("\r\n")
                    append("\r\n")
                }.encodeToByteArray()
                val output = requestHead + body
                var sent = 0
                while (sent < output.size) {
                    currentCoroutineContext().ensureActive(); check(monotonicTimeMillis() < deadline) { "漫画源连接或写入超时。" }
                    if (!CFWriteStreamCanAcceptBytes(write)) { checkStreamErrors(read, write); delay(10); continue }
                    val count = output.usePinned { CFWriteStreamWrite(write, it.addressOf(sent).reinterpret(), (output.size - sent).toLong()) }.toInt()
                    check(count > 0) { "漫画源请求写入失败。" }; sent += count
                }
                val received = Buffer()
                val frame = NativeResponseFrame(method == "HEAD", maximumBytes)
                val chunk = ByteArray(16_384)
                while (true) {
                    currentCoroutineContext().ensureActive(); check(monotonicTimeMillis() < deadline) { "漫画源响应读取超时。" }
                    if (!CFReadStreamHasBytesAvailable(read)) {
                        checkStreamErrors(read, write)
                        if (CFReadStreamGetStatus(read) == kCFStreamStatusAtEnd || CFReadStreamGetStatus(read) == kCFStreamStatusClosed) break
                        delay(10); continue
                    }
                    val count = chunk.usePinned { CFReadStreamRead(read, it.addressOf(0).reinterpret(), chunk.size.toLong()) }.toInt()
                    check(count >= 0) { "漫画源响应读取失败。" }
                    if (count == 0) break
                    require(received.size + count <= maximumBytes + 65_536L) { "漫画源响应过大。" }
                    received.write(chunk, 0, count)
                    if (frame.complete(received)) break
                }
                frame.requireCompleteAtEnd(received)
                parseNativeScriptResponse(received.readByteArray(), method == "HEAD", maximumBytes)
            } finally {
                synchronized(lock) { openStreams.removeAll { it.first == read }; CFReadStreamClose(read); CFWriteStreamClose(write) }
                CFRelease(read); CFRelease(write)
            }
        }
    }
}

private data class NativeScriptResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)
private val nativePauseLock = SynchronizedObject()
private val nativePauses = LinkedHashMap<String, Long>()
private val nativeOriginLock = Mutex()
private val nativeStarts = LinkedHashMap<String, Long>()
private suspend fun reserveNativeOrigin(url: HttpUrl) {
    val wait = nativeOriginLock.withLock {
        val now = monotonicTimeMillis()
        val start = maxOf(now, nativeStarts[url.origin()] ?: now)
        nativeStarts.remove(url.origin()); nativeStarts[url.origin()] = start + 400L
        while (nativeStarts.size > 128) nativeStarts.remove(nativeStarts.keys.first())
        (start - now).coerceAtLeast(0)
    }
    if (wait > 0) delay(wait)
}
private fun HttpUrl.origin() = "$scheme://$host:$port"
private fun nativeCfString(value: String): CFStringRef = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8) ?: error("无效的漫画源地址。")
private fun checkStreamErrors(reader: CFReadStreamRef, writer: CFWriteStreamRef) {
    check(CFReadStreamGetStatus(reader) != kCFStreamStatusError && CFWriteStreamGetStatus(writer) != kCFStreamStatusError) { "漫画源连接或 TLS 证书校验失败。" }
}

/** DNS answers are all checked before a numeric address is passed to CFStream. */
private fun nativePublicAddresses(host: String, port: Int): List<String> = memScoped {
    val result = alloc<CPointerVar<addrinfo>>()
    val hints = alloc<addrinfo>().apply {
        memset(ptr, 0, sizeOf<addrinfo>().convert())
        ai_family = AF_UNSPEC; ai_socktype = SOCK_STREAM; ai_protocol = IPPROTO_TCP
    }
    check(getaddrinfo(host, port.toString(), hints.ptr, result.ptr) == 0) { "漫画源域名解析失败。" }
    val first = result.value ?: error("漫画源域名没有返回地址。")
    try {
        buildList {
            var current: CPointer<addrinfo>? = first
            while (current != null) {
                val item = current.pointed
                val text = allocArray<ByteVar>(NI_MAXHOST)
                check(getnameinfo(item.ai_addr, item.ai_addrlen, text, NI_MAXHOST.toUInt(), null, 0u, NI_NUMERICHOST) == 0) { "漫画源地址无法读取。" }
                val address = text.toKString()
                require(nativePublicAddress(address)) { "漫画源域名指向私有或保留网络。" }
                add(address); require(size <= 64) { "漫画源域名地址过多。" }
                current = item.ai_next
            }
        }.distinct()
    } finally { freeaddrinfo(first) }
}

private fun nativePublicAddress(address: String): Boolean = memScoped {
    // AI_NUMERICHOST parses the already numeric DNS answer without another lookup.
    val result = alloc<CPointerVar<addrinfo>>().apply { value = null }
    val hints = alloc<addrinfo>().apply {
        memset(ptr, 0, sizeOf<addrinfo>().convert())
        ai_flags = AI_NUMERICHOST
        ai_family = AF_UNSPEC; ai_socktype = SOCK_STREAM; ai_protocol = IPPROTO_TCP
    }
    if (getaddrinfo(address, null, hints.ptr, result.ptr) != 0) return@memScoped false
    val first = result.value ?: return@memScoped false
    try {
        val item = first.pointed
        val socket = item.ai_addr ?: return@memScoped false
        val bytes = when (item.ai_family) {
            AF_INET -> {
                if (item.ai_addrlen.toLong() < sizeOf<sockaddr_in>()) return@memScoped false
                socket.reinterpret<sockaddr_in>().pointed.sin_addr.ptr.reinterpret<UByteVar>()
            }
            AF_INET6 -> {
                if (item.ai_addrlen.toLong() < sizeOf<sockaddr_in6>()) return@memScoped false
                socket.reinterpret<sockaddr_in6>().pointed.sin6_addr.ptr.reinterpret<UByteVar>()
            }
            else -> return@memScoped false
        }
        if (item.ai_family == AF_INET) {
            val a = bytes[0].toInt(); val b = bytes[1].toInt(); val c = bytes[2].toInt()
            return@memScoped a !in setOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) &&
                !(a == 169 && b == 254) && !(a == 172 && b in 16..31) && !(a == 192 && b in setOf(0, 168)) &&
                !(a == 198 && (b in 18..19 || b == 51 && c == 100)) && !(a == 203 && b == 0 && c == 113)
        }
        if ((0..9).all { bytes[it] == 0.toUByte() } && bytes[10] == 255.toUByte() && bytes[11] == 255.toUByte())
            return@memScoped nativePublicAddress((12..15).joinToString(".") { bytes[it].toString() })
        if (bytes[0] == 0.toUByte() && bytes[1] == 0x64.toUByte() && bytes[2] == 0xff.toUByte() && bytes[3] == 0x9b.toUByte() && (4..11).all { bytes[it] == 0.toUByte() })
            return@memScoped nativePublicAddress((12..15).joinToString(".") { bytes[it].toString() })
        bytes[0].toInt() and 0xe0 == 0x20 && !(bytes[0] == 0x20.toUByte() && bytes[1] == 1.toUByte() && bytes[2] == 0x0d.toUByte() && bytes[3] == 0xb8.toUByte()) &&
            !(bytes[0] == 0x20.toUByte() && bytes[1] == 2.toUByte()) && !(bytes[0] == 0x20.toUByte() && bytes[1] == 1.toUByte() && bytes[2] == 0.toUByte() && bytes[3] == 0.toUByte())
    } finally { freeaddrinfo(first) }
}

private fun checkNativeSystemProxy() {
    val settings = CFNetworkCopySystemProxySettings() ?: return
    try {
        for (name in listOf("HTTPEnable", "HTTPSEnable", "SOCKSEnable", "ProxyAutoConfigEnable", "ProxyAutoDiscoveryEnable")) {
            val key = nativeCfString(name)
            try {
                val value = CFDictionaryGetValue(settings, key) ?: continue
                val enabled = if (CFGetTypeID(value) == CFBooleanGetTypeID()) CFBooleanGetValue(value.reinterpret())
                    else if (CFGetTypeID(value) == CFNumberGetTypeID()) memScoped { val number = alloc<IntVar>(); CFNumberGetValue(value.reinterpret(), kCFNumberIntType, number.ptr) && number.value != 0 }
                    else true
                if (enabled) throw DownloadRouteException("第三方脚本来源暂不支持 iOS 系统代理，请选择应用直连或指定 SOCKS5 代理。")
            } finally { CFRelease(key) }
        }
    } finally { CFRelease(settings) }
}

private fun nativeScriptHeaders(value: JSONObject): Map<String, String> {
    require(value.length() <= 64)
    return value.keys().asSequence().associateWith { name ->
        require(name.length in 1..100 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" })
        (value.get(name) as? String)?.also { require(it.length <= 8192 && it.none { ch -> ch.code < 32 && ch != '\t' || ch.code == 127 }) }
            ?: error("漫画源请求头值必须是字符串。")
    }.filterKeys { it.lowercase() !in NATIVE_MANAGED_HEADERS }
}
private val NATIVE_MANAGED_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding",
    "proxy-authorization", "proxy-connection", "accept-encoding")
private fun nativeScriptBody(value: Any?, headers: Map<String, String>): ByteArray {
    if (value == null || value == JSONObject.NULL) return ByteArray(0)
    val type = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value.orEmpty()
    if (value is JSONObject && !type.contains("json", true)) return value.keys().asSequence().joinToString("&") { name ->
        val item = value.get(name); require(item is String || item is Number || item is Boolean)
        name.encodeURLParameter(spaceToPlus = true) + "=" + item.toString().encodeURLParameter(spaceToPlus = true)
    }.encodeToByteArray()
    if (value is JSONArray && !type.contains("json", true)) return ByteArray(value.length()) { index ->
        val item = value.get(index); require(item is Number && item.toInt() in 0..255 && item.toDouble() == item.toInt().toDouble()); item.toInt().toByte()
    }
    return value.toString().encodeToByteArray()
}
/** HTTP message framing lets a complete response finish without waiting for a CDN to close TLS. */
private class NativeResponseFrame(private val head: Boolean, private val maximum: Int) {
    private var headerOffset = 0L
    private var bodyOffset: Long? = null
    private var expectedEnd: Long? = null
    private var chunked = false
    private var chunkCursor = 0L
    private var chunkEnd: Long? = null
    private var chunkBytes = 0L
    private var trailers = false
    private var trailerStart = 0L
    private var interim = 0

    fun complete(buffer: Buffer): Boolean {
        expectedEnd?.let { return buffer.size >= it }
        while (bodyOffset == null) {
            val end = buffer.indexOf(HEADER_END, headerOffset)
            if (end < 0) {
                require(buffer.size <= 65_536) { "漫画源响应头过大。" }
                return false
            }
            require(end + 4 <= 65_536) { "漫画源响应头过大。" }
            val copy = Buffer(); buffer.copyTo(copy, headerOffset, end - headerOffset)
            val lines = copy.readUtf8().split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: error("漫画源 HTTP 状态无效。")
            if (status in 100..199 && status != 101) {
                require(++interim <= 4); headerOffset = end + 4; continue
            }
            require(status in 200..599 && lines.size <= 129) { "漫画源 HTTP 响应头无效。" }
            val headers = linkedMapOf<String, String>()
            lines.drop(1).forEach { line ->
                val split = line.indexOf(':'); require(split > 0) { "漫画源 HTTP 响应头无效。" }
                val name = line.substring(0, split).trim().lowercase()
                val value = line.substring(split + 1).trim()
                headers[name] = headers[name]?.let { "$it, $value" } ?: value
            }
            bodyOffset = end + 4; chunkCursor = end + 4
            if (head || status in setOf(204, 304) || status in 300..399) {
                expectedEnd = end + 4; return true
            }
            val transfer = headers["transfer-encoding"]
            if (transfer != null) {
                require(transfer.equals("chunked", true)) { "漫画源传输编码暂不支持。" }
                require(headers["content-length"] == null) { "漫画源响应帧长度冲突。" }
                chunked = true
            } else headers["content-length"]?.let { value ->
                val length = value.toLongOrNull() ?: error("漫画源响应长度无效。")
                require(length in 0L..maximum.toLong()) { "漫画源响应过大。" }
                expectedEnd = end + 4 + length
                return buffer.size >= end + 4 + length
            }
        }
        if (!chunked) return false // Unframed HTTP/1.0 responses still require a real EOF.
        while (true) {
            chunkEnd?.let { end ->
                if (buffer.size < end + 2) return false
                require(buffer[end] == 13.toByte() && buffer[end + 1] == 10.toByte()) { "漫画源 chunked 响应无效。" }
                chunkCursor = end + 2; chunkEnd = null
            }
            val lineEnd = buffer.indexOf(CRLF, chunkCursor)
            if (lineEnd < 0) {
                require(buffer.size - chunkCursor <= 8192) { "漫画源 chunked 响应行过大。" }
                return false
            }
            require(lineEnd - chunkCursor <= 8192) { "漫画源 chunked 响应行过大。" }
            if (trailers) {
                require(lineEnd - trailerStart <= 65_536) { "漫画源响应尾部过大。" }
                if (lineEnd == chunkCursor) { expectedEnd = lineEnd + 2; return true }
                chunkCursor = lineEnd + 2; continue
            }
            val line = Buffer(); buffer.copyTo(line, chunkCursor, lineEnd - chunkCursor)
            val size = line.readUtf8().substringBefore(';').trim().toLongOrNull(16) ?: error("漫画源 chunked 响应无效。")
            require(size >= 0 && size <= maximum && chunkBytes + size <= maximum) { "漫画源响应过大。" }
            chunkCursor = lineEnd + 2
            if (size == 0L) { trailers = true; trailerStart = chunkCursor }
            else { chunkBytes += size; chunkEnd = chunkCursor + size }
        }
    }

    fun requireCompleteAtEnd(buffer: Buffer) {
        val done = complete(buffer)
        require(bodyOffset != null && (done || !chunked && expectedEnd == null)) { "漫画源响应未完整接收。" }
    }

    private companion object {
        val HEADER_END = "\r\n\r\n".encodeUtf8()
        val CRLF = "\r\n".encodeUtf8()
    }
}

private fun parseNativeScriptResponse(bytes: ByteArray, head: Boolean, maximum: Int): NativeScriptResponse {
    fun findHeaderEnd(start: Int): Int {
        for (index in start until minOf(bytes.size - 3, start + 65_536))
            if (bytes[index] == 13.toByte() && bytes[index + 1] == 10.toByte() && bytes[index + 2] == 13.toByte() && bytes[index + 3] == 10.toByte()) return index
        error("漫画源 HTTP 响应头无效或过大。")
    }
    var start = 0
    var end = findHeaderEnd(start)
    var lines = bytes.copyOfRange(start, end).decodeToString().split("\r\n")
    var status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: error("漫画源 HTTP 状态无效。")
    var interim = 0
    while (status in 100..199 && status != 101) {
        require(++interim <= 4); start = end + 4; end = findHeaderEnd(start)
        lines = bytes.copyOfRange(start, end).decodeToString().split("\r\n")
        status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: error("漫画源 HTTP 状态无效。")
    }
    require(status in 200..599 && lines.size <= 129)
    val headers = linkedMapOf<String, String>()
    lines.drop(1).forEach { line ->
        val split = line.indexOf(':'); require(split > 0)
        val key = line.substring(0, split).trim(); val value = line.substring(split + 1).trim()
        require(key.none { it <= ' ' || it >= '\u007f' })
        val existing = headers.keys.firstOrNull { it.equals(key, true) }
        if (existing == null) headers[key] = value else headers[existing] = headers.getValue(existing) + ", " + value
    }
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    if (head || status in setOf(204, 304) || status in 300..399) return NativeScriptResponse(status, headers, ByteArray(0))
    val raw = bytes.copyOfRange(end + 4, bytes.size)
    val decoded = if (header("Transfer-Encoding")?.equals("chunked", true) == true) {
        val input = Buffer().write(raw); val output = Buffer()
        while (true) {
            val line = input.readUtf8LineStrict(8192).substringBefore(';').trim()
            val count = line.toLongOrNull(16) ?: error("漫画源 chunked 响应无效。")
            require(count >= 0 && count <= maximum && output.size + count <= maximum)
            if (count == 0L) break
            require(input.size >= count + 2)
            output.write(input, count)
            require(input.readByte() == 13.toByte() && input.readByte() == 10.toByte())
        }
        output.readByteArray()
    } else {
        val length = header("Content-Length")?.toLongOrNull()
        require(length == null || length == raw.size.toLong()) { "漫画源响应未完整接收。" }
        raw
    }
    require(decoded.size <= maximum)
    val plain = when (header("Content-Encoding")?.lowercase()) {
        null, "", "identity" -> decoded
        "gzip", "x-gzip" -> GzipSource(Buffer().write(decoded)).buffer().use { source ->
            val output = Buffer()
            while (true) {
                val count = source.read(output, 16_384)
                if (count < 0) break
                require(output.size <= maximum) { "漫画源解压响应过大。" }
            }
            output.readByteArray()
        }
        else -> error("此来源响应编码暂不受 iOS 脚本宿主支持。")
    }
    return NativeScriptResponse(status, headers, plain)
}

internal actual suspend fun fetchPluginText(context: PlatformContext, url: String): String {
    val session = JavascriptHttpSession(true, DownloadRoutingStore.get(context).snapshot(DownloadCategory.SOURCE_PACKAGES), 2 * 1024 * 1024, true)
    try {
        val value = JSONObject(session.request(JSONObject().put("method", "GET").put("url", url).put("headers", JSONObject().put("Accept", "application/json,text/javascript,text/plain,*/*")).put("rejectRedirects", true).toString()))
        require(value.getInt("statusCode") in 200..299) { "源文件下载失败。" }
        return value.getString("body")
    } finally { session.close() }
}

internal actual suspend fun fetchScriptImageBytes(context: PlatformContext, url: String,
    headers: Map<String, String>, route: DownloadRouteSnapshot?): ByteArray {
    val session = JavascriptHttpSession(true, route, 96 * 1024 * 1024, true)
    try { return session.imageBytes(url, headers) } finally { session.close() }
}
