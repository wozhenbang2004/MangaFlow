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
    val quality = if (measured) "${height}p" else declared
    val qualityDescription = when {
        measured -> "实测分辨率 $width × $height"
        declared != null -> "来源标注画质 $declared"
        else -> "画质未测"
    }
    val scope = episodeLabel?.let { "$it，" }.orEmpty()
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SourceMetricToken(if (!measured && declared != null) Icons.AutoMirrored.Outlined.Label else Icons.Outlined.AspectRatio,
            quality, scope + qualityDescription,
            modifier = Modifier.width(58.dp * scale))
        val speed = measurements?.measuredBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
        val frame = measurements?.firstFrameMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
        val address = measurements?.addressMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
        when {
            speed != null -> SourceMetricToken(Icons.Outlined.Speed, speed, scope + "实测传输速率 $speed", Modifier.width(82.dp * scale))
            frame != null -> SourceMetricToken(Icons.Outlined.Timer, frame, scope + "实测首帧 $frame", Modifier.width(82.dp * scale))
            address != null -> SourceMetricToken(Icons.Outlined.Link, address, scope + "取址耗时 $address", Modifier.width(82.dp * scale))
            else -> {
                val addressFailed = measurements?.addressObservedAt != null
                SourceMetricToken(if (addressFailed) Icons.Outlined.Link else Icons.Outlined.Speed, null,
                    scope + if (addressFailed) "上次未取得地址" else "网络未测", Modifier.width(82.dp * scale), waiting = addressFailed)
            }
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
    episodeLabel: String? = null, routeLabel: String? = null) {
    val width = measurements?.width?.takeIf { it > 0 }
    val height = measurements?.height?.takeIf { it > 0 }
    val decoded = if (width != null && height != null) "$width × $height" else null
    val declared = measurements?.let(::animeDeclaredQuality)
    val frame = measurements?.firstFrameMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
    val rate = measurements?.measuredBytesPerSecond?.takeIf { it > 0 }?.let(::animeSourceRate)
    val address = measurements?.addressMs?.takeIf { it >= 0 }?.let(::animeSourceDuration)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        episodeLabel?.let { AnimeQuietLabel(it) }
        when {
            decoded != null -> SourceMetricDetail(Icons.Outlined.AspectRatio, "实测分辨率", decoded)
            declared != null -> SourceMetricDetail(Icons.AutoMirrored.Outlined.Label, "来源标注", declared)
            else -> SourceMetricDetail(Icons.Outlined.AspectRatio, "画质", null)
        }
        SourceMetricDetail(Icons.Outlined.Speed, "传输采样", rate)
        SourceMetricDetail(Icons.Outlined.Timer, "首帧", frame)
        val addressFailed = address == null && measurements?.addressObservedAt != null
        SourceMetricDetail(Icons.Outlined.Link, "取址", if (addressFailed) "未取得地址" else address, waiting = addressFailed)
        routeLabel?.let { SourceMetricDetail(Icons.Outlined.Tune, "线路设置", it) }
        if (measurements != null) {
            val playedAt = measurements.playbackObservedAt?.let { formatInstantMillis(it, "MM-dd HH:mm") }
            val addressAt = measurements.addressObservedAt?.let { formatInstantMillis(it, "MM-dd HH:mm") }
            val times = listOfNotNull(playedAt?.let { "播放 $it" }, addressAt?.takeIf { it != playedAt }?.let { "取址 $it" })
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

private fun animeSourceRate(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_073_741_824L -> "${formatDecimal(bytesPerSecond / 1_073_741_824.0, 1)} GiB/s"
    bytesPerSecond >= 1_048_576L -> "${formatDecimal(bytesPerSecond / 1_048_576.0, 1)} MiB/s"
    bytesPerSecond >= 1_024L -> "${formatDecimal(bytesPerSecond / 1_024.0, 0)} KiB/s"
    else -> "$bytesPerSecond B/s"
}
