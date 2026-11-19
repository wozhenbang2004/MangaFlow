package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.enhancement.*
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.SettingsToggle
import dev.mangaflow.ui.motion.*

/** Destinations share one retained player-settings surface and one policy editor. */
internal enum class EnhancementPage(val title: String) {
    QUALITY("画质"), FRAME("运动补帧"), PREPARATION("预处理"), STATUS("运行状态"),
    MODELS("引擎与模型"), CACHE("增强缓存"), TASKS("增强任务"), TRIALS("性能记录"),
}

@Composable
internal fun AnimeEnhancementScope(editSession: Boolean, onSelect: (Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val stacked = maxWidth < 280.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EnhancementQuiet("应用范围")
            ScopeChoices(editSession, onSelect)
        } else Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("应用范围", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.width(188.dp)) { ScopeChoices(editSession, onSelect) }
        }
    }
}

@Composable
private fun ScopeChoices(editSession: Boolean, onSelect: (Boolean) -> Unit) {
    MotionTabs(if (editSession) 0 else 1, listOf("本次播放", "默认设置"),
        onSelect = { onSelect(it == 0) }, modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp,
        treatment = MotionTabTreatment.Soft, fullWidthIndicator = true,
        track = MaterialTheme.colorScheme.onSurface.copy(alpha = .035f), role = Role.RadioButton)
}

/** Adapt the existing Ovio selection surface; a setting is a radio choice, not a navigation tab. */
@Composable
private fun <T> AnimeEnhancementChoices(
    values: List<T>, selected: T, label: (T) -> String, description: String,
    onSelect: (T) -> Unit, columns: Int = 3,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val availableColumns = (maxWidth / (88.dp * scale)).toInt().coerceIn(1, columns)
        val cellCount = if (values.size == 4 && availableColumns == 3) 2 else availableColumns
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .035f)).padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            values.chunked(cellCount).forEach { row ->
                val index = row.indexOf(selected)
                MotionTabs(index.coerceAtLeast(0), row.map(label), onSelect = { onSelect(row[it]) },
                    modifier = Modifier.fillMaxWidth(row.size.toFloat() / cellCount), controlHeight = 48.dp,
                    treatment = MotionTabTreatment.Soft, fullWidthIndicator = true,
                    selectionVisible = index >= 0, role = Role.RadioButton,
                    selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal)
            }
        }
    }
}

@Composable
internal fun AnimeEnhancementNavigation(icon: ImageVector, title: String, value: String? = null,
    enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
        Icon(icon, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface.copy(alpha = if (enabled) 1f else .45f))
            if (!value.isNullOrBlank()) Text(value, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
internal fun AnimeEnhancementQuality(policy: EnhancementPolicy, inventory: EngineInventory,
    onChange: (EnhancementPolicy) -> Unit, onOpenModels: () -> Unit) {
    val model = inventory.models.firstOrNull { it.id == policy.modelId }
    val anime4k = model?.let { dev.mangaflow.anime.player.Anime4KOptionalModels.supports(it.engineId) } == true
    Text("增强档位", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    AnimeEnhancementChoices(QualityTier.entries, policy.tier, { it.label }, "画质档位",
        onSelect = { onChange(policy.copy(tier = it)) })
    Spacer(Modifier.height(8.dp))
    EnhancementQuiet(when (policy.tier) {
        QualityTier.ORIGINAL -> "保留片源画面，不进行画质增强。"
        QualityTier.AUTO -> model?.title?.takeIf { anime4k } ?: "根据已有测量与设备保护选择轻量或均衡。"
        QualityTier.LIGHT -> model?.title?.takeIf { anime4k } ?: "Anime4K S/S · 较轻的实时处理。"
        QualityTier.BALANCED -> model?.title?.takeIf { anime4k } ?: "Anime4K M/M · 更精细的实时处理。"
        QualityTier.FINE, QualityTier.ULTRA -> if (model == null)
            "使用兼容的高质量模型，实际执行与回退原因见运行状态。"
            else if (anime4k) "当前模型用于基础实时档，请选择适合此档的高质量模型。"
            else "${model.title} · 能否即时增强取决于设备、模型和片源。"
    })
    if (policy.tier != QualityTier.ORIGINAL && PreparationMode.IMMEDIATE !in policy.preparation)
        EnhancementQuiet("即时处理已关闭，可在“预处理”中调整。")
    Spacer(Modifier.height(12.dp))
    Text("输出倍率", style = MaterialTheme.typography.labelLarge)
    val engines = inventory.engines.filter { EngineContent.VIDEO in it.contents }
    val selectedEngine = model?.let { choice -> engines.firstOrNull { it.id == choice.engineId } }
    val supported = (selectedEngine?.supportedScales ?: engines.flatMap { it.supportedScales }.toSet())
        .filter { it in setOf(1, 2, 4) }.sorted()
    AnimeEnhancementChoices(listOf(1, 2, 4), policy.scale,
        { if (it == 1) "1× 修复" else "$it×" }, "增强输出倍率",
        onSelect = { onChange(policy.copy(scale = it)) })
    if (policy.scale !in supported) EnhancementQuiet("已选 ${policy.scale}× · 当前没有兼容引擎，保留原画。")
    SettingsToggle("锁定所选档位", policy.locked, Icons.Outlined.Lock,
        onCheckedChange = { onChange(policy.copy(locked = it)) })
    if (policy.locked) EnhancementQuiet("设备保护触发时暂用原画，你的选择会保留。")
    AnimeEnhancementNavigation(Icons.Outlined.Memory, "引擎与模型", model?.title ?: "自动选择兼容模型", onClick = onOpenModels)
}

@Composable
internal fun AnimeEnhancementFrames(policy: EnhancementPolicy, inventory: EngineInventory,
    onChange: (EnhancementPolicy) -> Unit, onOpenModels: () -> Unit,
    customFps: @Composable () -> Unit) {
    Text("目标帧率", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    AnimeEnhancementChoices(FrameMode.entries, policy.frameMode,
        { if (it == FrameMode.ORIGINAL) "原始帧率" else it.label }, "补帧目标",
        onSelect = { onChange(policy.copy(frameMode = it, detectScenes = true)) })
    if (policy.frameMode == FrameMode.CUSTOM) customFps()
    Spacer(Modifier.height(10.dp))
    val ready = inventory.engines.filter { it.supportsInterpolation &&
        it.readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL) }
    EnhancementQuiet(when {
        policy.frameMode == FrameMode.ORIGINAL -> "按文件原始帧率播放，不生成额外画面。"
        ready.isEmpty() -> "补帧引擎尚未就绪，已保存目标；当前仍按原始帧率播放。"
        ready.none { it.realtime && it.videoOutput == null } -> "当前支持离线补帧。处理完成后，从离线任务打开增强版本。"
        policy.frameQuality != FrameQuality.BALANCED -> "实时组件通过均衡档开启。已保存所选质量，供离线处理。"
        PreparationMode.IMMEDIATE !in policy.preparation -> "启用边播边增强即可试用实时补帧。"
        policy.frameMode == FrameMode.AUTO -> "自动需要显示器刷新率回执。可先选择明确目标进行试用。"
        else -> "已保存补帧目标，实际帧率以运行状态中的观测为准。"
    })
    Spacer(Modifier.height(14.dp))
    Text("补帧质量", style = MaterialTheme.typography.labelLarge)
    AnimeEnhancementChoices(FrameQuality.entries, policy.frameQuality, { it.label }, "插帧质量",
        onSelect = { onChange(policy.copy(frameQuality = it)) }, columns = 4)
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        EnhancementQuiet("切镜保护 · 补帧时始终启用")
    }
    AnimeEnhancementNavigation(Icons.Outlined.Memory, "补帧引擎", if (ready.isEmpty()) "查看安装与支持情况" else
        ready.joinToString(" · ") { it.title }, onClick = onOpenModels)
}

@Composable
internal fun AnimeEnhancementPreparation(policy: EnhancementPolicy, onChange: (EnhancementPolicy) -> Unit) {
    EnhancementQuiet("播放本地剧集时优先使用已有增强版本；尚未准备时播放原画。")
    Spacer(Modifier.height(8.dp))
    PreparationMode.entries.forEach { mode ->
        val label = when (mode) {
            PreparationMode.IMMEDIATE -> "边播边增强"
            PreparationMode.SMART -> "智能预处理"
            PreparationMode.COMPLETE -> "完整预处理"
        }
        val icon = when (mode) {
            PreparationMode.IMMEDIATE -> Icons.Outlined.PlayCircleOutline
            PreparationMode.SMART -> Icons.Outlined.AutoAwesome
            PreparationMode.COMPLETE -> Icons.Outlined.DownloadForOffline
        }
        SettingsToggle(label, mode in policy.preparation, icon, onCheckedChange = { enabled ->
            onChange(policy.copy(preparation = if (enabled) policy.preparation + mode else policy.preparation - mode))
        })
        Text(when (mode) {
            PreparationMode.IMMEDIATE -> "使用当前已支持的实时增强。"
            PreparationMode.SMART -> if (platformKind == PlatformKind.ANDROID)
                "兼容的点播片源可提前处理 5–15 秒画面，按原片时间与画面核对后复用。"
                else "优先使用已准备的增强版本。"
            PreparationMode.COMPLETE -> "使用完整增强版本；处理需从离线任务发起。"
        }, Modifier.padding(start = 34.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
