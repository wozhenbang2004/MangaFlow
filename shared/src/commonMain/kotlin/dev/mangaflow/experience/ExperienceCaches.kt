package dev.mangaflow.experience

import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import dev.mangaflow.platform.storage.StorageLock
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
    override val size: Long get() = cache.size
    override val maxSize: Long get() = synchronized(lock) { limit }
    override val keys: Set<MemoryCache.Key> get() = cache.keys
    override fun get(key: MemoryCache.Key): MemoryCache.Value? = cache[key]
    override fun set(key: MemoryCache.Key, value: MemoryCache.Value) = synchronized(lock) {
        cache[key] = value
        cache.trimToSize(limit)
    }
    override fun remove(key: MemoryCache.Key) = cache.remove(key)
    override fun trimToSize(size: Long) = synchronized(lock) { cache.trimToSize(minOf(size, limit)) }
    override fun clear() = cache.clear()
    fun applyBudget(fraction: Double, manualBytes: Long?, version: Long) = synchronized(lock) {
        if (version < revision) return@synchronized
        revision = version
        val protectedLimit = (total * fraction).toLong().coerceAtLeast(0L)
        limit = minOf(hardLimit, manualBytes ?: protectedLimit,
            if (fraction <= .10) protectedLimit else hardLimit)
        cache.trimToSize(limit)
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
) : DiskCache {
    private val lock = StorageLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val fileSystem: FileSystem = FileSystem.SYSTEM
    private var target = initialBytes.coerceAtLeast(0L)
    private var applied = target
    private var delegate: DiskCache? = build(target)
    private var leases = 0
    private var revision = -1L
    private var scheduled = false
    private var closed = false
    private var error: String? = null
    override val size: Long get() = synchronized(lock) { delegate?.size ?: 0L }
    override val maxSize: Long get() = synchronized(lock) { if (delegate == null) 0L else applied }
    val resizePending: Boolean get() = synchronized(lock) { target != applied || error != null }
    val resizeError: String? get() = synchronized(lock) { error }
    private fun build(bytes: Long) = DiskCache.Builder().directory(directory).fileSystem(fileSystem)
        .maxSizeBytes(bytes.coerceAtLeast(1L)).build()

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
                    // Never create a replacement until the old owner has really shut down.
                    delegate?.shutdown()
                    delegate = null
                    delegate = build(target)
                    // Opening size initializes the existing journal and schedules Coil's LRU trim.
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
        delegate?.openSnapshot(key)?.let { leases++; SnapshotLease(it) }
    }
    override fun openEditor(key: String): DiskCache.Editor? = synchronized(lock) {
        // Foreground original images must still reach disk while a long-lived reader
        // snapshot delays resizing. Optional prefetch is gated before network execution.
        if (closed || applied <= 0L) return@synchronized null
        delegate?.openEditor(key)?.let { leases++; EditorLease(it) }
    }
    override fun remove(key: String): Boolean = synchronized(lock) { delegate?.remove(key) ?: false }
    override fun clear() = synchronized(lock) { delegate?.clear(); Unit }
    override fun shutdown() = synchronized(lock) { closed = true; delegate?.shutdown(); delegate = null }

    private fun releaseLease() {
        leases--
        check(leases >= 0)
        scheduleResize()
    }
    private inner class SnapshotLease(private val snapshot: DiskCache.Snapshot) : DiskCache.Snapshot {
        private var finished = false
        override val metadata: Path get() = snapshot.metadata
        override val data: Path get() = snapshot.data
        override fun close() = synchronized(lock) {
            if (!finished) {
                finished = true
                try { snapshot.close() } finally { releaseLease() }
            }
        }
        override fun closeAndOpenEditor(): DiskCache.Editor? = synchronized(lock) {
            if (finished) return@synchronized null
            finished = true
            var editor: DiskCache.Editor? = null
            try {
                if (!closed && applied > 0L) editor = snapshot.closeAndOpenEditor()
                else snapshot.close()
                editor?.let(::EditorLease)
            } finally { if (editor == null) releaseLease() }
        }
    }
    private inner class EditorLease(private val editor: DiskCache.Editor) : DiskCache.Editor {
        private var finished = false
        override val metadata: Path get() = editor.metadata
        override val data: Path get() = editor.data
        override fun commit() = synchronized(lock) {
            if (!finished) {
                finished = true
                try { editor.commit() } finally { releaseLease() }
            }
        }
        override fun abort() = synchronized(lock) {
            if (!finished) {
                finished = true
                try { editor.abort() } finally { releaseLease() }
            }
        }
        override fun commitAndOpenSnapshot(): DiskCache.Snapshot? = synchronized(lock) {
            if (finished) return@synchronized null
            finished = true
            var snapshot: DiskCache.Snapshot? = null
            try {
                snapshot = editor.commitAndOpenSnapshot()
                snapshot?.let(::SnapshotLease)
            } finally { if (snapshot == null) releaseLease() }
        }
    }
}
