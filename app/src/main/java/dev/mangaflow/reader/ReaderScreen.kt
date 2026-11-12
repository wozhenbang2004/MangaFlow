package dev.mangaflow.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.mangaflow.data.Chapter
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage

@Composable
fun ReaderScreen(
    manga: Manga,
    chapters: List<Chapter>,
    initialChapterId: String,
    repository: MangaRepository,
    libraryStore: LibraryStore,
    downloadStore: DownloadStore,
    onBack: () -> Unit,
    onRequestNotificationPermission: () -> Unit = {},
) {
    val preferences by libraryStore.preferences.collectAsState()
    val downloads by downloadStore.entries.collectAsState()
    var chapterId by rememberSaveable(manga.id) { mutableStateOf(initialChapterId) }
    val chapter = chapters.firstOrNull { it.id == chapterId }
        ?: Chapter(chapterId, libraryStore.progressFor(manga.id)?.takeIf { it.chapterId == chapterId }?.chapterTitle ?: "正在阅读", 0)
    val siblings = remember(chapters, chapter.group) { chapters.filter { it.group == chapter.group }.sortedBy(Chapter::index) }
    val chapterIndex = siblings.indexOfFirst { it.id == chapterId }
    var retry by remember(chapterId) { mutableIntStateOf(0) }
    var pages by remember(chapterId) { mutableStateOf<List<Page>>(emptyList()) }
    var loading by remember(chapterId) { mutableStateOf(true) }
    var error by remember(chapterId) { mutableStateOf<String?>(null) }
    var offline by remember(chapterId) { mutableStateOf(false) }
    var pageIndex by remember(chapterId) { mutableIntStateOf(0) }
    var scrollOffset by remember(chapterId) { mutableIntStateOf(0) }
    var targetPage by remember(chapterId) { mutableStateOf<Int?>(null) }
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var downloadMessage by remember(chapterId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val headers = remember(manga.id, chapterId, repository) { repository.imageHeaders(manga.id, chapterId) }
    val activeDownload = downloads.firstOrNull { it.manga.id == manga.id && it.chapter.id == chapterId }
    val background = preferences.theme.background()

    ReaderWindow(preferences.keepScreenOn, controlsVisible || showSettings || showChapters)
    BackHandler { onBack() }

    LaunchedEffect(chapterId, retry) {
        loading = true
        error = null
        try {
            val local = withContext(Dispatchers.IO) { downloadStore.downloadedPages(manga.id, chapterId) }
            val loaded = local ?: repository.pages(manga.id, chapterId)
            if (loaded.isEmpty()) throw IllegalStateException("这一章还没有可读取的图片")
            val saved = libraryStore.progressFor(manga.id)?.takeIf { it.chapterId == chapterId }
            pageIndex = saved?.pageIndex?.coerceIn(0, loaded.lastIndex) ?: 0
            scrollOffset = saved?.scrollOffset ?: 0
            offline = local != null
            pages = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "章节暂时无法读取"
        } finally { loading = false }
    }

    LaunchedEffect(controlsVisible, showSettings, showChapters, loading, error) {
        if (controlsVisible && !showSettings && !showChapters && !loading && error == null) {
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
            pages.isNotEmpty() -> key(chapterId, preferences.mode) {
                ChapterPages(
                    pages = pages,
                    headers = headers,
                    preferences = preferences,
                    initialPage = pageIndex,
                    initialOffset = scrollOffset,
                    targetPage = targetPage,
                    onTargetConsumed = { targetPage = null },
                    onLocation = { index, offset -> pageIndex = index; scrollOffset = offset },
                    onSave = { index, offset -> libraryStore.saveProgress(manga, chapter, index, pages.size, offset) },
                    onTap = { controlsVisible = !controlsVisible },
                    onNext = siblings.getOrNull(chapterIndex + 1)?.let { next -> { chapterId = next.id; controlsVisible = true } },
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
                ReaderIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回漫画", onClick = onBack)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(manga.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(chapter.title + if (offline) " · 离线" else "", color = Color.White.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    if (showSettings) ReaderSettingsDialog(preferences, libraryStore::updatePreferences) { showSettings = false }
    if (showChapters) ReaderChaptersDialog(chapters, chapterId, onSelect = { chapterId = it; showChapters = false; controlsVisible = true }, onDismiss = { showChapters = false })
}

@OptIn(FlowPreview::class)
@Composable
private fun ChapterPages(
    pages: List<Page>,
    headers: Map<String, String>,
    preferences: ReaderPreferences,
    initialPage: Int,
    initialOffset: Int,
    targetPage: Int?,
    onTargetConsumed: () -> Unit,
    onLocation: (Int, Int) -> Unit,
    onSave: (Int, Int) -> Unit,
    onTap: () -> Unit,
    onNext: (() -> Unit)?,
) {
    val save by rememberUpdatedState(onSave)
    val location by rememberUpdatedState(onLocation)
    val startPage = remember { initialPage.coerceIn(0, pages.lastIndex) }
    if (preferences.mode == ReadingMode.VERTICAL) {
        val list = rememberLazyListState(initialFirstVisibleItemIndex = startPage)
        var restoring by remember { mutableStateOf(initialOffset > 0) }
        var targetMeasured by remember { mutableStateOf(pages[startPage].width > 0 && pages[startPage].height > 0) }
        LaunchedEffect(restoring, targetMeasured) {
            if (restoring && targetMeasured) {
                withFrameNanos { }
                list.scrollToItem(startPage, initialOffset)
                restoring = false
            }
        }
        LaunchedEffect(list) {
            snapshotFlow { list.isScrollInProgress }.collect { moving -> if (moving && !targetMeasured) restoring = false }
        }
        LaunchedEffect(list) {
            snapshotFlow { list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex) to list.firstVisibleItemScrollOffset }
                .distinctUntilChanged().collect { (index, offset) -> if (!restoring) location(index, offset) }
        }
        LaunchedEffect(list) {
            snapshotFlow { Triple(list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex), list.firstVisibleItemScrollOffset, restoring) }
                .debounce(450).distinctUntilChanged().collect { (index, offset, restoringNow) -> if (!restoringNow) save(index, offset) }
        }
        LaunchedEffect(targetPage) {
            targetPage?.let { restoring = false; list.scrollToItem(it.coerceIn(0, pages.lastIndex)); onTargetConsumed() }
        }
        DisposableEffect(list) {
            onDispose { if (!restoring) save(list.firstVisibleItemIndex.coerceAtMost(pages.lastIndex), list.firstVisibleItemScrollOffset) }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (preferences.pageGap) 6.dp else 0.dp)) {
            itemsIndexed(pages, key = { index, page -> "${page.index}:$index" }) { index, page ->
                ReaderPage(page, headers, paged = false, onTap = onTap, onMeasured = { if (index == startPage) targetMeasured = true })
            }
            item("chapter-end") {
                Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("本章已读完", color = preferences.theme.foreground().copy(alpha = .45f), fontSize = 13.sp)
                    if (onNext != null) TextButton(onClick = onNext) { Text("继续下一章", color = preferences.theme.foreground()) }
                }
            }
        }
    } else {
        val pager = rememberPagerState(initialPage = startPage, pageCount = { pages.size })
        LaunchedEffect(pager) {
            snapshotFlow { pager.currentPage }.distinctUntilChanged().collect { index -> location(index, 0); save(index, 0) }
        }
        LaunchedEffect(targetPage) { targetPage?.let { pager.scrollToPage(it.coerceIn(0, pages.lastIndex)); onTargetConsumed() } }
        DisposableEffect(pager) { onDispose { save(pager.currentPage, 0) } }
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = preferences.mode == ReadingMode.RIGHT_TO_LEFT,
            beyondViewportPageCount = 1,
            key = { it },
        ) { index -> ReaderPage(pages[index], headers, paged = true, onTap = onTap, onMeasured = {}) }
    }
}

@Composable
private fun ReaderPage(page: Page, headers: Map<String, String>, paged: Boolean, onTap: () -> Unit, onMeasured: () -> Unit) {
    val context = LocalContext.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.coerceAtLeast(320).dp
    val measured by rememberUpdatedState(onMeasured)
    var aspect by remember(page.url) { mutableFloatStateOf(if (page.width > 0 && page.height > 0) page.width.toFloat() / page.height else 0.7f) }
    var error by remember(page.url) { mutableStateOf<String?>(null) }
    var loading by remember(page.url) { mutableStateOf(true) }
    var reload by remember(page.url) { mutableIntStateOf(0) }
    val request = remember(page.url, headers, reload) {
        ImageRequest.Builder(context)
            .data(if (page.url.startsWith("file:")) File(URI(page.url)) else page.url)
            .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (name, value) -> set(name, value) } }.build())
            // This caps the thumbnail bitmap; Telephoto reads full-quality visible tiles from the original disk cache.
            .size(1600, 4096)
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
    BoxWithConstraints(if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        val naturalHeight = maxWidth / aspect
        val longImage = !paged && naturalHeight > screenHeight * 3f
        // Sub-sampling operates within its own layout bounds. Keep exceptionally tall
        // images in a screen-sized viewport so off-screen tiles are actually unloaded.
        val pageModifier = if (paged) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(if (longImage) screenHeight else naturalHeight)
        Box(pageModifier.clipToBounds(), contentAlignment = Alignment.Center) {
            key(reload) {
                ZoomableAsyncImage(
                    model = request,
                    contentDescription = "第 ${page.index + 1} 页",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (paged) ContentScale.Fit else ContentScale.FillWidth,
                    alignment = if (paged) Alignment.Center else Alignment.TopCenter,
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
                    listOf(ReadingMode.VERTICAL to "上下滚动", ReadingMode.RIGHT_TO_LEFT to "从右往左", ReadingMode.LEFT_TO_RIGHT to "从左往右").forEach { (mode, name) ->
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
private fun ReaderChaptersDialog(chapters: List<Chapter>, currentId: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(20.dp), color = Color(0xFF202022), contentColor = Color.White, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("章节", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    ReaderIcon(Icons.Rounded.Close, "关闭目录", onClick = onDismiss)
                }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = chapters.indexOfFirst { it.id == currentId }.coerceAtLeast(0))
                LazyColumn(state = state, modifier = Modifier.height(420.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(chapters, key = { _, chapter -> chapter.id }) { _, chapter ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (chapter.id == currentId) Color.White.copy(alpha = .08f) else Color.Transparent)
                            .clickable { onSelect(chapter.id) }.padding(horizontal = 12.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(chapter.title, Modifier.weight(1f), color = Color.White.copy(alpha = if (chapter.id == currentId) 1f else .65f), fontSize = 14.sp)
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
