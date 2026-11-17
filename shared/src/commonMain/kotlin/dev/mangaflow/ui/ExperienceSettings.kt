package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.experience.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionSlider
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class ExperienceWorkspace { OVERVIEW, TUNING, DIAGNOSTICS }

/** One retained workspace saves each accepted edit, with a single serialized latest-value writer. */
@Composable
internal fun ExperienceSettingsDialog(visible: Boolean, backdrop: HazeState?, onDismiss: () -> Unit,
    onOpenStorage: (() -> Unit)? = null, onOpenNetwork: (() -> Unit)? = null,
    onOpenSourceChecks: (() -> Unit)? = null) {
    val context = LocalMangaContext.current
    val store = remember(context) { ExperiencePolicyStore.get(context) }
    val savedBudget by store.state.collectAsStateWithLifecycle()
    val savedCustom by store.overrides.collectAsStateWithLifecycle()
    val liveUsage by store.usage.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    var applying by remember { mutableStateOf(false) }
    val applyScope = rememberCoroutineScope()
    val latestVisible by rememberUpdatedState(visible)
    var hostAlive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { hostAlive = false } }
    val active = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var baseline by remember(store) { mutableStateOf(store.overrides.value) }
    var baselineMode by remember(store) { mutableStateOf(store.state.value.mode) }
    var draft by remember(store) { mutableStateOf(store.overrides.value) }
    var draftMode by remember(store) { mutableStateOf(store.state.value.mode) }
    var workspace by rememberSaveable { mutableStateOf(ExperienceWorkspace.OVERVIEW) }
    var expanded by rememberSaveable { mutableStateOf("reading") }
    var deviceTab by rememberSaveable { mutableStateOf("live") }
    var showLegend by rememberSaveable { mutableStateOf(false) }
    var pendingExit by remember { mutableStateOf<String?>(null) }
    var handoff by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val editor = remember { ExperienceEditor() }
    val dirty = draft != baseline || draftMode != baselineMode || editor.pending
    val conflict = !applying && (store.overrides.value != baseline || store.state.value.mode != baselineMode)
    val preview = remember(draft, draftMode, savedBudget) { store.previewBudget(draft, draftMode) }
    val detailScroll = rememberScrollState()
    val navigationScroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val statusColors = mangaStatusColors()
    fun reload() {
        baseline = store.overrides.value
        baselineMode = store.state.value.mode
        draft = baseline
        draftMode = baselineMode
        notice = null
        pendingExit = null
        editor.clear()
        focus.clearFocus(force = true)
    }
    fun settleEditor(): Boolean {
        if (editor.pending && editor.accept?.invoke() != true) {
            notice = "请修正当前数值，或点 × 取消该项编辑"
            return false
        }
        editor.clear()
        return true
    }
    fun finishExit(destination: String?) {
        handoff = destination?.takeIf { it != "close" }
        pendingExit = null
        focus.clearFocus(force = true)
        onDismiss()
    }
    fun saveChanges() {
        if (applying || (draft == baseline && draftMode == baselineMode)) return
        if (store.overrides.value != baseline || store.state.value.mode != baselineMode) {
            notice = "设置已在其他入口改变，请重新载入"
            return
        }
        notice = null
        applying = true
        applyScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Accepted slider steps survive a host/navigation disposal while disk IO is in flight.
            withContext(NonCancellable) {
                try {
                    // Dragging can produce newer values while persistence is suspended. Keep only
                    // the newest complete configuration and never launch competing disk writes.
                    while (draft != baseline || draftMode != baselineMode) {
                        val candidate = draft
                        val candidateMode = draftMode
                        val saved = store.applyConfiguration(candidateMode, candidate,
                            expectedMode = baselineMode, expectedOverrides = baseline)
                        if (!saved) {
                            notice = "设置已在其他入口改变，请重新载入"
                            return@withContext
                        }
                        baseline = candidate
                        baselineMode = candidateMode
                    }
                    if (hostAlive && latestVisible) pendingExit?.let(::finishExit)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { notice = "设置未保存，请重试" }
                finally { applying = false }
            }
        }
    }
    fun change(value: ExperienceOverrides) {
        draft = value
        saveChanges()
    }
    fun changeMode(value: ExperienceMode) {
        draftMode = value
        saveChanges()
    }
    fun requestExit(destination: String = "close") {
        if (!active || !settleEditor()) return
        focus.clearFocus(force = true)
        if (applying || draft != baseline || draftMode != baselineMode) {
            pendingExit = destination
            saveChanges()
        } else finishExit(destination)
    }
    fun selectWorkspace(next: ExperienceWorkspace) {
        if (!active || pendingExit != null || !settleEditor()) return
        focus.clearFocus(force = true)
        workspace = next
    }
    fun selectCategory(category: String, forecast: Boolean = false) {
        if (!active || pendingExit != null || !settleEditor()) return
        focus.clearFocus(force = true)
        if (forecast) deviceTab = "forecast"
        workspace = if (category == "resources") ExperienceWorkspace.DIAGNOSTICS else ExperienceWorkspace.TUNING
        if (category != "resources") expanded = category
    }
    LaunchedEffect(visible) {
        if (visible) {
            // A host can hide/reopen this retained dialog without taking our close path.
            // Do not replace newer accepted edits while their writer is still draining.
            pendingExit = null
            if (!applying && !dirty) reload()
            workspace = ExperienceWorkspace.OVERVIEW
            expanded = "reading"
            showLegend = false
            detailScroll.scrollTo(0)
        }
    }
    LaunchedEffect(savedCustom, savedBudget.mode) {
        if (visible && !applying && !dirty && editor.field == null) reload()
    }
    LaunchedEffect(workspace, expanded) { detailScroll.scrollTo(0) }
    MotionDialog(visible, onDismissRequest = {
        if (pendingExit != null) pendingExit = null
        else requestExit()
    }, properties = DialogProperties(usePlatformDefaultWidth = false),
        maximumWidth = 1040.dp * LocalMangaWindow.current.fontScale,
        onClosed = {
            val destination = handoff
            handoff = null
            when (destination) {
                "storage" -> onOpenStorage?.invoke()
                "network" -> onOpenNetwork?.invoke()
                "source-checks" -> onOpenSourceChecks?.invoke()
            }
        }) {
        val window = LocalMangaWindow.current
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = if (window.width < 600.dp) 0.dp else 12.dp),
            contentAlignment = Alignment.Center) {
            // Use the host's actual safe/IME constraints, not a 560dp dialog in a tall reader.
            val wide = maxWidth - 32.dp >= 840.dp * window.fontScale
            val shownCategory = when (workspace) {
                ExperienceWorkspace.OVERVIEW -> "overview"
                ExperienceWorkspace.TUNING -> expanded
                ExperienceWorkspace.DIAGNOSTICS -> "resources"
            }
            val interactive = active && pendingExit == null
            LaunchedEffect(wide) { focus.clearFocus(force = true) }
            val panelWidth = if (wide) maxWidth else minOf(maxWidth, 640.dp * window.fontScale)
            val maximumHeight = minOf(maxHeight, window.height * .96f, if (wide) 780.dp else window.height)
                .coerceAtLeast(1.dp)
            val radius = if (window.width < 600.dp) 16.dp else 22.dp
            val panel: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().height(maximumHeight)
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("体验策略", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // A stable header slot replaces the permanently empty bottom action row.
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (applying) MotionBusyIndicator(Modifier.size(16.dp).semantics {
                                contentDescription = "正在保存设置"
                            }, strokeWidth = 1.5.dp)
                        }
                        MotionIconButton(onClick = { showLegend = !showLegend }, enabled = interactive,
                            modifier = Modifier.size(44.dp), tooltip = "图标与数值说明") {
                            Icon(Icons.Outlined.Info, "图标与数值说明", Modifier.size(20.dp),
                                tint = if (showLegend) colors.primary else colors.onSurfaceVariant)
                        }
                        if (onOpenStorage != null) MotionIconButton(onClick = { requestExit("storage") }, enabled = active,
                            modifier = Modifier.size(44.dp), tooltip = "管理与清理缓存") {
                            Icon(Icons.Outlined.CleaningServices, "管理与清理缓存", Modifier.size(20.dp))
                        }
                        MotionIconButton(onClick = { requestExit() }, enabled = active,
                            modifier = Modifier.size(44.dp), tooltip = "关闭体验与资源") {
                            Icon(Icons.Outlined.Close, "关闭体验与资源", Modifier.size(20.dp))
                        }
                    }
                    ExperienceIconChoices(listOf(
                        Triple(ExperienceWorkspace.OVERVIEW, "概览", Icons.Outlined.Dashboard),
                        Triple(ExperienceWorkspace.TUNING, "配置", Icons.Outlined.Tune),
                        Triple(ExperienceWorkspace.DIAGNOSTICS, "诊断", Icons.Outlined.Analytics)),
                        workspace, enabled = interactive, selectedLabel = true, navigation = true, onSelected = ::selectWorkspace)
                    Spacer(Modifier.height(8.dp))
                    if (!wide && workspace == ExperienceWorkspace.TUNING)
                        ExperienceCategoryNavigation(expanded, draft, interactive) { selectCategory(it) }
                    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 0.dp)) {
                        if (wide && workspace == ExperienceWorkspace.TUNING) Column(
                            Modifier.width(176.dp * window.fontScale).fillMaxHeight().verticalScroll(navigationScroll)) {
                            ExperienceCategoryNavigation(expanded, draft, interactive, vertical = true) { selectCategory(it) }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(detailScroll)) {
                            ExperienceReveal(showLegend) { ExperienceNavigationLegend() }
                            if (workspace == ExperienceWorkspace.OVERVIEW) {
                                ExperienceOverview(liveUsage, savedBudget, draft, draftMode, interactive,
                                    onMode = { mode ->
                                        if (interactive && settleEditor()) {
                                            changeMode(mode)
                                            focus.clearFocus(force = true)
                                        }
                                    }, onCategory = { selectCategory(it) })
                            }
                            // Keep one settings composition and one sampler across all three sections.
                            // Overview has no parameter rows; diagnostics selects the existing resources page.
                            ExperienceSettings(interactive, shownCategory,
                                store, preview, savedBudget, editor, draft, ::change,
                                currentDraft = { draft },
                                mode = draftMode, draft = false,
                                deviceTab = deviceTab, onDeviceTabChange = { deviceTab = it },
                                onOpenNetwork = onOpenNetwork?.let { { requestExit("network") } },
                                onOpenSourceChecks = onOpenSourceChecks?.let { { requestExit("source-checks") } })
                        }
                    }
                    // Successful saves do not resize the body. Only actionable failures get a footer.
                    if (notice != null || conflict) {
                        Text(notice ?: "设置已在其他入口改变，请重新载入",
                            Modifier.fillMaxWidth().padding(vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                            color = if (conflict) statusColors.waiting else statusColors.error)
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End) {
                            if (!applying) {
                                if (pendingExit != null) MotionTextButton(onClick = {
                                    val destination = pendingExit
                                    reload()
                                    finishExit(destination)
                                }) { Text("放弃未保存修改") }
                                MotionTextButton(onClick = {
                                    if (conflict) reload() else saveChanges()
                                }) { Text(if (conflict) "重新载入" else "重试") }
                            }
                        }
                    }
                }
            }
            Box(Modifier.width(panelWidth)) {
                if (backdrop != null) LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = radius,
                    role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
                else Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(radius),
                    color = colors.surfaceContainerLow, tonalElevation = 0.dp) { panel() }
            }
        }
    }
}

/** Observed cache use and short routes; forecasts and hardware belong in diagnostics. */
@Composable
private fun ExperienceOverview(usage: ExperienceUsage, budget: ExperienceBudget, custom: ExperienceOverrides, mode: ExperienceMode,
    enabled: Boolean, onMode: (ExperienceMode) -> Unit, onCategory: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("体验策略", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        ExperienceModeChoices(mode, enabled, onMode)
        ExperienceHint("全局配置；本章准备进度在阅读栏查看。")
        Spacer(Modifier.height(4.dp))
        Text("应用缓存", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ResourceAllocation(Icons.Outlined.Memory, "内存缓存", usage.memoryBytes, usage.memoryLimit,
                Modifier.weight(1f), enabled, draft = false) { onCategory("cache") }
            ResourceAllocation(Icons.Outlined.Storage, "磁盘缓存", usage.diskBytes, usage.diskLimit,
                Modifier.weight(1f), enabled, draft = false, resizePending = usage.diskResizePending) { onCategory("cache") }
        }
        usage.cacheError?.let { Text(it, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error) }
        if (budget.protections.isNotEmpty()) ExperienceProtectionSummary(budget.protections, false)
        ExperienceOverviewLink(Icons.Outlined.AutoStories, "阅读与预读",
            "待读 ${budget.prefetchAhead} 页 · 回看 ${budget.prefetchBehind} 页", enabled) { onCategory("reading") }
        ExperienceOverviewLink(Icons.Outlined.AccountTree, "并行调度",
            "同时预取 ${custom.prefetchRequests ?: budget.prefetchRequests} 个 · 解码 ${custom.decodeRequests ?: budget.decodeRequests} 个", enabled) { onCategory("parallel") }
        ExperienceOverviewLink(Icons.Outlined.Layers, "多源补页",
            "备用预算 ${budget.standbySources} 源", enabled) { onCategory("sources") }
        ExperienceOverviewLink(Icons.Outlined.Download, "后台任务",
            if (custom.allowBackgroundDownloads) (if (custom.yieldToReading) "退到后台继续下载 · 阅读优先" else "退到后台继续下载") else "退到后台暂停下载", enabled) { onCategory("downloads") }
        ExperienceOverviewLink(Icons.Outlined.Analytics, "诊断与保护",
            "观测 · 硬件 · 情景", enabled) { onCategory("resources") }
    }
}

@Composable
private fun ExperienceOverviewLink(icon: ImageVector, title: String, value: String,
    enabled: Boolean, onClick: () -> Unit) {
    MotionTextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
        Icon(icon, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExperienceProtectionSummary(protections: List<String>, draft: Boolean) {
    val statusColors = mangaStatusColors()
    var expanded by remember { mutableStateOf(false) }
    Column {
        MotionTextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.heightIn(min = 44.dp).semantics {
                contentDescription = (if (draft) "草稿预览" else "当前策略") + "，${protections.size} 项约束；点击查看"
            }) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp), tint = statusColors.waiting)
            Spacer(Modifier.width(6.dp))
            Text("${protections.size} 项限制", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ExperienceReveal(expanded) {
            protections.forEach { ExperienceHint(it) }
        }
    }
}

/** Short labels remain visible; diagnostics has its own top-level destination. */
@Composable
private fun ExperienceCategoryNavigation(selectedCategory: String, custom: ExperienceOverrides,
    enabled: Boolean, vertical: Boolean = false, onSelect: (String) -> Unit) {
    val entries = listOf(
        Triple("reading", "阅读", Icons.Outlined.AutoStories),
        Triple("parallel", "并行", Icons.Outlined.AccountTree),
        Triple("sources", "多源", Icons.Outlined.Layers),
        Triple("downloads", "后台", Icons.Outlined.Download),
        Triple("cache", "缓存", Icons.Outlined.Storage))
    fun modified(id: String) = when (id) {
        "reading" -> experienceReadingCustom(custom)
        "parallel" -> experienceParallelCustom(custom)
        "sources" -> experienceSourceCustom(custom)
        "downloads" -> experienceDownloadCustom(custom)
        else -> experienceCacheCustom(custom)
    }
    if (vertical) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { (id, title, icon) ->
            val selected = selectedCategory == id
            MotionTextButton(onClick = { onSelect(id) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (selected) .10f else 0f), RoundedCornerShape(12.dp))
                    .semantics { stateDescription = if (selected) "已选中" else "" },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Icon(icon, null, Modifier.size(19.dp))
                Text(title, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.labelLarge)
                if (modified(id)) Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
        }
    } else ExperienceIconChoices(entries, selectedCategory, enabled = enabled,
        stackedLabels = true, navigation = true, modified = ::modified, onSelected = onSelect)
}

@Composable
private fun ExperienceModeChoices(mode: ExperienceMode, enabled: Boolean, onSelected: (ExperienceMode) -> Unit) {
    ExperienceIconChoices(listOf(
        Triple(ExperienceMode.AUTO, "自动", Icons.Outlined.AutoMode),
        Triple(ExperienceMode.ECO, "节能", Icons.Outlined.BatterySaver),
        Triple(ExperienceMode.PERFORMANCE, "流畅", Icons.Outlined.Speed),
    ), mode, enabled = enabled, selectedLabel = true, onSelected = onSelected)
}

@Composable
private fun <T> ExperienceIconChoices(choices: List<Triple<T, String, ImageVector>>, selectedValue: T,
    enabled: Boolean = true, selectedLabel: Boolean = false, modified: ((T) -> Boolean)? = null,
    stackedLabels: Boolean = false, navigation: Boolean = false, onSelected: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimumSlot = when {
            stackedLabels -> 52.dp * fontScale
            selectedLabel -> 40.dp + MaterialTheme.typography.labelMedium.fontSize.value.dp *
                fontScale * choices.maxOf { it.second.length }
            else -> 44.dp
        }
        val trackWidth = maxOf(maxWidth, minimumSlot * choices.size)
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            MotionTabs(selected = choices.indexOfFirst { it.first == selectedValue }, labels = choices.map { it.second },
                onSelect = { if (enabled) onSelected(choices[it].first) }, role = if (navigation) Role.Tab else Role.RadioButton,
                modifier = Modifier.width(trackWidth).motionInputGate(enabled),
                foreground = colors.primary, muted = colors.onSurfaceVariant,
                track = colors.onSurface.copy(alpha = if (navigation) 0f else .025f),
                indicator = colors.primary.copy(alpha = if (navigation) .85f else .10f),
                fullWidthIndicator = !navigation, controlHeight = if (stackedLabels) 56.dp * fontScale else 48.dp,
                treatment = if (navigation) MotionTabTreatment.Underline else MotionTabTreatment.Soft,
                itemContent = { index, selected, color ->
                    val (id, title, icon) = choices[index]
                    val labelModifier = Modifier.semantics {
                        contentDescription = title
                        stateDescription = if (modified?.invoke(id) == true) "已自定义" else ""
                    }
                    val glyph: @Composable () -> Unit = {
                        Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
                            Icon(if (!navigation && selected) Icons.Outlined.Check else icon, null, Modifier.size(18.dp), tint = color)
                            if (modified?.invoke(id) == true) Box(Modifier.align(Alignment.TopEnd).size(4.dp)
                                .background(colors.primary, CircleShape))
                        }
                    }
                    if (stackedLabels) Column(labelModifier, horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        glyph()
                        Text(title, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
                    } else Row(labelModifier, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        glyph()
                        if (selectedLabel) Text(title, style = MaterialTheme.typography.labelMedium,
                            color = color, maxLines = 1)
                    }
                })
        }
    }
}

@Composable
private fun ExperienceNavigationLegend() {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ResourceValue(Icons.Outlined.AutoMode, "自动", "随设备调整")
        ResourceValue(Icons.Outlined.BatterySaver, "节能", "减少预取与后台工作")
        ResourceValue(Icons.Outlined.Speed, "流畅", "提前准备更多内容")
        ResourceValue(Icons.Outlined.AutoStories, "阅读", "预读与回看")
        ResourceValue(Icons.Outlined.AccountTree, "并行", "请求与解码数量")
        ResourceValue(Icons.Outlined.Layers, "多源", "备用、补页与切源")
        ResourceValue(Icons.Outlined.Download, "后台", "下载与任务让行")
        ResourceValue(Icons.Outlined.Storage, "缓存", "容量、时效与清理")
        ResourceValue(Icons.Outlined.Analytics, "诊断", "硬件、观测与保护")
        ExperienceHint("绿点／勾表示对应状态已确认，蓝色表示处理或编辑，时钟／暂停的琥珀色表示等待或约束，红色表示失败，问号／— 表示未知。蓝点表示自定义，自动图标表示跟随策略；拖动立即保存，键盘输入点勾或回车保存。")
        ExperienceHint("实测是当前占用；≈ 是配置情景估算，不是硬上限或安全保证。缓存占满不等于故障；详细估算放在诊断的情景页。")
    }
}

/** One named category shares the same focused page on both widths. */
@Composable
private fun ExperienceCategorySection(title: String, icon: ImageVector, modified: Boolean,
    content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (modified) Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                .semantics { contentDescription = "已自定义" })
        }
        content()
    }
}

/** Existing Ovio selection and retained rows keep one reading-first workspace. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ExperienceSettings(active: Boolean, expanded: String,
    store: ExperiencePolicyStore, budget: ExperienceBudget, appliedBudget: ExperienceBudget, editor: ExperienceEditor, custom: ExperienceOverrides,
    onChange: (ExperienceOverrides) -> Unit, currentDraft: () -> ExperienceOverrides,
    mode: ExperienceMode,
    draft: Boolean, deviceTab: String, onDeviceTabChange: (String) -> Unit,
    onOpenNetwork: (() -> Unit)?, onOpenSourceChecks: (() -> Unit)?) {
    val context = LocalMangaContext.current
    val usage by store.usage.collectAsStateWithLifecycle()
    val appliedCustom by store.overrides.collectAsStateWithLifecycle()
    val automaticMemoryBudget = remember(custom, mode, appliedBudget) {
        store.previewBudget(custom.copy(memoryMiB = null), mode)
    }
    val automaticMemoryBytes = estimateExperienceResources(automaticMemoryBudget, usage).imageMemoryBudgetBytes
    val uiScope = rememberCoroutineScope()
    var clearingMemory by remember { mutableStateOf(false) }
    var cacheNotice by remember { mutableStateOf<String?>(null) }
    var cacheClearFailed by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var cacheTab by rememberSaveable { mutableStateOf("images") }
    var cacheKind by rememberSaveable { mutableStateOf(ContentCacheKind.SEARCH) }
    val cacheSampleSeconds by rememberUpdatedState(appliedBudget.runtime.cacheSampleSeconds)
    fun closeEditor() {
        editor.clear()
        focus.clearFocus(force = true)
    }
    fun finishEditor(): Boolean {
        if (editor.pending && editor.accept?.invoke() != true) return false
        closeEditor()
        return true
    }
    fun change(value: ExperienceOverrides) {
        if (!active) return
        onChange(value)
    }
    fun toggleDetail(key: String) {
        if (!active) return
        if (editor.pending && editor.accept?.invoke() != true) return
        closeEditor()
        detail = if (detail == key) null else key
    }
    LaunchedEffect(active, expanded) {
        closeEditor()
    }
    LaunchedEffect(expanded) { detail = null }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        store.refreshResources()
        while (true) { store.refreshCacheUsage(); delay(cacheSampleSeconds.toLong() * 1_000L) }
    }
    Column(Modifier.fillMaxWidth().motionInputGate(active)) {
        Spacer(Modifier.height(4.dp))

        if (expanded == "reading") ExperienceCategorySection("阅读", Icons.Outlined.AutoStories,
            modified = experienceReadingCustom(custom)) {
            Text("预读方式", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExperienceIconChoices(listOf(
                Triple(PrefetchMode.SMART, "智能", Icons.Outlined.AutoMode),
                Triple(PrefetchMode.OFF, "关闭", Icons.Outlined.Block),
                Triple(PrefetchMode.MANUAL, "手动", Icons.Outlined.Tune),
            ), custom.prefetchMode, selectedLabel = true) {
                if (finishEditor()) change(currentDraft().copy(prefetchMode = it))
            }
            ExperienceReveal(custom.prefetchMode == PrefetchMode.MANUAL) {
                ExperienceLiveNumber("待读窗口", custom.ahead, budget.goal.prefetchAhead, 0..40, "页",
                    editor, active && expanded == "reading" && custom.prefetchMode == PrefetchMode.MANUAL, effective = appliedBudget.prefetchAhead) { change(custom.copy(ahead = it)) }
                ExperienceLiveNumber("回看窗口", custom.behind, budget.goal.prefetchBehind, 0..8, "页",
                    editor, active && expanded == "reading" && custom.prefetchMode == PrefetchMode.MANUAL, effective = appliedBudget.prefetchBehind) { change(custom.copy(behind = it)) }
            }
            ExperienceReveal(custom.prefetchMode != PrefetchMode.OFF) {
                ExperienceLiveNumber("临近页预解码", custom.decodedPrefetchPages, budget.goal.decodedPrefetchPages, 0..8, "页",
                    editor, active && expanded == "reading" && custom.prefetchMode != PrefetchMode.OFF,
                    effective = appliedBudget.decodedPrefetchPages) { change(custom.copy(decodedPrefetchPages = it)) }
                SettingsToggle("提前准备下一章", custom.crossChapter, Icons.Outlined.SkipNext) { change(custom.copy(crossChapter = it)) }
                ExperienceReveal(custom.crossChapter) {
                    ExperienceLiveNumber("下一章准备", custom.runtime.nextChapterPages, 3, 0..24, "页",
                        editor, active && expanded == "reading" && custom.crossChapter && custom.prefetchMode != PrefetchMode.OFF,
                        effective = appliedBudget.runtime.nextChapterPages, automaticLabel = "默认值") {
                        change(custom.copy(runtime = custom.runtime.copy(nextChapterPages = it ?: 3)))
                    }
                }
                ExperienceHint(when {
                    !custom.crossChapter -> "翻到下一章时再加载。"
                    !budget.crossChapter || minOf(budget.prefetchAhead, budget.runtime.nextChapterPages) == 0 -> "跨章预读当前已暂停。"
                    else -> "临近章末时，准备下一章前 ${minOf(budget.prefetchAhead, budget.runtime.nextChapterPages)} 页原图。"
                })
            }
            SettingsToggle("浏览时提前准备", custom.browseWarmup, Icons.Outlined.AutoStories) { change(custom.copy(browseWarmup = it)) }
            if (custom.browseWarmup && !budget.browseWarmup) ExperienceHint("浏览预热当前已暂停")
            Text("阅读状态栏位置", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExperienceIconChoices(listOf(Triple(ReaderStatusPlacement.TOP, "顶部", Icons.Outlined.VerticalAlignTop),
                Triple(ReaderStatusPlacement.BOTTOM, "底部", Icons.Outlined.VerticalAlignBottom),
                Triple(ReaderStatusPlacement.HIDDEN, "隐藏", Icons.Outlined.VisibilityOff)),
                custom.readerStatusPlacement, selectedLabel = true) {
                if (finishEditor()) change(currentDraft().copy(readerStatusPlacement = it))
            }
            ExperienceTools("reading", "阅读与预取", active && expanded == "reading", detail, ::toggleDetail,
                experienceReadingCustom(custom), onRestore = {
                    closeEditor()
                    change(custom.copy(prefetchMode = PrefetchMode.SMART, ahead = null, behind = null,
                        crossChapter = true, browseWarmup = true, decodedPrefetchPages = null, readerStatusPlacement = ReaderStatusPlacement.BOTTOM,
                        runtime = resetExperienceRuntime(custom.runtime, ExperienceRuntimeGroup.READING)))
                }, extra = {
                    ExperienceRuntimeSettings(ExperienceRuntimeGroup.READING, custom, budget, appliedBudget, usage, store,
                        editor, active && expanded == "reading" && detail == "reading-advanced", ::change)
                }) {
                ExperienceBudgetRow("窗口目标", budget.goal.prefetchAhead.toString() + " 待读 · " + budget.goal.prefetchBehind + " 回看")
                ExperienceHint("待读和回看跟随实际翻页方向，不固定为屏幕左右。窗口是候选页数，解析、传输、解码各有并发上限；原图存磁盘，临近预览进内存。")
                ExperienceHint("自动图标表示跟随策略，强调色表示手动值。拖动直接保存，手动输入不设业务上限；配置数量不等于正在执行的数量。")
                ExperienceHint("阅读底栏贴近安全区，四个固定槽位只展示状态。点击槽位查看数量、原因和操作，不自动展开遮挡画面。")
            }
        }

        if (expanded == "parallel") ExperienceCategorySection("并行", Icons.Outlined.AccountTree,
            modified = experienceParallelCustom(custom)) {
            ExperienceHint("同时处理的任务上限，不是提前准备的页数。")
            ExperienceLiveNumber("当前页请求", custom.readerRequests, budget.goal.readerRequests, 1..4, "任务",
                editor, active && expanded == "parallel", effective = appliedBudget.readerRequests) { change(custom.copy(readerRequests = it)) }
            ExperienceLiveNumber("图址解析", custom.resolveRequests, budget.goal.resolveRequests, 1..8, "任务",
                editor, active && expanded == "parallel", effective = appliedBudget.resolveRequests) { change(custom.copy(resolveRequests = it)) }
            ExperienceLiveNumber("原图预取", custom.prefetchRequests, budget.goal.prefetchRequests, 1..12, "任务",
                editor, active && expanded == "parallel", effective = appliedBudget.prefetchRequests) { change(custom.copy(prefetchRequests = it)) }
            ExperienceLiveNumber("图片解码", custom.decodeRequests, budget.goal.decodeRequests, 1..4, "任务",
                editor, active && expanded == "parallel", effective = appliedBudget.decodeRequests) { change(custom.copy(decodeRequests = it)) }
            ExperienceLiveNumber("下载页面", custom.downloadPages, budget.goal.backgroundPages, 1..maximumBackgroundPageParallelism, "任务",
                editor, active && expanded == "parallel", effective = appliedBudget.backgroundPages) { change(custom.copy(downloadPages = it)) }
            ExperienceLiveNumber("每源请求", custom.sourcePages, budget.goal.sourceParallelism, 1..8, "任务",
                editor, active && expanded == "parallel", effective = appliedBudget.sourceParallelism) { change(custom.copy(sourcePages = it)) }
            ExperienceLiveNumber("下载章节", custom.chapters, budget.goal.chapterParallelism, 1..4, "章",
                editor, active && expanded == "parallel", effective = appliedBudget.chapterParallelism) { change(custom.copy(chapters = it)) }
            ExperienceTools("parallel", "并行", active && expanded == "parallel", detail, ::toggleDetail,
                experienceParallelCustom(custom), onRestore = {
                    closeEditor(); change(custom.copy(readerRequests = null, resolveRequests = null, prefetchRequests = null,
                        decodeRequests = null, downloadPages = null, sourcePages = null, chapters = null,
                        runtime = resetExperienceRuntime(custom.runtime, ExperienceRuntimeGroup.PARALLEL)))
                }, extra = {
                    ExperienceRuntimeSettings(ExperienceRuntimeGroup.PARALLEL, custom, budget, appliedBudget, usage, store,
                        editor, active && expanded == "parallel" && detail == "parallel-advanced", ::change)
                }) {
                ExperienceHint("数字是各阶段允许同时执行的任务数。一个请求不等于一条系统线程，也不保证占满一个CPU核心。")
                ExperienceHint("自动项按设备与来源反馈调整；手动值保留。限流、系统暂停和存储故障会显示真实等待原因。")
            }
        }

        if (expanded == "sources") ExperienceCategorySection("多源", Icons.Outlined.Layers,
            modified = experienceSourceCustom(custom)) {
            ExperienceIconChoices(listOf<Triple<SourceRecoveryMode?, String, ImageVector>>(
                Triple(null, "跟随", Icons.Outlined.Sync), Triple(SourceRecoveryMode.OFF, "关闭", Icons.Outlined.Block),
                Triple(SourceRecoveryMode.BALANCED, "稳健", Icons.Outlined.Shield),
                Triple(SourceRecoveryMode.AGGRESSIVE, "积极", Icons.Outlined.Speed)), custom.sourceRecovery, selectedLabel = true) {
                if (finishEditor()) change(currentDraft().copy(sourceRecovery = it))
            }
            if (custom.sourceRecovery == null) ExperienceBudgetRow("预览", sourceRecoveryLabel(budget.sourceRecovery))
            ExperienceBudgetRow("预热预算", budget.standbySources.toString() + " 源 · " + experienceCacheSize(budget.standbyPrefetchBytes))
            budget.standbyPauseReason?.let { ExperienceHint(it) }
            ExperienceLiveNumber("同时备用来源", custom.standbySources,
                if (budget.sourceRecovery == SourceRecoveryMode.AGGRESSIVE) 2 else 1, 1..4, "源",
                editor, active && expanded == "sources", effective = appliedBudget.standbySources, automaticLabel = "预热模式") {
                change(custom.copy(standbySources = it))
            }
            ExperienceLiveNumber("每源附近预热", custom.standbyPages, 3, 1..12, "页",
                editor, active && expanded == "sources", effective = appliedBudget.standbyPages) { change(custom.copy(standbyPages = it ?: 3)) }
            ExperienceLiveNumber("备用原图窗口", custom.standbyMiB,
                (budget.goal.prefetchBytes / 4L / EXPERIENCE_MIB).toInt(), 1..128, "MB",
                editor, active && expanded == "sources", effective = (appliedBudget.standbyPrefetchBytes / EXPERIENCE_MIB).toInt()) {
                change(custom.copy(standbyMiB = it))
            }
            SettingsToggle("备用小图存入内存", custom.standbyMemoryWarmup, Icons.Outlined.Memory) {
                change(custom.copy(standbyMemoryWarmup = it))
            }
            ExperienceHint(if (custom.standbyMemoryWarmup) "原图 → 磁盘 · 小图 → 内存；受实际解码预算限制"
                else "原图 → 磁盘；切换时再解码，不预先占用解码内存")
            SettingsToggle("先补坏页", custom.recovery.autoRepair, Icons.Outlined.Healing) {
                change(custom.copy(recovery = custom.recovery.copy(autoRepair = it)))
            }
            SettingsToggle("连续失败切整章", custom.recovery.autoSwitch, Icons.Outlined.SwapHoriz) {
                change(custom.copy(recovery = custom.recovery.copy(autoSwitch = it)))
            }
            SettingsToggle("暂停自动补页与自动切源（全局）", custom.recovery.sourceLocked, Icons.Outlined.Lock) {
                change(custom.copy(recovery = custom.recovery.copy(sourceLocked = it)))
            }
            if (usage.throttledSources > 0) ExperienceHint("限流 · " + usage.throttledSources + " 源")
            ExperienceTools("sources", "多源与补页", active && expanded == "sources", detail, ::toggleDetail,
                experienceSourceCustom(custom), onRestore = {
                    closeEditor(); change(custom.copy(sourceRecovery = null, standbySources = null, standbyMiB = null,
                        standbyPages = 3, standbyMemoryWarmup = true,
                        runtime = resetExperienceRuntime(custom.runtime, ExperienceRuntimeGroup.SOURCES),
                        recovery = dev.mangaflow.reader.ReaderRecoveryPreferences()))
                }, extra = {
                    ExperienceRuntimeSettings(ExperienceRuntimeGroup.SOURCES, custom, budget, appliedBudget, usage, store,
                        editor, active && expanded == "sources" && detail == "sources-advanced", ::change)
                    ExperienceRecoverySettings(custom, appliedBudget, editor,
                        active && expanded == "sources" && detail == "sources-advanced", ::change)
                }) {
                ExperienceBudgetRow("模式目标", "备用至多 " + budget.goal.standbySources + " 源")
                ExperienceHint("备用源先准备附近原图。坏页只使用已确认的同页关系替换；页数比例只帮助定位，不能证明画面相同。人工对齐与撤销在阅读器中完成。")
                ExperienceHint("不同来源并行准备，当前位置优先；限流或预算用尽会显示原因。磁盘原图与内存预览遵循“缓存与离线”的独立保留策略。")
                ExperienceHint("未指定容量时自动分配备用窗口；手动容量按输入独立保留。翻页后只释放已退出窗口且结束的任务额度；磁盘旧原图仍按缓存策略回收。")
            }
        }

        if (expanded == "downloads") ExperienceCategorySection("后台", Icons.Outlined.Download,
            modified = experienceDownloadCustom(custom)) {
            SettingsToggle("阅读时下载让路", custom.yieldToReading, Icons.Outlined.AutoStories) { change(custom.copy(yieldToReading = it)) }
            ExperienceHint(when {
                !custom.yieldToReading -> "阅读与下载同时进行。"
                appliedBudget.downloadsYieldingToReader -> "正在为阅读让路：新下载暂缓，在途传输继续。"
                else -> "当前页等图时暂停新下载，已开始的传输继续。"
            })
            SettingsToggle("允许后台下载", custom.allowBackgroundDownloads, Icons.Outlined.Download) { change(custom.copy(allowBackgroundDownloads = it)) }
            if (onOpenNetwork != null || onOpenSourceChecks != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onOpenNetwork != null) MotionTextButton(onClick = onOpenNetwork, enabled = active && expanded == "downloads",
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                    Icon(Icons.Outlined.NetworkCheck, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp)); Text("线路检查", style = MaterialTheme.typography.labelLarge)
                }
                if (onOpenSourceChecks != null) MotionTextButton(onClick = onOpenSourceChecks, enabled = active && expanded == "downloads",
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                    Icon(Icons.Outlined.Sync, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp)); Text("来源检查", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (usage.activeBackgroundPages > 0 || usage.activeChapters > 0)
                ExperienceBudgetRow("正在下载", usage.activeBackgroundPages.toString() + " 页 · " + usage.activeChapters + " 章")
            ExperienceTools("downloads", "下载与后台", active && expanded == "downloads", detail, ::toggleDetail,
                experienceDownloadCustom(custom), onRestore = {
                    closeEditor()
                    change(custom.copy(yieldToReading = true, allowBackgroundDownloads = true,
                        runtime = resetExperienceRuntime(custom.runtime, ExperienceRuntimeGroup.BACKGROUND)))
                }, extra = {
                    ExperienceRuntimeSettings(ExperienceRuntimeGroup.BACKGROUND, custom, budget, appliedBudget, usage, store,
                        editor, active && expanded == "downloads" && detail == "downloads-advanced", ::change)
                }) {
                ExperienceHint("未指定每源并发时按真实反馈调整；手动并发直接生效。服务端限流时按实际回执等待，正在传输的页面继续完成。")
                ExperienceHint(when (platformKind) {
                    PlatformKind.DESKTOP -> "退出应用后停止下载，重新打开后核对并续传。"
                    PlatformKind.IOS -> "在系统允许的延长执行时段内继续；系统暂停或强制退出后，重新打开续传。"
                    else -> "后台下载受系统任务配额和通知权限限制；中断后保留可用断点。"
                })
            }
        }

        if (expanded == "cache") ExperienceCategorySection("缓存", Icons.Outlined.Storage,
            modified = experienceCacheCustom(custom)) {
            ExperienceIconChoices(listOf(Triple("images", "图片缓存", Icons.Outlined.Photo),
                Triple("data", "数据缓存", Icons.Outlined.Storage), Triple("areas", "占用明细", Icons.Outlined.PieChart)),
                cacheTab, selectedLabel = true, navigation = true) {
                if (finishEditor()) cacheTab = it
            }
            if (cacheTab == "images") {
            Text("内存缓存", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExperienceMemoryCacheSummary(usage, custom.contentCache.memoryIdleMinutes,
                active && expanded == "cache", clearingMemory) {
                    if (active && !clearingMemory) uiScope.launch {
                        clearingMemory = true
                        cacheNotice = null
                        cacheClearFailed = false
                        try {
                            val released = store.clearDecodedMemory().await()
                            cacheNotice = "已释放 " + experienceCacheSize(released) + " 缓存引用"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { cacheClearFailed = true; cacheNotice = "清理未完成，请重试" }
                        finally { clearingMemory = false }
                    }
            }
            cacheNotice?.let {
                val statusColors = mangaStatusColors()
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(if (cacheClearFailed) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, null,
                        Modifier.size(15.dp), tint = if (cacheClearFailed) statusColors.error else statusColors.success)
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = if (cacheClearFailed) statusColors.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ExperienceLiveNumber("内存缓存上限", custom.memoryMiB, automaticMemoryBytes?.let { (it / EXPERIENCE_MIB).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }, 1..512, "MB",
                editor, active && expanded == "cache", effective = usage.memoryLimit?.let { (it / EXPERIENCE_MIB).toInt() }, automaticLabel = "模式预算",
                automaticDisplay = usage.memoryLimit?.let { ((it.toDouble() / EXPERIENCE_MIB * 10).roundToInt() / 10.0).toString() }) {
                change(custom.copy(memoryMiB = it))
            }
            SettingsDisclosure("清理规则", if (custom.contentCache.memoryOutsideWindowCleanup) "范围外清理 · 满额淘汰" else "不按范围清理 · 满额淘汰",
                Icons.Outlined.Tune, expanded = detail == "cache-memory-rules", compact = true,
                onToggle = { toggleDetail("cache-memory-rules") }) {
            SettingsToggle("范围外清理解码缓存", custom.contentCache.memoryOutsideWindowCleanup, Icons.Outlined.FilterCenterFocus) {
                if (active && detail == "cache-memory-rules") change(custom.copy(contentCache = custom.contentCache.copy(memoryOutsideWindowCleanup = it)))
            }
            ExperienceHint(if (custom.contentCache.memoryOutsideWindowCleanup)
                "离开阅读范围且无人使用时清理；当前画面与其他会话保留项不强制释放。"
                else "范围外缓存保留，仍受容量上限与闲置过期限制。")
            ResourceValue(Icons.Outlined.Sort, "容量淘汰", "达到上限时，先清最久未用")
            ExperienceLiveNumber("闲置多久过期", custom.contentCache.memoryIdleMinutes, ContentCachePreferences().memoryIdleMinutes, 0..120, "分钟",
                editor, active && expanded == "cache" && detail == "cache-memory-rules", effective = appliedCustom.contentCache.memoryIdleMinutes,
                automaticLabel = "默认值") {
                change(custom.copy(contentCache = custom.contentCache.copy(memoryIdleMinutes = it ?: ContentCachePreferences().memoryIdleMinutes)))
            }
            ExperienceHint("0 分钟：关闭时间过期，不关闭范围清理或容量淘汰。")
            }
            Text("磁盘缓存", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ResourceAllocation(Icons.Outlined.Storage, "应用磁盘缓存", usage.diskBytes, usage.diskLimit,
                Modifier.fillMaxWidth(), active, draft = false, resizePending = usage.diskResizePending) {
                toggleDetail("cache-help")
            }
            ExperienceLiveNumber("磁盘缓存预算", custom.diskMiB, (budget.goal.diskCacheBytes / EXPERIENCE_MIB).toInt(), 1..8192, "MB",
                editor, active && expanded == "cache", effective = (appliedBudget.diskCacheBytes / EXPERIENCE_MIB).toInt()) {
                change(custom.copy(diskMiB = it))
            }
            SettingsDisclosure("清理规则", (if (custom.contentCache.diskOutsideWindowCleanup) "范围外清理" else "不按范围清理") +
                if (custom.runtime.automaticCleanup) " · 到期/满额淘汰" else " · 定期清理关闭",
                Icons.Outlined.Tune, expanded = detail == "cache-disk-rules", compact = true,
                onToggle = { toggleDetail("cache-disk-rules") }) {
            SettingsToggle("磁盘到期与满额整理", custom.runtime.automaticCleanup, Icons.Outlined.CleaningServices) {
                if (active && detail == "cache-disk-rules") change(custom.copy(runtime = custom.runtime.copy(automaticCleanup = it)))
            }
            ExperienceHint(if (custom.runtime.automaticCleanup) "到期或达到容量上限时清理未受保护的内容。"
                else "定期到期/满额整理关闭；缓存硬上限仍生效，范围外清理单独设置。")
            SettingsToggle("范围外清理原图", custom.contentCache.diskOutsideWindowCleanup, Icons.Outlined.FilterCenterFocus) {
                if (active && detail == "cache-disk-rules") change(custom.copy(contentCache = custom.contentCache.copy(diskOutsideWindowCleanup = it)))
            }
            ExperienceHint(if (custom.contentCache.diskOutsideWindowCleanup)
                "范围外且无人使用的原图可清理；远距离回看可能重新联网。在途、离线和其他会话保留项不删除。"
                else "不因离开阅读范围主动删除；仍受容量、已开启的到期与手动清理影响。")
            val evictionChoices = listOf(
                Triple(CacheEvictionOrder.UNUSED_FIRST, "未使用", Icons.Outlined.VisibilityOff),
                Triple(CacheEvictionOrder.LEAST_RECENT, "最久未用", Icons.Outlined.History),
                Triple(CacheEvictionOrder.LARGEST_FIRST, "较大优先", Icons.Outlined.PhotoSizeSelectLarge))
            Text("容量达到上限时优先清理", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExperienceIconChoices(evictionChoices, custom.runtime.evictionOrder,
                enabled = active && detail == "cache-disk-rules" && custom.runtime.automaticCleanup, selectedLabel = true) {
                if (finishEditor()) {
                    val latest = currentDraft()
                    change(latest.copy(runtime = latest.runtime.copy(evictionOrder = it)))
                }
            }
            ExperienceHint("淘汰顺序仅用于容量不足，不决定范围清理或闲置过期。")
            ExperienceLiveNumber("图片闲置多久过期", custom.contentCache.imageRetentionHours, 0, 0..720, "小时",
                editor, active && expanded == "cache" && detail == "cache-disk-rules", effective = appliedCustom.contentCache.imageRetentionHours,
                automaticLabel = "默认值") { change(custom.copy(contentCache = custom.contentCache.copy(imageRetentionHours = it ?: 0))) }
            if (custom.contentCache.imageRetentionHours == 0) ExperienceHint(if (custom.runtime.automaticCleanup) "0 小时：不过期，满额时仍按所选顺序淘汰。" else "0 小时：不过期；缓存硬上限、范围清理仍独立生效。")
            }
            ExperienceLiveNumber("原图预取窗口", custom.prefetchMiB, (budget.goal.prefetchBytes / EXPERIENCE_MIB).toInt(), 1..512, "MB",
                editor, active && expanded == "cache", effective = (appliedBudget.prefetchBytes / EXPERIENCE_MIB).toInt()) {
                change(custom.copy(prefetchMiB = it))
            }
            ExperienceBudgetRow("邻页 / 跨章 / 备用", experienceCacheSize(budget.adjacentPrefetchBytes) + " / " +
                experienceCacheSize(budget.nextChapterPrefetchBytes) + " / " + experienceCacheSize(budget.standbyPrefetchBytes))
            if (active && expanded == "cache") {
                val downloads = remember(context) { DownloadStore.get(context) }
                val entries by downloads.entries.collectAsStateWithLifecycle()
                ExperienceBudgetRow("离线已保存", experienceCacheSize(entries.sumOf { it.downloadedBytes }))
            }
            if (usage.diskResizePending) ResourceValue(Icons.Outlined.Storage, "容量调整", "调整尚未完成，在用内容保留",
                statusIcon = Icons.Outlined.Schedule, statusColor = mangaStatusColors().waiting)
            usage.cacheError?.let { Text(it, Modifier.padding(vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = mangaStatusColors().error) }
            } else if (cacheTab == "areas") {
                ExperienceCacheBreakdown(usage)
            } else {
                val cache = custom.contentCache
                Text("保存位置", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExperienceIconChoices(listOf(Triple(ContentCacheStorage.MEMORY_AND_DISK, "内存＋盘", Icons.Outlined.Storage),
                    Triple(ContentCacheStorage.MEMORY_ONLY, "仅内存", Icons.Outlined.Memory),
                    Triple(ContentCacheStorage.DISABLED, "关闭", Icons.Outlined.Block)), cache.storage, selectedLabel = true) {
                    if (finishEditor()) {
                        val latest = currentDraft()
                        change(latest.copy(contentCache = latest.contentCache.copy(storage = it)))
                    }
                }
                SettingsToggle("打开页面即更新", cache.refreshOnOpen, Icons.Outlined.Refresh) {
                    change(custom.copy(contentCache = cache.copy(refreshOnOpen = it)))
                }
                ExperienceIconChoices(listOf(
                    Triple(ContentCacheKind.SEARCH, "搜索", Icons.Outlined.Search),
                    Triple(ContentCacheKind.DISCOVERY, "发现", Icons.Outlined.Explore),
                    Triple(ContentCacheKind.RANKING, "榜单", Icons.Outlined.Leaderboard),
                    Triple(ContentCacheKind.DETAILS, "目录", Icons.Outlined.MenuBook),
                    Triple(ContentCacheKind.PAGE_MANIFEST, "图址", Icons.Outlined.Link),
                    Triple(ContentCacheKind.METRICS, "评分", Icons.Outlined.StarOutline),
                    Triple(ContentCacheKind.POPULARITY, "热度", Icons.Outlined.LocalFireDepartment),
                    Triple(ContentCacheKind.COMMENTS, "评论", Icons.Outlined.ChatBubbleOutline)), cacheKind, navigation = true) {
                    if (finishEditor()) cacheKind = it
                }
                val minutes = when (cacheKind) {
                    ContentCacheKind.SEARCH -> cache.searchMinutes; ContentCacheKind.DISCOVERY -> cache.discoveryMinutes
                    ContentCacheKind.RANKING -> cache.rankingMinutes; ContentCacheKind.DETAILS -> cache.detailMinutes
                    ContentCacheKind.PAGE_MANIFEST -> cache.manifestMinutes; ContentCacheKind.METRICS -> cache.scoreMinutes
                    ContentCacheKind.POPULARITY -> cache.popularityMinutes; ContentCacheKind.COMMENTS -> cache.commentMinutes
                }
                val defaultMinutes = (ContentCachePreferences().freshMillis(cacheKind) / 60_000L).toInt()
                val currentMinutes = (appliedCustom.contentCache.freshMillis(cacheKind) / 60_000L).toInt()
                ExperienceLiveNumber(when (cacheKind) {
                    ContentCacheKind.SEARCH -> "搜索间隔"; ContentCacheKind.DISCOVERY -> "发现间隔"
                    ContentCacheKind.RANKING -> "榜单间隔"; ContentCacheKind.DETAILS -> "目录间隔"
                    ContentCacheKind.PAGE_MANIFEST -> "图址间隔"; ContentCacheKind.METRICS -> "评分间隔"
                    ContentCacheKind.POPULARITY -> "热度时效上限"; ContentCacheKind.COMMENTS -> "评论间隔"
                },
                    minutes, defaultMinutes, 0..10_080, "分钟", editor, active && expanded == "cache",
                    effective = currentMinutes, automaticLabel = "默认值") { value ->
                    val next = value ?: defaultMinutes
                    change(custom.copy(contentCache = when (cacheKind) {
                        ContentCacheKind.SEARCH -> cache.copy(searchMinutes = next); ContentCacheKind.DISCOVERY -> cache.copy(discoveryMinutes = next)
                        ContentCacheKind.RANKING -> cache.copy(rankingMinutes = next); ContentCacheKind.DETAILS -> cache.copy(detailMinutes = next)
                        ContentCacheKind.PAGE_MANIFEST -> cache.copy(manifestMinutes = next); ContentCacheKind.METRICS -> cache.copy(scoreMinutes = next)
                        ContentCacheKind.POPULARITY -> cache.copy(popularityMinutes = next); ContentCacheKind.COMMENTS -> cache.copy(commentMinutes = next)
                    }))
                }
                val effectiveMinutes = cache.freshMillis(cacheKind) / 60_000L
                ExperienceBudgetRow("更新规则", if (effectiveMinutes == 0L) "每次请求更新" else "访问时超过 $effectiveMinutes 分钟再更新")
                if (cacheKind == ContentCacheKind.POPULARITY) ExperienceHint("热度随列表与详情返回，此上限同时约束这些资料的复用时间；不为每张卡单独请求热度。")
                else if (effectiveMinutes < minutes && cache.enabled && !cache.refreshOnOpen)
                    ExperienceHint("同时受热度资料时效上限约束。")
                ExperienceLiveNumber("失败结果重试间隔", cache.failureMinutes, 1, 0..60, "分钟", editor, active && expanded == "cache",
                    effective = appliedCustom.contentCache.failureMinutes, automaticLabel = "默认值") {
                    change(custom.copy(contentCache = cache.copy(failureMinutes = it ?: 1)))
                }
                ExperienceLiveNumber("快照最长保留", cache.retentionHours, 168, 1..720, "小时", editor, active && expanded == "cache",
                    effective = appliedCustom.contentCache.retentionHours, automaticLabel = "默认值") {
                    change(custom.copy(contentCache = cache.copy(retentionHours = it ?: 168)))
                }
                ExperienceLiveNumber("列表快照上限", cache.snapshotMemoryEntries, 24, 1..64, "份", editor, active && expanded == "cache",
                    effective = appliedCustom.contentCache.snapshotMemoryEntries, automaticLabel = "默认值") {
                    change(custom.copy(contentCache = cache.copy(snapshotMemoryEntries = it ?: 24)))
                }
                ExperienceLiveNumber("列表磁盘上限", cache.snapshotDiskMiB, 8, 1..128, "MB", editor, active && expanded == "cache",
                    effective = appliedCustom.contentCache.snapshotDiskMiB, automaticLabel = "默认值") {
                    change(custom.copy(contentCache = cache.copy(snapshotDiskMiB = it ?: 8)))
                }
            }
            ExperienceTools("cache", "缓存与离线", active && expanded == "cache", detail, ::toggleDetail,
                experienceCacheCustom(custom), onRestore = {
                    closeEditor(); change(custom.copy(memoryMiB = null, diskMiB = null, prefetchMiB = null, contentCache = ContentCachePreferences(),
                        runtime = resetExperienceRuntime(custom.runtime, ExperienceRuntimeGroup.CACHE)
                            .copy(automaticCleanup = true, evictionOrder = CacheEvictionOrder.UNUSED_FIRST)))
                }, extra = {
                    ExperienceRuntimeSettings(ExperienceRuntimeGroup.CACHE, custom, budget, appliedBudget, usage, store,
                        editor, active && expanded == "cache" && detail == "cache-advanced", ::change)
                }) {
                ExperienceHint("用量 / 上限；— 表示尚未取得。内存缓存只包含可复用的解码图像，手动容量按输入保存。磁盘缓存可自动回收，离线章节独立保存；可回收内容在缓存管理中清理。")
                ExperienceHint("闲置时间 0 仅关闭时间过期；范围清理与容量策略独立。内存到期只移除可复用缓存引用，不强行释放当前画面；磁盘图片过期也适用于封面。")
                ExperienceHint("自动磁盘预算会为设备预留空闲空间；手动值按输入保留。共享磁盘包含图片、目录、列表和番剧缓冲，不含离线章节。")
                ExperienceHint("范围清理、容量淘汰与闲置过期是独立触发条件。内存默认清理范围外缓存；磁盘默认不因离窗删除原图。磁盘定期整理开关不关闭范围清理或缓存硬上限。")
                ExperienceHint("页面资料间隔 0＝不复用已完成结果，进行中的请求仍合并。允许先显示旧快照再更新；更新失败保留旧内容。关闭资料缓存不会删除历史、收藏和离线章节。")
                ExperienceHint("预读调度与缓存删除不同：离开窗口取消未完成任务；已完成图片仅在相应清理策略允许且无人使用时移除。范围清理不等于立刻清空用量，阅读记录、收藏和离线章节不受影响。")
            }
        }

        if (expanded == "resources") ExperienceCategorySection("诊断", Icons.Outlined.Analytics,
            modified = experienceDeviceCustom(custom)) {
            ExperienceIconChoices(listOf(Triple("device", "硬件", Icons.Outlined.DeveloperBoard),
                Triple("forecast", "情景", Icons.Outlined.Calculate), Triple("live", "观测", Icons.Outlined.Analytics),
                Triple("protect", "保护", Icons.Outlined.Shield)), deviceTab, selectedLabel = true, navigation = true) {
                if (finishEditor()) onDeviceTabChange(it)
            }
            when (deviceTab) {
                "device" -> ExperienceDeviceDetails(usage)
                "forecast" -> ExperienceForecastDetails(usage, appliedBudget, budget, draft)
                "live" -> {
                    ExperienceBudgetRow("当前 · 章 / 下载 / 预取", usage.activeChapters.toString() + " / " + usage.activeBackgroundPages + " / " + usage.activePrefetch)
                    ExperienceBudgetRow("当前 · 页面 / 排队", usage.activeReaderImages.toString() + " / " + usage.queuedTasks)
                    ExperienceBudgetRow("解析 / 解码执行", usage.activeResolves.toString() + " / " + usage.activeDecodes)
                    ExperienceBudgetRow("解码 P95 / 排队 P95", (usage.decodeP95Millis?.let { "$it ms" } ?: "—") + " / " +
                        (usage.decodeQueueP95Millis?.let { "$it ms" } ?: "—"))
                    ExperienceBudgetRow("解码累计输出", experienceCacheSize(usage.decodedBytes))
                    ExperienceBudgetRow("限流来源", usage.throttledSources.toString())
                    ExperienceBudgetRow("内存 / 磁盘命中", usage.memoryHits.toString() + " / " + usage.diskHits)
                    ExperienceBudgetRow("预取复用 / 新取", usage.prefetchHits.toString() + " 次 / " + usage.prefetchedPages + " 页")
                    ExperienceBudgetRow("请求 P95", usage.requestP95Millis?.let { "$it ms" } ?: "—")
                    ExperienceBudgetRow("帧工作 P95 / P99", if (usage.frameP95Millis != null && usage.frameP99Millis != null)
                        frameMillis(usage.frameP95Millis!!) + " / " + frameMillis(usage.frameP99Millis!!) else "—")
                    ExperienceBudgetRow("实际传输", experienceCacheSize(usage.transferredBytes))
                    ExperienceBudgetRow("堆内存 / 上限", cacheUsage(usage.resources.processHeapBytes, usage.resources.processHeapLimitBytes))
                    ExperienceHint("累计解码量不是驻留内存；帧工作耗时不是屏幕 FPS。")
                }
                "protect" -> {
                    SettingsToggle("计费网络也可预取", custom.allowMeteredPrefetch, Icons.Outlined.NetworkCheck) { change(custom.copy(allowMeteredPrefetch = it)) }
                    SettingsToggle("低电量减少后台工作", custom.batteryProtection, Icons.Outlined.BatterySaver) { change(custom.copy(batteryProtection = it)) }
            val manualRates = if (platformKind == PlatformKind.ANDROID) usage.resources.supportedRefreshRates else emptyList()
            val choices = listOf<Pair<RefreshPreference?, String>>(
                null to "自动", RefreshPreference.SYSTEM to "系统", RefreshPreference.INTERACTION to "交互") +
                if (manualRates.isNotEmpty() || custom.refresh == RefreshPreference.MANUAL) listOf(RefreshPreference.MANUAL to "指定") else emptyList()
            SettingsChoices(choices, custom.refresh) { change(custom.copy(refresh = it)) }
            ExperienceReveal(custom.refresh == RefreshPreference.MANUAL) {
                if (manualRates.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    manualRates.forEach { rate ->
                        MotionTextButton(onClick = { change(custom.copy(refreshHz = rate)) },
                            enabled = active && expanded == "resources" && deviceTab == "protect" && custom.refresh == RefreshPreference.MANUAL,
                            modifier = Modifier.heightIn(min = 44.dp)) {
                            Text(rate.toString() + " Hz" + if (custom.refreshHz == rate) " ✓" else "")
                        }
                    }
                } else ExperienceHint("设备暂未提供可选刷新率，已保留指定请求")
            }
            ExperienceBudgetRow("预览刷新请求", budget.refreshHz?.let { it.toString() + " Hz" }
                ?: if (budget.preferHighRefresh) "优先高刷" else "跟随系统")

                    ExperienceHint(if (platformKind == PlatformKind.ANDROID) "刷新率是应用请求，最终由屏幕与系统决定。" else "刷新率由系统调度；保留请求，不承诺强制高刷。")
                }
            }
            ExperienceTools("resources", "设备与资源", active && expanded == "resources", detail, ::toggleDetail,
                experienceDeviceCustom(custom), onRestore = {
                    closeEditor(); change(custom.copy(allowMeteredPrefetch = false, batteryProtection = true, refresh = null, refreshHz = null,
                        runtime = resetExperienceRuntime(custom.runtime, ExperienceRuntimeGroup.DEVICE)))
                }, extra = {
                    ExperienceRuntimeSettings(ExperienceRuntimeGroup.DEVICE, custom, budget, appliedBudget, usage, store,
                        editor, active && expanded == "resources" && detail == "resources-advanced", ::change)
                }) {
                ExperienceHint(budget.reason)
                if (budget.protections.isNotEmpty()) ExperienceHint(budget.protections.joinToString(" · "))
                ExperienceHint("自动项按实际资源调整；手动值保留。应用仅显示当前运行设备实际提供的指标，未知不会当作0。")
            }
        }
    }
}

@Composable
private fun ExperienceRuntimeSettings(group: ExperienceRuntimeGroup, custom: ExperienceOverrides,
    budget: ExperienceBudget, currentBudget: ExperienceBudget, usage: ExperienceUsage, store: ExperiencePolicyStore,
    editor: ExperienceEditor, active: Boolean, onChange: (ExperienceOverrides) -> Unit) {
    ExperienceRuntimeField.entries.filter { it.group == group && it != ExperienceRuntimeField.NEXT_CHAPTER }.forEach { field ->
        key(field) {
            val section = when (field) {
                ExperienceRuntimeField.PREDICTION -> "自动窗口"
                ExperienceRuntimeField.PREFETCH_RETRIES -> "失败后重试"
                ExperienceRuntimeField.PREVIEW_WIDTH -> "预览尺寸"
                else -> null
            }
            if (section != null) Text(section, Modifier.padding(top = 12.dp, bottom = 2.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val supported = platformKind != PlatformKind.ANDROID ||
                field != ExperienceRuntimeField.TILE_DECODES && field != ExperienceRuntimeField.FALLBACK_PIXELS
            val automaticBudget = remember(custom, budget, field) {
                store.previewBudget(custom.copy(runtime = field.withValue(custom.runtime, null)), budget.mode)
            }
            val default = field.effectiveValue(automaticBudget, usage)
            val shortcut = field.semanticMaximum ?: when (field.unit) {
                "px" -> 2048; "MiB" -> 1024; "Mpx" -> 64; "秒" -> 120; "页" -> 24; else -> 16
            }
            ExperienceLiveNumber(field.label, field.value(custom.runtime), default,
                field.minimum..maxOf(field.minimum, shortcut), field.unit, editor, active && supported,
                effective = field.effectiveValue(currentBudget, usage), automaticLabel = if (field.followsAutomatic) "自动预算" else "默认值",
                semanticMaximum = field.semanticMaximum) { value ->
                onChange(custom.copy(runtime = field.withValue(custom.runtime, value)))
            }
            if (!supported) ExperienceHint("Android由原生图片引擎管理；保留其他平台的配置。")
            if (editor.field == field.label) ExperienceHint(field.help)
            when (field) {
                ExperienceRuntimeField.IMAGE_CONNECT -> ExperienceTransportLimit(custom.runtime.imageConnectSeconds.toLong() * 1_000L)
                ExperienceRuntimeField.IMAGE_TOTAL -> ExperienceTransportLimit(custom.runtime.imageTotalMinutes.toLong() * 60_000L)
                else -> Unit
            }
        }
    }
}

@Composable
private fun ExperienceRecoverySettings(custom: ExperienceOverrides, currentBudget: ExperienceBudget,
    editor: ExperienceEditor, active: Boolean, onChange: (ExperienceOverrides) -> Unit) {
    val p = custom.recovery
    val actual = currentBudget.recovery
    fun update(value: dev.mangaflow.reader.ReaderRecoveryPreferences) = onChange(custom.copy(recovery = value))
    ExperienceLiveNumber("连续失败", p.consecutiveFailures, 3, 1..12, "页", editor, active,
        effective = actual.consecutiveFailures) { update(p.copy(consecutiveFailures = it ?: 3)) }
    ExperienceLiveNumber("失败观察窗口", p.failureWindow, 6, 1..24, "页", editor, active,
        effective = actual.failureWindow) { update(p.copy(failureWindow = it ?: 6)) }
    ExperienceLiveNumber("窗口内失败", p.failuresInWindow, 3, 1..12, "页", editor, active,
        effective = actual.failuresInWindow) { update(p.copy(failuresInWindow = it ?: 3)) }
    if (p.failuresInWindow > p.failureWindow) ExperienceHint("窗口阈值大于窗口页数，此条件不会触发；连续失败条件仍独立生效。")
    ExperienceLiveNumber("切源冷却", p.switchCooldownSeconds, 60, 0..300, "秒", editor, active,
        effective = actual.switchCooldownSeconds) { update(p.copy(switchCooldownSeconds = it ?: 60)) }
    ExperienceLiveNumber("备用重试", p.standbyRetryCount, 2, 0..8, "次", editor, active,
        effective = actual.standbyRetryCount) { update(p.copy(standbyRetryCount = it ?: 2)) }
    ExperienceLiveNumber("备用重试间隔", p.standbyRetryDelaySeconds, 30, 0..120, "秒", editor, active,
        effective = actual.standbyRetryDelaySeconds) { update(p.copy(standbyRetryDelaySeconds = it ?: 30)) }
    ExperienceLiveNumber("备用目录等待", p.manifestTimeoutSeconds, 25, 1..120, "秒", editor, active,
        effective = actual.manifestTimeoutSeconds) { update(p.copy(manifestTimeoutSeconds = it ?: 25)) }
    ExperienceLiveNumber("图片无新字节等待", p.imageTimeoutSeconds, 45, 1..180, "秒", editor, active,
        effective = (effectiveImageTransportTimeoutMillis(actual.imageTimeoutSeconds.toLong() * 1_000L) / 1_000L).toInt()) {
        update(p.copy(imageTimeoutSeconds = it ?: 45))
    }
    ExperienceTransportLimit(p.imageTimeoutSeconds.toLong() * 1_000L)
    ExperienceLiveNumber("慢加载提示", p.slowLoadSeconds, 8, 1..60, "秒", editor, active,
        effective = actual.slowLoadSeconds) { update(p.copy(slowLoadSeconds = it ?: 8)) }
    ExperienceHint("自动补页使用已经确认的同页关系。排队、取消和资源暂停不计坏页；未对齐的版本先手动确认。整章自动切源还要求目标图可显示。")
}

@Composable
private fun ExperienceTransportLimit(requestedMillis: Long) {
    val limit = imageTransportTimeoutLimitMillis
    if (limit != null && requestedMillis > limit)
        ExperienceHint("保留输入值；此平台图片网络接口实际使用上限 ${limit}ms（约24.9天）。")
}

private fun resetExperienceRuntime(value: ExperienceRuntimePreferences, group: ExperienceRuntimeGroup): ExperienceRuntimePreferences =
    ExperienceRuntimeField.entries.filter { it.group == group }.fold(value) { current, field -> field.withValue(current, null) }

private fun runtimeModified(value: ExperienceRuntimePreferences, group: ExperienceRuntimeGroup): Boolean =
    ExperienceRuntimeField.entries.any { it.group == group && it.value(value) != it.value(ExperienceRuntimePreferences()) }


internal class ExperienceEditor {
    var field by mutableStateOf<String?>(null)
    var pending by mutableStateOf(false)
    var accept: (() -> Boolean)? = null
    // One edit buffer survives changes between the narrow and wide workspace.
    val input = mutableStateOf("")
    val position = mutableFloatStateOf(0f)
    var sliderMaximum by mutableIntStateOf(0)
    val invalidInput = mutableStateOf(false)
    fun clear() { field = null; pending = false; accept = null }
}

/** The same retained reveal/measurement owner used by SettingsDisclosure. */
@Composable
internal fun ExperienceReveal(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalMotionEnabled.current
    val reveal = animateFloatAsState(if (expanded) 1f else 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "experience-edit")
    Column(Modifier.fillMaxWidth().clipToBounds().motionInputGate(expanded)
        .layout { measurable, constraints ->
            if (reveal.value <= 0f) layout(constraints.minWidth.coerceAtLeast(0), 0) {}
            else {
                val body = measurable.measure(constraints.copy(minHeight = 0))
                layout(body.width, (body.height * reveal.value).toInt().coerceAtLeast(0)) { body.placeRelative(0, 0) }
            }
        }.graphicsLayer { alpha = reveal.value },
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

/** The experience dialog saves immediately; other consumers retain explicit numeric acceptance. */
@Composable
private fun ExperienceLiveNumber(title: String, value: Int?, automatic: Int?, range: IntRange, unit: String,
    editor: ExperienceEditor, active: Boolean, effective: Int? = null, automaticLabel: String = "模式目标",
    semanticMaximum: Int? = null, automaticDisplay: String? = null, onChange: (Int?) -> Unit) {
    ExperienceNumber(title, value, automatic, range, unit, editor, active, effective, automaticLabel,
        semanticMaximum, liveSlider = true, automaticDisplay = automaticDisplay, onChange = onChange)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ExperienceNumber(title: String, value: Int?, automatic: Int?, range: IntRange, unit: String,
    editor: ExperienceEditor, active: Boolean, effective: Int? = null, automaticLabel: String = "模式目标",
    semanticMaximum: Int? = null, liveSlider: Boolean = false, automaticDisplay: String? = null,
    onChange: (Int?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selected = value ?: effective ?: automatic
    // An automatic byte budget can be fractional; do not truncate 30.7 MB into a competing 30 MB label.
    val displayed = if (value == null) automaticDisplay ?: selected?.toString() else value.toString()
    val current = (selected ?: range.first).coerceAtLeast(range.first)
    val editing = active && editor.field == title
    // Freeze the shortcut scale for this edit: saving a step must not move the thumb's
    // coordinate system, especially after a manually entered value exceeds the shortcut.
    val sliderMaximum = if (editing) maxOf(range.last, editor.sliderMaximum) else maxOf(range.last, current)
    val focus = LocalFocusManager.current
    var draft by editor.input
    var sliding by editor.position
    var invalid by editor.invalidInput
    fun acceptsInput() = active && editor.field == title
    fun commit(): Boolean {
        if (!acceptsInput()) return false
        val parsed = draft.toIntOrNull()
        if (!editor.pending && parsed == selected) {
            editor.clear()
            focus.clearFocus(force = true)
            return true
        }
        if (parsed == null || parsed < range.first || (semanticMaximum != null && parsed > semanticMaximum)) { invalid = true; return false }
        val accepted = parsed
        draft = accepted.toString()
        sliding = accepted.toFloat()
        invalid = false
        onChange(accepted)
        editor.clear()
        focus.clearFocus(force = true)
        return true
    }
    SideEffect { if (editing) editor.accept = { commit() } }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 300.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            val valueControl: @Composable () -> Unit = {
                MotionTextButton(onClick = {
                    if (active && (editing || !editor.pending || editor.accept?.invoke() == true)) {
                        focus.clearFocus(force = true)
                        if (editing) editor.clear() else {
                            draft = selected?.toString().orEmpty()
                            sliding = current.toFloat()
                            editor.sliderMaximum = maxOf(range.last, current)
                            invalid = false
                            editor.clear()
                            editor.field = title
                        }
                    }
                }, enabled = active, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.widthIn(max = maxWidth).heightIn(min = 44.dp).semantics(mergeDescendants = true) {
                        contentDescription = title + "，" + (displayed?.plus(" " + unit) ?: "自动值尚未知") +
                            (if (value == null) "，跟随" + automaticLabel else "，手动值") +
                            (if (editing) "，点击收起编辑" else "，点击编辑")
                    }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text((displayed ?: "—") + " " + unit, Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodyMedium, color = if (editing || value != null) colors.primary else colors.onSurface)
                            Icon(if (editing) Icons.Outlined.Close else if (value == null) (if (liveSlider) Icons.Outlined.AutoMode else Icons.Outlined.Sync) else Icons.Outlined.ChevronRight,
                                null, Modifier.size(14.dp), tint = if (editing || value != null) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                }
            }
            if (stacked) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { valueControl() }
                }
            } else Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                valueControl()
            }
        }
        ExperienceReveal(editing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicTextField(draft, onValueChange = {
                    if (acceptsInput() && it.length <= Int.MAX_VALUE.toString().length && it.all(Char::isDigit)) {
                        draft = it; invalid = false; editor.pending = draft != selected?.toString().orEmpty()
                    }
                }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                        .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 10.dp, vertical = 12.dp)
                        .semantics { contentDescription = title + "数值，单位" + unit },
                    decorationBox = { input ->
                        Box {
                            if (draft.isEmpty()) Text("输入数值", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            input()
                        }
                    })
                Text(unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                MotionTextButton(onClick = { commit() }, enabled = editing,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 44.dp).semantics {
                        contentDescription = "保存" + title + "数值"
                    }) {
                    Icon(Icons.Outlined.Check, "保存数值", Modifier.size(18.dp))
                }
                MotionIconButton(onClick = {
                    if (acceptsInput()) {
                        editor.clear()
                        focus.clearFocus(force = true)
                        onChange(null)
                    }
                }, enabled = editing, modifier = Modifier.size(44.dp), tooltip = "恢复" + title + "跟随" + automaticLabel) {
                    Icon(Icons.Outlined.Sync, "恢复" + title + "跟随" + automaticLabel, Modifier.size(18.dp))
                }
            }
            if (semanticMaximum != null) Text(range.first.toString() + "–" + semanticMaximum + " " + unit,
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (invalid) Text(if (semanticMaximum != null) "请输入 " + range.first + "–" + semanticMaximum + " 的整数"
                else "请输入不小于 " + range.first + " 的有效整数", style = MaterialTheme.typography.labelSmall, color = colors.error)
            MotionSlider(sliding, onValueChange = {
                if (acceptsInput()) {
                    val accepted = it.roundToInt().coerceAtLeast(range.first)
                    sliding = accepted.toFloat()
                    draft = accepted.toString()
                    invalid = false
                    editor.pending = !liveSlider && draft != selected?.toString().orEmpty()
                    if (liveSlider) onChange(accepted)
                }
            }, valueRange = range.first.toFloat()..sliderMaximum.toFloat(), onValueChangeFinished = {
                // Live experience steps are already saved. Other consumers retain their local
                // numeric buffer until the check/IME Done action accepts it.
            }, formatValue = { it.roundToInt().toString() + " " + unit },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title + "滑块" })
            if (liveSlider) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(range.first.toString() + " " + unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(sliderMaximum.toString() + " " + unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            if (liveSlider) ExperienceHint(if (semanticMaximum == null) "拖动即保存；更大数值可直接输入。" else "拖动即保存。")
        }
    }
}

/** Existing icon presses and retained disclosure share one local, fixed-height tool row. */
@Composable
private fun ExperienceTools(key: String, title: String, active: Boolean, detail: String?, onDetail: (String) -> Unit,
    modified: Boolean, onRestore: () -> Unit, extraLabel: String = "高级参数",
    extraIcon: ImageVector = Icons.Outlined.Tune, extra: (@Composable ColumnScope.() -> Unit)? = null,
    help: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val helpOpen = detail == key + "-help"
    val extraOpen = detail == key + "-advanced"
    Column(Modifier.fillMaxWidth().motionInputGate(active)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionTextButton(onClick = { onDetail(key + "-help") }, enabled = active,
                modifier = Modifier.heightIn(min = 44.dp).semantics { stateDescription = if (helpOpen) "已展开" else "已收起" },
                contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(16.dp))
                Text("说明", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
            if (extra != null) MotionTextButton(onClick = { onDetail(key + "-advanced") }, enabled = active,
                modifier = Modifier.heightIn(min = 44.dp).semantics { stateDescription = if (extraOpen) "已展开" else "已收起" },
                contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(extraIcon, null, Modifier.size(16.dp))
                Text(extraLabel, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
                Icon(if (extraOpen) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(14.dp))
            }
            Spacer(Modifier.weight(1f))
            MotionSwap(modified, Modifier.size(44.dp)) { shown ->
                if (shown) MotionIconButton(onClick = onRestore, enabled = active && modified,
                    modifier = Modifier.size(44.dp), tooltip = "恢复" + title + "默认设置") {
                    Icon(Icons.Outlined.RestartAlt, "恢复" + title + "默认设置", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
        ExperienceReveal(extraOpen) { extra?.invoke(this) }
        ExperienceReveal(helpOpen, help)
    }
}

private fun experienceReadingCustom(value: ExperienceOverrides) =
    value.prefetchMode != PrefetchMode.SMART || value.ahead != null || value.behind != null ||
        !value.crossChapter || !value.browseWarmup || value.decodedPrefetchPages != null || value.readerStatusPlacement != ReaderStatusPlacement.BOTTOM || runtimeModified(value.runtime, ExperienceRuntimeGroup.READING)

private fun experienceParallelCustom(value: ExperienceOverrides) =
    value.readerRequests != null || value.resolveRequests != null || value.prefetchRequests != null ||
        value.decodeRequests != null || value.downloadPages != null || value.sourcePages != null || value.chapters != null || runtimeModified(value.runtime, ExperienceRuntimeGroup.PARALLEL)

private fun experienceDownloadCustom(value: ExperienceOverrides) = !value.yieldToReading || !value.allowBackgroundDownloads ||
    runtimeModified(value.runtime, ExperienceRuntimeGroup.BACKGROUND)

private fun experienceCacheCustom(value: ExperienceOverrides) = value.memoryMiB != null || value.diskMiB != null || value.prefetchMiB != null ||
    value.contentCache != ContentCachePreferences() || !value.runtime.automaticCleanup || value.runtime.evictionOrder != CacheEvictionOrder.UNUSED_FIRST ||
    runtimeModified(value.runtime, ExperienceRuntimeGroup.CACHE)

private fun experienceSourceCustom(value: ExperienceOverrides) = value.sourceRecovery != null || value.standbySources != null ||
    value.standbyMiB != null || value.standbyPages != 3 || !value.standbyMemoryWarmup ||
    value.recovery != dev.mangaflow.reader.ReaderRecoveryPreferences() || runtimeModified(value.runtime, ExperienceRuntimeGroup.SOURCES)

private fun experienceDeviceCustom(value: ExperienceOverrides) = value.allowMeteredPrefetch || !value.batteryProtection || value.refresh != null ||
    value.refreshHz != null || runtimeModified(value.runtime, ExperienceRuntimeGroup.DEVICE)

private fun sourceRecoveryLabel(value: SourceRecoveryMode) = when (value) {
    SourceRecoveryMode.OFF -> "按需切源"
    SourceRecoveryMode.BALANCED -> "稳健预热"
    SourceRecoveryMode.AGGRESSIVE -> "积极预热"
}

@Composable
private fun ExperienceBudgetRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.25f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
@Composable
private fun ExperienceHint(value: String) = Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
private fun cacheUsage(used: Long?, limit: Long?) = (used?.let(::experienceCacheSize) ?: "—") + " / " + (limit?.let(::experienceCacheSize) ?: "—")
private fun frameMillis(value: Double) = ((value * 10).roundToInt() / 10.0).toString() + " ms"
internal fun experienceModeLabel(mode: ExperienceMode) = when (mode) { ExperienceMode.AUTO -> "自动"; ExperienceMode.ECO -> "节能"; ExperienceMode.PERFORMANCE -> "流畅优先" }
internal fun experienceCacheSize(bytes: Long): String = when {
    bytes >= 1024L * EXPERIENCE_MIB -> ((bytes.toDouble() / (1024L * EXPERIENCE_MIB) * 10).roundToInt() / 10.0).toString() + " GB"
    bytes >= EXPERIENCE_MIB -> ((bytes.toDouble() / EXPERIENCE_MIB * 10).roundToInt() / 10.0).toString() + " MB"
    else -> (bytes.coerceAtLeast(0L) / 1024L).toString() + " KB"
}
