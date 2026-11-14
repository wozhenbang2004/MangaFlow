package dev.mangaflow.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy

internal actual fun platformHttpClient(http1Only: Boolean, route: DownloadRouteSnapshot?, configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            if (http1Only) protocols(listOf(Protocol.HTTP_1_1))
            when (route?.mode) {
                DownloadRouteMode.DIRECT -> proxy(Proxy.NO_PROXY)
                DownloadRouteMode.PROXY -> {
                    val endpoint = requireNotNull(route.proxy)
                    val type = if (endpoint.type == DownloadProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS
                    proxy(Proxy(type, InetSocketAddress.createUnresolved(endpoint.host, endpoint.port)))
                }
                DownloadRouteMode.SYSTEM, null -> Unit
            }
        }
    }
    configure(this)
}
