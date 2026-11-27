package dev.mangaflow.anime.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.anime.player.AnimePlayerPhase
import dev.mangaflow.anime.AnimeNextEpisodePreparation
import dev.mangaflow.anime.player.ANIME_ENHANCED_FRAME_FRESH_MS
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeEnhancementProtectionCause
import dev.mangaflow.anime.player.AnimeVideoEnhancementPhase
import dev.mangaflow.enhancement.EnhancementCenter
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.EnhancementMetric
import dev.mangaflow.enhancement.EnhancementPolicy
import dev.mangaflow.enhancement.QualityTier
import dev.mangaflow.enhancement.animeEnhancementSessionKey
import dev.mangaflow.enhancement.engine.EnhancementEngineIds
import dev.mangaflow.enhancement.engine.EngineReadiness
import dev.mangaflow.enhancement.ui.EnhancementReadout
import dev.mangaflow.enhancement.ui.EnhancementReadouts
import dev.mangaflow.enhancement.ui.enhancementResourceReadouts
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.platform.monotonicTimeMillis
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.DesktopIconTooltip
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.ui.motion.MotionIconButton

private fun videoDimensions(width: Int?, height: Int?): String? =
    if (width != null && width > 0 && height != null && height > 0) "$width×$height" else null

internal data class AnimePictureSubmission(val dimensions: String?, val prepared: Boolean)

/** Only current use of a prepared frame or a fresh live submission counts as an enhanced picture. */
internal fun AnimePlayerState.currentPictureSubmission(now: Long = monotonicTimeMillis()): AnimePictureSubmission? {
    if (preEnhanced || !isPlaying || playbackSuppressed || firstFrameRendered == false ||
        hasVideoTracks == false || videoEnhancement.renderedOriginalFallback) return null
    val effect = videoEnhancement
    val submitted = effect.observation?.takeIf {
        effect.phase == AnimeVideoEnhancementPhase.ACTIVE && it.recentlySubmitting && it.submittedFrames > 0L
    }?.lastSubmittedAtMillis?.takeIf { now - it in 0L until ANIME_ENHANCED_FRAME_FRESH_MS }
    val window = preparedVideoWindow
    if (window != null && window.frames > 0 && window.framesUsed > 0L && positionMs in window.startMs until window.endMs &&
        window.lastUsedAt > 0L && now - window.lastUsedAt in 0L..1_000L &&
        (submitted == null || window.lastUsedAt > submitted)) {
        return AnimePictureSubmission(videoDimensions(window.outputWidth, window.outputHeight), prepared = true)
    }
    return if (submitted != null) AnimePictureSubmission(videoDimensions(effect.outputWidth, effect.outputHeight),
        prepared = false) else null
}

/** The quiet reading uses only native values; a source rate is not a measured output rate. */
@Composable
internal fun AnimeInlineVideoQuality(playback: AnimePlayerState, modifier: Modifier = Modifier) {
    val context = LocalMangaContext.current
    val center = remember(context) { EnhancementCenter.get(context) }
    val defaults by center.settings.state.collectAsStateWithLifecycle()
    val sessions by center.settings.sessions.collectAsStateWithLifecycle()
    val policy = sessions[animeEnhancementSessionKey(playback)]?.takeIf { it.content == EnhancementContent.ANIME }?.policy
        ?: defaults.getValue(EnhancementContent.ANIME)
    val requested = policy.realtimeEnhancementEnabled && policy.tier != QualityTier.ORIGINAL
    val input = videoDimensions(playback.videoWidth, playback.videoHeight)
    val submission = if (requested) playback.currentPictureSubmission() else null
    val enhanced = submission?.dimensions
    val dimensions = when {
        submission != null -> listOfNotNull(if (submission.prepared) "预处理帧" else "增强帧", enhanced).joinToString(" ")
        input == null -> null
        playback.preEnhanced -> "增强文件 $input"
        else -> "片源 $input"
    }
    val effect = playback.videoEnhancement
    val protection = playback.currentEnhancementProtection()
    val status = when {
        !requested || submission != null || playback.preEnhanced || platformKind != PlatformKind.ANDROID -> null
        protection?.picture == true && protection.cause != AnimeEnhancementProtectionCause.GPU_BUDGET -> null
        effect.renderedOriginalFallback -> "已回到原画"
        else -> when (effect.phase) {
            AnimeVideoEnhancementPhase.OFF -> "增强未运行"
            AnimeVideoEnhancementPhase.UNAVAILABLE -> "增强不可用"
            AnimeVideoEnhancementPhase.FAILED -> "增强异常"
            else -> "等待增强帧"
        }
    }
    val presented = playback.presentedFps?.takeIf { it.isFinite() && it >= 0.0 }
    val output = playback.frameMetrics?.outputFps?.takeIf { it.isFinite() && it >= 0.0 }
    val source = playback.sourceFps?.takeIf { it.isFinite() && it > 0.0 }
    val rate = when {
        presented != null -> "呈现 ${formatDecimal(presented, 0)} fps"
        output != null -> "输出 ${formatDecimal(output, 0)} fps"
        source != null -> "源 ${formatDecimal(source, 0)} fps"
        else -> null
    }
    if (dimensions == null && rate == null && status == null && protection == null) return
    val caption = listOfNotNull(protection?.reasonLabel, status, dimensions, rate).joinToString(" · ")
    val dimensionDescription = when {
        enhanced != null -> "已观测增强帧尺寸 ${enhanced.replace("×", " 乘 ")}，不是屏幕分辨率或呈现回执"
        submission != null -> "播放器已提交当前位置的增强帧"
        input == null -> null
        playback.preEnhanced -> "增强文件的解码尺寸 ${input.replace("×", " 乘 ")}"
        else -> "片源输入分辨率 ${input.replace("×", " 乘 ")}"
    }
    val description = listOfNotNull(protection?.let { it.reasonLabel + "，" + it.actionLabel },
        status, dimensionDescription, when {
        presented != null -> "播放器实际呈现 ${formatDecimal(presented, 0)} 帧每秒"
        output != null -> "视频输出 ${formatDecimal(output, 0)} 帧每秒，不是屏幕刷新率"
        source != null -> "片源标称 ${formatDecimal(source, 1)} 帧每秒，未测得当前输出"
        else -> null
    }).joinToString("，")
    AnimeQuietVideoText(caption, description, modifier, alpha = .56f, maximumLines = 1)
}

/** Desktop samples decoder input and positively identified DirectML filter output, not renderer/window pixels. */
@Composable
internal fun AnimeDesktopVideoResolution(playback: AnimePlayerState, modifier: Modifier = Modifier) {
    val sample = playback.nativeVideoStatistics ?: return
    val input = sample.decoderInputSize?.let { videoDimensions(it.width, it.height) } ?: return
    val effect = playback.videoEnhancement
    val output = sample.confirmedSpatialOutputSize?.takeIf {
        !playback.preEnhanced && playback.isPlaying && effect.phase == AnimeVideoEnhancementPhase.ACTIVE &&
            !effect.renderedOriginalFallback && effect.outputWidth == it.width && effect.outputHeight == it.height &&
            monotonicTimeMillis() - sample.sampledAtMillis in 0L until 1_800L
    }?.let { videoDimensions(it.width, it.height) }
    val source = if (playback.preEnhanced) "增强文件" else "片源"
    val caption = if (output == null) "$source $input" else "$source $input · 增强输出 $output"
    val description = buildString {
        append(if (playback.preEnhanced) "增强文件的实际解码尺寸 " else "片源的实际解码尺寸 ")
        append(input.replace("×", " 乘 "))
        if (output != null) {
            append("；已核对的 DirectML 视频滤镜输出 ")
            append(output.replace("×", " 乘 "))
            append("，不是屏幕分辨率或呈现回执")
        }
    }
    AnimeQuietVideoText(caption, description, modifier, alpha = .48f, maximumLines = 2)
}

@Composable
private fun AnimeQuietVideoText(caption: String, description: String, modifier: Modifier,
    alpha: Float, maximumLines: Int) {
    Text(
        text = caption,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        color = Color.White.copy(alpha = alpha),
        maxLines = maximumLines,
        textAlign = if (maximumLines > 1) TextAlign.End else null,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFeatureSettings = "tnum",
            shadow = Shadow(Color.Black.copy(alpha = .52f), Offset(0f, 1f), 2f),
        ),
    )
}

/** Selected readings stay on the video without consuming the episode/comment pane's height. */
@Composable
internal fun AnimePlaybackStatus(
    playback: AnimePlayerState?,
    quiet: Boolean,
    observing: Boolean,
    modifier: Modifier = Modifier,
    nextPreparation: AnimeNextEpisodePreparation? = null,
    nextPreparationEnabled: Boolean = true,
    interactive: Boolean = false,
    maximumHeight: Dp = 160.dp,
    onInteraction: () -> Unit = {},
    onHoldControls: (Boolean) -> Unit = {},
) {
    val context = LocalMangaContext.current
    val center = remember(context) { EnhancementCenter.get(context) }
    val preferences by center.settings.visibleMetrics.collectAsStateWithLifecycle()
    val defaults by center.settings.state.collectAsStateWithLifecycle()
    val sessions by center.settings.sessions.collectAsStateWithLifecycle()
    val telemetry by center.telemetry.state.collectAsStateWithLifecycle()
    val inventory by center.inventory.collectAsStateWithLifecycle()
    val metrics = preferences.getValue(EnhancementContent.ANIME)
    val current = playback?.takeIf { it.assetKey != null &&
        it.phase !in setOf(AnimePlayerPhase.IDLE, AnimePlayerPhase.RELEASED) }
    val key = current?.let(::animeEnhancementSessionKey)
    val policy = key?.let { sessions[it]?.takeIf { value -> value.content == EnhancementContent.ANIME }?.policy }
        ?: defaults.getValue(EnhancementContent.ANIME)
    val reason = key?.let { telemetry.sessions[it]?.takeIf { value -> value.content == EnhancementContent.ANIME }?.reason }
    val liveMotionReadiness = if (platformKind == PlatformKind.DESKTOP) inventory.engines.firstOrNull {
        it.id == EnhancementEngineIds.DESKTOP_REALTIME_RIFE
    }?.readiness else null
    val readouts = animeEnhancementReadouts(current, metrics, policy, reason, nextPreparation,
        nextPreparationEnabled, liveMotionReadiness) +
        enhancementResourceReadouts(metrics, center.resources, observing)
    if (readouts.isEmpty()) return
    var details by rememberSaveable(playback?.episodeId) { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val summaryScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    val canExpand = maximumHeight >= 76.dp
    val expanded = details && canExpand && !quiet
    val desktop = platformKind == PlatformKind.DESKTOP
    val holdControls by rememberUpdatedState(onHoldControls)
    DisposableEffect(interactive, expanded, focused, summaryScroll.isScrollInProgress, detailScroll.isScrollInProgress) {
        holdControls(interactive && (expanded || focused || summaryScroll.isScrollInProgress || detailScroll.isScrollInProgress))
        onDispose { holdControls(false) }
    }
    AnimePlaybackSurface(null, modifier.heightIn(max = maximumHeight).onFocusChanged { focused = it.hasFocus },
        panel = expanded, cornerRadius = 15.dp) {
        Column {
            val rowWidth = if (quiet) Modifier else Modifier.fillMaxWidth()
            Row(rowWidth.heightIn(min = if (quiet) 26.dp else 44.dp).padding(start = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                AnimeGlassIcon(Icons.Outlined.QueryStats, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (expanded && desktop) AnimeGlassLabel("播放读数 · ${readouts.size} 项", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium)
                else AnimeGlassLabel(readouts.joinToString("  ·  ") { it.value },
                    Modifier.weight(1f, fill = !quiet).horizontalScroll(summaryScroll, enabled = interactive)
                        .padding(end = 8.dp, top = 3.dp, bottom = 3.dp)
                        .semantics { contentDescription = readouts.joinToString("；") { it.description } },
                    maxLines = 1, color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"))
                if (!quiet && canExpand) MotionIconButton(onClick = { details = !details; onInteraction() },
                    modifier = Modifier.size(44.dp), enabled = interactive,
                    tooltip = if (expanded) "收起观看读数" else "展开观看读数") {
                    AnimeGlassIcon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        if (expanded) "收起观看读数" else "展开 ${readouts.size} 项观看读数", Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (expanded) {
                val bounds = Modifier.fillMaxWidth().heightIn(max = (maximumHeight - 44.dp).coerceAtLeast(1.dp))
                if (desktop) Box(bounds) {
                    DesktopPlaybackReadings(readouts, Modifier.fillMaxWidth().verticalScroll(detailScroll, enabled = interactive)
                        .padding(start = 10.dp, end = 15.dp, bottom = 8.dp), interactive)
                    DesktopVerticalScrollbar(detailScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 3.dp))
                } else EnhancementReadouts(readouts, quiet = false,
                    modifier = bounds.verticalScroll(detailScroll, enabled = interactive)
                        .padding(start = 8.dp, end = 8.dp, bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun DesktopPlaybackReadings(readouts: List<EnhancementReadout>, modifier: Modifier, interactive: Boolean) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val inline = maxWidth >= 300.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            readouts.forEach { reading ->
                val interaction = remember(reading.metric) { MutableInteractionSource() }
                Box(Modifier.fillMaxWidth().hoverable(interaction, enabled = interactive)
                    .semantics(mergeDescendants = true) { contentDescription = reading.description }) {
                    val label = reading.metric.label(EnhancementContent.ANIME)
                    val digits = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
                    if (inline) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(label, Modifier.weight(.92f).alignByBaseline(),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        Text(reading.value, Modifier.weight(1.4f).alignByBaseline(), textAlign = TextAlign.End, style = digits)
                    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        Text(reading.value, style = digits)
                    }
                    DesktopIconTooltip(reading.description.takeIf { it != reading.value }, interaction, enabled = interactive)
                }
            }
        }
    }
}

private fun animeEnhancementReadouts(playback: AnimePlayerState?, metrics: Set<EnhancementMetric>,
    policy: EnhancementPolicy, reason: String?,
    nextPreparation: AnimeNextEpisodePreparation?, nextPreparationEnabled: Boolean,
    liveMotionReadiness: EngineReadiness?): List<EnhancementReadout> = buildList {
    val flowing = playback?.let { it.isPlaying && !it.isBuffering && !it.playbackSuppressed &&
        it.hasVideoTracks != false && it.firstFrameRendered != false } == true
    val effect = playback?.videoEnhancement
    val observation = effect?.observation
    val motion = playback?.realtimeMotion
    val protection = playback?.currentEnhancementProtection()
    val activeMotion = motion?.takeIf { flowing && it.phase == AnimeVideoEnhancementPhase.ACTIVE }
    val window = playback?.preparedVideoWindow?.takeIf { it.frames > 0 }
    val submission = playback?.currentPictureSubmission()
    val usingPrepared = submission?.prepared == true
    val desktop = platformKind == PlatformKind.DESKTOP
    val native = playback?.nativeVideoStatistics
    if (EnhancementMetric.QUALITY in metrics) {
        val quality = when {
            playback == null -> "画质 —"
            playback.preEnhanced -> "预增强片源"
            usingPrepared -> "预处理帧复用"
            protection?.picture == true && protection.cause != AnimeEnhancementProtectionCause.GPU_BUDGET ->
                "原画 · " + protection.reasonLabel
            protection?.cause == AnimeEnhancementProtectionCause.GPU_BUDGET && submission != null ->
                "轻量增强 · " + protection.reasonLabel
            else -> when (effect?.phase) {
                AnimeVideoEnhancementPhase.OFF -> when {
                    policy.tier == QualityTier.ORIGINAL -> "原画"
                    !policy.realtimeEnhancementEnabled -> "原画 · 增强关闭"
                    else -> "原画 · 增强未运行"
                }
                AnimeVideoEnhancementPhase.WAITING -> "增强 · 等待画面"
                AnimeVideoEnhancementPhase.CONFIGURED -> if (desktop)
                    "增强已加载" else "增强 · 等待输出"
                AnimeVideoEnhancementPhase.ACTIVE -> when {
                    submission != null -> "增强处理中"
                    flowing && desktop -> "增强运行中"
                    playback.isBuffering -> "缓冲中 · 等待增强帧"
                    flowing -> "等待当前位置的增强帧"
                    else -> "增强已配置"
                }
                AnimeVideoEnhancementPhase.UNAVAILABLE -> "增强不可用"
                AnimeVideoEnhancementPhase.FAILED -> "增强异常"
                null -> "画质 —"
            }
        }
        val detail = when {
            protection?.picture == true -> protection.description
            !desktop && effect?.phase == AnimeVideoEnhancementPhase.ACTIVE && submission == null -> null
            else -> effect?.detail ?: reason
        }
        add(EnhancementReadout(EnhancementMetric.QUALITY, quality, listOfNotNull(quality, detail).joinToString("，")))
    }
    if (EnhancementMetric.DIMENSIONS in metrics) {
        fun dimensions(width: Int?, height: Int?) = if (width != null && width > 0 && height != null && height > 0)
            "$width×$height" else null
        val input = dimensions(playback?.videoWidth, playback?.videoHeight)
        val submitted = submission?.dimensions
        val filtered = native?.filterOutputSize?.let { dimensions(it.width, it.height) }
        val viewport = playback?.playbackViewportSize?.let { dimensions(it.width, it.height) }
        val source = if (playback?.preEnhanced == true) "增强文件" else "输入"
        val value = when {
            desktop && playback != null -> "$source ${input ?: "—"} · 滤镜 ${filtered ?: "缺测"}"
            submitted != null && playback?.preEnhanced != true -> "$source ${input ?: "—"} · 提交 $submitted"
            else -> "$source ${input ?: "—"}"
        }
        val description = if (desktop)
            "$source ${input ?: "未报告"}。滤镜输出 ${filtered ?: "未报告"}是进入渲染前的实际帧尺寸。" +
                "视口 ${viewport ?: "未报告"}是播放器绘制区域，包含黑边。着色器增强后的画面尺寸未单独报告。"
        else "视频输入尺寸 ${input ?: "无观测"}，已提交增强帧尺寸 ${submitted ?: "无观测"}。提交尺寸与屏幕呈现分开计量。"
        add(EnhancementReadout(EnhancementMetric.DIMENSIONS, value, description))
    }
    if (EnhancementMetric.PRESENTED_FPS in metrics) {
        val recent = playback?.frameMetrics?.takeIf { flowing }
        val output = recent?.outputFps?.takeIf { it.isFinite() && it >= 0.0 }
        val presented = playback?.presentedFps?.takeIf { flowing && it.isFinite() && it >= 0.0 }
        val estimate = native?.estimatedFilterFps?.takeIf { flowing && recent != null }
        val current = when {
            presented != null -> "呈现 ${formatDecimal(presented, 1)} fps"
            output != null -> "视频输出 ${formatDecimal(output, 1)} fps"
            estimate != null -> "滤镜估计 ${formatDecimal(estimate, 1)} fps"
            playback == null -> "帧率 —"
            playback.isBuffering -> "帧率 · 缓冲中"
            !flowing -> "帧率 · 已暂停"
            recent == null -> "帧率 · 采样中"
            else -> "输出帧率未提供"
        }
        val sourceFps = playback?.sourceFps?.takeIf { desktop && it.isFinite() && it > 0 }
        val value = sourceFps?.let { "源 ${formatDecimal(it, 1)} · $current" } ?: current
        val description = when {
            presented != null -> "播放器实际呈现帧率 ${formatDecimal(presented, 1)} 帧每秒"
            output != null -> "最近 ${formatDecimal(checkNotNull(recent).windowDurationMs / 1000.0, 1)} 秒视频输出帧释放回调实测。"
            estimate != null -> "以最近视频帧的时间戳估算滤镜输出频率。屏幕呈现帧率未测得。"
            else -> value
        }
        val sourceDescription = sourceFps?.let { "片源标称 ${formatDecimal(it, 1)} fps。" }.orEmpty()
        add(EnhancementReadout(EnhancementMetric.PRESENTED_FPS, value, sourceDescription + description))
    }
    if (EnhancementMetric.DROPPED_FRAMES in metrics) {
        fun count(value: Long?) = value?.takeIf { it >= 0L }?.toString() ?: "—"
        val recent = playback?.frameMetrics?.takeIf { flowing }
        val duration = recent?.windowDurationMs?.let { if (it >= 4_500L) "近5秒" else "近${formatDecimal(it / 1000.0, 1)}秒" }
        val durationLabel = duration ?: "近期"
        val percent = recent?.recentDropPercent?.let { " · ${formatDecimal(it, 1)}%" }.orEmpty()
        val value = when {
            playback == null -> "丢帧 —"
            !flowing -> "丢帧 · 待播放"
            recent == null -> "丢帧 · 采样中"
            playback.nativeVideoStatistics != null ->
                "${durationLabel}丢帧 解码 ${count(recent.recentDecoderDroppedFrames)} · 输出 ${count(recent.recentOutputDroppedFrames)}"
            else -> "${durationLabel}丢帧 ${count(recent.recentDroppedFrames)}$percent"
        }
        add(EnhancementReadout(EnhancementMetric.DROPPED_FRAMES, value,
            "$value；滚动窗口最长5秒，暂停、定位与处理档位变化后重新采样。" +
                "仅已观测的丢帧与输出分母可比时显示比例，补帧时不混用输入/输出分母；累计计数见状态详情"))
    }
    if (EnhancementMetric.MOTION_STATUS in metrics) {
        val value = when {
            playback == null -> "补帧 —"
            !policy.realtimeInterpolationEnabled -> "补帧已关闭"
            policy.frameMode == dev.mangaflow.enhancement.FrameMode.ORIGINAL -> "原始帧率"
            protection?.motion == true -> "补帧暂缓 · " + protection.reasonLabel
            motion == null && liveMotionReadiness == EngineReadiness.NOT_INSTALLED -> "补帧 · 需装实时组件"
            motion?.phase == AnimeVideoEnhancementPhase.FAILED || motion?.phase == AnimeVideoEnhancementPhase.UNAVAILABLE ->
                motionProblemLabel(motion?.detail)
            motion?.phase == AnimeVideoEnhancementPhase.ACTIVE && flowing && platformKind == PlatformKind.DESKTOP ->
                motion.graphFps?.let { "补帧 · 滤镜估计 ${formatDecimal(it, 1)} fps" } ?: "补帧 · 等待输出"
            motion?.phase == AnimeVideoEnhancementPhase.ACTIVE && flowing && (motion.graphFps ?: 0.0) > 0.0 &&
                (motion.synthesizedFrames == 0L || motion.generationFps == 0.0) -> "补帧 · 保留原帧"
            motion?.phase == AnimeVideoEnhancementPhase.ACTIVE && flowing && motion.synthesizedFrames > 0L &&
                motion.outputInterpolatedFrames == 0L -> "补帧 · 等待视频输出"
            motion?.phase == AnimeVideoEnhancementPhase.ACTIVE && flowing && motion.synthesizedFrames > 0L -> "补帧处理中"
            motion?.phase == AnimeVideoEnhancementPhase.ACTIVE && !flowing -> "补帧 · 待播放"
            motion?.phase == AnimeVideoEnhancementPhase.WAITING && motion.detail?.contains("等待片源帧率") == true -> "补帧 · 等待源帧率"
            motion?.phase == AnimeVideoEnhancementPhase.WAITING && motion.detail?.contains("显示屏信息") == true -> "补帧 · 等待屏幕信息"
            motion?.phase == AnimeVideoEnhancementPhase.CONFIGURED -> "补帧 · 等待输出"
            motion != null && motion.phase != AnimeVideoEnhancementPhase.OFF -> "补帧待运行"
            else -> "未实时补帧"
        }
        add(EnhancementReadout(EnhancementMetric.MOTION_STATUS, value,
            listOfNotNull(value, motion?.detail).joinToString("，")))
    }
    if (EnhancementMetric.MOTION_THROUGHPUT in metrics) {
        val fps = activeMotion?.generationFps?.takeIf { it.isFinite() && it >= 0.0 }
        val value = fps?.let { formatDecimal(it, 1) + " 帧/s" } ?: "—"
        add(EnhancementReadout(EnhancementMetric.MOTION_THROUGHPUT, "插值生成 $value",
            "插值画面生成速度 $value，不是最终视频输出或屏幕呈现帧率"))
    }
    if (EnhancementMetric.MOTION_LATENCY in metrics) {
        val elapsed = activeMotion?.inferenceNanos?.takeIf { it >= 0L }
        val value = elapsed?.let { formatDecimal(it / 1_000_000.0, 1) + " ms" } ?: "—"
        add(EnhancementReadout(EnhancementMetric.MOTION_LATENCY, "插值推理 $value", "单次插值推理耗时 $value"))
    }
    if (EnhancementMetric.GPU_TIME in metrics) {
        val elapsed = observation?.takeIf { flowing && effect?.phase == AnimeVideoEnhancementPhase.ACTIVE && it.recentlySubmitting }
            ?.gpuExecutionNanos?.takeIf { it >= 0L }
        if (elapsed != null) {
            val value = formatDecimal(elapsed / 1_000_000.0, 1) + " ms"
            add(EnhancementReadout(EnhancementMetric.GPU_TIME, "增强 GPU $value", "增强 GPU 实际采样耗时 $value"))
        } else if (!desktop) {
            add(EnhancementReadout(EnhancementMetric.GPU_TIME, "增强 GPU —", "当前 GPU 耗时缺测"))
        }
    }
    if (EnhancementMetric.BUFFER in metrics) {
        val buffer = playback?.bufferedAheadMs?.takeIf { it >= 0L }
        val value = buffer?.let { formatDecimal(it / 1000.0, 1) + " s" } ?: "—"
        val target = playback?.bufferMetrics?.targetDurationMs?.let { " / ${formatDecimal(it / 1000.0, 0)} s" }.orEmpty()
        val loading = if (playback?.bufferMetrics?.isLoading == true) " · 补充中" else ""
        val refill = playback?.bufferMetrics?.refillBelowMs?.let { "；低于 ${formatDecimal(it / 1000.0, 1)} 秒继续补充" }.orEmpty()
        add(EnhancementReadout(EnhancementMetric.BUFFER, "缓冲 $value$target$loading",
            "播放点前方已连续缓冲 $value，目标${target.removePrefix(" / ").ifBlank { "未提供" }}$refill；" +
                "按媒体分段补充，仍受网络、容量与片尾限制；不是磁盘缓存总量"))
    }
    if (EnhancementMetric.BUFFER_BYTES in metrics) {
        val bytes = playback?.bufferMetrics?.allocatedBytes
        add(EnhancementReadout(EnhancementMetric.BUFFER_BYTES, "缓冲占用 ${bytes?.let(::animeBytes) ?: "—"}",
            listOfNotNull("播放器已分配的压缩缓冲 ${bytes?.let(::animeBytes) ?: "无观测"}，不代表磁盘缓存或解码帧队列",
                playback?.bufferMetrics?.detail).joinToString("，")))
    }
    if (EnhancementMetric.NEXT_EPISODE in metrics) {
        val value = when {
            playback == null -> "下一集 —"
            !nextPreparationEnabled -> "下一集准备关闭"
            nextPreparation != null -> "下一集 · ${animePreparationLabel(nextPreparation)}"
            else -> "下一集待准备"
        }
        add(EnhancementReadout(EnhancementMetric.NEXT_EPISODE, value,
            listOfNotNull(value, nextPreparation?.detail).joinToString("，")))
    }
    if (EnhancementMetric.PREPARED in metrics) {
        val prepared = if (playback != null && window != null)
            (window.endMs - maxOf(window.startMs, playback.positionMs)).coerceAtLeast(0L) else null
        val value = when {
            playback == null -> "已备 —"
            prepared != null -> "已备 ${formatDecimal(prepared / 1000.0, 1)} s"
            playback.preparedVideoIssue != null -> "预处理暂不可用"
            else -> "未备片段"
        }
        add(EnhancementReadout(EnhancementMetric.PREPARED, value,
            listOfNotNull("前方预处理片段，$value；与播放缓冲分别计量", playback?.preparedVideoIssue).joinToString("，")))
    }
}

private fun motionProblemLabel(detail: String?): String = "补帧 · " + when {
    detail.isNullOrBlank() -> "暂不可用"
    detail.contains("纹理尺寸") -> "超出纹理上限"
    detail.contains("资源不足") -> "资源暂不足"
    detail.contains("推理超过帧预算") || detail.contains("超过实时预算") -> "超出实时预算"
    detail.contains("HDR") -> "HDR 暂不支持"
    detail.contains("屏幕刷新率") -> "等待屏幕信息"
    detail.contains("源帧率的目标") -> "目标或源帧率不支持"
    detail.contains("实际帧间距已达到目标") -> "已达目标帧率"
    else -> detail.substringBefore('，').take(24)
}
