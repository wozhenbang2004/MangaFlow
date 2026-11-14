package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.preferences
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DownloadCategory {
    CHAPTERS, STEAM_WALLPAPERS, STEAM_COMPONENT, FONTS, AI_MODELS,
    COMPONENTS, SOURCE_PACKAGES, UPDATES, WEBDAV,
}

/** DIRECT disables HTTP/SOCKS proxies; it does not override an external VPN's routing. */
enum class DownloadRouteMode { SYSTEM, DIRECT, PROXY }
enum class DownloadProxyType { HTTP, SOCKS }

data class DownloadProxyEndpoint(val host: String, val port: Int, val type: DownloadProxyType)
data class DownloadRouteSnapshot(
    val mode: DownloadRouteMode,
    val proxy: DownloadProxyEndpoint? = null,
)

data class DownloadRoutingSettings(
    val defaultMode: DownloadRouteMode = DownloadRouteMode.SYSTEM,
    val categoryOverrides: Map<DownloadCategory, DownloadRouteMode> = emptyMap(),
    val proxy: DownloadProxyEndpoint? = null,
)

/** Invalid or unsupported routes must fail before a request, without falling back to direct. */
class DownloadRouteException(message: String) : IllegalStateException(message)

internal fun DownloadRouteSnapshot.checked(): DownloadRouteSnapshot = when (mode) {
    DownloadRouteMode.SYSTEM, DownloadRouteMode.DIRECT -> copy(proxy = null)
    DownloadRouteMode.PROXY -> copy(proxy = checkedProxy(proxy
        ?: throw DownloadRouteException("尚未配置代理，请先在网络与下载中填写代理地址。")))
}

private fun checkedProxy(endpoint: DownloadProxyEndpoint): DownloadProxyEndpoint {
    val input = endpoint.host.trim()
    val bracketed = input.startsWith('[') || input.endsWith(']')
    if (bracketed && !(input.startsWith('[') && input.endsWith(']'))) {
        throw DownloadRouteException("代理 IP 地址的方括号不完整。")
    }
    val host = if (bracketed) input.substring(1, input.length - 1) else input
    val valid = host.isNotEmpty() && host.length <= 253 && if (':' in host) {
        validIpv6(host)
    } else {
        !bracketed && host.removeSuffix(".").split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }
    if (!valid) throw DownloadRouteException("代理地址只填写主机名或 IP，不要包含协议、路径或账号密码。")
    if (endpoint.port !in 1..65535) throw DownloadRouteException("代理端口应在 1–65535 之间。")
    return endpoint.copy(host = host.lowercase())
}

private fun validIpv6(host: String): Boolean {
    if (host.any { it !in "0123456789abcdefABCDEF:." } || ":::" in host ||
        (host.startsWith(':') && !host.startsWith("::")) ||
        (host.endsWith(':') && !host.endsWith("::"))) return false
    val compressed = host.indexOf("::")
    if (compressed >= 0 && host.indexOf("::", compressed + 2) >= 0) return false
    val parts = host.split(':')
    var groups = 0
    for ((index, part) in parts.withIndex()) {
        if (part.isEmpty()) continue
        if ('.' in part) {
            if (index != parts.lastIndex) return false
            val octets = part.split('.')
            if (octets.size != 4 || octets.any {
                it.isEmpty() || it.length > 3 || it.any { c -> c !in '0'..'9' } ||
                    (it.length > 1 && it.first() == '0') || (it.toIntOrNull() ?: 256) !in 0..255
            }) return false
            groups += 2
        } else {
            if (part.length !in 1..4) return false
            groups++
        }
    }
    return if (compressed >= 0) groups < 8
    else !host.startsWith(':') && !host.endsWith(':') && groups == 8
}

/** Category preferences are read once per explicit transfer and passed as an immutable snapshot. */
class DownloadRoutingStore private constructor(context: PlatformContext) {
    private val disk = preferences(applicationContext(context), "mangaflow_download_routing")
    private val lock = StorageLock()
    private val mutableState = MutableStateFlow(readSettings())
    val state: StateFlow<DownloadRoutingSettings> = mutableState.asStateFlow()

    fun setDefault(mode: DownloadRouteMode) = synchronized(lock) {
        if (mode == DownloadRouteMode.PROXY) DownloadRouteSnapshot(mode, mutableState.value.proxy).checked()
        disk.edit().putString("default", mode.name).apply()
        mutableState.value = readSettings()
    }

    fun setCategory(category: DownloadCategory, mode: DownloadRouteMode?) = synchronized(lock) {
        if (mode == DownloadRouteMode.PROXY) DownloadRouteSnapshot(mode, mutableState.value.proxy).checked()
        val editor = disk.edit()
        if (mode == null) editor.remove(categoryKey(category)) else editor.putString(categoryKey(category), mode.name)
        editor.apply()
        mutableState.value = readSettings()
    }

    fun setProxy(endpoint: DownloadProxyEndpoint?) = synchronized(lock) {
        val checked = endpoint?.let(::checkedProxy)
        val editor = disk.edit()
        if (checked == null) editor.remove("proxy_host").remove("proxy_port").remove("proxy_type")
        else editor.putString("proxy_host", checked.host).putInt("proxy_port", checked.port)
            .putString("proxy_type", checked.type.name)
        editor.apply()
        mutableState.value = readSettings()
    }

    fun snapshot(category: DownloadCategory, overrideMode: DownloadRouteMode? = null): DownloadRouteSnapshot {
        val current = mutableState.value
        val mode = overrideMode ?: current.categoryOverrides[category] ?: current.defaultMode
        return DownloadRouteSnapshot(mode, current.proxy).checked()
    }

    private fun readSettings(): DownloadRoutingSettings {
        val proxyType = disk.getString("proxy_type")?.let { name -> DownloadProxyType.entries.firstOrNull { it.name == name } }
        val host = disk.getString("proxy_host")
        val proxy = if (host != null && proxyType != null)
            DownloadProxyEndpoint(host, disk.getInt("proxy_port"), proxyType) else null
        return DownloadRoutingSettings(
            defaultMode = readMode("default") ?: DownloadRouteMode.SYSTEM,
            categoryOverrides = DownloadCategory.entries.mapNotNull { category ->
                readMode(categoryKey(category))?.let { category to it }
            }.toMap(),
            proxy = proxy,
        )
    }

    private fun readMode(key: String): DownloadRouteMode? =
        disk.getString(key)?.let { name -> DownloadRouteMode.entries.firstOrNull { it.name == name } }
    private fun categoryKey(category: DownloadCategory) = "category." + category.name

    companion object {
        private val lock = StorageLock()
        private var instance: DownloadRoutingStore? = null
        fun get(context: PlatformContext): DownloadRoutingStore = synchronized(lock) {
            instance ?: DownloadRoutingStore(context).also { instance = it }
        }
    }
}
