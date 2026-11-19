package dev.mangaflow.experience

import coil3.ImageLoader
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.applicationContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.storage.PreferenceChangeListener
import dev.mangaflow.platform.storage.StorageLock
import dev.mangaflow.platform.storage.preferences
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val maximumBackgroundPageParallelism: Int get() = if (platformKind == PlatformKind.DESKTOP) 12 else 6

/** Stored intent, effective limits and measured usage are separate. One controller owns admissions. */
class ExperiencePolicyStore private constructor(context: PlatformContext) {
    private val app = applicationContext(context)
    private val disk = preferences(app, "mangaflow_library")
    private val lock = StorageLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configurationWrites = Mutex()
    private var configurationWriteActive = false
    private var failedConfiguration: ConfigurationRecord? = null
    private var resources = ExperienceResources()
    private var mode = readMode()
    private val mutableOverrides = MutableStateFlow(readOverrides())
    val overrides: StateFlow<ExperienceOverrides> = mutableOverrides.asStateFlow()
    private var memoryUntil = 0L
    private var heatUntil = 0L
    private var powerUntil = 0L
    private var warmUntil = 0L
    private var cpuUntil = 0L
    private var storageLow = false
    private var interactionUntil = 0L
    private var interactionJob: Job? = null
    private var resourceSampling: Job? = null
    private var resourceRefreshPending = false
    private var foregroundUsers = 0
    private var readerUsers = 0
    private var readerWaiting = false
    private var watchers = 0
    private var observerGeneration = 0L
    private var stopObserver: (() -> Unit)? = null
    private var sampling: Job? = null
    private var memoryCache: ExperienceMemoryCache? = null
    private var diskCache: ExperienceDiskCache? = null
    private var memoryUsageJob: Job? = null
    private var memoryRefreshPending = false
    private var memoryExpiryWatcher: Job? = null
    private var cacheUsageJob: Job? = null
    private var cacheRevision = 0L
    private var sampledDiskBytes = 0L
    private var activeSavedTransfers = 0
    private var activePages = 0
    private var activeImages = 0
    private var activeReaderImages = 0
    private var activeResolves = 0
    private var activeDecodes = 0
    private var activeTileDecodes = 0
    private var activePreparations = 0
    private var activeEnhancements = 0
    private var enhancementReservedBytes = 0L
    private val realtimeEnhancementOwners = mutableSetOf<String>()
    private var activeOptionalResolves = 0
    private var activeOptionalDecodes = 0
    private var framePressureUntil = 0L
    private var decodePressureUntil = 0L
    private var foregroundResolveWaiters = 0
    private var foregroundDecodeWaiters = 0
    private var nextDecodeBudgetAt = 0L
    private var activePrefetch = 0
    private var prefetchReserved = 0L
    private var prefetchStreamingBytes = 0L
    private var streamingReleaseGeneration = 0L
    private var materializationReleaseGeneration = 0L
    private var materializationStreamingBytes = 0L
    private var materializationReservedBytes = 0L
    private var queued = 0
    private var nextTicket = 0L
    private val pageQueue = linkedMapOf<Long, String>()
    private val chapterQueue = linkedMapOf<Long, String>()
    private val chapters = mutableSetOf<String>()
    private val sourceActive = mutableMapOf<String, Int>()
    private val sourceReaderWaiters = mutableMapOf<String, Int>()
    private val sources = mutableMapOf<String, SourceObservation>()
    private val measurements = ExperienceMeasurements()
    private val revision = MutableStateFlow(0L)
    internal val admissionChanges: StateFlow<Long> = revision.asStateFlow()
    private val mutableState = MutableStateFlow(budget())
    val state: StateFlow<ExperienceBudget> = mutableState.asStateFlow()
    private val mutableUsage = MutableStateFlow(ExperienceUsage())
    val usage: StateFlow<ExperienceUsage> = mutableUsage.asStateFlow()
    private val preferenceListener = PreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("experience_")) synchronized(lock) {
            // Both platform editors can notify after changing preference memory, even when
            // commit fails. The writer reconciles their final record before publishing.
            if (!configurationWriteActive) publishConfigurationRecord(readConfigurationRecord())
        }
    }

    init {
        disk.registerOnSharedPreferenceChangeListener(preferenceListener)
        refreshResources()
    }

    suspend fun setMode(next: ExperienceMode): Boolean = persistConfiguration { _, current -> next to current }

    private fun normalized(value: ExperienceOverrides): ExperienceOverrides = value.copy(
            ahead = value.ahead?.coerceAtLeast(0), behind = value.behind?.coerceAtLeast(0),
            prefetchRequests = value.prefetchRequests?.coerceAtLeast(1),
            prefetchMiB = value.prefetchMiB?.coerceAtLeast(1),
            readerRequests = value.readerRequests?.coerceAtLeast(1),
            resolveRequests = value.resolveRequests?.coerceAtLeast(1),
            decodeRequests = value.decodeRequests?.coerceAtLeast(1),
            downloadPages = value.downloadPages?.coerceAtLeast(1),
            sourcePages = value.sourcePages?.coerceAtLeast(1), chapters = value.chapters?.coerceAtLeast(1),
            memoryMiB = value.memoryMiB?.coerceAtLeast(1),
            diskMiB = value.diskMiB?.coerceAtLeast(1), refreshHz = value.refreshHz?.coerceAtLeast(1),
            decodedPrefetchPages = value.decodedPrefetchPages?.coerceAtLeast(0),
            standbySources = value.standbySources?.coerceAtLeast(1), standbyPages = value.standbyPages.coerceAtLeast(1),
            standbyMiB = value.standbyMiB?.coerceAtLeast(1),
            contentCache = value.contentCache.bounded(), runtime = value.runtime.checked(),
            recovery = value.recovery.checked(),
        )

    suspend fun setOverrides(value: ExperienceOverrides): Boolean = persistConfiguration { currentMode, _ ->
        currentMode to value
    }

    /** Change only this field against the latest confirmed configuration inside the writer. */
    suspend fun setSourceLocked(locked: Boolean): Boolean = persistConfiguration { currentMode, current ->
        currentMode to current.copy(recovery = current.recovery.copy(sourceLocked = locked))
    }

    /** A settings draft never mutates live admissions, pressure observations or caches. */
    fun previewBudget(overrides: ExperienceOverrides, mode: ExperienceMode = state.value.mode): ExperienceBudget =
        synchronized(lock) { budget(normalized(overrides), mode) }

    /** Compare the saved baseline before committing both mode and all numeric choices together. */
    suspend fun applyConfiguration(
        mode: ExperienceMode, overrides: ExperienceOverrides,
        expectedMode: ExperienceMode? = null, expectedOverrides: ExperienceOverrides? = null,
    ): Boolean = persistConfiguration(expectedMode, expectedOverrides) { _, _ -> mode to overrides }

    private data class ConfigurationRecord(val mode: String?, val overrides: String?)
    private data class ConfigurationWrite(val before: ConfigurationRecord, val candidate: ConfigurationRecord)

    /** A coherent two-key snapshot also observes restores whose listener has not run yet. */
    private fun readConfigurationRecord(): ConfigurationRecord = disk.all.let { values ->
        ConfigurationRecord(values[MODE_KEY] as? String, values[OVERRIDES_KEY] as? String)
    }

    private fun writeConfigurationRecord(value: ConfigurationRecord): Boolean = disk.edit().apply {
        if (value.mode == null) remove(MODE_KEY) else putString(MODE_KEY, value.mode)
        if (value.overrides == null) remove(OVERRIDES_KEY) else putString(OVERRIDES_KEY, value.overrides)
    }.commit()

    /** Called under the policy lock; no disk IO or suspend work occurs while publishing. */
    private fun publishConfigurationRecord(record: ConfigurationRecord) {
        // A failed rollback must not let a delayed native listener publish the failed candidate.
        if (record == failedConfiguration) return
        failedConfiguration = null
        val nextMode = ExperienceMode.entries.firstOrNull { it.name == record.mode } ?: ExperienceMode.AUTO
        val next = normalized(decodeExperienceOverrides(record.overrides))
        if (mode == nextMode && mutableOverrides.value == next) return
        val oldReserve = diskReserve()
        mode = nextMode
        mutableOverrides.value = next
        if (oldReserve != diskReserve()) resources.freeDiskBytes?.let { storageLow = it <= diskReserve() }
        publish()
    }

    /** The existing application scope owns every accepted write, including its failure cleanup. */
    private suspend fun persistConfiguration(
        expectedMode: ExperienceMode? = null, expectedOverrides: ExperienceOverrides? = null,
        select: (ExperienceMode, ExperienceOverrides) -> Pair<ExperienceMode, ExperienceOverrides>,
    ): Boolean = scope.async {
        configurationWrites.withLock {
            withContext(NonCancellable) {
                val write = synchronized(lock) {
                    val before = readConfigurationRecord()
                    publishConfigurationRecord(before)
                    if (expectedMode != null && mode != expectedMode ||
                        expectedOverrides != null && mutableOverrides.value != expectedOverrides) null
                    else {
                        val (nextMode, requested) = select(mode, mutableOverrides.value)
                        val candidate = ConfigurationRecord(nextMode.name, encodeExperienceOverrides(normalized(requested)))
                        configurationWriteActive = true
                        ConfigurationWrite(before, candidate)
                    }
                } ?: return@withContext false
                var attempted = false
                var committed = false
                var failure: Throwable? = null
                try {
                    try {
                        // A restore that reached preference memory after the baseline check wins.
                        if (readConfigurationRecord() == write.before) {
                            attempted = true
                            committed = writeConfigurationRecord(write.candidate)
                            if (!committed) failure = IllegalStateException("体验配置未能保存，请检查存储空间后重试。")
                        }
                    } catch (problem: Throwable) { failure = problem }
                    synchronized(lock) {
                        if (attempted && !committed) failedConfiguration = write.candidate
                        else if (committed) failedConfiguration = null
                    }
                    if (attempted && !committed && readConfigurationRecord() == write.candidate) {
                        // Editors update their in-memory map before returning commit's result.
                        // Restore that map too, but never overwrite an observed external change.
                        runCatching { writeConfigurationRecord(write.before) }
                    }
                    val accepted = synchronized(lock) {
                        val latest = readConfigurationRecord()
                        if (attempted && !committed && latest == write.candidate) failedConfiguration = write.candidate
                        else if (committed && latest == write.candidate) failedConfiguration = null
                        configurationWriteActive = false
                        publishConfigurationRecord(latest)
                        committed && latest == write.candidate
                    }
                    failure?.let { throw it }
                    accepted
                } finally {
                    synchronized(lock) { configurationWriteActive = false }
                }
            }
        }
    }.await()

    /** Native snapshots only; no bandwidth probes, hardware grades or synthetic benchmarks. */
    fun refreshResources() {
        val job = synchronized(lock) {
            if (resourceSampling != null) {
                resourceRefreshPending = true
                return
            }
            resourceRefreshPending = false
            scope.launch(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[Job]
                try {
                    do {
                        val sampled = try { readExperienceResources(app) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { ExperienceResources() }
                        synchronized(lock) {
                            val now = monotonicTimeMillis()
                            if (sampled.memoryPressure == true) memoryUntil = now + mutableOverrides.value.runtime.memoryRecoverySeconds.toLong() * 1_000L
                            if ((sampled.thermalPressure ?: 0) >= 2) heatUntil = now + mutableOverrides.value.runtime.thermalRecoverySeconds.toLong() * 1_000L
                            if ((sampled.thermalPressure ?: 0) >= 1) warmUntil = now + mutableOverrides.value.runtime.warmRecoverySeconds.toLong() * 1_000L
                            if ((sampled.cpuLoad ?: 0.0) > mutableOverrides.value.runtime.cpuBusyPercent / 100.0)
                                cpuUntil = now + mutableOverrides.value.runtime.cpuRecoverySeconds.toLong() * 1_000L
                            if (sampled.lowPower == true) powerUntil = now + mutableOverrides.value.runtime.powerRecoverySeconds.toLong() * 1_000L
                            sampled.freeDiskBytes?.let { free ->
                                val reserve = diskReserve()
                                if (free <= reserve) storageLow = true
                                else if (free > reserve + mutableOverrides.value.runtime.diskRecoveryMiB.toLong() * EXPERIENCE_MIB) storageLow = false
                            }
                            resources = sampled
                            publish()
                            updateUsage()
                        }
                        refreshCacheUsage()
                        val again = synchronized(lock) {
                            if (resourceRefreshPending) { resourceRefreshPending = false; true }
                            else { if (resourceSampling === owner) resourceSampling = null; false }
                        }
                    } while (again)
                } finally {
                    synchronized(lock) { if (resourceSampling === owner) resourceSampling = null }
                }
            }.also { resourceSampling = it }
        }
        job.start()
    }

    /** Events and the light snapshot loop exist only for a visible app or an actual worker. */
    fun startObserving(): () -> Unit {
        val generation = synchronized(lock) {
            watchers++
            if (watchers == 1) {
                sampling = scope.launch {
                    overrides.map { it.runtime.resourceSampleSeconds }.distinctUntilChanged().collectLatest { seconds ->
                        while (isActive) { refreshResources(); delay(seconds.toLong() * 1_000L) }
                    }
                }
                ++observerGeneration
            } else null
        }
        if (generation != null) scope.launch(Dispatchers.Main.immediate) {
            val release = try { observeExperienceResources(app, ::resourceChanged) } catch (_: Exception) { {} }
            val keep = synchronized(lock) {
                if (watchers > 0 && generation == observerGeneration) { stopObserver = release; true } else false
            }
            if (!keep) release()
        }
        refreshResources()
        var released = false
        return {
            val release = synchronized(lock) {
                if (released) null else {
                    released = true
                    watchers--
                    if (watchers == 0) {
                        observerGeneration++
                        sampling?.cancel(); sampling = null
                        stopObserver.also { stopObserver = null }
                    } else null
                }
            }
            if (release != null) scope.launch(Dispatchers.Main.immediate) { release() }
        }
    }

    fun startForeground(): () -> Unit = retainActivity(reader = false)
    fun startReading(): () -> Unit = retainActivity(reader = true)

    /** Enhancement shares these observations and this admission owner with reading/downloads. */
    internal fun enhancementWaitReason(priority: Int, estimatedBytes: Long = 0L): String? = synchronized(lock) {
        enhancementWaitReasonLocked(priority, estimatedBytes)
    }

    private fun enhancementWaitReasonLocked(priority: Int, estimatedBytes: Long): String? {
        val current = mutableState.value
        return when {
            resources.memoryPressure == true || (resources.thermalPressure ?: 0) >= 2 -> "设备保护：内存或温度压力"
            storageLow || resources.freeDiskBytes?.let { it <= current.diskReserveBytes } == true -> "存储空间不足"
            platformKind != PlatformKind.DESKTOP && !current.foreground -> "返回应用后继续处理"
            realtimeEnhancementOwners.isNotEmpty() -> "当前播放优先"
            priority >= 2 && (readerUsers > 0 || readerWaiting) -> "当前阅读优先"
            priority >= 1 && resources.cpuLoad?.let { it >= .92 } == true -> "等待 CPU 负载回落"
            estimatedBytes > 0 && resources.availableMemoryBytes?.let {
                estimatedBytes > (it - enhancementReservedBytes).coerceAtLeast(0L) / 2L
            } == true -> "等待可用内存"
            else -> null
        }
    }

    internal fun tryAcquireEnhancement(priority: Int, estimatedBytes: Long = 0L): (() -> Unit)? = synchronized(lock) {
        if (activeEnhancements > 0 || enhancementWaitReasonLocked(priority, estimatedBytes) != null) return@synchronized null
        activeEnhancements++
        enhancementReservedBytes += estimatedBytes.coerceAtLeast(0L)
        var released = false
        val release: () -> Unit = {
            synchronized(lock) {
                if (!released) {
                    released = true
                    activeEnhancements--
                    enhancementReservedBytes = (enhancementReservedBytes - estimatedBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
                    revision.value++
                }
            }
        }
        release
    }

    /** A native realtime renderer reserves the lane; queued optional GPU work yields. */
    internal fun setRealtimeEnhancement(owner: String, active: Boolean) = synchronized(lock) {
        val changed = if (active) realtimeEnhancementOwners.add(owner) else realtimeEnhancementOwners.remove(owner)
        if (changed) revision.value++
    }
    private fun retainActivity(reader: Boolean): () -> Unit {
        synchronized(lock) {
            if (reader) readerUsers++ else foregroundUsers++
            publish()
        }
        var released = false
        return {
            synchronized(lock) {
                if (!released) {
                    released = true
                    if (reader) { readerUsers--; if (readerUsers == 0) readerWaiting = false } else foregroundUsers--
                    publish()
                }
            }
        }
    }

    fun setReaderWaiting(waiting: Boolean) = synchronized(lock) {
        if (readerWaiting != waiting) { readerWaiting = waiting; publish() }
    }

    /** An input lease includes settling motion, then releases the window high-rate vote. */
    fun noteInteraction() = synchronized(lock) {
        interactionUntil = monotonicTimeMillis() + 1_500L
        if (interactionJob?.isActive != true) {
            publish()
            interactionJob = scope.launch {
                while (true) {
                    val remaining = synchronized(lock) {
                        (interactionUntil - monotonicTimeMillis()).also {
                            if (it <= 0L) { interactionJob = null; publish() }
                        }
                    }
                    if (remaining > 0L) delay(remaining)
                    else break
                }
            }
        }
    }

    private fun resourceChanged(memoryWarning: Boolean) {
        if (memoryWarning) synchronized(lock) { memoryUntil = monotonicTimeMillis() + mutableOverrides.value.runtime.memoryRecoverySeconds.toLong() * 1_000L; publish() }
        refreshResources()
    }

    internal fun attachImageLoader(loader: ImageLoader, memory: ExperienceMemoryCache, disk: ExperienceDiskCache) {
        check(loader.memoryCache === memory && loader.diskCache === disk)
        synchronized(lock) {
            memoryCache = memory
            diskCache = disk
            memoryExpiryWatcher?.cancel()
            // One app-owned deadline waits even when the settings/reader observers close.
            // Empty caches and explicit TTL 0 suspend without a background polling loop.
            memoryExpiryWatcher = scope.launch {
                memory.expiryDeadline.collectLatest { deadline ->
                    if (deadline != null) {
                        delay((deadline - monotonicTimeMillis()).coerceAtLeast(1L))
                        refreshMemoryUsage()
                    }
                }
            }
            publish()
        }
        dev.mangaflow.storage.StorageCacheStore.get(app).attachMemoryReclaimer(memory::removeOutsideWindow) { memory.keys.map { it.key }.toSet() }
        refreshCacheUsage()
    }

    /** Decoded cache accounting never waits for a native resource read or filesystem scan. */
    private fun refreshMemoryUsage() {
        val job = synchronized(lock) {
            if (memoryUsageJob != null) {
                memoryRefreshPending = true
                return
            }
            memoryRefreshPending = false
            scope.launch(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[Job]
                try {
                    do {
                        val memory = synchronized(lock) { memoryCache }
                        val snapshot = try { memory?.snapshot() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                        synchronized(lock) {
                            if (memoryCache === memory) mutableUsage.value = mutableUsage.value.copy(
                                memoryBytes = snapshot?.bytes,
                                memoryLimit = snapshot?.limit,
                                memoryCacheBasisBytes = memory?.automaticBasisBytes,
                                memoryCache = snapshot,
                                observedImageMemoryPeakBytes = maxObserved(mutableUsage.value.observedImageMemoryPeakBytes, snapshot?.bytes),
                            )
                        }
                        val again = synchronized(lock) {
                            if (memoryRefreshPending) { memoryRefreshPending = false; true }
                            else { if (memoryUsageJob === owner) memoryUsageJob = null; false }
                        }
                    } while (again)
                } finally {
                    synchronized(lock) { if (memoryUsageJob === owner) memoryUsageJob = null }
                }
            }.also { memoryUsageJob = it }
        }
        job.start()
    }

    fun refreshCacheUsage() {
        refreshMemoryUsage()
        val job = synchronized(lock) {
            // Reader indicators, settings and maintenance share this same local sample.
            // A slow filesystem must not accumulate one refresh job per visible tick.
            if (cacheUsageJob != null) return
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val disk = synchronized(lock) { diskCache }
                    val storage = dev.mangaflow.storage.StorageCacheStore.get(app)
                    storage.maintenanceTick()
                    val global = storage.sample(overrides.value.runtime.cacheSampleSeconds.toLong() * 1_000L)
                    val imageBytes = disk?.size
                    val pending = disk?.resizePending == true
                    val error = disk?.resizeError
                    val cacheGroups = global.items.groupBy { item ->
                        when (item.resource.backend) {
                            dev.mangaflow.storage.CacheBackend.SCRIPT -> "SCRIPT"
                            dev.mangaflow.storage.CacheBackend.JM -> "JM"
                            dev.mangaflow.storage.CacheBackend.CATALOG -> "CATALOG"
                            dev.mangaflow.storage.CacheBackend.AUXILIARY -> when (item.resource.auxiliaryOwnerId) {
                                "browse" -> "BROWSE"
                                "metadata" -> "METADATA"
                                "rankings" -> "RANKINGS"
                                else -> "DATA"
                            }
                            else -> item.resource.area.name
                        }
                    }
                    val cacheLabels = linkedMapOf("READING" to "阅读图片", "SCRIPT" to "脚本图片", "JM" to "JM 图片",
                        "ARTWORK" to "封面与头像", "CATALOG" to "章节目录", "BROWSE" to "浏览列表",
                        "METADATA" to "作品资料与评分", "RANKINGS" to "榜单", "DATA" to "其他资料",
                        "PANELS" to "分镜结果", "ANIME" to "番剧缓冲")
                    val cacheAreas = cacheLabels.map { (id, label) ->
                        val owned = cacheGroups[id].orEmpty()
                        val extraBytes = when (id) { "CATALOG" -> global.catalogBytes; "DATA" -> global.auxiliaryBytes; else -> 0L }
                        val extraReclaimable = if (id == "CATALOG") global.catalogReclaimableBytes else 0L
                        ExperienceCacheAreaUsage(id, label, owned.sumOf { it.bytes } + extraBytes,
                            protectedBytes = owned.filter { it.protected }.sumOf { it.bytes } + extraBytes - extraReclaimable,
                            reclaimableBytes = owned.filterNot { it.protected }.sumOf { it.bytes } + extraReclaimable)
                    }.filter { it.bytes > 0L } + listOf(
                        ExperienceCacheAreaUsage("UNCLASSIFIED", "未归类图片", global.coilUnclassifiedBytes,
                            protectedBytes = if (global.legacyReclaimable) 0L else global.coilUnclassifiedBytes,
                            reclaimableBytes = if (global.legacyReclaimable) global.coilUnclassifiedBytes else 0L),
                        ExperienceCacheAreaUsage("INDEX", "索引与临时文件", global.indexBytes + global.temporaryBytes,
                            protectedBytes = global.indexBytes + global.temporaryBytes),
                    ).filter { it.bytes > 0L }
                    synchronized(lock) {
                        sampledDiskBytes = global.totalBytes
                        mutableUsage.value = mutableUsage.value.copy(
                            imageDiskBytes = imageBytes, diskBytes = global.totalBytes,
                            diskLimit = state.value.diskCacheBytes, diskSampledAtMillis = storage.measuredAtMillis,
                            diskProtectedBytes = (global.totalBytes - global.reclaimableBytes).coerceAtLeast(0L),
                            diskReclaimableBytes = global.reclaimableBytes,
                            diskAreas = cacheAreas,
                            diskResizePending = pending || global.pending || global.totalBytes > state.value.diskCacheBytes,
                            cacheError = error,
                            observedDiskPeakBytes = maxObserved(mutableUsage.value.observedDiskPeakBytes, global.totalBytes),
                        )
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    synchronized(lock) { mutableUsage.value = mutableUsage.value.copy(cacheError = "缓存用量暂时无法读取") }
                } finally { synchronized(lock) { cacheUsageJob = null } }
            }.also { cacheUsageJob = it }
        }
        job.start()
    }

    /** Drops reusable decoded images only. Visible/native owners and original files retain their lifetimes. */
    fun clearDecodedMemory(): Deferred<Long> = scope.async {
        val memory = synchronized(lock) { memoryCache }
        val freed = memory?.clearAndMeasure() ?: 0L
        refreshCacheUsage()
        freed
    }

    internal suspend fun <T> withChapterAdmission(
        id: String, maxWaitMillis: Long? = null, onWaiting: (AdmissionWait?) -> Unit = {}, block: suspend () -> T,
    ): T =
        admit({ chapters.size < mutableState.value.chapterParallelism && id !in chapters },
            { chapters += id }, { chapters -= id }, chapter = id, maxWaitMillis = maxWaitMillis,
            waiting = { protectionWait() ?: AdmissionWait(AdmissionWaitReason.CHAPTER_LIMIT) }, onWaiting = onWaiting, block = block)

    internal suspend fun <T> withBackgroundPageAdmission(
        source: String, maxWaitMillis: Long? = null, onWaiting: (AdmissionWait?) -> Unit = {}, block: suspend () -> T,
    ): T =
        admit({ activePages < mutableState.value.backgroundPages && sourceAvailable(source) },
            { activePages++; enterSource(source) },
            { activePages--; releaseSource(source) }, source, fairPages = true,
            maxWaitMillis = maxWaitMillis, waiting = { backgroundWait(source) }, onWaiting = onWaiting, block = block)

    internal suspend fun <T> withSavedTransferAdmission(block: suspend () -> T): T =
        admit({ activeSavedTransfers.toLong() < mutableState.value.backgroundPages.toLong() +
            mutableState.value.readerRequests.toLong() },
            { activeSavedTransfers++ }, { activeSavedTransfers-- }, block = block)

    internal suspend fun <T> withImageAdmission(
        reader: Boolean, source: String? = null, onWaiting: (AdmissionWait?) -> Unit = {}, block: suspend () -> T,
    ): T {
        if (reader && source != null) synchronized(lock) {
            sourceReaderWaiters[source] = (sourceReaderWaiters[source] ?: 0) + 1
            revision.value++
        }
        try {
            return admit(
                ready = {
                    (if (reader) activeReaderImages < mutableState.value.readerRequests
                        else activeImages < mutableState.value.backgroundPages) &&
                        (source == null || sourceAvailable(source, reader))
                },
                enter = {
                    if (reader) activeReaderImages++ else activeImages++
                    if (source != null) enterSource(source)
                },
                leave = {
                    if (reader) activeReaderImages-- else activeImages--
                    if (source != null) releaseSource(source)
                },
                source = source,
                waiting = { if (source == null) AdmissionWait(AdmissionWaitReason.GLOBAL_LIMIT) else backgroundWait(source) },
                onWaiting = onWaiting, block = block,
            )
        } finally {
            if (reader && source != null) synchronized(lock) {
                val left = (sourceReaderWaiters[source] ?: 1) - 1
                if (left > 0) sourceReaderWaiters[source] = left else sourceReaderWaiters.remove(source)
                revision.value++
            }
        }
    }

    /** JS/native metadata resolution is separate from transport and pixel decoding. */
    internal suspend fun <T> withResolveAdmission(
        promoted: () -> Boolean = { true }, onWaiting: (AdmissionWait?) -> Unit = {}, block: suspend () -> T,
    ): T {
        val foreground = promoted()
        var enteredOptional = false
        synchronized(lock) { if (foreground) foregroundResolveWaiters++ }
        try {
            return admit(
                ready = {
                    val cap = mutableState.value.resolveRequests
                    val optionalCap = if (readerUsers > 0 && cap > 1 && mutableOverrides.value.resolveRequests == null) cap - 1 else cap
                    activeResolves < cap && (promoted() || foregroundResolveWaiters == 0 &&
                        activeOptionalResolves < optionalCap && !readerWaiting && canResolveOptional())
                },
                enter = { activeResolves++; enteredOptional = !promoted(); if (enteredOptional) activeOptionalResolves++ },
                leave = { activeResolves--; if (enteredOptional) activeOptionalResolves-- },
                abandon = { !promoted() && !canResolveOptional() }, onWaiting = onWaiting, block = block,
            )
        } finally {
            synchronized(lock) {
                if (foreground) foregroundResolveWaiters--
                revision.value++
            }
        }
    }

    /** Local bytes use the same decode permits and need no source or network reservation. */
    internal suspend fun <T> withDecodeAdmission(
        optional: Boolean = false, promoted: () -> Boolean = { false },
        onWaiting: (AdmissionWait?) -> Unit = {}, maxWaitMillis: Long? = null, block: suspend () -> T,
    ): T {
        val started = monotonicTimeMillis()
        val foreground = !optional || promoted()
        var enteredOptional = false
        synchronized(lock) { if (foreground) foregroundDecodeWaiters++ }
        try {
            return admit(
                ready = {
                    val cap = mutableState.value.decodeRequests
                    val optionalCap = if (readerUsers > 0 && cap > 1 && mutableOverrides.value.decodeRequests == null) cap - 1 else cap
                    activeDecodes < cap && (!optional || promoted() || foregroundDecodeWaiters == 0 &&
                        activeOptionalDecodes < optionalCap && !readerWaiting && canDecodeOptional())
                },
                enter = { activeDecodes++; enteredOptional = optional && !promoted(); if (enteredOptional) activeOptionalDecodes++ },
                leave = { activeDecodes--; if (enteredOptional) activeOptionalDecodes-- },
                abandon = { optional && !promoted() && !canDecodeOptional() }, onWaiting = onWaiting,
                // Bound only admission. Running decoders keep their permit until they exit.
                maxWaitMillis = maxWaitMillis,
            ) {
                synchronized(lock) {
                    measurements.recordDecodeQueue(monotonicTimeMillis() - started)
                    updateUsage()
                }
                block()
            }
        } finally {
            synchronized(lock) {
                if (foreground) foregroundDecodeWaiters--
                revision.value++
            }
        }
    }

    private fun canResolveOptional(): Boolean {
        val budget = mutableState.value
        // CPU/thermal pressure already reduces the published capacity/window. Rejecting
        // an otherwise retained candidate would make its completion immediately requeue it.
        return budget.foreground &&
            (budget.prefetchAhead > 0 || budget.prefetchBehind > 0 || budget.decodedPrefetchPages > 0 || budget.browseWarmup)
    }

    private fun canDecodeOptional(): Boolean {
        val budget = mutableState.value
        // Original restoration (e.g. script JM) is CPU work even with preview warming off.
        // Severe protection closes the candidate window; moderate load reduces decode capacity.
        return budget.foreground &&
            (budget.prefetchAhead > 0 || budget.prefetchBehind > 0 || budget.decodedPrefetchPages > 0 || budget.browseWarmup)
    }

    internal fun canDecodePrefetch(): Boolean = synchronized(lock) {
        !readerWaiting && mutableState.value.decodedPrefetchPages > 0 && canDecodeOptional()
    }

    internal suspend fun <T> withPrefetchAdmission(
        source: String, estimatedBytes: Long, promoted: () -> Boolean,
        onWaiting: (AdmissionWait?) -> Unit = {}, block: suspend () -> T,
    ): T = withOptionalAdmission(source, estimatedBytes, promoted, browseOnly = false, onWaiting = onWaiting, block = block)

    /** Speculation uses the existing prefetch/source permits and disappears as reading starts. */
    internal suspend fun <T> withBrowseWarmupAdmission(
        source: String, estimatedBytes: Long = EXPERIENCE_MIB, block: suspend () -> T,
    ): T = withOptionalAdmission(source, estimatedBytes, { false }, browseOnly = true, block = block)

    private suspend fun <T> withOptionalAdmission(
        source: String, estimatedBytes: Long, promoted: () -> Boolean, browseOnly: Boolean,
        onWaiting: (AdmissionWait?) -> Unit = {}, block: suspend () -> T,
    ): T {
        val reserved = estimatedBytes.coerceAtLeast(EXPERIENCE_MIB)
        return admit(
            ready = {
                val b = mutableState.value
                !readerWaiting && (!browseOnly || b.browseWarmup && readerUsers == 0) &&
                    activePrefetch < b.prefetchRequests && reserved <= b.prefetchBytes - prefetchReserved && sourceAvailable(source)
            },
            enter = { activePrefetch++; prefetchReserved += reserved; enterSource(source) },
            leave = { activePrefetch--; prefetchReserved -= reserved; releaseSource(source) },
            source = source,
            abandon = {
                if (promoted()) throw PrefetchPromoted()
                mutableState.value.prefetchBytes < reserved || !mutableState.value.foreground ||
                    browseOnly && (!mutableState.value.browseWarmup || readerUsers > 0)
            },
            onWaiting = onWaiting, block = block,
        )
    }
    /** Read-only recovery hint; actual work must still acquire the normal admission. */
    internal fun canRecoverPrefetchAdmission(stage: PrefetchStage, source: String, estimate: Long): Boolean = synchronized(lock) {
        val b = mutableState.value
        if (!b.foreground || readerWaiting) return@synchronized false
        when (stage) {
            PrefetchStage.RESOLVING -> {
                val cap = b.resolveRequests
                val optionalCap = if (readerUsers > 0 && cap > 1 && mutableOverrides.value.resolveRequests == null) cap - 1 else cap
                activeResolves < cap && foregroundResolveWaiters == 0 && activeOptionalResolves < optionalCap && canResolveOptional()
            }
            PrefetchStage.CHECKING -> {
                val cap = b.decodeRequests
                val optionalCap = if (readerUsers > 0 && cap > 1 && mutableOverrides.value.decodeRequests == null) cap - 1 else cap
                activeDecodes < cap && foregroundDecodeWaiters == 0 && activeOptionalDecodes < optionalCap && canDecodeOptional()
            }
            else -> activePrefetch < b.prefetchRequests && estimate.coerceAtLeast(EXPERIENCE_MIB) <=
                b.prefetchBytes - prefetchReserved && sourceAvailable(source)
        }
    }

    internal fun wakeAdmissions(): Unit = synchronized(lock) { revision.value++; Unit }

    internal val tileDecodeParallelism: Int get() = state.value.runtime.tileDecodeRequests
        ?: if (platformKind == PlatformKind.DESKTOP) 2 else 1

    internal suspend fun <T> withTileDecodeAdmission(block: suspend () -> T): T = admit(
        ready = { activeTileDecodes < tileDecodeParallelism },
        enter = { activeTileDecodes++ }, leave = { activeTileDecodes-- }, block = block,
    )

    /** A dynamic permit retains its owner until finally releases it; shrinking never cancels an accepted write. */
    internal suspend fun acquirePreparationPermit(maxWaitMillis: Long, onWaiting: (AdmissionWait?) -> Unit): () -> Unit {
        val deadline = monotonicTimeMillis() + maxWaitMillis.coerceAtLeast(1L)
        var accepted = false
        synchronized(lock) { queued++; updateUsage() }
        try {
            while (!accepted) {
                currentCoroutineContext().ensureActive()
                val before = synchronized(lock) {
                    if (activePreparations < (mutableState.value.runtime.chapterPreparationRequests ?: 2)) {
                        activePreparations++; queued--; accepted = true; updateUsage()
                    }
                    revision.value
                }
                if (accepted) break
                val wait = AdmissionWait(AdmissionWaitReason.CHAPTER_LIMIT)
                onWaiting(wait)
                val remaining = deadline - monotonicTimeMillis()
                if (remaining <= 0L) throw ExperienceAdmissionDeferred(wait)
                withTimeoutOrNull(remaining) { revision.first { it != before } }
            }
            onWaiting(null)
            var released = false
            return {
                synchronized(lock) {
                    if (!released) { released = true; activePreparations--; revision.value++; updateUsage() }
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (accepted) activePreparations-- else queued--
                revision.value++; updateUsage()
            }
            throw failure
        }
    }

    private suspend fun <T> admit(
        ready: () -> Boolean, enter: () -> Unit, leave: () -> Unit,
        source: String? = null, abandon: () -> Boolean = { false }, fairPages: Boolean = false,
        chapter: String? = null, maxWaitMillis: Long? = null,
        waiting: () -> AdmissionWait = { AdmissionWait(AdmissionWaitReason.GLOBAL_LIMIT) },
        onWaiting: (AdmissionWait?) -> Unit = {}, block: suspend () -> T,
    ): T {
        var admitted = false
        val deadline = maxWaitMillis?.let { monotonicTimeMillis() + it.coerceAtLeast(1L) }
        var previousWait: AdmissionWait? = null
        val ticket = synchronized(lock) {
            queued++
            (++nextTicket).also {
                if (fairPages) pageQueue[it] = requireNotNull(source)
                if (chapter != null) chapterQueue[it] = chapter
                updateUsage()
            }
        }
        try {
            while (!admitted) {
                currentCoroutineContext().ensureActive()
                var wakeAt: Long? = null
                var currentWait: AdmissionWait? = null
                val before = synchronized(lock) {
                    if (abandon()) throw CancellationException("预取预算已收敛")
                    val fair = (!fairPages || pageQueue.entries.firstOrNull { sourceAvailable(it.value) }?.key == ticket) &&
                        (chapter == null || chapterQueue.entries.firstOrNull { it.value !in chapters }?.key == ticket)
                    val capacity = ready()
                    if (fair && capacity) {
                        pageQueue.remove(ticket)
                        chapterQueue.remove(ticket)
                        enter(); admitted = true; queued--; updateUsage()
                    } else {
                        currentWait = if (capacity && !fair) AdmissionWait(AdmissionWaitReason.FAIR_QUEUE) else waiting()
                        wakeAt = source?.let { sources[it]?.blockedUntil }?.takeIf { it > monotonicTimeMillis() }
                    }
                    revision.value
                }
                // Store callbacks may acquire another lock; never call them under the policy lock.
                if (admitted) onWaiting(null)
                else {
                    val state = requireNotNull(currentWait)
                    if (state != previousWait) { onWaiting(state); previousWait = state }
                    val now = monotonicTimeMillis()
                    if (deadline != null && now >= deadline) throw ExperienceAdmissionDeferred(state)
                    val next = listOfNotNull(wakeAt, deadline).minOrNull()
                    if (next == null) revision.first { it != before }
                    else withTimeoutOrNull((next - now).coerceAtLeast(1L)) { revision.first { it != before } }
                }
            }
            return block()
        } finally {
            synchronized(lock) {
                if (admitted) leave() else queued--
                pageQueue.remove(ticket)
                chapterQueue.remove(ticket)
                revision.value++
                updateUsage()
            }
        }
    }

    /** Real monotonic server cooldown, without claiming a source is otherwise healthy. */
    internal fun sourceCooldownRemainingMillis(source: String): Long = synchronized(lock) {
        ((sources[source]?.blockedUntil ?: 0L) - monotonicTimeMillis()).coerceAtLeast(0L)
    }

    private fun sourceAvailable(source: String, reader: Boolean = false): Boolean {
        val now = monotonicTimeMillis()
        val observed = sources[source]
        if ((observed?.blockedUntil ?: 0L) > now) return false
        val ceiling = mutableState.value.sourceParallelism.coerceAtLeast(1)
        val learned = if (mutableOverrides.value.sourcePages != null) ceiling
            else minOf(observed?.parallelism ?: 1, ceiling)
        val active = sourceActive[source] ?: 0
        if (reader) {
            // One foreground lane may sit above learned optional concurrency, but never above
            // the user's/effective source ceiling or an advertised 429/503 cooldown.
            return active.toLong() < minOf(ceiling.toLong(), learned.toLong() + 1L)
        }
        if ((sourceReaderWaiters[source] ?: 0) > 0) return false
        val reserve = if (foregroundUsers > 0 && readerUsers > 0 && ceiling > 1 &&
            mutableOverrides.value.sourcePages == null) 1 else 0
        return active < minOf(learned, ceiling - reserve)
    }

    private fun protectionWait(): AdmissionWait? = when {
        resources.networkAvailable == false -> AdmissionWait(AdmissionWaitReason.NO_NETWORK)
        storageLow -> AdmissionWait(AdmissionWaitReason.STORAGE_LOW)
        (resources.thermalPressure ?: 0) >= 3 -> AdmissionWait(AdmissionWaitReason.THERMAL)
        !mutableState.value.foreground && !mutableOverrides.value.allowBackgroundDownloads ->
            AdmissionWait(AdmissionWaitReason.BACKGROUND_RESTRICTED)
        mutableState.value.foreground && readerUsers > 0 && readerWaiting && mutableOverrides.value.yieldToReading ->
            AdmissionWait(AdmissionWaitReason.READING_PRIORITY)
        else -> null
    }

    private fun backgroundWait(source: String): AdmissionWait {
        protectionWait()?.let { return it }
        val now = monotonicTimeMillis()
        val observed = sources[source]
        if (observed != null && observed.blockedUntil > now) {
            return AdmissionWait(AdmissionWaitReason.SOURCE_BACKOFF, observed.blockedUntil - now)
        }
        if (!sourceAvailable(source)) return AdmissionWait(AdmissionWaitReason.SOURCE_LIMIT)
        return AdmissionWait(when {
            resources.memoryPressure == true || memoryUntil > now -> AdmissionWaitReason.MEMORY_PRESSURE
            (resources.thermalPressure ?: 0) >= 1 || warmUntil > now -> AdmissionWaitReason.THERMAL
            mutableState.value.foreground && readerUsers > 0 && mutableOverrides.value.yieldToReading -> AdmissionWaitReason.READING_PRIORITY
            else -> AdmissionWaitReason.GLOBAL_LIMIT
        })
    }
    private fun enterSource(source: String) {
        val active = (sourceActive[source] ?: 0) + 1
        sourceActive[source] = active
        sources[source]?.let { it.windowPeak = maxOf(it.windowPeak, active) }
    }

    private fun releaseSource(source: String) {
        val left = (sourceActive[source] ?: 1) - 1
        if (left > 0) sourceActive[source] = left else sourceActive.remove(source)
    }

    internal fun recordTransfer(source: String, code: Int?, bytes: Long, elapsed: Long, retryAfter: String? = null, failed: Boolean = false) =
        synchronized(lock) {
            val now = monotonicTimeMillis()
            if (sources.size >= 64 && source !in sources) {
                sources.keys.firstOrNull { (sourceActive[it] ?: 0) == 0 }?.let(sources::remove)
            }
            val observed = sources.getOrPut(source) { SourceObservation() }
            val slow = bytes >= 64L * 1024L && elapsed > 0L &&
                bytes.toDouble() * 1000.0 / elapsed < 64L * 1024L
            if (code == 429 || code == 503) {
                // Server backoff wins over throughput learning; concurrent replies never shorten it.
                observed.blockedUntil = maxOf(observed.blockedUntil, now + experienceRetryDelay(retryAfter))
                observed.recoverAfter = maxOf(observed.recoverAfter, observed.blockedUntil + 30_000L)
                observed.parallelism = 1
                observed.resetWindow()
                observed.clearTrial()
            } else if (failed || code != null && code >= 500 || slow) {
                observed.recoverAfter = maxOf(observed.recoverAfter, now + 30_000L)
                observed.parallelism = (observed.parallelism - 1).coerceAtLeast(1)
                observed.resetWindow()
                observed.clearTrial()
            } else if (code != null && code in 200..299 && bytes > 0L && elapsed > 0L && now >= observed.recoverAfter) {
                if (observed.windowStartedAt == 0L) observed.windowStartedAt = (now - elapsed).coerceAtLeast(1L)
                observed.windowBytes += bytes
                observed.windowSuccesses++
                observed.windowPeak = maxOf(observed.windowPeak, sourceActive[source] ?: 1)
                observed.windowLatencies.addLast(elapsed)
                if (observed.windowLatencies.size > 32) observed.windowLatencies.removeFirst()
                val duration = now - observed.windowStartedAt
                if (observed.windowSuccesses >= 4 && duration >= 4_000L && observed.windowBytes >= 256L * 1024L) {
                    val rate = observed.windowBytes.toDouble() * 1000.0 / duration
                    val meanBytes = observed.windowBytes.toDouble() / observed.windowSuccesses
                    val sorted = observed.windowLatencies.sorted()
                    val tail = sorted[((sorted.size * 95 + 99) / 100 - 1).coerceIn(sorted.indices)]
                    val limit = mutableState.value.sourceParallelism
                    val saturated = observed.windowPeak >= observed.parallelism
                    if (observed.trialFrom > 0) {
                        // Compare completed-byte throughput and similar-size request tails,
                        // rather than calling a higher request count a performance improvement.
                        val comparable = observed.baselineMeanBytes > 0.0 &&
                            meanBytes / observed.baselineMeanBytes in .5..2.0
                        val tailWorse = comparable && tail > maxOf(15_000L, observed.baselineTailMillis * 2L)
                        val gain = rate / observed.baselineRate.coerceAtLeast(1.0)
                        if (gain < 1.08) observed.plateauWindows++ else observed.plateauWindows = 0
                        if (gain < .85 || tailWorse || observed.plateauWindows >= 2) {
                            observed.parallelism = observed.trialFrom.coerceAtLeast(1)
                            observed.nextIncreaseAt = now + 60_000L
                            observed.recoverAfter = maxOf(observed.recoverAfter, now + 15_000L)
                            observed.clearTrial()
                        } else if (gain >= 1.08) {
                            observed.clearTrial()
                            observed.nextIncreaseAt = maxOf(observed.nextIncreaseAt, now + 8_000L)
                        }
                    } else if (saturated && now >= observed.nextIncreaseAt && observed.parallelism < limit) {
                        observed.trialFrom = observed.parallelism
                        observed.baselineRate = rate
                        observed.baselineTailMillis = tail
                        observed.baselineMeanBytes = meanBytes
                        observed.parallelism++
                        observed.nextIncreaseAt = now + 8_000L
                    }
                    observed.resetWindow()
                }
            }
            measurements.recordTransfer(bytes, elapsed)
            revision.value++
            updateUsage()
        }

    internal fun recordImage(elapsed: Long, memory: Boolean, disk: Boolean, prefetch: Boolean, key: String, bytes: Long?) =
        synchronized(lock) {
            measurements.recordImage(elapsed, memory, disk, prefetch, key, bytes)
            updateUsage()
        }
    internal fun recordDecode(elapsedMillis: Long, decodedBytes: Long, optional: Boolean) = synchronized(lock) {
        measurements.recordDecode(elapsedMillis, decodedBytes, optional)
        val now = monotonicTimeMillis()
        if (readerUsers > 0 && elapsedMillis >= 120L) decodePressureUntil = now + 15_000L
        // Re-budget from real output sizes without persisting or resizing for every decode.
        if (now >= nextDecodeBudgetAt) {
            nextDecodeBudgetAt = now + 2_000L
            publish()
        }
        updateUsage()
    }
    internal fun estimatedPageBytes(key: String): Long = synchronized(lock) { measurements.estimate(key) }
    internal fun acceptPrefetchChunk(bytes: Long, owner: PrefetchResourceOwner, memoryBacked: Boolean = false) = synchronized(lock) {
        val budget = mutableState.value
        val streamNeed = owner.streaming + bytes
        val materialNeed = owner.materialization + if (memoryBacked) bytes else 0L
        // Include this attempt's earlier chunks: a too-large image must not become a
        // transient failure merely because its final chunk would fit an empty pool.
        if (streamNeed > budget.prefetchBytes || memoryBacked && materialNeed > budget.materializationBytes)
            throw PrefetchCapacityExceeded(streamNeed, if (memoryBacked) materialNeed else 0L)
        val streamBlocked = bytes > budget.prefetchBytes - prefetchStreamingBytes
        val materialBlocked = memoryBacked && bytes > budget.materializationBytes -
            materializationStreamingBytes - materializationReservedBytes
        if (streamBlocked || materialBlocked) throw PrefetchResourceBusy(owner,
            if (streamBlocked) streamingReleaseGeneration - owner.streamingReleases else null,
            if (materialBlocked) materializationReleaseGeneration - owner.materializationReleases else null,
            streamNeed, materialNeed)
        prefetchStreamingBytes += bytes
        owner.streaming += bytes
        if (memoryBacked) { materializationStreamingBytes += bytes; owner.materialization += bytes }
    }
    internal fun releasePrefetchBytes(bytes: Long, owner: PrefetchResourceOwner, memoryBacked: Boolean = false) = synchronized(lock) {
        prefetchStreamingBytes -= bytes
        owner.streaming -= bytes
        if (memoryBacked) { materializationStreamingBytes -= bytes; owner.materialization -= bytes }
        if (bytes > 0L) {
            streamingReleaseGeneration++; owner.streamingReleases++
            if (memoryBacked) { materializationReleaseGeneration++; owner.materializationReleases++ }
        }
    }
    internal fun canRetryPrefetchResource(failure: PrefetchResourceBusy): Boolean = synchronized(lock) {
        val externalRelease = failure.streamingGeneration?.let {
            it != streamingReleaseGeneration - failure.owner.streamingReleases
        } == true || failure.materializationGeneration?.let {
            it != materializationReleaseGeneration - failure.owner.materializationReleases
        } == true
        externalRelease &&
            (failure.streamingBytes == 0L || failure.streamingBytes <= mutableState.value.prefetchBytes - prefetchStreamingBytes) &&
            (failure.materializationBytes == 0L || failure.materializationBytes <= mutableState.value.materializationBytes -
                materializationStreamingBytes - materializationReservedBytes)
    }
    internal fun canRetryPrefetchCapacity(failure: PrefetchCapacityExceeded): Boolean = synchronized(lock) {
        // The failed demand exceeded at least one empty-pool cap. Reaching that exact
        // demand therefore requires real upward capacity recovery, not arbitrary jitter.
        (failure.streamingBytes == 0L || failure.streamingBytes <= mutableState.value.prefetchBytes - prefetchStreamingBytes) &&
            (failure.materializationBytes == 0L || failure.materializationBytes <= mutableState.value.materializationBytes -
                materializationStreamingBytes - materializationReservedBytes)
    }
    /** Temporary image working-set reservation, independent from transferred/original-window bytes. */
    internal fun reserveMaterialization(bytes: Long, optional: Boolean, owner: PrefetchResourceOwner): () -> Unit = synchronized(lock) {
        require(bytes >= 0L && bytes <= Long.MAX_VALUE - materializationReservedBytes)
        val needed = owner.materialization + bytes
        if (optional && needed > mutableState.value.materializationBytes)
            throw PrefetchCapacityExceeded(materializationBytes = needed)
        if (optional && bytes > mutableState.value.materializationBytes - materializationStreamingBytes - materializationReservedBytes)
            throw PrefetchResourceBusy(owner, null,
                materializationReleaseGeneration - owner.materializationReleases, owner.streaming, needed)
        // A reservation is not evidence of allocated/resident process memory.
        materializationReservedBytes += bytes
        owner.materialization += bytes
        var held = true
        val release: () -> Unit = {
            synchronized(lock) {
                if (held) {
                    held = false
                    materializationReservedBytes -= bytes
                    owner.materialization -= bytes
                    if (bytes > 0L) { materializationReleaseGeneration++; owner.materializationReleases++ }
                }
            }
        }
        release
    }
    internal fun canWritePrefetch(): Boolean = synchronized(lock) {
        mutableState.value.prefetchBytes > 0L && diskCache?.acceptsPrefetchWrites != false
    }
    fun recordFrame(durationNanos: Long) = synchronized(lock) {
        if (measurements.recordFrame(durationNanos)) {
            updateUsage()
            val now = monotonicTimeMillis()
            // Actual frame work, not an invented FPS or an assumed display refresh rate.
            if (readerUsers > 0 && (mutableUsage.value.frameP95Millis ?: 0.0) > 32.0 &&
                framePressureUntil < now + 5_000L) {
                framePressureUntil = now + 15_000L
                publish()
            }
        }
    }

    private fun budget(o: ExperienceOverrides = mutableOverrides.value, selectedMode: ExperienceMode = mode): ExperienceBudget {
        val mode = selectedMode
        val desktop = platformKind == PlatformKind.DESKTOP
        val tier = resourceTier(desktop)
        val goal = goalBudget(desktop, tier, mode)
        val now = monotonicTimeMillis()
        val warning = resources.memoryPressure == true || memoryUntil > now
        val hot = (resources.thermalPressure ?: 0) >= 2 || heatUntil > now
        val warm = (resources.thermalPressure ?: 0) >= 1 || warmUntil > now
        val heapHigh = resources.processHeapBytes?.let { used ->
            resources.processHeapLimitBytes?.takeIf { it > 0L }?.let { limit ->
                used.toDouble() / limit > o.runtime.heapBusyPercent / 100.0 || limit - used < 32L * EXPERIENCE_MIB
            }
        } == true
        val physicalFloor = (if (desktop) 512L else 256L) * EXPERIENCE_MIB
        val physicalTight = resources.availableMemoryBytes?.let { available ->
            available < maxOf(physicalFloor, (resources.totalMemoryBytes ?: 0L) / 20L)
        } == true
        val lowBattery = o.batteryProtection && resources.charging != true && resources.batteryPercent?.let { it <= o.runtime.lowBatteryPercent } == true
        val power = resources.lowPower == true || powerUntil > now || lowBattery
        val cpuBusy = (resources.cpuLoad ?: 0.0) > o.runtime.cpuBusyPercent / 100.0 || cpuUntil > now
        val frameBusy = framePressureUntil > now
        val decodeBusy = decodePressureUntil > now
        val lowRam = resources.lowMemoryDevice == true
        val severe = warning || heapHigh || physicalTight || hot
        val reserve = diskReserve(o)
        val free = resources.freeDiskBytes
        val diskLow = free?.let { it <= reserve || storageLow && reserve == diskReserve() &&
            it <= reserve + o.runtime.diskRecoveryMiB.toLong() * EXPERIENCE_MIB } ?: storageLow
        val foreground = foregroundUsers > 0
        val reading = foreground && readerUsers > 0 && o.yieldToReading
        var networkCap = when (tier) { 0 -> 2; 1 -> 6; 2 -> 8; else -> 12 }
        var resolveCap = when (tier) { 0 -> 2; 1 -> 4; 2 -> 6; else -> 8 }
        var decodeCap = when (tier) { 0 -> 1; 1 -> 2; 2 -> 3; else -> 4 }
        decodeCap = minOf(decodeCap, resources.processorCount?.coerceIn(1, 4) ?: 1)
        var backgroundCap = when (tier) { 0 -> 2; 1 -> 4; 2 -> if (desktop) 8 else 6; else -> maximumBackgroundPageParallelism }
        if (warm) {
            networkCap = minOf(networkCap, maxOf(2, goal.prefetchRequests))
            resolveCap = minOf(resolveCap, maxOf(2, goal.resolveRequests / 2))
            decodeCap = minOf(decodeCap, maxOf(1, goal.decodeRequests / 2))
            backgroundCap = minOf(backgroundCap, maxOf(1, goal.backgroundPages / 2))
        }
        if (power || lowRam || cpuBusy) {
            networkCap = minOf(networkCap, 2)
            resolveCap = minOf(resolveCap, 2)
            decodeCap = 1
            backgroundCap = minOf(backgroundCap, if (desktop) 2 else 1)
        }
        if (frameBusy || decodeBusy) {
            networkCap = minOf(networkCap, 2); resolveCap = minOf(resolveCap, 2)
            decodeCap = 1; backgroundCap = minOf(backgroundCap, 1)
        }
        if (severe) { networkCap = 1; resolveCap = 1; decodeCap = 1; backgroundCap = 1 }
        var background = o.downloadPages ?: minOf(goal.backgroundPages, backgroundCap, maximumBackgroundPageParallelism)
        if (reading && o.downloadPages == null) background = minOf(background, maxOf(1, goal.backgroundPages / 2))
        if (diskLow || resources.networkAvailable == false || (!foreground && !o.allowBackgroundDownloads) ||
            (resources.thermalPressure ?: 0) >= 3 || reading && readerWaiting) background = 0

        // This is a candidate window. Cached/offline candidates still resolve and decode when
        // transport is disabled; only the separate byte/request allocation permits new traffic.
        var ahead = if (o.prefetchMode == PrefetchMode.MANUAL) o.ahead ?: goal.prefetchAhead else goal.prefetchAhead
        var behind = if (o.prefetchMode == PrefetchMode.MANUAL) o.behind ?: goal.prefetchBehind else goal.prefetchBehind
        if (o.prefetchMode != PrefetchMode.MANUAL && warm) {
            ahead = minOf(ahead, maxOf(1, goal.prefetchAhead * 2 / 3))
            behind = minOf(behind, 2)
        }
        if (o.prefetchMode != PrefetchMode.MANUAL && (power || lowRam || cpuBusy)) {
            ahead = minOf(ahead, maxOf(1, goal.prefetchAhead / 3))
            behind = minOf(behind, 1)
        }
        if ((o.prefetchMode != PrefetchMode.MANUAL && severe) || o.prefetchMode == PrefetchMode.OFF || !foreground) { ahead = 0; behind = 0 }
        val fraction = when {
            warning || heapHigh || physicalTight -> .06
            lowRam -> minOf(goal.memoryCacheFraction, .10)
            power -> minOf(goal.memoryCacheFraction, .12)
            else -> goal.memoryCacheFraction
        }
        val ceiling = o.diskMiB?.toLong()?.times(EXPERIENCE_MIB) ?: goal.diskCacheBytes
        val bytes = if (o.diskMiB != null || free == null) ceiling else minOf(ceiling,
            (free + sampledDiskBytes - reserve).coerceAtLeast(0L)) / EXPERIENCE_MIB * EXPERIENCE_MIB
        val heapHeadroom = resources.processHeapLimitBytes?.let { limit ->
            resources.processHeapBytes?.let { used -> (limit - used).coerceAtLeast(0L) }
        }
        val pixelReserve = maxOf(32L * EXPERIENCE_MIB, measurements.estimatedDecodedPageBytes() * 2L)
        // Script fetches can hold complete encoded arrays plus transform copies. Only a
        // fraction of measured app headroom can be optional encoded work at one time.
        val heapTransferBudget = heapHeadroom?.let { ((it - pixelReserve).coerceAtLeast(0L) / 3L) }
            ?: (if (desktop) 24L else 12L) * EXPERIENCE_MIB
        val physicalTransferBudget = resources.availableMemoryBytes?.let { it / 12L } ?: Long.MAX_VALUE
        val transportHeadroom = minOf(heapTransferBudget, physicalTransferBudget)
        val networkAllowed = resources.networkAvailable != false && !diskLow &&
            !(resources.metered == true && !o.allowMeteredPrefetch)
        var prefetchBytes = if (ahead == 0 && behind == 0 || !networkAllowed) 0L
            else o.prefetchMiB?.toLong()?.times(EXPERIENCE_MIB) ?: minOf(goal.prefetchBytes, bytes)
        if (o.prefetchMiB == null && resources.metered == null && !o.allowMeteredPrefetch)
            prefetchBytes = minOf(prefetchBytes, (if (mode == ExperienceMode.PERFORMANCE) 64L else 24L) * EXPERIENCE_MIB)
        var decodedPages = o.decodedPrefetchPages ?: goal.decodedPrefetchPages
        if (o.decodedPrefetchPages == null && warm) decodedPages = minOf(decodedPages, maxOf(1, goal.decodedPrefetchPages / 2))
        if (o.decodedPrefetchPages == null && (severe || power || cpuBusy || lowRam || frameBusy)) decodedPages = 0
        else if (o.decodedPrefetchPages == null && decodeBusy) decodedPages = minOf(decodedPages, 1)
        // Keep at least half the live Coil budget for visible/backward images and details.
        // The recent decode p90 is only a conservative sizing estimate, never a hit guarantee.
        val previewEstimate = measurements.estimatedDecodedPageBytes()
        val memoryLimit = o.memoryMiB?.toLong()?.times(EXPERIENCE_MIB)
            ?: memoryCache?.automaticBasisBytes?.let { (it * fraction).toLong() } ?: 64L * EXPERIENCE_MIB
        val protectedPreviewBytes = maxOf(memoryLimit / 2L, previewEstimate * 2L)
        val warmPageLimit = ((memoryLimit - protectedPreviewBytes).coerceAtLeast(0L) / previewEstimate)
            .coerceAtMost(8L).toInt()
        if (o.decodedPrefetchPages == null) decodedPages = minOf(decodedPages, ahead, warmPageLimit)
        if (!foreground || o.prefetchMode == PrefetchMode.OFF) decodedPages = 0
        val recovery = o.sourceRecovery ?: goal.sourceRecovery
        val standbyPause = when {
            recovery == SourceRecoveryMode.OFF -> "备用预热已关闭，可在此选择稳健或积极"
            !foreground -> "应用不在前台，返回阅读后恢复"
            resources.networkAvailable == false -> "网络未连接，联网后自动恢复"
            resources.metered == null && !o.allowMeteredPrefetch -> "网络计费状态未知，确认非计费网络或允许计费预热后恢复"
            resources.metered == true && !o.allowMeteredPrefetch -> "计费网络受限，切换网络或允许计费预热后恢复"
            o.prefetchMode == PrefetchMode.OFF -> "阅读预取已关闭，开启后恢复备用准备"
            diskLow -> "磁盘空间不足，释放空间后恢复"
            o.standbySources == null && severe -> "内存或温度保护，资源恢复后自动继续"
            o.standbySources == null && warm -> "设备温度升高，温度恢复后自动继续"
            o.standbySources == null && power -> "省电保护，恢复电量或退出省电后继续"
            o.standbySources == null && cpuBusy -> "处理器繁忙，负载恢复后自动继续"
            o.standbySources == null && lowRam -> "低内存设备暂缓额外来源，当前源继续阅读"
            prefetchBytes <= 0L -> "没有可用预取预算，恢复窗口或空间后继续"
            else -> null
        }
        val requestedStandbys = o.standbySources ?: if (recovery == SourceRecoveryMode.AGGRESSIVE) 2 else 1
        val standby = if (standbyPause != null) 0 else o.standbySources ?: minOf(requestedStandbys, networkCap,
            o.prefetchRequests ?: goal.prefetchRequests)
        val manualReading = o.prefetchMode == PrefetchMode.MANUAL
        val browseWarmup = o.browseWarmup && (manualReading || goal.browseWarmup) && foreground && readerUsers == 0 &&
            prefetchBytes > 0L && (manualReading || !warm && !power && !cpuBusy && !lowRam) &&
            (resources.metered == false || o.allowMeteredPrefetch)
        val protections = buildList {
            if (diskLow) add("磁盘空间不足")
            if (warning) add(if (resources.memoryPressure == true) "系统内存紧张" else "内存保护恢复中")
            if (heapHigh) add("应用堆内存余量较低")
            if (physicalTight) add("可用系统内存较低")
            if (hot) add(if ((resources.thermalPressure ?: 0) >= 2) "温度保护" else "温度恢复中")
            else if (warm) add(if ((resources.thermalPressure ?: 0) >= 1) "设备温度升高" else "温度恢复中")
            if (power) add(if (lowBattery) "电量较低" else "系统省电保护")
            if (cpuBusy) add(if ((resources.cpuLoad ?: 0.0) > o.runtime.cpuBusyPercent / 100.0) "处理器负载较高" else "负载恢复中")
            if (frameBusy) add("帧工作耗时较高")
            else if (decodeBusy) add("图片解码耗时较高")
            if (resources.metered == true && !o.allowMeteredPrefetch) add("计费网络暂停网络预取")
            if (resources.networkAvailable == false) add("网络未连接，本地准备仍可用")
            if (!foreground && !o.allowBackgroundDownloads) add("后台下载已限制")
            if (lowRam) add("低内存设备预算")
        }
        val refresh = o.refresh ?: if (mode == ExperienceMode.ECO) RefreshPreference.SYSTEM else RefreshPreference.INTERACTION
        val high = foreground && (refresh == RefreshPreference.MANUAL || interactionUntil > now && !severe && !power && !cpuBusy) && refresh != RefreshPreference.SYSTEM
        val requestedHz = if (high && refresh == RefreshPreference.MANUAL) o.refreshHz else null
        val reason = protections.firstOrNull()?.let { it + "；自动项调整，手动值保留" } ?: when {
            o.prefetchMode == PrefetchMode.OFF -> "预取已关闭，当前页按需加载"
            reading && readerWaiting -> "优先加载当前页，后台下载让路"
            reading -> "当前页优先，按方向与速度准备"
            mode == ExperienceMode.ECO -> "减少预取与后台唤醒"
            mode == ExperienceMode.PERFORMANCE -> "扩大准备窗口，按实际资源保护"
            else -> "按设备余量准备，当前页优先"
        }
        return ExperienceBudget(mode, background, ahead, behind, high, fraction, bytes, reason,
            chapterParallelism = if (background == 0) 0 else o.chapters ?: minOf(goal.chapterParallelism,
                if (severe || warm || power || lowRam || cpuBusy || reading) 1 else 4),
            sourceParallelism = o.sourcePages ?: minOf(goal.sourceParallelism, networkCap),
            readerRequests = o.readerRequests ?: minOf(goal.readerRequests, if (severe) 1 else 4),
            resolveRequests = o.resolveRequests ?: minOf(goal.resolveRequests, resolveCap).coerceAtLeast(1),
            decodeRequests = o.decodeRequests ?: minOf(goal.decodeRequests, decodeCap).coerceAtLeast(1),
            prefetchRequests = if (prefetchBytes == 0L) 0 else o.prefetchRequests ?: minOf(goal.prefetchRequests, networkCap),
            prefetchBytes = prefetchBytes, materializationBytes = o.runtime.materializationMiB?.toLong()?.times(EXPERIENCE_MIB) ?: transportHeadroom,
            crossChapter = o.crossChapter && prefetchBytes > 0L && (manualReading || !warm && !power && !cpuBusy && !lowRam) &&
                (resources.metered == false || o.allowMeteredPrefetch),
            downloadsYieldingToReader = reading && readerWaiting,
            memoryCacheBytes = o.memoryMiB?.toLong()?.times(EXPERIENCE_MIB),
            refreshHz = requestedHz, protections = protections, foreground = foreground,
            decodedPrefetchPages = decodedPages, standbySources = standby,
            standbyPages = o.standbyPages, standbyMemoryWarmup = o.standbyMemoryWarmup && decodedPages > 0,
            standbyPauseReason = standbyPause,
            standbyBytes = o.standbyMiB?.toLong()?.times(EXPERIENCE_MIB),
            browseWarmup = browseWarmup, sourceRecovery = recovery,
            sourceHedgeDelayMillis = when (recovery) {
                SourceRecoveryMode.OFF -> goal.sourceHedgeDelayMillis
                SourceRecoveryMode.BALANCED -> 1_800L
                SourceRecoveryMode.AGGRESSIVE -> 650L
            }, goal = goal, runtime = o.runtime, recovery = o.recovery, diskReserveBytes = reserve,
        )
    }

    /** RAM/cores select an initial profile, never permission to ignore live pressure. */
    private fun resourceTier(desktop: Boolean): Int {
        val total = resources.totalMemoryBytes
        val heap = resources.processHeapLimitBytes
        val cores = resources.processorCount
        if (resources.lowMemoryDevice == true || total != null && total < 3L * 1024L * EXPERIENCE_MIB ||
            heap != null && heap < 192L * EXPERIENCE_MIB || cores != null && cores < 4) return 0
        if (total == null || cores == null) return 0
        if (desktop && total >= 16L * 1024L * EXPERIENCE_MIB && cores >= 8 && heap != null && heap >= 1024L * EXPERIENCE_MIB) return 3
        if (total >= 8L * 1024L * EXPERIENCE_MIB && cores >= 6 &&
            heap != null && heap >= (if (desktop) 512L else 384L) * EXPERIENCE_MIB) return 2
        // iOS has real physical/core observations but no portable process heap limit.
        // Keep its unknown per-process allowance conservative rather than inventing one.
        if (total >= 4L * 1024L * EXPERIENCE_MIB && cores >= 4 &&
            (heap != null && heap >= 256L * EXPERIENCE_MIB || platformKind == PlatformKind.IOS)) return 1
        return 0
    }

    private fun goalBudget(desktop: Boolean, tier: Int, selectedMode: ExperienceMode = mode): ExperienceGoalBudget {
        val mode = selectedMode
        if (mode == ExperienceMode.ECO) return ExperienceGoalBudget(
            prefetchAhead = 3, prefetchBehind = 1, decodedPrefetchPages = 0,
            backgroundPages = if (desktop) 2 else 1, chapterParallelism = 1,
            sourceParallelism = 1, readerRequests = 2, resolveRequests = 2, decodeRequests = 1,
            prefetchRequests = 1, prefetchBytes = 24L * EXPERIENCE_MIB,
            standbySources = 0, browseWarmup = false, memoryCacheFraction = .10,
            diskCacheBytes = 256L * EXPERIENCE_MIB, sourceHedgeDelayMillis = 3_500L,
            sourceRecovery = SourceRecoveryMode.OFF,
        )
        val performance = mode == ExperienceMode.PERFORMANCE
        val ahead = if (performance) listOf(8, 16, 24, 40)[tier] else listOf(6, 12, 18, 24)[tier]
        val behind = if (performance) listOf(1, 2, 4, 8)[tier] else listOf(1, 2, 3, 4)[tier]
        val network = if (performance) listOf(2, 4, 6, 12)[tier] else listOf(2, 3, 4, 8)[tier]
        val decode = if (performance) listOf(1, 2, 3, 4)[tier] else listOf(1, 2, 2, 3)[tier]
        val decoded = if (performance) listOf(1, 3, 5, 8)[tier] else listOf(1, 2, 3, 5)[tier]
        val bytesMiB = if (performance) listOf(64L, 128L, 256L, 512L)[tier] else listOf(48L, 96L, 160L, 256L)[tier]
        val background = if (performance) listOf(2, 4, if (desktop) 8 else 6, 12)[tier]
            else listOf(2, 3, if (desktop) 6 else 4, 8)[tier]
        return ExperienceGoalBudget(
            prefetchAhead = ahead, prefetchBehind = behind, decodedPrefetchPages = decoded,
            backgroundPages = minOf(background, maximumBackgroundPageParallelism),
            chapterParallelism = if (tier >= 2) 4 else 2,
            sourceParallelism = if (performance) listOf(2, 4, 6, 8)[tier] else listOf(2, 3, 4, 6)[tier],
            readerRequests = if (performance) 4 else 3, resolveRequests = listOf(2, 4, 6, 8)[tier],
            decodeRequests = decode, prefetchRequests = network, prefetchBytes = bytesMiB * EXPERIENCE_MIB,
            standbySources = if (performance) 2 else 1, browseWarmup = true,
            memoryCacheFraction = if (performance) (if (tier >= 2) .28 else .24) else (if (tier >= 2) .24 else .18),
            diskCacheBytes = (if (desktop) (if (tier >= 3) 4096L else 2048L)
                else if (tier >= 2) 2048L else if (tier == 1) 1024L else 512L) * EXPERIENCE_MIB,
            sourceHedgeDelayMillis = if (performance) 650L else 1_800L,
            sourceRecovery = if (performance) SourceRecoveryMode.AGGRESSIVE else SourceRecoveryMode.BALANCED,
        )
    }

    private fun publish() {
        val next = budget()
        mutableState.value = next
        revision.value++
        val memory = memoryCache
        val version = ++cacheRevision
        val retention = mutableOverrides.value.contentCache
        scope.launch {
            memory?.applyBudget(next.memoryCacheFraction, next.memoryCacheBytes, version, retention.memoryIdleMinutes)
            refreshMemoryUsage()
            val storage = dev.mangaflow.storage.StorageCacheStore.get(app)
            storage.setBudget(next.diskCacheBytes, version)
            storage.setRetention(retention.imageRetentionHours, version)
            storage.setRangeCleanup(retention.memoryOutsideWindowCleanup, retention.diskOutsideWindowCleanup, version)
            storage.setRuntime(next.runtime, version)
        }
    }

    private fun updateUsage() {
        mutableUsage.value = measurements.usage(mutableUsage.value).copy(
            resources = resources, activeChapters = chapters.size, activeBackgroundPages = activePages,
            activeReaderImages = activeReaderImages, activePrefetch = activePrefetch,
            activeResolves = activeResolves, activeDecodes = activeDecodes,
            activeTileDecodes = activeTileDecodes, activePreparations = activePreparations,
            materializationBytes = materializationStreamingBytes,
            queuedTasks = queued, prefetchReservedBytes = prefetchReserved,
            throttledSources = sources.values.count { it.recoverAfter > monotonicTimeMillis() },
            observedHeapPeakBytes = maxObserved(mutableUsage.value.observedHeapPeakBytes, resources.processHeapBytes),
            observedResidentPeakBytes = maxObserved(mutableUsage.value.observedResidentPeakBytes, resources.processResidentBytes),
            observedProportionalPeakBytes = maxObserved(mutableUsage.value.observedProportionalPeakBytes, resources.processProportionalBytes),
        )
    }
    private fun readMode() = ExperienceMode.entries.firstOrNull { it.name == disk.getString(MODE_KEY) } ?: ExperienceMode.AUTO
    private fun readOverrides() = decodeExperienceOverrides(disk.getString(OVERRIDES_KEY))
    private fun diskReserve(o: ExperienceOverrides = mutableOverrides.value): Long =
        (o.runtime.diskReserveMiB?.toLong() ?: if (o.diskMiB != null) 0L
            else if (platformKind == PlatformKind.DESKTOP) 1024L else 256L) * EXPERIENCE_MIB

    private fun maxObserved(previous: Long?, current: Long?): Long? = when {
        current == null -> previous
        previous == null -> current
        else -> maxOf(previous, current)
    }

    private class SourceObservation(
        var blockedUntil: Long = 0L, var recoverAfter: Long = 0L, var parallelism: Int = 1,
        var nextIncreaseAt: Long = 0L,
    ) {
        var windowStartedAt = 0L
        var windowBytes = 0L
        var windowSuccesses = 0
        var windowPeak = 0
        val windowLatencies = ArrayDeque<Long>()
        var trialFrom = 0
        var baselineRate = 0.0
        var baselineTailMillis = 0L
        var baselineMeanBytes = 0.0
        var plateauWindows = 0
        fun resetWindow() {
            windowStartedAt = 0L; windowBytes = 0L; windowSuccesses = 0; windowPeak = 0
            windowLatencies.clear()
        }
        fun clearTrial() {
            trialFrom = 0; baselineRate = 0.0; baselineTailMillis = 0L; baselineMeanBytes = 0.0
            plateauWindows = 0
        }
    }

    companion object {
        private const val MODE_KEY = "experience_mode_v1"
        private const val OVERRIDES_KEY = "experience_overrides_v1"
        private val instanceLock = StorageLock()
        private var instance: ExperiencePolicyStore? = null
        fun get(context: PlatformContext): ExperiencePolicyStore = synchronized(instanceLock) {
            instance ?: ExperiencePolicyStore(context).also { instance = it }
        }
    }
}
