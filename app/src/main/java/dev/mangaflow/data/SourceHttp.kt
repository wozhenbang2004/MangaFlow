package dev.mangaflow.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

internal data class SourceResponse(val text: String, val retryAfterSeconds: Long, val statusCode: Int = 200)

/** Anonymous clients deliberately have no account interceptors or token store. */
internal fun sourceClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .callTimeout(45, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

internal class SourceHttp(private val client: OkHttpClient = sourceClient()) {
    companion object {
        private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
        private val pauses = ConcurrentHashMap<String, Long>()
        private val ACCOUNT_ERROR_CODES = setOf(209, 210, 400, 401, 403, 422, 429)
    }

    fun rememberPause(url: HttpUrl, seconds: Long) {
        if (seconds <= 0) return
        val until = System.currentTimeMillis() + seconds.coerceAtMost(86_400) * 1_000
        pauses.merge(url.origin(), until) { current, incoming -> maxOf(current, incoming) }
    }

    suspend fun request(request: Request, operation: String, publicUrl: String = "", readAccountErrors: Boolean = false): SourceResponse {
        val remaining = pauses[request.url.origin()]?.minus(System.currentTimeMillis()) ?: 0
        if (remaining > 0) {
            val seconds = ceil(remaining / 1000.0).toLong()
            throw SourceException("$operation 暂时受限，请在 ${seconds} 秒后重试。", publicUrl, seconds)
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(
                        SourceException("$operation 连接失败，请检查网络后重试。", publicUrl, cause = e),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { current ->
                            val retry = retryAfter(current.header("Retry-After")).let {
                                if (current.code == 429 && it == 0L) 60L else it
                            }
                            when (current.code) {
                                209, 210, 429 -> {
                                    val pause = retry
                                    rememberPause(request.url, pause)
                                    // Account errors are interpreted and redacted by the
                                    // account parser before any message reaches the UI.
                                    if (!readAccountErrors) {
                                        val reason = runCatching {
                                            val json = JSONObject(current.peekBody(4096).string())
                                            (json.opt("message") as? String)?.trim()?.take(240)
                                        }.getOrNull()?.takeIf { it.isNotBlank() }
                                        throw SourceException(
                                            "$operation 来源返回提示（${current.code}）" +
                                                (reason?.let { "：$it" } ?: "，请稍后重试或查看原站提示。") +
                                                (if (pause > 0) " ${pause} 秒后可重试。" else ""),
                                            publicUrl,
                                            pause,
                                        )
                                    }
                                }
                                401, 403 -> if (!readAccountErrors) {
                                    throw SourceException("$operation 被来源拒绝，请查看原站访问提示。", publicUrl)
                                }
                            }
                            if (!current.isSuccessful && !(readAccountErrors && current.code in ACCOUNT_ERROR_CODES)) {
                                throw SourceException("$operation 暂时不可用（HTTP ${current.code}）。", publicUrl)
                            }
                            val body = current.body ?: throw SourceException("$operation 返回了空响应。", publicUrl)
                            if (body.contentLength() > MAX_TEXT_BYTES) throw SourceException("$operation 响应过大。", publicUrl)
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(16 * 1024)
                                while (continuation.isActive) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > MAX_TEXT_BYTES) throw SourceException("$operation 响应过大。", publicUrl)
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (!continuation.isActive) return
                            if (output.size() == 0) throw SourceException("$operation 返回了空响应。", publicUrl)
                            SourceResponse(output.toString(Charsets.UTF_8.name()), retry, current.code)
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is SourceException) error
                            else SourceException("$operation 数据读取失败，请重试。", publicUrl, cause = error),
                        )
                    }
                }
            })
        }
    }

    private fun retryAfter(value: String?): Long {
        if (value == null) return 0
        value.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
        return try {
            val date = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            ceil((date - System.currentTimeMillis()) / 1000.0).toLong().coerceIn(0, 86_400)
        } catch (_: Exception) { 0 }
    }

    private fun HttpUrl.origin() = "$scheme://$host:$port"
}
