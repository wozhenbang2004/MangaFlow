package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.StorageAtomicFile as AtomicFile
import dev.mangaflow.data.FederatedSearchSource
import dev.mangaflow.data.Manga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.storage.StorageFile as File
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.AtomicLong

data class BrowseSnapshot(
    val key: String,
    val items: List<Manga>,
    val page: Int,
    val hasMore: Boolean,
    val updatedAt: Long,
    val sourceMatches: Map<String, List<Manga>> = emptyMap(),
    val sourceNextPages: Map<String, Int> = emptyMap(),
    val sourceChoiceIds: Set<String> = emptySet(),
    val searchSources: Map<String, FederatedSearchSource> = emptyMap(),
)

/** Disposable public lists only. Favorites, reading positions and downloads live elsewhere. */
class BrowseCache(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, "browse-cache")
    private val memory = LinkedHashMap<String, BrowseSnapshot>(MAX_ENTRIES)
    private val memoryLock = StorageLock()
    private val diskLock = Mutex()
    private val epoch = AtomicLong()

    val generation: Long get() = epoch.get()

    fun cached(key: String): BrowseSnapshot? = synchronized(memoryLock) {
        memory.remove(key)?.takeIf { usableTime(it.updatedAt) }?.also { memory[key] = it }
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
                        readCachedManga(value)
                    }
                    if (items.any { it.id.isBlank() || it.title.isBlank() } || items.distinctBy(Manga::id).size != items.size) {
                        return@runCatching null
                    }
                    val matchesJson = json.optJSONObject("sourceMatches")
                    if (matchesJson != null && matchesJson.length() > MAX_ITEMS) return@runCatching null
                    var matchCount = 0
                    val matches = matchesJson?.let { groups ->
                        groups.keys().asSequence().associateWith { id ->
                            require(id.isNotBlank())
                            val values = groups.getJSONArray(id)
                            matchCount += values.length()
                            require(matchCount <= MAX_SOURCE_HITS)
                            (0 until values.length()).map { index -> readCachedManga(values.getJSONObject(index)) }.also { rows ->
                                require(rows.all { it.id.isNotBlank() && it.title.isNotBlank() })
                            }
                        }
                    }.orEmpty()
                    val nextJson = json.optJSONObject("sourceNextPages")
                    if (nextJson != null && nextJson.length() > MAX_SOURCES) return@runCatching null
                    val nextPages = nextJson?.let { sources ->
                        sources.keys().asSequence().associateWith { id ->
                            require(id.isNotBlank())
                            sources.getInt(id).also { require(it in 0..MAX_PAGE) }
                        }
                    }.orEmpty()
                    val choiceJson = json.optJSONArray("sourceChoiceIds")
                    if (choiceJson != null && choiceJson.length() > MAX_ITEMS) return@runCatching null
                    val choices = choiceJson?.let { array ->
                        (0 until array.length()).map { array.getString(it).also { id -> require(id.isNotBlank()) } }.toSet()
                    }.orEmpty()
                    val searchJson = json.optJSONObject("searchSources")
                    require(!json.has("searchSources") || searchJson != null)
                    if (searchJson != null && searchJson.length() > MAX_SOURCES) return@runCatching null
                    val searchSources = searchJson?.let { sources ->
                        sources.keys().asSequence().associateWith { id ->
                            val value = sources.getJSONObject(id)
                            fun page(name: String): Int? = if (value.has(name) && !value.isNull(name)) value.getInt(name) else null
                            FederatedSearchSource(
                                sourceId = value.getString("sourceId"),
                                sourceName = value.getString("sourceName"),
                                loading = value.optBoolean("loading", false),
                                error = if (value.has("error") && !value.isNull("error")) value.getString("error") else null,
                                hasMore = value.getBoolean("hasMore"),
                                nextPage = page("nextPage"),
                                failedPage = page("failedPage"),
                            )
                        }
                    }.orEmpty()
                    BrowseSnapshot(key, items, page, json.getBoolean("hasMore"), updatedAt,
                        matches, nextPages, choices, searchSources).takeIf(::withinBounds)
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
        if (!withinBounds(snapshot)) return
        synchronized(memoryLock) {
            if (generation != epoch.get()) return
            memory.remove(snapshot.key)
            memory[snapshot.key] = snapshot
            while (memory.size > MAX_ENTRIES || memory.values.sumOf { it.items.size + it.sourceMatches.values.sumOf(List<Manga>::size) } > MAX_MEMORY_ITEMS) {
                memory.remove(memory.keys.first())
            }
        }
    }

    suspend fun write(snapshot: BrowseSnapshot, generation: Long): Unit = withContext(Dispatchers.IO) {
        // Keep the last complete cached page range rather than truncating and skipping manga.
        if (!withinBounds(snapshot) || generation != epoch.get()) return@withContext
        // Superseded requests should not encode a large range merely to be rejected after IO.
        if (synchronized(memoryLock) { memory[snapshot.key]?.let { it !== snapshot } == true }) return@withContext
        val encoded = JSONObject().apply {
            put("version", VERSION)
            put("key", snapshot.key)
            put("updatedAt", snapshot.updatedAt)
            put("page", snapshot.page)
            put("hasMore", snapshot.hasMore)
            put("items", JSONArray(snapshot.items.map(::writeCachedManga)))
            if (snapshot.sourceMatches.isNotEmpty()) put("sourceMatches", JSONObject().apply {
                snapshot.sourceMatches.forEach { (id, matches) -> put(id, JSONArray(matches.map(::writeCachedManga))) }
            })
            if (snapshot.sourceNextPages.isNotEmpty()) put("sourceNextPages", JSONObject(snapshot.sourceNextPages))
            if (snapshot.sourceChoiceIds.isNotEmpty()) put("sourceChoiceIds", JSONArray(snapshot.sourceChoiceIds.toList()))
            if (snapshot.searchSources.isNotEmpty()) put("searchSources", JSONObject().apply {
                snapshot.searchSources.forEach { (id, source) -> put(id, JSONObject().apply {
                    put("sourceId", source.sourceId)
                    put("sourceName", source.sourceName)
                    put("loading", false)
                    put("hasMore", source.hasMore)
                    source.error?.let { put("error", it) }
                    source.nextPage?.let { put("nextPage", it) }
                    source.failedPage?.let { put("failedPage", it) }
                }) }
            })
        }.toString().encodeToByteArray()
        if (encoded.size > MAX_FILE_BYTES) return@withContext
        diskLock.withLock {
            if (generation != epoch.get()) return@withLock
            // A later page or refresh can finish while this write is waiting for IO.
            if (synchronized(memoryLock) { memory[snapshot.key]?.let { it !== snapshot } == true }) return@withLock
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
        synchronized(memoryLock) { memory.clear() }
        withContext(Dispatchers.IO) {
            diskLock.withLock {
                val failures = directory.listFiles().orEmpty().count { file -> file.isFile && !file.delete() }
                if (failures > 0) throw IllegalStateException("部分浏览缓存未能清理，请重试。")
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
        val digest = sha256(key.encodeToByteArray())
            .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
        return File(directory, "$digest.json")
    }

    private fun usableTime(updatedAt: Long): Boolean =
        wallTimeMillis() - updatedAt in -CLOCK_SKEW_MS..MAX_STALE_MS && updatedAt > 0

    private fun withinBounds(snapshot: BrowseSnapshot): Boolean = snapshot.items.size <= MAX_ITEMS &&
        snapshot.sourceMatches.size <= MAX_ITEMS && snapshot.sourceMatches.values.sumOf(List<Manga>::size) <= MAX_SOURCE_HITS &&
        snapshot.sourceNextPages.size <= MAX_SOURCES && snapshot.sourceNextPages.values.all { it in 0..MAX_PAGE } &&
        snapshot.sourceChoiceIds.size <= MAX_ITEMS && snapshot.searchSources.size <= MAX_SOURCES &&
        snapshot.searchSources.all { (id, source) ->
            SOURCE_ID_PATTERN.matches(id) && source.sourceId == id &&
                source.sourceName.isNotBlank() && source.sourceName.length <= MAX_SOURCE_NAME_LENGTH &&
                !source.loading && (source.error?.length ?: 0) <= MAX_SOURCE_ERROR_LENGTH &&
                (source.nextPage == null || source.nextPage in 0..MAX_PAGE) &&
                (source.failedPage == null || source.failedPage in 0..MAX_PAGE)
        }

    private companion object {
        const val VERSION = 1
        const val MAX_ENTRIES = 24
        const val MAX_ITEMS = 600
        const val MAX_MEMORY_ITEMS = 2_400
        const val MAX_SOURCE_HITS = 3_600
        const val MAX_SOURCES = 64
        const val MAX_SOURCE_NAME_LENGTH = 120
        const val MAX_SOURCE_ERROR_LENGTH = 300
        val SOURCE_ID_PATTERN = Regex("[a-z][a-z0-9_-]{0,39}")
        const val MAX_PAGE = 100_000
        const val MAX_FILE_BYTES = 1024L * 1024
        const val MAX_DISK_BYTES = 8L * 1024 * 1024
        const val CLOCK_SKEW_MS = 5L * 60_000
        const val MAX_STALE_MS = 7L * 24 * 60 * 60_000
    }
}

private fun readCachedManga(value: JSONObject): Manga = readManga(value).copy(
    popularity = value.opt("popularity")?.toString()?.toLongOrNull()?.takeIf { it >= 0 },
    rank = value.opt("rank")?.toString()?.toIntOrNull()?.takeIf { it > 0 },
    totalPopularity = value.opt("totalPopularity")?.toString()?.toLongOrNull()?.takeIf { it >= 0 },
)

private fun writeCachedManga(manga: Manga): JSONObject = manga.toJson().apply {
    manga.popularity?.let { put("popularity", it) }
    manga.rank?.let { put("rank", it) }
    manga.totalPopularity?.let { put("totalPopularity", it) }
}
