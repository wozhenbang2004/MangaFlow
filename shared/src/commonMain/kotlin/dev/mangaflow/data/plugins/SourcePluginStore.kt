package dev.mangaflow.data.plugins

import dev.mangaflow.data.SourceException
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow

/** Shared persisted identity; execution belongs to the platform-specific source host. */
data class ScriptSourcePackage(val sourceId: String, val metadata: JSONObject, val code: String,
    val origin: String, val digest: String)

data class SourcePluginCandidate(val metadata: JSONObject, val code: String? = null,
    val origin: String, val codeUrl: String? = null)

/** Repository browsing never executes code; candidates still require explicit package validation. */
data class SourcePluginRepository(val url: String, val name: String)

data class SourceRepositoryEntry(
    val name: String,
    val language: String,
    val version: String,
    val siteUrl: String,
    val candidate: SourcePluginCandidate?,
    val unavailableReason: String? = null,
)

data class SourceRepositoryCatalog(
    val repository: SourcePluginRepository,
    val entries: List<SourceRepositoryEntry>,
)

expect class SourcePluginStore(context: PlatformContext, runtime: JavascriptSourceRuntime) {
    val runtime: JavascriptSourceRuntime
    val installed: StateFlow<List<ScriptSourcePackage>>
    val repositories: StateFlow<List<SourcePluginRepository>>
    val loadError: StateFlow<String?>
    val ready: Deferred<Unit>
    suspend fun preview(codeOrJson: String, origin: String): List<SourcePluginCandidate>
    suspend fun fetchPreview(url: String): List<SourcePluginCandidate>
    suspend fun fetchRepository(url: String): SourceRepositoryCatalog
    suspend fun removeRepository(url: String)
    suspend fun prepare(candidate: SourcePluginCandidate): ScriptSourcePackage
    suspend fun install(bundle: ScriptSourcePackage)
    suspend fun remove(sourceId: String)
    suspend fun exportBackup(): JSONArray
    suspend fun validateBackup(array: JSONArray)
    suspend fun restoreBackup(array: JSONArray)
    companion object {
        fun get(context: PlatformContext): SourcePluginStore
    }
}

/** Static URL boundary; the Android host additionally checks the addresses used for connections. */
internal fun publicPluginUrl(value: String): HttpUrl = value.toHttpUrlOrNull()?.takeIf { url ->
    val host = url.host.lowercase().trimEnd('.')
    url.isHttps && url.username.isEmpty() && url.password.isEmpty() && host.contains('.') &&
        ':' !in host && host != "localhost" && !host.endsWith(".localhost") &&
        !host.endsWith(".local") && !host.endsWith(".internal") &&
        (if (host.all { it.isDigit() || it == '.' }) publicIpv4(host) else
            !AMBIGUOUS_IP.matches(host))
} ?: throw SourceException("源文件和图片必须使用公开 HTTPS 地址，不支持私网地址或嵌入凭据。")

private val AMBIGUOUS_IP = Regex("(?i)(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")

private fun publicIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.isEmpty() || it.length > 1 && it.startsWith('0') }) return false
    val octets = parts.map { it.toIntOrNull()?.takeIf { part -> part in 0..255 } ?: return false }
    val a = octets[0]; val b = octets[1]; val c = octets[2]
    return a !in setOf(0, 10, 127) && a < 224 && !(a == 100 && b in 64..127) &&
        !(a == 169 && b == 254) && !(a == 172 && b in 16..31) &&
        !(a == 192 && b in setOf(0, 168)) && !(a == 198 && (b in 18..19 || b == 51 && c == 100)) &&
        !(a == 203 && b == 0 && c == 113)
}
