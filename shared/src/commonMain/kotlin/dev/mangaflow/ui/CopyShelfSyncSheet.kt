package dev.mangaflow.ui

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

/** Adapt the existing Copy/Ovio workspace: one work, two sides, explicit staged changes. */
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
    var filter by remember(state.owner) { mutableIntStateOf(0) }
    var onlySelected by remember(state.owner) { mutableStateOf(false) }
    var showScope by remember(state.owner) { mutableStateOf(false) }
    var bulkOpen by remember(state.owner) { mutableStateOf(false) }
    val allList = key(state.owner) { rememberLazyListState() }
    val favoriteList = key(state.owner) { rememberLazyListState() }
    val progressList = key(state.owner) { rememberLazyListState() }
    val selectedList = key(state.owner) { rememberLazyListState() }
    val rowsByKey = remember(state.differences) { state.differences.associateBy { it.key } }
    val selected = state.differences.filter { difference ->
        choices[difference.key]?.let { it.evidence == difference.reviewKey && difference.allows(it.choice) } == true
    }
    val selectedKeys = selected.mapTo(mutableSetOf()) { it.key }
    // A refresh can temporarily withhold evidence. Reconcile after it settles, per field.
    LaunchedEffect(state.owner, state.differences, state.busy) {
        if (!state.busy) {
            choices = choices.filter { (key, staged) ->
                rowsByKey[key]?.let { it.reviewKey == staged.evidence && it.allows(staged.choice) } == true
            }
            if (choices.isEmpty()) onlySelected = false
        }
    }
    LaunchedEffect(visible, focusMangaId) {
        if (visible && focusMangaId != null) {
            filter = 2
            onlySelected = false
            progressList.scrollToItem(0)
        }
        if (!visible) bulkOpen = false
    }
    val canChoose = visible && state.owner != null && state.checked && !state.busy
    val scoped = state.differences.filter {
        filter == 0 || filter == 1 && it.kind == CopySyncDifferenceKind.FAVORITE ||
            filter == 2 && it.kind == CopySyncDifferenceKind.PROGRESS
    }
    val bulkRows = if (onlySelected) scoped.filter { it.key in selectedKeys } else scoped
    val favorites = state.differences.count { it.kind == CopySyncDifferenceKind.FAVORITE }
    val progress = state.differences.count { it.kind == CopySyncDifferenceKind.PROGRESS }
    val summary = selected.groupingBy { difference ->
        if (choices[difference.key]?.choice == CopyMergeChoice.USE_LOCAL) difference.localActionLabel else difference.cloudActionLabel
    }.eachCount().entries.joinToString(" · ") { it.key + " " + it.value + " 项" }
    val workingLabel = when {
        state.importing -> "正在导入收藏…"
        state.uploading && state.mergeTotal > 0 -> "已处理 " + state.mergeCompleted + " / " + state.mergeTotal + " 项"
        state.uploading -> "正在核对所选项目…"
        state.checking -> "正在读取拷贝…"
        else -> null
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
        bulkOpen = false
    }

    key(state.owner) {
        MotionDialog(visible, onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false),
            maximumWidth = 640.dp) {
            BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
                val height = minOf(maxHeight, LocalMangaWindow.current.height * .88f, 680.dp)
                LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                    role = GlassSurfaceRole.PANEL, elevation = 0.dp) {
                    // Natural height for a short comparison; only the list scrolls at the cap.
                    Column(Modifier.fillMaxWidth().heightIn(max = height)
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("拷贝同步", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (state.busy) MotionIconButton(onClick = onCancel, enabled = visible,
                                modifier = Modifier.size(44.dp), tooltip = "停止本次处理") {
                                Icon(Icons.Outlined.Stop, "停止本次处理", Modifier.size(18.dp))
                            } else MotionIconButton(onClick = onCheck, enabled = visible && state.owner != null,
                                modifier = Modifier.size(44.dp), tooltip = "重新检查差异") {
                                Icon(Icons.Outlined.Refresh, "重新检查差异", Modifier.size(18.dp))
                            }
                            MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭对照") {
                                Icon(Icons.Outlined.Close, "关闭对照", Modifier.size(20.dp))
                            }
                        }
                        MotionTabs(filter, listOf("全部 " + state.differences.size,
                            if (state.favoritesReady) "收藏 " + favorites else "收藏 —",
                            if (state.progressReady || progress > 0) "进度 " + progress else "进度 —"),
                            onSelect = { if (visible) { filter = it; onlySelected = false; bulkOpen = false } },
                            modifier = Modifier.fillMaxWidth(), treatment = MotionTabTreatment.Underline,
                            controlHeight = 40.dp, fontSize = 12.sp)
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selected.isNotEmpty() || onlySelected) MotionTextButton(
                                onClick = { if (visible) { if (!onlySelected) filter = 0; onlySelected = !onlySelected } }, enabled = visible,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                            ) {
                                Icon(if (onlySelected) Icons.Outlined.CheckCircle else Icons.Outlined.Checklist,
                                    null, Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("已选 " + selected.size, fontSize = 12.sp)
                            } else Text(scoped.map { it.manga.id }.distinct().size.toString() + " 部作品",
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Box {
                                MotionTextButton(onClick = { if (visible) bulkOpen = !bulkOpen }, enabled = visible,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                    Text("批量", fontSize = 12.sp)
                                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                                }
                                CompactGlassPopover(bulkOpen && visible, backdrop, onDismiss = { bulkOpen = false },
                                    maximumWidth = 256.dp) {
                                    CompactMenuAction(Icons.Outlined.DoneAll, "当前列表均选本机",
                                        enabled = canChoose && bulkRows.any { it.allows(CopyMergeChoice.USE_LOCAL) }) {
                                        chooseShown(CopyMergeChoice.USE_LOCAL)
                                    }
                                    CompactMenuAction(Icons.Outlined.CloudDone, "当前列表均选拷贝",
                                        enabled = canChoose && bulkRows.any { it.allows(CopyMergeChoice.USE_COPY) }) {
                                        chooseShown(CopyMergeChoice.USE_COPY)
                                    }
                                    CompactMenuAction(Icons.Outlined.ClearAll, "清空选择", enabled = choices.isNotEmpty() && !state.busy) {
                                        choices = emptyMap(); bulkOpen = false
                                    }
                                    CompactMenuAction(Icons.Outlined.Info, "同步范围与说明") {
                                        showScope = !showScope; bulkOpen = false
                                    }
                                }
                            }
                        }
                        if (scoped.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Spacer(Modifier.width(32.dp))
                            Text("本机", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant)
                            Text("拷贝", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant)
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
                            LazyColumn(Modifier.fillMaxWidth().motionInputGate(visible && target == scene), state = list,
                                contentPadding = PaddingValues(bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (workingLabel != null || state.error != null || state.message != null ||
                                    showScope || state.progressUnavailableCount > 0 && kindFilter != 1 ||
                                    !state.hasFullImport && favorites > 0 && kindFilter != 2 ||
                                    state.favoritesError != null && kindFilter != 2 || state.progressError != null && kindFilter != 1)
                                    item(key = "read-state") {
                                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            if (kindFilter != 2) CopySyncReadIssue("收藏", state.favoritesError, visible && !state.busy) {
                                                onRetry(CopySyncDifferenceKind.FAVORITE)
                                            }
                                            if (kindFilter != 1) CopySyncReadIssue("进度", state.progressError, visible && !state.busy) {
                                                onRetry(CopySyncDifferenceKind.PROGRESS)
                                            }
                                            workingLabel?.let { label ->
                                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                                    MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.4.dp)
                                                    Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                }
                                            }
                                            if (state.error != null) Text(state.error, Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                style = MaterialTheme.typography.bodySmall, color = colors.error, maxLines = 3,
                                                overflow = TextOverflow.Ellipsis)
                                            if (state.message != null) Text(state.message, Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 3,
                                                overflow = TextOverflow.Ellipsis)
                                            if (!state.hasFullImport && favorites > 0 && kindFilter != 2) Row(
                                                Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                                Text("修改拷贝收藏前，先导入现有收藏", Modifier.weight(1f),
                                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                MotionTextButton(onClick = onImport, enabled = canChoose && state.favoritesReady,
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                                                    Text("导入", fontSize = 12.sp)
                                                }
                                            }
                                            CopyShelfReveal(showScope) {
                                                Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text("收藏按所选一侧调整。进度只决定本机从哪章继续，不回写拷贝；本机页内位置、历史与下载保留。",
                                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                                    if (state.progressUnavailableCount > 0) Text(
                                                        "另有 " + state.progressUnavailableCount + " 部未取得可比较进度，不视为未读。",
                                                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                    if (state.localOnlyCount > 0) Text(
                                                        "仅本机的 " + state.localOnlyCount + " 部作品不参与拷贝同步。",
                                                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                                }
                                            }
                                            if (!showScope && state.progressUnavailableCount > 0 && kindFilter != 1) MotionTextButton(
                                                onClick = { showScope = true }, enabled = visible,
                                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 3.dp)) {
                                                Text(state.progressUnavailableCount.toString() + " 部进度未取得", fontSize = 11.sp,
                                                    color = colors.onSurfaceVariant)
                                                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                                            }

                                        }
                                    }
                                items(groups, key = { it.key }, contentType = { "copy-work-diff" }) { group ->
                                    CopyWorkDifference(group.value, choices, canChoose && target == scene, repository,
                                        Modifier.animateItem(
                                            fadeInSpec = if (motion) tween(MangaMotion.ListEnter) else null,
                                            placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                            fadeOutSpec = if (motion) tween(MangaMotion.ListExit) else null),
                                        onChoose = ::choose,
                                        onRetry = onRetry, onRetryWork = onRetryWork)
                                }
                                if (groups.isEmpty()) item(key = "empty") {
                                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(when {
                                            state.owner == null -> "请先连接拷贝账号"
                                            state.busy -> "已有内容会在读取后显示"
                                            selectedOnly -> "当前筛选中还没有选择"
                                            !state.favoritesReady && !state.progressReady -> "暂未取得可比较的数据"
                                            state.differences.isNotEmpty() -> "这一类暂无待处理项"
                                            state.favoritesReady && state.progressReady -> "当前可比较的项目已处理"
                                            else -> "已读取的部分暂无差异"
                                        }, style = MaterialTheme.typography.bodyMedium)
                                        if (!state.busy && (!state.favoritesReady || !state.progressReady))
                                            Text("未读取的内容不会当作未收藏或未读。可在上方重试。",
                                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        val hint = workingLabel ?: summary.ifBlank {
                            if (state.owner == null) "连接账号后再检查" else "点选要采用的一侧"
                        }
                        val applyButton: @Composable (Modifier) -> Unit = { modifier ->
                            MotionButton(onClick = {
                                if (canChoose && selected.isNotEmpty()) onApply(state.revision,
                                    selected.associate { it.key to requireNotNull(choices[it.key]).choice })
                            }, enabled = canChoose && selected.isNotEmpty(), modifier = modifier.heightIn(min = 44.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp)) {
                                Text(if (state.uploading && state.mergeTotal > 0)
                                    state.mergeCompleted.toString() + " / " + state.mergeTotal
                                else "应用" + if (selected.isEmpty()) "" else " " + selected.size + " 项",
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (LocalDensity.current.fontScale > 1.35f) Column(
                            Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(hint, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                maxLines = 4, overflow = TextOverflow.Ellipsis)
                            applyButton(Modifier.fillMaxWidth())
                        } else Row(Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(hint, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            applyButton(Modifier.widthIn(min = 100.dp))
                        }
                    }
                }
            }
        }
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
                Text(message, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            MotionTextButton(onClick = onRetry, enabled = enabled,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) { Text("重试", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun CopyWorkDifference(
    fields: List<CopySyncDifference>,
    choices: Map<String, CopyReviewChoice>,
    enabled: Boolean,
    repository: MangaRepository,
    modifier: Modifier = Modifier,
    onChoose: (CopySyncDifference, CopyMergeChoice) -> Unit,
    onRetry: (CopySyncDifferenceKind) -> Unit,
    onRetryWork: (Manga) -> Unit,
) {
    val manga = fields.first().manga
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            MangaCover(manga, repository, Modifier.size(width = 24.dp, height = 34.dp).mangaCoverCard(5.dp))
            Text(manga.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        for (field in fields) {
            val staged = choices[field.key]?.takeIf { it.evidence == field.reviewKey }?.choice
            val localValue = if (field.kind == CopySyncDifferenceKind.PROGRESS) copyProgressDisplay(field.localValue) else field.localValue
            val cloudValue = if (field.kind == CopySyncDifferenceKind.PROGRESS) copyProgressDisplay(field.cloudValue) else field.cloudValue
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (field.kind == CopySyncDifferenceKind.FAVORITE) "收藏" else "进度", Modifier.width(32.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                CopyMergeSide(localValue, field.localActionLabel,
                    staged == CopyMergeChoice.USE_LOCAL, enabled && field.allows(CopyMergeChoice.USE_LOCAL),
                    manga.title + "，本机，" + localValue + "，" + field.localActionLabel, Modifier.weight(1f)) {
                    onChoose(field, CopyMergeChoice.USE_LOCAL)
                }
                CopyMergeSide(cloudValue, field.cloudActionLabel,
                    staged == CopyMergeChoice.USE_COPY, enabled && field.allows(CopyMergeChoice.USE_COPY),
                    manga.title + "，拷贝，" + cloudValue + "，" + field.cloudActionLabel, Modifier.weight(1f)) {
                    onChoose(field, CopyMergeChoice.USE_COPY)
                }
            }
            if (!field.canUseLocal && !field.canUseCopy) Row(
                Modifier.fillMaxWidth().padding(start = 38.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(field.detail ?: "这一项需重新读取后再选择", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                MotionTextButton(onClick = {
                    if (field.kind == CopySyncDifferenceKind.PROGRESS) onRetryWork(field.manga) else onRetry(field.kind)
                }, enabled = enabled, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 3.dp)) {
                    Text("重查本书", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CopyMergeSide(
    value: String, action: String, chosen: Boolean, enabled: Boolean, description: String,
    modifier: Modifier, onChoose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = onChoose, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            selected = chosen
            contentDescription = description
        }, shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (chosen) colors.onSurface.copy(alpha = .075f) else colors.onSurface.copy(alpha = .025f),
            contentColor = colors.onSurface, disabledContentColor = colors.onSurfaceVariant.copy(alpha = .55f),
            disabledContainerColor = colors.onSurface.copy(alpha = .02f),
        ), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(action, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = if (enabled) .8f else .55f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (chosen) {
            Spacer(Modifier.width(3.dp))
            Icon(Icons.Outlined.Check, "已选择", Modifier.size(14.dp))
        }
    }
}
