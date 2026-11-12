package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.storage.ReadingProgress

@Composable
fun LibraryScreen(
    favorites: List<Manga>, history: List<ReadingProgress>, repository: MangaRepository,
    onOpen: (Manga) -> Unit, onContinue: (ReadingProgress) -> Unit, onDiscover: () -> Unit,
) {
    var showHistory by rememberSaveable { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(102.dp), modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "title") {
            Column {
                Text("书架", style = MaterialTheme.typography.headlineLarge)
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(selected = !showHistory, onClick = { showHistory = false },
                        label = { Text("收藏 ${favorites.size}") }, shape = RoundedCornerShape(100))
                    FilterChip(selected = showHistory, onClick = { showHistory = true },
                        label = { Text("最近阅读") }, shape = RoundedCornerShape(100))
                }
            }
        }
        if (showHistory) {
            if (history.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty-history") {
                EmptyMessage("从第一页开始", "读过的章节会留在这里，下次接着看。", "去发现", onDiscover)
            }
            items(history, key = { "history-${it.manga.id}" }, span = { GridItemSpan(maxLineSpan) }) {
                ContinueRow(it, repository, onContinue)
            }
        } else {
            if (favorites.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "empty-favorites") {
                EmptyMessage("把喜欢的故事留下", "在漫画详情点一下收藏，就能放进书架。", "去发现", onDiscover)
            }
            items(favorites, key = Manga::id) { manga -> CoverTile(manga, repository) { onOpen(manga) } }
        }
    }
}
