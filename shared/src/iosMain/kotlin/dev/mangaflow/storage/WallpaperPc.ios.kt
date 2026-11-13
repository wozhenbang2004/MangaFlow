package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

actual fun createWallpaperPcClient(context: PlatformContext): WallpaperPcClient = object : WallpaperPcClient {
    override val state = MutableStateFlow(WallpaperPcState(supported = false,
        message = "电脑配对接收暂支持安卓与 Windows，iOS 可继续添加本地图片或视频。")).asStateFlow()
    override fun discover() = Unit
    override fun connect(device: WallpaperPcDevice, pin: String) = Unit
    override fun forget(device: WallpaperPcDevice) = Unit
    override fun disconnect() = Unit
    override fun close() = Unit
}
