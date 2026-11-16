package dev.mangaflow.experience

import kotlinx.serialization.json.*
import kotlin.math.ceil
import dev.mangaflow.net.retryAfterMillis

internal fun encodeExperienceOverrides(o: ExperienceOverrides) = buildJsonObject {
    put("prefetch", o.prefetchMode.name)
    o.ahead?.let { put("ahead", it) }; o.behind?.let { put("behind", it) }
    o.prefetchRequests?.let { put("requests", it) }; o.prefetchMiB?.let { put("bytesMiB", it) }
    put("cross", o.crossChapter); put("metered", o.allowMeteredPrefetch)
    o.readerRequests?.let { put("reader", it) }; o.downloadPages?.let { put("pages", it) }
    o.resolveRequests?.let { put("resolve", it) }; o.decodeRequests?.let { put("decode", it) }
    o.sourcePages?.let { put("source", it) }; o.chapters?.let { put("chapters", it) }
    put("yield", o.yieldToReading)
    o.memoryMiB?.let { put("memoryMiB", it) }; o.diskMiB?.let { put("diskMiB", it) }
    o.refresh?.let { put("refresh", it.name) }; o.refreshHz?.let { put("hz", it) }
    put("battery", o.batteryProtection); put("background", o.allowBackgroundDownloads)
    o.decodedPrefetchPages?.let { put("decodedPages", it) }
    o.sourceRecovery?.let { put("sourceRecovery", it.name) }
    put("browseWarmup", o.browseWarmup)
    o.standbySources?.let { put("standbySources", it) }
    put("standbyPages", o.standbyPages); put("standbyMemory", o.standbyMemoryWarmup)
    o.standbyMiB?.let { put("standbyMiB", it) }
    put("readerStatus", o.readerStatusPlacement.name)
    put("contentCache", buildJsonObject {
        val c = o.contentCache
        put("storage", c.storage.name); put("open", c.refreshOnOpen)
        put("search", c.searchMinutes); put("discovery", c.discoveryMinutes); put("ranking", c.rankingMinutes)
        put("detail", c.detailMinutes); put("manifest", c.manifestMinutes); put("score", c.scoreMinutes)
        put("popularity", c.popularityMinutes); put("comments", c.commentMinutes); put("failure", c.failureMinutes)
        put("retention", c.retentionHours); put("memoryEntries", c.snapshotMemoryEntries); put("diskMiB", c.snapshotDiskMiB)
        put("memoryIdle", c.memoryIdleMinutes); put("imageAge", c.imageRetentionHours)
    })
}.toString()

internal fun decodeExperienceOverrides(value: String?): ExperienceOverrides = runCatching {
    val json = Json.parseToJsonElement(value ?: return ExperienceOverrides()).jsonObject
    fun int(key: String, min: Int, max: Int) = json[key]?.jsonPrimitive?.intOrNull?.coerceIn(min, max)
    fun bool(key: String, default: Boolean) = json[key]?.jsonPrimitive?.booleanOrNull ?: default
    ExperienceOverrides(
        prefetchMode = PrefetchMode.entries.firstOrNull { it.name == json["prefetch"]?.jsonPrimitive?.content } ?: PrefetchMode.SMART,
        ahead = int("ahead", 0, 40), behind = int("behind", 0, 8), prefetchRequests = int("requests", 1, 12),
        prefetchMiB = int("bytesMiB", 4, 512), crossChapter = bool("cross", true), allowMeteredPrefetch = bool("metered", false),
        readerRequests = int("reader", 1, 4), resolveRequests = int("resolve", 1, 8), decodeRequests = int("decode", 1, 4),
        downloadPages = int("pages", 1, maximumBackgroundPageParallelism),
        sourcePages = int("source", 1, 8), chapters = int("chapters", 1, 4), yieldToReading = bool("yield", true),
        memoryMiB = int("memoryMiB", 16, 512), diskMiB = int("diskMiB", 32, 8192),
        refresh = RefreshPreference.entries.firstOrNull { it.name == json["refresh"]?.jsonPrimitive?.content },
        refreshHz = int("hz", 30, 360), batteryProtection = bool("battery", true), allowBackgroundDownloads = bool("background", true),
        decodedPrefetchPages = int("decodedPages", 0, 8),
        sourceRecovery = SourceRecoveryMode.entries.firstOrNull { it.name == json["sourceRecovery"]?.jsonPrimitive?.content },
        browseWarmup = bool("browseWarmup", true),
        standbySources = int("standbySources", 1, 4), standbyPages = int("standbyPages", 1, 4) ?: 1,
        standbyMiB = int("standbyMiB", 1, 128),
        standbyMemoryWarmup = bool("standbyMemory", true),
        readerStatusPlacement = ReaderStatusPlacement.entries.firstOrNull { it.name == json["readerStatus"]?.jsonPrimitive?.content }
            ?: ReaderStatusPlacement.BOTTOM,
        contentCache = json["contentCache"]?.jsonObject?.let { c ->
            val defaults = ContentCachePreferences()
            fun number(key: String, default: Int) = c[key]?.jsonPrimitive?.intOrNull ?: default
            ContentCachePreferences(
                storage = ContentCacheStorage.entries.firstOrNull { it.name == c["storage"]?.jsonPrimitive?.content } ?: defaults.storage,
                refreshOnOpen = c["open"]?.jsonPrimitive?.booleanOrNull ?: defaults.refreshOnOpen,
                searchMinutes = number("search", defaults.searchMinutes), discoveryMinutes = number("discovery", defaults.discoveryMinutes),
                rankingMinutes = number("ranking", defaults.rankingMinutes), detailMinutes = number("detail", defaults.detailMinutes),
                manifestMinutes = number("manifest", defaults.manifestMinutes), scoreMinutes = number("score", defaults.scoreMinutes),
                popularityMinutes = number("popularity", defaults.popularityMinutes), commentMinutes = number("comments", defaults.commentMinutes),
                failureMinutes = number("failure", defaults.failureMinutes), retentionHours = number("retention", defaults.retentionHours),
                snapshotMemoryEntries = number("memoryEntries", defaults.snapshotMemoryEntries), snapshotDiskMiB = number("diskMiB", defaults.snapshotDiskMiB),
                memoryIdleMinutes = number("memoryIdle", defaults.memoryIdleMinutes), imageRetentionHours = number("imageAge", defaults.imageRetentionHours),
            ).bounded()
        } ?: ContentCachePreferences(),
    )
}.getOrDefault(ExperienceOverrides())

/** Honor both delay-seconds and IMF-fixdate without storing URLs or credentials. */
internal fun experienceRetryDelay(header: String?): Long = (retryAfterMillis(header) ?: 30_000L).coerceAtLeast(1_000L)

/** Bounded in-memory observations, not an analytics log or synthetic frame counter. */
internal class ExperienceMeasurements {
    private val requests = ArrayDeque<Long>()
    private val frames = ArrayDeque<Long>()
    private val decodeDurations = ArrayDeque<Long>()
    private val decodeQueues = ArrayDeque<Long>()
    private val decodedSizes = ArrayDeque<Long>()
    private var decodedBytes = 0L
    private var optionalDecodes = 0L
    private val sizes = linkedMapOf<String, Long>()
    private val prefetched = linkedSetOf<String>()
    private var imageRequests = 0L
    private var memoryHits = 0L
    private var diskHits = 0L
    private var prefetchedPages = 0L
    private var prefetchHits = 0L
    private var transferBytes = 0L
    private var transferMillis = 0L
    private var frameSequence = 0
    private var request95: Long? = null
    private var frame95: Double? = null
    private var frame99: Double? = null

    fun recordImage(elapsed: Long, memory: Boolean, disk: Boolean, prefetch: Boolean, key: String, bytes: Long?) {
        imageRequests++
        if (memory) memoryHits++
        if (disk) diskHits++
        requests.addLast(elapsed.coerceAtLeast(0L))
        if (requests.size > 180) requests.removeFirst()
        request95 = percentile(requests, .95)
        if (bytes != null && bytes > 0L) {
            sizes.remove(key); sizes[key] = bytes
            if (sizes.size > 256) sizes.remove(sizes.keys.first())
        }
        if (prefetch) {
            if (!memory && !disk) prefetchedPages++
            prefetched += key
            if (prefetched.size > 256) prefetched.remove(prefetched.first())
        } else if (prefetched.remove(key) && (memory || disk)) prefetchHits++
    }
    fun estimate(key: String) = sizes[key] ?: if (sizes.isEmpty()) 3L * EXPERIENCE_MIB
        else (sizes.values.sum() / sizes.size).coerceIn(EXPERIENCE_MIB, 24L * EXPERIENCE_MIB)

    fun recordDecodeQueue(elapsedMillis: Long) {
        decodeQueues.addLast(elapsedMillis.coerceAtLeast(0L))
        if (decodeQueues.size > 180) decodeQueues.removeFirst()
    }
    fun recordDecode(elapsedMillis: Long, bytes: Long, optional: Boolean) {
        decodeDurations.addLast(elapsedMillis.coerceAtLeast(0L))
        if (decodeDurations.size > 180) decodeDurations.removeFirst()
        if (bytes > 0L) {
            decodedBytes += bytes
            decodedSizes.addLast(bytes)
            if (decodedSizes.size > 32) decodedSizes.removeFirst()
        }
        if (optional) optionalDecodes++
    }
    /** A conservative recent output estimate; not a claimed page allocation or cache hit. */
    fun estimatedDecodedPageBytes(): Long =
        maxOf(4L * EXPERIENCE_MIB, percentile(decodedSizes, .9) ?: 4L * EXPERIENCE_MIB)

    fun recordTransfer(bytes: Long, elapsed: Long) {
        transferBytes += bytes.coerceAtLeast(0L)
        transferMillis += elapsed.coerceAtLeast(0L)
    }
    fun recordFrame(nanos: Long): Boolean {
        if (nanos <= 0L) return false
        frames.addLast(nanos)
        if (frames.size > 240) frames.removeFirst()
        if (++frameSequence % 60 != 0) return false
        frame95 = percentile(frames, .95)?.div(1_000_000.0)
        frame99 = percentile(frames, .99)?.div(1_000_000.0)
        return true
    }
    fun usage(previous: ExperienceUsage) = previous.copy(
        imageRequests = imageRequests, memoryHits = memoryHits, diskHits = diskHits,
        prefetchedPages = prefetchedPages, prefetchHits = prefetchHits,
        requestP95Millis = request95, transferredBytes = transferBytes, transferMillis = transferMillis,
        decodeP95Millis = percentile(decodeDurations, .95), decodeQueueP95Millis = percentile(decodeQueues, .95),
        decodedBytes = decodedBytes, optionalDecodes = optionalDecodes,
        frameP95Millis = frame95, frameP99Millis = frame99,
    )
    private fun percentile(values: Collection<Long>, fraction: Double): Long? =
        if (values.isEmpty()) null else values.sorted()[(ceil(values.size * fraction).toInt() - 1).coerceIn(0, values.size - 1)]
}
