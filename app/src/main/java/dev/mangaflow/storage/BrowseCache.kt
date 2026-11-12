package dev.mangaflow.storage

import android.content.Context
import android.util.AtomicFile
import dev.mangaflow.data.Manga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

data class BrowseSnapshot(
    val key: String,
    val items: List<Manga>,
    val page: Int,
    val hasMore: Boolean,
    val updatedAt: Long,
)

/** Disposable public lists only. Favorites, reading positions and downloads live elsewhere. */
class BrowseCache(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "browse-cache")
    private val memory = LinkedHashMap<String, BrowseSnapshot>(MAX_ENTRIES, 0.75f, true)
    private val diskLock = Mutex()
    private val epoch = AtomicLong()

    val generation: Long get() = epoch.get()

    fun cached(key: String): BrowseSnapshot? = synchronized(memory) {
        memory[key]?.takeIf { usableTime(it.updatedAt) }
    }

    suspend fun read(key: String, generation: Long): BrowseSnapshot? {
        cached(key)?.let { return if (generation == epoch.get()) it else null }
        return withContext(Dispatchers.IO) {
            diskLock.withLock {
                if (generation != epoch.get()) return@withLock null
                val file = fileFor(key)
                val snapshot = runCatching {
                    if (!file.isFile || file.length() !in 1..MAX_FILE_BYTES) return@runCatching null
                    val json = JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
                    if (json.optInt("version") != VERSION || json.getString("key") != key) return@runCatching null
                    val updatedAt = json.getLong("updatedAt")
                    val page = json.getInt("page")
                    val values = json.getJSONArray("items")
                    if (!usableTime(updatedAt) || page !in 0..MAX_PAGE || values.length() > MAX_ITEMS) return@runCatching null
                    val items = (0 until values.length()).map { index ->
                        val value = values.getJSONObject(index)
                        readManga(value).copy(
                            sourceUuid = value.optString("sourceUuid"),
                            popularity = value.opt("popularity")?.toString()?.toLongOrNull()?.takeIf { it >= 0 },
                            rank = value.opt("rank")?.toString()?.toIntOrNull()?.takeIf { it > 0 },
                            totalPopularity = value.opt("totalPopularity")?.toString()?.toLongOrNull()?.takeIf { it >= 0 },
                        )
                    }
                    if (items.any { it.id.isBlank() || it.title.isBlank() } || items.distinctBy(Manga::id).size != items.size) {
                        return@runCatching null
                    }
                    BrowseSnapshot(key, items, page, json.getBoolean("hasMore"), updatedAt)
                }.getOrNull()
                if (snapshot == null) {
                    AtomicFile(file).delete()
                    null
                } else {
                    remember(snapshot, generation)
                    snapshot.takeIf { generation == epoch.get() }
                }
            }
        }
    }

    /** Small memory updates never wait on JSON encoding or disk I/O. */
    fun remember(snapshot: BrowseSnapshot, generation: Long) {
        if (snapshot.items.size > MAX_ITEMS) return
        synchronized(memory) {
            if (generation != epoch.get()) return
            memory[snapshot.key] = snapshot
            while (memory.size > MAX_ENTRIES || memory.values.sumOf { it.items.size } > MAX_MEMORY_ITEMS) {
                memory.remove(memory.keys.first())
            }
        }
    }

    suspend fun write(snapshot: BrowseSnapshot, generation: Long): Unit = withContext(Dispatchers.IO) {
        // Keep the last complete cached page range rather than truncating and skipping manga.
        if (snapshot.items.size > MAX_ITEMS || generation != epoch.get()) return@withContext
        val encoded = JSONObject().apply {
            put("version", VERSION)
            put("key", snapshot.key)
            put("updatedAt", snapshot.updatedAt)
            put("page", snapshot.page)
            put("hasMore", snapshot.hasMore)
            put("items", JSONArray(snapshot.items.map { manga ->
                manga.toJson().apply {
                    put("sourceUuid", manga.sourceUuid)
                    manga.popularity?.let { put("popularity", it) }
                    manga.rank?.let { put("rank", it) }
                    manga.totalPopularity?.let { put("totalPopularity", it) }
                }
            }))
        }.toString().toByteArray(Charsets.UTF_8)
        if (encoded.size > MAX_FILE_BYTES) return@withContext
        diskLock.withLock {
            if (generation != epoch.get()) return@withLock
            // A later page or refresh can finish while this write is waiting for IO.
            if (synchronized(memory) { memory[snapshot.key]?.let { it !== snapshot } == true }) return@withLock
            runCatching {
                if (!directory.isDirectory && !directory.mkdirs()) return@runCatching
                val atomic = AtomicFile(fileFor(snapshot.key))
                val output = atomic.startWrite()
                try {
                    output.write(encoded)
                    atomic.finishWrite(output)
                } catch (failure: Exception) {
                    atomic.failWrite(output)
                    throw failure
                }
                trimDisk()
            }
            // A full/unavailable cache disk must not turn a successful source response into an error.
            Unit
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        diskLock.withLock { directory.listFiles().orEmpty().filter(File::isFile).sumOf(File::length) }
    }

    suspend fun clear() {
        epoch.incrementAndGet()
        synchronized(memory) { memory.clear() }
        withContext(Dispatchers.IO) {
            diskLock.withLock {
                val failures = directory.listFiles().orEmpty().count { file -> file.isFile && !file.delete() }
                if (failures > 0) throw java.io.IOException("部分浏览缓存未能清理，请重试。")
            }
        }
    }

    private fun trimDisk() {
        val files = directory.listFiles().orEmpty().filter(File::isFile).sortedByDescending(File::lastModified)
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= MAX_ENTRIES || bytes > MAX_DISK_BYTES) file.delete()
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(directory, "$digest.json")
    }

    private fun usableTime(updatedAt: Long): Boolean =
        System.currentTimeMillis() - updatedAt in -CLOCK_SKEW_MS..MAX_STALE_MS && updatedAt > 0

    private companion object {
        const val VERSION = 1
        const val MAX_ENTRIES = 24
        const val MAX_ITEMS = 600
        const val MAX_MEMORY_ITEMS = 2_400
        const val MAX_PAGE = 100_000
        const val MAX_FILE_BYTES = 1024L * 1024
        const val MAX_DISK_BYTES = 8L * 1024 * 1024
        const val CLOCK_SKEW_MS = 5L * 60_000
        const val MAX_STALE_MS = 7L * 24 * 60 * 60_000
    }
}
