package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun createSteamWallpaperClient(context: PlatformContext): SteamWallpaperClient = object : SteamWallpaperClient {
    override val state = MutableStateFlow(SteamWallpaperState(supported = false, restoring = false,
        message = "Steam 创意工坊暂支持安卓与 Windows，iOS 可继续添加本地图片或视频。")).asStateFlow()
    override fun login() = Unit
    override fun search(query: String, kind: SteamWallpaperKind, page: Int) = Unit
    override fun download(item: SteamWallpaperItem) = Unit
    override fun cancel() = Unit
    override fun logout() = Unit
    override fun close() = Unit
}
