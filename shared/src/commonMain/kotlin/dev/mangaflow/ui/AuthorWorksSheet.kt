package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.AuthorWorksState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton

@Composable
internal fun AuthorWorksSheet(
    state: AuthorWorksState,
    repository: MangaRepository,
    onDismiss: () -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Manga) -> Unit,
) {
    val grid = key(state.manga.id, state.name) { rememberLazyGridState() }
    val nearEnd by remember(grid) {
        derivedStateOf {
            val layout = grid.layoutInfo
            layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, state.items.size, state.hasMore, state.loading, state.loadingMore, state.error) {
        if (nearEnd && state.hasMore && !state.loading && !state.loadingMore && state.error == null) onMore()
    }
    val colors = MaterialTheme.colorScheme
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background, desktopWidth = 980.dp) { dismiss ->
        val desktop = platformKind == PlatformKind.DESKTOP
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.name, style = browseTitleStyle(23.sp, 30.sp), modifier = Modifier.semantics { heading() })
                    if (state.items.isNotEmpty() || desktop && state.loading) Text(
                        mangaNumberText(if (state.items.isEmpty()) "正在查找作者作品…" else "已载入 ${state.items.size} 部作品"),
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭作者作品", Modifier.size(20.dp)) }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(columns = GridCells.Adaptive((if (desktop) 144.dp else 88.dp) * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                    state = grid, modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (desktop && state.loading && state.items.isEmpty()) items(6,
                        key = { "author-placeholder-$it" }, contentType = { "cover-placeholder" }) {
                        DiscoveryCoverPlaceholder(narrow = false, showMetrics = false, showSource = true, showChapter = false)
                    }
                    items(state.items, key = Manga::id) { manga -> CoverTile(manga, repository, dense = !desktop) { onOpen(manga) } }
                    item(key = "author-footer", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            when {
                                desktop && state.loading && state.items.isEmpty() -> Unit
                                state.loading || state.loadingMore -> CircularProgressIndicator(Modifier.padding(20.dp).size(20.dp), strokeWidth = 1.5.dp)
                                desktop && state.error != null -> CatalogueState(
                                    if (state.items.isEmpty()) "作者作品暂未载入" else "后续作品暂未载入",
                                    state.error, selectableDetail = true) {
                                    TextButton(onClick = onRetry) { Text(if (state.items.isEmpty()) "重新查找" else "重试这一页") }
                                }
                                desktop && state.items.isEmpty() -> CatalogueState("暂时没有这位作者的作品",
                                    if (state.hasMore) "当前页没有作品，可以继续加载。" else "当前来源没有返回其他作品。") {
                                    TextButton(onClick = dismiss) { Text("返回作品") }
                                }
                                state.error != null -> {
                                    Text(state.error, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp,
                                        modifier = Modifier.padding(vertical = 12.dp))
                                    TextButton(onClick = onRetry) { Text("重试") }
                                }
                                state.items.isEmpty() -> Text("暂无这位作者的漫画", color = colors.onSurfaceVariant,
                                    fontSize = 13.sp, modifier = Modifier.padding(vertical = 28.dp))
                            }
                            if (state.hasMore && !state.loading && !state.loadingMore && state.error == null) {
                                TextButton(onClick = onMore) { Text("加载更多") }
                            }
                        }
                    }
                }
                if (desktop) DesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

