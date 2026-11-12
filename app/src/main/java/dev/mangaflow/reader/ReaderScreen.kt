package dev.mangaflow.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import dev.mangaflow.ui.motion.MotionBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.Orientation
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Scale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.PageCollection
import dev.mangaflow.storage.ReaderPreferences
import dev.mangaflow.storage.ReaderTheme
import dev.mangaflow.storage.ReadingMode
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.LibraryRestoreStatus
import dev.mangaflow.ui.ChapterReadMark
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.CompactMenuAction
import dev.mangaflow.ui.rememberChapterPositions
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.GlassSkinPicker
import dev.mangaflow.ui.motion.BottomPullLoadState
import dev.mangaflow.ui.motion.bottomPullLoad
import dev.mangaflow.ui.motion.rememberBottomPullLoadState
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(
    manga: Manga,
    chapters: List<Chapter>,
    initialChapterId: String,
    initialCollection: PageCollection? = null,
    repository: MangaRepository,
    libraryStore: LibraryStore,
    downloadStore: DownloadStore,
    communityRepository: CopyCommunityRepository,
    reviewRepository: dev.mangaflow.data.ReviewRepository,
    onBack: () -> Unit,
    onRequestNotificationPermission: () -> Unit = {},
    onChapterVisible: (Manga, Chapter) -> Unit = { _, _ -> },
    onNearChapterEnd: (Manga, Chapter) -> Unit = { _, _ -> },
    chaptersLoading: Boolean = false,
    chaptersError: String? = null,
    onRetryChapters: () -> Unit = {},
    active: Boolean = true,
    startAtBeginning: Boolean = false,
) {
    val libraryReady by libraryStore.ready.collectAsStateWithLifecycle()
    if (!libraryReady) {
        BackHandler(enabled = active, onBack = onBack)
        LibraryRestoreStatus(libraryStore)
        return
    }
    var chapterId by rememberSaveable(manga.id, initialCollection?.key) { mutableStateOf(initialChapterId) }
    var collectionPresentation by rememberSaveable(manga.id, initialCollection?.key) { mutableStateOf(initialCollection != null) }
    var collectionPositionApplied by rememberSaveable(manga.id, initialCollection?.key) { mutableStateOf(false) }
    val presentedCollection = initialCollection?.takeIf { collectionPresentation && it.chapter.id == chapterId }
    val globalPreferences by libraryStore.preferences.collectAsStateWithLifecycle()
    val mangaPreferences by libraryStore.mangaPreferences.collectAsStateWithLifecycle()
    val savedPreferences = remember(globalPreferences, mangaPreferences, manga.id) {
        (mangaPreferences[manga.id] ?: globalPreferences).copy(glassSkin = globalPreferences.glassSkin)
    }
    var collectionLayout by remember(manga.id, initialCollection?.key) { mutableStateOf<ReaderPreferences?>(null) }
    val preferences = remember(savedPreferences, presentedCollection, collectionLayout) {
        presentedCollection?.let {
            savedPreferences.copy(mode = collectionLayout?.mode ?: it.mode,
                doublePage = collectionLayout?.doublePage ?: it.spread,
                coverAlone = collectionLayout?.coverAlone ?: it.coverAlone)
        } ?: savedPreferences
    }
    val pageCollections by libraryStore.pageCollections.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val readerForeground = active && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var restartPending by rememberSaveable(manga.id, initialChapterId, initialCollection?.key) {
        mutableStateOf(startAtBeginning && initialCollection == null)
    }
    LaunchedEffect(chapterId) {
        if (initialCollection?.chapter?.id != chapterId) collectionPresentation = false
    }
    val chapter = chapters.firstOrNull { it.id == chapterId }
        ?: Chapter(chapterId, libraryStore.progressFor(manga.id)?.takeIf { it.chapterId == chapterId }?.chapterTitle ?: "正在阅读", 0)
    val localImport = manga.sourceUrl == LOCAL_SOURCE_URL
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
    var targetOffset by remember(chapterId) { mutableIntStateOf(0) }
    var returnLocation by remember(chapterId) { mutableStateOf<ReaderReturnLocation?>(null) }
    var showPageNavigator by remember(chapterId) { mutableStateOf(false) }
    var showReaderActions by remember(chapterId) { mutableStateOf(false) }
    var readerActionsPresent by remember(chapterId) { mutableStateOf(false) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var bottomControlsHeightPx by remember { mutableIntStateOf(0) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showComments by remember(chapterId) { mutableStateOf(false) }
    var showSpreadAdjustment by remember(chapterId) { mutableStateOf(false) }
    var manualSingles by remember(manga.id, chapterId, presentedCollection?.key) {
        mutableStateOf(presentedCollection?.singlePages ?: libraryStore.spreadSinglesFor(manga.id, chapterId))
    }
    var atChapterEnd by remember(chapterId, preferences.mode, preferences.doublePage, preferences.coverAlone, manualSingles) {
        mutableStateOf(false)
    }
    var nextRequested by remember(chapterId) { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var downloadMessage by remember(chapterId) { mutableStateOf<String?>(null) }
    var displayedDownloadMessage by remember(chapterId) { mutableStateOf("") }
    var progressInteracting by remember(chapterId) { mutableStateOf(false) }
    val dimPreview = remember { mutableStateOf<Float?>(null) }
    var imageVisible by remember(chapterId) { mutableStateOf(false) }
    var recentConfirmed by remember(manga.id, chapterId) { mutableStateOf(false) }
    var chapterVisibilityReported by rememberSaveable(manga.id, chapterId) { mutableStateOf(false) }
    var nextChapterPrefetched by remember(manga.id, chapterId) { mutableStateOf(false) }
    var leavingReader by remember { mutableStateOf(false) }
    val nextChapter = siblings.getOrNull(chapterIndex + 1).takeIf { chapterIndex >= 0 }
    val openNextChapter: (() -> Unit)? = nextChapter?.let { next ->
        {
            if (active && !leavingReader && !nextRequested && chapterId == chapter.id) {
                nextRequested = true
                chapterId = next.id
                controlsVisible = true
            }
        }
    }
    val chapterVisible by rememberUpdatedState(onChapterVisible)
    val nearChapterEnd by rememberUpdatedState(onNearChapterEnd)
    val scope = rememberCoroutineScope()
    val headers = remember(manga.id, chapterId, repository, localImport) {
        if (localImport) emptyMap() else repository.imageHeaders(manga.id, chapterId)
    }
    val chapterDownload = remember(downloadStore, manga.id, chapterId) { downloadStore.observeChapter(manga.id, chapterId) }
    val initialDownload = remember(downloadStore, manga.id, chapterId) {
        downloadStore.entries.value.firstOrNull { it.manga.id == manga.id && it.chapter.id == chapterId }
    }
    val activeDownload by chapterDownload.collectAsStateWithLifecycle(initialValue = initialDownload)
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
            if (localImport) throw IllegalStateException("本地文件已损坏，请删除后重新导入")
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

    val clock = rememberReaderClock(active && !leavingReader)
    val readingEligible = active && !leavingReader && !loading && error == null && imageVisible &&
        !showSettings && !showChapters && !showComments && !showSpreadAdjustment && !showPageNavigator &&
        !showReaderActions && !readerActionsPresent
    RecordReaderActivity(manga, readingEligible, libraryStore, chapterId) { owner, confirmedChapter ->
        if (owner == manga.id && confirmedChapter == chapterId && readingEligible) {
            recentConfirmed = true
            libraryStore.saveProgress(manga, chapter, pageIndex, pages.size, cursor.scrollOffset)
        }
    }
    fun updatePreferences(next: ReaderPreferences) {
        // Persist only settings the user actually changed, never the collection's temporary layout.
        val saved = savedPreferences.copy(
            mode = if (next.mode != preferences.mode) next.mode else savedPreferences.mode,
            theme = if (next.theme != preferences.theme) next.theme else savedPreferences.theme,
            keepScreenOn = if (next.keepScreenOn != preferences.keepScreenOn) next.keepScreenOn else savedPreferences.keepScreenOn,
            pageGap = if (next.pageGap != preferences.pageGap) next.pageGap else savedPreferences.pageGap,
            doublePage = if (next.doublePage != preferences.doublePage) next.doublePage else savedPreferences.doublePage,
            coverAlone = if (next.coverAlone != preferences.coverAlone) next.coverAlone else savedPreferences.coverAlone,
            dimAmount = if (next.dimAmount != preferences.dimAmount) next.dimAmount else savedPreferences.dimAmount,
            glassSkin = if (next.glassSkin != preferences.glassSkin) next.glassSkin else savedPreferences.glassSkin,
        )
        if (presentedCollection != null) collectionLayout = next
        libraryStore.updateMangaPreferences(manga.id, saved)
    }
    fun picture(first: Int, last: Int = first): PageCollection? {
        if (first !in pages.indices || last !in first..pages.lastIndex) return null
        return PageCollection(manga, chapter, first, last,
            previewUrls = (first..last).map { pages[it].url }, mode = preferences.mode,
            coverAlone = preferences.coverAlone, singlePages = manualSingles)
    }
    val spreadGroups = remember(pages, preferences.mode, preferences.doublePage, preferences.coverAlone, manualSingles) {
        if (preferences.mode != ReadingMode.VERTICAL && preferences.doublePage)
            pageSpreads(pages, preferences.coverAlone, manualSingles) else emptyList()
    }
    val currentSpread = remember(pages, spreadGroups, pageIndex) {
        if (pages.isEmpty()) null
        else if (spreadGroups.isNotEmpty()) spreadGroups.firstOrNull { it.contains(pageIndex) }
        else PageSpread(pageIndex.coerceIn(pages.indices))
    }
    val currentPicture = currentSpread?.let { picture(it.first, it.last) }
    val currentPictureCollected = currentPicture?.key?.let { key -> pageCollections.any { it.key == key } } == true
    fun togglePicture(first: Int, last: Int = first) {
        picture(first, last)?.let { collection ->
            val existed = pageCollections.any { it.key == collection.key }
            libraryStore.togglePageCollection(collection)
            downloadMessage = if (existed) "已取消画面收藏" else if (collection.spread) "已收藏跨页画面" else "已收藏画面"
        }
    }

    if (active) ReaderWindow(preferences.keepScreenOn, controlsVisible || showSettings || showChapters || showSpreadAdjustment || showComments, lightChrome)
    val leaveReader = { leavingReader = true; onBack() }
    MotionBackHandler(enabled = active, onBack = leaveReader)
    LaunchedEffect(active) { leavingReader = !active }

    LaunchedEffect(chapterId, retry, initialCollection?.key) {
        loading = true
        error = null
        try {
            // A stable source manifest represents the chapter, even when only some pages are saved.
            // File selection belongs to each image request and never creates a separate offline mode.
            val stored = downloadStore.storedPages(manga.id, chapterId)
            val loaded = stored ?: if (localImport) {
                throw IllegalStateException("本地章节不存在，请重新导入")
            } else repository.pages(manga.id, chapterId, forceRefresh = retry > 0)
            if (loaded.isEmpty()) throw IllegalStateException("这一章还没有可读取的图片")
            val restarting = restartPending && chapterId == initialChapterId && initialCollection == null
            val saved = if (restarting) null else libraryStore.progressFor(manga.id, chapterId)
            val collection = initialCollection?.takeIf { collectionPresentation && !collectionPositionApplied && it.chapter.id == chapterId }
            pageIndex = (collection?.firstPage ?: saved?.pageIndex ?: 0).coerceIn(0, loaded.lastIndex)
            cursor.scrollOffset = if (collection != null) 0 else saved?.scrollOffset ?: 0
            if (collection != null) collectionPositionApplied = true
            libraryStore.migratePageBookmarks(manga, chapter, loaded)
            downloadStore.prioritizeReadingPage(manga.id, chapterId, pageIndex)
            pages = loaded
            if (restarting) libraryStore.saveProgress(manga, chapter, 0, loaded.size,
                clearLegacyLongImagePosition = true)
            restartPending = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "章节暂时无法读取"
        } finally { loading = false }
    }

    LaunchedEffect(chapterId, pageIndex) {
        downloadStore.prioritizeReadingPage(manga.id, chapterId, pageIndex)
    }

    LaunchedEffect(controlsVisible, showSettings, showChapters, showSpreadAdjustment, showComments,
        showPageNavigator, showReaderActions, readerActionsPresent, loading, error, progressInteracting, touchExploration) {
        if (controlsVisible && !showSettings && !showChapters && !showSpreadAdjustment && !showComments &&
            !showPageNavigator && !showReaderActions && !readerActionsPresent &&
            !loading && error == null && !progressInteracting && !touchExploration) {
            delay(5000)
            controlsVisible = false
        }
    }
    LaunchedEffect(controlsVisible, active, leavingReader) {
        if (!controlsVisible || !active || leavingReader) showReaderActions = false
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
    LaunchedEffect(discussionState, pageIndex, pages.size, readerForeground, leavingReader, localImport) {
        if (!localImport && readerForeground && !leavingReader && pages.isNotEmpty() && pageIndex >= pages.lastIndex - 3) {
            discussionState.ensureLoaded()
        }
    }
    LaunchedEffect(atChapterEnd, touchExploration) {
        if (atChapterEnd && !touchExploration) controlsVisible = false
    }

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
                        targetOffset = targetOffset,
                        onTargetConsumed = { targetPage = null; targetOffset = 0 },
                        onLocation = { index, offset -> cursor.scrollOffset = offset; pageIndex = index },
                        onSave = { index, offset -> libraryStore.saveProgress(manga, chapter, index, pages.size, offset,
                            clearLegacyLongImagePosition = preferences.mode == ReadingMode.VERTICAL,
                            updateRecent = recentConfirmed && chapterId == chapter.id && active && !leavingReader) },
                        onReadingNavigation = {
                            if (chapterId == chapter.id && readingEligible) recentConfirmed = true
                        },
                        longImagePosition = { index -> libraryStore.longImagePositionFor(manga.id, chapter.id, index) },
                        onVisibleImageChanged = { visible ->
                            imageVisible = visible
                            if (visible && readerForeground && !leavingReader && chapterId == chapter.id && !chapterVisibilityReported) {
                                chapterVisibilityReported = true
                                chapterVisible(manga, chapter)
                            }
                        },
                        onNearEnd = {
                            val next = nextChapter
                            if (readerForeground && !leavingReader && chapterId == chapter.id && !nextChapterPrefetched && next != null) {
                                nextChapterPrefetched = true
                                nearChapterEnd(manga, next)
                            }
                        },
                        onTap = { controlsVisible = !controlsVisible },
                        active = readerForeground && !leavingReader && !showSettings && !showChapters &&
                            !showSpreadAdjustment && !showComments && !nextRequested,
                        onAtEndChanged = { atChapterEnd = it },
                        onNext = openNextChapter,
                        endContent = { pull ->
                            if (!controlsVisible && !showComments && !showSettings && !showChapters && !showSpreadAdjustment) {
                                ChapterEndFooter(
                                    pull = pull,
                                    nextTitle = nextChapter?.title,
                                    unavailableMessage = when {
                                        chaptersLoading -> "正在读取目录"
                                        chaptersError != null -> "目录暂未更新"
                                        chapterIndex < 0 -> "目录暂不可用"
                                        else -> "已到最后一话"
                                    },
                                    foreground = colors.onSurface,
                                    onNext = openNextChapter,
                                    onRetry = onRetryChapters.takeIf { !chaptersLoading && (chaptersError != null || chapterIndex < 0) },
                                )
                            }

                        },
                    )
                }
            }

            // Read the live dim preview during drawing; dragging does not publish or persist preferences.
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = dimPreview.value ?: preferences.dimAmount)) })
        }

        if (!localImport && active && !leavingReader && atChapterEnd && !loading && error == null &&
            !showComments && !showSettings && !showChapters && !showSpreadAdjustment &&
            !showReaderActions && !readerActionsPresent && !showPageNavigator && !progressInteracting) {
            ChapterEndComments(
                chapterId = chapterId, state = discussionState, backdrop = readerBackdrop,
                bottomClearance = if (controlsVisible) with(LocalDensity.current) {
                    (bottomControlsHeightPx - WindowInsets.navigationBarsIgnoringVisibility.getBottom(this)).coerceAtLeast(0).toDp()
                } else 0.dp,
                touchExploration = touchExploration,
                onOpen = { showComments = true },
                modifier = Modifier.align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }

        AnimatedVisibility(
            controlsVisible, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(if (motionEnabled) MangaMotion.Content else 0)) +
                slideInVertically(tween(if (motionEnabled) MangaMotion.Chrome else 0, easing = MangaMotion.EnterEasing)) { -toolbarTravel },
            exit = fadeOut(tween(if (motionEnabled) MangaMotion.Exit else 0)) +
                slideOutVertically(tween(if (motionEnabled) MangaMotion.Exit else 0, easing = MangaMotion.EnterEasing)) { -toolbarTravel },
        ) {
            LiquidGlassSurface(
                state = readerBackdrop,
                role = GlassSurfaceRole.NAVIGATION,
                trackPointer = true,
                softCard = true,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                // Retain Surface's input shield on blank chrome without consuming child gestures.
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).pointerInput(Unit) {}.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ReaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", interactive = controlsVisible, onClick = leaveReader)
                    Column(Modifier.weight(1f).padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(manga.title, color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(chapter.group.takeIf { hasMultipleGroups }, chapter.title).joinToString(" · "),
                            color = colors.onSurface.copy(alpha = .86f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (preferences.mode == ReadingMode.RIGHT_TO_LEFT || preferences.mode == ReadingMode.LEFT_TO_RIGHT) {
                        TextButton(enabled = controlsVisible, onClick = {
                            cursor.scrollOffset = 0
                            updatePreferences(preferences.copy(doublePage = !preferences.doublePage))
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (preferences.doublePage) "双页" else "单页", color = colors.onSurface, fontSize = 12.sp)
                        }
                    }
                    ReaderIcon(Icons.Rounded.Tune, "阅读设置", interactive = controlsVisible) { showSettings = true }
                }
            }
        }

        AnimatedVisibility(
            controlsVisible, Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomControlsHeightPx = it.height },
            enter = fadeIn(tween(if (motionEnabled) MangaMotion.Content else 0)) +
                slideInVertically(tween(if (motionEnabled) MangaMotion.Chrome else 0, easing = MangaMotion.EnterEasing)) { toolbarTravel },
            exit = fadeOut(tween(if (motionEnabled) MangaMotion.Exit else 0)) +
                slideOutVertically(tween(if (motionEnabled) MangaMotion.Exit else 0, easing = MangaMotion.EnterEasing)) { toolbarTravel },
        ) {
            LiquidGlassSurface(
                state = readerBackdrop,
                role = GlassSurfaceRole.NAVIGATION,
                trackPointer = true,
                softCard = true,
                modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
                cornerRadius = 20.dp,
            ) {
                Column(Modifier.fillMaxWidth().pointerInput(Unit) {}.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (pages.isNotEmpty()) key(chapterId) {
                        ReaderProgressSlider(
                            pageIndex = pageIndex, pageCount = pages.size, pendingPage = targetPage,
                            enabled = controlsVisible,
                            onSeek = { destination ->
                                if (destination != pageIndex) {
                                    returnLocation = ReaderReturnLocation(pageIndex, cursor.scrollOffset)
                                    targetOffset = 0
                                    targetPage = destination
                                }
                            },
                            onOpenNavigator = { showPageNavigator = true },
                            onInteractionChanged = { progressInteracting = it },
                        )

                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val actionsScroll = rememberScrollState()
                        val canCorrectSpread = preferences.doublePage &&
                            preferences.mode in setOf(ReadingMode.RIGHT_TO_LEFT, ReadingMode.LEFT_TO_RIGHT)
                        val hasMoreActions = canCorrectSpread || returnLocation != null
                        val narrow = maxWidth < (if (hasMoreActions) 348.dp else 300.dp)
                        Row(Modifier.fillMaxWidth().then(if (narrow) Modifier.horizontalScroll(actionsScroll) else Modifier),
                            verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ReaderIcon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上一章",
                                    enabled = chapterIndex > 0, interactive = controlsVisible) {
                                    siblings.getOrNull(chapterIndex - 1)?.let { chapterId = it.id }
                                }
                                ReaderIcon(Icons.Outlined.MenuBook, "章节目录", interactive = controlsVisible) { showChapters = true }
                                ReaderIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下一章",
                                    enabled = openNextChapter != null && !nextRequested, interactive = controlsVisible) {
                                    openNextChapter?.invoke()
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            if (!narrow) Spacer(Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ReaderIcon(
                                    if (currentPictureCollected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    if (currentPictureCollected) "取消画面收藏" else if (currentPicture?.spread == true) "收藏当前跨页" else "收藏当前画面",
                                    enabled = currentPicture != null, interactive = controlsVisible,
                                ) {
                                    currentPicture?.let { togglePicture(it.firstPage, it.lastPage) }
                                }
                                ReaderIcon(
                                    Icons.Outlined.Forum,
                                    if (localImport) "本地章节没有在线评论" else "本章评论",
                                    enabled = pages.isNotEmpty() && !localImport,
                                    interactive = controlsVisible,
                                ) {
                                    showComments = true
                                }
                                val downloaded = activeDownload?.status == DownloadStatus.COMPLETED
                                val downloading = activeDownload?.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
                                MotionIconButton(
                                    enabled = controlsVisible && pages.isNotEmpty() && !downloaded && !localImport,
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
                                key(chapterId) {
                                    Box {
                                        if (hasMoreActions) ReaderIcon(Icons.Outlined.MoreHoriz, "更多阅读操作", interactive = controlsVisible) {
                                            showReaderActions = !showReaderActions
                                        }
                                        CompactGlassPopover(
                                            visible = hasMoreActions && showReaderActions && controlsVisible && active && !leavingReader,
                                            backdrop = readerBackdrop, aboveAnchor = true,
                                            onDismiss = { showReaderActions = false },
                                            onPresenceChange = { readerActionsPresent = it },
                                        ) {
                                            if (canCorrectSpread) {
                                                CompactMenuAction(Icons.Rounded.Tune, "校正双页", enabled = showReaderActions) {
                                                    showReaderActions = false
                                                    showSpreadAdjustment = true
                                                }
                                            }
                                            if (returnLocation != null) {
                                                CompactMenuAction(Icons.AutoMirrored.Rounded.Undo, "返回刚才的位置", enabled = showReaderActions) {
                                                    showReaderActions = false
                                                    returnLocation?.let { previous ->
                                                        val current = ReaderReturnLocation(pageIndex, cursor.scrollOffset)
                                                        targetOffset = previous.scrollOffset
                                                        targetPage = previous.pageIndex
                                                        returnLocation = current
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
            }
        }

        if (!controlsVisible && pages.isNotEmpty()) Text(
            "$clock · ${pageIndex + 1} / ${pages.size}",
            color = preferences.theme.foreground().copy(alpha = .5f), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (atChapterEnd) 72.dp else 12.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controlsVisible = true }
                .padding(horizontal = 4.dp, vertical = 4.dp),
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
    ReaderPageNavigatorDialog(
        visible = showPageNavigator,
        pages = pages,
        headers = headers,
        currentPage = pageIndex,
        collectedPages = pageCollections.filter { it.manga.id == manga.id && it.chapter.id == chapterId && !it.spread }
            .map(PageCollection::firstPage).toSet(),
        onToggleCollection = { togglePicture(it) },
        backdrop = readerBackdrop,
        onSelect = { destination ->
            if (destination != pageIndex) {
                returnLocation = ReaderReturnLocation(pageIndex, cursor.scrollOffset)
                targetOffset = 0
                targetPage = destination
            }
            showPageNavigator = false
        },
        onDismiss = { showPageNavigator = false },
    )
    ReaderSettingsDialog(visible = showSettings, preferences = preferences, backdrop = readerBackdrop,
        onDimPreview = { dimPreview.value = it }, update = { next ->
        if (next.mode != preferences.mode || next.doublePage != preferences.doublePage || next.coverAlone != preferences.coverAlone) {
            cursor.scrollOffset = 0
        }
        updatePreferences(next)
    }) {
        dimPreview.value?.takeIf { it != preferences.dimAmount }?.let { amount ->
            updatePreferences(preferences.copy(dimAmount = amount))
        }
        showSettings = false
    }
    ReaderSpreadDialog(
        visible = showSpreadAdjustment && pages.isNotEmpty(),
        pages = pages,
        currentPage = pageIndex,
        preferences = preferences,
        manualSingles = manualSingles,
        backdrop = readerBackdrop,
        onCoverChange = { cover ->
            cursor.scrollOffset = 0
            updatePreferences(preferences.copy(coverAlone = cover))
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
        manga = manga, library = libraryStore,
        backdrop = readerBackdrop,
        downloadStore = downloadStore,
        loading = chaptersLoading, error = chaptersError, onRetry = onRetryChapters,
        onSelect = { chapterId = it; showChapters = false; controlsVisible = true },
        onDismiss = { showChapters = false },
    )
    if (showComments) ChapterDiscussion(
        chapter, colors.background, colors.onSurface,
        onClose = { showComments = false }, state = discussionState,
    )
}

@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
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
    targetOffset: Int,
    onTargetConsumed: () -> Unit,
    onLocation: (Int, Int) -> Unit,
    onSave: (Int, Int) -> Unit,
    onReadingNavigation: () -> Unit,
    longImagePosition: (Int) -> Float?,
    onVisibleImageChanged: (Boolean) -> Unit,
    onNearEnd: () -> Unit,
    onTap: () -> Unit,
    active: Boolean,
    onAtEndChanged: (Boolean) -> Unit,
    onNext: (() -> Unit)?,
    endContent: @Composable (BottomPullLoadState) -> Unit,
) {
    val save by rememberUpdatedState(onSave)
    val readingNavigation by rememberUpdatedState(onReadingNavigation)
    val location by rememberUpdatedState(onLocation)
    val visibleImageChanged by rememberUpdatedState(onVisibleImageChanged)
    val nearEnd by rememberUpdatedState(onNearEnd)
    val atEndChanged by rememberUpdatedState(onAtEndChanged)
    val loadedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val composedPages = remember { mutableStateMapOf<Int, Boolean>() }
    val prefetchRequests = remember { mutableStateMapOf<Int, Disposable>() }
    val pageDimensions = remember { mutableStateMapOf<String, IntSize>() }
    val startPage = remember { initialPage.coerceIn(0, pages.lastIndex) }
    val startOffset = remember { initialOffset.coerceAtLeast(0) }
    val endRevealPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    if (preferences.mode == ReadingMode.VERTICAL) {
        val list = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
        val pageLayoutReady = remember { mutableStateMapOf<Int, Boolean>() }
        val endLayoutReady = {
            pageLayoutReady[pages.lastIndex] == true && list.layoutInfo.visibleItemsInfo.all {
                it.index !in pages.indices || pageLayoutReady[it.index] == true
            }
        }
        val legacyLongPosition = remember { longImagePosition(startPage)?.takeIf { it > 0f } }
        var restoring by remember { mutableStateOf(startOffset > 0 || legacyLongPosition != null) }
        var targetHeight by remember { mutableIntStateOf(0) }
        val pull = rememberBottomPullLoadState(
            key = list,
            enabled = active && !restoring && onNext != null,
            loading = false,
            // An unresolved long last image can make its placeholder look like the end.
            canScrollForward = { list.canScrollForward || !endLayoutReady() },
            onLoadMore = { onNext?.invoke() },
            actionLabel = "进入下一话",
        )
        val atEnd by remember(list) {
            derivedStateOf {
                val layout = list.layoutInfo
                !restoring && endLayoutReady() && layout.visibleItemsInfo.any {
                    it.index == pages.lastIndex && it.offset < layout.viewportEndOffset &&
                        it.offset + it.size <= layout.viewportEndOffset + endRevealPx
                }
            }
        }
        LaunchedEffect(atEnd) { atEndChanged(atEnd) }
        val dragging by list.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(restoring, targetHeight) {
            if (restoring && targetHeight > 0) {
                withFrameNanos { }
                // Old vertical reading kept long-image pan separately from the list offset.
                val offset = (startOffset + (legacyLongPosition ?: 0f) * targetHeight).roundToInt()
                list.scrollToItem(startPage, offset.coerceIn(0, targetHeight - 1))
                restoring = false
            }
        }
        LaunchedEffect(dragging) { if (dragging) { restoring = false; readingNavigation() } }
        LaunchedEffect(list, active) {
            snapshotFlow {
                val layout = list.layoutInfo
                if (restoring) -1 else layout.visibleItemsInfo.lastOrNull { item ->
                    loadedPages[item.index] == true && item.size > 0 &&
                        item.offset < layout.viewportEndOffset &&
                        item.offset + item.size > layout.viewportStartOffset
                }?.index ?: -1
            }.distinctUntilChanged().collect { index ->
                visibleImageChanged(index >= 0)
                if (index >= 0) {
                    if (index >= pages.lastIndex - 3) nearEnd()
                }
            }
        }
        LaunchedEffect(list) {
            snapshotFlow { Triple(list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex),
                if (list.firstVisibleItemIndex <= pages.lastIndex) list.firstVisibleItemScrollOffset else 0, restoring) }
                .distinctUntilChanged().collect { (index, offset, restoringNow) -> if (!restoringNow) location(index, offset) }
        }
        LaunchedEffect(list) {
            snapshotFlow { Triple(list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex),
                if (list.firstVisibleItemIndex <= pages.lastIndex) list.firstVisibleItemScrollOffset else 0, restoring) }
                .debounce(450).distinctUntilChanged().collect { (index, offset, restoringNow) -> if (!restoringNow) save(index, offset) }
        }
        LaunchedEffect(targetPage, targetOffset) {
            targetPage?.let {
                readingNavigation()
                restoring = false
                list.scrollToItem(it.coerceIn(0, pages.lastIndex), targetOffset.coerceAtLeast(0))
                onTargetConsumed()
            }
        }
        DisposableEffect(list) {
            onDispose {
                visibleImageChanged(false)
                if (!restoring) save(list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex),
                    if (list.firstVisibleItemIndex <= pages.lastIndex) list.firstVisibleItemScrollOffset else 0)
            }
        }
        AdjacentPagePrefetch(pages, headers, preferences.mode, composedPages, prefetchRequests, resolvePage,
            enabled = active,
            onPageDimensions = { index, size -> pageDimensions[pages[index].url] = size }) {
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.index in pages.indices }
            val anchor = list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex)
            (visible.firstOrNull()?.index ?: anchor) to (visible.lastOrNull()?.index ?: anchor)
        }
        Box(Modifier.fillMaxSize().bottomPullLoad(pull)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(if (preferences.pageGap) 6.dp else 0.dp)) {
                itemsIndexed(pages, key = { index, page -> "${page.index}:$index" }, contentType = { _, _ -> "page" }) { index, page ->
                    ReaderPage(
                        page, headers, resolvePage = resolvePage, retryPage = retryPage, paged = false, onTap = onTap,
                        onMeasured = { loadedPages[index] = true },
                        onHeightMeasured = { if (index == startPage) targetHeight = it },
                        onComposed = { present -> if (present) composedPages[index] = true else composedPages.remove(index) },
                        prefetch = prefetchRequests[index],
                        knownDimensions = pageDimensions[page.url],
                        onDimensionsKnown = { pageDimensions[page.url] = it },
                        deferResize = { list.isScrollInProgress },
                        visibleWindow = {
                            val layout = list.layoutInfo
                            layout.visibleItemsInfo.firstOrNull { it.index == index }?.let { item ->
                                VerticalPageWindow((layout.viewportStartOffset - item.offset).coerceAtLeast(0),
                                    (layout.viewportEndOffset - item.offset).coerceIn(0, item.size))
                            }
                        },
                        onLayoutReady = { pageLayoutReady[index] = it },
                    )
                }
            }
            if (atEnd) Box(Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) { endContent(pull) }
        }
    } else {
        val groups = remember(pages, preferences.doublePage, preferences.coverAlone, manualSingles) {
            if (preferences.doublePage) pageSpreads(pages, preferences.coverAlone, manualSingles)
            else pages.indices.map { PageSpread(it) }
        }
        val startingGroup = remember(groups, startPage) {
            groups.indexOfFirst { it.contains(startPage) }.coerceAtLeast(0)
        }
        val pager = rememberPagerState(initialPage = startingGroup, pageCount = { groups.size })
        val dragging by pager.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(dragging) { if (dragging) readingNavigation() }
        val swipeReadyPages = remember { mutableStateMapOf<Int, Boolean>() }
        val atEnd by remember(pager, groups) {
            derivedStateOf {
                pager.settledPage == groups.lastIndex && pager.currentPage == groups.lastIndex &&
                    kotlin.math.abs(pager.currentPageOffsetFraction) < .01f && loadedPages[pages.lastIndex] == true
            }
        }
        val endReady = groups.last().let { group ->
            swipeReadyPages[group.first] == true && (group.second == null || swipeReadyPages[group.second] == true)
        }
        val pull = rememberBottomPullLoadState(
            key = pager,
            enabled = active && onNext != null && atEnd && endReady,
            loading = false,
            canScrollForward = { !atEnd || !endReady },
            onLoadMore = { onNext?.invoke() },
            actionLabel = "进入下一话",
            orientation = Orientation.Horizontal,
        )
        LaunchedEffect(atEnd) { atEndChanged(atEnd) }
        LaunchedEffect(pager, groups) {
            snapshotFlow { groups.getOrNull(pager.currentPage)?.first ?: pages.lastIndex }
                .distinctUntilChanged().collect { index -> location(index, 0) }
        }
        LaunchedEffect(pager, groups) {
            snapshotFlow { groups.getOrNull(pager.settledPage)?.first ?: pages.lastIndex }
                .distinctUntilChanged().collect { index -> save(index, 0) }
        }
        LaunchedEffect(pager, groups, active) {
            snapshotFlow {
                groups.getOrNull(pager.currentPage)?.let { group ->
                    if (loadedPages[group.first] == true || (group.second != null && loadedPages[group.second] == true)) group.last
                    else -1
                } ?: -1
            }.distinctUntilChanged().collect { lastVisible ->
                visibleImageChanged(lastVisible >= 0)
                if (lastVisible >= 0) {
                    if (lastVisible >= pages.lastIndex - 3) nearEnd()
                }
            }
        }
        LaunchedEffect(targetPage, groups) {
            targetPage?.let { imageIndex ->
                readingNavigation()
                val groupIndex = groups.indexOfFirst { it.contains(imageIndex.coerceIn(0, pages.lastIndex)) }
                if (groupIndex >= 0) pager.scrollToPage(groupIndex)
                onTargetConsumed()
            }
        }
        DisposableEffect(pager, groups) {
            onDispose {
                visibleImageChanged(false)
                save(groups.getOrNull(pager.settledPage)?.first ?: pages.lastIndex, 0)
            }
        }
        AdjacentPagePrefetch(pages, headers, preferences.mode, composedPages, prefetchRequests, resolvePage,
            enabled = active) {
            val shown = groups.getOrNull(pager.currentPage)
            (shown?.first ?: pages.lastIndex) to (shown?.last ?: pages.lastIndex)
        }
        Box(Modifier.fillMaxSize().bottomPullLoad(pull)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = preferences.mode == ReadingMode.RIGHT_TO_LEFT,
                beyondViewportPageCount = 1,
                key = { index -> "image:${groups[index].first}" },
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
                                onChapterSwipeReadyChanged = { ready ->
                                    if (ready) swipeReadyPages[imageIndex] = true else swipeReadyPages.remove(imageIndex)
                                },
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
                }
            }
            if (atEnd) Box(Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) { endContent(pull) }
        }
    }
}

private class ReaderCursor(var scrollOffset: Int = 0)

private data class PrefetchWindow(
    val first: Int, val last: Int, val composed: Set<Int>, val inFlight: Set<Int>, val enabled: Boolean,
)

private data class LoadedReaderImage(val diskCacheKey: String?, val previewSize: IntSize)

private fun IntSize.readerAspectRatio(): Float = (width.toFloat() / height).coerceAtLeast(.005f)

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
    enabled: Boolean,
    onPageDimensions: (Int, IntSize) -> Unit = { _, _ -> },
    visibleRange: () -> Pair<Int, Int>,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val visible by rememberUpdatedState(visibleRange)
    val dimensionsKnown by rememberUpdatedState(onPageDimensions)
    val prefetchEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(pages, headers, mode, imageLoader, resolvePage) {
        val finished = mutableSetOf<Int>()
        var previousFirst = -1
        var direction = 1
        try {
            snapshotFlow {
                val (first, last) = visible()
                PrefetchWindow(first, last, composedPages.keys.toSet(), requests.keys.toSet(), prefetchEnabled)
            }.distinctUntilChanged().collectLatest { window ->
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
                val candidates = if (direction > 0) listOf(window.last + 1, window.last + 2, window.last + 3, window.first - 1)
                    else listOf(window.first - 1, window.first - 2, window.first - 3, window.last + 1)
                val desired = candidates.filter { index ->
                    window.enabled && index in pages.indices && index !in window.composed && pages[index].url.startsWith("https:")
                }.toSet()
                val retained = desired + active
                requests.keys.filter { it !in retained }.forEach { index -> requests.remove(index)?.dispose() }
                finished.retainAll(retained)
                for (index in desired) {
                    if (index !in requests && index !in finished && requests.size < 4) {
                        val resource = try { resolvePage(pages[index]) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { finished += index; continue }
                        // Persistent downloads already have a file for Telephoto to read.
                        if (!resource.url.startsWith("https:")) {
                            if (mode == ReadingMode.VERTICAL) {
                                readerImageDimensions(imageLoader, resource)?.let { dimensionsKnown(index, it) }
                            }
                            finished += index
                            continue
                        }
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
                            if (mode == ReadingMode.VERTICAL && result is SuccessResult && requests[index] === request) {
                                val size = readerImageDimensions(imageLoader, resource, result.diskCacheKey)
                                if (size != null && requests[index] === request) dimensionsKnown(index, size)
                            }
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

@Composable
private fun ReaderPage(
    page: Page,
    headers: Map<String, String>,
    resolvePage: suspend (Page) -> Page,
    retryPage: suspend (Page) -> Page,
    paged: Boolean,
    pagedAlignment: Alignment = Alignment.Center,
    onTap: () -> Unit,
    onMeasured: () -> Unit,
    onHeightMeasured: (Int) -> Unit = {},
    onComposed: (Boolean) -> Unit = {},
    prefetch: Disposable? = null,
    knownDimensions: IntSize? = null,
    onDimensionsKnown: (IntSize) -> Unit = {},
    deferResize: () -> Boolean = { false },
    visibleWindow: () -> VerticalPageWindow? = { null },
    onLayoutReady: (Boolean) -> Unit = {},
    onChapterSwipeReadyChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.coerceAtLeast(320).dp
    val measured by rememberUpdatedState(onMeasured)
    val resolve by rememberUpdatedState(resolvePage)
    val retrySource by rememberUpdatedState(retryPage)
    val composed by rememberUpdatedState(onComposed)
    val heightMeasured by rememberUpdatedState(onHeightMeasured)
    val dimensionsMeasured by rememberUpdatedState(onDimensionsKnown)
    val resizeDeferred by rememberUpdatedState(deferResize)
    val layoutReady by rememberUpdatedState(onLayoutReady)
    val chapterSwipeReadyChanged by rememberUpdatedState(onChapterSwipeReadyChanged)
    // Lazy layout recycling must not collapse a measured page back to its placeholder height.
    val cachedDimensions = remember(page.url) { ReaderDimensionCache.get(page.url) }
    var aspect by rememberSaveable(page.url) { mutableFloatStateOf(
        (knownDimensions ?: cachedDimensions)?.readerAspectRatio()
            ?: if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height else 0.7f) }
    var hasDimensions by rememberSaveable(page.url) {
        mutableStateOf(knownDimensions != null || cachedDimensions != null || (page.width > 0 && page.height > 0))
    }
    var observedDimensions by remember(page.url) { mutableStateOf<IntSize?>(null) }
    var measuredHeight by remember { mutableIntStateOf(0) }
    var error by remember(page.url) { mutableStateOf<String?>(null) }
    var loading by remember(page.url) { mutableStateOf(true) }
    var reload by remember(page.url) { mutableIntStateOf(0) }
    var loadedImage by remember(page.url, reload) { mutableStateOf<LoadedReaderImage?>(null) }
    var resolvedPage by remember(page.url) { mutableStateOf<Page?>(null) }
    LaunchedEffect(page.url, reload) {
        loading = true
        error = null
        resolvedPage = null
        try {
            val resource = if (reload > 0) retrySource(page) else resolve(page)
            if (!paged && knownDimensions == null) {
                readerImageDimensions(context.imageLoader, resource)?.let {
                    observedDimensions = it
                    dimensionsMeasured(it)
                }
            }
            resolvedPage = resource
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // A failed persistent fetch must not immediately repeat the same HTTP request in Coil.
            loading = false
            error = failure.message ?: "图片暂时无法读取"
        }
    }
    LaunchedEffect(knownDimensions, observedDimensions) {
        val size = knownDimensions ?: observedDimensions ?: return@LaunchedEffect
        val measuredAspect = size.readerAspectRatio()
        if (!paged && measuredAspect != aspect) {
            // Do not move the scroll anchor when original dimensions arrive during a drag/fling.
            snapshotFlow { resizeDeferred() }.first { deferred -> !deferred }
        }
        aspect = measuredAspect
        hasDimensions = true
        ReaderDimensionCache.put(page.url, size)
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
        val viewportWidthPx = with(density) { maxWidth.roundToPx() }
        val previewWidth = viewportWidthPx.coerceIn(1, 1600)
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
                        if (!paged && width > 0 && height > 0) {
                            loadedImage = LoadedReaderImage(result.diskCacheKey, IntSize(width, height))
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
        LaunchedEffect(resource, loadedImage) {
            val image = loadedImage ?: return@LaunchedEffect
            if (!paged && resource != null && knownDimensions == null) {
                val size = readerImageDimensions(context.imageLoader, resource, image.diskCacheKey) ?: image.previewSize
                observedDimensions = size
                dimensionsMeasured(size)
            }
        }
        val imageError = error ?: prefetchError.takeUnless { resource?.url?.startsWith("file:") == true }
        val naturalHeight = maxWidth / aspect
        val naturalHeightPx = with(density) { naturalHeight.roundToPx() }
        val pendingAspect = (knownDimensions ?: observedDimensions ?: cachedDimensions)?.readerAspectRatio()
        LaunchedEffect(paged, hasDimensions, pendingAspect, aspect, naturalHeightPx, measuredHeight) {
            if (!paged) layoutReady(hasDimensions && (pendingAspect == null || pendingAspect == aspect) &&
                measuredHeight == naturalHeightPx && measuredHeight > 0)
        }
        LaunchedEffect(paged, hasDimensions, naturalHeightPx, measuredHeight) {
            // A successful image request can precede its new layout. Never restore from the placeholder height.
            if (!paged && hasDimensions && measuredHeight == naturalHeightPx && measuredHeight > 0) {
                heightMeasured(measuredHeight)
            }
        }
        // Vertical reading uses one continuous scroll surface for pages and long strips.
        val pageModifier = if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(naturalHeight)
            .onSizeChanged { measuredHeight = it.height }
        Box(pageModifier.clipToBounds(), contentAlignment = Alignment.Center) {
            if (request != null && !waitingForPrefetch && imageError == null) key(reload) {
                if (paged) {
                    val imageState = rememberZoomableImageState()
                    LaunchedEffect(imageState) {
                        snapshotFlow {
                            imageState.isImageDisplayed &&
                                (imageState.zoomableState.zoomFraction ?: 0f) <= .001f &&
                                !imageState.zoomableState.isAnimationRunning
                        }.distinctUntilChanged().collect { chapterSwipeReadyChanged(it) }
                    }
                    DisposableEffect(imageState) { onDispose { chapterSwipeReadyChanged(false) } }
                    ZoomableAsyncImage(
                        model = request,
                        state = imageState,
                        contentDescription = "第 ${page.index + 1} 页",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        alignment = pagedAlignment,
                        gesturesEnabled = true,
                        onClick = { onTap() },
                    )
                } else {
                    val tapSource = remember(page.url) { MutableInteractionSource() }
                    AsyncImage(
                        model = request,
                        contentDescription = "第 ${page.index + 1} 页",
                        modifier = Modifier.fillMaxSize()
                            .alpha(if (hasDimensions) 1f else 0f)
                            .clickable(
                                interactionSource = tapSource,
                                indication = null,
                                onClick = onTap,
                            ),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.TopCenter,
                    )
                    val originalSize = knownDimensions ?: observedDimensions
                        ?: page.takeIf { it.width > 0 && it.height > 0 }?.let { IntSize(it.width, it.height) }
                    if (hasDimensions && originalSize != null && naturalHeightPx > previewHeight * 2 &&
                        originalSize.readerAspectRatio() == aspect && loadedImage != null) {
                        VerticalReaderTiles(requireNotNull(resource), loadedImage?.diskCacheKey,
                            originalSize, viewportWidthPx, naturalHeightPx, visibleWindow)
                    }
                }
            }
            if (delayedLoading && imageError == null) CircularProgressIndicator(Modifier.size(22.dp), color = Color.Gray, strokeWidth = 1.5.dp)
            if (imageError != null) Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${page.index + 1} 页未加载", color = Color.Gray, fontSize = 13.sp)
                Text(imageError, color = Color.Gray, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { reload++ }) { Text("重载这页", color = Color.Gray) }
            }
        }
    }
}

private data class ReaderReturnLocation(val pageIndex: Int, val scrollOffset: Int)

@Composable
private fun ReaderPageNavigatorDialog(
    visible: Boolean,
    pages: List<Page>,
    headers: Map<String, String>,
    currentPage: Int,
    collectedPages: Set<Int>,
    onToggleCollection: (Int) -> Unit,
    backdrop: HazeState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    MotionDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        ReaderDialogSurface(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp - 96).coerceAtLeast(220).dp),
            backdrop,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                ReaderPanelHeading("页导航", "关闭页导航", onDismiss)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(76.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(pages, key = { it.index }) { page ->
                        val selected = page.index == currentPage
                        val request = remember(page.url, headers) {
                            ImageRequest.Builder(context).data(page.url)
                                .httpHeaders(NetworkHeaders.Builder().apply {
                                    headers.forEach { (name, value) -> set(name, value) }
                                }.build()).crossfade(0).build()
                        }
                        Column(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) .08f else .035f))
                                .clickable(role = Role.Button) { onSelect(page.index) }
                                .padding(5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.fillMaxWidth().aspectRatio(.7f)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                                AsyncImage(
                                    model = request,
                                    contentDescription = "第 ${page.index + 1} 页",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                MotionIconButton(onClick = { onToggleCollection(page.index) },
                                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(30.dp)
                                        .clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = .92f))) {
                                    Icon(if (page.index in collectedPages) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        if (page.index in collectedPages) "取消这一页的收藏" else "单独收藏这一页",
                                        Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            Text(
                                "${page.index + 1}",
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderProgressSlider(
    pageIndex: Int,
    pageCount: Int,
    pendingPage: Int?,
    enabled: Boolean,
    onSeek: (Int) -> Unit,
    onOpenNavigator: () -> Unit,
    onInteractionChanged: (Boolean) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val interactionChanged by rememberUpdatedState(onInteractionChanged)
    var preview by remember { mutableStateOf<Float?>(null) }
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val shown = (preview ?: pendingPage?.toFloat() ?: pageIndex.toFloat()).coerceIn(0f, lastPage.toFloat())

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
    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ReaderSeekBar(
            value = shown,
            onValueChange = { preview = it },
            onValueChangeFinished = {
                preview?.let { onSeek(it.roundToInt().coerceIn(0, lastPage)) }
                preview = null
            },
            valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            enabled = enabled && pageCount > 1,
            interactions = interactions,
            label = "阅读进度，第 ${shown.roundToInt() + 1} 页，共 $pageCount 页",
        )
        Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled && pageCount > 0, role = Role.Button,
                onClickLabel = "打开页面导航", onClick = onOpenNavigator)
            .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.roundToInt() + 1}", color = colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(" / $pageCount", color = colors.onSurfaceVariant, fontSize = 12.sp)
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
private fun ReaderSpreadDialog(
    visible: Boolean,
    pages: List<Page>,
    currentPage: Int,
    preferences: ReaderPreferences,
    manualSingles: Set<Int>,
    backdrop: HazeState,
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
        ReaderDialogSurface(Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth(), backdrop) {
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
    manga: Manga,
    library: LibraryStore,
    chapters: List<Chapter>,
    currentId: String,
    backdrop: HazeState,
    downloadStore: DownloadStore,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val groups = remember(chapters) { chapters.map(Chapter::group).distinct() }
    val positions = rememberChapterPositions(manga, library, active = visible)
    val currentGroup = chapters.firstOrNull { it.id == currentId }?.group ?: groups.firstOrNull().orEmpty()
    var selectedGroup by rememberSaveable { mutableStateOf(currentGroup) }
    val group = selectedGroup.takeIf { it in groups } ?: currentGroup
    val visibleChapters = remember(chapters, group) { chapters.filter { it.group == group }.sortedBy(Chapter::index) }
    val catalogBusy = rememberDelayedBusy(visible && loading)
    val availableHeight = (LocalConfiguration.current.screenHeightDp - 210).coerceAtLeast(112).dp
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    LaunchedEffect(visible, currentId, currentGroup) { if (visible) selectedGroup = currentGroup }
    MotionDialog(visible = visible, onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val downloadFlow = remember(downloadStore, manga.id) { downloadStore.observeManga(manga.id) }
        val downloads by downloadFlow.collectAsStateWithLifecycle(
            initialValue = downloadStore.entries.value.filter { it.manga.id == manga.id })
        val saved = remember(downloads) { downloads.associateBy { it.chapter.id } }
        ReaderDialogSurface(Modifier.padding(horizontal = 16.dp).widthIn(max = 600.dp).fillMaxWidth(), backdrop) {
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
                                Column(horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    ChapterReadMark(positions?.get(chapter.id), known = positions != null)
                                    if (entry != null) Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                        when (entry?.status) {
                                            DownloadStatus.COMPLETED -> Icon(Icons.Rounded.Check, "已下载", Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                                            DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING ->
                                                CircularProgressIndicator(progress = { (entry?.completedPages ?: 0).toFloat() / (entry?.totalPages ?: 1).coerceAtLeast(1) },
                                                    modifier = Modifier.size(11.dp).semantics { contentDescription = "下载中" },
                                                    strokeWidth = 1.dp, color = colors.onSurfaceVariant,
                                                    trackColor = colors.onSurface.copy(alpha = .10f))
                                            DownloadStatus.PAUSED -> Icon(Icons.Rounded.Pause, "下载已暂停", Modifier.size(11.dp), tint = colors.onSurfaceVariant)
                                            DownloadStatus.FAILED -> Icon(Icons.Rounded.Refresh, "下载未完成", Modifier.size(11.dp), tint = colors.error)
                                            null -> Unit
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
}

@Composable
internal fun ReaderPanelHeading(title: String, closeLabel: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        ReaderIcon(Icons.Rounded.Close, closeLabel, onClick = onDismiss)
    }
}

@Composable
private fun ReaderIcon(icon: ImageVector, label: String, enabled: Boolean = true, interactive: Boolean = true,
    emphasis: Float = .82f, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) emphasis else .25f)
    MotionIconButton(onClick = onClick, enabled = enabled && interactive, modifier = Modifier.size(48.dp)) {
        Icon(icon, label, Modifier.size(21.dp), tint = tint)
    }
}

private fun ReaderTheme.background() = when (this) { ReaderTheme.INK -> Color(0xFF101012); ReaderTheme.PAPER -> Color(0xFFF7F6F2); ReaderTheme.SEPIA -> Color(0xFFECE4D5) }
private fun ReaderTheme.foreground() = if (this == ReaderTheme.INK) Color.White else Color(0xFF242426)
private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

