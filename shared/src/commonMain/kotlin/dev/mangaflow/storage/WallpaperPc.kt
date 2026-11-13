package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.flow.StateFlow

data class WallpaperPcDevice(
    val id: String,
    val name: String,
    val address: String,
    val port: Int = 7889,
    val version: Int = 4,
    val paired: Boolean = false,
)

data class WallpaperPcState(
    val supported: Boolean = true,
    val discovering: Boolean = false,
    val connecting: Boolean = false,
    val connectedName: String? = null,
    val devices: List<WallpaperPcDevice> = emptyList(),
    val transferName: String? = null,
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val importedCount: Int = 0,
    val message: String? = null,
    val error: String? = null,
)

interface WallpaperPcClient {
    val state: StateFlow<WallpaperPcState>
    fun discover()
    fun connect(device: WallpaperPcDevice, pin: String)
    fun forget(device: WallpaperPcDevice)
    fun disconnect()
    fun close()
}

expect fun createWallpaperPcClient(context: PlatformContext): WallpaperPcClient
