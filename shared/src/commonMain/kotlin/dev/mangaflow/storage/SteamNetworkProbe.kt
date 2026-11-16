package dev.mangaflow.storage

import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.platform.PlatformContext

/** Anonymous CM connectivity only; this result says nothing about a content CDN or account. */
data class SteamNetworkProbeResult(
    val usable: Boolean,
    val supported: Boolean = true,
    val requiresPreparation: Boolean = false,
    val message: String,
    val responseMillis: Long? = null,
)

/** Uses an already prepared optional component and an independent, immutable-route native job. */
expect suspend fun probeSteamNetwork(
    context: PlatformContext,
    route: DownloadRouteSnapshot,
): SteamNetworkProbeResult
