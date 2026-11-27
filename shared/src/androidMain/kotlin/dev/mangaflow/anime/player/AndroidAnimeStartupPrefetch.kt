@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import dev.mangaflow.anime.animeRenditionOrder
import dev.mangaflow.anime.sources.animeMediaUrl
import dev.mangaflow.platform.PlatformContext
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Optional original-media work uses the same route, cache identity and asset lease as playback. */
internal suspend fun cacheAndroidAnimeStartup(
    context: PlatformContext,
    request: AnimeStartupCacheRequest,
): AnimeStartupCacheResult = coroutineScope {
    if (request.maximumBytes <= 0L || request.targetDurationMs <= 0L)
        return@coroutineScope AnimeStartupCacheResult(0L, detail = "下一集预读目标已关闭")
    if (request.validUntil <= SystemClock.elapsedRealtime())
        return@coroutineScope AnimeStartupCacheResult(0L, detail = "下一集准备已过期")
    val client = animeMediaHttpClient(request.asset, forPrefetch = true)
    val cancelRequests = launch(Dispatchers.IO) {
        try { awaitCancellation() }
        finally { client.cancelAll() }
    }
    val expiry = launch(Dispatchers.IO) {
        delay((request.validUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        client.cancelAll()
    }
    try {
        withContext(Dispatchers.IO) {
            val owner = AndroidAnimeCache.get(context)
            val release = owner.acquire(request.asset)
            try { StartupCacheReader(owner, OkHttpDataSource.Factory(client), request).run() }
            finally { release() }
        }
    } finally {
        cancelRequests.cancel()
        expiry.cancel()
        closeAnimeMediaHttpClient(client)
    }
}

private data class StartupStoredRange(val key: String, val offset: Long, val length: Long)
private data class StartupStoreResult(val complete: Boolean, val detail: String? = null)

private class StartupPlaylist(
    val media: HlsMediaPlaylist,
    val positionUs: Long,
    val targetEndUs: Long,
    val audio: Boolean,
) {
    var nextIndex = media.segments.indexOfLast { it.relativeStartTimeUs <= positionUs }
    var endUs = positionUs
    val discontinuity = media.segments.getOrNull(nextIndex)?.relativeDiscontinuitySequence
    val remainingMs: Long get() = (endUs - positionUs).coerceAtLeast(0L) / 1_000L
}

private class StartupCacheReader(
    private val owner: AndroidAnimeCache,
    private val upstream: DataSource.Factory,
    private val request: AnimeStartupCacheRequest,
) {
    private val asset = request.asset
    private var remaining = minOf(request.maximumBytes, owner.targetBytes).coerceAtLeast(0L)
    private val manifestLimit = minOf(256 * 1024L, remaining / 4L).toInt()
    private val stored = mutableListOf<StartupStoredRange>()
    private val completed = mutableSetOf<StartupStoredRange>()
    private var verifiedDurationMs: Long? = null
    private var reportRequestedBytes = true

    suspend fun run(): AnimeStartupCacheResult {
        if (remaining <= 0L) return AnimeStartupCacheResult(0L, detail = "原片缓存容量已关闭")
        val detail = try {
            checkActive()
            val root = checkedUri(Uri.parse(asset.url))
            when (inferAnimeMediaContentType(root, asset.mimeType)) {
                C.CONTENT_TYPE_HLS -> prepareHls(root)
                C.CONTENT_TYPE_OTHER -> {
                    // A byte prefix contains no verified timestamp-to-byte mapping. The
                    // separate retained ExoPlayer preparation seeks to the exact resume
                    // position and acknowledges its actual first decoded frame.
                    reportRequestedBytes = request.positionMs <= 0L
                    val result = store(root, 0L, C.LENGTH_UNSET.toLong(), audio = false, mp4Only = true)
                    if (request.positionMs > 0L)
                        "仅准备文件索引，续看位置由首帧预解码准备" +
                            (result.detail?.let { "；$it" } ?: "")
                    else result.detail ?: "已缓存文件开头；原片可播放时长需由播放器解码确认"
                }
                else -> "当前来源格式不支持下一集原片预读"
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: IOException) {
            if (SystemClock.elapsedRealtime() >= request.validUntil) "下一集准备已过期"
            else readablePlayerError(failure)
        } catch (failure: IllegalArgumentException) {
            readablePlayerError(failure)
        }
        return AnimeStartupCacheResult(
            bytes = if (reportRequestedBytes) residentBytes() else 0L,
            bufferedDurationMs = verifiedDurationMs,
            detail = detail,
        )
    }

    private suspend fun prepareHls(root: Uri): String {
        val playlist = readPlaylist(root, HlsPlaylistParser())
        var audio: HlsMediaPlaylist? = null
        var exactNativeSelection = true
        val media = when (playlist) {
            is HlsMediaPlaylist -> playlist
            is HlsMultivariantPlaylist -> {
                if (playlist.contentSteeringInfo != null || playlist.sessionKeyDrmInitData.isNotEmpty() ||
                    playlist.declaresEncryption())
                    return "当前 HLS 线路含动态分流或受保护内容，保留正常播放"
                val height = asset.preferredAdaptiveHeight
                val variant = (if (height == 0) playlist.variants.firstOrNull() else playlist.variants
                    .minByOrNull { animeRenditionOrder(it.format.height, height) }) ?: return "来源没有可预读的视频线路"
                // The regular player keeps adaptive selection. A candidate rendition
                // must not masquerade as the exact buffer that player will consume.
                if (playlist.variants.size != 1) exactNativeSelection = false
                if (playlist.videos.any { it.groupId == variant.videoGroupId && it.url != null })
                    return "当前 HLS 使用独立视频轨，需由播放器准备"
                val primary = readPlaylist(variant.url, HlsPlaylistParser(playlist, null)) as? HlsMediaPlaylist
                    ?: return "来源没有可预读的媒体清单"
                val candidates = playlist.audios.filter { it.groupId == variant.audioGroupId }
                if (candidates.size > 1) exactNativeSelection = false
                val selected = candidates.firstOrNull { it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }
                    ?: candidates.firstOrNull()
                if (variant.audioGroupId != null && selected == null && playlist.muxedAudioFormat == null)
                    return "当前 HLS 音轨无法确定，需由播放器准备"
                selected?.url?.let { uri ->
                    audio = readPlaylist(uri, HlsPlaylistParser(playlist, null)) as? HlsMediaPlaylist
                        ?: return "来源音轨清单无法预读"
                }
                primary
            }
            else -> return "来源没有可预读的媒体清单"
        }
        val all = listOfNotNull(media, audio)
        if (all.any { !it.hasEndTag || it.trailingParts.isNotEmpty() })
            return "下一集原片预读仅支持完整点播清单，直播与更新中清单保持正常播放"
        if (all.any { it.protectionSchemes != null || it.declaresEncryption() })
            return "受保护的媒体由播放器处理，未预读加密片段"
        val positionUs = millisToMicros(request.positionMs)
        val durationUs = millisToMicros(request.targetDurationMs)
        if (positionUs >= media.durationUs || media.segments.isEmpty())
            return "续看位置已到达媒体末尾，没有前方片段可预读"
        val tracks = mutableListOf(StartupPlaylist(media, positionUs, boundedEnd(positionUs, durationUs), false))
        audio?.let { track ->
            // Separate renditions need a shared time origin. Equal sequence numbers
            // and guessed bitrates are not evidence of audio/video alignment.
            if (!media.hasProgramDateTime || !track.hasProgramDateTime)
                return "分离音轨缺少可核对的共同时间轴，续看位置由首帧预解码准备"
            val audioPosition = positionUs + media.startTimeUs - track.startTimeUs
            if (audioPosition < 0L || audioPosition >= track.durationUs)
                return "续看位置不在音画共同时间范围内，需由播放器准备"
            tracks += StartupPlaylist(track, audioPosition, boundedEnd(audioPosition, durationUs), true)
        }
        if (tracks.any { it.nextIndex < 0 }) return "无法定位续看位置的媒体分段"
        verifiedDurationMs = 0L.takeIf { exactNativeSelection }
        var stopped: String? = null
        while (remaining > 0L) {
            checkActive()
            // Interleave audio and video so a large video rendition cannot consume
            // the entire allowance before the corresponding audio is prepared.
            val track = tracks.filter { it.endUs < minOf(it.targetEndUs, it.media.durationUs) }
                .minByOrNull { it.remainingMs } ?: break
            val segment = track.media.segments.getOrNull(track.nextIndex)
            if (segment == null || segment.relativeStartTimeUs > track.endUs) {
                stopped = "媒体分段时间不连续，已保留可核对的前方片段"
                break
            }
            if (segment.hasGapTag || segment.fullSegmentEncryptionKeyUri != null || segment.drmInitData != null ||
                segment.relativeDiscontinuitySequence != track.discontinuity) {
                stopped = "在缺失、加密或时间轴切换的分段前停止，保留正常播放"
                break
            }
            val initialization = segment.initializationSegment
            if (initialization != null) {
                if (initialization.fullSegmentEncryptionKeyUri != null || initialization.drmInitData != null) {
                    stopped = "初始化片段受保护，需由播放器准备"
                    break
                }
                val init = store(UriUtil.resolveToUri(track.media.baseUri, initialization.url),
                    initialization.byteRangeOffset, initialization.byteRangeLength, track.audio)
                if (!init.complete) {
                    stopped = init.detail ?: "初始化片段未完整缓存，等待下次准备"
                    break
                }
            }
            val result = store(UriUtil.resolveToUri(track.media.baseUri, segment.url),
                segment.byteRangeOffset, segment.byteRangeLength, track.audio)
            if (!result.complete) {
                stopped = result.detail ?: "原片预读达到字节预算；最后一个分段尚未完整缓存"
                break
            }
            track.endUs = segment.relativeStartTimeUs + segment.durationUs
            track.nextIndex++
            verifiedDurationMs = tracks.minOf { it.remainingMs }.takeIf { exactNativeSelection }
        }
        val detail = stopped ?: if (tracks.all { it.endUs >= minOf(it.targetEndUs, it.media.durationUs) })
            "已核对续看位置起的完整点播分段" + if (audio != null) "及默认音轨" else ""
        else "原片预读达到字节预算；已保存可核对的完整分段"
        return detail + if (exactNativeSelection) "" else
            "；缓存为候选清晰度/音轨，实际选轨由播放器决定，原片可播放时长仍未知"
    }

    private suspend fun readPlaylist(uri: Uri, parser: HlsPlaylistParser): HlsPlaylist {
        checkActive()
        val limit = minOf(manifestLimit.toLong(), remaining).toInt()
        if (limit <= 0) throw IOException("预读清单达到字节预算")
        val checked = checkedUri(uri)
        val source = upstream.createDataSource()
        DataSourceInputStream(source, DataSpec(checked)).use { input ->
            val bytes = ByteArray(limit)
            var size = 0
            while (size < limit) {
                checkActive()
                val count = input.read(bytes, size, limit - size)
                if (count == C.RESULT_END_OF_INPUT)
                    return parser.parse(source.uri ?: checked, ByteArrayInputStream(bytes, 0, size))
                size += count
                remaining -= count
            }
        }
        throw IOException("预取清单超过可用预算")
    }

    private suspend fun store(uri: Uri, offset: Long, declaredLength: Long, audio: Boolean,
        mp4Only: Boolean = false): StartupStoreResult {
        checkActive()
        if (offset < 0L) return StartupStoreResult(false, "媒体分段范围无效")
        val checked = checkedUri(uri)
        val key = owner.register(asset, animeCacheKey(asset, checked))
        val known = declaredLength.takeIf { it > 0L }
        val completedRange = StartupStoredRange(key, offset, known ?: C.LENGTH_UNSET.toLong())
        if (completedRange in completed) return StartupStoreResult(true)
        val allowance = minOf(known ?: remaining, remaining)
        if (allowance <= 0L) return StartupStoreResult(false, "原片预读达到字节预算")
        val imagePrefixedTransport = !mp4Only && usesImagePrefixedAnimeHls(asset.sourceId)
        val prefixLimit = if (imagePrefixedTransport) ANIME_HLS_IMAGE_INSPECTION_LIMIT else ANIME_STARTUP_MEDIA_PREFIX_BYTES
        val prefixLength = minOf(prefixLimit.toLong(), known ?: allowance, allowance).toInt()
        val cachedPrefix = cachedPrefixIsValid(key, checked, offset, prefixLength, audio, mp4Only, imagePrefixedTransport)
        if (!cachedPrefix && owner.cache.isCached(key, offset, prefixLength.toLong()))
            return StartupStoreResult(false, "已有媒体片段的开头无法核对，交由播放器继续准备")
        val verified = DataSource.Factory {
            VerifiedStartupMediaSource(upstream.createDataSource(), offset, cachedPrefix, audio, mp4Only,
                imagePrefixedTransport, prefixLimit)
        }
        val source = animeCacheDataSource(asset, owner, verified).createDataSource()
        val spec = DataSpec.Builder().setUri(checked).setPosition(offset).setLength(allowance)
            .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION).build()
        var consumed = 0L
        var reachedEnd = false
        var failure: String? = null
        var fullLength = ContentMetadata.getContentLength(owner.cache.getContentMetadata(key))
            .takeIf { it >= offset }?.minus(offset)
        // Reserve before open(), which may read a format prefix. On an I/O failure
        // keep the reservation; uncertain bytes are never refunded for more work.
        remaining -= allowance
        try {
            source.open(spec)
            val headers = source.responseHeaders
            val contentRange = headers.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull()
            val resourceSize = contentRange?.substringAfterLast('/')?.toLongOrNull()
                ?: if (contentRange == null) headers.entries
                    .firstOrNull { it.key.equals("Content-Length", true) }?.value?.firstOrNull()?.toLongOrNull()
                else null
            if (resourceSize != null && resourceSize >= offset) fullLength = resourceSize - offset
            val buffer = ByteArray(32 * 1024)
            while (consumed < allowance) {
                checkActive()
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), allowance - consumed).toInt())
                if (count == C.RESULT_END_OF_INPUT) {
                    reachedEnd = true
                    break
                }
                consumed += count
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: IOException) {
            failure = readablePlayerError(error)
        } finally {
            // CacheDataSink commits its last partial span when closed.
            try { source.close() }
            catch (error: IOException) { if (failure == null) failure = readablePlayerError(error) }
            if (consumed > 0L) stored += StartupStoredRange(key, offset, consumed)
        }
        if (failure == null) remaining += allowance - consumed
        val expected = known ?: fullLength ?: consumed.takeIf { reachedEnd }
        val complete = failure == null && expected != null && expected > 0L &&
            owner.cache.isCached(key, offset, expected)
        if (complete) completed += completedRange
        return StartupStoreResult(complete, failure)
    }

    private fun cachedPrefixIsValid(key: String, uri: Uri, offset: Long, size: Int,
        audio: Boolean, mp4Only: Boolean, imagePrefixedTransport: Boolean): Boolean {
        if (size <= 0) return false
        val available = owner.cache.getCachedLength(key, offset, size.toLong()).coerceAtLeast(0L).toInt()
        if (available <= 0) return false
        val source = CacheDataSource.Factory().setCache(owner.cache).setCacheKeyFactory { key }.createDataSource()
        return try {
            val bytes = ByteArray(available)
            source.open(DataSpec.Builder().setUri(uri).setPosition(offset).setLength(available.toLong()).build())
            var read = 0
            while (read < available) {
                val count = source.read(bytes, read, available - read)
                if (count == C.RESULT_END_OF_INPUT) break
                read += count
            }
            isAnimeStartupMediaPrefix(bytes, read, audio, mp4Only, imagePrefixedTransport)
        } catch (_: IOException) { false }
        finally { source.close() }
    }

    /** Count only actual resident intersections, merging repeated init/range requests. */
    private fun residentBytes(): Long {
        var total = 0L
        for ((key, ranges) in stored.groupBy { it.key }) {
            val merged = mutableListOf<LongRange>()
            for (range in ranges.sortedBy { it.offset }) {
                val end = boundedEnd(range.offset, range.length)
                val previous = merged.lastOrNull()
                if (previous != null && range.offset <= previous.last) {
                    merged[merged.lastIndex] = previous.first..maxOf(previous.last, end)
                } else merged += range.offset..end
            }
            for (span in owner.cache.getCachedSpans(key)) {
                val end = boundedEnd(span.position, span.length)
                for (range in merged)
                    total += (minOf(end, range.last) - maxOf(span.position, range.first)).coerceAtLeast(0L)
            }
        }
        return total
    }

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        if (SystemClock.elapsedRealtime() >= request.validUntil) throw IOException("下一集准备已过期")
    }
}

/** Verify each segment at its declared start; arbitrary cached-hole offsets are not format headers. */
private class VerifiedStartupMediaSource(
    private val source: DataSource,
    private val expectedOffset: Long,
    private val cachedPrefix: Boolean,
    private val audio: Boolean,
    private val mp4Only: Boolean,
    private val imagePrefixedTransport: Boolean,
    prefixLimit: Int,
) : DataSource by source {
    private val prefix = ByteArray(prefixLimit)
    private var size = 0
    private var position = 0

    override fun open(dataSpec: DataSpec): Long {
        size = 0
        position = 0
        val length = source.open(dataSpec)
        val mime = source.responseHeaders.entries.firstOrNull { it.key.equals("Content-Type", true) }
            ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase()
        if (mime?.startsWith("text/") == true || mime == "application/json")
            throw IOException("来源返回了非媒体内容，未写入预读缓存")
        if (dataSpec.position != expectedOffset) {
            if (!cachedPrefix) throw IOException("已有片段的开头尚不可核对，交由播放器继续准备")
            return length
        }
        val limit = minOf(prefix.size.toLong(), dataSpec.length.takeIf { it > 0L } ?: Long.MAX_VALUE).toInt()
        while (size < limit) {
            val count = source.read(prefix, size, limit - size)
            if (count == C.RESULT_END_OF_INPUT) break
            size += count
        }
        if (!isAnimeStartupMediaPrefix(prefix, size, audio, mp4Only, imagePrefixedTransport))
            throw IOException("未识别到可预读的媒体片段，保留正常播放")
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

private fun checkedUri(uri: Uri): Uri = Uri.parse(animeMediaUrl(uri.toString()).toString())
private fun millisToMicros(value: Long): Long = value.coerceIn(0L, Long.MAX_VALUE / 1_000L) * 1_000L
private fun boundedEnd(start: Long, length: Long): Long = start + minOf(length, Long.MAX_VALUE - start)

private fun HlsPlaylist.declaresEncryption(): Boolean = tags.any { tag ->
    // Media3 deliberately drops unsupported SAMPLE-AES identity-key information
    // from Segment fields. Inspect retained manifest tags too, so such media is
    // never described as an independently usable, unencrypted prepared window.
    tag.startsWith("#EXT-X-SESSION-KEY:") ||
        tag.startsWith("#EXT-X-KEY:") && tag.substringAfter(':').trim() != "METHOD=NONE"
}
