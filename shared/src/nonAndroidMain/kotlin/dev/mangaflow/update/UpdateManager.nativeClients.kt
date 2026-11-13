package dev.mangaflow.update

import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.NetworkClient
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
    private val client = NetworkClient(callTimeoutMillis = 20L * 60 * 1_000)
    private val prefs = preferences(context, "app_updates")
    private val root = StorageFile(cacheDirectory(context), "updates")
    private val _state = MutableStateFlow(UpdateState(lastChecked = prefs.getLong("last_check", 0)))
    actual val state: StateFlow<UpdateState> = _state.asStateFlow()
    actual val automatic: Boolean get() = prefs.getBoolean("automatic", true)
    private var job: Job? = null
    private var started = false

    actual fun start() {
        if (started) return
        started = true
        if (automatic && wallTimeMillis() - prefs.getLong("last_check", 0) >= 6L * 60 * 60 * 1_000) check(false)
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
            try {
                val release = withContext(Dispatchers.IO) {
                    val response = client.execute(Request.Builder()
                        .url("https://api.github.com/repos/$REPOSITORY/releases/latest")
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "MangaFlow").build(), 512L * 1024)
                    require(response.isSuccessful) { "发布服务暂不可用（HTTP ${response.code}）" }
                    val json = JSONObject(response.text())
                    val version = json.optString("tag_name").removePrefix("v")
                    val code = versionOrder(version)
                    val notes = json.optString("body")
                    val commit = json.optString("target_commitish")
                    if (platformKind == PlatformKind.IOS) {
                        AppRelease(code, version, notes, checkedUrl(json.optString("html_url")), "", 0, 0, commit)
                    } else {
                        val assets = json.optJSONArray("assets") ?: error("发布信息没有安装包")
                        val suffixes = releaseAssetSuffixes()
                        val candidates = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                        val asset = suffixes.firstNotNullOfOrNull { suffix ->
                            candidates.firstOrNull { it.optString("name").endsWith(suffix, ignoreCase = true) }
                        } ?: error("此版本尚未提供当前系统的安装包，可在项目发布页查看")
                        val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
                        require(Regex("[a-f0-9]{64}").matches(digest)) { "安装包缺少可信的 SHA-256 发布摘要" }
                        val bytes = asset.optLong("size")
                        require(bytes in 1..MAX_INSTALLER_BYTES) { "安装包大小无效" }
                        AppRelease(code, version, notes, checkedUrl(asset.optString("browser_download_url")), digest, bytes, 0, commit)
                    }
                }
                val now = wallTimeMillis()
                prefs.edit().putLong("last_check", now).apply()
                _state.value = if (release.versionCode <= versionOrder(platformVersion(context))) {
                    UpdateState(UpdatePhase.CURRENT, message = "已是最新版本", lastChecked = now)
                } else UpdateState(UpdatePhase.AVAILABLE, release, total = release.bytes,
                    message = "发现新版本 ${release.versionName}", lastChecked = now)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                _state.value = before.copy(phase = if (before.release != null) UpdatePhase.AVAILABLE else UpdatePhase.ERROR,
                    message = failure.message ?: "检查失败，请稍后重试")
            } finally { onComplete?.invoke() }
        }
    }

    actual fun download() {
        val release = _state.value.release ?: return
        if (job?.isActive == true) return
        if (platformKind == PlatformKind.IOS) { install(context); return }
        _state.value = _state.value.copy(phase = UpdatePhase.DOWNLOADING, downloaded = 0, message = "正在下载安装包…")
        job = scope.launch {
            val suffix = releaseAssetSuffixes().firstOrNull { release.url.substringBefore('?').endsWith(it, true) }
                ?: return@launch fail("安装包类型与当前系统不匹配")
            val target = StorageFile(root, release.sha256 + suffix)
            val incoming = StorageFile(target.path + ".part")
            try {
                withContext(Dispatchers.IO) {
                    check(root.isDirectory || root.mkdirs()) { "无法创建更新目录" }
                    val url = resolveAssetUrl(release.url)
                    incoming.outputStream().use { output ->
                        val response = client.download(Request.Builder().url(url).build(), release.bytes,
                            onChunk = { bytes, size -> output.write(bytes, 0, size) },
                            onProgress = { received, _ -> _state.value = _state.value.copy(downloaded = received, total = release.bytes) })
                        require(response.code == 200) { "安装包下载失败（HTTP ${response.code}）" }
                    }
                    _state.value = _state.value.copy(phase = UpdatePhase.VERIFYING, message = "正在核对安装包…")
                    require(incoming.length() == release.bytes && sha256File(incoming.path) == release.sha256) {
                        "安装包校验不一致，请重新下载"
                    }
                    check(incoming.renameTo(target)) { "无法保存安装包" }
                }
                _state.value = _state.value.copy(phase = UpdatePhase.READY, downloaded = release.bytes,
                    total = release.bytes, message = "安装包已下载")
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) { incoming.delete() }
                _state.value = _state.value.copy(phase = UpdatePhase.AVAILABLE, message = "下载已取消")
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.IO) { incoming.delete() }
                fail(failure.message ?: "下载失败，请重试")
            }
        }
    }

    actual fun cancelDownload() {
        if (_state.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) { job?.cancel(); client.cancelAll() }
    }

    actual fun install(context: PlatformContext) {
        val release = _state.value.release ?: return
        if (platformKind == PlatformKind.IOS) {
            _state.value = _state.value.copy(message = if (openPlatformUrl(context, release.url))
                "请通过已签名的分发渠道完成更新" else "无法打开分发页面")
            return
        }
        if (_state.value.phase != UpdatePhase.READY || job?.isActive == true) return
        job = scope.launch {
            try {
                val suffix = releaseAssetSuffixes().firstOrNull { release.url.substringBefore('?').endsWith(it, true) }
                    ?: error("安装包类型不匹配")
                val file = StorageFile(root, release.sha256 + suffix)
                withContext(Dispatchers.IO) {
                    require(file.length() == release.bytes && sha256File(file.path) == release.sha256) { "安装包校验失败，请重新下载" }
                }
                check(openDownloadedInstaller(context, file.path)) { "无法打开系统安装程序，请前往发布页下载安装" }
                _state.value = _state.value.copy(message = "请在系统界面完成安装")
            } catch (failure: Exception) { fail(failure.message ?: "无法继续安装") }
        }
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(phase = if (_state.value.release == null) UpdatePhase.ERROR else UpdatePhase.AVAILABLE,
            message = message)
    }

    private suspend fun resolveAssetUrl(initial: String): String {
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
            require(url.scheme == "https" && url.host in setOf("github.com", "api.github.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com", "github-releases.githubusercontent.com")) { "发布服务返回了非官方的下载地址" }
            return url.toString()
        }
        private fun versionOrder(value: String): Long = (value.substringBefore('-').split('.') + listOf("0", "0", "0")).take(3)
            .fold(0L) { total, part -> total * 1_000_000 + (part.toLongOrNull() ?: 0).coerceIn(0, 999_999) }
    }
}
