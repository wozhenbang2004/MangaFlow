package dev.mangaflow.sync

import dev.mangaflow.platform.PlatformContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.CoroutineScope

internal actual fun createLanReadingTransport(
    context: PlatformContext, scope: CoroutineScope, listener: LanReadingTransportListener,
): LanReadingTransport = JvmLanReadingTransport(context, scope, listener,
    System.getenv("COMPUTERNAME")?.takeIf(String::isNotBlank) ?: "Windows 漫流",
    localAddresses = ::desktopLanAddresses, newDiscovery = { DesktopLanServiceDiscovery() })

/** Bind to physical, directly connected adapters; VPN/default proxy settings are not inherited. */
private fun desktopLanAddresses(): List<LanLocalAddress> =
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { network ->
        val description = (network.name + " " + network.displayName).lowercase()
        val excluded = listOf("vpn", "tun", "tap", "wireguard", "tailscale", "zerotier", "teredo", "hamachi",
            "virtual", "vmware", "hyper-v", "docker", "wsl", "loopback")
        val usable = runCatching { network.isUp && !network.isLoopback && !network.isVirtual &&
            !network.isPointToPoint && network.supportsMulticast() && excluded.none { it in description } }.getOrDefault(false)
        if (!usable) emptyList() else network.interfaceAddresses.mapNotNull { link ->
            val address = link.address as? Inet4Address ?: return@mapNotNull null
            val prefix = link.networkPrefixLength.toInt()
            if (prefix !in 1..32 || address.isLoopbackAddress || address.isAnyLocalAddress) null else
                LanLocalAddress(address, prefix, network.name) { socket -> socket.bind(InetSocketAddress(address, 0)) }
        }
    }

/** JmDNS 3.6.5 owns mDNS parsing, probing, registration and service-cache expiry on Windows. */
private class DesktopLanServiceDiscovery : LanServiceDiscovery {
    private val services = CopyOnWriteArrayList<JmDNS>()
    @Volatile private var closed = false

    override fun start(port: Int, id: String, name: String, found: (LanReadingEndpoint) -> Unit,
        lost: (String) -> Unit, failed: (String) -> Unit) {
        desktopLanAddresses().forEach { local ->
            var service: JmDNS? = null
            try {
                val host = "mangaflow-${id.take(12)}-${local.interfaceName.filter(Char::isLetterOrDigit).take(16)}"
                val dns = JmDNS.create(local.address, host)
                service = dns
                services += dns
                if (closed) { services.remove(dns); dns.close(); return@forEach }
                val resolved = mutableMapOf<String, List<String>>()
                dns.addServiceListener(TYPE, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        if (!closed) dns.requestServiceInfo(event.type, event.name, true)
                    }
                    override fun serviceRemoved(event: ServiceEvent) {
                        val keys = synchronized(resolved) { resolved.remove(event.name).orEmpty() }
                        if (!closed) keys.forEach(lost)
                    }
                    override fun serviceResolved(event: ServiceEvent) {
                        if (closed) return
                        val info = event.info
                        if (info.getPropertyString("v") != "1") return
                        val otherId = info.getPropertyString("id").orEmpty()
                        val otherName = info.getPropertyString("name").orEmpty()
                        val endpoints = info.inet4Addresses.map { LanReadingEndpoint(otherId, otherName, it, info.port) }
                        val before = synchronized(resolved) { resolved.put(event.name, endpoints.map { it.key }).orEmpty() }
                        before.filter { key -> endpoints.none { it.key == key } }.forEach(lost)
                        endpoints.forEach(found)
                    }
                })
                dns.registerService(ServiceInfo.create(TYPE, "MangaFlow-${id.take(12)}", port, 0, 0,
                    mapOf("id" to id, "name" to name, "v" to "1")))
                if (closed) { services.remove(dns); dns.close() }
            } catch (_: Exception) {
                service?.let { services.remove(it); runCatching { it.close() } }
            }
        }
        if (services.isEmpty() && !closed) failed("等待可用的局域网；请连接同一 Wi-Fi 或有线网络，并允许漫流通过专用网络防火墙。")
    }

    override fun close() {
        closed = true
        services.forEach { service -> runCatching { service.unregisterAllServices(); service.close() } }
        services.clear()
    }

    private companion object { const val TYPE = "_mangaflow._tcp.local." }
}
