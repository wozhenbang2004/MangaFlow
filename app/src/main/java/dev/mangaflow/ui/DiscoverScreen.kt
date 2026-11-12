package dev.mangaflow.ui

import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.mangaflow.BrowseState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.data.RankPeriod
import dev.mangaflow.data.RankAudience
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: BrowseState,
    searchExpanded: Boolean,
    onCloseSearch: () -> Unit,
    canAutoAdvance: Boolean,
    repository: MangaRepository,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpen: (Manga) -> Unit,
    onPeriod: (RankPeriod) -> Unit,
    onAudience: (RankAudience) -> Unit,
    active: Boolean = true,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val searchFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val motion = LocalMotionEnabled.current
    val refreshing = rememberDelayedBusy(state.refreshing)
    BackHandler(active && searchExpanded) { keyboard?.hide(); onCloseSearch() }
    val positions = rememberSaveable(saver = BrowsePositionsSaver) { LinkedHashMap<String, LazyGridState>() }
    val retainedGrid = remember(state.cacheKey) {
        val saved = positions.remove(state.cacheKey) ?: LazyGridState()
        positions[state.cacheKey] = saved
        while (positions.size > 24) positions.remove(positions.keys.first())
        saved
    }
    val loadingGrid = remember(state.cacheKey) { LazyGridState() }
    // Let the disk snapshot hydrate before the short loading layout can clamp a saved position.
    val grid = if (state.loading && state.items.isEmpty()) loadingGrid else retainedGrid
    val heroVisible by remember(grid) {
        derivedStateOf { grid.layoutInfo.visibleItemsInfo.any { it.key == "hero" } }
    }
    val ranking = state.query.isBlank() && state.mode == DiscoveryMode.RANKING
    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        AnimatedVisibility(searchExpanded,
            enter = expandVertically(tween(if (motion) MangaMotion.Enter else 0, easing = MangaMotion.EnterEasing)) + fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
            exit = shrinkVertically(tween(if (motion) MangaMotion.Exit else 0, easing = MangaMotion.EnterEasing)) + fadeOut(tween(if (motion) 90 else 0)),
        ) {
            LaunchedEffect(searchExpanded, active) {
                if (searchExpanded && active) { searchFocus.requestFocus(); keyboard?.show() }
                else focus.clearFocus(force = true)
            }
            OutlinedTextField(
                value = state.query, onValueChange = { if (searchExpanded && active) onQuery(it) }, singleLine = true,
                readOnly = !searchExpanded || !active,
                placeholder = { Text("搜索漫画、作者", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(21.dp)) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) MotionIconButton(onClick = { onQuery(""); keyboard?.hide() }) {
                        Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(19.dp))
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onRefresh() }),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    unfocusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                    .motionInputGate(searchExpanded && active).focusRequester(searchFocus),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp)) {
            if (refreshing) LinearProgressIndicator(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .35f),
                trackColor = Color.Transparent,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3), state = grid,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(if (ranking) 8.dp else 16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
        if (ranking) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "rank-filters") {
                var audienceMenuOpen by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        RankPeriod.entries.forEach { period ->
                            DiscoveryFilter(period.label, state.period == period) { onPeriod(period) }
                        }
                    }
                    Box {
                        TextButton(onClick = { audienceMenuOpen = true }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                            Text(state.audience.label, fontSize = 13.sp)
                            Icon(Icons.Outlined.ArrowDropDown, "选择榜单频道", Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = audienceMenuOpen, onDismissRequest = { audienceMenuOpen = false }) {
                            RankAudience.entries.forEach { audience ->
                                DropdownMenuItem(text = { Text(audience.label,
                                    fontWeight = if (state.audience == audience) FontWeight.SemiBold else FontWeight.Normal) },
                                    onClick = { audienceMenuOpen = false; onAudience(audience) })
                            }
                        }
                    }
                    DiscoveryRefresh(state, onRefresh)
                }
            }
        }
        if (state.loading && state.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "loading") { QuietLoading("正在翻开书页…") }
        } else if (state.items.isEmpty() && state.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                EmptyMessage("暂时没能加载", state.error, "重试", onRetry)
            }
        } else if (state.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                EmptyMessage(if (state.query.isBlank()) "暂时没有漫画" else "没有找到这本漫画",
                    if (state.query.isBlank()) "来源暂时没有返回内容，可以稍后刷新。" else "试试书名的一部分，或换个别名。",
                    action = if (!ranking && state.query.isBlank()) "刷新" else null, onAction = onRefresh)
            }
        } else {
            if (state.query.isBlank() && state.mode == DiscoveryMode.RECOMMENDED) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                    FeaturedCarousel(state.items.take(6), repository, heroVisible && canAutoAdvance && !grid.isScrollInProgress, onOpen)
                }
            }
            if (!ranking) item(span = { GridItemSpan(maxLineSpan) }, key = "section") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        state.query.isNotBlank() -> "搜索结果"
                        state.mode == DiscoveryMode.LATEST -> "最近更新"
                        else -> "发现好故事"
                    }, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Text("拷贝漫画", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    DiscoveryRefresh(state, onRefresh)
                }
            }
            if (ranking) {
                items(state.items, key = Manga::id, span = { GridItemSpan(maxLineSpan) }) { manga ->
                    RankRow(manga, repository, state.period) { onOpen(manga) }
                }
            } else {
                items(state.items, key = Manga::id) { manga -> CoverTile(manga, repository, dense = true) { onOpen(manga) } }
            }
            if (state.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "more-error") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }
            } else if (state.hasMore) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "more") {
                    val showMoreBusy = rememberDelayedBusy(state.loadingMore)
                    Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                        if (state.loadingMore) {
                            if (showMoreBusy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                        else TextButton(onClick = onMore) { Text("再看一些"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun DiscoveryRefresh(state: BrowseState, onRefresh: () -> Unit) {
    MotionIconButton(onClick = onRefresh, enabled = !state.loading && !state.refreshing) {
        Icon(Icons.Outlined.Refresh,
            if (state.updatedAt > 0) "刷新漫画，${browseUpdatedAt(state.updatedAt)}" else "刷新漫画",
            Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FeaturedCarousel(manga: List<Manga>, repository: MangaRepository, visible: Boolean, onOpen: (Manga) -> Unit) {
    val pager = rememberPagerState(pageCount = { manga.size })
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    val motion = LocalMotionEnabled.current
    val autoAdvance = motion && manga.size > 1 && visible && !dragged && !pressed &&
        accessibility?.isTouchExplorationEnabled != true
    LaunchedEffect(autoAdvance, pager, manga.map { it.id }, lifecycle) {
        if (autoAdvance) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(6_000)
                if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % manga.size,
                    animationSpec = tween(420, easing = MangaMotion.EnterEasing))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalPager(state = pager, key = { manga[it].id },
            contentPadding = PaddingValues(end = if (manga.size > 1) 26.dp else 0.dp),
            pageSpacing = 10.dp, beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = "推荐 ${pager.currentPage + 1} / ${manga.size}" },
        ) { page -> FeaturedManga(manga[page], repository, interaction, onOpen) }
        if (manga.size > 1) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val color = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(width = (manga.size * 18).dp, height = 4.dp)) {
                val position = if (motion) pager.currentPage + pager.currentPageOffsetFraction else pager.currentPage.toFloat()
                repeat(manga.size) { index ->
                    val emphasis = (1f - kotlin.math.abs(position - index)).coerceIn(0f, 1f)
                    val width = (4f + 10f * emphasis).dp.toPx()
                    drawRoundRect(color.copy(alpha = .15f + .5f * emphasis),
                        topLeft = Offset((index * 18 + 9).dp.toPx() - width / 2f, 0f),
                        size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2f))
                }
            }
        }
    }
}

@Composable
private fun FeaturedManga(manga: Manga, repository: MangaRepository, interaction: MutableInteractionSource, onOpen: (Manga) -> Unit) {
    Box(Modifier.fillMaxWidth().height(204.dp).clip(RoundedCornerShape(22.dp))
        .background(Color(0xFF242426)).clickable(interactionSource = interaction, indication = null) { onOpen(manga) }) {
        MangaCover(manga, repository, Modifier.fillMaxSize(), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.04f), 0.4f to Color.Transparent,
            0.75f to Color.Black.copy(alpha = 0.5f), 1f to Color.Black.copy(alpha = 0.84f))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
            Text(manga.title, color = Color.White, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(manga, Modifier.fillMaxWidth().padding(top = 9.dp), foreground = Color.White)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(manga.authors.take(2).joinToString(" · ").ifBlank { "翻开，进入故事" },
                    color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "查看漫画", tint = Color.White,
                    modifier = Modifier.padding(start = 12.dp).size(20.dp))
            }
        }
    }
}

@Composable
private fun DiscoveryFilter(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(10.dp), border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLabelColor = MaterialTheme.colorScheme.onSurface))
}

@Composable
private fun RankRow(manga: Manga, repository: MangaRepository, period: RankPeriod, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction, pressedScale = .985f)
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(manga.rank?.toString()?.padStart(2, '0') ?: "—", fontSize = 17.sp,
            fontWeight = if ((manga.rank ?: 99) <= 3) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if ((manga.rank ?: 99) <= 3) .92f else .4f),
            maxLines = 1, modifier = Modifier.widthIn(min = 30.dp))
        MangaCover(manga, repository, Modifier.width(54.dp).height(76.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (manga.authors.isNotEmpty()) Text(manga.authors.take(2).joinToString(" / "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(manga, heatLabel = when (period) {
                RankPeriod.DAY -> "日热"
                RankPeriod.WEEK -> "周热"
                RankPeriod.MONTH -> "月热"
                RankPeriod.TOTAL -> "总热"
            })
        }
    }
}

@Composable
internal fun ContinueRow(progress: ReadingProgress, repository: MangaRepository, onContinue: (ReadingProgress) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .clickable(interactionSource = interaction, indication = null, onClickLabel = "继续阅读") { onContinue(progress) }
        .motionPress(interaction, pressedScale = .985f).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MangaCover(progress.manga, repository, Modifier.width(46.dp).height(64.dp).clip(RoundedCornerShape(7.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(progress.manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${progress.chapterTitle} · ${progress.pageIndex + 1}/${progress.pageCount} 页", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(progress.manga)
        }
        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecentReadingSheet(
    history: List<ReadingProgress>,
    repository: MangaRepository,
    onDismiss: () -> Unit,
    onContinue: (ReadingProgress) -> Unit,
) {
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) { dismiss ->
        Text("最近阅读", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, bottom = 16.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(history, key = { it.manga.id }) { progress ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable { onContinue(progress); dismiss() }.padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MangaCover(progress.manga, repository, Modifier.width(36.dp).height(50.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(progress.manga.title, style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${progress.chapterTitle} · ${progress.pageIndex + 1}/${progress.pageCount} 页",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.PlayArrow, "继续阅读", Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun CoverTile(manga: Manga, repository: MangaRepository, dense: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .motionPress(interaction)) {
        MangaCover(manga, repository, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
        Text(manga.title, modifier = Modifier.padding(top = 9.dp, start = 2.dp, end = 2.dp),
            style = if (dense) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        MangaMetricBadges(manga, Modifier.fillMaxWidth().padding(top = 6.dp, start = 2.dp, end = 2.dp), compact = true)
        val secondary = manga.latestChapter.ifBlank { if (dense) "" else manga.authors.firstOrNull().orEmpty() }
        if (secondary.isNotBlank()) Text(secondary, modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MangaCover(manga: Manga, repository: MangaRepository, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val request = remember(manga.id, manga.coverUrl, motion) {
        ImageRequest.Builder(context).data(manga.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().apply {
                repository.imageHeaders(manga.id, "").forEach { (name, value) -> set(name, value) }
            }.build()).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), modifier = Modifier.size(25.dp))
        AsyncImage(model = request, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun QuietLoading(message: String) {
    val visible = rememberDelayedBusy(true)
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(25.dp)) {
            if (visible) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 1.5.dp)
        }
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun EmptyMessage(title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.MenuBook, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

private fun browseUpdatedAt(timestamp: Long): String =
    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(timestamp)) + " 更新"

private val BrowsePositionsSaver = listSaver<LinkedHashMap<String, LazyGridState>, Any>(
    save = { positions -> positions.entries.flatMap { (key, state) ->
        listOf(key, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
    } },
    restore = { saved -> LinkedHashMap<String, LazyGridState>().apply {
        saved.chunked(3).forEach { entry ->
            if (entry.size == 3) put(entry[0] as String, LazyGridState(entry[1] as Int, entry[2] as Int))
        }
    } },
)

