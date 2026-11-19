package dev.mangaflow.storage

import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.experience.ExperienceDiskCache
import dev.mangaflow.experience.ExperienceRuntimePreferences
import dev.mangaflow.experience.CacheEvictionOrder
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
    ANIME("番剧缓冲"), READING("阅读图片"), ARTWORK("封面与头像"), DATA("资料与列表"), PANELS("分镜结果"), ENHANCEMENT("增强结果"),
}
internal enum class CacheBackend { COIL, SCRIPT, JM, PANEL, CATALOG, ANIME, AUXILIARY }

/** The player retains its own journal and open spans; the shared cache coordinator never deletes its files. */
internal data class AnimeDiskCacheUsage(val bytes: Long, val revision: Long, val active: Boolean, val accessedAt: Long)
internal interface AnimeDiskCacheOwner {
    fun snapshot(): AnimeDiskCacheUsage
    fun resize(bytes: Long, canResize: () -> Boolean = { true },
        commitResize: ((() -> Unit) -> Boolean) = { publish -> publish(); true })
    /** Null means the reviewed revision changed or playback still owns the cache. */
    fun clear(expectedRevision: Long, canClear: () -> Boolean = { true }): Long?
}

/** Exact cache identities only. No cookies, request headers, source code or account records. */
internal data class CacheResource(
    val id: String, val backend: CacheBackend, val key: String,
    val area: CacheArea, val mangaId: String = "", val chapterId: String = "", val sourceId: String = "",
    val pageIndex: Int = -1, val accessedAt: Long = 0L, val used: Boolean = false,
    val auxiliaryOwnerId: String = "",
)
internal data class StoredCacheItem(val resource: CacheResource, val bytes: Long, val modifiedAt: Long,
    val protected: Boolean = false, val auxiliaryEntry: AuxiliaryCacheEntry? = null)
internal data class StorageCacheSnapshot(val items: List<StoredCacheItem>, val coilUnclassifiedBytes: Long,
    val auxiliaryBytes: Long, val catalogBytes: Long, val catalogReclaimableBytes: Long,
    val indexBytes: Long, val temporaryBytes: Long, val budgetBytes: Long, val pending: Boolean, val legacyReclaimable: Boolean,
    val legacyRevision: Long, val legacyStamp: String) {
    val totalBytes: Long get() = items.sumOf { it.bytes } + coilUnclassifiedBytes + auxiliaryBytes + catalogBytes + indexBytes + temporaryBytes
    val reclaimableBytes: Long get() = items.filterNot { it.protected }.sumOf { it.bytes } +
        (if (legacyReclaimable) coilUnclassifiedBytes else 0L) + catalogReclaimableBytes
    fun areaBytes(area: CacheArea): Long = items.filter { it.resource.area == area }.sumOf { it.bytes } +
        if (area == CacheArea.DATA) auxiliaryBytes + catalogBytes else 0L
}
internal data class CacheClearResult(val freedBytes: Long, val skipped: Int, val failed: Int, val removedKeys: Set<String>)

internal enum class CacheMaintenanceTrigger { CAPACITY, EXPIRY }

/** An automatic sweep must not keep old candidates after any relevant setting changes. */
internal data class CacheMaintenanceFence(
    val policy: ExperienceRuntimePreferences, val limit: Long, val ageMillis: Long,
    val runtimeVersion: Long, val budgetVersion: Long, val retentionVersion: Long,
    val trigger: CacheMaintenanceTrigger = CacheMaintenanceTrigger.CAPACITY,
)

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
    private val rangeManifestReady = mutableSetOf<Long>()
    private val rangeOwners = mutableMapOf<Long, Any>()
    private val readingRanges = mutableMapOf<Long, Set<Int>>()
    private val windowPages = mutableMapOf<Long, MutableMap<String, MutableSet<Int>>>()
    private val windowSeen = mutableMapOf<Long, MutableSet<String>>()
    private val retiredRangeKeys = mutableSetOf<String>()
    private var memoryRangeCleanup = false
    private var diskRangeCleanup = false
    private var rangeVersion = -1L
    private val detachedMemory = mutableMapOf<String, Pair<CacheResource, Set<String>>>()
    private var reclaimMemory: ((Set<String>) -> Set<String>)? = null
    private var snapshotMemoryKeys: (() -> Set<String>)? = null
    private var nextWindow = 0L
    private var memoryRangeMaintenance: Job? = null
    private var memoryRangeRequested = false
    private var maintenance: Job? = null
    private var maintenanceRequested = false
    private var coil: ExperienceDiskCache? = null
    private var animeCache: AnimeDiskCacheOwner? = null
    private val auxiliaryOwners = linkedMapOf<String, AuxiliaryDiskCacheOwner>()
    private val catalogStore by lazy { ReaderCatalogStore(app) }
    private var budget = 512L * MIB
    private var budgetVersion = -1L
    private var cachedBytes = 0L
    private var sampledSnapshot: StorageCacheSnapshot? = null
    private var sampledAt = 0L
    private var runtime = ExperienceRuntimePreferences()
    private var runtimeVersion = -1L
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
    internal val measuredAtMillis: Long? get() = synchronized(lock) { sampledAt.takeIf { it > 0L } }
    internal fun setRuntime(value: ExperienceRuntimePreferences, version: Long) {
        val changed = synchronized(lock) {
            if (version < runtimeVersion) return
            runtimeVersion = version
            (runtime != value).also { runtime = value }
        }
        if (changed) requestMaintenance()
    }

    /** One shared local scan; readers/settings cannot queue duplicate directory walks. */
    internal suspend fun sample(maxAgeMillis: Long): StorageCacheSnapshot = withContext(Dispatchers.IO) {
        ready.await()
        mutation.withLock {
            val cached = synchronized(lock) {
                sampledSnapshot?.takeIf { wallTimeMillis() - sampledAt in 0L until maxAgeMillis }
            }
            cached ?: scan()
        }
    }
    internal fun cacheWorkFinished() = requestMaintenance()
    internal fun setRangeCleanup(memory: Boolean, disk: Boolean, version: Long) {
        synchronized(lock) {
            if (version < rangeVersion) return
            rangeVersion = version
            memoryRangeCleanup = memory; diskRangeCleanup = disk
        }
        requestMaintenance()
    }
    internal fun attachMemoryReclaimer(reclaimer: (Set<String>) -> Set<String>, snapshotKeys: () -> Set<String>) {
        synchronized(lock) { reclaimMemory = reclaimer; snapshotMemoryKeys = snapshotKeys }
        requestMaintenance()
    }
    /** Empty/uninitialized windows keep the chapter protected; a published range is exact. */
    internal fun updateReadingRange(owner: Any, pages: Set<Int>) {
        if (pages.isEmpty()) return
        val changed = synchronized(lock) {
            var changed = false
            rangeOwners.filterValues { it === owner }.keys.forEach { token ->
                if (readingRanges[token] != pages) { readingRanges[token] = pages.toSet(); changed = true }
                windowPages[token].orEmpty().filterValues { indices -> indices.any { it in pages } }.keys
                    .let { windowSeen.getOrPut(token) { mutableSetOf() }.addAll(it) }
            }
            if (changed) revision++
            changed
        }
        if (changed) {
            val needsDiskSweep = synchronized(lock) {
                diskRangeCleanup || cachedBytes >= budget
            }
            // Releasing an exact window can make space reclaimable even if no new
            // transfer is admitted. Range cleanup being off must not strand capacity.
            if (needsDiskSweep) requestMaintenance() else requestMemoryRangeMaintenance()
        }
    }
    /** Resolution/transfer evidence is bound to the exact live reader, not a chapter label. */
    internal fun registerReadingResource(owner: Any, pageIndex: Int, page: Page, diskKey: String?) {
        val id = if (page.url.startsWith("file:")) {
            val file = dev.mangaflow.reader.readerFilePath(page.url)?.let(::StorageFile) ?: return
            val backend = when (file.parentFile?.nativePath) {
                StorageFile(root, "script-decoded").nativePath -> CacheBackend.SCRIPT
                StorageFile(root, "jm-decoded").nativePath -> CacheBackend.JM
                else -> return // Offline/imported files never gain disposable authority.
            }
            identity(backend, file.name)
        } else diskKey?.let { identity(CacheBackend.COIL, it) } ?: return
        synchronized(lock) {
            if (pageIndex < 0 || id !in records) return
            rangeOwners.filterValues { it === owner }.keys.forEach { token ->
                windowKeys.getOrPut(token) { mutableSetOf() }.add(id)
                windowPages.getOrPut(token) { mutableMapOf() }.getOrPut(id) { mutableSetOf() }.add(pageIndex)
                if (pageIndex in readingRanges[token].orEmpty()) windowSeen.getOrPut(token) { mutableSetOf() }.add(id)
            }
            revision++
        }
        requestMemoryRangeMaintenance()
    }
    private fun rangeCandidate(id: String): Boolean = id in retiredRangeKeys || windowPages.any { (token, resources) ->
        val range = readingRanges[token]
        range != null && id in windowSeen[token].orEmpty() && resources[id]?.let { indices -> indices.isNotEmpty() && indices.none { it in range } } == true
    }

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
            ((retentionMillis > 0L || memoryRangeCleanup || diskRangeCleanup) && (now - lastMaintenanceTick !in 0L until 60_000L)).also {
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

    internal fun registerAuxiliaryOwner(owner: AuxiliaryDiskCacheOwner) {
        synchronized(lock) { auxiliaryOwners[owner.ownerId] = owner; sampledSnapshot = null }
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

    internal suspend fun registerPages(mangaId: String, chapterId: String, source: String, pages: List<Page>, headers: Map<String, String>,
        rangeOwner: Any? = null) = withContext(Dispatchers.Default) {
        val keys = pages.filter { it.url.startsWith("https:") || it.url.startsWith("http:") }.map { page ->
            val key = readerPageCacheKey(page, headers, source)
            Triple(page.index, key, identity(CacheBackend.COIL, key))
        }
        synchronized(lock) {
            keys.forEach { (pageIndex, key, id) ->
                windows.filter { (token, owner) -> owner.second == chapterId &&
                    (owner.first == mangaId || windowOwners[token] == mangaId) }.keys.forEach { token ->
                    windowKeys.getOrPut(token) { mutableSetOf() }.add(id)
                    if (rangeOwner != null && rangeOwners[token] === rangeOwner) {
                        windowPages.getOrPut(token) { mutableMapOf() }.getOrPut(id) { mutableSetOf() }.add(pageIndex)
                        if (pageIndex in readingRanges[token].orEmpty()) windowSeen.getOrPut(token) { mutableSetOf() }.add(id)
                    }
                }
                val old = records[id]
                records[id] = CacheResource(id, CacheBackend.COIL, key, CacheArea.READING,
                    mangaId, chapterId, source, pageIndex, old?.accessedAt ?: wallTimeMillis(), old?.used ?: false)
            }
            windows.filter { (token, owner) -> owner.second == chapterId &&
                    (owner.first == mangaId || windowOwners[token] == mangaId) &&
                    rangeOwner != null && rangeOwners[token] === rangeOwner }.keys.forEach { rangeManifestReady += it }
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
            windows.filter { (token, identity) -> identity.second == chapterId &&
                (mangaId.isBlank() || identity.first == mangaId || windowOwners[token] == mangaId) }.keys.forEach { token ->
                windowKeys.getOrPut(token) { mutableSetOf() }.add(id)
            }
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

    internal fun protectReading(mangaId: String, chapterId: String, owner: String = mangaId,
        rangeOwner: Any? = null): () -> Unit {
        val token = synchronized(lock) { (++nextWindow).also { token ->
            windows[token] = mangaId to chapterId
            windowOwners[token] = owner
            val known = records.values.filter { it.chapterId == chapterId }
            windowKeys[token] = known.map { it.id }.toMutableSet()
            // Persisted metadata is descriptive, never deletion authority. Current manifest
            // registration and actual file leases supply the session's exact associations.
            windowPages[token] = mutableMapOf()
            if (rangeOwner != null) rangeOwners[token] = rangeOwner
        } }
        return { synchronized(lock) {
            if (token in rangeOwners) {
                retiredRangeKeys += windowSeen[token].orEmpty()
                // Overflow drops only historical authorization, never the cached image.
                while (retiredRangeKeys.size > MAX_ENTRIES) retiredRangeKeys.remove(retiredRangeKeys.first())
            }
            windows.remove(token); windowKeys.remove(token); windowOwners.remove(token)
            rangeOwners.remove(token); readingRanges.remove(token); windowPages.remove(token); windowSeen.remove(token); rangeManifestReady.remove(token)
            revision++
        }; requestMaintenance() }
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
        if (windows.any { (token, owner) ->
            val matches = resource.id in windowKeys[token].orEmpty() || owner.second == resource.chapterId ||
                resource.chapterId.isBlank() && owner.first == resource.mangaId
            if (!matches) false else {
                val range = readingRanges[token]
                val pages = windowPages[token]?.get(resource.id)
                // Protection is independent of the reclamation trigger: disabling
                // proactive range cleanup must not disable capacity/expiry fallback.
                // Unknown/aliased ownership remains protected rather than guessed.
                token !in rangeManifestReady || range == null || pages.isNullOrEmpty() || pages.any { it in range }
            }
        }) return true
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
        val scanRevision = synchronized(lock) { revision }
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
        val knownCoilHashes = items.filter { it.resource.backend == CacheBackend.COIL }
            .map { it.resource.id.substringAfter(':') }.toSet()
        val unknownFiles = imageFiles.filter { it.name.matches(Regex("[a-f0-9]{64}\\.[01]")) &&
            it.name.substringBeforeLast('.') !in knownCoilHashes }
        val legacyStamp = hash(unknownFiles.sortedBy { it.name }.joinToString("|") {
            "${it.name}:${it.length()}:${it.lastModified()}"
        })
        val unknown = (diskSize - items.filter { it.resource.backend == CacheBackend.COIL }.sumOf { it.bytes }).coerceAtLeast(0L)
        val catalogOwner = catalogStore.cacheOwner
        val owners = synchronized(lock) { auxiliaryOwners.values.toList() } + catalogOwner
        for (owner in owners) {
            for (entry in owner.snapshot()) {
                val catalogue = owner.ownerId == "catalog"
                val backend = if (catalogue) CacheBackend.CATALOG else CacheBackend.AUXILIARY
                val key = owner.ownerId + ":" + entry.key
                val resource = CacheResource(identity(backend, key), backend, key,
                    if (owner.ownerId in setOf("enhancement-v1", "enhanced-video-v1")) CacheArea.ENHANCEMENT else CacheArea.DATA,
                    mangaId = if (catalogue) entry.key else "", accessedAt = if (catalogue)
                        catalogAccess(entry.key).takeIf { it > 0L } ?: entry.modifiedAt else entry.modifiedAt,
                    used = true, auxiliaryOwnerId = owner.ownerId)
                items += StoredCacheItem(resource, entry.bytes, entry.modifiedAt,
                    protected = catalogue && (protectedCatalog(entry.key) || catalogStore.isWriting(entry.key)),
                    auxiliaryEntry = entry)
                present += resource.id
            }
        }
        val catalogBytes = (catalogStore.sizeBytes() - items.filter { it.resource.backend == CacheBackend.CATALOG }
            .sumOf { it.bytes }).coerceAtLeast(0L)
        // Cold/unrecognized owners are measured but never reported as safely reclaimable.
        val auxiliary = (listOf("browse-cache", "metadata-cache", "site-rankings", "anime-metadata").sumOf { folder ->
            StorageFile(root, folder).listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L }
        } - items.filter { it.resource.backend == CacheBackend.AUXILIARY }.sumOf { it.bytes }).coerceAtLeast(0L)
        val total = StorageCacheSnapshot(items, unknown, auxiliary, catalogBytes, 0L,
            index.baseFile.length(), imageFiles.filterNot { it.name.matches(Regex("[a-f0-9]{64}\\.[01]")) }.sumOf { it.length() } +
                listOf("script-decoded", "jm-decoded", "reader-panels").sumOf { folder ->
                    StorageFile(root, folder).listFiles().orEmpty().filter { file ->
                        !file.name.matches(Regex("[a-f0-9]{64}\\.(img|jpg|json)"))
                    }.sumOf { it.length() }
                }, synchronized(lock) { budget }, error || disk?.resizePending == true || (video == null && videoBytes > 0L),
            synchronized(lock) { windows.isEmpty() && leases.isEmpty() && revision == scanRevision } && !DownloadStore.get(app).loading.value &&
                DownloadStore.get(app).entries.value.none { it.status != DownloadStatus.COMPLETED } && items.none { it.resource.backend == CacheBackend.COIL && it.protected },
            scanRevision, legacyStamp)
        synchronized(lock) {
            cachedBytes = total.totalBytes
            sampledSnapshot = total
            sampledAt = now
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

    private fun maintenanceAllowed(fence: CacheMaintenanceFence?): Boolean = fence == null ||
        (fence.trigger != CacheMaintenanceTrigger.EXPIRY || runtime.automaticCleanup) &&
        runtimeVersion == fence.runtimeVersion && budgetVersion == fence.budgetVersion &&
        retentionVersion == fence.retentionVersion && runtime == fence.policy && budget == fence.limit &&
        retentionMillis == fence.ageMillis

    /** Clear exactly the reviewed snapshot; new or changed entries are not silently swept in. */
    internal suspend fun clear(items: List<StoredCacheItem>, clearLegacy: Boolean = false,
        legacyRevision: Long? = null, legacyStamp: String? = null, rangeOnly: Boolean = false,
        maintenanceFence: CacheMaintenanceFence? = null,
        removeMemory: ((Set<String>) -> Unit)? = null): CacheClearResult = withContext(Dispatchers.IO) {
        ready.await()
        mutation.withLock {
            var freed = 0L; var skipped = 0; var failed = 0
            var ownedMutations = 0L
            val removedKeys = mutableSetOf<String>()
            for (item in items) {
                currentCoroutineContext().ensureActive()
                if (!synchronized(lock) { maintenanceAllowed(maintenanceFence) }) { skipped++; continue }
                val resource = item.resource
                var removedBytes = item.bytes
                val failuresBefore = failed
                val removed = if (resource.backend == CacheBackend.AUXILIARY || resource.backend == CacheBackend.CATALOG) {
                    val owner = if (resource.auxiliaryOwnerId == "catalog") catalogStore.cacheOwner
                        else synchronized(lock) { auxiliaryOwners[resource.auxiliaryOwnerId] }
                    val expected = item.auxiliaryEntry
                    val actual = try { if (item.protected || expected == null) null else owner?.remove(expected) { synchronized(lock) { maintenanceAllowed(maintenanceFence) } } }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: AuxiliaryCacheRemovalException) { freed += failure.freedBytes; failed++; null }
                    catch (_: Exception) { failed++; null }
                    if (actual != null) removedBytes = actual
                    actual != null
                } else if (resource.backend == CacheBackend.ANIME) {
                    val owner = synchronized(lock) { animeCache }
                    val actual = if (item.protected) null else owner?.clear(item.modifiedAt) { synchronized(lock) { maintenanceAllowed(maintenanceFence) } }
                    if (actual != null) removedBytes = actual
                    actual != null
                } else if (resource.backend == CacheBackend.COIL) {
                    val disk = synchronized(lock) { coil }
                    disk?.reclaim(resource.key, item.bytes, item.modifiedAt) {
                        synchronized(lock) { maintenanceAllowed(maintenanceFence) && !protected(resource, wallTimeMillis()) &&
                            (!rangeOnly || diskRangeCleanup && rangeCandidate(resource.id)) &&
                            (records[resource.id]?.accessedAt ?: resource.accessedAt) == resource.accessedAt }
                    } ?: false
                } else synchronized(lock) {
                    val latest = records[resource.id] ?: resource
                    val file = fileFor(resource)
                    if (!maintenanceAllowed(maintenanceFence) || protected(latest, wallTimeMillis()) || rangeOnly && (!diskRangeCleanup || !rangeCandidate(resource.id)) || latest.accessedAt != resource.accessedAt || file == null ||
                        file.lastModified() != item.modifiedAt || file.length() != item.bytes) false
                    else file.delete().also { if (!it && file.exists()) failed++ }
                }
                if (removed) {
                    freed += removedBytes
                    removedKeys += resource.key
                    synchronized(lock) {
                        val aliases = memoryKeys[resource.id].orEmpty().toSet()
                        // Manual disk cleanup must not evict aliases retained by another live
                        // reader while the disk operation was in flight. Keep Store -> Memory
                        // lock order, matching range maintenance, and retain identity until checked.
                        if (removeMemory != null) {
                            val all = (records.values + detachedMemory.values.map { it.first } + resource)
                                .distinctBy { it.id }
                            fun keysOf(item: CacheResource) = setOf(item.key) + memoryKeys[item.id].orEmpty() +
                                detachedMemory[item.id]?.second.orEmpty()
                            val blocked = all.filter { protected(it, wallTimeMillis()) ||
                                it.id != resource.id && it.id !in retiredRangeKeys &&
                                    windowPages.values.none { pages -> it.id in pages }
                            }.flatMap(::keysOf).toMutableSet()
                            val known = all.map { it.id }.toSet()
                            memoryKeys.filterKeys { it !in known }.values.forEach { blocked += it }
                            fun variants(keys: Set<String>) = keys + keys.map { "manga-cover:$it" }
                            try { removeMemory(variants(aliases + resource.key) - variants(blocked)) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { failed++ }
                        }
                        memoryKeys.remove(resource.id)
                        if (rangeCandidate(resource.id)) rememberDetachedMemory(resource, aliases + resource.key)
                        records.remove(resource.id)
                        if (resource.id !in detachedMemory) retiredRangeKeys.remove(resource.id)
                        removedKeys += aliases
                        revision++
                        ownedMutations++
                    }
                }
                else if (failed == failuresBefore) skipped++
            }
            if (clearLegacy) {
                val disk = synchronized(lock) { coil }
                // Legacy entries have no reliable owner. Only a fully idle image cache can be cleared.
                val remaining = scan()
                val version = synchronized(lock) { revision }
                if (disk != null && legacyRevision != null && legacyStamp != null &&
                    version == legacyRevision + ownedMutations && remaining.legacyStamp == legacyStamp &&
                    remaining.items.none { it.resource.backend == CacheBackend.COIL } &&
                    synchronized(lock) { windows.isEmpty() && leases.isEmpty() } &&
                    !DownloadStore.get(app).loading.value && DownloadStore.get(app).entries.value.none { it.status != DownloadStatus.COMPLETED }) {
                    freed += disk.clearWhenIdle { synchronized(lock) { maintenanceAllowed(maintenanceFence) && windows.isEmpty() && leases.isEmpty() && revision == version } }
                } else skipped++
            }
            scan()
            persist()
            CacheClearResult(freed, skipped, failed, removedKeys)
        }
    }

    /** Memory-only window updates do not scan the disk directory on every page turn. */
    private fun requestMemoryRangeMaintenance() {
        synchronized(lock) {
            if (!memoryRangeCleanup) return
            memoryRangeRequested = true
            if (memoryRangeMaintenance?.isActive == true) return
            memoryRangeMaintenance = scope.launch {
                try {
                    delay(1_500)
                    ready.await()
                    while (true) {
                        synchronized(lock) { memoryRangeRequested = false }
                        val retry = reclaimRangeMemory()
                        if (!retry && synchronized(lock) { !memoryRangeRequested }) break
                        // One owner holds the delayed retry; page turns cannot multiply timers.
                        delay(if (retry) FILE_HANDOFF_MILLIS + 100L else 1_500L)
                    }
                } finally {
                    val again = synchronized(lock) { memoryRangeMaintenance = null; memoryRangeRequested }
                    if (again) requestMemoryRangeMaintenance()
                }
            }
        }
    }
    /** Keep exact identities across an off/on toggle, not an unbounded cache history. */
    private fun rememberDetachedMemory(resource: CacheResource, keys: Set<String>) {
        fun chars(item: Pair<CacheResource, Set<String>>): Long {
            val r = item.first
            return listOf(r.id, r.key, r.mangaId, r.chapterId, r.sourceId, r.auxiliaryOwnerId)
                .sumOf { it.length.toLong() } + item.second.sumOf { it.length.toLong() }
        }
        val existing = detachedMemory[resource.id]
        val retainedKeys = existing?.second.orEmpty() + keys
        val item = resource to retainedKeys
        val used = detachedMemory.values.sumOf(::chars) - (existing?.let(::chars) ?: 0L)
        val aliases = detachedMemory.values.sumOf { it.second.size } - (existing?.second?.size ?: 0)
        // If metadata cannot fit, fail closed: ordinary TTL/LRU still owns that reference.
        if (existing == null && detachedMemory.size >= MAX_DETACHED_ENTRIES ||
            retainedKeys.size > MAX_DETACHED_ALIASES_PER_ENTRY || aliases + retainedKeys.size > MAX_DETACHED_ALIASES ||
            used + chars(item) > MAX_DETACHED_CHARS) return
        detachedMemory[resource.id] = item
    }
    private fun pruneDetachedMemory() {
        // Missing/unavailable cache is not evidence that its references disappeared.
        val present = try { snapshotMemoryKeys?.invoke() } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { null }
        if (present != null) {
            val iterator = detachedMemory.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val retained = entry.value.second.filterTo(mutableSetOf()) { it in present }
                if (retained.isEmpty()) iterator.remove()
                else entry.setValue(entry.value.first to retained)
            }
        }
        retiredRangeKeys.removeAll { it !in records && it !in detachedMemory }
    }
    private fun reclaimRangeMemory(): Boolean = synchronized(lock) {
        pruneDetachedMemory()
        if (!memoryRangeCleanup) return@synchronized false
        val resources = (records.values + detachedMemory.values.map { it.first }).distinctBy { it.id }
        fun keysFor(resource: CacheResource): Set<String> = setOf(resource.key) + memoryKeys[resource.id].orEmpty() +
            detachedMemory[resource.id]?.second.orEmpty()
        val candidates = resources.filter { it.area == CacheArea.READING && rangeCandidate(it.id) }
        val protectedKeys = resources.filter { resource ->
            protected(resource, wallTimeMillis()) ||
                resource.id !in retiredRangeKeys && windowPages.values.none { resource.id in it }
        }.flatMap(::keysFor).toMutableSet()
        // An alias whose owner disappeared is unknown, not implicit deletion permission.
        val known = resources.map { it.id }.toSet()
        memoryKeys.filterKeys { it !in known }.values.forEach { protectedKeys += it }
        val keys = candidates.filterNot { protected(it, wallTimeMillis()) }
            .flatMap(::keysFor).toSet() - protectedKeys
        val remaining = reclaimMemory?.invoke(keys)
        if (remaining != null) detachedMemory.entries.removeAll { (_, item) -> item.second.none { it in remaining } }
        retiredRangeKeys.removeAll { it !in records && it !in detachedMemory }
        candidates.any { wallTimeMillis() - it.accessedAt in 0L..FILE_HANDOFF_MILLIS } ||
            remaining?.any { it in keys } == true
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
                    var snapshot = snapshot()
                    // Range reclamation is independent of age and capacity cleanup.
                    val ranged = synchronized(lock) { if (!memoryRangeCleanup && !diskRangeCleanup) emptyList()
                        else snapshot.items.filter { it.resource.area == CacheArea.READING && rangeCandidate(it.resource.id) } }
                    retryAfterGrace = reclaimRangeMemory() || synchronized(lock) {
                        ranged.any { wallTimeMillis() - it.resource.accessedAt in 0L..FILE_HANDOFF_MILLIS }
                    }
                    if (synchronized(lock) { diskRangeCleanup } && ranged.isNotEmpty()) {
                        clear(ranged, rangeOnly = true)
                        snapshot = snapshot()
                    }
                    val fence = synchronized(lock) { CacheMaintenanceFence(runtime, budget, retentionMillis,
                        runtimeVersion, budgetVersion, retentionVersion) }
                    val limit = fence.limit
                    val policy = fence.policy
                    val age = fence.ageMillis
                    val now = wallTimeMillis()
                    val expired = if (!policy.automaticCleanup || age <= 0L) emptyList() else snapshot.items.filterNot { it.protected }.filter { item ->
                        item.resource.area == CacheArea.READING || item.resource.area == CacheArea.ARTWORK
                    }.filter { item ->
                        val last = item.resource.accessedAt.takeIf { it > 0L } ?: item.modifiedAt
                        last > 0L && now - last >= age
                    }
                    if (expired.isNotEmpty()) clear(expired, maintenanceFence = fence.copy(trigger = CacheMaintenanceTrigger.EXPIRY))
                    val afterExpiry = if (expired.isEmpty()) snapshot else snapshot()
                    // At exact capacity optional writers are already closed. Trimming only an
                    // overage can leave that state forever, with no next write to wake eviction.
                    // Reclaim a bounded refill slice, never protected readers/offline resources.
                    val refill = if (limit > 0L && afterExpiry.totalBytes >= limit)
                        minOf(8L * MIB, maxOf(1L, limit / 16L)) else 0L
                    val cleanupTarget = (limit - refill).coerceAtLeast(0L)
                    var excess = (afterExpiry.totalBytes - cleanupTarget).coerceAtLeast(0L)
                    if (excess > 0L) {
                        val ageOrder = compareBy<StoredCacheItem> { it.resource.accessedAt.takeIf { at -> at > 0L } ?: it.modifiedAt }
                        val order = when (policy.evictionOrder) {
                            CacheEvictionOrder.UNUSED_FIRST -> compareBy<StoredCacheItem> { if (!it.resource.used) 0 else 1 }.then(ageOrder)
                            CacheEvictionOrder.LEAST_RECENT -> ageOrder
                            CacheEvictionOrder.LARGEST_FIRST -> compareByDescending<StoredCacheItem> { it.bytes }.then(ageOrder)
                        }
                        val ordered = afterExpiry.items.filterNot { it.protected }.sortedWith(order)
                        val selected = ordered.takeWhile { item -> (excess > 0L).also { excess -= item.bytes } }
                        clear(selected, clearLegacy = excess > 0L && afterExpiry.legacyReclaimable,
                            legacyRevision = afterExpiry.legacyRevision, legacyStamp = afterExpiry.legacyStamp, maintenanceFence = fence)
                    }
                    ReaderCatalogStore(app).trimToSize(policy.catalogMiB?.toLong()?.times(MIB)
                        ?: (limit / 16).coerceAtMost(32L * MIB), expire = policy.automaticCleanup) { owner ->
                            !synchronized(lock) { maintenanceAllowed(fence) } || protectedCatalog(owner)
                        }
                    val beforeVideo = snapshot()
                    val videoBytes = beforeVideo.items.filter { it.resource.backend == CacheBackend.ANIME }.sumOf { it.bytes }
                    synchronized(lock) { animeCache }?.resize(
                        (limit - (beforeVideo.totalBytes - videoBytes)).coerceAtLeast(0L),
                        canResize = { synchronized(lock) { maintenanceAllowed(fence) } },
                        commitResize = { publish -> synchronized(lock) {
                            if (!maintenanceAllowed(fence)) false else { publish(); true }
                        } })
                    val current = snapshot()
                    retryAfterGrace = retryAfterGrace || current.totalBytes > cleanupTarget &&
                        current.items.any { it.protected && wallTimeMillis() - it.resource.accessedAt in 0L..FILE_HANDOFF_MILLIS }
                    val other = current.totalBytes - current.items.filter { it.resource.backend == CacheBackend.COIL }.sumOf { it.bytes } - current.coilUnclassifiedBytes
                    val disk = synchronized(lock) { coil }
                    // Do not stamp an old scanned budget with a newer publication version.
                    val publication = synchronized(lock) { budget to budgetVersion }
                    disk?.resize((publication.first - other).coerceAtLeast(0L), publication.second)
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
        private const val MAX_DETACHED_ENTRIES = 2_048
        private const val MAX_DETACHED_ALIASES_PER_ENTRY = 64
        private const val MAX_DETACHED_ALIASES = 8_192
        private const val MAX_DETACHED_CHARS = 1_048_576L
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
