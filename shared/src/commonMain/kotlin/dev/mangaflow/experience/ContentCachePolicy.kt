package dev.mangaflow.experience

import dev.mangaflow.platform.PlatformContext

enum class ContentCacheKind { SEARCH, DISCOVERY, RANKING, DETAILS, PAGE_MANIFEST, METRICS, POPULARITY, COMMENTS }
enum class ContentCacheStorage { MEMORY_AND_DISK, MEMORY_ONLY, DISABLED }
enum class ReaderStatusPlacement { TOP, BOTTOM, HIDDEN }

/** Freshness controls revalidation; retention controls disposable data, never saved chapters. */
data class ContentCachePreferences(
    val storage: ContentCacheStorage = ContentCacheStorage.MEMORY_AND_DISK,
    val refreshOnOpen: Boolean = false,
    val searchMinutes: Int = 5,
    val discoveryMinutes: Int = 15,
    val rankingMinutes: Int = 30,
    val detailMinutes: Int = 1,
    val manifestMinutes: Int = 1,
    val scoreMinutes: Int = 360,
    val popularityMinutes: Int = 30,
    val commentMinutes: Int = 3,
    val failureMinutes: Int = 1,
    val retentionHours: Int = 168,
    val snapshotMemoryEntries: Int = 24,
    val snapshotDiskMiB: Int = 8,
    /** Zero keeps the existing capacity/LRU policy without an additional idle/age limit. */
    val memoryIdleMinutes: Int = 0,
    val imageRetentionHours: Int = 0,
) {
    val enabled: Boolean get() = storage != ContentCacheStorage.DISABLED
    val storeOnDisk: Boolean get() = storage == ContentCacheStorage.MEMORY_AND_DISK
    val retentionMillis: Long get() = retentionHours.coerceIn(1, 720) * 3_600_000L
    fun freshMillis(kind: ContentCacheKind, failure: Boolean = false): Long {
        if (!enabled || refreshOnOpen) return 0L
        val minutes = if (failure) failureMinutes else when (kind) {
            ContentCacheKind.SEARCH -> searchMinutes
            ContentCacheKind.DISCOVERY -> discoveryMinutes
            ContentCacheKind.RANKING -> rankingMinutes
            ContentCacheKind.DETAILS -> detailMinutes
            ContentCacheKind.PAGE_MANIFEST -> manifestMinutes
            ContentCacheKind.METRICS -> scoreMinutes
            ContentCacheKind.POPULARITY -> popularityMinutes
            ContentCacheKind.COMMENTS -> commentMinutes
        }
        // Heat is returned by native list/detail metadata, not a separate per-card endpoint.
        // Its ceiling revalidates that metadata on the next visit without an N+1 fetch fan-out.
        val metadataMinutes = when (kind) {
            ContentCacheKind.SEARCH, ContentCacheKind.DISCOVERY, ContentCacheKind.RANKING, ContentCacheKind.DETAILS ->
                if (failure) minutes else minOf(minutes, popularityMinutes)
            else -> minutes
        }
        return metadataMinutes.coerceIn(0, 10_080) * 60_000L
    }
    internal fun bounded() = copy(
        searchMinutes = searchMinutes.coerceIn(0, 10_080), discoveryMinutes = discoveryMinutes.coerceIn(0, 10_080),
        rankingMinutes = rankingMinutes.coerceIn(0, 10_080), detailMinutes = detailMinutes.coerceIn(0, 10_080),
        manifestMinutes = manifestMinutes.coerceIn(0, 10_080), scoreMinutes = scoreMinutes.coerceIn(0, 10_080),
        popularityMinutes = popularityMinutes.coerceIn(0, 10_080), commentMinutes = commentMinutes.coerceIn(0, 10_080),
        failureMinutes = failureMinutes.coerceIn(0, 60), retentionHours = retentionHours.coerceIn(1, 720),
        snapshotMemoryEntries = snapshotMemoryEntries.coerceIn(1, 64), snapshotDiskMiB = snapshotDiskMiB.coerceIn(1, 128),
        memoryIdleMinutes = memoryIdleMinutes.coerceIn(0, 120), imageRetentionHours = imageRetentionHours.coerceIn(0, 720),
    )
}

internal fun contentCachePreferences(context: PlatformContext): ContentCachePreferences =
    ExperiencePolicyStore.get(context).overrides.value.contentCache
