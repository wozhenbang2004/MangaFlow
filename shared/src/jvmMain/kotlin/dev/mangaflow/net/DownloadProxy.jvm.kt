package dev.mangaflow.net

import java.net.InetSocketAddress
import java.net.Proxy

/** A task-local proxy. SYSTEM deliberately leaves the platform's selector untouched. */
internal fun DownloadRouteSnapshot?.javaProxy(): Proxy? {
    val route = this?.checked() ?: return null
    return when (route.mode) {
        DownloadRouteMode.SYSTEM -> null
        DownloadRouteMode.DIRECT -> Proxy.NO_PROXY
        DownloadRouteMode.PROXY -> {
            val endpoint = route.proxy ?: throw DownloadRouteException("请先配置下载代理。")
            Proxy(if (endpoint.type == DownloadProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved(endpoint.host, endpoint.port))
        }
    }
}
