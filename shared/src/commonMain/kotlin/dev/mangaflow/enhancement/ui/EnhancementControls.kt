package dev.mangaflow.enhancement.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mangaflow.enhancement.*
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.SettingsToggle
import dev.mangaflow.ui.motion.*

@Composable
internal fun EnhancementSection(
    title: String,
    icon: ImageVector,
    summary: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!summary.isNullOrBlank()) Text(summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
        content()
    }
}

/** No selection is painted in the other row; six presets remain directly comparable on a phone. */
@Composable
internal fun <T> EnhancementChoices(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    description: String,
    columns: Int = 3,
) {
    Column(Modifier.fillMaxWidth().semantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        values.chunked(columns).forEach { row ->
            val index = row.indexOf(selected)
            MotionTabs(index.coerceAtLeast(0), row.map(label), onSelect = { onSelect(row[it]) },
                modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp,
                treatment = MotionTabTreatment.Soft, selectionVisible = index >= 0)
        }
    }
}

@Composable
internal fun EnhancementDetailToggle(expanded: Boolean, onClick: () -> Unit, label: String = "详情") {
    MotionTextButton(onClick, contentPadding = PaddingValues(horizontal = 6.dp)) {
        Text(if (expanded) "收起" else label, style = MaterialTheme.typography.labelMedium)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
    }
}

@Composable
internal fun EnhancementQuiet(text: String, error: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun EnhancementValue(name: String, value: String, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.weight(1.15f), style = MaterialTheme.typography.bodySmall)
        }
        if (!detail.isNullOrBlank()) EnhancementQuiet(detail)
    }
}

@Composable
internal fun EnhancementDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f))

internal fun enhancementBytes(bytes: Long?): String = when {
    bytes == null -> "不可用"
    bytes >= 1024L * 1024 * 1024 -> formatDecimal(bytes / (1024.0 * 1024 * 1024), 2) + " GiB"
    bytes >= 1024L * 1024 -> formatDecimal(bytes / (1024.0 * 1024), 1) + " MiB"
    bytes >= 1024 -> formatDecimal(bytes / 1024.0, 1) + " KiB"
    else -> "$bytes B"
}

internal fun engineReadinessLabel(value: EngineReadiness): String = when (value) {
    EngineReadiness.BUILT_IN -> "内置"
    EngineReadiness.NOT_INSTALLED -> "未安装"
    EngineReadiness.NEEDS_MODELS -> "需要模型"
    EngineReadiness.READY_FOR_TRIAL -> "可试用 · 尚未验证"
    EngineReadiness.UNSUPPORTED -> "暂不支持"
    EngineReadiness.FAILED -> "不可用"
}

internal fun engineStageLabel(value: EngineStage): String = when (value) {
    EngineStage.DOWNLOAD -> "正在下载"
    EngineStage.VERIFY -> "校验文件"
    EngineStage.INSTALL -> "正在安装"
    EngineStage.MODEL_LOAD -> "加载模型"
    EngineStage.PROCESS -> "正在处理"
    EngineStage.ENCODE -> "正在编码"
    EngineStage.VALIDATE -> "校验结果"
    EngineStage.COMPLETE -> "已完成"
}

internal fun EngineProgress.readableProgress(): String {
    val count = if (total != null && total > 0) "$completed / $total" else if (completed > 0) "$completed" else ""
    return listOfNotNull(engineStageLabel(stage), count.takeIf { it.isNotBlank() },
        processingFps?.let { formatDecimal(it, 1) + " 处理帧/秒" }).joinToString(" · ")
}

@Composable
internal fun EnhancementQualityControls(
    content: EnhancementContent,
    policy: EnhancementPolicy,
    inventory: EngineInventory,
    onChange: (EnhancementPolicy) -> Unit,
    onOpenModels: () -> Unit,
) {
    var advanced by remember(content) { mutableStateOf(false) }
    val model = inventory.models.firstOrNull { it.id == policy.modelId }
    EnhancementSection("增强画质", Icons.Outlined.AutoAwesome,
        "目标 · ${if (content != EnhancementContent.ANIME && policy.tier == QualityTier.ORIGINAL) "原图" else policy.tier.label}",
        action = { EnhancementDetailToggle(advanced, { advanced = !advanced }, "模型与倍率") }) {
        EnhancementChoices(QualityTier.entries, policy.tier,
            label = { if (content != EnhancementContent.ANIME && it == QualityTier.ORIGINAL) "原图" else it.label },
            onSelect = { onChange(policy.copy(tier = it)) }, description = "目标画质档位")
        if (policy.tier in setOf(QualityTier.FINE, QualityTier.ULTRA) && model == null) {
            EnhancementQuiet("这一档需要指定兼容的高质量模型；准备好之前使用原画。")
            MotionTextButton(onOpenModels, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("选择或安装模型") }
        }
        SettingsToggle("锁定所选档位", policy.locked, Icons.Outlined.Lock,
            onCheckedChange = { onChange(policy.copy(locked = it)) })
        if (policy.locked) EnhancementQuiet("保留你的选择；设备保护触发时先显示原画，结果可提前处理。")
        if (advanced) Column(Modifier.animateContentSize(if (LocalMotionEnabled.current) tween(MangaMotion.Content) else snap()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("输出倍率", style = MaterialTheme.typography.labelLarge)
            val matching = inventory.engines.filter { descriptor ->
                if (content == EnhancementContent.ANIME) EngineContent.VIDEO in descriptor.contents
                else descriptor.contents.any { it != EngineContent.VIDEO }
            }
            val selectedEngine = model?.let { chosen -> matching.firstOrNull { it.id == chosen.engineId } }
            val supported = (selectedEngine?.supportedScales ?: matching.flatMap { it.supportedScales }.toSet())
                .filter { it in setOf(1, 2, 4) }.sorted()
            if (supported.isNotEmpty()) EnhancementChoices(supported, policy.scale,
                label = { if (it == 1) "1× 修复" else "$it×" },
                onSelect = { onChange(policy.copy(scale = it)) }, description = "增强输出倍率")
            if (policy.scale !in supported) EnhancementQuiet("所选 ${policy.scale}× 当前没有兼容引擎，执行时保持原画。")
            if (content != EnhancementContent.ANIME) EnhancementChoices(ImageStyle.entries, policy.style,
                label = { it.label }, onSelect = { onChange(policy.copy(style = it)) },
                description = "漫画内容类型", columns = 2)
            MotionTextButton(onOpenModels, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                Icon(Icons.Outlined.Memory, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(model?.title ?: "自动选模型 · 管理引擎")
            }
            if (model != null) EnhancementQuiet("${model.version} · 原生 ${model.nativeScale}× · ${model.license}")
            if (policy.tier in setOf(QualityTier.FINE, QualityTier.ULTRA)) {
                EnhancementQuiet("高级档只使用兼容的真实模型；安装完成后还需在当前设备试用。")
            }
        }
    }
}

@Composable
internal fun EnhancementFrameControls(policy: EnhancementPolicy, inventory: EngineInventory,
    onChange: (EnhancementPolicy) -> Unit, onOpenModels: () -> Unit,
    customFps: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    EnhancementSection("运动补帧", Icons.Outlined.SlowMotionVideo, "目标 · ${policy.frameMode.label}",
        action = { EnhancementDetailToggle(expanded, { expanded = !expanded }, "质量") }) {
        val modes = FrameMode.entries.filter { it != FrameMode.CUSTOM || platformKind == PlatformKind.DESKTOP }
        EnhancementChoices(modes, policy.frameMode, label = { it.label },
            onSelect = { onChange(policy.copy(frameMode = it, detectScenes = true)) }, description = "补帧目标")
        if (policy.frameMode != FrameMode.ORIGINAL) {
            val interpolation = inventory.engines.filter { it.supportsInterpolation }
            val ready = interpolation.filter { it.readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL) }
            if (ready.none { it.realtime }) EnhancementQuiet(if (ready.isNotEmpty())
                "当前通过离线预处理补帧；实时播放保持文件帧率。" else "当前没有已就绪的补帧引擎，目标设置会保留。")
            if (ready.isEmpty()) MotionTextButton(onOpenModels, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("查看补帧引擎") }
        }
        if (policy.frameMode == FrameMode.CUSTOM) customFps()
        if (expanded) {
            EnhancementChoices(FrameQuality.entries, policy.frameQuality, label = { it.label },
                onSelect = { onChange(policy.copy(frameQuality = it)) }, description = "插帧质量", columns = 4)
            EnhancementValue("切镜保护", "补帧时始终启用")
            EnhancementQuiet("补帧独立于画质增强。实际运行由兼容引擎和设备测量决定。")
        }
    }
}

@Composable
internal fun EnhancementPreparationControls(policy: EnhancementPolicy, onChange: (EnhancementPolicy) -> Unit,
    anime: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    EnhancementSection("提前处理", Icons.Outlined.Schedule,
        policy.preparation.sortedBy { it.ordinal }.joinToString(" · ") { it.label }.ifBlank { "保持原画" },
        action = { EnhancementDetailToggle(expanded, { expanded = !expanded }, "调整") }) {
        if (expanded) {
            PreparationMode.entries.forEach { mode ->
                SettingsToggle(mode.label, mode in policy.preparation, when (mode) {
                    PreparationMode.IMMEDIATE -> Icons.Outlined.PlayCircleOutline
                    PreparationMode.SMART -> Icons.Outlined.AutoAwesome
                    PreparationMode.COMPLETE -> Icons.Outlined.DownloadForOffline
                }, onCheckedChange = { enabled -> onChange(policy.copy(preparation =
                    if (enabled) policy.preparation + mode else policy.preparation - mode)) })
            }
            SettingsToggle("下载完成后增强", policy.enhanceAfterDownload, Icons.Outlined.Download,
                onCheckedChange = { onChange(policy.copy(enhanceAfterDownload = it)) })
            EnhancementQuiet(if (anime) "先保证播放缓冲。已有增强结果优先使用，没有结果立即播放原画。"
                else "当前页先显示原图；优先准备下一页，快速跳页后取消失去价值的任务。")
        }
    }
}
