package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
    val activeEpisode = detail?.episodes?.firstOrNull { it.id == state.selectedEpisodeId }
    val target = state.playbackTarget
    val episode = target?.episode ?: activeEpisode
    val resolutionError = target?.error
    val hasAlternate = episode?.let { state.episodeOptions[it.id].orEmpty().count { option -> option.confirmed } > 1 } == true
    val ordered = detail?.episodes.orEmpty().filter { it.kind == episode?.kind }
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
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHeld by remember { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var holdingFast by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var settingsPresent by remember { mutableStateOf(false) }
    var settingsSection by remember { mutableStateOf(AnimePlayerPanel.GENERAL) }
    var interactionEpoch by remember { mutableIntStateOf(0) }
    val forceControls = state.resolving || failure != null || playback.isBuffering ||
        playback.phase != AnimePlayerPhase.READY || !playback.playWhenReady || obscured || settingsPresent || state.notice != null
    val showChrome = !playback.inPictureInPicture && !locked && (!fullscreen || controlsVisible || forceControls)
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
    var headerHeight by remember(density, topInset) { mutableStateOf(48.dp + topInset) }
    var controlsHeight by remember(density) { mutableStateOf(144.dp) }
    val canControl = !state.resolving && failure == null && playback.phase in
        listOf(AnimePlayerPhase.OPENING, AnimePlayerPhase.READY, AnimePlayerPhase.ENDED)
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
    val playerColors = if (!fillVideo) colors else colors.copy(
        background = Color.Black, surface = Color(0xFF171717),
        onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = .68f),
        primary = Color.White, onPrimary = Color.Black, outline = Color.White.copy(alpha = .3f),
    )
    MaterialTheme(colorScheme = playerColors) {
        BoxWithConstraints(modifier.fillMaxSize().focusRequester(focus)
            .onKeyEvent { event ->
                // Focused buttons and the native slider get first refusal.
                if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
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
            val sideBySide = maxWidth >= 980.dp && maxHeight >= 420.dp
            val shortWindow = maxHeight < 520.dp
            val videoWidth = if (fillVideo || !sideBySide) maxWidth else (maxWidth - 316.dp).coerceAtLeast(1.dp)
            val room = (maxHeight - headerHeight - controlsHeight - bottomInset).coerceAtLeast(1.dp)
            val videoHeight = when {
                fillVideo -> maxHeight
                sideBySide || shortWindow -> room
                else -> (videoWidth * .5625f).coerceAtMost(maxHeight * .48f).coerceAtMost(room)
            }
            val videoTop = if (fillVideo) 0.dp else headerHeight
            val controlsTop = videoTop + videoHeight
            val episodeTop = controlsTop + controlsHeight

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
                    AnimeVideoStatus(state.resolving, state.autoMatching, target?.sourceName, failure, playback,
                        next, onRetry, onSources, onEpisode, canChooseSource = episode != null,
                        onKeepPlayback = onKeepPlayback,
                        canKeepPlayback = playback.phase == AnimePlayerPhase.READY && (state.resolving || resolutionError != null),
                        onRestorePlayback = onRestorePlayback, canRestorePlayback = state.canRestorePlayback)
                }
                if (holdingFast && !playback.inPictureInPicture) Text(animeSpeedLabel(playback.speed),
                    Modifier.align(Alignment.TopCenter).padding(top = 20.dp), color = Color.White,
                    style = MaterialTheme.typography.titleSmall)
            }

            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { if (!fillVideo) headerHeight = with(density) { it.height.toDp() } }
                .graphicsLayer { alpha = chromeReveal; translationY = (1f - chromeReveal) * -8.dp.toPx() }
                .motionInputGate(active && showChrome && !obscured && !settingsPresent)) {
                PageTitleHeader("正在播放", backdrop,
                    navigation = {
                        MotionIconButton(onClick = { if (fullscreen) onFullscreenChange(false) else onBack() },
                            modifier = Modifier.size(44.dp), tooltip = if (fullscreen) "退出全屏" else "退出播放并保存位置") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (fullscreen) "退出全屏" else "退出播放", Modifier.size(20.dp))
                        }
                    },
                    summary = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(detail?.subject?.title ?: "番剧", style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (episode != null) AnimeQuietLabel(animeEpisodeLabel(episode))
                        }
                    },
                    actions = {
                        Row {
                            if (fullscreen) MotionIconButton(onClick = { locked = true }, modifier = Modifier.size(44.dp),
                                tooltip = "锁定触控") { Icon(Icons.Outlined.LockOpen, "锁定触控", Modifier.size(20.dp)) }
                            if (hasAlternate && failure == null && !state.resolving) MotionIconButton(
                                onClick = { interact(); onSources() }, modifier = Modifier.size(44.dp),
                                tooltip = "切换来源与线路") {
                                Icon(Icons.Outlined.SwapHoriz, "切换来源", Modifier.size(21.dp))
                            }
                            MotionIconButton(onClick = ::toggleFullscreen, modifier = Modifier.size(44.dp),
                                tooltip = if (fullscreen) "退出全屏 · F" else "全屏 · F") {
                                Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                                    if (fullscreen) "退出全屏" else "全屏", Modifier.size(21.dp))
                            }
                        }
                    },
                    modifier = Modifier.windowInsetsPadding(safeInsets.only(WindowInsetsSides.Top))
                        .padding(horizontal = 8.dp, vertical = 2.dp))
            }

            val transportPlacement = if (fillVideo) Modifier.align(Alignment.BottomCenter)
                .widthIn(max = 960.dp).fillMaxWidth()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 12.dp, vertical = 8.dp)
            else Modifier.offset(y = controlsTop).width(videoWidth).padding(horizontal = 12.dp)
            Box(transportPlacement
                .onSizeChanged { if (!fillVideo) controlsHeight = with(density) { it.height.toDp() } }
                .graphicsLayer { alpha = chromeReveal; translationY = (1f - chromeReveal) * 10.dp.toPx() }
                .motionInputGate(active && showChrome && !obscured && !settingsPresent)) {
                LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = if (fullscreen) 24.dp else 18.dp,
                    role = if (fullscreen) GlassSurfaceRole.READING_DOCK else GlassSurfaceRole.CONTROL,
                    surfaceColor = if (fullscreen) Color.Black.copy(alpha = .55f) else null) {
                    AnimePlayerControls(controller.player, playback, state.resolving || failure != null,
                        previous, next, onEpisode, onToggle = ::toggle,
                        onInteraction = ::interact, onHoldControls = { controlsHeld = it },
                        onSettings = ::showSettings, nextPrepared = state.nextEpisodeReady,
                        workspaceNotice = state.notice, onDismissNotice = controller::dismissNotice,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp))
                }
            }

            if (!fillVideo && sideBySide) AnimePlayerEpisodes(detail?.episodes.orEmpty(), episode?.id, state, onEpisode,
                Modifier.offset(x = videoWidth + 16.dp, y = headerHeight).width(300.dp)
                    .height((maxHeight - headerHeight - bottomInset).coerceAtLeast(1.dp)))
            else if (!fillVideo && !shortWindow) AnimePlayerEpisodes(detail?.episodes.orEmpty(), episode?.id, state, onEpisode,
                Modifier.offset(y = episodeTop).fillMaxWidth().height((maxHeight - episodeTop - bottomInset).coerceAtLeast(1.dp)))
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

@Composable
private fun AnimeVideoStatus(resolving: Boolean, autoMatching: Boolean, sourceName: String?, failure: String?, playback: AnimePlayerState,
    next: AnimeEpisode?, onRetry: () -> Unit, onSources: () -> Unit, onEpisode: (AnimeEpisode) -> Unit,
    canChooseSource: Boolean, onKeepPlayback: () -> Unit, canKeepPlayback: Boolean,
    onRestorePlayback: () -> Unit, canRestorePlayback: Boolean) {
    when {
        resolving -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerStageMessage(if (autoMatching) "正在匹配本集片源" else "正在准备本集",
                sourceName.takeUnless { autoMatching })
            if (canKeepPlayback) MotionTextButton(onClick = onKeepPlayback, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("取消换源，保留当前播放", color = Color.White)
            }
        }
        failure != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerStageMessage("播放暂未成功", failure)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MotionButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) { Text("重试") }
                if (canChooseSource) MotionOutlinedButton(onClick = onSources, modifier = Modifier.heightIn(min = 44.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("换个来源") }
            }
            if (canKeepPlayback) MotionTextButton(onClick = onKeepPlayback, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("继续原来源", color = Color.White)
            }
            else if (canRestorePlayback) MotionTextButton(onClick = onRestorePlayback, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("恢复上次播放", color = Color.White)
            }
        }
        playback.phase == AnimePlayerPhase.IDLE -> PlayerStageMessage("正在准备播放", null)
        playback.phase == AnimePlayerPhase.OPENING -> PlayerStageMessage("正在打开视频", "等待播放器准备")
        playback.phase == AnimePlayerPhase.READY && !playback.isBuffering && playback.hasVideoTracks == false ->
            PlayerStageMessage("当前仅识别到音轨", "未发现视频轨道")
        playback.phase == AnimePlayerPhase.READY && !playback.isBuffering && playback.firstFrameRendered == false ->
            PlayerStageMessage("等待视频画面", if (playback.playWhenReady) "尚未收到首帧" else "播放已暂停")
        playback.isBuffering -> PlayerStageMessage("正在缓冲", if (playback.bufferedAheadMs >= 0L)
            "前方缓冲 " + animeTime(playback.bufferedAheadMs) else "等待视频数据")
        playback.ended -> Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerStageMessage("本集播放结束", "返回详情，或继续下一集。")
            if (next != null) MotionButton(onClick = { onEpisode(next) },
                modifier = Modifier.heightIn(min = 44.dp)) { Text("播放 " + animeEpisodeLabel(next)) }
        }
    }
}

@Composable
private fun PlayerStageMessage(title: String, detail: String?) {
    Column(Modifier.padding(16.dp).widthIn(max = 480.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
        if (!detail.isNullOrBlank()) Text(detail, color = Color.White.copy(alpha = .65f),
            style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun AnimePlayerControls(player: AnimePlayer, state: AnimePlayerState, blocked: Boolean,
    previous: AnimeEpisode?, next: AnimeEpisode?, onEpisode: (AnimeEpisode) -> Unit,
    onToggle: () -> Unit, onInteraction: () -> Unit,
    onHoldControls: (Boolean) -> Unit, workspaceNotice: String?, onDismissNotice: () -> Unit,
    onSettings: (AnimePlayerPanel) -> Unit, nextPrepared: Boolean,
    modifier: Modifier = Modifier) {
    var scrub by remember(state.sessionGeneration) { mutableStateOf<Long?>(null) }
    val duration = state.durationMs
    val ready = !blocked && state.phase in listOf(AnimePlayerPhase.OPENING, AnimePlayerPhase.READY, AnimePlayerPhase.ENDED)
    val canSeek = ready && duration > 0L
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
        if (workspaceNotice != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(workspaceNotice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            MotionIconButton(onClick = { onInteraction(); onDismissNotice() }, modifier = Modifier.size(44.dp),
                tooltip = "关闭提示") { Icon(Icons.Outlined.Close, "关闭提示", Modifier.size(18.dp)) }
        }
        if (state.notice != null) Text(state.notice, Modifier.fillMaxWidth().padding(top = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (canSeek) {
        MotionSlider(value = (scrub ?: state.positionMs).toFloat(), onValueChange = { onHoldControls(true); scrub = it.toLong() },
            enabled = ready && duration > 0L, valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
            onValueChangeFinished = { scrub?.let(player::seekTo); scrub = null; onHoldControls(false); onInteraction() },
            formatValue = { animeTime(it.toLong()) }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(animeTime(scrub ?: state.positionMs) + " / " + animeTime(duration),
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (state.bufferedAheadMs >= 0L && ready) AnimeQuietLabel("前方缓冲 " + animeTime(state.bufferedAheadMs))
        }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            MotionIconButton(onClick = { onInteraction(); previous?.let(onEpisode) }, enabled = previous != null,
                modifier = Modifier.size(44.dp), tooltip = "上一集") {
                Icon(Icons.Outlined.SkipPrevious, "上一集", Modifier.size(22.dp))
            }
            MotionIconButton(onClick = onToggle, enabled = ready, modifier = Modifier.size(48.dp),
                tooltip = if (state.playWhenReady && !state.ended) "暂停 · 空格" else "播放 · 空格") {
                Icon(if (state.ended) Icons.Outlined.Replay else if (state.playWhenReady) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (state.ended) "重播" else if (state.playWhenReady) "暂停" else "播放", Modifier.size(26.dp))
            }
            MotionIconButton(onClick = { onInteraction(); next?.let(onEpisode) }, enabled = next != null,
                modifier = Modifier.size(44.dp), tooltip = if (nextPrepared) "下一集 · 播放地址已准备" else "下一集") {
                Icon(Icons.Outlined.SkipNext, "下一集", Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            MotionTextButton(onClick = { onInteraction(); onSettings(AnimePlayerPanel.SPEED) }, enabled = ready,
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
private fun AnimePlayerEpisodes(episodes: List<AnimeEpisode>, currentId: String?, state: AnimeScreenState,
    onEpisode: (AnimeEpisode) -> Unit, modifier: Modifier = Modifier) {
    var group by rememberSaveable { mutableIntStateOf(0) }
    var reversed by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by remember { mutableStateOf(false) }
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val native = episodes.firstOrNull()?.subjectId?.startsWith("native:") == true
    val main = if (native) episodes else episodes.filter { it.kind == AnimeEpisodeKind.MAIN }
    val extra = if (native) emptyList() else episodes.filter { it.kind != AnimeEpisodeKind.MAIN }
    LaunchedEffect(currentId) {
        episodes.firstOrNull { it.id == currentId }?.let { group = if (native || it.kind == AnimeEpisodeKind.MAIN) 0 else 1 }
        query = ""
    }
    val selected = if (group == 1 && extra.isNotEmpty()) extra else main
    val shown = (if (reversed) selected.asReversed() else selected).filter {
        query.isBlank() || (!it.subjectId.startsWith("native:") && animeEpisodeNumber(it.number).contains(query.trim())) ||
            it.title.contains(query.trim(), ignoreCase = true)
    }
    LaunchedEffect(currentId, group, reversed) {
        val index = shown.indexOfFirst { it.id == currentId }
        if (index >= 0) scroll.scrollToItem(index)
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (extra.isNotEmpty()) MotionTabs(group, listOf("正片", "特别篇等"), onSelect = { group = it },
                modifier = Modifier.weight(1f), controlHeight = 44.dp, compact = true, treatment = MotionTabTreatment.Underline)
            else AnimeSectionTitle("选集", modifier = Modifier.weight(1f))
            MotionIconButton(onClick = { searchVisible = !searchVisible; if (!searchVisible) query = "" },
                modifier = Modifier.size(44.dp), tooltip = "查找集数") { Icon(Icons.Outlined.Search, "查找集数", Modifier.size(19.dp)) }
            MotionIconButton(onClick = { reversed = !reversed }, modifier = Modifier.size(44.dp),
                tooltip = if (reversed) "改为正序" else "改为倒序") {
                Icon(Icons.Outlined.SwapVert, if (reversed) "倒序，点按切换" else "正序，点按切换", Modifier.size(19.dp))
            }
            MotionIconButton(onClick = {
                query = ""
                episodes.firstOrNull { it.id == currentId }?.let { current ->
                    group = if (native || current.kind == AnimeEpisodeKind.MAIN) 0 else 1
                    val ordered = (if (group == 0) main else extra).let { if (reversed) it.asReversed() else it }
                    val index = ordered.indexOfFirst { it.id == currentId }
                    if (index >= 0) scope.launch { scroll.scrollToItem(index) }
                }
            }, enabled = currentId != null, modifier = Modifier.size(44.dp), tooltip = "定位当前集") {
                Icon(Icons.Outlined.MyLocation, "定位当前集", Modifier.size(19.dp))
            }
        }
        if (searchVisible) AnimeTextField(query, { query = it }, "集号或标题",
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            if (shown.isEmpty()) item { AnimeMessage("没有匹配的集数",
                action = if (query.isNotBlank()) "清除查找" else null, onAction = { query = "" }) }
            items(shown, key = { it.id }) { episode ->
                AnimeSelection(animeEpisodeLabel(episode), animeEpisodeSubtitle(episode),
                    titleMaxLines = 1, detailMaxLines = 1,
                    selected = episode.id == currentId, onClick = { onEpisode(episode) })
            }
        }
    }
}
