@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package dev.mangaflow.anime.player

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3 1.11.1 has no runtime setters for DefaultLoadControl's thresholds. Keep its
 * allocator/player lifecycle, but evaluate loading against one immutable, volatile
 * configuration. Updating preferences therefore does not recreate the media source,
 * decoder or playback timeline.
 *
 * The allocator counts compressed samples. It cannot distinguish samples before and
 * after the playhead, and Media3 does not support a dynamic byte-sized back buffer.
 */
internal class AndroidAnimeLoadControl(
    initial: AnimePlayerOptions,
    private val gate: AndroidAnimeStartupGate? = null,
    previousTargetBytes: Long = AnimePlayerOptions().bufferForwardBytes,
    private val heapMaximumBytes: Long = Runtime.getRuntime().maxMemory(),
    initialTarget: Int = acceptedTarget(initial.bufferForwardBytes, previousTargetBytes, heapMaximumBytes),
    private val allocator: DefaultAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(initial.bufferMinMs, initial.bufferMaxMs,
            initial.bufferStartMs, initial.bufferRebufferMs)
        .setTargetBufferBytes(initialTarget)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build(),
) : LoadControl {
    private data class Configuration(val options: AnimePlayerOptions, val target: Int)
    @Volatile private var configuration = Configuration(initial, initialTarget)
    private val reevaluate = AtomicBoolean(true)
    // Only the ExoPlayer playback thread reads/writes this hysteresis state.
    private var loading = false
    val effectiveForwardBytes: Long get() = configuration.target.toLong()

    // Media3's Java default methods are not a safe Kotlin `by` delegation surface:
    // several fall through to deprecated overloads that throw "not implemented".
    // Forward the pinned player's complete lifecycle and buffer contract explicitly.
    override fun onPrepared(playerId: PlayerId) {
        loading = false
        reevaluate.set(true)
        delegate.onPrepared(playerId)
    }

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
        allocator.setTargetBufferSize(configuration.target)
    }

    override fun onStopped(playerId: PlayerId) {
        loading = false
        delegate.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        loading = false
        delegate.onReleased(playerId)
    }

    // Preserve DefaultLoadControl's per-player allocation accounting wrapper.
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline,
        mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    fun update(options: AnimePlayerOptions) {
        val last = configuration
        val previous = last.options
        configuration = Configuration(options, acceptedTarget(options.bufferForwardBytes, last.target.toLong(), heapMaximumBytes))
        if (previous.bufferMinMs != options.bufferMinMs || previous.bufferMaxMs != options.bufferMaxMs ||
            previous.bufferStartMs != options.bufferStartMs || previous.bufferRebufferMs != options.bufferRebufferMs ||
            previous.bufferForwardBytes != options.bufferForwardBytes) reevaluate.set(true)
    }

    fun metrics(sampledAtMillis: Long): AnimeBufferMetrics {
        val current = configuration
        val options = current.options
        val target = current.target.toLong()
        return AnimeBufferMetrics(
            allocatedBytes = allocator.totalBytesAllocated.toLong(),
            targetForwardBytes = target,
            targetBackBytes = null,
            sampledAtMillis = sampledAtMillis,
            detail = buildString {
                append("已分配值为播放器压缩样本内存，无法精确拆分前后方；前方目标按样本块生效")
                if (options.bufferForwardBytes >= heapMaximumBytes)
                    append("；请求的前方预算达到或超过本进程堆上限 ${heapMaximumBytes / (1024L * 1024L)} MiB，" +
                        "继续使用上次有效或内核默认目标；所设值已保留")
                else if (target != options.bufferForwardBytes)
                    append("；请求的前方预算超过当前引擎整数容量，已按可支持容量执行")
                if (options.bufferBackBytes > 0L)
                    append("；当前引擎不支持按字节设置后方保留，后方设置已保存")
            },
        )
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val current = configuration
        val options = current.options
        val target = current.target
        // DefaultLoadControl can reset the allocator target on track changes. Reapply
        // the current value on the playback thread before each loading decision.
        allocator.setTargetBufferSize(target)
        allocator.trim()
        if (gate?.allowsLoading == false) {
            loading = false
            return false
        }
        val full = allocator.totalBytesAllocated >= target
        if (reevaluate.getAndSet(false)) loading = true
        val maximumUs = options.bufferMaxMs.toLong() * 1_000L
        var minimumUs = options.bufferMinMs.toLong() * 1_000L
        if (parameters.playbackSpeed > 1f)
            minimumUs = minOf(Util.getMediaDurationForPlayoutDuration(minimumUs, parameters.playbackSpeed), maximumUs)
        minimumUs = maxOf(minimumUs, 500_000L)
        if (parameters.bufferedDurationUs < minimumUs) loading = !full
        else if (parameters.bufferedDurationUs >= maximumUs || full) loading = false
        return loading
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val current = configuration
        val options = current.options
        var requiredUs = (if (parameters.rebuffering) options.bufferRebufferMs else options.bufferStartMs).toLong() * 1_000L
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET)
            requiredUs = minOf(requiredUs, parameters.targetLiveOffsetUs / 2L)
        val bufferedUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        // A deliberately small byte target must not deadlock startup waiting for a
        // duration the configured memory allowance cannot hold.
        return requiredUs <= 0L || bufferedUs >= requiredUs ||
            allocator.totalBytesAllocated >= current.target
    }

    private companion object {
        fun acceptedTarget(requested: Long, previous: Long, heapMaximum: Long): Int {
            // Reject only a provably impossible target; this is not a proportional
            // memory policy and does not claim to prevent every source of memory pressure.
            val accepted = if (requested in 1L until heapMaximum) requested else
                sequenceOf(previous, AnimePlayerOptions().bufferForwardBytes,
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_SIZE.toLong(), C.DEFAULT_BUFFER_SEGMENT_SIZE.toLong())
                    .firstOrNull { it in 1L until heapMaximum } ?: 1L
            return nativeTarget(accepted)
        }

        // DefaultAllocator reports an Int and allocates whole segments. Leave one
        // segment of representable headroom; do not mutate the persisted preference.
        fun nativeTarget(bytes: Long): Int = bytes.coerceIn(1L,
            Int.MAX_VALUE.toLong() - C.DEFAULT_BUFFER_SEGMENT_SIZE).toInt()
    }
}
