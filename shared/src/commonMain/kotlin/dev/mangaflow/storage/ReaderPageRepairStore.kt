package dev.mangaflow.storage

import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.sha256
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.dataDirectory
import dev.mangaflow.platform.storage.getSharedPreferences
import dev.mangaflow.platform.wallTimeMillis
import kotlinx.atomicfu.locks.synchronized

/** A human-confirmed single-page relation, never an inferred chapter-wide offset. */
internal data class ReaderPageAlignment(
    val workId: String,
    val logicalChapterId: String,
    val primaryChapterId: String,
    val primaryManifest: String,
    val primaryEnvironment: String,
    val primaryIndex: Int,
    val targetVariantId: String,
    val targetManifest: String,
    val targetEnvironment: String,
    val targetIndex: Int,
    val confirmedAt: Long = wallTimeMillis(),
    val revision: Long = 0L,
)

/** Metadata only. Original manifests, image caches, native progress and downloads stay unchanged. */
internal class ReaderPageRepairStore(context: PlatformContext) {
    private val preferences = context.getSharedPreferences("mangaflow_library")
    private val storagePath = dataDirectory(context).absolutePath
    fun read(workId: String, chapterId: String, index: Int): ReaderPageAlignment? = synchronized(lock) {
        root(confirmedRecord(readRecord())).optJSONObject(key(workId, chapterId, index))?.let(::decode)
    }

    /** Compare the exact reviewed relation, so a late confirmation never overwrites a newer edit. */
    fun save(value: ReaderPageAlignment, expectedRevision: Long?): ReaderPageAlignment = synchronized(lock) {
        val observed = readRecord()
        val before = confirmedRecord(observed)
        val root = root(before)
        val key = key(value.workId, value.primaryChapterId, value.primaryIndex)
        val previous = root.optJSONObject(key)?.let(::decode)
        check(previous?.revision == expectedRevision) { "这一页的对齐已变化，请重新确认。" }
        val saved = value.copy(revision = (before.revision ?: 0L).let {
            check(it < Long.MAX_VALUE) { "对齐版本已达到存储上限。" }; it + 1L
        })
        root.put(key, encode(saved))
        persist(observed, before, RepairRecord(root.toString(), saved.revision), "对齐未能保存，请检查存储空间。")
        saved
    }

    fun remove(value: ReaderPageAlignment): Boolean = synchronized(lock) {
        val observed = readRecord()
        val before = confirmedRecord(observed)
        val root = root(before)
        val key = key(value.workId, value.primaryChapterId, value.primaryIndex)
        if (root.optJSONObject(key)?.let(::decode)?.revision != value.revision) return@synchronized false
        root.remove(key)
        persist(observed, before, before.copy(content = root.toString()), "撤回未能保存，请重试。")
        true
    }

    private data class RepairRecord(val content: String?, val revision: Long?)
    private data class FailedWrite(val before: RepairRecord, val candidate: RepairRecord)

    /** Both keys come from one native snapshot, including restores not yet observed by listeners. */
    private fun readRecord(): RepairRecord = preferences.all.let { values ->
        RepairRecord(values[KEY] as? String, (values[REVISION] as? Number)?.toLong())
    }

    private fun confirmedRecord(record: RepairRecord): RepairRecord {
        val failed = failedWrites[storagePath] ?: return record
        if (record == failed.candidate) return failed.before
        failedWrites.remove(storagePath)
        return record
    }

    private fun writeRecord(record: RepairRecord): Boolean = preferences.edit().apply {
        if (record.content == null) remove(KEY) else putString(KEY, record.content)
        if (record.revision == null) remove(REVISION) else putLong(REVISION, record.revision)
    }.commit()

    private fun persist(observed: RepairRecord, before: RepairRecord, candidate: RepairRecord, message: String) {
        check(readRecord() == observed) { "对齐记录已变化，请重新确认。" }
        try {
            check(writeRecord(candidate)) { message }
        } catch (failure: Throwable) {
            // commit may update memory before failing. Keep the last confirmed record readable
            // even if restoring that memory also fails; another store instance shares this fence.
            failedWrites[storagePath] = FailedWrite(before, candidate)
            if (runCatching { readRecord() == candidate }.getOrDefault(false)) {
                // A later external edit wins. Never restore over a different observed record.
                runCatching { writeRecord(before) }
            }
            throw failure
        }
        failedWrites.remove(storagePath)
        check(readRecord() == candidate) { "对齐记录已变化，请重新确认。" }
    }

    private fun root(record: RepairRecord) = JSONObject(record.content ?: "{}")
    private fun encode(v: ReaderPageAlignment) = JSONObject()
        .put("work", v.workId).put("logical", v.logicalChapterId).put("primary", v.primaryChapterId)
        .put("manifest", v.primaryManifest).put("environment", v.primaryEnvironment).put("page", v.primaryIndex)
        .put("target", v.targetVariantId).put("targetManifest", v.targetManifest)
        .put("targetEnvironment", v.targetEnvironment).put("targetPage", v.targetIndex)
        .put("confirmed", v.confirmedAt).put("revision", v.revision)
    private fun decode(v: JSONObject): ReaderPageAlignment? = runCatching {
        ReaderPageAlignment(v.getString("work"), v.getString("logical"), v.getString("primary"),
            v.getString("manifest"), v.getString("environment"), v.getInt("page"),
            v.getString("target"), v.getString("targetManifest"), v.getString("targetEnvironment"),
            v.getInt("targetPage"), v.getLong("confirmed"), v.getLong("revision"))
            .takeIf { it.primaryIndex >= 0 && it.targetIndex >= 0 && it.revision > 0L }
    }.getOrNull()

    companion object {
        private const val KEY = "reader_page_repairs_v1"
        private const val REVISION = "reader_page_repairs_revision"
        private val lock = StorageLock()
        private val failedWrites = mutableMapOf<String, FailedWrite>()
        private fun key(work: String, chapter: String, index: Int) =
            sha256("${work.length}:$work${chapter.length}:$chapter:$index".encodeToByteArray())
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
}
