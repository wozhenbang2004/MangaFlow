package dev.mangaflow.net

import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.URL
import java.util.Base64
import javax.net.SocketFactory

/** Credentials are sent only in a loopback CONNECT handshake, never in an origin request. */
private data class RelaySocketObservation(val binding: RelayRouteBinding, val mode: DownloadRouteMode, val epoch: String)
private val relaySocketObservations = java.util.concurrent.ConcurrentHashMap<Int, RelaySocketObservation>()

/** Carry the hostname to CONNECT. Per-host ULA identities prevent cross-host HTTP/2 coalescing. */
private val relayDeferredDns = okhttp3.Dns { hostname ->
    val identity = java.security.MessageDigest.getInstance("SHA-256").digest(hostname.toByteArray(Charsets.UTF_8)).copyOf(16)
    identity[0] = 0xfd.toByte()
    // This address is an internal route identity; RelayTunnelSocketFactory never connects to it.
    listOf(InetAddress.getByAddress(hostname, identity))
}

internal class RelayTunnelSocketFactory(
    private val binding: RelayRouteBinding,
    private val mode: DownloadRouteMode,
    private val checkedDestination: ((InetAddress) -> Boolean)? = null,
) : SocketFactory() {
    override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
        private var observation: RelaySocketObservation? = null
        private var observationPort = -1
        override fun close() {
            observation?.let { relaySocketObservations.remove(observationPort, it) }
            super.close()
        }
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val target = endpoint as? InetSocketAddress ?: throw IOException("Relay 请求目标无效。")
            if (checkedDestination != null && (target.isUnresolved || target.address == null ||
                    !checkedDestination.invoke(target.address))) throw IOException("漫画源只能连接已核对的公开网络地址。")
            val managed = try { resolveRelayEndpoint(binding, mode) }
                catch (failure: DownloadRouteException) { throw IOException(failure.message, failure) }
            if (managed.host != "127.0.0.1" || managed.port !in 1..65535 ||
                managed.username.isBlank() || managed.password.isBlank() ||
                managed.username.length > 256 || managed.password.length > 512 ||
                managed.username.any { it <= ' ' || it >= '\u007f' || it == ':' } ||
                managed.password.any { it <= ' ' || it >= '\u007f' }) throw IOException("Relay 返回的专用入口无效。")
            // Script sources retain the existing public-address gate and send its numerical result.
            // Trusted native consumers retain the host so Relay can apply the exit's own DNS policy.
            val host = if (checkedDestination != null) target.address.hostAddress ?: throw IOException("目标地址无效。")
                else target.hostString
            if (host.isBlank() || host.length > 253 || host.any { it <= ' ' || it >= '\u007f' || it in "/\\@?#" } || '%' in host)
                throw IOException("Relay 请求主机无效。")
            val authority = (if (':' in host) "[$host]" else host) + ":" + target.port
            val authorization = Base64.getEncoder().encodeToString((managed.username + ":" + managed.password).toByteArray(Charsets.UTF_8))
            try {
                super.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), managed.port),
                    if (timeout > 0) minOf(timeout, 15_000) else 15_000)
                val priorTimeout = soTimeout
                soTimeout = 15_000
                getOutputStream().write(("CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n" +
                    "Proxy-Authorization: Basic $authorization\r\n\r\n").toByteArray(Charsets.US_ASCII))
                getOutputStream().flush()
                val header = ByteArrayOutputStream()
                var suffix = 0
                while (suffix != 0x0d0a0d0a) {
                    val next = getInputStream().read()
                    if (next < 0 || header.size() >= 8192) throw IOException("Relay 专用入口响应无效。")
                    header.write(next)
                    suffix = (suffix shl 8) or next
                }
                val status = header.toString("US-ASCII").substringBefore("\r\n")
                if (!status.matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) throw IOException("Relay 未接受此请求，请检查联动状态。")
                soTimeout = priorTimeout
                observationPort = localPort
                observation = RelaySocketObservation(binding, mode, managed.observationEpoch).also {
                    relaySocketObservations[observationPort] = it
                }
            } catch (failure: Exception) {
                try { close() } catch (_: Exception) { }
                if (failure is IOException) throw failure
                throw IOException("Relay 专用入口连接失败。", failure)
            }
        }
    }
    override fun createSocket(host: String, port: Int): Socket = unsupported()
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress, port: Int): Socket = unsupported()
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = unsupported()
    private fun unsupported(): Nothing = throw IOException("Relay 请求需要由网络客户端建立连接。")
}

internal fun OkHttpClient.Builder.observeRelayRoute(route: DownloadRouteSnapshot?, observeTraffic: Boolean = true): OkHttpClient.Builder = apply {
    val binding = route?.relayBinding ?: return@apply
    proxy(Proxy.NO_PROXY)
    addInterceptor { chain ->
        try { resolveRelayEndpoint(binding, route.mode) }
        catch (failure: DownloadRouteException) { throw IOException(failure.message, failure) }
        chain.proceed(chain.request())
    }
    if (observeTraffic) addNetworkInterceptor { chain ->
        val observed = chain.connection()?.socket()?.localPort?.let(relaySocketObservations::get)
        val response = chain.proceed(chain.request())
        if (response.code in 200..299 && observed != null && observed.binding == binding && observed.mode == route.mode)
            recordRelayRequest(binding, route.mode, observed.epoch)
        response
    }
}

internal fun OkHttpClient.Builder.applyDownloadRoute(route: DownloadRouteSnapshot?, observeTraffic: Boolean = true,
    publicMediaOnly: Boolean = false): OkHttpClient.Builder = apply {
    val selected = route?.checkedActive()
    addInterceptor { chain ->
        try { selected?.checkedActive() }
        catch (failure: DownloadRouteException) { throw IOException(failure.message, failure) }
        chain.proceed(chain.request())
    }
    if (!publicMediaOnly) {
        // Moving dynamic media HTTP permission into Android's platform config
        // must not expand ordinary API/image/update clients. The network guard
        // also checks each hop of clients that follow redirects automatically.
        val cleartextGuard = okhttp3.Interceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps && !isDefaultCleartextAllowed(url.host))
                throw IOException("此网络通道仅允许 HTTPS 或已授权的来源接口")
            chain.proceed(chain.request())
        }
        addInterceptor(cleartextGuard)
        addNetworkInterceptor(cleartextGuard)
    }
    if (selected?.relayBinding != null) {
        dns(relayDeferredDns)
        socketFactory(RelayTunnelSocketFactory(selected.relayBinding, selected.mode))
        observeRelayRoute(selected, observeTraffic)
    } else proxy(selected.javaProxy())
}

/** The update downloader keeps its existing streaming/redirect/Range contract. */
internal fun DownloadRouteSnapshot.openDownloadConnection(url: URL): HttpURLConnection {
    checkedActive()
    if (relayBinding == null) return (javaProxy()?.let { url.openConnection(it) } ?: url.openConnection()) as HttpURLConnection
    return RelayDownloadConnection(url, this)
}

private class RelayDownloadConnection(url: URL, private val route: DownloadRouteSnapshot) : HttpURLConnection(url) {
    private var call: okhttp3.Call? = null
    private var response: okhttp3.Response? = null
    private var client: OkHttpClient? = null
    override fun connect() {
        if (connected) return
        val created = OkHttpClient.Builder().applyDownloadRoute(route).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(connectTimeout.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS).build()
        client = created
        val request = okhttp3.Request.Builder().url(url).method(requestMethod, null)
        requestProperties.forEach { (name, values) -> values.forEach { request.addHeader(name, it) } }
        val current = created.newCall(request.build())
        call = current
        try { response = current.execute(); connected = true }
        catch (failure: Exception) { disconnect(); throw failure }
    }
    override fun getResponseCode(): Int { connect(); return requireNotNull(response).code }
    override fun getInputStream(): java.io.InputStream { connect(); return requireNotNull(response?.body).byteStream() }
    override fun getErrorStream(): java.io.InputStream? { connect(); return response?.takeIf { it.code >= 400 }?.body?.byteStream() }
    override fun getHeaderField(name: String?): String? { connect(); return if (name == null) response?.message else response?.header(name) }
    override fun getHeaderFields(): Map<String, List<String>> { connect(); return response?.headers?.toMultimap().orEmpty() }
    override fun getContentLengthLong(): Long { connect(); return response?.body?.contentLength() ?: -1 }
    override fun getContentLength(): Int = contentLengthLong.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt() ?: -1
    override fun usingProxy(): Boolean = true
    override fun disconnect() {
        call?.cancel(); response?.close(); response = null
        client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown(); client = null
        connected = false
    }
}
