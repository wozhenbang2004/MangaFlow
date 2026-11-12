package dev.mangaflow.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import dev.mangaflow.BuildConfig
import dev.mangaflow.DetailState
import dev.mangaflow.MangaViewModel
import dev.mangaflow.ReadingSession
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.reader.ReaderScreen
import dev.mangaflow.update.UpdatePhase
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
    data class Reader(val session: ReadingSession) : Frame { override val key = "reader-${session.manga.id}-${session.chapterId}" }
}

@Composable
fun MangaFlowApp(openUpdatesRequest: Int, openDownloadsRequest: Int, onRequestNotifications: () -> Unit, model: MangaViewModel = viewModel()) {
    val browse by model.browse.collectAsStateWithLifecycle()
    val metrics by model.metrics.collectAsStateWithLifecycle()
    val detail by model.detail.collectAsStateWithLifecycle()
    val reading by model.reading.collectAsStateWithLifecycle()
    val favorites by model.library.favorites.collectAsStateWithLifecycle()
    val history by model.library.history.collectAsStateWithLifecycle()
    val downloads by model.downloads.entries.collectAsStateWithLifecycle()
    val refreshingDownloads by model.refreshingDownloads.collectAsStateWithLifecycle()
    val account by model.account.collectAsStateWithLifecycle()
    val update by model.updates.state.collectAsStateWithLifecycle()
    var sectionName by rememberSaveable { mutableStateOf(Section.DISCOVER.name) }
    val section = Section.valueOf(sectionName)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val savedState = rememberSaveableStateHolder()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var recentReadingOpen by rememberSaveable { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { model.events.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(openUpdatesRequest) {
        if (openUpdatesRequest > 0) {
            model.closeReader()
            model.closeDetail()
            sectionName = Section.SETTINGS.name
            drawer.close()
        }
    }
    LaunchedEffect(openDownloadsRequest) {
        if (openDownloadsRequest > 0) {
            model.closeReader()
            model.closeDetail()
            sectionName = Section.DOWNLOADS.name
            drawer.close()
        }
    }
    BackHandler(reading != null || detail != null || drawer.isOpen || section != Section.DISCOVER) {
        when {
            reading != null -> model.closeReader()
            detail != null -> model.closeDetail()
            drawer.isOpen -> scope.launch { drawer.close() }
            else -> sectionName = Section.DISCOVER.name
        }
    }
    val frame: Frame = reading?.let { Frame.Reader(it) } ?: detail?.let { Frame.Detail(it) } ?: Frame.Root(section)
    if (recentReadingOpen && frame is Frame.Root && section == Section.DISCOVER) {
        RecentReadingSheet(history, model.repository,
            onDismiss = { recentReadingOpen = false },
            onContinue = { recentReadingOpen = false; model.continueReading(it) })
    }
    val activity = LocalContext.current.activityOrNull()
    val darkSystem = isSystemInDarkTheme()
    SideEffect {
        if (frame !is Frame.Reader && activity != null) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !darkSystem
                isAppearanceLightNavigationBars = !darkSystem
            }
        }
    }

    CompositionLocalProvider(LocalMangaMetrics provides MangaMetricState(metrics, model::retainMetrics, model::releaseMetrics)) {
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = frame is Frame.Root,
        scrimColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.22f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(296.dp),
                drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
                drawerContainerColor = MaterialTheme.colorScheme.background,
                drawerTonalElevation = 0.dp,
            ) {
                Column(Modifier.fillMaxHeight().statusBarsPadding().padding(20.dp)) {
                    Row(Modifier.padding(12.dp, 20.dp, 12.dp, 36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.MenuBook, contentDescription = null, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("漫流", style = MaterialTheme.typography.titleLarge)
                            Text("MANGAFLOW", fontSize = 10.sp, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Section.entries.forEach { destination ->
                        NavigationDrawerItem(
                            label = { Text(destination.title) },
                            selected = section == destination,
                            onClick = { sectionName = destination.name; scope.launch { drawer.close() } },
                            icon = { Icon(destination.icon, null, modifier = Modifier.size(21.dp)) },
                            shape = RoundedCornerShape(14.dp),
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                unselectedContainerColor = MaterialTheme.colorScheme.background,
                            ),
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("MangaFlow ${BuildConfig.VERSION_NAME}", modifier = Modifier.padding(12.dp).navigationBarsPadding(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
        ) { _ ->
            AnimatedContent(
                targetState = frame,
                contentKey = { it.key },
                transitionSpec = { (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 24 }) togetherWith fadeOut(tween(100)) },
                label = "page",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                when (current) {
                    is Frame.Reader -> ReaderScreen(
                        manga = current.session.manga, chapters = current.session.chapters,
                        initialChapterId = current.session.chapterId,
                        repository = model.repository, libraryStore = model.library, downloadStore = model.downloads,
                        communityRepository = model.community,
                        onBack = model::closeReader, onRequestNotificationPermission = onRequestNotifications,
                        onChapterVisible = model::onChapterVisible,
                        onNearChapterEnd = model::prefetchNextChapter,
                        chaptersLoading = current.session.chaptersLoading,
                        chaptersError = current.session.chaptersError,
                        onRetryChapters = model::retryReadingChapters,
                    )
                    is Frame.Detail -> savedState.SaveableStateProvider(current.key) {
                        DetailScreen(
                            manga = current.state.manga, detail = current.state.detail,
                            loading = current.state.loading, error = current.state.error,
                            reviews = current.state.reviews, reviewsLoading = current.state.reviewsLoading,
                            isFavorite = favorites.any { it.id == current.state.manga.id },
                            progress = history.firstOrNull { it.manga.id == current.state.manga.id },
                            downloadEntries = downloads,
                            onBack = model::closeDetail, onRetry = model::retryDetail,
                            onToggleFavorite = { model.toggleFavorite(current.state.manga) },
                            onRead = model::readChapter,
                            onContinue = model::continueReading,
                            onDownload = { onRequestNotifications(); model.downloadChapter(it) },
                            onReviewMore = model::moreReviews, onReviewRetry = model::retryReviews,
                            onReviewMatch = model::matchReviews, onReviewRelink = model::relinkReviews,
                            loadReplies = model::reviewReplies,
                        )
                    }
                    is Frame.Root -> savedState.SaveableStateProvider(current.key) {
                        Column(Modifier.fillMaxSize().statusBarsPadding()) {
                            val compactHeader = current.section == Section.DISCOVER || current.section == Section.LIBRARY
                            Row(Modifier.fillMaxWidth().height(if (compactHeader) 52.dp else 60.dp)
                                .padding(horizontal = if (compactHeader) 8.dp else 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                val hasUpdate = update.phase in setOf(UpdatePhase.AVAILABLE, UpdatePhase.READY)
                                Box {
                                    IconButton(onClick = { scope.launch { drawer.open() } }) {
                                        Icon(Icons.Outlined.Menu, if (hasUpdate) "打开导航，有新版本" else "打开导航", Modifier.size(22.dp))
                                    }
                                    if (hasUpdate && current.section == Section.DISCOVER) Box(
                                        Modifier.align(Alignment.TopEnd).padding(8.dp).size(5.dp)
                                            .background(MaterialTheme.colorScheme.onSurface, CircleShape))
                                }
                                if (current.section == Section.DISCOVER) {
                                    BrowseTabs(browse.mode.ordinal, DiscoveryMode.entries.map { it.label }, Modifier.weight(1f)) { index ->
                                        keyboard?.hide()
                                        searchExpanded = false
                                        model.selectDiscovery(DiscoveryMode.entries[index], clearQuery = true)
                                    }
                                } else {
                                    Text(if (current.section == Section.LIBRARY) "书架" else "漫流",
                                        modifier = Modifier.padding(start = 4.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.weight(1f))
                                }
                                if (hasUpdate && current.section != Section.DISCOVER) {
                                    TextButton(onClick = { sectionName = Section.SETTINGS.name }) { Text("有新版本", style = MaterialTheme.typography.labelMedium) }
                                }
                                if (current.section == Section.LIBRARY) {
                                    TextButton(onClick = { sectionName = Section.ACCOUNT.name }) { Text("云端书架") }
                                }
                                if (current.section == Section.DISCOVER) {
                                    if (history.isNotEmpty()) IconButton(onClick = { keyboard?.hide(); recentReadingOpen = true }) {
                                        Icon(Icons.Outlined.History, "最近阅读", Modifier.size(22.dp))
                                    }
                                    IconButton(onClick = {
                                        searchExpanded = !searchExpanded
                                        if (!searchExpanded) { keyboard?.hide(); model.setQuery("") }
                                    }) {
                                        Icon(if (searchExpanded) Icons.Outlined.Close else Icons.Outlined.Search,
                                            if (searchExpanded) "关闭搜索" else "搜索漫画、作者", Modifier.size(22.dp))
                                    }
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (current.section) {
                                    Section.DISCOVER -> DiscoverScreen(
                                        state = browse, repository = model.repository,
                                        searchExpanded = searchExpanded,
                                        canAutoAdvance = !drawer.isOpen && !recentReadingOpen && !searchExpanded && frame is Frame.Root,
                                        onCloseSearch = { searchExpanded = false; model.setQuery("") },
                                        onQuery = model::setQuery, onRefresh = model::refresh,
                                        onRetry = model::retryBrowse, onMore = model::loadMore,
                                        onOpen = { model.openManga(it) },
                                        onPeriod = { model.selectDiscovery(browse.mode, period = it) },
                                        onAudience = { model.selectDiscovery(browse.mode, audience = it) },
                                    )
                                    Section.LIBRARY -> LibraryScreen(
                                        favorites = favorites, history = history, repository = model.repository,
                                        onOpen = { model.openManga(it) }, onContinue = model::continueReading,
                                        onDiscover = { sectionName = Section.DISCOVER.name },
                                    )
                                    Section.DOWNLOADS -> DownloadsScreen(model.downloads, model::openDownload,
                                        model::retryDownload, refreshingDownloads)
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
                    }
                }
            }
        }
    }
}
}

private tailrec fun Context.activityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activityOrNull()
    else -> null
}

