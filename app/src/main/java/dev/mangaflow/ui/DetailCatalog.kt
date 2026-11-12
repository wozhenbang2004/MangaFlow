package dev.mangaflow.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Chapter
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlinx.coroutines.launch

/**
 * The catalog owns one stable, lazily-composed chapter grid. Filters and jumps live in
 * an overlay drawer so switching download mode never inserts or removes a layout row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DetailCatalog(
    mangaId: String,
    chapters: List<Chapter>,
    downloaded: Map<String, DownloadEntry>,
    progress: ReadingProgress?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onRead: (Chapter) -> Unit,
    onDownload: (Chapter) -> Unit,
) {
    val groups = remember(chapters) { chapters.groupingBy(Chapter::group).eachCount() }
    var chosenGroup by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    val group = chosenGroup?.takeIf { it.isEmpty() || it in groups }.orEmpty()
    var descending by rememberSaveable(mangaId) { mutableStateOf(false) }
    var query by rememberSaveable(mangaId) { mutableStateOf("") }
    var anchorId by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var downloadMode by rememberSaveable(mangaId) { mutableStateOf(false) }
    var shownError by remember { mutableStateOf<String?>(null) }
    var selectedChapterId by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var closingChapter by remember(mangaId) { mutableStateOf<Chapter?>(null) }

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val grid = rememberLazyGridState()
    val showBusy = rememberDelayedBusy(loading)
    val shown = remember(chapters, group, query, descending) {
        chapters.filter {
            (group.isEmpty() || it.group == group) &&
                (query.isBlank() || it.title.contains(query.trim(), ignoreCase = true))
        }.let { if (descending) it.asReversed() else it }
    }
    val savedChapter = chapters.firstOrNull { it.id == progress?.chapterId }

    // Restore the same grid position after visiting Comments or the reader.
    // Only an actual filter/order change returns to the start of the directory.
    val filterSignature = group + "\u001f" + descending + "\u001f" + query
    var appliedFilter by rememberSaveable(mangaId) { mutableStateOf(filterSignature) }
    LaunchedEffect(filterSignature) {
        if (appliedFilter != filterSignature) {
            grid.scrollToItem(0)
            appliedFilter = filterSignature
        }
    }
    LaunchedEffect(anchorId, shown) {
        val target = anchorId
        if (target != null) {
            val index = shown.indexOfFirst { it.id == target }
            if (index >= 0) grid.scrollToItem(index)
            anchorId = null
        }
    }

    val openChapter: (Chapter) -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
        onRead(it)
    }
    val closeDrawer: () -> Unit = { scope.launch { drawer.close() }; Unit }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(290.dp),
                drawerContainerColor = DetailSurface,
                drawerContentColor = DetailWhite,
                drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
            ) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 22.dp, end = 6.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("目录工具", color = DetailWhite, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        DetailAction(Icons.Outlined.Close, "关闭目录工具", onClick = closeDrawer)
                    }
                    ChapterSearch(query = query, onChange = { query = it }, onClear = { query = "" })
                    Text("排列", color = DetailMuted, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 6.dp))
                    DrawerChoice(
                        label = if (descending) "从新到旧" else "从旧到新",
                        selected = false,
                        icon = if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                        onClick = { descending = !descending },
                    )
                    if (savedChapter != null) {
                        DrawerChoice(
                            label = "定位上次阅读",
                            selected = false,
                            icon = Icons.Outlined.MyLocation,
                            onClick = {
                                chosenGroup = savedChapter.group
                                query = ""
                                anchorId = savedChapter.id
                                keyboard?.hide()
                                focusManager.clearFocus()
                                closeDrawer()
                            },
                        )
                    }
                    Text("章节分组", color = DetailMuted, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 6.dp))
                    (listOf("" to chapters.size) + groups.entries.map { it.key to it.value }).forEach { (name, count) ->
                        DrawerChoice(
                            label = name.ifBlank { "全部" },
                            count = count,
                            selected = group == name,
                            onClick = { chosenGroup = name; closeDrawer() },
                        )
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (downloadMode) "点选章节下载" else (shown.size.toString() + " 话"),
                    color = if (downloadMode) DetailWhite else DetailMuted,
                    fontSize = 12.sp, modifier = Modifier.weight(1f),
                )
                if (error != null) {
                    DetailAction(Icons.Outlined.Info, "目录加载提示", onClick = { shownError = error })
                    DetailAction(Icons.Outlined.Refresh, "重新加载目录",
                        enabled = !loading, onClick = onRetry)
                }
                DetailAction(Icons.Outlined.Tune, "筛选、排序和搜索章节",
                    selected = group.isNotEmpty() || query.isNotEmpty() || descending,
                    onClick = { scope.launch { drawer.open() } })
                DetailAction(
                    if (downloadMode) Icons.Outlined.Check else Icons.Outlined.Download,
                    if (downloadMode) "完成选择下载" else "选择章节下载",
                    selected = downloadMode,
                    onClick = { downloadMode = !downloadMode },
                )
            }
            Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 16.dp)) {
                if (showBusy) LinearProgressIndicator(
                    modifier = Modifier.fillMaxSize(), color = DetailSecondary, trackColor = Color.Transparent)
            }
            if (shown.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            loading && chapters.isEmpty() -> "正在读取章节"
                            error != null && chapters.isEmpty() -> "目录暂不可用，点击顶部重试"
                            query.isNotBlank() -> "没有找到这一章"
                            else -> "暂无章节"
                        },
                        color = DetailMuted, fontSize = 13.sp,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                val showGroup = group.isEmpty() && groups.size > 1
                val tileHeight = (if (showGroup) 58f else 47f).times(fontScale).dp
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = (74f * fontScale).dp),
                    state = grid,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 5.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    items(shown, key = Chapter::id) { chapter ->
                        ChapterTile(
                            chapter = chapter,
                            entry = downloaded[chapter.id],
                            progress = progress?.takeIf { it.chapterId == chapter.id },
                            showGroup = showGroup,
                            downloadMode = downloadMode,
                            height = tileHeight,
                            onRead = { openChapter(chapter) },
                            onDownload = { onDownload(chapter) },
                            onActions = { selectedChapterId = chapter.id },
                        )
                    }
                }
            }
        }
    }

    // Keep the opened message until dismissal finishes, even when retry clears the source error.
    shownError?.let { message ->
        MotionBottomSheet(
            onDismissRequest = { shownError = null },
            containerColor = DetailSurface, contentColor = DetailWhite,
        ) { dismiss ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("目录加载提示", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(message, color = DetailSecondary, fontSize = 14.sp, lineHeight = 23.sp,
                    modifier = Modifier.padding(vertical = 16.dp))
                TextButton(onClick = { dismiss(); onRetry() }, enabled = !loading) {
                    Text("重新加载", color = DetailWhite)
                }
            }
        }
    }
    (chapters.firstOrNull { it.id == selectedChapterId } ?: closingChapter)?.let { chapter ->
        val entry = downloaded[chapter.id]
        MotionBottomSheet(
            onDismissRequest = { selectedChapterId = null; closingChapter = null },
            containerColor = DetailSurface, contentColor = DetailWhite,
        ) { dismiss ->
            val dismissActions = {
                // A route change can cancel hide(); never restore the already-dismissed menu.
                closingChapter = chapter
                selectedChapterId = null
                dismiss()
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(chapter.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                Text(
                    listOf(chapter.group, chapterStatus(entry,
                        progress?.takeIf { it.chapterId == chapter.id }))
                        .filter(String::isNotBlank).joinToString(" · "),
                    color = DetailMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                TextButton(onClick = { dismissActions(); openChapter(chapter) },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("阅读这一章", color = DetailWhite)
                }
                TextButton(onClick = { dismissActions(); onDownload(chapter) },
                    enabled = canDownload(entry), modifier = Modifier.fillMaxWidth()) {
                    Text(if (entry?.status == DownloadStatus.COMPLETED) "已下载" else "下载这一章",
                        color = if (canDownload(entry)) DetailWhite else DetailMuted)
                }
            }
        }
    }
}

@Composable
private fun DrawerChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    count: Int? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .heightIn(min = 42.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .motionPress(interaction)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) Color.White.copy(alpha = .09f) else Color.Transparent)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = DetailSecondary, modifier = Modifier.size(18.dp))
        Text(label, color = if (selected) DetailWhite else DetailSecondary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (count != null) Text(count.toString(), color = DetailMuted, fontSize = 11.sp)
        if (selected) Icon(Icons.Outlined.Check, null, tint = DetailWhite, modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterTile(
    chapter: Chapter,
    entry: DownloadEntry?,
    progress: ReadingProgress?,
    showGroup: Boolean,
    downloadMode: Boolean,
    height: Dp,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onActions: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val enabled = !downloadMode || canDownload(entry)
    Column(
        Modifier.fillMaxWidth().height(height)
            .combinedClickable(
                interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button,
                onClickLabel = if (downloadMode) "下载章节" else "阅读章节",
                onLongClickLabel = "章节详情和操作",
                onClick = if (downloadMode) onDownload else onRead,
                onLongClick = onActions,
            )
            .motionPress(interaction, enabled = enabled)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = if (progress != null) .12f else .055f))
            .semantics {
                contentDescription = chapter.title + if (showGroup) "，" + chapter.group else ""
                stateDescription = chapterStatus(entry, progress)
            }
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                chapter.title, color = if (progress != null) DetailWhite else if (enabled) DetailSecondary else DetailMuted,
                fontSize = 11.sp, lineHeight = 15.sp, maxLines = if (showGroup) 1 else 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (progress != null) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(3.dp))
            // Reserve the icon's space even before download: no resizing when a job starts.
            MotionSwap(entry?.status to (downloadMode && entry == null), Modifier.size(14.dp)) { (status, showDownload) ->
                when (status) {
                    DownloadStatus.RUNNING -> if (entry != null && entry.totalPages > 0) {
                        CircularProgressIndicator(
                            progress = { (entry.completedPages.toFloat() / entry.totalPages).coerceIn(0f, 1f) },
                            modifier = Modifier.size(12.dp), color = DetailSecondary, strokeWidth = 1.dp)
                    } else Icon(Icons.Outlined.Download, "正在准备下载",
                        tint = DetailMuted, modifier = Modifier.size(14.dp))
                    DownloadStatus.QUEUED -> Icon(Icons.Outlined.Schedule, "等待下载",
                        tint = DetailMuted, modifier = Modifier.size(14.dp))
                    DownloadStatus.COMPLETED -> Icon(Icons.Outlined.Check, "已下载",
                        tint = Color(0xFFB5C8E2), modifier = Modifier.size(14.dp))
                    DownloadStatus.PAUSED, DownloadStatus.WAITING -> Icon(Icons.Outlined.Pause,
                        "下载暂停", tint = DetailMuted, modifier = Modifier.size(14.dp))
                    DownloadStatus.FAILED -> Icon(Icons.Outlined.Refresh, "下载失败",
                        tint = Color(0xFFDBB0A8), modifier = Modifier.size(14.dp))
                    else -> if (showDownload) Icon(Icons.Outlined.Download, "下载",
                        tint = DetailMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (showGroup) Text(
            chapter.group, color = DetailMuted, fontSize = 9.sp, lineHeight = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun canDownload(entry: DownloadEntry?): Boolean = entry?.status !in setOf(
    DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.COMPLETED,
)

private fun chapterStatus(entry: DownloadEntry?, progress: ReadingProgress?): String = listOfNotNull(
    progress?.let { "上次读到第 " + (it.pageIndex + 1) + " 页" },
    when (entry?.status) {
        DownloadStatus.RUNNING -> "正在下载 " + entry.completedPages + "/" + entry.totalPages
        DownloadStatus.QUEUED -> "等待下载"
        DownloadStatus.WAITING -> "等待恢复下载"
        DownloadStatus.PAUSED -> "下载已暂停"
        DownloadStatus.FAILED -> "下载未完成，可重试"
        DownloadStatus.COMPLETED -> "已下载"
        else -> null
    },
).joinToString("，")

@Composable
private fun ChapterSearch(
    query: String,
    onChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = query, onValueChange = { onChange(it.take(100)) }, singleLine = true,
        textStyle = TextStyle(color = DetailWhite, fontSize = 14.sp), cursorBrush = SolidColor(DetailWhite),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics { contentDescription = "搜索章节名称" },
        decorationBox = { input ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = .055f)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = DetailMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("章节名称或数字", color = DetailMuted, fontSize = 13.sp)
                    input()
                }
                if (query.isNotEmpty()) DetailAction(Icons.Outlined.Close, "清空搜索", onClick = onClear)
                else Spacer(Modifier.width(16.dp))
            }
        },
    )
}
