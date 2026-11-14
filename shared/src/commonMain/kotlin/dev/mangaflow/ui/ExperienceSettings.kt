package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.experience.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.ui.motion.MotionSlider
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** A compact settings entry opens one retained workspace, rather than expanding the page. */
@Composable
internal fun ExperienceSettingsDialog(visible: Boolean, backdrop: HazeState?, onDismiss: () -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val colors = MaterialTheme.colorScheme
    MotionDialog(visible, onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 520.dp) {
        val maximumHeight = minOf(LocalMangaWindow.current.height * .9f, 640.dp)
        val panel: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight)
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("体验策略", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭体验策略") {
                        Icon(Icons.Outlined.Close, "关闭体验策略", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scroll)
                    .padding(top = 6.dp, bottom = 8.dp)) {
                    ExperienceSettings(active, expanded) { expanded = it }
                }
            }
        }
        Box(Modifier.padding(horizontal = 12.dp)) {
            if (backdrop != null) LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
            else Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
                color = colors.surfaceContainerLow, tonalElevation = 0.dp) { panel() }
        }
    }
}

/** Existing Ovio selection, retained disclosure, shared slider and press-stretch switch. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ExperienceSettings(active: Boolean, expanded: String?, onExpandedChange: (String?) -> Unit) {
    val context = LocalMangaContext.current
    val store = remember(context) { ExperiencePolicyStore.get(context) }
    val budget by store.state.collectAsStateWithLifecycle()
    val custom by store.overrides.collectAsStateWithLifecycle()
    val usage by store.usage.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    fun change(value: ExperienceOverrides) {
        try { store.setOverrides(value); notice = null }
        catch (_: Exception) { notice = "设置暂未保存，请重试" }
    }
    fun toggle(key: String) { if (active) onExpandedChange(if (expanded == key) null else key) }
    LaunchedEffect(active, expanded) {
        if (!active) return@LaunchedEffect
        store.refreshResources()
        if (expanded == "cache" || expanded == "diagnostics") {
            while (true) { store.refreshCacheUsage(); delay(2_000L) }
        }
    }
    Column(Modifier.fillMaxWidth().motionInputGate(active), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsChoices(ExperienceMode.entries.map { it to experienceModeLabel(it) }, budget.mode) { mode ->
            try { store.setMode(mode); notice = null } catch (_: Exception) { notice = "体验策略暂未保存，请重试" }
        }
        Text(budget.reason, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }

        SettingsDisclosure("阅读加载", when (custom.prefetchMode) {
            PrefetchMode.OFF -> "预取已关闭"
            PrefetchMode.MANUAL -> "已自定义"
            PrefetchMode.SMART -> "智能调整"
        }, Icons.Outlined.AutoStories, expanded == "reading", { toggle("reading") }) {
            SettingsChoices(PrefetchMode.entries.map { it to when (it) {
                PrefetchMode.SMART -> "智能"; PrefetchMode.OFF -> "关闭"; PrefetchMode.MANUAL -> "手动"
            } }, custom.prefetchMode) { change(custom.copy(prefetchMode = it)) }
            if (custom.prefetchMode == PrefetchMode.MANUAL) {
                ExperienceNumber("前方页数", custom.ahead, budget.prefetchAhead, 0..12, "页") { change(custom.copy(ahead = it)) }
                ExperienceNumber("后方页数", custom.behind, budget.prefetchBehind, 0..4, "页") { change(custom.copy(behind = it)) }
            }
            if (custom.prefetchMode != PrefetchMode.OFF) {
                ExperienceNumber("预取请求", custom.prefetchRequests, budget.prefetchRequests, 1..3, "个") { change(custom.copy(prefetchRequests = it)) }
                ExperienceNumber("预取窗口", custom.prefetchMiB, 24, 4..96, "MB") { change(custom.copy(prefetchMiB = it)) }
                SettingsToggle("提前准备下一章", custom.crossChapter, Icons.Outlined.SkipNext) { change(custom.copy(crossChapter = it)) }
            }
            ExperienceNumber("当前页请求", custom.readerRequests, budget.readerRequests, 1..4, "个") { change(custom.copy(readerRequests = it)) }
            ExperienceBudgetRow("实际预取", "前 " + budget.prefetchAhead + " · 后 " + budget.prefetchBehind + " 页上限")
            ExperienceBudgetRow("窗口分配", experienceCacheSize(budget.adjacentPrefetchBytes) + " 邻页 · " + experienceCacheSize(budget.nextChapterPrefetchBytes) + " 跨章")
            ExperienceHint("智能预取按方向、翻页速度和原图字节收敛；双页按完整跨页取整。原图先缓存，显示时再解码。原生解密来源仅准备目录。")
        }
        SettingsDisclosure("下载与并发", if (custom.downloadPages != null || custom.sourcePages != null || custom.chapters != null) "已自定义" else "智能调整",
            Icons.Outlined.Download, expanded == "downloads", { toggle("downloads") }) {
            ExperienceNumber("总页面请求", custom.downloadPages, if (platformKind == PlatformKind.DESKTOP) 4 else 2,
                1..maximumBackgroundPageParallelism, "个") { change(custom.copy(downloadPages = it)) }
            ExperienceNumber("每个来源", custom.sourcePages, 2, 1..4, "个") { change(custom.copy(sourcePages = it)) }
            ExperienceNumber("同时下载章节", custom.chapters, 2, 1..4, "章") { change(custom.copy(chapters = it)) }
            SettingsToggle("阅读时下载让路", custom.yieldToReading, Icons.Outlined.AutoStories) { change(custom.copy(yieldToReading = it)) }
            ExperienceBudgetRow("当前限额", budget.backgroundPages.toString() + " 页 · " + budget.chapterParallelism + " 章 · 每源 " + budget.sourceParallelism)
            ExperienceHint("每源限额与预取共享；来源限流或变慢时自动收敛。新限额作用于后续请求，已传输的页面继续完成。")
        }
        SettingsDisclosure("缓存与存储", usage.diskBytes?.let(::experienceCacheSize) ?: "读取中",
            Icons.Outlined.Storage, expanded == "cache", { toggle("cache") }) {
            ExperienceBudgetRow("图片磁盘", cacheUsage(usage.diskBytes, usage.diskLimit))
            ExperienceBudgetRow("解码内存", cacheUsage(usage.memoryBytes, usage.memoryLimit))
            if (active && expanded == "cache") {
                val downloads = remember(context) { DownloadStore.get(context) }
                val entries by downloads.entries.collectAsStateWithLifecycle()
                ExperienceBudgetRow("下载图片", experienceCacheSize(entries.sumOf { it.downloadedBytes }))
            }
            ExperienceNumber("内存缓存上限", custom.memoryMiB, ((usage.memoryLimit ?: 128L * EXPERIENCE_MIB) / EXPERIENCE_MIB).toInt(),
                16..512, "MB") { change(custom.copy(memoryMiB = it)) }
            ExperienceNumber("磁盘缓存上限", custom.diskMiB, (budget.diskCacheBytes / EXPERIENCE_MIB).toInt().coerceAtLeast(32),
                32..8192, "MB") { change(custom.copy(diskMiB = it)) }
            ExperienceBudgetRow("当前磁盘预算", experienceCacheSize(budget.diskCacheBytes))
            if (usage.diskResizePending) ExperienceHint("等待正在使用的原图释放后应用容量；保留现有缓存。")
            usage.cacheError?.let { ExperienceHint(it) }
            ExperienceHint("内存上限受应用可用资源保护。图片缓存自动按最近使用回收；离线章节独立保存，可在缓存管理中清理可回收内容。")
        }
        SettingsDisclosure("显示与动效", when (custom.refresh) {
            null -> "跟随体验目标"; RefreshPreference.SYSTEM -> "跟随系统"; RefreshPreference.INTERACTION -> "交互时高刷"; RefreshPreference.MANUAL -> "已自定义"
        }, Icons.Outlined.Speed, expanded == "display", { toggle("display") }) {
            val manualRates = if (platformKind == PlatformKind.ANDROID) usage.resources.supportedRefreshRates else emptyList()
            val choices = listOf<Pair<RefreshPreference?, String>>(null to "自动", RefreshPreference.SYSTEM to "系统", RefreshPreference.INTERACTION to "交互") +
                if (manualRates.isNotEmpty()) listOf(RefreshPreference.MANUAL to "指定") else emptyList()
            SettingsChoices(choices, custom.refresh) { change(custom.copy(refresh = it)) }
            if (custom.refresh == RefreshPreference.MANUAL && manualRates.isNotEmpty()) {
                // Only the actual native display modes are selectable; no invented 120/300 Hz.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    manualRates.forEach { rate ->
                        MotionTextButton(onClick = { change(custom.copy(refreshHz = rate)) }) {
                            Text(rate.toString() + " Hz" + if (custom.refreshHz == rate) " ✓" else "")
                        }
                    }
                }
            }
            ExperienceHint(if (platformKind == PlatformKind.ANDROID)
                "交互结束后释放高刷请求；系统温度、省电和显示能力决定最终刷新率。"
                else "屏幕刷新由系统调度；应用减少后台工作以优先交互，当前平台不提供精确刷新率控制。")
        }
        SettingsDisclosure("网络与电量", if (custom.allowMeteredPrefetch) "允许计费预取" else "节制流量",
            Icons.Outlined.NetworkCheck, expanded == "network", { toggle("network") }) {
            SettingsToggle("计费网络也可预取", custom.allowMeteredPrefetch, Icons.Outlined.NetworkCheck) { change(custom.copy(allowMeteredPrefetch = it)) }
            SettingsToggle("低电量减少后台工作", custom.batteryProtection, Icons.Outlined.BatterySaver) { change(custom.copy(batteryProtection = it)) }
            SettingsToggle("允许后台下载", custom.allowBackgroundDownloads, Icons.Outlined.Download) { change(custom.copy(allowBackgroundDownloads = it)) }
            ExperienceHint("网络计费未知时，自动模式采用保守预取，跨章默认不取正文。手动设置仍受字节和资源预算约束；系统省电、严重温度与内存保护始终生效。")
        }
        SettingsDisclosure("实时资源状态", if (budget.protections.isEmpty()) "查看诊断" else budget.protections.first(),
            Icons.Outlined.Tune, expanded == "diagnostics", { toggle("diagnostics") }) {
            ExperienceBudgetRow("保护原因", budget.protections.joinToString(" · ").ifBlank { "未触发资源保护" })
            ExperienceBudgetRow("正在执行", usage.activeChapters.toString() + " 章 · " + usage.activeBackgroundPages + " 下载页 · " + usage.activePrefetch + " 预取")
            ExperienceBudgetRow("当前页 / 排队", usage.activeReaderImages.toString() + " / " + usage.queuedTasks)
            ExperienceBudgetRow("图片缓存命中", "内存 " + usage.memoryHits + " · 磁盘 " + usage.diskHits)
            ExperienceBudgetRow("预取复用", usage.prefetchHits.toString() + " 次 · 新取 " + usage.prefetchedPages + " 页")
            ExperienceBudgetRow("图片请求 P95", usage.requestP95Millis?.let { it.toString() + " ms（含排队与解码）" } ?: "尚无成功请求")
            ExperienceBudgetRow("实际传输", experienceCacheSize(usage.transferredBytes))
            ExperienceBudgetRow("限流来源", usage.throttledSources.toString())
            ExperienceBudgetRow("帧工作耗时 P95 / P99", if (usage.frameP95Millis != null && usage.frameP99Millis != null)
                frameMillis(usage.frameP95Millis!!) + " / " + frameMillis(usage.frameP99Millis!!)
                else if (platformKind == PlatformKind.ANDROID) "尚无帧样本" else "当前平台未提供")
            ExperienceBudgetRow("应用堆内存", cacheUsage(usage.resources.processHeapBytes, usage.resources.processHeapLimitBytes))
            ExperienceBudgetRow("可用系统内存", usage.resources.availableMemoryBytes?.let(::experienceCacheSize) ?: "系统未提供")
            ExperienceBudgetRow("可用磁盘", usage.resources.freeDiskBytes?.let(::experienceCacheSize) ?: "系统未提供")
            ExperienceBudgetRow("处理器负载", usage.resources.cpuLoad?.let { (it * 100).roundToInt().toString() + "%" } ?: "系统未提供")
            ExperienceBudgetRow("网络计费", when (usage.resources.metered) { true -> "计费"; false -> "非计费"; null -> "未知" })
            ExperienceBudgetRow("电量 / 温度", (usage.resources.batteryPercent?.let { it.toString() + "%" } ?: "未知") + " / " +
                when (usage.resources.thermalPressure) { 0 -> "正常"; 1 -> "升高"; 2 -> "较高"; 3 -> "严重"; else -> "未知" })
            ExperienceHint("这些是本次运行的实际观测。帧工作耗时不是实际屏幕 FPS；传输记录不代表带宽测速。保护快速收敛，恢复会保留缓冲时间。")
            MotionTextButton(onClick = store::refreshResources, enabled = active) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp)); Text("重新读取")
            }
        }
    }
}

@Composable
private fun ExperienceNumber(title: String, value: Int?, automatic: Int, range: IntRange, unit: String, onChange: (Int?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val current = (value ?: automatic).coerceIn(range)
    val focus = LocalFocusManager.current
    var draft by remember(value) { mutableStateOf(current.toString()) }
    var sliding by remember(value) { mutableFloatStateOf(current.toFloat()) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val parsed = draft.toIntOrNull()?.coerceIn(range)
        val accepted = parsed ?: current
        draft = accepted.toString()
        sliding = accepted.toFloat()
        if (parsed != null && parsed != value) onChange(parsed)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (value != null) {
                BasicTextField(draft, onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) draft = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus(force = true) }),
                    modifier = Modifier.width(64.dp).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                        .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 10.dp, vertical = 10.dp)
                        .semantics { contentDescription = title + "数值，单位" + unit }
                        .onFocusChanged {
                            if (focused && !it.isFocused) commit()
                            focused = it.isFocused
                        })
                Spacer(Modifier.width(4.dp))
                Text(unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            MotionTextButton(onClick = { focus.clearFocus(force = true); onChange(if (value == null) current else null) }) {
                Text(if (value == null) "智能" else "恢复智能", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (value != null) MotionSlider(sliding, onValueChange = { sliding = it; draft = it.roundToInt().toString() },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            onValueChangeFinished = { onChange(sliding.roundToInt().coerceIn(range)) },
            formatValue = { it.roundToInt().toString() + " " + unit }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ExperienceBudgetRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1.25f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
@Composable
private fun ExperienceHint(value: String) = Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
private fun cacheUsage(used: Long?, limit: Long?) = (used?.let(::experienceCacheSize) ?: "读取中") + " / " + (limit?.let(::experienceCacheSize) ?: "未知上限")
private fun frameMillis(value: Double) = ((value * 10).roundToInt() / 10.0).toString() + " ms"
internal fun experienceModeLabel(mode: ExperienceMode) = when (mode) { ExperienceMode.AUTO -> "自动"; ExperienceMode.ECO -> "节能"; ExperienceMode.PERFORMANCE -> "流畅优先" }
internal fun experienceCacheSize(bytes: Long): String = when {
    bytes >= 1024L * EXPERIENCE_MIB -> ((bytes.toDouble() / (1024L * EXPERIENCE_MIB) * 10).roundToInt() / 10.0).toString() + " GB"
    bytes >= EXPERIENCE_MIB -> ((bytes.toDouble() / EXPERIENCE_MIB * 10).roundToInt() / 10.0).toString() + " MB"
    else -> (bytes.coerceAtLeast(0L) / 1024L).toString() + " KB"
}
