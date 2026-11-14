package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext

/** Metadata for this app's default network, not detection of every external proxy. */
data class DownloadNetworkEnvironment(
    val defaultNetworkUsesVpn: Boolean?,
    val systemProxyConfigured: Boolean?,
)

/** Null means unavailable or unknown, including when there is no default network. */
expect fun readDownloadNetworkEnvironment(context: PlatformContext): DownloadNetworkEnvironment
