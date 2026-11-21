package dev.mangaflow.net

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSHTTPCookieAcceptPolicy.NSHTTPCookieAcceptPolicyNever

internal actual fun platformHttpClient(http1Only: Boolean, route: DownloadRouteSnapshot?, observeRelayTraffic: Boolean,
    publicMediaOnly: Boolean, configure: HttpClientConfig<*>.() -> Unit): HttpClient {
    // NSURLSession has no public per-request DNS-pinning hook. Keep ATS and the
    // existing HTTPS route instead of opening an unverified cleartext transport.
    if (publicMediaOnly) throw DownloadRouteException("当前 iOS 媒体通道暂不支持此 HTTP 地址，请选择 HTTPS 线路。")
    if (route?.relayBinding != null) throw DownloadRouteException("Relay 联动线路仅支持 Android，请选择当前设备的线路。")
    val endpoint = route?.takeIf { it.mode == DownloadRouteMode.PROXY }?.proxy
    if (endpoint?.type == DownloadProxyType.HTTP) {
        throw DownloadRouteException("当前 iOS 下载引擎不支持通过 HTTP 代理下载 HTTPS 文件，请改用 SOCKS 代理或其他线路。")
    }
    return HttpClient(Darwin) {
        engine {
            if (endpoint != null) proxy = ProxyBuilder.socks(endpoint.host, endpoint.port)
            configureSession {
                HTTPCookieAcceptPolicy = NSHTTPCookieAcceptPolicyNever
                HTTPCookieStorage = null
                URLCache = null
                if (route?.mode == DownloadRouteMode.DIRECT) {
                    connectionProxyDictionary = mapOf<Any?, Any?>(
                        "HTTPEnable" to 0, "HTTPSEnable" to 0, "SOCKSEnable" to 0,
                        "ProxyAutoConfigEnable" to 0, "ProxyAutoDiscoveryEnable" to 0,
                    )
                }
            }
        }
        configure(this)
    }
}
