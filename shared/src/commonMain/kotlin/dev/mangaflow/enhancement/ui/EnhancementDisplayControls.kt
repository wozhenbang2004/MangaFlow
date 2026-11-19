package dev.mangaflow.enhancement.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.enhancement.EnhancementCenter
import dev.mangaflow.enhancement.EnhancementContent
import dev.mangaflow.enhancement.EnhancementMetric
import dev.mangaflow.experience.ReaderStatusPlacement
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.SettingsToggle
import dev.mangaflow.ui.motion.MotionTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Manga shares display choices across its content namespaces, including session editors. */
@Composable
internal fun EnhancementDisplayControls(content: EnhancementContent) {
    val context = LocalMangaContext.current
    val center = remember(context) { EnhancementCenter.get(context) }
    val saved by center.settings.visibleMetrics.collectAsStateWithLifecycle()
    val readerPreferences by center.resources.overrides.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by remember(content, center) { mutableStateOf(center.settings.visibleMetrics.value.getValue(content)) }
    var pending by remember(content, center) { mutableIntStateOf(0) }
    var revision by remember(content, center) { mutableLongStateOf(0L) }
    var notice by remember(content, center) { mutableStateOf<String?>(null) }
    var openingStatus by remember(center) { mutableStateOf(false) }
    var statusError by remember(center) { mutableStateOf<String?>(null) }
    LaunchedEffect(saved, content) {
        if (pending == 0) draft = center.settings.visibleMetrics.value.getValue(content)
    }
    fun select(metric: EnhancementMetric, enabled: Boolean) {
        val next = if (enabled) draft + metric else draft - metric
        draft = next
        val edit = ++revision
        pending++
        notice = null
        val receipt = center.saveVisibleMetrics(content, next)
        scope.launch {
            try {
                if (!receipt.await() && edit == revision) notice = "显示项未能保存，请重试。"
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (edit == revision) notice = "显示项未能保存，请重试。" }
            finally {
                pending--
                if (pending == 0) draft = center.settings.visibleMetrics.value.getValue(content)
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EnhancementQuiet(if (content == EnhancementContent.ANIME)
            "所选读数显示在播放画面，收起控制后仍可见。" else "所选读数直接显示在阅读状态栏。")
        if (content != EnhancementContent.ANIME && readerPreferences.readerStatusPlacement == ReaderStatusPlacement.HIDDEN) {
            EnhancementQuiet("阅读状态栏目前已隐藏，所选读数暂不显示。")
            MotionTextButton(onClick = {
                if (openingStatus) return@MotionTextButton
                openingStatus = true
                statusError = null
                val baseline = center.resources.overrides.value
                val mode = center.resources.state.value.mode
                scope.launch {
                    try {
                        if (!center.resources.applyConfiguration(mode,
                            baseline.copy(readerStatusPlacement = ReaderStatusPlacement.BOTTOM),
                            expectedMode = mode, expectedOverrides = baseline))
                            statusError = "未能打开状态栏，设置可能已变化，请重试。"
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { statusError = "未能打开状态栏，请重试。" }
                    finally { openingStatus = false }
                }
            }, enabled = !openingStatus, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Icon(Icons.Outlined.VerticalAlignBottom, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (openingStatus) "正在打开…" else "在底部显示阅读状态栏")
            }
        }
        statusError?.let { EnhancementQuiet(it, error = true) }
        val groups = listOf(
            "画质与准备" to listOf(EnhancementMetric.QUALITY, EnhancementMetric.DIMENSIONS,
                EnhancementMetric.PREPARED, EnhancementMetric.TASKS, EnhancementMetric.PAGE_LATENCY,
                EnhancementMetric.CACHE_HITS, EnhancementMetric.GPU_TIME, EnhancementMetric.BUFFER,
                EnhancementMetric.BUFFER_BYTES, EnhancementMetric.NEXT_EPISODE),
            "帧率与补帧" to listOf(EnhancementMetric.PRESENTED_FPS, EnhancementMetric.DROPPED_FRAMES, EnhancementMetric.MOTION_STATUS,
                EnhancementMetric.MOTION_THROUGHPUT, EnhancementMetric.MOTION_LATENCY),
            "设备" to listOf(EnhancementMetric.PROCESS_CPU, EnhancementMetric.PROCESS_MEMORY, EnhancementMetric.THERMAL),
        )
        groups.forEach { (title, values) ->
            val available = values.filter { it.supports(content) }
            if (available.isNotEmpty()) {
                Text(title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    available.forEach { metric ->
                        SettingsToggle(metric.label(content), metric in draft, enhancementMetricIcon(metric)) {
                            select(metric, it)
                        }
                    }
                }
            }
        }
        when {
            notice != null -> EnhancementQuiet(notice.orEmpty(), error = true)
            pending > 0 -> EnhancementQuiet("正在保存显示项…")
            draft.isEmpty() -> EnhancementQuiet("已隐藏全部增强读数。")
        }
        EnhancementQuiet("— 表示没有观测；已备表示文件就绪。")
    }
}
