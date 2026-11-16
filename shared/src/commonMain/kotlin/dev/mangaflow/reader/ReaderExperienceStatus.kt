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
import androidx.compose.ui.text.style.TextOverflow
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
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.ExperienceUsage
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
    val placement = custom.readerStatusPlacement
    val shown = visible && placement != ReaderStatusPlacement.HIDDEN
    var selected by remember(sessionKey) { mutableStateOf<ReaderStatusSection?>(null) }
    var expandedWork by remember(sessionKey) { mutableStateOf<ReaderWork?>(null) }
    var popoverPresent by remember { mutableStateOf(false) }
    var pendingPolicy by remember(sessionKey) { mutableStateOf(false) }
    var pendingSources by remember(sessionKey) { mutableStateOf(false) }
    var pendingStandby by remember(sessionKey) { mutableStateOf<Pair<StandbyAction, String>?>(null) }
    var showPolicy by remember(sessionKey) { mutableStateOf(false) }
    var showScope by remember(sessionKey) { mutableStateOf(false) }
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
                    FlowRow(Modifier.padding(horizontal = 2.dp), maxItemsInEachRow = 4) {
                        fun inspect(section: ReaderStatusSection) {
                            selected = section
                            expandedWork = when (section) { ReaderStatusSection.SOURCES -> ReaderWork.SOURCES
                                ReaderStatusSection.CACHE -> ReaderWork.MEMORY; else -> null }
                            expandedSource = null
                            showScope = false
                        }
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
                        ReaderCacheEntry(store, shown) { inspect(ReaderStatusSection.CACHE) }
                    }
                }
            }
            CompactGlassPopover(visible = shown && selected != null, backdrop = backdrop,
                aboveAnchor = !top, alignToStart = true, maximumWidth = 344.dp,
                onDismiss = { selected = null }, onPresenceChange = { popoverPresent = it }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("阅读工作", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        MotionIconButton(onClick = { selected = null; pendingPolicy = true },
                            modifier = Modifier.size(44.dp), enabled = selected != null, tooltip = "配置体验策略") {
                            Icon(Icons.Outlined.Tune, "配置体验策略", Modifier.size(18.dp))
                        }
                        MotionIconButton(onClick = { selected = null }, modifier = Modifier.size(44.dp),
                            enabled = selected != null, tooltip = "关闭状态详情") {
                            Icon(Icons.Outlined.Close, "关闭状态详情", Modifier.size(18.dp))
                        }
                    }
                    StatusValue("当前页", if (preparation.lastVisible >= preparation.firstVisible && preparation.pageCount > 0)
                        "${pageRange(preparation.firstVisible, preparation.lastVisible)} / ${preparation.pageCount}" else "等待可见页")
                    StatusValue("前方已备", if (preparation.aheadTarget > 0)
                        "${preparation.aheadReady} / ${preparation.aheadTarget} 页" else aheadDetail)
                    fun toggle(work: ReaderWork) { expandedWork = if (expandedWork == work) null else work }
                    listOf(ReaderWork.RESOLVE, ReaderWork.ORIGINAL, ReaderWork.PREVIEW).forEach { work ->
                        val counts = work.counts(preparation)
                        WorkDisclosure(work.icon, work.title, counts.active.toLong(), preparation.pages.size.toLong(),
                            (if (work == ReaderWork.PREVIEW) "解码与核验 · " else "") + workSummary(preparation, work),
                            expandedWork == work, selected != null, counts.failed > 0) { toggle(work) }
                        if (expandedWork == work) PreparationDetails(preparation, work, budget)
                    }
                    val standbyActive = standbys.sumOf { it.activePages.toLong() + if (it.manifestActive) 1L else 0L }
                    WorkDisclosure(Icons.Outlined.Layers, "多源备用", standbyActive,
                        standbys.sumOf { it.requestedPages.toLong() + 1L }, standbyWorkSummary(standbys, sourceStatus),
                        expandedWork == ReaderWork.SOURCES, selected != null,
                        standbys.any { it.failedPages > 0 || it.phase == ReaderSourceStandbyPhase.FAILED }) { toggle(ReaderWork.SOURCES) }
                    if (expandedWork == ReaderWork.SOURCES) {
                            StatusExplanation(sourceStatus.detail, error = sourceStatus.mark == ReaderStatusMark.ERROR)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (onAlignPage != null) MotionTextButton(onClick = {
                                    selected = null; pendingStandby = StandbyAction.ALIGN to ""
                                }, enabled = selected != null, modifier = Modifier.heightIn(min = 44.dp)) {
                                    Icon(Icons.Outlined.CropFree, null, Modifier.size(16.dp))
                                    Text("对齐", Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall)
                                }
                                if (repairBusy) MotionBusyIndicator(Modifier.padding(horizontal = 6.dp).size(14.dp), strokeWidth = 1.5.dp)
                                if (repairedPages > 0) Text("补 $repairedPages", Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                else Spacer(Modifier.weight(1f))
                                if (onUndoPage != null) MotionIconButton(onClick = {
                                    selected = null; pendingStandby = StandbyAction.UNDO_PAGE to ""
                                }, enabled = selected != null, tooltip = "撤回当前单页替换") {
                                    Icon(Icons.Outlined.RestorePage, "撤回当前单页替换", Modifier.size(18.dp))
                                }
                                if (onUndoSourceSwitch != null) MotionIconButton(onClick = {
                                    selected = null; pendingStandby = StandbyAction.UNDO_SOURCE to ""
                                }, enabled = selected != null, tooltip = "撤销整章切源，回到原来源原页") {
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
                                }, enabled = selected != null && !changingSourceLock, tooltip = if (custom.recovery.sourceLocked)
                                    "解除来源锁定，允许按策略自动补页和切源" else "锁定来源，暂停自动补页和切源") {
                                    if (changingSourceLock) MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                                    else Icon(if (custom.recovery.sourceLocked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                        if (custom.recovery.sourceLocked) "已锁定来源" else "来源未锁定", Modifier.size(18.dp),
                                        tint = if (custom.recovery.sourceLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            sourceLockError?.let { StatusExplanation(it, error = true) }
                            repairReason?.let { StatusExplanation(it) }
                            standbys.forEach { row ->
                                StandbyDetails(row, expandedSource == row.variantId, selected != null,
                                    onToggle = { expandedSource = if (expandedSource == row.variantId) null else row.variantId },
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
                            }
                            if (alternativeCount > 0 && onOpenSources != null) MotionTextButton(
                                onClick = { selected = null; pendingSources = true }, enabled = selected != null,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
                                Icon(Icons.Outlined.Layers, null, Modifier.size(16.dp))
                                Text("整章来源", Modifier.padding(start = 6.dp).weight(1f),
                                    style = MaterialTheme.typography.labelSmall)
                                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
                            }
                    }
                    nextChapterPreparation?.let { next ->
                        WorkDisclosure(Icons.AutoMirrored.Outlined.MenuBook, "下一话",
                            next.activePages.toLong() + if (next.manifestActive) 1L else 0L,
                            next.requestedPages.toLong() + 1L, nextWorkSummary(next), expandedWork == ReaderWork.NEXT,
                            selected != null, next.failedPages > 0 || next.phase == ReaderNextChapterPreparationPhase.FAILED) { toggle(ReaderWork.NEXT) }
                        if (expandedWork == ReaderWork.NEXT) NextChapterDetails(next)
                    }
                    CacheOverview(store, expandedWork, selected != null, custom.contentCache.memoryIdleMinutes,
                        custom.contentCache.imageRetentionHours, custom.memoryMiB, custom.diskMiB, onToggle = ::toggle)
                    MotionTextButton(onClick = { showScope = !showScope }, enabled = selected != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(15.dp))
                        Text("范围与策略", Modifier.padding(start = 6.dp).weight(1f), style = MaterialTheme.typography.labelSmall)
                        Icon(if (showScope) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                    }
                    if (showScope) {
                        StatusExplanation("并发数是本次阅读工作实际进入处理的任务或请求数，不是系统线程数。各项共享资源名额；小图准备含解码与本地核验。")
                        StatusExplanation("缓存行统计共享解码内存与网络图片磁盘用量。备用页按附近位置准备；确认画面后只替换这一页。自动补页只复用仍有效的精确对齐，多页真实失败后才按配置考虑整章切源。")
                        StatusValue("配置窗口", "前 ${budget.prefetchAhead} · 后 ${budget.prefetchBehind} 页")
                        StatusValue("备用配置", "${budget.standbySources} 源 · 每源 ${budget.standbyPages} 页")
                        StatusExplanation(budget.reason)
                    }
                }
            }
        }
    }
    ExperienceSettingsDialog(visible = showPolicy && visible, backdrop = backdrop,
        onDismiss = { showPolicy = false })
}

private enum class ReaderStatusSection { PREPARATION, SOURCES, CACHE }
private enum class StandbyAction { RETRY, USE, ALIGN, UNDO_PAGE, UNDO_SOURCE }
private enum class ReaderWork(val title: String, val icon: ImageVector) {
    RESOLVE("地址解析", Icons.Outlined.Link), ORIGINAL("原图准备", Icons.Outlined.Download),
    PREVIEW("小图准备", Icons.Outlined.Memory), SOURCES("多源备用", Icons.Outlined.Layers),
    NEXT("下一话", Icons.AutoMirrored.Outlined.MenuBook), MEMORY("解码内存", Icons.Outlined.Memory), DISK("图片磁盘", Icons.Outlined.Storage);
    fun counts(state: ReaderPreparationState) = when (this) {
        RESOLVE -> state.resolving; ORIGINAL -> state.originals; else -> state.decoding
    }
    fun step(page: ReaderPreparationPage) = when (this) {
        RESOLVE -> page.resolve; ORIGINAL -> page.original; else -> page.decode
    }
}
private enum class ReaderStatusMark { ACTIVE, READY, PAUSED, UNKNOWN, ERROR, IDLE }

@Composable
private fun WorkDisclosure(icon: ImageVector, title: String, active: Long, maximum: Long, summary: String,
    expanded: Boolean, enabled: Boolean, hasFailure: Boolean, onToggle: () -> Unit) {
    val numberWidth = statusNumberWidth("并发 ${"8".repeat(maxOf(active, maximum, 1L).toString().length)}", 2.dp)
    MotionTextButton(onClick = onToggle, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics(mergeDescendants = true) {
            contentDescription = "$title，实际并发 $active，$summary，${if (expanded) "收起" else "展开"}详情"
        }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
        Icon(icon, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurface)
        Column(Modifier.weight(1f).padding(horizontal = 7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (hasFailure) Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.error)
                Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        StatusNumber("并发 $active", Modifier.width(numberWidth))
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
            Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun standbyWorkSummary(rows: List<ReaderSourceStandby>, status: StandbySummary): String {
    val targets = rows.filter { it.requestedPages > 0 }
    val ready = targets.count { it.targetOriginalPresent }
    val failed = rows.count { it.failedPages > 0 || it.phase == ReaderSourceStandbyPhase.FAILED }
    val queued = rows.count { it.manifestQueued } + rows.sumOf { it.queuedPages.toLong() }
    if (ready > 0) return "目标原图已备 $ready/${targets.size} 源" + if (failed > 0) " · $failed 源有失败" else ""
    if (queued > 0) return "${status.value} · 排队 $queued 项" + if (failed > 0) " · $failed 源有失败" else ""
    return status.detail
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
        if (rows.any { it.manifestActive })
            return StandbySummary("目录中", "正在读取已确认来源的本章目录，尚未建立图片准备目标",
                ReaderStatusMark.ACTIVE)
        if (rows.any { it.manifestQueued || it.phase == ReaderSourceStandbyPhase.QUEUED }) return StandbySummary("排队",
            rows.filter { it.manifestQueued || it.phase == ReaderSourceStandbyPhase.QUEUED }.firstNotNullOfOrNull { it.reason }
                ?: "已确认备用来源，目录请求正在排队", if (failed != null) ReaderStatusMark.ERROR else ReaderStatusMark.UNKNOWN)
        if (failed != null) return StandbySummary("失败", failed.reason ?: "备用来源目录准备失败", ReaderStatusMark.ERROR)
        val deferred = rows.firstOrNull { it.phase == ReaderSourceStandbyPhase.DEFERRED }
        if (deferred != null) return StandbySummary("暂缓", deferred.reason ?: "备用准备暂缓，等待条件恢复", ReaderStatusMark.PAUSED)
        return StandbySummary("待准备", availability.reason ?: "已确认备用来源，尚未建立图片准备目标", ReaderStatusMark.IDLE)
    }
    val ready = targets.count { it.targetOriginalPresent }
    val mark = when {
        ready > 0 -> ReaderStatusMark.READY
        rows.any { it.activePages > 0 || it.manifestActive } -> ReaderStatusMark.ACTIVE
        rows.any { it.failedPages > 0 || it.phase == ReaderSourceStandbyPhase.FAILED } -> ReaderStatusMark.ERROR
        rows.any { it.deferredPages > 0 || it.phase == ReaderSourceStandbyPhase.DEFERRED } -> ReaderStatusMark.PAUSED
        rows.any { it.queuedPages > 0 || it.phase == ReaderSourceStandbyPhase.QUEUED } -> ReaderStatusMark.UNKNOWN
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
private fun StatusNumber(value: String, modifier: Modifier = Modifier) {
    Text(value, modifier, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
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
private fun PreparationDetails(state: ReaderPreparationState, work: ReaderWork, budget: ExperienceBudget) {
    var showAllIssues by remember(state.sourceId, state.windowRevision, work) { mutableStateOf(false) }
    val counts = work.counts(state)
    StatusValue("排队 / 进行", "${counts.queued} / ${counts.active}")
    StatusValue("完成 / 暂缓 / 失败", "${counts.complete} / ${counts.deferred} / ${counts.failed}")
    when (work) {
        ReaderWork.RESOLVE -> StatusValue("解析并发配置", "${budget.resolveRequests} 项")
        ReaderWork.ORIGINAL -> {
            StatusValue("预读并发配置", "${budget.prefetchRequests} 项")
            StatusValue("当前页并发配置", "${budget.readerRequests} 项")
        }
        else -> {
            StatusValue("解码并发配置", "${budget.decodeRequests} 项")
            StatusExplanation("本地核验不一定占用解码名额。")
        }
    }
    state.pauseReason?.let { StatusExplanation(it) }
    val pages = state.pages.filter { work.step(it).phase != ReaderPreparationPhase.SKIPPED }
    if (pages.isNotEmpty()) {
        StatusSubheading("本窗口页进度", work.title)
        PreparationPages(pages, work)
        val current = pages.filter { it.role == ReaderPreparationRole.VISIBLE }
        current.forEach { page ->
            if (work == ReaderWork.ORIGINAL) StatusValue("第 ${page.pageIndex + 1} 页原图", presenceLabel(page.originalPresent))
            if (work == ReaderWork.PREVIEW) StatusValue("第 ${page.pageIndex + 1} 页小图", previewPresenceLabel(page.previewResident))
            if (work == ReaderWork.ORIGINAL && page.joining) StatusExplanation("接续本页已开始的预读")
        }
        val received = state.pages.sumOf { it.receivedBytes }
        val expected = state.pages.takeIf { pages -> pages.all { it.expectedBytes != null } }
            ?.sumOf { it.expectedBytes ?: 0L }
        if (work == ReaderWork.ORIGINAL && received > 0L) StatusValue("本窗口已接收", transferLabel(received, expected))
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
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pages.sortedBy { it.pageIndex }.forEach { page ->
            Column(Modifier.widthIn(min = 30.dp).semantics(mergeDescendants = true) {
                contentDescription = "第 ${page.pageIndex + 1} 页，${roleLabel(page.role)}，${work.title}${phaseLabel(work.step(page).phase)}，原图${presenceLabel(page.originalPresent)}，小图${previewPresenceLabel(page.previewResident)}"
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusNumber((page.pageIndex + 1).toString(), Modifier
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha =
                        if (page.role == ReaderPreparationRole.VISIBLE) .09f else 0f), CircleShape)
                    .padding(horizontal = 4.dp, vertical = 2.dp))
                StepMark(work.step(page).phase)
                if (work == ReaderWork.ORIGINAL) PresenceMark(Icons.Outlined.Download, page.originalPresent)
                if (work == ReaderWork.PREVIEW) PresenceMark(Icons.Outlined.Memory, page.previewResident)
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
private fun StandbyDetails(row: ReaderSourceStandby, expanded: Boolean, enabled: Boolean, onToggle: () -> Unit,
    onRetry: (() -> Unit)?, onUse: (() -> Unit)?) {
    MotionTextButton(onClick = onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
        BodySourceMark(row.sourceId, row.sourceName, Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.sourceName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(if (row.requestedPages > 0) "原图 ${row.preparedPages}/${row.requestedPages} · 小图 ${row.previewResidentPages}"
                else row.reason ?: when (row.phase) {
                    ReaderSourceStandbyPhase.PREPARING, ReaderSourceStandbyPhase.RESOLVING -> "读取目录中，尚无图片准备目标"
                    ReaderSourceStandbyPhase.QUEUED -> "目录请求排队中"
                    ReaderSourceStandbyPhase.MANIFEST_READY -> "目录已备，等待建立图片目标"
                    else -> "尚未建立图片准备目标"
                },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(when { row.targetOriginalPresent -> "目标已备"; row.manifestActive -> "目录中"
            row.manifestQueued -> if (row.phase == ReaderSourceStandbyPhase.FAILED) "重试排队" else "目录排队"
            else -> standbyPhaseLabel(row.phase) }, style = MaterialTheme.typography.labelSmall,
            color = if (row.phase == ReaderSourceStandbyPhase.FAILED && !row.targetOriginalPresent && !row.manifestQueued && !row.manifestActive)
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.padding(start = 4.dp).size(16.dp))
    }
    if (row.requestedPages > 0 || row.manifestActive || row.manifestQueued) StatusExplanation(
        "并发 ${row.activePages.toLong() + if (row.manifestActive) 1L else 0L} · 排队 ${row.queuedPages.toLong() + if (row.manifestQueued) 1L else 0L}" +
            (if (row.failedPages > 0) " · 失败 ${row.failedPages} 页" else ""))
    if (onRetry != null || onUse != null) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (onRetry != null) MotionTextButton(onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(15.dp))
            Text("重试此来源", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
        if (onUse != null) MotionTextButton(onUse, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp)
            .semantics { contentDescription = "与${row.sourceName}的附近备用页对齐，确认后仅替换当前页" },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
            Icon(Icons.Outlined.Layers, null, Modifier.size(15.dp))
            Text("对齐补页", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (onUse != null) StatusExplanation("附近位置是估算，先确认画面")
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
    StatusValue("当前状态", when { next.manifestActive -> "读取目录中"; next.manifestQueued -> "目录请求排队"
        else -> nextChapterPhaseLabel(next.phase) })
    StatusValue("并发 / 排队", "${next.activePages.toLong() + if (next.manifestActive) 1L else 0L} / ${next.queuedPages.toLong() + if (next.manifestQueued) 1L else 0L}")
    StatusValue("失败 / 暂缓", "${next.failedPages} / ${next.deferredPages}")
    if (next.activePages > 0) StatusValue("进行阶段", "解析 ${next.resolvingPages} · 原图 ${next.downloadingPages} · 核验 ${next.checkingPages}")
    next.receivedBytes?.takeIf { it > 0L }?.let { StatusValue("已接收", experienceCacheSize(it)) }
    next.reason?.let { StatusExplanation(it, error = next.phase == ReaderNextChapterPreparationPhase.FAILED) }
}

@Composable
private fun CacheOverview(store: ExperiencePolicyStore, expanded: ReaderWork?, enabled: Boolean,
    memoryIdleMinutes: Int, imageRetentionHours: Int, memoryMiB: Int?, diskMiB: Int?, onToggle: (ReaderWork) -> Unit) {
    val cache = projectedCacheUsage(store)
    StatusSubheading("共享图片缓存", "已用 / 当前容量")
    CacheWorkRow(Icons.Outlined.Memory, "解码内存", cache.memoryBytes, cache.memoryLimit,
        false, expanded == ReaderWork.MEMORY, enabled) { onToggle(ReaderWork.MEMORY) }
    if (expanded == ReaderWork.MEMORY) {
        StatusValue("用户配置", memoryMiB?.let { "$it MB" } ?: "自动")
        StatusValue("闲置保留", if (memoryIdleMinutes == 0) "按容量回收" else "$memoryIdleMinutes 分钟")
        StatusExplanation("这是共享解码图片缓存，不是进程内存；当前画面持有的图像另有占用。")
    }
    CacheWorkRow(Icons.Outlined.Storage, "图片磁盘", cache.diskBytes, cache.diskLimit,
        cache.pending, expanded == ReaderWork.DISK, enabled) { onToggle(ReaderWork.DISK) }
    if (expanded == ReaderWork.DISK) {
        StatusValue("用户配置", diskMiB?.let { "$it MB" } ?: "自动")
        StatusValue("原图保留", if (imageRetentionHours == 0) "按空间回收" else "$imageRetentionHours 小时")
        if (cache.pending) StatusExplanation("容量待调整；上方仍是当前实际容量，在用图片释放后再应用。")
        StatusExplanation("共享网络图片缓存；独立源文件等用量见存储管理。当前用量不代表整章离线。")
    }
    cache.error?.let { StatusExplanation(it, error = true) }
}

private data class ReaderCacheUsage(val memoryBytes: Long?, val memoryLimit: Long?, val diskBytes: Long?,
    val diskLimit: Long?, val pending: Boolean, val error: String?)
private fun ExperienceUsage.cacheProjection() = ReaderCacheUsage(memoryBytes, memoryLimit, diskBytes, diskLimit, diskResizePending, cacheError)

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
        pending -> CacheState("待调整", ReaderStatusMark.PAUSED, occupancy)
        limit == 0L -> CacheState("已停用", ReaderStatusMark.PAUSED, null)
        used == null || limit == null || used < 0L || limit < 0L -> CacheState("用量未知", ReaderStatusMark.UNKNOWN, null)
        used >= limit -> CacheState("已达容量", ReaderStatusMark.IDLE, occupancy)
        used == 0L -> CacheState("空缓存", ReaderStatusMark.IDLE, occupancy)
        else -> CacheState("有缓存", ReaderStatusMark.READY, occupancy)
    }
}

@Composable
private fun ReaderCacheEntry(store: ExperiencePolicyStore, enabled: Boolean, onOpen: () -> Unit) {
    val cache = projectedCacheUsage(store)
    val memory = cacheState(cache.memoryBytes, cache.memoryLimit)
    val disk = cacheState(cache.diskBytes, cache.diskLimit, cache.pending)
    MotionIconButton(onOpen, Modifier.width(70.dp).height(44.dp).semantics(mergeDescendants = true) {
        contentDescription = "共享图片缓存"
        stateDescription = "解码内存${statusCacheUsage(cache.memoryBytes, cache.memoryLimit)}，${memory.label}；图片磁盘${statusCacheUsage(cache.diskBytes, cache.diskLimit)}，${disk.label}；${cache.error ?: "点击查看"}"
    }, enabled = enabled, tooltip = "共享内存 / 磁盘用量") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Icons.Outlined.Memory to memory, Icons.Outlined.Storage to disk).forEach { (icon, state) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(21.dp), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(15.dp))
                        if (state.mark == ReaderStatusMark.UNKNOWN || state.mark == ReaderStatusMark.PAUSED)
                            StatusMark(state.mark, Modifier.align(Alignment.TopEnd).size(8.dp))
                    }
                    CacheOccupancy(state.occupancy, Modifier.width(22.dp).height(2.dp))
                }
            }
            if (cache.error != null) Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(9.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CacheWorkRow(icon: ImageVector, title: String, used: Long?, limit: Long?, pending: Boolean,
    expanded: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val state = cacheState(used, limit, pending)
    MotionTextButton(onClick = onToggle, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
        .semantics(mergeDescendants = true) { contentDescription = "$title，应用共享，已用与当前容量${statusCacheUsage(used, limit)}，${state.label}" },
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(icon, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurface)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(state.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(Modifier.padding(start = 24.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                StatusNumber(used?.let(::experienceCacheSize) ?: "未知")
                StatusNumber("/")
                StatusNumber(limit?.let(::experienceCacheSize) ?: "未知")
            }
            CacheOccupancy(state.occupancy, Modifier.padding(start = 24.dp, end = 23.dp).fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
private fun CacheOccupancy(occupancy: Float?, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (occupancy != null) .08f else 0f), CircleShape)) {
        if (occupancy != null) Box(Modifier.fillMaxHeight().fillMaxWidth(occupancy)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .45f), CircleShape))
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
