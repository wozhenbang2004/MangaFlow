package dev.mangaflow.net

import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.data.plugins.scriptSourceImageEpoch
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.storage.WebDavBackupStore
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class NetworkAutoRouteStatus { AVAILABLE, FAILED, NOT_CONFIGURED, LOGIN_REQUIRED, UNSUPPORTED, CANCELLED, PARTIAL }

data class NetworkAutoRouteResult(
    val status: NetworkAutoRouteStatus,
    val millis: Long? = null,
    val samples: Int = 0,
    val message: String,
    val httpStatus: Int? = null,
) {
    val usable: Boolean get() = status == NetworkAutoRouteStatus.AVAILABLE
}

data class NetworkAutoRow(
    val id: String,
    val label: String,
    val currentRoute: DownloadRouteSnapshot?,
    val direct: NetworkAutoRouteResult? = null,
    val proxy: NetworkAutoRouteResult? = null,
    val recommendation: DownloadRouteMode? = null,
    val reason: String = "等待检查",
    val coverage: String,
    val category: DownloadCategory? = null,
    val sourceId: String? = null,
)

data class NetworkAutoCheckState(
    val rows: List<NetworkAutoRow> = emptyList(),
    val completed: Int = 0,
    val total: Int = 0,
    val running: Boolean = false,
    val complete: Boolean = false,
    val stale: Boolean = false,
    val error: String? = null,
    val applying: Boolean = false,
    val staleReason: String? = null,
    /** Saved changes consume this review; retained measurements remain historical evidence. */
    val applied: Boolean = false,
    val activeProbeIds: Set<String> = emptySet(),
)

data class NetworkAutoApplyResult(val applied: Int, val message: String, val success: Boolean, val stale: Boolean = false)

/** The caller owns the run coroutine. Results never mutate routes until an explicit reviewed apply. */
class NetworkAutoCheckController(
    context: PlatformContext,
    repository: UnifiedMangaRepository?,
    registry: BodySourceRegistry?,
) {
    private val engine = NetworkAutoCheckEngine(context, repository, registry)
    val state: StateFlow<NetworkAutoCheckState> get() = engine.state
    suspend fun run() = engine.run()
    fun cancel() = engine.cancel()
    fun invalidate(reason: String) = engine.invalidate(reason)
    suspend fun apply(selected: Map<String, DownloadRouteMode>): NetworkAutoApplyResult = engine.apply(selected)
    fun close() = engine.close()
}

private data class AutoFence(val settings: DownloadRoutingSettings, val source: String, val resources: String,
    val network: Long, val environment: DownloadNetworkEnvironment?, val relay: String)
private data class AutoPlan(val fence: AutoFence, val targets: List<AutoTarget>,
    val candidates: Map<Pair<String, DownloadRouteMode>, AutoCandidate>)

private class NetworkAutoCheckEngine(context: PlatformContext, private val repository: UnifiedMangaRepository?,
    private val registry: BodySourceRegistry?) {
    private val app = applicationContext(context)
    private val store = DownloadRoutingStore.get(app)
    private val relay = RelayLinkController.get(app)
    private val plugins = SourcePluginStore.get(app)
    private val webdav = WebDavBackupStore(app)
    private val lock = StorageLock()
    private val mutableState = MutableStateFlow(NetworkAutoCheckState())
    val state = mutableState.asStateFlow()
    private var generation = 0L
    private var networkRevision = 0L
    private var runningJob: Job? = null
    private var closed = false
    private var plan: AutoPlan? = null
    private val releaseNetwork = runCatching { observeDownloadNetworkEnvironment(app) {
        synchronized(lock) { networkRevision++ }
        if (state.value.running || state.value.complete) invalidate("网络环境已变化，请重新检测")
    } }.getOrElse { {} }

    suspend fun run(): Unit = coroutineScope {
        val ownJob = currentCoroutineContext()[Job]
        val run = synchronized(lock) {
            if (closed || runningJob != null || state.value.applying) return@coroutineScope
            (++generation).also {
                runningJob = ownJob; plan = null
                mutableState.value = NetworkAutoCheckState(running = true)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val frozen = withTimeout(20_000L) {
                    plugins.ready.await()
                    val captured = store.state.value
                    val fence = captureFence(captured)
                    val targets = autoTargets(app, repository, registry, captured, webdav)
                    val candidates = targets.flatMap { target -> MODES.map { mode ->
                        (target.id to mode) to autoCandidate(captured, target, mode)
                    } }.toMap()
                    AutoPlan(fence, targets, candidates)
                }
                val captured = frozen.fence.settings
                val targets = frozen.targets
                val candidates = frozen.candidates
                if (!checkCurrent(run, frozen)) return@withContext
                synchronized(lock) { if (generation == run) plan = frozen }
                mutableState.update { previous -> if (current(run)) previous.copy(
                    rows = targets.map { target -> NetworkAutoRow(target.id, target.label, autoCurrentRoute(captured, target),
                        coverage = target.coverage, category = target.category, sourceId = target.sourceId) },
                    total = targets.size) else previous }
                val gate = Semaphore(4)
                try {
                    withTimeout(240_000L) {
                        coroutineScope {
                            targets.filterNot { it.aggregate }.map { target -> async {
                                gate.withPermit {
                                    if (!checkCurrent(run, frozen)) return@withPermit
                                    for (mode in MODES) {
                                        if (!checkCurrent(run, frozen)) return@withPermit
                                        val key = target.id + ":" + mode.name
                                        updateRun(run) { it.copy(activeProbeIds = it.activeProbeIds + key) }
                                        val candidate = candidates.getValue(target.id to mode)
                                        val measured = try { sample(target, candidate) }
                                        finally { updateRun(run) { it.copy(activeProbeIds = it.activeProbeIds - key) } }
                                        val result = if (mode == DownloadRouteMode.DIRECT && candidate.route?.relayBinding == null &&
                                            frozen.fence.environment?.defaultNetworkUsesVpn == true) measured.copy(
                                                message = measured.message + "；应用直连仍受系统 VPN 规则影响") else measured
                                        publish(run, target.id) { row -> if (mode == DownloadRouteMode.DIRECT) row.copy(direct = result)
                                            else row.copy(proxy = result) }
                                        if (!checkCurrent(run, frozen)) return@withPermit
                                    }
                                    publish(run, target.id) { row ->
                                        if (frozen.fence.environment?.defaultNetworkUsesVpn == true &&
                                            candidates[target.id to DownloadRouteMode.DIRECT]?.route?.relayBinding == null)
                                            row.copy(reason = "外部 VPN 仍控制应用直连，未证明独立出口，本轮不自动推荐")
                                        else recommended(row)
                                    }
                                    updateRun(run) { it.copy(completed = it.rows.count(::finished)) }
                                }
                            } }.awaitAll()
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    stopUnfinished("本轮时间预算已用完，未完成项可重新检测", stale = false, run)
                    return@withContext
                }
                if (!checkCurrent(run, frozen)) return@withContext
                targets.filter { it.aggregate }.forEach { target ->
                    val dependent = state.value.rows.filter { it.id != target.id &&
                        (target.category == null && it.category != DownloadCategory.CHAPTERS || target.category == DownloadCategory.CHAPTERS && it.sourceId != null) }
                    fun aggregate(mode: DownloadRouteMode): NetworkAutoRouteResult {
                        val measured = dependent.mapNotNull { if (mode == DownloadRouteMode.DIRECT) it.direct else it.proxy }
                        val good = measured.count { it.usable && it.samples == 2 }
                        return NetworkAutoRouteResult(NetworkAutoRouteStatus.PARTIAL, samples = 0,
                            message = if (target.category == DownloadCategory.CHAPTERS) "${good} 个来源目录可用；章节图片尚未验证"
                            else "${good} 项已验证，可能使用各自代理；全局覆盖不足，保留默认")
                    }
                    publish(run, target.id) { it.copy(direct = aggregate(DownloadRouteMode.DIRECT),
                        proxy = aggregate(DownloadRouteMode.PROXY), reason = "覆盖范围不足以修改这一整类，保留当前设置") }
                }
                updateRun(run) { it.copy(completed = it.rows.count(::finished), running = false,
                    complete = it.rows.all(::finished), activeProbeIds = emptySet()) }
            }
        } catch (_: TimeoutCancellationException) {
            stopUnfinished("检查目标准备超时，请重试", stale = false, run = run)
        } catch (cancel: CancellationException) {
            stopUnfinished("检查已取消，已完成结果保留", stale = false, run)
            throw cancel
        } catch (_: Exception) {
            stopUnfinished("检查计划暂未准备完成，请重试", stale = false, run)
        } finally {
            synchronized(lock) { if (generation == run) runningJob = null }
            updateRun(run) { it.copy(running = false, activeProbeIds = emptySet()) }
        }
    }

    fun cancel() {
        val (job, stoppedGeneration) = synchronized(lock) { generation++; (runningJob to generation).also { runningJob = null } }
        job?.cancel()
        stopUnfinished("检查已取消，已完成结果保留", stale = false, stoppedGeneration)
    }

    fun invalidate(reason: String) {
        if (state.value.applied || state.value.applying) return
        val (job, stoppedGeneration) = synchronized(lock) { generation++; (runningJob to generation).also { runningJob = null } }
        job?.cancel()
        stopUnfinished(reason, stale = true, stoppedGeneration)
    }

    fun close() {
        synchronized(lock) { closed = true }
        cancel()
        releaseNetwork()
        plan = null
    }

    suspend fun apply(selected: Map<String, DownloadRouteMode>): NetworkAutoApplyResult = withContext(Dispatchers.IO) {
        val frozen = synchronized(lock) {
            if (closed || !state.value.complete || state.value.stale || state.value.running || state.value.applying || state.value.applied) null
            else plan?.also { mutableState.update { it.copy(applying = true) } }
        } ?: return@withContext NetworkAutoApplyResult(0, "请先完成本轮检测后再确认设置", false, state.value.stale)
        try {
            if (!fresh(frozen)) {
                markApplyStale("设置、来源、联动会话或网络已变化，请重新检测")
                return@withContext NetworkAutoApplyResult(0, "检测依据已变化，未修改任何配置", false, true)
            }
            val categories = linkedMapOf<DownloadCategory, DownloadRouteMode>()
            val sources = linkedMapOf<String, SourceNetworkSettings>()
            var default: DownloadRouteMode? = null
            var changed = 0
            for ((id, mode) in selected) {
                val target = frozen.targets.singleOrNull { it.id == id }
                    ?: return@withContext NetworkAutoApplyResult(0, "选择中包含未检测的项目，未保存", false)
                val row = state.value.rows.singleOrNull { it.id == id }
                    ?: return@withContext NetworkAutoApplyResult(0, "检测结果已失效，未保存", false, true)
                val result = when (mode) { DownloadRouteMode.DIRECT -> row.direct; DownloadRouteMode.PROXY -> row.proxy; else -> null }
                if (target.aggregate || result?.usable != true || result.samples != 2)
                    return@withContext NetworkAutoApplyResult(0, "只能应用完整验证的可用线路，未保存", false)
                val route = frozen.candidates[id to mode]?.route
                    ?: return@withContext NetworkAutoApplyResult(0, "选中的线路已不可用，未保存", false, true)
                if (row.currentRoute == route) continue
                when {
                    target.sourceId != null -> sources[target.sourceId] = SourceNetworkSettings(mode, route.proxy)
                    target.category != null -> categories[target.category] = mode
                    else -> default = mode
                }
                changed++
            }
            if (changed == 0) return@withContext NetworkAutoApplyResult(0, "没有需要保存的变更", true)
            if (!fresh(frozen)) {
                markApplyStale("检测依据已变化，请重新检测")
                return@withContext NetworkAutoApplyResult(0, "检测依据已变化，未修改配置", false, true)
            }
            if (!store.applyBatch(frozen.fence.settings, default, categories, sources)) {
                markApplyStale("网络配置已被其他操作修改，请重新检测")
                return@withContext NetworkAutoApplyResult(0, "网络配置已变化，未修改配置", false, true)
            }
            val saved = store.state.value
            mutableState.update { it.copy(applying = false, applied = true, complete = false, stale = false,
                staleReason = null, error = null, rows = it.rows.map { row ->
                    row.copy(currentRoute = autoCurrentRoute(saved, frozen.targets.single { target -> target.id == row.id }))
                }) }
            plan = null
            NetworkAutoApplyResult(changed, "已保存 $changed 项；未选的配置键和进行中任务保留，跟随项按新设置继承。Relay 应用状态另行显示。", true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            NetworkAutoApplyResult(0, "设置未能完整保存，请检查存储或重新检测", false)
        } finally { mutableState.update { it.copy(applying = false) } }
    }

    private suspend fun sample(target: AutoTarget, candidate: AutoCandidate): NetworkAutoRouteResult {
        target.unsupported?.let { return it }
        candidate.unavailable?.let { return it }
        val route = candidate.route ?: return NetworkAutoRouteResult(NetworkAutoRouteStatus.NOT_CONFIGURED, message = "线路未配置")
        var samples = 0
        var elapsed = 0L
        var last = AutoSample("服务响应可用")
        try {
            withTimeout(target.timeoutMillis) {
                repeat(2) {
                    val began = monotonicTimeMillis()
                    last = target.probe(route)
                    elapsed += (monotonicTimeMillis() - began).coerceAtLeast(0)
                    samples++
                }
            }
            return NetworkAutoRouteResult(NetworkAutoRouteStatus.AVAILABLE, elapsed / 2, 2, last.message, last.code)
        } catch (_: TimeoutCancellationException) {
            return NetworkAutoRouteResult(NetworkAutoRouteStatus.FAILED, samples = samples, message = "本项检查超时，不能据此确认可用")
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { return autoFailure(error).copy(samples = samples) }
    }

    private fun markApplyStale(reason: String) {
        synchronized(lock) { plan = null }
        mutableState.update { it.copy(stale = true, complete = false, staleReason = reason, error = null,
            rows = it.rows.map { row -> row.copy(recommendation = null) }) }
    }

    private fun recommended(row: NetworkAutoRow): NetworkAutoRow {
        val direct = row.direct; val proxy = row.proxy
        if (uncertain(direct) || uncertain(proxy)) return row.copy(reason = "存在待配置、认证、限流或未完成证据，保留当前设置")
        val usable = listOf(DownloadRouteMode.DIRECT to direct, DownloadRouteMode.PROXY to proxy)
            .filter { it.second?.usable == true && it.second?.samples == 2 }
        if (usable.isEmpty()) return row.copy(reason = "本次没有完整验证的可用线路，保留当前设置")
        val faster = usable.minByOrNull { it.second?.millis ?: Long.MAX_VALUE }!!
        val current = usable.firstOrNull { it.first == row.currentRoute?.mode }
        val fastestMillis = faster.second?.millis ?: Long.MAX_VALUE
        val selected = if (current != null && (current.second?.millis ?: Long.MAX_VALUE) <= fastestMillis + maxOf(50L, fastestMillis / 5)) current else faster
        return row.copy(recommendation = selected.first, reason = when {
            usable.size == 1 -> "本次仅这一线路完整可用；结果不代表下载速度"
            selected.first == row.currentRoute?.mode -> "当前线路可用，差距较小，建议保持"
            else -> "按本次真实响应建议；不代表下载速度"
        })
    }

    private fun uncertain(result: NetworkAutoRouteResult?): Boolean = result == null || result.status in setOf(
        NetworkAutoRouteStatus.NOT_CONFIGURED, NetworkAutoRouteStatus.LOGIN_REQUIRED, NetworkAutoRouteStatus.UNSUPPORTED,
        NetworkAutoRouteStatus.CANCELLED, NetworkAutoRouteStatus.PARTIAL) || result.httpStatus in setOf(429, 503)

    private suspend fun captureFence(settings: DownloadRoutingSettings): AutoFence = AutoFence(settings,
        sourceIdentity(), resourceIdentity(), synchronized(lock) { networkRevision },
        runCatching { readDownloadNetworkEnvironment(app) }.getOrNull(), relayIdentity())

    private suspend fun fresh(frozen: AutoPlan): Boolean = store.state.value === frozen.fence.settings &&
        synchronized(lock) { !closed && networkRevision == frozen.fence.network } &&
        sourceIdentity() == frozen.fence.source && resourceIdentity() == frozen.fence.resources &&
        relayIdentity() == frozen.fence.relay && runCatching { readDownloadNetworkEnvironment(app) }.getOrNull() == frozen.fence.environment

    private fun sourceIdentity(): String {
        val entries = registry?.entries?.value
        val sources = entries?.filter { it.isInstalled }?.map { it.source } ?: repository?.sources.orEmpty()
        return hash(buildString {
            append(repository?.sourceConfigurationKey())
            sources.sortedBy { it.id }.forEach { source ->
                append('|').append(source.id).append(':').append(source.revision).append(':').append(source.homeUrl)
                append(':').append(scriptSourceImageEpoch(app, source.id))
                append(':').append(runCatching { repository?.sourceProbeIdentity(source.id) }.getOrNull())
            }
            append(entries?.map { listOf(it.source.id, it.enabled, it.removed, it.isInstalled) })
            append(preferences(app, "mangaflow_library").all.filterKeys { it.startsWith("body_source_") }.entries.sortedBy { it.key })
        })
    }

    private suspend fun resourceIdentity(): String {
        val webdavIdentity = try { webdav.connectionProbeIdentity() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { "unavailable" }
        return hash(buildString {
        append(plugins.repositories.value.map { it.url })
        append(plugins.installed.value.map { listOf(it.sourceId, it.digest, it.origin, it.metadata.optString("sourceCodeUrl")) })
        append(webdavIdentity)
    }) }

    private fun relayIdentity(): String = buildString {
        val state = relay.state.value
        append(listOf(state.enabled, state.authorized, state.phase, state.providerPackage, state.providerCertificate,
            state.grantId, state.savedRevision, state.appliedRevision, state.vpnConnected))
        MODES.forEach { mode ->
            val binding = runCatching { currentRelayBinding(mode) }.getOrNull()
            append(binding).append(':').append(binding?.let { runCatching { resolveRelayEndpoint(it, mode).observationEpoch }.getOrNull() })
        }
    }

    private suspend fun checkCurrent(run: Long, frozen: AutoPlan): Boolean {
        if (!current(run)) return false
        if (fresh(frozen)) return true
        invalidate("设置、来源、账号会话、Relay 或网络已变化，请重新检测")
        return false
    }

    private fun publish(run: Long, id: String, update: (NetworkAutoRow) -> NetworkAutoRow) {
        updateRun(run) { it.copy(rows = it.rows.map { row -> if (row.id == id) update(row) else row }) }
    }
    private fun updateRun(run: Long, update: (NetworkAutoCheckState) -> NetworkAutoCheckState) {
        mutableState.update { if (current(run)) update(it) else it }
    }
    private fun current(run: Long): Boolean = synchronized(lock) { !closed && generation == run }
    private fun stopUnfinished(reason: String, stale: Boolean, run: Long) {
        val stopped = NetworkAutoRouteResult(NetworkAutoRouteStatus.CANCELLED, message = "本项尚未完成")
        mutableState.update { previous -> if (synchronized(lock) { generation != run }) previous else previous.copy(running = false, complete = false, stale = stale,
            staleReason = reason.takeIf { stale }, error = reason.takeUnless { stale }, activeProbeIds = emptySet(),
            rows = previous.rows.map { it.copy(direct = it.direct ?: stopped, proxy = it.proxy ?: stopped,
                recommendation = null, reason = if (finished(it)) it.reason else reason) }) }
    }
    private fun finished(row: NetworkAutoRow): Boolean = row.direct != null && row.proxy != null
    private fun hash(value: String): String = sha256(value.encodeToByteArray()).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    companion object { private val MODES = listOf(DownloadRouteMode.DIRECT, DownloadRouteMode.PROXY) }
}
