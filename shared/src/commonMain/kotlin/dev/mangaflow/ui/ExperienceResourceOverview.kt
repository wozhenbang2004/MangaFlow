package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mangaflow.experience.*
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionBusyIndicator
import kotlin.math.roundToInt

/** Adapts the reader's CacheWorkRow/CacheOccupancy into this same retained settings surface. */
@Composable
internal fun ExperienceResourceOverview(usage: ExperienceUsage, appliedBudget: ExperienceBudget,
    previewBudget: ExperienceBudget, draft: Boolean, enabled: Boolean, editing: Boolean = false,
    showDraftImpact: Boolean = true,
    onOpen: (String) -> Unit) {
    val forecast = remember(appliedBudget, usage) { estimateExperienceResources(appliedBudget, usage) }
    val target = remember(previewBudget, usage, draft, forecast) {
        if (draft) estimateExperienceResources(previewBudget, usage) else forecast
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ResourceMetricRow(Icons.Outlined.Memory, "实测",
            resourceBytes(forecast.currentProcessBytes),
            processMemoryLabel(forecast.processMemoryKind), enabled,
            "当前应用占用 ${resourceBytes(forecast.currentProcessBytes)}，本次同口径观测峰值 ${resourceBytes(forecast.observedProcessPeakBytes)}") { onOpen("resources") }
        if (draft && showDraftImpact) MotionTextButton(onClick = { onOpen("resources") }, enabled = enabled,
            contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Box(Modifier.weight(1f)) { ExperienceDraftImpact(usage, appliedBudget, previewBudget, editing) }
        } else if (!draft) ResourceMetricRow(Icons.Outlined.Calculate, "≈",
            resourceRange(forecast.estimatedProcessPeakLowBytes, forecast.estimatedProcessPeakHighBytes),
            if (forecast.estimatedProcessPeakHighBytes != null) "峰值" else if (forecast.decodeSampleCount == 0) "待样本" else "未知",
            enabled, "配置情景估算 ${resourceRange(forecast.estimatedProcessPeakLowBytes, forecast.estimatedProcessPeakHighBytes)}，不是应用内存的硬上限",
            accent = colors.primary) { onOpen("resources") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ResourceAllocation(Icons.Outlined.Image, "图片内存", usage.memoryBytes,
                target.imageMemoryBudgetBytes, Modifier.weight(1f), enabled, draft) { onOpen("cache") }
            ResourceAllocation(Icons.Outlined.Storage, "缓存磁盘", forecast.diskCurrentBytes,
                target.diskBudgetBytes, Modifier.weight(1f), enabled, draft,
                resizePending = usage.diskResizePending) { onOpen("cache") }
        }
    }
}

@Composable
private fun ResourceMetricRow(icon: ImageVector, title: String, value: String, trailing: String,
    enabled: Boolean, description: String, accent: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit) {
    MotionTextButton(onClick, enabled = enabled, contentPadding = PaddingValues(0.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = accent)
            Text(trailing, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResourceAllocation(icon: ImageVector, title: String, used: Long?, budget: Long?,
    modifier: Modifier, enabled: Boolean, draft: Boolean, resizePending: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val statusColors = mangaStatusColors()
    val fraction = if (used != null && used >= 0L && budget != null && budget > 0L)
        (used.toDouble() / budget).coerceIn(0.0, 1.0).toFloat() else null
    val overBudget = used != null && budget != null && budget >= 0L && used > budget
    val known = used != null && used >= 0L && budget != null && budget >= 0L
    val accent = when {
        resizePending || overBudget -> statusColors.waiting
        !known || budget == 0L -> statusColors.neutral
        else -> colors.primary
    }
    MotionTextButton(onClick, enabled = enabled, contentPadding = PaddingValues(vertical = 6.dp),
        modifier = modifier.heightIn(min = 52.dp).semantics(mergeDescendants = true) {
            contentDescription = "$title，当前 ${resourceBytes(used)}，" + (if (draft) "草稿预算 " else "现用预算 ") + resourceBytes(budget) +
                if (overBudget) {
                    if (draft) "，低于当前占用，应用后按缓存规则回收" else "，当前高于预算，在用内容释放后回收"
                } else if (resizePending) "，现用磁盘容量仍在等待调整" else if (!known) "，尚无完整采样"
                else if (budget == 0L) { if (draft) "，草稿将关闭此项缓存预算" else "，此项缓存预算已关闭" } else ""
        }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(icon, null, Modifier.size(20.dp), tint = accent)
                Text(resourceBytes(used), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    val mark = when {
                        resizePending || overBudget -> Icons.Outlined.Schedule
                        !known -> Icons.Outlined.HelpOutline
                        budget == 0L -> Icons.Outlined.Block
                        draft -> Icons.Outlined.Edit
                        else -> null
                    }
                    if (mark != null) Icon(mark, null, Modifier.size(14.dp), tint = accent)
                }
            }
            Text("/ " + resourceBytes(budget), Modifier.padding(start = 26.dp),
                style = MaterialTheme.typography.labelSmall, color = if (draft) accent else colors.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(3.dp)
                .background(colors.onSurface.copy(alpha = if (fraction == null) .035f else .08f), CircleShape)) {
                if (fraction != null) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction)
                    .background(accent.copy(alpha = .75f), CircleShape))
            }
        }
    }
}

/** Values are one cache-owner snapshot; inspecting them never renews an image's idle age. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ExperienceMemoryCacheSummary(usage: ExperienceUsage, draftIdleMinutes: Int,
    enabled: Boolean, clearing: Boolean, onClear: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val statusColors = mangaStatusColors()
    val memory = usage.memoryCache
    var details by remember { mutableStateOf(false) }
    val expiry = memory?.idleMinutes?.let { if (it == 0) "∞" else "$it min" } ?: "—"
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Image, "解码内存", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(resourceBytes(memory?.bytes) + " / " + resourceBytes(memory?.limit),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(memory?.entries?.let { "$it 项" } ?: "—", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Schedule, null, Modifier.size(15.dp),
                            tint = colors.onSurfaceVariant)
                        Text(expiry, style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.semantics {
                                contentDescription = memory?.idleMinutes?.let {
                                    if (it == 0) "时间过期关闭，仅按容量淘汰" else "闲置 $it 分钟到期回收"
                                } ?: "内存缓存尚未采样"
                            })
                    }
                    if (memory != null && draftIdleMinutes != memory.idleMinutes) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Edit, "草稿闲置回收时间", Modifier.size(14.dp), tint = colors.primary)
                        Text(if (draftIdleMinutes == 0) "∞" else "$draftIdleMinutes min",
                            style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    }
                }
            }
            MotionIconButton(onClick = { details = !details }, enabled = enabled,
                modifier = Modifier.size(44.dp), tooltip = "实际过期、淘汰与清空") {
                Icon(Icons.Outlined.Info, "实际过期、淘汰与清空", Modifier.size(19.dp),
                    tint = if (details) colors.primary else colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = onClear, enabled = enabled && !clearing,
                modifier = Modifier.size(44.dp), tooltip = "释放可复用的解码图片内存") {
                if (clearing) MotionBusyIndicator(Modifier.size(20.dp).semantics { contentDescription = "正在清理解码缓存" },
                    color = statusColors.activity, strokeWidth = 1.5.dp)
                else Icon(Icons.Outlined.CleaningServices, "释放解码缓存", Modifier.size(20.dp), tint = colors.primary)
            }
        }
        ExperienceReveal(details) {
            fun released(entries: Long?, bytes: Long?) = if (entries == null || bytes == null) "—"
                else "$entries 项 · " + resourceBytes(bytes)
            fun removalMark(entries: Long?) = when {
                entries == null -> Icons.Outlined.HelpOutline
                entries > 0L -> Icons.Outlined.CheckCircle
                else -> Icons.Outlined.Remove
            }
            fun removalColor(entries: Long?) = if (entries != null && entries > 0L) statusColors.success else statusColors.neutral
            ResourceValue(Icons.Outlined.Schedule, "到期回收", released(memory?.expiredEntries, memory?.expiredBytes),
                removalMark(memory?.expiredEntries), removalColor(memory?.expiredEntries))
            ResourceValue(Icons.Outlined.SwapHoriz, "容量淘汰", released(memory?.evictedEntries, memory?.evictedBytes),
                removalMark(memory?.evictedEntries), removalColor(memory?.evictedEntries))
            ResourceValue(Icons.Outlined.CleaningServices, "清空回收", released(memory?.clearedEntries, memory?.clearedBytes),
                removalMark(memory?.clearedEntries), removalColor(memory?.clearedEntries))
            ResourceValue(Icons.Outlined.History, "最近回收", memory?.lastReclaimedAtMillis?.let {
                cacheInterval((memory.sampledAtMillis - it).coerceAtLeast(0L)) + "前"
            } ?: "—")
            ResourceValue(Icons.Outlined.Timer, "下一到期", when {
                memory == null -> "—"
                memory.idleMinutes == 0 -> "∞"
                memory.nextExpiryAtMillis != null -> cacheInterval((memory.nextExpiryAtMillis - memory.sampledAtMillis).coerceAtLeast(0L))
                else -> "—"
            })
            Text("∞＝只按容量淘汰。时间以最近采样为准；累计仅指本次缓存移出的条目与引用，当前显示图像仍可能占用内存。",
                Modifier.padding(vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun cacheInterval(millis: Long): String = when {
    millis < 60_000L -> "${(millis + 999L) / 1_000L} s"
    millis < 3_600_000L -> "${millis / 60_000L} min"
    else -> "${millis / 3_600_000L} h"
}

@Composable
internal fun ExperienceDeviceDetails(usage: ExperienceUsage) {
    val r = usage.resources
    val device = r.device
    val statusColors = mangaStatusColors()
    ResourceValue(Icons.Outlined.Devices, "设备", device.deviceModel ?: "—")
    ResourceValue(Icons.Outlined.DesktopWindows, "系统",
        listOfNotNull(device.platformName, device.osVersion).joinToString(" · ").ifBlank { "—" })
    ResourceValue(Icons.Outlined.Memory, "CPU", device.processorName ?: "—")
    ResourceValue(Icons.Outlined.DeveloperBoard, "架构", device.processorArchitecture ?: "—")
    ResourceValue(Icons.Outlined.GridView, "物理核 / 逻辑核",
        (device.physicalProcessorCount?.toString() ?: "—") + " / " +
            (device.logicalProcessorCount ?: r.processorCount)?.toString().orEmpty().ifBlank { "—" })
    ResourceValue(Icons.Outlined.Speed, "最高频率", device.processorMaxFrequencyHz?.let {
        if (it >= 1_000_000_000L) ((it / 1_000_000_000.0 * 100).roundToInt() / 100.0).toString() + " GHz"
        else (it / 1_000_000L).toString() + " MHz"
    } ?: "—")
    ResourceValue(Icons.Outlined.Memory, "RAM · 可用 / 总量",
        resourceBytes(r.availableMemoryBytes) + " / " + resourceBytes(r.totalMemoryBytes))
    ResourceValue(Icons.Outlined.Memory, "内存压力", when (r.memoryPressure) {
        true -> "较高"; false -> "正常"; null -> "—"
    }, statusIcon = when (r.memoryPressure) {
        true -> Icons.Outlined.WarningAmber; false -> Icons.Outlined.CheckCircle; null -> Icons.Outlined.HelpOutline
    }, statusColor = when (r.memoryPressure) {
        true -> statusColors.waiting; false -> statusColors.success; null -> statusColors.neutral
    })
    ResourceValue(Icons.Outlined.Storage, "磁盘 · 可用 / 总量",
        resourceBytes(r.freeDiskBytes) + " / " + resourceBytes(r.totalDiskBytes))
    ResourceValue(Icons.Outlined.Speed, "CPU · 应用 / 系统",
        resourcePercent(r.processCpuLoad) + " / " + resourcePercent(r.cpuLoad))
    ResourceValue(Icons.Outlined.BatteryStd, "电量",
        (r.batteryPercent?.let { "$it%" } ?: "—") + when (r.charging) { true -> " · 充电"; false -> " · 未充电"; null -> "" })
    ResourceValue(Icons.Outlined.Thermostat, "温度压力", when (r.thermalPressure) {
        0 -> "正常"; 1 -> "升高"; 2 -> "较高"; 3 -> "严重"; else -> "—"
    }, statusIcon = when (r.thermalPressure) {
        0 -> Icons.Outlined.CheckCircle
        1, 2 -> Icons.Outlined.WarningAmber
        3 -> Icons.Outlined.ErrorOutline
        else -> Icons.Outlined.HelpOutline
    }, statusColor = when (r.thermalPressure) {
        0 -> statusColors.success
        1, 2 -> statusColors.waiting
        3 -> statusColors.error
        else -> statusColors.neutral
    })
    ResourceValue(Icons.Outlined.NetworkCheck, "网络", when (r.networkAvailable) {
        false -> "离线"; true -> when (r.metered) { true -> "在线 · 计费"; false -> "在线 · 非计费"; null -> "在线 · 计费未知" }
        null -> "—"
    }, statusIcon = when (r.networkAvailable) {
        true -> Icons.Outlined.CheckCircle; false -> Icons.Outlined.WifiOff; null -> Icons.Outlined.HelpOutline
    }, statusColor = when (r.networkAvailable) {
        true -> statusColors.success; false -> statusColors.waiting; null -> statusColors.neutral
    })
    Text("— 表示平台未提供；状态来自最近采样。网络在线仅表示系统连接，不代表每个来源可达。",
        Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ExperienceForecastDetails(usage: ExperienceUsage, appliedBudget: ExperienceBudget,
    previewBudget: ExperienceBudget, draft: Boolean) {
    val applied = remember(appliedBudget, usage) { estimateExperienceResources(appliedBudget, usage) }
    val f = remember(previewBudget, usage) { estimateExperienceResources(previewBudget, usage) }
    ResourceValue(Icons.Outlined.Memory, "应用 " + processMemoryLabel(f.processMemoryKind), resourceBytes(f.currentProcessBytes))
    ResourceValue(Icons.Outlined.TrendingUp, "本次观测峰值", resourceBytes(f.observedProcessPeakBytes))
    if (!draft) ResourceValue(Icons.Outlined.Calculate, "现用情景峰值",
        resourceRange(f.estimatedProcessPeakLowBytes, f.estimatedProcessPeakHighBytes))
    if (draft) Text("预算变化 · 现用 → 草稿", Modifier.padding(top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ResourceValue(Icons.Outlined.Image, "可复用图片预算",
        resourceBudgetChange(applied.imageMemoryBudgetBytes, f.imageMemoryBudgetBytes, draft))
    ResourceValue(Icons.Outlined.Code, "原图处理预算",
        resourceBudgetChange(applied.encodedMemoryBudgetBytes, f.encodedMemoryBudgetBytes, draft))
    ResourceValue(Icons.Outlined.PhotoSizeSelectLarge, "并行解码估算",
        if (draft && (applied.decodeWorkingLowBytes != f.decodeWorkingLowBytes || applied.decodeWorkingHighBytes != f.decodeWorkingHighBytes))
            resourceRange(applied.decodeWorkingLowBytes, applied.decodeWorkingHighBytes) + " → " +
                resourceRange(f.decodeWorkingLowBytes, f.decodeWorkingHighBytes)
        else resourceRange(f.decodeWorkingLowBytes, f.decodeWorkingHighBytes))
    ResourceValue(Icons.Outlined.Storage, "磁盘预算",
        resourceBudgetChange(applied.diskBudgetBytes, f.diskBudgetBytes, draft))
    ResourceValue(Icons.Outlined.AutoStories, "原图窗口",
        resourceBudgetChange(applied.originalWindowBytes, f.originalWindowBytes, draft))
    ResourceValue(Icons.Outlined.Storage, "磁盘当前占用", resourceBytes(f.diskCurrentBytes))
    ResourceValue(Icons.Outlined.TrendingUp, "磁盘观测峰值", resourceBytes(f.diskObservedPeakBytes))
    ResourceValue(Icons.Outlined.Analytics, "真实解码样本", f.decodeSampleCount.toString())
    f.notes.forEach { note ->
        Text(note, Modifier.padding(vertical = 3.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ExperienceCacheBreakdown(usage: ExperienceUsage) {
    ResourceValue(Icons.Outlined.Storage, "共享磁盘", resourceBytes(usage.diskBytes))
    ResourceValue(Icons.Outlined.Lock, "保护中", resourceBytes(usage.diskProtectedBytes))
    ResourceValue(Icons.Outlined.CleaningServices, "可回收", resourceBytes(usage.diskReclaimableBytes))
    if (usage.diskAreas.isEmpty()) {
        Text("尚无分类采样", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else usage.diskAreas.forEach { area ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            ResourceValue(when (area.id.uppercase()) {
                "READING", "SCRIPT", "JM" -> Icons.Outlined.AutoStories
                "ARTWORK" -> Icons.Outlined.Image
                "DATA", "CATALOG", "BROWSE", "METADATA", "RANKINGS" -> Icons.Outlined.Folder
                "PANELS" -> Icons.Outlined.GridView
                "ANIME" -> Icons.Outlined.PlayCircleOutline
                else -> Icons.Outlined.Storage
            }, area.label, resourceBytes(area.bytes))
            Row(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(resourceBytes(area.protectedBytes), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.CleaningServices, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                Text(resourceBytes(area.reclaimableBytes), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    Text("锁：在用或受保护 · 清扫：可回收。清理前仍会核对当前归属。",
        Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    usage.cacheError?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
}

/** Keep the effect of a multi-field draft visible while its controls are being edited. */
@Composable
internal fun ExperienceDraftImpact(usage: ExperienceUsage, appliedBudget: ExperienceBudget,
    previewBudget: ExperienceBudget, editing: Boolean = false) {
    val applied = remember(appliedBudget, usage) { estimateExperienceResources(appliedBudget, usage) }
    val forecast = remember(previewBudget, usage) { estimateExperienceResources(previewBudget, usage) }
    val currentEstimate = resourceRange(applied.estimatedProcessPeakLowBytes, applied.estimatedProcessPeakHighBytes)
    val draftEstimate = resourceRange(forecast.estimatedProcessPeakLowBytes, forecast.estimatedProcessPeakHighBytes)
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)
        .semantics(mergeDescendants = true) {
            contentDescription = "使用同一份实际观测计算的情景峰值，现用配置 $currentEstimate，草稿配置 $draftEstimate。" +
                "只估算已知部分，不是硬上限。" + if (editing) "正在输入的数值尚未计入。" else ""
        }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 420.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ResourceEstimateLine(Icons.Outlined.CheckCircle, "现用", "≈ " + currentEstimate, colors.onSurfaceVariant)
                ResourceEstimateLine(Icons.Outlined.Edit, "草稿", "≈ " + draftEstimate, colors.primary)
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f)) { ResourceEstimateLine(Icons.Outlined.CheckCircle, "现用", "≈ " + currentEstimate, colors.onSurfaceVariant) }
                Box(Modifier.weight(1f)) { ResourceEstimateLine(Icons.Outlined.Edit, "草稿", "≈ " + draftEstimate, colors.primary) }
            }
        }
        val status = when {
            editing -> "编辑中 · 尚未计入"
            forecast.estimatedProcessPeakHighBytes == null && forecast.decodeSampleCount == 0 -> "待样本"
            forecast.estimatedProcessPeakHighBytes == null -> "观测不足"
            else -> null
        }
        if (status != null) Text(status, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ResourceEstimateLine(icon: ImageVector, label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = color, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
internal fun ResourceValue(icon: ImageVector, label: String, value: String,
    statusIcon: ImageVector? = null, statusColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.padding(top = 1.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.weight(1.35f), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface)
            if (statusIcon != null) Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Icon(statusIcon, null, Modifier.size(13.dp), tint = statusColor)
            }
        }
    }
}

private fun processMemoryLabel(kind: ExperienceProcessMemoryKind?) = when (kind) {
    ExperienceProcessMemoryKind.RESIDENT -> "RSS"
    ExperienceProcessMemoryKind.PROPORTIONAL -> "PSS"
    ExperienceProcessMemoryKind.MANAGED_HEAP -> "堆"
    null -> "内存"
}
private fun resourceBytes(bytes: Long?) = bytes?.let(::experienceCacheSize) ?: "—"
private fun resourceBudgetChange(applied: Long?, draft: Long?, changed: Boolean): String =
    if (changed && applied != draft) resourceBytes(applied) + " → " + resourceBytes(draft) else resourceBytes(draft)
private fun resourceRange(low: Long?, high: Long?): String = when {
    low == null || high == null -> "—"
    low == high -> resourceBytes(high)
    else -> resourceBytes(low) + " – " + resourceBytes(high)
}
private fun resourcePercent(value: Double?) = value?.takeIf(Double::isFinite)
    ?.let { (it * 100).roundToInt().toString() + "%" } ?: "—"
