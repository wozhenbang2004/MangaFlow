package dev.mangaflow.storage

import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.sha256File
import dev.mangaflow.platform.storage.StorageFile
import okio.IOException

/** An owner-issued review token. [key] is an opaque identity, never a caller-supplied path. */
data class AuxiliaryCacheEntry(
    val key: String,
    val bytes: Long,
    val modifiedAt: Long,
    val identity: String,
    val protected: Boolean = false,
    val content: String = "",
    val workId: String = "",
    val partId: String = "",
    val sourceId: String = "",
    val workTitle: String = "",
    val partTitle: String = "",
    val temporary: Boolean = false,
)

/** The repository keeps its own write lock and rechecks every reviewed identity before removal. */
interface AuxiliaryDiskCacheOwner {
    val ownerId: String
    suspend fun snapshot(): List<AuxiliaryCacheEntry>
    /** Null means the reviewed data changed or disappeared. The return value is actual removed bytes. */
    suspend fun remove(expected: AuxiliaryCacheEntry, canRemove: () -> Boolean = { true }): Long?
}

/** A failed family removal may still have released some files; the coordinator can report both. */
class AuxiliaryCacheRemovalException(val freedBytes: Long) : IOException("部分资料缓存未能清理，请重试。")

/**
 * Groups the existing atomic base/.new/.bak files without restoring or rewriting them during a scan.
 * All methods must run under the repository's disk mutex. Only the owner can issue [changed].
 */
internal class AuxiliaryCacheFiles(
    private val directory: StorageFile,
    private val ownsKey: (String) -> Boolean,
) {
    private data class Fingerprint(val bytes: Long, val modifiedAt: Long, val digest: String)
    private val fingerprints = mutableMapOf<String, Fingerprint>()
    private val revisions = mutableMapOf<String, Long>()
    private var nextRevision = 0L

    fun keyForFile(name: String): String? = name.removeSuffix(".new").removeSuffix(".bak")
        .takeIf { it == name || name == "$it.new" || name == "$it.bak" }
        ?.takeIf(ownsKey)

    /** Even an identical replacement in the same filesystem clock tick receives a new identity. */
    fun changed(key: String) {
        if (!ownsKey(key)) return
        revisions[key] = ++nextRevision
        family(key).forEach { fingerprints.remove(it.name) }
    }

    fun snapshot(memoryRevision: (String) -> Long = { 0L }): List<AuxiliaryCacheEntry> {
        val keys = directory.listFiles().orEmpty().filter { it.isFile }
            .mapNotNull { keyForFile(it.name) }.distinct()
        val names = keys.flatMap { key -> family(key).map { it.name } }.toSet()
        fingerprints.keys.retainAll(names)
        revisions.keys.retainAll(keys.toSet())
        return keys.mapNotNull { entry(it, memoryRevision(it)) }
    }

    /** Hashes stream through the existing bounded file hasher; no whole-file allocation. */
    fun entry(key: String, memoryRevision: Long, verifyBytes: Boolean = false): AuxiliaryCacheEntry? {
        if (!ownsKey(key)) return null
        val files = family(key).filter { it.isFile }
        if (files.isEmpty()) return null
        var total = 0L
        var modified = 0L
        val identity = buildString {
            append(key).append('|').append(revisions[key] ?: 0L).append('|').append(memoryRevision)
            files.forEach { file ->
                val bytes = file.length()
                val time = file.lastModified()
                val cached = fingerprints[file.name]?.takeIf { !verifyBytes && it.bytes == bytes && it.modifiedAt == time }
                val digest = cached?.digest ?: sha256File(file.path)
                // An outside filesystem change during the read cannot become a reviewable token.
                check(file.isFile && bytes == file.length() && time == file.lastModified()) { "资料缓存正在变化，请重试。" }
                fingerprints[file.name] = Fingerprint(bytes, time, digest)
                total += bytes
                modified = maxOf(modified, time)
                append('|').append(file.name).append(':').append(bytes).append(':').append(time).append(':').append(digest)
            }
        }
        return AuxiliaryCacheEntry(key, total, modified, sha256(identity.encodeToByteArray())
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') })
    }

    /** The owner must have compared [entry] with verifyBytes=true immediately before this call. */
    fun removeReviewed(entry: AuxiliaryCacheEntry): Long {
        val files = family(entry.key).filter { it.isFile }
        val before = files.sumOf { it.length() }
        files.forEach { it.delete() }
        val after = files.sumOf { if (it.isFile) it.length() else 0L }
        changed(entry.key)
        val freed = (before - after).coerceAtLeast(0L)
        if (files.any { it.isFile }) throw AuxiliaryCacheRemovalException(freed)
        return freed
    }

    private fun family(key: String): List<StorageFile> =
        listOf(key, "$key.new", "$key.bak").map { StorageFile(directory, it) }
}
