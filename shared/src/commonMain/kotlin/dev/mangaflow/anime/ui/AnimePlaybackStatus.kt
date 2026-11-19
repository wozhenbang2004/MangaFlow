package dev.mangaflow.anime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.AnimeNextEpisodePreparation
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeVideoEnhancementPhase
import dev.mangaflow.enhancement.EnhancementCenter
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.EnhancementMetric
import dev.mangaflow.enhancement.EnhancementPolicy
import dev.mangaflow.enhancement.QualityTier
import dev.mangaflow.enhancement.animeEnhancementSessionKey
import dev.mangaflow.experience.ExperienceResources
import dev.mangaflow.enhancement.ui.EnhancementReadout
import dev.mangaflow.enhancement.ui.EnhancementReadouts
import dev.mangaflow.enhancement.ui.enhancementResourceReadouts
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.platform.monotonicTimeMillis

/** Non-interactive readings stay visible when controls hide; configuration has a separate button. */
@Composable
internal fun AnimePlaybackStatus(
    playback: AnimePlayerState?,
    quiet: Boolean,
    observing: Boolean,
    modifier: Modifier = Modifier,
    nextPreparation: AnimeNextEpisodePreparation? = null,
    nextPreparationEnabled: Boolean = true,
) {
    val context = LocalMangaContext.current
    val center = remember(context) { EnhancementCenter.get(context) }
    val preferences by center.settings.visibleMetrics.collectAsStateWithLifecycle()
    val defaults by center.settings.state.collectAsStateWithLifecycle()
    val sessions by center.settings.sessions.collectAsStateWithLifecycle()
    val telemetry by center.telemetry.state.collectAsStateWithLifecycle()
    val usage by center.resources.usage.collectAsStateWithLifecycle()
    val metrics = preferences.getValue(EnhancementContent.ANIME)
    val current = playback?.takeIf { it.assetKey != null &&
        it.phase !in setOf(AnimePlayerPhase.IDLE, AnimePlayerPhase.RELEASED) }
    val key = current?.let(::animeEnhancementSessionKey)
    val policy = key?.let { sessions[it]?.takeIf { value -> value.content == EnhancementContent.ANIME }?.policy }
        ?: defaults.getValue(EnhancementContent.ANIME)
    val reason = key?.let { telemetry.sessions[it]?.takeIf { value -> value.content == EnhancementContent.ANIME }?.reason }
    val readouts = animeEnhancementReadouts(current, metrics, policy, usage.resources, reason, nextPreparation, nextPreparationEnabled) +
        enhancementResourceReadouts(metrics, center.resources, observing)
    EnhancementReadouts(readouts, quiet, modifier)
}

private fun animeEnhancementReadouts(playback: AnimePlayerState?, metrics: Set<EnhancementMetric>,
    policy: EnhancementPolicy, resources: ExperienceResources, reason: String?,
    nextPreparation: AnimeNextEpisodePreparation?, nextPreparationEnabled: Boolean): List<EnhancementReadout> = buildList {
    val flowing = playback?.let { it.isPlaying && !it.playbackSuppressed &&
        it.hasVideoTracks != false && it.firstFrameRendered != false } == true
    val effect = playback?.videoEnhancement
    val observation = effect?.observation
    val motion = playback?.realtimeMotion
    val activeMotion = motion?.takeIf { flowing && it.phase == AnimeVideoEnhancementPhase.ACTIVE }
    val window = playback?.preparedVideoWindow?.takeIf { it.frames > 0 }
    val usingPrepared = flowing && window != null && window.framesUsed > 0L &&
        monotonicTimeMillis() - window.lastUsedAt in 0L..1_000L
    if (EnhancementMetric.QUALITY in metrics) {
        val quality = when {
            playback == null -> "画质 —"
            playback.preEnhanced -> "预增强片源"
            usingPrepared -> "预处理帧复用"
            else -> when (effect?.phase) {
                AnimeVideoEnhancementPhase.OFF -> when {
                    policy.tier == QualityTier.ORIGINAL -> "原画"
                    !policy.realtimeEnhancementEnabled -> "原画 · 增强关闭"
                    resources.memoryPressure == true || (resources.thermalPressure ?: 0) >= 2 -> "原画 · 设备保护"
                    else -> "原画 · 增强未运行"
                }
                AnimeVideoEnhancementPhase.WAITING, AnimeVideoEnhancementPhase.CONFIGURED -> "增强待运行"
                AnimeVideoEnhancementPhase.ACTIVE -> if (flowing && observation?.recentlySubmitting == true)
                    "增强处理中" else "增强已配置"
                AnimeVideoEnhancementPhase.UNAVAILABLE -> "增强不可用"
                AnimeVideoEnhancementPhase.FAILED -> "增强异常"
                null -> "画质 —"
            }
        }
        add(EnhancementReadout(EnhancementMetric.QUALITY, quality,
            listOfNotNull(quality, effect?.detail ?: reason).joinToString("，")))
    }
    if (EnhancementMetric.DIMENSIONS in metrics) {
        fun dimensions(width: Int?, height: Int?) = if (width != null && width > 0 && height != null && height > 0)
            "$width×$height" else null
        val input = dimensions(playback?.videoWidth, playback?.videoHeight)
        val submitted = if (usingPrepared) dimensions(window?.outputWidth, window?.outputHeight)
            else if (effect?.phase == AnimeVideoEnhancementPhase.ACTIVE && (observation?.submittedFrames ?: 0L) > 0L)
                dimensions(effect?.outputWidth, effect?.outputHeight) else null
        val value = when {
            playback?.preEnhanced == true -> "增强文件 ${input ?: "—"}"
            submitted != null -> "输入 ${input ?: "—"} · 提交 $submitted"
            else -> "输入 ${input ?: "—"}"
        }
        add(EnhancementReadout(EnhancementMetric.DIMENSIONS, value,
            "视频输入尺寸 ${input ?: "无观测"}，已提交增强帧尺寸 ${submitted ?: "无观测"}；提交不代表最终呈现"))
    }
    if (EnhancementMetric.PRESENTED_FPS in metrics) {
        val recent = playback?.frameMetrics?.takeIf { flowing }
        val output = recent?.outputFps?.takeIf { it.isFinite() && it >= 0.0 }
        val presented = playback?.presentedFps?.takeIf { flowing && it.isFinite() && it >= 0.0 }
        val estimate = playback?.nativeVideoStatistics?.estimatedFilterFps?.takeIf { flowing && recent != null }
        val value = when {
            presented != null -> "呈现 ${formatDecimal(presented, 1)} fps"
            output != null -> "视频输出 ${formatDecimal(output, 1)} fps"
            estimate != null -> "滤镜估计 ${formatDecimal(estimate, 1)} fps"
            playback == null -> "帧率 —"
            playback.isBuffering -> "帧率 · 缓冲中"
            !flowing -> "帧率 · 已暂停"
            recent == null -> "帧率 · 采样中"
            else -> "输出帧率未提供"
        }
        val description = when {
            presented != null -> "播放器实际呈现帧率 ${formatDecimal(presented, 1)} 帧每秒"
            output != null -> "最近 ${formatDecimal(checkNotNull(recent).windowDurationMs / 1000.0, 1)} 秒视频输出帧释放回调实测；" +
                "包含处理后输出，不等于屏幕实际呈现或屏幕刷新率"
            estimate != null -> "播放器根据最近视频帧时间戳估计的滤镜输出帧率；丢帧时可能不准确，不等于当前屏幕帧率"
            else -> value
        }
        add(EnhancementReadout(EnhancementMetric.PRESENTED_FPS, value, description))
    }
    if (EnhancementMetric.DROPPED_FRAMES in metrics) {
        fun count(value: Long?) = value?.takeIf { it >= 0L }?.toString() ?: "—"
        val recent = playback?.frameMetrics?.takeIf { flowing }
        val duration = recent?.windowDurationMs?.let { if (it >= 4_500L) "近5秒" else "近${formatDecimal(it / 1000.0, 1)}秒" }
        val durationLabel = duration ?: "近期"
        val percent = recent?.recentDropPercent?.let { " · ${formatDecimal(it, 1)}%" }.orEmpty()
        val value = when {
            playback == null -> "丢帧 —"
            !flowing -> "丢帧 · 待播放"
            recent == null -> "丢帧 · 采样中"
            playback.nativeVideoStatistics != null ->
                "${durationLabel}丢帧 解码 ${count(recent.recentDecoderDroppedFrames)} · 输出 ${count(recent.recentOutputDroppedFrames)}"
            else -> "${durationLabel}丢帧 ${count(recent.recentDroppedFrames)}$percent"
        }
        add(EnhancementReadout(EnhancementMetric.DROPPED_FRAMES, value,
            "$value；滚动窗口最长5秒，暂停、定位与处理档位变化后重新采样。" +
                "仅已观测的丢帧与输出分母可比时显示比例，补帧时不混用输入/输出分母；累计计数见状态详情"))
    }
    if (EnhancementMetric.MOTION_STATUS in metrics) {
        val value = when {
            playback == null -> "补帧 —"
            !policy.realtimeInterpolationEnabled -> "补帧已关闭"
            motion?.phase == AnimeVideoEnhancementPhase.FAILED || motion?.phase == AnimeVideoEnhancementPhase.UNAVAILABLE ->
                motionProblemLabel(motion?.detail)
            motion?.phase == AnimeVideoEnhancementPhase.ACTIVE && flowing && motion.synthesizedFrames > 0L -> "补帧处理中"
            motion?.phase == AnimeVideoEnhancementPhase.WAITING && motion.detail?.contains("等待片源帧率") == true -> "补帧 · 等待源帧率"
            motion?.phase == AnimeVideoEnhancementPhase.CONFIGURED -> "补帧 · 等待输出"
            motion != null && motion.phase != AnimeVideoEnhancementPhase.OFF -> "补帧待运行"
            else -> "未实时补帧"
        }
        add(EnhancementReadout(EnhancementMetric.MOTION_STATUS, value,
            listOfNotNull(value, motion?.detail).joinToString("，")))
    }
    if (EnhancementMetric.MOTION_THROUGHPUT in metrics) {
        val fps = activeMotion?.generationFps?.takeIf { it.isFinite() && it >= 0.0 }
        val value = fps?.let { formatDecimal(it, 1) + " 帧/s" } ?: "—"
        add(EnhancementReadout(EnhancementMetric.MOTION_THROUGHPUT, "补帧处理 $value",
            "补帧处理吞吐 $value，不是屏幕呈现帧率"))
    }
    if (EnhancementMetric.MOTION_LATENCY in metrics) {
        val elapsed = activeMotion?.inferenceNanos?.takeIf { it >= 0L }
        val value = elapsed?.let { formatDecimal(it / 1_000_000.0, 1) + " ms" } ?: "—"
        add(EnhancementReadout(EnhancementMetric.MOTION_LATENCY, "补帧 $value", "单次补帧处理耗时 $value"))
    }
    if (EnhancementMetric.GPU_TIME in metrics) {
        val elapsed = observation?.takeIf { flowing && effect?.phase == AnimeVideoEnhancementPhase.ACTIVE && it.recentlySubmitting }
            ?.gpuExecutionNanos?.takeIf { it >= 0L }
        val value = elapsed?.let { formatDecimal(it / 1_000_000.0, 1) + " ms" } ?: "—"
        add(EnhancementReadout(EnhancementMetric.GPU_TIME, "增强 GPU $value", "增强 GPU 实际采样耗时 $value"))
    }
    if (EnhancementMetric.BUFFER in metrics) {
        val buffer = playback?.bufferedAheadMs?.takeIf { it >= 0L }
        val value = buffer?.let { formatDecimal(it / 1000.0, 1) + " s" } ?: "—"
        val target = playback?.bufferMetrics?.targetDurationMs?.let { " / ${formatDecimal(it / 1000.0, 0)} s" }.orEmpty()
        val loading = if (playback?.bufferMetrics?.isLoading == true) " · 补充中" else ""
        val refill = playback?.bufferMetrics?.refillBelowMs?.let { "；低于 ${formatDecimal(it / 1000.0, 1)} 秒继续补充" }.orEmpty()
        add(EnhancementReadout(EnhancementMetric.BUFFER, "缓冲 $value$target$loading",
            "播放点前方已连续缓冲 $value，目标${target.removePrefix(" / ").ifBlank { "未提供" }}$refill；" +
                "按媒体分段补充，仍受网络、容量与片尾限制；不是磁盘缓存总量"))
    }
    if (EnhancementMetric.BUFFER_BYTES in metrics) {
        val bytes = playback?.bufferMetrics?.allocatedBytes
        add(EnhancementReadout(EnhancementMetric.BUFFER_BYTES, "缓冲占用 ${bytes?.let(::animeBytes) ?: "—"}",
            listOfNotNull("播放器已分配的压缩缓冲 ${bytes?.let(::animeBytes) ?: "无观测"}，不代表磁盘缓存或解码帧队列",
                playback?.bufferMetrics?.detail).joinToString("，")))
    }
    if (EnhancementMetric.NEXT_EPISODE in metrics) {
        val value = when {
            playback == null -> "下一集 —"
            !nextPreparationEnabled -> "下一集准备关闭"
            nextPreparation != null -> "下一集 · ${animePreparationLabel(nextPreparation)}"
            else -> "下一集待准备"
        }
        add(EnhancementReadout(EnhancementMetric.NEXT_EPISODE, value,
            listOfNotNull(value, nextPreparation?.detail).joinToString("，")))
    }
    if (EnhancementMetric.PREPARED in metrics) {
        val prepared = if (playback != null && window != null)
            (window.endMs - maxOf(window.startMs, playback.positionMs)).coerceAtLeast(0L) else null
        val value = when {
            playback == null -> "已备 —"
            prepared != null -> "已备 ${formatDecimal(prepared / 1000.0, 1)} s"
            playback.preparedVideoIssue != null -> "预处理暂不可用"
            else -> "未备片段"
        }
        add(EnhancementReadout(EnhancementMetric.PREPARED, value,
            listOfNotNull("前方预处理片段，$value；与播放缓冲分别计量", playback?.preparedVideoIssue).joinToString("，")))
    }
}

private fun motionProblemLabel(detail: String?): String = "补帧 · " + when {
    detail.isNullOrBlank() -> "暂不可用"
    detail.contains("1080p") -> "输入超过 1080p"
    detail.contains("纹理尺寸") -> "超出纹理上限"
    detail.contains("资源不足") -> "资源暂不足"
    detail.contains("推理超过帧预算") -> "超出帧预算"
    detail.contains("HDR") -> "HDR 暂不支持"
    detail.contains("屏幕刷新率") -> "等待屏幕信息"
    detail.contains("源帧率的目标") -> "目标或源帧率不支持"
    detail.contains("实际帧间距已达到目标") -> "已达目标帧率"
    else -> detail.substringBefore('，').take(24)
}
