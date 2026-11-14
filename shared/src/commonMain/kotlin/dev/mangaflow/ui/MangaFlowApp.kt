package dev.mangaflow.ui

import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.layout.asPaddingValues

import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.ui.platform.*

import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import dev.mangaflow.ui.motion.BackHandler
import dev.mangaflow.ui.motion.MotionBackHandler
import dev.mangaflow.ui.motion.MotionBackState
import dev.mangaflow.ui.motion.LocalMotionBackState
import dev.mangaflow.ui.motion.motionBackPreview
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Leaderboard
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.ui.motion.MotionBottomSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.mangaflow.experience.ExperiencePolicyStore
import kotlinx.coroutines.awaitCancellation
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.DetailState
import dev.mangaflow.MangaViewModel
import dev.mangaflow.ReadingSession
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.reader.ReaderScreen
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.HistoryActionIcon
import dev.mangaflow.ui.motion.MotionNavigationColumn
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberFeelFeedback
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.update.UpdatePhase
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

private enum class Section(val title: String, val icon: ImageVector) {
    DISCOVER("发现", Icons.Outlined.Explore), BOARDS("各站榜单", Icons.Outlined.Leaderboard),
    LIBRARY("书架", Icons.Outlined.Bookmarks),
    DOWNLOADS("下载", Icons.Outlined.FileDownload), ACCOUNT("账号", Icons.Outlined.AccountCircle),
    SETTINGS("设置", Icons.Outlined.Settings),
}
private fun Section.navigationTitle(restricted: Boolean): String =
    if (this == Section.ACCOUNT && !restricted) "拷贝书架" else title
/** A new reference owns the motion; cancellation cannot replay a stale destination. */
private class DrawerAction(val target: DrawerValue, val destination: Section? = null,
    val restricted: Boolean? = null) {
    var rootCommitted by mutableStateOf(false)
}

/** Observe before the native drawer can catch an animating surface and consume the first DOWN. */
@Composable
private fun Modifier.drawerSelectionTapInput(
    targets: Map<Section, Rect>,
    canSelect: () -> Boolean,
    catchAnimatingTap: () -> Boolean,
    onSelect: (Section) -> Unit,
): Modifier {
    val latestCanSelect by rememberUpdatedState(canSelect)
    val latestCatchAnimatingTap by rememberUpdatedState(catchAnimatingTap)
    val latestOnSelect by rememberUpdatedState(onSelect)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return onGloballyPositioned { coordinates = it }.pointerInput(targets) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.isConsumed || !latestCanSelect() || !latestCatchAnimatingTap() ||
                (down.type != PointerType.Touch && down.type != PointerType.Stylus)) return@awaitEachGesture
            val layout = coordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
            val origin = layout.localToRoot(down.position)
            val target = targets.entries.firstOrNull { it.value.contains(origin) } ?: return@awaitEachGesture
            val destination = target.key
            val hitBounds = target.value
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val currentLayout = coordinates?.takeIf { it.isAttached } ?: break
                val point = currentLayout.localToRoot(change.position)
                // Root coordinates keep drawer travel out of the actual finger displacement.
                // Never consume DOWN or MOVE: a real swipe still belongs to the native drawer.
                if (!latestCanSelect() || event.changes.any {
                        it.id != down.id && (it.pressed || it.previousPressed)
                    } || (point - origin).getDistance() > viewConfiguration.touchSlop ||
                    !hitBounds.contains(point)) break
                if (!change.pressed) {
                    // This node precedes anchoredDraggable. A consumed synthetic CANCEL is not UP.
                    // Consuming only the real UP prevents Surface from delivering a second click.
                    if (change.changedToUp()) {
                        change.consume()
                        latestOnSelect(destination)
                    }
                    break
                }
            }
        }
    }
}

private sealed interface Frame {
    val key: String
    data class Root(val section: Section, val profile: String) : Frame {
        override val key = "root-$profile-${section.name}"
    }
    class Detail(private val snapshot: State<DetailState>) : Frame {
        val state: DetailState get() = snapshot.value
        override val key = "detail-${snapshot.value.manga.id}"
    }
    data class Reader(val session: ReadingSession) : Frame { override val key = "reader-${session.manga.id}-${session.collection?.key.orEmpty()}" }
}

private val Frame.depth: Int get() = when (this) {
    is Frame.Root -> 0
    is Frame.Detail -> 1
    is Frame.Reader -> 2
}

/** Keep the last drawn snapshot while its page is covered; resubscription receives current data. */
@Composable
private fun <T> StateFlow<T>.collectVisibleAsState(visible: Boolean): State<T> {
    val source = this
    val visibleFlow = remember(source, visible) { flow { if (visible) emitAll(source) } }
    return visibleFlow.collectAsStateWithLifecycle(initialValue = source.value)
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MangaFlowApp(openUpdatesRequest: Int, openDownloadsRequest: Int, onRequestNotifications: () -> Unit, model: MangaViewModel = rememberMangaViewModel()) {
    val windowSize = LocalMangaWindow.current
    val wallpaperContext = LocalContext.current.applicationContext
    val experience = remember(wallpaperContext) { ExperiencePolicyStore.get(wallpaperContext) }
    val experienceBudget by experience.state.collectAsStateWithLifecycle()
    val experienceLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(experience, experienceLifecycle) {
        experienceLifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val release = experience.startObserving()
            try { awaitCancellation() } finally { release() }
        }
    }
    val wallpaperStore = remember(wallpaperContext) { WallpaperStore.get(wallpaperContext) }
    val wallpaper by wallpaperStore.state.collectAsStateWithLifecycle()
    val metricState = remember(model) { MangaMetricState(model.metrics, model::retainMetrics, model::releaseMetrics) }
    val detail by model.detail.collectAsStateWithLifecycle()
    val authorWorks by model.authorWorks.collectAsStateWithLifecycle()
    val reading by model.reading.collectAsStateWithLifecycle()
    val sourceManagement by model.sourceManagement.collectAsStateWithLifecycle()
    val sourceOperation by model.chapterSourceOperation.collectAsStateWithLifecycle()
    val sourceResumeChoice by model.sourceResumeChoice.collectAsStateWithLifecycle()
    val sourceResumePolicy by model.sourceResumePolicy.collectAsStateWithLifecycle()
    val sourceCatalogErrors by model.repository.sourceErrors.collectAsStateWithLifecycle()
    val sourceDiscovery by model.repository.sourceDiscovery.collectAsStateWithLifecycle()
    val libraryOpening by model.libraryOpening.collectAsStateWithLifecycle()
    val refreshingDownloads by model.refreshingDownloads.collectAsStateWithLifecycle()
    val chapterPreparations by model.chapterPreparations.collectAsStateWithLifecycle()
    val account by model.account.collectAsStateWithLifecycle()
    val copyShelfSync by model.copyShelfSync.state.collectAsStateWithLifecycle()
    val copyShelfRemote by model.copyShelfRemote.collectAsStateWithLifecycle()
    val copyProgress by model.copyProgress.collectAsStateWithLifecycle()
    val adoptedResumeTargets by model.library.resumeTargets.collectAsStateWithLifecycle()
    var copySyncOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(account.profile?.username) { copySyncOpen = false }
    val update by model.updates.state.collectAsStateWithLifecycle()
    var restrictedMode by remember { mutableStateOf(false) }
    var profileSourcesOpen by remember { mutableStateOf(false) }
    var sourceSettingsOpen by remember { mutableStateOf(false) }
    val profileKey = if (restrictedMode) "restricted" else "ordinary"
    val ordinarySection = rememberSaveable { mutableStateOf(Section.DISCOVER.name) }
    val restrictedSection = rememberSaveable { mutableStateOf(Section.DISCOVER.name) }
    var sectionName by (if (restrictedMode) restrictedSection else ordinarySection)
    val section = Section.entries.firstOrNull {
        it.name == sectionName && (!restrictedMode || it != Section.BOARDS)
    } ?: Section.DISCOVER
    LaunchedEffect(account.profile?.username, account.busy, section, reading == null, detail == null, restrictedMode) {
        if (!restrictedMode && account.profile != null && !account.busy && reading == null && detail == null &&
            section in setOf(Section.ACCOUNT, Section.LIBRARY) && !copyShelfSync.checked && !copyShelfSync.busy)
            model.copyShelfSync.check()
    }
    LaunchedEffect(account.profile?.username, account.busy, section, reading == null, detail == null, restrictedMode) {
        if (!restrictedMode && section == Section.ACCOUNT && reading == null && detail == null &&
            account.profile != null && !account.busy) model.refreshCopyShelfMetadata()
        else model.cancelCopyShelfMetadata()
    }
    LaunchedEffect(detail?.manga?.id, account.profile?.username, account.busy, reading == null, restrictedMode) {
        val work = detail?.manga
        if (!restrictedMode && work != null && reading == null && !account.busy) model.refreshCopyProgress(work)
        else model.clearCopyProgress()
    }
    LaunchedEffect(detail?.detail, adoptedResumeTargets) { model.reconcileCopyProgress() }
    val displayedAccount = if (account.profile != null && copyShelfSync.owner == account.profile?.username && copyShelfSync.checked)
        account.copy(favorites = copyShelfSync.cloudFavorites, favoritesHasMore = false,
            favoritesLoading = copyShelfSync.checking, favoritesRefreshing = copyShelfSync.checking)
    else account
    val restrictedMetrics = remember(model) { MangaMetricState(model.metrics, { _ -> }, { _ -> }) }
    val displayedMetrics = if (restrictedMode) restrictedMetrics else metricState
    var drawerAction by remember { mutableStateOf<DrawerAction?>(null) }
    var drawerRootTransition by remember { mutableStateOf<Section?>(null) }
    val rootOpacity = remember { Animatable(1f) }
    val rootHandoff by remember {
        derivedStateOf { drawerAction?.let { (it.destination != null || it.restricted != null) && !it.rootCommitted } == true }
    }
    val changeSection: (Section) -> Unit = { destination ->
        model.cancelLibraryReading()
        // Root pages share one shell, so every entry must use its interruptible handoff.
        drawerAction = DrawerAction(DrawerValue.Closed, destination)
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerScroll = rememberScrollState()
    val drawerBackdrop = rememberHazeState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val savedState = rememberSaveableStateHolder()
    val ordinarySearch = rememberSaveable { mutableStateOf(false) }
    val restrictedSearch = rememberSaveable { mutableStateOf(false) }
    var searchExpanded by (if (restrictedMode) restrictedSearch else ordinarySearch)
    var desktopNavigationMode by rememberSaveable { mutableIntStateOf(0) }
    var desktopSearchRequest by remember { mutableIntStateOf(0) }
    var tagSearchVisible by rememberSaveable { mutableStateOf(false) }
    var recentReadingOpen by rememberSaveable { mutableStateOf(false) }
    val ordinaryBrowse = remember(model) { StandardBrowseActions(model) }
    val scopedBrowseSaver = remember(model, scope) {
        Saver<ScopedBrowseController, ArrayList<String>>(
            save = { ArrayList(it.savedSelection()) },
            restore = { saved ->
                ScopedBrowseController(model.repository, scope).apply { restoreSelection(saved) }
            },
        )
    }
    val scopedBrowse = rememberSaveable(saver = scopedBrowseSaver) {
        ScopedBrowseController(model.repository, scope)
    }
    val browseActions: BrowseUiActions = if (restrictedMode) scopedBrowse else ordinaryBrowse
    val browse by browseActions.state.collectVisibleAsState(
        reading == null && detail == null || tagSearchVisible ||
            searchExpanded && platformKind != PlatformKind.DESKTOP)
    val favorites by model.library.favorites.collectVisibleAsState(reading == null)
    val history by model.library.history.collectVisibleAsState(reading == null || recentReadingOpen)
    val accountChapterHistoryRevision by model.library.chapterHistoryRevision.collectVisibleAsState(
        reading == null && detail == null && section == Section.ACCOUNT && !restrictedMode)
    val preferences by model.library.preferences.collectVisibleAsState(reading == null)
    val sourceEntries by model.bodySources.entries.collectAsStateWithLifecycle()
    val privateSourceIds = remember(sourceEntries) { sourceEntries.filter { it.adult }.map { it.source.id }.toSet() }
    val ordinaryFavorites = remember(favorites, privateSourceIds, restrictedMode) {
        favorites.filter { (it.sourceId in privateSourceIds) == restrictedMode }
    }
    val ordinaryHistory = remember(history, privateSourceIds, restrictedMode) {
        history.filter { (it.manga.sourceId in privateSourceIds) == restrictedMode }
    }
    val profileIncludes = if (restrictedMode) privateSourceIds else null
    val profileExcludes = if (restrictedMode) emptySet() else privateSourceIds
    val sourceSignedIn by model.picacgSource.signedIn.collectAsStateWithLifecycle()
    val profileEntries = remember(sourceEntries, restrictedMode) {
        sourceEntries.filter { it.adult == restrictedMode }
    }
    val usableScopedSources = remember(sourceEntries, sourceSignedIn) {
        sourceEntries.filter { it.adult && it.enabled && it.isInstalled && !it.removed &&
            (it.source.id != "picacg" || sourceSignedIn) }.map { it.source }
    }
    val scopedUnavailableReason = remember(sourceEntries, sourceSignedIn, usableScopedSources) {
        val registered = sourceEntries.filter { it.adult && !it.removed }
        val enabled = registered.filter { it.enabled }
        val installed = enabled.filter { it.isInstalled }
        when {
            usableScopedSources.isNotEmpty() -> null
            registered.isEmpty() -> "此分区还没有来源，请先添加来源。"
            enabled.isEmpty() -> "此分区的来源尚未启用，请在来源设置中开启。"
            installed.isEmpty() -> "此分区的来源尚未安装，请先完成安装。"
            installed.any { it.source.id == "picacg" } && !sourceSignedIn ->
                "当前来源需要登录，请先到账号页登录后重试。"
            else -> "此分区暂时没有已就绪的来源，请查看来源状态。"
        }
    }
    SideEffect {
        ordinaryBrowse.setActive(!restrictedMode)
        scopedBrowse.updateSources(usableScopedSources, unavailableReason = scopedUnavailableReason)
        scopedBrowse.setActive(restrictedMode && (section == Section.DISCOVER || tagSearchVisible) &&
            reading == null && (detail == null || tagSearchVisible))
    }
    DisposableEffect(scopedBrowse, ordinaryBrowse) {
        onDispose { scopedBrowse.dispose(); ordinaryBrowse.setActive(false) }
    }
    var selectedCover by remember { mutableStateOf<MangaCoverKey?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current
    val motion = LocalMotionEnabled.current
    val feedback = rememberFeelFeedback()

    LaunchedEffect(drawerAction, motion) {
        val action = drawerAction
        if (action == null) {
            rootOpacity.snapTo(1f)
            return@LaunchedEffect
        }
        try {
            val destination = action.destination
            val changesProfile = action.restricted?.let { it != restrictedMode } == true
            val destinationSection = (destination ?: Section.entries.firstOrNull {
                it.name == if (action.restricted == true) restrictedSection.value else ordinarySection.value
            })?.takeUnless { action.restricted == true && it == Section.BOARDS } ?: Section.DISCOVER
            val changesRoot = (changesProfile || destination != null && sectionName != destination.name) &&
                reading == null && detail == null
            if (changesRoot) {
                // Show the selection before moving its surface. Never wait for a whole spring.
                if (motion && drawer.isOpen) withFrameNanos { }
                if (drawerAction !== action || reading != null || detail != null) return@LaunchedEffect
                model.cancelLibraryReading()
                drawerRootTransition = destinationSection
                if (motion) rootOpacity.snapTo(minOf(rootOpacity.value, MangaMotion.RootEntryAlpha))
                // A click commits its content scope before the shared foreground settles.
                if (action.restricted != null) {
                    ordinaryBrowse.setActive(!action.restricted)
                    scopedBrowse.setActive(action.restricted && destinationSection == Section.DISCOVER)
                    restrictedMode = action.restricted
                }
                if (destination != null) sectionName = destination.name
            }
            coroutineScope {
                launch {
                    if (!motion) drawer.snapTo(action.target)
                    else if (action.target == DrawerValue.Open) drawer.open() else drawer.close()
                }
                if (changesRoot && motion) {
                    snapshotFlow {
                        action.rootCommitted || reading != null || detail != null
                    }.first { it }
                }
                if (drawerAction === action) {
                    if (motion) rootOpacity.animateTo(1f,
                        tween(MangaMotion.RootFadeIn, easing = MangaMotion.EnterEasing))
                    else rootOpacity.snapTo(1f)
                }
            }
        } finally {
            if (drawerAction === action && currentCoroutineContext().isActive) {
                rootOpacity.snapTo(1f)
                drawerAction = null
            }
        }
    }

    LaunchedEffect(Unit) { model.events.collectLatest { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { PlatformNotices.messages.collectLatest { snackbar.showSnackbar(it) } }
    LaunchedEffect(openUpdatesRequest) {
        if (openUpdatesRequest > 0) {
            tagSearchVisible = false
            browseActions.clearSearch()
            model.closeReader()
            model.closeDetail()
            changeSection(Section.SETTINGS)
        }
    }
    LaunchedEffect(openDownloadsRequest) {
        if (openDownloadsRequest > 0) {
            tagSearchVisible = false
            browseActions.clearSearch()
            model.closeReader()
            model.closeDetail()
            changeSection(Section.DOWNLOADS)
        }
    }
    // Loading, catalogue and review updates belong to this route, not its navigation target.
    val detailFrame = detail?.let { state ->
        androidx.compose.runtime.key(state.manga.id) {
            val snapshot = rememberUpdatedState(state)
            remember(snapshot) { Frame.Detail(snapshot) }
        }
    }
    val frame: Frame = reading?.let { Frame.Reader(it) } ?: detailFrame ?: Frame.Root(section, profileKey)
    val desktop = platformKind == PlatformKind.DESKTOP
    val desktopNavigationVisible = desktop && reading == null && windowSize.width >= 760.dp
    val desktopNavigationWide = when (desktopNavigationMode) {
        1 -> true
        2 -> false
        else -> windowSize.width >= 1060.dp * windowSize.fontScale.coerceAtLeast(1f)
    }
    val profileSections = remember(restrictedMode) {
        Section.entries.filter { !restrictedMode || it != Section.BOARDS }
    }
    val desktopDestinations = remember(profileSections, restrictedMode) {
        profileSections.map { DesktopDestination(it.navigationTitle(restrictedMode), it.icon) }
    }
    CopyShelfSyncSheet(
        visible = copySyncOpen && account.profile != null && !restrictedMode,
        state = copyShelfSync, backdrop = drawerBackdrop,
        onCheck = model.copyShelfSync::check, onImport = model.copyShelfSync::importFavorites,
        onUpload = model.copyShelfSync::upload, onDismiss = { copySyncOpen = false },
    )
    sourceManagement?.let { state ->
        UnifiedSourceManager(state, drawerBackdrop, onDismiss = model::closeSourceManagement,
            onSearch = model::searchBodySource, onAttach = { candidate -> model.attachBodySource(state.work.id, candidate) },
            onRetryAll = model::retrySourceDiscovery, onChooseDomain = model::chooseSourceDomain,
            onChooseSource = model::chooseBodySource)
    }
    sourceResumeChoice?.let { choice ->
        SourceResumePrompt(choice, drawerBackdrop, onChoose = model::confirmSourceResumeChoice,
            onDismiss = model::cancelSourceResumeChoice)
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(frame.key) { focus.clearFocus(force = true); keyboard?.hide() }
    val commitProfile: (Boolean) -> Unit = { restricted ->
        if (restricted != restrictedMode) {
            model.cancelLibraryReading()
            model.closeReader()
            model.closeDetail()
            model.closeAuthor()
            model.closeSourceManagement()
            model.cancelSourceResumeChoice()
            tagSearchVisible = false
            recentReadingOpen = false
            profileSourcesOpen = false
            sourceSettingsOpen = false
            selectedCover = null
            keyboard?.hide()
            focus.clearFocus(force = true)
            drawerAction = DrawerAction(DrawerValue.Closed, restricted = restricted)
        }
    }
    val requestProfile: (Boolean) -> Unit = commitProfile
    val manageProfileSources: () -> Unit = {
        if (restrictedMode) profileSourcesOpen = true else sourceSettingsOpen = true
    }
    if (profileSourcesOpen) ContentProfileSourceSheet(
        entries = profileEntries, selectedId = scopedBrowse.sourceId, backdrop = drawerBackdrop,
        onSelect = scopedBrowse::selectSource,
        onManage = { sourceSettingsOpen = true },
        onDismiss = { profileSourcesOpen = false },
        unavailableReasons = if (sourceSignedIn) emptyMap()
            else mapOf("picacg" to "需要先在账号页登录"),
        onAccount = if (!sourceSignedIn && profileEntries.any {
            it.source.id == "picacg" && it.enabled && it.isInstalled && !it.removed
        }) { { changeSection(Section.ACCOUNT) } } else null,
    )
    if (sourceSettingsOpen) BodySourceSettingsSheet(
        model.bodySources, drawerBackdrop, model.sourcePlugins,
        onDismiss = { sourceSettingsOpen = false }, importAsAdult = restrictedMode,
    )
    val openDesktopSearch: () -> Unit = {
        // The regular reader/detail close paths retain their own positions and cancellation.
        model.closeReader()
        model.closeDetail()
        tagSearchVisible = false
        recentReadingOpen = false
        drawerAction = null
        sectionName = Section.DISCOVER.name
        browseActions.resumeBrowseSearch()
        searchExpanded = true
        desktopSearchRequest++
    }
    if (recentReadingOpen) {
        RecentReadingSheet(ordinaryHistory, model.repository, model.library,
            onDismiss = { recentReadingOpen = false },
            onContinue = { model.continueReading(it) },
            onRemoveHistory = model::removeHistory, backdrop = drawerBackdrop)
    }
    authorWorks?.let { author ->
        androidx.compose.runtime.key(author.manga.id, author.name) {
            AuthorWorksSheet(author, model.repository, onDismiss = model::closeAuthor,
                onMore = model::moreAuthor, onRetry = model::retryAuthor,
                onOpen = { model.openManga(it) })
        }
    }
    if (tagSearchVisible) {
        CompositionLocalProvider(LocalMangaMetrics provides displayedMetrics) {
        val panelHeight = (windowSize.height * .72f - 60.dp).coerceAtLeast(1.dp)
        val closeTags: () -> Unit = { tagSearchVisible = false; browseActions.clearSearch() }
        MotionBottomSheet(onDismissRequest = closeTags) { dismiss ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("标签筛选", Modifier.weight(1f), fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "关闭标签筛选", Modifier.size(20.dp))
                    }
                }
                DiscoverySearchResults(browse, expanded = true, active = true,
                    maxHeight = panelHeight, repository = model.repository,
                    onRetry = browseActions::retryBrowse, onMore = browseActions::loadMore,
                    onOpen = { manga -> selectedCover = null; dismiss(); model.openManga(manga) },
                    onTarget = { browseActions.setSearchTarget(SearchTarget.TAG) }, onTag = browseActions::toggleSearchTag,
                    onRetryTags = browseActions::retrySearchTags, tagsOnly = true, onTagQuery = browseActions::setQuery)
            }
        }
        }
    }
    val darkSystem = isSystemInDarkTheme()
    SystemBarAppearance(lightStatus = !darkSystem, lightNavigation = !darkSystem, active = frame is Frame.Root)

    val density = LocalDensity.current
    val drawerWidth = (296.dp + 48.dp * (windowSize.fontScale - 1f)).coerceAtMost(360.dp)
        .coerceAtMost((windowSize.width - 24.dp).coerceAtLeast(1.dp))
    var measuredDrawerWidth by remember { mutableFloatStateOf(0f) }
    val drawerWidthPx = measuredDrawerWidth.takeIf { it > 0f } ?: with(density) { drawerWidth.toPx() }
    LaunchedEffect(desktopNavigationVisible) {
        if (desktopNavigationVisible) drawer.snapTo(DrawerValue.Closed)
    }
    val drawerVisible by remember(drawer, drawerWidthPx, desktopNavigationVisible) {
        derivedStateOf {
            val offset = drawer.currentOffset
            !desktopNavigationVisible && (drawer.isOpen || drawer.targetValue == DrawerValue.Open ||
                (offset.isFinite() && offset > -drawerWidthPx + .5f))
        }
    }
    val drawerExposed by remember(drawer, drawerWidthPx, desktopNavigationVisible) {
        derivedStateOf {
            !desktopNavigationVisible && drawer.currentOffset.let { it.isFinite() && it > -drawerWidthPx + .5f }
        }
    }
    val drawerCanSelect = frame is Frame.Root && drawerExposed
    val drawerTargets = remember { mutableMapOf<Section, Rect>() }
    SideEffect { drawerTargets.keys.retainAll(profileSections.toSet()) }
    val canSelectDrawer: () -> Boolean = {
        frame is Frame.Root && drawer.currentOffset.let { it.isFinite() && it > -drawerWidthPx + .5f }
    }
    val selectDrawer: (Section) -> Unit = { destination ->
        if (destination in profileSections && canSelectDrawer()) {
            if ((drawerAction?.destination ?: section) != destination)
                feedback.perform(FeelEvent.Selection)
            changeSection(destination)
        }
    }
    BackHandler(drawerVisible || libraryOpening != null) {
        if (drawerVisible) drawerAction = DrawerAction(DrawerValue.Closed)
        else model.cancelLibraryReading()
    }
    val routeTravel = with(density) { 24.dp.roundToPx() }
    CompositionLocalProvider(
        LocalMangaMetrics provides displayedMetrics,
        LocalWallpaperActive provides (frame is Frame.Root && wallpaper.mediaPath != null),
    ) {
    DesktopNavigationWorkspace(
        visible = desktopNavigationVisible, expanded = desktopNavigationWide,
        destinations = desktopDestinations, selectedIndex = profileSections.indexOf(section).coerceAtLeast(0),
        version = "MangaFlow " + platformVersion(LocalContext.current), backdrop = drawerBackdrop,
        onToggle = { desktopNavigationMode = if (desktopNavigationWide) 2 else 1 },
        onNavigate = { index ->
            val destination = profileSections[index]
            model.closeDetail()
            tagSearchVisible = false
            recentReadingOpen = false
            searchExpanded = destination == Section.DISCOVER && browse.isSearch
            changeSection(destination)
        },
        onSearch = openDesktopSearch,
        profileControl = { compact ->
            ContentProfileSwitch(restrictedMode, requestProfile, manageProfileSources,
                compact = compact, enabled = !rootHandoff)
        },
        modifier = Modifier.then(if (desktop || !experienceBudget.preferHighRefresh) Modifier else
            Modifier.preferredFrameRate(FrameRateCategory.High)).onPreviewKeyEvent { event ->
            if (desktop && event.isCtrlPressed && !event.isAltPressed &&
                (event.key == Key.K || event.key == Key.F)) {
                if (event.type == KeyEventType.KeyDown) openDesktopSearch()
                true
            } else false
        }.onKeyEvent { event ->
            // A focused control or temporary popup gets first refusal.
            val canGoBack = desktop && event.isAltPressed && event.key == Key.DirectionLeft &&
                sourceManagement == null && sourceResumeChoice == null && authorWorks == null &&
                !recentReadingOpen && !tagSearchVisible && !drawerVisible
            if (canGoBack) {
                if (event.type == KeyEventType.KeyUp) when {
                    reading != null -> model.returnToMangaDetail()
                    detail != null -> model.closeDetail()
                    searchExpanded -> { searchExpanded = false; browseActions.suspendBrowseSearch(); focus.clearFocus(force = true) }
                    section != Section.DISCOVER -> changeSection(Section.DISCOVER)
                }
                true
            } else false
        },
    ) {
    ModalNavigationDrawer(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .drawerSelectionTapInput(drawerTargets, canSelectDrawer,
                { drawer.isAnimationRunning && !drawerScroll.isScrollInProgress }, selectDrawer),
        drawerState = drawer,
        gesturesEnabled = frame is Frame.Root && !desktopNavigationVisible,
        scrimColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(drawerWidth).onSizeChanged { measuredDrawerWidth = it.width.toFloat() }
                    .padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                drawerShape = RoundedCornerShape(26.dp),
                drawerContainerColor = Color.Transparent,
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                // Preserve the measured drawer anchors for shared navigation transitions,
                // but never draw the off-screen phone menu over persistent desktop navigation.
                if (!desktopNavigationVisible) {
                Box(Modifier.fillMaxSize()) {
                if (drawerVisible && frame is Frame.Root) LiquidGlassSurface(
                    state = drawerBackdrop,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                    cornerRadius = 26.dp,
                    role = GlassSurfaceRole.DRAWER,
                    trackPointer = true, elevation = 0.dp,
                ) {}
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                    .then(if (drawerCanSelect) Modifier else Modifier.clearAndSetSemantics { }
                        .focusProperties { canFocus = false })
                    .padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(Modifier.padding(12.dp, 8.dp, 12.dp, if (windowSize.shortHeight) 8.dp else 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("漫流", style = MaterialTheme.typography.titleLarge)
                            Text("MANGAFLOW", fontSize = 10.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ContentProfileSwitch(restrictedMode, requestProfile, manageProfileSources,
                        modifier = Modifier.padding(bottom = 10.dp), enabled = drawerCanSelect && !rootHandoff)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(drawerScroll)) {
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    MotionNavigationColumn(
                        selected = profileSections.indexOf(drawerAction?.destination ?: section).coerceAtLeast(0),
                        itemCount = profileSections.size,
                    ) { index, itemModifier ->
                        val destination = profileSections[index]
                        val interactions = remember(destination) { MutableInteractionSource() }
                        val selected = (drawerAction?.destination ?: section) == destination
                        NavigationDrawerItem(
                            label = {
                                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                    .motionPress(interactions, enabled = drawerCanSelect),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(destination.icon, null, Modifier.size(21.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else .82f))
                                    Spacer(Modifier.width(12.dp))
                                    Text(destination.navigationTitle(restrictedMode), fontWeight = FontWeight.Medium)
                                }
                            },
                            selected = selected,
                            onClick = { selectDrawer(destination) },
                            shape = RoundedCornerShape(14.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = Color.Transparent,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                unselectedContainerColor = Color.Transparent,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .88f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
                            ),
                            modifier = itemModifier.onGloballyPositioned {
                                drawerTargets[destination] = it.boundsInRoot()
                            },
                            interactionSource = interactions,
                        )
                    }
                    }
                    }
                    Text("MangaFlow ${platformVersion(LocalContext.current)}", modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
                }
            }
        },
    ) {
        Scaffold(
            modifier = Modifier
                // Keep the source node stable. The visible-only drawer effect owns capture demand;
                // Haze 2.1 releases the layer and draws directly when that demand disappears.
                .mangaHazeSource(drawerBackdrop),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
            SharedTransitionLayout {
            val coverScope = this
            AnimatedContent(
                targetState = frame,
                // Root destinations share the shell; only their body owns a section save-state key.
                contentKey = { if (it is Frame.Root) "root-shell" else it.key },
                transitionSpec = {
                    if (!motion || (initialState is Frame.Root && targetState is Frame.Root &&
                        (targetState as Frame.Root).section == drawerRootTransition))
                        (fadeIn(snap()) togetherWith fadeOut(snap())).using(null)
                    else if ((initialState is Frame.Root && targetState is Frame.Detail ||
                        initialState is Frame.Detail && targetState is Frame.Root) &&
                        selectedCover?.mangaId == ((targetState as? Frame.Detail)?.state?.manga?.id
                            ?: (initialState as? Frame.Detail)?.state?.manga?.id)) {
                        // The matched cover owns the spatial journey; a second route slide
                        // would move its landing target while the cover is already in flight.
                        (fadeIn(tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing)) togetherWith
                            fadeOut(tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing))).using(null)
                    } else {
                        val forward = targetState.depth >= initialState.depth
                        val direction = if (forward) 1 else -1
                        ((fadeIn(tween(MangaMotion.NavigationFade)) +
                            slideInHorizontally(spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness)) { direction * routeTravel }) togetherWith
                            (fadeOut(tween(MangaMotion.Quick)) +
                                slideOutHorizontally(tween(MangaMotion.Exit, easing = MangaMotion.EnterEasing)) { -direction * routeTravel / 2 }))
                            .using(null)
                    }
                },
                contentAlignment = Alignment.TopStart,
                label = "page",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                val pendingDrawerSelection = drawerAction
                LaunchedEffect(current.key, pendingDrawerSelection, motion) {
                    val selection = pendingDrawerSelection ?: return@LaunchedEffect
                    if (current is Frame.Root && current.key == frame.key &&
                        (selection.destination == null || selection.destination == current.section) &&
                        (selection.restricted == null || selection.restricted == (current.profile == "restricted"))) {
                        // Only the foreground reveal waits for a committed target frame.
                        // Native drawer travel has already started independently.
                        if (motion) withFrameNanos { }
                        selection.rootCommitted = true
                    }
                }
                // Retain the outgoing search cover until its real route transition finishes.
                val entry = transition
                LaunchedEffect(current.key, entry.currentState, entry.isRunning) {
                    if (current is Frame.Detail && current.key == frame.key &&
                        entry.currentState == EnterExitState.Visible && !entry.isRunning &&
                        !desktop && searchExpanded && selectedCover?.placement?.startsWith("search-") == true) {
                        searchExpanded = false
                        browseActions.clearSearch()
                    }
                }
                val currentBack = remember(current.key) { MotionBackState() }
                MotionBackHandler(
                    enabled = current.key == frame.key && !drawerVisible && libraryOpening == null &&
                        !recentReadingOpen && authorWorks == null &&
                        (reading != null || detail != null || section != Section.DISCOVER),
                    state = currentBack,
                ) {
                    when {
                        reading != null -> model.returnToMangaDetail()
                        detail != null -> model.closeDetail()
                        else -> changeSection(Section.DISCOVER)
                    }
                }
                CompositionLocalProvider(LocalMangaCoverTransition provides
                    MangaCoverTransition(coverScope, this, selectedCover) { selectedCover = it },
                    LocalMotionBackState provides currentBack) {
                Box(Modifier.fillMaxSize().motionBackPreview(currentBack)
                    .motionInputGate(current.key == frame.key && !currentBack.gesturing && !currentBack.completed)) {
                when (current) {
                    is Frame.Reader -> ReaderScreen(
                        manga = current.session.manga, chapters = current.session.chapters,
                        initialChapterId = current.session.chapterId,
                        initialCollection = current.session.collection,
                        startAtBeginning = current.session.startAtBeginning,
                        initialPageIndex = current.session.initialPageIndex,
                        sourceResumePolicy = sourceResumePolicy,
                        onSourceResumePolicy = model::setSourceResumePolicy,
                        repository = model.repository, libraryStore = model.library, downloadStore = model.downloads,
                        communityRepository = model.community,
                        reviewRepository = model.reviews,
                        onBack = model::returnToMangaDetail, onRequestNotificationPermission = onRequestNotifications,
                        onChapterVisible = model::onChapterVisible,
                        onNearChapterEnd = model::prefetchNextChapter,
                        chaptersLoading = current.session.chaptersLoading,
                        chaptersError = current.session.chaptersError,
                        onRetryChapters = model::retryReadingChapters,
                        active = current.key == frame.key && sourceManagement == null && sourceResumeChoice == null,
                        onReadVariant = model::readReaderChapterVariant,
                        onQuickReadVariant = model::quickReadReaderChapterVariant,
                        sourceSwitchName = sourceOperation.targetSource,
                        onSetPreferredSource = model::preferBodySource,
                        onClearChapterSource = model::clearChapterSource,
                        onCancelSourceSelection = model::cancelSourceSelection,
                        sourceLoading = sourceOperation.loading, sourceError = sourceOperation.error,
                    )
                    is Frame.Detail -> savedState.SaveableStateProvider(current.key) {
                        if (current.state.rankedWork != null) {
                            RankedWorkDetailScreen(current.state, repository = model.repository, onBack = model::closeDetail,
                                onSearch = model::searchRankedSources,
                                onUseSource = { workKey, manga ->
                                    selectedCover = null
                                    model.useRankedSource(workKey, manga)
                                },
                                onClearSource = model::clearRankedSource,
                                onConfirmSource = model::confirmRankedSource, onCancelPreview = model::cancelRankedSourcePreview,
                                active = current.key == frame.key && !currentBack.gesturing && !currentBack.completed,
                                wallpaper = {
                                    if (wallpaper.mediaPath != null) AppWallpaper(wallpaperStore, Modifier.fillMaxSize(),
                                        active = current.key == frame.key)
                                })
                        } else {
                        val downloadFlow = remember(model, current.state.manga.id) {
                            model.downloads.observeManga(current.state.manga.id)
                        }
                        val initialDownloads = remember(model, current.state.manga.id) {
                            model.downloads.entries.value.filter { it.manga.id == current.state.manga.id }
                        }
                        val downloads by downloadFlow.collectAsStateWithLifecycle(initialValue = initialDownloads)
                        val chapterPositions = rememberChapterPositions(current.state.manga, model.library,
                            active = current.key == frame.key)
                        CompositionLocalProvider(LocalWallpaperActive provides (wallpaper.mediaPath != null)) {
                            DetailScreen(
                                manga = current.state.manga, detail = current.state.detail,
                                repository = model.repository,
                                loading = current.state.loading, error = current.state.error,
                                reviews = current.state.reviews, reviewsLoading = current.state.reviewsLoading,
                                commentsAvailable = !restrictedMode,
                                isFavorite = favorites.any { it.id == current.state.manga.id },
                                progress = history.firstOrNull { it.manga.id == current.state.manga.id },
                                chapterPositions = chapterPositions,
                                cloudProgress = copyProgress?.takeIf { it.mangaId == current.state.manga.id &&
                                    it.owner == account.profile?.username },
                                onAdoptCloudProgress = model::adoptCopyProgress,
                                onRetryCloudProgress = { model.refreshCopyProgress(current.state.manga) },
                                preferredResumeChapter = adoptedResumeTargets[current.state.manga.id]
                                    ?.takeIf { it.accountOwner == account.profile?.username }
                                    ?.let { target -> current.state.detail?.chapters?.firstOrNull { it.id == target.chapterId } },
                                onContinuePreferred = { model.continuePreferredResume(current.state.manga) },
                                downloadEntries = downloads,
                                downloadPreparations = chapterPreparations,
                                loadVolumeCover = { chapter, refresh -> model.volumeCover(current.state.manga.id, chapter, refresh) },
                                volumeImageHeaders = { chapter -> model.repository.imageHeaders(chapterSourceMangaId(current.state.manga.id, chapter), chapter.id) },
                                onBack = model::closeDetail, onRetry = model::retryDetail,
                                onToggleFavorite = { model.toggleFavorite(current.state.manga) },
                                onRead = model::readChapter, onAuthor = if (restrictedMode) null else model::openAuthor,
                                onReadVariant = model::readChapterVariant,
                                onSetPreferredSource = model::preferBodySource,
                                onClearChapterSource = model::clearChapterSource,
                                onSplitVariant = model::splitChapterSource,
                                onRelinkVariant = model::relinkChapterSource,
                                onManageSources = model::manageSources,
                                onCancelSourceSelection = model::cancelSourceSelection,
                                sourceLoading = sourceOperation.loading,
                                pendingSourceCount = sourceDiscovery[current.state.manga.id]?.values?.count {
                                    it.status == dev.mangaflow.data.SourceDiscoveryStatus.NEEDS_CONFIRMATION
                                } ?: 0,
                                sourcesChecking = sourceDiscovery[current.state.manga.id]?.values?.any {
                                    it.status == dev.mangaflow.data.SourceDiscoveryStatus.SEARCHING
                                } == true,
                                sourceError = sourceOperation.error ?: sourceCatalogErrors[current.state.manga.id]
                                    ?.values?.takeIf { it.isNotEmpty() }?.joinToString("；"),
                                onTag = if (restrictedMode) null else { manga, tag ->
                                    model.openTagSearch(manga, tag)
                                    tagSearchVisible = true
                                },
                                onRestart = model::restartChapter,
                                onContinue = model::continueReading,
                                onDownload = { chapter, route -> onRequestNotifications(); model.downloadChapter(chapter, route) },
                                onDownloadVariant = { chapter, variant, route ->
                                    onRequestNotifications()
                                    model.downloadChapterVariant(current.state.manga, chapter, variant, route)
                                },
                                onReviewMore = model::moreReviews, onReviewRetry = model::retryReviews,
                                onReviewMatch = model::matchReviews, onReviewRelink = model::relinkReviews,
                                loadReplies = model::reviewReplies,
                                active = current.key == frame.key && authorWorks == null && !tagSearchVisible &&
                                    sourceManagement == null && sourceResumeChoice == null,
                                wallpaper = {
                                    if (wallpaper.mediaPath != null) AppWallpaper(wallpaperStore, Modifier.fillMaxSize(),
                                        active = current.key == frame.key && authorWorks == null && !tagSearchVisible)
                                },
                            )
                        }
                        }
                    }
                    is Frame.Root -> {
                        val searchBackdrop = rememberHazeState()
                        val rootWallpaperActive = current.key == frame.key &&
                            !drawerVisible && !recentReadingOpen && !tagSearchVisible
                        val chromeDensity = LocalDensity.current
                        val chromeTopInset = screenFitContentInsets().asPaddingValues().calculateTopPadding()
                        val discoveryBackground = browseActions.browseBackground(browse)
                        val displayedDiscovery = if (!browse.isSearch) browse else discoveryBackground
                        val discoveryModes = if (restrictedMode) listOf(DiscoveryMode.RECOMMENDED) else DiscoveryMode.entries
                        val rankingExpanded = !restrictedMode && displayedDiscovery.mode == DiscoveryMode.RANKING
                        var retainedRankingState by remember { mutableStateOf(displayedDiscovery) }
                        SideEffect { if (rankingExpanded) retainedRankingState = displayedDiscovery }
                        var rankingEmphasis by remember { mutableStateOf<State<Float>?>(null) }
                        var rankingAnchor by remember { mutableStateOf(Rect.Zero) }
                        var chromeHeightPx by remember(current.section, chromeDensity, chromeTopInset) {
                            mutableIntStateOf(with(chromeDensity) { (48.dp + chromeTopInset).roundToPx() })
                        }
                        val discoveryTabsWidth = with(chromeDensity) {
                            val labelSlot = maxOf(52.dp, 14.sp.toDp() * 2 + 24.dp)
                            labelSlot * discoveryModes.size + 8.dp
                        }
                        var searchPresent by remember { mutableStateOf(searchExpanded) }
                        val searchVisible = if (desktop) searchExpanded else searchExpanded || searchPresent
                        val desktopSearchContent = desktop && searchExpanded && (browse.query.isNotBlank() ||
                            browse.selectedTags.isNotEmpty() || browse.searchTarget == SearchTarget.TAG)
                        var searchForeground by remember { mutableStateOf<State<Float>?>(null) }
                        val chromeReady by remember {
                            derivedStateOf { desktop || !searchExpanded && (searchForeground?.value ?: 0f) <= .02f }
                        }
                        val rootActive = current.key == frame.key && !drawerVisible && !rootHandoff && !recentReadingOpen && authorWorks == null && !tagSearchVisible &&
                            !currentBack.gesturing && !currentBack.completed
                        val closeSearch: () -> Unit = {
                            keyboard?.hide()
                            focus.clearFocus(force = true)
                            searchExpanded = false
                            if (desktop) browseActions.suspendBrowseSearch() else browseActions.clearSearch()
                        }
                        // The backdrop reaches every edge; only foreground controls
                        // consume the system/cutout safe area.
                        Box(Modifier.fillMaxSize()) {
                        // The persistent lower source holds the real media; foreground page
                        // sources and headers sample it without owning the player lifecycle.
                        Box(Modifier.fillMaxSize().mangaHazeSource(searchBackdrop, zIndex = -1f)
                            .background(MaterialTheme.colorScheme.background)) {
                            AppWallpaper(wallpaperStore, Modifier.fillMaxSize(), active = rootWallpaperActive)
                        }
                        val contentWidth = if (desktop && current.section != Section.DISCOVER) {
                            MangaLayout.DesktopWorkspaceWidth
                        } else when (current.section) {
                            Section.SETTINGS -> MangaLayout.SettingsWidth
                            Section.ACCOUNT -> MangaLayout.AccountWidth
                            Section.DOWNLOADS -> MangaLayout.ListWidth
                            Section.DISCOVER -> if (desktop)
                                MangaLayout.desktopBrowseWidth(windowSize.width) else MangaLayout.BrowseWidth
                            else -> MangaLayout.BrowseWidth
                        }
                        BoxWithConstraints(Modifier
                            .screenFitViewport()
                            .widthIn(max = contentWidth).fillMaxSize().align(Alignment.TopCenter).graphicsLayer {
                            // Only the incoming foreground settles; the real backdrop never disappears.
                            alpha = rootOpacity.value
                            translationY = ((1f - rootOpacity.value) / (1f - MangaMotion.RootEntryAlpha))
                                .coerceIn(0f, 1f) * 6.dp.toPx()
                        }.motionInputGate(!rootHandoff)) {
                            val discoveryActionsWidth = (if (ordinaryHistory.isEmpty()) 44.dp else 88.dp) + 16.dp
                            val discoveryStacked = maxWidth - 16.dp < 44.dp + 12.dp + discoveryTabsWidth + discoveryActionsWidth
                            val discoveryTabHeight = with(chromeDensity) {
                                maxOf(44.dp, 14.sp.toDp() * (if (fontScale > 1.3f) 2.6f else 1.3f) + 16.dp)
                            }
                            val discoveryChromeBase = (if (discoveryStacked) 44.dp + 4.dp + discoveryTabHeight
                                else maxOf(44.dp, discoveryTabHeight)) + 4.dp + chromeTopInset
                            val chromePadding = (if (desktop) with(chromeDensity) { chromeHeightPx.toDp() }
                            else if (current.section == Section.DISCOVER) {
                                // Recommendation/update content must not follow the old ranking
                                // subbar's exit measurement or a first-frame header estimate.
                                if (rankingExpanded) maxOf(discoveryChromeBase, with(chromeDensity) { chromeHeightPx.toDp() })
                                else discoveryChromeBase
                            } else with(chromeDensity) { chromeHeightPx.toDp() }) + HeaderContentGap
                            val hasUpdate = update.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.READY)
                            val navigation: @Composable () -> Unit = {
                                if (!desktopNavigationVisible) Box {
                                    MotionIconButton(modifier = Modifier.size(44.dp), onClick = {
                                        closeSearch()
                                        model.cancelLibraryReading()
                                        drawerAction = DrawerAction(DrawerValue.Open)
                                    }) {
                                        Icon(Icons.Outlined.Menu, if (hasUpdate) "打开导航，有新版本" else "打开导航", Modifier.size(22.dp))
                                    }
                                    if (hasUpdate && current.section == Section.DISCOVER) Box(
                                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(5.dp)
                                            .background(MaterialTheme.colorScheme.onSurface, CircleShape))
                                }
                            }
                            val toolbar: @Composable (@Composable () -> Unit) -> Unit = { actions ->
                            Row(Modifier.padding(horizontal = 4.dp).height(44.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (current.section == Section.LIBRARY) {
                                    MotionIconButton(modifier = Modifier.size(44.dp), onClick = { changeSection(Section.ACCOUNT) }) {
                                        Icon(Icons.Outlined.CloudQueue, "云端书架", Modifier.size(21.dp))
                                    }
                                }
                                actions()
                            }
                            }
                            val rootCapturesBackground = current.section == Section.SETTINGS ||
                                current.section == Section.ACCOUNT && restrictedMode
                            Box(Modifier.fillMaxSize().then(if (rootCapturesBackground)
                                Modifier.mangaHazeSource(searchBackdrop)
                                else Modifier)) {
                                // The full backdrop includes the pixels beneath the menu button.
                                // Settings reserves chrome inside its scrolling content, so it
                                // can pass beneath the floating header without an inset clip.
                                Box(Modifier.fillMaxSize()) {
                                savedState.SaveableStateProvider(current.key +
                                    if (restrictedMode && current.section == Section.DISCOVER) ":" + browse.sourceScope else "") {
                                when (current.section) {
                                    Section.DISCOVER -> {
                                        val gallery: @Composable () -> Unit = {
                                            DiscoverScreen(
                                                state = browse, repository = model.repository,
                                                discoverColumns = if (platformKind == PlatformKind.DESKTOP)
                                                    preferences.desktopDiscoverColumns else preferences.discoverColumns,
                                                updateColumns = if (platformKind == PlatformKind.DESKTOP)
                                                    preferences.desktopUpdateColumns else preferences.updateColumns,
                                                onDiscoverColumnsChange = { columns ->
                                                    val current = model.library.preferences.value
                                                    model.library.updatePreferences(if (platformKind == PlatformKind.DESKTOP)
                                                        current.copy(desktopDiscoverColumns = columns) else current.copy(discoverColumns = columns))
                                                },
                                                onUpdateColumnsChange = { columns ->
                                                    val current = model.library.preferences.value
                                                    model.library.updatePreferences(if (platformKind == PlatformKind.DESKTOP)
                                                        current.copy(desktopUpdateColumns = columns) else current.copy(updateColumns = columns))
                                                },
                                                backgroundState = discoveryBackground,
                                                searchExpanded = if (desktop) false else searchVisible,
                                                searchBackdrop = searchBackdrop,
                                                backgroundInParent = true,
                                                contentTopPadding = chromePadding,
                                                active = rootActive,
                                                canAutoAdvance = rootActive && !searchVisible,
                                                onRefresh = browseActions::refresh,
                                                onRetry = browseActions::retryBrowse, onMore = browseActions::loadMore,
                                                onManageSources = if (restrictedMode) manageProfileSources else null,
                                                onOpen = { model.openManga(it) },
                                                isFavorite = { manga -> favorites.any { it.id == manga.id } },
                                                onToggleFavorite = model::toggleFavorite,
                                            )
                                        }
                                        if (desktop) DesktopDiscoveryWorkspace(desktopSearchContent, browse = gallery, results = {
                                            BoxWithConstraints(Modifier.fillMaxSize().padding(top = chromePadding)
                                                .mangaHazeSource(searchBackdrop)) {
                                                DiscoverySearchResults(
                                                    state = browse, expanded = true, active = rootActive,
                                                    maxHeight = maxHeight, repository = model.repository,
                                                    backdrop = searchBackdrop, scopeInToolbar = true,
                                                    desktopMainContent = true,
                                                    desktopActions = { manga -> listOf(
                                                        DesktopMenuAction("详情与目录", onClick = { model.openManga(manga) }),
                                                        DesktopMenuAction(if (favorites.any { it.id == manga.id }) "移出书架" else "加入书架",
                                                            onClick = { model.toggleFavorite(manga) }),
                                                    ) },
                                                    onRetry = browseActions::retryBrowse, onMore = browseActions::loadMore,
                                                    onTarget = browseActions::setSearchTarget, onTag = browseActions::toggleSearchTag,
                                                    onRetryTags = browseActions::retrySearchTags,
                                                    onOpen = { manga ->
                                                        keyboard?.hide()
                                                        focus.clearFocus(force = true)
                                                        model.openManga(manga)
                                                    },
                                                )
                                            }
                                        }) else gallery()
                                    }
                                    Section.BOARDS -> {
                                        if (restrictedMode) Box(Modifier.fillMaxSize()) {
                                            Box(Modifier.fillMaxSize().padding(top = chromePadding)) {
                                                EmptyMessage("当前范围暂无站点榜单", "已接入的站点尚不提供此内容范围的榜单。")
                                            }
                                            PageTitleHeader("各站榜单", searchBackdrop, navigation,
                                                modifier = Modifier.align(Alignment.TopCenter)
                                                    .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp))
                                        } else SiteRankingsScreen(
                                            controller = model.siteRankings, backdrop = searchBackdrop,
                                            navigation = navigation, active = rootActive,
                                            onOpen = model::openRankedWork, onInspect = model::inspectRankedWork,
                                        )
                                    }
                                    Section.LIBRARY -> LibraryScreen(
                                        copySyncState = copyShelfSync.takeIf { !restrictedMode &&
                                            account.profile != null && it.owner == account.profile?.username },
                                        onCopySync = if (restrictedMode) null else {
                                            { if (account.profile == null) changeSection(Section.ACCOUNT) else copySyncOpen = true }
                                        },
                                        onImportCopy = if (restrictedMode) null else {
                                            { copySyncOpen = true; model.copyShelfSync.importFavorites() }
                                        },
                                        favorites = ordinaryFavorites, history = ordinaryHistory,
                                        libraryStore = model.library, onOpenPageCollection = model::openPageCollection,
                                        repository = model.repository, opening = libraryOpening, active = rootActive,
                                        backdrop = searchBackdrop, navigation = navigation, toolbar = toolbar,
                                        backgroundInParent = true,
                                        onOpen = model::readFromLibrary, onDetails = { model.openManga(it) },
                                        onContinue = model::continueReading,
                                        onRemoveFavorite = model::removeFavorite,
                                        onRemoveHistory = model::removeHistory,
                                        onDiscover = { changeSection(Section.DISCOVER) },
                                        onRetryOpen = model::retryLibraryReading, onCancelOpen = model::cancelLibraryReading,
                                        includedSourceIds = profileIncludes, excludedSourceIds = profileExcludes,
                                    )
                                    Section.DOWNLOADS -> DownloadsScreen(store = model.downloads, repository = model.repository, onRead = model::openDownload,
                                        onRetry = model::retryDownload, onDetails = model::openManga,
                                        backdrop = searchBackdrop, navigation = navigation, toolbar = toolbar,
                                        backgroundInParent = true,
                                        active = rootActive && !searchVisible, refreshingIds = refreshingDownloads,
                                        history = ordinaryHistory, excludedSourceIds = profileExcludes,
                                        includedSourceIds = profileIncludes,
                                        preparations = chapterPreparations,
                                        onCancelPreparation = model::cancelChapterPreparation,
                                        onRetryPreparation = model::retryChapterPreparation)
                                    Section.ACCOUNT -> if (restrictedMode) ContentProfileAccountScreen(
                                        picacg = model.picacgSource,
                                        hasAccountSource = profileEntries.any { it.source.id == "picacg" &&
                                            it.enabled && it.isInstalled && !it.removed },
                                        active = rootActive, onSources = manageProfileSources,
                                        onSessionChange = scopedBrowse::refresh, contentTopPadding = chromePadding,
                                    ) else AccountScreen(
                                        state = displayedAccount, repository = model.repository,
                                        active = rootActive && !searchVisible,
                                        backdrop = searchBackdrop, navigation = navigation,
                                        onSearch = if (desktop && !desktopNavigationVisible) openDesktopSearch else null,
                                        localFavorites = ordinaryFavorites, localHistory = ordinaryHistory,
                                        chapterHistoryRevision = accountChapterHistoryRevision,
                                        readChapterProgress = { mangaId, chapterId ->
                                            withContext(Dispatchers.IO) {
                                                model.library.awaitReady()
                                                model.library.progressFor(mangaId, chapterId)
                                            }
                                        },
                                        onAddToLibrary = model::addCloudMangaToLibrary, onContinueLocal = model::continueReading,
                                        onRefreshFavorites = {
                                            model.copyShelfSync.check()
                                            model.refreshCopyShelfMetadata()
                                        }, onRefreshHistory = model::refreshCloudHistory,
                                        cloudMetadata = copyShelfRemote.takeIf { it.owner == account.profile?.username }?.items.orEmpty(),
                                        metadataLoading = copyShelfRemote.loading,
                                        metadataError = copyShelfRemote.error,
                                        onRetryMetadata = model::refreshCopyShelfMetadata,
                                        onOpenSync = { copySyncOpen = true },
                                        onLoginWithPassword = model::loginWithPassword,
                                        onLoginWithCookie = model::loginWithCookie,
                                        onLogout = model::logout,
                                        onRefresh = model::refreshAccount,
                                        onMoreFavorites = model::moreCloudFavorites, onMoreHistory = model::moreCloudHistory,
                                        onOpen = { model.openManga(it) }, onContinue = model::continueCloudHistory,
                                        onSetFavorite = model::setCloudFavorite,
                                        onRetrySync = model::retryAccountSync,
                                    )
                                    Section.SETTINGS -> SettingsScreen(model.updates, model.library, onRequestNotifications,
                                        readBrowseCacheBytes = model::browseCacheSize, clearBrowseCache = model::clearBrowseCache,
                                        readMetadataCacheBytes = model::metadataCacheSize, clearMetadataCache = model::clearMetadataCache,
                                        contentTopPadding = chromePadding,
                                        sourceBackdrop = searchBackdrop, sourceRegistry = model.bodySources,
                                        sourcePlugins = model.sourcePlugins)
                                }
                                }
                                }
                            }
                            if (current.section == Section.SETTINGS || current.section == Section.ACCOUNT && restrictedMode) {
                                if (desktop) DesktopManagementToolbar(
                                    title = when (current.section) {
                                        Section.ACCOUNT -> "账号"; else -> "设置"
                                    },
                                    modifier = Modifier.align(Alignment.TopCenter)
                                        .onSizeChanged { chromeHeightPx = it.height }
                                        .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top)),
                                ) {
                                    Spacer(Modifier.weight(1f))
                                    navigation()
                                    if (!desktopNavigationVisible) MotionIconButton(
                                        onClick = openDesktopSearch, modifier = Modifier.size(36.dp),
                                        tooltip = "搜索 · Ctrl+K",
                                    ) { Icon(Icons.Outlined.Search, "搜索", Modifier.size(20.dp)) }
                                } else PageTitleHeader(when (current.section) {
                                        Section.ACCOUNT -> "账号"; else -> "设置"
                                    },
                                    searchBackdrop, navigation,
                                    modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { chromeHeightPx = it.height }
                                        .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                                        .padding(horizontal = 8.dp, vertical = 2.dp))
                            } else if (current.section == Section.DISCOVER && desktop) {
                                DesktopDiscoveryHeader(
                                    searching = desktopSearchContent, backdrop = searchBackdrop,
                                    onLeaveSearch = closeSearch, navigation = navigation,
                                    tabs = { tabModifier ->
                                        BrowseTabs(discoveryModes.indexOf(displayedDiscovery.mode).coerceAtLeast(0), discoveryModes.map { it.label }, tabModifier) { index ->
                                            closeSearch()
                                            browseActions.selectDiscovery(discoveryModes[index], clearQuery = true)
                                        }
                                    },
                                    input = { inputModifier ->
                                        DesktopSearchField(
                                            query = browse.query, target = browse.searchTarget,
                                            copyOnly = browse.selectedTags.isNotEmpty(),
                                            active = rootActive, focusRequest = desktopSearchRequest,
                                            backdrop = searchBackdrop, modifier = inputModifier,
                                            showScopeSelector = !restrictedMode,
                                            onOpen = { browseActions.resumeBrowseSearch(); searchExpanded = true },
                                            onQuery = browseActions::setQuery,
                                            onSubmit = browseActions::submitSearch,
                                            onTarget = browseActions::setSearchTarget,
                                            onClear = { browseActions.clearSearch(); searchExpanded = false },
                                        )
                                    },
                                    recent = {
                                        if (ordinaryHistory.isNotEmpty()) MotionIconButton(
                                            modifier = Modifier.size(36.dp), tooltip = "最近阅读",
                                            onClick = { feedback.perform(FeelEvent.Selection); recentReadingOpen = true },
                                        ) {
                                            HistoryActionIcon(active = recentReadingOpen, description = "最近阅读",
                                                modifier = Modifier.size(21.dp), tint = MaterialTheme.colorScheme.onSurface)
                                        }
                                    },
                                    ranking = {
                                        if (rankingExpanded) RankingControls(displayedDiscovery,
                                            onPeriod = { browseActions.selectDiscovery(DiscoveryMode.RANKING, period = it) },
                                            onAudience = { browseActions.selectDiscovery(DiscoveryMode.RANKING, audience = it) },
                                            onRefresh = browseActions::refresh)
                                    },
                                    modifier = Modifier.align(Alignment.TopCenter)
                                        .onSizeChanged { chromeHeightPx = it.height }
                                        .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                                        .padding(horizontal = 12.dp, vertical = 4.dp),
                                )
                            } else if (current.section == Section.DISCOVER) {
                                SegmentedGlassHeader(searchBackdrop, navigation,
                                    controlsWidth = discoveryTabsWidth,
                                    actionsWidth = discoveryActionsWidth,
                                    modifier = Modifier.align(Alignment.TopCenter).onSizeChanged { chromeHeightPx = it.height }
                                        .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    controlsAlpha = {
                                        if (current.section != Section.DISCOVER) 1f
                                        else if (searchExpanded) 0f
                                        else 1f - (searchForeground?.value ?: 0f).coerceIn(0f, 1f)
                                    },
                                    actions = {
                                        Row(Modifier.padding(horizontal = 8.dp).height(44.dp)
                                            .motionInputGate(rootActive && chromeReady),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            if (ordinaryHistory.isNotEmpty()) MotionIconButton(modifier = Modifier.size(44.dp), onClick = {
                                                feedback.perform(FeelEvent.Selection)
                                                closeSearch()
                                                recentReadingOpen = true
                                            }) {
                                                HistoryActionIcon(active = recentReadingOpen, description = "最近阅读",
                                                    modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                                            }
                                            // Match the fixed search trigger's 8dp inset in the root overlay.
                                            Spacer(Modifier.size(44.dp))
                                        }
                                    },
                                    secondary = {
                                        RankingsFromTab(searchBackdrop, rankingExpanded, rankingAnchor,
                                            enabled = rootActive && chromeReady && rankingExpanded,
                                            onEmphasisState = { rankingEmphasis = it }) {
                                            RankingControls(if (rankingExpanded) displayedDiscovery else retainedRankingState,
                                                onPeriod = { browseActions.selectDiscovery(DiscoveryMode.RANKING, period = it) },
                                                onAudience = { browseActions.selectDiscovery(DiscoveryMode.RANKING, audience = it) },
                                                onRefresh = browseActions::refresh)
                                        }
                                    }) {
                                    BrowseTabs(discoveryModes.indexOf(browse.mode).coerceAtLeast(0), discoveryModes.map { it.label },
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                            .graphicsLayer { alpha = 1f - .24f * (rankingEmphasis?.value ?: 0f) }
                                            .onGloballyPositioned { coordinates ->
                                                // BrowseTabs uses equal, stationary hit cells; measure the actual middle cell.
                                                val bounds = coordinates.boundsInRoot()
                                                val slot = bounds.width / discoveryModes.size
                                                val left = bounds.left + slot * discoveryModes.indexOf(DiscoveryMode.RANKING).coerceAtLeast(0)
                                                rankingAnchor = Rect(left, bounds.top, left + slot, bounds.bottom)
                                            }.motionInputGate(rootActive && chromeReady)) { index ->
                                        closeSearch()
                                        browseActions.selectDiscovery(discoveryModes[index], clearQuery = true)
                                    }
                                }
                            }
                        if (current.section == Section.DISCOVER && !desktop) ExpandingSearchBar(
                            expanded = searchExpanded,
                            query = browse.query,
                            placeholder = "搜索",
                            searchTarget = browse.searchTarget.takeUnless { restrictedMode },
                            scopeCopyOnly = browse.selectedTags.isNotEmpty(),
                            onSearchTarget = browseActions::setSearchTarget,
                            active = rootActive,
                            backdrop = searchBackdrop,
                            onExpandedChange = { if (it) searchExpanded = true else closeSearch() },
                            onPresenceChange = { searchPresent = it },
                            onForegroundState = { searchForeground = it },
                            onQuery = browseActions::setQuery,
                            onSearch = browseActions::submitSearch,
                            modifier = Modifier.fillMaxSize()
                                .windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) { maxHeight, searchActive ->
                            DiscoverySearchResults(
                                state = browse, expanded = searchExpanded, active = searchActive,
                                scopeInToolbar = true,
                                maxHeight = maxHeight, repository = model.repository,
                                backdrop = searchBackdrop,
                                onRetry = browseActions::retryBrowse, onMore = browseActions::loadMore,
                                onTarget = browseActions::setSearchTarget, onTag = browseActions::toggleSearchTag,
                                onRetryTags = browseActions::retrySearchTags,
                                onOpen = { manga ->
                                    keyboard?.hide()
                                    focus.clearFocus(force = true)
                                    model.openManga(manga)
                                },
                            )
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
}
}
}

@Composable
private fun rememberMangaViewModel(): MangaViewModel {
    val context = LocalContext.current
    return viewModel { MangaViewModel(context) }
}
