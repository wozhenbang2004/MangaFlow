package dev.mangaflow.reader.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import dev.mangaflow.experience.*
import dev.mangaflow.experience.readerPageCacheKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Page
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.ReaderSourceStandby
import dev.mangaflow.reader.ReaderImageRecovery
import dev.mangaflow.reader.ReaderLoadAttempt
import dev.mangaflow.reader.ReaderPageResolver
import dev.mangaflow.reader.ReaderPagePrefetch
import dev.mangaflow.reader.ReaderPrefetchFailure
import dev.mangaflow.reader.recordPrefetchFailure
import dev.mangaflow.reader.LocalReaderPreparationTracker
import dev.mangaflow.reader.LocalReaderPageRepair
import dev.mangaflow.reader.ReaderPreparationWorkContext
import dev.mangaflow.reader.ReaderPreparationPhase
import dev.mangaflow.reader.readerPageErrorLabel
import dev.mangaflow.reader.ReaderPrefetchViewport
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.reader.isReaderPrefetchFailure
import dev.mangaflow.reader.readerTimeoutIsSource
import dev.mangaflow.reader.rememberReaderPageTransfer
import dev.mangaflow.reader.ReaderSurface
import dev.mangaflow.reader.ReaderWaitCaption
import dev.mangaflow.storage.ReaderPreferences
import dev.mangaflow.storage.DownloadProgress
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.ChapterSourceLogo
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One page cursor shared with the ordinary reader; panel positions never become vertical offsets. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuidedPanelReader(
    pages: List<Page>,
    headers: Map<String, String>,
    sourceId: String,
    prefetchRequests: MutableMap<Int, ReaderPagePrefetch>,
    prefetchFailures: MutableMap<Int, ReaderPrefetchFailure>,
    resolvePage: ReaderPageResolver,
    retryPage: ReaderPageResolver,
    observePageTransfer: (Page) -> Flow<DownloadProgress?>,
    externalRetrySerial: Int,
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
    nextChapterPages: Int,
    onAtEndChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onShowControls: () -> Unit,
    recoveryForeground: Color,
    sourceName: String,
    sourceAlternatives: List<ChapterVariant>,
    readySourceVariants: Map<String, ReaderSourceStandby>,
    onPrefetchFailure: (Page) -> Unit,
    onQuickSwitch: (Int, ChapterVariant) -> Unit,
    onRepairPage: (Int) -> Unit,
    onMoreSources: (Rect?) -> Unit,
    onNext: (() -> Unit)?,
    onDisable: () -> Unit,
    active: Boolean,
    backdrop: HazeState,
    artworkModifier: Modifier,
    bottomClearancePx: Int,
): ReaderPrefetchViewport {
    val context = LocalContext.current
    val primaryPreparationTracker = LocalReaderPreparationTracker.current
    val repair = LocalReaderPageRepair.current
    val repairController = repair?.controller
    val experience = remember(context) { ExperiencePolicyStore.get(context) }
    val nearEnd by rememberUpdatedState(onNearEnd)
    val pageFailed by rememberUpdatedState(onPrefetchFailure)
    val preparationLayout = remember { Any() }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val repository = remember(context) { PanelRepository(context) }
    val models = remember(context) { PanelModelStore.get(context) }
    val runtimes = remember(context) { PanelRuntimeStore.get(context) }
    val modelStates by models.states.collectAsStateWithLifecycle()
    val runtimeStates by runtimes.states.collectAsStateWithLifecycle()
    val installedModels = modelStates.mapValues { it.value.installed }
    val installedRuntimes = runtimeStates.mapValues { it.value.installed }
    var pageIndex by rememberSaveable { mutableIntStateOf(initialPage.coerceIn(pages.indices)) }
    var panelIndex by rememberSaveable { mutableIntStateOf(initialPanel.coerceAtLeast(0)) }
    var overview by rememberSaveable { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var forceDetection by remember { mutableStateOf(false) }
    val primaryPage = pages[pageIndex]
    val replacement = repair?.state?.replacements?.get(primaryPage.index)
    val currentPage = replacement?.page ?: primaryPage
    val currentHeaders = if (currentPage.sourceRouteId.isNotBlank()) emptyMap() else replacement?.headers ?: headers
    val currentSource = currentPage.sourceRouteId.ifBlank { replacement?.variant?.sourceId ?: sourceId }
    val preparationTracker = primaryPreparationTracker.takeIf { replacement == null }
    var imageRetry by remember(pageIndex, currentPage, currentHeaders, currentSource) { mutableIntStateOf(0) }
    val attempt = remember(pageIndex, currentPage, currentHeaders, currentSource, imageRetry) { ReaderLoadAttempt() }
    val preparationPageIndex = remember(attempt) { pageIndex }
    var preparationTicket by remember(attempt, preparationTracker) { mutableStateOf<Long?>(null) }
    val preparationWork = remember(attempt, preparationTracker) {
        PrefetchWork(experience::wakeAdmissions, observe = { snapshot ->
            preparationTicket?.let {
                preparationTracker?.observe(preparationPageIndex, it, snapshot)
                preparationTracker?.transfer(preparationPageIndex, it, snapshot)
            }
        })
    }
    val transferObserver = remember(attempt) { ReaderTransferObserver(preparationWork) }
    val attemptPrefetch = remember(attempt) { prefetchRequests[pageIndex].takeIf { imageRetry == 0 && replacement == null } }
    var waitingForPrefetch by remember(attempt) { mutableStateOf(attemptPrefetch != null) }
    var handedOffPrefetchProgress by remember(attempt) { mutableStateOf<ReaderTransferSnapshot?>(null) }
    var transportProgress by remember(attempt) { mutableStateOf(transferObserver.snapshot()) }
    var resource by remember(attempt) { mutableStateOf<Page?>(null) }
    var detection by remember(attempt) { mutableStateOf<PanelDetection?>(null) }
    var loading by remember(attempt) { mutableStateOf(true) }
    var error by remember(attempt) { mutableStateOf<String?>(null) }
    var imageNotice by remember(attempt) { mutableStateOf<String?>(null) }
    var imageReady by remember(attempt) { mutableStateOf(false) }
    val currentTransfer: (Page) -> Flow<DownloadProgress?> = remember(repairController, replacement, observePageTransfer) {
        if (replacement == null || repairController == null) observePageTransfer
        else { _ -> repairController.observeTransfer(replacement) }
    }
    val pageTransfer by rememberReaderPageTransfer(attempt, currentPage, currentTransfer,
        enabled = active && !imageReady && error == null && resource == null)
    LaunchedEffect(attempt, active, preparationTracker) {
        if (!active) return@LaunchedEffect
        val index = preparationPageIndex
        preparationWork.promote()
        val ticket = preparationTracker?.begin(index, PrefetchStage.QUEUED)
        preparationTicket = ticket
        var inspectedAt = 0L
        try {
            while (attempt.acceptsResult) {
                val progress = if (waitingForPrefetch) attemptPrefetch?.work?.snapshot() else preparationWork.snapshot()
                if (ticket != null && progress != null) {
                    preparationTracker?.observe(index, ticket, progress)
                    preparationTracker?.transfer(index, ticket, progress)
                }
                if (ticket != null && !waitingForPrefetch) pageTransfer?.let { preparationTracker?.download(index, ticket, it) }
                preparationTracker?.handoff(index, waitingForPrefetch)
                val now = monotonicTimeMillis()
                val resolved = resource
                val interval = experience.state.value.runtime.cacheSampleSeconds.coerceAtLeast(1).toLong() * 1000L
                if (resolved != null && now - inspectedAt >= interval) {
                    inspectedAt = now
                    val artifactKeys = preparationWork.snapshot()
                    val artifacts = inspectReaderPreparedArtifacts(context, resolved, currentHeaders, currentSource,
                        artifactKeys.diskCacheKey, artifactKeys.memoryCacheKey)
                    if (attempt.acceptsResult) preparationTracker?.artifact(index, artifacts.originalPresent,
                        artifacts.originalBytes, artifacts.previewResident,
                        freshnessMillis = (interval + monotonicTimeMillis() - now) * 2L)
                }
                preparationTracker?.sample()
                delay(250L)
            }
        } finally {
            ticket?.let { preparationTracker?.finish(it) }
            preparationTicket = null
        }
    }
    val waitClock = remember(attempt, active, imageReady) { TimeSource.Monotonic.markNow() }
    var slowLoading by remember(waitClock) { mutableStateOf(false) }
    var skipDetection by remember(attempt) { mutableStateOf(false) }
    var bypassNotice by remember(attempt) { mutableStateOf<String?>(null) }
    val detectionRun = remember(attempt, reload, active, installedModels, installedRuntimes,
        preferences.panelLayoutKey, skipDetection) { ReaderLoadAttempt() }
    var detectionClock by remember(detectionRun) { mutableStateOf<TimeMark?>(null) }
    val latestDetectionRun by rememberUpdatedState(detectionRun)
    val save by rememberUpdatedState(onSave)
    val location by rememberUpdatedState(onLocation)
    val visibleImage by rememberUpdatedState(onVisibleImageChanged)
    val navigate by rememberUpdatedState(onReadingNavigation)
    val currentResolve: ReaderPageResolver = remember(repairController, replacement, resolvePage) {
        if (replacement == null || repairController == null) resolvePage
        else { _, bytes -> repairController.resolveReplacement(replacement, false, bytes) }
    }
    val currentRetry: ReaderPageResolver = remember(repairController, replacement, retryPage) {
        if (replacement == null || repairController == null) retryPage
        else { _, bytes -> repairController.resolveReplacement(replacement, true, bytes) }
    }
    val resolve by rememberUpdatedState(currentResolve)
    val retry by rememberUpdatedState(currentRetry)
    DisposableEffect(primaryPage.index, repair?.controller, replacement) {
        val release = repair?.onRescue
        onDispose { release?.invoke(primaryPage.index, false) }
    }
    LaunchedEffect(attempt, active, imageReady, slowLoading, error) {
        repair?.onRescue?.invoke(primaryPage.index, active && !imageReady && (slowLoading || error != null))
    }
    val boxes = detection?.boxes?.takeIf { it.isNotEmpty() } ?: listOf(PanelBox.FULL)
    val selected = panelIndex.coerceIn(boxes.indices)
    val exitPosition by rememberUpdatedState(Triple(pageIndex, selected, imageReady && detection != null))
    val atEnd = detection != null && pageIndex == pages.lastIndex && selected == boxes.lastIndex
    val foreground = MaterialTheme.colorScheme.onSurface
    DisposableEffect(attempt) { onDispose { attempt.active = false } }
    DisposableEffect(detectionRun) { onDispose { detectionRun.active = false } }
    var previousRetrySerial by remember(pageIndex, currentPage, currentHeaders, currentSource) {
        mutableIntStateOf(externalRetrySerial)
    }
    LaunchedEffect(pageIndex, currentPage, currentHeaders, currentSource, externalRetrySerial) {
        if (externalRetrySerial > previousRetrySerial) {
            attempt.stopped = true
            detectionRun.stopped = true
            forceDetection = true
            imageRetry++
        }
        previousRetrySerial = externalRetrySerial
    }
    LaunchedEffect(attempt, active, imageReady, waitClock, error) {
        slowLoading = false
        val visibleAt = monotonicTimeMillis()
        val initialPrefetch = attemptPrefetch?.transfer?.snapshot()
        var observedPrefetchBytes = initialPrefetch?.receivedBytes ?: 0L
        var observedPrefetchAt = initialPrefetch?.lastProgressAt
        var inheritedProgressAt: Long? = null
        while (active && !imageReady && attempt.acceptsResult && error == null) {
            val transport = transferObserver.snapshot()
            val inherited = if (waitingForPrefetch) attemptPrefetch?.transfer?.snapshot() else handedOffPrefetchProgress
            if (inherited != null) {
                val latest = inherited.lastProgressAt
                // Only this exact request's positive body writes after visibility renew the clock.
                if (latest != null && latest >= visibleAt &&
                    (inherited.receivedBytes > observedPrefetchBytes || latest != observedPrefetchAt))
                    inheritedProgressAt = maxOf(inheritedProgressAt ?: latest, latest)
                observedPrefetchBytes = inherited.receivedBytes
                observedPrefetchAt = inherited.lastProgressAt
            }
            transportProgress = if (transport.receivedBytes > 0L || inherited == null) transport else inherited
            val latestByte = listOfNotNull(transport.lastProgressAt, inheritedProgressAt).maxOrNull()
            val idle = attempt.idleMillis(waitClock, latestByte)
            val config = experience.state.value.recovery
            val waitLimit = config.imageTimeoutSeconds.coerceAtLeast(1).toLong() * 1000L
            slowLoading = idle >= config.slowLoadSeconds.coerceAtLeast(1).toLong() * 1000L
            if (idle >= waitLimit) {
                attempt.stopped = true
                loading = false
                val observed = if (waitingForPrefetch) attemptPrefetch?.work?.snapshot() else preparationWork.snapshot()
                val sourceTimeout = currentSource != "jmcomic" && readerTimeoutIsSource(observed, pageTransfer)
                error = if (sourceTimeout) "本页连接超时" else "本页准备等待超时"
                if (replacement == null && sourceTimeout) {
                    repair?.controller?.failed(primaryPage.index)
                    repair?.controller?.recover(primaryPage.index)
                }
                break
            }
            delay(minOf(1_000L, waitLimit - idle))
        }
    }
    LaunchedEffect(detectionRun, loading, imageReady, detectionClock) {
        val startedAt = detectionClock
        if (active && loading && imageReady && !skipDetection && startedAt != null) {
            delay((experience.state.value.recovery.imageTimeoutSeconds.coerceAtLeast(1).toLong() * 1000L -
                startedAt.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L))
            if (attempt.acceptsResult && detectionRun.acceptsResult && latestDetectionRun === detectionRun && loading) {
                // Native inference can finish after cancellation. Release the visible
                // reader now; the old run cannot replace this full-page fallback.
                bypassNotice = "分镜识别超时，已保留整页，可继续翻页"
                detectionRun.stopped = true
                detection = PanelDetection(listOf(PanelBox.FULL), PanelEngine.CLASSIC.name, bypassNotice)
                overview = true
                skipDetection = true
                loading = false
            }
        }
    }

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
    LaunchedEffect(detectionRun, attempt.stopped) {
        if (!active || !attempt.acceptsResult || !detectionRun.acceptsResult) return@LaunchedEffect
        val requestedRun = detectionRun
        loading = true
        error = null
        try {
            val page = currentPage
            val pendingPrefetch = attemptPrefetch ?: prefetchRequests[pageIndex].takeIf { imageRetry > 0 && replacement == null }
            if (resource == null && pendingPrefetch != null) {
                // Reuse the exact original transfer, or finish cancelling it before an explicit retry.
                if (imageRetry > 0) pendingPrefetch.request.dispose() else pendingPrefetch.work.promote()
                waitingForPrefetch = true
                try {
                    pendingPrefetch.request.job.await()
                    currentCoroutineContext().ensureActive()
                } catch (cancelled: CancellationException) {
                    // Cancelling optional work releases the handoff, not this owning reader.
                    currentCoroutineContext().ensureActive()
                }
                if (!attempt.acceptsResult || !requestedRun.acceptsResult || latestDetectionRun !== requestedRun) return@LaunchedEffect
                handedOffPrefetchProgress = attemptPrefetch?.transfer?.snapshot()
                waitingForPrefetch = false
            }
            val receiveBytes: (Long) -> Unit = { count -> if (attempt.acceptsResult) transferObserver.onBytes(count) }
            val resolved = resource ?: withContext(ReaderPreparationWorkContext(preparationWork)) {
                if (imageRetry > 0) retry(page, receiveBytes) else resolve(page, receiveBytes)
            }
            preparationTicket?.let { preparationTracker?.end(preparationPageIndex, it, PrefetchStage.RESOLVING, ReaderPreparationPhase.COMPLETE) }
            currentCoroutineContext().ensureActive()
            if (!attempt.acceptsResult || !requestedRun.acceptsResult || latestDetectionRun !== requestedRun) return@LaunchedEffect
            resource = resolved
            if (replacement == null) repair?.controller?.primaryResource(primaryPage.index, primaryPage, resolved)
            detectionClock = TimeSource.Monotonic.markNow()
            val detected = if (skipDetection) PanelDetection(listOf(PanelBox.FULL), PanelEngine.CLASSIC.name, bypassNotice)
            else withTimeoutOrNull(experience.state.value.recovery.imageTimeoutSeconds.coerceAtLeast(1).toLong() * 1000L) {
                repository.detect(page, currentHeaders, { resolved }, preferences.panelEngine,
                    preferences.panelDirection, preferences.panelLearnedOrder, force = forceDetection, sourceId = currentSource)
            }
            val result = detected ?: PanelDetection(listOf(PanelBox.FULL), PanelEngine.CLASSIC.name,
                "分镜识别超时，已保留整页，可继续翻页")
            currentCoroutineContext().ensureActive()
            if (!attempt.acceptsResult || !requestedRun.acceptsResult || latestDetectionRun !== requestedRun) return@LaunchedEffect
            detection = result
            if (skipDetection || detected == null) overview = true
            panelIndex = panelIndex.coerceIn(result.boxes.indices.takeUnless { it.isEmpty() } ?: 0..0)
            forceDetection = false
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (!attempt.acceptsResult || !requestedRun.acceptsResult || latestDetectionRun !== requestedRun) return@LaunchedEffect
            // The page still renders independently. A failed analysis never traps the reader.
            error = if (resource == null) failure.message ?: "这一页暂时无法读取" else null
            if (resource == null) {
                val sourceFailure = isReaderPrefetchFailure(failure)
                preparationTicket?.let { preparationTracker?.end(preparationPageIndex, it,
                    preparationWork.snapshot().stage, if (sourceFailure) ReaderPreparationPhase.FAILED
                        else ReaderPreparationPhase.DEFERRED, readerPageErrorLabel(error.orEmpty())) }
                if (replacement == null && sourceFailure) {
                    repair?.controller?.failed(primaryPage.index)
                    repair?.controller?.recover(primaryPage.index)
                }
            }
            detection = PanelDetection(listOf(PanelBox.FULL), PanelEngine.CLASSIC.name,
                "分镜识别暂不可用，保留整页；可重新识别或继续翻页")
            overview = true
            forceDetection = false
        } finally {
            if (attempt.acceptsResult && requestedRun.acceptsResult && latestDetectionRun === requestedRun) loading = false
        }
    }
    LaunchedEffect(pageIndex, selected, detection, imageReady, active) {
        // The parent needs the displayed page for retry/source/ordinary-reading actions,
        // even when this page has no usable image yet. Only ready content is saved.
        if (active) location(pageIndex, if (detection != null && imageReady) selected else 0)
        if (detection != null && imageReady && active) {
            delay(350)
            save(pageIndex, selected)
        }
    }
    // Re-evaluate a stationary final page when next-chapter preparation is enabled again.
    LaunchedEffect(pageIndex, detection, imageReady, active, nextChapterPages) {
        if (detection != null && imageReady && active && nextChapterPages > 0 &&
            pageIndex.toLong() >= pages.lastIndex.toLong() - nextChapterPages.toLong()) nearEnd()
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
        Box(Modifier.matchParentSize().then(artworkModifier)) {
            resource?.takeIf { attempt.acceptsResult && error == null }?.let { loaded -> key(attempt, loaded.url) {
                // A late verdict or clearing the shared marker must not replace this camera's image.
                val bypassRejectedOriginal = remember(loaded) {
                    replacement == null && loaded.url.startsWith("https:") &&
                        (prefetchFailures[pageIndex] as? ReaderPrefetchFailure.CachedOriginal)?.let {
                            it.key == readerPageCacheKey(loaded, currentHeaders, currentSource)
                        } == true
                }
                PanelCamera(
                    page = loaded, headers = currentHeaders, sourceId = currentSource, box = boxes[selected], overview = overview,
                    transferObserver = transferObserver,
                    bypassFailedCache = bypassRejectedOriginal,
                    onViewportChanged = { viewport = it },
                    active = active,
                    onTap = { x ->
                        val forwardZone = if (preferences.panelDirection == PanelDirection.RIGHT_TO_LEFT) x < .3f else x > .7f
                        val backwardZone = if (preferences.panelDirection == PanelDirection.RIGHT_TO_LEFT) x > .7f else x < .3f
                        when {
                            loading || !imageReady -> onTap()
                            forwardZone && error == null -> move(true)
                            backwardZone && error == null -> move(false)
                            else -> onTap()
                        }
                    },
                    onImageReady = { if (attempt.acceptsResult) {
                        imageReady = it
                        if (it) {
                            if (replacement == null) { prefetchFailures.remove(pageIndex); repair?.controller?.succeeded(primaryPage.index) }
                            repair?.onRescue?.invoke(primaryPage.index, false)
                        }
                    } },
                    onError = {
                        if (attempt.acceptsResult) {
                            error = it; imageReady = false; loading = false; attempt.stopped = true
                        }
                    },
                    onDetailError = {
                        if (attempt.acceptsResult) imageNotice =
                            if (it == null) null else "原图细节暂不可用，当前显示预览"
                    },
                    // Both horizontal reader directions retain the app's left-swipe-next convention.
                    onSwipe = { delta -> move(delta < 0) },
                    boxes = boxes, selectedIndex = selected,
                    onSelectPanel = { panelIndex = it; overview = false; navigate() },
                    modifier = Modifier.fillMaxSize(),
                )
            } }
        }
        if (replacement != null && repair != null) TextButton(
            onClick = { repair.controller.select(primaryPage.index, replacement.variant) },
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        ) {
            ChapterSourceLogo(replacement.variant.sourceId, replacement.variant.sourceName, Modifier.size(16.dp))
            Text("补 ${primaryPage.index + 1}", Modifier.padding(start = 5.dp), fontSize = 11.sp, color = recoveryForeground)
        }
        if (!imageReady || error != null) {
            Box(Modifier.matchParentSize().clickable(
                interactionSource = remember(attempt) { MutableInteractionSource() }, indication = null,
                onClickLabel = "显示或收起阅读工具", onClick = onTap,
            ))
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                ReaderImageRecovery(
                    index = primaryPage.index, pageCount = pages.size, sourceName = replacement?.variant?.sourceName ?: sourceName,
                    failure = error.orEmpty(), pending = error == null, slow = slowLoading,
                    transferProgress = pageTransfer ?: transportProgress.takeIf { it.receivedBytes > 0L }?.let {
                        DownloadProgress(phase = dev.mangaflow.storage.DownloadPhase.TRANSFERRING,
                            receivedBytes = it.receivedBytes, expectedBytes = it.expectedBytes)
                    },
                    stage = if (prefetchFailures[pageIndex] != null && transportProgress.receivedBytes == 0L)
                        "预读未完成 · 正在载入本页" else if (resource == null) "准备本页图片" else "载入本页图片",
                    attempt = attempt, foreground = recoveryForeground, alternatives = sourceAlternatives,
                    readySourceVariants = readySourceVariants,
                    startedAt = waitClock,
                    onRetry = {
                        attempt.stopped = true; detectionRun.stopped = true
                        forceDetection = true; imageRetry++
                    },
                    onSwitch = { onQuickSwitch(pageIndex, it) }, onMore = onMoreSources,
                    onRepairPage = { onRepairPage(pageIndex) },
                    onControls = onShowControls,
                    onStop = { attempt.stopped = true; error = "已停止等待"; loading = false },
                )
                if (slowLoading || error != null) TextButton(onClick = onDisable) {
                    Text("普通阅读", color = recoveryForeground)
                }
            }
        }
        val clearance = with(LocalDensity.current) { bottomClearancePx.toDp() }
        Column(Modifier.align(Alignment.BottomCenter)
            .then(if (bottomClearancePx == 0) Modifier.windowInsetsPadding(dev.mangaflow.reader.readerNavigationInsets()) else Modifier)
            .padding(bottom = clearance + if (atEnd) 80.dp else 12.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            (imageNotice ?: detection?.notice)?.takeIf { error == null }?.let { notice ->
                ReaderSurface {
                    Text(notice, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = foreground,
                        fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Center)
                }
            }
            LiquidGlassSurface(backdrop, role = GlassSurfaceRole.READING_DOCK, preferSource = true, cornerRadius = 26.dp,
                trackPointer = active && !loading) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionIconButton(enabled = active && imageReady && !loading && (pageIndex > 0 || selected > 0),
                        onClick = { move(false) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "上一格", Modifier.size(20.dp))
                    }
                    Text(if (!imageReady) "载入图片" else if (loading) "识别分镜" else "${selected + 1} / ${boxes.size} 格",
                        fontSize = 12.sp, modifier = Modifier.widthIn(min = 64.dp)
                            .semantics { contentDescription = if (!imageReady) "第 ${pageIndex + 1} 页，载入图片"
                                else if (loading) "第 ${pageIndex + 1} 页，识别分镜"
                                else "第 ${pageIndex + 1} 页，第 ${selected + 1} 格，共 ${boxes.size} 格" },
                        textAlign = TextAlign.Center)
                    if (loading && imageReady) detectionClock?.let {
                        ReaderWaitCaption(it, true, foreground.copy(alpha = .6f))
                    }
                    MotionIconButton(enabled = active && imageReady && !loading && (!atEnd || onNext != null),
                        onClick = { move(true) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "下一格", Modifier.size(20.dp))
                    }
                    MotionIconButton(enabled = active && imageReady, onClick = { overview = !overview },
                        modifier = Modifier.size(48.dp)) {
                        Icon(if (overview) Icons.Outlined.GridView else Icons.Outlined.CropFree,
                            if (overview) "回到当前分镜" else "查看整页，点选分镜", Modifier.size(20.dp))
                    }
                    MotionIconButton(enabled = active && imageReady, onClick = {
                        if (loading) {
                            detectionRun.stopped = true
                            bypassNotice = null; skipDetection = true; overview = true
                            detection = PanelDetection(listOf(PanelBox.FULL), PanelEngine.CLASSIC.name)
                            loading = false
                        }
                        else { bypassNotice = null; skipDetection = false; forceDetection = true; reload++ }
                    },
                        modifier = Modifier.size(48.dp)) {
                        Icon(if (loading) Icons.Outlined.Close else Icons.Rounded.Refresh,
                            if (loading) "停止识别并保留整页" else "重新识别这页", Modifier.size(18.dp))
                    }
                }
            }
        }
    }
    val preparationReady = imageReady && !loading && error == null && detection != null
    return ReaderPrefetchViewport(
        visibleRange = { pageIndex to pageIndex }, position = { pageIndex.toFloat() },
        composed = mapOf(pageIndex to true),
        // Let active recognition finish before starting new optional preparation.
        // A terminal error yields this turn without claiming a readable image.
        // Already owned originals can remain within the shared host's bounded window.
        loaded = if (preparationReady) mapOf(pageIndex to primaryPage.url) else emptyMap(),
        failedLoads = if (!loading && error != null) mapOf(pageIndex to primaryPage.url) else emptyMap(),
        width = { viewport.width }, height = viewport.height, active = active, layoutKey = preparationLayout,
        foregroundPending = active && error == null && attempt.acceptsResult && detectionRun.acceptsResult &&
            (!imageReady || loading),
        recovered = { index -> prefetchFailures.remove(index) },
        failed = { index, failure ->
            if (active && index in pages.indices) {
                if (attempt.acceptsResult) {
                    prefetchFailures.recordPrefetchFailure(index, failure)
                    pageFailed(pages[index])
                } else if (attempt.stopped && replacement == null && index == pageIndex &&
                    failure is ReaderPrefetchFailure.CachedOriginal && resource?.let { resolved ->
                        resolved.url.startsWith("https:") && readerPageCacheKey(resolved, currentHeaders, currentSource) == failure.key
                    } == true) {
                    // Keep the exact local finding for a later manual retry, not a new load or fallback now.
                    prefetchFailures.recordPrefetchFailure(index, failure)
                }
            }
        })

}
