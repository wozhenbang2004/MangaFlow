package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeVideoEnhancementMode
import dev.mangaflow.anime.player.AnimeVideoEnhancementPhase
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.EnhancementSessionMetrics
import dev.mangaflow.experience.ExperienceResources
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.ui.motion.MotionTextButton

@Composable
internal fun EnhancementLiveStatus(
    content: EnhancementContent,
    sample: EnhancementSessionMetrics?,
    playback: AnimePlayerState?,
    resources: ExperienceResources,
    queued: Int,
    onOpenTasks: () -> Unit,
) {
    var expanded by remember(content, sample?.sessionKey) { mutableStateOf(false) }
    val anime = content == EnhancementContent.ANIME
    val observation = playback?.videoEnhancement?.observation
    val phase = playback?.videoEnhancement?.phase
    val actual = when {
        !anime -> when (sample?.currentEnhanced) { true -> "当前页已增强"; false -> "当前页原图"; null -> "当前页等待观测" }
        playback == null -> "未打开播放会话"
        playback.preEnhanced -> "正在播放离线增强版"
        phase == AnimeVideoEnhancementPhase.FAILED -> "增强失败"
        phase == AnimeVideoEnhancementPhase.UNAVAILABLE -> "增强不可用"
        phase == AnimeVideoEnhancementPhase.OFF -> "正在使用原画"
        observation?.recentlySubmitting == true && playback.isPlaying && !playback.playbackSuppressed -> "正在提交增强帧"
        phase == AnimeVideoEnhancementPhase.ACTIVE -> "已观测增强提交"
        phase == AnimeVideoEnhancementPhase.CONFIGURED -> "已配置 · 等待处理观测"
        else -> "等待视频画面"
    }
    val summary = if (anime) {
        "${fps(sample?.sourceFps)} → ${fps(sample?.presentedFps)} · " +
            (dimensions(sample?.outputWidth, sample?.outputHeight) ?: "尺寸不可用")
    } else actual + " · " + when (sample?.nextReady) {
        true -> "下一页已备好"
        false -> "下一页未备好"
        null -> "下一页待观测"
    }
    EnhancementSection("运行状态", Icons.Outlined.Speed, summary,
        action = { EnhancementDetailToggle(expanded, { expanded = !expanded }) }) {
        if (anime) EnhancementQuiet(actual + " · 前方增强 " + seconds(sample?.preparedSeconds))
        MotionTextButton(onOpenTasks, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (queued > 0) "$queued 项任务" else "任务记录", style = MaterialTheme.typography.labelMedium)
        }
        sample?.reason?.let { EnhancementQuiet(it) }
        if (expanded) {
            if (anime) {
                EnhancementValue(if (playback?.preEnhanced == true) "增强文件 / 目标 FPS" else "原始 / 目标 FPS",
                    fps(sample?.sourceFps) + " / " + fps(sample?.targetFps))
                EnhancementValue("实际呈现 FPS", fps(sample?.presentedFps))
                EnhancementValue("丢帧 / 重复帧", count(sample?.droppedFrames) + " / " + count(sample?.repeatedFrames))
                EnhancementDivider()
                EnhancementValue("实际画质模型", if (playback?.preEnhanced == true) "已完成的离线增强版本" else
                    sample?.model ?: playback?.videoEnhancement?.mode?.let { mode -> when (mode) {
                    AnimeVideoEnhancementMode.OFF -> "原画"
                    AnimeVideoEnhancementMode.FAST -> "Anime4K S/S"
                    AnimeVideoEnhancementMode.QUALITY -> "Anime4K M/M"
                } } ?: "不可用")
                EnhancementValue("提交 / GPU完成 / 呈现帧", count(sample?.submittedFrames ?: observation?.submittedFrames) +
                    " / " + count(sample?.completedFrames) + " / " + count(sample?.presentedFrames))
                EnhancementValue("GPU P50 / P95", millis(sample?.gpuP50Millis) + " / " + millis(sample?.gpuP95Millis))
                EnhancementValue("CPU提交 P50 / P95", millis(sample?.submitCpuP50Millis) + " / " + millis(sample?.submitCpuP95Millis))
                if (observation != null) {
                    val duration = observation.sampleDurationMillis
                    val frames = observation.sampleFrames
                    if (duration != null && duration > 0 && frames != null && observation.recentlySubmitting)
                        EnhancementValue("提交吞吐", formatDecimal(frames * 1000.0 / duration, 1) + " 帧/秒",
                            "只统计提交，不代表 GPU 已完成或实际显示帧率。")
                    observation.submitThreadCpuNanos?.let { EnhancementValue("最近一帧提交线程 CPU", millis(it / 1_000_000.0)) }
                    observation.gpuExecutionNanos?.let { EnhancementValue("最近一次 GPU 执行", millis(it / 1_000_000.0)) }
                    val submittedAt = observation.gpuSubmittedAtNanos
                    val completedAt = observation.gpuCompletionObservedAtNanos
                    if (submittedAt != null && completedAt != null && completedAt >= submittedAt)
                        EnhancementValue("提交到完成观测", millis((completedAt - submittedAt) / 1_000_000.0),
                            "包含调度与回读延迟，不能当作 GPU 执行耗时。")
                    EnhancementValue("最终呈现回执", if (observation.presentedAtNanos != null) "已收到" else "不可用")
                    EnhancementValue("中间纹理估算", enhancementBytes(observation.intermediateTextureBytes),
                        "仅当前 Anime4K 中间纹理，不是实测显存占用。")
                }
                EnhancementDivider()
                EnhancementValue("补帧生成 / 失败或跳过", count(sample?.generatedFrames) + " / " + count(sample?.skippedFrames))
                EnhancementValue("补帧生成速度", sample?.generationFps?.let { formatDecimal(it, 1) + " 帧/秒" } ?: "不可用")
                EnhancementValue("前方增强窗口", seconds(sample?.preparedSeconds))
            } else {
                EnhancementValue("整页增强 P50 / P95", millis(sample?.imageP50Millis) + " / " + millis(sample?.imageP95Millis),
                    "整页实际耗时，包含引擎处理与输出；与 GPU 执行耗时分开。")
                EnhancementValue("处理页样本", sample?.imageSamples?.toString() ?: "不可用")
                EnhancementValue("模型 / 输出尺寸", (sample?.model ?: "不可用") + " / " +
                    (dimensions(sample?.outputWidth, sample?.outputHeight) ?: "不可用"))
                EnhancementValue("GPU P50 / P95", millis(sample?.gpuP50Millis) + " / " + millis(sample?.gpuP95Millis))
            }
            EnhancementValue("增强缓存命中", sample?.let { metric -> metric.cacheHitRatio?.let {
                formatDecimal(it * 100, 1) + "% · ${metric.cacheHits}/${metric.cacheLookups}"
            } } ?: "尚无查询样本")
            EnhancementDivider()
            EnhancementValue("进程 CPU / 系统 CPU", percent(resources.processCpuLoad) + " / " + percent(resources.cpuLoad))
            EnhancementValue("GPU 负载 / 显存", "不可用 / 不可用")
            EnhancementValue("进程驻留内存", enhancementBytes(resources.processResidentBytes))
            EnhancementValue("进程堆 / 上限", enhancementBytes(resources.processHeapBytes) + " / " + enhancementBytes(resources.processHeapLimitBytes))
            EnhancementValue("系统可用内存", enhancementBytes(resources.availableMemoryBytes))
            EnhancementValue("热压力", thermalLabel(resources.thermalPressure))
            resources.sampledAtMillis?.let { time ->
                EnhancementQuiet("设备数据距今 ${formatDecimal((monotonicTimeMillis() - time).coerceAtLeast(0) / 1000.0, 1)} 秒")
            } ?: EnhancementQuiet("设备尚未提供采样。")
            sample?.adjustments?.takeLast(5)?.takeIf { it.isNotEmpty() }?.let { adjustments ->
                EnhancementDivider()
                Text("自动调整记录", style = MaterialTheme.typography.labelLarge)
                adjustments.forEach { EnhancementQuiet(it) }
            }
            playback?.videoEnhancement?.detail?.let { EnhancementQuiet(it) }
        }
    }
}

private fun fps(value: Double?): String = value?.let { formatDecimal(it, 1) + " FPS" } ?: "不可用"
private fun seconds(value: Double?): String = value?.let { formatDecimal(it, 1) + " 秒" } ?: "不可用"
private fun millis(value: Double?): String = value?.let { formatDecimal(it, 2) + " ms" } ?: "不可用"
private fun count(value: Long?): String = value?.toString() ?: "不可用"
private fun percent(value: Double?): String = value?.let { formatDecimal(it * 100, 1) + "%" } ?: "不可用"
private fun dimensions(width: Int?, height: Int?): String? =
    if (width != null && height != null) "$width × $height" else null
