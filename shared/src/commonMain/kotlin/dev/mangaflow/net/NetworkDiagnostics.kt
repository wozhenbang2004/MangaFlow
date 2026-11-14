package dev.mangaflow.net

import dev.mangaflow.data.CopyAnonymousIdentityStore
import dev.mangaflow.data.CopyMangaConfig
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.monotonicTimeMillis
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
data class NetworkProbeResult(val route: DownloadRouteSnapshot, val millis: Long?, val message: String, val usable: Boolean)
data class NetworkDiagnosis(
    val target: NetworkProbeTarget,
    val results: List<NetworkProbeResult>,
    val recommended: DownloadRouteSnapshot?,
)

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
    val routes = listOfNotNull(DownloadRouteSnapshot(DownloadRouteMode.SYSTEM), DownloadRouteSnapshot(DownloadRouteMode.DIRECT), proxy?.let { DownloadRouteSnapshot(DownloadRouteMode.PROXY, it) }).distinct()
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
            callTimeoutMillis = 8_000, route = route)
    } catch (failure: DownloadRouteException) {
        return NetworkProbeResult(route, null, failure.message ?: "此线路不可用", false)
    }
    val samples = mutableListOf<Long>()
    try {
        repeat(2) {
            val started = monotonicTimeMillis()
            val response = try {
                withTimeout(8_500) { client.execute(request, 256L * 1024) }
            } catch (_: TimeoutCancellationException) {
                return NetworkProbeResult(route, samples.averageMillis(), "连接超时", false)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: DownloadRouteException) {
                return NetworkProbeResult(route, samples.averageMillis(), failure.message ?: "此线路不可用", false)
            } catch (_: Exception) {
                return NetworkProbeResult(route, samples.averageMillis(), "连接或响应读取失败", false)
            }
            val elapsed = (monotonicTimeMillis() - started).coerceAtLeast(0)
            if (response.code !in 200..299) {
                val reason = when (response.code) {
                    401, 403 -> "服务已响应，但拒绝访问（" + response.code + "）"
                    429 -> "服务已响应，当前限流"
                    in 300..399 -> "服务已响应，地址需要跳转"
                    else -> "服务返回 HTTP " + response.code
                }
                return NetworkProbeResult(route, samples.averageMillis(), reason, false)
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
            if (!valid) return NetworkProbeResult(route, samples.averageMillis(), "服务已响应，内容与接口不符", false)
            samples += elapsed
        }
        return NetworkProbeResult(route, samples.averageMillis(), "接口可用 · 2 次", true)
    } finally {
        client.close()
    }
}

private fun List<Long>.averageMillis(): Long? = if (isEmpty()) null else sum() / size
