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
    QUALITY("画质"), FRAME("补帧"), PREPARATION("提前准备"), STATUS("状态"),
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

internal fun EnhancementPage.titleFor(hasSession: Boolean): String =
    if (this == EnhancementPage.STATUS && !hasSession) "显示" else title

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
    val sessionLabel = if (content == EnhancementContent.ANIME) "本次播放" else "这部漫画"
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
    val builtIn = inventory.engines.firstOrNull { it.id == policy.modelId &&
        it.id in setOf(EnhancementEngineIds.ANIME4K_SMALL, EnhancementEngineIds.ANIME4K_MEDIUM) &&
        it.readiness == EngineReadiness.BUILT_IN }
    val selectedTitle = model?.title ?: builtIn?.title
    val supportedScales = enhancementOutputScales(content, policy, inventory)
    val custom = policy.modelId != null
    Text("画质方案", style = MaterialTheme.typography.titleSmall)
    MotionTabs(if (custom) 1 else 0, listOf("使用预设", "自选模型"),
        onSelect = { if (it == 0) {
            if (custom) onChange(policy.copy(modelId = null))
        } else onOpenModels() }, modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp,
        treatment = MotionTabTreatment.Soft, fullWidthIndicator = true,
        track = MaterialTheme.colorScheme.onSurface.copy(alpha = .035f), role = Role.RadioButton)
    if (custom) {
        AnimeEnhancementNavigation(Icons.Outlined.Memory, "当前模型",
            selectedTitle ?: "所选模型不可用 · 点此检查", onClick = onOpenModels)
        EnhancementQuiet("由这个模型决定恢复算法；输出倍率单独设置，不再叠加预设档位。")
        SettingsToggle(if (anime) "播放时画质增强" else "阅读时画质增强",
            policy.tier != QualityTier.ORIGINAL && (!anime || policy.realtimeEnhancementEnabled),
            Icons.Outlined.AutoAwesome, onCheckedChange = { enabled ->
                onChange(policy.copy(
                    tier = if (enabled) policy.tier.takeUnless { it == QualityTier.ORIGINAL } ?: QualityTier.BALANCED
                        else if (anime) policy.tier else QualityTier.ORIGINAL,
                    realtimeEnhancementEnabled = if (anime) enabled else policy.realtimeEnhancementEnabled,
                    preparation = if (anime && enabled) policy.preparation + PreparationMode.IMMEDIATE else policy.preparation,
                ))
            })
        EnhancementQuiet(if (anime) "关闭后显示原画，模型、倍率与已备结果保留；补帧独立设置。"
            else "关闭后显示原图，模型、倍率与已完成的增强结果保留。")
        if (!anime && policy.tier != QualityTier.ORIGINAL &&
            PreparationMode.IMMEDIATE !in policy.preparation && PreparationMode.SMART !in policy.preparation)
            EnhancementQuiet("当前仅显示已备结果；需要处理新页面时，可在提前准备中开启。")
    } else {
        EnhancementChoices(QualityTier.entries, policy.tier,
            label = { if (!anime && it == QualityTier.ORIGINAL) "原图" else it.label },
            onSelect = { tier ->
                onChange(policy.copy(tier = tier, modelId = null,
                    realtimeEnhancementEnabled = if (anime) tier != QualityTier.ORIGINAL else policy.realtimeEnhancementEnabled,
                    preparation = if (anime && tier != QualityTier.ORIGINAL)
                        policy.preparation + PreparationMode.IMMEDIATE else policy.preparation))
            }, description = "切换预设算法，保持输出倍率")
        val preset = enhancementPresetDescription(content, policy, inventory)
        EnhancementValue("预设算法", preset.algorithm, preset.detail)
        if (preset.unavailable) AnimeEnhancementNavigation(Icons.Outlined.Memory, "准备兼容模型",
            "查看模型与运行组件", onClick = onOpenModels)
    }
    if (anime && !custom && policy.tier != QualityTier.ORIGINAL) {
        SettingsToggle("播放时画质增强", policy.realtimeEnhancementEnabled, Icons.Outlined.AutoAwesome,
            onCheckedChange = { onChange(policy.copy(realtimeEnhancementEnabled = it,
                preparation = if (it) policy.preparation + PreparationMode.IMMEDIATE else policy.preparation)) })
        EnhancementQuiet("只控制画质，补帧独立设置。关闭后显示原画，已备结果保留。")
    }
    Spacer(Modifier.height(8.dp))
    AnimeEnhancementNavigation(Icons.Outlined.Tune, "输出与策略",
        listOfNotNull(when {
            policy.scale !in supportedScales -> "${policy.scale}× · 当前不可用"
            policy.scale == 1 -> "1× 原尺寸修复"
            else -> "${policy.scale}×"
        },
            policy.style.label.takeUnless { anime }, "固定模型".takeIf {
                anime && policy.modelId == null && policy.tier == QualityTier.AUTO && policy.locked
            }).joinToString(" · "),
        onClick = onOpenOptions)
}

@Composable
internal fun EnhancementQualityOptions(content: EnhancementContent, policy: EnhancementPolicy,
    inventory: EngineInventory, onChange: (EnhancementPolicy) -> Unit) {
    val anime = content == EnhancementContent.ANIME
    val supported = enhancementOutputScales(content, policy, inventory)
    Text("输出倍率", style = MaterialTheme.typography.titleSmall)
    if (supported.isNotEmpty()) EnhancementChoices(supported, policy.scale,
        label = { if (it == 1) "1× 修复" else "$it×" },
        onSelect = { onChange(policy.copy(scale = it)) }, description = "模型支持的输出倍率")
    if (policy.scale !in supported) EnhancementQuiet(when {
        supported.isEmpty() -> "已保留 ${policy.scale}× 设置；当前模型与运行组件没有可用倍率，请返回画质模型检查安装或重新选择。"
        else -> "已保留 ${policy.scale}× 设置，当前不支持；请选择上方可用倍率。"
    })
    if (supported.isNotEmpty()) {
        val enlarged = supported.firstOrNull { it > 1 }
        EnhancementQuiet(listOfNotNull(
            "1× 保持原宽高，只做修复。".takeIf { 1 in supported },
            enlarged?.let { "${it}× 将宽和高各扩大 $it 倍，像素总量为 ${it.toLong() * it} 倍。" },
            if (anime) "倍率改变输出分辨率，不改变帧率、播放速度或画面适应方式。" else "倍率改变输出分辨率。",
        ).joinToString(""))
    }
    if (policy.tier == QualityTier.ORIGINAL) EnhancementQuiet(if (anime)
        "当前仍播放原画；开启画质增强后使用这个倍率。" else "当前仍显示原图；开启画质增强后使用这个倍率。")
    if (!anime) {
        Spacer(Modifier.height(12.dp))
        Text("漫画类型", style = MaterialTheme.typography.titleSmall)
        EnhancementChoices(ImageStyle.entries, policy.style, label = { it.label },
            onSelect = { onChange(policy.copy(style = it)) }, description = "漫画内容类型", columns = 2)
    }
    if (anime && policy.modelId == null && policy.tier == QualityTier.AUTO) {
        Spacer(Modifier.height(12.dp))
        EnhancementDivider()
        SettingsToggle("固定自动方案模型", policy.locked, Icons.Outlined.Lock,
            onCheckedChange = { onChange(policy.copy(locked = it)) })
        EnhancementQuiet("开启后固定使用 Anime4K M/M，不因处理变慢切到 S/S；内存或温度保护仍会暂用原画。")
    }
}

@Composable
internal fun AnimeEnhancementFrames(policy: EnhancementPolicy, inventory: EngineInventory,
    onChange: (EnhancementPolicy) -> Unit, onOpenEngines: () -> Unit, onOpenQuality: () -> Unit,
    customFps: @Composable () -> Unit) {
    Text("目标帧率", style = MaterialTheme.typography.titleSmall)
    EnhancementChoices(FrameMode.entries, policy.frameMode,
        label = { if (it == FrameMode.ORIGINAL) "原始帧率" else it.label },
        onSelect = { mode -> onChange(policy.copy(frameMode = mode, detectScenes = true,
            realtimeInterpolationEnabled = mode != FrameMode.ORIGINAL,
            preparation = if (mode != FrameMode.ORIGINAL) policy.preparation + PreparationMode.IMMEDIATE
                else policy.preparation)) }, description = "补帧目标")
    if (policy.frameMode == FrameMode.CUSTOM) customFps()
    val ready = inventory.engines.filter { it.isFrameInterpolationEngine() &&
        it.readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL) }
    val missingDesktopRuntime = platformKind == PlatformKind.DESKTOP &&
        inventory.engines.any { it.id == EnhancementEngineIds.DESKTOP_REALTIME_RIFE &&
            it.readiness == EngineReadiness.NOT_INSTALLED }
    EnhancementQuiet(when {
        policy.frameMode == FrameMode.ORIGINAL -> "按文件原始帧率播放。"
        missingDesktopRuntime -> "安装实时播放器组件后，重新打开漫流即可使用当前补帧目标。"
        ready.isEmpty() -> "补帧组件尚未就绪，目标已保留。"
        ready.none { it.realtime && it.videoOutput == null } -> "当前支持离线补帧，可从离线任务打开处理后的版本。"
        policy.frameQuality != FrameQuality.BALANCED -> "实时补帧仅在均衡档开启；当前所选质量用于离线处理。"
        !policy.realtimeInterpolationEnabled -> "播放时补帧已关闭，目标帧率保留。"
        policy.frameMode == FrameMode.AUTO -> "按屏幕刷新率与片源帧率选择目标。"
        else -> "目标已保存，当前输出帧率可在观看时显示。"
    })
    if (policy.frameMode != FrameMode.ORIGINAL) {
        SettingsToggle("播放时补帧", policy.realtimeInterpolationEnabled, Icons.Outlined.SlowMotionVideo,
            onCheckedChange = { onChange(policy.copy(realtimeInterpolationEnabled = it,
                preparation = if (it) policy.preparation + PreparationMode.IMMEDIATE else policy.preparation)) })
        EnhancementQuiet("只控制帧率，不改画质；关闭后按原始帧率播放。")
    }
    Spacer(Modifier.height(8.dp))
    AnimeEnhancementNavigation(Icons.Outlined.Tune, "补帧质量", policy.frameQuality.label,
        onClick = onOpenQuality)
    AnimeEnhancementNavigation(Icons.Outlined.Memory,
        if (missingDesktopRuntime) "安装实时补帧组件" else "补帧组件",
        if (missingDesktopRuntime) "AnimeJaNai 播放内核与 RIFE"
        else if (ready.isEmpty()) "查看安装与支持情况" else ready.joinToString(" · ") { it.title },
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
    inventory: EngineInventory, onChange: (EnhancementPolicy) -> Unit, currentIssue: String? = null) {
    val anime = content == EnhancementContent.ANIME
    fun setMode(mode: PreparationMode, enabled: Boolean) {
        onChange(policy.copy(preparation = if (enabled) policy.preparation + mode else policy.preparation - mode))
    }
    if (!anime) {
        val selected = when {
            PreparationMode.SMART in policy.preparation -> PreparationMode.SMART
            PreparationMode.IMMEDIATE in policy.preparation -> PreparationMode.IMMEDIATE
            else -> null
        }
        Text("阅读时增强", style = MaterialTheme.typography.titleSmall)
        EnhancementChoices(listOf(null, PreparationMode.IMMEDIATE, PreparationMode.SMART), selected,
            label = { when (it) {
                PreparationMode.IMMEDIATE -> "当前页"
                PreparationMode.SMART -> "提前增强"
                else -> "仅用已备"
            } }, onSelect = { mode ->
                val preparation = policy.preparation - PreparationMode.IMMEDIATE - PreparationMode.SMART
                onChange(policy.copy(preparation = if (mode == null) preparation else preparation + mode))
            }, description = "阅读时的增强处理方式")
        EnhancementQuiet(if (policy.tier == QualityTier.ORIGINAL) "当前使用原图；选择增强档位后按此方式处理。"
            else when (selected) {
                PreparationMode.IMMEDIATE -> "原图先显示，只处理正在阅读的页面。"
                PreparationMode.SMART -> "先增强当前页，再准备接下来要看的页面；原图先显示。"
                else -> "不生成新的增强图片；已有增强结果仍可直接显示。"
            })
        return
    }

    Text("当前播放", style = MaterialTheme.typography.titleSmall)
    if (platformKind !in setOf(PlatformKind.ANDROID, PlatformKind.DESKTOP)) {
        EnhancementQuiet("当前平台尚未接入播放时增强与提前处理。")
        return
    }
    EnhancementValue("画质增强", if (policy.realtimeEnhancementEnabled && policy.tier != QualityTier.ORIGINAL)
        "${policy.tier.label} · ${policy.scale}×" else "已关闭")
    EnhancementValue("补帧", if (policy.realtimeInterpolationEnabled && policy.frameMode != FrameMode.ORIGINAL)
        policy.frameMode.label else "已关闭")
    EnhancementQuiet("画质与补帧在各自页面独立开关。原片缓冲只准备可播放内容，不代表已增强。")

    Spacer(Modifier.height(12.dp))
    Text("接下来要看的片段", style = MaterialTheme.typography.titleSmall)
    val onlineEngines = if (platformKind == PlatformKind.ANDROID)
        setOf(EnhancementEngineIds.ANDROID_CUGAN_VIDEO, EnhancementEngineIds.ANDROID_VIDEO_ONNX)
        else setOf(EnhancementEngineIds.VIDEOJANAI)
    val compatible = inventory.models.any { model ->
        model.engineId in onlineEngines && EngineContent.VIDEO in model.contents &&
            model.nativeScale == policy.scale && (policy.modelId == null || model.id == policy.modelId) &&
            inventory.engines.any { it.id == model.engineId &&
                it.readiness in setOf(EngineReadiness.BUILT_IN, EngineReadiness.READY_FOR_TRIAL) }
    }
    val frameEngine = if (platformKind == PlatformKind.ANDROID) EnhancementEngineIds.ANDROID_RIFE_VULKAN
        else EnhancementEngineIds.DESKTOP_REALTIME_RIFE
    val realtimeFrames = policy.realtimeInterpolationEnabled && policy.frameMode != FrameMode.ORIGINAL &&
        policy.frameQuality == FrameQuality.BALANCED && inventory.frameModels.any { it.engineId == frameEngine } &&
        (policy.frameMode != FrameMode.CUSTOM || policy.customFps in 1..(if (platformKind == PlatformKind.ANDROID) 120 else 240))
    val androidRealtimeModel = platformKind == PlatformKind.ANDROID && compatible &&
        (policy.modelId != null || policy.tier in setOf(QualityTier.FINE, QualityTier.ULTRA))
    val desktopRealtimeModelId = policy.modelId ?: when (policy.tier) {
        QualityTier.FINE -> if (inventory.models.any { it.id == "animejanai-live:performance" })
            "animejanai-live:performance" else "artcnn:C4F16"
        QualityTier.ULTRA -> if (inventory.models.any { it.id == "animejanai-live:balanced" })
            "animejanai-live:balanced" else "artcnn:C4F32"
        else -> null
    }
    val desktopRealtimeModel = platformKind == PlatformKind.DESKTOP && policy.scale in 1..2 &&
        inventory.models.any { model ->
            model.engineId in setOf(EnhancementEngineIds.DESKTOP_ARTCNN, EnhancementEngineIds.DESKTOP_REALTIME) &&
                model.nativeScale == policy.scale && model.id == desktopRealtimeModelId
        }
    val realtimeModel = androidRealtimeModel || desktopRealtimeModel
    val lightRealtimeOnly = policy.tier == QualityTier.LIGHT && policy.modelId == null
    val canPrepare = compatible && policy.tier != QualityTier.ORIGINAL && !lightRealtimeOnly &&
        policy.realtimeEnhancementEnabled && !realtimeFrames && !realtimeModel
    val requested = PreparationMode.SMART in policy.preparation
    SettingsToggle("提前增强后续片段", requested, Icons.Outlined.AutoAwesome,
        onCheckedChange = { setMode(PreparationMode.SMART, it) }, enabled = canPrepare || requested)
    EnhancementQuiet(when {
        policy.tier == QualityTier.ORIGINAL -> "当前使用原画；选择增强档位后才会准备增强片段。"
        !policy.realtimeEnhancementEnabled -> "播放时画质增强已关闭，提前增强暂停；原片缓冲继续。"
        realtimeFrames -> "当前实时补帧与提前增强不能同时运行；优先补帧，原片缓冲继续。"
        realtimeModel -> "当前模型在播放时直接增强，暂不同时准备增强片段；原片缓冲继续。"
        lightRealtimeOnly -> "轻量自动档只做播放时增强；提前增强可在均衡或更高档位使用。"
        currentIssue != null -> "当前：$currentIssue"
        !compatible -> if (policy.modelId == null) "尚无兼容的已安装模型；可在画质模型中选择，当前继续实时处理或原画。"
            else "所选模型不提供此倍率的在线片段处理；可更换模型，或使用实时／离线增强。"
        !requested -> "已关闭提前增强；当前画面仍按画质页选择处理。"
        else -> "后台准备后续画面，当前播放优先；负载高或缓冲不足时等待，不保证同时运行。"
    })
    EnhancementQuiet("同一画面优先使用已备增强结果，不重复增强；未备好时使用可用的实时增强或原画。原片和增强结果分别缓存，关闭效果不会清空缓存。")
}
