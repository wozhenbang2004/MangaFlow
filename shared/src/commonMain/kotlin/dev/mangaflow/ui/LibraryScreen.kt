package dev.mangaflow.ui

import dev.mangaflow.typography.MangaFontScene

import androidx.compose.foundation.layout.WindowInsetsSides

import androidx.compose.foundation.layout.windowInsetsPadding

import androidx.compose.foundation.layout.only

import androidx.compose.foundation.layout.asPaddingValues

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
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
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.rememberFeelFeedback
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.rememberDelayedBusy
import dev.mangaflow.ui.motion.motionPress
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.glass.mangaHazeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LibraryToolDestination { COLLECTIONS, STATS }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    favorites: List<Manga>, history: List<ReadingProgress>,
    repository: MangaRepository, libraryStore: LibraryStore, opening: LibraryOpenState?, active: Boolean,
    onOpen: (Manga) -> Unit, onDetails: (Manga) -> Unit,
    onContinue: (ReadingProgress) -> Unit, onOpenPageCollection: (PageCollection) -> Unit, onDiscover: () -> Unit,
    onRetryOpen: () -> Unit, onCancelOpen: () -> Unit,
    onRemoveFavorite: (Manga) -> Unit, onRemoveHistory: (String) -> Unit,
    backdrop: HazeState, navigation: @Composable () -> Unit, toolbar: @Composable (@Composable () -> Unit) -> Unit,
    wallpaper: @Composable () -> Unit = {},
    backgroundInParent: Boolean = false,
) {
    val density = LocalDensity.current
    val motion = LocalMotionEnabled.current
    val haptics = rememberFeelFeedback()
    val contentInsets = screenFitContentInsets().asPaddingValues()
    val topInset = contentInsets.calculateTopPadding()
    val bottomInset = contentInsets.calculateBottomPadding()
    var headerHeightPx by remember(density, topInset) { mutableIntStateOf(with(density) { (48.dp + topInset).roundToPx() }) }
    val headerPadding = with(density) { headerHeightPx.toDp() } + HeaderContentGap
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showCollections by rememberSaveable { mutableStateOf(false) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var shelfMenuExpanded by remember { mutableStateOf(false) }
    var libraryActionsOpen by remember { mutableStateOf(false) }
    var pendingLibraryTool by remember { mutableStateOf<LibraryToolDestination?>(null) }
    val shelfTabs = remember { listOf(GlassTabChoice("收藏", Icons.Outlined.Bookmarks), GlassTabChoice("最近阅读", Icons.Outlined.History)) }
    val preferredShelfWidth = morphGlassTabWidth(shelfTabs)
    val collections by libraryStore.pageCollections.collectAsStateWithLifecycle()
    val libraryReady by libraryStore.ready.collectAsStateWithLifecycle()
    var managedId by remember { mutableStateOf<String?>(null) }
    var chapterHistoryManga by remember { mutableStateOf<Manga?>(null) }
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
    val trashColor by animateColorAsState(
        if (trashArmed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(if (motion) MangaMotion.Quick else 0), label = "trash-target-color")
    val trashEmphasis = animateFloatAsState(if (trashArmed) 1.12f else 1f,
        if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else androidx.compose.animation.core.snap(),
        label = "trash-target-press")
    LaunchedEffect(trashArmed) { if (trashArmed) haptics.perform(FeelEvent.Threshold) }
    val favoritesPosition = rememberLazyGridState()
    val historyPosition = rememberLazyListState()
    LaunchedEffect(active, showHistory) {
        if (!active || showHistory) favoritesPosition.stopScroll()
        if (!active || !showHistory) historyPosition.stopScroll()
    }
    val progressById = remember(history) { history.associateBy { it.manga.id } }
    val snackbar = remember { SnackbarHostState() }
    val removalScope = rememberCoroutineScope()
    var pendingFavorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingHistory by remember { mutableStateOf<Set<String>>(emptySet()) }
    val visibleFavorites = remember(favorites, pendingFavorites) { favorites.filterNot { it.id in pendingFavorites } }
    val visibleHistory = remember(history, pendingHistory) { history.filterNot { it.manga.id in pendingHistory } }
    fun removeFavoriteWithUndo(manga: Manga) {
        if (manga.id in pendingFavorites) return
        pendingFavorites = pendingFavorites + manga.id
        snackbar.currentSnackbarData?.dismiss()
        removalScope.launch {
            var undone = false
            try {
                undone = snackbar.showSnackbar("已移出书架", actionLabel = "撤销",
                    withDismissAction = true, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
                if (undone) haptics.perform(dev.mangaflow.ui.motion.FeelEvent.Undo)
            } finally {
                if (!undone) withContext(NonCancellable) { onRemoveFavorite(manga) }
                pendingFavorites = pendingFavorites - manga.id
            }
        }
    }
    fun removeHistoryWithUndo(mangaId: String) {
        if (mangaId in pendingHistory) return
        pendingHistory = pendingHistory + mangaId
        snackbar.currentSnackbarData?.dismiss()
        removalScope.launch {
            var undone = false
            try {
                undone = snackbar.showSnackbar("已移除阅读记录", actionLabel = "撤销",
                    withDismissAction = true, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
                if (undone) haptics.perform(dev.mangaflow.ui.motion.FeelEvent.Undo)
            } finally {
                if (!undone) withContext(NonCancellable) { onRemoveHistory(mangaId) }
                pendingHistory = pendingHistory - mangaId
            }
        }
    }
    LaunchedEffect(active, showHistory) {
        if (!active) { shelfMenuExpanded = false; libraryActionsOpen = false; pendingLibraryTool = null }
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
    if (showStats) {
        val readingActivity by libraryStore.readingActivity.collectAsStateWithLifecycle()
        ReadingStatsSheet(readingActivity, repository,
            onDismiss = { showStats = false }, onOpenManga = { showStats = false; onOpen(it) })
    }
    chapterHistoryManga?.let { manga ->
        ChapterHistorySheet(manga, libraryStore, onDismiss = { chapterHistoryManga = null }, onContinue = onContinue)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val shelfWidth = preferredShelfWidth.coerceAtMost((maxWidth - 180.dp).coerceAtLeast(70.dp))
    val historyInset = 16.dp + ((maxWidth - MangaLayout.ListWidth) / 2f).coerceAtLeast(0.dp)
    Box(Modifier.fillMaxSize().mangaHazeSource(backdrop)
        .background(if (backgroundInParent) Color.Transparent else MaterialTheme.colorScheme.background)) {
        wallpaper()
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!libraryReady) LibraryRestoreStatus(libraryStore, Modifier.padding(top = headerPadding))
            else MotionTabContent(if (showHistory) 1 else 0, Modifier.fillMaxSize()) { tab ->
                if (tab == 1) {
                    LazyColumn(
                        state = historyPosition,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = historyInset, end = historyInset, top = headerPadding + 4.dp, bottom = 24.dp + bottomInset),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (visibleHistory.isEmpty()) item(key = "empty-history") {
                            EmptyMessage("从第一页开始", "读过的章节会留在这里，下次接着看。", "去发现", onDiscover)
                        }
                        items(visibleHistory, key = { "history-${it.manga.id}" }, contentType = { "reading-history" }) { progress ->
                            SwipeToRemove(onRemove = { removeHistoryWithUndo(progress.manga.id) }, label = "删除阅读记录",
                                modifier = Modifier.animateItem(
                                    fadeInSpec = if (motion) tween(MangaMotion.Content) else null,
                                    placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                    fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null)) {
                                ContinueRow(progress, repository, onContinue,
                                    onManage = { onCancelOpen(); managedId = progress.manga.id },
                                    onHistory = { onCancelOpen(); managedId = null; chapterHistoryManga = progress.manga })
                                RecordActionsMenu(managedId == progress.manga.id,
                                    onDismiss = { managedId = null },
                                    onDetails = { onDetails(progress.manga) })
                            }
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val available = (maxWidth - 32.dp).coerceAtLeast(1.dp)
                        val gap = 12.dp
                        val minimumWidth = 94.dp * fontScale
                        val maximum = if (maxWidth >= 840.dp) 6 else if (maxWidth >= 600.dp) 4 else 3
                        val perShelf = MangaLayout.columns(maxWidth, maximum, minimumWidth, gap, 16.dp)
                        val bookWidth = (available - gap * (perShelf - 1)) / perShelf
                        val bookHeight = bookWidth / .7f
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(perShelf),
                            state = favoritesPosition,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                                top = headerPadding + 8.dp, bottom = 32.dp + bottomInset),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (visibleFavorites.isEmpty()) item(key = "empty-collection", span = {
                                androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                            }) { EmptyBookshelf(onDiscover) }
                            gridItems(visibleFavorites, key = Manga::id, contentType = { "shelf-book" }) { manga ->
                                val saved = progressById[manga.id]
                                val dragged = draggedFavorite?.id == manga.id
                                val interaction = remember(manga.id) { MutableInteractionSource() }
                                Column(Modifier.fillMaxWidth().animateItem(
                                    fadeInSpec = if (motion) tween(MangaMotion.Content) else null,
                                    placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                    fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null).zIndex(if (dragged) 8f else 0f)) {
                                    SwipeToRemove(onRemove = { removeFavoriteWithUndo(manga) },
                                        label = "删除收藏", enabled = !dragged) {
                                        BookshelfBook(
                                            manga, repository, saved,
                                            opening = opening?.let { it.manga.id == manga.id && it.loading } == true,
                                            onOpen = { onOpen(manga) },
                                            dragging = dragged,
                                            deleteArmed = dragged && trashArmed,
                                            onDragStart = { point ->
                                                onCancelOpen()
                                                managedId = null
                                                draggedFavorite = manga
                                                dragPoint = point
                                            },
                                            onDragMove = { dragPoint = it },
                                            onDragEnd = { point ->
                                                val shouldRemove = draggedFavorite?.id == manga.id && isTrashDrop(point)
                                                draggedFavorite = null
                                                dragPoint = Offset.Unspecified
                                                if (shouldRemove) removeFavoriteWithUndo(manga)
                                            },
                                            onDragCancel = {
                                                draggedFavorite = null
                                                dragPoint = Offset.Unspecified
                                            },
                                            modifier = Modifier.fillMaxWidth().height(bookHeight),
                                        )
                                    }
                                    Box(Modifier.fillMaxWidth()) {
                                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                            .semantics(mergeDescendants = true) { contentDescription = manga.title }
                                            .combinedClickable(interactionSource = interaction, indication = null,
                                                onClickLabel = if (saved == null) "开始阅读" else "继续阅读",
                                                onLongClickLabel = "详情与目录",
                                                onLongClick = { onCancelOpen(); managedId = manga.id },
                                                onClick = { onOpen(manga) })
                                            .padding(horizontal = 2.dp)) {
                                            Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), modifier = Modifier.padding(top = 4.dp),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            val progressText = saved?.let {
                                                it.chapterTitle.ifBlank { "继续阅读" } +
                                                    if (it.pageCount > 0) " · ${it.pageIndex + 1}/${it.pageCount}" else ""
                                            } ?: "未读"
                                            Text(mangaNumberText(progressText), modifier = Modifier.padding(top = 2.dp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 11.sp, lineHeight = 15.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        RecordActionsMenu(managedId == manga.id,
                                            onDismiss = { managedId = null },
                                            onDetails = { onDetails(manga) })
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
    }
        Column(Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeightPx = it.height }
            .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
            .padding(horizontal = 8.dp, vertical = 2.dp)) {
            PageTitleHeader("书架", backdrop, navigation,
                leadingWidth = shelfWidth,
                leading = {
                    Spacer(Modifier.width(shelfWidth).height(morphGlassTabHeight()))
                },
                actions = {
                    toolbar {
                        Box {
                            MotionIconButton(onClick = {
                                pendingLibraryTool = null
                                libraryActionsOpen = !libraryActionsOpen
                                shelfMenuExpanded = false
                            },
                                enabled = active, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Outlined.MoreHoriz, "书架工具", Modifier.size(21.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            CompactGlassPopover(libraryActionsOpen, backdrop,
                                onDismiss = { libraryActionsOpen = false },
                                maximumWidth = (192.dp * density.fontScale.coerceAtLeast(1f)).coerceAtMost(208.dp),
                                onPresenceChange = { present ->
                                    if (!present) {
                                        val destination = pendingLibraryTool
                                        pendingLibraryTool = null
                                        if (active) when (destination) {
                                            LibraryToolDestination.COLLECTIONS -> showCollections = true
                                            LibraryToolDestination.STATS -> showStats = true
                                            null -> Unit
                                        }
                                    }
                                }) {
                                LibraryToolAction(Icons.Outlined.Collections, "画面收藏", collections.size.toString()) {
                                    pendingLibraryTool = LibraryToolDestination.COLLECTIONS
                                    libraryActionsOpen = false
                                }
                                LibraryToolAction(Icons.Outlined.BarChart, "阅读统计") {
                                    pendingLibraryTool = LibraryToolDestination.STATS
                                    libraryActionsOpen = false
                                }
                            }
                        }
                    }
                })
        }
        MorphGlassTabSelector(backdrop, shelfTabs, if (showHistory) 1 else 0, shelfWidth,
            maximumHeight = maxHeight,
            onToggle = { shelfMenuExpanded = !shelfMenuExpanded; libraryActionsOpen = false }, enabled = active,
            expanded = shelfMenuExpanded, onDismiss = { shelfMenuExpanded = false },
            onSelect = { index ->
                shelfMenuExpanded = false
                if (showHistory != (index == 1)) onCancelOpen()
                showHistory = index == 1
            })
        AnimatedVisibility(
            visible = draggedFavorite != null,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp),
            enter = fadeIn(tween(if (motion) MangaMotion.Content else 0)) +
                scaleIn(tween(if (motion) MangaMotion.Chrome else 0), initialScale = if (motion) .9f else 1f),
            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                scaleOut(tween(if (motion) MangaMotion.Quick else 0), targetScale = if (motion) .94f else 1f),
        ) {
            Surface(
                modifier = Modifier.size(72.dp).onGloballyPositioned { trashBounds = it.boundsInRoot() },
                shape = CircleShape,
                color = trashColor,
                tonalElevation = 0.dp,
                shadowElevation = if (trashArmed) 10.dp else 5.dp,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = if (trashArmed) "松手删除收藏" else "拖到这里删除",
                        modifier = Modifier.size(27.dp).graphicsLayer { scaleX = trashEmphasis.value; scaleY = scaleX },
                        tint = if (trashArmed) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp))
    }
}


/** Two quiet actions share the existing anchored glass shell, sized for their actual content. */
@Composable
private fun LibraryToolAction(icon: ImageVector, label: String, count: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .motionPress(interaction, pressedScale = .98f)
        .heightIn(min = 44.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(17.dp), tint = colors.onSurface.copy(alpha = .86f))
        Text(label, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp,
            color = colors.onSurface.copy(alpha = .86f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text(count, Modifier.widthIn(max = 44.dp), fontSize = 11.sp,
            color = colors.onSurfaceVariant.copy(alpha = .72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}


@Composable
private fun EmptyBookshelf(onDiscover: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("书架还空着", style = MaterialTheme.typography.titleSmall)
        Text("收藏喜欢的漫画，放好后点书接着读。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onDiscover) { Text("去发现") }
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
            Text(state.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), style = MaterialTheme.typography.labelMedium,
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
