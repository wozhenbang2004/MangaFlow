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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ExperienceMode { AUTO, ECO, PERFORMANCE }

data class ExperienceBudget(
    val mode: ExperienceMode,
    val backgroundPages: Int,
    val prefetchAhead: Int,
    val prefetchBehind: Int,
    val preferHighRefresh: Boolean,
    val memoryCacheFraction: Double,
    val diskCacheBytes: Long,
    val reason: String,
    val cacheAppliesOnRestart: Boolean = false,
)

/** Unknown sensors remain unknown; hardware names and network type are not performance scores. */
internal data class ExperienceResources(
    val lowMemoryDevice: Boolean? = null,
    val memoryPressure: Boolean? = null,
    val lowPower: Boolean? = null,
    /** 0 nominal, 1 elevated, 2 serious, 3 critical. */
    val thermalPressure: Int? = null,
    val freeDiskBytes: Long? = null,
)

internal expect fun readExperienceResources(context: PlatformContext): ExperienceResources
internal expect fun observeExperienceResources(context: PlatformContext, changed: (Boolean) -> Unit): () -> Unit

internal val maximumBackgroundPageParallelism: Int get() = if (platformKind == PlatformKind.DESKTOP) 8 else 4

/** One application policy; only future optional admissions change when its budget changes. */
class ExperiencePolicyStore private constructor(context: PlatformContext) {
    private val app = applicationContext(context)
    // LibraryStore exports/restores every typed library preference, including this one key.
    private val disk = preferences(app, "mangaflow_library")
    private val lock = StorageLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var resources = ExperienceResources()
    private var mode = readMode()
    private var pressureUntil = 0L
    private var memoryRecovery: Job? = null
    private var sampleGeneration = 0L
    private var memoryCacheInitialized: Double? = null
    private var diskCacheInitialized: Long? = null
    private var imageLoader: ImageLoader? = null
    private var activePages = 0
    private val admissionRevision = MutableStateFlow(0L)
    private var watchers = 0
    private var observerGeneration = 0L
    private var stopObserver: (() -> Unit)? = null
    private val mutableState = MutableStateFlow(budget())
    val state: StateFlow<ExperienceBudget> = mutableState.asStateFlow()
    private val preferenceListener = PreferenceChangeListener { _, key ->
        if (key == MODE_KEY || key == null) synchronized(lock) {
            mode = readMode()
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
        refreshResources()
    }

    /** A one-shot native snapshot, never a speed test or periodic sensor loop. */
    fun refreshResources() {
        val generation = synchronized(lock) { ++sampleGeneration }
        scope.launch {
            val sampled = try { readExperienceResources(app) } catch (_: Exception) { ExperienceResources() }
            synchronized(lock) {
                if (generation == sampleGeneration) {
                    resources = sampled
                    publish()
                }
            }
        }
    }

    /** Reference-counted native events for the visible app and an actual background worker. */
    fun startObserving(): () -> Unit {
        val generation = synchronized(lock) {
            watchers++
            if (watchers == 1) ++observerGeneration else null
        }
        if (generation != null) scope.launch(Dispatchers.Main.immediate) {
            val release = try { observeExperienceResources(app, ::resourceChanged) } catch (_: Exception) { {} }
            val retained = synchronized(lock) {
                if (watchers > 0 && generation == observerGeneration) { stopObserver = release; true } else false
            }
            if (!retained) release()
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
                        stopObserver.also { stopObserver = null }
                    } else null
                }
            }
            if (release != null) scope.launch(Dispatchers.Main.immediate) { release() }
        }
    }

    private fun resourceChanged(memoryWarning: Boolean) {
        if (memoryWarning) {
            synchronized(lock) {
                pressureUntil = monotonicTimeMillis() + 60_000L
                publish()
                // Coalesce repeated warnings into one recovery snapshot after the
                // latest warning's grace period, rather than retaining timer jobs.
                memoryRecovery?.cancel()
                memoryRecovery = scope.launch { delay(60_000L); refreshResources() }
            }
        }
        refreshResources()
    }

    /** Cache factories freeze their own actual startup values; no singleton/cache replacement. */
    fun initialMemoryCacheFraction(): Double = synchronized(lock) {
        mutableState.value.memoryCacheFraction.also { memoryCacheInitialized = it; publish() }
    }

    fun initialDiskCacheBytes(): Long = synchronized(lock) {
        mutableState.value.diskCacheBytes.also { diskCacheInitialized = it; publish() }
    }

    fun attachImageLoader(loader: ImageLoader) {
        val trim = synchronized(lock) {
            imageLoader = loader
            resources.memoryPressure == true || pressureUntil > monotonicTimeMillis()
        }
        if (trim) scope.launch { loader.memoryCache?.let { it.trimToSize(it.maxSize / 4L) } }
    }

    internal suspend fun <T> withBackgroundPageAdmission(block: suspend () -> T): T {
        while (true) {
            val revision = admissionRevision.value
            val admitted = synchronized(lock) {
                if (activePages < mutableState.value.backgroundPages) { activePages++; true } else false
            }
            if (admitted) {
                try { return block() }
                finally { synchronized(lock) { activePages--; admissionRevision.value++ } }
            }
            admissionRevision.first { it != revision }
        }
    }

    private fun readMode() = ExperienceMode.entries.firstOrNull { it.name == disk.getString(MODE_KEY) }
        ?: ExperienceMode.AUTO

    private fun budget(): ExperienceBudget {
        val desktop = platformKind == PlatformKind.DESKTOP
        val warning = resources.memoryPressure == true || pressureUntil > monotonicTimeMillis()
        val hot = (resources.thermalPressure ?: 0) >= 2
        val warm = (resources.thermalPressure ?: 0) >= 1
        val constrained = resources.lowMemoryDevice == true || resources.lowPower == true || warm || warning
        val reserve = if (desktop) 1024L * MIB else 256L * MIB
        val diskLow = resources.freeDiskBytes?.let { it <= reserve } == true
        var background = when (mode) {
            ExperienceMode.AUTO -> if (desktop) 4 else 2
            ExperienceMode.ECO -> if (desktop) 2 else 1
            ExperienceMode.PERFORMANCE -> maximumBackgroundPageParallelism
        }
        if (constrained) background = minOf(background, if (desktop && !hot && !warning) 2 else 1)
        var ahead = when (mode) { ExperienceMode.AUTO -> 3; ExperienceMode.ECO -> 1; ExperienceMode.PERFORMANCE -> 6 }
        var behind = when (mode) { ExperienceMode.AUTO -> 1; ExperienceMode.ECO -> 0; ExperienceMode.PERFORMANCE -> 2 }
        if (constrained) { ahead = minOf(ahead, 1); behind = 0 }
        if (diskLow || hot || warning) { ahead = 0; behind = 0 }
        val fraction = when {
            warning -> .06
            constrained || mode == ExperienceMode.ECO -> .10
            mode == ExperienceMode.PERFORMANCE -> .24
            else -> .18
        }
        val ceiling = when (mode) {
            ExperienceMode.ECO -> 128L * MIB
            ExperienceMode.AUTO -> 256L * MIB
            ExperienceMode.PERFORMANCE -> if (desktop) 2048L * MIB else 512L * MIB
        }
        val bytes = (resources.freeDiskBytes?.let { free ->
            minOf(ceiling, free / 100L, (free - reserve).coerceAtLeast(0L))
        } ?: ceiling) / MIB * MIB
        val reason = when {
            diskLow -> "可用空间紧张，已暂停邻页预取"
            hot -> "设备温度较高，已减少后台工作"
            warning -> "内存紧张，已暂停邻页预取"
            resources.lowPower == true -> "系统省电模式，已减少后台工作"
            resources.lowMemoryDevice == true -> "按设备可用资源控制后台工作"
            warm -> "设备温度升高，已收敛预取"
            mode == ExperienceMode.ECO -> "减少预取与后台并发，优先节能"
            mode == ExperienceMode.PERFORMANCE -> "提高预取与后台并发，保留资源保护"
            else -> "保持当前页优先，按系统资源调整后台工作"
        }
        return ExperienceBudget(mode, background, ahead, behind,
            mode != ExperienceMode.ECO && !constrained, fraction, bytes, reason,
            memoryCacheInitialized?.let { it != fraction } == true || diskCacheInitialized?.let { it != bytes } == true)
    }

    /** Called under lock; waking admissions never cancels a page already using its slot. */
    private fun publish() {
        val next = budget()
        if (next == mutableState.value) return
        mutableState.value = next
        admissionRevision.value++
        if (resources.memoryPressure == true || pressureUntil > monotonicTimeMillis()) {
            val loader = imageLoader
            if (loader != null) scope.launch {
                loader.memoryCache?.let { it.trimToSize(it.maxSize / 4L) }
            }
        }
    }

    companion object {
        private const val MODE_KEY = "experience_mode_v1"
        private const val MIB = 1024L * 1024L
        private val instanceLock = StorageLock()
        private var instance: ExperiencePolicyStore? = null
        fun get(context: PlatformContext): ExperiencePolicyStore = synchronized(instanceLock) {
            instance ?: ExperiencePolicyStore(context).also { instance = it }
        }
    }
}
