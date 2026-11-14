package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ViewModule
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

private enum class CopyAccountAction { SYNC, LOGOUT }
private data class PendingCopyAccountAction(val action: CopyAccountAction, val owner: String)

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
    var pendingAction by remember(profile?.username) { mutableStateOf<PendingCopyAccountAction?>(null) }
    LaunchedEffect(active) { if (!active) { pendingAction = null; info = false; confirmLogout = false } }
    val accountName = profile?.let { it.nickname.ifBlank { it.username } }.orEmpty()
    val title = if (profile == null) "连接拷贝" else "拷贝书架"
    val requestLogout: () -> Unit = {
        profile?.username?.let { pendingAction = PendingCopyAccountAction(CopyAccountAction.LOGOUT, it) }
        info = false
    }
    val actions: @Composable () -> Unit = {
        if (profile != null) {
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                MotionSwap(state.busy || refreshing, Modifier.size(44.dp)) { busy ->
                    if (busy) MotionBusyIndicator(Modifier.size(17.dp), strokeWidth = 1.6.dp)
                    else MotionIconButton(onClick = onRefresh, enabled = active && !state.busy && !refreshing, modifier = Modifier.size(44.dp),
                        tooltip = if (refreshError != null) "列表读取失败，点击重试" else "刷新当前列表") {
                        Icon(Icons.Outlined.Refresh, if (refreshError != null) "列表读取失败，点击重试" else "刷新当前列表",
                            Modifier.size(20.dp), tint = if (refreshError != null) colors.error else colors.onSurface)
                    }
                }
            }
            Box {
                MotionIconButton(onClick = { pendingAction = null; info = true }, enabled = active, modifier = Modifier.size(44.dp),
                    tooltip = "$accountName · 账号与同步") {
                    Box {
                        Icon(Icons.Outlined.PersonOutline,
                            if (state.canRetrySync) "$accountName，账号与同步，有待同步内容" else "$accountName，账号与同步", Modifier.size(21.dp))
                        MotionSwap(state.canRetrySync, Modifier.align(Alignment.TopEnd).size(8.dp)) { pending ->
                            if (pending) Box(Modifier.size(6.dp).background(colors.error, CircleShape))
                            else Spacer(Modifier.size(6.dp))
                        }
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
            MotionSwap(title) { label -> Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }, actions = if (profile != null) actions else null)
    key(profile?.username) {
    MotionDialog(info, onDismissRequest = { pendingAction = null; info = false }, maximumWidth = 400.dp,
        onClosed = {
            val pending = pendingAction
            pendingAction = null
            if (active && pending != null && profile?.username == pending.owner) when (pending.action) {
                CopyAccountAction.SYNC -> onOpenSync?.invoke()
                CopyAccountAction.LOGOUT -> if (state.canRetrySync || state.syncingFavorites.isNotEmpty()) confirmLogout = true else onLogout()
            }
        }) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 24.dp,
            role = GlassSurfaceRole.POPOVER, softCard = true) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(accountName, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleMedium)
                profile?.let {
                    Text(it.username, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                Text("此页展示拷贝收藏与拷贝阅读章节。导入只处理收藏；阅读进度在详情中单独采用。",
                    modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Text("本机收藏的增删可主动同步到拷贝。退出账号会保留本机书架、下载和阅读进度。",
                    modifier = Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text("其他来源的阅读进度单独保存。", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
                CopyShelfValue(refreshError) { message ->
                    Column(Modifier.padding(bottom = 12.dp)) { Text("当前列表", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        AccountError(message, state.busy || refreshing, onRefresh) }
                }
                CopyShelfValue(state.syncMessage?.takeIf(String::isNotBlank)) {
                    Text(it, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall,
                        color = if (state.canRetrySync) colors.error else colors.onSurfaceVariant)
                }
                if (onOpenSync != null) MotionTextButton(onClick = {
                    profile?.username?.let { pendingAction = PendingCopyAccountAction(CopyAccountAction.SYNC, it) }
                    info = false
                }, enabled = active, modifier = Modifier.padding(bottom = 12.dp)) {
                    Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                    Text("收藏同步", Modifier.padding(start = 6.dp))
                }
                CopyShelfReveal(state.canRetrySync) {
                    MotionTextButton(onClick = onRetrySync, enabled = active && state.canRetrySync && !state.busy, modifier = Modifier.padding(bottom = 12.dp)) { Text("重试同步") }
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
    MotionDialog(confirmLogout, onDismissRequest = { confirmLogout = false }, maximumWidth = 400.dp) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 24.dp,
            role = GlassSurfaceRole.POPOVER, softCard = true) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("仍有内容待同步", style = MaterialTheme.typography.titleMedium)
                Text("现在退出会停止本次同步。本机书架、下载和阅读进度会保留。",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MotionTextButton(onClick = { confirmLogout = false }) { Text("保留登录") }
                    MotionTextButton(onClick = { confirmLogout = false; onLogout() }, enabled = active && profile != null) {
                        Text("退出账号", color = colors.error)
                    }
                }
            }
        }
    }
    }
}

/** One native account collection. Local membership is an action, never cloud reading metadata. */
@OptIn(ExperimentalFoundationApi::class)
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
    val motion = LocalMotionEnabled.current
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
    val sortKey = sort.name + ":" + joinedAscending
    var appliedSort by rememberSaveable { mutableStateOf(sortKey) }
    LaunchedEffect(sortKey, selected, active) {
        if (appliedSort == sortKey) return@LaunchedEffect
        appliedSort = sortKey
        val foreground = if (selected == 0) galleryPosition else listPosition
        val background = if (selected == 0) listPosition else galleryPosition
        background.scrollToItem(0)
        if (motion && active) foreground.animateScrollToItem(0) else foreground.scrollToItem(0)
    }
    val sortRotation by animateFloatAsState(if (sortMenu) 180f else 0f,
        if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(), label = "copy-sort-menu")
    val orderRotation by animateFloatAsState(if (joinedAscending) 180f else 0f,
        if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else snap(), label = "copy-sort-order")
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
                        MotionSwap(sort.label, Modifier.weight(1f, fill = false).padding(start = 6.dp)) { label ->
                            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Outlined.KeyboardArrowDown, "选择排序", Modifier.padding(start = 3.dp).size(15.dp)
                            .graphicsLayer { rotationZ = sortRotation })
                    }
                    CompactGlassPopover(sortMenu, backdrop, onDismiss = { sortMenu = false }, maximumWidth = 184.dp) {
                        CopyShelfSort.entries.forEach { option ->
                            MotionTextButton(onClick = { sortName = option.name; sortMenu = false },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { this.selected = sort == option },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(option.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurface, maxLines = 1)
                                MotionSwap(sort == option, Modifier.size(16.dp)) { checked ->
                                    if (checked) Icon(Icons.Outlined.Check, "已选择", Modifier.size(16.dp))
                                    else Spacer(Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                CopyShelfTool(metadataLoading || metadataError != null && onRetryMetadata != null) {
                    MotionSwap(if (metadataLoading) 0 else if (metadataError != null) 1 else 2, Modifier.size(44.dp)) { phase ->
                        when (phase) {
                            0 -> MotionBusyIndicator(Modifier.size(12.dp), strokeWidth = 1.3.dp)
                            1 -> MotionIconButton(onClick = { onRetryMetadata?.invoke() }, enabled = active && !metadataLoading && metadataError != null,
                                modifier = Modifier.size(44.dp), tooltip = "拷贝记录读取失败，点击重试") {
                                Icon(Icons.Outlined.Refresh, "拷贝记录读取失败，重试", Modifier.size(16.dp), tint = colors.error)
                            }
                            else -> Spacer(Modifier.size(44.dp))
                        }
                    }
                }
                CopyShelfTool(sort == CopyShelfSort.JOINED) {
                    MotionIconButton(onClick = { joinedAscending = !joinedAscending },
                        enabled = active && sort == CopyShelfSort.JOINED, modifier = Modifier.size(44.dp),
                        tooltip = if (joinedAscending) "加入拷贝时间：旧到新，切换为新到旧" else "加入拷贝时间：新到旧，切换为旧到新") {
                        Icon(Icons.Outlined.ArrowDownward,
                            if (joinedAscending) "加入拷贝时间：旧到新" else "加入拷贝时间：新到旧",
                            Modifier.size(16.dp).graphicsLayer { rotationZ = orderRotation })
                    }
                }
                if (onOpenSync != null) MotionIconButton(onClick = onOpenSync, enabled = active,
                    modifier = Modifier.size(44.dp), tooltip = "收藏导入与提交") {
                    Icon(Icons.Outlined.SwapHoriz, "收藏导入与提交", Modifier.size(18.dp))
                }
                MotionIconButton(onClick = { favoriteList = !favoriteList }, enabled = active,
                    modifier = Modifier.size(44.dp), tooltip = if (favoriteList) "封面网格" else "紧凑列表") {
                    MotionSwap(favoriteList, Modifier.size(18.dp)) { list ->
                        Icon(if (list) Icons.Outlined.ViewModule else Icons.Outlined.List,
                            if (list) "切换封面网格" else "切换紧凑列表", Modifier.size(18.dp))
                    }
                }
            }
            CopyShelfValue(state.error) {
                Box(Modifier.fillMaxWidth().heightIn(max = 112.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)) { AccountError(it, state.busy, onRefreshAccount) }
            }
            CopyShelfValue(count.takeIf { hasMore && it > 0 }) { loaded ->
                Text("已载入 $loaded 本", Modifier.padding(horizontal = if (desktop) 20.dp else 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            MotionTabContent(selected, Modifier.weight(1f).fillMaxWidth(), soft = true) { mode ->
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
                            Box(Modifier.animateItem(
                                fadeInSpec = if (motion) tween(MangaMotion.ListEnter) else null,
                                placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                fadeOutSpec = if (motion) tween(MangaMotion.ListExit) else null)) {
                                MotionTabContent(CloudShelfPlaceholder(if (loading) 0 else if (error != null) 1 else 2, error),
                                    contentKey = { it.phase }, order = { it.phase }, fillContent = false, soft = true) { pane ->
                                    when (pane.phase) {
                                        0 -> QuietLoading("正在读取拷贝书架…")
                                        1 -> AccountError(pane.error.orEmpty(), false, onMoreFavorites)
                                        else -> EmptyMessage("拷贝书架还是空的", "在拷贝收藏的漫画会显示在这里。", "刷新", onRefreshFavorites)
                                    }
                                }
                            }
                        } else items(favorites, key = { "cloud-favorite:" + it.id },
                            contentType = { if (grid) "cloud-cover" else "cloud-row" }) { manga ->
                            CloudAccountItem(manga, repository, backdrop, grid = grid,
                                modifier = Modifier.animateItem(
                                    fadeInSpec = if (motion) tween(MangaMotion.ListEnter) else null,
                                    placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                                    fadeOutSpec = if (motion) tween(MangaMotion.ListExit) else null),
                                metadata = cloudMetadata[manga.id], local = manga.id in localIds,
                                syncing = manga.id in state.syncingFavorites,
                                placement = if (grid) "cloud-favorite-cover" else "cloud-favorite-row",
                                managed = managedId == manga.id, onManage = { managedId = manga.id },
                                onDismiss = { managedId = null }, onClick = { onOpen(manga) },
                                onAdd = { onAddToLibrary(manga) }, onRatings = { ratings = manga })
                        }
                        item(key = "cloud-more", span = { GridItemSpan(maxLineSpan) }) {
                            val footer = if (favorites.isNotEmpty() && (loading && !state.favoritesRefreshing || error != null || hasMore))
                                CloudShelfPlaceholder(if (loading && !state.favoritesRefreshing) 0 else if (error != null) 1 else 2, error) else null
                            CopyShelfValue(footer, Modifier.fillMaxWidth()) { pane ->
                                Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    when (pane.phase) {
                                        0 -> MotionBusyIndicator(Modifier.size(20.dp), strokeWidth = 1.8.dp)
                                        1 -> AccountError(pane.error.orEmpty(), loading, onMoreFavorites)
                                        else -> MotionTextButton(onClick = onMoreFavorites, enabled = active && !loading && hasMore) { Text("加载更多") }
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

private data class CloudShelfPlaceholder(val phase: Int, val error: String?)

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
    modifier: Modifier = Modifier,
    managed: Boolean, onManage: () -> Unit, onDismiss: () -> Unit,
    onClick: () -> Unit, onAdd: () -> Unit, onRatings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val canManage = !local
    val actions = if (canManage) listOf(DesktopMenuAction("加入本机书架", onClick = onAdd)) else emptyList()
    var previousLocal by remember(manga.id) { mutableStateOf(local) }
    var addedFeedback by remember(manga.id) { mutableStateOf(false) }
    LaunchedEffect(local) {
        addedFeedback = local && !previousLocal
        previousLocal = local
        if (addedFeedback) { delay(1_000L); addedFeedback = false }
    }
    val feedbackPhase = when { addedFeedback -> 1; canManage -> 2; else -> 3 }
    val itemMenu: @Composable (Boolean) -> Unit = { showTrigger ->
        Box {
            CopyShelfTool(showTrigger && feedbackPhase != 3) {
                val control: @Composable () -> Unit = {
                    MotionSwap(feedbackPhase, Modifier.size(44.dp)) { phase ->
                        when (phase) {
                            1 -> Icon(Icons.Outlined.Check, "已加入本机书架", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                            2 -> MotionIconButton(onClick = onManage, enabled = canManage,
                                modifier = Modifier.size(44.dp), tooltip = "加入本机书架") {
                                Icon(Icons.Outlined.BookmarkAdd, "加入本机书架", Modifier.size(17.dp))
                            }
                            else -> Spacer(Modifier.size(44.dp))
                        }
                    }
                }
                if (grid) LiquidGlassSurface(backdrop, Modifier, cornerRadius = 16.dp, role = GlassSurfaceRole.NAVIGATION) { control() }
                else control()
            }
            CompactGlassPopover(managed && canManage, backdrop, maximumWidth = 192.dp, onDismiss = onDismiss) {
                CompactMenuAction(Icons.Outlined.BookmarkAdd, "加入本机书架") { onDismiss(); if (canManage) onAdd() }
            }
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    // Adapt CoverTile's source geometry/press/shared-cover path, with account-owned metadata.
    Box(modifier) { DesktopContextMenuArea(actions) {
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
            Box(Modifier.align(Alignment.TopEnd)) { itemMenu(platformKind == PlatformKind.DESKTOP) }
            Box(Modifier.align(if (platformKind == PlatformKind.DESKTOP) Alignment.TopStart else Alignment.TopEnd).padding(6.dp)) {
                CopyShelfTool(syncing || addedFeedback && platformKind != PlatformKind.DESKTOP) {
                    LiquidGlassSurface(backdrop, Modifier, cornerRadius = 12.dp, role = GlassSurfaceRole.ARTWORK) {
                        MotionSwap(syncing, Modifier.size(24.dp)) { busy ->
                            if (busy) MotionBusyIndicator(Modifier.size(12.dp), strokeWidth = 1.4.dp)
                            else Icon(Icons.Outlined.Check, "已加入本机书架", Modifier.size(14.dp), tint = colors.onSurface)
                        }
                    }
                }
            }
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
                CopyShelfTool(syncing) {
                    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                        MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.4.dp)
                    }
                }
                itemMenu(true)
            }
        }
    } }
}

@Composable
private fun CloudChapterCaption(metadata: CopyShelfMetadata?, modifier: Modifier = Modifier) {
    val caption = metadata?.takeIf { it.chapterId.isNotBlank() }?.let { it.chapterId to it.chapterTitle }
    val colors = MaterialTheme.colorScheme
    CopyShelfValue(caption) { (_, title) ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Outlined.History, if (title.isBlank()) "拷贝已保存阅读章节" else null,
                Modifier.size(12.dp), tint = colors.onSurfaceVariant)
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
