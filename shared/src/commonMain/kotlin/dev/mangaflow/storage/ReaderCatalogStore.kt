package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import dev.mangaflow.platform.storage.StorageAtomicFile as AtomicFile
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.platform.storage.StorageFile as File
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.AtomicLong
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.experience.contentCachePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject

/** A saved directory lets local images participate in the ordinary reader after a restart. */
class ReaderCatalogStore(private val context: Context) {
    private val root = File(context.applicationContext.filesDir, "reader-catalogs")
    // Repository and ViewModel instances target the same AtomicFile siblings.
    private val state = synchronized(statesLock) { states.getOrPut(root.path) { CatalogState(root) } }

    suspend fun read(mangaId: String): MangaDetail? {
        val generation = state.epoch.get()
        cached(mangaId, generation)?.let {
            StorageCacheStore.get(context).noteCatalog(mangaId)
            return it
        }
        if (!canStoreOnDisk(mangaId)) return null
        return withContext(Dispatchers.IO) {
            state.files.withLock {
                if (generation != state.epoch.get()) return@withLock null
                cached(mangaId, generation)?.let { return@withLock it }
                val loaded = runCatching {
                    val file = fileFor(mangaId)
                    if (!reusable(mangaId, file.lastModified())) return@runCatching null
                    val json = AtomicFile(file).openRead().bufferedReader().use { input ->
                        if (file.length() !in 1..MAX_FILE_BYTES) return@runCatching null
                        JSONObject(input.readText())
                    }
                    val metadata = json.getJSONObject("manga")
                    val manga = readManga(metadata).copy(sourceUuid = metadata.optString("sourceUuid"))
                    if (manga.id != mangaId) return@runCatching null
                    val array = json.getJSONArray("chapters")
                    val chapters = (0 until array.length()).map { readChapter(array.getJSONObject(it)) }
                        .distinctBy { it.id }.sortedBy { it.index }
                    if (chapters.isEmpty()) null else MangaDetail(manga, chapters).also {
                        remember(it, file.length(), generation, file.lastModified())
                    }
                }.getOrNull()
                loaded.takeIf { generation == state.epoch.get() && reusable(mangaId, fileFor(mangaId).lastModified()) }
                    ?.also { StorageCacheStore.get(context).noteCatalog(mangaId) }
            }
        }
    }

    suspend fun save(detail: MangaDetail, isCurrent: () -> Boolean = { true }) {
        if (detail.chapters.isEmpty() || !isCurrent()) return
        StorageCacheStore.get(context).noteCatalog(detail.manga.id)
        val generation = state.epoch.get()
        val token = state.sequence.incrementAndGet()
        synchronized(state.memoryLock) { state.writes[detail.manga.id] = token }
        try {
            withContext(Dispatchers.IO) {
                if (!currentWrite(detail.manga.id, generation, token, isCurrent)) return@withContext
                if (cached(detail.manga.id, generation) == detail) return@withContext
                // Encoding another book must not occupy the shared AtomicFile gate.
                val bytes = runCatching {
                    JSONObject().apply {
                        put("manga", detail.manga.toJson().put("sourceUuid", detail.manga.sourceUuid))
                        put("chapters", JSONArray(detail.chapters.map { it.toJson() }))
                    }.toString().encodeToByteArray()
                }.getOrNull() ?: return@withContext
                if (bytes.size > MAX_FILE_BYTES) return@withContext
                if (!canStoreOnDisk(detail.manga.id)) {
                    if (contentCachePreferences(context).enabled && currentWrite(detail.manga.id, generation, token, isCurrent))
                        remember(detail, bytes.size.toLong(), generation)
                    return@withContext
                }
                state.files.withLock {
                    if (!currentWrite(detail.manga.id, generation, token, isCurrent) || !canStoreOnDisk(detail.manga.id)) return@withLock
                    if (cached(detail.manga.id, generation) == detail) return@withLock
                    // Disk failure prevents caching, not otherwise valid online reading.
                    runCatching {
                        check(root.isDirectory || root.mkdirs())
                        val file = AtomicFile(fileFor(detail.manga.id))
                        val stream = file.startWrite()
                        try {
                            stream.write(bytes)
                            if (!currentWrite(detail.manga.id, generation, token, isCurrent) || !canStoreOnDisk(detail.manga.id)) {
                                file.failWrite(stream)
                                return@runCatching
                            }
                            file.finishWrite(stream)
                            state.cacheFiles.changed(file.baseFile.name)
                            remember(detail, bytes.size.toLong(), generation)
                        } catch (failure: Exception) {
                            file.failWrite(stream)
                            throw failure
                        }
                    }
                }
            }
        } finally {
            synchronized(state.memoryLock) {
                if (state.writes[detail.manga.id] == token) state.writes.remove(detail.manga.id)
            }
            StorageCacheStore.get(context).cacheWorkFinished()
        }
    }

    /** Catalogue entries share the cache review contract while retaining offline dependencies. */
    internal val cacheOwner: AuxiliaryDiskCacheOwner = object : AuxiliaryDiskCacheOwner {
        override val ownerId: String = "catalog"
        override suspend fun snapshot(): List<AuxiliaryCacheEntry> = withContext(Dispatchers.IO) {
            state.files.withLock {
                state.cacheFiles.snapshot { filename ->
                    catalogOwner(File(root, filename))?.let { owner ->
                        synchronized(state.memoryLock) { state.memoryRevisions[owner] ?: 0L }
                    } ?: 0L
                }.mapNotNull { entry -> catalogOwner(File(root, entry.key))?.let { entry.copy(key = it) } }
            }
        }
        override suspend fun remove(expected: AuxiliaryCacheEntry): Long? = withContext(Dispatchers.IO) {
            state.files.withLock {
                val owner = expected.key
                if (StorageCacheStore.get(context).protectedCatalog(owner) || isWriting(owner)) return@withLock null
                val file = fileFor(owner)
                val reviewed = expected.copy(key = file.name)
                val memoryRevision = synchronized(state.memoryLock) { state.memoryRevisions[owner] ?: 0L }
                if (state.cacheFiles.entry(file.name, memoryRevision, verifyBytes = true) != reviewed) return@withLock null
                synchronized(state.memoryLock) {
                    // Hashing yields to queued/new save intents. Recheck their exact identity at deletion.
                    if (owner in state.writes || (state.memoryRevisions[owner] ?: 0L) != memoryRevision ||
                        StorageCacheStore.get(context).protectedCatalog(owner)) return@synchronized null
                    var freed = 0L
                    try {
                        freed = state.cacheFiles.removeReviewed(reviewed)
                        freed
                    } catch (failure: AuxiliaryCacheRemovalException) {
                        freed = failure.freedBytes
                        throw failure
                    } finally {
                        if (freed > 0L) {
                            state.memory.remove(owner)
                            state.memoryRevisions.remove(owner)
                            state.diskOwners.remove(file.path)
                            StorageCacheStore.get(context).cacheWorkFinished()
                        }
                    }
                }
            }
        }
    }

    internal fun isWriting(mangaId: String): Boolean = synchronized(state.memoryLock) { mangaId in state.writes }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        state.files.withLock { root.listFiles()?.sumOf { it.length() } ?: 0L }
    }

    suspend fun clear() {
        trimToSize(0L, StorageCacheStore.get(context)::protectedCatalog)
    }

    /** Parse only bounded catalogue metadata under the same gate as readers and writers. */
    internal suspend fun cacheUsage(protect: (String) -> Boolean): Pair<Long, Long> = withContext(Dispatchers.IO) {
        state.files.withLock {
            val files = root.listFiles().orEmpty()
            val reclaimable = files.filter { file -> catalogOwner(file)?.let { !protect(it) } == true }.sumOf { it.length() }
            files.sumOf { it.length() } to reclaimable
        }
    }

    internal suspend fun trimToSize(maxBytes: Long, protect: (String) -> Boolean): Long = withContext(Dispatchers.IO) {
        state.files.withLock {
            var total = root.listFiles().orEmpty().sumOf { it.length() }
            val retention = contentCachePreferences(context).retentionMillis
            val now = wallTimeMillis()
            var freed = 0L
            try {
                root.listFiles().orEmpty().sortedBy { file ->
                    catalogOwner(file)?.let { StorageCacheStore.get(context).catalogAccess(it) }?.takeIf { it > 0L } ?: file.lastModified()
                }.forEach { file ->
                    val owner = catalogOwner(file) ?: return@forEach
                    val last = StorageCacheStore.get(context).catalogAccess(owner).takeIf { it > 0L } ?: file.lastModified()
                    if (total <= maxBytes && now - last < retention) return@forEach
                    // Re-evaluate dependencies immediately before removing this AtomicFile family.
                    if (protect(owner)) return@forEach
                    val bytes = file.length()
                    AtomicFile(file).delete()
                    state.cacheFiles.changed(file.name)
                    if (!file.exists()) { total -= bytes; freed += bytes; state.diskOwners.remove(file.path) }
                }
            } finally { if (freed > 0L) invalidateMemory() }
            freed
        }
    }

    private fun catalogOwner(file: File): String? = runCatching {
        if (file.extension != "json" || file.length() !in 1..MAX_FILE_BYTES ||
            File(file.path + ".new").exists() || File(file.path + ".bak").exists()) return@runCatching null
        val bytes = file.length()
        val modified = file.lastModified()
        state.diskOwners[file.path]?.takeIf { it.first == bytes && it.second == modified }?.let { return@runCatching it.third }
        JSONObject(file.readText()).getJSONObject("manga").getString("id").takeIf { it.isNotBlank() }?.also {
            state.diskOwners[file.path] = Triple(bytes, modified, it)
        }
    }.getOrNull()

    private fun invalidateMemory() {
        state.epoch.incrementAndGet()
        synchronized(state.memoryLock) {
            state.memory.clear()
            state.memoryRevisions.clear()
            state.writes.clear()
        }
    }

    private fun cached(mangaId: String, generation: Long): MangaDetail? = synchronized(state.memoryLock) {
        if (generation != state.epoch.get()) return@synchronized null
        state.memory.remove(mangaId)?.takeIf { reusable(mangaId, it.storedAt) }
            ?.also { state.memory[mangaId] = it }?.detail
    }

    private fun remember(detail: MangaDetail, bytes: Long, generation: Long, storedAt: Long = wallTimeMillis()) = synchronized(state.memoryLock) {
        if (generation != state.epoch.get()) return@synchronized
        state.memory.remove(detail.manga.id)
        state.memoryRevisions[detail.manga.id] = state.sequence.incrementAndGet()
        val policy = dev.mangaflow.experience.ExperiencePolicyStore.get(context).state.value.runtime
        val maxBytes = policy.catalogMemoryMiB.toLong() * 1_048_576L
        if (bytes <= maxBytes) state.memory[detail.manga.id] = CachedCatalog(detail, bytes, storedAt)
        else state.memoryRevisions.remove(detail.manga.id)
        while (state.memory.size > policy.catalogMemoryEntries || state.memory.values.sumOf { it.bytes } > maxBytes) {
            val oldest = state.memory.keys.first()
            state.memory.remove(oldest)
            state.memoryRevisions.remove(oldest)
        }
    }

    private fun currentWrite(mangaId: String, generation: Long, token: Long, isCurrent: () -> Boolean): Boolean =
        generation == state.epoch.get() && isCurrent() &&
            synchronized(state.memoryLock) { state.writes[mangaId] == token }

    private fun fileFor(mangaId: String): File {
        val key = sha256(mangaId.encodeToByteArray())
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        return File(root, "$key.json")
    }

    private fun reusable(mangaId: String, storedAt: Long): Boolean =
        StorageCacheStore.get(context).protectedCatalog(mangaId) || contentCachePreferences(context).let { policy ->
            policy.enabled && storedAt > 0L && wallTimeMillis() - storedAt in 0L..policy.retentionMillis
        }

    private fun canStoreOnDisk(mangaId: String): Boolean =
        StorageCacheStore.get(context).protectedCatalog(mangaId) || contentCachePreferences(context).storeOnDisk

    private data class CachedCatalog(val detail: MangaDetail, val bytes: Long, val storedAt: Long)
    private class CatalogState(root: File) {
        val cacheFiles = AuxiliaryCacheFiles(root) { it.matches(Regex("[a-f0-9]{64}\\.json")) }
        val files = Mutex()
        val memoryLock = StorageLock()
        val epoch = AtomicLong()
        val sequence = AtomicLong()
        val memory = LinkedHashMap<String, CachedCatalog>()
        val writes = mutableMapOf<String, Long>()
        val memoryRevisions = mutableMapOf<String, Long>()
        val diskOwners = mutableMapOf<String, Triple<Long, Long, String>>()
    }

    private companion object {
        val statesLock = StorageLock()
        val states = mutableMapOf<String, CatalogState>()
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }
}
