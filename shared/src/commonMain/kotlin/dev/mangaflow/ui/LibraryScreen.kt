package dev.mangaflow.ui

import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkRemove
import androidx.compose.material.icons.outlined.ViewList
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.mangaflow.LibraryOpenState
import dev.mangaflow.account.CopyShelfSyncState
import dev.mangaflow.account.AdultShelfSyncState
import dev.mangaflow.account.CopyShelfMetadata
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.Manga
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.SavedResumeTarget
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.PageCollection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.BackHandler
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.rememberFeelFeedback
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.rememberDelayedBusy
import dev.mangaflow.ui.motion.motionPress
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.glass.mangaHazeSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class LibraryToolDestination { COLLECTIONS, STATS, COPY_SYNC, ACCOUNT }
private enum class UnifiedShelfScope { LOCAL, COPY, SITE }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    favorites: List<Manga>, history: List<ReadingProgress>,
    repository: UnifiedMangaRepository, libraryStore: LibraryStore, opening: LibraryOpenState?, active: Boolean,
    onOpen: (Manga) -> Unit, onDetails: (Manga) -> Unit,
    onContinue: (ReadingProgress) -> Unit, onOpenPageCollection: (PageCollection) -> Unit, onDiscover: () -> Unit,
    onRetryOpen: () -> Unit, onCancelOpen: () -> Unit,
    onRemoveFavorite: (Manga) -> Unit, onRemoveHistory: (String) -> Unit,
    backdrop: HazeState, navigation: @Composable () -> Unit, toolbar: @Composable (@Composable () -> Unit) -> Unit,
    wallpaper: @Composable () -> Unit = {},
    backgroundInParent: Boolean = false,
    includedSourceIds: Set<String>? = null,
    excludedSourceIds: Set<String> = emptySet(),
    copySyncState: CopyShelfSyncState? = null,
    onCopySync: (() -> Unit)? = null,
    siteSyncStates: Map<String, AdultShelfSyncState> = emptyMap(),
    onSiteSync: ((String) -> Unit)? = null,
    onAdultProgressSync: (() -> Unit)? = null,
    onImportCopy: (() -> Unit)? = null,
    preferredResumeTargets: Map<String, SavedResumeTarget> = emptyMap(),
    cloudFavorites: List<Manga> = emptyList(),
    cloudMetadata: Map<String, CopyShelfMetadata> = emptyMap(),
    cloudLoading: Boolean = false,
    cloudHasMore: Boolean = false,
    cloudError: String? = null,
    cloudComplete: Boolean = true,
    onAddCloudFavorite: ((Manga) -> Unit)? = null,
    onMoreCloudFavorites: (() -> Unit)? = null,
    onRefreshCloudFavorites: (() -> Unit)? = null,
    onOpenAccount: (() -> Unit)? = null,
    copyShelfAvailable: Boolean = true,
    adultProfile: Boolean = false,
    siteSources: List<BodySourceInfo> = emptyList(),
    siteAccountRevision: Int = 0,
    requestedSite: String? = null,
    onSiteRequestHandled: () -> Unit = {},
    onOpenSiteAccount: ((String) -> Unit)? = null,
    onAddSiteFavorite: ((Manga) -> Unit)? = null,
) {
    fun sourceIncluded(sourceId: String): Boolean =
        (includedSourceIds == null || sourceId in includedSourceIds) && sourceId !in excludedSourceIds
    val scopedFavorites = remember(favorites, includedSourceIds, excludedSourceIds) {
        favorites.filter { sourceIncluded(it.sourceId) }
    }
    val scopedHistory = remember(history, includedSourceIds, excludedSourceIds) {
        history.filter { sourceIncluded(it.manga.sourceId) }
    }
    val shelfTitle = if (adultProfile) "成人书架" else "书架"
    val historyTitle = "最近阅读"
    val desktop = platformKind == PlatformKind.DESKTOP
    val androidManga = platformKind == PlatformKind.ANDROID && !adultProfile
    val density = LocalDensity.current
    val motion = LocalMotionEnabled.current
    val haptics = rememberFeelFeedback()
    val contentInsets = screenFitContentInsets().asPaddingValues()
    val topInset = contentInsets.calculateTopPadding()
    val bottomInset = contentInsets.calculateBottomPadding()
    var headerHeightPx by remember(density, topInset) { mutableIntStateOf(with(density) { (48.dp + topInset).roundToPx() }) }
    val headerPadding = with(density) { headerHeightPx.toDp() } + HeaderContentGap
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var scopeName by rememberSaveable { mutableStateOf(UnifiedShelfScope.LOCAL.name) }
    val siteSource = siteSources.firstOrNull { it.id == scopeName }
    val shelfScope = when {
        siteSource != null -> UnifiedShelfScope.SITE
        copyShelfAvailable && scopeName == UnifiedShelfScope.COPY.name -> UnifiedShelfScope.COPY
        else -> UnifiedShelfScope.LOCAL
    }
    val siteShelves = siteSources.associate { source -> key(source.id) {
        source.id to remember(source, repository, siteAccountRevision) { ProfileSiteShelf(source, repository) }
    } }
    val siteShelf = siteSource?.let { siteShelves[it.id] }
    val siteVisible = active && !showHistory && siteShelf != null
    val siteJobs = rememberCoroutineScope()
    var siteJob by remember(siteShelf) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    DisposableEffect(siteShelf, siteVisible) { onDispose { siteJob?.cancel() } }
    LaunchedEffect(siteShelf, siteVisible) { if (siteVisible) siteShelf?.activate() }
    fun runSiteAction(action: suspend ProfileSiteShelf.() -> Unit) {
        if (siteVisible && siteShelf != null && !siteShelf.busy && siteJob?.isActive != true) {
            siteJob = siteJobs.launch { siteShelf.action() }
        }
    }
    LaunchedEffect(requestedSite, siteSources, active) {
        if (active && requestedSite != null && siteSources.any { it.id == requestedSite }) {
            scopeName = requestedSite
            showHistory = false
            onSiteRequestHandled()
        }
    }
    var showCollections by rememberSaveable { mutableStateOf(false) }
    var showStats by rememberSaveable { mutableStateOf(false) }
    var libraryActionsOpen by remember { mutableStateOf(false) }
    var pendingLibraryTool by remember { mutableStateOf<LibraryToolDestination?>(null) }
    val shelfOptions = remember(copyShelfAvailable, siteSources) { buildList {
        add(UnifiedShelfScope.LOCAL.name to "本机")
        if (copyShelfAvailable) add(UnifiedShelfScope.COPY.name to "拷贝")
        siteSources.forEach { add(it.id to it.name) }
    } }
    val shelfLabels = shelfOptions.map { it.second }
    val selectedShelfIndex = shelfOptions.indexOfFirst { it.first == scopeName }.coerceAtLeast(0)
    val hasShelfTabs = shelfOptions.size > 1
    val storedCollections by libraryStore.pageCollections.collectAsStateWithLifecycle()
    val collections = remember(storedCollections, includedSourceIds, excludedSourceIds) {
        storedCollections.filter { sourceIncluded(it.manga.sourceId) }
    }
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
    val trashArmed by remember { derivedStateOf { draggedFavorite != null && isTrashDrop(dragPoint) } }
    val trashColor by animateColorAsState(
        if (trashArmed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        tween(if (motion) MangaMotion.Quick else 0), label = "trash-target-color")
    val trashEmphasis = animateFloatAsState(if (trashArmed) 1.12f else 1f,
        if (motion) spring(MangaMotion.PressDamping, MangaMotion.PressStiffness) else androidx.compose.animation.core.snap(),
        label = "trash-target-press")
    LaunchedEffect(trashArmed) { if (trashArmed) haptics.perform(FeelEvent.Threshold) }
    val localBooksPosition = rememberLazyGridState()
    val copyBooksPosition = rememberLazyGridState()
    val siteBookPositions = siteSources.associate { source -> key(source.id) {
        source.id to rememberLazyGridState()
    } }
    val siteBooksPosition = siteSource?.let { siteBookPositions[it.id] }
    val favoritesPosition = when (shelfScope) {
        UnifiedShelfScope.LOCAL -> localBooksPosition
        UnifiedShelfScope.COPY -> copyBooksPosition
        UnifiedShelfScope.SITE -> siteBooksPosition ?: localBooksPosition
    }
    val historyPosition = rememberLazyListState()
    var desktopHistoryBook by rememberSaveable(adultProfile) { mutableStateOf<String?>(null) }
    LaunchedEffect(active, showHistory, shelfScope, siteSource?.id) {
        if (!active || showHistory || shelfScope != UnifiedShelfScope.LOCAL) localBooksPosition.stopScroll()
        if (!active || showHistory || shelfScope != UnifiedShelfScope.COPY) copyBooksPosition.stopScroll()
        for ((id, position) in siteBookPositions) {
            if (!active || showHistory || id != siteSource?.id) position.stopScroll()
        }
        if (!active || !showHistory) historyPosition.stopScroll()
    }
    val progressById = remember(scopedHistory) { scopedHistory.associateBy { it.manga.id } }
    val snackbar = remember { SnackbarHostState() }
    val removalScope = rememberCoroutineScope()
    fun removeSiteBook(book: Manga) {
        runSiteAction {
            remove(book)?.let { notice -> removalScope.launch { snackbar.showSnackbar(notice) } }
        }
    }
    var pendingFavorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    val historyRemoval = rememberHistoryRemoval(snackbar)
    // Projection, not an import: cloud-only titles remain remote and never gain local delete authority.
    // Same title is not proof of the same work. Only identical source-qualified IDs collapse.
    val localKeys = remember(scopedFavorites, pendingFavorites) {
        scopedFavorites.filterNot { it.id in pendingFavorites }.map { it.sourceId to it.id }.toSet()
    }
    val cloudKeys = remember(cloudFavorites) {
        cloudFavorites.asSequence().filter { it.sourceId == "copy" && !SourceKeys.isEncoded(it.id) }
            .map { it.sourceId to it.id }.toSet()
    }
    val unifiedBooks = remember(scopedFavorites, pendingFavorites, cloudFavorites, includedSourceIds, excludedSourceIds) {
        (scopedFavorites.filterNot { it.id in pendingFavorites } +
            cloudFavorites.filter { it.sourceId == "copy" && !SourceKeys.isEncoded(it.id) &&
                sourceIncluded(it.sourceId) }).distinctBy { it.sourceId to it.id }
    }
    val localAndCopyFavorites = remember(unifiedBooks, localKeys, cloudKeys,
        shelfScope, scopedHistory, cloudMetadata, libraryStore) {
        val filtered = unifiedBooks.filter { book ->
            val key = book.sourceId to book.id
            when (shelfScope) {
                UnifiedShelfScope.LOCAL -> key in localKeys
                UnifiedShelfScope.COPY -> key in cloudKeys
                UnifiedShelfScope.SITE -> false
            }
        }
        val stable = unifiedBooks.withIndex().associate { (index, manga) -> (manga.sourceId to manga.id) to index }
        fun stableIndex(manga: Manga) = stable[manga.sourceId to manga.id] ?: Int.MAX_VALUE
        when (shelfScope) {
            UnifiedShelfScope.LOCAL -> filtered.sortedWith(
                compareByDescending<Manga> { progressById[it.id]?.updatedAt ?: 0L }
                    .thenByDescending { libraryStore.favoriteAddedAt(it.id) ?: 0L }
                    .thenBy(::stableIndex))
            UnifiedShelfScope.COPY -> filtered.sortedWith(
                compareByDescending<Manga> { cloudMetadata[it.id]?.readAt ?: 0L }
                    .thenBy { cloudMetadata[it.id]?.readOrder ?: Int.MAX_VALUE }
                    .thenByDescending { cloudMetadata[it.id]?.joinedAt ?: 0L }
                    .thenBy(::stableIndex))
            UnifiedShelfScope.SITE -> emptyList()
        }
    }
    val visibleFavorites = siteShelf?.books ?: localAndCopyFavorites
    val visibleHistory = remember(scopedHistory, historyRemoval.pending) {
        scopedHistory.filterNot { it.manga.id in historyRemoval.pending }
    }
    val copyNotice = copySyncState?.takeIf {
        it.owner != null && (it.differences.isNotEmpty() || it.favoritesError != null || it.progressError != null)
    }
    val importCopy = onImportCopy ?: onCopySync
    fun removeFavoriteWithUndo(manga: Manga) {
        if (!sourceIncluded(manga.sourceId) || (manga.sourceId to manga.id) !in localKeys ||
            manga.id in pendingFavorites) return
        pendingFavorites = pendingFavorites + manga.id
        snackbar.currentSnackbarData?.dismiss()
        removalScope.launch {
            var undone = false
            try {
                undone = snackbar.showSnackbar(
                    if (cloudKeys.contains(manga.sourceId to manga.id)) "已移出本机 · 拷贝仍保留" else "已移出书架",
                    actionLabel = "撤销",
                    withDismissAction = true, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed
                if (undone) haptics.perform(dev.mangaflow.ui.motion.FeelEvent.Undo)
            } finally {
                if (!undone) withContext(NonCancellable) { onRemoveFavorite(manga) }
                pendingFavorites = pendingFavorites - manga.id
            }
        }
    }
    fun removeHistoryWithUndo(mangaId: String) {
        if (scopedHistory.any { it.manga.id == mangaId }) historyRemoval.request(mangaId, onRemoveHistory)
    }
    LaunchedEffect(active, showHistory, shelfScope) {
        if (!active) { libraryActionsOpen = false; pendingLibraryTool = null }
        managedId = null
        draggedFavorite = null
        dragPoint = Offset.Unspecified
        trashBounds = null
        if (!active || showHistory) onCancelOpen()
    }
    BackHandler(active && showHistory && !showCollections && !showStats &&
        chapterHistoryManga == null && !libraryActionsOpen) {
        showHistory = false
    }
    if (showCollections) PageCollectionsSheet(collections, repository,
        onOpen = { showCollections = false; onOpenPageCollection(it) },
        onRemove = { libraryStore.removePageCollection(it.key) },
        onDismiss = { showCollections = false })
    if (showStats && (!desktop || libraryReady)) {
        val readingActivity by libraryStore.readingActivity.collectAsStateWithLifecycle()
        val scopedActivity = remember(readingActivity, includedSourceIds, excludedSourceIds) {
            readingActivity.filter { sourceIncluded(it.manga.sourceId) }
        }
        ReadingStatsSheet(scopedActivity, repository,
            onDismiss = { showStats = false }, onOpenManga = { showStats = false; onOpen(it) })
    }
    chapterHistoryManga?.takeIf { sourceIncluded(it.sourceId) }?.let { manga ->
        ChapterHistorySheet(manga, libraryStore, onDismiss = { chapterHistoryManga = null }, onContinue = onContinue)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val shelfWidth = (maxWidth - 140.dp).coerceAtMost((if (adultProfile) 284 else 196).dp).coerceAtLeast(112.dp)
    val historyInset = 16.dp + ((maxWidth - if (desktop) 1040.dp else MangaLayout.ListWidth) / 2f).coerceAtLeast(0.dp)
    val splitHistory = desktop && maxWidth >= 720.dp * density.fontScale.coerceAtLeast(1f) &&
        maxHeight - headerPadding > 300.dp && visibleHistory.isNotEmpty()
    Box(Modifier.fillMaxSize().mangaHazeSource(backdrop)
        .background(if (backgroundInParent) Color.Transparent else MaterialTheme.colorScheme.background)) {
        wallpaper()
        Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!libraryReady) LibraryRestoreStatus(libraryStore, Modifier.padding(top = headerPadding))
            else MotionTabContent(if (showHistory) 1 else 0, Modifier.fillMaxSize()) { tab ->
                if (tab == 1 && splitHistory) {
                    DesktopReadingHistory(visibleHistory, repository, libraryStore, historyPosition, active && showHistory,
                        onContinue, ::removeHistoryWithUndo, Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp,
                            top = headerPadding + 8.dp, bottom = 16.dp + bottomInset), desktopHistoryBook,
                        { desktopHistoryBook = it }, onDetails,
                        header = if (copyNotice != null && importCopy != null) ({
                            CopyShelfImportHint(copyNotice, active && showHistory, importCopy)
                        }) else null)
                } else if (tab == 1) {
                    val today = localToday()
                    val dates = remember(visibleHistory) { readingHistoryByDay(visibleHistory) }
                    LazyColumn(
                        state = historyPosition,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = historyInset, end = historyInset, top = headerPadding + 4.dp, bottom = 24.dp + bottomInset),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (copyNotice != null && importCopy != null) item(key = "copy-differences") {
                            CopyShelfImportHint(copyNotice, active, importCopy)
                        }
                        if (visibleHistory.isEmpty()) item(key = "empty-history") {
                            if (desktop) CatalogueState("还没有阅读记录", "读过的作品和上次停下的页码会显示在这里。") {
                                TextButton(onClick = onDiscover) { Text("去发现") }
                            } else EmptyMessage("从第一页开始", "读过的章节会留在这里，下次接着看。", "去发现", onDiscover)
                        }
                        dates.forEach { (date, books) ->
                            item(key = "reading-date:$date", contentType = "history-day") {
                                val day = readingHistoryDay(date, today)
                                Text(mangaNumberText(day), style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp).semantics { heading() })
                            }
                            items(books, key = { "history-${it.manga.id}" }, contentType = { "reading-history" }) { progress ->
                                val historyActions = listOf(
                                    DesktopMenuAction("继续阅读", icon = Icons.Outlined.AutoStories) { onContinue(progress) },
                                    DesktopMenuAction("详情与目录", icon = Icons.Outlined.ViewList) { onDetails(progress.manga) },
                                    DesktopMenuAction("章节记录", icon = Icons.Outlined.History) { chapterHistoryManga = progress.manga },
                                    DesktopMenuAction("移除阅读记录", icon = Icons.Outlined.DeleteOutline, destructive = true) { removeHistoryWithUndo(progress.manga.id) })
                                DesktopContextMenuArea(historyActions) {
                                Box {
                                SwipeToRemove(enabled = !desktop, onRemove = { removeHistoryWithUndo(progress.manga.id) }, label = "删除阅读记录",
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = if (motion) tween(MangaMotion.Content) else null,
                                        placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                        fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null)) {
                                    Box(Modifier.padding(end = if (desktop) 40.dp else 0.dp)) {
                                    ContinueRow(progress, repository, onContinue,
                                        onManage = { onCancelOpen(); managedId = progress.manga.id },
                                        onHistory = { onCancelOpen(); managedId = null; chapterHistoryManga = progress.manga },
                                        namedHistory = adultProfile)
                                    }
                                    RecordActionsMenu(managedId == progress.manga.id,
                                        onDismiss = { managedId = null },
                                        onDetails = { onDetails(progress.manga) })
                                }
                                if (desktop) Box(Modifier.align(Alignment.CenterEnd)) {
                                    DesktopMoreButton(historyActions, "阅读记录操作")
                                }
                                }
                                }
                            }
                        }
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
                        val shelfInset = if (desktop) maxOf((maxWidth - 1360.dp) / 2f, when {
                            maxWidth >= 900.dp -> 32.dp
                            maxWidth < 420.dp -> 14.dp
                            else -> 22.dp
                        }) else 16.dp
                        val available = (maxWidth - shelfInset * 2).coerceAtLeast(1.dp)
                        val gap = if (desktop && maxWidth >= 720.dp) 20.dp else 12.dp
                        val minimumWidth = 94.dp * fontScale
                        val maximum = if (maxWidth >= 840.dp) 6 else if (maxWidth >= 600.dp) 4 else 3
                        val perShelf = if (desktop) MangaLayout.columns(maxWidth, 8, 150.dp * fontScale, gap, shelfInset)
                            else MangaLayout.columns(maxWidth, maximum, minimumWidth, gap, 16.dp)
                        val bookWidth = (available - gap * (perShelf - 1)) / perShelf
                        val bookHeight = bookWidth / .7f
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(perShelf),
                            state = favoritesPosition,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = shelfInset, end = shelfInset,
                                top = headerPadding + if (desktop) 16.dp else 8.dp, bottom = 32.dp + bottomInset),
                            horizontalArrangement = Arrangement.spacedBy(gap),
                            verticalArrangement = Arrangement.spacedBy(if (desktop) 14.dp else 8.dp),
                        ) {
                            val recent = visibleHistory.take(if (desktop) 3 else 1)
                            if (shelfScope == UnifiedShelfScope.LOCAL && recent.isNotEmpty()) item(
                                key = "resume-reading", span = {
                                    androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                                }) {
                                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("继续阅读", Modifier.weight(1f).padding(bottom = if (desktop) 10.dp else 0.dp)
                                            .semantics { heading() }, style = if (desktop) browseTitleStyle(24.sp, 31.sp)
                                                else MaterialTheme.typography.titleLarge)
                                        if (!desktop) TextButton(onClick = { onCancelOpen(); showHistory = true },
                                            enabled = active,
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                                            Text("阅读记录", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                    if (desktop) DesktopResumeShelf(recent, repository, active && !showHistory, onContinue, onDetails,
                                        onHistory = { chapterHistoryManga = it })
                                    else ContinueRow(recent.first(), repository, prominent = true,
                                        onContinue = { if (active) onContinue(it) })
                                }
                            }
                            val showCollectionHeading = desktop || adultProfile || shelfScope == UnifiedShelfScope.LOCAL && androidManga
                            if (showCollectionHeading && visibleFavorites.isNotEmpty()) item(
                                key = if (adultProfile) "adult-shelf-section" else "shelf-collection-section", span = {
                                    androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                                }) {
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    if (siteSource != null) ChapterSourceLogo(siteSource.id, siteSource.name, Modifier.size(19.dp))
                                    Text(when {
                                        siteSource != null -> "${siteSource.name}收藏"
                                        desktop && shelfScope == UnifiedShelfScope.COPY -> "拷贝收藏"
                                        else -> "我的收藏"
                                    }, Modifier.weight(1f, fill = false).semantics { heading() },
                                        style = if (desktop) browseTitleStyle(22.sp, 29.sp) else MaterialTheme.typography.titleLarge,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(visibleFavorites.size.toString() + if (siteShelf?.nextPage != null) "+" else "",
                                        style = mangaMetricValueStyle(),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.semantics {
                                            contentDescription = "已显示 ${visibleFavorites.size} 本收藏" +
                                                if (siteShelf?.nextPage != null) "，仍有后续页面" else ""
                                        })
                                }
                            }
                            if (siteShelf != null && siteShelf.accountAvailable == true) item(
                                key = "site-collection-controls", span = {
                                    androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                                }) {
                                SiteShelfControls(siteShelf, siteVisible,
                                    onFolder = { id -> runSiteAction { load(id) } },
                                    onRefresh = { runSiteAction { load(refreshFolders = true) } },
                                    onSync = onSiteSync?.takeIf { siteShelf.source.id in siteSyncStates }
                                        ?.let { action -> { action(siteShelf.source.id) } },
                                    syncCount = siteSyncStates[siteShelf.source.id]?.differences?.size ?: 0)
                            }
                            val collectionError = siteShelf?.collectionError
                            if (siteShelf != null && collectionError != null) item(key = "site-collection-error", span = {
                                androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                            }) {
                                SiteShelfStatus(
                                    collectionError,
                                    action = "重试",
                                    enabled = siteVisible && !siteShelf.busy,
                                    onAction = { runSiteAction { retryRead() } },
                                )
                            }
                            if (siteShelf != null && visibleFavorites.isEmpty() && collectionError == null &&
                                siteShelf.nextPage == null) item(key = "site-collection-state", span = {
                                androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                            }) {
                                when {
                                    siteShelf.busy || siteShelf.accountAvailable == null ->
                                        SiteShelfStatus("正在读取站点收藏…")
                                    siteShelf.accountAvailable == false -> EmptyMessage("登录${siteShelf.source.name}",
                                        "登录后可查看站点收藏。", "去登录".takeIf { onOpenSiteAccount != null },
                                        { onOpenSiteAccount?.invoke(siteShelf.source.id) })
                                    else -> EmptyMessage("这个收藏夹还是空的", "在${siteShelf.source.name}收藏的作品会显示在这里。",
                                        "去发现", onDiscover)
                                }
                            }
                            if (shelfScope == UnifiedShelfScope.COPY && copyNotice != null && importCopy != null) item(key = "copy-new-favorites", span = {
                                androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                            }) { CopyShelfImportHint(copyNotice, active, importCopy) }
                            if (shelfScope == UnifiedShelfScope.COPY && !cloudComplete &&
                                (cloudLoading || cloudError != null || cloudHasMore)) item(
                                key = "cloud-shelf-status", span = {
                                    androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                                }) {
                                TextButton(onClick = {
                                    if (cloudError != null) onRefreshCloudFavorites?.invoke()
                                    else if (cloudHasMore) onMoreCloudFavorites?.invoke()
                                }, enabled = active && !cloudLoading &&
                                    (cloudError != null || cloudHasMore),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp)) {
                                    if (cloudLoading) dev.mangaflow.ui.motion.MotionBusyIndicator(
                                        Modifier.size(13.dp), strokeWidth = 1.4.dp)
                                    else Icon(Icons.Outlined.Sync, null, Modifier.size(14.dp))
                                    Text(when {
                                        cloudError != null -> "拷贝收藏暂未读取 · 重试"
                                        cloudLoading -> "正在接入拷贝书架"
                                        else -> "继续读取拷贝收藏"
                                    }, Modifier.padding(start = 7.dp), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (siteShelf == null && visibleFavorites.isEmpty() &&
                                (shelfScope == UnifiedShelfScope.LOCAL || !cloudLoading && cloudComplete)) item(
                                key = "empty-collection", span = {
                                    androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                                }) {
                                when {
                                    shelfScope == UnifiedShelfScope.LOCAL && desktop -> CatalogueState("还没有收藏的漫画",
                                        "在作品详情点收藏，漫画就会加入书架。", Modifier.background(
                                            MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp)).padding(10.dp)) {
                                        TextButton(onClick = onDiscover) { Text("去发现") }
                                    }
                                    shelfScope == UnifiedShelfScope.LOCAL -> EmptyBookshelf(onDiscover, androidManga)
                                    onAddCloudFavorite == null && onOpenAccount != null ->
                                        EmptyMessage("登录拷贝账号", "登录后，这里显示拷贝收藏。", "去登录", onOpenAccount)
                                    else -> EmptyMessage("拷贝书架还是空的", "在拷贝收藏的漫画会显示在这里。", "去发现", onDiscover)
                                }
                            }
                            gridItems(visibleFavorites, key = { it.sourceId + ":" + it.id },
                                contentType = { "shelf-book" }) { manga ->
                                val local = (manga.sourceId to manga.id) in localKeys
                                val fromCopy = (manga.sourceId to manga.id) in cloudKeys
                                val remote = siteShelf != null
                                val allowLocalRemoval = local && !remote
                                val addBook = if (remote) onAddSiteFavorite else onAddCloudFavorite
                                val saved = progressById[manga.id]
                                val preferred = preferredResumeTargets[manga.id]?.takeIf { it.mangaId == manga.id }
                                val hasContinuation = preferred != null || saved != null
                                val dragged = draggedFavorite?.id == manga.id
                                val interaction = remember(manga.id) { MutableInteractionSource() }
                                val hovered by interaction.collectIsHoveredAsState()
                                var cardFocused by remember(manga.id) { mutableStateOf(false) }
                                var bookMenuOpen by remember(manga.id) { mutableStateOf(false) }
                                val desktopTools = desktop && (hovered || cardFocused || bookMenuOpen)
                                val bookActions = listOf(
                                    DesktopMenuAction(if (hasContinuation) "继续阅读" else "开始阅读", icon = Icons.Outlined.AutoStories) { onOpen(manga) },
                                    DesktopMenuAction("详情与目录", icon = Icons.Outlined.ViewList) { onCancelOpen(); onDetails(manga) },
                                    DesktopMenuAction("章节记录", icon = Icons.Outlined.History) { onCancelOpen(); chapterHistoryManga = manga },
                                    when {
                                        allowLocalRemoval -> DesktopMenuAction("移出本机收藏", icon = Icons.Outlined.BookmarkRemove, destructive = true) { removeFavoriteWithUndo(manga) }
                                        !local && addBook != null -> DesktopMenuAction("加入本机收藏", icon = Icons.Outlined.BookmarkAdd) { addBook(manga) }
                                        else -> null
                                    },
                                    if (remote) DesktopMenuAction("移出站点收藏", icon = Icons.Outlined.BookmarkRemove, destructive = true) { removeSiteBook(manga) } else null,
                                ).filterNotNull()
                                DesktopContextMenuArea(bookActions) {
                                Column(Modifier.fillMaxWidth().then(if (desktop) Modifier
                                    .hoverable(interaction).onFocusChanged { cardFocused = it.hasFocus }.focusGroup()
                                    else Modifier).animateItem(
                                    fadeInSpec = if (motion) tween(MangaMotion.Content) else null,
                                    placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                    fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null).zIndex(if (dragged) 8f else 0f)) {
                                    Box(Modifier.fillMaxWidth()) {
                                    SwipeToRemove(onRemove = { removeFavoriteWithUndo(manga) },
                                        label = "移出本机收藏", enabled = allowLocalRemoval && !dragged && !desktop) {
                                        BookshelfBook(
                                            manga, repository, saved,
                                            resumeTarget = preferred,
                                            opening = opening?.let { it.manga.id == manga.id && it.loading } == true,
                                            onOpen = { onOpen(manga) },
                                            dragging = dragged, dragEnabled = allowLocalRemoval,
                                            deleteArmed = dragged && trashArmed,
                                            onDragStart = { point ->
                                                onCancelOpen()
                                                managedId = null
                                                if (allowLocalRemoval) { draggedFavorite = manga; dragPoint = point }
                                            },
                                            onDragMove = { dragPoint = it },
                                            onDragEnd = { point ->
                                                val shouldRemove = draggedFavorite?.id == manga.id && isTrashDrop(point)
                                                draggedFavorite = null
                                                dragPoint = Offset.Unspecified
                                                if (shouldRemove && allowLocalRemoval) removeFavoriteWithUndo(manga)
                                            },
                                            onDragCancel = {
                                                draggedFavorite = null
                                                dragPoint = Offset.Unspecified
                                            },
                                            modifier = Modifier.fillMaxWidth().height(bookHeight),
                                        )
                                    }
                                    if (fromCopy) Row(Modifier.align(Alignment.TopStart).padding(6.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = .86f))
                                        .padding(horizontal = 5.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        if (local) Icon(Icons.Outlined.Bookmarks, "本机与拷贝", Modifier.size(13.dp))
                                        ChapterSourceLogo("copy", "拷贝", Modifier.size(15.dp))
                                    }
                                    if ((fromCopy || remote) && addBook != null) {
                                        Box(Modifier.align(Alignment.TopEnd)) {
                                            ShelfLocalAddition(local, active, desktopTools) { addBook(manga) }
                                        }
                                    }
                                    if (desktopTools) Row(Modifier.align(Alignment.TopEnd).padding(4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Surface(shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                MotionIconButton(onClick = { onCancelOpen(); onDetails(manga) },
                                                    modifier = Modifier.size(32.dp)) {
                                                    Icon(Icons.AutoMirrored.Outlined.MenuBook, "详情与目录", Modifier.size(17.dp))
                                                }
                                                DesktopMoreButton(bookActions, "书架作品操作", onExpandedChange = { bookMenuOpen = it })
                                            }
                                        }
                                    }
                                    }
                                    Box(Modifier.fillMaxWidth()) {
                                        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                            .semantics(mergeDescendants = true) { contentDescription = manga.title }
                                            .combinedClickable(interactionSource = interaction, indication = null,
                                                onClickLabel = if (hasContinuation) "继续阅读" else "开始阅读",
                                                onLongClickLabel = "详情与目录",
                                                onLongClick = { onCancelOpen(); managedId = manga.id },
                                                onClick = { onOpen(manga) })
                                            .padding(start = 1.dp, end = 1.dp, top = 8.dp, bottom = 9.dp)) {
                                            Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                                                style = MaterialTheme.typography.titleSmall.copy(lineBreak = LineBreak.Heading),
                                                fontSize = if (desktop) 16.sp else 15.sp, lineHeight = if (desktop) 22.sp else 20.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            // An adopted cloud chapter has no observed local page cursor.
                                            val progressText = preferred?.chapterTitle?.ifBlank { "继续阅读" } ?: saved?.let {
                                                it.chapterTitle.ifBlank { "继续阅读" } +
                                                    if (it.pageCount > 0) " · ${it.pageIndex + 1}/${it.pageCount}" else ""
                                            } ?: cloudMetadata[manga.id]?.chapterTitle
                                                ?.takeIf { fromCopy && it.isNotBlank() }?.let { "拷贝 · $it" } ?: "未读"
                                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (preferred == null && saved != null) ChapterReadMark(saved, size = 12.dp)
                                                Text(mangaNumberText(progressText), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    style = MaterialTheme.typography.labelSmall, fontSize = 12.sp,
                                                    lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            if (adultProfile && !remote) MangaBrowseSource(manga,
                                                Modifier.padding(top = 3.dp))
                                        }
                                        if (remote) CloudFavoriteActionsMenu(managedId == manga.id,
                                            onDismiss = { managedId = null }, onDetails = { onDetails(manga) },
                                            onRemove = { removeSiteBook(manga) },
                                            removeEnabled = siteVisible && siteShelf?.busy == false, removeLabel = "移出站点收藏")
                                        else RecordActionsMenu(managedId == manga.id,
                                            onDismiss = { managedId = null },
                                            onDetails = { onDetails(manga) })
                                    }
                                }
                                }
                            }
                            if (siteShelf != null && siteShelf.nextPage != null) item(key = "site-collection-more", span = {
                                androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                            }) {
                                val canLoad = siteVisible && !siteShelf.busy
                                when {
                                    siteShelf.loadingMore -> SiteShelfStatus("正在读取下一页收藏…")
                                    siteShelf.nextPageFailed -> SiteShelfStatus("下一页收藏暂未读取", "重试下一页", canLoad,
                                        onAction = { runSiteAction { retryRead() } })
                                    else -> TextButton(onClick = { runSiteAction { loadMore() } },
                                        enabled = canLoad, modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                                        Text("加载更多收藏")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (desktop && libraryReady && (!showHistory || !splitHistory)) {
                val track = Modifier.align(Alignment.CenterEnd).padding(top = headerPadding, bottom = bottomInset)
                if (showHistory) DesktopVerticalScrollbar(historyPosition, track)
                else DesktopVerticalScrollbar(favoritesPosition, track)
            }
            if (!showHistory) LibraryOpeningNotice(opening?.takeIf { sourceIncluded(it.manga.sourceId) }, onRetryOpen, onCancelOpen,
                Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        }
    }
        val selectShelf: (Int) -> Unit = { index ->
            val destination = shelfOptions.getOrNull(index)?.first ?: UnifiedShelfScope.LOCAL.name
            if (active && (destination != scopeName || showHistory)) {
                onCancelOpen()
                managedId = null
                scopeName = destination
                showHistory = false
            }
        }
        val scopeTabs: @Composable (Modifier, Dp) -> Unit = { modifier, height ->
            val scrollTabs = shelfLabels.size > 3
            MotionTabs(selectedShelfIndex, shelfLabels, selectShelf,
                modifier = modifier.then(if (scrollTabs) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                treatment = MotionTabTreatment.Soft, compact = scrollTabs, controlHeight = height,
                itemContent = if (adultProfile) ({ index, _, color ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        val source = siteSources.firstOrNull { it.id == shelfOptions[index].first }
                        if (source != null) ChapterSourceLogo(source.id, source.name, Modifier.size(16.dp))
                        Text(shelfLabels[index], color = color, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }) else null)
        }
        if (desktop) {
            val stackedTools = maxWidth < 680.dp * density.fontScale.coerceAtLeast(1f)
            val readingStatsAction: @Composable () -> Unit = {
                MotionIconButton(onClick = { showStats = true }, enabled = active && libraryReady,
                    modifier = Modifier.size(36.dp), tooltip = "阅读统计") {
                    Icon(Icons.Outlined.BarChart, "阅读统计", Modifier.size(19.dp))
                }
            }
            DesktopManagementToolbar(if (showHistory) historyTitle else shelfTitle, Modifier.align(Alignment.TopCenter)
                .onSizeChanged { headerHeightPx = it.height },
                navigation = navigation, minimumToolsWidth = 680.dp) {
                // Keep time statistics in view before the shelf tabs fill the narrow tool row.
                if (stackedTools) readingStatsAction()
                if (showHistory) MotionIconButton(onClick = { showHistory = false },
                    modifier = Modifier.size(36.dp), tooltip = "返回书架") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回书架", Modifier.size(19.dp))
                } else if (hasShelfTabs) scopeTabs(if (stackedTools) Modifier.width(240.dp * density.fontScale.coerceIn(1f, 1.5f))
                    else Modifier.widthIn(max = 280.dp).weight(1f, fill = false), 36.dp)
                if (!stackedTools) Spacer(Modifier.weight(1f))
                if (!showHistory) {
                    val openHistory = { onCancelOpen(); showHistory = true }
                    if (stackedTools) MotionIconButton(onClick = openHistory, enabled = active, modifier = Modifier.size(36.dp),
                        tooltip = "阅读记录") { Icon(Icons.Outlined.History, "阅读记录", Modifier.size(19.dp)) }
                    else TextButton(onClick = openHistory, enabled = active) {
                        Icon(Icons.Outlined.History, null, Modifier.size(16.dp))
                        Text("阅读记录", Modifier.padding(start = 5.dp), maxLines = 1)
                    }
                }
                val accountLabel = if (adultProfile) "来源账号" else "拷贝账号"
                if (onOpenAccount != null) MotionIconButton(onClick = onOpenAccount,
                    modifier = Modifier.size(36.dp), tooltip = accountLabel) {
                    Icon(Icons.Outlined.AccountCircle, accountLabel, Modifier.size(19.dp))
                }
                if (onCopySync != null) MotionIconButton(onClick = onCopySync, enabled = active,
                    modifier = Modifier.size(36.dp), tooltip = "查看拷贝差异") {
                    Icon(Icons.Outlined.Sync, "查看拷贝差异", Modifier.size(19.dp))
                }
                if (showHistory && onAdultProgressSync != null) MotionIconButton(onClick = onAdultProgressSync,
                    enabled = active, modifier = Modifier.size(44.dp), tooltip = "同步成人阅读进度") {
                    Icon(Icons.Outlined.Sync, "同步成人阅读进度", Modifier.size(19.dp))
                }
                MotionIconButton(onClick = { showCollections = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Collections, "画面收藏", Modifier.size(19.dp))
                }
                if (!stackedTools) readingStatsAction()
            }
        } else Column(Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeightPx = it.height }
            .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
            .padding(horizontal = 8.dp, vertical = 2.dp)) {
            val hasLeading = showHistory || hasShelfTabs
            PageTitleHeader(if (showHistory) historyTitle else shelfTitle, backdrop, navigation,
                leadingWidth = if (hasLeading) shelfWidth else 0.dp,
                leading = if (hasLeading) ({
                    if (showHistory) Row(Modifier.width(shelfWidth),
                        verticalAlignment = Alignment.CenterVertically) {
                        MotionIconButton(onClick = { showHistory = false }, modifier = Modifier.size(40.dp),
                            tooltip = "返回书架") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回书架", Modifier.size(19.dp))
                        }
                        Text(historyTitle, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else LiquidGlassSurface(backdrop, Modifier.width(shelfWidth), cornerRadius = 22.dp,
                        role = GlassSurfaceRole.NAVIGATION, trackPointer = true) {
                        scopeTabs(Modifier.fillMaxWidth(), 40.dp)
                    }
                }) else null,
                summary = if (hasLeading) null else ({
                    Text(shelfTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }),
                actions = {
                    toolbar {
                        if (showHistory && onAdultProgressSync != null) MotionIconButton(onClick = onAdultProgressSync,
                            enabled = active, modifier = Modifier.size(44.dp), tooltip = "同步成人阅读进度") {
                            Icon(Icons.Outlined.Sync, "同步成人阅读进度", Modifier.size(19.dp))
                        }
                        Box {
                            MotionIconButton(onClick = {
                                pendingLibraryTool = null
                                libraryActionsOpen = !libraryActionsOpen
                            }, enabled = active, modifier = Modifier.size(44.dp)) {
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
                                            LibraryToolDestination.COPY_SYNC -> onCopySync?.invoke()
                                            LibraryToolDestination.ACCOUNT -> onOpenAccount?.invoke()
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
                                if (onCopySync != null) LibraryToolAction(Icons.Outlined.Sync, "拷贝同步",
                                    copySyncState?.differences?.size?.takeIf { it > 0 }?.toString()) {
                                    pendingLibraryTool = LibraryToolDestination.COPY_SYNC
                                    libraryActionsOpen = false
                                }
                                if (onOpenAccount != null) LibraryToolAction(Icons.Outlined.AccountCircle,
                                    if (adultProfile) "来源账号" else "拷贝账号") {
                                    pendingLibraryTool = LibraryToolDestination.ACCOUNT
                                    libraryActionsOpen = false
                                }
                            }
                        }
                    }
                })
        }
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
        MangaSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp))
    }
}

/** A confirmed local addition briefly occupies its original badge on a cloud or site cover. */
@Composable
private fun ShelfLocalAddition(local: Boolean, active: Boolean, coveredByTools: Boolean, onClick: () -> Unit) {
    var wasLocal by remember { mutableStateOf(local) }
    var receipt by remember { mutableStateOf(false) }
    LaunchedEffect(local, active) {
        val added = active && local && !wasLocal
        wasLocal = local
        receipt = added
        if (added) {
            delay(1_050L)
            receipt = false
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val success = mangaStatusColors().success
    CopyShelfTool(!coveredByTools && (!local || receipt || active && !wasLocal)) {
        val action = if (local || coveredByTools) Modifier else Modifier.clickable(
            interactionSource = interaction, indication = null, enabled = active, role = Role.Button,
            onClickLabel = "加入本机书架", onClick = onClick).motionPress(interaction, active)
        Box(Modifier.size(44.dp).then(action), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(10.dp), color = colors.surface.copy(alpha = .9f)) {
                MotionSwap(local, Modifier.size(30.dp)) { added ->
                    Icon(if (added) Icons.Outlined.Check else Icons.Outlined.BookmarkAdd,
                        if (added) "已加入本机书架" else "加入本机书架", Modifier.size(16.dp),
                        tint = if (added) success else colors.onSurface)
                }
            }
            DesktopIconTooltip("加入本机书架", interaction, active && !local && !coveredByTools)
        }
    }
}

/** A read-only difference notice opens the comparison; it never starts an import. */
@Composable
private fun CopyShelfImportHint(state: CopyShelfSyncState, active: Boolean, onImport: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val favorites = state.differences.count { it.kind == dev.mangaflow.account.CopySyncDifferenceKind.FAVORITE }
    val progress = state.differences.size - favorites
    val description = "查看拷贝同步差异，收藏 " + favorites + " 项，进度 " + progress + " 项" +
        if (state.favoritesError != null || state.progressError != null) "，部分检查未完成" else ""
    TextButton(onClick = onImport, enabled = active,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp)) {
        Icon(Icons.Outlined.Sync, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        Text(if (state.differences.isNotEmpty()) "拷贝差异" else "检查未完成", Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (favorites > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bookmarks, null, Modifier.size(13.dp), tint = colors.onSurfaceVariant)
            Text(favorites.toString(), Modifier.padding(start = 3.dp, end = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        if (progress > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.History, null, Modifier.size(13.dp), tint = colors.onSurfaceVariant)
            Text(progress.toString(), Modifier.padding(start = 3.dp, end = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        dev.mangaflow.ui.motion.MotionSwap(state.busy, Modifier.size(18.dp)) { busy ->
            if (busy) dev.mangaflow.ui.motion.MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.4.dp)
            else Icon(Icons.Outlined.ChevronRight, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        }
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
private fun SiteShelfControls(
    shelf: ProfileSiteShelf,
    active: Boolean,
    onFolder: (String) -> Unit,
    onRefresh: () -> Unit,
    onSync: (() -> Unit)?,
    syncCount: Int,
) {
    val canEdit = active && !shelf.busy
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (shelf.folders.size > 1) Box(Modifier.weight(1f)) {
            val folders = shelf.folders
            MotionTabs(folders.indexOfFirst { it.id == shelf.folderId }.coerceAtLeast(0), folders.map { it.title },
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                onSelect = { index -> if (canEdit && folders[index].id != shelf.folderId) onFolder(folders[index].id) },
                compact = true, treatment = MotionTabTreatment.Soft, controlHeight = 44.dp)
        } else Spacer(Modifier.weight(1f))
        if (onSync != null) MotionIconButton(onClick = onSync, enabled = active,
            modifier = Modifier.size(44.dp), tooltip = if (syncCount > 0) "查看 $syncCount 项${shelf.source.name}收藏差异"
                else "同步${shelf.source.name}收藏") {
            Icon(Icons.Outlined.Sync, "同步${shelf.source.name}收藏", Modifier.size(18.dp))
        }
        MotionIconButton(onClick = onRefresh, enabled = canEdit,
            modifier = Modifier.size(44.dp), tooltip = "刷新站点收藏") {
            Icon(Icons.Outlined.Refresh, "刷新站点收藏", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SiteShelfStatus(
    message: String,
    action: String? = null,
    enabled: Boolean = false,
    onAction: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (action != null) TextButton(onClick = onAction, enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) {
            Text(action, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EmptyBookshelf(onDiscover: () -> Unit, editorial: Boolean) {
    Column(Modifier.fillMaxWidth().padding(
        horizontal = if (editorial) 0.dp else 24.dp, vertical = if (editorial) 14.dp else 36.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (editorial) "还没有收藏" else "书架还空着", style = MaterialTheme.typography.titleSmall)
        Text(if (editorial) "在作品详情点收藏，即可在这里找到它。" else "收藏喜欢的漫画，放好后点书接着读。",
            style = MaterialTheme.typography.bodySmall,
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
