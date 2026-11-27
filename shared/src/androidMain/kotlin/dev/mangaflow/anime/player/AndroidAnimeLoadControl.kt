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
 * Media3 captures the keyframe flag and back-buffer duration once when the engine
 * is constructed. The compressed-sample allocator is shared by both directions.
 */
internal class AndroidAnimeLoadControl(
    initial: AnimePlayerOptions,
    private val gate: AndroidAnimeStartupGate? = null,
    previousTargetBytes: Long = AnimePlayerOptions().bufferForwardBytes,
    private val heapMaximumBytes: Long = Runtime.getRuntime().maxMemory(),
    initialTarget: Int = acceptedTarget(initial.bufferForwardBytes, previousTargetBytes, heapMaximumBytes),
    private val backWindowMs: Int = projectBackWindowMs(initial, initialTarget),
    private val allocator: DefaultAllocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    private val delegate: DefaultLoadControl = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        .setBufferDurationsMs(initial.bufferMinMs, initial.bufferMaxMs,
            initial.bufferStartMs, initial.bufferRebufferMs)
        .setBackBuffer(backWindowMs, backWindowMs > 0)
        .setTargetBufferBytes(initialTarget)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build(),
) : LoadControl {
    private data class Configuration(val options: AnimePlayerOptions, val target: Int, val nextBackWindowMs: Int)
    @Volatile private var configuration = Configuration(initial, initialTarget, backWindowMs)
    private val reevaluate = AtomicBoolean(true)
    // Only the ExoPlayer playback thread reads/writes this hysteresis state.
    private var loading = false
    private var selectedBitrate = 0L
    val effectiveForwardBytes: Long get() = configuration.target.toLong()

    // Media3's Java default methods are not a safe Kotlin `by` delegation surface:
    // several fall through to deprecated overloads that throw "not implemented".
    // Forward the pinned player's complete lifecycle and buffer contract explicitly.
    override fun onPrepared(playerId: PlayerId) {
        loading = false
        selectedBitrate = 0L
        reevaluate.set(true)
        delegate.onPrepared(playerId)
    }

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
        // An adaptive selection can switch representations without another track callback.
        // Budget for the highest declared rate in each selected audio/video group.
        var bitrate = 0L
        for (selection in trackSelections) {
            if (selection == null) continue
            val type = selection.trackGroup.type
            if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO) continue
            var highest = 0
            for (index in 0 until selection.length()) {
                val format = selection.getFormat(index)
                highest = maxOf(highest, format.peakBitrate, format.averageBitrate)
            }
            bitrate += highest
        }
        selectedBitrate = bitrate
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

    fun hasCompatibleBackBuffer(options: AnimePlayerOptions): Boolean {
        val current = configuration
        if (options == current.options) return current.nextBackWindowMs == backWindowMs
        val target = acceptedTarget(options.bufferForwardBytes, current.target.toLong(), heapMaximumBytes)
        return projectBackWindowMs(options, target) == backWindowMs
    }

    fun update(options: AnimePlayerOptions) {
        val last = configuration
        val previous = last.options
        val target = acceptedTarget(options.bufferForwardBytes, last.target.toLong(), heapMaximumBytes)
        configuration = Configuration(options, target, projectBackWindowMs(options, target))
        if (previous.bufferMinMs != options.bufferMinMs || previous.bufferMaxMs != options.bufferMaxMs ||
            previous.bufferStartMs != options.bufferStartMs || previous.bufferRebufferMs != options.bufferRebufferMs ||
            previous.bufferForwardBytes != options.bufferForwardBytes ||
            previous.bufferRefillNearTarget != options.bufferRefillNearTarget) reevaluate.set(true)
    }

    fun metrics(sampledAtMillis: Long, isLoading: Boolean? = null, playbackSpeed: Float = 1f): AnimeBufferMetrics {
        val current = configuration
        val options = current.options
        val target = current.target.toLong()
        return AnimeBufferMetrics(
            allocatedBytes = allocator.totalBytesAllocated.toLong(),
            targetForwardBytes = target.takeIf { backWindowMs == 0 },
            targetBackBytes = null,
            sampledAtMillis = sampledAtMillis,
            targetDurationMs = options.bufferMaxMs.toLong(),
            refillBelowMs = refillDurationUs(options, playbackSpeed) / 1_000L,
            isLoading = isLoading,
            detail = buildString {
                append("已分配值为播放器压缩样本内存")
                if (backWindowMs == 0) append("，本次播放未保留回看窗口")
                else {
                    val duration = if (backWindowMs % 1_000 == 0) "${backWindowMs / 1_000} 秒" else "$backWindowMs 毫秒"
                    append("，前后共用 ${target / 1024} KiB 样本目标。本次回看时窗 $duration，按关键帧保留，正播缺数据时可缩短")
                }
                if (options.bufferForwardBytes >= heapMaximumBytes)
                    append("；请求的前方预算达到或超过本进程堆上限 ${heapMaximumBytes / (1024L * 1024L)} MiB，" +
                        "继续使用上次有效或内核默认目标；所设值已保留")
                else if (target != options.bufferForwardBytes)
                    append("；请求的前方预算超过当前引擎整数容量，已按可支持容量执行")
                if (current.nextBackWindowMs != backWindowMs)
                    append("。新回看时窗在下次创建播放器时生效")
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
        val allocated = allocator.totalBytesAllocated
        // Leave room for forward reads before a high-bitrate or long-GOP back buffer
        // fills the shared pool. At low forward runway unlock that reserve. At the real
        // ceiling Media3 can discard the back buffer and reevaluate this same callback.
        val resumeMs = maxOf(options.bufferStartMs, options.bufferRebufferMs, 500).coerceAtMost(options.bufferMaxMs)
        val bufferedPlayoutUs = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        val reserve = if (backWindowMs > 0 && bufferedPlayoutUs >= resumeMs * 2_000L) {
            val byTime = target.toLong() * resumeMs / options.bufferMaxMs
            val byRate = (selectedBitrate.toDouble() * resumeMs / 8_000.0).toLong()
            val bounded = minOf(target / 4L, maxOf(byTime, byRate))
            (bounded / C.DEFAULT_BUFFER_SEGMENT_SIZE * C.DEFAULT_BUFFER_SEGMENT_SIZE).toInt()
        } else 0
        val full = allocated >= target - reserve
        if (reevaluate.getAndSet(false)) loading = true
        val maximumUs = options.bufferMaxMs.toLong() * 1_000L
        val minimumUs = refillDurationUs(options, parameters.playbackSpeed)
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
            (parameters.bufferedDurationUs > 0L && allocator.totalBytesAllocated >= current.target)
    }

    private companion object {
        fun projectBackWindowMs(options: AnimePlayerOptions, target: Int): Int {
            val segment = C.DEFAULT_BUFFER_SEGMENT_SIZE.toLong()
            if (options.bufferBackBytes < segment || target < 2L * segment) return 0
            // Project the byte preference onto the duration API. Keep the startup/rebuffer
            // runway and at least half of the remaining horizon available for forward play.
            // The byte ceiling still applies to the complete allocator, including long GOPs.
            val resumeMs = maxOf(options.bufferStartMs, options.bufferRebufferMs, 500)
            val horizon = (options.bufferMaxMs.toLong() - resumeMs).coerceAtLeast(0L)
            val backShare = minOf(options.bufferBackBytes, target.toLong())
            val projected = horizon * backShare / (target + backShare)
            if (projected < 1_000L) return projected.toInt()
            return minOf(horizon / 2L, (projected + 500L) / 1_000L * 1_000L).toInt()
        }

        fun refillDurationUs(options: AnimePlayerOptions, playbackSpeed: Float): Long {
            val maximumUs = options.bufferMaxMs.toLong() * 1_000L
            val minimumUs = options.effectiveBufferRefillMs.toLong() * 1_000L
            // The near-target band is already bounded by the media-time ceiling. Scaling it
            // to that ceiling would remove its hysteresis at faster playback speeds.
            val speedAdjusted = if (!options.bufferRefillNearTarget && playbackSpeed > 1f)
                Util.getMediaDurationForPlayoutDuration(minimumUs, playbackSpeed) else minimumUs
            return speedAdjusted.coerceAtLeast(500_000L).coerceAtMost(maximumUs)
        }

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
