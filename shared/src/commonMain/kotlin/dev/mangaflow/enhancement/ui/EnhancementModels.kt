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
import dev.mangaflow.enhancement.EnhancementCenter
import dev.mangaflow.enhancement.EnhancementContent
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

private data class ModelChoice(val key: String, val model: EngineModel?, val offer: EnhancementModelOffer?) {
    val title: String get() = offer?.title ?: checkNotNull(model).title
    val engineId: String get() = model?.engineId ?: checkNotNull(offer).engineId
    val scale: Int get() = model?.nativeScale ?: checkNotNull(offer).scale
}

private fun EngineDescriptor.matches(content: EnhancementContent): Boolean =
    if (content == EnhancementContent.ANIME) EngineContent.VIDEO in contents
    else contents.any { it != EngineContent.VIDEO }

private fun modelChoices(inventory: EngineInventory, content: EnhancementContent): List<ModelChoice> {
    val engines = inventory.engines.filter { it.matches(content) }.map { it.id }.toSet()
    val models = inventory.models.filter {
        it.engineId in engines && (EngineContent.VIDEO in it.contents) == (content == EnhancementContent.ANIME)
    }.distinctBy { it.engineId to it.sha256 }
    val offers = enhancementModelOffers.filter {
        it.engineId in engines && (EngineContent.VIDEO in it.contents) == (content == EnhancementContent.ANIME)
    }.distinctBy { it.engineId to it.sha256 }
    val published = offers.map { it.engineId to it.sha256 }.toSet()
    return (offers.map { offer ->
        ModelChoice("offer:${offer.id}", models.firstOrNull {
            it.engineId == offer.engineId && it.sha256 == offer.sha256
        }, offer)
    } + models.filter { (it.engineId to it.sha256) !in published }.map {
        ModelChoice("model:${it.id}", it, null)
    }).sortedWith(compareByDescending<ModelChoice> { it.model != null }.thenBy { it.title })
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
    selectedModel: String?,
    onSelectModel: (String?) -> Unit,
    onOpenModel: (String) -> Unit,
    onOpenEngines: () -> Unit,
    onImport: () -> Unit,
    onRefresh: () -> Unit,
) {
    val choices = remember(inventory, content) { modelChoices(inventory, content) }
    ModelChoiceRow("自动选择", "按当前档位匹配", selectedModel == null, true, { onSelectModel(null) })
    ModelListTools("可选模型", onImport, onRefresh)
    if (choices.isEmpty()) EnhancementQuiet("还没有可选模型，可先准备运行组件或导入模型。")
    choices.forEach { choice ->
        val pending = choice.offer?.let { progress["model:${it.id}"] }
        val selected = choice.model?.id == selectedModel && selectedModel != null
        val state = when {
            selected -> "已选用"
            choice.model != null -> "已安装"
            pending?.failed == true -> "下载失败 · 可重试"
            pending?.installing() == true -> pending.shortProgress()
            else -> "可下载"
        }
        val bytes = choice.model?.let { enhancementBytes(it.bytes) } ?: choice.offer?.sizeLabel
        val summary = listOfNotNull("${choice.scale}×", bytes, state).joinToString(" · ")
        key(choice.key) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ModelChoiceRow(choice.title, summary, selected, choice.model != null,
                    onClick = { choice.model?.let { onSelectModel(it.id) } ?: onOpenModel(choice.key) },
                    modifier = Modifier.weight(1f), failed = pending?.failed == true && choice.model == null)
                MotionIconButton(onClick = { onOpenModel(choice.key) }, modifier = Modifier.size(44.dp),
                    tooltip = "${choice.title} · 详情") {
                    Icon(Icons.Outlined.Info, "${choice.title} · 详情", Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (selectedModel != null && choices.none { it.model?.id == selectedModel })
        EnhancementQuiet("所选模型当前不在已安装列表中，可重新检查或选择其他模型。")
    progress["inventory"]?.takeIf { it.failed }?.detail?.let { EnhancementQuiet(it, error = true) }
    EnhancementDivider()
    AnimeEnhancementNavigation(Icons.Outlined.Memory, "运行组件", onClick = onOpenEngines)
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
            Text(summary, style = MaterialTheme.typography.labelSmall,
                color = if (failed) colors.error else colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun EnhancementModelDetail(
    inventory: EngineInventory,
    progress: Map<String, EngineProgress>,
    content: EnhancementContent,
    modelKey: String?,
    selectedModel: String?,
    scale: Int,
    onSelectModel: (String?) -> Unit,
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
    val engine = inventory.engines.firstOrNull { it.id == choice.engineId }
    val pending = offer?.let { progress["model:${it.id}"] }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var downloading by remember(modelKey) { mutableStateOf(false) }
    var notice by remember(modelKey) { mutableStateOf<String?>(null) }
    var noticeFailed by remember(modelKey) { mutableStateOf(false) }
    val busy = downloading || pending?.installing() == true
    Text(choice.title, style = MaterialTheme.typography.titleMedium)
    EnhancementQuiet(listOfNotNull("${choice.scale}×", model?.let { enhancementBytes(it.bytes) } ?: offer?.sizeLabel,
        if (model == null) "尚未安装" else if (model.id == selectedModel) "已选用" else "已安装").joinToString(" · "))
    if (model != null) {
        MotionButton({ onSelectModel(model.id) }, modifier = Modifier.fillMaxWidth(), enabled = selectedModel != model.id) {
            Icon(if (selectedModel == model.id) Icons.Outlined.Check else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(if (selectedModel == model.id) "已选用此模型" else "使用此模型")
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
    }
    val feedback = pending?.takeIf { it.failed }?.detail ?: notice
    feedback?.let { EnhancementQuiet(it, error = pending?.failed == true || noticeFailed) }
    if (busy && pending?.detail != null && !pending.failed) EnhancementQuiet(pending.detail)
    if (scale != choice.scale) EnhancementQuiet("当前输出为 ${scale}×，模型原生倍率为 ${choice.scale}×；选用不会改变输出设置。")
    if (engine != null) AnimeEnhancementNavigation(Icons.Outlined.Memory, "运行组件",
        "${engine.title} · ${engineReadinessLabel(engine.readiness)}", onClick = { onOpenEngine(engine.id) })
    EnhancementDivider()
    offer?.detail?.let { EnhancementQuiet(it) }
    EnhancementValue("适用内容", (model?.contents ?: offer?.contents.orEmpty()).joinToString(" · ", transform = ::modelContentLabel))
    model?.let { EnhancementValue("版本", it.version) }
    EnhancementValue("许可", model?.license ?: offer?.license.orEmpty())
    val source = offer?.sourceUrl ?: engine?.sourceUrl
    if (!source.isNullOrBlank()) MotionTextButton({ uriHandler.openUri(source) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Icon(Icons.Outlined.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(if (offer != null) "模型来源" else "组件来源")
    }
    Text("SHA-256", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SelectionContainer {
        Text(model?.sha256 ?: offer?.sha256.orEmpty(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
        (if (frames) engine.supportsInterpolation else !engine.supportsInterpolation ||
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
        Text(if (busy) pending?.shortProgress() ?: "正在安装" else if (pending?.failed == true) "重试安装" else "安装组件")
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
    if (engine.supportsInterpolation) {
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
