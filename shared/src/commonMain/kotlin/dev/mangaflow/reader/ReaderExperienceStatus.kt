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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.ReaderSourceStandby
import dev.mangaflow.data.ReaderSourceStandbyPhase
import dev.mangaflow.experience.ExperienceBudget
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.ReaderStatusPlacement
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.ExperienceSettingsDialog
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.experienceCacheSize
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.delay

/** The reader observes the existing owners; this strip never starts speculative transfers. */
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
    modifier: Modifier = Modifier,
) {
    val custom by store.overrides.collectAsStateWithLifecycle()
    val usage by store.usage.collectAsStateWithLifecycle()
    val motion = LocalMotionEnabled.current
    val placement = custom.readerStatusPlacement
    val shown = visible && placement != ReaderStatusPlacement.HIDDEN
    var selected by remember(sessionKey) { mutableStateOf<ReaderStatusSection?>(null) }
    var retained by remember(sessionKey) { mutableStateOf(ReaderStatusSection.PREFETCH) }
    var popoverPresent by remember { mutableStateOf(false) }
    var pendingPolicy by remember(sessionKey) { mutableStateOf(false) }
    var showPolicy by remember(sessionKey) { mutableStateOf(false) }
    val reportInspection by rememberUpdatedState(onInspectingChange)
    SideEffect { reportInspection(popoverPresent || selected != null || showPolicy || pendingPolicy) }
    DisposableEffect(Unit) { onDispose { reportInspection(false) } }
    LaunchedEffect(visible) {
        if (!visible) { selected = null; pendingPolicy = false; showPolicy = false }
    }
    LaunchedEffect(shown) { if (!shown) selected = null }
    LaunchedEffect(pendingPolicy, popoverPresent, visible) {
        if (visible && pendingPolicy && !popoverPresent) {
            pendingPolicy = false
            showPolicy = true
        }
    }
    LaunchedEffect(shown, showPolicy) {
        if (!shown || showPolicy) return@LaunchedEffect
        while (true) { store.refreshCacheUsage(); delay(2_000L) }
    }
    val top = placement == ReaderStatusPlacement.TOP
    val clearance by animateDpAsState(
        if (top) topClearance else bottomClearance,
        animationSpec = if (motion) tween(MangaMotion.Chrome) else snap(), label = "reading-status-clearance",
    )
    val prefetchState = when {
        usage.activePrefetch > 0 -> ReaderStatusMark.ACTIVE
        budget.prefetchBytes <= 0L -> ReaderStatusMark.PAUSED
        else -> ReaderStatusMark.IDLE
    }
    val standbyState = when {
        standbys.any { it.activePages > 0 || it.phase == ReaderSourceStandbyPhase.PREPARING } -> ReaderStatusMark.ACTIVE
        standbys.any { it.targetPrepared } -> ReaderStatusMark.READY
        standbys.any { it.phase == ReaderSourceStandbyPhase.FAILED } -> ReaderStatusMark.ERROR
        budget.standbySources <= 0 || standbys.any { it.phase == ReaderSourceStandbyPhase.DEFERRED } -> ReaderStatusMark.PAUSED
        alternativeCount == 0 -> ReaderStatusMark.IDLE
        else -> ReaderStatusMark.UNKNOWN
    }
    val memoryState = if (usage.memoryBytes == null) ReaderStatusMark.UNKNOWN
        else if (usage.memoryBytes!! > 0L) ReaderStatusMark.READY else ReaderStatusMark.IDLE
    val diskState = when {
        usage.cacheError != null -> ReaderStatusMark.ERROR
        usage.diskResizePending -> ReaderStatusMark.PAUSED
        usage.diskBytes == null -> ReaderStatusMark.UNKNOWN
        usage.diskBytes!! > 0L -> ReaderStatusMark.READY
        else -> ReaderStatusMark.IDLE
    }
    Box(modifier.fillMaxSize()) {
        Box(Modifier.align(if (top) Alignment.TopStart else Alignment.BottomStart)
            .windowInsetsPadding(
                (if (top) readerStatusInsets().union(WindowInsets.captionBar) else readerNavigationInsets())
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal + if (top) WindowInsetsSides.Top else WindowInsetsSides.Bottom))
            .padding(start = 12.dp, top = if (top) clearance + 8.dp else 0.dp,
                bottom = if (top) 0.dp else clearance + 8.dp)) {
            AnimatedVisibility(shown,
                enter = fadeIn(tween(if (motion) MangaMotion.Content else 0)),
                exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
                LiquidGlassSurface(backdrop, cornerRadius = 22.dp, role = GlassSurfaceRole.READING_DOCK,
                    softCard = true, trackPointer = true) {
                    Row(Modifier.padding(horizontal = 2.dp)) {
                        fun inspect(section: ReaderStatusSection) { retained = section; selected = section }
                        StatusGlyph(Icons.AutoMirrored.Outlined.MenuBook, "预读", prefetchState,
                            "${usage.activePrefetch} 个预读请求进行中", shown) { inspect(ReaderStatusSection.PREFETCH) }
                        StatusGlyph(Icons.Outlined.Layers, "备用来源", standbyState,
                            "${standbys.count { it.targetPrepared }} 个来源已备好当前附近目标原图", shown) { inspect(ReaderStatusSection.SOURCES) }
                        StatusGlyph(Icons.Outlined.Memory, "解码内存", memoryState,
                            statusCacheUsage(usage.memoryBytes, usage.memoryLimit), shown) { inspect(ReaderStatusSection.MEMORY) }
                        StatusGlyph(Icons.Outlined.Storage, "图片磁盘", diskState,
                            statusCacheUsage(usage.diskBytes, usage.diskLimit), shown) { inspect(ReaderStatusSection.DISK) }
                    }
                }
            }
            CompactGlassPopover(visible = shown && selected != null, backdrop = backdrop,
                aboveAnchor = !top, alignToStart = true, maximumWidth = 312.dp,
                onDismiss = { selected = null }, onPresenceChange = { popoverPresent = it }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(retained.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
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
                        ReaderStatusSection.PREFETCH -> {
                            StatusValue("进行中", "${usage.activePrefetch} 个请求")
                            StatusValue("候选窗口", "前 ${budget.prefetchAhead} · 后 ${budget.prefetchBehind} 页")
                            StatusValue("传输上限", "${budget.prefetchRequests} 个 · ${experienceCacheSize(budget.adjacentPrefetchBytes)}")
                            StatusValue("邻页小图预解码", "${budget.decodedPrefetchPages} 页")
                            StatusExplanation(if (budget.prefetchBytes <= 0L) budget.reason
                                else "当前页优先。窗口是准备范围，不代表这些页都已缓存；请求数是应用当前实际预读任务。")
                            StatusExplanation("移出窗口会取消尚未完成的预读；已完成的原图与小图分别按磁盘、内存保留策略清理。")
                        }
                        ReaderStatusSection.SOURCES -> {
                            StatusValue("有效上限", "${budget.standbySources} 源 · 每源 ${budget.standbyPages} 页")
                            StatusValue("存储", if (budget.standbyMemoryWarmup) "原图磁盘 + 小图内存" else "仅原图磁盘")
                            if (standbys.isEmpty()) StatusExplanation(when {
                                alternativeCount == 0 -> "本章没有已确认的备用来源。"
                                budget.standbySources <= 0 -> budget.standbyPauseReason ?: budget.reason
                                else -> "等待当前页与可用资源，再准备已确认的同章来源。"
                            })
                            standbys.forEach { row ->
                                StatusValue(row.sourceName, when {
                                    row.phase == ReaderSourceStandbyPhase.PREPARING -> "准备中"
                                    row.phase == ReaderSourceStandbyPhase.FAILED -> "请求失败"
                                    row.phase == ReaderSourceStandbyPhase.DEFERRED -> "暂缓"
                                    row.preparedPages > 0 -> "原图 ${row.preparedPages}/${row.requestedPages} · 验图 ${row.decodedPages}"
                                    else -> "目录已备妥"
                                })
                                row.reason?.let { StatusExplanation(it) }
                                if (row.preparedPages > 0) StatusExplanation(if (row.memoryWarmed)
                                    "当前目标的小图仍在内存缓存" else "当前目标的小图未驻留内存，切换时再解码")
                            }
                            StatusExplanation("备用按当前阅读位置估算，版本可能分页不同。原图已缓存与小图已解码是两种状态，均不证明跨源同页等价。")
                        }
                        ReaderStatusSection.MEMORY -> {
                            StatusValue("解码缓存", statusCacheUsage(usage.memoryBytes, usage.memoryLimit))
                            StatusValue("未复用保留", if (custom.contentCache.memoryIdleMinutes == 0) "按容量淘汰"
                                else "${custom.contentCache.memoryIdleMinutes} 分钟")
                            StatusExplanation("这里是图片缓存实际用量，不是应用全部内存。当前画面持有的图像与脚本临时缓冲另有占用。")
                            StatusExplanation("翻过的页面可短暂复用；容量不足或系统内存压力会回收缓存。清掉缓存不等于强制释放当前正在显示的图片。")
                        }
                        ReaderStatusSection.DISK -> {
                            StatusValue("图片缓存", statusCacheUsage(usage.diskBytes, usage.diskLimit))
                            StatusValue("原图保留", if (custom.contentCache.imageRetentionHours == 0) "按空间回收"
                                else "${custom.contentCache.imageRetentionHours} 小时")
                            if (usage.diskResizePending) StatusExplanation("等待在用原图释放后调整容量。")
                            usage.cacheError?.let { StatusExplanation(it) }
                            StatusExplanation("这是共享图片磁盘缓存，含阅读与备用预热原图；脚本等其他目录在缓存管理中统一统计。")
                            StatusExplanation("已读原图可供回翻。清理会避开正在阅读、传输与已保存的离线内容，保留时间不等于到点强删。")
                        }
                    }
                }
            }
        }
    }
    ExperienceSettingsDialog(visible = showPolicy && visible, backdrop = backdrop) { showPolicy = false }
}

private enum class ReaderStatusSection(val title: String) {
    PREFETCH("阅读预读"), SOURCES("多源备用"), MEMORY("解码内存"), DISK("图片磁盘"),
}
private enum class ReaderStatusMark { ACTIVE, READY, PAUSED, UNKNOWN, ERROR, IDLE }

@Composable
private fun StatusGlyph(icon: ImageVector, label: String, mark: ReaderStatusMark,
    detail: String, enabled: Boolean, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    val description = when (mark) {
        ReaderStatusMark.ACTIVE -> "进行中"; ReaderStatusMark.READY -> "有缓存"
        ReaderStatusMark.PAUSED -> "暂缓"; ReaderStatusMark.UNKNOWN -> "尚未取得"
        ReaderStatusMark.ERROR -> "异常"; ReaderStatusMark.IDLE -> "待命"
    }
    MotionIconButton(onClick, Modifier.size(44.dp).semantics {
        contentDescription = label
        stateDescription = "$description，$detail，点击查看详情"
    }, enabled = enabled, tooltip = "$label · $description") {
        Box(Modifier.size(29.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = color.copy(alpha = .80f))
            when (mark) {
                ReaderStatusMark.ACTIVE -> MotionBusyIndicator(Modifier.align(Alignment.TopEnd).size(9.dp),
                    color = color.copy(alpha = .82f), strokeWidth = 1.dp)
                ReaderStatusMark.READY -> Box(Modifier.align(Alignment.TopEnd).size(5.dp)
                    .background(color.copy(alpha = .75f), CircleShape))
                ReaderStatusMark.PAUSED -> Icon(Icons.Outlined.Pause, null,
                    Modifier.align(Alignment.TopEnd).size(9.dp), tint = color.copy(alpha = .65f))
                ReaderStatusMark.UNKNOWN -> Icon(Icons.Outlined.HelpOutline, null,
                    Modifier.align(Alignment.TopEnd).size(9.dp), tint = color.copy(alpha = .65f))
                ReaderStatusMark.ERROR -> Icon(Icons.Outlined.ErrorOutline, null,
                    Modifier.align(Alignment.TopEnd).size(9.dp), tint = color)
                ReaderStatusMark.IDLE -> Unit
            }
        }
    }
}

@Composable
private fun StatusValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(value, Modifier.weight(1.25f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusExplanation(value: String) {
    Text(value, Modifier.fillMaxWidth().padding(vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun statusCacheUsage(used: Long?, limit: Long?) =
    (used?.let(::experienceCacheSize) ?: "未知") + " / " + (limit?.let(::experienceCacheSize) ?: "未知")
