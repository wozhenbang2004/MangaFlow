package dev.mangaflow.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
            CompactBookHeader(current)
            if (showHeaderMetrics) {
                MangaMetricBadges(current, Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    foreground = DetailWhite)
            }
            DetailSections(
                selected = section,
                labels = listOf(if (chapters.isEmpty()) "章节" else "章节 " + chapters.size, "评论"),
                onSelect = { section = it },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                sections.SaveableStateProvider(section) {
                    when (section) {
                        0 -> DetailCatalog(
                            mangaId = current.id, chapters = chapters, downloaded = downloaded,
                            progress = resume, loading = loading, error = error,
                            onRetry = onRetry, onRead = onRead, onDownload = onDownload,
                        )
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
 * Artwork, synopsis and native metadata share one compact header. Full synopsis appears
 * in a sheet instead of taking the chapter catalog's space or becoming a separate tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactBookHeader(manga: Manga) {
    var fullIntroduction by remember(manga.id) { mutableStateOf(false) }
    val synopsis = manga.description.ifBlank { "来源暂无简介" }
    val tags = remember(manga.status, manga.tags, manga.latestChapter) {
        buildList {
            if (manga.status.isNotBlank()) add(manga.status.trim())
            addAll(manga.tags.filter(String::isNotBlank).distinct().take(12))
            if (manga.latestChapter.isNotBlank()) add("更新 · " + manga.latestChapter)
        }.distinct()
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            DetailCover(manga)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    manga.title, color = DetailWhite, fontSize = 19.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (manga.authors.isNotEmpty()) Text(
                    manga.authors.joinToString(" / "), color = DetailSecondary, fontSize = 11.sp,
                    lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "简介 · " + synopsis,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = manga.description.isNotBlank(),
                            onClickLabel = "查看完整简介") { fullIntroduction = true },
                    color = DetailSecondary, fontSize = 11.sp, lineHeight = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (tags.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tags.forEachIndexed { index, tag -> DetailTag(tag, index) }
            }
        }
    }
    if (fullIntroduction) {
        ModalBottomSheet(
            onDismissRequest = { fullIntroduction = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DetailSurface, contentColor = DetailWhite,
        ) {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp).padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(manga.title, fontSize = 17.sp, lineHeight = 24.sp,
                        color = DetailWhite, fontWeight = FontWeight.SemiBold)
                    Text(synopsis, fontSize = 14.sp, lineHeight = 23.sp, color = DetailSecondary)
                    Text("内容来源 · 拷贝漫画", fontSize = 11.sp, color = DetailMuted)
                }
            }
        }
    }
}

@Composable
private fun DetailTag(label: String, index: Int) {
    val accent = when (index % 4) {
        0 -> Color(0xFFD9B49E)  // muted apricot
        1 -> Color(0xFFAFC4E2)  // soft blue
        2 -> Color(0xFFC7B0DC)  // dusty lilac
        else -> Color(0xFFD5AFBB) // muted rose
    }
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = .12f))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        color = accent, fontSize = 10.sp, lineHeight = 14.sp,
        maxLines = 1,
    )
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
    Box(Modifier.size(width = 78.dp, height = 104.dp).clip(RoundedCornerShape(10.dp)).background(DetailSurface),
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

/** Quiet text tabs: the synopsis belongs to the header, not another route. */
@Composable
private fun DetailSections(selected: Int, labels: List<String>, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 2.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            Column(
                Modifier.height(38.dp).selectable(
                    selected = selected == index, role = Role.Tab, onClick = { onSelect(index) },
                ).padding(horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        label, color = if (selected == index) DetailWhite else DetailMuted,
                        fontSize = 13.sp,
                        fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Box(
                    Modifier.width(if (selected == index) 32.dp else 32.dp).height(2.dp)
                        .background(if (selected == index) DetailWhite else Color.Transparent,
                            RoundedCornerShape(2.dp)),
                )
            }
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
    val label = when {
        resume != null -> "继续阅读 · " + resume.chapterTitle
        firstChapter != null -> "开始阅读 · " + firstChapter.title
        loading -> "加载章节"
        else -> "暂无可读章节"
    }
    Button(
        onClick = { if (resume != null) onContinue(resume) else firstChapter?.let(onRead) },
        enabled = canRead,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DetailWhite, contentColor = DetailInk,
            disabledContainerColor = DetailSurface, disabledContentColor = DetailMuted,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (loading && !canRead) CircularProgressIndicator(
            Modifier.size(18.dp), color = DetailMuted, strokeWidth = 1.5.dp)
        else Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (resume != null) Text(
            "第 " + (resume.pageIndex + 1) + " 页",
            color = DetailInk.copy(alpha = .65f), fontSize = 11.sp,
            maxLines = 1,
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
