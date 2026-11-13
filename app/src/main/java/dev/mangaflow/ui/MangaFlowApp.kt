package dev.mangaflow.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import dev.mangaflow.ui.motion.MotionBackHandler
import dev.mangaflow.ui.motion.MotionBackState
import dev.mangaflow.ui.motion.LocalMotionBackState
import dev.mangaflow.ui.motion.motionBackPreview
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.ui.motion.MotionBottomSheet
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.update.UpdatePhase
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

private enum class Section(val title: String, val icon: ImageVector) {
    DISCOVER("发现", Icons.Outlined.Explore), LIBRARY("书架", Icons.Outlined.Bookmarks),
    DOWNLOADS("下载", Icons.Outlined.FileDownload), ACCOUNT("账号与云端", Icons.Outlined.AccountCircle),
    SETTINGS("设置", Icons.Outlined.Settings),
}
private sealed interface Frame {
    val key: String
    data class Root(val section: Section) : Frame { override val key = "root-${section.name}" }
    data class Detail(val state: DetailState) : Frame { override val key = "detail-${state.manga.id}" }
    data class Reader(val session: ReadingSession) : Frame { override val key = "reader-${session.manga.id}-${session.chapterId}-${session.collection?.key.orEmpty()}" }
}

private val Frame.depth: Int get() = when (this) {
    is Frame.Root -> 0
    is Frame.Detail -> 1
    is Frame.Reader -> 2
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MangaFlowApp(openUpdatesRequest: Int, openDownloadsRequest: Int, onRequestNotifications: () -> Unit, model: MangaViewModel = viewModel()) {
    val wallpaperContext = LocalContext.current.applicationContext
    val wallpaperStore = remember(wallpaperContext) { WallpaperStore.get(wallpaperContext) }
    val wallpaper by wallpaperStore.state.collectAsStateWithLifecycle()
    val browse by model.browse.collectAsStateWithLifecycle()
    val metricState = remember(model) { MangaMetricState(model.metrics, model::retainMetrics, model::releaseMetrics) }
    val detail by model.detail.collectAsStateWithLifecycle()
    val authorWorks by model.authorWorks.collectAsStateWithLifecycle()
    val reading by model.reading.collectAsStateWithLifecycle()
    val libraryOpening by model.libraryOpening.collectAsStateWithLifecycle()
    val favorites by model.library.favorites.collectAsStateWithLifecycle()
    val history by model.library.history.collectAsStateWithLifecycle()
    val preferences by model.library.preferences.collectAsStateWithLifecycle()
    val refreshingDownloads by model.refreshingDownloads.collectAsStateWithLifecycle()
    val account by model.account.collectAsStateWithLifecycle()
    val update by model.updates.state.collectAsStateWithLifecycle()
    var sectionName by rememberSaveable { mutableStateOf(Section.DISCOVER.name) }
    val section = Section.valueOf(sectionName)
    val changeSection: (Section) -> Unit = { destination ->
        model.cancelLibraryReading()
        sectionName = destination.name
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val drawerBackdrop = rememberHazeState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val savedState = rememberSaveableStateHolder()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var tagSearchVisible by rememberSaveable { mutableStateOf(false) }
    var recentReadingOpen by rememberSaveable { mutableStateOf(false) }
    var selectedCover by remember { mutableStateOf<MangaCoverKey?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { model.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(openUpdatesRequest) {
        if (openUpdatesRequest > 0) {
            tagSearchVisible = false
            model.clearSearch()
            model.closeReader()
            model.closeDetail()
            changeSection(Section.SETTINGS)
            drawer.close()
        }
    }
    LaunchedEffect(openDownloadsRequest) {
        if (openDownloadsRequest > 0) {
            tagSearchVisible = false
            model.clearSearch()
            model.closeReader()
            model.closeDetail()
            changeSection(Section.DOWNLOADS)
            drawer.close()
        }
    }
    val frame: Frame = reading?.let { Frame.Reader(it) } ?: detail?.let { Frame.Detail(it) } ?: Frame.Root(section)
    BackHandler(drawer.isOpen || libraryOpening != null) {
        if (drawer.isOpen) scope.launch { drawer.close() }
        else model.cancelLibraryReading()
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
        val panelHeight = (LocalConfiguration.current.screenHeightDp.dp * .72f - 60.dp).coerceAtLeast(120.dp)
        val closeTags: () -> Unit = { tagSearchVisible = false; model.clearSearch() }
        MotionBottomSheet(onDismissRequest = closeTags) { dismiss ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(value = browse.query, onValueChange = model::setQuery,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                            .semantics { contentDescription = "搜索作品、作者或标签" },
                        decorationBox = { input ->
                            Box {
                                if (browse.query.isEmpty()) Text(when (browse.searchTarget) {
                                    SearchTarget.TITLE -> "搜索作品"
                                    SearchTarget.AUTHOR -> "搜索作者"
                                    SearchTarget.TAG -> "筛选标签"
                                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                input()
                            }
                        })
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "关闭标签搜索", Modifier.size(20.dp))
                    }
                }
                DiscoverySearchResults(browse, expanded = true, active = true,
                    maxHeight = panelHeight, repository = model.repository,
                    onRetry = model::retryBrowse, onMore = model::loadMore,
                    onOpen = { manga -> selectedCover = null; dismiss(); model.openManga(manga) },
                    onTarget = model::setSearchTarget, onTag = model::toggleSearchTag,
                    onRetryTags = model::retrySearchTags)
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

    val motion = LocalMotionEnabled.current
    val density = LocalDensity.current
    var measuredDrawerWidth by remember { mutableFloatStateOf(0f) }
    val drawerWidthPx = measuredDrawerWidth.takeIf { it > 0f } ?: with(density) { 296.dp.toPx() }
    val drawerVisible by remember(drawer, drawerWidthPx) {
        derivedStateOf {
            val offset = drawer.currentOffset
            drawer.isOpen || drawer.targetValue == DrawerValue.Open ||
                (offset.isFinite() && offset > -drawerWidthPx + .5f)
        }
    }
    val routeTravel = with(density) { 24.dp.roundToPx() }
    CompositionLocalProvider(
        LocalMangaMetrics provides metricState,
        LocalWallpaperActive provides (frame is Frame.Root && wallpaper.mediaPath != null),
    ) {
    ModalNavigationDrawer(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        drawerState = drawer,
        gesturesEnabled = frame is Frame.Root,
        scrimColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(296.dp).onSizeChanged { measuredDrawerWidth = it.width.toFloat() }
                    .padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
                drawerShape = RoundedCornerShape(26.dp),
                drawerContainerColor = Color.Transparent,
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                LiquidGlassSurface(
                    state = drawerBackdrop,
                    modifier = Modifier.fillMaxHeight().fillMaxWidth(),
                    cornerRadius = 26.dp,
                    role = GlassSurfaceRole.DRAWER,
                    trackPointer = true, elevation = 0.dp,
                ) {
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(Modifier.padding(12.dp, 12.dp, 12.dp, 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("漫流", style = MaterialTheme.typography.titleLarge)
                            Text("MANGAFLOW", fontSize = 10.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Section.entries.forEach { destination ->
                        NavigationDrawerItem(
                            label = { Text(destination.title,
                                fontWeight = if (section == destination) FontWeight.SemiBold else FontWeight.Normal) },
                            selected = section == destination,
                            onClick = { changeSection(destination); scope.launch { drawer.close() } },
                            icon = { Icon(destination.icon, null, modifier = Modifier.size(21.dp)) },
                            shape = RoundedCornerShape(14.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .075f),
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                unselectedContainerColor = Color.Transparent,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .88f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .82f),
                            ),
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("MangaFlow ${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                }
            }
        },
    ) {
        Scaffold(
            modifier = Modifier
                // Capture only while opening, visible or closing; ordinary browsing pays no drawer capture cost.
                .then(if (frame is Frame.Root && drawerVisible) Modifier.mangaHazeSource(drawerBackdrop) else Modifier)
                .graphicsLayer {
                // Vaul's backdrop relationship follows the actual drag, without a second spring.
                val offset = drawer.currentOffset
                val progress = if (motion && frame is Frame.Root && offset.isFinite())
                    (1f + offset / drawerWidthPx).coerceIn(0f, 1f) else 0f
                transformOrigin = TransformOrigin(1f, .5f)
                scaleX = 1f - .035f * progress
                scaleY = scaleX
                translationX = 8.dp.toPx() * progress
                shape = RoundedCornerShape((20f * progress).dp)
                clip = progress > 0f
            },
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
        ) { _ ->
            Box(Modifier.fillMaxSize()) {
            SharedTransitionLayout {
            val coverScope = this
            AnimatedContent(
                targetState = frame,
                contentKey = { it.key },
                transitionSpec = {
                    if (!motion) (fadeIn(snap()) togetherWith fadeOut(snap())).using(null)
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
                val currentBack = remember(current.key) { MotionBackState() }
                MotionBackHandler(
                    enabled = current.key == frame.key && !drawer.isOpen && libraryOpening == null &&
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
                        repository = model.repository, libraryStore = model.library, downloadStore = model.downloads,
                        communityRepository = model.community,
                        reviewRepository = model.reviews,
                        onBack = model::closeReader, onRequestNotificationPermission = onRequestNotifications,
                        onChapterVisible = model::onChapterVisible,
                        onNearChapterEnd = model::prefetchNextChapter,
                        chaptersLoading = current.session.chaptersLoading,
                        chaptersError = current.session.chaptersError,
                        onRetryChapters = model::retryReadingChapters,
                        active = current.key == frame.key,
                    )
                    is Frame.Detail -> savedState.SaveableStateProvider(current.key) {
                        val downloadFlow = remember(model, current.state.manga.id) {
                            model.downloads.observeManga(current.state.manga.id)
                        }
                        val downloads by downloadFlow.collectAsStateWithLifecycle(
                            initialValue = model.downloads.entries.value.filter { it.manga.id == current.state.manga.id })
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
                                volumeImageHeaders = { chapter -> model.repository.imageHeaders(current.state.manga.id, chapter.id) },
                                onBack = model::closeDetail, onRetry = model::retryDetail,
                                onToggleFavorite = { model.toggleFavorite(current.state.manga) },
                                onRead = model::readChapter, onAuthor = model::openAuthor,
                                onTag = { manga, tag -> model.openTagSearch(manga, tag); tagSearchVisible = true },
                                onRestart = model::restartChapter,
                                onContinue = model::continueReading,
                                onDownload = { onRequestNotifications(); model.downloadChapter(it) },
                                onReviewMore = model::moreReviews, onReviewRetry = model::retryReviews,
                                onReviewMatch = model::matchReviews, onReviewRelink = model::relinkReviews,
                                loadReplies = model::reviewReplies,
                                active = current.key == frame.key && authorWorks == null && !tagSearchVisible,
                                wallpaper = {
                                    if (wallpaper.mediaPath != null) AppWallpaper(wallpaperStore, Modifier.fillMaxSize(),
                                        active = current.key == frame.key && authorWorks == null && !tagSearchVisible)
                                },
                            )
                        }
                    }
                    is Frame.Root -> savedState.SaveableStateProvider(current.key) {
                        val searchBackdrop = rememberHazeState()
                        val backdropWallpaper: @Composable () -> Unit = {
                            if (wallpaper.mediaPath != null) {
                                AppWallpaper(wallpaperStore, Modifier.fillMaxSize(),
                                    active = !drawerVisible && !recentReadingOpen && !tagSearchVisible)
                            }
                        }
                        val chromeDensity = LocalDensity.current
                        val discoveryBackground = model.browseBackground(browse)
                        val displayedDiscovery = if (!browse.isSearch) browse else discoveryBackground
                        val rankingPresence = remember {
                            MutableTransitionState(displayedDiscovery.mode == DiscoveryMode.RANKING)
                        }
                        rankingPresence.targetState = displayedDiscovery.mode == DiscoveryMode.RANKING
                        var chromeHeightPx by remember {
                            mutableIntStateOf(with(chromeDensity) {
                                (if (current.section == Section.DISCOVER && rankingPresence.targetState) 88.dp else 44.dp).toPx().toInt()
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
                        val rootActive = current.key == frame.key && !drawer.isOpen && !recentReadingOpen && authorWorks == null && !tagSearchVisible &&
                            !currentBack.gesturing && !currentBack.completed
                        val closeSearch: () -> Unit = {
                            keyboard?.hide()
                            focus.clearFocus(force = true)
                            searchExpanded = false
                            model.clearSearch()
                        }
                        Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        Box(Modifier.fillMaxSize()) {
                            val hasUpdate = update.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.READY)
                            val navigation: @Composable () -> Unit = {
                                Box {
                                    MotionIconButton(modifier = Modifier.size(44.dp), onClick = {
                                        closeSearch()
                                        model.cancelLibraryReading()
                                        scope.launch { drawer.open() }
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
                                if (hasUpdate && current.section != Section.DISCOVER) {
                                    MotionIconButton(modifier = Modifier.size(44.dp), onClick = { changeSection(Section.SETTINGS) }) {
                                        Icon(Icons.Outlined.SystemUpdateAlt, "有新版本", Modifier.size(21.dp))
                                    }
                                }
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
                                Modifier.mangaHazeSource(searchBackdrop).background(MaterialTheme.colorScheme.background)
                                else Modifier)) {
                                // The full backdrop includes the pixels beneath the menu button.
                                // Content clearance belongs to the foreground, outside this capture's bounds.
                                if (rootCapturesBackground) backdropWallpaper()
                                Box(Modifier.fillMaxSize().padding(top = if (rootCapturesBackground) chromePadding else 0.dp)) {
                                when (current.section) {
                                    Section.DISCOVER -> DiscoverScreen(
                                        state = browse, repository = model.repository,
                                        discoverColumns = preferences.discoverColumns,
                                        updateColumns = preferences.updateColumns,
                                        backgroundState = discoveryBackground,
                                        searchExpanded = searchVisible,
                                        searchBackdrop = searchBackdrop,
                                        wallpaper = backdropWallpaper,
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
                                        wallpaper = backdropWallpaper,
                                        onOpen = model::readFromLibrary, onDetails = { model.openManga(it) },
                                        onContinue = model::continueReading,
                                        onRemoveFavorite = model::removeFavorite,
                                        onRemoveHistory = model::removeHistory,
                                        onDiscover = { changeSection(Section.DISCOVER) },
                                        onRetryOpen = model::retryLibraryReading, onCancelOpen = model::cancelLibraryReading,
                                    )
                                    Section.DOWNLOADS -> DownloadsScreen(store = model.downloads, repository = model.repository, onRead = model::openDownload,
                                        onRetry = model::retryDownload, backdrop = searchBackdrop, navigation = navigation, toolbar = toolbar,
                                        wallpaper = backdropWallpaper,
                                        refreshingIds = refreshingDownloads, history = history)
                                    Section.ACCOUNT -> AccountScreen(
                                        state = account, repository = model.repository,
                                        onLoginWithCookie = model::loginWithCookie, onLogin = model::login,
                                        onLogout = model::logout,
                                        onRefresh = model::refreshAccount,
                                        onMoreFavorites = model::moreCloudFavorites, onMoreHistory = model::moreCloudHistory,
                                        onOpen = { model.openManga(it) }, onContinue = model::continueCloudHistory,
                                        onSetFavorite = model::setCloudFavorite,
                                        onRetrySync = model::retryAccountSync,
                                    )
                                    Section.SETTINGS -> SettingsScreen(model.updates, model.library, onRequestNotifications,
                                        readBrowseCacheBytes = model::browseCacheSize, clearBrowseCache = model::clearBrowseCache,
                                        readMetadataCacheBytes = model::metadataCacheSize, clearMetadataCache = model::clearMetadataCache)
                                }
                                }
                            }
                            if (current.section in setOf(Section.ACCOUNT, Section.SETTINGS)) {
                                PageTitleHeader(if (current.section == Section.ACCOUNT) "账号" else "设置",
                                    searchBackdrop, navigation,
                                    modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 2.dp)
                                        .onSizeChanged { chromeHeightPx = it.height },
                                    actions = if (hasUpdate) ({ toolbar {} }) else null)
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
                                        AnimatedVisibility(visibleState = rankingPresence,
                                            enter = fadeIn(tween(if (motion) MangaMotion.Content else 0)) +
                                                expandVertically(tween(if (motion) MangaMotion.Content else 0)),
                                            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                                                shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
                                            LiquidGlassSurface(searchBackdrop,
                                                Modifier.padding(start = 50.dp, top = 2.dp).fillMaxWidth(),
                                                cornerRadius = 22.dp, role = GlassSurfaceRole.NAVIGATION,
                                                trackPointer = true) {
                                                RankingControls(displayedDiscovery,
                                                    onPeriod = { model.selectDiscovery(displayedDiscovery.mode, period = it) },
                                                    onAudience = { model.selectDiscovery(displayedDiscovery.mode, audience = it) },
                                                    onRefresh = model::refresh,
                                                    modifier = Modifier.motionInputGate(rootActive && chromeReady &&
                                                        displayedDiscovery.mode == DiscoveryMode.RANKING))
                                            }
                                        }
                                    }) {
                                    BrowseTabs(browse.mode.ordinal, DiscoveryMode.entries.map { it.label },
                                        Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                                            .motionInputGate(rootActive && chromeReady)) { index ->
                                        closeSearch()
                                        model.selectDiscovery(DiscoveryMode.entries[index], clearQuery = true)
                                    }
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
                            onSearch = model::refresh,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 2.dp),
                        ) { maxHeight, searchActive ->
                            DiscoverySearchResults(
                                state = browse, expanded = searchExpanded, active = searchActive,
                                maxHeight = maxHeight, repository = model.repository,
                                onRetry = model::retryBrowse, onMore = model::loadMore,
                                onTarget = model::setSearchTarget, onTag = model::toggleSearchTag,
                                onRetryTags = model::retrySearchTags,
                                onOpen = { manga -> selectedCover = null; closeSearch(); model.openManga(manga) },
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

internal tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}
