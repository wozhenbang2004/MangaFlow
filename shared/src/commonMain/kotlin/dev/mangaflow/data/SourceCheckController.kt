package dev.mangaflow.data

import dev.mangaflow.background.ApplicationOperations
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.filesDir
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.beginDownloadExecution
import dev.mangaflow.storage.readManga
import dev.mangaflow.storage.toJson
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** One executor for process timers and Android wakes; individual completed works are checkpointed. */
class SourceCheckController(
    context: PlatformContext,
    private val repository: UnifiedMangaRepository,
    private val library: LibraryStore,
    private val operations: ApplicationOperations,
    private val prepareSources: suspend () -> Unit,
) {
    private val application = applicationContext(context)
    private val file = StorageFile(application.filesDir, "source-checks.json")
    private val writer = Mutex()
    private val executionLock = StorageLock()
    private var execution: Deferred<Unit>? = null
    private val ready = CompletableDeferred<Unit>()
    private val mutablePreferences = MutableStateFlow(SourceCheckPreferences())
    val preferences = mutablePreferences.asStateFlow()
    private val mutableState = MutableStateFlow(SourceCheckScheduleState())
    val state = mutableState.asStateFlow()
    @Volatile private var records = Record()
    @Volatile private var storageFailed = false
    @Volatile private var restoreGeneration = 0L

    private data class Stamp(val at: Long, val configuration: String)
    private data class Record(val preferences: SourceCheckPreferences = SourceCheckPreferences(),
        val focused: Manga? = null, val stamps: Map<String, Stamp> = emptyMap(), val lastCompletedAt: Long = 0L)

    init {
        operations.scope.launch {
            try {
                records = withContext(Dispatchers.IO) { read() }
                mutablePreferences.value = records.preferences
                repository.configureSourceChecks(records.preferences)
                mutableState.update { it.copy(lastCompletedAt = records.lastCompletedAt) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failStorage("来源检查记录无法读取，自动检查已暂停。") }
            finally { ready.complete(Unit) }
        }
        operations.scope.launch {
            ready.await()
            preferences.collectLatest { selected ->
                try {
                    if (selected.enabled && !storageFailed) scheduleSourceCheckWake(application, selected.intervalMinutes)
                    else cancelSourceCheckWake(application)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableState.update { it.copy(error = "系统定时检查尚未安排；应用运行时仍会按周期检查。") }
                }
                if (!selected.enabled || storageFailed) return@collectLatest
                while (true) {
                    try { checkDue() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { it.copy(error = "自动来源检查未完成，将在下次唤醒继续。") } }
                    val now = wallTimeMillis()
                    val untilDue = state.value.nextCheckAt.takeIf { it > now }?.minus(now)
                    // A short observation tick also notices newly saved works and configuration changes.
                    delay(minOf(selected.intervalMillis, untilDue ?: 60_000L, 60_000L).coerceAtLeast(1_000L))
                }
            }
        }
        operations.scope.launch {
            library.backupRestored.drop(1).collect {
                ready.await()
                restoreGeneration++
                repository.invalidateSourceChecks()
                try { change { it.copy(focused = null, stamps = emptyMap(), lastCompletedAt = 0L) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failStorage("恢复后来源检查状态未能保存，自动检查已暂停。") }
            }
        }
    }

    fun updatePreferences(next: SourceCheckPreferences) {
        try { next.checked() } catch (failure: IllegalArgumentException) {
            mutableState.update { it.copy(error = failure.message) }; return
        }
        operations.scope.launch {
            ready.await()
            try {
                check(!storageFailed) { "来源检查记录尚未恢复。" }
                change { it.copy(preferences = next) }
                repository.configureSourceChecks(next)
                mutablePreferences.value = next
                mutableState.update { it.copy(nextCheckAt = 0L, error = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failStorage("来源检查设置未能保存，原设置保留；自动检查已暂停。") }
        }
    }

    /** Only the current unsaved work is retained; saved shelf/history membership remains authoritative. */
    fun watch(work: Manga) {
        if (!eligible(work)) return
        operations.scope.launch {
            ready.await()
            if (storageFailed || records.focused == work) return@launch
            try {
                change { it.copy(focused = work) }
                mutableState.update { it.copy(nextCheckAt = 0L) }
                if (preferences.value.enabled) resume()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failStorage("当前作品的自动检查范围未能保存。") }
        }
    }

    fun resume() {
        operations.scope.launch {
            try { checkDue() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(error = "自动来源检查未完成，将在下次唤醒继续。") } }
        }
    }

    /** Android's worker awaits this same executor; it never starts a second source scan. */
    suspend fun checkDue() {
        ready.await()
        if (storageFailed || !preferences.value.enabled) return
        val run = synchronized(executionLock) {
            execution?.takeIf { !it.isCompleted } ?: operations.scope.async(start = CoroutineStart.LAZY) {
                executeDue()
            }.also { next ->
                execution = next
                next.invokeOnCompletion { synchronized(executionLock) {
                    if (execution === next) execution = null
                } }
            }
        }
        run.start()
        run.await()
    }

    private suspend fun executeDue() {
        val caller = currentCoroutineContext()[Job]
        val release = beginDownloadExecution { caller?.cancel(CancellationException("系统暂停了来源检查")) }
        val generation = restoreGeneration
        try {
            library.awaitReady()
            prepareSources()
            currentCoroutineContext().ensureActive()
            if (storageFailed || !preferences.value.enabled || generation != restoreGeneration) return
            if (repository.enabledSources().isEmpty()) {
                mutableState.update { it.copy(nextCheckAt = 0L) }
                return
            }
            val configuration = repository.sourceConfigurationKey()
            val now = wallTimeMillis()
            val candidates = targets()
            val due = candidates.filter { work -> !fresh(records.stamps[work.id], configuration, now) }
                .sortedBy { records.stamps[it.id]?.at ?: 0L }
            if (due.isEmpty()) {
                publishNext(candidates, configuration)
                return
            }
            mutableState.update { it.copy(running = true, checkedWorks = 0, totalWorks = due.size, error = null) }
            var checked = 0
            for (work in due) {
                currentCoroutineContext().ensureActive()
                if (!preferences.value.enabled || generation != restoreGeneration || storageFailed ||
                    repository.sourceConfigurationKey() != configuration) break
                if (targets().none { it.id == work.id }) continue
                repository.checkSources(work, force = true, includePrimary = true)
                currentCoroutineContext().ensureActive()
                if (generation != restoreGeneration || repository.sourceConfigurationKey() != configuration) break
                val remaining = repository.sourceDiscovery.value[work.id].orEmpty().values.any {
                    it.status.name in setOf("SEARCHING", "QUEUED", "RETRYING")
                }
                if (remaining) continue
                val completedAt = wallTimeMillis()
                change { current ->
                    val currentIds = targets().mapTo(mutableSetOf()) { it.id }
                    current.copy(stamps = current.stamps.filterKeys { it in currentIds } +
                        (work.id to Stamp(completedAt, configuration)), lastCompletedAt = completedAt)
                }
                checked++
                mutableState.update { it.copy(checkedWorks = checked, lastCompletedAt = completedAt) }
            }
            publishNext(targets(), repository.sourceConfigurationKey())
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(error = "自动检查已暂停；已完成的作品已保存，下次继续检查未完成项。") }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(error = if (storageFailed) "检查记录未能保存，自动检查已暂停。"
                else "自动来源检查未完成，将在下次唤醒继续。") }
            throw failure
        } finally {
            mutableState.update { it.copy(running = false) }
            release()
        }
    }

    private fun targets(): List<Manga> = (library.favorites.value + library.history.value.map { it.manga } +
        listOfNotNull(records.focused)).distinctBy { it.id }.filter(::eligible)

    private fun eligible(work: Manga): Boolean = work.id.isNotBlank() && work.title.isNotBlank() &&
        work.sourceId != "catalog" && work.sourceUrl != DownloadStore.LOCAL_SOURCE_URL &&
        repository.sources.any { it.id == work.sourceId && !it.adult }

    private fun fresh(stamp: Stamp?, configuration: String, now: Long): Boolean = stamp != null &&
        stamp.configuration == configuration && now - stamp.at in 0L until preferences.value.intervalMillis

    private fun publishNext(works: List<Manga>, configuration: String) {
        val now = wallTimeMillis()
        val next = works.minOfOrNull { work ->
            val stamp = records.stamps[work.id]
            if (!fresh(stamp, configuration, now)) now else stamp!!.at + preferences.value.intervalMillis
        } ?: 0L
        mutableState.update { it.copy(nextCheckAt = if (preferences.value.enabled) next else 0L) }
    }

    private suspend fun change(transform: (Record) -> Record) = withContext(NonCancellable) {
        writer.withLock {
            val next = transform(records)
            try { withContext(Dispatchers.IO) { write(next) } }
            catch (failure: Exception) {
                failStorage("来源检查记录未能保存，自动检查已暂停。")
                throw failure
            }
            records = next
        }
    }

    private fun failStorage(message: String) {
        storageFailed = true
        mutableState.update { it.copy(error = message, nextCheckAt = 0L) }
        runCatching { cancelSourceCheckWake(application) }
    }

    private fun read(): Record {
        val atomic = StorageAtomicFile(file)
        if (!file.exists() && !StorageFile(file.path + ".bak").exists()) return Record()
        val json = atomic.openRead().bufferedReader().use { JSONObject(it.readText()) }
        require(json.optString("format") == "source-checks-v1") { "来源检查记录格式不兼容。" }
        val settings = json.getJSONObject("preferences")
        val selected = SourceCheckPreferences(settings.optBoolean("enabled", true),
            settings.optInt("intervalMinutes", 30), settings.optInt("concurrency", 8),
            settings.optInt("timeoutSeconds", 25), settings.optInt("retries", 1)).checked()
        val stamps = json.optJSONArray("stamps") ?: JSONArray()
        val entries = (0 until stamps.length()).associate { index ->
            val item = stamps.getJSONObject(index)
            item.getString("id") to Stamp(item.getLong("at"), item.getString("configuration"))
        }
        return Record(selected, json.optJSONObject("focused")?.let(::readManga), entries,
            json.optLong("lastCompletedAt"))
    }

    private fun write(record: Record) {
        val settings = record.preferences
        val json = JSONObject().put("format", "source-checks-v1").put("preferences", JSONObject()
            .put("enabled", settings.enabled).put("intervalMinutes", settings.intervalMinutes)
            .put("concurrency", settings.concurrency).put("timeoutSeconds", settings.timeoutSeconds)
            .put("retries", settings.retries)).put("lastCompletedAt", record.lastCompletedAt)
        record.focused?.let { json.put("focused", it.toJson()) }
        json.put("stamps", JSONArray(record.stamps.map { (id, stamp) -> JSONObject()
            .put("id", id).put("at", stamp.at).put("configuration", stamp.configuration) }))
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } != false) { "检查记录目录无法创建。" }
        val atomic = StorageAtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(json.toString().encodeToByteArray()); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
}

internal expect fun scheduleSourceCheckWake(context: PlatformContext, intervalMinutes: Int)
internal expect fun cancelSourceCheckWake(context: PlatformContext)
