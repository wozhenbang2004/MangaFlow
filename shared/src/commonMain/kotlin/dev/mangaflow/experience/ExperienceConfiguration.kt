package dev.mangaflow.experience

enum class ExperienceMode { AUTO, ECO, PERFORMANCE }
enum class PrefetchMode { SMART, OFF, MANUAL }
enum class RefreshPreference { SYSTEM, INTERACTION, MANUAL }

/** Null means follow the selected goal. Overrides are ceilings, never protection bypasses. */
data class ExperienceOverrides(
    val prefetchMode: PrefetchMode = PrefetchMode.SMART,
    val ahead: Int? = null,
    val behind: Int? = null,
    val prefetchRequests: Int? = null,
    val prefetchMiB: Int? = null,
    val crossChapter: Boolean = true,
    val allowMeteredPrefetch: Boolean = false,
    val readerRequests: Int? = null,
    val downloadPages: Int? = null,
    val sourcePages: Int? = null,
    val chapters: Int? = null,
    val yieldToReading: Boolean = true,
    val memoryMiB: Int? = null,
    val diskMiB: Int? = null,
    val refresh: RefreshPreference? = null,
    val refreshHz: Int? = null,
    val batteryProtection: Boolean = true,
    val allowBackgroundDownloads: Boolean = true,
)

data class ExperienceBudget(
    val mode: ExperienceMode,
    val backgroundPages: Int,
    val prefetchAhead: Int,
    val prefetchBehind: Int,
    val preferHighRefresh: Boolean,
    val memoryCacheFraction: Double,
    val diskCacheBytes: Long,
    val reason: String,
    val chapterParallelism: Int = 1,
    val sourceParallelism: Int = 1,
    val readerRequests: Int = 2,
    val prefetchRequests: Int = 1,
    val prefetchBytes: Long = 0L,
    val crossChapter: Boolean = false,
    val memoryCacheBytes: Long? = null,
    val refreshHz: Int? = null,
    val protections: List<String> = emptyList(),
    val foreground: Boolean = false,
) {
    val nextChapterPrefetchBytes: Long get() = if (crossChapter) minOf(prefetchBytes / 4L, 12L * EXPERIENCE_MIB) else 0L
    val adjacentPrefetchBytes: Long get() = prefetchBytes - nextChapterPrefetchBytes
}

/** Only actual sensors are populated. JVM heap is labelled separately from physical RAM. */
data class ExperienceResources(
    val lowMemoryDevice: Boolean? = null,
    val memoryPressure: Boolean? = null,
    val lowPower: Boolean? = null,
    /** 0 nominal, 1 elevated, 2 serious, 3 critical. */
    val thermalPressure: Int? = null,
    val freeDiskBytes: Long? = null,
    val processHeapBytes: Long? = null,
    val processHeapLimitBytes: Long? = null,
    val availableMemoryBytes: Long? = null,
    val cpuLoad: Double? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val metered: Boolean? = null,
    val networkAvailable: Boolean? = null,
    val supportedRefreshRates: List<Int> = emptyList(),
)

data class ExperienceUsage(
    val resources: ExperienceResources = ExperienceResources(),
    val memoryBytes: Long? = null,
    val memoryLimit: Long? = null,
    val diskBytes: Long? = null,
    val diskLimit: Long? = null,
    val diskResizePending: Boolean = false,
    val cacheError: String? = null,
    val activeChapters: Int = 0,
    val activeBackgroundPages: Int = 0,
    val activeReaderImages: Int = 0,
    val activePrefetch: Int = 0,
    val queuedTasks: Int = 0,
    val prefetchReservedBytes: Long = 0L,
    val imageRequests: Long = 0L,
    val memoryHits: Long = 0L,
    val diskHits: Long = 0L,
    val prefetchedPages: Long = 0L,
    val prefetchHits: Long = 0L,
    val requestP95Millis: Long? = null,
    val transferredBytes: Long = 0L,
    val transferMillis: Long = 0L,
    val throttledSources: Int = 0,
    val frameP95Millis: Double? = null,
    val frameP99Millis: Double? = null,
)

internal expect fun readExperienceResources(context: dev.mangaflow.platform.PlatformContext): ExperienceResources
internal expect fun observeExperienceResources(
    context: dev.mangaflow.platform.PlatformContext, changed: (Boolean) -> Unit,
): () -> Unit

internal const val EXPERIENCE_MIB = 1024L * 1024L
