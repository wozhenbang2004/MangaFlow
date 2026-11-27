@file:OptIn(me.saket.telephoto.ExperimentalTelephotoApi::class)

package dev.mangaflow.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.zoomable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import dev.mangaflow.storage.swipeRightForNext

import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.chapterGroupLabel
import dev.mangaflow.ui.mangaNumberText
import dev.mangaflow.ui.ChapterSourceLogo
import dev.mangaflow.enhancement.*
import dev.mangaflow.enhancement.ui.EnhancementCenterPanel
import androidx.compose.material.icons.rounded.AutoAwesome
import dev.mangaflow.ui.motion.MotionSlider

import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.data.ReaderSourceStandby
import dev.mangaflow.data.ReaderSourceStandbyPhase
import dev.mangaflow.data.sources.materializeJmPage
import dev.mangaflow.data.sources.prefetchJmPage
import dev.mangaflow.data.plugins.resolveScriptPage
import dev.mangaflow.data.plugins.materializeScriptPage
import dev.mangaflow.data.plugins.prefetchScriptPage
import dev.mangaflow.data.plugins.invalidateScriptImage
import dev.mangaflow.data.plugins.discardFailedScriptImage

import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.MangaLayout
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.ui.ChapterSearchField
import dev.mangaflow.ui.rememberMatchingChapters
import dev.mangaflow.platform.LocalWindowFullscreen
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
import androidx.compose.animation.core.updateTransition
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Source
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.storage.StorageFile
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import dev.mangaflow.data.ChapterSemantics
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.DownloadProgress
import dev.mangaflow.experience.*
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.PageCollection
import dev.mangaflow.storage.PrivateChapterKey
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
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.rememberFeelFeedback
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.mangaflow.reader.ReaderZoomableImage as ZoomableAsyncImage
import dev.mangaflow.reader.rememberReaderImageState as rememberZoomableImageState

/** Only policy fields that change the reader's behavior belong to its root composition. */
private data class ReaderBehaviorBudget(
    val standbySources: Int,
    val crossChapter: Boolean,
    val nextChapterPages: Int,
    val recovery: ReaderRecoveryPreferences,
    val enhancementAhead: Int,
    val enhancementProtected: Boolean,
)

private fun ExperienceBudget.readerBehaviorBudget() = ReaderBehaviorBudget(
    standbySources, crossChapter, runtime.nextChapterPages, recovery, prefetchAhead, protections.isNotEmpty(),
)

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
    adultProfile: Boolean = false,
    adultSourceIds: Set<String> = emptySet(),
    onRequestNotificationPermission: () -> Unit = {},
    onChapterVisible: (Manga, Chapter) -> Unit = { _, _ -> },
    onReadingProgress: (Manga, Chapter, Int, Int) -> Unit = { _, _, _, _ -> },
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
    onWarmSourceStandbys: (Chapter, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    onRetryStandby: (Chapter, String) -> Unit = { _, _ -> },
    onRescueSourceStandbys: (Chapter, Boolean) -> Unit = { _, _ -> },
    onVerifiedSourceSwitch: (Chapter, ChapterVariant, Int, String) -> Unit = { _, _, _, _ -> },
    canUndoSourceSwitch: Boolean = false,
    onUndoSourceSwitch: () -> Unit = {},
    onPrefetchPageFailed: (Chapter, Page) -> Unit = { _, _ -> },
    onCancelSourceStandbys: () -> Unit = {},
    desktopFindRequest: Int = 0,
    onDesktopFindHandled: (Int) -> Unit = {},
) {
    val libraryReady by libraryStore.ready.collectAsStateWithLifecycle()
    val adultImageContext = LocalContext.current
    val experience = remember(adultImageContext) { ExperiencePolicyStore.get(adultImageContext) }
    val readerBudget = remember(experience) {
        experience.state.map { it.readerBehaviorBudget() }.distinctUntilChanged()
    }
    val experienceBudget by readerBudget.collectAsStateWithLifecycle(
        initialValue = experience.state.value.readerBehaviorBudget(),
    )
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
        (mangaPreferences[manga.id] ?: globalPreferences).copy(pageTurnGesture = globalPreferences.pageTurnGesture)
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
    val discussionState = if (!adultProfile) rememberChapterDiscussionState(manga, chapter, communityRepository, reviewRepository) else null
    val privateDiscussionKey = if (adultProfile && !localImport) remember(manga.id, chapter, adultSourceIds) {
        PrivateChapterKey.from(manga, chapter, adultSourceIds)
    } else null
    val privateDiscussion = privateDiscussionKey?.let { rememberPrivateChapterDiscussionState(it) }
    val commentsAvailable = !localImport && (discussionState != null || privateDiscussion != null)
    val siblings = remember(chapters, chapter, chaptersLoading, chaptersError) {
        ChapterSemantics.readingSequence(chapters, chapter,
            catalogComplete = !chaptersLoading && chaptersError == null)
    }
    val hasMultipleGroups = remember(chapters) { chapters.map(Chapter::group).distinct().size > 1 }
    val chapterIndex = siblings.indexOfFirst { it.id == chapterId }
    var retry by remember(chapterId) { mutableIntStateOf(0) }
    var currentPageRetrySerial by remember(chapterId) { mutableIntStateOf(0) }
    var currentPageRetryIndex by remember(chapterId) { mutableIntStateOf(-1) }
    var pages by remember(chapterId) { mutableStateOf<List<Page>>(emptyList()) }
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
    var pageFocusRequest by remember(chapterId) { mutableIntStateOf(0) }
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
    var controlsVisible by rememberSaveable { mutableStateOf(false) }
    val fullscreen = LocalWindowFullscreen.current
    val immersive = fullscreen?.active == true
    val hideAmbientChrome = immersive && !controlsVisible
    var bottomControlsHeightPx by remember { mutableIntStateOf(0) }
    var readerStatusInspecting by remember(chapterId) { mutableStateOf(false) }
    var readerStatusOpen by remember(chapterId) { mutableStateOf(false) }
    var restoreStatusFocus by remember(chapterId) { mutableStateOf(false) }
    val statusControlFocus = remember { FocusRequester() }
    val placementFlow = remember(experience) { experience.overrides.map { it.readerStatusPlacement }.distinctUntilChanged() }
    val statusPlacement by placementFlow.collectAsStateWithLifecycle(initialValue = experience.overrides.value.readerStatusPlacement)
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showEnhancement by rememberSaveable { mutableStateOf(false) }
    var comparisonTarget by remember(chapterId) { mutableStateOf<ReaderComparisonTarget?>(null) }
    val enhancementDivider = remember(chapterId) { mutableFloatStateOf(.5f) }
    var showPanelModels by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!PanelReadingAvailable) showPanelModels = false }
    var showComments by remember(chapterId) { mutableStateOf(false) }
    LaunchedEffect(commentsAvailable) { if (!commentsAvailable) showComments = false }
    var showSpreadAdjustment by remember(chapterId) { mutableStateOf(false) }
    var manualSingles by remember(manga.id, chapterId, presentedCollection?.key) {
        mutableStateOf(presentedCollection?.singlePages ?: libraryStore.spreadSinglesFor(manga.id, chapterId))
    }
    var atChapterEnd by remember(chapterId, preferences.mode == ReadingMode.VERTICAL, preferences.doublePage, preferences.coverAlone, manualSingles,
        preferences.panelLayoutKey) {
        mutableStateOf(false)
    }
    var chapterChangeRequested by remember(chapterId) { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var chaptersQuery by rememberSaveable(manga.id) { mutableStateOf("") }
    var handledFindRequest by rememberSaveable(manga.id) { mutableIntStateOf(0) }
    var focusReadingAfterSelection by remember(manga.id) { mutableStateOf(false) }
    val desktop = platformKind == PlatformKind.DESKTOP
    val readerWindow = LocalMangaWindow.current
    val readerFontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val dockedPanels = desktop && readerWindow.width >= 960.dp * readerFontScale &&
        readerWindow.height >= 480.dp
    val panelWidth = (if (readerWindow.width >= 1280.dp * readerFontScale) 336.dp else 304.dp) * readerFontScale
    val inspectorOpen = showSettings || showChapters || showPageNavigator
    val panelBlocksReading = !dockedPanels && (showSettings || showChapters)
    val pageNavigatorBlocking = showPageNavigator && !dockedPanels
    val wideReaderChrome = desktop && readerWindow.width - (if (dockedPanels && inspectorOpen) panelWidth else 0.dp) >=
        790.dp * readerFontScale
    var retainedInspector by remember { mutableStateOf(ReaderInspector.CHAPTERS) }
    val inspector = when {
        showSettings -> ReaderInspector.SETTINGS
        showPageNavigator -> ReaderInspector.PAGES
        showChapters -> ReaderInspector.CHAPTERS
        else -> retainedInspector
    }
    SideEffect { if (inspectorOpen) retainedInspector = inspector }
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
    var recentConfirmed by remember(manga.id, chapterId) { mutableStateOf(false) }
    var chapterVisibilityReported by remember(manga.id, chapterId) { mutableStateOf(false) }
    var leavingReader by remember { mutableStateOf(false) }
    val nextChapter = siblings.getOrNull(chapterIndex + 1).takeIf { chapterIndex >= 0 }
    var nextChapterPrefetched by remember(manga.id, chapterId, nextChapter?.id) { mutableStateOf(false) }
    val previousChapter = siblings.getOrNull(chapterIndex - 1).takeIf { chapterIndex > 0 }
    val openPreviousChapter: (() -> Unit)? = previousChapter?.let { previous ->
        {
            if (active && !leavingReader && !sourcePickerBlocking && !chapterChangeRequested && chapterId == chapter.id) {
                onCancelSourceSelection()
                chapterChangeRequested = true
                chapterId = previous.id
            }
        }
    }
    val openNextChapter: (() -> Unit)? = nextChapter?.let { next ->
        {
            if (active && !leavingReader && !sourcePickerBlocking && !chapterChangeRequested && chapterId == chapter.id) {
                onCancelSourceSelection()
                chapterChangeRequested = true
                chapterId = next.id
            }
        }
    }
    val chapterVisible by rememberUpdatedState(onChapterVisible)
    val readingProgress by rememberUpdatedState(onReadingProgress)
    val nearChapterEnd by rememberUpdatedState(onNearChapterEnd)
    val scope = rememberCoroutineScope()
    val sourceIdentity = ReaderSourceIdentity(chapter.logicalId.ifBlank { chapter.id },
        chapter.id, chapter.sourceId, chapter.sourceMangaId)
    val enhancementContent = if (adultProfile) EnhancementContent.ADULT_MANGA else EnhancementContent.MANGA
    val enhancementCenter = remember(adultImageContext) { EnhancementCenter.get(adultImageContext) }
    val enhancement = remember(manga.id, sourceIdentity, enhancementContent, scope) {
        ReaderEnhancementController(adultImageContext, enhancementContent, manga.id, chapterId, chapter.sourceId, scope,
            workSourceId = manga.sourceId)
    }
    DisposableEffect(enhancement) { onDispose { enhancement.close() } }
    val enhancementDefaults by enhancementCenter.settings.state.collectAsStateWithLifecycle()
    val enhancementOverrides by enhancementCenter.settings.sessions.collectAsStateWithLifecycle()
    val enhancementPolicy = remember(enhancementDefaults, enhancementOverrides, enhancement.sessionKey, enhancementContent) {
        enhancementCenter.settings.policy(enhancementContent, enhancement.sessionKey)
    }
    val enhancementState by enhancement.state.collectAsStateWithLifecycle()
    val latestComparisonOwner by rememberUpdatedState(enhancement to pageIndex)
    val compareEnhancement = comparisonTarget?.matches(enhancement, pageIndex,
        enhancementState.assets[pageIndex], enhancementPolicy) == true
    val readerToolsVisible = controlsVisible && !readerStatusInspecting && !readerStatusOpen && !panelBlocksReading &&
        !showPanelModels && !showEnhancement && !compareEnhancement && !showSpreadAdjustment && !showComments && !pageNavigatorBlocking
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
    val latestReadingIdentity by rememberUpdatedState(manga.id to sourceIdentity)
    var headers by remember(manga.id, chapterId, chapter.sourceId, chapter.sourceMangaId, repository, localImport) {
        mutableStateOf(if (localImport) emptyMap() else
            repository.imageHeaders(chapterSourceMangaId(manga.id, chapter), chapterId).toMap())
    }
    val isolatedPanelLayout = if (preferences.panelEngine == PanelEngine.OFF) null else
        listOf(preferences.mode, preferences.panelLayoutKey, preferences.doublePage, preferences.coverAlone)
    val preparationTracker = remember(sourceIdentity, pages, headers) { ReaderPreparationTracker(chapter.sourceId, pages.size) }
    val sharedPrefetchRequests = remember(sourceIdentity, pages, headers) { mutableStateMapOf<Int, ReaderPagePrefetch>() }
    val sharedPrefetchFailures = remember(sourceIdentity, pages, headers) { mutableStateMapOf<Int, ReaderPrefetchFailure>() }
    val enhancementSources = remember(sourceIdentity, pages, headers) {
        mutableStateMapOf<Int, ReaderEnhancementSourceReceipt>()
    }
    val preparationWindow by key(preparationTracker) {
        val windows = remember {
            preparationTracker.state.map { it.readingWindow() }.distinctUntilChanged()
        }
        windows.collectAsStateWithLifecycle(initialValue = remember { preparationTracker.state.value.readingWindow() })
    }
    val comparisonSnapshot = comparisonTarget
    LaunchedEffect(comparisonSnapshot, compareEnhancement) {
        if (!compareEnhancement && comparisonTarget == comparisonSnapshot) comparisonTarget = null
    }
    BackHandler(enabled = active && compareEnhancement) { comparisonTarget = null }
    LaunchedEffect(sourceIdentity, chapter, pageIndex, pages, standbyActive, loading, error,
        sourcePickerBlocking, chapterChangeRequested, experienceBudget.standbySources, preparationWindow.direction) {
        // A stalled or failed visible image is exactly when spare sources matter. Do not
        // gate standby preparation on the visible image reaching READY.
        if (!standbyActive || pages.isEmpty() ||
            sourcePickerBlocking || chapterChangeRequested) return@LaunchedEffect
        // A finite intent delay follows the actual cursor; rapid paging replaces stale work.
        delay(350)
        warmSourceStandbys(chapter, pageIndex.coerceIn(pages.indices), pages.size, preparationWindow.direction)
    }
    val chapterDownload = remember(downloadStore, manga.id, chapterId) { downloadStore.observeChapter(manga.id, chapterId) }
    val initialDownload = remember(downloadStore, manga.id, chapterId) {
        downloadStore.entries.value.firstOrNull { it.manga.id == manga.id && it.chapter.id == chapterId }
    }
    val activeDownload by chapterDownload.collectAsStateWithLifecycle(initialValue = initialDownload)
    val storageCache = remember(adultImageContext) { dev.mangaflow.storage.StorageCacheStore.get(adultImageContext) }
    val cacheRangeOwner = remember(storageCache, manga.id, chapterId, pages, headers) { Any() }
    // The retained reader owns this chapter even while settings are overlaid or the app is paused.
    DisposableEffect(storageCache, manga.id, chapterId, active, cacheRangeOwner) {
        val release = if (active) storageCache.protectReading(manga.id, chapterId, chapterSourceMangaId(manga.id, chapter), cacheRangeOwner) else ({})
        onDispose { release() }
    }
    LaunchedEffect(storageCache, manga.id, chapterId, pages, headers, active) {
        storageCache.registerPages(manga.id, chapterId,
            dev.mangaflow.data.SourceKeys.decodeChapter(chapterSourceMangaId(manga.id, chapter), chapterId).sourceId,
            pages, headers, rangeOwner = cacheRangeOwner)
    }
    val background = preferences.theme.background()
    val colors = MaterialTheme.colorScheme
    val lightChrome = colors.surface.luminance() > .5f
    val readerBackdrop = key(manga.id) { rememberHazeState() }
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
                    materializeJmPage(adultImageContext, owner, readingChapterId, materialized, onBytes = onBytes) ?: materialized
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
                    ?: throw ReaderPrefetchDownloadOwned()
                if (local.url.startsWith("file:")) {
                    // A download can finish after resolution but before this attempt starts.
                    // This shortcut has no script/JM receipt, so charge its actual local size.
                    val bytes = withContext(Dispatchers.IO) {
                        readerFilePath(local.url)?.let { StorageFile(it).length().takeIf { size -> size > 0L } }
                    }
                    if (bytes != null) work.ifOptional {
                        try { budget.accept(bytes) }
                        catch (_: PrefetchBudgetExceeded) { budget.exhaust() }
                    }
                    local
                } else {
                    val owner = chapterSourceMangaId(manga.id, chapter)
                    if (dev.mangaflow.data.SourceKeys.decodeChapter(owner, readingChapterId).sourceId == "jmcomic")
                        prefetchJmPage(adultImageContext, owner, readingChapterId, local, budget, work)
                    else prefetchScriptPage(adultImageContext, owner, readingChapterId, local, budget, work)
                }
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
                val materialized = materializeScriptPage(adultImageContext, owner, readingChapterId, resolved,
                    onBytes = onBytes, work = work, retry = true)
                if (dev.mangaflow.data.SourceKeys.decodeChapter(owner, readingChapterId).sourceId == "jmcomic")
                    materializeJmPage(adultImageContext, owner, readingChapterId, materialized,
                        onBytes = onBytes, retry = true) ?: materialized
                else materialized
            }
        }
    }
    val latestRepairActive by rememberUpdatedState(readerForeground && !leavingReader && !chapterChangeRequested)
    val latestPrimaryPages by rememberUpdatedState(pages)
    val repairDensity = LocalDensity.current
    val repairViewportWidth = with(repairDensity) { readerWindow.width.roundToPx() }
    val repairViewportHeight = with(repairDensity) { readerWindow.height.roundToPx() }
    val repairs = remember(manga.id, sourceIdentity, pages, headers, repository, downloadStore) {
        val ownedPages = pages
        ReaderPageRepairController(adultImageContext, manga.id, chapter, ownedPages, headers,
            repository, downloadStore, scope, repairViewportWidth, repairViewportHeight) {
            latestReadingIdentity == (manga.id to sourceIdentity) && latestPrimaryPages === ownedPages && latestRepairActive
        }
    }
    SideEffect { repairs.updatePresentation(chapter, repairViewportWidth, repairViewportHeight) }
    val repairState by repairs.state.collectAsStateWithLifecycle()
    val preferredRepairVariants by rememberUpdatedState(readySourceVariants)
    val openPageRepair: (Int) -> Unit = remember(repairs, alternativeSources) {
        { index ->
            val variant = repairs.state.value.replacements[index]?.variant
                ?.takeIf { selected -> alternativeSources.any { it.storageId == selected.storageId } }
                ?: alternativeSources.firstOrNull { it.storageId in preferredRepairVariants }
                ?: alternativeSources.firstOrNull()
            if (variant != null) repairs.select(index, variant)
        }
    }
    val rescueStandbys by rememberUpdatedState(onRescueSourceStandbys)
    val rescuePages = remember(sourceIdentity) { mutableStateMapOf<Int, Boolean>() }
    val visibleRepairPages = remember(pageIndex, preparationWindow.visible, pages.size) {
        if (pageIndex !in pages.indices) emptyList()
        else if (pageIndex in preparationWindow.visible)
            listOf(pageIndex) + preparationWindow.visible.filter { it in pages.indices && it != pageIndex }
        else listOf(pageIndex)
    }
    val latestVisibleRepairPages by rememberUpdatedState(visibleRepairPages)
    val verifiedSourceSwitch by rememberUpdatedState(onVerifiedSourceSwitch)
    DisposableEffect(repairs) { onDispose { repairs.close() } }
    LaunchedEffect(repairs, readerForeground) { if (!readerForeground) repairs.pause() }
    LaunchedEffect(repairs, experienceBudget.recovery) {
        if (experienceBudget.recovery.sourceLocked || !experienceBudget.recovery.autoRepair && !experienceBudget.recovery.autoSwitch)
            repairs.suspendAutomatic()
    }
    LaunchedEffect(repairs, repairState.busy, repairState.selection, visibleRepairPages, readerForeground, experienceBudget.recovery) {
        if (readerForeground && !repairState.busy && repairState.selection == null) repairs.recoverVisible(visibleRepairPages)
    }
    LaunchedEffect(repairs, readerForeground) {
        if (!readerForeground) return@LaunchedEffect
        while (true) {
            repairs.inspectOwnership()
            delay(experience.state.value.runtime.cacheSampleSeconds.coerceAtLeast(1).toLong() * 1000L)
        }
    }
    LaunchedEffect(repairState.switchReady, visibleRepairPages, sourceIdentity, readerForeground, sourceLoading, sourcePickerBlocking,
        leavingReader, chapterChangeRequested, experienceBudget.recovery.sourceLocked, experienceBudget.recovery.autoSwitch) {
        val failedPage = repairState.switchReady?.alignment?.primaryIndex ?: return@LaunchedEffect
        if (readerForeground && !sourceLoading && !sourcePickerBlocking && !leavingReader && !chapterChangeRequested &&
            failedPage in visibleRepairPages && !experience.state.value.recovery.sourceLocked && experience.state.value.recovery.autoSwitch) {
            val ready = repairs.consumeSwitch(failedPage) ?: return@LaunchedEffect
            verifiedSourceSwitch(chapter, ready.variant, ready.alignment.targetIndex, ready.alignment.targetManifest)
        }
    }
    LaunchedEffect(rescuePages.keys.toList(), readerForeground, sourceIdentity, standbyAvailability, sourceStandbys.isNotEmpty()) {
        rescueStandbys(chapter, readerForeground && rescuePages.isNotEmpty())
    }
    DisposableEffect(sourceIdentity) { onDispose { rescueStandbys(chapter, false) } }
    val reportPrefetchFailure = remember(manga.id, sourceIdentity, repairs) {
        { page: Page ->
            if (latestReadingIdentity == (manga.id to sourceIdentity) && pages.any { it == page }) {
                prefetchedPageFailed(chapter, page)
                repairs.failed(page.index)
                repairs.recoverVisible(latestVisibleRepairPages)
            }
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
    val readerDockWidth = if (desktop) 986.dp else 440.dp
    val touchExploration = rememberReaderTouchExploration()
    val chapterWaitVisible = readerForeground && !leavingReader && !panelBlocksReading && !showPanelModels &&
        !showComments && !showSpreadAdjustment && !pageNavigatorBlocking && !sourcePickerBlocking
    LaunchedEffect(chapterLoadAttempt, chapterWaitVisible, loading, error) {
        chapterSlowLoading = false
        if (chapterWaitVisible && loading && error == null && chapterLoadAttempt.acceptsResult) {
            chapterWaitClock = TimeSource.Monotonic.markNow()
            val slow = experience.state.value.recovery.slowLoadSeconds.coerceAtLeast(1).toLong() * 1000L
            val limit = experience.state.value.recovery.manifestTimeoutSeconds.coerceAtLeast(1).toLong() * 1000L
            delay(minOf(slow, limit))
            chapterSlowLoading = true
            delay((limit - slow).coerceAtLeast(0L))
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

    val clock = rememberReaderClock(readerToolsVisible && readerForeground && !leavingReader)
    val readingEligible = active && !leavingReader && !loading && error == null && imageVisible &&
        !panelBlocksReading && !showPanelModels && !showComments && !showSpreadAdjustment && !pageNavigatorBlocking &&
        !showReaderActions && !readerActionsPresent && !sourcePickerBlocking && !readerStatusInspecting &&
        repairState.selection == null
    RecordReaderActivity(manga, readingEligible, libraryStore, chapterId) { owner, confirmedChapter ->
        if (owner == manga.id && confirmedChapter == chapterId && readingEligible) {
            recentConfirmed = true
            libraryStore.saveProgress(manga, chapter, pageIndex, chapterPageCount, cursor.scrollOffset,
                panelIndex = if (preferences.panelEngine != PanelEngine.OFF) cursor.panelIndex else -1,
                panelLayout = if (preferences.panelEngine != PanelEngine.OFF) preferences.panelLayoutKey else "")
        }
    }
    // The renderer's end state includes the second page of a spread and the last guided panel.
    // Imported/LAN progress and offscreen prefetches never produce this user-reading event.
    LaunchedEffect(manga.id, chapterId, chapterPageCount, atChapterEnd, readingEligible, readerForeground, recentConfirmed) {
        if (!adultProfile && readingEligible && readerForeground && recentConfirmed && atChapterEnd && chapterPageCount > 0) {
            delay(750L)
            readingProgress(manga, chapter, chapterPageCount - 1, chapterPageCount)
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
        if (next.pageTurnGesture != globalPreferences.pageTurnGesture)
            libraryStore.updatePageTurnGesture(next.pageTurnGesture)
        // Persist only settings the user actually changed, never the collection's temporary layout.
        val saved = savedPreferences.copy(
            mode = if (next.mode != preferences.mode) next.mode else savedPreferences.mode,
            lastPagedMode = if (next.mode != preferences.mode) {
                next.mode.takeUnless { it == ReadingMode.VERTICAL }
                    ?: preferences.mode.takeUnless { it == ReadingMode.VERTICAL } ?: savedPreferences.lastPagedMode
            } else savedPreferences.lastPagedMode,
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
            showPageNavigator = false
            showSettings = true
            controlsVisible = true
        }
    }
    fun toggleReaderChapters() {
        if (showSettings) closeReaderSettings()
        showPageNavigator = false
        showChapters = !showChapters
        controlsVisible = true
        if (!showChapters) keyboardFocusRestore++
    }
    fun openPageNavigator() {
        if (pages.isEmpty() || !active || leavingReader) return
        if (showSettings) closeReaderSettings()
        showChapters = false
        showPageNavigator = true
        pageFocusRequest++
        controlsVisible = true
    }
    fun closePageNavigator() {
        showPageNavigator = false
        keyboardFocusRestore++
    }
    LaunchedEffect(active, desktopFindRequest) {
        if (desktop && active && desktopFindRequest > 0 && desktopFindRequest != handledFindRequest) {
            if (!sourcePickerBlocking && !showComments && !showPanelModels && !showSpreadAdjustment &&
                !pageNavigatorBlocking && repairState.selection == null) {
                if (showSettings) closeReaderSettings()
                handledFindRequest = desktopFindRequest
                showPageNavigator = false
                focusReadingAfterSelection = false
                showChapters = true
                controlsVisible = true
            }
            onDesktopFindHandled(desktopFindRequest)
        }
    }
    val pagePanel: @Composable (Boolean) -> Unit = { embedded ->
        ReaderPageNavigatorDialog(
            visible = showPageNavigator, embedded = embedded, focusRequest = pageFocusRequest,
            pages = pages,
            headers = headers,
            sourceId = chapter.sourceId,
            resolvePage = resolvePage,
            currentPage = pageIndex,
            pageInformation = {
                ReaderPageResolutionInfo(pages, headers, chapter.sourceId, pageIndex,
                    showSpread = preferences.panelEngine == PanelEngine.OFF && preferences.doublePage && preferences.mode != ReadingMode.VERTICAL,
                    enhancementEnabled = enhancementPolicy.tier != QualityTier.ORIGINAL, enhancement = enhancementState,
                    replacements = repairState.replacements, enhancementSources = enhancementSources)
            },
            collectedPages = remember(pageCollections, manga.id, chapterId) {
                pageCollections.filter { it.manga.id == manga.id && it.chapter.id == chapterId && !it.spread }
                    .map(PageCollection::firstPage).toSet()
            },
            onToggleCollection = { togglePicture(it) },
            backdrop = readerBackdrop,
            onSelect = { destination ->
                if (destination != pageIndex) {
                    returnLocation = ReaderReturnLocation(pageIndex, cursor.scrollOffset)
                    targetOffset = 0
                    targetPage = destination
                }
                if (!embedded) showPageNavigator = false
                focusReadingAfterSelection = embedded
                keyboardFocusRestore++
            },
            onDismiss = ::closePageNavigator,
        )
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
            visible = showChapters, chapters = chapters, currentId = chapterId, backdrop = readerBackdrop,
            manga = manga, library = libraryStore,
            downloadStore = downloadStore, loading = chaptersLoading, error = chaptersError, onRetry = onRetryChapters,
            embedded = embedded,
            query = chaptersQuery, onQuery = { chaptersQuery = it }, focusRequest = handledFindRequest,
            onSelect = {
                onCancelSourceSelection()
                if (it != chapterId) { chapterChangeRequested = true; chapterId = it }
                if (!dockedPanels) showChapters = false
                focusReadingAfterSelection = dockedPanels
                controlsVisible = true
                keyboardFocusRestore++
            },
            onDismiss = { showChapters = false; keyboardFocusRestore++ },
        )
    }

    ReaderPageRepairDialog(repairState, pages, headers, chapter.sourceId, alternativeSources, readerBackdrop, repairs)

    if (active) ReaderWindow(preferences.keepScreenOn, controlsVisible || showSettings || showPanelModels || showChapters || showSpreadAdjustment || showComments || sourcePickerBlocking || repairState.selection != null, lightChrome)
    val leaveReader = { onCancelSourceSelection(); leavingReader = true; onBack() }
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

    LaunchedEffect(controlsVisible, showSettings, showEnhancement, compareEnhancement, showChapters, showSpreadAdjustment, showComments,
        showPageNavigator, showReaderActions, readerActionsPresent, sourcePickerBlocking, readerStatusInspecting,
        loading, error, imageVisible, progressInteracting, touchExploration, chromeEngaged) {
        if (controlsVisible && !showSettings && !showEnhancement && !compareEnhancement && !showChapters && !showSpreadAdjustment && !showComments &&
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
    LaunchedEffect(immersive, active) {
        if (immersive && active && !touchExploration) {
            controlsVisible = false
            keyboardFocusRestore++
        }
    }
    LaunchedEffect(touchExploration) { if (touchExploration) controlsVisible = true }
    LaunchedEffect(discussionState, pageIndex, pages.size, readerForeground, leavingReader, localImport) {
        if (!localImport && readerForeground && !leavingReader && pages.isNotEmpty() && pageIndex >= pages.lastIndex - 3) {
            discussionState?.ensureLoaded()
        }
    }
    LaunchedEffect(atChapterEnd, touchExploration, chromeEngaged) {
        if (atChapterEnd && !(desktop && inspectorOpen) && !touchExploration && !chromeEngaged && !readerStatusInspecting) controlsVisible = false
    }

    val keyboard = LocalReaderKeyboard.current
    val keyboardFocus = remember { FocusRequester() }
    var keyboardFocused by remember { mutableStateOf(false) }
    var keyboardSerial by remember(sourceIdentity) { mutableIntStateOf(0) }
    var keyboardRequest by remember(sourceIdentity) { mutableStateOf<ReaderKeyboardRequest?>(null) }
    val desktopCanvasReady = desktop && readerForeground && !leavingReader && !panelBlocksReading &&
        !showPanelModels && !showEnhancement && !compareEnhancement && !showComments && !showSpreadAdjustment &&
        !pageNavigatorBlocking && !showReaderActions && !readerActionsPresent && !sourcePickerBlocking &&
        !readerStatusOpen && !readerStatusInspecting && !progressInteracting && repairState.selection == null
    // Focusing the canvas and revealing its tools need no decoded image. A new page turn
    // still requires the existing reading proof; a turn in flight can expose a placeholder.
    val keyboardReady = keyboard != null && desktopCanvasReady
    val keyboardTurnOwned = keyboardReady && !sourceLoading && !chapterChangeRequested &&
        !loading && error == null && pages.isNotEmpty() && preferences.panelEngine == PanelEngine.OFF
    LaunchedEffect(keyboardTurnOwned) {
        if (!keyboardTurnOwned) keyboardRequest = null
    }
    LaunchedEffect(keyboardReady, keyboardFocusRestore, readerStatusInspecting, inspector) {
        // Let closing chrome settle first. An open inspector keeps its own focused editor.
        if (restoreStatusFocus && readerToolsVisible && readerForeground && !leavingReader &&
            statusPlacement != ReaderStatusPlacement.HIDDEN) {
            withFrameNanos { }
            statusControlFocus.requestFocus()
            restoreStatusFocus = false
        } else if (keyboardReady && (!inspectorOpen || focusReadingAfterSelection)) {
            withFrameNanos { }
            keyboardFocus.requestFocus()
            focusReadingAfterSelection = false
        }
    }
    val keyboardModifier = if (keyboard == null) Modifier else Modifier
        .focusRequester(keyboardFocus)
        .onFocusChanged { keyboardFocused = it.isFocused }
        // Bubble only while this reading surface itself owns focus. Editors,
        // dialogs and their text/IME handling never share its shortcut target.
        .onKeyEvent { event ->
            if (!keyboardReady || !keyboardFocused || event.type != KeyEventType.KeyDown) false
            else keyboard.actionFor(event, preferences.mode != ReadingMode.VERTICAL &&
                preferences.swipeRightForNext(desktop = true))?.let { action ->
                if (action == ReaderKeyboardAction.PAGE_NAVIGATOR && pages.isNotEmpty() && !chapterChangeRequested) {
                    openPageNavigator()
                    true
                } else if (action == ReaderKeyboardAction.TOGGLE_CONTROLS) {
                    controlsVisible = !controlsVisible
                    true
                } else if (keyboardTurnOwned && readingEligible) {
                    keyboardRequest = ReaderKeyboardRequest(++keyboardSerial, action)
                    true
                } else false
            } ?: false
        }.focusable(enabled = keyboardReady)
    val desktopMouseRevealAllowed = desktopCanvasReady && !inspectorOpen && !touchExploration
    val revealDesktopTools by rememberUpdatedState<() -> Unit> {
        if (desktopMouseRevealAllowed && !controlsVisible) controlsVisible = true
    }
    val mouseReveal = if (desktopMouseRevealAllowed)
        Modifier.readerMouseEdgeReveal(MangaLayout.ListWidth, readerDockWidth) { revealDesktopTools() }
    else Modifier

    CompositionLocalProvider(LocalReaderActionFinished provides { if (desktop) keyboardFocusRestore++ },
        LocalReaderEnhancement provides enhancement,
        LocalReaderEnhancementSources provides enhancementSources,
        LocalReaderEnhancementPresentation provides ReaderEnhancementPresentation(enhancement, enhancementState,
            comparisonFraction = enhancementDivider.takeIf { compareEnhancement },
            comparisonPageIndex = pageIndex.takeIf { compareEnhancement },
            onComparisonFractionChange = { enhancementDivider.floatValue = it.coerceIn(0f, 1f) }),
        LocalReaderPreparationTracker provides preparationTracker,
        LocalReaderCacheRangeOwner provides cacheRangeOwner,
        LocalReaderPageRepair provides ReaderRepairRenderContext(repairState, repairs) { index, waiting ->
            if (waiting) rescuePages[index] = true else rescuePages.remove(index)
        }) {
    ReaderWorkspace(
        panelVisible = dockedPanels && inspectorOpen,
        panelWidth = panelWidth,
        panel = {
            Column(Modifier.fillMaxSize()) {
                ReaderSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    val destinations = if (pages.isNotEmpty() || showPageNavigator) ReaderInspector.entries
                        else listOf(ReaderInspector.CHAPTERS, ReaderInspector.SETTINGS)
                    MotionTabs(destinations.indexOf(inspector).coerceAtLeast(0), destinations.map { it.label }, onSelect = { index ->
                        val target = destinations[index]
                        if (target != inspector) when (target) {
                            ReaderInspector.CHAPTERS -> toggleReaderChapters()
                            ReaderInspector.PAGES -> openPageNavigator()
                            ReaderInspector.SETTINGS -> toggleReaderSettings()
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(3.dp), controlHeight = 38.dp,
                        tooltips = destinations.map { it.description })
                }
                Spacer(Modifier.height(6.dp))
                MotionTabContent(selected = inspector, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentKey = { it }, order = { it.ordinal }, soft = true) { target ->
                    when (target) {
                        ReaderInspector.CHAPTERS -> chaptersPanel(true)
                        ReaderInspector.PAGES -> pagePanel(true)
                        ReaderInspector.SETTINGS -> settingsPanel(true)
                    }
                }
            }
            BackHandler(active && inspectorOpen) {
                if (showSettings) closeReaderSettings()
                else if (showPageNavigator) closePageNavigator()
                else { showChapters = false; keyboardFocusRestore++ }
            }
        },
    ) {
    val cutout = WindowInsets.displayCutout
    Box(Modifier.fillMaxSize().then(keyboardModifier).then(mouseReveal).drawBehind {
        // A full page already paints its paper. Only gutters and the brief handoff expose this backing.
        if (sourceArrival.value < 1f || cutout.getLeft(this, layoutDirection) > 0 || cutout.getRight(this, layoutDirection) > 0) {
            drawRect(Color.Black.copy(alpha = dimPreview.value ?: preferences.dimAmount).compositeOver(background))
        }
    }) {
        // Exactly one viewport owns the page sample. Record its final pan, handoff and dim,
        // leaving chapter-edge controls, scrollbars and the guided-mode dock outside the layer.
        val artworkModifier = Modifier.mangaHazeSource(readerBackdrop).graphicsLayer {
            alpha = .90f + .10f * sourceArrival.value
            translationX = (1f - sourceArrival.value) * 6.dp.toPx()
        }.background(background).drawWithContent {
            drawContent()
            val dim = dimPreview.value ?: preferences.dimAmount
            if (dim > 0f) drawRect(Color.Black.copy(alpha = dim))
        }
        Box(Modifier.fillMaxSize().then(if (loading || error != null) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "显示或收起阅读工具",
                onClick = { controlsVisible = !controlsVisible },
            ) else Modifier)) {
            var preparationViewport: ReaderPrefetchViewport? = null
            if (loading || error != null || pages.isEmpty()) Box(Modifier.matchParentSize().then(artworkModifier))
            when {
                loading || error != null -> if (chapterWaitVisible) ReaderImageRecovery(
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
                pages.isNotEmpty() -> key(chapterId, sourceIdentity, isolatedPanelLayout,
                    manualSingles.takeIf { preferences.panelEngine != PanelEngine.OFF }) {
                    preparationViewport = if (preferences.panelEngine != PanelEngine.OFF) GuidedPanelReader(
                        pages = pages, headers = headers, resolvePage = resolvePage, retryPage = retryPage,
                        observePageTransfer = observePageTransfer,
                        sourceId = chapter.sourceId, prefetchRequests = sharedPrefetchRequests, prefetchFailures = sharedPrefetchFailures,
                        sourceName = currentSourceName.orEmpty(), sourceAlternatives = alternativeSources,
                        readySourceVariants = readySourceVariants, onPrefetchFailure = reportPrefetchFailure,
                        externalRetrySerial = currentPageRetrySerial,
                        onShowControls = { controlsVisible = true },
                        recoveryForeground = preferences.theme.foreground(),
                        onQuickSwitch = { failedIndex, variant -> repairs.select(failedIndex, variant) },
                        onRepairPage = openPageRepair,
                        onMoreSources = { anchor ->
                            sourceTriggerAnchor = anchor
                            sourceCloseRequested = false; sourceChapter = chapter; controlsVisible = true
                        },
                        // Do not consume the one-shot request before the view model owns this visible chapter.
                        nextChapterPages = if (experienceBudget.crossChapter && chapterVisibilityReported && nextChapter != null)
                            experienceBudget.nextChapterPages else 0,
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
                            !showSpreadAdjustment && !showComments && !pageNavigatorBlocking && !sourcePickerBlocking &&
                            repairState.selection == null && !chapterChangeRequested,
                        backdrop = readerBackdrop, artworkModifier = artworkModifier,
                        bottomClearancePx = if (controlsVisible) bottomControlsHeightPx else 0,
                    ) else ChapterPages(
                        artworkModifier = artworkModifier,
                        pages = pages, sourceId = chapter.sourceId, prefetchRequests = sharedPrefetchRequests, prefetchFailures = sharedPrefetchFailures,
                        headers = headers,
                        resolvePage = resolvePage,
                        retryPage = retryPage,
                        observePageTransfer = observePageTransfer,
                        sourceAlternatives = alternativeSources,
                        readySourceVariants = readySourceVariants, onPrefetchFailure = reportPrefetchFailure,
                        sourceName = currentSourceName.orEmpty(),
                        onShowControls = { controlsVisible = true },
                        retryTargetIndex = currentPageRetryIndex, retryPulse = currentPageRetrySerial,
                        // Do not consume the one-shot request before the view model owns this visible chapter.
                        nextChapterPages = if (experienceBudget.crossChapter && chapterVisibilityReported && nextChapter != null)
                            experienceBudget.nextChapterPages else 0,
                        onQuickSwitch = { failedIndex, variant -> repairs.select(failedIndex, variant) },
                        onRepairPage = openPageRepair,
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
                        keyboardRequest = keyboardRequest.takeIf { keyboardTurnOwned },
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
                            !showSpreadAdjustment && !showComments && !sourcePickerBlocking &&
                            repairState.selection == null && !chapterChangeRequested,
                        onAtEndChanged = { atChapterEnd = it },
                        onNext = openNextChapter,
                        onPrevious = openPreviousChapter,
                        startContent = { pull ->
                            ChapterEndFooter(pull, previousChapter?.title,
                                when {
                                    chaptersLoading -> "正在读取目录"
                                    chaptersError != null -> "目录暂未更新"
                                    chapterIndex < 0 -> "目录暂不可用"
                                    else -> "已到第一话"
                                }, colors.onSurface, openPreviousChapter,
                                onRetryChapters.takeIf { !chaptersLoading && (chaptersError != null || chapterIndex < 0) },
                                backdrop = readerBackdrop, previous = true)
                        },
                        endContent = { pull ->
                            if (!controlsVisible && (!immersive || pull.progress > 0f) &&
                                !showComments && !showSettings && !showChapters && !showSpreadAdjustment) {
                                ChapterEndFooter(
                                    pull = pull,
                                    nextTitle = nextChapter?.title,
                                    unavailableMessage = when {
                                        chaptersLoading -> "正在读取目录"
                                        chaptersError != null -> "目录暂未更新"
                                        chapterIndex < 0 -> "目录暂不可用"
                                        else -> "已到最后一话"
                                    },
                                    foreground = colors.onSurface, backdrop = readerBackdrop,
                                    onNext = openNextChapter,
                                    onRetry = onRetryChapters.takeIf { !chaptersLoading && (chaptersError != null || chapterIndex < 0) },
                                )
                            }

                        },
                    )
                }
            }

            // One primary-source preparation owner outlives either visual renderer.
            // The descriptor is returned synchronously from the selected branch above.
            val latestPreparationViewport by rememberUpdatedState(preparationViewport)
            LaunchedEffect(experience, readerForeground, leavingReader, loading, imageVisible, pages) {
                snapshotFlow {
                    val viewport = latestPreparationViewport
                    readerForeground && !leavingReader && (loading || viewport?.active == true &&
                        (viewport.foregroundPending || !imageVisible && !viewport.visibleRange().let { (first, last) ->
                            readerRangeSettled(pages, first, last, viewport.loaded, viewport.failedLoads)
                        }))
                }.collect { experience.setReaderWaiting(it) }
            }
            // Use the renderer's actual spread/window, including covers, wide artwork and
            // manual singles. The saved cursor alone describes only one half of a spread.
            val enhancementViewport by rememberUpdatedState(preparationViewport)
            val enhancementCursor by rememberUpdatedState(pageIndex)
            LaunchedEffect(enhancement, pages, preparationWindow.direction, readerForeground,
                leavingReader, chapterChangeRequested, enhancementPolicy,
                experienceBudget.enhancementAhead, experienceBudget.enhancementProtected) {
                snapshotFlow {
                    val viewport = enhancementViewport
                    val range = viewport?.visibleRange?.invoke()
                    val visible = range?.let { (first, last) ->
                        (first..last).filter { it in pages.indices }.toSet()
                    }.orEmpty()
                    // Share prefetch's established reading intent. A brief backward glance
                    // must not turn enhancement around while original preparation stays ahead.
                    val direction = if (preparationWindow.direction < 0) -1 else 1
                    val next = range?.let { (first, last) ->
                        readerDirectionalPages(first, last, pages.size, 1, direction,
                            viewport?.groups, completeSpreads = true).toSet()
                    }.orEmpty()
                    val ahead = range?.let { (first, last) ->
                        readerDirectionalPages(first, last, pages.size, experienceBudget.enhancementAhead,
                            direction, viewport?.groups, completeSpreads = true).toSet()
                    }.orEmpty()
                    ReaderEnhancementWindow(enhancementCursor.takeIf { it in visible }
                        ?: visible.minOrNull() ?: enhancementCursor, direction, visible, next, ahead)
                }.distinctUntilChanged().collect { window ->
                    enhancement.updateWindow(window.page, window.direction, pages.size,
                        readerForeground && !leavingReader && !chapterChangeRequested && window.visible.isNotEmpty(),
                        enhancementPolicy, experience.state.value, visiblePages = window.visible,
                        nextVisiblePages = window.next, aheadWindow = window.ahead)
                }
            }
            preparationViewport?.let { viewport ->
                AdjacentPagePrefetch(pages, headers, preferences.mode, viewport.composed, viewport.loaded,
                    sharedPrefetchRequests, prefetchPage, scriptPrefetch = scriptPrefetch,
                    enabled = viewport.active, foregroundActive = viewport.active, sourceId = chapter.sourceId,
                    viewportWidth = viewport.width, viewportHeight = viewport.height,
                    onPageFailure = viewport.failed, onPageRecovered = viewport.recovered,
                    onOriginalVerified = { index ->
                        if (sharedPrefetchFailures[index] == ReaderPrefetchFailure.Source)
                            sharedPrefetchFailures.remove(index)
                    },
                    onPageDimensions = viewport.dimensions, position = viewport.position, groups = viewport.groups,
                    scrollDirection = viewport.scrollDirection, directionAnchor = viewport.directionAnchor,
                    directionBaseline = viewport.directionBaseline, layoutKey = viewport.layoutKey,
                    retainOriginalsAcrossLayouts = true,
                    prepareEnhancementAhead = enhancementPolicy.tier != QualityTier.ORIGINAL &&
                        PreparationMode.SMART in enhancementPolicy.preparation,
                    failedLoads = viewport.failedLoads, download = activeDownload, visibleRange = viewport.visibleRange)
            }
        }

        AnimatedVisibility(visible = sourceLoading && sourceSwitchName.isNotBlank() || sourceError != null,
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = 60.dp),
            enter = fadeIn(tween(if (motionEnabled) MangaMotion.Quick else 0)),
            exit = fadeOut(tween(if (motionEnabled) MangaMotion.Quick else 0))) {
            LiquidGlassSurface(readerBackdrop, Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp),
                role = GlassSurfaceRole.POPOVER, cornerRadius = 18.dp, softCard = true) {
                Row(Modifier.padding(start = 13.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
        }

        if (commentsAvailable && !hideAmbientChrome && active && !leavingReader && atChapterEnd && !loading && error == null &&
            !showComments && !showSettings && !showChapters && !showSpreadAdjustment &&
            !showReaderActions && !readerActionsPresent && !pageNavigatorBlocking && !sourcePickerBlocking &&
            !readerStatusInspecting && !progressInteracting) {
            val bottom = if (controlsVisible) with(LocalDensity.current) {
                (bottomControlsHeightPx - dev.mangaflow.reader.readerNavigationInsets().getBottom(this)).coerceAtLeast(0).toDp()
            } else 0.dp
            val anchor = Modifier.align(Alignment.BottomStart)
                .windowInsetsPadding(dev.mangaflow.reader.readerNavigationInsets().union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            if (discussionState != null) ChapterEndComments(chapterId = chapterId, state = discussionState,
                backdrop = readerBackdrop, bottomClearance = bottom, touchExploration = touchExploration,
                onOpen = { showComments = true }, modifier = anchor)
            else PrivateChapterDiscussionButton(readerBackdrop, onOpen = { showComments = true },
                modifier = anchor.padding(bottom = bottom))
        }

        val statusAvailable = readerForeground && !leavingReader && !panelBlocksReading && !showPanelModels &&
            !showEnhancement && !compareEnhancement && !showSpreadAdjustment && !showComments && !pageNavigatorBlocking &&
            !sourcePickerBlocking && !chapterChangeRequested && !showReaderActions && !readerActionsPresent &&
            repairState.selection == null
        val statusButton: @Composable () -> Unit = {
            ReaderPrefetchButton(preparationTracker.state, enabled = readerToolsVisible && statusAvailable,
                modifier = Modifier.widthIn(min = 48.dp, max = (68.dp * readerFontScale).coerceAtMost(100.dp))
                    .focusRequester(statusControlFocus)) {
                restoreStatusFocus = false
                readerStatusOpen = true
            }
        }
        val toolsPresence = updateTransition(readerToolsVisible, label = "reader-tools")
        ReaderToolPresence(toolsPresence, top = true, modifier = Modifier.align(Alignment.TopCenter)) {
            LiquidGlassSurface(
                state = readerBackdrop,
                role = GlassSurfaceRole.NAVIGATION,
                cornerRadius = 28.dp,
                preferSource = true,
                trackPointer = true,
                interactionSource = topChromeInteraction,
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
                    ReaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回漫画详情", interactive = readerToolsVisible, onClick = leaveReader)
                    Column(Modifier.weight(1f).padding(start = 3.dp, end = 4.dp, top = 5.dp, bottom = 5.dp)) {
                        Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), color = colors.onSurface,
                            fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(chapter.group.takeIf { hasMultipleGroups }, chapter.title,
                            repairState.replacements[pageIndex]?.let {
                                "补页 · ${it.variant.sourceName} ${it.page.index + 1}"
                            },
                            currentSourceName.takeIf { chapter.variants.size > 1 },
                            clock.takeIf { it.isNotBlank() }).joinToString(" · "),
                            color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if ((!desktop || wideReaderChrome) && preferences.panelEngine == PanelEngine.OFF &&
                        (preferences.mode == ReadingMode.RIGHT_TO_LEFT || preferences.mode == ReadingMode.LEFT_TO_RIGHT)) {
                        TextButton(enabled = readerToolsVisible, onClick = {
                            cursor.scrollOffset = 0
                            updatePreferences(preferences.copy(doublePage = !preferences.doublePage))
                            keyboardFocusRestore++
                        }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                            Text(if (preferences.doublePage) "双页" else "单页", color = colors.onSurface, fontSize = 12.sp)
                        }
                    }
                    if (statusPlacement == ReaderStatusPlacement.HIDDEN && repairState.replacements.containsKey(pageIndex)) {
                        ReaderIcon(Icons.Rounded.Refresh, "恢复本页原图", interactive = readerToolsVisible,
                            onClick = { repairs.undo(pageIndex) })
                        ReaderIcon(Icons.Rounded.Tune, "调整本页备用来源与对齐", interactive = readerToolsVisible,
                            onClick = { openPageRepair(pageIndex) })
                    }
                    if (statusPlacement == ReaderStatusPlacement.TOP) statusButton()
                    if (wideReaderChrome) {
                        TextButton(onClick = ::toggleReaderChapters, enabled = readerToolsVisible,
                            contentPadding = PaddingValues(horizontal = 9.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("目录", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = ::openPageNavigator, enabled = readerToolsVisible && pages.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 9.dp)) {
                            Icon(Icons.Outlined.MyLocation, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("定位书页", color = colors.onSurface, style = MaterialTheme.typography.labelMedium)
                        }
                    } else if (desktop) ReaderIcon(Icons.AutoMirrored.Outlined.MenuBook,
                        if (showChapters) "收起章节目录" else "章节目录", interactive = readerToolsVisible,
                        emphasis = if (showChapters) 1f else .82f, onClick = ::toggleReaderChapters)
                    ReaderIcon(Icons.Rounded.AutoAwesome, "画质与性能", interactive = readerToolsVisible,
                        emphasis = if (enhancementState.currentEnhanced || showEnhancement) 1f else .82f,
                        onClick = { showEnhancement = true; controlsVisible = true })
                    ReaderIcon(Icons.Rounded.Tune, if (desktop) "阅读设置与快捷键" else "阅读设置",
                        interactive = readerToolsVisible, emphasis = if (showSettings) 1f else .82f,
                        onClick = ::toggleReaderSettings)
                    if (fullscreen != null) ReaderIcon(
                        if (immersive) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        if (immersive) "退出全屏（Esc）" else "沉浸全屏（F11）",
                        interactive = readerToolsVisible, onClick = { fullscreen.setActive(!immersive) },
                    )
                }
            }
        }

        ReaderToolPresence(toolsPresence, top = false, modifier = Modifier.align(Alignment.BottomCenter)
            .onSizeChanged { bottomControlsHeightPx = it.height }) {
            Box(Modifier.hoverable(bottomChromeInteraction, enabled = desktop)
                .then(if (desktop) Modifier.onFocusChanged { bottomChromeFocused = it.hasFocus }.focusGroup() else Modifier)
                .windowInsetsPadding(dev.mangaflow.reader.readerNavigationInsets().union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)
                .widthIn(max = readerDockWidth).fillMaxWidth()) {
                LiquidGlassSurface(
                    state = readerBackdrop,
                    role = GlassSurfaceRole.READING_DOCK,
                    preferSource = true,
                    trackPointer = true,
                    interactionSource = bottomChromeInteraction,
                    softCard = true,
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 28.dp,
                ) {
                    ReaderDockGroups(desktop, progress = {
                        if (pages.isNotEmpty()) key(chapterId) {
                            ReaderProgressSlider(
                                pageIndex = pageIndex, pages = pages, pendingPage = targetPage,
                                showSpreadRange = preferences.panelEngine == PanelEngine.OFF &&
                                    preferences.doublePage && preferences.mode != ReadingMode.VERTICAL,
                                enabled = readerToolsVisible,
                                onSeek = { destination ->
                                    if (destination != pageIndex) {
                                        returnLocation = ReaderReturnLocation(pageIndex, cursor.scrollOffset)
                                        targetOffset = 0
                                        targetPage = destination
                                    }
                                },
                                onOpenNavigator = ::openPageNavigator,
                                onInteractionChanged = { progressInteracting = it; if (!it) keyboardFocusRestore++ },
                                statusContent = if (statusPlacement == ReaderStatusPlacement.BOTTOM) statusButton else null,
                                sourceContent = {
                                    if (wideReaderChrome && preferences.panelEngine == PanelEngine.OFF) {
                                        val mayTurn = readerToolsVisible && keyboardTurnOwned && readingEligible
                                        ReaderPageStepButtons(
                                            forwardOnLeft = preferences.mode != ReadingMode.VERTICAL && preferences.swipeRightForNext(desktop = true),
                                            previousEnabled = mayTurn && (currentSpread?.first ?: pageIndex) > 0,
                                            nextEnabled = mayTurn && (currentSpread?.last ?: pageIndex) < pages.lastIndex,
                                        ) { action -> keyboardRequest = ReaderKeyboardRequest(++keyboardSerial, action) }
                                    }
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
                                            enabled = readerToolsVisible && active && !leavingReader,
                                            onOpen = {
                                                sourceCloseRequested = false
                                                sourceChapter = chapter
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        if (pages.isEmpty() && statusPlacement == ReaderStatusPlacement.BOTTOM) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { statusButton() }
                        }
                    }, actions = {
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
                                openPreviousChapter?.invoke()
                            }
                            val commentsLabel = when {
                                privateDiscussion != null -> "本话私有讨论"
                                commentsAvailable -> "本章评论"
                                else -> "本章暂未提供讨论"
                            }
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
                                (!inlineComments && pages.isNotEmpty() && commentsAvailable) ||
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
                                    if (desktop) Icons.Outlined.SkipPrevious else Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上一章",
                                    enabled = chapterIndex > 0, interactive = readerToolsVisible,
                                    buttonSize = 44.dp, onClick = openPrevious)
                                if (inlineCatalog) ReaderIcon(
                                    Icons.AutoMirrored.Outlined.MenuBook, "章节目录", interactive = readerToolsVisible,
                                    buttonSize = 44.dp, onClick = ::toggleReaderChapters)
                                if (inlineChapterNavigation) ReaderIcon(
                                    if (desktop) Icons.Outlined.SkipNext else Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下一章",
                                    enabled = openNextChapter != null && !chapterChangeRequested,
                                    interactive = readerToolsVisible, buttonSize = 44.dp,
                                    onClick = { openNextChapter?.invoke() })
                                Spacer(Modifier.weight(1f))
                                if (inlinePicture) ReaderIcon(
                                    pictureIcon, pictureLabel, enabled = currentPicture != null,
                                    interactive = readerToolsVisible, buttonSize = 44.dp, onClick = toggleCurrentPicture)
                                if (inlineComments) ReaderIcon(
                                    Icons.Outlined.Forum, commentsLabel, enabled = pages.isNotEmpty() && commentsAvailable,
                                    interactive = readerToolsVisible, buttonSize = 44.dp, onClick = { showComments = true })
                                key(chapterId) {
                                    Box {
                                        if (secondaryActions) ReaderIcon(Icons.Outlined.MoreHoriz, "其他阅读操作", interactive = readerToolsVisible,
                                            buttonSize = 44.dp) { showReaderActions = !showReaderActions }
                                        CompactGlassPopover(
                                            visible = secondaryActions && showReaderActions && readerToolsVisible && active && !leavingReader,
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
                                                enabled = showReaderActions && pages.isNotEmpty() && commentsAvailable) {
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
                                    interactive = readerToolsVisible && active && !leavingReader,
                                    onClick = saveChapter,
                                )
                            }
                        }
                    })
                }
            }
        }

        ReaderExperienceStatus(
            visible = statusAvailable,
            open = readerStatusOpen,
            onClose = { restoreFocus ->
                restoreStatusFocus = restoreFocus
                readerStatusOpen = false
            },
            sessionKey = sourceIdentity, store = experience,
            standbys = currentSourceStandbys, alternativeCount = alternativeSources.size, backdrop = readerBackdrop,
            preparationUpdates = preparationTracker.state,
            enhancement = enhancementState, enhancementPolicy = enhancementPolicy,
            enhancementSettings = enhancementCenter.settings, enhancementContent = enhancementContent,
            currentPage = pageIndex.takeIf { pages.isNotEmpty() && !loading && error == null },
            onOpenEnhancement = { showEnhancement = true; controlsVisible = true },
            chapterLoading = loading, chapterUnavailable = error != null,
            imageVisible = imageVisible, localChapter = localImport,
            downloadedChapter = activeDownload?.status == DownloadStatus.COMPLETED,
            repairedPages = repairState.replacements.size, repairBusy = repairState.busy, repairReason = repairState.reason,
            onAlignPage = if (alternativeSources.isNotEmpty() && pageIndex in pages.indices)
                ({ openPageRepair(pageIndex) }) else null,
            onUndoPage = repairState.replacements[pageIndex]?.let { { repairs.undo(pageIndex) } },
            onUndoSourceSwitch = onUndoSourceSwitch.takeIf { canUndoSourceSwitch },
            standbyAvailability = standbyAvailability.takeIf { it.workId == manga.id && it.chapterId == chapter.id }
                ?: dev.mangaflow.data.ReaderSourceStandbyAvailability(),
            nextChapterPreparation = nextChapterPreparation?.takeIf {
                it.workId == manga.id && it.currentChapterId == chapter.id && it.chapterId == nextChapter?.id
            },
            onInspectingChange = { readerStatusInspecting = it },
            onOpenSources = if (alternativeSources.isEmpty()) null else ({
                sourceTriggerAnchor = null
                sourceCloseRequested = false; sourceChapter = chapter; controlsVisible = true
            }),
            onRetryStandby = { variantId ->
                val failed = currentSourceStandbys.any { it.variantId == variantId &&
                    !it.manifestQueued && !it.manifestActive &&
                    (it.phase in setOf(ReaderSourceStandbyPhase.FAILED, ReaderSourceStandbyPhase.DEFERRED) ||
                        it.failedPages > 0 || it.deferredPages > 0) }
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
                        repairs.select(pageIndex, variant)
                }
            },
        )

        if (compareEnhancement && enhancementState.assets[pageIndex] != null) {
            LiquidGlassSurface(state = readerBackdrop, role = GlassSurfaceRole.READING_DOCK, preferSource = true, softCard = true,
                modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(readerNavigationInsets())
                    .padding(horizontal = 20.dp, vertical = 12.dp).widthIn(max = 400.dp).fillMaxWidth()) {
                ReaderComparisonControls(enhancementDivider) { comparisonTarget = null }
            }
        }
        AnimatedVisibility(
            visible = downloadMessage != null,
            modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(if (motionEnabled) MangaMotion.Content else 0)),
            exit = fadeOut(tween(if (motionEnabled) MangaMotion.Exit else 0)),
        ) {
            LiquidGlassSurface(readerBackdrop, Modifier.padding(32.dp), role = GlassSurfaceRole.POPOVER,
                cornerRadius = 16.dp, softCard = true) {
                Text(downloadMessage ?: displayedDownloadMessage, Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    color = colors.onSurface, fontSize = 13.sp)
            }
        }
    }
    }
    }
    if (!dockedPanels) pagePanel(false)
    if (!dockedPanels) settingsPanel(false)
    EnhancementCenterPanel(
        visible = showEnhancement, backdrop = readerBackdrop, initialContent = enhancementContent,
        sessionKey = enhancement.sessionKey, onDismiss = { showEnhancement = false },
        readerState = enhancementState, onRetryTask = enhancement::retryTask, onCancelTask = enhancement::cancelTask,
        onClosed = { keyboardFocusRestore++ },
        onCompare = enhancementState.assets[pageIndex]?.let { result ->
            val target = ReaderComparisonTarget(enhancement, pageIndex, result, enhancementPolicy)
            return@let {
                val (owner, index) = latestComparisonOwner
                val selected = enhancementCenter.settings.policy(enhancementContent, owner.sessionKey)
                if (target.matches(owner, index, owner.state.value.assets[index], selected)) {
                    comparisonTarget = target
                    enhancementDivider.floatValue = .5f
                    controlsVisible = false
                }
            }
        },
    )
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
    if (showComments) {
        if (discussionState != null) ChapterDiscussion(chapter, colors.background, colors.onSurface,
            onClose = { showComments = false }, state = discussionState)
        else if (privateDiscussion != null) {
            val name = chapter.variants.firstOrNull { it.sourceId == chapter.sourceId &&
                it.mangaId == chapter.sourceMangaId && it.chapterId == chapter.sourceChapterId }?.sourceName?.takeIf { it.isNotBlank() }
                ?: manga.sourceName.takeIf { chapter.sourceId == manga.sourceId && it.isNotBlank() }
                ?: sourceDisplayName(chapter.sourceId)
            PrivateChapterDiscussion(chapter.title, name, privateDiscussion, colors.background, colors.onSurface,
                onClose = { showComments = false })
        }
    }
}

private data class ReaderComparisonTarget(
    val owner: ReaderEnhancementController,
    val pageIndex: Int,
    val asset: ReaderEnhancedPage,
    val policy: EnhancementPolicy,
) {
    fun matches(owner: ReaderEnhancementController, pageIndex: Int, asset: ReaderEnhancedPage?,
        policy: EnhancementPolicy): Boolean = this.owner === owner && this.pageIndex == pageIndex &&
        asset != null && asset.identity == this.asset.identity &&
        asset.sourceCacheIdentity == this.asset.sourceCacheIdentity &&
        this.policy.tier == policy.tier && this.policy.scale == policy.scale &&
        this.policy.style == policy.style && this.policy.modelId == policy.modelId
}

/** Only this small control and the comparison drawing read the changing fraction. */
@Composable
private fun ReaderComparisonControls(divider: MutableFloatState, onClose: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("左侧原图", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            MotionSlider(divider.floatValue, { divider.floatValue = it },
                Modifier.weight(1f).padding(horizontal = 12.dp)
                    .semantics { contentDescription = "当前页原图与增强的分界位置" },
                valueRange = 0f..1f, formatValue = { "原图 ${(it * 100f).roundToInt()}%" })
            Text("右侧增强", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            ReaderIcon(Icons.Rounded.Close, "结束当前页比较", onClick = onClose)
        }
    }
}

@OptIn(FlowPreview::class, ExperimentalLayoutApi::class)
@Composable
private fun ChapterPages(
    pages: List<Page>,
    artworkModifier: Modifier,
    sourceId: String,
    prefetchRequests: MutableMap<Int, ReaderPagePrefetch>,
    prefetchFailures: MutableMap<Int, ReaderPrefetchFailure>,
    headers: Map<String, String>,
    resolvePage: ReaderPageResolver,
    retryPage: ReaderPageResolver,
    observePageTransfer: (Page) -> Flow<DownloadProgress?>,
    sourceAlternatives: List<ChapterVariant>,
    readySourceVariants: Map<String, ReaderSourceStandby>,
    onPrefetchFailure: (Page) -> Unit,
    sourceName: String,
    onShowControls: () -> Unit,
    retryTargetIndex: Int,
    retryPulse: Int,
    onQuickSwitch: (Int, ChapterVariant) -> Unit,
    onRepairPage: (Int) -> Unit,
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
    nextChapterPages: Int = 3,
    onTap: () -> Unit,
    active: Boolean,
    onAtEndChanged: (Boolean) -> Unit,
    onNext: (() -> Unit)?,
    onPrevious: (() -> Unit)?,
    startContent: @Composable (BottomPullLoadState) -> Unit,
    endContent: @Composable (BottomPullLoadState) -> Unit,
): ReaderPrefetchViewport {
    val save by rememberUpdatedState(onSave)
    val readingNavigation by rememberUpdatedState(onReadingNavigation)
    val location by rememberUpdatedState(onLocation)
    val visibleImageChanged by rememberUpdatedState(onVisibleImageChanged)
    val nearEnd by rememberUpdatedState(onNearEnd)
    val atEndChanged by rememberUpdatedState(onAtEndChanged)
    val currentPages by rememberUpdatedState(pages)
    val vertical = preferences.mode == ReadingMode.VERTICAL
    val layoutKey = remember(vertical, preferences.doublePage, preferences.coverAlone, manualSingles) { Any() }
    val loadedPages = remember(pages, headers, sourceId, layoutKey) { mutableStateMapOf<Int, String>() }
    val failedLoads = remember(pages, headers, sourceId, layoutKey) { mutableStateMapOf<Int, String>() }
    val composedPages = remember(pages, headers, sourceId, layoutKey) { mutableStateMapOf<Int, Boolean>() }
    val pageDimensions = remember(pages, headers, sourceId) { mutableStateMapOf<String, IntSize>() }
    val startPage = remember(layoutKey) { initialPage.coerceIn(0, pages.lastIndex) }
    val startOffset = remember(layoutKey) { initialOffset.coerceAtLeast(0) }
    val endRevealPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val previewHeightPx = with(LocalDensity.current) { LocalMangaWindow.current.height.roundToPx() }
    var contentWidthPx by remember { mutableIntStateOf(0) }
    val keyboardMotion = LocalMotionEnabled.current
    var prefetchViewport: ReaderPrefetchViewport? = null
    key(layoutKey) {
    if (vertical) {
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
        var verticalDirection by remember { mutableIntStateOf(1) }
        var verticalIntentAnchor by remember { mutableIntStateOf(startPage) }
        var verticalLayoutShift by remember { mutableStateOf(0L) }
        var directionBaseline by remember { mutableStateOf(0L to startPage.toLong()) }
        var navigationGeneration by remember { mutableStateOf(0L) }
        var directionSuppression by remember { mutableIntStateOf(0) }
        fun rebaseDirection() {
            verticalIntentAnchor = list.firstVisibleItemIndex.coerceIn(pages.indices)
            directionBaseline = (directionBaseline.first + 1L) to (verticalIntentAnchor.toLong() - verticalLayoutShift)
        }
        suspend fun withoutScrollDirection(rebase: Boolean = true, block: suspend () -> Unit) {
            val generation = navigationGeneration
            directionSuppression++
            var completed = false
            try { block(); completed = true } finally {
                if ((rebase || !completed) && generation == navigationGeneration) rebaseDirection()
                directionSuppression--
            }
        }
        fun recordNavigation(index: Int, offset: Int = 0) {
            navigationGeneration++
            verticalIntentAnchor = index
            val current = list.firstVisibleItemIndex
            verticalDirection = when {
                index > current || index == current && offset > list.firstVisibleItemScrollOffset -> 1
                index < current || index == current && offset < list.firstVisibleItemScrollOffset -> -1
                else -> verticalDirection
            }
        }
        LaunchedEffect(list) {
            var wasScrolling = false
            snapshotFlow {
                val direction = if (restoring || directionSuppression > 0 || !list.isScrollInProgress) 0
                    else if (list.lastScrolledForward) 1 else if (list.lastScrolledBackward) -1 else 0
                Triple(direction, list.firstVisibleItemIndex.coerceIn(pages.indices),
                    !restoring && directionSuppression == 0 && !list.isScrollInProgress)
            }.distinctUntilChanged().collect { (direction, anchor, idle) ->
                if (direction != 0) {
                    verticalDirection = direction
                    verticalIntentAnchor = anchor
                } else if (idle && wasScrolling) {
                    // Preserve the last real scroll sample if its final anchor and the
                    // transition to idle arrive together in one Compose snapshot.
                    val finalDirection = anchor.compareTo(verticalIntentAnchor)
                    if (finalDirection != 0) verticalDirection = finalDirection
                    verticalIntentAnchor = anchor
                } else if (idle && anchor != verticalIntentAnchor) {
                    // A layout correction translates coordinates; it is neither another
                    // reversed page nor a reason to forget a genuine one-page glance.
                    verticalLayoutShift += anchor.toLong() - verticalIntentAnchor
                    verticalIntentAnchor = anchor
                }
                wasScrolling = direction != 0
            }
        }
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
                !restoring && endLayoutReady() && loadedPages[pages.lastIndex] == pages.last().url && layout.visibleItemsInfo.any {
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
                withoutScrollDirection { list.scrollToItem(startPage, offset.coerceIn(0, targetHeight - 1)) }
                restoring = false
            }
        }
        LaunchedEffect(dragging) { if (dragging) {
            navigationGeneration++
            if (restoring || directionSuppression > 0) rebaseDirection()
            restoring = false; readingNavigation()
        } }
        LaunchedEffect(list, active, nextChapterPages) {
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
                    if (nextChapterPages > 0 && index.toLong() >= pages.lastIndex.toLong() - nextChapterPages.toLong()) nearEnd()
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
                    withoutScrollDirection {
                        list.stopScroll()
                        list.scrollToItem(old.second, offset)
                    }
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
                val index = it.coerceIn(0, pages.lastIndex)
                val offset = targetOffset.coerceAtLeast(0)
                recordNavigation(index, offset)
                withoutScrollDirection(rebase = false) { list.scrollToItem(index, offset) }
                onTargetConsumed()
            }
        }
        LaunchedEffect(keyboardRequest?.serial, active, restoring) {
            val request = keyboardRequest ?: return@LaunchedEffect
            if (!active || restoring) return@LaunchedEffect
            try {
                readingNavigation()
                suspend fun moveTo(index: Int) {
                    val target = index.coerceIn(0, pages.lastIndex)
                    recordNavigation(target)
                    withoutScrollDirection(rebase = false) {
                        if (keyboardMotion) list.animateScrollToItem(target)
                        else list.scrollToItem(target)
                    }
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
                    ReaderKeyboardAction.TOGGLE_CONTROLS, ReaderKeyboardAction.PAGE_NAVIGATOR -> Unit
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
        prefetchViewport = ReaderPrefetchViewport(
            scrollDirection = { verticalDirection },
            directionAnchor = { verticalIntentAnchor.toLong() - verticalLayoutShift },
            directionBaseline = { directionBaseline },
            position = {
                val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == list.firstVisibleItemIndex }
                list.firstVisibleItemIndex + list.firstVisibleItemScrollOffset.toFloat() / (item?.size ?: 1).coerceAtLeast(1)
            }, visibleRange = {
                val layout = list.layoutInfo
                val visible = layout.visibleItemsInfo.filter {
                    it.index in pages.indices && it.size > 0 &&
                        it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset
                }
                val anchor = list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex)
                (visible.firstOrNull()?.index ?: anchor) to (visible.lastOrNull()?.index ?: anchor)
            })
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .bottomPullLoad(pull).bottomPullLoad(previousPull)) {
            val desktop = platformKind == PlatformKind.DESKTOP
            LazyColumn(state = list, modifier = Modifier.matchParentSize().then(artworkModifier)
                .then(if (desktop) Modifier.padding(horizontal = 14.dp) else Modifier)
                .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = MangaLayout.ReaderPageWidth)
                .fillMaxSize().onSizeChanged { contentWidthPx = it.width },
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
                        onLoadFailed = { failed ->
                            if (currentPages.getOrNull(index)?.url == page.url) {
                                if (failed) failedLoads[index] = page.url else failedLoads.remove(index)
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
            if (desktop && active) MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = preferences.theme.foreground())) {
                DesktopVerticalScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 3.dp))
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
            groups.indexOfReaderPage(startPage).coerceAtLeast(0)
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
                    ReaderKeyboardAction.TOGGLE_CONTROLS, ReaderKeyboardAction.PAGE_NAVIGATOR -> pager.currentPage
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
        val spreadCameras = remember { mutableStateMapOf<Int, ZoomableState>() }
        val spreadPinches = remember { mutableStateMapOf<Int, ZoomableState>() }
        fun spreadZoomed(index: Int): Boolean = spreadPinches.containsKey(index) || spreadCameras[index]?.let {
            (it.zoomFraction ?: 0f) > .001f || it.isAnimationRunning
        } == true
        val pageLayoutDirection = LocalLayoutDirection.current
        val desktop = platformKind == PlatformKind.DESKTOP
        val swipeRightNext = preferences.swipeRightForNext(desktop)
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
            enabled = active && onNext != null && atEnd && endReady && !spreadZoomed(pager.currentPage),
            loading = false,
            canScrollForward = { !atEnd || !endReady },
            onLoadMore = { onNext?.invoke() },
            actionLabel = "进入下一话",
            orientation = Orientation.Horizontal,
            towardStart = swipeRightNext,
        )
        val mouseScope = rememberCoroutineScope()
        val mouseSwipeEnabled by rememberUpdatedState(!spreadZoomed(pager.currentPage))
        val onMouseSwipe by rememberUpdatedState<(Int, Boolean) -> Unit> { step, longSwipe ->
            if (!pager.isScrollInProgress && mouseSwipeEnabled) {
                val destination = pager.settledPage + step
                if (destination in groups.indices) {
                    readingNavigation()
                    mouseScope.launch {
                        if (keyboardMotion) pager.animateScrollToPage(destination) else pager.scrollToPage(destination)
                    }
                } else if (step > 0 && longSwipe && atEnd && endReady) {
                    onNext?.invoke()
                }
            }
        }
        val mouseSwipe = if (desktop && active) Modifier.pointerInput(pager, swipeRightNext) {
            // Foundation's pager already owns touch and trackpad scroll. Add primary-button drags.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed || !mouseSwipeEnabled)
                    return@awaitEachGesture
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    ?: return@awaitEachGesture
                var last = drag.position
                val released = horizontalDrag(drag.id) { change ->
                    last = change.position
                    change.consume()
                }
                val dx = last.x - down.position.x
                val distance = kotlin.math.abs(dx)
                if (released && distance >= 48.dp.toPx() && distance > kotlin.math.abs(last.y - down.position.y)) {
                    onMouseSwipe(if ((dx > 0f) == swipeRightNext) 1 else -1, distance >= 96.dp.toPx())
                }
            }
        } else Modifier
        LaunchedEffect(atEnd) { atEndChanged(atEnd) }
        LaunchedEffect(pager, groups) {
            snapshotFlow { groups.getOrNull(pager.currentPage)?.first }
                .distinctUntilChanged().collect { index -> if (index != null) location(index, 0) }
        }
        LaunchedEffect(pager, groups) {
            snapshotFlow { groups.getOrNull(pager.settledPage)?.first }
                .distinctUntilChanged().collect { index -> if (index != null) save(index, 0) }
        }
        LaunchedEffect(pager, groups, active, nextChapterPages) {
            snapshotFlow {
                groups.getOrNull(pager.currentPage)?.let { group ->
                    if (loadedPages[group.first] == pages[group.first].url ||
                        (group.second != null && loadedPages[group.second] == pages[group.second].url)) group.last
                    else -1
                } ?: -1
            }.distinctUntilChanged().collect { lastVisible ->
                visibleImageChanged(lastVisible >= 0)
                if (lastVisible >= 0) {
                    if (nextChapterPages > 0 && lastVisible.toLong() >= pages.lastIndex.toLong() - nextChapterPages.toLong()) nearEnd()
                }
            }
        }
        LaunchedEffect(targetPage, groups) {
            targetPage?.let { imageIndex ->
                readingNavigation()
                val groupIndex = groups.indexOfReaderPage(imageIndex.coerceIn(0, pages.lastIndex))
                if (groupIndex >= 0) pager.scrollToPage(groupIndex)
                onTargetConsumed()
            }
        }
        DisposableEffect(pager, groups) {
            onDispose {
                visibleImageChanged(false)
                groups.getOrNull(pager.settledPage)?.let { save(it.first, 0) }
            }
        }
        prefetchViewport = ReaderPrefetchViewport(groups = groups,
            // During a drag, currentPage is only the page nearest the snap point. Do not
            // change the long-window direction for a turn that the user hasn't settled.
            directionAnchor = { pager.settledPage.toLong() },
            position = { pager.currentPage + pager.currentPageOffsetFraction }, visibleRange = {
                val shown = groups.getOrNull(pager.currentPage) ?: groups.getOrNull(pager.settledPage)
                    ?: groups.getOrNull(groups.indexOfReaderPage(initialPage)) ?: groups[startingGroup]
                shown.first to shown.last
            })
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .then(mouseSwipe)
            .bottomPullLoad(pull)) {
            // Physical swipe direction is a user preference, not the OS layout direction.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize().then(artworkModifier).onSizeChanged { contentWidthPx = it.width },
                reverseLayout = swipeRightNext,
                userScrollEnabled = active && !spreadZoomed(pager.currentPage),
                // The separate bounded warmer owns offscreen previews; the pager composes only its viewport.
                beyondViewportPageCount = 0,
                key = { index -> "image:${groups[index].first}" },
            ) { index ->
                val group = groups.getOrNull(index)
                val replacements = LocalReaderPageRepair.current?.state?.replacements
                if (group != null) key(sourceId, headers, pages[group.first], group.second?.let { pages[it] },
                    replacements?.get(group.first), group.second?.let { replacements?.get(it) }) {
                    val second = group.second
                    val spreadZoom = rememberZoomableState()
                    var spreadViewport by remember { mutableStateOf(IntSize.Zero) }
                    val spreadSlots = remember { mutableStateMapOf<Int, IntRect>() }
                    if (second != null && spreadViewport.width > 0 && spreadViewport.height > 0) {
                        spreadZoom.contentScale = ContentScale.Fit
                        spreadZoom.contentAlignment = Alignment.Center
                        spreadZoom.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(
                            Size(spreadViewport.width.toFloat(), spreadViewport.height.toFloat())))
                    }
                    DisposableEffect(index, second, spreadZoom) {
                        if (second != null) spreadCameras[index] = spreadZoom
                        onDispose {
                            if (spreadCameras[index] === spreadZoom) spreadCameras.remove(index)
                            if (spreadPinches[index] === spreadZoom) spreadPinches.remove(index)
                        }
                    }
                    val renderImage: @Composable (Int, Modifier, Alignment) -> Unit = { imageIndex, modifier, alignment ->
                        Box(modifier.onGloballyPositioned { coordinates ->
                            if (second != null) {
                                val position = coordinates.positionInParent()
                                val x = position.x.roundToInt()
                                val y = position.y.roundToInt()
                                spreadSlots[imageIndex] = IntRect(x, y, x + coordinates.size.width, y + coordinates.size.height)
                            }
                        }) {
                            CompositionLocalProvider(LocalLayoutDirection provides pageLayoutDirection) {
                            ReaderPage(
                                pages[imageIndex], headers, sourceId = sourceId, resolvePage = resolvePage, retryPage = retryPage,
                                observePageTransfer = observePageTransfer, paged = true, pagedAlignment = alignment, onTap = onTap,
                                spreadViewport = if (second != null) ReaderSpreadViewport(spreadZoom, spreadViewport,
                                    spreadSlots[imageIndex] ?: IntRect.Zero) else null,
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
                                onLoadFailed = { failed ->
                                    val url = pages[imageIndex].url
                                    if (currentPages.getOrNull(imageIndex)?.url == url) {
                                        if (failed) failedLoads[imageIndex] = url else failedLoads.remove(imageIndex)
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
                    }
                    if (second != null) {
                        // Pair the image edges at the spine, leaving unused space outside the spread.
                        // Reading direction, rather than the system locale, selects the physical sides.
                        Row(
                            Modifier.fillMaxSize().clipToBounds().onSizeChanged { spreadViewport = it }
                                .pointerInput(spreadZoom, active) {
                                    if (active) awaitEachGesture {
                                        try {
                                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                            do {
                                                if (currentEvent.changes.count { it.pressed } >= 2) spreadPinches[index] = spreadZoom
                                                if (currentEvent.changes.none { it.pressed }) break
                                                awaitPointerEvent(PointerEventPass.Initial)
                                            } while (true)
                                        } finally {
                                            if (spreadPinches[index] === spreadZoom) spreadPinches.remove(index)
                                        }
                                    }
                                }
                                .zoomable(spreadZoom, enabled = active && pager.currentPage == index, onClick = { onTap() }),
                            horizontalArrangement = Arrangement.Absolute.Left,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val rightToLeft = preferences.mode == ReadingMode.RIGHT_TO_LEFT
                            val ordered = if (rightToLeft) listOf(second, group.first) else listOf(group.first, second)
                            ordered.forEachIndexed { slot, imageIndex ->
                                key(imageIndex) {
                                    renderImage(imageIndex, Modifier.weight(1f).fillMaxHeight(),
                                        if (slot == 0) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft)
                                }
                            }
                        }
                    } else {
                        renderImage(group.first, Modifier.fillMaxSize(), Alignment.Center)
                    }
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
    // Layout-specific state is returned, never published by an asynchronous effect.
    return checkNotNull(prefetchViewport).copy(
        composed = composedPages, loaded = loadedPages, failedLoads = failedLoads,
        width = { contentWidthPx }, height = previewHeightPx,
        active = active, layoutKey = layoutKey,
        failed = { index, failure ->
            prefetchFailures.recordPrefetchFailure(index, failure)
            onPrefetchFailure(pages[index])
        },
        recovered = { index -> prefetchFailures.remove(index) },
        dimensions = { index, size -> pageDimensions[pages[index].url] = size })
}

internal data class ReaderPrefetchViewport(
    val visibleRange: () -> Pair<Int, Int>, val position: () -> Float,
    val groups: List<PageSpread>? = null, val scrollDirection: (() -> Int)? = null,
    val directionAnchor: (() -> Long)? = null, val directionBaseline: (() -> Pair<Long, Long>)? = null,
    val composed: Map<Int, Boolean> = emptyMap(), val loaded: Map<Int, String> = emptyMap(),
    val failedLoads: Map<Int, String> = emptyMap(),
    val width: () -> Int = { 0 }, val height: Int = 0, val active: Boolean = false,
    val foregroundPending: Boolean = false, val layoutKey: Any? = null,
    val failed: (Int, ReaderPrefetchFailure) -> Unit = { _, _ -> }, val recovered: (Int) -> Unit = {},
    val dimensions: (Int, IntSize) -> Unit = { _, _ -> },
)

private fun readerRangeSettled(pages: List<Page>, first: Int, last: Int, loaded: Map<Int, String>,
    failed: Map<Int, String>): Boolean {
    // A visible image already lets the user read. If all visible loads have stopped
    // with errors, they no longer need to reserve the starting turn for other pages.
    val visible = maxOf(first, 0)..minOf(last, pages.lastIndex)
    return visible.any { loaded[it] == pages[it].url } ||
        !visible.isEmpty() && visible.all { failed[it] == pages[it].url }
}

private data class ReaderEnhancementWindow(
    val page: Int, val direction: Int, val visible: Set<Int>, val next: Set<Int>, val ahead: Set<Int>,
)

/** A live image request proves a materialized/download URL belongs to this exact original. */
private data class ReaderEnhancementSourceReceipt(val originalKey: String, val resolvedKey: String)
private val LocalReaderEnhancementSources =
    staticCompositionLocalOf<MutableMap<Int, ReaderEnhancementSourceReceipt>?> { null }

/** Original preparation and enhancement must agree on every page in a requested spread. */
private fun readerDirectionalPages(
    first: Int, last: Int, pageCount: Int, count: Int, step: Int,
    groups: List<PageSpread>?, completeSpreads: Boolean,
): List<Int> {
    if (count <= 0 || pageCount <= 0) return emptyList()
    val edge = if (step > 0) last else first
    if (edge !in 0 until pageCount) return emptyList()
    if (groups == null) {
        val available = if (step > 0) pageCount - 1 - edge else edge
        return (1..minOf(count, available)).map { edge + step * it }
    }
    val owner = groups.indexOfReaderPage(edge)
    if (owner < 0) return emptyList()
    val selected = mutableListOf<Int>()
    for (offset in 1..minOf(count, groups.size)) {
        if (selected.size >= count) break
        val spread = groups.getOrNull(owner + step * offset) ?: break
        val unit = listOfNotNull(spread.first, spread.second)
        if (!completeSpreads && selected.size + unit.size > count) break
        selected += unit
    }
    return selected
}

private data class ReaderSourceIdentity(val logicalId: String, val chapterId: String,
    val sourceId: String, val sourceMangaId: String)

private class ReaderCursor(var scrollOffset: Int = 0, var panelIndex: Int = 0)

// A byte-progress update does not change which store can supply an original.
private data class ReadingWindow(val visible: IntRange, val direction: Int)

private fun ReaderPreparationState.readingWindow() = ReadingWindow(firstVisible..lastVisible, direction)

private data class ReaderDownloadAvailability(val generation: String, val status: DownloadStatus,
    val completed: Int, val routeFallback: Boolean, val needsSourceRefresh: Boolean)

private data class PrefetchWindow(
    val first: Int, val last: Int, val composed: Set<Int>, val inFlight: Set<Int>, val enabled: Boolean,
    val visibleReady: Boolean, val ahead: Int, val behind: Int, val prefetchMode: PrefetchMode,
    val position: Float, val bytes: Long, val parallel: Int, val decoded: Int,
    val viewport: IntSize, val checking: Set<Int>, val resolving: Set<Int>, val resolved: Set<Int>,
    val resolveParallel: Int, val decodeParallel: Int, val decodeEnabled: Boolean, val foregroundActive: Boolean,
    val runtime: ExperienceRuntimePreferences,
    val scrollDirection: Int?, val directionAnchor: Long?, val directionBaseline: Pair<Long, Long>?,
    val groups: List<PageSpread>?, val layoutKey: Any?, val originalCacheRevision: Long, val diskWaitReason: String?,
    val prepareEnhancementAhead: Boolean, val download: ReaderDownloadAvailability?,
)

private data class PrefetchedReaderPage(val page: Page, val diskCacheKey: String?)

private fun Page.hasInspectableReaderOriginal(sourceId: String): Boolean {
    val source = sourceRouteId.ifBlank { sourceId }
    return url.startsWith("file:") || (source != "jmcomic" && !source.startsWith("js_") &&
        sourceImageToken.isBlank() && (url.startsWith("https:") || url.startsWith("http:")))
}

private data class PrefetchRecoveryKey(val bytes: Long, val parallel: Int, val resolve: Int,
    val decode: Int, val writable: Boolean, val foreground: Boolean)

/** One exact image request retains its body observer through promotion into the visible page. */
internal data class ReaderPagePrefetch(val request: Disposable, val transfer: ReaderTransferObserver, val work: PrefetchWork)

/** Failure to prepare a page is not evidence that its retained bytes are invalid. */
internal sealed interface ReaderPrefetchFailure {
    data object Source : ReaderPrefetchFailure
    data class CachedOriginal(val key: String) : ReaderPrefetchFailure
}

internal fun MutableMap<Int, ReaderPrefetchFailure>.recordPrefetchFailure(index: Int, failure: ReaderPrefetchFailure) {
    if (get(index) !is ReaderPrefetchFailure.CachedOriginal || failure is ReaderPrefetchFailure.CachedOriginal)
        put(index, failure)
}

internal class ReaderPrefetchCacheRecovery(var recovered: Boolean = false)

/** A download took ownership after initial resolution; only a local lookup may retry. */
private class ReaderPrefetchDownloadOwned : Exception("已有下载负责本页")

private data class LoadedReaderImage(val diskCacheKey: String?, val previewSize: IntSize, val requestedSize: IntSize,
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
    onPageFailure: (Int, ReaderPrefetchFailure) -> Unit = { _, _ -> },
    onPageRecovered: (Int) -> Unit = {},
    onOriginalVerified: (Int) -> Unit = {},
    position: (() -> Float)? = null, groups: List<PageSpread>? = null,
    scrollDirection: (() -> Int)? = null,
    directionAnchor: (() -> Long)? = null,
    directionBaseline: (() -> Pair<Long, Long>)? = null,
    layoutKey: Any? = null, retainOriginalsAcrossLayouts: Boolean = false,
    prepareEnhancementAhead: Boolean = false,
    failedLoads: Map<Int, String> = emptyMap(), download: DownloadEntry? = null,
    visibleRange: () -> Pair<Int, Int>,
) {
    val context = LocalContext.current
    val imageLoader = SingletonImageLoader.get(imagePlatformContext(context))
    val experience = remember(context) { ExperiencePolicyStore.get(context) }
    val tracker = LocalReaderPreparationTracker.current
    val enhancement = LocalReaderEnhancement.current
    val cacheRangeOwner = LocalReaderCacheRangeOwner.current
    val rangeStore = remember(context) { dev.mangaflow.storage.StorageCacheStore.get(context) }
    val budget by experience.state.collectAsStateWithLifecycle()
    val overrides by experience.overrides.collectAsStateWithLifecycle()
    val diskState by experience.prefetchDiskState.collectAsStateWithLifecycle()
    val currentBudget by rememberUpdatedState(budget)
    val currentOverrides by rememberUpdatedState(overrides)
    val visible by rememberUpdatedState(visibleRange)
    val currentPosition by rememberUpdatedState(position)
    val currentScrollDirection by rememberUpdatedState(scrollDirection)
    val currentDirectionAnchor by rememberUpdatedState(directionAnchor)
    val currentDirectionBaseline by rememberUpdatedState(directionBaseline)
    val dimensionsKnown by rememberUpdatedState(onPageDimensions)
    val pageFailed by rememberUpdatedState(onPageFailure)
    val pageRecovered by rememberUpdatedState(onPageRecovered)
    val originalVerified by rememberUpdatedState(onOriginalVerified)
    val currentViewportWidth by rememberUpdatedState(viewportWidth)
    val currentComposed by rememberUpdatedState(composedPages)
    val currentLoaded by rememberUpdatedState(loadedPages)
    val currentFailedLoads by rememberUpdatedState(failedLoads)
    val prefetchEnabled by rememberUpdatedState(enabled)
    val readerActive by rememberUpdatedState(foregroundActive)
    val currentGroups by rememberUpdatedState(groups)
    val currentHeight by rememberUpdatedState(viewportHeight)
    val currentLayout by rememberUpdatedState(layoutKey)
    val currentEnhancementAhead by rememberUpdatedState(prepareEnhancementAhead)
    val currentDownload by rememberUpdatedState(download?.let {
        ReaderDownloadAvailability(it.progress.generation, it.status, it.completedPages, it.routingError, it.needsSourceRefresh)
    })
    LaunchedEffect(pages, headers, if (retainOriginalsAcrossLayouts) null else mode, imageLoader,
        resolvePage, scriptPrefetch, sourceId, if (retainOriginalsAcrossLayouts) null else groups,
        if (retainOriginalsAcrossLayouts) null else viewportHeight,
        if (retainOriginalsAcrossLayouts) null else layoutKey, tracker, cacheRangeOwner, rangeStore, enhancement) {
        val owner = Any()
        val requestScope = this
        val resolvedAttempts = mutableSetOf<Int>()
        val resolvedPages = mutableStateMapOf<Int, Page>()
        val resolutionJobs = mutableStateMapOf<Int, Job>()
        val resolutionTokens = mutableMapOf<Int, PrefetchWork>()
        val waitingForDownload = mutableSetOf<Int>()
        var previousDownload = currentDownload
        var requestedDiskRecheck = false
        val originalFinished = mutableSetOf<Int>()
        var originalCacheRevision by mutableLongStateOf(0L)
        val retryAttempts = mutableMapOf<Pair<Int, PrefetchStage>, Long>()
        val retryAfter = mutableStateMapOf<Pair<Int, PrefetchStage>, Long>()
        val resourceDeferred = mutableSetOf<Pair<Int, PrefetchStage>>()
        val diskDeferred = mutableSetOf<Int>()
        // The concrete byte-window owner is the retry generation, including a promoted
        // request that finishes after a newer optional window has already been installed.
        val budgetDeferred = mutableMapOf<Int, PrefetchByteBudget>()
        val transientDeferred = mutableMapOf<Pair<Int, PrefetchStage>, PrefetchResourceBusy>()
        val capacityDeferred = mutableMapOf<Pair<Int, PrefetchStage>, PrefetchCapacityExceeded>()
        var recoveryKey: PrefetchRecoveryKey? = null
        val workTokens = mutableMapOf<Int, PrefetchWork>()
        val allTickets = mutableMapOf<Pair<Int, PrefetchStage>, Long>()
        val memoryKeys = mutableMapOf<Int, coil3.memory.MemoryCache.Key>()
        var inspectTargets = emptySet<Int>()
        var inspectVisible = emptySet<Int>()
        var nextPreviewPages = emptySet<Int>()
        val rewarmedNextPreviews = mutableSetOf<Int>()
        fun token(index: Int, stage: PrefetchStage): PrefetchWork {
            transientDeferred.remove(index to stage)
            capacityDeferred.remove(index to stage)
            if (stage == PrefetchStage.DOWNLOADING) {
                budgetDeferred.remove(index)
                diskDeferred.remove(index)
            }
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
        fun failed(index: Int, stage: PrefetchStage, failure: Throwable, windowBudget: PrefetchByteBudget? = null) {
            val key = index to stage
            val sourceFailure = isReaderPrefetchFailure(failure)
            transientDeferred.remove(key)
            capacityDeferred.remove(key)
            var diskQuotaDeferred = false
            var resourceCause: Throwable? = failure
            repeat(8) {
                (resourceCause as? PrefetchResourceBusy)?.let { transientDeferred[key] = it }
                (resourceCause as? PrefetchCapacityExceeded)?.let { capacityDeferred[key] = it }
                if (resourceCause is PrefetchDiskQuotaDeferred) diskQuotaDeferred = true
                resourceCause = resourceCause?.cause
            }
            if (stage == PrefetchStage.DOWNLOADING) {
                budgetDeferred.remove(index)
                if (diskQuotaDeferred) diskDeferred += index else diskDeferred.remove(index)
                var cause: Throwable? = failure
                repeat(8) {
                    if (cause is PrefetchBudgetExceeded && !diskQuotaDeferred &&
                        key !in capacityDeferred && windowBudget != null) budgetDeferred[index] = windowBudget
                    cause = cause?.cause
                }
            }
            val reason = readerPrefetchErrorLabel(failure)
            end(index, stage, if (sourceFailure) ReaderPreparationPhase.FAILED else ReaderPreparationPhase.DEFERRED, reason)
            if (sourceFailure) { resourceDeferred -= key; pageFailed(index, ReaderPrefetchFailure.Source) }
            else if (key in capacityDeferred || key in transientDeferred ||
                stage == PrefetchStage.DOWNLOADING && index in budgetDeferred) resourceDeferred -= key
            else resourceDeferred += key
            val attempts = (retryAttempts[key] ?: 0L) + if (sourceFailure) 1L else 0L
            if (sourceFailure) retryAttempts[key] = attempts
            // Local protection is not a source retry. It resumes only after a real budget change;
            // real failures have the user's finite extra attempts within this exact live window.
            val runtime = currentBudget.runtime
            if (diskQuotaDeferred) {
                retryAfter[key] = monotonicTimeMillis() + runtime.prefetchRetryBaseSeconds.coerceAtLeast(1).toLong() * 1_000L
            } else if (sourceFailure && attempts <= runtime.prefetchRetries.coerceAtLeast(0).toLong()) {
                val base = runtime.prefetchRetryBaseSeconds.coerceAtLeast(1).toLong()
                val maximum = runtime.prefetchRetryMaxSeconds.coerceAtLeast(1).toLong()
                val exponent = (attempts - 1L).coerceIn(0L, 31L).toInt()
                val scaled = (base * (1L shl exponent)).coerceAtMost(maximum)
                retryAfter[key] = monotonicTimeMillis() + scaled * 1000L
            } else retryAfter.remove(key)
        }
        val scriptRequests = mutableStateMapOf<Int, Job>()
        val checks = mutableStateMapOf<Int, Job>()
        val checkTokens = mutableMapOf<Int, PrefetchWork>()
        val retainedPages = mutableMapOf<Int, PrefetchedReaderPage>()
        val originalEpochs = mutableMapOf<Int, Int>()
        val originalStamps = mutableMapOf<Int, ReaderOriginalStamp>()
        val originalBytes = mutableMapOf<Int, Long>()
        val verifiedOriginals = mutableSetOf<Int>()
        // Some responses can be displayed but never retained. Repeat their local resolution
        // only once per live window, until we actually observe a reusable original again.
        val retriedMissingOriginals = mutableSetOf<Int>()
        val newlyAvailable = mutableSetOf<Int>()
        val inspected = mutableMapOf<Int, Int>()
        // An attempted cache-only check is not a claim that a preview remains resident.
        val previewChecks = mutableMapOf<Int, Pair<IntSize, Int>>()
        val deferredChecks = mutableStateMapOf<Int, Long>()
        // A whole window shares one retry timer, independent of the user's statistics interval.
        val retryWake = snapshotFlow {
            if (prefetchEnabled && readerActive) (retryAfter.values + deferredChecks.values).distinct().sorted()
            else emptyList()
        }.flatMapLatest { deadlines ->
            flow {
                var lastWake = Long.MIN_VALUE
                for (deadline in deadlines) {
                    if (deadline <= lastWake) continue
                    delay((deadline - monotonicTimeMillis()).coerceAtLeast(0L))
                    lastWake = monotonicTimeMillis()
                    emit(Unit)
                }
            }
        }.onStart { emit(Unit) }
        fun transfers(): Set<Int> = (requests.keys + scriptRequests.keys).toSet()
        fun transferring(index: Int) = index in requests || index in scriptRequests
        fun pending(): Set<Int> = transfers() + resolutionJobs.keys
        fun originalReady(index: Int, page: Page, diskKey: String?, bytes: Long? = null) {
            enhancement?.originalReady(index, page, headers, page.sourceRouteId.ifBlank { sourceId }, diskKey)
            cacheRangeOwner?.let { rangeStore.registerReadingResource(it, index, page, diskKey) }
            retainedPages[index] = PrefetchedReaderPage(page, diskKey)
            originalEpochs[index] = (originalEpochs[index] ?: 0) + 1
            originalStamps.remove(index)
            originalBytes.remove(index)
            verifiedOriginals.remove(index)
            bytes?.takeIf { it > 0L }?.let { originalBytes[index] = it }
            newlyAvailable.add(index)
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
        val readingIntent = ReaderPrefetchDirection()
        var previousLayout: Any? = currentLayout
        var previousPreviewViewport: IntSize? = null
        var observedBaseline: Pair<Any?, Long>? = null
        var intentEpoch = 0L
        var previousPosition = Float.NaN
        var previousTime = monotonicTimeMillis()
        var lastMove = previousTime
        var speed = 0f
        var direction = 1
        var budgetOwner: Set<Int> = emptySet()
        var budgetLimit = -1L
        var byteWindow = PrefetchByteBudget(0L)
        val accountedOriginals = mutableSetOf<Int>()
        fun accountCachedOriginal(index: Int, bytes: Long?) {
            if (bytes == null || index !in budgetOwner || index in accountedOriginals) return
            try { byteWindow.accept(bytes) }
            catch (_: PrefetchBudgetExceeded) { byteWindow.exhaust() }
            accountedOriginals += index
        }
        fun publishOriginalObservation(index: Int, artifact: ReaderPreparedArtifacts, started: Long) {
            // The visible renderer publishes its own exact source, including per-page repairs.
            if (index in inspectTargets && index !in inspectVisible) {
                tracker?.artifact(index, artifact.originalPresent, artifact.originalBytes, null,
                    freshnessMillis = currentBudget.runtime.cacheSampleSeconds.coerceAtLeast(1).toLong() * 2_000L,
                    owner = owner, startedAt = started)
            }
        }
        fun acceptCachedOriginal(index: Int, page: Page, diskKey: String, artifact: ReaderPreparedArtifacts) {
            if (transferring(index)) return
            val retained = retainedPages[index]
            if (retained == null || retained.page != page || retained.diskCacheKey != diskKey || index !in originalFinished) {
                originalReady(index, page, diskKey, artifact.originalBytes)
                originalFinished += index
                val key = index to PrefetchStage.DOWNLOADING
                retryAttempts.remove(key); retryAfter.remove(key); resourceDeferred.remove(key)
                budgetDeferred.remove(index); diskDeferred.remove(index); transientDeferred.remove(key); capacityDeferred.remove(key)
                tracker?.localOriginal(index, owner)
                originalCacheRevision++
            } else if (index !in verifiedOriginals || artifact.originalStamp != originalStamps[index]) {
                enhancement?.originalReady(index, page, headers, page.sourceRouteId.ifBlank { sourceId }, diskKey)
            }
            accountCachedOriginal(index, artifact.originalBytes)
            verifiedOriginals += index
            retriedMissingOriginals.remove(index)
            artifact.originalStamp?.let { originalStamps[index] = it }
            originalVerified(index)
        }
        val progressSampler = requestScope.launch {
            var cacheAt = 0L
            var previousScanMillis = 0L
            var lastTargets = emptySet<Int>()
            while (true) {
                if (!prefetchEnabled || !readerActive) {
                    lastTargets = emptySet()
                    snapshotFlow { prefetchEnabled && readerActive }.first { it }
                }
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
                val targets = inspectTargets - inspectVisible
                val fullScan = now - cacheAt >= currentBudget.runtime.cacheSampleSeconds.coerceAtLeast(1).toLong() * 1000L
                val scanTargets = if (fullScan) targets else (targets - lastTargets) + newlyAvailable.intersect(targets)
                lastTargets = targets
                if (scanTargets.isNotEmpty()) {
                    if (fullScan) cacheAt = now
                    newlyAvailable.removeAll(scanTargets)
                    val freshnessMillis = currentBudget.runtime.cacheSampleSeconds.coerceAtLeast(1).toLong() * 2_000L +
                        previousScanMillis * 2L
                    val scanLayout = currentLayout
                    val scanViewport = IntSize(currentViewportWidth(), currentHeight)
                    val scan = tracker?.beginObservationScan(owner, scanTargets.size)
                    var scanCompleted = false
                    try {
                        for (index in scanTargets) {
                            if (!prefetchEnabled || !readerActive) break
                            if (index !in inspectTargets || index in inspectVisible) continue
                            val retained = retainedPages[index]
                            val resolved = resolvedPages[index]
                            val epoch = originalEpochs[index]
                            val previewKey = memoryKeys[index]
                            val resource = retained?.page ?: (resolved ?: pages.getOrNull(index))
                                ?.takeIf { it.hasInspectableReaderOriginal(sourceId) } ?: continue
                            val started = monotonicTimeMillis()
                            val artifact = inspectReaderPreparedArtifacts(context, resource, headers, sourceId,
                                retained?.diskCacheKey, previewKey, previousOriginal = originalStamps[index])
                            if (!prefetchEnabled || !readerActive) break
                            tracker?.inspectedObservation(owner, scan)
                            if (index in inspectTargets && index !in inspectVisible && originalEpochs[index] == epoch &&
                                retainedPages[index] === retained && resolvedPages[index] == resolved) {
                                // The exact remote cache hit is useful before resolution. A miss may
                                // still have a downloaded/materialized alias; only resolved misses count.
                                val original = artifact.originalPresent.takeIf { it == true || retained != null ||
                                    resolved != null || resource.url.startsWith("file:") }
                                if (original == true) {
                                    val diskKey = retained?.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId)
                                    acceptCachedOriginal(index, resource, diskKey, artifact)
                                } else if (artifact.missingFile && retained != null && index in originalFinished &&
                                    index !in pending() && index !in checks && !pages[index].url.startsWith("file:")) {
                                    // A previous transfer is not proof that Coil or a materializer
                                    // still owns the bytes. Repeat the exact download lookup first.
                                    val retry = retriedMissingOriginals.add(index)
                                    retainedPages.remove(index)
                                    originalEpochs[index] = (epoch ?: 0) + 1
                                    verifiedOriginals.remove(index)
                                    originalBytes.remove(index)
                                    if (retry) {
                                        originalFinished.remove(index)
                                        resolvedAttempts.remove(index)
                                        resolvedPages.remove(index)
                                    }
                                    tracker?.missingOriginal(index, owner, retry)
                                    originalCacheRevision++
                                }
                                if (artifact.originalStamp != null) originalStamps[index] = artifact.originalStamp
                                else if (artifact.originalPresent == false) originalStamps.remove(index)
                                artifact.originalBytes?.let { originalBytes[index] = it }
                                val preview = artifact.previewResident.takeIf { scanLayout === currentLayout &&
                                    memoryKeys[index] == previewKey &&
                                    scanViewport == IntSize(currentViewportWidth(), currentHeight) }
                                // Rewarm an evicted next page from disk once per approach. Larger
                                // sets can exceed the LRU and continuously displace one another.
                                if (preview == false && original == true && index in nextPreviewPages &&
                                    index !in checks && index in previewChecks && rewarmedNextPreviews.add(index)) {
                                    previewChecks.remove(index)
                                    originalCacheRevision++
                                }
                                tracker?.artifact(index, original, artifact.originalBytes,
                                    preview, freshnessMillis = freshnessMillis, owner = owner, startedAt = started)
                            }
                        }
                        scanCompleted = prefetchEnabled && readerActive
                    } finally { tracker?.finishObservationScan(owner, scan, interrupted = !scanCompleted) }
                    if (fullScan) previousScanMillis = monotonicTimeMillis() - now
                }
                tracker?.sample()
                delay(250L)
            }
        }
        try {
            snapshotFlow {
                val (first, last) = visible()
                PrefetchWindow(first, last, currentComposed.keys.toSet(), transfers(), prefetchEnabled,
                    readerRangeSettled(pages, first, last, currentLoaded, currentFailedLoads),
                    currentBudget.prefetchAhead, currentBudget.prefetchBehind, currentOverrides.prefetchMode,
                    ((currentPosition?.invoke() ?: first.toFloat()) * 10f).roundToInt() / 10f,
                    currentBudget.adjacentPrefetchBytes, currentBudget.prefetchRequests,
                    currentBudget.decodedPrefetchPages, IntSize(currentViewportWidth(), currentHeight),
                    checks.keys.toSet(), resolutionJobs.keys.toSet(), resolvedPages.keys.toSet(),
                    currentBudget.resolveRequests, currentBudget.decodeRequests, experience.canDecodePrefetch(), readerActive,
                    currentBudget.runtime, currentScrollDirection?.invoke(), currentDirectionAnchor?.invoke(),
                    currentDirectionBaseline?.invoke(), currentGroups, currentLayout, originalCacheRevision, diskState.waitReason,
                    currentEnhancementAhead, currentDownload)
            }.distinctUntilChanged().combine(snapshotFlow {
                prefetchEnabled && (currentBudget.prefetchBytes > 0L ||
                    currentBudget.decodedPrefetchPages > 0 && experience.canDecodePrefetch())
            }.distinctUntilChanged().flatMapLatest { running -> flow {
                emit(monotonicTimeMillis())
                if (running) while (true) {
                    delay(currentBudget.runtime.cacheSampleSeconds.coerceAtLeast(1).toLong() * 1000L)
                    emit(monotonicTimeMillis())
                }
            } }) { window, _ -> window }.combine(retryWake) { window, _ -> window }.collectLatest { window ->
                val now = monotonicTimeMillis()
                if (previousLayout !== window.layoutKey || previousPreviewViewport != window.viewport) {
                    previousLayout = window.layoutKey
                    previousPreviewViewport = window.viewport
                    val retiredChecks = checks.values.toList()
                    checks.clear(); checkTokens.clear()
                    retiredChecks.forEach { it.cancel() }
                    val retiredCheckTickets = allTickets.filterKeys { it.second == PrefetchStage.CHECKING }.values.toSet()
                    allTickets.keys.filter { it.second == PrefetchStage.CHECKING }.toList().forEach { key ->
                        allTickets.remove(key)?.let { tracker?.finish(it) }
                    }
                    tracker?.invalidateLayoutPreviews(retiredCheckTickets)
                    memoryKeys.clear(); inspected.clear(); previewChecks.clear(); deferredChecks.clear()
                    rewarmedNextPreviews.clear()
                    previousPosition = Float.NaN; speed = 0f
                }
                val groups = window.groups
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
                // The long preparation window follows sustained reading intent, not a
                // one-page glance backwards. A spread is one navigation unit; panels
                // inside the same image never count as additional reversed pages.
                val logicalPage = window.directionAnchor ?: (groups?.indexOfReaderPage(window.first)
                    ?.takeIf { it >= 0 } ?: window.first).toLong()
                val baseline = window.layoutKey to (window.directionBaseline?.first ?: 0L)
                if (observedBaseline != baseline) { observedBaseline = baseline; intentEpoch++ }
                val intentDirection = readingIntent.update(logicalPage, window.scrollDirection, intentEpoch,
                    window.directionBaseline?.second ?: logicalPage)
                // Manual "待读" and "回看" have separate limits in chapter order. Looking
                // back must not exchange them (or cancel still-needed forward originals).
                val manual = window.prefetchMode == PrefetchMode.MANUAL
                val nextDirection = if (manual) 1 else intentDirection
                if (nextDirection != direction) speed = 0f
                direction = nextDirection
                // Do not cancel overlapping work merely for a direction change. The
                // desired-window filter below still cancels all genuinely departed work,
                // and budget shrink retains its independent cancellation/admission gate.
                if (previousPosition.isFinite() && window.position != previousPosition) {
                    val delta = window.position - previousPosition
                    val sample = kotlin.math.abs(delta) * 1000f / (now - previousTime).coerceAtLeast(100L)
                    speed = if (speed == 0f) sample else speed * .65f + sample * .35f
                    lastMove = now
                }
                if (window.position != previousPosition) previousTime = now
                previousPosition = window.position
                val seconds = window.runtime.predictionSeconds ?: when (currentBudget.mode) {
                    ExperienceMode.ECO -> 2; ExperienceMode.AUTO -> 5; ExperienceMode.PERFORMANCE -> 8 }
                val readyLead = maxOf(1, window.decoded, window.parallel)
                // Explicit advance enhancement needs the same original window. The original
                // cache, byte budget and admission owners still decide when work can start.
                val ahead = if (window.prepareEnhancementAhead || manual) window.ahead else
                    minOf(window.ahead.toLong(), if (now - lastMove > window.runtime.idleWindowSeconds.coerceAtLeast(1).toLong() * 1000L)
                        readyLead.toLong() else if (speed == 0f) readyLead.toLong() * 2L else
                        maxOf(readyLead.toLong(), kotlin.math.ceil(speed.toDouble() * seconds.toDouble()).toLong()))
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                fun candidates(count: Int, forward: Boolean): List<Int> {
                    val step = if (forward) direction else -direction
                    return readerDirectionalPages(window.first, window.last, pages.size, count,
                        step, groups, completeSpreads = window.prepareEnhancementAhead && forward)
                }
                fun eligible(index: Int) = window.enabled && index in pages.indices &&
                    index !in visiblePages &&
                    (pages[index].url.startsWith("file:") || pages[index].url.startsWith("https:") ||
                        pages[index].url.startsWith("http:") ||
                        sourceId.startsWith("js_") && pages[index].sourceImageToken.isNotBlank())
                // Cadence chooses new work, while observations follow the actual cursor and the
                // applied window size. A slow turn cannot discard verified overlapping originals.
                val aheadTargets = candidates(window.ahead, true).filter { it in pages.indices }
                val behindTargets = candidates(window.behind, false).filter { it in pages.indices }
                val networkDesired = if (window.bytes > 0L)
                    (candidates(ahead, true) + behindTargets).filter(::eligible).toSet() else emptySet()
                // One total preview count covers both sides; it does not double the
                // encoded/network window. Extra nearby pages are cache-only candidates.
                val previewTargets = if (window.enabled) readerNearbyPreviewPages(window.first, window.last,
                    pages.size, window.decoded, direction, groups).toSet() else emptySet()
                val hasSavedPages = window.enabled && (window.download?.completed ?: 0) > 0
                val memoryWindow = if (hasSavedPages) previewTargets else previewTargets.filter(::eligible).toSet()
                nextPreviewPages = if (memoryWindow.isEmpty()) emptySet() else readerDirectionalPages(
                    window.first, window.last, pages.size, 1, direction, groups, completeSpreads = true)
                    .filterTo(mutableSetOf()) { it in memoryWindow }
                rewarmedNextPreviews.retainAll(nextPreviewPages)
                // Near previews get their resolve/check turns before distant header inspections.
                val desired = memoryWindow + networkDesired
                // Saved pages live outside Coil. Their exact local owner can be read even
                // when the current policy permits neither another GET nor a warmed preview.
                val savedCandidates = if (hasSavedPages) aheadTargets + behindTargets else emptyList()
                val resolveDesired = desired + savedCandidates
                val writable = window.diskWaitReason == null && window.bytes > 0L
                val recovery = PrefetchRecoveryKey(window.bytes, window.parallel, window.resolveParallel,
                    window.decodeParallel, writable, window.foregroundActive)
                if (recoveryKey != null && recoveryKey != recovery) resourceDeferred.forEach { key ->
                    retryAfter[key] = now
                }
                recoveryKey = recovery
                if (window.download != previousDownload) {
                    val old = previousDownload
                    val download = window.download
                    // Completion count alone can advance in any order. Recheck only pages
                    // already waiting on this writer. A changed owner also retires unused URLs.
                    val ownerChanged = download == null || old == null || old.copy(completed = download.completed) != download
                    for (index in resolveDesired) {
                        if (transferring(index) || index in resolutionJobs) continue
                        if (index in waitingForDownload) {
                            retryAfter.remove(index to PrefetchStage.RESOLVING)
                            resolvedAttempts.remove(index)
                        } else if (ownerChanged && index !in retainedPages && resolvedPages.remove(index) != null) {
                            resolvedAttempts.remove(index)
                        }
                    }
                    previousDownload = download
                }
                // Admission can pause while a newly visible page is loading. That does not
                // remove the overlapping target window or its completed cache evidence.
                val observationWindow = (aheadTargets + behindTargets).toSet() + visiblePages
                inspectTargets = previewTargets + observationWindow
                inspectVisible = visiblePages
                // A remounted current image can temporarily be unready. Keep existing
                // original resolution and transfers within the same observation window;
                // cancelling their local lookup on every turn can starve the transfer queue.
                // A turn pauses new admissions without clearing targets, counters or jobs
                // whose original or preview is still inside the live window.
                val retainedOriginals = if (retainOriginalsAcrossLayouts && window.enabled &&
                    window.foregroundActive && window.bytes > 0L) pending().intersect(observationWindow) else emptySet()
                val running = desired + foreground + promoted + retainedOriginals + resolutionJobs.keys.intersect(resolveDesired)
                (pending() + checks.keys).filter { it !in running }.forEach(::stop)
                // Preview preparation can extend beyond the original-image window when
                // its independent decoded-page setting is larger. Keep that admitted work's
                // exact resources without inflating the original-readiness target count.
                val retained = observationWindow + previewTargets + desired + promoted
                cacheRangeOwner?.let { rangeStore.updateReadingRange(it, retained + window.composed) }
                resolvedAttempts.retainAll(retained)
                waitingForDownload.retainAll(retained)
                resolvedPages.keys.retainAll(retained)
                originalFinished.retainAll(retained)
                retainedPages.keys.retainAll(retained)
                originalEpochs.keys.retainAll(retained)
                originalStamps.keys.retainAll(retained)
                originalBytes.keys.retainAll(retained)
                verifiedOriginals.retainAll(retained)
                retriedMissingOriginals.retainAll(retained)
                newlyAvailable.retainAll(retained)
                memoryKeys.keys.retainAll(retained)
                inspected.keys.retainAll(retained)
                previewChecks.keys.retainAll(retained)
                deferredChecks.keys.retainAll(retained)
                retryAttempts.keys.removeAll { it.first !in retained }
                retryAfter.keys.removeAll { it.first !in retained }
                resourceDeferred.removeAll { it.first !in retained }
                diskDeferred.retainAll(retained)
                budgetDeferred.keys.retainAll(retained)
                transientDeferred.keys.removeAll { it.first !in retained }
                capacityDeferred.keys.removeAll { it.first !in retained }
                if (window.bytes < budgetLimit) {
                    transfers().filter { it !in foreground && it !in promoted }.forEach(::stop)
                }
                if ((networkDesired != budgetOwner || window.bytes != budgetLimit) &&
                    transfers().none { it !in foreground && it !in promoted }) {
                    budgetOwner = networkDesired
                    budgetLimit = window.bytes
                    byteWindow = PrefetchByteBudget(window.bytes)
                    accountedOriginals.clear()
                    for (index in networkDesired.filter { it in originalFinished && it in retainedPages }) {
                        accountCachedOriginal(index, originalBytes[index])
                    }
                }
                val waitingForOriginal = networkDesired.any {
                    it !in originalFinished || (it to PrefetchStage.DOWNLOADING) in retryAfter
                }
                val waitingForWrite = window.visibleReady && window.foregroundActive && window.parallel > 0 &&
                    window.diskWaitReason != null && byteWindow.remaining > 0L &&
                    networkDesired.any { index ->
                        !transferring(index) && index !in resolutionJobs && index !in waitingForDownload &&
                            resolvedPages[index]?.url?.startsWith("file:") == false &&
                            (index !in originalFinished || index in diskDeferred)
                    }
                if (window.diskWaitReason == null) requestedDiskRecheck = false
                else if (waitingForWrite && !requestedDiskRecheck) {
                    requestedDiskRecheck = true
                    experience.recheckPrefetchDiskQuota()
                }
                tracker?.window(owner, window.first, window.last, direction, aheadTargets, behindTargets,
                    networkDesired, memoryWindow, previewTargets, window.foregroundActive,
                    when { !window.enabled -> "阅读暂缓"; !window.visibleReady -> "当前页优先"
                        window.bytes <= 0L && currentOverrides.prefetchMiB == 0 -> "原图预读额度设为 0"
                        window.bytes <= 0L -> currentBudget.reason
                        waitingForWrite -> window.diskWaitReason
                        byteWindow.remaining <= 0L && waitingForOriginal ->
                            "原图预取额度已用尽；翻页或提高额度后继续"
                        else -> null })
                // A new window can have usable bytes without changing configured limits.
                // Retry only settled budget failures from an older owner; keep source-failure
                // retry limits, in-flight ownership and the new window's byte checks intact.
                budgetDeferred.forEach { (index, failedWindow) ->
                    if (failedWindow !== byteWindow && index in networkDesired && index !in pending() &&
                        writable && window.foregroundActive && byteWindow.remaining > 0L) {
                        val key = index to PrefetchStage.DOWNLOADING
                        retryAfter[key] = now
                    }
                }
                // Self-release cannot authorize another request. Each failed attempt waits
                // for a foreign release from a blocked pool plus full replay headroom; its
                // one scheduled retry is delayed and still passes normal window admission.
                for ((key, failure) in transientDeferred.toMap()) {
                    if (key.first in desired && key.first !in pending() && window.visibleReady &&
                        window.foregroundActive && writable && experience.canRetryPrefetchResource(failure)) {
                        transientDeferred.remove(key)
                        retryAfter[key] = maxOf(retryAfter[key] ?: 0L,
                            now + window.runtime.prefetchRetryBaseSeconds.coerceAtLeast(1).toLong() * 1_000L)
                    }
                }
                // Known empty-pool insufficiency is not restarted by a direction, parallelism
                // or arbitrary budget change. Keep the demand until a real attempt begins,
                // and recheck at dispatch so a cap shrink during the delay cannot bypass it.
                for ((key, failure) in capacityDeferred) {
                    if (key.first in desired && key.first !in pending() && key !in retryAfter &&
                        window.visibleReady && window.foregroundActive && writable &&
                        experience.canRetryPrefetchCapacity(failure)) {
                        retryAfter[key] = now + window.runtime.prefetchRetryBaseSeconds.coerceAtLeast(1).toLong() * 1_000L
                    }
                }
                for ((key, readyAt) in retryAfter.toMap()) {
                    val (index, stage) = key
                    val wanted = if (stage == PrefetchStage.RESOLVING) index in resolveDesired else index in desired
                    if (now < readyAt || !wanted || !window.visibleReady ||
                        !window.foregroundActive || stage == PrefetchStage.DOWNLOADING && !writable || index in pending()) continue
                    if (capacityDeferred[key]?.let { !experience.canRetryPrefetchCapacity(it) } == true) continue
                    when (stage) {
                        PrefetchStage.RESOLVING -> resolvedAttempts.remove(index)
                        PrefetchStage.DOWNLOADING -> originalFinished.remove(index)
                        else -> Unit
                    }
                    retryAfter.remove(key)
                }
                if (!window.visibleReady) return@collectLatest
                // Resolution owns a small independent queue and never suspends this window collector.
                for (index in resolveDesired) {
                    if (index in resolvedAttempts || index in resolutionJobs) continue
                    if (resolutionJobs.size >= window.resolveParallel) break
                    if (capacityDeferred[index to PrefetchStage.RESOLVING]?.let {
                            !experience.canRetryPrefetchCapacity(it)
                        } == true) continue
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
                                if (index !in verifiedOriginals && resource.hasInspectableReaderOriginal(sourceId)) {
                                    // Look up the exact saved/Coil original before publishing a URL
                                    // for transport. Otherwise a fast turn can join an unnecessary GET.
                                    val key = readerPageCacheKey(resource, headers, sourceId)
                                    val started = monotonicTimeMillis()
                                    val artifact = inspectReaderPreparedArtifacts(context, resource, headers, sourceId,
                                        key, previousOriginal = originalStamps[index])
                                    currentCoroutineContext().ensureActive()
                                    if (resolutionJobs[index] !== owned) return@launch
                                    if (artifact.originalPresent == true) {
                                        acceptCachedOriginal(index, resource, key, artifact)
                                    } else if (resource.url.startsWith("file:")) {
                                        // The decode stage can still identify a format that the
                                        // optional header reader could not verify. Never GET a file URL.
                                        originalReady(index, resource, key)
                                        originalFinished += index
                                    }
                                    publishOriginalObservation(index, artifact, started)
                                }
                                if (window.download != currentDownload && !resource.url.startsWith("file:") &&
                                    index !in retainedPages) return@launch
                                resolvedPages[index] = resource
                            }
                            end(index, PrefetchStage.RESOLVING, if (resource != null) ReaderPreparationPhase.COMPLETE
                                else ReaderPreparationPhase.DEFERRED, if (resource == null) "已有下载负责本页" else null)
                            val key = index to PrefetchStage.RESOLVING
                            if (resource == null) {
                                waitingForDownload += index
                                // This is only DownloadStore's local ownership lookup. Revisit it
                                // after the real download settles, without starting a duplicate.
                                retryAfter[key] = if (window.download != currentDownload) monotonicTimeMillis() else
                                    monotonicTimeMillis() + currentBudget.runtime.prefetchRetryBaseSeconds.coerceAtLeast(1).toLong() * 1000L
                            } else {
                                waitingForDownload.remove(index)
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
                    // A due retry may have waited for a request slot after clearing finished.
                    // Keep its demand authoritative until this actual dispatch, not just the timer.
                    if (capacityDeferred[index to PrefetchStage.DOWNLOADING]?.let {
                            !experience.canRetryPrefetchCapacity(it)
                        } == true) continue
                    if (transfers().size >= window.parallel) break
                    // An estimate cannot prove this page exceeds the remaining window.
                    // Transport admission and real body bytes bound the attempted transfer.
                    if (byteWindow.remaining <= 0L) break
                    val windowBytes = byteWindow
                    val token = token(index, PrefetchStage.DOWNLOADING)
                    if (sourceId.startsWith("js_") || sourceId == "jmcomic") {
                        val job = requestScope.launch(start = CoroutineStart.LAZY) {
                            val owned = currentCoroutineContext()[Job]
                            try {
                                val local = scriptPrefetch(pages[index], windowBytes, token)
                                currentCoroutineContext().ensureActive()
                                if (scriptRequests[index] !== owned) return@launch
                                if (local != null) {
                                    val cacheKey = readerPageCacheKey(local, headers, sourceId)
                                    val bytes = inspectReaderPreparedArtifacts(context, local, headers, sourceId, cacheKey).originalBytes
                                    currentCoroutineContext().ensureActive()
                                    if (scriptRequests[index] !== owned) return@launch
                                    originalReady(index, local, cacheKey, bytes)
                                    if (bytes != null) originalVerified(index)
                                    // Script/JM receipts already charged the actual transfer or cache hit.
                                    if (windowBytes === byteWindow) accountedOriginals += index
                                    // A recovered script/JM original is complete, not a pending
                                    // resource failure to restart when another budget changes.
                                    val key = index to PrefetchStage.DOWNLOADING
                                    retryAttempts.remove(key); retryAfter.remove(key); resourceDeferred.remove(key)
                                }
                                end(index, PrefetchStage.DOWNLOADING, if (local != null) ReaderPreparationPhase.COMPLETE
                                    else ReaderPreparationPhase.DEFERRED, if (local == null) "原图尚未准备" else null)
                                originalFinished += index
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: ReaderPrefetchDownloadOwned) {
                                if (scriptRequests[index] === owned) {
                                    // Retry enters scriptPrefetch's local ownership lookup first.
                                    // Never bypass the download's frozen route or count this as a
                                    // source failure; its record can finish or disappear later.
                                    end(index, PrefetchStage.DOWNLOADING, ReaderPreparationPhase.DEFERRED,
                                        "已有下载负责本页")
                                    originalFinished += index
                                    retryAfter[index to PrefetchStage.DOWNLOADING] = monotonicTimeMillis() +
                                        currentBudget.runtime.prefetchRetryBaseSeconds.coerceAtLeast(1).toLong() * 1000L
                                }
                            }
                            catch (pressure: OutOfMemoryError) {
                                if (scriptRequests[index] === owned) {
                                    failed(index, PrefetchStage.DOWNLOADING, pressure, windowBytes)
                                    originalFinished += index
                                }
                            }
                            catch (deferred: PrefetchBudgetExceeded) {
                                if (scriptRequests[index] === owned) {
                                    failed(index, PrefetchStage.DOWNLOADING, deferred, windowBytes)
                                    originalFinished += index
                                }
                            } catch (failure: Exception) {
                                if (scriptRequests[index] === owned) {
                                    failed(index, PrefetchStage.DOWNLOADING, failure, windowBytes)
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
                    // A canonical source may explicitly supply HTTP image URLs. Do not
                    // discard them after eligibility already admitted them; this does not
                    // downgrade an HTTPS URL or bypass the active source route.
                    if (!resource.url.startsWith("https:") && !resource.url.startsWith("http:")) {
                        end(index, PrefetchStage.DOWNLOADING, ReaderPreparationPhase.DEFERRED, "此来源需进入阅读后准备")
                        allTickets[index to PrefetchStage.DOWNLOADING]?.let { tracker?.finish(it) }
                        originalFinished += index
                        continue
                    }
                    val transfer = ReaderTransferObserver()
                    val request = imageLoader.enqueue(readerImageRequest(context, resource, headers, sourceId)
                        .readerTransferProgress(transfer)
                        .prefetchPriority(windowBytes, resource.sourceRouteId.ifBlank {
                            sourceId.ifBlank { experienceOrigin(resource.url) }
                        }, token)
                        .size(1).memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.ENABLED)
                        .decoderFactory(BlackholeDecoder.Factory()).build())
                    val ownedRequest = ReaderPagePrefetch(request, transfer, token)
                    requests[index] = ownedRequest
                    workTokens[index] = token
                    requestScope.launch {
                        try {
                            val result = request.job.await()
                            currentCoroutineContext().ensureActive()
                            if (requests[index] !== ownedRequest || index !in inspectTargets && !token.isForeground) return@launch
                            if (result is SuccessResult) {
                                val started = monotonicTimeMillis()
                                val diskKey = result.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId)
                                val artifact = inspectReaderPreparedArtifacts(context, resource, headers, sourceId,
                                    diskKey, previousOriginal = originalStamps[index])
                                currentCoroutineContext().ensureActive()
                                if (requests[index] !== ownedRequest || index !in inspectTargets && !token.isForeground) return@launch
                                originalReady(index, resource, diskKey,
                                    artifact.originalBytes ?: transfer.snapshot().receivedBytes.takeIf { it > 0L })
                                if (artifact.originalPresent == true) {
                                    verifiedOriginals += index
                                    retriedMissingOriginals.remove(index)
                                    artifact.originalStamp?.let { originalStamps[index] = it }
                                    originalVerified(index)
                                }
                                publishOriginalObservation(index, artifact, started)
                                if (result.dataSource != coil3.decode.DataSource.NETWORK) token.ifOptional {
                                    val cachedBytes = artifact.originalBytes
                                    if (cachedBytes != null) {
                                        try { windowBytes.accept(cachedBytes) }
                                        catch (_: PrefetchBudgetExceeded) { windowBytes.exhaust() }
                                    }
                                }
                                if (windowBytes === byteWindow) accountedOriginals += index
                                val key = index to PrefetchStage.DOWNLOADING
                                retryAttempts.remove(key); retryAfter.remove(key); resourceDeferred.remove(key)
                                end(index, PrefetchStage.DOWNLOADING, ReaderPreparationPhase.COMPLETE)
                            } else if (result is ErrorResult) {
                                failed(index, PrefetchStage.DOWNLOADING, result.throwable, windowBytes)
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
                // Inspect existing originals, then warm the exact preview when the decode
                // budget allows it. Unresolved network pages do not occupy decoder slots.
                for (index in desired) {
                    val retainedPage = retainedPages[index] ?: continue
                    val resource = retainedPage.page
                    val epoch = originalEpochs[index] ?: 0
                    val paired = groups?.let { it.getOrNull(it.indexOfReaderPage(index))?.second } != null
                    val size = readerPreviewSize(
                        if (paired) window.viewport.width / 2 else window.viewport.width,
                        window.viewport.height, maxWidthPx = window.runtime.previewWidthPx,
                        maxHeightPx = window.runtime.previewHeightPx)
                    val warmMemory = window.decodeEnabled && index in memoryWindow && window.viewport.width > 0
                    if (index in checks || (!warmMemory && inspected[index] == epoch) ||
                        (warmMemory && previewChecks[index] == (size to epoch)) ||
                        now < (deferredChecks[index] ?: 0L)) continue
                    if (checks.size >= window.decodeParallel) break
                    val token = token(index, PrefetchStage.CHECKING)
                    val check = requestScope.launch(start = CoroutineStart.LAZY) {
                        val owned = currentCoroutineContext()[Job]
                        try {
                            val result = preflightReaderPage(context, resource, headers, sourceId,
                                size.width, size.height, retainedPage.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId),
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
                            }, when (result.status) {
                                ReaderPagePreflightStatus.DEFERRED -> "等待可用解码资源"
                                ReaderPagePreflightStatus.FAILED -> result.failure?.let(::readerPrefetchErrorLabel)
                                    ?: "图片预读未完成"
                                else -> null
                            })
                            when (result.status) {
                                ReaderPagePreflightStatus.READY, ReaderPagePreflightStatus.UNVERIFIED,
                                ReaderPagePreflightStatus.FAILED -> {
                                    deferredChecks.remove(index)
                                    inspected[index] = epoch
                                    if (warmMemory) previewChecks[index] = size to epoch
                                    if (result.status == ReaderPagePreflightStatus.FAILED &&
                                        result.failure?.let(::isReaderPrefetchFailure) == true) {
                                        val rejected = result.diskCacheKey?.takeIf { resource.url.startsWith("https:") }
                                        pageFailed(index, rejected?.let { ReaderPrefetchFailure.CachedOriginal(it) }
                                            ?: ReaderPrefetchFailure.Source)
                                    }
                                }
                                ReaderPagePreflightStatus.DEFERRED -> deferredChecks[index] = monotonicTimeMillis() +
                                    currentBudget.runtime.prefetchRetryBaseSeconds.coerceAtLeast(1).toLong() * 1000L
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
    spreadViewport: ReaderSpreadViewport? = null,
    onTap: () -> Unit,
    onShowControls: () -> Unit,
    sourceName: String,
    pageCount: Int,
    foreground: Color,
    sourceAlternatives: List<ChapterVariant> = emptyList(),
    readySourceVariants: Map<String, ReaderSourceStandby> = emptyMap(),
    prefetchFailure: ReaderPrefetchFailure? = null,
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
    onLoadFailed: (Boolean) -> Unit = {},
) {
    val repair = LocalReaderPageRepair.current
    val repairController = repair?.controller
    val replacement = repair?.state?.replacements?.get(page.index)
    val effectivePage = replacement?.page ?: page
    val effectiveHeaders = if (effectivePage.sourceRouteId.isNotBlank()) emptyMap() else replacement?.headers ?: headers
    val effectiveSource = effectivePage.sourceRouteId.ifBlank { replacement?.variant?.sourceId ?: sourceId }
    // Stable owners keep recomposition from cancelling an existing request or transfer observer.
    val effectiveResolve: ReaderPageResolver = remember(page, repairController, replacement, resolvePage) {
        if (replacement == null || repairController == null)
            { input, bytes -> resolvePage(input, bytes).also { repairController?.primaryResource(page.index, page, it) } }
        else { _, bytes -> repairController.resolveReplacement(replacement, false, bytes) }
    }
    val effectiveRetry: ReaderPageResolver = remember(page, repairController, replacement, retryPage) {
        if (replacement == null || repairController == null)
            { input, bytes -> retryPage(input, bytes).also { repairController?.primaryResource(page.index, page, it) } }
        else { _, bytes -> repairController.resolveReplacement(replacement, true, bytes) }
    }
    val effectiveTransfer: (Page) -> Flow<DownloadProgress?> = remember(repairController, replacement, observePageTransfer) {
        if (replacement == null || repairController == null) observePageTransfer
        else { _ -> repairController.observeTransfer(replacement) }
    }
    DisposableEffect(page.index, repair?.controller, replacement) {
        val release = repair?.onRescue
        onDispose { release?.invoke(page.index, false) }
    }
    Box(if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        // The primary preparation ledger must never report a backup's bytes as primary cache hits.
        CompositionLocalProvider(LocalReaderPreparationTracker provides
            if (replacement == null) LocalReaderPreparationTracker.current else null) {
            ReaderPageImage(effectivePage, effectiveHeaders, effectiveSource, effectiveResolve, effectiveRetry,
                effectiveTransfer, paged, pagedAlignment, onTap, onShowControls,
                replacement?.variant?.sourceName ?: sourceName, pageCount, foreground,
                sourceAlternatives, readySourceVariants, prefetchFailure.takeIf { replacement == null },
                onQuickSwitch, onMoreSources, onRepairPage, externalRetrySerial,
                onLoadChanged = { ready ->
                    if (ready) {
                        repair?.onRescue?.invoke(page.index, false)
                        if (replacement == null) repair?.controller?.succeeded(page.index)
                    }
                    onLoadChanged(ready)
                },
                onHeightMeasured = onHeightMeasured, onComposed = onComposed,
                prefetch = prefetch.takeIf { replacement == null },
                knownDimensions = knownDimensions.takeIf { replacement == null },
                onDimensionsKnown = { dimensions -> if (replacement == null) onDimensionsKnown(dimensions) },
                deferResize = deferResize, visibleWindow = visibleWindow, isVisible = isVisible,
                onLayoutReady = onLayoutReady, onChapterSwipeReadyChanged = onChapterSwipeReadyChanged,
                onLoadFailed = onLoadFailed,
                logicalPageIndex = page.index, spreadViewport = spreadViewport,
                onSourceFailure = { failure ->
                    if (replacement == null && failure != null && isReaderPrefetchFailure(failure)) {
                        repair?.controller?.failed(page.index)
                        if (isVisible()) repair?.controller?.recover(page.index)
                    }
                    repair?.onRescue?.invoke(page.index, isVisible())
                },
                onSlowWaiting = { waiting -> repair?.onRescue?.invoke(page.index, waiting && isVisible()) },
            )
        }
        if (replacement != null && repair != null) {
            TextButton(onClick = { repair.controller.select(page.index, replacement.variant) },
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).heightIn(min = 44.dp)) {
                ChapterSourceLogo(replacement.variant.sourceId, replacement.variant.sourceName, Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("补 ${page.index + 1}", fontSize = 11.sp, color = foreground)
            }
        }
    }
}

// Keep this cache group out of the viewport's large Compose lambda. In the Windows
// package its inlined branch emitted an invalid JVM stack frame before any page I/O.
@Composable
private fun rememberReaderPageRequest(
    resource: Page?,
    headers: Map<String, String>,
    attempt: ReaderLoadAttempt,
    previewSize: IntSize,
    build: (Page) -> ImageRequest,
): ImageRequest? = remember(resource, headers, attempt, previewSize) {
    resource?.let(build)
}

@Composable
private fun ReaderPageImage(
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
    prefetchFailure: ReaderPrefetchFailure? = null,
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
    logicalPageIndex: Int = page.index,
    spreadViewport: ReaderSpreadViewport? = null,
    onLoadFailed: (Boolean) -> Unit,
    onSourceFailure: (Throwable?) -> Unit = {},
    onSlowWaiting: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val preparationTracker = LocalReaderPreparationTracker.current
    val enhancementPresentation = LocalReaderEnhancementPresentation.current
    val enhancementSources = LocalReaderEnhancementSources.current
    val imageRangeOwner = LocalReaderCacheRangeOwner.current
    val imageRangeStore = remember(context) { dev.mangaflow.storage.StorageCacheStore.get(context) }
    val experience = remember(context) { ExperiencePolicyStore.get(context) }
    val imageRuntime by remember(experience) { experience.state.map { it.runtime }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(initialValue = experience.state.value.runtime)
    val density = LocalDensity.current
    val screenHeight = LocalMangaWindow.current.height.coerceAtLeast(1.dp)
    // Request callbacks retain their own page/URL owner even if a later manifest replaces this slot.
    val loadChanged = onLoadChanged
    val loadFailed = onLoadFailed
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
    val sourceFailure by rememberUpdatedState(onSourceFailure)
    val slowWaiting by rememberUpdatedState(onSlowWaiting)
    LaunchedEffect(pageVisible) { if (!pageVisible) slowWaiting(false) }
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
    LaunchedEffect(enhancementPresentation?.controller, logicalPageIndex, reload) {
        if (reload > 0) enhancementPresentation?.controller?.invalidate(logicalPageIndex)
    }
    var fileRecoveryAttempted by remember(originalCacheIdentity) { mutableStateOf(false) }
    var failedMaterializedUrl by remember(originalCacheIdentity) { mutableStateOf<String?>(null) }
    // Latch an exact rejected original only when this page is visible. Optional network
    // failures cannot cancel a foreground load or bypass an already usable local copy.
    val rejectedCacheKey = (prefetchFailure as? ReaderPrefetchFailure.CachedOriginal)?.key
    var bypassPrefetchCache by remember(originalCacheIdentity) { mutableStateOf<String?>(null) }
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
    LaunchedEffect(attempt, pageVisible, loading, error) {
        loadFailed(pageVisible && !loading && error != null)
    }
    var slowLoading by remember(attempt) { mutableStateOf(false) }
    var recoveryStage by remember(attempt) { mutableStateOf(PrefetchStage.QUEUED) }
    LaunchedEffect(attempt, pageVisible, slowLoading) {
        if (pageVisible && slowLoading) slowWaiting(true)
    }
    var waitClock by remember(attempt) { mutableStateOf<TimeMark?>(null) }
    var resolvedPage by remember(attempt) { mutableStateOf<Page?>(null) }
    var loadedImage by remember(originalCacheIdentity, reload) { mutableStateOf<LoadedReaderImage?>(null) }
    val pageTransfer by rememberReaderPageTransfer(attempt, page, observePageTransfer,
        enabled = pageVisible && loading && error == null && resolvedPage == null)
    LaunchedEffect(attempt, pageVisible, handoffCaptured, loading) {
        if (!pageVisible || !handoffCaptured) return@LaunchedEffect
        if (!loading) {
            preparationTracker?.handoff(page.index, false)
            return@LaunchedEffect
        }
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
    DisposableEffect(attempt) {
        onDispose { attempt.active = false; loadFailed(false) }
    }
    LaunchedEffect(rejectedCacheKey, attempt, pageVisible, resolvedPage) {
        val resolved = resolvedPage
        if (rejectedCacheKey != null && pageVisible && attempt.acceptsResult && loadedImage == null &&
            bypassPrefetchCache == null && resolved != null && resolved.url.startsWith("https:") &&
            readerPageCacheKey(resolved, headers, sourceId) == rejectedCacheKey) {
            attempt.stopped = true
            bypassPrefetchCache = rejectedCacheKey
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
                val observed = if (waitingForPrefetch) attemptPrefetch?.work?.snapshot() else preparationWork.snapshot()
                recoveryStage = observed?.stage ?: PrefetchStage.QUEUED
                val latestByte = listOfNotNull(transport.lastProgressAt, inheritedProgressAt).maxOrNull()
                val idle = attempt.idleMillis(visibleSince, latestByte)
                val recovery = experience.state.value.recovery
                val waitLimit = recovery.imageTimeoutSeconds.coerceAtLeast(1).toLong() * 1000L
                slowLoading = idle >= recovery.slowLoadSeconds.coerceAtLeast(1).toLong() * 1000L
                if (idle >= waitLimit) {
                    attempt.stopped = true
                    // Stop only this page's captured optional Coil transfer. Cancellation is
                    // not completion: its owner retains the request and permits until it exits.
                    pendingPrefetch?.request?.dispose()
                    // Native mutex waits and image processing have separate stages; only
                    // admitted transport inactivity can count as a source timeout.
                    val sourceTimeout = readerTimeoutIsSource(observed, pageTransfer)
                    error = if (sourceTimeout) "本页连接超时" else "本页准备等待超时"
                    sourceFailure(if (sourceTimeout)
                        IllegalStateException("本页连接超时") else null)
                    preparationTicket?.let { preparationTracker?.end(page.index, it, preparationWork.snapshot().stage,
                        if (sourceTimeout) ReaderPreparationPhase.FAILED else ReaderPreparationPhase.DEFERRED,
                        if (sourceTimeout) "本页连接超时" else "等待可用资源超时") }
                    loading = false
                    loadChanged(false)
                    break
                }
                delay(minOf(1_000L, waitLimit - idle))
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
                originalCacheIdentity = originalCacheIdentity, runtime = experience.state.value.runtime)?.let {
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
                sourceFailure(failure)
                preparationTicket?.let { preparationTracker?.end(page.index, it, preparationWork.snapshot().stage,
                    if (isReaderPrefetchFailure(failure)) ReaderPreparationPhase.FAILED else ReaderPreparationPhase.DEFERRED,
                    readerPrefetchErrorLabel(failure)) }
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
    LaunchedEffect(attempt, pendingPrefetch, attempt.stopped, handoffCaptured) {
        if (!attempt.acceptsResult || !handoffCaptured) return@LaunchedEffect
        val oldPrefetch = pendingPrefetch
        if (oldPrefetch != null) {
            // An explicit retry owns a fresh request after this exact old transfer releases.
            if (reload > 0) oldPrefetch.request.dispose()
            try {
                // This suspends only this page's handoff; other prefetch jobs keep their own lanes.
                // Join completion, including cancellation cleanup, before permitting the
                // replacement image request. Never infer owner release from dispose().
                oldPrefetch.request.job.join()
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
        val previewSize = readerPreviewSize(viewportWidthPx, with(density) { screenHeight.roundToPx() },
            maxWidthPx = imageRuntime.previewWidthPx, maxHeightPx = imageRuntime.previewHeightPx)
        val currentPreviewSize by rememberUpdatedState(previewSize)
        val resource = resolvedPage
        val enhancementSource = remember(attempt, resource, loadedImage, originalCacheIdentity) {
            resource?.let { resolved -> loadedImage?.let { image ->
                ReaderEnhancementSourceReceipt(originalCacheIdentity,
                    image.diskCacheKey ?: readerPageCacheKey(resolved, headers, sourceId))
            } }
        }
        DisposableEffect(enhancementSources, logicalPageIndex, enhancementSource) {
            if (enhancementSource != null) enhancementSources?.set(logicalPageIndex, enhancementSource)
            onDispose {
                if (enhancementSource != null && enhancementSources?.get(logicalPageIndex) === enhancementSource)
                    enhancementSources?.remove(logicalPageIndex)
            }
        }
        val request = rememberReaderPageRequest(resource, headers, attempt, previewSize,
            build = @DontMemoize { requested ->
                val rejected = bypassPrefetchCache != null && requested.url.startsWith("https:") &&
                    readerPageCacheKey(requested, headers, sourceId) == bypassPrefetchCache
                readerImageRequest(context, requested, headers, sourceId)
                .readerTransferProgress(transferObserver)
                .readerPreparationWork(preparationWork)
                .apply {
                    // Retry a suspect cached original until a foreground network decode succeeds.
                    // Never evict a key that another visible request may already have replaced.
                    if (rejected && !prefetchCacheRecovery.recovered) {
                        diskCachePolicy(CachePolicy.WRITE_ONLY)
                        memoryCachePolicy(CachePolicy.DISABLED)
                    }
                }
                // The preview need only fit this viewport. Telephoto keeps the original
                // disk file for full-quality visible tiles and pinch zoom.
                .size(previewSize.width, previewSize.height)
                .scale(Scale.FIT)
                .crossfade(false)
                .listener(
                    onStart = {
                        if (attempt.acceptsResult && currentPreviewSize == previewSize && loadedImage == null) {
                            loading = true; error = null; loadChanged(false)
                        }
                    },
                    onSuccess = { _, result -> if (attempt.acceptsResult && currentPreviewSize == previewSize) {
                        val width = result.image.width
                        val height = result.image.height
                        if (width > 0 && height > 0) {
                            loadedImage = LoadedReaderImage(result.diskCacheKey, IntSize(width, height), previewSize,
                                result.memoryCacheKey)
                        }
                        loading = false
                        if (width > 0 && height > 0 && rejected &&
                            result.dataSource == coil3.decode.DataSource.NETWORK) prefetchCacheRecovery.recovered = true
                        loadChanged(true)
                    } },
                    onError = { _, result -> if (attempt.acceptsResult && currentPreviewSize == previewSize) {
                        loadChanged(false)
                        if (isReaderAdmissionDeferred(result.throwable)) {
                            // A resource wait must not trigger missing-file recovery or
                            // source failure. Keep the original; retry remains explicit.
                            loading = false
                            error = "本页资源等待超时，请重试"
                            preparationTicket?.let { preparationTracker?.end(page.index, it,
                                PrefetchStage.CHECKING, ReaderPreparationPhase.DEFERRED, error) }
                        } else if (requested.url.startsWith("file:") &&
                            (page.url.startsWith("https:") || page.sourceImageToken.isNotEmpty()) && !fileRecoveryAttempted) {
                            // Files can be removed by the download screen while this image is visible.
                            // Recover just this image from its retained source address.
                            fileRecoveryAttempted = true
                            failedMaterializedUrl = requested.url
                            loadedImage = null
                            reload++
                        } else {
                            loading = false
                            error = readerPrefetchErrorLabel(result.throwable)
                            sourceFailure(result.throwable)
                            preparationTicket?.let { ticket ->
                                preparationTracker?.end(page.index, ticket, preparationWork.snapshot().stage,
                                    if (isReaderPrefetchFailure(result.throwable)) ReaderPreparationPhase.FAILED
                                    else ReaderPreparationPhase.DEFERRED, error)
                            }
                        }
                    } },
                )
                .build()
            },
        )
        LaunchedEffect(resource, loadedImage, pageVisible, imageRangeOwner) {
            val image = loadedImage ?: return@LaunchedEffect
            if (resource != null) {
                enhancementPresentation?.controller?.originalReady(logicalPageIndex, resource, headers, sourceId,
                    image.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId))
                imageRangeOwner?.let { imageRangeStore.registerReadingResource(it, logicalPageIndex, resource,
                    image.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId)) }
                readerImageDimensions(SingletonImageLoader.get(imagePlatformContext(context)), resource,
                    image.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId),
                    originalPage = page.takeIf { reload == 0 || resource.url == page.url }, refresh = true,
                    originalCacheIdentity = originalCacheIdentity, runtime = experience.state.value.runtime)?.let { size ->
                    observedDimensions = size
                    dimensionsMeasured(size)
                }
            }
        }
        LaunchedEffect(attempt, resource, loadedImage, pageVisible, previewSize) {
            if (!pageVisible || resource == null) return@LaunchedEffect
            var originalStamp: ReaderOriginalStamp? = null
            while (attempt.acceptsResult) {
                val started = monotonicTimeMillis()
                val interval = experience.state.value.runtime.cacheSampleSeconds.coerceAtLeast(1).toLong() * 1000L
                val artifact = inspectReaderPreparedArtifacts(context, resource, headers, sourceId,
                    loadedImage?.diskCacheKey, loadedImage?.takeIf { it.requestedSize == previewSize }?.memoryCacheKey,
                    previousOriginal = originalStamp)
                originalStamp = artifact.originalStamp
                if (attempt.acceptsResult) {
                    preparationTracker?.artifact(page.index, artifact.originalPresent, artifact.originalBytes, artifact.previewResident,
                        freshnessMillis = (interval + monotonicTimeMillis() - started) * 2L, startedAt = started)
                    preparationTracker?.sample()
                }
                delay(interval)
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
        val enhancedAsset = enhancementPresentation?.state?.assets?.get(logicalPageIndex)?.takeIf { enhanced ->
            resource != null && enhanced.sourceCacheIdentity ==
                (loadedImage?.diskCacheKey ?: readerPageCacheKey(resource, headers, sourceId))
        }
        val comparison = enhancementPresentation?.takeIf { it.comparisonPageIndex == logicalPageIndex }
        Box(pageModifier.clipToBounds(), contentAlignment = Alignment.Center) {
            if (request != null && !awaitingPrefetch && imageError == null && (pageVisible || loadedImage != null)) key(reload) {
                if (paged) {
                    val imageState = rememberZoomableImageState()
                    LaunchedEffect(imageState, spreadViewport?.zoom) {
                        snapshotFlow {
                            imageState.isImageDisplayed &&
                                ((spreadViewport?.zoom ?: imageState.zoomableState).zoomFraction ?: 0f) <= .001f &&
                                !(spreadViewport?.zoom ?: imageState.zoomableState).isAnimationRunning
                        }.distinctUntilChanged().collect { chapterSwipeReadyChanged(it) }
                    }
                    DisposableEffect(imageState) { onDispose { chapterSwipeReadyChanged(false) } }
                    key(detailRetry) {
                        if (spreadViewport != null) PortableReaderZoomableImage(
                            model = request, contentDescription = "第 ${logicalPageIndex + 1} 页", modifier = Modifier.fillMaxSize(),
                            state = imageState, alignment = pagedAlignment, contentScale = ContentScale.Fit,
                            gesturesEnabled = false, onClick = null, spread = spreadViewport,
                        ) else ZoomableAsyncImage(
                            model = request,
                            state = imageState,
                            contentDescription = "第 ${logicalPageIndex + 1} 页",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            alignment = pagedAlignment,
                            // Until the preview arrives, the containing page owns the tap for chrome.
                            gesturesEnabled = !loading,
                            onClick = { onTap() },
                        )
                    }
                    ReaderEnhancedLayer(enhancedAsset, Modifier.matchParentSize(), pagedAlignment,
                        zoom = imageState.zoomableState, spread = spreadViewport,
                        comparisonFraction = comparison?.comparisonFraction,
                        onComparisonFractionChange = comparison?.onComparisonFractionChange)
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
                        contentDescription = "第 ${logicalPageIndex + 1} 页",
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
                    if (hasDimensions && originalSize != null && naturalHeightPx > previewSize.height * 2 &&
                        originalSize.readerAspectRatio() == aspect && loadedImage != null) {
                        key(resource?.url, detailRetry) {
                            VerticalReaderTiles(requireNotNull(resource), loadedImage?.diskCacheKey,
                                originalSize, viewportWidthPx, naturalHeightPx, visibleWindow,
                                onDetailStatus = { detailStatus = it })
                        }
                    }
                    ReaderEnhancedLayer(enhancedAsset, Modifier.matchParentSize(), Alignment.TopCenter,
                        visibleWindow = visibleWindow, comparisonFraction = comparison?.comparisonFraction,
                        onComparisonFractionChange = comparison?.onComparisonFractionChange)
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
            if (pageVisible && (imageError != null || pendingRecovery)) {
                var feedbackHeight by remember { mutableIntStateOf(0) }
                val feedbackPosition = if (paged) Modifier.align(Alignment.Center)
                else Modifier.align(Alignment.TopCenter).offset {
                    val window = visibleWindow()
                    val top = window?.top ?: 0
                    val height = window?.let { it.bottom - it.top } ?: naturalHeightPx
                    IntOffset(0, top + ((height - feedbackHeight) / 2).coerceAtLeast(0))
                }
                ReaderImageRecovery(
                    index = logicalPageIndex, pageCount = pageCount, sourceName = sourceName,
                    failure = imageError.orEmpty(), pending = pendingRecovery, slow = slowLoading,
                    transferProgress = pageTransfer ?: transportProgress.takeIf { it.receivedBytes > 0L }?.let {
                        DownloadProgress(phase = dev.mangaflow.storage.DownloadPhase.TRANSFERRING,
                            receivedBytes = it.receivedBytes, expectedBytes = it.expectedBytes)
                    },
                    stage = when {
                        prefetchFailure != null && transportProgress.receivedBytes == 0L -> "预读未完成 · 正在载入本页"
                        awaitingPrefetch && reload > 0 -> "正在结束本页旧请求"
                        awaitingPrefetch -> "接续本页预读"
                        recoveryStage == PrefetchStage.CHECKING -> "解码本页图片"
                        recoveryStage == PrefetchStage.RESOLVING -> "解析本页并检查本机缓存"
                        resource == null -> "检查本机与已有下载"
                        resource.url.startsWith("file:") -> "解码本机图片"
                        else -> "请求图片"
                    },
                    attempt = attempt, startedAt = waitClock ?: attempt.startedAt, foreground = foreground,
                    modifier = feedbackPosition.onSizeChanged { feedbackHeight = it.height },
                    alternatives = sourceAlternatives,
                    readySourceVariants = readySourceVariants,
                    onRetry = { attempt.stopped = true; reload++ }, onSwitch = onQuickSwitch, onMore = onMoreSources,
                    onRepairPage = onRepairPage,
                    onControls = onShowControls,
                    onStop = {
                        attempt.stopped = true; error = "已停止等待"; loading = false; loadChanged(false)
                        pendingPrefetch?.request?.dispose()
                    },
                )
            }
        }
    }
}

private enum class ReaderInspector(val label: String, val description: String) {
    CHAPTERS("章节", "章节目录"), PAGES("书页", "定位书页"), SETTINGS("设置", "阅读设置"),
}

@Composable
private fun ReaderPageStepButtons(forwardOnLeft: Boolean, previousEnabled: Boolean, nextEnabled: Boolean,
    onTurn: (ReaderKeyboardAction) -> Unit) {
    val step: @Composable (Boolean, ImageVector) -> Unit = { forward, icon ->
        ReaderIcon(icon, if (forward) "下一页" else "上一页", enabled = if (forward) nextEnabled else previousEnabled,
            buttonSize = 36.dp) { onTurn(if (forward) ReaderKeyboardAction.NEXT_PAGE else ReaderKeyboardAction.PREVIOUS_PAGE) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        step(forwardOnLeft, Icons.AutoMirrored.Rounded.KeyboardArrowLeft)
        step(!forwardOnLeft, Icons.AutoMirrored.Rounded.KeyboardArrowRight)
    }
}

private data class ReaderReturnLocation(val pageIndex: Int, val scrollOffset: Int)

@Composable
private fun ReaderPageNavigatorDialog(
    visible: Boolean,
    embedded: Boolean,
    focusRequest: Int,
    pages: List<Page>,
    headers: Map<String, String>,
    sourceId: String,
    resolvePage: ReaderPageResolver,
    currentPage: Int,
    pageInformation: @Composable () -> Unit,
    collectedPages: Set<Int>,
    onToggleCollection: (Int) -> Unit,
    backdrop: HazeState,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val desktop = platformKind == PlatformKind.DESKTOP
    val panel: @Composable () -> Unit = {
        ReaderDialogSurface(
            if (embedded) Modifier.fillMaxSize().motionInputGate(visible)
            else Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = LocalMangaWindow.current.panelHeight),
            backdrop, dialogWindow = !embedded,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                ReaderPanelHeading(if (desktop) "书页" else "页导航", "关闭页导航", onDismiss)
                val grid = rememberLazyGridState(initialFirstVisibleItemIndex =
                    if (desktop && pages.isNotEmpty()) currentPage.coerceIn(pages.indices) else 0)
                if (desktop && pages.isNotEmpty()) {
                    var input by remember(pages, visible) { mutableStateOf("") }
                    val destination = input.toIntOrNull()?.takeIf { it in 1..pages.size }?.minus(1)
                    val numberFocus = remember { FocusRequester() }
                    val navigationScope = rememberCoroutineScope()
                    LaunchedEffect(visible, focusRequest) {
                        if (visible) {
                            withFrameNanos { }
                            numberFocus.requestFocus()
                        }
                    }
                    val go: () -> Unit = {
                        if (visible && destination != null) {
                            onSelect(destination)
                            if (embedded) navigationScope.launch { grid.scrollToItem(destination) }
                            input = ""
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = input, onValueChange = { value ->
                            if (value.length <= pages.size.toString().length && value.all { it in '0'..'9' }) input = value
                        }, modifier = Modifier.weight(1f).focusRequester(numberFocus).onPreviewKeyEvent { event ->
                            if (event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                if (event.type == KeyEventType.KeyDown) go()
                                true
                            } else false
                        }, singleLine = true, label = { Text("跳到页码") },
                            placeholder = { Text("${currentPage + 1} / ${pages.size}") },
                            textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(12.dp))
                        TextButton(onClick = go, enabled = visible && destination != null) { Text("前往") }
                        ReaderIcon(Icons.Outlined.MyLocation, "在缩略图中定位当前页", enabled = visible,
                            buttonSize = 38.dp) { navigationScope.launch { grid.scrollToItem(currentPage.coerceIn(pages.indices)) } }
                    }
                }
                pageInformation()
                Box(Modifier.fillMaxWidth().weight(1f, fill = embedded)) {
                    LazyVerticalGrid(
                        state = grid,
                        columns = GridCells.Adaptive(76.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(end = if (desktop) 8.dp else 0.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp),
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
                            val colors = MaterialTheme.colorScheme
                            val pointer = if (desktop) remember(page) { MutableInteractionSource() } else null
                            val hovered = pointer?.collectIsHoveredAsState()?.value == true
                            var focused by remember(page) { mutableStateOf(false) }
                            val cell = if (pointer != null) Modifier.hoverable(pointer)
                                .onFocusChanged { focused = it.hasFocus }.focusGroup() else Modifier
                            Column(cell.clip(RoundedCornerShape(8.dp))
                                .then(if (desktop && selected) Modifier.background(colors.primary.copy(alpha = .10f)) else Modifier)
                                .selectable(selected, enabled = visible, role = Role.RadioButton, onClick = { onSelect(page.index) })
                                .then(if (desktop) Modifier.padding(3.dp) else Modifier)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(.7f)
                                    .clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant)) {
                                    AsyncImage(
                                        model = request,
                                        contentDescription = "第 ${page.index + 1} 页",
                                        contentScale = if (desktop) ContentScale.Fit else ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (!desktop || selected || hovered || focused || page.index in collectedPages) {
                                        MotionIconButton(onClick = { onToggleCollection(page.index) }, enabled = visible,
                                            modifier = Modifier.align(Alignment.TopEnd).size(44.dp)) {
                                            Box(Modifier.size(25.dp).background(colors.surface, CircleShape),
                                                contentAlignment = Alignment.Center) {
                                                Icon(if (page.index in collectedPages) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                    if (page.index in collectedPages) "取消这一页的收藏" else "单独收藏这一页",
                                                    Modifier.size(14.dp), tint = colors.onSurface)
                                            }
                                        }
                                    }
                                }
                                Row(Modifier.padding(start = 2.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    if (selected) Spacer(Modifier.size(12.dp, 3.dp).background(colors.primary, CircleShape))
                                    Text("${page.index + 1}", fontFamily = mangaFontFamily(MangaFontScene.NUMBERS),
                                        fontSize = 13.sp, lineHeight = 17.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) colors.onSurface else colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (desktop) Box(Modifier.matchParentSize()) {
                        DesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }
    if (embedded) panel() else MotionDialog(visible = visible, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) { panel() }
}

@Composable
private fun ReaderProgressSlider(
    pageIndex: Int,
    pages: List<Page>,
    pendingPage: Int?,
    showSpreadRange: Boolean,
    enabled: Boolean,
    onSeek: (Int) -> Unit,
    onOpenNavigator: () -> Unit,
    onInteractionChanged: (Boolean) -> Unit,
    sourceContent: (@Composable () -> Unit)? = null,
    statusContent: (@Composable () -> Unit)? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val dragged by interactions.collectIsDraggedAsState()
    val interactionChanged by rememberUpdatedState(onInteractionChanged)
    val feedback = rememberFeelFeedback()
    var preview by remember { mutableStateOf<Float?>(null) }
    val pageCount = pages.size
    val lastPage = (pageCount - 1).coerceAtLeast(0)
    val shown = (preview ?: pendingPage?.toFloat() ?: pageIndex.toFloat()).coerceIn(0f, lastPage.toFloat())
    val enhancement = LocalReaderEnhancementPresentation.current
    val spread = enhancement?.state?.visiblePages?.takeIf {
        showSpreadRange && it.size == 2 && pageIndex in it && enhancement.state.currentPage == pageIndex &&
            preview == null && pendingPage == null
    }
    val pageLabel = if (spread != null) "${spread.min() + 1}–${spread.max() + 1}" else "${shown.roundToInt() + 1}"
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelLarge.copy(fontFamily = mangaFontFamily(MangaFontScene.NUMBERS),
        fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val totalStyle = SpanStyle(color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Normal)
    val reserve = "8".repeat(pageCount.toString().length)
    val reserveLabel = buildAnnotatedString {
        append(if (showSpreadRange) "$reserve–$reserve" else reserve)
        withStyle(totalStyle) { append(" / $reserve") }
    }
    val measurer = rememberTextMeasurer()
    val labelPixels = remember(measurer, reserveLabel, labelStyle) {
        measurer.measure(reserveLabel, labelStyle, maxLines = 1).size.width
    }
    val labelWidth = (with(LocalDensity.current) { labelPixels.toDp() } + 12.dp)
        .coerceIn(48.dp, (LocalMangaWindow.current.width * .35f).coerceAtLeast(48.dp))

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

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        sourceContent?.invoke()
        ReaderSeekBar(
            value = shown,
            onValueChange = { preview = it },
            onValueChangeFinished = {
                preview?.let {
                    val destination = it.roundToInt().coerceIn(0, lastPage)
                    if (destination != (pendingPage ?: pageIndex)) feedback.perform(FeelEvent.Selection)
                    onSeek(destination)
                }
                preview = null
            },
            valueRange = 0f..lastPage.coerceAtLeast(1).toFloat(),
            modifier = Modifier.weight(1f).padding(end = 4.dp),
            enabled = enabled && pageCount > 1,
            interactions = interactions,
            pageCount = pageCount,
            label = "阅读进度，第 ${shown.roundToInt() + 1} 页，共 $pageCount 页",
        )
        Box(Modifier.width(labelWidth)
            .heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled && pageCount > 0, role = Role.Button,
                onClickLabel = "打开页面导航", onClick = onOpenNavigator)
            .semantics { contentDescription = "第 $pageLabel 页，共 $pageCount 页" }
            .padding(horizontal = 4.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center) {
            Text(buildAnnotatedString {
                append(pageLabel)
                withStyle(totalStyle) { append(" / $pageCount") }
            }, color = colors.onSurface,
                style = labelStyle, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        statusContent?.invoke()
    }
}

@Composable
private fun ReaderPageResolutionInfo(pages: List<Page>, headers: Map<String, String>, sourceId: String,
    pageIndex: Int, showSpread: Boolean, enhancementEnabled: Boolean, enhancement: ReaderEnhancementState,
    replacements: Map<Int, ReaderPageReplacement>, enhancementSources: Map<Int, ReaderEnhancementSourceReceipt>) {
    if (pageIndex !in pages.indices) return
    val shownIndices = enhancement.visiblePages.takeIf {
        showSpread && it.size == 2 && pageIndex in it && enhancement.currentPage == pageIndex
    }?.sorted() ?: listOf(pageIndex)
    val dimensionRevision by ReaderDimensionCache.revision.collectAsStateWithLifecycle()
    val sourceReceipts = shownIndices.associateWith { enhancementSources[it] }
    val resolutions = remember(shownIndices, pages, headers, sourceId, dimensionRevision,
        enhancement.assets, enhancementEnabled, replacements, sourceReceipts) {
        shownIndices.map { index ->
            val replacement = replacements[index]
            val page = replacement?.page ?: pages.getOrNull(index)
            val ownedHeaders = if (page?.sourceRouteId?.isNotBlank() == true) emptyMap()
                else replacement?.headers ?: headers
            val ownedSource = page?.sourceRouteId?.ifBlank { replacement?.variant?.sourceId ?: sourceId } ?: sourceId
            val originalKey = page?.let { readerPageCacheKey(it, ownedHeaders, ownedSource) }
            val resolvedKey = sourceReceipts[index]?.takeIf { it.originalKey == originalKey }?.resolvedKey ?: originalKey
            val originalSize = resolvedKey?.let(ReaderDimensionCache::get)
                ?: originalKey?.let(ReaderDimensionCache::get)
                ?: page?.takeIf { it.width > 0 && it.height > 0 }?.let { IntSize(it.width, it.height) }
            val asset = enhancement.assets[index]?.takeIf {
                // A matching source version alone cannot prove a URL alias. Use the same
                // resolved cache identity as the live image, including repaired pages.
                enhancementEnabled && page != null && it.identity.pageId == index.toString() &&
                    it.identity.sourceId == ownedSource && it.identity.sourceVersion == readerEnhancementSourceVersion(page) &&
                    it.sourceCacheIdentity == resolvedKey
            }
            index to readerEnhancementResolution(index, originalSize, asset)
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        resolutions.forEach { (index, resolution) ->
            val value = when {
                resolution.enhanced && resolution.originalLabel != null -> "原图 ${resolution.originalLabel} · ${resolution.label}"
                resolution.valueLabel == "—" -> "尺寸待核实"
                else -> resolution.label
            }
            Text("第 ${index + 1} 页 · $value", modifier = Modifier.semantics { contentDescription = resolution.description },
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    backdrop: HazeState,
    manga: Manga,
    library: LibraryStore,
    chapters: List<Chapter>,
    currentId: String,
    downloadStore: DownloadStore,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    embedded: Boolean = false,
    query: String,
    onQuery: (String) -> Unit,
    focusRequest: Int,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val desktop = platformKind == PlatformKind.DESKTOP
    val android = platformKind == PlatformKind.ANDROID
    val groups = remember(chapters) { chapters.map(Chapter::group).distinct() }
    val positions = rememberChapterPositions(manga, library, active = visible)
    val currentGroup = remember(chapters, currentId) { chapters.firstOrNull { it.id == currentId }?.group }
    val catalogBusy = rememberDelayedBusy(visible && loading)
    var searchExpanded by rememberSaveable(manga.id) { mutableStateOf(false) }
    var mobileFindRequest by rememberSaveable(manga.id) { mutableIntStateOf(0) }
    val searchVisible = desktop || android && searchExpanded
    val search = query.trim().takeIf { searchVisible }.orEmpty()
    val searching = search.isNotEmpty()
    val results = rememberMatchingChapters(chapters, search)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val panel: @Composable () -> Unit = {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val stackHeading = android && (LocalMangaWindow.current.width < 390.dp || fontScale > 1.2f)
        var selectedGroup by rememberSaveable(manga.id, currentId) { mutableStateOf<String?>(null) }
        var locateCurrent by remember(manga.id, currentId) { mutableStateOf(false) }
        val group = selectedGroup?.takeIf { it in groups } ?: currentGroup ?: groups.firstOrNull().orEmpty()
        val visibleCount = remember(chapters, group) { chapters.count { it.group == group } }
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
                    val countLabel = when {
                        android && searching -> "找到 ${results.size} 章"
                        searching -> "${results.size} 章"
                        else -> "$visibleCount 章"
                    }
                    if (stackHeading) Column(Modifier.weight(1f).padding(end = 4.dp)) {
                        Text("章节", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        Text(countLabel, color = colors.onSurfaceVariant, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text("章节", style = MaterialTheme.typography.titleLarge)
                        Text(mangaNumberText(countLabel), Modifier.weight(1f).padding(start = 8.dp),
                            color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!android || catalogBusy) Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (catalogBusy) CircularProgressIndicator(Modifier.size(13.dp)
                            .semantics { contentDescription = "正在补全章节目录" }, color = colors.onSurfaceVariant, strokeWidth = 1.5.dp)
                    }
                    if (android && (chapters.isNotEmpty() || searchExpanded)) {
                        val searchLabel = if (searchExpanded) "收起章节搜索" else "查找章节"
                        ReaderIcon(if (searchExpanded) Icons.Rounded.ExpandLess else Icons.Outlined.Search,
                            searchLabel, interactive = visible) {
                            if (searchExpanded) {
                                keyboard?.hide()
                                focus.clearFocus(force = true)
                                onQuery("")
                                searchExpanded = false
                            } else {
                                searchExpanded = true
                                mobileFindRequest++
                            }
                        }
                    }
                    ReaderIcon(Icons.Outlined.MyLocation, "定位当前话", enabled = currentGroup != null) {
                        if (android && searchExpanded) {
                            keyboard?.hide()
                            focus.clearFocus(force = true)
                            searchExpanded = false
                        }
                        onQuery("")
                        selectedGroup = currentGroup
                        locateCurrent = true
                    }
                    ReaderIcon(Icons.Rounded.Refresh, "刷新目录", enabled = !loading, onClick = onRetry)
                    ReaderIcon(Icons.Rounded.Close, "关闭目录", onClick = onDismiss)
                }
                if (searchVisible) ChapterSearchField(
                    query, onQuery, if (android) mobileFindRequest else focusRequest, visible,
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onOpenMatch = if (desktop) results.singleOrNull()?.let { match -> { onSelect(match.id) } } else null,
                    showKeyboardOnOpen = android,
                )
                if (groups.size > 1 && !searching) MotionTabs(
                    selected = groups.indexOf(group), labels = groups.map(::chapterGroupLabel), onSelect = {
                        locateCurrent = false
                        selectedGroup = groups[it]
                    },
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
                    val groupChapters = remember(chapters, targetGroup) {
                        chapters.filter { it.group == targetGroup }.sortedBy(Chapter::index)
                    }
                    val targetChapters = if (searching) results else groupChapters
                    val listResults = android && searching
                    val initialIndex = remember(groupChapters, currentId) { groupChapters.indexOfFirst { it.id == currentId } }
                    val catalogPosition = rememberLazyGridState(
                        initialFirstVisibleItemIndex = (initialIndex - 6).coerceAtLeast(0))
                    val searchPosition = if (desktop || android) rememberLazyGridState() else catalogPosition
                    val state = if (searching) searchPosition else catalogPosition
                    var positioned by rememberSaveable { mutableStateOf(initialIndex >= 0) }
                    var appliedSearch by rememberSaveable { mutableStateOf("") }
                    val currentIndex by rememberUpdatedState(initialIndex)
                    LaunchedEffect(active, locateCurrent, searching) {
                        if (!active) {
                            state.stopScroll()
                        } else if (!searching && locateCurrent && currentIndex >= 0) {
                            positioned = true
                            state.requestScrollToItem((currentIndex - 6).coerceAtLeast(0))
                            locateCurrent = false
                        } else if (!searching && !positioned) {
                            // Locate a chapter that arrives late only while the reader is still at the top.
                            // After this first placement the keyed grid owns its scroll anchor.
                            val (moved, index) = snapshotFlow {
                                val moved = state.isScrollInProgress || state.firstVisibleItemIndex != 0 ||
                                    state.firstVisibleItemScrollOffset != 0
                                moved to currentIndex
                            }.first { (moved, index) -> moved || index >= 0 }
                            positioned = true
                            if (moved) {
                                if (selectedGroup == null) selectedGroup = targetGroup
                            } else state.requestScrollToItem((index - 6).coerceAtLeast(0))
                        }
                    }
                    LaunchedEffect(active, search) {
                        if (active && search != appliedSearch) {
                            appliedSearch = search
                            if (searching) searchPosition.requestScrollToItem(0)
                        }
                    }
                    Box(Modifier.fillMaxWidth()) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive((if (embedded) 112.dp else 68.dp) * fontScale), state = state,
                        modifier = Modifier.fillMaxWidth().padding(end = if (desktop) 10.dp else 0.dp),
                        userScrollEnabled = active,
                        contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (searching && targetChapters.isEmpty() && !loading) item(span = {
                            androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                        }) {
                            Text("没有符合条件的章节", color = colors.onSurfaceVariant,
                                fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
                        }
                        items(targetChapters, key = Chapter::id, span = if (listResults) {
                            { _ -> androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
                        } else null) { chapter ->
                            val selected = chapter.id == currentId
                            val entry = saved[chapter.id]
                            Row(Modifier.fillMaxWidth().heightIn(min = if (listResults) 56.dp else 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) colors.primaryContainer
                                    else colors.onSurface.copy(alpha = if (listResults) 0f else .042f))
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(chapter.id) })
                                .padding(horizontal = if (listResults) 12.dp else 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(chapter.title, color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                                        fontSize = if (listResults) 15.sp else 13.sp, lineHeight = if (listResults) 21.sp else 18.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (searching && groups.size > 1) Text(chapterGroupLabel(chapter.group),
                                        color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
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
                                if (listResults) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                                    Modifier.padding(start = 8.dp).size(18.dp), tint = colors.onSurfaceVariant)
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
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onClosed = if (android) ({
            searchExpanded = false
            onQuery("")
        }) else null,
        content = panel)
}

@Composable
internal fun ReaderPanelHeading(title: String, closeLabel: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f))
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
