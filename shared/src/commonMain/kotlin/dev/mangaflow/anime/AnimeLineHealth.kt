package dev.mangaflow.anime

import dev.mangaflow.anime.downloads.animeDigest
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.identity
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.filesDir
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HEALTH_MAX_AGE_MS = 72 * 60 * 60_000L
private const val HEALTH_RECENT_AGE_MS = 6 * 60 * 60_000L
private const val HEALTH_MAX_KEYS = 128
private const val HEALTH_WINDOW_SIZE = 14
private const val HEALTH_MAX_SAMPLE_MS = 180_000L
private const val HEALTH_MAX_FILE_BYTES = 512 * 1024L

internal data class AnimeLineHealthKey(val digest: String)

/** One real viewing. Its identity and writes are only used in memory. */
internal class AnimeLineViewing {
    private val outcomes = mutableMapOf<AnimeLineHealthKey, Boolean>()

    // The owning health store serializes updates for this viewing.
    internal fun update(key: AnimeLineHealthKey, stalled: Boolean): Boolean {
        val previous = outcomes[key]
        if (previous == true || previous == stalled) return false
        outcomes[key] = stalled
        return true
    }
}

/** An opaque hint for ordering confirmed candidates. An empty group aggregates a whole source. */
internal fun animeLineHealthKey(sourceId: String, group: String, sourceRevision: String,
    route: DownloadRouteSnapshot, preferredHeight: Int, network: String): AnimeLineHealthKey {
    val identity = listOf(sourceId, group, sourceRevision, route.identity(), preferredHeight.toString(), network)
        .joinToString("") { value -> "${value.length}:$value" }
    return AnimeLineHealthKey(animeDigest(identity))
}

internal data class AnimeLineHealth(
    val startupSucceeded: Int,
    val startupFailed: Int,
    val firstFrameP50Ms: Long?,
    val addressP50Ms: Long?,
    val earlyPlaybackSamples: Int,
    val earlyStallCount: Int,
    val earlyRebufferP50Ms: Long?,
    val decodedHeightP50: Int?,
    val earlyFailures: Int,
    val sustainedPlaybackSamples: Int = 0,
    val sustainedPlaybackStalls: Int = 0,
    val firstFrameSamples: Int = 0,
    val nativeAdvanceP50Ms: Long? = null,
    val nativeAdvanceSamples: Int = 0,
) {
    val hasPlaybackHistory get() = startupSucceeded + startupFailed + sustainedPlaybackSamples > 0
    val hasWorkingHistory get() = startupSucceeded > startupFailed || sustainedPlaybackSamples > 2 * sustainedPlaybackStalls
    val repeatedPlaybackTrouble get() = startupFailed >= 2 && startupFailed > startupSucceeded ||
        earlyFailures >= 2 && earlyFailures >= earlyPlaybackSamples ||
        earlyPlaybackSamples >= 2 && (earlyRebufferP50Ms ?: 0L) >= 1_000L ||
        sustainedPlaybackStalls >= 2 && sustainedPlaybackStalls * 2 >= sustainedPlaybackSamples

    /** At comparable quality, displace the last working line only for a measured, stable advantage. */
    fun startsNoticeablyFasterThan(previous: AnimeLineHealth?): Boolean {
        if (previous == null || repeatedPlaybackTrouble || startupSucceeded <= startupFailed) return false
        val compareFrames = firstFrameSamples >= 2 && previous.firstFrameSamples >= 2
        if (!compareFrames && (nativeAdvanceSamples < 2 || previous.nativeAdvanceSamples < 2)) return false
        val stable = sustainedPlaybackSamples > 2 * sustainedPlaybackStalls || earlyPlaybackSamples > earlyStallCount + earlyFailures
        if (!stable) return false
        var ours = (if (compareFrames) firstFrameP50Ms else nativeAdvanceP50Ms) ?: return false
        var theirs = (if (compareFrames) previous.firstFrameP50Ms else previous.nativeAdvanceP50Ms) ?: return false
        if (addressP50Ms != null && previous.addressP50Ms != null) {
            ours += addressP50Ms
            theirs += previous.addressP50Ms
        }
        return theirs - ours >= 1_000L && ours * 4 <= theirs * 3
    }
}

/** Rate from actual network bytes while this video is transferring. Buffered pauses supply no speed sample. */
internal class AnimePlaybackTransferWindow {
    private data class Slice(val at: Long, val bytes: Long, val elapsed: Long)
    private val slices = ArrayDeque<Slice>()
    private var previousAt = -1L
    private var previousBytes = 0L

    fun observe(now: Long, received: Long?): Long? {
        if (received == null || received < 0L) return null
        val elapsed = now - previousAt
        if (previousAt >= 0L && elapsed in 0L until 1_000L && received >= previousBytes) return null
        val bytes = received - previousBytes
        if (previousAt < 0L || elapsed !in 1_000L..4_000L || bytes < 0L) slices.clear()
        else if (bytes > 0L) slices.addLast(Slice(now, bytes, elapsed))
        previousAt = now
        previousBytes = received
        while (slices.size > 6 || slices.firstOrNull()?.let { now - it.at > 15_000L } == true) slices.removeFirst()
        if (bytes <= 0L || slices.size < 3) return null
        val total = slices.sumOf { it.bytes }
        return if (total >= 64 * 1024L) total * 1_000L / slices.sumOf { it.elapsed } else null
    }
}

/** The app-owned controller records observations in memory. Disk work never holds the playback lock. */
internal class AnimeLineHealthStore(context: PlatformContext, scope: CoroutineScope) {
    private enum class Window { ADDRESS, STARTUP, EARLY_PLAYBACK, RESOLUTION, SUSTAINED_PLAYBACK }
    private enum class Kind(val window: Window) {
        ADDRESS(Window.ADDRESS), FIRST_FRAME(Window.STARTUP), PLAYABLE_OUTPUT(Window.STARTUP),
        STARTUP_FAILURE(Window.STARTUP), EARLY_REBUFFER(Window.EARLY_PLAYBACK),
        EARLY_FAILURE(Window.EARLY_PLAYBACK), DECODED_HEIGHT(Window.RESOLUTION),
        SUSTAINED_CLEAR(Window.SUSTAINED_PLAYBACK), SUSTAINED_STALL(Window.SUSTAINED_PLAYBACK)
    }

    private data class Observation(val kind: Kind, val time: Long, val value: Long, val viewing: AnimeLineViewing? = null)
    private data class CachedEstimate(val computedAt: Long, val expiresAt: Long, val health: AnimeLineHealth)

    private val lock = StorageLock()
    private val history = linkedMapOf<AnimeLineHealthKey, List<Observation>>()
    private val estimates = mutableMapOf<AnimeLineHealthKey, CachedEstimate>()
    private val writes = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            val file = StorageAtomicFile(StorageFile(context.filesDir, "anime-line-health-v1.json"))
            val restored = runCatching {
                file.openRead().use { input ->
                    if (file.baseFile.length() > HEALTH_MAX_FILE_BYTES) emptyMap()
                    else decode(input.bufferedReader().readText())
                }
            }.getOrDefault(emptyMap())
            if (restored.isNotEmpty()) synchronized(lock) {
                val now = wallTimeMillis()
                for ((key, rows) in restored) {
                    val merged = recent(rows + history[key].orEmpty(), now)
                    if (merged.isNotEmpty()) {
                        history[key] = merged
                        estimates.remove(key)
                    }
                }
                capKeysLocked()
            }
            for (ignored in writes) {
                delay(600L)
                writes.tryReceive() // This snapshot already includes other observations from the delay.
                val snapshot = synchronized(lock) { history.toMap() }
                val encoded = encode(snapshot)
                runCatching {
                    val output = file.startWrite()
                    try {
                        output.write(encoded)
                        file.finishWrite(output)
                    } catch (failure: Exception) {
                        file.failWrite(output)
                        throw failure
                    }
                }
            }
        }
    }

    fun recordAddress(key: AnimeLineHealthKey, elapsedMs: Long) = record(key, Kind.ADDRESS, elapsedMs)
    fun recordFirstFrame(key: AnimeLineHealthKey, elapsedMs: Long) = record(key, Kind.FIRST_FRAME, elapsedMs)
    // Zero is the original success-only record. A positive duration times source-backed
    // native video clock movement, independently of a presented FIRST_FRAME.
    fun recordPlayableOutput(key: AnimeLineHealthKey, nativeAdvanceMs: Long?) =
        record(key, Kind.PLAYABLE_OUTPUT, nativeAdvanceMs?.takeIf { it in 1L..HEALTH_MAX_SAMPLE_MS } ?: 0L)
    fun recordStartupFailure(key: AnimeLineHealthKey) = record(key, Kind.STARTUP_FAILURE, 0L)
    fun recordEarlyPlayback(key: AnimeLineHealthKey, rebufferMs: Long) = record(key, Kind.EARLY_REBUFFER, rebufferMs)
    fun recordEarlyFailure(key: AnimeLineHealthKey) = record(key, Kind.EARLY_FAILURE, 0L)
    fun recordSustainedPlayback(key: AnimeLineHealthKey, viewing: AnimeLineViewing, stalled: Boolean) =
        record(key, if (stalled) Kind.SUSTAINED_STALL else Kind.SUSTAINED_CLEAR, 0L, viewing)
    fun recordDecodedHeight(key: AnimeLineHealthKey, height: Int) {
        if (height in 144..8640) record(key, Kind.DECODED_HEIGHT, height.toLong())
    }

    fun estimate(key: AnimeLineHealthKey): AnimeLineHealth? {
        val now = wallTimeMillis()
        val observations = synchronized(lock) {
            val cached = estimates[key]
            if (cached != null && now >= cached.computedAt && now < cached.expiresAt) return cached.health
            history[key]
        } ?: return null
        val historyRows = observations.filter { it.isRecent(now) }
        if (historyRows.isEmpty()) return null
        // Once the current conditions have three observations, old failures on this network class
        // no longer dominate a recovered line. Sparse windows can still use the 72-hour history.
        val rows = historyRows.groupBy { it.kind.window }.values.flatMap { window ->
            window.filter { now - it.time < HEALTH_RECENT_AGE_MS }.takeIf { it.size >= 3 } ?: window
        }
        val starts = rows.filter { it.kind == Kind.FIRST_FRAME }.map { it.value }
        val nativeAdvances = rows.filter { it.kind == Kind.PLAYABLE_OUTPUT && it.value > 0L }.map { it.value }
        val rebuffer = rows.filter { it.kind == Kind.EARLY_REBUFFER }.map { it.value }
        val heights = rows.filter { it.kind == Kind.DECODED_HEIGHT }.map { it.value }.sorted()
        val health = AnimeLineHealth(starts.size + rows.count { it.kind == Kind.PLAYABLE_OUTPUT },
            rows.count { it.kind == Kind.STARTUP_FAILURE }, median(starts),
            median(rows.filter { it.kind == Kind.ADDRESS }.map { it.value }),
            rebuffer.size, rebuffer.count { it > 0L }, median(rebuffer),
            heights.getOrNull((heights.size - 1) / 2)?.toInt(), rows.count { it.kind == Kind.EARLY_FAILURE },
            rows.count { it.kind.window == Window.SUSTAINED_PLAYBACK }, rows.count { it.kind == Kind.SUSTAINED_STALL },
            firstFrameSamples = starts.size, nativeAdvanceP50Ms = median(nativeAdvances), nativeAdvanceSamples = nativeAdvances.size)
        val expiresAt = observations.minOf { observation ->
            when {
                observation.time > now -> observation.time
                now - observation.time < HEALTH_RECENT_AGE_MS -> observation.time + HEALTH_RECENT_AGE_MS
                now - observation.time < HEALTH_MAX_AGE_MS -> observation.time + HEALTH_MAX_AGE_MS
                else -> Long.MAX_VALUE
            }
        }
        synchronized(lock) {
            if (history[key] === observations) estimates[key] = CachedEstimate(now, expiresAt, health)
        }
        return health
    }

    private fun record(key: AnimeLineHealthKey, kind: Kind, value: Long, viewing: AnimeLineViewing? = null) {
        if (value !in 0L..HEALTH_MAX_SAMPLE_MS) return
        val now = wallTimeMillis()
        if (now <= 0L) return
        synchronized(lock) {
            if (viewing != null && !viewing.update(key, kind == Kind.SUSTAINED_STALL)) return
            val rows = history.remove(key).orEmpty()
            val retained = if (viewing == null) rows else rows.filterNot { it.viewing === viewing }
            history[key] = recent(retained + Observation(kind, now, value, viewing), now)
            estimates.remove(key)
            capKeysLocked()
        }
        writes.trySend(Unit)
    }

    private fun capKeysLocked() {
        if (history.size <= HEALTH_MAX_KEYS) return
        val oldest = history.entries.sortedBy { it.value.last().time }.take(history.size - HEALTH_MAX_KEYS)
        oldest.forEach {
            history.remove(it.key)
            estimates.remove(it.key)
        }
    }

    private fun recent(rows: List<Observation>, now: Long): List<Observation> = rows
        .filter { it.isRecent(now) }.sortedBy { it.time }.groupBy { it.kind.window }.values
        .flatMap { it.takeLast(HEALTH_WINDOW_SIZE) }.sortedBy { it.time }

    private fun Observation.isRecent(now: Long) = time > 0L && now - time in 0L until HEALTH_MAX_AGE_MS

    private fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }

    private fun encode(history: Map<AnimeLineHealthKey, List<Observation>>): ByteArray {
        val now = wallTimeMillis()
        val data = JSONObject()
        for ((key, rows) in history) {
            val current = rows.filter { it.isRecent(now) }
            if (current.isNotEmpty()) {
                // Older readers scan at most four windows. Keep all of their observations first.
                val (sustained, original) = current.partition { it.kind.window == Window.SUSTAINED_PLAYBACK }
                data.put(key.digest, JSONArray((original + sustained).map { listOf(it.kind.name, it.time, it.value) }))
            }
        }
        return data.toString().encodeToByteArray()
    }

    private fun decode(raw: String): Map<AnimeLineHealthKey, List<Observation>> {
        val data = JSONObject(raw)
        val now = wallTimeMillis()
        return buildMap {
            for (digest in data.keys().asSequence().take(HEALTH_MAX_KEYS)) {
                if (digest.length != 64 || digest.any { it !in '0'..'9' && it !in 'a'..'f' }) continue
                val samples = data.optJSONArray(digest) ?: continue
                val rows = (0 until minOf(samples.length(), Window.entries.size * HEALTH_WINDOW_SIZE)).mapNotNull { index ->
                    val row = samples.optJSONArray(index) ?: return@mapNotNull null
                    val kind = Kind.entries.firstOrNull { it.name == row.optString(0) } ?: return@mapNotNull null
                    val value = row.optLong(2, -1L)
                    val range = when {
                        kind == Kind.DECODED_HEIGHT -> 144L..8640L
                        kind.window == Window.SUSTAINED_PLAYBACK -> 0L..0L
                        else -> 0L..HEALTH_MAX_SAMPLE_MS
                    }
                    if (value !in range) return@mapNotNull null
                    Observation(kind, row.optLong(1), value)
                }
                val current = recent(rows, now)
                if (current.isNotEmpty()) put(AnimeLineHealthKey(digest), current)
            }
        }
    }
}
