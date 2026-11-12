package dev.mangaflow.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.LibraryOpenState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.rememberDelayedBusy

@Composable
fun LibraryScreen(
    favorites: List<Manga>, history: List<ReadingProgress>, repository: MangaRepository,
    opening: LibraryOpenState?, active: Boolean,
    onOpen: (Manga) -> Unit, onDetails: (Manga) -> Unit,
    onContinue: (ReadingProgress) -> Unit, onDiscover: () -> Unit,
    onRetryOpen: () -> Unit, onCancelOpen: () -> Unit,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val favoritesPosition = rememberLazyListState()
    val historyPosition = rememberLazyListState()
    val progressById = remember(history) { history.associateBy { it.manga.id } }
    LaunchedEffect(active, showHistory) {
        if (!active || showHistory) onCancelOpen()
    }
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        BrowseTabs(if (showHistory) 1 else 0, listOf("收藏 ${favorites.size}", "最近阅读"),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { index ->
            if (showHistory != (index == 1)) onCancelOpen()
            showHistory = index == 1
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            MotionTabContent(if (showHistory) 1 else 0, Modifier.fillMaxSize()) { tab ->
                if (tab == 1) {
                    LazyColumn(
                        state = historyPosition,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (history.isEmpty()) item(key = "empty-history") {
                            EmptyMessage("从第一页开始", "读过的章节会留在这里，下次接着看。", "去发现", onDiscover)
                        }
                        items(history, key = { "history-${it.manga.id}" }) {
                            ContinueRow(it, repository, onContinue)
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val available = (maxWidth - 32.dp).coerceAtLeast(1.dp)
                        val faceWidth = (114.dp * fontScale).coerceAtMost(available)
                        val spineWidth = 76.dp * fontScale
                        val gap = 10.dp
                        val perShelf = (1 + ((available - faceWidth) / (spineWidth + gap)).toInt()).coerceAtLeast(1)
                        val rows = remember(favorites, perShelf) { favorites.chunked(perShelf) }
                        LazyColumn(
                            state = favoritesPosition,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 20.dp, bottom = 32.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            if (favorites.isEmpty()) item(key = "empty-shelf") {
                                EmptyBookshelf(onDiscover)
                            }
                            items(rows, key = { "shelf-${it.first().id}" }) { books ->
                                BookshelfRow(books, repository, progressById, opening,
                                    faceWidth, spineWidth, 164.dp * fontScale.coerceAtMost(1.5f),
                                    onOpen, onDetails)
                            }
                        }
                    }
                }
            }
            if (!showHistory) LibraryOpeningNotice(opening, onRetryOpen, onCancelOpen,
                Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfRow(
    books: List<Manga>, repository: MangaRepository, progress: Map<String, ReadingProgress>,
    opening: LibraryOpenState?, faceWidth: Dp, spineWidth: Dp, bookHeight: Dp,
    onOpen: (Manga) -> Unit, onDetails: (Manga) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            books.forEachIndexed { index, manga ->
                BookshelfBook(manga, repository, progress[manga.id], faceOut = index == 0,
                    opening = opening?.let { it.manga.id == manga.id && it.loading } == true,
                    onOpen = { onOpen(manga) }, onDetails = { onDetails(manga) },
                    modifier = Modifier.width(if (index == 0) faceWidth else spineWidth).height(bookHeight))
            }
        }
        BookshelfPlank(Modifier.fillMaxWidth().height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            books.forEachIndexed { index, manga ->
                val interaction = remember(manga.id) { MutableInteractionSource() }
                val saved = progress[manga.id]
                Column(Modifier.width(if (index == 0) faceWidth else spineWidth)
                    .combinedClickable(interactionSource = interaction, indication = null,
                        onClickLabel = if (saved == null) "开始阅读" else "继续阅读",
                        onLongClickLabel = "查看详情和目录", onLongClick = { onDetails(manga) },
                        onClick = { onOpen(manga) })
                    .padding(top = 2.dp)) {
                    Text(manga.title, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    Text(saved?.chapterTitle?.ifBlank { "继续阅读" } ?: "未读",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // Keep native metrics; additional sources remain available in details.
                    MangaMetricBadges(manga,
                        Modifier.fillMaxWidth().padding(top = 5.dp)
                            .heightIn(min = 68.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                        compact = true, maxScores = if (index == 0) 2 else 1)
                }
            }
        }
    }
}

@Composable
private fun EmptyBookshelf(onDiscover: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("给喜欢的故事留个位置", style = MaterialTheme.typography.titleMedium)
            Text("收藏漫画后，它就会放上这层书架。", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onDiscover) { Text("去发现") }
        }
        BookshelfPlank(Modifier.fillMaxWidth().height(20.dp))
        Spacer(Modifier.height(156.dp))
        BookshelfPlank(Modifier.fillMaxWidth().height(20.dp))
    }
}

@Composable
private fun LibraryOpeningNotice(
    state: LibraryOpenState?, onRetry: () -> Unit, onCancel: () -> Unit, modifier: Modifier,
) {
    val busyVisible = rememberDelayedBusy(state?.loading == true)
    if (state == null || (state.loading && !busyVisible)) return
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 4.dp) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Text(state.manga.title, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (state.loading) "正在打开首章…" else state.error ?: "暂时无法打开，请重试。",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.loading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 3.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(if (state.loading) "取消" else "收起") }
                if (!state.loading) TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}
