package dev.mangaflow.update

import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.net.DownloadRouteSnapshot

internal actual suspend fun planNativeIncrementalUpdate(
    context: PlatformContext, release: AppRelease, releaseMetadata: String, route: DownloadRouteSnapshot,
): NativeIncrementalPlan? = null
internal actual suspend fun downloadNativeIncrementalUpdate(
    context: PlatformContext, plan: NativeIncrementalPlan, route: DownloadRouteSnapshot, onProgress: (Long, Long) -> Unit,
) { error("iOS updates use the signed distribution channel") }
internal actual fun installNativeIncrementalUpdate(context: PlatformContext, plan: NativeIncrementalPlan): Boolean = false
internal actual fun previousNativeUpdateResult(context: PlatformContext): String? = null
