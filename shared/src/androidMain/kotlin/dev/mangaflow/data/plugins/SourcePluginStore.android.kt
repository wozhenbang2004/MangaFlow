package dev.mangaflow.data.plugins

import android.content.Context
import android.util.AtomicFile
import dev.mangaflow.data.SourceException
import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.Response
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Code, metadata, tombstones and installed index share a single durable atomic commit. */
actual class SourcePluginStore actual constructor(context: PlatformContext, actual val runtime: JavascriptSourceRuntime) {
    private val file = AtomicFile(File(context.applicationContext.filesDir, "source-plugins/packages.json"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableInstalled = MutableStateFlow<List<ScriptSourcePackage>>(emptyList())
    actual val installed: StateFlow<List<ScriptSourcePackage>> = mutableInstalled.asStateFlow()
    private val mutableLoadError = MutableStateFlow<String?>(null)
    actual val loadError: StateFlow<String?> = mutableLoadError.asStateFlow()
    private var damagedFilePending = false
    private var descriptors = JSONObject()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY).dns { host ->
        Dns.SYSTEM.lookup(host).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw IOException("源地址指向私有或保留网络。")
        }
    }.build()

    actual val ready: Deferred<Unit> = scope.async {
        mutex.withLock {
            try { if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                val root = file.openRead().use { input ->
                    val bytes = boundedBytes(input, MAX_STORE_BYTES)
                    JSONObject(bytes.toString(Charsets.UTF_8))
                }
                require(root.getInt("version") == 1) { "源包存储版本暂不支持。" }
                val packages = decodePackages(root.getJSONArray("packages"))
                descriptors = root.optJSONObject("descriptors") ?: JSONObject()
                mutableInstalled.value = packages
            } } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                damagedFilePending = true
                val preserved = preserveDamagedFile()
                mutableLoadError.value = "源包存储未能读取：${failure.message.orEmpty().take(180)}。" +
                    if (preserved) "原文件已隔离，可重新导入或从备份恢复；阅读记录未删除。"
                    else "原文件仍已保留，请释放存储空间后重新导入或恢复备份。"
                descriptors = JSONObject()
                mutableInstalled.value = emptyList()
            }
        }
    }

    actual suspend fun preview(codeOrJson: String, origin: String): List<SourcePluginCandidate> = withContext(Dispatchers.IO) {
        require(codeOrJson.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "源文件或索引超过 2 MiB。" }
        val text = codeOrJson.trim().removePrefix("\uFEFF")
        val script = !text.startsWith("[") && !text.startsWith("{")
        // Native JSONTokener accepts the upstream static JS metadata literal. Convert
        // its output explicitly; no org.json object crosses the shared model boundary.
        val native = org.json.JSONTokener(if (script) metadataLiteral(text) else text).nextValue()
        val parsed: Any = when (native) {
            is org.json.JSONArray -> JSONArray(native.toString())
            is org.json.JSONObject -> JSONObject(native.toString())
            else -> throw SourceException("这不是支持的 Mangayomi JS 源或仓库索引。")
        }
        val rows = when (parsed) {
            is JSONArray -> parsed
            is JSONObject -> parsed.optJSONArray("sources") ?: parsed.optJSONArray("manga") ?: JSONArray().put(parsed)
            else -> throw SourceException("这不是支持的 Mangayomi JS 源或仓库索引。")
        }
        require(rows.length() <= MAX_INDEX_ITEMS) { "源索引超过 2000 项。" }
        (0 until rows.length()).mapNotNull { index ->
            val metadata = rows.getJSONObject(index)
            val js = if (metadata.has("sourceCodeLanguage")) metadata.optInt("sourceCodeLanguage", -1) == 1 else script
            val manga = metadata.optBoolean("isManga", false) || metadata.optInt("itemType", -1) == 0 ||
                metadata.optString("pkgPath").replace('\\', '/').startsWith("manga/")
            if (!js || !manga) return@mapNotNull null
            val copy = JSONObject(metadata.toString())
            validateMetadata(copy)
            val codeUrl = copy.optString("sourceCodeUrl").takeIf(String::isNotBlank)?.let { resolveCodeUrl(it, origin) }
            codeUrl?.let { copy.put("sourceCodeUrl", it) }
            SourcePluginCandidate(copy, if (script) codeOrJson else null, origin.take(4096), codeUrl)
        }.also { require(it.isNotEmpty()) { "没有可导入的 JavaScript 漫画源（Dart、动画和小说源暂不支持）。" } }
    }

    actual suspend fun fetchPreview(url: String): List<SourcePluginCandidate> = preview(fetchText(url), url)

    actual suspend fun prepare(candidate: SourcePluginCandidate): ScriptSourcePackage = withContext(Dispatchers.IO) {
        val code = candidate.code ?: fetchText(candidate.codeUrl ?: candidate.metadata.optString("sourceCodeUrl")
            .takeIf(String::isNotBlank) ?: throw SourceException("这个索引条目没有 sourceCodeUrl。"))
        require(code.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "单个源代码超过 2 MiB。" }
        val scriptCandidates = preview(code, candidate.codeUrl ?: candidate.origin)
        val expectedBase = normalizedBase(candidate.metadata.getString("baseUrl"))
        val expectedLanguage = candidate.metadata.getString("lang").trim().lowercase()
        val matching = scriptCandidates.filter { normalizedBase(it.metadata.getString("baseUrl")) == expectedBase &&
            it.metadata.getString("lang").trim().lowercase() == expectedLanguage }
        require(matching.size == 1) { "索引条目与脚本声明的语言或站点不唯一对应，请选择具体源。" }
        val metadata = JSONObject(candidate.metadata.toString())
        matching.single().metadata.keys().forEach { key -> metadata.put(key, matching.single().metadata.get(key)) }
        candidate.codeUrl?.let { metadata.put("sourceCodeUrl", it) }
        metadata.put("sourceCodeLanguage", 1)
        val bundle = ScriptSourcePackage(identity(metadata), metadata, code, candidate.origin.take(4096), digest(code, metadata))
        validatePackage(bundle)
        bundle
    }

    actual suspend fun install(bundle: ScriptSourcePackage) {
        ready.await()
        val checked = withContext(Dispatchers.IO) { copyPackage(bundle).also(::validatePackage) }
        runtime.validate(checked)
        withContext(Dispatchers.IO) { mutex.withLock {
            val stable = checked
            val next = mutableInstalled.value.filterNot { it.sourceId == stable.sourceId } + stable
            validatePackages(next)
            val nextDescriptors = JSONObject(descriptors.toString()).put(stable.sourceId, descriptor(stable))
            commit(next, nextDescriptors)
        } }
    }

    actual suspend fun remove(sourceId: String) {
        ready.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            val current = mutableInstalled.value.firstOrNull { it.sourceId == sourceId } ?: return@withLock
            val nextDescriptors = JSONObject(descriptors.toString()).put(sourceId, descriptor(current))
            commit(mutableInstalled.value.filterNot { it.sourceId == sourceId }, nextDescriptors)
        } }
    }

    actual suspend fun exportBackup(): JSONArray = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock { JSONArray(mutableInstalled.value.map(::encodePackage)) }
    }

    actual suspend fun validateBackup(array: JSONArray): Unit = withContext(Dispatchers.IO) { decodePackages(array); Unit }

    actual suspend fun restoreBackup(array: JSONArray) {
        val packages = withContext(Dispatchers.IO) { decodePackages(array) }
        ready.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            val nextDescriptors = JSONObject(descriptors.toString())
            mutableInstalled.value.forEach { nextDescriptors.put(it.sourceId, descriptor(it)) }
            packages.forEach { nextDescriptors.put(it.sourceId, descriptor(it)) }
            commit(packages, nextDescriptors)
        } }
    }

    private fun commit(packages: List<ScriptSourcePackage>, nextDescriptors: JSONObject) {
        check(preserveDamagedFile()) { "原损坏源文件尚不能安全隔离，未覆盖；请检查存储空间。" }
        val bytes = JSONObject().put("version", 1).put("packages", JSONArray(packages.map(::encodePackage)))
            .put("descriptors", nextDescriptors).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STORE_BYTES) { "源包存储超过容量限制。" }
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建源包目录。" }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw SourceException("源包未能保存，请检查存储空间。", cause = failure) }
        descriptors = nextDescriptors
        mutableInstalled.value = packages
        mutableLoadError.value = null
    }

    private fun preserveDamagedFile(): Boolean {
        if (!damagedFilePending) return true
        val original = file.baseFile
        if (!original.exists()) { damagedFilePending = false; return true }
        val quarantine = File(original.parentFile, "packages-damaged-${System.currentTimeMillis()}.json")
        if (!runCatching { original.renameTo(quarantine) }.getOrDefault(false)) return false
        damagedFilePending = false
        return true
    }

    private suspend fun fetchText(value: String): String {
        val url = androidPluginUrl(value)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json,text/javascript,text/plain,*/*").build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(SourceException("源文件下载失败：${e.message.orEmpty().take(150)}", value, cause = e))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use { current ->
                            require(current.isSuccessful) { "源文件下载失败（HTTP ${current.code}），未导入重定向或错误页。" }
                            val body = current.body ?: throw IOException("源文件为空。")
                            require(body.contentLength() <= MAX_FILE_BYTES || body.contentLength() == -1L) { "源文件超过 2 MiB。" }
                            body.byteStream().use { boundedBytes(it, MAX_FILE_BYTES).toString(Charsets.UTF_8) }
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
                }
            })
        }
    }

    actual companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_TOTAL_CODE_BYTES = 8 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 64 * 1024
        private const val MAX_STORE_BYTES = 24 * 1024 * 1024
        private const val MAX_INDEX_ITEMS = 2_000
        private const val MAX_INSTALLED = 64
        @Volatile private var instance: SourcePluginStore? = null
        actual fun get(context: PlatformContext): SourcePluginStore = instance ?: synchronized(this) {
            instance ?: SourcePluginStore(context.applicationContext, JavascriptSourceRuntime(context.applicationContext)).also { instance = it }
        }

        private fun normalizedBase(value: String): String = androidPluginUrl(value).toString().trimEnd('/')
        private fun identity(metadata: JSONObject): String {
            val path = metadata.optString("pkgPath").trim().replace('\\', '/').ifBlank {
                metadata.optString("sourceCodeUrl").toHttpUrlOrNull()?.encodedPath?.removePrefix("/").orEmpty()
            }.ifBlank { metadata.opt("id")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty() }
            require(path.isNotBlank() && path.length <= 2048 && path.none(Char::isISOControl)) { "源声明缺少稳定 pkgPath、sourceCodeUrl 路径或上游 ID。" }
            val key = "mangayomi-js\u0000${metadata.getString("lang").trim().lowercase()}\u0000$path"
            return "js_" + sha256(key.toByteArray(Charsets.UTF_8)).take(36)
        }
        private fun digest(code: String, metadata: JSONObject): String = sha256((code + "\u0000" + canonical(metadata)).toByteArray(Charsets.UTF_8))
        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> throw IllegalArgumentException("源元数据类型不受支持。")
        }
        private fun validateMetadata(metadata: JSONObject) {
            require(metadata.toString().toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) { "单源元数据超过 64 KiB。" }
            require(metadata.optString("name").trim().length in 1..120 && metadata.optString("lang").trim().length in 1..40) { "源缺少有效名称或语言。" }
            normalizedBase(metadata.getString("baseUrl"))
            require(!metadata.has("sourceCodeLanguage") || metadata.optInt("sourceCodeLanguage", -1) == 1) { "仅支持 Mangayomi JavaScript 源，暂不支持 Dart。" }
            require(!metadata.has("itemType") || metadata.optInt("itemType", -1) == 0) { "仅支持漫画源，不支持动画或小说源。" }
            require(!metadata.has("isManga") || metadata.optBoolean("isManga", false)) { "这个源声明不是漫画源。" }
            ScriptBodySource.manifestHeaders(metadata)
        }
        private fun validatePackage(bundle: ScriptSourcePackage) {
            validateMetadata(bundle.metadata)
            require(bundle.code.isNotBlank() && bundle.code.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES) { "单源代码为空或超过 2 MiB。" }
            require(bundle.origin.length <= 4096 && bundle.sourceId == identity(bundle.metadata) && bundle.digest == digest(bundle.code, bundle.metadata)) { "源包身份或摘要校验失败。" }
        }
        private fun validatePackages(packages: List<ScriptSourcePackage>) {
            require(packages.size <= MAX_INSTALLED && packages.map { it.sourceId }.distinct().size == packages.size) { "源包最多 64 个，且不能存在重复身份。" }
            packages.forEach(::validatePackage)
            require(packages.sumOf { it.code.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_TOTAL_CODE_BYTES) { "已安装源代码总量超过 8 MiB。" }
        }
        private fun decodePackages(array: JSONArray): List<ScriptSourcePackage> {
            require(array.length() <= MAX_INSTALLED) { "源包数量超过 64 个。" }
            return (0 until array.length()).map { i -> array.getJSONObject(i).let {
                ScriptSourcePackage(it.getString("sourceId"), JSONObject(it.getJSONObject("metadata").toString()), it.getString("code"), it.getString("origin"), it.getString("digest"))
            } }.also(::validatePackages)
        }
        private fun encodePackage(bundle: ScriptSourcePackage) = descriptor(bundle).put("code", bundle.code)
        private fun descriptor(bundle: ScriptSourcePackage) = JSONObject().put("sourceId", bundle.sourceId)
            .put("metadata", JSONObject(bundle.metadata.toString())).put("origin", bundle.origin).put("digest", bundle.digest)
        private fun copyPackage(bundle: ScriptSourcePackage) = bundle.copy(metadata = JSONObject(bundle.metadata.toString()))
        private fun resolveCodeUrl(value: String, origin: String): String = androidPluginUrl(
            value.toHttpUrlOrNull()?.toString() ?: origin.toHttpUrlOrNull()?.resolve(value)?.toString()
                ?: throw SourceException("源脚本地址无效。")
        ).toString()

        private fun metadataLiteral(code: String): String {
            val declaration = findMetadataDeclaration(code)
                ?: throw SourceException("JS 文件缺少真实 mangayomiSources 元数据声明。")
            val start = declaration.range.last + 1
            require(start < code.length && code[start] in "[{") { "mangayomiSources 必须是静态 JSON 字面量。" }
            var depth = 0; var quote: Char? = null; var escaped = false; var lineComment = false; var blockComment = false
            var i = start
            while (i < code.length) {
                val c = code[i]; val next = code.getOrNull(i + 1)
                when {
                    lineComment -> if (c == '\n') lineComment = false
                    blockComment -> if (c == '*' && next == '/') { blockComment = false; i++ }
                    quote != null -> if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
                    c == '/' && next == '/' -> { lineComment = true; i++ }
                    c == '/' && next == '*' -> { blockComment = true; i++ }
                    c == '"' || c == '\'' -> quote = c
                    c == '[' || c == '{' -> depth++
                    c == ']' || c == '}' -> { depth--; if (depth == 0) return code.substring(start, i + 1) }
                }
                i++
            }
            throw SourceException("mangayomiSources 元数据不完整。")
        }
        private fun findMetadataDeclaration(code: String): MatchResult? {
            val pattern = Regex("(?:const|let|var)\\s+mangayomiSources\\s*=\\s*")
            var quote: Char? = null; var escaped = false; var line = false; var block = false; var i = 0
            while (i < code.length) {
                val c = code[i]; val next = code.getOrNull(i + 1)
                when {
                    line -> if (c == '\n') line = false
                    block -> if (c == '*' && next == '/') { block = false; i++ }
                    quote != null -> if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == quote) quote = null
                    c == '/' && next == '/' -> { line = true; i++ }
                    c == '/' && next == '*' -> { block = true; i++ }
                    c == '"' || c == '\'' || c == '`' -> quote = c
                    c in "clv" && (i == 0 || code[i - 1].let { !it.isLetterOrDigit() && it != '_' && it != '$' }) ->
                        pattern.matchAt(code, i)?.let { return it }
                }
                i++
            }
            return null
        }
        private fun boundedBytes(input: java.io.InputStream, maximum: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break
                require(output.size().toLong() + count <= maximum) { "源文件超过容量限制。" }; output.write(buffer, 0, count) }
            return output.toByteArray()
        }
    }
}

/** Syntax/known-network check also applies to source-provided public image URLs. */
internal fun androidPluginUrl(value: String): HttpUrl = value.toHttpUrlOrNull()?.takeIf { url ->
    url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.host.contains('.') &&
        !url.host.endsWith(".local", true) && !url.host.endsWith(".localhost", true) &&
        !url.host.endsWith(".internal", true) && url.host != "localhost" &&
        (!url.host.matches(Regex("[0-9.]+")) || runCatching { publicAddress(InetAddress.getByName(url.host)) }.getOrDefault(false))
} ?: throw SourceException("源文件和图片必须使用公开 HTTPS 地址，不支持私网地址或嵌入凭据。")

private fun publicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address.map { it.toInt() and 255 }
    if (bytes.size == 16 && bytes.take(10).all { it == 0 } && bytes[10] == 255 && bytes[11] == 255)
        return publicAddress(InetAddress.getByAddress(address.address.copyOfRange(12, 16)))
    return if (bytes.size == 4) bytes[0] != 0 && bytes[0] < 224 &&
        !(bytes[0] == 100 && bytes[1] in 64..127) && !(bytes[0] == 198 && bytes[1] in 18..19)
    else bytes.size == 16 && (bytes[0] and 0xfe) != 0xfc
}
