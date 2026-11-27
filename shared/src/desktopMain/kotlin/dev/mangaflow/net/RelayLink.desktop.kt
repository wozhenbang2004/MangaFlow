package dev.mangaflow.net

import dev.mangaflow.APP_DISPLAY_NAME

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal actual val relayLinkAvailable = System.getProperty("os.name", "").startsWith("Windows", ignoreCase = true)
private val grantIdentity = Regex("[a-f0-9]{32}")
private data class WindowsGrant(val id: String, val enabled: Boolean)
private data class WindowsLease(val grantId: String, val generation: String)

private class DesktopRelayLink(context: PlatformContext) : RelayPlatformLink {
    private val disk = preferences(context, "desktop-relay-link")
    private val gate = Mutex()
    private val mutableState = MutableStateFlow(RelayLinkState(
        phase = if (relayLinkAvailable) RelayLinkPhase.NOT_INSTALLED else RelayLinkPhase.NOT_AVAILABLE,
    ))
    override val state = mutableState.asStateFlow()
    @Volatile private var grant = disk.getString("grant")?.takeIf(grantIdentity::matches)?.let {
        WindowsGrant(it, disk.getBoolean("enabled"))
    }
    @Volatile private var lease: WindowsLease? = null
    @Volatile private var operations: CoroutineScope? = null
    private var observer: Job? = null
    private val endpoints = mutableMapOf<DownloadRouteMode, RelayManagedEndpoint>()

    @Synchronized
    override fun start(scope: CoroutineScope) {
        if (!relayLinkAvailable || observer?.isActive == true) return
        operations = scope
        observer = scope.launch(Dispatchers.IO) {
            while (true) {
                gate.withLock { refreshLocked() }
                delay(if (grant?.enabled == true && lease == null) 1500 else 6500)
            }
        }
    }

    override fun refresh() = launch { refreshLocked() }

    override fun requestAuthorization() = launch {
        mutableState.update { it.copy(phase = RelayLinkPhase.AUTHORIZING, error = null) }
        applyReply(call { it.authorize() })
    }

    override fun enable() = launch {
        val known = grant ?: return@launch
        applyReply(call { it.setEnabled(known.id, true) })
    }

    override fun disable() = stop("pause")
    override fun revoke() = stop("revoke")

    private fun stop(action: String) = launch {
        val known = grant ?: return@launch
        val editor = disk.edit().putString("pending", action).putString("pending-grant", known.id)
        if (!editor.commit()) {
            mutableState.update { it.copy(error = "未能保存联动操作，请重试。") }
            return@launch
        }
        lease = null
        endpoints.clear()
        refreshLocked()
    }

    override fun openProvider() {
        operations?.launch(Dispatchers.IO) {
            val exe = windowsRelayExecutable() ?: return@launch
            try { ProcessBuilder(exe.absolutePath).directory(exe.parentFile).start() }
            catch (_: Exception) { mutableState.update { it.copy(error = "无法打开 Relay，请从桌面打开它。") } }
        }
    }

    override fun updateSources(sources: Map<String, String>) = Unit

    private fun launch(block: suspend () -> Unit) {
        operations?.launch(Dispatchers.IO) { gate.withLock { block() } }
    }

    private suspend fun refreshLocked() {
        val action = disk.getString("pending")
        val id = disk.getString("pending-grant")
        if (id != null && action in setOf("pause", "revoke")) {
            val result = call { if (action == "pause") it.setEnabled(id, false) else it.revoke(id) }
            if (result?.status !in setOf(WindowsRelayCompanionStatus.AUTHORIZATION_REQUIRED,
                    WindowsRelayCompanionStatus.DISABLED, WindowsRelayCompanionStatus.STALE)) {
                applyReply(result)
                lease = null
                endpoints.clear()
                mutableState.update { it.copy(phase = RelayLinkPhase.ERROR, error = "还未收到 Relay 的停用或撤销回执，将自动重试。") }
                return
            }
            disk.edit().remove("pending").remove("pending-grant").commit()
        }
        applyReply(call { it.status() })
    }

    private suspend fun call(block: suspend (WindowsRelayCompanionClient) -> WindowsRelayCompanionResult):
        WindowsRelayCompanionResult? = try { windowsRelayCompanionClient()?.let { block(it) } }
        catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { null }

    private fun applyReply(result: WindowsRelayCompanionResult?) {
        val status = result?.status
        val replyGrant = result?.bindingId?.takeIf(grantIdentity::matches)
        val newGrant = when {
            status == WindowsRelayCompanionStatus.AUTHORIZATION_REQUIRED -> null
            replyGrant != null -> WindowsGrant(replyGrant, status != WindowsRelayCompanionStatus.DISABLED)
            else -> grant // A service restart must not change an already selected route.
        }
        if (newGrant != grant) {
            val editor = disk.edit().putBoolean("enabled", newGrant?.enabled == true)
            if (newGrant == null) editor.remove("grant") else editor.putString("grant", newGrant.id)
            if (!editor.commit()) {
                lease = null
                endpoints.clear()
                mutableState.update { it.copy(phase = RelayLinkPhase.ERROR, error = "未能保存 Relay 授权，请重试。") }
                return
            }
            grant = newGrant
        }
        val current = if (status == WindowsRelayCompanionStatus.READY && replyGrant != null)
            result?.generation?.let { WindowsLease(replyGrant, it) } else null
        val keepEvidence = current != null && lease == current
        if (!keepEvidence) endpoints.clear()
        lease = current
        val installed = windowsRelayExecutable() != null || status != null && status != WindowsRelayCompanionStatus.UNAVAILABLE
        val phase = when (status) {
            WindowsRelayCompanionStatus.READY -> if (current != null) RelayLinkPhase.APPLIED else RelayLinkPhase.ERROR
            WindowsRelayCompanionStatus.WAITING -> RelayLinkPhase.WAITING_FOR_VPN
            WindowsRelayCompanionStatus.AUTHORIZATION_REQUIRED -> RelayLinkPhase.AUTHORIZATION_REQUIRED
            WindowsRelayCompanionStatus.DISABLED -> RelayLinkPhase.DISABLED
            WindowsRelayCompanionStatus.UNTRUSTED -> RelayLinkPhase.INCOMPATIBLE
            WindowsRelayCompanionStatus.STALE, WindowsRelayCompanionStatus.REJECTED -> RelayLinkPhase.ERROR
            else -> if (newGrant?.enabled == true) RelayLinkPhase.WAITING_FOR_VPN
                else if (installed) RelayLinkPhase.INCOMPATIBLE else RelayLinkPhase.NOT_INSTALLED
        }
        mutableState.update {
            it.copy(installed = installed, supported = installed && status !in setOf(null,
                WindowsRelayCompanionStatus.UNAVAILABLE, WindowsRelayCompanionStatus.UNTRUSTED),
                authorized = newGrant != null, enabled = newGrant?.enabled == true,
                providerPackage = WINDOWS_RELAY_PROVIDER.takeIf { installed },
                providerCertificate = WINDOWS_RELAY_AUTHORITY.takeIf { installed },
                grantId = newGrant?.id, phase = phase, vpnConnected = current != null,
                realRequestVerified = keepEvidence && it.realRequestVerified,
                verifiedAtMillis = it.verifiedAtMillis.takeIf { keepEvidence },
                verifiedMode = it.verifiedMode.takeIf { keepEvidence },
                error = when {
                    status == WindowsRelayCompanionStatus.UNTRUSTED -> "请从桌面的 $APP_DISPLAY_NAME 更新脚本启动支持联动的安装。"
                    phase == RelayLinkPhase.INCOMPATIBLE -> "请使用桌面的 Relay 更新脚本更新后台。"
                    phase == RelayLinkPhase.ERROR -> "Relay 联动状态已变化，请重新检测。"
                    else -> null
                })
        }
    }

    fun binding(mode: DownloadRouteMode): RelayRouteBinding? =
        grant?.takeIf { relayLinkAvailable && it.enabled && mode != DownloadRouteMode.SYSTEM }?.let {
            RelayRouteBinding(WINDOWS_RELAY_PROVIDER, WINDOWS_RELAY_AUTHORITY, it.id)
        }

    fun endpoint(binding: RelayRouteBinding, mode: DownloadRouteMode): RelayManagedEndpoint =
        runBlocking(Dispatchers.IO) {
            gate.withLock {
                if (this@DesktopRelayLink.binding(mode) != binding) throw DownloadRouteException("此任务的桌面 Relay 授权已失效。")
                val current = lease ?: throw DownloadRouteException("桌面 Relay 线路暂不可用，请连接 Relay 后重试。")
                if (current.grantId != binding.grantId) throw DownloadRouteException("Relay 授权已变化，请重试原任务。")
                endpoints[mode]?.let { return@withLock it }

                val reply = call { it.acquire(binding.grantId, mode) }
                val endpoint = reply?.endpoint
                if (reply?.status != WindowsRelayCompanionStatus.READY || endpoint == null ||
                    reply.bindingId != current.grantId || reply.generation != current.generation) {
                    refreshLocked()
                    throw DownloadRouteException("Relay 尚未确认原任务的线路，请重试。")
                }
                RelayManagedEndpoint("127.0.0.1", endpoint.port, endpoint.username, endpoint.password,
                    current.generation, endpoint.ownerPid, endpoint.ownerStartTicks).also { endpoints[mode] = it }
            }
        }

    fun observe(binding: RelayRouteBinding, mode: DownloadRouteMode, generation: String) {
        if (lease?.generation != generation ||
            state.value.realRequestVerified && state.value.verifiedMode == mode) return
        launch {
            if (this@DesktopRelayLink.binding(mode) != binding || lease?.generation != generation ||
                state.value.realRequestVerified && state.value.verifiedMode == mode) return@launch
            refreshLocked()
            if (this@DesktopRelayLink.binding(mode) == binding && lease?.generation == generation)
                mutableState.update { it.copy(realRequestVerified = true, verifiedMode = mode,
                    verifiedAtMillis = System.currentTimeMillis()) }
        }
    }

    val controller = RelayLinkController(this)
}

@Volatile private var desktopLink: DesktopRelayLink? = null

internal actual fun platformRelayLinkController(context: PlatformContext): RelayLinkController =
    desktopLink?.controller ?: synchronized(DesktopRelayLink::class.java) {
        desktopLink?.controller ?: DesktopRelayLink(context).also { desktopLink = it }.controller
    }

actual fun currentRelayBinding(mode: DownloadRouteMode): RelayRouteBinding? = desktopLink?.binding(mode)
actual fun resolveRelayEndpoint(binding: RelayRouteBinding, mode: DownloadRouteMode): RelayManagedEndpoint =
    desktopLink?.endpoint(binding, mode) ?: throw DownloadRouteException("桌面 Relay 线路尚未启动。")

actual fun recordRelayRequest(binding: RelayRouteBinding, mode: DownloadRouteMode, observationEpoch: String) {
    desktopLink?.observe(binding, mode, observationEpoch)
}
