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
private const val HEALTH_MAX_KEYS = 128
private const val HEALTH_WINDOW_SIZE = 14
private const val HEALTH_MAX_SAMPLE_MS = 180_000L
private const val HEALTH_MAX_FILE_BYTES = 512 * 1024L

internal data class AnimeLineHealthKey(val digest: String)

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
)

/** The app-owned controller records observations in memory. Disk work never holds the playback lock. */
internal class AnimeLineHealthStore(context: PlatformContext, scope: CoroutineScope) {
    private enum class Window { ADDRESS, STARTUP, EARLY_PLAYBACK }
    private enum class Kind(val window: Window) {
        ADDRESS(Window.ADDRESS), FIRST_FRAME(Window.STARTUP), PLAYABLE_OUTPUT(Window.STARTUP),
        STARTUP_FAILURE(Window.STARTUP), EARLY_REBUFFER(Window.EARLY_PLAYBACK)
    }

    private data class Observation(val kind: Kind, val time: Long, val elapsedMs: Long)

    private val lock = StorageLock()
    private val history = linkedMapOf<AnimeLineHealthKey, List<Observation>>()
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
                    if (merged.isNotEmpty()) history[key] = merged
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
    fun recordPlayableOutput(key: AnimeLineHealthKey) = record(key, Kind.PLAYABLE_OUTPUT, 0L)
    fun recordStartupFailure(key: AnimeLineHealthKey) = record(key, Kind.STARTUP_FAILURE, 0L)
    fun recordEarlyPlayback(key: AnimeLineHealthKey, rebufferMs: Long) = record(key, Kind.EARLY_REBUFFER, rebufferMs)

    fun estimate(key: AnimeLineHealthKey): AnimeLineHealth? {
        val now = wallTimeMillis()
        val rows = synchronized(lock) { history[key] }?.filter { it.isRecent(now) }.orEmpty()
        if (rows.isEmpty()) return null
        val starts = rows.filter { it.kind == Kind.FIRST_FRAME }.map { it.elapsedMs }
        val rebuffer = rows.filter { it.kind == Kind.EARLY_REBUFFER }.map { it.elapsedMs }
        return AnimeLineHealth(starts.size + rows.count { it.kind == Kind.PLAYABLE_OUTPUT },
            rows.count { it.kind == Kind.STARTUP_FAILURE }, median(starts),
            median(rows.filter { it.kind == Kind.ADDRESS }.map { it.elapsedMs }),
            rebuffer.size, rebuffer.count { it > 0L }, median(rebuffer))
    }

    private fun record(key: AnimeLineHealthKey, kind: Kind, elapsedMs: Long) {
        if (elapsedMs !in 0L..HEALTH_MAX_SAMPLE_MS) return
        val now = wallTimeMillis()
        if (now <= 0L) return
        synchronized(lock) {
            val rows = history.remove(key).orEmpty()
            history[key] = recent(rows + Observation(kind, now, elapsedMs), now)
            capKeysLocked()
        }
        writes.trySend(Unit)
    }

    private fun capKeysLocked() {
        if (history.size <= HEALTH_MAX_KEYS) return
        val oldest = history.entries.sortedBy { it.value.last().time }.take(history.size - HEALTH_MAX_KEYS)
        oldest.forEach { history.remove(it.key) }
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
            if (current.isNotEmpty()) data.put(key.digest,
                JSONArray(current.map { listOf(it.kind.name, it.time, it.elapsedMs) }))
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
                    val elapsedMs = row.optLong(2, -1L).takeIf { it in 0L..HEALTH_MAX_SAMPLE_MS } ?: return@mapNotNull null
                    Observation(kind, row.optLong(1), elapsedMs)
                }
                val current = recent(rows, now)
                if (current.isNotEmpty()) put(AnimeLineHealthKey(digest), current)
            }
        }
    }
}
