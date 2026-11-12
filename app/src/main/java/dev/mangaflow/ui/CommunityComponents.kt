package dev.mangaflow.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val CommunityWhite = Color(0xFFF3F3F1)
private val CommunityMuted = Color(0xFF9B9BA1)
private val CommunityLine = Color.White.copy(alpha = 0.07f)
private val CommunityProviders = listOf("copy", "bangumi", "anilist")

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
    var selectedId by rememberSaveable(mangaId) { mutableStateOf("copy") }
    val selected = sources.firstOrNull { it.id == selectedId }
    val listStates = rememberSaveableStateHolder()

    Column(modifier.fillMaxSize()) {
        ProviderStrip(sources, selectedId, loading, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            selectedId = it
        }
        HorizontalDivider(color = CommunityLine, thickness = 0.5.dp)
        // Each provider keeps its scroll position when switching source or detail tabs.
        listStates.SaveableStateProvider("$mangaId:$selectedId:${selected?.subjectId.orEmpty()}") {
            val list = rememberLazyListState()
            var chooseMatch by rememberSaveable {
                mutableStateOf(selected?.status == ReviewStatus.NO_MATCH)
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                if (selected == null) {
                    item("pending") { PendingSource(selectedId, loading, onRetry) }
                } else {
                    item("source") {
                        SourceSummary(selected, showMatchedTitle = true)
                        if (selected.status != ReviewStatus.READY) {
                            SourceNotice(selected, onRetry, onChoose = { chooseMatch = !chooseMatch },
                                chooseLabel = if (chooseMatch) "收起候选条目" else "选择对应漫画")
                        } else if (selected.candidates.isNotEmpty()) {
                            TextButton(onClick = { chooseMatch = !chooseMatch },
                                contentPadding = PaddingValues(end = 10.dp)) {
                                Text(if (chooseMatch) "收起候选条目" else "关联有误？", color = CommunityMuted, fontSize = 12.sp)
                            }
                        }
                        if (selected.id != "copy" && selected.status != ReviewStatus.NO_MATCH) {
                            TextButton(onClick = { chooseMatch = false; onRelink(selected.id) },
                                enabled = !selected.loadingMore, contentPadding = PaddingValues(end = 10.dp)) {
                                Text("重新关联漫画", color = CommunityMuted, fontSize = 12.sp)
                            }
                        }
                    }
                    if (chooseMatch) {
                        items(selected.candidates, key = { "candidate:${it.id}" }) { candidate ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = !selected.loadingMore, role = Role.Button) {
                                    chooseMatch = false
                                    onMatch(selected.id, candidate.id)
                                }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(candidate.title, color = CommunityWhite, fontSize = 14.sp,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (candidate.subtitle.isNotBlank()) Text(candidate.subtitle, color = CommunityMuted,
                                        fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
                                }
                                Icon(Icons.Outlined.ChevronRight, "关联这本漫画", tint = CommunityMuted, modifier = Modifier.size(18.dp))
                            }
                            HorizontalDivider(color = CommunityLine, thickness = 0.5.dp)
                        }
                    }
                    if (selected.status == ReviewStatus.READY && selected.reviews.isEmpty() &&
                        selected.reviewsError == null && !selected.loadingMore) {
                        item("empty") {
                            Text(if (selected.nextPage == null) "这里还没有公开评论" else "这一页暂无可展示的评论",
                                color = CommunityMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 22.dp))
                        }
                    }
                    items(selected.reviews, key = { "${selected.id}:${selected.subjectId}:${it.kind}:${it.id}" }) { review ->
                        DiscussionComment(selected, review, loadReplies)
                        HorizontalDivider(color = CommunityLine, thickness = 0.5.dp)
                    }
                    item("pagination") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (selected.reviewsError != null) {
                                CommentFailure(selected.reviewsError, selected.loadingMore) {
                                    if (selected.reviews.isNotEmpty() && selected.nextPage != null) onMore(selected.id)
                                    else onRetry(selected.id)
                                }
                            }
                            if (selected.loadingMore) SmallLoading()
                            else if (selected.nextPage != null && selected.reviewsError == null) {
                                TextButton(onClick = { onMore(selected.id) }, modifier = Modifier.fillMaxWidth()) {
                                    Text("加载更多评论", color = CommunityWhite, fontSize = 13.sp)
                                }
                            } else if (selected.reviews.isNotEmpty() && selected.reviewsError == null) {
                                Text("已到末尾", color = CommunityMuted, fontSize = 11.sp,
                                    modifier = Modifier.padding(vertical = 20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderStrip(
    sources: List<ReviewSource>,
    selectedId: String,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val providerIds = (CommunityProviders + sources.map { it.id }).distinct()
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        providerIds.forEach { id ->
            val source = sources.firstOrNull { it.id == id }
            val selected = id == selectedId
            val background by animateColorAsState(
                if (selected) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.025f),
                animationSpec = tween(150), label = "provider-selection",
            )
            Row(Modifier.heightIn(min = 48.dp).widthIn(min = 96.dp).clip(RoundedCornerShape(12.dp))
                .background(background).selectable(selected, role = Role.Tab, onClick = { onSelect(id) })
                .semantics { contentDescription = providerDescription(id, source, loading) }
                .padding(horizontal = 9.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ProviderMark(id, tint = if (selected) CommunityWhite else CommunityMuted)
                Column {
                    if (source == null && loading) {
                        CircularProgressIndicator(Modifier.size(12.dp), color = CommunityMuted, strokeWidth = 1.dp)
                    } else {
                        Text(sourceMetric(source), color = if (selected) CommunityWhite else CommunityMuted,
                            fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    }
                    Text(when {
                        source?.status == ReviewStatus.NO_MATCH -> "待关联"
                        source?.status == ReviewStatus.UNAVAILABLE -> "暂不可用"
                        id == "copy" -> "热度"
                        else -> "/ ${source?.scoreScale ?: if (id == "anilist") 100 else 10}"
                    }, color = CommunityMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
                }
            }
        }
    }
}

/** Compact site abbreviations; these are deliberately not imitations of official logos. */
@Composable
internal fun ProviderMark(id: String, modifier: Modifier = Modifier, tint: Color = CommunityWhite) {
    Box(modifier.size(25.dp).clip(RoundedCornerShape(7.dp)).background(tint.copy(alpha = 0.065f)),
        contentAlignment = Alignment.Center) {
        Text(when (id) { "copy" -> "拷"; "bangumi" -> "bgm"; "anilist" -> "A"; else -> id.take(2) },
            color = tint, fontSize = if (id == "bangumi") 9.sp else 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SourceSummary(source: ReviewSource, showMatchedTitle: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val detail = listOfNotNull(
                source.ratingCount?.let { "$it 人评分" },
                source.reviewCount?.let { "$it 条评论" }
                    ?: source.reviews.takeIf { it.isNotEmpty() }?.let {
                        if (source.id == "anilist") "已加载 ${it.size} 篇长评" else "已加载 ${it.size} 条评论"
                    },
            ).joinToString(" · ").takeIf { it.isNotBlank() }
            Text(listOfNotNull(source.name, detail).joinToString(" · "), color = CommunityMuted, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showMatchedTitle && source.matchedTitle.isNotBlank()) {
                Text(source.matchedTitle, color = CommunityWhite.copy(alpha = 0.75f), fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PendingSource(id: String, loading: Boolean, onRetry: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (loading) "正在读取${providerName(id)}评论" else "暂未取得${providerName(id)}评价",
            color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (!loading) TextButton(onClick = { onRetry(id) }) { Text("重试", color = CommunityWhite, fontSize = 12.sp) }
    }
}

@Composable
private fun SourceNotice(
    source: ReviewSource,
    onRetry: (String) -> Unit,
    onChoose: () -> Unit,
    chooseLabel: String = "选择对应漫画",
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(source.message.ifBlank {
            if (source.status == ReviewStatus.NO_MATCH) "还未确认对应条目" else "暂时无法读取这个站点"
        }, color = CommunityMuted, fontSize = 12.sp, lineHeight = 19.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (source.candidates.isNotEmpty()) TextButton(onClick = onChoose,
                enabled = !source.loadingMore, contentPadding = PaddingValues(end = 14.dp)) {
                Text(chooseLabel, color = CommunityWhite, fontSize = 12.sp)
            }
            TextButton(onClick = { onRetry(source.id) }, enabled = !source.loadingMore,
                contentPadding = PaddingValues(end = 14.dp)) {
                Text(if (source.status == ReviewStatus.NO_MATCH) "重新查找" else "重试", color = CommunityMuted, fontSize = 12.sp)
            }
            if (source.loadingMore) CircularProgressIndicator(Modifier.size(12.dp), color = CommunityMuted, strokeWidth = 1.dp)
        }
    }
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
) {
    val text = review.body.ifBlank { review.summary }.trim()
    val conceal = review.spoiler || review.kind == ReviewKind.REVIEW
    var revealed by rememberSaveable(review.id, review.url, conceal) { mutableStateOf(!conceal) }
    var expanded by rememberSaveable(review.id, review.url) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().animateContentSize(animationSpec = tween(160)),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CommentAvatar(review, foreground)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(review.author, color = foreground.copy(alpha = .84f), fontSize = 12.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (review.date.isNotBlank()) Text(review.date, color = muted, fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                review.score?.let {
                    Text("${communityScore(it)} / ${review.scoreScale}", color = muted, fontSize = 11.sp,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
            Spacer(Modifier.height(7.dp))
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
                Text(text, color = foreground.copy(alpha = .92f), fontSize = 14.sp, lineHeight = 23.sp,
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
        ImageRequest.Builder(context).data(review.avatarUrl).size(96).crossfade(100).build()
    }
    Box(Modifier.size(32.dp).clip(CircleShape).background(foreground.copy(alpha = .075f))
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
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        CommentContent(review, Modifier, CommunityWhite, CommunityMuted, showReplyCount = source.id != "copy")
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
    }, modifier = Modifier.padding(start = 42.dp), contentPadding = PaddingValues(end = 10.dp)) {
        Text(if (expanded) "收起回复" else "${review.replyCount} 条回复", color = CommunityMuted, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
        Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            null, tint = CommunityMuted, modifier = Modifier.size(15.dp))
    }
    if (expanded) {
        Column(Modifier.fillMaxWidth().padding(start = 42.dp)) {
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

private fun sourceMetric(source: ReviewSource?): String = when {
    source == null -> "—"
    source.id == "copy" -> source.popularity?.let(::compactPopularity) ?: "—"
    source.score != null -> communityScore(source.score)
    else -> "—"
}

private fun providerDescription(id: String, source: ReviewSource?, loading: Boolean): String {
    val name = source?.name ?: providerName(id)
    return when {
        source == null -> "$name，${if (loading) "正在加载" else "暂未取得评价"}"
        source.status == ReviewStatus.NO_MATCH -> "$name，待关联漫画"
        source.status == ReviewStatus.UNAVAILABLE -> "$name，暂时不可用"
        source.id == "copy" -> "$name，热度 ${source.popularity?.toString() ?: "暂无数据"}，查看评论"
        else -> "$name，${source.score?.let { "${communityScore(it)} 分，满分 ${source.scoreScale}" } ?: "暂无评分"}，查看评论"
    }
}

private fun communityScore(score: Double): String =
    if (score == score.toInt().toDouble()) score.toInt().toString() else String.format(Locale.ROOT, "%.1f", score)

private fun compactPopularity(value: Long): String = when {
    value >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", value / 100_000_000.0)
    value >= 10_000 -> String.format(Locale.ROOT, "%.1f万", value / 10_000.0)
    else -> value.toString()
}
