package dev.mangaflow.ui

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.account.CopyMergeChoice
import dev.mangaflow.account.CopyShelfSyncState
import dev.mangaflow.account.CopySyncDifference
import dev.mangaflow.account.CopySyncDifferenceKind
import dev.mangaflow.ui.motion.*

/** One actual comparison, no automatic winners, and one explicit selected merge. */
@Composable
internal fun CopyShelfSyncSheet(
    visible: Boolean,
    state: CopyShelfSyncState,
    backdrop: HazeState,
    onCheck: () -> Unit,
    onImport: () -> Unit,
    onApply: (Long, Map<String, CopyMergeChoice>) -> Unit,
    onDismiss: () -> Unit,
    focusMangaId: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    var choices by remember(state.owner, state.revision) {
        mutableStateOf<Map<String, CopyMergeChoice>>(emptyMap())
    }
    var filter by remember(state.owner) { mutableIntStateOf(0) }
    var showScope by remember(state.owner) { mutableStateOf(false) }
    val list = key(state.owner) { rememberLazyListState() }
    LaunchedEffect(visible, focusMangaId) {
        if (visible && focusMangaId != null) { filter = 2; list.scrollToItem(0) }
    }
    val canAct = visible && state.owner != null && state.checked && !state.busy && state.error == null
    val favorites = state.differences.count { it.kind == CopySyncDifferenceKind.FAVORITE }
    val progress = state.differences.count { it.kind == CopySyncDifferenceKind.PROGRESS }
    val shown = state.differences.sortedBy { if (it.manga.id == focusMangaId) 0 else 1 }.filter {
        filter == 0 || filter == 1 && it.kind == CopySyncDifferenceKind.FAVORITE ||
            filter == 2 && it.kind == CopySyncDifferenceKind.PROGRESS
    }
    val selected = state.differences.filter { it.key in choices }
    val actions = selected.groupingBy {
        if (choices[it.key] == CopyMergeChoice.USE_LOCAL) it.localActionLabel else it.cloudActionLabel
    }.eachCount().entries.joinToString(" · ") { it.key + " " + it.value }
    key(state.owner) {
        MotionDialog(visible, onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false),
            maximumWidth = 600.dp) {
            BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
                val height = minOf(maxHeight, LocalMangaWindow.current.height * .88f, 580.dp)
                LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                    role = GlassSurfaceRole.PANEL, elevation = 0.dp) {
                    Column(Modifier.fillMaxWidth().height(height).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("拷贝差异", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            MotionIconButton(onClick = { showScope = !showScope }, enabled = visible,
                                modifier = Modifier.size(44.dp), tooltip = "合并范围") {
                                Icon(Icons.Outlined.Info, "合并范围", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                            }
                            if (state.busy) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                MotionBusyIndicator(Modifier.size(17.dp), strokeWidth = 1.6.dp)
                            } else MotionIconButton(onClick = onCheck, enabled = visible && state.owner != null,
                                modifier = Modifier.size(44.dp), tooltip = "重新检查差异") {
                                Icon(Icons.Outlined.Refresh, "重新检查差异", Modifier.size(18.dp))
                            }
                            MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭差异") {
                                Icon(Icons.Outlined.Close, "关闭差异", Modifier.size(20.dp))
                            }
                        }
                        MotionTabs(filter, listOf("全部 " + state.differences.size, "收藏 " + favorites, "进度 " + progress),
                            onSelect = { if (visible) filter = it }, modifier = Modifier.fillMaxWidth(),
                            treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 40.dp,
                            track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                        LazyColumn(Modifier.weight(1f).fillMaxWidth().motionInputGate(visible), state = list,
                            verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                            if (showScope) item(key = "scope") {
                                Text("左侧本机，右侧拷贝。逐项选择处理方式，再合并所选；再次点击可取消选择。阅读仅比较章节，本机各章页码保留；保留本机不会上报阅读。",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            if (state.progressUnavailableCount > 0) item(key = "unknown-progress") {
                                Text("另有 " + state.progressUnavailableCount + " 本进度尚不可比较",
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            if (progress > 0 && filter != 1) item(key = "progress-scope") {
                                Text("进度按章节比较，本机页码保留。",
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            if (state.busy) item(key = "busy") {
                                Text(when {
                                    state.importing -> "正在完整导入收藏…"
                                    state.uploading -> "正在应用所选更改…"
                                    else -> "正在检查差异…"
                                }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            state.error?.let { error -> item(key = "error") {
                                Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                            } }
                            state.message?.takeIf(String::isNotBlank)?.let { message -> item(key = "message") {
                                Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            } }
                            when {
                                state.owner == null -> item(key = "login") { Text("请先连接拷贝账号。") }
                                !state.checked -> item(key = "unchecked") {
                                    Text("检查完成后显示本机与拷贝的差异。", style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant)
                                }
                                else -> {
                                    if (!state.hasFullImport) item(key = "full-import") {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("先完整导入收藏，才可向拷贝提交收藏增删。原有书架与阅读记录保留。",
                                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                            MotionTextButton(onClick = onImport, enabled = canAct) {
                                                Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(15.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("完整导入收藏 · " + state.cloudFavorites.size + " 本")
                                            }
                                        }
                                    }
                                    items(shown, key = { it.key }, contentType = { "copy-difference" }) { difference ->
                                        CopyDifferenceRow(difference, choices[difference.key], canAct,
                                            Modifier.animateItem(
                                                fadeInSpec = if (motion) tween(MangaMotion.ListEnter) else null,
                                                placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                                fadeOutSpec = if (motion) tween(MangaMotion.ListExit) else null),
                                            showDetail = state.hasFullImport && (!difference.canUseLocal || !difference.canUseCopy)) { choice ->
                                            if (canAct) choices = if (choices[difference.key] == choice)
                                                choices - difference.key else choices + (difference.key to choice)
                                        }
                                    }
                                    if (shown.isEmpty() && state.error == null && !state.busy) item(key = "empty") {
                                        Text(if (state.differences.isEmpty())
                                            if (state.progressUnavailableCount > 0) "已知项目暂无待处理差异" else "暂无待处理差异"
                                            else "这一类暂无差异",
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (state.localOnlyCount > 0) item(key = "local-only") {
                                        Text("仅本机 " + state.localOnlyCount + " 本",
                                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (selected.isNotEmpty()) Text(actions, Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        MotionButton(onClick = {
                            if (canAct && selected.isNotEmpty()) onApply(state.revision, choices.toMap())
                        }, enabled = canAct && selected.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(if (selected.isEmpty()) "选择合并方式" else "合并所选 " + selected.size + " 项")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyDifferenceRow(
    difference: CopySyncDifference,
    choice: CopyMergeChoice?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    showDetail: Boolean = true,
    onChoose: (CopyMergeChoice) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(if (difference.kind == CopySyncDifferenceKind.FAVORITE) Icons.Outlined.BookmarkBorder
                else Icons.Outlined.AutoStories, if (difference.kind == CopySyncDifferenceKind.FAVORITE) "收藏差异" else "阅读进度差异",
                Modifier.size(15.dp), tint = colors.onSurfaceVariant)
            Text(difference.manga.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CopyDifferenceValue("本机", difference.localValue, Modifier.weight(1f))
            CopyDifferenceValue("拷贝", difference.cloudValue, Modifier.weight(1f))
        }
        difference.detail?.takeIf { showDetail && it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CopyMergeButton(difference.localActionLabel, choice == CopyMergeChoice.USE_LOCAL,
                enabled && difference.canUseLocal, Modifier.weight(1f)) { onChoose(CopyMergeChoice.USE_LOCAL) }
            CopyMergeButton(difference.cloudActionLabel, choice == CopyMergeChoice.USE_COPY,
                enabled && difference.canUseCopy, Modifier.weight(1f)) { onChoose(CopyMergeChoice.USE_COPY) }
        }
    }
}

@Composable
private fun CopyDifferenceValue(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CopyMergeButton(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 44.dp).semantics { this.selected = selected },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, "已选择", Modifier.size(15.dp), tint = colors.primary)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = if (!enabled) colors.onSurfaceVariant.copy(alpha = .4f)
                else if (selected) colors.primary else colors.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
