package dev.mangaflow.net

import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class WindowsRelayCompanionStatus { UNAVAILABLE, UNTRUSTED, AUTHORIZATION_REQUIRED, WAITING, DISABLED, READY, STALE, REJECTED }

/** A live CONNECT endpoint, not serializable settings or a diagnostic value. Host is always loopback. */
class WindowsRelayCompanionEndpoint(
    val port: Int, val username: String, val password: String, val epoch: String,
    val ownerPid: Int, val ownerStartTicks: Long,
) {
    override fun toString(): String = "WindowsRelayCompanionEndpoint(redacted)"
}

class WindowsRelayCompanionResult(
    val status: WindowsRelayCompanionStatus,
    val endpoint: WindowsRelayCompanionEndpoint? = null,
    val bindingId: String? = null,
    val generation: String? = null,
)

interface WindowsRelayCompanionClient {
    suspend fun status(): WindowsRelayCompanionResult
    suspend fun authorize(): WindowsRelayCompanionResult
    suspend fun acquire(bindingId: String, mode: DownloadRouteMode): WindowsRelayCompanionResult
    suspend fun setEnabled(bindingId: String, enabled: Boolean): WindowsRelayCompanionResult
    suspend fun revoke(bindingId: String): WindowsRelayCompanionResult
}

private val windowsCompanion = AtomicReference<WindowsRelayCompanionClient?>()

/** Desktop entry point only. Install before creating application services; no replace/fallback. */
fun installWindowsRelayCompanion(client: WindowsRelayCompanionClient) {
    check(windowsCompanion.compareAndSet(null, client))
}

fun windowsRelayCompanionClient(): WindowsRelayCompanionClient? = windowsCompanion.get()

private var relayProvider: (() -> java.io.File?)? = null

fun installWindowsRelayActivation(path: () -> java.io.File?) {
    relayProvider = path
}

internal fun windowsRelayExecutable() = relayProvider?.invoke()

/** Windows startup only; the native verifier receives raw 100ns Windows FILETIME ticks. */
fun installWindowsRelaySocketPeerVerifier(verify: (Socket, Int, Long) -> Boolean) {
    installRelaySocketPeerVerifier { socket, pid, dotNetTicks ->
        val expected = fileTimeFor(dotNetTicks)
        pid > 0 && expected != null && verify(socket, pid, expected)
    }
}

private const val WINDOWS_FILETIME_OFFSET = 504_911_232_000_000_000L
private const val DOTNET_MAX_TICKS = 3_155_378_975_999_999_999L
private fun fileTimeFor(dotNetTicks: Long): Long? = dotNetTicks
    .takeIf { it > WINDOWS_FILETIME_OFFSET && it <= DOTNET_MAX_TICKS }?.minus(WINDOWS_FILETIME_OFFSET)

private const val VERSION = 2
private const val SCOPE = "mangaflow-network"
private const val FRAME_LIMIT = 4096
private val wireToken = Regex("[A-Za-z0-9_-]{1,128}")

private class WindowsRelayWireReply(
    val status: String,
    val bindingId: String?,
    val generation: String?,
    val endpoint: WindowsRelayWireEndpoint?,
)

private class WindowsRelayWireEndpoint(
    val port: Int,
    val username: String,
    val password: String,
    val epoch: String,
    val ownerPid: Int,
    val ownerStartTicks: Long,
)

/** The native exchange must hold the authenticated Windows service and pipe for the full round trip. */
fun framedWindowsRelayCompanionClient(
    exchange: suspend (request: ByteArray) -> ByteArray?,
): WindowsRelayCompanionClient = object : WindowsRelayCompanionClient {
    private val random by lazy { SecureRandom() }

    private val requests = Mutex()

    override suspend fun status() = call("status")
    override suspend fun authorize() = call("authorize")
    override suspend fun acquire(bindingId: String, mode: DownloadRouteMode) =
        call("acquire", bindingId, when (mode) {
            DownloadRouteMode.DIRECT -> "direct"
            DownloadRouteMode.PROXY -> "proxy"
            DownloadRouteMode.SYSTEM -> error("System routing uses no Relay endpoint")
        })
    override suspend fun setEnabled(bindingId: String, enabled: Boolean) = call(if (enabled) "resume" else "pause", bindingId)
    override suspend fun revoke(bindingId: String) = call("revoke", bindingId)

    private suspend fun call(operation: String, bindingId: String? = null, mode: String? = null): WindowsRelayCompanionResult =
        withContext(Dispatchers.IO) { requests.withLock {
            val id = HexFormat.of().formatHex(ByteArray(16).also(random::nextBytes))
            val sent = buildJsonObject {
                put("protocolVersion", VERSION)
                put("requestId", id)
                put("operation", operation)
                put("scope", SCOPE)
                if (bindingId != null) put("bindingId", bindingId)
                if (mode != null) put("mode", mode)
            }.toString().encodeToByteArray()
            val bytes = exchange(sent) ?: return@withLock reply(WindowsRelayCompanionStatus.UNAVAILABLE)
            try {
                if (bytes.size !in 1..FRAME_LIMIT) return@withContext reply(WindowsRelayCompanionStatus.REJECTED)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                val response = decodeWireReply(text, id) ?: return@withContext reply(WindowsRelayCompanionStatus.REJECTED)
                verifyResponse(operation, response)
            } catch (_: IllegalArgumentException) {
                reply(WindowsRelayCompanionStatus.REJECTED)
            } catch (_: java.nio.charset.CharacterCodingException) {
                reply(WindowsRelayCompanionStatus.REJECTED)
            } finally {
                bytes.fill(0)
            }
        } }
}

private val replyFields = setOf("protocolVersion", "requestId", "status", "bindingId", "generation", "endpoint")
private val endpointFields = setOf("port", "username", "password", "epoch", "ownerPid", "ownerStartTicks")

private fun decodeWireReply(text: String, id: String): WindowsRelayWireReply? {
    if (!hasUniqueWireFields(text)) return null
    val fields = Json.parseToJsonElement(text) as? JsonObject ?: return null
    if (!replyFields.containsAll(fields.keys) || fields.wireInt("protocolVersion") != VERSION ||
        fields.wireString("requestId") != id) return null
    val status = fields.wireString("status") ?: return null
    val endpoint = when (val item = fields["endpoint"]) {
        null, JsonNull -> null
        is JsonObject -> {
            if (item.keys != endpointFields) return null
            WindowsRelayWireEndpoint(item.wireInt("port") ?: return null,
                item.wireString("username") ?: return null, item.wireString("password") ?: return null,
                item.wireString("epoch") ?: return null,
                item.wireInt("ownerPid")?.takeIf { it > 0 } ?: return null,
                item.wireString("ownerStartTicks")?.toLongOrNull()
                    ?.takeIf { it.toString() == item.wireString("ownerStartTicks") && fileTimeFor(it) != null }
                    ?: return null)
        }
        else -> return null
    }
    return WindowsRelayWireReply(status, fields.wireString("bindingId"), fields.wireString("generation"), endpoint)
}

private fun JsonObject.wireString(key: String): String? = when (val value = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> value.content.takeIf { value.isString } ?: throw IllegalArgumentException()
    else -> throw IllegalArgumentException()
}

private fun JsonObject.wireInt(key: String): Int? {
    val raw = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content ?: return null
    return raw.toIntOrNull()?.takeIf { it.toString() == raw }
}

// The protocol has only ASCII unescaped field names/values and two possible object levels. Check
// before JsonObject, whose last-wins map would otherwise lose duplicate-key evidence.
private fun hasUniqueWireFields(text: String): Boolean {
    val objects = ArrayList<MutableSet<String>>(2)
    var index = 0
    while (index < text.length) {
        when (text[index]) {
            '{' -> {
                if (objects.size == 2) return false
                objects.add(mutableSetOf())
            }
            '}' -> {
                if (objects.isEmpty()) return false
                objects.removeAt(objects.lastIndex)
            }
            '[', ']', '\\' -> return false
            '"' -> {
                val end = text.indexOf('"', index + 1)
                if (end < 0 || text.substring(index + 1, end).contains('\\')) return false
                var following = end + 1
                while (following < text.length && text[following] in " \t\r\n") following++
                if (following < text.length && text[following] == ':') {
                    val scope = objects.lastOrNull() ?: return false
                    if (!scope.add(text.substring(index + 1, end))) return false
                }
                index = end
            }
        }
        index++
    }
    return objects.isEmpty()
}

private fun verifyResponse(operation: String, response: WindowsRelayWireReply): WindowsRelayCompanionResult {
    val status = when (response.status) {
        "unavailable" -> WindowsRelayCompanionStatus.UNAVAILABLE
        "untrusted" -> WindowsRelayCompanionStatus.UNTRUSTED
        "authorization_required" -> WindowsRelayCompanionStatus.AUTHORIZATION_REQUIRED
        "waiting" -> WindowsRelayCompanionStatus.WAITING
        "disabled" -> WindowsRelayCompanionStatus.DISABLED
        "stale" -> WindowsRelayCompanionStatus.STALE
        "rejected" -> WindowsRelayCompanionStatus.REJECTED
        "ready" -> WindowsRelayCompanionStatus.READY
        else -> return reply(WindowsRelayCompanionStatus.REJECTED)
    }
    if (status == WindowsRelayCompanionStatus.WAITING || status == WindowsRelayCompanionStatus.DISABLED) {
        val id = response.bindingId?.takeIf(wireToken::matches)
        return if (id != null && response.generation == null && response.endpoint == null)
            WindowsRelayCompanionResult(status, bindingId = id) else reply(WindowsRelayCompanionStatus.REJECTED)
    }
    if (status != WindowsRelayCompanionStatus.READY) {
        return if (response.endpoint == null && response.bindingId == null && response.generation == null) reply(status)
        else reply(WindowsRelayCompanionStatus.REJECTED)
    }
    val binding = response.bindingId?.takeIf(wireToken::matches) ?: return reply(WindowsRelayCompanionStatus.REJECTED)
    val generation = response.generation?.takeIf(wireToken::matches) ?: return reply(WindowsRelayCompanionStatus.REJECTED)
    val endpoint = response.endpoint
    if (operation != "acquire" && endpoint == null) return WindowsRelayCompanionResult(status, null, binding, generation)
    if (operation != "acquire" || endpoint == null || endpoint.port !in 1024..65535 ||
        endpoint.epoch != generation || !wireToken.matches(endpoint.username) || !wireToken.matches(endpoint.password))
        return reply(WindowsRelayCompanionStatus.REJECTED)
    return WindowsRelayCompanionResult(status,
        WindowsRelayCompanionEndpoint(endpoint.port, endpoint.username, endpoint.password,
            generation, endpoint.ownerPid, endpoint.ownerStartTicks), binding, generation)
}

private fun reply(status: WindowsRelayCompanionStatus) = WindowsRelayCompanionResult(status)
