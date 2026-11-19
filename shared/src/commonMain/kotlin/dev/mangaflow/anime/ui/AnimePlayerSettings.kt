package dev.mangaflow.anime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.*
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.animeEnhancementSessionKey
import dev.mangaflow.enhancement.ui.EnhancementPage
import dev.mangaflow.enhancement.ui.EnhancementPanelHost
import dev.mangaflow.enhancement.ui.EnhancementSettingsContent
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import kotlin.math.roundToInt

internal enum class AnimePlayerPanel {
    GENERAL, VIDEO, VIDEO_SOURCE, AUDIO, SUBTITLES, SPEED, SLEEP, SKIP, BUFFER, DOWNLOADS,
    DANMAKU, DANMAKU_SEARCH, DANMAKU_DISPLAY, DANMAKU_ACCESS,
}

/** One retained panel owns all playback disclosures; no dialog-on-dialog navigation. */
@Composable
internal fun AnimePlayerSettings(
    visible: Boolean,
    initialSection: AnimePlayerPanel,
    controller: AnimeController,
    state: AnimeScreenState,
    playback: AnimePlayerState,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    onPlayDownload: (AnimeDownloadSelection) -> Unit,
    onSources: (AnimeEpisode) -> Unit,
    initialEnhancementPage: EnhancementPage = EnhancementPage.QUALITY,
) {
    var section by remember { mutableStateOf(initialSection) }
    var enhancementPage by remember { mutableStateOf(initialEnhancementPage) }
    var detailParent by remember { mutableStateOf(initialEnhancementPage) }
    val enhancementTabs = listOf(EnhancementPage.QUALITY, EnhancementPage.FRAME,
        EnhancementPage.PREPARATION, EnhancementPage.STATUS)
    var speedDraft by remember { mutableStateOf("") }
    var holdDraft by remember { mutableStateOf("") }
    var minutesDraft by remember { mutableStateOf("") }
    var openingDraft by remember { mutableStateOf("") }
    var endingDraft by remember { mutableStateOf("") }
    var minimumDraft by remember { mutableStateOf("") }
    var maximumDraft by remember { mutableStateOf("") }
    var startDraft by remember { mutableStateOf("") }
    var rebufferDraft by remember { mutableStateOf("") }
    var retryDraft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<AnimeDownloadSelection?>(null) }
    var pendingSourceEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }
    LaunchedEffect(visible, initialSection, initialEnhancementPage) {
        if (visible) {
            section = initialSection; error = null; pendingDownload = null; pendingSourceEpisode = null
            enhancementPage = initialEnhancementPage; detailParent = initialEnhancementPage
            speedDraft = playback.speed.toString(); holdDraft = state.settings.longPressSpeed.toString()
            openingDraft = state.settings.skipOpeningSeconds.toString(); endingDraft = state.settings.skipEndingSeconds.toString()
            minimumDraft = bufferSeconds(state.settings.bufferMinMs); maximumDraft = bufferSeconds(state.settings.bufferMaxMs)
            startDraft = bufferSeconds(state.settings.bufferStartMs); rebufferDraft = bufferSeconds(state.settings.bufferRebufferMs)
            retryDraft = state.settings.automaticRecoveryAttempts.toString(); minutesDraft = ""
        }
    }
    val capabilities = playback.capabilities
    val target = state.playbackTarget
    val currentMedia = playback.takeIf {
        target == null && state.selectedEpisodeId != null && it.episodeId == state.selectedEpisodeId && it.assetKey != null
    }
    val episode = target?.episode ?: state.detail?.episodes?.firstOrNull { it.id == state.selectedEpisodeId }
    val enhancementSessionKey = currentMedia?.let(::animeEnhancementSessionKey)
    val enhancementSavedState = rememberSaveableStateHolder()
    val enhancementStateKey = "anime-enhancement:${enhancementSessionKey ?: "defaults"}"
    DisposableEffect(enhancementStateKey) {
        onDispose { enhancementSavedState.removeState(enhancementStateKey) }
    }
    val sectionScrolls = remember { mutableMapOf<String, LazyListState>() }
    val scrollKey = if (section == AnimePlayerPanel.VIDEO) "video:${enhancementPage.name}" else section.name
    val sectionScroll = remember(scrollKey) { sectionScrolls.getOrPut(scrollKey) { LazyListState() } }
    fun show(next: AnimePlayerPanel) {
        if (next == AnimePlayerPanel.VIDEO && section == AnimePlayerPanel.GENERAL)
            enhancementPage = EnhancementPage.QUALITY
        section = next; error = null
    }
    fun navigateEnhancement(next: EnhancementPage) {
        if (next !in enhancementTabs && enhancementPage in enhancementTabs)
            detailParent = enhancementPage
        enhancementPage = next; error = null
    }
    val enhancementParent = if (enhancementPage in enhancementTabs) null else detailParent
    val backLabel = when {
        section == AnimePlayerPanel.VIDEO_SOURCE -> "返回画质"
        section == AnimePlayerPanel.VIDEO && enhancementParent != null -> "返回${enhancementParent.title}"
        section in setOf(AnimePlayerPanel.DANMAKU_SEARCH, AnimePlayerPanel.DANMAKU_DISPLAY,
            AnimePlayerPanel.DANMAKU_ACCESS) -> "返回弹幕设置"
        else -> "返回播放设置"
    }
    fun back() {
        when {
            section == AnimePlayerPanel.VIDEO_SOURCE -> {
                enhancementPage = EnhancementPage.QUALITY; show(AnimePlayerPanel.VIDEO)
            }
            section == AnimePlayerPanel.VIDEO && enhancementParent != null -> navigateEnhancement(enhancementParent)
            section in setOf(AnimePlayerPanel.DANMAKU_SEARCH, AnimePlayerPanel.DANMAKU_DISPLAY,
                AnimePlayerPanel.DANMAKU_ACCESS) -> show(AnimePlayerPanel.DANMAKU)
            else -> show(AnimePlayerPanel.GENERAL)
        }
    }
    EnhancementPanelHost(visible, backdrop, onDismiss, maximumWidth = 480.dp, onClosed = {
        val request = pendingDownload
        val sourceEpisode = pendingSourceEpisode
        pendingDownload = null
        pendingSourceEpisode = null
        onClosed()
        if (request != null) onPlayDownload(request)
        else if (sourceEpisode != null) onSources(sourceEpisode)
    }, surface = { modifier, body -> AnimePanelSurface(backdrop, modifier) { body() } }) {
            MotionBackHandler(visible && section != AnimePlayerPanel.GENERAL, state = null, onBack = ::back)
            val panelHeight = LocalMangaWindow.current.panelHeight
            Column(Modifier.fillMaxWidth().heightIn(max = if (section in setOf(AnimePlayerPanel.VIDEO,
                AnimePlayerPanel.VIDEO_SOURCE)) minOf(panelHeight, 560.dp) else panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (section != AnimePlayerPanel.GENERAL) MotionIconButton(onClick = ::back,
                        modifier = Modifier.size(44.dp), tooltip = backLabel) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, Modifier.size(20.dp))
                    }
                    Text(when (section) {
                        AnimePlayerPanel.GENERAL -> "播放设置"
                        AnimePlayerPanel.VIDEO -> if (enhancementPage in enhancementTabs) "画质与性能" else enhancementPage.title
                        AnimePlayerPanel.VIDEO_SOURCE -> "片源与显示"
                        AnimePlayerPanel.AUDIO -> "声音"
                        AnimePlayerPanel.SUBTITLES -> "字幕"
                        AnimePlayerPanel.SPEED -> "播放速度"
                        AnimePlayerPanel.SLEEP -> "睡眠计时"
                        AnimePlayerPanel.SKIP -> "片头与片尾"
                        AnimePlayerPanel.BUFFER -> "缓冲与恢复"
                        AnimePlayerPanel.DOWNLOADS -> "离线下载"
                        AnimePlayerPanel.DANMAKU -> "弹幕"
                        AnimePlayerPanel.DANMAKU_SEARCH -> "匹配本集弹幕"
                        AnimePlayerPanel.DANMAKU_DISPLAY -> "弹幕显示与时间"
                        AnimePlayerPanel.DANMAKU_ACCESS -> "弹幕服务配置"
                    }, Modifier.weight(1f).padding(start = if (section == AnimePlayerPanel.GENERAL) 8.dp else 0.dp),
                        style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭播放设置") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                if (section == AnimePlayerPanel.VIDEO && enhancementPage in enhancementTabs) {
                    AnimeChoiceStrip(listOf("画质", "补帧", "预处理", "状态"), enhancementTabs.indexOf(enhancementPage),
                        onSelect = { navigateEnhancement(enhancementTabs[it]) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), active = visible)
                }
                if (section == AnimePlayerPanel.DOWNLOADS) AnimeDownloadsContent(controller, state, backdrop,
                    onPlay = { pendingDownload = it; onDismiss() }, modifier = Modifier.weight(1f, fill = false))
                else if (section in setOf(AnimePlayerPanel.DANMAKU, AnimePlayerPanel.DANMAKU_SEARCH,
                    AnimePlayerPanel.DANMAKU_DISPLAY, AnimePlayerPanel.DANMAKU_ACCESS)) AnimeDanmakuPane(section, controller, state,
                    onSection = ::show, modifier = Modifier.weight(1f, fill = false))
                else LazyColumn(Modifier.weight(1f, fill = false), state = sectionScroll,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (section) {
                        AnimePlayerPanel.GENERAL -> {
                            item { AnimeQuietLabel("画面与声音") }
                            item { AnimePlayerSettingLink(Icons.Outlined.AspectRatio, "画质与性能",
                                "画质、补帧与运行状态") { show(AnimePlayerPanel.VIDEO) } }
                            if (AnimePlayerCapability.VOLUME in capabilities || AnimePlayerCapability.AUDIO_TRACKS in capabilities) item {
                                val soundSummary = listOfNotNull(
                                    if (AnimePlayerCapability.VOLUME in capabilities) (playback.volume * 100).roundToInt().toString() + "%" else null,
                                    if (AnimePlayerCapability.AUDIO_TRACKS in capabilities)
                                        playback.tracks.firstOrNull { it.kind == AnimeTrackKind.AUDIO && it.selected }?.label ?: "自动音轨"
                                    else null,
                                ).joinToString(" · ")
                                AnimePlayerSettingLink(Icons.Outlined.VolumeUp, "声音", soundSummary) {
                                    show(AnimePlayerPanel.AUDIO)
                                }
                            }
                            if (AnimePlayerCapability.SUBTITLE_TRACKS in capabilities) item {
                                AnimePlayerSettingLink(Icons.Outlined.Subtitles, "字幕",
                                    playback.tracks.firstOrNull { it.kind == AnimeTrackKind.SUBTITLE && it.selected }?.label ?: "关闭") {
                                    show(AnimePlayerPanel.SUBTITLES)
                                }
                            }
                            item { AnimePlayerSettingLink(Icons.Outlined.ChatBubbleOutline, "弹幕", "匹配与显示") {
                                show(AnimePlayerPanel.DANMAKU)
                            } }
                            item { AnimeSectionTitle("观看习惯") }
                            item { AnimePlayerSettingLink(Icons.Outlined.Speed, "速度", animeSpeedLabel(playback.speed)) { show(AnimePlayerPanel.SPEED) } }
                            item { AnimePlayerSettingLink(Icons.Outlined.Bedtime, "睡眠计时", when {
                                state.sleepAtEpisodeEnd -> "本集结束"
                                state.sleepDeadlineMs != null -> animeTime((state.sleepDeadlineMs - wallTimeMillis()).coerceAtLeast(0L))
                                else -> "关闭"
                            }) { show(AnimePlayerPanel.SLEEP) } }
                            item { AnimePlayerSettingLink(Icons.Outlined.SkipNext, "片头与片尾",
                                state.settings.skipOpeningSeconds.toString() + " / " + state.settings.skipEndingSeconds + " 秒") { show(AnimePlayerPanel.SKIP) } }
                            item { AnimeSectionTitle("连续播放") }
                            item { SettingsToggle("自动播放下一集", state.settings.autoPlayNext, Icons.Outlined.PlayCircleOutline,
                                onCheckedChange = { enabled -> controller.updatePlaybackSettings { it.copy(autoPlayNext = enabled) } }) }
                            item { SettingsToggle("提前准备下一集", state.settings.prepareNextEpisode, Icons.Outlined.DownloadForOffline,
                                onCheckedChange = { enabled -> controller.updatePlaybackSettings { it.copy(prepareNextEpisode = enabled) } }) }
                            state.nextEpisodePreparation?.let { preparation ->
                                val next = state.detail?.episodes?.firstOrNull { it.id == preparation.episodeId }
                                if (next != null) item {
                                    AnimeQuietLabel("${animeEpisodeLabel(next)} · ${animePreparationLabel(preparation)}")
                                }
                            }
                            item { SettingsToggle("失败后尝试已确认的来源", state.settings.automaticSourceRecovery, Icons.Outlined.SwapHoriz,
                                onCheckedChange = { enabled -> controller.updatePlaybackSettings { it.copy(automaticSourceRecovery = enabled) } }) }
                            if (AnimePlayerCapability.BACKGROUND_AUDIO in capabilities) item {
                                SettingsToggle("后台播放声音", state.settings.backgroundAudio, Icons.Outlined.Headphones,
                                    onCheckedChange = { enabled -> controller.updatePlaybackSettings { it.copy(backgroundAudio = enabled) } })
                            }
                            if (AnimePlayerCapability.PICTURE_IN_PICTURE in capabilities) item {
                                CompactMenuAction(Icons.Outlined.PictureInPictureAlt, "进入画中画", onClick = {
                                    controller.player.enterPictureInPicture(); onDismiss()
                                })
                            }
                            item { AnimeSectionTitle("离线与高级") }
                            item { CompactMenuAction(Icons.Outlined.Download, "下载当前集",
                                enabled = !state.resolving && state.selectedEpisodeId != null &&
                                    playback.phase in setOf(AnimePlayerPhase.READY, AnimePlayerPhase.ENDED),
                                onClick = { controller.downloadCurrent(); show(AnimePlayerPanel.DOWNLOADS) }) }
                            item { AnimePlayerSettingLink(Icons.Outlined.DownloadForOffline, "离线任务", "查看") {
                                show(AnimePlayerPanel.DOWNLOADS)
                            } }
                            item { AnimePlayerSettingLink(Icons.Outlined.Tune, "缓冲与恢复", "高级") { show(AnimePlayerPanel.BUFFER) } }
                        }
                        AnimePlayerPanel.VIDEO -> {
                            item(key = "enhancement-center") {
                                enhancementSavedState.SaveableStateProvider(enhancementStateKey) {
                                    EnhancementSettingsContent(EnhancementContent.ANIME,
                                        sessionKey = enhancementSessionKey,
                                        animePlayback = currentMedia, page = enhancementPage, onPageChange = ::navigateEnhancement,
                                        qualityFooter = {
                                            val resolution = currentMedia?.let {
                                                state.playbackResolutions.firstOrNull { it.id == state.selectedResolutionId }?.label
                                            }
                                            AnimePlayerSettingLink(Icons.Outlined.AspectRatio, "片源与显示",
                                                listOfNotNull(resolution, animeScaleLabel(playback.videoScale)).joinToString(" · ")) {
                                                show(AnimePlayerPanel.VIDEO_SOURCE)
                                            }
                                        })
                                }
                            }
                        }
                        AnimePlayerPanel.VIDEO_SOURCE -> {
                            if (AnimePlayerCapability.VIDEO_SCALE in capabilities) item {
                                AnimeSectionTitle("画面比例")
                                AnimeChoiceStrip(listOf("适应", "拉伸", "填满"), playback.videoScale.ordinal,
                                    onSelect = { controller.player.setVideoScale(AnimeVideoScale.entries[it]) },
                                    modifier = Modifier.fillMaxWidth())
                            }
                            item {
                                AnimeSectionTitle("优先清晰度")
                                val heights = listOf(0, 480, 720, 1080, 2160)
                                AnimeChoiceStrip(heights.map { if (it == 0) "自动" else "${it}p" },
                                    heights.indexOf(state.settings.preferredSourceHeight), onSelect = {
                                        val height = heights[it]
                                        controller.updatePlaybackSettings { it.copy(preferredSourceHeight = height) }
                                    }, modifier = Modifier.fillMaxWidth())
                                AnimeQuietLabel("重新开播时，从来源明确提供的选项中优先取不超过目标的最高档。")
                            }
                            item {
                                val dimensions = currentMedia?.let { media ->
                                    if (media.videoWidth != null && media.videoHeight != null)
                                        "${media.videoWidth} × ${media.videoHeight}" else null
                                }
                                AnimeQuietLabel("当前解码画质 · ${dimensions ?: "—"}")
                            }
                            if (currentMedia != null && state.playbackResolutions.isNotEmpty()) {
                                item { AnimeSectionTitle("来源画质") }
                                items(state.playbackResolutions, key = { "resolution:" + it.id }) { resolution ->
                                    AnimeSelection(resolution.label, selected = resolution.id == state.selectedResolutionId,
                                        onClick = { controller.selectResolution(resolution.id) })
                                }
                            }
                            val videoTracks = currentMedia?.tracks.orEmpty().filter { it.kind == AnimeTrackKind.VIDEO }
                            if (currentMedia != null && videoTracks.isNotEmpty() && AnimePlayerCapability.VIDEO_TRACKS in capabilities) {
                                item { AnimeSectionTitle("视频轨道") }
                                item { AnimeSelection("自动选择", selected = videoTracks.none { it.selected },
                                    onClick = { controller.player.selectTrack(AnimeTrackKind.VIDEO, null) }) }
                                items(videoTracks, key = { "video:" + it.id }) { track ->
                                    AnimeSelection(track.label, selected = track.selected,
                                        onClick = { controller.player.selectTrack(track.kind, track.id) })
                                }
                            }
                            if (currentMedia == null) item {
                                AnimeQuietLabel("开播后显示本集实际清晰度与轨道；优先清晰度可提前设置。")
                            } else if (state.playbackResolutions.isEmpty() && videoTracks.isEmpty()) item {
                                AnimeQuietLabel("当前尚无其他清晰度可选；本地增强可在画质页调整。")
                            }
                        }
                        AnimePlayerPanel.AUDIO, AnimePlayerPanel.SUBTITLES -> {
                            val kind = if (section == AnimePlayerPanel.AUDIO) AnimeTrackKind.AUDIO else AnimeTrackKind.SUBTITLE
                            val tracks = playback.tracks.filter { it.kind == kind }
                            if (kind == AnimeTrackKind.AUDIO && AnimePlayerCapability.VOLUME in capabilities) item {
                                AnimeSectionTitle("播放音量", (playback.volume * 100).roundToInt().toString() + "%")
                                MotionSlider(playback.volume, onValueChange = controller.player::setVolume,
                                    valueRange = 0f..1f, formatValue = { (it * 100).roundToInt().toString() + "%" },
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "播放音量" })
                            }
                            val supportsTracks = if (kind == AnimeTrackKind.AUDIO)
                                AnimePlayerCapability.AUDIO_TRACKS in capabilities else AnimePlayerCapability.SUBTITLE_TRACKS in capabilities
                            if (supportsTracks) {
                                if (kind == AnimeTrackKind.AUDIO) item { AnimeSectionTitle("音轨") }
                                if (kind == AnimeTrackKind.SUBTITLE || AnimePlayerCapability.AUTOMATIC_AUDIO_TRACK in capabilities) item {
                                    AnimeSelection(if (kind == AnimeTrackKind.SUBTITLE) "关闭字幕" else "自动选择",
                                        selected = tracks.none { it.selected }, onClick = { controller.player.selectTrack(kind, null) })
                                }
                                items(tracks, key = { it.id }) { track ->
                                    AnimeSelection(track.label, track.language, track.selected,
                                        onClick = { controller.player.selectTrack(kind, track.id) })
                                }
                                if (tracks.isEmpty()) item { AnimeQuietLabel("当前资源尚未提供可选择的轨道。") }
                            }
                        }
                        AnimePlayerPanel.SPEED -> {
                            item {
                                val presets = listOf(.75f, 1f, 1.25f, 1.5f, 2f)
                                val speeds = if (playback.speed in presets) presets else presets + playback.speed
                                AnimeChoiceStrip(speeds.map(::animeSpeedLabel), speeds.indexOf(playback.speed), onSelect = {
                                        val speed = speeds[it]
                                        speedDraft = speed.toString(); controller.player.setSpeed(speed)
                                    }, modifier = Modifier.fillMaxWidth())
                            }
                            item { AnimeSectionTitle("自定义速度") }
                            item { AnimeTextField(speedDraft, { speedDraft = it; error = null }, "播放倍速") }
                            item { AnimeTextField(holdDraft, { holdDraft = it; error = null }, "长按时的倍速") }
                            item { AnimeQuietLabel("按住视频临时加速，松开恢复原速度。") }
                            item { MotionButton(onClick = {
                                val speed = speedDraft.trim().toFloatOrNull()
                                val hold = holdDraft.trim().toFloatOrNull()
                                if (speed == null || !speed.isFinite() || speed <= 0f || hold == null || !hold.isFinite() || hold <= 0f)
                                    error = "倍速必须是大于零的数字。"
                                else {
                                    controller.player.setSpeed(speed)
                                    controller.updatePlaybackSettings { it.copy(longPressSpeed = hold) }; error = null
                                }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("应用速度") } }
                        }
                        AnimePlayerPanel.SLEEP -> {
                            item { AnimeSelection("关闭计时", selected = state.sleepDeadlineMs == null && !state.sleepAtEpisodeEnd,
                                onClick = { controller.setSleepTimer(null) }) }
                            item { AnimeSelection("本集结束后暂停", selected = state.sleepAtEpisodeEnd,
                                onClick = { controller.setSleepTimer(null, atEpisodeEnd = true) }) }
                            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(15, 30, 60).forEach { minutes -> MotionTextButton(onClick = { controller.setSleepTimer(minutes) },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("$minutes 分钟") } }
                            } }
                            item { AnimeTextField(minutesDraft, { minutesDraft = it; error = null }, "自定义分钟数") }
                            item { MotionButton(onClick = {
                                val minutes = minutesDraft.trim().toIntOrNull()
                                if (minutes == null || minutes <= 0) error = "请输入大于零的分钟数。"
                                else { controller.setSleepTimer(minutes); error = null }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("开始计时") } }
                        }
                        AnimePlayerPanel.SKIP -> {
                            item { AnimeQuietLabel("每集跳过的秒数；设为 0 关闭。") }
                            item { AnimeTextField(openingDraft, { openingDraft = it; error = null }, "片头秒数") }
                            item { AnimeTextField(endingDraft, { endingDraft = it; error = null }, "片尾秒数") }
                            item { MotionButton(onClick = {
                                val opening = openingDraft.trim().toIntOrNull(); val ending = endingDraft.trim().toIntOrNull()
                                if (opening == null || ending == null || opening < 0 || ending < 0) error = "秒数必须是非负整数。"
                                else { controller.updatePlaybackSettings { it.copy(skipOpeningSeconds = opening,
                                    skipEndingSeconds = ending) }; error = null }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("应用跳过设置") } }
                        }
                        AnimePlayerPanel.BUFFER -> {
                            item {
                                AnimeSectionTitle("本集诊断", episode?.let(::animeEpisodeLabel))
                                val source = target?.sourceName ?: target?.failure?.sourceName
                                    ?: state.selectedPlaybackOption?.sourceName.takeIf { currentMedia != null }
                                val route = state.playbackRouteMode?.takeIf { currentMedia != null }?.let(::downloadRouteLabel)
                                val stage = when {
                                    state.resolving && state.autoMatching -> "正在查找线路"
                                    state.resolving -> "正在解析播放地址"
                                    target?.error != null -> target.failure?.stage ?: "取址未完成"
                                    currentMedia?.phase == AnimePlayerPhase.OPENING -> "正在打开媒体"
                                    currentMedia?.phase == AnimePlayerPhase.ERROR -> "媒体打开或播放失败"
                                    currentMedia?.playbackSuppressed == true -> "播放暂时被系统挂起"
                                    currentMedia?.isBuffering == true -> "正在缓冲"
                                    currentMedia?.isPlaying == true -> "正在播放"
                                    currentMedia?.ended == true -> "本集已播完"
                                    currentMedia?.phase == AnimePlayerPhase.READY -> "已暂停"
                                    else -> "等待媒体"
                                }
                                AnimeQuietLabel(listOfNotNull(stage, source, route).joinToString(" · "))
                                (target?.failure?.reason ?: target?.error ?: currentMedia?.error)?.let { AnimeQuietLabel(it) }
                                val firstFrame = state.playbackFirstFrameMs.takeIf { currentMedia != null }?.let { "$it ms" }
                                val buffered = currentMedia?.bufferedAheadMs?.takeIf { it >= 0 }?.let { animeTime(it) }
                                val speed = currentMedia?.downloadBytesPerSecond?.takeIf { it >= 0 }?.let(::animeSourceRate)
                                AnimeQuietLabel("打开至首帧 ${firstFrame ?: "—"} · 已缓冲 ${buffered ?: "—"}")
                                AnimeQuietLabel("实播传输速率 · ${speed ?: "—"}")
                                if (episode != null) MotionTextButton(onClick = {
                                    pendingSourceEpisode = episode
                                    onDismiss()
                                }, modifier = Modifier.heightIn(min = 44.dp)) { Text("查看本集线路") }
                            }
                            val configureBuffer = AnimePlayerCapability.BUFFER_CONTROL in capabilities ||
                                currentMedia == null || playback.phase in setOf(AnimePlayerPhase.OPENING, AnimePlayerPhase.ERROR)
                            if (configureBuffer) {
                                item { AnimeQuietLabel("缓冲目标会保存，实际缓存量随内核、媒体与可用预算变化。") }
                                item { AnimeTextField(minimumDraft, { minimumDraft = it; error = null }, "最小缓冲（秒）") }
                                item { AnimeTextField(maximumDraft, { maximumDraft = it; error = null }, "最大缓冲（秒）") }
                                item { AnimeTextField(startDraft, { startDraft = it; error = null }, "起播缓冲（秒）") }
                                item { AnimeTextField(rebufferDraft, { rebufferDraft = it; error = null }, "恢复缓冲（秒）") }
                            } else item { AnimeQuietLabel("当前内核自行管理缓冲，可调整单线路重试次数。") }
                            item { AnimeTextField(retryDraft, { retryDraft = it; error = null }, "单线路重试次数，0 关闭自动恢复") }
                            item { MotionButton(onClick = {
                                val minimum = bufferMillis(minimumDraft); val maximum = bufferMillis(maximumDraft)
                                val start = bufferMillis(startDraft); val rebuffer = bufferMillis(rebufferDraft)
                                val retries = retryDraft.trim().toIntOrNull()
                                if (minimum == null || maximum == null || start == null || rebuffer == null || retries == null ||
                                    retries < 0 || maximum <= 0 || maximum < minimum || minimum < start || minimum < rebuffer)
                                    error = "最大缓冲 ≥ 最小缓冲 ≥ 起播及恢复缓冲，恢复次数不能为负。"
                                else { controller.updatePlaybackSettings { it.copy(bufferMinMs = minimum,
                                    bufferMaxMs = maximum, bufferStartMs = start, bufferRebufferMs = rebuffer,
                                    automaticRecoveryAttempts = retries) }; error = null }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("应用缓冲设置") } }
                        }
                        AnimePlayerPanel.DOWNLOADS -> Unit
                        AnimePlayerPanel.DANMAKU, AnimePlayerPanel.DANMAKU_SEARCH,
                        AnimePlayerPanel.DANMAKU_DISPLAY, AnimePlayerPanel.DANMAKU_ACCESS -> Unit
                    }
                    if (error != null) item { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    if (state.notice != null) item { AnimeQuietLabel(state.notice) }
                }
            }
    }
}

@Composable
private fun AnimePlayerSettingLink(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    // Keep CompactMenuAction's quiet hit surface and press response, with separate text roles.
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .motionPress(interaction, pressedScale = .98f).heightIn(min = 56.dp)
        .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
    }
}

internal fun animeScaleLabel(scale: AnimeVideoScale): String = when (scale) {
    AnimeVideoScale.FIT -> "适应"
    AnimeVideoScale.FILL -> "拉伸"
    AnimeVideoScale.ZOOM -> "填满"
}

internal fun animeSpeedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"

private fun bufferSeconds(milliseconds: Int): String = if (milliseconds % 1000 == 0) (milliseconds / 1000).toString()
    else (milliseconds / 1000.0).toString()

private fun bufferMillis(seconds: String): Int? = seconds.trim().toDoubleOrNull()?.takeIf {
    it.isFinite() && it >= 0 && it * 1000.0 <= Int.MAX_VALUE
}?.let { (it * 1000.0).toInt() }
