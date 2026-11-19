package dev.mangaflow.anime.sources

import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.experience.contentCachePreferences
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.AtomicLong
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.cacheDir
import dev.mangaflow.storage.AuxiliaryCacheEntry
import dev.mangaflow.storage.AuxiliaryCacheFiles
import dev.mangaflow.storage.AuxiliaryDiskCacheOwner
import dev.mangaflow.storage.StorageCacheStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.atomicfu.locks.synchronized

internal data class AnimeCachedDocument(val text: String, val savedAt: Long)

/** Disposable metadata, included in the application's reviewed cache cleanup. */
internal class AnimeMetadataCache private constructor(private val context: PlatformContext) {
    private val directory = StorageFile(context.cacheDir, "anime-metadata")
    private val lock = Mutex()
    private val epoch = AtomicLong()
    private val memory = LinkedHashMap<String, AnimeCachedDocument>()
    private val versions = mutableMapOf<String, Long>()
    private var revision = 0L
    private val files = AuxiliaryCacheFiles(directory) { Regex("[a-f0-9]{64}\\.json").matches(it) }
    private val policy get() = contentCachePreferences(context)
    val generation: Long get() = epoch.get()

    init {
        StorageCacheStore.get(context).registerAuxiliaryOwner(object : AuxiliaryDiskCacheOwner {
            override val ownerId = "anime-metadata"
            override suspend fun snapshot(): List<AuxiliaryCacheEntry> = withContext(Dispatchers.IO) {
                lock.withLock { files.snapshot { versions[it] ?: 0L } }
            }
            override suspend fun remove(expected: AuxiliaryCacheEntry, canRemove: () -> Boolean): Long? = withContext(Dispatchers.IO) {
                lock.withLock {
                    if (files.entry(expected.key, versions[expected.key] ?: 0L, verifyBytes = true) != expected)
                        return@withLock null
                    if (!canRemove()) return@withLock null
                    epoch.incrementAndGet()
                    memory.remove(expected.key)
                    versions.remove(expected.key)
                    files.removeReviewed(expected)
                }.also { if (it != null) StorageCacheStore.get(context).cacheWorkFinished() }
            }
        })
    }

    fun isFresh(document: AnimeCachedDocument, kind: ContentCacheKind): Boolean =
        isFresh(document.savedAt, kind)

    fun isFresh(savedAt: Long, kind: ContentCacheKind): Boolean =
        savedAt > 0L && wallTimeMillis() - savedAt in 0 until policy.freshMillis(kind)

    suspend fun read(key: String): AnimeCachedDocument? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!policy.enabled) { memory.clear(); return@withLock null }
            val name = name(key)
            memory.remove(name)?.takeIf(::usable)?.also { memory[name] = it }?.let { return@withLock it }
            if (!policy.storeOnDisk) return@withLock null
            val file = StorageAtomicFile(StorageFile(directory, name))
            if (!file.baseFile.exists() && !StorageFile(file.baseFile.path + ".bak").exists()) return@withLock null
            try {
                require(file.baseFile.length() <= MAX_DOCUMENT_BYTES)
                val text = file.openRead().use { stream ->
                    val chunks = mutableListOf<ByteArray>()
                    val buffer = ByteArray(16_384)
                    var total = 0
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(total.toLong() + count <= MAX_DOCUMENT_BYTES)
                        chunks += buffer.copyOf(count)
                        total += count
                    }
                    ByteArray(total).also { bytes ->
                        var offset = 0
                        chunks.forEach { chunk -> chunk.copyInto(bytes, offset); offset += chunk.size }
                    }.decodeToString()
                }
                val json = JSONObject(text)
                require(json.getString("key") == key && json.getInt("version") == 1)
                AnimeCachedDocument(json.getString("document"), json.getLong("savedAt")).takeIf(::usable)
                    ?.also { remember(name, it) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }

    suspend fun write(key: String, text: String, requestedGeneration: Long) = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!policy.enabled || requestedGeneration != generation) return@withLock
            val name = name(key)
            val document = AnimeCachedDocument(text, wallTimeMillis())
            if (text.encodeToByteArray().size > MAX_DOCUMENT_BYTES) return@withLock
            remember(name, document)
            if (!policy.storeOnDisk) return@withLock
            val encoded = JSONObject().put("key", key).put("version", 1)
                .put("savedAt", document.savedAt).put("document", text).toString().encodeToByteArray()
            if (encoded.size > MAX_DOCUMENT_BYTES) return@withLock
            val file = StorageAtomicFile(StorageFile(directory, name))
            try {
                val output = file.startWrite()
                try { output.write(encoded); file.finishWrite(output); files.changed(name) }
                catch (failure: Exception) { file.failWrite(output); throw failure }
                trimDisk()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Metadata is usable even when its disposable copy cannot be saved. */ }
            finally { StorageCacheStore.get(context).cacheWorkFinished() }
        }
    }

    private fun usable(value: AnimeCachedDocument) = wallTimeMillis() - value.savedAt in 0..policy.retentionMillis

    private fun remember(name: String, value: AnimeCachedDocument) {
        memory.remove(name)
        memory[name] = value
        versions[name] = ++revision
        val maxBytes = ExperiencePolicyStore.get(context).state.value.runtime.catalogMemoryMiB * 1024L * 1024L
        while (memory.size > policy.snapshotMemoryEntries || memory.values.sumOf { it.text.length.toLong() * 2 } > maxBytes) {
            val eldest = memory.keys.firstOrNull() ?: break
            memory.remove(eldest)
            versions.remove(eldest)
        }
    }

    private fun trimDisk() {
        val snapshot = files.snapshot { versions[it] ?: 0L }.sortedBy { it.modifiedAt }
        var bytes = snapshot.sumOf { it.bytes }
        snapshot.forEach { entry ->
            val current = policy
            if (bytes > current.snapshotDiskMiB * 1024L * 1024L || ExperiencePolicyStore.get(context).state.value.runtime.automaticCleanup &&
                wallTimeMillis() - entry.modifiedAt > current.retentionMillis) {
                if (files.entry(entry.key, versions[entry.key] ?: 0L, verifyBytes = true) == entry) {
                    bytes -= files.removeReviewed(entry)
                }
            }
        }
    }

    private fun name(key: String) = sha256(key.encodeToByteArray()).joinToString("") {
        it.toUByte().toString(16).padStart(2, '0')
    } + ".json"

    companion object {
        private const val MAX_DOCUMENT_BYTES = 8L * 1024 * 1024
        private val instancesLock = StorageLock()
        private val instances = mutableMapOf<String, AnimeMetadataCache>()
        fun get(context: PlatformContext): AnimeMetadataCache = synchronized(instancesLock) {
            instances.getOrPut(context.cacheDir.path) { AnimeMetadataCache(context) }
        }
    }
}
