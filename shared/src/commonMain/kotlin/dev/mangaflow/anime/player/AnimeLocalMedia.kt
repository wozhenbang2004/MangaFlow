package dev.mangaflow.anime.player

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.platform.storage.StorageFile
import dev.mangaflow.platform.storage.StorageLock
import kotlinx.atomicfu.locks.synchronized

/** A store-issued capability. Arbitrary file URLs never enter the online playback path. */
class AnimeLocalMedia internal constructor(
    val downloadId: String,
    private val assetId: String,
    private val episodeId: String,
    private val sourceId: String,
    private val sourceEpisodeId: String,
    private val sourceRevision: String,
    private val sessionEpoch: Long,
    private val edition: String,
    private val opener: () -> AnimeLocalMediaLease,
) {
    internal fun acquire(asset: AnimePlaybackAsset): AnimeLocalMediaLease {
        require(asset.id == assetId && asset.episodeId == episodeId && asset.sourceId == sourceId &&
            asset.sourceEpisodeId == sourceEpisodeId && asset.sourceRevision == sourceRevision &&
            asset.sessionEpoch == sessionEpoch && asset.edition == edition) {
            "离线媒体与当前剧集或来源不匹配"
        }
        return opener()
    }
}

internal data class AnimeLocalResource(val file: StorageFile, val mimeType: String, val size: Long)

/** The lease keeps these exact completed files alive until the native player releases them. */
internal class AnimeLocalMediaLease(
    val entryName: String,
    val resources: Map<String, AnimeLocalResource>,
    private val release: () -> Unit,
) : AutoCloseable {
    val entry: AnimeLocalResource get() = checkNotNull(resources[entryName])
    private val lock = StorageLock()
    private var closed = false
    override fun close() {
        val shouldRelease = synchronized(lock) { if (closed) false else { closed = true; true } }
        if (shouldRelease) release()
    }
}
