package dev.mangaflow.enhancement

import dev.mangaflow.enhancement.engine.EngineOutput
import dev.mangaflow.enhancement.engine.EngineProgress
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.storage.StorageLock
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

enum class EnhancementPriority { P0, P1, P2, P3 }
enum class EnhancementTaskPhase(val label: String) {
    QUEUED("排队中"), WAITING("等待资源"), RUNNING("处理中"), PAUSED("已暂停"),
    COMPLETED("已完成"), FAILED("失败"), CANCELLED("已取消")
}
data class EnhancementTask(
    val id: String,
    val content: EnhancementContent,
    val priority: EnhancementPriority,
    val sessionKey: String?,
    val generation: Long,
    val phase: EnhancementTaskPhase = EnhancementTaskPhase.QUEUED,
    val progress: EngineProgress? = null,
    val reason: String? = null,
    val createdAtMillis: Long = monotonicTimeMillis(),
    val output: EngineOutput? = null,
    val title: String? = null,
)

class EnhancementJobContext internal constructor(
    val progress: (EngineProgress) -> Unit,
    private val isCurrent: () -> Boolean,
    private val admission: () -> String?,
) {
    suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        checkCurrent()
        admission()?.let { throw EnhancementYield(it) }
    }
    fun checkCurrent() { if (!isCurrent()) throw CancellationException("内容已切换") }
}
private class EnhancementYield(message: String) : CancellationException(message)

/** One application queue; engines never create a second GPU scheduler. P0 still permits raw fallback. */
class EnhancementScheduler(
    private val scope: CoroutineScope,
    private val resources: ExperiencePolicyStore,
    private val completed: (EnhancementTask, EngineOutput) -> Unit = { _, _ -> },
) {
    private class Entry(
        var task: EnhancementTask,
        val estimatedBytes: Long,
        val isCurrent: () -> Boolean,
        val work: suspend (EnhancementJobContext) -> EngineOutput,
        val result: CompletableDeferred<EngineOutput> = CompletableDeferred(),
        var run: Deferred<EngineOutput>? = null,
        var pauseRequested: Boolean = false,
        var cancelRequested: Boolean = false,
        var lastProgressPublishedAt: Long = 0L,
    )
    private val lock = StorageLock()
    private val entries = linkedMapOf<String, Entry>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutableState = MutableStateFlow<List<EnhancementTask>>(emptyList())
    val state: StateFlow<List<EnhancementTask>> = mutableState.asStateFlow()
    private var stopResourceObservation: (() -> Unit)? = null

    init {
        scope.launch {
            // Admission changes include resource samples, interaction and realtime ownership.
            // Image/cache usage counters cannot change this queue's admission decision.
            combine(resources.state, resources.admissionChanges) { _, _ -> Unit }.collect {
                synchronized(lock) {
                    if (stopResourceObservation == null) return@collect
                    entries.values.filter { it.run != null }.forEach { entry ->
                        resources.enhancementWaitReason(entry.task.priority.ordinal)?.let { reason ->
                            entry.run?.cancel(EnhancementYield(reason))
                        }
                    }
                }
                wake.trySend(Unit)
            }
        }
        scope.launch { runQueue() }
    }

    fun submit(
        id: String, content: EnhancementContent, priority: EnhancementPriority,
        sessionKey: String? = null, generation: Long = 0L, estimatedBytes: Long = 0L,
        isCurrent: () -> Boolean = { true },
        title: String? = null,
        work: suspend (EnhancementJobContext) -> EngineOutput,
    ): Deferred<EngineOutput> = synchronized(lock) {
        require(id.isNotBlank())
        entries[id]?.takeIf { !it.result.isCompleted }?.let { existing ->
            if (existing.task.content == content && existing.task.sessionKey == sessionKey &&
                existing.task.generation == generation && existing.isCurrent()) {
                if (priority < existing.task.priority) existing.task = existing.task.copy(priority = priority)
                publish(); wake.trySend(Unit)
                return@synchronized existing.result
            }
            cancelEntry(existing, "任务已被新的内容替代")
        }
        val entry = Entry(EnhancementTask(id, content, priority, sessionKey, generation, title = title?.take(240)),
            estimatedBytes.coerceAtLeast(0L), isCurrent, work)
        entries[id] = entry
        // Cancellation happens at real engine checkpoints; the permit stays held until the old work exits.
        entries.values.filter { it !== entry && it.task.priority > priority && it.run != null }
            .forEach { it.run?.cancel(EnhancementYield("临近内容优先")) }
        trimHistory(); publish(); wake.trySend(Unit)
        entry.result
    }

    /** A prefetched page becoming visible keeps its attempt and receives foreground admission. */
    fun promote(id: String, priority: EnhancementPriority): Unit = synchronized(lock) {
        val entry = entries[id]?.takeIf { !it.result.isCompleted && it.isCurrent() } ?: return@synchronized
        if (priority >= entry.task.priority) return@synchronized
        entry.task = entry.task.copy(priority = priority)
        if (!entry.pauseRequested) entries.values.filter { it !== entry && it.task.priority > priority && it.run != null }
            .forEach { it.run?.cancel(EnhancementYield("当前页优先")) }
        publish(); wake.trySend(Unit)
    }

    fun pause(id: String) = synchronized(lock) {
        entries[id]?.takeIf { !it.result.isCompleted }?.let {
            it.pauseRequested = true; it.task = it.task.copy(phase = EnhancementTaskPhase.PAUSED, reason = "用户暂停")
            it.run?.cancel(EnhancementYield("用户暂停")); publish()
        }
    }
    fun resume(id: String) = synchronized(lock) {
        entries[id]?.takeIf { !it.result.isCompleted && it.pauseRequested }?.let {
            it.pauseRequested = false; it.task = it.task.copy(phase = EnhancementTaskPhase.QUEUED, reason = null)
            publish(); wake.trySend(Unit)
        }
    }
    fun cancel(id: String) = cancel(id, "用户取消")
    fun cancel(id: String, reason: String) = synchronized(lock) {
        entries[id]?.let { cancelEntry(it, reason) }; publish(); wake.trySend(Unit)
    }
    fun cancelSession(sessionKey: String) = cancelSession(sessionKey, "会话已结束")
    fun cancelSession(sessionKey: String, reason: String) = synchronized(lock) {
        entries.values.filter { it.task.sessionKey == sessionKey && !it.result.isCompleted }
            .forEach { cancelEntry(it, reason) }
        publish(); wake.trySend(Unit)
    }
    fun retry(id: String): Deferred<EngineOutput>? {
        // Reader-owned attempts must be recreated by their owner; an expired closure is not a retry.
        val entry = synchronized(lock) { entries[id]?.takeIf {
            it.task.phase == EnhancementTaskPhase.FAILED && it.isCurrent()
        } } ?: return null
        return submit(id, entry.task.content, entry.task.priority, entry.task.sessionKey, entry.task.generation,
            entry.estimatedBytes, entry.isCurrent, title = entry.task.title, work = entry.work)
    }
    private fun cancelEntry(entry: Entry, reason: String) {
        if (entry.result.isCompleted) return
        entry.cancelRequested = true
        entry.task = entry.task.copy(phase = EnhancementTaskPhase.CANCELLED, reason = reason)
        entry.run?.cancel(CancellationException(reason))
        entry.result.cancel(CancellationException(reason))
    }

    private suspend fun runQueue() {
        try { while (currentCoroutineContext().isActive) {
            val picked = synchronized(lock) {
                var chosen: Pair<Entry, () -> Unit>? = null
                entries.values.filter { !it.result.isCompleted && !it.pauseRequested && it.run == null }
                    .sortedWith(compareBy<Entry> { it.task.priority.ordinal }.thenBy { it.task.createdAtMillis })
                    .forEach { entry ->
                        if (!entry.isCurrent()) {
                            cancelEntry(entry, "内容已切换")
                        } else if (chosen == null) {
                            val reason = resources.enhancementWaitReason(entry.task.priority.ordinal, entry.estimatedBytes)
                            val release = if (reason == null) resources.tryAcquireEnhancement(entry.task.priority.ordinal, entry.estimatedBytes) else null
                            if (release != null) {
                                entry.task = entry.task.copy(phase = EnhancementTaskPhase.RUNNING, reason = null)
                                chosen = entry to release
                            } else entry.task = entry.task.copy(phase = EnhancementTaskPhase.WAITING, reason = reason ?: "等待增强引擎")
                        }
                    }
                publish(); chosen
            }
            // A waiting queue retains the existing resource observer, so real changes wake it.
            // An empty/paused queue has no periodic timer, filesystem sample or UI publication.
            if (picked == null) { wake.receive(); continue }
            val (entry, release) = picked
            try {
                val ctx = EnhancementJobContext(progress = { value -> synchronized(lock) {
                    if (!entry.cancelRequested && entry.isCurrent()) {
                        val previousStage = entry.task.progress?.stage
                        entry.task = entry.task.copy(progress = value)
                        val now = monotonicTimeMillis()
                        if (platformKind != PlatformKind.DESKTOP || previousStage != value.stage ||
                            now - entry.lastProgressPublishedAt >= 100L ||
                            value.total?.let { it > 0L && value.completed >= it } == true) {
                            entry.lastProgressPublishedAt = now
                            publish()
                        }
                    }
                } }, isCurrent = entry.isCurrent, admission = {
                    resources.enhancementWaitReason(entry.task.priority.ordinal)
                })
                val run = scope.async(start = CoroutineStart.LAZY) {
                    ctx.checkpoint()
                    entry.work(ctx).also {
                        // The engine may already have atomically published a valid asset. A new
                        // resource wait must not turn that success into another inference attempt.
                        currentCoroutineContext().ensureActive()
                        ctx.checkCurrent()
                    }
                }
                synchronized(lock) {
                    entry.run = run
                    if (entry.cancelRequested || entry.pauseRequested) run.cancel(EnhancementYield("已暂停或取消"))
                }
                run.start()
                val output = run.await()
                synchronized(lock) {
                    if (entry.cancelRequested || !entry.isCurrent()) throw CancellationException("内容已切换")
                    entry.task = entry.task.copy(phase = EnhancementTaskPhase.COMPLETED, output = output, reason = null)
                    entry.result.complete(output); publish()
                }
                runCatching { completed(entry.task, output) }
            } catch (cancel: CancellationException) {
                synchronized(lock) {
                    when {
                        entry.cancelRequested -> Unit // Keep the actual owner's cancellation reason.
                        !entry.isCurrent() -> cancelEntry(entry, "内容已切换")
                        entry.pauseRequested -> entry.task = entry.task.copy(phase = EnhancementTaskPhase.PAUSED, reason = "用户暂停")
                        !scope.isActive -> cancelEntry(entry, "增强服务已结束")
                        else -> entry.task = entry.task.copy(phase = EnhancementTaskPhase.WAITING, reason = cancel.message ?: "等待资源")
                    }
                    publish()
                }
            } catch (error: Exception) {
                synchronized(lock) {
                    entry.task = entry.task.copy(phase = EnhancementTaskPhase.FAILED, reason = error.message?.take(240) ?: "增强未完成，原画仍可使用")
                    entry.result.completeExceptionally(error); publish()
                }
            } finally {
                synchronized(lock) { entry.run = null; publish() }
                release()
            }
        } } finally {
            synchronized(lock) {
                stopResourceObservation?.invoke()
                stopResourceObservation = null
            }
        }
    }

    private fun publish() {
        val pending = entries.values.any { it.run != null ||
            !it.result.isCompleted && !it.pauseRequested && !it.cancelRequested }
        if (pending && stopResourceObservation == null) stopResourceObservation = resources.startObserving()
        else if (!pending && stopResourceObservation != null) {
            stopResourceObservation?.invoke()
            stopResourceObservation = null
        }
        mutableState.value = entries.values.map { it.task }
    }
    private fun trimHistory() {
        val finished = entries.values.filter { it.result.isCompleted }
        finished.take((finished.size - 80).coerceAtLeast(0)).forEach { entries.remove(it.task.id) }
    }
}
