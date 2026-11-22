package dev.mangaflow.sync

import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope

internal actual fun createLanReadingTransport(
    context: PlatformContext, scope: CoroutineScope, listener: LanReadingTransportListener,
): LanReadingTransport = object : LanReadingTransport {
    override val supported = false
    override suspend fun identity(): String = error("当前平台尚未支持局域网阅读同步。")
    override suspend fun start() = Unit
    override fun stop() = Unit
    override fun openPairing() = Unit
    override fun closePairing() = Unit
    override fun pair(deviceId: String) = Unit
    override fun confirm(session: String) = Unit
    override fun reject(session: String) = Unit
    override fun synchronize(force: Boolean) = Unit
    override suspend fun forget(deviceId: String) = Unit
    override fun isTrusted(deviceId: String) = false
}
