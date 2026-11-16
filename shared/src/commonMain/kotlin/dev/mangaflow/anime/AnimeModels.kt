package dev.mangaflow.anime

import dev.mangaflow.net.DownloadRouteSnapshot

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
)

data class AnimeSourceEpisode(
    val id: String,
    val title: String,
    val url: String,
    val number: Double? = null,
    val kind: AnimeEpisodeKind = AnimeEpisodeKind.MAIN,
    val group: String = "",
    val edition: String = "",
)

data class AnimeSourceCatalog(val title: AnimeSourceTitle, val episodes: List<AnimeSourceEpisode>)

data class AnimeSubtitle(
    val id: String,
    val url: String,
    val label: String,
    val language: String? = null,
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
)

data class AnimeSourceBinding(
    val subjectId: String,
    val catalog: AnimeSourceTitle,
    val episodeMappings: Map<String, String> = emptyMap(),
    val preferred: Boolean = false,
    val selectedGroup: String? = null,
)

data class AnimeSourceResult(
    val providerId: String,
    val name: String,
    val items: List<AnimeSourceTitle> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
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

data class AnimeScreenState(
    val query: String = "",
    val catalog: List<AnimeSubject> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
    val detail: AnimeDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val library: List<AnimeLibraryEntry> = emptyList(),
    val sourceResults: List<AnimeSourceResult> = emptyList(),
    val sourceCatalogs: List<AnimeSourceCatalog> = emptyList(),
    val sourceBindings: List<AnimeSourceBinding> = emptyList(),
    val sourceCatalogErrors: Map<String, String> = emptyMap(),
    val previewCatalog: AnimeSourceCatalog? = null,
    val previewLoading: Boolean = false,
    val sourceError: String? = null,
    val episodeOptions: Map<String, List<AnimeEpisodeOption>> = emptyMap(),
    val selectedEpisodeId: String? = null,
    val resolving: Boolean = false,
    val pendingPlayback: AnimePlaybackChoice? = null,
    val accounts: List<AnimeTrackingAccount> = emptyList(),
    val reviews: List<AnimeTrackingReview> = emptyList(),
    val syncing: Boolean = false,
    val notice: String? = null,
    val canUndo: Boolean = false,
    val preferredSourceId: String? = null,
    val sourceGroups: Map<String, String> = emptyMap(),
    val sourceRoutes: Map<String, dev.mangaflow.net.DownloadRouteMode> = emptyMap(),
)
