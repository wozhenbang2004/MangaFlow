package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.experience.*
import dev.mangaflow.ui.motion.MotionTextButton
import kotlin.math.roundToInt

/** Adapts the reader's CacheWorkRow/CacheOccupancy into this same retained settings surface. */
@Composable
internal fun ExperienceResourceOverview(usage: ExperienceUsage, appliedBudget: ExperienceBudget,
    previewBudget: ExperienceBudget, draft: Boolean, enabled: Boolean, editing: Boolean = false,
    onOpen: (String) -> Unit) {
    val forecast = remember(appliedBudget, usage) { estimateExperienceResources(appliedBudget, usage) }
    val target = remember(previewBudget, usage, draft, forecast) {
        if (draft) estimateExperienceResources(previewBudget, usage) else forecast
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ResourceMetricRow(Icons.Outlined.Memory, "应用 " + processMemoryLabel(forecast.processMemoryKind),
            resourceBytes(forecast.currentProcessBytes),
            "观测峰值 " + resourceBytes(forecast.observedProcessPeakBytes), enabled,
            "当前应用占用 ${resourceBytes(forecast.currentProcessBytes)}，本次同口径观测峰值 ${resourceBytes(forecast.observedProcessPeakBytes)}") { onOpen("resources") }
        if (draft) MotionTextButton(onClick = { onOpen("resources") }, enabled = enabled,
            contentPadding = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Box(Modifier.weight(1f)) { ExperienceDraftImpact(usage, appliedBudget, previewBudget, editing) }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
        } else ResourceMetricRow(Icons.Outlined.TrendingUp, "情景峰值",
            resourceRange(forecast.estimatedProcessPeakLowBytes, forecast.estimatedProcessPeakHighBytes),
            if (forecast.estimatedProcessPeakHighBytes != null) "非硬上限" else if (forecast.decodeSampleCount == 0) "待样本" else "数据不足",
            enabled, "配置情景估算 ${resourceRange(forecast.estimatedProcessPeakLowBytes, forecast.estimatedProcessPeakHighBytes)}，不是应用内存的硬上限",
            accent = colors.primary) { onOpen("resources") }
        Text(if (draft) "当前占用 / 草稿预算" else "当前占用 / 现用预算",
            Modifier.padding(top = 5.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ResourceAllocation(Icons.Outlined.Image, "图片内存", usage.memoryBytes,
                target.imageMemoryBudgetBytes, Modifier.weight(1f), enabled, draft) { onOpen("cache") }
            ResourceAllocation(Icons.Outlined.Storage, "缓存磁盘", forecast.diskCurrentBytes,
                target.diskBudgetBytes, Modifier.weight(1f), enabled, draft) { onOpen("cache") }
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            val stacked = maxWidth < 330.dp * scale
            val valuesStacked = maxWidth < 270.dp * scale
            Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, null, Modifier.size(18.dp), tint = accent)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!stacked) Column(horizontalAlignment = Alignment.End) {
                        Text(value, style = MaterialTheme.typography.bodyMedium, color = accent)
                        Text(trailing, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (stacked && valuesStacked) Column(Modifier.fillMaxWidth().padding(start = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = accent)
                    Text(trailing, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (stacked) Row(Modifier.fillMaxWidth().padding(start = 26.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = accent)
                    Text(trailing, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ResourceAllocation(icon: ImageVector, title: String, used: Long?, budget: Long?,
    modifier: Modifier, enabled: Boolean, draft: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fraction = if (used != null && used >= 0L && budget != null && budget > 0L)
        (used.toDouble() / budget).coerceIn(0.0, 1.0).toFloat() else null
    val overBudget = used != null && budget != null && budget >= 0L && used > budget
    val accent = if (overBudget) colors.tertiary else colors.primary
    MotionTextButton(onClick, enabled = enabled, contentPadding = PaddingValues(vertical = 6.dp),
        modifier = modifier.heightIn(min = 52.dp).semantics(mergeDescendants = true) {
            contentDescription = "$title，当前 ${resourceBytes(used)}，" + (if (draft) "草稿预算 " else "现用预算 ") + resourceBytes(budget) +
                if (overBudget) {
                    if (draft) "，低于当前占用，应用后按缓存规则回收" else "，当前高于预算，在用内容释放后回收"
                } else ""
        }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(icon, null, Modifier.size(15.dp), tint = accent)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (overBudget) Icon(Icons.Outlined.Schedule, null, Modifier.size(12.dp), tint = accent)
            }
            Text(resourceBytes(used) + " / " + resourceBytes(budget),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
            Box(Modifier.fillMaxWidth().height(3.dp)
                .background(colors.onSurface.copy(alpha = if (fraction == null) .035f else .08f), CircleShape)) {
                if (fraction != null) Box(Modifier.fillMaxHeight().fillMaxWidth(fraction)
                    .background(accent.copy(alpha = .75f), CircleShape))
            }
        }
    }
}

@Composable
internal fun ExperienceDeviceDetails(usage: ExperienceUsage) {
    val r = usage.resources
    val device = r.device
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
    ResourceValue(Icons.Outlined.Storage, "磁盘 · 可用 / 总量",
        resourceBytes(r.freeDiskBytes) + " / " + resourceBytes(r.totalDiskBytes))
    ResourceValue(Icons.Outlined.Speed, "CPU · 应用 / 系统",
        resourcePercent(r.processCpuLoad) + " / " + resourcePercent(r.cpuLoad))
    ResourceValue(Icons.Outlined.BatteryStd, "电量",
        (r.batteryPercent?.let { "$it%" } ?: "—") + when (r.charging) { true -> " · 充电"; false -> " · 未充电"; null -> "" })
    ResourceValue(Icons.Outlined.Thermostat, "温度压力", when (r.thermalPressure) {
        0 -> "正常"; 1 -> "升高"; 2 -> "较高"; 3 -> "严重"; else -> "—"
    })
    ResourceValue(Icons.Outlined.NetworkCheck, "网络", when (r.networkAvailable) {
        false -> "离线"; true -> when (r.metered) { true -> "在线 · 计费"; false -> "在线 · 非计费"; null -> "在线 · 计费未知" }
        null -> "—"
    })
    Text("— 表示平台未提供；CPU 用量来自实际采样。",
        Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ExperienceForecastDetails(usage: ExperienceUsage, appliedBudget: ExperienceBudget,
    previewBudget: ExperienceBudget, draft: Boolean, editing: Boolean = false) {
    val applied = remember(appliedBudget, usage) { estimateExperienceResources(appliedBudget, usage) }
    val f = remember(previewBudget, usage) { estimateExperienceResources(previewBudget, usage) }
    ResourceValue(Icons.Outlined.Memory, "应用 " + processMemoryLabel(f.processMemoryKind), resourceBytes(f.currentProcessBytes))
    ResourceValue(Icons.Outlined.TrendingUp, "本次观测峰值", resourceBytes(f.observedProcessPeakBytes))
    if (draft) ExperienceDraftImpact(usage, appliedBudget, previewBudget, editing)
    else ResourceValue(Icons.Outlined.Calculate, "现用情景峰值",
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
                ResourceEstimateLine("现用估算", currentEstimate, colors.onSurfaceVariant)
                ResourceEstimateLine("草稿估算", draftEstimate, colors.primary)
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("现用估算", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(currentEstimate, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("草稿估算", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                    Text(draftEstimate, style = MaterialTheme.typography.labelMedium, color = colors.primary)
                }
            }
        }
        Text(when {
            editing -> "数值完成后更新峰值估算"
            forecast.estimatedProcessPeakHighBytes == null && forecast.decodeSampleCount == 0 -> "待解码样本 · 峰值未知"
            forecast.estimatedProcessPeakHighBytes == null -> "观测不足 · 峰值未知"
            else -> "情景峰值 · 非硬上限"
        }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ResourceEstimateLine(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = color, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
internal fun ResourceValue(icon: ImageVector, label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.padding(top = 1.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.35f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface)
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
