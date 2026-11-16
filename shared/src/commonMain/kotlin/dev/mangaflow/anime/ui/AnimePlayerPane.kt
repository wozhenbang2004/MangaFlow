package dev.mangaflow.anime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

@Composable
internal fun AnimePlayerPane(
    controller: AnimeController,
    state: AnimeScreenState,
    playback: AnimePlayerState,
    backdrop: HazeState,
    requestedEpisode: AnimeEpisode?,
    resolutionError: String?,
    active: Boolean,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    obscured: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSources: () -> Unit,
    onEpisode: (AnimeEpisode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.detail
    val episode = requestedEpisode ?: detail?.episodes?.firstOrNull { it.id == state.selectedEpisodeId }
    val ordered = detail?.episodes.orEmpty().filter { it.kind == episode?.kind }
    val index = ordered.indexOfFirst { it.id == episode?.id }
    val previous = ordered.getOrNull(index - 1).takeIf { index > 0 }
    val next = ordered.getOrNull(index + 1).takeIf { index >= 0 }
    val failure = resolutionError ?: playback.error ?: if (playback.phase == AnimePlayerPhase.ERROR)
        "播放器未能准备视频，请重试或选择其他来源。" else null
    val focus = remember { FocusRequester() }
    val stageInteraction = remember { MutableInteractionSource() }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHeld by remember { mutableStateOf(false) }
    var interactionEpoch by remember { mutableIntStateOf(0) }
    val forceControls = state.resolving || failure != null || playback.isBuffering ||
        playback.phase != AnimePlayerPhase.READY || !playback.playWhenReady || obscured || state.notice != null
    val showChrome = !fullscreen || controlsVisible || forceControls
    val motion = LocalMotionEnabled.current
    val reveal by animateFloatAsState(if (showChrome) 1f else 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
        label = "anime-player-controls")
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
    LaunchedEffect(fullscreen) { controlsVisible = true }
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
        controlsVisible = !fullscreen || showChrome,
        lightChrome = !fullscreen && colors.background.luminance() > .5f,
    )
    val playerColors = if (!fullscreen) colors else colors.copy(
        background = Color.Black, surface = Color(0xFF171717),
        onSurface = Color.White, onSurfaceVariant = Color.White.copy(alpha = .68f),
        primary = Color.White, onPrimary = Color.Black, outline = Color.White.copy(alpha = .3f),
    )
    MaterialTheme(colorScheme = playerColors) {
        BoxWithConstraints(modifier.fillMaxSize().focusRequester(focus)
            .onKeyEvent { event ->
                // Focused buttons and the native slider get first refusal.
                if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed ||
                    event.type != KeyEventType.KeyUp || obscured) false
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
            val videoWidth = if (fullscreen || !sideBySide) maxWidth else (maxWidth - 316.dp).coerceAtLeast(1.dp)
            val room = (maxHeight - headerHeight - controlsHeight - bottomInset).coerceAtLeast(1.dp)
            val videoHeight = when {
                fullscreen -> maxHeight
                sideBySide || shortWindow -> room
                else -> (videoWidth * .5625f).coerceAtMost(maxHeight * .48f).coerceAtMost(room)
            }
            val videoTop = if (fullscreen) 0.dp else headerHeight
            val controlsTop = videoTop + videoHeight
            val episodeTop = controlsTop + controlsHeight

            // One native surface keeps its composition identity through fullscreen and size changes.
            Box(Modifier.offset(y = videoTop).width(videoWidth).height(videoHeight)
                .background(Color.Black).mangaHazeSource(backdrop), contentAlignment = Alignment.Center) {
                AnimePlayerSurface(controller.player, Modifier.fillMaxSize())
                if (state.resolving || failure != null) Box(Modifier.fillMaxSize().background(Color.Black))
                Box(Modifier.fillMaxSize().clickable(enabled = fullscreen,
                    interactionSource = stageInteraction, indication = null,
                    onClickLabel = if (showChrome) "隐藏播放控制" else "显示播放控制",
                    onClick = { controlsVisible = if (forceControls) true else !controlsVisible; interactionEpoch++ }))
                AnimeVideoStatus(state.resolving, failure, playback, next, onRetry, onSources, onEpisode)
            }

            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .onSizeChanged { if (!fullscreen) headerHeight = with(density) { it.height.toDp() } }
                .graphicsLayer { alpha = reveal; translationY = (1f - reveal) * -8.dp.toPx() }
                .motionInputGate(active && showChrome && !obscured)) {
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
                            if (episode != null) AnimeQuietLabel(animeEpisodeLabel(episode) + " · " + episode.title)
                        }
                    },
                    actions = {
                        Row {
                            MotionIconButton(onClick = { interact(); onSources() }, modifier = Modifier.size(44.dp),
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

            val transportPlacement = if (fullscreen) Modifier.align(Alignment.BottomCenter)
                .widthIn(max = 960.dp).fillMaxWidth()
                .windowInsetsPadding(safeInsets.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 12.dp, vertical = 8.dp)
            else Modifier.offset(y = controlsTop).width(videoWidth).padding(horizontal = 12.dp)
            Box(transportPlacement
                .onSizeChanged { if (!fullscreen) controlsHeight = with(density) { it.height.toDp() } }
                .graphicsLayer { alpha = reveal; translationY = (1f - reveal) * 10.dp.toPx() }
                .motionInputGate(active && showChrome && !obscured)) {
                LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = if (fullscreen) 24.dp else 18.dp,
                    role = if (fullscreen) GlassSurfaceRole.READING_DOCK else GlassSurfaceRole.CONTROL,
                    surfaceColor = if (fullscreen) Color.Black.copy(alpha = .55f) else null) {
                    AnimePlayerControls(controller.player, playback, state.resolving || failure != null,
                        backdrop, previous, next, onEpisode, onSources, onToggle = ::toggle,
                        onInteraction = ::interact, onHoldControls = { controlsHeld = it },
                        workspaceNotice = state.notice, onDismissNotice = controller::dismissNotice,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp))
                }
            }

            if (!fullscreen && sideBySide) AnimePlayerEpisodes(detail?.episodes.orEmpty(), episode?.id, state, onEpisode,
                Modifier.offset(x = videoWidth + 16.dp, y = headerHeight).width(300.dp)
                    .height((maxHeight - headerHeight - bottomInset).coerceAtLeast(1.dp)))
            else if (!fullscreen && !shortWindow) AnimePlayerEpisodes(detail?.episodes.orEmpty(), episode?.id, state, onEpisode,
                Modifier.offset(y = episodeTop).fillMaxWidth().height((maxHeight - episodeTop - bottomInset).coerceAtLeast(1.dp)))
        }
    }
}

@Composable
private fun AnimeVideoStatus(resolving: Boolean, failure: String?, playback: AnimePlayerState,
    next: AnimeEpisode?, onRetry: () -> Unit, onSources: () -> Unit, onEpisode: (AnimeEpisode) -> Unit) {
    when {
        resolving -> PlayerStageMessage("正在取得播放地址", "返回和换源仍可使用。")
        failure != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlayerStageMessage("播放暂未成功", failure)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MotionButton(onClick = onRetry, modifier = Modifier.heightIn(min = 44.dp)) { Text("重试") }
                MotionOutlinedButton(onClick = onSources, modifier = Modifier.heightIn(min = 44.dp),
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("换个来源") }
            }
        }
        playback.phase == AnimePlayerPhase.IDLE -> PlayerStageMessage("正在准备播放", null)
        playback.phase == AnimePlayerPhase.OPENING -> PlayerStageMessage("正在打开视频", "等待播放器准备")
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
    backdrop: HazeState, previous: AnimeEpisode?, next: AnimeEpisode?, onEpisode: (AnimeEpisode) -> Unit,
    onSources: () -> Unit, onToggle: () -> Unit, onInteraction: () -> Unit,
    onHoldControls: (Boolean) -> Unit, workspaceNotice: String?, onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier) {
    var scrub by remember(state.sessionGeneration) { mutableStateOf<Long?>(null) }
    var speedOpen by remember { mutableStateOf(false) }
    val duration = state.durationMs
    val ready = !blocked && state.phase in listOf(AnimePlayerPhase.OPENING, AnimePlayerPhase.READY, AnimePlayerPhase.ENDED)
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
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
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
                modifier = Modifier.size(44.dp), tooltip = "下一集") {
                Icon(Icons.Outlined.SkipNext, "下一集", Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            Box {
                MotionTextButton(onClick = { onInteraction(); onHoldControls(true); speedOpen = true }, enabled = ready,
                    modifier = Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(animeSpeedLabel(state.speed), style = MaterialTheme.typography.labelLarge)
                }
                CompactGlassPopover(speedOpen, backdrop, onDismiss = { speedOpen = false; onHoldControls(false); onInteraction() }, aboveAnchor = true) {
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        CompactMenuAction(if (speed == state.speed) Icons.Outlined.Check else Icons.Outlined.Speed,
                            animeSpeedLabel(speed), onClick = { speedOpen = false; onHoldControls(false); onInteraction(); player.setSpeed(speed) })
                    }
                }
            }
            MotionIconButton(onClick = { onInteraction(); onSources() }, modifier = Modifier.size(44.dp), tooltip = "切换来源") {
                Icon(Icons.Outlined.Tune, "播放来源", Modifier.size(20.dp))
            }
        }
    }
}

private fun animeSpeedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"

@Composable
private fun AnimePlayerEpisodes(episodes: List<AnimeEpisode>, currentId: String?, state: AnimeScreenState,
    onEpisode: (AnimeEpisode) -> Unit, modifier: Modifier = Modifier) {
    var group by remember { mutableIntStateOf(0) }
    val main = episodes.filter { it.kind == AnimeEpisodeKind.MAIN }
    val extra = episodes.filter { it.kind != AnimeEpisodeKind.MAIN }
    LaunchedEffect(currentId) {
        if (episodes.any { it.id == currentId && it.kind != AnimeEpisodeKind.MAIN }) group = 1
    }
    val shown = if (group == 1 && extra.isNotEmpty()) extra else main
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (extra.isNotEmpty()) MotionTabs(group, listOf("正片", "特别篇等"), onSelect = { group = it },
                modifier = Modifier.width(188.dp), controlHeight = 44.dp, treatment = MotionTabTreatment.Underline)
            else AnimeSectionTitle("选集", modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(shown, key = { it.id }) { episode ->
                AnimeSelection(animeEpisodeLabel(episode) + " · " + episode.title,
                    if (state.episodeOptions[episode.id].orEmpty().any { it.confirmed }) null else "需要确认来源",
                    selected = episode.id == currentId, onClick = { onEpisode(episode) })
            }
        }
    }
}
