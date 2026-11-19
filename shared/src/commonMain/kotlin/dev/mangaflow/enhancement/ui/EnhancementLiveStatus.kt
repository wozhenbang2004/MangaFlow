package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeVideoEnhancementMode
import dev.mangaflow.anime.player.AnimeVideoEnhancementPhase
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.EnhancementSessionMetrics
import dev.mangaflow.enhancement.animeEnhancementSessionKey
import dev.mangaflow.experience.ExperienceResources
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTextButton

@Composable
internal fun EnhancementLiveStatus(
    content: EnhancementContent,
    sample: EnhancementSessionMetrics?,
    playback: AnimePlayerState?,
    resources: ExperienceResources,
    queued: Int,
    onOpenTasks: () -> Unit,
    showHeading: Boolean = true,
    animeScope: Int? = null,
    onAnimeScopeChange: ((Int) -> Unit)? = null,
) {
    if (content == EnhancementContent.ANIME) {
        AnimeEnhancementStatus(sample, playback, resources, queued, onOpenTasks, showHeading,
            animeScope, onAnimeScopeChange)
        return
    }
    var expanded by remember(content, sample?.sessionKey) { mutableStateOf(false) }
    val actual = when (sample?.currentEnhanced) {
        true -> "当前页已增强"
        false -> "当前页原图"
        null -> "当前页等待观测"
    }
    val summary = actual + " · " + when (sample?.nextReady) {
        true -> "下一页已备好"
        false -> "下一页未备好"
        null -> "下一页待观测"
    }
    EnhancementSection("运行状态", Icons.Outlined.Speed, summary,
        action = { EnhancementDetailToggle(expanded, { expanded = !expanded }) }) {
        MotionTextButton(onOpenTasks, contentPadding = PaddingValues(horizontal = 0.dp)) {
            Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(if (queued > 0) "$queued 项任务" else "任务记录", style = MaterialTheme.typography.labelMedium)
        }
        sample?.reason?.let { EnhancementQuiet(it) }
        if (expanded) {
            EnhancementValue("整页增强 P50 / P95", millis(sample?.imageP50Millis) + " / " + millis(sample?.imageP95Millis),
                "整页实际耗时，包含引擎处理与输出；与 GPU 执行耗时分开。")
            EnhancementValue("处理页样本", sample?.imageSamples?.toString() ?: "不可用")
            EnhancementValue("模型 / 输出尺寸", (sample?.model ?: "不可用") + " / " +
                (dimensions(sample?.outputWidth, sample?.outputHeight) ?: "不可用"))
            EnhancementValue("GPU P50 / P95", millis(sample?.gpuP50Millis) + " / " + millis(sample?.gpuP95Millis))
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

private fun millis(value: Double?): String = value?.let { formatDecimal(it, 2) + " ms" } ?: "不可用"
private fun percent(value: Double?): String = value?.let { formatDecimal(it * 100, 1) + "%" } ?: "不可用"
private fun dimensions(width: Int?, height: Int?): String? =
    if (width != null && height != null) "$width × $height" else null

/** Keep observed playback, renderer submissions and device readings in separate scopes. */
@Composable
private fun AnimeEnhancementStatus(
    sample: EnhancementSessionMetrics?,
    playback: AnimePlayerState?,
    resources: ExperienceResources,
    queued: Int,
    onOpenTasks: () -> Unit,
    showHeading: Boolean,
    animeScope: Int?,
    onAnimeScopeChange: ((Int) -> Unit)?,
) {
    var localScope by remember(playback?.assetKey, playback?.sessionGeneration) { mutableIntStateOf(0) }
    val scope = (animeScope ?: localScope).coerceIn(0, 2)
    val hasSession = playback?.assetKey != null && playback.phase !in setOf(AnimePlayerPhase.IDLE, AnimePlayerPhase.RELEASED)
    val metrics = sample?.takeIf {
        hasSession && playback != null && it.content == EnhancementContent.ANIME &&
            it.sessionKey == animeEnhancementSessionKey(playback)
    }
    val effect = playback?.videoEnhancement.takeIf { hasSession }
    val observation = effect?.observation
    val playing = hasSession && playback?.isPlaying == true && !playback.isBuffering && !playback.playbackSuppressed &&
        playback.hasVideoTracks != false && playback.firstFrameRendered != false
    val recentlySubmitting = playing && observation?.recentlySubmitting == true
    val stateLabel = when {
        playback?.phase == AnimePlayerPhase.ERROR -> "播放未成功"
        playback?.phase == AnimePlayerPhase.OPENING -> "正在打开视频"
        !hasSession -> "未开始播放"
        playback?.phase == AnimePlayerPhase.ENDED -> "本集已结束"
        playback?.playbackSuppressed == true -> "系统暂缓播放"
        playback?.playWhenReady == false -> "已暂停"
        playback?.isBuffering == true -> "正在缓冲"
        playback?.firstFrameRendered == false && playback.hasVideoTracks != false -> "等待首帧"
        playback?.hasVideoTracks == false -> "正在播放音轨"
        playing -> "播放中"
        else -> "等待播放状态"
    }
    val processingLabel = when {
        !hasSession -> "打开视频后显示本集数据，设备读数仍可查看。"
        playback?.preEnhanced == true -> "已选择离线增强版本"
        effect?.phase == AnimeVideoEnhancementPhase.FAILED -> "增强未成功 · 原因见处理页"
        effect?.phase == AnimeVideoEnhancementPhase.UNAVAILABLE -> "当前增强不可用 · 原因见处理页"
        effect?.mode == AnimeVideoEnhancementMode.OFF -> "当前使用原画设置"
        recentlySubmitting -> "正在提交增强帧"
        effect?.phase == AnimeVideoEnhancementPhase.ACTIVE -> "已收到增强提交样本"
        effect?.phase == AnimeVideoEnhancementPhase.CONFIGURED -> "增强已配置 · 等待处理样本"
        else -> "等待增强处理样本"
    }
    val currentFps = playback?.presentedFps.takeIf { playing }
    val inputSize = dimensions(playback?.videoWidth, playback?.videoHeight).takeIf { hasSession }
    val submittedSize = if (effect?.phase == AnimeVideoEnhancementPhase.ACTIVE && (observation?.submittedFrames ?: 0) > 0)
        dimensions(effect?.outputWidth, effect?.outputHeight) else null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showHeading) Text("运行状态", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(when {
                playback?.phase == AnimePlayerPhase.ERROR -> Icons.Outlined.ErrorOutline
                !hasSession -> Icons.Outlined.PlayCircleOutline
                playback?.playWhenReady == false -> Icons.Outlined.Pause
                playing -> Icons.Outlined.PlayCircleOutline
                else -> Icons.Outlined.Schedule
            }, null, Modifier.padding(top = 2.dp).size(20.dp),
                tint = if (playback?.phase == AnimePlayerPhase.ERROR) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stateLabel, style = MaterialTheme.typography.titleSmall)
                EnhancementQuiet(processingLabel)
            }
        }
        MotionTabs(scope, listOf("播放", "处理", "设备"), onSelect = {
            localScope = it
            onAnimeScopeChange?.invoke(it)
        },
            modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp, treatment = MotionTabTreatment.Soft)
        when (scope) {
            0 -> {
                AnimeMetricGrid(listOf(
                    (if (playback?.preEnhanced == true) "增强文件帧率" else "源视频帧率") to
                        animeFps(playback?.sourceFps.takeIf { hasSession }),
                    "目标帧率" to animeFps(metrics?.targetFps),
                    "实际呈现" to animeFps(currentFps),
                    "前方缓冲" to animeSeconds(playback?.bufferedAheadMs?.takeIf { hasSession && it >= 0 }?.div(1000.0)),
                ))
                EnhancementDivider()
                AnimeMetricRow(if (playback?.preEnhanced == true) "增强文件尺寸" else "视频输入尺寸", inputSize ?: "—")
                if (playback?.preEnhanced != true) AnimeMetricRow("已提交帧尺寸", submittedSize ?: "—")
                AnimeMetricRow("已呈现帧 · 累计", animeCount(playback?.presentedFrames.takeIf { hasSession }))
                AnimeMetricRow("丢帧 / 重复帧 · 累计", animeCount(playback?.droppedFrames.takeIf { hasSession }) +
                    " / " + animeCount(metrics?.repeatedFrames))
                AnimeMetricRow("接收速度", playback?.downloadBytesPerSecond?.takeIf { hasSession && it >= 0 }
                    ?.let { enhancementBytes(it) + "/s" } ?: "—")
                EnhancementQuiet(when {
                    !hasSession -> "尚无本集数据。— 表示未采集，不代表数值为零。"
                    !playing -> "播放未在进行，实时呈现帧率暂不显示；已收到的累计数据保留。"
                    currentFps == null -> "播放器未提供实际呈现帧率；提交速度和目标帧率不能替代它。"
                    else -> "目标帧率是设置；实际呈现只使用播放器回报。— 表示未采集。"
                })
                if (playback?.phase == AnimePlayerPhase.ERROR) playback.error?.takeIf { it.isNotBlank() }
                    ?.let { EnhancementQuiet(it, error = true) }
            }
            1 -> {
                val model = if (playback?.preEnhanced == true) "离线增强版本" else when (effect?.mode) {
                    AnimeVideoEnhancementMode.OFF -> "原画"
                    AnimeVideoEnhancementMode.FAST -> "Anime4K S/S"
                    AnimeVideoEnhancementMode.QUALITY -> "Anime4K M/M"
                    null -> "—"
                }
                AnimeMetricRow(if (playback?.preEnhanced == true) "当前版本" else "已配置模型", model)
                val duration = observation?.sampleDurationMillis
                val frames = observation?.sampleFrames
                val submitFps = if (recentlySubmitting && duration != null && duration > 0 && frames != null)
                    frames * 1000.0 / duration else null
                AnimeMetricGrid(listOf(
                    "提交频率" to (submitFps?.let { formatDecimal(it, 1) + " 帧/s" } ?: "—"),
                    "前方增强" to animeSeconds(metrics?.preparedSeconds),
                ))
                EnhancementQuiet("提交频率不等于 GPU 完成或屏幕呈现帧率；提交尺寸对应处理缓冲区，不代表屏幕呈现分辨率。")
                EnhancementDivider()
                AnimeMetricRow("提交 / GPU 完成 / 呈现 · 累计",
                    animeCount(observation?.submittedFrames ?: metrics?.submittedFrames) + " / " +
                        animeCount(metrics?.completedFrames) + " / " + animeCount(playback?.presentedFrames.takeIf { hasSession }))
                AnimeMetricRow("GPU 处理 P50 / P95", animeMillis(metrics?.gpuP50Millis) + " / " + animeMillis(metrics?.gpuP95Millis))
                AnimeMetricRow("CPU 提交 P50 / P95", animeMillis(metrics?.submitCpuP50Millis) + " / " + animeMillis(metrics?.submitCpuP95Millis))
                AnimeMetricRow("最近提交线程 CPU", animeMillis(observation?.submitThreadCpuNanos?.div(1_000_000.0)))
                AnimeMetricRow("最近 GPU 执行", animeMillis(observation?.gpuExecutionNanos?.div(1_000_000.0)))
                val submittedAt = observation?.gpuSubmittedAtNanos
                val completedAt = observation?.gpuCompletionObservedAtNanos
                AnimeMetricRow("提交到完成观测", animeMillis(if (submittedAt != null && completedAt != null && completedAt >= submittedAt)
                    (completedAt - submittedAt) / 1_000_000.0 else null))
                AnimeMetricRow("最终呈现回执", if (observation?.presentedAtNanos != null) "已收到" else "—")
                AnimeMetricRow("中间纹理估算", observation?.intermediateTextureBytes?.let(::enhancementBytes) ?: "—")
                EnhancementQuiet("耗时为本次会话已收到的样本。完成观测包含调度延迟；纹理大小是估算，不是实测显存。")
                EnhancementDivider()
                AnimeMetricRow("补帧生成 / 失败或跳过", animeCount(metrics?.generatedFrames) + " / " + animeCount(metrics?.skippedFrames))
                AnimeMetricRow("补帧生成速度", metrics?.generationFps?.let { formatDecimal(it, 1) + " 帧/s" } ?: "—")
                AnimeMetricRow("增强缓存命中", metrics?.let { metric -> metric.cacheHitRatio?.let {
                    formatDecimal(it * 100, 1) + "% · ${metric.cacheHits}/${metric.cacheLookups}"
                } } ?: "—")
                listOfNotNull(metrics?.reason, effect?.detail).filter { it.isNotBlank() }.distinct().forEach { EnhancementQuiet(it) }
                metrics?.adjustments?.takeLast(5)?.takeIf { it.isNotEmpty() }?.let { adjustments ->
                    EnhancementDivider()
                    Text("自动调整记录", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics { heading() })
                    adjustments.forEach { EnhancementQuiet(it) }
                }
            }
            else -> {
                AnimeMetricGrid(listOf(
                    "进程 CPU" to animePercent(resources.processCpuLoad),
                    "系统 CPU" to animePercent(resources.cpuLoad),
                    "进程驻留内存" to (resources.processResidentBytes?.let(::enhancementBytes) ?: "—"),
                    "系统可用内存" to (resources.availableMemoryBytes?.let(::enhancementBytes) ?: "—"),
                ))
                EnhancementDivider()
                AnimeMetricRow("进程堆 / 上限", (resources.processHeapBytes?.let(::enhancementBytes) ?: "—") +
                    " / " + (resources.processHeapLimitBytes?.let(::enhancementBytes) ?: "—"))
                AnimeMetricRow("热压力", resources.thermalPressure?.takeIf { it in 0..3 }?.let(::thermalLabel) ?: "—")
                AnimeMetricRow("GPU 负载 / 显存", "未采集")
                resources.sampledAtMillis?.let { time ->
                    EnhancementQuiet("设备采样于 ${formatDecimal((monotonicTimeMillis() - time).coerceAtLeast(0) / 1000.0, 1)} 秒前。")
                } ?: EnhancementQuiet("设备尚未提供采样；— 不代表资源占用为零。")
            }
        }
        EnhancementDivider()
        MotionTextButton(onOpenTasks, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
            Icon(Icons.Outlined.Layers, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("增强任务", style = MaterialTheme.typography.bodyMedium)
                Text(if (queued > 0) "全部番剧 · $queued 项未完成" else "查看全部番剧记录", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
        }
    }
}

/** A shared numeric axis without a separate card for every reading. */
@Composable
private fun AnimeMetricGrid(values: List<Pair<String, String>>) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 280.dp * fontScale) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            values.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { (name, value) ->
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {},
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AnimeMetricRow(name: String, value: String) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        if (maxWidth < 320.dp * fontScale) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.weight(1.15f), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                textAlign = TextAlign.End)
        }
    }
}

private fun animeFps(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0 }?.let { formatDecimal(it, 1) + " FPS" } ?: "—"
private fun animeSeconds(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0 }?.let { formatDecimal(it, 1) + " 秒" } ?: "—"
private fun animeMillis(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0 }?.let { formatDecimal(it, 2) + " ms" } ?: "—"
private fun animeCount(value: Long?): String = value?.takeIf { it >= 0 }?.toString() ?: "—"
private fun animePercent(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0 }?.let { formatDecimal(it * 100, 1) + "%" } ?: "—"
