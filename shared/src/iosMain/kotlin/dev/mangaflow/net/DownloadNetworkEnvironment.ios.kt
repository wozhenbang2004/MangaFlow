@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext
import kotlinx.cinterop.*
import platform.CFNetwork.CFNetworkCopySystemProxySettings
import platform.CoreFoundation.*

/** Current public system proxy settings. VPN ownership and actual traffic remain unknown. */
actual fun readDownloadNetworkEnvironment(context: PlatformContext): DownloadNetworkEnvironment {
    val settings = CFNetworkCopySystemProxySettings()
        ?: return DownloadNetworkEnvironment(null, null, observation = DownloadNetworkObservation.SYSTEM_SETTINGS)
    return try {
        val http = settings.proxyFlag("HTTPEnable")
        val https = settings.proxyFlag("HTTPSEnable")
        val socks = settings.proxyFlag("SOCKSEnable")
        val pac = settings.proxyFlag("ProxyAutoConfigEnable")
        val discovery = settings.proxyFlag("ProxyAutoDiscoveryEnable")
        val automatic = enabledState(listOf(pac, discovery))
        val proxies = buildList {
            if (http == true) settings.endpoint("HTTP", DownloadProxyType.HTTP)?.let {
                add(DetectedDownloadProxy(it, DownloadProxyScope.HTTP))
            }
            if (https == true) settings.endpoint("HTTPS", DownloadProxyType.HTTP)?.let {
                add(DetectedDownloadProxy(it, DownloadProxyScope.HTTPS))
            }
            if (socks == true) settings.endpoint("SOCKS", DownloadProxyType.SOCKS)?.let {
                add(DetectedDownloadProxy(it, DownloadProxyScope.SOCKS))
            }
        }.distinct()
        DownloadNetworkEnvironment(
            defaultNetworkUsesVpn = null,
            systemProxyConfigured = enabledState(listOf(http, https, socks, pac, discovery)),
            proxies = proxies, automaticProxyConfigured = automatic,
            observation = DownloadNetworkObservation.SYSTEM_SETTINGS,
        )
    } finally { CFRelease(settings) }
}

/** Systemwide proxy changes have no reliable public notification used here; refresh on foreground. */
actual fun observeDownloadNetworkEnvironment(context: PlatformContext, changed: () -> Unit): () -> Unit = {}

private fun enabledState(flags: List<Boolean?>): Boolean? = when {
    flags.any { it == true } -> true
    flags.all { it == false } -> false
    else -> null
}

private fun CFDictionaryRef.endpoint(prefix: String, type: DownloadProxyType): DownloadProxyEndpoint? =
    detectedProxyEndpoint(proxyString(prefix + "Proxy"), proxyNumber(prefix + "Port")
        ?.takeIf { it in 1L..65535L }?.toInt(), type)

/** Borrowed values live only until the owning settings dictionary is released. */
private fun CFDictionaryRef.proxyValue(name: String): COpaquePointer? {
    val key = CFStringCreateWithCString(null, name, kCFStringEncodingUTF8) ?: return null
    return try { CFDictionaryGetValue(this, key) } finally { CFRelease(key) }
}

private fun CFDictionaryRef.proxyFlag(name: String): Boolean? = proxyNumber(name)?.let {
    when (it) { 0L -> false; 1L -> true; else -> null }
}

private fun CFDictionaryRef.proxyNumber(name: String): Long? = memScoped {
    val value = proxyValue(name) ?: return@memScoped null
    if (CFGetTypeID(value) == CFBooleanGetTypeID()) return@memScoped if (CFBooleanGetValue(value.reinterpret())) 1L else 0L
    if (CFGetTypeID(value) != CFNumberGetTypeID()) return@memScoped null
    val number = alloc<LongVar>()
    if (CFNumberGetValue(value.reinterpret(), kCFNumberSInt64Type, number.ptr)) number.value else null
}

private fun CFDictionaryRef.proxyString(name: String): String? = memScoped {
    val value = proxyValue(name) ?: return@memScoped null
    if (CFGetTypeID(value) != CFStringGetTypeID()) return@memScoped null
    val string: CFStringRef = value.reinterpret()
    val length = CFStringGetLength(string)
    if (length !in 1L..1024L) return@memScoped null
    val capacity = CFStringGetMaximumSizeForEncoding(length, kCFStringEncodingUTF8) + 1L
    val buffer = allocArray<ByteVar>(capacity.toInt())
    if (CFStringGetCString(string, buffer, capacity, kCFStringEncodingUTF8)) buffer.toKString() else null
}
