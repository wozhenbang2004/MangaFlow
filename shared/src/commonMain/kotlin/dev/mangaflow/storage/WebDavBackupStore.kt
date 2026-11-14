@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package dev.mangaflow.storage

import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.NetworkResponse
import dev.mangaflow.net.Request
import dev.mangaflow.net.RequestBody
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64

data class WebDavBackupConfig(
    val endpoint: String = "", val username: String = "", val remotePath: String = DEFAULT_REMOTE_PATH,
    val hasPassword: Boolean = false, val lastUploadAt: Long = 0L, val lastDownloadAt: Long = 0L,
) {
    val configured: Boolean get() = endpoint.isNotBlank() && remotePath.isNotBlank()
    companion object { const val DEFAULT_REMOTE_PATH = "MangaFlow/reading-backup.json" }
}

/** The same bounded HTTPS WebDAV protocol on every platform, with an OS-protected secret. */
class WebDavBackupStore(private val context: PlatformContext) {
    private val disk = preferences(context, "mangaflow_webdav_backup")
    private val lock = StorageLock()
    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<WebDavBackupConfig> = _config.asStateFlow()

    suspend fun saveConfiguration(endpoint: String, username: String, remotePath: String, password: String?): WebDavBackupConfig = withContext(Dispatchers.IO) {
        require(username.length <= 512) { "WebDAV 用户名过长。" }
        require(password == null || password.length <= 4096) { "WebDAV 密码过长。" }
        val nextUrl = normalizeEndpoint(endpoint)
        val nextUsername = username.trim()
        val nextPath = normalizeRemotePath(remotePath).joinToString("/")
        synchronized(lock) {
            val previous = readConfig()
            val oldUrl = previous.endpoint.toHttpUrlOrNull()
            check(!(previous.configured && previous.hasPassword && password == null &&
                (oldUrl?.host != nextUrl.host || oldUrl?.port != nextUrl.port || previous.username != nextUsername))) {
                "更换服务器或用户名后，请重新输入 WebDAV 密码。"
            }
            val oldSecret = if (password != null) runCatching { readSecret(context, SECRET) }.getOrNull() else null
            if (password != null) {
                if (password.isEmpty()) deleteSecret(context, SECRET) else writeSecret(context, SECRET, password)
            }
            val editor = disk.edit().putString("endpoint", nextUrl.toString()).putString("username", nextUsername).putString("remote_path", nextPath)
            if (password != null) editor.putBoolean("secret_ref", password.isNotEmpty()).remove("secret_iv").remove("secret_value")
            if (!editor.commit()) {
                if (password != null) runCatching {
                    if (oldSecret == null) deleteSecret(context, SECRET) else writeSecret(context, SECRET, oldSecret)
                }
                error("无法保存 WebDAV 配置，请检查设备存储空间。")
            }
            readConfig().also { _config.value = it }
        }
    }

    suspend fun clearConfiguration() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            deleteSecret(context, SECRET)
            check(disk.edit().clear().commit()) { "无法清除 WebDAV 配置，请检查设备存储空间。" }
            _config.value = readConfig()
        }
    }

    suspend fun upload(payload: String) {
        val bytes = payload.encodeToByteArray()
        require(bytes.isNotEmpty() && bytes.size <= MAX_BACKUP_BYTES) { "阅读备份为空或超过 8 MiB，已停止上传。" }
        val credentials = withContext(Dispatchers.IO) { readCredentials() }
        var transfer: NetworkClient? = null
        try {
            val client = transferClient()
            transfer = client
            val base = normalizeEndpoint(credentials.first.endpoint)
            val segments = normalizeRemotePath(credentials.first.remotePath)
            segments.dropLast(1).indices.forEach { index ->
                val url = buildUrl(base, segments.take(index + 1), collection = true)
                val response = execute(client, requestBuilder(url, credentials).method("MKCOL", RequestBody(ByteArray(0), null)).build(), "准备 WebDAV 目录")
                if (response.code != 405) ensureSuccessful(response, "准备 WebDAV 目录")
            }
            val response = execute(client, requestBuilder(buildUrl(base, segments), credentials)
                .method("PUT", RequestBody(bytes, "application/json; charset=utf-8")).build(), "上传阅读备份")
            ensureSuccessful(response, "上传阅读备份")
            rememberTransfer(upload = true)
        } finally { transfer?.close() }
    }

    suspend fun download(): String {
        val credentials = withContext(Dispatchers.IO) { readCredentials() }
        var transfer: NetworkClient? = null
        try {
            val client = transferClient()
            transfer = client
            val base = normalizeEndpoint(credentials.first.endpoint)
            val response = execute(client, requestBuilder(buildUrl(base, normalizeRemotePath(credentials.first.remotePath)), credentials)
                .get().header("Accept", "application/json, text/plain;q=0.8").build(), "下载阅读备份")
            ensureSuccessful(response, "下载阅读备份")
            check(response.bytes.isNotEmpty()) { "远端备份为空。" }
            val payload = response.text()
            rememberTransfer(upload = false)
            return payload
        } finally { transfer?.close() }
    }

    private fun transferClient() = NetworkClient(
        connectTimeoutMillis = 20_000, readTimeoutMillis = 40_000, callTimeoutMillis = 60_000,
        route = DownloadRoutingStore.get(context).snapshot(DownloadCategory.WEBDAV),
    )

    private suspend fun execute(client: NetworkClient, request: Request, operation: String): NetworkResponse = try {
        client.execute(request, MAX_BACKUP_BYTES.toLong())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: DownloadRouteException) { throw failure }
    catch (failure: Exception) { throw IllegalStateException("$operation 连接或读取失败，请检查网络、HTTPS 证书和地址。", failure) }

    private fun requestBuilder(url: HttpUrl, credentials: Pair<WebDavBackupConfig, String>): Request.Builder {
        val builder = Request.Builder().url(url).header("User-Agent", "MangaFlow-WebDAV").header("Cache-Control", "no-store")
        if (credentials.first.username.isNotEmpty() || credentials.second.isNotEmpty()) {
            builder.header("Authorization", "Basic " + Base64.Default.encode("${credentials.first.username}:${credentials.second}".encodeToByteArray()))
        }
        return builder
    }
    private fun readCredentials(): Pair<WebDavBackupConfig, String> = synchronized(lock) {
        val current = readConfig()
        check(current.configured) { "请先填写 WebDAV HTTPS 地址和远端文件。" }
        val password = if (current.hasPassword) readSecret(context, SECRET) ?: error("已保存的 WebDAV 密码无法读取，请重新输入。") else ""
        current to password
    }
    private fun rememberTransfer(upload: Boolean) = synchronized(lock) {
        disk.edit().putLong(if (upload) "last_upload" else "last_download", wallTimeMillis()).apply()
        _config.value = readConfig()
    }
    private fun readConfig() = WebDavBackupConfig(
        endpoint = disk.getString("endpoint", "").orEmpty().trim(), username = disk.getString("username", "").orEmpty().trim(),
        remotePath = disk.getString("remote_path", WebDavBackupConfig.DEFAULT_REMOTE_PATH).orEmpty().trim().ifBlank { WebDavBackupConfig.DEFAULT_REMOTE_PATH },
        hasPassword = disk.getBoolean("secret_ref", false) || (!disk.getString("secret_iv", null).isNullOrBlank() && !disk.getString("secret_value", null).isNullOrBlank()),
        lastUploadAt = disk.getLong("last_upload", 0), lastDownloadAt = disk.getLong("last_download", 0),
    )
    private fun normalizeEndpoint(raw: String): HttpUrl {
        require(raw.length <= 4096) { "WebDAV 地址过长。" }
        val url = raw.trim().toHttpUrlOrNull() ?: error("请输入完整的 WebDAV HTTPS 地址。")
        require(url.scheme == "https") { "WebDAV 只允许使用 HTTPS 地址。" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "请不要把账号密码写进 URL。" }
        require(url.query == null && url.fragment == null) { "WebDAV 地址不能包含查询参数或片段。" }
        return url.newBuilder().apply { if (!url.encodedPath.endsWith('/')) addPathSegment("") }.build()
    }
    private fun normalizeRemotePath(raw: String): List<String> {
        val value = raw.trim().replace('\\', '/')
        require(value.length <= 1024) { "远端文件路径过长。" }
        val segments = value.split('/').filter { it.isNotBlank() }
        require(segments.isNotEmpty() && segments.size <= 32) { "请填写层级不超过 32 的远端备份文件路径。" }
        require(segments.none { it in setOf(".", "..") || it.any { character -> character.code < 32 || character == '\u007f' } }) { "远端文件路径包含无效字符。" }
        return segments
    }
    private fun buildUrl(base: HttpUrl, segments: List<String>, collection: Boolean = false): HttpUrl = base.newBuilder().apply {
        segments.forEach { addPathSegment(it) }
        if (collection) addPathSegment("")
    }.build()
    private fun ensureSuccessful(response: NetworkResponse, operation: String) {
        if (response.isSuccessful) return
        error(when (response.code) {
            301, 302, 303, 307, 308 -> "WebDAV 地址发生重定向，请填写跳转后的最终 HTTPS 地址。"
            401, 403 -> "WebDAV 账号、密码或目录权限不正确。"
            404 -> "远端还没有阅读备份，或 WebDAV 路径不存在。"
            405 -> "服务器不允许执行 $operation，请检查 WebDAV 目录权限。"
            409 -> "远端目录不存在，且服务器拒绝自动创建。"
            413 -> "服务器拒绝了过大的阅读备份。"
            423 -> "远端备份正被锁定，请稍后重试。"
            429 -> "WebDAV 请求过于频繁，请稍后重试。"
            507 -> "WebDAV 存储空间不足。"
            else -> "$operation 暂时不可用（HTTP ${response.code}）。"
        })
    }
    private companion object { const val SECRET = "webdav.password"; const val MAX_BACKUP_BYTES = 8 * 1024 * 1024 }
}
