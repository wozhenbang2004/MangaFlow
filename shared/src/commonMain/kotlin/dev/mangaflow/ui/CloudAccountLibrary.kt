package dev.mangaflow.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.AccountUiState
import dev.mangaflow.account.CloudHistoryEntry
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.platform.*
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

/** Account identity and ordinary actions share the existing root-header geometry. */
@Composable
internal fun CloudAccountHeader(
    state: AccountUiState,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    active: Boolean,
    refreshing: Boolean,
    refreshError: String?,
    onSearch: (() -> Unit)?,
    onRefresh: () -> Unit,
    onRetrySync: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile
    val colors = MaterialTheme.colorScheme
    var menu by remember(profile?.username) { mutableStateOf(false) }
    var info by remember(profile?.username) { mutableStateOf(false) }
    var confirmLogout by remember(profile?.username) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { menu = false; info = false; confirmLogout = false } }
    val actions: @Composable () -> Unit = {
        if (profile != null) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (state.busy || refreshing) MotionBusyIndicator(Modifier.size(17.dp), strokeWidth = 1.6.dp)
                else MotionIconButton(onClick = onRefresh, enabled = active, modifier = Modifier.size(44.dp),
                    tooltip = if (refreshError != null) "列表读取失败，点击重试" else "刷新当前列表") {
                    Icon(Icons.Outlined.Refresh, if (refreshError != null) "列表读取失败，点击重试" else "刷新当前列表",
                        Modifier.size(20.dp), tint = if (refreshError != null) colors.error else colors.onSurface)
                }
            }
            Box {
                MotionIconButton(onClick = { menu = !menu }, enabled = active, modifier = Modifier.size(44.dp),
                    tooltip = if (state.canRetrySync) "账号 · 有待同步内容" else "账号操作") {
                    Box {
                        Icon(Icons.Outlined.MoreHoriz,
                            if (state.canRetrySync) "账号操作，有待同步内容" else "账号操作", Modifier.size(21.dp))
                        if (state.canRetrySync) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(colors.error, CircleShape))
                    }
                }
                CompactGlassPopover(menu, backdrop, onDismiss = { menu = false }) {
                    CompactMenuAction(Icons.Outlined.Info, "账号与同步", onClick = { menu = false; info = true })
                    if (state.canRetrySync) CompactMenuAction(Icons.Outlined.Refresh, "重试同步",
                        enabled = active && !state.busy, onClick = { menu = false; onRetrySync() })
                    CompactMenuAction(Icons.Outlined.Logout, "退出账号", onClick = {
                        menu = false
                        if (state.canRetrySync || state.syncingFavorites.isNotEmpty()) confirmLogout = true
                        else onLogout()
                    })
                }
            }
        }
    }
    if (platformKind == PlatformKind.DESKTOP) {
        DesktopManagementToolbar("拷贝账号", modifier, navigation, minimumToolsWidth = 480.dp) {
            Text(profile?.let { it.nickname.ifBlank { it.username } }.orEmpty(),
                Modifier.weight(1f).widthIn(max = 240.dp), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onSearch != null) MotionIconButton(onClick = onSearch, enabled = active,
                modifier = Modifier.size(44.dp), tooltip = "搜索 · Ctrl+K") {
                Icon(Icons.Outlined.Search, "搜索", Modifier.size(20.dp))
            }
            actions()
        }
    } else PageTitleHeader("拷贝账号", backdrop, navigation, modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        summary = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(28.dp).background(colors.surfaceVariant.copy(alpha = .7f), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.PersonOutline, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
                Column {
                    Text(profile?.nickname?.ifBlank { profile.username } ?: "拷贝账号",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (profile != null) Text("拷贝", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }, actions = if (profile != null) actions else null)
    MotionDialog(info, onDismissRequest = { info = false }, maximumWidth = 400.dp) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 24.dp,
            role = GlassSurfaceRole.POPOVER, softCard = true) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("账号与同步", style = MaterialTheme.typography.titleMedium)
                profile?.let {
                    Text(it.username, style = MaterialTheme.typography.bodyMedium)
                }
                Text("云端收藏与阅读章节跟随拷贝账号；精确页码与分镜位置保存在本机。",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text("云端漫画可单独加入本机书架。移除云端收藏或退出账号，会保留本机书架、下载和阅读进度。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text("其他来源的阅读进度单独保存。", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
                refreshError?.let {
                    Text("当前列表", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    AccountError(it, state.busy || refreshing, onRefresh)
                }
                state.syncMessage?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (state.canRetrySync) colors.error else colors.onSurfaceVariant)
                }
                if (state.canRetrySync) MotionTextButton(onClick = onRetrySync, enabled = active && !state.busy) {
                    Text("重试同步")
                }
                MotionTextButton(onClick = { info = false }, modifier = Modifier.align(Alignment.End)) { Text("知道了") }
            }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false }, shape = RoundedCornerShape(24.dp), containerColor = colors.surface,
        title = { Text("仍有内容待同步") },
        text = { Text("现在退出会停止本次同步。本机书架、下载和阅读进度会保留。") },
        confirmButton = { MotionTextButton(onClick = { confirmLogout = false; onLogout() }) { Text("退出账号") } },
        dismissButton = { MotionTextButton(onClick = { confirmLogout = false }) { Text("保留登录") } },
    )
}

/** Adapt discovery covers, library rows and retained Ovio tabs; no second reading implementation. */
@Composable
internal fun CloudAccountLibrary(
    state: AccountUiState,
    repository: MangaRepository,
    backdrop: HazeState,
    active: Boolean,
    showHistory: Boolean,
    onShowHistory: (Boolean) -> Unit,
    localFavorites: List<Manga>,
    localHistory: List<ReadingProgress>,
    contentTopPadding: Dp,
    onOpen: (Manga) -> Unit,
    onContinue: (CloudHistoryEntry) -> Unit,
    onContinueLocal: (ReadingProgress) -> Unit,
    onAddToLibrary: (Manga) -> Unit,
    onRemoveFavorite: (Manga) -> Unit,
    onMoreFavorites: () -> Unit,
    onMoreHistory: () -> Unit,
    onRefreshFavorites: () -> Unit,
    onRefreshHistory: () -> Unit,
    onRefreshAccount: () -> Unit,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val bottomInset = screenFitContentInsets().asPaddingValues().calculateBottomPadding()
    var favoriteList by rememberSaveable { mutableStateOf(false) }
    val selected = if (showHistory) 2 else if (favoriteList) 1 else 0
    val galleryPosition = rememberLazyGridState()
    val favoriteListPosition = rememberLazyGridState()
    val historyPosition = rememberLazyGridState()
    val position = when (selected) { 0 -> galleryPosition; 1 -> favoriteListPosition; else -> historyPosition }
    val favorites = remember(state.favorites) { state.favorites.distinctBy(Manga::id) }
    val history = remember(state.history) { state.history.distinctBy { it.manga.id } }
    val localIds = remember(localFavorites) { localFavorites.map { it.id }.toSet() }
    val localProgress = remember(localHistory) { localHistory.associateBy { it.manga.id } }
    var managedId by remember { mutableStateOf<String?>(null) }
    var ratings by remember { mutableStateOf<Manga?>(null) }
    val sourceFan = remember { MetricSourceFanState() }
    val coverTransition = LocalMangaCoverTransition.current
    LaunchedEffect(active, selected) {
        managedId = null; ratings = null; sourceFan.clear()
    }
    val loading = if (showHistory) state.historyLoading else state.favoritesLoading
    val hasMore = if (showHistory) state.historyHasMore else state.favoritesHasMore
    val error = if (showHistory) state.historyError else state.favoritesError
    val count = if (showHistory) history.size else favorites.size
    val more = if (showHistory) onMoreHistory else onMoreFavorites
    // Only the selected, visible feed paginates. Errors require an explicit retry.
    LaunchedEffect(active, selected, loading, hasMore, error, count) {
        if (!active || loading || !hasMore || error != null || count == 0) return@LaunchedEffect
        snapshotFlow {
            val layout = position.layoutInfo
            layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >=
                layout.totalItemsCount - (if (selected == 0) 7 else 3)
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) more() }
    }
    CompositionLocalProvider(LocalMetricSourceFan provides sourceFan) {
    Column(Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(active).padding(top = contentTopPadding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (desktop) 20.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BrowseTabs(selected = if (showHistory) 1 else 0, labels = listOf("收藏", "记录"), modifier = Modifier.weight(1f)) {
                onShowHistory(it == 1)
            }
            if (!showHistory) MotionIconButton(onClick = { favoriteList = !favoriteList },
                enabled = active, modifier = Modifier.size(44.dp),
                tooltip = if (favoriteList) "封面网格" else "紧凑列表") {
                Icon(if (favoriteList) Icons.Outlined.ViewModule else Icons.Outlined.List,
                    if (favoriteList) "切换封面网格" else "切换紧凑列表", Modifier.size(20.dp))
            } else Spacer(Modifier.size(44.dp))
        }
        state.error?.let {
            Box(Modifier.fillMaxWidth().heightIn(max = 112.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)) { AccountError(it, state.busy, onRefreshAccount) }
        }
        MotionTabContent(selected, Modifier.weight(1f).fillMaxWidth()) { mode ->
            val historyTab = mode == 2
            val grid = mode == 0
            val feedLoading = if (historyTab) state.historyLoading else state.favoritesLoading
            val refreshing = if (historyTab) state.historyRefreshing else state.favoritesRefreshing
            val feedError = if (historyTab) state.historyError else state.favoritesError
            val feedHasMore = if (historyTab) state.historyHasMore else state.favoritesHasMore
            val feedMore = if (historyTab) onMoreHistory else onMoreFavorites
            val feedRefresh = if (historyTab) onRefreshHistory else onRefreshFavorites
            val feedEmpty = if (historyTab) history.isEmpty() else favorites.isEmpty()
            val feedPosition = when (mode) { 0 -> galleryPosition; 1 -> favoriteListPosition; else -> historyPosition }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = if (!grid) GridCells.Fixed(1)
                    else if (maxWidth < 600.dp) GridCells.Fixed(if (fontScale >= 1.4f || maxWidth < 340.dp) 2 else 3)
                    else GridCells.Adaptive(148.dp * fontScale)
                LazyVerticalGrid(columns, state = feedPosition, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = if (desktop) 20.dp else 16.dp,
                        end = if (desktop) 20.dp else 16.dp, top = 6.dp, bottom = 24.dp + bottomInset),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(if (grid) 14.dp else 2.dp)) {
                    if (feedEmpty) item(key = "cloud-empty", span = { GridItemSpan(maxLineSpan) }) {
                        when {
                            feedLoading -> QuietLoading(if (historyTab) "正在读取云端记录…" else "正在读取云端收藏…")
                            feedError != null -> AccountError(feedError, false, feedMore)
                            else -> EmptyMessage(if (historyTab) "还没有云端记录" else "还没有云端收藏",
                                if (historyTab) "在拷贝阅读后的章节会显示在这里。" else "拷贝账号收藏的漫画会显示在这里。",
                                "刷新", feedRefresh)
                        }
                    } else if (historyTab) {
                        items(history, key = { "cloud-history:" + it.manga.id }, contentType = { "cloud-row" }) { entry ->
                            val manga = entry.manga
                            CloudAccountItem(manga, repository, backdrop, grid = false, history = entry,
                                progress = localProgress[manga.id], local = manga.id in localIds,
                                syncing = manga.id in state.syncingFavorites, placement = "cloud-history-row",
                                managed = managedId == manga.id, onManage = { managedId = manga.id },
                                onDismiss = { managedId = null }, onClick = { onContinue(entry) },
                                onDetails = { coverTransition?.select?.invoke(null); onOpen(manga) },
                                onResumeLocal = localProgress[manga.id]?.let { p -> { onContinueLocal(p) } },
                                onAdd = { onAddToLibrary(manga) }, onRemove = null, onRatings = { ratings = manga })
                        }
                    } else {
                        items(favorites, key = { "cloud-favorite:" + it.id }, contentType = { if (grid) "cloud-cover" else "cloud-row" }) { manga ->
                            CloudAccountItem(manga, repository, backdrop, grid = grid, history = null,
                                progress = localProgress[manga.id], local = manga.id in localIds,
                                syncing = manga.id in state.syncingFavorites, placement = if (grid) "cloud-favorite-cover" else "cloud-favorite-row",
                                managed = managedId == manga.id, onManage = { managedId = manga.id },
                                onDismiss = { managedId = null }, onClick = { onOpen(manga) },
                                onDetails = { coverTransition?.select?.invoke(null); onOpen(manga) },
                                onResumeLocal = localProgress[manga.id]?.let { p -> { onContinueLocal(p) } },
                                onAdd = { onAddToLibrary(manga) }, onRemove = { onRemoveFavorite(manga) }, onRatings = { ratings = manga })
                        }
                    }
                    if (!feedEmpty && (feedLoading && !refreshing || feedError != null || feedHasMore)) {
                        item(key = "cloud-more", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                when {
                                    feedLoading && !refreshing -> MotionBusyIndicator(Modifier.size(20.dp), strokeWidth = 1.8.dp)
                                    feedError != null -> AccountError(feedError, feedLoading, feedMore)
                                    !feedLoading -> MotionTextButton(onClick = feedMore) { Text("加载更多") }
                                }
                            }
                        }
                    }
                }
                if (desktop) DesktopVerticalScrollbar(feedPosition,
                    Modifier.align(Alignment.CenterEnd).padding(bottom = bottomInset))
            }
        }
    }
    MetricSourceFan(sourceFan, backdrop)
    }
    ratings?.let { DetailRatingSheet(it, backdrop) { ratings = null } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudAccountItem(
    manga: Manga, repository: MangaRepository, backdrop: HazeState, grid: Boolean,
    history: CloudHistoryEntry?, progress: ReadingProgress?, local: Boolean, syncing: Boolean,
    placement: String, managed: Boolean, onManage: () -> Unit, onDismiss: () -> Unit,
    onClick: () -> Unit, onDetails: () -> Unit, onResumeLocal: (() -> Unit)?,
    onAdd: () -> Unit, onRemove: (() -> Unit)?, onRatings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val transition = LocalMangaCoverTransition.current
    val actions = buildList {
        add(DesktopMenuAction("详情与目录", onClick = onDetails))
        if (onResumeLocal != null) add(DesktopMenuAction("从本机进度继续", onClick = onResumeLocal))
        add(DesktopMenuAction(if (local) "已在本机书架" else "加入本机书架", enabled = !local, onClick = onAdd))
        add(DesktopMenuAction("各站评分", onClick = onRatings))
        if (onRemove != null) add(DesktopMenuAction("移除云端收藏", enabled = !syncing, onClick = onRemove))
    }
    val itemMenu: @Composable (Boolean) -> Unit = { showTrigger ->
        Box {
            if (showTrigger) MotionIconButton(onClick = onManage, modifier = Modifier.size(44.dp), tooltip = "漫画操作") {
                Icon(Icons.Outlined.MoreHoriz, "漫画操作", Modifier.size(19.dp))
            }
            CompactGlassPopover(managed, backdrop, onDismiss = onDismiss) {
                CompactMenuAction(Icons.Outlined.Info, "详情与目录") { onDismiss(); onDetails() }
                if (onResumeLocal != null) CompactMenuAction(Icons.Outlined.PlayArrow, "从本机进度继续") {
                    onDismiss(); onResumeLocal()
                }
                CompactMenuAction(if (local) Icons.Outlined.BookmarkAdded else Icons.Outlined.BookmarkAdd,
                    if (local) "已在本机书架" else "加入本机书架", enabled = !local) { onDismiss(); onAdd() }
                CompactMenuAction(Icons.Outlined.Info, "各站评分") { onDismiss(); onRatings() }
                if (onRemove != null) CompactMenuAction(Icons.Outlined.DeleteOutline, "移除云端收藏",
                    enabled = !syncing, destructive = true) { onDismiss(); onRemove() }
            }
        }
    }
    DesktopContextMenuArea(actions) {
        if (grid) Box {
            CoverTile(manga, repository, dense = true, narrow = true, placement = placement,
                onLongClick = onManage, onRatings = onRatings, onClick = onClick)
            if (local || syncing) Box(Modifier.align(Alignment.TopStart).padding(5.dp).size(22.dp)
                .background(colors.surface.copy(alpha = .94f), CircleShape), contentAlignment = Alignment.Center) {
                if (syncing) MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.4.dp)
                else Icon(Icons.Outlined.BookmarkAdded, "已在本机书架", Modifier.size(13.dp), tint = colors.onSurfaceVariant)
            }
            // Long press retains a native accessibility action; pointer users also get a visible trigger.
            Box(Modifier.align(Alignment.TopEnd)) {
                if (platformKind == PlatformKind.DESKTOP) LiquidGlassSurface(backdrop, Modifier, cornerRadius = 16.dp,
                    role = GlassSurfaceRole.NAVIGATION) { itemMenu(true) }
                else itemMenu(false)
            }
        } else {
            val interaction = remember { MutableInteractionSource() }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = if (history?.chapterId?.isNotBlank() == true) "继续阅读云端章节" else "查看详情与目录",
                    onLongClickLabel = "漫画操作", onLongClick = onManage, onClick = {
                        if (history == null) transition?.select?.invoke(MangaCoverKey(manga.id, placement))
                        onClick()
                    }).motionPress(interaction, pressedScale = .988f)
                .heightIn(min = 76.dp).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MangaCover(manga, repository, Modifier.width(42.dp).height(60.dp)
                    .mangaSharedCover(manga.id, placement).mangaCoverCard(8.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (history != null) {
                        val chapter = history.chapterTitle.ifBlank { if (history.chapterId.isBlank()) "查看详情与目录" else "继续云端章节" }
                        val exact = progress?.takeIf { it.chapterId == history.chapterId }
                        val position = exact?.let { if (it.pageCount > 0) "本机 " + (it.pageIndex + 1) + "/" + it.pageCount + " 页"
                            else "本机第 " + (it.pageIndex + 1) + " 页" }
                        Text(listOfNotNull(chapter, position).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    } else MangaMetricBadges(manga, compact = true, showMore = false, plain = true, onExpand = onRatings)
                }
                if (syncing) MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                else if (history?.chapterId?.isNotBlank() == true) Icon(Icons.Outlined.PlayArrow, null,
                    Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                itemMenu(true)
            }
        }
    }
}
