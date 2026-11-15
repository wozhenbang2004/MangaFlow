package dev.mangaflow.account

import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.preferences
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.storage.beginDownloadExecution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class CopyReadingOwner(val name: String, val credentialDigest: String, val version: Long) {
    val key: String get() = "${name.length}:$name:$credentialDigest"
}

data class CopyReadingOutboxState(val owner: String? = null, val pending: Int = 0,
    val running: Boolean = false, val message: String? = null, val error: String? = null)

/** Accepted visible-reading intents survive navigation/process loss; only this owner sends them. */
class CopyReadingOutbox(context: PlatformContext, private val account: CopyAccountRepository,
    private val scope: CoroutineScope) {
    private val application = applicationContext(context)
    private val disk by lazy { preferences(application, "mangaflow_copy_reading_outbox") }
    private val stateGate = Mutex()
    private val executor = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(CopyReadingOutboxState())
    val state = mutableState.asStateFlow()
    private var records = emptyMap<String, Entry>()
    private var owner: CopyReadingOwner? = null
    private var runner: Job? = null
    private var activeExecution: Job? = null
    private var storageFailed = false
    private var lastVisible: String? = null

    private data class Entry(val owner: String, val credentialDigest: String, val manga: String,
        val chapter: String, val title: String, val token: String, val intentAt: Long,
        val attempts: Int = 0, val nextAt: Long = 0L, val cooldownUntil: Long = 0L,
        val submitted: Boolean = false, val blocked: Boolean = false) {
        val ownerKey: String get() = "${owner.length}:$owner:$credentialDigest"
        val key: String get() = "$ownerKey/$manga"
    }

    init {
        scope.launch {
            try {
                records = withContext(Dispatchers.IO) { readEntries() }
            } catch (cancelled: CancellationException) {
                ready.cancel(cancelled)
                throw cancelled
            } catch (failure: Exception) {
                storageFailed = true
                mutableState.value = mutableState.value.copy(error = "待同步阅读记录无法读取，请检查本机存储。")
            } finally { ready.complete(Unit) }
            accountChanged()
            if (records.isNotEmpty() && !storageFailed) scheduleWake(0L)
        }
        scope.launch { account.profile.collect { accountChanged() } }
    }

    /** Root also invokes this for a version change whose profile text stayed the same. */
    fun accountChanged() {
        val next = account.readingQueueOwner()
        if (next == owner) return
        val previous = owner
        owner = next
        lastVisible = null
        if (previous != null) {
            runner?.cancel()
            activeExecution?.cancel()
            runner = null
        }
        publish(running = false, message = null,
            error = if (storageFailed) "待同步阅读记录无法读取，请检查本机存储。" else null)
        if (next != null) kick() else runCatching { cancelCopyReadingWake(application) }
    }

    /** This is called only after a real public image becomes visible, never by preview/import. */
    fun submit(manga: Manga, chapter: Chapter) {
        if (manga.sourceId != "copy" || chapter.sourceId != "copy" || manga.sourceUrl == LOCAL_SOURCE_URL ||
            SourceKeys.isEncoded(manga.id) || SourceKeys.isEncoded(chapter.id) || !SLUG.matches(manga.id) ||
            !CHAPTER.matches(chapter.id) || chapter.id.none { it != '0' && it != '-' }) return
        val acceptedOwner = account.readingQueueOwner() ?: return
        val visibleKey = acceptedOwner.key + "/" + manga.id + "/" + chapter.id
        if (lastVisible == visibleKey) return
        lastVisible = visibleKey
        scope.launch {
            ready.await()
            if (storageFailed || !current(acceptedOwner)) return@launch
            val entry = Entry(acceptedOwner.name, acceptedOwner.credentialDigest, manga.id, chapter.id,
                chapter.title.take(160), randomUuid(), wallTimeMillis())
            try {
                change { old ->
                    if (!current(acceptedOwner)) return@change old
                    // A fresh visible chapter renews its intent, while server cooldown remains authoritative.
                    val cooldown = old.values.filter { it.ownerKey == acceptedOwner.key }.maxOfOrNull { it.cooldownUntil } ?: 0L
                    old + (entry.key to entry.copy(cooldownUntil = cooldown, nextAt = maxOf(entry.intentAt, cooldown)))
                }
                if (current(acceptedOwner)) { publish(); scheduleWake(0L); kick() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (lastVisible == visibleKey) lastVisible = null
                if (current(acceptedOwner)) failStorage()
            }
        }
    }

    /** Explicit retry renews only this verified owner's existing reading intents. */
    fun retry() {
        val acceptedOwner = account.readingQueueOwner() ?: return
        scope.launch {
            ready.await()
            if (storageFailed || !current(acceptedOwner)) return@launch
            try {
                val now = wallTimeMillis()
                change { old -> old.mapValues { (_, entry) ->
                    if (entry.ownerKey != acceptedOwner.key) entry else entry.copy(attempts = 0,
                        nextAt = maxOf(now, entry.cooldownUntil), submitted = false, blocked = false, intentAt = now)
                } }
                publish(error = null)
                scheduleWake(0L)
                kick()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(acceptedOwner)) failStorage() }
        }
    }

    /** Reopening/resuming may continue a retained iOS queue after its finite native lease expired. */
    fun resume() { accountChanged(); kick() }

    private fun kick() {
        if (runner?.isActive == true || owner == null || storageFailed) return
        val task = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            try {
                delay(700L)
                while (true) {
                    if (!processPending()) break
                    val currentOwner = account.readingQueueOwner() ?: break
                    val pending = eligible(currentOwner).filter { !it.blocked && it.attempts < MAX_ATTEMPTS }
                    if (pending.isEmpty()) break
                    delay((pending.minOf { it.nextAt } - wallTimeMillis()).coerceAtLeast(1_000L))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failStorage() }
            finally { if (runner === ownJob) runner = null }
        }
        runner = task
        task.start()
    }

    private fun scheduleWake(delayMillis: Long) {
        try { scheduleCopyReadingWake(application, delayMillis) }
        catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "系统暂未安排后台重试，阅读待办已保留。")
        }
    }

    /** The OS worker and live application share this exact executor and durable queue. */
    suspend fun processPending(): Boolean = withContext(Dispatchers.Main.immediate) {
        ready.await()
        if (storageFailed) return@withContext false
        executor.withLock {
            if (account.readingQueueOwner() == null) {
                try { account.restore() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mutableState.value = mutableState.value.copy(error = "账号暂时无法验证，阅读待办已保留。")
                    return@withLock records.isNotEmpty()
                }
            }
            accountChanged()
            val acceptedOwner = account.readingQueueOwner() ?: return@withLock false
            val ownedJob = currentCoroutineContext()[Job]
            activeExecution = ownedJob
            val release = beginDownloadExecution { ownedJob?.cancel(CancellationException("系统暂停阅读记录同步")) }
            mutableState.value = mutableState.value.copy(running = true, error = null)
            try {
                withTimeoutOrNull(45_000L) {
                    while (current(acceptedOwner)) {
                        val now = wallTimeMillis()
                        val entry = records.values.filter { it.ownerKey == acceptedOwner.key && !it.blocked && it.attempts < MAX_ATTEMPTS }
                            .filter { it.nextAt <= now }.minByOrNull { it.intentAt } ?: break
                        syncOne(acceptedOwner, entry)
                    }
                }
            } finally {
                release()
                if (activeExecution === ownedJob) activeExecution = null
                publish(running = false)
            }
            val pending = eligible(acceptedOwner).filter { !it.blocked && it.attempts < MAX_ATTEMPTS }
            if (pending.isNotEmpty()) {
                val delay = (pending.minOf { it.nextAt } - wallTimeMillis()).coerceAtLeast(1_000L)
                scheduleWake(delay)
            }
            pending.isNotEmpty()
        }
    }

    private suspend fun syncOne(acceptedOwner: CopyReadingOwner, entry: Entry) {
        try {
            requireCurrent(acceptedOwner)
            // A previous write may have reached the server without returning its acknowledgement.
            val cloud = account.cloudProgress(entry.manga)
            requireCurrent(acceptedOwner)
            if (cloud?.chapterId == entry.chapter) {
                removeCurrent(entry)
                return
            }
            if (records[entry.key]?.token != entry.token) return
            if (entry.submitted || wallTimeMillis() - entry.intentAt > FRESH_INTENT_MILLIS) {
                updateCurrent(entry) { it.copy(blocked = true) }
                publish(error = "云端位置需重新确认；待办已保留，可点重试同步。")
                return
            }
            var marked = false
            change { old ->
                if (old[entry.key]?.token != entry.token || !current(acceptedOwner)) old else {
                    marked = true
                    old + (entry.key to entry.copy(submitted = true))
                }
            }
            if (!marked) return
            requireCurrent(acceptedOwner)
            account.syncChapter(entry.manga, entry.chapter) {
                withContext(Dispatchers.Main.immediate) {
                    requireCurrent(acceptedOwner)
                    if (records[entry.key]?.token != entry.token) throw ObsoleteReadingIntent()
                }
            }
            requireCurrent(acceptedOwner)
            removeCurrent(entry)
            publish(message = "云端章节记录已同步；页内位置保存在本机。", error = null)
        } catch (_: ObsoleteReadingIntent) {
            // A newer persisted chapter owns this work. It needs no error, cooldown or cancellation.
            return
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!current(acceptedOwner)) return
            val now = wallTimeMillis()
            val count = entry.attempts + 1
            val seconds = (failure as? SourceException)?.retryAfterSeconds?.coerceAtLeast(0L) ?: 0L
            val server = seconds.coerceAtMost((Long.MAX_VALUE - now) / 1_000L) * 1_000L + now
            val next = maxOf(server, now + (5_000L shl (count - 1).coerceIn(0, 5)))
            val denied = (failure as? SourceException)?.httpStatus in setOf(401, 403)
            change { old -> old.mapValues { (_, value) ->
                if (value.ownerKey != acceptedOwner.key) value
                else {
                    val same = value.key == entry.key && value.token == entry.token
                    value.copy(attempts = if (same) count else value.attempts,
                        nextAt = maxOf(value.nextAt, next), cooldownUntil = maxOf(value.cooldownUntil, server),
                        blocked = value.blocked || denied || same && count >= MAX_ATTEMPTS)
                }
            } }
            publish(error = if (denied) "来源拒绝账号访问，阅读待办已保留。" else "云端章节暂未确认，已保留待办并等待重试。")
        }
    }

    private suspend fun removeCurrent(entry: Entry) = change { old ->
        if (old[entry.key]?.token == entry.token) old - entry.key else old
    }

    private suspend fun updateCurrent(entry: Entry, update: (Entry) -> Entry) = change { old ->
        val current = old[entry.key]
        if (current?.token == entry.token) old + (entry.key to update(current)) else old
    }

    /** Snapshot publication follows successful storage; newer queued chapters cannot be lost to an older response. */
    private suspend fun change(transform: (Map<String, Entry>) -> Map<String, Entry>) = stateGate.withLock {
        val next = transform(records)
        if (next == records) return@withLock
        check(next.size <= MAX_RECORDS) { "阅读同步待办过多，请先处理未完成记录。" }
        val saved = withContext(Dispatchers.IO) {
            val rows = JSONArray()
            next.values.sortedBy { it.key }.forEach { value -> rows.put(JSONObject()
                .put("owner", value.owner).put("credential", value.credentialDigest).put("manga", value.manga)
                .put("chapter", value.chapter).put("title", value.title).put("token", value.token).put("intentAt", value.intentAt)
                .put("attempts", value.attempts).put("nextAt", value.nextAt).put("cooldown", value.cooldownUntil)
                .put("submitted", value.submitted).put("blocked", value.blocked)) }
            disk.edit().putString("queue", JSONObject().put("schema", 1).put("entries", rows).toString()).commit()
        }
        check(saved) { "阅读同步待办尚未保存。" }
        records = next
    }

    private fun readEntries(): Map<String, Entry> {
        val encoded = disk.getString("queue", null) ?: return emptyMap()
        require(encoded.length <= 512 * 1024)
        val root = JSONObject(encoded)
        require(root.optInt("schema") == 1)
        val rows = root.getJSONArray("entries")
        require(rows.length() <= MAX_RECORDS)
        val result = linkedMapOf<String, Entry>()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val entry = Entry(row.getString("owner"), row.getString("credential"), row.getString("manga"),
                row.getString("chapter"), row.optString("title").take(160), row.getString("token"),
                row.optLong("intentAt"), row.optInt("attempts"), row.optLong("nextAt"), row.optLong("cooldown"),
                row.optBoolean("submitted"), row.optBoolean("blocked"))
            require(entry.owner.length in 1..160 && DIGEST.matches(entry.credentialDigest) && SLUG.matches(entry.manga) &&
                CHAPTER.matches(entry.chapter) && entry.chapter.any { it != '0' && it != '-' } && entry.token.length in 1..80 &&
                entry.intentAt > 0L && entry.attempts in 0..MAX_ATTEMPTS && entry.nextAt >= 0L && entry.cooldownUntil >= 0L)
            require(result.put(entry.key, entry) == null)
        }
        return result
    }

    private fun eligible(acceptedOwner: CopyReadingOwner) = records.values.filter { it.ownerKey == acceptedOwner.key }
    private fun current(acceptedOwner: CopyReadingOwner) = account.readingQueueOwner() == acceptedOwner
    private suspend fun requireCurrent(acceptedOwner: CopyReadingOwner) {
        currentCoroutineContext().ensureActive()
        if (!current(acceptedOwner)) throw CancellationException("阅读同步账号已改变")
    }
    private fun publish(running: Boolean = mutableState.value.running,
        message: String? = mutableState.value.message, error: String? = mutableState.value.error) {
        val current = account.readingQueueOwner()
        mutableState.value = CopyReadingOutboxState(current?.name,
            current?.let { eligible(it).size } ?: 0, running, message, error)
    }
    private fun failStorage() {
        mutableState.value = mutableState.value.copy(error = "待同步阅读记录尚未保存，请检查可用空间。")
    }

    private class ObsoleteReadingIntent : Exception()

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_RECORDS = 256
        const val FRESH_INTENT_MILLIS = 5L * 60_000L
        val SLUG = Regex("[A-Za-z0-9_-]{1,160}")
        val CHAPTER = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val DIGEST = Regex("[0-9a-f]{64}")
    }
}

internal expect fun scheduleCopyReadingWake(context: PlatformContext, delayMillis: Long)
internal expect fun cancelCopyReadingWake(context: PlatformContext)
