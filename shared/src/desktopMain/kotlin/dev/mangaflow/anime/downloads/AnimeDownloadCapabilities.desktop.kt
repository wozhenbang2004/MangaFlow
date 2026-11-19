package dev.mangaflow.anime.downloads

import dev.mangaflow.platform.PlatformContext

internal actual fun animeOfflineVideoContainers(): Set<String> = setOf("MP4", "MKV", "WebM", "TS")
internal actual fun animeSystemDownloadExecution() = false
internal actual fun animeProcessDownloadExecution() = true
internal actual fun scheduleAnimeDownloadExecution(context: PlatformContext, recover: Boolean) = Unit
