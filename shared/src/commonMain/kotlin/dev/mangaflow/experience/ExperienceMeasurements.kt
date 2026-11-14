package dev.mangaflow.experience

import kotlinx.serialization.json.*
import kotlin.math.ceil
import kotlin.time.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

internal fun encodeExperienceOverrides(o: ExperienceOverrides) = buildJsonObject {
    put("prefetch", o.prefetchMode.name)
    o.ahead?.let { put("ahead", it) }; o.behind?.let { put("behind", it) }
    o.prefetchRequests?.let { put("requests", it) }; o.prefetchMiB?.let { put("bytesMiB", it) }
    put("cross", o.crossChapter); put("metered", o.allowMeteredPrefetch)
    o.readerRequests?.let { put("reader", it) }; o.downloadPages?.let { put("pages", it) }
    o.sourcePages?.let { put("source", it) }; o.chapters?.let { put("chapters", it) }
    put("yield", o.yieldToReading)
    o.memoryMiB?.let { put("memoryMiB", it) }; o.diskMiB?.let { put("diskMiB", it) }
    o.refresh?.let { put("refresh", it.name) }; o.refreshHz?.let { put("hz", it) }
    put("battery", o.batteryProtection); put("background", o.allowBackgroundDownloads)
}.toString()

internal fun decodeExperienceOverrides(value: String?): ExperienceOverrides = runCatching {
    val json = Json.parseToJsonElement(value ?: return ExperienceOverrides()).jsonObject
    fun int(key: String, min: Int, max: Int) = json[key]?.jsonPrimitive?.intOrNull?.coerceIn(min, max)
    fun bool(key: String, default: Boolean) = json[key]?.jsonPrimitive?.booleanOrNull ?: default
    ExperienceOverrides(
        prefetchMode = PrefetchMode.entries.firstOrNull { it.name == json["prefetch"]?.jsonPrimitive?.content } ?: PrefetchMode.SMART,
        ahead = int("ahead", 0, 12), behind = int("behind", 0, 4), prefetchRequests = int("requests", 1, 3),
        prefetchMiB = int("bytesMiB", 4, 96), crossChapter = bool("cross", true), allowMeteredPrefetch = bool("metered", false),
        readerRequests = int("reader", 1, 4), downloadPages = int("pages", 1, maximumBackgroundPageParallelism),
        sourcePages = int("source", 1, 4), chapters = int("chapters", 1, 4), yieldToReading = bool("yield", true),
        memoryMiB = int("memoryMiB", 16, 512), diskMiB = int("diskMiB", 32, 8192),
        refresh = RefreshPreference.entries.firstOrNull { it.name == json["refresh"]?.jsonPrimitive?.content },
        refreshHz = int("hz", 30, 360), batteryProtection = bool("battery", true), allowBackgroundDownloads = bool("background", true),
    )
}.getOrDefault(ExperienceOverrides())

/** Honor both delay-seconds and IMF-fixdate without storing URLs or credentials. */
internal fun experienceRetryDelay(header: String?): Long {
    val seconds = header?.trim()?.toLongOrNull()
    if (seconds != null) return seconds.coerceIn(1, 86_400) * 1000L
    val date = runCatching {
        val match = Regex("^[A-Za-z]{3}, ([0-9]{2}) ([A-Za-z]{3}) ([0-9]{4}) ([0-9]{2}):([0-9]{2}):([0-9]{2}) GMT$")
            .matchEntire(header?.trim().orEmpty()) ?: return@runCatching null
        val g = match.groupValues
        val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec").indexOf(g[2]) + 1
        LocalDateTime(g[3].toInt(), month, g[1].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            .toInstant(TimeZone.UTC).toEpochMilliseconds()
    }.getOrNull()
    return date?.let { (it - Clock.System.now().toEpochMilliseconds()).coerceIn(1000L, 86_400_000L) } ?: 30_000L
}

/** Bounded in-memory observations, not an analytics log or synthetic frame counter. */
internal class ExperienceMeasurements {
    private val requests = ArrayDeque<Long>()
    private val frames = ArrayDeque<Long>()
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
        frameP95Millis = frame95, frameP99Millis = frame99,
    )
    private fun percentile(values: Collection<Long>, fraction: Double): Long? =
        if (values.isEmpty()) null else values.sorted()[(ceil(values.size * fraction).toInt() - 1).coerceIn(0, values.size - 1)]
}
