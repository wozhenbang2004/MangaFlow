package dev.mangaflow.data.plugins

import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.checked
import dev.mangaflow.net.javaProxy
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/**
 * Keep OkHttp's checked public DNS and original TLS host. A resolved SOCKS target is
 * encoded as an IP by Android's Socket implementation, never resolved again by the proxy.
 */
internal fun publicSourceSocketFactory(
    selected: DownloadRouteSnapshot,
    isPublic: (InetAddress) -> Boolean,
): SocketFactory? {
    val route = selected.checked()
    if (route.mode != DownloadRouteMode.PROXY) return null
    if (route.proxy?.type != DownloadProxyType.SOCKS) {
        throw DownloadRouteException("第三方源下载暂不支持 HTTP 代理，请为此类选择 SOCKS 代理或直连。")
    }
    val proxy = route.javaProxy() ?: throw DownloadRouteException("下载代理未配置。")
    return PublicSourceSocksFactory(proxy, isPublic)
}

private class PublicSourceSocksFactory(
    private val proxy: Proxy,
    private val isPublic: (InetAddress) -> Boolean,
) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket(proxy) {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val address = endpoint as? InetSocketAddress
                ?: throw IOException("漫画源代理缺少已核对的网络地址。")
            val target = address.address
            if (address.isUnresolved || target == null || !isPublic(target)) {
                throw IOException("漫画源代理只能连接已核对的公开网络地址。")
            }
            super.connect(address, timeout)
        }
    }

    // OkHttp only uses the unconnected overload; other callers must not bypass its checked DNS.
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()

    private fun unsupported(): Nothing = throw IOException("漫画源代理需要先核对目标地址。")
}
