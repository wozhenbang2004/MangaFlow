package dev.mangaflow.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
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
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Scale
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.CopyCommunityRepository
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReaderPreferences
import dev.mangaflow.storage.ReaderTheme
import dev.mangaflow.storage.ReadingMode
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableImageState

@Composable
fun ReaderScreen(
    manga: Manga,
    chapters: List<Chapter>,
    initialChapterId: String,
    repository: MangaRepository,
    libraryStore: LibraryStore,
    downloadStore: DownloadStore,
    communityRepository: CopyCommunityRepository,
    onBack: () -> Unit,
    onRequestNotificationPermission: () -> Unit = {},
    onChapterVisible: (Manga, Chapter) -> Unit = { _, _ -> },
    onNearChapterEnd: (Manga, Chapter) -> Unit = { _, _ -> },
    chaptersLoading: Boolean = false,
    chaptersError: String? = null,
    onRetryChapters: () -> Unit = {},
) {
    val preferences by libraryStore.preferences.collectAsState()
    val downloads by downloadStore.entries.collectAsState()
    var chapterId by rememberSaveable(manga.id) { mutableStateOf(initialChapterId) }
    val chapter = chapters.firstOrNull { it.id == chapterId }
        ?: Chapter(chapterId, libraryStore.progressFor(manga.id)?.takeIf { it.chapterId == chapterId }?.chapterTitle ?: "正在阅读", 0)
    val siblings = remember(chapters, chapter.group) { chapters.filter { it.group == chapter.group }.sortedBy(Chapter::index) }
    val hasMultipleGroups = remember(chapters) { chapters.map(Chapter::group).distinct().size > 1 }
    val chapterIndex = siblings.indexOfFirst { it.id == chapterId }
    var retry by remember(chapterId) { mutableIntStateOf(0) }
    var pages by remember(chapterId) { mutableStateOf<List<Page>>(emptyList()) }
    var loading by remember(chapterId) { mutableStateOf(true) }
    var error by remember(chapterId) { mutableStateOf<String?>(null) }
    var offline by remember(chapterId) { mutableStateOf(false) }
    var pageIndex by remember(chapterId) { mutableIntStateOf(0) }
    // Pixel offsets are a resume cursor, not UI state. Publishing every scroll delta
    // here used to recompose ReaderScreen and ChapterPages for every animation frame.
    val cursor = remember(chapterId) { ReaderCursor() }
    var targetPage by remember(chapterId) { mutableStateOf<Int?>(null) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSpreadAdjustment by remember(chapterId) { mutableStateOf(false) }
    var manualSingles by remember(manga.id, chapterId) {
        mutableStateOf(libraryStore.spreadSinglesFor(manga.id, chapterId))
    }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var downloadMessage by remember(chapterId) { mutableStateOf<String?>(null) }
    var chapterVisibilityReported by rememberSaveable(manga.id, chapterId) { mutableStateOf(false) }
    var nextChapterPrefetched by remember(manga.id, chapterId) { mutableStateOf(false) }
    var leavingReader by remember { mutableStateOf(false) }
    val chapterVisible by rememberUpdatedState(onChapterVisible)
    val nearChapterEnd by rememberUpdatedState(onNearChapterEnd)
    val scope = rememberCoroutineScope()
    val headers = remember(manga.id, chapterId, repository) { repository.imageHeaders(manga.id, chapterId) }
    val activeDownload = downloads.firstOrNull { it.manga.id == manga.id && it.chapter.id == chapterId }
    val background = preferences.theme.background()

    ReaderWindow(preferences.keepScreenOn, controlsVisible || showSettings || showChapters || showSpreadAdjustment)
    val leaveReader = { leavingReader = true; onBack() }
    BackHandler(onBack = leaveReader)

    LaunchedEffect(chapterId, retry) {
        loading = true
        error = null
        try {
            downloadStore.awaitReady()
            val local = withContext(Dispatchers.IO) { downloadStore.downloadedPages(manga.id, chapterId) }
            val loaded = local ?: repository.pages(manga.id, chapterId, forceRefresh = retry > 0)
            if (loaded.isEmpty()) throw IllegalStateException("这一章还没有可读取的图片")
            val saved = libraryStore.progressFor(manga.id, chapterId)
            pageIndex = saved?.pageIndex?.coerceIn(0, loaded.lastIndex) ?: 0
            cursor.scrollOffset = saved?.scrollOffset ?: 0
            offline = local != null
            pages = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "章节暂时无法读取"
        } finally { loading = false }
    }

    LaunchedEffect(controlsVisible, showSettings, showChapters, showSpreadAdjustment, loading, error) {
        if (controlsVisible && !showSettings && !showChapters && !showSpreadAdjustment && !loading && error == null) {
            delay(5000)
            controlsVisible = false
        }
    }
    LaunchedEffect(downloadMessage) { if (downloadMessage != null) { delay(4500); downloadMessage = null } }

    Box(Modifier.fillMaxSize().background(background)) {
        when {
            loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
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
                        if (!leavingReader && chapterId == chapter.id && !chapterVisibilityReported) {
                            chapterVisibilityReported = true
                            chapterVisible(manga, chapter)
                        }
                    },
                    onNearEnd = {
                        val next = siblings.getOrNull(chapterIndex + 1)
                        if (!leavingReader && chapterId == chapter.id && !nextChapterPrefetched && next != null) {
                            nextChapterPrefetched = true
                            nearChapterEnd(manga, next)
                        }
                    },
                    onTap = { controlsVisible = !controlsVisible },
                    endContent = {
                        ChapterDiscussion(manga, chapter, communityRepository, background, preferences.theme.foreground(),
                            onNext = siblings.getOrNull(chapterIndex + 1)?.let { next ->
                                { chapterId = next.id; controlsVisible = true }
                            })
                    },
                )
            }
        }

        if (preferences.dimAmount > 0) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = preferences.dimAmount)))

        AnimatedVisibility(controlsVisible, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .88f), Color.Transparent)))
                    .statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 30.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onClick = leaveReader)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(manga.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(chapter.group.takeIf { hasMultipleGroups }, chapter.title, "离线".takeIf { offline }).joinToString(" · "),
                        color = Color.White.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (preferences.mode == ReadingMode.RIGHT_TO_LEFT || preferences.mode == ReadingMode.LEFT_TO_RIGHT) {
                    TextButton(onClick = {
                        cursor.scrollOffset = 0
                        libraryStore.updatePreferences(preferences.copy(doublePage = !preferences.doublePage))
                    }) {
                        Text(if (preferences.doublePage) "双页" else "单页", color = Color.White, fontSize = 12.sp)
                    }
                }
                ReaderIcon(Icons.Rounded.Tune, "阅读设置") { showSettings = true }
            }
        }

        AnimatedVisibility(controlsVisible, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .9f))))
                    .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 14.dp),
            ) {
                if (pages.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${pageIndex + 1}", color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = pageIndex.toFloat(),
                            onValueChange = { targetPage = it.toInt().coerceIn(0, pages.lastIndex) },
                            valueRange = 0f..pages.lastIndex.coerceAtLeast(1).toFloat(),
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            enabled = pages.size > 1,
                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .18f)),
                        )
                        Text("${pages.size}", color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    ReaderIcon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上一章", enabled = chapterIndex > 0) {
                        siblings.getOrNull(chapterIndex - 1)?.let { chapterId = it.id }
                    }
                    TextButton(onClick = { showChapters = true }) { Text("目录", color = Color.White, fontSize = 13.sp) }
                    if (preferences.doublePage && (preferences.mode == ReadingMode.RIGHT_TO_LEFT || preferences.mode == ReadingMode.LEFT_TO_RIGHT)) {
                        TextButton(onClick = { showSpreadAdjustment = true }) {
                            Text("错页校正", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    val downloaded = activeDownload?.status == DownloadStatus.COMPLETED
                    val downloading = activeDownload?.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
                    ReaderIcon(if (downloaded) Icons.Rounded.Check else Icons.Rounded.Download, if (downloaded) "本章已下载" else "下载本章", enabled = pages.isNotEmpty() && !downloaded && !downloading) {
                        onRequestNotificationPermission()
                        scope.launch {
                            try {
                                downloadStore.enqueue(manga, chapter, pages, headers)
                                downloadMessage = "已加入下载"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { downloadMessage = failure.message ?: "无法加入下载" }
                        }
                    }
                    ReaderIcon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下一章", enabled = chapterIndex >= 0 && chapterIndex < siblings.lastIndex) {
                        siblings.getOrNull(chapterIndex + 1)?.let { chapterId = it.id }
                    }
                }
            }
        }

        if (!controlsVisible && pages.isNotEmpty()) Text(
            "${pageIndex + 1} / ${pages.size}", color = Color.White.copy(alpha = .72f), fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp)
                .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .35f)).padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable { controlsVisible = true },
        )
        downloadMessage?.let {
            Surface(Modifier.align(Alignment.Center).padding(32.dp), color = Color(0xEE252527), shape = RoundedCornerShape(16.dp)) {
                Text(it, Modifier.padding(horizontal = 20.dp, vertical = 14.dp), color = Color.White, fontSize = 13.sp)
            }
        }
    }
    if (showSettings) ReaderSettingsDialog(preferences, { next ->
        if (next.mode != preferences.mode || next.doublePage != preferences.doublePage || next.coverAlone != preferences.coverAlone) {
            cursor.scrollOffset = 0
        }
        libraryStore.updatePreferences(next)
    }) { showSettings = false }
    if (showSpreadAdjustment && pages.isNotEmpty()) {
        ReaderSpreadDialog(
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
    }
    if (showChapters) ReaderChaptersDialog(
        chapters, chapterId,
        loading = chaptersLoading, error = chaptersError, onRetry = onRetryChapters,
        onSelect = { chapterId = it; showChapters = false; controlsVisible = true },
        onDismiss = { showChapters = false },
    )
}

@OptIn(FlowPreview::class)
@Composable
private fun ChapterPages(
    pages: List<Page>,
    headers: Map<String, String>,
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
        LaunchedEffect(list, onNearEnd) {
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
        AdjacentPagePrefetch(pages, headers, preferences.mode, composedPages, prefetchRequests) {
            val visible = list.layoutInfo.visibleItemsInfo.filter { it.index in pages.indices }
            val anchor = list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex)
            (visible.firstOrNull()?.index ?: anchor) to (visible.lastOrNull()?.index ?: anchor)
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (preferences.mode == ReadingMode.VERTICAL && preferences.pageGap) 6.dp else 0.dp)) {
            itemsIndexed(pages, key = { index, page -> "${page.index}:$index" }, contentType = { _, _ -> "page" }) { index, page ->
                ReaderPage(
                    page, headers, paged = false, continuous = preferences.mode == ReadingMode.WEBTOON, onTap = onTap,
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
                .distinctUntilChanged().collect { index -> location(index, 0); save(index, 0) }
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
            onDispose { save(groups.getOrNull(pager.currentPage)?.first ?: pages.lastIndex, 0) }
        }
        AdjacentPagePrefetch(pages, headers, preferences.mode, composedPages, prefetchRequests) {
            val shown = groups.getOrNull(pager.currentPage)
            (shown?.first ?: pages.lastIndex) to (shown?.last ?: pages.lastIndex)
        }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = preferences.mode == ReadingMode.RIGHT_TO_LEFT,
            beyondViewportPageCount = 1,
            key = { index -> groups.getOrNull(index)?.first?.let { "image:$" + "it" } ?: "chapter-end" },
        ) { index ->
            val group = groups.getOrNull(index)
            if (group != null) {
                val renderImage: @Composable (Int, Modifier) -> Unit = { imageIndex, modifier ->
                    Box(modifier) {
                        ReaderPage(
                            pages[imageIndex], headers, paged = true, onTap = onTap,
                            onMeasured = { loadedPages[imageIndex] = true },
                            onComposed = { present -> if (present) composedPages[imageIndex] = true else composedPages.remove(imageIndex) },
                            prefetch = prefetchRequests[imageIndex],
                        )
                    }
                }
                val second = group.second
                if (second != null) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        if (preferences.mode == ReadingMode.RIGHT_TO_LEFT) {
                            renderImage(second, Modifier.weight(1f).fillMaxHeight())
                            renderImage(group.first, Modifier.weight(1f).fillMaxHeight())
                        } else {
                            renderImage(group.first, Modifier.weight(1f).fillMaxHeight())
                            renderImage(second, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                } else {
                    renderImage(group.first, Modifier.fillMaxSize())
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
    visibleRange: () -> Pair<Int, Int>,
) {
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val visible by rememberUpdatedState(visibleRange)
    LaunchedEffect(pages, headers, mode, imageLoader) {
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
                        // Fetch original bytes into Coil's existing disk cache. Never cache the
                        // empty decoder image in memory or report it as a visibly loaded page.
                        val request = imageLoader.enqueue(
                            readerImageRequest(context, pages[index], headers)
                                .size(1)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .decoderFactory(BlackholeDecoder.Factory())
                                .build(),
                        )
                        requests[index] = request
                        launch {
                            request.job.join()
                            if (requests[index] === request) {
                                finished += index
                                requests.remove(index)
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
    paged: Boolean,
    continuous: Boolean = false,
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
    val composed by rememberUpdatedState(onComposed)
    val readLongImagePosition by rememberUpdatedState(longImagePosition)
    val saveLongImagePosition by rememberUpdatedState(onSaveLongImagePosition)
    // Lazy layout recycling must not collapse a measured page back to its placeholder height.
    var aspect by rememberSaveable(page.url) { mutableFloatStateOf(if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height else 0.7f) }
    var error by remember(page.url) { mutableStateOf<String?>(null) }
    var loading by remember(page.url) { mutableStateOf(true) }
    var reload by remember(page.url) { mutableIntStateOf(0) }
    var waitingForPrefetch by remember(page.url, prefetch) { mutableStateOf(prefetch != null && !prefetch.job.isCompleted) }
    LaunchedEffect(prefetch) {
        // Coil 3.2 does not deduplicate cache misses in flight. Join the same original
        // fetch before Telephoto opens its completed disk entry, instead of starting over.
        prefetch?.job?.join()
        waitingForPrefetch = false
    }
    DisposableEffect(page.url) {
        composed(true)
        onDispose { composed(false) }
    }
    BoxWithConstraints(if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        val previewWidth = with(density) { maxWidth.roundToPx() }.coerceIn(1, 1600)
        val previewHeight = with(density) { screenHeight.roundToPx() }.coerceIn(1, 4096)
        val request = remember(page.url, headers, reload, previewWidth, previewHeight) {
            readerImageRequest(context, page, headers)
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
                        if (width > 0 && height > 0) aspect = (width.toFloat() / height).coerceAtLeast(.005f)
                        loading = false
                        measured()
                    },
                    onError = { _, result -> loading = false; error = result.throwable.message ?: "图片暂时无法读取" },
                )
                .build()
        }
        val naturalHeight = maxWidth / aspect
        val longImage = !paged && !continuous && naturalHeight > screenHeight * 3f
        // Sub-sampling operates within its own layout bounds. Keep exceptionally tall
        // images in a screen-sized viewport so off-screen tiles are actually unloaded.
        val pageModifier = if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(if (longImage) screenHeight else naturalHeight)
        Box(pageModifier.clipToBounds(), contentAlignment = Alignment.Center) {
            if (!waitingForPrefetch) key(reload) {
                val imageState = rememberZoomableImageState()
                var longImageRestored by rememberSaveable(page.url) { mutableStateOf(false) }
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
                    alignment = if (paged) Alignment.Center else Alignment.TopCenter,
                    gesturesEnabled = !continuous && (!longImage || longImageRestored),
                    onClick = { onTap() },
                )
            }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.Gray, strokeWidth = 1.5.dp)
            if (longImage && !loading && error == null) Text(
                "长图可在页内上下拖动",
                color = Color.White.copy(alpha = .9f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .58f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
            if (error != null) Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${page.index + 1} 页未加载", color = Color.Gray, fontSize = 13.sp)
                Text(error.orEmpty(), color = Color.Gray, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
private fun ReaderWindow(keepScreenOn: Boolean, controlsVisible: Boolean) {
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
    LaunchedEffect(window, view, keepScreenOn, controlsVisible) {
        window?.let {
            if (keepScreenOn) it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (controlsVisible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

@Composable
private fun ReaderSettingsDialog(preferences: ReaderPreferences, update: (ReaderPreferences) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(20.dp), color = Color(0xFF202022), contentColor = Color.White, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("阅读设置", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    ReaderIcon(Icons.Rounded.Close, "关闭设置", onClick = onDismiss)
                }
                Text("翻页方式", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .05f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    listOf(ReadingMode.VERTICAL to "纵向", ReadingMode.WEBTOON to "条漫", ReadingMode.RIGHT_TO_LEFT to "右翻", ReadingMode.LEFT_TO_RIGHT to "左翻").forEach { (mode, name) ->
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (preferences.mode == mode) Color.White.copy(alpha = .14f) else Color.Transparent)
                            .clickable { update(preferences.copy(mode = mode)) }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(name, color = Color.White.copy(alpha = if (preferences.mode == mode) 1f else .55f), fontSize = 12.sp)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("背景", fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReaderTheme.entries.forEach { theme ->
                            Box(Modifier.size(44.dp).semantics { contentDescription = when (theme) { ReaderTheme.INK -> "墨黑背景"; ReaderTheme.PAPER -> "纸白背景"; ReaderTheme.SEPIA -> "暖纸背景" } }
                                .padding(4.dp).clip(CircleShape).background(theme.background()).clickable { update(preferences.copy(theme = theme)) }, contentAlignment = Alignment.Center) {
                                if (preferences.theme == theme) Icon(Icons.Rounded.Check, "已选择", Modifier.size(18.dp), tint = theme.foreground())
                            }
                        }
                    }
                }
                ReaderToggle("保持屏幕常亮", preferences.keepScreenOn) { update(preferences.copy(keepScreenOn = it)) }
                if (preferences.mode == ReadingMode.VERTICAL) ReaderToggle("页间留白", preferences.pageGap) { update(preferences.copy(pageGap = it)) }
                if (preferences.mode == ReadingMode.WEBTOON) {
                    Text("条漫连续显示完整长图，无页间空隙", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                }
                if (preferences.mode == ReadingMode.RIGHT_TO_LEFT || preferences.mode == ReadingMode.LEFT_TO_RIGHT) {
                    ReaderToggle("双页跨页", preferences.doublePage) { update(preferences.copy(doublePage = it)) }
                    if (preferences.doublePage) {
                        ReaderToggle("封面独页", preferences.coverAlone) { update(preferences.copy(coverAlone = it)) }
                        Text("宽幅图自动整页显示，错位可在阅读页点「错页校正」", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                    }
                }
                Column {
                    Text("降低亮度", fontSize = 14.sp)
                    Slider(value = preferences.dimAmount, onValueChange = { update(preferences.copy(dimAmount = it)) }, valueRange = 0f..0.65f,
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .15f)))
                }
            }
        }
    }
}

@Composable
private fun ReaderSpreadDialog(
    pages: List<Page>,
    currentPage: Int,
    preferences: ReaderPreferences,
    manualSingles: Set<Int>,
    onCoverChange: (Boolean) -> Unit,
    onCorrection: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = currentPage.coerceIn(0, pages.lastIndex)
    val original = pages[first]
    val wide = original.width > 0 && original.height > 0 &&
        original.width.toFloat() / original.height >= 1.25f
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(20.dp), color = Color(0xFF202022), contentColor = Color.White, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("错页校正", fontSize = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    ReaderIcon(Icons.Rounded.Close, "关闭校正", onClick = onDismiss)
                }
                Text("当前显示从第 $" + "{first + 1} 页开始", color = Color.White.copy(alpha = .75f), fontSize = 13.sp)
                Text("两张图拼接错位时，将这一页设为独页，后续页会自动重新配对。不会更改原始图片顺序。",
                    color = Color.White.copy(alpha = .56f), fontSize = 12.sp)
                ReaderToggle("封面独页", preferences.coverAlone) {
                    onCoverChange(it)
                    onDismiss()
                }
                if (first == 0 && preferences.coverAlone) {
                    Text("封面已独立。需要让第一页参与拼页时，请关闭上方开关。",
                        color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
                } else if (wide) {
                    Text("本页是宽幅原图，保持单独显示，避免被压成半页。",
                        color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
                } else {
                    TextButton(onClick = {
                        val next = if (first in manualSingles) manualSingles - first else manualSingles + first
                        onCorrection(next)
                        onDismiss()
                    }) {
                        Text(if (first in manualSingles) "恢复第 $" + "{first + 1} 页的自动拼接"
                            else "第 $" + "{first + 1} 页独立，后续重新配对", color = Color.White, fontSize = 13.sp)
                    }
                }
                if (manualSingles.isNotEmpty()) {
                    TextButton(onClick = { onCorrection(emptySet()); onDismiss() }) {
                        Text("清除本章所有错页调整", color = Color.White.copy(alpha = .64f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderChaptersDialog(
    chapters: List<Chapter>,
    currentId: String,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val hasMultipleGroups = remember(chapters) { chapters.map(Chapter::group).distinct().size > 1 }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(20.dp), color = Color(0xFF202022), contentColor = Color.White, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("章节", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    ReaderIcon(Icons.Rounded.Close, "关闭目录", onClick = onDismiss)
                }
                if (loading) Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = Color.White.copy(alpha = .6f), strokeWidth = 1.5.dp)
                    Text("正在补全章节目录", Modifier.padding(start = 10.dp), color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
                } else if (error != null) {
                    Text(error, Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = .65f), fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onRetry) { Text("重试目录", color = Color.White, fontSize = 12.sp) }
                }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = chapters.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
                LazyColumn(state = state, modifier = Modifier.height(420.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { _, chapter ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (chapter.id == currentId) Color.White.copy(alpha = .08f) else Color.Transparent)
                            .clickable { onSelect(chapter.id) }.padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(chapter.title, color = Color.White.copy(alpha = if (chapter.id == currentId) 1f else .65f), fontSize = 14.sp)
                                if (hasMultipleGroups) Text(chapter.group, color = Color.White.copy(alpha = .45f), fontSize = 11.sp)
                            }
                            if (chapter.id == currentId) Icon(Icons.Rounded.Check, "当前章节", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderToggle(label: String, value: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = value, onCheckedChange = update, colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF202022), checkedTrackColor = Color.White, uncheckedBorderColor = Color.Transparent))
    }
}

@Composable
private fun ReaderIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, label, Modifier.size(22.dp), tint = Color.White.copy(alpha = if (enabled) .92f else .25f))
    }
}

private fun ReaderTheme.background() = when (this) { ReaderTheme.INK -> Color(0xFF101012); ReaderTheme.PAPER -> Color(0xFFF7F6F2); ReaderTheme.SEPIA -> Color(0xFFECE4D5) }
private fun ReaderTheme.foreground() = if (this == ReaderTheme.INK) Color.White else Color(0xFF242426)
private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
