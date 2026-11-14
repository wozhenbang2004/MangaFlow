package dev.mangaflow.data

import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.Request
import dev.mangaflow.platform.ConcurrentMap
import dev.mangaflow.platform.wallTimeMillis
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlin.math.ceil

internal data class SourceResponse(val text: String, val retryAfterSeconds: Long, val statusCode: Int = 200)

/** Anonymous and owner clients have distinct transports, jobs and cooldown maps, without shared cookies. */
internal fun sourceClient(http1Only: Boolean = false): NetworkClient = NetworkClient(http1Only = http1Only)

internal class SourceHttp(
    client: NetworkClient? = null,
    private val pauses: ConcurrentMap<String, Long> = sharedPauses,
) {
    private val ordinaryClient by lazy { client ?: sourceClient() }
    companion object {
        private const val MAX_TEXT_BYTES = 8L * 1024 * 1024
        private val sharedPauses = ConcurrentMap<String, Long>()
        private val ACCOUNT_ERROR_CODES = setOf(209, 210, 400, 401, 403, 422, 429)
    }
    fun rememberPause(url: HttpUrl, seconds: Long) {
        if (seconds <= 0) return
        val until = wallTimeMillis() + seconds.coerceAtMost(86_400) * 1_000
        pauses.merge(url.origin(), until) { current, incoming -> maxOf(current, incoming) }
    }
    suspend fun request(request: Request, operation: String, publicUrl: String = "", readAccountErrors: Boolean = false): SourceResponse {
        val remaining = pauses[request.url.origin()]?.minus(wallTimeMillis()) ?: 0
        if (remaining > 0) {
            val seconds = ceil(remaining / 1000.0).toLong()
            throw SourceException("$operation 暂时受限，请在 ${seconds} 秒后重试。", publicUrl, seconds)
        }
        val transport = currentCoroutineContext()[ChapterDownloadTransport]?.client ?: ordinaryClient
        val response = try { transport.execute(request, MAX_TEXT_BYTES) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: DownloadRouteException) { throw failure }
        catch (failure: Exception) { throw SourceException("$operation 连接或数据读取失败，请检查网络后重试。", publicUrl, cause = failure) }
        val retry = retryAfter(response.header("Retry-After")).let { if (response.code == 429 && it == 0L) 60L else it }
        when (response.code) {
            209, 210, 429 -> {
                rememberPause(request.url, retry)
                if (!readAccountErrors) {
                    val reason = runCatching {
                        val text = response.bytes.take(4096).toByteArray().decodeToString()
                        (JSONObject(text).opt("message") as? String)?.trim()?.take(240)
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                    throw SourceException("$operation 来源返回提示（${response.code}）" +
                        (reason?.let { "：$it" } ?: "，请稍后重试或查看原站提示。") +
                        (if (retry > 0) " ${retry} 秒后可重试。" else ""), publicUrl, retry, httpStatus = response.code)
                }
            }
            401, 403 -> if (!readAccountErrors) throw SourceException("$operation 被来源拒绝，请查看原站访问提示。", publicUrl, httpStatus = response.code)
        }
        if (!response.isSuccessful && !(readAccountErrors && response.code in ACCOUNT_ERROR_CODES)) {
            throw SourceException("$operation 暂时不可用（HTTP ${response.code}）。", publicUrl, httpStatus = response.code)
        }
        if (response.bytes.isEmpty()) throw SourceException("$operation 返回了空响应。", publicUrl)
        return SourceResponse(response.text(), retry, response.code)
    }
    private fun retryAfter(value: String?): Long {
        if (value == null) return 0
        value.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
        return try { ceil((value.fromHttpToGmtDate().timestamp - wallTimeMillis()) / 1000.0).toLong().coerceIn(0, 86_400) }
            catch (_: Exception) { 0 }
    }
    private fun HttpUrl.origin() = "$scheme://$host:$port"
}
