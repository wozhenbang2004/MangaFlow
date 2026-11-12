package dev.mangaflow.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val DetailBackground: Color @Composable get() = MaterialTheme.colorScheme.background
internal val DetailSurface: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant
internal val DetailForeground: Color @Composable get() = MaterialTheme.colorScheme.onSurface
internal val DetailSecondary: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
internal val DetailMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .85f)
internal val DetailLine: Color @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)

@OptIn(ExperimentalMaterial3Api::class)
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
    val activeSection = section.coerceIn(0, 1)

    var fullIntroduction by rememberSaveable(manga.id) { mutableStateOf(false) }
    var coverFailed by remember(current.coverUrl) { mutableStateOf(false) }
    var coverRetry by remember(current.coverUrl) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val coverRequest = remember(context, current.coverUrl, current.sourceUrl, current.id, coverRetry) {
        ImageRequest.Builder(context).data(current.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().set("User-Agent", "Mozilla/5.0")
                .set("Referer", current.sourceUrl.ifBlank { "https://www.copy3000.com/comic/" + current.id }).build())
            .crossfade(160).memoryCacheKey("detail-cover:" + current.coverUrl + ":" + coverRetry).build()
    }
    val scope = rememberCoroutineScope()
    var contentsJump by remember { mutableStateOf<Job?>(null) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(DetailBackground)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
    ) {
        val density = LocalDensity.current
        val coverContentScale = if (maxWidth > maxHeight) ContentScale.Fit else ContentScale.Crop
        val compactHero = maxHeight < 600.dp || density.fontScale >= 1.5f
        val heroHeight = if (compactHero) {
            (maxHeight * .32f).coerceAtMost((maxHeight - 250.dp).coerceAtLeast(0.dp))
        } else {
            (maxHeight * .60f).coerceAtMost(560.dp).coerceAtMost(maxHeight - 280.dp)
        }
        val heroPixels = with(density) { heroHeight.toPx() }
        val header = rememberTopAppBarState(initialHeightOffsetLimit = -heroPixels)
        val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = header, snapAnimationSpec = null, flingAnimationSpec = null,
        )
        SideEffect {
            if (header.heightOffsetLimit != -heroPixels) {
                val collapsed = header.collapsedFraction
                contentsJump?.cancel()
                header.heightOffsetLimit = -heroPixels
                header.heightOffset = -heroPixels * collapsed
            }
        }
        val scrolling = remember(behavior) {
            object : NestedScrollConnection by behavior.nestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) contentsJump?.cancel()
                    return behavior.nestedScrollConnection.onPreScroll(available, source)
                }
            }
        }
        val openContents: () -> Unit = {
            section = 0
            contentsJump?.cancel()
            contentsJump = scope.launch {
                animate(header.heightOffset, header.heightOffsetLimit, animationSpec = tween(360)) { value, _ ->
                    header.heightOffset = value
                }
            }
        }
        val showToolbarTitle by remember(header, compactHero) {
            derivedStateOf { compactHero || header.collapsedFraction > .8f }
        }

        // Sakurairo's fixed-background relationship: only the foreground moves.
        // Keep this image's bounds independent of heightOffset to avoid recropping each frame.
        Box(
            Modifier.fillMaxWidth().height(heroHeight + 48.dp).background(DetailSurface),
            contentAlignment = Alignment.TopCenter,
        ) {
            Icon(Icons.Outlined.MenuBook, null, tint = DetailMuted.copy(alpha = .3f),
                modifier = Modifier.align(Alignment.Center).size(48.dp))
            if (current.coverUrl.isNotBlank()) AsyncImage(
                model = coverRequest, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = coverContentScale,
                alignment = Alignment.TopCenter,
                onError = { coverFailed = true }, onSuccess = { coverFailed = false },
            )
        }
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().height(48.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = .94f))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DetailAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", onClick = onBack)
                Text(
                    if (showToolbarTitle) current.title else "拷贝漫画",
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = "漫画信息与完整简介") {
                            fullIntroduction = true
                        }.padding(horizontal = 8.dp, vertical = 10.dp),
                    color = DetailForeground, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (coverFailed) DetailAction(Icons.Outlined.Refresh, "重新加载封面", onClick = {
                    coverFailed = false
                    coverRetry++
                })
                DetailAction(
                    if (isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    if (isFavorite) "从书架移除" else "加入书架", selected = isFavorite,
                    onClick = onToggleFavorite,
                )
            }
            Box(
                Modifier.fillMaxWidth().clipToBounds().nestedScroll(scrolling)
                    .scrollable(rememberScrollableState { 0f }, Orientation.Vertical)
                    .layout { measurable, constraints ->
                        // Read the moving offset during layout, without recomposing the whole detail.
                        val expanded = heroPixels.roundToInt().coerceAtMost(constraints.maxHeight)
                        val foreground = measurable.measure(
                            constraints.copy(minHeight = expanded, maxHeight = expanded),
                        )
                        val offset = header.heightOffset.roundToInt().coerceIn(-expanded, 0)
                        layout(foreground.width, expanded + offset) {
                            foreground.placeRelative(0, offset)
                        }
                    },
            ) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!compactHero) {
                        HeroBookInformation(current, onIntroduction = { fullIntroduction = true },
                            onContents = openContents)
                    } else {
                        IconButton(
                            onClick = openContents,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = .94f)),
                        ) { Icon(Icons.Outlined.KeyboardArrowDown, "滑到章节", tint = DetailForeground) }
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(DetailBackground).padding(top = 8.dp),
            ) {
                DetailSections(
                    selected = activeSection,
                    labels = listOf(if (chapters.isEmpty()) "章节" else "章节 " + chapters.size, "评论"),
                    onSelect = { section = it },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    sections.SaveableStateProvider(activeSection) {
                        when (activeSection) {
                            0 -> DetailCatalog(
                                mangaId = current.id, chapters = chapters, downloaded = downloaded,
                                progress = resume, loading = loading, error = error, scrolling = scrolling,
                                onRetry = onRetry, onRead = onRead, onDownload = onDownload,
                            )
                            else -> CommunityPanel(
                                mangaId = current.id, sources = reviews, loading = reviewsLoading,
                                onMore = onReviewMore, onRetry = onReviewRetry, onMatch = onReviewMatch,
                                onRelink = onReviewRelink, loadReplies = loadReplies,
                                modifier = Modifier.nestedScroll(scrolling),
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
    if (fullIntroduction) DetailIntroduction(current) { fullIntroduction = false }
}

@Composable
private fun HeroBookInformation(manga: Manga, onIntroduction: () -> Unit, onContents: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .94f))
            .padding(horizontal = 14.dp).padding(top = 13.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            manga.title, color = DetailForeground, fontSize = 22.sp, lineHeight = 28.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (manga.authors.isNotEmpty()) Text(
            manga.authors.joinToString(" / "), color = DetailSecondary, fontSize = 11.sp,
            lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            "简介 · " + manga.description.ifBlank { "来源暂无简介" },
            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .clickable(role = Role.Button, onClickLabel = "查看完整简介", onClick = onIntroduction),
            color = DetailSecondary, fontSize = 12.sp, lineHeight = 18.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        DetailTags(manga)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MangaMetricBadges(manga, Modifier.weight(1f), foreground = DetailForeground)
            IconButton(onClick = onContents, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.KeyboardArrowDown, "滑到章节", tint = DetailForeground)
            }
        }
    }
}

@Composable
private fun DetailTags(manga: Manga) {
    val tags = remember(manga.status, manga.tags, manga.latestChapter) {
        buildList {
            if (manga.status.isNotBlank()) add(manga.status.trim())
            addAll(manga.tags.filter(String::isNotBlank).distinct().take(12))
            if (manga.latestChapter.isNotBlank()) add("更新 · " + manga.latestChapter)
        }.distinct()
    }
    if (tags.isNotEmpty()) Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { tags.forEachIndexed { index, tag -> DetailTag(tag, index) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailIntroduction(manga: Manga, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface, contentColor = DetailForeground,
    ) {
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(manga.title, fontSize = 20.sp, lineHeight = 28.sp,
                    color = DetailForeground, fontWeight = FontWeight.SemiBold)
                if (manga.authors.isNotEmpty()) Text(manga.authors.joinToString(" / "),
                    color = DetailSecondary, fontSize = 13.sp)
                DetailTags(manga)
                MangaMetricBadges(manga, Modifier.fillMaxWidth(), foreground = DetailForeground)
                Text(manga.description.ifBlank { "来源暂无简介" },
                    fontSize = 14.sp, lineHeight = 23.sp, color = DetailSecondary)
                Text("内容来源 · 拷贝漫画", fontSize = 11.sp, color = DetailMuted)
            }
        }
    }
}

@Composable
private fun DetailTag(label: String, index: Int) {
    val onDark = DetailForeground.luminance() > .5f
    val accent = when (index % 4) {
        0 -> if (onDark) Color(0xFFD9B49E) else Color(0xFF805C43)
        1 -> if (onDark) Color(0xFFAFC4E2) else Color(0xFF486481)
        2 -> if (onDark) Color(0xFFC7B0DC) else Color(0xFF75588A)
        else -> if (onDark) Color(0xFFD5AFBB) else Color(0xFF865B6B)
    }
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = if (onDark) .12f else .085f))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        color = accent, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1,
    )
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
                        label, color = if (selected == index) DetailForeground else DetailMuted,
                        fontSize = 13.sp,
                        fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Box(
                    Modifier.width(32.dp).height(2.dp)
                        .background(if (selected == index) DetailForeground else Color.Transparent,
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
            containerColor = DetailForeground, contentColor = DetailBackground,
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
            color = DetailBackground.copy(alpha = .65f), fontSize = 11.sp,
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
            .background(if (selected) DetailForeground.copy(alpha = .10f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (!enabled) DetailMuted.copy(alpha = .3f) else if (selected) DetailForeground else DetailSecondary,
            modifier = Modifier.size(21.dp))
    }
}
