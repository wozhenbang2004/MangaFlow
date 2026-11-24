package dev.mangaflow.net

import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Installed by the desktop application's consent/session owner, not by an HTTP request. */
interface DesktopRelayTunnelProvider {
    /** A local observation of the current selected scope, consent, mode and live generation. Never performs I/O. */
    fun isCurrent(binding: DesktopRelayRouteBinding): Boolean

    /** A fresh scoped credential acquired on an OkHttp worker, with the companion's own bounded timeout. */
    fun open(binding: DesktopRelayRouteBinding): DesktopRelayEndpoint
}

/** The verified service echoes the exact active binding and generation; credentials stay in memory. */
class DesktopRelayEndpoint(
    val bindingId: String,
    val generation: String,
    val mode: DownloadRouteMode,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val ownerPid: Int,
    val ownerStartTicks: Long,
) {
    override fun toString(): String = "DesktopRelayEndpoint(redacted)"
}

object DesktopRelayTransport {
    @Volatile private var provider: DesktopRelayTunnelProvider? = null

    fun install(provider: DesktopRelayTunnelProvider) {
        this.provider = provider
    }

    internal fun owner(): DesktopRelayTunnelProvider {
        if (!System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true))
            throw DownloadRouteException("当前系统不支持 Relay 桌面目录线路。")
        return provider ?: throw DownloadRouteException("尚未接通已授权的 Relay 桌面服务。")
    }

    internal fun requireCurrent(owner: DesktopRelayTunnelProvider, binding: DesktopRelayRouteBinding) {
        val current = try { provider === owner && owner.isCurrent(binding) && provider === owner }
        catch (_: Exception) { false }
        if (!current) throw IOException("Relay 桌面目录线路已改变或失效，请重新核对。")
    }
}

private const val CatalogueHost = "m.douban.com"
private val cataloguePaths = setOf("/rexxar/api/v2/tv/recommend", "/rexxar/api/v2/movie/recommend")

private fun requireCatalogueRequest(request: Request) {
    val url = request.url
    val host = request.header("Host")
    if (request.method != "GET" || request.body != null || !url.isHttps || url.host != CatalogueHost || url.port != 443 ||
        url.encodedPath !in cataloguePaths || url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() ||
        url.fragment != null || host != null && host != CatalogueHost && host != "$CatalogueHost:443" ||
        request.header("Authorization") != null || request.header("Cookie") != null ||
        request.header("Proxy-Authorization") != null) {
        throw IOException("Relay 桌面线路仅允许公开的豆瓣动画目录，不会改走其他线路。")
    }
}

private fun requireCataloguePeer(socket: Socket, endpoint: DesktopRelayEndpoint) {
    if (!socket.isConnected || socket.isClosed || socket.inetAddress?.hostAddress != "127.0.0.1" ||
        socket.localAddress?.hostAddress != "127.0.0.1" || socket.port != endpoint.port || socket.localPort !in 1..65535) {
        throw IOException("无法核对实际的 Relay 本机连接。")
    }
    val verified = try { verifyWindowsRelaySocketPeer(socket, endpoint.ownerPid, endpoint.ownerStartTicks) }
    catch (_: Exception) { false }
    catch (_: LinkageError) { false }
    if (!verified) throw IOException("实际连接不属于已授权的 Relay 服务进程；未发送凭据。")
}

/** No default proxy, local DNS or pooled connection can substitute for this exact authenticated tunnel. */
internal fun OkHttpClient.Builder.applyDesktopCatalogueRelay(binding: DesktopRelayRouteBinding): OkHttpClient.Builder = apply {
    val owner = DesktopRelayTransport.owner()
    fun requireCurrent() = DesktopRelayTransport.requireCurrent(owner, binding)

    proxy(Proxy.NO_PROXY)
    protocols(listOf(Protocol.HTTP_1_1))
    connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
    authenticator(Authenticator.NONE)
    proxyAuthenticator(Authenticator.NONE)
    dns { hostname ->
        if (hostname != CatalogueHost) throw UnknownHostException("此主机不属于 Relay 桌面目录线路。")
        relayDeferredDns.lookup(hostname)
    }
    socketFactory(RelayTunnelSocketFactory.catalogue { target ->
        if (target.hostString != CatalogueHost || target.port != 443) throw IOException("不允许连接此 Relay 目录目标。")
        requireCurrent()
        val endpoint = try { owner.open(binding) }
        catch (_: Exception) { throw IOException("Relay 未返回有效的目录连接，请核对联动。") }
        requireCurrent()
        if (endpoint.bindingId != binding.bindingId || endpoint.generation != binding.generation || endpoint.mode != binding.mode)
            throw IOException("Relay 返回了不同的目录线路或服务代次。")
        if (endpoint.ownerPid <= 0 || endpoint.ownerStartTicks <= 0L)
            throw IOException("Relay 没有提供可核对的连接进程；未发送凭据。")
        val provePeer: (Socket) -> Unit = { socket ->
            requireCurrent()
            requireCataloguePeer(socket, endpoint)
            requireCurrent()
        }
        // The CONNECT factory checks the endpoint and proves the actual socket before sending credentials.
        RelayManagedEndpoint(endpoint.host, endpoint.port, endpoint.username, endpoint.password, endpoint.generation) to provePeer
    })

    // Check the public request before DNS/CONNECT and again immediately before writing origin bytes.
    addInterceptor { chain ->
        requireCatalogueRequest(chain.request())
        requireCurrent()
        val response = chain.proceed(chain.request())
        try {
            requireCurrent()
            // AnimeHttp follows some redirects itself. Keep them from leaving this fixed operation.
            if (response.code in 300..399) throw IOException("Relay 公开目录不跟随重定向。")
            response
        } catch (failure: IOException) {
            response.close()
            throw failure
        }
    }
    addNetworkInterceptor(Interceptor { chain ->
        requireCatalogueRequest(chain.request())
        requireCurrent()
        chain.proceed(chain.request())
    })
}
