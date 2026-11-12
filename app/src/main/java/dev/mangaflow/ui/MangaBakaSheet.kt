package dev.mangaflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.mangaflow.MangaBakaExplorer
import dev.mangaflow.MangaBakaRoute
import dev.mangaflow.MangaBakaState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaBakaBook
import dev.mangaflow.data.MangaBakaRelation
import dev.mangaflow.data.MangaBakaShelf
import dev.mangaflow.data.MangaBakaStack
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs

/** Reuses the app's existing moving sheet, Ovio tabs and compact cover/list grammar. */
@Composable
internal fun MangaBakaSheet(
    explorer: MangaBakaExplorer,
    readerRepository: MangaRepository,
    onDismiss: () -> Unit,
    onOpen: (Manga) -> Unit,
) {
    val state by explorer.state.collectAsStateWithLifecycle()
    val positions = rememberSaveableStateHolder()
    LaunchedEffect(explorer) { explorer.resume() }
    DisposableEffect(explorer) { onDispose { explorer.close() } }
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) { dismiss ->
        BackHandler(state.canGoBack) { explorer.back() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(.91f).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.canGoBack) MotionIconButton(onClick = explorer::back) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一层", Modifier.size(20.dp))
                }
                Text(when (state.route) {
                    is MangaBakaRoute.Home -> "找漫画"
                    is MangaBakaRoute.Work -> "相似与系列"
                    is MangaBakaRoute.Stack -> "主题书单"
                    is MangaBakaRoute.Match -> "确认作品"
                }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(start = if (state.canGoBack) 0.dp else 14.dp))
                MotionIconButton(onClick = explorer::refresh, enabled = !state.loading && !state.loadingMore) {
                    Icon(Icons.Outlined.Refresh, "刷新当前内容", Modifier.size(19.dp))
                }
                MotionIconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭找漫画", Modifier.size(20.dp)) }
            }
            when (val route = state.route) {
                is MangaBakaRoute.Home -> MotionTabs(route.shelf.ordinal, MangaBakaShelf.entries.map { it.label },
                    { explorer.selectShelf(MangaBakaShelf.entries[it]) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), compact = true)
                is MangaBakaRoute.Work -> MotionTabs(route.relation.ordinal, MangaBakaRelation.entries.map { it.label },
                    { explorer.selectRelation(MangaBakaRelation.entries[it]) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), compact = true)
                else -> Unit
            }
            MotionTabContent(state, Modifier.weight(1f).fillMaxWidth(), contentKey = { it.route.key },
                order = { when (val route = it.route) {
                    is MangaBakaRoute.Home -> route.shelf.ordinal
                    is MangaBakaRoute.Work -> 4 + route.relation.ordinal
                    is MangaBakaRoute.Stack -> 8
                    is MangaBakaRoute.Match -> 9
                } }, soft = true) { pane ->
                positions.SaveableStateProvider(pane.route.key) {
                    CatalogContents(pane, readerRepository, active = pane.route.key == state.route.key,
                        onBook = explorer::openBook, onStack = explorer::openStack,
                        onMore = explorer::more, onRetry = explorer::retry,
                        onFindReading = explorer::findReading, onOpen = { manga -> onOpen(manga); dismiss() })
                }
            }
            Text("资料 · MangaBaka 及原始来源", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f),
                fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 7.dp))
        }
    }
}

@Composable
private fun CatalogContents(
    state: MangaBakaState,
    readerRepository: MangaRepository,
    active: Boolean,
    onBook: (MangaBakaBook) -> Unit,
    onStack: (MangaBakaStack) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onFindReading: () -> Unit,
    onOpen: (Manga) -> Unit,
) {
    val list = rememberLazyGridState()
    val colors = MaterialTheme.colorScheme
    var revealSpoilers by rememberSaveable(state.route.key) { mutableStateOf(false) }
    val spoilerBlocked = state.stack?.spoiler == true && !revealSpoilers
    val columns = if (LocalDensity.current.fontScale >= 1.4f) 2 else 3
    LazyVerticalGrid(columns = GridCells.Fixed(columns), state = list, userScrollEnabled = active,
        modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item(key = "context", span = { GridItemSpan(maxLineSpan) }) {
            when (val route = state.route) {
                is MangaBakaRoute.Home -> Text(when (route.shelf) {
                    MangaBakaShelf.RISING -> "最近更多人加入书库的作品"
                    MangaBakaShelf.GEMS -> "评分较好、仍较少人发现的作品"
                    MangaBakaShelf.STACKS -> "读者整理的公开书单"
                }, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                is MangaBakaRoute.Match -> Text("选择「${route.manga.title}」对应的作品", color = colors.onSurfaceVariant,
                    fontSize = 13.sp, lineHeight = 19.sp)
                is MangaBakaRoute.Work -> state.book?.let { book ->
                    BookInformation(book, state, active, onFindReading)
                }
                is MangaBakaRoute.Stack -> state.stack?.let { stack ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (spoilerBlocked) {
                            Text("这份书单包含剧透", color = colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("标题、说明和作品会在确认后显示。", color = colors.onSurfaceVariant, fontSize = 12.sp)
                            TextButton(onClick = { revealSpoilers = true }, enabled = active) { Text("显示书单") }
                        } else {
                            Text(stack.title, color = colors.onSurface, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
                            Text(listOf(stack.owner, "${stack.count} 项").filter(String::isNotBlank).joinToString(" · "),
                                color = colors.onSurfaceVariant, fontSize = 12.sp)
                            if (stack.description.isNotBlank()) ExpandableCatalogText(stack.description, state.route.key)
                        }
                    }
                }
            }
        }
        if (!spoilerBlocked) {
            items(state.readingCandidates, key = { "reader:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { manga ->
                ReadingVersionRow(manga, readerRepository, active) { onOpen(manga) }
            }
            items(state.books, key = { "book:${it.id}:${it.relation}" }) { book ->
                CatalogBookTile(book, active) { onBook(book) }
            }
            items(state.stacks, key = { "stack:${it.id}" }, span = { GridItemSpan(maxLineSpan) }) { stack ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = active, role = Role.Button) { onStack(stack) }.padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.MenuBook, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (stack.spoiler) "含剧透的书单" else stack.title, fontSize = 14.sp, lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(stack.owner, "${stack.count} 项").filter(String::isNotBlank).joinToString(" · "),
                            color = colors.onSurfaceVariant, fontSize = 11.sp)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    state.loading || state.loadingMore -> CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 1.6.dp)
                    state.error != null -> {
                        Text(state.error, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                        TextButton(onClick = onRetry, enabled = active) { Text("重新读取") }
                    }
                    !spoilerBlocked && state.books.isEmpty() && state.stacks.isEmpty() ->
                        Text(if (state.route is MangaBakaRoute.Work) "暂无这类相关作品" else "暂时没有可显示的漫画",
                            color = colors.onSurfaceVariant, fontSize = 12.sp)
                }
                if (!spoilerBlocked && state.nextPage != null && !state.loading && !state.loadingMore && state.error == null) {
                    TextButton(onClick = onMore, enabled = active) { Text("加载更多") }
                }
            }
        }
    }
}

@Composable
private fun BookInformation(
    book: MangaBakaBook, state: MangaBakaState, active: Boolean,
    onFindReading: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CatalogCover(book, Modifier.width(62.dp).height(86.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(book.title, color = colors.onSurface, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                if (book.authors.isNotEmpty()) Text(book.authors.joinToString(" / "), color = colors.onSurfaceVariant,
                    fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(book.format, book.status).filter(String::isNotBlank).joinToString(" · "), color = colors.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        if (book.description.isNotBlank()) ExpandableCatalogText(book.description, book.id)
        if (book.origins.isNotEmpty()) Text("资料关联 · " + book.origins.joinToString(" / "),
            color = colors.onSurfaceVariant.copy(alpha = .7f), fontSize = 10.sp, lineHeight = 14.sp)
        if ((state.route as? MangaBakaRoute.Work)?.relation == MangaBakaRelation.AUTHOR) {
            book.authors.firstOrNull()?.let { Text("${it} 的其他作品", color = colors.onSurfaceVariant, fontSize = 12.sp) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onFindReading, enabled = active && !state.readingLoading) {
                Text(if (state.readingLoading) "正在查找…" else if (state.searchedReading) "重新查找阅读版本" else "在拷贝中查找")
            }
            if (state.readingLoading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        }
        if (state.readingError != null) Text(state.readingError, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        if (state.searchedReading && !state.readingLoading && state.readingError == null && state.readingCandidates.isEmpty()) {
            Text("暂未找到阅读版本，仍可浏览相关作品。", color = colors.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReadingVersionRow(manga: Manga, repository: MangaRepository, enabled: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看${manga.title}的章节", onClick = onOpen)
        .padding(vertical = 7.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MangaCover(manga, repository, Modifier.width(30.dp).height(42.dp).clip(RoundedCornerShape(5.dp)))
        Column(Modifier.weight(1f)) {
            Text(manga.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (manga.authors.isNotEmpty()) Text(manga.authors.joinToString(" / "), fontSize = 11.sp,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("查看章节", fontSize = 11.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun CatalogBookTile(book: MangaBakaBook, enabled: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onOpen),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        CatalogCover(book, Modifier.fillMaxWidth().aspectRatio(.7f).clip(RoundedCornerShape(9.dp)))
        if (book.relation.isNotBlank()) Text(book.relation, color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 1)
        Text(book.title, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
        val caption = book.authors.firstOrNull().orEmpty().ifBlank { book.format }
        Text(caption, color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (book.note.isNotBlank()) Text(book.note, fontSize = 11.sp, lineHeight = 15.sp,
            color = colors.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CatalogCover(book: MangaBakaBook, modifier: Modifier) {
    var failed by remember(book.coverUrl) { mutableStateOf(false) }
    Box(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)), contentAlignment = Alignment.Center) {
        if (book.coverUrl.isNotBlank() && !failed) AsyncImage(book.coverUrl, book.title,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true })
        else Icon(Icons.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f), modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun ExpandableCatalogText(text: String, key: String) {
    var expanded by rememberSaveable(key, text) { mutableStateOf(false) }
    Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button,
            onClickLabel = if (expanded) "收起说明" else "展开说明") { expanded = !expanded })
}
