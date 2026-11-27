@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.AnimeDiskCacheEntry
import dev.mangaflow.storage.AnimeDiskCacheOwner
import dev.mangaflow.storage.AnimeDiskCacheUsage
import dev.mangaflow.storage.AuxiliaryCacheRemovalException
import dev.mangaflow.storage.StorageCacheStore
import java.io.File
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/**
 * A single SimpleCache owns all its files for the entire process. The existing
 * storage coordinator supplies its live budget and clears through this owner.
 */
internal class AndroidAnimeCache private constructor(context: Context) : AnimeDiskCacheOwner {
    private val revision = AtomicLong(0)
    private val accessed = AtomicLong(wallTimeMillis())
    private val activeAssets = mutableMapOf<String, Int>()
    private val keyRevisions = mutableMapOf<String, Long>()
    private val metadata = mutableMapOf<String, AssetMetadata>()
    private val store = StorageCacheStore.get(context)
    private val evictor = AdjustableAnimeEvictor(canTrim = { key -> !protected(key) }) { key, sizeChanged ->
        keyRevisions[key] = revision.incrementAndGet()
        accessed.set(wallTimeMillis())
        // A replay touch updates LRU and the cleanup token without scheduling a size sweep.
        if (sizeChanged) store.cacheWorkFinished()
    }
    val cache = SimpleCache(
        File(context.cacheDir, "anime-stream-v1"), evictor, StandaloneDatabaseProvider(context),
    )
    val targetBytes: Long get() = evictor.targetBytes

    init { store.attachAnimeCache(this) }

    /** Acquired on IO before a player or startup writer can touch this asset's spans. */
    fun acquire(asset: AnimePlaybackAsset): () -> Unit {
        val identity = asset.playbackIdentity()
        synchronized(cache) {
            activeAssets[identity] = (activeAssets[identity] ?: 0) + 1
            revision.incrementAndGet()
            accessed.set(wallTimeMillis())
        }
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) {
                synchronized(cache) {
                    val remaining = (activeAssets[identity] ?: 1) - 1
                    if (remaining <= 0) activeAssets.remove(identity) else activeAssets[identity] = remaining
                    revision.incrementAndGet()
                    accessed.set(wallTimeMillis())
                }
                store.cacheWorkFinished()
            }
        }
    }

    /** Keep cached ranges readable when writes pause. Speculative readers may write short, exact ranges only. */
    fun playbackDataSource(asset: AnimePlaybackAsset, upstream: DataSource.Factory,
        speculative: Boolean = false): DataSource.Factory {
        val writer = animeCacheDataSource(asset, this, upstream)
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache)
                .setFragmentSize(REPLAY_FRAGMENT_BYTES))
        val reader = readOnlyDataSource(asset, upstream)
        return DataSource.Factory {
            ReplayingAnimeDataSource(writer.createDataSource(), reader.createDataSource()) { spec ->
                targetBytes > 0L && (!speculative || spec.length in 1L..REPLAY_FRAGMENT_BYTES)
            }
        }
    }

    /** Optional processing can consume originals without holding the player's future write range. */
    private fun readOnlyDataSource(asset: AnimePlaybackAsset, upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory { spec -> animeCacheKey(asset, spec.uri) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setCacheWriteDataSinkFactory(null)

    /** The key stays opaque. Native metadata stores object labels, never URLs or headers. */
    fun register(asset: AnimePlaybackAsset, key: String): String = synchronized(cache) {
        val value = AssetMetadata(asset.playbackIdentity(), asset.subjectId, asset.episodeId, asset.sourceId,
            asset.subjectTitle, asset.episodeTitle.ifBlank { asset.title })
        // The current asset is authoritative; disk classification is optional.
        if (metadata[key] != value) {
            metadata[key] = value
            keyRevisions[key] = revision.incrementAndGet()
            // Classification failure must not prevent raw playback. The in-process lease remains valid.
            runCatching {
                cache.applyContentMetadataMutations(key, ContentMetadataMutations()
                    .set(META_ASSET, value.asset)
                    .set(META_SUBJECT, value.subjectId)
                    .set(META_EPISODE, value.episodeId)
                    .set(META_SOURCE, value.sourceId)
                    .set(META_SUBJECT_TITLE, value.subjectTitle)
                    .set(META_EPISODE_TITLE, value.episodeTitle))
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
            }
        }
        key
    }

    override fun snapshot(): AnimeDiskCacheUsage = synchronized(cache) {
        AnimeDiskCacheUsage(
            bytes = cache.cacheSpace,
            revision = revision.get(),
            active = activeAssets.isNotEmpty(),
            accessedAt = accessed.get(),
            entries = cache.keys.mapNotNull(::entry),
        )
    }

    override fun resize(bytes: Long, canResize: () -> Boolean, commitResize: (() -> Unit) -> Boolean) {
        synchronized(cache) {
            // Active assets keep their spans; other episodes can already release shared space.
            if (!evictor.resize(cache, bytes.coerceAtLeast(0L), canResize, commitResize)) store.cacheWorkFinished()
        }
    }

    override fun clear(expectedRevision: Long, canClear: () -> Boolean): Long? = synchronized(cache) {
        if (activeAssets.isNotEmpty() || revision.get() != expectedRevision || !canClear()) return@synchronized null
        val before = cache.cacheSpace
        cache.keys.toList().forEach { key -> if (canClear()) cache.removeResource(key) }
        val freed = (before - cache.cacheSpace).coerceAtLeast(0L)
        revision.incrementAndGet()
        store.cacheWorkFinished()
        freed
    }

    override fun clearEntry(expected: AnimeDiskCacheEntry, canClear: () -> Boolean): Long? = synchronized(cache) {
        val current = entry(expected.key) ?: return@synchronized null
        if (current.protected || current != expected || !canClear()) return@synchronized null
        val before = cache.cacheSpace
        try { cache.removeResource(expected.key) }
        catch (_: Exception) { throw AuxiliaryCacheRemovalException((before - cache.cacheSpace).coerceAtLeast(0L)) }
        val freed = (before - cache.cacheSpace).coerceAtLeast(0L)
        store.cacheWorkFinished()
        freed
    }

    private fun entry(key: String): AnimeDiskCacheEntry? {
        val spans = cache.getCachedSpans(key)
        val bytes = spans.sumOf { it.length }
        if (bytes <= 0L) return null
        val owner = readMetadata(key)
        return AnimeDiskCacheEntry(key, bytes, keyRevisions[key] ?: 0L,
            spans.maxOfOrNull { it.lastTouchTimestamp } ?: 0L, protected(key),
            owner.subjectId, owner.episodeId, owner.sourceId, owner.subjectTitle, owner.episodeTitle)
    }

    /** Called only under SimpleCache's monitor, including its eviction callbacks. */
    private fun protected(key: String): Boolean {
        // The empty check also avoids accessing cache during its initialization callbacks.
        if (activeAssets.isEmpty()) return false
        val identity = try { readMetadata(key).asset }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { return true }
        return identity.isBlank() || (activeAssets[identity] ?: 0) > 0
    }

    private fun readMetadata(key: String): AssetMetadata = metadata.getOrPut(key) {
        val value = cache.getContentMetadata(key)
        AssetMetadata(value.get(META_ASSET, "").orEmpty(), value.get(META_SUBJECT, "").orEmpty(),
            value.get(META_EPISODE, "").orEmpty(), value.get(META_SOURCE, "").orEmpty(),
            value.get(META_SUBJECT_TITLE, "").orEmpty(), value.get(META_EPISODE_TITLE, "").orEmpty())
    }

    private data class AssetMetadata(val asset: String, val subjectId: String, val episodeId: String,
        val sourceId: String, val subjectTitle: String, val episodeTitle: String)

    companion object {
        private const val REPLAY_FRAGMENT_BYTES = 2L * 1024 * 1024
        private const val META_ASSET = "custom_mangaflow_asset"
        private const val META_SUBJECT = "custom_mangaflow_subject"
        private const val META_EPISODE = "custom_mangaflow_episode"
        private const val META_SOURCE = "custom_mangaflow_source"
        private const val META_SUBJECT_TITLE = "custom_mangaflow_subject_title"
        private const val META_EPISODE_TITLE = "custom_mangaflow_episode_title"
        @Volatile private var instance: AndroidAnimeCache? = null
        @Synchronized fun get(context: Context): AndroidAnimeCache =
            instance ?: AndroidAnimeCache(context.applicationContext).also { instance = it }
    }
}

/** One Media3 source may be reopened for many seeks. Choose admission at each open. */
private class ReplayingAnimeDataSource(
    private val writer: DataSource,
    private val reader: DataSource,
    private val canWrite: (DataSpec) -> Boolean,
) : DataSource {
    private var active: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        check(active == null)
        val source = if (canWrite(dataSpec)) writer else reader
        active = source // Media3 also calls close() after a failed open.
        // HLS chunks do not opt into early span publication by default. Commit large
        // chunks incrementally so a second reader can reuse the downloaded prefix.
        val spec = if (source === writer && !dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION))
            dataSpec.buildUpon().setFlags(dataSpec.flags or DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
        else dataSpec
        return source.open(spec)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        writer.addTransferListener(transferListener)
        reader.addTransferListener(transferListener)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()

    override fun close() {
        val source = active
        active = null
        source?.close()
    }
}

/** Callbacks and eviction are serialized by SimpleCache's own monitor. */
private class AdjustableAnimeEvictor(private val canTrim: (String) -> Boolean,
    private val changed: (key: String, sizeChanged: Boolean) -> Unit) : CacheEvictor {
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
        changed(span.key, true)
        evict(cache, 0L)
    }
    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) bytes = (bytes - span.length).coerceAtLeast(0L)
        changed(span.key, true)
    }
    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        spans.remove(oldSpan)
        spans.add(newSpan)
        changed(newSpan.key, false)
    }
    fun resize(cache: Cache, requested: Long, canResize: () -> Boolean, commitResize: (() -> Unit) -> Boolean): Boolean {
        // Do not publish a provisional quota: a stale sweep must not leave its old
        // limit installed for subsequent native writes after its eviction was stopped.
        if (!evict(cache, 0L, requested, canResize) || !canResize()) return false
        // A soft target still applies while protected spans exceed it. Their release triggers a sweep.
        return commitResize { targetBytes = requested }
    }
    private fun evict(cache: Cache, incoming: Long, limit: Long = targetBytes,
        canEvict: () -> Boolean = { true }): Boolean {
        val available = (limit - incoming).coerceAtLeast(0L)
        while (bytes > available && spans.isNotEmpty()) {
            if (!canEvict()) return false
            val removable = spans.firstOrNull { canTrim(it.key) } ?: break
            cache.removeSpan(removable)
        }
        return canEvict()
    }
}
