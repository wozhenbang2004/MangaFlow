package dev.mangaflow.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.data.ReviewRepository
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReaderPreferences
import dev.mangaflow.storage.ReaderTheme
import dev.mangaflow.storage.ReadingMode
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.rememberDelayedBusy
import java.io.File
import java.net.URI
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableImageState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    manga: Manga,
    chapters: List<Chapter>,
    initialChapterId: String,
    repository: MangaRepository,
    libraryStore: LibraryStore,
    downloadStore: DownloadStore,
    communityRepository: CopyCommunityRepository,
    reviewRepository: ReviewRepository,
    onBack: () -> Unit,
    onRequestNotificationPermission: () -> Unit = {},
    onChapterVisible: (Manga, Chapter) -> Unit = { _, _ -> },
    onNearChapterEnd: (Manga, Chapter) -> Unit = { _, _ -> },
    chaptersLoading: Boolean = false,
    chaptersError: String? = null,
    onRetryChapters: () -> Unit = {},
    active: Boolean = true,
) {
    val preferences by libraryStore.preferences.collectAsState()
    val downloads by downloadStore.entries.collectAsState()
    var chapterId by rememberSaveable(manga.id) { mutableStateOf(initialChapterId) }
    val chapter = chapters.firstOrNull { it.id == chapterId }
        ?: Chapter(chapterId, libraryStore.progressFor(manga.id)?.takeIf { it.chapterId == chapterId }?.chapterTitle ?: "正在阅读", 0)
    val discussionState = rememberChapterDiscussionState(manga, chapter, communityRepository, reviewRepository)
    val siblings = remember(chapters, chapter.group) { chapters.filter { it.group == chapter.group }.sortedBy(Chapter::index) }
    val hasMultipleGroups = remember(chapters) { chapters.map(Chapter::group).distinct().size > 1 }
    val chapterIndex = siblings.indexOfFirst { it.id == chapterId }
    var retry by remember(chapterId) { mutableIntStateOf(0) }
    var pages by remember(chapterId) { mutableStateOf<List<Page>>(emptyList()) }
    var loading by remember(chapterId) { mutableStateOf(true) }
    var error by remember(chapterId) { mutableStateOf<String?>(null) }
    var pageIndex by remember(chapterId) { mutableIntStateOf(0) }
    // Pixel offsets are a resume cursor, not UI state. Publishing every scroll delta
    // here used to recompose ReaderScreen and ChapterPages for every animation frame.
    val cursor = remember(chapterId) { ReaderCursor() }
    var targetPage by remember(chapterId) { mutableStateOf<Int?>(null) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showComments by remember(chapterId) { mutableStateOf(false) }
    var showSpreadAdjustment by remember(chapterId) { mutableStateOf(false) }
    var manualSingles by remember(manga.id, chapterId) {
        mutableStateOf(libraryStore.spreadSinglesFor(manga.id, chapterId))
    }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var downloadMessage by remember(chapterId) { mutableStateOf<String?>(null) }
    var displayedDownloadMessage by remember(chapterId) { mutableStateOf("") }
    var progressInteracting by remember(chapterId) { mutableStateOf(false) }
    val dimPreview = remember { mutableStateOf<Float?>(null) }
    var chapterVisibilityReported by rememberSaveable(manga.id, chapterId) { mutableStateOf(false) }
    var nextChapterPrefetched by remember(manga.id, chapterId) { mutableStateOf(false) }
    var leavingReader by remember { mutableStateOf(false) }
    val chapterVisible by rememberUpdatedState(onChapterVisible)
    val nearChapterEnd by rememberUpdatedState(onNearChapterEnd)
    val scope = rememberCoroutineScope()
    val headers = remember(manga.id, chapterId, repository) { repository.imageHeaders(manga.id, chapterId) }
    val activeDownload = downloads.firstOrNull { it.manga.id == manga.id && it.chapter.id == chapterId }
    val background = preferences.theme.background()
    val colors = MaterialTheme.colorScheme
    val lightChrome = colors.surface.luminance() > .5f
    val readerBackdrop = rememberHazeState()
    val readingChapterId = chapterId
    val resolvePage: suspend (Page) -> Page = remember(manga.id, chapterId, downloadStore) {
        { page -> downloadStore.resolveReaderPage(manga.id, readingChapterId, page) }
    }
    val retryPage: suspend (Page) -> Page = remember(manga.id, chapterId, repository, downloadStore) {
        { sourcePage ->
            val expectedEntry = downloadStore.entryFor(manga.id, readingChapterId)
            if (expectedEntry?.needsSourceRefresh == true) {
                val refreshed = repository.pages(manga.id, readingChapterId, forceRefresh = true)
                val replacement = refreshed.firstOrNull { it.index == sourcePage.index }
                    ?: throw IllegalStateException("刷新后没有找到这一页，请重新打开章节")
                downloadStore.refreshReadingDownload(manga, chapter, refreshed, headers, expectedEntry)
                if (chapterId == readingChapterId) {
                    pages = refreshed
                    pageIndex = pageIndex.coerceIn(0, refreshed.lastIndex)
                }
                downloadStore.resolveReaderPage(manga.id, readingChapterId, replacement)
            } else downloadStore.retryReaderPage(manga.id, readingChapterId, sourcePage)
        }
    }
    val motionEnabled = LocalMotionEnabled.current
    val toolbarTravel = with(LocalDensity.current) { 12.dp.roundToPx() }
    val touchExploration = rememberReaderTouchExploration()
    val delayedLoading = rememberDelayedBusy(loading)

    if (active) ReaderWindow(preferences.keepScreenOn, controlsVisible || showSettings || showChapters || showSpreadAdjustment || showComments, lightChrome)
    val leaveReader = { leavingReader = true; onBack() }
    BackHandler(enabled = active, onBack = leaveReader)
    LaunchedEffect(active) { leavingReader = !active }

    LaunchedEffect(chapterId, retry) {
        loading = true
        error = null
        try {
            downloadStore.awaitReady()
            // A stable source manifest represents the chapter, even when only some pages are saved.
            // File selection belongs to each image request and never creates a separate offline mode.
            val stored = downloadStore.storedPages(manga.id, chapterId)
            val loaded = stored ?: repository.pages(manga.id, chapterId, forceRefresh = retry > 0)
            if (loaded.isEmpty()) throw IllegalStateException("这一章还没有可读取的图片")
            val saved = libraryStore.progressFor(manga.id, chapterId)
            pageIndex = saved?.pageIndex?.coerceIn(0, loaded.lastIndex) ?: 0
            cursor.scrollOffset = saved?.scrollOffset ?: 0
            if (libraryStore.preferences.value.downloadWhileReading) {
                try { downloadStore.ensureReadingDownload(manga, chapter, loaded, headers) {
                    libraryStore.preferences.value.downloadWhileReading
                } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { downloadMessage = failure.message ?: "本章暂时未能保存，可继续阅读" }
            }
            downloadStore.prioritizeReadingPage(manga.id, chapterId, pageIndex)
            pages = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "章节暂时无法读取"
        } finally { loading = false }
    }

    LaunchedEffect(chapterId, pageIndex) {
        downloadStore.prioritizeReadingPage(manga.id, chapterId, pageIndex)
    }
    LaunchedEffect(chapterId, preferences.downloadWhileReading, loading) {
        if (!loading && preferences.downloadWhileReading && pages.isNotEmpty()) {
            try { downloadStore.ensureReadingDownload(manga, chapter, pages, headers) {
                libraryStore.preferences.value.downloadWhileReading
            } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { downloadMessage = failure.message ?: "本章暂时未能保存，可继续阅读" }
        }
    }

    LaunchedEffect(controlsVisible, showSettings, showChapters, showSpreadAdjustment, showComments, loading, error, progressInteracting, touchExploration) {
        if (controlsVisible && !showSettings && !showChapters && !showSpreadAdjustment && !showComments &&
            !loading && error == null && !progressInteracting && !touchExploration) {
            delay(5000)
            controlsVisible = false
        }
    }
    LaunchedEffect(downloadMessage) {
        downloadMessage?.let {
            displayedDownloadMessage = it
            delay(4500)
            downloadMessage = null
        }
    }
    LaunchedEffect(preferences.dimAmount) { dimPreview.value = null }
    LaunchedEffect(touchExploration) { if (touchExploration) controlsVisible = true }

    Box(Modifier.fillMaxSize()) {
        // Sample the existing artwork and dim layer; controls stay outside their own backdrop.
        Box(Modifier.fillMaxSize().hazeSource(readerBackdrop).background(background)) {
            when {
                loading -> if (delayedLoading) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = preferences.theme.foreground(), strokeWidth = 2.dp)
                    Spacer(Modifier.height(16.dp))
                    Text("正在打开章节", color = preferences.theme.foreground().copy(alpha = .6f), fontSize = 13.sp)
                }
                error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.MenuBook, null, Modifier.size(32.dp), tint = preferences.theme.foreground().copy(alpha = .5f))
                    Spacer(Modifier.height(16.dp))
                    Text(error.orEmpty(), color = preferences.theme.foreground(), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { retry++ }) { Text("重新读取", color = preferences.theme.foreground()) }
                }
                pages.isNotEmpty() -> key(chapterId, preferences.mode, preferences.doublePage, preferences.coverAlone, manualSingles) {
                    ChapterPages(
                        pages = pages,
                        headers = headers,
                        resolvePage = resolvePage,
                        retryPage = retryPage,
                        preferences = preferences,
                        manualSingles = manualSingles,
                        initialPage = pageIndex,
                        initialOffset = cursor.scrollOffset,
                        targetPage = targetPage,
                        onTargetConsumed = { targetPage = null },
                        onLocation = { index, offset -> cursor.scrollOffset = offset; pageIndex = index },
                        onSave = { index, offset -> libraryStore.saveProgress(manga, chapter, index, pages.size, offset) },
                        longImagePosition = { index -> libraryStore.longImagePositionFor(manga.id, chapter.id, index) },
                        onSaveLongImagePosition = { index, position -> libraryStore.saveLongImagePosition(manga.id, chapter.id, index, position) },
                        onVisibleImageLoaded = {
                            if (active && !leavingReader && chapterId == chapter.id && !chapterVisibilityReported) {
                                chapterVisibilityReported = true
                                chapterVisible(manga, chapter)
                            }
                        },
                        onNearEnd = {
                            val next = siblings.getOrNull(chapterIndex + 1)
                            if (active && !leavingReader && chapterId == chapter.id && !nextChapterPrefetched && next != null) {
                                nextChapterPrefetched = true
                                nearChapterEnd(manga, next)
                            }
                        },
                        onTap = { controlsVisible = !controlsVisible },
                        endContent = {
                            ChapterDiscussion(manga, chapter, communityRepository, colors.background, colors.onSurface,
                                onNext = siblings.getOrNull(chapterIndex + 1)?.let { next ->
                                    { chapterId = next.id; controlsVisible = true }
                                }, reviewRepository = reviewRepository, state = discussionState)
                        },
                    )
                }
            }

            // Read the live dim preview during drawing; dragging does not publish or persist preferences.
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = dimPreview.value ?: preferences.dimAmount)) })
        }

        AnimatedVisibility(
            controlsVisible, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(if (motionEnabled) MangaMotion.Content else 0)) +
                slideInVertically(tween(if (motionEnabled) MangaMotion.Enter else 0, easing = MangaMotion.EnterEasing)) { -toolbarTravel },
            exit = fadeOut(tween(if (motionEnabled) MangaMotion.Exit else 0)) +
                slideOutVertically(tween(if (motionEnabled) MangaMotion.Exit else 0, easing = MangaMotion.EnterEasing)) { -toolbarTravel },
        ) {
            LiquidGlassSurface(
                state = readerBackdrop,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                // Retain Surface's input shield on blank chrome without consuming child gestures.
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).pointerInput(Unit) {}.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ReaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", interactive = controlsVisible, onClick = leaveReader)
                    Column(Modifier.weight(1f).padding(start = 2.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        Text(manga.title, color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(chapter.group.takeIf { hasMultipleGroups }, chapter.title).joinToString(" · "),
                            color = colors.onSurface.copy(alpha = .86f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (preferences.mode == ReadingMode.RIGHT_TO_LEFT || preferences.mode == ReadingMode.LEFT_TO_RIGHT) {
                        TextButton(enabled = controlsVisible, onClick = {
                            cursor.scrollOffset = 0
                            libraryStore.updatePreferences(preferences.copy(doublePage = !preferences.doublePage))
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (preferences.doublePage) "双页" else "单页", color = colors.onSurface, fontSize = 12.sp)
                        }
                    }
                    ReaderIcon(Icons.Rounded.Tune, "阅读设置", interactive = controlsVisible) { showSettings = true }
                }
            }
        }

        AnimatedVisibility(
            controlsVisible, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(if (motionEnabled) MangaMotion.Content else 0)) +
                slideInVertically(tween(if (motionEnabled) MangaMotion.Enter else 0, easing = MangaMotion.EnterEasing)) { toolbarTravel },
            exit = fadeOut(tween(if (motionEnabled) MangaMotion.Exit else 0)) +
                slideOutVertically(tween(if (motionEnabled) MangaMotion.Exit else 0, easing = MangaMotion.EnterEasing)) { toolbarTravel },
        ) {
            LiquidGlassSurface(
                state = readerBackdrop,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
            ) {
                Column(Modifier.fillMaxWidth().pointerInput(Unit) {}.padding(horizontal = 8.dp, vertical = 2.dp)) {
                    if (pages.isNotEmpty()) key(chapterId) {
                        ReaderProgressSlider(
                            pageIndex = pageIndex, pageCount = pages.size, pendingPage = targetPage,
                            enabled = controlsVisible,
                            onSeek = { targetPage = it },
                            onInteractionChanged = { progressInteracting = it },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        ReaderIcon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上一章",
                            enabled = chapterIndex > 0, interactive = controlsVisible) {
                            siblings.getOrNull(chapterIndex - 1)?.let { chapterId = it.id }
                        }
                        ReaderIcon(Icons.Rounded.MenuBook, "章节目录", interactive = controlsVisible) { showChapters = true }
                        ReaderIcon(Icons.Rounded.Forum, "本章评论", enabled = pages.isNotEmpty(), interactive = controlsVisible) {
                            showComments = true
                        }
                        if (preferences.doublePage && (preferences.mode == ReadingMode.RIGHT_TO_LEFT || preferences.mode == ReadingMode.LEFT_TO_RIGHT)) {
                            TextButton(onClick = { showSpreadAdjustment = true }, enabled = controlsVisible,
                                contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Text("校正", color = colors.onSurface, fontSize = 12.sp)
                            }
                        }
                        val downloaded = activeDownload?.status == DownloadStatus.COMPLETED
                        val downloading = activeDownload?.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
                        MotionIconButton(
                            enabled = controlsVisible && pages.isNotEmpty() && !downloaded,
                            modifier = Modifier.size(48.dp),
                            onClick = {
                                if (downloading) activeDownload?.let { downloadStore.pause(it.id) }
                                else {
                                    val expectedEntry = activeDownload
                                    val chapterToSave = chapter
                                    val pagesToSave = pages
                                    onRequestNotificationPermission()
                                    scope.launch {
                                        try {
                                            val sourcePages = if (expectedEntry?.needsSourceRefresh == true)
                                                repository.pages(manga.id, chapterToSave.id, forceRefresh = true) else pagesToSave
                                            val saved = if (expectedEntry != null)
                                                downloadStore.refreshReadingDownload(manga, chapterToSave, sourcePages, headers, expectedEntry)
                                            else { downloadStore.enqueue(manga, chapterToSave, sourcePages, headers); true }
                                            if (saved) downloadMessage = "正在保存本章，可继续阅读"
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { downloadMessage = failure.message ?: "无法保存本章" }
                                    }
                                }
                            },
                        ) {
                            MotionSwap(activeDownload?.status, Modifier.size(22.dp)) { status ->
                                val busy = status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
                                Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                                    if (busy) CircularProgressIndicator(
                                        progress = { (activeDownload?.completedPages?.toFloat() ?: 0f) /
                                            (activeDownload?.totalPages ?: 1).coerceAtLeast(1) },
                                        modifier = Modifier.size(22.dp), strokeWidth = 1.5.dp,
                                        color = colors.onSurface.copy(alpha = .65f), trackColor = colors.onSurface.copy(alpha = .10f),
                                    )
                                    Icon(when {
                                        busy -> Icons.Rounded.Pause
                                        status == DownloadStatus.COMPLETED -> Icons.Rounded.Check
                                        status == DownloadStatus.FAILED -> Icons.Rounded.Refresh
                                        else -> Icons.Rounded.Download
                                    }, when {
                                        busy -> "暂停下载，本章已保存 ${activeDownload?.completedPages ?: 0} 页"
                                        status == DownloadStatus.COMPLETED -> "本章已下载"
                                        status == DownloadStatus.FAILED -> "重试下载"
                                        else -> "保存本章"
                                    }, Modifier.size(if (busy) 12.dp else 21.dp), tint = colors.onSurface.copy(alpha = .75f))
                                }
                            }
                        }
                        ReaderIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下一章",
                            enabled = chapterIndex >= 0 && chapterIndex < siblings.lastIndex, interactive = controlsVisible) {
                            siblings.getOrNull(chapterIndex + 1)?.let { chapterId = it.id }
                        }
                    }
                }
            }
        }

        if (!controlsVisible && pages.isNotEmpty()) Text(
            "${pageIndex + 1} / ${pages.size}", color = colors.onSurfaceVariant, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility).padding(12.dp)
                .clip(RoundedCornerShape(8.dp)).background(colors.surface.copy(alpha = .9f)).padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable { controlsVisible = true },
        )
        AnimatedVisibility(
            visible = downloadMessage != null,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(if (motionEnabled) MangaMotion.Content else 0)),
            exit = fadeOut(tween(if (motionEnabled) MangaMotion.Exit else 0)),
        ) {
            ReaderSurface(Modifier.padding(32.dp), shape = RoundedCornerShape(16.dp)) {
                Text(downloadMessage ?: displayedDownloadMessage, Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    color = colors.onSurface, fontSize = 13.sp)
            }
        }
    }
    ReaderSettingsDialog(visible = showSettings, preferences = preferences, onDimPreview = { dimPreview.value = it }, update = { next ->
        val downloadChoiceChanged = next.downloadWhileReading != preferences.downloadWhileReading
        if (next.mode != preferences.mode || next.doublePage != preferences.doublePage || next.coverAlone != preferences.coverAlone) {
            cursor.scrollOffset = 0
        }
        libraryStore.updatePreferences(next)
        if (downloadChoiceChanged) {
            if (!next.downloadWhileReading) {
                downloadStore.entryFor(manga.id, chapter.id)
                    ?.takeIf { it.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING) }
                    ?.let { downloadStore.pause(it.id) }
            } else if (pages.isNotEmpty()) {
                // An explicit switch-on can resume a paused/deleted task; automatic effects cannot.
                val chapterToSave = chapter
                val pagesToSave = pages
                val expectedEntry = activeDownload
                onRequestNotificationPermission()
                scope.launch {
                    try {
                        val sourcePages = if (expectedEntry?.needsSourceRefresh == true)
                            repository.pages(manga.id, chapterToSave.id, forceRefresh = true) else pagesToSave
                        if (!libraryStore.preferences.value.downloadWhileReading) return@launch
                        if (expectedEntry != null)
                            downloadStore.refreshReadingDownload(manga, chapterToSave, sourcePages, headers, expectedEntry) {
                                libraryStore.preferences.value.downloadWhileReading
                            }
                        else downloadStore.enqueue(manga, chapterToSave, sourcePages, headers) {
                            libraryStore.preferences.value.downloadWhileReading
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { downloadMessage = failure.message ?: "本章暂时未能保存，可继续阅读" }
                }
            }
        }
    }) {
        dimPreview.value?.takeIf { it != preferences.dimAmount }?.let { amount ->
            libraryStore.updatePreferences(preferences.copy(dimAmount = amount))
        }
        showSettings = false
    }
    ReaderSpreadDialog(
        visible = showSpreadAdjustment && pages.isNotEmpty(),
        pages = pages,
        currentPage = pageIndex,
        preferences = preferences,
        manualSingles = manualSingles,
        onCoverChange = { cover ->
            cursor.scrollOffset = 0
            libraryStore.updatePreferences(preferences.copy(coverAlone = cover))
        },
        onCorrection = { next ->
            cursor.scrollOffset = 0
            manualSingles = next
            libraryStore.saveSpreadSingles(manga.id, chapterId, next)
        },
        onDismiss = { showSpreadAdjustment = false },
    )
    ReaderChaptersDialog(
        visible = showChapters, chapters = chapters, currentId = chapterId,
        downloads = downloads.filter { it.manga.id == manga.id },
        loading = chaptersLoading, error = chaptersError, onRetry = onRetryChapters,
        onSelect = { chapterId = it; showChapters = false; controlsVisible = true },
        onDismiss = { showChapters = false },
    )
    if (showComments) ChapterDiscussion(
        manga, chapter, communityRepository, colors.background, colors.onSurface,
        onNext = null, reviewRepository = reviewRepository,
        previewVisible = false, onClose = { showComments = false }, state = discussionState,
    )
}

@OptIn(FlowPreview::class)
@Composable
private fun ChapterPages(
    pages: List<Page>,
    headers: Map<String, String>,
    resolvePage: suspend (Page) -> Page,
    retryPage: suspend (Page) -> Page,
    preferences: ReaderPreferences,
    manualSingles: Set<Int>,
    initialPage: Int,
    initialOffset: Int,
    targetPage: Int?,
    onTargetConsumed: () -> Unit,
    onLocation: (Int, Int) -> Unit,
    onSave: (Int, Int) -> Unit,
    longImagePosition: (Int) -> Float?,
    onSaveLongImagePosition: (Int, Float) -> Unit,
    onVisibleImageLoaded: () -> Unit,
    onNearEnd: () -> Unit,
    onTap: () -> Unit,
    endContent: @Composable () -> Unit,
) {
    val save by rememberUpdatedState(onSave)
    val location by rememberUpdatedState(onLocation)
    val visibleImageLoaded by rememberUpdatedState(onVisibleImageLoaded)
    val nearEnd by rememberUpdatedState(onNearEnd)
    val loadedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val composedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val prefetchRequests = remember { mutableStateMapOf<Int, Disposable>() }
    val startPage = remember { initialPage.coerceIn(0, pages.lastIndex) }
    val startOffset = remember { initialOffset.coerceAtLeast(0) }
    if (preferences.mode == ReadingMode.VERTICAL || preferences.mode == ReadingMode.WEBTOON) {
        val list = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
        var restoring by remember { mutableStateOf(startOffset > 0) }
        var targetMeasured by remember { mutableStateOf(pages[startPage].width > 0 && pages[startPage].height > 0) }
        LaunchedEffect(restoring, targetMeasured) {
            if (restoring && targetMeasured) {
                withFrameNanos { }
                list.scrollToItem(startPage, startOffset)
                restoring = false
            }
        }
        LaunchedEffect(list) {
            snapshotFlow { list.isScrollInProgress }.collect { moving -> if (moving && !targetMeasured) restoring = false }
        }
        LaunchedEffect(list) {
            snapshotFlow {
                val layout = list.layoutInfo
                if (restoring) -1 else layout.visibleItemsInfo.lastOrNull { item ->
                    loadedPages[item.index] == true && item.size > 0 &&
                        item.offset < layout.viewportEndOffset &&
                        item.offset + item.size > layout.viewportStartOffset
                }?.index ?: -1
            }.distinctUntilChanged().collect { index ->
                if (index >= 0) {
                    visibleImageLoaded()
                    if (index >= pages.lastIndex - 3) nearEnd()
                }
            }
        }
        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex) to
                if (list.firstVisibleItemIndex <= pages.lastIndex) list.firstVisibleItemScrollOffset else 0 }
                .distinctUntilChanged().collect { (index, offset) -> if (!restoring) location(index, offset) }
        }
        LaunchedEffect(list) {
            snapshotFlow { Triple(list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex),
                if (list.firstVisibleItemIndex <= pages.lastIndex) list.firstVisibleItemScrollOffset else 0, restoring) }
                .debounce(450).distinctUntilChanged().collect { (index, offset, restoringNow) -> if (!restoringNow) save(index, offset) }
        }
        LaunchedEffect(targetPage) {
            targetPage?.let { restoring = false; list.scrollToItem(it.coerceIn(0, pages.lastIndex)); onTargetConsumed() }
        }
        DisposableEffect(list) {
            onDispose { if (!restoring) save(list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex),
                if (list.firstVisibleItemIndex <= pages.lastIndex) list.firstVisibleItemScrollOffset else 0) }
        }
        AdjacentPagePrefetch(pages, headers, preferences.mode, composedPages, prefetchRequests, resolvePage) {
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.index in pages.indices }
            val anchor = list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex)
            (visible.firstOrNull()?.index ?: anchor) to (visible.lastOrNull()?.index ?: anchor)
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (preferences.mode == ReadingMode.VERTICAL && preferences.pageGap) 6.dp else 0.dp)) {
            itemsIndexed(pages, key = { index, page -> "${page.index}:$index" }, contentType = { _, _ -> "page" }) { index, page ->
                ReaderPage(
                    page, headers, resolvePage = resolvePage, retryPage = retryPage, paged = false, continuous = preferences.mode == ReadingMode.WEBTOON, onTap = onTap,
                    onMeasured = {
                        loadedPages[index] = true
                        if (index == startPage) targetMeasured = true
                    },
                    longImagePosition = { longImagePosition(index) },
                    onSaveLongImagePosition = { onSaveLongImagePosition(index, it) },
                    onComposed = { present -> if (present) composedPages[index] = true else composedPages.remove(index) },
                    prefetch = prefetchRequests[index],
                )
            }
            item("chapter-end", contentType = "chapter-end") {
                endContent()
            }
        }
    } else {
        val groups = remember(pages, preferences.doublePage, preferences.coverAlone, manualSingles) {
            if (preferences.doublePage) pageSpreads(pages, preferences.coverAlone, manualSingles)
            else pages.indices.map { PageSpread(it) }
        }
        val startingGroup = remember(groups, startPage) {
            groups.indexOfFirst { it.contains(startPage) }.coerceAtLeast(0)
        }
        val pager = rememberPagerState(initialPage = startingGroup, pageCount = { groups.size + 1 })
        LaunchedEffect(pager, groups) {
            snapshotFlow { groups.getOrNull(pager.currentPage)?.first ?: pages.lastIndex }
                .distinctUntilChanged().collect { index -> location(index, 0) }
        }
        LaunchedEffect(pager, groups) {
            snapshotFlow { groups.getOrNull(pager.settledPage)?.first ?: pages.lastIndex }
                .distinctUntilChanged().collect { index -> save(index, 0) }
        }
        LaunchedEffect(pager, groups) {
            snapshotFlow {
                groups.getOrNull(pager.currentPage)?.let { group ->
                    if (loadedPages[group.first] == true || (group.second != null && loadedPages[group.second] == true)) group.last
                    else -1
                } ?: -1
            }.distinctUntilChanged().collect { lastVisible ->
                if (lastVisible >= 0) {
                    visibleImageLoaded()
                    if (lastVisible >= pages.lastIndex - 3) nearEnd()
                }
            }
        }
        LaunchedEffect(targetPage, groups) {
            targetPage?.let { imageIndex ->
                val groupIndex = groups.indexOfFirst { it.contains(imageIndex.coerceIn(0, pages.lastIndex)) }
                if (groupIndex >= 0) pager.scrollToPage(groupIndex)
                onTargetConsumed()
            }
        }
        DisposableEffect(pager, groups) {
            onDispose { save(groups.getOrNull(pager.settledPage)?.first ?: pages.lastIndex, 0) }
        }
        AdjacentPagePrefetch(pages, headers, preferences.mode, composedPages, prefetchRequests, resolvePage) {
            val shown = groups.getOrNull(pager.currentPage)
            (shown?.first ?: pages.lastIndex) to (shown?.last ?: pages.lastIndex)
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = preferences.mode == ReadingMode.RIGHT_TO_LEFT,
            beyondViewportPageCount = 1,
            key = { index -> groups.getOrNull(index)?.first?.let { "image:$it" } ?: "chapter-end" },
        ) { index ->
            val group = groups.getOrNull(index)
            if (group != null) {
                val renderImage: @Composable (Int, Modifier, Alignment) -> Unit = { imageIndex, modifier, alignment ->
                    Box(modifier) {
                        ReaderPage(
                            pages[imageIndex], headers, resolvePage = resolvePage, retryPage = retryPage, paged = true, pagedAlignment = alignment, onTap = onTap,
                            onMeasured = { loadedPages[imageIndex] = true },
                            onComposed = { present -> if (present) composedPages[imageIndex] = true else composedPages.remove(imageIndex) },
                            prefetch = prefetchRequests[imageIndex],
                        )
                    }
                }
                val second = group.second
                if (second != null) {
                    // Pair the image edges at the spine, leaving unused space outside the spread.
                    // Reading direction, rather than the system locale, selects the physical sides.
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Absolute.Left,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val rightToLeft = preferences.mode == ReadingMode.RIGHT_TO_LEFT
                        renderImage(
                            if (rightToLeft) second else group.first,
                            Modifier.weight(1f).fillMaxHeight(),
                            AbsoluteAlignment.CenterRight,
                        )
                        renderImage(
                            if (rightToLeft) group.first else second,
                            Modifier.weight(1f).fillMaxHeight(),
                            AbsoluteAlignment.CenterLeft,
                        )
                    }
                } else {
                    renderImage(group.first, Modifier.fillMaxSize(), Alignment.Center)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item("chapter-discussion", contentType = "chapter-end") { endContent() }
                }
            }
        }
    }
}

private class ReaderCursor(var scrollOffset: Int = 0)
private class LongImageCursor(var position: Float? = null)

private data class PrefetchWindow(val first: Int, val last: Int, val composed: Set<Int>, val inFlight: Set<Int>)

/** Telephoto preloads tiles inside an image, not upcoming comic pages. */
@OptIn(ExperimentalCoilApi::class)
@Composable
private fun AdjacentPagePrefetch(
    pages: List<Page>,
    headers: Map<String, String>,
    mode: ReadingMode,
    composedPages: Map<Int, Boolean>,
    requests: MutableMap<Int, Disposable>,
    resolvePage: suspend (Page) -> Page,
    visibleRange: () -> Pair<Int, Int>,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val visible by rememberUpdatedState(visibleRange)
    LaunchedEffect(pages, headers, mode, imageLoader, resolvePage) {
        val finished = mutableSetOf<Int>()
        var previousFirst = -1
        var direction = 1
        try {
            snapshotFlow {
                val (first, last) = visible()
                PrefetchWindow(first, last, composedPages.keys.toSet(), requests.keys.toSet())
            }.distinctUntilChanged().collect { window ->
                val active = window.composed + (window.first..window.last)
                val nextDirection = when {
                    previousFirst < 0 || window.first == previousFirst -> direction
                    window.first > previousFirst -> 1
                    else -> -1
                }
                if (nextDirection != direction) {
                    // A request handed to a composed/visible page is now foreground work.
                    // Do not cancel those bytes merely because the reader changes direction.
                    requests.keys.filter { it !in active }.forEach { requests.remove(it)?.dispose() }
                    finished.clear()
                }
                direction = nextDirection
                previousFirst = window.first
                val candidates = if (direction > 0) listOf(window.last + 1, window.last + 2, window.first - 1)
                    else listOf(window.first - 1, window.first - 2, window.last + 1)
                val desired = candidates.filter { index ->
                    index in pages.indices && index !in window.composed && pages[index].url.startsWith("https:")
                }.toSet()
                val retained = desired + active
                requests.keys.filter { it !in retained }.forEach { index -> requests.remove(index)?.dispose() }
                finished.retainAll(retained)
                for (index in desired) {
                    if (index !in requests && index !in finished && requests.size < 3) {
                        val resource = try { resolvePage(pages[index]) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { finished += index; continue }
                        // Persistent downloads already have a file for Telephoto to read.
                        if (!resource.url.startsWith("https:")) { finished += index; continue }
                        // Fetch original bytes into Coil's existing disk cache. Never cache the
                        // empty decoder image in memory or report it as a visibly loaded page.
                        val request = imageLoader.enqueue(
                            readerImageRequest(context, resource, headers)
                                .size(1)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .decoderFactory(BlackholeDecoder.Factory())
                                .build(),
                        )
                        requests[index] = request
                        launch {
                            val result = try { request.job.await() }
                            catch (_: CancellationException) { return@launch }
                            if (requests[index] === request) {
                                finished += index
                                // Keep a failed prefetch available for its visible image to report.
                                // Dropping it here would immediately replay the same failed HTTP call.
                                if (result !is ErrorResult) requests.remove(index)
                            }
                        }
                    }
                }
            }
        } finally {
            val pending = requests.values.toList()
            requests.clear()
            pending.forEach(Disposable::dispose)
        }
    }
}

private fun readerImageRequest(context: Context, page: Page, headers: Map<String, String>): ImageRequest.Builder =
    ImageRequest.Builder(context)
        .data(if (page.url.startsWith("file:")) File(URI(page.url)) else page.url)
        .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build())

@OptIn(FlowPreview::class)
@Composable
private fun ReaderPage(
    page: Page,
    headers: Map<String, String>,
    resolvePage: suspend (Page) -> Page,
    retryPage: suspend (Page) -> Page,
    paged: Boolean,
    continuous: Boolean = false,
    pagedAlignment: Alignment = Alignment.Center,
    onTap: () -> Unit,
    onMeasured: () -> Unit,
    longImagePosition: () -> Float? = { null },
    onSaveLongImagePosition: (Float) -> Unit = {},
    onComposed: (Boolean) -> Unit = {},
    prefetch: Disposable? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.coerceAtLeast(320).dp
    val measured by rememberUpdatedState(onMeasured)
    val resolve by rememberUpdatedState(resolvePage)
    val retrySource by rememberUpdatedState(retryPage)
    val composed by rememberUpdatedState(onComposed)
    val readLongImagePosition by rememberUpdatedState(longImagePosition)
    val saveLongImagePosition by rememberUpdatedState(onSaveLongImagePosition)
    // Lazy layout recycling must not collapse a measured page back to its placeholder height.
    var aspect by rememberSaveable(page.index) { mutableFloatStateOf(if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height else 0.7f) }
    var error by remember(page.url) { mutableStateOf<String?>(null) }
    var loading by remember(page.url) { mutableStateOf(true) }
    var reload by remember(page.url) { mutableIntStateOf(0) }
    var resolvedPage by remember(page.url) { mutableStateOf<Page?>(null) }
    LaunchedEffect(page.url, reload) {
        loading = true
        error = null
        resolvedPage = null
        try {
            resolvedPage = if (reload > 0) retrySource(page) else resolve(page)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // A failed persistent fetch must not immediately repeat the same HTTP request in Coil.
            loading = false
            error = failure.message ?: "图片暂时无法读取"
        }
    }
    val delayedLoading = rememberDelayedBusy(loading)
    var prefetchError by remember(page.url) { mutableStateOf<String?>(null) }
    var waitingForPrefetch by remember(page.url, prefetch, reload) { mutableStateOf(prefetch != null && reload == 0) }
    LaunchedEffect(page.url, prefetch, reload) {
        prefetchError = null
        if (prefetch != null && reload == 0) {
            try {
                val result = prefetch.job.await()
                if (result is ErrorResult) {
                    prefetchError = result.throwable.message ?: "图片暂时无法读取"
                    loading = false
                }
            } catch (cancelled: CancellationException) {
                // A superseded background prefetch may be cancelled while this page is still active.
                currentCoroutineContext().ensureActive()
            }
        }
        waitingForPrefetch = false
    }
    DisposableEffect(page.url) {
        composed(true)
        onDispose { composed(false) }
    }
    BoxWithConstraints(if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        val previewWidth = with(density) { maxWidth.roundToPx() }.coerceIn(1, 1600)
        val previewHeight = with(density) { screenHeight.roundToPx() }.coerceIn(1, 4096)
        val resource = resolvedPage
        val request = remember(resource?.url, headers, reload, previewWidth, previewHeight) {
            resource?.let { requested -> readerImageRequest(context, requested, headers)
                // The preview need only fit this viewport. Telephoto keeps the original
                // disk file for full-quality visible tiles and pinch zoom.
                .size(previewWidth, previewHeight)
                .scale(Scale.FIT)
                .crossfade(false)
                .listener(
                    onStart = { loading = true; error = null },
                    onSuccess = { _, result ->
                        val width = result.image.width
                        val height = result.image.height
                        // Original dimensions keep the lazy item's height stable. A decoded preview
                        // may have been rounded while fitting the viewport and is only a fallback.
                        if ((page.width <= 0 || page.height <= 0) && width > 0 && height > 0) {
                            aspect = (width.toFloat() / height).coerceAtLeast(.005f)
                        }
                        loading = false
                        measured()
                    },
                    onError = { _, result ->
                        if (requested.url.startsWith("file:") && page.url.startsWith("https:")) {
                            // Files can be removed by the download screen while this image is visible.
                            // Recover just this image from its retained source address.
                            resolvedPage = page
                        } else {
                            loading = false
                            error = result.throwable.message ?: "图片暂时无法读取"
                        }
                    },
                )
                .build() }
        }
        val imageError = error ?: prefetchError.takeUnless { resource?.url?.startsWith("file:") == true }
        val naturalHeight = maxWidth / aspect
        val longImage = !paged && !continuous && naturalHeight > screenHeight * 3f
        // Sub-sampling operates within its own layout bounds. Keep exceptionally tall
        // images in a screen-sized viewport so off-screen tiles are actually unloaded.
        val pageModifier = if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(if (longImage) screenHeight else naturalHeight)
        Box(pageModifier.clipToBounds(), contentAlignment = Alignment.Center) {
            if (request != null && !waitingForPrefetch && imageError == null) key(reload) {
                val imageState = rememberZoomableImageState()
                var longImageRestored by rememberSaveable(page.index) { mutableStateOf(false) }
                val latestLongImagePosition = remember { LongImageCursor() }
                if (longImage) {
                    LaunchedEffect(imageState) {
                        snapshotFlow {
                            imageState.isImageDisplayed && !imageState.isPlaceholderDisplayed &&
                                imageState.zoomableState.contentTransformation.isSpecified
                        }.first { it }
                        if (!longImageRestored) {
                            readLongImagePosition()?.let { imageState.zoomableState.restoreLongImagePosition(it) }
                            longImageRestored = true
                        }
                    }
                    LaunchedEffect(imageState) {
                        snapshotFlow {
                            if (longImageRestored && imageState.isImageDisplayed && !imageState.isPlaceholderDisplayed) {
                                imageState.zoomableState.longImagePosition()
                            } else null
                        }.filterNotNull().distinctUntilChanged()
                            .onEach { latestLongImagePosition.position = it }
                            .debounce(450).collect { saveLongImagePosition(it) }
                    }
                    DisposableEffect(imageState) {
                        onDispose {
                            if (longImageRestored) latestLongImagePosition.position?.let(saveLongImagePosition)
                        }
                    }
                }
                ZoomableAsyncImage(
                    model = request,
                    state = imageState,
                    contentDescription = "第 ${page.index + 1} 页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (paged) ContentScale.Fit else ContentScale.FillWidth,
                    alignment = if (paged) pagedAlignment else Alignment.TopCenter,
                    gesturesEnabled = !continuous && (!longImage || longImageRestored),
                    onClick = { onTap() },
                )
            }
            if (delayedLoading && imageError == null) CircularProgressIndicator(Modifier.size(22.dp), color = Color.Gray, strokeWidth = 1.5.dp)
            if (longImage && !loading && imageError == null) Text(
                "长图可在页内上下拖动",
                color = Color.White.copy(alpha = .9f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .58f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
            if (imageError != null) Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${page.index + 1} 页未加载", color = Color.Gray, fontSize = 13.sp)
                Text(imageError, color = Color.Gray, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { reload++ }) { Text("重载这页", color = Color.Gray) }
            }
        }
    }
}

// In Telephoto 0.16.0, contentSize exposes the full height; coordinate-system bounds are viewport-clipped.
@Suppress("DEPRECATION")
private fun ZoomableState.longImagePosition(): Float? {
    val transformation = contentTransformation
    if (!transformation.isSpecified) return null
    val height = transformation.contentSize.height * transformation.scale.scaleY
    if (!height.isFinite() || height <= 0f || !transformation.offset.y.isFinite()) return null
    return ((longImageTopOffset() - transformation.offset.y) / height).coerceIn(0f, 1f)
}

@OptIn(ExperimentalTelephotoApi::class)
@Suppress("DEPRECATION")
private fun ZoomableState.longImageTopOffset(): Float {
    // The pinned library top-aligns sub-sampled tiles, but centers ordinary painters before scaling.
    if (!autoApplyTransformations) return 0f
    val viewport = coordinateSystem.viewportSize
    if (viewport.width <= 0f || viewport.height <= 0f) return 0f
    val transformation = contentTransformation
    val origin = ZoomableContentLocation.scaledInsideAndCenterAligned(transformation.contentSize)
        .location(viewport, LayoutDirection.Ltr).top
    return -origin * transformation.scale.scaleY
}

@Suppress("DEPRECATION")
private suspend fun ZoomableState.restoreLongImagePosition(position: Float) {
    val initialScale = contentTransformation.scaleMetadata.initialScale
    val fittedZoom = maxOf(initialScale.scaleX, initialScale.scaleY)
    if (!fittedZoom.isFinite() || fittedZoom <= 0f) return
    // Fresh reader sessions resume the same part of a long strip at its readable, full-width scale.
    // Telephoto's own saveable state retains pinch zoom during configuration changes.
    zoomTo(zoomFactor = fittedZoom, animationSpec = SnapSpec())
    val transformation = contentTransformation
    val height = transformation.contentSize.height * transformation.scale.scaleY
    val distance = longImageTopOffset() - position.coerceIn(0f, 1f) * height - transformation.offset.y
    if (height.isFinite() && height > 0f && distance.isFinite()) {
        panBy(offset = Offset(0f, distance), animationSpec = SnapSpec())
    }
}

@Composable
private fun ReaderProgressSlider(
    pageIndex: Int,
    pageCount: Int,
    pendingPage: Int?,
    enabled: Boolean,
    onSeek: (Int) -> Unit,
    onInteractionChanged: (Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val interactionChanged by rememberUpdatedState(onInteractionChanged)
    var preview by remember { mutableStateOf<Float?>(null) }
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val shown = (preview ?: pendingPage?.toFloat() ?: pageIndex.toFloat()).coerceIn(0f, lastPage.toFloat())
    val numberWidth = with(LocalDensity.current) { 12.sp.toDp() * (pageCount.toString().length * 2 + 3) * .65f }

    LaunchedEffect(pressed, dragged, enabled) {
        interactionChanged(enabled && (pressed || dragged))
        if (!enabled) preview = null
    }
    LaunchedEffect(interactions) {
        interactions.interactions.collect { interaction ->
            if (interaction is DragInteraction.Cancel) preview = null
        }
    }
    DisposableEffect(interactions) { onDispose { interactionChanged(false) } }

    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ReaderSeekBar(
            value = shown,
            onValueChange = { preview = it },
            onValueChangeFinished = {
                preview?.let { onSeek(it.roundToInt().coerceIn(0, lastPage)) }
                preview = null
            },
            valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f).padding(end = 10.dp),
            enabled = enabled && pageCount > 1,
            interactions = interactions,
            label = "阅读进度，第 ${shown.roundToInt() + 1} 页，共 $pageCount 页",
        )
        Row(Modifier.width(numberWidth), horizontalArrangement = Arrangement.Center) {
            Text("${shown.roundToInt() + 1}", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(" / $pageCount", color = colors.onSurface.copy(alpha = .86f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun rememberReaderTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

@Composable
private fun ReaderWindow(keepScreenOn: Boolean, controlsVisible: Boolean, lightChrome: Boolean) {
    val context = LocalContext.current
    val view = LocalView.current
    val window = remember(context) { context.activity()?.window }
    DisposableEffect(window, view) {
        val originalKeepOn = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val insets = ViewCompat.getRootWindowInsets(view)
        val oldStatusVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val oldNavigationVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val oldLightStatus = controller?.isAppearanceLightStatusBars ?: false
        val oldLightNavigation = controller?.isAppearanceLightNavigationBars ?: false
        val oldBehavior = controller?.systemBarsBehavior ?: WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        onDispose {
            if (originalKeepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.apply {
                isAppearanceLightStatusBars = oldLightStatus
                isAppearanceLightNavigationBars = oldLightNavigation
                systemBarsBehavior = oldBehavior
                if (oldStatusVisible) show(WindowInsetsCompat.Type.statusBars()) else hide(WindowInsetsCompat.Type.statusBars())
                if (oldNavigationVisible) show(WindowInsetsCompat.Type.navigationBars()) else hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
    LaunchedEffect(window, view, keepScreenOn, controlsVisible, lightChrome) {
        window?.let {
            if (keepScreenOn) it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = lightChrome
                isAppearanceLightNavigationBars = lightChrome
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (controlsVisible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
private fun ReaderSettingsDialog(
    visible: Boolean,
    preferences: ReaderPreferences,
    onDimPreview: (Float) -> Unit,
    update: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    var dimAmount by remember { mutableFloatStateOf(preferences.dimAmount) }
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val modes = listOf(ReadingMode.VERTICAL to "纵向", ReadingMode.WEBTOON to "条漫",
        ReadingMode.RIGHT_TO_LEFT to "右翻", ReadingMode.LEFT_TO_RIGHT to "左翻")
    val themes = listOf(ReaderTheme.PAPER, ReaderTheme.SEPIA, ReaderTheme.INK)
    val availableHeight = (LocalConfiguration.current.screenHeightDp - 96).coerceAtLeast(160).dp
    LaunchedEffect(visible, preferences.dimAmount) { if (visible) dimAmount = preferences.dimAmount }
    MotionDialog(visible = visible, onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReaderDialogSurface(Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth()) {
            Column(Modifier.heightIn(max = availableHeight).padding(horizontal = 18.dp)) {
                ReaderPanelHeading("阅读设置", "关闭设置", onDismiss)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("阅读方式", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    MotionTabs(
                        selected = modes.indexOfFirst { it.first == preferences.mode },
                        labels = modes.map { it.second },
                        onSelect = { update(preferences.copy(mode = modes[it].first)) },
                        foreground = colors.onSurface, muted = colors.onSurfaceVariant,
                        track = colors.onSurface.copy(alpha = .035f), indicator = colors.onSurface.copy(alpha = .08f),
                        fontSize = 12.sp, role = Role.RadioButton,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("纸面", Modifier.weight(1f), fontSize = 14.sp)
                        MotionTabs(
                            selected = themes.indexOf(preferences.theme), labels = listOf("纸白", "暖纸", "墨黑"),
                            onSelect = { update(preferences.copy(theme = themes[it])) },
                            modifier = Modifier.width(188.dp), foreground = colors.onSurface,
                            muted = colors.onSurfaceVariant, indicator = colors.onSurface.copy(alpha = .08f), fontSize = 12.sp,
                            role = Role.RadioButton,
                        )
                    }
                    ReaderToggle("保持屏幕常亮", preferences.keepScreenOn) { update(preferences.copy(keepScreenOn = it)) }
                    ReaderToggle("边看边下", preferences.downloadWhileReading, "只保存正在阅读的章节") {
                        update(preferences.copy(downloadWhileReading = it))
                    }
                    Column(Modifier.fillMaxWidth().animateContentSize(
                        animationSpec = tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing),
                    )) {
                        if (preferences.mode == ReadingMode.VERTICAL) ReaderToggle("页间留白", preferences.pageGap) {
                            update(preferences.copy(pageGap = it))
                        }
                        if (preferences.mode == ReadingMode.WEBTOON) Text("长图连续显示，无页间空隙",
                            color = colors.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                        if (preferences.mode == ReadingMode.RIGHT_TO_LEFT || preferences.mode == ReadingMode.LEFT_TO_RIGHT) {
                            ReaderToggle("双页跨页", preferences.doublePage) { update(preferences.copy(doublePage = it)) }
                            if (preferences.doublePage) ReaderToggle("封面独页", preferences.coverAlone) {
                                update(preferences.copy(coverAlone = it))
                            }
                        }
                    }
                    Column(Modifier.padding(top = 2.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("降低亮度", fontSize = 14.sp)
                            Text("${(dimAmount * 100).roundToInt()}%", color = colors.onSurfaceVariant, fontSize = 12.sp)
                        }
                        ReaderSeekBar(
                            value = dimAmount, onValueChange = { dimAmount = it; onDimPreview(it) },
                            onValueChangeFinished = { update(preferences.copy(dimAmount = dimAmount)) },
                            valueRange = 0f..0.65f, modifier = Modifier.fillMaxWidth(), label = "降低阅读亮度",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderSpreadDialog(
    visible: Boolean,
    pages: List<Page>,
    currentPage: Int,
    preferences: ReaderPreferences,
    manualSingles: Set<Int>,
    onCoverChange: (Boolean) -> Unit,
    onCorrection: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = currentPage.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
    val original = pages.getOrNull(first)
    val wide = original != null && original.width > 0 && original.height > 0 &&
        original.width.toFloat() / original.height >= 1.25f
    val colors = MaterialTheme.colorScheme
    val availableHeight = (LocalConfiguration.current.screenHeightDp - 96).coerceAtLeast(160).dp
    MotionDialog(visible = visible, onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReaderDialogSurface(Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth()) {
            Column(Modifier.heightIn(max = availableHeight).padding(horizontal = 18.dp)) {
                ReaderPanelHeading("错页校正", "关闭校正", onDismiss)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("当前从第 ${first + 1} 页开始", color = colors.onSurface, fontSize = 13.sp)
                    Text("把这一页设为独页，后续两页重新配对。原始图片顺序保持不变。",
                        color = colors.onSurfaceVariant, fontSize = 12.sp)
                    ReaderToggle("封面独页", preferences.coverAlone) { onCoverChange(it); onDismiss() }
                    if (first == 0 && preferences.coverAlone) {
                        Text("封面已经独立。关闭上方开关可让第一页参与拼页。", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    } else if (wide) {
                        Text("这一页是宽幅原图，保持整页显示。", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    } else {
                        TextButton(onClick = {
                            onCorrection(if (first in manualSingles) manualSingles - first else manualSingles + first)
                            onDismiss()
                        }) {
                            Text(if (first in manualSingles) "恢复第 ${first + 1} 页的自动拼接"
                                else "第 ${first + 1} 页独立，后续重新配对", color = colors.onSurface, fontSize = 13.sp)
                        }
                    }
                    if (manualSingles.isNotEmpty()) TextButton(onClick = { onCorrection(emptySet()); onDismiss() }) {
                        Text("清除本章调整", color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderChaptersDialog(
    visible: Boolean,
    chapters: List<Chapter>,
    currentId: String,
    downloads: List<DownloadEntry>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val groups = remember(chapters) { chapters.map(Chapter::group).distinct() }
    val currentGroup = chapters.firstOrNull { it.id == currentId }?.group ?: groups.firstOrNull().orEmpty()
    var selectedGroup by rememberSaveable { mutableStateOf(currentGroup) }
    val group = selectedGroup.takeIf { it in groups } ?: currentGroup
    val visibleChapters = remember(chapters, group) { chapters.filter { it.group == group }.sortedBy(Chapter::index) }
    val saved = remember(downloads) { downloads.associateBy { it.chapter.id } }
    val catalogBusy = rememberDelayedBusy(visible && loading)
    val availableHeight = (LocalConfiguration.current.screenHeightDp - 210).coerceAtLeast(112).dp
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    LaunchedEffect(visible, currentId, currentGroup) { if (visible) selectedGroup = currentGroup }
    MotionDialog(visible = visible, onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReaderDialogSurface(Modifier.padding(horizontal = 16.dp).widthIn(max = 600.dp).fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("章节", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("${visibleChapters.size} 章", Modifier.weight(1f).padding(start = 8.dp),
                        color = colors.onSurfaceVariant, fontSize = 12.sp)
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (catalogBusy) CircularProgressIndicator(Modifier.size(13.dp)
                            .semantics { contentDescription = "正在补全章节目录" }, color = colors.onSurfaceVariant, strokeWidth = 1.5.dp)
                    }
                    ReaderIcon(Icons.Rounded.Refresh, "刷新目录", enabled = !loading, onClick = onRetry)
                    ReaderIcon(Icons.Rounded.Close, "关闭目录", onClick = onDismiss)
                }
                if (groups.size > 1) MotionTabs(
                    selected = groups.indexOf(group), labels = groups, onSelect = { selectedGroup = groups[it] },
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    compact = true, underline = true, indicator = colors.onSurface.copy(alpha = .75f), fontSize = 12.sp,
                )
                if (!loading && error != null) Text(error, Modifier.padding(vertical = 6.dp),
                    color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MotionTabContent(
                    selected = group,
                    modifier = Modifier.fillMaxWidth().heightIn(max = minOf(450.dp, availableHeight)),
                    contentKey = { it }, order = { groups.indexOf(it) },
                    fillContent = false,
                ) { targetGroup ->
                    val active = visible && targetGroup == group
                    val targetChapters = remember(chapters, targetGroup) {
                        chapters.filter { it.group == targetGroup }.sortedBy(Chapter::index)
                    }
                    val state = rememberLazyGridState()
                    LaunchedEffect(active, currentId, targetChapters) {
                        if (active) {
                            val index = targetChapters.indexOfFirst { it.id == currentId }
                            state.scrollToItem((index - 6).coerceAtLeast(0))
                        } else state.stopScroll()
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(68.dp * fontScale), state = state,
                        modifier = Modifier.fillMaxWidth(), userScrollEnabled = active,
                        contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(targetChapters, key = Chapter::id) { chapter ->
                            val selected = chapter.id == currentId
                            val entry = saved[chapter.id]
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                                .background(colors.onSurface.copy(alpha = if (selected) .09f else .035f))
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(chapter.id) })
                                .padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(chapter.title, Modifier.weight(1f), color = colors.onSurface,
                                    fontSize = 12.sp, lineHeight = 17.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                    when (entry?.status) {
                                        DownloadStatus.COMPLETED -> Icon(Icons.Rounded.Check, "已下载", Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                                        DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING ->
                                            CircularProgressIndicator(progress = { (entry?.completedPages ?: 0).toFloat() / (entry?.totalPages ?: 1).coerceAtLeast(1) },
                                                modifier = Modifier.size(11.dp).semantics { contentDescription = "下载中" },
                                                strokeWidth = 1.dp, color = colors.onSurfaceVariant,
                                                trackColor = colors.onSurface.copy(alpha = .10f))
                                        DownloadStatus.PAUSED -> Icon(Icons.Rounded.Pause, "下载已暂停", Modifier.size(11.dp), tint = colors.onSurfaceVariant)
                                        DownloadStatus.FAILED -> Icon(Icons.Rounded.Refresh, "下载未完成", Modifier.size(11.dp), tint = colors.error)
                                        null -> if (selected) Icon(Icons.Rounded.Check, "当前章节", Modifier.size(12.dp), tint = colors.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPanelHeading(title: String, closeLabel: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        ReaderIcon(Icons.Rounded.Close, closeLabel, onClick = onDismiss)
    }
}

@Composable
private fun ReaderIcon(icon: ImageVector, label: String, enabled: Boolean = true, interactive: Boolean = true, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) .82f else .25f)
    MotionIconButton(onClick = onClick, enabled = enabled && interactive, modifier = Modifier.size(48.dp)) {
        Icon(icon, label, Modifier.size(21.dp), tint = tint)
    }
}

private fun ReaderTheme.background() = when (this) { ReaderTheme.INK -> Color(0xFF101012); ReaderTheme.PAPER -> Color(0xFFF7F6F2); ReaderTheme.SEPIA -> Color(0xFFECE4D5) }
private fun ReaderTheme.foreground() = if (this == ReaderTheme.INK) Color.White else Color(0xFF242426)
private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
