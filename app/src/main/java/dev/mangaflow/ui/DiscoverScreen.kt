package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    state: BrowseState,
    history: List<ReadingProgress>,
    repository: MangaRepository,
    onQuery: (String) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onOpen: (Manga) -> Unit,
    onContinue: (ReadingProgress) -> Unit,
    onMode: (DiscoveryMode) -> Unit,
    onPeriod: (RankPeriod) -> Unit,
    onAudience: (RankAudience) -> Unit,
) {
    val grid = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current
    val selectionKey = "${state.query}|${state.mode}|${state.period}|${state.audience}"
    var previousQuery by rememberSaveable { mutableStateOf(selectionKey) }
    LaunchedEffect(selectionKey) {
        if (previousQuery != selectionKey) {
            grid.scrollToItem(0)
            previousQuery = selectionKey
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(102.dp), state = grid,
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "heading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("发现", style = MaterialTheme.typography.headlineLarge)
                    Text("下一段故事，从这里开始", modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Icons.Outlined.Refresh, "刷新漫画", Modifier.size(21.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "search") {
            OutlinedTextField(
                value = state.query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text("搜索漫画、作者", style = MaterialTheme.typography.bodyMedium) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(21.dp)) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = { onQuery(""); keyboard?.hide() }) {
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.query.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "discovery-tabs") {
                Column {
                    // Continuous underline follows the selected peer, keeping navigation separate from filters.
                    TabRow(selectedTabIndex = state.mode.ordinal, containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.onSurface, divider = {},
                        indicator = { positions ->
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(positions[state.mode.ordinal]).padding(horizontal = 24.dp),
                                height = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                        }) {
                        DiscoveryMode.entries.forEach { mode ->
                            Tab(selected = state.mode == mode, onClick = { onMode(mode) },
                                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = { Text(mode.label, fontSize = 15.sp,
                                    fontWeight = if (state.mode == mode) FontWeight.SemiBold else FontWeight.Normal) })
                        }
                    }
                    if (state.mode == DiscoveryMode.RANKING) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            RankPeriod.entries.forEach { period ->
                                DiscoveryFilter(period.label, state.period == period) { onPeriod(period) }
                            }
                            Spacer(Modifier.width(6.dp))
                            RankAudience.entries.forEach { audience ->
                                DiscoveryFilter(audience.label, state.audience == audience) { onAudience(audience) }
                            }
                        }
                    }
                }
            }
        }
        if (state.query.isBlank() && state.mode == DiscoveryMode.RECOMMENDED && history.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                ContinueRow(history.first(), repository, onContinue)
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
                    if (state.query.isBlank()) "来源暂时没有返回内容，可以稍后刷新。" else "试试书名的一部分，或换个别名。")
            }
        } else {
            if (state.query.isBlank() && state.mode == DiscoveryMode.RECOMMENDED) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                    FeaturedManga(state.items.first(), repository, onOpen)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "section") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when {
                        state.query.isNotBlank() -> "搜索结果"
                        state.mode == DiscoveryMode.RANKING -> "${state.period.label} · ${state.audience.label}"
                        state.mode == DiscoveryMode.LATEST -> "最近更新"
                        else -> "发现好故事"
                    }, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.weight(1f))
                    if (state.loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 1.5.dp)
                    else Text(if (state.mode == DiscoveryMode.RANKING && state.query.isBlank()) when (state.period) {
                        RankPeriod.DAY -> "上升最快"
                        RankPeriod.WEEK -> "最近 7 天"
                        RankPeriod.MONTH -> "最近 30 天"
                        RankPeriod.TOTAL -> "拷贝热度"
                    } else "拷贝漫画", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.query.isBlank() && state.mode == DiscoveryMode.RANKING) {
                items(state.items, key = Manga::id, span = { GridItemSpan(maxLineSpan) }) { manga ->
                    RankRow(manga, repository) { onOpen(manga) }
                }
            } else {
                val visible = if (state.query.isBlank() && state.mode == DiscoveryMode.RECOMMENDED) state.items.drop(1) else state.items
                items(visible, key = Manga::id) { manga -> CoverTile(manga, repository) { onOpen(manga) } }
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
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (state.loadingMore) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else TextButton(onClick = onMore) { Text("再看一些"); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeaturedManga(manga: Manga, repository: MangaRepository, onOpen: (Manga) -> Unit) {
    Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(22.dp))
        .background(Color(0xFF242426)).clickable { onOpen(manga) }) {
        MangaCover(manga, repository, Modifier.fillMaxSize(), ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.04f), 0.4f to Color.Transparent,
            0.75f to Color.Black.copy(alpha = 0.5f), 1f to Color.Black.copy(alpha = 0.84f))))
        Text("拷贝推荐", color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp)
                .clip(RoundedCornerShape(100)).background(Color.Black.copy(alpha = 0.23f)).padding(horizontal = 12.dp, vertical = 7.dp))
        Column(Modifier.align(Alignment.BottomStart).padding(24.dp)) {
            Text(manga.title, color = Color.White, style = MaterialTheme.typography.headlineMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(manga.authors.take(2).joinToString(" · ").ifBlank { "翻开，进入故事" },
                    color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "查看漫画", tint = Color.White,
                    modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.17f)).padding(12.dp).size(20.dp))
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
private fun RankRow(manga: Manga, repository: MangaRepository, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(manga.rank?.toString()?.padStart(2, '0') ?: "—", fontSize = 20.sp,
            fontWeight = if ((manga.rank ?: 99) <= 3) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if ((manga.rank ?: 99) <= 3) .92f else .4f),
            modifier = Modifier.width(38.dp))
        MangaCover(manga, repository, Modifier.width(65.dp).height(91.dp).clip(RoundedCornerShape(9.dp)))
        Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(manga.authors.take(2).joinToString(" / "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            manga.popularity?.let { value ->
                val label = when {
                    value >= 100_000_000 -> String.format(java.util.Locale.ROOT, "%.1f亿", value / 100_000_000.0)
                    value >= 10_000 -> String.format(java.util.Locale.ROOT, "%.1f万", value / 10_000.0)
                    else -> value.toString()
                }
                Text("热度 $label", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(start = 8.dp).size(17.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
    }
}

@Composable
internal fun ContinueRow(progress: ReadingProgress, repository: MangaRepository, onContinue: (ReadingProgress) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).clickable { onContinue(progress) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MangaCover(progress.manga, repository, Modifier.width(45.dp).height(62.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("继续阅读", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(progress.manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${progress.chapterTitle} · ${progress.pageIndex + 1}/${progress.pageCount} 页", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.PlayArrow, "继续阅读", Modifier.size(23.dp))
    }
}

@Composable
internal fun CoverTile(manga: Manga, repository: MangaRepository, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, spring(dampingRatio = 0.8f, stiffness = 550f), label = "cover-press")
    Column(Modifier.scale(scale).clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)) {
        MangaCover(manga, repository, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(12.dp)))
        Text(manga.title, modifier = Modifier.padding(top = 9.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val secondary = manga.latestChapter.ifBlank { manga.authors.firstOrNull().orEmpty() }
        if (secondary.isNotBlank()) Text(secondary, modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MangaCover(manga: Manga, repository: MangaRepository, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val context = LocalContext.current
    val request = remember(manga.id, manga.coverUrl) {
        ImageRequest.Builder(context).data(manga.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().apply {
                repository.imageHeaders(manga.id, "").forEach { (name, value) -> set(name, value) }
            }.build()).crossfade(180).build()
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f), modifier = Modifier.size(25.dp))
        AsyncImage(model = request, contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun QuietLoading(message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 64.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 1.5.dp)
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
