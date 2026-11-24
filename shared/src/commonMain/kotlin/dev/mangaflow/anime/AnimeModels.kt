package dev.mangaflow.anime

import dev.mangaflow.net.DownloadRouteSnapshot
import dev.mangaflow.net.DownloadRouteMode
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
    /** Number of ratings returned by the metadata provider; never collection popularity. */
    val ratingCount: Int? = null,
    val format: AnimeDiscoveryFormat? = null,
    /** Complete names supplied by the catalogue, never a title with its season removed. */
    val aliases: List<String> = emptyList(),
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
    /** Episode number within this subject, when the metadata provider supplies one. */
    val numberInSubject: Double? = null,
    /** Confirmed remote Bangumi identities; the local episode ID retains its own work owner. */
    val metadataEpisodeId: String? = null,
    val metadataSubjectId: String? = null,
)

data class AnimeDetail(
    val subject: AnimeSubject,
    val episodes: List<AnimeEpisode>,
    /** Optional, confirmed supplementary metadata. It never replaces the primary catalogue's presentation. */
    val metadataSubject: AnimeSubject? = null,
)

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
    val description: String = "",
    /** The audited adapter read these playback groups from a single upstream work record. */
    val sameWorkRoutes: Boolean = false,
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
    /** Complete request headers for this variant, independent of the currently playing variant. */
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
    /** Safe catalogue labels for native cache ownership; never persist signed URLs or credentials here. */
    val subjectId: String = "",
    val subjectTitle: String = "",
    val episodeTitle: String = "",
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
    /** Labels captured from the actual played episode, never reconstructed from its opaque ID. */
    val episodeTitle: String = "",
    val episodeNumber: Double? = null,
    val episodeKind: AnimeEpisodeKind? = null,
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
    /** A user-confirmed Bangumi reference for native or Douban works; automatic links belong to detail metadata. */
    val nativeMetadata: AnimeSubject? = null,
    /** An explicit Douban unlink survives background metadata resolution and process restarts. */
    val metadataAutoMatchDisabled: Boolean = false,
    /** Stable Douban episode ownership survives disposable metadata-cache cleanup. */
    val catalogDetail: AnimeDetail? = null,
    /** Observed wall time from visible Android playback; never derived from resume position. */
    val visibleWatchMillis: Long = 0L,
)

data class AnimeSourceBinding(
    val subjectId: String,
    val catalog: AnimeSourceTitle,
    val episodeMappings: Map<String, String> = emptyMap(),
    val preferred: Boolean = false,
    val selectedGroup: String? = null,
    /** Legacy bindings remain user-confirmed unless explicitly marked automatic. */
    val automatic: Boolean = false,
    val preferredExplicitly: Boolean = false,
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
    val queued: Boolean = false,
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

data class AnimePlaybackTarget(
    val episode: AnimeEpisode,
    val manualOption: AnimeEpisodeOption? = null,
    val sourceName: String? = null,
    val error: String? = null,
    val failure: AnimePlaybackFailure? = null,
)

data class AnimePlaybackFailure(
    val sourceId: String,
    val sourceName: String,
    val stage: String,
    val reason: String,
    val unsupported: Boolean = false,
) {
    internal val description get() = "$sourceName · $stage\n$reason"
}

internal enum class AnimeEpisodeAvailability { MATCHED, CHECKING, UNMATCHED, RECENTLY_FAILED, UPCOMING }

internal data class AnimePlaybackMeasurements(
    val updatedAt: Long,
    val width: Int? = null,
    val height: Int? = null,
    val firstFrameMs: Long? = null,
    val measuredBytesPerSecond: Long? = null,
    val addressMs: Long? = null,
    val declaredQuality: String? = null,
    val firstFrameObservedAt: Long? = null,
    val playbackObservedAt: Long? = null,
    val durationMs: Long? = null,
    val durationAssetId: String? = null,
    val durationObservedAt: Long? = null,
    val addressObservedAt: Long? = null,
    /** A bounded media response is not a decoded first frame or sustained download rate. */
    val mediaObservedAt: Long? = null,
    val mediaPreferredHeight: Int? = null,
    val mediaHttpStatus: Int? = null,
    val mediaVerified: Boolean? = null,
    val mediaFailure: String? = null,
    val mediaQuality: String? = null,
    val mediaHeaderHeight: Int? = null,
    val mediaSampleBytesPerSecond: Long? = null,
    val mediaSampleMs: Long? = null,
)

internal data class AnimeWorkPlaybackMeasurements(
    val episode: AnimeEpisode,
    val option: AnimeEpisodeOption,
    val measurements: AnimePlaybackMeasurements,
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
    val preferredSourceHeight: Int = 0,
    val bufferForwardMiB: Int? = null,
    val bufferBackMiB: Int? = null,
    val nextEpisodeLeadSeconds: Int? = null,
    val nextEpisodeBufferSeconds: Int? = null,
    val nextEpisodeCacheMiB: Int? = null,
    val predecodeNextEpisode: Boolean = true,
    val onlinePreparationSeconds: Int? = null,
    val bufferRefillNearTarget: Boolean = true,
) {
    fun validate(): AnimePlaybackSettings {
        require(skipOpeningSeconds >= 0 && skipEndingSeconds >= 0) { "跳过时长不能小于零。" }
        require(longPressSpeed.isFinite() && longPressSpeed > 0f) { "长按速度必须大于零。" }
        require(bufferMinMs >= 0 && bufferMaxMs > 0 && bufferMaxMs >= bufferMinMs && bufferStartMs >= 0 &&
            bufferRebufferMs >= 0 && bufferStartMs <= bufferMinMs && bufferRebufferMs <= bufferMinMs) {
            "缓冲上限应不小于下限；起播与重新缓冲应不大于下限。"
        }
        require(automaticRecoveryAttempts >= 0) { "自动恢复次数不能小于零。" }
        require(preferredSourceHeight in listOf(0, 480, 720, 1080, 2160)) { "未知的来源清晰度目标。" }
        require(bufferForwardMiB == null || bufferForwardMiB > 0) { "前方缓冲容量必须大于零。" }
        require(bufferBackMiB == null || bufferBackMiB >= 0) { "回看容量不能为负。" }
        require(nextEpisodeLeadSeconds == null || nextEpisodeLeadSeconds >= 0) { "提前准备时长不能为负。" }
        require(nextEpisodeBufferSeconds == null || nextEpisodeBufferSeconds >= 0) { "下一集缓冲时长不能为负。" }
        require(nextEpisodeCacheMiB == null || nextEpisodeCacheMiB >= 0) { "下一集缓存容量不能为负。" }
        require(onlinePreparationSeconds == null || onlinePreparationSeconds > 0) { "增强窗口时长必须大于零。" }
        return this
    }
}

enum class AnimePreparationPhase { WAITING, RESOLVING, CACHING, DECODING, READY, PARTIAL, FAILED }

data class AnimeNextEpisodePreparation(
    val episodeId: String,
    val addressResolved: Boolean = false,
    val cachedBytes: Long = 0L,
    val startupFramePrepared: Boolean = false,
    val phase: AnimePreparationPhase = AnimePreparationPhase.RESOLVING,
    val positionMs: Long = 0L,
    val bufferedDurationMs: Long? = null,
    val targetDurationMs: Long = 0L,
    val maximumBytes: Long = 0L,
    val detail: String? = null,
)

enum class AnimeSourceMeasurementPhase(internal val evidencePriority: Int = 0) {
    QUEUED, RUNNING, COMPLETE(4), MANIFEST_ONLY(3), ADDRESS_ONLY(2), FAILED(1),
    WAITING_CATALOG, NO_EPISODE, UNAVAILABLE, CANCELLED;

    internal val checking: Boolean
        get() = this == QUEUED || this == RUNNING
}

data class AnimeSourceLineMeasurement(val phase: AnimeSourceMeasurementPhase, val reason: String? = null)

data class AnimeSourceMeasurementProgress(
    val episodeId: String?,
    val phase: AnimeSourceMeasurementPhase,
    val reason: String? = null,
    val lines: List<AnimeEpisodeOption> = emptyList(),
    val lineMeasurements: Map<AnimeEpisodeOption, AnimeSourceLineMeasurement> = emptyMap(),
)

data class AnimeSourceMeasurementBatch(
    val subjectId: String,
    /** Null measures one confirmed episode per source across the work. */
    val episodeId: String?,
    val running: Boolean,
    val sources: Map<String, AnimeSourceMeasurementProgress>,
    val manual: Boolean = false,
)

data class AnimeScreenState(
    val libraryReadUnavailable: Boolean = false,
    val discoveryTab: AnimeDiscoveryTab = AnimeDiscoveryTab.HOME,
    val discoveryFeeds: Map<String, AnimeDiscoveryFeed> = emptyMap(),
    val discoveryColumns: Int? = null,
    val detail: AnimeDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    /** The exact historical episode currently being reopened, not general source discovery. */
    val pendingHistoryEpisodeId: String? = null,
    /** Detail open to the first exact episode line published in this visit. */
    val detailFirstConfirmedLineMs: Long? = null,
    val detailScores: List<AnimeSourceScore> = emptyList(),
    val detailScoreChecks: Map<AnimeDiscoveryProvider, AnimeScoreCheck> = emptyMap(),
    val nativeMetadataSearch: AnimeNativeMetadataSearch? = null,
    val groupSourceCandidates: List<AnimeSourceTitle> = emptyList(),
    val associationChoices: Map<String, List<AnimeSourceTitle>> = emptyMap(),
    val library: List<AnimeLibraryEntry> = emptyList(),
    /** Exact-owner, uncleared local positions; partial episodes are distinct from completed ones. */
    val episodeProgress: Map<String, AnimeWatchProgress> = emptyMap(),
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
    val playbackRecoveryStopReason: String? = null,
    val episodeOptions: Map<String, List<AnimeEpisodeOption>> = emptyMap(),
    val episodeOptionsLoading: Boolean = false,
    val selectedEpisodeId: String? = null,
    val selectedPlaybackOption: AnimeEpisodeOption? = null,
    val sourceMeasurements: AnimeSourceMeasurementBatch? = null,
    val playbackMeasurementsRevision: Long = 0,
    val playbackFirstFrameMs: Long? = null,
    val playbackIntentFirstFrameMs: Long? = null,
    val playbackRouteMode: DownloadRouteMode? = null,
    val resolving: Boolean = false,
    val playbackTarget: AnimePlaybackTarget? = null,
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
    val nextEpisodePreparation: AnimeNextEpisodePreparation? = null,
    val playbackResolutions: List<AnimeResolution> = emptyList(),
    val selectedResolutionId: String? = null,
    val canRestorePlayback: Boolean = false,
    val sourceDanmakuAvailable: Boolean = false,
    val sourceExtensions: List<dev.mangaflow.anime.sources.lanerc.LanercSourceListing> = emptyList(),
    val sourceOperationIds: Set<String> = emptySet(),
    val sourcePreparation: Map<String, dev.mangaflow.anime.sources.lanerc.SourcePreparationState> = emptyMap(),
) {
    val detailScoreLoading: Boolean get() = detailScoreChecks.values.any { it.loading }
    val detailScoreError: String? get() = detailScoreChecks.values.mapNotNull { it.error }.joinToString("\n").ifBlank { null }
    val detailScoreMessage: String? get() = detailScoreChecks.values.mapNotNull { it.message }.joinToString(" · ").ifBlank { null }
}

data class AnimeNativeMetadataSearch(
    val subjectId: String,
    val query: String,
    val candidates: List<AnimeSubject> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

internal fun AnimeSubject.canonicalBangumiId(): Int? = externalIds["bangumi"]?.toIntOrNull()?.takeIf {
    it > 0 && id == "bangumi:$it" && externalIds["bangumi"] == it.toString() &&
        "native-source" !in externalIds
}

internal fun AnimeSubject.canonicalDoubanId(): Long? = externalIds["douban"]?.toLongOrNull()?.takeIf {
    it > 0L && id == "douban:$it" && externalIds["douban"] == it.toString() &&
        "native-source" !in externalIds
}
