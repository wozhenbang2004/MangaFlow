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
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One retained workspace owns its draft, numeric editor and exit-bound storage handoff. */
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
    val active = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !applying
    var baseline by remember(store) { mutableStateOf(store.overrides.value) }
    var baselineMode by remember(store) { mutableStateOf(store.state.value.mode) }
    var draft by remember(store) { mutableStateOf(store.overrides.value) }
    var draftMode by remember(store) { mutableStateOf(store.state.value.mode) }
    var expanded by rememberSaveable { mutableStateOf("reading") }
    var deviceTab by rememberSaveable { mutableStateOf("device") }
    var showLegend by rememberSaveable { mutableStateOf(false) }
    var pendingExit by remember { mutableStateOf<String?>(null) }
    var handoff by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val editor = remember { ExperienceEditor() }
    val dirty = draft != baseline || draftMode != baselineMode || editor.pending
    val conflict = savedCustom != baseline || savedBudget.mode != baselineMode
    val preview = remember(draft, draftMode, savedBudget) { store.previewBudget(draft, draftMode) }
    val detailScroll = rememberScrollState()
    val navigationScroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
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
    fun requestExit(destination: String = "close") {
        if (!active || !settleEditor()) return
        focus.clearFocus(force = true)
        if (draft != baseline || draftMode != baselineMode) pendingExit = destination else finishExit(destination)
    }
    fun selectCategory(category: String, forecast: Boolean = false) {
        if (!active || pendingExit != null || !settleEditor()) return
        focus.clearFocus(force = true)
        if (forecast) deviceTab = "forecast"
        expanded = category
    }
    fun apply() {
        if (!active || !dirty || !settleEditor()) return
        // Recheck the actual preference owner immediately before saving this draft.
        if (store.overrides.value != baseline || store.state.value.mode != baselineMode) {
            notice = "设置已在其他入口改变，请重新载入"
            return
        }
        val candidateMode = draftMode
        val candidate = draft
        val expectedMode = baselineMode
        val expected = baseline
        applying = true
        applyScope.launch {
            try {
                val saved = store.applyConfiguration(candidateMode, candidate,
                    expectedMode = expectedMode, expectedOverrides = expected)
                if (!saved) { notice = "设置已在其他入口改变，请重新载入"; return@launch }
                baseline = store.overrides.value
                baselineMode = store.state.value.mode
                draft = baseline
                draftMode = baselineMode
                notice = null
                focus.clearFocus(force = true)
                pendingExit?.let(::finishExit)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { notice = "设置未保存，请重试" }
            finally { applying = false }
        }
    }
    LaunchedEffect(visible) {
        if (visible) { reload(); expanded = "reading"; showLegend = false; detailScroll.scrollTo(0) }
    }
    LaunchedEffect(savedCustom, savedBudget.mode) {
        if (visible && !dirty) reload()
    }
    LaunchedEffect(expanded) { detailScroll.scrollTo(0) }
    MotionDialog(visible, onDismissRequest = {
        if (!applying) {
            if (pendingExit != null) pendingExit = null
            else requestExit()
        }
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
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            // The dialog's own constraints include split-screen, safe drawing and the IME.
            val wide = maxWidth - 32.dp >= 840.dp * window.fontScale
            val shownCategory = expanded
            LaunchedEffect(wide) { focus.clearFocus(force = true) }
            val panelWidth = if (wide) maxWidth else minOf(maxWidth, 560.dp * window.fontScale)
            val maximumHeight = minOf(window.height * .9f, if (wide) 680.dp else 560.dp).coerceAtLeast(1.dp)
            val pinnedImpact = !wide && maximumHeight >= 420.dp * window.fontScale
            val panel: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().height(maximumHeight)
                    .padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("体验与资源", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MotionIconButton(onClick = { showLegend = !showLegend }, enabled = active && pendingExit == null,
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
                    Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 0.dp)) {
                        if (wide) Column(Modifier.width(280.dp * window.fontScale).fillMaxHeight()
                            .verticalScroll(navigationScroll)) {
                            ExperienceModeChoices(draftMode, active && pendingExit == null) { mode ->
                                if (active && pendingExit == null && settleEditor()) {
                                    draftMode = mode
                                    notice = null
                                    focus.clearFocus(force = true)
                                }
                            }
                            ExperienceResourceOverview(liveUsage, savedBudget, preview, dirty,
                                active && pendingExit == null, editor.pending) { group ->
                                selectCategory(group, forecast = group == "resources")
                            }
                            if (preview.protections.isNotEmpty()) ExperienceProtectionSummary(preview.protections)
                            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                ExperienceCategoryNavigation(shownCategory, draft, active && pendingExit == null) { selectCategory(it) }
                            }
                            ExperienceReveal(showLegend) { ExperienceNavigationLegend() }
                        }
                        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(detailScroll)) {
                            if (!wide) {
                                ExperienceModeChoices(draftMode, active && pendingExit == null) { mode ->
                                    if (settleEditor()) {
                                        draftMode = mode
                                        notice = null
                                        focus.clearFocus(force = true)
                                    }
                                }
                                ExperienceResourceOverview(liveUsage, savedBudget, preview, dirty,
                                    active && pendingExit == null, editor.pending, showDraftImpact = !pinnedImpact) { group ->
                                    selectCategory(group, forecast = group == "resources")
                                }
                                if (preview.protections.isNotEmpty()) ExperienceProtectionSummary(preview.protections)
                                ExperienceCategoryNavigation(shownCategory, draft, active && pendingExit == null) { selectCategory(it) }
                                ExperienceReveal(showLegend) { ExperienceNavigationLegend() }
                            }
                            ExperienceSettings(active && pendingExit == null, shownCategory,
                                store, preview, savedBudget, editor, draft, { draft = it; notice = null },
                                currentDraft = { draft },
                                mode = draftMode, draft = dirty,
                                deviceTab = deviceTab, onDeviceTabChange = { deviceTab = it },
                                onOpenNetwork = onOpenNetwork?.let { { requestExit("network") } },
                                onOpenSourceChecks = onOpenSourceChecks?.let { { requestExit("source-checks") } })
                        }
                    }
                    if (dirty && pinnedImpact)
                        ExperienceDraftImpact(liveUsage, savedBudget, preview, editor.pending)
                    if (notice != null || conflict) Text(notice ?: "设置已在其他入口改变，请重新载入",
                        Modifier.fillMaxWidth().padding(vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = colors.error)
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(if (dirty) Icons.Outlined.Edit else Icons.Outlined.CheckCircle,
                            if (dirty) "有未应用的修改" else "配置已应用", Modifier.size(18.dp),
                            tint = if (dirty) colors.primary else colors.onSurfaceVariant)
                        Text(when { applying -> "应用中"; pendingExit != null -> "尚未应用"; editor.pending -> "编辑中"; dirty -> "草稿"; else -> "" },
                            Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = if (dirty) colors.primary else colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (pendingExit != null) MotionIconButton(onClick = { pendingExit = null }, enabled = active,
                            modifier = Modifier.size(44.dp), tooltip = "继续编辑") {
                            Icon(Icons.Outlined.Edit, "继续编辑", Modifier.size(18.dp))
                        }
                        if (dirty || conflict) MotionIconButton(onClick = {
                            val destination = pendingExit
                            reload()
                            if (destination != null) finishExit(destination)
                        }, enabled = active, modifier = Modifier.size(44.dp),
                            tooltip = if (pendingExit != null) "放弃草稿并继续" else if (conflict) "重新载入设置" else "撤销所有未应用修改") {
                            Icon(if (conflict) Icons.Outlined.Refresh else Icons.Outlined.Undo,
                                if (pendingExit != null) "放弃草稿并继续" else if (conflict) "重新载入设置" else "撤销草稿", Modifier.size(18.dp))
                        }
                        MotionButton(onClick = { apply() }, enabled = active && dirty && !conflict,
                            modifier = Modifier.heightIn(min = 44.dp).semantics {
                                contentDescription = if (pendingExit != null) "应用全部设置并继续" else "应用全部设置"
                            }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("应用")
                        }
                    }
                }
            }
            Box(Modifier.width(panelWidth)) {
                if (backdrop != null) LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                    role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
                else Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                    color = colors.surfaceContainerLow, tonalElevation = 0.dp) { panel() }
            }
        }
    }
}

@Composable
private fun ExperienceProtectionSummary(protections: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        MotionTextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 4.dp),
            modifier = Modifier.heightIn(min = 44.dp).semantics {
                contentDescription = "${protections.size} 项运行保护；点击查看"
            }) {
            Icon(Icons.Outlined.Shield, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(protections.size.toString(), color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium)
        }
        ExperienceReveal(expanded) {
            protections.forEach { ExperienceHint(it) }
        }
    }
}

/** Keep the adopted Ovio selection, with names available on tap through the workspace legend. */
@Composable
private fun ExperienceCategoryNavigation(selectedCategory: String, custom: ExperienceOverrides,
    enabled: Boolean, onSelect: (String) -> Unit) {
    ExperienceIconChoices(listOf(
        Triple("reading", "阅读", Icons.Outlined.AutoStories),
        Triple("parallel", "并行", Icons.Outlined.AccountTree),
        Triple("sources", "多源", Icons.Outlined.Layers),
        Triple("downloads", "后台", Icons.Outlined.Download),
        Triple("cache", "缓存", Icons.Outlined.Storage),
        Triple("resources", "设备", Icons.Outlined.DeveloperBoard),
    ), selectedCategory, enabled = enabled, modified = { id ->
        when (id) {
            "reading" -> experienceReadingCustom(custom)
            "parallel" -> experienceParallelCustom(custom)
            "sources" -> experienceSourceCustom(custom)
            "downloads" -> experienceDownloadCustom(custom)
            "cache" -> experienceCacheCustom(custom)
            else -> experienceDeviceCustom(custom)
        }
    }, onSelected = onSelect)
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
    onSelected: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val minimumSlot = if (selectedLabel) 40.dp + MaterialTheme.typography.labelMedium.fontSize.value.dp *
            fontScale * choices.maxOf { it.second.length } else 44.dp
        val trackWidth = maxOf(maxWidth, minimumSlot * choices.size)
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            MotionTabs(selected = choices.indexOfFirst { it.first == selectedValue }, labels = choices.map { it.second },
                onSelect = { if (enabled) onSelected(choices[it].first) }, role = Role.RadioButton,
                modifier = Modifier.width(trackWidth).motionInputGate(enabled),
                foreground = colors.primary, muted = colors.onSurfaceVariant,
                track = colors.onSurface.copy(alpha = .035f), indicator = colors.primary.copy(alpha = .12f),
                fullWidthIndicator = true, controlHeight = 44.dp, treatment = MotionTabTreatment.Soft,
                itemContent = { index, selected, color ->
                    val (id, title, icon) = choices[index]
                    Row(Modifier.semantics {
                        contentDescription = title
                        stateDescription = if (modified?.invoke(id) == true) "已自定义" else ""
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                            Icon(icon, null, Modifier.size(21.dp), tint = color)
                            if (modified?.invoke(id) == true) Box(Modifier.align(Alignment.TopEnd).size(4.dp)
                                .background(colors.primary, CircleShape))
                        }
                        if (selectedLabel && selected) Text(title, style = MaterialTheme.typography.labelMedium,
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
        ResourceValue(Icons.Outlined.DeveloperBoard, "设备", "硬件、观测与保护")
        ExperienceHint("实测是当前占用；≈ 是配置情景估算，不是硬上限。点数值查看口径；— 表示未知。蓝点表示自定义，修改后点“应用”保存。")
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
            if (modified) Icon(Icons.Outlined.Edit, "已自定义", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
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
            ExperienceIconChoices(listOf(
                Triple(PrefetchMode.SMART, "智能", Icons.Outlined.AutoMode),
                Triple(PrefetchMode.OFF, "关闭", Icons.Outlined.Block),
                Triple(PrefetchMode.MANUAL, "手动", Icons.Outlined.Tune),
            ), custom.prefetchMode, selectedLabel = true) {
                if (finishEditor()) change(currentDraft().copy(prefetchMode = it))
            }
            ExperienceReveal(custom.prefetchMode == PrefetchMode.MANUAL) {
                ExperienceNumber("待读窗口", custom.ahead, budget.goal.prefetchAhead, 0..40, "页",
                    editor, active && expanded == "reading" && custom.prefetchMode == PrefetchMode.MANUAL, effective = appliedBudget.prefetchAhead) { change(custom.copy(ahead = it)) }
                ExperienceNumber("回看窗口", custom.behind, budget.goal.prefetchBehind, 0..8, "页",
                    editor, active && expanded == "reading" && custom.prefetchMode == PrefetchMode.MANUAL, effective = appliedBudget.prefetchBehind) { change(custom.copy(behind = it)) }
            }
            ExperienceReveal(custom.prefetchMode != PrefetchMode.OFF) {
                SettingsToggle("提前准备下一章", custom.crossChapter, Icons.Outlined.SkipNext) { change(custom.copy(crossChapter = it)) }
                if (custom.crossChapter && !budget.crossChapter) ExperienceHint("跨章预读当前已暂停")
            }
            SettingsToggle("浏览时提前准备", custom.browseWarmup, Icons.Outlined.AutoStories) { change(custom.copy(browseWarmup = it)) }
            if (custom.browseWarmup && !budget.browseWarmup) ExperienceHint("浏览预热当前已暂停")
            ExperienceTools("reading", "阅读与预取", active && expanded == "reading", detail, ::toggleDetail,
                experienceReadingCustom(custom), onRestore = {
                    closeEditor()
                    change(custom.copy(prefetchMode = PrefetchMode.SMART, ahead = null, behind = null,
                        crossChapter = true, browseWarmup = true, decodedPrefetchPages = null, readerStatusPlacement = ReaderStatusPlacement.BOTTOM,
                        runtime = resetExperienceRuntime(custom.runtime, ExperienceRuntimeGroup.READING)))
                }, extra = {
                    ExperienceNumber("临近页预解码", custom.decodedPrefetchPages, budget.goal.decodedPrefetchPages, 0..8, "页",
                        editor, active && expanded == "reading" && detail == "reading-advanced", effective = appliedBudget.decodedPrefetchPages) {
                        change(custom.copy(decodedPrefetchPages = it))
                    }
                    ExperienceRuntimeSettings(ExperienceRuntimeGroup.READING, custom, budget, appliedBudget, usage, store,
                        editor, active && expanded == "reading" && detail == "reading-advanced", ::change)
                    Text("阅读状态栏", style = MaterialTheme.typography.bodyMedium)
                    SettingsChoices(listOf(ReaderStatusPlacement.TOP to "顶部", ReaderStatusPlacement.BOTTOM to "底部",
                        ReaderStatusPlacement.HIDDEN to "隐藏"), custom.readerStatusPlacement) { change(custom.copy(readerStatusPlacement = it)) }
                }) {
                ExperienceBudgetRow("窗口目标", budget.goal.prefetchAhead.toString() + " 待读 · " + budget.goal.prefetchBehind + " 回看")
                ExperienceHint("待读和回看跟随实际翻页方向，不固定为屏幕左右。窗口是候选页数，解析、传输、解码各有并发上限；原图存磁盘，临近预览进内存。")
                ExperienceHint("环形箭头表示跟随目标，笔形表示手动值，直接输入不设业务上限；“当前”只在暂停等实际状态不同时显示。配置数量不等于正在执行的数量。")
            }
        }

        if (expanded == "parallel") ExperienceCategorySection("并行", Icons.Outlined.AccountTree,
            modified = experienceParallelCustom(custom)) {
            ExperienceNumber("当前页请求", custom.readerRequests, budget.goal.readerRequests, 1..4, "个",
                editor, active && expanded == "parallel", effective = appliedBudget.readerRequests) { change(custom.copy(readerRequests = it)) }
            ExperienceNumber("图址解析", custom.resolveRequests, budget.goal.resolveRequests, 1..8, "个",
                editor, active && expanded == "parallel", effective = appliedBudget.resolveRequests) { change(custom.copy(resolveRequests = it)) }
            ExperienceNumber("原图预取", custom.prefetchRequests, budget.goal.prefetchRequests, 1..12, "个",
                editor, active && expanded == "parallel", effective = appliedBudget.prefetchRequests) { change(custom.copy(prefetchRequests = it)) }
            ExperienceNumber("图片解码", custom.decodeRequests, budget.goal.decodeRequests, 1..4, "个",
                editor, active && expanded == "parallel", effective = appliedBudget.decodeRequests) { change(custom.copy(decodeRequests = it)) }
            ExperienceNumber("下载页面", custom.downloadPages, budget.goal.backgroundPages, 1..maximumBackgroundPageParallelism, "个",
                editor, active && expanded == "parallel", effective = appliedBudget.backgroundPages) { change(custom.copy(downloadPages = it)) }
            ExperienceNumber("每源请求", custom.sourcePages, budget.goal.sourceParallelism, 1..8, "个",
                editor, active && expanded == "parallel", effective = appliedBudget.sourceParallelism) { change(custom.copy(sourcePages = it)) }
            ExperienceNumber("下载章节", custom.chapters, budget.goal.chapterParallelism, 1..4, "章",
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
                ExperienceBudgetRow("当前 · 请求 / 排队", usage.activeReaderImages.toString() + " / " + usage.queuedTasks)
                ExperienceBudgetRow("当前 · 解析 / 解码", usage.activeResolves.toString() + " / " + usage.activeDecodes)
                ExperienceBudgetRow("当前 · 预取 / 下载", usage.activePrefetch.toString() + " / " + usage.activeBackgroundPages)
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
            ExperienceNumber("同时备用来源", custom.standbySources,
                if (budget.sourceRecovery == SourceRecoveryMode.AGGRESSIVE) 2 else 1, 1..4, "源",
                editor, active && expanded == "sources", effective = appliedBudget.standbySources, automaticLabel = "预热模式") {
                change(custom.copy(standbySources = it))
            }
            ExperienceNumber("每源附近预热", custom.standbyPages, 3, 1..12, "页",
                editor, active && expanded == "sources", effective = appliedBudget.standbyPages) { change(custom.copy(standbyPages = it ?: 3)) }
            ExperienceNumber("备用原图窗口", custom.standbyMiB,
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
            SettingsToggle("锁定当前来源", custom.recovery.sourceLocked, Icons.Outlined.Lock) {
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
            SettingsToggle("磁盘自动回收", custom.runtime.automaticCleanup, Icons.Outlined.CleaningServices) {
                change(custom.copy(runtime = custom.runtime.copy(automaticCleanup = it)))
            }
            ExperienceIconChoices(listOf(Triple(CacheEvictionOrder.UNUSED_FIRST, "未使用", Icons.Outlined.VisibilityOff),
                Triple(CacheEvictionOrder.LEAST_RECENT, "最久未用", Icons.Outlined.History),
                Triple(CacheEvictionOrder.LARGEST_FIRST, "较大优先", Icons.Outlined.PhotoSizeSelectLarge)),
                custom.runtime.evictionOrder, selectedLabel = true) {
                if (finishEditor()) {
                    val latest = currentDraft()
                    change(latest.copy(runtime = latest.runtime.copy(evictionOrder = it)))
                }
            }
            ExperienceIconChoices(listOf(Triple("images", "容量", Icons.Outlined.Storage),
                Triple("data", "时效", Icons.Outlined.Schedule), Triple("areas", "组成", Icons.Outlined.PieChart)),
                cacheTab, selectedLabel = true) {
                if (finishEditor()) cacheTab = it
            }
            if (cacheTab == "images") {
            ExperienceMemoryCacheSummary(usage, custom.contentCache.memoryIdleMinutes,
                active && expanded == "cache", clearingMemory) {
                    if (active && !clearingMemory) uiScope.launch {
                        clearingMemory = true
                        cacheNotice = null
                        try {
                            val released = store.clearDecodedMemory().await()
                            cacheNotice = "已释放 " + experienceCacheSize(released) + " 缓存引用"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { cacheNotice = "清理未完成，请重试" }
                        finally { clearingMemory = false }
                    }
            }
            cacheNotice?.let { ExperienceHint(it) }
            ExperienceNumber("内存缓存上限", custom.memoryMiB, automaticMemoryBytes?.let { (it / EXPERIENCE_MIB).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }, 1..512, "MB",
                editor, active && expanded == "cache", effective = usage.memoryLimit?.let { (it / EXPERIENCE_MIB).toInt() }, automaticLabel = "模式预算") {
                change(custom.copy(memoryMiB = it))
            }
            ExperienceNumber("闲置回收", custom.contentCache.memoryIdleMinutes, ContentCachePreferences().memoryIdleMinutes, 0..120, "分钟",
                editor, active && expanded == "cache", effective = appliedCustom.contentCache.memoryIdleMinutes,
                automaticLabel = "默认值") {
                change(custom.copy(contentCache = custom.contentCache.copy(memoryIdleMinutes = it ?: ContentCachePreferences().memoryIdleMinutes)))
            }
            ExperienceBudgetRow("共享磁盘", cacheUsage(usage.diskBytes, usage.diskLimit))
            ExperienceNumber("磁盘缓存预算", custom.diskMiB, (budget.goal.diskCacheBytes / EXPERIENCE_MIB).toInt(), 1..8192, "MB",
                editor, active && expanded == "cache", effective = (appliedBudget.diskCacheBytes / EXPERIENCE_MIB).toInt()) {
                change(custom.copy(diskMiB = it))
            }
            ExperienceNumber("原图预取窗口", custom.prefetchMiB, (budget.goal.prefetchBytes / EXPERIENCE_MIB).toInt(), 1..512, "MB",
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
            if (usage.diskResizePending) ExperienceHint("等待在用原图释放后应用容量")
            usage.cacheError?.let { ExperienceHint(it) }
            ExperienceNumber("磁盘图片保留", custom.contentCache.imageRetentionHours, 0, 0..720, "小时",
                editor, active && expanded == "cache", effective = appliedCustom.contentCache.imageRetentionHours,
                automaticLabel = "默认值") { change(custom.copy(contentCache = custom.contentCache.copy(imageRetentionHours = it ?: 0))) }
            } else if (cacheTab == "areas") {
                ExperienceCacheBreakdown(usage)
            } else {
                val cache = custom.contentCache
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
                    Triple(ContentCacheKind.COMMENTS, "评论", Icons.Outlined.ChatBubbleOutline)), cacheKind) {
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
                ExperienceNumber(when (cacheKind) {
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
                ExperienceBudgetRow("预览更新规则", if (effectiveMinutes == 0L) "每次请求更新" else "访问时超过 $effectiveMinutes 分钟再更新")
                if (cacheKind == ContentCacheKind.POPULARITY) ExperienceHint("热度随列表与详情返回，此上限同时约束这些资料的复用时间；不为每张卡单独请求热度。")
                else if (effectiveMinutes < minutes && cache.enabled && !cache.refreshOnOpen)
                    ExperienceHint("同时受热度资料时效上限约束。")
                ExperienceNumber("失败结果重试间隔", cache.failureMinutes, 1, 0..60, "分钟", editor, active && expanded == "cache",
                    effective = appliedCustom.contentCache.failureMinutes, automaticLabel = "默认值") {
                    change(custom.copy(contentCache = cache.copy(failureMinutes = it ?: 1)))
                }
                ExperienceNumber("快照最长保留", cache.retentionHours, 168, 1..720, "小时", editor, active && expanded == "cache",
                    effective = appliedCustom.contentCache.retentionHours, automaticLabel = "默认值") {
                    change(custom.copy(contentCache = cache.copy(retentionHours = it ?: 168)))
                }
                ExperienceNumber("列表快照上限", cache.snapshotMemoryEntries, 24, 1..64, "份", editor, active && expanded == "cache",
                    effective = appliedCustom.contentCache.snapshotMemoryEntries, automaticLabel = "默认值") {
                    change(custom.copy(contentCache = cache.copy(snapshotMemoryEntries = it ?: 24)))
                }
                ExperienceNumber("列表磁盘上限", cache.snapshotDiskMiB, 8, 1..128, "MB", editor, active && expanded == "cache",
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
                ExperienceHint("闲置回收 0＝只按容量淘汰；时钟旁的 ∞ 表示时间过期已关闭。到期只移除可复用缓存引用，不强行释放仍在显示的图片。")
                ExperienceHint("磁盘保留从最近访问起算，始终保护正在阅读、传输和离线文件。关闭磁盘自动回收后保留至手动清理，内存策略独立生效。")
                ExperienceHint("页面资料间隔 0＝不复用已完成结果，进行中的请求仍合并。允许先显示旧快照再更新；更新失败保留旧内容。关闭资料缓存不会删除历史、收藏和离线章节。")
                ExperienceHint("预读离开窗口会取消未完成任务，完成的图片按内存闲置、磁盘保留与总空间规则回收。列表快照按设置的份数与磁盘容量淘汰；阅读记录、收藏和离线章节独立保存。")
            }
        }

        if (expanded == "resources") ExperienceCategorySection("设备", Icons.Outlined.DeveloperBoard,
            modified = experienceDeviceCustom(custom)) {
            ExperienceIconChoices(listOf(Triple("device", "硬件", Icons.Outlined.DeveloperBoard),
                Triple("forecast", "情景", Icons.Outlined.Calculate), Triple("live", "观测", Icons.Outlined.Analytics),
                Triple("protect", "保护", Icons.Outlined.Shield)), deviceTab, selectedLabel = true) {
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
    ExperienceRuntimeField.entries.filter { it.group == group }.forEach { field ->
        key(field) {
            val supported = platformKind != PlatformKind.ANDROID ||
                field != ExperienceRuntimeField.TILE_DECODES && field != ExperienceRuntimeField.FALLBACK_PIXELS
            val automaticBudget = remember(custom, budget, field) {
                store.previewBudget(custom.copy(runtime = field.withValue(custom.runtime, null)), budget.mode)
            }
            val default = field.effectiveValue(automaticBudget, usage)
            val shortcut = field.semanticMaximum ?: when (field.unit) {
                "px" -> 2048; "MiB" -> 1024; "Mpx" -> 64; "秒" -> 120; "页" -> 24; else -> 16
            }
            ExperienceNumber(field.label, field.value(custom.runtime), default,
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
    ExperienceNumber("连续失败", p.consecutiveFailures, 3, 1..12, "页", editor, active,
        effective = actual.consecutiveFailures) { update(p.copy(consecutiveFailures = it ?: 3)) }
    ExperienceNumber("失败观察窗口", p.failureWindow, 6, 1..24, "页", editor, active,
        effective = actual.failureWindow) { update(p.copy(failureWindow = it ?: 6)) }
    ExperienceNumber("窗口内失败", p.failuresInWindow, 3, 1..12, "页", editor, active,
        effective = actual.failuresInWindow) { update(p.copy(failuresInWindow = it ?: 3)) }
    if (p.failuresInWindow > p.failureWindow) ExperienceHint("窗口阈值大于窗口页数，此条件不会触发；连续失败条件仍独立生效。")
    ExperienceNumber("切源冷却", p.switchCooldownSeconds, 60, 0..300, "秒", editor, active,
        effective = actual.switchCooldownSeconds) { update(p.copy(switchCooldownSeconds = it ?: 60)) }
    ExperienceNumber("备用重试", p.standbyRetryCount, 2, 0..8, "次", editor, active,
        effective = actual.standbyRetryCount) { update(p.copy(standbyRetryCount = it ?: 2)) }
    ExperienceNumber("备用重试间隔", p.standbyRetryDelaySeconds, 30, 0..120, "秒", editor, active,
        effective = actual.standbyRetryDelaySeconds) { update(p.copy(standbyRetryDelaySeconds = it ?: 30)) }
    ExperienceNumber("备用目录等待", p.manifestTimeoutSeconds, 25, 1..120, "秒", editor, active,
        effective = actual.manifestTimeoutSeconds) { update(p.copy(manifestTimeoutSeconds = it ?: 25)) }
    ExperienceNumber("图片无新字节等待", p.imageTimeoutSeconds, 45, 1..180, "秒", editor, active,
        effective = (effectiveImageTransportTimeoutMillis(actual.imageTimeoutSeconds.toLong() * 1_000L) / 1_000L).toInt()) {
        update(p.copy(imageTimeoutSeconds = it ?: 45))
    }
    ExperienceTransportLimit(p.imageTimeoutSeconds.toLong() * 1_000L)
    ExperienceNumber("慢加载提示", p.slowLoadSeconds, 8, 1..60, "秒", editor, active,
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

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ExperienceNumber(title: String, value: Int?, automatic: Int?, range: IntRange, unit: String,
    editor: ExperienceEditor, active: Boolean, effective: Int? = null, automaticLabel: String = "模式目标",
    semanticMaximum: Int? = null, onChange: (Int?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selected = value ?: automatic
    val current = (selected ?: range.first).coerceAtLeast(range.first)
    // The slider is a shortcut; the numeric editor accepts every representable value.
    val sliderMaximum = maxOf(range.last, current)
    val editing = active && editor.field == title
    val focus = LocalFocusManager.current
    var draft by editor.input
    var sliding by editor.position
    var invalid by editor.invalidInput
    fun acceptsInput() = active && editor.field == title
    fun commit(): Boolean {
        if (!acceptsInput()) return false
        val parsed = draft.toIntOrNull()
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
                            invalid = false
                            editor.clear()
                            editor.field = title
                        }
                    }
                }, enabled = active, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.widthIn(max = maxWidth).heightIn(min = 44.dp).semantics(mergeDescendants = true) {
                        contentDescription = title + "，" + (selected?.toString()?.plus(" " + unit) ?: "自动值尚未知") +
                            (if (value == null) "，跟随" + automaticLabel else "，手动值") +
                            (effective?.let { "，当前有效值 " + it + " " + unit } ?: "") +
                            (if (editing) "，点击取消未应用的编辑" else "，点击编辑")
                    }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text((selected?.toString() ?: "—") + " " + unit, Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodyMedium, color = if (editing || value != null) colors.primary else colors.onSurface)
                            Icon(if (editing) Icons.Outlined.Close else if (value == null) Icons.Outlined.Sync else Icons.Outlined.Edit,
                                null, Modifier.size(14.dp), tint = if (editing || value != null) colors.primary else colors.onSurfaceVariant)
                        }
                        if (selected != null && effective != null && effective != selected)
                            Text("当前 " + effective, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
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
                        contentDescription = "完成" + title + "编辑，保留为草稿"
                    }) {
                    Text("完成")
                }
                MotionIconButton(onClick = {
                    if (acceptsInput()) { editor.clear(); focus.clearFocus(force = true) }
                }, enabled = editing, modifier = Modifier.size(44.dp), tooltip = "取消未应用的" + title + "编辑") {
                    Icon(Icons.Outlined.Close, "取消编辑", Modifier.size(18.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = automaticLabel + " " + (automatic?.toString()?.plus(" " + unit) ?: "未知")
                            }) {
                            Icon(Icons.Outlined.Sync, null, Modifier.size(15.dp), tint = colors.primary)
                            Text("目标 " + (automatic?.toString()?.plus(" " + unit) ?: "—"),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Outlined.CheckCircle, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                            Text("当前 " + (effective?.toString()?.plus(" " + unit) ?: "—"),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                    if (semanticMaximum != null) Text(range.first.toString() + "–" + semanticMaximum + " " + unit,
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
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
            if (invalid) Text(if (semanticMaximum != null) "请输入 " + range.first + "–" + semanticMaximum + " 的整数"
                else "请输入不小于 " + range.first + " 的有效整数", style = MaterialTheme.typography.labelSmall, color = colors.error)
            MotionSlider(sliding, onValueChange = {
                if (acceptsInput()) { sliding = it; draft = it.roundToInt().toString(); invalid = false; editor.pending = true }
            }, valueRange = range.first.toFloat()..sliderMaximum.toFloat(), onValueChangeFinished = {
                if (acceptsInput()) {
                    val accepted = sliding.roundToInt().coerceAtLeast(range.first)
                    draft = accepted.toString()
                    invalid = false
                    editor.pending = draft != selected?.toString().orEmpty()
                }
            }, formatValue = { it.roundToInt().toString() + " " + unit },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title + "滑块" })
        }
    }
}

/** Existing icon presses and retained disclosure share one local, fixed-height tool row. */
@Composable
private fun ExperienceTools(key: String, title: String, active: Boolean, detail: String?, onDetail: (String) -> Unit,
    modified: Boolean, onRestore: () -> Unit, extraLabel: String = "更多参数",
    extraIcon: ImageVector = Icons.Outlined.Tune, extra: (@Composable ColumnScope.() -> Unit)? = null,
    help: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val helpOpen = detail == key + "-help"
    val extraOpen = detail == key + "-advanced"
    Column(Modifier.fillMaxWidth().motionInputGate(active)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionIconButton(onClick = { onDetail(key + "-help") }, enabled = active,
                modifier = Modifier.size(44.dp).semantics { stateDescription = if (helpOpen) "已展开" else "已收起" },
                tooltip = title + "说明") {
                Icon(Icons.Outlined.Info, title + "说明", Modifier.size(18.dp),
                    tint = if (helpOpen) colors.primary else colors.onSurfaceVariant)
            }
            if (extra != null) MotionIconButton(onClick = { onDetail(key + "-advanced") }, enabled = active,
                modifier = Modifier.size(44.dp).semantics { stateDescription = if (extraOpen) "已展开" else "已收起" },
                tooltip = title + "：" + extraLabel) {
                Icon(extraIcon, title + "：" + extraLabel, Modifier.size(18.dp),
                    tint = if (extraOpen) colors.primary else colors.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            MotionSwap(modified, Modifier.size(44.dp)) { shown ->
                if (shown) MotionIconButton(onClick = onRestore, enabled = active && modified,
                    modifier = Modifier.size(44.dp), tooltip = "恢复" + title + "默认设置") {
                    Icon(Icons.Outlined.RestartAlt, "恢复" + title + "默认设置", Modifier.size(18.dp),
                        tint = colors.onSurfaceVariant)
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
