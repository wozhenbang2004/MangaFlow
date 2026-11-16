package dev.mangaflow.reader

import androidx.compose.runtime.staticCompositionLocalOf
import dev.mangaflow.experience.AdmissionWaitReason
import dev.mangaflow.experience.PrefetchStage
import dev.mangaflow.experience.PrefetchWorkSnapshot
import dev.mangaflow.storage.DownloadPhase
import dev.mangaflow.storage.DownloadProgress
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.storage.StorageLock
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import dev.mangaflow.experience.PrefetchWork

internal enum class ReaderPreparationPhase { QUEUED, ACTIVE, COMPLETE, DEFERRED, FAILED, SKIPPED }
internal enum class ReaderPreparationRole { VISIBLE, AHEAD, BEHIND }
internal data class ReaderPreparationStep(val phase: ReaderPreparationPhase = ReaderPreparationPhase.SKIPPED,
    val reason: String? = null)
internal data class ReaderPreparationCounts(val queued: Int = 0, val active: Int = 0, val complete: Int = 0,
    val deferred: Int = 0, val failed: Int = 0)
internal data class ReaderPreparationPage(
    val pageIndex: Int, val role: ReaderPreparationRole,
    val resolve: ReaderPreparationStep = ReaderPreparationStep(),
    val original: ReaderPreparationStep = ReaderPreparationStep(),
    val decode: ReaderPreparationStep = ReaderPreparationStep(),
    val originalPresent: Boolean? = null, val previewResident: Boolean? = null,
    val originalBytes: Long? = null, val inspectedAt: Long? = null,
    val receivedBytes: Long = 0L, val expectedBytes: Long? = null,
    val promoted: Boolean = false, val joining: Boolean = false,
)
internal data class ReaderPreparationState(
    val sourceId: String = "", val pageCount: Int = 0, val firstVisible: Int = 0, val lastVisible: Int = -1,
    val direction: Int = 1, val windowRevision: Long = 0L, val active: Boolean = false,
    val pauseReason: String? = null, val pages: List<ReaderPreparationPage> = emptyList(),
) {
    val aheadTarget get() = pages.count { it.role == ReaderPreparationRole.AHEAD }
    val behindTarget get() = pages.count { it.role == ReaderPreparationRole.BEHIND }
    val aheadReady get() = pages.count { it.role == ReaderPreparationRole.AHEAD && it.originalPresent == true }
    val behindReady get() = pages.count { it.role == ReaderPreparationRole.BEHIND && it.originalPresent == true }
    private fun count(select: (ReaderPreparationPage) -> ReaderPreparationStep): ReaderPreparationCounts {
        val steps = pages.map(select)
        return ReaderPreparationCounts(steps.count { it.phase == ReaderPreparationPhase.QUEUED },
            steps.count { it.phase == ReaderPreparationPhase.ACTIVE }, steps.count { it.phase == ReaderPreparationPhase.COMPLETE },
            steps.count { it.phase == ReaderPreparationPhase.DEFERRED }, steps.count { it.phase == ReaderPreparationPhase.FAILED })
    }
    val resolving get() = count { it.resolve }
    val originals get() = count { it.original }
    val decoding get() = count { it.decode }
}

/** One selected chapter's live window, never an application-wide hit/transfer counter. */
internal class ReaderPreparationTracker(private val sourceId: String, private val pageCount: Int) {
    private val lock = StorageLock()
    private var next = 0L
    private var owner: Any? = null
    private var snapshot = ReaderPreparationState(sourceId, pageCount)
    private val rows = mutableMapOf<Int, ReaderPreparationPage>()
    private val tickets = mutableMapOf<Pair<Int, PrefetchStage>, Long>()
    private val activeTickets = mutableSetOf<Long>()
    private val ticketOwners = mutableMapOf<Long, Any?>()
    private val mutableState = MutableStateFlow(snapshot)
    val state = mutableState.asStateFlow()

    fun window(owner: Any, first: Int, last: Int, direction: Int, ahead: List<Int>, behind: List<Int>,
        network: Set<Int>, decoded: Set<Int>, active: Boolean, reason: String?) = synchronized(lock) {
        this.owner = owner
        val roles = linkedMapOf<Int, ReaderPreparationRole>()
        (first..last).filter { it in 0 until pageCount }.forEach { roles[it] = ReaderPreparationRole.VISIBLE }
        ahead.forEach { roles[it] = ReaderPreparationRole.AHEAD }; behind.forEach { roles[it] = ReaderPreparationRole.BEHIND }
        rows.keys.retainAll(roles.keys)
        tickets.filterKeys { it.first !in roles }.values.forEach { activeTickets.remove(it); ticketOwners.remove(it) }
        tickets.keys.removeAll { it.first !in roles }
        roles.forEach { (index, role) ->
            val previous = rows[index] ?: ReaderPreparationPage(index, role)
            fun requested(step: ReaderPreparationStep, wanted: Boolean) = when {
                !wanted && role != ReaderPreparationRole.VISIBLE -> ReaderPreparationStep()
                wanted && step.phase == ReaderPreparationPhase.SKIPPED -> ReaderPreparationStep(ReaderPreparationPhase.QUEUED)
                else -> step
            }
            rows[index] = previous.copy(role = role,
                resolve = requested(previous.resolve, index in network || index in decoded),
                original = requested(previous.original, index in network), decode = requested(previous.decode, index in decoded),
                promoted = role == ReaderPreparationRole.VISIBLE && active)
        }
        val changed = snapshot.firstVisible != first || snapshot.lastVisible != last ||
            snapshot.direction != direction || snapshot.pages.associate { it.pageIndex to it.role } != roles
        snapshot = snapshot.copy(firstVisible = first, lastVisible = last, direction = direction,
            windowRevision = snapshot.windowRevision + if (changed) 1 else 0, active = active, pauseReason = reason)
        publishLocked()
    }
    fun close(owner: Any) = synchronized(lock) {
        // A layout/window effect can restart while the same visible image keeps transferring.
        // Retire that effect's work only; the foreground attempt owns its own ticket lifetime.
        val owned = ticketOwners.filterValues { it === owner }.keys.toSet()
        activeTickets.removeAll(owned)
        owned.forEach { ticketOwners.remove(it) }
        tickets.entries.removeAll { it.value in owned }
        if (this.owner === owner) {
            this.owner = null
            rows.keys.retainAll(tickets.filterValues { it in activeTickets }.keys.map { it.first }.toSet())
            snapshot = snapshot.copy(active = false)
            publishLocked()
        }
    }
    fun begin(index: Int, stage: PrefetchStage, owner: Any? = null): Long = synchronized(lock) {
        val id = ++next
        activeTickets += id
        ticketOwners[id] = owner
        val previous = tickets.put(index to stage, id)
        if (previous != null && previous !in tickets.values) { activeTickets.remove(previous); ticketOwners.remove(previous) }
        updateStep(index, stage, ReaderPreparationStep(ReaderPreparationPhase.QUEUED))
        id
    }
    fun observe(index: Int, ticket: Long, value: PrefetchWorkSnapshot) = synchronized(lock) {
        if (ticket !in activeTickets || value.stage == PrefetchStage.QUEUED || index !in rows) return@synchronized
        val key = index to value.stage
        if ((tickets[key] ?: Long.MIN_VALUE) > ticket) return@synchronized
        tickets[key] = ticket
        val phase = when {
            value.settled && value.succeeded == true -> ReaderPreparationPhase.COMPLETE
            value.settled -> ReaderPreparationPhase.DEFERRED
            value.admitted -> ReaderPreparationPhase.ACTIVE
            else -> ReaderPreparationPhase.QUEUED
        }
        updateStep(index, value.stage, ReaderPreparationStep(phase, value.wait?.reason?.let(::preparationWaitLabel)))
        publishLocked()
    }
    fun end(index: Int, ticket: Long, stage: PrefetchStage, phase: ReaderPreparationPhase,
        reason: String? = null) = synchronized(lock) {
        if (ticket in activeTickets && (tickets[index to stage] ?: Long.MIN_VALUE) <= ticket) {
            tickets[index to stage] = ticket
            updateStep(index, stage, ReaderPreparationStep(phase, reason)); publishLocked()
        }
    }
    fun finish(ticket: Long) = synchronized(lock) { activeTickets.remove(ticket); ticketOwners.remove(ticket) }
    fun retire(index: Int) = synchronized(lock) {
        tickets.filterKeys { it.first == index }.values.forEach { activeTickets.remove(it); ticketOwners.remove(it) }
        tickets.keys.removeAll { it.first == index }
    }
    fun transfer(index: Int, ticket: Long, value: PrefetchWorkSnapshot) = synchronized(lock) {
        if (value.stage != PrefetchStage.DOWNLOADING || ticket !in activeTickets ||
            tickets[index to PrefetchStage.DOWNLOADING] != ticket) return@synchronized
        rows[index]?.let { rows[index] = it.copy(receivedBytes = value.receivedBytes, expectedBytes = value.expectedBytes) }
    }
    /** This flow is already pinned by DownloadStore to the visible page's exact transfer. */
    fun download(index: Int, ticket: Long, progress: DownloadProgress) = synchronized(lock) {
        if (ticket !in activeTickets || (tickets[index to PrefetchStage.DOWNLOADING] ?: Long.MIN_VALUE) > ticket) return@synchronized
        tickets[index to PrefetchStage.DOWNLOADING] = ticket
        val phase = when (progress.phase) {
            DownloadPhase.CONNECTING, DownloadPhase.FIRST_BYTE_WAIT, DownloadPhase.TRANSFERRING -> ReaderPreparationPhase.ACTIVE
            DownloadPhase.COMPLETED -> ReaderPreparationPhase.COMPLETE
            DownloadPhase.FAILED -> ReaderPreparationPhase.FAILED
            DownloadPhase.PAUSED, DownloadPhase.WAITING -> ReaderPreparationPhase.DEFERRED
            else -> ReaderPreparationPhase.QUEUED
        }
        updateStep(index, PrefetchStage.DOWNLOADING, ReaderPreparationStep(phase,
            progress.waitingReason?.label ?: when (progress.phase) {
                DownloadPhase.VERIFYING -> "原图正在验证"; DownloadPhase.COMMITTING -> "原图正在保存"; else -> null
            }))
        rows[index]?.let { rows[index] = it.copy(receivedBytes = progress.transferredBytes,
            expectedBytes = progress.expectedBytes.takeIf { progress.receivedBytes == progress.transferredBytes }) }
    }
    fun handoff(index: Int, joining: Boolean) = synchronized(lock) {
        rows[index]?.let { rows[index] = it.copy(joining = joining) }; publishLocked()
    }
    fun artifact(index: Int, original: Boolean?, bytes: Long?, preview: Boolean?) = synchronized(lock) {
        rows[index]?.let { rows[index] = it.copy(originalPresent = original, originalBytes = bytes,
            previewResident = preview, inspectedAt = monotonicTimeMillis()) }
    }
    fun sample() = synchronized(lock) { publishLocked() }
    private fun updateStep(index: Int, stage: PrefetchStage, step: ReaderPreparationStep) {
        val row = rows[index] ?: return
        rows[index] = when (stage) {
            PrefetchStage.RESOLVING -> row.copy(resolve = step)
            PrefetchStage.DOWNLOADING -> row.copy(original = step)
            PrefetchStage.CHECKING -> row.copy(decode = step)
            PrefetchStage.QUEUED -> row
        }
    }
    private fun publishLocked() {
        val now = monotonicTimeMillis()
        snapshot = snapshot.copy(pages = rows.values.sortedBy { it.pageIndex }.map { row ->
            if (row.inspectedAt?.let { now - it <= 4_000L } == true) row
            else row.copy(originalPresent = null, previewResident = null, originalBytes = null)
        })
        mutableState.value = snapshot
    }
}

internal class ReaderPreparationWorkContext(val work: PrefetchWork) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ReaderPreparationWorkContext>
}

internal val LocalReaderPreparationTracker = staticCompositionLocalOf<ReaderPreparationTracker?> { null }
internal fun preparationWaitLabel(reason: AdmissionWaitReason): String = when (reason) {
    AdmissionWaitReason.NO_NETWORK -> "网络未连接"; AdmissionWaitReason.STORAGE_LOW -> "存储空间保护"
    AdmissionWaitReason.THERMAL -> "设备温度保护"; AdmissionWaitReason.MEMORY_PRESSURE -> "内存保护"
    AdmissionWaitReason.BACKGROUND_RESTRICTED -> "应用不在前台"; AdmissionWaitReason.READING_PRIORITY -> "当前页优先"
    AdmissionWaitReason.SOURCE_BACKOFF -> "来源冷却中"; AdmissionWaitReason.SOURCE_LIMIT -> "来源并发上限"
    AdmissionWaitReason.GLOBAL_LIMIT -> "等待处理名额"; AdmissionWaitReason.CHAPTER_LIMIT -> "等待章节名额"
    AdmissionWaitReason.FAIR_QUEUE -> "等待其他来源轮次"
}
