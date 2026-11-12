package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.MotionTabContent

@Composable
fun LibraryScreen(
    favorites: List<Manga>, history: List<ReadingProgress>, repository: MangaRepository,
    onOpen: (Manga) -> Unit, onContinue: (ReadingProgress) -> Unit, onDiscover: () -> Unit,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    val favoritesPosition = rememberLazyGridState()
    val historyPosition = rememberLazyGridState()
    val coverWidth = 100.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        BrowseTabs(if (showHistory) 1 else 0, listOf("收藏 ${favorites.size}", "最近阅读"),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { showHistory = it == 1 }
        MotionTabContent(if (showHistory) 1 else 0, Modifier.weight(1f).fillMaxWidth()) { tab ->
            val historyTab = tab == 1
            LazyVerticalGrid(
                columns = if (historyTab) GridCells.Fixed(1) else GridCells.Adaptive(coverWidth),
                state = if (historyTab) historyPosition else favoritesPosition,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(if (historyTab) 4.dp else 14.dp),
            ) {
                if (historyTab) {
                    if (history.isEmpty()) item(key = "empty-history") {
                        EmptyMessage("从第一页开始", "读过的章节会留在这里，下次接着看。", "去发现", onDiscover)
                    }
                    items(history, key = { "history-${it.manga.id}" }) {
                        ContinueRow(it, repository, onContinue)
                    }
                } else {
                    if (favorites.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty-favorites") {
                        EmptyMessage("把喜欢的故事留下", "在漫画详情点一下收藏，就能放进书架。", "去发现", onDiscover)
                    }
                    items(favorites, key = Manga::id) { manga -> CoverTile(manga, repository, dense = true) { onOpen(manga) } }
                }
            }
        }
    }
}
