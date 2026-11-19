package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.enhancement.*
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.SettingsToggle
import dev.mangaflow.ui.motion.*

/** Destinations share one retained surface and one policy editor. */
internal enum class EnhancementPage(val title: String) {
    QUALITY("画质"), FRAME("补帧"), PREPARATION("预处理"), STATUS("状态"),
    QUALITY_OPTIONS("输出与策略"), FRAME_OPTIONS("补帧质量"), DISPLAY("观看时显示"),
    MODELS("画质模型"), MODEL_DETAIL("模型详情"), ENGINES("运行组件"),
    FRAME_ENGINES("补帧组件"), ENGINE_DETAIL("组件详情"), IMPORT("导入模型"),
    CACHE("增强缓存"), TASKS("增强任务"), TRIALS("性能记录"),
    ;

    val isPrimary: Boolean get() = this in listOf(QUALITY, FRAME, PREPARATION, STATUS)
}

internal fun enhancementPrimaryPages(content: EnhancementContent): List<EnhancementPage> =
    listOfNotNull(EnhancementPage.QUALITY, EnhancementPage.FRAME.takeIf { content == EnhancementContent.ANIME },
        EnhancementPage.PREPARATION, EnhancementPage.STATUS)

/** Remember the actual parent, including model → component → import journeys. */
@Stable
internal class EnhancementNavigation(initial: EnhancementPage = EnhancementPage.QUALITY) {
    var page by mutableStateOf(initial)
        private set
    private var history by mutableStateOf(initialHistory(initial))
    val parent: EnhancementPage? get() = history.lastOrNull()

    fun open(next: EnhancementPage) {
        if (next == page) return
        history = if (next.isPrimary) emptyList() else history + page
        page = next
    }

    fun back(): Boolean {
        val previous = history.lastOrNull() ?: return false
        history = history.dropLast(1)
        page = previous
        return true
    }

    fun reset(next: EnhancementPage = EnhancementPage.QUALITY) {
        history = initialHistory(next)
        page = next
    }

    private fun initialHistory(page: EnhancementPage): List<EnhancementPage> = when (page) {
        EnhancementPage.QUALITY, EnhancementPage.FRAME, EnhancementPage.PREPARATION, EnhancementPage.STATUS -> emptyList()
        EnhancementPage.FRAME_OPTIONS, EnhancementPage.FRAME_ENGINES -> listOf(EnhancementPage.FRAME)
        EnhancementPage.DISPLAY, EnhancementPage.TRIALS -> listOf(EnhancementPage.STATUS)
        EnhancementPage.CACHE, EnhancementPage.TASKS -> listOf(EnhancementPage.PREPARATION)
        else -> listOf(EnhancementPage.QUALITY)
    }
}

@Composable
internal fun EnhancementScope(content: EnhancementContent, editSession: Boolean, onSelect: (Boolean) -> Unit) {
    val sessionLabel = if (content == EnhancementContent.ANIME) "本次播放" else "本次阅读"
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val stacked = maxWidth < 280.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EnhancementQuiet("应用范围")
            ScopeChoices(sessionLabel, editSession, onSelect)
        } else Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("应用范围", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.width(188.dp)) { ScopeChoices(sessionLabel, editSession, onSelect) }
        }
    }
}

@Composable
private fun ScopeChoices(sessionLabel: String, editSession: Boolean, onSelect: (Boolean) -> Unit) {
    MotionTabs(if (editSession) 0 else 1, listOf(sessionLabel, "默认设置"),
        onSelect = { onSelect(it == 0) }, modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp,
        treatment = MotionTabTreatment.Soft, fullWidthIndicator = true,
        track = MaterialTheme.colorScheme.onSurface.copy(alpha = .035f), role = Role.RadioButton)
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
internal fun EnhancementQuality(content: EnhancementContent, policy: EnhancementPolicy, inventory: EngineInventory,
    onChange: (EnhancementPolicy) -> Unit, onOpenModels: () -> Unit, onOpenOptions: () -> Unit) {
    val anime = content == EnhancementContent.ANIME
    val model = inventory.models.firstOrNull { it.id == policy.modelId }
    Text("增强档位", style = MaterialTheme.typography.titleSmall)
    EnhancementChoices(QualityTier.entries, policy.tier,
        label = { if (!anime && it == QualityTier.ORIGINAL) "原图" else it.label },
        onSelect = { onChange(policy.copy(tier = it)) }, description = "画质档位")
    EnhancementQuiet(when {
        policy.tier == QualityTier.ORIGINAL -> if (anime) "保留片源画面。" else "保留原图。"
        model == null && policy.modelId != null -> "所选模型不可用，可重新选择；你的档位会保留。"
        anime && PreparationMode.IMMEDIATE !in policy.preparation -> "即时增强已关闭，可在预处理中开启。"
        policy.tier == QualityTier.AUTO -> "依据已有测量和设备负载选择档位。"
        policy.tier == QualityTier.LIGHT -> "优先降低处理开销。"
        policy.tier == QualityTier.BALANCED -> "兼顾清晰度与处理开销。"
        else -> "需要兼容的高质量模型，运行效果以实际观测为准。"
    })
    Spacer(Modifier.height(8.dp))
    AnimeEnhancementNavigation(Icons.Outlined.Memory, "画质模型",
        model?.title ?: if (policy.modelId == null) "自动选择" else "所选模型不可用", onClick = onOpenModels)
    AnimeEnhancementNavigation(Icons.Outlined.Tune, "输出与策略",
        listOfNotNull(if (policy.scale == 1) "1× 修复" else "${policy.scale}×",
            policy.style.label.takeUnless { anime }, "锁定档位".takeIf { policy.locked }).joinToString(" · "),
        onClick = onOpenOptions)
}

@Composable
internal fun EnhancementQualityOptions(content: EnhancementContent, policy: EnhancementPolicy,
    inventory: EngineInventory, onChange: (EnhancementPolicy) -> Unit) {
    val anime = content == EnhancementContent.ANIME
    val engines = inventory.engines.filter { if (anime) EngineContent.VIDEO in it.contents
        else it.contents.any { kind -> kind != EngineContent.VIDEO } }
    val selectedEngine = inventory.models.firstOrNull { it.id == policy.modelId }
        ?.let { choice -> engines.firstOrNull { it.id == choice.engineId } }
    val supported = selectedEngine?.supportedScales ?: engines.flatMap { it.supportedScales }.toSet()
    Text("输出倍率", style = MaterialTheme.typography.titleSmall)
    EnhancementChoices(listOf(1, 2, 4), policy.scale, label = { if (it == 1) "1× 修复" else "$it×" },
        onSelect = { onChange(policy.copy(scale = it)) }, description = "增强输出倍率")
    if (policy.scale !in supported) EnhancementQuiet("已选 ${policy.scale}× · 当前没有兼容组件，保持原画。")
    if (!anime) {
        Spacer(Modifier.height(12.dp))
        Text("漫画类型", style = MaterialTheme.typography.titleSmall)
        EnhancementChoices(ImageStyle.entries, policy.style, label = { it.label },
            onSelect = { onChange(policy.copy(style = it)) }, description = "漫画内容类型", columns = 2)
    }
    Spacer(Modifier.height(12.dp))
    EnhancementDivider()
    SettingsToggle("锁定所选档位", policy.locked, Icons.Outlined.Lock,
        onCheckedChange = { onChange(policy.copy(locked = it)) })
    EnhancementQuiet("开启后保留所选档位；设备保护触发时暂用原画。")
}

@Composable
internal fun AnimeEnhancementFrames(policy: EnhancementPolicy, inventory: EngineInventory,
    onChange: (EnhancementPolicy) -> Unit, onOpenEngines: () -> Unit, onOpenQuality: () -> Unit,
    customFps: @Composable () -> Unit) {
    Text("目标帧率", style = MaterialTheme.typography.titleSmall)
    EnhancementChoices(FrameMode.entries, policy.frameMode,
        label = { if (it == FrameMode.ORIGINAL) "原始帧率" else it.label },
        onSelect = { onChange(policy.copy(frameMode = it, detectScenes = true)) }, description = "补帧目标")
    if (policy.frameMode == FrameMode.CUSTOM) customFps()
    val ready = inventory.engines.filter { it.supportsInterpolation &&
        it.readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL) }
    EnhancementQuiet(when {
        policy.frameMode == FrameMode.ORIGINAL -> "按文件原始帧率播放。"
        ready.isEmpty() -> "补帧组件尚未就绪，目标已保留。"
        ready.none { it.realtime && it.videoOutput == null } -> "当前支持离线补帧，可从离线任务打开处理后的版本。"
        policy.frameQuality != FrameQuality.BALANCED -> "实时补帧仅在均衡档开启；当前所选质量用于离线处理。"
        PreparationMode.IMMEDIATE !in policy.preparation -> "在预处理中开启边播边增强，可使用实时补帧。"
        policy.frameMode == FrameMode.AUTO -> "按屏幕刷新率与片源帧率选择目标。"
        else -> "目标已保存，实际呈现帧率可在观看时显示。"
    })
    Spacer(Modifier.height(8.dp))
    AnimeEnhancementNavigation(Icons.Outlined.Tune, "补帧质量", policy.frameQuality.label,
        onClick = onOpenQuality)
    AnimeEnhancementNavigation(Icons.Outlined.Memory, "补帧组件",
        if (ready.isEmpty()) "查看安装与支持情况" else ready.joinToString(" · ") { it.title },
        onClick = onOpenEngines)
}

@Composable
internal fun EnhancementFrameOptions(policy: EnhancementPolicy, onChange: (EnhancementPolicy) -> Unit) {
    Text("插帧质量", style = MaterialTheme.typography.titleSmall)
    EnhancementChoices(FrameQuality.entries, policy.frameQuality, label = { it.label },
        onSelect = { onChange(policy.copy(frameQuality = it)) }, description = "插帧质量", columns = 4)
    EnhancementQuiet("只有均衡档可开启实时补帧；其他档位用于兼容的离线处理。")
    Spacer(Modifier.height(12.dp))
    EnhancementValue("切镜保护", "始终启用")
    EnhancementQuiet("识别场景切换，避免在不同镜头之间生成过渡画面。")
}

@Composable
internal fun EnhancementPreparation(content: EnhancementContent, policy: EnhancementPolicy,
    onChange: (EnhancementPolicy) -> Unit) {
    val anime = content == EnhancementContent.ANIME
    PreparationMode.entries.forEach { mode ->
        val label = when (mode) {
            PreparationMode.IMMEDIATE -> if (anime) "边播边增强" else "当前页即时增强"
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
            PreparationMode.IMMEDIATE -> if (anime) "使用兼容的实时画质增强与补帧。" else "原图先显示，增强完成后复用结果。"
            PreparationMode.SMART -> if (!anime) "优先准备即将阅读的页面。"
                else if (platformKind == PlatformKind.ANDROID) "兼容片源可提前准备 5–15 秒画面。"
                else "优先使用已准备的增强版本。"
            PreparationMode.COMPLETE -> if (anime) "使用完整增强版本；在离线任务中发起处理。"
                else "按当前资源预算准备整章增强结果。"
        }, Modifier.padding(start = 34.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
