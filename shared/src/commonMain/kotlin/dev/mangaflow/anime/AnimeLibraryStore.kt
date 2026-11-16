package dev.mangaflow.anime

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.filesDir
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AnimeUndo(
    val subjectId: String,
    val before: AnimeLibraryEntry?,
    val bindings: List<AnimeSourceBinding>,
    val expectedRevision: Long,
)

internal data class AnimeLibraryState(
    val entries: Map<String, AnimeLibraryEntry> = emptyMap(),
    val bindings: List<AnimeSourceBinding> = emptyList(),
    val progress: Map<String, AnimeWatchProgress> = emptyMap(),
    val trackingTargets: Map<String, String> = emptyMap(),
    val undo: AnimeUndo? = null,
)

/** One atomic document owns watch state and reviewed associations, including their undo receipt. */
internal class AnimeLibraryStore(context: PlatformContext, scope: CoroutineScope) {
    private val file = StorageAtomicFile(StorageFile(context.filesDir, "anime-library.json"))
    private val writer = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var readFailure: Throwable? = null
    private val mutableState = MutableStateFlow(AnimeLibraryState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                mutableState.value = withContext(Dispatchers.IO) {
                    if (!file.baseFile.exists() && !StorageFile(file.baseFile.path + ".bak").exists())
                        AnimeLibraryState()
                    else decode(file.openRead().use { it.bufferedReader().readText() })
                }
            } catch (failure: Exception) {
                // A damaged file is retained. No mutation may overwrite it with an empty library.
                readFailure = failure
            } finally { ready.complete(Unit) }
        }
    }

    suspend fun awaitReady() {
        ready.await()
        check(readFailure == null) { "追番记录未能读取，原文件已保留；请检查存储空间后重启。" }
    }

    suspend fun edit(
        subject: AnimeSubject,
        expectedRevision: Long? = null,
        rememberUndo: Boolean = true,
        editBindings: (List<AnimeSourceBinding>) -> List<AnimeSourceBinding> = { it },
        transform: (AnimeLibraryEntry) -> AnimeLibraryEntry = { it },
    ): AnimeLibraryEntry {
        awaitReady()
        return writer.withLock {
            val before = mutableState.value
            val previous = before.entries[subject.id]
            val current = previous ?: AnimeLibraryEntry(subject)
            check(expectedRevision == null || current.revision == expectedRevision) {
                "这部番的本地记录已变化，请重新核对后应用。"
            }
            val changed = transform(current).copy(
                subject = subject, revision = current.revision + 1L, updatedAt = wallTimeMillis())
            require(changed.subject.id == subject.id)
            val bindings = editBindings(before.bindings.filter { it.subjectId == subject.id })
            require(bindings.all { it.subjectId == subject.id })
            require(bindings.map { it.catalog.sourceId }.distinct().size == bindings.size)
            val next = before.copy(
                entries = before.entries + (subject.id to changed),
                bindings = before.bindings.filterNot { it.subjectId == subject.id } + bindings,
                undo = if (rememberUndo) AnimeUndo(subject.id, previous,
                    before.bindings.filter { it.subjectId == subject.id }, changed.revision) else before.undo,
            )
            persist(next)
            changed
        }
    }

    suspend fun checkpoint(
        subject: AnimeSubject,
        progress: AnimeWatchProgress,
        isCurrent: () -> Boolean,
    ) {
        awaitReady()
        writer.withLock {
            if (!isCurrent()) return@withLock
            val before = mutableState.value
            val entry = before.entries[subject.id] ?: AnimeLibraryEntry(subject)
            val previous = before.progress[progress.episodeId]
            if (previous != null && previous.updatedAt > progress.updatedAt) return@withLock
            val completed = entry.completedEpisodeIds + if (progress.completed) setOf(progress.episodeId) else emptySet()
            val changed = entry.copy(
                subject = subject, resume = progress, completedEpisodeIds = completed,
                updatedAt = progress.updatedAt,
                revision = entry.revision + if (completed != entry.completedEpisodeIds) 1L else 0L,
            )
            persist(before.copy(entries = before.entries + (subject.id to changed),
                progress = before.progress + (progress.episodeId to progress)))
        }
    }

    suspend fun setTrackingTarget(subjectId: String, providerId: String, ownerId: String, remoteId: String) {
        awaitReady()
        writer.withLock {
            val before = mutableState.value
            persist(before.copy(trackingTargets = before.trackingTargets +
                (trackingKey(subjectId, providerId, ownerId) to remoteId)))
        }
    }

    fun trackingTarget(subjectId: String, providerId: String, ownerId: String): String? =
        mutableState.value.trackingTargets[trackingKey(subjectId, providerId, ownerId)]

    suspend fun undo(): Boolean {
        awaitReady()
        return writer.withLock {
            val before = mutableState.value
            val undo = before.undo ?: return@withLock false
            val current = before.entries[undo.subjectId] ?: return@withLock false
            if (current.revision != undo.expectedRevision) return@withLock false
            // Position checkpoints belong to playback, not to the reviewed field/mapping edit.
            val restored = (undo.before ?: AnimeLibraryEntry(current.subject)).copy(
                subject = current.subject, resume = current.resume, updatedAt = wallTimeMillis(),
                revision = current.revision + 1L,
            )
            persist(before.copy(entries = before.entries + (undo.subjectId to restored),
                bindings = before.bindings.filterNot { it.subjectId == undo.subjectId } + undo.bindings,
                undo = null))
            true
        }
    }

    /** Once publication begins it must finish before another writer can observe the new revision. */
    private suspend fun persist(next: AnimeLibraryState) = withContext(NonCancellable + Dispatchers.IO) {
        val bytes = encode(next).encodeToByteArray()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw IllegalStateException("追番记录未能保存，旧记录仍然保留。请检查存储空间后重试。", failure)
        }
        mutableState.value = next
    }

    private fun encode(state: AnimeLibraryState): String = JSONObject()
        .put("format", 1)
        .put("entries", JSONArray(state.entries.values.map(::encodeEntry)))
        .put("bindings", JSONArray(state.bindings.map(::encodeBinding)))
        .put("progress", JSONArray(state.progress.values.map(::encodeProgress)))
        .put("trackingTargets", JSONObject(state.trackingTargets))
        .apply {
            state.undo?.let { undo ->
                put("undo", JSONObject().put("subjectId", undo.subjectId)
                    .put("revision", undo.expectedRevision).put("before", undo.before?.let(::encodeEntry))
                    .put("bindings", JSONArray(undo.bindings.map(::encodeBinding))))
            }
        }.toString()

    private fun decode(text: String): AnimeLibraryState {
        val json = JSONObject(text)
        check(json.getInt("format") == 1) { "追番记录版本无法识别。" }
        val entries = objects(json.getJSONArray("entries")).map(::decodeEntry).associateBy { it.subject.id }
        val bindings = objects(json.getJSONArray("bindings")).map(::decodeBinding)
        val progress = objects(json.getJSONArray("progress")).map(::decodeProgress).associateBy { it.episodeId }
        val targets = json.optJSONObject("trackingTargets") ?: JSONObject()
        val undo = json.optJSONObject("undo")?.let {
            AnimeUndo(it.getString("subjectId"), it.optJSONObject("before")?.let(::decodeEntry),
                objects(it.getJSONArray("bindings")).map(::decodeBinding), it.getLong("revision"))
        }
        return AnimeLibraryState(entries, bindings, progress,
            targets.keys().asSequence().associateWith { targets.getString(it) }, undo)
    }

    private fun encodeEntry(entry: AnimeLibraryEntry) = JSONObject()
        .put("subject", encodeSubject(entry.subject)).put("status", entry.status.name)
        .put("followed", entry.followed).put("completed", JSONArray(entry.completedEpisodeIds.toList()))
        .put("resume", entry.resume?.let(::encodeProgress)).put("updated", entry.updatedAt)
        .put("revision", entry.revision)

    private fun decodeEntry(json: JSONObject) = AnimeLibraryEntry(
        subject = decodeSubject(json.getJSONObject("subject")),
        status = AnimeWatchStatus.valueOf(json.getString("status")),
        followed = json.getBoolean("followed"),
        completedEpisodeIds = strings(json.getJSONArray("completed")).toSet(),
        resume = json.optJSONObject("resume")?.let(::decodeProgress),
        updatedAt = json.getLong("updated"), revision = json.getLong("revision"),
    )

    private fun encodeSubject(subject: AnimeSubject) = JSONObject().put("id", subject.id)
        .put("title", subject.title).put("original", subject.originalTitle).put("cover", subject.coverUrl)
        .put("description", subject.description).put("count", subject.episodeCount).put("year", subject.year)
        .put("tags", JSONArray(subject.tags)).put("externalIds", JSONObject(subject.externalIds))

    private fun decodeSubject(json: JSONObject): AnimeSubject {
        val ids = json.optJSONObject("externalIds") ?: JSONObject()
        return AnimeSubject(json.getString("id"), json.getString("title"),
            json.optString("original"), json.optString("cover"), json.optString("description"),
            if (json.isNull("count")) null else json.getInt("count"),
            if (json.isNull("year")) null else json.getInt("year"),
            strings(json.optJSONArray("tags") ?: JSONArray()),
            ids.keys().asSequence().associateWith { ids.getString(it) })
    }

    private fun encodeBinding(binding: AnimeSourceBinding) = JSONObject().put("subject", binding.subjectId)
        .put("source", binding.catalog.sourceId).put("id", binding.catalog.id)
        .put("title", binding.catalog.title).put("url", binding.catalog.url)
        .put("cover", binding.catalog.coverUrl).put("year", binding.catalog.year)
        .put("count", binding.catalog.episodeCount).put("externalIds", JSONObject(binding.catalog.externalIds))
        .put("episodes", JSONObject(binding.episodeMappings)).put("preferred", binding.preferred)
        .put("group", binding.selectedGroup)

    private fun decodeBinding(json: JSONObject): AnimeSourceBinding {
        val ids = json.optJSONObject("externalIds") ?: JSONObject()
        val mappings = json.getJSONObject("episodes")
        return AnimeSourceBinding(json.getString("subject"), AnimeSourceTitle(
            json.getString("source"), json.getString("id"), json.getString("title"), json.getString("url"),
            json.optString("cover"), if (json.isNull("year")) null else json.getInt("year"),
            if (json.isNull("count")) null else json.getInt("count"),
            ids.keys().asSequence().associateWith { ids.getString(it) }),
            mappings.keys().asSequence().associateWith { mappings.getString(it) },
            json.optBoolean("preferred"), json.optString("group", null))
    }

    private fun encodeProgress(progress: AnimeWatchProgress) = JSONObject().put("episode", progress.episodeId)
        .put("asset", progress.assetId).put("source", progress.sourceId).put("position", progress.positionMs)
        .put("duration", progress.durationMs).put("updated", progress.updatedAt).put("completed", progress.completed)

    private fun decodeProgress(json: JSONObject) = AnimeWatchProgress(
        json.getString("episode"), json.getString("asset"), json.getString("source"),
        json.getLong("position").coerceAtLeast(0L), json.getLong("duration").coerceAtLeast(0L),
        json.getLong("updated"), json.optBoolean("completed"))

    private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map(array::getJSONObject)
    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)
    private fun trackingKey(subjectId: String, providerId: String, ownerId: String): String =
        JSONObject().put("subject", subjectId).put("provider", providerId).put("owner", ownerId).toString()
}
