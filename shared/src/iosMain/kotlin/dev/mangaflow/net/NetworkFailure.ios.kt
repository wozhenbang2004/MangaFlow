package dev.mangaflow.net

import io.ktor.client.engine.darwin.DarwinHttpRequestException
import platform.Foundation.NSURLErrorDomain

internal actual fun platformNetworkFailureKind(failure: Throwable): NetworkFailureKind? {
    val error = (failure as? DarwinHttpRequestException)?.origin ?: return null
    if (error.domain != NSURLErrorDomain) return null
    return when (error.code) {
        -1009L -> NetworkFailureKind.NO_NETWORK
        -1003L, -1006L -> NetworkFailureKind.DNS
        -1004L, -1005L -> NetworkFailureKind.CONNECTION
        -1001L -> NetworkFailureKind.TRANSFER_TIMEOUT
        -1200L, -1201L, -1202L, -1203L, -1204L, -1205L, -1206L -> NetworkFailureKind.TLS
        else -> null
    }
}

internal actual fun platformTlsFailureDetail(failure: Throwable): String? {
    val error = (failure as? DarwinHttpRequestException)?.origin ?: return null
    if (error.domain != NSURLErrorDomain) return null
    return when (error.code) {
        -1201L -> "站点证书的有效期校验失败，请核对设备时间"
        -1202L, -1203L -> "系统未能信任站点证书"
        -1204L -> "站点证书尚未生效，请核对设备时间"
        else -> null
    }
}
