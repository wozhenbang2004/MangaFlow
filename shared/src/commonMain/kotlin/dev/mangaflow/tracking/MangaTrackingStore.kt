package dev.mangaflow.tracking

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.filesDir

internal data class TrackingRecords(
    val bindings: List<MangaTrackingBinding> = emptyList(),
    val pending: List<MangaTrackingPending> = emptyList(),
)

/** Account tokens live in platform secrets, never in this progress/outbox document. */
internal class MangaTrackingStore(context: PlatformContext) {
    private val file = StorageFile(context.filesDir, "manga-tracking.json")
    private val atomic = StorageAtomicFile(file)

    fun read(): TrackingRecords {
        if (!file.exists() && !StorageFile(file.path + ".bak").exists()) return TrackingRecords()
        require(maxOf(file.length(), StorageFile(file.path + ".bak").length()) <= 8L * 1024 * 1024) {
            "漫画进度文件过大，已保留原文件并停止自动同步。"
        }
        val json = atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }
        require(json.optInt("schema") == 1) { "漫画进度文件版本无法识别，原文件未覆盖。" }
        val bindings = json.requiredArray("bindings").strictObjects().map { value ->
            MangaTrackingBinding(
                value.getString("mangaId"), value.getString("mangaTitle"),
                MangaTrackingProvider.valueOf(value.getString("provider")), value.getString("ownerId"),
                subject(value.requiredObject("subject")), value.optJSONObject("numbering")?.let(::numbering),
                value.optBoolean("autoSync"), progress(value.requiredObject("local")),
                value.optJSONObject("remote")?.let(::snapshot),
                value.requiredArray("completed").let { array -> (0 until array.length()).map {
                    array.optString(it).toIntOrNull()?.takeIf { number -> number > 0 }
                        ?: error("漫画完读编号无效，原文件未覆盖。")
                }.toSet() },
            ).also {
                require(it.mangaId.isNotBlank() && it.ownerId.toIntOrNull()?.let { id -> id > 0 } == true)
                require(it.remote == null || it.remote.provider == it.provider &&
                    it.remote.ownerId == it.ownerId && it.remote.subject.id == it.subject.id)
                require(!it.autoSync || it.numbering != null && it.remote != null)
            }
        }
        require(bindings.map { it.key }.distinct().size == bindings.size)
        val pending = json.requiredArray("pending").strictObjects().map { value ->
            MangaTrackingPending(
                bindingKey = value.getString("bindingKey"), token = value.getString("token"),
                expected = snapshot(value.requiredObject("expected")), desired = progress(value.requiredObject("desired")),
                automatic = value.optBoolean("automatic"), submitted = value.optBoolean("submitted"),
                attempts = value.optInt("attempts").coerceAtLeast(0), retryAt = value.optLong("retryAt"),
                blocked = value.optBoolean("blocked"), message = value.optString("message").takeIf(String::isNotBlank),
            ).also { item ->
                val binding = bindings.singleOrNull { it.key == item.bindingKey }
                require(binding != null && item.token.isNotBlank() && item.desired.status != null &&
                    item.expected.provider == binding.provider && item.expected.ownerId == binding.ownerId &&
                    item.expected.subject.id == binding.subject.id)
            }
        }
        require(pending.map { it.bindingKey }.distinct().size == pending.size)
        return TrackingRecords(bindings, pending)
    }

    fun write(records: TrackingRecords) {
        val json = JSONObject().put("schema", 1)
            .put("bindings", JSONArray(records.bindings.map { value ->
                JSONObject().put("mangaId", value.mangaId).put("mangaTitle", value.mangaTitle)
                    .put("provider", value.provider.name).put("ownerId", value.ownerId)
                    .put("subject", subjectJson(value.subject))
                    .put("numbering", value.numbering?.let(::numberingJson) ?: JSONObject.NULL)
                    .put("autoSync", value.autoSync).put("local", progressJson(value.local))
                    .put("remote", value.remote?.let(::snapshotJson) ?: JSONObject.NULL)
                    .put("completed", JSONArray(value.completedNumbers.sorted()))
            }))
            .put("pending", JSONArray(records.pending.map { value ->
                JSONObject().put("bindingKey", value.bindingKey).put("token", value.token)
                    .put("expected", snapshotJson(value.expected)).put("desired", progressJson(value.desired))
                    .put("automatic", value.automatic).put("submitted", value.submitted)
                    .put("attempts", value.attempts).put("retryAt", value.retryAt).put("blocked", value.blocked)
                    .put("message", value.message ?: JSONObject.NULL)
            }))
        val output = atomic.startWrite()
        try {
            output.write(json.toString().encodeToByteArray())
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }
}

private fun progressJson(value: MangaTrackingProgress): JSONObject = JSONObject()
    .put("status", value.status?.name ?: JSONObject.NULL).put("chapters", value.chapters).put("volumes", value.volumes)
private fun progress(value: JSONObject): MangaTrackingProgress = MangaTrackingProgress(
    value.optString("status").takeIf(String::isNotBlank)?.let { MangaTrackingStatus.valueOf(it) },
    value.requiredNonnegative("chapters"), value.requiredNonnegative("volumes"))
private fun subjectJson(value: MangaTrackingSubject): JSONObject = JSONObject().put("id", value.id)
    .put("title", value.title).put("subtitle", value.subtitle).put("url", value.url)
    .put("chapters", value.chapters ?: JSONObject.NULL).put("volumes", value.volumes ?: JSONObject.NULL)
private fun subject(value: JSONObject): MangaTrackingSubject = MangaTrackingSubject(
    value.getString("id").also { require(it.toIntOrNull()?.let { id -> id > 0 } == true) },
    value.getString("title"), value.getString("subtitle"), value.getString("url"),
    value.optInt("chapters").takeIf { it > 0 }, value.optInt("volumes").takeIf { it > 0 })
private fun numberingJson(value: MangaTrackingNumbering): JSONObject =
    JSONObject().put("unit", value.unit.name).put("branch", value.branch)
private fun numbering(value: JSONObject): MangaTrackingNumbering =
    MangaTrackingNumbering(MangaTrackingUnit.valueOf(value.getString("unit")), value.getString("branch"))
private fun snapshotJson(value: MangaTrackingSnapshot): JSONObject = JSONObject()
    .put("provider", value.provider.name).put("ownerId", value.ownerId).put("subject", subjectJson(value.subject))
    .put("progress", progressJson(value.progress)).put("revision", value.revision).put("readAt", value.readAt)
    .put("entryId", value.entryId ?: JSONObject.NULL)
private fun snapshot(value: JSONObject): MangaTrackingSnapshot = MangaTrackingSnapshot(
    MangaTrackingProvider.valueOf(value.getString("provider")), value.getString("ownerId"),
    subject(value.requiredObject("subject")), progress(value.requiredObject("progress")),
    value.getString("revision").also { require(it.matches(Regex("[0-9a-f]{64}"))) }, value.getLong("readAt"),
    value.optInt("entryId").takeIf { it > 0 })

private fun JSONArray.strictObjects(): List<JSONObject> = (0 until length()).map { index ->
    optJSONObject(index) ?: error("漫画进度记录不完整，原文件未覆盖。")
}
