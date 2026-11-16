package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

/** Catalogue presence, successful install and successful playback remain separate facts. */
@Composable
internal fun AnimeSourceManager(visible: Boolean, state: AnimeScreenState, controller: AnimeController,
    backdrop: HazeState, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var removeConfirm by remember { mutableStateOf(false) }
    val selected = state.sourceExtensions.firstOrNull { it.id == selectedId }
    val listings = state.sourceExtensions.filter { item ->
        when (tab) { 0 -> item.kind.name == "VIDEO"; 1 -> item.kind.name == "BROWSE"; else -> item.kind.name !in setOf("VIDEO", "BROWSE") }
    }
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 640.dp,
        onClosed = { selectedId = null; removeConfirm = false }) {
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (selected != null) MotionIconButton(onClick = { selectedId = null; removeConfirm = false },
                        modifier = Modifier.size(44.dp), tooltip = "返回来源目录") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                    }
                    Text(selected?.name ?: "来源管理", Modifier.weight(1f).padding(start = if (selected == null) 8.dp else 0.dp),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭来源管理") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                if (selected == null) {
                    MotionTabs(tab, listOf("视频", "浏览", "其他"), onSelect = { tab = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), controlHeight = 44.dp,
                        treatment = MotionTabTreatment.Soft)
                    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (tab == 0) item {
                            AnimeSectionTitle("内置来源", "TvTFun · AGE · GiriGiriLove · LMM")
                            AnimeQuietLabel("扩展脚本与内置来源共同搜索，实际播放在选集时确认。", Modifier.padding(bottom = 12.dp))
                        }
                        items(listings, key = { it.id }) { item ->
                            val busy = item.id in state.sourceOperationIds
                            AnimeSelection(item.name, when {
                                busy -> "正在处理"
                                item.error != null -> item.error
                                item.installed -> (if (item.enabled) "已启用" else "已暂停") + " · " + item.version
                                item.kind.name == "VIDEO" -> "可安装的视频来源"
                                item.kind.name == "BROWSE" -> "浏览资料 · 可安装"
                                item.kind.name == "DANMAKU" -> "弹幕匹配 · 可安装"
                                else -> "目录条目 · " + animeExtensionKind(item.kind.name)
                            }, selected = item.installed && item.enabled, onClick = {
                                selectedId = item.id; removeConfirm = false
                            })
                        }
                        if (listings.isEmpty()) item { AnimeMessage("这一类暂无来源") }
                    }
                    if (tab == 0) MotionButton(onClick = controller::installVideoSources,
                        enabled = state.sourceOperationIds.isEmpty() && listings.any { it.installable && !it.installed },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 48.dp)) {
                        Text(if (state.sourceOperationIds.isEmpty()) "安装视频来源" else "正在安装来源")
                    }
                } else {
                    val busy = selected.id in state.sourceOperationIds
                    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            AnimeQuietLabel(animeExtensionKind(selected.kind.name) + if (selected.installed) " · " + selected.version else "")
                            if (selected.aliases.isNotEmpty()) AnimeQuietLabel(selected.aliases.joinToString(" · "))
                            if (selected.installed && selected.digest.isNotBlank()) AnimeQuietLabel("脚本版本 " + selected.digest.take(12))
                        }
                        val declared = selected.capabilities.mapNotNull(::animeExtensionCapability).distinct()
                        if (declared.isNotEmpty()) item {
                            AnimeQuietLabel("脚本声明 · " + declared.joinToString(" · "))
                        }
                        val search = state.sourceResults.firstOrNull { it.providerId == selected.id }
                        val catalogueError = state.sourceCatalogErrors[selected.id]
                        if (search != null || catalogueError != null) item {
                            AnimeQuietLabel(when {
                                catalogueError != null -> "最近目录读取 · " + catalogueError
                                search?.loading == true -> "正在搜索"
                                search?.error != null -> "最近搜索 · " + search.error
                                else -> "最近搜索取得 " + search?.items.orEmpty().size + " 个候选"
                            })
                        }
                        if (selected.error != null) item {
                            Text(selected.error.orEmpty(), color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (removeConfirm) item {
                            Text("移除已安装脚本。作品关联与观看记录保留，可重新安装后继续。", style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                MotionTextButton(onClick = { removeConfirm = false }) { Text("取消") }
                                MotionButton(onClick = { controller.removeSource(selected.id); selectedId = null; removeConfirm = false },
                                    enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("确认移除") }
                            }
                        } else if (selected.installed) {
                            item {
                                SettingsToggle("启用这个来源", selected.enabled, Icons.Outlined.PowerSettingsNew,
                                    onCheckedChange = { if (!busy) controller.enableSource(selected.id, it) })
                            }
                            item { CompactMenuAction(Icons.Outlined.Refresh, "检查并更新脚本", enabled = !busy,
                                onClick = { controller.updateSource(selected.id) }) }
                            if (selected.previousVersion != null) item {
                                CompactMenuAction(Icons.Outlined.Undo, "恢复上一个脚本版本", enabled = !busy,
                                    onClick = { controller.rollbackSource(selected.id) })
                            }
                            item { CompactMenuAction(Icons.Outlined.DeleteOutline, "移除脚本", enabled = !busy,
                                destructive = true, onClick = { removeConfirm = true }) }
                        } else if (selected.installable) item {
                            MotionButton(onClick = { controller.installSource(selected.id) }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (busy) "正在安装" else "安装来源")
                            }
                        } else item {
                            AnimeQuietLabel(selected.error ?: "这种来源目前不能安装。")
                        }
                        item {
                            AnimeQuietLabel(selected.scriptUrl)
                        }
                    }
                }
                if (state.notice != null) Text(state.notice, Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun animeExtensionKind(kind: String): String = when (kind) {
    "VIDEO" -> "视频来源"
    "BROWSE" -> "浏览资料"
    "COMIC" -> "漫画来源"
    "DANMAKU" -> "弹幕来源"
    else -> "类型尚未确认"
}

private fun animeExtensionCapability(function: String): String? = when (function) {
    "search" -> "关键词搜索"
    "searchFiltered", "categories" -> "分类筛选"
    "homeSections" -> "发现内容"
    "detail" -> "作品资料"
    "related" -> "相关推荐"
    "play" -> "播放解析"
    "danmu" -> "弹幕"
    "login" -> "来源登录"
    else -> null
}
