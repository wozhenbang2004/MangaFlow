package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext

enum class DownloadNetworkTransport { WIFI, CELLULAR, ETHERNET, OTHER }
enum class DownloadProxyScope { DEFAULT, HTTP, HTTPS, SOCKS }
enum class DownloadNetworkObservation { DEFAULT_NETWORK, SYSTEM_SETTINGS, APP_RUNTIME }

/** A publicly reported endpoint, never a guessed port or a live reachability result. */
data class DetectedDownloadProxy(
    val endpoint: DownloadProxyEndpoint,
    val scope: DownloadProxyScope = DownloadProxyScope.DEFAULT,
)

/** Passive system metadata; configuring a proxy does not prove which path a request uses. */
data class DownloadNetworkEnvironment(
    val defaultNetworkUsesVpn: Boolean?,
    val systemProxyConfigured: Boolean?,
    val connected: Boolean? = null,
    val transport: DownloadNetworkTransport? = null,
    val proxies: List<DetectedDownloadProxy> = emptyList(),
    val automaticProxyConfigured: Boolean? = null,
    val observation: DownloadNetworkObservation = DownloadNetworkObservation.DEFAULT_NETWORK,
)

/** Null means unavailable or unknown, including when there is no default network. */
expect fun readDownloadNetworkEnvironment(context: PlatformContext): DownloadNetworkEnvironment

/** Events only; callers release registration when the network panel leaves the foreground. */
expect fun observeDownloadNetworkEnvironment(context: PlatformContext, changed: () -> Unit): () -> Unit

internal fun detectedProxyEndpoint(host: String?, port: Int?, type: DownloadProxyType): DownloadProxyEndpoint? {
    if (host.isNullOrBlank() || port == null) return null
    return runCatching { DownloadRouteSnapshot(DownloadRouteMode.PROXY,
        DownloadProxyEndpoint(host, port, type)).checked().proxy }.getOrNull()
}
