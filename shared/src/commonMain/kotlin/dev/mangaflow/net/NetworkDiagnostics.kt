package dev.mangaflow.net

import dev.mangaflow.data.CopyAnonymousIdentityStore
import dev.mangaflow.data.CopyMangaConfig
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class NetworkProbeTarget { CATALOG, UPDATE }
enum class NetworkProbeFailure(val label: String) {
    ROUTE("线路配置不可用"), NO_NETWORK("设备当前没有可用网络"), DNS("无法解析服务地址"),
    CONNECTION("无法连接服务"), TLS("安全连接校验失败"), CONNECT_TIMEOUT("连接服务超时"),
    RESPONSE_TIMEOUT("等待服务响应超时"), TRANSFER_TIMEOUT("响应传输已停止"),
    REQUEST_TIMEOUT("本次检查超时"), AUTH("服务拒绝访问"), RATE_LIMIT("服务限制请求"),
    SOURCE_BUSY("服务暂时繁忙"), REDIRECT("服务地址需要跳转"), HTTP("服务返回错误"),
    INVALID_RESPONSE("响应内容与接口不符"), UNKNOWN("连接或响应读取失败"),
}

data class NetworkProbeResult(
    val route: DownloadRouteSnapshot,
    val millis: Long?,
    val message: String,
    val usable: Boolean,
    val failureKind: NetworkProbeFailure? = null,
    val httpStatus: Int? = null,
    /** Actual attempt duration, including body reads; never a DNS/TLS/bandwidth measurement. */
    val responseMillis: Long? = null,
    val retryAt: Long? = null,
    val samples: Int = 0,
)
data class NetworkDiagnosis(
    val target: NetworkProbeTarget,
    val results: List<NetworkProbeResult>,
    val recommended: DownloadRouteSnapshot?,
    val checkedAt: Long = wallTimeMillis(),
) {
    /** An allowlist report: no URLs, proxy endpoints, identities, headers or exception chains. */
    fun safeReport(): String = buildString {
        appendLine("MangaFlow 连接检查")
        appendLine("目标：" + when (target) { NetworkProbeTarget.CATALOG -> "漫画目录接口"; NetworkProbeTarget.UPDATE -> "更新接口" })
        appendLine("检查时间（Unix ms）：$checkedAt")
        results.forEach { result ->
            append(when (result.route.mode) {
                DownloadRouteMode.SYSTEM -> "跟随系统"
                DownloadRouteMode.DIRECT -> "应用直连"
                DownloadRouteMode.PROXY -> "指定代理"
            })
            append("：").append(result.failureKind?.label ?: if (result.usable) "接口可用" else "状态未确认")
            result.httpStatus?.let { append(" · HTTP ").append(it) }
            result.responseMillis?.let { append(" · 本次总耗时 ").append(it).append(" ms") }
            if (result.samples > 0) append(" · 已验证响应 ").append(result.samples).append(" 次")
            result.retryAt?.let { append(" · 允许重试时间（Unix ms）").append(it) }
            appendLine()
        }
        append("以上仅代表所选接口；不证明图片 CDN 可用、网速或已绕过系统 VPN。")
    }
}

/** Runs only from the explicit settings action, using known public app services and configured routes. */
suspend fun diagnoseNetwork(context: PlatformContext, target: NetworkProbeTarget, proxy: DownloadProxyEndpoint?): NetworkDiagnosis = withContext(Dispatchers.IO) {
    val request = when (target) {
        NetworkProbeTarget.CATALOG -> Request.Builder()
            .url(CopyMangaConfig().apiOrigin + "/api/v3/comics?free_type=1&limit=1&offset=0&ordering=-popular&platform=3&in_mainland=false&request_id=")
            .apply { CopyAnonymousIdentityStore.load(context).headers().forEach { (name, value) -> header(name, value) } }
            .build()
        NetworkProbeTarget.UPDATE -> Request.Builder()
            .url("https://api.github.com/repos/wozhenbang2004/MangaFlow/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MangaFlow-Connection-Check")
            .build()
    }
    val routes = listOfNotNull(
        DownloadRouteSnapshot(DownloadRouteMode.SYSTEM),
        DownloadRouteSnapshot(DownloadRouteMode.DIRECT, relayBinding = currentRelayBinding(DownloadRouteMode.DIRECT)),
        proxy?.let { DownloadRouteSnapshot(DownloadRouteMode.PROXY, it) }
            ?: currentRelayBinding(DownloadRouteMode.PROXY)?.let { DownloadRouteSnapshot(DownloadRouteMode.PROXY, relayBinding = it) },
    ).distinct()
    val gate = Semaphore(2)
    val results = coroutineScope {
        routes.map { route -> async { gate.withPermit { probeRoute(target, route, request) } } }.awaitAll()
    }
    val available = results.filter { it.usable && it.millis != null }
    val fastest = available.minByOrNull { requireNotNull(it.millis) }
    // Keep the system route when its small probe is practically tied, rather than suggest churn.
    val system = available.firstOrNull { it.route.mode == DownloadRouteMode.SYSTEM }
    val recommended = if (system != null && fastest != null &&
        requireNotNull(system.millis) <= requireNotNull(fastest.millis) + maxOf(40L, requireNotNull(fastest.millis) / 6)) {
        system.route
    } else fastest?.route
    NetworkDiagnosis(target, results, recommended)
}

private suspend fun probeRoute(target: NetworkProbeTarget, route: DownloadRouteSnapshot, request: Request): NetworkProbeResult {
    val client = try {
        NetworkClient(connectTimeoutMillis = 4_000, readTimeoutMillis = 6_000,
            callTimeoutMillis = 8_000, route = route, observeRelayTraffic = false)
    } catch (failure: DownloadRouteException) {
        return NetworkProbeResult(route, null, failure.message ?: "此线路不可用", false,
            failureKind = NetworkProbeFailure.ROUTE)
    }
    val samples = mutableListOf<Long>()
    try {
        repeat(2) {
            val started = monotonicTimeMillis()
            val response = try {
                withTimeout(8_500) { client.execute(request, 256L * 1024) }
            } catch (_: TimeoutCancellationException) {
                return NetworkProbeResult(route, null, "本次检查超时", false,
                    failureKind = NetworkProbeFailure.REQUEST_TIMEOUT,
                    responseMillis = (monotonicTimeMillis() - started).coerceAtLeast(0), samples = samples.size)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: DownloadRouteException) {
                return NetworkProbeResult(route, null, failure.message ?: "此线路不可用", false,
                    failureKind = NetworkProbeFailure.ROUTE,
                    responseMillis = (monotonicTimeMillis() - started).coerceAtLeast(0), samples = samples.size)
            } catch (failure: Exception) {
                val kind = when (networkFailureKind(failure)) {
                    NetworkFailureKind.NO_NETWORK -> NetworkProbeFailure.NO_NETWORK
                    NetworkFailureKind.DNS -> NetworkProbeFailure.DNS
                    NetworkFailureKind.CONNECTION -> NetworkProbeFailure.CONNECTION
                    NetworkFailureKind.TLS -> NetworkProbeFailure.TLS
                    NetworkFailureKind.CONNECT_TIMEOUT -> NetworkProbeFailure.CONNECT_TIMEOUT
                    NetworkFailureKind.RESPONSE_TIMEOUT -> NetworkProbeFailure.RESPONSE_TIMEOUT
                    NetworkFailureKind.TRANSFER_TIMEOUT -> NetworkProbeFailure.TRANSFER_TIMEOUT
                    NetworkFailureKind.REQUEST_TIMEOUT -> NetworkProbeFailure.REQUEST_TIMEOUT
                    null -> NetworkProbeFailure.UNKNOWN
                }
                return NetworkProbeResult(route, null, kind.label, false, failureKind = kind,
                    responseMillis = (monotonicTimeMillis() - started).coerceAtLeast(0), samples = samples.size)
            }
            val elapsed = (monotonicTimeMillis() - started).coerceAtLeast(0)
            if (response.code !in 200..299) {
                val reason = when (response.code) {
                    401, 403 -> "服务已响应，但拒绝访问（" + response.code + "）"
                    429 -> "服务已响应，当前限流"
                    503 -> "服务已响应，暂时繁忙"
                    in 300..399 -> "服务已响应，地址需要跳转"
                    else -> "服务返回 HTTP " + response.code
                }
                val kind = when (response.code) {
                    401, 403 -> NetworkProbeFailure.AUTH
                    429 -> NetworkProbeFailure.RATE_LIMIT
                    503 -> NetworkProbeFailure.SOURCE_BUSY
                    in 300..399 -> NetworkProbeFailure.REDIRECT
                    else -> NetworkProbeFailure.HTTP
                }
                val retryAt = if (response.code in setOf(429, 503))
                    retryAfterMillis(response.header("Retry-After"))?.let { wallTimeMillis() + it } else null
                return NetworkProbeResult(route, null, reason, false, kind, response.code,
                    responseMillis = elapsed, retryAt = retryAt, samples = samples.size)
            }
            val valid = runCatching {
                val json = JSONObject(response.text())
                when (target) {
                    NetworkProbeTarget.CATALOG -> json.optInt("code", -1) == 200 &&
                        json.optJSONObject("results")?.optJSONArray("list") != null
                    NetworkProbeTarget.UPDATE -> json.optString("tag_name").isNotBlank() &&
                        json.optJSONArray("assets") != null
                }
            }.getOrDefault(false)
            if (!valid) return NetworkProbeResult(route, null, "服务已响应，内容与接口不符", false,
                failureKind = NetworkProbeFailure.INVALID_RESPONSE, httpStatus = response.code,
                responseMillis = elapsed, samples = samples.size)
            samples += elapsed
        }
        return NetworkProbeResult(route, samples.averageMillis(), "接口可用 · 2 次", true,
            responseMillis = samples.lastOrNull(), samples = samples.size)
    } finally {
        client.close()
    }
}

private fun List<Long>.averageMillis(): Long? = if (isEmpty()) null else sum() / size
