package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.player.Anime4KOptionalModels
import dev.mangaflow.enhancement.EnhancementCenter
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.EnhancementPolicy
import dev.mangaflow.enhancement.QualityTier
import dev.mangaflow.enhancement.enhancementModelSelectionPolicy
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.platform.releasePickedDocuments
import dev.mangaflow.ui.platform.rememberOpenDocumentPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ModelChoice(
    val key: String,
    val model: EngineModel?,
    val offer: EnhancementModelOffer?,
    val profile: EngineDescriptor? = null,
) {
    val title: String get() = offer?.title ?: model?.title ?: checkNotNull(profile).title
    val engineId: String get() = model?.engineId ?: offer?.engineId ?: checkNotNull(profile).id
    val builtIn: Boolean get() = profile?.readiness == EngineReadiness.BUILT_IN
    val selectionId: String? get() = model?.id ?: profile?.id?.takeIf { builtIn }
    val scales: Set<Int> get() = profile?.supportedScales ?: setOf(model?.nativeScale ?: checkNotNull(offer).scale)
    val scaleLabel: String get() = scales.sorted().joinToString(" / ") { "$it×" }
    val sizeLabel: String? get() = model?.let { enhancementBytes(it.bytes) } ?: offer?.sizeLabel
        ?: profile?.downloadBytes?.let(::enhancementBytes)
    val progressKey: String? get() = offer?.let { "model:${it.id}" } ?: profile?.id

    fun stateLabel(selectedModel: String?, pending: EngineProgress?): String = when {
        selectionId != null && selectionId == selectedModel -> if (builtIn) "内置 · 已选用" else "已选用"
        builtIn -> "内置 · 可直接选用"
        model != null -> "已安装"
        pending?.failed == true -> "下载失败 · 可重试"
        pending?.installing() == true -> pending.shortProgress()
        profile != null && !profile.canInstall -> "模型待检查"
        else -> "可下载"
    }
}

private fun EngineDescriptor.matches(content: EnhancementContent): Boolean =
    if (content == EnhancementContent.ANIME) EngineContent.VIDEO in contents
    else contents.any { it != EngineContent.VIDEO }

private fun modelChoices(inventory: EngineInventory, content: EnhancementContent): List<ModelChoice> {
    val compatibleEngines = inventory.engines.filter { it.matches(content) }
    val engines = compatibleEngines.map { it.id }.toSet()
    val profiles = compatibleEngines.filter {
        it.id in setOf(EnhancementEngineIds.ANIME4K_SMALL, EnhancementEngineIds.ANIME4K_MEDIUM) ||
            Anime4KOptionalModels.supports(it.id)
    }
    val profileIds = profiles.map { it.id }.toSet()
    val models = inventory.models.filter {
        it.engineId in engines && (EngineContent.VIDEO in it.contents) == (content == EnhancementContent.ANIME)
    }.distinctBy { it.engineId to it.sha256 }
    val offers = enhancementModelOffers.filter {
        it.engineId in engines && (EngineContent.VIDEO in it.contents) == (content == EnhancementContent.ANIME)
    }.distinctBy { it.engineId to it.sha256 }
    val published = offers.map { it.engineId to it.sha256 }.toSet()
    val files = (offers.map { offer ->
        ModelChoice("offer:${offer.id}", models.firstOrNull {
            it.engineId == offer.engineId && it.sha256 == offer.sha256
        }, offer)
    } + models.filter { it.engineId !in profileIds && (it.engineId to it.sha256) !in published }.map {
        ModelChoice("model:${it.id}", it, null)
    }).sortedWith(compareByDescending<ModelChoice> { it.model != null }.thenBy { it.title })
    // Built-in profiles have no separate model file. Optional profiles keep the same page key
    // while their verified installation appears, so the download action can become selection.
    return profiles.map { profile ->
        ModelChoice("engine:${profile.id}", models.firstOrNull { it.engineId == profile.id }, null, profile)
    } + files
}

private fun EngineProgress.installing(): Boolean = !failed && stage in setOf(
    EngineStage.DOWNLOAD, EngineStage.VERIFY, EngineStage.INSTALL,
)

private fun EngineProgress.shortProgress(): String {
    if (failed) return "未完成 · 可重试"
    val percent = total?.takeIf { it > 0L }?.let { ((completed.toDouble() / it) * 100).toInt().coerceIn(0, 100) }
    return engineStageLabel(stage) + if (percent != null && stage != EngineStage.COMPLETE) " $percent%" else ""
}

@Composable
internal fun EnhancementModelList(
    inventory: EngineInventory,
    progress: Map<String, EngineProgress>,
    content: EnhancementContent,
    policy: EnhancementPolicy,
    onSelectModel: (EnhancementPolicy) -> Unit,
    onUsePresets: () -> Unit,
    onOpenModel: (String) -> Unit,
    onOpenEngines: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
) {
    val choices = remember(inventory, content) { modelChoices(inventory, content) }
    val selectedModel = policy.modelId
    ModelChoiceRow("使用预设", "返回画质页选择预设算法；保留 ${policy.scale}× 输出设置",
        selectedModel == null, true, onClick = onUsePresets)
    ModelListTools("可选模型", onImport, onRefresh)
    EnhancementQuiet("选用后按具体模型处理。通过「使用预设」切回档位选择。")
    if (content != EnhancementContent.ANIME)
        EnhancementQuiet("先按黑白／彩色与原图尺寸选。MB、MiB 是文件体积，不是画质排名；放大更多也不代表细节一定更准。")
    if (content == EnhancementContent.ANIME && choices.any { it.engineId == EnhancementEngineIds.ANIME4K_MEDIUM && it.builtIn })
        EnhancementQuiet("日常观看可先选均衡（Anime4K M/M）。掉帧时试轻量（S/S）；有性能余量再比较其他模型。")
    if (choices.isEmpty()) EnhancementQuiet("还没有可选模型，可先准备运行组件或导入模型。")
    choices.forEach { choice ->
        val pending = choice.progressKey?.let { progress[it] }
        val selectionId = choice.selectionId
        val selection = selectionId?.let { enhancementModelSelectionPolicy(content, policy, inventory, it) }
        val selected = selectionId != null && selectionId == selectedModel
        val unavailable = selectionId != null && selection == null
        val summary = listOfNotNull("当前不可选".takeIf { unavailable }, anime4kSizeLabel(choice.engineId), choice.scaleLabel, choice.sizeLabel,
            choice.stateLabel(selectedModel, pending).takeUnless { unavailable }).joinToString(" · ")
        key(choice.key) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ModelChoiceRow(choice.title, summary, selected, selection != null,
                    onClick = { if (selection != null) onSelectModel(selection) else onOpenModel(choice.key) },
                    modifier = Modifier.weight(1f), failed = pending?.failed == true && selectionId == null,
                    actionLabel = selection?.scale?.takeIf { it != policy.scale }?.let { "选用并改为 ${it}×" },
                    detailsOnly = selectionId != null && selection == null,
                    guidance = modelGuidance(choice, content)?.summary)
                MotionIconButton(onClick = { onOpenModel(choice.key) }, modifier = Modifier.size(44.dp),
                    tooltip = "${choice.title} · 详情") {
                    Icon(Icons.Outlined.Info, "${choice.title} · 详情", Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (selectedModel != null && choices.none { it.selectionId == selectedModel })
        EnhancementQuiet("所选模型当前不可用，可重新检查或选择其他模型。")
    progress["inventory"]?.takeIf { it.failed }?.detail?.let { EnhancementQuiet(it, error = true) }
    EnhancementDivider()
    AnimeEnhancementNavigation(Icons.Outlined.Memory, "运行组件", onClick = onOpenEngines)
}

private fun anime4kSizeLabel(engineId: String): String? = when (engineId) {
    EnhancementEngineIds.ANIME4K_SMALL -> "小型模型"
    EnhancementEngineIds.ANIME4K_MEDIUM -> "中型模型"
    "anime4k-optional-l" -> "大型模型"
    "anime4k-optional-vl" -> "更大型模型"
    "anime4k-optional-ul" -> "本系列最大模型"
    else -> null
}

private data class ModelGuidance(val summary: String, val detail: String, val sourceUrl: String)

private fun modelGuidance(choice: ModelChoice, content: EnhancementContent): ModelGuidance? =
    if (content == EnhancementContent.ANIME) animeModelGuidance(choice) else mangaModelGuidance(choice)

private fun animeModelGuidance(choice: ModelChoice): ModelGuidance? {
    val anime4kSource = "https://github.com/bloc97/Anime4K/blob/master/md/GLSL_Instructions_Advanced.md"
    val animeJaNaiSource = "https://github.com/the-database/mpv-AnimeJaNai"
    val artCnnSource = "https://github.com/Artoriuz/ArtCNN"
    return when (choice.model?.id) {
        "artcnn:C4F16" -> ModelGuidance("实时 2× 亮度超分 · ArtCNN 轻量版",
            "重建动画线条和亮度细节。C4F16 计算量较低，想比较 ArtCNN 时可从它试起。文件标注的 2× 是模型倍率，窗口大小由播放器决定。", artCnnSource)
        "artcnn:C4F32" -> ModelGuidance("实时 2× 亮度超分 · ArtCNN 细节版",
            "与 C4F16 同属 2× 亮度超分，C4F32 使用更多特征通道，对显卡要求更高。可用同一片段比较线条、纹理和掉帧。", artCnnSource)
        "animejanai-live:performance" -> ModelGuidance("实时 2× 动画修复 · 优先速度",
            "HD V3.1 Performance 通过 Windows DirectML 放大和修复动画画面。想用 AnimeJaNai 实时播放时，先用这一档确认本机流畅度。", animeJaNaiSource)
        "animejanai-live:balanced" -> ModelGuidance("实时 2× 动画修复 · 侧重细节",
            "HD V3.1 Balanced 使用比 Performance 更大的网络，保留细节的能力与计算开销都更高。适合 Performance 还有余量时比较。", animeJaNaiSource)
        "animejanai-live:temporalfix-s1" -> ModelGuidance("实时 1× 时序修复 · 原尺寸",
            "利用相邻帧减轻细线抖动或纹理闪烁，保持原分辨率和源帧率。适合想稳定画面细节时试用。", "https://github.com/pifroggi/vs_temporalfix")
        else -> when (choice.engineId) {
            EnhancementEngineIds.ANIME4K_SMALL -> ModelGuidance("轻量预设 · 从流畅度优先选起",
                "Anime4K S/S 使用小型恢复和上采样网络。1× 修复原尺寸，2× 可启用超分着色器，适合显卡余量少或高分辨率播放时先试。", anime4kSource)
            EnhancementEngineIds.ANIME4K_MEDIUM -> ModelGuidance("均衡预设 · 推荐先试",
                "Anime4K M/M 使用中型恢复和上采样网络，适合日常动画播放。1× 保留原尺寸，2× 可启用超分着色器。掉帧时换 S/S。", anime4kSource)
            "anime4k-optional-l", "anime4k-optional-vl", "anime4k-optional-ul" -> {
                val tier = when (choice.engineId) {
                    "anime4k-optional-l" -> "L · 高负载"
                    "anime4k-optional-vl" -> "VL · 更高负载"
                    else -> "UL · 系列最高负载"
                }
                ModelGuidance("实时动画修复 · $tier",
                    "Anime4K 的 S → M → L → VL → UL 逐级增大，通常也更耗显卡。更大网络在部分片段可恢复更多细节，建议同场景比较线条、纹理和流畅度。", anime4kSource)
            }
            EnhancementEngineIds.VIDEOJANAI -> ModelGuidance("片段预处理／离线增强 · 先算后播",
                "使用 VideoJaNai 在后台准备片段或生成单独的增强文件，适合能接受等待和缓存空间开销的场景。效果、倍率和耗时取决于所选权重。",
                "https://github.com/the-database/VideoJaNai")
            EnhancementEngineIds.ANDROID_CUGAN_VIDEO -> ModelGuidance("安卓 2× 超分 · 保留原有纹理",
                "Real-CUGAN NOSE 采用 2× 无降噪权重与 Vulkan。优先试动画线条和压缩痕迹较少的片段，实时速度取决于手机 GPU 与片源。",
                "https://github.com/nihui/realcugan-ncnn-vulkan")
            else -> null
        }
    }
}

/** Describes published families; it never ranks weights or changes the selected model. */
private fun mangaModelGuidance(choice: ModelChoice): ModelGuidance? {
    val title = choice.title
    val mangaSource = "https://github.com/the-database/MangaJaNai"
    return when {
        choice.offer?.id == "animesharp-v2-mosr-soft-fp32" -> ModelGuidance(
            "较干净的彩页／插画 · 柔和修复",
            "Soft 适合较干净的图，偏向保留原有虚化和柔和层次；严重压缩痕迹不一定能清干净。" +
                "当前是 MoSR 2× 版本，同系列还有其他网络与 Sharp 版本，不能按文件大小混排画质。黑白页先比较文字和网点是否保真。",
            "https://huggingface.co/Kim2091/AnimeSharpV2")
        choice.offer?.id == "realesrgan-anime-6b-fp32" -> ModelGuidance(
            "动漫／插画 · 需要更大输出时比较",
            "这是原生 4× 动漫插画模型。6B 指 6 个 RRDB 网络模块，不是 60 亿参数或画质等级。" +
                "4× 会生成原图 16 倍像素；同页 2× 只有 4 倍像素。黑白漫画要特别比较小字、网点和细线，不能只看输出尺寸。",
            "https://github.com/xinntao/Real-ESRGAN/blob/master/docs/model_zoo.md")
        choice.engineId == EnhancementEngineIds.ANDROID_CUGAN -> ModelGuidance(
            "彩页／条漫 · 无降噪 2×",
            "当前接入的是 NOSE 包中的 2× 无降噪权重，适合先比较原本较干净的彩页或条漫。" +
                "它不会因为文件小就自动更差，也不能当成强力去噪模型；处理速度取决于实际 GPU、驱动和页面。",
            "https://github.com/nihui/realcugan-ncnn-vulkan")
        choice.engineId == EnhancementEngineIds.MANGAJANAI && title.contains("IllustrationJaNai", true) -> {
            val variant = when {
                title.contains("V3denoise", true) -> "清理网点噪声" to
                    "V3denoise 偏向清理彩页的网点噪声，可能同时抹掉细纹理。"
                title.contains("V3detail", true) -> "保留纹理细节" to
                    "V3detail 偏向保留纹理，不清理网点噪声；部分图可能产生过多纹理。"
                else -> "彩色插画修复" to "用于彩页、封面和插画；具体修复倾向以此版本的作者说明为准。"
            }
            ModelGuidance("彩页／封面 · ${variant.first}", variant.second +
                "需要降噪时比较 denoise，原图干净时比较 detail。SPAN、FDAT、DAT、HAT 是不同网络，不能按 MB 排名。" +
                "先用同一页对比文字、颜色和纹理，再按本机耗时决定。", "$mangaSource/releases")
        }
        choice.engineId == EnhancementEngineIds.MANGAJANAI && title.contains("MangaJaNai", true) -> {
            val height = Regex("(?:^|[_ -])(1200|1300|1400|1500|1600|1920|2048)p(?:[_ -]|$)",
                RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)
            ModelGuidance(if (height != null) "黑白漫画 · 原图高度约 ${height}px" else "黑白漫画 · 按原图高度选",
                "1200p、1600p、2048p 等表示适合的原图整页高度，选最接近原图高度的型号，不是数字越大越好。" +
                    "不要用分块高度或增强后尺寸来选。这个系列针对黑白漫画的文字与网点；彩页应比较 IllustrationJaNai。" +
                    "同系列 2× 可先用于连续阅读，4× 留给需要更大输出且能接受处理等待的情况。", mangaSource)
        }
        else -> null
    }
}

@Composable
private fun ModelListTools(title: String, onImport: () -> Unit, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (platformKind != PlatformKind.IOS) MotionIconButton(onImport, Modifier.size(44.dp), tooltip = "导入模型") {
            Icon(Icons.Outlined.UploadFile, "导入模型", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MotionIconButton(onRefresh, Modifier.size(44.dp), tooltip = "重新检查安装") {
            Icon(Icons.Outlined.Refresh, "重新检查安装", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelChoiceRow(
    title: String,
    summary: String,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
    actionLabel: String? = null,
    detailsOnly: Boolean = false,
    guidance: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick, modifier.fillMaxWidth().heightIn(min = 56.dp).semantics {
        if (selectable) { role = Role.RadioButton; this.selected = selected }
    }, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface,
            containerColor = if (selected) colors.primary.copy(alpha = .07f) else Color.Transparent),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Icon(when {
            selected -> Icons.Outlined.RadioButtonChecked
            selectable -> Icons.Outlined.RadioButtonUnchecked
            failed -> Icons.Outlined.ErrorOutline
            detailsOnly -> Icons.Outlined.Info
            else -> Icons.Outlined.Download
        }, null, Modifier.size(19.dp), tint = when {
            selected -> colors.primary
            failed -> colors.error
            else -> colors.onSurfaceVariant
        })
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            guidance?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(summary, style = MaterialTheme.typography.labelSmall,
                color = if (failed) colors.error else colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            actionLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.primary) }
        }
    }
}

@Composable
internal fun EnhancementModelDetail(
    inventory: EngineInventory,
    progress: Map<String, EngineProgress>,
    content: EnhancementContent,
    modelKey: String?,
    policy: EnhancementPolicy,
    onSelectModel: (EnhancementPolicy) -> Unit,
    center: EnhancementCenter,
    onOpenEngine: (String) -> Unit,
) {
    val choice = remember(inventory, content, modelKey) { modelChoices(inventory, content).firstOrNull { it.key == modelKey } }
    if (choice == null) {
        EnhancementQuiet("模型信息暂不可用，返回模型列表后重新检查。")
        return
    }
    val model = choice.model
    val offer = choice.offer
    val engine = choice.profile ?: inventory.engines.firstOrNull { it.id == choice.engineId }
    val pending = choice.progressKey?.let { progress[it] }
    val selectionId = choice.selectionId
    val selectedModel = policy.modelId
    val selection = selectionId?.let { enhancementModelSelectionPolicy(content, policy, inventory, it) }
    val selected = selectionId != null && selectionId == selectedModel && selection?.scale == policy.scale
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var downloading by remember(modelKey) { mutableStateOf(false) }
    var notice by remember(modelKey) { mutableStateOf<String?>(null) }
    var noticeFailed by remember(modelKey) { mutableStateOf(false) }
    val busy = downloading || pending?.installing() == true
    Text(choice.title, style = MaterialTheme.typography.titleMedium)
    EnhancementQuiet(listOfNotNull(choice.scaleLabel, choice.sizeLabel,
        choice.stateLabel(selectedModel, pending)).joinToString(" · "))
    val guidance = modelGuidance(choice, content)
    if (content == EnhancementContent.ANIME) guidance?.let { EnhancementQuiet(it.detail) }
    if (selectionId != null) {
        MotionButton({ selection?.let(onSelectModel) }, modifier = Modifier.fillMaxWidth(), enabled = selection != null && !selected) {
            Icon(if (selected) Icons.Outlined.Check else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(when {
                selection == null -> "当前无法选用"
                selected -> "已选用此模型"
                selection.scale != policy.scale -> "选用并改为 ${selection.scale}×"
                else -> "使用此模型"
            })
        }
    } else if (offer != null) {
        MotionButton(onClick = {
            downloading = true; notice = null; noticeFailed = false
            val receipt = center.downloadModel(offer)
            scope.launch {
                try {
                    receipt.await()
                    notice = "已安装，可点击使用此模型。"
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { notice = failure.message ?: "下载未完成，请重试。"; noticeFailed = true }
                finally { downloading = false }
            }
        }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
            Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (busy) pending?.shortProgress() ?: "准备下载" else if (pending?.failed == true || noticeFailed) "重试下载" else "下载并安装")
        }
    } else if (engine != null && (engine.canInstall || busy)) {
        MotionButton({ center.installEngine(engine.id) }, modifier = Modifier.fillMaxWidth(),
            enabled = engine.canInstall && !busy) {
            Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (busy) pending?.shortProgress() ?: "准备下载"
                else if (pending?.failed == true) "重试下载" else "下载并安装")
        }
    } else if (choice.profile != null) {
        MotionButton({ center.refreshInventory() }, modifier = Modifier.fillMaxWidth()) { Text("重新检查安装") }
    }
    val feedback = pending?.takeIf { it.failed }?.detail ?: notice
        ?: "已安装，可点击使用此模型。".takeIf {
            choice.profile != null && model != null && pending?.stage == EngineStage.COMPLETE && selectionId != selectedModel
        }
    feedback?.let { EnhancementQuiet(it, error = pending?.failed == true || noticeFailed) }
    if (busy && pending?.detail != null && !pending.failed) EnhancementQuiet(pending.detail)
    when {
        selectionId != null && selection == null ->
            EnhancementQuiet("模型与当前内容或运行组件不兼容；请检查适用内容与运行组件，原设置会保留。")
        selection != null && selection.scale != policy.scale ->
            EnhancementQuiet("当前输出为 ${policy.scale}×；点击选用会同时保存此模型与 ${selection.scale}× 输出。")
        policy.scale !in choice.scales ->
            EnhancementQuiet("当前输出为 ${policy.scale}×，此模型支持 ${choice.scaleLabel}；安装就绪后可选用兼容倍率。")
    }
    if (policy.tier == QualityTier.ORIGINAL)
        EnhancementQuiet("画质增强当前关闭；选用后可回画质页开启，模型与倍率会保留。")
    if (engine != null) AnimeEnhancementNavigation(Icons.Outlined.Memory, "运行组件",
        "${engine.title} · ${engineReadinessLabel(engine.readiness)}", onClick = { onOpenEngine(engine.id) })
    EnhancementDivider()
    if (content != EnhancementContent.ANIME) {
        guidance?.let { EnhancementQuiet(it.detail) }
        if (guidance == null) EnhancementQuiet("此模型没有专门的选型说明，请按原作者标注的适用画面和原生倍率选择；导入时填写的内容类型不代表已验证效果。")
        EnhancementQuiet("文件体积不等于运行内存或画质。FP16／FP32 是数值精度，2×／4× 是宽高倍率，都不是画质分数；以同页对比和实际耗时取舍。")
    } else if (guidance == null) {
        EnhancementQuiet("可按作者标注的适用片源、原生倍率和运行组件选择，再用同一片段比较效果。")
    }
    (offer?.detail ?: choice.profile?.detail)?.let { EnhancementQuiet(it) }
    if (choice.profile != null) EnhancementQuiet("用于即时播放增强，不生成完整离线增强视频。")
    EnhancementValue("适用内容", (model?.contents ?: offer?.contents ?: choice.profile?.contents.orEmpty())
        .joinToString(" · ", transform = ::modelContentLabel))
    (model?.version ?: choice.profile?.version)?.let { EnhancementValue("版本", it) }
    EnhancementValue("许可", model?.license ?: offer?.license ?: choice.profile?.license.orEmpty())
    val source = offer?.sourceUrl ?: engine?.sourceUrl
    if (!source.isNullOrBlank()) MotionTextButton({ uriHandler.openUri(source) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(if (offer != null) "模型来源" else "组件来源")
    }
    if (guidance != null && guidance.sourceUrl != source) MotionTextButton(
        { uriHandler.openUri(guidance.sourceUrl) }, contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text("作者选型说明")
    }
    (model?.sha256 ?: offer?.sha256)?.let { digest ->
        Text("SHA-256", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(digest, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Video enhancement pipelines can chain RIFE without being interpolation models themselves. */
internal fun EngineDescriptor.isFrameInterpolationEngine(): Boolean = supportsInterpolation && id in setOf(
    EnhancementEngineIds.ANDROID_RIFE, EnhancementEngineIds.ANDROID_RIFE_VULKAN,
    EnhancementEngineIds.DESKTOP_REALTIME_RIFE, EnhancementEngineIds.VIDEOJANAI,
)

@Composable
internal fun EnhancementEngineList(
    inventory: EngineInventory,
    progress: Map<String, EngineProgress>,
    content: EnhancementContent,
    frames: Boolean,
    onOpenEngine: (String) -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
) {
    val engines = inventory.engines.filter { engine -> engine.matches(content) &&
        (if (frames) engine.isFrameInterpolationEngine() else !engine.supportsInterpolation ||
            engine.supportedScales.any { it > 1 } || inventory.models.any { it.engineId == engine.id }) }
    ModelListTools("可用组件", onImport, onRefresh)
    if (engines.isEmpty()) EnhancementQuiet(if (frames) "当前没有可用的补帧组件信息。" else "尚未取得运行组件信息。")
    engines.forEach { engine ->
        val pending = progress[engine.id]
        val status = pending?.takeIf { it.failed || it.installing() }?.shortProgress()
            ?: engineReadinessLabel(engine.readiness)
        val mode = if (engine.realtime) "实时" else if (EngineContent.VIDEO in engine.contents) "离线处理" else "图像处理"
        AnimeEnhancementNavigation(Icons.Outlined.Memory, engine.title, "$mode · $status", onClick = { onOpenEngine(engine.id) })
    }
    progress["inventory"]?.takeIf { it.failed }?.detail?.let { EnhancementQuiet(it, error = true) }
}

@Composable
internal fun EnhancementEngineDetail(
    inventory: EngineInventory,
    progress: Map<String, EngineProgress>,
    engineId: String?,
    onInstall: (String) -> Unit,
    onImport: () -> Unit,
) {
    val engine = inventory.engines.firstOrNull { it.id == engineId }
    if (engine == null) {
        EnhancementQuiet("组件信息暂不可用，返回列表后重新检查。")
        return
    }
    val pending = progress[engine.id]
    val busy = pending?.installing() == true
    val uriHandler = LocalUriHandler.current
    Text(engine.title, style = MaterialTheme.typography.titleMedium)
    EnhancementQuiet(engineReadinessLabel(engine.readiness))
    if (engine.canInstall || busy) MotionButton({ onInstall(engine.id) }, modifier = Modifier.fillMaxWidth(), enabled = engine.canInstall && !busy) {
        Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(if (busy) pending?.shortProgress() ?: "正在安装" else if (pending?.failed == true) "重试安装"
            else if (engine.detail.startsWith("组件需要更新")) "更新组件" else "安装组件")
    }
    pending?.let { value ->
        if (value.detail != null) EnhancementQuiet(value.detail, error = value.failed)
        else if (value.failed) EnhancementQuiet("安装未完成，请重试。", error = true)
    }
    if (engine.id in setOf(EnhancementEngineIds.ANDROID_ONNX, EnhancementEngineIds.ANDROID_VIDEO_ONNX,
            EnhancementEngineIds.ANDROID_VIDEO_REALTIME, EnhancementEngineIds.ANDROID_RIFE,
            EnhancementEngineIds.MANGAJANAI, EnhancementEngineIds.VIDEOJANAI)) {
        AnimeEnhancementNavigation(Icons.Outlined.UploadFile, "导入 ONNX 模型", onClick = onImport)
    }
    EnhancementDivider()
    EnhancementQuiet(engine.detail)
    EnhancementValue("处理方式", if (engine.realtime) "实时" else if (EngineContent.VIDEO in engine.contents) "离线处理" else "图像处理")
    EnhancementValue("支持倍率", engine.supportedScales.sorted().joinToString(" / ") { "$it×" }.ifBlank { "—" })
    engine.downloadBytes?.let { EnhancementValue("组件下载", enhancementBytes(it)) }
    EnhancementValue("版本", engine.version)
    EnhancementValue("许可", engine.license)
    if (engine.sourceUrl.isNotBlank()) MotionTextButton({ uriHandler.openUri(engine.sourceUrl) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("官方来源")
    }
    if (engine.isFrameInterpolationEngine()) {
        val models = inventory.frameModels.filter { it.engineId == engine.id }
        EnhancementDivider()
        Text("已安装的补帧模型", style = MaterialTheme.typography.titleSmall)
        if (models.isEmpty()) EnhancementQuiet("此组件尚未登记补帧模型。")
        models.forEach { model ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(model.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${model.version} · ${enhancementBytes(model.bytes)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        EnhancementQuiet("按所选补帧质量匹配兼容模型；安装不代表已在设备上完成试用。")
    }
}

@Composable
internal fun EnhancementModelImport(center: EnhancementCenter, video: Boolean = false) {
    var scale by rememberSaveable(video) { mutableIntStateOf(2) }
    var style by rememberSaveable { mutableStateOf(EngineContent.BLACK_WHITE) }
    var importing by remember(center, video) { mutableStateOf(false) }
    var notice by remember(center, video) { mutableStateOf<String?>(null) }
    var failed by remember(center, video) { mutableStateOf(false) }
    var attached by remember(center, video) { mutableStateOf(true) }
    DisposableEffect(center, video) { onDispose { attached = false } }
    val scope = rememberCoroutineScope()
    val picker = rememberOpenDocumentPicker { uri ->
        if (uri != null && !importing) {
            importing = true; notice = null; failed = false
            val receipt = center.importModel(uri, scale, if (video) EngineContent.VIDEO else style)
            // Enter the non-cancellable wait before the page can leave and cancel its UI scope.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    try {
                        val model = receipt.await()
                        if (attached) notice = if (model.engineId == EnhancementEngineIds.ANDROID_RIFE)
                            "${model.title} 已导入，将按补帧质量匹配。" else "${model.title} 已导入，返回模型列表后可选用。"
                    } catch (error: Exception) {
                        if (attached) { notice = error.message ?: "模型未能导入，可重新选择文件。"; failed = true }
                    } finally {
                        releasePickedDocuments(listOf(uri))
                        if (attached) importing = false
                    }
                }
            }
        } else if (uri != null) releasePickedDocuments(listOf(uri))
    }
    EnhancementQuiet(if (video) "选择 FP32 NCHW RGB ONNX 模型，并按模型说明设置原生倍率。"
        else "按模型说明设置原生倍率与适用内容，再选择 ONNX 文件。")
    Text("原生倍率", style = MaterialTheme.typography.labelLarge)
    EnhancementChoices(listOf(1, 2, 4), scale, { "$it×" }, { scale = it }, "模型声明倍率")
    if (!video) {
        Text("适用内容", style = MaterialTheme.typography.labelLarge)
        EnhancementChoices(listOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON), style,
            ::modelContentLabel, { style = it }, "模型适用内容")
    } else if (platformKind == PlatformKind.ANDROID) {
        EnhancementQuiet("RIFE 补帧使用官方包的 rife_v2 FP32 文件，导入时按摘要识别版本。")
    }
    MotionButton({ picker.launch(arrayOf("application/octet-stream", "application/onnx", "*/*")) },
        modifier = Modifier.fillMaxWidth(), enabled = !importing) {
        Icon(Icons.Outlined.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(if (importing) "正在导入" else "选择 .onnx 文件")
    }
    notice?.let { EnhancementQuiet(it, error = failed) }
    EnhancementQuiet("导入完成后加入模型库；实际兼容性在处理内容时确认。请遵循模型原始许可。")
}

private fun modelContentLabel(content: EngineContent): String = when (content) {
    EngineContent.BLACK_WHITE -> "黑白漫画"
    EngineContent.COLOR -> "彩色漫画"
    EngineContent.WEBTOON -> "条漫"
    EngineContent.VIDEO -> "番剧"
}
