package dev.mangaflow.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ReadingProgress

internal val DetailInk = Color(0xFF121213)
internal val DetailSurface = Color(0xFF202022)
internal val DetailWhite = Color(0xFFF3F3F1)
internal val DetailSecondary = Color(0xFFB4B4B8)
internal val DetailMuted = Color(0xFF85858B)
internal val DetailLine = Color.White.copy(alpha = 0.075f)

@Composable
fun DetailScreen(
    manga: Manga,
    detail: MangaDetail?,
    loading: Boolean,
    error: String?,
    reviews: List<ReviewSource>,
    reviewsLoading: Boolean,
    isFavorite: Boolean,
    progress: ReadingProgress?,
    downloadEntries: List<DownloadEntry>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRead: (Chapter) -> Unit,
    onContinue: (ReadingProgress) -> Unit,
    onDownload: (Chapter) -> Unit,
    onReviewMore: (String) -> Unit = {},
    onReviewRetry: (String) -> Unit = {},
    onReviewMatch: (String, String) -> Unit = { _, _ -> },
    onReviewRelink: (String) -> Unit = {},
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage = { _, _, _ -> ReviewPage(emptyList()) },
) {
    val current = detail?.manga ?: manga
    val downloaded = remember(downloadEntries, manga.id) {
        downloadEntries.filter { it.manga.id == manga.id }.associateBy { it.chapter.id }
    }
    val chapters = remember(detail, downloaded) {
        detail?.chapters ?: downloaded.values.filter { it.status == DownloadStatus.COMPLETED }
            .map(DownloadEntry::chapter).distinctBy(Chapter::id).sortedBy(Chapter::index)
    }
    val resume = progress?.takeIf { it.manga.id == manga.id && it.chapterId.isNotBlank() }
    var section by rememberSaveable(manga.id) { mutableIntStateOf(0) }
    val sections = rememberSaveableStateHolder()

    BoxWithConstraints(
        Modifier.fillMaxSize().background(DetailInk)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        // The book header yields room on short screens, with the keyboard, or at large font sizes.
        val compactHeader = maxHeight < 620.dp || LocalDensity.current.fontScale >= 1.4f
        val showHeaderMetrics = maxHeight >= 400.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DetailAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = onBack)
                Text(
                    if (compactHeader) current.title else "拷贝漫画",
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    color = if (compactHeader) DetailWhite else DetailMuted,
                    fontSize = if (compactHeader) 17.sp else 12.sp,
                    fontWeight = if (compactHeader) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                DetailAction(
                    if (isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    if (isFavorite) "从书架移除" else "加入书架", selected = isFavorite,
                    onClick = onToggleFavorite,
                )
            }
            if (!compactHeader) CompactBookHeader(current)
            if (showHeaderMetrics) {
                MangaMetricBadges(current, Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    foreground = DetailWhite)
            }
            DetailSections(
                selected = section,
                labels = listOf(if (chapters.isEmpty()) "章节" else "章节 " + chapters.size, "简介", "评论"),
                onSelect = { section = it },
            )
            Crossfade(
                targetState = section,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                animationSpec = tween(120), label = "detail-section",
            ) { selected ->
                sections.SaveableStateProvider(selected) {
                    when (selected) {
                        0 -> DetailCatalog(
                            mangaId = current.id, chapters = chapters, downloaded = downloaded,
                            progress = resume, loading = loading, error = error,
                            onRetry = onRetry, onRead = onRead, onDownload = onDownload,
                        )
                        1 -> DetailIntroduction(current, loading)
                        else -> CommunityPanel(
                            mangaId = current.id, sources = reviews, loading = reviewsLoading,
                            onMore = onReviewMore, onRetry = onReviewRetry, onMatch = onReviewMatch,
                            onRelink = onReviewRelink, loadReplies = loadReplies,
                        )
                    }
                }
            }
            DetailReadAction(
                resume = resume, firstChapter = chapters.firstOrNull(), loading = loading,
                onContinue = onContinue, onRead = onRead,
            )
        }
    }
}

/**
 * Adapted from Mihon's MangaAndSourceTitlesSmall (524fa530), Apache-2.0.
 * Keeps its horizontal cover/info grouping, with bounded metadata and MangaFlow's own actions.
 * See THIRD_PARTY_NOTICES.md.
 */
@Composable
private fun CompactBookHeader(manga: Manga) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 2.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailCover(manga)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(manga.title, color = DetailWhite, fontSize = 22.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (manga.authors.isNotEmpty()) Text(manga.authors.joinToString(" / "),
                color = DetailSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (manga.status.isNotBlank()) Text(manga.status, color = DetailSecondary, fontSize = 11.sp)
            if (manga.tags.isNotEmpty()) Text(manga.tags.take(4).joinToString(" · "),
                color = DetailMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DetailCover(manga: Manga) {
    val context = LocalContext.current
    var failed by remember(manga.coverUrl) { mutableStateOf(false) }
    var retry by remember(manga.coverUrl) { mutableIntStateOf(0) }
    val request = remember(manga.coverUrl, manga.id, retry) {
        ImageRequest.Builder(context).data(manga.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().set("User-Agent", "Mozilla/5.0")
                .set("Referer", manga.sourceUrl.ifBlank { "https://www.copy3000.com/comic/" + manga.id }).build())
            .crossfade(160).memoryCacheKey("detail-cover:" + manga.coverUrl + ":" + retry).build()
    }
    Box(Modifier.size(width = 84.dp, height = 112.dp).clip(RoundedCornerShape(10.dp)).background(DetailSurface),
        contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.MenuBook, null, tint = DetailMuted.copy(alpha = .3f), modifier = Modifier.size(28.dp))
        if (manga.coverUrl.isNotBlank()) {
            AsyncImage(request, manga.title + "封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onError = { failed = true }, onSuccess = { failed = false })
        }
        if (failed) IconButton(onClick = { failed = false; retry++ }) {
            Icon(Icons.Outlined.Refresh, "重新加载封面", tint = DetailWhite)
        }
    }
}

/** Compose adaptation of Ovio Liquid Tabs minimal's independently sprung indicator edges. */
@Composable
private fun DetailSections(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    var movingRight by remember { mutableStateOf(true) }
    val left = animateFloatAsState(selected.toFloat(),
        spring(dampingRatio = 1f, stiffness = if (movingRight) 230f else 520f), label = "section-left")
    val right = animateFloatAsState(selected + 1f,
        spring(dampingRatio = 1f, stiffness = if (movingRight) 520f else 230f), label = "section-right")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp)
            .height(48.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .035f))
            .drawBehind {
                val slot = size.width / labels.size
                val inset = 4.dp.toPx()
                val l = left.value * slot + inset
                val r = right.value * slot - inset
                val stretch = ((r - l) / (slot - 2 * inset) - 1f).coerceAtLeast(0f)
                val squash = (1f - .06f * stretch).coerceAtLeast(.8f)
                val h = (size.height - 2 * inset) * squash
                drawRoundRect(
                    color = Color.White.copy(alpha = .12f),
                    topLeft = Offset(l, (size.height - h) / 2),
                    size = Size((r - l).coerceAtLeast(h), h),
                    cornerRadius = CornerRadius(h / 2),
                )
            }.selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            Box(
                Modifier.weight(1f).height(48.dp).selectable(
                    selected = index == selected, role = Role.Tab,
                    onClick = { movingRight = index.toFloat() > left.value; onSelect(index) },
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (index == selected) DetailWhite else DetailMuted, fontSize = 13.sp,
                    fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun DetailIntroduction(manga: Manga, loading: Boolean) {
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(manga.title, color = DetailWhite, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 27.sp)
            if (manga.authors.isNotEmpty()) Text(manga.authors.joinToString(" / "),
                color = DetailSecondary, fontSize = 13.sp, lineHeight = 21.sp)
            val metadata = (listOf(manga.status) + manga.tags).filter(String::isNotBlank).joinToString(" · ")
            if (metadata.isNotBlank()) Text(metadata, color = DetailMuted, fontSize = 12.sp, lineHeight = 20.sp)
            if (manga.latestChapter.isNotBlank()) Text("更新至 " + manga.latestChapter,
                color = DetailSecondary, fontSize = 12.sp, lineHeight = 20.sp)
            Text(manga.description.ifBlank { if (loading) "正在读取简介" else "来源暂无简介" },
                color = DetailSecondary, fontSize = 14.sp, lineHeight = 24.sp)
            Text("内容来源 · 拷贝漫画", color = DetailMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun DetailReadAction(
    resume: ReadingProgress?,
    firstChapter: Chapter?,
    loading: Boolean,
    onContinue: (ReadingProgress) -> Unit,
    onRead: (Chapter) -> Unit,
) {
    val canRead = resume != null || firstChapter != null
    Button(
        onClick = { if (resume != null) onContinue(resume) else firstChapter?.let(onRead) },
        enabled = canRead,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DetailWhite, contentColor = DetailInk,
            disabledContainerColor = DetailSurface, disabledContentColor = DetailMuted,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (loading && !canRead) CircularProgressIndicator(Modifier.size(18.dp), color = DetailMuted, strokeWidth = 1.5.dp)
        else Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(if (resume != null) "继续阅读" else if (loading && !canRead) "加载章节" else "开始阅读",
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (resume != null) Text(
            " · " + resume.chapterTitle + " · 第 " + (resume.pageIndex + 1) + " 页",
            modifier = Modifier.weight(1f, fill = false).padding(start = 4.dp),
            color = DetailInk.copy(alpha = .6f), fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun DetailAction(
    icon: ImageVector,
    description: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) .94f else 1f,
        spring(dampingRatio = .8f, stiffness = 550f), label = "detail-action")
    Box(
        Modifier.size(48.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White.copy(alpha = .10f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (!enabled) DetailMuted.copy(alpha = .3f) else if (selected) DetailWhite else DetailSecondary,
            modifier = Modifier.size(21.dp))
    }
}
