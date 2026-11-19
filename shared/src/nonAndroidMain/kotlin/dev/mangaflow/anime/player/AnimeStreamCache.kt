package dev.mangaflow.anime.player

import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.net.NetworkResponse

/** The native stream bridge delegates persistence and eviction to the platform's one cache owner. */
internal interface AnimeStreamCache {
    fun retain(asset: AnimePlaybackAsset): () -> Unit
    fun read(asset: AnimePlaybackAsset, url: String, range: String?): AnimeCachedStream?
    fun write(asset: AnimePlaybackAsset, url: String, response: NetworkResponse): AnimeStreamCacheWriter?
}

internal interface AnimeCachedStream {
    val start: Long
    val end: Long
    val total: Long
    val contentType: String
    fun read(buffer: ByteArray): Int
    fun close()
}

internal interface AnimeStreamCacheWriter {
    fun append(bytes: ByteArray, count: Int)
    /** Incomplete spans are discarded; a complete bounded prefix may already have been published. */
    fun close()
}
