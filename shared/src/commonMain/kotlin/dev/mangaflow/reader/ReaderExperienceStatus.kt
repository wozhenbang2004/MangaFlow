package dev.mangaflow.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ReaderNextChapterPreparation
import dev.mangaflow.data.ReaderNextChapterPreparationPhase
import dev.mangaflow.data.ReaderSourceStandby
import dev.mangaflow.data.ReaderSourceStandbyAvailability
import dev.mangaflow.data.ReaderSourceStandbyAvailabilityPhase
import dev.mangaflow.data.ReaderSourceStandbyPhase
import dev.mangaflow.experience.ExperienceBudget
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.ReaderStatusPlacement
import dev.mangaflow.experience.SourceRecoveryMode
import dev.mangaflow.ui.BodySourceMark
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.ExperienceSettingsDialog
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.experienceCacheSize
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import kotlinx.coroutines.delay

/** Observes this reader's owners; opening the strip never starts a transfer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderExperienceStatus(
    visible: Boolean,
    sessionKey: Any,
    store: ExperiencePolicyStore,
    budget: ExperienceBudget,
    standbys: List<ReaderSourceStandby>,
    alternativeCount: Int,
    backdrop: HazeState,
    topClearance: Dp,
    bottomClearance: Dp,
    onInspectingChange: (Boolean) -> Unit,
    preparation: ReaderPreparationState = ReaderPreparationState(),
    nextChapterPreparation: ReaderNextChapterPreparation? = null,
    standbyAvailability: ReaderSourceStandbyAvailability = ReaderSourceStandbyAvailability(),
    onOpenSources: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val custom by store.overrides.collectAsStateWithLifecycle()
    val motion = LocalMotionEnabled.current
    val placement = custom.readerStatusPlacement
    val shown = visible && placement != ReaderStatusPlacement.HIDDEN
    var selected by remember(sessionKey) { mutableStateOf<ReaderStatusSection?>(null) }
    var retained by remember(sessionKey) { mutableStateOf(ReaderStatusSection.PREPARATION) }
    var popoverPresent by remember { mutableStateOf(false) }
    var pendingPolicy by remember(sessionKey) { mutableStateOf(false) }
    var pendingSources by remember(sessionKey) { mutableStateOf(false) }
    var showPolicy by remember(sessionKey) { mutableStateOf(false) }
    var showScope by remember(sessionKey) { mutableStateOf(false) }
    var expandedSource by remember(sessionKey) { mutableStateOf<String?>(null) }
    val reportInspection by rememberUpdatedState(onInspectingChange)
    val openSources by rememberUpdatedState(onOpenSources)
    SideEffect { reportInspection(popoverPresent || selected != null || showPolicy || pendingPolicy || pendingSources) }
    DisposableEffect(Unit) { onDispose { reportInspection(false) } }
    LaunchedEffect(visible) {
        if (!visible) { selected = null; pendingPolicy = false; showPolicy = false; pendingSources = false }
    }
    LaunchedEffect(shown) { if (!shown) selected = null }
    LaunchedEffect(pendingPolicy, popoverPresent, visible) {
        if (visible && pendingPolicy && !popoverPresent) {
            pendingPolicy = false
            showPolicy = true
        }
    }
    LaunchedEffect(pendingSources, popoverPresent, visible) {
        if (visible && pendingSources && !popoverPresent) {
            pendingSources = false
            openSources?.invoke()
        }
    }
    // Shared cache sampling belongs to its low-frequency disclosure, not the live reader strip.
    LaunchedEffect(shown, selected, showPolicy) {
        if (!shown || selected != ReaderStatusSection.CACHE || showPolicy) return@LaunchedEffect
        while (true) { store.refreshCacheUsage(); delay(2_000L) }
    }
    val top = placement == ReaderStatusPlacement.TOP
    val clearance by animateDpAsState(
        if (top) topClearance else bottomClearance,
        animationSpec = if (motion) tween(MangaMotion.Chrome) else snap(), label = "reading-status-clearance",
    )
    val steps = listOf(preparation.resolving, preparation.originals, preparation.decoding)
    val failed = steps.sumOf { it.failed }
    val deferred = steps.sumOf { it.deferred }
    val active = steps.sumOf { it.active }
    val activityMark = when {
        failed > 0 -> ReaderStatusMark.ERROR
        active > 0 -> ReaderStatusMark.ACTIVE
        preparation.pauseReason != null || deferred > 0 -> ReaderStatusMark.PAUSED
        preparation.pages.isEmpty() -> ReaderStatusMark.UNKNOWN
        preparation.pages.any { it.originalPresent == null } -> ReaderStatusMark.UNKNOWN
        preparation.pages.any { it.originalPresent == true } -> ReaderStatusMark.READY
        else -> ReaderStatusMark.IDLE
    }
    val aheadPages = preparation.pages.filter { it.role == ReaderPreparationRole.AHEAD }
    val aheadSteps = aheadPages.flatMap { listOf(it.resolve, it.original, it.decode) }
    val aheadMark = when {
        aheadSteps.any { it.phase == ReaderPreparationPhase.FAILED } -> ReaderStatusMark.ERROR
        aheadSteps.any { it.phase == ReaderPreparationPhase.ACTIVE } -> ReaderStatusMark.ACTIVE
        preparation.pauseReason != null || aheadSteps.any { it.phase == ReaderPreparationPhase.DEFERRED } -> ReaderStatusMark.PAUSED
        preparation.pages.isEmpty() || aheadPages.any { it.originalPresent == null } -> ReaderStatusMark.UNKNOWN
        preparation.aheadReady > 0 -> ReaderStatusMark.READY
        else -> ReaderStatusMark.IDLE
    }
    val sourceStatus = standbySummary(standbyAvailability, standbys, budget)
    val aheadValue = if (preparation.aheadTarget <= 0) "—" else "${preparation.aheadReady}/${preparation.aheadTarget}"
    val aheadDetail = when {
        preparation.aheadTarget > 0 -> "已核实 ${preparation.aheadReady} / ${preparation.aheadTarget} 页"
        preparation.pauseReason != null -> preparation.pauseReason
        preparation.pages.isEmpty() -> "等待建立当前阅读窗口"
        else -> "当前没有前方准备目标"
    }
    val aheadReserve = fractionReserve(preparation.aheadTarget)
    val sourceReserve = if (sourceStatus.hasTargets) fractionReserve(standbys.size) else "待准备"
    val activityReserve = "8".repeat(preparation.pages.size.coerceAtLeast(1).toString().length)
    val activityWidth = statusNumberWidth("$activityReserve  $activityReserve", 64.dp)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.align(if (top) Alignment.TopStart else Alignment.BottomStart)
            .windowInsetsPadding(
                (if (top) readerStatusInsets().union(WindowInsets.captionBar) else readerNavigationInsets())
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal + if (top) WindowInsetsSides.Top else WindowInsetsSides.Bottom))
            .padding(start = 12.dp, end = 12.dp, top = if (top) clearance + 8.dp else 0.dp,
                bottom = if (top) 0.dp else clearance + 8.dp)) {
            AnimatedVisibility(shown,
                enter = fadeIn(tween(if (motion) MangaMotion.Content else 0)),
                exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
                LiquidGlassSurface(backdrop, cornerRadius = 22.dp, role = GlassSurfaceRole.READING_DOCK,
                    softCard = true, trackPointer = true) {
                    FlowRow(Modifier.padding(horizontal = 2.dp), maxItemsInEachRow = 3) {
                        fun inspect(section: ReaderStatusSection) { retained = section; selected = section }
                        StatusMicro(Icons.AutoMirrored.Outlined.MenuBook, aheadValue, aheadReserve,
                            "前方原图", aheadMark,
                            aheadDetail, shown,
                            progress = if (preparation.aheadTarget > 0) preparation.aheadReady.toFloat() / preparation.aheadTarget else null,
                        ) { inspect(ReaderStatusSection.PREPARATION) }
                        MotionIconButton(onClick = { inspect(ReaderStatusSection.PREPARATION) },
                            modifier = Modifier.width(activityWidth).height(44.dp).semantics(mergeDescendants = true) {
                                contentDescription = "当前窗口活动"
                                stateDescription = "原图 ${preparation.originals.active}，小图 ${preparation.decoding.active}，解析 ${preparation.resolving.active}，失败 $failed，暂缓 $deferred；点击查看"
                            }, enabled = shown, tooltip = "当前窗口 · 原图 / 小图") {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Outlined.Download, null, Modifier.size(15.dp))
                                StatusNumber(preparation.originals.active.toString())
                                Icon(Icons.Outlined.Memory, null, Modifier.padding(start = 3.dp).size(15.dp))
                                StatusNumber(preparation.decoding.active.toString())
                                StatusMark(activityMark, Modifier.size(9.dp))
                            }
                        }
                        StatusMicro(Icons.Outlined.Layers, sourceStatus.value, sourceReserve, "备用来源", sourceStatus.mark,
                            sourceStatus.detail, shown, progress = sourceStatus.progress,
                        ) { inspect(ReaderStatusSection.SOURCES) }
                    }
                }
            }
            CompactGlassPopover(visible = shown && selected != null, backdrop = backdrop,
                aboveAnchor = !top, alignToStart = true, maximumWidth = 344.dp,
                onDismiss = { selected = null }, onPresenceChange = { popoverPresent = it }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(retained.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        MotionIconButton(onClick = { retained = ReaderStatusSection.CACHE; selected = retained },
                            modifier = Modifier.size(44.dp), enabled = selected != null, tooltip = "共享缓存") {
                            Icon(Icons.Outlined.Storage, "共享缓存", Modifier.size(18.dp))
                        }
                        MotionIconButton(onClick = { selected = null; pendingPolicy = true },
                            modifier = Modifier.size(44.dp), enabled = selected != null, tooltip = "配置体验策略") {
                            Icon(Icons.Outlined.Tune, "配置体验策略", Modifier.size(18.dp))
                        }
                        MotionIconButton(onClick = { selected = null }, modifier = Modifier.size(44.dp),
                            enabled = selected != null, tooltip = "关闭状态详情") {
                            Icon(Icons.Outlined.Close, "关闭状态详情", Modifier.size(18.dp))
                        }
                    }
                    when (retained) {
                        ReaderStatusSection.PREPARATION -> {
                            PreparationDetails(preparation)
                            nextChapterPreparation?.let { NextChapterDetails(it) }
                        }
                        ReaderStatusSection.SOURCES -> {
                            StatusExplanation(sourceStatus.detail, error = sourceStatus.mark == ReaderStatusMark.ERROR)
                            standbys.forEach { row ->
                                StandbyDetails(row, expandedSource == row.variantId, selected != null) {
                                    expandedSource = if (expandedSource == row.variantId) null else row.variantId
                                }
                            }
                            if (alternativeCount > 0 && onOpenSources != null) MotionTextButton(
                                onClick = { selected = null; pendingSources = true }, enabled = selected != null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
                                Icon(Icons.Outlined.Layers, null, Modifier.size(16.dp))
                                Text("选择备用来源", Modifier.padding(start = 6.dp).weight(1f),
                                    style = MaterialTheme.typography.labelSmall)
                                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
                            }
                            nextChapterPreparation?.let { NextChapterDetails(it) }
                        }
                        ReaderStatusSection.CACHE -> {
                            CacheDetails(store, custom.contentCache.memoryIdleMinutes, custom.contentCache.imageRetentionHours)
                        }
                    }
                    MotionTextButton(onClick = { showScope = !showScope }, enabled = selected != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(15.dp))
                        Text("范围与策略", Modifier.padding(start = 6.dp).weight(1f), style = MaterialTheme.typography.labelSmall)
                        Icon(if (showScope) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                    }
                    if (showScope) {
                        StatusExplanation(when (retained) {
                            ReaderStatusSection.PREPARATION -> "仅统计本次阅读窗口。勾表示阶段完成；原图与小图是否仍在缓存，分别以实时检查为准。"
                            ReaderStatusSection.SOURCES -> "备用页按各来源阅读位置估算，不能视为同一页或已自动补图。小图驻留与原图已备分别统计。"
                            ReaderStatusSection.CACHE -> "这里是应用共享图片缓存，不是当前章节用量或应用全部内存。在用图片和离线内容有各自保护。"
                        })
                        StatusValue("配置窗口", "前 ${budget.prefetchAhead} · 后 ${budget.prefetchBehind} 页")
                        StatusValue("备用配置", "${budget.standbySources} 源 · 每源 ${budget.standbyPages} 页")
                        StatusExplanation(budget.reason)
                    }
                }
            }
        }
    }
    ExperienceSettingsDialog(visible = showPolicy && visible, backdrop = backdrop) { showPolicy = false }
}

private enum class ReaderStatusSection(val title: String) {
    PREPARATION("当前准备"), SOURCES("多源备用"), CACHE("共享缓存"),
}
private enum class ReaderStatusMark { ACTIVE, READY, PAUSED, UNKNOWN, ERROR, IDLE }

private data class StandbySummary(val value: String, val detail: String, val mark: ReaderStatusMark,
    val hasTargets: Boolean = false, val progress: Float? = null)

private fun standbySummary(availability: ReaderSourceStandbyAvailability, rows: List<ReaderSourceStandby>,
    budget: ExperienceBudget): StandbySummary {
    if (budget.sourceRecovery == SourceRecoveryMode.OFF) return StandbySummary("关闭",
        "备用准备已关闭，可在体验策略中开启", ReaderStatusMark.PAUSED)
    when (availability.phase) {
        ReaderSourceStandbyAvailabilityPhase.IDLE -> return StandbySummary("—",
            availability.reason ?: "尚未建立本章备用准备状态", ReaderStatusMark.UNKNOWN)
        ReaderSourceStandbyAvailabilityPhase.NO_ALTERNATIVES -> return StandbySummary("未关联",
            availability.reason ?: "当前没有已关联的其他来源", ReaderStatusMark.IDLE)
        ReaderSourceStandbyAvailabilityPhase.NO_MATCHING_CHAPTER -> return StandbySummary("未匹配",
            availability.reason ?: "已关联其他来源，本章还没有确认匹配的章节", ReaderStatusMark.UNKNOWN)
        ReaderSourceStandbyAvailabilityPhase.UNCONFIRMED -> return StandbySummary("待确认",
            availability.reason ?: "其他来源关联尚未确认，暂不自动准备", ReaderStatusMark.UNKNOWN)
        ReaderSourceStandbyAvailabilityPhase.DISABLED -> return StandbySummary("已停用",
            availability.reason ?: "匹配来源当前不可用，请检查来源设置", ReaderStatusMark.PAUSED)
        ReaderSourceStandbyAvailabilityPhase.ELIGIBLE -> Unit
    }
    if (budget.standbySources <= 0 || budget.standbyPrefetchBytes <= 0L) return StandbySummary("受限",
        budget.standbyPauseReason ?: budget.reason, ReaderStatusMark.PAUSED)
    val targets = rows.filter { it.requestedPages > 0 }
    if (targets.isEmpty()) {
        val failed = rows.firstOrNull { it.phase == ReaderSourceStandbyPhase.FAILED }
        if (rows.any { it.phase == ReaderSourceStandbyPhase.PREPARING || it.phase == ReaderSourceStandbyPhase.RESOLVING })
            return StandbySummary("目录中", "正在读取已确认来源的本章目录，尚未建立图片准备目标",
                if (failed != null) ReaderStatusMark.ERROR else ReaderStatusMark.ACTIVE)
        if (rows.any { it.phase == ReaderSourceStandbyPhase.QUEUED }) return StandbySummary("排队",
            rows.filter { it.phase == ReaderSourceStandbyPhase.QUEUED }.firstNotNullOfOrNull { it.reason }
                ?: "已确认备用来源，目录请求正在排队", if (failed != null) ReaderStatusMark.ERROR else ReaderStatusMark.UNKNOWN)
        if (failed != null) return StandbySummary("失败", failed.reason ?: "备用来源目录准备失败", ReaderStatusMark.ERROR)
        val deferred = rows.firstOrNull { it.phase == ReaderSourceStandbyPhase.DEFERRED }
        if (deferred != null) return StandbySummary("暂缓", deferred.reason ?: "备用准备暂缓，等待条件恢复", ReaderStatusMark.PAUSED)
        return StandbySummary("待准备", availability.reason ?: "已确认备用来源，尚未建立图片准备目标", ReaderStatusMark.IDLE)
    }
    val ready = targets.count { it.targetOriginalPresent }
    val mark = when {
        rows.any { it.failedPages > 0 || it.phase == ReaderSourceStandbyPhase.FAILED } -> ReaderStatusMark.ERROR
        rows.any { it.activePages > 0 || it.phase == ReaderSourceStandbyPhase.PREPARING } -> ReaderStatusMark.ACTIVE
        rows.any { it.deferredPages > 0 || it.phase == ReaderSourceStandbyPhase.DEFERRED } -> ReaderStatusMark.PAUSED
        ready > 0 -> ReaderStatusMark.READY
        rows.any { it.queuedPages > 0 || it.phase == ReaderSourceStandbyPhase.QUEUED } -> ReaderStatusMark.UNKNOWN
        else -> ReaderStatusMark.IDLE
    }
    return StandbySummary("$ready/${targets.size}",
        "已核实 $ready / ${targets.size} 个已建立目标的备用来源；各来源进度如下", mark,
        hasTargets = true, progress = ready.toFloat() / targets.size)
}

@Composable
private fun statusNumberWidth(reserve: String, decoration: Dp): Dp {
    val style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
    val measurer = rememberTextMeasurer()
    val width = measurer.measure(reserve, style, maxLines = 1, softWrap = false).size.width
    return with(LocalDensity.current) { width.toDp() } + decoration
}

private fun fractionReserve(total: Int): String {
    val digits = "8".repeat(total.coerceAtLeast(1).toString().length)
    return "$digits/$digits"
}

@Composable
private fun StatusNumber(value: String, modifier: Modifier = Modifier) {
    Text(value, modifier, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
        maxLines = 1, softWrap = false)
}

@Composable
private fun StatusMicro(icon: ImageVector, value: String, reserve: String, label: String,
    mark: ReaderStatusMark, detail: String, enabled: Boolean, progress: Float?, onClick: () -> Unit) {
    val width = statusNumberWidth(reserve, 44.dp).coerceAtLeast(60.dp)
    MotionIconButton(onClick, Modifier.width(width).height(44.dp).semantics(mergeDescendants = true) {
        contentDescription = label
        stateDescription = "$detail，${mark.description}，点击查看详情"
    }, enabled = enabled, tooltip = "$label · $detail") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, null, Modifier.size(16.dp))
                StatusNumber(value)
                StatusMark(mark, Modifier.size(9.dp))
            }
            Box(Modifier.width(width - 22.dp).height(2.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (progress != null) .08f else 0f), CircleShape)) {
                if (progress != null) Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .48f), CircleShape))
            }
        }
    }
}

private val ReaderStatusMark.description get() = when (this) {
    ReaderStatusMark.ACTIVE -> "进行中"; ReaderStatusMark.READY -> "已有核实原图"
    ReaderStatusMark.PAUSED -> "暂缓"; ReaderStatusMark.UNKNOWN -> "待核实"
    ReaderStatusMark.ERROR -> "有失败"; ReaderStatusMark.IDLE -> "待命"
}

@Composable
private fun StatusMark(mark: ReaderStatusMark, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    when (mark) {
        ReaderStatusMark.ACTIVE -> MotionBusyIndicator(modifier, color = color.copy(alpha = .82f), strokeWidth = 1.dp)
        ReaderStatusMark.READY -> Box(modifier.padding(2.dp).background(color.copy(alpha = .75f), CircleShape))
        ReaderStatusMark.PAUSED -> Icon(Icons.Outlined.Pause, null, modifier, tint = color.copy(alpha = .65f))
        ReaderStatusMark.UNKNOWN -> Icon(Icons.Outlined.HelpOutline, null, modifier, tint = color.copy(alpha = .65f))
        ReaderStatusMark.ERROR -> Icon(Icons.Outlined.ErrorOutline, null, modifier, tint = MaterialTheme.colorScheme.error)
        ReaderStatusMark.IDLE -> Spacer(modifier)
    }
}

@Composable
private fun PreparationDetails(state: ReaderPreparationState) {
    var showAllIssues by remember(state.sourceId, state.windowRevision) { mutableStateOf(false) }
    StatusValue("当前页", if (state.lastVisible >= state.firstVisible && state.pageCount > 0)
        "${pageRange(state.firstVisible, state.lastVisible)} / ${state.pageCount}" else "等待可见页")
    listOf(ReaderPreparationRole.AHEAD to "前方", ReaderPreparationRole.BEHIND to "回翻").forEach { (role, label) ->
        val pages = state.pages.filter { it.role == role }
        StatusValue(label, if (pages.isEmpty()) "无准备页" else
            "${pageRange(pages.minOf { it.pageIndex }, pages.maxOf { it.pageIndex })} · ${pages.count { it.originalPresent == true }}/${pages.size} 已备")
    }
    state.pauseReason?.let { StatusExplanation(it) }
    StageCounts(Icons.Outlined.Link, "解析", state.resolving)
    StageCounts(Icons.Outlined.Download, "原图", state.originals)
    StageCounts(Icons.Outlined.Memory, "小图", state.decoding)
    if (state.pages.isNotEmpty()) {
        StatusSubheading("页进度", "解析 · 原图 · 小图")
        PreparationPages(state.pages)
        val current = state.pages.filter { it.role == ReaderPreparationRole.VISIBLE }
        current.forEach { page ->
            StatusValue("第 ${page.pageIndex + 1} 页", "原图${presenceLabel(page.originalPresent)} · 小图${previewPresenceLabel(page.previewResident)}")
            if (page.joining) StatusExplanation("接续本页已开始的预读")
        }
        val received = state.pages.sumOf { it.receivedBytes }
        val expected = state.pages.takeIf { pages -> pages.all { it.expectedBytes != null } }
            ?.sumOf { it.expectedBytes ?: 0L }
        if (received > 0L) StatusValue("本窗口已接收", transferLabel(received, expected))
        val issues = state.pages.flatMap { page ->
            listOf("解析" to page.resolve, "原图" to page.original, "小图" to page.decode).mapNotNull { (stage, step) ->
                val reason = step.reason ?: if (step.phase == ReaderPreparationPhase.FAILED) "本次未返回具体原因" else null
                reason?.takeIf { step.phase == ReaderPreparationPhase.FAILED ||
                    step.phase == ReaderPreparationPhase.DEFERRED || step.phase == ReaderPreparationPhase.QUEUED }
                    ?.let { PreparationIssue(page.pageIndex, stage, step.phase, it) }
            }
        }.groupBy { Triple(it.stage, it.phase, it.reason) }
        issues.entries.sortedBy { when (it.key.second) {
            ReaderPreparationPhase.FAILED -> 0; ReaderPreparationPhase.DEFERRED -> 1; else -> 2
        } }.take(if (showAllIssues) issues.size else 3).forEach { (key, pages) ->
            StatusExplanation("第 ${pageRanges(pages.map { it.index })} 页 · ${key.first}${phaseLabel(key.second)} · ${key.third}",
                error = key.second == ReaderPreparationPhase.FAILED)
        }
        if (issues.size > 3) MotionTextButton(onClick = { showAllIssues = !showAllIssues },
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
            Text(if (showAllIssues) "收起其他原因" else "另 ${issues.size - 3} 项原因", Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall)
            Icon(if (showAllIssues) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StageCounts(icon: ImageVector, label: String, counts: ReaderPreparationCounts) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.widthIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(15.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
        }
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StageCount(Icons.Outlined.Schedule, "排队", counts.queued)
            StageCount(Icons.Outlined.PlayArrow, "进行", counts.active)
            StageCount(Icons.Outlined.Check, "阶段完成", counts.complete)
            StageCount(Icons.Outlined.Pause, "暂缓", counts.deferred)
            StageCount(Icons.Outlined.ErrorOutline, "失败", counts.failed)
        }
    }
}

@Composable
private fun StageCount(icon: ImageVector, label: String, count: Int) {
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label $count" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, null, Modifier.size(12.dp), tint = if (label == "失败" && count > 0)
            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        StatusNumber(count.toString())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreparationPages(pages: List<ReaderPreparationPage>) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pages.sortedBy { it.pageIndex }.forEach { page ->
            Column(Modifier.widthIn(min = 30.dp).semantics(mergeDescendants = true) {
                contentDescription = "第 ${page.pageIndex + 1} 页，${roleLabel(page.role)}，解析${phaseLabel(page.resolve.phase)}，原图${phaseLabel(page.original.phase)}，小图${phaseLabel(page.decode.phase)}，原图${presenceLabel(page.originalPresent)}，小图${previewPresenceLabel(page.previewResident)}"
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusNumber((page.pageIndex + 1).toString(), Modifier
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha =
                        if (page.role == ReaderPreparationRole.VISIBLE) .09f else 0f), CircleShape)
                    .padding(horizontal = 4.dp, vertical = 2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    StepMark(page.resolve.phase)
                    StepMark(page.original.phase)
                    StepMark(page.decode.phase)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PresenceMark(Icons.Outlined.Download, page.originalPresent)
                    PresenceMark(Icons.Outlined.Memory, page.previewResident)
                }
            }
        }
    }
}

@Composable
private fun StepMark(phase: ReaderPreparationPhase) {
    val mark = when (phase) {
        ReaderPreparationPhase.ACTIVE -> ReaderStatusMark.ACTIVE
        ReaderPreparationPhase.COMPLETE -> ReaderStatusMark.READY
        ReaderPreparationPhase.DEFERRED -> ReaderStatusMark.PAUSED
        ReaderPreparationPhase.FAILED -> ReaderStatusMark.ERROR
        ReaderPreparationPhase.QUEUED -> ReaderStatusMark.UNKNOWN
        ReaderPreparationPhase.SKIPPED -> ReaderStatusMark.IDLE
    }
    if (phase == ReaderPreparationPhase.COMPLETE) Icon(Icons.Outlined.Check, null, Modifier.size(10.dp))
    else if (phase == ReaderPreparationPhase.QUEUED) Icon(Icons.Outlined.Schedule, null, Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    else StatusMark(mark, Modifier.size(10.dp))
}

@Composable
private fun PresenceMark(icon: ImageVector, present: Boolean?) {
    Icon(if (present == null) Icons.Outlined.HelpOutline else icon, null, Modifier.size(10.dp),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = when (present) { true -> .85f; false -> .18f; null -> .45f }))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StandbyDetails(row: ReaderSourceStandby, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    MotionTextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
        BodySourceMark(row.sourceId, row.sourceName, Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.sourceName, style = MaterialTheme.typography.labelLarge)
            Text(if (row.requestedPages > 0) "原图 ${row.preparedPages}/${row.requestedPages} · 小图 ${row.previewResidentPages}"
                else row.reason ?: when (row.phase) {
                    ReaderSourceStandbyPhase.PREPARING, ReaderSourceStandbyPhase.RESOLVING -> "读取目录中，尚无图片准备目标"
                    ReaderSourceStandbyPhase.QUEUED -> "目录请求排队中"
                    ReaderSourceStandbyPhase.MANIFEST_READY -> "目录已备，等待建立图片目标"
                    else -> "尚未建立图片准备目标"
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(standbyPhaseLabel(row.phase), style = MaterialTheme.typography.labelSmall,
            color = if (row.phase == ReaderSourceStandbyPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.padding(start = 4.dp).size(16.dp))
    }
    if (row.requestedPages > 0) FlowRow(Modifier.fillMaxWidth().padding(start = 25.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StageCount(Icons.Outlined.Schedule, "排队", row.queuedPages)
        StageCount(Icons.Outlined.PlayArrow, "进行", row.activePages)
        StageCount(Icons.Outlined.ErrorOutline, "失败", row.failedPages)
        StageCount(Icons.Outlined.Pause, "暂缓", row.deferredPages)
    }
    if (expanded) {
        StatusValue(if (row.approximate) "估算位置" else "来源位置", row.targetPageIndex?.let {
            "${it + 1} / ${row.totalPages ?: "?"} 页"
        } ?: "等待目录")
        StatusValue("目标原图 / 小图", "${standbyPresenceLabel(row.targetOriginalPresent)} / ${standbyPresenceLabel(row.targetPreviewResident, preview = true)}")
        StatusValue("进行阶段", "解析 ${row.resolvingPages} · 原图 ${row.downloadingPages} · 检查 ${row.checkingPages}")
        if (row.receivedBytes > 0L) StatusValue("当前窗口已接收", transferLabel(row.receivedBytes, row.expectedBytes))
        if (row.cachedOriginalBytes > 0L) StatusValue("当前窗口已缓存", experienceCacheSize(row.cachedOriginalBytes))
        if (row.settlingPages > 0) StatusValue("旧窗口收尾", "${row.settlingPages} 页")
        val issues = row.windowPages.filter { it.phase == ReaderSourceStandbyPhase.FAILED || it.phase == ReaderSourceStandbyPhase.DEFERRED }
            .filter { it.reason != null }.groupBy { it.phase to it.reason }
        if (row.requestedPages > 0 && issues.keys.none { it.second == row.reason }) row.reason?.let {
            StatusExplanation(it, error = row.phase == ReaderSourceStandbyPhase.FAILED)
        }
        issues.forEach { (key, pages) ->
            StatusExplanation("第 ${pageRanges(pages.map { it.index })} 页 · ${key.second}", error = key.first == ReaderSourceStandbyPhase.FAILED)
        }
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            row.windowPages.forEach { page ->
                Column(Modifier.widthIn(min = 32.dp).semantics(mergeDescendants = true) {
                    contentDescription = "估算来源第 ${page.index + 1} 页，${standbyPhaseLabel(page.phase)}，原图${standbyPresenceLabel(page.originalPresent)}，小图${standbyPresenceLabel(page.previewResident, preview = true)}"
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    StatusNumber((page.index + 1).toString())
                    StatusMark(standbyMark(page.phase), Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PresenceMark(Icons.Outlined.Download, page.originalPresent)
                        PresenceMark(Icons.Outlined.Memory, page.previewResident)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NextChapterDetails(next: ReaderNextChapterPreparation) {
    StatusSubheading("下一话", next.chapterTitle)
    StatusValue("原图已备", if (next.requestedPages > 0) "${next.originalReadyPages}/${next.requestedPages}" else "尚未建立准备目标")
    StatusValue("当前状态", nextChapterPhaseLabel(next.phase))
    FlowRow(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StageCount(Icons.Outlined.Schedule, "排队", next.queuedPages)
        StageCount(Icons.Outlined.PlayArrow, "进行", next.activePages)
        StageCount(Icons.Outlined.ErrorOutline, "失败", next.failedPages)
        StageCount(Icons.Outlined.Pause, "暂缓", next.deferredPages)
    }
    if (next.activePages > 0) StatusValue("进行阶段", "解析 ${next.resolvingPages} · 原图 ${next.downloadingPages} · 检查 ${next.checkingPages}")
    next.receivedBytes?.takeIf { it > 0L }?.let { StatusValue("已接收", experienceCacheSize(it)) }
    next.reason?.let { StatusExplanation(it, error = next.phase == ReaderNextChapterPreparationPhase.FAILED) }
}

@Composable
private fun CacheDetails(store: ExperiencePolicyStore, memoryIdleMinutes: Int, imageRetentionHours: Int) {
    // This subscription exists only while CACHE is the retained popover scene.
    val usage by store.usage.collectAsStateWithLifecycle()
    StatusValue("共享小图内存", statusCacheUsage(usage.memoryBytes, usage.memoryLimit))
    StatusValue("共享原图磁盘", statusCacheUsage(usage.diskBytes, usage.diskLimit))
    StatusValue("小图闲置保留", if (memoryIdleMinutes == 0) "按容量回收" else "$memoryIdleMinutes 分钟")
    StatusValue("原图保留", if (imageRetentionHours == 0) "按空间回收" else "$imageRetentionHours 小时")
    if (usage.diskResizePending) StatusExplanation("等待在用原图释放后调整容量")
    usage.cacheError?.let { StatusExplanation(it, error = true) }
}

private fun nextChapterPhaseLabel(phase: ReaderNextChapterPreparationPhase) = when (phase) {
    ReaderNextChapterPreparationPhase.QUEUED -> "排队"
    ReaderNextChapterPreparationPhase.MANIFEST -> "读取目录"
    ReaderNextChapterPreparationPhase.PREPARING -> "准备原图"
    ReaderNextChapterPreparationPhase.READY -> "原图已备"
    ReaderNextChapterPreparationPhase.DEFERRED -> "暂缓"
    ReaderNextChapterPreparationPhase.FAILED -> "失败"
}

private fun standbyMark(phase: ReaderSourceStandbyPhase) = when (phase) {
    ReaderSourceStandbyPhase.PREPARING, ReaderSourceStandbyPhase.RESOLVING,
    ReaderSourceStandbyPhase.DOWNLOADING, ReaderSourceStandbyPhase.CHECKING -> ReaderStatusMark.ACTIVE
    ReaderSourceStandbyPhase.READY -> ReaderStatusMark.READY
    ReaderSourceStandbyPhase.FAILED -> ReaderStatusMark.ERROR
    ReaderSourceStandbyPhase.DEFERRED -> ReaderStatusMark.PAUSED
    ReaderSourceStandbyPhase.QUEUED -> ReaderStatusMark.UNKNOWN
    ReaderSourceStandbyPhase.MANIFEST_READY -> ReaderStatusMark.IDLE
}

private fun standbyPhaseLabel(phase: ReaderSourceStandbyPhase) = when (phase) {
    ReaderSourceStandbyPhase.PREPARING -> "目录中"
    ReaderSourceStandbyPhase.MANIFEST_READY -> "目录已备"
    ReaderSourceStandbyPhase.QUEUED -> "排队"
    ReaderSourceStandbyPhase.RESOLVING -> "解析"
    ReaderSourceStandbyPhase.DOWNLOADING -> "原图中"
    ReaderSourceStandbyPhase.CHECKING -> "检查中"
    ReaderSourceStandbyPhase.READY -> "目标已备"
    ReaderSourceStandbyPhase.FAILED -> "失败"
    ReaderSourceStandbyPhase.DEFERRED -> "暂缓"
}
private fun roleLabel(role: ReaderPreparationRole) = when (role) {
    ReaderPreparationRole.VISIBLE -> "当前"; ReaderPreparationRole.AHEAD -> "前方"; ReaderPreparationRole.BEHIND -> "回翻"
}
private fun phaseLabel(phase: ReaderPreparationPhase) = when (phase) {
    ReaderPreparationPhase.QUEUED -> "排队"; ReaderPreparationPhase.ACTIVE -> "进行中"
    ReaderPreparationPhase.COMPLETE -> "阶段完成"; ReaderPreparationPhase.DEFERRED -> "暂缓"
    ReaderPreparationPhase.FAILED -> "失败"; ReaderPreparationPhase.SKIPPED -> "未安排"
}
private fun presenceLabel(present: Boolean?) = when (present) { true -> "已备"; false -> "未备"; null -> "待核实" }
private fun previewPresenceLabel(present: Boolean?) = when (present) { true -> "驻留"; false -> "未驻留"; null -> "待核实" }
private fun standbyPresenceLabel(present: Boolean, preview: Boolean = false) =
    if (present) { if (preview) "驻留" else "已备" } else "未确认"
private data class PreparationIssue(val index: Int, val stage: String, val phase: ReaderPreparationPhase, val reason: String)
private fun pageRanges(indices: List<Int>): String {
    val pages = indices.distinct().sorted()
    if (pages.isEmpty()) return ""
    var first = pages.first()
    var last = first
    val ranges = mutableListOf<String>()
    pages.drop(1).forEach { index ->
        if (index == last + 1) last = index else {
            ranges += pageRange(first, last)
            first = index
            last = index
        }
    }
    ranges += pageRange(first, last)
    return ranges.joinToString("、")
}
private fun pageRange(first: Int, last: Int) = if (first == last) "${first + 1}" else "${first + 1}–${last + 1}"
private fun transferLabel(received: Long, expected: Long?) = experienceCacheSize(received) +
    (expected?.takeIf { it > 0L }?.let { " / ${experienceCacheSize(it)}" } ?: "")

@Composable
private fun StatusSubheading(label: String, detail: String) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(detail, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(.9f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.3f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusExplanation(value: String, error: Boolean = false) {
    Text(value, Modifier.fillMaxWidth().padding(vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun statusCacheUsage(used: Long?, limit: Long?) =
    (used?.let(::experienceCacheSize) ?: "未知") + " / " + (limit?.let(::experienceCacheSize) ?: "未知")
