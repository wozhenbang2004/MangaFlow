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
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.List
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.AccountUiState
import dev.mangaflow.account.CopyShelfMetadata
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
    onOpenSync: (() -> Unit)? = null,
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
                Text("此页展示拷贝收藏与拷贝阅读章节。导入只处理收藏；阅读进度在详情中单独采用。",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text("本机收藏的增删可主动同步到拷贝。退出账号会保留本机书架、下载和阅读进度。",
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
                if (onOpenSync != null) MotionTextButton(onClick = { info = false; onOpenSync() }, enabled = active) {
                    Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                    Text("收藏同步", Modifier.padding(start = 6.dp))
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

/** One native account collection. Local membership is an action, never cloud reading metadata. */
@Composable
internal fun CloudAccountLibrary(
    state: AccountUiState,
    repository: MangaRepository,
    backdrop: HazeState,
    active: Boolean,
    localFavorites: List<Manga>,
    localHistory: List<ReadingProgress>,
    contentTopPadding: Dp,
    onOpen: (Manga) -> Unit,
    onAddToLibrary: (Manga) -> Unit,
    onMoreFavorites: () -> Unit,
    onRefreshFavorites: () -> Unit,
    onRefreshAccount: () -> Unit,
    favoriteAddedAt: (String) -> Long? = { null },
    onOpenSync: (() -> Unit)? = null,
    cloudMetadata: Map<String, CopyShelfMetadata> = emptyMap(),
    metadataLoading: Boolean = false,
    metadataError: String? = null,
    onRetryMetadata: (() -> Unit)? = null,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val colors = MaterialTheme.colorScheme
    val bottomInset = screenFitContentInsets().asPaddingValues().calculateBottomPadding()
    var favoriteList by rememberSaveable { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(CopyShelfSort.RECENT.name) }
    var joinedAscending by rememberSaveable { mutableStateOf(false) }
    val sort = CopyShelfSort.entries.firstOrNull { it.name == sortName }
        ?: if (sortName in setOf("ADDED", "COPY")) CopyShelfSort.JOINED else CopyShelfSort.RECENT
    var sortMenu by remember { mutableStateOf(false) }
    val galleryPosition = rememberLazyGridState()
    val listPosition = rememberLazyGridState()
    val selected = if (favoriteList) 1 else 0
    val position = if (favoriteList) listPosition else galleryPosition
    val nativeFavorites = remember(state.favorites) { state.favorites.distinctBy(Manga::id) }
    val localIds = remember(localFavorites) { localFavorites.map { it.id }.toSet() }
    val favorites = remember(nativeFavorites, cloudMetadata, sort, joinedAscending) {
        val nativeOrder = nativeFavorites.withIndex().associate { it.value.id to it.index }
        val stable = compareBy<Manga> { nativeOrder[it.id] ?: Int.MAX_VALUE }
        when (sort) {
            CopyShelfSort.RECENT -> cloudChronology(nativeFavorites, cloudMetadata, recent = true, ascending = false, stable = stable)
            CopyShelfSort.JOINED -> cloudChronology(nativeFavorites, cloudMetadata, recent = false, ascending = joinedAscending, stable = stable)
            CopyShelfSort.HEAT -> nativeFavorites.sortedWith(
                compareByDescending<Manga> { it.popularity ?: it.totalPopularity ?: Long.MIN_VALUE }.then(stable))
            CopyShelfSort.TITLE -> nativeFavorites.sortedWith(compareBy<Manga> { it.title.lowercase() }.then(stable))
        }
    }
    var managedId by remember { mutableStateOf<String?>(null) }
    var ratings by remember { mutableStateOf<Manga?>(null) }
    val sourceFan = remember { MetricSourceFanState() }
    LaunchedEffect(active, selected, sort, joinedAscending) {
        sortMenu = false; managedId = null; ratings = null; sourceFan.clear()
    }
    LaunchedEffect(sort, joinedAscending) {
        galleryPosition.scrollToItem(0)
        listPosition.scrollToItem(0)
    }
    val loading = state.favoritesLoading
    val hasMore = state.favoritesHasMore
    val error = state.favoritesError
    val count = favorites.size
    LaunchedEffect(active, selected, loading, hasMore, error, count) {
        if (!active || loading || !hasMore || error != null || count == 0) return@LaunchedEffect
        snapshotFlow {
            val layout = position.layoutInfo
            layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >=
                layout.totalItemsCount - if (favoriteList) 3 else 7
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) onMoreFavorites() }
    }
    CompositionLocalProvider(LocalMetricSourceFan provides sourceFan) {
        Column(Modifier.fillMaxSize().mangaHazeSource(backdrop).motionInputGate(active).padding(top = contentTopPadding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (desktop) 20.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    MotionTextButton(onClick = { sortMenu = !sortMenu }, enabled = active,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) {
                        Icon(Icons.Outlined.Sort, null, Modifier.size(16.dp))
                        Text(sort.label, Modifier.weight(1f, fill = false).padding(start = 6.dp), style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.KeyboardArrowDown, "选择排序", Modifier.padding(start = 3.dp).size(15.dp))
                    }
                    CompactGlassPopover(sortMenu, backdrop, onDismiss = { sortMenu = false }, maximumWidth = 184.dp) {
                        CopyShelfSort.entries.forEach { option ->
                            MotionTextButton(onClick = { sortName = option.name; sortMenu = false },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { this.selected = sort == option },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(option.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurface, maxLines = 1)
                                if (sort == option) Icon(Icons.Outlined.Check, "已选择", Modifier.size(16.dp))
                                else Spacer(Modifier.size(16.dp))
                            }
                        }
                    }
                }
                if (metadataLoading) Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    MotionBusyIndicator(Modifier.size(12.dp), strokeWidth = 1.3.dp)
                } else if (metadataError != null && onRetryMetadata != null) MotionIconButton(
                    onClick = onRetryMetadata, enabled = active, modifier = Modifier.size(44.dp),
                    tooltip = "拷贝记录读取失败，点击重试") {
                    Icon(Icons.Outlined.Refresh, "拷贝记录读取失败，重试", Modifier.size(16.dp), tint = colors.error)
                }
                if (sort == CopyShelfSort.JOINED) MotionIconButton(onClick = { joinedAscending = !joinedAscending },
                    enabled = active, modifier = Modifier.size(44.dp),
                    tooltip = if (joinedAscending) "加入拷贝时间：旧到新，切换为新到旧" else "加入拷贝时间：新到旧，切换为旧到新") {
                    Icon(if (joinedAscending) Icons.Outlined.ArrowUpward else Icons.Outlined.ArrowDownward,
                        if (joinedAscending) "加入拷贝时间：旧到新" else "加入拷贝时间：新到旧", Modifier.size(16.dp))
                }
                if (onOpenSync != null) MotionIconButton(onClick = onOpenSync, enabled = active,
                    modifier = Modifier.size(44.dp), tooltip = "收藏导入与提交") {
                    Icon(Icons.Outlined.SwapHoriz, "收藏导入与提交", Modifier.size(18.dp))
                }
                MotionIconButton(onClick = { favoriteList = !favoriteList }, enabled = active,
                    modifier = Modifier.size(44.dp), tooltip = if (favoriteList) "封面网格" else "紧凑列表") {
                    Icon(if (favoriteList) Icons.Outlined.ViewModule else Icons.Outlined.List,
                        if (favoriteList) "切换封面网格" else "切换紧凑列表", Modifier.size(18.dp))
                }
            }
            state.error?.let {
                Box(Modifier.fillMaxWidth().heightIn(max = 112.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)) { AccountError(it, state.busy, onRefreshAccount) }
            }
            if (hasMore && count > 0) Text("已载入 $count 本",
                Modifier.padding(horizontal = if (desktop) 20.dp else 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            MotionTabContent(selected, Modifier.weight(1f).fillMaxWidth()) { mode ->
                val grid = mode == 0
                val feedPosition = if (grid) galleryPosition else listPosition
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = if (!grid) GridCells.Fixed(1)
                        else if (maxWidth < 600.dp) GridCells.Fixed(if (fontScale >= 1.4f || maxWidth < 340.dp) 2 else 3)
                        else GridCells.Adaptive(148.dp * fontScale)
                    LazyVerticalGrid(columns, state = feedPosition, modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = if (desktop) 20.dp else 16.dp,
                            end = if (desktop) 20.dp else 16.dp, top = 6.dp, bottom = 24.dp + bottomInset),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(if (grid) 12.dp else 2.dp)) {
                        if (favorites.isEmpty()) item(key = "cloud-empty", span = { GridItemSpan(maxLineSpan) }) {
                            when {
                                loading -> QuietLoading("正在读取拷贝书架…")
                                error != null -> AccountError(error, false, onMoreFavorites)
                                else -> EmptyMessage("拷贝书架还是空的", "在拷贝收藏的漫画会显示在这里。", "刷新", onRefreshFavorites)
                            }
                        } else items(favorites, key = { "cloud-favorite:" + it.id },
                            contentType = { if (grid) "cloud-cover" else "cloud-row" }) { manga ->
                            CloudAccountItem(manga, repository, backdrop, grid = grid,
                                metadata = cloudMetadata[manga.id], local = manga.id in localIds,
                                syncing = manga.id in state.syncingFavorites,
                                placement = if (grid) "cloud-favorite-cover" else "cloud-favorite-row",
                                managed = managedId == manga.id, onManage = { managedId = manga.id },
                                onDismiss = { managedId = null }, onClick = { onOpen(manga) },
                                onAdd = { onAddToLibrary(manga) }, onRatings = { ratings = manga })
                        }
                        if (favorites.isNotEmpty() && (loading && !state.favoritesRefreshing || error != null || hasMore)) {
                            item(key = "cloud-more", span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    when {
                                        loading && !state.favoritesRefreshing -> MotionBusyIndicator(Modifier.size(20.dp), strokeWidth = 1.8.dp)
                                        error != null -> AccountError(error, loading, onMoreFavorites)
                                        !loading -> MotionTextButton(onClick = onMoreFavorites) { Text("加载更多") }
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

private enum class CopyShelfSort(val label: String) {
    RECENT("最近阅读"), HEAT("热度"), JOINED("加入拷贝时间"), TITLE("名称")
}

/** Native time-ordered ranks remain ranks; no local timestamp or list index is invented as a date. */
private fun cloudChronology(
    mangas: List<Manga>, metadata: Map<String, CopyShelfMetadata>, recent: Boolean, ascending: Boolean,
    stable: Comparator<Manga>,
): List<Manga> {
    fun record(manga: Manga): CopyShelfMetadata? = metadata[manga.id]?.takeIf {
        !recent || it.chapterId.isNotBlank() || (it.readAt ?: 0L) > 0L
    }
    val order: (Manga) -> Int? = { manga -> record(manga)?.let { if (recent) it.readOrder else it.joinedOrder } }
    val time: (Manga) -> Long? = { manga -> record(manga)?.let { if (recent) it.readAt else it.joinedAt }?.takeIf { it > 0L } }
    return if (mangas.any { order(it) != null }) {
        mangas.sortedWith(compareBy<Manga> { order(it) == null }
            .then(if (ascending) compareByDescending(order) else compareBy(order)).then(stable))
    } else mangas.sortedWith(compareBy<Manga> { time(it) == null }
        .then(if (ascending) compareBy(time) else compareByDescending(time)).then(stable))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CloudAccountItem(
    manga: Manga, repository: MangaRepository, backdrop: HazeState, grid: Boolean,
    metadata: CopyShelfMetadata?, local: Boolean, syncing: Boolean, placement: String,
    managed: Boolean, onManage: () -> Unit, onDismiss: () -> Unit,
    onClick: () -> Unit, onAdd: () -> Unit, onRatings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val canManage = !local
    val actions = if (canManage) listOf(DesktopMenuAction("加入本机书架", onClick = onAdd)) else emptyList()
    val itemMenu: @Composable (Boolean) -> Unit = { showTrigger ->
        Box {
            if (showTrigger && canManage) MotionIconButton(onClick = onManage,
                modifier = Modifier.size(44.dp), tooltip = "加入本机书架") {
                Icon(Icons.Outlined.BookmarkAdd, "加入本机书架", Modifier.size(17.dp))
            }
            CompactGlassPopover(managed && canManage, backdrop, maximumWidth = 192.dp, onDismiss = onDismiss) {
                CompactMenuAction(Icons.Outlined.BookmarkAdd, "加入本机书架") { onDismiss(); onAdd() }
            }
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    // Adapt CoverTile's source geometry/press/shared-cover path, with account-owned metadata.
    DesktopContextMenuArea(actions) {
        if (grid) Box {
            Column(Modifier.fillMaxWidth()
                .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = "查看${manga.title}", onLongClickLabel = if (canManage) "加入本机书架" else null,
                    onLongClick = if (canManage) onManage else null, onClick = {
                        transition?.select?.invoke(MangaCoverKey(manga.id, placement)); onClick()
                    }).motionPress(interaction)) {
                MangaCover(manga, repository, Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                    .mangaSharedCover(manga.id, placement).mangaCoverCard(12.dp))
                Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                    modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                    minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MangaMetricBadges(manga, Modifier.fillMaxWidth().padding(top = 2.dp, start = 2.dp, end = 2.dp),
                    compact = true, showMore = false, plain = true, valueSize = 12.sp, onExpand = onRatings)
                CloudChapterCaption(metadata, Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 1.dp))
            }
            if (platformKind == PlatformKind.DESKTOP && canManage) Box(Modifier.align(Alignment.TopEnd)) {
                LiquidGlassSurface(backdrop, Modifier, cornerRadius = 16.dp, role = GlassSurfaceRole.NAVIGATION) { itemMenu(true) }
            } else if (canManage) Box(Modifier.align(Alignment.TopEnd)) { itemMenu(false) }
        } else {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = "打开${manga.title}详情",
                    onLongClickLabel = if (canManage) "加入本机书架" else null,
                    onLongClick = if (canManage) onManage else null, onClick = {
                        transition?.select?.invoke(MangaCoverKey(manga.id, placement)); onClick()
                    }).motionPress(interaction, pressedScale = .988f)
                .heightIn(min = 76.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                MangaCover(manga, repository, Modifier.width(42.dp).height(60.dp)
                    .mangaSharedCover(manga.id, placement).mangaCoverCard(8.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    CloudChapterCaption(metadata, Modifier.fillMaxWidth())
                }
                if (syncing) MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.4.dp)
                itemMenu(true)
            }
        }
    }
}

@Composable
private fun CloudChapterCaption(metadata: CopyShelfMetadata?, modifier: Modifier = Modifier) {
    if (metadata?.chapterId.isNullOrBlank()) return
    val colors = MaterialTheme.colorScheme
    val label = metadata?.chapterTitle.orEmpty()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(Icons.Outlined.History, if (label.isBlank()) "拷贝已保存阅读章节" else null,
            Modifier.size(12.dp), tint = colors.onSurfaceVariant)
        if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
