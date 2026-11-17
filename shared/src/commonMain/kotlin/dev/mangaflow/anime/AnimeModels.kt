package dev.mangaflow.anime

import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.anime.player.AnimeVideoEnhancementMode

/** Canonical subject identities never share the manga library's keys or progress. */
data class AnimeSubject(
    val id: String,
    val title: String,
    val originalTitle: String = "",
    val coverUrl: String = "",
    val description: String = "",
    val episodeCount: Int? = null,
    val year: Int? = null,
    val tags: List<String> = emptyList(),
    val externalIds: Map<String, String> = emptyMap(),
    val airWeekday: Int? = null,
    val rating: Double? = null,
    val rank: Int? = null,
)

enum class AnimeEpisodeKind { MAIN, SPECIAL, OPENING, ENDING, TRAILER, OTHER }

data class AnimeEpisode(
    val id: String,
    val subjectId: String,
    val title: String,
    val number: Double,
    val kind: AnimeEpisodeKind = AnimeEpisodeKind.MAIN,
    val durationMs: Long? = null,
    val airDate: String? = null,
)

data class AnimeDetail(val subject: AnimeSubject, val episodes: List<AnimeEpisode>)

data class AnimeSourceTitle(
    val sourceId: String,
    val id: String,
    val title: String,
    val url: String,
    val coverUrl: String = "",
    val year: Int? = null,
    val episodeCount: Int? = null,
    val externalIds: Map<String, String> = emptyMap(),
    /** Source-provided display text, not proof of a playable or canonical episode count. */
    val updateText: String = "",
    val ratingText: String = "",
    /** Discovery snapshot only; never used to rewrite a saved binding's native identity. */
    val sourceRevision: String? = null,
)

data class AnimeSourceEpisode(
    val id: String,
    val title: String,
    val url: String,
    val number: Double? = null,
    val kind: AnimeEpisodeKind = AnimeEpisodeKind.MAIN,
    val group: String = "",
    val edition: String = "",
    /** Provider-owned opaque play flag. It is never interpreted as a media URL. */
    val playbackData: String? = null,
)

data class AnimeSourceCatalog(
    val title: AnimeSourceTitle,
    val episodes: List<AnimeSourceEpisode>,
    /** Actual partial-read status, not a claim that unavailable entries were deleted. */
    val notice: String? = null,
)

data class AnimeSubtitle(
    val id: String,
    val url: String,
    val label: String,
    val language: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class AnimeResolution(
    val id: String,
    val label: String,
    val url: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** Resolved URLs and credentials are session data, never persisted in the watch library. */
data class AnimePlaybackAsset(
    val id: String,
    val sourceId: String,
    val sourceEpisodeId: String,
    val url: String,
    val route: DownloadRouteSnapshot,
    val episodeId: String = sourceEpisodeId,
    val title: String = "",
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val edition: String = "",
    val subtitles: List<AnimeSubtitle> = emptyList(),
    val expiresAt: Long? = null,
    val sourceRevision: String = "1",
    val sessionEpoch: Long = 0L,
    val resolutions: List<AnimeResolution> = emptyList(),
    val startPositionMs: Long? = null,
)

enum class AnimeWatchStatus { WISH, WATCHING, COMPLETED, ON_HOLD, DROPPED }

data class AnimeWatchProgress(
    val episodeId: String,
    val assetId: String,
    val sourceId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val completed: Boolean = false,
    val subjectId: String = "",
    val localDownloadId: String? = null,
)

data class AnimeLibraryEntry(
    val subject: AnimeSubject,
    val status: AnimeWatchStatus = AnimeWatchStatus.WATCHING,
    val followed: Boolean = false,
    val completedEpisodeIds: Set<String> = emptySet(),
    val resume: AnimeWatchProgress? = null,
    val updatedAt: Long = 0L,
    /** Semantic edits only. Routine position checkpoints cannot invalidate a sync review. */
    val revision: Long = 0L,
    val folder: String = "",
    val pinned: Boolean = false,
    /** Explicit per-work exclusions survive background association and process restarts. */
    val excludedSourceIds: Set<String> = emptySet(),
)

data class AnimeSourceBinding(
    val subjectId: String,
    val catalog: AnimeSourceTitle,
    val episodeMappings: Map<String, String> = emptyMap(),
    val preferred: Boolean = false,
    val selectedGroup: String? = null,
    /** Legacy bindings remain user-confirmed unless explicitly marked automatic. */
    val automatic: Boolean = false,
)

data class AnimeSourceResult(
    val providerId: String,
    val name: String,
    val items: List<AnimeSourceTitle> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
)

data class AnimeEpisodeOption(
    val sourceId: String,
    val sourceName: String,
    val catalog: AnimeSourceTitle,
    val episode: AnimeSourceEpisode,
    val confirmed: Boolean = false,
)

data class AnimeTrackingAccount(val providerId: String, val ownerId: String, val displayName: String)

/** Only confirmed MAIN-episode numbers belong here; specials remain independently owned locally. */
data class AnimeTrackingSnapshot(
    val providerId: String,
    val ownerId: String,
    val remoteSubjectId: String,
    val status: AnimeWatchStatus?,
    val completedNumbers: Set<Double>,
    val fetchedAt: Long,
    val revision: String,
)

data class AnimeTrackingReview(
    val id: String,
    val subjectId: String,
    val subjectTitle: String,
    val providerId: String,
    val localStatus: AnimeWatchStatus?,
    val remoteStatus: AnimeWatchStatus?,
    val localNumbers: Set<Double>,
    val remoteNumbers: Set<Double>,
    val remoteSubjectId: String,
    val localRevision: Long,
)

enum class AnimeSyncChoice { LOCAL, REMOTE, MERGE }

data class AnimePlaybackChoice(
    val episode: AnimeEpisode,
    val option: AnimeEpisodeOption,
    val previousPositionMs: Long,
    val previousSourceId: String,
)

enum class AnimeBrowseMode { POPULAR, NEWEST, CALENDAR }

data class AnimePlaybackSettings(
    val autoPlayNext: Boolean = true,
    val prepareNextEpisode: Boolean = true,
    val skipOpeningSeconds: Int = 0,
    val skipEndingSeconds: Int = 0,
    val longPressSpeed: Float = 2f,
    val backgroundAudio: Boolean = false,
    val automaticSourceRecovery: Boolean = true,
    val bufferMinMs: Int = 50_000,
    val bufferMaxMs: Int = 120_000,
    val bufferStartMs: Int = 1_500,
    val bufferRebufferMs: Int = 3_000,
    val automaticRecoveryAttempts: Int = 2,
    val videoEnhancement: AnimeVideoEnhancementMode = AnimeVideoEnhancementMode.OFF,
) {
    fun validate(): AnimePlaybackSettings {
        require(skipOpeningSeconds >= 0 && skipEndingSeconds >= 0) { "跳过时长不能小于零。" }
        require(longPressSpeed.isFinite() && longPressSpeed > 0f) { "长按速度必须大于零。" }
        require(bufferMinMs >= 0 && bufferMaxMs > 0 && bufferMaxMs >= bufferMinMs && bufferStartMs >= 0 &&
            bufferRebufferMs >= 0 && bufferStartMs <= bufferMinMs && bufferRebufferMs <= bufferMinMs) {
            "缓冲上限应不小于下限；起播与重新缓冲应不大于下限。"
        }
        require(automaticRecoveryAttempts >= 0) { "自动恢复次数不能小于零。" }
        return this
    }
}

data class AnimeScreenState(
    val libraryReadUnavailable: Boolean = false,
    val discoveryTab: AnimeDiscoveryTab = AnimeDiscoveryTab.HOME,
    val discoverySections: List<AnimeDiscoverySection> = emptyList(),
    val discoveryFeeds: Map<String, AnimeDiscoveryFeed> = emptyMap(),
    val discoveryColumns: Int? = null,
    val query: String = "",
    val catalog: List<AnimeSubject> = emptyList(),
    val unifiedSearch: List<AnimeSearchGroup> = emptyList(),
    val searchProviders: List<AnimeSourceResult> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val detail: AnimeDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val library: List<AnimeLibraryEntry> = emptyList(),
    val sourceResults: List<AnimeSourceResult> = emptyList(),
    val sourceCatalogs: List<AnimeSourceCatalog> = emptyList(),
    val sourceBindings: List<AnimeSourceBinding> = emptyList(),
    val sourceAggregation: Map<String, AnimeAssociationProgress> = emptyMap(),
    val sourceAggregationRunning: Boolean = false,
    val sourceAggregationCompleted: Int = 0,
    val sourceAggregationTotal: Int = 0,
    val sourceCatalogErrors: Map<String, String> = emptyMap(),
    val previewCatalog: AnimeSourceCatalog? = null,
    val previewLoading: Boolean = false,
    val sourceError: String? = null,
    val episodeOptions: Map<String, List<AnimeEpisodeOption>> = emptyMap(),
    val selectedEpisodeId: String? = null,
    val resolving: Boolean = false,
    val autoPlaySelectionEpisodeId: String? = null,
    val autoPlaySelectionGeneration: Long = 0L,
    val autoMatching: Boolean = false,
    val pendingPlayback: AnimePlaybackChoice? = null,
    val accounts: List<AnimeTrackingAccount> = emptyList(),
    val reviews: List<AnimeTrackingReview> = emptyList(),
    val syncing: Boolean = false,
    val notice: String? = null,
    val canUndo: Boolean = false,
    val preferredSourceId: String? = null,
    val sourceGroups: Map<String, String> = emptyMap(),
    val sourceRoutes: Map<String, dev.mangaflow.net.DownloadRouteMode> = emptyMap(),
    val browseMode: AnimeBrowseMode = AnimeBrowseMode.POPULAR,
    val catalogPage: Int = 0,
    val catalogHasMore: Boolean = false,
    val catalogLoadingMore: Boolean = false,
    val catalogMoreError: String? = null,
    val catalogYear: Int? = null,
    val catalogTag: String = "",
    val calendarDay: Int? = null,
    val detailFromCache: Boolean = false,
    val relatedSubjects: List<AnimeSubject> = emptyList(),
    val settings: AnimePlaybackSettings = AnimePlaybackSettings(),
    val resumeEntry: AnimeLibraryEntry? = null,
    val searchHistory: List<String> = emptyList(),
    val sleepDeadlineMs: Long? = null,
    val sleepAtEpisodeEnd: Boolean = false,
    val nextEpisodeReady: Boolean = false,
    val playbackResolutions: List<AnimeResolution> = emptyList(),
    val selectedResolutionId: String? = null,
    val canRestorePlayback: Boolean = false,
    val sourceDanmakuAvailable: Boolean = false,
    val sourceExtensions: List<dev.mangaflow.anime.sources.lanerc.LanercSourceListing> = emptyList(),
    val sourceOperationIds: Set<String> = emptySet(),
    val sourcePreparation: Map<String, dev.mangaflow.anime.sources.lanerc.SourcePreparationState> = emptyMap(),
)
