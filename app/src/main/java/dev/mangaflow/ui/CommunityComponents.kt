package dev.mangaflow.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewKind
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.data.ReviewStatus
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val CommunityWhite: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val CommunityMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val CommunityLine: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)
private val CommunityProviders = listOf("copy", "bangumi", "anilist")

/** Mix only confirmed reviews, keeping each source's native ID and pagination. */
private data class MixedReview(val source: ReviewSource, val review: MangaReview, val order: Int)

private fun reviewDateKey(date: String): String {
    val value = date.trim().replace('T', ' ')
    return if (value.length >= 10 && value[4] == '-' && value[7] == '-' &&
        value.take(4).all(Char::isDigit)) value.take(19) else ""
}

@Composable
internal fun CommunityPanel(
    mangaId: String,
    sources: List<ReviewSource>,
    loading: Boolean,
    onMore: (String) -> Unit,
    onRetry: (String) -> Unit,
    onMatch: (String, String) -> Unit,
    onRelink: (String) -> Unit,
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage,
    modifier: Modifier = Modifier,
) {
    val mixed = remember(sources) {
        sources.asSequence().filter { it.status == ReviewStatus.READY }
            .flatMap { source -> source.reviews.mapIndexed { index, review ->
                MixedReview(source, review, index)
            }.asSequence() }
            .sortedWith(compareByDescending<MixedReview> { reviewDateKey(it.review.date) }
                .thenBy { CommunityProviders.indexOf(it.source.id).let { n -> if (n < 0) 99 else n } }
                .thenBy(MixedReview::order)).toList()
    }
    val listState = rememberLazyListState()
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 4
        }
    }
    val loadable = sources.filter {
        it.status == ReviewStatus.READY && it.nextPage != null &&
            !it.loadingMore && it.reviewsError == null
    }
    LaunchedEffect(mangaId, sources, nearEnd, loading) {
        if (!loading && nearEnd && sources.none { it.loadingMore }) {
            loadable.minWithOrNull(compareBy<ReviewSource> { it.reviews.size }
                .thenBy { CommunityProviders.indexOf(it.id).let { n -> if (n < 0) 99 else n } })
                ?.let { onMore(it.id) }
        }
    }
    val failures = sources.filter { it.status == ReviewStatus.UNAVAILABLE || it.reviewsError != null }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        items(mixed, key = {
            it.source.id + ":" + it.source.subjectId + ":" + it.review.kind + ":" + it.review.id
        }) { item ->
            DiscussionComment(item.source, item.review, loadReplies)
            HorizontalDivider(color = CommunityLine, thickness = .5.dp)
        }
        item("mixed-footer") {
            Column(Modifier.fillMaxWidth()) {
                when {
                    loading || sources.any { it.loadingMore } -> SmallLoading()
                    mixed.isEmpty() && failures.isEmpty() -> Text("还没有可显示的评论",
                        color = CommunityMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 18.dp))
                    mixed.isNotEmpty() && loadable.isEmpty() && failures.isEmpty() -> Text(
                        "当前可获取的评论已加载完毕", color = CommunityMuted, fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 16.dp))
                }
                sources.forEach { source ->
                    key(mangaId, source.id) {
                        if (source.status == ReviewStatus.UNAVAILABLE || source.reviewsError != null) {
                            CommentLoadNotice(source, onRetry)
                        }
                        if (source.status == ReviewStatus.NO_MATCH && source.candidates.isNotEmpty()) {
                            CommentMatchChoice(source, onMatch)
                        }
                    }
                }
            }
        }
    }
}

/** Failures belong beside the affected content, without a separate management screen. */
@Composable
private fun CommentLoadNotice(source: ReviewSource, onRetry: (String) -> Unit) {
    val message = source.reviewsError?.takeIf(String::isNotBlank)
        ?: source.message.takeIf { source.status == ReviewStatus.UNAVAILABLE && it.isNotBlank() }
        ?: "评论暂时无法读取，请稍后重试。"
    var expanded by rememberSaveable(source.id, message) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(
        animationSpec = if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(),
    )) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CommentSourceBadge(source.id)
            Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button,
                    onClickLabel = if (expanded) "收起失败原因" else "查看失败原因") { expanded = !expanded }
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (source.loadingMore) "正在重新读取评论" else "评论暂时无法读取",
                    color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                MotionSwap(expanded, Modifier.size(16.dp)) { open ->
                    Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        null, tint = CommunityMuted, modifier = Modifier.size(16.dp))
                }
            }
            MotionIconButton(onClick = { onRetry(source.id) }, enabled = !source.loadingMore) {
                Icon(Icons.Outlined.Refresh, "重试${source.name}评论", tint = CommunityMuted,
                    modifier = Modifier.size(18.dp))
            }
        }
        if (expanded) Text(message, color = CommunityMuted, fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 10.dp))
    }
}

/** Only an explicit choice can add reviews for an uncertain title match. */
@Composable
private fun CommentMatchChoice(source: ReviewSource, onMatch: (String, String) -> Unit) {
    var expanded by rememberSaveable(source.id, source.candidates) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(
        animationSpec = if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(),
    )) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !source.loadingMore, role = Role.Button,
                onClickLabel = if (expanded) "收起漫画选择" else "选择对应漫画以查看评论") { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CommentSourceBadge(source.id)
            Text(if (source.loadingMore) "正在读取评论" else "选择对应漫画以查看评论",
                color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            MotionSwap(expanded, Modifier.size(32.dp)) { open ->
                Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    null, tint = CommunityMuted, modifier = Modifier.size(16.dp))
            }
        }
        if (expanded) {
            source.candidates.forEach { candidate ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp))
                    .clickable(enabled = !source.loadingMore, role = Role.Button,
                        onClickLabel = "查看${candidate.title}的评论") {
                        expanded = false
                        onMatch(source.id, candidate.id)
                    }.padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(candidate.title, color = CommunityWhite, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (candidate.subtitle.isNotBlank()) Text(candidate.subtitle,
                            color = CommunityMuted, fontSize = 10.sp, lineHeight = 15.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.ChevronRight, null, tint = CommunityMuted,
                        modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun CommentSourceBadge(sourceId: String) {
    val onLight = MaterialTheme.colorScheme.background.luminance() > .5f
    val accent = when (sourceId) {
        "copy" -> if (onLight) Color(0xFF815941) else Color(0xFFD5B7A0)
        "bangumi" -> if (onLight) Color(0xFF506B92) else Color(0xFFB5C8E4)
        "anilist" -> if (onLight) Color(0xFF725B8C) else Color(0xFFC7B2DD)
        else -> CommunityMuted
    }
    Text(when (sourceId) {
        "copy" -> "拷贝"; "bangumi" -> "bgm"; "anilist" -> "AniList"
        else -> providerName(sourceId)
    }, modifier = Modifier.clip(RoundedCornerShape(5.dp))
        .background(accent.copy(alpha = .12f)).padding(horizontal = 5.dp, vertical = 2.dp)
        .semantics { contentDescription = "评论来自" + providerName(sourceId) },
        color = accent, fontSize = 10.sp, lineHeight = 13.sp,
        fontWeight = FontWeight.Medium, maxLines = 1)
}

@Composable
private fun CommentFailure(message: String, busy: Boolean, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = CommunityMuted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = retry, enabled = !busy) {
            Icon(Icons.Outlined.Refresh, "重新读取评论", tint = CommunityMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SmallLoading() {
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(17.dp), color = CommunityMuted, strokeWidth = 1.5.dp)
    }
}

@Composable
fun CommunityComment(
    review: MangaReview,
    modifier: Modifier = Modifier,
    foreground: Color = Color.White,
    muted: Color = Color.White.copy(alpha = 0.5f),
) {
    CommentContent(review, modifier, foreground, muted, showReplyCount = true)
}

@Composable
private fun CommentContent(
    review: MangaReview,
    modifier: Modifier,
    foreground: Color,
    muted: Color,
    showReplyCount: Boolean,
    sourceId: String? = null,
) {
    val text = review.body.ifBlank { review.summary }.trim()
    val conceal = review.spoiler || review.kind == ReviewKind.REVIEW
    var revealed by rememberSaveable(review.id, review.url, conceal) { mutableStateOf(!conceal) }
    var expanded by rememberSaveable(review.id, review.url) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().animateContentSize(animationSpec = tween(160)),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CommentAvatar(review, foreground)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(review.author, color = foreground.copy(alpha = .87f), fontSize = 12.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (sourceId != null) CommentSourceBadge(sourceId)
                    }
                    if (review.date.isNotBlank()) Text(review.date, color = muted, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                review.score?.let {
                    Text("${communityScore(it)} / ${review.scoreScale}", color = muted, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(3.dp))
            if (review.replyTo.isNotBlank()) {
                Text("回复 ${review.replyTo}", color = muted, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 5.dp))
            }
            if (!revealed) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp))
                    .background(foreground.copy(alpha = .045f)).clickable(role = Role.Button) { revealed = true }
                    .padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VisibilityOff, null, tint = muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (review.kind == ReviewKind.REVIEW) "展开长评 · 可能涉及后续剧情" else "含剧透，点按查看",
                        color = muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null, tint = muted, modifier = Modifier.size(15.dp))
                }
            } else {
                if (review.kind == ReviewKind.REVIEW && review.body.isNotBlank() && review.summary.isNotBlank() &&
                    review.summary.trim() != text) {
                    Text(review.summary, color = foreground, fontSize = 13.sp, lineHeight = 21.sp,
                        fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 5.dp))
                }
                Text(text, color = foreground.copy(alpha = .92f), fontSize = 13.sp, lineHeight = 20.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
            }
            if ((revealed && (overflows || expanded || conceal)) || (showReplyCount && (review.replyCount ?: 0) > 0)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (revealed && (overflows || expanded)) TextButton(
                        onClick = { expanded = !expanded }, contentPadding = PaddingValues(end = 12.dp),
                    ) { Text(if (expanded) "收起" else "展开", color = muted, fontSize = 11.sp) }
                    if (conceal && revealed) TextButton(onClick = { revealed = false; expanded = false },
                        contentPadding = PaddingValues(end = 12.dp)) {
                        Text(if (review.kind == ReviewKind.REVIEW) "收起长评" else "隐藏剧透", color = muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    if (showReplyCount && (review.replyCount ?: 0) > 0) {
                        Text("${review.replyCount} 条回复", color = muted, fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 10.dp))
                    }
                }
            }
        }
    }
}

/** Ovio's saved Avatar pattern: initials remain underneath a lazy image, including on failure. */
@Composable
private fun CommentAvatar(review: MangaReview, foreground: Color) {
    val context = LocalContext.current
    var failed by remember(review.avatarUrl) { mutableStateOf(false) }
    val initial = remember(review.author) {
        review.author.trim().takeIf { it.isNotEmpty() }
            ?.let { String(Character.toChars(it.codePointAt(0))).uppercase(Locale.ROOT) } ?: "·"
    }
    val request = remember(review.avatarUrl, context) {
        ImageRequest.Builder(context).data(review.avatarUrl).size(88).crossfade(100).build()
    }
    Box(Modifier.size(28.dp).clip(CircleShape).background(foreground.copy(alpha = .075f))
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(initial, color = foreground.copy(alpha = .55f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (review.avatarUrl.isNotBlank() && !failed) {
            AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(), onError = { failed = true })
        }
    }
}

@Composable
private fun DiscussionComment(
    source: ReviewSource,
    review: MangaReview,
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        CommentContent(review, Modifier, CommunityWhite, CommunityMuted,
            showReplyCount = source.id != "copy", sourceId = source.id)
        if (source.id == "copy" && source.subjectId.isNotBlank() && (review.replyCount ?: 0) > 0) {
            CommentReplies(source, review, loadReplies)
        }
    }
}

@Composable
private fun CommentReplies(
    source: ReviewSource,
    review: MangaReview,
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage,
) {
    var expanded by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<MangaReview>>(emptyList()) }
    var nextPage by remember { mutableStateOf<Int?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var failedPage by remember { mutableIntStateOf(0) }
    var requestVersion by remember { mutableIntStateOf(0) }
    var request by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val fetchReplies by rememberUpdatedState(loadReplies)

    fun load(page: Int) {
        if (busy) return
        val version = ++requestVersion
        busy = true
        error = null
        request = scope.launch {
            try {
                val result = fetchReplies(source, review, page)
                currentCoroutineContext().ensureActive()
                if (version == requestVersion) {
                    rows = (if (page == 0) result.items else rows + result.items).distinctBy { it.id }
                    nextPage = result.nextPage
                    loaded = true
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                if (version == requestVersion) {
                    error = failure.message ?: "暂时无法读取回复"
                    failedPage = page
                }
            } finally {
                if (version == requestVersion) busy = false
            }
        }
    }

    TextButton(onClick = {
        expanded = !expanded
        if (expanded && !loaded) load(0)
        else if (!expanded) {
            requestVersion++
            request?.cancel()
            busy = false
        }
    }, modifier = Modifier.padding(start = 36.dp), contentPadding = PaddingValues(end = 10.dp)) {
        Text(if (expanded) "收起回复" else "${review.replyCount} 条回复", color = CommunityMuted, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            null, tint = CommunityMuted, modifier = Modifier.size(15.dp))
    }
    if (expanded) {
        Column(Modifier.fillMaxWidth().padding(start = 36.dp)) {
            rows.forEach { reply ->
                key(source.subjectId, review.id, reply.id) {
                    CommunityComment(reply, Modifier.padding(bottom = 10.dp), CommunityWhite, CommunityMuted)
                }
            }
            if (error != null) CommentFailure(error.orEmpty(), busy) { load(failedPage) }
            if (busy) SmallLoading()
            else if (loaded && rows.isEmpty() && error == null) {
                Text("暂无可展示的回复", color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (!busy && error == null && nextPage != null) TextButton(onClick = { nextPage?.let(::load) },
                contentPadding = PaddingValues(end = 10.dp)) {
                Text("更多回复", color = CommunityMuted, fontSize = 12.sp)
            }
        }
    }
}

private fun providerName(id: String): String = when (id) {
    "copy" -> "拷贝漫画"
    "bangumi" -> "Bangumi"
    "anilist" -> "AniList"
    else -> id
}

private fun communityScore(score: Double): String =
    if (score == score.toInt().toDouble()) score.toInt().toString() else String.format(Locale.ROOT, "%.1f", score)
