package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

internal val relayLinkAvailable: Boolean get() = platformKind == PlatformKind.ANDROID

enum class RelayLinkPhase {
    NOT_AVAILABLE, NOT_INSTALLED, INCOMPATIBLE, AUTHORIZATION_REQUIRED, AUTHORIZING,
    DISABLED, CONNECTING, WAITING_FOR_VPN, SYNCING, SAVED, APPLIED, CONFLICT, ERROR,
}

/** Public presentation state. Local-entry credentials never enter this snapshot or persistence. */
data class RelayLinkState(
    val installed: Boolean = false,
    val supported: Boolean = false,
    val authorized: Boolean = false,
    val enabled: Boolean = false,
    val providerPackage: String? = null,
    val providerCertificate: String? = null,
    val grantId: String? = null,
    val savedRevision: Long = 0,
    val appliedRevision: Long = 0,
    val vpnConnected: Boolean = false,
    val phase: RelayLinkPhase = RelayLinkPhase.NOT_AVAILABLE,
    val ruleCount: Int = 0,
    val conflicts: List<String> = emptyList(),
    val error: String? = null,
    val realRequestVerified: Boolean = false,
    val verifiedAtMillis: Long? = null,
    val verifiedMode: DownloadRouteMode? = null,
)

/** Short-lived authenticated loopback entry; never log, serialize or put it in a route snapshot. */
class RelayManagedEndpoint(val host: String, val port: Int, val username: String, val password: String,
    val observationEpoch: String) {
    override fun toString(): String = "RelayManagedEndpoint(redacted)"
}

class RelayLinkController internal constructor(private val platform: RelayPlatformLink) {
    val state: StateFlow<RelayLinkState> get() = platform.state
    fun start(scope: CoroutineScope) = platform.start(scope)
    fun refresh() = platform.refresh()
    fun requestAuthorization() = platform.requestAuthorization()
    fun enable() = platform.enable()
    fun disable() = platform.disable()
    fun retry() = platform.refresh()
    fun revoke() = platform.revoke()
    fun openProvider() = platform.openProvider()
    /** Only installed source metadata is supplied; CDN domains are never inferred from a title. */
    fun updateSources(sources: Map<String, String>) = platform.updateSources(sources)

    companion object {
        fun get(context: PlatformContext): RelayLinkController = platformRelayLinkController(context)
    }
}

internal interface RelayPlatformLink {
    val state: StateFlow<RelayLinkState>
    fun start(scope: CoroutineScope)
    fun refresh()
    fun requestAuthorization()
    fun enable()
    fun disable()
    fun revoke()
    fun openProvider()
    fun updateSources(sources: Map<String, String>)
}

internal expect fun platformRelayLinkController(context: PlatformContext): RelayLinkController

/** An enabled grant survives a temporary disconnect so frozen requests cannot silently change path. */
expect fun currentRelayBinding(mode: DownloadRouteMode): RelayRouteBinding?
expect fun resolveRelayEndpoint(binding: RelayRouteBinding, mode: DownloadRouteMode): RelayManagedEndpoint

/** Actual origin response observation only; it does not claim a public IP or geographic exit. */
expect fun recordRelayRequest(binding: RelayRouteBinding, mode: DownloadRouteMode, observationEpoch: String)
