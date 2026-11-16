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
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/** Coil 3.2 has a read-only maximum. Enforce a live maximum around its existing LRU. */
internal class ExperienceMemoryCache(private val cache: MemoryCache) : MemoryCache {
    private val lock = StorageLock()
    private val total = (cache.maxSize / .32).toLong()
    private val hardLimit = minOf(cache.maxSize, 512L * EXPERIENCE_MIB)
    private var limit = minOf(hardLimit, (total * .18).toLong())
    private var revision = -1L
    private var idleMillis = 0L
    private val accessed = mutableMapOf<MemoryCache.Key, Long>()
    override val size: Long get() = cache.size
    override val maxSize: Long get() = synchronized(lock) { limit }
    override val keys: Set<MemoryCache.Key> get() = cache.keys
    override fun get(key: MemoryCache.Key): MemoryCache.Value? = synchronized(lock) {
        val now = monotonicTimeMillis()
        val last = accessed[key]
        if (idleMillis > 0L && last != null && now - last >= idleMillis) {
            accessed.remove(key); cache.remove(key); null
        } else cache[key]?.also { accessed[key] = now }
    }
    override fun set(key: MemoryCache.Key, value: MemoryCache.Value) = synchronized(lock) {
        cache[key] = value
        accessed[key] = monotonicTimeMillis()
        cache.trimToSize(limit)
        accessed.keys.retainAll(cache.keys)
        Unit
    }
    override fun remove(key: MemoryCache.Key) = synchronized(lock) { accessed.remove(key); cache.remove(key) }
    override fun trimToSize(size: Long) = synchronized(lock) { cache.trimToSize(minOf(size, limit)) }
    override fun clear() = synchronized(lock) { accessed.clear(); cache.clear() }
    fun trimExpired(): Unit = synchronized(lock) {
        if (idleMillis > 0L) {
            val now = monotonicTimeMillis()
            accessed.filterValues { now - it >= idleMillis }.keys.toList().forEach { cache.remove(it); accessed.remove(it) }
        }
        accessed.keys.retainAll(cache.keys)
        Unit
    }
    fun applyBudget(fraction: Double, manualBytes: Long?, version: Long, idleMinutes: Int = 0) = synchronized(lock) {
        if (version < revision) return@synchronized
        revision = version
        idleMillis = idleMinutes.coerceIn(0, 120) * 60_000L
        val protectedLimit = (total * fraction).toLong().coerceAtLeast(0L)
        limit = minOf(hardLimit, manualBytes ?: protectedLimit,
            if (fraction <= .10) protectedLimit else hardLimit)
        cache.trimToSize(limit)
        trimExpired()
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
