package dev.mangaflow.anime.player

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.net.NetworkResponse
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.AnimeDiskCacheEntry
import dev.mangaflow.storage.AnimeDiskCacheOwner
import dev.mangaflow.storage.AnimeDiskCacheUsage
import dev.mangaflow.storage.StorageCacheStore
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Original compressed bytes only. Native packet memory and enhanced frames are different owners. */
internal class DesktopAnimeStreamCache private constructor(context: PlatformContext) : AnimeStreamCache, AnimeDiskCacheOwner {
    private val directory = File(context.cacheRoot, "anime-stream-cache")
    private val storage = StorageCacheStore.get(context)
    private val lock = Any()
    private val spans = linkedMapOf<String, Span>()
    private val writes = mutableMapOf<String, Pair<AnimePlaybackAsset, File>>()
    private val leases = mutableMapOf<String, Int>()
    private val revisions = mutableMapOf<String, Long>()
    private var loaded = false
    private var budget = 0L
    private var budgetInitialized = false
    private var reserved = 0L
    private var revision = 0L
    private var directoryStamp = ""

    private data class Span(
        val id: String, val asset: String, val resource: String, val start: Long, val end: Long, val total: Long,
        val type: String, val expiresAt: Long?, var accessedAt: Long, val file: File, val metadata: File,
        val subjectId: String, val episodeId: String, val sourceId: String,
        val subjectTitle: String, val episodeTitle: String,
    ) {
        val bytes: Long get() = end - start + 1L
        var diskStamp = ""
        fun physicalBytes(): Long = (if (file.isFile) file.length() else 0L) +
            (if (metadata.isFile) metadata.length() else 0L)
        fun stamp(): String = "${file.isFile}:${file.length()}:${file.lastModified()}:${metadata.isFile}:${metadata.length()}:${metadata.lastModified()}"
    }

    override fun retain(asset: AnimePlaybackAsset): () -> Unit {
        val key = identity(asset)
        synchronized(lock) {
            load()
            leases[key] = (leases[key] ?: 0) + 1
            changed(key)
        }
        val released = AtomicBoolean()
        return {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    val remaining = (leases[key] ?: 1) - 1
                    if (remaining == 0) leases.remove(key) else leases[key] = remaining
                    changed(key)
                }
                storage.cacheWorkFinished()
            }
        }
    }

    override fun read(asset: AnimePlaybackAsset, url: String, range: String?): AnimeCachedStream? = synchronized(lock) {
        load()
        if (asset.expiresAt?.let { it <= wallTimeMillis() } == true) return null
        val key = identity(asset)
        val resource = digest(url)
        val now = wallTimeMillis()
        val candidates = spans.values.filter { it.asset == key && it.resource == resource &&
            it.expiresAt?.let { deadline -> deadline > now } != false && it.file.length() == it.bytes }
        for (span in candidates.sortedByDescending { it.bytes }) {
            val requested = if (range == null) 0L to (span.total - 1L) else animeByteRange(range, span.total)
            if (requested == null || requested.first !in span.start..span.end || requested.second > span.end ||
                range == null && (span.start != 0L || span.end + 1L != span.total)) continue
            val selectedEnd = minOf(requested.second, span.end)
            val input = runCatching { RandomAccessFile(span.file, "r").apply { seek(requested.first - span.start) } }.getOrNull()
                ?: continue
            span.accessedAt = now
            span.metadata.setLastModified(now)
            span.diskStamp = span.stamp()
            leases[key] = (leases[key] ?: 0) + 1
            changed(key)
            return object : AnimeCachedStream {
                override val start = requested.first
                override val end = selectedEnd
                override val total = span.total
                override val contentType = span.type
                private var remaining = end - start + 1L
                private val closed = AtomicBoolean()
                override fun read(buffer: ByteArray): Int {
                    if (remaining <= 0L) return -1
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    check(count > 0) { "番剧缓存片段不完整" }
                    remaining -= count
                    return count
                }
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    runCatching { input.close() }
                    synchronized(lock) {
                        val count = (leases[key] ?: 1) - 1
                        if (count == 0) leases.remove(key) else leases[key] = count
                        changed(key)
                    }
                }
            }
        }
        null
    }

    override fun write(asset: AnimePlaybackAsset, url: String, response: NetworkResponse): AnimeStreamCacheWriter? = synchronized(lock) {
        load()
        if (!budgetInitialized || budget <= 0L || response.code !in setOf(200, 206) ||
            response.header("Content-Encoding")?.equals("identity", true) == false ||
            asset.expiresAt?.let { it <= wallTimeMillis() } == true) return null
        val length = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L } ?: return null
        val interval = if (response.code == 206) {
            val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                ?: return null
            val values = match.groupValues.drop(1).map { it.toLongOrNull() ?: return null }
            if (values[0] > values[1] || values[1] >= values[2] || values[1] - values[0] + 1L != length) return null
            values
        } else listOf(0L, length - 1L, length)
        val key = identity(asset)
        val resource = digest(url)
        val maximum = minOf(length, budget)
        if (maximum <= 0L) return null
        val end = interval[0] + maximum - 1L
        val id = digest("$key|$resource|${interval[0]}|$end|${interval[2]}")
        if (spans[id]?.file?.length() == maximum) return null
        trim(maximum)
        if (spans.values.sumOf { it.physicalBytes() } + reserved > budget - maximum) return null
        if (!directory.isDirectory && !directory.mkdirs()) return null
        val temporary = File(directory, "${UUID.randomUUID()}.partial")
        val output = runCatching { temporary.outputStream().buffered() }.getOrNull() ?: return null
        writes[temporary.name] = asset to temporary
        reserved += maximum
        leases[key] = (leases[key] ?: 0) + 1
        changed(key)
        object : AnimeStreamCacheWriter {
            private var count = 0L
            private var done = false
            override fun append(bytes: ByteArray, count: Int) {
                if (done) return
                val accepted = minOf(count.toLong(), maximum - this.count).toInt()
                output.write(bytes, 0, accepted)
                this.count += accepted
                if (this.count == maximum) finish(publish = true)
            }
            override fun close() { finish(publish = count == maximum) }
            private fun finish(publish: Boolean) {
                if (done) return
                done = true
                try {
                    output.close()
                    if (publish) synchronized(lock) {
                        if (temporary.length() == maximum && spans[id] == null &&
                            asset.expiresAt?.let { it > wallTimeMillis() } != false && maximum <= budget &&
                            spans.values.sumOf { it.physicalBytes() } + reserved <= budget) {
                            val payload = File(directory, "$id.bin")
                            val metadata = File(directory, "$id.properties")
                            val staged = File(directory, "${UUID.randomUUID()}.partial")
                            val span = Span(id, key, resource, interval[0], end, interval[2],
                                response.header("Content-Type") ?: "application/octet-stream", asset.expiresAt,
                                wallTimeMillis(), payload, metadata, asset.subjectId, asset.episodeId, asset.sourceId,
                                asset.subjectTitle, asset.episodeTitle)
                            try {
                                properties(span).let { data -> staged.outputStream().use { data.store(it, "MangaFlow media span") } }
                                Files.move(temporary.toPath(), payload.toPath(), StandardCopyOption.ATOMIC_MOVE)
                                Files.move(staged.toPath(), metadata.toPath(), StandardCopyOption.ATOMIC_MOVE)
                                span.diskStamp = span.stamp()
                                spans[id] = span
                                changed(key)
                            } catch (failure: Exception) {
                                if (!spans.containsKey(id)) { payload.delete(); metadata.delete() }
                                throw failure
                            } finally { staged.delete() }
                        }
                    }
                } finally {
                    temporary.delete()
                    synchronized(lock) {
                        writes.remove(temporary.name)
                        reserved -= maximum
                        val left = (leases[key] ?: 1) - 1
                        if (left == 0) leases.remove(key) else leases[key] = left
                        changed(key)
                    }
                    storage.cacheWorkFinished()
                }
            }
        }
    }

    override fun snapshot(): AnimeDiskCacheUsage = synchronized(lock) {
        load()
        val total = observeDisk()
        val grouped = spans.values.groupBy { it.asset }
        val entries = (grouped.keys + writes.values.map { identity(it.first) }).distinct().map { key ->
            entry(key, grouped[key].orEmpty())
        }
        AnimeDiskCacheUsage(total, revision, leases.isNotEmpty(),
            entries.maxOfOrNull { it.accessedAt } ?: 0L, entries)
    }

    private fun entry(key: String, values: List<Span>): AnimeDiskCacheEntry {
        val active = writes.values.filter { identity(it.first) == key }
        val first = values.firstOrNull()
        val asset = active.firstOrNull()?.first
        return AnimeDiskCacheEntry(key, values.sumOf { it.physicalBytes() } + active.sumOf { it.second.length() }, revisions[key] ?: 0L,
            maxOf(values.maxOfOrNull { it.accessedAt } ?: 0L, active.maxOfOrNull { it.second.lastModified() } ?: 0L),
            (leases[key] ?: 0) > 0, first?.subjectId ?: asset?.subjectId.orEmpty(), first?.episodeId ?: asset?.episodeId.orEmpty(),
            first?.sourceId ?: asset?.sourceId.orEmpty(), first?.subjectTitle ?: asset?.subjectTitle.orEmpty(),
            first?.episodeTitle ?: asset?.episodeTitle.orEmpty())
    }

    private fun observeDisk(): Long {
        spans.values.forEach { span ->
            val observed = span.stamp()
            if (observed != span.diskStamp) { span.diskStamp = observed; changed(span.asset) }
        }
        val files = directory.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        val stamp = files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        if (stamp != directoryStamp) { directoryStamp = stamp; revision++ }
        // In-flight and unclassified files remain visible through the coordinator's protected remainder.
        return files.sumOf { it.length() }
    }

    override fun resize(bytes: Long, canResize: () -> Boolean, commitResize: ((() -> Unit) -> Boolean)) = synchronized(lock) {
        if (canResize()) commitResize {
            budget = bytes.coerceAtLeast(0L)
            budgetInitialized = true
            if (loaded) trim(0L)
        }
        Unit
    }

    override fun clear(expectedRevision: Long, canClear: () -> Boolean): Long? = synchronized(lock) {
        load()
        observeDisk()
        if (revision != expectedRevision || leases.isNotEmpty() || !canClear()) return null
        spans.values.map { it.asset }.distinct().sumOf { remove(it) }
    }

    override fun clearEntry(expected: AnimeDiskCacheEntry, canClear: () -> Boolean): Long? = synchronized(lock) {
        load()
        observeDisk()
        val values = spans.values.filter { it.asset == expected.key }
        if (values.isEmpty() || entry(expected.key, values) != expected || (leases[expected.key] ?: 0) > 0 || !canClear()) return null
        remove(expected.key)
    }

    private fun changed(key: String) { revision++; revisions[key] = (revisions[key] ?: 0L) + 1L }

    private fun trim(needed: Long) {
        val groups = spans.values.groupBy { it.asset }.entries.sortedBy { (_, values) -> values.maxOf { it.accessedAt } }
        var bytes = spans.values.sumOf { it.physicalBytes() } + reserved
        for ((key, _) in groups) {
            if (bytes <= budget - needed) break
            if ((leases[key] ?: 0) == 0) bytes -= remove(key)
        }
    }

    private fun remove(key: String): Long {
        var freed = 0L
        spans.values.filter { it.asset == key }.forEach { span ->
            val before = span.physicalBytes()
            if (!span.file.exists() || span.file.delete()) {
                span.metadata.delete()
                if (!span.metadata.exists()) spans.remove(span.id)
                freed += (before - span.physicalBytes()).coerceAtLeast(0L)
                span.diskStamp = span.stamp()
            }
        }
        if (freed > 0L) changed(key)
        return freed
    }

    private fun load() {
        if (loaded) return
        loaded = true
        // These names are generated only by this owner. No active writer exists before the first load.
        directory.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[a-f0-9-]{36}\\.partial")) }
            .forEach { it.delete() }
        directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.properties")) && it.length() <= 32_768L }
            .forEach { metadata ->
                runCatching {
                    val data = Properties().apply { metadata.inputStream().use { load(it) } }
                    fun text(key: String) = requireNotNull(data.getProperty(key))
                    val id = metadata.nameWithoutExtension
                    val file = File(directory, "$id.bin")
                    val span = Span(id, text("asset"), text("resource"), text("start").toLong(), text("end").toLong(),
                        text("total").toLong(), text("type"), data.getProperty("expires")?.toLongOrNull(),
                        metadata.lastModified(), file, metadata, text("subject"), text("episode"), text("source"),
                        text("subjectTitle"), text("episodeTitle"))
                    require(span.start >= 0L && span.end >= span.start && span.total > span.end &&
                        file.isFile && file.length() == span.bytes &&
                        span.asset.matches(Regex("[a-f0-9]{64}")) && span.resource.matches(Regex("[a-f0-9]{64}")))
                    spans[id] = span
                    span.diskStamp = span.stamp()
                    changed(span.asset)
                }
            }
    }

    private fun properties(span: Span) = Properties().apply {
        setProperty("asset", span.asset); setProperty("resource", span.resource)
        setProperty("start", span.start.toString()); setProperty("end", span.end.toString()); setProperty("total", span.total.toString())
        setProperty("type", span.type); span.expiresAt?.let { setProperty("expires", it.toString()) }
        setProperty("subject", span.subjectId); setProperty("episode", span.episodeId); setProperty("source", span.sourceId)
        setProperty("subjectTitle", span.subjectTitle); setProperty("episodeTitle", span.episodeTitle)
    }

    companion object {
        private val stores = mutableMapOf<String, DesktopAnimeStreamCache>()
        fun get(context: PlatformContext): DesktopAnimeStreamCache = synchronized(stores) {
            stores.getOrPut(context.cacheRoot) {
                DesktopAnimeStreamCache(context).also { StorageCacheStore.get(context).attachAnimeCache(it) }
            }
        }
        private fun identity(asset: AnimePlaybackAsset): String = digest(buildString {
            listOf(asset.playbackIdentity(), asset.episodeId, asset.url,
                asset.headers.entries.sortedBy { it.key.lowercase() }.joinToString("|") { "${it.key.length}:${it.key}${it.value.length}:${it.value}" })
                .forEach { append(it.length).append(':').append(it) }
        })
        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
