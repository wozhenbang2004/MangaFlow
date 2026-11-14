package dev.mangaflow.storage

import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRoutingStore
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
import java.util.concurrent.atomic.AtomicReference
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

/** Each lane owns its native job; authenticated state is committed only through the vault lock. */
internal class JvmSteamWallpaperClient(
    context: PlatformContext,
    private val prepareNative: suspend (Boolean, (Long, Long) -> Unit) -> Boolean,
    private val cancelPreparation: () -> Unit = {},
) : SteamWallpaperClient {
    private enum class LaneKind { AUTH, BROWSE, TRANSFER, PROFILE }
    private class Lane(val kind: LaneKind) {
        val generation = AtomicLong()
        val nativeJob = AtomicLong()
        @Volatile var operation: Job? = null
    }
    private data class Preparation(val lane: Lane, val token: Long)
    private data class SessionLease(val record: JsonObject, val epoch: Long)
    private data class BrowseKey(val library: SteamWallpaperLibrary, val query: String,
        val kind: SteamWallpaperKind, val steamId: String)
    private data class BrowseSnapshot(val items: List<SteamWallpaperItem>, val page: Int,
        val hasMore: Boolean, val message: String?, val savedAt: Long)

    private val app = applicationContext(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vaultLock = Mutex()
    private val preparationLock = Mutex()
    private val preparationOwner = AtomicReference<Preparation?>()
    private val sessionEpoch = AtomicLong()
    private val auth = Lane(LaneKind.AUTH)
    private val browse = Lane(LaneKind.BROWSE)
    private val transfer = Lane(LaneKind.TRANSFER)
    private val profile = Lane(LaneKind.PROFILE)
    private val lanes = listOf(auth, browse, transfer, profile)
    private val snapshots = linkedMapOf<BrowseKey, BrowseSnapshot>()
    private val knownItems = linkedMapOf<String, SteamWallpaperItem>()
    private val _state = MutableStateFlow(SteamWallpaperState())
    override val state = _state.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var foreground = true
    @Volatile private var session: JsonObject? = null
    @Volatile private var savedSession: JsonObject? = null

    init {
        var restoredRecord: JsonObject? = null
        start(auth, { it.copy(restoring = true, authError = null) }, 60_000L, allowInstall = false, restoring = true, invalidateSessionErrors = true,
            makeRequest = {
                val stored = vaultLock.withLock { readSecret(app, SECRET_KEY) }
                if (stored == null) null else {
                    require(stored.length in 1..MAX_EVENT)
                    val saved = sessionRecord(Json.parseToJsonElement(stored) as? JsonObject
                        ?: throw SteamFailure("invalid_response"))
                    restoredRecord = saved
                    request("account", saved)
                }
            }, onEvent = { token, event ->
                if (event.text("kind") == "account") publishAccount(token, event, restoredRecord)
            })
    }

    override fun login() {
        if (closed || !_state.value.supported) return
        val saved = session ?: savedSession
        start(auth, { it.copy(restoring = false, authenticating = true, canResumeSession = false, qrUrl = null,
            guardMethods = emptyList(), guardSubmitting = false, guardError = null,
            authError = null, message = null) }, 350_000L,
            invalidateSessionErrors = saved != null,
            makeRequest = { request(if (saved == null) "login" else "account", saved) },
            onEvent = { token, event -> loginEvent(token, event, saved) })
    }

    override fun loginWithPassword(accountName: String, password: CharArray) {
        val account = accountName.trim()
        if (closed || !_state.value.supported ||
            account.isEmpty() || account.length > 128 || account.any(Char::isISOControl) ||
            password.isEmpty() || password.size > 4096) {
            password.fill('\u0000')
            if (!closed) _state.update { it.copy(authError = "请输入有效的 Steam 账号和密码。") }
            return
        }
        val ownedPassword = password.copyOf()
        password.fill('\u0000')
        start(auth, { it.copy(restoring = false, authenticating = true, canResumeSession = false, qrUrl = null,
            guardMethods = emptyList(), guardSubmitting = false, guardError = null,
            authError = null, message = null) }, 350_000L,
            makeRequest = {
                try {
                    buildJsonObject {
                        put("op", "passwordLogin"); put("account", account)
                        put("password", ownedPassword.concatToString())
                    }
                } finally { ownedPassword.fill('\u0000') }
            }, transientSecret = ownedPassword,
            onEvent = { token, event -> loginEvent(token, event, null) })
    }

    override fun submitGuardCode(code: String, method: SteamGuardMethod) {
        val current = _state.value
        val kind = when (method) {
            SteamGuardMethod.EMAIL_CODE -> 2
            SteamGuardMethod.AUTHENTICATOR_CODE -> 3
            else -> return
        }
        if (closed || !current.authenticating || current.guardSubmitting || method !in current.guardMethods) return
        val value = code.trim().uppercase()
        if (value.length != 5 || value.any { it !in 'A'..'Z' && it !in '0'..'9' }) {
            _state.update { it.copy(guardError = "请输入完整的 5 位 Steam 验证码。") }
            return
        }
        val token = auth.generation.get()
        val handle = auth.nativeJob.get()
        if (handle <= 0) return
        update(auth, token) { it.copy(guardSubmitting = true, guardError = null) }
        scope.launch {
            try {
                requireCurrent(auth, token)
                if (auth.nativeJob.get() != handle || !SteamNative.nativeSubmitGuard(handle, value, kind))
                    update(auth, token) { it.copy(guardSubmitting = false,
                        guardError = "验证暂未提交，请稍后重试。") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: LinkageError) {
                update(auth, token) { it.copy(guardSubmitting = false,
                    guardError = "Steam 组件需要更新，请重新打开创意工坊后重试。") }
            } catch (_: Exception) {
                update(auth, token) { it.copy(guardSubmitting = false,
                    guardError = "验证暂未提交，请稍后重试。") }
            }
        }
    }

    private suspend fun loginEvent(token: Long, event: JsonObject, previous: JsonObject?) {
        when (event.text("kind")) {
            "qr" -> {
                val url = steamQrUrl(event.text("url"))
                update(auth, token) { it.copy(qrUrl = url, canResumeSession = false, message = "请在 Steam 手机应用中确认登录。") }
            }
            "guard" -> {
                val values = event["methods"] as? JsonArray ?: throw SteamFailure("invalid_response")
                require(values.size <= 8)
                val methods = values.mapNotNull { value ->
                    when ((value as? JsonPrimitive)?.longOrNull) {
                        2L -> SteamGuardMethod.EMAIL_CODE
                        3L -> SteamGuardMethod.AUTHENTICATOR_CODE
                        4L -> SteamGuardMethod.MOBILE_CONFIRMATION
                        5L -> SteamGuardMethod.EMAIL_CONFIRMATION
                        else -> null
                    }
                }.distinct()
                if (methods.isEmpty()) throw SteamFailure("unsupported_guard")
                update(auth, token) { it.copy(guardMethods = methods, canResumeSession = false, guardSubmitting = false,
                    guardError = null, message = null) }
            }
            "guardAccepted" -> update(auth, token) { it.copy(guardSubmitting = true, guardError = null,
                message = "验证已提交，正在等待 Steam 确认。") }
            "guardRejected" -> update(auth, token) { it.copy(guardSubmitting = false,
                guardError = when (event.text("code")) {
                    "expired_guard_code" -> "验证码已过期，请输入新的验证码。"
                    "guard_submission_unknown" -> "连接中断，验证码是否提交尚未确认，请等待状态更新后再试。"
                    else -> "验证码不正确，请重新输入。"
                }) }
            "guardReconnecting" -> update(auth, token) { it.copy(
                message = "验证连接暂时中断，正在恢复本次验证…") }
            "guardResumed" -> update(auth, token) { it.copy(guardSubmitting = false,
                message = "验证连接已恢复，请继续完成验证。") }
            "authVerifying" -> update(auth, token) { it.copy(qrUrl = null, guardMethods = emptyList(),
                guardSubmitting = false, guardError = null, message = "验证已通过，正在确认 Steam 登录…") }
            "loggedIn", "account" -> publishAccount(token, event, previous)
            "phase" -> if (event.text("phase") == "waiting")
                update(auth, token) { it.copy(message = "正在等待另一项 Steam 操作结束…") }
        }
    }

    override fun search(query: String, kind: SteamWallpaperKind, page: Int) {
        if (closed || !_state.value.supported) return
        val keyword = query.trim()
        if (keyword.length > 120 || page !in 1..1000) {
            _state.update { it.copy(browseError = "搜索词最多 120 字，请使用有效页码。") }
            return
        }
        if (keyword.isEmpty()) {
            cancelBrowse()
            _state.update { it.copy(library = SteamWallpaperLibrary.SEARCH, query = "",
                kind = kind, items = emptyList(), page = 1, hasMore = false, browseError = null,
                message = if (it.authenticating) it.message else null) }
            return
        }
        browseItems(SteamWallpaperLibrary.SEARCH, keyword, kind, page, null)
    }

    override fun loadLibrary(library: SteamWallpaperLibrary, kind: SteamWallpaperKind, page: Int) {
        if (library == SteamWallpaperLibrary.SEARCH) {
            search(_state.value.query, kind, page)
            return
        }
        if (closed || !_state.value.supported || page !in 1..1000) return
        val lease = currentLease()
        if (lease == null) {
            _state.update { it.copy(browseError = "请先登录 Steam 后查看收藏和订阅。") }
            return
        }
        browseItems(library, "", kind, page, lease)
    }

    private fun browseItems(library: SteamWallpaperLibrary, query: String,
        kind: SteamWallpaperKind, page: Int, lease: SessionLease?) {
        val previous = _state.value
        val same = previous.library == library && previous.query == query && previous.kind == kind
        val append = page > 1 && same && page == previous.page + 1 && previous.hasMore && !previous.loading
        if (page > 1 && !append) return
        if (page == 1 && same && previous.loading) return
        val key = BrowseKey(library, query, kind, lease?.record?.text("steamId").orEmpty())
        val cached = if (!same && page == 1) synchronized(snapshots) { snapshots[key] } else null
        if (cached != null && System.nanoTime() - cached.savedAt in 0L until 60_000_000_000L) {
            cancelBrowse()
            _state.update { it.copy(library = library, query = query, kind = kind, items = cached.items,
                page = cached.page, hasMore = cached.hasMore,
                message = if (it.authenticating) it.message else cached.message, browseError = null) }
            return
        }
        start(browse, { it.copy(library = library, loading = true, query = query, kind = kind,
            browseError = null, message = if (it.authenticating) it.message else null, page = if (append) it.page else cached?.page ?: 1,
            items = if (append || same) it.items else cached?.items.orEmpty(),
            hasMore = if (append || same) it.hasMore else cached?.hasMore ?: false) },
            90_000L, lease = lease,
            makeRequest = {
                if (library == SteamWallpaperLibrary.SEARCH) request("search", null) {
                    put("query", query); put("media", kind.wire()); put("page", page)
                } else request("library", lease?.record) {
                    put("collection", if (library == SteamWallpaperLibrary.FAVORITES) "favorites" else "subscriptions")
                    put("media", kind.wire()); put("page", page)
                }
            }) { token, event ->
                when (event.text("kind")) {
                    "phase" -> if (event.text("phase") == "waiting")
                        update(browse, token) { if (it.authenticating) it else it.copy(message = "正在等待另一项 Steam 操作结束…") }
                    "items" -> {
                        val rows = event["items"] as? JsonArray ?: throw SteamFailure("invalid_response")
                        require(rows.size <= 100)
                        val items = rows.mapNotNull {
                            parseItem(it as? JsonObject ?: throw SteamFailure("invalid_response"), kind)
                        }
                        val actualPage = event.number("page")?.toInt() ?: page
                        if (actualPage != page) throw SteamFailure("invalid_response")
                        requireCurrent(browse, token, lease)
                        val combined = ((if (append) previous.items else emptyList()) + items)
                            .distinctBy(SteamWallpaperItem::id).take(500)
                        val hasMore = event.flag("hasMore") == true && combined.size < 500
                        val message = if (combined.size >= 500 && event.flag("hasMore") == true)
                            "已显示前 500 个可导入项目。"
                        else if (items.isEmpty() && !append) {
                            if ((event.number("unsupportedCount") ?: 0) > 0 && kind == SteamWallpaperKind.IMAGE)
                                "这一页没有可直接导入的图片；场景工程需从电脑导出原媒体后添加。"
                            else when (library) {
                                SteamWallpaperLibrary.FAVORITES -> "这一页没有可直接导入的收藏项目。"
                                SteamWallpaperLibrary.SUBSCRIPTIONS -> "这一页没有可直接导入的订阅项目。"
                                SteamWallpaperLibrary.SEARCH -> "没有找到可直接导入的原始图片或视频项目。"
                            }
                        } else null
                        rememberItems(combined)
                        synchronized(snapshots) {
                            snapshots.remove(key)
                            snapshots[key] = BrowseSnapshot(combined, page, hasMore, message, System.nanoTime())
                            while (snapshots.size > 12) snapshots.remove(snapshots.keys.first())
                        }
                        update(browse, token) { it.copy(items = combined, page = page,
                            hasMore = hasMore, message = if (it.authenticating) it.message else message) }
                    }
                }
            }
    }

    override fun download(item: SteamWallpaperItem) {
        if (closed || !_state.value.supported) return
        val lease = currentLease()
        if (lease == null) {
            _state.update { it.copy(transferError = "请先登录拥有 Wallpaper Engine 的 Steam 账号。") }
            return
        }
        if (_state.value.transferId != null) return
        val selected = _state.value.items.firstOrNull { it.id == item.id && it.kind == item.kind }
            ?: synchronized(knownItems) { knownItems[itemKey(item)] }
        if (selected == null || selected.id != item.id || selected.kind != item.kind || !validItemId(item.id)) return
        if (selected.sizeBytes != null && selected.sizeBytes > MAX_MEDIA) {
            _state.update { it.copy(transferError = "这份壁纸超过 512 MB，请选择较小的原始图片或视频。") }
            return
        }
        start(transfer, { it.copy(transferId = selected.id, transferName = selected.title,
            receivedBytes = 0, totalBytes = 0, transferProgressKnown = false,
            transferPhase = SteamWallpaperTransferPhase.CONNECTING, transferError = null) },
            15 * 60_000L, lease = lease,
            makeRequest = { request("download", lease.record) {
                put("itemId", selected.id); put("media", selected.kind.wire())
            } }, download = selected) { _, _ -> Unit }
    }

    override fun refreshProfile() {
        if (closed || !_state.value.supported) return
        val lease = currentLease() ?: return
        if (_state.value.profileLoading) return
        start(profile, { it.copy(profileLoading = true, profileError = null) }, 30_000L,
            lease = lease, allowInstall = false,
            makeRequest = { request("profile", lease.record) }) { token, event ->
                if (event.text("kind") == "profile") {
                    if (event.text("steamId") != lease.record.text("steamId"))
                        throw SteamFailure("invalid_response")
                    val name = event.text("personaName").filterNot(Char::isISOControl).trim().take(128).takeIf { it.isNotBlank() }
                    val avatar = avatarUrl(event.text("avatarUrl")).takeIf { it.isNotBlank() }
                    update(profile, token) { current ->
                        val account = current.account
                        if (account == null || account.steamId != lease.record.text("steamId")) current
                        else current.copy(account = account.copy(personaName = name, avatarUrl = avatar))
                    }
                }
            }
    }

    override fun setForeground(foreground: Boolean) {
        // Guard polling and owned transfers retain their native handles while the UI is backgrounded.
        this.foreground = foreground
    }

    override fun cancelAuthentication() {
        stop(auth)
        if (!closed) _state.update { it.copy(restoring = false, authenticating = false,
            canResumeSession = session == null && savedSession != null, qrUrl = null,
            guardMethods = emptyList(), guardSubmitting = false, guardError = null, authError = null) }
    }

    override fun cancelBrowse() {
        stop(browse)
        if (!closed) _state.update { it.copy(loading = false, browseError = null) }
    }

    override fun cancelTransfer() {
        stop(transfer)
        if (!closed) _state.update { it.copy(transferId = null, transferName = null,
            receivedBytes = 0, totalBytes = 0, transferPhase = SteamWallpaperTransferPhase.NONE,
            transferProgressKnown = false, transferError = null) }
    }

    override fun cancel() {
        lanes.forEach(::stop)
        if (!closed) _state.update { it.copy(restoring = false, preparing = false,
            canResumeSession = session == null && savedSession != null, componentBytes = 0,
            componentTotalBytes = 0, authenticating = false, qrUrl = null,
            guardMethods = emptyList(), guardSubmitting = false, guardError = null,
            loading = false, transferId = null, transferName = null, receivedBytes = 0,
            totalBytes = 0, transferPhase = SteamWallpaperTransferPhase.NONE,
            transferProgressKnown = false, profileLoading = false, authError = null, browseError = null,
            transferError = null, profileError = null, message = null) }
    }

    override fun logout() {
        if (closed) return
        lanes.forEach(::stop)
        val token = auth.generation.get()
        val previous = _state.value.account
        val previousSession = session
        val previousSavedSession = savedSession
        val epoch = sessionEpoch.incrementAndGet()
        session = null
        savedSession = null
        clearPersonalData()
        _state.update { it.copy(restoring = true, preparing = false, componentBytes = 0,
            componentTotalBytes = 0, authenticating = false, qrUrl = null,
            guardMethods = emptyList(), guardSubmitting = false, guardError = null,
            loading = false, account = null, canResumeSession = false, transferId = null, transferName = null,
            receivedBytes = 0, totalBytes = 0, transferPhase = SteamWallpaperTransferPhase.NONE,
            transferProgressKnown = false, transferError = null, profileLoading = false,
            profileError = null, authError = null, browseError = null, message = null, error = null,
            items = if (it.library == SteamWallpaperLibrary.SEARCH) it.items else emptyList(),
            hasMore = it.library == SteamWallpaperLibrary.SEARCH && it.hasMore) }
        auth.operation = scope.launch {
            try {
                vaultLock.withLock {
                    requireCurrent(auth, token)
                    if (sessionEpoch.get() != epoch) throw CancellationException("Steam account changed")
                    deleteSecret(app, SECRET_KEY)
                }
                update(auth, token) { it.copy(restoring = false, message = "已退出 Steam") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isCurrent(auth, token) && sessionEpoch.get() == epoch) {
                    session = previousSession
                    savedSession = previousSavedSession
                }
                update(auth, token) { it.copy(restoring = false, account = previous,
                    canResumeSession = previousSession == null && previousSavedSession != null,
                    authError = "未能移除安全登录凭据，请重试退出。") }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        lanes.forEach(::stop)
        sessionEpoch.incrementAndGet()
        session = null
        savedSession = null
        synchronized(snapshots) { snapshots.clear() }
        synchronized(knownItems) { knownItems.clear() }
        scope.cancel()
    }

    private fun start(
        lane: Lane,
        state: (SteamWallpaperState) -> SteamWallpaperState,
        timeout: Long,
        lease: SessionLease? = null,
        allowInstall: Boolean = true,
        restoring: Boolean = false,
        invalidateSessionErrors: Boolean = lease != null,
        makeRequest: suspend () -> JsonObject?,
        download: SteamWallpaperItem? = null,
        transientSecret: CharArray? = null,
        onEvent: suspend (Long, JsonObject) -> Unit,
    ) {
        if (closed) { transientSecret?.fill('\u0000'); return }
        // Freeze this operation's route; a settings edit affects only later operations.
        val route = try {
            if (restoring) null else steamRoute()
        } catch (failure: Exception) {
            transientSecret?.fill('\u0000')
            val message = if (failure is DownloadRouteException) failure.message else null
            fail(lane, lane.generation.get(),
                message ?: "Steam 连接线路暂不可用，请检查下载线路设置。")
            return
        }
        stop(lane)
        val token = lane.generation.get()
        update(lane, token, state)
        lane.operation = scope.launch {
            var directory: File? = null
            var media: File? = null
            var primaryFile: String? = null
            try {
                requireCurrent(lane, token, lease)
                val ready = ensureNative(lane, token, lease, allowInstall)
                if (!ready) {
                    if (restoring) {
                        update(lane, token) { it.copy(message = "首次使用时下载 Steam 组件，普通阅读不受影响。") }
                        return@launch
                    }
                    throw SteamFailure("component_unavailable")
                }
                requireCurrent(lane, token, lease)
                val unrouted = makeRequest() ?: return@launch
                requireCurrent(lane, token, lease)
                if (restoring) {
                    val candidate = sessionRecord(unrouted)
                    vaultLock.withLock {
                        requireCurrent(lane, token, lease)
                        savedSession = candidate
                        update(lane, token) { it.copy(canResumeSession = true) }
                    }
                }
                var request = routedRequest(unrouted, route ?: steamRoute())
                if (download != null) {
                    val root = File(cacheDirectory(app).path, "steam-wallpapers")
                    check(root.isDirectory || root.mkdirs())
                    directory = File(root, randomUuid())
                    check(checkNotNull(directory).mkdir())
                    request = buildJsonObject {
                        request.forEach { (key, value) -> put(key, value) }
                        put("directory", checkNotNull(directory).absolutePath); put("maxBytes", MAX_MEDIA)
                    }
                }
                runNative(lane, token, lease, request, timeout) { event ->
                    when {
                        lane.kind == LaneKind.TRANSFER && event.text("kind") == "complete" -> {
                            val value = event.text("primaryFile")
                            if (value.isNotBlank()) {
                                if (download == null || event.text("media") != download.kind.wire())
                                    throw SteamFailure("invalid_response")
                                primaryFile = value.takeIf { it.length <= 1024 }
                                    ?: throw SteamFailure("invalid_response")
                            }
                        }
                        lane.kind == LaneKind.TRANSFER && event.text("kind") == "progress" ->
                            transferProgress(token, event)
                        lane.kind == LaneKind.TRANSFER && event.text("kind") == "phase" -> {
                            val phase = transferPhase(event.text("phase"))
                                ?: throw SteamFailure("invalid_response")
                            update(lane, token) { it.copy(transferPhase = phase) }
                        }
                        else -> onEvent(token, event)
                    }
                }
                requireCurrent(lane, token, if (lane.kind == LaneKind.AUTH) null else lease)
                if (download != null) {
                    update(lane, token) { it.copy(transferPhase = SteamWallpaperTransferPhase.VERIFYING) }
                    media = withTimeout(120_000L) {
                        WallpaperEnginePackage.extractProjectMedia(checkNotNull(directory),
                            download.title, download.kind, primaryFile)
                    }
                    requireCurrent(lane, token, lease)
                    vaultLock.withLock {
                        requireCurrent(lane, token, lease)
                        update(lane, token) { it.copy(transferPhase = SteamWallpaperTransferPhase.IMPORTING) }
                        withTimeout(120_000L) {
                            WallpaperStore.get(app).importMedia(checkNotNull(media).toURI().toString(),
                                displayName = download.title)
                        }
                        requireCurrent(lane, token, lease)
                    }
                    update(lane, token) { it.copy(importedCount = it.importedCount + 1,
                        message = if (it.authenticating) it.message else "已加入壁纸库", transferError = null) }
                }
            } catch (timeoutError: TimeoutCancellationException) {
                fail(lane, token, if (lane.kind == LaneKind.TRANSFER)
                    "壁纸操作超时，请重试；已保留 Steam 登录。" else if (lane.kind == LaneKind.PROFILE)
                    "Steam 公开资料读取超时，可稍后重试。" else if (restoring)
                    "暂时未能恢复 Steam 登录，可继续搜索或重新登录。"
                    else if (lane.kind == LaneKind.AUTH) "本次 Steam 验证已超时，请重新登录。"
                    else "Steam 请求超时，请重试。")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DownloadRouteException) {
                fail(lane, token, failure.message ?: "Steam 连接线路暂不可用，请检查下载线路设置。")
            } catch (failure: SteamFailure) {
                val removed = if (lane.kind != LaneKind.PROFILE && invalidateSessionErrors &&
                    failure.code in SESSION_ERRORS) invalidateSession(lane, token, lease) else true
                if (failure.code == "component_unavailable")
                    update(lane, token) { it.copy(error = failure.userMessage()) }
                fail(lane, token, failure.userMessage() +
                    if (removed) "" else " 旧登录凭据未能清理，请重试退出。")
            } catch (_: SecurityException) {
                fail(lane, token, if (lane.kind == LaneKind.PROFILE) "暂时无法读取 Steam 公开资料。"
                    else "无法保存 Steam 登录或壁纸，请检查系统权限。")
            } catch (_: LinkageError) {
                update(lane, token) { it.copy(error = "Steam 组件需要更新，请关闭创意工坊后重新打开。") }
                fail(lane, token, "Steam 组件需要更新，请关闭创意工坊后重新打开。")
            } catch (_: Exception) {
                fail(lane, token, when (lane.kind) {
                    LaneKind.TRANSFER -> "壁纸未能完成校验或加入，请重试或选择其他原始图片、视频。"
                    LaneKind.PROFILE -> "暂时无法读取 Steam 公开资料，Steam 登录仍然保留。"
                    LaneKind.AUTH -> if (restoring) "暂时未能恢复 Steam 登录，可继续搜索或重新登录。"
                        else "Steam 登录连接暂不可用，请重试。"
                    LaneKind.BROWSE -> "Steam 暂时不可用，请稍后重试。"
                })
            } finally {
                transientSecret?.fill('\u0000')
                media?.delete()
                directory?.let(::removeDownloadDirectory)
                update(lane, token) { current -> when (lane.kind) {
                    LaneKind.AUTH -> current.copy(restoring = false, authenticating = false,
                        canResumeSession = session == null && savedSession != null, qrUrl = null,
                        guardMethods = emptyList(), guardSubmitting = false, guardError = null)
                    LaneKind.BROWSE -> current.copy(loading = false)
                    LaneKind.PROFILE -> current.copy(profileLoading = false)
                    LaneKind.TRANSFER -> {
                        val failed = current.transferError != null
                        current.copy(transferId = null,
                            transferName = if (failed) current.transferName else null,
                            receivedBytes = if (failed) current.receivedBytes else 0,
                            totalBytes = if (failed) current.totalBytes else 0,
                            transferPhase = SteamWallpaperTransferPhase.NONE, transferProgressKnown = false)
                    }
                } }
            }
        }
        lane.operation?.invokeOnCompletion { transientSecret?.fill('\u0000') }
    }

    private suspend fun ensureNative(lane: Lane, token: Long, lease: SessionLease?,
        allowInstall: Boolean): Boolean = preparationLock.withLock {
        requireCurrent(lane, token, lease)
        if (prepareNative(false) { _, _ -> }) {
            update(lane, token) { it.copy(error = null) }
            return@withLock true
        }
        requireCurrent(lane, token, lease)
        if (!allowInstall) return@withLock false
        val owner = Preparation(lane, token)
        preparationOwner.set(owner)
        update(lane, token) { it.copy(preparing = true, componentBytes = 0, componentTotalBytes = 0) }
        try {
            val ready = prepareNative(true) { received, total ->
                if (preparationOwner.get() === owner)
                    update(lane, token) { it.copy(componentBytes = received.coerceAtLeast(0),
                        componentTotalBytes = total.coerceAtLeast(0)) }
            }
            requireCurrent(lane, token, lease)
            if (ready) update(lane, token) { it.copy(error = null) }
            ready
        } finally {
            if (preparationOwner.compareAndSet(owner, null))
                _state.update { it.copy(preparing = false, componentBytes = 0, componentTotalBytes = 0) }
        }
    }

    private suspend fun runNative(
        lane: Lane, token: Long, lease: SessionLease?, request: JsonObject, timeout: Long,
        onEvent: suspend (JsonObject) -> Unit,
    ) = withTimeout(timeout) {
        requireCurrent(lane, token, lease)
        val handle = SteamNative.nativeStart(request.toString())
        if (handle <= 0) throw SteamFailure("unavailable")
        lane.nativeJob.set(handle)
        val expected = when (request.text("op")) {
            "login", "passwordLogin" -> "loggedIn"
            "account" -> "account"
            "search", "library" -> "items"
            "profile" -> "profile"
            else -> "complete"
        }
        var receivedResult = expected == "complete"
        var phase = SteamWallpaperTransferPhase.CONNECTING
        var lastBytes = 0L
        var lastAdvance = System.nanoTime()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                requireCurrent(lane, token, if (lane.kind == LaneKind.AUTH) null else lease)
                if (lane.kind == LaneKind.TRANSFER) {
                    val limit = when (phase) {
                        SteamWallpaperTransferPhase.WAITING -> 30_000_000_000L
                        SteamWallpaperTransferPhase.CONNECTING -> 60_000_000_000L
                        SteamWallpaperTransferPhase.VERIFYING -> 120_000_000_000L
                        else -> 90_000_000_000L
                    }
                    if (System.nanoTime() - lastAdvance > limit)
                        throw SteamFailure(when (phase) {
                            SteamWallpaperTransferPhase.WAITING -> "busy"
                            SteamWallpaperTransferPhase.CONNECTING -> "connection_timeout"
                            SteamWallpaperTransferPhase.RESOLVING -> "metadata_timeout"
                            else -> "download_stalled"
                        })
                }
                val raw = SteamNative.nativePoll(handle)
                if (raw == null) { delay(if (foreground) 32L else 160L); continue }
                if (raw.length !in 1..MAX_EVENT) throw SteamFailure("invalid_response")
                val event = Json.parseToJsonElement(raw) as? JsonObject ?: throw SteamFailure("invalid_response")
                requireCurrent(lane, token, if (lane.kind == LaneKind.AUTH) null else lease)
                if (lane.kind == LaneKind.TRANSFER) {
                    if (event.text("kind") == "phase") {
                        val next = transferPhase(event.text("phase")) ?: throw SteamFailure("invalid_response")
                        if (next != phase) { phase = next; lastAdvance = System.nanoTime() }
                    } else if (event.text("kind") == "progress") {
                        val bytes = event.number("completed") ?: 0L
                        if (bytes > lastBytes) { lastBytes = bytes; lastAdvance = System.nanoTime() }
                    }
                }
                when (event.text("kind")) {
                    "error" -> throw SteamFailure(event.text("code").takeIf { it in ERROR_CODES } ?: "unavailable")
                    "complete" -> {
                        if (!receivedResult) throw SteamFailure("invalid_response")
                        onEvent(event)
                        break
                    }
                    else -> {
                        if (event.text("kind") == expected) receivedResult = true
                        onEvent(event)
                    }
                }
            }
        } finally {
            lane.nativeJob.compareAndSet(handle, 0)
            runCatching { SteamNative.nativeCancel(handle) }
            runCatching { SteamNative.nativeFree(handle) }
        }
    }

    private fun transferProgress(token: Long, event: JsonObject) {
        val received = event.number("completed") ?: 0L
        val total = event.number("total") ?: 0L
        if (received < 0 || total < 0 || received > MAX_MEDIA || total > MAX_MEDIA ||
            (total > 0 && received > total)) throw SteamFailure("too_large")
        val known = event.flag("known") == true && total > 0
        update(transfer, token) { it.copy(receivedBytes = received, totalBytes = total,
            transferProgressKnown = known) }
    }

    private fun transferPhase(value: String): SteamWallpaperTransferPhase? = when (value) {
        "waiting" -> SteamWallpaperTransferPhase.WAITING
        "connecting" -> SteamWallpaperTransferPhase.CONNECTING
        "resolving" -> SteamWallpaperTransferPhase.RESOLVING
        "downloading" -> SteamWallpaperTransferPhase.DOWNLOADING
        "verifying" -> SteamWallpaperTransferPhase.VERIFYING
        else -> null
    }

    private suspend fun publishAccount(token: Long, event: JsonObject, previous: JsonObject?) {
        requireCurrent(auth, token)
        if (previous != null && event.text("steamId").isNotBlank() &&
            event.text("steamId") != previous.text("steamId")) throw SteamFailure("invalid_response")
        val record = sessionRecord(buildJsonObject {
            put("account", event.text("account").ifEmpty { previous?.text("account").orEmpty() })
            put("steamId", event.text("steamId").ifEmpty { previous?.text("steamId").orEmpty() })
            put("refreshToken", event.text("refreshToken").ifEmpty { previous?.text("refreshToken").orEmpty() })
        })
        vaultLock.withLock {
            requireCurrent(auth, token)
            writeSecret(app, SECRET_KEY, record.toString())
            requireCurrent(auth, token)
            session = record
            savedSession = null
            sessionEpoch.incrementAndGet()
            // A verified credential replaces the account context, never an anonymous browse.
            if (_state.value.library != SteamWallpaperLibrary.SEARCH) cancelBrowse()
            cancelTransfer()
            stop(profile)
            clearPersonalData()
            val persona = event.text("personaName").filterNot(Char::isISOControl).trim().take(128)
                .takeIf { it.isNotBlank() }
            val avatar = avatarUrl(event.text("avatarUrl")).takeIf { it.isNotBlank() }
            update(auth, token) { current -> current.copy(
                account = SteamWallpaperAccount(record.text("account"), record.text("steamId"),
                    personaName = persona, avatarUrl = avatar),
                canResumeSession = false, authenticating = false, qrUrl = null, guardMethods = emptyList(),
                guardSubmitting = false, guardError = null, profileLoading = false, profileError = null,
                items = if (current.library == SteamWallpaperLibrary.SEARCH) current.items else emptyList(),
                hasMore = current.library == SteamWallpaperLibrary.SEARCH && current.hasMore,
                message = "Steam 已登录", authError = null) }
        }
        if (isCurrent(auth, token)) refreshProfile()
    }

    private suspend fun invalidateSession(lane: Lane, token: Long, lease: SessionLease?): Boolean {
        var removed = true
        vaultLock.withLock {
            requireCurrent(lane, token, lease)
            session = null
            savedSession = null
            sessionEpoch.incrementAndGet()
            removed = runCatching { deleteSecret(app, SECRET_KEY) }.isSuccess
            if (lane !== browse && _state.value.library != SteamWallpaperLibrary.SEARCH) stop(browse)
            if (lane !== transfer) stop(transfer)
            if (lane !== profile) stop(profile)
            clearPersonalData()
            update(lane, token) { current -> current.copy(account = null, canResumeSession = false,
                authError = if (lane === auth || !current.authenticating) "Steam 登录已失效，请重新登录。" else current.authError,
                loading = if (lane === browse) current.loading else false,
                transferId = if (lane === transfer) current.transferId else null,
                transferName = if (lane === transfer) current.transferName else null,
                transferPhase = if (lane === transfer) current.transferPhase else SteamWallpaperTransferPhase.NONE,
                transferProgressKnown = lane === transfer && current.transferProgressKnown,
                profileLoading = false,
                items = if (current.library == SteamWallpaperLibrary.SEARCH) current.items else emptyList(),
                hasMore = current.library == SteamWallpaperLibrary.SEARCH && current.hasMore) }
        }
        return removed
    }

    private fun currentLease(): SessionLease? {
        val record = session ?: return null
        val epoch = sessionEpoch.get()
        if (closed || _state.value.account?.steamId != record.text("steamId")) return null
        if (sessionEpoch.get() != epoch || session !== record) return null
        return SessionLease(record, epoch)
    }

    private fun stop(lane: Lane) {
        lane.generation.incrementAndGet()
        lane.operation?.cancel()
        lane.operation = null
        val owner = preparationOwner.get()
        if (owner?.lane === lane && preparationOwner.compareAndSet(owner, null)) {
            runCatching { cancelPreparation() }
            _state.update { it.copy(preparing = false, componentBytes = 0, componentTotalBytes = 0) }
        }
        lane.nativeJob.get().takeIf { it > 0 }?.let { runCatching { SteamNative.nativeCancel(it) } }
    }

    private fun isCurrent(lane: Lane, token: Long): Boolean = !closed && lane.generation.get() == token

    private fun requireCurrent(lane: Lane, token: Long, lease: SessionLease? = null) {
        if (!isCurrent(lane, token) || lease != null &&
            (sessionEpoch.get() != lease.epoch || session !== lease.record))
            throw CancellationException("Steam operation changed")
    }

    private fun update(lane: Lane, token: Long, transform: (SteamWallpaperState) -> SteamWallpaperState) {
        _state.update { if (isCurrent(lane, token)) transform(it) else it }
    }

    private fun fail(lane: Lane, token: Long, message: String) = update(lane, token) {
        when (lane.kind) {
            LaneKind.TRANSFER -> it.copy(transferError = message)
            LaneKind.PROFILE -> it.copy(profileError = message)
            LaneKind.AUTH -> it.copy(authError = message, message = null)
            LaneKind.BROWSE -> it.copy(browseError = message,
                message = if (it.authenticating) it.message else null)
        }
    }

    private fun itemKey(item: SteamWallpaperItem): String = item.kind.name + ":" + item.id

    private fun rememberItems(items: List<SteamWallpaperItem>) = synchronized(knownItems) {
        items.forEach { item ->
            val key = itemKey(item)
            knownItems.remove(key)
            knownItems[key] = item
        }
        while (knownItems.size > 500) knownItems.remove(knownItems.keys.first())
    }

    private fun clearPersonalData() {
        synchronized(snapshots) {
            snapshots.keys.filter { it.library != SteamWallpaperLibrary.SEARCH }.forEach(snapshots::remove)
        }
        synchronized(knownItems) { knownItems.clear() }
    }

    private fun avatarUrl(value: String): String = runCatching {
        val uri = URI(value)
        val host = uri.host.orEmpty().lowercase()
        value.takeIf { value.length in 1..4096 && uri.scheme == "https" && uri.userInfo == null &&
            uri.port == -1 && uri.fragment == null &&
            (host == "steamstatic.com" || host.endsWith(".steamstatic.com") ||
                host == "steamusercontent.com" || host.endsWith(".steamusercontent.com") ||
                host == "steamuserimages-a.akamaihd.net") }.orEmpty()
    }.getOrDefault("")

    private fun sessionRecord(value: JsonObject): JsonObject {
        val account = value.text("account")
        val steamId = value.text("steamId")
        val token = value.text("refreshToken")
        require(account.length in 1..128 && account.none(Char::isISOControl))
        require(steamId.length in 1..20 && steamId.all(Char::isDigit))
        require(token.length in 16..16384 && token.none(Char::isISOControl))
        return buildJsonObject { put("account", account); put("steamId", steamId); put("refreshToken", token) }
    }


    private fun steamRoute(): DownloadRouteSnapshot {
        val route = DownloadRoutingStore.get(app).snapshot(DownloadCategory.STEAM_WALLPAPERS)
        if (route.mode == DownloadRouteMode.PROXY && route.proxy == null)
            throw DownloadRouteException("尚未配置代理，请先在网络与下载中填写代理地址。")
        return route
    }

    private fun routedRequest(request: JsonObject, route: DownloadRouteSnapshot): JsonObject = buildJsonObject {
        request.forEach { (key, value) -> put(key, value) }
        put("network", buildJsonObject {
            put("kind", when (route.mode) {
                DownloadRouteMode.SYSTEM -> "SYSTEM"
                DownloadRouteMode.DIRECT -> "DIRECT"
                DownloadRouteMode.PROXY -> when (checkNotNull(route.proxy).type) {
                    DownloadProxyType.HTTP -> "HTTP"
                    DownloadProxyType.SOCKS -> "SOCKS5"
                }
            })
            if (route.mode == DownloadRouteMode.PROXY) {
                val proxy = checkNotNull(route.proxy)
                put("host", proxy.host); put("port", proxy.port)
            }
        })
    }

    private fun request(op: String, credentials: JsonObject? = session,
        arguments: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("op", op)
            credentials?.forEach { (key, value) -> put(key, value) }
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
            "timeout", "login_timeout", "auth_session_expired" -> "本次 Steam 验证已超时，请重新登录。"
            "auth_connection_lost" -> "未能恢复本次验证连接，请重新登录。"
            "connection_timeout" -> "Steam 连接超时，请检查线路后重试。"
            "metadata_timeout" -> "Steam 下载信息获取超时，请重试。"
            "download_stalled" -> "壁纸传输长时间没有进展，已停止，请重试或检查下载线路。"
            "range_unsupported" -> "当前下载地址不支持安全分段传输，请换用其他项目或从电脑导出。"
            "content_changed" -> "下载文件已变化，已停止本次传输，请重新下载。"
            "profile_unavailable", "profile_timeout" -> "暂时无法读取 Steam 公开资料，登录仍然保留。"
            "too_large", "size_limit" -> "这份壁纸超过 512 MB，请选择较小的项目。"
            "unsupported_media" -> "这份项目不能直接作为原始图片或视频导入。"
            "network_configuration" -> "Steam 连接线路未配置，请先设置应用代理地址。"
            "component_unavailable" -> "Steam 组件未能准备好，请检查网络后重试。"
            "not_found" -> "这份壁纸已不可用，请刷新后重试。"
            "download_failed" -> "壁纸下载未完成，请检查网络后重试。"
            "unsafe_path" -> "这份壁纸的文件路径不安全，已停止导入。"
            "login_failed" -> "Steam 登录未完成，请重新确认。"
            "invalid_credentials" -> "Steam 账号或密码不正确。"
            "invalid_guard_code" -> "Steam 验证码不正确，请重新输入。"
            "expired_guard_code" -> "Steam 验证码已过期，请输入新的验证码。"
            "login_rate_limited" -> "Steam 登录尝试过多，请稍后重试或使用扫码登录。"
            "login_refused" -> "Steam 暂未允许此账号登录，请到 Steam 查看账号状态。"
            "unsupported_guard" -> "此账号需要其他 Steam 验证方式，请使用扫码登录。"
            "busy" -> "另一项 Steam 操作仍未结束，请稍后重试；当前下载与登录不会被搜索取消。"
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
            "steam_unavailable", "not_owned", "size_limit", "unsafe_path", "download_failed", "busy", "native_failed",
            "invalid_credentials", "invalid_guard_code", "expired_guard_code", "login_rate_limited",
            "login_refused", "unsupported_guard", "network_configuration",
            "connection_timeout", "metadata_timeout", "download_stalled", "range_unsupported", "content_changed",
            "auth_connection_lost", "auth_session_expired", "profile_unavailable", "profile_timeout")
    }
}
