@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import dev.mangaflow.anime.AnimePlaybackAsset
import dev.mangaflow.anime.sources.animePublicUrl
import dev.mangaflow.platform.PlatformContext
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal suspend fun cacheAndroidAnimeStartup(
    context: PlatformContext,
    asset: AnimePlaybackAsset,
    byteLimit: Long,
): Long = coroutineScope {
    val client = animeMediaHttpClient(asset, forPrefetch = true)
    val cancelRequests = launch {
        try { awaitCancellation() }
        finally { client.dispatcher.cancelAll() }
    }
    try {
        withContext(Dispatchers.IO) {
            val owner = AndroidAnimeCache.get(context)
            val budget = minOf(byteLimit, owner.targetBytes)
            if (budget <= 0L) return@withContext 0L
            val release = owner.acquire()
            try {
                val root = Uri.parse(animePublicUrl(asset.url).toString())
                val upstream = OkHttpDataSource.Factory(client)
                val verifiedUpstream = DataSource.Factory { VerifiedStartupSource(upstream.createDataSource()) }
                val cached = animeCacheDataSource(asset, owner, verifiedUpstream)
                suspend fun store(uri: Uri, offset: Long, length: Long): Pair<Long, Long> {
                    val checked = Uri.parse(animePublicUrl(uri.toString()).toString())
                    val spec = DataSpec.Builder().setUri(checked).setPosition(offset).setLength(length)
                        .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
                    val source = cached.createDataSource()
                    var remaining = length
                    try {
                        source.open(spec)
                        val buffer = ByteArray(32 * 1024)
                        while (remaining > 0L) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count == C.RESULT_END_OF_INPUT) break
                            remaining -= count
                        }
                    } finally {
                        source.close()
                    }
                    return owner.cache.getCachedLength(animeCacheKey(asset, checked), offset, length)
                        .coerceAtLeast(0L) to (length - remaining)
                }
                when (Util.inferContentTypeForUriAndMimeType(root, asset.mimeType)) {
                    C.CONTENT_TYPE_HLS -> {
                        val manifestBudget = minOf(256 * 1024L, budget / 4).toInt()
                        if (manifestBudget == 0) return@withContext 0L
                        val (playlist, rootBytes) = readStartupPlaylist(upstream, root, HlsPlaylistParser(), manifestBudget)
                        var remaining = budget - rootBytes
                        val media = when (playlist) {
                            is HlsMediaPlaylist -> playlist
                            is HlsMultivariantPlaylist -> {
                                val variant = playlist.variants.firstOrNull() ?: return@withContext 0L
                                val (child, childBytes) = readStartupPlaylist(upstream, variant.url,
                                    HlsPlaylistParser(playlist, null), minOf(manifestBudget.toLong(), remaining).toInt())
                                remaining -= childBytes
                                child as? HlsMediaPlaylist ?: return@withContext 0L
                            }
                            else -> return@withContext 0L
                        }
                        if (!media.hasEndTag) return@withContext 0L
                        val first = media.segments.firstOrNull() ?: return@withContext 0L
                        if (first.hasGapTag || first.fullSegmentEncryptionKeyUri != null || first.drmInitData != null)
                            return@withContext 0L
                        var total = 0L
                        for (segment in listOfNotNull(first.initializationSegment, first)) {
                            if (remaining <= 0L || segment.fullSegmentEncryptionKeyUri != null || segment.drmInitData != null) break
                            val length = segment.byteRangeLength.takeIf { it > 0 }?.coerceAtMost(remaining) ?: remaining
                            val uri = UriUtil.resolveToUri(media.baseUri, segment.url)
                            val (bytes, readBytes) = store(uri, segment.byteRangeOffset, length)
                            total += bytes
                            remaining -= readBytes
                        }
                        total
                    }
                    C.CONTENT_TYPE_OTHER -> store(root, 0L, budget).first
                    else -> 0L
                }
            } finally {
                release()
            }
        }
    } finally {
        cancelRequests.cancel()
        client.connectionPool.evictAll()
    }
}

private suspend fun readStartupPlaylist(
    upstream: DataSource.Factory,
    uri: Uri,
    parser: HlsPlaylistParser,
    limit: Int,
): Pair<HlsPlaylist, Int> {
    val checked = Uri.parse(animePublicUrl(uri.toString()).toString())
    val source = upstream.createDataSource()
    DataSourceInputStream(source, DataSpec(checked)).use { input ->
        val bytes = ByteArray(limit)
        var size = 0
        while (size < limit) {
            currentCoroutineContext().ensureActive()
            val count = input.read(bytes, size, limit - size)
            if (count < 0) return parser.parse(source.uri ?: checked, ByteArrayInputStream(bytes, 0, size)) to size
            size += count
        }
        throw IOException("预取清单超过可用预算")
    }
}

/** Only verified video prefixes enter the shared playback cache from optional work. */
private class VerifiedStartupSource(private val source: DataSource) : DataSource by source {
    private val prefix = ByteArray(5 * 188)
    private var size = 0
    private var position = 0

    override fun open(dataSpec: DataSpec): Long {
        position = 0
        size = 0
        val length = source.open(dataSpec)
        val limit = minOf(prefix.size.toLong(), dataSpec.length.takeIf { it > 0 } ?: Long.MAX_VALUE).toInt()
        while (size < limit) {
            val count = source.read(prefix, size, limit - size)
            if (count == C.RESULT_END_OF_INPUT) break
            size += count
        }
        if (!isAnimeStartupVideoPrefix(prefix, size)) throw IOException("未识别到可缓存的视频开头")
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return source.read(buffer, offset, length)
        val count = minOf(length, size - position)
        prefix.copyInto(buffer, offset, position, position + count)
        position += count
        return count
    }
}
