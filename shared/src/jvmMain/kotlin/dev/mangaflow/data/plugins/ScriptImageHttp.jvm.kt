package dev.mangaflow.data.plugins

import dev.mangaflow.data.SourceException
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fetch real original bytes, retaining the source's DNS/TLS and immutable routing guarantees. */
internal actual suspend fun fetchScriptImageBytes(context: PlatformContext, url: String,
    headers: Map<String, String>, route: DownloadRouteSnapshot?): ByteArray {
    var current = publicPluginUrl(url).toString().toHttpUrlOrNull() ?: error("来源图片地址无效。")
    var actualHeaders = JvmScriptNetwork.checkedHeaders(JSONObject(headers)).filterKeys { !it.equals("Accept-Encoding", true) }
    val sockets = route?.let { publicSourceSocketFactory(it, JvmScriptNetwork::isPublic) }
    val client = JvmScriptNetwork.defaultClient.newBuilder().connectionPool(ConnectionPool()).dispatcher(Dispatcher())
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .apply { if (sockets != null) socketFactory(sockets) }.build()
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
            val result = suspendCancellableCoroutine<ScriptImageHttpResult> { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, failure: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(SourceException("来源图片连接失败。", cause = failure))
                    }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val value = response.use { currentResponse ->
                                val location = currentResponse.header("Location")
                                if (currentResponse.code in setOf(301, 302, 303, 307, 308))
                                    return@use ScriptImageHttpResult(currentResponse.code, location, ByteArray(0))
                                if (!currentResponse.isSuccessful) throw SourceException("来源图片暂时无法读取。", httpStatus = currentResponse.code)
                                val body = currentResponse.body ?: error("来源图片为空。")
                                require(body.contentLength() <= MAX_SCRIPT_IMAGE_BYTES) { "来源图片超过 96 MiB。" }
                                val output = ByteArrayOutputStream()
                                body.byteStream().use { input ->
                                    val chunk = ByteArray(16_384)
                                    while (continuation.isActive) {
                                        val count = input.read(chunk)
                                        if (count < 0) break
                                        require(output.size().toLong() + count <= MAX_SCRIPT_IMAGE_BYTES) { "来源图片超过 96 MiB。" }
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

private const val MAX_SCRIPT_IMAGE_BYTES = 96L * 1024 * 1024
private data class ScriptImageHttpResult(val code: Int, val location: String?, val bytes: ByteArray)
private fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"
