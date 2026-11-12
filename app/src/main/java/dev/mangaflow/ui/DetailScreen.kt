package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ReadingProgress
import kotlinx.coroutines.launch

private val DetailInk = Color(0xFF121213)
private val DetailWhite = Color(0xFFF3F3F1)
private val DetailSecondary = Color(0xFFB4B4B8)
private val DetailMuted = Color(0xFF7D7D83)
private val DetailLine = Color.White.copy(alpha = 0.075f)

@Composable
fun DetailScreen(
    manga: Manga,
    detail: MangaDetail?,
    loading: Boolean,
    error: String?,
    reviews: List<ReviewSource>,
    reviewsLoading: Boolean,
    isFavorite: Boolean,
    progress: ReadingProgress?,
    downloadEntries: List<DownloadEntry>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRead: (Chapter) -> Unit,
    onContinue: (ReadingProgress) -> Unit,
    onDownload: (Chapter) -> Unit,
    onReviewMore: (String) -> Unit = {},
    onReviewRetry: (String) -> Unit = {},
    onReviewMatch: (String, String) -> Unit = { _, _ -> },
    onReviewRelink: (String) -> Unit = {},
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage = { _, _, _ -> ReviewPage(emptyList()) },
) {
    val current = detail?.manga ?: manga
    val downloaded = remember(downloadEntries, manga.id) { downloadEntries.filter { it.manga.id == manga.id }.associateBy { it.chapter.id } }
    val chapters = remember(detail, downloaded) {
        detail?.chapters ?: downloaded.values.filter { it.status == DownloadStatus.COMPLETED }
            .map(DownloadEntry::chapter).distinctBy(Chapter::id).sortedBy(Chapter::index)
    }
    val resumeProgress = progress?.takeIf { it.manga.id == manga.id && it.chapterId.isNotBlank() }
    val startChapter = chapters.firstOrNull()
    val canRead = resumeProgress != null || startChapter != null
    val groups = remember(chapters) { chapters.map(Chapter::group).distinct() }
    var group by rememberSaveable(manga.id) { mutableStateOf("") }
    var descending by rememberSaveable(manga.id) { mutableStateOf(false) }
    var query by rememberSaveable(manga.id) { mutableStateOf("") }
    var searching by rememberSaveable(manga.id) { mutableStateOf(false) }
    val shownChapters = remember(chapters, group, query, descending) {
        chapters.filter { (group.isEmpty() || it.group == group) && (query.isBlank() || it.title.contains(query.trim(), true)) }
            .let { if (descending) it.asReversed() else it }
    }
    LaunchedEffect(groups) { if (group !in groups) group = "" }
    val list = rememberLazyListState()
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val collapseOffset = with(LocalDensity.current) { 360.dp.toPx() }
    val collapsed by remember(list, collapseOffset) {
        derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > collapseOffset }
    }
    val haze = rememberHazeState()
    val scope = rememberCoroutineScope()


    Box(Modifier.fillMaxSize().background(DetailInk)) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
            contentPadding = PaddingValues(bottom = 134.dp),
        ) {
            item(key = "hero") { DetailHero(current) }
            item(key = "synopsis") { DetailSynopsis(current.description, loading) }
            item(key = "reviews") {
                CommunityOverview(
                    mangaId = current.id, mangaTitle = current.title,
                    sources = reviews, loading = reviewsLoading,
                    onMore = onReviewMore, onRetry = onReviewRetry,
                    onMatch = onReviewMatch, onRelink = onReviewRelink, loadReplies = loadReplies,
                )
            }
            // This header has a stable index so the persistent chapter shortcut can scroll to it.
            item(key = "catalog") {
                Column(Modifier.padding(horizontal = 24.dp).padding(top = 34.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("章节", color = DetailWhite, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        if (chapters.isNotEmpty()) Text("  ${chapters.size}", color = DetailMuted, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { descending = !descending }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Icon(if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                                contentDescription = null, tint = DetailSecondary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (descending) "倒序" else "正序", color = DetailSecondary, fontSize = 13.sp)
                        }
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                                if (searching) "收起章节搜索" else "搜索章节", tint = DetailSecondary, modifier = Modifier.size(21.dp))
                        }
                    }
                    AnimatedVisibility(searching) {
                        ChapterSearch(query, onChange = { query = it }, onClear = { query = "" })
                    }
                    if (groups.size > 1) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GroupOption("全部", selected = group.isEmpty()) { group = "" }
                            groups.forEach { name -> GroupOption(name, selected = name == group) { group = name } }
                        }
                    }
                    if (loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(1.dp).padding(top = 0.dp),
                            color = DetailSecondary, trackColor = Color.White.copy(alpha = 0.04f))
                    }
                    if (error != null) {
                        Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
                            Text(when {
                                chapters.isEmpty() -> "目录暂未加载"
                                chapters.all { downloaded[it.id]?.status == DownloadStatus.COMPLETED } -> "正在显示已下载章节"
                                else -> "已保留上次目录"
                            }, color = DetailWhite, fontSize = 14.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(error, color = DetailSecondary, fontSize = 13.sp, lineHeight = 21.sp)
                            Row {
                                TextButton(onClick = onRetry, contentPadding = PaddingValues(end = 18.dp)) {
                                    Icon(Icons.Outlined.Refresh, null, tint = DetailWhite, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("重新加载", color = DetailWhite, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    if (!loading && error == null && shownChapters.isEmpty()) {
                        Text(if (query.isNotBlank()) "没有找到这一章" else "暂无章节", color = DetailMuted,
                            fontSize = 14.sp, modifier = Modifier.padding(vertical = 24.dp))
                    }
                }
            }
            items(shownChapters, key = { "chapter:${it.id}" }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    entry = downloaded[chapter.id],
                    progress = progress?.takeIf { it.chapterId == chapter.id },
                    showGroup = groups.size > 1 && group.isEmpty(),
                    onRead = { onRead(chapter) },
                    onDownload = { onDownload(chapter) },
                )
            }
        }

        Row(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { topBarHeightPx = it.height }
                .statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailGlassButton(Icons.AutoMirrored.Outlined.ArrowBack, "返回", haze, onBack)
            Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(visible = collapsed, enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.clip(RoundedCornerShape(50)).hazeEffect(haze, thinGlass())
                        .padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(current.title, color = DetailWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            DetailGlassButton(if (isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                if (isFavorite) "从书架移除" else "加入书架", haze, onToggleFavorite)
        }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, DetailInk.copy(alpha = 0.96f))))
                .padding(top = 24.dp).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { if (resumeProgress != null) onContinue(resumeProgress) else startChapter?.let(onRead) },
                enabled = canRead,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DetailWhite, contentColor = DetailInk,
                    disabledContainerColor = Color(0xFF29292C), disabledContentColor = DetailMuted),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) {
                if (loading && !canRead) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = DetailSecondary, strokeWidth = 1.5.dp)
                } else Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(if (resumeProgress != null) "继续阅读" else if (loading && !canRead) "加载章节" else "开始阅读",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    if (resumeProgress != null) {
                        Text(resumeProgress.chapterTitle, fontSize = 10.sp, color = DetailInk.copy(alpha = 0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Row(Modifier.height(56.dp).clip(RoundedCornerShape(18.dp)).hazeEffect(haze, thinGlass())
                .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
                .clickable(role = Role.Button) { scope.launch { list.animateScrollToItem(3, scrollOffset = -topBarHeightPx) } }
                .padding(horizontal = 17.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Outlined.FormatListNumbered, null, tint = DetailWhite, modifier = Modifier.size(19.dp))
                Text("章节", color = DetailWhite, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DetailHero(manga: Manga) {
    val context = LocalContext.current
    var failed by remember(manga.coverUrl) { mutableStateOf(false) }
    var retry by remember(manga.coverUrl) { mutableIntStateOf(0) }
    val request = remember(manga.coverUrl, manga.id, retry) {
        ImageRequest.Builder(context).data(manga.coverUrl)
            .httpHeaders(NetworkHeaders.Builder()
                .set("User-Agent", "Mozilla/5.0")
                .set("Referer", manga.sourceUrl.ifBlank { "https://www.copy3000.com/comic/${manga.id}" }).build())
            .crossfade(220)
            .memoryCacheKey("detail-cover:${manga.coverUrl}:$retry")
            .build()
    }
    Box(Modifier.fillMaxWidth().height(510.dp).background(Color(0xFF242426))) {
        if (manga.coverUrl.isNotBlank()) {
            AsyncImage(request, manga.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter, onError = { failed = true }, onSuccess = { failed = false })
        }
        if (manga.coverUrl.isBlank() || failed) {
            Column(Modifier.align(Alignment.Center).padding(bottom = 72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.MenuBook, null, tint = Color.White.copy(alpha = 0.1f), modifier = Modifier.size(76.dp))
                if (failed) TextButton(onClick = { failed = false; retry++ }) {
                    Text("重载封面", color = DetailMuted, fontSize = 12.sp)
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.28f), 0.22f to Color.Transparent,
            0.50f to DetailInk.copy(alpha = 0.15f), 0.77f to DetailInk.copy(alpha = 0.88f), 1f to DetailInk,
        )))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("MANGA", color = DetailSecondary, fontSize = 10.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.Medium)
                if (manga.status.isNotBlank()) {
                    Box(Modifier.size(2.dp).background(DetailMuted, CircleShape))
                    Text(manga.status, color = DetailSecondary, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(manga.title, color = DetailWhite, fontSize = 31.sp, lineHeight = 38.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (manga.authors.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(manga.authors.joinToString(" / "), color = DetailSecondary, fontSize = 13.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (manga.tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(manga.tags.take(5).joinToString("  ·  "), color = DetailMuted, fontSize = 11.sp, lineHeight = 18.sp)
            }
            MangaMetricBadges(manga, Modifier.fillMaxWidth().padding(top = 14.dp), foreground = DetailWhite)
            Text("拷贝漫画", color = DetailMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable
private fun DetailSynopsis(description: String, loading: Boolean) {
    var expanded by rememberSaveable(description) { mutableStateOf(false) }
    var canExpand by remember(description) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).animateContentSize()) {
        if (description.isNotBlank()) {
            Text(description, color = DetailSecondary, fontSize = 14.sp, lineHeight = 25.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) canExpand = it.hasVisualOverflow })
            if (canExpand || expanded) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(end = 12.dp)) {
                    Text(if (expanded) "收起" else "展开简介", color = DetailWhite.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }
        } else if (loading) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(7.dp).background(Color.White.copy(alpha = 0.055f), CircleShape))
                Box(Modifier.fillMaxWidth(0.72f).height(7.dp).background(Color.White.copy(alpha = 0.055f), CircleShape))
            }
        }
    }
}

@Composable
private fun ChapterSearch(query: String, onChange: (String) -> Unit, onClear: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = query,
        onValueChange = { onChange(it.take(100)) },
        singleLine = true,
        textStyle = TextStyle(color = DetailWhite, fontSize = 14.sp),
        cursorBrush = SolidColor(DetailWhite),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).focusRequester(focus),
        decorationBox = { input ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(13.dp))
                .background(Color.White.copy(alpha = 0.055f)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, tint = DetailMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("搜索章节", color = DetailMuted, fontSize = 14.sp)
                    input()
                }
                if (query.isNotEmpty()) IconButton(onClick = onClear) {
                    Icon(Icons.Outlined.Close, "清空搜索", tint = DetailMuted, modifier = Modifier.size(16.dp))
                } else Spacer(Modifier.width(16.dp))
            }
        },
    )
}

@Composable
private fun GroupOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(13.dp))
        .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent)
        .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = 15.dp, vertical = 12.dp)) {
        Text(label, color = if (selected) DetailWhite else DetailMuted, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
private fun ChapterRow(
    chapter: Chapter,
    entry: DownloadEntry?,
    progress: ReadingProgress?,
    showGroup: Boolean,
    onRead: () -> Unit,
    onDownload: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onRead),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterStart) {
                if (progress != null) Box(Modifier.width(2.dp).height(19.dp).background(DetailWhite, CircleShape))
                else Text((chapter.index + 1).toString().padStart(2, '0'), color = DetailMuted.copy(alpha = 0.75f), fontSize = 10.sp)
            }
            Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
                Text(chapter.title, color = if (progress != null) DetailWhite else DetailSecondary,
                    fontSize = 14.sp, fontWeight = if (progress != null) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                val note = when {
                    entry?.status == DownloadStatus.RUNNING -> "正在下载 ${entry.completedPages}/${entry.totalPages}"
                    entry?.status == DownloadStatus.QUEUED -> "等待下载"
                    entry?.status == DownloadStatus.WAITING -> "等待恢复下载"
                    entry?.status == DownloadStatus.PAUSED -> "下载已暂停"
                    entry?.status == DownloadStatus.FAILED -> "下载未完成 · 点击重试"
                    progress != null -> "上次读到第 ${progress.pageIndex + 1} 页"
                    entry?.status == DownloadStatus.COMPLETED -> "已下载"
                    showGroup -> chapter.group
                    else -> ""
                }
                if (note.isNotBlank()) Text(note, color = DetailMuted, fontSize = 10.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val busy = entry?.status == DownloadStatus.RUNNING || entry?.status == DownloadStatus.QUEUED
            IconButton(onClick = onDownload, enabled = !busy && entry?.status != DownloadStatus.COMPLETED) {
                when {
                    entry?.status == DownloadStatus.RUNNING -> CircularProgressIndicator(
                        progress = { if (entry.totalPages > 0) entry.completedPages.toFloat() / entry.totalPages else 0f },
                        modifier = Modifier.size(18.dp), color = DetailSecondary, strokeWidth = 1.5.dp,
                        trackColor = Color.White.copy(alpha = 0.08f),
                    )
                    entry?.status == DownloadStatus.QUEUED -> CircularProgressIndicator(Modifier.size(18.dp), color = DetailMuted, strokeWidth = 1.dp)
                    entry?.status == DownloadStatus.COMPLETED -> Icon(Icons.Outlined.Check, "已下载 ${chapter.title}", tint = DetailMuted, modifier = Modifier.size(19.dp))
                    entry?.status == DownloadStatus.FAILED -> Icon(Icons.Outlined.Refresh, "重试下载 ${chapter.title}", tint = DetailSecondary, modifier = Modifier.size(20.dp))
                    else -> Icon(Icons.Outlined.Download, "下载 ${chapter.title}", tint = DetailMuted, modifier = Modifier.size(20.dp))
                }
            }
        }
        HorizontalDivider(color = DetailLine, thickness = 0.5.dp)
    }
}

@Composable
private fun DetailGlassButton(icon: ImageVector, description: String, haze: HazeState, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 550f), label = "glass-press")
    Box(Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }
        .shadow(5.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.12f))
        .clip(CircleShape).hazeEffect(haze, thinGlass())
        .border(0.5.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.27f), Color.White.copy(alpha = 0.04f))), CircleShape)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = DetailWhite, modifier = Modifier.size(21.dp))
    }
}

private fun thinGlass() = HazeStyle(
    backgroundColor = DetailInk,
    tints = listOf(HazeTint(Color(0xFF252529).copy(alpha = 0.28f)), HazeTint(Color.White.copy(alpha = 0.035f))),
    blurRadius = 16.dp,
    noiseFactor = 0f,
    fallbackTint = HazeTint(Color(0xFF242427).copy(alpha = 0.82f)),
)
