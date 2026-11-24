package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.platformKind
import dev.mangaflow.reader.ReaderWindow
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun AnimePlayerPane(
    controller: AnimeController,
    state: AnimeScreenState,
    playback: AnimePlayerState,
    backdrop: HazeState,
    active: Boolean,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    obscured: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onKeepPlayback: () -> Unit,
    onRestorePlayback: () -> Unit,
    onPlayDownload: (AnimeDownloadSelection) -> Unit,
    onSources: (AnimeEpisode?) -> Unit,
    onEpisode: (AnimeEpisode) -> Unit,
    modifier: Modifier = Modifier,
    onStorage: () -> Unit = {},
) {
    val fillVideo = fullscreen || playback.inPictureInPicture
    val desktop = platformKind == PlatformKind.DESKTOP
    val headerOverVideo = fillVideo || desktop
    val detail = state.detail
    val activeId = state.selectedEpisodeId ?: playback.episodeId
    val activeEpisode = remember(detail?.episodes, activeId) { detail?.episodes?.firstOrNull { it.id == activeId } }
    val target = state.playbackTarget
    val episode = target?.episode ?: activeEpisode
    val resolutionError = target?.error
    val awaitingVideo = playback.firstFrameRendered == false && playback.hasVideoTracks != false
    val keepingCurrentVideo = (state.resolving || state.pendingPlayback != null) && target?.manualOption != null &&
        target.episode.id == state.selectedEpisodeId && playback.phase == AnimePlayerPhase.READY &&
        !playback.isBuffering && !awaitingVideo
    val blockingResolution = state.resolving && !keepingCurrentVideo
    val showingCurrentPlayback = (target == null || keepingCurrentVideo) && playback.assetKey != null &&
        playback.episodeId == state.selectedEpisodeId
    val currentOption = state.selectedPlaybackOption.takeIf {
        showingCurrentPlayback && episode != null && state.selectedEpisodeId == episode.id
    }
    val hasAudioAlternative = if (episode != null && playback.phase == AnimePlayerPhase.READY && playback.hasVideoTracks == false)
        remember(episode.id, state) {
            controller.confirmedPlaybackLines(episode.id, state).asSequence()
                .filter { controller.canAttemptPlaybackLine(episode.id, it, state) }.drop(1).any()
        } else false
    val ordered = remember(detail?.episodes, episode?.kind) { detail?.episodes.orEmpty().filter { it.kind == episode?.kind } }
    val index = remember(ordered, episode?.id) { ordered.indexOfFirst { it.id == episode?.id } }
    val previous = ordered.getOrNull(index - 1).takeIf { index > 0 }
    val next = ordered.getOrNull(index + 1).takeIf { index >= 0 }
    val nextUpcoming = playback.ended && next != null &&
        controller.episodeAvailability(next.id, state) == AnimeEpisodeAvailability.UPCOMING
    val playerFailure = playback.error ?: if (playback.phase == AnimePlayerPhase.ERROR)
        "播放器未能准备视频，请重试或选择其他来源。" else null
    val playbackSource = currentOption?.sourceName
    val incident = target?.failure ?: playerFailure?.let { message -> currentOption?.let {
        AnimePlaybackFailure(it.sourceId, it.sourceName, "播放视频", message)
    } }
    val error = incident?.reason ?: resolutionError ?: playerFailure
    val failure = error?.let { listOfNotNull(it, state.playbackRecoveryStopReason).joinToString("\n") }
    // Retrying a failed lookup is useful even before any source has delivered a line.
    val canRetry = episode != null || playback.phase == AnimePlayerPhase.ERROR
    val focus = remember { FocusRequester() }
    val chromeFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    val watchingFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val touchExploration = LocalTouchExplorationEnabled.current
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHeld by remember { mutableStateOf(false) }
    var readingsHeld by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }
    var transportFocused by remember { mutableStateOf(false) }
    var centerFocused by remember { mutableStateOf(false) }
    var focusChromeOnReveal by remember { mutableStateOf<FocusRequester?>(null) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var episodesVisible by rememberSaveable { mutableStateOf(true) }
    var watchingTab by rememberSaveable(detail?.subject?.id) { mutableIntStateOf(0) }
    val episodeListState = key(detail?.subject?.id) { rememberSaveableStateHolder() }
    var watchPanelOpen by remember { mutableStateOf(false) }
    var watchPanelPresent by remember { mutableStateOf(false) }
    var queuedEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }
    var queuedLine by remember { mutableStateOf<AnimeEpisode?>(null) }
    var readingsVisible by rememberSaveable { mutableStateOf(false) }
    var holdingFast by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var settingsPresent by remember { mutableStateOf(false) }
    var interactionEpoch by remember { mutableIntStateOf(0) }
    val panelPresent = settingsPresent || watchPanelPresent
    val requiredControls = blockingResolution || failure != null || awaitingVideo ||
        playback.phase != AnimePlayerPhase.READY || !playback.playWhenReady || obscured || settingsPresent || watchPanelPresent ||
        state.notice != null || touchExploration
    val forceControls = requiredControls || headerFocused || transportFocused || centerFocused || readingsHeld
    val showChrome = !playback.inPictureInPicture && !panelPresent && !(obscured && headerOverVideo) && (!locked || touchExploration) &&
        (controlsVisible || forceControls)
    val motion = LocalMotionEnabled.current
    val reveal by animateFloatAsState(if (showChrome) 1f else 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
        label = "anime-player-controls")
    val chromeReveal = if (playback.inPictureInPicture) 0f else reveal
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val safeInsets = screenFitContentInsets()
    val topInset = safeInsets.asPaddingValues().calculateTopPadding()
    val bottomInset = safeInsets.asPaddingValues().calculateBottomPadding()
    var headerHeight by remember(density, topInset) { mutableStateOf(56.dp + topInset) }
    var footerHeight by remember(density) { mutableStateOf(56.dp) }
    val canControl = showingCurrentPlayback && !blockingResolution && failure == null &&
        (playback.phase == AnimePlayerPhase.READY || playback.ended)
    fun interact() { controlsVisible = true; interactionEpoch++ }
    fun showSources() { interact(); onSources(episode) }
    fun showSettings() {
        interact()
        if (headerFocused) focusChromeOnReveal = settingsFocus
        settingsPresent = true
        settingsVisible = true
    }
    fun toggleFullscreen() { interact(); onFullscreenChange(!fullscreen) }
    fun openWatching() {
        queuedEpisode = null
        queuedLine = null
        if (transportFocused) focusChromeOnReveal = watchingFocus
        watchPanelPresent = true
        watchPanelOpen = true
        interact()
    }
    fun seek(delta: Long) {
        if (playback.durationMs <= 0L || !canControl) return
        interact()
        controller.player.seekTo((playback.positionMs + delta).coerceIn(0L, playback.durationMs))
    }
    fun toggle() {
        if (!canControl) return
        interact()
        when {
            playback.ended -> { controller.player.seekTo(0L); controller.player.play() }
            playback.playWhenReady -> controller.player.pause()
            else -> controller.player.play()
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(fullscreen) { controlsVisible = true; if (!fullscreen) locked = false }
    LaunchedEffect(touchExploration) {
        if (touchExploration) { locked = false; controlsVisible = true }
    }
    LaunchedEffect(canControl) { if (!canControl) { transportFocused = false; centerFocused = false } }
    LaunchedEffect(playback.inPictureInPicture, active) {
        if (playback.inPictureInPicture || !active) {
            settingsVisible = false
            queuedEpisode = null
            queuedLine = null
            watchPanelOpen = false
            locked = false
        }
    }
    MotionBackHandler(active && locked && !obscured, state = null, onBack = { locked = false; interact() })
    LaunchedEffect(controlsVisible, forceControls, controlsHeld, interactionEpoch, active) {
        if (active && controlsVisible && !forceControls && !controlsHeld) {
            delay(3_500L)
            controlsVisible = false
        }
    }
    AnimePlayerOrientation(fullscreen, active = active && !playback.inPictureInPicture)
    // This shared window policy restores the previous flags on UI disposal without stopping playback.
    if (active) ReaderWindow(
        keepScreenOn = state.resolving || playback.playWhenReady &&
            playback.phase in listOf(AnimePlayerPhase.OPENING, AnimePlayerPhase.READY),
        controlsVisible = !fillVideo || showChrome || panelPresent || obscured,
        lightChrome = !fillVideo && colors.background.luminance() > .5f,
    )
    val videoColors = colors.copy(
        background = Color(0xFF101014), surface = Color(0xFF1C1D23),
        onSurface = Color(0xFFFAF8FF), onSurfaceVariant = Color(0xFFC6C1CD),
        primary = Color(0xFFF5EEFF), onPrimary = Color(0xFF241C31), outline = Color.White.copy(alpha = .30f),
        inverseSurface = Color(0xFFF5EEFF), inverseOnSurface = Color(0xFF241C31),
    )
    CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
        BoxWithConstraints(modifier.fillMaxSize().background(if (headerOverVideo) videoColors.background else colors.surface)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (active && !showChrome && !locked && !obscured && !settingsPresent && !watchPanelPresent &&
                    !playback.inPictureInPicture && event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
                    focusChromeOnReveal = chromeFocus
                    interact()
                    true
                } else false
            }
            .onKeyEvent { event ->
                // Focused buttons and the native slider get first refusal.
                    if (!active || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
                    event.type != KeyEventType.KeyUp || obscured || settingsPresent || watchPanelPresent || playback.inPictureInPicture) false
                else if (locked) { if (event.key == Key.Escape) { locked = false; true } else false }
                else when (event.key) {
                    Key.Spacebar -> { toggle(); true }
                    Key.DirectionLeft -> { seek(-10_000L); true }
                    Key.DirectionRight -> { seek(10_000L); true }
                    Key.F -> { toggleFullscreen(); true }
                    Key.Escape -> if (fullscreen) { onFullscreenChange(false); true } else false
                    else -> false
                }
            }.pointerInput(fullscreen, active, locked, obscured, settingsPresent, watchPanelPresent, playback.inPictureInPicture) {
                if (!fullscreen || !active || locked || obscured || settingsPresent || watchPanelPresent || playback.inPictureInPicture)
                    return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move && event.changes.any { it.type == PointerType.Mouse } &&
                            event.changes.none { it.pressed || it.previousPressed }) interact()
                    }
                }
            }.focusable()) {
            val widePlayer = maxWidth >= 680.dp
            val sideBySide = episodesVisible && widePlayer &&
                (desktop || maxWidth >= 900.dp || maxHeight < 540.dp)
            val railWidth = (maxWidth * .29f).coerceIn(234.dp, 340.dp)
            val videoWidth = if (fillVideo || !sideBySide) maxWidth else maxWidth - railWidth
            val videoTop = if (headerOverVideo) 0.dp else headerHeight
            val room = (maxHeight - videoTop - bottomInset).coerceAtLeast(1.dp)
            val inlineWatching = !fillVideo && episodesVisible && (sideBySide || room >= 380.dp)
            LaunchedEffect(active, showChrome, focusChromeOnReveal, chromeReveal > 0f, panelPresent, obscured,
                playback.inPictureInPicture, inlineWatching, detail == null) {
                val destination = focusChromeOnReveal ?: return@LaunchedEffect
                if (!active || obscured || playback.inPictureInPicture) focusChromeOnReveal = null
                else if (showChrome && chromeReveal > 0f && !panelPresent) {
                    withFrameNanos { }
                    val targetFocus = if (destination == watchingFocus && (inlineWatching || detail == null))
                        chromeFocus else destination
                    targetFocus.requestFocus()
                    focusChromeOnReveal = null
                }
            }
            val videoHeight = when {
                fillVideo -> maxHeight
                sideBySide || !inlineWatching -> room
                else -> minOf((videoWidth * 9f / 16f).coerceAtLeast(204.dp), room - 156.dp)
            }
            val centralTransport = (fillVideo || sideBySide) && canControl && !playback.ended &&
                !playback.isBuffering && !awaitingVideo && playback.hasVideoTracks != false &&
                state.notice == null && playback.notice == null &&
                videoHeight >= 250.dp * density.fontScale.coerceAtLeast(1f)
            val controlsTop = videoTop + videoHeight
            val subject = detail?.subject
            val opening = playback.phase == AnimePlayerPhase.IDLE || playback.phase == AnimePlayerPhase.OPENING || awaitingVideo
            val showStage = blockingResolution || failure != null || opening
            val compactStage = videoHeight - (if (headerOverVideo) headerHeight else 0.dp) - footerHeight < 108.dp
            val showArtwork = showStage && subject?.coverUrl?.isNotBlank() == true &&
                !(playback.firstFrameRendered == true && playback.episodeId == episode?.id) && !playback.inPictureInPicture
            val latestPlayback by rememberUpdatedState(playback)
            val latestRequiredControls by rememberUpdatedState(requiredControls)
            val latestChromeVisible by rememberUpdatedState(showChrome)

            // One native surface keeps its composition identity through fullscreen and size changes.
            Box(Modifier.padding(top = videoTop).width(videoWidth).height(videoHeight)
                .background(Color.Black).clipToBounds().pointerInput(playback.sessionGeneration, active, locked, fullscreen, canControl,
                    state.settings.longPressSpeed, obscured, settingsPresent, watchPanelPresent, playback.inPictureInPicture) {
                    if (!active || locked || obscured || settingsPresent || watchPanelPresent || playback.inPictureInPicture) return@pointerInput
                    var accelerated = false
                    // Children consume their own buttons; blank video and passive status layers
                    // share this parent gesture instead of competing full-size sibling hit targets.
                    detectTapGestures(
                        onTap = {
                            if (!accelerated) {
                                if (latestRequiredControls) controlsVisible = true
                                else {
                                    val revealControls = !latestChromeVisible
                                    focusManager.clearFocus()
                                    focus.requestFocus()
                                    controlsVisible = revealControls
                                }
                                interactionEpoch++
                            }
                        },
                        onDoubleTap = { point ->
                            if (canControl) {
                                val delta = if (point.x < size.width / 2f) -10_000L else 10_000L
                                val target = (latestPlayback.positionMs + delta).coerceAtLeast(0L)
                                controller.player.seekTo(if (latestPlayback.durationMs > 0L)
                                    target.coerceAtMost(latestPlayback.durationMs) else target)
                                interact()
                            }
                        },
                        onPress = press@{
                            accelerated = false
                            if (!canControl || !latestPlayback.isPlaying) return@press
                            val generation = latestPlayback.sessionGeneration
                            val originalSpeed = latestPlayback.speed
                            coroutineScope {
                                val fast = launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    if (controller.player.state.value.sessionGeneration == generation) {
                                        accelerated = true
                                        holdingFast = true
                                        controller.player.setSpeed(state.settings.longPressSpeed)
                                    }
                                }
                                try { tryAwaitRelease() }
                                finally {
                                    fast.cancel()
                                    if (holdingFast && controller.player.state.value.sessionGeneration == generation)
                                        controller.player.setSpeed(originalSpeed)
                                    holdingFast = false
                                }
                            }
                        },
                    )
                }, contentAlignment = Alignment.Center) {
                AnimePlayerSurface(controller.player, Modifier.fillMaxSize())
                AnimeDanmakuOverlay(controller, playback,
                    visible = active && !obscured && (!panelPresent || headerOverVideo) && !blockingResolution && failure == null,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp))
                if (showArtwork && subject != null) {
                    val context = LocalMangaContext.current
                    val request = remember(context, subject.coverUrl, animePosterSourceId(subject)) {
                        ImageRequest.Builder(imagePlatformContext(context))
                            .animeArtwork(subject.coverUrl, animePosterSourceId(subject)).build()
                    }
                    AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = .48f })
                }
                if (!playback.inPictureInPicture && showStage) {
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        Color.Black.copy(alpha = .66f), Color.Black.copy(alpha = .43f), Color.Black.copy(alpha = .85f),
                    ))))
                }
                val statusTop = if (headerOverVideo && chromeReveal > 0f) headerHeight else 0.dp
                val statusBottom = if (chromeReveal > 0f) footerHeight else 0.dp
                if (!playback.inPictureInPicture) Box(Modifier.fillMaxSize()
                    .padding(top = statusTop, bottom = statusBottom)
                    .motionInputGate(active && !locked && !obscured && !settingsPresent && !watchPanelPresent),
                    contentAlignment = Alignment.Center) {
                    MaterialTheme(colorScheme = videoColors) {
                      CompositionLocalProvider(LocalContentColor provides videoColors.onSurface) {
                        AnimeVideoStatus(
                            resolving = blockingResolution, autoMatching = state.autoMatching,
                            sourceName = if (target == null) playbackSource else target.sourceName,
                            failure = failure, incident = incident, playback = playback,
                            hasAudioAlternative = hasAudioAlternative,
                            canChooseSource = episode != null && !compactStage, canRetry = canRetry,
                            compact = videoHeight - statusTop - statusBottom < 196.dp,
                            next = next, nextUpcoming = nextUpcoming, onRetry = onRetry, onSources = ::showSources, onEpisode = onEpisode,
                            onKeepPlayback = onKeepPlayback,
                            canKeepPlayback = playback.phase == AnimePlayerPhase.READY && (state.resolving || resolutionError != null),
                            onRestorePlayback = onRestorePlayback, canRestorePlayback = state.canRestorePlayback,
                        )
                      }
                    }
                }
                if (holdingFast && !playback.inPictureInPicture) Text(animeSpeedLabel(playback.speed),
                    Modifier.align(Alignment.TopCenter).padding(top = 20.dp), color = Color.White,
                    style = MaterialTheme.typography.titleSmall)
                val readingsRoom = videoHeight - statusTop - statusBottom - 16.dp
                val readingsHeight = minOf(160.dp, if (centralTransport && chromeReveal > 0f)
                    (readingsRoom - 64.dp) * .5f else readingsRoom * .5f)
                if (readingsVisible && !panelPresent && !playback.inPictureInPicture && showingCurrentPlayback && failure == null &&
                    !blockingResolution && !awaitingVideo && readingsHeight >= 44.dp) {
                    MaterialTheme(colorScheme = videoColors) {
                        AnimePlaybackStatus(playback, quiet = !showChrome,
                            observing = active && !obscured && !settingsPresent && !watchPanelPresent,
                            interactive = active && showChrome && !locked && !obscured && !settingsPresent && !watchPanelPresent,
                            maximumHeight = readingsHeight,
                            onInteraction = ::interact,
                            onHoldControls = { readingsHeld = it },
                            modifier = Modifier.align(Alignment.TopStart)
                                .padding(start = 14.dp, end = 14.dp, top = statusTop + 8.dp)
                                .widthIn(max = 320.dp).fillMaxWidth(),
                            nextPreparation = state.nextEpisodePreparation,
                            nextPreparationEnabled = state.settings.prepareNextEpisode)
                    }
                }
                if (centralTransport && canControl && !playback.inPictureInPicture && chromeReveal > 0f) {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                      Box(Modifier.fillMaxSize().padding(top = statusTop, bottom = statusBottom), contentAlignment = Alignment.Center) {
                        Row(Modifier
                            .graphicsLayer { alpha = chromeReveal }
                            .onFocusChanged { centerFocused = it.hasFocus }.focusGroup()
                            .motionInputGate(active && showChrome && !obscured && !settingsPresent && !watchPanelPresent),
                            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (previous != null) MotionIconButton(onClick = { interact(); onEpisode(previous) },
                                modifier = Modifier.size(48.dp), tooltip = "上一集") {
                                Icon(Icons.Outlined.SkipPrevious, "上一集", Modifier.size(26.dp))
                            } else Spacer(Modifier.size(48.dp))
                            MotionIconButton(onClick = ::toggle, modifier = Modifier.size(64.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(Color.White.copy(alpha = .19f))
                                .border(1.dp, Color.White.copy(alpha = .23f), RoundedCornerShape(32.dp)),
                                tooltip = if (playback.playWhenReady && !playback.ended) "暂停 · 空格" else "播放 · 空格") {
                                Icon(if (playback.playWhenReady) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    if (playback.playWhenReady) "暂停" else "播放", Modifier.size(34.dp))
                            }
                            if (next != null) MotionIconButton(onClick = { interact(); onEpisode(next) },
                                modifier = Modifier.size(48.dp), tooltip = "下一集") {
                                Icon(Icons.Outlined.SkipNext, "下一集", Modifier.size(26.dp))
                            } else Spacer(Modifier.size(48.dp))
                        }
                      }
                    }
                }
            }

            val headerReveal = if (headerOverVideo) chromeReveal else 1f
            val headerInteractive = !headerOverVideo || showChrome
            if (!playback.inPictureInPicture && headerReveal > 0f) {
                val headerColors = if (headerOverVideo) videoColors else colors
                val headerSurface = if (headerOverVideo) Modifier.background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = .86f), Color.Black.copy(alpha = .40f), Color.Transparent)),
                ) else Modifier
                val headerShell = Modifier.align(Alignment.TopStart)
                    .width(if (!fillVideo && headerOverVideo) videoWidth else maxWidth)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer { alpha = headerReveal; translationY = (1f - headerReveal) * -8.dp.toPx() }
                    .motionInputGate(active && headerInteractive && !obscured && !settingsPresent && !watchPanelPresent)
                    .onFocusChanged { headerFocused = it.hasFocus }.focusGroup()
                    .then(headerSurface)
                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = if (headerOverVideo) 4.dp else 8.dp, vertical = 3.dp)
                val headerContent: @Composable () -> Unit = {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 3.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        MotionIconButton(onClick = { if (fullscreen) onFullscreenChange(false) else onBack() },
                            modifier = Modifier.size(44.dp).focusRequester(chromeFocus),
                            tooltip = if (fullscreen) "退出全屏" else "退出播放并保存位置") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (fullscreen) "退出全屏" else "退出播放", Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(detail?.subject?.title ?: "播放", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val caption = listOfNotNull(episode?.let(::animeEpisodeLabel),
                                episode?.let(::animeEpisodeSubtitle)).joinToString(" · ")
                            if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall,
                                color = headerColors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (fullscreen && canControl && !touchExploration) MotionIconButton(onClick = { locked = true },
                            modifier = Modifier.size(44.dp), tooltip = "锁定触控") {
                            Icon(Icons.Outlined.LockOpen, "锁定触控", Modifier.size(20.dp))
                        }
                        if (desktop && !fillVideo && sideBySide && detail != null) {
                            val label = if (episodesVisible) "收起选集与评论" else "展开选集与评论"
                            MotionIconButton(onClick = { episodesVisible = !episodesVisible; interact() },
                                modifier = Modifier.size(44.dp).semantics {
                                    stateDescription = if (episodesVisible) "选集与评论已展开" else "选集与评论已收起"
                                }, tooltip = label) {
                                Icon(Icons.Outlined.ViewSidebar, label, Modifier.size(20.dp))
                            }
                        } else if (desktop && !fillVideo && !episodesVisible && widePlayer && detail != null) {
                            MotionIconButton(onClick = { episodesVisible = true; interact() },
                                modifier = Modifier.size(44.dp), tooltip = "展开选集与评论") {
                                Icon(Icons.Outlined.ViewSidebar, "展开选集与评论", Modifier.size(20.dp))
                            }
                        }
                        MotionIconButton(onClick = ::showSettings, modifier = Modifier.size(44.dp).focusRequester(settingsFocus),
                            tooltip = "播放设置") {
                            Icon(Icons.Outlined.MoreHoriz, "播放设置", Modifier.size(22.dp))
                        }
                    }
                }
                Box(headerShell) {
                    if (headerOverVideo) CompositionLocalProvider(LocalContentColor provides headerColors.onSurface) {
                        headerContent()
                    } else LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), role = GlassSurfaceRole.NAVIGATION,
                        cornerRadius = 24.dp, elevation = 1.dp) { headerContent() }
                }
            }

            if (!playback.inPictureInPicture && chromeReveal > 0f) {
                val transportPlacement = if (fillVideo) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    else Modifier.align(Alignment.BottomStart).offset(y = controlsTop - maxHeight).width(videoWidth)
                val panel = Modifier.background(Brush.verticalGradient(
                    0f to Color.Transparent, .34f to Color.Black.copy(alpha = .56f), 1f to Color.Black.copy(alpha = .80f)))
                Box(transportPlacement
                    .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer { alpha = chromeReveal; translationY = (1f - chromeReveal) * 8.dp.toPx() }
                    .motionInputGate(active && showChrome && !locked && !obscured && !settingsPresent && !watchPanelPresent)
                    .onFocusChanged { transportFocused = it.hasFocus }.focusGroup()
                    .then(panel), contentAlignment = Alignment.BottomCenter) {
                    MaterialTheme(colorScheme = videoColors) {
                      CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        Column(Modifier.widthIn(max = 960.dp).fillMaxWidth()
                            .then(if (fillVideo) Modifier.windowInsetsPadding(
                                safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) else Modifier)
                            .padding(horizontal = 12.dp)) {
                            AnimePlayerControls(controller.player, playback,
                                previous, next, onEpisode, onToggle = ::toggle,
                                onInteraction = ::interact, onHoldControls = { controlsHeld = it },
                                nextPreparation = state.nextEpisodePreparation,
                                workspaceNotice = state.notice, onDismissNotice = controller::dismissNotice,
                                centralTransport = centralTransport, canControl = canControl,
                                sourceName = currentOption?.sourceName ?: "本地播放".takeIf { showingCurrentPlayback },
                                onSources = if (episode != null && !inlineWatching && (!showStage || compactStage)) ::showSources else null,
                                fullscreen = fullscreen, onFullscreen = ::toggleFullscreen,
                                onWatching = if (!inlineWatching && detail != null) ::openWatching else null,
                                watchingFocus = watchingFocus,
                                modifier = Modifier.fillMaxWidth())
                        }
                      }
                    }
                }
            }
            if (inlineWatching && !watchPanelPresent) {
                val episodePanel = if (sideBySide) Modifier.align(Alignment.TopEnd).offset(y = videoTop)
                    .width(railWidth).height(room).windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top))
                else Modifier.offset(y = controlsTop).fillMaxWidth()
                    .height((maxHeight - controlsTop - bottomInset).coerceAtLeast(0.dp))
                AnimeWatchingPane(state, episode, playbackSource, controller, backdrop, watchingTab, { watchingTab = it }, episodeListState,
                    onEpisode, { onSources(it) }, active && !obscured && !settingsPresent, desktop && sideBySide,
                    episodePanel.background(colors.surface))
            }
            if (locked && !playback.inPictureInPicture) MotionIconButton(onClick = { locked = false; interact() },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(48.dp), tooltip = "解除触控锁定") {
                Icon(Icons.Outlined.Lock, "解除触控锁定", Modifier.size(24.dp), tint = Color.White)
            }
        }
        if (settingsPresent) AnimePlayerSettings(settingsVisible && !playback.inPictureInPicture, AnimePlayerPanel.GENERAL,
            controller, state, playback, backdrop, overVideo = headerOverVideo,
            onDismiss = { settingsVisible = false; interact() }, onClosed = { settingsPresent = false },
            onPlayDownload = onPlayDownload, onSources = onSources,
            onStorage = onStorage, readingsVisible = readingsVisible, onReadingsVisible = { readingsVisible = it })
        if (watchPanelPresent) AnimePlaybackPanel(watchPanelOpen && !playback.inPictureInPicture, headerOverVideo,
            detail?.subject, backdrop, onDismiss = { watchPanelOpen = false; interact() }, onClosed = {
                val wasPresent = watchPanelPresent
                watchPanelPresent = false
                val catalogue = detail?.episodes.orEmpty()
                val requestedLine = queuedLine?.let { request ->
                    catalogue.firstOrNull { it.subjectId == request.subjectId && it.id == request.id }
                }
                val requestedEpisode = queuedEpisode?.let { request ->
                    catalogue.firstOrNull { it.subjectId == request.subjectId && it.id == request.id }
                }
                queuedLine = null
                queuedEpisode = null
                if (active && !obscured && !playback.inPictureInPicture) {
                    if (requestedLine != null) onSources(requestedLine)
                    else if (requestedEpisode != null) onEpisode(requestedEpisode)
                    else if (wasPresent) interact()
                }
            }) {
            AnimeWatchingPane(state, episode, playbackSource, controller, backdrop, watchingTab, { watchingTab = it }, episodeListState,
                onEpisode = { queuedEpisode = it; watchPanelOpen = false },
                onSources = { queuedLine = it; watchPanelOpen = false },
                active = active && watchPanelOpen && !obscured, rail = desktop,
                modifier = Modifier.fillMaxWidth().height(LocalMangaWindow.current.height)
                    .semantics { paneTitle = "选集与本集评论" },
                onClose = { watchPanelOpen = false; interact() })
        }
    }
}

@Composable
private fun AnimeWatchingPane(
    state: AnimeScreenState, episode: AnimeEpisode?, sourceName: String?, controller: AnimeController, backdrop: HazeState,
    tab: Int, onTabChange: (Int) -> Unit, episodeListState: SaveableStateHolder,
    onEpisode: (AnimeEpisode) -> Unit, onSources: (AnimeEpisode) -> Unit, active: Boolean,
    rail: Boolean, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null,
) {
    val detail = state.detail
    val entry = remember(detail?.subject?.id, state.library) {
        state.library.firstOrNull { it.subject.id == detail?.subject?.id }
    }
    val colors = MaterialTheme.colorScheme
    Column(modifier.motionInputGate(active)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp)) {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val showLineName = onClose == null && maxWidth >= 320.dp * fontScale
            val tabs = if (maxWidth < 278.dp * fontScale) listOf("选集", "评论") else listOf("选集", "本集评论")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f)) {
                    if (onClose != null) MotionTabs(tab, tabs, onSelect = onTabChange,
                        modifier = Modifier.widthIn(max = 208.dp).fillMaxWidth(),
                        controlHeight = 44.dp, treatment = MotionTabTreatment.Underline,
                        tooltips = listOf("选集", "本集评论"))
                    else LiquidGlassSurface(backdrop, Modifier.widthIn(max = 224.dp).fillMaxWidth(),
                        cornerRadius = 24.dp, role = GlassSurfaceRole.NAVIGATION, elevation = 1.dp) {
                        MotionTabs(tab, tabs, onSelect = onTabChange, controlHeight = 48.dp,
                            fullWidthIndicator = true, tooltips = listOf("选集", "本集评论"))
                    }
                }
                if (episode != null && tab == 0) {
                    val lineLabel = sourceName?.let { "切换本集线路，当前 $it" } ?: "选择本集线路"
                    if (showLineName) MotionTextButton(onClick = { onSources(episode) },
                        modifier = Modifier.widthIn(max = 128.dp).heightIn(min = 48.dp)
                            .semantics { stateDescription = lineLabel },
                        contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(sourceName ?: "选线路", style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else MotionIconButton(onClick = { onSources(episode) },
                        modifier = Modifier.size(48.dp), tooltip = lineLabel) {
                        Icon(Icons.Outlined.SwapHoriz, lineLabel, Modifier.size(18.dp))
                    }
                }
                if (onClose != null) MotionIconButton(onClick = onClose, modifier = Modifier.size(44.dp),
                    tooltip = "关闭选集与评论") {
                    Icon(Icons.Outlined.Close, "关闭选集与评论", Modifier.size(20.dp))
                }
            }
        }
        MotionTabContent(tab, Modifier.weight(1f).fillMaxWidth(), soft = true) { item ->
            episodeListState.SaveableStateProvider(item) {
                val paneActive = active && item == tab
                if (item == 0) AnimePlayerEpisodes(detail?.episodes.orEmpty(), episode?.id,
                    entry?.completedEpisodeIds.orEmpty(), onEpisode, episodeProgress = state.episodeProgress,
                    episodeAvailability = { controller.episodeAvailability(it, state) },
                    onSources = onSources, active = paneActive, desktopRail = rail, modifier = Modifier.fillMaxSize())
                else if (episode != null) {
                    val focusedDetail = detail?.takeIf { it.subject.id == episode.subjectId }
                    val reference = focusedDetail?.subject?.takeIf { it.canonicalBangumiId() != null }
                        ?: focusedDetail?.metadataSubject
                        ?: entry?.nativeMetadata.takeIf { entry?.subject?.id == episode.subjectId && episode.subjectId.startsWith("native:") }
                    AnimeEpisodeDiscussionContent(paneActive, episode, focusedDetail?.episodes.orEmpty(), reference,
                        backdrop, controller, Modifier.fillMaxSize())
                } else Box(Modifier.fillMaxSize().padding(16.dp)) {
                    AnimeQuietLabel("选一集后可以查看本集评论。")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeVideoStatus(
    resolving: Boolean, autoMatching: Boolean, sourceName: String?, failure: String?, incident: AnimePlaybackFailure?,
    playback: AnimePlayerState, hasAudioAlternative: Boolean, canChooseSource: Boolean, canRetry: Boolean,
    compact: Boolean, next: AnimeEpisode?, nextUpcoming: Boolean,
    onRetry: () -> Unit, onSources: () -> Unit, onEpisode: (AnimeEpisode) -> Unit,
    onKeepPlayback: () -> Unit, canKeepPlayback: Boolean, onRestorePlayback: () -> Unit, canRestorePlayback: Boolean,
) {
    val shape = RoundedCornerShape(14.dp)
    val awaitingVideo = playback.firstFrameRendered == false && playback.hasVideoTracks != false
    val rebuffering = playback.phase == AnimePlayerPhase.READY && playback.playWhenReady &&
        playback.isBuffering && !awaitingVideo && !resolving && failure == null
    var showBuffering by remember(playback.sessionGeneration, rebuffering) { mutableStateOf(false) }
    LaunchedEffect(playback.sessionGeneration, rebuffering) {
        if (rebuffering) {
            delay(700L)
            showBuffering = true
        }
    }
    when {
        resolving -> Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerStageMessage(if (autoMatching) "正在找本集线路" else "正在打开本集", sourceName, loading = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                if (canChooseSource) PlayerSwitchLine(onSources)
                if (canKeepPlayback) MotionTextButton(onClick = onKeepPlayback, modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("继续原线路", color = Color.White)
                }
            }
        }
        failure != null -> Column(Modifier.widthIn(max = 500.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlayerStageMessage("本集暂时无法播放", failure, maxLines = if (compact) 3 else 5,
                incident = incident)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (canRetry) {
                    MotionButton(onClick = onRetry, shape = shape, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("重试本集")
                    }
                    if (canChooseSource) MotionTextButton(onClick = onSources, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("选择线路", color = Color.White)
                    }
                } else if (canChooseSource) {
                    MotionButton(onClick = onSources, shape = shape, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("查看线路")
                    }
                }
                if (canKeepPlayback || canRestorePlayback) MotionTextButton(
                    onClick = if (canKeepPlayback) onKeepPlayback else onRestorePlayback,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(if (canKeepPlayback) "继续原线路" else "返回上次播放", color = Color.White) }
            }
        }
        playback.phase == AnimePlayerPhase.IDLE || playback.phase == AnimePlayerPhase.OPENING ||
            playback.phase == AnimePlayerPhase.READY && awaitingVideo ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val paused = playback.phase == AnimePlayerPhase.READY && !playback.playWhenReady
                val stage = when {
                    playback.recoveryAttempt > 0 -> "正在恢复本集"
                    paused -> "已暂停"
                    playback.phase == AnimePlayerPhase.READY -> "正在加载画面"
                    else -> "正在准备播放"
                }
                PlayerStageMessage(stage, sourceName, loading = !paused)
                if (canChooseSource) PlayerSwitchLine(onSources)
            }
        playback.phase == AnimePlayerPhase.READY && !playback.isBuffering && playback.hasVideoTracks == false ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerStageMessage("这条线路只有音频", null)
                if (hasAudioAlternative) MotionTextButton(onClick = onSources,
                    modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("换条线路", color = Color.White)
                }
            }
        showBuffering -> Row(Modifier.clip(RoundedCornerShape(18.dp))
            .background(Color.Black.copy(alpha = .62f)).padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (LocalMotionEnabled.current) CircularProgressIndicator(Modifier.size(12.dp),
                color = Color.White, strokeWidth = 1.5.dp)
            Text("正在缓冲", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        playback.ended -> Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerStageMessage("本集已播完", null)
            if (next != null) MotionButton(onClick = { onEpisode(next) }, shape = shape,
                modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(Icons.Outlined.SkipNext, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(if (nextUpcoming) "查看下一集" else "播放${animeEpisodeLabel(next)}")
            }
        }
    }
}

@Composable
private fun PlayerSwitchLine(onSources: () -> Unit) {
    MotionTextButton(onClick = onSources, modifier = Modifier.heightIn(min = 44.dp)) {
        Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp), tint = Color.White)
        Spacer(Modifier.width(4.dp))
        Text("换线路", color = Color.White)
    }
}

@Composable
private fun PlayerStageMessage(title: String, detail: String?, maxLines: Int = 2,
    incident: AnimePlaybackFailure? = null, loading: Boolean = false) {
    Column(Modifier.widthIn(max = 460.dp).padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading && LocalMotionEnabled.current) CircularProgressIndicator(Modifier.size(15.dp),
                color = Color.White, strokeWidth = 1.5.dp)
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        if (incident != null) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AnimeSourceMark(incident.sourceId, incident.sourceName, size = 16.dp, ink = Color.White.copy(alpha = .8f))
            Text("${incident.sourceName} · ${incident.stage}", color = Color.White.copy(alpha = .8f),
                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (!detail.isNullOrBlank()) Text(detail, color = Color.White.copy(alpha = .65f),
            style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center)
    }
}

@Composable
private fun AnimePlayerControls(player: AnimePlayer, state: AnimePlayerState,
    previous: AnimeEpisode?, next: AnimeEpisode?, onEpisode: (AnimeEpisode) -> Unit,
    onToggle: () -> Unit, onInteraction: () -> Unit,
    onHoldControls: (Boolean) -> Unit, workspaceNotice: String?, onDismissNotice: () -> Unit,
    nextPreparation: AnimeNextEpisodePreparation?, centralTransport: Boolean, canControl: Boolean,
    sourceName: String?, onSources: (() -> Unit)?,
    fullscreen: Boolean, onFullscreen: () -> Unit, onWatching: (() -> Unit)?, watchingFocus: FocusRequester,
    modifier: Modifier = Modifier) {
    var scrub by remember(state.sessionGeneration, state.timelineGeneration) { mutableStateOf<Long?>(null) }
    val duration = state.durationMs
    val canSeek = canControl && duration > 0L
    val bufferedRange = state.bufferedMs.takeIf {
        state.phase == AnimePlayerPhase.READY && state.assetKey != null &&
            state.positionMs in 0L until duration && it > state.positionMs
    }?.let { state.positionMs.toFloat()..it.coerceAtMost(duration).toFloat() }
    val releaseControls by rememberUpdatedState(onHoldControls)
    DisposableEffect(canSeek, state.sessionGeneration, state.timelineGeneration) {
        // Slider removal or a changed playback timeline may skip onValueChangeFinished mid-drag.
        scrub = null
        releaseControls(false)
        onDispose {
            scrub = null
            releaseControls(false)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        val notice = workspaceNotice ?: state.notice
        if (notice != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (workspaceNotice != null)
            MotionIconButton(onClick = { onInteraction(); onDismissNotice() }, modifier = Modifier.size(44.dp),
                tooltip = "关闭提示") { Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp)) }
        }
        if (canSeek) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
                Text(animeTime(scrub ?: state.positionMs), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = MaterialTheme.colorScheme.onSurface)) {
                    MotionSlider(value = (scrub ?: state.positionMs).toFloat(),
                        onValueChange = { onHoldControls(true); scrub = it.toLong() },
                        valueRange = 0f..duration.toFloat(),
                        bufferedRange = bufferedRange,
                        onValueChangeFinished = { scrub?.let(player::seekTo); scrub = null; onHoldControls(false); onInteraction() },
                        formatValue = { animeTime(it.toLong()) }, modifier = Modifier.weight(1f))
                }
                Text(animeTime(duration), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
            val wide = maxWidth / LocalDensity.current.fontScale.coerceAtLeast(1f) >= 380.dp
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (!centralTransport && canControl) {
                        if (wide && previous != null) MotionIconButton(onClick = { onInteraction(); onEpisode(previous) },
                            modifier = Modifier.size(44.dp), tooltip = "上一集") {
                            Icon(Icons.Outlined.SkipPrevious, "上一集", Modifier.size(21.dp))
                        }
                        val playLabel = if (state.ended) "重播" else if (state.playWhenReady) "暂停" else "播放"
                        MotionIconButton(onClick = onToggle, modifier = Modifier.size(44.dp), tooltip = "$playLabel · 空格") {
                            Icon(if (state.ended) Icons.Outlined.Replay else if (state.playWhenReady)
                                Icons.Outlined.Pause else Icons.Outlined.PlayArrow, playLabel, Modifier.size(28.dp))
                        }
                        if (next != null && !state.ended) MotionIconButton(onClick = { onInteraction(); onEpisode(next) },
                            modifier = Modifier.size(44.dp), tooltip = nextPreparation?.takeIf { it.episodeId == next.id }
                                ?.let { "下一集 · ${animePreparationLabel(it)}" } ?: "下一集") {
                            Icon(Icons.Outlined.SkipNext, "下一集", Modifier.size(21.dp))
                        }
                    }
                }
                if (onSources != null) {
                    val sourceLabel = sourceName?.let { "换线路，当前 $it" } ?: "选择播放线路"
                    if (wide && sourceName != null) MotionTextButton(onClick = onSources,
                        modifier = Modifier.widthIn(max = 156.dp).heightIn(min = 44.dp),
                        contentPadding = PaddingValues(horizontal = 7.dp),
                    ) {
                        Icon(Icons.Outlined.SwapHoriz, sourceLabel, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(sourceName, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else MotionIconButton(onClick = onSources, modifier = Modifier.size(44.dp), tooltip = sourceLabel) {
                        Icon(Icons.Outlined.SwapHoriz, sourceLabel, Modifier.size(20.dp))
                    }
                }
                if (onWatching != null) MotionIconButton(onClick = onWatching,
                    modifier = Modifier.size(44.dp).focusRequester(watchingFocus), tooltip = "选集与评论") {
                    Icon(Icons.Outlined.List, "选集与评论", Modifier.size(22.dp))
                }
                val fullscreenLabel = if (fullscreen) "退出全屏" else "全屏播放"
                MotionIconButton(onClick = onFullscreen, modifier = Modifier.size(44.dp), tooltip = "$fullscreenLabel · F") {
                    Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        fullscreenLabel, Modifier.size(24.dp))
                }
            }
        }
    }
}

internal fun animePreparationLabel(preparation: AnimeNextEpisodePreparation): String = when {
    preparation.phase == AnimePreparationPhase.WAITING -> "等待准备资源"
    preparation.phase == AnimePreparationPhase.RESOLVING -> "正在解析地址"
    preparation.phase == AnimePreparationPhase.CACHING -> "正在预读原片"
    preparation.phase == AnimePreparationPhase.DECODING -> "正在准备首帧"
    preparation.phase == AnimePreparationPhase.FAILED -> "准备未完成"
    preparation.startupFramePrepared -> "首帧已备"
    preparation.bufferedDurationMs != null -> "原片已备 ${animeTime(preparation.bufferedDurationMs)}"
    preparation.cachedBytes > 0L -> "原片已备 ${animeBytes(preparation.cachedBytes)}"
    preparation.addressResolved -> "播放地址已解析"
    else -> "等待准备"
}

internal fun animeBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> dev.mangaflow.platform.formatDecimal(bytes / (1024.0 * 1024.0 * 1024.0), 1) + " GiB"
    bytes >= 1024L * 1024L -> dev.mangaflow.platform.formatDecimal(bytes / (1024.0 * 1024.0), 1) + " MiB"
    bytes >= 1024L -> dev.mangaflow.platform.formatDecimal(bytes / 1024.0, 1) + " KiB"
    else -> "$bytes B"
}

@Composable
private fun AnimePlayerEpisodes(
    episodes: List<AnimeEpisode>, currentId: String?, completed: Set<String>,
    onEpisode: (AnimeEpisode) -> Unit, episodeProgress: Map<String, AnimeWatchProgress>,
    episodeAvailability: (String) -> AnimeEpisodeAvailability,
    onSources: (AnimeEpisode) -> Unit,
    active: Boolean, desktopRail: Boolean, modifier: Modifier = Modifier,
) {
    val subjectId = episodes.firstOrNull()?.subjectId
    var group by rememberSaveable(subjectId) { mutableIntStateOf(0) }
    var reversed by rememberSaveable(subjectId) { mutableStateOf(false) }
    var query by rememberSaveable(subjectId) { mutableStateOf("") }
    var searchVisible by rememberSaveable(subjectId) { mutableStateOf(false) }
    var lastSelectedEpisodeId by rememberSaveable(subjectId) { mutableStateOf<String?>(null) }
    var lastScrolledEpisodeId by rememberSaveable(subjectId) { mutableStateOf<String?>(null) }
    val scroll = rememberLazyGridState()
    val native = subjectId?.startsWith("native:") == true
    val (main, extra) = remember(episodes, native) {
        if (native) episodes to emptyList() else episodes.partition { it.kind == AnimeEpisodeKind.MAIN }
    }
    val selectedGroup = if (main.isEmpty() && extra.isNotEmpty()) 1 else if (extra.isEmpty()) 0 else group
    var rangeChoice by rememberSaveable(subjectId, selectedGroup) { mutableIntStateOf(-1) }
    LaunchedEffect(currentId, episodes) {
        if (currentId == lastSelectedEpisodeId) return@LaunchedEffect
        episodes.firstOrNull { it.id == currentId }?.let {
            group = if (native || it.kind == AnimeEpisodeKind.MAIN) 0 else 1
            rangeChoice = -1
            lastSelectedEpisodeId = it.id
        }
    }
    val selected = if (selectedGroup == 1) extra else main
    val term = query.trim()
    val ranges = remember(selected) { if (selected.size > 30) selected.chunked(30) else emptyList() }
    val currentRange = remember(ranges, currentId) {
        ranges.indexOfFirst { range -> range.any { it.id == currentId } }.coerceAtLeast(0)
    }
    val shownRange = rangeChoice.takeIf { it in ranges.indices } ?: currentRange
    val visibleEpisodes = if (term.isBlank()) ranges.getOrNull(shownRange) ?: selected else selected
    val shown = remember(visibleEpisodes, reversed, term) {
        val ordered = if (reversed) visibleEpisodes.asReversed() else visibleEpisodes
        if (term.isBlank()) ordered else ordered.filter {
            animeEpisodeLabel(it).contains(term, ignoreCase = true) || it.title.contains(term, ignoreCase = true)
        }
    }
    LaunchedEffect(currentId, shown, lastScrolledEpisodeId) {
        if (currentId == lastScrolledEpisodeId) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == currentId }
        if (index >= 0) {
            if (scroll.layoutInfo.visibleItemsInfo.none { it.key == currentId }) scroll.scrollToItem(index)
            lastScrolledEpisodeId = currentId
        }
    }
    val panelPadding = 16.dp
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(modifier.motionInputGate(active)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = panelPadding, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (selected.isNotEmpty()) Text(
                    if (term.isBlank()) "${selected.size} 集" else "${shown.size} / ${selected.size} 集",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val currentEpisode = episodes.firstOrNull { it.id == currentId }
            if (currentEpisode != null) MotionIconButton(onClick = {
                if (main.isNotEmpty() && extra.isNotEmpty()) group = if (currentEpisode.kind == AnimeEpisodeKind.MAIN) 0 else 1
                rangeChoice = -1
                searchVisible = false
                query = ""
                lastScrolledEpisodeId = null
            }, modifier = Modifier.size(44.dp), tooltip = "定位当前集") {
                Icon(Icons.Outlined.MyLocation, "定位当前集", Modifier.size(18.dp))
            }
            if (episodes.size > 12 || searchVisible) {
                val searchLabel = if (searchVisible) "关闭集数查找" else "查找集数"
                MotionIconButton(onClick = {
                    searchVisible = !searchVisible
                    if (!searchVisible) query = ""
                }, modifier = Modifier.size(44.dp), tooltip = searchLabel) {
                    Icon(if (searchVisible) Icons.Outlined.Close else Icons.Outlined.Search, searchLabel, Modifier.size(19.dp))
                }
                val orderLabel = if (reversed) "改为正序" else "改为倒序"
                MotionIconButton(onClick = { reversed = !reversed }, modifier = Modifier.size(44.dp), tooltip = orderLabel) {
                    Icon(Icons.Outlined.SwapVert, orderLabel, Modifier.size(19.dp))
                }
            }
        }
        if (main.isNotEmpty() && extra.isNotEmpty()) MotionTabs(selectedGroup, listOf("正片", "特别篇等"),
            onSelect = { group = it }, modifier = Modifier.padding(horizontal = panelPadding).widthIn(max = 280.dp).fillMaxWidth(),
            controlHeight = 44.dp, compact = true, treatment = MotionTabTreatment.Soft)
        if (searchVisible) AnimeTextField(query, { query = it }, "集号或标题",
            Modifier.padding(horizontal = panelPadding, vertical = 4.dp))
        if (term.isBlank() && ranges.size > 1) {
            val displayOrder = if (reversed) ranges.indices.reversed() else ranges.indices
            AnimeChoiceStrip(displayOrder.map { index ->
                val range = ranges[index]
                val first = range.first().number
                val last = range.last().number
                when {
                    first <= 0 || last <= 0 || !first.isFinite() || !last.isFinite() || first > last -> "第 ${index + 1} 组"
                    first == last -> animeEpisodeNumber(first)
                    else -> "${animeEpisodeNumber(first)}–${animeEpisodeNumber(last)}"
                }
            }, displayOrder.indexOf(shownRange), onSelect = { index ->
                rangeChoice = displayOrder.elementAt(index)
                scroll.requestScrollToItem(0)
            }, modifier = Modifier.padding(horizontal = panelPadding, vertical = 4.dp), active = active)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val columns = if (desktopRail) 1 else animeEpisodeGridColumns(maxWidth - panelPadding * 2, fontScale)
            LazyVerticalGrid(GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(), state = scroll, userScrollEnabled = active,
                contentPadding = PaddingValues(start = panelPadding, end = panelPadding, top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(if (desktopRail) 3.dp else 8.dp)) {
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    AnimeMessage(if (term.isBlank()) "暂无集数" else "没有匹配的集数",
                        action = if (term.isNotBlank()) "清除查找" else null, onAction = { query = "" })
                }
                items(shown, key = { it.id }, contentType = { "episode" }) { episode ->
                    AnimeEpisodeTile(episode, availability = episodeAvailability(episode.id),
                        progress = episodeProgress[episode.id]?.takeIf { it.subjectId == episode.subjectId },
                        watched = episode.id in completed, selectionLabel = "当前集".takeIf { episode.id == currentId },
                        enabled = active, listRow = desktopRail,
                        onPlay = { onEpisode(episode) }, onSources = { onSources(episode) })
                }
            }
            if (desktopRail) DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
