package dev.mangaflow.enhancement

import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.sha256File
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.cacheDirectory
import dev.mangaflow.platform.storage.syncStorageFile
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.AuxiliaryCacheEntry
import dev.mangaflow.storage.AuxiliaryCacheRemovalException
import dev.mangaflow.storage.AuxiliaryDiskCacheOwner
import dev.mangaflow.storage.StorageCacheStore
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** No URL or header is retained in the derivative journal. Original bytes have their own owner. */
data class EnhancedAssetIdentity(
    val namespace: String,
    val sourceId: String,
    val sourceVersion: String,
    val workId: String,
    val chapterId: String,
    val pageId: String,
    val originalDigest: String,
    val modelId: String,
    val modelVersion: String,
    val modelDigest: String,
    val scale: Int,
    val parameters: String,
    val outputFormat: String = "png",
) {
    init {
        require(namespace in NAMESPACES)
        require(scale > 0)
        require(originalDigest.matches(DIGEST) && modelDigest.isNotBlank())
        require(outputFormat in setOf("png", "webp", "jpg", "mkv", "mp4"))
    }

    /** Length prefixes prevent a provider identifier from changing field boundaries. */
    val encoded: String = listOf("enhanced-asset-v1", namespace, sourceId, sourceVersion,
        workId, chapterId, pageId, originalDigest, modelId, modelVersion, modelDigest,
        scale.toString(), parameters, outputFormat).joinToString("") { "${it.length}:$it" }
    val key: String = digest(encoded)

    companion object {
        internal val NAMESPACES = setOf("manga", "adult_manga", "anime")
        internal val DIGEST = Regex("[a-f0-9]{64}")
        internal fun digest(value: String): String = sha256(value.encodeToByteArray()).joinToString("") {
            it.toUByte().toString(16).padStart(2, '0')
        }
    }
}

data class EnhancedAssetUsage(
    val completedBytes: Long = 0L,
    val temporaryBytes: Long = 0L,
    val entries: Int = 0,
    val leasedEntries: Int = 0,
)

/** A caller must keep this lease until its image decoder/player has released the file. */
class EnhancedAssetLease internal constructor(
    val identity: EnhancedAssetIdentity,
    val file: StorageFile,
    val width: Int,
    val height: Int,
    val outputDigest: String,
    private val release: () -> Unit,
    private val acquireRetention: () -> AutoCloseable,
) : AutoCloseable {
    private val lock = StorageLock()
    private var closed = false
    /** Native decoders retain independently until their actual blocking call returns. */
    fun retain(): AutoCloseable = synchronized(lock) {
        check(!closed) { "增强文件租约已释放" }
        acquireRetention()
    }
    internal fun fork(): EnhancedAssetLease = synchronized(lock) {
        check(!closed) { "增强文件租约已释放" }
        val retained = acquireRetention()
        EnhancedAssetLease(identity, file, width, height, outputDigest, retained::close, acquireRetention)
    }

    override fun close() = synchronized(lock) { if (!closed) { closed = true; release() } }
}

class EnhancedAssetWork internal constructor(
    val identity: EnhancedAssetIdentity,
    val directory: StorageFile,
    val output: StorageFile,
    internal val token: String,
)

/**
 * One owner for regenerable derivatives. The entire directory (asset + manifest) is
 * renamed only after validation. It never accepts an original/download path to delete.
 */
class EnhancedAssetCache private constructor(context: PlatformContext) : AuxiliaryDiskCacheOwner {
    private val app = applicationContext(context)
    private val root = StorageFile(cacheDirectory(app), "enhancement-v1")
    private val mutation = Mutex()
    private val cleanup = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private data class DisplayFile(val work: EnhancedAssetWork, val width: Int, val height: Int, val digest: String,
        val bytes: Long, val modified: Long)
    private val displayFiles = mutableMapOf<String, DisplayFile>()
    private val leaseLock = StorageLock()
    private val leases = mutableMapOf<String, Int>()
    private val lastAccess = mutableMapOf<String, Long>()
    private val activeWork = mutableMapOf<String, EnhancedAssetIdentity>()
    private val verified = mutableMapOf<String, String>()
    // Only this owner publishes files. Removals may lower a known upper bound.
    private val measuredUpperBytes = mutableMapOf<String, Long>()
    private val mutableRevision = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val storage = StorageCacheStore.get(app)
    /** A completed background chapter can wake a cache-only reader without starting inference. */
    val revision: StateFlow<Map<String, Long>> = mutableRevision.asStateFlow()
    override val ownerId: String = "enhancement-v1"

    init {
        storage.registerAuxiliaryOwner(this)
        // A process killed with a visible result cannot run its final lease callback.
        cleanup.launch {
            mutation.withLock {
                namespaces(null).forEach { namespace ->
                    StorageFile(root, namespace).listFiles().orEmpty().filter { directory ->
                        directory.name.startsWith(DISPLAY_PREFIX) && directory.name.matches(WORK_DIRECTORY) &&
                            directory.name.removePrefix(".work-") !in activeWork
                    }.forEach { it.deleteRecursively() }
                }
            }
        }
    }

    /** A cancelled dispatcher handoff must release the lease it never delivered. */
    private suspend fun acquireOnIo(action: suspend () -> EnhancedAssetLease?): EnhancedAssetLease? {
        var acquired: EnhancedAssetLease? = null
        try {
            val result = withContext(Dispatchers.IO) {
                mutation.withLock { action().also { acquired = it } }
            }
            acquired = null
            return result
        } finally {
            acquired?.close()
        }
    }

    suspend fun acquire(identity: EnhancedAssetIdentity): EnhancedAssetLease? = acquireOnIo { acquireLocked(identity) }

    internal suspend fun acquireForReader(identity: EnhancedAssetIdentity): EnhancedAssetLease? = acquireOnIo {
        acquireLocked(identity) ?: existingDisplay(identity)?.let(::leaseDisplay)
    }

    suspend fun prepare(identity: EnhancedAssetIdentity): EnhancedAssetWork {
        var allocated: EnhancedAssetWork? = null
        try {
            val result = withContext(Dispatchers.IO) {
                mutation.withLock {
                    val parent = StorageFile(root, identity.namespace)
                    check(parent.isDirectory || parent.mkdirs()) { "无法建立增强缓存目录" }
                    val token = randomUuid()
                    val directory = StorageFile(parent, ".work-$token")
                    check(directory.mkdir()) { "无法建立增强工作目录" }
                    activeWork[token] = identity
                    EnhancedAssetWork(identity, directory, StorageFile(directory, "asset.${identity.outputFormat}"), token)
                        .also { allocated = it }
                }
            }
            allocated = null
            return result
        } finally {
            allocated?.let { withContext(NonCancellable) { abandon(it) } }
        }
    }

    /** Caller-supplied dimensions are checked against the engine's decoded output before this point. */
    suspend fun publish(
        work: EnhancedAssetWork,
        width: Int,
        height: Int,
        maximumRetainedBytes: Long = Long.MAX_VALUE,
        isCurrent: () -> Boolean,
    ): EnhancedAssetLease = checkNotNull(acquireOnIo {
        require(work.token in activeWork && ownedWork(work)) { "增强工作身份已失效" }
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "阅读位置或处理配置已改变" }
        require(width > 0 && height > 0) { "增强图片尺寸无效" }
        check(work.output.isFile && work.output.length() > 0L) { "增强结果不完整" }
        val bytes = work.output.length()
        val modified = work.output.lastModified()
        val outputDigest = sha256File(work.output.path)
        check(work.output.length() == bytes && work.output.lastModified() == modified) { "增强结果仍在写入" }
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "阅读位置或处理配置已改变" }
        acquireLocked(work.identity)?.let { existing ->
            abandonLocked(work)
            return@acquireOnIo existing
        }
        if (bytes > maximumRetainedBytes) {
            // Keep this actual output readable through every decoder's last lease,
            // without adding a disposable visible page to the chapter/durable cache.
            val key = cacheToken(work.identity.namespace, work.identity.key)
            existingDisplay(work.identity)?.let { existing ->
                abandonLocked(work)
                return@acquireOnIo leaseDisplay(existing)
            }
            work.directory.listFiles().orEmpty().filter { it != work.output }.forEach {
                check(it.deleteRecursively()) { "增强临时输入无法清理" }
            }
            val token = "view-${work.token}"
            val directory = StorageFile(work.directory.parentFile, ".work-$token")
            check(work.directory.renameTo(directory)) { "增强结果无法交给阅读器" }
            activeWork.remove(work.token)
            activeWork[token] = work.identity
            val pinned = EnhancedAssetWork(work.identity, directory, StorageFile(directory, work.output.name), token)
            val page = DisplayFile(pinned, width, height, outputDigest, bytes, pinned.output.lastModified())
            displayFiles[key] = page
            return@acquireOnIo leaseDisplay(page)
        }
        syncStorageFile(work.output)
        val metadata = JSONObject().put("schema", 1).put("identity", work.identity.encoded)
            .put("key", work.identity.key).put("format", work.identity.outputFormat)
            .put("width", width).put("height", height).put("bytes", bytes)
            .put("outputDigest", outputDigest).put("createdAt", wallTimeMillis())
        val manifest = StorageFile(work.directory, "manifest.json")
        manifest.writeText(metadata.toString())
        syncStorageFile(manifest)
        val publishedBytes = bytes + manifest.length()
        // Original input copies are transient and are never part of the published asset.
        work.directory.listFiles().orEmpty().filter { it != work.output && it != manifest }.forEach {
            check(it.deleteRecursively()) { "增强临时输入无法清理" }
        }
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "阅读位置或处理配置已改变" }
        val destination = assetDirectory(work.identity.namespace, work.identity.key)
        if (destination.exists()) {
            check(!isLeased(work.identity.namespace, work.identity.key)) { "旧增强结果仍在使用" }
            check(destination.deleteRecursively()) { "旧增强结果无法替换" }
        }
        // Same-volume atomic directory publication. No partially written file becomes readable.
        check(work.directory.renameTo(destination)) { "增强结果无法发布" }
        activeWork.remove(work.token)
        val namespace = work.identity.namespace
        measuredUpperBytes[namespace]?.let { measuredUpperBytes[namespace] = it + publishedBytes }
        // Rename transfers the same privately owned, already hashed file. The ordinary
        // acquire path rechecks manifest, size and mtime, and rehashes if the stamp differs.
        verified[cacheToken(namespace, work.identity.key)] = "$bytes:$modified:$outputDigest"
        requireNotNull(acquireLocked(work.identity)) { "增强结果校验失败" }.also {
            changed(work.identity.namespace)
        }
    })

    suspend fun abandon(work: EnhancedAssetWork) = withContext(Dispatchers.IO) {
        mutation.withLock { abandonLocked(work) }
    }

    suspend fun usage(namespace: String? = null): EnhancedAssetUsage = withContext(Dispatchers.IO) {
        mutation.withLock {
            require(namespace == null || namespace in EnhancedAssetIdentity.NAMESPACES)
            var bytes = 0L; var temporary = 0L; var count = 0; var held = 0
            namespaces(namespace).forEach { name ->
                StorageFile(root, name).listFiles().orEmpty().forEach { directory ->
                    if (!directory.isDirectory) return@forEach
                    if (directory.name.startsWith(".work-")) temporary += directoryBytes(directory)
                    else if (directory.name.matches(EnhancedAssetIdentity.DIGEST)) {
                        bytes += directoryBytes(directory); count++
                        if (isLeased(name, directory.name)) held++
                    }
                }
            }
            EnhancedAssetUsage(bytes, temporary, count, held)
        }
    }

    /** Reclaims only derivatives and abandoned work. An active decoder's lease wins over cleanup. */
    suspend fun clear(namespace: String? = null): Long = withContext(Dispatchers.IO) {
        mutation.withLock {
            require(namespace == null || namespace in EnhancedAssetIdentity.NAMESPACES)
            var freed = 0L
            namespaces(namespace).forEach { name ->
                StorageFile(root, name).listFiles().orEmpty().forEach { directory ->
                    val key = directory.name
                    val abandoned = key.startsWith(".work-") && key.removePrefix(".work-") !in activeWork
                    val derivative = key.matches(EnhancedAssetIdentity.DIGEST) && !isLeased(name, key)
                    if (directory.isDirectory && (abandoned || derivative)) {
                        val before = directoryBytes(directory)
                        directory.deleteRecursively()
                        freed += (before - directoryBytes(directory)).coerceAtLeast(0L)
                        verified.remove(cacheToken(name, key)); lastAccess.remove(cacheToken(name, key))
                        if (before > directoryBytes(directory)) changed(name)
                    }
                }
            }
            freed
        }
    }

    /** Window retirement never calls this; LRU eviction is a separate storage-budget decision. */
    suspend fun trimToBytes(maximum: Long, namespace: String? = null): Long = withContext(Dispatchers.IO) {
        mutation.withLock {
            require(namespace == null || namespace in EnhancedAssetIdentity.NAMESPACES)
            val limit = maximum.coerceAtLeast(0L)
            val settled = namespace != null && activeWork.values.none { it.namespace == namespace }
            if (settled && namespace != null && measuredUpperBytes[namespace]?.let { it <= limit } == true)
                return@withLock 0L
            val entries = readEntriesLocked(namespace)
            var remaining = entries.sumOf { it.bytes }
            var freed = 0L
            if (remaining > limit) {
                for (entry in entries.sortedBy { lastAccess[it.key] ?: it.modifiedAt }) {
                    if (remaining <= limit) break
                    val removed = removeLocked(entry) { true } ?: continue
                    remaining -= removed
                    freed += removed
                }
            }
            if (settled && namespace != null) measuredUpperBytes[namespace] = remaining
            freed
        }
    }

    override suspend fun snapshot(): List<AuxiliaryCacheEntry> = withContext(Dispatchers.IO) {
        mutation.withLock { readEntriesLocked() }
    }

    override suspend fun remove(expected: AuxiliaryCacheEntry, canRemove: () -> Boolean): Long? =
        withContext(Dispatchers.IO) { mutation.withLock { removeLocked(expected, canRemove) } }

    private fun acquireLocked(identity: EnhancedAssetIdentity): EnhancedAssetLease? {
        val directory = assetDirectory(identity.namespace, identity.key)
        val meta = metadata(directory) ?: return null
        if (meta.optString("identity") != identity.encoded || meta.optString("key") != identity.key ||
            meta.optString("format") != identity.outputFormat) return null
        val file = StorageFile(directory, "asset.${identity.outputFormat}")
        val bytes = meta.optLong("bytes")
        val width = meta.optInt("width"); val height = meta.optInt("height")
        val hash = meta.optString("outputDigest")
        if (!file.isFile || bytes <= 0 || file.length() != bytes || width <= 0 || height <= 0 ||
            !hash.matches(EnhancedAssetIdentity.DIGEST)) return null
        val token = cacheToken(identity.namespace, identity.key)
        val modified = file.lastModified()
        val stamp = "$bytes:$modified:$hash"
        if (verified[token] != stamp) {
            if (sha256File(file.path) != hash || file.length() != bytes || file.lastModified() != modified) return null
            verified[token] = stamp
        }
        lastAccess[token] = wallTimeMillis()
        return lease(identity, file, width, height, hash, token, storage::cacheWorkFinished)
    }

    private fun existingDisplay(identity: EnhancedAssetIdentity): DisplayFile? =
        displayFiles[cacheToken(identity.namespace, identity.key)]?.takeIf { entry ->
            entry.work.identity == identity && entry.work.output.isFile &&
                entry.work.output.length() == entry.bytes && entry.work.output.lastModified() == entry.modified
        }

    private fun leaseDisplay(page: DisplayFile): EnhancedAssetLease {
        val work = page.work
        val identity = work.identity
        val token = cacheToken(identity.namespace, work.directory.name)
        return lease(identity, work.output, page.width, page.height, page.digest, token) {
            cleanup.launch {
                mutation.withLock {
                    val key = cacheToken(identity.namespace, identity.key)
                    if (!isLeased(identity.namespace, work.directory.name)) {
                        if (displayFiles[key] === page) displayFiles.remove(key)
                        abandonLocked(work)
                    }
                }
            }
        }
    }

    private fun lease(identity: EnhancedAssetIdentity, file: StorageFile, width: Int, height: Int,
        hash: String, token: String, onUnused: () -> Unit): EnhancedAssetLease {
        synchronized(leaseLock) { leases[token] = (leases[token] ?: 0) + 1 }
        fun releaseLease() {
            val last = synchronized(leaseLock) {
                val count = (leases[token] ?: 0) - 1
                if (count <= 0) leases.remove(token) else leases[token] = count
                count <= 0
            }
            if (last) onUnused()
        }
        return EnhancedAssetLease(identity, file, width, height, hash, release = ::releaseLease,
            acquireRetention = {
                synchronized(leaseLock) { leases[token] = (leases[token] ?: 0) + 1 }
                object : AutoCloseable {
                    private val closedLock = StorageLock()
                    private var released = false
                    override fun close() = synchronized(closedLock) {
                        if (!released) { released = true; releaseLease() }
                    }
                }
            })
    }

    private fun readEntriesLocked(onlyNamespace: String? = null): List<AuxiliaryCacheEntry> = buildList {
        namespaces(onlyNamespace).forEach { namespace ->
            StorageFile(root, namespace).listFiles().orEmpty().forEach { directory ->
                if (directory.isDirectory && directory.name.matches(EnhancedAssetIdentity.DIGEST)) {
                    entry(namespace, directory.name)?.let(::add)
                } else if (directory.isDirectory && directory.name.matches(WORK_DIRECTORY)) {
                    workEntry(namespace, directory.name)?.let(::add)
                }
            }
        }
    }

    private fun entry(namespace: String, key: String): AuxiliaryCacheEntry? {
        val directory = assetDirectory(namespace, key)
        val meta = metadata(directory) ?: return null
        if (meta.optString("key") != key) return null
        val format = meta.optString("format")
        if (format !in setOf("png", "webp", "jpg", "mkv", "mp4")) return null
        val asset = StorageFile(directory, "asset.$format")
        if (!asset.isFile) return null
        val manifest = StorageFile(directory, "manifest.json")
        val bytes = directoryBytes(directory)
        val modified = maxOf(asset.lastModified(), manifest.lastModified())
        val stamp = EnhancedAssetIdentity.digest("${meta}\u0000$bytes\u0000$modified")
        val fields = identityFields(meta.optString("identity"))?.takeIf { it[1] == namespace }
        return AuxiliaryCacheEntry(cacheToken(namespace, key), bytes, modified, stamp,
            protected = isLeased(namespace, key), content = namespace,
            sourceId = fields?.get(2).orEmpty(), workId = fields?.get(4).orEmpty(), partId = fields?.get(5).orEmpty())
    }

    private fun workEntry(namespace: String, key: String): AuxiliaryCacheEntry? {
        if (namespace !in EnhancedAssetIdentity.NAMESPACES || !key.matches(WORK_DIRECTORY)) return null
        val directory = StorageFile(StorageFile(root, namespace), key)
        if (!directory.isDirectory) return null
        var bytes = 0L
        var modified = 0L
        val stamp = buildString {
            fun visit(file: StorageFile, path: String) {
                if (file.isFile) {
                    val size = file.length()
                    val time = file.lastModified()
                    bytes += size; modified = maxOf(modified, time)
                    append('|').append(path.length).append(':').append(path).append(':').append(size).append(':').append(time)
                } else file.listFiles().orEmpty().sortedBy { it.name }.forEach { visit(it, "$path/${it.name}") }
            }
            visit(directory, "")
        }
        if (bytes <= 0L) return null
        val owner = activeWork[key.removePrefix(".work-")]
        return AuxiliaryCacheEntry(cacheToken(namespace, key), bytes, modified, EnhancedAssetIdentity.digest(stamp),
            protected = owner != null, content = namespace, temporary = true,
            sourceId = owner?.sourceId.orEmpty(), workId = owner?.workId.orEmpty(), partId = owner?.chapterId.orEmpty())
    }

    /** Read only the existing length-prefixed object fields; the digest remains the authoritative key. */
    private fun identityFields(encoded: String): List<String>? {
        val fields = mutableListOf<String>()
        var offset = 0
        repeat(7) {
            val separator = encoded.indexOf(':', offset)
            if (separator < offset) return null
            val length = encoded.substring(offset, separator).toIntOrNull() ?: return null
            if (length < 0 || length > encoded.length - separator - 1) return null
            offset = separator + 1
            fields += encoded.substring(offset, offset + length)
            offset += length
        }
        return fields.takeIf { it[0] == "enhanced-asset-v1" && it[1] in EnhancedAssetIdentity.NAMESPACES }
    }

    private fun removeLocked(expected: AuxiliaryCacheEntry, canRemove: () -> Boolean): Long? {
        val namespace = expected.key.substringBefore(':')
        val key = expected.key.substringAfter(':', "")
        val temporary = key.matches(WORK_DIRECTORY)
        if (expected.protected || namespace !in EnhancedAssetIdentity.NAMESPACES ||
            (!temporary && !key.matches(EnhancedAssetIdentity.DIGEST)) ||
            isLeased(namespace, key) || temporary && key.removePrefix(".work-") in activeWork || !canRemove()) return null
        val current = (if (temporary) workEntry(namespace, key) else entry(namespace, key)) ?: return null
        if (current != expected) return null
        val directory = assetDirectory(namespace, key)
        if (!canRemove() || isLeased(namespace, key)) return null
        val removed = directory.deleteRecursively()
        val freed = (current.bytes - directoryBytes(directory)).coerceAtLeast(0L)
        if (freed > 0L) changed(namespace)
        verified.remove(expected.key); lastAccess.remove(expected.key)
        if (!removed) throw AuxiliaryCacheRemovalException(freed)
        return freed
    }

    private fun metadata(directory: StorageFile): JSONObject? = runCatching {
        val file = StorageFile(directory, "manifest.json")
        if (!file.isFile || file.length() !in 1L..65_536L) return@runCatching null
        JSONObject(file.readText()).takeIf { it.optInt("schema") == 1 }
    }.getOrNull()

    private fun abandonLocked(work: EnhancedAssetWork) {
        if (work.token in activeWork && ownedWork(work)) {
            if (!work.directory.deleteRecursively()) measuredUpperBytes.remove(work.identity.namespace)
            activeWork.remove(work.token)
            storage.cacheWorkFinished()
        }
    }

    private fun ownedWork(work: EnhancedAssetWork): Boolean =
        work.directory == StorageFile(StorageFile(root, work.identity.namespace), ".work-${work.token}") &&
            work.output == StorageFile(work.directory, "asset.${work.identity.outputFormat}")

    private fun isLeased(namespace: String, key: String): Boolean = synchronized(leaseLock) {
        (leases[cacheToken(namespace, key)] ?: 0) > 0
    }
    private fun assetDirectory(namespace: String, key: String) = StorageFile(StorageFile(root, namespace), key)
    private fun cacheToken(namespace: String, key: String) = "$namespace:$key"
    private fun changed(namespace: String) {
        mutableRevision.value = mutableRevision.value + (namespace to ((mutableRevision.value[namespace] ?: 0L) + 1L))
        storage.cacheWorkFinished()
    }
    private fun namespaces(namespace: String?) = namespace?.let(::setOf) ?: EnhancedAssetIdentity.NAMESPACES
    private fun directoryBytes(directory: StorageFile): Long = directory.listFiles().orEmpty().sumOf {
        if (it.isFile) it.length() else if (it.isDirectory) directoryBytes(it) else 0L
    }

    companion object {
        private const val DISPLAY_PREFIX = ".work-view-"
        private val WORK_DIRECTORY = Regex("\\.work-[A-Za-z0-9-]{1,64}")
        private val lock = StorageLock()
        private val instances = mutableMapOf<String, EnhancedAssetCache>()
        fun get(context: PlatformContext): EnhancedAssetCache = synchronized(lock) {
            val key = cacheDirectory(applicationContext(context)).absolutePath
            instances.getOrPut(key) { EnhancedAssetCache(context) }
        }
    }
}
