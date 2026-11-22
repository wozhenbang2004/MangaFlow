package dev.mangaflow.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import dev.mangaflow.platform.PlatformContext
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope

internal actual fun createLanReadingTransport(
    context: PlatformContext, scope: CoroutineScope, listener: LanReadingTransportListener,
): LanReadingTransport {
    val application = context.applicationContext
    val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    return JvmLanReadingTransport(application, scope, listener, Build.MODEL,
        localAddresses = {
            connectivity?.allNetworks.orEmpty().flatMap { network ->
                val capabilities = connectivity?.getNetworkCapabilities(network)
                val links = connectivity?.getLinkProperties(network)
                if (capabilities == null || links == null ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
                    !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) emptyList()
                else links.linkAddresses.mapNotNull { link ->
                    val address = link.address as? Inet4Address ?: return@mapNotNull null
                    if (address.isLoopbackAddress || link.prefixLength !in 1..32) null else
                        LanLocalAddress(address, link.prefixLength, links.interfaceName.orEmpty()) { socket ->
                            // Preserve the physical network, including local-only Wi-Fi; never alter process VPN routing.
                            network.bindSocket(socket)
                        }
                }
            }
        }, newDiscovery = { AndroidLanServiceDiscovery(application) })
}

/** DNS-SD registration/resolution belongs to Android, including its multicast and interface handling. */
private class AndroidLanServiceDiscovery(private val application: Context) : LanServiceDiscovery {
    private val manager = application.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    @Volatile private var closed = false
    private var discovery: NsdManager.DiscoveryListener? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val waiting = ArrayDeque<NsdServiceInfo>()
    private val present = mutableSetOf<String>()
    private val resolved = mutableMapOf<String, List<String>>()
    private var resolving = false
    private var foundCallback: ((LanReadingEndpoint) -> Unit)? = null

    override fun start(port: Int, id: String, name: String, found: (LanReadingEndpoint) -> Unit,
        lost: (String) -> Unit, failed: (String) -> Unit) {
        foundCallback = found
        if (closed) return
        // T extensions 7+ manages reception while foreground; earlier Android needs an explicit lock.
        if (Build.VERSION.SDK_INT < 33 || SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7) {
            multicast = (application.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("mangaflow-reading-sync")?.apply { setReferenceCounted(false); acquire() }
        }
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (!closed) failed("局域网设备广播暂不可用，将自动重试。")
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = registrationListener
        val service = NsdServiceInfo().apply {
            serviceType = TYPE
            serviceName = "MangaFlow-${id.take(12)}"
            setPort(port)
            setAttribute("id", id)
            setAttribute("name", name)
            setAttribute("v", "1")
        }
        manager.registerService(service, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (!closed) failed("局域网设备发现暂不可用，将自动重试。")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (closed || serviceInfo.serviceType.trimEnd('.') != TYPE.trimEnd('.')) return
                synchronized(lock) {
                    if (present.size < 128 && present.add(serviceInfo.serviceName)) waiting.addLast(serviceInfo)
                }
                resolveNext()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val keys = synchronized(lock) {
                    present.remove(serviceInfo.serviceName)
                    waiting.removeAll { it.serviceName == serviceInfo.serviceName }
                    resolved.remove(serviceInfo.serviceName).orEmpty()
                }
                if (!closed) keys.forEach(lost)
            }
        }
        discovery = discoveryListener
        manager.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val next = synchronized(lock) {
            if (closed || resolving || waiting.isEmpty()) null
            else waiting.removeFirst().also { resolving = true }
        } ?: return
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) { resolving = false; present.remove(serviceInfo.serviceName) }
                resolveNext()
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val stillPresent = synchronized(lock) { serviceInfo.serviceName in present && !closed }
                if (stillPresent && serviceInfo.attributes["v"]?.decodeToString() == "1") {
                    val id = serviceInfo.attributes["id"]?.decodeToString().orEmpty()
                    val name = serviceInfo.attributes["name"]?.decodeToString().orEmpty()
                    val hosts = if (Build.VERSION.SDK_INT >= 34) serviceInfo.hostAddresses else listOfNotNull(serviceInfo.host)
                    val endpoints = hosts.filterIsInstance<Inet4Address>().map { LanReadingEndpoint(id, name, it, serviceInfo.port) }
                    synchronized(lock) { resolved[serviceInfo.serviceName] = endpoints.map { it.key } }
                    if (!closed) endpoints.forEach { foundCallback?.invoke(it) }
                }
                synchronized(lock) { resolving = false }
                resolveNext()
            }
        }
        try { manager.resolveService(next, callback) }
        catch (_: Exception) {
            synchronized(lock) { resolving = false; present.remove(next.serviceName) }
            resolveNext()
        }
    }

    override fun close() {
        closed = true
        discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registration?.let { runCatching { manager.unregisterService(it) } }
        multicast?.let { if (it.isHeld) runCatching { it.release() } }
        multicast = null
        synchronized(lock) { waiting.clear(); present.clear(); resolved.clear() }
        foundCallback = null
    }

    private companion object { const val TYPE = "_mangaflow._tcp." }
}
