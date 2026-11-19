package dev.mangaflow.anime.downloads

import dev.mangaflow.anime.AnimeDetail
import dev.mangaflow.anime.AnimeDocumentCodec
import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.player.AnimeLocalMedia
import dev.mangaflow.anime.player.AnimeLocalMediaLease
import dev.mangaflow.anime.player.AnimeLocalResource
import dev.mangaflow.anime.player.playbackIdentity
import dev.mangaflow.anime.player.readablePlayerError
import dev.mangaflow.json.JSONArray
import dev.mangaflow.json.JSONObject
import dev.mangaflow.net.DownloadProxyEndpoint
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.NetworkClient
import dev.mangaflow.net.checked
import dev.mangaflow.net.readRelayBinding
import dev.mangaflow.net.writeRelayBinding
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.randomUuid
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.platform.storage.StorageAtomicFile
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.applicationContext
import dev.mangaflow.platform.storage.deleteSecret
import dev.mangaflow.platform.storage.filesDir
import dev.mangaflow.platform.storage.readSecret
import dev.mangaflow.platform.storage.writeSecret
import kotlinx.atomicfu.locks.synchronized
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application-owned explicit offline work. Android's scheduled worker may restore pending intent.
 * Downloads never share the disposable player cache or the watch-progress document.
 */
class AnimeDownloadStore(context: PlatformContext, applicationScope: CoroutineScope) {
    val capabilities = AnimeDownloadCapabilities(videoContainers = animeOfflineVideoContainers(),
        systemBackgroundExecution = animeSystemDownloadExecution(),
        processBackgroundExecution = animeProcessDownloadExecution())
    private val context = context.applicationContext
    private val root = StorageFile(this.context.filesDir, "anime-downloads")
    private val scope = CoroutineScope(applicationScope.coroutineContext +
        SupervisorJob(applicationScope.coroutineContext[Job]) + Dispatchers.IO)
    private val lock = StorageLock()
    private val ready = CompletableDeferred<Unit>()
    private val actions = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val records = linkedMapOf<String, Record>()
    private val readers = mutableMapOf<String, Int>()
    private val mutableState = MutableStateFlow(AnimeDownloadsState())
    val state: StateFlow<AnimeDownloadsState> = mutableState.asStateFlow()
    private val foreground = atomic(false)
    private val playbackBusy = atomic(false)
    private var backgroundExecutions = 0
    private var activeId: String? = null
    private var activeGeneration: String? = null
    private var activeJob: Job? = null

    /** Explicit originals and partial downloads are counted separately from disposable player caches. */
    suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        ready.await()
        fun bytes(file: StorageFile): Long = if (file.isFile) file.length()
            else file.listFiles().orEmpty().sumOf(::bytes)
        bytes(root)
    }

    private data class Record(
        val entry: AnimeDownloadEntry,
        val generation: String,
        val requestIdentity: String,
        val desired: Boolean = false,
        val removed: Boolean = false,
        val resetRequired: Boolean = false,
        val entryName: String? = null,
        val resources: List<AnimeSavedResource> = emptyList(),
        val planIdentity: String? = null,
    )

    init {
        scope.launch {
            for (action in actions) {
                try { ready.await(); action() }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: Throwable) { synchronized(lock) { publish(readablePlayerError(failure)) } }
            }
        }
        scope.launch {
            try {
                check(root.isDirectory || root.mkdirs()) { "无法创建动漫离线目录" }
                var unreadable = false
                root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(ID) }.forEach { folder ->
                    currentCoroutineContext().ensureActive()
                    try {
                        val manifest = StorageFile(folder, "manifest.json")
                        require(manifest.length() in 1L..MAX_MANIFEST_BYTES)
                        val saved = decode(JSONObject(StorageAtomicFile(manifest).openRead().use { it.bufferedReader().readText() }))
                        require(saved.entry.id == folder.name)
                        if (saved.removed) {
                            if (folder.deleteRecursively()) runCatching { deleteSecret(this@AnimeDownloadStore.context, secretKey(saved.entry.id)) }
                            return@forEach
                        }
                        val valid = saved.entry.status == AnimeDownloadStatus.COMPLETED && filesValid(saved)
                        val pending = saved.desired && capabilities.systemBackgroundExecution &&
                            saved.entry.status in PENDING_STATUSES
                        val entry = saved.entry.copy(status = when {
                            valid -> AnimeDownloadStatus.COMPLETED
                            saved.entry.status == AnimeDownloadStatus.COMPLETED -> AnimeDownloadStatus.FAILED
                            saved.entry.status == AnimeDownloadStatus.FAILED -> AnimeDownloadStatus.FAILED
                            pending -> AnimeDownloadStatus.QUEUED
                            else -> AnimeDownloadStatus.PAUSED
                        }, error = when {
                            valid || pending -> null
                            saved.entry.status == AnimeDownloadStatus.COMPLETED -> "离线文件缺失或大小改变，请重新下载"
                            saved.entry.status in PENDING_STATUSES ->
                                "上次下载已暂停；点继续可恢复下载，来源不支持续传时会重新下载"
                            else -> saved.entry.error
                        })
                        synchronized(lock) { records[entry.id] = saved.copy(entry = entry, desired = pending) }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { unreadable = true }
                }
                synchronized(lock) {
                    publish(if (unreadable) "部分离线记录无法读取，原有文件已保留" else null)
                }
                ready.complete(Unit)
                if (hasPendingWork()) {
                    // Recover intent even if the process exited between saving it and scheduling the worker.
                    requestBackgroundExecution(recover = true)
                    action { synchronized(lock) { kick() } }
                }
            } catch (failure: Throwable) {
                synchronized(lock) { mutableState.value = mutableState.value.copy(loading = false, notice = readablePlayerError(failure)) }
                ready.completeExceptionally(failure)
            }
        }
    }

    /** Calling this is the user action; restoring metadata never starts network transfers. */
    suspend fun enqueue(subject: AnimeSubject, episode: AnimeEpisode, asset: AnimePlaybackAsset,
        sourceName: String = asset.sourceId): String = withContext(Dispatchers.IO) {
        ready.await()
        require(episode.subjectId == subject.id && asset.episodeId == episode.id) { "下载内容与当前番剧或剧集不匹配" }
        require(episode.id.isNotBlank() && episode.number.isFinite() && asset.sourceId.isNotBlank()) { "离线任务缺少有效身份" }
        AnimeDocumentCodec.subject(AnimeDocumentCodec.subject(subject))
        checkedAnimeDownloadUrl(asset.url)
        require(asset.expiresAt?.let { it > wallTimeMillis() } != false) { "下载地址已过期，请重新解析同一来源的这集" }
        require(asset.subtitles.isEmpty()) { "当前离线保存尚不支持外挂字幕，请选择内嵌字幕的线路" }
        asset.mimeType?.let(::requireSupportedFormat)
        require(!asset.url.substringBefore('?').endsWith(".mpd", true) && asset.mimeType != "application/dash+xml") {
            "当前离线保存支持视频文件和静态 HLS，DASH 线路请更换来源后下载"
        }
        val id = animeDigest(listOf(subject.id, episode.id, asset.sourceEpisodeId, asset.playbackIdentity())
            .joinToString("") { "${it.length}:$it" })
        synchronized(lock) {
            val previous = records[id]
            check(previous?.removed != true) { "这份离线内容正在释放，稍后再下载" }
            if (previous?.entry?.status == AnimeDownloadStatus.COMPLETED && filesValid(previous)) return@synchronized id
            val folder = directory(id)
            check(folder.isDirectory || folder.mkdirs()) { "无法创建这集的离线目录" }
            // Platform keystore/keychain failure is visible; no plaintext fallback is permitted.
            writeSecret(this@AnimeDownloadStore.context, secretKey(id), encodeAsset(asset).toString())
            val now = wallTimeMillis()
            val fingerprint = requestIdentity(asset, asset.url)
            val changed = previous != null && previous.requestIdentity != fingerprint
            val entry = AnimeDownloadEntry(id, subject, episode, asset.sourceId, sourceName, asset.sourceEpisodeId,
                asset.id, asset.edition, asset.sourceRevision, asset.sessionEpoch, AnimeDownloadStatus.QUEUED,
                downloadedBytes = if (changed) 0 else previous?.entry?.downloadedBytes ?: 0L,
                totalBytes = if (changed) null else previous?.entry?.totalBytes,
                createdAt = previous?.entry?.createdAt ?: now, updatedAt = now)
            val record = Record(entry, randomUuid(), fingerprint, desired = true,
                resetRequired = changed || previous?.resetRequired == true,
                entryName = if (changed) null else previous?.entryName,
                planIdentity = if (changed) null else previous?.planIdentity)
            save(record); records[id] = record
            if (activeId == id) activeJob?.cancel()
            publish(null); requestBackgroundExecution(); kick()
            id
        }
    }

    fun pause(id: String) = action {
        synchronized(lock) {
            val old = records[id]?.takeUnless { it.removed || it.entry.status == AnimeDownloadStatus.COMPLETED }
                ?: return@synchronized
            val next = old.copy(generation = randomUuid(), desired = false,
                entry = old.entry.copy(status = AnimeDownloadStatus.PAUSED, error = null, updatedAt = wallTimeMillis()))
            save(next); records[id] = next
            if (activeId == id) activeJob?.cancel()
            publish()
        }
    }

    fun resume(id: String) = action {
        synchronized(lock) {
            val old = records[id]?.takeUnless { it.removed || it.entry.status == AnimeDownloadStatus.COMPLETED }
                ?: return@synchronized
            val next = old.copy(generation = randomUuid(), desired = true,
                entry = old.entry.copy(status = AnimeDownloadStatus.QUEUED, error = null, updatedAt = wallTimeMillis()))
            save(next); records[id] = next
            if (activeId == id) activeJob?.cancel()
            publish(); requestBackgroundExecution(); kick()
        }
    }

    fun remove(id: String) = action {
        val job = synchronized(lock) {
            val old = records[id] ?: return@synchronized null
            val removed = old.copy(generation = randomUuid(), desired = false, removed = true)
            save(removed); records[id] = removed
            publish()
            activeJob.takeIf { activeId == id }
        }
        job?.cancelAndJoin()
        synchronized(lock) { cleanRemoved(id); kick() }
    }

    fun setForeground(value: Boolean) {
        if (foreground.getAndSet(value) != value) action {
            synchronized(lock) { deferActiveIfNeeded(); kick() }
            if (value && hasPendingWork()) requestBackgroundExecution(recover = true)
        }
    }

    internal suspend fun hasPendingWork(): Boolean {
        ready.await()
        return state.value.entries.any { it.status in PENDING_STATUSES }
    }

    /** The worker holds the system execution lease. This store remains the only media writer. */
    internal suspend fun runWithBackgroundExecution(onEntry: suspend (AnimeDownloadEntry) -> Unit) {
        ready.await()
        synchronized(lock) { backgroundExecutions++ }
        try {
            synchronized(lock) {
                if (state.value.notice == BACKGROUND_SCHEDULE_ERROR) publish(null)
                kick()
            }
            state.first { snapshot ->
                val entry = snapshot.entries.firstOrNull { it.status == AnimeDownloadStatus.DOWNLOADING }
                    ?: snapshot.entries.lastOrNull { it.status in PENDING_STATUSES }
                if (entry != null) onEntry(entry)
                entry == null
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { synchronized(lock) {
                backgroundExecutions--
                runCatching { deferActiveIfNeeded(); kick() }.onFailure { publish(readablePlayerError(it)) }
            } }
        }
    }

    /** The current video owns bandwidth while opening/buffering; existing explicit work waits. */
    fun setPlaybackBusy(value: Boolean) {
        if (playbackBusy.getAndSet(value) != value) action { synchronized(lock) { deferActiveIfNeeded(); kick() } }
    }

    suspend fun playback(id: String): AnimeDownloadedPlayback = withContext(Dispatchers.IO) {
        ready.await()
        synchronized(lock) {
            val record = checkNotNull(records[id]?.takeUnless { it.removed }) { "这份离线内容已删除" }
            check(record.entry.status == AnimeDownloadStatus.COMPLETED && filesValid(record)) { "离线文件尚未完成或已损坏，请继续下载" }
            val row = record.entry
            val media = record.resources.single { it.name == record.entryName }
            val asset = AnimePlaybackAsset(row.assetId, row.sourceId, row.sourceEpisodeId,
                StorageFile(directory(id), media.name).toURI(), DownloadRouteSnapshot(DownloadRouteMode.SYSTEM),
                episodeId = row.episode.id, title = row.subject.title + " · " + row.episode.title,
                mimeType = media.mimeType, edition = row.edition, sourceRevision = row.sourceRevision,
                sessionEpoch = row.sessionEpoch)
            val capability = AnimeLocalMedia(id, row.assetId, row.episode.id, row.sourceId,
                row.sourceEpisodeId, row.sourceRevision, row.sessionEpoch, row.edition) {
                synchronized(lock) {
                    val current = records[id]
                    check(current != null && !current.removed && current.generation == record.generation && filesValid(current)) {
                        "离线内容已变化或删除，请重新打开"
                    }
                    readers[id] = (readers[id] ?: 0) + 1
                    AnimeLocalMediaLease(checkNotNull(current.entryName), current.resources.associate { resource ->
                        resource.name to AnimeLocalResource(StorageFile(directory(id), resource.name), resource.mimeType, resource.bytes)
                    }) {
                        // Native release runs on Main; never wait there for a disk/keychain operation.
                        scope.launch { synchronized(lock) {
                            val remaining = (readers[id] ?: 1) - 1
                            if (remaining <= 0) readers.remove(id) else readers[id] = remaining
                            runCatching { cleanRemoved(id) }.onFailure { publish(readablePlayerError(it)) }
                        } }
                    }
                }
            }
            AnimeDownloadedPlayback(row.subject, row.episode, asset, capability)
        }
    }

    private fun action(block: suspend () -> Unit) {
        actions.trySend(block)
    }

    private fun requestBackgroundExecution(recover: Boolean = false) {
        if (!capabilities.systemBackgroundExecution) return
        scope.launch {
            try {
                scheduleAnimeDownloadExecution(context, recover)
                synchronized(lock) { if (state.value.notice == BACKGROUND_SCHEDULE_ERROR) publish(null) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                synchronized(lock) {
                    if (records.values.any { it.desired && !it.removed } && backgroundExecutions == 0) {
                        publish(BACKGROUND_SCHEDULE_ERROR)
                    }
                }
            }
        }
    }

    private fun executionAllowed() = foreground.value || capabilities.processBackgroundExecution || backgroundExecutions > 0

    private fun deferActiveIfNeeded() {
        if (executionAllowed() && !playbackBusy.value) return
        val id = activeId ?: return
        val old = records[id]?.takeIf { it.desired && !it.removed } ?: return
        val next = old.copy(generation = randomUuid(), entry = old.entry.copy(status = AnimeDownloadStatus.WAITING,
            error = waitReason(), updatedAt = wallTimeMillis()))
        save(next); records[id] = next
        activeJob?.cancel(); publish()
    }

    private fun kick() {
        if (activeJob != null || !scope.isActive) return
        val candidate = records.values.firstOrNull { it.desired && !it.removed } ?: return
        if (!executionAllowed() || playbackBusy.value) {
            if (candidate.entry.status != AnimeDownloadStatus.WAITING || candidate.entry.error != waitReason()) {
                val next = candidate.copy(entry = candidate.entry.copy(status = AnimeDownloadStatus.WAITING, error = waitReason()))
                save(next); records[next.entry.id] = next; publish()
            }
            return
        }
        val running = candidate.copy(entry = candidate.entry.copy(status = AnimeDownloadStatus.DOWNLOADING,
            error = null, updatedAt = wallTimeMillis()))
        save(running); records[running.entry.id] = running
        activeId = running.entry.id; activeGeneration = running.generation
        val job = scope.launch(start = CoroutineStart.LAZY) { run(running) }
        job.invokeOnCompletion {
            // A queued coroutine can be cancelled before its body/finally ever runs.
            scope.launch { synchronized(lock) {
                if (activeId == running.entry.id && activeGeneration == running.generation) {
                    activeId = null; activeGeneration = null; activeJob = null
                    runCatching { cleanRemoved(running.entry.id); kick() }.onFailure { publish(readablePlayerError(it)) }
                }
            } }
        }
        activeJob = job; publish(); job.start()
    }

    private fun waitReason() = when {
        playbackBusy.value -> "正在为当前播放让出带宽，缓冲完成后继续"
        capabilities.systemBackgroundExecution -> "等待 Android 安排后台下载，回到应用也可继续"
        else -> "回到应用后继续下载"
    }

    private suspend fun run(initial: Record) {
        val id = initial.entry.id
        val generation = initial.generation
        var client: NetworkClient? = null
        var lastPublished = 0L
        var lastSaved = 0L
        fun guard() = synchronized(lock) {
            if (records[id]?.let { it.generation == generation && it.desired && !it.removed } != true)
                throw CancellationException("Offline task replaced")
            if (!executionAllowed() || playbackBusy.value) {
                deferActiveIfNeeded()
                throw CancellationException("Current playback has priority")
            }
        }
        fun progress(bytes: Long, total: Long?, completed: Int, files: Int?) = synchronized(lock) {
            guard()
            val old = records.getValue(id)
            val next = old.copy(entry = old.entry.copy(downloadedBytes = bytes, totalBytes = total,
                completedFiles = completed, totalFiles = files, updatedAt = wallTimeMillis()))
            records[id] = next
            val now = monotonicTimeMillis()
            if (now - lastSaved >= 1_000L) { save(next); lastSaved = now }
            if (now - lastPublished >= 200L) { publish(); lastPublished = now }
        }
        try {
            guard()
            val encoded = readSecret(context, secretKey(id)) ?: error("下载凭据已失效，请重新解析同一来源的这集后下载")
            val asset = decodeAsset(JSONObject(encoded))
            check(asset.episodeId == initial.entry.episode.id && asset.id == initial.entry.assetId &&
                asset.sourceEpisodeId == initial.entry.sourceEpisodeId && asset.edition == initial.entry.edition &&
                asset.sourceId == initial.entry.sourceId && asset.sourceRevision == initial.entry.sourceRevision &&
                asset.sessionEpoch == initial.entry.sessionEpoch && requestIdentity(asset, asset.url) == initial.requestIdentity) {
                "离线任务身份已变化，请重新创建下载"
            }
            check(asset.expiresAt?.let { it > wallTimeMillis() } != false) { "下载地址已过期，请重新解析同一来源的这集后下载" }
            val folder = directory(id)
            if (initial.resetRequired) {
                clearMediaFiles(folder, ::guard)
                synchronized(lock) {
                    guard(); val next = records.getValue(id).copy(resetRequired = false, resources = emptyList(), entryName = null)
                    save(next); records[id] = next
                }
            }
            val transport = NetworkClient(route = asset.route, acceptCompression = false)
            client = transport
            val hls = initial.entryName == "media.m3u8" || asset.mimeType?.contains("mpegurl", true) == true ||
                asset.url.substringBefore('?').endsWith(".m3u8", true)
            val resources = if (hls) null else try {
                listOf(downloadAnimeResource(asset, AnimeResourcePlan("media.bin", asset.url, asset.mimeType), folder,
                    transport, ::guard, { bytes, total -> progress(bytes, total, 0, 1) }, validateVideo = true))
            } catch (_: AnimeHlsDetected) { null }
            val saved: List<AnimeSavedResource>
            val entryName: String
            if (resources != null) { saved = resources; entryName = resources.single().name }
            else {
                val plan = loadAnimeHlsDownload(asset, transport, ::guard)
                guard()
                val previousPlan = synchronized(lock) { records.getValue(id).planIdentity }
                if (previousPlan != null && previousPlan != plan.identity) clearMediaFiles(folder, ::guard)
                synchronized(lock) {
                    guard(); val next = records.getValue(id).copy(planIdentity = plan.identity, entryName = "media.m3u8")
                    save(next); records[id] = next
                }
                val parts = mutableListOf<AnimeSavedResource>()
                var retained = 0L
                plan.resources.forEachIndexed { index, resource ->
                    val result = downloadAnimeResource(asset, resource, folder, transport, ::guard,
                        { bytes, _ -> progress(retained + bytes, null, index, plan.resources.size) })
                    parts += result; retained += result.bytes
                }
                entryName = "media.m3u8"
                val manifests = plan.childPlaylists + (entryName to plan.playlist)
                saved = parts + manifests.map { (name, text) ->
                    guard()
                    val playlist = StorageFile(folder, name)
                    val atomic = StorageAtomicFile(playlist)
                    val output = atomic.startWrite()
                    try { output.write(text.encodeToByteArray()); atomic.finishWrite(output) }
                    catch (failure: Throwable) { atomic.failWrite(output); throw failure }
                    AnimeSavedResource(name, "application/vnd.apple.mpegurl", playlist.length())
                }
            }
            synchronized(lock) {
                guard()
                requireSupportedFormat(saved.single { it.name == entryName }.mimeType)
                val old = records.getValue(id)
                val bytes = saved.sumOf { it.bytes }
                val complete = old.copy(desired = false, entryName = entryName, resources = saved,
                    entry = old.entry.copy(status = AnimeDownloadStatus.COMPLETED, downloadedBytes = bytes,
                        totalBytes = bytes, completedFiles = saved.size, totalFiles = saved.size, error = null,
                        updatedAt = wallTimeMillis()))
                check(filesValid(complete)) { "离线文件尚未完整保存" }
                save(complete); records[id] = complete; publish()
            }
        } catch (cancel: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { synchronized(lock) {
                records[id]?.takeIf { it.generation == generation && !it.removed }?.let { old ->
                    val paused = old.copy(desired = false, entry = old.entry.copy(status = AnimeDownloadStatus.PAUSED,
                        error = "下载已暂停；来源支持续传时会沿用已保存的断点", updatedAt = wallTimeMillis()))
                    runCatching { save(paused) }; records[id] = paused; publish()
                }
            } }
            throw cancel
        } catch (failure: Throwable) {
            synchronized(lock) {
                records[id]?.takeIf { it.generation == generation && !it.removed }?.let { old ->
                    val failed = old.copy(desired = false, entry = old.entry.copy(status = AnimeDownloadStatus.FAILED,
                        error = readablePlayerError(failure), updatedAt = wallTimeMillis()))
                    runCatching { save(failed) }; records[id] = failed; publish()
                }
            }
        } finally {
            runCatching { client?.close() }
            synchronized(lock) {
                if (activeId == id && activeGeneration == generation) {
                    activeId = null; activeGeneration = null; activeJob = null
                    runCatching { cleanRemoved(id); kick() }.onFailure { publish(readablePlayerError(it)) }
                }
            }
        }
    }

    private fun clearMediaFiles(folder: StorageFile, guard: () -> Unit) {
        folder.listFiles().orEmpty().filter { !it.name.startsWith("manifest.json") }.forEach {
            guard(); check(it.deleteRecursively()) { "无法替换旧的离线文件，请检查存储空间" }
        }
    }

    private fun requireSupportedFormat(mime: String) {
        val container = when (mime.substringBefore(';').lowercase()) {
            "video/mp4" -> "MP4"
            "video/x-matroska" -> "MKV"
            "video/webm" -> "WebM"
            "video/mp2t" -> "TS"
            else -> return
        }
        require(container in capabilities.videoContainers) {
            "当前设备的系统播放器不支持 $container 离线文件，请选择 MP4 或静态 HLS 线路"
        }
    }

    private fun filesValid(record: Record): Boolean = record.entryName != null && record.resources.isNotEmpty() &&
        record.resources.any { it.name == record.entryName } && record.resources.map { it.name }.distinct().size == record.resources.size &&
        record.resources.all { resource -> resource.name.matches(FILE_NAME) && resource.bytes > 0 &&
            StorageFile(directory(record.entry.id), resource.name).let { file -> file.isFile && file.length() == resource.bytes } }

    private fun cleanRemoved(id: String) {
        val record = records[id]?.takeIf { it.removed } ?: return
        if (activeId == id || (readers[id] ?: 0) > 0) return
        check(!directory(id).exists() || directory(id).deleteRecursively()) { "无法删除离线文件，请检查存储空间" }
        records.remove(id)
        runCatching { deleteSecret(context, secretKey(record.entry.id)) }.onFailure {
            publish("离线文件已删除；系统凭据清理未完成")
        }
    }

    private fun publish(notice: String? = mutableState.value.notice) {
        mutableState.value = AnimeDownloadsState(records.values.filterNot { it.removed }.map { it.entry }
            .sortedByDescending { it.createdAt }, loading = false, notice = notice)
    }
    private fun directory(id: String): StorageFile { require(id.matches(ID)); return StorageFile(root, id) }
    private fun secretKey(id: String) = "anime.download.$id"
    private fun save(record: Record) = writeAnimeJson(StorageFile(directory(record.entry.id), "manifest.json"), encode(record))

    private fun encode(record: Record): JSONObject {
        val row = record.entry
        return JSONObject().put("schema", 1).put("id", row.id)
            .put("metadata", AnimeDocumentCodec.detail(AnimeDetail(row.subject, listOf(row.episode))))
            .put("source", row.sourceId).put("sourceName", row.sourceName).put("sourceEpisode", row.sourceEpisodeId)
            .put("asset", row.assetId).put("edition", row.edition).put("revision", row.sourceRevision).put("epoch", row.sessionEpoch)
            .put("status", row.status.name).put("bytes", row.downloadedBytes).put("total", row.totalBytes ?: -1L)
            .put("completedFiles", row.completedFiles).put("totalFiles", row.totalFiles ?: -1)
            .put("error", row.error).put("created", row.createdAt).put("updated", row.updatedAt)
            .put("generation", record.generation).put("request", record.requestIdentity)
            .put("desired", record.desired).put("removed", record.removed)
            .put("reset", record.resetRequired).put("entry", record.entryName).put("plan", record.planIdentity)
            .put("resources", JSONArray(record.resources.map { JSONObject().put("name", it.name).put("mime", it.mimeType).put("bytes", it.bytes) }))
    }

    private fun decode(json: JSONObject): Record {
        require(json.getInt("schema") == 1)
        val detail = AnimeDocumentCodec.detail(json.getJSONObject("metadata"))
        require(detail.episodes.size == 1)
        val id = json.getString("id"); require(id.matches(ID))
        val row = AnimeDownloadEntry(id, detail.subject, detail.episodes.single(), json.getString("source"),
            json.optString("sourceName", json.getString("source")), json.getString("sourceEpisode"), json.getString("asset"),
            json.optString("edition"), json.getString("revision"), json.getLong("epoch"),
            AnimeDownloadStatus.valueOf(json.getString("status")), json.optLong("bytes"),
            json.optLong("total", -1).takeIf { it >= 0 }, json.optInt("completedFiles"),
            json.optInt("totalFiles", -1).takeIf { it >= 0 }, json.optString("error", null),
            json.getLong("created"), json.getLong("updated"))
        val resources = json.getJSONArray("resources")
        return Record(row, json.getString("generation"), json.getString("request"), desired = json.optBoolean("desired"),
            removed = json.optBoolean("removed"),
            resetRequired = json.optBoolean("reset"), entryName = json.optString("entry", null),
            planIdentity = json.optString("plan", null), resources = (0 until resources.length()).map { index ->
                val item = resources.getJSONObject(index)
                AnimeSavedResource(item.getString("name"), item.getString("mime"), item.getLong("bytes"))
            })
    }

    private fun encodeAsset(asset: AnimePlaybackAsset): JSONObject = JSONObject().put("id", asset.id).put("source", asset.sourceId)
        .put("sourceEpisode", asset.sourceEpisodeId).put("episode", asset.episodeId).put("url", asset.url).put("title", asset.title)
        .put("headers", JSONObject(asset.headers)).put("mime", asset.mimeType).put("edition", asset.edition)
        .put("expires", asset.expiresAt).put("revision", asset.sourceRevision).put("epoch", asset.sessionEpoch)
        .put("route", asset.route.writeRelayBinding(JSONObject().put("mode", asset.route.mode.name).apply {
            asset.route.proxy?.let { put("proxy", JSONObject().put("host", it.host).put("port", it.port).put("type", it.type.name)) }
        }))

    private fun decodeAsset(json: JSONObject): AnimePlaybackAsset {
        val route = json.getJSONObject("route")
        val proxy = route.optJSONObject("proxy")?.let {
            DownloadProxyEndpoint(it.getString("host"), it.getInt("port"), DownloadProxyType.valueOf(it.getString("type")))
        }
        val headers = json.getJSONObject("headers")
        return AnimePlaybackAsset(json.getString("id"), json.getString("source"), json.getString("sourceEpisode"),
            json.getString("url"), DownloadRouteSnapshot(DownloadRouteMode.valueOf(route.getString("mode")), proxy, readRelayBinding(route)).checked(),
            episodeId = json.getString("episode"), title = json.optString("title"),
            headers = headers.keys().asSequence().associateWith { headers.getString(it) }, mimeType = json.optString("mime", null),
            edition = json.optString("edition"), expiresAt = if (json.isNull("expires")) null else json.getLong("expires"),
            sourceRevision = json.getString("revision"), sessionEpoch = json.getLong("epoch"))
    }

    private companion object {
        val ID = Regex("[a-f0-9]{64}")
        val FILE_NAME = Regex("(?:media\\.bin|(?:media|video|audio)\\.m3u8|(?:segment|init)-[0-9]+\\.bin)")
        val PENDING_STATUSES = setOf(AnimeDownloadStatus.QUEUED, AnimeDownloadStatus.DOWNLOADING, AnimeDownloadStatus.WAITING)
        const val BACKGROUND_SCHEDULE_ERROR = "Android 暂未接收后台下载任务，留在应用内可继续下载"
        const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024
    }
}
