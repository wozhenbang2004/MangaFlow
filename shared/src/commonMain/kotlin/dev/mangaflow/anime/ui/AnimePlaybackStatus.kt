package dev.mangaflow.anime.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.SlowMotionVideo
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.player.AnimePlayerState
import dev.mangaflow.anime.player.AnimeVideoEnhancementPhase
import dev.mangaflow.enhancement.ui.EnhancementPage
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.ui.motion.MotionTextButton

/** Read the current player only; this strip does not create telemetry or preparation work. */
@Composable
internal fun AnimePlaybackStatus(
    playback: AnimePlayerState?,
    quiet: Boolean,
    enabled: Boolean,
    onEnhancement: (EnhancementPage) -> Unit,
    onBuffer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val flowing = playback?.let { it.isPlaying && !it.playbackSuppressed &&
        it.hasVideoTracks != false && it.firstFrameRendered != false } == true
    val enhancement = playback?.videoEnhancement
    val quality = when {
        playback == null -> "画质 —"
        playback.preEnhanced -> "预增强片源"
        else -> when (enhancement?.phase) {
            AnimeVideoEnhancementPhase.OFF -> playback.videoHeight?.takeIf { it > 0 }?.let { "原片 ${it}p" } ?: "原画"
            AnimeVideoEnhancementPhase.WAITING, AnimeVideoEnhancementPhase.CONFIGURED -> "增强待运行"
            AnimeVideoEnhancementPhase.ACTIVE -> if (flowing && enhancement.observation?.recentlySubmitting == true)
                "增强处理中" else "增强已配置"
            AnimeVideoEnhancementPhase.UNAVAILABLE -> "增强不可用"
            AnimeVideoEnhancementPhase.FAILED -> "增强异常"
            null -> "画质 —"
        }
    }
    val motion = playback?.realtimeMotion
    val presented = playback?.presentedFps?.takeIf { flowing && it.isFinite() && it >= 0.0 }
    val sourceFps = playback?.sourceFps?.takeIf { it.isFinite() && it > 0.0 }
    val frames = when {
        playback == null -> "帧率 —"
        motion?.phase == AnimeVideoEnhancementPhase.FAILED -> "补帧异常"
        motion?.phase == AnimeVideoEnhancementPhase.UNAVAILABLE -> "补帧不可用"
        presented != null -> "呈现 ${formatDecimal(presented, 0)} fps"
        motion?.phase == AnimeVideoEnhancementPhase.ACTIVE && flowing && motion.synthesizedFrames > 0L -> "补帧处理中"
        motion != null && motion.phase != AnimeVideoEnhancementPhase.OFF -> "补帧待运行"
        sourceFps != null -> "源 ${formatDecimal(sourceFps, 0)} fps"
        else -> "帧率 —"
    }
    val buffer = playback?.bufferedAheadMs?.takeIf { it >= 0L }
    val window = playback?.preparedVideoWindow?.takeIf { it.frames > 0 }
    val prepared = if (playback != null && window != null)
        (window.endMs - maxOf(window.startMs, playback.positionMs)).coerceAtLeast(0L) else null
    val preparation = when {
        playback == null -> "预处理 —"
        prepared != null && prepared > 0L -> "已备 ${formatDecimal(prepared / 1000.0, 1)} s"
        playback.preparedVideoIssue != null -> "准备异常"
        else -> "未备片段"
    }
    Row(modifier.horizontalScroll(rememberScrollState(), enabled = enabled), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        PlaybackStatusItem(Icons.Outlined.AutoAwesome, quality, "画质", quiet, enabled,
            listOfNotNull(quality, enhancement?.detail).joinToString("，")) { onEnhancement(EnhancementPage.QUALITY) }
        PlaybackStatusItem(Icons.Outlined.SlowMotionVideo, frames, "补帧", quiet, enabled,
            listOfNotNull(frames, motion?.detail).joinToString("，")) { onEnhancement(EnhancementPage.FRAME) }
        PlaybackStatusItem(Icons.Outlined.Speed,
            buffer?.let { "缓冲 ${formatDecimal(it / 1000.0, 0)} s" } ?: "缓冲 —",
            "播放缓冲", quiet, enabled,
            buffer?.let { "播放缓冲 ${formatDecimal(it / 1000.0, 1)} 秒" } ?: "播放器未提供缓冲观测", onBuffer)
        PlaybackStatusItem(Icons.Outlined.Layers, preparation, "预处理", quiet, enabled,
            listOfNotNull("前方预处理片段，$preparation", playback?.preparedVideoIssue).joinToString("，")) {
            onEnhancement(EnhancementPage.PREPARATION)
        }
    }
}

@Composable
private fun PlaybackStatusItem(
    icon: ImageVector,
    value: String,
    label: String,
    quiet: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (quiet) .6f else .88f)
    val minimum = 64.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    MotionTextButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.widthIn(min = minimum).heightIn(min = 44.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label，$description，打开设置" },
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
        Icon(icon, null, Modifier.size(13.dp), tint = ink)
        Spacer(Modifier.width(5.dp))
        Text(value, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
