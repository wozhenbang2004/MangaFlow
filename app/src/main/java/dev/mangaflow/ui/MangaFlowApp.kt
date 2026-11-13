package dev.mangaflow.ui

import dev.mangaflow.data.chapterSourceMangaId

import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import dev.mangaflow.BuildConfig
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.DetailState
import dev.mangaflow.MangaViewModel
import dev.mangaflow.ReadingSession
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.reader.ReaderScreen
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
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

private enum class Section(val title: String, val icon: ImageVector) {
    DISCOVER("发现", Icons.Outlined.Explore), LIBRARY("书架", Icons.Outlined.Bookmarks),
    DOWNLOADS("下载", Icons.Outlined.FileDownload), ACCOUNT("账号与云端", Icons.Outlined.AccountCircle),
    SETTINGS("设置", Icons.Outlined.Settings),
}
/** A new reference owns the motion; cancellation cannot replay a stale destination. */
private class DrawerAction(val target: DrawerValue, val destination: Section? = null) {
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
    data class Root(val section: Section) : Frame { override val key = "root-${section.name}" }
    data class Detail(val state: DetailState) : Frame { override val key = "detail-${state.manga.id}" }
    data class Reader(val session: ReadingSession) : Frame { override val key = "reader-${session.manga.id}-${session.collection?.key.orEmpty()}" }
}

private val Frame.depth: Int get() = when (this) {
    is Frame.Root -> 0
    is Frame.Detail -> 1
    is Frame.Reader -> 2
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MangaFlowApp(openUpdatesRequest: Int, openDownloadsRequest: Int, onRequestNotifications: () -> Unit, model: MangaViewModel = viewModel()) {
    val windowSize = LocalMangaWindow.current
    val wallpaperContext = LocalContext.current.applicationContext
    val wallpaperStore = remember(wallpaperContext) { WallpaperStore.get(wallpaperContext) }
    val wallpaper by wallpaperStore.state.collectAsStateWithLifecycle()
    val browse by model.browse.collectAsStateWithLifecycle()
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
    val favorites by model.library.favorites.collectAsStateWithLifecycle()
    val history by model.library.history.collectAsStateWithLifecycle()
    val preferences by model.library.preferences.collectAsStateWithLifecycle()
    val refreshingDownloads by model.refreshingDownloads.collectAsStateWithLifecycle()
    val account by model.account.collectAsStateWithLifecycle()
    val update by model.updates.state.collectAsStateWithLifecycle()
    var sectionName by rememberSaveable { mutableStateOf(Section.DISCOVER.name) }
    val section = Section.valueOf(sectionName)
    var drawerAction by remember { mutableStateOf<DrawerAction?>(null) }
    var drawerRootTransition by remember { mutableStateOf<Section?>(null) }
    val rootOpacity = remember { Animatable(1f) }
    val rootHandoff by remember {
        derivedStateOf { drawerAction?.let { it.destination != null && !it.rootCommitted } == true }
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
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var tagSearchVisible by rememberSaveable { mutableStateOf(false) }
    var recentReadingOpen by rememberSaveable { mutableStateOf(false) }
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
            val changesRoot = destination != null && sectionName != destination.name &&
                reading == null && detail == null
            if (changesRoot) {
                // Show the selection before moving its surface. Never wait for a whole spring.
                if (motion && drawer.isOpen) withFrameNanos { }
                if (drawerAction !== action || reading != null || detail != null) return@LaunchedEffect
                model.cancelLibraryReading()
                drawerRootTransition = destination
                if (motion) rootOpacity.snapTo(minOf(rootOpacity.value, MangaMotion.RootEntryAlpha))
                // A click commits the destination; a subsequent drag owns only native drawer travel.
                sectionName = requireNotNull(destination).name
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
    LaunchedEffect(openUpdatesRequest) {
        if (openUpdatesRequest > 0) {
            tagSearchVisible = false
            model.clearSearch()
            model.closeReader()
            model.closeDetail()
            changeSection(Section.SETTINGS)
        }
    }
    LaunchedEffect(openDownloadsRequest) {
        if (openDownloadsRequest > 0) {
            tagSearchVisible = false
            model.clearSearch()
            model.closeReader()
            model.closeDetail()
            changeSection(Section.DOWNLOADS)
        }
    }
    val frame: Frame = reading?.let { Frame.Reader(it) } ?: detail?.let { Frame.Detail(it) } ?: Frame.Root(section)
    sourceManagement?.let { state ->
        UnifiedSourceManager(state, drawerBackdrop, onDismiss = model::closeSourceManagement,
            onSearch = model::searchBodySource, onAttach = model::attachBodySource,
            onDetach = model::detachBodySource, onPrefer = model::preferBodySource,
            onRetryAll = model::retrySourceDiscovery, onChooseDomain = model::chooseSourceDomain,
            onChooseSource = model::chooseBodySource)
    }
    sourceResumeChoice?.let { choice ->
        SourceResumePrompt(choice, drawerBackdrop, onChoose = model::confirmSourceResumeChoice,
            onDismiss = model::cancelSourceResumeChoice)
    }
    val focus = LocalFocusManager.current
    LaunchedEffect(frame.key) { focus.clearFocus(force = true); keyboard?.hide() }
    if (recentReadingOpen) {
        RecentReadingSheet(history, model.repository, model.library,
            onDismiss = { recentReadingOpen = false },
            onContinue = { model.continueReading(it) },
            onRemoveHistory = model::removeHistory)
    }
    authorWorks?.let { author ->
        androidx.compose.runtime.key(author.manga.id, author.name) {
            AuthorWorksSheet(author, model.repository, onDismiss = model::closeAuthor,
                onMore = model::moreAuthor, onRetry = model::retryAuthor,
                onOpen = { model.openManga(it) })
        }
    }
    if (tagSearchVisible) {
        CompositionLocalProvider(LocalMangaMetrics provides metricState) {
        val panelHeight = (windowSize.height * .72f - 60.dp).coerceAtLeast(1.dp)
        val closeTags: () -> Unit = { tagSearchVisible = false; model.clearSearch() }
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
                    onRetry = model::retryBrowse, onMore = model::loadMore,
                    onOpen = { manga -> selectedCover = null; dismiss(); model.openManga(manga) },
                    onTarget = { model.setSearchTarget(SearchTarget.TAG) }, onTag = model::toggleSearchTag,
                    onRetryTags = model::retrySearchTags, tagsOnly = true, onTagQuery = model::setQuery)
            }
        }
        }
    }
    val activity = LocalContext.current.activityOrNull()
    val darkSystem = isSystemInDarkTheme()
    SideEffect {
        if (frame is Frame.Root && activity != null) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !darkSystem
                isAppearanceLightNavigationBars = !darkSystem
            }
        }
    }

    val density = LocalDensity.current
    val drawerWidth = (296.dp + 48.dp * (windowSize.fontScale - 1f)).coerceAtMost(360.dp)
        .coerceAtMost((windowSize.width - 24.dp).coerceAtLeast(1.dp))
    var measuredDrawerWidth by remember { mutableFloatStateOf(0f) }
    val drawerWidthPx = measuredDrawerWidth.takeIf { it > 0f } ?: with(density) { drawerWidth.toPx() }
    val drawerVisible by remember(drawer, drawerWidthPx) {
        derivedStateOf {
            val offset = drawer.currentOffset
            drawer.isOpen || drawer.targetValue == DrawerValue.Open ||
                (offset.isFinite() && offset > -drawerWidthPx + .5f)
        }
    }
    val drawerExposed by remember(drawer, drawerWidthPx) {
        derivedStateOf {
            drawer.currentOffset.let { it.isFinite() && it > -drawerWidthPx + .5f }
        }
    }
    val drawerCanSelect = frame is Frame.Root && drawerExposed
    val drawerTargets = remember { mutableMapOf<Section, Rect>() }
    val canSelectDrawer: () -> Boolean = {
        frame is Frame.Root && drawer.currentOffset.let { it.isFinite() && it > -drawerWidthPx + .5f }
    }
    val selectDrawer: (Section) -> Unit = { destination ->
        if (canSelectDrawer()) {
            if ((drawerAction?.destination ?: Section.valueOf(sectionName)) != destination)
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
        LocalMangaMetrics provides metricState,
        LocalWallpaperActive provides (frame is Frame.Root && wallpaper.mediaPath != null),
    ) {
    ModalNavigationDrawer(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            .drawerSelectionTapInput(drawerTargets, canSelectDrawer,
                { drawer.isAnimationRunning && !drawerScroll.isScrollInProgress }, selectDrawer),
        drawerState = drawer,
        gesturesEnabled = frame is Frame.Root,
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
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(drawerScroll)) {
                    CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    MotionNavigationColumn(
                        selected = (drawerAction?.destination ?: section).ordinal,
                        itemCount = Section.entries.size,
                    ) { index, itemModifier ->
                        val destination = Section.entries[index]
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
                                    Text(destination.title, fontWeight = FontWeight.Medium)
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
                    Text("MangaFlow ${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    else {
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
                        selection.destination == current.section) {
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
                        searchExpanded && selectedCover?.placement?.startsWith("search-") == true) {
                        searchExpanded = false
                        model.clearSearch()
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
                        reading != null -> model.closeReader()
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
                        onBack = model::closeReader, onRequestNotificationPermission = onRequestNotifications,
                        onChapterVisible = model::onChapterVisible,
                        onNearChapterEnd = model::prefetchNextChapter,
                        chaptersLoading = current.session.chaptersLoading,
                        chaptersError = current.session.chaptersError,
                        onRetryChapters = model::retryReadingChapters,
                        active = current.key == frame.key && sourceManagement == null && sourceResumeChoice == null,
                        onReadVariant = model::readReaderChapterVariant,
                        onDownloadVariant = { chapter, variant ->
                            onRequestNotifications()
                            model.downloadChapterVariant(current.session.manga, chapter, variant)
                        },
                        onSetPreferredSource = model::preferBodySource,
                        onClearChapterSource = model::clearChapterSource,
                        onCancelSourceSelection = model::cancelSourceSelection,
                        sourceLoading = sourceOperation.loading, sourceError = sourceOperation.error,
                    )
                    is Frame.Detail -> savedState.SaveableStateProvider(current.key) {
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
                                loading = current.state.loading, error = current.state.error,
                                reviews = current.state.reviews, reviewsLoading = current.state.reviewsLoading,
                                isFavorite = favorites.any { it.id == current.state.manga.id },
                                progress = history.firstOrNull { it.manga.id == current.state.manga.id },
                                chapterPositions = chapterPositions,
                                downloadEntries = downloads,
                                loadVolumeCover = { chapter, refresh -> model.volumeCover(current.state.manga.id, chapter, refresh) },
                                volumeImageHeaders = { chapter -> model.repository.imageHeaders(chapterSourceMangaId(current.state.manga.id, chapter), chapter.id) },
                                onBack = model::closeDetail, onRetry = model::retryDetail,
                                onToggleFavorite = { model.toggleFavorite(current.state.manga) },
                                onRead = model::readChapter, onAuthor = model::openAuthor,
                                onReadVariant = model::readChapterVariant,
                                onSetPreferredSource = model::preferBodySource,
                                onClearChapterSource = model::clearChapterSource,
                                onSplitVariant = model::splitChapterSource,
                                onRelinkVariant = model::relinkChapterSource,
                                onManageSources = model::manageSources,
                                onCancelSourceSelection = model::cancelSourceSelection,
                                sourceLoading = sourceOperation.loading,
                                sourcesChecking = sourceDiscovery[current.state.manga.id]?.values?.any {
                                    it.status == dev.mangaflow.data.SourceDiscoveryStatus.SEARCHING
                                } == true,
                                sourceError = sourceOperation.error ?: sourceCatalogErrors[current.state.manga.id]
                                    ?.values?.takeIf { it.isNotEmpty() }?.joinToString("；"),
                                onTag = { manga, tag -> model.openTagSearch(manga, tag); tagSearchVisible = true },
                                onRestart = model::restartChapter,
                                onContinue = model::continueReading,
                                onDownload = { onRequestNotifications(); model.downloadChapter(it) },
                                onDownloadVariant = { chapter, variant ->
                                    onRequestNotifications()
                                    model.downloadChapterVariant(current.state.manga, chapter, variant)
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
                    is Frame.Root -> {
                        val searchBackdrop = rememberHazeState()
                        val rootWallpaperActive = current.key == frame.key &&
                            !drawerVisible && !recentReadingOpen && !tagSearchVisible
                        val chromeDensity = LocalDensity.current
                        val discoveryBackground = model.browseBackground(browse)
                        val displayedDiscovery = if (!browse.isSearch) browse else discoveryBackground
                        val rankingExpanded = displayedDiscovery.mode == DiscoveryMode.RANKING
                        var rankingAnchor by remember { mutableStateOf(Rect.Zero) }
                        var chromeHeightPx by remember(current.section) {
                            mutableIntStateOf(with(chromeDensity) {
                                (if (current.section == Section.DISCOVER && rankingExpanded) 88.dp else 44.dp).toPx().toInt()
                            })
                        }
                        val chromePadding = with(chromeDensity) { chromeHeightPx.toDp() } + HeaderContentGap
                        val discoveryTabsWidth = with(chromeDensity) {
                            val labelSlot = maxOf(52.dp, 14.sp.toDp() * 2 + 24.dp)
                            labelSlot * DiscoveryMode.entries.size + 8.dp
                        }
                        var searchPresent by remember { mutableStateOf(searchExpanded) }
                        val searchVisible = searchExpanded || searchPresent
                        var searchForeground by remember { mutableStateOf<State<Float>?>(null) }
                        val chromeReady by remember {
                            derivedStateOf { !searchExpanded && (searchForeground?.value ?: 0f) <= .02f }
                        }
                        val rootActive = current.key == frame.key && !drawerVisible && !rootHandoff && !recentReadingOpen && authorWorks == null && !tagSearchVisible &&
                            !currentBack.gesturing && !currentBack.completed
                        val closeSearch: () -> Unit = {
                            keyboard?.hide()
                            focus.clearFocus(force = true)
                            searchExpanded = false
                            model.clearSearch()
                        }
                        Box(Modifier.fillMaxSize().windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                        // The persistent lower source holds the real media; foreground page
                        // sources and headers sample it without owning the player lifecycle.
                        Box(Modifier.fillMaxSize().mangaHazeSource(searchBackdrop, zIndex = -1f)
                            .background(MaterialTheme.colorScheme.background)) {
                            AppWallpaper(wallpaperStore, Modifier.fillMaxSize(), active = rootWallpaperActive)
                        }
                        val contentWidth = when (current.section) {
                            Section.SETTINGS -> MangaLayout.SettingsWidth
                            Section.ACCOUNT -> MangaLayout.AccountWidth
                            Section.DOWNLOADS -> MangaLayout.ListWidth
                            else -> MangaLayout.BrowseWidth
                        }
                        Box(Modifier.widthIn(max = contentWidth).fillMaxSize().align(Alignment.TopCenter).graphicsLayer {
                            // Only the incoming foreground settles; the real backdrop never disappears.
                            alpha = rootOpacity.value
                            translationY = ((1f - rootOpacity.value) / (1f - MangaMotion.RootEntryAlpha))
                                .coerceIn(0f, 1f) * 6.dp.toPx()
                        }.motionInputGate(!rootHandoff)) {
                            val hasUpdate = update.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.READY)
                            val navigation: @Composable () -> Unit = {
                                Box {
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
                            val rootCapturesBackground = current.section in setOf(Section.ACCOUNT, Section.SETTINGS)
                            Box(Modifier.fillMaxSize().then(if (rootCapturesBackground)
                                Modifier.mangaHazeSource(searchBackdrop)
                                else Modifier)) {
                                // The full backdrop includes the pixels beneath the menu button.
                                // Settings reserves chrome inside its scrolling content, so it
                                // can pass beneath the floating header without an inset clip.
                                Box(Modifier.fillMaxSize().padding(top = if (current.section == Section.ACCOUNT) chromePadding else 0.dp)) {
                                savedState.SaveableStateProvider(current.key) {
                                when (current.section) {
                                    Section.DISCOVER -> DiscoverScreen(
                                        state = browse, repository = model.repository,
                                        discoverColumns = preferences.discoverColumns,
                                        updateColumns = preferences.updateColumns,
                                        backgroundState = discoveryBackground,
                                        searchExpanded = searchVisible,
                                        searchBackdrop = searchBackdrop,
                                        backgroundInParent = true,
                                        contentTopPadding = chromePadding,
                                        active = rootActive,
                                        canAutoAdvance = rootActive && !searchVisible,
                                        onRefresh = model::refresh,
                                        onRetry = model::retryBrowse, onMore = model::loadMore,
                                        onOpen = { model.openManga(it) },
                                        isFavorite = { manga -> favorites.any { it.id == manga.id } },
                                        onToggleFavorite = model::toggleFavorite,
                                    )
                                    Section.LIBRARY -> LibraryScreen(
                                        favorites = favorites, history = history,
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
                                    )
                                    Section.DOWNLOADS -> DownloadsScreen(store = model.downloads, repository = model.repository, onRead = model::openDownload,
                                        onRetry = model::retryDownload, backdrop = searchBackdrop, navigation = navigation, toolbar = toolbar,
                                        backgroundInParent = true,
                                        active = rootActive && !searchVisible, refreshingIds = refreshingDownloads, history = history)
                                    Section.ACCOUNT -> AccountScreen(
                                        state = account, repository = model.repository,
                                        active = rootActive,
                                        onLoginWithCookie = model::loginWithCookie,
                                        onBeginWebsiteLogin = model::beginWebsiteLogin,
                                        onCancelWebsiteLogin = model::cancelWebsiteLogin,
                                        onWebsiteToken = model::loginWithWebsiteToken,
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
                                        contentTopPadding = chromePadding)
                                }
                                }
                                }
                            }
                            if (current.section in setOf(Section.ACCOUNT, Section.SETTINGS)) {
                                PageTitleHeader(if (current.section == Section.ACCOUNT) "账号" else "设置",
                                    searchBackdrop, navigation,
                                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 2.dp)
                                        .onSizeChanged { chromeHeightPx = it.height })
                            } else if (current.section == Section.DISCOVER) {
                                SegmentedGlassHeader(searchBackdrop, navigation,
                                    controlsWidth = discoveryTabsWidth,
                                    actionsWidth = (if (history.isEmpty()) 44.dp else 88.dp) + 16.dp,
                                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 2.dp)
                                        .onSizeChanged { chromeHeightPx = it.height },
                                    controlsAlpha = {
                                        if (current.section != Section.DISCOVER) 1f
                                        else if (searchExpanded) 0f
                                        else 1f - (searchForeground?.value ?: 0f).coerceIn(0f, 1f)
                                    },
                                    actions = {
                                        Row(Modifier.padding(horizontal = 8.dp).height(44.dp)
                                            .motionInputGate(rootActive && chromeReady),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            if (history.isNotEmpty()) MotionIconButton(modifier = Modifier.size(44.dp), onClick = {
                                                closeSearch()
                                                recentReadingOpen = true
                                            }) {
                                                Icon(Icons.Outlined.History, "最近阅读", Modifier.size(22.dp))
                                            }
                                            // Match the fixed search trigger's 8dp inset in the root overlay.
                                            Spacer(Modifier.size(44.dp))
                                        }
                                    },
                                    secondary = {
                                        RankingsFromTab(searchBackdrop, rankingExpanded, rankingAnchor,
                                            enabled = rootActive && chromeReady && rankingExpanded) {
                                            RankingControls(displayedDiscovery,
                                                onPeriod = { model.selectDiscovery(displayedDiscovery.mode, period = it) },
                                                onAudience = { model.selectDiscovery(displayedDiscovery.mode, audience = it) },
                                                onRefresh = model::refresh)
                                        }
                                    }) {
                                    BrowseTabs(browse.mode.ordinal, DiscoveryMode.entries.map { it.label },
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                            .onGloballyPositioned { coordinates ->
                                                // BrowseTabs uses equal, stationary hit cells; measure the actual middle cell.
                                                val bounds = coordinates.boundsInRoot()
                                                val slot = bounds.width / DiscoveryMode.entries.size
                                                val left = bounds.left + slot * DiscoveryMode.RANKING.ordinal
                                                rankingAnchor = Rect(left, bounds.top, left + slot, bounds.bottom)
                                            }.motionInputGate(rootActive && chromeReady)) { index ->
                                        closeSearch()
                                        model.selectDiscovery(DiscoveryMode.entries[index], clearQuery = true)
                                    }
                                }
                            }
                        if (current.section == Section.DISCOVER) ExpandingSearchBar(
                            expanded = searchExpanded,
                            query = browse.query,
                            placeholder = when (browse.searchTarget) {
                                SearchTarget.TITLE -> "搜索作品"
                                SearchTarget.AUTHOR -> "搜索作者"
                                SearchTarget.TAG -> "筛选标签"
                            },
                            active = rootActive,
                            backdrop = searchBackdrop,
                            onExpandedChange = { if (it) searchExpanded = true else closeSearch() },
                            onPresenceChange = { searchPresent = it },
                            onForegroundState = { searchForeground = it },
                            onQuery = model::setQuery,
                            onSearch = model::submitSearch,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 2.dp),
                        ) { maxHeight, searchActive ->
                            DiscoverySearchResults(
                                state = browse, expanded = searchExpanded, active = searchActive,
                                maxHeight = maxHeight, repository = model.repository,
                                backdrop = searchBackdrop,
                                onRetry = model::retryBrowse, onMore = model::loadMore,
                                onTarget = model::setSearchTarget, onTag = model::toggleSearchTag,
                                onRetryTags = model::retrySearchTags,
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

internal tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}
