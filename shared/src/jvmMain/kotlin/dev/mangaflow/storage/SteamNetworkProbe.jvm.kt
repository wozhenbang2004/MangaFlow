package dev.mangaflow.storage

import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.checked
import dev.mangaflow.net.resolveRelayEndpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Does not construct the account client, read its vault, or mutate any existing native lane. */
internal suspend fun runJvmSteamNetworkProbe(
    route: DownloadRouteSnapshot,
    loadInstalled: suspend () -> Boolean,
): SteamNetworkProbeResult = withContext(Dispatchers.IO) {
    try {
        withTimeout(24_000L) {
            val frozen = route.checked()
            if (!loadInstalled()) return@withTimeout missingSteamProbeComponent()
            currentCoroutineContext().ensureActive()
            // The encoded object and Relay credentials remain in JNI memory only.
            val request = routedSteamRequest(buildJsonObject { put("op", "probe") }, frozen)
            val handle = SteamNative.nativeStart(request.toString())
            if (handle <= 0) return@withTimeout SteamNetworkProbeResult(false,
                message = "Steam 连接组件正在使用，请稍后重试。")
            var connectedMillis: Long? = null
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    // A revoked/replaced Relay grant must close this actual job, never reroute it.
                    frozen.relayBinding?.let { resolveRelayEndpoint(it, frozen.mode) }
                    val raw = SteamNative.nativePoll(handle)
                    if (raw == null) { delay(40L); continue }
                    if (raw.length !in 1..8192) return@withTimeout invalidSteamProbeResponse()
                    val event = Json.parseToJsonElement(raw) as? JsonObject
                        ?: return@withTimeout invalidSteamProbeResponse()
                    fun value(key: String) = event[key] as? JsonPrimitive
                    when (value("kind")?.contentOrNull) {
                        "phase" -> Unit
                        "probeConnected" -> {
                            val millis = value("responseMillis")?.longOrNull
                            if (value("scope")?.contentOrNull != "steam_cm" ||
                                value("anonymous")?.booleanOrNull != true || millis == null || millis !in 0..20_000L)
                                return@withTimeout invalidSteamProbeResponse()
                            connectedMillis = millis
                        }
                        "complete" -> return@withTimeout if (connectedMillis != null)
                            SteamNetworkProbeResult(true, message = "Steam CM 匿名连接成功；内容下载尚未验证。",
                                responseMillis = connectedMillis)
                            else invalidSteamProbeResponse()
                        "error" -> return@withTimeout when (value("code")?.contentOrNull) {
                            "invalid_request" -> missingSteamProbeComponent(update = true)
                            "connection_timeout", "timeout" -> SteamNetworkProbeResult(false,
                                message = "Steam CM 连接超时，当前路线未能完成匿名连接。")
                            "network_configuration" -> SteamNetworkProbeResult(false,
                                message = "当前 Steam 路线尚未准备，请检查代理或 Relay 联动。")
                            "busy" -> SteamNetworkProbeResult(false, message = "Steam 连接组件正在使用，请稍后重试。")
                            else -> SteamNetworkProbeResult(false, message = "Steam CM 匿名连接未成功；账号与下载任务保持原状。")
                        }
                        else -> return@withTimeout invalidSteamProbeResponse()
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                invalidSteamProbeResponse()
            } finally {
                // Native free joins only this owned supervisor; cancellation cannot strand its socket.
                withContext(NonCancellable) {
                    runCatching { SteamNative.nativeCancel(handle) }
                    runCatching { SteamNative.nativeFree(handle) }
                }
            }
        }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        SteamNetworkProbeResult(false, message = "Steam CM 连接超时，当前检查已停止。")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: DownloadRouteException) {
        SteamNetworkProbeResult(false, message = "当前 Steam 路线不可用，请检查代理或 Relay 联动。")
    } catch (_: LinkageError) {
        missingSteamProbeComponent(update = true)
    } catch (_: Exception) {
        invalidSteamProbeResponse()
    }
}

internal fun missingSteamProbeComponent(update: Boolean = false) = SteamNetworkProbeResult(false,
    requiresPreparation = true,
    message = if (update) "Steam 连接组件需要更新，当前版本不支持只读连接检查。"
        else "请先在组件管理中准备 Steam 连接组件，再检查 CM 连接。")

private fun invalidSteamProbeResponse() = SteamNetworkProbeResult(false,
    message = "未取得有效的 Steam CM 连接回执，请稍后重试。")
