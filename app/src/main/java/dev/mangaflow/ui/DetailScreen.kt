package dev.mangaflow.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
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
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy
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
    active: Boolean = true,
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
    val motion = LocalMotionEnabled.current
    val coverRequest = remember(context, current.coverUrl, current.sourceUrl, current.id, coverRetry, motion) {
        ImageRequest.Builder(context).data(current.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().set("User-Agent", "Mozilla/5.0")
                .set("Referer", current.sourceUrl.ifBlank { "https://www.copy3000.com/comic/" + current.id }).build())
            .crossfade(if (motion) MangaMotion.Quick else 0)
            .memoryCacheKey("detail-cover:" + current.coverUrl + ":" + coverRetry).build()
    }
    val scope = rememberCoroutineScope()
    var contentsJump by remember { mutableStateOf<Job?>(null) }
    val artworkBackdrop = rememberHazeState()

    BoxWithConstraints(
        Modifier.fillMaxSize().background(DetailBackground)
            .navigationBarsPadding().imePadding(),
    ) {
        val density = LocalDensity.current
        val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val availableHeight = (maxHeight - statusInset).coerceAtLeast(0.dp)
        val landscape = maxWidth > availableHeight
        val compactHero = availableHeight < 600.dp || density.fontScale >= 1.5f
        val heroHeight = if (compactHero) {
            (availableHeight * .32f).coerceAtMost((availableHeight - 250.dp).coerceAtLeast(0.dp))
        } else {
            (availableHeight * .60f).coerceAtMost(560.dp).coerceAtMost(availableHeight - 280.dp)
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
        val artworkScroll = rememberScrollableState { delta ->
            contentsJump?.cancel()
            val previous = header.heightOffset
            header.heightOffset = (previous + delta).coerceIn(header.heightOffsetLimit, 0f)
            // Consume exactly the header's movement, including reversal and fling frames.
            header.heightOffset - previous
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
        // Sakurairo's fixed-background relationship: only the foreground moves.
        // Keep this image's bounds independent of heightOffset to avoid recropping each frame.
        Box(
            Modifier.fillMaxWidth().height(heroHeight + 48.dp + statusInset)
                .hazeSource(artworkBackdrop).background(DetailSurface),
            contentAlignment = Alignment.TopCenter,
        ) {
            Icon(Icons.Outlined.MenuBook, null, tint = DetailMuted.copy(alpha = .3f),
                modifier = Modifier.align(Alignment.Center).size(48.dp))
            if (current.coverUrl.isNotBlank()) AsyncImage(
                model = coverRequest, contentDescription = null, modifier = Modifier.fillMaxSize(),
                contentScale = if (landscape) ContentScale.Fit else ContentScale.Crop,
                alignment = Alignment.TopCenter,
                onError = { coverFailed = true }, onSuccess = { coverFailed = false },
            )
            Box(Modifier.fillMaxWidth().height(statusInset + 96.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .46f), Color.Transparent))))
        }
        Column(Modifier.fillMaxSize()) {
            ArtworkToolbar(
                header = header, compact = compactHero, title = current.title, backdrop = artworkBackdrop,
                favorite = isFavorite, coverFailed = coverFailed, active = active,
                onBack = onBack, onIntroduction = { fullIntroduction = true },
                onToggleFavorite = onToggleFavorite, onRetryCover = {
                    coverFailed = false
                    coverRetry++
                },
            )
            Box(
                Modifier.fillMaxWidth().clipToBounds()
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
                // The empty artwork area and the card are sibling gesture targets. Each
                // owns one scroll chain; the header must not also consume it in pre-scroll.
                Box(Modifier.fillMaxSize().scrollable(artworkScroll, Orientation.Vertical))
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!compactHero) {
                        HeroBookInformation(current, artworkBackdrop, onIntroduction = { fullIntroduction = true },
                            onContents = openContents,
                            modifier = Modifier.scrollable(artworkScroll, Orientation.Vertical))
                    } else {
                        LiquidGlassSurface(artworkBackdrop,
                            Modifier.size(48.dp).scrollable(artworkScroll, Orientation.Vertical), cornerRadius = 16.dp) {
                            MotionIconButton(onClick = openContents, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowDown, "滑到章节", tint = DetailForeground)
                            }
                        }
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
                MotionTabContent(activeSection, Modifier.weight(1f).fillMaxWidth()) { targetSection ->
                    sections.SaveableStateProvider(targetSection) {
                        when (targetSection) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtworkToolbar(
    header: TopAppBarState,
    compact: Boolean,
    title: String,
    backdrop: HazeState,
    favorite: Boolean,
    coverFailed: Boolean,
    active: Boolean,
    onBack: () -> Unit,
    onIntroduction: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRetryCover: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress = header.collapsedFraction.coerceIn(0f, 1f)
    val titleAlpha = if (compact) 1f else ((progress - .35f) / .65f).coerceIn(0f, 1f)
    val titleInteraction = remember { MutableInteractionSource() }
    val activity = LocalContext.current.activityOrNull()
    val lightSurface = colors.surface.luminance() > .5f
    val lightStatusBars by remember(header, lightSurface) {
        derivedStateOf { lightSurface && header.collapsedFraction > .7f }
    }
    LaunchedEffect(active, activity, lightStatusBars, lightSurface) {
        if (active) activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = lightStatusBars
                isAppearanceLightNavigationBars = lightSurface
            }
        }
    }
    Row(Modifier.fillMaxWidth().background(colors.surface.copy(alpha = progress))
        .statusBarsPadding().height(48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ArtworkAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", backdrop, 1f - progress, onBack)
        Box(Modifier.weight(1f).padding(horizontal = 4.dp).graphicsLayer { alpha = titleAlpha }
            .motionInputGate(titleAlpha > .1f)
            .clickable(interactionSource = titleInteraction, indication = null, role = Role.Button,
                onClickLabel = "漫画信息与完整简介", onClick = onIntroduction)
            .clip(RoundedCornerShape(12.dp))) {
            LiquidGlassSurface(backdrop,
                Modifier.matchParentSize().graphicsLayer { alpha = 1f - progress }, cornerRadius = 12.dp) {}
            Text(title, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                .motionPress(titleInteraction, pressedScale = .985f),
                color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (coverFailed) ArtworkAction(Icons.Outlined.Refresh, "重新加载封面", backdrop, 1f - progress, onRetryCover)
        ArtworkAction(if (favorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            if (favorite) "从书架移除" else "加入书架", backdrop, 1f - progress, onToggleFavorite)
    }
}

@Composable
private fun ArtworkAction(icon: ImageVector, description: String, backdrop: HazeState, glassAlpha: Float, onClick: () -> Unit) {
    MotionIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            LiquidGlassSurface(backdrop,
                Modifier.matchParentSize().graphicsLayer { alpha = glassAlpha }, cornerRadius = 14.dp) {}
            MotionSwap(icon, Modifier.size(21.dp)) { shown ->
                Icon(shown, description, tint = DetailForeground, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun HeroBookInformation(
    manga: Manga,
    backdrop: HazeState,
    onIntroduction: () -> Unit,
    onContents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiquidGlassSurface(backdrop, modifier.fillMaxWidth(), cornerRadius = 18.dp) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 14.dp).padding(top = 13.dp, bottom = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                manga.title, color = DetailForeground, fontSize = 22.sp, lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (manga.authors.isNotEmpty()) Text(
                manga.authors.joinToString(" / "), color = DetailForeground.copy(alpha = .82f), fontSize = 11.sp,
                lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "简介 · " + manga.description.ifBlank { "暂无简介" },
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable(role = Role.Button, onClickLabel = "查看完整简介", onClick = onIntroduction),
                color = DetailForeground.copy(alpha = .82f), fontSize = 12.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            DetailTags(manga)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MangaMetricBadges(manga, Modifier.weight(1f), foreground = DetailForeground)
                MotionIconButton(onClick = onContents, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.KeyboardArrowDown, "滑到章节", tint = DetailForeground)
                }
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
    MotionBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface, contentColor = DetailForeground,
    ) { _ ->
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
    MotionTabs(selected, labels, onSelect,
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 2.dp),
        foreground = DetailForeground, muted = DetailMuted,
        indicator = DetailForeground, underline = true, compact = true,
        selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal)
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
    val interaction = remember { MutableInteractionSource() }
    val busy = rememberDelayedBusy(loading && !canRead)
    val label = when {
        resume != null -> "继续阅读 · " + resume.chapterTitle
        firstChapter != null -> "开始阅读 · " + firstChapter.title
        loading -> "加载章节"
        else -> "暂无可读章节"
    }
    Button(
        onClick = { if (resume != null) onContinue(resume) else firstChapter?.let(onRead) },
        enabled = canRead,
        interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 50.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DetailForeground, contentColor = DetailBackground,
            disabledContainerColor = DetailSurface, disabledContentColor = DetailMuted,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth().motionPress(interaction, enabled = canRead, pressedScale = .985f),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (loading && !canRead) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = DetailMuted, strokeWidth = 1.5.dp)
                } else Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(22.dp))
            }
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
    Box(
        Modifier.size(48.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                enabled = enabled, onClick = onClick)
            .motionPress(interaction, enabled = enabled, pressedScale = .94f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) DetailForeground.copy(alpha = .10f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        MotionSwap(icon, Modifier.size(21.dp)) { currentIcon ->
            Icon(currentIcon, description,
                tint = if (!enabled) DetailMuted.copy(alpha = .3f) else if (selected) DetailForeground else DetailSecondary,
                modifier = Modifier.size(21.dp))
        }
    }
}
