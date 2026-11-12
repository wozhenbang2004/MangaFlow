package dev.mangaflow.reader

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.ui.CommunityDiscussionComment
import dev.mangaflow.ui.CommentSourceBadge
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlinx.coroutines.launch

@Composable
internal fun rememberChapterDiscussionState(
    manga: Manga,
    chapter: Chapter,
    repository: CopyCommunityRepository,
    reviews: ReviewRepository,
): ChapterDiscussionState {
    val scope = rememberCoroutineScope()
    val state = remember(manga.id, manga.title, manga.sourceUuid, chapter.id, chapter.title, chapter.group, repository, reviews) {
        ChapterDiscussionState(manga, chapter, repository, reviews, scope)
    }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

/** The sheet uses the same exact-chapter rows as the floating preview, without entering the page list. */
@Composable
internal fun ChapterDiscussion(
    chapter: Chapter,
    background: Color,
    foreground: Color,
    onClose: () -> Unit,
    state: ChapterDiscussionState,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.ensureLoaded() }
    val mixed = remember(state.sources, chapter.id) { mixedDiscussionRows(state.sources, chapter.id) }
    val chapterCount = state.totalCount
    val muted = foreground.copy(alpha = .58f)
    val busy = state.refreshing || state.paging
    val delayedBusy = rememberDelayedBusy(busy)
    val notices = state.sources.filter { it.loaded && !it.busy &&
        (it.source.status != ReviewStatus.READY || it.source.reviewsError != null) }
    val listState = rememberLazyListState()
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(state, nearEnd, state.sources, busy) {
        if (nearEnd && !busy) state.loadNext()
    }

    MotionBottomSheet(onDismissRequest = onClose,
        containerColor = background, contentColor = foreground) { dismiss ->
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                DiscussionHeading(chapterCount, mixed.size, busy, foreground,
                    modifier = Modifier.weight(1f), onRefresh = {
                        state.refresh(forceRefresh = true)
                        scope.launch { listState.scrollToItem(0) }
                    })
                MotionIconButton(onClick = dismiss) {
                    Icon(Icons.Outlined.Close, "关闭评论", tint = muted, modifier = Modifier.size(20.dp))
                }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(mixed, key = ReaderDiscussionRow::key) { row ->
                    CommunityDiscussionComment(row.source.source, row.review, state::replies,
                        foreground = foreground, muted = muted)
                    HorizontalDivider(color = foreground.copy(alpha = .06f), thickness = .5.dp)
                }
                items(notices, key = { "notice:${it.key}" }) { source ->
                    DiscussionNotice(source, foreground, busy) { state.retry(source.key) }
                }
                item("discussion-footer") {
                    when {
                        delayedBusy -> DiscussionLoading(muted)
                        busy -> Spacer(Modifier.height(40.dp))
                        state.hasMore -> TextButton(onClick = state::loadNext, modifier = Modifier.fillMaxWidth()) {
                            Text("加载更多", color = foreground, fontSize = 12.sp)
                        }
                        mixed.isEmpty() && notices.isEmpty() -> Text("还没有评论", color = muted,
                            fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
                        mixed.isNotEmpty() -> Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscussionHeading(
    chapterCount: Int?,
    loadedCount: Int,
    busy: Boolean,
    foreground: Color,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
) {
    val muted = foreground.copy(alpha = .58f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("评论", color = foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            val count = chapterCount?.toString() ?: "已加载 $loadedCount".takeIf { loadedCount > 0 }.orEmpty()
            if (count.isNotEmpty()) Text(count, color = muted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        MotionIconButton(onClick = onRefresh, enabled = !busy) {
            Icon(Icons.Outlined.Refresh, "刷新最新评论", tint = muted.copy(alpha = if (busy) .28f else .58f),
                modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DiscussionNotice(
    state: ReaderDiscussionSource,
    foreground: Color,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    val source = state.source
    val message = source.reviewsError ?: source.message.ifBlank { "暂时无法读取评论" }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CommentSourceBadge(source.id)
        Text(message,
            color = foreground.copy(alpha = .58f), fontSize = 11.sp, lineHeight = 17.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        MotionIconButton(onClick = onRetry, enabled = !busy) {
            Icon(Icons.Outlined.Refresh, "重新读取${source.name}评论", tint = foreground.copy(alpha = .58f),
                modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun DiscussionLoading(color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(17.dp), color = color, strokeWidth = 1.5.dp)
    }
}
