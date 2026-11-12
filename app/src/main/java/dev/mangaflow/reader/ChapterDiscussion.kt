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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.ui.CommunityDiscussionComment
import dev.mangaflow.ui.CommentMatchChoice
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlinx.coroutines.launch

@Composable
internal fun rememberChapterDiscussionState(
    manga: Manga,
    chapter: Chapter,
    repository: CopyCommunityRepository,
    reviewRepository: ReviewRepository,
): ChapterDiscussionState {
    val scope = rememberCoroutineScope()
    val state = remember(manga.id, manga.title, manga.sourceUuid, chapter.id, repository, reviewRepository) {
        ChapterDiscussionState(manga, chapter, repository, reviewRepository, scope)
    }
    DisposableEffect(state) { onDispose { state.dispose() } }
    return state
}

/** Local images and online discussion share this same chapter end; comments never count as pages. */
@Composable
internal fun ChapterDiscussion(
    manga: Manga,
    chapter: Chapter,
    repository: CopyCommunityRepository,
    background: Color,
    foreground: Color,
    onNext: (() -> Unit)?,
    reviewRepository: ReviewRepository,
    previewVisible: Boolean = true,
    onClose: (() -> Unit)? = null,
    state: ChapterDiscussionState,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.ensureLoaded() }
    var expanded by rememberSaveable(manga.id, chapter.id, previewVisible) { mutableStateOf(!previewVisible) }
    val mixed = remember(state.sources) { mixedDiscussionRows(state.sources) }
    val chapterCount = state.sources.firstOrNull { it.isChapter }?.source?.reviewCount
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
    LaunchedEffect(state, expanded, nearEnd, state.sources, busy) {
        if (expanded && nearEnd && !busy) state.loadNext()
    }

    if (previewVisible) Column(Modifier.fillMaxWidth().background(background).padding(horizontal = 16.dp)
        .padding(top = 12.dp, bottom = 96.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("本话读完了", color = muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (onNext != null) TextButton(onClick = onNext) {
                Text("下一话 →", color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(4.dp))
        DiscussionHeading(chapter, chapterCount, mixed.size, busy, foreground,
            onRefresh = { state.refresh(forceRefresh = true) })
        if (mixed.isEmpty()) {
            if (delayedBusy) DiscussionLoading(muted)
            else if (!busy && notices.isEmpty() && !state.hasMore) Text("暂时还没有公开讨论", color = muted,
                fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp))
        }
        mixed.take(6).forEach { row ->
            key(row.key) { DiscussionRow(row, state) }
            HorizontalDivider(color = foreground.copy(alpha = .06f), thickness = .5.dp)
        }
        if (mixed.isEmpty()) {
            notices.forEach { source ->
                key(source.key) { DiscussionSourceActions(source, state, foreground, busy) }
            }
        } else if (notices.isNotEmpty()) {
            Text(notices.map { it.source.name }.distinct().joinToString(" · ") + " 暂未更新",
                color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        }
        if (mixed.isNotEmpty() || notices.isNotEmpty() || state.hasMore) TextButton(
            onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
        ) {
            Text("展开评论", color = foreground, fontSize = 13.sp)
        }
    }

    if (expanded) {
        MotionBottomSheet(onDismissRequest = { expanded = false; onClose?.invoke() },
            containerColor = background, contentColor = foreground) { dismiss ->
            Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    DiscussionHeading(chapter, chapterCount, mixed.size, busy, foreground,
                        modifier = Modifier.weight(1f), onRefresh = {
                            state.refresh(forceRefresh = true)
                            scope.launch { listState.scrollToItem(0) }
                        })
                    MotionIconButton(onClick = dismiss) {
                        Icon(Icons.Outlined.Close, "关闭评论", tint = muted, modifier = Modifier.size(20.dp))
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                    items(mixed, key = ReaderDiscussionRow::key) { row ->
                        DiscussionRow(row, state)
                        HorizontalDivider(color = foreground.copy(alpha = .06f), thickness = .5.dp)
                    }
                    items(notices, key = { "notice:${it.key}" }) { source ->
                        DiscussionSourceActions(source, state, foreground, busy)
                    }
                    item("discussion-footer") {
                        when {
                            delayedBusy -> DiscussionLoading(muted)
                            busy -> Spacer(Modifier.height(40.dp))
                            state.hasMore -> TextButton(onClick = state::loadNext, modifier = Modifier.fillMaxWidth()) {
                                Text("加载更多", color = foreground, fontSize = 12.sp)
                            }
                            mixed.isEmpty() && notices.isEmpty() -> Text("暂时还没有公开讨论", color = muted,
                                fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
                            mixed.isNotEmpty() -> Text("已显示当前可获取的讨论", color = muted,
                                fontSize = 11.sp, modifier = Modifier.padding(vertical = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscussionHeading(
    chapter: Chapter,
    chapterCount: Int?,
    loadedCount: Int,
    busy: Boolean,
    foreground: Color,
    modifier: Modifier = Modifier,
    onRefresh: () -> Unit,
) {
    val muted = foreground.copy(alpha = .58f)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("评论", color = foreground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                val count = listOfNotNull(chapterCount?.let { "本话 $it" },
                    "已加载 $loadedCount".takeIf { loadedCount > 0 }).joinToString(" · ")
                if (count.isNotEmpty()) Text(count, color = muted, fontSize = 10.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${chapter.title} · 最新优先", color = muted, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
        }
        MotionIconButton(onClick = onRefresh, enabled = !busy) {
            Icon(Icons.Outlined.Refresh, "刷新最新评论", tint = muted.copy(alpha = if (busy) .28f else .58f),
                modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DiscussionRow(row: ReaderDiscussionRow, state: ChapterDiscussionState) {
    CommunityDiscussionComment(row.source.source, row.review, loadReplies = state::loadReplies,
        scopeLabel = if (row.source.isChapter) "本话" else "作品")
}

@Composable
private fun DiscussionSourceActions(
    source: ReaderDiscussionSource,
    state: ChapterDiscussionState,
    foreground: Color,
    busy: Boolean,
) {
    if (source.source.status == ReviewStatus.NO_MATCH && source.source.candidates.isNotEmpty()) {
        CommentMatchChoice(source.source.copy(loadingMore = busy), onMatch = state::choose)
        if (source.source.reviewsError != null) {
            DiscussionNotice(source, foreground, busy) { state.retry(source.key) }
        }
    } else {
        DiscussionNotice(source, foreground, busy) { state.retry(source.key) }
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
    val message = when {
        source.reviewsError != null -> source.reviewsError
        source.status == ReviewStatus.NO_MATCH -> "暂未确认对应漫画"
        else -> source.message.ifBlank { "暂时无法读取评论" }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${source.name}${if (state.isChapter) " · 本话" else " · 作品"}  $message",
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
