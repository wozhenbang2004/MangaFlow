package dev.mangaflow.data.plugins

import dev.mangaflow.data.SourceException
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.HttpUrl
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageInput
import dev.mangaflow.platform.storage.applicationContext
import dev.mangaflow.platform.storage.filesDir
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*

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

/** Code, metadata, tombstones and installed index share a single durable atomic commit. */
class SourcePluginStore constructor(context: PlatformContext, val runtime: JavascriptSourceRuntime) {
    private val application = context.applicationContext
    private val file = StorageAtomicFile(StorageFile(context.applicationContext.filesDir, "source-plugins/packages.json"))
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableInstalled = MutableStateFlow<List<ScriptSourcePackage>>(emptyList())
    val installed: StateFlow<List<ScriptSourcePackage>> = mutableInstalled.asStateFlow()
    private val mutableRepositories = MutableStateFlow<List<SourcePluginRepository>>(emptyList())
    val repositories: StateFlow<List<SourcePluginRepository>> = mutableRepositories.asStateFlow()
    private val mutableLoadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = mutableLoadError.asStateFlow()
    private var damagedFilePending = false
    private var descriptors = JSONObject()
    val ready: Deferred<Unit> = scope.async {
        mutex.withLock {
            try { if (file.baseFile.exists() || StorageFile(file.baseFile.path + ".bak").exists()) {
                val root = file.openRead().use { input ->
                    val bytes = boundedBytes(input, MAX_STORE_BYTES)
                    JSONObject(bytes.decodeToString())
                }
                require(root.getInt("version") == 1) { "源包存储版本暂不支持。" }
                val packages = decodePackages(root.getJSONArray("packages"))
                descriptors = root.optJSONObject("descriptors") ?: JSONObject()
                mutableInstalled.value = packages
                mutableRepositories.value = decodeRepositories(root.optJSONArray("repositories") ?: JSONArray())
            } } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                damagedFilePending = true
                val preserved = preserveDamagedFile()
                mutableLoadError.value = "源包存储未能读取：${failure.message.orEmpty().take(180)}。" +
                    if (preserved) "原文件已隔离，可重新导入或从备份恢复；阅读记录未删除。"
                    else "原文件仍已保留，请释放存储空间后重新导入或恢复备份。"
                descriptors = JSONObject()
                mutableInstalled.value = emptyList()
                mutableRepositories.value = emptyList()
            }
        }
    }

    suspend fun preview(codeOrJson: String, origin: String): List<SourcePluginCandidate> = withContext(Dispatchers.IO) {
        require(codeOrJson.encodeToByteArray().size <= MAX_FILE_BYTES) { "源文件或索引超过 2 MiB。" }
        val text = codeOrJson.trim().removePrefix("\uFEFF")
        val script = !text.startsWith("[") && !text.startsWith("{")
        if (script && VeneraMetadata.isScript(text)) {
            val metadata = VeneraMetadata.fromScript(text, origin)
            validateMetadata(metadata)
            return@withContext listOf(SourcePluginCandidate(metadata, codeOrJson, origin.take(4096),
                metadata.optString("sourceCodeUrl").takeIf(String::isNotBlank)))
        }
        val literal = if (script) staticJavascriptJson(metadataLiteral(text)) else text
        val parsed: Any = if (literal.trimStart().startsWith("[")) JSONArray(literal) else JSONObject(literal)
        val rows = when (parsed) {
            is JSONArray -> parsed
            is JSONObject -> parsed.optJSONArray("sources") ?: parsed.optJSONArray("manga") ?: JSONArray().put(parsed)
            else -> throw SourceException("这不是支持的 Mangayomi JS 源或仓库索引。")
        }
        require(rows.length() <= MAX_INDEX_ITEMS) { "源索引超过 2000 项。" }
        (0 until rows.length()).mapNotNull { index ->
            val raw = rows.getJSONObject(index)
            val metadata = if (VeneraMetadata.isEntry(raw)) VeneraMetadata.fromIndex(raw, origin) else raw
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

    suspend fun fetchPreview(url: String): List<SourcePluginCandidate> = preview(fetchText(url), url)

    suspend fun fetchRepository(url: String): SourceRepositoryCatalog = withContext(Dispatchers.IO) {
        val indexUrl = repositoryIndexUrl(url)
        val text = fetchText(indexUrl).trim().removePrefix("\uFEFF")
        val rows = when {
            text.startsWith("[") -> JSONArray(text)
            text.startsWith("{") -> JSONObject(text).let { root ->
                root.optJSONArray("sources") ?: root.optJSONArray("manga")
                    ?: throw SourceException("仓库目录缺少 sources / manga 列表，请使用 Mangayomi JS 仓库的 index.json 直链。")
            }
            else -> throw SourceException("这不是源仓库目录。请填写 index.json 的 HTTPS 直链；单个脚本请切换到文件或脚本。")
        }
        require(rows.length() in 1..MAX_INDEX_ITEMS) { "仓库目录为空或超过 2000 项。" }
        val entries = (0 until rows.length()).map { index ->
            currentCoroutineContext().ensureActive()
            val metadata = rows.optJSONObject(index)
            val name = metadata?.optString("name")?.trim()?.take(120).orEmpty().ifBlank { "未命名来源 ${index + 1}" }
            val language = metadata?.optString("lang")?.take(40).orEmpty()
            val version = metadata?.optString("version")?.take(80).orEmpty()
            val site = metadata?.optString("baseUrl")?.take(4096).orEmpty()
            var candidate: SourcePluginCandidate? = null
            val reason = when {
                metadata == null -> "条目不是有效的源声明"
                VeneraMetadata.isEntry(metadata) -> try {
                    candidate = preview(JSONArray().put(metadata).toString(), indexUrl).single()
                    null
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { failure.message?.take(180) ?: "Venera 源声明暂不兼容" }
                metadata.optInt("sourceCodeLanguage", -1) == 0 -> "Dart 源暂不支持"
                metadata.optInt("sourceCodeLanguage", -1) != 1 -> "未声明兼容的 JavaScript 源格式"
                metadata.optInt("itemType", -1) != 0 && !metadata.optBoolean("isManga", false) -> "动画或小说源暂不支持"
                metadata.optBoolean("hasCloudflare", false) -> "需要网页验证，此脚本宿主暂不支持"
                else -> try {
                    candidate = preview(JSONArray().put(metadata).toString(), indexUrl).single()
                    require(!candidate?.codeUrl.isNullOrBlank()) { "条目没有可下载的脚本地址" }
                    null
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { candidate = null; failure.message?.take(180) ?: "源声明暂不兼容" }
            }
            SourceRepositoryEntry(name, language, version, site, candidate, reason)
        }
        val checked = publicPluginUrl(indexUrl)
        val name = if (checked.host == "raw.githubusercontent.com") checked.pathSegments.take(2).joinToString("/")
            else checked.host
        val repository = SourcePluginRepository(indexUrl, name)
        ready.await()
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val next = mutableRepositories.value.filterNot { it.url == indexUrl } + repository
            require(next.size <= MAX_REPOSITORIES) { "最多添加 16 个源仓库，请先移除不再使用的仓库。" }
            if (next != mutableRepositories.value) commit(mutableInstalled.value, descriptors, next)
        }
        SourceRepositoryCatalog(repository, entries)
    }

    suspend fun removeRepository(url: String) {
        ready.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            val next = mutableRepositories.value.filterNot { it.url == url }
            if (next != mutableRepositories.value) commit(mutableInstalled.value, descriptors, next)
        } }
    }

    suspend fun prepare(candidate: SourcePluginCandidate): ScriptSourcePackage = withContext(Dispatchers.IO) {
        var code = candidate.code ?: fetchText(candidate.codeUrl ?: candidate.metadata.optString("sourceCodeUrl")
            .takeIf(String::isNotBlank) ?: throw SourceException("这个索引条目没有 sourceCodeUrl。"))
        require(code.encodeToByteArray().size <= MAX_FILE_BYTES) { "单个源代码超过 2 MiB。" }
        val scriptCandidates = preview(code, candidate.codeUrl ?: candidate.origin)
        val expectedBase = normalizedBase(candidate.metadata.getString("baseUrl"))
        val expectedLanguage = candidate.metadata.getString("lang").trim().lowercase()
        val venera = candidate.metadata.optString("protocol") == "venera"
        val matching = scriptCandidates.filter {
            if (venera) it.metadata.optString("protocol") == "venera" &&
                it.metadata.optString("key") == candidate.metadata.optString("key")
            else it.metadata.optString("protocol") != "venera" &&
                normalizedBase(it.metadata.getString("baseUrl")) == expectedBase &&
                it.metadata.getString("lang").trim().lowercase() == expectedLanguage
        }
        require(matching.size == 1) { "索引条目与脚本声明的语言或站点不唯一对应，请选择具体源。" }
        val metadata = JSONObject(candidate.metadata.toString())
        matching.single().metadata.keys().forEach { key -> metadata.put(key, matching.single().metadata.get(key)) }
        candidate.codeUrl?.let { metadata.put("sourceCodeUrl", it) }
        metadata.put("sourceCodeLanguage", 1)
        metadata.put("isAdult", adultSourceMetadata(metadata) || adultSourceMetadata(candidate.metadata))
        metadata.put("upstreamCodeDigest", sha256(code.encodeToByteArray()))
        if (venera) {
            val repaired = repairVeneraScript(code, metadata)
            code = repaired.first
            if (repaired.second.length() > 0) metadata.put("hostRepairs", repaired.second)
        }
        val sourceId = identity(metadata)
        if (!candidate.origin.startsWith("https://")) {
            ready.await()
            installed.value.firstOrNull { it.sourceId == sourceId && it.code != code }?.let { previous ->
                metadata.put("locallyModified", true).put("upstreamVersion", previous.metadata.optString("version"))
                    .put("upstreamCodeDigest", previous.metadata.optString("upstreamCodeDigest"))
            }
        }
        val bundle = ScriptSourcePackage(sourceId, metadata, code, candidate.origin.take(4096), digest(code, metadata))
        validatePackage(bundle)
        bundle
    }

    /** A read-only update check. Changed code is never installed in the background. */
    suspend fun checkUpdate(sourceId: String): SourcePluginCandidate? {
        ready.await()
        val current = installed.value.firstOrNull { it.sourceId == sourceId }
            ?: throw SourceException("这个脚本源尚未安装。")
        val url = current.metadata.optString("sourceCodeUrl").takeIf(String::isNotBlank)
            ?: throw SourceException("这个脚本没有可检查的更新地址。")
        val candidate = fetchPreview(url).singleOrNull { identity(it.metadata) == sourceId }
            ?: throw SourceException("更新脚本的来源身份不一致，已保留当前版本。")
        val originalDigest = current.metadata.optString("upstreamCodeDigest")
        return candidate.takeIf { incoming -> incoming.code?.let {
            if (originalDigest.isNotBlank()) sha256(it.encodeToByteArray()) != originalDigest else it != current.code
        } ?: true }
    }

    suspend fun update(sourceId: String) {
        ready.await()
        val old = installed.value.firstOrNull { it.sourceId == sourceId }
            ?: throw SourceException("这个脚本源尚未安装。")
        require(!old.metadata.optBoolean("locallyModified", false)) {
            "此脚本含本机修订，请先通过文件预览合并更新，当前修订已保留。"
        }
        val candidate = checkUpdate(sourceId) ?: return
        val prepared = prepare(candidate)
        require(prepared.sourceId == sourceId) { "更新源身份不一致。" }
        runtime.validate(prepared)
        withContext(Dispatchers.IO) { mutex.withLock {
            require(mutableInstalled.value.firstOrNull { it.sourceId == sourceId }?.digest == old.digest) {
                "检查期间脚本已更改，请重新检查更新。"
            }
            val next = mutableInstalled.value.map { if (it.sourceId == sourceId) prepared else it }
            validatePackages(next)
            commit(next, JSONObject(descriptors.toString()).put(sourceId, descriptor(prepared)))
        } }
    }

    /** Explicit local repairs retain the upstream base and are protected from one-click updates. */
    suspend fun patch(sourceId: String, code: String) {
        ready.await()
        val old = installed.value.firstOrNull { it.sourceId == sourceId } ?: throw SourceException("来源未安装。")
        val candidate = preview(code, old.metadata.optString("sourceCodeUrl").ifBlank { old.origin })
            .singleOrNull { identity(it.metadata) == sourceId } ?: throw SourceException("修订不能改变来源身份。")
        val changed = prepare(candidate)
        val metadata = JSONObject(changed.metadata.toString()).put("locallyModified", true)
            .put("upstreamCodeDigest", old.metadata.optString("upstreamCodeDigest").ifBlank { sha256(old.code.encodeToByteArray()) })
            .put("upstreamVersion", old.metadata.optString("version"))
        val repaired = changed.copy(metadata = metadata, digest = digest(changed.code, metadata))
        runtime.validate(repaired)
        withContext(Dispatchers.IO) { mutex.withLock {
            require(mutableInstalled.value.firstOrNull { it.sourceId == sourceId }?.digest == old.digest) {
                "来源已更改，请重新应用修订。"
            }
            val next = mutableInstalled.value.map { if (it.sourceId == sourceId) repaired else it }
            validatePackages(next)
            commit(next, JSONObject(descriptors.toString()).put(sourceId, descriptor(repaired)))
        } }
    }

    suspend fun install(bundle: ScriptSourcePackage) {
        ready.await()
        val checked = withContext(Dispatchers.IO) { copyPackage(bundle).also(::validatePackage) }
        runtime.validate(checked)
        withContext(Dispatchers.IO) { mutex.withLock {
            val stable = checked
            if (mutableInstalled.value.any { it.sourceId == stable.sourceId && it.digest == stable.digest }) return@withLock
            val next = mutableInstalled.value.filterNot { it.sourceId == stable.sourceId } + stable
            validatePackages(next)
            val nextDescriptors = JSONObject(descriptors.toString()).put(stable.sourceId, descriptor(stable))
            commit(next, nextDescriptors)
        } }
    }

    suspend fun remove(sourceId: String) {
        ready.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            val current = mutableInstalled.value.firstOrNull { it.sourceId == sourceId } ?: return@withLock
            val nextDescriptors = JSONObject(descriptors.toString()).put(sourceId, descriptor(current))
            commit(mutableInstalled.value.filterNot { it.sourceId == sourceId }, nextDescriptors)
        } }
    }

    suspend fun exportBackup(): JSONArray = withContext(Dispatchers.IO) {
        ready.await()
        mutex.withLock { JSONArray(mutableInstalled.value.map(::encodePackage)) }
    }

    suspend fun validateBackup(array: JSONArray): Unit = withContext(Dispatchers.IO) { decodePackages(array); Unit }

    suspend fun restoreBackup(array: JSONArray) {
        val packages = withContext(Dispatchers.IO) { decodePackages(array) }
        ready.await()
        withContext(Dispatchers.IO) { mutex.withLock {
            val nextDescriptors = JSONObject(descriptors.toString())
            mutableInstalled.value.forEach { nextDescriptors.put(it.sourceId, descriptor(it)) }
            packages.forEach { nextDescriptors.put(it.sourceId, descriptor(it)) }
            commit(packages, nextDescriptors)
        } }
    }

    private fun commit(packages: List<ScriptSourcePackage>, nextDescriptors: JSONObject,
        nextRepositories: List<SourcePluginRepository> = mutableRepositories.value) {
        check(preserveDamagedFile()) { "原损坏源文件尚不能安全隔离，未覆盖；请检查存储空间。" }
        val bytes = JSONObject().put("version", 1).put("packages", JSONArray(packages.map(::encodePackage)))
            .put("descriptors", nextDescriptors)
            .put("repositories", JSONArray(nextRepositories.map { JSONObject().put("url", it.url).put("name", it.name) }))
            .toString().encodeToByteArray()
        require(bytes.size <= MAX_STORE_BYTES) { "源包存储超过容量限制。" }
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建源包目录。" }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw SourceException("源包未能保存，请检查存储空间。", cause = failure) }
        descriptors = nextDescriptors
        mutableInstalled.value = packages
        mutableRepositories.value = nextRepositories
        mutableLoadError.value = null
    }

    private fun preserveDamagedFile(): Boolean {
        if (!damagedFilePending) return true
        val original = file.baseFile
        if (!original.exists()) { damagedFilePending = false; return true }
        val quarantine = StorageFile(original.parentFile, "packages-damaged-${wallTimeMillis()}.json")
        if (!runCatching { original.renameTo(quarantine) }.getOrDefault(false)) return false
        damagedFilePending = false
        return true
    }

    private suspend fun fetchText(value: String): String = fetchPluginText(application, publicPluginUrl(value).toString())

    companion object {
        private const val MAX_FILE_BYTES = 2 * 1024 * 1024
        private const val MAX_TOTAL_CODE_BYTES = 8 * 1024 * 1024
        private const val MAX_METADATA_BYTES = 64 * 1024
        private const val MAX_STORE_BYTES = 24 * 1024 * 1024
        private const val MAX_INDEX_ITEMS = 2_000
        private const val MAX_INSTALLED = 64
        private const val MAX_REPOSITORIES = 16
        private val instanceLock = SynchronizedObject()
        private var instance: SourcePluginStore? = null
        fun get(context: PlatformContext): SourcePluginStore = synchronized(instanceLock) {
            instance ?: SourcePluginStore(context.applicationContext, JavascriptSourceRuntime(context.applicationContext)).also { instance = it }
        }

        private fun normalizedBase(value: String): String = publicPluginUrl(value).toString().trimEnd('/')
        private fun repositoryIndexUrl(value: String): String {
            val url = publicPluginUrl(value.trim())
            require(value.length <= 4096) { "仓库地址过长。" }
            if (url.host in setOf("github.com", "www.github.com")) {
                val parts = url.pathSegments
                require(parts.size >= 5 && parts[2] == "blob" && parts.last().endsWith(".json", true)) {
                    "请填写仓库 index.json 的 HTTPS 直链，不是仓库首页。"
                }
                return publicPluginUrl("https://raw.githubusercontent.com/" +
                    (parts.take(2) + parts.drop(3)).joinToString("/")).toString()
            }
            return url.newBuilder().fragment(null).build().toString()
        }
        private fun decodeRepositories(array: JSONArray): List<SourcePluginRepository> {
            require(array.length() <= MAX_REPOSITORIES) { "源仓库数量超过限制。" }
            return (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                SourcePluginRepository(repositoryIndexUrl(item.getString("url")), item.optString("name").take(180))
            }.distinctBy { it.url }
        }
        private fun identity(metadata: JSONObject): String {
            if (metadata.optString("protocol") == "venera") {
                val key = metadata.getString("key")
                require(key.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Venera 来源编号无效。" }
                return "js_" + sha256(("venera-js\u0000" + VeneraMetadata.namespace(metadata) + "\u0000" + key).encodeToByteArray()).take(36)
            }
            val path = metadata.optString("pkgPath").trim().replace('\\', '/').ifBlank {
                metadata.optString("sourceCodeUrl").toHttpUrlOrNull()?.encodedPath?.removePrefix("/").orEmpty()
            }.ifBlank { metadata.opt("id")?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty() }
            require(path.isNotBlank() && path.length <= 2048 && path.none(Char::isISOControl)) { "源声明缺少稳定 pkgPath、sourceCodeUrl 路径或上游 ID。" }
            val key = "mangayomi-js\u0000${metadata.getString("lang").trim().lowercase()}\u0000$path"
            return "js_" + sha256(key.encodeToByteArray()).take(36)
        }
        private fun digest(code: String, metadata: JSONObject): String = sha256((code + "\u0000" + canonical(metadata)).encodeToByteArray())
        private fun sha256(bytes: ByteArray): String = dev.mangaflow.platform.sha256(bytes).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        private fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> throw IllegalArgumentException("源元数据类型不受支持。")
        }
        private fun validateMetadata(metadata: JSONObject) {
            require(metadata.toString().encodeToByteArray().size <= MAX_METADATA_BYTES) { "单源元数据超过 64 KiB。" }
            require(metadata.optString("name").trim().length in 1..120 && metadata.optString("lang").trim().length in 1..40) { "源缺少有效名称或语言。" }
            normalizedBase(metadata.getString("baseUrl"))
            require(metadata.optString("protocol") in setOf("", "mangayomi", "venera")) { "未知脚本源协议。" }
            if (metadata.optString("protocol") == "venera" && metadata.has("entryClass")) {
                require(metadata.getString("entryClass").matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,119}"))) { "脚本入口类名无效。" }
            }
            require(!metadata.has("sourceCodeLanguage") || metadata.optInt("sourceCodeLanguage", -1) == 1) { "仅支持 Mangayomi JavaScript 源，暂不支持 Dart。" }
            require(!metadata.has("itemType") || metadata.optInt("itemType", -1) == 0) { "仅支持漫画源，不支持动画或小说源。" }
            require(!metadata.has("isManga") || metadata.optBoolean("isManga", false)) { "这个源声明不是漫画源。" }
            ScriptBodySource.manifestHeaders(metadata)
        }
        private fun validatePackage(bundle: ScriptSourcePackage) {
            validateMetadata(bundle.metadata)
            require(bundle.code.isNotBlank() && bundle.code.encodeToByteArray().size <= MAX_FILE_BYTES) { "单源代码为空或超过 2 MiB。" }
            require(bundle.origin.length <= 4096 && bundle.sourceId == identity(bundle.metadata) && bundle.digest == digest(bundle.code, bundle.metadata)) { "源包身份或摘要校验失败。" }
        }
        private fun validatePackages(packages: List<ScriptSourcePackage>) {
            require(packages.size <= MAX_INSTALLED && packages.map { it.sourceId }.distinct().size == packages.size) { "源包最多 64 个，且不能存在重复身份。" }
            packages.forEach(::validatePackage)
            require(packages.sumOf { it.code.encodeToByteArray().size.toLong() } <= MAX_TOTAL_CODE_BYTES) { "已安装源代码总量超过 8 MiB。" }
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
        private fun resolveCodeUrl(value: String, origin: String): String = publicPluginUrl(
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
        private fun boundedBytes(input: StorageInput, maximum: Int): ByteArray {
            val output = okio.Buffer()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size + count <= maximum) { "源文件超过容量限制。" }
                output.write(buffer, 0, count)
            }
            return output.readByteArray()
        }
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
