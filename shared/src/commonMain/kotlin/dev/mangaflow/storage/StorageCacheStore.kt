package dev.mangaflow.storage

import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.experience.ExperienceDiskCache
import dev.mangaflow.experience.readerPageCacheKey
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.*
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class CacheArea(val label: String) {
    ANIME("番剧缓冲"), READING("阅读图片"), ARTWORK("封面与头像"), DATA("资料与列表"), PANELS("分镜结果"),
}
internal enum class CacheBackend { COIL, SCRIPT, JM, PANEL, CATALOG, ANIME }

/** The player retains its own journal and open spans; the shared cache coordinator never deletes its files. */
internal data class AnimeDiskCacheUsage(val bytes: Long, val revision: Long, val active: Boolean, val accessedAt: Long)
internal interface AnimeDiskCacheOwner {
    fun snapshot(): AnimeDiskCacheUsage
    fun resize(bytes: Long)
    /** Null means the reviewed revision changed or playback still owns the cache. */
    fun clear(expectedRevision: Long): Long?
}

/** Exact cache identities only. No cookies, request headers, source code or account records. */
internal data class CacheResource(
    val id: String, val backend: CacheBackend, val key: String,
    val area: CacheArea, val mangaId: String = "", val chapterId: String = "", val sourceId: String = "",
    val pageIndex: Int = -1, val accessedAt: Long = 0L, val used: Boolean = false,
)
internal data class StoredCacheItem(val resource: CacheResource, val bytes: Long, val modifiedAt: Long,
    val protected: Boolean = false)
internal data class StorageCacheSnapshot(val items: List<StoredCacheItem>, val coilUnclassifiedBytes: Long,
    val auxiliaryBytes: Long, val catalogBytes: Long, val catalogReclaimableBytes: Long,
    val indexBytes: Long, val temporaryBytes: Long, val budgetBytes: Long, val pending: Boolean, val legacyReclaimable: Boolean) {
    val totalBytes: Long get() = items.sumOf { it.bytes } + coilUnclassifiedBytes + auxiliaryBytes + catalogBytes + indexBytes + temporaryBytes
    val reclaimableBytes: Long get() = items.filterNot { it.protected }.sumOf { it.bytes } +
        (if (legacyReclaimable) coilUnclassifiedBytes else 0L) + auxiliaryBytes + catalogReclaimableBytes
    fun areaBytes(area: CacheArea): Long = items.filter { it.resource.area == area }.sumOf { it.bytes } +
        if (area == CacheArea.DATA) auxiliaryBytes + catalogBytes else 0L
}
internal data class CacheClearResult(val freedBytes: Long, val skipped: Int, val failed: Int, val removedKeys: Set<String>)

/** One app-wide disposable budget. Offline files and optional components never enter this store. */
class StorageCacheStore private constructor(context: PlatformContext) {
    private val app = applicationContext(context)
    private val root = cacheDirectory(app)
    private val index = StorageAtomicFile(StorageFile(root, "storage-cache-index.json"))
    private val lock = StorageLock()
    private val mutation = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ready = CompletableDeferred<Unit>()
    private val records = linkedMapOf<String, CacheResource>()
    private val leases = mutableMapOf<String, Int>()
    private val memoryKeys = mutableMapOf<String, MutableSet<String>>()
    private val windows = mutableMapOf<Long, Pair<String, String>>()
    private val windowKeys = mutableMapOf<Long, MutableSet<String>>()
    private val windowOwners = mutableMapOf<Long, String>()
    private var nextWindow = 0L
    private var maintenance: Job? = null
    private var maintenanceRequested = false
    private var coil: ExperienceDiskCache? = null
    private var animeCache: AnimeDiskCacheOwner? = null
    private var budget = 512L * MIB
    private var budgetVersion = -1L
    private var cachedBytes = 0L
    private var error = false
    private var revision = 0L
    private var retentionMillis = 0L
    private var retentionVersion = -1L
    private var lastMaintenanceTick = 0L

    init {
        scope.launch {
            try {
                val base = index.baseFile
                if (base.isFile && base.length() in 1..MAX_INDEX_BYTES) {
                    val json = JSONObject(index.openRead().use { it.bufferedReader().readText() })
                    if (json.optInt("version") == 1) {
                        val values = json.getJSONArray("entries")
                        require(values.length() <= MAX_ENTRIES)
                        val loaded = (0 until values.length()).mapNotNull { i -> runCatching {
                            val row = values.getJSONObject(i)
                            val backend = CacheBackend.valueOf(row.getString("backend"))
                            val key = row.getString("key")
                            require(key.length in 1..16_384)
                            CacheResource(identity(backend, key), backend, key, CacheArea.valueOf(row.getString("area")),
                                row.optString("manga"), row.optString("chapter"), row.optString("source"),
                                row.optInt("page", -1), row.optLong("access"), row.optBoolean("used"))
                        }.getOrNull() }
                        synchronized(lock) { loaded.forEach { if (it.id !in records) records[it.id] = it } }
                    }
                }
            } catch (_: Exception) { error = true }
            finally { ready.complete(Unit) }
        }
    }

    internal val measuredBytes: Long get() = synchronized(lock) { cachedBytes }
    internal fun cacheWorkFinished() = requestMaintenance()
    internal fun setRetention(hours: Int, version: Long) {
        val changed = synchronized(lock) {
            if (version < retentionVersion) return
            retentionVersion = version
            val next = hours.coerceAtLeast(0) * 3_600_000L
            (next != retentionMillis).also { retentionMillis = next }
        }
        if (changed) requestMaintenance()
    }
    /** The visible application's existing sampler checks age at most once per minute. */
    internal fun maintenanceTick() {
        val due = synchronized(lock) {
            val now = wallTimeMillis()
            (retentionMillis > 0L && (now - lastMaintenanceTick !in 0L until 60_000L)).also {
                if (it) lastMaintenanceTick = now
            }
        }
        if (due) requestMaintenance()
    }
    internal fun attachAnimeCache(owner: AnimeDiskCacheOwner) {
        val target = synchronized(lock) { animeCache = owner; budget }
        owner.resize(target)
        requestMaintenance()
    }

    internal fun attach(cache: ExperienceDiskCache) { synchronized(lock) { coil = cache }; requestMaintenance() }
    internal fun setBudget(bytes: Long, version: Long) {
        synchronized(lock) {
            if (version < budgetVersion) return
            budgetVersion = version
            budget = bytes.coerceAtLeast(0L)
        }
        requestMaintenance()
    }

    internal suspend fun registerPages(mangaId: String, chapterId: String, source: String, pages: List<Page>, headers: Map<String, String>) = withContext(Dispatchers.Default) {
        val keys = pages.filter { it.url.startsWith("https:") || it.url.startsWith("http:") }.map { page ->
            val key = readerPageCacheKey(page, headers, source)
            Triple(page.index, key, identity(CacheBackend.COIL, key))
        }
        synchronized(lock) {
            keys.forEach { (pageIndex, key, id) ->
                windows.filterValues { it.second == chapterId }.keys.forEach { token ->
                    windowKeys.getOrPut(token) { mutableSetOf() }.add(id)
                }
                val old = records[id]
                records[id] = CacheResource(id, CacheBackend.COIL, key, CacheArea.READING,
                    mangaId, chapterId, source, pageIndex, old?.accessedAt ?: wallTimeMillis(), old?.used ?: false)
            }
            revision++
        }
        requestMaintenance()
    }

    internal fun noteCoil(key: String, used: Boolean) {
        synchronized(lock) {
            val id = identity(CacheBackend.COIL, key)
            val old = records[id] ?: CacheResource(id, CacheBackend.COIL, key, CacheArea.ARTWORK)
            records[id] = old.copy(accessedAt = wallTimeMillis(), used = old.used || used)
            revision++
        }
        requestMaintenance()
    }

    internal fun noteCatalog(mangaId: String) {
        synchronized(lock) {
            val id = identity(CacheBackend.CATALOG, mangaId)
            records[id] = CacheResource(id, CacheBackend.CATALOG, mangaId, CacheArea.DATA, mangaId = mangaId,
                accessedAt = wallTimeMillis(), used = true)
        }
        requestMaintenance()
    }
    internal fun catalogAccess(mangaId: String): Long = synchronized(lock) {
        records[identity(CacheBackend.CATALOG, mangaId)]?.accessedAt ?: 0L
    }

    internal fun noteMemoryFile(url: String, memoryKey: String) {
        val file = dev.mangaflow.reader.readerFilePath(url)?.let(::StorageFile) ?: return
        val backend = when (file.parentFile?.nativePath) {
            StorageFile(root, "script-decoded").nativePath -> CacheBackend.SCRIPT
            StorageFile(root, "jm-decoded").nativePath -> CacheBackend.JM
            else -> return
        }
        synchronized(lock) { memoryKeys.getOrPut(identity(backend, file.name)) { mutableSetOf() }.add(memoryKey) }
    }

    /** Registered before network/write starts; release after the actual file consumer finishes. */
    internal fun leaseFile(backend: CacheBackend, file: StorageFile, mangaId: String = "", chapterId: String = "",
        source: String = "", pageIndex: Int = -1, used: Boolean = true): () -> Unit {
        val key = file.name
        val id = identity(backend, key)
        synchronized(lock) {
            val old = records[id]
            records[id] = CacheResource(id, backend, key, if (backend == CacheBackend.PANEL) CacheArea.PANELS else CacheArea.READING,
                mangaId.ifBlank { old?.mangaId.orEmpty() }, chapterId.ifBlank { old?.chapterId.orEmpty() },
                source.ifBlank { old?.sourceId.orEmpty() }, pageIndex, wallTimeMillis(), used || old?.used == true)
            leases[id] = (leases[id] ?: 0) + 1
            revision++
        }
        var released = false
        return {
            synchronized(lock) {
                if (!released) {
                    released = true
                    val left = (leases[id] ?: 1) - 1
                    if (left == 0) leases.remove(id) else leases[id] = left
                }
            }
            requestMaintenance()
        }
    }

    internal fun protectReading(mangaId: String, chapterId: String, owner: String = mangaId): () -> Unit {
        val token = synchronized(lock) { (++nextWindow).also { token ->
            windows[token] = mangaId to chapterId
            windowOwners[token] = owner
            windowKeys[token] = records.values.filter { it.chapterId == chapterId }.map { it.id }.toMutableSet()
        } }
        return { synchronized(lock) { windows.remove(token); windowKeys.remove(token); windowOwners.remove(token) }; requestMaintenance() }
    }

    internal fun protectedCatalog(mangaId: String): Boolean {
        if (mangaId.startsWith("local:")) return true
        val downloads = DownloadStore.get(app)
        if (downloads.loading.value) return true
        return downloads.entries.value.any { it.manga.id == mangaId || chapterSourceMangaId(it.manga.id, it.chapter) == mangaId } ||
            synchronized(lock) { windows.values.any { it.first == mangaId } || mangaId in windowOwners.values }
    }

    private fun protected(resource: CacheResource, now: Long): Boolean {
        if ((leases[resource.id] ?: 0) > 0 || now - resource.accessedAt in 0L..FILE_HANDOFF_MILLIS) return true
        if (windowKeys.values.any { resource.id in it } || windows.values.any { (manga, chapter) -> chapter == resource.chapterId ||
                resource.chapterId.isBlank() && manga == resource.mangaId }) return true
        val downloads = DownloadStore.get(app)
        if (downloads.loading.value) return true
        return resource.chapterId.isNotBlank() && downloads.entries.value.any {
            it.chapter.id == resource.chapterId && it.status != DownloadStatus.COMPLETED
        }
    }

    private fun fileFor(resource: CacheResource): StorageFile? {
        val folder = when (resource.backend) {
            CacheBackend.SCRIPT -> "script-decoded"
            CacheBackend.JM -> "jm-decoded"
            CacheBackend.PANEL -> "reader-panels"
            else -> return null
        }
        val extension = when (resource.backend) { CacheBackend.SCRIPT -> "img"; CacheBackend.JM -> "jpg"; else -> "json" }
        if (!resource.key.matches(Regex("[a-f0-9]{64}\\.$extension"))) return null
        return StorageFile(StorageFile(root, folder), resource.key)
    }

    internal suspend fun snapshot(): StorageCacheSnapshot = withContext(Dispatchers.IO) {
        ready.await()
        mutation.withLock { scan() }
    }

    private suspend fun scan(): StorageCacheSnapshot {
        currentCoroutineContext().ensureActive()
        val disk = synchronized(lock) { coil }
        val saved = synchronized(lock) { records.values.toList() }
        val items = mutableListOf<StoredCacheItem>()
        val now = wallTimeMillis()
        val videoOwner = synchronized(lock) { animeCache }
        val video = videoOwner?.snapshot()
        val videoBytes = video?.bytes ?: ownedVideoBytes(StorageFile(root, "anime-stream-v1"))
        if (videoBytes > 0L) {
            val resource = CacheResource("anime:stream", CacheBackend.ANIME, "anime-stream-v1",
                CacheArea.ANIME, accessedAt = video?.accessedAt ?: 0L, used = true)
            // modifiedAt is an owner revision for this aggregate, never a guessed file timestamp.
            items += StoredCacheItem(resource, videoBytes, video?.revision ?: -1L,
                protected = video == null || video.active)
        }
        val present = mutableSetOf<String>()
        val imageFiles = StorageFile(root, "image-cache").listFiles().orEmpty()
        val imageNames = imageFiles.map { it.name }.toSet()
        // Coil's hashed filenames are observed for size only. Mutations always use its public owner.
        saved.filter { it.backend == CacheBackend.COIL }.forEach { resource ->
            val hash = resource.id.substringAfter(':')
            if ("$hash.1" !in imageNames) return@forEach
            val dir = disk?.directory ?: StorageFile(root, "image-cache").nativePath
            val data = StorageFile(dir / "$hash.1")
            val meta = StorageFile(dir / "$hash.0")
            if (data.isFile) {
                val bytes = data.length() + meta.length()
                present += resource.id
                items += StoredCacheItem(resource, bytes, data.lastModified(), synchronized(lock) { protected(resource, now) })
            }
        }
        listOf(CacheBackend.SCRIPT, CacheBackend.JM, CacheBackend.PANEL).forEach { backend ->
            val folder = when (backend) { CacheBackend.SCRIPT -> "script-decoded"; CacheBackend.JM -> "jm-decoded"; else -> "reader-panels" }
            StorageFile(root, folder).listFiles().orEmpty().forEach { file ->
                val id = identity(backend, file.name)
                val resource = synchronized(lock) { records[id] } ?: CacheResource(id, backend, file.name,
                    if (backend == CacheBackend.PANEL) CacheArea.PANELS else CacheArea.READING)
                if (fileFor(resource) == null || !file.isFile) return@forEach
                present += id
                items += StoredCacheItem(resource, file.length(), file.lastModified(), synchronized(lock) { protected(resource, now) })
            }
        }
        val diskSize = imageFiles
            .filter { it.name.matches(Regex("[a-f0-9]{64}\\.[01]")) }.sumOf { it.length() }
        val unknown = (diskSize - items.filter { it.resource.backend == CacheBackend.COIL }.sumOf { it.bytes }).coerceAtLeast(0L)
        val catalogs = ReaderCatalogStore(app).cacheUsage(::protectedCatalog)
        val auxiliary = listOf("browse-cache", "metadata-cache", "site-rankings").sumOf { folder ->
            StorageFile(root, folder).listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L }
        }
        val total = StorageCacheSnapshot(items, unknown, auxiliary, catalogs.first, catalogs.second,
            index.baseFile.length(), imageFiles.filterNot { it.name.matches(Regex("[a-f0-9]{64}\\.[01]")) }.sumOf { it.length() } +
                listOf("script-decoded", "jm-decoded", "reader-panels").sumOf { folder ->
                    StorageFile(root, folder).listFiles().orEmpty().filter { file ->
                        !file.name.matches(Regex("[a-f0-9]{64}\\.(img|jpg|json)"))
                    }.sumOf { it.length() }
                }, synchronized(lock) { budget }, error || disk?.resizePending == true || (video == null && videoBytes > 0L),
            synchronized(lock) { windows.isEmpty() && leases.isEmpty() } && !DownloadStore.get(app).loading.value &&
                DownloadStore.get(app).entries.value.none { it.status != DownloadStatus.COMPLETED } && items.none { it.resource.backend == CacheBackend.COIL && it.protected })
        synchronized(lock) {
            cachedBytes = total.totalBytes
            records.values.filter { it.backend != CacheBackend.CATALOG && it.id !in present &&
                now - it.accessedAt > 30L * 60_000L && (leases[it.id] ?: 0) == 0 &&
                windowKeys.values.none { keys -> it.id in keys } }.map { it.id }.forEach {
                records.remove(it); memoryKeys.remove(it)
            }
            // Keep recent directory registrations; do not grow a second unbounded history database.
            val oldest = records.values.filter { it.id !in present && (leases[it.id] ?: 0) == 0 }
                .sortedBy { it.accessedAt }.take((records.size - MAX_ENTRIES).coerceAtLeast(0))
            oldest.forEach { records.remove(it.id); memoryKeys.remove(it.id) }
        }
        return total
    }

    /** Clear exactly the reviewed snapshot; new or changed entries are not silently swept in. */
    internal suspend fun clear(items: List<StoredCacheItem>, clearLegacy: Boolean = false): CacheClearResult = withContext(Dispatchers.IO) {
        ready.await()
        mutation.withLock {
            var freed = 0L; var skipped = 0; var failed = 0
            val removedKeys = mutableSetOf<String>()
            for (item in items) {
                currentCoroutineContext().ensureActive()
                val resource = item.resource
                var removedBytes = item.bytes
                val removed = if (resource.backend == CacheBackend.ANIME) {
                    val owner = synchronized(lock) { animeCache }
                    val actual = if (item.protected) null else owner?.clear(item.modifiedAt)
                    if (actual != null) removedBytes = actual
                    actual != null
                } else if (resource.backend == CacheBackend.COIL) {
                    val disk = synchronized(lock) { coil }
                    disk?.reclaim(resource.key, item.bytes, item.modifiedAt) {
                        synchronized(lock) { !protected(resource, wallTimeMillis()) &&
                            (records[resource.id]?.accessedAt ?: resource.accessedAt) == resource.accessedAt }
                    } ?: false
                } else synchronized(lock) {
                    val latest = records[resource.id] ?: resource
                    val file = fileFor(resource)
                    if (protected(latest, wallTimeMillis()) || latest.accessedAt != resource.accessedAt || file == null ||
                        file.lastModified() != item.modifiedAt || file.length() != item.bytes) false
                    else file.delete().also { if (!it && file.exists()) failed++ }
                }
                if (removed) {
                    freed += removedBytes
                    removedKeys += resource.key
                    synchronized(lock) {
                        records.remove(resource.id)
                        removedKeys += memoryKeys.remove(resource.id).orEmpty()
                        revision++
                    }
                }
                else skipped++
            }
            if (clearLegacy) {
                val disk = synchronized(lock) { coil }
                // Legacy entries have no reliable owner. Only a fully idle image cache can be cleared.
                val remaining = scan()
                val version = synchronized(lock) { revision }
                if (disk != null && remaining.items.none { it.resource.backend == CacheBackend.COIL } &&
                    synchronized(lock) { windows.isEmpty() && leases.isEmpty() } &&
                    !DownloadStore.get(app).loading.value && DownloadStore.get(app).entries.value.none { it.status != DownloadStatus.COMPLETED }) {
                    freed += disk.clearWhenIdle { synchronized(lock) { windows.isEmpty() && leases.isEmpty() && revision == version } }
                } else skipped++
            }
            scan()
            persist()
            CacheClearResult(freed, skipped, failed, removedKeys)
        }
    }

    private fun requestMaintenance() {
        synchronized(lock) {
            maintenanceRequested = true
            if (maintenance?.isActive == true) return
            maintenance = scope.launch {
                delay(1_500)
                synchronized(lock) { maintenanceRequested = false }
                var retryAfterGrace = false
                try {
                    ready.await()
                    val snapshot = snapshot()
                    val limit = snapshot.budgetBytes
                    val age = synchronized(lock) { retentionMillis }
                    val now = wallTimeMillis()
                    val expired = if (age <= 0L) emptyList() else snapshot.items.filterNot { it.protected }.filter { item ->
                        item.resource.area == CacheArea.READING || item.resource.area == CacheArea.ARTWORK
                    }.filter { item ->
                        val last = item.resource.accessedAt.takeIf { it > 0L } ?: item.modifiedAt
                        last > 0L && now - last >= age
                    }
                    if (expired.isNotEmpty()) clear(expired)
                    val afterExpiry = if (expired.isEmpty()) snapshot else snapshot()
                    var excess = (afterExpiry.totalBytes - limit).coerceAtLeast(0L)
                    if (excess > 0L) {
                        val ordered = afterExpiry.items.filterNot { it.protected }.sortedWith(
                            compareBy<StoredCacheItem> { if (!it.resource.used) 0 else 1 }
                                .thenBy { it.resource.accessedAt.takeIf { at -> at > 0L } ?: it.modifiedAt })
                        val selected = ordered.takeWhile { item -> (excess > 0L).also { excess -= item.bytes } }
                        clear(selected, clearLegacy = excess > 0L && afterExpiry.legacyReclaimable)
                    }
                    ReaderCatalogStore(app).trimToSize((limit / 16).coerceAtMost(32L * MIB), ::protectedCatalog)
                    val beforeVideo = snapshot()
                    val videoBytes = beforeVideo.items.filter { it.resource.backend == CacheBackend.ANIME }.sumOf { it.bytes }
                    synchronized(lock) { animeCache }?.resize(
                        (limit - (beforeVideo.totalBytes - videoBytes)).coerceAtLeast(0L))
                    val current = snapshot()
                    retryAfterGrace = current.totalBytes > current.budgetBytes &&
                        current.items.any { it.protected && wallTimeMillis() - it.resource.accessedAt in 0L..FILE_HANDOFF_MILLIS }
                    val other = current.totalBytes - current.items.filter { it.resource.backend == CacheBackend.COIL }.sumOf { it.bytes } - current.coilUnclassifiedBytes
                    val disk = synchronized(lock) { coil }
                    disk?.resize((limit - other).coerceAtLeast(0L), synchronized(lock) { budgetVersion })
                    mutation.withLock { persist() }
                    error = false
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { error = true }
                finally {
                    val again = synchronized(lock) { maintenance = null; maintenanceRequested }
                    if (again || retryAfterGrace) {
                        // One bounded follow-up when handoff protection expires; no busy spin.
                        scope.launch { delay(if (retryAfterGrace) FILE_HANDOFF_MILLIS + 100L else 1_500L); requestMaintenance() }
                    }
                }
            }
        }
    }

    /** Cold caches are measured but protected until their native journal owner is attached. */
    private fun ownedVideoBytes(directory: StorageFile): Long =
        directory.listFiles().orEmpty().sumOf { file ->
            if (file.isDirectory) ownedVideoBytes(file) else if (file.isFile) file.length() else 0L
        }

    private fun persist() {
        var rows = synchronized(lock) { records.values.toList().takeLast(MAX_ENTRIES) }
        fun encode() = JSONObject().put("version", 1).put("entries", JSONArray(rows.map { row ->
            JSONObject().put("backend", row.backend.name).put("key", row.key).put("area", row.area.name)
                .put("manga", row.mangaId).put("chapter", row.chapterId).put("source", row.sourceId)
                .put("page", row.pageIndex).put("access", row.accessedAt).put("used", row.used)
        })).toString().encodeToByteArray()
        var bytes = encode()
        while (bytes.size > MAX_INDEX_BYTES && rows.isNotEmpty()) {
            rows = rows.drop(maxOf(1, rows.size / 4))
            bytes = encode()
        }
        val output = index.startWrite()
        try { output.write(bytes); index.finishWrite(output) }
        catch (failure: Exception) { index.failWrite(output); throw failure }
    }

    companion object {
        private const val MIB = 1024L * 1024
        private const val MAX_ENTRIES = 20_000
        private const val MAX_INDEX_BYTES = 24L * MIB
        private const val FILE_HANDOFF_MILLIS = 30_000L
        private val instancesLock = StorageLock()
        private val instances = mutableMapOf<String, StorageCacheStore>()
        fun get(context: PlatformContext): StorageCacheStore = synchronized(instancesLock) {
            instances.getOrPut(cacheDirectory(context).path) { StorageCacheStore(context) }
        }
        private fun hash(value: String) = sha256(value.encodeToByteArray()).joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
        private fun identity(backend: CacheBackend, key: String) = backend.name + ":" + hash(key)
    }
}
