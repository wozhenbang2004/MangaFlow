package dev.mangaflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.account.AdultProgressPreference
import dev.mangaflow.account.AdultProgressSyncController
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.storage.AdultProgressWork
import dev.mangaflow.storage.ChapterProgress
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdultProgressSyncSheet(
    controller: AdultProgressSyncController, backdrop: HazeState, sourceName: (String) -> String,
    onDismiss: () -> Unit, onConfigure: () -> Unit,
) {
    val state by controller.state.collectAsStateWithLifecycle()
    val configuration by controller.configuration.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var choices by remember(state.revision) { mutableStateOf<Map<String, AdultProgressPreference>>(emptyMap()) }
    var expanded by remember(state.revision) { mutableStateOf<String?>(null) }
    var configureAfterExit by remember { mutableStateOf(false) }
    val canApply = state.compared && !state.busy && configuration.configured

    MotionBottomSheet(backdrop = backdrop, onDismissRequest = {
        controller.dismiss()
        onDismiss()
        if (configureAfterExit) onConfigure()
    }) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 64.dp).coerceAtLeast(200.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("同步成人阅读进度", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (configuration.configured && !state.busy && (state.compared || state.completed != null)) MotionIconButton(onClick = controller::compare,
                    tooltip = "重新比较阅读进度", modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Refresh, "重新比较", Modifier.size(18.dp))
                }
                MotionIconButton(onClick = dismiss, tooltip = "关闭", modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(18.dp))
                }
            }
            if (!configuration.configured) {
                Text("配置 WebDAV 后，可以跨设备同步章节、来源版本、页码和页内位置。",
                    fontSize = 12.sp, color = colors.onSurfaceVariant)
                MotionButton(onClick = { configureAfterExit = true; dismiss() }, enabled = !state.busy) {
                    Text("配置 WebDAV", fontSize = 12.sp)
                }
            } else if (state.busy) QuietLoading(
                if (state.applying) "正在保存已确认的位置…" else "正在比较本机和 WebDAV…")
            state.error?.let { Text(it, fontSize = 12.sp, color = colors.error) }
            state.completed?.let { Text(it, fontSize = 12.sp, color = colors.onSurfaceVariant) }
            if (state.unavailableWorks > 0)
                Text("远端有 ${state.unavailableWorks} 部作品需要当前设备识别其成人来源，已原样保留。",
                    fontSize = 11.sp, color = colors.onSurfaceVariant)
            if (state.compared && state.differences.isEmpty()) {
                Text("可同步的阅读位置已一致。", fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            if (state.compared && state.differences.isNotEmpty()) {
                Text("所选作品会合并独有章节。相同版本的冲突和继续阅读位置按选择处理。",
                    fontSize = 11.sp, color = colors.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.differences.forEach { difference ->
                        val local = difference.device
                        val remote = difference.webdav
                        val chapters = local?.chapters.orEmpty()
                        val other = remote?.chapters.orEmpty()
                        val differingIds = difference.chapterDifferences
                        item(key = "work:${difference.workId}") {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(difference.title, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("本机  ${progressResume(local, sourceName)}", fontSize = 12.sp, color = colors.onSurfaceVariant)
                                Text("WebDAV  ${progressResume(remote, sourceName)}", fontSize = 12.sp, color = colors.onSurfaceVariant)
                                if (differingIds.isNotEmpty()) MotionTextButton(
                                    onClick = { expanded = if (expanded == difference.workId) null else difference.workId },
                                    modifier = Modifier.heightIn(min = 44.dp)) {
                                    Text(if (expanded == difference.workId) "收起章节位置" else "对照 ${differingIds.size} 个章节版本",
                                        fontSize = 11.sp)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val available = buildList {
                                        if (local != null) add(AdultProgressPreference.DEVICE to
                                            (if (remote == null) "同步到 WebDAV" else "冲突时用本机"))
                                        if (remote != null) add(AdultProgressPreference.WEBDAV to
                                            (if (local == null) "导入本机" else "冲突时用 WebDAV"))
                                    }
                                    available.forEach { (side, label) ->
                                        val selected = choices[difference.workId] == side
                                        MotionTextButton(enabled = canApply, modifier = Modifier.heightIn(min = 44.dp)
                                            .semantics { this.selected = selected }, onClick = {
                                            choices = if (selected) choices - difference.workId else choices + (difference.workId to side)
                                        }) {
                                            if (selected) {
                                                Icon(Icons.Outlined.Check, null, Modifier.size(13.dp))
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(label, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                        if (expanded == difference.workId) items(differingIds,
                            key = { "position:${difference.workId}:$it" }) { chapterId ->
                            Column(Modifier.fillMaxWidth().padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                val source = sourceName(SourceKeys.decodeChapter(difference.workId, chapterId).sourceId)
                                val title = (chapters[chapterId] ?: other[chapterId])?.chapterTitle.orEmpty().ifBlank { "章节" }
                                Text("$source · $title", fontSize = 12.sp)
                                Text("本机  ${positionText(chapters[chapterId])}", fontSize = 11.sp, color = colors.onSurfaceVariant)
                                Text("WebDAV  ${positionText(other[chapterId])}", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MotionButton(onClick = { controller.apply(state.revision, choices) },
                        enabled = canApply && choices.isNotEmpty()) {
                        Text("同步 ${choices.size} 部作品", fontSize = 12.sp)
                    }
                }
            } else if (configuration.configured && !state.busy && state.completed == null) {
                MotionTextButton(onClick = controller::compare) { Text("比较阅读进度", fontSize = 12.sp) }
            }
        }
    }
}

private fun progressResume(work: AdultProgressWork?, sourceName: (String) -> String): String {
    if (work == null) return "无记录"
    val chapterId = work.recentChapterId ?: return "${work.chapters.size} 个章节位置"
    val chapter = work.chapters.getValue(chapterId)
    val source = sourceName(SourceKeys.decodeChapter(work.manga.id, chapterId).sourceId)
    return listOf(source, chapter.chapterTitle, positionText(chapter)).filter(String::isNotBlank).joinToString(" · ")
}

private fun positionText(position: ChapterProgress?): String {
    if (position == null) return "无记录"
    return buildList {
        add("第 ${position.pageIndex + 1}/${position.pageCount} 页")
        if (position.scrollOffset > 0) add("页内 ${position.scrollOffset} px")
        position.legacyLongImageFraction?.let { add("长图 ${(it * 100).toInt()}%") }
        if (position.panelIndex >= 0) add("画格 ${position.panelIndex + 1}")
    }.joinToString(" · ")
}
