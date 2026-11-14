package dev.mangaflow.data.plugins

import dev.mangaflow.data.SourceException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

import dev.mangaflow.data.plugins.JvmScriptNetwork.defaultClient
import dev.mangaflow.data.plugins.JvmScriptNetwork.isPublic
import dev.mangaflow.data.plugins.JvmScriptNetwork.checkedHeaders
import dev.mangaflow.data.plugins.JvmScriptNetwork.requestBody
import dev.mangaflow.data.plugins.JvmScriptNetwork.checkUrl
import dev.mangaflow.data.plugins.JvmScriptNetwork.publicUrl
import dev.mangaflow.data.plugins.JvmScriptNetwork.reserveOrigin
import dev.mangaflow.data.plugins.JvmScriptNetwork.rememberPause
import dev.mangaflow.data.plugins.JvmScriptNetwork.retrySeconds
import dev.mangaflow.data.plugins.JvmScriptNetwork.REDIRECTS
import dev.mangaflow.data.plugins.JvmScriptNetwork.MAX_ARGUMENT_CHARS
import dev.mangaflow.data.plugins.JvmScriptNetwork.origin
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.DownloadCategory
internal actual class JavascriptHttpSession actual constructor(private val enabled: Boolean, private val route: DownloadRouteSnapshot?, private val maximumBytes: Int, private val allowRedirects: Boolean) {
        private val owned = route != null
        private val client = if (route == null) defaultClient else {
            val sockets = publicSourceSocketFactory(route, ::isPublic)
            // Preserve the checked public DNS/TLS host. SOCKS receives the verified numerical IP.
            defaultClient.newBuilder().connectionPool(ConnectionPool()).dispatcher(Dispatcher())
                .apply { if (sockets != null) socketFactory(sockets) }.build()
        }

        actual fun close() {
            if (!owned) return
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        private val requests = AtomicInteger()
        private val slots = Semaphore(2)

        actual suspend fun request(raw: String): String {
            check(enabled) { "验证漫画源时不允许网络请求。" }
            require(requests.incrementAndGet() <= 32) { "漫画源单次操作请求过多。" }
            require(raw.length <= MAX_ARGUMENT_CHARS) { "漫画源 HTTP 参数过大。" }
            val input = JSONObject(raw)
            var method = input.getString("method")
            require(method in setOf("GET", "POST", "HEAD", "PUT", "DELETE", "PATCH")) { "漫画源 HTTP 方法暂不支持。" }
            var url = publicUrl(input.getString("url"))
            var headers = checkedHeaders(input.optJSONObject("headers") ?: JSONObject())
            var body = requestBody(input.opt("body"), headers)
            if (method in setOf("GET", "HEAD")) body = null
            return slots.withPermit {
                repeat(4) { redirect ->
                    currentCoroutineContext().ensureActive()
                    checkSystemRoute(url)
                    reserveOrigin(url)
                    val builder = Request.Builder().url(url)
                    headers.forEach { (name, value) -> builder.header(name, value) }
                    builder.method(method, if (method in setOf("POST", "PUT", "PATCH")) body ?: ByteArray(0).toRequestBody(null) else body)
                    val result = response(builder.build())
                    if (result.status in REDIRECTS) {
                        require(allowRedirects) { "来源登录请求发生重定向，请更新来源地址后重试。" }
                        require(!input.optBoolean("rejectRedirects", false)) { "源文件地址发生重定向，请使用最终 HTTPS 直链。" }
                        require(redirect < 3) { "漫画源重定向次数过多。" }
                        val location = result.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value
                            ?: throw SourceException("漫画源重定向缺少目标。", url.toString())
                        val target = url.resolve(location) ?: throw SourceException("漫画源重定向地址无效。", url.toString())
                        checkUrl(target)
                        if (url.origin() != target.origin()) {
                            require(body == null) { "不允许跨站点重定向请求正文，请更新来源地址后重试。" }
                            headers = headers.filterKeys { !it.equals("Authorization", true) && !it.equals("Cookie", true) }
                        }
                        if (result.status == 303 || method == "POST" && result.status in setOf(301, 302)) {
                            method = "GET"; body = null
                        }
                        url = target
                    } else {
                        return@withPermit JSONObject().put("body", result.body).put("statusCode", result.status)
                            .put("headers", JSONObject(result.headers)).put("url", url.toString()).toString()
                    }
                }
                throw SourceException("漫画源重定向次数过多。", url.toString())
            }
        }

        private fun checkSystemRoute(url: HttpUrl) {
            if (route?.mode != DownloadRouteMode.SYSTEM) return
            val selected = try { ProxySelector.getDefault()?.select(URI(url.toString())).orEmpty() }
            catch (_: Exception) {
                throw DownloadRouteException("无法核对脚本来源的系统代理，请选择应用直连或指定 SOCKS5 代理。")
            }
            if (selected.any { it.type() != Proxy.Type.DIRECT }) {
                // A system proxy must not resolve a different, unchecked destination behind our DNS gate.
                throw DownloadRouteException("此脚本来源暂不支持系统代理，请选择应用直连或指定 SOCKS5 代理。")
            }
        }

        private suspend fun response(request: Request): HttpResult = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        SourceException("漫画源网络连接失败。", request.url.toString(), cause = e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use { current ->
                            val headers = current.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                            require(headers.size <= 128 && headers.entries.sumOf { it.key.length + it.value.length } <= 32_768) {
                                "漫画源响应头过大。"
                            }
                            val retry = retrySeconds(current.header("Retry-After"))
                            if (retry > 0 || current.code == 429) rememberPause(request.url, retry.takeIf { it > 0 } ?: 60L)
                            if (current.code == 429) throw SourceException("漫画源请求受限，请稍后重试。", request.url.toString(), retry)
                            if (current.code in REDIRECTS || request.method == "HEAD") return@use HttpResult(current.code, headers, "")
                            val source = current.body ?: throw SourceException("漫画源响应为空。", request.url.toString())
                            require(source.contentLength() <= maximumBytes) { "漫画源响应过大。" }
                            val output = ByteArrayOutputStream()
                            source.byteStream().use { stream ->
                                val buffer = ByteArray(16_384)
                                while (continuation.isActive) {
                                    val size = stream.read(buffer)
                                    if (size < 0) break
                                    require(output.size() + size <= maximumBytes) { "漫画源响应过大。" }
                                    output.write(buffer, 0, size)
                                }
                            }
                            HttpResult(current.code, headers, output.toByteArray()
                                .toString(source.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8))
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (failure: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }


private data class HttpResult(val status: Int, val headers: Map<String, String>, val body: String)

internal object JvmScriptNetwork {
        const val MAX_SCRIPT_CHARS = 1024 * 1024
        const val MAX_ARGUMENT_CHARS = 2 * 1024 * 1024
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        val METHODS = setOf("search", "getDetail", "getPageList", "getPopular", "getLatestUpdates", "getFilterList", "getSourcePreferences", "getHeaders")
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val originLock = Mutex()
        val originStarts = LinkedHashMap<String, Long>()
        val pauses = LinkedHashMap<String, Long>()
        val pauseLock = Any()
        // A direct, DNS-validated connection pins the checked public address. An HTTP proxy
        // could resolve the target independently and defeat the private-address restriction.
        val defaultClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(PublicDns)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()

        fun argument(values: Array<Any?>): String = (values.singleOrNull() as? String)
            ?.also { require(it.length <= MAX_RESPONSE_BYTES + 4096) { "漫画源宿主参数过大。" } }
            ?: throw IllegalArgumentException("漫画源宿主参数无效。")

        fun publicUrl(value: String): HttpUrl = value.toHttpUrlOrNull()
            ?.also(::checkUrl) ?: throw IllegalArgumentException("漫画源仅支持有效的公开 HTTPS URL。")

        fun checkUrl(url: HttpUrl) {
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.toString().length <= 8192) {
                "漫画源仅支持无内嵌凭据的公开 HTTPS URL。"
            }
            val host = url.host.lowercase()
            require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) { "漫画源不能访问本机或局域网。" }
            if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
                require(isPublic(InetAddress.getByName(host))) { "漫画源不能访问私有或保留地址。" }
            }
        }

        object PublicDns : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw UnknownHostException("漫画源目标不是公开网络地址。")
            }
        }

        fun isPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address
            if (bytes.size == 16) {
                val prefix = bytes.take(12)
                if (prefix.take(10).all { it == 0.toByte() } && prefix[10] == 0xff.toByte() && prefix[11] == 0xff.toByte())
                    return isPublic(InetAddress.getByAddress(bytes.takeLast(4).toByteArray()))
                // Well-known DNS64 prefix may translate a real public IPv4 endpoint.
                if (prefix[0] == 0.toByte() && prefix[1] == 0x64.toByte() && prefix[2] == 0xff.toByte() &&
                    prefix[3] == 0x9b.toByte() && prefix.drop(4).all { it == 0.toByte() })
                    return isPublic(InetAddress.getByAddress(bytes.takeLast(4).toByteArray()))
                val first = bytes[0].toInt() and 255
                return first and 0xe0 == 0x20 &&
                    !(first == 0x20 && bytes[1] == 0x01.toByte() && bytes[2] == 0x0d.toByte() && bytes[3] == 0xb8.toByte()) &&
                    !(first == 0x20 && bytes[1] == 0x02.toByte()) &&
                    !(first == 0x20 && bytes[1] == 0x01.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte())
            }
            val a = bytes[0].toInt() and 255; val b = bytes[1].toInt() and 255; val c = bytes[2].toInt() and 255
            return a !in setOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) &&
                !(a == 169 && b == 254) && !(a == 172 && b in 16..31) && !(a == 192 && b in setOf(0, 168)) &&
                !(a == 198 && (b in 18..19 || b == 51 && c == 100)) && !(a == 203 && b == 0 && c == 113)
        }

        fun checkedHeaders(value: JSONObject): Map<String, String> {
            require(value.length() <= 64) { "漫画源请求头过多。" }
            return value.keys().asSequence().associateWith { name ->
                require(name.length in 1..100 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" }) { "漫画源请求头名称无效。" }
                (value.get(name) as? String)?.also {
                    require(it.length <= 8192 && it.none { ch -> ch.code < 32 && ch != '\t' || ch.code == 127 }) { "漫画源请求头内容无效。" }
                } ?: throw IllegalArgumentException("漫画源请求头值必须是字符串。")
            }.filterKeys { it.lowercase() !in MANAGED_HEADERS }
        }

        private val MANAGED_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding",
            "proxy-authorization", "proxy-connection", "accept-encoding")

        fun requestBody(value: Any?, headers: Map<String, String>): RequestBody? {
            if (value == null || value == JSONObject.NULL) return null
            val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            if (value is JSONObject && contentType?.contains("json", true) != true) return FormBody.Builder().apply {
                value.keys().forEach { name ->
                    val field = value.get(name)
                    require(field is String || field is Number || field is Boolean) { "表单字段需为文本，嵌套对象请指定 JSON 请求类型。" }
                    add(name, field.toString())
                }
            }.build()
            if (value is JSONArray && contentType?.contains("json", true) != true) {
                require(value.length() <= MAX_ARGUMENT_CHARS) { "漫画源二进制请求体过大。" }
                val bytes = ByteArray(value.length()) { index ->
                    val item = value.get(index)
                    require(item is Number && item.toDouble() == item.toInt().toDouble() && item.toInt() in 0..255) {
                        "非 JSON 数组请求体必须是字节数组。"
                    }
                    (item as Number).toByte()
                }
                return bytes.toRequestBody((contentType ?: "application/octet-stream").toMediaTypeOrNull())
            }
            val text = value.toString()
            require(text.length <= MAX_ARGUMENT_CHARS) { "漫画源请求体过大。" }
            return text.toRequestBody((contentType ?: if (value is JSONObject || value is JSONArray) "application/json; charset=utf-8" else "text/plain; charset=utf-8").toMediaTypeOrNull())
        }

        suspend fun reserveOrigin(url: HttpUrl) {
            val pause = synchronized(pauseLock) { (pauses[url.origin()] ?: 0L) - System.currentTimeMillis() }
            if (pause > 0) throw SourceException("漫画源请求暂时受限，请稍后重试。", url.toString(), ceil(pause / 1000.0).toLong())
            val wait = originLock.withLock {
                val now = System.nanoTime()
                val start = maxOf(now, originStarts[url.origin()] ?: now)
                originStarts.remove(url.origin()); originStarts[url.origin()] = start + 400_000_000L
                while (originStarts.size > 128) originStarts.remove(originStarts.keys.first())
                ((start - now) / 1_000_000L).coerceAtLeast(0L)
            }
            if (wait > 0) delay(wait)
        }

        fun rememberPause(url: HttpUrl, seconds: Long) = synchronized(pauseLock) {
            val expiry = System.currentTimeMillis() + seconds.coerceIn(0, 86_400) * 1000L
            pauses[url.origin()] = maxOf(pauses[url.origin()] ?: 0L, expiry)
            while (pauses.size > 128) pauses.remove(pauses.keys.first())
        }

        fun retrySeconds(value: String?): Long {
            if (value == null) return 0
            value.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
            return runCatching { ceil((ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000.0).toLong().coerceIn(0, 86_400) }.getOrDefault(0)
        }

        fun HttpUrl.origin() = "${scheme}://${host}:$port"
    }

internal actual suspend fun fetchPluginText(context: PlatformContext, url: String): String {
    val route = DownloadRoutingStore.get(context).snapshot(DownloadCategory.SOURCE_PACKAGES)
    val session = JavascriptHttpSession(true, route, 2 * 1024 * 1024, true)
    try {
        val value = JSONObject(session.request(JSONObject().put("method", "GET").put("url", url).put("headers", JSONObject().put("Accept", "application/json,text/javascript,text/plain,*/*")).put("rejectRedirects", true).toString()))
        require(value.getInt("statusCode") in 200..299) { "源文件下载失败（HTTP ${value.getInt("statusCode")}）。" }
        return value.getString("body")
    } finally { session.close() }
}
