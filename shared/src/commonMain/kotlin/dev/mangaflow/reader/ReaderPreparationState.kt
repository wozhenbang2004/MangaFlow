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
    val originalBytes: Long? = null, val inspectedAt: Long? = null, val freshUntil: Long? = null,
    val receivedBytes: Long = 0L, val expectedBytes: Long? = null,
    val promoted: Boolean = false, val joining: Boolean = false,
)
/** Session-local diagnostics contain only counts, revisions and monotonic durations. */
internal data class ReaderObservationScan(
    val round: Long, val startWindow: Long, val endWindow: Long,
    val candidates: Int, val inspected: Int = 0, val elapsedMillis: Long = 0L,
    val interrupted: Boolean = false,
)
internal data class ReaderObservationDiagnostics(
    val unobserved: Int = 0, val expired: Int = 0, val uncertain: Int = 0,
    val absent: Int = 0, val present: Int = 0,
    val scanning: ReaderObservationScan? = null, val recentScans: List<ReaderObservationScan> = emptyList(),
)
internal data class ReaderPreparationState(
    val sourceId: String = "", val pageCount: Int = 0, val firstVisible: Int = 0, val lastVisible: Int = -1,
    val direction: Int = 1, val windowRevision: Long = 0L, val active: Boolean = false,
    val pauseReason: String? = null, val pages: List<ReaderPreparationPage> = emptyList(),
    val observations: ReaderObservationDiagnostics = ReaderObservationDiagnostics(),
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
    private var scanRound = 0L
    private var scanStartedAt = 0L
    private var scanning: ReaderObservationScan? = null
    private var recentScans = emptyList<ReaderObservationScan>()
    private val mutableState = MutableStateFlow(snapshot)
    val state = mutableState.asStateFlow()

    fun window(owner: Any, first: Int, last: Int, direction: Int, ahead: List<Int>, behind: List<Int>,
        network: Set<Int>, decoded: Set<Int>, active: Boolean, reason: String?) = synchronized(lock) {
        if (this.owner !== owner) finishScanLocked(interrupted = true)
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
                !wanted && role != ReaderPreparationRole.VISIBLE && step.phase != ReaderPreparationPhase.COMPLETE ->
                    ReaderPreparationStep()
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
    /** Resolution already supplied a local original, so no transfer will be queued.
     * Cache presence/bytes still come from artifact inspection, not this task outcome. */
    fun localOriginal(index: Int, owner: Any) = synchronized(lock) {
        if (this.owner !== owner || tickets[index to PrefetchStage.DOWNLOADING]?.let { it in activeTickets } == true)
            return@synchronized
        updateStep(index, PrefetchStage.DOWNLOADING,
            ReaderPreparationStep(ReaderPreparationPhase.COMPLETE, "复用本地原图，无需网络请求"))
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
            finishScanLocked(interrupted = true)
            this.owner = null
            // Layout effects can restart without changing this tracker's exact chapter,
            // page list or headers. Keep only their still-expiring artifact observations;
            // retiring a job must not erase a separately verified cached original.
            rows.toMap().forEach { (index, row) ->
                fun retained(stage: PrefetchStage, step: ReaderPreparationStep) =
                    if (tickets[index to stage]?.let { it in activeTickets } == true) step else ReaderPreparationStep()
                rows[index] = row.copy(resolve = retained(PrefetchStage.RESOLVING, row.resolve),
                    original = retained(PrefetchStage.DOWNLOADING, row.original),
                    decode = retained(PrefetchStage.CHECKING, row.decode))
            }
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
        // A terminal foreground callback knows why a request stopped. The sampler's
        // settled-failure transport summary cannot replace that diagnosis with
        // a generic deferred state. A new active attempt or a newer ticket may proceed.
        if (tickets[key] == ticket && value.settled && value.succeeded != true) {
            val row = rows[index]
            val previous = when (value.stage) {
                PrefetchStage.RESOLVING -> row?.resolve
                PrefetchStage.DOWNLOADING -> row?.original
                PrefetchStage.CHECKING -> row?.decode
                else -> null
            }
            if (previous?.reason != null && previous.phase in setOf(
                    ReaderPreparationPhase.FAILED, ReaderPreparationPhase.DEFERRED)) return@synchronized
        }
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
    /** Layout sizes changed, not original identity or the time of its last verification. */
    fun invalidateLayoutPreviews(retiredChecks: Set<Long>) = synchronized(lock) {
        rows.toMap().forEach { (index, row) ->
            val key = index to PrefetchStage.CHECKING
            val ticket = tickets[key]
            // CHECKING also describes source-native original materialization (for
            // example JM descrambling), not just viewport-sized preview checks. Only
            // retire the exact preview tickets cancelled by the layout host. Independent
            // original/foreground work retains its real stage, even outside visibility.
            val independent = ticket != null && ticket !in retiredChecks
            if (!independent) {
                tickets.remove(key)?.let { activeTickets.remove(it); ticketOwners.remove(it) }
            }
            rows[index] = row.copy(previewResident = null,
                decode = if (independent) row.decode else ReaderPreparationStep())
        }
        publishLocked()
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
    fun artifact(index: Int, original: Boolean?, bytes: Long?, preview: Boolean?,
        freshnessMillis: Long = 4_000L, owner: Any? = null) = synchronized(lock) {
        if (owner != null && this.owner !== owner) return@synchronized
        val now = monotonicTimeMillis()
        rows[index]?.let { rows[index] = it.copy(originalPresent = original, originalBytes = bytes,
            previewResident = preview, inspectedAt = now, freshUntil = now + freshnessMillis) }
    }
    fun beginObservationScan(owner: Any, candidates: Int): Long? = synchronized(lock) {
        if (this.owner !== owner) return@synchronized null
        finishScanLocked(interrupted = true)
        scanStartedAt = monotonicTimeMillis()
        scanning = ReaderObservationScan(++scanRound, snapshot.windowRevision, snapshot.windowRevision, candidates)
        publishLocked()
        scanRound
    }
    fun inspectedObservation(owner: Any, round: Long?) = synchronized(lock) {
        if (this.owner === owner && round != null && scanning?.round == round)
            scanning = scanning?.let { it.copy(inspected = it.inspected + 1) }
    }
    fun finishObservationScan(owner: Any, round: Long?, interrupted: Boolean) = synchronized(lock) {
        if (this.owner === owner && round != null && scanning?.round == round) {
            finishScanLocked(interrupted)
            publishLocked()
        }
    }
    private fun finishScanLocked(interrupted: Boolean) {
        scanning?.let { scan ->
            recentScans = (recentScans + scan.copy(endWindow = snapshot.windowRevision,
                elapsedMillis = (monotonicTimeMillis() - scanStartedAt).coerceAtLeast(0L),
                interrupted = interrupted)).takeLast(8)
        }
        scanning = null
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
        // Count original-artifact observations before expiry masks their value. Never change
        // the value or deadline to make a readiness count look stable.
        val candidates = rows.values.filter { it.role != ReaderPreparationRole.VISIBLE }
        val unobserved = candidates.count { it.inspectedAt == null }
        val expired = candidates.count { it.inspectedAt != null && (it.freshUntil ?: 0L) < now }
        val fresh = candidates.filter { it.inspectedAt != null && (it.freshUntil ?: 0L) >= now }
        val observations = ReaderObservationDiagnostics(unobserved, expired,
            fresh.count { it.originalPresent == null }, fresh.count { it.originalPresent == false },
            fresh.count { it.originalPresent == true },
            scanning?.copy(endWindow = snapshot.windowRevision,
                elapsedMillis = (now - scanStartedAt).coerceAtLeast(0L)), recentScans)
        snapshot = snapshot.copy(observations = observations, pages = rows.values.sortedBy { it.pageIndex }.map { row ->
            if (row.freshUntil?.let { now <= it } == true) row
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
