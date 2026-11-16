package dev.mangaflow.reader

import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.chapterGroupLabel
import dev.mangaflow.ui.mangaNumberText

import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.data.ReaderSourceStandby
import dev.mangaflow.data.ReaderSourceStandbyPhase
import dev.mangaflow.data.sources.materializeJmPage
import dev.mangaflow.data.plugins.resolveScriptPage
import dev.mangaflow.data.plugins.materializeScriptPage
import dev.mangaflow.data.plugins.prefetchScriptPage
import dev.mangaflow.data.plugins.invalidateScriptImage
import dev.mangaflow.data.plugins.discardFailedScriptImage

import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.MangaLayout
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing

import dev.mangaflow.ui.motion.BackHandler
import dev.mangaflow.ui.motion.MotionBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import coil3.compose.AsyncImage
import coil3.annotation.ExperimentalCoilApi
import coil3.decode.BlackholeDecoder
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Scale
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.DownloadProgress
import dev.mangaflow.experience.*
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.PageCollection
import dev.mangaflow.storage.ReaderPreferences
import dev.mangaflow.reader.panels.GuidedPanelReader
import dev.mangaflow.reader.panels.PanelEngine
import dev.mangaflow.reader.panels.PanelReadingAvailable
import dev.mangaflow.reader.panels.PanelModelsDialog
import dev.mangaflow.reader.panels.panelLayoutKey
import dev.mangaflow.storage.ReaderTheme
import dev.mangaflow.storage.ReadingMode
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.LibraryRestoreStatus
import dev.mangaflow.ui.ChapterReadMark
import dev.mangaflow.ui.CompactGlassPopover
import dev.mangaflow.ui.ChapterSourcePicker
import dev.mangaflow.ui.ReaderSourceStack
import dev.mangaflow.ui.CompactMenuAction
import dev.mangaflow.ui.rememberChapterPositions
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.motion.BottomPullLoadState
import dev.mangaflow.ui.motion.bottomPullLoad
import dev.mangaflow.ui.motion.rememberBottomPullLoadState
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.CompactDownloadIcon
import dev.mangaflow.ui.CompactDownloadState
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.rememberDelayedBusy
import kotlin.math.roundToInt
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.mangaflow.reader.ReaderZoomableImage as ZoomableAsyncImage
import dev.mangaflow.reader.rememberReaderImageState as rememberZoomableImageState

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
    onReadVariant: (Chapter, ChapterVariant, Int, Int) -> Unit = { _, _, _, _ -> },
    onQuickReadVariant: (Chapter, ChapterVariant, Int, Int) -> Unit = { _, _, _, _ -> },
    sourceSwitchName: String = "",
    onSetPreferredSource: (String) -> Unit = {},
    onClearChapterSource: (Chapter) -> Unit = {},
    onCancelSourceSelection: () -> Unit = {},
    sourceLoading: Boolean = false,
    sourceError: String? = null,
    initialPageIndex: Int? = null,
    sourceResumePolicy: String = "ask",
    onSourceResumePolicy: (String) -> Unit = {},
    sourceStandbys: List<ReaderSourceStandby> = emptyList(),
    standbyAvailability: dev.mangaflow.data.ReaderSourceStandbyAvailability = dev.mangaflow.data.ReaderSourceStandbyAvailability(),
    nextChapterPreparation: dev.mangaflow.data.ReaderNextChapterPreparation? = null,
    onWarmSourceStandbys: (Chapter, Int, Int) -> Unit = { _, _, _ -> },
    onRetryStandby: (Chapter, String) -> Unit = { _, _ -> },
    onPrefetchPageFailed: (Chapter, Page) -> Unit = { _, _ -> },
    onCancelSourceStandbys: () -> Unit = {},
) {
    val libraryReady by libraryStore.ready.collectAsStateWithLifecycle()
    val adultImageContext = LocalContext.current
    val experience = remember(adultImageContext) { ExperiencePolicyStore.get(adultImageContext) }
    val experienceBudget by experience.state.collectAsStateWithLifecycle()
    if (!libraryReady) {
        BackHandler(enabled = active, onBack = onBack)
        LibraryRestoreStatus(libraryStore)
        return
    }
    var chapterId by rememberSaveable(manga.id, initialChapterId, initialCollection?.key) { mutableStateOf(initialChapterId) }
    var collectionPresentation by rememberSaveable(manga.id, initialCollection?.key) { mutableStateOf(initialCollection != null) }
    var collectionPositionApplied by rememberSaveable(manga.id, initialCollection?.key) { mutableStateOf(false) }
    val presentedCollection = initialCollection?.takeIf { collectionPresentation && it.chapter.id == chapterId }
    val globalPreferences by libraryStore.preferences.collectAsStateWithLifecycle()
    val mangaPreferences by libraryStore.mangaPreferences.collectAsStateWithLifecycle()
    val savedPreferences = remember(globalPreferences, mangaPreferences, manga.id) {
        mangaPreferences[manga.id] ?: globalPreferences
    }
    var collectionLayout by remember(manga.id, initialCollection?.key) { mutableStateOf<ReaderPreferences?>(null) }
    val preferences = remember(savedPreferences, presentedCollection, collectionLayout) {
        val layout = presentedCollection?.let {
            savedPreferences.copy(mode = collectionLayout?.mode ?: it.mode,
                doublePage = collectionLayout?.doublePage ?: it.spread,
                coverAlone = collectionLayout?.coverAlone ?: it.coverAlone)
        } ?: savedPreferences
        if (PanelReadingAvailable) layout else layout.copy(panelEngine = PanelEngine.OFF, panelLearnedOrder = false)
    }
    val pageCollections by libraryStore.pageCollections.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val readerForeground = active && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    DisposableEffect(experience, readerForeground) {
        val release = if (readerForeground) experience.startReading() else ({})
        onDispose { release() }
    }
    var restartPending by rememberSaveable(manga.id, initialChapterId, initialCollection?.key) {
        mutableStateOf(startAtBeginning && initialCollection == null)
    }
    var sourcePagePending by rememberSaveable(manga.id, initialChapterId, initialPageIndex, initialCollection?.key) {
        mutableStateOf(initialPageIndex != null && initialCollection == null)
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
    var currentPageRetrySerial by remember(chapterId) { mutableIntStateOf(0) }
    var currentPageRetryIndex by remember(chapterId) { mutableIntStateOf(-1) }
    var pages by remember(chapterId) { mutableStateOf<List<Page>>(emptyList()) }
    // Backup images change only this displayed session, not the canonical chapter or history.
    val pageRepairs = remember(chapterId, chapter.sourceId, chapter.sourceMangaId) {
        mutableStateMapOf<Int, ReaderPageRepair>()
    }
    var repairTarget by remember(chapterId) { mutableStateOf<Int?>(null) }
    var loading by remember(chapterId) { mutableStateOf(true) }
    var error by remember(chapterId) { mutableStateOf<String?>(null) }
    val chapterLoadAttempt = remember(manga.id, chapterId, chapter.sourceId, chapter.sourceMangaId,
        retry, initialCollection?.key, initialPageIndex) { ReaderLoadAttempt() }
    val latestChapterLoadAttempt by rememberUpdatedState(chapterLoadAttempt)
    var chapterLoadStage by remember(chapterLoadAttempt) { mutableStateOf("检查本机章节") }
    var chapterSlowLoading by remember(chapterLoadAttempt) { mutableStateOf(false) }
    var chapterWaitClock by remember(chapterLoadAttempt) { mutableStateOf<TimeMark?>(null) }
    DisposableEffect(chapterLoadAttempt) { onDispose { chapterLoadAttempt.active = false } }
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
    var sourceChapter by remember(manga.id) { mutableStateOf<Chapter?>(null) }
    // The source fan leaves the actual progress-row stack (or the visible error action).
    // ChapterSourcePicker retains this screen-coordinate origin through its closing flight.
    var sourceTriggerAnchor by remember(chapterId) { mutableStateOf<Rect?>(null) }
    var sourceCloseRequested by remember(manga.id) { mutableStateOf(false) }
    var sourcePickerPresent by remember(manga.id) { mutableStateOf(false) }
    val sourcePickerBlocking = sourceChapter != null || sourcePickerPresent
    val canChooseSource = !localImport && chapter.variants.size > 1
    val alternativeSources = remember(chapter.id, chapter.variants) {
        chapter.variants.filterNot { it.storageId == chapter.id }.distinctBy { it.storageId }
    }
    val currentSourceName = chapter.variants.firstOrNull { it.storageId == chapter.id }?.sourceName
    val sourceSwitchWait = remember(sourceLoading, sourceSwitchName) { ReaderLoadAttempt() }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var topControlsHeightPx by remember { mutableIntStateOf(0) }
    var bottomControlsHeightPx by remember { mutableIntStateOf(0) }
    var readerStatusInspecting by remember(chapterId) { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showPanelModels by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!PanelReadingAvailable) showPanelModels = false }
    var showComments by remember(chapterId) { mutableStateOf(false) }
    var showSpreadAdjustment by remember(chapterId) { mutableStateOf(false) }
    var manualSingles by remember(manga.id, chapterId, presentedCollection?.key) {
        mutableStateOf(presentedCollection?.singlePages ?: libraryStore.spreadSinglesFor(manga.id, chapterId))
    }
    var atChapterEnd by remember(chapterId, preferences.mode, preferences.doublePage, preferences.coverAlone, manualSingles,
        preferences.panelLayoutKey) {
        mutableStateOf(false)
    }
    var chapterChangeRequested by remember(chapterId) { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    val desktop = platformKind == PlatformKind.DESKTOP
    val readerWindow = LocalMangaWindow.current
    val readerFontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val dockedPanels = desktop && readerWindow.width >= 960.dp * readerFontScale &&
        readerWindow.height >= 480.dp
    val panelWidth = (if (readerWindow.width >= 1280.dp * readerFontScale) 336.dp else 304.dp) * readerFontScale
    val panelBlocksReading = !dockedPanels && (showSettings || showChapters)
    var retainedInspector by remember { mutableIntStateOf(0) }
    val inspector = if (showSettings) 1 else if (showChapters) 0 else retainedInspector
    SideEffect { if (showSettings || showChapters) retainedInspector = inspector }
    val topChromeInteraction = remember { MutableInteractionSource() }
    val bottomChromeInteraction = remember { MutableInteractionSource() }
    val topChromeHovered by topChromeInteraction.collectIsHoveredAsState()
    val bottomChromeHovered by bottomChromeInteraction.collectIsHoveredAsState()
    var topChromeFocused by remember { mutableStateOf(false) }
    var bottomChromeFocused by remember { mutableStateOf(false) }
    val chromeEngaged = desktop && (topChromeHovered || bottomChromeHovered || topChromeFocused || bottomChromeFocused)
    var keyboardFocusRestore by remember { mutableIntStateOf(0) }
    var downloadMessage by remember(chapterId) { mutableStateOf<String?>(null) }
    var displayedDownloadMessage by remember(chapterId) { mutableStateOf("") }
    var progressInteracting by remember(chapterId) { mutableStateOf(false) }
    val dimPreview = remember { mutableStateOf<Float?>(null) }
    var imageVisible by remember(chapterId) { mutableStateOf(false) }
    LaunchedEffect(readerForeground, loading, imageVisible) {
        experience.setReaderWaiting(readerForeground && (loading || !imageVisible))
    }
    var recentConfirmed by remember(manga.id, chapterId) { mutableStateOf(false) }
    var chapterVisibilityReported by remember(manga.id, chapterId) { mutableStateOf(false) }
    var nextChapterPrefetched by remember(manga.id, chapterId) { mutableStateOf(false) }
    var leavingReader by remember { mutableStateOf(false) }
    val nextChapter = siblings.getOrNull(chapterIndex + 1).takeIf { chapterIndex >= 0 }
    val previousChapter = siblings.getOrNull(chapterIndex - 1).takeIf { chapterIndex > 0 }
    val openPreviousChapter: (() -> Unit)? = previousChapter?.let { previous ->
        {
            if (active && !leavingReader && !sourcePickerBlocking && !chapterChangeRequested && chapterId == chapter.id) {
                chapterChangeRequested = true
                chapterId = previous.id
                controlsVisible = true
            }
        }
    }
    val openNextChapter: (() -> Unit)? = nextChapter?.let { next ->
        {
            if (active && !leavingReader && !sourcePickerBlocking && !chapterChangeRequested && chapterId == chapter.id) {
                chapterChangeRequested = true
                chapterId = next.id
                controlsVisible = true
            }
        }
    }
    val chapterVisible by rememberUpdatedState(onChapterVisible)
    val nearChapterEnd by rememberUpdatedState(onNearChapterEnd)
    val scope = rememberCoroutineScope()
    val sourceIdentity = ReaderSourceIdentity(chapter.logicalId.ifBlank { chapter.id },
        chapter.id, chapter.sourceId, chapter.sourceMangaId)
    val warmSourceStandbys by rememberUpdatedState(onWarmSourceStandbys)
    val prefetchedPageFailed by rememberUpdatedState(onPrefetchPageFailed)
    val cancelSourceStandbys by rememberUpdatedState(onCancelSourceStandbys)
    val currentSourceStandbys = remember(sourceStandbys, manga.id, chapter.id) {
        sourceStandbys.filter { it.workId == manga.id && it.chapterId == chapter.id }
    }
    val readySourceVariants = remember(currentSourceStandbys) {
        currentSourceStandbys.filter { it.targetPageIndex != null && it.targetPrepared }.associateBy { it.variantId }
    }
    val standbyActive = readerForeground && !leavingReader && !chapterChangeRequested
    DisposableEffect(sourceIdentity, standbyActive) {
        if (!standbyActive) cancelSourceStandbys()
        onDispose { cancelSourceStandbys() }
    }
    LaunchedEffect(sourceIdentity, chapter, pageIndex, pages, standbyActive, loading, error,
        sourcePickerBlocking, chapterChangeRequested, experienceBudget.standbySources) {
        // A stalled or failed visible image is exactly when spare sources matter. Do not
        // gate standby preparation on the visible image reaching READY.
        if (!standbyActive || pages.isEmpty() ||
            sourcePickerBlocking || chapterChangeRequested) return@LaunchedEffect
        // A finite intent delay follows the actual cursor; rapid paging replaces stale work.
        delay(350)
        warmSourceStandbys(chapter, pageIndex.coerceIn(pages.indices), pages.size)
    }
    val latestReadingIdentity by rememberUpdatedState(manga.id to sourceIdentity)
    val reportPrefetchFailure = remember(manga.id, sourceIdentity) {
        { page: Page ->
            if (latestReadingIdentity == (manga.id to sourceIdentity) && pages.any { it == page })
                prefetchedPageFailed(chapter, page)
        }
    }
    var headers by remember(manga.id, chapterId, chapter.sourceId, chapter.sourceMangaId, repository, localImport) {
        mutableStateOf(if (localImport) emptyMap() else
            repository.imageHeaders(chapterSourceMangaId(manga.id, chapter), chapterId).toMap())
    }
    val preparationTracker = remember(sourceIdentity, pages, headers, preferences.mode, preferences.panelLayoutKey,
        preferences.doublePage, preferences.coverAlone) { ReaderPreparationTracker(chapter.sourceId, pages.size) }
    val preparation by preparationTracker.state.collectAsStateWithLifecycle()
    val chapterDownload = remember(downloadStore, manga.id, chapterId) { downloadStore.observeChapter(manga.id, chapterId) }
    val initialDownload = remember(downloadStore, manga.id, chapterId) {
        downloadStore.entries.value.firstOrNull { it.manga.id == manga.id && it.chapter.id == chapterId }
    }
    val activeDownload by chapterDownload.collectAsStateWithLifecycle(initialValue = initialDownload)
    val storageCache = remember(adultImageContext) { dev.mangaflow.storage.StorageCacheStore.get(adultImageContext) }
    // The retained reader owns this chapter even while settings are overlaid or the app is paused.
    DisposableEffect(storageCache, manga.id, chapterId, active) {
        val release = if (active) storageCache.protectReading(manga.id, chapterId, chapterSourceMangaId(manga.id, chapter)) else ({})
        onDispose { release() }
    }
    LaunchedEffect(storageCache, manga.id, chapterId, pages, headers) {
        storageCache.registerPages(manga.id, chapterId,
            dev.mangaflow.data.SourceKeys.decodeChapter(chapterSourceMangaId(manga.id, chapter), chapterId).sourceId,
            pages, headers)
    }
    val background = preferences.theme.background()
    val colors = MaterialTheme.colorScheme
    val lightChrome = colors.surface.luminance() > .5f
    val readerBackdrop = rememberHazeState()
    val readingChapterId = chapterId
    val resolvePage: ReaderPageResolver = remember(manga.id, chapterId, chapter.sourceId,
        chapter.sourceMangaId, downloadStore, adultImageContext) {
        { page, onBytes ->
            val work = currentCoroutineContext()[ReaderPreparationWorkContext]?.work
            val local = if (work == null) downloadStore.resolveReaderPage(manga.id, readingChapterId, page)
                else work.during(PrefetchStage.RESOLVING) {
                    experience.withResolveAdmission({ true }, { work.admission(PrefetchStage.RESOLVING, it) }) {
                        downloadStore.resolveReaderPage(manga.id, readingChapterId, page)
                    }
                }
            if (local.url.startsWith("file:")) local else {
                val owner = chapterSourceMangaId(manga.id, chapter)
                val resolved = resolveScriptPage(adultImageContext, owner, readingChapterId, local, work = work)
                val materialized = materializeScriptPage(adultImageContext, owner, readingChapterId, resolved, onBytes = onBytes, work = work)
                if (dev.mangaflow.data.SourceKeys.decodeChapter(owner, readingChapterId).sourceId == "jmcomic")
                    if (work == null) experience.withImageAdmission(reader = true) {
                        materializeJmPage(adultImageContext, owner, readingChapterId, materialized) ?: materialized
                    } else work.during(PrefetchStage.DOWNLOADING) {
                        experience.withImageAdmission(reader = true, onWaiting = { work.admission(PrefetchStage.DOWNLOADING, it) }) {
                            materializeJmPage(adultImageContext, owner, readingChapterId, materialized) ?: materialized
                        }
                    }
                else materialized
            }
        }
    }
    val observePageTransfer: (Page) -> Flow<DownloadProgress?> = remember(downloadStore, manga.id, readingChapterId) {
        { page -> downloadStore.observePageTransfer(manga.id, readingChapterId, page.index, expectedPage = page) }
    }
    val prefetchPage: suspend (Page) -> Page? = remember(manga.id, chapterId, downloadStore) {
        { page -> downloadStore.resolvePrefetchPage(manga.id, readingChapterId, page) }
    }
    val scriptPrefetch: suspend (Page, PrefetchByteBudget, PrefetchWork) -> Page? =
        remember(manga.id, chapterId, chapter.sourceId, chapter.sourceMangaId, downloadStore, adultImageContext) {
            { page, budget, work ->
                val local = downloadStore.resolvePrefetchPage(manga.id, readingChapterId, page)
                if (local == null || local.url.startsWith("file:")) local else
                    prefetchScriptPage(adultImageContext, chapterSourceMangaId(manga.id, chapter),
                        readingChapterId, local, budget, work)
            }
        }
    val retryPage: ReaderPageResolver = remember(manga.id, chapterId, sourceIdentity, repository, downloadStore, adultImageContext) {
        { sourcePage, onBytes ->
            val work = currentCoroutineContext()[ReaderPreparationWorkContext]?.work
            val requestedIdentity = manga.id to sourceIdentity
            if (localImport) throw IllegalStateException("本地文件已损坏，请删除后重新导入")
            val expectedEntry = downloadStore.entryFor(manga.id, readingChapterId)
            invalidateScriptImage(chapterSourceMangaId(manga.id, chapter), readingChapterId, sourcePage.sourceImageToken)
            val retried = if (expectedEntry?.needsSourceRefresh == true) {
                val route = downloadStore.routeForReadingDownload(manga.id, readingChapterId, expectedEntry)
                val prepared = dev.mangaflow.data.foregroundRequest("刷新下载图片地址") {
                    repository.prepareDownload(adultImageContext, chapterSourceMangaId(manga.id, chapter),
                        readingChapterId, route, forceRefresh = true)
                }
                val replacement = prepared.pages.firstOrNull { it.index == sourcePage.index }
                    ?: throw IllegalStateException("刷新后没有找到这一页，请重新打开章节")
                val saved = downloadStore.refreshReadingDownload(manga, chapter, prepared.pages,
                    prepared.headers, expectedEntry, route = route)
                if (!saved) throw CancellationException("下载任务已变化，请重新操作。")
                if (chapterId == readingChapterId && latestReadingIdentity == requestedIdentity) {
                    headers = prepared.headers.toMap()
                    pages = prepared.pages
                    pageIndex = pageIndex.coerceIn(0, prepared.pages.lastIndex)
                }
                downloadStore.resolveReaderPage(manga.id, readingChapterId, replacement)
            } else downloadStore.retryReaderPage(manga.id, readingChapterId, sourcePage)
            if (retried.url.startsWith("file:")) retried else {
                val owner = chapterSourceMangaId(manga.id, chapter)
                val resolved = resolveScriptPage(adultImageContext, owner, readingChapterId, retried, work = work)
                val materialized = materializeScriptPage(adultImageContext, owner, readingChapterId, resolved, onBytes = onBytes, work = work)
                if (dev.mangaflow.data.SourceKeys.decodeChapter(owner, readingChapterId).sourceId == "jmcomic")
                    if (work == null) experience.withImageAdmission(reader = true) {
                        materializeJmPage(adultImageContext, owner, readingChapterId, materialized) ?: materialized
                    } else work.during(PrefetchStage.DOWNLOADING) {
                        experience.withImageAdmission(reader = true, onWaiting = { work.admission(PrefetchStage.DOWNLOADING, it) }) {
                            materializeJmPage(adultImageContext, owner, readingChapterId, materialized) ?: materialized
                        }
                    }
                else materialized
            }
        }
    }
    val repairSnapshot = pageRepairs.toMap()
    val displayedPages = remember(pages, repairSnapshot) {
        pages.mapIndexed { index, page ->
            repairSnapshot[index]?.page?.takeIf { it.index == page.index } ?: page
        }
    }
    // Keep callbacks stable: AdjacentPagePrefetch observes their identity, and a fresh
    // lambda on every reader recomposition would repeatedly cancel in-flight warmups.
    val displayedResolve: ReaderPageResolver = remember(resolvePage) {
        { page, onBytes -> if (page.sourceRouteId.isNotBlank()) page else resolvePage(page, onBytes) }
    }
    val displayedRetry: ReaderPageResolver = remember(retryPage) {
        { page, onBytes -> if (page.sourceRouteId.isNotBlank()) page else retryPage(page, onBytes) }
    }
    val displayedPrefetch: suspend (Page) -> Page? = remember(prefetchPage) {
        { page -> if (page.sourceRouteId.isNotBlank()) page else prefetchPage(page) }
    }
    val displayedScriptPrefetch: suspend (Page, PrefetchByteBudget, PrefetchWork) -> Page? =
        remember(scriptPrefetch) {
            { page, budget, work ->
                if (page.sourceRouteId.isNotBlank()) page else scriptPrefetch(page, budget, work)
            }
        }
    LaunchedEffect(storageCache, repairSnapshot) {
        repairSnapshot.values.forEach { repair ->
            storageCache.registerPages(manga.id, chapterId, repair.variant.sourceId,
                listOf(repair.page), repair.headers)
        }
    }
    val motionEnabled = LocalMotionEnabled.current
    val sourceArrival = remember(manga.id) { Animatable(1f) }
    var lastVisibleSource by remember(manga.id) { mutableStateOf<ReaderSourceIdentity?>(null) }
    var sourceArrivalPending by remember(manga.id) { mutableStateOf(false) }
    LaunchedEffect(sourceIdentity, motionEnabled) {
        val previous = lastVisibleSource
        sourceArrivalPending = motionEnabled && previous != null &&
            (previous.sourceId != sourceIdentity.sourceId || previous.sourceMangaId != sourceIdentity.sourceMangaId ||
                (previous.logicalId == sourceIdentity.logicalId && previous.chapterId != sourceIdentity.chapterId))
        sourceArrival.snapTo(if (sourceArrivalPending) 0f else 1f)
    }
    LaunchedEffect(sourceIdentity, imageVisible, loading, error, motionEnabled) {
        if (!motionEnabled || error != null) {
            sourceArrival.snapTo(1f)
            sourceArrivalPending = false
        } else if (imageVisible && !loading) {
            lastVisibleSource = sourceIdentity
            if (sourceArrivalPending) {
                sourceArrival.animateTo(1f, tween(MangaMotion.Content, easing = MangaMotion.EnterEasing))
                sourceArrivalPending = false
            }
        }
    }
    val toolbarTravel = with(LocalDensity.current) { 12.dp.roundToPx() }
    val touchExploration = rememberReaderTouchExploration()
    val delayedLoading = rememberDelayedBusy(loading)
    val chapterWaitVisible = readerForeground && !leavingReader && !panelBlocksReading && !showPanelModels &&
        !showComments && !showSpreadAdjustment && !showPageNavigator && !sourcePickerBlocking
    LaunchedEffect(chapterLoadAttempt, chapterWaitVisible, loading, error) {
        chapterSlowLoading = false
        if (chapterWaitVisible && loading && error == null && chapterLoadAttempt.acceptsResult) {
            chapterWaitClock = TimeSource.Monotonic.markNow()
            delay(READER_SLOW_LOAD_MILLIS)
            chapterSlowLoading = true
            delay(25_000L - READER_SLOW_LOAD_MILLIS)
            if (chapterWaitVisible && loading && error == null && latestChapterLoadAttempt === chapterLoadAttempt &&
                chapterLoadAttempt.acceptsResult) {
                chapterLoadAttempt.stopped = true
                error = "打开章节超时"
                loading = false
            }
        }
    }
    // Old page panes may save their own cursor while a source switch disposes them.
    val chapterPageCount = pages.size

    val clock = rememberReaderClock(active && !leavingReader)
    val readingEligible = active && !leavingReader && !loading && error == null && imageVisible &&
        !panelBlocksReading && !showPanelModels && !showComments && !showSpreadAdjustment && !showPageNavigator &&
        !showReaderActions && !readerActionsPresent && !sourcePickerBlocking && !readerStatusInspecting
    RecordReaderActivity(manga, readingEligible, libraryStore, chapterId) { owner, confirmedChapter ->
        if (owner == manga.id && confirmedChapter == chapterId && readingEligible) {
            recentConfirmed = true
            libraryStore.saveProgress(manga, chapter, pageIndex, chapterPageCount, cursor.scrollOffset,
                panelIndex = if (preferences.panelEngine != PanelEngine.OFF) cursor.panelIndex else -1,
                panelLayout = if (preferences.panelEngine != PanelEngine.OFF) preferences.panelLayoutKey else "")
        }
    }
    // A displayed/preloaded image is not itself a confirmed reading event. The cloud
    // chapter follows the same deliberate navigation/dwell confirmation as local recent.
    LaunchedEffect(manga.id, chapterId, recentConfirmed, readingEligible, readerForeground) {
        if (recentConfirmed && readingEligible && readerForeground && chapterId == chapter.id && !chapterVisibilityReported) {
            chapterVisibilityReported = true
            chapterVisible(manga, chapter)
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
            panelEngine = next.panelEngine,
            panelDirection = next.panelDirection,
            panelLearnedOrder = next.panelLearnedOrder,
        )
        if (next.panelLayoutKey != preferences.panelLayoutKey) cursor.panelIndex = 0
        if (presentedCollection != null) collectionLayout = next
        libraryStore.updateMangaPreferences(manga.id, saved)
    }
    fun picture(first: Int, last: Int = first): PageCollection? {
        if (first !in pages.indices || last !in first..pages.lastIndex) return null
        return PageCollection(manga, chapter, first, last,
            previewUrls = (first..last).map { pages[it].url }, mode = preferences.mode,
            coverAlone = preferences.coverAlone, singlePages = manualSingles)
    }
    val spreadGroups = remember(pages, preferences.mode, preferences.doublePage, preferences.coverAlone, manualSingles,
        preferences.panelEngine) {
        if (preferences.panelEngine == PanelEngine.OFF && preferences.mode != ReadingMode.VERTICAL && preferences.doublePage)
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

    fun closeReaderSettings() {
        dimPreview.value?.takeIf { it != preferences.dimAmount }?.let { amount ->
            updatePreferences(preferences.copy(dimAmount = amount))
        }
        showSettings = false
        keyboardFocusRestore++
    }
    fun toggleReaderSettings() {
        if (showSettings) closeReaderSettings() else {
            showChapters = false
            showSettings = true
            controlsVisible = true
        }
    }
    fun toggleReaderChapters() {
        if (showSettings) closeReaderSettings()
        showChapters = !showChapters
        controlsVisible = true
        if (!showChapters) keyboardFocusRestore++
    }
    val settingsPanel: @Composable (Boolean) -> Unit = { embedded ->
        ReaderSettingsDialog(
            visible = showSettings && !showPanelModels, preferences = preferences, backdrop = readerBackdrop,
            onModels = { showPanelModels = true }, embedded = embedded,
            sourceResumePolicy = sourceResumePolicy, onSourceResumePolicy = onSourceResumePolicy,
            onDimPreview = { dimPreview.value = it }, update = { next ->
                if (next.mode != preferences.mode || next.doublePage != preferences.doublePage ||
                    next.coverAlone != preferences.coverAlone) cursor.scrollOffset = 0
                updatePreferences(next)
            }, onDismiss = ::closeReaderSettings,
        )
    }
    val chaptersPanel: @Composable (Boolean) -> Unit = { embedded ->
        ReaderChaptersDialog(
            visible = showChapters, chapters = chapters, currentId = chapterId,
            manga = manga, library = libraryStore, backdrop = readerBackdrop,
            downloadStore = downloadStore, loading = chaptersLoading, error = chaptersError, onRetry = onRetryChapters,
            embedded = embedded,
            onSelect = {
                chapterId = it
                if (!dockedPanels) showChapters = false
                controlsVisible = true
                keyboardFocusRestore++
            },
            onDismiss = { showChapters = false; keyboardFocusRestore++ },
        )
    }

    if (active) ReaderWindow(preferences.keepScreenOn, controlsVisible || showSettings || showPanelModels || showChapters || showSpreadAdjustment || showComments || sourcePickerBlocking, lightChrome)
    val leaveReader = { leavingReader = true; onBack() }
    MotionBackHandler(enabled = active, onBack = leaveReader)
    LaunchedEffect(active) { leavingReader = !active }
    LaunchedEffect(initialChapterId) {
        if (sourceChapter != null && sourceChapter?.id != initialChapterId) sourceCloseRequested = true
    }
    LaunchedEffect(active, leavingReader) {
        if ((!active || leavingReader) && sourceChapter != null && !sourceCloseRequested) {
            onCancelSourceSelection()
            sourceChapter = null
        }
    }

    LaunchedEffect(chapterId, chapter.sourceId, chapter.sourceMangaId, retry, initialCollection?.key, initialPageIndex,
        chapterLoadAttempt.stopped) {
        if (!chapterLoadAttempt.acceptsResult) return@LaunchedEffect
        val requestedAttempt = chapterLoadAttempt
        val requestedManga = manga
        val requestedChapterId = chapterId
        val requestedChapter = chapter
        loading = true
        error = null
        try {
            // A stable source manifest represents the chapter, even when only some pages are saved.
            // File selection belongs to each image request and never creates a separate offline mode.
            val stored = downloadStore.storedPages(requestedManga.id, requestedChapterId)
            val loaded = stored ?: if (localImport) {
                throw IllegalStateException("本地章节不存在，请重新导入")
            } else dev.mangaflow.data.foregroundRequest("章节图片") {
                chapterLoadStage = "获取图片目录"
                repository.pages(chapterSourceMangaId(requestedManga.id, requestedChapter), requestedChapterId, forceRefresh = retry > 0)
            }
            currentCoroutineContext().ensureActive()
            if (chapterId != requestedChapterId || latestChapterLoadAttempt !== requestedAttempt ||
                !requestedAttempt.acceptsResult) return@LaunchedEffect
            if (loaded.isEmpty()) throw IllegalStateException("这一章还没有可读取的图片")
            chapterLoadStage = "恢复阅读位置"
            val sourcePage = initialPageIndex?.takeIf {
                sourcePagePending && requestedChapterId == initialChapterId && initialCollection == null
            }
            val restarting = restartPending && chapterId == initialChapterId && initialCollection == null && sourcePage == null
            val saved = if (restarting || sourcePage != null) null else withContext(Dispatchers.IO) {
                libraryStore.progressFor(requestedManga.id, requestedChapterId)
            }
            val collection = initialCollection?.takeIf { collectionPresentation && !collectionPositionApplied && it.chapter.id == chapterId }
            pageIndex = (sourcePage ?: collection?.firstPage ?: saved?.pageIndex ?: 0).coerceIn(0, loaded.lastIndex)
            cursor.scrollOffset = if (sourcePage != null || collection != null) 0 else saved?.scrollOffset ?: 0
            cursor.panelIndex = if (sourcePage == null && collection == null && saved?.panelLayout == preferences.panelLayoutKey)
                saved.panelIndex.coerceAtLeast(0) else 0
            if (collection != null) collectionPositionApplied = true
            val restartSaved = !restarting || withContext(Dispatchers.IO) {
                try {
                    libraryStore.saveProgress(requestedManga, requestedChapter, 0, loaded.size,
                        clearLegacyLongImagePosition = true)
                    true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            }
            currentCoroutineContext().ensureActive()
            if (chapterId != requestedChapterId || latestChapterLoadAttempt !== requestedAttempt ||
                !requestedAttempt.acceptsResult) return@LaunchedEffect
            pages = loaded
            if (sourcePage != null) sourcePagePending = false
            restartPending = false
            if (!restartSaved) downloadMessage = "阅读位置暂未保存，可继续阅读"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (latestChapterLoadAttempt === requestedAttempt && requestedAttempt.acceptsResult)
                error = failure.message ?: "章节暂时无法读取"
        } finally {
            if (latestChapterLoadAttempt === requestedAttempt && requestedAttempt.acceptsResult) loading = false
        }
    }

    // Legacy picture migration is auxiliary persistence: valid chapter images must
    // render even if this disk write is slow or fails. Original markers are retained.
    LaunchedEffect(manga.id, chapterId, pages, libraryStore) {
        val migrationManga = manga
        val migrationChapter = chapter
        val migrationPages = pages
        if (migrationPages.isEmpty()) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                libraryStore.migratePageBookmarks(migrationManga, migrationChapter, migrationPages)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { downloadMessage = "旧画面收藏暂未迁移，原记录已保留" }
    }

    LaunchedEffect(chapterId, pageIndex, pages.isNotEmpty()) {
        if (pages.isNotEmpty()) downloadStore.prioritizeReadingPage(manga.id, chapterId, pageIndex)
    }

    LaunchedEffect(controlsVisible, showSettings, showChapters, showSpreadAdjustment, showComments,
        showPageNavigator, showReaderActions, readerActionsPresent, sourcePickerBlocking, readerStatusInspecting,
        loading, error, imageVisible, progressInteracting, touchExploration, chromeEngaged) {
        if (controlsVisible && !showSettings && !showChapters && !showSpreadAdjustment && !showComments &&
            !showPageNavigator && !showReaderActions && !readerActionsPresent && !sourcePickerBlocking && !readerStatusInspecting &&
            !loading && error == null && imageVisible && !progressInteracting && !touchExploration && !chromeEngaged) {
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
    LaunchedEffect(atChapterEnd, touchExploration, chromeEngaged, readerStatusInspecting) {
        if (atChapterEnd && !touchExploration && !chromeEngaged && !readerStatusInspecting) controlsVisible = false
    }

    val keyboard = LocalReaderKeyboard.current
    val keyboardFocus = remember { FocusRequester() }
    var keyboardFocused by remember { mutableStateOf(false) }
    var keyboardSerial by remember(chapterId) { mutableIntStateOf(0) }
    var keyboardRequest by remember(chapterId) { mutableStateOf<ReaderKeyboardRequest?>(null) }
    val keyboardReady = keyboard != null && readingEligible && !showPageNavigator &&
        preferences.panelEngine == PanelEngine.OFF
    LaunchedEffect(keyboardReady, keyboardFocusRestore) {
        // Let closing chrome settle first. An open inspector keeps its own focused editor.
        if (keyboardReady && !showSettings && !showChapters) {
            withFrameNanos { }
            keyboardFocus.requestFocus()
        }
    }
    val keyboardModifier = if (keyboard == null) Modifier else Modifier
        .focusRequester(keyboardFocus)
        .onFocusChanged { keyboardFocused = it.isFocused }
        // Bubble only while this reading surface itself owns focus. Editors,
        // dialogs and their text/IME handling never share its shortcut target.
        .onKeyEvent { event ->
            if (!keyboardReady || !keyboardFocused || event.type != KeyEventType.KeyDown) false
            else keyboard.actionFor(event, preferences.mode == ReadingMode.RIGHT_TO_LEFT)?.let { action ->
                if (action == ReaderKeyboardAction.TOGGLE_CONTROLS) controlsVisible = !controlsVisible
                else keyboardRequest = ReaderKeyboardRequest(++keyboardSerial, action)
                true
            } ?: false
        }.focusable(enabled = keyboardReady)

    CompositionLocalProvider(LocalReaderActionFinished provides { if (desktop) keyboardFocusRestore++ },
        LocalReaderPreparationTracker provides preparationTracker) {
    ReaderWorkspace(
        panelVisible = dockedPanels && (showSettings || showChapters),
        panelWidth = panelWidth,
        panel = {
            MotionTabContent(selected = inspector, modifier = Modifier.fillMaxSize(), soft = true) { target ->
                if (target == 1) settingsPanel(true) else chaptersPanel(true)
            }
            BackHandler(active && (showSettings || showChapters)) {
                if (showSettings) closeReaderSettings()
                else { showChapters = false; keyboardFocusRestore++ }
            }
        },
    ) {
    Box(Modifier.fillMaxSize().then(keyboardModifier)) {
        // Sample the existing artwork and dim layer; controls stay outside their own backdrop.
        Box(Modifier.fillMaxSize().graphicsLayer {
            // Only a real source handoff arms this finite arrival. Keep the current
            // image readable, preserve its own zoom/cursor, and never fade to empty.
            alpha = .90f + .10f * sourceArrival.value
            translationX = (1f - sourceArrival.value) * 6.dp.toPx()
        }.mangaHazeSource(readerBackdrop).background(background)
            .then(if (loading || error != null) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "显示或收起阅读工具",
                onClick = { controlsVisible = !controlsVisible },
            ) else Modifier)) {
            when {
                loading || error != null -> if (chapterWaitVisible && (delayedLoading || error != null)) ReaderImageRecovery(
                    index = null, failure = error.orEmpty(), pending = loading,
                    slow = chapterSlowLoading, stage = chapterLoadStage,
                    sourceName = currentSourceName.orEmpty(), attempt = chapterLoadAttempt,
                    startedAt = chapterWaitClock ?: chapterLoadAttempt.startedAt,
                    foreground = preferences.theme.foreground(), modifier = Modifier.align(Alignment.Center),
                    alternatives = alternativeSources,
                    readySourceVariants = readySourceVariants,
                    onRetry = { chapterLoadAttempt.stopped = true; retry++ },
                    onSwitch = { variant -> onReadVariant(chapter, variant, pageIndex, pages.size) },
                    onMore = { anchor ->
                        sourceTriggerAnchor = anchor
                        sourceCloseRequested = false; sourceChapter = chapter; controlsVisible = true
                    },
                    onControls = { controlsVisible = true },
                    onStop = {
                        chapterLoadAttempt.stopped = true
                        error = "已停止等待"; loading = false
                    },
                )
                pages.isNotEmpty() -> key(chapterId, preferences.mode, preferences.doublePage, preferences.coverAlone, manualSingles,
                    preferences.panelLayoutKey) {
                    if (preferences.panelEngine != PanelEngine.OFF) GuidedPanelReader(
                        pages = displayedPages, headers = headers,
                        resolvePage = displayedResolve, retryPage = displayedRetry,
                        observePageTransfer = observePageTransfer,
                        sourceId = chapter.sourceId, prefetchPage = displayedPrefetch, scriptPrefetch = displayedScriptPrefetch,
                        sourceName = currentSourceName.orEmpty(), sourceAlternatives = alternativeSources,
                        readySourceVariants = readySourceVariants, onPrefetchFailure = reportPrefetchFailure,
                        externalRetrySerial = currentPageRetrySerial,
                        onShowControls = { controlsVisible = true },
                        recoveryForeground = preferences.theme.foreground(),
                        onQuickSwitch = { failedIndex, variant ->
                            onQuickReadVariant(chapter, variant, failedIndex, pages.size)
                        },
                        onRepairPage = { index -> repairTarget = index },
                        onMoreSources = { anchor ->
                            sourceTriggerAnchor = anchor
                            sourceCloseRequested = false; sourceChapter = chapter; controlsVisible = true
                        },
                        preferences = preferences, initialPage = pageIndex, initialPanel = cursor.panelIndex,
                        targetPage = targetPage,
                        onTargetConsumed = { targetPage = null; targetOffset = 0 },
                        onLocation = { index, panel -> pageIndex = index; cursor.panelIndex = panel; cursor.scrollOffset = 0 },
                        onSave = { index, panel -> libraryStore.saveProgress(manga, chapter, index, chapterPageCount,
                            updateRecent = recentConfirmed && chapterId == chapter.id && active && !leavingReader,
                            panelIndex = panel, panelLayout = preferences.panelLayoutKey) },
                        onReadingNavigation = { if (chapterId == chapter.id && readingEligible) recentConfirmed = true },
                        onVisibleImageChanged = { visible ->
                            imageVisible = visible
                        },
                        onNearEnd = {
                            val next = nextChapter
                            if (experienceBudget.crossChapter && readerForeground && !leavingReader && !sourcePickerBlocking && chapterId == chapter.id && !nextChapterPrefetched && next != null) {
                                nextChapterPrefetched = true
                                nearChapterEnd(manga, next)
                            }
                        },
                        onAtEndChanged = { atChapterEnd = it },
                        onTap = { controlsVisible = !controlsVisible }, onNext = openNextChapter,
                        onDisable = { updatePreferences(preferences.copy(panelEngine = PanelEngine.OFF)) },
                        active = readerForeground && !leavingReader && !panelBlocksReading && !showPanelModels &&
                            !showSpreadAdjustment && !showComments && !showPageNavigator && !sourcePickerBlocking && !chapterChangeRequested,
                        backdrop = readerBackdrop,
                        bottomClearancePx = if (controlsVisible) bottomControlsHeightPx else 0,
                    ) else ChapterPages(
                        pages = displayedPages, sourceId = chapter.sourceId,
                        prefetchPage = displayedPrefetch, scriptPrefetch = displayedScriptPrefetch,
                        headers = headers,
                        resolvePage = displayedResolve,
                        retryPage = displayedRetry,
                        observePageTransfer = observePageTransfer,
                        sourceAlternatives = alternativeSources,
                        readySourceVariants = readySourceVariants, onPrefetchFailure = reportPrefetchFailure,
                        sourceName = currentSourceName.orEmpty(),
                        onShowControls = { controlsVisible = true },
                        retryTargetIndex = currentPageRetryIndex, retryPulse = currentPageRetrySerial,
                        onQuickSwitch = { failedIndex, variant ->
                            onQuickReadVariant(chapter, variant, failedIndex, pages.size)
                        },
                        onRepairPage = { index -> repairTarget = index },
                        onMoreSources = { anchor ->
                            sourceTriggerAnchor = anchor
                            sourceCloseRequested = false
                            sourceChapter = chapter
                            controlsVisible = true
                        },
                        preferences = preferences,
                        manualSingles = manualSingles,
                        initialPage = pageIndex,
                        initialOffset = cursor.scrollOffset,
                        targetPage = targetPage,
                        targetOffset = targetOffset,
                        keyboardRequest = keyboardRequest,
                        onKeyboardConsumed = { serial -> if (keyboardRequest?.serial == serial) keyboardRequest = null },
                        onTargetConsumed = { targetPage = null; targetOffset = 0 },
                        onLocation = { index, offset -> cursor.scrollOffset = offset; pageIndex = index },
                        onSave = { index, offset -> libraryStore.saveProgress(manga, chapter, index, chapterPageCount, offset,
                            clearLegacyLongImagePosition = preferences.mode == ReadingMode.VERTICAL,
                            updateRecent = recentConfirmed && chapterId == chapter.id && active && !leavingReader) },
                        onReadingNavigation = {
                            if (chapterId == chapter.id && readingEligible) recentConfirmed = true
                        },
                        longImagePosition = { index -> libraryStore.longImagePositionFor(manga.id, chapter.id, index) },
                        onVisibleImageChanged = { visible ->
                            imageVisible = visible
                        },
                        onNearEnd = {
                            val next = nextChapter
                            if (experienceBudget.crossChapter && readerForeground && !leavingReader && !sourcePickerBlocking && chapterId == chapter.id && !nextChapterPrefetched && next != null) {
                                nextChapterPrefetched = true
                                nearChapterEnd(manga, next)
                            }
                        },
                        onTap = {
                            if (keyboardReady) keyboardFocus.requestFocus()
                            controlsVisible = !controlsVisible
                        },
                        active = readerForeground && !leavingReader && !panelBlocksReading &&
                            !showSpreadAdjustment && !showComments && !sourcePickerBlocking && !chapterChangeRequested,
                        onAtEndChanged = { atChapterEnd = it },
                        onNext = openNextChapter,
                        onPrevious = openPreviousChapter,
                        startContent = { pull ->
                            ChapterEndFooter(pull, previousChapter?.title, "已到第一话", colors.onSurface,
                                openPreviousChapter, null, previous = true)
                        },
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

        AnimatedVisibility(visible = sourceLoading && sourceSwitchName.isNotBlank() || sourceError != null,
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = 60.dp),
            enter = fadeIn(tween(if (motionEnabled) MangaMotion.Quick else 0)),
            exit = fadeOut(tween(if (motionEnabled) MangaMotion.Quick else 0))) {
            Row(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp)
                .background(colors.surface.copy(alpha = .94f), RoundedCornerShape(14.dp))
                .padding(start = 13.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (sourceLoading) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.4.dp,
                    color = colors.onSurfaceVariant)
                Column(Modifier.weight(1f, fill = false)) {
                    Text(if (sourceLoading) "准备来源 · $sourceSwitchName"
                        else readerPageErrorLabel(sourceError.orEmpty()) + " · 已保留当前页",
                        fontSize = 12.sp, color = colors.onSurface, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    if (sourceLoading) ReaderWaitCaption(sourceSwitchWait.startedAt, true, colors.onSurfaceVariant)
                }
                if (!sourceLoading) TextButton(onClick = {
                    sourceCloseRequested = false; sourceChapter = chapter; controlsVisible = true
                }) { Text("其他来源", fontSize = 11.sp, color = colors.onSurface) }
                ReaderIcon(Icons.Rounded.Close, if (sourceLoading) "停止换源" else "关闭换源提示",
                    interactive = active && !leavingReader, onClick = onCancelSourceSelection)
            }
        }

        if (!localImport && active && !leavingReader && atChapterEnd && !loading && error == null &&
            !showComments && !showSettings && !showChapters && !showSpreadAdjustment &&
            !showReaderActions && !readerActionsPresent && !showPageNavigator && !sourcePickerBlocking &&
            !readerStatusInspecting && !progressInteracting) {
            ChapterEndComments(
                chapterId = chapterId, state = discussionState, backdrop = readerBackdrop,
                bottomClearance = if (controlsVisible) with(LocalDensity.current) {
                    (bottomControlsHeightPx - dev.mangaflow.reader.readerNavigationInsets().getBottom(this)).coerceAtLeast(0).toDp()
                } else 0.dp,
                touchExploration = touchExploration,
                onOpen = { showComments = true },
                modifier = Modifier.align(Alignment.BottomStart)
                    .windowInsetsPadding(dev.mangaflow.reader.readerNavigationInsets().union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }

        AnimatedVisibility(
            controlsVisible, Modifier.align(Alignment.TopCenter).onSizeChanged { topControlsHeightPx = it.height },
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
                modifier = Modifier.widthIn(max = MangaLayout.ListWidth).fillMaxWidth()
                    .hoverable(topChromeInteraction, enabled = desktop)
                    .then(if (desktop) Modifier.onFocusChanged { topChromeFocused = it.hasFocus }.focusGroup() else Modifier)
                    .windowInsetsPadding(dev.mangaflow.reader.readerStatusInsets().union(WindowInsets.displayCutout)
                        .union(WindowInsets.captionBar).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                // Retain Surface's input shield on blank chrome without consuming child gestures.
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).pointerInput(Unit) {}.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    ReaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回漫画详情", interactive = controlsVisible, onClick = leaveReader)
                    Column(Modifier.weight(1f).padding(start = 2.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)) {
                        Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(chapter.group.takeIf { hasMultipleGroups }, chapter.title,
                            pageRepairs[pageIndex]?.let { "补页 · " + it.variant.sourceName + " " + (it.nativeIndex + 1) },
                            currentSourceName.takeIf { chapter.variants.size > 1 }).joinToString(" · "),
                            color = colors.onSurface.copy(alpha = .86f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (preferences.panelEngine == PanelEngine.OFF &&
                        (preferences.mode == ReadingMode.RIGHT_TO_LEFT || preferences.mode == ReadingMode.LEFT_TO_RIGHT)) {
                        TextButton(enabled = controlsVisible, onClick = {
                            cursor.scrollOffset = 0
                            updatePreferences(preferences.copy(doublePage = !preferences.doublePage))
                            keyboardFocusRestore++
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (preferences.doublePage) "双页" else "单页", color = colors.onSurface, fontSize = 12.sp)
                        }
                    }
                    if (pageRepairs.containsKey(pageIndex)) {
                        ReaderIcon(Icons.Rounded.Refresh, "恢复本页原图", interactive = controlsVisible,
                            onClick = {
                                pageRepairs.remove(pageIndex)
                                currentPageRetryIndex = pageIndex
                                currentPageRetrySerial++
                            })
                        ReaderIcon(Icons.Rounded.Tune, "调整本页备用来源与对齐", interactive = controlsVisible,
                            onClick = { repairTarget = pageIndex })
                    }
                    if (desktop) ReaderIcon(Icons.AutoMirrored.Outlined.MenuBook,
                        if (showChapters) "收起章节目录" else "章节目录", interactive = controlsVisible,
                        emphasis = if (showChapters) 1f else .82f, onClick = ::toggleReaderChapters)
                    ReaderIcon(Icons.Rounded.Tune, if (desktop) "阅读设置与快捷键" else "阅读设置",
                        interactive = controlsVisible, emphasis = if (showSettings) 1f else .82f,
                        onClick = ::toggleReaderSettings)
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
            Box(Modifier.hoverable(bottomChromeInteraction, enabled = desktop)
                .then(if (desktop) Modifier.onFocusChanged { bottomChromeFocused = it.hasFocus }.focusGroup() else Modifier)
                .windowInsetsPadding(dev.mangaflow.reader.readerNavigationInsets().union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)
                .widthIn(max = 440.dp).fillMaxWidth()) {
                LiquidGlassSurface(
                    state = readerBackdrop,
                    role = GlassSurfaceRole.READING_DOCK,
                    trackPointer = true,
                    softCard = true,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.dp,
                ) {
                    Column(Modifier.fillMaxWidth().pointerInput(Unit) {}
                        .padding(horizontal = 10.dp, vertical = 4.dp)) {
                        if (pages.isNotEmpty()) key(chapterId) {
                            ReaderProgressSlider(
                                pageIndex = pageIndex, pages = pages, pendingPage = targetPage,
                                headers = headers, sourceId = chapter.sourceId,
                                enabled = controlsVisible,
                                onSeek = { destination ->
                                    if (destination != pageIndex) {
                                        returnLocation = ReaderReturnLocation(pageIndex, cursor.scrollOffset)
                                        targetOffset = 0
                                        targetPage = destination
                                    }
                                },
                                onOpenNavigator = { showPageNavigator = true },
                                onInteractionChanged = { progressInteracting = it; if (!it) keyboardFocusRestore++ },
                                sourceContent = {
                                    if (canChooseSource || sourceLoading) Box(
                                        Modifier.width(44.dp).heightIn(min = 44.dp)
                                            .onGloballyPositioned { coordinates ->
                                                val origin = coordinates.localToScreen(Offset.Zero)
                                                if (origin.isSpecified) sourceTriggerAnchor = Rect(origin,
                                                    Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (sourceLoading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.4.dp)
                                        else ReaderSourceStack(
                                            chapter = chapter,
                                            enabled = controlsVisible && active && !leavingReader,
                                            onOpen = {
                                                sourceCloseRequested = false
                                                sourceChapter = chapter
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val canCorrectSpread = preferences.panelEngine == PanelEngine.OFF && preferences.doublePage &&
                                preferences.mode in setOf(ReadingMode.RIGHT_TO_LEFT, ReadingMode.LEFT_TO_RIGHT)
                            // Keep download in the final slot; disclose only actions that need overflow.
                            val reserveSecondarySlot = maxWidth < 264.dp || canCorrectSpread ||
                                returnLocation != null || (canChooseSource && pages.isEmpty())
                            val finalSlots = if (reserveSecondarySlot) 2 else 1
                            val inlineChapterNavigation = maxWidth >= (((if (desktop) 2 else 3) + finalSlots) * 44).dp
                            val inlineCatalog = !desktop && maxWidth >= ((1 + finalSlots) * 44).dp
                            val chapterSlots = if (inlineChapterNavigation) (if (desktop) 2 else 3)
                                else if (inlineCatalog) 1 else 0
                            val extraSlots = ((maxWidth.value - (chapterSlots + finalSlots) * 44f) / 44f)
                                .toInt().coerceIn(0, 2)
                            val inlinePicture = extraSlots >= 1
                            val inlineComments = extraSlots >= 2
                            val pictureLabel = if (currentPictureCollected) "取消画面收藏"
                                else if (currentPicture?.spread == true) "收藏当前跨页" else "收藏当前画面"
                            val pictureIcon = if (currentPictureCollected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder
                            val toggleCurrentPicture: () -> Unit = {
                                currentPicture?.let { togglePicture(it.firstPage, it.lastPage) }
                            }
                            val openPrevious: () -> Unit = {
                                siblings.getOrNull(chapterIndex - 1)?.let { chapterId = it.id }
                            }
                            val commentsLabel = if (localImport) "本地章节没有在线评论" else "本章评论"
                            var savingChapter by remember(chapterId) { mutableStateOf(false) }
                            val downloaded = activeDownload?.status == DownloadStatus.COMPLETED
                            val downloading = activeDownload?.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
                            val downloadLabel = when {
                                savingChapter -> "正在准备本章下载"
                                activeDownload?.status == DownloadStatus.QUEUED -> "下载排队中，点击暂停"
                                activeDownload?.status == DownloadStatus.WAITING -> "等待恢复下载，点击暂停"
                                downloading -> activeDownload?.let { entry ->
                                    if (entry.totalPages > 0) "下载 ${entry.completedPages} / ${entry.totalPages} 页，点击暂停"
                                    else "正在获取本章页数，点击暂停"
                                } ?: "正在下载本章，点击暂停"
                                downloaded -> "本章已下载"
                                localImport -> "本地章节无需下载"
                                activeDownload?.status == DownloadStatus.PAUSED -> "下载已暂停，点击继续"
                                activeDownload?.status == DownloadStatus.FAILED -> "重试下载"
                                else -> "保存本章"
                            }
                            val secondaryActions = !inlineChapterNavigation || (!inlineCatalog && !desktop) ||
                                (!inlinePicture && currentPicture != null) ||
                                (!inlineComments && pages.isNotEmpty() && !localImport) ||
                                (canChooseSource && pages.isEmpty()) || canCorrectSpread || returnLocation != null
                            LaunchedEffect(secondaryActions) {
                                if (!secondaryActions) showReaderActions = false
                            }
                            val saveChapter: () -> Unit = {
                                if (downloading) activeDownload?.let { downloadStore.pause(it.id) }
                                else if (!savingChapter) {
                                    savingChapter = true
                                    val expectedEntry = activeDownload
                                    val chapterToSave = chapter
                                    onRequestNotificationPermission()
                                    scope.launch {
                                        try {
                                            val route = downloadStore.routeForReadingDownload(manga.id, chapterToSave.id,
                                                expectedEntry, useLatestDefaults = expectedEntry?.status != DownloadStatus.PAUSED)
                                            val prepared = dev.mangaflow.data.foregroundRequest("下载章节图片") {
                                                repository.prepareDownload(adultImageContext,
                                                    chapterSourceMangaId(manga.id, chapterToSave), chapterToSave.id,
                                                    route, forceRefresh = expectedEntry?.needsSourceRefresh == true)
                                            }
                                            val saved = if (expectedEntry != null)
                                                downloadStore.refreshReadingDownload(manga, chapterToSave, prepared.pages,
                                                    prepared.headers, expectedEntry, route = route)
                                            else { downloadStore.enqueue(manga, chapterToSave, prepared.pages,
                                                prepared.headers, route = route); true }
                                            if (saved) downloadMessage = "正在保存本章，可继续阅读"
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (failure: Exception) { downloadMessage = failure.message ?: "无法保存本章" }
                                        finally { savingChapter = false }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (inlineChapterNavigation) ReaderIcon(
                                    Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上一章",
                                    enabled = chapterIndex > 0, interactive = controlsVisible,
                                    buttonSize = 44.dp, onClick = openPrevious)
                                if (inlineCatalog) ReaderIcon(
                                    Icons.AutoMirrored.Outlined.MenuBook, "章节目录", interactive = controlsVisible,
                                    buttonSize = 44.dp, onClick = ::toggleReaderChapters)
                                if (inlineChapterNavigation) ReaderIcon(
                                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下一章",
                                    enabled = openNextChapter != null && !chapterChangeRequested,
                                    interactive = controlsVisible, buttonSize = 44.dp,
                                    onClick = { openNextChapter?.invoke() })
                                Spacer(Modifier.weight(1f))
                                if (inlinePicture) ReaderIcon(
                                    pictureIcon, pictureLabel, enabled = currentPicture != null,
                                    interactive = controlsVisible, buttonSize = 44.dp, onClick = toggleCurrentPicture)
                                if (inlineComments) ReaderIcon(
                                    Icons.Outlined.Forum, commentsLabel, enabled = pages.isNotEmpty() && !localImport,
                                    interactive = controlsVisible, buttonSize = 44.dp, onClick = { showComments = true })
                                key(chapterId) {
                                    Box {
                                        if (secondaryActions) ReaderIcon(Icons.Outlined.MoreHoriz, "其他阅读操作", interactive = controlsVisible,
                                            buttonSize = 44.dp) { showReaderActions = !showReaderActions }
                                        CompactGlassPopover(
                                            visible = secondaryActions && showReaderActions && controlsVisible && active && !leavingReader,
                                            backdrop = readerBackdrop, aboveAnchor = true,
                                            onDismiss = { showReaderActions = false },
                                            onPresenceChange = { readerActionsPresent = it },
                                        ) {
                                            if (!inlineChapterNavigation) {
                                                CompactMenuAction(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上一章",
                                                    enabled = showReaderActions && chapterIndex > 0) {
                                                    showReaderActions = false
                                                    openPrevious()
                                                }
                                            }
                                            if (!inlineCatalog && !desktop) CompactMenuAction(Icons.AutoMirrored.Outlined.MenuBook, "章节目录",
                                                enabled = showReaderActions) {
                                                showReaderActions = false
                                                toggleReaderChapters()
                                            }
                                            if (!inlineChapterNavigation) {
                                                CompactMenuAction(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下一章",
                                                    enabled = showReaderActions && openNextChapter != null && !chapterChangeRequested) {
                                                    showReaderActions = false
                                                    openNextChapter?.invoke()
                                                }
                                            }
                                            if (!inlinePicture) CompactMenuAction(pictureIcon, pictureLabel,
                                                enabled = showReaderActions && currentPicture != null) {
                                                showReaderActions = false
                                                toggleCurrentPicture()
                                            }
                                            if (!inlineComments) CompactMenuAction(Icons.Outlined.Forum, commentsLabel,
                                                enabled = showReaderActions && pages.isNotEmpty() && !localImport) {
                                                showReaderActions = false
                                                showComments = true
                                            }
                                            if (canChooseSource && pages.isEmpty()) {
                                                CompactMenuAction(Icons.Outlined.Source, "本章来源", enabled = showReaderActions) {
                                                    showReaderActions = false
                                                    sourceCloseRequested = false
                                                    sourceChapter = chapter
                                                }
                                            }
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
                                ReaderDownloadAction(
                                    chapterId = chapterId, entry = activeDownload, submitting = savingChapter,
                                    label = downloadLabel,
                                    enabled = pages.isNotEmpty() && !downloaded && !localImport && !savingChapter,
                                    interactive = controlsVisible && active && !leavingReader,
                                    onClick = saveChapter,
                                )
                            }
                        }
                    }
                }
            }
        }

        ReaderExperienceStatus(
            visible = readerForeground && !leavingReader && !panelBlocksReading && !showPanelModels &&
                !showSpreadAdjustment && !showComments && !showPageNavigator && !sourcePickerBlocking &&
                !chapterChangeRequested && !showReaderActions && !readerActionsPresent,
            sessionKey = sourceIdentity, store = experience, budget = experienceBudget,
            standbys = currentSourceStandbys, alternativeCount = alternativeSources.size, backdrop = readerBackdrop,
            preparation = preparation,
            standbyAvailability = standbyAvailability.takeIf { it.workId == manga.id && it.chapterId == chapter.id }
                ?: dev.mangaflow.data.ReaderSourceStandbyAvailability(),
            nextChapterPreparation = nextChapterPreparation?.takeIf {
                it.workId == manga.id && it.currentChapterId == chapter.id && it.chapterId == nextChapter?.id
            },
            topClearance = with(LocalDensity.current) {
                (if (controlsVisible) (topControlsHeightPx - readerStatusInsets().getTop(this))
                    .coerceAtLeast(0).toDp().coerceAtLeast(60.dp) else 0.dp) +
                    if (sourceLoading || sourceError != null) 64.dp else 0.dp
            },
            bottomClearance = maxOf(if (atChapterEnd) 72.dp else 24.dp, with(LocalDensity.current) {
                if (controlsVisible) (bottomControlsHeightPx - readerNavigationInsets().getBottom(this))
                    .coerceAtLeast(0).toDp().coerceAtLeast(112.dp) else 0.dp
            }),
            onInspectingChange = { readerStatusInspecting = it },
            onOpenSources = if (alternativeSources.isEmpty()) null else ({
                sourceTriggerAnchor = null
                sourceCloseRequested = false; sourceChapter = chapter; controlsVisible = true
            }),
            onRetryStandby = { variantId ->
                val failed = currentSourceStandbys.any { it.variantId == variantId &&
                    !it.manifestQueued && !it.manifestActive &&
                    (it.phase == dev.mangaflow.data.ReaderSourceStandbyPhase.FAILED || it.failedPages > 0) }
                if (latestReadingIdentity == (manga.id to sourceIdentity) && readerForeground &&
                    failed && chapter.variants.any { it.storageId == variantId && it.storageId != chapter.id })
                    onRetryStandby(chapter, variantId)
            },
            onUseStandby = { variantId ->
                if (latestReadingIdentity == (manga.id to sourceIdentity) && readerForeground) {
                    val prepared = currentSourceStandbys.firstOrNull { row ->
                        row.variantId == variantId && row.targetOriginalPresent && row.totalPages?.let { count ->
                            count > 0 && row.targetPageIndex == dev.mangaflow.data.approximateReaderPage(pageIndex, pages.size, count)
                        } == true
                    }
                    val variant = chapter.variants.firstOrNull { it.storageId == variantId && it.storageId != chapter.id }
                    if (prepared != null && variant != null)
                        onQuickReadVariant(chapter, variant, pageIndex, pages.size)
                }
            },
        )

        if (!controlsVisible && pages.isNotEmpty() && preferences.panelEngine == PanelEngine.OFF) Text(
            "$clock · ${pageIndex + 1} / ${pages.size}",
            color = preferences.theme.foreground().copy(alpha = .5f), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(dev.mangaflow.reader.readerNavigationInsets().union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
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
    }
    }
    ReaderPageRepairDialog(
        index = repairTarget, mainPageCount = pages.size, alternatives = alternativeSources,
        repository = repository, backdrop = readerBackdrop,
        onApply = { repaired ->
            if (pages.getOrNull(repaired.primaryIndex) != null) {
                pageRepairs[repaired.primaryIndex] = repaired
                currentPageRetryIndex = repaired.primaryIndex
                currentPageRetrySerial++
            }
            repairTarget = null
        },
        onDismiss = { repairTarget = null },
    )
    ReaderPageNavigatorDialog(
        visible = showPageNavigator,
        pages = pages,
        headers = headers,
        sourceId = chapter.sourceId,
        resolvePage = resolvePage,
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
    if (!dockedPanels) settingsPanel(false)
    PanelModelsDialog(visible = showPanelModels, backdrop = readerBackdrop) { showPanelModels = false }
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
    if (!dockedPanels) chaptersPanel(false)
    val sourcePositions = rememberChapterPositions(manga, libraryStore, active = sourcePickerBlocking)
    val sourceDownloads = if (sourcePickerBlocking) {
        val flow = remember(downloadStore, manga.id) { downloadStore.observeManga(manga.id) }
        val initial = remember(downloadStore, manga.id) {
            downloadStore.entries.value.filter { it.manga.id == manga.id }
        }
        val entries by flow.collectAsStateWithLifecycle(initialValue = initial)
        remember(entries) { entries.associateBy { it.chapter.id } }
    } else emptyMap()
    ChapterSourcePicker(
        chapter = sourceChapter, backdrop = readerBackdrop,
        repository = repository, workMangaId = manga.id,
        onDismiss = { sourceChapter = null; sourceCloseRequested = false },
        onCancelSelection = onCancelSourceSelection,
        onReadVariant = { selected, variant ->
            // Always close the orbit after a real selection; the old reader stays interactive
            // while the target source prepares off the UI thread.
            sourceCloseRequested = true
            if (variant.storageId == chapter.id) {
                onCancelSourceSelection()
                if (loading || error != null || pages.isEmpty()) {
                    chapterLoadAttempt.stopped = true
                    retry++
                } else {
                    currentPageRetryIndex = pageIndex
                    currentPageRetrySerial++
                }
            } else {
                onReadVariant(selected, variant, pageIndex, pages.size)
            }
        },
        onSetPreferredSource = onSetPreferredSource,
        onClearChapterSource = onClearChapterSource,
        chapters = chapters,
        allowCorrections = false,
        downloaded = sourceDownloads, chapterPositions = sourcePositions,
        anchor = sourceTriggerAnchor, closeRequested = sourceCloseRequested,
        loading = sourceLoading, error = sourceError,
        onPresenceChange = { sourcePickerPresent = it },
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
    sourceId: String,
    prefetchPage: suspend (Page) -> Page?,
    scriptPrefetch: suspend (Page, PrefetchByteBudget, PrefetchWork) -> Page?,
    headers: Map<String, String>,
    resolvePage: ReaderPageResolver,
    retryPage: ReaderPageResolver,
    observePageTransfer: (Page) -> Flow<DownloadProgress?>,
    sourceAlternatives: List<ChapterVariant>,
    readySourceVariants: Map<String, ReaderSourceStandby>,
    onPrefetchFailure: (Page) -> Unit,
    onRepairPage: (Int) -> Unit,
    sourceName: String,
    onShowControls: () -> Unit,
    retryTargetIndex: Int,
    retryPulse: Int,
    onQuickSwitch: (Int, ChapterVariant) -> Unit,
    onMoreSources: (Rect?) -> Unit,
    preferences: ReaderPreferences,
    manualSingles: Set<Int>,
    initialPage: Int,
    initialOffset: Int,
    targetPage: Int?,
    targetOffset: Int,
    keyboardRequest: ReaderKeyboardRequest?,
    onKeyboardConsumed: (Int) -> Unit,
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
    onPrevious: (() -> Unit)?,
    startContent: @Composable (BottomPullLoadState) -> Unit,
    endContent: @Composable (BottomPullLoadState) -> Unit,
) {
    val save by rememberUpdatedState(onSave)
    val readingNavigation by rememberUpdatedState(onReadingNavigation)
    val location by rememberUpdatedState(onLocation)
    val visibleImageChanged by rememberUpdatedState(onVisibleImageChanged)
    val nearEnd by rememberUpdatedState(onNearEnd)
    val atEndChanged by rememberUpdatedState(onAtEndChanged)
    val currentPages by rememberUpdatedState(pages)
    val loadedPages = remember(pages, headers, sourceId) { mutableStateMapOf<Int, String>() }
    val composedPages = remember(pages, headers, sourceId) { mutableStateMapOf<Int, Boolean>() }
    val prefetchRequests = remember(pages, headers, sourceId) { mutableStateMapOf<Int, ReaderPagePrefetch>() }
    val prefetchFailures = remember(pages, headers, sourceId) { mutableStateMapOf<Int, String>() }
    val pageDimensions = remember(pages, headers, sourceId) { mutableStateMapOf<String, IntSize>() }
    val startPage = remember { initialPage.coerceIn(0, pages.lastIndex) }
    val startOffset = remember { initialOffset.coerceAtLeast(0) }
    val endRevealPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val previewHeightPx = with(LocalDensity.current) { LocalMangaWindow.current.height.roundToPx() }
    var contentWidthPx by remember { mutableIntStateOf(0) }
    val keyboardMotion = LocalMotionEnabled.current
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
        val previousPull = rememberBottomPullLoadState(
            key = list,
            enabled = active && !restoring && onPrevious != null,
            loading = false,
            canScrollForward = { list.canScrollBackward || pageLayoutReady[0] != true || loadedPages[0] != pages[0].url },
            onLoadMore = { onPrevious?.invoke() },
            actionLabel = "返回上一话",
            towardStart = true,
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
                    item.index in pages.indices && loadedPages[item.index] == pages[item.index].url && item.size > 0 &&
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
            var previous: Triple<Int, Int, Int>? = null
            snapshotFlow {
                Triple(list.layoutInfo.viewportSize.width,
                    list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex), list.firstVisibleItemScrollOffset)
            }.distinctUntilChanged().collect { current ->
                val old = previous
                previous = current
                if (!restoring && old != null && old.first > 0 && current.first > 0 && old.first != current.first) {
                    // A vertical page's height scales with its viewport width. Keep the same
                    // place in the original image while rotating/resizing, without a new history format.
                    val offset = (old.third.toDouble() * current.first / old.first).roundToInt().coerceAtLeast(0)
                    list.stopScroll()
                    list.scrollToItem(old.second, offset)
                    previous = Triple(current.first, old.second, offset)
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
        LaunchedEffect(keyboardRequest?.serial, active, restoring) {
            val request = keyboardRequest ?: return@LaunchedEffect
            if (!active || restoring) return@LaunchedEffect
            try {
                readingNavigation()
                suspend fun moveTo(index: Int) {
                    if (keyboardMotion) list.animateScrollToItem(index.coerceIn(0, pages.lastIndex))
                    else list.scrollToItem(index.coerceIn(0, pages.lastIndex))
                }
                when (request.action) {
                    ReaderKeyboardAction.FIRST_PAGE -> moveTo(0)
                    ReaderKeyboardAction.LAST_PAGE -> moveTo(pages.lastIndex)
                    ReaderKeyboardAction.PREVIOUS_PAGE -> when {
                        list.firstVisibleItemScrollOffset > 0 -> moveTo(list.firstVisibleItemIndex)
                        list.firstVisibleItemIndex > 0 -> moveTo(list.firstVisibleItemIndex - 1)
                        else -> onPrevious?.invoke()
                    }
                    ReaderKeyboardAction.NEXT_PAGE -> when {
                        list.firstVisibleItemIndex < pages.lastIndex -> moveTo(list.firstVisibleItemIndex + 1)
                        list.canScrollForward -> {
                            val step = (list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset) * .85f
                            if (keyboardMotion) list.animateScrollBy(step) else list.scrollBy(step)
                        }
                        else -> onNext?.invoke()
                    }
                    ReaderKeyboardAction.SCROLL_UP, ReaderKeyboardAction.SCROLL_DOWN -> {
                        val forward = request.action == ReaderKeyboardAction.SCROLL_DOWN
                        if (if (forward) list.canScrollForward else list.canScrollBackward) {
                            val step = (list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset) * .85f * if (forward) 1 else -1
                            if (keyboardMotion) list.animateScrollBy(step) else list.scrollBy(step)
                        } else if (forward) onNext?.invoke() else onPrevious?.invoke()
                    }
                    ReaderKeyboardAction.TOGGLE_CONTROLS -> Unit
                }
            } finally { onKeyboardConsumed(request.serial) }
        }
        DisposableEffect(list) {
            onDispose {
                visibleImageChanged(false)
                if (!restoring) save(list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex),
                    if (list.firstVisibleItemIndex <= pages.lastIndex) list.firstVisibleItemScrollOffset else 0)
            }
        }
        AdjacentPagePrefetch(pages, headers, preferences.mode, composedPages, loadedPages, prefetchRequests, prefetchPage,
            scriptPrefetch = scriptPrefetch,
            enabled = active, sourceId = sourceId,
            viewportWidth = { contentWidthPx }, viewportHeight = previewHeightPx,
            onPageFailure = { index, failure -> prefetchFailures[index] = failure
                onPrefetchFailure(pages[index]) },
            onPageRecovered = { index -> prefetchFailures.remove(index) },
            onPageDimensions = { index, size -> pageDimensions[pages[index].url] = size },
            position = {
                val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
                list.firstVisibleItemIndex + list.firstVisibleItemScrollOffset.toFloat() / (item?.size ?: 1).coerceAtLeast(1)
            }) {
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.index in pages.indices }
            val anchor = list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex)
            (visible.firstOrNull()?.index ?: anchor) to (visible.lastOrNull()?.index ?: anchor)
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .bottomPullLoad(pull).bottomPullLoad(previousPull)) {
            LazyColumn(state = list, modifier = Modifier.widthIn(max = MangaLayout.ReaderPageWidth)
                .fillMaxSize().align(Alignment.TopCenter).onSizeChanged { contentWidthPx = it.width },
                verticalArrangement = Arrangement.spacedBy(if (preferences.pageGap) 6.dp else 0.dp)) {
                itemsIndexed(pages, key = { index, page -> "${page.index}:$index" }, contentType = { _, _ -> "page" }) { index, page ->
                    ReaderPage(
                        page, headers, sourceId = sourceId, resolvePage = resolvePage, retryPage = retryPage,
                        observePageTransfer = observePageTransfer, paged = false, onTap = onTap,
                        sourceAlternatives = sourceAlternatives, sourceName = sourceName,
                        readySourceVariants = readySourceVariants, prefetchFailure = prefetchFailures[index],
                        pageCount = pages.size, foreground = preferences.theme.foreground(), onShowControls = onShowControls,
                        onQuickSwitch = { variant -> onQuickSwitch(index, variant) },
                        onMoreSources = onMoreSources,
                        onRepairPage = { onRepairPage(index) },
                        externalRetrySerial = if (index == retryTargetIndex) retryPulse else 0,
                        onLoadChanged = { ready ->
                            if (currentPages.getOrNull(index)?.url == page.url) {
                                if (ready) loadedPages[index] = page.url
                                else if (loadedPages[index] == page.url) loadedPages.remove(index)
                                if (ready) prefetchFailures.remove(index)
                            }
                        },
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
                        isVisible = {
                            active && list.layoutInfo.let { layout ->
                                layout.visibleItemsInfo.any { item ->
                                    item.index == index && item.offset < layout.viewportEndOffset &&
                                        item.offset + item.size > layout.viewportStartOffset
                                }
                            }
                        },
                        onLayoutReady = { pageLayoutReady[index] = it },
                    )
                }
            }
            if (atEnd) Box(Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(dev.mangaflow.reader.readerNavigationInsets().union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) { endContent(pull) }
            if (previousPull.progress > 0f) Box(Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 16.dp, top = 64.dp)) { startContent(previousPull) }
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
        LaunchedEffect(keyboardRequest?.serial, active) {
            val request = keyboardRequest ?: return@LaunchedEffect
            if (!active) return@LaunchedEffect
            try {
                readingNavigation()
                val destination = when (request.action) {
                    ReaderKeyboardAction.FIRST_PAGE -> 0
                    ReaderKeyboardAction.LAST_PAGE -> groups.lastIndex
                    ReaderKeyboardAction.PREVIOUS_PAGE, ReaderKeyboardAction.SCROLL_UP -> pager.currentPage - 1
                    ReaderKeyboardAction.NEXT_PAGE, ReaderKeyboardAction.SCROLL_DOWN -> pager.currentPage + 1
                    ReaderKeyboardAction.TOGGLE_CONTROLS -> pager.currentPage
                }
                when {
                    destination < 0 -> onPrevious?.invoke()
                    destination > groups.lastIndex -> onNext?.invoke()
                    keyboardMotion -> pager.animateScrollToPage(destination)
                    else -> pager.scrollToPage(destination)
                }
            } finally { onKeyboardConsumed(request.serial) }
        }
        val dragging by pager.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(dragging) { if (dragging) readingNavigation() }
        val swipeReadyPages = remember { mutableStateMapOf<Int, Boolean>() }
        val atEnd by remember(pager, groups) {
            derivedStateOf {
                pager.settledPage == groups.lastIndex && pager.currentPage == groups.lastIndex &&
                    kotlin.math.abs(pager.currentPageOffsetFraction) < .01f && loadedPages[pages.lastIndex] == pages.last().url
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
                    if (loadedPages[group.first] == pages[group.first].url ||
                        (group.second != null && loadedPages[group.second] == pages[group.second].url)) group.last
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
        AdjacentPagePrefetch(pages, headers, preferences.mode, composedPages, loadedPages, prefetchRequests, prefetchPage,
            scriptPrefetch = scriptPrefetch,
            enabled = active, sourceId = sourceId, groups = groups,
            viewportWidth = { contentWidthPx }, viewportHeight = previewHeightPx,
            onPageFailure = { index, failure -> prefetchFailures[index] = failure
                onPrefetchFailure(pages[index]) },
            onPageRecovered = { index -> prefetchFailures.remove(index) },
            position = { pager.currentPage + pager.currentPageOffsetFraction }) {
            val shown = groups.getOrNull(pager.currentPage)
            (shown?.first ?: pages.lastIndex) to (shown?.last ?: pages.lastIndex)
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .bottomPullLoad(pull)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().onSizeChanged { contentWidthPx = it.width },
                reverseLayout = preferences.mode == ReadingMode.RIGHT_TO_LEFT,
                // The separate bounded warmer owns offscreen previews; the pager composes only its viewport.
                beyondViewportPageCount = 0,
                key = { index -> "image:${groups[index].first}" },
            ) { index ->
                val group = groups.getOrNull(index)
                if (group != null) {
                    val renderImage: @Composable (Int, Modifier, Alignment) -> Unit = { imageIndex, modifier, alignment ->
                        Box(modifier) {
                            ReaderPage(
                                pages[imageIndex], headers, sourceId = sourceId, resolvePage = resolvePage, retryPage = retryPage,
                                observePageTransfer = observePageTransfer, paged = true, pagedAlignment = alignment, onTap = onTap,
                                sourceAlternatives = sourceAlternatives, sourceName = sourceName,
                                readySourceVariants = readySourceVariants, prefetchFailure = prefetchFailures[imageIndex],
                                pageCount = pages.size, foreground = preferences.theme.foreground(), onShowControls = onShowControls,
                                onQuickSwitch = { variant -> onQuickSwitch(imageIndex, variant) },
                                onMoreSources = onMoreSources,
                                onRepairPage = { onRepairPage(imageIndex) },
                                externalRetrySerial = if (imageIndex == retryTargetIndex) retryPulse else 0,
                                onLoadChanged = { ready ->
                                    val url = pages[imageIndex].url
                                    if (currentPages.getOrNull(imageIndex)?.url == url) {
                                        if (ready) loadedPages[imageIndex] = url
                                        else if (loadedPages[imageIndex] == url) loadedPages.remove(imageIndex)
                                        if (ready) prefetchFailures.remove(imageIndex)
                                    }
                                },
                                onComposed = { present -> if (present) composedPages[imageIndex] = true else composedPages.remove(imageIndex) },
                                prefetch = prefetchRequests[imageIndex],
                                isVisible = { active && pager.currentPage == index },
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
                .windowInsetsPadding(dev.mangaflow.reader.readerNavigationInsets().union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 72.dp, end = 16.dp, bottom = 12.dp)) { endContent(pull) }
        }
    }
}

private data class ReaderSourceIdentity(val logicalId: String, val chapterId: String,
    val sourceId: String, val sourceMangaId: String)

private class ReaderCursor(var scrollOffset: Int = 0, var panelIndex: Int = 0)

private data class PrefetchWindow(
    val first: Int, val last: Int, val composed: Set<Int>, val inFlight: Set<Int>, val enabled: Boolean,
    val visibleReady: Boolean, val ahead: Int, val behind: Int,
    val position: Float, val bytes: Long, val parallel: Int, val decoded: Int,
    val viewport: IntSize, val checking: Set<Int>, val resolving: Set<Int>, val resolved: Set<Int>,
    val resolveParallel: Int, val decodeParallel: Int, val decodeEnabled: Boolean, val foregroundActive: Boolean,
)

private data class PrefetchedReaderPage(val page: Page, val diskCacheKey: String?)

private data class PrefetchRecoveryKey(val bytes: Long, val parallel: Int, val resolve: Int,
    val decode: Int, val writable: Boolean, val foreground: Boolean)

/** One exact image request retains its body observer through promotion into the visible page. */
internal data class ReaderPagePrefetch(val request: Disposable, val transfer: ReaderTransferObserver, val work: PrefetchWork)

private class ReaderPrefetchCacheRecovery(var recovered: Boolean = false)

private data class LoadedReaderImage(val diskCacheKey: String?, val previewSize: IntSize,
    val memoryCacheKey: coil3.memory.MemoryCache.Key? = null)

private fun IntSize.readerAspectRatio(): Float = (width.toFloat() / height).coerceAtLeast(.005f)

/** Telephoto preloads tiles inside an image, not upcoming comic pages. */
@OptIn(ExperimentalCoilApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
internal fun AdjacentPagePrefetch(
    pages: List<Page>, headers: Map<String, String>, mode: ReadingMode,
    composedPages: Map<Int, Boolean>, loadedPages: Map<Int, String>,
    requests: MutableMap<Int, ReaderPagePrefetch>, resolvePage: suspend (Page) -> Page?,
    scriptPrefetch: suspend (Page, PrefetchByteBudget, PrefetchWork) -> Page?,
    enabled: Boolean, sourceId: String = "", foregroundActive: Boolean = enabled,
    viewportWidth: () -> Int, viewportHeight: Int,
    onPageDimensions: (Int, IntSize) -> Unit = { _, _ -> },
    onPageFailure: (Int, String) -> Unit = { _, _ -> },
    onPageRecovered: (Int) -> Unit = {},
    position: (() -> Float)? = null, groups: List<PageSpread>? = null,
    visibleRange: () -> Pair<Int, Int>,
) {
    val context = LocalContext.current
    val imageLoader = SingletonImageLoader.get(imagePlatformContext(context))
    val experience = remember(context) { ExperiencePolicyStore.get(context) }
    val tracker = LocalReaderPreparationTracker.current
    val budget by experience.state.collectAsStateWithLifecycle()
    val overrides by experience.overrides.collectAsStateWithLifecycle()
    val currentBudget by rememberUpdatedState(budget)
    val currentOverrides by rememberUpdatedState(overrides)
    val visible by rememberUpdatedState(visibleRange)
    val currentPosition by rememberUpdatedState(position)
    val dimensionsKnown by rememberUpdatedState(onPageDimensions)
    val pageFailed by rememberUpdatedState(onPageFailure)
    val pageRecovered by rememberUpdatedState(onPageRecovered)
    val currentViewportWidth by rememberUpdatedState(viewportWidth)
    val currentComposed by rememberUpdatedState(composedPages)
    val currentLoaded by rememberUpdatedState(loadedPages)
    val prefetchEnabled by rememberUpdatedState(enabled)
    val readerActive by rememberUpdatedState(foregroundActive)
    LaunchedEffect(pages, headers, mode, imageLoader, resolvePage, scriptPrefetch, sourceId, groups, viewportHeight, tracker) {
        val owner = Any()
        val requestScope = this
        val resolvedAttempts = mutableSetOf<Int>()
        val resolvedPages = mutableStateMapOf<Int, Page>()
        val resolutionJobs = mutableStateMapOf<Int, Job>()
        val resolutionTokens = mutableMapOf<Int, PrefetchWork>()
        val originalFinished = mutableSetOf<Int>()
        val retryAttempts = mutableMapOf<Pair<Int, PrefetchStage>, Int>()
        val retryAfter = mutableMapOf<Pair<Int, PrefetchStage>, Long>()
        val resourceDeferred = mutableSetOf<Pair<Int, PrefetchStage>>()
        var recoveryKey: PrefetchRecoveryKey? = null
        val workTokens = mutableMapOf<Int, PrefetchWork>()
        val allTickets = mutableMapOf<Pair<Int, PrefetchStage>, Long>()
        val memoryKeys = mutableMapOf<Int, coil3.memory.MemoryCache.Key>()
        var inspectTargets = emptySet<Int>()
        var inspectVisible = emptySet<Int>()
        fun token(index: Int, stage: PrefetchStage): PrefetchWork {
            val ticket = tracker?.begin(index, stage, owner) ?: 0L
            allTickets[index to stage] = ticket
            return PrefetchWork(experience::wakeAdmissions, observe = {
                tracker?.observe(index, ticket, it)
                tracker?.transfer(index, ticket, it)
            })
        }
        fun end(index: Int, stage: PrefetchStage, phase: ReaderPreparationPhase, reason: String? = null) {
            allTickets[index to stage]?.let { tracker?.end(index, it, stage, phase, reason) }
        }
        fun failed(index: Int, stage: PrefetchStage, failure: Throwable) {
            val key = index to stage
            val sourceFailure = isReaderPrefetchFailure(failure)
            val reason = readerPrefetchErrorLabel(failure)
            end(index, stage, if (sourceFailure) ReaderPreparationPhase.FAILED else ReaderPreparationPhase.DEFERRED, reason)
            if (sourceFailure) { resourceDeferred -= key; pageFailed(index, reason) } else resourceDeferred += key
            val attempts = (retryAttempts[key] ?: 0) + 1
            retryAttempts[key] = attempts
            // Three attempts per page/stage in this live window. A real resource recovery
            // may resume local deferrals; ticking alone cannot cause endless requests.
            if (attempts < 3) retryAfter[key] = monotonicTimeMillis() + if (attempts == 1) 2_000L else 8_000L
            else retryAfter.remove(key)
        }
        val scriptRequests = mutableStateMapOf<Int, Job>()
        val checks = mutableStateMapOf<Int, Job>()
        val checkTokens = mutableMapOf<Int, PrefetchWork>()
        val retainedPages = mutableMapOf<Int, PrefetchedReaderPage>()
        val originalEpochs = mutableMapOf<Int, Int>()
        val inspected = mutableMapOf<Int, Int>()
        // An attempted cache-only check is not a claim that a preview remains resident.
        val previewChecks = mutableMapOf<Int, Pair<IntSize, Int>>()
        val deferredChecks = mutableMapOf<Int, Long>()
        val pairedPages = groups.orEmpty().filter { it.second != null }
            .flatMap { listOfNotNull(it.first, it.second) }.toSet()
        fun transfers(): Set<Int> = (requests.keys + scriptRequests.keys).toSet()
        fun pending(): Set<Int> = transfers() + resolutionJobs.keys
        fun originalReady(index: Int, page: Page, diskKey: String?) {
            retainedPages[index] = PrefetchedReaderPage(page, diskKey)
            originalEpochs[index] = (originalEpochs[index] ?: 0) + 1
            inspected.remove(index)
            previewChecks.remove(index)
            deferredChecks.remove(index)
        }
        fun stop(index: Int) {
            // Retain ownership until each cancelled child has actually settled. A fast return
            // to this page must not start a duplicate transport while the old one releases.
            tracker?.retire(index)
            resolutionJobs[index]?.cancel()
            requests[index]?.request?.dispose()
            scriptRequests[index]?.cancel()
            checks[index]?.cancel()
        }
        var previousPosition = Float.NaN
        var previousTime = monotonicTimeMillis()
        var lastMove = previousTime
        var speed = 0f
        var direction = 1
        var budgetOwner: Set<Int> = emptySet()
        var budgetLimit = -1L
        var byteWindow = PrefetchByteBudget(0L)
        val progressSampler = requestScope.launch {
            var cacheAt = 0L
            while (true) {
                listOf(PrefetchStage.RESOLVING to resolutionTokens, PrefetchStage.DOWNLOADING to workTokens,
                    PrefetchStage.CHECKING to checkTokens).forEach { (stage, tokens) ->
                    tokens.forEach { (index, work) ->
                        val snapshot = work.snapshot()
                        allTickets[index to stage]?.let { ticket ->
                            tracker?.observe(index, ticket, snapshot); tracker?.transfer(index, ticket, snapshot)
                        }
                    }
                }
                val now = monotonicTimeMillis()
                if (now - cacheAt >= 2_000L) {
                    cacheAt = now
                    for (index in inspectTargets - inspectVisible) {
                        val retained = retainedPages[index]
                        val resource = retained?.page ?: resolvedPages[index] ?: continue
                        val artifact = inspectReaderPreparedArtifacts(context, resource, headers, sourceId,
                            retained?.diskCacheKey, memoryKeys[index])
                        if (index in inspectTargets && index !in inspectVisible)
                            tracker?.artifact(index, artifact.originalPresent, artifact.originalBytes, artifact.previewResident)
                    }
                }
                tracker?.sample()
                delay(250L)
            }
        }
        try {
            snapshotFlow {
                val (first, last) = visible()
                PrefetchWindow(first, last, currentComposed.keys.toSet(), transfers(), prefetchEnabled,
                    (first..last).all { it !in pages.indices || currentLoaded[it] == pages[it].url },
                    currentBudget.prefetchAhead, currentBudget.prefetchBehind,
                    ((currentPosition?.invoke() ?: first.toFloat()) * 10f).roundToInt() / 10f,
                    currentBudget.adjacentPrefetchBytes, currentBudget.prefetchRequests,
                    currentBudget.decodedPrefetchPages, IntSize(currentViewportWidth(), viewportHeight),
                    checks.keys.toSet(), resolutionJobs.keys.toSet(), resolvedPages.keys.toSet(),
                    currentBudget.resolveRequests, currentBudget.decodeRequests, experience.canDecodePrefetch(), readerActive)
            }.distinctUntilChanged().combine(snapshotFlow {
                prefetchEnabled && (currentBudget.prefetchBytes > 0L ||
                    currentBudget.decodedPrefetchPages > 0 && experience.canDecodePrefetch())
            }.distinctUntilChanged().flatMapLatest { running -> flow {
                emit(monotonicTimeMillis())
                if (running) while (true) { delay(2_000L); emit(monotonicTimeMillis()) }
            } }) { window, _ -> window }.collectLatest { window ->
                val now = monotonicTimeMillis()
                val visiblePages = (window.first..window.last).filter { it in pages.indices }.toSet()
                // Composition may include many future pages. Only actual visibility promotes work.
                val foreground = if (window.foregroundActive) visiblePages else emptySet()
                foreground.forEach {
                    workTokens[it]?.promote()
                    resolutionTokens[it]?.promote()
                    checkTokens[it]?.promote()
                }
                val promoted = if (window.foregroundActive) (workTokens.filterValues { it.isForeground }.keys +
                    resolutionTokens.filterValues { it.isForeground }.keys +
                    checkTokens.filterValues { it.isForeground }.keys).toSet() else emptySet()
                if (previousPosition.isFinite() && window.position != previousPosition) {
                    val delta = window.position - previousPosition
                    val nextDirection = if (delta > 0f) 1 else -1
                    if (nextDirection != direction) {
                        (pending() + checks.keys).filter { it !in foreground && it !in promoted }.forEach(::stop)
                        speed = 0f
                    }
                    direction = nextDirection
                    val sample = kotlin.math.abs(delta) * 1000f / (now - previousTime).coerceAtLeast(100L)
                    speed = if (speed == 0f) sample else speed * .65f + sample * .35f
                    lastMove = now
                }
                if (window.position != previousPosition) previousTime = now
                previousPosition = window.position
                val seconds = when (currentBudget.mode) { ExperienceMode.ECO -> 2; ExperienceMode.AUTO -> 5; ExperienceMode.PERFORMANCE -> 8 }
                val readyLead = maxOf(1, window.decoded, window.parallel)
                val ahead = if (currentOverrides.prefetchMode == PrefetchMode.MANUAL) window.ahead else
                    minOf(window.ahead, if (now - lastMove > 8_000L) readyLead else
                        if (speed == 0f) readyLead * 2 else
                            maxOf(readyLead, kotlin.math.ceil(speed * seconds).toInt()))
                fun candidates(count: Int, forward: Boolean): List<Int> {
                    if (count <= 0) return emptyList()
                    val step = if (forward) direction else -direction
                    val edge = if (step > 0) window.last else window.first
                    if (groups == null) {
                        val available = if (step > 0) pages.lastIndex - edge else edge
                        return (1..minOf(count, available.coerceAtLeast(0))).map { edge + step * it }
                    }
                    val owner = groups.indexOfFirst { it.contains(edge) }
                    if (owner < 0) return emptyList()
                    val selected = mutableListOf<Int>()
                    for (offset in 1..minOf(count, groups.size)) {
                        val spread = groups.getOrNull(owner + step * offset) ?: break
                        val unit = listOfNotNull(spread.first, spread.second)
                        if (selected.size + unit.size > count) break
                        selected += unit
                    }
                    return selected
                }
                fun eligible(index: Int) = window.enabled && window.visibleReady && index in pages.indices &&
                    index !in visiblePages &&
                    (pages[index].url.startsWith("file:") || pages[index].url.startsWith("https:") ||
                        pages[index].url.startsWith("http:") ||
                        sourceId.startsWith("js_") && pages[index].sourceImageToken.isNotBlank())
                val aheadTargets = candidates(ahead, true).filter { it in pages.indices }
                val behindTargets = candidates(window.behind, false).filter { it in pages.indices }
                val networkDesired = if (window.bytes > 0L) (aheadTargets + behindTargets).filter(::eligible).toSet() else emptySet()
                // Local/Coil originals can decode while offline or while HTTP prefetch is disabled.
                val memoryWindow = if (window.decodeEnabled && window.decoded > 0)
                    candidates(window.decoded, true).filter(::eligible).toSet() else emptySet()
                val desired = networkDesired + memoryWindow
                val writable = experience.canWritePrefetch()
                val recovery = PrefetchRecoveryKey(window.bytes, window.parallel, window.resolveParallel,
                    window.decodeParallel, writable, window.foregroundActive)
                if (recoveryKey != null && recoveryKey != recovery) resourceDeferred.forEach { key ->
                    retryAttempts.remove(key)
                    retryAfter[key] = now
                }
                recoveryKey = recovery
                inspectTargets = desired + visiblePages
                inspectVisible = visiblePages
                tracker?.window(owner, window.first, window.last, direction, aheadTargets, behindTargets,
                    networkDesired, memoryWindow, window.foregroundActive,
                    when { !window.enabled -> "阅读暂缓"; !window.visibleReady -> "当前页优先"
                        window.bytes <= 0L -> currentBudget.reason
                        !writable -> "原图缓存暂缓写入，容量恢复后继续"; else -> null })
                val retained = desired + foreground + promoted
                (pending() + checks.keys).filter { it !in retained }.forEach(::stop)
                resolvedAttempts.retainAll(retained)
                resolvedPages.keys.retainAll(retained)
                originalFinished.retainAll(retained)
                retainedPages.keys.retainAll(retained)
                originalEpochs.keys.retainAll(retained)
                inspected.keys.retainAll(retained)
                previewChecks.keys.retainAll(retained)
                deferredChecks.keys.retainAll(retained)
                retryAttempts.keys.removeAll { it.first !in retained }
                retryAfter.keys.removeAll { it.first !in retained }
                resourceDeferred.removeAll { it.first !in retained }
                for ((key, readyAt) in retryAfter.toMap()) {
                    val (index, stage) = key
                    if (now < readyAt || index !in desired || !window.visibleReady ||
                        !window.foregroundActive || stage == PrefetchStage.DOWNLOADING && !writable || index in pending()) continue
                    when (stage) {
                        PrefetchStage.RESOLVING -> resolvedAttempts.remove(index)
                        PrefetchStage.DOWNLOADING -> originalFinished.remove(index)
                        else -> Unit
                    }
                    retryAfter.remove(key)
                }
                if (window.bytes < budgetLimit) {
                    transfers().filter { it !in foreground && it !in promoted }.forEach(::stop)
                }
                if ((networkDesired != budgetOwner || window.bytes != budgetLimit) &&
                    transfers().none { it !in foreground && it !in promoted }) {
                    budgetOwner = networkDesired
                    budgetLimit = window.bytes
                    byteWindow = PrefetchByteBudget(window.bytes)
                    for (index in networkDesired.filter { it in originalFinished && it in retainedPages }) {
                        try { byteWindow.accept(experience.estimatedPageBytes(pages[index].url)) }
                        catch (_: PrefetchBudgetExceeded) { byteWindow.exhaust(); break }
                    }
                }
                // Resolution owns a small independent queue and never suspends this window collector.
                for (index in desired) {
                    if (index in resolvedAttempts || index in resolutionJobs) continue
                    if (resolutionJobs.size >= window.resolveParallel) break
                    val token = token(index, PrefetchStage.RESOLVING)
                    val job = requestScope.launch(start = CoroutineStart.LAZY) {
                        val owned = currentCoroutineContext()[Job]
                        try {
                            val resource = token.during(PrefetchStage.RESOLVING) {
                                experience.withResolveAdmission({ token.isForeground },
                                    { token.admission(PrefetchStage.RESOLVING, it) }) { resolvePage(pages[index]) }
                            }
                            currentCoroutineContext().ensureActive()
                            if (resolutionJobs[index] !== owned) return@launch
                            if (resource != null) {
                                resolvedPages[index] = resource
                                if (resource.url.startsWith("file:")) {
                                    originalReady(index, resource, readerPageCacheKey(resource, headers, sourceId))
                                    originalFinished += index
                                }
                            }
                            end(index, PrefetchStage.RESOLVING, if (resource != null) ReaderPreparationPhase.COMPLETE
                                else ReaderPreparationPhase.DEFERRED, if (resource == null) "已有下载负责本页" else null)
                            val key = index to PrefetchStage.RESOLVING
                            if (resource == null) {
                                // This is only DownloadStore's local ownership lookup. Revisit it
                                // after the real download settles, without starting a duplicate.
                                retryAfter[key] = monotonicTimeMillis() + 2_000L
                            } else {
                                retryAttempts.remove(key); retryAfter.remove(key); resourceDeferred.remove(key)
                            }
                            resolvedAttempts += index
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (pressure: OutOfMemoryError) {
                            if (resolutionJobs[index] === owned) {
                                failed(index, PrefetchStage.RESOLVING, pressure)
                                resolvedAttempts += index
                            }
                        }
                        catch (failure: Exception) {
                            if (resolutionJobs[index] === owned) {
                                failed(index, PrefetchStage.RESOLVING, failure)
                                resolvedAttempts += index
                            }
                        } finally {
                            if (resolutionJobs[index] === owned) { resolutionJobs.remove(index); resolutionTokens.remove(index)
                                allTickets[index to PrefetchStage.RESOLVING]?.let { tracker?.finish(it) } }
                        }
                    }
                    resolutionJobs[index] = job
                    resolutionTokens[index] = token
                    job.start()
                }
                // Original transfers have their own capacity/byte slice; resolved local pages skip it.
                for (index in networkDesired) {
                    if (!writable) break
                    val resource = resolvedPages[index] ?: continue
                    if (index in transfers() || index in originalFinished) continue
                    if (transfers().size >= window.parallel) break
                    if (experience.estimatedPageBytes(resource.url) > byteWindow.remaining) break
                    val windowBytes = byteWindow
                    val token = token(index, PrefetchStage.DOWNLOADING)
                    if (sourceId.startsWith("js_")) {
                        val job = requestScope.launch(start = CoroutineStart.LAZY) {
                            val owned = currentCoroutineContext()[Job]
                            try {
                                val local = scriptPrefetch(pages[index], windowBytes, token)
                                currentCoroutineContext().ensureActive()
                                if (scriptRequests[index] !== owned) return@launch
                                if (local != null) originalReady(index, local, readerPageCacheKey(local, headers, sourceId))
                                end(index, PrefetchStage.DOWNLOADING, if (local != null) ReaderPreparationPhase.COMPLETE
                                    else ReaderPreparationPhase.DEFERRED, if (local == null) "原图尚未准备" else null)
                                originalFinished += index
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (pressure: OutOfMemoryError) {
                                if (scriptRequests[index] === owned) {
                                    failed(index, PrefetchStage.DOWNLOADING, pressure)
                                    originalFinished += index
                                }
                            }
                            catch (_: PrefetchBudgetExceeded) {
                                if (scriptRequests[index] === owned) {
                                    failed(index, PrefetchStage.DOWNLOADING, PrefetchBudgetExceeded())
                                    originalFinished += index
                                }
                            } catch (failure: Exception) {
                                if (scriptRequests[index] === owned) {
                                    failed(index, PrefetchStage.DOWNLOADING, failure)
                                    originalFinished += index
                                }
                            } finally {
                                if (scriptRequests[index] === owned) { scriptRequests.remove(index); workTokens.remove(index)
                                    allTickets[index to PrefetchStage.DOWNLOADING]?.let { tracker?.finish(it) } }
                            }
                        }
                        scriptRequests[index] = job
                        workTokens[index] = token
                        job.start()
                        continue
                    }
                    if (sourceId == "jmcomic" || !resource.url.startsWith("https:")) {
                        // The native JM decryptor remains foreground-owned.
                        end(index, PrefetchStage.DOWNLOADING, ReaderPreparationPhase.DEFERRED, "此来源在实际阅读时恢复原图")
                        allTickets[index to PrefetchStage.DOWNLOADING]?.let { tracker?.finish(it) }
                        originalFinished += index
                        continue
                    }
                    val transfer = ReaderTransferObserver()
                    val request = imageLoader.enqueue(readerImageRequest(context, resource, headers, sourceId)
                        .readerTransferProgress(transfer)
                        .prefetchPriority(windowBytes, sourceId.ifBlank { experienceOrigin(resource.url) }, token)
                        .size(1).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.ENABLED)
                        .decoderFactory(BlackholeDecoder.Factory()).build())
                    val ownedRequest = ReaderPagePrefetch(request, transfer, token)
                    requests[index] = ownedRequest
                    workTokens[index] = token
                    requestScope.launch {
                        try {
                            val result = request.job.await()
                            currentCoroutineContext().ensureActive()
                            if (requests[index] !== ownedRequest) return@launch
                            if (result is SuccessResult) {
                                originalReady(index, resource, result.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId))
                                if (result.dataSource != coil3.decode.DataSource.NETWORK) token.ifOptional {
                                    try { windowBytes.accept(experience.estimatedPageBytes(resource.url)) }
                                    catch (_: PrefetchBudgetExceeded) { windowBytes.exhaust() }
                                }
                                val key = index to PrefetchStage.DOWNLOADING
                                retryAttempts.remove(key); retryAfter.remove(key); resourceDeferred.remove(key)
                                end(index, PrefetchStage.DOWNLOADING, ReaderPreparationPhase.COMPLETE)
                            } else if (result is ErrorResult) {
                                failed(index, PrefetchStage.DOWNLOADING, result.throwable)
                            }
                            originalFinished += index
                        } catch (cancelled: CancellationException) {
                            currentCoroutineContext().ensureActive()
                        } finally {
                            if (requests[index] === ownedRequest) { requests.remove(index); workTokens.remove(index)
                                allTickets[index to PrefetchStage.DOWNLOADING]?.let { tracker?.finish(it) } }
                        }
                    }
                }
                // Cache-only inspection can run before a transfer, and repeats after real originals arrive.
                for (index in desired) {
                    val retainedPage = retainedPages[index]
                    val resource = retainedPage?.page ?: resolvedPages[index] ?: continue
                    val epoch = originalEpochs[index] ?: 0
                    val size = readerPreviewSize(
                        if (index in pairedPages) window.viewport.width / 2 else window.viewport.width,
                        window.viewport.height)
                    val warmMemory = index in memoryWindow && window.viewport.width > 0
                    if (index in checks || (!warmMemory && inspected[index] == epoch) ||
                        (warmMemory && previewChecks[index] == (size to epoch)) ||
                        now < (deferredChecks[index] ?: 0L)) continue
                    if (checks.size >= window.decodeParallel) break
                    val token = token(index, PrefetchStage.CHECKING)
                    val check = requestScope.launch(start = CoroutineStart.LAZY) {
                        val owned = currentCoroutineContext()[Job]
                        try {
                            val result = preflightReaderPage(context, resource, headers, sourceId,
                                size.width, size.height, retainedPage?.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId),
                                warmMemory, token)
                            currentCoroutineContext().ensureActive()
                            if (checks[index] !== owned || (originalEpochs[index] ?: 0) != epoch) return@launch
                            result.dimensions?.let {
                                ReaderDimensionCache.put(readerPageCacheKey(pages[index], headers, sourceId), it)
                                dimensionsKnown(index, it)
                            }
                            result.memoryCacheKey?.let { memoryKeys[index] = it }
                            if (result.status == ReaderPagePreflightStatus.READY) pageRecovered(index)
                            end(index, PrefetchStage.CHECKING, when (result.status) {
                                ReaderPagePreflightStatus.READY -> ReaderPreparationPhase.COMPLETE
                                ReaderPagePreflightStatus.UNVERIFIED -> if (warmMemory) ReaderPreparationPhase.DEFERRED else ReaderPreparationPhase.SKIPPED
                                ReaderPagePreflightStatus.DEFERRED -> ReaderPreparationPhase.DEFERRED
                                ReaderPagePreflightStatus.FAILED -> ReaderPreparationPhase.FAILED
                            }, if (result.status == ReaderPagePreflightStatus.DEFERRED) "等待可用解码资源" else null)
                            when (result.status) {
                                ReaderPagePreflightStatus.READY, ReaderPagePreflightStatus.UNVERIFIED,
                                ReaderPagePreflightStatus.FAILED -> {
                                    inspected[index] = epoch
                                    if (warmMemory) previewChecks[index] = size to epoch
                                    if (result.status == ReaderPagePreflightStatus.FAILED)
                                        result.failure?.takeIf(::isReaderPrefetchFailure)?.let {
                                            pageFailed(index, readerPrefetchErrorLabel(it))
                                        }
                                }
                                ReaderPagePreflightStatus.DEFERRED -> deferredChecks[index] = monotonicTimeMillis() + 2_000L
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        finally { if (checks[index] === owned) { checks.remove(index); checkTokens.remove(index)
                            allTickets[index to PrefetchStage.CHECKING]?.let { tracker?.finish(it) } } }
                    }
                    checks[index] = check
                    checkTokens[index] = token
                    check.start()
                }
            }
        } finally {
            progressSampler.cancel()
            tracker?.close(owner)
            val pendingResolutions = resolutionJobs.values.toList()
            resolutionJobs.clear()
            pendingResolutions.forEach { it.cancel() }
            val pendingChecks = checks.values.toList()
            checks.clear()
            pendingChecks.forEach { it.cancel() }
            val scriptPending = scriptRequests.values.toList()
            scriptRequests.clear()
            scriptPending.forEach { it.cancel() }
            val pendingRequests = requests.values.toList()
            requests.clear()
            pendingRequests.forEach { it.request.dispose() }
        }
    }
}

private fun readerImageRequest(context: dev.mangaflow.platform.PlatformContext, page: Page, headers: Map<String, String>, sourceId: String = ""): ImageRequest.Builder =
    readerPageRequest(context, page, headers, sourceId)

@Composable
private fun ReaderPage(
    page: Page,
    headers: Map<String, String>,
    sourceId: String = "",
    resolvePage: ReaderPageResolver,
    retryPage: ReaderPageResolver,
    observePageTransfer: (Page) -> Flow<DownloadProgress?>,
    paged: Boolean,
    pagedAlignment: Alignment = Alignment.Center,
    onTap: () -> Unit,
    onShowControls: () -> Unit,
    sourceName: String,
    pageCount: Int,
    foreground: Color,
    sourceAlternatives: List<ChapterVariant> = emptyList(),
    readySourceVariants: Map<String, ReaderSourceStandby> = emptyMap(),
    prefetchFailure: String? = null,
    onQuickSwitch: (ChapterVariant) -> Unit = {},
    onMoreSources: (Rect?) -> Unit = {},
    onRepairPage: () -> Unit = {},
    externalRetrySerial: Int = 0,
    onLoadChanged: (Boolean) -> Unit,
    onHeightMeasured: (Int) -> Unit = {},
    onComposed: (Boolean) -> Unit = {},
    prefetch: ReaderPagePrefetch? = null,
    knownDimensions: IntSize? = null,
    onDimensionsKnown: (IntSize) -> Unit = {},
    deferResize: () -> Boolean = { false },
    visibleWindow: () -> VerticalPageWindow? = { null },
    isVisible: () -> Boolean,
    onLayoutReady: (Boolean) -> Unit = {},
    onChapterSwipeReadyChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val preparationTracker = LocalReaderPreparationTracker.current
    val experience = remember(context) { ExperiencePolicyStore.get(context) }
    val density = LocalDensity.current
    val screenHeight = LocalMangaWindow.current.height.coerceAtLeast(1.dp)
    // Request callbacks retain their own page/URL owner even if a later manifest replaces this slot.
    val loadChanged = onLoadChanged
    val resolve by rememberUpdatedState(resolvePage)
    val retrySource by rememberUpdatedState(retryPage)
    val composed by rememberUpdatedState(onComposed)
    val heightMeasured by rememberUpdatedState(onHeightMeasured)
    val dimensionsMeasured by rememberUpdatedState(onDimensionsKnown)
    val resizeDeferred by rememberUpdatedState(deferResize)
    val layoutReady by rememberUpdatedState(onLayoutReady)
    val chapterSwipeReadyChanged by rememberUpdatedState(onChapterSwipeReadyChanged)
    val visible by rememberUpdatedState(isVisible)
    val pageVisible by remember { derivedStateOf { visible() } }
    // Lazy layout recycling must not collapse a measured page back to its placeholder height.
    val originalCacheIdentity = remember(page, headers, sourceId) { readerPageCacheKey(page, headers, sourceId) }
    val cachedDimensions = remember(originalCacheIdentity) { ReaderDimensionCache.get(originalCacheIdentity) }
    var aspect by rememberSaveable(originalCacheIdentity) { mutableFloatStateOf(
        (knownDimensions ?: cachedDimensions)?.readerAspectRatio()
            ?: if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height else 0.7f) }
    var hasDimensions by rememberSaveable(originalCacheIdentity) {
        mutableStateOf(knownDimensions != null || cachedDimensions != null || (page.width > 0 && page.height > 0))
    }
    var observedDimensions by remember(originalCacheIdentity) { mutableStateOf<IntSize?>(null) }
    var measuredHeight by remember { mutableIntStateOf(0) }
    var reload by remember(originalCacheIdentity) { mutableIntStateOf(0) }
    var fileRecoveryAttempted by remember(originalCacheIdentity) { mutableStateOf(false) }
    var failedMaterializedUrl by remember(originalCacheIdentity) { mutableStateOf<String?>(null) }
    // Keep the failed-prefetch fact after its caption clears. A late discovery creates one
    // fresh attempt, while a successful foreground image never gets restarted by old evidence.
    var bypassPrefetchCache by remember(originalCacheIdentity) { mutableStateOf(prefetchFailure != null) }
    val prefetchCacheRecovery = remember(originalCacheIdentity) { ReaderPrefetchCacheRecovery() }
    val attempt = remember(page, headers, sourceId, reload, bypassPrefetchCache) { ReaderLoadAttempt() }
    val transferObserver = remember(attempt) { ReaderTransferObserver() }
    var transportProgress by remember(attempt) { mutableStateOf(transferObserver.snapshot()) }
    val latestPrefetch by rememberUpdatedState(prefetch)
    var pendingPrefetch by remember(attempt) { mutableStateOf<ReaderPagePrefetch?>(null) }
    var handoffCaptured by remember(attempt) { mutableStateOf(false) }
    val attemptPrefetch = pendingPrefetch.takeIf { reload == 0 }
    var waitingForPrefetch by remember(attempt) { mutableStateOf(false) }
    var preparationTicket by remember(attempt, preparationTracker) { mutableStateOf<Long?>(null) }
    val preparationWork = remember(attempt, preparationTracker) {
        PrefetchWork(experience::wakeAdmissions, observe = { snapshot ->
            preparationTicket?.let {
                preparationTracker?.observe(page.index, it, snapshot)
                preparationTracker?.transfer(page.index, it, snapshot)
            }
        })
    }
    DisposableEffect(attempt, preparationTracker) {
        onDispose { preparationTicket?.let { preparationTracker?.finish(it) } }
    }
    LaunchedEffect(attempt, pageVisible) {
        if (pageVisible && attempt.acceptsResult) {
            preparationTicket = preparationTracker?.begin(page.index, PrefetchStage.QUEUED)
            preparationWork.promote()
            if (!handoffCaptured) {
                // A composed future page may acquire a prefetch after its first composition.
                // Freeze the exact request at first visibility, including explicit retry settlement.
                pendingPrefetch = latestPrefetch
                waitingForPrefetch = pendingPrefetch != null
                if (reload == 0) pendingPrefetch?.work?.promote()
                handoffCaptured = true
            }
        } else {
            preparationTicket?.let { preparationTracker?.finish(it) }
            preparationTicket = null
        }
    }
    var handedOffPrefetchProgress by remember(attempt) { mutableStateOf<ReaderTransferSnapshot?>(null) }
    var error by remember(attempt) { mutableStateOf<String?>(null) }
    var loading by remember(attempt) { mutableStateOf(true) }
    var slowLoading by remember(attempt) { mutableStateOf(false) }
    var waitClock by remember(attempt) { mutableStateOf<TimeMark?>(null) }
    var resolvedPage by remember(attempt) { mutableStateOf<Page?>(null) }
    val pageTransfer by rememberReaderPageTransfer(attempt, page, observePageTransfer,
        enabled = pageVisible && loading && error == null && resolvedPage == null)
    LaunchedEffect(attempt, pageVisible, handoffCaptured) {
        if (!pageVisible || !handoffCaptured) return@LaunchedEffect
        while (true) {
            val value = if (waitingForPrefetch) attemptPrefetch?.work?.snapshot() else preparationWork.snapshot()
            val ticket = preparationTicket
            if (value != null && ticket != null) {
                preparationTracker?.observe(page.index, ticket, value)
                preparationTracker?.transfer(page.index, ticket, value)
            }
            if (ticket != null && !waitingForPrefetch) pageTransfer?.let { preparationTracker?.download(page.index, ticket, it) }
            preparationTracker?.handoff(page.index, waitingForPrefetch)
            preparationTracker?.sample()
            delay(250L)
        }
    }
    DisposableEffect(attempt) { onDispose { attempt.active = false } }
    LaunchedEffect(prefetchFailure, attempt) {
        if (prefetchFailure != null && !bypassPrefetchCache && (loading || error != null)) {
            attempt.stopped = true
            bypassPrefetchCache = true
        }
    }
    LaunchedEffect(attempt, pageVisible, loading, error) {
        slowLoading = false
        // Composition can prepare an offscreen page; only a visible foreground wait owns this deadline.
        if (pageVisible && loading && error == null && attempt.acceptsResult) {
            val visibleSince = TimeSource.Monotonic.markNow()
            val visibleAt = monotonicTimeMillis()
            val initialPrefetch = attemptPrefetch?.transfer?.snapshot()
            var observedPrefetchBytes = initialPrefetch?.receivedBytes ?: 0L
            var observedPrefetchAt = initialPrefetch?.lastProgressAt
            var inheritedProgressAt: Long? = null
            waitClock = visibleSince
            while (pageVisible && loading && error == null && attempt.acceptsResult) {
                val transport = transferObserver.snapshot()
                val inherited = if (waitingForPrefetch) attemptPrefetch?.transfer?.snapshot() else handedOffPrefetchProgress
                // Headers and bytes received before this page became visible cannot renew its
                // clock. Only this exact promoted request's later positive body writes count.
                if (inherited != null) {
                    val latest = inherited.lastProgressAt
                    if (latest != null && latest >= visibleAt &&
                        (inherited.receivedBytes > observedPrefetchBytes || latest != observedPrefetchAt))
                        inheritedProgressAt = maxOf(inheritedProgressAt ?: latest, latest)
                    observedPrefetchBytes = inherited.receivedBytes
                    observedPrefetchAt = inherited.lastProgressAt
                }
                transportProgress = if (transport.receivedBytes > 0L || inherited == null) transport else inherited
                val latestByte = listOfNotNull(transport.lastProgressAt, inheritedProgressAt).maxOrNull()
                val idle = attempt.idleMillis(visibleSince, latestByte)
                slowLoading = idle >= READER_SLOW_LOAD_MILLIS
                if (idle >= READER_PAGE_WAIT_MILLIS) {
                    attempt.stopped = true
                    error = "本页连接超时"
                    preparationTicket?.let { preparationTracker?.end(page.index, it, preparationWork.snapshot().stage,
                        ReaderPreparationPhase.FAILED, "本页连接超时") }
                    loading = false
                    loadChanged(false)
                    break
                }
                delay(minOf(1_000L, READER_PAGE_WAIT_MILLIS - idle))
            }
        }
    }
    var previousRetrySerial by remember(originalCacheIdentity) { mutableIntStateOf(externalRetrySerial) }
    LaunchedEffect(externalRetrySerial) {
        if (externalRetrySerial > previousRetrySerial) reload++
        previousRetrySerial = externalRetrySerial
    }
    var detailStatus by remember(originalCacheIdentity, reload) { mutableStateOf<ReaderDetailStatus?>(null) }
    var detailRetry by remember(originalCacheIdentity, reload) { mutableIntStateOf(0) }
    var loadedImage by remember(originalCacheIdentity, reload) { mutableStateOf<LoadedReaderImage?>(null) }
    LaunchedEffect(attempt, attempt.stopped, pageVisible, handoffCaptured) {
        if (!attempt.acceptsResult || !pageVisible || !handoffCaptured || resolvedPage != null) return@LaunchedEffect
        loadChanged(false)
        loading = true
        error = null
        resolvedPage = null
        try {
            failedMaterializedUrl?.let { discardFailedScriptImage(context, it) }
            failedMaterializedUrl = null
            val receiveBytes: (Long) -> Unit = { count -> if (attempt.acceptsResult) transferObserver.onBytes(count) }
            val resource = withContext(ReaderPreparationWorkContext(preparationWork)) {
                if (reload > 0) retrySource(page, receiveBytes) else resolve(page, receiveBytes)
            }
            preparationTicket?.let { preparationTracker?.end(page.index, it, PrefetchStage.RESOLVING, ReaderPreparationPhase.COMPLETE) }
            currentCoroutineContext().ensureActive()
            if (!attempt.acceptsResult) return@LaunchedEffect
            // Start the image immediately; original-size IO can finish alongside its decode.
            resolvedPage = resource
            readerImageDimensions(SingletonImageLoader.get(imagePlatformContext(context)), resource,
                diskCacheKey = readerPageCacheKey(resource, headers, sourceId),
                originalPage = page.takeIf { reload == 0 || resource.url == page.url },
                originalCacheIdentity = originalCacheIdentity)?.let {
                currentCoroutineContext().ensureActive()
                if (attempt.acceptsResult) {
                    observedDimensions = it
                    dimensionsMeasured(it)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // A failed persistent fetch must not immediately repeat the same HTTP request in Coil.
            if (attempt.acceptsResult) {
                loading = false
                error = failure.message ?: "图片暂时无法读取"
                preparationTicket?.let { preparationTracker?.end(page.index, it, preparationWork.snapshot().stage,
                    ReaderPreparationPhase.FAILED, readerPageErrorLabel(error.orEmpty())) }
            }
        }
    }
    // A decoded preview may guide layout if header parsing is unavailable. It is never
    // published as an original-file measurement or passed to the original tile decoder.
    val layoutDimensions = knownDimensions ?: observedDimensions ?: cachedDimensions ?: loadedImage?.previewSize
    LaunchedEffect(layoutDimensions) {
        val size = layoutDimensions ?: return@LaunchedEffect
        val measuredAspect = size.readerAspectRatio()
        if (!paged && measuredAspect != aspect) {
            // Do not move the scroll anchor when original dimensions arrive during a drag/fling.
            snapshotFlow { resizeDeferred() }.first { deferred -> !deferred }
        }
        aspect = measuredAspect
        hasDimensions = true
    }
    val delayedLoading = rememberDelayedBusy(loading)
    LaunchedEffect(attempt, pendingPrefetch, attempt.stopped, handoffCaptured) {
        if (!attempt.acceptsResult || !handoffCaptured) return@LaunchedEffect
        val oldPrefetch = pendingPrefetch
        if (oldPrefetch != null) {
            // An explicit retry owns a fresh request after this exact old transfer releases.
            if (reload > 0) oldPrefetch.request.dispose()
            try {
                // This suspends only this page's handoff; other prefetch jobs keep their own lanes.
                oldPrefetch.request.job.await()
                currentCoroutineContext().ensureActive()
                if (!attempt.acceptsResult) return@LaunchedEffect
                // Optional failure only releases the handoff. The foreground image request reports its own result.
            } catch (cancelled: CancellationException) {
                // A superseded background prefetch may be cancelled while this page is still active.
                currentCoroutineContext().ensureActive()
            }
        }
        handedOffPrefetchProgress = attemptPrefetch?.transfer?.snapshot()
        waitingForPrefetch = false
    }
    DisposableEffect(originalCacheIdentity) {
        val releaseLoad = onLoadChanged
        composed(true)
        onDispose { composed(false); releaseLoad(false) }
    }
    BoxWithConstraints(if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        val viewportWidthPx = with(density) { maxWidth.roundToPx() }
        val previewSize = readerPreviewSize(viewportWidthPx, with(density) { screenHeight.roundToPx() })
        val previewWidth = previewSize.width
        val previewHeight = previewSize.height
        val resource = resolvedPage
        val request = remember(resource, headers, attempt, previewWidth, previewHeight) {
            resource?.let { requested -> readerImageRequest(context, requested, headers, sourceId)
                .readerTransferProgress(transferObserver)
                .readerPreparationWork(preparationWork)
                .apply {
                    // Retry a suspect cached original until a foreground network decode succeeds.
                    // Never evict a key that another visible request may already have replaced.
                    if (bypassPrefetchCache && !prefetchCacheRecovery.recovered && requested.url.startsWith("https:")) {
                        diskCachePolicy(CachePolicy.WRITE_ONLY)
                        memoryCachePolicy(CachePolicy.DISABLED)
                    }
                }
                // The preview need only fit this viewport. Telephoto keeps the original
                // disk file for full-quality visible tiles and pinch zoom.
                .size(previewWidth, previewHeight)
                .scale(Scale.FIT)
                .crossfade(false)
                .listener(
                    onStart = {
                        if (attempt.acceptsResult) { loading = true; error = null; loadChanged(false) }
                    },
                    onSuccess = { _, result -> if (attempt.acceptsResult) {
                        val width = result.image.width
                        val height = result.image.height
                        if (width > 0 && height > 0) {
                            loadedImage = LoadedReaderImage(result.diskCacheKey, IntSize(width, height), result.memoryCacheKey)
                        }
                        loading = false
                        if (width > 0 && height > 0 && bypassPrefetchCache && requested.url.startsWith("https:") &&
                            result.dataSource == coil3.decode.DataSource.NETWORK) prefetchCacheRecovery.recovered = true
                        loadChanged(true)
                    } },
                    onError = { _, result -> if (attempt.acceptsResult) {
                        loadChanged(false)
                        if (requested.url.startsWith("file:") &&
                            (page.url.startsWith("https:") || page.sourceImageToken.isNotEmpty()) && !fileRecoveryAttempted) {
                            // Files can be removed by the download screen while this image is visible.
                            // Recover just this image from its retained source address.
                            fileRecoveryAttempted = true
                            failedMaterializedUrl = requested.url
                            loadedImage = null
                            reload++
                        } else {
                            loading = false
                            error = result.throwable.message ?: "图片暂时无法读取"
                        }
                    } },
                )
                .build() }
        }
        LaunchedEffect(resource, loadedImage, pageVisible) {
            val image = loadedImage ?: return@LaunchedEffect
            if (resource != null) {
                readerImageDimensions(SingletonImageLoader.get(imagePlatformContext(context)), resource,
                    image.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId),
                    originalPage = page.takeIf { reload == 0 || resource.url == page.url }, refresh = true,
                    originalCacheIdentity = originalCacheIdentity)?.let { size ->
                    observedDimensions = size
                    dimensionsMeasured(size)
                }
            }
        }
        LaunchedEffect(attempt, resource, loadedImage, pageVisible) {
            if (!pageVisible || resource == null) return@LaunchedEffect
            while (attempt.acceptsResult) {
                val artifact = inspectReaderPreparedArtifacts(context, resource, headers, sourceId,
                    loadedImage?.diskCacheKey, loadedImage?.memoryCacheKey)
                if (attempt.acceptsResult) {
                    preparationTracker?.artifact(page.index, artifact.originalPresent, artifact.originalBytes, artifact.previewResident)
                    preparationTracker?.sample()
                }
                delay(2_000L)
            }
        }
        val imageError = error
        // A saved/materialized image is ready to display; an old remote prefetch cannot hold it back.
        val awaitingPrefetch = waitingForPrefetch && resource?.url?.startsWith("file:") != true
        val pendingRecovery = loading && imageError == null
        val naturalHeight = maxWidth / aspect
        val naturalHeightPx = with(density) { naturalHeight.roundToPx() }
        val pendingAspect = layoutDimensions?.readerAspectRatio()
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
            if (request != null && !awaitingPrefetch && imageError == null && (pageVisible || loadedImage != null)) key(reload) {
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
                    key(detailRetry) {
                        ZoomableAsyncImage(
                            model = request,
                            state = imageState,
                            contentDescription = "第 ${page.index + 1} 页",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alignment = pagedAlignment,
                            // Until the preview arrives, the containing page owns the tap for chrome.
                            gesturesEnabled = !loading,
                            onClick = { onTap() },
                        )
                    }
                    imageState.detailNotice?.takeIf { imageState.detailError == null }?.let { message ->
                        Text(message, Modifier.align(Alignment.BottomCenter).padding(16.dp),
                            color = Color.Gray, fontSize = 11.sp)
                    }
                    imageState.detailError?.let { message ->
                        Column(Modifier.align(Alignment.BottomCenter).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(message, color = Color.Gray, fontSize = 11.sp)
                            TextButton(onClick = {
                                imageState.detailError = null
                                imageState.detailNotice = null
                                detailRetry++
                            }) { Text("重试原图细节", color = Color.Gray) }
                        }
                    }
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
                        key(resource?.url, detailRetry) {
                            VerticalReaderTiles(requireNotNull(resource), loadedImage?.diskCacheKey,
                                originalSize, viewportWidthPx, naturalHeightPx, visibleWindow,
                                onDetailStatus = { detailStatus = it })
                        }
                    }
                }
            }
            if (!paged) detailStatus?.let { status ->
                Column(Modifier.align(Alignment.TopCenter).offset {
                    IntOffset(0, (visibleWindow()?.top ?: 0) + 64.dp.roundToPx())
                }.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(status.message, color = Color.Gray, fontSize = 11.sp)
                    if (status.retryable) TextButton(onClick = { detailStatus = null; detailRetry++ }) { Text("重试原图细节", color = Color.Gray) }
                }
            }
            if (loading || awaitingPrefetch || imageError != null) {
                // This page-local sibling sits ABOVE Telephoto while it has no usable image.
                // Its click cancels on drag, so the containing pager/list still owns scrolling.
                Box(Modifier.matchParentSize().clickable(
                    interactionSource = remember(page.url) { MutableInteractionSource() }, indication = null,
                    onClickLabel = "显示或收起阅读工具", onClick = onTap,
                ))
            }
            if (pageVisible && (imageError != null || delayedLoading && pendingRecovery)) {
                var feedbackHeight by remember { mutableIntStateOf(0) }
                val feedbackPosition = if (paged) Modifier.align(Alignment.Center)
                else Modifier.align(Alignment.TopCenter).offset {
                    val window = visibleWindow()
                    val top = window?.top ?: 0
                    val height = window?.let { it.bottom - it.top } ?: naturalHeightPx
                    IntOffset(0, top + ((height - feedbackHeight) / 2).coerceAtLeast(0))
                }
                ReaderImageRecovery(
                    index = page.index, pageCount = pageCount, sourceName = sourceName,
                    failure = imageError.orEmpty(), pending = pendingRecovery, slow = slowLoading,
                    transferProgress = pageTransfer ?: transportProgress.takeIf { it.receivedBytes > 0L }?.let {
                        DownloadProgress(phase = dev.mangaflow.storage.DownloadPhase.TRANSFERRING,
                            receivedBytes = it.receivedBytes, expectedBytes = it.expectedBytes)
                    },
                    stage = when {
                        prefetchFailure != null && transportProgress.receivedBytes == 0L -> "预取发现异常 · 正在重新读取"
                        resource == null -> "检查本机与已有下载"
                        awaitingPrefetch && reload > 0 -> "正在结束本页旧请求"
                        awaitingPrefetch -> "接续本页预读"
                        resource.url.startsWith("file:") -> "解码本机图片"
                        else -> "请求图片"
                    },
                    attempt = attempt, startedAt = waitClock ?: attempt.startedAt, foreground = foreground,
                    modifier = feedbackPosition.onSizeChanged { feedbackHeight = it.height },
                    alternatives = sourceAlternatives,
                    readySourceVariants = readySourceVariants,
                    onRetry = { reload++ }, onSwitch = onQuickSwitch, onMore = onMoreSources,
                    onRepairPage = onRepairPage,
                    onControls = onShowControls,
                    onStop = {
                        attempt.stopped = true; error = "已停止等待"; loading = false; loadChanged(false)
                    },
                )
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
    sourceId: String,
    resolvePage: ReaderPageResolver,
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
                .heightIn(max = LocalMangaWindow.current.panelHeight),
            backdrop,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                ReaderPanelHeading("页导航", "关闭页导航", onDismiss)
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(76.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(pages, key = { it.index }) { page ->
                        val selected = page.index == currentPage
                        var preview by remember(page) { mutableStateOf<Page?>(null) }
                        LaunchedEffect(page, visible, resolvePage) {
                            if (!visible) return@LaunchedEffect
                            try { preview = resolvePage(page, null) }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { preview = null }
                        }
                        val request = remember(preview, headers, sourceId) {
                            preview?.let { readerImageRequest(context, it, headers, sourceId).crossfade(0).build() }
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
                                fontFamily = mangaFontFamily(MangaFontScene.NUMBERS), fontSize = 12.sp,
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
    pages: List<Page>,
    pendingPage: Int?,
    headers: Map<String, String>,
    sourceId: String,
    enabled: Boolean,
    onSeek: (Int) -> Unit,
    onOpenNavigator: () -> Unit,
    onInteractionChanged: (Boolean) -> Unit,
    sourceContent: (@Composable () -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val interactionChanged by rememberUpdatedState(onInteractionChanged)
    var preview by remember { mutableStateOf<Float?>(null) }
    val pageCount = pages.size
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val shown = (preview ?: pendingPage?.toFloat() ?: pageIndex.toFloat()).coerceIn(0f, lastPage.toFloat())
    val dimensionRevision by ReaderDimensionCache.revision.collectAsStateWithLifecycle()
    val shownPage = pages.getOrNull(shown.roundToInt())
    val originalSize = remember(shownPage, headers, sourceId, dimensionRevision) {
        shownPage?.let { ReaderDimensionCache.get(readerPageCacheKey(it, headers, sourceId)) }
    }
    val resolutionLabel = originalSize?.let { "${it.width} × ${it.height} px" } ?: "—"
    val resolutionDescription = originalSize?.let { "原图 ${it.width} × ${it.height} 像素" } ?: "原图尺寸尚未获取"
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)

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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        sourceContent?.invoke()
        ReaderSeekBar(
            value = shown,
            onValueChange = { preview = it },
            onValueChangeFinished = {
                preview?.let { onSeek(it.roundToInt().coerceIn(0, lastPage)) }
                preview = null
            },
            valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f).padding(end = 4.dp),
            enabled = enabled && pageCount > 1,
            interactions = interactions,
            label = "阅读进度，第 ${shown.roundToInt() + 1} 页，共 $pageCount 页",
        )
        Column(Modifier.width(112.dp * fontScale).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled && pageCount > 0, role = Role.Button,
                onClickLabel = "打开页面导航", onClick = onOpenNavigator)
            .semantics { contentDescription = "第 ${shown.roundToInt() + 1} 页，共 $pageCount 页，$resolutionDescription" }
            .padding(horizontal = 4.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Text(mangaNumberText("${shown.roundToInt() + 1}"), color = colors.onSurface, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                Text(mangaNumberText(" / $pageCount"), color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Text(mangaNumberText(resolutionLabel), color = colors.onSurfaceVariant, fontSize = 12.sp,
                lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun rememberReaderTouchExploration(): Boolean =
    dev.mangaflow.ui.motion.LocalTouchExplorationEnabled.current

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
    MotionDialog(visible = visible, onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val availableHeight = LocalMangaWindow.current.panelHeight
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
    embedded: Boolean = false,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val desktop = platformKind == PlatformKind.DESKTOP
    val groups = remember(chapters) { chapters.map(Chapter::group).distinct() }
    val positions = rememberChapterPositions(manga, library, active = visible)
    val currentGroup = chapters.firstOrNull { it.id == currentId }?.group ?: groups.firstOrNull().orEmpty()
    var selectedGroup by rememberSaveable { mutableStateOf(currentGroup) }
    val group = selectedGroup.takeIf { it in groups } ?: currentGroup
    val visibleChapters = remember(chapters, group) { chapters.filter { it.group == group }.sortedBy(Chapter::index) }
    val catalogBusy = rememberDelayedBusy(visible && loading)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    LaunchedEffect(visible, currentId, currentGroup) { if (visible) selectedGroup = currentGroup }
    val panel: @Composable () -> Unit = {
        val downloadFlow = remember(downloadStore, manga.id) { downloadStore.observeManga(manga.id) }
        val initialDownloads = remember(downloadStore, manga.id) {
            downloadStore.entries.value.filter { it.manga.id == manga.id }
        }
        val downloads by downloadFlow.collectAsStateWithLifecycle(initialValue = initialDownloads)
        val saved = remember(downloads) { downloads.associateBy { it.chapter.id } }
        val surface = if (embedded) Modifier.fillMaxSize().motionInputGate(visible)
            else Modifier.padding(horizontal = 16.dp).widthIn(max = 600.dp).fillMaxWidth()
        ReaderDialogSurface(surface, backdrop, dialogWindow = !embedded) {
            Column((if (embedded) Modifier.fillMaxSize() else Modifier.heightIn(max = LocalMangaWindow.current.panelHeight))
                .padding(horizontal = if (embedded) 12.dp else 16.dp, vertical = 10.dp)) {
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
                    selected = groups.indexOf(group), labels = groups.map(::chapterGroupLabel), onSelect = { selectedGroup = groups[it] },
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    compact = true, underline = true, indicator = colors.onSurface.copy(alpha = .75f), fontSize = 12.sp,
                )
                if (!loading && error != null) Text(error, Modifier.padding(vertical = 6.dp),
                    color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MotionTabContent(
                    selected = group,
                    modifier = (if (embedded) Modifier.weight(1f).fillMaxWidth()
                        else Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = 450.dp)),
                    contentKey = { it }, order = { groups.indexOf(it) },
                    fillContent = embedded,
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
                    Box(Modifier.fillMaxWidth()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive((if (embedded) 112.dp else 68.dp) * fontScale), state = state,
                        modifier = Modifier.fillMaxWidth().padding(end = if (desktop) 10.dp else 0.dp),
                        userScrollEnabled = active,
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
                    DesktopVerticalScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp))
                    }
                }
            }
        }
    }
    if (embedded) panel()
    else MotionDialog(visible = visible, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), content = panel)
}

@Composable
internal fun ReaderPanelHeading(title: String, closeLabel: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        ReaderIcon(Icons.Rounded.Close, closeLabel, onClick = onDismiss)
    }
}

/** The reader owns the action; the shared micro icon observes the existing download entry. */
@Composable
private fun ReaderDownloadAction(
    chapterId: String,
    entry: DownloadEntry?,
    submitting: Boolean,
    label: String,
    enabled: Boolean,
    interactive: Boolean,
    onClick: () -> Unit,
) {
    val state = when {
        submitting -> CompactDownloadState.DOWNLOADING
        entry?.status == DownloadStatus.QUEUED || entry?.status == DownloadStatus.WAITING -> CompactDownloadState.WAITING
        entry?.status == DownloadStatus.RUNNING -> CompactDownloadState.DOWNLOADING
        entry?.status == DownloadStatus.PAUSED -> CompactDownloadState.PAUSED
        entry?.status == DownloadStatus.COMPLETED -> CompactDownloadState.COMPLETE
        entry?.status == DownloadStatus.FAILED -> CompactDownloadState.ERROR
        else -> CompactDownloadState.AVAILABLE
    }
    val progress = entry?.takeIf { it.totalPages > 0 }?.let {
        it.completedPages.toFloat() / it.totalPages
    }
    val actionFinished = LocalReaderActionFinished.current
    MotionIconButton({ onClick(); actionFinished() }, enabled = enabled && interactive,
        modifier = Modifier.size(44.dp), tooltip = label) {
        key(chapterId) {
            CompactDownloadIcon(state, progress = if (submitting) null else progress,
                contentDescription = label,
                activeIcon = if (submitting) Icons.Rounded.Download else Icons.Rounded.Pause)
        }
    }
}

@Composable
private fun ReaderIcon(icon: ImageVector, label: String, enabled: Boolean = true, interactive: Boolean = true,
    emphasis: Float = .82f, buttonSize: Dp = 48.dp, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) emphasis else .25f)
    val actionFinished = LocalReaderActionFinished.current
    MotionIconButton(onClick = { onClick(); actionFinished() }, enabled = enabled && interactive,
        modifier = Modifier.size(buttonSize), tooltip = label) {
        Icon(icon, label, Modifier.size(21.dp), tint = tint)
    }
}

private fun ReaderTheme.background() = when (this) { ReaderTheme.INK -> Color(0xFF101012); ReaderTheme.PAPER -> Color(0xFFF7F6F2); ReaderTheme.SEPIA -> Color(0xFFECE4D5) }
private fun ReaderTheme.foreground() = if (this == ReaderTheme.INK) Color.White else Color(0xFF242426)
