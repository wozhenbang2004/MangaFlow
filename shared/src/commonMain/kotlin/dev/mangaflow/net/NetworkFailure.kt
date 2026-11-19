package dev.mangaflow.net

import dev.mangaflow.platform.wallTimeMillis
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.fromHttpToGmtDate
import okio.IOException

internal enum class NetworkFailureKind(val message: String) {
    NO_NETWORK("设备当前没有可用网络"),
    DNS("无法解析来源地址，请检查网络"),
    CONNECTION("无法连接来源，请检查所选线路"),
    TLS("未能完成来源安全连接"),
    CONNECT_TIMEOUT("连接来源超时，请检查所选线路"),
    RESPONSE_TIMEOUT("等待来源响应超时，请稍后重试"),
    TRANSFER_TIMEOUT("图片传输已停止响应，稍后从有效断点继续"),
    REQUEST_TIMEOUT("图片传输超过本次安全时限，稍后从有效断点继续"),
}

internal class NetworkTransferException(
    val kind: NetworkFailureKind, message: String, cause: Throwable? = null,
) : IOException(message, cause)

internal expect fun platformNetworkFailureKind(failure: Throwable): NetworkFailureKind?
internal expect fun platformTlsFailureDetail(failure: Throwable): String?

internal fun networkFailureKind(failure: Throwable): NetworkFailureKind? {
    var current: Throwable? = failure
    repeat(8) {
        val candidate = current ?: return null
        when (candidate) {
            is NetworkTransferException -> return candidate.kind
            is ConnectTimeoutException -> return NetworkFailureKind.CONNECT_TIMEOUT
            is SocketTimeoutException -> return NetworkFailureKind.TRANSFER_TIMEOUT
            is HttpRequestTimeoutException -> return NetworkFailureKind.REQUEST_TIMEOUT
        }
        platformNetworkFailureKind(candidate)?.let { return it }
        val next = candidate.cause
        if (next === candidate) return null
        current = next
    }
    return null
}

/** Only report explicit, platform-typed evidence. Never copy exception text into diagnostics. */
internal fun networkTlsFailureDetail(failure: Throwable): String? {
    var current: Throwable? = failure
    var detail: String? = null
    repeat(8) {
        val candidate = current ?: return detail
        platformTlsFailureDetail(candidate)?.let { detail = it }
        val next = candidate.cause
        if (next === candidate) return detail
        current = next
    }
    return detail
}

/** Retry-After is either a nonnegative number of seconds or an HTTP date. */
internal fun retryAfterMillis(header: String?, now: Long = wallTimeMillis()): Long? {
    val value = header?.trim()?.takeIf { it.length in 1..128 } ?: return null
    if (value.all { it in '0'..'9' }) {
        return (value.toLongOrNull() ?: 86_400L).coerceAtMost(86_400L) * 1000L
    }
    return runCatching { (value.fromHttpToGmtDate().timestamp - now).coerceIn(0L, 86_400_000L) }.getOrNull()
}
