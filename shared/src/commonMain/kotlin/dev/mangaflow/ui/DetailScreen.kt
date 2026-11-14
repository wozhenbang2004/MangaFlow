package dev.mangaflow.ui

import dev.mangaflow.typography.MangaFontScene

import dev.mangaflow.ui.platform.*

import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding

import coil3.Image as CoilImage
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.MenuBook
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import coil3.compose.rememberAsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.data.Page
import dev.mangaflow.data.MangaReview
import dev.mangaflow.data.ReviewPage
import dev.mangaflow.data.ReviewSource
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DetailAppearanceStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ChapterPreparationState
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    onDownload: (Chapter, DownloadRouteMode?) -> Unit,
    onDownloadVariant: (Chapter, ChapterVariant, DownloadRouteMode?) -> Unit,
    onAuthor: ((Manga, String) -> Unit)? = { _, _ -> },
    onTag: ((Manga, String) -> Unit)? = { _, _ -> },
    onReviewMore: (String) -> Unit = {},
    onReviewRetry: (String) -> Unit = {},
    onReviewMatch: (String, String) -> Unit = { _, _ -> },
    onReviewRelink: (String) -> Unit = {},
    loadReplies: suspend (ReviewSource, MangaReview, Int) -> ReviewPage = { _, _, _ -> ReviewPage(emptyList()) },
    active: Boolean = true,
    onRestart: (Chapter) -> Unit = onRead,
    wallpaper: @Composable () -> Unit = {},
    onReadVariant: (Chapter, ChapterVariant) -> Unit = { _, _ -> },
    onSetPreferredSource: (String) -> Unit = {},
    onClearChapterSource: (Chapter) -> Unit = {},
    onSplitVariant: (Chapter, ChapterVariant) -> Unit = { _, _ -> },
    onRelinkVariant: (Chapter, ChapterVariant, Chapter) -> Unit = { _, _, _ -> },
    onManageSources: () -> Unit = {},
    onCancelSourceSelection: () -> Unit = {},
    sourceLoading: Boolean = false,
    sourceError: String? = null,
    sourcesChecking: Boolean = false,
    pendingSourceCount: Int = 0,
    repository: MangaRepository? = null,
    commentsAvailable: Boolean = true,
    sourceCommentsContent: (@Composable (Modifier, Dp, Int, Boolean) -> Unit)? = null,
    downloadPreparations: List<ChapterPreparationState> = emptyList(),
    cloudProgress: CopyProgressHintState? = null,
    onAdoptCloudProgress: (() -> Unit)? = null,
    onRetryCloudProgress: (() -> Unit)? = null,
    preferredResumeChapter: Chapter? = null,
    onContinuePreferred: (() -> Unit)? = null,
) {
    val current = detail?.manga ?: manga
    var pendingDownload by remember(current.id) { mutableStateOf<Chapter?>(null) }
    var pendingDownloadAnchor by remember(current.id) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val downloadSourceEntries = remember(downloadEntries) { downloadEntries.associateBy { it.chapter.id } }
    val preparingSources = remember(downloadPreparations, current.id) {
        downloadPreparations.filter { it.manga.id == current.id }.associateBy { it.chapter.id }
    }
    LaunchedEffect(active, current.id) { if (!active) pendingDownload = null }
    val feedback = dev.mangaflow.ui.motion.rememberFeelFeedback()
    val downloaded = remember(downloadEntries, manga.id) {
        downloadEntries.filter { it.manga.id == manga.id }.associateBy { it.chapter.id }
    }
    val chapters = remember(detail, downloaded) {
        detail?.chapters ?: downloaded.values.filter { it.status == DownloadStatus.COMPLETED }
            .map(DownloadEntry::chapter).distinctBy(Chapter::id).sortedBy(Chapter::index)
    }
    val sourceCount = remember(chapters) {
        chapters.asSequence().flatMap { it.variants.asSequence() }.map { it.sourceId }
            .filter(String::isNotBlank).distinct().count()
    }
    val resume = progress?.takeIf { it.manga.id == manga.id && it.chapterId.isNotBlank() }
    val copyProgress = cloudProgress?.takeIf { it.mangaId == current.id && it.owner != null }
    var section by rememberSaveable(manga.id) { mutableIntStateOf(0) }
    var chapterDownloadMode by rememberSaveable(manga.id) { mutableStateOf(false) }
    val sections = rememberSaveableStateHolder()
    val activeSection = if (commentsAvailable) section.coerceIn(0, 1) else 0

    var fullIntroduction by rememberSaveable(manga.id) { mutableStateOf(false) }
    var fullRatings by rememberSaveable(manga.id) { mutableStateOf(false) }
    var commentFilterOpen by remember(manga.id) { mutableStateOf(false) }
    var commentFilterChanged by remember(manga.id) { mutableStateOf(false) }
    var dockHidden by remember(manga.id) { mutableStateOf(false) }
    val dockTravel = remember(manga.id) { floatArrayOf(0f) }
    var commentsList by remember(manga.id) { mutableStateOf<LazyListState?>(null) }
    var commentsOpenRevision by remember(manga.id) { mutableIntStateOf(0) }
    val commentCount = detailCommentCount(reviews)
    LaunchedEffect(commentsAvailable) {
        if (!commentsAvailable) {
            section = 0
            commentFilterOpen = false
            commentsList = null
            dockHidden = false
            dockTravel[0] = 0f
        }
    }
    var coverFailed by remember(current.coverUrl) { mutableStateOf(false) }
    var coverRetry by remember(current.coverUrl) { mutableIntStateOf(0) }
    var coverBitmap by remember(current.coverUrl) { mutableStateOf<CoilImage?>(null) }
    var extractedCoverColor by remember(current.coverUrl) {
        mutableStateOf(FeaturedCoverColors.get(current.coverUrl))
    }
    val context = LocalContext.current
    val appearanceStore = remember(context) { DetailAppearanceStore.get(context) }
    val appearance by appearanceStore.state.collectAsStateWithLifecycle()
    val motion = LocalMotionEnabled.current
    LaunchedEffect(current.coverUrl, coverBitmap) {
        val bitmap = coverBitmap ?: return@LaunchedEffect
        val color = FeaturedCoverColors.get(current.coverUrl)
            ?: withContext(Dispatchers.Default) { FeaturedCoverColors.extract(current.coverUrl, bitmap) }
        if (color != null) extractedCoverColor = color
    }
    val coverColor = extractedCoverColor ?: DetailSurface
    val atmosphereColor by animateColorAsState(lerp(DetailBackground, coverColor, .36f),
        animationSpec = if (motion) tween(420, easing = MangaMotion.EnterEasing) else snap(),
        label = "detail-artwork-atmosphere")
    val informationTone by animateColorAsState(lerp(DetailBackground, coverColor,
        if (DetailBackground.luminance() > .5f) .08f else .12f),
        animationSpec = if (motion) tween(420, easing = MangaMotion.EnterEasing) else snap(),
        label = "detail-information-tone")
    val lightDetail = DetailBackground.luminance() > .5f
    val dockDensity = LocalDensity.current
    var measuredDockHeight by remember(current.id, dockDensity) { mutableIntStateOf(0) }
    val window = LocalMangaWindow.current
    val coverDecodeWidth = with(dockDensity) { (window.width * .5f).roundToPx() }.coerceIn(320, 1024)
    val coverRequest = remember(context, current.coverUrl, current.sourceUrl, current.id, coverRetry, motion, coverDecodeWidth) {
        ImageRequest.Builder(imagePlatformContext(context)).data(current.coverUrl)
            .softwareBitmap()
            .size(coverDecodeWidth, coverDecodeWidth * 3 / 2)
            .httpHeaders(NetworkHeaders.Builder().set("User-Agent", "Mozilla/5.0")
                .set("Referer", current.sourceUrl.ifBlank { "https://www.copy3000.com/comic/" + current.id }).build())
            .crossfade(0)
            .placeholderMemoryCacheKey("manga-cover:" + current.coverUrl)
            .memoryCacheKey("detail-cover:" + current.coverUrl + ":" + coverRetry).build()
    }
    // The sharp poster and blurred atmosphere share one request and one decoded image.
    val coverPainter = rememberAsyncImagePainter(coverRequest, contentScale = ContentScale.Fit,
        onError = { if (current.coverUrl.isNotBlank()) coverFailed = true }, onSuccess = { result ->
            coverFailed = false
            coverBitmap = result.result.image
        })
    val atmosphereBlur = remember(appearance.blur) {
        HazeBlurStyle {
            blurRadius(appearance.blur.dp)
            backgroundColor(Color.Transparent)
            colorEffects(emptyList())
            noiseFactor(0f)
            blurredEdgeTreatment(BlurredEdgeTreatment.Unbounded)
        }
    }
    val scope = rememberCoroutineScope()
    var contentsJump by remember { mutableStateOf<Job?>(null) }
    var catalogGrid by remember(current.id) { mutableStateOf<LazyGridState?>(null) }
    val artworkBackdrop = rememberHazeState()
    val readingBackdrop = rememberHazeState()

    LaunchedEffect(active, activeSection) {
        if (active && activeSection == 1) {
            contentsJump?.cancel()
            catalogGrid?.stopScroll()
        }
    }

    // Both layouts keep one catalogue/comment implementation and the same saved section state.
    val sectionPane: @Composable (Modifier, Dp) -> Unit = { paneModifier, paneBottomPadding ->
        Column(paneModifier) {
            DetailSections(
                selected = activeSection,
                commentsAvailable = commentsAvailable, sourceComments = sourceCommentsContent != null,
                chapterCount = chapters.size, commentCount = commentCount,
                onSelect = { target ->
                    if (target == 0 || commentsAvailable) {
                        if (target == 1) {
                            // Do not restore a previously scrolled comment position on a new visit.
                            // The pane may still be transitioning, so also notify its own list state.
                            commentsOpenRevision++
                            commentsList?.requestScrollToItem(0)
                        }
                        section = target
                        commentFilterOpen = false
                        dockHidden = false
                        dockTravel[0] = 0f
                    }
                },
                filterOpen = commentFilterOpen, filterChanged = commentFilterChanged,
                onToggleFilter = { if (commentsAvailable) commentFilterOpen = !commentFilterOpen },
                downloadMode = chapterDownloadMode,
                onToggleDownload = { chapterDownloadMode = !chapterDownloadMode },
                sourceId = current.sourceId,
                sourceName = current.sourceName.ifBlank { sourceDisplayName(current.sourceId) },
                sourceCount = sourceCount, sourceLoading = sourceLoading,
                sourcesChecking = sourcesChecking, pendingSourceCount = pendingSourceCount,
                onManageSources = onManageSources,
            )
            // Let content draw behind the floating dock to the physical bottom edge.
            // Reserve its stable clearance inside each list so the last item stays reachable.
            MotionTabContent(activeSection, Modifier.weight(1f).fillMaxWidth(),
                contentKey = { it }, order = { it }, soft = true) { targetSection ->
                sections.SaveableStateProvider(targetSection) {
                    when (targetSection) {
                        0 -> DetailCatalog(
                            mangaId = current.id, chapters = chapters, downloaded = downloaded,
                            repository = repository,
                            progress = resume, chapterPositions = chapterPositions,
                            loading = loading, error = error,
                            active = active && targetSection == activeSection,
                            onScrollTarget = { catalogGrid = it },
                            onRetry = onRetry, onRead = onRead,
                            onDownload = { chapter, anchor -> if (active) {
                                pendingDownloadAnchor = anchor
                                pendingDownload = chapter
                            } },
                            loadVolumeCover = loadVolumeCover, volumeImageHeaders = volumeImageHeaders,
                            bottomPadding = paneBottomPadding,
                            downloadMode = chapterDownloadMode,
                            backdrop = readingBackdrop,
                            onReadVariant = onReadVariant,
                            onSetPreferredSource = onSetPreferredSource,
                            onClearChapterSource = onClearChapterSource,
                            onSplitVariant = onSplitVariant,
                            onRelinkVariant = onRelinkVariant,
                            onCancelSourceSelection = onCancelSourceSelection,
                            sourceLoading = sourceLoading, sourceError = sourceError,
                        )
                        else -> if (sourceCommentsContent != null) {
                            sourceCommentsContent(Modifier.fillMaxSize(), paneBottomPadding, commentsOpenRevision, active && targetSection == activeSection)
                        } else if (commentsAvailable) Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().padding(end =
                                if (platformKind == PlatformKind.DESKTOP) 12.dp else 0.dp)) {
                                CommunityPanel(
                                    mangaId = current.id, sources = reviews, loading = reviewsLoading,
                                    openRevision = commentsOpenRevision,
                                    onMore = onReviewMore, onRetry = onReviewRetry, onMatch = onReviewMatch,
                                    onRelink = onReviewRelink, loadReplies = loadReplies,
                                    onScrollTarget = { commentsList = it }, backdrop = readingBackdrop,
                                    captureBackdrop = false,
                                    active = active && targetSection == activeSection, bottomPadding = paneBottomPadding,
                                    filterOpen = commentFilterOpen, onFilterDismiss = { commentFilterOpen = false },
                                    onFilterChanged = { commentFilterChanged = it },
                                )
                            }
                            if (platformKind == PlatformKind.DESKTOP) commentsList?.let { list ->
                                DesktopVerticalScrollbar(list,
                                    Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(informationTone)) {
    BoxWithConstraints(
        Modifier.fillMaxSize().align(Alignment.TopCenter)
            .screenFitViewport().imePadding(),
    ) {
        val density = LocalDensity.current
        val viewportWidth = minOf(maxWidth, MangaLayout.DetailWidth)
        val fontScale = density.fontScale.coerceAtLeast(1f)
        val desktopDetail = platformKind == PlatformKind.DESKTOP &&
            maxWidth >= 840.dp * fontScale.coerceAtMost(1.5f)
        val desktopWidth = minOf(maxWidth, 1440.dp)
        val summaryWidth = (desktopWidth * .34f).coerceIn(300.dp, 380.dp)
        // Retain the largest real dock clearance: condensing must not move the last chapter rows.
        val bottomInset = screenFitContentInsets().asPaddingValues().calculateBottomPadding()
        val readingInset = maxOf((38f * fontScale + 10f).coerceAtLeast(56f).dp,
            with(density) { measuredDockHeight.toDp() }) + 16.dp + bottomInset
        val statusInset = screenFitContentInsets().asPaddingValues().calculateTopPadding()
        val availableHeight = (maxHeight - statusInset).coerceAtLeast(0.dp)
        val compactHero = desktopDetail || availableHeight < 600.dp
        val collapsedHero = !desktopDetail && availableHeight < 420.dp
        val seamHeight = if (collapsedHero) 0.dp else 6.dp
        // Safety belongs to the artwork's content, not a clipped viewport below the toolbar.
        val toolbarClearance = statusInset + 56.dp
        val toolbarPixels = with(density) { toolbarClearance.roundToPx() }
        val artworkHeadroom = toolbarClearance + if (compactHero) 8.dp else 16.dp
        val naturalHeroHeight = remember(current.id, viewportWidth, density) { mutableIntStateOf(0) }
        // Foreground measurement publishes this frame's actual height. Background layers
        // measure afterwards and draw behind it, without a zero-height first-frame handoff.
        val header = key(current.id) { rememberTopAppBarState(initialHeightOffsetLimit = 0f) }
        var chapterHeaderOffset by remember(current.id) { mutableFloatStateOf(0f) }
        var commentsOwnHeader by remember(current.id) { mutableStateOf(false) }
        var contentsRequestedWhileInComments by remember(current.id) { mutableStateOf(false) }
        // The artwork remains the same measured node throughout the handoff. Animate its
        // existing header offset rather than switching the hero to a separate layout.
        // A rapid reverse starts at the last drawn position, not at either endpoint.
        LaunchedEffect(active, activeSection, header, naturalHeroHeight.intValue, desktopDetail, motion) {
            if (!active || desktopDetail || naturalHeroHeight.intValue <= 0) return@LaunchedEffect
            if (activeSection == 1 && !commentsOwnHeader) {
                chapterHeaderOffset = header.heightOffset
                commentsOwnHeader = true
            }
            if (activeSection != 1 && !commentsOwnHeader) return@LaunchedEffect
            val destination = (if (activeSection == 1 || contentsRequestedWhileInComments)
                header.heightOffsetLimit else chapterHeaderOffset).coerceIn(header.heightOffsetLimit, 0f)
            val start = header.heightOffset.coerceIn(header.heightOffsetLimit, 0f)
            if (!motion || start == destination) {
                header.heightOffset = destination
            } else {
                Animatable(start).animateTo(destination, spring(
                    dampingRatio = MangaMotion.NavigationDamping,
                    stiffness = 240f,
                )) {
                    header.heightOffset = value.coerceIn(header.heightOffsetLimit, 0f)
                }
                header.heightOffset = destination
            }
            if (activeSection == 0) {
                commentsOwnHeader = false
                contentsRequestedWhileInComments = false
            }
        }
        val dockScrollTravel = with(density) { 96.dp.toPx() }
        val dockCollapse by remember(header, activeSection, catalogGrid, dockScrollTravel, naturalHeroHeight, desktopDetail) {
            derivedStateOf {
                if (desktopDetail || activeSection != 0) 0f else {
                    // Start with the first actual pixel; an unmeasured header is not a scroll.
                    val heroPixels = naturalHeroHeight.intValue.toFloat()
                    val coverTravel = if (heroPixels > 0f)
                        (-header.heightOffset).coerceIn(0f, heroPixels) else 0f
                    val coverProgress = (coverTravel / dockScrollTravel).coerceIn(0f, 1f)
                    val catalogProgress = catalogGrid?.let { grid ->
                        if (grid.firstVisibleItemIndex > 0) 1f
                        else (grid.firstVisibleItemScrollOffset / dockScrollTravel).coerceIn(0f, 1f)
                    } ?: 0f
                    maxOf(coverProgress, catalogProgress)
                }
            }
        }
        val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = header, snapAnimationSpec = null, flingAnimationSpec = rememberSplineBasedDecay(),
        )
        val scrolling = remember(behavior) {
            object : NestedScrollConnection by behavior.nestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f)
                        contentsJump?.cancel()
                    return behavior.nestedScrollConnection.onPreScroll(available, source)
                }
            }
        }
        // Chapter gestures are allowed to collapse the artwork. Comments are an
        // independent reading surface: never forward their drag/fling into this header.
        val chapterScrollConnection = if (activeSection == 0) Modifier.nestedScroll(scrolling) else Modifier
        val emptyCatalogScroll = rememberScrollableState { 0f }
        val artworkScroll: ScrollableState = catalogGrid ?: emptyCatalogScroll
        val openContents: () -> Unit = {
            val fromComments = activeSection == 1
            // Opening the chapter catalogue from comments goes to the collapsed
            // chapter header; tapping its ordinary tab restores the prior hero.
            contentsRequestedWhileInComments = fromComments
            section = 0
            dockHidden = false
            contentsJump?.cancel()
            if (!desktopDetail && !fromComments) contentsJump = scope.launch {
                animate(header.heightOffset, header.heightOffsetLimit,
                    animationSpec = tween(if (motion) 320 else 0, easing = MangaMotion.EnterEasing)) { value, _ ->
                    header.heightOffset = value
                }
            }
        }
        // A fixed atmosphere sits behind the moving book and opaque catalogue plane.
        // Floating controls sample the final composed scene through the existing Haze source.
        Box(Modifier.fillMaxSize().mangaHazeSource(readingBackdrop).background(informationTone)) {
            if (desktopDetail) {
                Row(Modifier.width(desktopWidth).fillMaxHeight().align(Alignment.TopCenter)
                    .padding(start = 20.dp, end = 20.dp, top = toolbarClearance + 8.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.width(summaryWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val summaryScroll = rememberScrollState()
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Column(Modifier.fillMaxSize().verticalScroll(summaryScroll).padding(end = 12.dp)) {
                                HeroBookInformation(current, coverPainter, artworkBackdrop, informationTone,
                                    compact = true, artworkHeadroom = 0.dp,
                                    onIntroduction = { fullIntroduction = true },
                                    onRatings = { fullRatings = true },
                                    onAuthor = onAuthor?.let { action -> { name -> action(current, name) } },
                                    onTag = onTag?.let { action -> { tag -> action(current, tag) } }, collapse = { 0f })
                            }
                            DesktopVerticalScrollbar(summaryScroll,
                                Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
                        }
                        DetailReadingDock(mangaId = current.id, backdrop = readingBackdrop,
                            resume = resume, firstChapter = chapters.firstOrNull(), loading = loading,
                            hidden = false, active = active, persistent = true,
                            modifier = Modifier.fillMaxWidth(),
                            onContinue = onContinue, onRead = onRead, onRestart = onRestart,
                            onContents = openContents, preferredResumeChapter = preferredResumeChapter,
                            onContinuePreferred = onContinuePreferred, cloudProgress = copyProgress,
                            onAdoptCloudProgress = onAdoptCloudProgress, onRetryCloudProgress = onRetryCloudProgress)
                    }
                    sectionPane(Modifier.weight(1f).fillMaxHeight().background(informationTone),
                        16.dp + bottomInset)
                }
                Box(Modifier.width(desktopWidth).align(Alignment.TopCenter)) {
                    ArtworkToolbar(header = header, compact = true, title = current.title,
                        backdrop = readingBackdrop, favorite = isFavorite,
                        coverFailed = coverFailed, active = active, coverLight = lightDetail,
                        comments = activeSection == 1, commentCount = commentCount,
                        sceneColor = informationTone.copy(alpha = .80f),
                        onBack = onBack, onIntroduction = { fullIntroduction = true },
                        onToggleFavorite = {
                            feedback.perform(dev.mangaflow.ui.motion.FeelEvent.Confirm)
                            onToggleFavorite()
                        }, onRetryCover = { coverFailed = false; coverRetry++ })
                }
            } else {
                Column(Modifier.widthIn(max = MangaLayout.DetailWidth).fillMaxSize()
                    .align(Alignment.TopCenter).then(chapterScrollConnection)) {
                    Box(
                        Modifier.fillMaxWidth()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                if (size.height > 0f) {
                                    // The sharp poster passes behind the controls and fades only at the window edge.
                                    drawRect(Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black),
                                        endY = minOf(size.height, 28.dp.toPx()).coerceAtLeast(1f),
                                    ), blendMode = BlendMode.DstIn)
                                }
                            }
                            .scrollable(artworkScroll, Orientation.Vertical,
                                reverseDirection = true, enabled = active && activeSection == 0)
                            .layout { measurable, constraints ->
                                // Read the moving offset during layout, without recomposing the whole detail.
                                // Measure the actual book content independently of the previous
                                // header height. Long titles/type remain reachable by this scroll.
                                val foreground = measurable.measure(
                                    constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
                                )
                                val expanded = if (collapsedHero) 0 else foreground.height
                                if (naturalHeroHeight.intValue != expanded) naturalHeroHeight.intValue = expanded
                                val limit = -expanded.toFloat()
                                if (header.heightOffsetLimit != limit) {
                                    contentsJump?.cancel()
                                    val offset = header.heightOffset.coerceIn(limit, 0f)
                                    header.heightOffsetLimit = limit
                                    header.heightOffset = offset
                                }
                                // Every frame of the section handoff shares one measured
                                // height and offset. The comment list moves up with the
                                // shrinking hero; it cannot pull on the hero by nested scroll.
                                val offset = header.heightOffset.roundToInt().coerceIn(-expanded, 0)
                                val visibleHeight = maxOf(toolbarPixels, expanded + offset)
                                    .coerceIn(constraints.minHeight, constraints.maxHeight)
                                layout(foreground.width, visibleHeight) {
                                    foreground.placeRelative(0, offset)
                                }
                            },
                    ) {
                        if (!collapsedHero) {
                            key(current.id, viewportWidth, density) {
                                HeroBookInformation(current, coverPainter, artworkBackdrop, informationTone,
                                    compact = compactHero, artworkHeadroom = artworkHeadroom,
                                    onIntroduction = { fullIntroduction = true },
                                    onRatings = { fullRatings = true },
                                    onAuthor = onAuthor?.let { action -> { name -> action(current, name) } },
                                    onTag = onTag?.let { action -> { tag -> action(current, tag) } },
                                    collapse = { header.collapsedFraction })
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1f).fillMaxWidth().background(informationTone)
                            // Share the grid's scroll mutex as well as its position: a new drag
                            // on the surrounding surface must cancel an existing grid fling.
                            .scrollable(artworkScroll, Orientation.Vertical,
                                reverseDirection = true, enabled = active && activeSection == 0),
                    ) {
                        Spacer(Modifier.fillMaxWidth().layout { _, constraints ->
                            val seam = (if (collapsedHero) 0f else
                                seamHeight.toPx() * (1f - header.collapsedFraction)).roundToInt()
                                .coerceIn(0, constraints.maxHeight)
                            layout(constraints.maxWidth, seam) {}
                        })
                        sectionPane(Modifier.weight(1f).fillMaxWidth(), readingInset + 16.dp)
                    }
                }
                // Keep toolbar hit targets safe while the real clear artwork scrolls under them.
                Box(Modifier.widthIn(max = MangaLayout.DetailWidth).fillMaxWidth()
                    .height(toolbarClearance + 24.dp).align(Alignment.TopCenter)
                    .drawBehind {
                        val reveal = if (collapsedHero) 1f
                            else ((header.collapsedFraction - .68f) / .32f).coerceIn(0f, 1f)
                        val end = size.height.coerceAtLeast(1f)
                        val solidEnd = (toolbarClearance.toPx() / end).coerceIn(0f, .99f)
                        drawRect(Brush.verticalGradient(
                            0f to informationTone.copy(alpha = reveal),
                            solidEnd to informationTone.copy(alpha = reveal),
                            1f to informationTone.copy(alpha = 0f),
                        ))
                    }) {
                    ArtworkToolbar(
                        header = header, compact = collapsedHero, title = current.title, backdrop = artworkBackdrop,
                        favorite = isFavorite, coverFailed = coverFailed, active = active,
                        coverLight = lightDetail,
                        comments = activeSection == 1, commentCount = commentCount,
                        sceneColor = Color.Transparent,
                        onBack = onBack, onIntroduction = { fullIntroduction = true },
                        onToggleFavorite = { feedback.perform(dev.mangaflow.ui.motion.FeelEvent.Confirm); onToggleFavorite() }, onRetryCover = {
                            coverFailed = false
                            coverRetry++
                        },
                    )
                }
            }
            // Measure after the foreground, paint before it and its toolbar.
            Box(
                Modifier.fillMaxWidth().zIndex(-1f)
                    .layout { measurable, constraints ->
                        val height = if (desktopDetail) constraints.maxHeight
                            else (naturalHeroHeight.intValue + seamHeight.roundToPx())
                                .coerceIn(constraints.minHeight, constraints.maxHeight)
                        val layer = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
                        layout(layer.width, layer.height) { layer.placeRelative(0, 0) }
                    }
                    .graphicsLayer { clip = true }
                    .mangaHazeSource(artworkBackdrop).background(atmosphereColor),
                contentAlignment = Alignment.TopCenter,
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = DetailMuted.copy(alpha = .3f),
                    modifier = Modifier.align(Alignment.Center).size(48.dp))
                if (current.coverUrl.isNotBlank()) Image(
                    painter = coverPainter, contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        scaleX = 1.12f; scaleY = 1.12f
                        alpha = appearance.strength
                    }.then(if (appearance.blur > 0f && modernPlatformBlur)
                        Modifier.hazeBlur(input = HazeInput.Content, style = atmosphereBlur) else Modifier),
                    contentScale = ContentScale.Crop,
                )
                Box(Modifier.matchParentSize().drawBehind {
                    val height = size.height.coerceAtLeast(1f)
                    val fadeEnd = (1f - 24.dp.toPx() / height).coerceIn(.65f, .96f)
                    val fadeStart = (fadeEnd - 104.dp.toPx() / height).coerceIn(.25f, .84f)
                    drawRect(Brush.verticalGradient(
                        0f to informationTone.copy(alpha = if (lightDetail) .44f else .52f),
                        fadeStart to informationTone.copy(alpha = if (lightDetail) .44f else .52f),
                        (fadeStart + (fadeEnd - fadeStart) * .55f) to
                            informationTone.copy(alpha = if (lightDetail) .72f else .78f),
                        fadeEnd to informationTone,
                        1f to informationTone,
                    ))
                })
            }
            // The catalogue's surface rises over the stationary atmosphere, including wide-window gutters.
            if (!desktopDetail) Box(Modifier.matchParentSize().zIndex(-1f).drawBehind {
                val chapterTop = maxOf(toolbarPixels.toFloat(),
                    naturalHeroHeight.intValue + header.heightOffset).coerceIn(0f, size.height)
                drawRect(informationTone, topLeft = Offset(0f, chapterTop),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height - chapterTop))
            })
        }
        if (!desktopDetail) DetailReadingDock(mangaId = current.id, backdrop = readingBackdrop,
            resume = resume, firstChapter = chapters.firstOrNull(), loading = loading,
            hidden = dockHidden, collapseProgress = { dockCollapse }, active = active,
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Bottom))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            onHeightChanged = { measuredDockHeight = maxOf(measuredDockHeight, it) },
            onContinue = onContinue, onRead = onRead, onRestart = onRestart, onContents = openContents,
            preferredResumeChapter = preferredResumeChapter, onContinuePreferred = onContinuePreferred,
            cloudProgress = copyProgress, onAdoptCloudProgress = onAdoptCloudProgress,
            onRetryCloudProgress = onRetryCloudProgress)
    }
    }
    if (fullIntroduction) DetailIntroduction(current, backdrop = readingBackdrop,
        onAuthor = onAuthor?.let { action -> { name -> fullIntroduction = false; action(current, name) } },
        onTag = onTag?.let { action -> { tag -> fullIntroduction = false; action(current, tag) } },
        onDismiss = { fullIntroduction = false })
    if (fullRatings) DetailRatingSheet(current, backdrop = readingBackdrop) { fullRatings = false }
    pendingDownload?.takeIf { active }?.let { pending ->
        key(current.id, pending.logicalId, pending.id) {
            ChapterDownloadSourceSheet(current, pending, downloadSourceEntries, readingBackdrop,
                preparations = preparingSources,
                onSelect = { variant, route ->
                    if (active && pendingDownload == pending) {
                        if (pending.variants.isEmpty()) onDownload(pending, route)
                        else onDownloadVariant(pending, variant, route)
                    }
                },
                onDismiss = { if (pendingDownload == pending) { pendingDownload = null; pendingDownloadAnchor = null } },
                anchor = pendingDownloadAnchor, repository = repository)
        }
    }
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
    sceneColor: Color,
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
    val lightSurface = colors.surface.luminance() > .5f
    val lightStatusBars by remember(header, lightSurface, compact, coverLight) {
        derivedStateOf {
            if (compact || header.collapsedFraction > .9f) lightSurface else coverLight
        }
    }
    SystemBarAppearance(lightStatusBars, lightSurface, active)
    Row(Modifier.fillMaxWidth().drawBehind { drawRect(sceneColor.copy(alpha = sceneColor.alpha * progress)) }
        .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
        .heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtworkAction(Icons.AutoMirrored.Outlined.ArrowBack, "返回", backdrop, 1f - progress, tint, glassTone, onBack)
        Column(Modifier.weight(1f).padding(horizontal = 8.dp).graphicsLayer { alpha = progress }
            .then(if (progress > .02f) Modifier else Modifier.clearAndSetSemantics { })
            .clickable(enabled = progress > .7f, role = Role.Button, onClickLabel = "查看作品信息", onClick = onIntroduction),
            verticalArrangement = Arrangement.Center) {
            // The small toolbar title crosses into its Comments identity instead
            // of swapping two different text baselines on the first tap frame.
            MotionSwap(comments, Modifier.fillMaxWidth().heightIn(min = 38.dp)) { showComments ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                    if (showComments) Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("评论", color = DetailForeground, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold)
                        commentCount?.let { Text(it, color = DetailSecondary, fontSize = 11.sp) }
                    }
                    Text(title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                        color = if (showComments) DetailSecondary else DetailForeground,
                        fontSize = if (showComments) 11.sp else 15.sp,
                        lineHeight = if (showComments) 15.sp else 20.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (coverFailed) ArtworkAction(Icons.Outlined.Refresh, "重新加载封面", backdrop, 1f - progress, tint, glassTone, onRetryCover)
        ArtworkAction(if (favorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            if (favorite) "从书架移除" else "加入书架", backdrop, 1f - progress, tint, glassTone, onToggleFavorite)
    }
}

@Composable
private fun ArtworkAction(icon: ImageVector, description: String, backdrop: HazeState, glassAlpha: Float,
    tint: Color, glassTone: Color, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(48.dp)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .motionPress(interaction, pressedScale = .965f), contentAlignment = Alignment.Center) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            LiquidGlassSurface(backdrop,
                Modifier.matchParentSize().graphicsLayer {
                    alpha = glassAlpha
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }, cornerRadius = 20.dp, elevation = 0.dp, role = GlassSurfaceRole.ARTWORK,
                interactionSource = interaction, trackPointer = true, surfaceColor = glassTone,
                softCard = false) {}
            MotionSwap(icon, Modifier.size(21.dp)) { shown ->
                Icon(shown, description, tint = tint, modifier = Modifier.size(21.dp))
            }
        }
        DesktopIconTooltip(description, interaction, enabled = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroBookInformation(
    manga: Manga,
    cover: Painter,
    backdrop: HazeState,
    informationTone: Color,
    compact: Boolean,
    artworkHeadroom: Dp,
    onIntroduction: () -> Unit,
    onRatings: () -> Unit,
    onAuthor: ((String) -> Unit)?,
    onTag: ((String) -> Unit)?,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
) {
    val infoVisible by remember(collapse) { derivedStateOf { collapse() < .98f } }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val coverSize = cover.intrinsicSize
    val actualAspect = if (coverSize.width.isFinite() && coverSize.height.isFinite() &&
        coverSize.width > 0f && coverSize.height > 0f) coverSize.width / coverSize.height else null
    val route = LocalMangaCoverTransition.current?.visibility?.transition
    val arrived = route == null || route.currentState == EnterExitState.Visible &&
        route.targetState == EnterExitState.Visible && !route.isRunning
    var targetAspect by remember(manga.id, manga.coverUrl) { mutableFloatStateOf(actualAspect ?: 2f / 3f) }
    LaunchedEffect(actualAspect, arrived) {
        if (arrived && actualAspect != null) targetAspect = actualAspect
    }
    // Late intrinsic dimensions may change the fitted frame only after the cover lands.
    // Keep the whole image visible and settle to its real ratio without a layout snap.
    val posterAspect by animateFloatAsState(targetAspect,
        if (LocalMotionEnabled.current) tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing) else snap(),
        label = "detail-poster-aspect")
    val ink = if (informationTone.luminance() > .5f) Color(0xFF242426) else Color.White
    val titleInteraction = remember(manga.id) { MutableInteractionSource() }
    val titleStyle = MaterialTheme.typography.headlineSmall.copy(
        fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
        fontSize = if (compact) 20.sp else 24.sp,
        lineHeight = if (compact) 28.sp else 32.sp,
        fontWeight = FontWeight.SemiBold,
    )
    BoxWithConstraints(modifier.fillMaxWidth().wrapContentHeight(unbounded = true)
        .padding(horizontal = 20.dp).padding(top = 12.dp + artworkHeadroom, bottom = 2.dp)
        .then(if (infoVisible) Modifier else Modifier.clearAndSetSemantics { })) {
        val contentWidth = maxWidth
        val posterWidth = minOf(contentWidth * if (density.fontScale >= 1.4f) .34f else .38f,
            224.dp, (contentWidth - 136.dp).coerceAtLeast(72.dp))
        val infoWidth = with(density) {
            (contentWidth.roundToPx() - posterWidth.roundToPx() - 16.dp.roundToPx()).coerceAtLeast(1).toDp()
        }
        val titleLayout = remember(manga.title, titleStyle, infoWidth, density) {
            measurer.measure(manga.title, style = titleStyle,
                constraints = Constraints(maxWidth = with(density) { infoWidth.roundToPx() }))
        }
        val maximumInlineLines = if (density.fontScale >= 1.4f) 3 else 5
        val stacked = infoWidth < 112.dp || titleLayout.lineCount > maximumInlineLines
        // Measure the content independently of the display-height floor so long text
        // can expand through the existing scroll without a size-feedback loop.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = if (stacked) 1 else 2,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(posterWidth).mangaSharedCover(manga.id)) {
                    LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 12.dp,
                        role = GlassSurfaceRole.ARTWORK, elevation = 0.dp, softCard = false) {
                        Box(Modifier.fillMaxWidth().padding(2.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Outlined.MenuBook, null,
                                tint = ink.copy(alpha = .16f), modifier = Modifier.size(36.dp))
                            Image(cover, contentDescription = null, contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxWidth().aspectRatio(posterAspect)
                                    .clip(RoundedCornerShape(10.dp)))
                        }
                    }
                }
                Column(Modifier.width(if (stacked) contentWidth else infoWidth),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(manga.title, color = ink, style = titleStyle,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = infoVisible,
                            interactionSource = titleInteraction, indication = null,
                            role = Role.Button, onClickLabel = "查看完整作品信息", onClick = onIntroduction)
                            .motionPress(titleInteraction, enabled = infoVisible, pressedScale = .985f))
                    AuthorNames(manga.authors, ink.copy(alpha = .82f), enabled = infoVisible, onAuthor = onAuthor)
                    val state = listOf(manga.status, manga.latestChapter.takeIf(String::isNotBlank)
                        ?.let { "更新 · $it" }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
                    if (state.isNotBlank()) Text(state, color = ink.copy(alpha = .80f),
                        fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    HeroSynopsis(manga, ink, infoVisible, onIntroduction)
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                DetailTags(manga, includeState = false, enabled = infoVisible, onTag = onTag)
                DetailRatingStrip(manga, ink.copy(alpha = .86f), enabled = infoVisible,
                    compact = true, onExpand = onRatings)
            }
        }
    }
}

@Composable
private fun HeroSynopsis(manga: Manga, ink: Color, enabled: Boolean, onIntroduction: () -> Unit) {
    val interaction = remember(manga.id) { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = enabled, role = Role.Button,
        interactionSource = interaction, indication = null,
        onClickLabel = "展开简介与作品信息", onClick = onIntroduction)
        .motionPress(interaction, enabled = enabled, pressedScale = .985f).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(manga.description.ifBlank { "作品信息" }, fontFamily = mangaFontFamily(MangaFontScene.READING), color = ink.copy(alpha = .82f),
            fontSize = 14.sp, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, null, tint = ink.copy(alpha = .55f), modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun DetailTags(manga: Manga, modifier: Modifier = Modifier, onArtwork: Boolean = false,
    includeState: Boolean = true, enabled: Boolean = true, onTag: ((String) -> Unit)?) {
    val tags = remember(manga.status, manga.tags, manga.latestChapter, includeState) {
        buildList<Pair<String, Boolean>> {
            val status = manga.status.trim()
            val update = manga.latestChapter.takeIf(String::isNotBlank)?.let { "更新 · $it" }
            if (includeState && status.isNotEmpty()) add(status to false)
            manga.tags.map(String::trim).filter { it.isNotBlank() && it != status && it != update }
                .distinct().forEach { add(it to true) }
            if (includeState && update != null) add(update to false)
        }.distinctBy { it.first }
    }
    if (tags.isNotEmpty()) Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = if (onArtwork) 0.dp else 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { tags.forEachIndexed { index, (tag, topic) ->
        DetailTag(tag, index, onArtwork, enabled,
            onClick = if (topic) onTag?.let { action -> { action(tag) } } else null)
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailIntroduction(manga: Manga, backdrop: HazeState, onAuthor: ((String) -> Unit)?,
    onTag: ((String) -> Unit)?, onDismiss: () -> Unit) {
    MotionBottomSheet(
        onDismissRequest = onDismiss,
        backdrop = backdrop, desktopCloseButton = true,
        containerColor = MaterialTheme.colorScheme.surface, contentColor = DetailForeground,
    ) { _ ->
        SelectionContainer {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), fontSize = 20.sp, lineHeight = 28.sp,
                    color = DetailForeground, fontWeight = FontWeight.SemiBold)
                AuthorNames(manga.authors, DetailSecondary, onAuthor = onAuthor)
                DetailTags(manga, onTag = onTag)
                MangaMetricBadges(manga, Modifier.fillMaxWidth(), foreground = DetailForeground)
                Text(manga.description.ifBlank { "来源暂无简介" },
                    fontFamily = mangaFontFamily(MangaFontScene.READING), fontSize = 16.sp, lineHeight = 26.sp, color = DetailSecondary)
            }
        }
    }
}

@Composable
private fun DetailTag(label: String, index: Int, onArtwork: Boolean = false,
    enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val onDark = onArtwork || DetailForeground.luminance() > .5f
    val accent = when (index % 4) {
        0 -> if (onDark) Color(0xFFD9B49E) else Color(0xFF805C43)
        1 -> if (onDark) Color(0xFFAFC4E2) else Color(0xFF486481)
        2 -> if (onDark) Color(0xFFC7B0DC) else Color(0xFF75588A)
        else -> if (onDark) Color(0xFFD5AFBB) else Color(0xFF865B6B)
    }
    val interaction = remember(label) { MutableInteractionSource() }
    if (onArtwork) {
        Box(Modifier.heightIn(min = 32.dp).then(if (onClick != null)
            Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button, onClickLabel = "搜索${label}漫画", onClick = onClick)
                .motionPress(interaction, enabled = enabled) else Modifier).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center) {
            Text(label, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                .background(accent.copy(alpha = .12f)).padding(horizontal = 6.dp, vertical = 3.dp),
                color = accent, fontSize = 12.sp, lineHeight = 18.sp,
                maxLines = 1)
        }
        return
    }
    Box(
        modifier = if (onClick != null) Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled,
                role = Role.Button, onClickLabel = "搜索${label}漫画", onClick = onClick)
            .motionPress(interaction, enabled = enabled)
            else Modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            modifier = Modifier.clip(RoundedCornerShape(7.dp))
                .background(accent.copy(alpha = if (onArtwork) .18f else if (onDark) .12f else .085f))
                .padding(horizontal = 7.dp, vertical = 4.dp),
            color = accent, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuthorNames(authors: List<String>, color: Color, enabled: Boolean = true,
    onAuthor: ((String) -> Unit)?) {
    if (authors.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        authors.filter(String::isNotBlank).distinct().forEach { name ->
            val interaction = remember(name) { MutableInteractionSource() }
            Text(name, color = color, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .then(if (onAuthor != null) Modifier.clickable(enabled = enabled, role = Role.Button,
                        interactionSource = interaction, indication = null,
                        onClickLabel = "查看${name}的漫画") { onAuthor(name) }
                        .motionPress(interaction, enabled = enabled) else Modifier)
                    .padding(vertical = 4.dp))
        }
    }
}

/** One section row owns navigation and the actions relevant to the selected section. */
@Composable
private fun DetailSections(
    selected: Int, chapterCount: Int, commentCount: String?, onSelect: (Int) -> Unit,
    filterOpen: Boolean, filterChanged: Boolean, onToggleFilter: () -> Unit,
    downloadMode: Boolean, onToggleDownload: () -> Unit,
    sourceId: String, sourceName: String, sourceCount: Int,
    sourceLoading: Boolean, sourcesChecking: Boolean, pendingSourceCount: Int,
    onManageSources: () -> Unit,
    commentsAvailable: Boolean,
    sourceComments: Boolean = false,
) {
    val labels = if (commentsAvailable) listOf("章节", "评论") else listOf("章节")
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val countStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
    val sourceLabel = (if (pendingSourceCount > 0) pendingSourceCount else sourceCount).toString()
    val sourceLabelWidth = with(density) {
        measurer.measure(sourceLabel, countStyle, maxLines = 1).size.width.toDp()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 2.dp)) {
        val availableWidth = maxWidth
        val scale = density.fontScale.coerceAtLeast(1f)
        val comments = commentsAvailable && selected == 1
        val sourceWidth = (sourceLabelWidth + 46.dp).coerceAtLeast(48.dp)
            .coerceAtMost((availableWidth - 120.dp).coerceAtLeast(48.dp))
        // Keep action width and the tab track continuous while the whole
        // section rail travels with the shrinking artwork above it.
        val actionsWidth by animateDpAsState(
            targetValue = if (comments) { if (sourceComments) 0.dp else 44.dp } else sourceWidth + 48.dp,
            animationSpec = if (LocalMotionEnabled.current)
                spring(dampingRatio = MangaMotion.NavigationDamping, stiffness = 430f)
                else snap(),
            label = "detail-section-actions-width",
        )
        val tabWidth = ((if (commentsAvailable) 164f else 82f) * scale).dp
            .coerceAtMost((availableWidth - actionsWidth - 16.dp).coerceAtLeast(0.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            MotionTabs(if (comments) 1 else 0, labels, onSelect, modifier = Modifier.width(tabWidth),
                foreground = DetailForeground, muted = DetailSecondary,
                track = Color.Transparent, indicator = DetailForeground.copy(alpha = .85f),
                compact = true, controlHeight = 40.dp, fontSize = 13.sp,
                treatment = MotionTabTreatment.Underline,
                itemContent = { index, chosen, color ->
                    val count = if (index == 0) chapterCount.takeIf { it > 0 }?.toString() else commentCount
                    Row(Modifier.semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull(labels[index], count).joinToString("，")
                    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(labels[index], color = color, fontSize = 13.sp,
                            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        count?.let { Text(it, Modifier.weight(1f, fill = false),
                            color = color.copy(alpha = .65f), fontSize = 10.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                })
            Spacer(Modifier.weight(1f))
            MotionSwap(comments, Modifier.width(actionsWidth).height(44.dp)) { showingComments ->
                Row(Modifier.fillMaxWidth().motionInputGate(showingComments == comments),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!showingComments) DetailSourceAction(
                        sourceId, sourceName, sourceCount, pendingSourceCount,
                        sourceLoading, sourcesChecking, sourceWidth, onManageSources)
                    if (!showingComments || !sourceComments) DetailAction(
                        icon = if (showingComments) Icons.Outlined.FilterList else if (downloadMode) Icons.Outlined.Check else Icons.Outlined.Download,
                        description = if (showingComments) {
                            if (filterOpen) "收起评论筛选" else "筛选评论"
                        } else if (downloadMode) "退出连续下载" else "连续下载章节",
                        selected = if (showingComments) filterOpen || filterChanged else downloadMode,
                        onClick = if (showingComments) onToggleFilter else onToggleDownload,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailSourceAction(
    sourceId: String, sourceName: String, sourceCount: Int, pendingSourceCount: Int,
    sourceLoading: Boolean, sourcesChecking: Boolean, width: Dp, onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pending = pendingSourceCount > 0
    val color = if (pending) MaterialTheme.colorScheme.primary else DetailSecondary
    Row(Modifier.width(width).height(44.dp).clip(RoundedCornerShape(10.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "管理漫画来源", onClick = onClick)
        .motionPress(interaction, pressedScale = .96f)
        .semantics(mergeDescendants = true) {
            contentDescription = "${sourceName}，漫画来源"
            stateDescription = listOfNotNull(
                "已合并 ${sourceCount} 个来源",
                "待确认 ${pendingSourceCount} 个来源".takeIf { pending },
                "正在准备来源".takeIf { sourceLoading },
                "正在检查来源".takeIf { sourcesChecking },
            ).joinToString("，")
        }.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BodySourceMark(sourceId, sourceName, Modifier.size(17.dp), muted = true)
        Text((if (pending) pendingSourceCount else sourceCount).toString(),
            Modifier.weight(1f), color = color, fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(13.dp), contentAlignment = Alignment.Center) {
            when {
                pending -> Icon(Icons.Outlined.Info, null, Modifier.size(13.dp), tint = color)
                sourceLoading || sourcesChecking -> MotionBusyIndicator(Modifier.size(11.dp),
                    color = color, strokeWidth = 1.2.dp)
                else -> Icon(Icons.Outlined.ExpandMore, null, Modifier.size(13.dp), tint = color)
            }
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
        Modifier.size(44.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                enabled = enabled, onClick = onClick)
            .motionPress(interaction, enabled = enabled, pressedScale = .94f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) DetailForeground.copy(alpha = .10f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        MotionSwap(icon, Modifier.size(19.dp)) { currentIcon ->
            Icon(currentIcon, description,
                tint = if (!enabled) DetailMuted.copy(alpha = .3f) else if (selected) DetailForeground else DetailSecondary,
                modifier = Modifier.size(19.dp))
        }
    }
}
