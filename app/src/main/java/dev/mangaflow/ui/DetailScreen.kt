package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animate
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import coil3.request.allowHardware
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.Page
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
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.motionPress
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
    chapterPositions: Map<String, ReadingProgress>?,
    downloadEntries: List<DownloadEntry>,
    loadVolumeCover: suspend (Chapter, Boolean) -> Page,
    volumeImageHeaders: (Chapter) -> Map<String, String>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRead: (Chapter) -> Unit,
    onContinue: (ReadingProgress) -> Unit,
    onDownload: (Chapter) -> Unit,
    onAuthor: (Manga, String) -> Unit = { _, _ -> },
    onReviewMore: (String) -> Unit = {},
    onReviewRetry: (String) -> Unit = {},
    onReviewMatch: (String, String) -> Unit = { _, _ -> },
    onReviewRelink: (String) -> Unit = {},
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage = { _, _, _ -> ReviewPage(emptyList()) },
    active: Boolean = true,
    onRestart: (Chapter) -> Unit = onRead,
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
    var fullRatings by rememberSaveable(manga.id) { mutableStateOf(false) }
    var commentFilterOpen by remember(manga.id) { mutableStateOf(false) }
    var commentFilterChanged by remember(manga.id) { mutableStateOf(false) }
    var dockHidden by remember(manga.id) { mutableStateOf(false) }
    val dockTravel = remember(manga.id) { floatArrayOf(0f) }
    var commentsList by remember(manga.id) { mutableStateOf<LazyListState?>(null) }
    val commentCount = detailCommentCount(reviews)
    var coverFailed by remember(current.coverUrl) { mutableStateOf(false) }
    var coverLight by remember(current.coverUrl) { mutableStateOf(false) }
    var coverRetry by remember(current.coverUrl) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val coverRequest = remember(context, current.coverUrl, current.sourceUrl, current.id, coverRetry, motion) {
        ImageRequest.Builder(context).data(current.coverUrl)
            .allowHardware(false)
            .httpHeaders(NetworkHeaders.Builder().set("User-Agent", "Mozilla/5.0")
                .set("Referer", current.sourceUrl.ifBlank { "https://www.copy3000.com/comic/" + current.id }).build())
            .crossfade(if (motion) MangaMotion.Quick else 0)
            .placeholderMemoryCacheKey("manga-cover:" + current.coverUrl)
            .memoryCacheKey("detail-cover:" + current.coverUrl + ":" + coverRetry).build()
    }
    val scope = rememberCoroutineScope()
    var contentsJump by remember { mutableStateOf<Job?>(null) }
    var catalogGrid by remember(current.id) { mutableStateOf<LazyGridState?>(null) }
    val artworkBackdrop = rememberHazeState()
    val readingBackdrop = rememberHazeState()
    val commentsBackdrop = rememberHazeState()

    BoxWithConstraints(
        Modifier.fillMaxSize().background(DetailBackground)
            .navigationBarsPadding().imePadding(),
    ) {
        val density = LocalDensity.current
        val readingInset = (60f * density.fontScale.coerceAtLeast(1f) + 28f).dp
        val statusInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val availableHeight = (maxHeight - statusInset).coerceAtLeast(0.dp)
        val landscape = maxWidth > availableHeight
        val compactHero = availableHeight < 600.dp || density.fontScale >= 1.5f
        val heroHeight = if (compactHero) {
            (availableHeight * .32f).coerceAtMost((availableHeight - 250.dp).coerceAtLeast(0.dp))
        } else {
            (availableHeight * .56f - 56.dp).coerceAtLeast(0.dp)
                .coerceAtMost(560.dp).coerceAtMost((availableHeight - 280.dp).coerceAtLeast(0.dp))
        }
        val heroPixels = with(density) { heroHeight.toPx() }
        val header = rememberTopAppBarState(initialHeightOffsetLimit = -heroPixels)
        val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = header, snapAnimationSpec = null, flingAnimationSpec = rememberSplineBasedDecay(),
        )
        SideEffect {
            if (header.heightOffsetLimit != -heroPixels) {
                val collapsed = header.collapsedFraction
                contentsJump?.cancel()
                header.heightOffsetLimit = -heroPixels
                header.heightOffset = -heroPixels * collapsed
            }
        }
        val scrolling = remember(behavior, activeSection) {
            object : NestedScrollConnection by behavior.nestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) {
                        contentsJump?.cancel()
                        if (available.y < 0f && activeSection == 1 && header.collapsedFraction > .8f) {
                            dockTravel[0] += -available.y
                            if (dockTravel[0] > with(density) { 72.dp.toPx() }) dockHidden = true
                        } else if (available.y > 0f) {
                            dockTravel[0] = 0f
                            dockHidden = false
                        }
                    }
                    return behavior.nestedScrollConnection.onPreScroll(available, source)
                }
            }
        }
        val emptyCatalogScroll = rememberScrollableState { 0f }
        // Artwork and chapter surfaces share one scroll mutex and nested header decay.
        // Remaining fling velocity flows into the grid after the artwork has collapsed.
        val artworkScroll: ScrollableState = if (activeSection == 0) catalogGrid ?: emptyCatalogScroll
            else commentsList ?: emptyCatalogScroll
        val openContents: () -> Unit = {
            section = 0
            dockHidden = false
            contentsJump?.cancel()
            contentsJump = scope.launch {
                animate(header.heightOffset, header.heightOffsetLimit, animationSpec = tween(if (motion) 320 else 0, easing = MangaMotion.EnterEasing)) { value, _ ->
                    header.heightOffset = value
                }
            }
        }
        // Sakurairo's fixed-background relationship: only the foreground moves.
        // Keep this image's bounds independent of heightOffset to avoid recropping each frame.
        Box(
            Modifier.fillMaxWidth().height(heroHeight + 56.dp + statusInset)
                .hazeSource(artworkBackdrop).background(DetailSurface),
            contentAlignment = Alignment.TopCenter,
        ) {
            Icon(Icons.Outlined.MenuBook, null, tint = DetailMuted.copy(alpha = .3f),
                modifier = Modifier.align(Alignment.Center).size(48.dp))
            if (current.coverUrl.isNotBlank()) AsyncImage(
                model = coverRequest, contentDescription = null,
                modifier = Modifier.fillMaxSize().mangaSharedCover(current.id),
                contentScale = if (landscape) ContentScale.Fit else ContentScale.Crop,
                alignment = Alignment.TopCenter,
                onError = { coverFailed = true }, onSuccess = { result ->
                    coverFailed = false
                    val bitmap = (result.result.image as? coil3.BitmapImage)?.bitmap
                    if (bitmap != null) runCatching {
                        listOf(.15f, .85f).flatMap { x -> listOf(.06f, .14f).map { y ->
                            Color(bitmap.getPixel((bitmap.width * x).toInt().coerceIn(0, bitmap.width - 1),
                                (bitmap.height * y).toInt().coerceIn(0, bitmap.height - 1))).luminance()
                        } }.average() > .45
                    }.getOrNull()?.let { coverLight = it }
                },
            )
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(heroHeight * .72f)
                .background(Brush.verticalGradient(0f to Color.Transparent,
                    .48f to Color.Black.copy(alpha = .46f), 1f to Color.Black.copy(alpha = .86f))))
            // The whole visible cover yields to the neutral scene together; no toolbar-only white band.
            Box(Modifier.matchParentSize().graphicsLayer {
                alpha = if (compactHero) 1f else ((header.collapsedFraction - .68f) / .32f).coerceIn(0f, 1f)
            }.background(DetailBackground))
        }
        Column(Modifier.fillMaxSize().hazeSource(readingBackdrop).nestedScroll(scrolling)) {
            ArtworkToolbar(
                header = header, compact = compactHero, title = current.title, backdrop = artworkBackdrop,
                favorite = isFavorite, coverFailed = coverFailed, active = active, coverLight = coverLight,
                comments = activeSection == 1, commentCount = commentCount,
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
                Box(Modifier.fillMaxSize().scrollable(artworkScroll, Orientation.Vertical,
                    reverseDirection = true, enabled = active))
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!compactHero) {
                        HeroBookInformation(current, onIntroduction = { fullIntroduction = true },
                            onRatings = { fullRatings = true }, onAuthor = { onAuthor(current, it) },
                            collapse = { header.collapsedFraction },
                            modifier = Modifier.scrollable(artworkScroll, Orientation.Vertical,
                                reverseDirection = true, enabled = active))
                    } else {
                        LiquidGlassSurface(artworkBackdrop,
                            Modifier.size(48.dp).scrollable(artworkScroll, Orientation.Vertical,
                                reverseDirection = true, enabled = active),
                            cornerRadius = 16.dp, trackPointer = true) {
                            MotionIconButton(onClick = openContents, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowDown, "滑到章节", tint = DetailForeground)
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = (24f * (1f - header.collapsedFraction)).dp,
                        topEnd = (24f * (1f - header.collapsedFraction)).dp))
                    // Share the grid's scroll mutex as well as its position: a new drag
                    // on the surrounding surface must cancel an existing grid fling.
                    .scrollable(artworkScroll, Orientation.Vertical,
                        reverseDirection = true, enabled = active)
                    .background(DetailBackground).padding(top = 8.dp),
            ) {
                if (compactHero) DetailTags(current, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
                DetailSections(
                    selected = activeSection,
                    chapterCount = chapters.size, commentCount = commentCount,
                    onSelect = { section = it; commentFilterOpen = false; dockHidden = false; dockTravel[0] = 0f },
                    filterOpen = commentFilterOpen, filterChanged = commentFilterChanged,
                    onToggleFilter = { commentFilterOpen = !commentFilterOpen },
                )
                MotionTabContent(activeSection, Modifier.weight(1f).fillMaxWidth()) { targetSection ->
                    sections.SaveableStateProvider(targetSection) {
                        when (targetSection) {
                            0 -> DetailCatalog(
                                mangaId = current.id, chapters = chapters, downloaded = downloaded,
                                progress = resume, chapterPositions = chapterPositions,
                                loading = loading, error = error,
                                active = active && targetSection == activeSection,
                                onScrollTarget = { catalogGrid = it },
                                onRetry = onRetry, onRead = onRead, onDownload = onDownload,
                                loadVolumeCover = loadVolumeCover, volumeImageHeaders = volumeImageHeaders,
                                bottomPadding = readingInset,
                            )
                            else -> CommunityPanel(
                                mangaId = current.id, sources = reviews, loading = reviewsLoading,
                                onMore = onReviewMore, onRetry = onReviewRetry, onMatch = onReviewMatch,
                                onRelink = onReviewRelink, loadReplies = loadReplies,
                                onScrollTarget = { commentsList = it }, backdrop = commentsBackdrop,
                                active = active && targetSection == activeSection, bottomPadding = readingInset,
                                filterOpen = commentFilterOpen, onFilterDismiss = { commentFilterOpen = false },
                                onFilterChanged = { commentFilterChanged = it },
                            )
                        }
                    }
                }
            }
        }
        DetailReadingDock(mangaId = current.id, backdrop = readingBackdrop,
            resume = resume, firstChapter = chapters.firstOrNull(), loading = loading,
            hidden = dockHidden, active = active,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            onContinue = onContinue, onRead = onRead, onRestart = onRestart, onContents = openContents)
    }
    if (fullIntroduction) DetailIntroduction(current,
        onAuthor = { fullIntroduction = false; onAuthor(current, it) }, onDismiss = { fullIntroduction = false })
    if (fullRatings) DetailRatingSheet(current) { fullRatings = false }
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
    coverLight: Boolean,
    comments: Boolean,
    commentCount: String?,
    onBack: () -> Unit,
    onIntroduction: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRetryCover: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val progress = if (compact) 1f else ((header.collapsedFraction - .68f) / .32f).coerceIn(0f, 1f)
    val artworkTint = if (coverLight) Color(0xFF242426) else Color.White
    val tint = lerp(artworkTint, DetailForeground, progress)
    val glassTone = if (coverLight) Color(0xFFFCFCFA) else Color(0xFF202023)
    val activity = LocalContext.current.activityOrNull()
    val lightSurface = colors.surface.luminance() > .5f
    val lightStatusBars by remember(header, lightSurface, compact, coverLight) {
        derivedStateOf {
            if (compact || header.collapsedFraction > .9f) lightSurface else coverLight
        }
    }
    LaunchedEffect(active, activity, lightStatusBars, lightSurface) {
        if (active) activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView).apply {
                isAppearanceLightStatusBars = lightStatusBars
                isAppearanceLightNavigationBars = lightSurface
            }
        }
    }
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", backdrop, 1f - progress, tint, glassTone, onBack)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp).graphicsLayer { alpha = progress }
            .then(if (progress > .02f) Modifier else Modifier.clearAndSetSemantics { })
            .clickable(enabled = progress > .7f, role = Role.Button, onClickLabel = "查看作品信息", onClick = onIntroduction),
            verticalArrangement = Arrangement.Center) {
            if (comments) Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("评论", color = DetailForeground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                commentCount?.let { Text(it, color = DetailSecondary, fontSize = 11.sp) }
            }
            Text(title, color = if (comments) DetailSecondary else DetailForeground,
                fontSize = if (comments) 11.sp else 15.sp, lineHeight = if (comments) 15.sp else 20.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth())
        }
        if (coverFailed) ArtworkAction(Icons.Outlined.Refresh, "重新加载封面", backdrop, 1f - progress, tint, glassTone, onRetryCover)
        ArtworkAction(if (favorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            if (favorite) "从书架移除" else "加入书架", backdrop, 1f - progress, tint, glassTone, onToggleFavorite)
    }
}

@Composable
private fun ArtworkAction(icon: ImageVector, description: String, backdrop: HazeState, glassAlpha: Float,
    tint: Color, glassTone: Color, onClick: () -> Unit) {
    MotionIconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            LiquidGlassSurface(backdrop,
                Modifier.matchParentSize().graphicsLayer {
                    alpha = glassAlpha
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }, cornerRadius = 16.dp, elevation = 8.dp,
                trackPointer = true, surfaceColor = glassTone, softCard = true) {}
            MotionSwap(icon, Modifier.size(21.dp)) { shown ->
                Icon(shown, description, tint = tint, modifier = Modifier.size(21.dp))
            }
        }
    }
}

@Composable
private fun HeroBookInformation(
    manga: Manga,
    onIntroduction: () -> Unit,
    onRatings: () -> Unit,
    onAuthor: (String) -> Unit,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
) {
    val infoVisible = collapse() < .22f
    Column(modifier.fillMaxWidth().graphicsLayer { alpha = (1f - collapse() / .3f).coerceIn(0f, 1f) }
        .then(if (infoVisible) Modifier else Modifier.clearAndSetSemantics { })
        .padding(horizontal = 22.dp).padding(top = 20.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(manga.title, color = Color.White, fontSize = 22.sp, lineHeight = 29.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth())
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AuthorNames(manga.authors.take(2), Color.White.copy(alpha = .85f), enabled = infoVisible, onAuthor = onAuthor)
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp)
                .clickable(enabled = infoVisible, role = Role.Button,
                    onClickLabel = "展开简介与作品信息", onClick = onIntroduction),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(manga.description.ifBlank { "暂无简介" }, color = Color.White.copy(alpha = .88f),
                    fontSize = 13.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f))
                Text("展开", color = Color.White.copy(alpha = .7f), fontSize = 11.sp)
            }
            DetailTags(manga, onArtwork = true)
            DetailRatingStrip(manga, Color.White, enabled = infoVisible, onExpand = onRatings)
        }
    }
}

@Composable
private fun DetailTags(manga: Manga, modifier: Modifier = Modifier, onArtwork: Boolean = false) {
    val tags = remember(manga.status, manga.tags, manga.latestChapter) {
        buildList {
            if (manga.status.isNotBlank()) add(manga.status.trim())
            addAll(manga.tags.map(String::trim).filter(String::isNotBlank).distinct())
            if (manga.latestChapter.isNotBlank()) add("更新 · " + manga.latestChapter)
        }.distinct()
    }
    if (tags.isNotEmpty()) Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { tags.forEachIndexed { index, tag -> DetailTag(tag, index, onArtwork) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailIntroduction(manga: Manga, onAuthor: (String) -> Unit, onDismiss: () -> Unit) {
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
                AuthorNames(manga.authors, DetailSecondary, onAuthor = onAuthor)
                DetailTags(manga)
                MangaMetricBadges(manga, Modifier.fillMaxWidth(), foreground = DetailForeground)
                Text(manga.description.ifBlank { "来源暂无简介" },
                    fontSize = 14.sp, lineHeight = 23.sp, color = DetailSecondary)
            }
        }
    }
}

@Composable
private fun DetailTag(label: String, index: Int, onArtwork: Boolean = false) {
    val onDark = onArtwork || DetailForeground.luminance() > .5f
    val accent = when (index % 4) {
        0 -> if (onDark) Color(0xFFD9B49E) else Color(0xFF805C43)
        1 -> if (onDark) Color(0xFFAFC4E2) else Color(0xFF486481)
        2 -> if (onDark) Color(0xFFC7B0DC) else Color(0xFF75588A)
        else -> if (onDark) Color(0xFFD5AFBB) else Color(0xFF865B6B)
    }
    Text(
        label,
        modifier = Modifier.clip(RoundedCornerShape(7.dp))
            .background(accent.copy(alpha = if (onArtwork) .18f else if (onDark) .12f else .085f))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        color = accent, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuthorNames(authors: List<String>, color: Color, enabled: Boolean = true, onAuthor: (String) -> Unit) {
    if (authors.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        authors.filter(String::isNotBlank).distinct().forEach { name ->
            Text(name, color = color, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled, role = Role.Button, onClickLabel = "查看${name}的漫画") { onAuthor(name) }
                    .padding(vertical = 4.dp))
        }
    }
}

/** Chapter/comment tabs keep their Ovio selector; the filter belongs beside the comment tab. */
@Composable
private fun DetailSections(
    selected: Int, chapterCount: Int, commentCount: String?, onSelect: (Int) -> Unit,
    filterOpen: Boolean, filterChanged: Boolean, onToggleFilter: () -> Unit,
) {
    val labels = listOf("章节", "评论")
    val motion = LocalMotionEnabled.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MotionTabs(selected, labels, onSelect, modifier = Modifier.weight(1f),
            foreground = DetailForeground, muted = DetailSecondary, indicator = DetailForeground.copy(alpha = .065f),
            compact = false, fullWidthIndicator = true, indicatorHeight = 36.dp, fontSize = 15.sp,
            treatment = MotionTabTreatment.Soft,
            itemContent = { index, chosen, color ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(labels[index], color = color, fontSize = 15.sp,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium)
                    val count = if (index == 0) chapterCount.takeIf { it > 0 }?.toString() else commentCount
                    count?.let { Text(it, color = color.copy(alpha = .65f), fontSize = 11.sp) }
                }
            })
        AnimatedVisibility(selected == 1,
            enter = fadeIn(tween(if (motion) MangaMotion.Content else 0)) + expandHorizontally(tween(if (motion) MangaMotion.Content else 0)),
            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) + shrinkHorizontally(tween(if (motion) MangaMotion.Content else 0))) {
            DetailAction(Icons.Outlined.FilterList, if (filterOpen) "收起评论筛选" else "筛选评论",
                selected = filterOpen || filterChanged, enabled = selected == 1, onClick = onToggleFilter)
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
