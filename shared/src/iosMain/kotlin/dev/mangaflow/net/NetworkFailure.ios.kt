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
