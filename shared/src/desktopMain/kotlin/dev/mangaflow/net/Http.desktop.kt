package dev.mangaflow.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy

// Unconfigured clients retain their existing ProxySelector; transfer overrides stay client-local.
internal actual fun platformHttpClient(http1Only: Boolean, route: DownloadRouteSnapshot?, observeRelayTraffic: Boolean,
    publicMediaOnly: Boolean, configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    if (route?.relayBinding != null) throw DownloadRouteException("Relay 联动线路仅支持 Android，请选择当前设备的线路。")
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            retryOnConnectionFailure(false)
            val desktopBinding = route?.desktopRelayBinding
            if (desktopBinding != null) {
                if (publicMediaOnly) throw DownloadRouteException("Relay 桌面联动仅适用于公开动画目录。")
                requireNotNull(route).checkedActiveForHttp()
                applyDesktopCatalogueRelay(desktopBinding)
            } else {
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
                if (publicMediaOnly) applyPublicMediaNetwork(route)
            }
        }
    }
    configure(this)
}

// Desktop did not have Android's platform cleartext restriction.
internal fun isDefaultCleartextAllowed(@Suppress("UNUSED_PARAMETER") host: String): Boolean = true
