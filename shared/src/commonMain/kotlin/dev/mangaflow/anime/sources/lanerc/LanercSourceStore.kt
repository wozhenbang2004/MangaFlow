package dev.mangaflow.anime.sources.lanerc

import dev.mangaflow.anime.sources.BUNDLED_ANIME_SOURCE_IDS
import dev.mangaflow.anime.sources.BUNDLED_API_ANIME_IDS
import dev.mangaflow.anime.sources.bundledAnimeSourceVersion
import dev.mangaflow.anime.sources.animeHash
import dev.mangaflow.data.plugins.fetchPluginText
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.applicationContext
import dev.mangaflow.platform.storage.filesDir
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.storage.deleteSecret
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One durable, application-owned package index. Browsing this directory never executes scripts. */
class LanercSourceStore private constructor(context: PlatformContext) {
    private val application = context.applicationContext
    private val preparationPreferences = preferences(application, "anime-source-preparation")
    private val file = StorageAtomicFile(StorageFile(application.filesDir, "anime-sources/packages.json"))
    private val writer = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val packages = MutableStateFlow<Map<String, LanercInstalledPackage>>(emptyMap())
    private var builtIns: Map<String, BuiltInChoice> = emptyMap()
    private val packageErrors = mutableMapOf<String, PackageError>()
    private val mutableState = MutableStateFlow(project(emptyMap()))
    val state = mutableState.asStateFlow()
    private var loadFailure: Boolean = false
    internal val runtime = LanercScriptRuntime(application, this)
    val ready: Deferred<Unit> = scope.async {
        writer.withLock {
            try {
                if (file.baseFile.exists() || StorageFile(file.baseFile.path + ".bak").exists()) {
                    val text = file.openRead().use { stream ->
                        val output = mutableListOf<ByteArray>()
                        var total = 0
                        val buffer = ByteArray(16_384)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_STORE_BYTES)
                            output += buffer.copyOf(count)
                        }
                        ByteArray(total).also { bytes ->
                            var offset = 0
                            output.forEach { chunk -> chunk.copyInto(bytes, offset); offset += chunk.size }
                        }.decodeToString()
                    }
                    val root = JSONObject(text)
                    require(root.getInt("version") in 1..3)
                    val choices = root.optJSONArray("builtIns") ?: JSONArray()
                    require(choices.length() <= BUNDLED_ANIME_SOURCE_IDS.size)
                    builtIns = (0 until choices.length()).associate { index ->
                        val item = choices.getJSONObject(index)
                        val id = item.getString("id")
                        require(id in BUNDLED_ANIME_SOURCE_IDS)
                        val revision = item.getString("revision")
                        require(revision.matches(Regex("[A-Za-z0-9-]{1,100}")))
                        id to BuiltInChoice(item.getBoolean("enabled"), item.getBoolean("removed"), revision)
                    }
                    require(builtIns.size == choices.length())
                    val array = root.getJSONArray("packages")
                    require(array.length() <= LANERC_SOURCE_DIRECTORY.size)
                    val restored = (0 until array.length()).map { decode(array.getJSONObject(it)) }
                    require(restored.map { it.id }.distinct().size == restored.size)
                    val migrated = restored.map { installed ->
                        if (installed.optionsKey.isEmpty() && installed.optionsJson != "{}") {
                            val key = randomUuid()
                            writeSecret(application, optionsSecret(installed.id, key), installed.optionsJson)
                            installed.copy(optionsKey = key)
                        } else installed
                    }.associateBy { it.id }
                    // A later application release may add another built-in while storage is
                    // already v3. Freeze the legacy-owner preference before any removal can run.
                    val missingLegacyChoices = BUNDLED_ANIME_SOURCE_IDS.any { id ->
                        id !in builtIns && hasLegacyChoice(id, migrated)
                    }
                    if (root.getInt("version") < 3 || missingLegacyChoices ||
                        restored.any { it.optionsKey.isEmpty() && it.optionsJson != "{}" }) commit(migrated)
                    else { packages.value = migrated; mutableState.value = project(migrated) }
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                loadFailure = true
                mutableState.value = project(emptyMap(), "来源包未能读取，原文件已保留；请检查存储空间后重启。")
            }
        }
    }

    private val automatic = AutomaticAnimeSources(application, this, scope)
    val preparation = automatic.state
    suspend fun retryAutomaticSources() { ready.await(); automatic.retry() }
    suspend fun retryAutomaticSource(id: String) = actionGate(id).withLock {
        ready.await()
        automatic.retry(setOf(id))
    }
    suspend fun restoreAutomaticSource(id: String) = actionGate(id).withLock {
        ready.await()
        automatic.restore(id)
    }

    private val operationGuard = SynchronizedObject()
    private val packageOperations = mutableMapOf<String, Mutex>()
    private val sourceActions = mutableMapOf<String, Mutex>()
    // User removal/restoration spans both the persistent preference and package mutation.
    // Background install jobs never acquire this gate, so removal may cancel/join them safely.
    private fun actionGate(id: String): Mutex = synchronized(operationGuard) {
        sourceActions.getOrPut(id) { Mutex() }
    }
    private fun packageGate(id: String): Mutex = synchronized(operationGuard) {
        packageOperations.getOrPut(id) { Mutex() }
    }
    init { scope.launch { ready.await(); automatic.start() } }

    internal suspend fun installAutomatically(id: String) = packageGate(id).withLock {
        require(id in AUTOMATIC_ANIME_SOURCE_IDS)
        download(id, update = false, initialEnabled = id !in AUTOMATIC_ANIME_INACTIVE_IDS)
    }

    suspend fun homeSections(id: String): List<LanercBrowseSection> = readHomeSections(id)
    suspend fun categories(id: String): List<LanercCategory> = readCategories(id)
    suspend fun searchFiltered(id: String, category: String, filtersJson: String = "{}", page: Int = 1): List<dev.mangaflow.anime.AnimeSourceTitle> =
        readFiltered(id, category, filtersJson, page)
    suspend fun related(id: String, titleId: String): List<dev.mangaflow.anime.AnimeSourceTitle> = readRelated(id, titleId)
    suspend fun danmaku(id: String, payloadJson: String, expectedRevision: String? = null): String {
        require(payloadJson.length <= 65_536)
        JSONObject(payloadJson)
        return runtime.call(id, "danmu", JSONArray(listOf(payloadJson)), expectedRevision).value?.toString() ?: "[]"
    }

    /**
     * Native adapters need no downloads. The packaged automatic manifest is user-authorized;
     * it never executes unverified directory bytes or silently updates an installed revision.
     * A known URL is not an integrity guarantee: pin downloaded bytes before validation executes JS.
     */
    suspend fun install(id: String) = packageGate(id).withLock {
        if (id in BUNDLED_ANIME_SOURCE_IDS) editBuiltIn(id, enabled = true, removed = false)
        else download(id, update = false)
    }
    suspend fun update(id: String) = packageGate(id).withLock {
        check(id !in BUNDLED_ANIME_SOURCE_IDS) { "此来源已内置，随应用版本更新，无需下载脚本。" }
        download(id, update = true)
    }

    suspend fun installAllVideo(): Map<String, String> {
        val failures = linkedMapOf<String, String>()
        for (descriptor in LANERC_SOURCE_DIRECTORY.filter { it.kind == LanercSourceKind.VIDEO }) {
            if (state.value.any { it.id == descriptor.id && it.installed }) continue
            try { install(descriptor.id) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failures[descriptor.id] = "该来源未能安装，可单独重试。" }
        }
        return failures
    }

    private suspend fun download(id: String, update: Boolean, initialEnabled: Boolean = true) {
        ready.await()
        val descriptor = descriptor(id)
        require(installable(descriptor)) { unsupported(descriptor) ?: "此类来源暂不支持。" }
        val expected = writer.withLock {
            if (loadFailure) throw LanercPackagePreparationException(LanercPackageStage.STORAGE,
                "来源存储未能读取，原文件仍保留。请重启应用或恢复来源备份后重试。")
            packages.value[id]
        }
        if (!update && expected != null) return
        if (update) check(expected != null) { "请先安装该来源。" }
        var stage = LanercPackageStage.DOWNLOAD
        try {
            // The existing installer transport keeps its DNS/public-IP, route and redirect guards.
            val code = fetchPluginText(application, descriptor.scriptUrl)
            stage = LanercPackageStage.INTEGRITY
            require(code.encodeToByteArray().size in 1..MAX_CODE_BYTES) { "脚本文件过大或为空。" }
            val digest = animeHash(code)
            check(digest == descriptor.observedDigest) { "来源脚本与内置校验记录不一致。" }
            val version = Regex("(?im)(?:version|版本)\\s*[:=]\\s*['\"]?([A-Za-z0-9._-]{1,48})")
                .find(code)?.groupValues?.get(1) ?: digest.take(12)
            val next = LanercInstalledPackage(id, code, digest, version, expected?.enabled ?: initialEnabled,
                randomUuid(), expected?.optionsJson ?: "{}", expected?.code, expected?.digest, expected?.version,
                optionsKey = expected?.optionsKey.orEmpty())
            stage = LanercPackageStage.ENGINE
            LanercEnginePreparation.awaitReady(application)
            stage = LanercPackageStage.COMPATIBILITY
            runtime.validate(next, descriptor)
            stage = LanercPackageStage.STORAGE
            withContext(Dispatchers.IO) {
                writer.withLock {
                    if (packages.value[id]?.revision != expected?.revision)
                        throw LanercPackagePreparationException(LanercPackageStage.STORAGE,
                            "来源已被其他操作更改，请重新检查此来源。")
                    if (expected?.digest == digest) {
                        packageErrors.remove(id)
                        mutableState.value = project(packages.value)
                        return@withLock
                    }
                    commit(packages.value + (id to next))
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            val message = when (stage) {
                LanercPackageStage.DOWNLOAD -> "${descriptor.name} 脚本下载失败，请检查来源包下载线路。"
                LanercPackageStage.INTEGRITY -> "${descriptor.name} 脚本内容和应用内的校验记录不符，需更新来源记录。"
                LanercPackageStage.ENGINE -> (failure as? LanercCapabilityException)?.message
                    ?: "脚本组件未能准备，请检查组件管理。"
                LanercPackageStage.COMPATIBILITY -> (failure as? LanercCapabilityException)?.message
                    ?: "${descriptor.name} 兼容验证未完成，请检查脚本组件或更新应用。"
                LanercPackageStage.STORAGE -> "${descriptor.name} 保存失败，请检查存储空间。"
            }
            val problem = failure as? LanercPackagePreparationException ?: LanercPackagePreparationException(stage, message)
            writer.withLock {
                // A backup restore or setting change may have already replaced this exact package.
                if (packages.value[id]?.revision == expected?.revision) {
                    packageErrors[id] = PackageError(expected?.revision, problem.message.orEmpty())
                    mutableState.value = project(packages.value)
                }
            }
            throw problem
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        if (id in BUNDLED_ANIME_SOURCE_IDS) editBuiltIn(id, enabled, removed = false)
        else edit(id) { it.copy(enabled = enabled, revision = randomUuid()) }
    }

    private suspend fun editBuiltIn(id: String, enabled: Boolean, removed: Boolean) {
        ready.await()
        withContext(Dispatchers.IO) { writer.withLock {
            check(!loadFailure) { "来源存储未能读取，不能覆盖原文件。" }
            commit(packages.value, builtIns + (id to BuiltInChoice(enabled, removed, randomUuid())))
        } }
    }
    suspend fun setOptions(id: String, optionsJson: String) {
        check(id !in BUNDLED_ANIME_SOURCE_IDS) { "此内建来源仅使用匿名接口，不需要私密参数。" }
        require(optionsJson.encodeToByteArray().size <= 65_536) { "来源参数过大。" }
        val options = JSONObject(optionsJson).toString()
        ready.await()
        withContext(Dispatchers.IO) { writer.withLock {
            check(!loadFailure) { "来源存储未能读取，不能覆盖原文件。" }
            val current = packages.value[id] ?: error("请先安装该来源。")
            val key = randomUuid()
            writeSecret(application, optionsSecret(id, key), options)
            try { commit(packages.value + (id to current.copy(optionsJson = options, optionsKey = key, revision = randomUuid()))) }
            catch (failure: Exception) { if (packages.value[id]?.optionsKey != key) runCatching { deleteSecret(application, optionsSecret(id, key)) }; throw failure }
            if (current.optionsKey.isNotEmpty()) runCatching { deleteSecret(application, optionsSecret(id, current.optionsKey)) }
        } }
    }
    suspend fun rollback(id: String) = edit(id) { installed ->
        check(id !in BUNDLED_ANIME_SOURCE_IDS) { "此来源已内置，随应用版本更新。" }
        val code = installed.previousCode ?: error("该来源没有可回退的版本。")
        val digest = installed.previousDigest ?: error("旧版本记录不完整。")
        check(animeHash(code) == digest) { "旧版本完整性校验失败。" }
        installed.copy(code = code, digest = digest, version = installed.previousVersion ?: digest.take(12),
            revision = randomUuid(), previousCode = installed.code, previousDigest = installed.digest,
            previousVersion = installed.version)
    }
    suspend fun remove(id: String): Unit = actionGate(id).withLock {
        automatic.suppress(id)
        packageGate(id).withLock removePackage@ {
            if (id in BUNDLED_ANIME_SOURCE_IDS) {
                // A durable tombstone prevents automatic registration from undoing the user's removal.
                editBuiltIn(id, enabled = false, removed = true)
                return@removePackage
            }
            ready.await()
            withContext(Dispatchers.IO) { writer.withLock {
                check(!loadFailure) { "来源存储未能读取，不能覆盖原文件。" }
                val old = packages.value[id]
                commit(packages.value - id)
                if (packageErrors.remove(id) != null) mutableState.value = project(packages.value)
                if (old?.optionsKey?.isNotEmpty() == true) runCatching { deleteSecret(application, optionsSecret(id, old.optionsKey)) }
            } }
        }
    }

    /** Private user backup of installed bytes; no Ext, cookies, credentials or session state. */
    suspend fun exportBackup(): JSONArray {
        ready.await()
        return withContext(Dispatchers.IO) { writer.withLock {
            check(!loadFailure) { "来源存储未能读取，不能导出不完整的来源备份。" }
            val installed = packages.value
            val choices = BUNDLED_ANIME_SOURCE_IDS.map { id ->
                val choice = builtIns[id] ?: defaultBuiltInChoice(id, installed)
                JSONObject().put("id", id).put("builtIn", true).put("enabled", choice.enabled)
                    .put("removed", choice.removed)
            }
            JSONArray(installed.values.map { item -> encode(item).also { it.remove("optionsKey") } } + choices)
        } }
    }

    fun validateBackup(value: JSONArray) {
        require(value.length() <= LANERC_SOURCE_DIRECTORY.size && value.toString().encodeToByteArray().size <= MAX_STORE_BYTES) { "来源备份过大。" }
        val items = (0 until value.length()).map { index ->
            val item = value.getJSONObject(index)
            require(!item.has("options") && !item.has("optionsKey")) { "来源备份不能包含私密参数。" }
            if (item.optBoolean("builtIn", false)) {
                require(item.getString("id") in BUNDLED_ANIME_SOURCE_IDS && !item.has("code"))
                item.getBoolean("enabled")
                item.getBoolean("removed")
                item.getString("id")
            } else decode(item, loadPrivateOptions = false).id
        }
        require(items.distinct().size == items.size) { "来源备份包含重复来源。" }
    }

    /** Restore is the user's explicit grant for these known packages; no code is evaluated here. */
    suspend fun restoreBackup(value: JSONArray) {
        val snapshot = JSONArray(value.toString())
        validateBackup(snapshot)
        ready.await()
        withContext(Dispatchers.IO) { writer.withLock {
            val entries = (0 until snapshot.length()).map { snapshot.getJSONObject(it) }
            val next = entries.filterNot { it.optBoolean("builtIn", false) }
                .map { decode(it, loadPrivateOptions = false).copy(revision = randomUuid()) }.associateBy { it.id }
            val restoredChoices = entries.filter { it.optBoolean("builtIn", false) }.associate { item ->
                item.getString("id") to BuiltInChoice(item.getBoolean("enabled"), item.getBoolean("removed"), randomUuid())
            }
            val old = packages.value
            // Legacy backups lack native choices; do not erase this device's explicit choices.
            commit(next, builtIns + restoredChoices)
            loadFailure = false
            old.values.filter { it.optionsKey.isNotEmpty() }.forEach { installed ->
                runCatching { deleteSecret(application, optionsSecret(installed.id, installed.optionsKey)) }
            }
        } }
    }

    private fun optionsSecret(id: String, key: String): String = "anime-lanerc-options:" + animeHash(id + "\n" + key)

    internal fun snapshot(id: String): LanercInstalledPackage? = packages.value[id]?.takeIf { it.enabled && id !in BUNDLED_ANIME_SOURCE_IDS }
    internal fun isCurrent(value: LanercInstalledPackage): Boolean =
        packages.value[value.id]?.let { it.enabled && it.revision == value.revision && it.digest == value.digest } == true
    internal fun descriptor(id: String): LanercSourceDescriptor =
        LANERC_SOURCE_DIRECTORY.firstOrNull { it.id == id } ?: error("此来源不在已知目录中。")

    private suspend fun edit(id: String, transform: (LanercInstalledPackage) -> LanercInstalledPackage) {
        ready.await()
        withContext(Dispatchers.IO) { writer.withLock {
            check(!loadFailure) { "来源存储未能读取，不能覆盖原文件。" }
            val current = packages.value[id] ?: error("请先安装该来源。")
            commit(packages.value + (id to transform(current)))
        } }
    }

    private suspend fun commit(next: Map<String, LanercInstalledPackage>, choices: Map<String, BuiltInChoice> = builtIns) = withContext(NonCancellable) {
        // Persist the legacy-owner preference, so subsequently deleting that old source cannot
        // silently activate a different adapter or change playback identity.
        val effectiveChoices = BUNDLED_ANIME_SOURCE_IDS.filter { id ->
            id !in choices && hasLegacyChoice(id, next)
        }.associateWith { defaultBuiltInChoice(it, next).copy(revision = randomUuid()) } + choices
        val bytes = JSONObject().put("version", 3).put("packages", JSONArray(next.values.map(::encode)))
            .put("builtIns", JSONArray(effectiveChoices.map { (id, choice) -> JSONObject().put("id", id)
                .put("enabled", choice.enabled).put("removed", choice.removed).put("revision", choice.revision) }))
            .toString().encodeToByteArray()
        require(bytes.size <= MAX_STORE_BYTES) { "来源包总容量超出安全存储范围。" }
        check(file.baseFile.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建来源目录。" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
        val previous = packages.value
        packages.value = next
        builtIns = effectiveChoices
        packageErrors.entries.removeAll { (id, error) -> error.revision != next[id]?.revision }
        mutableState.value = project(next)
        previous.values.filter { next[it.id]?.revision != it.revision }.forEach { runtime.invalidate(it.id) }
    }

    private fun encode(value: LanercInstalledPackage): JSONObject = JSONObject()
        .put("id", value.id).put("code", value.code).put("digest", value.digest).put("version", value.version)
        .put("enabled", value.enabled).put("revision", value.revision).put("optionsKey", value.optionsKey)
        .put("previousCode", value.previousCode ?: JSONObject.NULL)
        .put("previousDigest", value.previousDigest ?: JSONObject.NULL)
        .put("previousVersion", value.previousVersion ?: JSONObject.NULL)

    private fun decode(value: JSONObject, loadPrivateOptions: Boolean = true): LanercInstalledPackage {
        val id = value.getString("id")
        require(id !in BUNDLED_ANIME_SOURCE_IDS && installable(descriptor(id)))
        val code = value.getString("code")
        val digest = value.getString("digest")
        require(code.encodeToByteArray().size in 1..MAX_CODE_BYTES && animeHash(code) == digest)
        val optionsKey = if (loadPrivateOptions) value.optString("optionsKey") else ""
        require(optionsKey.isEmpty() || optionsKey.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val options = if (!loadPrivateOptions) "{}" else if (optionsKey.isNotEmpty())
            readSecret(application, optionsSecret(id, optionsKey)) ?: error("来源私密参数暂时无法读取。")
            else value.optString("options", "{}")
        require(options.encodeToByteArray().size <= 65_536)
        JSONObject(options)
        require(value.getString("version").matches(Regex("[A-Za-z0-9._-]{1,100}")) &&
            value.getString("revision").matches(Regex("[A-Za-z0-9-]{1,100}")))
        val previous = if (value.isNull("previousCode")) null else value.getString("previousCode")
        val previousDigest = if (value.isNull("previousDigest")) null else value.getString("previousDigest")
        require(previous == null || previous.encodeToByteArray().size in 1..MAX_CODE_BYTES && animeHash(previous) == previousDigest)
        require(previous != null || previousDigest == null && value.isNull("previousVersion"))
        if (previous != null) require(value.getString("previousVersion").matches(Regex("[A-Za-z0-9._-]{1,100}")))
        return LanercInstalledPackage(id, code, digest, value.getString("version"), value.getBoolean("enabled"),
            value.getString("revision"), options, previous, previousDigest,
            if (value.isNull("previousVersion")) null else value.getString("previousVersion"), optionsKey)
    }

    private fun project(packages: Map<String, LanercInstalledPackage>, loadError: String? = null) = LANERC_SOURCE_DIRECTORY.map { item ->
            val installed = packages[item.id]
            if (item.id in BUNDLED_ANIME_SOURCE_IDS) {
                val choice = builtIns[item.id] ?: defaultBuiltInChoice(item.id, packages)
                val version = bundledAnimeSourceVersion(item.id)
                return@map LanercSourceListing(item.id, item.name, item.kind, item.scriptUrl,
                    installed = !choice.removed, enabled = loadError == null && !choice.removed && choice.enabled,
                    version = version,
                    error = loadError, aliases = item.aliases, capabilities = item.functions,
                    revision = version + "-" + choice.revision)
            }
            LanercSourceListing(item.id, item.name, item.kind, item.scriptUrl,
                installed != null, installed?.enabled == true, installed?.version.orEmpty(), installed?.digest.orEmpty(),
                installed?.previousVersion, loadError ?: packageErrors[item.id]?.message ?: unsupported(item), item.aliases, item.functions,
                "{}", installable(item), installed?.revision, installed?.optionsJson?.let { it != "{}" } == true)
        }.map { listing ->
            listing.copy(
                automaticPreparation = listing.id in AUTOMATIC_ANIME_SOURCE_IDS,
                bundledAlternativeId = ANIME_BUNDLED_ALTERNATIVES[listing.id],
                preparationUnavailableReason = when (listing.id) {
                    "anime-lanerc-lmm85" -> "已接入公开直链；需要网页验证或媒体嗅探的线路仍不可用。"
                    else -> null
                },
            )
        }

    private fun hasLegacyChoice(id: String, packages: Map<String, LanercInstalledPackage>): Boolean {
        val legacy = id.replace("anime-native-", "anime-lanerc-")
        return packages[legacy] != null || preparationPreferences.getBoolean("removed:$legacy")
    }

    private fun defaultBuiltInChoice(id: String, packages: Map<String, LanercInstalledPackage>): BuiltInChoice {
        val legacy = id.replace("anime-native-", "anime-lanerc-")
        val removed = preparationPreferences.getBoolean("removed:$legacy")
        val installed = packages[legacy]
        // New API adapters may coexist with an enabled legacy script. Saved titles keep their
        // old source IDs; explicit legacy disable/removal is carried forward, never undone.
        val enabled = !removed && if (id in BUNDLED_API_ANIME_IDS) installed?.enabled != false else installed == null
        return BuiltInChoice(enabled, removed, "bundled")
    }

    private data class BuiltInChoice(val enabled: Boolean, val removed: Boolean, val revision: String)
    private data class PackageError(val revision: String?, val message: String)

    companion object {
        private const val MAX_CODE_BYTES = 5 * 1024 * 1024
        private const val MAX_STORE_BYTES = 64 * 1024 * 1024
        private fun installable(descriptor: LanercSourceDescriptor) = descriptor.kind in
            setOf(LanercSourceKind.VIDEO, LanercSourceKind.BROWSE, LanercSourceKind.DANMAKU)
        private fun unsupported(descriptor: LanercSourceDescriptor): String? =
            lanercAccessNote(descriptor.id) ?: if (!installable(descriptor)) "正在核对来源的接入方式。" else null


        private val lock = SynchronizedObject()
        private val instances = mutableMapOf<String, LanercSourceStore>()
        fun get(context: PlatformContext): LanercSourceStore = synchronized(lock) {
            instances.getOrPut(context.applicationContext.filesDir.path) { LanercSourceStore(context) }
        }
    }
}

internal enum class LanercPackageStage { DOWNLOAD, INTEGRITY, ENGINE, COMPATIBILITY, STORAGE }

internal class LanercPackagePreparationException(val stage: LanercPackageStage, message: String) : IllegalStateException(message)
