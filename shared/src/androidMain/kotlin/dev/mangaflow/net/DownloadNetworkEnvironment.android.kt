package dev.mangaflow.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.mangaflow.platform.PlatformContext

actual fun readDownloadNetworkEnvironment(context: PlatformContext): DownloadNetworkEnvironment {
    val manager = try {
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (_: Exception) { null }
        ?: return DownloadNetworkEnvironment(null, null)
    val network = try { manager.activeNetwork } catch (_: Exception) { null }
        ?: return DownloadNetworkEnvironment(null, null)
    // Query both properties for the same captured network. A handoff or denied read
    // can make either property unknown without inventing a negative result.
    val usesVpn = try {
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } catch (_: Exception) { null }
    val proxyConfigured = try {
        manager.getLinkProperties(network)?.let { it.httpProxy != null }
    } catch (_: Exception) { null }
    val sameDefaultNetwork = try { manager.activeNetwork == network } catch (_: Exception) { false }
    if (!sameDefaultNetwork) return DownloadNetworkEnvironment(null, null)
    return DownloadNetworkEnvironment(usesVpn, proxyConfigured)
}
