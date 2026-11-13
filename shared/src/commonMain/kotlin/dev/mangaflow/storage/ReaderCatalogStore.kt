package dev.mangaflow.storage

import dev.mangaflow.platform.PlatformContext as Context
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import dev.mangaflow.platform.storage.StorageAtomicFile as AtomicFile
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.platform.storage.StorageFile as File
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject

/** A saved directory lets local images participate in the ordinary reader after a restart. */
class ReaderCatalogStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "reader-catalogs")
    // Repository and ViewModel instances target the same AtomicFile siblings.
    private val state = synchronized(statesLock) { states.getOrPut(root.path) { CatalogState() } }

    suspend fun read(mangaId: String): MangaDetail? {
        val generation = state.epoch.get()
        cached(mangaId, generation)?.let { return it }
        return withContext(Dispatchers.IO) {
            state.files.withLock {
                if (generation != state.epoch.get()) return@withLock null
                cached(mangaId, generation)?.let { return@withLock it }
                val loaded = runCatching {
                    val file = fileFor(mangaId)
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
                        remember(it, file.length(), generation)
                    }
                }.getOrNull()
                loaded.takeIf { generation == state.epoch.get() }
            }
        }
    }

    suspend fun save(detail: MangaDetail, isCurrent: () -> Boolean = { true }) {
        if (detail.chapters.isEmpty() || !isCurrent()) return
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
                state.files.withLock {
                    if (!currentWrite(detail.manga.id, generation, token, isCurrent)) return@withLock
                    if (cached(detail.manga.id, generation) == detail) return@withLock
                    // Disk failure prevents caching, not otherwise valid online reading.
                    runCatching {
                        check(root.isDirectory || root.mkdirs())
                        val file = AtomicFile(fileFor(detail.manga.id))
                        val stream = file.startWrite()
                        try {
                            stream.write(bytes)
                            if (!currentWrite(detail.manga.id, generation, token, isCurrent)) {
                                file.failWrite(stream)
                                return@runCatching
                            }
                            file.finishWrite(stream)
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
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        state.files.withLock { root.listFiles()?.sumOf { it.length() } ?: 0L }
    }

    suspend fun clear() {
        invalidateMemory()
        withContext(Dispatchers.IO) {
            state.files.withLock {
                try {
                    root.listFiles()?.forEach { file ->
                        check(file.delete() || !file.exists()) { "目录缓存未完全清除，请重试" }
                    }
                } finally {
                    // Also reject writers queued while the clear itself was waiting for IO.
                    invalidateMemory()
                }
            }
        }
    }

    private fun invalidateMemory() {
        state.epoch.incrementAndGet()
        synchronized(state.memoryLock) {
            state.memory.clear()
            state.writes.clear()
        }
    }

    private fun cached(mangaId: String, generation: Long): MangaDetail? = synchronized(state.memoryLock) {
        if (generation != state.epoch.get()) return@synchronized null
        state.memory.remove(mangaId)?.also { state.memory[mangaId] = it }?.detail
    }

    private fun remember(detail: MangaDetail, bytes: Long, generation: Long) = synchronized(state.memoryLock) {
        if (generation != state.epoch.get()) return@synchronized
        state.memory.remove(detail.manga.id)
        if (bytes <= MAX_MEMORY_BYTES) state.memory[detail.manga.id] = CachedCatalog(detail, bytes)
        while (state.memory.size > MAX_MEMORY_ENTRIES || state.memory.values.sumOf { it.bytes } > MAX_MEMORY_BYTES) {
            state.memory.remove(state.memory.keys.first())
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

    private data class CachedCatalog(val detail: MangaDetail, val bytes: Long)
    private class CatalogState {
        val files = Mutex()
        val memoryLock = StorageLock()
        val epoch = AtomicLong()
        val sequence = AtomicLong()
        val memory = LinkedHashMap<String, CachedCatalog>()
        val writes = mutableMapOf<String, Long>()
    }

    private companion object {
        val statesLock = StorageLock()
        val states = mutableMapOf<String, CatalogState>()
        const val MAX_MEMORY_ENTRIES = 4
        const val MAX_MEMORY_BYTES = 8L * 1024 * 1024
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }
}
