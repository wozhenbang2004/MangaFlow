package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
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
    val group = chosenGroup?.takeIf { it.isEmpty() || it in groups } ?: groups.keys.firstOrNull().orEmpty()
    var descending by rememberSaveable(mangaId) { mutableStateOf(false) }
    var query by rememberSaveable(mangaId) { mutableStateOf("") }
    var searching by rememberSaveable(mangaId) { mutableStateOf(false) }
    var anchorId by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var downloadMode by rememberSaveable(mangaId) { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var selectedChapterId by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var focusSearch by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val shown = remember(chapters, group, query, descending) {
        chapters.filter { (group.isEmpty() || it.group == group) && (query.isBlank() || it.title.contains(query.trim(), true)) }
            .let { if (descending) it.asReversed() else it }
    }
    val savedChapter = chapters.firstOrNull { it.id == progress?.chapterId }
    val openChapter: (Chapter) -> Unit = {
        keyboard?.hide()
        focusManager.clearFocus()
        onRead(it)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(
                    onClick = { groupMenu = true }, enabled = groups.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(group.ifEmpty { "全部" }, color = DetailSecondary, fontSize = 12.sp,
                        modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Outlined.KeyboardArrowDown, "选择章节分组", tint = DetailMuted, modifier = Modifier.size(17.dp))
                }
                DropdownMenu(expanded = groupMenu, onDismissRequest = { groupMenu = false },
                    containerColor = DetailSurface, tonalElevation = 0.dp, modifier = Modifier.heightIn(max = 320.dp)) {
                    (listOf("" to chapters.size) + groups.entries.map { it.key to it.value }).forEach { (name, count) ->
                        DropdownMenuItem(
                            text = { Text(name.ifEmpty { "全部" } + " · " + count, color = DetailWhite, fontSize = 13.sp) },
                            trailingIcon = { if (group == name) Icon(Icons.Outlined.Check, null, tint = DetailSecondary, modifier = Modifier.size(17.dp)) },
                            onClick = { chosenGroup = name; anchorId = null; groupMenu = false },
                        )
                    }
                }
            }
            if (savedChapter != null) DetailAction(
                Icons.Outlined.MyLocation, "定位上次阅读章节", onClick = {
                    chosenGroup = savedChapter.group; query = ""; searching = false; anchorId = savedChapter.id
                    keyboard?.hide(); focusManager.clearFocus()
                },
            )
            DetailAction(if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                if (descending) "倒序，点击切换正序" else "正序，点击切换倒序",
                onClick = { descending = !descending; anchorId = null })
            DetailAction(if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                if (searching) "收起搜索" else "搜索章节", selected = searching, onClick = {
                    searching = !searching
                    if (searching) focusSearch = true
                    else { query = ""; anchorId = null; keyboard?.hide(); focusManager.clearFocus() }
                })
            DetailAction(Icons.Outlined.Download, if (downloadMode) "退出下载模式" else "选择章节下载",
                selected = downloadMode, onClick = { downloadMode = !downloadMode })
        }
        AnimatedVisibility(searching) {
            ChapterSearch(query, requestFocus = focusSearch, onFocused = { focusSearch = false },
                onChange = { query = it; anchorId = null }, onClear = { query = ""; anchorId = null })
        }
        if (downloadMode) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("点选章节下载", color = DetailSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { downloadMode = false }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("完成", color = DetailWhite, fontSize = 12.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).padding(horizontal = 16.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxSize(), color = DetailSecondary, trackColor = Color.Transparent)
        }
        if (error != null) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    chapters.isEmpty() -> "目录暂未加载"
                    chapters.all { downloaded[it.id]?.status == DownloadStatus.COMPLETED } -> "正在显示已下载章节"
                    else -> "已保留上次目录"
                },
                color = DetailSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f),
            )
            DetailAction(Icons.Outlined.Info, "查看目录加载失败原因", onClick = { showError = true })
            DetailAction(Icons.Outlined.Refresh, "重新加载目录", enabled = !loading, onClick = onRetry)
        }

        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        loading && chapters.isEmpty() -> "正在读取章节"
                        error != null && chapters.isEmpty() -> "可重试目录，已有记录仍可继续阅读"
                        query.isNotBlank() -> "没有找到这一章"
                        else -> "暂无章节"
                    },
                    color = DetailMuted, fontSize = 13.sp, modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                val fontScale = LocalDensity.current.fontScale
                val showGroup = group.isEmpty() && groups.size > 1
                val cellHeight = ((if (showGroup) 47f else 34f) * fontScale + 18f).dp.coerceAtLeast(52.dp)
                val columns = (maxWidth.value / (108f * fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 6)
                val gap = 6.dp
                // Account for the 48dp pager and the grid's 8dp top/bottom padding.
                val rows = ((maxHeight.value - 48f - 16f + gap.value) / (cellHeight.value + gap.value)).toInt().coerceAtLeast(1)
                val capacity = rows * columns
                val pageCount = (shown.size + capacity - 1) / capacity
                val anchor = shown.indexOfFirst { it.id == anchorId }.coerceAtLeast(0)
                val page = (anchor / capacity).coerceIn(0, pageCount - 1)
                val start = page * capacity
                val end = (start + capacity).coerceAtMost(shown.size)
                Column(Modifier.fillMaxSize()) {
                    key(page, capacity) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns), modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap),
                        ) {
                            items(shown.subList(start, end), key = Chapter::id) { chapter ->
                                ChapterTile(
                                    chapter, downloaded[chapter.id], progress?.takeIf { it.chapterId == chapter.id },
                                    showGroup, downloadMode, cellHeight,
                                    onRead = { openChapter(chapter) }, onDownload = { onDownload(chapter) },
                                    onActions = { selectedChapterId = chapter.id },
                                )
                            }
                        }
                    }
                    CatalogPager(page, pageCount, capacity, shown.size, start, end) { next ->
                        anchorId = shown[(next * capacity).coerceIn(shown.indices)].id
                    }
                }
            }
        }
    }
    if (showError && error != null) {
        ModalBottomSheet(onDismissRequest = { showError = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DetailSurface, contentColor = DetailWhite) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("目录加载提示", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(error, color = DetailSecondary, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(vertical = 16.dp))
                TextButton(onClick = { showError = false; onRetry() }, enabled = !loading) {
                    Text("重新加载", color = DetailWhite)
                }
            }
        }
    }
    chapters.firstOrNull { it.id == selectedChapterId }?.let { chapter ->
        val entry = downloaded[chapter.id]
        ModalBottomSheet(onDismissRequest = { selectedChapterId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DetailSurface, contentColor = DetailWhite) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(chapter.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                Text(listOf(chapter.group, chapterStatus(entry, progress?.takeIf { it.chapterId == chapter.id }))
                    .filter(String::isNotBlank).joinToString(" · "),
                    color = DetailMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                TextButton(onClick = { selectedChapterId = null; openChapter(chapter) }, modifier = Modifier.fillMaxWidth()) {
                    Text("阅读这一章", color = DetailWhite)
                }
                TextButton(onClick = { selectedChapterId = null; onDownload(chapter) },
                    enabled = canDownload(entry), modifier = Modifier.fillMaxWidth()) {
                    Text(if (entry?.status == DownloadStatus.COMPLETED) "已下载" else "下载这一章",
                        color = if (canDownload(entry)) DetailWhite else DetailMuted)
                }
            }
        }
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
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .97f else 1f,
        spring(dampingRatio = .85f, stiffness = 550f), label = "chapter-press")
    val enabled = !downloadMode || canDownload(entry)
    Column(
        Modifier.fillMaxWidth().height(height).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = if (progress != null) .15f else if (downloadMode) .075f else .04f))
            .combinedClickable(
                interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button,
                onClickLabel = if (downloadMode) "下载章节" else "阅读章节", onLongClickLabel = "章节详情和操作",
                onClick = if (downloadMode) onDownload else onRead, onLongClick = onActions,
            )
            .semantics {
                contentDescription = chapter.title + if (showGroup) "，" + chapter.group else ""
                stateDescription = chapterStatus(entry, progress)
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(chapter.title, color = if (progress != null) DetailWhite else if (enabled) DetailSecondary else DetailMuted,
                fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (progress != null) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.weight(1f))
            if (entry != null || downloadMode) {
                Spacer(Modifier.width(4.dp))
                when (entry?.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> CircularProgressIndicator(
                        Modifier.size(12.dp), color = DetailMuted, strokeWidth = 1.dp)
                    else -> Icon(
                        when (entry?.status) {
                            DownloadStatus.COMPLETED -> Icons.Outlined.Check
                            DownloadStatus.PAUSED, DownloadStatus.WAITING -> Icons.Outlined.Pause
                            DownloadStatus.FAILED -> Icons.Outlined.Refresh
                            else -> Icons.Outlined.Download
                        }, null, tint = DetailMuted, modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        if (showGroup) Text(chapter.group, color = DetailMuted, fontSize = 10.sp,
            lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
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

/** BoardUI Pagination's controlled/bounded pager adapted to a phone-sized range selector. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogPager(
    page: Int, pageCount: Int, capacity: Int, total: Int, start: Int, end: Int,
    onPage: (Int) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        DetailAction(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一页章节", enabled = page > 0) { onPage(page - 1) }
        TextButton(onClick = { choosing = true }, enabled = pageCount > 1, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text((start + 1).toString() + "–" + end + " / " + total, color = DetailMuted, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (pageCount > 1) Icon(Icons.Outlined.KeyboardArrowDown, "选择章节范围",
                tint = DetailMuted, modifier = Modifier.size(16.dp))
        }
        DetailAction(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一页章节", enabled = page < pageCount - 1) { onPage(page + 1) }
    }
    if (choosing) {
        ModalBottomSheet(onDismissRequest = { choosing = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DetailSurface, contentColor = DetailWhite) {
            Text("跳到章节范围", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val state = rememberLazyListState(initialFirstVisibleItemIndex = page)
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                items(pageCount) { index ->
                    TextButton(onClick = { choosing = false; onPage(index) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .background(if (page == index) Color.White.copy(alpha = .08f) else Color.Transparent, RoundedCornerShape(10.dp))) {
                        Text((index * capacity + 1).toString() + "–" + ((index + 1) * capacity).coerceAtMost(total),
                            color = DetailWhite, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (page == index) Icon(Icons.Outlined.Check, "当前章节范围", tint = DetailMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterSearch(
    query: String, requestFocus: Boolean, onFocused: () -> Unit,
    onChange: (String) -> Unit, onClear: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(requestFocus) {
        if (requestFocus) { focus.requestFocus(); onFocused() }
    }
    BasicTextField(
        value = query, onValueChange = { onChange(it.take(100)) }, singleLine = true,
        textStyle = TextStyle(color = DetailWhite, fontSize = 14.sp), cursorBrush = SolidColor(DetailWhite),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus)
            .semantics { contentDescription = "搜索章节名称" },
        decorationBox = { input ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = .055f)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
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
