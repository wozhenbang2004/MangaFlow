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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.*
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.animeEnhancementSessionKey
import dev.mangaflow.enhancement.ui.EnhancementPage
import dev.mangaflow.enhancement.ui.EnhancementNavigation
import dev.mangaflow.enhancement.ui.EnhancementSettingsContent
import dev.mangaflow.enhancement.ui.titleFor
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import kotlin.math.roundToInt

internal enum class AnimePlayerPanel {
    GENERAL, ADVANCED, VIDEO, VIDEO_SOURCE, VIDEO_PREFERENCE, AUDIO, SUBTITLES, SPEED, SLEEP, SKIP, PREPARATION, BUFFER, NEXT_PREPARATION, DOWNLOADS,
    DANMAKU, DANMAKU_SEARCH, DANMAKU_DISPLAY, DANMAKU_ACCESS,
}

private data class AnimePictureInPictureTarget(
    val subjectId: String, val episodeId: String, val assetKey: String,
    val session: Long, val timeline: Long, val fullscreen: Boolean,
)

private fun AnimePlayerState.pictureInPictureTarget(
    screen: AnimeScreenState, visibleFullscreen: Boolean?,
): AnimePictureInPictureTarget? {
    val fullscreen = visibleFullscreen ?: return null
    val subjectId = screen.detail?.subject?.id ?: return null
    val episode = episodeId ?: return null
    val asset = assetKey ?: return null
    if (AnimePlayerCapability.PICTURE_IN_PICTURE !in capabilities || inPictureInPicture ||
        phase != AnimePlayerPhase.READY || error != null || firstFrameRendered != true || hasVideoTracks == false ||
        screen.selectedEpisodeId != episode || screen.resolving || screen.playbackTarget != null ||
        screen.pendingPlayback != null) return null
    return AnimePictureInPictureTarget(subjectId, episode, asset, sessionGeneration, timelineGeneration, fullscreen)
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
    artwork: HazeState?,
    overVideo: Boolean,
    pictureInPictureFullscreen: Boolean?,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    onPlayDownload: (AnimeDownloadSelection) -> Unit,
    onSources: (AnimeEpisode) -> Unit,
    initialEnhancementPage: EnhancementPage = EnhancementPage.QUALITY,
    onStorage: () -> Unit = {},
    readingsVisible: Boolean = false,
    onReadingsVisible: (Boolean) -> Unit = {},
) {
    var section by remember { mutableStateOf(initialSection) }
    val enhancementNavigation = remember { EnhancementNavigation(initialEnhancementPage) }
    val enhancementPage = enhancementNavigation.page
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
    var forwardDraft by remember { mutableStateOf("") }
    var backDraft by remember { mutableStateOf("") }
    var leadDraft by remember { mutableStateOf("") }
    var nextSecondsDraft by remember { mutableStateOf("") }
    var nextCacheDraft by remember { mutableStateOf("") }
    var onlineDraft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<AnimeDownloadSelection?>(null) }
    var pendingSourceEpisode by remember { mutableStateOf<AnimeEpisode?>(null) }
    var pendingStorage by remember { mutableStateOf(false) }
    var pendingPictureInPicture by remember { mutableStateOf<AnimePictureInPictureTarget?>(null) }
    var videoReturnSection by remember { mutableStateOf(AnimePlayerPanel.GENERAL) }
    var sourceReturnSection by remember { mutableStateOf(AnimePlayerPanel.GENERAL) }
    LaunchedEffect(visible, initialSection, initialEnhancementPage) {
        if (visible) {
            section = initialSection; error = null; pendingDownload = null; pendingSourceEpisode = null
            pendingStorage = false; videoReturnSection = AnimePlayerPanel.GENERAL
            pendingPictureInPicture = null
            sourceReturnSection = AnimePlayerPanel.GENERAL
            enhancementNavigation.reset(initialEnhancementPage)
            speedDraft = playback.speed.toString(); holdDraft = state.settings.longPressSpeed.toString()
            openingDraft = state.settings.skipOpeningSeconds.toString(); endingDraft = state.settings.skipEndingSeconds.toString()
            minimumDraft = bufferSeconds(state.settings.bufferMinMs); maximumDraft = bufferSeconds(state.settings.bufferMaxMs)
            startDraft = bufferSeconds(state.settings.bufferStartMs); rebufferDraft = bufferSeconds(state.settings.bufferRebufferMs)
            retryDraft = state.settings.automaticRecoveryAttempts.toString(); minutesDraft = ""
            forwardDraft = state.settings.bufferForwardMiB?.toString().orEmpty()
            backDraft = state.settings.bufferBackMiB?.toString().orEmpty()
            leadDraft = state.settings.nextEpisodeLeadSeconds?.toString().orEmpty()
            nextSecondsDraft = state.settings.nextEpisodeBufferSeconds?.toString().orEmpty()
            nextCacheDraft = state.settings.nextEpisodeCacheMiB?.toString().orEmpty()
            onlineDraft = state.settings.onlinePreparationSeconds?.toString().orEmpty()
        }
    }
    val capabilities = playback.capabilities
    val preparationBudget = controller.preparationBudget()
    val target = state.playbackTarget
    val currentMedia = playback.takeIf {
        target == null && state.selectedEpisodeId != null && it.episodeId == state.selectedEpisodeId && it.assetKey != null
    }
    var showVideoInfo by remember(currentMedia?.assetKey) { mutableStateOf(false) }
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
        if (next == AnimePlayerPanel.VIDEO_SOURCE && section != AnimePlayerPanel.VIDEO_PREFERENCE)
            sourceReturnSection = if (section == AnimePlayerPanel.VIDEO) AnimePlayerPanel.VIDEO else AnimePlayerPanel.GENERAL
        if (next == AnimePlayerPanel.VIDEO && section != AnimePlayerPanel.VIDEO_SOURCE) {
            videoReturnSection = when (section) {
                AnimePlayerPanel.PREPARATION, AnimePlayerPanel.ADVANCED -> section
                else -> AnimePlayerPanel.GENERAL
            }
            if (section == AnimePlayerPanel.GENERAL || section == AnimePlayerPanel.ADVANCED) enhancementNavigation.reset()
        }
        section = next; error = null
    }
    fun navigateEnhancement(next: EnhancementPage) {
        if (next in setOf(EnhancementPage.MODEL_DETAIL, EnhancementPage.ENGINE_DETAIL, EnhancementPage.IMPORT))
            sectionScrolls["video:${next.name}"] = LazyListState()
        enhancementNavigation.open(next)
        error = null
    }
    val enhancementParent = enhancementNavigation.parent
    val backLabel = when {
        section in setOf(AnimePlayerPanel.BUFFER, AnimePlayerPanel.NEXT_PREPARATION) -> "返回播放准备"
        section == AnimePlayerPanel.VIDEO_SOURCE && sourceReturnSection == AnimePlayerPanel.VIDEO -> "返回画质"
        section == AnimePlayerPanel.VIDEO_PREFERENCE -> "返回片源与显示"
        section == AnimePlayerPanel.VIDEO && enhancementParent != null -> "返回${enhancementParent.titleFor(enhancementSessionKey != null)}"
        section == AnimePlayerPanel.VIDEO && videoReturnSection == AnimePlayerPanel.PREPARATION -> "返回播放准备"
        (section == AnimePlayerPanel.VIDEO && videoReturnSection == AnimePlayerPanel.ADVANCED) ||
            section in setOf(AnimePlayerPanel.SLEEP, AnimePlayerPanel.SKIP, AnimePlayerPanel.DOWNLOADS, AnimePlayerPanel.PREPARATION) -> "返回更多播放设置"
        section in setOf(AnimePlayerPanel.DANMAKU_SEARCH, AnimePlayerPanel.DANMAKU_DISPLAY,
            AnimePlayerPanel.DANMAKU_ACCESS) -> "返回弹幕设置"
        else -> "返回播放设置"
    }
    fun back() {
        when {
            section in setOf(AnimePlayerPanel.BUFFER, AnimePlayerPanel.NEXT_PREPARATION) -> show(AnimePlayerPanel.PREPARATION)
            section == AnimePlayerPanel.VIDEO_PREFERENCE -> show(AnimePlayerPanel.VIDEO_SOURCE)
            section == AnimePlayerPanel.VIDEO_SOURCE -> show(sourceReturnSection)
            section == AnimePlayerPanel.VIDEO && enhancementParent != null -> { enhancementNavigation.back(); error = null }
            section == AnimePlayerPanel.VIDEO -> show(videoReturnSection)
            section in setOf(AnimePlayerPanel.SLEEP, AnimePlayerPanel.SKIP, AnimePlayerPanel.DOWNLOADS,
                AnimePlayerPanel.PREPARATION) -> show(AnimePlayerPanel.ADVANCED)
            section in setOf(AnimePlayerPanel.DANMAKU_SEARCH, AnimePlayerPanel.DANMAKU_DISPLAY,
                AnimePlayerPanel.DANMAKU_ACCESS) -> show(AnimePlayerPanel.DANMAKU)
            else -> show(AnimePlayerPanel.GENERAL)
        }
    }
    AnimePlaybackPanel(visible, overVideo, artwork, onDismiss, onClosed = {
        val request = pendingDownload
        val sourceEpisode = pendingSourceEpisode
        val storage = pendingStorage
        val pictureInPicture = pendingPictureInPicture
        pendingDownload = null
        pendingSourceEpisode = null
        pendingStorage = false
        pendingPictureInPicture = null
        onClosed()
        if (request != null) onPlayDownload(request)
        else if (sourceEpisode != null) onSources(sourceEpisode)
        else if (storage) onStorage()
        else if (pictureInPicture != null) {
            val player = controller.player
            // The retained dialog is gone. A different episode, seek or Back must not take over this handoff.
            if (player.state.value.pictureInPictureTarget(controller.state.value, pictureInPictureFullscreen) == pictureInPicture)
                player.enterPictureInPicture()
        }
    }) {
            MotionBackHandler(visible && section != AnimePlayerPanel.GENERAL, state = null, onBack = ::back)
            Column(Modifier.fillMaxWidth().height(LocalMangaWindow.current.height)) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (section != AnimePlayerPanel.GENERAL) MotionIconButton(onClick = ::back,
                        modifier = Modifier.size(44.dp), tooltip = backLabel) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, backLabel, Modifier.size(20.dp))
                    }
                    val panelTitle = when (section) {
                        AnimePlayerPanel.GENERAL -> "播放设置"
                        AnimePlayerPanel.ADVANCED -> "更多播放设置"
                        AnimePlayerPanel.VIDEO -> if (enhancementPage in enhancementTabs) "番剧体验策略" else enhancementPage.title
                        AnimePlayerPanel.VIDEO_SOURCE -> "片源与显示"
                        AnimePlayerPanel.VIDEO_PREFERENCE -> "开播清晰度偏好"
                        AnimePlayerPanel.AUDIO -> "声音"
                        AnimePlayerPanel.SUBTITLES -> "字幕"
                        AnimePlayerPanel.SPEED -> "播放速度"
                        AnimePlayerPanel.SLEEP -> "睡眠计时"
                        AnimePlayerPanel.SKIP -> "片头与片尾"
                        AnimePlayerPanel.BUFFER -> "缓冲与恢复"
                        AnimePlayerPanel.PREPARATION -> "播放准备"
                        AnimePlayerPanel.NEXT_PREPARATION -> "下一集准备"
                        AnimePlayerPanel.DOWNLOADS -> "离线下载"
                        AnimePlayerPanel.DANMAKU -> "弹幕"
                        AnimePlayerPanel.DANMAKU_SEARCH -> "匹配本集弹幕"
                        AnimePlayerPanel.DANMAKU_DISPLAY -> "弹幕显示与时间"
                        AnimePlayerPanel.DANMAKU_ACCESS -> "弹幕服务配置"
                    }
                    Text(panelTitle, Modifier.weight(1f).padding(start = if (section == AnimePlayerPanel.GENERAL) 8.dp else 0.dp)
                        .semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭播放设置") {
                        Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                    }
                }
                if (section == AnimePlayerPanel.VIDEO && enhancementPage in enhancementTabs) {
                    AnimeChoiceStrip(enhancementTabs.map { it.titleFor(enhancementSessionKey != null) }, enhancementTabs.indexOf(enhancementPage),
                        onSelect = { navigateEnhancement(enhancementTabs[it]) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), active = visible)
                }
                if (section == AnimePlayerPanel.DOWNLOADS) AnimeDownloadsContent(controller, state, backdrop,
                    onPlay = { pendingDownload = it; onDismiss() }, modifier = Modifier.weight(1f, fill = false))
                else if (section in setOf(AnimePlayerPanel.DANMAKU, AnimePlayerPanel.DANMAKU_SEARCH,
                    AnimePlayerPanel.DANMAKU_DISPLAY, AnimePlayerPanel.DANMAKU_ACCESS)) AnimeDanmakuPane(section, controller, state,
                    onSection = ::show, modifier = Modifier.weight(1f, fill = false))
                else LazyColumn(Modifier.weight(1f, fill = false), state = sectionScroll,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (section == AnimePlayerPanel.GENERAL) 1.dp else 5.dp)) {
                    when (section) {
                        AnimePlayerPanel.GENERAL -> {
                            item { AnimeQuickPlaybackSpeed(playback.speed, visible, onChange = { speed ->
                                speedDraft = speed.toString()
                                controller.player.setSpeed(speed)
                            }, onMore = { show(AnimePlayerPanel.SPEED) }) }
                            if (episode != null) item {
                                AnimePlayerSettingLink(Icons.Outlined.SwapHoriz, "播放线路",
                                    state.selectedPlaybackOption?.sourceName?.takeIf { currentMedia != null }.orEmpty()) {
                                    pendingSourceEpisode = episode
                                    onDismiss()
                                }
                            }
                            item { AnimePlayerSettingLink(Icons.Outlined.HighQuality, "清晰度与画面",
                                currentMedia?.let { media ->
                                    state.playbackResolutions.firstOrNull { it.id == state.selectedResolutionId }?.label
                                        ?: media.videoHeight?.takeIf { it > 0 }?.let { "${it}p" }
                                }.orEmpty()) {
                                show(AnimePlayerPanel.VIDEO_SOURCE)
                            } }
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
                            item { AnimePlayerSettingLink(Icons.Outlined.ChatBubbleOutline, "弹幕", "") {
                                show(AnimePlayerPanel.DANMAKU)
                            } }
                            item { AnimePlayerSettingLink(Icons.Outlined.Tune, "更多播放设置", "增强、连播与下载") {
                                show(AnimePlayerPanel.ADVANCED)
                            } }
                        }
                        AnimePlayerPanel.ADVANCED -> {
                            item { AnimePlayerSettingsSection("观看习惯") }
                            item { AnimePlayerSettingLink(Icons.Outlined.Bedtime, "睡眠计时", when {
                                state.sleepAtEpisodeEnd -> "本集结束"
                                state.sleepDeadlineMs != null -> animeTime((state.sleepDeadlineMs - wallTimeMillis()).coerceAtLeast(0L))
                                else -> "关闭"
                            }) { show(AnimePlayerPanel.SLEEP) } }
                            item { AnimePlayerSettingLink(Icons.Outlined.SkipNext, "片头与片尾",
                                state.settings.skipOpeningSeconds.toString() + " / " + state.settings.skipEndingSeconds + " 秒") { show(AnimePlayerPanel.SKIP) } }
                            item { AnimePlayerSettingsSection("连续播放") }
                            item { SettingsToggle("自动播放下一集", state.settings.autoPlayNext, Icons.Outlined.PlayCircleOutline,
                                onCheckedChange = { enabled -> controller.updatePlaybackSettings { it.copy(autoPlayNext = enabled) } }) }
                            item { AnimePlayerSettingLink(Icons.Outlined.DataUsage, "播放准备",
                                "当前缓冲、下一集与增强片段") { show(AnimePlayerPanel.PREPARATION) } }
                            item { SettingsToggle("失败后尝试已确认的来源", state.settings.automaticSourceRecovery, Icons.Outlined.SwapHoriz,
                                onCheckedChange = { enabled -> controller.updatePlaybackSettings { it.copy(automaticSourceRecovery = enabled) } }) }
                            if (AnimePlayerCapability.BACKGROUND_AUDIO in capabilities) item {
                                SettingsToggle("后台播放声音", state.settings.backgroundAudio, Icons.Outlined.Headphones,
                                    onCheckedChange = { enabled -> controller.updatePlaybackSettings { it.copy(backgroundAudio = enabled) } })
                            }
                            if (AnimePlayerCapability.PICTURE_IN_PICTURE in capabilities) item {
                                val eligible = playback.pictureInPictureTarget(state, pictureInPictureFullscreen)
                                val label = when {
                                    eligible != null -> "进入画中画"
                                    playback.hasVideoTracks == false -> "画中画 · 当前线路只有音频"
                                    else -> "画中画 · 出画后可用"
                                }
                                CompactMenuAction(Icons.Outlined.PictureInPictureAlt, label, enabled = eligible != null,
                                    onClick = {
                                        val current = controller.player.state.value.pictureInPictureTarget(
                                            controller.state.value, pictureInPictureFullscreen)
                                        if (current != null && current == eligible) {
                                            pendingPictureInPicture = current
                                            onDismiss()
                                        }
                                    })
                            }
                            item { AnimePlayerSettingsSection("画质与读数") }
                            item { AnimePlayerSettingLink(Icons.Outlined.AutoAwesome, "画质增强与补帧", "") {
                                show(AnimePlayerPanel.VIDEO)
                            } }
                            item { SettingsToggle("本次显示播放读数", readingsVisible, Icons.Outlined.QueryStats,
                                onCheckedChange = onReadingsVisible) }
                            item { AnimePlayerSettingLink(Icons.Outlined.Tune, "选择播放读数", "") {
                                show(AnimePlayerPanel.VIDEO)
                                navigateEnhancement(EnhancementPage.DISPLAY)
                            } }
                            item { AnimePlayerSettingsSection("离线") }
                            item { CompactMenuAction(Icons.Outlined.Download, "下载当前集",
                                enabled = !state.resolving && state.selectedEpisodeId != null &&
                                    playback.phase in setOf(AnimePlayerPhase.READY, AnimePlayerPhase.ENDED),
                                onClick = { controller.downloadCurrent(); show(AnimePlayerPanel.DOWNLOADS) }) }
                            item { AnimePlayerSettingLink(Icons.Outlined.DownloadForOffline, "离线任务", "查看") {
                                show(AnimePlayerPanel.DOWNLOADS)
                            } }
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
                            if (currentMedia != null && state.playbackResolutions.isNotEmpty()) {
                                item { AnimeSectionTitle("本集片源清晰度") }
                                items(state.playbackResolutions, key = { "resolution:" + it.id }) { resolution ->
                                    AnimeSelection(resolution.label, selected = resolution.id == state.selectedResolutionId,
                                        onClick = { controller.selectResolution(resolution.id) })
                                }
                            }
                            val videoTracks = currentMedia?.tracks.orEmpty().filter { it.kind == AnimeTrackKind.VIDEO }
                            if (currentMedia != null && videoTracks.isNotEmpty() && AnimePlayerCapability.VIDEO_TRACKS in capabilities) {
                                item { AnimeSectionTitle("当前媒体的视频轨道") }
                                item { AnimeSelection("自动选择", selected = videoTracks.none { it.selected },
                                    onClick = { controller.player.selectTrack(AnimeTrackKind.VIDEO, null) }) }
                                items(videoTracks, key = { "video:" + it.id }) { track ->
                                    AnimeSelection(track.label, selected = track.selected,
                                        onClick = { controller.player.selectTrack(track.kind, track.id) })
                                }
                            }
                            if (currentMedia == null) item {
                                AnimeQuietLabel("开播后可查看本集的清晰度与轨道。")
                            } else if (state.playbackResolutions.isEmpty() && videoTracks.isEmpty()) item {
                                AnimeQuietLabel("当前来源没有列出可切换的清晰度。")
                            }
                            item {
                                val preferred = state.settings.preferredSourceHeight
                                AnimePlayerSettingLink(Icons.Outlined.HighQuality, "以后开播的清晰度偏好",
                                    if (preferred == 0) "自动" else "优先 ${preferred}p") {
                                    show(AnimePlayerPanel.VIDEO_PREFERENCE)
                                }
                            }
                            if (AnimePlayerCapability.VIDEO_SCALE in capabilities) item {
                                AnimeSectionTitle("画面适配")
                                AnimeChoiceStrip(listOf("适应", "拉伸", "填满"), playback.videoScale.ordinal,
                                    onSelect = { controller.player.setVideoScale(AnimeVideoScale.entries[it]) },
                                    modifier = Modifier.fillMaxWidth())
                            }
                            if (currentMedia != null) item {
                                MotionTextButton(onClick = { showVideoInfo = !showVideoInfo },
                                    modifier = Modifier.heightIn(min = 44.dp)) {
                                    Text(if (showVideoInfo) "收起解码信息" else "查看解码信息")
                                    Icon(if (showVideoInfo) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                        null, Modifier.size(17.dp))
                                }
                                if (showVideoInfo) {
                                    val width = currentMedia.videoWidth
                                    val height = currentMedia.videoHeight
                                    val dimensions = if (width != null && height != null && width > 0 && height > 0)
                                        "$width × $height" else "尚未报告"
                                    val decoded = if (currentMedia.preEnhanced == true) "增强文件解码" else "当前视频解码"
                                    AnimeQuietLabel("$decoded · $dimensions")
                                    val enhanced = currentMedia.videoEnhancement?.takeIf {
                                        (it.observation?.submittedFrames ?: 0L) > 0L &&
                                            (it.outputWidth ?: 0) > 0 && (it.outputHeight ?: 0) > 0
                                    }
                                    if (enhanced != null) AnimeQuietLabel("最近增强提交 · ${enhanced.outputWidth} × ${enhanced.outputHeight}")
                                }
                            }
                        }
                        AnimePlayerPanel.VIDEO_PREFERENCE -> {
                            item {
                                AnimeSectionTitle("以后开播优先选择")
                                val heights = listOf(0, 480, 720, 1080, 2160)
                                AnimeChoiceStrip(heights.map { if (it == 0) "自动" else "${it}p" },
                                    heights.indexOf(state.settings.preferredSourceHeight), onSelect = {
                                        val height = heights[it]
                                        controller.updatePlaybackSettings { it.copy(preferredSourceHeight = height) }
                                    }, modifier = Modifier.fillMaxWidth())
                                AnimeQuietLabel("重新开播时，从来源明确提供的选项中优先取不超过此高度的最高档；来源没有该档时使用可用选项。")
                                AnimeQuietLabel("1080p 通常为 1920×1080；2160p 通常为 3840×2160。p 表示画面高度，FPS 才表示每秒帧数。")
                                AnimeQuietLabel("这个偏好不会把 1080p 片源变成 4K；分辨率增强请在画质的输出倍率中设置。")
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
                        AnimePlayerPanel.PREPARATION -> {
                            item { AnimePlayerSettingLink(Icons.Outlined.DataUsage, "当前缓冲与恢复",
                                currentMedia?.bufferedAheadMs?.takeIf { it >= 0L }?.let { "已缓冲 ${animeTime(it)}" }
                                    ?: "查看真实缓冲与容量") { show(AnimePlayerPanel.BUFFER) } }
                            item { AnimePlayerSettingLink(Icons.Outlined.SkipNext, "下一集准备",
                                state.nextEpisodePreparation?.let(::animePreparationLabel)
                                    ?: if (state.settings.prepareNextEpisode) "播放接近结尾时准备" else "已关闭") {
                                show(AnimePlayerPanel.NEXT_PREPARATION)
                            } }
                            item { AnimePlayerSettingLink(Icons.Outlined.Layers, "提前增强后续片段",
                                currentMedia?.preparedVideoWindow?.let { "已备 ${animeTime(it.endMs - it.startMs)}" }
                                    ?: currentMedia?.preparedVideoIssue ?: "选择播放时处理方式") {
                                enhancementNavigation.reset(EnhancementPage.PREPARATION)
                                show(AnimePlayerPanel.VIDEO)
                            } }
                            item { AnimePlayerSettingLink(Icons.Outlined.Storage, "缓存管理", "原片、增强结果与离线下载") {
                                pendingStorage = true; onDismiss()
                            } }
                            item { AnimeSectionTitle("在线增强窗口") }
                            item { AnimeTextField(onlineDraft, { onlineDraft = it; error = null }, "每段目标时长（秒）",
                                emptyHint = "自动 · ${preparationBudget.onlineWindowMs / 1000L}") }
                            item { AnimeQuietLabel("清空使用自动值。实际处理受所选模型、输出尺寸和内核能力限制，原因显示在增强状态中。") }
                            item { MotionButton(onClick = {
                                val value = onlineDraft.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                                if (onlineDraft.isNotBlank() && (value == null || value <= 0)) error = "时长须为正整数，或清空使用自动。"
                                else { controller.updatePlaybackSettings { it.copy(onlinePreparationSeconds = value) }; error = null }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存窗口时长") } }
                        }
                        AnimePlayerPanel.NEXT_PREPARATION -> {
                            item { SettingsToggle("提前准备下一集", state.settings.prepareNextEpisode, Icons.Outlined.DownloadForOffline,
                                onCheckedChange = { enabled -> controller.updatePlaybackSettings { it.copy(prepareNextEpisode = enabled) } }) }
                            state.nextEpisodePreparation?.let { preparation -> item {
                                AnimeSectionTitle("当前准备", state.detail?.episodes?.firstOrNull {
                                    it.id == preparation.episodeId }?.let(::animeEpisodeLabel))
                                AnimeQuietLabel(animePreparationLabel(preparation))
                                AnimeQuietLabel("从 ${animeTime(preparation.positionMs)} 准备 · 原片 ${animeBytes(preparation.cachedBytes)} · 连续时长 ${
                                    preparation.bufferedDurationMs?.let(::animeTime) ?: "—"}")
                                preparation.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            } }
                            item { AnimeTextField(leadDraft, { leadDraft = it; error = null }, "片尾前多久开始（秒）",
                                emptyHint = "自动 · ${preparationBudget.nextEpisodeLeadMs / 1000L}") }
                            item { AnimeTextField(nextSecondsDraft, { nextSecondsDraft = it; error = null }, "原片目标时长（秒）",
                                emptyHint = "自动 · ${preparationBudget.nextEpisodeDurationMs / 1000L}") }
                            item { AnimeTextField(nextCacheDraft, { nextCacheDraft = it; error = null }, "原片缓存容量（MiB）",
                                emptyHint = "自动 · ${preparationBudget.nextEpisodeBytes / (1024L * 1024L)}") }
                            item { SettingsToggle("提前解码起播首帧", state.settings.predecodeNextEpisode, Icons.Outlined.SlowMotionVideo,
                                onCheckedChange = { enabled -> controller.updatePlaybackSettings { it.copy(predecodeNextEpisode = enabled) } }) }
                            item { AnimeQuietLabel("清空使用自动值。预读保存原片，预解码准备真实起播帧；会从下一集的续看位置准备，当前播放优先。") }
                            item { MotionButton(onClick = {
                                fun optional(raw: String) = raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                                val lead = optional(leadDraft); val seconds = optional(nextSecondsDraft); val capacity = optional(nextCacheDraft)
                                val invalid = listOf(leadDraft to lead, nextSecondsDraft to seconds, nextCacheDraft to capacity)
                                    .any { (raw, value) -> raw.isNotBlank() && (value == null || value < 0) }
                                if (invalid) error = "请输入非负整数，或清空使用自动。"
                                else { controller.updatePlaybackSettings { it.copy(nextEpisodeLeadSeconds = lead,
                                    nextEpisodeBufferSeconds = seconds, nextEpisodeCacheMiB = capacity) }; error = null }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存下一集准备") } }
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
                                val intentFirstFrame = state.playbackIntentFirstFrameMs.takeIf { currentMedia != null }
                                val buffered = currentMedia?.bufferedAheadMs?.takeIf { it >= 0 }?.let { animeTime(it) }
                                val speed = currentMedia?.downloadBytesPerSecond?.takeIf { it >= 0 }?.let(::animeSourceRate)
                                AnimeQuietLabel("打开至首帧 ${firstFrame ?: "—"} · 前方已缓冲 ${buffered ?: "—"}")
                                intentFirstFrame?.let { AnimeQuietLabel("发起播放至首帧 $it ms") }
                                AnimeQuietLabel("实播传输速率 · ${speed ?: "—"}")
                                AnimeQuietLabel("前方缓冲目标 · ${bufferSeconds(state.settings.bufferMaxMs)} 秒")
                                AnimeQuietLabel(if (state.settings.bufferRefillNearTarget)
                                    "接近目标时及时补充，尽量稳定前方可播时长；容量、网速或片尾可能限制实际时长。"
                                    else "达到目标后暂停读取，降至 ${bufferSeconds(state.settings.bufferMinMs)} 秒再补充；容量或片尾可能限制实际时长。")
                                currentMedia?.bufferMetrics?.let { metrics ->
                                    AnimeQuietLabel("已分配缓冲 · ${metrics.allocatedBytes?.let(::animeBytes) ?: "—"}")
                                    if (metrics.forwardBytes != null || metrics.backBytes != null)
                                        AnimeQuietLabel("前方 ${metrics.forwardBytes?.let(::animeBytes) ?: "—"} · 回看 ${metrics.backBytes?.let(::animeBytes) ?: "—"}")
                                    if (metrics.targetForwardBytes != null || metrics.targetBackBytes != null)
                                        AnimeQuietLabel("内核容量目标 · 前方 ${metrics.targetForwardBytes?.let(::animeBytes) ?: "—"} · 回看 ${metrics.targetBackBytes?.let(::animeBytes) ?: "—"}")
                                    metrics.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                if (episode != null) MotionTextButton(onClick = {
                                    pendingSourceEpisode = episode
                                    onDismiss()
                                }, modifier = Modifier.heightIn(min = 44.dp)) { Text("查看本集线路") }
                            }
                            val configureBuffer = AnimePlayerCapability.BUFFER_CONTROL in capabilities ||
                                currentMedia == null || playback.phase in setOf(AnimePlayerPhase.OPENING, AnimePlayerPhase.ERROR)
                            if (configureBuffer) {
                                item { AnimeQuietLabel("缓冲目标会保存，实际缓存量随内核、媒体与可用预算变化。") }
                                item { SettingsToggle("及时补充", state.settings.bufferRefillNearTarget, Icons.Outlined.Sync,
                                    onCheckedChange = { value -> controller.updatePlaybackSettings {
                                        it.copy(bufferRefillNearTarget = value)
                                    } }) }
                                if (!state.settings.bufferRefillNearTarget)
                                    item { AnimeTextField(minimumDraft, { minimumDraft = it; error = null }, "继续读取的补充阈值（秒）") }
                                item { AnimeTextField(maximumDraft, { maximumDraft = it; error = null }, "前方缓冲目标上限（秒）") }
                                item { AnimeTextField(startDraft, { startDraft = it; error = null }, "起播需要的缓冲（秒）") }
                                item { AnimeTextField(rebufferDraft, { rebufferDraft = it; error = null }, "卡顿后恢复需要（秒）") }
                                item { AnimeTextField(forwardDraft, { forwardDraft = it; error = null }, "前方缓冲容量（MiB）",
                                    emptyHint = "自动 · ${preparationBudget.forwardBytes / (1024L * 1024L)}") }
                                item { AnimeTextField(backDraft, { backDraft = it; error = null }, "回看缓冲容量（MiB）",
                                    emptyHint = "自动 · ${preparationBudget.backBytes / (1024L * 1024L)}") }
                                item { AnimeQuietLabel("容量清空使用自动值。支持的参数会直接更新当前播放；内核限制显示在本集诊断中。") }
                            } else item { AnimeQuietLabel("当前内核自行管理缓冲，可调整单线路重试次数。") }
                            item { AnimeTextField(retryDraft, { retryDraft = it; error = null }, "单线路重试次数，0 关闭自动恢复") }
                            item { MotionButton(onClick = {
                                val minimum = bufferMillis(minimumDraft); val maximum = bufferMillis(maximumDraft)
                                val start = bufferMillis(startDraft); val rebuffer = bufferMillis(rebufferDraft)
                                val retries = retryDraft.trim().toIntOrNull()
                                val forward = forwardDraft.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                                val back = backDraft.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                                if (minimum == null || maximum == null || start == null || rebuffer == null || retries == null ||
                                    retries < 0 || maximum <= 0 || maximum < minimum || minimum < start || minimum < rebuffer ||
                                    forwardDraft.isNotBlank() && (forward == null || forward <= 0) ||
                                    backDraft.isNotBlank() && (back == null || back < 0))
                                    error = "最大缓冲 ≥ 最小缓冲 ≥ 起播及恢复缓冲，恢复次数不能为负。"
                                else { controller.updatePlaybackSettings { it.copy(bufferMinMs = minimum,
                                    bufferMaxMs = maximum, bufferStartMs = start, bufferRebufferMs = rebuffer,
                                    bufferForwardMiB = forward, bufferBackMiB = back,
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
private fun AnimeQuickPlaybackSpeed(speed: Float, active: Boolean, onChange: (Float) -> Unit, onMore: () -> Unit) {
    val presets = remember(speed) {
        val common = listOf(1f, 1.25f, 1.5f, 2f)
        if (speed in common) common else (common + speed).sorted()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MotionIconButton(onClick = onMore, enabled = active, modifier = Modifier.size(44.dp),
            tooltip = "更多倍速与长按设置") {
            Icon(Icons.Outlined.Speed, "更多倍速与长按设置", Modifier.size(19.dp))
        }
        AnimeChoiceStrip(presets.map(::animeSpeedLabel), presets.indexOf(speed), onSelect = { onChange(presets[it]) },
            modifier = Modifier.weight(1f).semantics { contentDescription = "播放倍速" }, active = active)
    }
}

@Composable
private fun AnimePlayerSettingsSection(title: String) {
    Text(title, Modifier.padding(start = 8.dp, top = 9.dp, bottom = 2.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AnimePlayerSettingLink(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val window = LocalMangaWindow.current
    val inlineValue = value.isNotBlank() && value.length <= 14 && window.width >= 264.dp * window.fontScale
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .motionPress(interaction, pressedScale = .98f).heightIn(min = 46.dp)
        .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            if (!inlineValue && value.isNotBlank()) Text(value, style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (inlineValue) Text(value, Modifier.widthIn(max = window.width * .38f),
            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
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
