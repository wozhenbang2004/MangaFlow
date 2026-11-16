package dev.mangaflow.data.plugins

import dev.mangaflow.net.observeRelayRoute

import dev.mangaflow.data.SourceException
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.imageTimeouts
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.NetworkFailureKind
import dev.mangaflow.net.NetworkTransferException
import dev.mangaflow.net.retryAfterMillis
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

/** Fetch real original bytes, retaining the source's DNS/TLS and immutable routing guarantees. */
internal actual suspend fun fetchScriptImageBytes(context: PlatformContext, url: String,
    headers: Map<String, String>, route: DownloadRouteSnapshot?, onBytes: ((Long) -> Unit)?,
    onResponse: ((Int, Map<String, String>) -> Unit)?): ByteArray {
    val configuration = ExperiencePolicyStore.get(context).state.value
    val runtime = configuration.runtime
    val timeouts = configuration.imageTimeouts()
    val maxImageBytes = runtime.maxImageBytes
    var current = publicPluginUrl(url).toString().toHttpUrlOrNull() ?: error("来源图片地址无效。")
    val responseObserver = onResponse
    var actualHeaders = JvmScriptNetwork.checkedHeaders(JSONObject(headers)).filterKeys { !it.equals("Accept-Encoding", true) }
    val sockets = route?.let { publicSourceSocketFactory(it, JvmScriptNetwork::isPublic) }
    val client = JvmScriptNetwork.defaultClient.newBuilder().connectionPool(ConnectionPool()).dispatcher(Dispatcher())
        .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.idleMillis, TimeUnit.MILLISECONDS).callTimeout(timeouts.totalMillis, TimeUnit.MILLISECONDS)
        .apply { if (sockets != null) socketFactory(sockets) }.observeRelayRoute(route).build()
    try {
        repeat(4) { redirects ->
            currentCoroutineContext().ensureActive()
            if (route?.mode == DownloadRouteMode.SYSTEM) {
                val proxies = try { ProxySelector.getDefault()?.select(URI(current.toString())).orEmpty() }
                    catch (_: Exception) { throw DownloadRouteException("无法核对脚本图片的系统代理，请选择直连或 SOCKS5 代理。") }
                if (proxies.any { it.type() != Proxy.Type.DIRECT }) throw DownloadRouteException("脚本图片暂不支持系统代理，请选择直连或指定 SOCKS5 代理。")
            }
            JvmScriptNetwork.reserveOrigin(current)
            val request = Request.Builder().url(current)
                .apply { actualHeaders.forEach { (name, value) -> header(name, value) } }.build()
            val requestContext = currentCoroutineContext()
            val result = suspendCancellableCoroutine<ScriptImageHttpResult> { continuation ->
                val call = client.newCall(request)
                // One winner between response headers and their deadline: waiting/received/expired.
                val headerState = AtomicInteger(0)
                val responseDeadline = CoroutineScope(requestContext).launch {
                    delay(timeouts.idleMillis)
                    if (headerState.compareAndSet(0, 2)) call.cancel()
                }
                continuation.invokeOnCancellation { responseDeadline.cancel(); call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, failure: IOException) {
                        headerState.compareAndSet(0, 1)
                        responseDeadline.cancel()
                        if (continuation.isActive) continuation.resumeWithException(
                            if (headerState.get() == 2) NetworkTransferException(NetworkFailureKind.RESPONSE_TIMEOUT,
                                "等待来源图片响应超时，请稍后重试。", failure)
                            else SourceException("来源图片连接失败。", cause = failure))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        headerState.compareAndSet(0, 1)
                        responseDeadline.cancel()
                        try {
                            val value = response.use { currentResponse ->
                                if (headerState.get() == 2) throw NetworkTransferException(NetworkFailureKind.RESPONSE_TIMEOUT,
                                    "等待来源图片响应超时，请稍后重试。")
                                val responseHeaders = currentResponse.headers.toMultimap().mapValues { it.value.joinToString(", ") }
                                responseObserver?.invoke(currentResponse.code, responseHeaders)
                                val location = currentResponse.header("Location")
                                if (currentResponse.code in setOf(301, 302, 303, 307, 308))
                                    return@use ScriptImageHttpResult(currentResponse.code, location, ByteArray(0))
                                if (!currentResponse.isSuccessful) {
                                    val limited = currentResponse.code in setOf(429, 503)
                                    val retry = retryAfterMillis(currentResponse.header("Retry-After")) ?: if (limited) 30_000L else 0L
                                    val seconds = ceil(retry / 1000.0).toLong()
                                    if (limited) JvmScriptNetwork.rememberPause(current, seconds.coerceAtLeast(1L))
                                    throw SourceException(when (currentResponse.code) {
                                        401 -> "来源登录已失效，请检查登录状态。"
                                        403 -> "来源拒绝图片访问，请检查来源授权。"
                                        404, 410 -> "来源图片地址已失效，请刷新本章。"
                                        429 -> "来源限制图片请求，请稍后重试。"
                                        503 -> "来源图片服务暂时不可用，请稍后重试。"
                                        else -> "来源图片暂时无法读取。"
                                    }, retryAfterSeconds = seconds, httpStatus = currentResponse.code)
                                }
                                val body = currentResponse.body ?: error("来源图片为空。")
                                require(body.contentLength() <= maxImageBytes) { "来源图片超过配置的 ${runtime.maxImageMiB} MiB。" }
                                require(body.contentLength() <= Int.MAX_VALUE.toLong()) { "来源图片超过平台数组容量。" }
                                val output = ByteArrayOutputStream()
                                body.byteStream().use { input ->
                                    val chunk = ByteArray(16_384)
                                    while (continuation.isActive) {
                                        val count = input.read(chunk)
                                        if (count < 0) break
                                        if (count == 0) continue
                                        require(output.size().toLong() + count <= maxImageBytes) { "来源图片超过配置的 ${runtime.maxImageMiB} MiB。" }
                                        require(output.size().toLong() + count <= Int.MAX_VALUE.toLong()) { "来源图片超过平台数组容量。" }
                                        onBytes?.invoke(count.toLong())
                                        output.write(chunk, 0, count)
                                    }
                                }
                                require(output.size() > 0) { "来源图片为空。" }
                                ScriptImageHttpResult(currentResponse.code, null, output.toByteArray())
                            }
                            if (continuation.isActive) continuation.resume(value)
                        } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                    }
                })
            }
            if (result.code in setOf(301, 302, 303, 307, 308)) {
                require(redirects < 3) { "图片重定向次数过多。" }
                val target = current.resolve(result.location ?: error("图片重定向缺少目标。")) ?: error("图片重定向地址无效。")
                publicPluginUrl(target.toString())
                if (origin(current) != origin(target)) actualHeaders = actualHeaders.filterKeys { !it.equals("Authorization", true) && !it.equals("Cookie", true) }
                current = target
            } else return result.bytes
        }
        error("图片重定向次数过多。")
    } finally { client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
}

private data class ScriptImageHttpResult(val code: Int, val location: String?, val bytes: ByteArray)
private fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"
