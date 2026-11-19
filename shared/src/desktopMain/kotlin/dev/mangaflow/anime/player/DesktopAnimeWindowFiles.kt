package dev.mangaflow.anime.player

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.storage.AuxiliaryCacheEntry
import dev.mangaflow.storage.AuxiliaryDiskCacheOwner
import dev.mangaflow.storage.AuxiliaryCacheRemovalException
import dev.mangaflow.storage.StorageCacheStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Online work/output stays visible to the shared cache coordinator while its consumer owns a lease. */
internal class DesktopAnimeWindowFiles private constructor(context: PlatformContext) : AuxiliaryDiskCacheOwner {
    private val root = File(context.cacheRoot, "anime-online-windows")
    private val lock = Any()
    private val active = mutableSetOf<String>()
    private val storage = StorageCacheStore.get(context)
    override val ownerId = "anime-online-windows"

    fun create(asset: AnimePlaybackAsset): File = synchronized(lock) {
        check(root.isDirectory || root.mkdirs()) { "无法创建番剧预处理缓存目录" }
        val directory = File(root, UUID.randomUUID().toString())
        check(directory.mkdir()) { "无法创建番剧预处理窗口" }
        try {
            Properties().apply {
                setProperty("work", asset.subjectId); setProperty("part", asset.episodeId); setProperty("source", asset.sourceId)
                setProperty("workTitle", asset.subjectTitle); setProperty("partTitle", asset.episodeTitle)
            }.let { labels -> File(directory, "labels.properties").outputStream().use { labels.store(it, "MangaFlow anime window") } }
        } catch (failure: Exception) { directory.deleteRecursively(); throw failure }
        active += directory.name
        directory
    }

    fun release(directory: File) {
        try {
            synchronized(lock) {
                if (directory.parentFile == root && directory.name in active) {
                    val before = bytes(directory)
                    try { removeDirectory(directory, before) }
                    finally { active.remove(directory.name) }
                }
            }
        } finally { storage.cacheWorkFinished() }
    }

    private fun labels(directory: File): Properties = runCatching {
        val file = File(directory, "labels.properties")
        require(file.isFile && file.length() in 1L..32_768L)
        Properties().apply { file.inputStream().use { load(it) } }
    }.getOrDefault(Properties())

    private fun removeDirectory(directory: File, expectedBytes: Long): Long {
        val labels = File(directory, "labels.properties")
        val children = directory.listFiles().orEmpty().filter { it != labels }
        children.forEach { it.deleteRecursively() }
        if (children.none { it.exists() }) { labels.delete(); directory.delete() }
        val freed = (expectedBytes - bytes(directory)).coerceAtLeast(0L)
        if (directory.exists()) throw AuxiliaryCacheRemovalException(freed)
        return freed
    }

    override suspend fun snapshot(): List<AuxiliaryCacheEntry> = withContext(Dispatchers.IO) { synchronized(lock) {
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(NAME) }.map(::entry)
    } }

    override suspend fun remove(expected: AuxiliaryCacheEntry, canRemove: () -> Boolean): Long? = withContext(Dispatchers.IO) {
        try {
            synchronized(lock) {
                if (!expected.key.matches(NAME) || expected.key in active || !canRemove()) return@synchronized null
                val directory = File(root, expected.key)
                if (!directory.isDirectory || entry(directory) != expected) return@synchronized null
                removeDirectory(directory, expected.bytes)
            }
        } finally { storage.cacheWorkFinished() }
    }

    private fun entry(directory: File): AuxiliaryCacheEntry {
        val files = directory.walkTopDown().filter(File::isFile).toList().sortedBy { it.path }
        val stamp = files.joinToString("|") { "${it.relativeTo(directory).path}:${it.length()}:${it.lastModified()}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(stamp.toByteArray()).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
        val labels = labels(directory)
        return AuxiliaryCacheEntry(directory.name, files.sumOf { it.length() }, files.maxOfOrNull { it.lastModified() } ?: 0L,
            digest, directory.name in active, content = "anime", workId = labels.getProperty("work").orEmpty(),
            partId = labels.getProperty("part").orEmpty(), sourceId = labels.getProperty("source").orEmpty(),
            workTitle = labels.getProperty("workTitle").orEmpty(), partTitle = labels.getProperty("partTitle").orEmpty(),
            temporary = true)
    }

    companion object {
        private val NAME = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val stores = mutableMapOf<String, DesktopAnimeWindowFiles>()
        fun get(context: PlatformContext): DesktopAnimeWindowFiles = synchronized(stores) {
            stores.getOrPut(context.cacheRoot) {
                DesktopAnimeWindowFiles(context).also { StorageCacheStore.get(context).registerAuxiliaryOwner(it) }
            }
        }
        fun bytes(directory: File): Long = if (!directory.exists()) 0L else directory.walkTopDown().filter(File::isFile).sumOf { it.length() }
    }
}
