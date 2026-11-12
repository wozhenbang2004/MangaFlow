package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.mangaflow.LibraryOpenState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.PageCollection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.storage.ShelfState
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.rememberDelayedBusy

@Composable
fun LibraryScreen(
    favorites: List<Manga>, history: List<ReadingProgress>, shelfStates: Map<String, ShelfState>,
    repository: MangaRepository, libraryStore: LibraryStore, opening: LibraryOpenState?, active: Boolean,
    onOpen: (Manga) -> Unit, onDetails: (Manga) -> Unit,
    onContinue: (ReadingProgress) -> Unit, onOpenPageCollection: (PageCollection) -> Unit, onDiscover: () -> Unit,
    onRetryOpen: () -> Unit, onCancelOpen: () -> Unit,
    onRemoveFavorite: (Manga) -> Unit, onRemoveHistory: (String) -> Unit,
    onShelfState: (Manga, ShelfState) -> Unit,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var shelfFilterName by rememberSaveable { mutableStateOf("") }
    val shelfFilters = listOf<ShelfState?>(null, ShelfState.FOLLOWING, ShelfState.HOARDING, ShelfState.PAUSED)
    val shelfFilter = shelfFilters.firstOrNull { it?.name.orEmpty() == shelfFilterName }
    var showCollections by rememberSaveable { mutableStateOf(false) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    val collections by libraryStore.pageCollections.collectAsStateWithLifecycle()
    val readingActivity by libraryStore.readingActivity.collectAsStateWithLifecycle()
    var managedId by remember { mutableStateOf<String?>(null) }
    var draggedFavorite by remember { mutableStateOf<Manga?>(null) }
    var dragPoint by remember { mutableStateOf(Offset.Unspecified) }
    var trashBounds by remember { mutableStateOf<Rect?>(null) }
    fun isTrashDrop(point: Offset): Boolean {
        val bounds = trashBounds ?: return false
        if (point == Offset.Unspecified || bounds.isEmpty) return false
        val radius = minOf(bounds.width, bounds.height) / 2f
        return (point - bounds.center).getDistanceSquared() <= radius * radius
    }
    val trashArmed = draggedFavorite != null && isTrashDrop(dragPoint)
    val favoritesPosition = rememberLazyListState()
    val historyPosition = rememberLazyListState()
    val progressById = remember(history) { history.associateBy { it.manga.id } }
    LaunchedEffect(active, showHistory) {
        managedId = null
        draggedFavorite = null
        dragPoint = Offset.Unspecified
        trashBounds = null
        if (!active || showHistory) onCancelOpen()
    }
    if (showCollections) PageCollectionsSheet(collections, repository,
        onOpen = { showCollections = false; onOpenPageCollection(it) },
        onRemove = { libraryStore.removePageCollection(it.key) },
        onDismiss = { showCollections = false })
    if (showStats) ReadingStatsSheet(readingActivity, repository,
        onDismiss = { showStats = false }, onOpenManga = { showStats = false; onOpen(it) })
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BrowseTabs(if (showHistory) 1 else 0, listOf("收藏 ${favorites.size}", "最近阅读"),
                Modifier.weight(1f)) { index ->
                if (showHistory != (index == 1)) onCancelOpen()
                showHistory = index == 1
            }
            MotionIconButton(onClick = { showCollections = true }, enabled = active) {
                Icon(Icons.Outlined.Collections, "画面收藏，${collections.size} 项", Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MotionIconButton(onClick = { showStats = true }, enabled = active) {
                Icon(Icons.Outlined.BarChart, "阅读统计", Modifier.size(21.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                        items(history, key = { "history-${it.manga.id}" }) { progress ->
                            SwipeToRemove(onRemove = { onRemoveHistory(progress.manga.id) }, label = "删除阅读记录") {
                                ContinueRow(progress, repository, onContinue,
                                    onManage = { onCancelOpen(); managedId = progress.manga.id })
                                RecordActionsMenu(managedId == progress.manga.id,
                                    onDismiss = { managedId = null },
                                    onDetails = { onDetails(progress.manga) })
                            }
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        BrowseTabs(
                            shelfFilters.indexOf(shelfFilter),
                            shelfFilters.map {
                                when (it) {
                                    null -> "全部"
                                    ShelfState.FOLLOWING -> "追更"
                                    ShelfState.HOARDING -> "养肥"
                                    ShelfState.PAUSED -> "搁置"
                                    ShelfState.NORMAL -> "收藏"
                                }
                            },
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        ) { index ->
                            shelfFilterName = shelfFilters[index]?.name.orEmpty()
                            managedId = null
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val visibleFavorites = remember(favorites, shelfStates, shelfFilter) {
                                if (shelfFilter == null) favorites
                                else favorites.filter { (shelfStates[it.id] ?: ShelfState.NORMAL) == shelfFilter }
                            }
                            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                            val available = (maxWidth - 52.dp).coerceAtLeast(1.dp)
                            val gap = 12.dp
                            val minimumWidth = 94.dp * fontScale.coerceAtMost(1.6f)
                            val perShelf = ((available + gap) / (minimumWidth + gap)).toInt().coerceIn(1, 6)
                            val bookWidth = (available - gap * (perShelf - 1)) / perShelf
                            val bookHeight = ((bookWidth - 14.dp).coerceAtLeast(1.dp) / .7f) + 8.dp
                            val rows = remember(visibleFavorites, perShelf) { visibleFavorites.chunked(perShelf) }
                            LazyColumn(
                                state = favoritesPosition,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp),
                                verticalArrangement = Arrangement.spacedBy(0.dp),
                            ) {
                                if (visibleFavorites.isEmpty()) item(key = "empty-shelf-${shelfFilter?.name.orEmpty()}") {
                                    if (favorites.isEmpty()) EmptyBookshelf(onDiscover)
                                    else EmptyShelfGroup(shelfFilter)
                                }
                                if (rows.isNotEmpty()) item(key = "case-top") {
                                    BookshelfPlank(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(22.dp))
                                }
                                items(rows, key = { "shelf-${it.first().id}" }) { books ->
                                    BookshelfRow(
                                        books, repository, progressById, shelfStates, opening,
                                        bookWidth, bookHeight, managedId,
                                        draggedId = draggedFavorite?.id,
                                        deleteArmed = trashArmed,
                                        onOpen = onOpen,
                                        onDetails = onDetails,
                                        onRemove = onRemoveFavorite,
                                        onManage = { if (it != null) onCancelOpen(); managedId = it },
                                        onShelfState = onShelfState,
                                        onDragStart = { manga, point ->
                                            onCancelOpen()
                                            managedId = null
                                            draggedFavorite = manga
                                            dragPoint = point
                                        },
                                        onDragMove = { dragPoint = it },
                                        onDragEnd = { manga, point ->
                                            val remove = draggedFavorite?.id == manga.id && isTrashDrop(point)
                                            draggedFavorite = null
                                            dragPoint = Offset.Unspecified
                                            if (remove) onRemoveFavorite(manga)
                                        },
                                        onDragCancel = {
                                            draggedFavorite = null
                                            dragPoint = Offset.Unspecified
                                        },
                                    )
                                }
                                if (rows.size == 1) item(key = "empty-cabinet-bay") {
                                    BookshelfCabinetBay(Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                                        .height(bookHeight + 36.dp)) {
                                        BookshelfPlank(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                            .padding(horizontal = 3.dp).height(22.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!showHistory) LibraryOpeningNotice(opening, onRetryOpen, onCancelOpen,
                Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
    }
        AnimatedVisibility(
            visible = draggedFavorite != null,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
            enter = fadeIn() + scaleIn(initialScale = .82f),
            exit = fadeOut() + scaleOut(targetScale = .9f),
        ) {
            Surface(
                modifier = Modifier.size(72.dp).onGloballyPositioned { trashBounds = it.boundsInRoot() },
                shape = CircleShape,
                color = if (trashArmed) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
                shadowElevation = if (trashArmed) 10.dp else 5.dp,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = if (trashArmed) "松手删除收藏" else "拖到这里删除",
                        modifier = Modifier.size(27.dp),
                        tint = if (trashArmed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookshelfRow(
    books: List<Manga>, repository: MangaRepository, progress: Map<String, ReadingProgress>,
    shelfStates: Map<String, ShelfState>, opening: LibraryOpenState?,
    bookWidth: Dp, bookHeight: Dp, managedId: String?,
    draggedId: String?, deleteArmed: Boolean,
    onOpen: (Manga) -> Unit, onDetails: (Manga) -> Unit, onRemove: (Manga) -> Unit,
    onManage: (String?) -> Unit, onShelfState: (Manga, ShelfState) -> Unit,
    onDragStart: (Manga, Offset) -> Unit, onDragMove: (Offset) -> Unit,
    onDragEnd: (Manga, Offset) -> Unit, onDragCancel: () -> Unit,
) {
    val draggingHere = books.any { it.id == draggedId }
    BookshelfCabinetBay(Modifier.fillMaxWidth().padding(horizontal = 12.dp).zIndex(if (draggingHere) 8f else 0f)) {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).zIndex(if (draggingHere) 8f else 0f),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                books.forEach { manga ->
                    key(manga.id) {
                        Box(Modifier.width(bookWidth).zIndex(if (draggedId == manga.id) 8f else 0f)) {
                            SwipeToRemove(onRemove = { onRemove(manga) }, label = "删除收藏",
                                enabled = draggedId != manga.id) {
                                BookshelfBook(
                                    manga, repository, progress[manga.id],
                                    opening = opening?.let { it.manga.id == manga.id && it.loading } == true,
                                    onOpen = { onOpen(manga) },
                                    dragging = draggedId == manga.id,
                                    deleteArmed = draggedId == manga.id && deleteArmed,
                                    onDragStart = { onDragStart(manga, it) },
                                    onDragMove = onDragMove,
                                    onDragEnd = { onDragEnd(manga, it) },
                                    onDragCancel = onDragCancel,
                                    modifier = Modifier.fillMaxWidth().height(bookHeight),
                                )
                            }
                            RecordActionsMenu(
                                managedId == manga.id,
                                onDismiss = { onManage(null) },
                                onDetails = { onDetails(manga) },
                                shelfState = shelfStates[manga.id] ?: ShelfState.NORMAL,
                                onShelfState = { onShelfState(manga, it) },
                            )
                        }
                    }
                }
            }
            BookshelfPlank(Modifier.fillMaxWidth().padding(horizontal = 3.dp).height(22.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                books.forEach { manga ->
                    key(manga.id) {
                        val interaction = remember(manga.id) { MutableInteractionSource() }
                        val saved = progress[manga.id]
                        val shelfState = shelfStates[manga.id] ?: ShelfState.NORMAL
                        val stateLabel = when (shelfState) {
                            ShelfState.NORMAL -> ""
                            ShelfState.FOLLOWING -> "追更"
                            ShelfState.HOARDING -> "养肥"
                            ShelfState.PAUSED -> "搁置"
                        }
                        val hasKnownUpdate = shelfState == ShelfState.FOLLOWING && saved != null &&
                            manga.latestChapter.isNotBlank() && saved.chapterTitle.isNotBlank() &&
                            manga.latestChapter != saved.chapterTitle
                        Column(Modifier.width(bookWidth)
                            .combinedClickable(interactionSource = interaction, indication = null,
                                onClickLabel = if (saved == null) "开始阅读" else "继续阅读",
                                onLongClickLabel = "管理收藏", onLongClick = { onManage(manga.id) },
                                onClick = { onOpen(manga) })
                            .padding(top = 2.dp)) {
                            Text(manga.title, style = MaterialTheme.typography.bodyMedium,
                                fontSize = 13.sp, lineHeight = 18.sp,
                                fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            val progressText = saved?.let {
                                it.chapterTitle.ifBlank { "继续阅读" } +
                                    if (it.pageCount > 0) " · ${it.pageIndex + 1}/${it.pageCount}" else ""
                            } ?: "未读"
                            Text(
                                listOfNotNull(
                                    "更新".takeIf { hasKnownUpdate },
                                    stateLabel.takeIf(String::isNotBlank),
                                    progressText,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp, lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            // Keep native metrics; additional sources remain available in details.
                            MangaMetricBadges(manga,
                                Modifier.fillMaxWidth().padding(top = 4.dp)
                                    .heightIn(min = 32.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                                compact = true, maxScores = 1, showMore = false, plain = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyShelfGroup(filter: ShelfState?) {
    val label = when (filter) {
        ShelfState.FOLLOWING -> "追更"
        ShelfState.HOARDING -> "养肥"
        ShelfState.PAUSED -> "搁置"
        else -> "这个"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${label}分组还是空的", style = MaterialTheme.typography.titleSmall)
        Text("长按书名即可调整分组。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyBookshelf(onDiscover: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        BookshelfPlank(Modifier.fillMaxWidth().height(22.dp))
        BookshelfCabinetBay(Modifier.fillMaxWidth().height(210.dp)) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("把喜欢的故事放上来", style = MaterialTheme.typography.titleSmall)
                Text("收藏后，点书就能接着读。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onDiscover) { Text("去发现") }
            }
            BookshelfPlank(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 3.dp).height(22.dp))
        }
        BookshelfCabinetBay(Modifier.fillMaxWidth().height(176.dp)) {
            BookshelfPlank(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 3.dp).height(22.dp))
        }
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
