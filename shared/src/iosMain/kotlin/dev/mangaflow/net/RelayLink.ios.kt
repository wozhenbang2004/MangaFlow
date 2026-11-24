package dev.mangaflow.net

import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private object UnavailableRelayLink : RelayPlatformLink {
    override val state = MutableStateFlow(RelayLinkState()).asStateFlow()
    override fun start(scope: CoroutineScope) = Unit
    override fun refresh() = Unit
    override fun requestAuthorization() = Unit
    override fun enable() = Unit
    override fun disable() = Unit
    override fun revoke() = Unit
    override fun openProvider() = Unit
    override fun updateSources(sources: Map<String, String>) = Unit
    val controller = RelayLinkController(this)
}

internal actual fun platformRelayLinkController(context: PlatformContext): RelayLinkController =
    UnavailableRelayLink.controller

actual fun currentRelayBinding(mode: DownloadRouteMode): RelayRouteBinding? = null
actual fun resolveRelayEndpoint(binding: RelayRouteBinding, mode: DownloadRouteMode): RelayManagedEndpoint =
    throw DownloadRouteException("此平台不支持 Relay 联动，原任务不能改用其他网络路径。")
actual fun recordRelayRequest(binding: RelayRouteBinding, mode: DownloadRouteMode, observationEpoch: String) = Unit
