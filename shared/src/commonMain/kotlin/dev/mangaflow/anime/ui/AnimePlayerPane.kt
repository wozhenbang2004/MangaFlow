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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.mangaflow.reader.ReaderWindow
import dev.mangaflow.ui.*
import dev.mangaflow.ui.glass.mangaHazeSource
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
    onPlayDownload: (String) -> Unit,
    onSources: () -> Unit,
    onEpisode: (AnimeEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fillVideo = fullscreen || playback.inPictureInPicture
    val detail = state.detail
    val activeEpisode = detail?.episodes?.firstOrNull { it.id == (state.selectedEpisodeId ?: playback.episodeId) }
    val target = state.playbackTarget
    val episode = target?.episode ?: activeEpisode
    val resolutionError = target?.error
    val optionCount = remember(episode?.id, state.detail, state.episodeOptions, state.sourceCatalogs) {
        episode?.let { controller.confirmedPlaybackLines(it.id, state).size } ?: 0
    }
    val ordered = remember(detail?.episodes, episode?.kind) { detail?.episodes.orEmpty().filter { it.kind == episode?.kind } }
    val index = ordered.indexOfFirst { it.id == episode?.id }
    val previous = ordered.getOrNull(index - 1).takeIf { index > 0 }
    val next = ordered.getOrNull(index + 1).takeIf { index >= 0 }
    val playerFailure = playback.error ?: if (playback.phase == AnimePlayerPhase.ERROR)
        "播放器未能准备视频，请重试或选择其他来源。" else null
    val failure = resolutionError ?: playerFailure?.let { error ->
        if (playback.phase == AnimePlayerPhase.ERROR) listOfNotNull(error, state.playbackRecoveryStopReason).joinToString("\n")
        else error
    }
    val focus = remember { FocusRequester() }
    val chromeFocus = remember { FocusRequester() }
    val touchExploration = LocalTouchExplorationEnabled.current
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHeld by remember { mutableStateOf(false) }
    var headerFocused by remember { mutableStateOf(false) }
    var transportFocused by remember { mutableStateOf(false) }
    var focusChromeOnReveal by remember { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var holdingFast by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var settingsPresent by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(AnimePlayerPanel.GENERAL) }
    var interactionEpoch by remember { mutableIntStateOf(0) }
    val awaitingVideo = playback.firstFrameRendered == false && playback.hasVideoTracks != false
    val forceControls = state.resolving || failure != null || awaitingVideo ||
        playback.phase != AnimePlayerPhase.READY || !playback.playWhenReady || obscured || settingsPresent ||
        state.notice != null || touchExploration || headerFocused || transportFocused
    val showChrome = !playback.inPictureInPicture && (!locked || touchExploration) &&
        (!fullscreen || controlsVisible || forceControls)
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
    var controlsHeight by remember(density) { mutableStateOf(52.dp) }
    val canControl = !state.resolving && failure == null &&
        (playback.phase == AnimePlayerPhase.READY || playback.ended)
    fun interact() { controlsVisible = true; interactionEpoch++ }
    fun showSettings(section: AnimePlayerPanel) {
        interact(); settingsSection = section; settingsPresent = true; settingsVisible = true
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
    LaunchedEffect(showChrome, focusChromeOnReveal) {
        if (showChrome && focusChromeOnReveal) {
            chromeFocus.requestFocus()
            focusChromeOnReveal = false
        }
    }
    LaunchedEffect(canControl) { if (!canControl) transportFocused = false }
    LaunchedEffect(playback.inPictureInPicture) {
        if (playback.inPictureInPicture) { settingsVisible = false; settingsPresent = false; locked = false }
    }
    MotionBackHandler(active && locked && !obscured, state = null, onBack = { locked = false; interact() })
    LaunchedEffect(fullscreen, controlsVisible, forceControls, controlsHeld, interactionEpoch, active) {
        if (active && fullscreen && controlsVisible && !forceControls && !controlsHeld) {
            delay(3_500L)
            controlsVisible = false
        }
    }
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
        BoxWithConstraints(modifier.fillMaxSize().focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (active && fullscreen && !showChrome && !locked && !obscured && !settingsPresent &&
                    !playback.inPictureInPicture && event.key == Key.Tab && event.type == KeyEventType.KeyDown) {
                    focusChromeOnReveal = true
                    interact()
                    true
                } else false
            }
            .onKeyEvent { event ->
                // Focused buttons and the native slider get first refusal.
                    if (!active || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
                    event.type != KeyEventType.KeyUp || obscured || settingsPresent || playback.inPictureInPicture) false
                else if (locked) { if (event.key == Key.Escape) { locked = false; true } else false }
                else when (event.key) {
                    Key.Spacebar -> { toggle(); true }
                    Key.DirectionLeft -> { seek(-10_000L); true }
                    Key.DirectionRight -> { seek(10_000L); true }
                    Key.F -> { toggleFullscreen(); true }
                    Key.Escape -> if (fullscreen) { onFullscreenChange(false); true } else false
                    else -> false
                }
            }.pointerInput(fullscreen) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (fullscreen && event.type == PointerEventType.Move) interact()
                    }
                }
            }.focusable()) {
            val sideBySide = maxWidth >= 650.dp && (maxWidth >= 920.dp || maxHeight < 520.dp)
            val railWidth = (maxWidth * .32f).coerceIn(214.dp, 330.dp)
            val videoWidth = if (fillVideo || !sideBySide) maxWidth else maxWidth - railWidth
            val room = (maxHeight - headerHeight - bottomInset).coerceAtLeast(1.dp)
            val reserveEpisodes = if (room >= 368.dp) 164.dp else if (room >= 260.dp) 108.dp else 0.dp
            val videoHeight = when {
                fillVideo -> maxHeight
                sideBySide -> room
                else -> (videoWidth * .5625f).coerceAtLeast(minOf(184.dp, room))
                    .coerceAtMost((room - reserveEpisodes).coerceAtLeast(1.dp))
            }
            val overlayTransport = fillVideo || sideBySide || room - videoHeight < 244.dp
            val transportHeight = if (canControl && !overlayTransport) controlsHeight else 0.dp
            val videoTop = if (fillVideo) 0.dp else headerHeight
            val controlsTop = videoTop + videoHeight
            val episodeTop = controlsTop + transportHeight

            // One native surface keeps its composition identity through fullscreen and size changes.
            Box(Modifier.offset(y = videoTop).width(videoWidth).height(videoHeight)
                .background(Color.Black).mangaHazeSource(backdrop), contentAlignment = Alignment.Center) {
                AnimePlayerSurface(controller.player, Modifier.fillMaxSize())
                AnimeDanmakuOverlay(controller, playback,
                    visible = active && !obscured && !settingsPresent && !state.resolving && failure == null,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp))
                if (!playback.inPictureInPicture && (state.resolving || failure != null)) Box(Modifier.fillMaxSize().background(Color.Black.copy(
                    alpha = if (playback.firstFrameRendered == true || playback.phase == AnimePlayerPhase.READY) .45f else 1f)))
                val latestPlayback by rememberUpdatedState(playback)
                val latestForceControls by rememberUpdatedState(forceControls)
                Box(Modifier.fillMaxSize().pointerInput(playback.sessionGeneration, locked, fullscreen, canControl,
                    state.settings.longPressSpeed, obscured, settingsPresent, playback.inPictureInPicture) {
                    if (locked || obscured || settingsPresent || playback.inPictureInPicture) return@pointerInput
                    var accelerated = false
                    detectTapGestures(
                        onTap = {
                            if (!accelerated) {
                                controlsVisible = if (!fullscreen || latestForceControls) true else !controlsVisible
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
                })
                if (!playback.inPictureInPicture) Box(Modifier.motionInputGate(active && !locked && !obscured && !settingsPresent)) {
                    MaterialTheme(colorScheme = videoColors) {
                        AnimeVideoStatus(
                            resolving = state.resolving, autoMatching = state.autoMatching,
                            sourceName = target?.sourceName, failure = failure, playback = playback,
                            optionsAvailable = optionCount, canChooseSource = episode != null,
                            compact = videoHeight < 196.dp,
                            next = next, onRetry = onRetry, onSources = onSources, onEpisode = onEpisode,
                            onKeepPlayback = onKeepPlayback,
                            canKeepPlayback = playback.phase == AnimePlayerPhase.READY && (state.resolving || resolutionError != null),
                            onRestorePlayback = onRestorePlayback, canRestorePlayback = state.canRestorePlayback,
                        )
                    }
                }
                if (holdingFast && !playback.inPictureInPicture) Text(animeSpeedLabel(playback.speed),
                    Modifier.align(Alignment.TopCenter).padding(top = 20.dp), color = Color.White,
                    style = MaterialTheme.typography.titleSmall)
            }

            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { if (!fillVideo) headerHeight = with(density) { it.height.toDp() } }
                .graphicsLayer { alpha = chromeReveal; translationY = (1f - chromeReveal) * -8.dp.toPx() }
                .motionInputGate(active && showChrome && !obscured && !settingsPresent)
                .onFocusChanged { headerFocused = it.hasFocus }.focusGroup()) {
                Row(Modifier.fillMaxWidth().then(if (fullscreen) Modifier.background(Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = .86f), Color.Transparent))) else Modifier)
                    .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top))
                    .heightIn(min = 56.dp).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    MotionIconButton(onClick = { if (fullscreen) onFullscreenChange(false) else onBack() },
                        modifier = Modifier.size(44.dp).focusRequester(chromeFocus),
                        tooltip = if (fullscreen) "退出全屏" else "退出播放并保存位置") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (fullscreen) "退出全屏" else "退出播放", Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(detail?.subject?.title ?: "播放", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (episode != null) Text(animeEpisodeLabel(episode), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (fullscreen && canControl && !touchExploration) MotionIconButton(onClick = { locked = true }, modifier = Modifier.size(44.dp),
                        tooltip = "锁定触控") { Icon(Icons.Outlined.LockOpen, "锁定触控", Modifier.size(20.dp)) }
                    if (optionCount > 1 && failure == null && !state.resolving) MotionIconButton(
                        onClick = { interact(); onSources() }, modifier = Modifier.size(44.dp),
                        tooltip = "切换来源与线路") {
                        Icon(Icons.Outlined.SwapHoriz, "切换线路", Modifier.size(21.dp))
                    }
                    if (canControl || fullscreen) MotionIconButton(onClick = ::toggleFullscreen, modifier = Modifier.size(44.dp),
                        tooltip = if (fullscreen) "退出全屏 · F" else "全屏 · F") {
                        Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                            if (fullscreen) "退出全屏" else "全屏", Modifier.size(21.dp))
                    }
                }
            }

            if (!playback.inPictureInPicture && canControl) {
                val transportPlacement = when {
                    fillVideo -> Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    overlayTransport -> Modifier.align(Alignment.BottomStart)
                        .offset(y = controlsTop - maxHeight).width(videoWidth)
                    else -> Modifier.offset(y = controlsTop).width(videoWidth)
                }
                val panel = if (overlayTransport) Modifier.background(Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = .9f)))) else Modifier.background(Color.Black)
                Box(transportPlacement
                    .onSizeChanged { if (!overlayTransport) controlsHeight = with(density) { it.height.toDp() } }
                    .graphicsLayer { alpha = chromeReveal; translationY = (1f - chromeReveal) * 10.dp.toPx() }
                    .motionInputGate(active && showChrome && !obscured && !settingsPresent)
                    .onFocusChanged { transportFocused = it.hasFocus }.focusGroup()
                    .then(panel), contentAlignment = Alignment.BottomCenter) {
                    MaterialTheme(colorScheme = videoColors) {
                        AnimePlayerControls(controller.player, playback,
                            previous, next, onEpisode, onToggle = ::toggle,
                            onInteraction = ::interact, onHoldControls = { controlsHeld = it },
                            onSettings = ::showSettings, nextPrepared = state.nextEpisodeReady,
                            workspaceNotice = state.notice, onDismissNotice = controller::dismissNotice,
                            modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth()
                                .then(if (fillVideo) Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom)) else Modifier)
                                .padding(horizontal = 12.dp))
                    }
                }
            }
            if (!fillVideo) {
                val episodePanel = if (sideBySide) Modifier.align(Alignment.TopEnd).offset(y = headerHeight)
                    .width(railWidth).height(room)
                else Modifier.offset(y = episodeTop).fillMaxWidth()
                    .height((maxHeight - episodeTop - bottomInset).coerceAtLeast(0.dp))
                val entry = remember(detail?.subject?.id, state.library) {
                    state.library.firstOrNull { it.subject.id == detail?.subject?.id }
                }
                AnimePlayerEpisodes(detail?.episodes.orEmpty(), episode?.id, entry?.completedEpisodeIds.orEmpty(),
                    onEpisode, active = active && !obscured && !settingsPresent, modifier = episodePanel)
            }
            if (locked && !playback.inPictureInPicture) MotionIconButton(onClick = { locked = false; interact() },
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(48.dp), tooltip = "解除触控锁定") {
                Icon(Icons.Outlined.Lock, "解除触控锁定", Modifier.size(24.dp), tint = Color.White)
            }
        }
        if (!playback.inPictureInPicture) AnimePlayerSettings(settingsVisible, settingsSection, controller, state, playback, backdrop,
            onDismiss = { settingsVisible = false; interact() }, onClosed = { settingsPresent = false },
            onPlayDownload = onPlayDownload)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeVideoStatus(
    resolving: Boolean, autoMatching: Boolean, sourceName: String?, failure: String?, playback: AnimePlayerState,
    optionsAvailable: Int, canChooseSource: Boolean, compact: Boolean, next: AnimeEpisode?,
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
            if (canKeepPlayback) MotionTextButton(onClick = onKeepPlayback, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("继续原线路", color = Color.White)
            }
        }
        failure != null -> Column(Modifier.widthIn(max = 500.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlayerStageMessage("本集暂时无法播放", failure, maxLines = if (compact) 2 else 4)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (optionsAvailable > 1) {
                    MotionButton(onClick = onSources, shape = shape, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("换线路")
                    }
                    MotionTextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("重试本集", color = Color.White)
                    }
                } else {
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
        playback.phase == AnimePlayerPhase.IDLE || playback.phase == AnimePlayerPhase.OPENING ->
            PlayerStageMessage(if (playback.recoveryAttempt > 0) "正在重新连接视频" else "正在打开视频", sourceName)
        playback.phase == AnimePlayerPhase.READY && !playback.isBuffering && playback.hasVideoTracks == false ->
            PlayerStageMessage("这条线路只有音频", null)
        playback.phase == AnimePlayerPhase.READY && !playback.isBuffering && awaitingVideo ->
            PlayerStageMessage(if (playback.playWhenReady) "正在等待画面" else "已暂停", null)
        playback.isBuffering && awaitingVideo -> PlayerStageMessage("正在缓冲首帧", sourceName)
        showBuffering -> Text("正在缓冲", Modifier.clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = .72f)).padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, color = Color.White,
            style = MaterialTheme.typography.labelMedium)
        playback.ended -> Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlayerStageMessage("本集已播完", null)
            if (next != null) MotionButton(onClick = { onEpisode(next) }, shape = shape,
                modifier = Modifier.heightIn(min = 44.dp)) { Text("播放下一集") }
        }
    }
}

@Composable
private fun PlayerStageMessage(title: String, detail: String?, maxLines: Int = 2) {
    Column(Modifier.widthIn(max = 480.dp).padding(vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
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
    onSettings: (AnimePlayerPanel) -> Unit, nextPrepared: Boolean,
    modifier: Modifier = Modifier) {
    var scrub by remember(state.sessionGeneration) { mutableStateOf<Long?>(null) }
    val duration = state.durationMs
    val canSeek = duration > 0L
    val releaseControls by rememberUpdatedState(onHoldControls)
    DisposableEffect(canSeek, state.sessionGeneration) {
        // Slider removal/session replacement may skip onValueChangeFinished mid-drag.
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
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = Color.White)) {
                    MotionSlider(value = (scrub ?: state.positionMs).toFloat(),
                        onValueChange = { onHoldControls(true); scrub = it.toLong() },
                        valueRange = 0f..duration.toFloat(),
                        onValueChangeFinished = { scrub?.let(player::seekTo); scrub = null; onHoldControls(false); onInteraction() },
                        formatValue = { animeTime(it.toLong()) }, modifier = Modifier.weight(1f))
                }
                Text(animeTime(duration), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (previous != null) MotionIconButton(onClick = { onInteraction(); onEpisode(previous) },
                modifier = Modifier.size(44.dp), tooltip = "上一集") {
                Icon(Icons.Outlined.SkipPrevious, "上一集", Modifier.size(22.dp))
            }
            MotionIconButton(onClick = onToggle, modifier = Modifier.size(48.dp),
                tooltip = if (state.playWhenReady && !state.ended) "暂停 · 空格" else "播放 · 空格") {
                Icon(if (state.ended) Icons.Outlined.Replay else if (state.playWhenReady) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (state.ended) "重播" else if (state.playWhenReady) "暂停" else "播放", Modifier.size(26.dp))
            }
            if (next != null) MotionIconButton(onClick = { onInteraction(); onEpisode(next) },
                modifier = Modifier.size(44.dp), tooltip = if (nextPrepared) "下一集 · 播放地址已准备" else "下一集") {
                Icon(Icons.Outlined.SkipNext, "下一集", Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            MotionTextButton(onClick = { onInteraction(); onSettings(AnimePlayerPanel.SPEED) },
                modifier = Modifier.widthIn(min = 44.dp, max = 80.dp).heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(animeSpeedLabel(state.speed), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            MotionIconButton(onClick = { onSettings(AnimePlayerPanel.GENERAL) }, modifier = Modifier.size(44.dp), tooltip = "播放设置") {
                Icon(Icons.Outlined.Settings, "播放设置", Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun AnimePlayerEpisodes(
    episodes: List<AnimeEpisode>, currentId: String?, completed: Set<String>,
    onEpisode: (AnimeEpisode) -> Unit, active: Boolean, modifier: Modifier = Modifier,
) {
    val subjectId = episodes.firstOrNull()?.subjectId
    var group by rememberSaveable(subjectId) { mutableIntStateOf(0) }
    var reversed by rememberSaveable(subjectId) { mutableStateOf(false) }
    var query by rememberSaveable(subjectId) { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    val scroll = rememberLazyGridState()
    val native = subjectId?.startsWith("native:") == true
    val (main, extra) = remember(episodes, native) {
        if (native) episodes to emptyList() else episodes.partition { it.kind == AnimeEpisodeKind.MAIN }
    }
    val selectedGroup = if (main.isEmpty() && extra.isNotEmpty()) 1 else if (extra.isEmpty()) 0 else group
    LaunchedEffect(currentId, episodes) {
        episodes.firstOrNull { it.id == currentId }?.let {
            group = if (native || it.kind == AnimeEpisodeKind.MAIN) 0 else 1
        }
    }
    val selected = if (selectedGroup == 1) extra else main
    val term = query.trim()
    val shown = remember(selected, reversed, term) {
        val ordered = if (reversed) selected.asReversed() else selected
        if (term.isBlank()) ordered else ordered.filter {
            animeEpisodeLabel(it).contains(term, ignoreCase = true) || it.title.contains(term, ignoreCase = true)
        }
    }
    LaunchedEffect(currentId, selectedGroup, reversed, shown) {
        val index = shown.indexOfFirst { it.id == currentId }
        if (index >= 0 && scroll.layoutInfo.visibleItemsInfo.none { it.key == currentId }) scroll.scrollToItem(index)
    }
    val panelPadding = 16.dp
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val cellWidth = (if (fontScale > 1.32f) 90.dp else 72.dp) * fontScale
    Column(modifier.motionInputGate(active).padding(top = 8.dp)) {
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
            controlHeight = 44.dp, compact = true, treatment = MotionTabTreatment.Underline)
        if (searchVisible) AnimeTextField(query, { query = it }, "集号或标题",
            Modifier.padding(horizontal = panelPadding, vertical = 4.dp))
        LazyVerticalGrid(GridCells.Adaptive(cellWidth), modifier = Modifier.weight(1f).fillMaxWidth(),
            state = scroll, userScrollEnabled = active,
            contentPadding = PaddingValues(start = panelPadding, end = panelPadding, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                AnimeMessage(if (term.isBlank()) "暂无集数" else "没有匹配的集数",
                    action = if (term.isNotBlank()) "清除查找" else null, onAction = { query = "" })
            }
            items(shown, key = { it.id }, contentType = { "episode" }) { episode ->
                WatchEpisodeCell(episode, current = episode.id == currentId, watched = episode.id in completed,
                    enabled = active, onClick = { onEpisode(episode) })
            }
        }
    }
}

@Composable
private fun WatchEpisodeCell(
    episode: AnimeEpisode, current: Boolean, watched: Boolean, enabled: Boolean, onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val color = if (current) colors.onPrimary else if (watched) colors.onSurfaceVariant else colors.onSurface
    val stateLabel = if (current) "当前集" else if (watched) "已看完" else "未看完"
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(11.dp))
        .background(if (current) colors.primary else colors.surfaceContainerHigh)
        .motionPress(interaction, enabled = enabled)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .semantics { selected = current; stateDescription = stateLabel }
        .padding(horizontal = 6.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(animeEpisodeLabel(episode), style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = color)
    }
}
