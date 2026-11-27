package dev.mangaflow.update

import dev.mangaflow.net.writeRelayBinding
import dev.mangaflow.net.readRelayBinding

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadProxyEndpoint
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.checked
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.Request
import dev.mangaflow.net.HttpUrl.Companion.toHttpUrl
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Desktop installs verified native assets; iOS hands updates to its signed distribution channel. */
actual class UpdateManager private constructor(private val context: PlatformContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var activeClient: NetworkClient? = null
    private val prefs = preferences(context, "app_updates")
    private val root = StorageFile(cacheDirectory(context), "updates")
    private val previousInstallMessage = previousNativeUpdateResult(context)
    val hasPreviousInstallResult: Boolean get() = previousInstallMessage != null
    private val _state = MutableStateFlow(UpdateState(lastChecked = prefs.getLong("last_check", 0),
        message = previousInstallMessage ?: "检查漫流的新版本"))
    actual val state: StateFlow<UpdateState> = _state.asStateFlow()
    actual val automatic: Boolean get() = prefs.getBoolean("automatic", true)
    private var job: Job? = null
    private var started = false
    private var incrementalPlan: NativeIncrementalPlan? = null
    private var retainedRoute: DownloadRouteSnapshot? = null
    private val checkpoint = StorageFile(StorageFile(dataDirectory(context), "application-transfers"), "native-update.json")

    actual fun start() {
        if (started) return
        started = true
        job = scope.launch {
            val retained = try { withContext(Dispatchers.IO) { readCheckpoint() } }
                catch (_: Exception) {
                    _state.value = _state.value.copy(phase = UpdatePhase.ERROR, message = "更新下载计划未能恢复，请重新检查更新")
                    job = null
                    return@launch
                }
            if (retained != null) {
                retainedRoute = retained.route
                val file = releaseAssetSuffixes().firstOrNull { retained.release.url.substringBefore('?').endsWith(it, true) }
                    ?.let { StorageFile(root, retained.release.sha256 + it) }
                _state.value = _state.value.copy(phase = UpdatePhase.VERIFYING, message = "正在恢复更新任务…")
                val complete = withContext(Dispatchers.IO) {
                    file != null && file.isFile && file.length() == retained.release.bytes && sha256File(file.path) == retained.release.sha256
                }
                _state.value = UpdateState(if (complete) UpdatePhase.READY else UpdatePhase.AVAILABLE, retained.release,
                    downloaded = if (complete) retained.release.bytes else 0L, total = retained.release.bytes,
                    message = previousInstallMessage ?: if (complete) "安装包已下载，可继续安装"
                    else "已恢复下载计划；未完成文件将在继续时重新下载")
                job = null
                if (!complete && retained.status in setOf("queued", "running", "verifying")) download(retained.route)
            } else {
                job = null
                if (automatic && wallTimeMillis() - prefs.getLong("last_check", 0) >= 6L * 60 * 60 * 1_000) check(false)
            }
        }
    }

    actual fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        if (enabled) check(false)
    }

    actual fun check(manual: Boolean, onComplete: (() -> Unit)?) {
        if ((!manual && !automatic) || job?.isActive == true) { onComplete?.invoke(); return }
        if (platformKind == PlatformKind.IOS) {
            val channel = platformUpdateChannel(context)
            val opened = manual && channel != null && openPlatformUrl(context, channel)
            _state.value = UpdateState(
                phase = if (channel == null) UpdatePhase.ERROR else UpdatePhase.IDLE,
                message = when {
                    channel == null -> "此 iOS 构建未配置签名分发渠道，请通过原安装渠道更新"
                    opened -> "已打开签名分发渠道"
                    manual -> "无法打开签名分发渠道"
                    else -> "通过原签名分发渠道更新"
                },
            )
            onComplete?.invoke()
            return
        }
        val before = _state.value
        _state.value = before.copy(phase = UpdatePhase.CHECKING, message = "正在检查新版本…")
        job = scope.launch {
            var taskClient: NetworkClient? = null
            try {
                val route = DownloadRoutingStore.get(context).snapshot(DownloadCategory.UPDATES)
                val client = NetworkClient(callTimeoutMillis = 20L * 60 * 1_000, route = route)
                taskClient = client
                activeClient = client
                var releaseMetadata = ""
                val release = withContext(Dispatchers.IO) {
                    val suffixes = releaseAssetSuffixes()
                    val latest = JSONObject(fetchReleaseMetadata(client, MANIFEST_URL))
                    val selected = windowsInstaller(latest, suffixes)?.let { latest to it } ?: run {
                        // Android publishes first; Windows assets may still be building for that tag.
                        val recent = JSONArray(fetchReleaseMetadata(client,
                            "https://api.github.com/repos/$REPOSITORY/releases?per_page=8"))
                        (0 until recent.length()).asSequence().mapNotNull { recent.optJSONObject(it) }
                            .filter { !it.optBoolean("draft") && !it.optBoolean("prerelease") }
                            .mapNotNull { json -> windowsInstaller(json, suffixes)?.let { json to it } }
                            .maxByOrNull { (json, _) -> versionOrder(json.optString("tag_name").removePrefix("v")) }
                            ?: error("近期版本暂未提供 Windows 安装包，请稍后再检查")
                    }
                    val (json, asset) = selected
                    releaseMetadata = json.toString()
                    val version = json.optString("tag_name").removePrefix("v")
                    val code = versionOrder(version)
                    val notes = json.optString("body")
                    val commit = json.optString("target_commitish")
                    val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
                    AppRelease(code, version, notes, checkedUrl(asset.optString("browser_download_url")),
                        digest, asset.optLong("size"), 0, commit)
                }
                retainedRoute = null
                val now = wallTimeMillis()
                prefs.edit().putLong("last_check", now).apply()
                _state.value = if (release.versionCode <= versionOrder(platformVersion(context))) {
                    incrementalPlan = null
                    UpdateState(UpdatePhase.CURRENT, message = "已是最新版本", lastChecked = now)
                } else {
                    incrementalPlan = try {
                        planNativeIncrementalUpdate(context, release, releaseMetadata, route)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (failure: DownloadRouteException) { throw failure
                    } catch (_: Exception) { null }
                    val delta = incrementalPlan
                    UpdateState(UpdatePhase.AVAILABLE, release, total = delta?.bytes ?: release.bytes,
                        message = if (delta != null) "发现新版本 ${release.versionName} · 跨版本增量（只下载变动文件）" else "发现新版本 ${release.versionName}", lastChecked = now)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                _state.value = before.copy(phase = if (before.release != null) UpdatePhase.AVAILABLE else UpdatePhase.ERROR,
                    message = failure.message ?: "检查失败，请稍后重试")
            } finally {
                if (activeClient === taskClient) activeClient = null
                taskClient?.close()
                onComplete?.invoke()
            }
        }
    }

    private suspend fun fetchReleaseMetadata(client: NetworkClient, url: String): String {
        val response = client.execute(Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json").header("User-Agent", "MangaFlow").build(),
            4L * 1024 * 1024)
        require(response.isSuccessful) { "发布服务暂不可用（HTTP ${response.code}）" }
        return response.text()
    }

    private fun windowsInstaller(release: JSONObject, suffixes: List<String>): JSONObject? {
        val tag = release.optString("tag_name")
        if (!tag.matches(Regex("v[0-9]+[.][0-9]+[.][0-9]+"))) return null
        val assets = release.optJSONArray("assets") ?: return null
        val candidates = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
        return suffixes.firstNotNullOfOrNull { suffix ->
            val name = "MangaFlow-${tag.removePrefix("v")}$suffix"
            candidates.firstOrNull { asset ->
                asset.optString("name") == name && asset.optLong("size") in 1..MAX_INSTALLER_BYTES &&
                    asset.optString("digest").matches(Regex("sha256:[a-fA-F0-9]{64}")) &&
                    asset.optString("browser_download_url") == "https://github.com/$REPOSITORY/releases/download/$tag/$name"
            }
        }
    }

    actual fun download() {
        val route = try { retainedRoute ?: DownloadRoutingStore.get(context).snapshot(DownloadCategory.UPDATES) }
        catch (failure: DownloadRouteException) { fail(failure.message ?: "下载线路不可用"); return }
        download(route)
    }

    private fun download(route: DownloadRouteSnapshot) {
        val release = _state.value.release ?: return
        if (job?.isActive == true) return
        if (platformKind == PlatformKind.IOS) { install(context); return }
        val delta = incrementalPlan
        if (delta != null) {
            _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0,
                total = delta.bytes, message = "正在下载变动文件…")
            job = scope.launch {
                try {
                    downloadNativeIncrementalUpdate(context, delta, route) { received, total ->
                        _state.value = _state.value.copy(downloaded = received, total = total)
                    }
                    _state.value = _state.value.copy(phase = UpdatePhase.READY, downloaded = delta.bytes,
                        total = delta.bytes, message = "增量更新已就绪")
                } catch (cancelled: CancellationException) {
                    _state.value = _state.value.copy(phase = UpdatePhase.AVAILABLE, message = "下载已取消")
                    throw cancelled
                } catch (failure: DownloadRouteException) {
                    _state.value = _state.value.copy(phase = UpdatePhase.AVAILABLE,
                        message = failure.message ?: "下载线路不可用")
                } catch (_: Exception) {
                    incrementalPlan = null
                    _state.value = _state.value.copy(phase = UpdatePhase.AVAILABLE, total = release.bytes,
                        message = "增量更新不可用，正在改用完整安装包…")
                    job = null
                    download(route)
                }
            }
            return
        }
        _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0,
            total = release.bytes, message = "正在下载安装包…")
        job = scope.launch {
            var taskClient: NetworkClient? = null
            val suffix = releaseAssetSuffixes().firstOrNull { release.url.substringBefore('?').endsWith(it, true) }
                ?: return@launch fail("安装包类型与当前系统不匹配")
            val target = StorageFile(root, release.sha256 + suffix)
            val incoming = StorageFile(target.path + ".part")
            try {
                val client = NetworkClient(callTimeoutMillis = 20L * 60 * 1_000, route = route)
                taskClient = client
                activeClient = client
                withContext(Dispatchers.IO) {
                    check(root.isDirectory || root.mkdirs()) { "无法创建更新目录" }
                    writeCheckpoint(release, route, "running")
                    retainedRoute = route
                    if (target.isFile && target.length() == release.bytes && sha256File(target.path) == release.sha256) {
                        writeCheckpoint(release, route, "completed")
                        return@withContext
                    }
                    // Without a trustworthy retained response validator, never append a partial installer.
                    // The user intent/route survives; incomplete bytes are explicitly restarted.
                    val url = resolveAssetUrl(release.url, client)
                    incoming.outputStream().use { output ->
                        val response = client.download(Request.Builder().url(url).build(), release.bytes,
                            onChunk = { bytes, size -> output.write(bytes, 0, size) },
                            onProgress = { received, _ -> _state.value = _state.value.copy(downloaded = received, total = release.bytes) })
                        require(response.code == 200) { "安装包下载失败（HTTP ${response.code}）" }
                    }
                    writeCheckpoint(release, route, "verifying")
                    _state.value = _state.value.copy(phase = UpdatePhase.VERIFYING, message = "正在核对安装包…")
                    require(incoming.length() == release.bytes && sha256File(incoming.path) == release.sha256) {
                        "安装包校验不一致，请重新下载"
                    }
                    check(incoming.renameTo(target)) { "无法保存安装包" }
                    writeCheckpoint(release, route, "completed")
                }
                _state.value = _state.value.copy(phase = UpdatePhase.READY, downloaded = release.bytes,
                    total = release.bytes, message = "安装包已下载")
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { writeCheckpoint(release, route, "paused") }.onFailure { checkpoint.delete() }
                }
                _state.value = _state.value.copy(phase = UpdatePhase.AVAILABLE, message = "下载已暂停；继续时重新下载未完成文件")
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.IO) {
                    runCatching { writeCheckpoint(release, route, "failed") }.onFailure { checkpoint.delete() }
                }
                fail((failure.message ?: "下载失败") + "；下载计划已保留，重试将重新下载未完成文件")
            } finally {
                if (activeClient === taskClient) activeClient = null
                taskClient?.close()
            }
        }
    }

    actual fun cancelDownload() {
        if (_state.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) { job?.cancel(); activeClient?.cancelAll() }
    }

    actual fun install(context: PlatformContext) {
        val release = _state.value.release ?: return
        if (platformKind == PlatformKind.IOS) {
            _state.value = _state.value.copy(message = if (openPlatformUrl(context, release.url))
                "请通过已签名的分发渠道完成更新" else "无法打开分发页面")
            return
        }
        if (_state.value.phase != UpdatePhase.READY || job?.isActive == true) return
        _state.value = _state.value.copy(phase = UpdatePhase.VERIFYING, message = "正在保存并准备安装…")
        job = scope.launch {
            try {
                val delta = incrementalPlan
                val launched = withContext(Dispatchers.IO) {
                    if (delta != null) {
                        installNativeIncrementalUpdate(context, delta)
                    } else {
                        val suffix = releaseAssetSuffixes().firstOrNull { release.url.substringBefore('?').endsWith(it, true) }
                            ?: error("安装包类型不匹配")
                        val file = StorageFile(root, release.sha256 + suffix)
                        require(file.length() == release.bytes && sha256File(file.path) == release.sha256) { "安装包校验失败，请重新下载" }
                        openDownloadedInstaller(context, file.path)
                    }
                }
                _state.value = if (launched) {
                    _state.value.copy(message = "正在保存并关闭应用，随后安装更新")
                } else {
                    _state.value.copy(phase = UpdatePhase.READY, message = "安装助手未能启动。请重试，或从发布页手动安装。")
                }
            } catch (failure: Exception) { fail(failure.message ?: "无法继续安装") }
        }
    }

    private data class RetainedUpdate(val release: AppRelease, val route: DownloadRouteSnapshot, val status: String)

    private fun writeCheckpoint(release: AppRelease, route: DownloadRouteSnapshot, status: String) {
        check(checkpoint.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        val proxy = route.proxy
        val json = JSONObject().put("schema", 1).put("status", status)
            .put("code", release.versionCode).put("version", release.versionName).put("notes", release.notes.take(6000))
            .put("url", release.url).put("sha256", release.sha256).put("bytes", release.bytes)
            .put("sourceCommit", release.sourceCommit).put("route", route.mode.name)
        route.writeRelayBinding(json)
        if (proxy != null) json.put("proxy", JSONObject().put("host", proxy.host).put("port", proxy.port).put("type", proxy.type.name))
        val atomic = StorageAtomicFile(checkpoint)
        val output = atomic.startWrite()
        try { output.write(json.toString().encodeToByteArray()); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    private fun readCheckpoint(): RetainedUpdate? {
        if (platformKind == PlatformKind.IOS || !checkpoint.isFile) return null
        require(checkpoint.length() in 1..65_536L) { "更新任务记录无效" }
        val json = JSONObject(checkpoint.readText())
        require(json.getInt("schema") == 1)
        val version = json.getString("version")
        val code = json.getLong("code")
        require(version.length in 1..80 && code == versionOrder(version))
        if (code <= versionOrder(platformVersion(context))) { checkpoint.delete(); return null }
        val hash = json.getString("sha256")
        val bytes = json.getLong("bytes")
        require(hash.matches(Regex("[a-f0-9]{64}")) && bytes in 1..MAX_INSTALLER_BYTES)
        val url = checkedUrl(json.getString("url"))
        require(releaseAssetSuffixes().any { url.substringBefore('?').endsWith(it, true) })
        val proxy = json.optJSONObject("proxy")?.let {
            DownloadProxyEndpoint(it.getString("host"), it.getInt("port"), DownloadProxyType.valueOf(it.getString("type")))
        }
        val route = DownloadRouteSnapshot(DownloadRouteMode.valueOf(json.getString("route")), proxy, readRelayBinding(json)).checked()
        val status = json.getString("status")
        require(status in setOf("queued", "running", "verifying", "paused", "failed", "completed"))
        return RetainedUpdate(AppRelease(code, version, json.optString("notes"), url, hash, bytes, 0,
            json.optString("sourceCommit")), route, status)
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(phase = if (_state.value.release == null) UpdatePhase.ERROR else UpdatePhase.AVAILABLE,
            message = message)
    }

    private suspend fun resolveAssetUrl(initial: String, client: NetworkClient): String {
        var url = checkedUrl(initial)
        repeat(6) {
            val response = client.execute(Request.Builder().url(url).method("HEAD", null).build(), MAX_INSTALLER_BYTES)
            if (response.code !in setOf(301, 302, 303, 307, 308)) {
                require(response.isSuccessful) { "安装包服务暂不可用（HTTP ${response.code}）" }
                return url
            }
            val next = response.header("Location") ?: error("发布服务未提供下载地址")
            url = checkedUrl(url.toHttpUrl().resolve(next)?.toString() ?: error("下载地址无效"))
        }
        error("发布服务重定向过多")
    }

    actual companion object {
        actual const val REPOSITORY = "wozhenbang2004/MangaFlow"
        actual const val MANIFEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        private const val MAX_INSTALLER_BYTES = 2L * 1024 * 1024 * 1024
        private val instances = ConcurrentMap<PlatformContext, UpdateManager>()
        actual fun get(context: PlatformContext): UpdateManager = instances.computeIfAbsent(applicationContext(context)) { UpdateManager(it) }
        private fun checkedUrl(value: String): String {
            val url = value.toHttpUrl()
            require(url.scheme == "https" && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
                url.fragment == null && url.host in setOf("github.com", "api.github.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com", "github-releases.githubusercontent.com")) { "发布服务返回了非官方的下载地址" }
            return url.toString()
        }
        private fun versionOrder(value: String): Long = (value.substringBefore('-').split('.') + listOf("0", "0", "0")).take(3)
            .fold(0L) { total, part -> total * 1_000_000 + (part.toLongOrNull() ?: 0).coerceIn(0, 999_999) }
    }
}
