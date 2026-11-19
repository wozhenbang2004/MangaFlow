package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.ui.AnimeTextField
import dev.mangaflow.enhancement.*
import dev.mangaflow.enhancement.engine.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.SettingsToggle
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.rememberOpenDocumentPicker
import dev.mangaflow.ui.platform.releasePickedDocuments
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class EnhancementWorkspace { POLICY, MODELS, TASKS, TRIALS }

/** A contextual entry cannot switch into another content namespace. */
@Composable
internal fun EnhancementCenterPanel(
    visible: Boolean,
    backdrop: HazeState?,
    initialContent: EnhancementContent = EnhancementContent.MANGA,
    sessionKey: String? = null,
    onDismiss: () -> Unit,
    onClosed: (() -> Unit)? = null,
    onCompare: (() -> Unit)? = null,
) {
    var content by remember(initialContent, sessionKey) { mutableStateOf(initialContent) }
    var compareAfterClose by remember { mutableStateOf(false) }
    EnhancementPanelHost(visible, backdrop, onDismiss, onClosed = {
        onClosed?.invoke()
        if (compareAfterClose) { compareAfterClose = false; onCompare?.invoke() }
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("画质与性能", style = MaterialTheme.typography.titleMedium)
                    Text(if (sessionKey == null) "按内容分别保存默认设置" else content.label,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MotionIconButton(onDismiss, Modifier.size(44.dp), tooltip = "关闭画质与性能") {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (sessionKey == null) MotionTabs(content.ordinal, EnhancementContent.entries.map { it.label },
                    onSelect = { content = EnhancementContent.entries[it] }, modifier = Modifier.fillMaxWidth(),
                    controlHeight = 44.dp, treatment = MotionTabTreatment.Soft)
                EnhancementSettingsContent(content, sessionKey, onCompare = onCompare?.let {
                    { compareAfterClose = true; onDismiss() }
                })
            }
        }
    }
}

/** Shared by the global settings surface and the existing playback settings flow. */
@Composable
internal fun EnhancementSettingsContent(
    content: EnhancementContent,
    sessionKey: String? = null,
    animePlayback: AnimePlayerState? = null,
    onCompare: (() -> Unit)? = null,
) {
    val context = LocalMangaContext.current
    val center = remember(context) { EnhancementCenter.get(context) }
    val saved by center.settings.state.collectAsStateWithLifecycle()
    val sessions by center.settings.sessions.collectAsStateWithLifecycle()
    val inventory by center.inventory.collectAsStateWithLifecycle()
    val progress by center.engineProgress.collectAsStateWithLifecycle()
    val tasks by center.scheduler.state.collectAsStateWithLifecycle()
    val attachedVideoPreparation by center.videoPreparation.collectAsStateWithLifecycle()
    val videoPreparation = if (content == EnhancementContent.ANIME) remember(context, attachedVideoPreparation) {
        attachedVideoPreparation ?: dev.mangaflow.MangaApplicationServices.get(context).anime.enhancements
    } else null
    val videoState = if (content == EnhancementContent.ANIME)
        videoPreparation?.state?.collectAsStateWithLifecycle()?.value else null
    val telemetry by center.telemetry.state.collectAsStateWithLifecycle()
    val usage by center.resources.usage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var workspace by remember(content, sessionKey) { mutableStateOf(EnhancementWorkspace.POLICY) }
    var editSession by remember(content, sessionKey) { mutableStateOf(sessionKey != null) }
    val activeKey = sessionKey.takeIf { editSession }
    var draft by remember(content, activeKey) { mutableStateOf(center.settings.policy(content, activeKey)) }
    var revision by remember(content, activeKey) { mutableLongStateOf(0L) }
    var pending by remember(content, activeKey) { mutableIntStateOf(0) }
    var notice by remember(content, activeKey) { mutableStateOf<String?>(null) }
    var cacheUsage by remember(content) { mutableStateOf<EnhancedAssetUsage?>(null) }
    var cacheBusy by remember { mutableStateOf(false) }
    var fpsDraft by remember(draft.customFps) { mutableStateOf(draft.customFps.toString()) }
    var cacheDraft by remember(draft.cacheMiB) { mutableStateOf(draft.cacheMiB.toString()) }
    var cacheDetails by remember(content) { mutableStateOf(false) }
    val observed = sessionKey?.let { telemetry.sessions[it] }?.takeIf { it.content == content }
    val scopedTasks = tasks.filter { it.content == content && (sessionKey == null || it.sessionKey == sessionKey) }
    val activeTasks = scopedTasks.filter { it.phase in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING,
        EnhancementTaskPhase.RUNNING, EnhancementTaskPhase.PAUSED) }
    DisposableEffect(center) { val release = center.resources.startObserving(); onDispose(release) }
    LaunchedEffect(saved, sessions, activeKey) {
        if (pending == 0) draft = center.settings.policy(content, activeKey)
    }
    LaunchedEffect(content, scopedTasks.count { it.phase == EnhancementTaskPhase.COMPLETED }, videoPreparation,
        videoState?.entries?.count { it.phase == EnhancementTaskPhase.COMPLETED }, videoState?.entries?.size, videoState?.loading) {
        try { cacheUsage = readEnhancementUsage(context, content, videoPreparation) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { cacheUsage = null }
    }
    fun change(next: EnhancementPolicy) {
        draft = next
        val currentRevision = ++revision
        pending++
        notice = null
        val receipt = center.savePolicy(content, next, activeKey)
        scope.launch {
            try {
                val accepted = receipt.await()
                if (!accepted && currentRevision == revision) notice = "未能保存设置，请重试或检查存储空间。"
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (currentRevision == revision) notice = error.message ?: "未能保存设置。"
            } finally { pending-- }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (workspace != EnhancementWorkspace.POLICY) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MotionIconButton({ workspace = EnhancementWorkspace.POLICY }, Modifier.size(44.dp), tooltip = "返回画质设置") {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
            }
            Text(when (workspace) {
                EnhancementWorkspace.MODELS -> "增强引擎与模型"
                EnhancementWorkspace.TASKS -> "增强任务"
                EnhancementWorkspace.TRIALS -> "性能试用记录"
                EnhancementWorkspace.POLICY -> "画质与性能"
            }, style = MaterialTheme.typography.titleSmall)
        }
        when (workspace) {
            EnhancementWorkspace.POLICY -> {
                if (sessionKey != null) {
                    MotionTabs(if (editSession) 0 else 1, listOf("本次使用", "默认设置"), onSelect = {
                        editSession = it == 0; notice = null
                    }, modifier = Modifier.fillMaxWidth(), controlHeight = 40.dp, treatment = MotionTabTreatment.Soft)
                    if (editSession && sessions[sessionKey]?.content == content) MotionTextButton(onClick = {
                        center.resetSessionPolicy(sessionKey)
                        draft = center.settings.policy(content)
                    }, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("恢复跟随默认设置") }
                }
                EnhancementQualityControls(content, draft, inventory, ::change) { workspace = EnhancementWorkspace.MODELS }
                EnhancementDivider()
                if (content == EnhancementContent.ANIME) {
                    EnhancementFrameControls(draft, inventory, ::change, { workspace = EnhancementWorkspace.MODELS }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { AnimeTextField(fpsDraft, { fpsDraft = it }, "自定义 FPS") }
                            MotionTextButton(onClick = {
                                val fps = fpsDraft.toIntOrNull()
                                if (fps == null || fps <= 0) notice = "帧率须为大于零的整数。"
                                else change(draft.copy(customFps = fps))
                            }) { Text("保存") }
                        }
                    }
                    EnhancementDivider()
                }
                EnhancementPreparationControls(draft, ::change, content == EnhancementContent.ANIME)
                EnhancementDivider()
                EnhancementLiveStatus(content, observed, animePlayback, usage.resources, activeTasks.size,
                    onOpenTasks = { workspace = EnhancementWorkspace.TASKS })
                EnhancementDivider()
                EnhancementSection("缓存状态", Icons.Outlined.Storage,
                    cacheUsage?.let { "增强 ${enhancementBytes(it.completedBytes)} · ${it.entries} 份" } ?: "缓存用量不可用",
                    action = { EnhancementDetailToggle(cacheDetails, { cacheDetails = !cacheDetails }, "预算") }) {
                    EnhancementValue("临时工作文件", enhancementBytes(cacheUsage?.temporaryBytes))
                    EnhancementValue("模型文件", enhancementBytes(inventory.modelBytes.takeIf { inventory.engines.isNotEmpty() }))
                    if ((cacheUsage?.leasedEntries ?: 0) > 0) EnhancementQuiet("${cacheUsage?.leasedEntries} 份正在使用，清理时会保留。")
                    if (cacheDetails) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { AnimeTextField(cacheDraft, { cacheDraft = it }, "增强缓存 MiB") }
                            MotionTextButton(onClick = {
                                val size = cacheDraft.toIntOrNull()
                                if (size == null || size < 0) notice = "缓存预算须为非负整数。"
                                else change(draft.copy(cacheMiB = size))
                            }) { Text("保存") }
                        }
                        EnhancementQuiet("当前内容类型独立设置；不改变原始下载和其他内容的缓存预算。")
                    }
                    MotionTextButton(onClick = {
                        if (!cacheBusy) {
                            cacheBusy = true
                            scope.launch {
                                try {
                                    val freed = withContext(NonCancellable) {
                                        EnhancedAssetCache.get(context).clear(content.directory) +
                                            if (content == EnhancementContent.ANIME) checkNotNull(videoPreparation) {
                                                "视频增强缓存尚未就绪，请重新打开画质与性能"
                                            }.clear() else 0L
                                    }
                                    cacheUsage = readEnhancementUsage(context, content, videoPreparation)
                                    notice = "已清理 ${enhancementBytes(freed)} 增强缓存，原始下载保留。"
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { notice = error.message ?: "清理未完成。" }
                                finally { cacheBusy = false }
                            }
                        }
                    }, enabled = !cacheBusy, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Icon(Icons.Outlined.CleaningServices, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (cacheBusy) "正在清理" else "清理可再生成的增强结果")
                    }
                }
                EnhancementDivider()
                EnhancementSection(if (content == EnhancementContent.ANIME) "比较与试用" else "当前页比较", Icons.Outlined.Compare) {
                    MotionTextButton(onClick = { onCompare?.invoke() }, enabled = onCompare != null,
                        contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Icon(Icons.Outlined.Compare, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                        Text(if (content == EnhancementContent.ANIME) "同帧原画 / 增强" else "原图 / 增强对比")
                    }
                    if (onCompare == null) EnhancementQuiet(if (content == EnhancementContent.ANIME)
                        "当前内核尚未提供同帧图像，不能进行真实画面对比。" else "当前页有同一原图的增强结果后可比较。")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionTextButton(onClick = {
                            if (sessionKey != null) try { center.startTrial(content, sessionKey) }
                            catch (error: Exception) { notice = error.message ?: "无法开始试用。" }
                        }, enabled = sessionKey != null && telemetry.activeTrial == null,
                            contentPadding = PaddingValues(horizontal = 0.dp)) {
                            Text(if (telemetry.activeTrial?.sessionKey == sessionKey && sessionKey != null) "正在记录 15 秒" else "性能试用 · 15 秒")
                        }
                        MotionTextButton({ workspace = EnhancementWorkspace.TRIALS }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                            Text("比较记录")
                        }
                    }
                    if (sessionKey == null) EnhancementQuiet("打开实际漫画或番剧后试用；这里可查看已有本机记录。")
                    else EnhancementQuiet("在真实内容上记录此次表现，短时结果不代表长时间稳定。")
                }
            }
            EnhancementWorkspace.MODELS -> EnhancementModels(inventory, progress, content, draft.modelId,
                onSelectModel = { change(draft.copy(modelId = it)) },
                onInstall = { center.installEngine(it) }, onRefresh = { center.refreshInventory() }, center = center)
            EnhancementWorkspace.TASKS -> EnhancementTasks(scopedTasks, center)
            EnhancementWorkspace.TRIALS -> EnhancementTrials(telemetry.trials.filter { it.content == content })
        }
        notice?.let { EnhancementQuiet(it) }
        telemetry.persistenceErrors[content]?.let { EnhancementQuiet(it, error = true) }
        progress["inventory"]?.detail?.let { EnhancementQuiet(it, error = true) }
    }
}

@Composable
private fun EnhancementModels(inventory: EngineInventory, progress: Map<String, EngineProgress>, content: EnhancementContent,
    selectedModel: String?, onSelectModel: (String?) -> Unit, onInstall: (String) -> Unit, onRefresh: () -> Unit,
    center: EnhancementCenter) {
    MotionTextButton(onRefresh, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("重新检查安装")
    }
    val engines = inventory.engines.filter { engine -> if (content == EnhancementContent.ANIME)
        EngineContent.VIDEO in engine.contents else engine.contents.any { it != EngineContent.VIDEO } }
    if (engines.isEmpty()) EnhancementQuiet("尚未取得引擎信息。")
    engines.forEach { engine ->
        EnhancementSection(engine.title, Icons.Outlined.Memory, engineReadinessLabel(engine.readiness)) {
            EnhancementQuiet(engine.detail)
            EnhancementValue("支持倍率", engine.supportedScales.sorted().joinToString(" / ") { "$it×" }.ifBlank { "不可用" })
            EnhancementValue("版本 / 许可", "${engine.version} · ${engine.license}")
            progress[engine.id]?.let { value ->
                EnhancementQuiet(value.readableProgress())
                value.detail?.let { EnhancementQuiet(it) }
            }
            if (engine.canInstall) MotionTextButton(onClick = { onInstall(engine.id) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("安装" + (engine.downloadBytes?.let { " · ${enhancementBytes(it)}" } ?: ""))
            }
            val models = inventory.models.filter { it.engineId == engine.id }
            models.forEach { model ->
                MotionTextButton(onClick = { onSelectModel(model.id) }, modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                    Icon(if (selectedModel == model.id) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                        null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.title, style = MaterialTheme.typography.bodyMedium)
                        Text("${model.nativeScale}× · ${enhancementBytes(model.bytes)} · ${model.sha256.take(12)}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        EnhancementDivider()
    }
    if (selectedModel != null) MotionTextButton({ onSelectModel(null) }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Text("恢复自动选模型")
    }
    if (content != EnhancementContent.ANIME) EnhancementModelImport(center, onSelectModel)
}

@Composable
private fun EnhancementModelImport(center: EnhancementCenter, onSelectModel: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var scale by remember { mutableIntStateOf(2) }
    var style by remember { mutableStateOf(EngineContent.BLACK_WHITE) }
    var importing by remember { mutableStateOf(false) }
    var attached by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { attached = false } }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val select = rememberUpdatedState(onSelectModel)
    val picker = rememberOpenDocumentPicker { uri ->
        if (uri != null && !importing) {
            importing = true; notice = null
            val receipt = center.importModel(uri, scale, style)
            scope.launch {
                // A granted picker document stays valid until the application-owned copy settles.
                withContext(NonCancellable) {
                    try {
                        val model = receipt.await()
                        if (attached) select.value(model.id)
                        notice = "已导入 ${model.title}。首次真实试用会验证模型输入与输出倍率。"
                    } catch (error: Exception) { notice = error.message ?: "模型未能导入。" }
                    finally { releasePickedDocuments(listOf(uri)); importing = false }
                }
            }
        }
    }
    EnhancementSection("导入模型", Icons.Outlined.UploadFile, "ONNX 权重文件",
        action = { EnhancementDetailToggle(expanded, { expanded = !expanded }) }) {
        if (expanded) {
            EnhancementQuiet("按模型说明选择原生倍率和适用内容。文件摘要会实际计算，导入不代表已通过设备验证。")
            EnhancementChoices(listOf(1, 2, 4), scale, { "$it×" }, { scale = it }, "模型声明倍率")
            EnhancementChoices(listOf(EngineContent.BLACK_WHITE, EngineContent.COLOR, EngineContent.WEBTOON), style,
                { when (it) { EngineContent.BLACK_WHITE -> "黑白"; EngineContent.COLOR -> "彩色";
                    EngineContent.WEBTOON -> "条漫"; EngineContent.VIDEO -> "番剧" } },
                { style = it }, "模型适用内容")
            MotionTextButton(onClick = { picker.launch(arrayOf("application/octet-stream", "application/onnx", "*/*")) },
                enabled = !importing, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (importing) "正在导入模型" else "选择 .onnx 文件")
            }
            EnhancementQuiet("请遵循模型的原始许可；普通与成人漫画共享模型文件，各自的增强结果仍独立保存。")
            notice?.let { EnhancementQuiet(it) }
        }
    }
}

@Composable
private fun EnhancementTasks(tasks: List<EnhancementTask>, center: EnhancementCenter) {
    if (tasks.isEmpty()) { EnhancementQuiet("当前范围没有增强任务。"); return }
    tasks.sortedByDescending { it.createdAtMillis }.take(30).forEach { task ->
        EnhancementSection(task.title ?: task.content.label, Icons.Outlined.Layers,
            task.phase.label + " · " + (task.progress?.readableProgress() ?: task.reason ?: "等待处理")) {
            task.output?.let { output -> EnhancementValue("已验证结果", "${output.width} × ${output.height} · ${enhancementBytes(output.bytes)}") }
            task.reason?.let { EnhancementQuiet(it, task.phase == EnhancementTaskPhase.FAILED) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.phase == EnhancementTaskPhase.FAILED) MotionTextButton({ center.scheduler.retry(task.id) }) { Text("重试") }
                if (task.phase == EnhancementTaskPhase.PAUSED) MotionTextButton({ center.scheduler.resume(task.id) }) { Text("继续") }
                if (task.phase in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING, EnhancementTaskPhase.RUNNING))
                    MotionTextButton({ center.scheduler.pause(task.id) }) { Text("暂停") }
                if (task.phase in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING,
                    EnhancementTaskPhase.RUNNING, EnhancementTaskPhase.PAUSED))
                    MotionTextButton({ center.scheduler.cancel(task.id) }) { Text("取消") }
            }
        }
        EnhancementDivider()
    }
}

@Composable
private fun EnhancementTrials(trials: List<EnhancementTrial>) {
    if (trials.isEmpty()) { EnhancementQuiet("尚无本机试用记录。请在阅读或播放时选择档位，开始一次性能试用。"); return }
    var expanded by remember { mutableStateOf<String?>(null) }
    EnhancementQuiet("各次内容、模型和时间可能不同；只有同条件结果适合直接比较。")
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("档位", Modifier.weight(.8f), style = MaterialTheme.typography.labelSmall)
        Text("实际输出", Modifier.weight(1.2f), style = MaterialTheme.typography.labelSmall)
        Text("GPU P95", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        Text("丢帧", Modifier.weight(.6f), style = MaterialTheme.typography.labelSmall)
    }
    trials.sortedByDescending { it.startedAt }.forEach { trial ->
        MotionTextButton({ expanded = if (expanded == trial.id) null else trial.id },
            modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${trial.tier.label}\n${trial.scale}×", Modifier.weight(.8f), style = MaterialTheme.typography.bodySmall)
                Text(trial.outputLabel ?: "不可用", Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
                Text(trial.gpuP95Millis?.let { formatDecimal(it, 2) + " ms" } ?: "不可用",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(trial.droppedFrames?.toString() ?: "—", Modifier.weight(.6f), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded == trial.id) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            EnhancementQuiet(formatInstantMillis(trial.startedAt, "MM-dd HH:mm") + " · " + trial.confidence.label)
            EnhancementValue("处理样本", "${trial.samples} · ${formatDecimal(trial.durationMillis / 1000.0, 1)} 秒")
            EnhancementValue("整页处理 P95", trial.processingP95Millis?.let { formatDecimal(it, 1) + " ms" } ?: "不可用")
            EnhancementValue("实际呈现 / 丢帧", (trial.presentedFps?.let { formatDecimal(it, 1) + " FPS" } ?: "不可用") +
                " / " + (trial.droppedFrames?.toString() ?: "不可用"))
            EnhancementValue("热压力", thermalLabel(trial.thermalStart) + " → " + thermalLabel(trial.thermalEnd))
            trial.modelDigest?.let { EnhancementValue("模型摘要", it.take(16)) }
            trial.reason?.let { EnhancementQuiet(it) }
        }
        EnhancementDivider()
    }
}

internal fun thermalLabel(value: Int?): String = when (value) {
    0 -> "正常"
    1 -> "升高"
    2 -> "严重"
    3 -> "紧急"
    else -> "不可用"
}

private suspend fun readEnhancementUsage(context: PlatformContext, content: EnhancementContent,
    video: VideoPreparationStore?): EnhancedAssetUsage? {
    val image = EnhancedAssetCache.get(context).usage(content.directory)
    if (content != EnhancementContent.ANIME) return image
    val media = checkNotNull(video) { "视频增强缓存尚未就绪" }.usage()
    return EnhancedAssetUsage(image.completedBytes + media.completedBytes,
        image.temporaryBytes + media.temporaryBytes, image.entries + media.entries, image.leasedEntries + media.leasedEntries)
}
