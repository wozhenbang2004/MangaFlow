package dev.mangaflow.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import dev.mangaflow.platform.monotonicTimeMillis
import okio.Buffer

/** Same source URL contract on every target, backed by Ktor's maintained URL parser. */
class HttpUrl internal constructor(internal val value: Url, private val hasFragment: Boolean = value.fragment.isNotEmpty()) {
    val scheme: String get() = value.protocol.name
    val host: String get() = value.host.lowercase()
    val port: Int get() = value.port
    val username: String get() = value.user.orEmpty()
    val password: String get() = value.password.orEmpty()
    val fragment: String? get() = value.fragment.takeIf { hasFragment }
    val encodedPath: String get() = value.encodedPath.ifEmpty { "/" }
    val pathSegments: List<String> get() = value.rawSegments.dropWhile { it.isEmpty() }
    val query: String? get() = value.encodedQuery.takeIf { it.isNotEmpty() || value.trailingQuery }
    val queryParameterNames: Set<String> get() = value.parameters.names()
    val querySize: Int get() = value.parameters.entries().sumOf { it.value.size }
    val isHttps: Boolean get() = scheme == "https"
    fun queryParameter(name: String): String? = value.parameters[name]
    fun newBuilder(): Builder = Builder(URLBuilder().takeFrom(value), hasFragment)
    fun resolve(link: String): HttpUrl? = runCatching {
        checkText(link)
        val next = URLBuilder().takeFrom(value).takeFrom(link).build()
        checked(next, '#' in link || (link.isEmpty() && hasFragment))
    }.getOrNull()
    override fun toString(): String = value.toString() + if (hasFragment && value.fragment.isEmpty() && !value.toString().contains('#')) "#" else ""
    override fun equals(other: Any?): Boolean = other is HttpUrl && toString() == other.toString()
    override fun hashCode(): Int = toString().hashCode()
    class Builder internal constructor(private val builder: URLBuilder, private var fragmentPresent: Boolean) {
        fun addPathSegment(segment: String) = apply { builder.appendPathSegments(segment, encodeSlash = true) }
        fun addPathSegments(segments: String) = apply { builder.appendPathSegments(segments, encodeSlash = false) }
        fun addQueryParameter(name: String, value: String?) = apply { builder.parameters.append(name, value.orEmpty()) }
        fun setQueryParameter(name: String, value: String?) = apply { builder.parameters.remove(name); addQueryParameter(name, value) }
        fun removeAllQueryParameters(name: String) = apply { builder.parameters.remove(name) }
        fun scheme(scheme: String) = apply { builder.protocol = URLProtocol.createOrDefault(scheme) }
        fun host(host: String) = apply { builder.host = host }
        fun port(port: Int) = apply { require(port in 1..65535); builder.port = port }
        fun fragment(fragment: String?) = apply { builder.fragment = fragment.orEmpty(); fragmentPresent = fragment != null }
        fun build(): HttpUrl = checked(builder.build(), fragmentPresent)
    }
    companion object {
        fun String.toHttpUrl(): HttpUrl {
            checkText(this)
            require(startsWith("https://", true) || startsWith("http://", true)) { "An absolute HTTP URL is required" }
            return checked(Url(this), '#' in this)
        }
        fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()
        private fun checked(url: Url, hasFragment: Boolean): HttpUrl {
            require(url.protocol.name in setOf("https", "http") && url.host.isNotBlank() && url.port in 1..65535) { "Invalid HTTP URL" }
            return HttpUrl(url, hasFragment)
        }
        private fun checkText(value: String) { require(value.none { it.code < 0x20 || it.code == 0x7f || it == '\\' }) { "Unsafe URL text" } }
    }
}

class MediaType private constructor(val value: String) {
    companion object { fun String.toMediaType(): MediaType = MediaType(this) }
}
open class RequestBody(val bytes: ByteArray, val contentType: String?) {
    companion object { fun String.toRequestBody(type: MediaType? = null): RequestBody = RequestBody(encodeToByteArray(), type?.value) }
}
class FormBody private constructor(private val fields: List<Pair<String, String>>) : RequestBody(
    fields.joinToString("&") { (key, value) -> key.encodeURLParameter(spaceToPlus = true) + "=" + value.encodeURLParameter(spaceToPlus = true) }.encodeToByteArray(),
    "application/x-www-form-urlencoded",
) {
    val size: Int get() = fields.size
    fun name(index: Int): String = fields[index].first
    fun value(index: Int): String = fields[index].second
    class Builder {
        private val fields = mutableListOf<Pair<String, String>>()
        fun add(name: String, value: String) = apply { fields += name to value }
        fun build(): FormBody = FormBody(fields.toList())
    }
}
class Request private constructor(val url: HttpUrl, val method: String, val headers: Map<String, String>, val body: RequestBody?) {
    class Builder {
        private var url: HttpUrl? = null
        private var method = "GET"
        private var body: RequestBody? = null
        private val headers = linkedMapOf<String, String>()
        fun url(url: HttpUrl) = apply { this.url = url }
        fun url(url: String) = apply { this.url = with(HttpUrl) { url.toHttpUrl() } }
        fun header(name: String, value: String) = apply {
            require(name.isNotBlank() && name.none { it <= ' ' || it >= '\u007f' || it == ':' })
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' })
            headers.keys.firstOrNull { it.equals(name, true) }?.let(headers::remove)
            headers[name] = value
        }
        fun get() = method("GET", null)
        fun post(body: RequestBody) = method("POST", body)
        fun method(name: String, body: RequestBody?) = apply { this.method = name; this.body = body }
        fun build(): Request = Request(requireNotNull(url), method, headers.toMap(), body)
    }
}

data class NetworkResponse(val code: Int, val headers: Map<String, String>, val bytes: ByteArray) {
    val isSuccessful: Boolean get() = code in 200..299
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    fun text(): String = bytes.decodeToString()
}

data class NetworkTransferObservation(
    val code: Int?, val headers: Map<String, String>, val bytes: Long, val elapsedMillis: Long, val failed: Boolean,
)

/** Engines retain system TLS validation. Each client has isolated jobs and no cookie installation. */
internal expect fun platformHttpClient(http1Only: Boolean, route: DownloadRouteSnapshot?, configure: HttpClientConfig<*>.() -> Unit): HttpClient

class NetworkClient(
    http1Only: Boolean = false,
    connectTimeoutMillis: Long = 15_000,
    readTimeoutMillis: Long = 30_000,
    callTimeoutMillis: Long = 45_000,
    acceptCompression: Boolean = true,
    route: DownloadRouteSnapshot? = null,
    private val onTransfer: ((NetworkTransferObservation) -> Unit)? = null,
) {
    private val timeouts = Triple(connectTimeoutMillis, readTimeoutMillis, callTimeoutMillis)
    private val selectedRoute = route?.checked()
    private val client = platformHttpClient(http1Only, selectedRoute) {
        expectSuccess = false
        followRedirects = false
        if (acceptCompression) install(ContentEncoding) { gzip(); deflate() }
        install(HttpTimeout) {
            this.connectTimeoutMillis = timeouts.first
            this.socketTimeoutMillis = timeouts.second
            this.requestTimeoutMillis = timeouts.third
        }
    }
    private val activeLock = SynchronizedObject()
    private val active = mutableSetOf<Job>()
    fun cancelAll() { synchronized(activeLock) { active.toList() }.forEach { it.cancel() } }
    fun close() { cancelAll(); client.close() }
    suspend fun execute(request: Request, maximumBytes: Long = 8L * 1024 * 1024): NetworkResponse = coroutineScope {
        val output = Buffer()
        val response = transfer(request, maximumBytes, { _, _ -> }, { buffer, count -> output.write(buffer, 0, count) })
        NetworkResponse(response.first, response.second, output.readByteArray())
    }
    /** A real bounded download path; chunks are delivered before the next read rather than buffered in memory. */
    suspend fun download(request: Request, maximumBytes: Long, onChunk: (ByteArray, Int) -> Unit, onProgress: (Long, Long) -> Unit = { _, _ -> }): NetworkResponse {
        val response = transfer(request, maximumBytes, onProgress, onChunk)
        return NetworkResponse(response.first, response.second, ByteArray(0))
    }
    /** Validate response headers before opening an output or consuming its body. */
    internal suspend fun downloadWithHeaders(
        request: Request, maximumBytes: Long, onResponse: (NetworkResponse) -> Boolean,
        onChunk: (ByteArray, Int) -> Unit,
    ): NetworkResponse {
        val response = transfer(request, maximumBytes, { _, _ -> }, onChunk) { code, headers ->
            onResponse(NetworkResponse(code, headers, ByteArray(0)))
        }
        return NetworkResponse(response.first, response.second, ByteArray(0))
    }
    private suspend fun transfer(
        request: Request, maximumBytes: Long, progress: (Long, Long) -> Unit,
        write: (ByteArray, Int) -> Unit, acceptResponse: (Int, Map<String, String>) -> Boolean = { _, _ -> true },
    ): Pair<Int, Map<String, String>> = coroutineScope {
        require(maximumBytes >= 0)
        val job = currentCoroutineContext()[Job]!!
        synchronized(activeLock) { active.add(job) }
        val started = monotonicTimeMillis()
        var observedCode: Int? = null
        var observedHeaders: Map<String, String> = emptyMap()
        var received = 0L
        var failed = false
        var cancelled = false
        try {
            client.prepareRequest {
                url.takeFrom(request.url.toString())
                method = HttpMethod.parse(request.method)
                request.headers.forEach { (name, value) -> headers.append(name, value) }
                request.body?.let { body ->
                    if (request.headers.keys.none { it.equals("Content-Type", true) }) body.contentType?.let { headers.append("Content-Type", it) }
                    setBody(body.bytes)
                }
            }.execute { response ->
                if (selectedRoute?.mode == DownloadRouteMode.PROXY && response.status.value == 407) {
                    throw DownloadRouteException("这个代理需要身份验证；当前仅支持无需账号密码的 HTTP / SOCKS 代理。")
                }
                val responseHeaders = response.headers.entries().associate { it.key to it.value.joinToString(", ") }
                observedCode = response.status.value
                observedHeaders = responseHeaders
                if (!acceptResponse(response.status.value, responseHeaders)) return@execute response.status.value to responseHeaders
                val announced = response.headers["Content-Length"]?.toLongOrNull()
                require(announced == null || announced <= maximumBytes) { "Response exceeds the size limit" }
                val input = response.bodyAsChannel()
                val buffer = ByteArray(16 * 1024)
                progress(0, announced ?: -1)
                while (!input.isClosedForRead) {
                    currentCoroutineContext().ensureActive()
                    val count = input.readAvailable(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    received += count
                    require(received <= maximumBytes) { "Response exceeds the size limit" }
                    write(buffer, count)
                    progress(received, announced ?: -1)
                }
                input.closedCause?.let { throw it }
                response.status.value to responseHeaders
            }
        } catch (cancel: CancellationException) {
            cancelled = true
            throw cancel
        } catch (failure: Exception) {
            failed = true
            throw failure
        } finally {
            synchronized(activeLock) { active.remove(job) }
            if (!cancelled) runCatching {
                onTransfer?.invoke(NetworkTransferObservation(observedCode, observedHeaders, received,
                    monotonicTimeMillis() - started, failed))
            }
        }
    }
}
