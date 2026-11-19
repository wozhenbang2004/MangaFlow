package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.enhancement.EnhancementMetric
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.platform.formatDecimal
import dev.mangaflow.platform.monotonicTimeMillis

internal data class EnhancementReadout(val metric: EnhancementMetric, val value: String, val description: String = value)

internal fun enhancementMetricIcon(metric: EnhancementMetric): ImageVector = when (metric) {
    EnhancementMetric.QUALITY -> Icons.Outlined.AutoAwesome
    EnhancementMetric.DIMENSIONS -> Icons.Outlined.AspectRatio
    EnhancementMetric.PREPARED -> Icons.Outlined.Layers
    EnhancementMetric.TASKS -> Icons.Outlined.AccountTree
    EnhancementMetric.PAGE_LATENCY, EnhancementMetric.MOTION_LATENCY, EnhancementMetric.GPU_TIME -> Icons.Outlined.Timer
    EnhancementMetric.CACHE_HITS -> Icons.Outlined.Cached
    EnhancementMetric.PRESENTED_FPS, EnhancementMetric.MOTION_THROUGHPUT -> Icons.Outlined.Speed
    EnhancementMetric.MOTION_STATUS -> Icons.Outlined.SlowMotionVideo
    EnhancementMetric.BUFFER -> Icons.Outlined.DataUsage
    EnhancementMetric.PROCESS_CPU -> Icons.Outlined.Memory
    EnhancementMetric.PROCESS_MEMORY -> Icons.Outlined.Storage
    EnhancementMetric.THERMAL -> Icons.Outlined.Thermostat
}

/** Readings have no click action. Configuration and diagnostic navigation have their own controls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EnhancementReadouts(readouts: List<EnhancementReadout>, quiet: Boolean, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = if (quiet) .7f else .88f)
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        readouts.forEach { reading ->
            Row(Modifier.widthIn(max = 280.dp).padding(vertical = 2.dp)
                .semantics(mergeDescendants = true) { contentDescription = reading.description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(enhancementMetricIcon(reading.metric), null, Modifier.size(13.dp), tint = ink)
                Text(reading.value, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = ink)
            }
        }
    }
}

/** Shares the existing resource observer, including its user-selected cadence and lifetime. */
@Composable
internal fun enhancementResourceReadouts(
    metrics: Set<EnhancementMetric>, store: ExperiencePolicyStore, observing: Boolean,
): List<EnhancementReadout> {
    if (metrics.none { it.resource }) return emptyList()
    DisposableEffect(store, observing) {
        val release = if (observing) store.startObserving() else null
        onDispose { release?.invoke() }
    }
    val usage by store.usage.collectAsStateWithLifecycle()
    val budget by store.state.collectAsStateWithLifecycle()
    val sample = usage.resources
    val fresh = observing && sample.sampledAtMillis?.let {
        monotonicTimeMillis() - it in 0L..(budget.runtime.resourceSampleSeconds.toLong() * 2_000L + 1_000L)
    } == true
    return buildList {
        if (EnhancementMetric.PROCESS_CPU in metrics) {
            val cpu = sample.processCpuLoad?.takeIf { fresh && it.isFinite() && it >= 0.0 }
            val value = cpu?.let { formatDecimal(it * 100.0, 0) + "%" } ?: "—"
            add(EnhancementReadout(EnhancementMetric.PROCESS_CPU, "CPU $value", "进程 CPU 占用 $value"))
        }
        if (EnhancementMetric.PROCESS_MEMORY in metrics) {
            val bytes = sample.processResidentBytes?.takeIf { fresh && it >= 0L }
            val value = bytes?.let(::enhancementBytes) ?: "—"
            add(EnhancementReadout(EnhancementMetric.PROCESS_MEMORY, "内存 $value", "进程驻留内存 $value"))
        }
        if (EnhancementMetric.THERMAL in metrics) {
            val value = when (sample.thermalPressure.takeIf { fresh }) {
                0 -> "正常"
                1 -> "升高"
                2 -> "较高"
                3 -> "严重"
                else -> "—"
            }
            add(EnhancementReadout(EnhancementMetric.THERMAL, "热 $value", "设备热压力 $value"))
        }
    }
}
