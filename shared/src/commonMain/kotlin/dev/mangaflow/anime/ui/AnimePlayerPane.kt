package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.*
import dev.mangaflow.enhancement.ui.EnhancementPage
import dev.mangaflow.platform.PlatformKind
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
    val activeEpisode = detail?.episodes?.firstOrNull { it.id == (state.selectedEpisodeId ?: playback.episodeId) }
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
    val confirmedOptions = remember(episode?.id, state.detail, state.episodeOptions, state.sourceCatalogs,
        state.sourceBindings, state.sourceExtensions) {
        episode?.let { controller.confirmedPlaybackLines(it.id, state) }.orEmpty()
    }
    val currentOption = state.selectedPlaybackOption.takeIf {
        showingCurrentPlayback && episode != null && state.selectedEpisodeId == episode.id
    }
    val usableOptions = remember(episode?.id, confirmedOptions, state.sourceRoutes, target, playback.phase) {
        confirmedOptions.filter { episode != null && controller.canAttemptPlaybackLine(episode.id, it, state) }
    }
    val optionCount = usableOptions.size
    val ordered = remember(detail?.episodes, episode?.kind) { detail?.episodes.orEmpty().filter { it.kind == episode?.kind } }
    val index = ordered.indexOfFirst { it.id == episode?.id }
    val previous = ordered.getOrNull(index - 1).takeIf { index > 0 }
    val next = ordered.getOrNull(index + 1).takeIf { index >= 0 }
    val nextReady = next?.let { controller.episodeAvailability(it.id, state) == AnimeEpisodeAvailability.MATCHED } == true
    val playerFailure = playback.error ?: if (playback.phase == AnimePlayerPhase.ERROR)
        "播放器未能准备视频，请重试或选择其他来源。" else null
    val playbackSource = currentOption?.sourceName
    val incident = target?.failure ?: playerFailure?.let { message -> currentOption?.let {
        AnimePlaybackFailure(it.sourceId, it.sourceName, "播放视频", message)
    } }
    val error = incident?.reason ?: resolutionError ?: playerFailure
    val failure = error?.let { listOfNotNull(it, state.playbackRecoveryStopReason).joinToString("\n") }
    val hasAlternative = incident != null && usableOptions.any { it.sourceId != incident.sourceId }
    val canRetry = if (target?.manualOption != null && episode != null)
        controller.canAttemptPlaybackLine(episode.id, target.manualOption, state)
    else optionCount > 0 || target == null && currentOption == null && playback.phase == AnimePlayerPhase.ERROR
    val focus = remember { FocusRequester() }
    val chromeFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val touchExploration = LocalTouchExplorationEnabled.current
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHeld by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }
    var transportFocused by remember { mutableStateOf(false) }
    var centerFocused by remember { mutableStateOf(false) }
    var focusChromeOnReveal by remember { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var episodesVisible by rememberSaveable { mutableStateOf(true) }
    val episodeListState = key(detail?.subject?.id) { rememberSaveableStateHolder() }
    var holdingFast by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var settingsPresent by remember { mutableStateOf(false) }
    var discussionEpisode by remember(detail?.subject?.id) { mutableStateOf<AnimeEpisode?>(null) }
    var discussionVisible by remember(detail?.subject?.id) { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(AnimePlayerPanel.GENERAL) }
    var settingsEnhancementPage by remember { mutableStateOf(EnhancementPage.QUALITY) }
    var interactionEpoch by remember { mutableIntStateOf(0) }
    val requiredControls = blockingResolution || failure != null || awaitingVideo ||
        playback.phase != AnimePlayerPhase.READY || !playback.playWhenReady || obscured || settingsPresent || discussionVisible ||
        state.notice != null || touchExploration
    val forceControls = requiredControls || headerFocused || transportFocused || centerFocused
    val showChrome = !playback.inPictureInPicture && (!locked || touchExploration) &&
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
    var controlsHeight by remember(density) { mutableStateOf(140.dp) }
    var footerHeight by remember(density) { mutableStateOf(44.dp) }
    val canControl = !blockingResolution && failure == null &&
        (playback.phase == AnimePlayerPhase.READY || playback.ended)
    fun interact() { controlsVisible = true; interactionEpoch++ }
    fun showSources() { interact(); onSources(episode) }
    fun showSettings(section: AnimePlayerPanel) {
        interact(); settingsSection = section; settingsEnhancementPage = EnhancementPage.QUALITY
        settingsPresent = true; settingsVisible = true
    }
    fun showEnhancement(page: EnhancementPage) {
        interact(); settingsSection = AnimePlayerPanel.VIDEO; settingsEnhancementPage = page
        settingsPresent = true; settingsVisible = true
    }
    fun toggleFullscreen() { interact(); onFullscreenChange(!fullscreen) }
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
    LaunchedEffect(active, showChrome, focusChromeOnReveal, chromeReveal > 0f, obscured, settingsPresent) {
        if (active && showChrome && chromeReveal > 0f && focusChromeOnReveal && !obscured && !settingsPresent) {
            withFrameNanos { }
            chromeFocus.requestFocus()
            focusChromeOnReveal = false
        } else if (!active || obscured || settingsPresent) {
            focusChromeOnReveal = false
        }
    }
    LaunchedEffect(canControl) { if (!canControl) { transportFocused = false; centerFocused = false } }
    LaunchedEffect(playback.inPictureInPicture) {
        if (playback.inPictureInPicture) {
            settingsVisible = false; settingsPresent = false; discussionVisible = false; locked = false
        }
    }
    MotionBackHandler(active && locked && !obscured, state = null, onBack = { locked = false; interact() })
    LaunchedEffect(fullscreen, controlsVisible, forceControls, controlsHeld, interactionEpoch, active) {
        if (active && fullscreen && controlsVisible && !forceControls && !controlsHeld) {
            delay(3_500L)
            controlsVisible = false
        }
    }
    AnimePlayerOrientation(fullscreen, active = active && !playback.inPictureInPicture)
    // This shared window policy restores the previous flags on UI disposal without stopping playback.
    if (active) ReaderWindow(
        keepScreenOn = state.resolving || playback.playWhenReady &&
            playback.phase in listOf(AnimePlayerPhase.OPENING, AnimePlayerPhase.READY),
        controlsVisible = !fillVideo || showChrome,
        lightChrome = !fillVideo && colors.background.luminance() > .5f,
    )
    val videoColors = colors.copy(
        background = Color.Black, surface = Color(0xFF171A21),
        onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = .68f),
        primary = Color(0xFFE4EDFF), onPrimary = Color(0xFF162744), outline = Color.White.copy(alpha = .32f),
        inverseSurface = Color(0xFFE4EDFF), inverseOnSurface = Color(0xFF162744),
    )
    MaterialTheme(colorScheme = if (fillVideo) videoColors else colors) {
      CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        BoxWithConstraints(modifier.fillMaxSize().focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (active && !showChrome && !locked && !obscured && !settingsPresent &&
                    !playback.inPictureInPicture && event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
                    focusChromeOnReveal = true
                    interact()
                    true
                } else false
            }
            .onKeyEvent { event ->
                // Focused buttons and the native slider get first refusal.
                    if (!active || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
                    event.type != KeyEventType.KeyUp || obscured || settingsPresent || discussionVisible || playback.inPictureInPicture) false
                else if (locked) { if (event.key == Key.Escape) { locked = false; true } else false }
                else when (event.key) {
                    Key.Spacebar -> { toggle(); true }
                    Key.DirectionLeft -> { seek(-10_000L); true }
                    Key.DirectionRight -> { seek(10_000L); true }
                    Key.F -> { toggleFullscreen(); true }
                    Key.Escape -> if (fullscreen) { onFullscreenChange(false); true } else false
                    else -> false
                }
            }.pointerInput(fullscreen, active, locked, obscured, settingsPresent, playback.inPictureInPicture) {
                if (!fullscreen || !active || locked || obscured || settingsPresent || playback.inPictureInPicture)
                    return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move && event.changes.any { it.type == PointerType.Mouse } &&
                            event.changes.none { it.pressed || it.previousPressed }) interact()
                    }
                }
            }.focusable()) {
            val sideBySide = if (desktop) episodesVisible && maxWidth >= 736.dp
                else maxWidth >= 650.dp && (maxWidth >= 920.dp || maxHeight < 520.dp)
            val railWidth = if (desktop) (maxWidth * .26f).coerceIn(248.dp, 328.dp)
                else (maxWidth * .32f).coerceIn(214.dp, 330.dp)
            val videoWidth = if (fillVideo || !sideBySide) maxWidth else maxWidth - railWidth
            val videoTop = if (headerOverVideo) 0.dp else headerHeight
            val room = (maxHeight - videoTop - bottomInset).coerceAtLeast(1.dp)
            val reserveEpisodes = if (room >= 368.dp) 164.dp else if (room >= 260.dp) 108.dp else 0.dp
            val videoHeight = when {
                fillVideo -> maxHeight
                desktop && (sideBySide || !episodesVisible) -> minOf(room, videoWidth * .5625f)
                sideBySide -> room
                else -> (videoWidth * .5625f).coerceAtLeast(minOf(184.dp, room))
                    .coerceAtMost((room - reserveEpisodes).coerceAtLeast(1.dp))
            }
            val overlayTransport = fillVideo || (!desktop && sideBySide) ||
                room - videoHeight < (if (desktop) 160.dp else 244.dp)
            val centralTransport = (fillVideo || sideBySide) && canControl && !playback.ended &&
                !playback.isBuffering && !awaitingVideo && playback.hasVideoTracks != false &&
                state.notice == null && playback.notice == null &&
                videoHeight >= 250.dp * density.fontScale.coerceAtLeast(1f)
            val transportHeight = if (episode != null && !overlayTransport && !playback.inPictureInPicture) controlsHeight else 0.dp
            val controlsTop = videoTop + videoHeight
            val episodeTop = controlsTop + transportHeight
            val latestPlayback by rememberUpdatedState(playback)
            val latestRequiredControls by rememberUpdatedState(requiredControls)
            val latestChromeVisible by rememberUpdatedState(showChrome)

            // One native surface keeps its composition identity through fullscreen and size changes.
            Box(Modifier.padding(top = videoTop).width(videoWidth).height(videoHeight)
                .background(Color.Black).pointerInput(playback.sessionGeneration, active, locked, fullscreen, canControl,
                    state.settings.longPressSpeed, obscured, settingsPresent, playback.inPictureInPicture) {
                    if (!active || locked || obscured || settingsPresent || playback.inPictureInPicture) return@pointerInput
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
                    visible = active && !obscured && !settingsPresent && !blockingResolution && failure == null,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp))
                if (!playback.inPictureInPicture && (blockingResolution || failure != null)) Box(Modifier.fillMaxSize().background(Color.Black.copy(
                    alpha = if (playback.firstFrameRendered == true || playback.phase == AnimePlayerPhase.READY) .45f else 1f)))
                val statusTop = if (headerOverVideo && chromeReveal > 0f) headerHeight else 0.dp
                val statusBottom = if (overlayTransport) footerHeight else 0.dp
                if (!playback.inPictureInPicture) Box(Modifier.fillMaxSize()
                    .padding(top = statusTop, bottom = statusBottom)
                    .motionInputGate(active && !locked && !obscured && !settingsPresent),
                    contentAlignment = Alignment.Center) {
                    MaterialTheme(colorScheme = videoColors) {
                      CompositionLocalProvider(LocalContentColor provides videoColors.onSurface) {
                        AnimeVideoStatus(
                            resolving = blockingResolution, autoMatching = state.autoMatching,
                            sourceName = if (target == null) playbackSource else target.sourceName,
                            failure = failure, incident = incident, playback = playback,
                            optionsAvailable = optionCount, hasAlternative = hasAlternative,
                            canChooseSource = episode != null, canRetry = canRetry,
                            compact = videoHeight - statusTop - statusBottom < 196.dp,
                            next = next, nextReady = nextReady, onRetry = onRetry, onSources = ::showSources, onEpisode = onEpisode,
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
                if (centralTransport && canControl && !playback.inPictureInPicture && chromeReveal > 0f) {
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                      Box(Modifier.fillMaxSize().padding(top = statusTop, bottom = statusBottom), contentAlignment = Alignment.Center) {
                        Row(Modifier
                            .graphicsLayer { alpha = chromeReveal }
                            .onFocusChanged { centerFocused = it.hasFocus }.focusGroup()
                            .motionInputGate(active && showChrome && !obscured && !settingsPresent),
                            horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (previous != null) MotionIconButton(onClick = { interact(); onEpisode(previous) },
                                modifier = Modifier.size(48.dp), tooltip = "上一集") {
                                Icon(Icons.Outlined.SkipPrevious, "上一集", Modifier.size(26.dp))
                            } else Spacer(Modifier.size(48.dp))
                            MotionIconButton(onClick = ::toggle, modifier = Modifier.size(64.dp)
                                .background(Color.Black.copy(alpha = .24f), RoundedCornerShape(32.dp)),
                                tooltip = if (playback.playWhenReady && !playback.ended) "暂停 · 空格" else "播放 · 空格") {
                                Icon(if (playback.ended) Icons.Outlined.Replay else if (playback.playWhenReady)
                                    Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                    if (playback.ended) "重播" else if (playback.playWhenReady) "暂停" else "播放", Modifier.size(36.dp))
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

            if (chromeReveal > 0f) {
                val headerColors = if (headerOverVideo) videoColors else colors
                val headerSurface = if (headerOverVideo) Modifier.background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = .86f), Color.Transparent)),
                ) else Modifier
                CompositionLocalProvider(LocalContentColor provides headerColors.onSurface) {
                    Row(
                        Modifier.align(Alignment.TopStart).width(if (desktop && !fillVideo) videoWidth else maxWidth)
                            .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                            .graphicsLayer { alpha = chromeReveal; translationY = (1f - chromeReveal) * -8.dp.toPx() }
                            .motionInputGate(active && showChrome && !obscured && !settingsPresent)
                            .onFocusChanged { headerFocused = it.hasFocus }.focusGroup()
                            .then(headerSurface)
                            .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .heightIn(min = if (desktop && !fullscreen) 48.dp else 56.dp)
                            .padding(horizontal = 8.dp, vertical = if (desktop && !fullscreen) 2.dp else 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        MotionIconButton(onClick = { if (fullscreen) onFullscreenChange(false) else onBack() },
                            modifier = Modifier.size(44.dp).focusRequester(chromeFocus),
                            tooltip = if (fullscreen) "退出全屏" else "退出播放并保存位置") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (fullscreen) "退出全屏" else "退出播放", Modifier.size(20.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(detail?.subject?.title ?: "播放", style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val caption = listOfNotNull(episode?.let(::animeEpisodeLabel),
                                (if (keepingCurrentVideo) currentOption?.sourceName else target?.sourceName ?: currentOption?.sourceName)
                                    ?.takeIf { it.isNotBlank() }).joinToString(" · ")
                            if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.labelSmall,
                                color = headerColors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (fullscreen && canControl && !touchExploration) MotionIconButton(onClick = { locked = true },
                            modifier = Modifier.size(44.dp), tooltip = "锁定触控") {
                            Icon(Icons.Outlined.LockOpen, "锁定触控", Modifier.size(20.dp))
                        }
                        if (episode != null) MotionTextButton(onClick = ::showSources, modifier = Modifier.heightIn(min = 44.dp)) {
                            Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("换线路", maxLines = 1)
                        }
                        if (episode != null) MotionIconButton(onClick = {
                            interact(); discussionEpisode = episode; discussionVisible = true
                        }, modifier = Modifier.size(44.dp), tooltip = "本集讨论") {
                            Icon(Icons.Outlined.Forum, "本集讨论", Modifier.size(20.dp))
                        }
                        if (desktop && !fillVideo && detail != null) {
                            val label = if (episodesVisible) "收起选集" else "展开选集"
                            MotionIconButton(onClick = { episodesVisible = !episodesVisible; interact() },
                                modifier = Modifier.size(44.dp).semantics {
                                    stateDescription = if (episodesVisible) "选集已展开" else "选集已收起"
                                }, tooltip = label) {
                                Icon(Icons.Outlined.ViewSidebar, label, Modifier.size(20.dp))
                            }
                        }
                        MotionIconButton(onClick = { showSettings(AnimePlayerPanel.GENERAL) }, modifier = Modifier.size(44.dp),
                            tooltip = "播放设置") {
                            Icon(Icons.Outlined.Settings, "播放设置", Modifier.size(20.dp))
                        }
                        if (fullscreen) MotionIconButton(onClick = ::toggleFullscreen, modifier = Modifier.size(44.dp),
                            tooltip = "退出全屏 · F") {
                            Icon(Icons.Outlined.FullscreenExit, "退出全屏", Modifier.size(21.dp))
                        }
                    }
                }
            }

            if (!playback.inPictureInPicture && episode != null) {
                val transportPlacement = when {
                    fillVideo -> Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    overlayTransport -> Modifier.align(Alignment.BottomStart)
                        .offset(y = controlsTop - maxHeight).width(videoWidth)
                    else -> Modifier.offset(y = controlsTop).width(videoWidth)
                }
                val panel = if (overlayTransport) Modifier.background(Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = .24f + .5f * chromeReveal)))) else Modifier.background(colors.surface)
                Box(transportPlacement
                    .onSizeChanged {
                        footerHeight = with(density) { it.height.toDp() }
                        if (!overlayTransport) controlsHeight = footerHeight
                    }
                    .onFocusChanged { transportFocused = it.hasFocus }.focusGroup()
                    .then(panel), contentAlignment = Alignment.BottomCenter) {
                    MaterialTheme(colorScheme = if (overlayTransport) videoColors else colors) {
                      CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        Column(Modifier.widthIn(max = 960.dp).fillMaxWidth()
                            .then(if (fillVideo) Modifier.windowInsetsPadding(
                                safeInsets.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)) else Modifier)
                            .padding(horizontal = 12.dp)) {
                            // Inline playback retains its measured transport space when the user
                            // hides controls. The native video and episode list do not jump.
                            CopyShelfReveal(canControl && (showChrome || !overlayTransport)) {
                                AnimePlayerControls(controller.player, playback,
                                    previous, next, onEpisode, onToggle = ::toggle,
                                    onInteraction = ::interact, onHoldControls = { controlsHeld = it },
                                    onSettings = ::showSettings, nextPreparation = state.nextEpisodePreparation,
                                    workspaceNotice = state.notice, onDismissNotice = controller::dismissNotice,
                                    centralTransport = centralTransport,
                                    modifier = Modifier.fillMaxWidth()
                                        .graphicsLayer { if (!overlayTransport) alpha = chromeReveal }
                                        .motionInputGate(active && showChrome && !obscured && !settingsPresent))
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                AnimePlaybackStatus(playback.takeIf { showingCurrentPlayback }, quiet = !showChrome,
                                    observing = active && !obscured && !settingsPresent, modifier = Modifier.weight(1f),
                                    nextPreparation = state.nextEpisodePreparation,
                                    nextPreparationEnabled = state.settings.prepareNextEpisode)
                                if (showChrome) MotionIconButton(onClick = { showEnhancement(EnhancementPage.DISPLAY) },
                                    modifier = Modifier.size(44.dp),
                                    enabled = active && !locked && !obscured && !settingsPresent,
                                    tooltip = "选择观看读数") {
                                    Icon(Icons.Outlined.Tune, "选择观看读数", Modifier.size(18.dp))
                                }
                                if (!fullscreen) MotionIconButton(onClick = ::toggleFullscreen,
                                    modifier = Modifier.size(44.dp),
                                    enabled = active && !locked && !obscured && !settingsPresent,
                                    tooltip = "全屏播放 · F") {
                                    Icon(Icons.Outlined.Fullscreen, "全屏播放", Modifier.size(23.dp))
                                }
                            }
                        }
                      }
                    }
                }
            }
            if (!fillVideo && episodesVisible) {
                val episodePanel = if (sideBySide) Modifier.align(Alignment.TopEnd).offset(y = videoTop)
                    .width(railWidth).height(room)
                else Modifier.offset(y = episodeTop).fillMaxWidth()
                    .height((maxHeight - episodeTop - bottomInset).coerceAtLeast(0.dp))
                val entry = remember(detail?.subject?.id, state.library) {
                    state.library.firstOrNull { it.subject.id == detail?.subject?.id }
                }
                episodeListState.SaveableStateProvider("episodes") {
                    AnimePlayerEpisodes(detail?.episodes.orEmpty(), episode?.id, entry?.completedEpisodeIds.orEmpty(),
                        onEpisode, episodeAvailability = { controller.episodeAvailability(it, state) },
                        active = active && !obscured && !settingsPresent, desktopRail = desktop && sideBySide,
                        modifier = episodePanel)
                }
            }
            if (locked && !playback.inPictureInPicture) MotionIconButton(onClick = { locked = false; interact() },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(48.dp), tooltip = "解除触控锁定") {
                Icon(Icons.Outlined.Lock, "解除触控锁定", Modifier.size(24.dp), tint = Color.White)
            }
        }
        if (!playback.inPictureInPicture) AnimePlayerSettings(settingsVisible, settingsSection, controller, state, playback, backdrop,
            onDismiss = { settingsVisible = false; interact() }, onClosed = { settingsPresent = false },
            onPlayDownload = onPlayDownload, onSources = onSources, initialEnhancementPage = settingsEnhancementPage,
            onStorage = onStorage)
        discussionEpisode?.let { focused ->
            val reference = detail?.subject?.takeIf { it.canonicalBangumiId() != null } ?:
                state.library.firstOrNull { it.subject.id == focused.subjectId }?.nativeMetadata
            AnimeEpisodeDiscussionPanel(discussionVisible, focused, detail?.episodes.orEmpty(), reference?.id,
                backdrop, controller, onDismiss = { discussionVisible = false; interact() },
                onClosed = { if (!discussionVisible) discussionEpisode = null })
        }
      }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeVideoStatus(
    resolving: Boolean, autoMatching: Boolean, sourceName: String?, failure: String?, incident: AnimePlaybackFailure?,
    playback: AnimePlayerState, optionsAvailable: Int, hasAlternative: Boolean, canChooseSource: Boolean, canRetry: Boolean,
    compact: Boolean, next: AnimeEpisode?, nextReady: Boolean,
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
            PlayerStageMessage(if (autoMatching) "正在查找本集线路" else "正在打开本集", sourceName)
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
                if (canChooseSource && (hasAlternative || optionsAvailable > 1 || !canRetry)) {
                    MotionButton(onClick = onSources, shape = shape, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(if (hasAlternative || optionsAvailable > 1) "换线路" else "查看来源")
                    }
                    if (canRetry) MotionTextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("重试本集", color = Color.White)
                    }
                } else if (canRetry) {
                    MotionButton(onClick = onRetry, shape = shape, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("重试本集")
                    }
                    if (canChooseSource) MotionTextButton(onClick = onSources, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("查看线路", color = Color.White)
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
                val stage = when {
                    playback.recoveryAttempt > 0 -> "正在重新连接视频"
                    playback.phase != AnimePlayerPhase.READY -> "正在打开视频"
                    !playback.playWhenReady -> "已暂停"
                    playback.isBuffering -> "正在缓冲首帧"
                    else -> "正在等待画面"
                }
                PlayerStageMessage(stage, sourceName)
                if (canChooseSource) PlayerSwitchLine(onSources)
            }
        playback.phase == AnimePlayerPhase.READY && !playback.isBuffering && playback.hasVideoTracks == false ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerStageMessage("这条线路只有音频", null)
                if (optionsAvailable > 1) MotionTextButton(onClick = onSources,
                    modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("换条线路", color = Color.White)
                }
            }
        showBuffering -> Text("正在缓冲", Modifier.clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = .72f)).padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, color = Color.White,
            style = MaterialTheme.typography.labelMedium)
        playback.ended -> Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerStageMessage("本集已播完", null)
            if (next != null) MotionButton(onClick = { onEpisode(next) }, shape = shape,
                modifier = Modifier.heightIn(min = 44.dp)) { Text(if (nextReady) "播放下一集" else "查看下一集来源") }
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
    incident: AnimePlaybackFailure? = null) {
    Column(Modifier.widthIn(max = 480.dp).padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
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
    onSettings: (AnimePlayerPanel) -> Unit, nextPreparation: AnimeNextEpisodePreparation?,
    centralTransport: Boolean,
    modifier: Modifier = Modifier) {
    var scrub by remember(state.sessionGeneration, state.timelineGeneration) { mutableStateOf<Long?>(null) }
    val duration = state.durationMs
    val canSeek = duration > 0L
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
                if (centralTransport) MotionTextButton(onClick = { onInteraction(); onSettings(AnimePlayerPanel.SPEED) },
                    modifier = Modifier.widthIn(min = 44.dp).heightIn(min = 44.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(animeSpeedLabel(state.speed), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (!centralTransport || !canSeek) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!centralTransport && previous != null) MotionIconButton(onClick = { onInteraction(); onEpisode(previous) },
                modifier = Modifier.size(44.dp), tooltip = "上一集") {
                Icon(Icons.Outlined.SkipPrevious, "上一集", Modifier.size(22.dp))
            }
            if (!centralTransport) MotionIconButton(onClick = onToggle, modifier = Modifier.size(48.dp),
                tooltip = if (state.playWhenReady && !state.ended) "暂停 · 空格" else "播放 · 空格") {
                Icon(if (state.ended) Icons.Outlined.Replay else if (state.playWhenReady) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (state.ended) "重播" else if (state.playWhenReady) "暂停" else "播放", Modifier.size(26.dp))
            }
            if (!centralTransport && next != null) MotionIconButton(onClick = { onInteraction(); onEpisode(next) },
                modifier = Modifier.size(44.dp),
                tooltip = nextPreparation?.takeIf { it.episodeId == next.id }
                    ?.let { "下一集 · ${animePreparationLabel(it)}" } ?: "下一集") {
                Icon(Icons.Outlined.SkipNext, "下一集", Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            MotionTextButton(onClick = { onInteraction(); onSettings(AnimePlayerPanel.SPEED) },
                modifier = Modifier.widthIn(min = 44.dp, max = 80.dp).heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(animeSpeedLabel(state.speed), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onEpisode: (AnimeEpisode) -> Unit, episodeAvailability: (String) -> AnimeEpisodeAvailability,
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
    LaunchedEffect(currentId, shown) {
        if (currentId == lastScrolledEpisodeId) return@LaunchedEffect
        val index = shown.indexOfFirst { it.id == currentId }
        if (index >= 0) {
            if (scroll.layoutInfo.visibleItemsInfo.none { it.key == currentId }) scroll.scrollToItem(index)
            lastScrolledEpisodeId = currentId
        }
    }
    val panelPadding = 16.dp
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val cellWidth = (if (fontScale > 1.32f) 90.dp else 72.dp) * fontScale
    val panel = if (desktopRail) Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow) else Modifier
    Column(modifier.then(panel).motionInputGate(active).padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = panelPadding, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("选集", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                if (selected.isNotEmpty()) AnimeQuietLabel(
                    if (term.isBlank()) "${selected.size} 集" else "${shown.size} / ${selected.size} 集",
                )
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(if (desktopRail) GridCells.Fixed(1) else GridCells.Adaptive(cellWidth),
                modifier = Modifier.fillMaxSize(), state = scroll, userScrollEnabled = active,
                contentPadding = PaddingValues(start = panelPadding, end = panelPadding, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(if (desktopRail) 3.dp else 8.dp)) {
                if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    AnimeMessage(if (term.isBlank()) "暂无集数" else "没有匹配的集数",
                        action = if (term.isNotBlank()) "清除查找" else null, onAction = { query = "" })
                }
                items(shown, key = { it.id }, contentType = { "episode" }) { episode ->
                    WatchEpisodeCell(episode, current = episode.id == currentId, watched = episode.id in completed,
                        availability = episodeAvailability(episode.id),
                        enabled = active, inRail = desktopRail, onClick = { onEpisode(episode) })
                }
            }
            if (desktopRail) DesktopVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun WatchEpisodeCell(
    episode: AnimeEpisode, current: Boolean, watched: Boolean, availability: AnimeEpisodeAvailability,
    enabled: Boolean, inRail: Boolean, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val color = when {
        inRail && current -> colors.onPrimaryContainer
        watched && !current -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    val stateLabel = if (current) "当前集" else if (watched) "已看完" else "未看完"
    val lineLabel = when (availability) {
        AnimeEpisodeAvailability.MATCHED -> null
        AnimeEpisodeAvailability.CHECKING -> "找源中"
        AnimeEpisodeAvailability.UNMATCHED -> "待匹配"
        AnimeEpisodeAvailability.RECENTLY_FAILED -> "线路暂不可用"
        AnimeEpisodeAvailability.UPCOMING -> "待播"
    }
    val surface = when {
        inRail && current -> colors.primaryContainer
        inRail -> Color.Transparent
        current -> colors.onSurface.copy(alpha = .11f)
        else -> colors.surfaceContainerLow
    }
    Box(Modifier.fillMaxWidth().heightIn(min = if (inRail) 44.dp else 48.dp)
        .clip(RoundedCornerShape(if (inRail) 8.dp else 11.dp)).background(surface)
        .motionPress(interaction, enabled = enabled)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = if (availability == AnimeEpisodeAvailability.RECENTLY_FAILED ||
                availability == AnimeEpisodeAvailability.UPCOMING) "查看本集来源" else "播放本集", onClick = onClick)
        .semantics { selected = current; stateDescription = listOfNotNull(stateLabel, lineLabel).joinToString("，") }
        .padding(horizontal = if (inRail) 10.dp else 6.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        if (inRail) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(animeEpisodeLabel(episode),
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = color)
                    animeEpisodeSubtitle(episode)?.let { subtitle ->
                        Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (current) color else colors.onSurfaceVariant)
                    }
                }
                when {
                    current -> Icon(Icons.Outlined.PlayArrow, null, Modifier.size(16.dp), tint = color)
                    availability == AnimeEpisodeAvailability.RECENTLY_FAILED ->
                        Icon(Icons.Outlined.CloudOff, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                    availability == AnimeEpisodeAvailability.UPCOMING ->
                        Text("待播", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    watched -> Icon(Icons.Outlined.Check, null, Modifier.size(15.dp), tint = color)
                    else -> Unit
                }
            }
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (current || watched) Icon(if (current) Icons.Outlined.PlayArrow else Icons.Outlined.Check,
                    null, Modifier.size(12.dp), tint = color)
                Text(animeEpisodeLabel(episode), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = color)
            }
            if (lineLabel != null && availability != AnimeEpisodeAvailability.CHECKING) Text(lineLabel, style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = color.copy(alpha = .8f))
        }
    }
}
