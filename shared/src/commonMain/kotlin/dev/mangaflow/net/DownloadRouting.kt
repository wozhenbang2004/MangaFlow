package dev.mangaflow.net

import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.sha256
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
    COMPONENTS, SOURCE_PACKAGES, UPDATES, WEBDAV, ANIME,
}

/** DIRECT uses an authorized Relay exit when bound; otherwise it only disables app proxies. */
enum class DownloadRouteMode { SYSTEM, DIRECT, PROXY }
enum class DownloadProxyProvider { RELAY, CUSTOM }
enum class DownloadProxyType { HTTP, SOCKS }

data class DownloadProxyEndpoint(val host: String, val port: Int, val type: DownloadProxyType)
/** A frozen authorization identity. Credentials are obtained over Binder and never persisted here. */
data class RelayRouteBinding(val providerPackage: String, val certificate: String, val grantId: String)

data class DownloadRouteSnapshot(
    val mode: DownloadRouteMode,
    val proxy: DownloadProxyEndpoint? = null,
    val relayBinding: RelayRouteBinding? = null,
)

/** A source owns its endpoint; edits to another source or the download proxy cannot change it. */
data class SourceNetworkSettings(val mode: DownloadRouteMode, val proxy: DownloadProxyEndpoint? = null)

data class DownloadRoutingSettings(
    val defaultMode: DownloadRouteMode = DownloadRouteMode.SYSTEM,
    val categoryOverrides: Map<DownloadCategory, DownloadRouteMode> = emptyMap(),
    val proxy: DownloadProxyEndpoint? = null,
    val sourceOverrides: Map<String, SourceNetworkSettings> = emptyMap(),
    val proxyProvider: DownloadProxyProvider = if (proxy != null || sourceOverrides.values.any { it.proxy != null }) DownloadProxyProvider.CUSTOM else DownloadProxyProvider.RELAY,
)

/** Invalid or unsupported routes must fail before a request, without falling back to direct. */
class DownloadRouteException(message: String) : IllegalStateException(message)

internal fun DownloadRouteSnapshot.checked(): DownloadRouteSnapshot {
    relayBinding?.let { binding ->
        if (mode == DownloadRouteMode.SYSTEM || proxy != null ||
            binding.providerPackage !in setOf("dev.relay.android.network", "dev.relay.android") ||
            !binding.certificate.matches(Regex("[a-fA-F0-9]{64}")) ||
            !binding.grantId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            throw DownloadRouteException("已保存的 Relay 联动身份无效，请重新授权后重试。")
        }
        return this
    }
    return when (mode) {
        DownloadRouteMode.SYSTEM, DownloadRouteMode.DIRECT -> copy(proxy = null)
        DownloadRouteMode.PROXY -> copy(proxy = checkedProxy(proxy
            ?: throw DownloadRouteException("尚未配置代理，请先授权 Relay 联动或填写代理地址。")))
    }
}

internal fun configuredRoute(mode: DownloadRouteMode, proxy: DownloadProxyEndpoint?,
    provider: DownloadProxyProvider): DownloadRouteSnapshot {
    val endpoint = proxy.takeIf { mode == DownloadRouteMode.PROXY && provider == DownloadProxyProvider.CUSTOM }
    val binding = if (provider == DownloadProxyProvider.RELAY && mode != DownloadRouteMode.SYSTEM) currentRelayBinding(mode) else null
    return DownloadRouteSnapshot(mode, endpoint, binding).checked()
}

/** Frozen work never silently changes provider after a user switches the application setting. */
internal fun DownloadRouteSnapshot.checkedActive(): DownloadRouteSnapshot {
    val provider = DownloadRoutingStore.activeProxyProvider()
    if (relayBinding != null && provider == DownloadProxyProvider.CUSTOM ||
        proxy != null && provider == DownloadProxyProvider.RELAY) {
        throw DownloadRouteException("代理提供方已切换，原任务线路已停用；请按当前设置重新开始。")
    }
    return checked()
}

/** Stable route identity includes the grant, never an ephemeral port or secret. */
internal fun DownloadRouteSnapshot.identity(): String = mode.name + "|" + proxy?.type?.name.orEmpty() + "|" +
    proxy?.host.orEmpty() + "|" + proxy?.port?.toString().orEmpty() + "|" +
    relayBinding?.let { it.providerPackage + "|" + it.certificate + "|" + it.grantId }.orEmpty()

internal fun DownloadRouteSnapshot.writeRelayBinding(json: JSONObject): JSONObject = json.apply {
    relayBinding?.let { put("relay", JSONObject().put("package", it.providerPackage)
        .put("certificate", it.certificate).put("grantId", it.grantId)) }
}

internal fun readRelayBinding(json: JSONObject): RelayRouteBinding? = if (json.has("relay")) {
    // An invalid binding must not turn a previously managed route into a normal system route.
    val value = json.getJSONObject("relay")
    RelayRouteBinding(value.getString("package"), value.getString("certificate"), value.getString("grantId"))
} else null

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
        if (mode == DownloadRouteMode.PROXY) configuredRoute(mode, mutableState.value.proxy, mutableState.value.proxyProvider)
        disk.edit().putString("default", mode.name).apply()
        mutableState.value = readSettings()
    }

    fun setCategory(category: DownloadCategory, mode: DownloadRouteMode?) = synchronized(lock) {
        if (mode == DownloadRouteMode.PROXY) configuredRoute(mode, mutableState.value.proxy, mutableState.value.proxyProvider)
        val editor = disk.edit()
        if (mode == null) editor.remove(categoryKey(category)) else editor.putString(categoryKey(category), mode.name)
        editor.apply()
        mutableState.value = readSettings()
    }

    fun setProxy(endpoint: DownloadProxyEndpoint?, useAsDefault: Boolean = false) = synchronized(lock) {
        val checked = endpoint?.let(::checkedProxy)
        if (useAsDefault && checked == null) throw DownloadRouteException("请先填写有效的代理地址。")
        val editor = disk.edit()
        if (checked == null) editor.remove("proxy_host").remove("proxy_port").remove("proxy_type")
        else editor.putString("proxy_host", checked.host).putInt("proxy_port", checked.port)
            .putString("proxy_type", checked.type.name)
        val provider = if (useAsDefault) DownloadProxyProvider.CUSTOM else mutableState.value.proxyProvider
        editor.putString("proxy_provider", provider.name)
        if (useAsDefault) editor.putString("default", DownloadRouteMode.PROXY.name)
        editor.apply()
        mutableState.value = readSettings()
    }

    fun setProxyProvider(provider: DownloadProxyProvider) = synchronized(lock) {
        val previous = disk.getString("proxy_provider")
        val saved = runCatching { disk.edit().putString("proxy_provider", provider.name).commit() }.getOrDefault(false)
        if (!saved) {
            // Preference backends may change their in-memory map before returning failure.
            val rollback = disk.edit()
            if (previous == null) rollback.remove("proxy_provider") else rollback.putString("proxy_provider", previous)
            rollback.apply()
            throw DownloadRouteException("代理提供方未能保存，请重试。")
        }
        mutableState.value = readSettings()
    }

    fun setSource(sourceId: String, settings: SourceNetworkSettings?) = synchronized(lock) {
        require(sourceId.matches(Regex("[A-Za-z0-9_.-]{1,96}"))) { "漫画源标识无效。" }
        val checked = settings?.let {
            configuredRoute(it.mode, it.proxy ?: mutableState.value.proxy, mutableState.value.proxyProvider)
            SourceNetworkSettings(it.mode, if (it.mode == DownloadRouteMode.PROXY &&
                mutableState.value.proxyProvider == DownloadProxyProvider.CUSTOM) it.proxy?.let(::checkedProxy) else it.proxy)
        }
        val next = mutableState.value.sourceOverrides.toMutableMap()
        if (checked == null) next.remove(sourceId) else next[sourceId] = checked
        val encoded = JSONObject()
        next.entries.sortedBy { it.key }.forEach { (id, value) ->
            encoded.put(id, JSONObject().put("mode", value.mode.name).apply {
                value.proxy?.let { put("host", it.host); put("port", it.port); put("type", it.type.name) }
            })
        }
        disk.edit().putString("sources", encoded.toString()).apply()
        mutableState.value = readSettings()
    }

    /** One reviewed result writes its selected rules together; callers perform disk I/O off Main. */
    fun applyBatch(
        expected: DownloadRoutingSettings,
        defaultMode: DownloadRouteMode? = null,
        categoryModes: Map<DownloadCategory, DownloadRouteMode> = emptyMap(),
        sourceSettings: Map<String, SourceNetworkSettings> = emptyMap(),
    ): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current !== expected) return@synchronized false

        // Validate the entire selected batch before creating an editor. The selected provider
        // owns transport; dormant per-source endpoints survive changes to route modes.
        defaultMode?.let { configuredRoute(it, current.proxy, current.proxyProvider) }
        val categories = categoryModes.toMap()
        categories.values.forEach { configuredRoute(it, current.proxy, current.proxyProvider) }
        val sources = sourceSettings.toMap().mapValues { (id, settings) ->
            if (!id.matches(Regex("[A-Za-z0-9_.-]{1,96}"))) throw DownloadRouteException("漫画源标识无效。")
            val route = configuredRoute(settings.mode, settings.proxy ?: current.proxy, current.proxyProvider)
            SourceNetworkSettings(route.mode, settings.proxy ?: current.sourceOverrides[id]?.proxy)
        }
        val changes = linkedMapOf<String, String>()
        defaultMode?.let { changes["default"] = it.name }
        categories.forEach { (category, mode) -> changes[categoryKey(category)] = mode.name }
        if (sources.isNotEmpty()) {
            val encoded = JSONObject()
            (current.sourceOverrides + sources).entries.sortedBy { it.key }.forEach { (id, value) ->
                encoded.put(id, JSONObject().put("mode", value.mode.name).apply {
                    value.proxy?.let { put("host", it.host); put("port", it.port); put("type", it.type.name) }
                })
            }
            changes["sources"] = encoded.toString()
        }
        if (changes.isEmpty()) return@synchronized true

        val previous = changes.keys.associateWith { disk.getString(it) }
        val editor = disk.edit()
        changes.forEach { (key, value) -> editor.putString(key, value) }
        val saved = try { editor.commit() } catch (_: Exception) { false }
        if (!saved) {
            // Both actual preference backends update memory before reporting a failed write.
            // Restore touched keys so a later unrelated edit cannot publish this failed batch.
            runCatching {
                val rollback = disk.edit()
                previous.forEach { (key, value) ->
                    if (value == null) rollback.remove(key) else rollback.putString(key, value)
                }
                rollback.apply()
            }
            throw DownloadRouteException("网络设置未能保存，请检查存储空间后重试。")
        }
        mutableState.value = readSettings()
        true
    }

    /** Explicit task choices are resolved once; source overrides never rewrite a frozen task. */
    fun sourceSnapshot(sourceId: String, category: DownloadCategory? = null,
        overrideMode: DownloadRouteMode? = null): DownloadRouteSnapshot {
        val current = mutableState.value
        if (overrideMode != null) return configuredRoute(overrideMode, current.proxy, current.proxyProvider)
        current.sourceOverrides[sourceId]?.let { return configuredRoute(it.mode, it.proxy ?: current.proxy, current.proxyProvider) }
        val mode = category?.let { current.categoryOverrides[it] } ?: current.defaultMode
        return configuredRoute(mode, current.proxy, current.proxyProvider)
    }

    fun snapshot(category: DownloadCategory, overrideMode: DownloadRouteMode? = null): DownloadRouteSnapshot {
        val current = mutableState.value
        val mode = overrideMode ?: current.categoryOverrides[category] ?: current.defaultMode
        return configuredRoute(mode, current.proxy, current.proxyProvider)
    }

    private fun readSettings(): DownloadRoutingSettings {
        val proxyType = disk.getString("proxy_type")?.let { name -> DownloadProxyType.entries.firstOrNull { it.name == name } }
        val host = disk.getString("proxy_host")
        val proxy = if (host != null && proxyType != null)
            DownloadProxyEndpoint(host, disk.getInt("proxy_port"), proxyType) else null
        val sources = readSources()
        return DownloadRoutingSettings(
            defaultMode = readMode("default") ?: DownloadRouteMode.SYSTEM,
            categoryOverrides = DownloadCategory.entries.mapNotNull { category ->
                readMode(categoryKey(category))?.let { category to it }
            }.toMap(),
            proxy = proxy,
            sourceOverrides = sources,
            proxyProvider = disk.getString("proxy_provider")?.let { saved -> DownloadProxyProvider.entries.firstOrNull { it.name == saved } }
                ?: if (proxy != null || sources.values.any { it.proxy != null }) DownloadProxyProvider.CUSTOM else DownloadProxyProvider.RELAY,
        )
    }

    private fun readSources(): Map<String, SourceNetworkSettings> {
        val encoded = runCatching { JSONObject(disk.getString("sources") ?: "{}") }.getOrNull() ?: return emptyMap()
        return buildMap {
            encoded.keys().forEach { id ->
                if (id.matches(Regex("[A-Za-z0-9_.-]{1,96}"))) {
                    val value = encoded.optJSONObject(id) ?: return@forEach
                    val mode = DownloadRouteMode.entries.firstOrNull { it.name == value.optString("mode") } ?: return@forEach
                    val type = DownloadProxyType.entries.firstOrNull { it.name == value.optString("type") }
                    // Retain invalid saved proxy settings so requests fail explicitly; never change them to direct.
                    val endpoint = if (type != null) DownloadProxyEndpoint(value.optString("host"), value.optInt("port"), type) else null
                    put(id, SourceNetworkSettings(mode, endpoint))
                }
            }
        }
    }

    private fun readMode(key: String): DownloadRouteMode? =
        disk.getString(key)?.let { name -> DownloadRouteMode.entries.firstOrNull { it.name == name } }
    private fun categoryKey(category: DownloadCategory) = "category." + category.name

    companion object {
        private val lock = StorageLock()
        private var instance: DownloadRoutingStore? = null
        internal fun activeSourceSnapshot(sourceId: String, category: DownloadCategory? = null): DownloadRouteSnapshot =
            synchronized(lock) { instance }?.sourceSnapshot(sourceId, category)
                ?: DownloadRouteSnapshot(DownloadRouteMode.SYSTEM)

        /** Unowned images and catalogue APIs still obey the app-wide route at request start. */
        internal fun activeDefaultSnapshot(): DownloadRouteSnapshot {
            val current = synchronized(lock) { instance }?.state?.value
                ?: return DownloadRouteSnapshot(DownloadRouteMode.SYSTEM)
            return configuredRoute(current.defaultMode, current.proxy, current.proxyProvider)
        }

        internal fun sourceRevision(sourceId: String): String {
            val current = synchronized(lock) { instance }?.state?.value ?: DownloadRoutingSettings()
            val source = current.sourceOverrides[sourceId]
            val mode = source?.mode ?: current.defaultMode
            val endpoint = source?.proxy ?: current.proxy
            val text = current.proxyProvider.name + "|" + runCatching {
                configuredRoute(mode, endpoint, current.proxyProvider).identity()
            }.getOrElse { mode.name + "|unavailable" }
            return sha256(text.encodeToByteArray()).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        }
        internal fun activeProxyProvider(): DownloadProxyProvider? = synchronized(lock) { instance }?.state?.value?.proxyProvider
        fun get(context: PlatformContext): DownloadRoutingStore = synchronized(lock) {
            instance ?: DownloadRoutingStore(context).also { instance = it }
        }
    }
}
