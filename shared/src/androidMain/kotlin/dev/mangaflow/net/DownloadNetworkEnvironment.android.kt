package dev.mangaflow.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Proxy
import android.net.ProxyInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.mangaflow.platform.PlatformContext
import java.util.concurrent.atomic.AtomicBoolean

actual fun readDownloadNetworkEnvironment(context: PlatformContext): DownloadNetworkEnvironment {
    val manager = connectivityManager(context)
        ?: return DownloadNetworkEnvironment(null, null)
    val captured = runCatching { manager.activeNetwork }
    if (captured.isFailure) return DownloadNetworkEnvironment(null, null)
    val network = captured.getOrNull()
    val capabilities = network?.let { runCatching { manager.getNetworkCapabilities(it) }.getOrNull() }
    // getDefaultProxy also includes a global proxy or this process's bound network.
    // Reading only LinkProperties would miss those publicly exposed configurations.
    val proxyRead = runCatching { manager.defaultProxy }
    val proxy = proxyRead.getOrNull()
    val automatic = when {
        proxyRead.isFailure -> null
        proxy == null -> false
        else -> usesAutomaticProxy(proxy)
    }
    val endpoint = if (automatic == false) proxy?.let {
        detectedProxyEndpoint(it.host, it.port, DownloadProxyType.HTTP)
    } else null
    // Do not publish one snapshot assembled across a network/proxy handoff. The native
    // event observer schedules another read after the changed capabilities are available.
    val sameNetwork = runCatching { manager.activeNetwork == network }.getOrDefault(false)
    val sameProxy = if (proxyRead.isSuccess) runCatching { manager.defaultProxy == proxy }.getOrDefault(false) else true
    if (!sameNetwork || !sameProxy) return DownloadNetworkEnvironment(null, null)
    return DownloadNetworkEnvironment(
        defaultNetworkUsesVpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        systemProxyConfigured = if (proxyRead.isSuccess) proxy != null else null,
        connected = network != null,
        transport = capabilities?.let {
            when {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> DownloadNetworkTransport.WIFI
                it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> DownloadNetworkTransport.CELLULAR
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> DownloadNetworkTransport.ETHERNET
                else -> DownloadNetworkTransport.OTHER
            }
        },
        proxies = endpoint?.let { listOf(DetectedDownloadProxy(it)) }.orEmpty(),
        automaticProxyConfigured = automatic,
        observation = if (network == null) DownloadNetworkObservation.SYSTEM_SETTINGS else DownloadNetworkObservation.DEFAULT_NETWORK,
    )
}

private fun connectivityManager(context: PlatformContext): ConnectivityManager? = runCatching {
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
}.getOrNull()

/** PAC can expose a local implementation port; it is not a portable manual HTTP endpoint. */
private fun usesAutomaticProxy(proxy: ProxyInfo): Boolean? = runCatching {
    proxy.pacFileUrl?.let { it != Uri.EMPTY && it.toString().isNotBlank() } ?: false
}.getOrNull()

/** Passive native changes only. No request, port scan, installed-app lookup or route mutation. */
actual fun observeDownloadNetworkEnvironment(context: PlatformContext, changed: () -> Unit): () -> Unit {
    val app = context.applicationContext
    val manager = connectivityManager(app) ?: return {}
    val released = AtomicBoolean(false)
    val handler = Handler(Looper.getMainLooper())
    val notification = Runnable { if (!released.get()) changed() }
    fun notifyChanged() {
        if (!released.get()) {
            handler.removeCallbacks(notification)
            handler.post(notification)
        }
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        // Read outside these callbacks: onAvailable can precede capabilities/link data.
        override fun onAvailable(network: Network) = notifyChanged()
        override fun onLost(network: Network) = notifyChanged()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = notifyChanged()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = notifyChanged()
    }
    val callbackRegistered = runCatching {
        when {
            Build.VERSION.SDK_INT >= 26 -> manager.registerDefaultNetworkCallback(callback, handler)
            Build.VERSION.SDK_INT >= 24 -> manager.registerDefaultNetworkCallback(callback)
            else -> return@runCatching false
        }
        true
    }.getOrDefault(false)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Proxy.PROXY_CHANGE_ACTION) notifyChanged()
        }
    }
    val receiverRegistered = runCatching {
        val filter = IntentFilter(Proxy.PROXY_CHANGE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        true
    }.getOrDefault(false)
    return {
        if (released.compareAndSet(false, true)) {
            handler.removeCallbacks(notification)
            if (callbackRegistered) runCatching { manager.unregisterNetworkCallback(callback) }
            if (receiverRegistered) runCatching { app.unregisterReceiver(receiver) }
        }
    }
}
