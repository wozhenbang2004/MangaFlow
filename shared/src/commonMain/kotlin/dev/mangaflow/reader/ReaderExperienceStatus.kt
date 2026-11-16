package dev.mangaflow.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
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
import dev.mangaflow.data.approximateReaderPage
import dev.mangaflow.experience.ExperienceBudget
import dev.mangaflow.experience.ExperienceMemoryCacheUsage
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.ExperienceUsage
import dev.mangaflow.experience.ReaderStatusPlacement
import dev.mangaflow.experience.SourceRecoveryMode
import dev.mangaflow.ui.BodySourceMark
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.ExperienceSettingsDialog
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.experienceCacheSize
import dev.mangaflow.ui.mangaStatusColors
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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
    onRetryStandby: ((String) -> Unit)? = null,
    onUseStandby: ((String) -> Unit)? = null,
    repairedPages: Int = 0,
    repairBusy: Boolean = false,
    repairReason: String? = null,
    onAlignPage: (() -> Unit)? = null,
    onUndoPage: (() -> Unit)? = null,
    onUndoSourceSwitch: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val custom by store.overrides.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var changingSourceLock by remember(sessionKey) { mutableStateOf(false) }
    var sourceLockError by remember(sessionKey) { mutableStateOf<String?>(null) }
    val motion = LocalMotionEnabled.current
    val window = LocalMangaWindow.current
    val placement = custom.readerStatusPlacement
    val shown = visible && placement != ReaderStatusPlacement.HIDDEN
    var selected by remember(sessionKey) { mutableStateOf<ReaderStatusSection?>(null) }
    var retainedSection by remember(sessionKey) { mutableStateOf(ReaderStatusSection.PREPARATION) }
    val sectionHeader = remember(sessionKey) { BringIntoViewRequester() }
    val expandedWorks = remember(sessionKey) { mutableStateMapOf<ReaderStatusSection, ReaderWork?>() }
    var popoverPresent by remember { mutableStateOf(false) }
    var pendingPolicy by remember(sessionKey) { mutableStateOf(false) }
    var pendingSources by remember(sessionKey) { mutableStateOf(false) }
    var pendingStandby by remember(sessionKey) { mutableStateOf<Pair<StandbyAction, String>?>(null) }
    var showPolicy by remember(sessionKey) { mutableStateOf(false) }
    val scopeHelp = remember(sessionKey) { mutableStateMapOf<ReaderStatusSection, Boolean>() }
    var expandedSource by remember(sessionKey) { mutableStateOf<String?>(null) }
    val reportInspection by rememberUpdatedState(onInspectingChange)
    val openSources by rememberUpdatedState(onOpenSources)
    val retryStandby by rememberUpdatedState(onRetryStandby)
    val useStandby by rememberUpdatedState(onUseStandby)
    val alignPage by rememberUpdatedState(onAlignPage)
    val undoPage by rememberUpdatedState(onUndoPage)
    val undoSourceSwitch by rememberUpdatedState(onUndoSourceSwitch)
    SideEffect { reportInspection(popoverPresent || selected != null || showPolicy || pendingPolicy || pendingSources || pendingStandby != null) }
    DisposableEffect(Unit) { onDispose { reportInspection(false) } }
    LaunchedEffect(visible) {
        if (!visible) { selected = null; pendingPolicy = false; showPolicy = false; pendingSources = false; pendingStandby = null }
    }
    LaunchedEffect(shown) { if (!shown) selected = null }
    LaunchedEffect(selected, popoverPresent) {
        if (selected != null && popoverPresent) {
            withFrameNanos { }
            sectionHeader.bringIntoView()
        }
    }
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
    LaunchedEffect(pendingStandby, popoverPresent, visible) {
        if (visible && pendingStandby != null && !popoverPresent) {
            val action = pendingStandby
            pendingStandby = null
            action?.let { (kind, id) -> when (kind) {
                StandbyAction.RETRY -> retryStandby?.invoke(id)
                StandbyAction.USE -> useStandby?.invoke(id)
                StandbyAction.ALIGN -> alignPage?.invoke()
                StandbyAction.UNDO_PAGE -> undoPage?.invoke()
                StandbyAction.UNDO_SOURCE -> undoSourceSwitch?.invoke()
            } }
        }
    }
    // Local cache sampling follows this indicator's presence; hidden readers do not poll it.
    LaunchedEffect(shown) {
        if (!shown) return@LaunchedEffect
        while (true) { store.refreshCacheUsage(); delay(store.state.value.runtime.cacheSampleSeconds.coerceAtLeast(1).toLong() * 1000L) }
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
    val queued = steps.sumOf { it.queued }
    val workMaximum = maxOf(preparation.pages.size.toLong(),
        steps.maxOf { maxOf(it.active, it.queued, it.complete, it.deferred, it.failed) }.toLong(),
        nextChapterPreparation?.let { next -> maxOf(next.requestedPages.toLong() + 1L,
            next.activePages.toLong() + 1L, next.queuedPages.toLong() + 1L,
            next.originalReadyPages.toLong(), next.deferredPages.toLong(), next.failedPages.toLong()) } ?: 0L)
    val sourceMaximum = standbys.maxOfOrNull { row -> maxOf(row.requestedPages.toLong() + 1L,
        row.preparedPages.toLong(), row.previewResidentPages.toLong(), row.activePages.toLong() + 1L,
        row.queuedPages.toLong() + 1L, row.failedPages.toLong()) } ?: 1L
    val sourceReserve = "8".repeat(sourceMaximum.coerceAtLeast(1L).toString().length)
    val activityMark = when {
        failed > 0 -> ReaderStatusMark.ERROR
        active > 0 -> ReaderStatusMark.ACTIVE
        preparation.pauseReason != null || deferred > 0 -> ReaderStatusMark.PAUSED
        queued > 0 -> ReaderStatusMark.QUEUED
        preparation.pages.isEmpty() -> ReaderStatusMark.UNKNOWN
        steps.any { it.complete > 0 } -> ReaderStatusMark.READY
        else -> ReaderStatusMark.IDLE
    }
    val aheadPages = preparation.pages.filter { it.role == ReaderPreparationRole.AHEAD }
    val aheadMark = originalReadinessMark(aheadPages, preparation.pauseReason)
    val sourceStatus = standbySummary(standbyAvailability, standbys, budget)
    val aheadValue = originalReadinessValue(aheadPages)
    val aheadDetail = when {
        preparation.aheadTarget > 0 -> "已核实 ${preparation.aheadReady} / ${preparation.aheadTarget} 页"
        preparation.pauseReason != null -> preparation.pauseReason
        preparation.pages.isEmpty() -> "等待建立当前阅读窗口"
        else -> "当前没有前方准备目标"
    }
    val aheadReserve = fractionReserve(preparation.aheadTarget)
    val sourceStripReserve = fractionReserve(standbys.size)
    val activityReserve = "8".repeat((preparation.pages.size.toLong() * 3L).coerceAtLeast(1L).toString().length)
    fun inspect(section: ReaderStatusSection) {
        retainedSection = section
        selected = section
    }
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
                    ReaderStatusStrip(selected, retainedSection, shown,
                        maxOf(76.dp, maxOf(statusNumberWidth(aheadReserve, 45.dp), statusNumberWidth(activityReserve, 45.dp),
                            statusNumberWidth(sourceStripReserve, 45.dp))), ::inspect) { section, isSelected ->
                        when (section) {
                            ReaderStatusSection.PREPARATION -> StatusMicro(Icons.AutoMirrored.Outlined.MenuBook,
                                aheadValue, "前方原图", aheadMark, aheadDetail,
                                progress = if (preparation.aheadTarget > 0) preparation.aheadReady.toFloat() / preparation.aheadTarget else null,
                                selected = isSelected)
                            ReaderStatusSection.WORK -> StatusMicro(Icons.Outlined.AccountTree,
                                if (preparation.pages.isEmpty()) "—" else active.toString(), "当前窗口工作", activityMark,
                                "原图 ${preparation.originals.active}，解码与核验 ${preparation.decoding.active}，解析 ${preparation.resolving.active}，排队 $queued，失败 $failed，暂缓 $deferred；${steps.sumOf { it.complete }} 项阶段完成",
                                progress = null, selected = isSelected)
                            ReaderStatusSection.SOURCES -> StatusMicro(Icons.Outlined.Layers,
                                if (sourceStatus.hasTargets) sourceStatus.value else "—", "备用来源", sourceStatus.mark,
                                sourceStatus.detail, progress = sourceStatus.progress, selected = isSelected)
                            ReaderStatusSection.CACHE -> ReaderCacheEntry(store, isSelected)
                        }
                    }
                }
            }
            CompactGlassPopover(visible = shown && selected != null, backdrop = backdrop,
                aboveAnchor = !top, alignToStart = true,
                maximumWidth = if (window.width >= 720.dp) 480.dp else 344.dp,
                onDismiss = { selected = null }, onPresenceChange = { popoverPresent = it }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth().bringIntoViewRequester(sectionHeader), verticalAlignment = Alignment.CenterVertically) {
                        Text(retainedSection.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MotionIconButton(onClick = { scopeHelp[retainedSection] = scopeHelp[retainedSection] != true }, modifier = Modifier.size(44.dp),
                            enabled = selected != null, tooltip = "图标与当前范围说明") {
                            Icon(Icons.Outlined.Info, "图标与当前范围说明", Modifier.size(18.dp),
                                tint = if (scopeHelp[retainedSection] == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
                    ReaderStatusTabs(retainedSection, selected != null, ::inspect)
                    MotionTabContent(selected = retainedSection, contentKey = { it }, order = { it.ordinal },
                        fillContent = false) { shownSection ->
                        val bodyEnabled = selected == shownSection
                        val expandedWork = expandedWorks[shownSection]
                        fun toggle(work: ReaderWork) { if (bodyEnabled) expandedWorks[shownSection] = if (expandedWork == work) null else work }
                        val currentPage = if (preparation.lastVisible >= preparation.firstVisible && preparation.pageCount > 0)
                            "${pageRange(preparation.firstVisible, preparation.lastVisible)} / ${preparation.pageCount}" else "—"
                        Column(Modifier.fillMaxWidth().motionInputGate(bodyEnabled)) {
                            StatusReveal(scopeHelp[shownSection] == true) { ReaderStatusLegend(shownSection, budget) }
                            when (shownSection) {
                                ReaderStatusSection.PREPARATION -> {
                                    StatusContext(currentPage)
                                    ReadinessRow(Icons.Outlined.KeyboardDoubleArrowRight, "前方",
                                        preparation.pages.filter { it.role == ReaderPreparationRole.AHEAD },
                                        preparation.pauseReason, expandedWork == ReaderWork.AHEAD, bodyEnabled) { toggle(ReaderWork.AHEAD) }
                                    StatusReveal(expandedWork == ReaderWork.AHEAD) {
                                        PreparationPages(preparation.pages.filter { it.role == ReaderPreparationRole.AHEAD }, ReaderWork.ORIGINAL)
                                    }
                                    ReadinessRow(Icons.Outlined.KeyboardDoubleArrowLeft, "回看",
                                        preparation.pages.filter { it.role == ReaderPreparationRole.BEHIND },
                                        preparation.pauseReason, expandedWork == ReaderWork.BEHIND, bodyEnabled) { toggle(ReaderWork.BEHIND) }
                                    StatusReveal(expandedWork == ReaderWork.BEHIND) {
                                        PreparationPages(preparation.pages.filter { it.role == ReaderPreparationRole.BEHIND }, ReaderWork.ORIGINAL)
                                    }
                                    preparation.pauseReason?.let { StatusExplanation(it) }
                                    nextChapterPreparation?.let { next ->
                                        NextWorkRow(next, workMaximum, expandedWork == ReaderWork.NEXT, bodyEnabled) { toggle(ReaderWork.NEXT) }
                                        StatusReveal(expandedWork == ReaderWork.NEXT) { NextChapterDetails(next) }
                                    }
                                }
                                ReaderStatusSection.WORK -> {
                                    StatusContext(currentPage)
                                    listOf(ReaderWork.RESOLVE, ReaderWork.ORIGINAL, ReaderWork.PREVIEW).forEach { work ->
                                        val counts = work.counts(preparation)
                                        WorkDisclosure(work.icon, work.title, counts.active.toLong(), workMaximum,
                                            workSummary(preparation, work), expandedWork == work, bodyEnabled, counts.failed > 0,
                                            counts.queued.toLong(), counts.complete.toLong(), counts.deferred.toLong(), counts.failed.toLong(),
                                            known = preparation.pages.isNotEmpty()) { toggle(work) }
                                        StatusReveal(expandedWork == work) { PreparationDetails(preparation, work) }
                                    }
                                    nextChapterPreparation?.let { next ->
                                        NextWorkRow(next, workMaximum, expandedWork == ReaderWork.NEXT, bodyEnabled) { toggle(ReaderWork.NEXT) }
                                        StatusReveal(expandedWork == ReaderWork.NEXT) { NextChapterDetails(next) }
                                    }
                                }
                                ReaderStatusSection.SOURCES -> {
                                    StatusContext(currentPage)
                                    sourceLockError?.let { StatusExplanation(it, error = true) }
                                    repairReason?.let { StatusExplanation(it) }
                                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        if (onAlignPage != null) MotionIconButton(onClick = {
                                            selected = null; pendingStandby = StandbyAction.ALIGN to ""
                                        }, enabled = bodyEnabled, modifier = Modifier.size(44.dp), tooltip = "对齐附近来源，确认后补当前页") {
                                            Icon(Icons.Outlined.CropFree, "对齐补当前页", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                        if (repairBusy || repairedPages > 0) Row(Modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (repairBusy) MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                            if (repairedPages > 0) StatusCounter(Icons.Outlined.RestorePage, repairedPages.toLong(), "已补页数",
                                                iconColor = mangaStatusColors().success)
                                        }
                                        if (onUndoPage != null) MotionIconButton(onClick = {
                                            selected = null; pendingStandby = StandbyAction.UNDO_PAGE to ""
                                        }, enabled = bodyEnabled, modifier = Modifier.size(44.dp), tooltip = "撤回当前单页替换") {
                                            Icon(Icons.Outlined.RestorePage, "撤回当前单页替换", Modifier.size(18.dp))
                                        }
                                        if (onUndoSourceSwitch != null) MotionIconButton(onClick = {
                                            selected = null; pendingStandby = StandbyAction.UNDO_SOURCE to ""
                                        }, enabled = bodyEnabled, modifier = Modifier.size(44.dp), tooltip = "撤销整章切源，回到原来源原页") {
                                            Icon(Icons.Outlined.Undo, "撤销整章切源", Modifier.size(18.dp))
                                        }
                                        MotionIconButton(onClick = {
                                            if (!changingSourceLock) {
                                                changingSourceLock = true; sourceLockError = null
                                                val locked = !store.overrides.value.recovery.sourceLocked
                                                scope.launch {
                                                    try { if (!store.setSourceLocked(locked)) sourceLockError = "来源锁定未保存，请重试" }
                                                    catch (cancel: CancellationException) { throw cancel }
                                                    catch (_: Exception) { sourceLockError = "来源锁定未保存，请重试" }
                                                    finally { changingSourceLock = false }
                                                }
                                            }
                                        }, enabled = bodyEnabled && !changingSourceLock, modifier = Modifier.size(44.dp),
                                            tooltip = if (custom.recovery.sourceLocked) "恢复自动补页与自动切源（全局）" else "暂停所有漫画的自动补页和整章切源；手动选择仍可用") {
                                            if (changingSourceLock) MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                                            else Icon(if (custom.recovery.sourceLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                                if (custom.recovery.sourceLocked) "自动补页与切源已暂停" else "允许自动补页与切源", Modifier.size(18.dp),
                                                tint = if (custom.recovery.sourceLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (alternativeCount > 0 && onOpenSources != null) MotionIconButton(
                                            onClick = { selected = null; pendingSources = true }, enabled = bodyEnabled,
                                            modifier = Modifier.size(44.dp), tooltip = "选择整章来源") {
                                            Icon(Icons.Outlined.Layers, "选择整章来源", Modifier.size(19.dp))
                                        }
                                    }
                                    if (standbys.isEmpty() || !sourceStatus.hasTargets)
                                        StatusExplanation(sourceStatus.detail, error = sourceStatus.mark == ReaderStatusMark.ERROR)
                                    standbys.forEach { row -> key(row.variantId) {
                                        StandbyDetails(row, sourceReserve, expandedSource == row.variantId, bodyEnabled,
                                            onToggle = { if (bodyEnabled) expandedSource = if (expandedSource == row.variantId) null else row.variantId },
                                            onRetry = if (onRetryStandby != null && !row.manifestQueued && !row.manifestActive &&
                                                (row.phase in setOf(ReaderSourceStandbyPhase.FAILED, ReaderSourceStandbyPhase.DEFERRED) ||
                                                    row.failedPages > 0 || row.deferredPages > 0)) ({
                                                selected = null; pendingStandby = StandbyAction.RETRY to row.variantId
                                            }) else null,
                                            onUse = if (onUseStandby != null && row.targetOriginalPresent &&
                                                preparation.pageCount > 0 && preparation.lastVisible >= preparation.firstVisible &&
                                                row.totalPages?.let { total -> total > 0 && row.targetPageIndex ==
                                                    approximateReaderPage(preparation.firstVisible, preparation.pageCount, total) } == true) ({
                                                selected = null; pendingStandby = StandbyAction.USE to row.variantId
                                            }) else null)
                                    } }
                                }
                                ReaderStatusSection.CACHE -> CacheOverview(store, expandedWork, bodyEnabled,
                                    custom.contentCache.memoryIdleMinutes, custom.contentCache.imageRetentionHours,
                                    custom.memoryMiB, custom.diskMiB, onToggle = ::toggle)
                            }
                        }
                    }
                }
            }
        }
    }
    ExperienceSettingsDialog(visible = showPolicy && visible, backdrop = backdrop,
        onDismiss = { showPolicy = false })
}

private enum class ReaderStatusSection(val title: String, val icon: ImageVector) {
    PREPARATION("准备", Icons.AutoMirrored.Outlined.MenuBook), WORK("工作", Icons.Outlined.AccountTree),
    SOURCES("多源", Icons.Outlined.Layers), CACHE("缓存", Icons.Outlined.Storage)
}
private enum class StandbyAction { RETRY, USE, ALIGN, UNDO_PAGE, UNDO_SOURCE }
private enum class ReaderWork(val title: String, val icon: ImageVector) {
    RESOLVE("解析", Icons.Outlined.Link), ORIGINAL("原图", Icons.Outlined.Download),
    PREVIEW("解码", Icons.Outlined.PhotoSizeSelectLarge),
    AHEAD("前方", Icons.Outlined.KeyboardDoubleArrowRight), BEHIND("回看", Icons.Outlined.KeyboardDoubleArrowLeft),
    NEXT("下一话", Icons.AutoMirrored.Outlined.MenuBook), MEMORY("解码内存", Icons.Outlined.Memory), DISK("图片磁盘", Icons.Outlined.Storage);
    fun counts(state: ReaderPreparationState) = when (this) {
        RESOLVE -> state.resolving; ORIGINAL -> state.originals; else -> state.decoding
    }
    fun step(page: ReaderPreparationPage) = when (this) {
        RESOLVE -> page.resolve; ORIGINAL -> page.original; else -> page.decode
    }
}
private enum class ReaderStatusMark { ACTIVE, READY, QUEUED, PAUSED, UNKNOWN, ERROR, DISABLED, IDLE }

/** The adopted Ovio indicator moves behind stationary, fully reserved reading controls. */
@Composable
private fun ReaderStatusStrip(selected: ReaderStatusSection?, retained: ReaderStatusSection, enabled: Boolean,
    cellWidth: Dp, onSelect: (ReaderStatusSection) -> Unit,
    content: @Composable (ReaderStatusSection, Boolean) -> Unit) {
    val entries = ReaderStatusSection.entries
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val anchors = remember { List(entries.size) { BringIntoViewRequester() } }
    LaunchedEffect(selected) {
        if (selected != null) {
            withFrameNanos { }
            anchors[selected.ordinal].bringIntoView()
        }
    }
    BoxWithConstraints(Modifier.padding(horizontal = 2.dp)) {
        val trackWidth = cellWidth * entries.size
        Box(Modifier.width(minOf(maxWidth, trackWidth)).horizontalScroll(scroll)) {
            MotionTabs(selected = retained.ordinal, labels = entries.map { it.title },
                onSelect = { if (enabled) onSelect(entries[it]) },
                modifier = Modifier.width(trackWidth).motionInputGate(enabled),
                foreground = colors.primary, muted = colors.onSurfaceVariant,
                indicator = colors.primary.copy(alpha = .16f), fullWidthIndicator = true,
                controlHeight = 44.dp, indicatorCornerRadius = 18.dp,
                role = Role.Button, selectionVisible = enabled && selected != null,
                tooltips = if (enabled) listOf("前方原图", "当前窗口工作", "备用来源", "共享内存 / 磁盘用量") else null,
                itemContent = { index, isSelected, _ ->
                    Box(Modifier.fillMaxSize().bringIntoViewRequester(anchors[index]), contentAlignment = Alignment.Center) {
                        content(entries[index], isSelected)
                    }
                })
        }
    }
}

@Composable
private fun ReaderStatusTabs(section: ReaderStatusSection, enabled: Boolean, onSelect: (ReaderStatusSection) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val entries = ReaderStatusSection.entries
    val scroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val trackWidth = maxOf(maxWidth, 44.dp * entries.size)
        Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            MotionTabs(selected = section.ordinal, labels = entries.map { it.title },
                onSelect = { if (enabled) onSelect(entries[it]) },
                modifier = Modifier.width(trackWidth).motionInputGate(enabled),
                foreground = colors.primary, muted = colors.onSurfaceVariant,
                track = colors.onSurface.copy(alpha = .025f), indicator = colors.primary.copy(alpha = .16f),
                treatment = MotionTabTreatment.Capsule, fullWidthIndicator = true, controlHeight = 44.dp,
                tooltips = if (enabled) entries.map { it.title } else null,
                itemContent = { index, isSelected, color ->
                    Icon(entries[index].icon, entries[index].title, Modifier.size(if (isSelected) 22.dp else 20.dp), tint = color)
                })
        }
    }
}

/** The existing retained transition owns both reveal lifetime and outgoing input. */
@Composable
private fun StatusReveal(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    MotionTabContent(selected = expanded, contentKey = { it }, order = { if (it) 1 else 0 }, fillContent = false) { shown ->
        if (shown) Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderStatusLegend(section: ReaderStatusSection, budget: ExperienceBudget) {
    val status = mangaStatusColors()
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(Triple(Icons.Outlined.CheckCircle, "已确认", status.success),
            Triple(Icons.Outlined.PlayArrow, "进行", status.activity),
            Triple(Icons.Outlined.Schedule, "等待", status.waiting),
            Triple(Icons.Outlined.Pause, "暂缓", status.waiting),
            Triple(Icons.Outlined.ErrorOutline, "失败", status.error),
            Triple(Icons.Outlined.HelpOutline, "未知", status.neutral)).forEach { (icon, label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, null, Modifier.size(14.dp), tint = color)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    when (section) {
        ReaderStatusSection.PREPARATION -> {
            StatusExplanation("已备 / 目标。绿点表示原图备齐；? 待核实。页码下方依次是阶段与当前原图。")
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusToken(Icons.Outlined.KeyboardDoubleArrowRight, "前读配置", "${budget.prefetchAhead} 页")
                StatusToken(Icons.Outlined.KeyboardDoubleArrowLeft, "回看配置", "${budget.prefetchBehind} 页")
                StatusToken(Icons.Outlined.PhotoSizeSelectLarge, "预解码配置", "${budget.decodedPrefetchPages} 页")
            }
            StatusExplanation(budget.reason)
        }
        ReaderStatusSection.WORK -> {
            StatusExplanation("并发只计已获名额的任务；阶段完成不等于小图仍驻留。")
            FlowRow(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusToken(Icons.Outlined.Link, "解析并发配置", "解析 ${budget.resolveRequests}")
                StatusToken(Icons.Outlined.Download, "预读并发配置", "预读 ${budget.prefetchRequests}")
                StatusToken(Icons.AutoMirrored.Outlined.MenuBook, "当前页并发配置", "当前页 ${budget.readerRequests}")
                StatusToken(Icons.Outlined.PhotoSizeSelectLarge, "解码并发配置", "解码 ${budget.decodeRequests}")
            }
            StatusExplanation("解码含本地核验；下一话的完成数是已备原图。")
        }
        ReaderStatusSection.SOURCES -> {
            StatusIconValue(Icons.Outlined.CropFree, "单页", "对齐画面后补当前页")
            StatusIconValue(Icons.Outlined.Layers, "整章", "打开来源选择")
            StatusIconValue(Icons.Outlined.Lock, "自动恢复", "全局补页 / 切源开关")
            StatusExplanation("≈ 是附近页估算，补页前确认画面。绿点只对应已核实目标；失败与可用数量分别保留。")
            StatusToken(Icons.Outlined.Tune, "备用配置", "${budget.standbySources} 源 · ${budget.standbyPages} 页 / 源")
        }
        ReaderStatusSection.CACHE -> {
            StatusIconValue(Icons.Outlined.GridView, "离窗", "仅停未被接手的预读")
            StatusIconValue(Icons.Outlined.Timer, "过期", "从写入 / 命中起算")
            StatusIconValue(Icons.Outlined.SwapHoriz, "淘汰", "容量不足时先移旧图")
            StatusExplanation("∞ 关闭时间过期。累计回收是缓存引用；当前画面、进程内存与磁盘原图各自保留。")
            StatusExplanation("图片磁盘为共享缓存；独立源文件与离线用量见存储管理。")
        }
    }
}

private fun originalReadinessValue(pages: List<ReaderPreparationPage>): String = when {
    pages.isEmpty() -> "—"
    pages.all { it.originalPresent == null } -> "—/${pages.size}"
    else -> "${pages.count { it.originalPresent == true }}/${pages.size}"
}

private fun originalReadinessMark(pages: List<ReaderPreparationPage>, pauseReason: String?): ReaderStatusMark {
    val steps = pages.flatMap { listOf(it.resolve, it.original) }
    return when {
        pages.isEmpty() -> ReaderStatusMark.UNKNOWN
        steps.any { it.phase == ReaderPreparationPhase.FAILED } -> ReaderStatusMark.ERROR
        steps.any { it.phase == ReaderPreparationPhase.ACTIVE } -> ReaderStatusMark.ACTIVE
        pauseReason != null || steps.any { it.phase == ReaderPreparationPhase.DEFERRED } -> ReaderStatusMark.PAUSED
        steps.any { it.phase == ReaderPreparationPhase.QUEUED } -> ReaderStatusMark.QUEUED
        pages.any { it.originalPresent == null } -> ReaderStatusMark.UNKNOWN
        pages.all { it.originalPresent == true } -> ReaderStatusMark.READY
        else -> ReaderStatusMark.IDLE
    }
}

@Composable
private fun ReadinessRow(icon: ImageVector, label: String, pages: List<ReaderPreparationPage>,
    pauseReason: String?, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val ready = pages.count { it.originalPresent == true }
    val mark = originalReadinessMark(pages, pauseReason)
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = onToggle, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .background(if (expanded) colors.primary.copy(alpha = .065f) else Color.Transparent, RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {
            contentDescription = "$label，已核实 $ready / ${pages.size} 页原图；${pages.count { it.originalPresent == null }} 页待核实；${mark.description}；点击查看窗口"
            stateDescription = if (expanded) "已展开" else "已收起"
        }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
        Icon(icon, null, Modifier.size(19.dp), tint = if (expanded) colors.primary else colors.onSurfaceVariant)
        Text(label, Modifier.weight(1f).padding(horizontal = 7.dp), style = MaterialTheme.typography.labelLarge)
        StatusNumber(originalReadinessValue(pages), color = if (expanded) colors.primary else colors.onSurface)
        StatusMark(mark, Modifier.padding(start = 6.dp).size(12.dp))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
            Modifier.padding(start = 5.dp).size(16.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun NextWorkRow(next: ReaderNextChapterPreparation, maximum: Long, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    WorkDisclosure(Icons.AutoMirrored.Outlined.MenuBook, "下一话",
        next.activePages.toLong() + if (next.manifestActive) 1L else 0L,
        maximum, nextWorkSummary(next), expanded, enabled,
        next.failedPages > 0 || next.phase == ReaderNextChapterPreparationPhase.FAILED,
        next.queuedPages.toLong() + if (next.manifestQueued) 1L else 0L,
        next.originalReadyPages.toLong().takeIf { next.requestedPages > 0 }, next.deferredPages.toLong(), next.failedPages.toLong(),
        completedLabel = "原图已备", onToggle = onToggle)
}

@Composable
private fun StatusCounter(icon: ImageVector, value: Long?, label: String, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant, reserve: String? = null, iconColor: Color = color) {
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = label + " " + (value?.toString() ?: "未知") },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(13.dp), tint = iconColor)
        StatusNumber(value?.toString() ?: "—", if (reserve != null) Modifier.width(statusNumberWidth(reserve, 0.dp)) else Modifier,
            color = color)
    }
}

/** Metrics share axes across rows; wide values and larger type reduce the column count. */
@Composable
private fun StatusMetricSlots(reserve: String, modifier: Modifier = Modifier, count: Int = 4, item: @Composable (Int) -> Unit) {
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val minimumWidth = maxOf(56.dp * scale, statusNumberWidth(reserve, 24.dp))
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = when {
            count == 3 && maxWidth >= minimumWidth * 3 -> 3
            maxWidth >= minimumWidth * 4 -> 4
            maxWidth >= minimumWidth * 2 -> 2
            else -> 1
        }.coerceAtMost(count)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (first in 0 until count step columns) {
                Row(Modifier.fillMaxWidth()) {
                    repeat(columns) { offset -> Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (first + offset < count) item(first + offset)
                    } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkDisclosure(icon: ImageVector, title: String, active: Long, maximum: Long, summary: String,
    expanded: Boolean, enabled: Boolean, hasFailure: Boolean, queued: Long, completed: Long?, deferred: Long,
    failed: Long, known: Boolean = true, completedLabel: String = "阶段完成", onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val status = mangaStatusColors()
    val reserve = "8".repeat(maxOf(active, maximum, 1L).toString().length)
    MotionTextButton(onClick = onToggle, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .background(if (expanded) colors.primary.copy(alpha = .065f) else Color.Transparent, RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) {
            contentDescription = title
            stateDescription = "$summary，${if (expanded) "收起" else "展开"}详情"
        }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = if (expanded) colors.primary else colors.onSurfaceVariant)
                Text(title, Modifier.weight(1f).padding(horizontal = 7.dp), style = MaterialTheme.typography.labelLarge)
                StatusCounter(Icons.Outlined.PlayArrow, active.takeIf { known }, "实际并发",
                    color = if (known && active > 0L) status.activity else status.neutral, reserve = reserve)
                Box(Modifier.width(19.dp), contentAlignment = Alignment.CenterEnd) {
                    if (hasFailure && failed == 0L) Icon(Icons.Outlined.ErrorOutline, "准备失败", Modifier.size(13.dp), tint = colors.error)
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                    Modifier.padding(start = 6.dp).size(16.dp), tint = colors.onSurfaceVariant)
            }
            StatusMetricSlots(reserve, Modifier.padding(start = 25.dp)) { index ->
                when (index) {
                    0 -> StatusCounter(Icons.Outlined.Schedule, queued.takeIf { known }, "排队", reserve = reserve,
                        iconColor = if (known && queued > 0L) status.waiting else status.neutral)
                    1 -> StatusCounter(Icons.Outlined.Check, completed?.takeIf { known }, completedLabel, reserve = reserve,
                        iconColor = if (known && (completed ?: 0L) > 0L) status.success else status.neutral)
                    2 -> StatusCounter(Icons.Outlined.Pause, deferred.takeIf { known }, "暂缓", reserve = reserve,
                        iconColor = if (known && deferred > 0L) status.waiting else status.neutral)
                    else -> StatusCounter(Icons.Outlined.ErrorOutline, failed.takeIf { known }, "失败",
                        color = if (known && failed > 0L) status.error else status.neutral, reserve = reserve)
                }
            }
        }
    }
}

private fun workSummary(state: ReaderPreparationState, work: ReaderWork): String {
    val counts = work.counts(state)
    val reason = state.pages.map(work::step).firstNotNullOfOrNull { step -> step.reason?.takeIf {
        step.phase == ReaderPreparationPhase.QUEUED || step.phase == ReaderPreparationPhase.DEFERRED
    } }
    return when {
        state.pages.isEmpty() -> state.pauseReason ?: "等待当前阅读窗口"
        counts.failed > 0 -> "${counts.failed} 页失败" + if (counts.queued > 0) " · 排队 ${counts.queued}" else ""
        counts.queued > 0 -> "排队 ${counts.queued}" + (reason?.let { " · $it" } ?: "")
        counts.deferred > 0 -> reason ?: "暂缓 ${counts.deferred} 页"
        counts.active > 0 -> "准备中"
        counts.complete > 0 -> "${counts.complete} 页阶段已完成"
        else -> "当前未安排此项工作"
    }
}

private fun nextWorkSummary(next: ReaderNextChapterPreparation): String = when {
    next.originalReadyPages > 0 -> "原图已备 ${next.originalReadyPages}/${next.requestedPages}" +
        if (next.failedPages > 0) " · ${next.failedPages} 页失败" else ""
    next.manifestQueued -> next.reason ?: "目录请求排队"
    next.manifestActive -> "读取目录中"
    next.queuedPages > 0 -> "排队 ${next.queuedPages} 页"
    else -> next.reason ?: nextChapterPhaseLabel(next.phase)
}

private data class StandbySummary(val value: String, val detail: String, val mark: ReaderStatusMark,
    val hasTargets: Boolean = false, val progress: Float? = null)

private fun standbySummary(availability: ReaderSourceStandbyAvailability, rows: List<ReaderSourceStandby>,
    budget: ExperienceBudget): StandbySummary {
    if (budget.sourceRecovery == SourceRecoveryMode.OFF) return StandbySummary("关闭",
        "备用准备已关闭，可在体验策略中开启", ReaderStatusMark.DISABLED)
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
            availability.reason ?: "匹配来源当前不可用，请检查来源设置", ReaderStatusMark.DISABLED)
        ReaderSourceStandbyAvailabilityPhase.ELIGIBLE -> Unit
    }
    if (budget.standbySources <= 0 || budget.standbyPrefetchBytes <= 0L) return StandbySummary("受限",
        budget.standbyPauseReason ?: budget.reason, ReaderStatusMark.PAUSED)
    val targets = rows.filter { it.requestedPages > 0 }
    if (targets.isEmpty()) {
        val failed = rows.firstOrNull { it.failedPages > 0 || it.phase == ReaderSourceStandbyPhase.FAILED }
        if (failed != null) return StandbySummary("失败", (failed.reason ?: "备用来源目录准备失败") +
            if (rows.any { it.manifestActive || it.manifestQueued }) "；其他来源仍在准备或排队" else "", ReaderStatusMark.ERROR)
        if (rows.any { it.manifestActive })
            return StandbySummary("目录中", "正在读取已确认来源的本章目录，尚未建立图片准备目标",
                ReaderStatusMark.ACTIVE)
        if (rows.any { it.manifestQueued || it.phase == ReaderSourceStandbyPhase.QUEUED }) return StandbySummary("排队",
            rows.filter { it.manifestQueued || it.phase == ReaderSourceStandbyPhase.QUEUED }.firstNotNullOfOrNull { it.reason }
                ?: "已确认备用来源，目录请求正在排队", ReaderStatusMark.QUEUED)
        val deferred = rows.firstOrNull { it.phase == ReaderSourceStandbyPhase.DEFERRED }
        if (deferred != null) return StandbySummary("暂缓", deferred.reason ?: "备用准备暂缓，等待条件恢复", ReaderStatusMark.PAUSED)
        return StandbySummary("待准备", availability.reason ?: "已确认备用来源，尚未建立图片准备目标", ReaderStatusMark.IDLE)
    }
    val ready = targets.count { it.targetOriginalPresent }
    val mark = when {
        rows.any { it.failedPages > 0 || it.phase == ReaderSourceStandbyPhase.FAILED } -> ReaderStatusMark.ERROR
        rows.any { it.activePages > 0 || it.manifestActive } -> ReaderStatusMark.ACTIVE
        rows.any { it.deferredPages > 0 || it.phase == ReaderSourceStandbyPhase.DEFERRED } -> ReaderStatusMark.PAUSED
        rows.any { it.manifestQueued || it.queuedPages > 0 || it.phase == ReaderSourceStandbyPhase.QUEUED } -> ReaderStatusMark.QUEUED
        targets.any { row -> !row.targetOriginalPresent &&
            row.windowPages.firstOrNull { it.index == row.targetPageIndex }?.cacheInspectedAtMillis == null } -> ReaderStatusMark.UNKNOWN
        ready == targets.size -> ReaderStatusMark.READY
        else -> ReaderStatusMark.IDLE
    }
    val failed = rows.count { it.failedPages > 0 || it.phase == ReaderSourceStandbyPhase.FAILED }
    return StandbySummary("$ready/${targets.size}",
        "已核实 $ready / ${targets.size} 个已建立目标的备用来源" + if (failed > 0) "；另有 $failed 源有失败，可分别重试" else "", mark,
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
private fun StatusNumber(value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(value, modifier, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
        color = color, maxLines = 1, softWrap = false, textAlign = TextAlign.End)
}

@Composable
private fun StatusMicro(icon: ImageVector, value: String, label: String,
    mark: ReaderStatusMark, detail: String, progress: Float?, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val status = mangaStatusColors()
    val accent = if (selected) colors.primary else colors.onSurfaceVariant
    val progressColor = when (mark) {
        ReaderStatusMark.READY -> status.success
        ReaderStatusMark.ACTIVE -> status.activity
        ReaderStatusMark.QUEUED, ReaderStatusMark.PAUSED -> status.waiting
        ReaderStatusMark.ERROR -> status.error
        else -> status.neutral
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp).semantics(mergeDescendants = true) {
            contentDescription = label
            stateDescription = "$detail，${mark.description}，点击查看详情"
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = accent)
                StatusNumber(value, color = accent)
                StatusMark(mark, Modifier.size(9.dp))
            }
            Box(Modifier.fillMaxWidth().height(2.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (progress != null) .08f else 0f), CircleShape)) {
                if (progress != null) Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f))
                    .background(progressColor.copy(alpha = .8f), CircleShape))
            }
    }
}

private val ReaderStatusMark.description get() = when (this) {
    ReaderStatusMark.ACTIVE -> "进行中"; ReaderStatusMark.READY -> "已确认"
    ReaderStatusMark.QUEUED -> "排队"
    ReaderStatusMark.PAUSED -> "暂缓"; ReaderStatusMark.UNKNOWN -> "待核实"
    ReaderStatusMark.ERROR -> "有失败"; ReaderStatusMark.DISABLED -> "已停用"; ReaderStatusMark.IDLE -> "待命"
}

@Composable
private fun StatusMark(mark: ReaderStatusMark, modifier: Modifier = Modifier) {
    val status = mangaStatusColors()
    when (mark) {
        ReaderStatusMark.ACTIVE -> MotionBusyIndicator(modifier, color = status.activity, strokeWidth = 1.dp)
        ReaderStatusMark.READY -> Box(modifier.padding(1.5.dp).background(status.success, CircleShape))
        ReaderStatusMark.QUEUED -> Icon(Icons.Outlined.Schedule, null, modifier, tint = status.waiting)
        ReaderStatusMark.PAUSED -> Icon(Icons.Outlined.Pause, null, modifier, tint = status.waiting)
        ReaderStatusMark.UNKNOWN -> Icon(Icons.Outlined.HelpOutline, null, modifier, tint = status.neutral)
        ReaderStatusMark.ERROR -> Icon(Icons.Outlined.ErrorOutline, null, modifier, tint = status.error)
        ReaderStatusMark.DISABLED -> Icon(Icons.Outlined.Block, null, modifier, tint = status.neutral)
        ReaderStatusMark.IDLE -> Spacer(modifier)
    }
}

@Composable
private fun PreparationDetails(state: ReaderPreparationState, work: ReaderWork) {
    var showAllIssues by remember(state.sourceId, state.windowRevision, work) { mutableStateOf(false) }
    val pages = state.pages.filter { work.step(it).phase != ReaderPreparationPhase.SKIPPED }
    if (pages.isNotEmpty()) {
        PreparationPages(pages, work)
        val current = pages.filter { it.role == ReaderPreparationRole.VISIBLE }
        current.forEach { page ->
            if (work == ReaderWork.ORIGINAL) StatusToken(Icons.Outlined.Download, "第 ${page.pageIndex + 1} 页原图",
                "${page.pageIndex + 1} · ${presenceLabel(page.originalPresent)}",
                if (page.originalPresent == true) mangaStatusColors().success else mangaStatusColors().neutral)
            if (work == ReaderWork.PREVIEW) StatusToken(Icons.Outlined.PhotoSizeSelectLarge, "第 ${page.pageIndex + 1} 页小图",
                "${page.pageIndex + 1} · ${previewPresenceLabel(page.previewResident)}",
                if (page.previewResident == true) mangaStatusColors().success else mangaStatusColors().neutral)
            if (work == ReaderWork.ORIGINAL && page.joining) StatusExplanation("接续本页已开始的预读")
        }
        val received = state.pages.sumOf { it.receivedBytes }
        val expected = state.pages.takeIf { pages -> pages.all { it.expectedBytes != null } }
            ?.sumOf { it.expectedBytes ?: 0L }
        if (work == ReaderWork.ORIGINAL && received > 0L)
            StatusToken(Icons.Outlined.CloudDownload, "本窗口已接收", transferLabel(received, expected))
        val issues = state.pages.flatMap { page ->
            listOf(work.title to work.step(page)).mapNotNull { (stage, step) ->
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
private fun PreparationPages(pages: List<ReaderPreparationPage>, work: ReaderWork) {
    val pageReserve = "8".repeat((pages.maxOfOrNull { it.pageIndex.toLong() + 1L } ?: 1L).toString().length)
    val pageWidth = statusNumberWidth(pageReserve, 10.dp).coerceAtLeast(32.dp)
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pages.sortedBy { it.pageIndex }.forEach { page ->
            Column(Modifier.width(pageWidth).semantics(mergeDescendants = true) {
                contentDescription = "第 ${page.pageIndex + 1} 页，${roleLabel(page.role)}，${work.title}${phaseLabel(work.step(page).phase)}，原图${presenceLabel(page.originalPresent)}，小图${previewPresenceLabel(page.previewResident)}"
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusNumber((page.pageIndex + 1).toString(), Modifier
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha =
                        if (page.role == ReaderPreparationRole.VISIBLE) .09f else 0f), CircleShape)
                    .padding(horizontal = 4.dp, vertical = 2.dp))
                StepMark(work.step(page).phase)
                if (work == ReaderWork.ORIGINAL) PresenceMark(Icons.Outlined.Download, page.originalPresent)
                if (work == ReaderWork.PREVIEW) PresenceMark(Icons.Outlined.PhotoSizeSelectLarge, page.previewResident)
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
        ReaderPreparationPhase.QUEUED -> ReaderStatusMark.QUEUED
        ReaderPreparationPhase.SKIPPED -> ReaderStatusMark.IDLE
    }
    if (phase == ReaderPreparationPhase.COMPLETE) Icon(Icons.Outlined.Check, null, Modifier.size(10.dp), tint = mangaStatusColors().success)
    else StatusMark(mark, Modifier.size(10.dp))
}

@Composable
private fun PresenceMark(icon: ImageVector, present: Boolean?) {
    val status = mangaStatusColors()
    Icon(when (present) { true -> icon; false -> Icons.Outlined.Remove; null -> Icons.Outlined.HelpOutline },
        null, Modifier.size(10.dp), tint = if (present == true) status.success else status.neutral)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StandbyDetails(row: ReaderSourceStandby, reserve: String, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit,
    onRetry: (() -> Unit)?, onUse: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val status = mangaStatusColors()
    val active = row.activePages.toLong() + if (row.manifestActive) 1L else 0L
    val queued = row.queuedPages.toLong() + if (row.manifestQueued) 1L else 0L
    val targetInspected = row.windowPages.firstOrNull { it.index == row.targetPageIndex }?.cacheInspectedAtMillis != null
    val targetOriginal = row.targetOriginalPresent.takeIf { it || targetInspected }
    val targetPreview = row.targetPreviewResident.takeIf { it || targetInspected }
    val mark = when {
        row.phase == ReaderSourceStandbyPhase.FAILED || row.failedPages > 0 -> ReaderStatusMark.ERROR
        row.manifestActive || active > 0L -> ReaderStatusMark.ACTIVE
        row.deferredPages > 0 || row.phase == ReaderSourceStandbyPhase.DEFERRED -> ReaderStatusMark.PAUSED
        queued > 0L || row.phase == ReaderSourceStandbyPhase.QUEUED -> ReaderStatusMark.QUEUED
        row.targetOriginalPresent -> ReaderStatusMark.READY
        row.requestedPages > 0 && targetOriginal == null -> ReaderStatusMark.UNKNOWN
        row.phase == ReaderSourceStandbyPhase.READY -> ReaderStatusMark.IDLE
        else -> standbyMark(row.phase)
    }
    val original = when {
        row.requestedPages <= 0 -> "—"
        row.cacheInspectedAtMillis == null && row.preparedPages == 0 -> "—/${row.requestedPages}"
        else -> "${row.preparedPages}/${row.requestedPages}"
    }
    MotionTextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .background(if (expanded) colors.primary.copy(alpha = .065f) else Color.Transparent, RoundedCornerShape(12.dp))
        .semantics(mergeDescendants = true) {
            contentDescription = row.sourceName
            stateDescription = "${mark.description}，${standbyPhaseLabel(row.phase)}，目标原图${presenceLabel(targetOriginal)}；${if (expanded) "收起" else "展开"}来源详情"
        }, enabled = enabled, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BodySourceMark(row.sourceId, row.sourceName, Modifier.size(18.dp))
                Text(row.sourceName, Modifier.weight(1f).padding(horizontal = 7.dp), style = MaterialTheme.typography.labelLarge,
                    color = if (expanded) colors.primary else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusNumber(original, Modifier.width(statusNumberWidth("$reserve/$reserve", 0.dp))
                    .semantics { contentDescription = "已核实原图 $original" },
                    color = if (expanded) colors.primary else colors.onSurface)
                Spacer(Modifier.width(6.dp))
                StatusMark(mark, Modifier.size(12.dp))
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                    Modifier.padding(start = 6.dp).size(16.dp), tint = colors.onSurfaceVariant)
            }
            StatusMetricSlots(reserve, Modifier.padding(start = 25.dp)) { index ->
                when (index) {
                    0 -> StatusCounter(Icons.Outlined.PhotoSizeSelectLarge,
                        row.previewResidentPages.toLong().takeIf { row.cacheInspectedAtMillis != null || it > 0L },
                        "小图实测驻留", reserve = reserve,
                        iconColor = if (row.previewResidentPages > 0) status.success else status.neutral)
                    1 -> StatusCounter(Icons.Outlined.PlayArrow, active, "实际并发",
                        color = if (active > 0) status.activity else status.neutral, reserve = reserve)
                    2 -> StatusCounter(Icons.Outlined.Schedule, queued, "排队", reserve = reserve,
                        iconColor = if (queued > 0L) status.waiting else status.neutral)
                    else -> StatusCounter(Icons.Outlined.ErrorOutline, row.failedPages.toLong(), "失败页",
                        color = if (row.failedPages > 0) status.error else status.neutral, reserve = reserve)
                }
            }
        }
    }
    StatusReveal(expanded) {
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusToken(Icons.Outlined.FindInPage, if (row.approximate) "附近估算页" else "目标页",
                row.targetPageIndex?.let { (if (row.approximate) "≈ " else "") + "${it + 1} / ${row.totalPages ?: "?"}" } ?: "—")
            StatusToken(Icons.Outlined.Download,
                "目标原图", presenceLabel(targetOriginal), if (targetOriginal == true) status.success else status.neutral)
            StatusToken(Icons.Outlined.PhotoSizeSelectLarge,
                "目标小图", previewPresenceLabel(targetPreview), if (targetPreview == true) status.success else status.neutral)
        }
        if (onRetry != null || onUse != null) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (onRetry != null) MotionIconButton(onRetry, enabled = enabled, modifier = Modifier.size(44.dp), tooltip = "重试此来源") {
                Icon(Icons.Outlined.Refresh, "重试${row.sourceName}", Modifier.size(19.dp), tint = colors.primary)
            }
            if (onUse != null) MotionIconButton(onUse, enabled = enabled, modifier = Modifier.size(44.dp),
                tooltip = "对齐附近备用页，确认画面后仅替换当前页") {
                Icon(Icons.Outlined.CropFree, "与${row.sourceName}对齐补当前页", Modifier.size(19.dp), tint = colors.primary)
            }
        }
        if (onUse != null) StatusExplanation("先确认画面，再补当前页。")
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusCounter(Icons.Outlined.Link, row.resolvingPages.toLong(), "解析中",
                iconColor = if (row.resolvingPages > 0) status.activity else status.neutral)
            StatusCounter(Icons.Outlined.Download, row.downloadingPages.toLong(), "原图传输中",
                iconColor = if (row.downloadingPages > 0) status.activity else status.neutral)
            StatusCounter(Icons.Outlined.PhotoSizeSelectLarge, row.checkingPages.toLong(), "本地核验中",
                iconColor = if (row.checkingPages > 0) status.activity else status.neutral)
            if (row.deferredPages > 0) StatusCounter(Icons.Outlined.Pause, row.deferredPages.toLong(), "暂缓", iconColor = status.waiting)
        }
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.receivedBytes > 0L) StatusToken(Icons.Outlined.CloudDownload, "已接收", transferLabel(row.receivedBytes, row.expectedBytes))
            if (row.cachedOriginalBytes > 0L) StatusToken(Icons.Outlined.Storage, "已存原图", experienceCacheSize(row.cachedOriginalBytes))
            if (row.settlingPages > 0) StatusToken(Icons.Outlined.HourglassEmpty, "旧窗收尾", row.settlingPages.toString(), status.waiting)
        }
        val issues = row.windowPages.filter { it.phase == ReaderSourceStandbyPhase.FAILED || it.phase == ReaderSourceStandbyPhase.DEFERRED }
            .filter { it.reason != null }.groupBy { it.phase to it.reason }
        if (issues.keys.none { it.second == row.reason }) row.reason?.let {
            StatusExplanation(it, error = row.phase == ReaderSourceStandbyPhase.FAILED)
        }
        if (row.requestedPages <= 0 && row.reason == null)
            StatusExplanation(when {
                row.manifestActive -> "正在读取本章目录"
                row.manifestQueued -> "目录请求排队中"
                row.phase == ReaderSourceStandbyPhase.MANIFEST_READY -> "目录已备，等待建立图片目标"
                else -> standbyPhaseLabel(row.phase)
            }, error = row.phase == ReaderSourceStandbyPhase.FAILED)
        issues.forEach { (key, pages) ->
            StatusExplanation("第 ${pageRanges(pages.map { it.index })} 页 · ${key.second}", error = key.first == ReaderSourceStandbyPhase.FAILED)
        }
        val pageReserve = "8".repeat((row.windowPages.maxOfOrNull { it.index.toLong() + 1L } ?: 1L).toString().length)
        val pageWidth = statusNumberWidth(pageReserve, 10.dp).coerceAtLeast(32.dp)
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            row.windowPages.forEach { page ->
                val originalPresent = page.originalPresent.takeIf { it || page.cacheInspectedAtMillis != null }
                val previewResident = page.previewResident.takeIf { it || page.cacheInspectedAtMillis != null }
                Column(Modifier.width(pageWidth).semantics(mergeDescendants = true) {
                    contentDescription = "估算来源第 ${page.index + 1} 页，${standbyPhaseLabel(page.phase)}，原图${presenceLabel(originalPresent)}，小图${previewPresenceLabel(previewResident)}"
                }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    StatusNumber((page.index + 1).toString())
                    StatusMark(standbyMark(page.phase), Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        PresenceMark(Icons.Outlined.Download, originalPresent)
                        PresenceMark(Icons.Outlined.PhotoSizeSelectLarge, previewResident)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun NextChapterDetails(next: ReaderNextChapterPreparation) {
    val status = mangaStatusColors()
    val failed = next.failedPages > 0 || next.phase == ReaderNextChapterPreparationPhase.FAILED
    val active = next.manifestActive || next.activePages > 0
    val queued = next.manifestQueued || next.queuedPages > 0 || next.phase == ReaderNextChapterPreparationPhase.QUEUED
    val deferred = next.deferredPages > 0 || next.phase == ReaderNextChapterPreparationPhase.DEFERRED
    val ready = next.requestedPages > 0 && next.originalReadyPages == next.requestedPages
    val stateIcon = when {
        failed -> Icons.Outlined.ErrorOutline; active -> Icons.Outlined.PlayArrow
        deferred -> Icons.Outlined.Pause; queued -> Icons.Outlined.Schedule
        ready -> Icons.Outlined.CheckCircle; else -> Icons.Outlined.HelpOutline
    }
    val stateColor = when {
        failed -> status.error; active -> status.activity; deferred || queued -> status.waiting
        ready -> status.success; else -> status.neutral
    }
    Text(next.chapterTitle, Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusToken(stateIcon, "下一话状态", when {
            next.failedPages > 0 -> "${next.failedPages} 页失败"
            failed -> "目录或准备失败"
            next.manifestActive -> "读取目录中"; next.manifestQueued -> "目录请求排队"
            else -> nextChapterPhaseLabel(next.phase)
        }, stateColor)
        StatusToken(Icons.Outlined.Download, "下一话已备原图",
            if (next.requestedPages > 0) "${next.originalReadyPages}/${next.requestedPages}" else "—",
            if (next.originalReadyPages > 0) status.success else status.neutral)
        next.receivedBytes?.takeIf { it > 0L }?.let { StatusToken(Icons.Outlined.CloudDownload, "已接收", experienceCacheSize(it)) }
    }
    if (next.activePages > 0) FlowRow(Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusCounter(Icons.Outlined.Link, next.resolvingPages.toLong(), "解析中",
            iconColor = if (next.resolvingPages > 0) status.activity else status.neutral)
        StatusCounter(Icons.Outlined.Download, next.downloadingPages.toLong(), "原图传输中",
            iconColor = if (next.downloadingPages > 0) status.activity else status.neutral)
        StatusCounter(Icons.Outlined.PhotoSizeSelectLarge, next.checkingPages.toLong(), "本地核验中",
            iconColor = if (next.checkingPages > 0) status.activity else status.neutral)
    }
    next.reason?.let { StatusExplanation(it, error = next.phase == ReaderNextChapterPreparationPhase.FAILED) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CacheOverview(store: ExperiencePolicyStore, expanded: ReaderWork?, enabled: Boolean,
    memoryIdleMinutes: Int, imageRetentionHours: Int, memoryMiB: Int?, diskMiB: Int?,
    onToggle: (ReaderWork) -> Unit) {
    val cache = projectedCacheUsage(store)
    val memory = projectedMemoryLifecycle(store)
    val scope = rememberCoroutineScope()
    var clearing by remember(store) { mutableStateOf(false) }
    var clearResult by remember(store) { mutableStateOf<String?>(null) }
    var clearError by remember(store) { mutableStateOf<String?>(null) }
    val status = mangaStatusColors()
    CacheWorkRow(Icons.Outlined.Memory, "解码内存", cache.memoryBytes, cache.memoryLimit,
        false, expanded == ReaderWork.MEMORY, enabled) { onToggle(ReaderWork.MEMORY) }
    CacheMemoryLifecycle(memory, memoryIdleMinutes, enabled) { onToggle(ReaderWork.MEMORY) }
    StatusReveal(expanded == ReaderWork.MEMORY) {
        val idle = memory?.idleMinutes
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusToken(Icons.Outlined.GridView, "当前解码条目", memory?.entries?.let { "$it 项" } ?: "—")
            StatusToken(Icons.Outlined.Tune, "用户内存上限配置", memoryMiB?.let { "$it MB" } ?: "自动")
            StatusToken(if (idle == 0) Icons.Outlined.TimerOff else Icons.Outlined.Schedule, "下次闲置到期",
                when {
                    idle == 0 -> "∞"
                    memory == null -> "—"
                    else -> memory.nextExpiryAtMillis?.let { cacheElapsedLabel((it - memory.sampledAtMillis).coerceAtLeast(0L)) }
                        ?: "无待清"
                })
        }
        StatusExplanation(when (idle) {
            null -> "离窗不即删 · 等待缓存采样"
            0 -> "离窗不即删 · 按容量淘汰"
            else -> "离窗不即删 · 闲置到期或容量不足时回收"
        })
        val reclaimReserve = listOf(memory?.evictedBytes, memory?.expiredBytes, memory?.clearedBytes)
            .map { it?.let(::experienceCacheSize) ?: "—" }
            .plus(listOf(memory?.evictedEntries, memory?.expiredEntries, memory?.clearedEntries).map { it?.let { count -> "$count 项" } ?: "—" })
            .maxByOrNull { it.length } ?: "—"
        StatusMetricSlots(reclaimReserve, Modifier.padding(vertical = 6.dp), count = 3) { index ->
            when (index) {
                0 -> CacheReclaimMetric(Icons.Outlined.SwapHoriz, "淘汰", memory?.evictedBytes, memory?.evictedEntries)
                1 -> CacheReclaimMetric(Icons.Outlined.Timer, "到期", memory?.expiredBytes, memory?.expiredEntries)
                else -> CacheReclaimMetric(Icons.Outlined.CleaningServices, "清空", memory?.clearedBytes, memory?.clearedEntries)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                StatusToken(Icons.Outlined.History, "最近回收", memory?.let { sample -> sample.lastReclaimedAtMillis?.let {
                    cacheElapsedLabel((sample.sampledAtMillis - it).coerceAtLeast(0L)) + "前"
                } ?: "尚无" } ?: "—")
            }
            clearResult?.let { result ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "清理完成，已移出可复用解码缓存 $result" }) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = status.success)
                    Text(result, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            MotionIconButton(onClick = {
                if (enabled && !clearing) {
                    clearing = true; clearResult = null; clearError = null
                    scope.launch {
                        try { clearResult = "−" + experienceCacheSize(store.clearDecodedMemory().await()) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { clearError = "未能释放，请重试" }
                        finally { clearing = false }
                    }
                }
            }, enabled = enabled && !clearing && (cache.memoryBytes ?: 0L) > 0L, modifier = Modifier.size(44.dp),
                tooltip = "释放可复用解码缓存，保留当前画面与磁盘原图") {
                if (clearing) MotionBusyIndicator(Modifier.size(18.dp), color = status.activity, strokeWidth = 1.5.dp)
                else Icon(Icons.Outlined.CleaningServices, "释放可复用解码缓存", Modifier.size(19.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        clearError?.let { StatusExplanation(it, error = true) }
    }
    CacheWorkRow(Icons.Outlined.Storage, "图片磁盘", cache.diskBytes, cache.diskLimit,
        cache.pending, expanded == ReaderWork.DISK, enabled) { onToggle(ReaderWork.DISK) }
    StatusReveal(expanded == ReaderWork.DISK) {
        FlowRow(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusToken(Icons.Outlined.Tune, "用户磁盘上限配置", diskMiB?.let { "$it MB" } ?: "自动")
            StatusToken(Icons.Outlined.Timer, "原图保留", if (imageRetentionHours == 0) "按空间回收" else "$imageRetentionHours h")
        }
        if (cache.pending) StatusExplanation("容量待调整；上方仍是当前实际容量，在用图片释放后再应用。")
    }
    cache.error?.let { StatusExplanation(it, error = true) }
}

@Composable
private fun CacheReclaimMetric(icon: ImageVector, label: String, bytes: Long?, entries: Long?) {
    val status = mangaStatusColors()
    Column(Modifier.semantics(mergeDescendants = true) {
        contentDescription = "本次缓存累计$label，${entries?.let { "$it 项" } ?: "条目未知"}，${bytes?.let(::experienceCacheSize) ?: "字节未知"}"
    }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(14.dp),
                tint = if ((bytes ?: 0L) > 0L || (entries ?: 0L) > 0L) status.success else status.neutral)
            Text(label, style = MaterialTheme.typography.labelSmall, color = status.neutral)
        }
        StatusNumber(bytes?.let(::experienceCacheSize) ?: "—")
        Text(entries?.let { "$it 项" } ?: "—", style = MaterialTheme.typography.labelSmall, color = status.neutral)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CacheMemoryLifecycle(memory: ExperienceMemoryCacheUsage?, configuredIdleMinutes: Int,
    enabled: Boolean, onInspect: () -> Unit) {
    val idle = memory?.idleMinutes
    val expiry = when { idle == null -> "—"; idle > 0 -> "$idle min"; else -> "∞" }
    val released = memory?.let { sample ->
        listOf(sample.expiredBytes, sample.evictedBytes, sample.clearedBytes).fold(0L) { total, bytes ->
            total + minOf(bytes.coerceAtLeast(0L), Long.MAX_VALUE - total)
        }
    }
    val colors = MaterialTheme.colorScheme
    val status = mangaStatusColors()
    MotionTextButton(onClick = onInspect, enabled = enabled,
        contentPadding = PaddingValues(start = 30.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics(mergeDescendants = true) {
            contentDescription = (when {
                idle == null -> "内存缓存尚未采样；配置为" + if (configuredIdleMinutes == 0) "仅按容量淘汰" else "闲置 $configuredIdleMinutes 分钟过期"
                idle == 0 -> "闲置过期关闭，按容量淘汰"
                else -> "闲置 $idle 分钟后过期"
            }) +
                "；当前解码缓存条目 ${memory?.entries ?: "未知"}；本次到期、容量与清空累计回收 " +
                (released?.let(::experienceCacheSize) ?: "未知") + "；点击查看回收详情"
        }) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(when { idle == null -> Icons.Outlined.HelpOutline; idle > 0 -> Icons.Outlined.Timer; else -> Icons.Outlined.TimerOff },
                    null, Modifier.size(15.dp), tint = status.neutral)
                Text("闲置 $expiry", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Cached, null, Modifier.size(15.dp), tint = if ((released ?: 0L) > 0L) status.success else status.neutral)
                Text("累计 " + (released?.let(::experienceCacheSize) ?: "—"), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant)
            }
        }
        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}

private fun cacheElapsedLabel(millis: Long): String = when {
    millis < 60_000L -> "${millis / 1_000L}s"
    millis < 3_600_000L -> "${millis / 60_000L}min"
    else -> "${millis / 3_600_000L}h"
}

@Composable
private fun projectedMemoryLifecycle(store: ExperiencePolicyStore): ExperienceMemoryCacheUsage? {
    val flow = remember(store) { store.usage.map { it.memoryCache }.distinctUntilChanged() }
    val memory by flow.collectAsStateWithLifecycle(initialValue = store.usage.value.memoryCache)
    return memory
}

private data class ReaderCacheUsage(val memoryBytes: Long?, val memoryLimit: Long?, val diskBytes: Long?,
    val diskLimit: Long?, val pending: Boolean, val error: String?, val recentMemoryRelease: Boolean)
private fun ExperienceUsage.cacheProjection() = ReaderCacheUsage(memoryBytes, memoryLimit, diskBytes, diskLimit,
    diskResizePending, cacheError, memoryCache?.let { sample -> sample.lastReclaimedAtMillis?.let {
        sample.sampledAtMillis - it in 0L..5_000L
    } } == true)

@Composable
private fun projectedCacheUsage(store: ExperiencePolicyStore): ReaderCacheUsage {
    // Admission/transfer counters never invalidate either cache-only subcomposition.
    val flow = remember(store) { store.usage.map { it.cacheProjection() }.distinctUntilChanged() }
    val cache by flow.collectAsStateWithLifecycle(initialValue = store.usage.value.cacheProjection())
    return cache
}

private data class CacheState(val label: String, val mark: ReaderStatusMark, val occupancy: Float?)
private fun cacheState(used: Long?, limit: Long?, pending: Boolean = false): CacheState {
    val occupancy = if (used != null && used >= 0L && limit != null && limit > 0L)
        (used.toDouble() / limit).coerceIn(0.0, 1.0).toFloat() else null
    return when {
        pending -> CacheState("待调整", ReaderStatusMark.QUEUED, occupancy)
        limit == 0L -> CacheState("已停用", ReaderStatusMark.DISABLED, null)
        used == null || limit == null || used < 0L || limit < 0L -> CacheState("用量未知", ReaderStatusMark.UNKNOWN, null)
        used >= limit -> CacheState("已达容量", ReaderStatusMark.IDLE, occupancy)
        used == 0L -> CacheState("空缓存", ReaderStatusMark.IDLE, occupancy)
        else -> CacheState("有缓存", ReaderStatusMark.IDLE, occupancy)
    }
}

@Composable
private fun ReaderCacheEntry(store: ExperiencePolicyStore, selected: Boolean) {
    val cache = projectedCacheUsage(store)
    val memory = cacheState(cache.memoryBytes, cache.memoryLimit)
    val disk = cacheState(cache.diskBytes, cache.diskLimit, cache.pending)
    val status = mangaStatusColors()
    val accent = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.semantics(mergeDescendants = true) {
        contentDescription = "共享图片缓存"
        stateDescription = "解码内存${statusCacheUsage(cache.memoryBytes, cache.memoryLimit)}，${memory.label}；" +
            (if (cache.recentMemoryRelease) "近期有解码缓存回收；" else "") +
            "图片磁盘${statusCacheUsage(cache.diskBytes, cache.diskLimit)}，${disk.label}；${cache.error ?: "点击查看"}"
    }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Icons.Outlined.Memory to memory, Icons.Outlined.Storage to disk).forEachIndexed { index, (icon, state) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(21.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(15.dp), tint = accent)
                        if (state.mark == ReaderStatusMark.UNKNOWN || state.mark == ReaderStatusMark.QUEUED || state.mark == ReaderStatusMark.DISABLED)
                            StatusMark(state.mark, Modifier.align(Alignment.TopEnd).size(8.dp))
                        else if (index == 0 && cache.recentMemoryRelease) Icon(Icons.Outlined.Cached,
                            "近期有解码缓存回收", Modifier.align(Alignment.TopEnd).size(9.dp),
                            tint = status.success)
                    }
                    CacheOccupancy(state.occupancy, Modifier.width(22.dp).height(2.dp), accent)
                }
            }
            Box(Modifier.size(9.dp)) {
                if (cache.error != null) Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(9.dp), tint = status.error)
            }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CacheWorkRow(icon: ImageVector, title: String, used: Long?, limit: Long?, pending: Boolean,
    expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val state = cacheState(used, limit, pending)
    val colors = MaterialTheme.colorScheme
    val accent = if (expanded) colors.primary else colors.onSurfaceVariant
    MotionTextButton(onClick = onToggle, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .background(if (expanded) colors.primary.copy(alpha = .065f) else Color.Transparent, RoundedCornerShape(12.dp))
        .semantics(mergeDescendants = true) {
            contentDescription = "$title，应用共享，已用与当前容量${statusCacheUsage(used, limit)}，${state.label}"
            stateDescription = if (expanded) "已展开" else "已收起"
        }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(icon, null, Modifier.size(18.dp), tint = accent)
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    StatusNumber(used?.let(::experienceCacheSize) ?: "—")
                    Text("/ " + (limit?.let(::experienceCacheSize) ?: "—"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(12.dp)) {
                    if (state.mark == ReaderStatusMark.UNKNOWN || state.mark == ReaderStatusMark.QUEUED || state.mark == ReaderStatusMark.DISABLED)
                        StatusMark(state.mark, Modifier.size(12.dp))
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CacheOccupancy(state.occupancy, Modifier.padding(start = 24.dp, end = 23.dp).fillMaxWidth().height(2.dp), accent)
        }
    }
}

@Composable
private fun CacheOccupancy(occupancy: Float?, modifier: Modifier, color: Color) {
    Box(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (occupancy != null) .08f else 0f), CircleShape)) {
        if (occupancy != null) Box(Modifier.fillMaxHeight().fillMaxWidth(occupancy)
            .background(color.copy(alpha = .65f), CircleShape))
    }
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
    ReaderSourceStandbyPhase.QUEUED -> ReaderStatusMark.QUEUED
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
private fun StatusContext(page: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
        StatusToken(Icons.AutoMirrored.Outlined.MenuBook, "当前阅读页", page)
    }
}

/** Compact facts reuse the existing icon/value language; explanations stay in scoped Info. */
@Composable
private fun StatusToken(icon: ImageVector, label: String, value: String,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$label，$value" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(15.dp), tint = iconColor)
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusIconValue(icon: ImageVector, label: String, value: String,
    iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {
        contentDescription = "$label，$value"
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(17.dp), tint = iconColor)
        Text(label, Modifier.weight(.7f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusExplanation(value: String, error: Boolean = false) {
    Text(value, Modifier.fillMaxWidth().padding(vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun statusCacheUsage(used: Long?, limit: Long?) =
    (used?.let(::experienceCacheSize) ?: "未知") + " / " + (limit?.let(::experienceCacheSize) ?: "未知")
