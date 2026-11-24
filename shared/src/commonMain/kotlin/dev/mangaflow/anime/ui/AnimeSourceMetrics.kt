package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.AnimePlaybackMeasurements
import dev.mangaflow.anime.AnimeSourceLineMeasurement
import dev.mangaflow.anime.AnimeSourceMeasurementPhase
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.ui.mangaMetricValueStyle
import dev.mangaflow.ui.mangaStatusColors

/** The list exposes one quality value and one network value on the same axes for each source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnimeSourceMetricsStrip(measurements: AnimePlaybackMeasurements?, modifier: Modifier = Modifier,
    episodeLabel: String? = null, progress: AnimeSourceLineMeasurement? = null) {
    val width = measurements?.width?.takeIf { it > 0 }
    val height = measurements?.height?.takeIf { it > 0 }
    val measured = width != null && height != null
    val declared = measurements?.let(::animeDeclaredQuality)
    val mediaQuality = measurements?.mediaQuality
    val headerHeight = measurements?.mediaHeaderHeight
    val quality = if (measured) "${height}p" else headerHeight?.let { "${it}p" } ?: mediaQuality ?: declared
    val qualityChecked = measurements?.mediaObservedAt != null || measurements?.addressObservedAt != null
    val hasVideoEvidence = measurements?.playbackObservedAt != null || measurements?.firstFrameObservedAt != null ||
        measurements?.mediaVerified == true && measurements.mediaFailure == null
    val qualityDescription = when {
        measured -> "播放解码实测分辨率 $width × $height"
        headerHeight != null -> "媒体轨道标注 ${headerHeight}p"
        mediaQuality != null -> "媒体清单标注最高画质 $mediaQuality，非实播分辨率"
        declared != null -> "来源标注画质 $declared，尚未实播验证"
        qualityChecked -> "本次取址或媒体检查未取得分辨率，播放后可读取实际画质"
        else -> "尚未取得画质数据"
    }
    val scope = episodeLabel?.let { "$it，" }.orEmpty()
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SourceMetricToken(if (!measured && quality != null) Icons.AutoMirrored.Outlined.Label else Icons.Outlined.AspectRatio,
            quality ?: if (hasVideoEvidence) "未知" else "—", scope + qualityDescription,
            modifier = Modifier.width(58.dp * scale))
        val speed = measurements?.measuredBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
        val probeSpeed = measurements?.takeIf { it.mediaVerified == true && it.mediaFailure == null }
            ?.mediaSampleBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
        val frame = measurements?.firstFrameMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
        val address = measurements?.addressMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
        val failure = measurements?.mediaFailure
        val evidenceAt = measurements?.mediaObservedAt ?: 0L
        val playedAt = maxOf(measurements?.playbackObservedAt ?: 0L, measurements?.firstFrameObservedAt ?: 0L)
        val latestProbe = evidenceAt > 0L && playedAt <= evidenceAt
        val progressLabel = progress?.phase?.takeIf { it != AnimeSourceMeasurementPhase.COMPLETE }
            ?.let(::animeSourceMeasurementLabel)
        val statusCode = measurements?.mediaHttpStatus?.takeIf { it >= 400 }
        when {
            progressLabel != null && progress?.phase?.checking == true ->
                SourceMetricToken(Icons.Outlined.Speed, progressLabel, progressLabel, Modifier.width(82.dp * scale))
            latestProbe && failure != null && measurements?.mediaVerified == true -> SourceMetricToken(Icons.Outlined.Link,
                "仅清单", scope + failure, Modifier.width(82.dp * scale), waiting = true)
            latestProbe && failure != null && (statusCode != null || measurements?.mediaVerified == false) -> SourceMetricToken(Icons.Outlined.Link,
                statusCode?.let { "HTTP $it" } ?: "未验证", scope + failure,
                Modifier.width(82.dp * scale), waiting = true)
            progressLabel != null && address == null && speed == null && probeSpeed == null && frame == null ->
                SourceMetricToken(Icons.Outlined.Speed, progressLabel,
                progress?.reason ?: "此来源$progressLabel", Modifier.width(82.dp * scale),
                waiting = progress?.phase == AnimeSourceMeasurementPhase.UNAVAILABLE ||
                    progress?.phase == AnimeSourceMeasurementPhase.FAILED)
            speed != null -> SourceMetricToken(Icons.Outlined.Speed, speed, scope + "播放实测传输速率 $speed", Modifier.width(82.dp * scale))
            frame != null -> SourceMetricToken(Icons.Outlined.Timer, frame, scope + "播放实测首帧 $frame", Modifier.width(82.dp * scale))
            probeSpeed != null -> SourceMetricToken(Icons.Outlined.Link, probeSpeed, scope + "媒体样本传输速率 $probeSpeed，非实播速率", Modifier.width(82.dp * scale))
            measurements?.mediaVerified == true && failure == null -> SourceMetricToken(Icons.Outlined.Link, "样本可读", scope + "已读取媒体样本，非实播结果", Modifier.width(82.dp * scale))
            address != null -> SourceMetricToken(Icons.Outlined.Link, "取址 $address", scope + "解析地址耗时 $address", Modifier.width(82.dp * scale))
            failure != null -> SourceMetricToken(Icons.Outlined.Link, "未验证", scope + failure, Modifier.width(82.dp * scale))
            else -> SourceMetricToken(Icons.Outlined.Speed, "未测", scope + "尚未取得网络实测数据",
                Modifier.width(82.dp * scale))
        }
    }
}

@Composable
private fun SourceMetricToken(icon: ImageVector, value: String?, description: String,
    modifier: Modifier = Modifier, waiting: Boolean = false) {
    val status = mangaStatusColors()
    val colors = MaterialTheme.colorScheme
    Row(modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (waiting) status.waiting else colors.onSurfaceVariant)
        Text(value ?: "—", Modifier.weight(1f), style = mangaMetricValueStyle(),
            color = if (waiting) status.waiting else colors.onSurfaceVariant, textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AnimeSourceMetricsDetails(measurements: AnimePlaybackMeasurements?, modifier: Modifier = Modifier,
    episodeLabel: String? = null, routeLabel: String? = null, progress: AnimeSourceLineMeasurement? = null) {
    val width = measurements?.width?.takeIf { it > 0 }
    val height = measurements?.height?.takeIf { it > 0 }
    val decoded = if (width != null && height != null) "$width × $height" else null
    val declared = measurements?.let(::animeDeclaredQuality)
    val mediaQuality = measurements?.mediaQuality
    val headerHeight = measurements?.mediaHeaderHeight
    val probeSpeed = measurements?.takeIf { it.mediaVerified == true && it.mediaFailure == null }
        ?.mediaSampleBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
    val frame = measurements?.firstFrameMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
    val rate = measurements?.measuredBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
    val address = measurements?.addressMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        episodeLabel?.let { AnimeQuietLabel(it) }
        if (progress != null) {
            SourceMetricDetail(Icons.Outlined.Speed, "本次测速", animeSourceMeasurementLabel(progress.phase),
                waiting = progress.phase == AnimeSourceMeasurementPhase.UNAVAILABLE ||
                    progress.phase == AnimeSourceMeasurementPhase.MANIFEST_ONLY ||
                    progress.phase == AnimeSourceMeasurementPhase.FAILED)
            progress.reason?.takeUnless { it == measurements?.mediaFailure }?.let { AnimeQuietLabel(it) }
        }
        when {
            decoded != null -> SourceMetricDetail(Icons.Outlined.AspectRatio, "播放实测分辨率", decoded)
            headerHeight != null -> SourceMetricDetail(Icons.Outlined.AspectRatio, "视频轨道标注", "${headerHeight}p")
            mediaQuality != null -> SourceMetricDetail(Icons.AutoMirrored.Outlined.Label, "清单最高画质", mediaQuality)
            declared != null -> SourceMetricDetail(Icons.AutoMirrored.Outlined.Label, "来源标注画质", declared)
            else -> SourceMetricDetail(Icons.Outlined.AspectRatio, "画质", null)
        }
        SourceMetricDetail(Icons.Outlined.Speed, "播放传输", rate)
        if (probeSpeed != null) SourceMetricDetail(Icons.Outlined.Speed, "样本传输", probeSpeed)
        SourceMetricDetail(Icons.Outlined.Timer, "首帧（实播）", frame)
        val addressFailed = address == null && measurements?.addressObservedAt != null
        SourceMetricDetail(Icons.Outlined.Link, "取址", if (addressFailed) "未取得地址" else address, waiting = addressFailed)
        val mediaState = when {
            measurements?.mediaFailure != null -> measurements?.mediaFailure
            measurements?.mediaVerified == true -> "已读媒体样本"
            measurements?.mediaObservedAt != null -> "尚未验证"
            else -> null
        }
        SourceMetricDetail(Icons.Outlined.Link, "媒体预探测", mediaState, waiting = measurements?.mediaFailure != null)
        measurements?.mediaSampleMs?.let { SourceMetricDetail(Icons.Outlined.Timer,
            "预探测耗时", animeSourceDuration(it)) }
        routeLabel?.let { SourceMetricDetail(Icons.Outlined.Tune, "线路设置", it) }
        if (measurements != null) {
            val playedAt = listOfNotNull(measurements.playbackObservedAt, measurements.firstFrameObservedAt)
                .maxOrNull()?.let { formatInstantMillis(it, "MM-dd HH:mm") }
            val addressAt = measurements.addressObservedAt?.let { formatInstantMillis(it, "MM-dd HH:mm") }
            val mediaAt = measurements.mediaObservedAt?.let { formatInstantMillis(it, "MM-dd HH:mm") }
            val times = listOfNotNull(playedAt?.let { "播放 $it" },
                mediaAt?.takeIf { it != playedAt }?.let { "探测 $it" },
                addressAt?.takeIf { it != playedAt && it != mediaAt }?.let { "取址 $it" })
            if (times.isNotEmpty()) AnimeQuietLabel(times.joinToString(" · "))
        }
    }
}

private fun animeSourceMeasurementLabel(phase: AnimeSourceMeasurementPhase): String = when (phase) {
    AnimeSourceMeasurementPhase.QUEUED -> "排队中"
    AnimeSourceMeasurementPhase.RUNNING -> "测速中"
    AnimeSourceMeasurementPhase.COMPLETE -> "已有媒体证据"
    AnimeSourceMeasurementPhase.MANIFEST_ONLY -> "仅清单"
    AnimeSourceMeasurementPhase.ADDRESS_ONLY -> "仅取址"
    AnimeSourceMeasurementPhase.FAILED -> "失败"
    AnimeSourceMeasurementPhase.WAITING_CATALOG -> "等待目录"
    AnimeSourceMeasurementPhase.NO_EPISODE -> "待对应"
    AnimeSourceMeasurementPhase.UNAVAILABLE -> "待重试"
    AnimeSourceMeasurementPhase.CANCELLED -> "已停止"
}

@Composable
private fun SourceMetricDetail(icon: ImageVector, title: String, value: String?, waiting: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val status = mangaStatusColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text(value ?: "—", Modifier.weight(1.7f).clearAndSetSemantics { contentDescription = value ?: "未测量" },
            style = mangaMetricValueStyle(), textAlign = TextAlign.End,
            color = when {
                waiting -> status.waiting
                value == null -> colors.onSurfaceVariant
                else -> colors.onSurface
            })
    }
}

private fun animeDeclaredQuality(measurements: AnimePlaybackMeasurements): String? =
    measurements.declaredQuality?.takeIf(String::isNotBlank)?.let { if (it == "4k") "4K" else it }

private fun animeSourceDuration(millis: Long): String =
    if (millis >= 1_000L) "${formatDecimal(millis / 1_000.0, 1)} s" else "$millis ms"

internal fun animeSourceRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_073_741_824L -> "${formatDecimal(bytesPerSecond / 1_073_741_824.0, 1)} GiB/s"
    bytesPerSecond >= 1_048_576L -> "${formatDecimal(bytesPerSecond / 1_048_576.0, 1)} MiB/s"
    bytesPerSecond >= 1_024L -> "${formatDecimal(bytesPerSecond / 1_024.0, 0)} KiB/s"
    else -> "$bytesPerSecond B/s"
}
