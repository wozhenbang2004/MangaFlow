package dev.mangaflow.experience

import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.monotonicTimeMillis
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okio.ByteString.Companion.encodeUtf8
import okio.FileMetadata
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM

/** Coil 3.2 has a read-only maximum. Enforce a live maximum around its existing LRU. */
internal class ExperienceMemoryCache(private val cache: MemoryCache, private val automaticTotal: Long) : MemoryCache {
    internal val automaticBasisBytes: Long get() = automaticTotal
    private val lock = StorageLock()
    private var limit = (automaticTotal * .18).toLong().coerceAtLeast(0L)
    private var revision = -1L
    private var idleMillis = 0L
    private val accessed = mutableMapOf<MemoryCache.Key, Long>()
    private val mutableExpiryDeadline = MutableStateFlow<Long?>(null)
    internal val expiryDeadline = mutableExpiryDeadline.asStateFlow()
    private var expiredEntries = 0L
    private var expiredBytes = 0L
    private var evictedEntries = 0L
    private var evictedBytes = 0L
    private var rangeEntries = 0L
    private var rangeBytes = 0L
    private var clearedEntries = 0L
    private var clearedBytes = 0L
    private var lastReclaimedAt: Long? = null
    override val size: Long get() = synchronized(lock) { cache.size }
    override val maxSize: Long get() = synchronized(lock) { limit }
    override val keys: Set<MemoryCache.Key> get() = synchronized(lock) { cache.keys }
    override fun get(key: MemoryCache.Key): MemoryCache.Value? = synchronized(lock) {
        val now = monotonicTimeMillis()
        val last = accessed[key]
        if (idleMillis > 0L && last != null && now - last >= idleMillis) {
            val before = cache.size
            val removed = if (cache.remove(key)) 1 else 0
            accessed.remove(key)
            recordRemoval(Removal.EXPIRED, removed, before, now)
            updateExpiryDeadline(now)
            null
        } else cache[key]?.also {
            accessed[key] = now
            updateExpiryDeadline(now)
        } ?: run {
            accessed.remove(key)
            updateExpiryDeadline(now)
            null
        }
    }
    override fun set(key: MemoryCache.Key, value: MemoryCache.Value) = synchronized(lock) {
        val now = monotonicTimeMillis()
        trimExpired(now)
        // Match Coil's oversized-entry rule at our live limit: one large decode must not
        // flush the entire reusable LRU only to evict itself immediately afterwards.
        if (limit == 0L || value.image.size > limit) {
            val before = cache.size
            val removed = if (cache.remove(key)) 1 else 0
            accessed.remove(key)
            recordRemoval(Removal.CAPACITY, removed, before, now)
            updateExpiryDeadline(now)
            return@synchronized
        }
        cache[key] = value
        accessed[key] = now
        trimCapacity(limit, now)
        updateExpiryDeadline(now)
        Unit
    }
    override fun remove(key: MemoryCache.Key) = synchronized(lock) {
        accessed.remove(key)
        cache.remove(key).also { updateExpiryDeadline(monotonicTimeMillis()) }
    }
    override fun trimToSize(size: Long) = synchronized(lock) {
        val now = monotonicTimeMillis()
        trimCapacity(minOf(size, limit).coerceAtLeast(0L), now)
        updateExpiryDeadline(now)
    }
    /** Drops reusable references only; displayed/native images retain their own ownership. */
    internal fun removeOutsideWindow(keys: Set<String>) = synchronized(lock) {
        val now = monotonicTimeMillis()
        val before = cache.size
        var removed = 0
        cache.keys.filter { it.key in keys }.forEach { key ->
            // A recently reused entry may belong to another view not in the reader registry.
            val last = accessed[key] ?: return@forEach
            if (now - last > 30_000L && cache.remove(key)) { accessed.remove(key); removed++ }
        }
        recordRemoval(Removal.RANGE, removed, before, now)
        updateExpiryDeadline(now)
        cache.keys.map { it.key }.toSet()
    }
    /** Manual cache cleanup removes reusable references, never native/display ownership. */
    internal fun removeReviewed(keys: Set<String>) = synchronized(lock) {
        val now = monotonicTimeMillis()
        val before = cache.size
        var removed = 0
        try {
            cache.keys.filter { it.key in keys }.forEach { key ->
                if (cache.remove(key)) { accessed.remove(key); removed++ }
            }
        } finally {
            recordRemoval(Removal.CLEARED, removed, before, now)
            updateExpiryDeadline(now)
        }
    }
    override fun clear() { clearAndMeasure() }
    fun clearAndMeasure(): Long = synchronized(lock) {
        val before = cache.size
        val entries = cache.keys.size
        cache.clear()
        accessed.clear()
        recordRemoval(Removal.CLEARED, entries, before, monotonicTimeMillis())
        mutableExpiryDeadline.value = null
        (before - cache.size).coerceAtLeast(0L)
    }
    fun snapshot(): ExperienceMemoryCacheUsage = synchronized(lock) {
        val now = monotonicTimeMillis()
        trimExpired(now)
        ExperienceMemoryCacheUsage(cache.size, limit, cache.keys.size,
            (idleMillis / 60_000L).toInt(), now, nextExpiry(), lastReclaimedAt,
            expiredEntries, expiredBytes, evictedEntries, evictedBytes, clearedEntries, clearedBytes, rangeEntries, rangeBytes)
    }
    private fun trimExpired(now: Long) {
        if (idleMillis > 0L) {
            val before = cache.size
            var removed = 0
            val iterator = accessed.iterator()
            while (iterator.hasNext()) {
                val (key, last) = iterator.next()
                if (now - last >= idleMillis) {
                    if (cache.remove(key)) removed++
                    iterator.remove()
                }
            }
            recordRemoval(Removal.EXPIRED, removed, before, now)
        }
        accessed.keys.retainAll(cache.keys)
        updateExpiryDeadline(now)
    }
    private fun trimCapacity(target: Long, now: Long) {
        val before = cache.size
        val entries = cache.keys.size
        cache.trimToSize(target)
        val retained = cache.keys
        accessed.keys.retainAll(retained)
        recordRemoval(Removal.CAPACITY, (entries - retained.size).coerceAtLeast(0), before, now)
    }
    private enum class Removal { EXPIRED, CAPACITY, CLEARED, RANGE }
    private fun recordRemoval(reason: Removal, count: Int, before: Long, now: Long) {
        val bytes = (before - cache.size).coerceAtLeast(0L)
        if (count == 0 && bytes == 0L) return
        lastReclaimedAt = now
        when (reason) {
            Removal.RANGE -> {
                rangeEntries = addCount(rangeEntries, count.toLong())
                rangeBytes = addCount(rangeBytes, bytes)
            }
            Removal.EXPIRED -> {
                expiredEntries = addCount(expiredEntries, count.toLong())
                expiredBytes = addCount(expiredBytes, bytes)
            }
            Removal.CAPACITY -> {
                evictedEntries = addCount(evictedEntries, count.toLong())
                evictedBytes = addCount(evictedBytes, bytes)
            }
            Removal.CLEARED -> {
                clearedEntries = addCount(clearedEntries, count.toLong())
                clearedBytes = addCount(clearedBytes, bytes)
            }
        }
    }
    private fun addCount(value: Long, amount: Long): Long =
        if (amount >= Long.MAX_VALUE - value) Long.MAX_VALUE else value + amount
    private fun nextExpiry(): Long? = if (idleMillis == 0L) null else
        accessed.values.minOrNull()?.let { if (it > Long.MAX_VALUE - idleMillis) Long.MAX_VALUE else it + idleMillis }
    private fun updateExpiryDeadline(now: Long, reset: Boolean = false) {
        if (idleMillis == 0L || accessed.isEmpty()) {
            mutableExpiryDeadline.value = null
            return
        }
        val scheduled = mutableExpiryDeadline.value
        // Accesses and newer inserts can only postpone expiry. Leave the existing timer
        // running; at its deadline one sweep computes the next deadline from real accesses.
        if (reset || scheduled == null || scheduled <= now) mutableExpiryDeadline.value = nextExpiry()
    }
    fun applyBudget(fraction: Double, manualBytes: Long?, version: Long, idleMinutes: Int = 0) = synchronized(lock) {
        if (version < revision) return@synchronized
        revision = version
        val nextIdleMillis = idleMinutes.coerceAtLeast(0) * 60_000L
        val retentionChanged = idleMillis != nextIdleMillis
        idleMillis = nextIdleMillis
        val protectedLimit = (automaticTotal * fraction).toLong().coerceAtLeast(0L)
        limit = manualBytes?.coerceAtLeast(0L) ?: protectedLimit
        val now = monotonicTimeMillis()
        trimExpired(now)
        trimCapacity(limit, now)
        updateExpiryDeadline(now, reset = retentionChanged)
    }
}

internal enum class PrefetchDiskState(val waitReason: String?) {
    AVAILABLE(null),
    FULL("原图缓存已满，等待腾出空间"),
    NO_ALLOCATION("原图缓存暂无可写额度"),
    UNAVAILABLE("原图缓存暂不可用"),
}

internal data class PrefetchDiskQuota(val state: PrefetchDiskState, val usedBytes: Long?, val limitBytes: Long?)

internal val coilCleanFilename = Regex("[a-f0-9]{64}\\.[01]")

/** One physical inventory for shared-cache accounting and the image write gate. An unreadable
 * directory is unknown, never an empty cache. A cache just removed by the OS can be empty. */
internal fun imageCacheFiles(directory: Path, fs: FileSystem = FileSystem.SYSTEM): Map<String, FileMetadata> {
    val paths = try {
        fs.list(directory)
    } catch (failure: IOException) {
        val parent = directory.parent ?: throw failure
        if (directory !in fs.list(parent)) return emptyMap()
        throw failure
    }
    val files = mutableMapOf<String, FileMetadata>()
    for (path in paths) {
        val meta = try { fs.metadata(path) } catch (_: FileNotFoundException) { continue }
        if (meta.isRegularFile) {
            if (meta.size == null) throw IOException("Image cache file size unavailable")
            files[path.name] = meta
        }
    }
    return files
}

/**
 * One directory owner, one stable ImageLoader cache identity. Original snapshots can live for
 * an entire reading session; resizing waits for every reader/editor lease without clearing.
 */
internal class ExperienceDiskCache(
    override val directory: Path,
    initialBytes: Long,
    private val changed: () -> Unit,
    private val workFinished: () -> Unit,
    private val publishQuota: (PrefetchDiskQuota) -> Unit,
) : DiskCache {
    private val lock = StorageLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val fileSystem: FileSystem = FileSystem.SYSTEM
    private var target = initialBytes.coerceAtLeast(0L)
    private var applied = target
    private var delegate: DiskCache? = build()
    private var leases = 0
    private val keyLeases = mutableMapOf<String, Int>()
    private val removedWhileLeased = mutableSetOf<String>()
    private val reclaimWhenReleased = mutableSetOf<String>()
    private var clearWhenReleased = false
    private var revision = -1L
    private var scheduled = false
    private var closed = false
    private var error: String? = null
    private var quota: PrefetchDiskQuota? = null
    private var fileGeneration = 0L
    // Coil restores CLEAN lengths from its journal. After OS cache eviction those lengths can
    // outlive the files. Keep a corrected measurement current through the owner's own writes.
    private var correctedBytes: Long? = null
    private var needsFileMeasurement = false
    private var verification = 0L
    private var verifiedSequence = -1L
    private var requestedWhileBlocked = false
    override val size: Long get() = synchronized(lock) { delegate?.size ?: 0L }
    override val maxSize: Long get() = synchronized(lock) { if (delegate == null) 0L else applied }
    val resizePending: Boolean get() = synchronized(lock) { target != applied || error != null }
    val acceptsPrefetchWrites: Boolean get() = synchronized(lock) {
        (quota ?: updateQuota()).state == PrefetchDiskState.AVAILABLE
    }
    val resizeError: String? get() = synchronized(lock) { error }

    // The journal changes through this single owner. Notify even when a reader holds a
    // snapshot and the accounting-only publication of the new maximum is still pending.
    private fun measuredSize(): Long? = try { delegate?.size } catch (_: Exception) { null }
    internal fun sampleSize(): Long? = synchronized(lock) {
        updateQuota().usedBytes
    }
    internal fun imageFileGeneration(): Long = synchronized(lock) { fileGeneration }
    internal fun requestBlockedCheck(): Long? = synchronized(lock) {
        if (closed || requestedWhileBlocked || quota?.state == PrefetchDiskState.AVAILABLE) return@synchronized null
        requestedWhileBlocked = true
        verification
    }
    internal fun stillNeedsCheck(checkpoint: Long): Boolean = synchronized(lock) {
        !closed && quota?.state != PrefetchDiskState.AVAILABLE && checkpoint == verification
    }
    private fun updateQuota(journal: Long? = if (closed) null else measuredSize()): PrefetchDiskQuota {
        val bytes = if (needsFileMeasurement || journal == null) null else correctedBytes ?: journal
        val next = when {
            closed || delegate == null -> PrefetchDiskState.UNAVAILABLE
            target == 0L -> PrefetchDiskState.NO_ALLOCATION
            bytes == null -> PrefetchDiskState.UNAVAILABLE
            bytes >= target -> PrefetchDiskState.FULL
            else -> PrefetchDiskState.AVAILABLE
        }
        val value = PrefetchDiskQuota(next, bytes, target.takeUnless { closed })
        if (next == PrefetchDiskState.AVAILABLE) requestedWhileBlocked = false
        if (quota != value) {
            quota = value
            publishQuota(value)
        }
        return value
    }
    // The shared storage owner performs value-aware eviction. An independent Coil LRU
    // ceiling could otherwise delete a protected reading window behind that owner's back.
    private fun build() = DiskCache.Builder().directory(directory).fileSystem(fileSystem)
        .maxSizeBytes(Long.MAX_VALUE).build()

    fun resize(bytes: Long, version: Long, observedFiles: Long? = null, generation: Long? = null,
        allowIncrease: Boolean = true) = synchronized(lock) {
        if (closed) return@synchronized
        val confirmsFiles = observedFiles != null && generation == fileGeneration && version >= verifiedSequence
        if (version >= revision) {
            revision = version
            target = if (allowIncrease) bytes.coerceAtLeast(0L) else minOf(target, bytes.coerceAtLeast(0L))
        } else if (!confirmsFiles) return@synchronized
        val journal = measuredSize()
        if (confirmsFiles) {
            correctedBytes = observedFiles.takeUnless { it == journal }
            needsFileMeasurement = false
            verifiedSequence = version
            verification++
        }
        updateQuota(journal)
        scheduleResize()
    }
    private fun scheduleResize() {
        if (closed || scheduled || leases != 0 || target == applied && error == null) return
        scheduled = true
        scope.launch {
            synchronized(lock) {
                scheduled = false
                if (closed || leases != 0 || target == applied && error == null) return@synchronized
                try {
                    // The journal owner stays unchanged; the global coordinator owns trimming.
                    delegate?.size
                    applied = target
                    error = null
                } catch (_: Exception) {
                    error = "缓存容量暂未调整，请稍后重试"
                }
                updateQuota()
            }
            changed()
        }
    }

    private fun snapshotLocked(key: String): DiskCache.Snapshot? {
        if (closed) return null
        val snapshot = delegate?.openSnapshot(key) ?: return null
        acquireLease(key)
        return SnapshotLease(key, snapshot)
    }

    override fun openSnapshot(key: String): DiskCache.Snapshot? = synchronized(lock) { snapshotLocked(key) }
    internal fun openInspectedSnapshot(key: String, onMiss: (Boolean) -> Unit): DiskCache.Snapshot? = synchronized(lock) {
        snapshotLocked(key).also {
            if (it == null) onMiss(!closed && delegate != null && key !in keyLeases &&
                quota?.state != PrefetchDiskState.UNAVAILABLE)
        }
    }
    override fun openEditor(key: String): DiskCache.Editor? = synchronized(lock) {
        // Foreground original images must still reach disk while a long-lived reader
        // snapshot delays resizing. Optional prefetch is gated before network execution.
        if (closed || target <= 0L) return@synchronized null
        delegate?.openEditor(key)?.let { acquireLease(key); EditorLease(key, it) }
    }
    /** Measure the same two files the shared inventory counts. An IO error is not zero bytes. */
    private fun entryBytes(hash: String): Long? {
        var total = 0L
        for (index in 0..1) {
            val meta = try {
                fileSystem.metadata(directory / "$hash.$index")
            } catch (_: FileNotFoundException) {
                continue
            } catch (_: IOException) {
                return null
            }
            total += meta.size ?: return null
        }
        return total
    }
    private inline fun <T> changeEntry(key: String, operation: () -> T): T {
        val corrected = correctedBytes
        val hash = corrected?.let { key.encodeUtf8().sha256().hex() }
        val before = hash?.let(::entryBytes)
        try {
            return operation()
        } finally {
            fileGeneration++
            if (corrected != null && hash != null) {
                val after = entryBytes(hash)
                correctedBytes = if (before != null && after != null) (corrected + after - before).coerceAtLeast(0L) else null
                needsFileMeasurement = correctedBytes == null
            }
            updateQuota()
        }
    }
    override fun remove(key: String): Boolean = synchronized(lock) {
        changeEntry(key) {
            (delegate?.remove(key) == true).also { if (it && key in keyLeases) removedWhileLeased += key }
        }
    }
    private fun physicalSize(): Long? = try {
        imageCacheFiles(directory, fileSystem).entries.sumOf { (name, meta) ->
            if (coilCleanFilename.matches(name)) meta.size ?: 0L else 0L
        }
    } catch (_: IOException) { null }
    private fun clearFiles(): Long {
        val before = physicalSize()
        try {
            delegate?.clear()
            removedWhileLeased += keyLeases.keys
        } finally {
            fileGeneration++
            val after = physicalSize()
            val journal = measuredSize()
            correctedBytes = after.takeUnless { it == journal }
            needsFileMeasurement = after == null
            updateQuota(journal)
            workFinished()
        }
        val after = quota?.usedBytes
        return if (before != null && after != null) (before - after).coerceAtLeast(0L) else 0L
    }
    override fun clear() = synchronized(lock) { clearFiles(); Unit }
    override fun shutdown() = synchronized(lock) {
        closed = true
        delegate?.shutdown()
        delegate = null
        updateQuota()
        Unit
    }

    /** Management never edits Coil's journal or removes an entry held by a reader/editor. */
    internal fun reclaim(key: String, bytes: Long, modifiedAt: Long, allowed: () -> Boolean): Boolean = synchronized(lock) {
        if (closed || !allowed()) return@synchronized false
        if ((keyLeases[key] ?: 0) > 0) {
            reclaimWhenReleased += key
            return@synchronized false
        }
        val snapshot = delegate?.openSnapshot(key) ?: return@synchronized false
        val unchanged = try {
            val data = fileSystem.metadata(snapshot.data)
            (data.size ?: 0L) + (fileSystem.metadata(snapshot.metadata).size ?: 0L) == bytes &&
                (data.lastModifiedAtMillis ?: 0L) == modifiedAt
        } finally { snapshot.close() }
        unchanged && changeEntry(key) { delegate?.remove(key) == true }
    }
    internal fun clearWhenIdle(allowed: () -> Boolean): Long = synchronized(lock) {
        if (closed || !allowed()) return@synchronized 0L
        if (leases != 0) {
            clearWhenReleased = true
            return@synchronized 0L
        }
        clearFiles()
    }

    private fun acquireLease(key: String) { leases++; keyLeases[key] = (keyLeases[key] ?: 0) + 1 }
    private fun releaseLease(key: String, wrote: Boolean = false) {
        leases--
        val remaining = (keyLeases[key] ?: 1) - 1
        if (remaining == 0) { keyLeases.remove(key); removedWhileLeased.remove(key) } else keyLeases[key] = remaining
        check(leases >= 0)
        scheduleResize()
        val retryKey = remaining == 0 && reclaimWhenReleased.remove(key)
        val retryClear = leases == 0 && clearWhenReleased
        if (retryClear) clearWhenReleased = false
        // Header checks and cache-only preview reads can close dozens of snapshots per
        // window. Only writers or a release that actually unblocks eviction need a scan.
        if (wrote || retryKey || retryClear) workFinished()
    }
    private inner class SnapshotLease(private val key: String, private val snapshot: DiskCache.Snapshot) : DiskCache.Snapshot {
        private var finished = false
        override val metadata: Path get() = snapshot.metadata
        override val data: Path get() = snapshot.data
        override fun close() = synchronized(lock) {
            if (!finished) {
                finished = true
                try {
                    if (key in removedWhileLeased && keyLeases[key] == 1) changeEntry(key) { snapshot.close() }
                    else snapshot.close()
                } finally { releaseLease(key) }
            }
        }
        override fun closeAndOpenEditor(): DiskCache.Editor? = synchronized(lock) {
            if (finished) return@synchronized null
            finished = true
            var editor: DiskCache.Editor? = null
            try {
                if (key in removedWhileLeased && keyLeases[key] == 1) changeEntry(key) { snapshot.close() }
                else if (!closed && target > 0L) editor = snapshot.closeAndOpenEditor()
                else snapshot.close()
                editor?.let { EditorLease(key, it) }
            } finally { if (editor == null) releaseLease(key) }
        }
    }
    private inner class EditorLease(private val key: String, private val editor: DiskCache.Editor) : DiskCache.Editor {
        private var finished = false
        override val metadata: Path get() = editor.metadata
        override val data: Path get() = editor.data
        override fun commit() = synchronized(lock) {
            if (!finished) {
                finished = true
                try { changeEntry(key) { editor.commit() } } finally { releaseLease(key, wrote = true) }
            }
        }
        override fun abort() = synchronized(lock) {
            if (!finished) {
                finished = true
                try { changeEntry(key) { editor.abort() } } finally { releaseLease(key, wrote = true) }
            }
        }
        override fun commitAndOpenSnapshot(): DiskCache.Snapshot? = synchronized(lock) {
            if (finished) return@synchronized null
            finished = true
            var snapshot: DiskCache.Snapshot? = null
            try {
                snapshot = changeEntry(key) { editor.commitAndOpenSnapshot() }
                snapshot?.let { SnapshotLease(key, it) }
            } finally {
                if (snapshot == null) releaseLease(key, wrote = true)
                else workFinished()
            }
        }
    }
}
