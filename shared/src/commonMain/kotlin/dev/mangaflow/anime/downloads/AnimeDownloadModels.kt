package dev.mangaflow.anime.downloads

import dev.mangaflow.anime.AnimeEpisode
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.player.AnimeLocalMedia
import dev.mangaflow.platform.PlatformContext

enum class AnimeDownloadStatus { QUEUED, DOWNLOADING, WAITING, PAUSED, COMPLETED, FAILED }

data class AnimeDownloadEntry(
    val id: String,
    val subject: AnimeSubject,
    val episode: AnimeEpisode,
    val sourceId: String,
    val sourceName: String,
    val sourceEpisodeId: String,
    val assetId: String,
    val edition: String,
    val sourceRevision: String,
    val sessionEpoch: Long,
    val status: AnimeDownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val completedFiles: Int = 0,
    val totalFiles: Int? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

sealed interface AnimeDownloadReadIssue {
    data object Partial : AnimeDownloadReadIssue
    data class Unavailable(val detail: String) : AnimeDownloadReadIssue
}

data class AnimeDownloadsState(
    val entries: List<AnimeDownloadEntry> = emptyList(),
    val loading: Boolean = true,
    val notice: String? = null,
    /** Whether this process could read the stored records, independent from action or scheduling notices. */
    val readIssue: AnimeDownloadReadIssue? = null,
)

data class AnimeDownloadCapabilities(
    val progressiveFiles: Boolean = true,
    val videoContainers: Set<String> = setOf("MP4", "MKV", "WebM", "TS"),
    /** Finite, unencrypted TS/fMP4 playlists, including one video variant with a declared default audio rendition. */
    val staticHls: Boolean = true,
    val systemBackgroundExecution: Boolean = false,
    val processBackgroundExecution: Boolean = false,
)

internal expect fun animeOfflineVideoContainers(): Set<String>
internal expect fun animeSystemDownloadExecution(): Boolean
internal expect fun animeProcessDownloadExecution(): Boolean
internal expect fun scheduleAnimeDownloadExecution(context: PlatformContext, recover: Boolean)

data class AnimeDownloadedPlayback(
    val subject: AnimeSubject,
    val episode: AnimeEpisode,
    val asset: AnimePlaybackAsset,
    val localMedia: AnimeLocalMedia,
)
