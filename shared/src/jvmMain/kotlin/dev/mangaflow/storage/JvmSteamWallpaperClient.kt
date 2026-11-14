package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.storage.cacheDirectory
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import dev.mangaflow.platform.storage.deleteSecret
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Secrets remain in the platform vault. A native job receives only its own session copy. */
internal class JvmSteamWallpaperClient(
    context: PlatformContext,
    private val prepareNative: suspend (Boolean, (Long, Long) -> Unit) -> Boolean,
    private val cancelPreparation: () -> Unit = {},
) : SteamWallpaperClient {
    private val app = applicationContext(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationLock = Mutex()
    private val generation = AtomicLong()
    private val nativeJob = AtomicLong()
    private val _state = MutableStateFlow(SteamWallpaperState())
    override val state = _state.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var session: JsonObject? = null
    private var operation: Job? = null

    init {
        val token = generation.incrementAndGet()
        operation = scope.launch {
            operationLock.withLock {
                try {
                    if (!prepareNative(false) { _, _ -> }) {
                        update(token) { it.copy(restoring = false,
                            message = "首次使用时下载 Steam 组件，普通阅读不受影响。") }
                        return@withLock
                    }
                    val stored = readSecret(app, SECRET_KEY) ?: run {
                        update(token) { it.copy(restoring = false) }
                        return@withLock
                    }
                    require(stored.length in 1..MAX_EVENT)
                    val saved = sessionRecord(Json.parseToJsonElement(stored) as? JsonObject
                        ?: error("invalid session"))
                    requireCurrent(token)
                    session = saved
                    runNative(token, request("account"), 60_000L) { event ->
                        if (event.text("kind") == "account") publishAccount(token, event, saved)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    update(token) { it.copy(error = "Steam 登录恢复超时，请重试。") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: SteamFailure) {
                    if (failure.code in SESSION_ERRORS) {
                        session = null
                        runCatching { deleteSecret(app, SECRET_KEY) }
                        update(token) { it.copy(account = null, error = "Steam 登录已失效，请重新登录。") }
                    } else update(token) { it.copy(error = failure.userMessage()) }
                } catch (_: Exception) {
                    session = null
                    update(token) { it.copy(error = "已保存的 Steam 登录暂不可用，请重新登录。") }
                } finally {
                    update(token) { it.copy(restoring = false) }
                }
            }
        }
    }

    override fun login() {
        if (closed || !_state.value.supported || _state.value.restoring) return
        start({ it.copy(authenticating = true, qrUrl = null, error = null, message = null) }, 310_000L,
            makeRequest = { request(if (session == null) "login" else "account") }) { token, event ->
            when (event.text("kind")) {
                "qr" -> {
                    val url = steamQrUrl(event.text("url"))
                    update(token) { it.copy(qrUrl = url, message = "请在 Steam 手机应用中确认登录。") }
                }
                "loggedIn", "account" -> publishAccount(token, event, session)
            }
        }
    }

    override fun search(query: String, kind: SteamWallpaperKind, page: Int) {
        if (closed || !_state.value.supported || _state.value.restoring) return
        val keyword = query.trim()
        if (keyword.length > 120 || page !in 1..1000) {
            _state.update { it.copy(error = "搜索词最多 120 字，请使用有效页码。") }
            return
        }
        val previous = _state.value
        val append = page > 1 && previous.query == keyword && previous.kind == kind &&
            page == previous.page + 1 && previous.hasMore
        if (page > 1 && !append) return
        start({ it.copy(loading = true, query = keyword, kind = kind, error = null, message = null,
            items = if (append) it.items else emptyList(), hasMore = if (append) it.hasMore else false) },
            60_000L, makeRequest = {
                request("search") {
                    put("query", keyword); put("media", kind.wire()); put("page", page)
                }
            }) { token, event ->
            if (event.text("kind") == "items") {
                val rows = event["items"] as? JsonArray ?: throw SteamFailure("invalid_response")
                require(rows.size <= 100)
                val items = rows.mapNotNull { parseItem(it as? JsonObject ?: throw SteamFailure("invalid_response"), kind) }
                val actualPage = event.number("page")?.toInt() ?: page
                if (actualPage != page) throw SteamFailure("invalid_response")
                update(token) { current ->
                    val combined = (if (append) current.items + items else items).distinctBy(SteamWallpaperItem::id)
                    current.copy(items = combined.take(500), page = page,
                        hasMore = event.flag("hasMore") == true && combined.size < 500,
                        message = if (items.isEmpty() && !append) {
                            if ((event.number("unsupportedCount") ?: 0) > 0 && kind == SteamWallpaperKind.IMAGE)
                                "此页主要是场景工程，无法直接取得原始图片，可从电脑导出图片后添加。"
                            else "没有找到可直接导入的原始图片或视频项目。"
                        } else null)
                }
            }
        }
    }

    override fun download(item: SteamWallpaperItem) {
        if (closed || !_state.value.supported || _state.value.restoring) return
        if (session == null || _state.value.account == null) {
            _state.update { it.copy(error = "请先登录拥有 Wallpaper Engine 的 Steam 账号。") }
            return
        }
        val selected = _state.value.items.firstOrNull { it.id == item.id && it.kind == item.kind }
        if (selected == null || !validItemId(item.id)) return
        if (item.sizeBytes != null && item.sizeBytes > MAX_MEDIA) {
            _state.update { it.copy(error = "这份壁纸超过 512 MB，请选择较小的原始图片或视频。") }
            return
        }
        start({ it.copy(transferId = selected.id, transferName = selected.title,
            receivedBytes = 0, totalBytes = selected.sizeBytes?.coerceAtLeast(0) ?: 0,
            error = null, message = null) }, 15 * 60_000L,
            makeRequest = { request("download") { put("itemId", selected.id); put("media", selected.kind.wire()) } },
            download = selected) { _, _ -> Unit }
    }

    override fun cancel() {
        stop()
        _state.update { it.copy(restoring = false, preparing = false, componentBytes = 0,
            componentTotalBytes = 0, authenticating = false, qrUrl = null,
            loading = false, transferId = null, transferName = null, receivedBytes = 0,
            totalBytes = 0, message = null) }
    }

    override fun logout() {
        if (closed) return
        stop()
        val token = generation.get()
        val previous = _state.value.account
        val previousSession = session
        session = null
        _state.update { it.copy(restoring = true, preparing = false, componentBytes = 0,
            componentTotalBytes = 0, authenticating = false, qrUrl = null,
            loading = false, account = null, transferId = null, transferName = null,
            receivedBytes = 0, totalBytes = 0, message = null, error = null) }
        operation = scope.launch {
            operationLock.withLock {
                try {
                    requireCurrent(token)
                    deleteSecret(app, SECRET_KEY)
                    update(token) { it.copy(restoring = false, message = "已退出 Steam") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (generation.get() == token) session = previousSession
                    update(token) { it.copy(restoring = false, account = previous,
                        error = "未能移除安全登录凭据，请重试退出。") }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        stop()
        session = null
        scope.cancel()
    }

    private fun start(
        state: (SteamWallpaperState) -> SteamWallpaperState,
        timeout: Long,
        makeRequest: () -> JsonObject,
        download: SteamWallpaperItem? = null,
        onEvent: suspend (Long, JsonObject) -> Unit,
    ) {
        stop()
        val token = generation.get()
        _state.update { state(it.copy(authenticating = false, qrUrl = null, loading = false,
            preparing = false, componentBytes = 0, componentTotalBytes = 0,
            transferId = null, transferName = null, receivedBytes = 0, totalBytes = 0)) }
        operation = scope.launch {
            operationLock.withLock {
                var directory: File? = null
                var media: File? = null
                var primaryFile: String? = null
                try {
                    requireCurrent(token)
                    update(token) { it.copy(preparing = true, componentBytes = 0, componentTotalBytes = 0) }
                    val ready = prepareNative(true) { received, total ->
                        update(token) { it.copy(componentBytes = received.coerceAtLeast(0),
                            componentTotalBytes = total.coerceAtLeast(0)) }
                    }
                    requireCurrent(token)
                    update(token) { it.copy(preparing = false) }
                    if (!ready) throw SteamFailure("component_unavailable")
                    var request = makeRequest()
                    if (download != null) {
                        val root = File(cacheDirectory(app).path, "steam-wallpapers")
                        check(root.isDirectory || root.mkdirs())
                        directory = File(root, randomUuid())
                        check(directory.mkdir())
                        request = buildJsonObject {
                            request.forEach { (key, value) -> put(key, value) }
                            put("directory", checkNotNull(directory).absolutePath); put("maxBytes", MAX_MEDIA)
                        }
                    }
                    runNative(token, request, timeout) { event ->
                        when (event.text("kind")) {
                            "complete" -> {
                                val value = event.text("primaryFile")
                                if (value.isNotBlank()) {
                                    if (download == null || event.text("media") != download.kind.wire())
                                        throw SteamFailure("invalid_response")
                                    primaryFile = value.takeIf { it.length <= 1024 }
                                        ?: throw SteamFailure("invalid_response")
                                }
                            }
                            "progress" -> {
                                val received = event.number("completed") ?: 0L
                                val total = event.number("total") ?: 0L
                                if (received < 0 || total < 0 || received > MAX_MEDIA ||
                                    total > MAX_MEDIA || (total > 0 && received > total)) {
                                    throw SteamFailure("too_large")
                                }
                                update(token) { it.copy(receivedBytes = received, totalBytes = total) }
                            }
                            else -> onEvent(token, event)
                        }
                    }
                    requireCurrent(token)
                    if (download != null) {
                        update(token) { it.copy(message = "正在加入壁纸库…") }
                        media = WallpaperEnginePackage.extractProjectMedia(checkNotNull(directory),
                            download.title, download.kind, primaryFile)
                        requireCurrent(token)
                        currentCoroutineContext().ensureActive()
                        WallpaperStore.get(app).importMedia(checkNotNull(media).toURI().toString(), displayName = download.title)
                        update(token) { it.copy(importedCount = it.importedCount + 1, message = "已加入壁纸库") }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    update(token) { it.copy(error = "Steam 请求超时，请重试。", message = null) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: SteamFailure) {
                    if (failure.code in SESSION_ERRORS) {
                        session = null
                        runCatching { deleteSecret(app, SECRET_KEY) }
                        update(token) { it.copy(account = null) }
                    }
                    update(token) { it.copy(error = failure.userMessage(), message = null) }
                } catch (_: SecurityException) {
                    update(token) { it.copy(error = "无法保存 Steam 登录或壁纸，请检查系统权限。", message = null) }
                } catch (_: Exception) {
                    update(token) { it.copy(error = if (download != null)
                        "这份项目未能作为原始图片或视频加入，请选择视频项目或从电脑导出原媒体。"
                        else "Steam 暂时不可用，请稍后重试。", message = null) }
                } finally {
                    media?.delete()
                    directory?.let { removeDownloadDirectory(it) }
                    update(token) { it.copy(preparing = false, componentBytes = 0, componentTotalBytes = 0,
                        authenticating = false, qrUrl = null, loading = false,
                        transferId = null, transferName = null, receivedBytes = 0, totalBytes = 0) }
                }
            }
        }
    }

    private suspend fun runNative(
        token: Long, request: JsonObject, timeout: Long,
        onEvent: suspend (JsonObject) -> Unit,
    ) = withTimeout(timeout) {
        requireCurrent(token)
        val handle = SteamNative.nativeStart(request.toString())
        if (handle <= 0) throw SteamFailure("unavailable")
        nativeJob.set(handle)
        val expected = when (request.text("op")) {
            "login" -> "loggedIn"
            "account" -> "account"
            "search" -> "items"
            else -> "complete"
        }
        var receivedResult = expected == "complete"
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                requireCurrent(token)
                val raw = SteamNative.nativePoll(handle)
                if (raw == null) { delay(16); continue }
                if (raw.length !in 1..MAX_EVENT) throw SteamFailure("invalid_response")
                val event = Json.parseToJsonElement(raw) as? JsonObject ?: throw SteamFailure("invalid_response")
                requireCurrent(token)
                when (event.text("kind")) {
                    "error" -> throw SteamFailure(event.text("code").takeIf { it in ERROR_CODES } ?: "unavailable")
                    "complete" -> {
                        if (!receivedResult) throw SteamFailure("invalid_response")
                        onEvent(event); break
                    }
                    else -> {
                        if (event.text("kind") == expected) receivedResult = true
                        onEvent(event)
                    }
                }
            }
        } finally {
            nativeJob.compareAndSet(handle, 0)
            runCatching { SteamNative.nativeCancel(handle) }
            runCatching { SteamNative.nativeFree(handle) }
        }
    }

    private fun publishAccount(token: Long, event: JsonObject, previous: JsonObject?) {
        requireCurrent(token)
        val record = sessionRecord(buildJsonObject {
            put("account", event.text("account"))
            put("steamId", event.text("steamId").ifEmpty { previous?.text("steamId").orEmpty() })
            put("refreshToken", event.text("refreshToken").ifEmpty { previous?.text("refreshToken").orEmpty() })
        })
        // operationLock serializes this write with logout; late jobs cannot republish state.
        writeSecret(app, SECRET_KEY, record.toString())
        requireCurrent(token)
        session = record
        update(token) { it.copy(account = SteamWallpaperAccount(record.text("account"), record.text("steamId")),
            authenticating = false, qrUrl = null, message = "Steam 已登录", error = null) }
    }

    private fun sessionRecord(value: JsonObject): JsonObject {
        val account = value.text("account")
        val steamId = value.text("steamId")
        val token = value.text("refreshToken")
        require(account.length in 1..128 && account.none(Char::isISOControl))
        require(steamId.length in 1..20 && steamId.all(Char::isDigit))
        require(token.length in 16..16384 && token.none(Char::isISOControl))
        return buildJsonObject { put("account", account); put("steamId", steamId); put("refreshToken", token) }
    }

    private fun request(op: String, arguments: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("op", op)
            session?.forEach { (key, value) -> put(key, value) }
            arguments()
        }

    private fun parseItem(value: JsonObject, selectedKind: SteamWallpaperKind): SteamWallpaperItem? {
        val kind = when (value.text("media")) {
            "image" -> SteamWallpaperKind.IMAGE
            "video" -> SteamWallpaperKind.VIDEO
            else -> return null
        }
        if (kind != selectedKind) return null
        val id = value.text("id")
        val title = value.text("title").filterNot(Char::isISOControl).trim().take(180)
        if (!validItemId(id) || title.isBlank()) throw SteamFailure("invalid_response")
        val size = value.number("sizeBytes")?.takeIf { it >= 0 }
        return SteamWallpaperItem(id, title, previewUrl(value.text("previewUrl")), kind, size)
    }

    private fun stop() {
        generation.incrementAndGet()
        operation?.cancel()
        operation = null
        runCatching { cancelPreparation() }
        nativeJob.get().takeIf { it > 0 }?.let { runCatching { SteamNative.nativeCancel(it) } }
    }

    private fun update(token: Long, transform: (SteamWallpaperState) -> SteamWallpaperState) {
        _state.update { if (!closed && generation.get() == token) transform(it) else it }
    }

    private fun requireCurrent(token: Long) {
        if (closed || generation.get() != token) throw CancellationException("Steam operation changed")
    }

    private fun steamQrUrl(value: String): String {
        val uri = URI(value)
        require(value.length in 1..4096 && uri.scheme == "https" && uri.host == "s.team" &&
            uri.path.startsWith("/q/") && uri.userInfo == null && uri.port == -1 && uri.fragment == null)
        return value
    }

    private fun previewUrl(value: String): String = runCatching {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase()
        value.takeIf { value.length <= 4096 && uri.scheme == "https" && uri.userInfo == null &&
            (host == "steamcommunity.com" || host.endsWith(".steamusercontent.com") ||
                host == "steamusercontent.com" || host.endsWith(".steamstatic.com") ||
                host.endsWith(".steamuserimages-a.akamaihd.net") ||
                host == "steamuserimages-a.akamaihd.net") }.orEmpty()
    }.getOrDefault("")

    private fun removeDownloadDirectory(directory: File) {
        // No FOLLOW_LINKS: downloaded paths cannot make cleanup traverse another directory.
        runCatching {
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE
                }
            })
        }
    }

    private class SteamFailure(val code: String) : Exception() {
        fun userMessage(): String = when (code) {
            "session_expired", "invalid_token", "not_logged_in", "auth_required" -> "Steam 登录已失效，请重新登录。"
            "ownership_required", "not_owned" -> "请使用拥有 Wallpaper Engine 的 Steam 账号。"
            "cancelled" -> "操作已取消"
            "timeout", "login_timeout" -> "Steam 请求超时，请重试。"
            "too_large", "size_limit" -> "这份壁纸超过 512 MB，请选择较小的项目。"
            "unsupported_media" -> "这份项目不能直接作为原始图片或视频导入。"
            "component_unavailable" -> "Steam 组件未能准备好，请检查网络后重试。"
            "not_found" -> "这份壁纸已不可用，请刷新后重试。"
            "download_failed" -> "壁纸下载未完成，请检查网络后重试。"
            "unsafe_path" -> "这份壁纸的文件路径不安全，已停止导入。"
            "login_failed" -> "Steam 登录未完成，请重新确认。"
            "busy" -> "Steam 仍在结束上一次操作，请稍后重试。"
            "invalid_response" -> "Steam 返回的信息不完整，请重试。"
            else -> "Steam 暂时不可用，请稍后重试。"
        }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun SteamWallpaperKind.wire(): String = if (this == SteamWallpaperKind.VIDEO) "video" else "image"
    private fun validItemId(value: String): Boolean = value.length in 1..20 && value.all(Char::isDigit)

    companion object {
        private const val SECRET_KEY = "wallpaper.steam.session.v1"
        private const val MAX_MEDIA = 512L * 1024 * 1024
        private const val MAX_EVENT = 1024 * 1024
        private val SESSION_ERRORS = setOf("session_expired", "invalid_token", "not_logged_in", "auth_required")
        private val ERROR_CODES = SESSION_ERRORS + setOf("ownership_required", "cancelled", "timeout",
            "too_large", "unsupported_media", "not_found", "invalid_response", "unavailable", "invalid_job",
            "invalid_request", "auth_required", "session_unavailable", "login_failed", "login_timeout",
            "steam_unavailable", "not_owned", "size_limit", "unsafe_path", "download_failed", "busy", "native_failed")
    }
}
