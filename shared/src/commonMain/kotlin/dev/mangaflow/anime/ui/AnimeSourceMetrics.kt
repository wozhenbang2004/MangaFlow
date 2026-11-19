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
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.ui.mangaMetricValueStyle
import dev.mangaflow.ui.mangaStatusColors

/** The list exposes one quality value and one network value on the same axes for each source. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnimeSourceMetricsStrip(measurements: AnimePlaybackMeasurements?, modifier: Modifier = Modifier,
    episodeLabel: String? = null) {
    val width = measurements?.width?.takeIf { it > 0 }
    val height = measurements?.height?.takeIf { it > 0 }
    val measured = width != null && height != null
    val declared = measurements?.let(::animeDeclaredQuality)
    val mediaQuality = measurements?.mediaQuality
    val quality = if (measured) "${height}p" else mediaQuality ?: declared
    val qualityDescription = when {
        measured -> "播放解码实测分辨率 $width × $height"
        mediaQuality != null -> "媒体清单标注最高画质 $mediaQuality，非实播分辨率"
        declared != null -> "来源标注画质 $declared，尚未实播验证"
        else -> "画质尚未测得"
    }
    val scope = episodeLabel?.let { "$it，" }.orEmpty()
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SourceMetricToken(if (!measured && declared != null) Icons.AutoMirrored.Outlined.Label else Icons.Outlined.AspectRatio,
            quality, scope + qualityDescription,
            modifier = Modifier.width(58.dp * scale))
        val speed = measurements?.measuredBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
        val probeSpeed = measurements?.mediaSampleBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
        val frame = measurements?.firstFrameMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
        val address = measurements?.addressMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
        val failure = measurements?.mediaFailure
        val evidenceAt = measurements?.mediaObservedAt ?: 0L
        val latestProbeFailed = measurements != null && measurements.mediaVerified == false &&
            (measurements.playbackObservedAt ?: 0L) < evidenceAt
        when {
            latestProbeFailed && failure != null -> SourceMetricToken(Icons.Outlined.Link,
                measurements?.mediaHttpStatus?.toString() ?: "失败", scope + failure,
                Modifier.width(82.dp * scale), waiting = true)
            speed != null -> SourceMetricToken(Icons.Outlined.Speed, speed, scope + "播放实测传输速率 $speed", Modifier.width(82.dp * scale))
            probeSpeed != null -> SourceMetricToken(Icons.Outlined.Speed, probeSpeed, scope + "64 KiB 以内的小样本传输速率 $probeSpeed，非持续播放速率", Modifier.width(82.dp * scale))
            frame != null -> SourceMetricToken(Icons.Outlined.Timer, frame, scope + "播放实测首帧 $frame", Modifier.width(82.dp * scale))
            address != null -> SourceMetricToken(Icons.Outlined.Link, address, scope + "解析地址耗时 $address", Modifier.width(82.dp * scale))
            failure != null -> SourceMetricToken(Icons.Outlined.Link, "未验证", scope + failure, Modifier.width(82.dp * scale))
            else -> SourceMetricToken(Icons.Outlined.Speed, null, scope + "尚未取得网络实测数据",
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
        Text(value ?: "待测", Modifier.weight(1f), style = mangaMetricValueStyle(),
            color = if (waiting) status.waiting else colors.onSurfaceVariant, textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun AnimeSourceMetricsDetails(measurements: AnimePlaybackMeasurements?, modifier: Modifier = Modifier,
    episodeLabel: String? = null, routeLabel: String? = null) {
    val width = measurements?.width?.takeIf { it > 0 }
    val height = measurements?.height?.takeIf { it > 0 }
    val decoded = if (width != null && height != null) "$width × $height" else null
    val declared = measurements?.let(::animeDeclaredQuality)
    val mediaQuality = measurements?.mediaQuality
    val probeSpeed = measurements?.mediaSampleBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
    val frame = measurements?.firstFrameMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
    val rate = measurements?.measuredBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
    val address = measurements?.addressMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        episodeLabel?.let { AnimeQuietLabel(it) }
        when {
            decoded != null -> SourceMetricDetail(Icons.Outlined.AspectRatio, "播放实测分辨率", decoded)
            mediaQuality != null -> SourceMetricDetail(Icons.AutoMirrored.Outlined.Label, "清单最高画质", mediaQuality)
            declared != null -> SourceMetricDetail(Icons.AutoMirrored.Outlined.Label, "来源标注画质", declared)
            else -> SourceMetricDetail(Icons.Outlined.AspectRatio, "画质", null)
        }
        SourceMetricDetail(Icons.Outlined.Speed, "播放传输", rate)
        if (probeSpeed != null) SourceMetricDetail(Icons.Outlined.Speed, "媒体小样本", probeSpeed)
        SourceMetricDetail(Icons.Outlined.Timer, "首帧（实播）", frame)
        val addressFailed = address == null && measurements?.addressObservedAt != null
        SourceMetricDetail(Icons.Outlined.Link, "取址", if (addressFailed) "未取得地址" else address, waiting = addressFailed)
        val mediaState = when {
            measurements?.mediaFailure != null -> measurements?.mediaFailure
            measurements?.mediaVerified == true -> "媒体头已验证"
            measurements?.mediaObservedAt != null -> "尚未验证"
            else -> null
        }
        SourceMetricDetail(Icons.Outlined.Link, "媒体预探测", mediaState, waiting = measurements?.mediaVerified == false)
        measurements?.mediaSampleMs?.let { SourceMetricDetail(Icons.Outlined.Timer,
            "预探测耗时", animeSourceDuration(it)) }
        routeLabel?.let { SourceMetricDetail(Icons.Outlined.Tune, "线路设置", it) }
        if (measurements != null) {
            val playedAt = measurements.playbackObservedAt?.let { formatInstantMillis(it, "MM-dd HH:mm") }
            val addressAt = measurements.addressObservedAt?.let { formatInstantMillis(it, "MM-dd HH:mm") }
            val mediaAt = measurements.mediaObservedAt?.let { formatInstantMillis(it, "MM-dd HH:mm") }
            val times = listOfNotNull(playedAt?.let { "播放 $it" },
                mediaAt?.takeIf { it != playedAt }?.let { "探测 $it" },
                addressAt?.takeIf { it != playedAt && it != mediaAt }?.let { "取址 $it" })
            if (times.isNotEmpty()) AnimeQuietLabel(times.joinToString(" · "))
        }
    }
}

@Composable
private fun SourceMetricDetail(icon: ImageVector, title: String, value: String?, waiting: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val status = mangaStatusColors()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text(value ?: "—", Modifier.clearAndSetSemantics { contentDescription = value ?: "未测量" },
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
