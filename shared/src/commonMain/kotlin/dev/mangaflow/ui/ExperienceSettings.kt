package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.experience.ExperienceMode
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate

/** The existing Ovio selection and quiet disclosure configure the real shared budget. */
@Composable
internal fun ExperienceSettings(active: Boolean) {
    val context = LocalMangaContext.current
    val store = remember(context) { ExperiencePolicyStore.get(context) }
    val budget by store.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var more by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active) { if (active) store.refreshResources() }
    Column(Modifier.fillMaxWidth().motionInputGate(active),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MotionTabs(
            selected = budget.mode.ordinal, labels = ExperienceMode.entries.map(::experienceModeLabel),
            onSelect = { index ->
                notice = null
                try { store.setMode(ExperienceMode.entries[index]) }
                catch (_: Exception) { notice = "体验策略暂未保存，请重试。" }
            }, modifier = Modifier.fillMaxWidth(), treatment = MotionTabTreatment.Soft,
            fullWidthIndicator = true, controlHeight = 44.dp,
            track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface,
        )
        Text(budget.reason, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        if (budget.cacheAppliesOnRestart) Text("缓存上限将在下次启动应用后更新。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        MotionTextButton(onClick = { more = !more }, enabled = active, modifier = Modifier.fillMaxWidth()) {
            Text("更多", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text("当前策略预算", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Icon(Icons.Outlined.ChevronRight, if (more) "收起策略预算" else "展开策略预算",
                Modifier.size(18.dp).graphicsLayer { rotationZ = if (more) 90f else 0f },
                tint = colors.onSurfaceVariant)
        }
        if (more) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ExperienceBudgetRow("后台下载", "同时 ${budget.backgroundPages} 页")
            ExperienceBudgetRow("阅读预取", "前 ${budget.prefetchAhead} · 后 ${budget.prefetchBehind} 页")
            ExperienceBudgetRow("刷新偏好", if (budget.preferHighRefresh) "请求较高刷新率" else "跟随系统")
            ExperienceBudgetRow("目标磁盘缓存", experienceCacheSize(budget.diskCacheBytes))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("缓存容量在图片缓存初始化时应用。", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                MotionIconButton(onClick = store::refreshResources, enabled = active,
                    modifier = Modifier.size(44.dp), tooltip = "重新读取设备资源状态") {
                    Icon(Icons.Outlined.Refresh, "重新读取设备资源状态", Modifier.size(18.dp),
                        tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ExperienceBudgetRow(label: String, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant)
        Text(value, Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall,
            color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

internal fun experienceModeLabel(mode: ExperienceMode): String = when (mode) {
    ExperienceMode.AUTO -> "自动"
    ExperienceMode.ECO -> "节能"
    ExperienceMode.PERFORMANCE -> "流畅优先"
}

internal fun experienceCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    else -> "${bytes.coerceAtLeast(0L) / 1024L} KB"
}
