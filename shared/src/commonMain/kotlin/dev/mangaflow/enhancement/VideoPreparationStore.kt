package dev.mangaflow.enhancement

import dev.mangaflow.anime.downloads.AnimeDownloadStore
import dev.mangaflow.anime.downloads.AnimeDownloadStatus
import dev.mangaflow.anime.downloads.AnimeDownloadedPlayback
import dev.mangaflow.anime.player.AnimeLocalMedia
import dev.mangaflow.anime.player.AnimeLocalMediaLease
import dev.mangaflow.anime.player.AnimeLocalResource
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.json.JSONObject
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.*
import dev.mangaflow.storage.AuxiliaryCacheEntry
import dev.mangaflow.storage.AuxiliaryDiskCacheOwner
import dev.mangaflow.storage.StorageCacheStore
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class VideoEnhancementVersion(
    val id: String,
    val downloadId: String,
    val sourceIdentity: String,
    val sourceDigest: String,
    val sourceStamp: String,
    val modelId: String?,
    val modelDigest: String,
    val engineVersion: String,
    val tier: QualityTier,
    val scale: Int,
    val frameMode: FrameMode,
    val frameQuality: FrameQuality,
    val customFps: Int,
    val detectScenes: Boolean,
    val phase: EnhancementTaskPhase,
    val generation: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val durationMillis: Long? = null,
    val bytes: Long = 0,
    val outputDigest: String? = null,
    val outputModifiedAt: Long = 0L,
    val progress: EngineProgress? = null,
    val reason: String? = null,
) {
    val label: String get() = listOfNotNull(width?.let { w -> height?.let { "$w × $it" } },
        fps?.let { "${(it * 100).toInt() / 100.0} FPS" }).joinToString(" · ").ifEmpty { "${scale}× · ${frameMode.label}" }
    fun policy() = EnhancementPolicy(tier = tier, scale = scale, modelId = modelId, frameMode = frameMode,
        frameQuality = frameQuality, customFps = customFps, detectScenes = detectScenes,
        preparation = setOf(PreparationMode.COMPLETE))
}
data class VideoPreparationState(val entries: List<VideoEnhancementVersion> = emptyList(), val loading: Boolean = true,
    val notice: String? = null)

/** Validated offline originals stay leased; every enhanced version has a separate atomic manifest and file. */
class VideoPreparationStore(
    context: PlatformContext,
    private val downloads: AnimeDownloadStore,
    private val center: EnhancementCenter,
) : AuxiliaryDiskCacheOwner {
    private val app = applicationContext(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = StorageFile(cacheDirectory(app), "enhanced-video-v1")
    private val lock = StorageLock()
    private val records = linkedMapOf<String, VideoEnhancementVersion>()
    private val readers = mutableMapOf<String, Int>()
    private val activeJobs = mutableSetOf<String>()
    private val verifiedOutputs = mutableMapOf<String, String>()
    private val ready = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(VideoPreparationState())
    val state: StateFlow<VideoPreparationState> = mutableState.asStateFlow()
    override val ownerId = "enhanced-video-v1"

    init {
        StorageCacheStore.get(app).registerAuxiliaryOwner(this)
        scope.launch {
            try {
                root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(DIGEST) }.forEach { folder ->
                    runCatching {
                        val file = StorageFile(folder, "manifest.json")
                        require(file.length() in 1..32_768)
                        val old = decode(JSONObject(file.readText()))
                        require(old.id == folder.name)
                        val output = output(old.id)
                        val valid = old.phase == EnhancementTaskPhase.COMPLETED && output.isFile && output.length() == old.bytes
                        val row = if (valid) old else old.copy(phase = EnhancementTaskPhase.PAUSED,
                            reason = "上次处理已中断，点击继续可复用通过校验的片段")
                        synchronized(lock) { records[row.id] = row }
                    }.onFailure { synchronized(lock) { publish("部分增强记录无法读取；原视频与已有文件已保留") } }
                }
            } finally { synchronized(lock) { publish() }; ready.complete(Unit) }
            // Verification is deliberately off the opening path. Raw playback never waits for a multi-GB hash.
            val verify = synchronized(lock) { records.values.filter { it.phase == EnhancementTaskPhase.COMPLETED } }
            verify.forEach { row ->
                val file = output(row.id)
                val held = synchronized(lock) {
                    if (file.isFile && file.length() == row.bytes) {
                        readers[row.id] = (readers[row.id] ?: 0) + 1; true
                    } else false
                }
                if (held) try {
                    if (sha256File(file.path) == row.outputDigest) synchronized(lock) {
                        if (records[row.id] == row) verifiedOutputs[row.id] = outputStamp(file, row.outputDigest.orEmpty())
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* The original remains immediately usable. */ }
                finally { synchronized(lock) { release(row.id) } }
            }
        }
        scope.launch {
            ready.await()
            center.scheduler.state.collect { jobs -> synchronized(lock) {
                var changed = false
                jobs.filter { it.id.startsWith(PREFIX) }.forEach { task ->
                    val id = task.id.removePrefix(PREFIX)
                    val old = records[id] ?: return@forEach
                    if (old.generation != task.generation || old.phase == EnhancementTaskPhase.COMPLETED) return@forEach
                    val next = old.copy(phase = task.phase, progress = task.progress, reason = task.reason,
                        updatedAt = wallTimeMillis())
                    records[id] = next; changed = true
                    if (old.phase != next.phase) runCatching { save(next) }
                        .onFailure { publish("增强任务状态未能保存，原视频保留") }
                }
                if (changed) publish()
            } }
        }
        scope.launch {
            ready.await()
            var initial = true
            var complete = emptySet<String>()
            downloads.state.filter { !it.loading }.collect { snapshot ->
                val now = snapshot.entries.filter { it.status == AnimeDownloadStatus.COMPLETED }.map { it.id }.toSet()
                if (!initial && center.settings.policy(EnhancementContent.ANIME).enhanceAfterDownload) {
                    (now - complete).forEach { enqueue(it, automatic = true) }
                }
                initial = false; complete = now
            }
        }
    }

    /** A user-selected target remains unchanged when the device or engine cannot run it. */
    fun enqueue(downloadId: String, policy: EnhancementPolicy = center.settings.policy(EnhancementContent.ANIME),
        automatic: Boolean = false): Job = scope.launch {
        ready.await()
        try {
            policy.checked()
            require(policy.tier != QualityTier.ORIGINAL || policy.frameMode != FrameMode.ORIGINAL) { "请选择画质增强或运动补帧" }
            require(policy.frameMode != FrameMode.AUTO) {
                "尚未取得当前播放显示器的实际刷新率；请明确选择 2×、60 FPS、120 FPS 或自定义目标"
            }
            val inventory = center.engine.inspect()
            val descriptor = inventory.engines.firstOrNull { it.id == EnhancementEngineIds.VIDEOJANAI }
            require(descriptor != null && descriptor.readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL)) {
                descriptor?.detail ?: "当前平台尚无可执行的离线视频增强引擎"
            }
            val model = if (policy.tier == QualityTier.ORIGINAL) null else inventory.models.firstOrNull {
                it.id == policy.modelId && EngineContent.VIDEO in it.contents
            } ?: if (policy.tier in setOf(QualityTier.AUTO, QualityTier.BALANCED)) inventory.models.firstOrNull {
                EngineContent.VIDEO in it.contents && it.nativeScale == policy.scale
            } else null
            require(policy.tier == QualityTier.ORIGINAL || model != null) { "请在画质与性能中选择已安装的视频模型" }
            val scale = if (policy.tier == QualityTier.ORIGINAL) 1 else policy.scale
            require(scale in descriptor.supportedScales) { "该引擎不支持所选倍率" }
            val original = downloads.playback(downloadId)
            val lease = original.localMedia.acquire(original.asset)
            val inputStamp = sourceStamp(lease)
            val digest = try { sourceDigest(lease).also { check(sourceStamp(lease) == inputStamp) { "原视频仍在变化" } } }
                finally { lease.close() }
            val source = sourceIdentity(original)
            val rifeVersion = when (policy.frameQuality) {
                FrameQuality.LIGHT -> "4.25-lite"
                FrameQuality.BALANCED, FrameQuality.FINE -> "4.22"
                FrameQuality.ULTRA -> "4.26"
            }
            val ensemble = policy.frameQuality in setOf(FrameQuality.FINE, FrameQuality.ULTRA)
            val rifeName = "rife_v${rifeVersion.replace("-lite", "_lite")}${if (ensemble) "_ensemble" else ""}.onnx"
            val rife = if (policy.frameMode == FrameMode.ORIGINAL) null else inventory.frameModels.firstOrNull {
                StorageFile(it.path).name == rifeName
            }
            require(policy.frameMode == FrameMode.ORIGINAL || rife != null) { "所选补帧档位需要 $rifeName，请先安装对应模型" }
            val modelDigest = EnhancedAssetIdentity.digest("${model?.sha256.orEmpty()}|${rife?.sha256.orEmpty()}")
            val parameters = listOf(descriptor.version, policy.tier, scale, policy.frameMode, policy.frameQuality,
                policy.customFps, true, model?.id, modelDigest).joinToString("|")
            val id = EnhancedAssetIdentity.digest("${source.length}:$source|$digest|$parameters")
            val row = synchronized(lock) {
                val old = records[id]
                if (old != null && (id in activeJobs || old.phase == EnhancementTaskPhase.COMPLETED && output(id).isFile)) return@launch
                VideoEnhancementVersion(id, downloadId, source, digest, inputStamp, model?.id, modelDigest, descriptor.version,
                    policy.tier, scale, policy.frameMode, policy.frameQuality, policy.customFps, true,
                    EnhancementTaskPhase.QUEUED, (old?.generation ?: 0L) + 1,
                    old?.createdAt ?: wallTimeMillis(), wallTimeMillis()).also {
                        check((readers[id] ?: 0) == 0) { "该增强版本正在播放" }
                        if (output(id).exists()) check(output(id).delete()) { "无法清理未发布的增强输出" }
                        save(it); records[id] = it; activeJobs += id; publish()
                    }
            }
            val task = center.scheduler.submit(PREFIX + id, EnhancementContent.ANIME,
                if (automatic) EnhancementPriority.P3 else EnhancementPriority.P2,
                generation = row.generation, estimatedBytes = 128L * 1024 * 1024,
                isCurrent = { synchronized(lock) { records[id]?.generation == row.generation &&
                    records[id]?.phase != EnhancementTaskPhase.CANCELLED } },
                title = "${original.subject.title} · ${original.episode.title}") { job ->
                val current = downloads.playback(downloadId)
                check(sourceIdentity(current) == source) { "原视频的来源身份已改变" }
                current.localMedia.acquire(current.asset).use { originalLease ->
                    check(sourceDigest(originalLease) == digest) { "原视频内容已改变，请重新创建增强任务" }
                    check(sourceStamp(originalLease) == inputStamp) { "原视频资源已更新，请重新创建增强任务" }
                    job.checkpoint()
                    val folder = directory(id)
                    val temp = StorageFile(folder, "output-${randomUuid()}.mkv")
                    try {
                        val result = center.engine.processVideo(EngineVideoRequest(
                            sourcePath = originalLease.entry.file.path, destinationPath = temp.path,
                            workDirectory = StorageFile(folder, "segments").path, model = model, scale = scale,
                            targetFps = when (policy.frameMode) { FrameMode.FPS60 -> 60.0; FrameMode.FPS120 -> 120.0
                                FrameMode.CUSTOM -> policy.customFps.toDouble(); else -> null },
                            frameMultiplier = if (policy.frameMode == FrameMode.DOUBLE) 2 else null,
                            rifeVersion = rifeVersion, rifeEnsemble = ensemble, expectedRifeSha256 = rife?.sha256,
                            // Scene protection is mandatory. Threshold zero treats nearly every change as a cut.
                            sceneThreshold = .15,
                            segmentSeconds = 60,
                        ), job.progress)
                        job.checkpoint()
                        checkNotNull(result.video) { "视频输出缺少时间轴与音轨校验回执" }
                        check(result.sha256 == sha256File(temp.path)) { "视频输出摘要不一致" }
                        check(result.modelSha256 == row.modelDigest && result.engineVersion == row.engineVersion) {
                            "输出所用模型或引擎与任务身份不一致"
                        }
                        check(sourceStamp(originalLease) == inputStamp && sourceDigest(originalLease) == digest) {
                            "处理期间原视频资源发生变化，结果不再发布"
                        }
                        synchronized(lock) {
                            check(records[id]?.generation == row.generation) { "处理任务已改变" }
                            check((readers[id] ?: 0) == 0 && !output(id).exists()) { "已有增强版本仍在使用" }
                            syncStorageFile(temp)
                            check(temp.renameTo(output(id))) { "增强视频无法原子发布" }
                            val done = row.copy(phase = EnhancementTaskPhase.COMPLETED, width = result.width,
                                height = result.height, fps = result.video.fps, durationMillis = result.video.durationMillis,
                                bytes = result.bytes, outputDigest = result.sha256,
                                outputModifiedAt = output(id).lastModified(), updatedAt = wallTimeMillis())
                            save(done); records[id] = done; publish()
                            verifiedOutputs[id] = outputStamp(output(id), result.sha256)
                        }
                        StorageFile(folder, "segments").deleteRecursively()
                        result.copy(path = output(id).path)
                    } finally { temp.delete() }
                }
            }
            try { task.await() } finally { synchronized(lock) { activeJobs.remove(id) } }
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { synchronized(lock) { publish(error.message ?: "增强未完成，原视频仍可播放") } }
    }

    fun pause(id: String) = center.scheduler.pause(PREFIX + id)
    fun resume(id: String) {
        val row = synchronized(lock) { records[id] } ?: return
        if (synchronized(lock) { id in activeJobs }) center.scheduler.resume(PREFIX + id)
        else enqueue(row.downloadId, row.policy())
    }
    fun cancel(id: String) {
        center.scheduler.cancel(PREFIX + id)
        scope.launch { ready.await(); synchronized(lock) { records[id]?.let {
            val cancelled = it.copy(phase = EnhancementTaskPhase.CANCELLED, generation = it.generation + 1, reason = "用户取消")
            save(cancelled); records[id] = cancelled; publish()
        } } }
    }

    /** Read/verify before issuing a native capability; deletion and acquisition share the same lock. */
    suspend fun playback(original: AnimeDownloadedPlayback, versionId: String? = null): AnimeDownloadedPlayback = withContext(Dispatchers.IO) {
        ready.await()
        val row = synchronized(lock) { records.values.filter { it.downloadId == original.localMedia.downloadId &&
            it.phase == EnhancementTaskPhase.COMPLETED && if (versionId != null) it.id == versionId else {
                val desired = center.settings.policy(EnhancementContent.ANIME)
                (desired.tier != QualityTier.ORIGINAL || desired.frameMode != FrameMode.ORIGINAL) &&
                    it.tier == desired.tier &&
                    it.scale == (if (desired.tier == QualityTier.ORIGINAL) 1 else desired.scale) &&
                    it.frameMode == desired.frameMode &&
                    (it.frameMode == FrameMode.ORIGINAL || it.frameQuality == desired.frameQuality && it.detectScenes) &&
                    (it.frameMode != FrameMode.CUSTOM || it.customFps == desired.customFps) &&
                    (desired.modelId == null || desired.modelId == it.modelId)
            } }
            .maxByOrNull { it.updatedAt } } ?: return@withContext original
        if (row.sourceIdentity != sourceIdentity(original)) return@withContext original
        val source = original.localMedia.acquire(original.asset)
        val validSource = try { sourceStamp(source) == row.sourceStamp } finally { source.close() }
        if (!validSource) return@withContext original
        val file = output(row.id)
        val valid = synchronized(lock) {
            file.isFile && file.length() == row.bytes && records[row.id] == row &&
                verifiedOutputs[row.id] == outputStamp(file, row.outputDigest.orEmpty())
        }
        if (!valid) return@withContext original
        val asset = original.asset.copy(url = file.toURI(), mimeType = "video/x-matroska", subtitles = emptyList())
        val capability = AnimeLocalMedia(original.localMedia.downloadId, asset.id, asset.episodeId, asset.sourceId,
            asset.sourceEpisodeId, asset.sourceRevision, asset.sessionEpoch, asset.edition, enhancedVersion = true) {
            synchronized(lock) {
                check(records[row.id]?.outputDigest == row.outputDigest && file.isFile && file.length() == row.bytes &&
                    verifiedOutputs[row.id] == outputStamp(file, row.outputDigest.orEmpty())) {
                    "增强缓存已清理，请重新打开原视频"
                }
                readers[row.id] = (readers[row.id] ?: 0) + 1
                AnimeLocalMediaLease(file.name, mapOf(file.name to AnimeLocalResource(file, "video/x-matroska", row.bytes))) {
                    synchronized(lock) { release(row.id) }
                }
            }
        }
        original.copy(asset = asset, localMedia = capability)
    }

    override suspend fun snapshot(): List<AuxiliaryCacheEntry> = withContext(Dispatchers.IO) {
        ready.await(); synchronized(lock) { records.values.filter { it.phase == EnhancementTaskPhase.COMPLETED }.mapNotNull(::cacheEntry) }
    }
    override suspend fun remove(expected: AuxiliaryCacheEntry, canRemove: () -> Boolean): Long? = withContext(Dispatchers.IO) {
        ready.await(); synchronized(lock) {
            val row = records[expected.key] ?: return@synchronized null
            if (!canRemove() || (readers[row.id] ?: 0) > 0 || row.id in activeJobs || cacheEntry(row) != expected) return@synchronized null
            if (!directory(row.id).deleteRecursively()) return@synchronized null
            records.remove(row.id); verifiedOutputs.remove(row.id); publish(); expected.bytes
        }
    }
    fun removeVersion(id: String): Job = scope.launch {
        val entry = synchronized(lock) { records[id]?.let(::cacheEntry) } ?: return@launch
        if (remove(entry) { true } == null) synchronized(lock) { publish("该版本正在播放或处理，稍后再清理") }
    }

    suspend fun usage(): EnhancedAssetUsage = withContext(Dispatchers.IO) {
        ready.await(); synchronized(lock) {
            val completed = records.values.filter { it.phase == EnhancementTaskPhase.COMPLETED }
                .sumOf { output(it.id).takeIf { file -> file.isFile }?.length() ?: 0L }
            val total = directoryBytes(root)
            EnhancedAssetUsage(completed, (total - completed).coerceAtLeast(0L),
                records.values.count { it.phase == EnhancementTaskPhase.COMPLETED }, readers.count { it.value > 0 })
        }
    }
    suspend fun clear(): Long = withContext(Dispatchers.IO) {
        ready.await(); synchronized(lock) {
            var freed = 0L
            records.values.toList().filter { (readers[it.id] ?: 0) == 0 && it.id !in activeJobs }.forEach { row ->
                val folder = directory(row.id)
                val bytes = directoryBytes(folder)
                if (folder.deleteRecursively()) { records.remove(row.id); verifiedOutputs.remove(row.id); freed += bytes }
            }
            publish(); freed
        }
    }

    private fun cacheEntry(row: VideoEnhancementVersion): AuxiliaryCacheEntry? {
        val file = output(row.id)
        if (!file.isFile) return null
        return AuxiliaryCacheEntry(row.id, directoryBytes(directory(row.id)), file.lastModified(),
            "${row.generation}:${row.outputDigest}:${row.bytes}")
    }
    private fun release(id: String) { val n = (readers[id] ?: 1) - 1; if (n <= 0) readers.remove(id) else readers[id] = n }
    private fun directory(id: String) = StorageFile(root, id.also { require(it.matches(DIGEST)) })
    private fun output(id: String) = StorageFile(directory(id), "enhanced.mkv")
    private fun publish(notice: String? = mutableState.value.notice) {
        mutableState.value = VideoPreparationState(records.values.sortedByDescending { it.createdAt }, loading = false, notice = notice)
    }
    private fun sourceIdentity(value: AnimeDownloadedPlayback): String = with(value.asset) {
        listOf(sourceId, sourceRevision, sessionEpoch.toString(), id, sourceEpisodeId, episodeId,
            edition, value.subject.id).joinToString("") { "${it.length}:$it" }
    }
    private fun sourceDigest(lease: AnimeLocalMediaLease): String = EnhancedAssetIdentity.digest(
        lease.resources.entries.sortedBy { it.key }.map { (name, resource) ->
            "$name:${resource.size}:${sha256File(resource.file.path)}"
        }.joinToString("\n"))
    private fun sourceStamp(lease: AnimeLocalMediaLease): String = EnhancedAssetIdentity.digest(
        lease.resources.entries.sortedBy { it.key }.joinToString("\n") { (name, resource) ->
            "$name:${resource.size}:${resource.file.length()}:${resource.file.lastModified()}"
        })
    private fun outputStamp(file: StorageFile, digest: String) = "$digest:${file.length()}:${file.lastModified()}"
    private fun directoryBytes(file: StorageFile): Long = if (file.isFile) file.length()
        else file.listFiles().orEmpty().sumOf(::directoryBytes)
    private fun save(row: VideoEnhancementVersion) {
        val folder = directory(row.id)
        check(folder.isDirectory || folder.mkdirs()) { "无法保存增强版本" }
        val atomic = StorageAtomicFile(StorageFile(folder, "manifest.json"))
        val stream = atomic.startWrite()
        try { stream.write(encode(row).toString().encodeToByteArray()); atomic.finishWrite(stream) }
        catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
    private fun encode(r: VideoEnhancementVersion) = JSONObject().put("schema", 1).put("id", r.id).put("download", r.downloadId)
        .put("source", r.sourceIdentity).put("sourceDigest", r.sourceDigest).put("sourceStamp", r.sourceStamp).put("modelId", r.modelId ?: "")
        .put("modelDigest", r.modelDigest).put("engine", r.engineVersion).put("tier", r.tier.name).put("scale", r.scale)
        .put("frameMode", r.frameMode.name).put("frameQuality", r.frameQuality.name).put("customFps", r.customFps)
        .put("scene", r.detectScenes).put("phase", r.phase.name).put("generation", r.generation)
        .put("created", r.createdAt).put("updated", r.updatedAt).put("width", r.width ?: 0).put("height", r.height ?: 0)
        .put("fps", r.fps ?: -1.0).put("duration", r.durationMillis ?: -1).put("bytes", r.bytes)
        .put("outputDigest", r.outputDigest ?: "").put("outputModified", r.outputModifiedAt).put("reason", r.reason ?: "")
    private fun decode(j: JSONObject): VideoEnhancementVersion {
        require(j.optInt("schema") == 1)
        return VideoEnhancementVersion(j.getString("id"), j.getString("download"), j.getString("source"), j.getString("sourceDigest"), j.optString("sourceStamp"),
            j.optString("modelId").takeIf { it.isNotBlank() }, j.getString("modelDigest"), j.getString("engine"),
            QualityTier.valueOf(j.getString("tier")), j.getInt("scale"), FrameMode.valueOf(j.getString("frameMode")),
            FrameQuality.valueOf(j.getString("frameQuality")), j.getInt("customFps"), j.optBoolean("scene", true),
            EnhancementTaskPhase.valueOf(j.getString("phase")), j.getLong("generation"), j.getLong("created"), j.getLong("updated"),
            j.optInt("width").takeIf { it > 0 }, j.optInt("height").takeIf { it > 0 }, j.optDouble("fps", -1.0).takeIf { it > 0 },
            j.optLong("duration", -1).takeIf { it >= 0 }, j.optLong("bytes"), j.optString("outputDigest").takeIf { it.isNotBlank() },
            outputModifiedAt = j.optLong("outputModified"), reason = j.optString("reason").takeIf { it.isNotBlank() })
    }
    private companion object { val DIGEST = Regex("[a-f0-9]{64}"); const val PREFIX = "video-enhance:" }
}
