package dev.mangaflow.update

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.net.DownloadRouteSnapshot

/** A verified plan for the latest file set, independent of how many releases were skipped. */
internal data class NativeIncrementalPlan(val token: String, val bytes: Long)

internal expect suspend fun planNativeIncrementalUpdate(
    context: PlatformContext, release: AppRelease, releaseMetadata: String, route: DownloadRouteSnapshot,
): NativeIncrementalPlan?
internal expect suspend fun downloadNativeIncrementalUpdate(
    context: PlatformContext, plan: NativeIncrementalPlan, route: DownloadRouteSnapshot, onProgress: (Long, Long) -> Unit,
)
internal expect fun installNativeIncrementalUpdate(context: PlatformContext, plan: NativeIncrementalPlan): Boolean
internal expect fun previousNativeUpdateResult(context: PlatformContext): String?
