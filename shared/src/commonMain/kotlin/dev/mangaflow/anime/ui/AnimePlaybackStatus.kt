package dev.mangaflow.anime.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeVideoEnhancementPhase
import dev.mangaflow.enhancement.EnhancementCenter
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.EnhancementMetric
import dev.mangaflow.enhancement.EnhancementPolicy
import dev.mangaflow.enhancement.PreparationMode
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
    val readouts = animeEnhancementReadouts(current, metrics, policy, usage.resources, reason) +
        enhancementResourceReadouts(metrics, center.resources, observing)
    EnhancementReadouts(readouts, quiet, modifier)
}

private fun animeEnhancementReadouts(playback: AnimePlayerState?, metrics: Set<EnhancementMetric>,
    policy: EnhancementPolicy, resources: ExperienceResources, reason: String?): List<EnhancementReadout> = buildList {
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
                    PreparationMode.IMMEDIATE !in policy.preparation -> "原画 · 即时关闭"
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
            submitted != null -> "提交 $submitted"
            else -> "输入 ${input ?: "—"}"
        }
        add(EnhancementReadout(EnhancementMetric.DIMENSIONS, value,
            "视频输入尺寸 ${input ?: "无观测"}，已提交增强帧尺寸 ${submitted ?: "无观测"}；提交不代表最终呈现"))
    }
    if (EnhancementMetric.PRESENTED_FPS in metrics) {
        val fps = playback?.presentedFps?.takeIf { flowing && it.isFinite() && it >= 0.0 }
        val value = fps?.let { "呈现 ${formatDecimal(it, 1)} fps" } ?: "呈现 —"
        add(EnhancementReadout(EnhancementMetric.PRESENTED_FPS, value,
            if (fps != null) "播放器实际呈现帧率 ${formatDecimal(fps, 1)} 帧每秒"
            else if (!flowing && playback != null) "播放未在进行，暂不显示实时呈现帧率"
            else "播放器尚无实际呈现帧率观测"))
    }
    if (EnhancementMetric.MOTION_STATUS in metrics) {
        val value = when {
            playback == null -> "补帧 —"
            motion?.phase == AnimeVideoEnhancementPhase.FAILED -> "补帧异常"
            motion?.phase == AnimeVideoEnhancementPhase.UNAVAILABLE -> "补帧不可用"
            motion?.phase == AnimeVideoEnhancementPhase.ACTIVE && flowing && motion.synthesizedFrames > 0L -> "补帧处理中"
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
        add(EnhancementReadout(EnhancementMetric.BUFFER, "缓冲 $value", "播放器前方连续缓冲 $value"))
    }
    if (EnhancementMetric.PREPARED in metrics) {
        val prepared = if (playback != null && window != null)
            (window.endMs - maxOf(window.startMs, playback.positionMs)).coerceAtLeast(0L) else null
        val value = when {
            playback == null -> "已备 —"
            prepared != null -> "已备 ${formatDecimal(prepared / 1000.0, 1)} s"
            playback.preparedVideoIssue != null -> "准备异常"
            else -> "未备片段"
        }
        add(EnhancementReadout(EnhancementMetric.PREPARED, value,
            listOfNotNull("前方预处理片段，$value；与播放缓冲分别计量", playback?.preparedVideoIssue).joinToString("，")))
    }
}
