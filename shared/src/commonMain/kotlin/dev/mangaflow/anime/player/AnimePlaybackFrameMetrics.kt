package dev.mangaflow.anime.player

/** Recent native observations, independent of the counters retained for diagnostic details. */
data class AnimePlaybackFrameMetrics(
    val windowDurationMs: Long,
    /** Wall-clock rate of output-frame release callbacks, not display refresh or physical Present. */
    val outputFps: Double? = null,
    val recentDroppedFrames: Long? = null,
    val recentDropPercent: Double? = null,
    val recentDecoderDroppedFrames: Long? = null,
    val recentOutputDroppedFrames: Long? = null,
)

/** One sampler per native player. Missing or reset counters never become artificial zeroes. */
internal class AnimePlaybackFrameWindow {
    private data class Sample(val at: Long, val output: Long?, val dropped: Long?,
        val decoderDropped: Long?, val outputDropped: Long?)
    private val samples = ArrayDeque<Sample>()
    private var epoch: Any? = null

    fun reset() {
        samples.clear()
        epoch = null
    }

    fun sample(now: Long, epoch: Any, flowing: Boolean, outputFrames: Long? = null,
        droppedFrames: Long? = null, decoderDroppedFrames: Long? = null, outputDroppedFrames: Long? = null,
        comparableDropDenominator: Boolean = false): AnimePlaybackFrameMetrics? {
        if (!flowing) { reset(); return null }
        fun Long?.valid() = this?.takeIf { it >= 0L }
        val current = Sample(now, outputFrames.valid(), droppedFrames.valid(),
            decoderDroppedFrames.valid(), outputDroppedFrames.valid())
        val last = samples.lastOrNull()
        fun resetCounter(before: Long?, after: Long?) = (before == null) != (after == null) ||
            (before != null && after != null && after < before)
        if (this.epoch != epoch || last != null && (now < last.at || now - last.at > 5_000L ||
                resetCounter(last.output, current.output) || resetCounter(last.dropped, current.dropped) ||
                resetCounter(last.decoderDropped, current.decoderDropped) ||
                resetCounter(last.outputDropped, current.outputDropped))) samples.clear()
        this.epoch = epoch
        if (samples.isEmpty() || now - samples.last().at >= 200L) samples.addLast(current)
        while (samples.size > 1 && samples.first().at < now - 5_000L) samples.removeFirst()
        val first = samples.first()
        val duration = now - first.at
        if (duration < 1_000L) return null
        fun delta(before: Long?, after: Long?) = if (before != null && after != null && after >= before)
            after - before else null
        val output = delta(first.output, current.output)
        val dropped = delta(first.dropped, current.dropped)
        val denominator = if (comparableDropDenominator && output != null && dropped != null) output + dropped else null
        return AnimePlaybackFrameMetrics(
            windowDurationMs = duration,
            outputFps = output?.let { it * 1_000.0 / duration },
            recentDroppedFrames = dropped,
            recentDropPercent = if (denominator != null && denominator > 0L) dropped!! * 100.0 / denominator else null,
            recentDecoderDroppedFrames = delta(first.decoderDropped, current.decoderDropped),
            recentOutputDroppedFrames = delta(first.outputDropped, current.outputDropped),
        )
    }
}
