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

internal val maximumBackgroundPageParallelism: Int get() = if (platformKind == PlatformKind.DESKTOP) 8 else 4

/** Stored intent, effective limits and measured usage are separate. One controller owns admissions. */
class ExperiencePolicyStore private constructor(context: PlatformContext) {
    private val app = applicationContext(context)
    private val disk = preferences(app, "mangaflow_library")
    private val lock = StorageLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    private var sampleGeneration = 0L
    private var foregroundUsers = 0
    private var readerUsers = 0
    private var readerWaiting = false
    private var watchers = 0
    private var observerGeneration = 0L
    private var stopObserver: (() -> Unit)? = null
    private var sampling: Job? = null
    private var memoryCache: ExperienceMemoryCache? = null
    private var diskCache: ExperienceDiskCache? = null
    private var cacheRevision = 0L
    private var sampledDiskBytes = 0L
    private var activePages = 0
    private var activeImages = 0
    private var activeReaderImages = 0
    private var activePrefetch = 0
    private var prefetchReserved = 0L
    private var prefetchStreamingBytes = 0L
    private var queued = 0
    private var nextTicket = 0L
    private val pageQueue = linkedMapOf<Long, String>()
    private val chapterQueue = linkedMapOf<Long, String>()
    private val chapters = mutableSetOf<String>()
    private val sourceActive = mutableMapOf<String, Int>()
    private val sources = mutableMapOf<String, SourceObservation>()
    private val measurements = ExperienceMeasurements()
    private val revision = MutableStateFlow(0L)
    private val mutableState = MutableStateFlow(budget())
    val state: StateFlow<ExperienceBudget> = mutableState.asStateFlow()
    private val mutableUsage = MutableStateFlow(ExperienceUsage())
    val usage: StateFlow<ExperienceUsage> = mutableUsage.asStateFlow()
    private val preferenceListener = PreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("experience_")) synchronized(lock) {
            mode = readMode()
            mutableOverrides.value = readOverrides()
            publish()
        }
    }

    init {
        disk.registerOnSharedPreferenceChangeListener(preferenceListener)
        refreshResources()
    }

    fun setMode(next: ExperienceMode) {
        disk.edit().putString(MODE_KEY, next.name).apply()
        synchronized(lock) { mode = next; publish() }
    }

    fun setOverrides(value: ExperienceOverrides) {
        val next = value.copy(
            ahead = value.ahead?.coerceIn(0, 12), behind = value.behind?.coerceIn(0, 4),
            prefetchRequests = value.prefetchRequests?.coerceIn(1, 3),
            prefetchMiB = value.prefetchMiB?.coerceIn(4, 96),
            readerRequests = value.readerRequests?.coerceIn(1, 4),
            downloadPages = value.downloadPages?.coerceIn(1, maximumBackgroundPageParallelism),
            sourcePages = value.sourcePages?.coerceIn(1, 4), chapters = value.chapters?.coerceIn(1, 4),
            memoryMiB = value.memoryMiB?.coerceIn(16, 512),
            diskMiB = value.diskMiB?.coerceIn(32, 8192), refreshHz = value.refreshHz?.coerceIn(30, 360),
            decodedPrefetchPages = value.decodedPrefetchPages?.coerceIn(0, 2),
        )
        if (next == mutableOverrides.value) return
        // One typed, backup-compatible preference makes a restore/change atomic.
        disk.edit().putString(OVERRIDES_KEY, encodeExperienceOverrides(next)).apply()
        synchronized(lock) { mutableOverrides.value = next; publish() }
    }

    /** Native snapshots only; no bandwidth probes, hardware grades or synthetic benchmarks. */
    fun refreshResources() {
        val generation = synchronized(lock) { ++sampleGeneration }
        scope.launch {
            val sampled = try { readExperienceResources(app) } catch (_: Exception) { ExperienceResources() }
            synchronized(lock) {
                if (generation != sampleGeneration) return@synchronized
                val now = monotonicTimeMillis()
                if (sampled.memoryPressure == true) memoryUntil = now + 60_000L
                if ((sampled.thermalPressure ?: 0) >= 2) heatUntil = now + 30_000L
                if ((sampled.thermalPressure ?: 0) >= 1) warmUntil = now + 15_000L
                if ((sampled.cpuLoad ?: 0.0) > .85) cpuUntil = now + 20_000L
                if (sampled.lowPower == true) powerUntil = now + 15_000L
                sampled.freeDiskBytes?.let { free ->
                    val reserve = diskReserve()
                    if (free <= reserve) storageLow = true
                    else if (free > reserve + 64L * EXPERIENCE_MIB) storageLow = false
                }
                resources = sampled
                publish()
                updateUsage()
            }
            refreshCacheUsage()
        }
    }

    /** Events and the light snapshot loop exist only for a visible app or an actual worker. */
    fun startObserving(): () -> Unit {
        val generation = synchronized(lock) {
            watchers++
            if (watchers == 1) {
                sampling = scope.launch {
                    while (isActive) { refreshResources(); delay(10_000L) }
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
        if (memoryWarning) synchronized(lock) { memoryUntil = monotonicTimeMillis() + 60_000L; publish() }
        refreshResources()
    }

    internal fun attachImageLoader(loader: ImageLoader, memory: ExperienceMemoryCache, disk: ExperienceDiskCache) {
        check(loader.memoryCache === memory && loader.diskCache === disk)
        synchronized(lock) { memoryCache = memory; diskCache = disk; publish() }
        refreshCacheUsage()
    }

    fun refreshCacheUsage() {
        scope.launch {
            val caches = synchronized(lock) { memoryCache to diskCache }
            val memory = caches.first
            val disk = caches.second
            try {
                val snapshot = listOf(memory?.size, memory?.maxSize, disk?.size, disk?.maxSize)
                val pending = disk?.resizePending == true
                val error = disk?.resizeError
                synchronized(lock) {
                    sampledDiskBytes = snapshot[2] ?: 0L
                    mutableUsage.value = mutableUsage.value.copy(
                        memoryBytes = snapshot[0], memoryLimit = snapshot[1],
                        diskBytes = snapshot[2], diskLimit = snapshot[3],
                        diskResizePending = pending, cacheError = error,
                    )
                }
            } catch (_: Exception) {
                synchronized(lock) { mutableUsage.value = mutableUsage.value.copy(cacheError = "图片缓存用量暂时无法读取") }
            }
        }
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
            { activePages++; sourceActive[source] = (sourceActive[source] ?: 0) + 1 },
            { activePages--; releaseSource(source) }, source, fairPages = true,
            maxWaitMillis = maxWaitMillis, waiting = { backgroundWait(source) }, onWaiting = onWaiting, block = block)

    internal suspend fun <T> withImageAdmission(reader: Boolean, block: suspend () -> T): T =
        if (reader) admit({ activeReaderImages < mutableState.value.readerRequests },
            { activeReaderImages++ }, { activeReaderImages-- }, block = block)
        else admit({ activeImages < if (platformKind == PlatformKind.DESKTOP) 8 else 4 },
            { activeImages++ }, { activeImages-- }, block = block)

    internal suspend fun <T> withPrefetchAdmission(
        source: String, estimatedBytes: Long, promoted: () -> Boolean, block: suspend () -> T,
    ): T = withOptionalAdmission(source, estimatedBytes, promoted, browseOnly = false, block = block)

    /** Speculation uses the existing prefetch/source permits and disappears as reading starts. */
    internal suspend fun <T> withBrowseWarmupAdmission(
        source: String, estimatedBytes: Long = EXPERIENCE_MIB, block: suspend () -> T,
    ): T = withOptionalAdmission(source, estimatedBytes, { false }, browseOnly = true, block = block)

    private suspend fun <T> withOptionalAdmission(
        source: String, estimatedBytes: Long, promoted: () -> Boolean, browseOnly: Boolean, block: suspend () -> T,
    ): T {
        val reserved = estimatedBytes.coerceAtLeast(EXPERIENCE_MIB)
        return admit(
            ready = {
                val b = mutableState.value
                !readerWaiting && (!browseOnly || b.browseWarmup && readerUsers == 0) &&
                    activePrefetch < b.prefetchRequests && reserved <= b.prefetchBytes - prefetchReserved && sourceAvailable(source)
            },
            enter = { activePrefetch++; prefetchReserved += reserved; sourceActive[source] = (sourceActive[source] ?: 0) + 1 },
            leave = { activePrefetch--; prefetchReserved -= reserved; releaseSource(source) },
            source = source,
            abandon = {
                if (promoted()) throw PrefetchPromoted()
                mutableState.value.prefetchBytes < reserved || !mutableState.value.foreground ||
                    browseOnly && (!mutableState.value.browseWarmup || readerUsers > 0)
            },
            block = block,
        )
    }
    internal fun wakeAdmissions(): Unit = synchronized(lock) { revision.value++; Unit }

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

    private fun sourceAvailable(source: String): Boolean {
        val now = monotonicTimeMillis()
        val observed = sources[source]
        val limit = minOf(observed?.parallelism ?: 1, mutableState.value.sourceParallelism)
        return (observed?.blockedUntil ?: 0L) <= now && (sourceActive[source] ?: 0) < limit
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
            if (code == 429 || code == 503) {
                // Concurrent failures cannot shorten an already advertised source cooldown.
                observed.blockedUntil = maxOf(observed.blockedUntil, now + experienceRetryDelay(retryAfter))
                observed.recoverAfter = maxOf(observed.recoverAfter, observed.blockedUntil + 30_000L)
                observed.parallelism = 1
                observed.healthySince = 0L; observed.successes = 0
            } else if (failed || code != null && code >= 500 ||
                (bytes >= 64L * 1024L && elapsed > 0L && bytes * 1000L / elapsed < 128L * 1024L)) {
                observed.recoverAfter = maxOf(observed.recoverAfter, now + 30_000L)
                observed.parallelism = (observed.parallelism - 1).coerceAtLeast(1)
                observed.healthySince = 0L; observed.successes = 0
            } else if (code != null && code in 200..299 && bytes > 0L && now >= observed.recoverAfter) {
                if (observed.healthySince == 0L) observed.healthySince = now
                observed.successes++
                if (observed.successes >= 4 && now - observed.healthySince >= 20_000L && now >= observed.nextIncreaseAt) {
                    observed.parallelism = minOf(observed.parallelism + 1, mutableState.value.sourceParallelism)
                    observed.nextIncreaseAt = now + 30_000L
                    observed.healthySince = now; observed.successes = 0
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
    internal fun estimatedPageBytes(key: String): Long = synchronized(lock) { measurements.estimate(key) }
    internal fun acceptPrefetchChunk(bytes: Long) = synchronized(lock) {
        if (bytes > mutableState.value.prefetchBytes - prefetchStreamingBytes) throw PrefetchBudgetExceeded()
        prefetchStreamingBytes += bytes
    }
    internal fun releasePrefetchBytes(bytes: Long) = synchronized(lock) { prefetchStreamingBytes -= bytes }
    internal fun canWritePrefetch(): Boolean = synchronized(lock) {
        mutableState.value.prefetchBytes > 0L && diskCache?.acceptsPrefetchWrites != false
    }
    fun recordFrame(durationNanos: Long) = synchronized(lock) {
        if (measurements.recordFrame(durationNanos)) updateUsage()
    }

    private fun budget(): ExperienceBudget {
        val o = mutableOverrides.value
        val desktop = platformKind == PlatformKind.DESKTOP
        val goal = goalBudget(desktop)
        val now = monotonicTimeMillis()
        val warning = resources.memoryPressure == true || memoryUntil > now
        val hot = (resources.thermalPressure ?: 0) >= 2 || heatUntil > now
        val warm = (resources.thermalPressure ?: 0) >= 1 || warmUntil > now
        val heapHigh = resources.processHeapBytes?.let { used ->
            resources.processHeapLimitBytes?.takeIf { it > 0L }?.let { used.toDouble() / it > .88 }
        } == true
        val lowBattery = o.batteryProtection && resources.charging != true && resources.batteryPercent?.let { it <= 20 } == true
        val power = resources.lowPower == true || powerUntil > now || lowBattery
        val cpuBusy = (resources.cpuLoad ?: 0.0) > .85 || cpuUntil > now
        val lowRam = resources.lowMemoryDevice == true
        val severe = warning || heapHigh || hot
        val reserve = diskReserve()
        val free = resources.freeDiskBytes
        val diskLow = storageLow || free != null && free <= reserve
        val foreground = foregroundUsers > 0
        val reading = foreground && readerUsers > 0 && o.yieldToReading
        var background = o.downloadPages ?: goal.backgroundPages
        // LIGHT thermal pressure reduces speculative work first. Do not turn all three goals
        // into the same memory/window preset after one ordinary Android LIGHT sample.
        if (warm) background = minOf(background, maxOf(1, goal.backgroundPages / 2))
        if (reading || power || lowRam || cpuBusy) background = minOf(background, if (desktop && !severe && !readerWaiting) 2 else 1)
        if (severe) background = minOf(background, 1)
        if (diskLow || resources.networkAvailable == false || (!foreground && !o.allowBackgroundDownloads) ||
            (resources.thermalPressure ?: 0) >= 3 || reading && readerWaiting) background = 0
        var ahead = if (o.prefetchMode == PrefetchMode.MANUAL) o.ahead ?: 3 else
            goal.prefetchAhead
        var behind = if (o.prefetchMode == PrefetchMode.MANUAL) o.behind ?: 1 else
            goal.prefetchBehind
        if (warm) {
            ahead = minOf(ahead, when (mode) { ExperienceMode.ECO -> 1; ExperienceMode.AUTO -> 4; ExperienceMode.PERFORMANCE -> 8 })
            behind = minOf(behind, 1)
        }
        if (power || lowRam || cpuBusy) {
            ahead = minOf(ahead, when (mode) { ExperienceMode.ECO -> 1; ExperienceMode.AUTO -> 2; ExperienceMode.PERFORMANCE -> 4 })
            behind = 0
        }
        if (resources.metered == null && mode == ExperienceMode.AUTO &&
            !o.allowMeteredPrefetch && o.prefetchMode != PrefetchMode.MANUAL) {
            ahead = minOf(ahead, 1); behind = 0
        }
        if (diskLow || severe || o.prefetchMode == PrefetchMode.OFF ||
            !foreground || resources.networkAvailable == false || (resources.metered == true && !o.allowMeteredPrefetch)) {
            ahead = 0; behind = 0
        }
        // Temperature/CPU alone do not imply a memory shortage. Keeping usable images avoids
        // evict/decode cycles while only the expensive optional starts are being restricted.
        val fraction = when {
            warning || heapHigh -> .06
            lowRam -> minOf(goal.memoryCacheFraction, .10)
            power -> minOf(goal.memoryCacheFraction, .10)
            else -> goal.memoryCacheFraction
        }
        val ceiling = o.diskMiB?.toLong()?.times(EXPERIENCE_MIB) ?: goal.diskCacheBytes
        // Existing cache is reclaimable. Its own growth must not repeatedly lower its budget.
        val bytes = if (free == null) ceiling else minOf(ceiling,
            (free + sampledDiskBytes - reserve).coerceAtLeast(0L)) / EXPERIENCE_MIB * EXPERIENCE_MIB
        val protections = buildList {
            if (diskLow) add("磁盘空间不足")
            if (warning) add(if (resources.memoryPressure == true) "系统内存紧张" else "内存保护恢复中")
            if (heapHigh) add("应用堆内存占用较高")
            if (hot) add(if ((resources.thermalPressure ?: 0) >= 2) "温度保护" else "温度恢复中")
            else if (warm) add(if ((resources.thermalPressure ?: 0) >= 1) "设备温度升高" else "温度恢复中")
            if (power) add(if (lowBattery) "电量较低" else "系统省电保护")
            if (cpuBusy) add(if ((resources.cpuLoad ?: 0.0) > .85) "处理器负载较高" else "负载恢复中")
            if (resources.metered == true && !o.allowMeteredPrefetch) add("计费网络暂停预取")
            if (resources.networkAvailable == false) add("网络未连接")
            if (!foreground && !o.allowBackgroundDownloads) add("后台下载已限制")
            if (lowRam) add("低内存设备预算")
        }
        val refresh = o.refresh ?: if (mode == ExperienceMode.ECO) RefreshPreference.SYSTEM else RefreshPreference.INTERACTION
        val high = foreground && interactionUntil > now && !severe && !power && !cpuBusy && refresh != RefreshPreference.SYSTEM
        val requestedHz = if (high && refresh == RefreshPreference.MANUAL) o.refreshHz else null
        val prefetchBytes = if (ahead + behind == 0) 0L else minOf(
            o.prefetchMiB?.toLong()?.times(EXPERIENCE_MIB) ?: goal.prefetchBytes,
            bytes,
        )
        var decodedPages = o.decodedPrefetchPages ?: goal.decodedPrefetchPages
        if (warm || lowRam) decodedPages = minOf(decodedPages, 1)
        if (severe || power || cpuBusy || prefetchBytes == 0L) decodedPages = 0
        decodedPages = minOf(decodedPages, ahead)
        val recovery = o.sourceRecovery ?: goal.sourceRecovery
        val standby = if (prefetchBytes > 0L && !warm && !power && !cpuBusy && !lowRam &&
            (resources.metered == false || o.allowMeteredPrefetch)) when (recovery) {
            SourceRecoveryMode.OFF -> 0
            SourceRecoveryMode.BALANCED -> 1
            SourceRecoveryMode.AGGRESSIVE -> 2
        } else 0
        val browseWarmup = o.browseWarmup && goal.browseWarmup && foreground && readerUsers == 0 &&
            prefetchBytes > 0L && !warm && !power && !cpuBusy && !lowRam &&
            (resources.metered == false || o.allowMeteredPrefetch)
        val reason = protections.firstOrNull()?.let { it + "，已收敛可选工作" } ?: when {
            o.prefetchMode == PrefetchMode.OFF -> "预取已关闭，当前页按需加载"
            reading && readerWaiting -> "优先加载当前页，后台下载让路"
            reading -> "优先阅读，按翻页速度预取"
            mode == ExperienceMode.ECO -> "减少预取与后台唤醒"
            mode == ExperienceMode.PERFORMANCE -> "优先交互与阅读，保留资源保护"
            else -> "按实际资源调整，当前页优先"
        }
        return ExperienceBudget(mode, background, ahead, behind, high, fraction, bytes, reason,
            chapterParallelism = if (background == 0) 0 else minOf(o.chapters ?: goal.chapterParallelism,
                if (severe || warm || power || lowRam || cpuBusy || reading) 1 else 4),
            sourceParallelism = minOf(o.sourcePages ?: goal.sourceParallelism,
                if (severe || power || lowRam || cpuBusy) 1 else if (warm) 2 else 4),
            readerRequests = minOf(o.readerRequests ?: goal.readerRequests, if (severe) 1 else 4),
            prefetchRequests = minOf(o.prefetchRequests ?: goal.prefetchRequests,
                if (severe || warm || power || lowRam || cpuBusy) 1 else 3),
            prefetchBytes = prefetchBytes,
            crossChapter = o.crossChapter && ahead > 0 && !warm && !power && !cpuBusy && !lowRam &&
                (resources.metered == false || o.allowMeteredPrefetch),
            memoryCacheBytes = o.memoryMiB?.toLong()?.times(EXPERIENCE_MIB),
            refreshHz = requestedHz, protections = protections, foreground = foreground,
            decodedPrefetchPages = decodedPages, standbySources = standby,
            browseWarmup = browseWarmup, sourceRecovery = recovery,
            sourceHedgeDelayMillis = when (recovery) {
                SourceRecoveryMode.OFF -> goal.sourceHedgeDelayMillis
                SourceRecoveryMode.BALANCED -> 1_800L
                SourceRecoveryMode.AGGRESSIVE -> 650L
            }, goal = goal,
        )
    }

    private fun goalBudget(desktop: Boolean) = when (mode) {
        ExperienceMode.ECO -> ExperienceGoalBudget(
            prefetchAhead = 1, prefetchBehind = 0, decodedPrefetchPages = 0,
            backgroundPages = if (desktop) 2 else 1, chapterParallelism = 1,
            sourceParallelism = 1, prefetchBytes = 8L * EXPERIENCE_MIB,
            standbySources = 0, browseWarmup = false, memoryCacheFraction = .10,
            diskCacheBytes = 128L * EXPERIENCE_MIB, sourceHedgeDelayMillis = 3_500L,
            sourceRecovery = SourceRecoveryMode.OFF,
        )
        ExperienceMode.AUTO -> ExperienceGoalBudget(
            backgroundPages = if (desktop) 4 else 2, chapterParallelism = if (desktop) 3 else 2,
            diskCacheBytes = (if (desktop) 1024L else 512L) * EXPERIENCE_MIB,
        )
        ExperienceMode.PERFORMANCE -> ExperienceGoalBudget(
            prefetchAhead = 12, prefetchBehind = 2, decodedPrefetchPages = 2,
            backgroundPages = maximumBackgroundPageParallelism, chapterParallelism = if (desktop) 3 else 2,
            sourceParallelism = 3, readerRequests = 3, prefetchRequests = 2,
            prefetchBytes = 64L * EXPERIENCE_MIB, standbySources = 2,
            memoryCacheFraction = .24, diskCacheBytes = (if (desktop) 2048L else 1024L) * EXPERIENCE_MIB,
            sourceHedgeDelayMillis = 650L, sourceRecovery = SourceRecoveryMode.AGGRESSIVE,
        )
    }

    private fun publish() {
        val next = budget()
        mutableState.value = next
        revision.value++
        val memory = memoryCache
        val cache = diskCache
        val version = ++cacheRevision
        scope.launch {
            memory?.applyBudget(next.memoryCacheFraction, next.memoryCacheBytes, version)
            cache?.resize(next.diskCacheBytes, version)
        }
    }

    private fun updateUsage() {
        mutableUsage.value = measurements.usage(mutableUsage.value).copy(
            resources = resources, activeChapters = chapters.size, activeBackgroundPages = activePages,
            activeReaderImages = activeReaderImages, activePrefetch = activePrefetch,
            queuedTasks = queued, prefetchReservedBytes = prefetchReserved,
            throttledSources = sources.values.count { it.recoverAfter > monotonicTimeMillis() },
        )
    }
    private fun readMode() = ExperienceMode.entries.firstOrNull { it.name == disk.getString(MODE_KEY) } ?: ExperienceMode.AUTO
    private fun readOverrides() = decodeExperienceOverrides(disk.getString(OVERRIDES_KEY))
    private fun diskReserve(): Long = (if (platformKind == PlatformKind.DESKTOP) 1024L else 256L) * EXPERIENCE_MIB

    private class SourceObservation(
        var blockedUntil: Long = 0L, var recoverAfter: Long = 0L, var parallelism: Int = 1,
        var healthySince: Long = 0L, var successes: Int = 0, var nextIncreaseAt: Long = 0L,
    )

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
