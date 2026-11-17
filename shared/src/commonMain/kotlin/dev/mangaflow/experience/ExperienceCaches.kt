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
import okio.FileSystem
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

/**
 * One directory owner, one stable ImageLoader cache identity. Original snapshots can live for
 * an entire reading session; resizing waits for every reader/editor lease without clearing.
 */
internal class ExperienceDiskCache(
    override val directory: Path,
    initialBytes: Long,
    private val changed: () -> Unit,
    private val workFinished: () -> Unit,
) : DiskCache {
    private val lock = StorageLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val fileSystem: FileSystem = FileSystem.SYSTEM
    private var target = initialBytes.coerceAtLeast(0L)
    private var applied = target
    private var delegate: DiskCache? = build()
    private var leases = 0
    private val keyLeases = mutableMapOf<String, Int>()
    private var revision = -1L
    private var scheduled = false
    private var closed = false
    private var error: String? = null
    override val size: Long get() = synchronized(lock) { delegate?.size ?: 0L }
    override val maxSize: Long get() = synchronized(lock) { if (delegate == null) 0L else applied }
    val resizePending: Boolean get() = synchronized(lock) { target != applied || error != null }
    /** Pending budget publication must not block writes already below the new target. */
    val acceptsPrefetchWrites: Boolean get() = synchronized(lock) {
        // Reader snapshots may hold leases through an entire session. The stable journal and
        // coordinator still enforce actual capacity while publishing a smaller target waits.
        !closed && error == null && delegate != null && target > 0L && (delegate?.size ?: 0L) < target
    }
    val resizeError: String? get() = synchronized(lock) { error }
    // The shared storage owner performs value-aware eviction. An independent Coil LRU
    // ceiling could otherwise delete a protected reading window behind that owner's back.
    private fun build() = DiskCache.Builder().directory(directory).fileSystem(fileSystem)
        .maxSizeBytes(Long.MAX_VALUE).build()

    fun resize(bytes: Long, version: Long) = synchronized(lock) {
        if (closed || version < revision) return@synchronized
        revision = version
        target = bytes.coerceAtLeast(0L)
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
            }
            changed()
        }
    }

    override fun openSnapshot(key: String): DiskCache.Snapshot? = synchronized(lock) {
        if (closed) return@synchronized null
        delegate?.openSnapshot(key)?.let { acquireLease(key); SnapshotLease(key, it) }
    }
    override fun openEditor(key: String): DiskCache.Editor? = synchronized(lock) {
        // Foreground original images must still reach disk while a long-lived reader
        // snapshot delays resizing. Optional prefetch is gated before network execution.
        if (closed || target <= 0L) return@synchronized null
        delegate?.openEditor(key)?.let { acquireLease(key); EditorLease(key, it) }
    }
    override fun remove(key: String): Boolean = synchronized(lock) { delegate?.remove(key) ?: false }
    override fun clear() = synchronized(lock) { delegate?.clear(); Unit }
    override fun shutdown() = synchronized(lock) { closed = true; delegate?.shutdown(); delegate = null }

    /** Management never edits Coil's journal or removes an entry held by a reader/editor. */
    internal fun reclaim(key: String, bytes: Long, modifiedAt: Long, allowed: () -> Boolean): Boolean = synchronized(lock) {
        if (closed || (keyLeases[key] ?: 0) > 0 || !allowed()) return@synchronized false
        val snapshot = delegate?.openSnapshot(key) ?: return@synchronized false
        val unchanged = try {
            val data = fileSystem.metadata(snapshot.data)
            (data.size ?: 0L) + (fileSystem.metadata(snapshot.metadata).size ?: 0L) == bytes &&
                (data.lastModifiedAtMillis ?: 0L) == modifiedAt
        } finally { snapshot.close() }
        unchanged && delegate?.remove(key) == true
    }
    internal fun clearWhenIdle(allowed: () -> Boolean): Long = synchronized(lock) {
        if (closed || leases != 0 || !allowed()) return@synchronized 0L
        val before = delegate?.size ?: 0L
        delegate?.clear()
        (before - (delegate?.size ?: 0L)).coerceAtLeast(0L)
    }

    private fun acquireLease(key: String) { leases++; keyLeases[key] = (keyLeases[key] ?: 0) + 1 }
    private fun releaseLease(key: String) {
        leases--
        val remaining = (keyLeases[key] ?: 1) - 1
        if (remaining == 0) keyLeases.remove(key) else keyLeases[key] = remaining
        check(leases >= 0)
        scheduleResize()
        workFinished()
    }
    private inner class SnapshotLease(private val key: String, private val snapshot: DiskCache.Snapshot) : DiskCache.Snapshot {
        private var finished = false
        override val metadata: Path get() = snapshot.metadata
        override val data: Path get() = snapshot.data
        override fun close() = synchronized(lock) {
            if (!finished) {
                finished = true
                try { snapshot.close() } finally { releaseLease(key) }
            }
        }
        override fun closeAndOpenEditor(): DiskCache.Editor? = synchronized(lock) {
            if (finished) return@synchronized null
            finished = true
            var editor: DiskCache.Editor? = null
            try {
                if (!closed && target > 0L) editor = snapshot.closeAndOpenEditor()
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
                try { editor.commit() } finally { releaseLease(key) }
            }
        }
        override fun abort() = synchronized(lock) {
            if (!finished) {
                finished = true
                try { editor.abort() } finally { releaseLease(key) }
            }
        }
        override fun commitAndOpenSnapshot(): DiskCache.Snapshot? = synchronized(lock) {
            if (finished) return@synchronized null
            finished = true
            var snapshot: DiskCache.Snapshot? = null
            try {
                snapshot = editor.commitAndOpenSnapshot()
                snapshot?.let { SnapshotLease(key, it) }
            } finally { if (snapshot == null) releaseLease(key) }
        }
    }
}
