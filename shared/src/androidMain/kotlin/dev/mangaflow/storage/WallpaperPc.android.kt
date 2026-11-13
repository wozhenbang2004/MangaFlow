package dev.mangaflow.storage

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import dev.mangaflow.platform.PlatformContext
import java.net.Inet4Address
import java.net.NetworkInterface

actual fun createWallpaperPcClient(context: PlatformContext): WallpaperPcClient {
    val app = context.applicationContext
    val metrics = app.resources.displayMetrics
    val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    fun localNetwork(destination: Inet4Address? = null): Network? {
        val manager = connectivity ?: return null
        val networks = manager.allNetworks.filter { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@filter false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                manager.getLinkProperties(network)?.linkAddresses?.any { it.address is Inet4Address } == true
        }
        // The socket belongs to the physical LAN, not a process-wide VPN/default-route change.
        // No INTERNET capability is required: local-only Wi-Fi remains a valid pairing network.
        if (destination != null) networks.firstOrNull { network ->
            manager.getLinkProperties(network)?.linkAddresses?.any { link ->
                val source = link.address as? Inet4Address ?: return@any false
                sameIpv4Subnet(source, destination, link.prefixLength)
            } == true
        }?.let { return it }
        return networks.firstOrNull { it == manager.activeNetwork } ?: networks.firstOrNull()
    }
    return JvmWallpaperPcClient(app, Build.MODEL, metrics.widthPixels, metrics.heightPixels,
        acquireDiscovery = {
            val manager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = manager?.createMulticastLock("mangaflow-wallpaper-pc")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val release: () -> Unit = { if (lock?.isHeld == true) lock.release() }
            release
        },
        routeDiscovery = { socket ->
            localNetwork()?.let { network ->
                network.bindSocket(socket)
                connectivity?.getLinkProperties(network)?.interfaceName?.let(NetworkInterface::getByName)
            }
        },
        routeConnection = { socket, destination ->
            if (!destination.isLoopbackAddress) localNetwork(destination)?.bindSocket(socket)
        },
    )
}

private fun sameIpv4Subnet(source: Inet4Address, destination: Inet4Address, prefixLength: Int): Boolean {
    if (prefixLength !in 1..32) return false
    val a = source.address
    val b = destination.address
    var remaining = prefixLength
    for (index in a.indices) {
        if (remaining <= 0) break
        val bits = minOf(8, remaining)
        val mask = (255 shl (8 - bits)) and 255
        if (((a[index].toInt() and 255) and mask) != ((b[index].toInt() and 255) and mask)) return false
        remaining -= bits
    }
    return true
}
