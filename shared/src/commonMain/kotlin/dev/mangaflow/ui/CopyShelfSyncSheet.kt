package dev.mangaflow.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.account.CopyMergeChoice
import dev.mangaflow.account.CopyShelfSyncState
import dev.mangaflow.account.CopySyncDifference
import dev.mangaflow.account.CopySyncDifferenceKind
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class CopyReviewChoice(val evidence: String, val choice: CopyMergeChoice)

private fun CopySyncDifference.allows(choice: CopyMergeChoice): Boolean =
    reviewKey.isNotBlank() && if (choice == CopyMergeChoice.USE_LOCAL) canUseLocal else canUseCopy

/** Repair only a byte-for-byte reversible UTF-8/Latin-1 display accident; stored titles and IDs stay intact. */
private fun copyProgressDisplay(value: String): String {
    val split = value.indexOf(" · ")
    val title = if (split < 0) value else value.substring(0, split)
    if (title.none { it.code in 0xc2..0xf4 } || title.any { it.code > 0xff }) return value
    val bytes = ByteArray(title.length) { title[it].code.toByte() }
    val decoded = try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { return value }
    if (decoded.none { it.code in 0x2e80..0x9fff }) return value
    val encoded = decoded.encodeToByteArray()
    if (encoded.size != bytes.size || encoded.indices.any { encoded[it] != bytes[it] }) return value
    return decoded + if (split < 0) "" else value.substring(split)
}

/** Existing Ovio/beUI workspace, with inline tools and explicit per-field staged changes. */
@Composable
internal fun CopyShelfSyncSheet(
    visible: Boolean,
    state: CopyShelfSyncState,
    repository: MangaRepository,
    backdrop: HazeState,
    onCheck: () -> Unit,
    onRetry: (CopySyncDifferenceKind) -> Unit,
    onRetryWork: (Manga) -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onApply: (Long, Map<String, CopyMergeChoice>) -> Unit,
    onDismiss: () -> Unit,
    focusMangaId: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    var choices by remember(state.owner) { mutableStateOf<Map<String, CopyReviewChoice>>(emptyMap()) }
    var expandedWorks by remember(state.owner) { mutableStateOf<Set<String>>(emptySet()) }
    var filter by remember(state.owner) { mutableIntStateOf(0) }
    var onlySelected by remember(state.owner) { mutableStateOf(false) }
    var showScope by remember(state.owner) { mutableStateOf(false) }
    var bulkOpen by remember(state.owner) { mutableStateOf(false) }
    val toolScope = rememberCoroutineScope()
    var toolScroll by remember(state.owner) { mutableStateOf<Job?>(null) }
    val allList = key(state.owner) { rememberLazyListState() }
    val favoriteList = key(state.owner) { rememberLazyListState() }
    val progressList = key(state.owner) { rememberLazyListState() }
    val selectedList = key(state.owner) { rememberLazyListState() }
    val rowsByKey = remember(state.differences) { state.differences.associateBy { it.key } }
    val selected = state.differences.filter { difference ->
        choices[difference.key]?.let { it.evidence == difference.reviewKey && difference.allows(it.choice) } == true
    }
    val selectedKeys = selected.mapTo(mutableSetOf()) { it.key }
    // A read-only refresh can temporarily withhold evidence. Reconcile only after it settles.
    LaunchedEffect(state.owner, state.differences, state.busy) {
        if (!state.busy) {
            choices = choices.filter { (key, staged) ->
                rowsByKey[key]?.let { it.reviewKey == staged.evidence && it.allows(staged.choice) } == true
            }
            expandedWorks = expandedWorks.intersect(state.differences.mapTo(mutableSetOf()) { it.manga.id })
            if (choices.isEmpty()) onlySelected = false
        }
    }
    LaunchedEffect(visible, focusMangaId) {
        if (visible && focusMangaId != null) {
            filter = 2
            onlySelected = false
            expandedWorks = expandedWorks + focusMangaId
            progressList.scrollToItem(0)
        }
        if (!visible) { bulkOpen = false; toolScroll?.cancel() }
    }
    // Staging valid evidence is harmless during a read; apply must wait for the coordinator.
    val canChoose = visible && state.owner != null && state.checked && !state.importing && !state.uploading
    val canApply = canChoose && !state.checking && !state.projecting
    val scoped = state.differences.filter {
        filter == 0 || filter == 1 && it.kind == CopySyncDifferenceKind.FAVORITE ||
            filter == 2 && it.kind == CopySyncDifferenceKind.PROGRESS
    }
    val bulkRows = if (onlySelected) scoped.filter { it.key in selectedKeys } else scoped
    val activeList = if (onlySelected) selectedList else when (filter) {
        1 -> favoriteList; 2 -> progressList; else -> allList
    }
    val favorites = state.differences.count { it.kind == CopySyncDifferenceKind.FAVORITE }
    val progress = state.differences.count { it.kind == CopySyncDifferenceKind.PROGRESS }
    val summary = selected.groupingBy { difference ->
        if (choices[difference.key]?.choice == CopyMergeChoice.USE_LOCAL) difference.localActionLabel else difference.cloudActionLabel
    }.eachCount().entries.joinToString(" · ") { it.key + " " + it.value }
    val workingLabel = when {
        state.importing -> "正在导入收藏"
        state.uploading && state.mergeTotal > 0 -> "已处理 " + state.mergeCompleted + " / " + state.mergeTotal
        state.uploading -> "正在核对所选项"
        state.checking -> "正在核对拷贝"
        else -> null
    }

    val statusAngle by animateFloatAsState(if (showScope) 180f else 0f,
        tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing), label = "sync-status-disclosure")
    val scopedWorks = scoped.map { it.manga.id }.distinct().size
    val statusIssue = when {
        state.error != null -> "处理未完成"
        state.favoritesError != null && filter != 2 -> "收藏未取得"
        state.progressError != null && filter != 1 -> "进度未取得"
        state.progressUnavailableCount > 0 && filter != 1 -> state.progressUnavailableCount.toString() + " 部进度未取得"
        !state.hasFullImport && favorites > 0 && filter != 2 -> "首次回写需导入收藏"
        else -> null
    }
    val statusHasError = state.error != null || state.favoritesError != null && filter != 2 ||
        state.progressError != null && filter != 1
    val statusLabel = when {
        state.owner == null -> "连接账号后再检查"
        workingLabel != null -> listOfNotNull(workingLabel, statusIssue).joinToString(" · ")
        statusIssue != null -> statusIssue
        onlySelected -> "已选 " + selected.size + " 项 · 查看改动"
        state.message != null && selected.isEmpty() -> state.message
        !state.checked -> "检查本机与拷贝"
        scoped.isEmpty() -> "没有待处理差异"
        scopedWorks == scoped.size -> "点选一侧 · " + scopedWorks + " 部"
        else -> scopedWorks.toString() + " 部 · " + scoped.size + " 项"
    }

    fun choose(difference: CopySyncDifference, choice: CopyMergeChoice) {
        if (!canChoose || !difference.allows(choice)) return
        val staged = CopyReviewChoice(difference.reviewKey, choice)
        choices = if (choices[difference.key] == staged) choices - difference.key else choices + (difference.key to staged)
    }
    fun chooseShown(choice: CopyMergeChoice) {
        if (!canChoose) return
        choices = choices + bulkRows.filter { it.allows(choice) }
            .associate { it.key to CopyReviewChoice(it.reviewKey, choice) }
    }
    fun revealTools(opening: Boolean) {
        toolScroll?.cancel()
        if (opening) toolScroll = toolScope.launch {
            if (motion) activeList.animateScrollToItem(0) else activeList.scrollToItem(0)
        }
    }
    DisposableEffect(state.owner) { onDispose { toolScroll?.cancel() } }

    key(state.owner) {
        MotionDialog(visible, onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false),
            maximumWidth = 640.dp) {
            BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
                val height = minOf(maxHeight, LocalMangaWindow.current.height * .88f, 720.dp)
                val stackedSides = maxWidth - 32.dp - (if (onlySelected) 32.dp else 82.dp) < 188.dp * LocalDensity.current.fontScale
                LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                    role = GlassSurfaceRole.PANEL, elevation = 0.dp) {
                    Column(Modifier.fillMaxWidth().heightIn(max = height)
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Sync, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text("拷贝同步", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            MotionSwap(state.busy, Modifier.size(44.dp)) { busy ->
                                MotionIconButton(onClick = if (busy) onCancel else onCheck,
                                    enabled = visible && state.owner != null && busy == state.busy,
                                    modifier = Modifier.size(44.dp), tooltip = if (busy) "停止本次处理" else "重新检查差异") {
                                    Icon(if (busy) Icons.Outlined.Stop else Icons.Outlined.Refresh,
                                        if (busy) "停止本次处理" else "重新检查差异", Modifier.size(18.dp))
                                }
                            }
                            MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭对照") {
                                Icon(Icons.Outlined.Close, "关闭对照", Modifier.size(20.dp))
                            }
                        }
                        MotionTabs(filter, listOf("全部",
                            if (state.favoritesReady) "收藏 " + favorites else "收藏 —",
                            if (state.progressReady || progress > 0) "进度 " + progress else "进度 —"),
                            onSelect = { if (visible) { toolScroll?.cancel(); filter = it; onlySelected = false; bulkOpen = false } },
                            modifier = Modifier.fillMaxWidth(), treatment = MotionTabTreatment.Soft,
                            controlHeight = 44.dp, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            MotionTextButton(onClick = { showScope = !showScope; revealTools(showScope) },
                                enabled = visible, modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                                    .semantics { contentDescription = statusLabel + (if (showScope) "，收起检查状态与同步说明" else "，展开检查状态与同步说明"); this.selected = showScope },
                                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
                                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                    if (state.busy) MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.4.dp)
                                    else Icon(if (statusIssue != null) Icons.Outlined.Info else Icons.Outlined.CompareArrows,
                                        null, Modifier.size(14.dp), tint = if (statusHasError) colors.error else colors.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(statusLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                    color = if (statusHasError) colors.error else colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Outlined.ExpandMore, null,
                                    Modifier.padding(start = 2.dp).size(14.dp).graphicsLayer { rotationZ = statusAngle },
                                    tint = colors.onSurfaceVariant)
                            }
                            CopyShelfTool(selected.isNotEmpty() || onlySelected) {
                                MotionTextButton(onClick = { if (visible) { toolScroll?.cancel(); if (!onlySelected) filter = 0; onlySelected = !onlySelected; bulkOpen = false } },
                                    enabled = visible && selected.isNotEmpty(),
                                    modifier = Modifier.semantics { contentDescription = "查看全部已选的 " + selected.size + " 项实际改动"; this.selected = onlySelected },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                    Icon(if (onlySelected) Icons.Outlined.CheckCircle else Icons.Outlined.Checklist, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    MotionSwap(selected.size) { count -> Text(count.toString(), fontSize = 12.sp) }
                                }
                            }
                            MotionIconButton(onClick = { if (visible) { bulkOpen = !bulkOpen; revealTools(bulkOpen) } }, enabled = visible,
                                modifier = Modifier.size(44.dp).semantics { this.selected = bulkOpen }, tooltip = "批量选择当前列表") {
                                Icon(Icons.Outlined.DoneAll, "批量选择当前列表", Modifier.size(18.dp))
                            }
                        }
                        if (bulkRows.isNotEmpty() && !stackedSides) Row(
                            Modifier.fillMaxWidth().padding(start = 32.dp, end = if (onlySelected) 0.dp else 50.dp, bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            CopySyncSideHeading("本机", Modifier.weight(1f), cloud = false)
                            CopySyncSideHeading("拷贝", Modifier.weight(1f), cloud = true)
                        }
                        val scene = if (onlySelected) filter + 3 else filter
                        MotionTabContent(selected = scene, modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                            contentKey = { it }, order = { it }, fillContent = false, soft = true) { target ->
                            val kindFilter = target % 3
                            val selectedOnly = target >= 3
                            val fields = state.differences.filter {
                                (kindFilter == 0 || kindFilter == 1 && it.kind == CopySyncDifferenceKind.FAVORITE ||
                                    kindFilter == 2 && it.kind == CopySyncDifferenceKind.PROGRESS) &&
                                    (!selectedOnly || it.key in selectedKeys)
                            }
                            val groups = fields.groupBy { it.manga.id }.entries
                                .sortedBy { if (it.key == focusMangaId) 0 else 1 }
                            val list = if (selectedOnly) selectedList else when (kindFilter) {
                                1 -> favoriteList; 2 -> progressList; else -> allList
                            }
                            val active = visible && target == scene
                            LazyColumn(Modifier.fillMaxWidth().motionInputGate(active), state = list,
                                contentPadding = PaddingValues(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                item(key = "inline-tools") {
                                    Column {
                                        CopyShelfReveal(bulkOpen && active) {
                                            Column(Modifier.padding(bottom = 6.dp)) {
                                                Text("选择当前列表要采用的一侧", style = MaterialTheme.typography.labelSmall,
                                                    color = colors.onSurfaceVariant)
                                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    MotionTextButton(onClick = { chooseShown(CopyMergeChoice.USE_LOCAL) },
                                                        enabled = canChoose && active && bulkRows.any { it.allows(CopyMergeChoice.USE_LOCAL) },
                                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                                                        Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(16.dp))
                                                        Spacer(Modifier.width(5.dp)); Text("本机", fontSize = 12.sp)
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(bulkRows.count { it.allows(CopyMergeChoice.USE_LOCAL) }.toString(),
                                                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                    }
                                                    MotionTextButton(onClick = { chooseShown(CopyMergeChoice.USE_COPY) },
                                                        enabled = canChoose && active && bulkRows.any { it.allows(CopyMergeChoice.USE_COPY) },
                                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                                                        Icon(Icons.Outlined.CloudQueue, null, Modifier.size(16.dp))
                                                        Spacer(Modifier.width(5.dp)); Text("拷贝", fontSize = 12.sp)
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(bulkRows.count { it.allows(CopyMergeChoice.USE_COPY) }.toString(),
                                                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                    }
                                                    MotionIconButton(onClick = { choices = choices - bulkRows.map { it.key }.toSet() },
                                                        enabled = active && !state.importing && !state.uploading && bulkRows.any { it.key in selectedKeys },
                                                        modifier = Modifier.size(44.dp), tooltip = "清除当前列表的选择") {
                                                        Icon(Icons.Outlined.ClearAll, "清除当前列表的选择", Modifier.size(18.dp))
                                                    }
                                                }
                                            }
                                        }
                                        CopyShelfReveal(showScope) {
                                            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (kindFilter != 2) CopySyncReadIssue("收藏", state.favoritesError, active && !state.busy) {
                                                    onRetry(CopySyncDifferenceKind.FAVORITE)
                                                }
                                                if (kindFilter != 1) CopySyncReadIssue("进度", state.progressError, active && !state.busy) {
                                                    onRetry(CopySyncDifferenceKind.PROGRESS)
                                                }
                                                if (state.error != null) Text(state.error, Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                    style = MaterialTheme.typography.bodySmall, color = colors.error)
                                                CopyShelfValue(state.message) { message ->
                                                    Text(message, Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                                }
                                                if (!state.hasFullImport && favorites > 0 && kindFilter != 2) Row(
                                                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                    Text("首次回写需先导入收藏", Modifier.weight(1f),
                                                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                    MotionIconButton(onClick = onImport, enabled = active && canApply && state.favoritesReady,
                                                        modifier = Modifier.size(44.dp), tooltip = "完整导入拷贝收藏，保留本机原有作品") {
                                                        Icon(Icons.Outlined.CloudDownload, "完整导入拷贝收藏，保留本机原有作品", Modifier.size(18.dp))
                                                    }
                                                }
                                                if (state.progressUnavailableCount > 0) Text(
                                                    state.progressUnavailableCount.toString() + " 部尚无可比较进度，不表示未读。",
                                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                Text("收藏按所选一侧调整。进度只决定本机从哪章继续，不回写拷贝。",
                                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                                Text("本机页内位置、历史与下载保留。只有实际阅读才向拷贝报告章节。",
                                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                if (state.localOnlyCount > 0) Text(
                                                    "仅本机的 " + state.localOnlyCount + " 部作品不参与拷贝同步。",
                                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                                items(groups, key = { it.key }, contentType = { "copy-work-diff" }) { group ->
                                    CopyWorkDifference(group.value, choices, canChoose && active, active && !state.busy, repository,
                                        expanded = selectedOnly || group.key in expandedWorks,
                                        disclosable = !selectedOnly,
                                        modifier = Modifier.animateItem(
                                            fadeInSpec = if (motion) tween(MangaMotion.ListEnter) else null,
                                            placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                            fadeOutSpec = if (motion) tween(MangaMotion.ListExit) else null),
                                        onExpand = { if (active) expandedWorks = if (group.key in expandedWorks) expandedWorks - group.key else expandedWorks + group.key },
                                        onChoose = ::choose, onRetry = onRetry, onRetryWork = onRetryWork)
                                }
                                if (groups.isEmpty()) item(key = "empty") {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(if (state.busy) Icons.Outlined.Sync else if (state.error != null || state.favoritesError != null || state.progressError != null)
                                            Icons.Outlined.CloudOff else Icons.Outlined.CheckCircle,
                                            null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                                        Text(when {
                                            state.busy -> "核对后会显示差异"
                                            selectedOnly -> "还没有选择改动"
                                            state.owner == null -> "连接账号后再检查"
                                            state.error != null || state.favoritesError != null || state.progressError != null -> "部分内容尚未读到"
                                            !state.checked -> "检查本机与拷贝的差异"
                                            state.progressUnavailableCount > 0 && kindFilter != 1 -> "已取得的进度没有差异"
                                            else -> "没有待处理差异"
                                        }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        val hint = if (state.uploading) workingLabel.orEmpty() else summary.ifBlank {
                            if (state.owner == null) "连接账号后再检查" else "点选要采用的一侧"
                        }
                        val applyButton: @Composable (Modifier) -> Unit = { modifier ->
                            MotionButton(onClick = {
                                if (canApply && selected.isNotEmpty()) onApply(state.revision,
                                    selected.associate { it.key to requireNotNull(choices[it.key]).choice })
                            }, enabled = canApply && selected.isNotEmpty(), modifier = modifier.heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) {
                                Text(when {
                                    state.uploading && state.mergeTotal > 0 -> state.mergeCompleted.toString() + " / " + state.mergeTotal
                                    state.uploading -> "核对中"
                                    else -> "应用 " + selected.size
                                }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        CopyShelfReveal(selected.isNotEmpty() || state.uploading) {
                            if (LocalDensity.current.fontScale > 1.35f) Column(
                                Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(hint, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                applyButton(Modifier.fillMaxWidth())
                            } else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(hint, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                applyButton(Modifier.widthIn(min = 92.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopySyncSideHeading(label: String, modifier: Modifier, cloud: Boolean) {
    Row(modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(if (cloud) Icons.Outlined.CloudQueue else Icons.Outlined.PhoneAndroid,
            null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CopySyncReadIssue(label: String, error: String?, enabled: Boolean, onRetry: () -> Unit) {
    CopyShelfValue(error) { message ->
        val colors = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp), tint = colors.error)
            Column(Modifier.weight(1f)) {
                Text(label + "暂未读到", style = MaterialTheme.typography.labelMedium)
                Text(message, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = onRetry, enabled = enabled, modifier = Modifier.size(44.dp), tooltip = "重新读取" + label) {
                Icon(Icons.Outlined.Refresh, "重新读取" + label, Modifier.size(17.dp))
            }
        }
    }
}

@Composable
private fun CopyWorkDifference(
    fields: List<CopySyncDifference>,
    choices: Map<String, CopyReviewChoice>,
    enabled: Boolean,
    canRetry: Boolean,
    repository: MangaRepository,
    expanded: Boolean,
    disclosable: Boolean,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit,
    onChoose: (CopySyncDifference, CopyMergeChoice) -> Unit,
    onRetry: (CopySyncDifferenceKind) -> Unit,
    onRetryWork: (Manga) -> Unit,
) {
    val manga = fields.first().manga
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val angle by animateFloatAsState(if (expanded) 180f else 0f,
        tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing), label = "sync-work-disclosure")
    val count = fields.count { field -> choices[field.key]?.let { it.evidence == field.reviewKey && field.allows(it.choice) } == true }
    Row(modifier.fillMaxWidth().heightIn(min = 68.dp).padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MangaCover(manga, repository, Modifier.size(width = 26.dp, height = 38.dp).mangaCoverCard(4.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(manga.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium, maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (count > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(11.dp), tint = colors.onSurfaceVariant)
                    Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            for (field in fields) {
                val staged = choices[field.key]?.takeIf { it.evidence == field.reviewKey && field.allows(it.choice) }?.choice
                val localValue = if (field.kind == CopySyncDifferenceKind.PROGRESS) copyProgressDisplay(field.localValue) else field.localValue
                val cloudValue = if (field.kind == CopySyncDifferenceKind.PROGRESS) copyProgressDisplay(field.cloudValue) else field.cloudValue
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val stacked = maxWidth < 188.dp * LocalDensity.current.fontScale
                    val local: @Composable (Modifier) -> Unit = { sideModifier ->
                        CopyMergeSide(localValue, field.localActionLabel, expanded || staged == CopyMergeChoice.USE_LOCAL,
                            staged == CopyMergeChoice.USE_LOCAL, enabled && field.allows(CopyMergeChoice.USE_LOCAL),
                            manga.title + "，本机，" + localValue + "，" + field.localActionLabel,
                            sideModifier, if (stacked) "本机" else null) { onChoose(field, CopyMergeChoice.USE_LOCAL) }
                    }
                    val cloud: @Composable (Modifier) -> Unit = { sideModifier ->
                        CopyMergeSide(cloudValue, field.cloudActionLabel, expanded || staged == CopyMergeChoice.USE_COPY,
                            staged == CopyMergeChoice.USE_COPY, enabled && field.allows(CopyMergeChoice.USE_COPY),
                            manga.title + "，拷贝，" + cloudValue + "，" + field.cloudActionLabel,
                            sideModifier, if (stacked) "拷贝" else null) { onChoose(field, CopyMergeChoice.USE_COPY) }
                    }
                    if (stacked) Column(Modifier.fillMaxWidth()) { local(Modifier.fillMaxWidth()); cloud(Modifier.fillMaxWidth()) }
                    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        local(Modifier.weight(1f)); cloud(Modifier.weight(1f))
                    }
                }
                CopyShelfReveal(expanded && !field.canUseLocal && !field.canUseCopy) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(field.detail ?: "需重新读取后再选择", Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        MotionIconButton(onClick = {
                            if (field.kind == CopySyncDifferenceKind.PROGRESS) onRetryWork(field.manga) else onRetry(field.kind)
                        }, enabled = canRetry, modifier = Modifier.size(44.dp), tooltip = "重查本书") {
                            Icon(Icons.Outlined.Refresh, "重查本书", Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
        if (disclosable) MotionIconButton(onClick = onExpand, modifier = Modifier.size(44.dp),
            tooltip = if (expanded) "收起合并动作" else "查看合并动作") {
            Icon(Icons.Outlined.ExpandMore, if (expanded) "收起合并动作" else "查看合并动作",
                Modifier.size(16.dp).graphicsLayer { rotationZ = angle })
        }
    }
}

@Composable
private fun CopyMergeSide(
    value: String, action: String, showAction: Boolean, chosen: Boolean, enabled: Boolean, description: String,
    modifier: Modifier, sideLabel: String?, onChoose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val background by animateColorAsState(
        if (chosen) lerp(colors.surface, colors.onSurface, .085f) else colors.surface.copy(alpha = 0f),
        tween(if (motion) MangaMotion.Quick else 0), label = "sync-choice-surface")
    MotionTextButton(onClick = onChoose, enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp).semantics { selected = chosen; contentDescription = description },
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.textButtonColors(containerColor = background, contentColor = colors.onSurface,
            disabledContentColor = colors.onSurfaceVariant, disabledContainerColor = background),
        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (sideLabel != null) Text(sideLabel, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            CopyShelfReveal(showAction) {
                Text(action, style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.padding(start = 3.dp).size(14.dp), contentAlignment = Alignment.Center) {
            MotionSwap(chosen, Modifier.size(14.dp)) { checked ->
                if (checked) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp)) else Spacer(Modifier.size(14.dp))
            }
        }
    }
}
