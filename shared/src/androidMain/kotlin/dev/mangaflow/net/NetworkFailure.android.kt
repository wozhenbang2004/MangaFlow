package dev.mangaflow.net

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

internal actual fun platformNetworkFailureKind(failure: Throwable): NetworkFailureKind? = when (failure) {
    is SSLException -> NetworkFailureKind.TLS
    is UnknownHostException -> NetworkFailureKind.DNS
    is SocketTimeoutException -> NetworkFailureKind.TRANSFER_TIMEOUT
    is NoRouteToHostException, is ConnectException -> NetworkFailureKind.CONNECTION
    else -> null
}
