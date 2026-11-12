package dev.mangaflow.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaReview
import dev.mangaflow.ui.CommunityComment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Composed only at the chapter end. The discussion never counts as an image or changes page totals. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterDiscussion(
    manga: Manga,
    chapter: Chapter,
    repository: CopyCommunityRepository,
    background: Color,
    foreground: Color,
    onNext: (() -> Unit)?,
) {
    var comments by remember(chapter.id) { mutableStateOf<List<MangaReview>>(emptyList()) }
    var total by remember(chapter.id) { mutableStateOf<Int?>(null) }
    var nextPage by remember(chapter.id) { mutableStateOf<Int?>(0) }
    var loading by remember(chapter.id) { mutableStateOf(true) }
    var error by remember(chapter.id) { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(chapter.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val muted = foreground.copy(alpha = .54f)

    suspend fun load(page: Int) {
        loading = true
        error = null
        try {
            val result = repository.chapterComments(manga, chapter, page)
            comments = (if (page == 0) result.items else comments + result.items).distinctBy(MangaReview::id)
            total = result.total
            nextPage = result.nextPage
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "本章讨论暂时无法读取" }
        finally { loading = false }
    }

    LaunchedEffect(manga.id, chapter.id) { load(0) }
    val loadMore = {
        if (!loading) nextPage?.let { page -> scope.launch { load(page) } }
        Unit
    }

    Column(Modifier.fillMaxWidth().background(background).padding(horizontal = 24.dp)
        .padding(top = 34.dp, bottom = 100.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本章读完了", color = muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (onNext != null) TextButton(onClick = onNext) {
                Text("继续下一章 →", color = foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("本章讨论", color = foreground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            total?.let { Text("  $it", color = muted, fontSize = 12.sp) }
            Spacer(Modifier.weight(1f))
            Text("拷贝", color = muted, fontSize = 11.sp)
        }
        Text(chapter.title, color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        if (loading && comments.isEmpty()) {
            CircularProgressIndicator(Modifier.padding(vertical = 18.dp).size(18.dp), color = muted, strokeWidth = 1.5.dp)
        }
        comments.take(3).forEach { comment ->
            CommunityComment(comment, foreground = foreground, muted = muted,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
        }
        if (!loading && error == null && comments.isEmpty()) {
            Text("这一章还没有讨论", color = muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
        }
        error?.let { message ->
            Text(message, color = muted, fontSize = 12.sp)
            TextButton(onClick = loadMore, enabled = !loading) { Text("重试", color = foreground) }
        }
        if (comments.isNotEmpty()) TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(total?.let { "查看全部 $it 条讨论" } ?: "查看全部讨论", color = foreground, fontSize = 13.sp)
        }
    }

    if (expanded) {
        ModalBottomSheet(onDismissRequest = { expanded = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = background, contentColor = foreground) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.90f).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("本章讨论", color = foreground, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("${chapter.title} · 拷贝", color = muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    TextButton(onClick = { expanded = false }) { Text("完成", color = foreground, fontSize = 13.sp) }
                }
                LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(comments, key = MangaReview::id) { comment ->
                        CommunityComment(comment, foreground = foreground, muted = muted)
                        HorizontalDivider(Modifier.padding(top = 14.dp), color = foreground.copy(alpha = .055f))
                    }
                    item("discussion-more") {
                        when {
                            loading -> CircularProgressIndicator(Modifier.size(20.dp), color = muted, strokeWidth = 1.5.dp)
                            error != null -> Column {
                                Text(error.orEmpty(), color = muted, fontSize = 12.sp)
                                TextButton(onClick = loadMore) { Text("重试", color = foreground) }
                            }
                            nextPage != null -> TextButton(onClick = loadMore, modifier = Modifier.fillMaxWidth()) {
                                Text("继续加载", color = foreground)
                            }
                            else -> Text("已读完全部讨论", color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
