package dev.mangaflow.data

import dev.mangaflow.platform.*

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.applicationContext
import dev.mangaflow.platform.storage.cacheDir
import dev.mangaflow.platform.storage.StorageAtomicFile as AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.storage.StorageFile as File
import okio.IOException
import dev.mangaflow.platform.AtomicBoolean

/** Small, anonymous metadata shared by discovery, search, library and detail. */
class MangaMetricsRepository(
    context: Context,
    private val reviews: ReviewRepository,
    private val community: CopyCommunityRepository,
) {
    private val directory = File(context.applicationContext.cacheDir, "metadata-cache")
    private val file = AtomicFile(File(directory, "metrics.json"))
    private val lock = SynchronizedObject()
    private val disk = Mutex()
    private val requests = mutableMapOf<String, InFlightRequest>()
    private val entries = LinkedHashMap<String, CachedManga>()
    private var loaded = false
    @kotlin.concurrent.Volatile private var generation = 0L

    /** No disk or network work on composition. An expired success can still be shown. */
    fun cached(mangaId: String): List<MangaMetric> = synchronized(lock) {
        entries[mangaId]?.metrics().orEmpty()
    }

    /** Publish stored values before refreshing, then publish each independent source. */
    suspend fun lookup(
        manga: Manga,
        forceRefresh: Boolean = false,
        onUpdate: (List<MangaMetric>) -> Unit = {},
    ): List<MangaMetric> =
        withContext(Dispatchers.IO) {
            val epoch = generation
            val dirty = AtomicBoolean(false)
            val updates = SynchronizedObject()
            loadDisk(epoch)
            // Count holders and waiters so a cancelled waiter cannot replace a live lock.
            val request = synchronized(lock) {
                requests.getOrPut(manga.id) { InFlightRequest() }.also { it.references++ }
            }
            try {
                request.mutex.withLock {
                    if (epoch != generation) return@withLock emptyList()
                    val identity = identityKey(manga)
                    prepare(manga.id, identity)
                    // Overall heat is already in public list/detail metadata. A badge must not
                    // fetch a chapter directory or copy a day/week rank value into overall heat.
                    val copy = community.metric(manga)
                    if (copy.popularity != null && remember(manga, identity, epoch, copy, preserveUnchangedTime = true)) {
                        dirty.set(true)
                    }
                    if (epoch == generation && identity == identityKey(manga)) onUpdate(current(manga.id, identity))

                    val now = wallTimeMillis()
                    val due = synchronized(lock) {
                        ReviewProviders.metricIds.filter { source ->
                            val next = entries[manga.id]?.sources?.get(source)?.nextFetchAt ?: 0
                            forceRefresh || next <= now || next > now + SCORE_TTL
                        }.toSet()
                    }
                    if (due.isNotEmpty()) {
                        currentCoroutineContext().ensureActive()
                        if (epoch == generation && identity == identityKey(manga)) {
                            reviews.lookupMetrics(manga, due) { source ->
                                // Independent providers may finish together. Commit and publish
                                // one complete snapshot at a time so an older callback cannot win.
                                synchronized(updates) {
                                    if (remember(manga, identity, epoch, source)) {
                                        dirty.set(true)
                                        if (epoch == generation && identity == identityKey(manga)) {
                                            onUpdate(current(manga.id, identity))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (epoch == generation && identity == identityKey(manga)) current(manga.id, identity) else emptyList()
                }
            } finally {
                synchronized(lock) {
                    request.references--
                    if (request.references == 0 && requests[manga.id] === request) requests.remove(manga.id)
                }
                // Leaving a card cancels its network work, but keeps already received values.
                // This bounded file write runs after releasing the request lock; no new fetch starts.
                if (dirty.get() && epoch == generation) withContext(NonCancellable + Dispatchers.IO) {
                    persist(epoch)
                }
            }
        }

    /** A confirmed detail association also updates all already visible card badges. */
    suspend fun recordSources(manga: Manga, sources: List<ReviewSource>): Unit = withContext(Dispatchers.IO) {
        val epoch = generation
        loadDisk(epoch)
        if (epoch != generation) return@withContext
        val identity = identityKey(manga)
        prepare(manga.id, identity)
        sources.forEach { remember(manga, identity, epoch, it) }
        currentCoroutineContext().ensureActive()
        persist(epoch)
    }

    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        disk.withLock { directory.listFiles()?.filter(File::isFile)?.sumOf(File::length) ?: 0L }
    }

    /** Association preferences belong to the user, and are deliberately not cache data. */
    suspend fun clearCache(): Unit = withContext(Dispatchers.IO) {
        disk.withLock {
            synchronized(lock) {
                generation++
                entries.clear()
                loaded = true
            }
            reviews.clearCache()
            community.clearCache()
            file.delete()
            if (directory.listFiles()?.any { it.isFile } == true) {
                throw IOException("评分缓存未能完全清理，请重试。")
            }
        }
    }

    private fun prepare(mangaId: String, identity: String) = synchronized(lock) {
        val now = wallTimeMillis()
        val existing = entries.remove(mangaId)?.takeIf { it.identity == identity }
        entries[mangaId] = existing?.copy(accessedAt = now) ?: CachedManga(identity, now)
        while (entries.size > MAX_ITEMS) entries.remove(entries.keys.first())
    }

    private fun current(mangaId: String, identity: String): List<MangaMetric> = synchronized(lock) {
        entries[mangaId]?.takeIf { it.identity == identity }?.metrics().orEmpty()
    }

    private fun remember(
        manga: Manga, identity: String, epoch: Long, source: ReviewSource, preserveUnchangedTime: Boolean = false,
    ): Boolean {
        if (source.id !in SOURCE_IDS || epoch != generation || identity != identityKey(manga)) return false
        return synchronized(lock) {
            if (epoch != generation) return@synchronized false
            val entry = entries[manga.id]?.takeIf { it.identity == identity } ?: return@synchronized false
            val previous = entry.sources[source.id]
            val now = wallTimeMillis()
            val metric = when (source.status) {
                ReviewStatus.UNAVAILABLE -> previous?.metric
                ReviewStatus.NO_MATCH -> null
                ReviewStatus.READY -> MangaMetric(source.id, source.name,
                    source.score?.takeIf { it.isFinite() && it >= 0 &&
                        (source.scoreScale == 0 || it <= source.scoreScale.toDouble()) },
                    source.scoreScale, source.ratingCount?.takeIf { it >= 0 }, source.popularity?.takeIf { it >= 0 },
                    source.scoreLabel)
                    .takeIf { it.score != null || it.popularity != null }
            }
            if (preserveUnchangedTime && previous != null && previous.metric == metric) return@synchronized false
            val oldTime = previous?.checkedAt ?: now
            val checkedAt = if (source.status == ReviewStatus.UNAVAILABLE) oldTime else now
            val ttl = when {
                source.status == ReviewStatus.UNAVAILABLE -> RETRY_TTL
                source.status == ReviewStatus.NO_MATCH -> NO_MATCH_TTL
                source.id == "copy" -> HEAT_TTL
                else -> SCORE_TTL
            }
            entries[manga.id] = entry.copy(sources = entry.sources + (source.id to CachedMetric(metric, checkedAt, now + ttl)))
            true
        }
    }

    private suspend fun loadDisk(epoch: Long) {
        disk.withLock {
            if (epoch != generation || synchronized(lock) { loaded }) return@withLock
            val restored = LinkedHashMap<String, CachedManga>()
            try {
                val bytes = file.openRead().use { input ->
                    if (file.baseFile.length() > MAX_BYTES) throw IOException("Metadata cache is too large")
                    input.readBytes()
                }
                val root = JSONObject(bytes.decodeToString())
                if (root.optInt("version") in 1..2) {
                    val rows = root.optJSONArray("items") ?: JSONArray()
                    val now = wallTimeMillis()
                    for (index in 0 until minOf(rows.length(), MAX_ITEMS)) {
                        val row = rows.optJSONObject(index) ?: continue
                        val id = row.optString("id")
                        val identity = row.optString("identity")
                        val accessed = row.optLong("accessedAt")
                        if (!id.matches(Regex("[A-Za-z0-9_-]{1,160}")) || !identity.matches(Regex("[a-f0-9]{64}")) ||
                            accessed > now + RETRY_TTL || now - accessed > MAX_AGE) continue
                        val sourceRows = row.optJSONArray("sources") ?: JSONArray()
                        val sources = mutableMapOf<String, CachedMetric>()
                        for (position in 0 until minOf(sourceRows.length(), SOURCE_IDS.size)) {
                            val value = sourceRows.optJSONObject(position) ?: continue
                            val source = value.optString("source")
                            val checked = value.optLong("checkedAt")
                            val next = value.optLong("nextFetchAt")
                            if (source !in SOURCE_IDS || checked <= 0 || checked > now + RETRY_TTL || now - checked > MAX_AGE) continue
                            val scale = ReviewProviders.find(source)?.scoreScale ?: 10
                            val score = value.number("score")?.takeIf { it.isFinite() && it >= 0 &&
                                (scale == 0 || it <= scale.toDouble()) }
                            val heat = value.nonNegativeLong("popularity")?.takeIf { source == "copy" }
                            val count = value.nonNegativeLong("ratingCount")?.takeIf { it <= Int.MAX_VALUE }?.toInt()
                            val label = value.optString("scoreLabel").take(40)
                            val metric = if (score != null || heat != null) MangaMetric(source, sourceName(source), score, scale, count, heat, label) else null
                            val validNext = next.takeIf { it in checked..(now + SCORE_TTL) } ?: 0
                            // Older v1/v2 caches gave empty matches the six-hour score TTL.
                            // Shorten only empty entries, preserving any shorter failure retry.
                            val nextFetch = if (metric == null) minOf(validNext, checked + NO_MATCH_TTL) else validNext
                            sources[source] = CachedMetric(metric, checked, nextFetch)
                        }
                        restored[id] = CachedManga(identity, accessed, sources)
                    }
                }
            } catch (_: Exception) {
                // A missing, old or damaged cache does not block real source data.
            }
            synchronized(lock) {
                if (epoch == generation) {
                    restored.forEach { (id, entry) -> entries.putIfAbsent(id, entry) }
                    loaded = true
                }
            }
        }
    }

    private suspend fun persist(epoch: Long) {
        disk.withLock {
            currentCoroutineContext().ensureActive()
            if (epoch != generation) return@withLock
            val rows = synchronized(lock) {
                JSONArray().apply {
                    entries.forEach { (id, entry) -> put(JSONObject().put("id", id).put("identity", entry.identity)
                        .put("accessedAt", entry.accessedAt).put("sources", JSONArray().apply {
                            entry.sources.forEach { (source, value) -> put(JSONObject().put("source", source)
                                .put("checkedAt", value.checkedAt).put("nextFetchAt", value.nextFetchAt)
                                .put("score", value.metric?.score).put("ratingCount", value.metric?.ratingCount)
                                .put("popularity", value.metric?.popularity).put("scoreLabel", value.metric?.scoreLabel)) }
                        })) }
                }
            }
            val bytes = JSONObject().put("version", 2).put("items", rows).toString().encodeToByteArray()
            if (bytes.size > MAX_BYTES || epoch != generation) return@withLock
            var output: dev.mangaflow.platform.storage.StorageOutput? = null
            try {
                if (!directory.exists() && !directory.mkdirs()) return@withLock
                output = file.startWrite()
                output.write(bytes)
                file.finishWrite(output)
            } catch (cancel: CancellationException) {
                output?.let(file::failWrite)
                throw cancel
            } catch (_: Exception) {
                output?.let(file::failWrite)
            }
        }
    }

    private fun identityKey(manga: Manga) = sha256(reviews.metricIdentityKey(manga).encodeToByteArray()).hexString()

    private fun JSONObject.number(key: String): Double? = (opt(key) as? Number)?.toDouble()
    private fun JSONObject.nonNegativeLong(key: String): Long? = (opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }

    private class InFlightRequest {
        val mutex = Mutex()
        var references = 0
    }

    private data class CachedManga(val identity: String, val accessedAt: Long, val sources: Map<String, CachedMetric> = emptyMap()) {
        fun metrics() = SOURCE_IDS.mapNotNull { sources[it]?.metric }
    }
    private data class CachedMetric(val metric: MangaMetric?, val checkedAt: Long, val nextFetchAt: Long)

    private companion object {
        const val MAX_ITEMS = 256
        const val MAX_BYTES = 512 * 1024
        const val HEAT_TTL = 30 * 60_000L
        const val SCORE_TTL = 6 * 60 * 60_000L
        const val RETRY_TTL = 5 * 60_000L
        const val NO_MATCH_TTL = 15 * 60_000L
        const val MAX_AGE = 7 * 24 * 60 * 60_000L
        val SOURCE_IDS = listOf("copy") + ReviewProviders.metricIds
        fun sourceName(id: String) = if (id == "copy") "热度" else ReviewProviders.find(id)?.name ?: id
    }
}
