package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext
import java.util.concurrent.TimeUnit

/** Read configuration only. ProxySelector.select can evaluate PAC and is deliberately not used. */
actual fun readDownloadNetworkEnvironment(context: PlatformContext): DownloadNetworkEnvironment {
    val runtime = listOf(
        runtimeProxy("http.proxyHost", "http.proxyPort", DownloadProxyType.HTTP, DownloadProxyScope.HTTP),
        runtimeProxy("https.proxyHost", "https.proxyPort", DownloadProxyType.HTTP, DownloadProxyScope.HTTPS),
        runtimeProxy("socksProxyHost", "socksProxyPort", DownloadProxyType.SOCKS, DownloadProxyScope.SOCKS),
    )
    if (runtime.any { it.configured }) return DownloadNetworkEnvironment(
        defaultNetworkUsesVpn = null, systemProxyConfigured = true,
        proxies = runtime.mapNotNull(RuntimeProxy::proxy).distinct(),
        observation = DownloadNetworkObservation.APP_RUNTIME,
    )
    if (!runCatching { System.getProperty("os.name").orEmpty().startsWith("Windows", true) }.getOrDefault(false)) {
        return DownloadNetworkEnvironment(null, null, observation = DownloadNetworkObservation.APP_RUNTIME)
    }
    // These fixed current-user values describe Windows internet settings, not the
    // external application's private configuration, running status or selected node.
    val manual = registryValue("ProxyEnable")?.let(::registryBoolean)
    val server = if (manual == true) registryValue("ProxyServer") else null
    val automatic = registryValue("AutoConfigURL")?.let { it.isNotBlank() }
    val configured = when {
        manual == true || automatic == true -> true
        manual == false && automatic == false -> false
        else -> null // Missing/inaccessible PAC/WPAD state is not proof of no proxy.
    }
    return DownloadNetworkEnvironment(
        defaultNetworkUsesVpn = null, systemProxyConfigured = configured,
        proxies = if (manual == true) parseWindowsProxyServer(server.orEmpty()) else emptyList(),
        automaticProxyConfigured = automatic,
        observation = DownloadNetworkObservation.SYSTEM_SETTINGS,
    )
}

/** Foreground/explicit refresh owns snapshots; no polling process or PAC evaluation. */
actual fun observeDownloadNetworkEnvironment(context: PlatformContext, changed: () -> Unit): () -> Unit = {}

private data class RuntimeProxy(val configured: Boolean, val proxy: DetectedDownloadProxy?)

private fun runtimeProxy(hostKey: String, portKey: String, type: DownloadProxyType,
    scope: DownloadProxyScope): RuntimeProxy {
    val host = runCatching { System.getProperty(hostKey) }.getOrNull()?.takeIf { it.isNotBlank() }
    val port = runCatching { System.getProperty(portKey)?.toIntOrNull() }.getOrNull()
    return RuntimeProxy(host != null, detectedProxyEndpoint(host, port, type)?.let { DetectedDownloadProxy(it, scope) })
}

private fun registryBoolean(value: String): Boolean? = value.trim().let {
    val number = if (it.startsWith("0x", true)) it.substring(2).toLongOrNull(16) else it.toLongOrNull()
    when (number) { 0L -> false; 1L -> true; else -> null }
}

/** Bounded read of an allowlisted OS value. No shell, registry writes or network access. */
private fun registryValue(name: String): String? {
    if (name !in setOf("ProxyEnable", "ProxyServer", "AutoConfigURL")) return null
    val process = try {
        val systemRoot = System.getenv("SystemRoot")?.takeIf { it.isNotBlank() } ?: return null
        val executable = java.nio.file.Path.of(systemRoot, "System32", "reg.exe")
        if (!executable.isAbsolute || !java.nio.file.Files.isRegularFile(executable)) return null
        ProcessBuilder(executable.toString(), "query", INTERNET_SETTINGS, "/v", name)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    } catch (_: Exception) { return null }
    return try {
        process.outputStream.close()
        if (!process.waitFor(600, TimeUnit.MILLISECONDS) || process.exitValue() != 0) return null
        val bytes = process.inputStream.readNBytes(16 * 1024)
        if (process.inputStream.read() != -1) return null
        val text = bytes.toString(Charsets.UTF_8)
        val pattern = Regex("^\\s*" + Regex.escape(name) + "\\s+REG_(?:SZ|DWORD)\\s*(.*?)\\s*$")
        text.lineSequence().mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1) }.firstOrNull()
    } catch (_: Exception) { null }
    finally {
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }
}

private fun parseWindowsProxyServer(value: String): List<DetectedDownloadProxy> = value
    .split(';', ' ', '\t').mapNotNull { item ->
        val part = item.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val separator = part.indexOf('=')
        val protocol = if (separator >= 0) part.substring(0, separator).lowercase() else ""
        val address = if (separator >= 0) part.substring(separator + 1) else part
        val scope = when (protocol) {
            "" -> DownloadProxyScope.DEFAULT
            "http" -> DownloadProxyScope.HTTP
            "https" -> DownloadProxyScope.HTTPS
            "socks", "socks5" -> DownloadProxyScope.SOCKS
            else -> return@mapNotNull null
        }
        val type = if (scope == DownloadProxyScope.SOCKS) DownloadProxyType.SOCKS else DownloadProxyType.HTTP
        val nativeAddress = address.removePrefix(if (type == DownloadProxyType.SOCKS) "socks://" else "http://")
        val match = Regex("^(\\[[0-9A-Fa-f:.]+]|[^:]+):([0-9]{1,5})$").matchEntire(nativeAddress)
            ?: return@mapNotNull null
        detectedProxyEndpoint(match.groupValues[1], match.groupValues[2].toIntOrNull(), type)
            ?.let { DetectedDownloadProxy(it, scope) }
    }.distinct()

private const val INTERNET_SETTINGS = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
