package dev.mangaflow.ui

import dev.mangaflow.typography.MangaFontScene

import dev.mangaflow.ui.platform.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import dev.mangaflow.ui.motion.MotionIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val CommunityWhite: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val CommunityMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val CommunityLine: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = .07f)
private val CommunityProviders = dev.mangaflow.data.ReviewProviders.displayOrder

/** Mix only confirmed reviews, keeping each source's native ID and pagination. */
private data class ReviewRows(val sourceId: String, val subjectId: String, val reviews: List<MangaReview>)
private data class MixedReview(val sourceId: String, val subjectId: String, val review: MangaReview, val order: Int) {
    val dateKey = reviewDateKey(review.date)
    val sourceOrder = CommunityProviders.indexOf(sourceId).let { if (it < 0) 99 else it }
}

private fun reviewDateKey(date: String): String {
    val value = date.trim().replace('T', ' ')
    return if (value.length >= 10 && value[4] == '-' && value[7] == '-' &&
        value.take(4).all(Char::isDigit)) value.take(19) else ""
}

private fun MixedReview.stableKey(): String =
    sourceId + ":" + subjectId + ":" + review.kind + ":" + review.id

private data class ReviewCluster(val key: String, val rows: List<MixedReview>, val promotion: Boolean)

/** Normalize and group actual review text away from input/scroll composition. */
private suspend fun prepareReviewClusters(
    inputs: List<ReviewRows>,
    selectedSource: String?,
    longOnly: Boolean,
): List<ReviewCluster> {
    val context = currentCoroutineContext()
    val ordered = inputs.asSequence().filter { selectedSource == null || it.sourceId == selectedSource }
        .flatMap { source ->
            source.reviews.asSequence().withIndex()
                .filter { !longOnly || it.value.kind == ReviewKind.REVIEW }
                .map { item ->
                    context.ensureActive()
                    MixedReview(source.sourceId, source.subjectId, item.value, item.index)
                }
        }.distinctBy { it.stableKey() }
        .sortedWith(compareByDescending<MixedReview> { it.dateKey }
            .thenBy { it.sourceOrder }.thenBy(MixedReview::order))
    val grouped = linkedMapOf<String, MutableList<MixedReview>>()
    ordered.forEach { row ->
        context.ensureActive()
        val content = reviewDuplicateText(row.review)
        val key = if (content.length >= 12) "text:${row.review.kind}:${row.review.spoiler}:$content"
            else "id:" + row.stableKey()
        grouped.getOrPut(key) { mutableListOf() }.add(row)
    }
    return grouped.map { (key, rows) ->
        context.ensureActive()
        ReviewCluster(key, rows, rows.any { isLikelyReviewPromotion(it.review) })
    }
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
    onScrollTarget: (LazyListState) -> Unit = {},
    backdrop: HazeState = rememberHazeState(),
    captureBackdrop: Boolean = true,
    active: Boolean = true,
    bottomPadding: Dp = 12.dp,
    filterOpen: Boolean = false,
    onFilterDismiss: () -> Unit = {},
    onFilterChanged: (Boolean) -> Unit = {},
) {
    var selectedSource by rememberSaveable(mangaId) { mutableStateOf<String?>(null) }
    var longOnly by rememberSaveable(mangaId) { mutableStateOf(false) }
    var hidePromotions by rememberSaveable(mangaId) { mutableStateOf(true) }
    var filterPresent by remember(mangaId) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    SideEffect { if (active) { onScrollTarget(listState); onFilterChanged(selectedSource != null || longOnly || !hidePromotions) } }
    LaunchedEffect(active) {
        if (!active) {
            onFilterDismiss()
            listState.stopScroll()
        }
    }
    // Paging/busy/metric-only publications must not normalize and sort every comment again.
    val reviewRows = remember(sources) {
        sources.filter { it.status == ReviewStatus.READY && it.hasWorkReviews }
            .map { ReviewRows(it.id, it.subjectId, it.reviews) }
    }
    val sourcesById = remember(sources) { sources.associateBy { it.id } }
    var clusters by remember(mangaId, selectedSource, longOnly) { mutableStateOf(emptyList<ReviewCluster>()) }
    var preparing by remember(mangaId, selectedSource, longOnly) { mutableStateOf(true) }
    LaunchedEffect(mangaId, reviewRows, selectedSource, longOnly, active) {
        if (!active) return@LaunchedEffect
        preparing = true
        val prepared = withContext(Dispatchers.Default) {
            prepareReviewClusters(reviewRows, selectedSource, longOnly)
        }
        // Cancelled inputs cannot replace the current filter. Existing rows remain during an append.
        clusters = prepared
        preparing = false
    }
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 4
        }
    }
    val relevantSources = remember(sources, selectedSource) {
        sources.filter { it.hasWorkReviews && (selectedSource == null || it.id == selectedSource) }
    }
    val nextSource = remember(relevantSources) {
        relevantSources.filter { it.status == ReviewStatus.READY && it.nextPage != null &&
            !it.loadingMore && it.reviewsError == null }
            .minWithOrNull(compareBy<ReviewSource> { it.reviews.size }
                .thenBy { CommunityProviders.indexOf(it.id).let { n -> if (n < 0) 99 else n } })
    }
    // A selected ready provider need not wait for unrelated score/comment providers.
    val relevantBusy = preparing || relevantSources.any { it.loadingMore } ||
        (loading && (selectedSource == null || relevantSources.isEmpty()))
    LaunchedEffect(mangaId, sources, nearEnd, relevantBusy, selectedSource, longOnly, active, clusters.isEmpty()) {
        if (active && !relevantBusy && nearEnd &&
            (clusters.isNotEmpty() || (!longOnly && selectedSource == null))) nextSource?.let { onMore(it.id) }
    }
    val unavailableSources = remember(relevantSources) { relevantSources.filter {
        it.status == ReviewStatus.UNAVAILABLE || it.reviewsError != null
    } }
    val allUnavailable = relevantSources.isNotEmpty() && unavailableSources.size == relevantSources.size
    val resetPosition = { scope.launch { listState.scrollToItem(0) }; Unit }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().then(if (captureBackdrop && (filterOpen || filterPresent) && active)
            Modifier.mangaHazeSource(backdrop) else Modifier)) {
            DetailCommentFilter(backdrop, sources, selectedSource, longOnly, hidePromotions,
                visible = filterOpen && active, onPresenceChange = { filterPresent = it },
                onSelect = { selectedSource = it; resetPosition() },
                onLongOnly = { longOnly = it; resetPosition() },
                onHidePromotions = { hidePromotions = it; resetPosition() },
                onRetry = onRetry, onMatch = onMatch, onDismiss = onFilterDismiss)
            LazyColumn(state = listState, userScrollEnabled = active,
                modifier = Modifier.weight(1f).fillMaxWidth().motionInputGate(active),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding)) {
                items(clusters, key = { it.key }) { cluster ->
                    CuratedReviewCluster(mangaId, cluster, sourcesById, hidePromotions, loadReplies)
                    HorizontalDivider(Modifier.padding(start = 38.dp), color = CommunityLine.copy(alpha = .035f), thickness = .5.dp)
                }
                item("mixed-footer") {
                    Column(Modifier.fillMaxWidth()) {
                        when {
                            relevantBusy -> SmallLoading()
                            clusters.isEmpty() && unavailableSources.isNotEmpty() ->
                                CommentUnavailableNotice(unavailableSources, allUnavailable, onRetry)
                            clusters.isEmpty() -> Text(if (selectedSource != null || longOnly) "暂没有符合筛选的评论" else "还没有可显示的评论",
                                color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
                            unavailableSources.isNotEmpty() -> CommentUnavailableNotice(unavailableSources, allUnavailable, onRetry)
                        }
                        if (clusters.isEmpty() && nextSource != null && !relevantBusy) {
                            TextButton(onClick = { onMore(nextSource.id) }) { Text("继续查找评论", color = CommunityMuted) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CuratedReviewCluster(
    mangaId: String,
    cluster: ReviewCluster,
    sources: Map<String, ReviewSource>,
    hidePromotions: Boolean,
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage,
) {
    var revealPromotion by rememberSaveable(mangaId, cluster.key) { mutableStateOf(false) }
    var showSimilar by rememberSaveable(mangaId, cluster.key) { mutableStateOf(false) }
    val concealed = hidePromotions && cluster.promotion && !revealPromotion
    Column(Modifier.fillMaxWidth().animateContentSize(
        animationSpec = if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap())) {
        if (concealed) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button,
                onClickLabel = "展开疑似推广原文") { revealPromotion = true }.padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.VisibilityOff, null, tint = CommunityMuted.copy(alpha = .6f), modifier = Modifier.size(15.dp))
                Text("疑似推广" + if (cluster.rows.size > 1) " · 相似内容 ${cluster.rows.size} 条" else " · 点击查看原文",
                    color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                CommentSourceBadge(cluster.rows.first().sourceId)
                Icon(Icons.Outlined.ChevronRight, null, tint = CommunityMuted, modifier = Modifier.size(15.dp))
            }
        } else {
            val shown = if (showSimilar) cluster.rows else cluster.rows.take(1)
            shown.forEach { row -> key(row.stableKey()) {
                sources[row.sourceId]?.takeIf { it.subjectId == row.subjectId }?.let { source ->
                    CommunityDiscussionComment(source, row.review, loadReplies, comfortable = true)
                }
            } }
            Row(Modifier.padding(start = 38.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (cluster.rows.size > 1) Text(
                    if (showSimilar) "收起相似内容" else "相似内容 ${cluster.rows.size} 条",
                    color = CommunityMuted, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.clickable(role = Role.Button) { showSimilar = !showSimilar }.padding(vertical = 7.dp))
                if (hidePromotions && cluster.promotion) Text("折叠推广", color = CommunityMuted,
                    fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.clickable(role = Role.Button) { revealPromotion = false }.padding(vertical = 7.dp))
            }
        }
    }
}

/** Failed retrieval stays distinct from an empty discussion, with details disclosed on demand. */
@Composable
private fun CommentUnavailableNotice(sources: List<ReviewSource>, allUnavailable: Boolean,
    onRetry: (String) -> Unit) {
    var expanded by rememberSaveable(sources.map { it.id }) { mutableStateOf(false) }
    val motion = LocalMotionEnabled.current
    val busy = sources.any { it.loadingMore }
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = if (expanded) "收起失败原因" else "查看失败原因",
                    onClick = { expanded = !expanded })
                .motionPress(interaction, pressedScale = .99f)
                .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(15.dp), tint = CommunityMuted)
                Text(if (allUnavailable) "评论暂时无法读取" else "部分评论来源暂不可用",
                    color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    null, Modifier.size(15.dp), tint = CommunityMuted)
            }
            MotionIconButton(onClick = { sources.forEach { onRetry(it.id) } }, enabled = !busy) {
                Icon(Icons.Outlined.Refresh, "重试${sources.size}个不可用评论来源",
                    Modifier.size(18.dp), tint = CommunityMuted)
            }
        }
        AnimatedVisibility(expanded,
            enter = expandVertically(tween(if (motion) MangaMotion.Content else 0)) +
                fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
            exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0)) +
                fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
            Column(Modifier.fillMaxWidth()) {
                sources.forEach { source -> key(source.id) { CommentLoadNotice(source, onRetry) } }
            }
        }
    }
}

/** Source-specific recovery is disclosed inside the existing comment filter. */
@Composable
internal fun CommentLoadNotice(source: ReviewSource, onRetry: (String) -> Unit) {
    val content = if (source.hasWorkReviews) "评论" else "条目资料"
    val message = source.reviewsError?.takeIf(String::isNotBlank)
        ?: source.message.takeIf { source.status == ReviewStatus.UNAVAILABLE && it.isNotBlank() }
        ?: "${content}暂时无法读取，请稍后重试。"
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
                Text(if (source.loadingMore) "正在重新读取$content" else "${content}暂时无法读取",
                    color = CommunityMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                MotionSwap(expanded, Modifier.size(16.dp)) { open ->
                    Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        null, tint = CommunityMuted, modifier = Modifier.size(16.dp))
                }
            }
            MotionIconButton(onClick = { onRetry(source.id) }, enabled = !source.loadingMore) {
                Icon(Icons.Outlined.Refresh, "重新读取$content", tint = CommunityMuted,
                    modifier = Modifier.size(18.dp))
            }
        }
        if (expanded) Text(message, color = CommunityMuted, fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 10.dp))
    }
}

/** Only an explicit choice can add reviews for an uncertain title match. */
@Composable
internal fun CommentMatchChoice(source: ReviewSource, onMatch: (String, String) -> Unit) {
    val content = if (source.hasWorkReviews) "评论" else "条目资料"
    var expanded by rememberSaveable(source.id, source.candidates) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(
        animationSpec = if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(),
    )) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
            .clickable(enabled = !source.loadingMore, role = Role.Button,
                onClickLabel = if (expanded) "收起漫画选择" else "选择对应漫画以查看$content") { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CommentSourceBadge(source.id)
            Text(if (source.loadingMore) "正在读取$content" else "选择对应漫画以查看$content",
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
                        onClickLabel = "选择${candidate.title}") {
                        expanded = false
                        onMatch(source.id, candidate.id)
                    }.padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(candidate.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), color = CommunityWhite, fontSize = 12.sp,
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
internal fun CommentSourceBadge(sourceId: String) {
    val onLight = MaterialTheme.colorScheme.background.luminance() > .5f
    val accent = reviewSourceAccent(sourceId, !onLight)
    val name = when (sourceId) {
        "copy" -> "拷贝"; "bangumi" -> "bgm"; "anilist" -> "AniList"
        else -> dev.mangaflow.data.ReviewProviders.find(sourceId)?.mark ?: providerName(sourceId)
    }
    Text(name, modifier = Modifier.clip(RoundedCornerShape(5.dp))
        .background(accent.copy(alpha = .12f)).padding(horizontal = 4.dp, vertical = 1.dp)
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
    foreground: Color = MaterialTheme.colorScheme.onSurface,
    muted: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    sourceId: String? = null,
) {
    CommentContent(review, modifier, foreground, muted, showReplyCount = true,
        sourceId = sourceId)
}

private enum class CommentPresentation { HIDDEN, PREVIEW, EXPANDED }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentContent(
    review: MangaReview,
    modifier: Modifier,
    foreground: Color,
    muted: Color,
    showReplyCount: Boolean,
    sourceId: String? = null,
    comfortable: Boolean = false,
    compactReply: Boolean = false,
) {
    val text = review.body.ifBlank { review.summary }.trim()
    val reviewHeading = review.summary.trim().takeIf {
        review.kind == ReviewKind.REVIEW && review.body.isNotBlank() && it.isNotBlank() && it != text
    }
    val translationText = remember(reviewHeading, text) {
        reviewHeading?.let { "$it\n\n$text" } ?: text
    }
    val conceal = review.spoiler || (!comfortable && review.kind == ReviewKind.REVIEW)
    // A long review has one visibility owner: revealing it also expands the full body.
    var presentation by rememberSaveable(sourceId, review.id, review.url, conceal) {
        mutableStateOf(if (conceal) CommentPresentation.HIDDEN else CommentPresentation.PREVIEW)
    }
    val revealed = presentation != CommentPresentation.HIDDEN
    val expanded = presentation == CommentPresentation.EXPANDED
    var overflows by remember(text) { mutableStateOf(false) }
    val expandable = revealed && (conceal || overflows || expanded)
    val toggleContent = {
        presentation = when (presentation) {
            CommentPresentation.HIDDEN, CommentPresentation.PREVIEW -> CommentPresentation.EXPANDED
            CommentPresentation.EXPANDED -> if (conceal) CommentPresentation.HIDDEN else CommentPresentation.PREVIEW
        }
    }
    val hasForeignText = remember(translationText) { hasForeignCommentText(translationText) }
    val canTranslate = revealed && hasForeignText
    val contentInteraction = remember(review.id, sourceId) { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().animateContentSize(
        animationSpec = if (LocalMotionEnabled.current) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap())
        .then(if (expandable) Modifier.clickable(interactionSource = contentInteraction, indication = null,
            role = Role.Button, onClickLabel = if (expanded) "收起评论" else "展开评论", onClick = toggleContent)
            .motionPress(contentInteraction, pressedScale = .99f) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CommentAvatar(review, foreground, size = if (compactReply) 24.dp else if (comfortable) 30.dp else 28.dp)
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 18.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(review.author, color = foreground.copy(alpha = .87f), fontSize = if (comfortable) 14.sp else 12.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                if (sourceId != null) CommentSourceBadge(sourceId)
                if (review.date.isNotBlank()) Text(compactCommentDate(review.date), color = muted, fontSize = if (comfortable) 11.sp else 10.sp,
                    modifier = Modifier.widthIn(max = 76.dp).semantics { contentDescription = review.date },
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (canTranslate) CommentTranslationAction(translationText, muted)
                if (expandable) Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    null, tint = muted, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.height(2.dp))
            if (review.replyTo.isNotBlank()) {
                Text("回复 ${review.replyTo}", color = muted, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 3.dp))
            }
            if (!revealed) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(9.dp))
                    .background(foreground.copy(alpha = .045f)).clickable(role = Role.Button,
                        onClickLabel = "展开完整评论", onClick = toggleContent)
                    .padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VisibilityOff, null, tint = muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (review.kind == ReviewKind.REVIEW) "展开长评 · 可能涉及后续剧情" else "含剧透，点按查看",
                        color = muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null, tint = muted, modifier = Modifier.size(15.dp))
                }
            } else {
                if (reviewHeading != null) {
                    Text(reviewHeading, fontFamily = mangaFontFamily(MangaFontScene.READING), color = foreground,
                        fontSize = if (comfortable) 16.sp else 14.sp, lineHeight = if (comfortable) 24.sp else 22.sp,
                        fontWeight = FontWeight.Medium, maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text(text, fontFamily = mangaFontFamily(MangaFontScene.READING), color = foreground.copy(alpha = .92f),
                    fontSize = if (comfortable) 16.sp else 14.sp, lineHeight = if (comfortable) 26.sp else 22.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else if (comfortable) {
                        if (review.kind == ReviewKind.REVIEW) 5 else 4
                    } else 3, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
            }
            val hasFeedback = (review.upVotes ?: 0) > 0 || (review.downVotes ?: 0) > 0 ||
                (revealed && conceal) ||
                (showReplyCount && (review.replyCount ?: 0) > 0)
            if (hasFeedback) Row(Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.Bottom) {
                if (hasFeedback) FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (conceal && revealed) TextButton(onClick = toggleContent,
                        contentPadding = PaddingValues(end = 12.dp)) {
                        Text(if (review.kind == ReviewKind.REVIEW) "收起长评" else "隐藏剧透", color = muted, fontSize = 11.sp)
                    }
                    review.upVotes?.takeIf { it > 0 }?.let {
                        Text("${review.positiveVoteLabel} $it", color = muted, fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp))
                    }
                    review.downVotes?.takeIf { it > 0 }?.let {
                        Text("踩 $it", color = muted, fontSize = 10.sp,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp))
                    }
                    if (showReplyCount && (review.replyCount ?: 0) > 0) {
                        Text("${review.replyCount} 条回复", color = muted, fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.CenterVertically).padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

/** Ovio's saved Avatar pattern: initials remain underneath a lazy image, including on failure. */
@Composable
internal fun CommentAvatar(review: MangaReview, foreground: Color, size: Dp = 28.dp) {
    val context = LocalContext.current
    var failed by remember(review.avatarUrl) { mutableStateOf(false) }
    val initial = remember(review.author) {
        review.author.trim().takeIf { it.isNotEmpty() }
            ?.let { it.take(if (it.length > 1 && it[0].isHighSurrogate() && it[1].isLowSurrogate()) 2 else 1).uppercase() } ?: "·"
    }
    val request = remember(review.avatarUrl, context) {
        ImageRequest.Builder(imagePlatformContext(context)).data(review.avatarUrl).size(88).crossfade(100).build()
    }
    Box(Modifier.size(size).clip(CircleShape).background(foreground.copy(alpha = .075f))
        .clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(initial, color = foreground.copy(alpha = .55f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        if (review.avatarUrl.isNotBlank() && !failed) {
            AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(), onError = { failed = true })
        }
    }
}

@Composable
internal fun CommunityDiscussionComment(
    source: ReviewSource,
    review: MangaReview,
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage,
    foreground: Color = CommunityWhite,
    muted: Color = CommunityMuted,
    comfortable: Boolean = false,
) {
    val hasReplies = ((source.id == "copy" && review.kind == ReviewKind.COMMENT) || source.supportsReplies) &&
        source.subjectId.isNotBlank() && (review.replyCount ?: if (review.canLoadReplies) 1 else 0) > 0
    Column(Modifier.fillMaxWidth().padding(vertical = if (comfortable) 7.dp else 6.dp)) {
        CommentContent(review, Modifier, foreground, muted,
            showReplyCount = !hasReplies, sourceId = source.id, comfortable = comfortable)
        if (hasReplies) {
            key(source.id, source.subjectId, review.id, source.externalIds) {
                CommentReplies(source, review, loadReplies, foreground, muted,
                    leadingInset = if (comfortable) 38.dp else 36.dp)
            }
        }
    }
}

@Composable
private fun CommentReplies(
    source: ReviewSource,
    review: MangaReview,
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage,
    foreground: Color,
    muted: Color,
    leadingInset: Dp,
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
                    error = result.error
                    if (result.error != null) failedPage = page
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

    val motion = LocalMotionEnabled.current
    val chevron = animateFloatAsState(if (expanded) 180f else 0f,
        tween(if (motion) MangaMotion.Quick else 0), label = "comment-replies-chevron")
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.padding(start = leadingInset, top = 3.dp)
        .clip(RoundedCornerShape(6.dp))
        .semantics { stateDescription = if (expanded) "回复已展开" else "回复已收起" }
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = {
            expanded = !expanded
            if (expanded && !loaded) load(0)
            else if (!expanded) {
                requestVersion++
                request?.cancel()
                busy = false
            }
        }).motionPress(interaction, pressedScale = .98f)
        .heightIn(min = 28.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (expanded) "收起回复" else review.replyCount?.let { "$it 条回复" } ?: "查看回复",
            color = muted, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Outlined.KeyboardArrowDown,
            null, tint = muted, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = chevron.value })
    }
    AnimatedVisibility(expanded,
        enter = expandVertically(tween(if (motion) MangaMotion.Content else 0), expandFrom = Alignment.Top) +
            fadeIn(tween(if (motion) MangaMotion.Quick else 0)),
        exit = shrinkVertically(tween(if (motion) MangaMotion.Content else 0), shrinkTowards = Alignment.Top) +
            fadeOut(tween(if (motion) MangaMotion.Quick else 0))) {
        Column(Modifier.fillMaxWidth().motionInputGate(expanded)
            .padding(start = leadingInset, top = 3.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { reply ->
                key(source.subjectId, review.id, reply.id) {
                    CommentContent(reply, Modifier, foreground, muted, showReplyCount = true,
                        sourceId = source.id, compactReply = true)
                }
            }
            if (error != null) CommentFailure(error.orEmpty(), busy) { load(failedPage) }
            if (busy) SmallLoading()
            else if (loaded && rows.isEmpty() && error == null) {
                Text("暂无可展示的回复", color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (!busy && error == null && nextPage != null) TextButton(onClick = { nextPage?.let(::load) },
                contentPadding = PaddingValues(end = 10.dp)) {
                Text("更多回复", color = muted, fontSize = 12.sp)
            }
        }
    }
}

private fun providerName(id: String): String = when (id) {
    "copy" -> "拷贝"
    "bangumi" -> "Bangumi"
    "anilist" -> "AniList"
    "dantotsu" -> "Dantotsu"
    else -> dev.mangaflow.data.ReviewProviders.find(id)?.name ?: id
}

private fun compactCommentDate(date: String): String {
    val value = date.trim()
    return if (value.length >= 10 && value[4] == '-' && value[7] == '-' && value.take(4).all(Char::isDigit)) {
        if (value.take(4) == localToday().year.toString()) value.substring(5, 10) else value.take(10)
    } else value
}
