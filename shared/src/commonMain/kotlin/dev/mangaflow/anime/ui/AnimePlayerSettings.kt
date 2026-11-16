package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.anime.player.*
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*
import kotlin.math.roundToInt

internal enum class AnimePlayerPanel {
    GENERAL, VIDEO, AUDIO, SUBTITLES, SPEED, SLEEP, SKIP, BUFFER, DOWNLOADS,
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
    onPlayDownload: (String) -> Unit,
) {
    var section by remember { mutableStateOf(initialSection) }
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
    LaunchedEffect(visible, initialSection) {
        if (visible) {
            section = initialSection; error = null
            speedDraft = playback.speed.toString(); holdDraft = state.settings.longPressSpeed.toString()
            openingDraft = state.settings.skipOpeningSeconds.toString(); endingDraft = state.settings.skipEndingSeconds.toString()
            minimumDraft = bufferSeconds(state.settings.bufferMinMs); maximumDraft = bufferSeconds(state.settings.bufferMaxMs)
            startDraft = bufferSeconds(state.settings.bufferStartMs); rebufferDraft = bufferSeconds(state.settings.bufferRebufferMs)
            retryDraft = state.settings.automaticRecoveryAttempts.toString(); minutesDraft = ""
        }
    }
    val capabilities = playback.capabilities
    val sectionScrolls = remember { mutableMapOf<AnimePlayerPanel, LazyListState>() }
    val sectionScroll = remember(section) { sectionScrolls.getOrPut(section) { LazyListState() } }
    fun show(next: AnimePlayerPanel) { section = next; error = null }
    MotionDialog(visible, onDismissRequest = onDismiss, maximumWidth = 540.dp, onClosed = onClosed) {
        LiquidGlassSurface(backdrop, role = GlassSurfaceRole.PANEL, cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)) {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (section != AnimePlayerPanel.GENERAL) MotionIconButton(onClick = {
                        show(if (section in setOf(AnimePlayerPanel.DANMAKU_SEARCH, AnimePlayerPanel.DANMAKU_DISPLAY,
                            AnimePlayerPanel.DANMAKU_ACCESS)) AnimePlayerPanel.DANMAKU else AnimePlayerPanel.GENERAL)
                    },
                        modifier = Modifier.size(44.dp), tooltip = "返回播放设置") {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                    }
                    Text(when (section) {
                        AnimePlayerPanel.GENERAL -> "播放设置"
                        AnimePlayerPanel.VIDEO -> "画面与清晰度"
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
                if (section == AnimePlayerPanel.DOWNLOADS) AnimeDownloadsContent(controller, state, backdrop,
                    onPlay = { onDismiss(); onPlayDownload(it) }, modifier = Modifier.weight(1f, fill = false))
                else if (section in setOf(AnimePlayerPanel.DANMAKU, AnimePlayerPanel.DANMAKU_SEARCH,
                    AnimePlayerPanel.DANMAKU_DISPLAY, AnimePlayerPanel.DANMAKU_ACCESS)) AnimeDanmakuPane(section, controller, state,
                    onSection = ::show, modifier = Modifier.weight(1f, fill = false))
                else LazyColumn(Modifier.weight(1f, fill = false), state = sectionScroll, contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (section) {
                        AnimePlayerPanel.GENERAL -> {
                            item { AnimePlayerSettingLink(Icons.Outlined.AspectRatio, "画面与清晰度",
                                animeScaleLabel(playback.videoScale)) { show(AnimePlayerPanel.VIDEO) } }
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
                            item { AnimePlayerSettingLink(Icons.Outlined.Speed, "速度", animeSpeedLabel(playback.speed)) { show(AnimePlayerPanel.SPEED) } }
                            item { AnimePlayerSettingLink(Icons.Outlined.Bedtime, "睡眠计时", when {
                                state.sleepAtEpisodeEnd -> "本集结束"
                                state.sleepDeadlineMs != null -> animeTime((state.sleepDeadlineMs - wallTimeMillis()).coerceAtLeast(0L))
                                else -> "关闭"
                            }) { show(AnimePlayerPanel.SLEEP) } }
                            item { AnimePlayerSettingLink(Icons.Outlined.SkipNext, "片头与片尾",
                                state.settings.skipOpeningSeconds.toString() + " / " + state.settings.skipEndingSeconds + " 秒") { show(AnimePlayerPanel.SKIP) } }
                            item { SettingsToggle("自动播放下一集", state.settings.autoPlayNext, Icons.Outlined.PlayCircleOutline,
                                onCheckedChange = { controller.updatePlaybackSettings(state.settings.copy(autoPlayNext = it)) }) }
                            item { SettingsToggle("提前准备下一集", state.settings.prepareNextEpisode, Icons.Outlined.DownloadForOffline,
                                onCheckedChange = { controller.updatePlaybackSettings(state.settings.copy(prepareNextEpisode = it)) }) }
                            item { SettingsToggle("失败后尝试已确认的来源", state.settings.automaticSourceRecovery, Icons.Outlined.SwapHoriz,
                                onCheckedChange = { controller.updatePlaybackSettings(state.settings.copy(automaticSourceRecovery = it)) }) }
                            if (AnimePlayerCapability.BACKGROUND_AUDIO in capabilities) item {
                                SettingsToggle("后台播放声音", state.settings.backgroundAudio, Icons.Outlined.Headphones,
                                    onCheckedChange = { controller.updatePlaybackSettings(state.settings.copy(backgroundAudio = it)) })
                            }
                            if (AnimePlayerCapability.PICTURE_IN_PICTURE in capabilities) item {
                                CompactMenuAction(Icons.Outlined.PictureInPictureAlt, "进入画中画", onClick = {
                                    controller.player.enterPictureInPicture(); onDismiss()
                                })
                            }
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
                            if (AnimePlayerCapability.VIDEO_SCALE in capabilities) item {
                                MotionTabs(playback.videoScale.ordinal, listOf("适应", "拉伸", "填满"),
                                    onSelect = { controller.player.setVideoScale(AnimeVideoScale.entries[it]) },
                                    modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp, treatment = MotionTabTreatment.Soft)
                            }
                            if (playback.videoWidth != null && playback.videoHeight != null) item {
                                AnimeQuietLabel("视频 " + playback.videoWidth + " × " + playback.videoHeight)
                            }
                            if (AnimePlayerCapability.VIDEO_ENHANCEMENT in capabilities ||
                                state.settings.videoEnhancement != AnimeVideoEnhancementMode.OFF) {
                                item {
                                    AnimeSectionTitle("Anime4K", animeEnhancementLabel(playback.videoEnhancement.phase))
                                    MotionTabs(state.settings.videoEnhancement.ordinal, listOf("原画", "轻量", "精细"),
                                        onSelect = { controller.updatePlaybackSettings(state.settings.copy(
                                            videoEnhancement = AnimeVideoEnhancementMode.entries[it])) },
                                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Anime4K 画质增强" },
                                        controlHeight = 44.dp, treatment = MotionTabTreatment.Soft)
                                }
                                item {
                                    AnimeQuietLabel(when (state.settings.videoEnhancement) {
                                        AnimeVideoEnhancementMode.OFF -> "本机 GPU 实时增强动漫画面，不改原视频。"
                                        AnimeVideoEnhancementMode.FAST -> "轻量处理线条与放大；优先减轻 GPU 负担。"
                                        AnimeVideoEnhancementMode.QUALITY -> "更细致地处理线条与放大；GPU 负担更高。"
                                    })
                                }
                                val enhancement = playback.videoEnhancement
                                enhancement.detail?.takeIf(String::isNotBlank)?.let { detail ->
                                    item { AnimeQuietLabel(detail) }
                                }
                                if (enhancement.inputWidth != null && enhancement.inputHeight != null &&
                                    enhancement.outputWidth != null && enhancement.outputHeight != null) item {
                                    AnimeQuietLabel("处理 " + enhancement.inputWidth + " × " + enhancement.inputHeight +
                                        " → " + enhancement.outputWidth + " × " + enhancement.outputHeight)
                                }
                            }
                            if (state.playbackResolutions.isNotEmpty()) {
                                item { AnimeSectionTitle("来源画质") }
                                items(state.playbackResolutions, key = { "resolution:" + it.id }) { resolution ->
                                    AnimeSelection(resolution.label, selected = resolution.id == state.selectedResolutionId,
                                        onClick = { controller.selectResolution(resolution.id) })
                                }
                            }
                            val videoTracks = playback.tracks.filter { it.kind == AnimeTrackKind.VIDEO }
                            if (AnimePlayerCapability.VIDEO_TRACKS in capabilities) {
                                item { AnimeSectionTitle("视频轨道") }
                                item { AnimeSelection("自动选择", selected = videoTracks.none { it.selected },
                                    onClick = { controller.player.selectTrack(AnimeTrackKind.VIDEO, null) }) }
                                items(videoTracks, key = { "video:" + it.id }) { track ->
                                    AnimeSelection(track.label, selected = track.selected,
                                        onClick = { controller.player.selectTrack(track.kind, track.id) })
                                }
                            }
                            if (state.playbackResolutions.isEmpty() && videoTracks.isEmpty()) item {
                                AnimeQuietLabel("片源没有提供其他清晰度。")
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
                                MotionTabs(speeds.indexOf(playback.speed), speeds.map(::animeSpeedLabel), onSelect = {
                                        val speed = speeds[it]
                                        speedDraft = speed.toString(); controller.player.setSpeed(speed)
                                    }, modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp,
                                    treatment = MotionTabTreatment.Soft)
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
                                    controller.updatePlaybackSettings(state.settings.copy(longPressSpeed = hold)); error = null
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
                                else { controller.updatePlaybackSettings(state.settings.copy(skipOpeningSeconds = opening,
                                    skipEndingSeconds = ending)); error = null }
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("应用跳过设置") } }
                        }
                        AnimePlayerPanel.BUFFER -> {
                            if (AnimePlayerCapability.BUFFER_CONTROL in capabilities) {
                                item { AnimeQuietLabel("秒数是播放器的准备目标，实际缓存量由当前资源与可用预算决定。") }
                                item { AnimeTextField(minimumDraft, { minimumDraft = it; error = null }, "最小缓冲（秒）") }
                                item { AnimeTextField(maximumDraft, { maximumDraft = it; error = null }, "最大缓冲（秒）") }
                                item { AnimeTextField(startDraft, { startDraft = it; error = null }, "起播缓冲（秒）") }
                                item { AnimeTextField(rebufferDraft, { rebufferDraft = it; error = null }, "恢复缓冲（秒）") }
                            } else item { AnimeQuietLabel("当前内核自行管理缓冲，可调整自动恢复次数。") }
                            item { AnimeTextField(retryDraft, { retryDraft = it; error = null }, "自动恢复次数，0 为关闭") }
                            item { MotionButton(onClick = {
                                val minimum = bufferMillis(minimumDraft); val maximum = bufferMillis(maximumDraft)
                                val start = bufferMillis(startDraft); val rebuffer = bufferMillis(rebufferDraft)
                                val retries = retryDraft.trim().toIntOrNull()
                                if (minimum == null || maximum == null || start == null || rebuffer == null || retries == null ||
                                    retries < 0 || maximum <= 0 || maximum < minimum || minimum < start || minimum < rebuffer)
                                    error = "最大缓冲 ≥ 最小缓冲 ≥ 起播及恢复缓冲，恢复次数不能为负。"
                                else { controller.updatePlaybackSettings(state.settings.copy(bufferMinMs = minimum,
                                    bufferMaxMs = maximum, bufferStartMs = start, bufferRebufferMs = rebuffer,
                                    automaticRecoveryAttempts = retries)); error = null }
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
}

@Composable
private fun AnimePlayerSettingLink(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    CompactMenuAction(icon, title + " · " + value, onClick = onClick)
}

internal fun animeScaleLabel(scale: AnimeVideoScale): String = when (scale) {
    AnimeVideoScale.FIT -> "适应"
    AnimeVideoScale.FILL -> "拉伸"
    AnimeVideoScale.ZOOM -> "填满"
}

private fun animeEnhancementLabel(phase: AnimeVideoEnhancementPhase): String = when (phase) {
    AnimeVideoEnhancementPhase.OFF -> "原画"
    AnimeVideoEnhancementPhase.WAITING -> "等待画面"
    AnimeVideoEnhancementPhase.CONFIGURED -> "已配置"
    AnimeVideoEnhancementPhase.ACTIVE -> "已在处理画面"
    AnimeVideoEnhancementPhase.UNAVAILABLE -> "当前保持原画"
    AnimeVideoEnhancementPhase.FAILED -> "增强失败"
}

internal fun animeSpeedLabel(speed: Float): String =
    (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()) + "×"

private fun bufferSeconds(milliseconds: Int): String = if (milliseconds % 1000 == 0) (milliseconds / 1000).toString()
    else (milliseconds / 1000.0).toString()

private fun bufferMillis(seconds: String): Int? = seconds.trim().toDoubleOrNull()?.takeIf {
    it.isFinite() && it >= 0 && it * 1000.0 <= Int.MAX_VALUE
}?.let { (it * 1000.0).toInt() }
