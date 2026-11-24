package dev.mangaflow.net

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

private val desktopRelayWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
private val companionId = Regex("[A-Za-z0-9_-]{1,128}")
private const val cataloguePreference = "catalogue-mode"

private class DesktopRelayLink(context: PlatformContext) : RelayPlatformLink, DesktopRelayTunnelProvider {
    private val disk = preferences(context, "desktop-relay-companion")
    private val updates = Mutex()
    private val mutableState = MutableStateFlow(RelayLinkState(
        desktopCompanion = desktopRelayWindows,
        desktopCatalogueMode = if (desktopRelayWindows) savedMode() else DownloadRouteMode.SYSTEM,
    ))
    override val state = mutableState.asStateFlow()
    @Volatile private var lease: Pair<String, String>? = null
    @Volatile private var scope: CoroutineScope? = null
    private var observer: Job? = null

    private fun savedMode() = disk.getString(cataloguePreference)?.let { name ->
        DownloadRouteMode.entries.firstOrNull { it.name == name }
    } ?: DownloadRouteMode.SYSTEM

    @Synchronized
    override fun start(scope: CoroutineScope) {
        if (!desktopRelayWindows || observer?.isActive == true) return
        this.scope = scope
        DesktopRelayTransport.install(this)
        observer = scope.launch(Dispatchers.IO) {
            while (true) {
                updates.withLock { updateStatus() }
                delay(12_000L)
            }
        }
    }

    override fun refresh() {
        scope?.launch(Dispatchers.IO) { updates.withLock { updateStatus() } }
    }

    override fun setDesktopCatalogueMode(mode: DownloadRouteMode) {
        if (!desktopRelayWindows) return
        scope?.launch(Dispatchers.IO) {
            updates.withLock {
                if (mode != DownloadRouteMode.SYSTEM) {
                    updateStatus()
                    if (lease == null) {
                        mutableState.update { it.copy(error = "Relay 尚未确认目录授权，未更改线路。") }
                        return@withLock
                    }
                }
                if (!disk.edit().putString(cataloguePreference, mode.name).commit()) {
                    mutableState.update { it.copy(error = "未能保存桌面 Relay 目录线路。") }
                    return@withLock
                }
                mutableState.update { it.copy(desktopCatalogueMode = mode,
                    enabled = mode != DownloadRouteMode.SYSTEM && lease != null,
                    realRequestVerified = false, verifiedAtMillis = null, verifiedMode = null, error = null) }
            }
        }
    }

    override fun desktopCatalogueRoute(): DownloadRouteSnapshot? {
        val mode = mutableState.value.desktopCatalogueMode
        if (!desktopRelayWindows || mode == DownloadRouteMode.SYSTEM) return null
        val current = lease ?: throw DownloadRouteException("桌面 Relay 目录线路当前不可用；尚未请求原站。")
        return DownloadRouteSnapshot(mode, desktopRelayBinding = DesktopRelayRouteBinding(current.first, current.second, mode))
    }

    override fun isCurrent(binding: DesktopRelayRouteBinding): Boolean {
        val current = lease ?: return false
        return mutableState.value.desktopCatalogueMode == binding.mode &&
            binding.mode != DownloadRouteMode.SYSTEM && binding.bindingId == current.first && binding.generation == current.second
    }

    override fun open(binding: DesktopRelayRouteBinding): DesktopRelayEndpoint {
        if (!isCurrent(binding)) throw DownloadRouteException("桌面 Relay 目录线路已失效。")
        // Only the socket factory calls this on the OkHttp connection worker. No UI thread IPC.
        val response = runBlocking {
            windowsRelayCompanionClient()?.acquire(if (binding.mode == DownloadRouteMode.DIRECT)
                WindowsRelayCompanionMode.DIRECT else WindowsRelayCompanionMode.PROXY)
        }
        val entry = response?.endpoint
        if (response?.status != WindowsRelayCompanionStatus.READY || entry == null ||
            response.bindingId != binding.bindingId || response.generation != binding.generation ||
            entry.epoch != binding.generation || !isCurrent(binding)) {
            refresh()
            throw DownloadRouteException("桌面 Relay 未确认原目录线路；未改走其他线路。")
        }
        return DesktopRelayEndpoint(binding.bindingId, binding.generation, binding.mode,
            "127.0.0.1", entry.port, entry.username, entry.password, entry.ownerPid, entry.ownerStartTicks)
    }

    private suspend fun updateStatus() {
        val response = companionCall { windowsRelayCompanionClient()?.status() }
        val oldLease = lease
        val found = response?.status
        val id = response?.bindingId
        val generation = response?.generation
        val live = if (found == WindowsRelayCompanionStatus.READY && id != null && generation != null &&
            companionId.matches(id) && companionId.matches(generation)) id to generation else null
        lease = live
        val phase = when {
            live != null -> RelayLinkPhase.APPLIED
            found == WindowsRelayCompanionStatus.UNAUTHORIZED -> RelayLinkPhase.AUTHORIZATION_REQUIRED
            found == WindowsRelayCompanionStatus.REJECTED || found == WindowsRelayCompanionStatus.STALE -> RelayLinkPhase.ERROR
            else -> RelayLinkPhase.NOT_AVAILABLE
        }
        mutableState.update {
            val keepEvidence = live != null && oldLease == live
            val recognized = live != null || found == WindowsRelayCompanionStatus.UNAUTHORIZED ||
                found == WindowsRelayCompanionStatus.STALE
            it.copy(installed = recognized, supported = recognized,
                authorized = live != null, enabled = live != null && it.desktopCatalogueMode != DownloadRouteMode.SYSTEM,
                phase = phase,
                desktopCatalogueEpoch = live?.let { (id, generation) -> "$id:$generation" },
                realRequestVerified = keepEvidence && it.realRequestVerified,
                verifiedAtMillis = it.verifiedAtMillis.takeIf { keepEvidence },
                verifiedMode = it.verifiedMode.takeIf { keepEvidence })
        }
    }

    // The client may ask Relay to revoke a previous grant but cannot create its own authorization.
    override fun revoke() {
        scope?.launch(Dispatchers.IO) {
            updates.withLock {
                lease = null
                companionCall { windowsRelayCompanionClient()?.revoke() }
                updateStatus()
            }
        }
    }

    private suspend fun companionCall(block: suspend () -> WindowsRelayCompanionResult?): WindowsRelayCompanionResult? =
        try { block() } catch (failure: CancellationException) { throw failure } catch (_: Exception) { null }

    override fun requestAuthorization() = refresh()
    override fun enable() = refresh()
    override fun disable() = setDesktopCatalogueMode(DownloadRouteMode.SYSTEM)
    override fun openProvider() = Unit
    override fun updateSources(sources: Map<String, String>) = Unit
}

@Volatile private var desktopRelayController: RelayLinkController? = null

internal actual fun platformRelayLinkController(context: PlatformContext): RelayLinkController =
    desktopRelayController ?: synchronized(DesktopRelayLink::class.java) {
        desktopRelayController ?: RelayLinkController(DesktopRelayLink(context)).also { desktopRelayController = it }
    }

// Desktop's restricted catalogue link is deliberately not a general Android managed route.
actual fun currentRelayBinding(mode: DownloadRouteMode): RelayRouteBinding? = null

actual fun resolveRelayEndpoint(binding: RelayRouteBinding, mode: DownloadRouteMode): RelayManagedEndpoint =
    throw DownloadRouteException("桌面端不能使用旧版 Android Relay 联动线路。")

actual fun recordRelayRequest(binding: RelayRouteBinding, mode: DownloadRouteMode, observationEpoch: String) = Unit
