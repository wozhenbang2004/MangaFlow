package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

/** The same list owns collection, history and folder views; navigation keeps its scroll. */
@Composable
internal fun AnimeLibrary(
    state: AnimeScreenState,
    scroll: LazyListState,
    controller: AnimeController,
    backdrop: HazeState,
    onOpen: (AnimeSubject) -> Unit,
    onResume: (AnimeLibraryEntry) -> Unit,
    onDiscover: () -> Unit,
    onReview: () -> Unit,
    onDownloads: () -> Unit,
) {
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var order by rememberSaveable { mutableIntStateOf(0) }
    var filterOpen by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val folders = state.library.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted()
    val entries = state.library.filter { entry ->
        (when (filter) { 1 -> entry.followed; 2 -> entry.resume != null; else -> entry.followed || entry.resume != null }) &&
            (folder == null || entry.folder == folder)
    }.let { rows ->
        when (order) {
            1 -> rows.sortedWith(compareByDescending<AnimeLibraryEntry> { it.pinned }.thenBy { it.subject.title })
            2 -> rows.sortedWith(compareByDescending<AnimeLibraryEntry> { it.pinned }
                .thenByDescending { it.subject.year ?: 0 }.thenBy { it.subject.title })
            else -> rows.sortedWith(compareByDescending<AnimeLibraryEntry> { it.pinned }
                .thenByDescending { it.resume?.updatedAt ?: it.updatedAt })
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionTabs(filter, listOf("全部", "收藏", "历史"), onSelect = { filter = it },
                modifier = Modifier.width(192.dp), controlHeight = 44.dp, compact = true,
                treatment = MotionTabTreatment.Soft)
            Spacer(Modifier.weight(1f))
            MotionIconButton(onClick = onDownloads, modifier = Modifier.size(44.dp), tooltip = "离线下载") {
                Icon(Icons.Outlined.DownloadForOffline, "离线下载", Modifier.size(20.dp))
            }
            Box {
                MotionIconButton(onClick = { filterOpen = true }, modifier = Modifier.size(44.dp), tooltip = "文件夹与排序") {
                    Icon(if (folder == null) Icons.Outlined.FilterList else Icons.Outlined.Folder,
                        "文件夹与排序", Modifier.size(20.dp))
                }
                CompactGlassPopover(filterOpen, backdrop, onDismiss = { filterOpen = false }) {
                    AnimeQuietLabel("文件夹", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    CompactMenuAction(if (folder == null) Icons.Outlined.Check else Icons.Outlined.Folder, "全部文件夹",
                        onClick = { folder = null; filterOpen = false })
                    CompactMenuAction(if (folder == "") Icons.Outlined.Check else Icons.Outlined.FolderOpen, "未分组",
                        onClick = { folder = ""; filterOpen = false })
                    folders.forEach { name ->
                        CompactMenuAction(if (folder == name) Icons.Outlined.Check else Icons.Outlined.Folder, name,
                            onClick = { folder = name; filterOpen = false })
                    }
                    AnimeQuietLabel("排序", Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    listOf("最近观看", "作品名称", "年份").forEachIndexed { index, label ->
                        CompactMenuAction(if (order == index) Icons.Outlined.Check else Icons.Outlined.Sort, label,
                            onClick = { order = index; filterOpen = false })
                    }
                }
            }
        }
        if (folder != null) Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AnimeQuietLabel(folder?.ifBlank { "未分组" }.orEmpty(), Modifier.weight(1f))
            MotionIconButton(onClick = { folder = null }, modifier = Modifier.size(44.dp), tooltip = "清除文件夹筛选") {
                Icon(Icons.Outlined.Close, "清除文件夹筛选", Modifier.size(16.dp))
            }
        }
        if (entries.isEmpty()) AnimeMessage(if (state.library.isEmpty()) "你的追番从这里开始" else "这里还没有番剧",
            action = if (folder == null) "去发现" else "查看全部", onAction = {
                if (folder == null) onDiscover() else folder = null
            })
        else LazyColumn(Modifier.fillMaxSize(), state = scroll,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 104.dp)) {
            if (state.reviews.isNotEmpty()) item(key = "reviews") {
                AnimeSelection("待核对 " + state.reviews.size + " 部", "状态与已看集数逐项选择", false, onReview)
            }
            items(entries, key = { it.subject.id }) { entry ->
                val subtitle = listOfNotNull(
                    "置顶".takeIf { entry.pinned },
                    animeStatusLabel(entry.status),
                    entry.folder.takeIf { it.isNotBlank() },
                    entry.resume?.let { if (it.completed) "已看完本集" else "续看 " + animeTime(it.positionMs) },
                ).joinToString(" · ")
                AnimeSubjectRow(entry.subject, subtitle, onOpen = { onOpen(entry.subject) }, trailing = {
                    if (entry.resume?.completed == false) MotionIconButton(onClick = { onResume(entry) }, modifier = Modifier.size(44.dp),
                        tooltip = "继续观看") { Icon(Icons.Outlined.PlayArrow, "继续观看", Modifier.size(23.dp)) }
                    MotionIconButton(onClick = { selectedId = entry.subject.id }, modifier = Modifier.size(44.dp),
                        tooltip = "管理这部番剧") { Icon(Icons.Outlined.MoreHoriz, "管理番剧", Modifier.size(19.dp)) }
                })
            }
        }
    }
    AnimeLibraryEntryPanel(selectedId?.let { id -> state.library.firstOrNull { it.subject.id == id } },
        controller, backdrop, folders, onDismiss = { selectedId = null })
}

/** Adapted from DetailReadingDock's material, press, progress and compact geometry. */
@Composable
internal fun AnimeResumeDock(entry: AnimeLibraryEntry, backdrop: HazeState, onResume: () -> Unit,
    onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val resume = entry.resume ?: return
    val interaction = remember(entry.subject.id) { MutableInteractionSource() }
    val motion = LocalMotionEnabled.current
    val fraction = if (resume.durationMs > 0L) (resume.positionMs.toFloat() / resume.durationMs).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(fraction,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "anime-resume-progress")
    val color = MaterialTheme.colorScheme.onSurface
    LiquidGlassSurface(backdrop, modifier.widthIn(max = 560.dp).fillMaxWidth(), cornerRadius = 26.dp,
        role = GlassSurfaceRole.READING_DOCK, interactionSource = interaction, trackPointer = true, softCard = true) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .motionPress(interaction, pressedScale = .99f)
                .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onResume)
                .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AnimePoster(entry.subject, Modifier.width(34.dp).height(46.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(entry.subject.title, style = MaterialTheme.typography.labelLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AnimeQuietLabel("继续观看 · " + animeTime(resume.positionMs))
                }
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(24.dp))
                MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "隐藏这条续看") {
                    Icon(Icons.Outlined.Close, "隐藏这条续看", Modifier.size(17.dp))
                }
            }
            if (resume.durationMs > 0L) Canvas(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 20.dp)
                .semantics { contentDescription = "观看进度 " + (fraction * 100).toInt() + "%" }) {
                drawLine(color.copy(alpha = .08f), start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
                drawLine(color.copy(alpha = .6f), start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset(size.width * progress, 0f),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun AnimeLibraryEntryPanel(entry: AnimeLibraryEntry?, controller: AnimeController,
    backdrop: HazeState, folders: List<String>, onDismiss: () -> Unit) {
    var retained by remember { mutableStateOf<AnimeLibraryEntry?>(null) }
    SideEffect { if (entry != null) retained = entry }
    val shown = entry ?: retained
    var section by remember(shown?.subject?.id) { mutableIntStateOf(0) }
    var folderDraft by remember(shown?.subject?.id) { mutableStateOf(shown?.folder.orEmpty()) }
    MotionDialog(entry != null, onDismiss, maximumWidth = 480.dp, onClosed = { retained = null }) {
        if (shown != null) LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (section != 0) MotionIconButton(onClick = { section = 0 }, modifier = Modifier.size(44.dp), tooltip = "返回管理") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                    }
                    Text(shown.subject.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    when (section) {
                        0 -> {
                            CompactMenuAction(Icons.Outlined.PushPin, if (shown.pinned) "取消置顶" else "置顶", onClick = {
                                controller.setPinned(shown.subject.id, !shown.pinned); onDismiss()
                            })
                            CompactMenuAction(Icons.Outlined.Folder, shown.folder.ifBlank { "移动到文件夹" }, onClick = { section = 1 })
                            CompactMenuAction(Icons.Outlined.BookmarkBorder, if (shown.followed) "取消收藏" else "加入收藏", onClick = {
                                controller.setFollowed(shown.subject, !shown.followed); onDismiss()
                            })
                            CompactMenuAction(Icons.Outlined.History, "清除观看位置", enabled = shown.resume != null,
                                onClick = { section = 2 })
                            CompactMenuAction(Icons.Outlined.DeleteOutline, "移除这部番剧", destructive = true,
                                onClick = { section = 3 })
                        }
                        1 -> {
                            AnimeTextField(folderDraft, { folderDraft = it }, "文件夹名称，留空放入未分组", Modifier.padding(vertical = 10.dp))
                            folders.forEach { name -> AnimeSelection(name, selected = folderDraft == name,
                                onClick = { folderDraft = name }) }
                            MotionButton(onClick = { controller.setFolder(shown.subject.id, folderDraft.trim()); onDismiss() },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 48.dp)) { Text("保存文件夹") }
                        }
                        else -> {
                            Text(if (section == 2) "清除这部番的逐集观看位置。收藏和已看标记保留。"
                                else "移除这部番的本地收藏、观看记录与来源关联。此操作不会删除追番平台的数据。",
                                Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
                            MotionButton(onClick = {
                                if (section == 2) controller.clearWatchHistory(shown.subject.id)
                                else controller.removeLibraryEntry(shown.subject.id)
                                onDismiss()
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                Text(if (section == 2) "确认清除观看位置" else "确认移除本地记录")
                            }
                        }
                    }
                }
            }
        }
    }
}
