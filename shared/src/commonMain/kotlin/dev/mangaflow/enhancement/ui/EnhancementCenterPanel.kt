package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeVideoEnhancementPhase
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Defaults have two media categories; contextual records retain their actual content identity. */
@Composable
internal fun EnhancementCenterPanel(
    visible: Boolean,
    backdrop: HazeState?,
    initialContent: EnhancementContent = EnhancementContent.MANGA,
    sessionKey: String? = null,
    allowMediaSwitch: Boolean = true,
    onDismiss: () -> Unit,
    onClosed: (() -> Unit)? = null,
    onCompare: (() -> Unit)? = null,
    readerState: ReaderEnhancementState? = null,
    onRetryTask: ((String) -> Boolean)? = null,
    onCancelTask: ((String) -> Boolean)? = null,
) {
    val canSwitchMedia = allowMediaSwitch && sessionKey == null
    val mediaCategories = listOf(EnhancementContent.MANGA, EnhancementContent.ANIME)
    var content by remember(initialContent, sessionKey, canSwitchMedia) {
        mutableStateOf(if (canSwitchMedia) initialContent.settingsOwner else initialContent)
    }
    val navigation = remember(content, sessionKey) { EnhancementNavigation() }
    val primaryPages = enhancementPrimaryPages(content)
    val editorState = rememberSaveableStateHolder()
    val editorKey = "enhancement:${content.name}:${sessionKey ?: "defaults"}"
    DisposableEffect(editorKey) { onDispose { editorState.removeState(editorKey) } }
    val scrolls = remember(content, sessionKey) { mutableMapOf<EnhancementPage, ScrollState>() }
    val scroll = remember(content, sessionKey, navigation.page) {
        scrolls.getOrPut(navigation.page) { ScrollState(0) }
    }
    var compareAfterClose by remember { mutableStateOf(false) }
    fun navigate(next: EnhancementPage) {
        if (next in setOf(EnhancementPage.MODEL_DETAIL, EnhancementPage.ENGINE_DETAIL, EnhancementPage.IMPORT))
            scrolls[next] = ScrollState(0)
        navigation.open(next)
    }
    EnhancementPanelHost(visible, backdrop, onDismiss, onClosed = {
        onClosed?.invoke()
        if (compareAfterClose) { compareAfterClose = false; onCompare?.invoke() }
    }) {
        MotionBackHandler(visible && navigation.parent != null, state = null, onBack = { navigation.back() })
        Column(Modifier.fillMaxWidth().heightIn(max = minOf(LocalMangaWindow.current.panelHeight, 560.dp))) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                navigation.parent?.let { parent ->
                    val backLabel = "返回${parent.titleFor(sessionKey != null)}"
                    MotionIconButton({ navigation.back() }, Modifier.size(44.dp), tooltip = backLabel) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = if (navigation.parent == null) 8.dp else 0.dp)) {
                    val title = when {
                        navigation.page.isPrimary -> "画质与性能"
                        canSwitchMedia && content.settingsOwner == EnhancementContent.MANGA &&
                            navigation.page == EnhancementPage.CACHE -> "缓存预算"
                        else -> navigation.page.title
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!canSwitchMedia || !navigation.page.isPrimary)
                        Text(content.settingsLabel, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MotionIconButton(onDismiss, Modifier.size(44.dp), tooltip = "关闭画质与性能") {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                }
            }
            if (navigation.page.isPrimary) {
                if (canSwitchMedia) MotionTabs(mediaCategories.indexOf(content.settingsOwner), mediaCategories.map { it.settingsLabel },
                    onSelect = { content = mediaCategories[it] },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    controlHeight = 44.dp, treatment = MotionTabTreatment.Soft)
                MotionTabs(primaryPages.indexOf(navigation.page), primaryPages.map { it.titleFor(sessionKey != null) },
                    onSelect = { navigate(primaryPages[it]) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    controlHeight = 44.dp, treatment = MotionTabTreatment.Soft)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll)
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)) {
                editorState.SaveableStateProvider(editorKey) {
                    EnhancementSettingsContent(content, sessionKey, page = navigation.page, onPageChange = ::navigate,
                        showContentRecords = !canSwitchMedia,
                        readerState = readerState, onRetryTask = onRetryTask, onCancelTask = onCancelTask,
                        onCompare = onCompare?.let { { compareAfterClose = true; onDismiss() } })
                }
            }
        }
    }
}

/** Shared by the reader, global settings and the existing playback settings flow. */
@Composable
internal fun EnhancementSettingsContent(
    content: EnhancementContent,
    sessionKey: String? = null,
    animePlayback: AnimePlayerState? = null,
    onCompare: (() -> Unit)? = null,
    page: EnhancementPage,
    onPageChange: (EnhancementPage) -> Unit,
    qualityFooter: (@Composable () -> Unit)? = null,
    showContentRecords: Boolean = true,
    readerState: ReaderEnhancementState? = null,
    onRetryTask: ((String) -> Boolean)? = null,
    onCancelTask: ((String) -> Boolean)? = null,
) {
    val context = LocalMangaContext.current
    val center = remember(context) { EnhancementCenter.get(context) }
    val saved by center.settings.state.collectAsStateWithLifecycle()
    val sessions by center.settings.sessions.collectAsStateWithLifecycle()
    val visibleMetrics by center.settings.visibleMetrics.collectAsStateWithLifecycle()
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
    var editSession by rememberSaveable(content, sessionKey) { mutableStateOf(sessionKey != null) }
    var animeStatusScope by rememberSaveable(content, sessionKey) { mutableIntStateOf(0) }
    var modelKey by rememberSaveable(content, sessionKey) { mutableStateOf<String?>(null) }
    var engineId by rememberSaveable(content, sessionKey) { mutableStateOf<String?>(null) }
    val activeKey = sessionKey.takeIf { editSession }
    var draft by remember(content, activeKey) { mutableStateOf(center.settings.policy(content, activeKey)) }
    var revision by remember(content, activeKey) { mutableLongStateOf(0L) }
    var pending by remember(content, activeKey) { mutableIntStateOf(0) }
    var notice by remember(content, activeKey) { mutableStateOf<String?>(null) }
    var cacheUsage by remember(content) { mutableStateOf<EnhancedAssetUsage?>(null) }
    var cacheBusy by remember(content) { mutableStateOf(false) }
    var fpsDraft by remember(content, activeKey, draft.customFps) { mutableStateOf(draft.customFps.toString()) }
    var cacheDraft by remember(content, activeKey, draft.cacheMiB) { mutableStateOf(draft.cacheMiB.toString()) }
    val observed = sessionKey?.let { telemetry.sessions[it] }?.takeIf { it.content == content }
    // Offline video work belongs to the content namespace, not the transient player session.
    val scopedTasks = tasks.filter { it.content == content &&
        (content == EnhancementContent.ANIME || sessionKey == null || it.sessionKey == sessionKey) }
    val activeTasks = scopedTasks.filter { it.phase in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING,
        EnhancementTaskPhase.RUNNING, EnhancementTaskPhase.PAUSED) }
    DisposableEffect(center, sessionKey, showContentRecords) {
        val release = if (sessionKey != null && showContentRecords) center.resources.startObserving() else null
        onDispose { release?.invoke() }
    }
    LaunchedEffect(saved, sessions, activeKey) {
        if (pending == 0) draft = center.settings.policy(content, activeKey)
    }
    LaunchedEffect(content, showContentRecords, scopedTasks.count { it.phase == EnhancementTaskPhase.COMPLETED }, videoPreparation,
        videoState?.entries?.count { it.phase == EnhancementTaskPhase.COMPLETED }, videoState?.entries?.size, videoState?.loading) {
        if (!showContentRecords) { cacheUsage = null; return@LaunchedEffect }
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
                if (!receipt.await() && currentRevision == revision) notice = "未能保存设置，请重试或检查存储空间。"
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (currentRevision == revision) notice = error.message ?: "未能保存设置。"
            } finally {
                pending--
                if (pending == 0) draft = center.settings.policy(content, activeKey)
            }
        }
    }
    fun openModel(key: String) { modelKey = key; onPageChange(EnhancementPage.MODEL_DETAIL) }
    fun openEngine(id: String) { engineId = id; onPageChange(EnhancementPage.ENGINE_DETAIL) }
    val anime = content == EnhancementContent.ANIME
    fun selectModel(id: String?) {
        change(draft.copy(modelId = id, preparation =
            if (anime && draft.tier != QualityTier.ORIGINAL) draft.preparation + PreparationMode.IMMEDIATE
            else draft.preparation))
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sessionKey != null && page in setOf(EnhancementPage.QUALITY, EnhancementPage.FRAME, EnhancementPage.PREPARATION))
            EnhancementScope(content, editSession) { editSession = it; notice = null }
        else if (page in setOf(EnhancementPage.QUALITY_OPTIONS, EnhancementPage.FRAME_OPTIONS,
                EnhancementPage.MODELS, EnhancementPage.MODEL_DETAIL))
            EnhancementQuiet(if (activeKey != null) if (anime) "应用于本次播放" else "应用于本次阅读"
                else "应用于${content.settingsLabel}默认设置")
        if (anime && draft.tier == QualityTier.ORIGINAL &&
            page in setOf(EnhancementPage.MODELS, EnhancementPage.MODEL_DETAIL))
            EnhancementQuiet("当前使用原画；模型选择会保存，返回画质页选择增强档位后启用。")
        when (page) {
            EnhancementPage.QUALITY -> {
                EnhancementQuality(content, draft, inventory, ::change,
                    onOpenModels = { onPageChange(EnhancementPage.MODELS) },
                    onOpenOptions = { onPageChange(EnhancementPage.QUALITY_OPTIONS) })
                if (anime && animePlayback != null && sessionKey != null && sessionKey == animeEnhancementSessionKey(animePlayback)) {
                    val currentPolicy = sessions[sessionKey]?.takeIf { it.content == content }?.policy
                        ?: saved.getValue(content)
                    val effect = animePlayback.videoEnhancement
                    val usingPrepared = animePlayback.isPlaying && !animePlayback.playbackSuppressed &&
                        animePlayback.preparedVideoWindow?.let { it.framesUsed > 0L &&
                            monotonicTimeMillis() - it.lastUsedAt in 0L..1_000L } == true
                    val actualReason = when {
                        animePlayback.preEnhanced || usingPrepared -> null
                        effect.phase in setOf(AnimeVideoEnhancementPhase.UNAVAILABLE, AnimeVideoEnhancementPhase.FAILED) -> effect.detail
                        currentPolicy.tier == QualityTier.ORIGINAL -> null
                        effect.phase == AnimeVideoEnhancementPhase.OFF -> when {
                            PreparationMode.IMMEDIATE !in currentPolicy.preparation -> "即时增强已关闭，尚未匹配到提前处理的画面。"
                            usage.resources.memoryPressure == true || (usage.resources.thermalPressure ?: 0) >= 2 ->
                                "设备保护暂缓了增强，所选档位保留。"
                            else -> observed?.reason ?: "所选增强尚未运行，正在等待播放器应用。"
                        }
                        else -> null
                    }
                    if (actualReason != null) EnhancementQuiet("当前播放：$actualReason",
                        error = effect.phase == AnimeVideoEnhancementPhase.FAILED)
                }
                qualityFooter?.invoke()
                if (editSession && sessionKey != null && sessions[sessionKey]?.content == content)
                    AnimeEnhancementNavigation(Icons.Outlined.RestartAlt, "恢复跟随默认设置", onClick = {
                        revision++
                        center.resetSessionPolicy(sessionKey)
                        draft = center.settings.policy(content)
                        notice = null
                    })
            }
            EnhancementPage.QUALITY_OPTIONS -> EnhancementQualityOptions(content, draft, inventory, ::change)
            EnhancementPage.FRAME -> AnimeEnhancementFrames(draft, inventory, ::change,
                onOpenEngines = { onPageChange(EnhancementPage.FRAME_ENGINES) },
                onOpenQuality = { onPageChange(EnhancementPage.FRAME_OPTIONS) }) {
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
            EnhancementPage.FRAME_OPTIONS -> EnhancementFrameOptions(draft, ::change)
            EnhancementPage.PREPARATION -> {
                EnhancementPreparation(content, draft, ::change)
                EnhancementDivider()
                if (activeKey == null) {
                    SettingsToggle("下载完成后增强", draft.enhanceAfterDownload, Icons.Outlined.Download,
                        onCheckedChange = { change(draft.copy(enhanceAfterDownload = it)) })
                    EnhancementQuiet("使用默认设置处理之后完成的下载。")
                } else AnimeEnhancementNavigation(Icons.Outlined.Download, "下载后自动增强",
                    if (saved.getValue(content).enhanceAfterDownload) "已开启 · 使用默认设置" else "已关闭 · 在默认设置中调整",
                    onClick = { editSession = false; notice = null })
                if (showContentRecords) {
                    EnhancementDivider()
                    AnimeEnhancementNavigation(Icons.Outlined.Layers, "增强任务",
                        if (activeTasks.isEmpty()) "查看处理记录" else "${activeTasks.size} 项未完成",
                        onClick = { onPageChange(EnhancementPage.TASKS) })
                    AnimeEnhancementNavigation(Icons.Outlined.Storage, "增强缓存",
                        cacheUsage?.let { "${enhancementBytes(it.completedBytes)} · ${it.entries} 份" } ?: "查看用量与清理",
                        onClick = { onPageChange(EnhancementPage.CACHE) })
                    if (sessionKey == null) AnimeEnhancementNavigation(Icons.Outlined.QueryStats, "性能记录",
                        "查看已保存的性能记录", onClick = { onPageChange(EnhancementPage.TRIALS) })
                } else if (!anime) {
                    EnhancementDivider()
                    AnimeEnhancementNavigation(Icons.Outlined.Storage, "缓存预算", "${draft.cacheMiB} MiB",
                        onClick = { onPageChange(EnhancementPage.CACHE) })
                }
            }
            EnhancementPage.STATUS -> {
                if (sessionKey == null) EnhancementDisplayControls(content)
                else {
                    val count = visibleMetrics.getValue(content).size
                    AnimeEnhancementNavigation(Icons.Outlined.Visibility, "观看时显示",
                        if (count == 0) "增强读数已隐藏" else "已选 $count 项 · ${if (anime) "画面" else "阅读状态栏"}中直接显示",
                        onClick = { onPageChange(EnhancementPage.DISPLAY) })
                    EnhancementDivider()
                    EnhancementLiveStatus(content, observed, animePlayback, usage.resources, activeTasks.size,
                        onOpenTasks = { onPageChange(EnhancementPage.TASKS) }, showHeading = false,
                        animeScope = animeStatusScope, onAnimeScopeChange = { animeStatusScope = it },
                        readerState = readerState, inventory = inventory,
                        policy = sessions[sessionKey]?.takeIf { it.content == content }?.policy ?: saved.getValue(content))
                    if (showContentRecords) AnimeEnhancementNavigation(Icons.Outlined.QueryStats, "性能记录", "记录当前档位的实际表现",
                        onClick = { onPageChange(EnhancementPage.TRIALS) })
                }
            }
            EnhancementPage.DISPLAY -> EnhancementDisplayControls(content)
            EnhancementPage.MODELS -> EnhancementModelList(inventory, progress, content, draft.modelId,
                onSelectModel = ::selectModel, onOpenModel = ::openModel,
                onOpenEngines = { onPageChange(EnhancementPage.ENGINES) },
                onImport = { onPageChange(EnhancementPage.IMPORT) }, onRefresh = { center.refreshInventory() })
            EnhancementPage.MODEL_DETAIL -> EnhancementModelDetail(inventory, progress, content, modelKey,
                draft.modelId, draft.scale, onSelectModel = ::selectModel,
                center = center, onOpenEngine = ::openEngine)
            EnhancementPage.ENGINES, EnhancementPage.FRAME_ENGINES -> EnhancementEngineList(inventory, progress, content,
                frames = page == EnhancementPage.FRAME_ENGINES, onOpenEngine = ::openEngine,
                onImport = { onPageChange(EnhancementPage.IMPORT) }, onRefresh = { center.refreshInventory() })
            EnhancementPage.ENGINE_DETAIL -> EnhancementEngineDetail(inventory, progress, engineId,
                onInstall = { center.installEngine(it) }, onImport = { onPageChange(EnhancementPage.IMPORT) })
            EnhancementPage.IMPORT -> EnhancementModelImport(center, video = anime)
            EnhancementPage.CACHE -> {
                if (showContentRecords) {
                    EnhancementValue("增强结果", cacheUsage?.let { "${enhancementBytes(it.completedBytes)} · ${it.entries} 份" } ?: "—")
                    EnhancementValue("临时工作文件", cacheUsage?.let { enhancementBytes(it.temporaryBytes) } ?: "—")
                    EnhancementValue("模型文件", inventory.modelBytes.takeIf { inventory.engines.isNotEmpty() }
                        ?.let(::enhancementBytes) ?: "—")
                    if ((cacheUsage?.leasedEntries ?: 0) > 0) EnhancementQuiet("${cacheUsage?.leasedEntries} 份正在使用，清理时保留。")
                    if (cacheUsage == null) EnhancementQuiet("尚未取得缓存用量。")
                    EnhancementDivider()
                }
                if (anime) {
                    EnhancementValue("缓存预算", "跟随共享磁盘预算")
                    EnhancementQuiet("在设置 → 体验策略 → 缓存中调整。")
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { AnimeTextField(cacheDraft, { cacheDraft = it }, "增强缓存 MiB") }
                        MotionTextButton(onClick = {
                            val size = cacheDraft.toIntOrNull()
                            if (size == null || size < 0) notice = "缓存预算须为非负整数。"
                            else change(draft.copy(cacheMiB = size))
                        }) { Text("保存") }
                    }
                }
                if (showContentRecords) {
                    Spacer(Modifier.height(8.dp))
                    MotionOutlinedButton(onClick = {
                        if (!cacheBusy) {
                            cacheBusy = true
                            scope.launch {
                                try {
                                    val freed = withContext(NonCancellable) {
                                        EnhancedAssetCache.get(context).clear(content.directory) +
                                            if (anime) checkNotNull(videoPreparation) { "视频增强缓存尚未就绪，请重新打开设置" }.clear() else 0L
                                    }
                                    cacheUsage = readEnhancementUsage(context, content, videoPreparation)
                                    notice = "已释放 ${enhancementBytes(freed)}。"
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (error: Exception) { notice = error.message ?: "清理未完成。" }
                                finally { cacheBusy = false }
                            }
                        }
                    }, enabled = !cacheBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.CleaningServices, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (cacheBusy) "正在清理" else "清理增强结果")
                    }
                    EnhancementQuiet("保留原始下载、模型和正在使用的结果。")
                }
            }
            EnhancementPage.TASKS -> {
                if (anime) EnhancementQuiet("全部番剧的增强任务，包含下载后的离线处理。")
                EnhancementTasks(scopedTasks, center, onRetryTask, onCancelTask,
                    readerState?.manuallyStoppedTaskIds.orEmpty())
            }
            EnhancementPage.TRIALS -> {
                val recording = telemetry.activeTrial?.sessionKey == sessionKey && sessionKey != null
                val canRecord = sessionKey != null && telemetry.activeTrial == null && (!anime ||
                    animePlayback?.let { it.isPlaying && !it.playbackSuppressed && !it.isBuffering } == true)
                MotionButton(onClick = {
                    if (sessionKey != null) try { center.startTrial(content, sessionKey) }
                    catch (error: Exception) { notice = error.message ?: "无法开始记录。" }
                }, enabled = canRecord, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (recording) "正在记录 · 15 秒" else "记录当前表现 · 15 秒")
                }
                EnhancementQuiet(if (sessionKey == null || anime && animePlayback?.isPlaying != true)
                    "打开实际内容后可记录性能；已有记录仍可查看。"
                    else "记录真实内容的短时表现，不代表长时间稳定性。")
                if (onCompare != null) AnimeEnhancementNavigation(Icons.Outlined.Compare,
                    if (anime) "同帧原画 / 增强" else "原图 / 增强对比", onClick = onCompare)
                else EnhancementQuiet(if (anime) "当前播放器尚不支持同帧画面对比。" else "当前页有增强结果后可比较。")
                EnhancementDivider()
                EnhancementTrials(telemetry.trials.filter { it.content == content })
            }
        }
        notice?.let { EnhancementQuiet(it) }
        telemetry.persistenceErrors[content]?.let { EnhancementQuiet(it, error = true) }
    }
}

@Composable
private fun EnhancementTasks(
    tasks: List<EnhancementTask>,
    center: EnhancementCenter,
    onRetryTask: ((String) -> Boolean)?,
    onCancelTask: ((String) -> Boolean)?,
    manuallyStoppedTaskIds: Set<String>,
) {
    if (tasks.isEmpty()) { EnhancementQuiet("当前范围没有增强任务。"); return }
    var showFinished by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    fun isFinished(task: EnhancementTask) = task.phase == EnhancementTaskPhase.COMPLETED ||
        task.phase == EnhancementTaskPhase.CANCELLED && task.id !in manuallyStoppedTaskIds
    val finished = tasks.filter(::isFinished)
        .sortedByDescending { it.createdAtMillis }
    val current = tasks.filterNot(::isFinished)
        .sortedWith(compareBy<EnhancementTask> { it.phase in setOf(EnhancementTaskPhase.FAILED, EnhancementTaskPhase.CANCELLED) }
            .thenByDescending { it.createdAtMillis })
    actionError?.let { EnhancementQuiet(it, error = true) }
    if (current.isEmpty()) EnhancementQuiet("当前没有正在处理或等待恢复的增强任务。")
    val visibleTasks = current + if (showFinished) finished.take(30) else emptyList()
    visibleTasks.forEachIndexed { index, task ->
        if (showFinished && index == current.size) EnhancementDetailToggle(true, { showFinished = false },
            "已结束记录 · ${finished.size} 项")
        key(task.id) {
            EnhancementSection(task.title ?: task.content.label,
                if (task.phase == EnhancementTaskPhase.FAILED) Icons.Outlined.ErrorOutline else Icons.Outlined.Layers,
                task.readableStatus()) {
                task.output?.let { output -> EnhancementValue("已验证结果", "${output.width} × ${output.height} · ${enhancementBytes(output.bytes)}") }
                if (task.phase == EnhancementTaskPhase.RUNNING) task.progress?.detail?.let { EnhancementQuiet(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (task.phase == EnhancementTaskPhase.FAILED ||
                        task.phase == EnhancementTaskPhase.CANCELLED && task.id in manuallyStoppedTaskIds) MotionTextButton({
                        actionError = null
                        if (onRetryTask != null) {
                            if (!onRetryTask(task.id)) actionError = "原任务已过期，请回到需要增强的页面后重新处理。"
                        } else if (center.scheduler.retry(task.id) == null)
                            actionError = "原任务已过期，请从对应内容重新开始增强。"
                    }) { Text("重试") }
                    if (task.phase == EnhancementTaskPhase.PAUSED) MotionTextButton({ center.scheduler.resume(task.id) }) { Text("继续") }
                    if (task.phase in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING, EnhancementTaskPhase.RUNNING))
                        MotionTextButton({ center.scheduler.pause(task.id) }) { Text("暂停") }
                    if (task.phase in setOf(EnhancementTaskPhase.QUEUED, EnhancementTaskPhase.WAITING,
                        EnhancementTaskPhase.RUNNING, EnhancementTaskPhase.PAUSED))
                        MotionTextButton({
                            actionError = null
                            if (onCancelTask != null) {
                                if (!onCancelTask(task.id)) actionError = "任务状态已变化，请查看当前处理状态。"
                            } else center.scheduler.cancel(task.id)
                        }) { Text("取消") }
                }
            }
            EnhancementDivider()
        }
    }
    if (!showFinished && finished.isNotEmpty()) EnhancementDetailToggle(false, { showFinished = true },
        "已结束记录 · ${finished.size} 项")
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
