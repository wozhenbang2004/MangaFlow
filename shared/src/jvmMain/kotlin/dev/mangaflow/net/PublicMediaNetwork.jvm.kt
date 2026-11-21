package dev.mangaflow.net

import dev.mangaflow.data.plugins.JvmScriptNetwork
import dev.mangaflow.data.plugins.publicSourceSocketFactory
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/** Only the opt-in HTTP media transport uses a checked, pinned public destination. */
internal fun OkHttpClient.Builder.applyPublicMediaNetwork(route: DownloadRouteSnapshot?): OkHttpClient.Builder = apply {
    val selected = route?.checkedActive()
    if (selected?.relayBinding == null && selected?.mode == DownloadRouteMode.PROXY &&
        selected.proxy?.type != DownloadProxyType.SOCKS) {
        throw DownloadRouteException("此 HTTP 媒体线路需要直连、SOCKS 或 Relay；当前 HTTP 代理无法固定公开目标地址。")
    }
    val sockets = selected?.let { publicSourceSocketFactory(it, JvmScriptNetwork::isPublic) }
    // SOCKS/Relay receive the checked numerical address through the existing
    // socket factory. Ordinary HTTPS clients keep their original proxy behavior.
    proxy(Proxy.NO_PROXY)
    dns(JvmScriptNetwork.PublicDns)
    if (sockets != null) socketFactory(sockets)
    addInterceptor { chain ->
        val url = chain.request().url
        try {
            selected?.checkedActive()
            require(url.username.isEmpty() && url.password.isEmpty() && url.toString().length <= 16_384)
            JvmScriptNetwork.checkPublicHost(url)
        } catch (failure: IllegalArgumentException) {
            throw IOException("媒体地址不是无内嵌凭据的公开网络地址。", failure)
        } catch (failure: DownloadRouteException) {
            throw IOException(failure.message, failure)
        }
        if (selected?.relayBinding == null && (selected == null || selected.mode == DownloadRouteMode.SYSTEM)) {
            val proxies = try { ProxySelector.getDefault()?.select(URI(url.toString())).orEmpty() }
            catch (failure: Exception) { throw IOException("无法核对 HTTP 媒体的系统代理，请选择明确的播放线路。", failure) }
            if (proxies.any { it.type() != Proxy.Type.DIRECT }) {
                throw IOException("此 HTTP 媒体不支持当前系统代理，请选择直连、SOCKS 或 Relay 播放线路。")
            }
        }
        chain.proceed(chain.request())
    }
}
