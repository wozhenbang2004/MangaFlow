package dev.mangaflow.data.plugins

import android.content.Context
import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.evaluate
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.ChapterDownloadTransport
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.json.JSONArray as SharedJSONArray
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

/** No browser, JVM reflection, file API, account interceptor or ambient cookie jar is exported. */
actual class JavascriptSourceRuntime actual constructor(context: PlatformContext) {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("mangaflow_script_source_preferences", Context.MODE_PRIVATE)
    private val hostScript by lazy { application.assets.open("source-host.js").bufferedReader().use { it.readText() } }

    actual suspend fun validate(bundle: ScriptSourcePackage) {
        execute(bundle, reading = false, allowNetwork = false) { js ->
            // Compilation and export inspection are actual validation; no made-up search is run.
            js.evaluate<String>("__mfValidate(globalThis.__mfProvider)", "validate.js")
            Unit
        }
    }

    actual suspend fun call(bundle: ScriptSourcePackage, method: String, args: SharedJSONArray): String {
        require(method in METHODS) { "不支持的漫画源操作：$method" }
        require(args.toString().length <= MAX_ARGUMENT_CHARS) { "漫画源操作参数过大。" }
        return execute(bundle, reading = method == "getPageList", allowNetwork = true) { js ->
            val result = js.evaluate<String>(
                "__mfInvoke(globalThis.__mfProvider, ${JSONObject.quote(method)}, $args)", "operation.js",
            )
            require(result.length <= MAX_RESPONSE_BYTES) { "漫画源返回内容过大。" }
            val envelope = JSONObject(result)
            require(envelope.has("result")) { "漫画源操作没有返回结果。" }
            checkedHeaders(envelope.getJSONObject("headers"))
            result
        }
    }

    private suspend fun <T> execute(bundle: ScriptSourcePackage, reading: Boolean, allowNetwork: Boolean,
        block: suspend (QuickJs) -> T): T = withContext(Dispatchers.Default) {
        require(bundle.code.length in 1..MAX_SCRIPT_CHARS) { "漫画源脚本大小无效。" }
        val gate = if (reading) readingContexts else otherContexts
        withTimeout(20_000L) {
            gate.withPermit {
                PanelRuntimeStore.get(application).ensureLoaded(PanelRuntimeId.QUICKJS)
                currentCoroutineContext().ensureActive()
                val js = try { QuickJs.create(Dispatchers.Default) } catch (failure: LinkageError) {
                    throw SourceException("漫画源脚本引擎加载失败，请重启应用或重新下载引擎。", cause = failure)
                }
                val preferenceCalls = AtomicInteger()
                val preferenceScope = MessageDigest.getInstance("SHA-256").digest(bundle.sourceId.toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val dom = JavascriptDomBridge(bundle.metadata.optString("baseUrl"))
                var http: HttpSession? = null
                try {
                    val requests = HttpSession(allowNetwork, currentCoroutineContext()[ChapterDownloadTransport]?.route)
                    http = requests
                    js.memoryLimit = 48L * 1024 * 1024
                    js.maxStackSize = 256L * 1024
                    js.evaluationTimeoutMillis = 2_000L
                    js.defineBinding("__mfHttp", AsyncFunctionBinding<String> { values -> requests.request(argument(values)) })
                    js.defineBinding("__mfDom", FunctionBinding<String> { values -> dom.call(argument(values)) })
                    js.defineBinding("__mfPrefs", FunctionBinding<String> { values ->
                        require(preferenceCalls.incrementAndGet() <= 128) { "漫画源偏好操作过多。" }
                        preference(preferenceScope, argument(values), allowNetwork)
                    })
                    js.defineBinding("__mfCodec", FunctionBinding<String> { values -> codec(argument(values)) })
                    js.defineBinding("__mfUrl", FunctionBinding<String> { values -> urlInfo(argument(values)) })
                    val metadata = JSONObject(bundle.metadata.toString()).apply {
                        if (!has("id")) put("id", bundle.sourceId)
                    }
                    js.evaluate<Unit>("globalThis.__mfMetadata = $metadata; void 0;", "metadata.js")
                    js.evaluate<Unit>(hostScript, "source-host.js")
                    val bytecode = js.compile(bundle.code + "\n;globalThis.__mfProvider = new DefaultExtension(); void 0;\n", "source.js")
                    js.evaluate<Unit>(bytecode)
                    block(js)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: DownloadRouteException) {
                    throw failure
                } catch (failure: Exception) {
                    throw SourceException("漫画源脚本执行失败：${failure.message?.take(240) ?: "宿主能力不兼容"}",
                        bundle.metadata.optString("baseUrl"), cause = failure)
                } finally {
                    // QuickJS 1.0.18 also interrupts evaluate and cancels its async bindings
                    // when the evaluating coroutine is cancelled. close releases native state.
                    runCatching { if (!js.isClosed) js.interruptEvaluation() }
                    try { js.close() } finally {
                        try { dom.close() } finally { http?.close() }
                    }
                }
            }
        }
    }

    private fun preference(scope: String, raw: String, writable: Boolean): String {
        val request = JSONObject(raw)
        val key = request.getString("key")
        require(key.length in 1..160) { "漫画源偏好名称无效。" }
        synchronized(preferenceLock) {
            val values = JSONObject(preferences.getString(scope, "{}") ?: "{}")
            return when (request.getString("op")) {
                "get" -> scriptJsonValue(values.opt(key))
                "set", "remove" -> {
                    check(writable) { "验证漫画源时不允许修改偏好。" }
                    if (request.getString("op") == "remove") values.remove(key)
                    else values.put(key, request.opt("value") ?: JSONObject.NULL)
                    val encoded = values.toString()
                    require(values.length() <= 64 && encoded.length <= 16_384) { "漫画源偏好存储超出限制。" }
                    check(preferences.edit().putString(scope, encoded).commit()) { "漫画源偏好未能保存。" }
                    "true"
                }
                else -> throw IllegalArgumentException("不支持的漫画源偏好操作。")
            }
        }
    }

    private fun codec(raw: String): String {
        val request = JSONObject(raw)
        val text = request.getString("text")
        require(text.length <= MAX_ARGUMENT_CHARS) { "漫画源编码数据过大。" }
        return when (request.getString("op")) {
            "atob" -> Base64.decode(text, Base64.DEFAULT).toString(Charsets.ISO_8859_1)
            "btoa" -> {
                require(text.all { it.code <= 255 }) { "btoa 只接受二进制字符串。" }
                Base64.encodeToString(text.toByteArray(Charsets.ISO_8859_1), Base64.NO_WRAP)
            }
            "aesCbc" -> {
                val key = request.getString("key").toByteArray(Charsets.UTF_8)
                val iv = request.getString("iv").toByteArray(Charsets.UTF_8)
                require(key.size in setOf(16, 24, 32) && iv.size == 16) { "AES-CBC 需要有效的 UTF-8 密钥和 16 字节 IV。" }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                val encrypt = request.getBoolean("encrypt")
                cipher.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                if (encrypt) Base64.encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
                else cipher.doFinal(Base64.decode(text, Base64.DEFAULT)).toString(Charsets.UTF_8)
            }
            else -> throw IllegalArgumentException("不支持的漫画源编码或加密方式。")
        }
    }

    private fun urlInfo(raw: String): String {
        val value = JSONObject(raw)
        val base = value.optString("base").takeIf { it.isNotBlank() }?.let(::publicUrl)
        val url = base?.resolve(value.getString("url")) ?: value.getString("url").toHttpUrlOrNull()
            ?: throw IllegalArgumentException("漫画源 URL 无效。")
        checkUrl(url)
        return JSONObject().put("href", url.toString()).put("origin", url.origin())
            .put("hostname", url.host).put("host", url.host + if (url.port == 443) "" else ":${url.port}")
            .put("pathname", url.encodedPath).put("search", url.encodedQuery?.let { "?$it" }.orEmpty())
            .put("hash", url.encodedFragment?.let { "#$it" }.orEmpty()).toString()
    }

    private class HttpSession(private val enabled: Boolean, private val route: DownloadRouteSnapshot?) {
        private val owned = route != null
        private val client = if (route == null) defaultClient else {
            val sockets = publicSourceSocketFactory(route, ::isPublic)
            // Preserve the checked public DNS/TLS host. SOCKS receives the verified numerical IP.
            defaultClient.newBuilder().connectionPool(ConnectionPool()).dispatcher(Dispatcher())
                .apply { if (sockets != null) socketFactory(sockets) }.build()
        }

        fun close() {
            if (!owned) return
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        private val requests = AtomicInteger()
        private val slots = Semaphore(2)

        suspend fun request(raw: String): String {
            check(enabled) { "验证漫画源时不允许网络请求。" }
            require(requests.incrementAndGet() <= 32) { "漫画源单次操作请求过多。" }
            require(raw.length <= MAX_ARGUMENT_CHARS) { "漫画源 HTTP 参数过大。" }
            val input = JSONObject(raw)
            var method = input.getString("method")
            require(method in setOf("GET", "POST", "HEAD")) { "漫画源 HTTP 方法暂不支持。" }
            var url = publicUrl(input.getString("url"))
            var headers = checkedHeaders(input.optJSONObject("headers") ?: JSONObject())
            var body = requestBody(input.opt("body"), headers)
            if (method != "POST") body = null
            return slots.withPermit {
                repeat(4) { redirect ->
                    currentCoroutineContext().ensureActive()
                    checkSystemRoute(url)
                    reserveOrigin(url)
                    val builder = Request.Builder().url(url)
                    headers.forEach { (name, value) -> builder.header(name, value) }
                    builder.method(method, if (method == "POST") body ?: ByteArray(0).toRequestBody(null) else null)
                    val result = response(builder.build())
                    if (result.status in REDIRECTS) {
                        require(redirect < 3) { "漫画源重定向次数过多。" }
                        val location = result.headers.entries.firstOrNull { it.key.equals("Location", true) }?.value
                            ?: throw SourceException("漫画源重定向缺少目标。", url.toString())
                        val target = url.resolve(location) ?: throw SourceException("漫画源重定向地址无效。", url.toString())
                        checkUrl(target)
                        if (url.origin() != target.origin()) headers = headers.filterKeys {
                            !it.equals("Authorization", true) && !it.equals("Cookie", true)
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
                            if (current.code in setOf(401, 403)) throw SourceException(
                                "漫画源拒绝访问；交互式验证暂不支持。", request.url.toString(), httpStatus = current.code)
                            if (current.code in REDIRECTS || request.method == "HEAD") return@use HttpResult(current.code, headers, "")
                            val source = current.body ?: throw SourceException("漫画源响应为空。", request.url.toString())
                            require(source.contentLength() <= MAX_RESPONSE_BYTES) { "漫画源响应过大。" }
                            val output = ByteArrayOutputStream()
                            source.byteStream().use { stream ->
                                val buffer = ByteArray(16_384)
                                while (continuation.isActive) {
                                    val size = stream.read(buffer)
                                    if (size < 0) break
                                    require(output.size() + size <= MAX_RESPONSE_BYTES) { "漫画源响应过大。" }
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

    companion object {
        private const val MAX_SCRIPT_CHARS = 1024 * 1024
        private const val MAX_ARGUMENT_CHARS = 2 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        private val METHODS = setOf("search", "getDetail", "getPageList", "getPopular", "getLatestUpdates", "getFilterList", "getSourcePreferences", "getHeaders")
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val readingContexts = Semaphore(1)
        private val otherContexts = Semaphore(1)
        private val preferenceLock = Any()
        private val originLock = Mutex()
        private val originStarts = LinkedHashMap<String, Long>()
        private val pauses = LinkedHashMap<String, Long>()
        private val pauseLock = Any()
        // A direct, DNS-validated connection pins the checked public address. An HTTP proxy
        // could resolve the target independently and defeat the private-address restriction.
        private val defaultClient = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).dns(PublicDns)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()

        private fun argument(values: Array<Any?>): String = (values.singleOrNull() as? String)
            ?.also { require(it.length <= MAX_RESPONSE_BYTES + 4096) { "漫画源宿主参数过大。" } }
            ?: throw IllegalArgumentException("漫画源宿主参数无效。")

        private fun publicUrl(value: String): HttpUrl = value.toHttpUrlOrNull()
            ?.also(::checkUrl) ?: throw IllegalArgumentException("漫画源仅支持有效的公开 HTTPS URL。")

        private fun checkUrl(url: HttpUrl) {
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.toString().length <= 8192) {
                "漫画源仅支持无内嵌凭据的公开 HTTPS URL。"
            }
            val host = url.host.lowercase()
            require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) { "漫画源不能访问本机或局域网。" }
            if (host.contains(':') || host.all { it.isDigit() || it == '.' }) {
                require(isPublic(InetAddress.getByName(host))) { "漫画源不能访问私有或保留地址。" }
            }
        }

        private object PublicDns : Dns {
            override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw UnknownHostException("漫画源目标不是公开网络地址。")
            }
        }

        private fun isPublic(address: InetAddress): Boolean {
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

        private fun checkedHeaders(value: JSONObject): Map<String, String> {
            require(value.length() <= 64) { "漫画源请求头过多。" }
            return value.keys().asSequence().associateWith { name ->
                require(name.length in 1..100 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" }) { "漫画源请求头名称无效。" }
                require(name.lowercase() !in setOf("host", "connection", "content-length", "transfer-encoding", "proxy-authorization")) {
                    "漫画源不能覆盖传输控制请求头。"
                }
                (value.get(name) as? String)?.also {
                    require(it.length <= 8192 && it.none { ch -> ch.code < 32 && ch != '\t' || ch.code == 127 }) { "漫画源请求头内容无效。" }
                } ?: throw IllegalArgumentException("漫画源请求头值必须是字符串。")
            }
        }

        private fun requestBody(value: Any?, headers: Map<String, String>): RequestBody? {
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

        private suspend fun reserveOrigin(url: HttpUrl) {
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

        private fun rememberPause(url: HttpUrl, seconds: Long) = synchronized(pauseLock) {
            val expiry = System.currentTimeMillis() + seconds.coerceIn(0, 86_400) * 1000L
            pauses[url.origin()] = maxOf(pauses[url.origin()] ?: 0L, expiry)
            while (pauses.size > 128) pauses.remove(pauses.keys.first())
        }

        private fun retrySeconds(value: String?): Long {
            if (value == null) return 0
            value.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
            return runCatching { ceil((ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - System.currentTimeMillis()) / 1000.0).toLong().coerceIn(0, 86_400) }.getOrDefault(0)
        }

        private fun HttpUrl.origin() = "${scheme}://${host}:$port"
    }
}
