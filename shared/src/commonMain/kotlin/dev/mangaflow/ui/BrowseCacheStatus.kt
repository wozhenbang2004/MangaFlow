package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.BrowseState
import dev.mangaflow.data.DiscoveryMode
import dev.mangaflow.experience.ContentCacheKind
import dev.mangaflow.experience.ContentCacheStorage
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.wallTimeMillis
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import kotlinx.coroutines.delay

/** One page-level indicator discloses actual list origin and the current reusable-data policy. */
@Composable
internal fun BrowseCacheStatus(state: BrowseState, backdrop: HazeState, active: Boolean,
    onRefresh: (() -> Unit)? = null) {
    val context = LocalMangaContext.current
    val store = remember(context) { ExperiencePolicyStore.get(context) }
    val overrides by store.overrides.collectAsStateWithLifecycle()
    val policy = overrides.contentCache
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val interactive = active && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    var now by remember { mutableStateOf(wallTimeMillis()) }
    var expanded by remember { mutableStateOf(false) }
    var popupPresent by remember { mutableStateOf(false) }
    var pendingSettingsKey by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.updatedAt) { now = wallTimeMillis() }
    LaunchedEffect(active, state.cacheKey, lifecycle) {
        expanded = false
        pendingSettingsKey = null
        settingsOpen = false
        if (active) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = wallTimeMillis()
                delay(30_000L)
            }
        }
    }
    LaunchedEffect(interactive) {
        if (!interactive) { expanded = false; pendingSettingsKey = null; settingsOpen = false }
    }
    // Wait for CompactGlassPopover's actual retained exit, including reduced motion.
    LaunchedEffect(popupPresent, pendingSettingsKey, interactive, state.cacheKey) {
        if (!popupPresent && pendingSettingsKey != null) {
            settingsOpen = interactive && pendingSettingsKey == state.cacheKey
            pendingSettingsKey = null
        }
    }
    val kind = when {
        state.isSearch -> ContentCacheKind.SEARCH
        state.mode == DiscoveryMode.RANKING -> ContentCacheKind.RANKING
        else -> ContentCacheKind.DISCOVERY
    }
    val heading = when (kind) {
        ContentCacheKind.SEARCH -> "搜索缓存"
        ContentCacheKind.RANKING -> "榜单缓存"
        else -> "发现缓存"
    }
    val fresh = policy.freshMillis(kind)
    val age = state.updatedAt.takeIf { it > 0L && it <= now }?.let { now - it }
    val stale = state.fromCache && age != null && (fresh == 0L || age >= fresh)
    val busy = state.loading || state.refreshing || state.loadingMore
    val actionLabel = if (onRefresh != null) "刷新与缓存" else heading
    val status = when {
        busy -> if (state.loadingMore) "正在追加结果" else "正在更新"
        state.error != null -> "更新未完整完成"
        state.fromCache -> if (stale) "缓存已过鲜活期" else "从缓存恢复"
        state.updatedAt > 0L -> "最近请求已完成"
        else -> "尚无列表"
    }
    val icon = when {
        busy -> Icons.Outlined.Sync
        state.error != null -> Icons.Outlined.CloudOff
        stale -> Icons.Outlined.Schedule
        else -> Icons.Outlined.Storage
    }
    val colors = MaterialTheme.colorScheme
    Box {
        MotionIconButton(onClick = { pendingSettingsKey = null; expanded = !expanded },
            enabled = interactive, modifier = Modifier.size(44.dp).semantics {
                contentDescription = actionLabel
                stateDescription = status
            }, tooltip = "$actionLabel · $status") {
            MotionSwap(icon, Modifier.size(19.dp)) { shown ->
                Icon(shown, null, Modifier.size(19.dp),
                    tint = if (state.error != null) colors.error else colors.onSurfaceVariant)
            }
        }
        CompactGlassPopover(visible = expanded && interactive, backdrop = backdrop,
            onDismiss = { expanded = false; pendingSettingsKey = null },
            onPresenceChange = { popupPresent = it }, maximumWidth = 288.dp) {
            if (onRefresh != null) CompactMenuAction(Icons.Outlined.Refresh, "刷新榜单",
                enabled = interactive && !busy) {
                expanded = false
                onRefresh()
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(heading, style = MaterialTheme.typography.titleSmall)
                BrowseCacheValue("当前", status)
                BrowseCacheValue("列表来源", if (state.fromCache) "含已保存列表" else if (state.updatedAt > 0L)
                    "最近完成的请求" else "等待结果")
                BrowseCacheValue("列表时间", if (state.updatedAt > 0L)
                    formatInstantMillis(state.updatedAt, "MM-dd HH:mm") else "尚未生成")
                if (state.updatedAt > 0L) BrowseCacheValue("列表年龄", age?.let {
                    if (it < 60_000L) "不足 1 分钟" else cacheDuration(it)
                } ?: "时间待校准")
                BrowseCacheValue("鲜活期", when {
                    !policy.enabled -> "不复用"
                    policy.refreshOnOpen -> "每次打开更新"
                    fresh == 0L -> "每次请求更新"
                    else -> cacheDuration(fresh)
                })
                BrowseCacheValue("恢复保留", if (policy.enabled) cacheDuration(policy.retentionMillis) else "关闭复用")
                BrowseCacheValue("保存策略", when (policy.storage) {
                    ContentCacheStorage.MEMORY_AND_DISK -> "内存 + 磁盘"
                    ContentCacheStorage.MEMORY_ONLY -> "仅内存"
                    ContentCacheStorage.DISABLED -> "关闭"
                })
                if (state.error != null) Text(state.error, style = MaterialTheme.typography.bodySmall,
                    color = colors.error)
                Text("列表时间是生成 / 保存时间；请求完成也可能命中来源缓存。",
                    fontSize = 11.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
                if (!policy.enabled) Text("已有文件仍可在存储管理中清理。",
                    fontSize = 11.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant)
            }
            CompactMenuAction(Icons.Outlined.Tune, "调整缓存策略", enabled = interactive) {
                pendingSettingsKey = state.cacheKey
                expanded = false
            }
        }
    }
    ExperienceSettingsDialog(visible = settingsOpen && interactive, backdrop = backdrop,
        onDismiss = { settingsOpen = false })
}

@Composable
private fun BrowseCacheValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, Modifier.weight(1f), fontSize = 11.sp, lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.6f), fontSize = 12.sp, lineHeight = 17.sp,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

private fun cacheDuration(milliseconds: Long): String = when {
    milliseconds >= 86_400_000L && milliseconds % 86_400_000L == 0L -> "${milliseconds / 86_400_000L} 天"
    milliseconds >= 3_600_000L && milliseconds % 3_600_000L == 0L -> "${milliseconds / 3_600_000L} 小时"
    else -> "${(milliseconds / 60_000L).coerceAtLeast(1L)} 分钟"
}
