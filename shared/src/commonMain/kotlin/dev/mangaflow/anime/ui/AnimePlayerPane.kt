package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.platformKind
import dev.mangaflow.reader.ReaderWindow
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Playback facts that can change controls/placement but do not tick with the media clock. */
@Immutable
private data class AnimePlaybackChrome(
    val sessionGeneration: Long, val timelineGeneration: Long, val assetKey: String?, val episodeId: String?,
    val phase: AnimePlayerPhase, val playWhenReady: Boolean, val isBuffering: Boolean,
    val playbackSuppressed: Boolean, val firstFrameRendered: Boolean?, val hasVideoTracks: Boolean?,
    val inPictureInPicture: Boolean, val error: String?, val notice: String?, val recoveryAttempt: Int,
    val hasTimeline: Boolean, val hourTimeline: Boolean,
    val canSourceVideoGlass: Boolean,
) {
    constructor(playback: AnimePlayerState) : this(
        playback.sessionGeneration, playback.timelineGeneration, playback.assetKey, playback.episodeId,
        playback.phase, playback.playWhenReady, playback.isBuffering, playback.playbackSuppressed,
        playback.firstFrameRendered, playback.hasVideoTracks, playback.inPictureInPicture, playback.error,
        playback.notice, playback.recoveryAttempt, playback.durationMs > 0L, playback.durationMs >= 3_600_000L,
        playback.canSourceVideoGlass,
    )

    val ended: Boolean get() = phase == AnimePlayerPhase.ENDED
    val isPlaying: Boolean get() = phase == AnimePlayerPhase.READY && playWhenReady && !isBuffering

    fun sameMedia(playback: AnimePlayerState): Boolean = sessionGeneration == playback.sessionGeneration &&
        assetKey == playback.assetKey && episodeId == playback.episodeId

    fun owns(playback: AnimePlayerState): Boolean = sameMedia(playback) && timelineGeneration == playback.timelineGeneration
}

/** A non-inline restart scope keeps the subscription's fast reads out of the player layout. */
@Composable
private fun AnimePlaybackObservation(observations: State<AnimePlayerState>, content: @Composable (AnimePlayerState) -> Unit) {
    content(observations.value)
}

@Composable
internal fun AnimePlayerPane(
    controller: AnimeController,
    state: AnimeScreenState,
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
    browseVisit: AnimePlayerBrowseVisit? = null,
    browseRequest: Int = 0,
    onBrowseOpen: (AnimePlayerBrowseVisit) -> Unit = {},
    onBrowseClose: (AnimePlayerBrowseVisit) -> Unit = {},
    onBrowseRequestConsumed: (Int) -> Unit = {},
) {
    val player = controller.player
    val observations = player.state.collectAsStateWithLifecycle()
    val playback = remember(observations) {
        derivedStateOf(structuralEqualityPolicy()) { AnimePlaybackChrome(observations.value) }
    }.value
    val fillVideo = fullscreen || playback.inPictureInPicture
    val desktop = platformKind == PlatformKind.DESKTOP
    val headerOverVideo = fillVideo || desktop
    val detail = state.detail
    val activeId = state.selectedEpisodeId ?: playback.episodeId
    val activeEpisode = remember(detail?.episodes, activeId) { detail?.episodes?.firstOrNull { it.id == activeId } }
        ?: detail?.let { controller.currentPlaybackEpisode(it.subject.id, state.selectedEpisodeId) }
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
    val browseFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val windowInfo = LocalWindowInfo.current
    val touchExploration = LocalTouchExplorationEnabled.current
    var videoWorkspaceFocused by remember { mutableStateOf(false) }
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
    var hadBrowseVisit by remember { mutableStateOf(false) }
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
    val hoverEnabled = active && !locked && !obscured && !panelPresent && !playback.inPictureInPicture
    val subject = detail?.subject
    val opening = playback.phase == AnimePlayerPhase.IDLE || playback.phase == AnimePlayerPhase.OPENING || awaitingVideo
    val showStage = blockingResolution || failure != null || opening
    val showArtwork = showStage && subject?.coverUrl?.isNotBlank() == true &&
        !(playback.firstFrameRendered == true && playback.episodeId == episode?.id) && !playback.inPictureInPicture
    val posterSource = if (showArtwork && subject != null) animePosterSourceId(subject) else null
    val posterLayer = if (showArtwork) key(subject?.id, subject?.coverUrl, posterSource) { rememberHazeState() } else null
    val posterLoaded = remember(posterLayer) { mutableStateOf(false) }
    val glassAccessibility = LocalGlassAccessibilitySettings.current
    val artwork = posterLayer?.takeIf { active && posterLoaded.value &&
        !glassAccessibility.reduceTransparency && !glassAccessibility.increaseContrast }
    val windowsDockRenderer = LocalWindowsVideoDock.current
    val useWindowsVideoDock = windowsDockRenderer != null
    // Desktop has no firstFrameRendered acknowledgement. Use its current libmpv decoder
    // observations to admit the live Canvas, not a CPU snapshot or a synthetic frame.
    val videoDockSource = if (windowsDockRenderer == true && active && !obscured && !locked && !panelPresent &&
        chromeReveal > 0f && !showStage && showingCurrentPlayback && canControl && playback.canSourceVideoGlass &&
        playback.assetKey != null && playback.episodeId != null &&
        !glassAccessibility.reduceTransparency && !glassAccessibility.increaseContrast) {
        key(player, playback.sessionGeneration, playback.timelineGeneration, playback.assetKey, playback.episodeId) {
            val haze = rememberHazeState()
            remember(haze) {
                AnimeVideoGlassSource(haze, player, playback.sessionGeneration, playback.timelineGeneration,
                    playback.assetKey, playback.episodeId)
            }
        }
    } else null
    fun interact() { controlsVisible = true; interactionEpoch++ }
    fun browseBack(): Boolean {
        val visit = browseVisit?.takeIf { active && it.subjectId == subject?.id } ?: return false
        if (!visit.returnToResults()) onBrowseClose(visit)
        interact()
        return true
    }
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
        val current = player.state.value
        if (!canControl || !playback.owns(current) || current.durationMs <= 0L ||
            current.phase != AnimePlayerPhase.READY && !current.ended) return
        interact()
        player.seekTo((current.positionMs + delta).coerceIn(0L, current.durationMs))
    }
    fun toggle() {
        val current = player.state.value
        if (!canControl || !playback.owns(current) || current.phase != AnimePlayerPhase.READY && !current.ended) return
        interact()
        when {
            current.ended -> { player.seekTo(0L); player.play() }
            current.playWhenReady -> player.pause()
            else -> player.play()
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
            if (playback.inPictureInPicture) {
                settingsPresent = false
                watchPanelPresent = false
                focusChromeOnReveal = null
            }
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
    CompositionLocalProvider(LocalContentColor provides colors.onSurface, LocalDesktopTooltipOverMedia provides desktop) {
        BoxWithConstraints(modifier.fillMaxSize().background(if (headerOverVideo) videoColors.background else colors.surface)
            .focusRequester(focus)
            .onFocusChanged { videoWorkspaceFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (active && (!desktop || videoWorkspaceFocused) && !showChrome && !locked && !obscured &&
                    !settingsPresent && !watchPanelPresent &&
                    !playback.inPictureInPicture && event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
                    focusChromeOnReveal = chromeFocus
                    interact()
                    true
                } else false
            }
            .onKeyEvent { event ->
                // Desktop editors and controls own both halves of a keystroke, even if
                // they only consume KeyDown and leave KeyUp unhandled.
                if (!active || (desktop && !videoWorkspaceFocused) ||
                    event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
                    event.type != KeyEventType.KeyUp || obscured || settingsPresent || watchPanelPresent || playback.inPictureInPicture) false
                else if (locked) { if (event.key == Key.Escape) { locked = false; true } else false }
                else when (event.key) {
                    Key.Spacebar -> { toggle(); true }
                    Key.DirectionLeft -> { seek(-10_000L); true }
                    Key.DirectionRight -> { seek(10_000L); true }
                    Key.F -> { toggleFullscreen(); true }
                    Key.Escape -> if (fullscreen) { onFullscreenChange(false); true } else browseBack()
                    else -> false
                }
            }.revealOnMouseMove(fullscreen && hoverEnabled, ::interact).focusable()) {
            val widePlayer = maxWidth >= 680.dp
            val videoTop = if (headerOverVideo) 0.dp else headerHeight
            val room = (maxHeight - videoTop - bottomInset).coerceAtLeast(1.dp)
            val browseFits = desktop && active && !fillVideo &&
                maxWidth >= 720.dp * density.fontScale.coerceAtLeast(1f) &&
                room >= 360.dp * density.fontScale.coerceAtLeast(1f)
            val browsing = browseVisit != null && browseFits
            val browseInteractive = active && !obscured && !locked && !panelPresent
            fun openBrowse() {
                val subjectId = subject?.id ?: return
                if (!browseFits || !browseInteractive) return
                val live = player.state.value
                if (!playback.owns(live)) return
                if (browsing && browseVisit != null) browseVisit.focusSearch()
                else if (controller.ownsVideoReadout(subjectId, live, includePaused = true)) {
                    onBrowseOpen(AnimePlayerBrowseVisit(subjectId))
                }
                interact()
            }
            LaunchedEffect(browseVisit, browseFits, active) {
                if (active && browseVisit != null && !browseFits) onBrowseClose(browseVisit)
                if (active && hadBrowseVisit && browseVisit == null && browseInteractive && !fillVideo) {
                    focusChromeOnReveal = browseFocus
                    interact()
                }
                hadBrowseVisit = active && browseVisit != null
            }
            LaunchedEffect(browseRequest, active) {
                if (!active || browseRequest == 0) return@LaunchedEffect
                onBrowseRequestConsumed(browseRequest)
                openBrowse()
            }
            val sideBySide = browsing || episodesVisible && widePlayer &&
                (desktop || maxWidth >= 900.dp || maxHeight < 540.dp)
            val railWidth = (maxWidth * .29f).coerceIn(if (browsing) 278.dp else 234.dp, 340.dp)
            val videoWidth = if (fillVideo || !sideBySide) maxWidth else maxWidth - railWidth
            val inlineWatching = !browsing && !fillVideo && episodesVisible && (sideBySide || room >= 380.dp)
            LaunchedEffect(active, showChrome, focusChromeOnReveal, chromeReveal > 0f, panelPresent, obscured,
                playback.inPictureInPicture, inlineWatching, detail == null) {
                val destination = focusChromeOnReveal ?: return@LaunchedEffect
                if (!active || obscured || playback.inPictureInPicture) focusChromeOnReveal = null
                else if (showChrome && chromeReveal > 0f && !panelPresent) {
                    withFrameNanos { }
                    val targetFocus = when {
                        destination == watchingFocus && (inlineWatching || detail == null) -> chromeFocus
                        destination == browseFocus && (!browseFits || detail == null || !browseInteractive ||
                            !showingCurrentPlayback || !canControl || showStage) -> chromeFocus
                        else -> destination
                    }
                    targetFocus.requestFocus()
                    focusChromeOnReveal = null
                }
            }
            val videoHeight = when {
                fillVideo -> maxHeight
                sideBySide || !inlineWatching -> room
                else -> minOf((videoWidth * 9f / 16f).coerceAtLeast(204.dp), room - 156.dp)
            }
            val centralTransport = !desktop && (fillVideo || sideBySide) && canControl && !playback.ended &&
                !playback.isBuffering && !awaitingVideo && playback.hasVideoTracks != false &&
                state.notice == null && playback.notice == null &&
                videoHeight >= 250.dp * density.fontScale.coerceAtLeast(1f)
            val controlsTop = videoTop + videoHeight
            val compactStage = videoHeight - (if (headerOverVideo) headerHeight else 0.dp) - footerHeight < 108.dp
            val pointerMedia = playback
            val latestChrome by rememberUpdatedState(playback)
            val latestRequiredControls by rememberUpdatedState(requiredControls)
            val latestChromeVisible by rememberUpdatedState(showChrome)
            val seekBareVideo by rememberUpdatedState<(AnimePlaybackChrome, Long) -> Unit> { owner, delta ->
                val live = controller.state.value
                val current = player.state.value
                if (active && !locked && !obscured && !settingsPresent && !watchPanelPresent &&
                    windowInfo.isWindowFocused && !current.inPictureInPicture &&
                    live.selectedEpisodeId == current.episodeId && live.playbackTarget == null &&
                    !live.resolving && live.pendingPlayback == null && owner.owns(current) &&
                    canControl && current.durationMs > 0L && (current.phase == AnimePlayerPhase.READY || current.ended)) {
                    focus.requestFocus()
                    seek(delta)
                }
            }

            // One native surface keeps its composition identity through fullscreen and size changes.
            Box(Modifier.padding(top = videoTop).width(videoWidth).height(videoHeight)
                .background(Color.Black).clipToBounds().revealOnMouseMove(desktop && !fillVideo && hoverEnabled, ::interact)
                .pointerInput(pointerMedia.sessionGeneration, pointerMedia.assetKey, active, locked, fullscreen, canControl,
                    state.settings.longPressSpeed, obscured, settingsPresent, watchPanelPresent, playback.inPictureInPicture) {
                    if (!active || locked || obscured || settingsPresent || watchPanelPresent || playback.inPictureInPicture) return@pointerInput
                    var accelerated = false
                    // Children consume their own buttons; blank video and passive status layers
                    // share this parent gesture instead of competing full-size sibling hit targets.
                    detectTapGestures(
                        onTap = {
                            if (!accelerated) {
                                if (latestRequiredControls) {
                                    if (desktop) focus.requestFocus()
                                    controlsVisible = true
                                } else {
                                    val revealControls = !latestChromeVisible
                                    focusManager.clearFocus()
                                    focus.requestFocus()
                                    controlsVisible = revealControls
                                }
                                interactionEpoch++
                            }
                        },
                        onDoubleTap = { point ->
                            val current = player.state.value
                            if (canControl && pointerMedia.sameMedia(current) && latestChrome.owns(current) &&
                                (current.phase == AnimePlayerPhase.READY || current.ended)) {
                                val delta = if (point.x < size.width / 2f) -10_000L else 10_000L
                                val position = (current.positionMs + delta).coerceAtLeast(0L)
                                player.seekTo(if (current.durationMs > 0L) position.coerceAtMost(current.durationMs) else position)
                                interact()
                            }
                        },
                        onPress = press@{
                            accelerated = false
                            val current = player.state.value
                            val expected = latestChrome
                            if (!canControl || !pointerMedia.sameMedia(current) || !expected.owns(current) ||
                                !current.isPlaying || current.playbackSuppressed) return@press
                            val originalSpeed = current.speed
                            coroutineScope {
                                val fast = launch {
                                    delay(viewConfiguration.longPressTimeoutMillis)
                                    val live = player.state.value
                                    if (expected.owns(live) && live.isPlaying && !live.playbackSuppressed) {
                                        accelerated = true
                                        holdingFast = true
                                        player.setSpeed(state.settings.longPressSpeed)
                                    }
                                }
                                try { tryAwaitRelease() }
                                finally {
                                    fast.cancel()
                                    if (accelerated && expected.sameMedia(player.state.value)) player.setSpeed(originalSpeed)
                                    holdingFast = false
                                }
                            }
                        },
                    )
                }, contentAlignment = Alignment.Center) {
                CompositionLocalProvider(LocalAnimeVideoGlassSource provides videoDockSource) {
                    AnimePlayerSurface(player, Modifier.fillMaxSize())
                }
                if (desktop && hoverEnabled && canControl && playback.hasTimeline && !showStage &&
                    target == null && !state.resolving && state.pendingPlayback == null) {
                    // A backmost hit target, not a parent of the buttons, timeline or panels.
                    // The stock touch detector still handles non-mouse pointers.
                    Box(Modifier.matchParentSize().desktopVideoSeek(pointerMedia) { seekBareVideo(pointerMedia, it) })
                }
                AnimePlaybackObservation(observations) { current ->
                    AnimeDanmakuOverlay(controller, current, visible = playback.owns(current) &&
                        active && !obscured && (!panelPresent || headerOverVideo) && !blockingResolution && failure == null,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp))
                }
                if (posterLayer != null && subject != null) key(posterLayer) {
                    val context = LocalMangaContext.current
                    val request = remember(context, subject.coverUrl, posterSource) {
                        ImageRequest.Builder(imagePlatformContext(context))
                            .animeArtwork(subject.coverUrl, posterSource).build()
                    }
                    AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                        onSuccess = { posterLoaded.value = true }, onError = { posterLoaded.value = false },
                        onLoading = { posterLoaded.value = false },
                        modifier = Modifier.fillMaxSize().then(if (artwork != null) Modifier.mangaHazeSource(artwork) else Modifier)
                            .graphicsLayer { alpha = .48f })
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
                if (holdingFast && !playback.inPictureInPicture)
                    AnimePlaybackObservation(observations) { current ->
                        if (playback.owns(current) && !current.inPictureInPicture)
                            AnimePlaybackSurface(artwork, Modifier.align(Alignment.TopCenter).padding(top = statusTop + 6.dp)) {
                                Text(animeSpeedLabel(current.speed), Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                    style = MaterialTheme.typography.labelLarge)
                            }
                    }
                val readingsRoom = videoHeight - statusTop - statusBottom - 16.dp
                val readingsHeight = minOf(160.dp, if (centralTransport && chromeReveal > 0f)
                    (readingsRoom - 64.dp) * .5f else readingsRoom * .5f)
                val showVideoReadings = readingsVisible && !panelPresent && !playback.inPictureInPicture &&
                    showingCurrentPlayback && failure == null && !blockingResolution && !awaitingVideo && readingsHeight >= 44.dp
                if (showVideoReadings) {
                    AnimePlaybackObservation(observations) { current ->
                        if (!playback.owns(current) || current.inPictureInPicture) return@AnimePlaybackObservation
                        MaterialTheme(colorScheme = videoColors) {
                            AnimePlaybackStatus(current, quiet = !showChrome,
                                observing = active && !obscured && !settingsPresent && !watchPanelPresent,
                                interactive = active && showChrome && !locked && !obscured && !settingsPresent && !watchPanelPresent,
                                maximumHeight = readingsHeight,
                                onInteraction = ::interact,
                                onHoldControls = { readingsHeld = it },
                                modifier = Modifier.align(Alignment.TopStart)
                                    .padding(start = 14.dp, end = 14.dp, top = statusTop + 8.dp)
                                    .widthIn(max = 320.dp),
                                nextPreparation = state.nextEpisodePreparation,
                                nextPreparationEnabled = state.settings.prepareNextEpisode)
                        }
                    }
                }
                val quietFontScale = density.fontScale.coerceAtLeast(1f)
                // Optional diagnostics retain their own left-hand panel. Reserve its height on
                // smaller desktop players rather than letting the passive right label cover it.
                val resolutionTop = maxOf(statusTop, topInset) + 8.dp +
                    if (showVideoReadings && videoWidth < 630.dp * quietFontScale) readingsHeight + 4.dp else 0.dp
                if (desktop && active && !locked && !obscured && !panelPresent && !holdingFast &&
                    !playback.inPictureInPicture && !showStage && showingCurrentPlayback &&
                    playback.phase == AnimePlayerPhase.READY && !playback.isBuffering && !playback.playbackSuppressed &&
                    playback.hasVideoTracks != false && videoWidth >= 200.dp * quietFontScale &&
                    videoHeight - resolutionTop - statusBottom >= 32.dp * quietFontScale && subject != null) {
                    AnimePlaybackObservation(observations) { current ->
                        if (playback.owns(current) && controller.ownsVideoReadout(subject.id, current, includePaused = true))
                            AnimeDesktopVideoResolution(current, Modifier.align(Alignment.TopEnd)
                                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Horizontal))
                                .padding(top = resolutionTop, end = 12.dp).widthIn(max = videoWidth * .82f))
                    }
                } else if (!desktop && active && !showChrome && chromeReveal <= 0f && !locked && !obscured && !panelPresent &&
                    !readingsVisible && !holdingFast && !playback.inPictureInPicture && !showStage &&
                    showingCurrentPlayback && playback.isPlaying && !playback.playbackSuppressed &&
                    playback.hasVideoTracks != false && videoWidth >= 220.dp * quietFontScale &&
                    videoHeight - (if (headerOverVideo) topInset else 0.dp) >= 90.dp * quietFontScale && subject != null) {
                    val insetSides = if (headerOverVideo) WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        else WindowInsetsSides.Horizontal
                    AnimePlaybackObservation(observations) { current ->
                        if (playback.owns(current) && controller.ownsVideoReadout(subject.id, current))
                            AnimeInlineVideoQuality(current, Modifier.align(Alignment.TopEnd)
                                .windowInsetsPadding(safeInsets.only(insetSides)).padding(top = 8.dp, end = 10.dp)
                                .widthIn(max = videoWidth * .75f))
                    }
                }
                if (centralTransport && canControl && !playback.inPictureInPicture && chromeReveal > 0f) {
                    Box(Modifier.fillMaxSize().padding(top = statusTop, bottom = statusBottom), contentAlignment = Alignment.Center) {
                        AnimePlaybackSurface(artwork, Modifier.graphicsLayer { alpha = chromeReveal }
                            .onFocusChanged { centerFocused = it.hasFocus }.focusGroup()
                            .motionInputGate(active && showChrome && !obscured && !settingsPresent && !watchPanelPresent),
                            cornerRadius = 32.dp) {
                            Row(Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (previous != null) MotionIconButton(onClick = { interact(); onEpisode(previous) },
                                    modifier = Modifier.size(48.dp), tooltip = "上一集") {
                                    Icon(Icons.Outlined.SkipPrevious, "上一集", Modifier.size(23.dp))
                                }
                                val playLabel = if (playback.playWhenReady) "暂停" else "播放"
                                MotionIconButton(onClick = ::toggle, modifier = Modifier.size(52.dp)
                                    .background(Color.White.copy(alpha = .15f), RoundedCornerShape(26.dp)),
                                    tooltip = "$playLabel · 空格") {
                                    Icon(if (playback.playWhenReady) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                        playLabel, Modifier.size(31.dp))
                                }
                                if (next != null) MotionIconButton(onClick = { interact(); onEpisode(next) },
                                    modifier = Modifier.size(48.dp), tooltip = "下一集") {
                                    Icon(Icons.Outlined.SkipNext, "下一集", Modifier.size(23.dp))
                                }
                            }
                        }
                    }
                }
            }

            val headerReveal = if (headerOverVideo) chromeReveal else 1f
            val headerInteractive = !headerOverVideo || showChrome
            if (!playback.inPictureInPicture && headerReveal > 0f) {
                val headerColors = if (headerOverVideo) videoColors else colors
                val headerArtwork = artwork.takeIf { headerOverVideo }
                val headerShell = Modifier.align(Alignment.TopStart)
                    .width(if (!fillVideo && headerOverVideo) videoWidth else maxWidth)
                    .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer { alpha = headerReveal; translationY = (1f - headerReveal) * -8.dp.toPx() }
                    .motionInputGate(active && headerInteractive && !obscured && !settingsPresent && !watchPanelPresent)
                    .onFocusChanged { headerFocused = it.hasFocus }.focusGroup()
                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
                Row(headerShell, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    AnimePlaybackSurface(headerArtwork, Modifier.weight(1f, fill = false).widthIn(max = 440.dp)
                        .desktopChromeHitPath(desktop),
                        overVideo = headerOverVideo) {
                        Row(Modifier.heightIn(min = 46.dp).padding(start = 2.dp, end = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val backLabel = when {
                                fullscreen -> "退出全屏"
                                browsing && browseVisit?.previewId != null -> "返回搜索结果"
                                browsing -> "收起边看边逛"
                                else -> "退出播放并保存位置"
                            }
                            MotionIconButton(onClick = {
                                if (fullscreen) onFullscreenChange(false)
                                else if (!browseBack()) onBack()
                            }, modifier = Modifier.size(44.dp).focusRequester(chromeFocus), tooltip = backLabel) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, Modifier.size(20.dp))
                            }
                            Column(Modifier.weight(1f, fill = false).padding(vertical = 3.dp)) {
                                Text(subject?.title ?: "播放", style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val caption = listOfNotNull(episode?.let(::animeEpisodeLabel),
                                    episode?.let(::animeEpisodeSubtitle)).joinToString(" · ")
                                if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall,
                                    color = headerColors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    AnimePlaybackTopActionSurface(headerArtwork, videoDockSource?.haze,
                        Modifier.desktopChromeHitPath(desktop), overVideo = headerOverVideo,
                        windowsVideo = useWindowsVideoDock && headerOverVideo) {
                        Row(Modifier.padding(horizontal = 2.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (fullscreen && canControl && !touchExploration) MotionIconButton(onClick = { locked = true },
                                modifier = Modifier.size(44.dp), tooltip = "锁定触控") {
                                Icon(Icons.Outlined.LockOpen, "锁定触控", Modifier.size(20.dp))
                            }
                            if (desktop && !fillVideo && widePlayer && detail != null) {
                                val label = when {
                                    browsing -> "收起边看边逛"
                                    !browseFits -> "扩大窗口后可边看边逛"
                                    else -> "边看边逛 · Ctrl+K"
                                }
                                MotionIconButton(onClick = {
                                    if (browsing && browseVisit != null) onBrowseClose(browseVisit) else openBrowse()
                                },
                                    enabled = browseFits && browseInteractive &&
                                        (browsing || showingCurrentPlayback && canControl && !showStage),
                                    modifier = Modifier.size(44.dp).focusRequester(browseFocus), tooltip = label) {
                                    Icon(if (browsing) Icons.Outlined.Close else Icons.Outlined.Search,
                                        label, Modifier.size(20.dp))
                                }
                            }
                            if (desktop && !browsing && !fillVideo && widePlayer && detail != null &&
                                (sideBySide || !episodesVisible)) {
                                val label = if (episodesVisible) "收起选集与评论" else "展开选集与评论"
                                MotionIconButton(onClick = { episodesVisible = !episodesVisible; interact() },
                                    modifier = Modifier.size(44.dp).semantics {
                                        stateDescription = if (episodesVisible) "选集与评论已展开" else "选集与评论已收起"
                                    }, tooltip = label) {
                                    Icon(Icons.Outlined.ViewSidebar, label, Modifier.size(20.dp))
                                }
                            }
                            MotionIconButton(onClick = ::showSettings, modifier = Modifier.size(44.dp).focusRequester(settingsFocus),
                                tooltip = "播放设置") {
                                Icon(Icons.Outlined.MoreHoriz, "播放设置", Modifier.size(22.dp))
                            }
                        }
                    }
                }
            }

            if (!playback.inPictureInPicture && chromeReveal > 0f) {
                val transportPlacement = if (fillVideo) Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    else Modifier.align(Alignment.BottomStart).offset(y = controlsTop - maxHeight).width(videoWidth)
                Box(transportPlacement
                    .onSizeChanged { footerHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer { alpha = chromeReveal; translationY = (1f - chromeReveal) * 8.dp.toPx() }
                    .motionInputGate(active && showChrome && !locked && !obscured && !settingsPresent && !watchPanelPresent)
                    .onFocusChanged { transportFocused = it.hasFocus }.focusGroup()
                    .then(if (fillVideo) Modifier.windowInsetsPadding(
                        safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) else Modifier)
                    .padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.BottomCenter) {
                    val dockColors = remember(useWindowsVideoDock, videoColors) {
                        if (useWindowsVideoDock) videoColors.copy(surface = WindowsVideoDockSurface,
                            onSurface = WindowsVideoDockInk, onSurfaceVariant = WindowsVideoDockInk,
                            primary = WindowsVideoDockInk, onPrimary = WindowsVideoDockSurface,
                            outline = WindowsVideoDockInk.copy(alpha = .36f),
                            inverseSurface = WindowsVideoDockInk, inverseOnSurface = WindowsVideoDockSurface)
                        else videoColors
                    }
                    MaterialTheme(colorScheme = dockColors) {
                        val hasTimeline = canControl && playback.hasTimeline
                        val hasNotice = state.notice != null || playback.notice != null
                        val canOpenLines = episode != null && !inlineWatching && (!showStage || compactStage)
                        val canOpenWatching = !inlineWatching && detail != null
                        val dockWidth = when {
                            hasTimeline || hasNotice -> 800.dp
                            canControl -> 340.dp
                            else -> 18.dp + 44.dp * (1 + (if (canOpenLines) 1 else 0) + (if (canOpenWatching) 1 else 0))
                        }
                        AnimePlaybackSurface(if (useWindowsVideoDock) videoDockSource?.haze else artwork,
                            Modifier.widthIn(max = dockWidth).fillMaxWidth().desktopChromeHitPath(desktop),
                            cornerRadius = 23.dp, windowsVideoDock = useWindowsVideoDock) {
                            AnimePlayerControls(player, playback, observations,
                                previous, next, onEpisode, onToggle = ::toggle,
                                onInteraction = ::interact, onHoldControls = { controlsHeld = it },
                                nextPreparation = state.nextEpisodePreparation,
                                workspaceNotice = state.notice, onDismissNotice = controller::dismissNotice,
                                centralTransport = centralTransport, canControl = canControl, desktop = desktop,
                                sourceName = currentOption?.sourceName ?: "本地播放".takeIf { showingCurrentPlayback },
                                onSources = if (canOpenLines) ::showSources else null,
                                fullscreen = fullscreen, onFullscreen = ::toggleFullscreen,
                                onWatching = if (canOpenWatching) ::openWatching else null,
                                watchingFocus = watchingFocus,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp))
                        }
                    }
                }
            }
            if (browsing && browseVisit != null) key(browseVisit) {
                AnimePlayerBrowseRail(controller, browseVisit,
                    active = browseInteractive, onClose = { onBrowseClose(browseVisit) },
                    modifier = Modifier.align(Alignment.TopEnd).width(railWidth).height(room)
                        .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top)).background(colors.surface))
            } else if (inlineWatching && !watchPanelPresent) {
                val episodePanel = if (sideBySide) Modifier.align(Alignment.TopEnd).offset(y = videoTop)
                    .width(railWidth).height(room).windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top))
                else Modifier.offset(y = controlsTop).fillMaxWidth()
                    .height((maxHeight - controlsTop - bottomInset).coerceAtLeast(0.dp))
                AnimeWatchingPane(state, episode, playbackSource, controller, backdrop, watchingTab, { watchingTab = it }, episodeListState,
                    onEpisode, { onSources(it) }, active && !obscured && !settingsPresent, desktop && sideBySide,
                    episodePanel.background(colors.surface))
            }
            if (locked && !playback.inPictureInPicture) AnimePlaybackSurface(artwork, Modifier.align(Alignment.CenterStart)
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Horizontal)).padding(start = 10.dp)) {
                MotionIconButton(onClick = { locked = false; interact() }, modifier = Modifier.size(48.dp), tooltip = "解除触控锁定") {
                    Icon(Icons.Outlined.Lock, "解除触控锁定", Modifier.size(23.dp))
                }
            }
        }
        if (settingsPresent && !playback.inPictureInPicture) AnimePlaybackObservation(observations) { current ->
            AnimePlayerSettings(settingsVisible, AnimePlayerPanel.GENERAL, controller, state, current, backdrop,
                artwork = artwork.takeIf { fillVideo }, overVideo = headerOverVideo,
                pictureInPictureFullscreen = fullscreen.takeIf { active && !obscured },
                onDismiss = { settingsVisible = false; interact() }, onClosed = { settingsPresent = false },
                onPlayDownload = onPlayDownload, onSources = onSources,
                onStorage = onStorage, readingsVisible = readingsVisible, onReadingsVisible = { readingsVisible = it })
        }
        if (watchPanelPresent && !playback.inPictureInPicture) AnimePlaybackPanel(watchPanelOpen, headerOverVideo,
            artwork.takeIf { fillVideo }, onDismiss = { watchPanelOpen = false; interact() }, onClosed = {
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

private fun Modifier.revealOnMouseMove(enabled: Boolean, onMove: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(Unit) {
        awaitPointerEventScope {
            var lastRevealMs = -1L
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Move || event.changes.any { it.pressed || it.previousPressed }) continue
                val mouse = event.changes.firstOrNull { it.type == PointerType.Mouse } ?: continue
                if (lastRevealMs < 0 || mouse.uptimeMillis - lastRevealMs >= 240L) {
                    lastRevealMs = mouse.uptimeMillis
                    onMove()
                }
            }
        }
    }

// Match an ordinary click rather than treating a drag, wheel or mouse chord as a seek.
// Read raw Press/Release because Foundation's tap detector is primary-button oriented.
private fun Modifier.desktopVideoSeek(owner: AnimePlaybackChrome, onSeek: (Long) -> Unit): Modifier =
    pointerInput(owner.sessionGeneration, owner.timelineGeneration, owner.assetKey, owner.episodeId) {
        awaitPointerEventScope {
            var down: PointerInputChange? = null
            var delta = 0L
            fun inside(change: PointerInputChange) = change.position.x >= 0f && change.position.x < size.width &&
                change.position.y >= 0f && change.position.y < size.height
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val mouse = event.changes.singleOrNull()?.takeIf { it.type == PointerType.Mouse }
                if (mouse == null) { down = null; continue }
                val held = down
                if (held == null) {
                    if (event.type != PointerEventType.Press || mouse.isConsumed || !inside(mouse) ||
                        event.buttons.indexOfFirstPressed() != event.buttons.indexOfLastPressed()) continue
                    delta = when {
                        event.buttons.isPrimaryPressed -> -10_000L
                        event.buttons.isSecondaryPressed -> 10_000L
                        else -> continue
                    }
                    down = mouse
                    mouse.consume() // The ancestor's touch tap/hold must not also claim this stroke.
                } else if (mouse.id != held.id || mouse.isConsumed || !inside(mouse) ||
                    (mouse.position - held.position).getDistance() > viewConfiguration.touchSlop ||
                    event.type == PointerEventType.Press || event.type == PointerEventType.Exit ||
                    event.type == PointerEventType.Scroll) {
                    down = null
                } else if (event.type == PointerEventType.Release) {
                    down = null
                    if (!event.buttons.areAnyPressed) {
                        mouse.consume()
                        onSeek(delta)
                    }
                }
            }
        }
    }

// Chrome can own passive padding/labels as well as its clickable children.
private fun Modifier.desktopChromeHitPath(desktop: Boolean): Modifier =
    if (desktop) pointerInput(Unit) { } else this

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
                    val reference = focusedDetail?.let { shown ->
                        shown.subject.takeIf { it.canonicalBangumiId() != null }
                            ?: entry?.nativeMetadata
                            ?: shown.metadataSubject.takeUnless { entry?.metadataAutoMatchDisabled == true }
                    }
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
    playback: AnimePlaybackChrome, hasAudioAlternative: Boolean, canChooseSource: Boolean, canRetry: Boolean,
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
        showBuffering -> AnimePlaybackSurface(null, cornerRadius = 18.dp) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (LocalMotionEnabled.current) CircularProgressIndicator(Modifier.size(12.dp), color = Color.White, strokeWidth = 1.5.dp)
                Text("正在缓冲", style = MaterialTheme.typography.labelMedium)
            }
        }
        playback.ended -> AnimePlaybackSurface(null, cornerRadius = 20.dp) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
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
private fun AnimePlayerControls(player: AnimePlayer, state: AnimePlaybackChrome, observations: State<AnimePlayerState>,
    previous: AnimeEpisode?, next: AnimeEpisode?, onEpisode: (AnimeEpisode) -> Unit,
    onToggle: () -> Unit, onInteraction: () -> Unit,
    onHoldControls: (Boolean) -> Unit, workspaceNotice: String?, onDismissNotice: () -> Unit,
    nextPreparation: AnimeNextEpisodePreparation?, centralTransport: Boolean, canControl: Boolean, desktop: Boolean,
    sourceName: String?, onSources: (() -> Unit)?,
    fullscreen: Boolean, onFullscreen: () -> Unit, onWatching: (() -> Unit)?, watchingFocus: FocusRequester,
    modifier: Modifier = Modifier) {
    var scrub by remember(state.sessionGeneration, state.timelineGeneration, state.assetKey, state.episodeId) {
        mutableStateOf<Long?>(null)
    }
    val canSeek = canControl && state.hasTimeline
    val releaseControls by rememberUpdatedState(onHoldControls)
    BoxWithConstraints(modifier) {
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val wide = maxWidth / fontScale >= 380.dp
        val namedLine = onSources != null && sourceName != null && maxWidth / fontScale >= 520.dp
        val previousButton = !desktop && !centralTransport && canControl && wide && previous != null
        val playButton = !centralTransport && canControl
        val nextButton = !desktop && playButton && next != null && !state.ended
        val iconSlots = 1 + listOf(previousButton, playButton, nextButton, onWatching != null,
            !namedLine && onSources != null).count { it }
        val actionWidth = 44.dp * iconSlots + if (namedLine) 138.dp else 0.dp
        val timelineWidth = (if (state.hourTimeline) 216.dp else 176.dp) * fontScale
        val inlineTimeline = canSeek && maxWidth >= actionWidth + timelineWidth + 6.dp
        val desktopEpisodeRoom = desktop && inlineTimeline && !state.ended &&
            maxWidth >= actionWidth + timelineWidth + 50.dp
        val desktopNext = desktopEpisodeRoom && next != null
        val desktopPrevious = desktopEpisodeRoom && previous != null &&
            (!desktopNext || maxWidth >= actionWidth + timelineWidth + 94.dp)
        DisposableEffect(canSeek, state.sessionGeneration, state.timelineGeneration, state.assetKey, inlineTimeline) {
            // A changed timeline or a moved slider can end a drag before onValueChangeFinished.
            scrub = null
            releaseControls(false)
            onDispose {
                scrub = null
                releaseControls(false)
            }
        }
        val timeline: @Composable (Modifier) -> Unit = { placement ->
            val timelineFocus = remember(state.sessionGeneration, state.assetKey, state.episodeId) { FocusRequester() }
            var timelineFocused by remember(state.sessionGeneration, state.assetKey, state.episodeId) { mutableStateOf(false) }
            AnimePlaybackObservation(observations) { current ->
                if (!state.owns(current) || current.durationMs <= 0L) return@AnimePlaybackObservation
                val duration = current.durationMs
                val bufferedRange = current.bufferedMs.takeIf {
                    current.phase == AnimePlayerPhase.READY && current.assetKey != null &&
                        current.positionMs in 0L until duration && it > current.positionMs
                }?.let { current.positionMs.toFloat()..it.coerceAtMost(duration).toFloat() }
                Row(placement, verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
                    Text(animeTime(scrub ?: current.positionMs), style = timeStyle, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = MaterialTheme.colorScheme.onSurface)) {
                        val restoreFocus = timelineFocused
                        // Material's drag otherwise follows replaced callbacks into a new timeline.
                        key(state.sessionGeneration, state.timelineGeneration, state.assetKey, state.episodeId) {
                            LaunchedEffect(timelineFocus) {
                                if (restoreFocus) {
                                    withFrameNanos { }
                                    timelineFocus.requestFocus()
                                }
                            }
                            MotionSlider(value = (scrub ?: current.positionMs).toFloat(),
                                onValueChange = {
                                    if (state.owns(player.state.value)) { onHoldControls(true); scrub = it.toLong() }
                                },
                                valueRange = 0f..duration.toFloat(),
                                bufferedRange = bufferedRange,
                                onValueChangeFinished = {
                                    val requested = scrub
                                    val live = player.state.value
                                    if (requested != null && state.owns(live) && live.durationMs > 0L &&
                                        (live.phase == AnimePlayerPhase.READY || live.ended))
                                        player.seekTo(requested.coerceIn(0L, live.durationMs))
                                    scrub = null
                                    onHoldControls(false)
                                    onInteraction()
                                },
                                formatValue = { animeTime(it.toLong()) },
                                modifier = Modifier.weight(1f).focusRequester(timelineFocus)
                                    .onFocusChanged { timelineFocused = it.hasFocus }.focusGroup()
                                    .semantics { contentDescription = "播放进度" })
                        }
                    }
                    Text(animeTime(duration), style = timeStyle, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val transport: @Composable () -> Unit = {
            if (previousButton && previous != null) MotionIconButton(onClick = { onInteraction(); onEpisode(previous) },
                modifier = Modifier.size(44.dp), tooltip = "上一集") {
                Icon(Icons.Outlined.SkipPrevious, "上一集", Modifier.size(21.dp))
            }
            if (playButton) {
                val playLabel = if (state.ended) "重播" else if (state.playWhenReady) "暂停" else "播放"
                MotionIconButton(onClick = onToggle, modifier = Modifier.size(44.dp), tooltip = "$playLabel · 空格") {
                    Icon(if (state.ended) Icons.Outlined.Replay else if (state.playWhenReady)
                        Icons.Outlined.Pause else Icons.Outlined.PlayArrow, playLabel, Modifier.size(28.dp))
                }
            }
            if (nextButton && next != null) MotionIconButton(onClick = { onInteraction(); onEpisode(next) },
                modifier = Modifier.size(44.dp), tooltip = nextPreparation?.takeIf { it.episodeId == next.id }
                    ?.let { "下一集 · ${animePreparationLabel(it)}" } ?: "下一集") {
                Icon(Icons.Outlined.SkipNext, "下一集", Modifier.size(21.dp))
            }
        }
        Column {
            val notice = workspaceNotice ?: state.notice
            if (notice != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (workspaceNotice != null) MotionIconButton(onClick = { onInteraction(); onDismissNotice() },
                    modifier = Modifier.size(44.dp), tooltip = "关闭提示") {
                    Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp))
                }
            }
            if (canSeek && !inlineTimeline) timeline(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                if (inlineTimeline) {
                    transport()
                    timeline(Modifier.weight(1f).padding(horizontal = 3.dp))
                } else {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically) { transport() }
                }
                if (desktopPrevious && previous != null)
                    MotionIconButton(onClick = { onInteraction(); onEpisode(previous) },
                        modifier = Modifier.size(44.dp), tooltip = "上一集") {
                        Icon(Icons.Outlined.SkipPrevious, "上一集", Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                if (desktopNext && next != null)
                    MotionIconButton(onClick = { onInteraction(); onEpisode(next) },
                        modifier = Modifier.size(44.dp), tooltip = nextPreparation?.takeIf { it.episodeId == next.id }
                            ?.let { "下一集 · ${animePreparationLabel(it)}" } ?: "下一集") {
                        Icon(Icons.Outlined.SkipNext, "下一集", Modifier.size(19.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                if (onSources != null) {
                    val sourceLabel = sourceName?.let { "换线路，当前 $it" } ?: "选择播放线路"
                    if (namedLine && sourceName != null) MotionTextButton(onClick = onSources,
                        modifier = Modifier.widthIn(max = 138.dp).heightIn(min = 44.dp),
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
