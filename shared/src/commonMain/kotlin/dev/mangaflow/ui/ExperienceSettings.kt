package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** A compact settings entry opens one retained workspace, rather than expanding the page. */
@Composable
internal fun ExperienceSettingsDialog(visible: Boolean, backdrop: HazeState?, onDismiss: () -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val colors = MaterialTheme.colorScheme
    MotionDialog(visible, onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 520.dp) {
        val maximumHeight = minOf(LocalMangaWindow.current.height * .9f, 640.dp)
        val panel: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight)
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("体验策略", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭体验策略") {
                        Icon(Icons.Outlined.Close, "关闭体验策略", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scroll)
                    .padding(top = 6.dp, bottom = 8.dp)) {
                    ExperienceSettings(active, expanded) { expanded = it }
                }
            }
        }
        Box(Modifier.padding(horizontal = 12.dp)) {
            if (backdrop != null) LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
            else Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                color = colors.surfaceContainerLow, tonalElevation = 0.dp) { panel() }
        }
    }
}

/** Existing Ovio selection and retained rows keep one reading-first workspace. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ExperienceSettings(active: Boolean, expanded: String?, onExpandedChange: (String?) -> Unit) {
    val context = LocalMangaContext.current
    val store = remember(context) { ExperiencePolicyStore.get(context) }
    val budget by store.state.collectAsStateWithLifecycle()
    val custom by store.overrides.collectAsStateWithLifecycle()
    val usage by store.usage.collectAsStateWithLifecycle()
    val editor = remember { ExperienceEditor() }
    val focus = LocalFocusManager.current
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    fun closeEditor() {
        editor.field = null
        focus.clearFocus(force = true)
    }
    fun change(value: ExperienceOverrides) {
        if (!active) return
        try { store.setOverrides(value); notice = null }
        catch (_: Exception) { notice = "设置未保存，请重试" }
    }
    fun toggle(key: String) {
        if (!active) return
        closeEditor()
        detail = null
        onExpandedChange(if (expanded == key) null else key)
    }
    fun toggleDetail(key: String) {
        if (!active) return
        closeEditor()
        detail = if (detail == key) null else key
    }
    LaunchedEffect(active, expanded) {
        closeEditor()
        if (!active) return@LaunchedEffect
        store.refreshResources()
        if (expanded == "cache" || expanded == "resources") {
            while (true) { store.refreshCacheUsage(); delay(2_000L) }
        }
    }
    Column(Modifier.fillMaxWidth().motionInputGate(active)) {
        SettingsChoices(ExperienceMode.entries.map { it to experienceModeLabel(it) }, budget.mode) { mode ->
            if (active) {
                closeEditor()
                try { store.setMode(mode); notice = null }
                catch (_: Exception) { notice = "模式未保存，请重试" }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (budget.protections.isEmpty()) Icons.Outlined.AutoStories else Icons.Outlined.Info,
                null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
            Text(budget.reason, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        notice?.let {
            Text(it, Modifier.fillMaxWidth().padding(bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.error)
        }

        SettingsDisclosure("阅读与预取",
            if (custom.prefetchMode == PrefetchMode.OFF) "已关闭"
            else "前 " + budget.prefetchAhead + " · 后 " + budget.prefetchBehind,
            Icons.Outlined.AutoStories, expanded == "reading", { toggle("reading") },
            compact = true, modified = experienceReadingCustom(custom)) {
            SettingsChoices(PrefetchMode.entries.map { it to when (it) {
                PrefetchMode.SMART -> "智能"; PrefetchMode.OFF -> "关闭"; PrefetchMode.MANUAL -> "手动"
            } }, custom.prefetchMode) {
                closeEditor()
                change(custom.copy(prefetchMode = it))
            }
            ExperienceReveal(custom.prefetchMode == PrefetchMode.MANUAL) {
                ExperienceNumber("前方页数", custom.ahead, budget.goal.prefetchAhead, 0..12, "页",
                    editor, active && expanded == "reading" && custom.prefetchMode == PrefetchMode.MANUAL, effective = budget.prefetchAhead) { change(custom.copy(ahead = it)) }
                ExperienceNumber("后方页数", custom.behind, budget.goal.prefetchBehind, 0..4, "页",
                    editor, active && expanded == "reading" && custom.prefetchMode == PrefetchMode.MANUAL, effective = budget.prefetchBehind) { change(custom.copy(behind = it)) }
            }
            ExperienceReveal(custom.prefetchMode == PrefetchMode.SMART) {
                ExperienceBudgetRow("模式目标", "前 " + budget.goal.prefetchAhead + " · 后 " + budget.goal.prefetchBehind + " 页")
            }
            ExperienceReveal(custom.prefetchMode != PrefetchMode.OFF) {
                SettingsToggle("提前准备下一章", custom.crossChapter, Icons.Outlined.SkipNext) { change(custom.copy(crossChapter = it)) }
                if (custom.crossChapter && !budget.crossChapter) ExperienceHint("跨章预读当前已暂停")
            }
            SettingsToggle("浏览时提前准备", custom.browseWarmup, Icons.Outlined.AutoStories) { change(custom.copy(browseWarmup = it)) }
            if (custom.browseWarmup && !budget.browseWarmup) ExperienceHint("浏览预热当前已暂停")
            SettingsDisclosure("更多参数", "", Icons.Outlined.Tune, detail == "reading-advanced",
                { toggleDetail("reading-advanced") }, compact = true) {
                ExperienceReveal(custom.prefetchMode != PrefetchMode.OFF) {
                    ExperienceNumber("预取请求", custom.prefetchRequests, budget.goal.prefetchRequests, 1..3, "个",
                        editor, active && expanded == "reading" && detail == "reading-advanced" && custom.prefetchMode != PrefetchMode.OFF,
                        effective = budget.prefetchRequests) { change(custom.copy(prefetchRequests = it)) }
                    ExperienceNumber("原图预取窗口", custom.prefetchMiB, (budget.goal.prefetchBytes / EXPERIENCE_MIB).toInt(), 4..96, "MB",
                        editor, active && expanded == "reading" && detail == "reading-advanced" && custom.prefetchMode != PrefetchMode.OFF,
                        effective = (budget.prefetchBytes / EXPERIENCE_MIB).toInt()) { change(custom.copy(prefetchMiB = it)) }
                    ExperienceBudgetRow("邻页 / 跨章", experienceCacheSize(budget.adjacentPrefetchBytes) + " / " +
                        experienceCacheSize(budget.nextChapterPrefetchBytes))
                }
                ExperienceNumber("当前页请求", custom.readerRequests, budget.goal.readerRequests, 1..4, "个",
                    editor, active && expanded == "reading" && detail == "reading-advanced",
                    effective = budget.readerRequests) { change(custom.copy(readerRequests = it)) }
            }
            ExperienceHelp("reading-help", detail, { toggleDetail("reading-help") }) {
                ExperienceHint("按方向、翻页速度和完整双页预取。原图存磁盘，临近页少量预解码进内存；邻页、跨章和备用来源共用一个字节窗口。")
                ExperienceHint("模式目标是期望值；手动值是用户上限。实际上限还会受设备和网络条件约束。")
            }
            ExperienceRestore(experienceReadingCustom(custom), active) {
                closeEditor()
                change(custom.copy(prefetchMode = PrefetchMode.SMART, ahead = null, behind = null,
                    prefetchRequests = null, prefetchMiB = null, crossChapter = true, readerRequests = null, browseWarmup = true))
            }
        }

        SettingsDisclosure("图像质量与渲染", "预解码 " + budget.decodedPrefetchPages + " 页",
            Icons.Outlined.Image, expanded == "rendering", { toggle("rendering") },
            compact = true, modified = custom.decodedPrefetchPages != null) {
            ExperienceHint("保留原图 · 细节按需加载")
            ExperienceNumber("临近页预解码", custom.decodedPrefetchPages, budget.goal.decodedPrefetchPages, 0..2, "页",
                editor, active && expanded == "rendering", effective = budget.decodedPrefetchPages) {
                change(custom.copy(decodedPrefetchPages = it))
            }
            ExperienceHelp("rendering-help", detail, { toggleDetail("rendering-help") }) {
                ExperienceHint("预解码只准备显示尺寸，不压缩或替换来源原图。超大图与放大细节仍受解码内存保护；关闭预解码也可正常阅读。")
                ExperienceHint("双页、阅读方向和阅读模式在阅读控制台调整。")
            }
            ExperienceRestore(custom.decodedPrefetchPages != null, active) {
                closeEditor(); change(custom.copy(decodedPrefetchPages = null))
            }
        }

        SettingsDisclosure("多源与补页", when {
            budget.sourceRecovery == SourceRecoveryMode.OFF -> "按需切源"
            budget.standbySources == 0 -> "预热暂停"
            else -> "备用 " + budget.standbySources + " 源"
        }, Icons.Outlined.Layers, expanded == "sources", { toggle("sources") },
            compact = true, modified = custom.sourceRecovery != null) {
            SettingsChoices(listOf<Pair<SourceRecoveryMode?, String>>(
                null to "跟随", SourceRecoveryMode.OFF to "关闭",
                SourceRecoveryMode.BALANCED to "稳健", SourceRecoveryMode.AGGRESSIVE to "积极"), custom.sourceRecovery) {
                change(custom.copy(sourceRecovery = it))
            }
            ExperienceBudgetRow("当前策略", sourceRecoveryLabel(budget.sourceRecovery))
            if (budget.standbySources != budget.goal.standbySources)
                ExperienceBudgetRow("模式目标", "备用至多 " + budget.goal.standbySources + " 源")
            ExperienceBudgetRow("备用预热", budget.standbySources.toString() + " 源 · " + experienceCacheSize(budget.standbyPrefetchBytes))
            if (usage.throttledSources > 0) ExperienceHint(usage.throttledSources.toString() + " 个来源正在等待限流恢复")
            ExperienceHelp("sources-help", detail, { toggleDetail("sources-help") }) {
                ExperienceHint("提前准备已关联的同章备用来源，预取可发现请求和图片错误。章节对齐不代表每一页相同，不会仅按页码替换图片；切换版本保留独立位置。")
            }
            ExperienceRestore(custom.sourceRecovery != null, active) {
                closeEditor(); change(custom.copy(sourceRecovery = null))
            }
        }

        SettingsDisclosure("下载与后台",
            if (budget.backgroundPages == 0) "暂缓新请求"
            else budget.backgroundPages.toString() + " 页 · " + budget.chapterParallelism + " 章",
            Icons.Outlined.Download, expanded == "downloads", { toggle("downloads") },
            compact = true, modified = experienceDownloadCustom(custom)) {
            ExperienceNumber("总页面请求", custom.downloadPages, budget.goal.backgroundPages, 1..maximumBackgroundPageParallelism, "个",
                editor, active && expanded == "downloads", effective = budget.backgroundPages) { change(custom.copy(downloadPages = it)) }
            ExperienceNumber("每个来源", custom.sourcePages, budget.goal.sourceParallelism, 1..4, "个",
                editor, active && expanded == "downloads", effective = budget.sourceParallelism) { change(custom.copy(sourcePages = it)) }
            ExperienceNumber("同时下载章节", custom.chapters, budget.goal.chapterParallelism, 1..4, "章",
                editor, active && expanded == "downloads", effective = budget.chapterParallelism) { change(custom.copy(chapters = it)) }
            SettingsToggle("阅读时下载让路", custom.yieldToReading, Icons.Outlined.AutoStories) { change(custom.copy(yieldToReading = it)) }
            SettingsToggle("允许后台下载", custom.allowBackgroundDownloads, Icons.Outlined.Download) { change(custom.copy(allowBackgroundDownloads = it)) }
            if (usage.activeBackgroundPages > 0 || usage.activeChapters > 0)
                ExperienceBudgetRow("正在下载", usage.activeBackgroundPages.toString() + " 页 · " + usage.activeChapters + " 章")
            ExperienceHelp("downloads-help", detail, { toggleDetail("downloads-help") }) {
                ExperienceHint("每源从一个请求开始，按真实反馈调整；限流时等待恢复。上限只限制新请求，正在传输的页面继续完成。")
                ExperienceHint(when (platformKind) {
                    PlatformKind.DESKTOP -> "退出应用后停止下载，重新打开后核对并续传。"
                    PlatformKind.IOS -> "在系统允许的延长执行时段内继续；系统暂停或强制退出后，重新打开续传。"
                    else -> "后台下载受系统任务配额和通知权限限制；中断后保留可用断点。"
                })
            }
            ExperienceRestore(experienceDownloadCustom(custom), active) {
                closeEditor()
                change(custom.copy(downloadPages = null, sourcePages = null, chapters = null,
                    yieldToReading = true, allowBackgroundDownloads = true))
            }
        }

        SettingsDisclosure("缓存与离线", cacheUsage(usage.diskBytes, usage.diskLimit),
            Icons.Outlined.Storage, expanded == "cache", { toggle("cache") },
            compact = true, modified = custom.memoryMiB != null || custom.diskMiB != null) {
            ExperienceBudgetRow("解码内存", cacheUsage(usage.memoryBytes, usage.memoryLimit))
            ExperienceNumber("内存缓存上限", custom.memoryMiB, usage.memoryLimit?.let { (it / EXPERIENCE_MIB).toInt() }, 16..512, "MB",
                editor, active && expanded == "cache", effective = usage.memoryLimit?.let { (it / EXPERIENCE_MIB).toInt() }, automaticLabel = "当前可用上限") {
                change(custom.copy(memoryMiB = it))
            }
            ExperienceBudgetRow("图片磁盘", cacheUsage(usage.diskBytes, usage.diskLimit))
            ExperienceNumber("磁盘缓存上限", custom.diskMiB, (budget.goal.diskCacheBytes / EXPERIENCE_MIB).toInt(), 32..8192, "MB",
                editor, active && expanded == "cache", effective = (budget.diskCacheBytes / EXPERIENCE_MIB).toInt()) {
                change(custom.copy(diskMiB = it))
            }
            if (active && expanded == "cache") {
                val downloads = remember(context) { DownloadStore.get(context) }
                val entries by downloads.entries.collectAsStateWithLifecycle()
                ExperienceBudgetRow("离线已保存", experienceCacheSize(entries.sumOf { it.downloadedBytes }))
            }
            if (usage.diskResizePending) ExperienceHint("等待在用原图释放后应用容量")
            usage.cacheError?.let { ExperienceHint(it) }
            ExperienceHelp("cache-help", detail, { toggleDetail("cache-help") }) {
                ExperienceHint("内存缓存只包含可复用的解码图像，上限受可用资源保护。磁盘缓存可自动回收，离线章节独立保存；可回收内容在缓存管理中清理。")
            }
            ExperienceRestore(custom.memoryMiB != null || custom.diskMiB != null, active) {
                closeEditor(); change(custom.copy(memoryMiB = null, diskMiB = null))
            }
        }

        SettingsDisclosure("显示与交互", when (custom.refresh) {
            null -> "跟随目标"; RefreshPreference.SYSTEM -> "跟随系统"; RefreshPreference.INTERACTION -> "交互高刷"
            RefreshPreference.MANUAL -> custom.refreshHz?.let { it.toString() + " Hz 请求" } ?: "指定刷新率"
        }, Icons.Outlined.Speed, expanded == "display", { toggle("display") },
            compact = true, modified = custom.refresh != null || custom.refreshHz != null) {
            val manualRates = if (platformKind == PlatformKind.ANDROID) usage.resources.supportedRefreshRates else emptyList()
            val choices = listOf<Pair<RefreshPreference?, String>>(
                null to "自动", RefreshPreference.SYSTEM to "系统", RefreshPreference.INTERACTION to "交互") +
                if (manualRates.isNotEmpty() || custom.refresh == RefreshPreference.MANUAL) listOf(RefreshPreference.MANUAL to "指定") else emptyList()
            SettingsChoices(choices, custom.refresh) { change(custom.copy(refresh = it)) }
            ExperienceReveal(custom.refresh == RefreshPreference.MANUAL) {
                if (manualRates.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    manualRates.forEach { rate ->
                        MotionTextButton(onClick = { change(custom.copy(refreshHz = rate)) },
                            enabled = active && expanded == "display" && custom.refresh == RefreshPreference.MANUAL,
                            modifier = Modifier.heightIn(min = 44.dp)) {
                            Text(rate.toString() + " Hz" + if (custom.refreshHz == rate) " ✓" else "")
                        }
                    }
                } else ExperienceHint("设备暂未提供可选刷新率，已保留指定请求")
            }
            ExperienceBudgetRow("当前刷新请求", budget.refreshHz?.let { it.toString() + " Hz" }
                ?: if (budget.preferHighRefresh) "优先高刷" else "跟随系统")
            ExperienceHelp("display-help", detail, { toggleDetail("display-help") }) {
                ExperienceHint(if (platformKind == PlatformKind.ANDROID)
                    "交互结束后释放高刷请求；系统温度、省电和显示能力决定最终刷新率。"
                    else "屏幕刷新由系统调度；应用减少后台工作以优先交互，当前平台不提供精确刷新率控制。")
                ExperienceHint("动效遵循应用与系统的减少动态效果设置。帧工作耗时不等于屏幕 FPS。")
            }
            ExperienceRestore(custom.refresh != null || custom.refreshHz != null, active) {
                closeEditor(); change(custom.copy(refresh = null, refreshHz = null))
            }
        }

        SettingsDisclosure("设备与资源保护", budget.protections.firstOrNull() ?: "按需保护",
            Icons.Outlined.Tune, expanded == "resources", { toggle("resources") },
            compact = true, modified = custom.allowMeteredPrefetch || !custom.batteryProtection) {
            SettingsToggle("计费网络也可预取", custom.allowMeteredPrefetch, Icons.Outlined.NetworkCheck) { change(custom.copy(allowMeteredPrefetch = it)) }
            SettingsToggle("低电量减少后台工作", custom.batteryProtection, Icons.Outlined.BatterySaver) { change(custom.copy(batteryProtection = it)) }
            if (budget.protections.isNotEmpty()) ExperienceHint(budget.protections.joinToString(" · "))
            SettingsDisclosure("运行详情", "", Icons.Outlined.Tune, detail == "diagnostics",
                { toggleDetail("diagnostics") }, compact = true) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("本次实际观测", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    MotionIconButton(onClick = store::refreshResources, enabled = active,
                        modifier = Modifier.size(44.dp), tooltip = "重新读取设备状态") {
                        Icon(Icons.Outlined.Refresh, "重新读取设备状态", Modifier.size(18.dp))
                    }
                }
                ExperienceBudgetRow("执行", usage.activeChapters.toString() + " 章 · " + usage.activeBackgroundPages + " 下载 · " + usage.activePrefetch + " 预取")
                ExperienceBudgetRow("当前页 / 排队", usage.activeReaderImages.toString() + " / " + usage.queuedTasks)
                ExperienceBudgetRow("限流来源", usage.throttledSources.toString())
                ExperienceBudgetRow("内存 / 磁盘命中", usage.memoryHits.toString() + " / " + usage.diskHits)
                ExperienceBudgetRow("预取复用 / 新取", usage.prefetchHits.toString() + " 次 / " + usage.prefetchedPages + " 页")
                ExperienceBudgetRow("请求 P95", usage.requestP95Millis?.let { it.toString() + " ms" } ?: "—")
                ExperienceBudgetRow("帧工作 P95 / P99", if (usage.frameP95Millis != null && usage.frameP99Millis != null)
                    frameMillis(usage.frameP95Millis!!) + " / " + frameMillis(usage.frameP99Millis!!)
                    else if (platformKind == PlatformKind.ANDROID) "尚无样本" else "平台未提供")
                ExperienceBudgetRow("实际传输", experienceCacheSize(usage.transferredBytes))
                ExperienceBudgetRow("应用堆内存", cacheUsage(usage.resources.processHeapBytes, usage.resources.processHeapLimitBytes))
                ExperienceBudgetRow("可用系统内存", usage.resources.availableMemoryBytes?.let(::experienceCacheSize) ?: "—")
                ExperienceBudgetRow("可用磁盘", usage.resources.freeDiskBytes?.let(::experienceCacheSize) ?: "—")
                ExperienceBudgetRow("处理器负载", usage.resources.cpuLoad?.let { (it * 100).roundToInt().toString() + "%" } ?: "—")
                ExperienceBudgetRow("网络计费", when (usage.resources.metered) { true -> "计费"; false -> "非计费"; null -> "未知" })
                ExperienceBudgetRow("电量 / 温度", (usage.resources.batteryPercent?.let { it.toString() + "%" } ?: "未知") + " / " +
                    when (usage.resources.thermalPressure) { 0 -> "正常"; 1 -> "升高"; 2 -> "较高"; 3 -> "严重"; else -> "未知" })
                ExperienceHint("请求耗时包含排队与解码；帧工作耗时不等于屏幕 FPS。未知不代表正常。")
            }
            ExperienceHelp("resources-help", detail, { toggleDetail("resources-help") }) {
                ExperienceHint("系统省电、严重温度与内存保护始终生效，恢复保留缓冲时间。计费网络及计费未知时，额外预热按当前预算收敛。")
            }
            ExperienceRestore(custom.allowMeteredPrefetch || !custom.batteryProtection, active) {
                closeEditor(); change(custom.copy(allowMeteredPrefetch = false, batteryProtection = true))
            }
        }
    }
}

private class ExperienceEditor {
    var field by mutableStateOf<String?>(null)
}

/** The same retained reveal/measurement owner used by SettingsDisclosure. */
@Composable
private fun ExperienceReveal(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
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
private fun ExperienceNumber(title: String, value: Int?, automatic: Int?, range: IntRange, unit: String,
    editor: ExperienceEditor, active: Boolean, effective: Int? = null, automaticLabel: String = "模式目标", onChange: (Int?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selected = value ?: automatic
    val current = (selected ?: range.first).coerceIn(range)
    val editing = active && editor.field == title
    val focus = LocalFocusManager.current
    var draft by remember { mutableStateOf("") }
    var sliding by remember { mutableFloatStateOf(current.toFloat()) }
    var invalid by remember { mutableStateOf(false) }
    fun acceptsInput() = active && editor.field == title
    fun commit() {
        if (!acceptsInput()) return
        val parsed = draft.toIntOrNull()
        if (parsed == null) { invalid = true; return }
        val accepted = parsed.coerceIn(range)
        draft = accepted.toString()
        sliding = accepted.toFloat()
        invalid = false
        onChange(accepted)
        focus.clearFocus(force = true)
    }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 300.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            val valueControl: @Composable () -> Unit = {
                MotionTextButton(onClick = {
                    if (active) {
                        focus.clearFocus(force = true)
                        if (editing) editor.field = null else {
                            draft = selected?.coerceIn(range)?.toString().orEmpty()
                            sliding = current.toFloat()
                            invalid = false
                            editor.field = title
                        }
                    }
                }, enabled = active, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.heightIn(min = 44.dp).semantics {
                        contentDescription = title + "，" + (selected?.toString()?.plus(" " + unit) ?: "自动值尚未知") +
                            (if (value == null) "，跟随模式目标" else "，手动上限") +
                            if (editing) "，点击取消未应用的编辑" else "，点击编辑"
                    }) {
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text((selected?.toString() ?: "—") + " " + unit, style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface)
                            Text(if (value == null) "自动" else "手动", style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant)
                            Icon(if (editing) Icons.Outlined.Close else Icons.Outlined.Edit, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                        }
                        if (selected != null && effective != null && effective != selected)
                            Text("实际 ≤" + effective + " " + unit, style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant)
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
                    if (acceptsInput() && it.length <= 5 && it.all(Char::isDigit)) { draft = it; invalid = false }
                }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                        .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 10.dp, vertical = 12.dp)
                        .semantics { contentDescription = title + "数值，单位" + unit },
                    decorationBox = { input ->
                        Box {
                            if (draft.isEmpty()) Text("输入上限", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            input()
                        }
                    })
                Text(unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                MotionIconButton(onClick = { commit() }, enabled = editing,
                    modifier = Modifier.size(44.dp), tooltip = "应用" + title) {
                    Icon(Icons.Outlined.Check, "应用" + title, Modifier.size(18.dp))
                }
                MotionIconButton(onClick = {
                    if (acceptsInput()) { editor.field = null; focus.clearFocus(force = true) }
                }, enabled = editing, modifier = Modifier.size(44.dp), tooltip = "取消未应用的" + title + "编辑") {
                    Icon(Icons.Outlined.Close, "取消编辑", Modifier.size(18.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(automaticLabel + " " + (automatic?.toString()?.plus(" " + unit) ?: "—"),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                MotionTextButton(onClick = {
                    if (acceptsInput()) {
                        editor.field = null
                        focus.clearFocus(force = true)
                        onChange(null)
                    }
                }, enabled = editing, modifier = Modifier.heightIn(min = 44.dp)) { Text("跟随目标", style = MaterialTheme.typography.labelSmall) }
            }
            if (invalid) Text("请输入 " + range.first + "–" + range.last, style = MaterialTheme.typography.labelSmall, color = colors.error)
            MotionSlider(sliding, onValueChange = {
                if (acceptsInput()) { sliding = it; draft = it.roundToInt().toString(); invalid = false }
            }, valueRange = range.first.toFloat()..range.last.toFloat(), onValueChangeFinished = {
                if (acceptsInput()) {
                    val accepted = sliding.roundToInt().coerceIn(range)
                    draft = accepted.toString()
                    onChange(accepted)
                }
            }, formatValue = { it.roundToInt().toString() + " " + unit },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title + "滑块" })
        }
    }
}

@Composable
private fun ExperienceHelp(key: String, expanded: String?, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    SettingsDisclosure("说明", "", Icons.Outlined.Info, expanded == key, onToggle, compact = true, content = content)
}

@Composable
private fun ExperienceRestore(visible: Boolean, active: Boolean, onRestore: () -> Unit) {
    ExperienceReveal(visible) {
        MotionTextButton(onClick = onRestore, enabled = active && visible, modifier = Modifier.heightIn(min = 44.dp)) {
            Icon(Icons.Outlined.RestartAlt, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("恢复跟随目标", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun experienceReadingCustom(value: ExperienceOverrides) =
    value.prefetchMode != PrefetchMode.SMART || value.ahead != null || value.behind != null ||
        value.prefetchRequests != null || value.prefetchMiB != null || !value.crossChapter ||
        value.readerRequests != null || !value.browseWarmup

private fun experienceDownloadCustom(value: ExperienceOverrides) =
    value.downloadPages != null || value.sourcePages != null || value.chapters != null ||
        !value.yieldToReading || !value.allowBackgroundDownloads

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
private fun cacheUsage(used: Long?, limit: Long?) = (used?.let(::experienceCacheSize) ?: "读取中") + " / " + (limit?.let(::experienceCacheSize) ?: "未知上限")
private fun frameMillis(value: Double) = ((value * 10).roundToInt() / 10.0).toString() + " ms"
internal fun experienceModeLabel(mode: ExperienceMode) = when (mode) { ExperienceMode.AUTO -> "自动"; ExperienceMode.ECO -> "节能"; ExperienceMode.PERFORMANCE -> "流畅优先" }
internal fun experienceCacheSize(bytes: Long): String = when {
    bytes >= 1024L * EXPERIENCE_MIB -> ((bytes.toDouble() / (1024L * EXPERIENCE_MIB) * 10).roundToInt() / 10.0).toString() + " GB"
    bytes >= EXPERIENCE_MIB -> ((bytes.toDouble() / EXPERIENCE_MIB * 10).roundToInt() / 10.0).toString() + " MB"
    else -> (bytes.coerceAtLeast(0L) / 1024L).toString() + " KB"
}
