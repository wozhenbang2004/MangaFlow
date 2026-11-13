package dev.mangaflow.reader.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CropFree
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Page
import dev.mangaflow.reader.ReaderSurface
import dev.mangaflow.storage.ReaderPreferences
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One page cursor shared with the ordinary reader; panel positions never become vertical offsets. */
@Composable
internal fun GuidedPanelReader(
    pages: List<Page>,
    headers: Map<String, String>,
    resolvePage: suspend (Page) -> Page,
    retryPage: suspend (Page) -> Page,
    preferences: ReaderPreferences,
    initialPage: Int,
    initialPanel: Int,
    targetPage: Int?,
    onTargetConsumed: () -> Unit,
    onLocation: (Int, Int) -> Unit,
    onSave: (Int, Int) -> Unit,
    onReadingNavigation: () -> Unit,
    onVisibleImageChanged: (Boolean) -> Unit,
    onNearEnd: () -> Unit,
    onAtEndChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onNext: (() -> Unit)?,
    onDisable: () -> Unit,
    active: Boolean,
    backdrop: HazeState,
    bottomClearancePx: Int,
) {
    val context = LocalContext.current
    val repository = remember(context) { PanelRepository(context) }
    val models = remember(context) { PanelModelStore.get(context) }
    val modelStates by models.states.collectAsStateWithLifecycle()
    val installedModels = modelStates.mapValues { it.value.installed }
    var pageIndex by rememberSaveable { mutableIntStateOf(initialPage.coerceIn(pages.indices)) }
    var panelIndex by rememberSaveable { mutableIntStateOf(initialPanel.coerceAtLeast(0)) }
    var overview by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var forceDetection by remember { mutableStateOf(false) }
    var resource by remember(pages[pageIndex].url) { mutableStateOf<Page?>(null) }
    var detection by remember(pages[pageIndex].url) { mutableStateOf<PanelDetection?>(null) }
    var loading by remember(pages[pageIndex].url) { mutableStateOf(true) }
    var error by remember(pages[pageIndex].url) { mutableStateOf<String?>(null) }
    var imageReady by remember(pages[pageIndex].url) { mutableStateOf(false) }
    val save by rememberUpdatedState(onSave)
    val location by rememberUpdatedState(onLocation)
    val visibleImage by rememberUpdatedState(onVisibleImageChanged)
    val navigate by rememberUpdatedState(onReadingNavigation)
    val resolve by rememberUpdatedState(resolvePage)
    val retry by rememberUpdatedState(retryPage)
    val boxes = detection?.boxes?.takeIf { it.isNotEmpty() } ?: listOf(PanelBox.FULL)
    val selected = panelIndex.coerceIn(boxes.indices)
    val exitPosition by rememberUpdatedState(Triple(pageIndex, selected, imageReady && detection != null))
    val atEnd = detection != null && pageIndex == pages.lastIndex && selected == boxes.lastIndex
    val foreground = MaterialTheme.colorScheme.onSurface

    fun move(forward: Boolean) {
        if (!active || loading || error != null || !imageReady) return
        navigate()
        save(pageIndex, selected)
        when {
            overview && forward && pageIndex < pages.lastIndex -> { pageIndex++; panelIndex = 0 }
            overview && !forward && pageIndex > 0 -> { pageIndex--; panelIndex = Int.MAX_VALUE }
            !overview && forward && selected < boxes.lastIndex -> panelIndex = selected + 1
            !overview && !forward && selected > 0 -> panelIndex = selected - 1
            forward && pageIndex < pages.lastIndex -> { pageIndex++; panelIndex = 0 }
            !forward && pageIndex > 0 -> { pageIndex--; panelIndex = Int.MAX_VALUE }
            forward -> onNext?.invoke()
        }
    }

    LaunchedEffect(targetPage) {
        targetPage?.let {
            if (imageReady && detection != null) save(pageIndex, selected)
            pageIndex = it.coerceIn(pages.indices)
            panelIndex = 0
            overview = false
            navigate()
            onTargetConsumed()
        }
    }
    LaunchedEffect(pageIndex, reload, active, installedModels, preferences.panelLayoutKey) {
        if (!active) return@LaunchedEffect
        loading = true
        error = null
        try {
            val page = pages[pageIndex]
            val resolved = if (reload > 0 && resource == null) retry(page) else resolve(page)
            resource = resolved
            val result = repository.detect(page, headers, { resolved }, preferences.panelEngine,
                preferences.panelDirection, preferences.panelLearnedOrder, force = forceDetection)
            detection = result
            panelIndex = panelIndex.coerceIn(result.boxes.indices.takeUnless { it.isEmpty() } ?: 0..0)
            forceDetection = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // The page still renders independently. A failed analysis never traps the reader.
            error = if (resource == null) failure.message ?: "这一页暂时无法读取" else null
            detection = PanelDetection(listOf(PanelBox.FULL), PanelEngine.CLASSIC.name,
                "分镜识别暂不可用，保留整页；可重新识别或继续翻页")
            overview = true
            forceDetection = false
        } finally { loading = false }
    }
    LaunchedEffect(pageIndex, detection, active) {
        if (!active || detection == null) return@LaunchedEffect
        // The visible page always wins. A page change cancels this bounded pre-read window.
        delay(400)
        for (index in pageIndex + 1..minOf(pageIndex + 2, pages.lastIndex)) {
            try {
                withTimeoutOrNull(15_000) {
                    repository.detect(pages[index], headers, resolve, preferences.panelEngine,
                        preferences.panelDirection, preferences.panelLearnedOrder)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* The visible page owns its eventual retry and error. */ }
        }
    }
    LaunchedEffect(pageIndex, selected, detection, imageReady, active) {
        if (detection != null && imageReady && active) {
            location(pageIndex, selected)
            if (pageIndex >= pages.lastIndex - 3) onNearEnd()
            delay(350)
            save(pageIndex, selected)
        }
    }
    LaunchedEffect(imageReady, active) { visibleImage(imageReady && active) }
    LaunchedEffect(atEnd) { onAtEndChanged(atEnd) }
    DisposableEffect(Unit) {
        onDispose {
            visibleImage(false)
            val (page, panel, loaded) = exitPosition
            if (loaded) save(page, panel)
        }
    }
    DisposableEffect(repository) {
        onDispose { CoroutineScope(Dispatchers.IO).launch { repository.close() } }
    }

    Box(Modifier.fillMaxSize()) {
        resource?.let { loaded -> key(loaded.url) {
            PanelCamera(
                page = loaded, headers = headers, box = boxes[selected], overview = overview,
                active = active,
                onTap = { x ->
                    val forwardZone = if (preferences.panelDirection == PanelDirection.RIGHT_TO_LEFT) x < .3f else x > .7f
                    val backwardZone = if (preferences.panelDirection == PanelDirection.RIGHT_TO_LEFT) x > .7f else x < .3f
                    when {
                        forwardZone && error == null -> move(true)
                        backwardZone && error == null -> move(false)
                        else -> onTap()
                    }
                },
                onImageReady = { imageReady = it },
                onError = { error = it },
                // Both horizontal reader directions retain the app's left-swipe-next convention.
                onSwipe = { delta -> move(delta < 0) },
                boxes = boxes, selectedIndex = selected,
                onSelectPanel = { panelIndex = it; overview = false; navigate() },
                modifier = Modifier.fillMaxSize(),
            )
        } }
        if (resource == null && loading) MotionBusyIndicator(Modifier.align(Alignment.Center).size(24.dp))
        if (error != null) ReaderSurface(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 360.dp)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error.orEmpty(), color = foreground, fontSize = 12.sp, textAlign = TextAlign.Center)
                Row {
                    TextButton(onClick = { resource = null; forceDetection = true; reload++ }) { Text("重试这页") }
                    TextButton(onClick = onDisable) { Text("普通阅读") }
                }
            }
        }
        val clearance = with(LocalDensity.current) { bottomClearancePx.toDp() }
        Column(Modifier.align(Alignment.BottomCenter)
            .then(if (bottomClearancePx == 0) Modifier.windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility) else Modifier)
            .padding(bottom = clearance + if (atEnd) 80.dp else 12.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            detection?.notice?.takeIf { error == null }?.let { notice ->
                ReaderSurface {
                    Text(notice, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = foreground,
                        fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center)
                }
            }
            LiquidGlassSurface(backdrop, role = GlassSurfaceRole.READING_DOCK) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionIconButton(enabled = active && !loading && (pageIndex > 0 || selected > 0),
                        onClick = { move(false) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上一格", Modifier.size(20.dp))
                    }
                    Text(if (loading) "识别中" else "${selected + 1} / ${boxes.size} 格",
                        fontSize = 12.sp, modifier = Modifier.widthIn(min = 64.dp)
                            .semantics { contentDescription = "第 ${pageIndex + 1} 页，第 ${selected + 1} 格，共 ${boxes.size} 格" },
                        textAlign = TextAlign.Center)
                    MotionIconButton(enabled = active && !loading && (!atEnd || onNext != null),
                        onClick = { move(true) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下一格", Modifier.size(20.dp))
                    }
                    MotionIconButton(enabled = active && resource != null, onClick = { overview = !overview },
                        modifier = Modifier.size(48.dp)) {
                        Icon(if (overview) Icons.Outlined.GridView else Icons.Outlined.CropFree,
                            if (overview) "回到当前分镜" else "查看整页，点选分镜", Modifier.size(20.dp))
                    }
                    MotionIconButton(enabled = active && !loading, onClick = { forceDetection = true; reload++ },
                        modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Refresh, "重新识别这页", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
