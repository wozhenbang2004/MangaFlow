@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.AnimeDiskCacheOwner
import dev.mangaflow.storage.AnimeDiskCacheUsage
import dev.mangaflow.storage.StorageCacheStore
import java.io.File
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A single SimpleCache owns all its files for the entire process. The existing
 * storage coordinator supplies its live budget and clears through this owner.
 */
internal class AndroidAnimeCache private constructor(context: Context) : AnimeDiskCacheOwner {
    private val revision = AtomicLong(0)
    private val sessions = AtomicInteger(0)
    private val accessed = AtomicLong(wallTimeMillis())
    private val store = StorageCacheStore.get(context)
    private val evictor = AdjustableAnimeEvictor {
        revision.incrementAndGet()
        accessed.set(wallTimeMillis())
        store.cacheWorkFinished()
    }
    val cache = SimpleCache(
        File(context.cacheDir, "anime-stream-v1"), evictor, StandaloneDatabaseProvider(context),
    )
    val targetBytes: Long get() = evictor.targetBytes

    init { store.attachAnimeCache(this) }

    /** Acquired on IO before any new session can read or write the cache. */
    fun acquire(): () -> Unit {
        synchronized(cache) {
            sessions.incrementAndGet()
            revision.incrementAndGet()
            accessed.set(wallTimeMillis())
        }
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) {
                sessions.decrementAndGet()
                revision.incrementAndGet()
                accessed.set(wallTimeMillis())
                store.cacheWorkFinished()
            }
        }
    }

    override fun snapshot(): AnimeDiskCacheUsage = synchronized(cache) {
        AnimeDiskCacheUsage(
            bytes = cache.cacheSpace,
            revision = revision.get(),
            active = sessions.get() > 0,
            accessedAt = accessed.get(),
        )
    }

    override fun resize(bytes: Long) {
        synchronized(cache) { evictor.resize(cache, bytes.coerceAtLeast(0L)) }
    }

    override fun clear(expectedRevision: Long): Long? = synchronized(cache) {
        if (sessions.get() > 0 || revision.get() != expectedRevision) return@synchronized null
        val before = cache.cacheSpace
        cache.keys.toList().forEach(cache::removeResource)
        val freed = (before - cache.cacheSpace).coerceAtLeast(0L)
        revision.incrementAndGet()
        store.cacheWorkFinished()
        freed
    }

    companion object {
        @Volatile private var instance: AndroidAnimeCache? = null
        @Synchronized fun get(context: Context): AndroidAnimeCache =
            instance ?: AndroidAnimeCache(context.applicationContext).also { instance = it }
    }
}

/** Callbacks and eviction are serialized by SimpleCache's own monitor. */
private class AdjustableAnimeEvictor(private val changed: () -> Unit) : CacheEvictor {
    // Reopening a cache must not delete a user's larger cache before the storage
    // controller restores its configured budget.
    @Volatile var targetBytes: Long = Long.MAX_VALUE
        private set
    private var bytes = 0L
    private val spans = TreeSet<CacheSpan> { left, right ->
        val age = left.lastTouchTimestamp.compareTo(right.lastTouchTimestamp)
        if (age != 0) age else left.compareTo(right)
    }

    override fun requiresCacheSpanTouches(): Boolean = true
    override fun onCacheInitialized() {}
    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        evict(cache, if (length == C.LENGTH_UNSET.toLong()) 0L else length.coerceAtLeast(0L))
    }
    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (spans.add(span)) bytes += span.length
        changed()
        evict(cache, 0L)
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) bytes = (bytes - span.length).coerceAtLeast(0L)
        changed()
    }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        spans.remove(oldSpan)
        spans.add(newSpan)
        changed()
    }
    fun resize(cache: Cache, requested: Long) {
        targetBytes = requested
        evict(cache, 0L)
    }
    private fun evict(cache: Cache, incoming: Long) {
        val available = (targetBytes - incoming).coerceAtLeast(0L)
        while (bytes > available && spans.isNotEmpty()) {
            cache.removeSpan(spans.first())
        }
    }
}
