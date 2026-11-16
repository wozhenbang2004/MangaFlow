package dev.mangaflow.net

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.dataDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer

/** Display-only history. No route endpoints, Binder grants, request payloads, fences or apply selections. */
internal class NetworkAutoHistoryStore(context: PlatformContext) {
    private val file = StorageAtomicFile(StorageFile(dataDirectory(context), "network-check/last-report.json"))
    private val writer = Mutex()
    private var completed: JSONObject? = null
    private var attempt: JSONObject? = null

    suspend fun load(): NetworkAutoCheckState = withContext(Dispatchers.IO) { writer.withLock {
        try {
            if (!file.baseFile.exists() && !StorageFile(file.baseFile.path + ".bak").exists())
                return@withLock NetworkAutoCheckState(restoring = false)
            val bytes = file.openRead().use { input ->
                val output = Buffer()
                val block = ByteArray(8192)
                while (true) {
                    val count = input.read(block)
                    if (count < 0) break
                    check(output.size + count <= MAX_BYTES)
                    output.write(block, 0, count)
                }
                output.readByteArray()
            }
            val root = JSONObject(bytes.decodeToString())
            check(root.optInt("schema") == 1)
            completed = root.optJSONObject("completed")
            attempt = root.optJSONObject("attempt")
            val report = completed?.let(::decode)
            val interrupted = attempt?.optString("phase") == "RUNNING"
            val id = attempt?.optString("id")?.takeIf { it.matches(ID) }
            (report ?: NetworkAutoCheckState(runId = id,
                startedAtMillis = attempt?.optLong("startedAt")?.takeIf { it > 0 })).copy(
                restoring = false, historyOnly = report != null || attempt != null,
                interrupted = interrupted, stale = report != null || attempt != null, complete = false,
                staleReason = if (report != null) "上次报告仅供查看，请重新检测后确认修改" else null,
                error = if (interrupted) {
                    if (report != null) "上次检测已中断，保留最近一次完整报告" else "上次检测已中断，请重新检测"
                } else null,
                selection = emptyMap())
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { NetworkAutoCheckState(restoring = false, historyOnly = true, stale = true,
            historySaved = false, error = "上次检测报告未能读取，请重新检测") }
    } }

    suspend fun begin(id: String, startedAt: Long): Boolean = withContext(Dispatchers.IO) { writer.withLock {
        attempt = JSONObject().put("id", id).put("startedAt", startedAt).put("phase", "RUNNING")
        save()
    } }

    suspend fun finish(state: NetworkAutoCheckState): Boolean = withContext(Dispatchers.IO) { writer.withLock {
        try {
            // The one operation owner finalizes queued cancellation even when work never reached begin().
            if (state.complete || state.applied) completed = encode(state)
            attempt = JSONObject().put("id", state.runId).put("startedAt", state.startedAtMillis)
                .put("finishedAt", state.finishedAtMillis).put("phase", when {
                    state.complete || state.applied -> "COMPLETED"
                    state.interrupted -> "INTERRUPTED"
                    state.stale -> "STALE"
                    else -> "STOPPED"
                })
            save()
        } catch (_: Exception) { false }
    } }

    suspend fun applied(state: NetworkAutoCheckState): Boolean = withContext(Dispatchers.IO) { writer.withLock {
        try {
            if (completed?.optString("id") != state.runId) return@withLock false
            completed = encode(state)
            save()
        } catch (_: Exception) { false }
    } }

    private fun save(): Boolean = try {
        val root = JSONObject().put("schema", 1)
        completed?.let { root.put("completed", it) }
        attempt?.let { root.put("attempt", it) }
        val bytes = root.toString().encodeToByteArray()
        check(bytes.size <= MAX_BYTES)
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Exception) { file.failWrite(output); throw failure }
        true
    } catch (_: Exception) { false }

    private fun encode(state: NetworkAutoCheckState): JSONObject {
        check(state.runId?.matches(ID) == true && state.finishedAtMillis != null)
        check(state.rows.size <= 256 && state.rows.all { it.direct?.confirming == false && it.proxy?.confirming == false })
        val rows = JSONArray()
        state.rows.forEach { row ->
            val item = JSONObject().put("id", row.id).put("label", safeText(row.label, 140))
                .put("group", row.group.name).put("coverage", safeText(row.coverage, 300))
                .put("reason", safeText(row.reason, 220))
            row.currentRoute?.mode?.let { item.put("currentMode", it.name) }
            row.recommendation?.let { item.put("recommendedMode", it.name) }
            row.category?.let { item.put("category", it.name) }
            row.sourceId?.let { item.put("sourceId", it) }
            row.ratingId?.let { item.put("ratingId", it) }
            row.direct?.let { item.put("direct", encodeResult(it)) }
            row.proxy?.let { item.put("proxy", encodeResult(it)) }
            rows.put(item)
        }
        return JSONObject().put("id", state.runId).put("startedAt", state.startedAtMillis)
            .put("finishedAt", state.finishedAtMillis).put("rows", rows).put("applied", state.applied)
            .put("applyMessage", state.applyMessage?.let { safeText(it, 220) })
    }

    private fun encodeResult(result: NetworkAutoRouteResult): JSONObject = JSONObject()
        .put("status", result.status.name).put("millis", result.millis).put("samples", result.samples)
        .put("message", safeText(result.message, 260)).put("httpStatus", result.httpStatus)

    private fun decode(root: JSONObject): NetworkAutoCheckState {
        val id = root.getString("id").also { check(it.matches(ID)) }
        val started = root.getLong("startedAt").also { check(it > 0) }
        val finished = root.getLong("finishedAt").also { check(it > 0) }
        val array = root.getJSONArray("rows").also { check(it.length() <= 256) }
        val rows = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            NetworkAutoRow(id = item.getString("id").also { check(it.matches(Regex("[A-Za-z0-9_.-]{1,160}"))) },
                label = safeText(item.getString("label"), 140),
                currentRoute = mode(item.optString("currentMode"))?.let { DownloadRouteSnapshot(it) },
                direct = item.optJSONObject("direct")?.let(::decodeResult), proxy = item.optJSONObject("proxy")?.let(::decodeResult),
                recommendation = mode(item.optString("recommendedMode")), reason = safeText(item.optString("reason"), 220),
                coverage = safeText(item.optString("coverage"), 300),
                category = DownloadCategory.entries.firstOrNull { it.name == item.optString("category") },
                sourceId = item.optString("sourceId").takeIf(String::isNotBlank),
                group = NetworkAutoGroup.valueOf(item.getString("group")),
                ratingId = item.optString("ratingId").takeIf(String::isNotBlank), canApply = false)
        }
        return NetworkAutoCheckState(rows, rows.size, rows.size, runId = id, startedAtMillis = started,
            finishedAtMillis = finished, applied = root.optBoolean("applied"),
            applyMessage = root.optString("applyMessage").takeIf(String::isNotBlank))
    }

    private fun decodeResult(item: JSONObject): NetworkAutoRouteResult = NetworkAutoRouteResult(
        status = NetworkAutoRouteStatus.valueOf(item.getString("status")),
        millis = item.optLong("millis", -1).takeIf { it in 0..3_600_000L },
        samples = item.optInt("samples").also { check(it in 0..2) },
        message = safeText(item.optString("message"), 260), httpStatus = item.optInt("httpStatus").takeIf { it in 100..599 })

    private fun mode(name: String): DownloadRouteMode? = DownloadRouteMode.entries.firstOrNull { it.name == name }
    private fun safeText(text: String, limit: Int): String = if ("://" in text || Regex("(?i)bearer |authorization:|password=|token=").containsMatchIn(text))
        "详细信息未写入历史报告" else text.filter { it.code >= 32 || it == '\n' }.take(limit)

    companion object {
        private const val MAX_BYTES = 1024 * 1024
        private val ID = Regex("[a-fA-F0-9-]{36}")
    }
}
