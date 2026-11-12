package dev.mangaflow.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class WebDavBackupConfig(
    val endpoint: String = "",
    val username: String = "",
    val remotePath: String = DEFAULT_REMOTE_PATH,
    val hasPassword: Boolean = false,
    val lastUploadAt: Long = 0L,
    val lastDownloadAt: Long = 0L,
) {
    val configured: Boolean get() = endpoint.isNotBlank() && remotePath.isNotBlank()

    companion object {
        const val DEFAULT_REMOTE_PATH = "MangaFlow/reading-backup.json"
    }
}

class WebDavBackupStore(context: Context) {
    private val disk: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<WebDavBackupConfig> = _config.asStateFlow()

    suspend fun saveConfiguration(
        endpoint: String,
        username: String,
        remotePath: String,
        password: String?,
    ): WebDavBackupConfig = withContext(Dispatchers.IO) {
        if (username.length > MAX_USERNAME_CHARS) throw WebDavBackupException("WebDAV 用户名过长。")
        if (password != null && password.length > MAX_PASSWORD_CHARS) {
            throw WebDavBackupException("WebDAV 密码过长。")
        }
        val normalizedEndpoint = normalizeEndpoint(endpoint).toString()
        val normalizedUsername = username.trim()
        val normalizedPath = normalizeRemotePath(remotePath).joinToString("/")
        synchronized(lock) {
            val previous = readConfig()
            val previousUrl = previous.endpoint.toHttpUrlOrNull()
            val nextUrl = normalizedEndpoint.toHttpUrlOrNull()
            val identityChanged = previous.configured && previous.hasPassword && password == null && (
                previousUrl?.host != nextUrl?.host ||
                    previousUrl?.port != nextUrl?.port ||
                    previous.username != normalizedUsername
                )
            if (identityChanged) {
                throw WebDavBackupException("更换服务器或用户名后，请重新输入 WebDAV 密码。")
            }

            val editor = disk.edit()
                .putString(KEY_ENDPOINT, normalizedEndpoint)
                .putString(KEY_USERNAME, normalizedUsername)
                .putString(KEY_REMOTE_PATH, normalizedPath)
            if (password != null) {
                if (password.isEmpty()) {
                    editor.remove(KEY_SECRET_IV).remove(KEY_SECRET_VALUE)
                } else {
                    val encrypted = encryptPassword(password)
                    editor.putString(KEY_SECRET_IV, encrypted.iv)
                        .putString(KEY_SECRET_VALUE, encrypted.value)
                }
            }
            check(editor.commit()) { "无法保存 WebDAV 配置，请检查设备存储空间。" }
            readConfig().also { _config.value = it }
        }
    }

    suspend fun clearConfiguration() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(disk.edit().clear().commit()) { "无法清除 WebDAV 配置，请检查设备存储空间。" }
            runCatching {
                val keyStore = androidKeyStore()
                if (keyStore.containsAlias(KEYSTORE_ALIAS)) keyStore.deleteEntry(KEYSTORE_ALIAS)
            }
            _config.value = readConfig()
        }
    }

    suspend fun upload(payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) throw WebDavBackupException("阅读备份为空，无法上传。")
        if (bytes.size > MAX_BACKUP_BYTES) throw WebDavBackupException("阅读备份超过 8 MiB，已停止上传。")
        val credentials = withContext(Dispatchers.IO) { readCredentials() }
        val base = normalizeEndpoint(credentials.config.endpoint)
        val segments = normalizeRemotePath(credentials.config.remotePath)
        createCollections(base, segments.dropLast(1), credentials)
        val request = requestBuilder(buildUrl(base, segments), credentials)
            .put(bytes.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        execute(request, "上传阅读备份") { response ->
            ensureSuccessful(response, "上传阅读备份")
            Unit
        }
        rememberTransfer(upload = true)
    }

    suspend fun download(): String {
        val credentials = withContext(Dispatchers.IO) { readCredentials() }
        val base = normalizeEndpoint(credentials.config.endpoint)
        val segments = normalizeRemotePath(credentials.config.remotePath)
        val request = requestBuilder(buildUrl(base, segments), credentials)
            .get()
            .header("Accept", "application/json, text/plain;q=0.8")
            .build()
        val payload = execute(request, "下载阅读备份") { response ->
            ensureSuccessful(response, "下载阅读备份")
            val body = response.body ?: throw WebDavBackupException("远端返回了空备份。")
            val contentLength = body.contentLength()
            if (contentLength > MAX_BACKUP_BYTES) {
                throw WebDavBackupException("远端备份超过 8 MiB，已停止下载。")
            }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_BACKUP_BYTES) {
                        throw WebDavBackupException("远端备份超过 8 MiB，已停止下载。")
                    }
                    output.write(buffer, 0, count)
                }
            }
            if (output.size() == 0) throw WebDavBackupException("远端备份为空。")
            output.toString(Charsets.UTF_8.name())
        }
        rememberTransfer(upload = false)
        return payload
    }

    private suspend fun createCollections(
        base: HttpUrl,
        parents: List<String>,
        credentials: WebDavCredentials,
    ) {
        parents.indices.forEach { index ->
            val url = buildUrl(base, parents.take(index + 1), collection = true)
            val request = requestBuilder(url, credentials)
                .method("MKCOL", EMPTY_BODY)
                .build()
            execute(request, "准备 WebDAV 目录") { response ->
                if (response.code != 405) ensureSuccessful(response, "准备 WebDAV 目录")
                Unit
            }
        }
    }

    private fun requestBuilder(url: HttpUrl, credentials: WebDavCredentials): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "MangaFlow-WebDAV")
            .header("Cache-Control", "no-store")
        if (credentials.config.username.isNotEmpty() || credentials.password.isNotEmpty()) {
            builder.header(
                "Authorization",
                Credentials.basic(credentials.config.username, credentials.password, Charsets.UTF_8),
            )
        }
        return builder
    }

    private suspend fun <T> execute(
        request: Request,
        operation: String,
        read: (Response) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, failure: IOException) {
                if (!continuation.isActive) return
                continuation.resumeWithException(mapNetworkFailure(operation, failure))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use(read)
                    if (continuation.isActive) continuation.resume(value)
                } catch (failure: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            if (failure is WebDavBackupException) failure
                            else WebDavBackupException("$operation 失败，请稍后重试。", failure),
                        )
                    }
                }
            }
        })
    }

    private fun ensureSuccessful(response: Response, operation: String) {
        if (response.isSuccessful) return
        throw when (response.code) {
            301, 302, 303, 307, 308 -> WebDavBackupException(
                "WebDAV 地址发生重定向，请填写跳转后的最终 HTTPS 地址。",
            )
            401, 403 -> WebDavBackupException("WebDAV 账号、密码或目录权限不正确。")
            404 -> WebDavBackupException("远端还没有阅读备份，或 WebDAV 路径不存在。")
            405 -> WebDavBackupException("服务器不允许执行 $operation，请检查 WebDAV 目录权限。")
            409 -> WebDavBackupException("远端目录不存在，且服务器拒绝自动创建。")
            413 -> WebDavBackupException("服务器拒绝了过大的阅读备份。")
            423 -> WebDavBackupException("远端备份正被锁定，请稍后重试。")
            429 -> WebDavBackupException("WebDAV 请求过于频繁，请稍后重试。")
            507 -> WebDavBackupException("WebDAV 存储空间不足。")
            else -> WebDavBackupException("$operation 暂时不可用（HTTP ${response.code}）。")
        }
    }

    private fun mapNetworkFailure(operation: String, failure: IOException): WebDavBackupException = when (failure) {
        is SSLException -> WebDavBackupException("WebDAV TLS 连接失败，请检查 HTTPS 证书和地址。", failure)
        is UnknownHostException -> WebDavBackupException("找不到 WebDAV 服务器，请检查地址。", failure)
        is SocketTimeoutException -> WebDavBackupException("$operation 超时，请检查网络后重试。", failure)
        else -> WebDavBackupException("$operation 连接失败，请检查网络和 WebDAV 地址。", failure)
    }

    private fun readCredentials(): WebDavCredentials = synchronized(lock) {
        val current = readConfig()
        if (!current.configured) throw WebDavBackupException("请先填写 WebDAV HTTPS 地址和远端文件。")
        val password = if (current.hasPassword) decryptPassword() else ""
        WebDavCredentials(current, password)
    }

    private fun rememberTransfer(upload: Boolean) {
        val timestamp = System.currentTimeMillis()
        synchronized(lock) {
            val key = if (upload) KEY_LAST_UPLOAD else KEY_LAST_DOWNLOAD
            disk.edit().putLong(key, timestamp).apply()
            _config.value = readConfig()
        }
    }

    private fun readConfig(): WebDavBackupConfig = WebDavBackupConfig(
        endpoint = disk.getString(KEY_ENDPOINT, "")?.trim().orEmpty(),
        username = disk.getString(KEY_USERNAME, "")?.trim().orEmpty(),
        remotePath = disk.getString(KEY_REMOTE_PATH, WebDavBackupConfig.DEFAULT_REMOTE_PATH)
            ?.trim()
            .orEmpty()
            .ifBlank { WebDavBackupConfig.DEFAULT_REMOTE_PATH },
        hasPassword = !disk.getString(KEY_SECRET_IV, null).isNullOrBlank() &&
            !disk.getString(KEY_SECRET_VALUE, null).isNullOrBlank(),
        lastUploadAt = disk.getLong(KEY_LAST_UPLOAD, 0L),
        lastDownloadAt = disk.getLong(KEY_LAST_DOWNLOAD, 0L),
    )

    private fun encryptPassword(password: String): EncryptedPassword {
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            EncryptedPassword(
                iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
                value = Base64.encodeToString(
                    cipher.doFinal(password.toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                ),
            )
        } catch (failure: Exception) {
            throw WebDavBackupException("无法安全保存 WebDAV 密码。", failure)
        }
    }

    private fun decryptPassword(): String {
        val iv = disk.getString(KEY_SECRET_IV, null)
            ?: throw WebDavBackupException("已保存的 WebDAV 密码不完整，请重新输入。")
        val value = disk.getString(KEY_SECRET_VALUE, null)
            ?: throw WebDavBackupException("已保存的 WebDAV 密码不完整，请重新输入。")
        return try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (failure: Exception) {
            throw WebDavBackupException("已保存的 WebDAV 密码无法解密，请清除配置后重新输入。", failure)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = androidKeyStore()
        val existing = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        if (keyStore.containsAlias(KEYSTORE_ALIAS)) keyStore.deleteEntry(KEYSTORE_ALIAS)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun normalizeEndpoint(raw: String): HttpUrl {
        val value = raw.trim()
        if (value.length > MAX_ENDPOINT_CHARS) throw WebDavBackupException("WebDAV 地址过长。")
        val parsed = value.toHttpUrlOrNull()
            ?: throw WebDavBackupException("请输入完整的 WebDAV HTTPS 地址。")
        if (parsed.scheme != "https") throw WebDavBackupException("WebDAV 只允许使用 HTTPS 地址。")
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw WebDavBackupException("请不要把账号密码写进 URL，使用下方账号与密码输入框。")
        }
        if (parsed.query != null || parsed.fragment != null) {
            throw WebDavBackupException("WebDAV 地址不能包含查询参数或片段。")
        }
        val builder = parsed.newBuilder()
        if (!parsed.encodedPath.endsWith('/')) builder.addPathSegment("")
        return builder.build()
    }

    private fun normalizeRemotePath(raw: String): List<String> {
        val value = raw.trim().replace('\\', '/')
        if (value.length > MAX_REMOTE_PATH_CHARS) throw WebDavBackupException("远端文件路径过长。")
        val segments = value.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) throw WebDavBackupException("请填写远端备份文件路径。")
        if (segments.size > MAX_REMOTE_SEGMENTS) throw WebDavBackupException("远端文件路径层级过深。")
        segments.forEach { segment ->
            if (segment == "." || segment == "..") throw WebDavBackupException("远端文件路径不能包含 . 或 ..。")
            if (segment.any { it.code < 32 || it == '\u007f' }) {
                throw WebDavBackupException("远端文件路径包含无效字符。")
            }
        }
        return segments
    }

    private fun buildUrl(base: HttpUrl, segments: List<String>, collection: Boolean = false): HttpUrl {
        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        if (collection) builder.addPathSegment("")
        return builder.build()
    }

    private data class EncryptedPassword(val iv: String, val value: String)
    private data class WebDavCredentials(val config: WebDavBackupConfig, val password: String)

    private class WebDavBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private companion object {
        const val PREFERENCES_NAME = "mangaflow_webdav_backup"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_USERNAME = "username"
        const val KEY_REMOTE_PATH = "remote_path"
        const val KEY_SECRET_IV = "secret_iv"
        const val KEY_SECRET_VALUE = "secret_value"
        const val KEY_LAST_UPLOAD = "last_upload"
        const val KEY_LAST_DOWNLOAD = "last_download"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "mangaflow.webdav.password.v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
        const val MAX_ENDPOINT_CHARS = 4096
        const val MAX_USERNAME_CHARS = 512
        const val MAX_PASSWORD_CHARS = 4096
        const val MAX_REMOTE_PATH_CHARS = 1024
        const val MAX_REMOTE_SEGMENTS = 32
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}
