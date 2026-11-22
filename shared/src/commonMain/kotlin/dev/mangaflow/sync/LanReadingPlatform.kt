package dev.mangaflow.sync

import dev.mangaflow.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope

internal data class LanReadingDevice(val id: String, val name: String, val online: Boolean, val trusted: Boolean)
internal data class LanReadingPairing(
    val session: String, val name: String, val code: String, val confirmedHere: Boolean = false,
)
/** A source/authorization lease is rechecked immediately before handing metadata to the socket. */
internal data class LanReadingDocument(val payload: String, val stillAllowed: () -> Boolean)

internal interface LanReadingTransport {
    val supported: Boolean
    suspend fun identity(): String
    suspend fun start()
    fun stop()
    fun openPairing()
    fun closePairing()
    fun pair(deviceId: String)
    fun confirm(session: String)
    fun reject(session: String)
    fun synchronize(force: Boolean = false)
    suspend fun forget(deviceId: String)
    fun isTrusted(deviceId: String): Boolean
}

internal interface LanReadingTransportListener {
    fun devices(value: List<LanReadingDevice>)
    fun pairing(value: LanReadingPairing?)
    fun pairingOpen(value: Boolean)
    fun activity(deviceId: String?, busy: Boolean, error: String? = null)
    fun synchronized(deviceId: String)
    suspend fun document(deviceId: String): LanReadingDocument
    suspend fun merge(deviceId: String, payload: String): LanReadingDocument
}

internal expect fun createLanReadingTransport(
    context: PlatformContext, scope: CoroutineScope, listener: LanReadingTransportListener,
): LanReadingTransport
