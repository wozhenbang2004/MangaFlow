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
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.platform.*
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.ChapterProgress
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

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
    var info by remember(profile?.username) { mutableStateOf(false) }
    var confirmLogout by remember(profile?.username) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { info = false; confirmLogout = false } }
    val accountName = profile?.let { it.nickname.ifBlank { it.username } }.orEmpty()
    val title = if (profile == null) "连接拷贝" else "拷贝书架"
    val requestLogout = {
        info = false
        if (state.canRetrySync || state.syncingFavorites.isNotEmpty()) confirmLogout = true
        else onLogout()
    }
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
                MotionIconButton(onClick = { info = true }, enabled = active, modifier = Modifier.size(44.dp),
                    tooltip = "$accountName · 账号与同步") {
                    Box {
                        Icon(Icons.Outlined.PersonOutline,
                            if (state.canRetrySync) "$accountName，账号与同步，有待同步内容" else "$accountName，账号与同步", Modifier.size(21.dp))
                        if (state.canRetrySync) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(colors.error, CircleShape))
                    }
                }
            }
        }
    }
    if (platformKind == PlatformKind.DESKTOP) {
        DesktopManagementToolbar(title, modifier, navigation, minimumToolsWidth = 480.dp) {
            Text(accountName,
                Modifier.weight(1f).widthIn(max = 240.dp), style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onSearch != null) MotionIconButton(onClick = onSearch, enabled = active,
                modifier = Modifier.size(44.dp), tooltip = "搜索 · Ctrl+K") {
                Icon(Icons.Outlined.Search, "搜索", Modifier.size(20.dp))
            }
            actions()
        }
    } else PageTitleHeader(title, backdrop, navigation, modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        summary = {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }, actions = if (profile != null) actions else null)
    MotionDialog(info, onDismissRequest = { info = false }, maximumWidth = 400.dp) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 24.dp,
            role = GlassSurfaceRole.POPOVER, softCard = true) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(accountName, style = MaterialTheme.typography.titleMedium)
                profile?.let {
                    Text(it.username, style = MaterialTheme.typography.bodyMedium)
                }
                Text("这里展示拷贝账号的书架与最近阅读。阅读章节随账号同步，页码与分镜位置保存在本机。",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text("需要离线整理时，可将漫画加入本机书架。移出拷贝书架或退出账号，会保留本机书架、下载和阅读进度。",
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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = requestLogout, enabled = active) {
                        Text("退出账号", color = colors.error)
                    }
                    Spacer(Modifier.weight(1f))
                    MotionTextButton(onClick = { info = false }) { Text("完成") }
                }
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
    chapterHistoryRevision: Long,
    readChapterProgress: suspend (String, String) -> ChapterProgress?,
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
    val visibleHistoryKeys by remember(historyPosition) {
        derivedStateOf { historyPosition.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
    }
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
    // Keep one readable content plane over the wallpaper, rather than painting every row as a card.
    Column(Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(active).padding(top = contentTopPadding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (desktop) 20.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BrowseTabs(selected = if (showHistory) 1 else 0, labels = listOf("书架", "最近阅读"), modifier = Modifier.weight(1f)) {
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
                            feedLoading -> QuietLoading(if (historyTab) "正在读取最近阅读…" else "正在读取拷贝书架…")
                            feedError != null -> AccountError(feedError, false, feedMore)
                            else -> EmptyMessage(if (historyTab) "还没有阅读记录" else "拷贝书架还是空的",
                                if (historyTab) "拷贝账号的阅读章节会显示在这里，本机历史仍保留在书架。" else "在漫画详情中收藏后，会同步到拷贝书架。",
                                "刷新", feedRefresh)
                        }
                    } else if (historyTab) {
                        items(history, key = { "cloud-history:" + it.manga.id }, contentType = { "cloud-row" }) { entry ->
                            val manga = entry.manga
                            val exactProgress = rememberCloudChapterProgress(
                                owner = state.profile?.username.orEmpty(), entry = entry,
                                revision = chapterHistoryRevision,
                                visible = active && selected == mode && ("cloud-history:" + manga.id) in visibleHistoryKeys,
                                read = readChapterProgress,
                            )
                            CloudAccountItem(manga, repository, backdrop, grid = false, history = entry,
                                progress = localProgress[manga.id], local = manga.id in localIds,
                                exactProgress = exactProgress,
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
    exactProgress: ChapterProgress? = null,
) {
    val colors = MaterialTheme.colorScheme
    val transition = LocalMangaCoverTransition.current
    val cloudChapter = history?.let { entry -> entry.chapterTitle.ifBlank {
        if (entry.chapterId.isBlank()) "查看详情与目录" else "阅读章节"
    } }
    val localSource = progress?.let { saved ->
        runCatching { SourceKeys.decodeChapter(saved.manga.id, saved.chapterId).sourceId }.getOrNull()
    }
    val localSourceName = progress?.let { saved ->
        when (localSource) {
            null -> "来源未确认"
            "copy" -> null
            saved.manga.sourceId -> saved.manga.sourceName.ifBlank { sourceDisplayName(localSource) }
            else -> sourceDisplayName(localSource)
        }
    }
    val localChapter = progress?.chapterTitle?.ifBlank { "阅读章节" }
    val localAction = localChapter?.let {
        "继续本机 " + listOfNotNull(localSourceName, it).joinToString(" · ")
    } ?: "从本机进度继续"
    val differentLocalChapter = history != null && progress != null && history.chapterId != progress.chapterId
    val cloudAction = if (history?.chapterId?.isNotBlank() == true) "继续拷贝记录 $cloudChapter" else "查看详情与目录"
    val canManage = !local || onRemove != null
    val actions = buildList {
        if (!local) add(DesktopMenuAction("加入本机书架", onClick = onAdd))
        if (onRemove != null) add(DesktopMenuAction("移出拷贝书架", enabled = !syncing, onClick = onRemove))
    }
    val itemMenu: @Composable (Boolean) -> Unit = { showTrigger ->
        Box {
            if (showTrigger && canManage) MotionIconButton(onClick = onManage, modifier = Modifier.size(44.dp), tooltip = "管理书架") {
                Icon(Icons.Outlined.MoreHoriz, "管理书架", Modifier.size(19.dp))
            }
            CompactGlassPopover(managed && canManage, backdrop, maximumWidth = 216.dp, onDismiss = onDismiss) {
                if (!local) CompactMenuAction(Icons.Outlined.BookmarkAdd, "加入本机书架") { onDismiss(); onAdd() }
                if (onRemove != null) CompactMenuAction(Icons.Outlined.DeleteOutline, "移出拷贝书架",
                    enabled = !syncing, destructive = true) { onDismiss(); onRemove() }
            }
        }
    }
    DesktopContextMenuArea(actions) {
        if (grid) Box {
            CoverTile(manga, repository, dense = true, narrow = true, placement = placement,
                onLongClick = if (canManage) onManage else null, onRatings = onRatings, onClick = onClick)
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
                    onClickLabel = "打开${manga.title}详情",
                    onLongClickLabel = if (canManage) "管理书架" else null,
                    onLongClick = if (canManage) onManage else null, onClick = {
                        if (history == null) transition?.select?.invoke(MangaCoverKey(manga.id, placement))
                        if (history == null) onClick() else onDetails()
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
                        val position = exactProgress?.let {
                            if (it.pageCount > 0) "本机" + (it.pageIndex + 1) + "/" + it.pageCount + "页"
                            else "本机第" + (it.pageIndex + 1) + "页"
                        }
                        val chapterCaption = if (history.chapterId.isBlank()) cloudChapter.orEmpty()
                            else "拷贝 " + cloudChapter.orEmpty()
                        Text(listOfNotNull(chapterCaption, position).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (differentLocalChapter && onResumeLocal != null) {
                            MotionTextButton(onClick = onResumeLocal,
                                modifier = Modifier.heightIn(min = 36.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                                Text(localAction, style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    } else {
                        val caption = progress?.let { localAction.removePrefix("继续") }
                            ?: manga.authors.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (syncing) MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                else if (history?.chapterId?.isNotBlank() == true) MotionIconButton(
                    onClick = onClick, modifier = Modifier.size(44.dp), tooltip = cloudAction,
                ) { Icon(Icons.Outlined.PlayArrow, cloudAction, Modifier.size(20.dp), tint = colors.onSurface) }
                itemMenu(true)
            }
        }
    }
}

/** Only visible exact cloud identities read the small per-chapter key; the latest local chapter is separate. */
@Composable
private fun rememberCloudChapterProgress(
    owner: String, entry: CloudHistoryEntry, revision: Long, visible: Boolean,
    read: suspend (String, String) -> ChapterProgress?,
): ChapterProgress? {
    var progress by remember(owner, entry.manga.id, entry.chapterId) { mutableStateOf<ChapterProgress?>(null) }
    val currentRead by rememberUpdatedState(read)
    LaunchedEffect(owner, entry.manga.id, entry.chapterId, revision, visible) {
        if (!visible || entry.chapterId.isBlank()) return@LaunchedEffect
        try {
            progress = withContext(Dispatchers.IO) { currentRead(entry.manga.id, entry.chapterId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // An unavailable local position must not hide or block an otherwise usable cloud row.
            progress = null
        }
    }
    return progress
}
