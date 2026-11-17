package dev.mangaflow.anime

import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.anime.player.AnimeVideoEnhancementMode
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.filesDir
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.background.ApplicationOperations
import kotlinx.atomicfu.locks.synchronized
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
    val settings: AnimePlaybackSettings = AnimePlaybackSettings(),
    val searchHistory: List<String> = emptyList(),
    val dismissedResume: Map<String, Long> = emptyMap(),
    val clearedHistoryAt: Map<String, Long> = emptyMap(),
    val discoveryColumns: Int? = null,
    /** Process-local authority. Old playback cannot write through a backup replacement. */
    val restoreEpoch: Long = 0L,
    /** No persisted records are known; entries/bindings are session-only playback associations. */
    val readUnavailable: Boolean = false,
)

/** One atomic document owns watch state and reviewed associations, including their undo receipt. */
internal class AnimeLibraryStore private constructor(context: PlatformContext, scope: CoroutineScope) {
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
                mutableState.value = AnimeLibraryState(readUnavailable = true)
            } finally { ready.complete(Unit) }
        }
    }

    suspend fun awaitLoaded() { ready.await() }

    suspend fun awaitReady() {
        ready.await()
        check(readFailure == null) { "本地追番记录读取失败，原文件已保留；当前不能保存修改。请检查存储空间或通过备份恢复。" }
    }

    suspend fun edit(
        subject: AnimeSubject,
        expectedRevision: Long? = null,
        rememberUndo: Boolean = true,
        editBindings: (List<AnimeSourceBinding>) -> List<AnimeSourceBinding> = { it },
        transform: (AnimeLibraryEntry) -> AnimeLibraryEntry = { it },
    ): AnimeLibraryEntry {
        return editInternal(subject, expectedRevision, rememberUndo, editBindings, transform, sessionAllowed = false)
    }

    /** Online identity discovery may continue without claiming to recover any old user choices. */
    suspend fun editPlaybackAssociation(
        subject: AnimeSubject,
        editBindings: (List<AnimeSourceBinding>) -> List<AnimeSourceBinding>,
    ): AnimeLibraryEntry = editInternal(subject, null, false, editBindings, { it }, sessionAllowed = true)

    private suspend fun editInternal(
        subject: AnimeSubject, expectedRevision: Long?, rememberUndo: Boolean,
        editBindings: (List<AnimeSourceBinding>) -> List<AnimeSourceBinding>,
        transform: (AnimeLibraryEntry) -> AnimeLibraryEntry, sessionAllowed: Boolean,
    ): AnimeLibraryEntry {
        if (sessionAllowed) awaitLoaded() else awaitReady()
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
            if (readFailure != null) {
                check(sessionAllowed)
                mutableState.value = next
            } else persist(next)
            changed
        }
    }

    suspend fun checkpoint(
        subject: AnimeSubject,
        progress: AnimeWatchProgress,
        isCurrent: () -> Boolean,
    ): Boolean {
        awaitLoaded()
        if (state.value.readUnavailable) return false
        return writer.withLock {
            if (!isCurrent()) return@withLock false
            val before = mutableState.value
            if (progress.updatedAt <= (before.clearedHistoryAt[subject.id] ?: -1L)) return@withLock false
            val entry = before.entries[subject.id] ?: AnimeLibraryEntry(subject)
            val previous = before.progress[progress.episodeId]
            if (previous != null && previous.updatedAt > progress.updatedAt) return@withLock false
            val completed = entry.completedEpisodeIds + if (progress.completed) setOf(progress.episodeId) else emptySet()
            val changed = entry.copy(
                subject = subject, resume = progress, completedEpisodeIds = completed,
                updatedAt = progress.updatedAt,
                revision = entry.revision + if (completed != entry.completedEpisodeIds) 1L else 0L,
            )
            persist(before.copy(entries = before.entries + (subject.id to changed),
                progress = before.progress + (progress.episodeId to progress)))
            true
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

    fun progressFor(subjectId: String, episodeId: String): AnimeWatchProgress? {
        val before = mutableState.value
        return before.progress[episodeId]?.takeIf { (it.subjectId.isEmpty() || it.subjectId == subjectId) &&
            it.updatedAt > (before.clearedHistoryAt[subjectId] ?: -1L) }
    }

    suspend fun setSettings(settings: AnimePlaybackSettings) = update { it.copy(settings = settings.validate()) }

    suspend fun setDiscoveryColumns(columns: Int) = update {
        require(columns >= 0) { "列数不能小于零。" }
        it.copy(discoveryColumns = columns)
    }

    suspend fun rememberSearch(query: String) = update {
        val text = query.trim()
        if (text.isEmpty()) it else it.copy(searchHistory = listOf(text) + it.searchHistory.filterNot { old -> old == text })
    }

    suspend fun clearSearchHistory() = update { it.copy(searchHistory = emptyList()) }

    suspend fun dismissResume(subjectId: String) = update { before ->
        val progress = before.entries[subjectId]?.resume
        if (progress == null) before else before.copy(dismissedResume = before.dismissedResume + (subjectId to progress.updatedAt))
    }

    suspend fun clearWatchHistory(subjectId: String, removeEntry: Boolean = false) = update { before ->
        val entry = before.entries[subjectId] ?: return@update before
        val knownEpisodes = entry.completedEpisodeIds + listOfNotNull(entry.resume?.episodeId)
        before.copy(entries = if (removeEntry) before.entries - subjectId else before.entries +
            (subjectId to entry.copy(resume = null, revision = entry.revision + 1, updatedAt = wallTimeMillis())),
            progress = before.progress.filterValues { it.subjectId != subjectId &&
                !(it.subjectId.isEmpty() && it.episodeId in knownEpisodes) },
            bindings = if (removeEntry) before.bindings.filterNot { it.subjectId == subjectId } else before.bindings,
            trackingTargets = if (removeEntry) before.trackingTargets.filterKeys {
                JSONObject(it).optString("subject") != subjectId
            } else before.trackingTargets,
            dismissedResume = before.dismissedResume - subjectId,
            clearedHistoryAt = before.clearedHistoryAt + (subjectId to wallTimeMillis()),
            undo = before.undo?.takeUnless { it.subjectId == subjectId })
    }

    suspend fun exportBackup(): JSONObject {
        awaitReady()
        return writer.withLock { JSONObject(encode(mutableState.value.copy(undo = null))) }
    }

    fun validateBackup(document: JSONObject): Int = decode(document.toString()).entries.size

    suspend fun restoreBackup(document: JSONObject) {
        val validated = decode(document.toString())
        ready.await()
        writer.withLock {
            // Publication and clearing the read fault are one commit even if the caller leaves.
            withContext(NonCancellable) {
                persist(validated.copy(undo = null, restoreEpoch = mutableState.value.restoreEpoch + 1))
                readFailure = null
            }
        }
    }

    private suspend fun update(transform: (AnimeLibraryState) -> AnimeLibraryState) {
        awaitReady()
        writer.withLock {
            val before = mutableState.value
            val next = transform(before)
            if (next != before) persist(next)
        }
    }

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
        .put("settings", encodeSettings(state.settings))
        .put("searchHistory", JSONArray(state.searchHistory))
        .put("dismissedResume", JSONObject(state.dismissedResume))
        .put("clearedHistoryAt", JSONObject(state.clearedHistoryAt))
        .put("discoveryColumns", state.discoveryColumns)
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
        val entryRows = objects(json.getJSONArray("entries")).map(::decodeEntry)
        val entries = entryRows.associateBy { it.subject.id }
        require(entries.size == entryRows.size) { "备份中存在重复的番剧身份。" }
        require(entries.values.all { entry -> entry.resume?.let {
            it.subjectId.isEmpty() || it.subjectId == entry.subject.id
        } != false }) { "续看记录属于另一部番，请检查备份。" }
        val bindings = objects(json.getJSONArray("bindings")).map(::decodeBinding)
        require(bindings.all { it.subjectId in entries && it.catalog.sourceId.isNotBlank() && it.catalog.id.isNotBlank() })
        require(bindings.map { it.subjectId to it.catalog.sourceId }.distinct().size == bindings.size)
        require(bindings.all { binding ->
            val subject = entries.getValue(binding.subjectId).subject
            val source = subject.externalIds["native-source"]
            source == null || (binding.catalog.sourceId == source && subject.id ==
                "native:$source:${dev.mangaflow.anime.sources.animeHash(binding.catalog.id + "\n" + binding.catalog.url)}")
        }) { "原始来源绑定与条目身份不一致。" }

        val progressRows = objects(json.getJSONArray("progress")).map(::decodeProgress)
        val progress = progressRows.associateBy { it.episodeId }
        require(progress.size == progressRows.size && progress.values.all { it.episodeId.isNotBlank() &&
            (it.subjectId.isEmpty() || it.subjectId in entries) })
        require(entries.values.all { entry -> entry.resume?.let { resume ->
            progress[resume.episodeId]?.let { it.subjectId.isEmpty() || it.subjectId == entry.subject.id } != false
        } != false }) { "观看位置与所属番剧不一致。" }
        val targets = json.optJSONObject("trackingTargets") ?: JSONObject()
        val undo = json.optJSONObject("undo")?.let {
            AnimeUndo(it.getString("subjectId"), it.optJSONObject("before")?.let(::decodeEntry),
                objects(it.getJSONArray("bindings")).map(::decodeBinding), it.getLong("revision"))
        }
        val dismissed = json.optJSONObject("dismissedResume") ?: JSONObject()
        val cleared = json.optJSONObject("clearedHistoryAt") ?: JSONObject()
        return AnimeLibraryState(entries, bindings, progress,
            targets.keys().asSequence().associateWith { key ->
                val target = JSONObject(key)
                require(target.getString("subject").isNotBlank() && target.getString("provider").isNotBlank() &&
                    target.getString("owner").isNotBlank())
                targets.getString(key)
            }, undo,
            settings = json.optJSONObject("settings")?.let(::decodeSettings) ?: AnimePlaybackSettings(),
            searchHistory = strings(json.optJSONArray("searchHistory") ?: JSONArray()).filter { it.isNotBlank() }.distinct(),
            dismissedResume = dismissed.keys().asSequence().associateWith { dismissed.getLong(it) },
            clearedHistoryAt = cleared.keys().asSequence().associateWith { cleared.getLong(it) },
            discoveryColumns = if (json.isNull("discoveryColumns")) null else json.getInt("discoveryColumns")
                .also { require(it >= 0) { "发现页列数无效。" } })
    }

    private fun encodeEntry(entry: AnimeLibraryEntry) = JSONObject()
        .put("subject", encodeSubject(entry.subject)).put("status", entry.status.name)
        .put("followed", entry.followed).put("completed", JSONArray(entry.completedEpisodeIds.toList()))
        .put("resume", entry.resume?.let(::encodeProgress)).put("updated", entry.updatedAt)
        .put("revision", entry.revision).put("folder", entry.folder).put("pinned", entry.pinned)
        .put("excludedSourceIds", JSONArray(entry.excludedSourceIds.sorted()))

    private fun decodeEntry(json: JSONObject) = AnimeLibraryEntry(
        subject = decodeSubject(json.getJSONObject("subject")),
        status = AnimeWatchStatus.valueOf(json.getString("status")),
        followed = json.getBoolean("followed"),
        completedEpisodeIds = strings(json.getJSONArray("completed")).toSet(),
        resume = json.optJSONObject("resume")?.let(::decodeProgress),
        updatedAt = json.getLong("updated"), revision = json.getLong("revision"),
        folder = json.optString("folder"), pinned = json.optBoolean("pinned"),
        excludedSourceIds = json.optJSONArray("excludedSourceIds")?.let { strings(it).toSet() } ?: emptySet(),
    )

    private fun encodeSubject(subject: AnimeSubject) = AnimeDocumentCodec.subject(subject)
    private fun decodeSubject(json: JSONObject) = AnimeDocumentCodec.subject(json)

    private fun encodeBinding(binding: AnimeSourceBinding) = JSONObject().put("subject", binding.subjectId)
        .put("source", binding.catalog.sourceId).put("id", binding.catalog.id)
        .put("title", binding.catalog.title).put("url", binding.catalog.url)
        .put("cover", binding.catalog.coverUrl).put("year", binding.catalog.year)
        .put("count", binding.catalog.episodeCount).put("externalIds", JSONObject(binding.catalog.externalIds))
        .put("updateText", binding.catalog.updateText).put("ratingText", binding.catalog.ratingText)
        .put("episodes", JSONObject(binding.episodeMappings)).put("preferred", binding.preferred)
        .put("group", binding.selectedGroup).put("automatic", binding.automatic)

    private fun decodeBinding(json: JSONObject): AnimeSourceBinding {
        val ids = json.optJSONObject("externalIds") ?: JSONObject()
        val mappings = json.getJSONObject("episodes")
        return AnimeSourceBinding(json.getString("subject"), AnimeSourceTitle(
            json.getString("source"), json.getString("id"), json.getString("title"), json.getString("url"),
            json.optString("cover"), if (json.isNull("year")) null else json.getInt("year"),
            if (json.isNull("count")) null else json.getInt("count"),
            ids.keys().asSequence().associateWith { ids.getString(it) },
            updateText = json.optString("updateText"), ratingText = json.optString("ratingText")),
            mappings.keys().asSequence().associateWith { mappings.getString(it) },
            json.optBoolean("preferred"), json.optString("group", null), automatic = json.optBoolean("automatic", false))
    }

    private fun encodeProgress(progress: AnimeWatchProgress) = JSONObject().put("episode", progress.episodeId)
        .put("asset", progress.assetId).put("source", progress.sourceId).put("position", progress.positionMs)
        .put("duration", progress.durationMs).put("updated", progress.updatedAt).put("completed", progress.completed)
        .put("subject", progress.subjectId)
        .put("localDownload", progress.localDownloadId)

    private fun decodeProgress(json: JSONObject) = AnimeWatchProgress(
        json.getString("episode"), json.getString("asset"), json.getString("source"),
        json.getLong("position").coerceAtLeast(0L), json.getLong("duration").coerceAtLeast(0L),
        json.getLong("updated"), json.optBoolean("completed"), json.optString("subject"),
        json.optString("localDownload", null)?.also { require(it.matches(Regex("[a-f0-9]{64}"))) })

    private fun encodeSettings(value: AnimePlaybackSettings) = JSONObject()
        .put("autoPlayNext", value.autoPlayNext).put("prepareNextEpisode", value.prepareNextEpisode)
        .put("skipOpeningSeconds", value.skipOpeningSeconds).put("skipEndingSeconds", value.skipEndingSeconds)
        .put("longPressSpeed", value.longPressSpeed.toDouble()).put("backgroundAudio", value.backgroundAudio)
        .put("automaticSourceRecovery", value.automaticSourceRecovery)
        .put("bufferMinMs", value.bufferMinMs).put("bufferMaxMs", value.bufferMaxMs)
        .put("bufferStartMs", value.bufferStartMs).put("bufferRebufferMs", value.bufferRebufferMs)
        .put("automaticRecoveryAttempts", value.automaticRecoveryAttempts)
        .put("videoEnhancement", value.videoEnhancement.name)

    private fun decodeSettings(json: JSONObject): AnimePlaybackSettings {
        val defaults = AnimePlaybackSettings()
        return AnimePlaybackSettings(
            autoPlayNext = json.optBoolean("autoPlayNext", defaults.autoPlayNext),
            prepareNextEpisode = json.optBoolean("prepareNextEpisode", defaults.prepareNextEpisode),
            skipOpeningSeconds = json.optInt("skipOpeningSeconds", defaults.skipOpeningSeconds),
            skipEndingSeconds = json.optInt("skipEndingSeconds", defaults.skipEndingSeconds),
            longPressSpeed = json.optDouble("longPressSpeed", defaults.longPressSpeed.toDouble()).toFloat(),
            backgroundAudio = json.optBoolean("backgroundAudio", defaults.backgroundAudio),
            automaticSourceRecovery = json.optBoolean("automaticSourceRecovery", defaults.automaticSourceRecovery),
            bufferMinMs = json.optInt("bufferMinMs", defaults.bufferMinMs),
            bufferMaxMs = json.optInt("bufferMaxMs", defaults.bufferMaxMs),
            bufferStartMs = json.optInt("bufferStartMs", defaults.bufferStartMs),
            bufferRebufferMs = json.optInt("bufferRebufferMs", defaults.bufferRebufferMs),
            automaticRecoveryAttempts = json.optInt("automaticRecoveryAttempts", defaults.automaticRecoveryAttempts),
            videoEnhancement = AnimeVideoEnhancementMode.entries.firstOrNull {
                it.name == json.optString("videoEnhancement")
            } ?: defaults.videoEnhancement,
        ).validate()
    }

    private fun objects(array: JSONArray): List<JSONObject> = (0 until array.length()).map(array::getJSONObject)
    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)
    private fun trackingKey(subjectId: String, providerId: String, ownerId: String): String =
        JSONObject().put("subject", subjectId).put("provider", providerId).put("owner", ownerId).toString()

    companion object {
        private val lock = StorageLock()
        private val instances = mutableMapOf<String, AnimeLibraryStore>()
        fun get(context: PlatformContext): AnimeLibraryStore = synchronized(lock) {
            instances.getOrPut(context.filesDir.path) { AnimeLibraryStore(context, ApplicationOperations.get(context).scope) }
        }
    }
}
