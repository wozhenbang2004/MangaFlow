package dev.mangaflow.net

import dev.mangaflow.MangaApplicationServices
import dev.mangaflow.background.ApplicationOperations
import dev.mangaflow.background.ApplicationOperationKind
import dev.mangaflow.background.ApplicationOperationPhase
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.ReviewProviders
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.data.plugins.scriptSourceImageEpoch
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.storage.WebDavBackupStore
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class NetworkAutoRouteStatus { AVAILABLE, FAILED, NOT_CONFIGURED, LOGIN_REQUIRED, UNSUPPORTED, CANCELLED, PARTIAL }
enum class NetworkAutoGroup { MANGA_SOURCES, ANIME_SOURCES, RATING_SITES, DOWNLOADS }

data class NetworkAutoRouteResult(
    val status: NetworkAutoRouteStatus,
    val millis: Long? = null,
    val samples: Int = 0,
    val message: String,
    val httpStatus: Int? = null,
    val confirming: Boolean = false,
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
    val group: NetworkAutoGroup = NetworkAutoGroup.DOWNLOADS,
    val ratingId: String? = null,
    val canApply: Boolean = true,
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
    val runId: String? = null,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val restoring: Boolean = true,
    val historyOnly: Boolean = false,
    val interrupted: Boolean = false,
    val historySaved: Boolean = true,
    val selection: Map<String, DownloadRouteMode> = emptyMap(),
    val applyMessage: String? = null,
)


/** An OS wake is opportunistic; foreground process ticks honor shorter user intervals. */
data class NetworkAutoScheduleState(
    val enabled: Boolean = true,
    val intervalMinutes: Int = 30,
    val concurrency: Int = 12,
    val autoApply: Boolean = true,
    /** Explicit per-target veto. Measurements still run; automatic route writes skip these IDs. */
    val lockedTargetIds: Set<String> = emptySet(),
    val lastAttemptAtMillis: Long = 0L,
    val lastCompletedAtMillis: Long = 0L,
    val lastApplied: Int = 0,
    val message: String? = null,
) {
    val intervalMillis: Long get() = intervalMinutes.toLong() * 60_000L
    fun checked(): NetworkAutoScheduleState = apply {
        require(intervalMinutes > 0 && concurrency > 0) { "检查周期和并发必须大于零。" }
    }
}

private class NetworkAutoScheduleStore(context: PlatformContext) {
    private val prefs = preferences(applicationContext(context), "mangaflow_network_auto")
    private val mutable = MutableStateFlow(NetworkAutoScheduleState(
        enabled = prefs.getBoolean("enabled", true),
        intervalMinutes = prefs.getInt("intervalMinutes", 30).takeIf { it > 0 } ?: 30,
        concurrency = prefs.getInt("concurrency", 12).takeIf { it > 0 } ?: 12,
        autoApply = prefs.getBoolean("autoApply", true),
        lockedTargetIds = prefs.getString("lockedTargets")?.split('|')
            ?.filter { it.matches(Regex("[A-Za-z0-9_.-]{1,160}")) }?.toSet().orEmpty(),
        lastAttemptAtMillis = prefs.getLong("lastAttemptAt", 0L),
        lastCompletedAtMillis = prefs.getLong("lastCompletedAt", 0L),
        lastApplied = prefs.getInt("lastApplied", 0),
    ))
    val state: StateFlow<NetworkAutoScheduleState> = mutable.asStateFlow()
    fun update(next: NetworkAutoScheduleState): Boolean {
        next.checked()
        if (!prefs.edit().putBoolean("enabled", next.enabled)
                .putInt("intervalMinutes", next.intervalMinutes)
                .putInt("concurrency", next.concurrency)
                .putBoolean("autoApply", next.autoApply)
                .putString("lockedTargets", next.lockedTargetIds.sorted().joinToString("|")).commit()) return false
        mutable.update { previous -> next.copy(
            lastAttemptAtMillis = previous.lastAttemptAtMillis,
            lastCompletedAtMillis = previous.lastCompletedAtMillis,
            lastApplied = previous.lastApplied, message = previous.message) }
        return true
    }
    fun lockTarget(id: String, locked: Boolean): Boolean {
        if (!id.matches(Regex("[A-Za-z0-9_.-]{1,160}"))) return false
        val current = mutable.value
        return update(current.copy(lockedTargetIds =
            if (locked) current.lockedTargetIds + id else current.lockedTargetIds - id))
    }
    fun started(now: Long) {
        prefs.edit().putLong("lastAttemptAt", now).apply()
        mutable.update { it.copy(lastAttemptAtMillis = now, message = "检测中") }
    }
    fun finished(now: Long, applied: Int, message: String) {
        prefs.edit().putLong("lastCompletedAt", now).putInt("lastApplied", applied).apply()
        mutable.update { it.copy(lastCompletedAtMillis = now, lastApplied = applied, message = message) }
    }
}

private class NetworkAutoScheduler(
    private val context: PlatformContext,
    private val operations: ApplicationOperations,
    private val engine: NetworkAutoCheckEngine,
    private val schedule: NetworkAutoScheduleStore,
) {
    private val lock = Mutex()

    init {
        operations.scope.launch(Dispatchers.IO) {
            operations.ready.first { it }
            schedule.state.map { it.enabled to it.intervalMinutes }.distinctUntilChanged().collectLatest { (enabled, minutes) ->
                try {
                    if (enabled) scheduleNetworkAutoWake(context, minutes) else cancelNetworkAutoWake(context)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* The live scheduler is still available. */ }
                if (enabled) while (true) {
                    try { checkDue() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Retry on the next due tick; keep the last verified route. */ }
                    delay(minOf(minutes.toLong() * 60_000L, 60_000L))
                }
            }
        }
    }

    fun resume() {
        operations.scope.launch(Dispatchers.IO) {
            try { checkDue() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Never block app startup for a network probe. */ }
        }
    }

    /** Shared by the Android OS worker, in-process scheduler and app resume. */
    suspend fun checkDue() = lock.withLock {
        val settings = schedule.state.value
        val now = wallTimeMillis()
        if (!settings.enabled || (settings.lastAttemptAtMillis > 0L &&
                now >= settings.lastAttemptAtMillis &&
                now - settings.lastAttemptAtMillis < settings.intervalMillis)) return@withLock
        if (!engine.start()) return@withLock
        val runId = engine.state.value.runId
        schedule.started(now)
        val finished = withTimeoutOrNull(dev.mangaflow.experience.ExperiencePolicyStore.get(context).state.value.runtime.networkCheckTimeoutSeconds.toLong() * 1_000L + 30_000L) {
            engine.state.first { it.runId == runId && !it.running && !it.restoring }
        }
        var applied = 0
        var message = when {
            finished == null -> "本轮检测超时，线路保持不变"
            !finished.complete -> "部分检查未完成，线路保持不变"
            else -> "检测完成，线路保持不变"
        }
        val eligible = finished?.selection.orEmpty().filterKeys {
            it !in schedule.state.value.lockedTargetIds
        }
        if (finished?.complete == true && schedule.state.value.enabled && schedule.state.value.autoApply &&
            eligible.isNotEmpty()) {
            val result = engine.apply(eligible)
            if (result.success) {
                applied = result.applied
                message = if (applied > 0) "已自动调整 $applied 项线路" else "线路无需调整"
            } else message = "配置或网络变化，未自动修改线路"
        }
        schedule.finished(wallTimeMillis(), applied, message)
    }
}

data class NetworkAutoApplyResult(val applied: Int, val message: String, val success: Boolean, val stale: Boolean = false)

/** One application operation owns execution; views only subscribe, stage and explicitly confirm. */
class NetworkAutoCheckController internal constructor(
    context: PlatformContext,
    repository: UnifiedMangaRepository?,
    registry: BodySourceRegistry?,
    operations: ApplicationOperations = ApplicationOperations.get(context),
    prepareSources: suspend () -> Unit = {},
) {
    private val settings = NetworkAutoScheduleStore(context)
    private val engine = NetworkAutoCheckEngine(context, repository, registry, operations, prepareSources, settings.state)
    private val scheduler = NetworkAutoScheduler(context, operations, engine, settings)
    val state: StateFlow<NetworkAutoCheckState> get() = engine.state
    val schedule: StateFlow<NetworkAutoScheduleState> get() = settings.state
    fun updateSchedule(next: NetworkAutoScheduleState): Boolean = settings.update(next)
    /** A source lock does not stop checking and never changes a selected manual route. */
    fun lockTarget(id: String, locked: Boolean): Boolean = settings.lockTarget(id, locked)
    suspend fun checkDue() = scheduler.checkDue()
    fun resume() = scheduler.resume()
    fun start(): Boolean = engine.start()
    suspend fun run() { start() }
    fun cancel() = engine.cancel()
    fun invalidate(reason: String) = engine.invalidate(reason)
    suspend fun apply(selected: Map<String, DownloadRouteMode>): NetworkAutoApplyResult = engine.apply(selected)
    fun stage(id: String, mode: DownloadRouteMode?) = engine.stage(id, mode)
    /** Detaching a presentation must never cancel the accepted application operation. */
    fun close() = Unit
    companion object {
        fun get(context: PlatformContext): NetworkAutoCheckController = MangaApplicationServices.get(context).networkChecks
    }
}

private data class AutoFence(val settings: DownloadRoutingSettings, val source: String, val resources: String,
    val network: Long, val environment: DownloadNetworkEnvironment?, val relay: String)
private data class AutoPlan(val fence: AutoFence, val targets: List<AutoTarget>,
    val candidates: Map<Pair<String, DownloadRouteMode>, AutoCandidate>)
private class OwnedCheckRun(val generation: Long, val id: String, val startedAt: Long) {
    var cancelled = false
    var interrupted = false
    var finalized = false
    var stopped: NetworkAutoCheckState? = null
}

private class NetworkAutoCheckEngine(context: PlatformContext, private val repository: UnifiedMangaRepository?,
    private val registry: BodySourceRegistry?, private val operations: ApplicationOperations,
    private val prepareSources: suspend () -> Unit,
    private val schedule: StateFlow<NetworkAutoScheduleState>) {
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
    private var activeRun: OwnedCheckRun? = null
    private val history = NetworkAutoHistoryStore(app)
    private val restored = CompletableDeferred<Unit>()
    private val releaseNetwork = runCatching { observeDownloadNetworkEnvironment(app) {
        synchronized(lock) { networkRevision++ }
        if (state.value.running || state.value.complete) invalidate("网络环境已变化，请重新检测")
    } }.getOrElse { {} }

    init {
        operations.scope.launch(Dispatchers.IO) {
            try {
                val previous = history.load()
                operations.ready.first { it }
                val operation = operations.find(OPERATION_KEY)
                val recovered = if (operation?.phase == ApplicationOperationPhase.INTERRUPTED) previous.copy(
                    interrupted = true, historyOnly = true, stale = true,
                    error = if (previous.rows.isNotEmpty()) "上次检测已中断，已有完整报告仅供查看"
                        else "上次检测已中断，请重新检测") else previous
                synchronized(lock) {
                    if (activeRun == null && state.value.runId == null) mutableState.value = recovered
                    else mutableState.update { it.copy(restoring = false) }
                }
            } finally { restored.complete(Unit) }
        }
    }

    fun start(): Boolean {
        if (operations.find(OPERATION_KEY)?.active == true) return false
        val attempt = synchronized(lock) {
            if (closed || activeRun != null || state.value.applying) return false
            val previousState = state.value
            val previousPlan = plan
            val accepted = OwnedCheckRun(++generation, randomUuid(), wallTimeMillis()).also {
                activeRun = it; plan = null
                mutableState.value = NetworkAutoCheckState(running = true, runId = it.id,
                    startedAtMillis = it.startedAt, restoring = !restored.isCompleted)
            }
            Triple(accepted, previousState, previousPlan)
        }
        val accepted = attempt.first
        val handle = operations.submit<Unit>(OPERATION_KEY, ApplicationOperationKind.NETWORK_CHECK, "网络检测",
            onTerminal = { finishRun(accepted) }) {
            restored.await()
            currentCoroutineContext().ensureActive()
            val durable = history.begin(accepted.id, accepted.startedAt)
            updateRun(accepted.generation) { it.copy(restoring = false, historySaved = durable) }
            execute(accepted)
            if (!state.value.complete) throw IllegalStateException("网络检测未完整完成")
        }
        if (!handle.accepted) {
            synchronized(lock) {
                if (activeRun === accepted) {
                    generation++; activeRun = null; runningJob = null; plan = attempt.third
                    mutableState.value = attempt.second.copy(restoring = !restored.isCompleted)
                }
            }
            return false
        }
        synchronized(lock) {
            if (activeRun === accepted && generation == accepted.generation) runningJob = handle.result
            else handle.result.cancel()
        }
        return handle.accepted
    }

    private suspend fun execute(accepted: OwnedCheckRun): Unit = coroutineScope {
        val run = accepted.generation
        try {
            withContext(Dispatchers.IO) {
                val frozen = withTimeout(20_000L) {
                    prepareSources()
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
                        coverage = target.coverage, category = target.category, sourceId = target.sourceId,
                        group = target.group, ratingId = target.ratingId, canApply = target.unsupported == null) },
                    total = targets.size) else previous }
                val gate = Semaphore(schedule.value.concurrency)
                val mutableSources = targets.filterNot { it.parallelRoutes }.associate { it.id to Mutex() }
                val mangaTargets = targets.filter { it.group == NetworkAutoGroup.MANGA_SOURCES }
                val animeTargets = targets.filter { it.group == NetworkAutoGroup.ANIME_SOURCES }
                val ratingTargets = targets.filter { it.group == NetworkAutoGroup.RATING_SITES }
                val ordered = buildList {
                    repeat(maxOf(mangaTargets.size, animeTargets.size, ratingTargets.size)) { index ->
                        mangaTargets.getOrNull(index)?.let { add(it) }
                        animeTargets.getOrNull(index)?.let { add(it) }
                        ratingTargets.getOrNull(index)?.let { add(it) }
                    }
                    addAll(targets.filter { it.group == NetworkAutoGroup.DOWNLOADS })
                }
                try {
                    withTimeout(dev.mangaflow.experience.ExperiencePolicyStore.get(app).state.value.runtime.networkCheckTimeoutSeconds.toLong() * 1_000L) {
                        coroutineScope {
                            ordered.flatMap { target -> MODES.map { mode -> async {
                                val inspect: suspend () -> Unit = {
                                    gate.withPermit {
                                        if (!checkCurrent(run, frozen)) return@withPermit
                                        val key = target.id + ":" + mode.name
                                        updateRun(run) { it.copy(activeProbeIds = it.activeProbeIds + key) }
                                        val candidate = candidates.getValue(target.id to mode)
                                        fun qualified(result: NetworkAutoRouteResult): NetworkAutoRouteResult =
                                            if (mode == DownloadRouteMode.DIRECT && candidate.route?.relayBinding == null &&
                                                frozen.fence.environment?.defaultNetworkUsesVpn == true) result.copy(
                                                message = result.message + "；应用直连仍受系统 VPN 规则影响") else result
                                        val measured = try { sample(target, candidate) { first ->
                                            publish(run, target.id) { row -> if (mode == DownloadRouteMode.DIRECT)
                                                row.copy(direct = qualified(first)) else row.copy(proxy = qualified(first)) }
                                        } }
                                        finally { updateRun(run) { it.copy(activeProbeIds = it.activeProbeIds - key) } }
                                        val result = qualified(measured)
                                        publish(run, target.id) { row -> if (mode == DownloadRouteMode.DIRECT) row.copy(direct = result)
                                            else row.copy(proxy = result) }
                                        if (!checkCurrent(run, frozen)) return@withPermit
                                        publish(run, target.id) { row ->
                                            if (!finished(row)) row
                                            else if (frozen.fence.environment?.defaultNetworkUsesVpn == true &&
                                                candidates[target.id to DownloadRouteMode.DIRECT]?.route?.relayBinding == null)
                                                row.copy(reason = "外部 VPN 仍控制应用直连，未证明独立出口，本轮不自动推荐")
                                            else recommended(row)
                                        }
                                        updateRun(run) { it.copy(completed = it.rows.count(::finished)) }
                                    }
                                }
                                val sessionLock = mutableSources[target.id]
                                if (sessionLock == null) inspect() else sessionLock.withLock { inspect() }
                            } } }.awaitAll()
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    stopUnfinished("本轮时间预算已用完，未完成项可重新检测", stale = false, run)
                    return@withContext
                }
                if (!checkCurrent(run, frozen)) return@withContext
                updateRun(run) { it.copy(completed = it.rows.count(::finished),
                    complete = it.rows.all(::finished), activeProbeIds = emptySet(), selection = suggestions(it.rows)) }
            }
        } catch (_: TimeoutCancellationException) {
            stopUnfinished("检查目标准备超时，请重试", stale = false, run = run)
        } catch (cancel: CancellationException) {
            val interrupted = synchronized(lock) {
                (!accepted.cancelled && !state.value.stale).also { accepted.interrupted = it }
            }
            stopUnfinished(if (interrupted) "系统暂停了检测，请重新检测" else "检查已取消，已完成结果保留", stale = false, run)
            throw cancel
        } catch (_: Exception) {
            stopUnfinished("检查计划暂未准备完成，请重试", stale = false, run)
        } finally {
            synchronized(lock) { if (generation == run) runningJob = null }
            updateRun(run) { it.copy(activeProbeIds = emptySet()) }
        }
    }

    fun cancel() {
        val (job, stoppedGeneration) = synchronized(lock) {
            activeRun?.cancelled = true
            generation++; (runningJob to generation).also { runningJob = null }
        }
        job?.cancel()
        stopUnfinished("检查已取消，已完成结果保留", stale = false, stoppedGeneration)
        synchronized(lock) { activeRun?.stopped = state.value }
    }

    fun invalidate(reason: String) {
        if (state.value.applied || state.value.applying) return
        val (job, stoppedGeneration) = synchronized(lock) { generation++; (runningJob to generation).also { runningJob = null } }
        job?.cancel()
        stopUnfinished(reason, stale = true, stoppedGeneration)
        synchronized(lock) { activeRun?.stopped = state.value }
    }

    fun stage(id: String, mode: DownloadRouteMode?) {
        mutableState.update { before ->
            if (!before.complete || before.historyOnly || before.stale || before.running || before.applying || before.applied) before
            else {
                val row = before.rows.firstOrNull { it.id == id }
                val result = when (mode) { DownloadRouteMode.DIRECT -> row?.direct; DownloadRouteMode.PROXY -> row?.proxy; else -> null }
                if (mode == null || mode == row?.currentRoute?.mode) before.copy(selection = before.selection - id)
                else if (id in schedule.value.lockedTargetIds) before
                else if (row?.canApply == true && result?.usable == true && result.samples == 2 && !result.confirming)
                    before.copy(selection = before.selection + (id to mode)) else before
            }
        }
    }

    suspend fun apply(selected: Map<String, DownloadRouteMode>): NetworkAutoApplyResult = withContext(Dispatchers.IO) {
        val frozen = synchronized(lock) {
            if (closed || !state.value.complete || state.value.historyOnly || state.value.stale || state.value.running || state.value.applying || state.value.applied) null
            else plan?.also { mutableState.update { it.copy(applying = true) } }
        } ?: return@withContext NetworkAutoApplyResult(0, "请先完成本轮检测后再确认设置", false, state.value.stale)
        val applyingRunId = state.value.runId
        try {
            if (selected.keys.any { it in schedule.value.lockedTargetIds }) {
                return@withContext NetworkAutoApplyResult(0, "锁定项目保持原线路，请先解除锁定", false)
            }
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
                if (target.unsupported != null || result?.usable != true || result.samples != 2 || result.confirming)
                    return@withContext NetworkAutoApplyResult(0, "只能应用完整验证的可用线路，未保存", false)
                val route = frozen.candidates[id to mode]?.route
                    ?: return@withContext NetworkAutoApplyResult(0, "选中的线路已不可用，未保存", false, true)
                if (row.currentRoute == route) continue
                when {
                    target.sourceId != null -> sources[target.sourceId] = SourceNetworkSettings(mode, route.proxy)
                    target.ratingId != null -> sources[ReviewProviders.routingId(target.ratingId)] = SourceNetworkSettings(mode, route.proxy)
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
            if (selected.keys.any { it in schedule.value.lockedTargetIds }) {
                return@withContext NetworkAutoApplyResult(0, "保存前有来源被锁定，未修改线路", false)
            }
            if (!store.applyBatch(frozen.fence.settings, default, categories, sources)) {
                markApplyStale("网络配置已被其他操作修改，请重新检测")
                return@withContext NetworkAutoApplyResult(0, "网络配置已变化，未修改配置", false, true)
            }
            val saved = store.state.value
            val receipt = "已保存 $changed 项；未选的配置键和进行中任务保留，跟随项按新设置继承。Relay 应用状态另行显示。"
            mutableState.update { it.copy(applied = true, complete = false, stale = false,
                selection = emptyMap(), applyMessage = receipt,
                staleReason = null, error = null, rows = it.rows.map { row ->
                    row.copy(currentRoute = autoCurrentRoute(saved, frozen.targets.single { target -> target.id == row.id }))
                }) }
            plan = null
            val appliedSnapshot = state.value
            withContext(NonCancellable) {
                val durable = history.applied(appliedSnapshot)
                mutableState.update { if (it.runId == applyingRunId) it.copy(historySaved = durable) else it }
            }
            NetworkAutoApplyResult(changed, receipt, true)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            NetworkAutoApplyResult(0, "设置未能完整保存，请检查存储或重新检测", false)
        } finally { mutableState.update { if (it.runId == applyingRunId) it.copy(applying = false) else it } }
    }

    private suspend fun sample(target: AutoTarget, candidate: AutoCandidate,
        onSample: (NetworkAutoRouteResult) -> Unit): NetworkAutoRouteResult {
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
                    elapsed += last.responseMillis ?: (monotonicTimeMillis() - began).coerceAtLeast(0)
                    samples++
                    if (samples == 1) onSample(NetworkAutoRouteResult(NetworkAutoRouteStatus.PARTIAL,
                        elapsed, 1, last.message + "；等待第二次确认", last.code, confirming = true))
                }
            }
            return NetworkAutoRouteResult(NetworkAutoRouteStatus.AVAILABLE, elapsed / 2, 2, last.message, last.code)
        } catch (_: TimeoutCancellationException) {
            return NetworkAutoRouteResult(NetworkAutoRouteStatus.FAILED, samples = samples, message = "本项检查超时，不能据此确认可用")
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { return autoFailure(error).copy(samples = samples, millis = elapsed.takeIf { samples > 0 }?.div(samples)) }
    }

    private fun markApplyStale(reason: String) {
        synchronized(lock) { plan = null }
        mutableState.update { it.copy(stale = true, complete = false, staleReason = reason, error = null, selection = emptyMap(),
            rows = it.rows.map { row -> row.copy(recommendation = null) }) }
    }

    private suspend fun finishRun(accepted: OwnedCheckRun) = withContext(NonCancellable + Dispatchers.IO) {
        restored.await()
        val terminal = synchronized(lock) {
            if (accepted.finalized) return@withContext
            accepted.finalized = true
            val snapshot = accepted.stopped ?: state.value.takeIf { it.runId == accepted.id }
                ?: NetworkAutoCheckState(runId = accepted.id, startedAtMillis = accepted.startedAt, restoring = false)
            val complete = snapshot.complete || snapshot.applied
            val interrupted = !complete && !accepted.cancelled && !snapshot.stale && (accepted.interrupted || snapshot.error == null)
            snapshot.copy(running = false, restoring = false, finishedAtMillis = wallTimeMillis(),
                interrupted = interrupted, activeProbeIds = emptySet(),
                selection = snapshot.selection.takeIf { complete }.orEmpty(),
                error = snapshot.error ?: if (interrupted) "系统暂停了检测，请重新检测" else null)
        }
        val durable = history.finish(terminal)
        synchronized(lock) {
            if (state.value.runId == accepted.id) mutableState.update { current -> current.copy(
                running = false, restoring = false, finishedAtMillis = terminal.finishedAtMillis,
                interrupted = terminal.interrupted, historySaved = durable, activeProbeIds = emptySet(),
                error = current.error ?: terminal.error) }
            if (activeRun === accepted) activeRun = null
        }
    }

    private fun suggestions(rows: List<NetworkAutoRow>): Map<String, DownloadRouteMode> = rows.mapNotNull { row ->
        val mode = row.recommendation
        val result = when (mode) { DownloadRouteMode.DIRECT -> row.direct; DownloadRouteMode.PROXY -> row.proxy; else -> null }
        if (row.canApply && row.id !in schedule.value.lockedTargetIds &&
            mode != null && mode != row.currentRoute?.mode && result?.usable == true &&
            result.samples == 2 && !result.confirming) row.id to mode else null
    }.toMap()

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
        fun stoppedResult(result: NetworkAutoRouteResult?): NetworkAutoRouteResult = when {
            result == null -> stopped
            result.confirming -> result.copy(status = NetworkAutoRouteStatus.CANCELLED, confirming = false,
                message = result.message.replace("；等待第二次确认", "") + "；本轮检查未完成")
            else -> result
        }
        mutableState.update { previous -> if (synchronized(lock) { generation != run }) previous else previous.copy(running = false, complete = false, stale = stale, selection = emptyMap(),
            staleReason = reason.takeIf { stale }, error = reason.takeUnless { stale }, activeProbeIds = emptySet(),
            rows = previous.rows.map { it.copy(direct = stoppedResult(it.direct), proxy = stoppedResult(it.proxy),
                recommendation = null, reason = if (finished(it)) it.reason else reason) }) }
    }
    private fun finished(row: NetworkAutoRow): Boolean = row.direct != null && row.proxy != null &&
        !row.direct.confirming && !row.proxy.confirming
    private fun hash(value: String): String = sha256(value.encodeToByteArray()).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    companion object {
        private val MODES = listOf(DownloadRouteMode.DIRECT, DownloadRouteMode.PROXY)
        private const val OPERATION_KEY = "network.connection-check"
    }
}
