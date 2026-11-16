package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.stateDescription
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
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionSlider
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** A compact settings entry opens one retained workspace, rather than expanding the page. */
@Composable
internal fun ExperienceSettingsDialog(visible: Boolean, backdrop: HazeState?, onDismiss: () -> Unit) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val overviewScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    val scroll = if (expanded == null) overviewScroll else detailScroll
    LaunchedEffect(expanded) { if (expanded != null) detailScroll.scrollTo(0) }
    val colors = MaterialTheme.colorScheme
    MotionDialog(visible, onDismissRequest = {
        if (expanded != null) expanded = null else onDismiss()
    }, properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 520.dp) {
        val maximumHeight = minOf(LocalMangaWindow.current.height * .9f, 640.dp)
        val panel: @Composable () -> Unit = {
            // One stable panel size: navigation changes its content, never its outer window.
            Column(Modifier.fillMaxWidth().height(minOf(maximumHeight, 480.dp).coerceAtLeast(1.dp))
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

/** Existing Ovio selection and retained rows keep one reading-first workspace. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ExperienceSettings(active: Boolean, expanded: String?, onExpandedChange: (String?) -> Unit) {
    val context = LocalMangaContext.current
    val store = remember(context) { ExperiencePolicyStore.get(context) }
    val budget by store.state.collectAsStateWithLifecycle()
    val custom by store.overrides.collectAsStateWithLifecycle()
    val usage by store.usage.collectAsStateWithLifecycle()
    val editor = remember { ExperienceEditor() }
    val focus = LocalFocusManager.current
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var cacheTab by rememberSaveable { mutableStateOf("images") }
    var cacheKind by rememberSaveable { mutableStateOf(ContentCacheKind.SEARCH) }
    val colors = MaterialTheme.colorScheme
    fun closeEditor() {
        editor.field = null
        focus.clearFocus(force = true)
    }
    fun change(value: ExperienceOverrides) {
        if (!active) return
        try { store.setOverrides(value); notice = null }
        catch (_: Exception) { notice = "设置未保存，请重试" }
    }
    fun toggle(key: String) {
        if (!active) return
        closeEditor()
        detail = null
        onExpandedChange(if (expanded == key) null else key)
    }
    fun toggleDetail(key: String) {
        if (!active) return
        closeEditor()
        detail = if (detail == key) null else key
    }
    LaunchedEffect(active, expanded) {
        closeEditor()
        if (!active) return@LaunchedEffect
        store.refreshResources()
        if (expanded == "cache" || expanded == "resources") {
            while (true) { store.refreshCacheUsage(); delay(2_000L) }
        }
    }
    Column(Modifier.fillMaxWidth().motionInputGate(active)) {
        if (expanded == null) {
            SettingsChoices(ExperienceMode.entries.map { it to experienceModeLabel(it) }, budget.mode) { mode ->
                if (active) {
                    closeEditor()
                    try { store.setMode(mode); notice = null }
                    catch (_: Exception) { notice = "模式未保存，请重试" }
                }
            }
            if (budget.protections.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Outlined.Shield, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                    Text(budget.protections.joinToString(" · "), Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else {
            MotionTextButton(onClick = { toggle(expanded) }, enabled = active,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                modifier = Modifier.heightIn(min = 44.dp)) {
                Icon(Icons.Outlined.ArrowBack, null, Modifier.size(18.dp), tint = colors.onSurface)
                Spacer(Modifier.width(6.dp))
                Text("全部策略", style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
            }
        }
        Spacer(Modifier.height(4.dp))
        notice?.let {
            Text(it, Modifier.fillMaxWidth().padding(bottom = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.error)
        }

        if (expanded == null || expanded == "reading") SettingsDisclosure("阅读与预取",
            if (custom.prefetchMode == PrefetchMode.OFF) "已关闭"
            else budget.prefetchAhead.toString() + " 待读 · " + budget.prefetchBehind + " 回看",
            Icons.Outlined.AutoStories, expanded == "reading", { toggle("reading") },
            compact = true, modified = experienceReadingCustom(custom)) {
            SettingsChoices(PrefetchMode.entries.map { it to when (it) {
                PrefetchMode.SMART -> "智能"; PrefetchMode.OFF -> "关闭"; PrefetchMode.MANUAL -> "手动"
            } }, custom.prefetchMode) {
                closeEditor()
                change(custom.copy(prefetchMode = it))
            }
            ExperienceReveal(custom.prefetchMode == PrefetchMode.MANUAL) {
                ExperienceNumber("待读窗口", custom.ahead, budget.goal.prefetchAhead, 0..40, "页",
                    editor, active && expanded == "reading" && custom.prefetchMode == PrefetchMode.MANUAL, effective = budget.prefetchAhead) { change(custom.copy(ahead = it)) }
                ExperienceNumber("回看窗口", custom.behind, budget.goal.prefetchBehind, 0..8, "页",
                    editor, active && expanded == "reading" && custom.prefetchMode == PrefetchMode.MANUAL, effective = budget.prefetchBehind) { change(custom.copy(behind = it)) }
            }
            ExperienceReveal(custom.prefetchMode != PrefetchMode.OFF) {
                SettingsToggle("提前准备下一章", custom.crossChapter, Icons.Outlined.SkipNext) { change(custom.copy(crossChapter = it)) }
                if (custom.crossChapter && !budget.crossChapter) ExperienceHint("跨章预读当前已暂停")
            }
            SettingsToggle("浏览时提前准备", custom.browseWarmup, Icons.Outlined.AutoStories) { change(custom.copy(browseWarmup = it)) }
            if (custom.browseWarmup && !budget.browseWarmup) ExperienceHint("浏览预热当前已暂停")
            ExperienceTools("reading", "阅读与预取", active && expanded == "reading", detail, ::toggleDetail,
                experienceReadingCustom(custom), onRestore = {
                    closeEditor()
                    change(custom.copy(prefetchMode = PrefetchMode.SMART, ahead = null, behind = null,
                        prefetchRequests = null, prefetchMiB = null, resolveRequests = null, crossChapter = true,
                        readerRequests = null, browseWarmup = true))
                }, extra = {
                    ExperienceReveal(custom.prefetchMode != PrefetchMode.OFF) {
                        ExperienceNumber("传输并发", custom.prefetchRequests, budget.goal.prefetchRequests, 1..12, "个",
                            editor, active && expanded == "reading" && detail == "reading-advanced" && custom.prefetchMode != PrefetchMode.OFF,
                            effective = budget.prefetchRequests) { change(custom.copy(prefetchRequests = it)) }
                        ExperienceNumber("原图预取窗口", custom.prefetchMiB, (budget.goal.prefetchBytes / EXPERIENCE_MIB).toInt(), 1..512, "MB",
                            editor, active && expanded == "reading" && detail == "reading-advanced" && custom.prefetchMode != PrefetchMode.OFF,
                            effective = (budget.prefetchBytes / EXPERIENCE_MIB).toInt()) { change(custom.copy(prefetchMiB = it)) }
                        ExperienceBudgetRow("邻页 / 跨章", experienceCacheSize(budget.adjacentPrefetchBytes) + " / " +
                            experienceCacheSize(budget.nextChapterPrefetchBytes))
                    }
                    ExperienceNumber("解析并发", custom.resolveRequests, budget.goal.resolveRequests, 1..8, "个",
                        editor, active && expanded == "reading" && detail == "reading-advanced",
                        effective = budget.resolveRequests) { change(custom.copy(resolveRequests = it)) }
                    ExperienceNumber("当前页请求", custom.readerRequests, budget.goal.readerRequests, 1..4, "个",
                        editor, active && expanded == "reading" && detail == "reading-advanced",
                        effective = budget.readerRequests) { change(custom.copy(readerRequests = it)) }
                }) {
                ExperienceBudgetRow("窗口目标", budget.goal.prefetchAhead.toString() + " 待读 · " + budget.goal.prefetchBehind + " 回看")
                ExperienceHint("待读和回看跟随实际翻页方向，不固定为屏幕左右。窗口是候选页数，解析、传输、解码各有并发上限；原图存磁盘，临近预览进内存。")
                ExperienceHint("环形箭头表示跟随目标，笔形表示手动值，直接输入不设业务上限；“当前”只在暂停等实际状态不同时显示。配置数量不等于正在执行的数量。")
            }
        }

        if (expanded == null || expanded == "rendering") SettingsDisclosure("图像质量与渲染", "预解码 " + budget.decodedPrefetchPages + " 页",
            Icons.Outlined.Image, expanded == "rendering", { toggle("rendering") },
            compact = true, modified = custom.decodedPrefetchPages != null || custom.decodeRequests != null) {
            ExperienceNumber("临近页预解码", custom.decodedPrefetchPages, budget.goal.decodedPrefetchPages, 0..8, "页",
                editor, active && expanded == "rendering", effective = budget.decodedPrefetchPages) {
                change(custom.copy(decodedPrefetchPages = it))
            }
            ExperienceNumber("解码并发", custom.decodeRequests, budget.goal.decodeRequests, 1..4, "个",
                editor, active && expanded == "rendering", effective = budget.decodeRequests) {
                change(custom.copy(decodeRequests = it))
            }
            ExperienceTools("rendering", "图像质量与渲染", active && expanded == "rendering", detail, ::toggleDetail,
                custom.decodedPrefetchPages != null || custom.decodeRequests != null, onRestore = {
                    closeEditor(); change(custom.copy(decodedPrefetchPages = null, decodeRequests = null))
                }) {
                ExperienceHint("保留来源原图；预解码只准备显示尺寸，不压缩或替换原图。超大图与放大细节仍受解码内存保护；关闭预解码也可正常阅读。")
                ExperienceHint("双页、阅读方向和阅读模式在阅读控制台调整。")
            }
        }

        if (expanded == null || expanded == "sources") SettingsDisclosure("多源与补页", when {
            budget.sourceRecovery == SourceRecoveryMode.OFF -> "按需切源"
            budget.standbySources == 0 -> "预热暂停"
            else -> "备用 " + budget.standbySources + " 源"
        }, Icons.Outlined.Layers, expanded == "sources", { toggle("sources") },
            compact = true, modified = custom.sourceRecovery != null || custom.standbySources != null || custom.standbyMiB != null || custom.standbyPages != 3 || !custom.standbyMemoryWarmup) {
            SettingsChoices(listOf<Pair<SourceRecoveryMode?, String>>(
                null to "跟随", SourceRecoveryMode.OFF to "关闭",
                SourceRecoveryMode.BALANCED to "稳健", SourceRecoveryMode.AGGRESSIVE to "积极"), custom.sourceRecovery) {
                change(custom.copy(sourceRecovery = it))
            }
            if (custom.sourceRecovery == null) ExperienceBudgetRow("当前", sourceRecoveryLabel(budget.sourceRecovery))
            ExperienceBudgetRow("备用预热", budget.standbySources.toString() + " 源 · " + experienceCacheSize(budget.standbyPrefetchBytes))
            budget.standbyPauseReason?.let { ExperienceHint(it) }
            ExperienceNumber("同时备用来源", custom.standbySources,
                if (budget.sourceRecovery == SourceRecoveryMode.AGGRESSIVE) 2 else 1, 1..4, "源",
                editor, active && expanded == "sources", effective = budget.standbySources, automaticLabel = "预热模式") {
                change(custom.copy(standbySources = it))
            }
            ExperienceNumber("每源附近预热", custom.standbyPages, 3, 1..12, "页",
                editor, active && expanded == "sources", effective = budget.standbyPages) { change(custom.copy(standbyPages = it ?: 3)) }
            ExperienceNumber("备用原图窗口", custom.standbyMiB,
                (budget.goal.prefetchBytes / 4L / EXPERIENCE_MIB).toInt(), 1..128, "MB",
                editor, active && expanded == "sources", effective = (budget.standbyPrefetchBytes / EXPERIENCE_MIB).toInt()) {
                change(custom.copy(standbyMiB = it))
            }
            SettingsToggle("备用小图存入内存", custom.standbyMemoryWarmup, Icons.Outlined.Memory) {
                change(custom.copy(standbyMemoryWarmup = it))
            }
            ExperienceHint(if (custom.standbyMemoryWarmup) "原图 → 磁盘 · 小图 → 内存；受实际解码预算限制"
                else "原图 → 磁盘；切换时再解码，不预先占用解码内存")
            SettingsToggle("允许计费或未知网络预热", custom.allowMeteredPrefetch, Icons.Outlined.NetworkCheck) {
                change(custom.copy(allowMeteredPrefetch = it))
            }
            if (usage.throttledSources > 0) ExperienceHint("限流 · " + usage.throttledSources + " 源")
            ExperienceTools("sources", "多源与补页", active && expanded == "sources", detail, ::toggleDetail,
                custom.sourceRecovery != null || custom.standbySources != null || custom.standbyMiB != null || custom.standbyPages != 3 || !custom.standbyMemoryWarmup, onRestore = {
                    closeEditor(); change(custom.copy(sourceRecovery = null, standbySources = null, standbyMiB = null, standbyPages = 3, standbyMemoryWarmup = true))
                }) {
                ExperienceBudgetRow("模式目标", "备用至多 " + budget.goal.standbySources + " 源")
                ExperienceHint("提前准备已关联的同章备用来源，预取可发现请求和图片错误。章节对齐不代表每一页相同，不会仅按页码替换图片；切换版本保留独立位置。")
                ExperienceHint("不同来源并行准备，当前位置优先；限流或预算用尽会显示原因。磁盘原图与内存预览遵循“缓存与离线”的独立保留策略。")
                ExperienceHint("未指定容量时自动分配备用窗口；手动容量按输入独立保留。翻页后只释放已退出窗口且结束的任务额度；磁盘旧原图仍按缓存策略回收。")
            }
        }

        if (expanded == null || expanded == "downloads") SettingsDisclosure("下载与后台",
            if (budget.backgroundPages == 0) "暂缓新请求"
            else budget.backgroundPages.toString() + " 页 · " + budget.chapterParallelism + " 章",
            Icons.Outlined.Download, expanded == "downloads", { toggle("downloads") },
            compact = true, modified = experienceDownloadCustom(custom)) {
            ExperienceNumber("总页面请求", custom.downloadPages, budget.goal.backgroundPages, 1..maximumBackgroundPageParallelism, "个",
                editor, active && expanded == "downloads", effective = budget.backgroundPages) { change(custom.copy(downloadPages = it)) }
            ExperienceNumber("每个来源", custom.sourcePages, budget.goal.sourceParallelism, 1..8, "个",
                editor, active && expanded == "downloads", effective = budget.sourceParallelism) { change(custom.copy(sourcePages = it)) }
            ExperienceNumber("同时下载章节", custom.chapters, budget.goal.chapterParallelism, 1..4, "章",
                editor, active && expanded == "downloads", effective = budget.chapterParallelism) { change(custom.copy(chapters = it)) }
            SettingsToggle("阅读时下载让路", custom.yieldToReading, Icons.Outlined.AutoStories) { change(custom.copy(yieldToReading = it)) }
            SettingsToggle("允许后台下载", custom.allowBackgroundDownloads, Icons.Outlined.Download) { change(custom.copy(allowBackgroundDownloads = it)) }
            if (usage.activeBackgroundPages > 0 || usage.activeChapters > 0)
                ExperienceBudgetRow("正在下载", usage.activeBackgroundPages.toString() + " 页 · " + usage.activeChapters + " 章")
            ExperienceTools("downloads", "下载与后台", active && expanded == "downloads", detail, ::toggleDetail,
                experienceDownloadCustom(custom), onRestore = {
                    closeEditor()
                    change(custom.copy(downloadPages = null, sourcePages = null, chapters = null,
                        yieldToReading = true, allowBackgroundDownloads = true))
                }) {
                ExperienceHint("未指定每源并发时按真实反馈调整；手动并发直接生效。服务端限流时按实际回执等待，正在传输的页面继续完成。")
                ExperienceHint(when (platformKind) {
                    PlatformKind.DESKTOP -> "退出应用后停止下载，重新打开后核对并续传。"
                    PlatformKind.IOS -> "在系统允许的延长执行时段内继续；系统暂停或强制退出后，重新打开续传。"
                    else -> "后台下载受系统任务配额和通知权限限制；中断后保留可用断点。"
                })
            }
        }

        if (expanded == null || expanded == "cache") SettingsDisclosure("缓存与离线", usage.diskBytes?.let { "已用 " + experienceCacheSize(it) } ?: "用量未知",
            Icons.Outlined.Storage, expanded == "cache", { toggle("cache") },
            compact = true, modified = custom.memoryMiB != null || custom.diskMiB != null || custom.contentCache != ContentCachePreferences()) {
            SettingsChoices(listOf("images" to "图片", "data" to "页面与资料"), cacheTab) {
                closeEditor(); cacheTab = it
            }
            if (cacheTab == "images") {
            ExperienceBudgetRow("解码内存", cacheUsage(usage.memoryBytes, usage.memoryLimit))
            ExperienceNumber("内存缓存上限", custom.memoryMiB, usage.memoryLimit?.let { (it / EXPERIENCE_MIB).toInt() }, 1..512, "MB",
                editor, active && expanded == "cache", effective = usage.memoryLimit?.let { (it / EXPERIENCE_MIB).toInt() }, automaticLabel = "当前可用上限") {
                change(custom.copy(memoryMiB = it))
            }
            ExperienceBudgetRow("图片磁盘", cacheUsage(usage.diskBytes, usage.diskLimit))
            ExperienceNumber("磁盘缓存上限", custom.diskMiB, (budget.goal.diskCacheBytes / EXPERIENCE_MIB).toInt(), 1..8192, "MB",
                editor, active && expanded == "cache", effective = (budget.diskCacheBytes / EXPERIENCE_MIB).toInt()) {
                change(custom.copy(diskMiB = it))
            }
            if (active && expanded == "cache") {
                val downloads = remember(context) { DownloadStore.get(context) }
                val entries by downloads.entries.collectAsStateWithLifecycle()
                ExperienceBudgetRow("离线已保存", experienceCacheSize(entries.sumOf { it.downloadedBytes }))
            }
            if (usage.diskResizePending) ExperienceHint("等待在用原图释放后应用容量")
            usage.cacheError?.let { ExperienceHint(it) }
            ExperienceNumber("解码内存闲置回收", custom.contentCache.memoryIdleMinutes, 0, 0..120, "分钟",
                editor, active && expanded == "cache") { change(custom.copy(contentCache = custom.contentCache.copy(memoryIdleMinutes = it ?: 0))) }
            ExperienceNumber("磁盘图片保留", custom.contentCache.imageRetentionHours, 0, 0..720, "小时",
                editor, active && expanded == "cache") { change(custom.copy(contentCache = custom.contentCache.copy(imageRetentionHours = it ?: 0))) }
            ExperienceHint("0＝只按空间与最近使用淘汰。闲置回收只释放缓存引用；当前显示图像仍可持有内存。磁盘保留从最近访问起算，在前台约每分钟检查，并始终保护正在阅读、传输和离线文件。")
            } else {
                val cache = custom.contentCache
                SettingsChoices(listOf(ContentCacheStorage.MEMORY_AND_DISK to "内存＋磁盘",
                    ContentCacheStorage.MEMORY_ONLY to "仅内存", ContentCacheStorage.DISABLED to "关闭"), cache.storage) {
                    closeEditor(); change(custom.copy(contentCache = cache.copy(storage = it)))
                }
                SettingsToggle("打开页面即更新", cache.refreshOnOpen, Icons.Outlined.Refresh) {
                    change(custom.copy(contentCache = cache.copy(refreshOnOpen = it)))
                }
                SettingsChoices(listOf(ContentCacheKind.SEARCH to "搜索", ContentCacheKind.DISCOVERY to "发现",
                    ContentCacheKind.RANKING to "榜单", ContentCacheKind.DETAILS to "目录"),
                    cacheKind) {
                    closeEditor(); cacheKind = it
                }
                SettingsChoices(listOf(ContentCacheKind.PAGE_MANIFEST to "图址", ContentCacheKind.METRICS to "评分",
                    ContentCacheKind.POPULARITY to "热度", ContentCacheKind.COMMENTS to "评论"),
                    cacheKind) {
                    closeEditor(); cacheKind = it
                }
                val minutes = when (cacheKind) {
                    ContentCacheKind.SEARCH -> cache.searchMinutes; ContentCacheKind.DISCOVERY -> cache.discoveryMinutes
                    ContentCacheKind.RANKING -> cache.rankingMinutes; ContentCacheKind.DETAILS -> cache.detailMinutes
                    ContentCacheKind.PAGE_MANIFEST -> cache.manifestMinutes; ContentCacheKind.METRICS -> cache.scoreMinutes
                    ContentCacheKind.POPULARITY -> cache.popularityMinutes; ContentCacheKind.COMMENTS -> cache.commentMinutes
                }
                val defaultMinutes = (ContentCachePreferences().freshMillis(cacheKind) / 60_000L).toInt()
                ExperienceNumber(if (cacheKind == ContentCacheKind.POPULARITY) "热度资料时效上限" else "重新请求间隔",
                    minutes, defaultMinutes, 0..10_080, "分钟", editor, active && expanded == "cache") { value ->
                    val next = value ?: defaultMinutes
                    change(custom.copy(contentCache = when (cacheKind) {
                        ContentCacheKind.SEARCH -> cache.copy(searchMinutes = next); ContentCacheKind.DISCOVERY -> cache.copy(discoveryMinutes = next)
                        ContentCacheKind.RANKING -> cache.copy(rankingMinutes = next); ContentCacheKind.DETAILS -> cache.copy(detailMinutes = next)
                        ContentCacheKind.PAGE_MANIFEST -> cache.copy(manifestMinutes = next); ContentCacheKind.METRICS -> cache.copy(scoreMinutes = next)
                        ContentCacheKind.POPULARITY -> cache.copy(popularityMinutes = next); ContentCacheKind.COMMENTS -> cache.copy(commentMinutes = next)
                    }))
                }
                val effectiveMinutes = cache.freshMillis(cacheKind) / 60_000L
                ExperienceBudgetRow("当前更新规则", if (effectiveMinutes == 0L) "每次请求更新" else "访问时超过 $effectiveMinutes 分钟再更新")
                if (cacheKind == ContentCacheKind.POPULARITY) ExperienceHint("热度随列表与详情返回，此上限同时约束这些资料的复用时间；不为每张卡单独请求热度。")
                else if (effectiveMinutes < minutes && cache.enabled && !cache.refreshOnOpen)
                    ExperienceHint("当前同时受热度资料时效上限约束。")
                ExperienceNumber("失败结果重试间隔", cache.failureMinutes, 1, 0..60, "分钟", editor, active && expanded == "cache") {
                    change(custom.copy(contentCache = cache.copy(failureMinutes = it ?: 1)))
                }
                ExperienceNumber("快照最长保留", cache.retentionHours, 168, 1..720, "小时", editor, active && expanded == "cache") {
                    change(custom.copy(contentCache = cache.copy(retentionHours = it ?: 168)))
                }
                ExperienceNumber("列表快照上限", cache.snapshotMemoryEntries, 24, 1..64, "份", editor, active && expanded == "cache") {
                    change(custom.copy(contentCache = cache.copy(snapshotMemoryEntries = it ?: 24)))
                }
                ExperienceNumber("列表磁盘上限", cache.snapshotDiskMiB, 8, 1..128, "MB", editor, active && expanded == "cache") {
                    change(custom.copy(contentCache = cache.copy(snapshotDiskMiB = it ?: 8)))
                }
                ExperienceHint("0 分钟＝不复用已完成结果。同次正在进行的请求仍合并。开启缓存时，可先显示旧快照再更新；更新失败保留旧内容并显示原因。关闭后停止复用页面资料；旧文件可在缓存管理中清理。历史、收藏和离线章节单独保存。")
            }
            ExperienceTools("cache", "缓存与离线", active && expanded == "cache", detail, ::toggleDetail,
                custom.memoryMiB != null || custom.diskMiB != null || custom.contentCache != ContentCachePreferences(), onRestore = {
                    closeEditor(); change(custom.copy(memoryMiB = null, diskMiB = null, contentCache = ContentCachePreferences()))
                }) {
                ExperienceHint("用量 / 上限；— 表示尚未取得。内存缓存只包含可复用的解码图像，手动容量按输入保存。磁盘缓存可自动回收，离线章节独立保存；可回收内容在缓存管理中清理。")
                ExperienceHint("预读离开窗口会取消未完成任务，完成的图片按内存闲置、磁盘保留与总空间规则回收。列表快照按设置的份数与磁盘容量淘汰；阅读记录、收藏和离线章节独立保存。")
            }
        }

        if (expanded == null || expanded == "display") SettingsDisclosure("显示与交互", when (custom.refresh) {
            null -> "跟随目标"; RefreshPreference.SYSTEM -> "跟随系统"; RefreshPreference.INTERACTION -> "交互高刷"
            RefreshPreference.MANUAL -> custom.refreshHz?.let { it.toString() + " Hz 请求" } ?: "指定刷新率"
        }, Icons.Outlined.Speed, expanded == "display", { toggle("display") },
            compact = true, modified = custom.refresh != null || custom.refreshHz != null || custom.readerStatusPlacement != ReaderStatusPlacement.BOTTOM) {
            Text("阅读状态栏", style = MaterialTheme.typography.bodyMedium)
            SettingsChoices(listOf(ReaderStatusPlacement.TOP to "顶部", ReaderStatusPlacement.BOTTOM to "底部",
                ReaderStatusPlacement.HIDDEN to "隐藏"), custom.readerStatusPlacement) { change(custom.copy(readerStatusPlacement = it)) }
            val manualRates = if (platformKind == PlatformKind.ANDROID) usage.resources.supportedRefreshRates else emptyList()
            val choices = listOf<Pair<RefreshPreference?, String>>(
                null to "自动", RefreshPreference.SYSTEM to "系统", RefreshPreference.INTERACTION to "交互") +
                if (manualRates.isNotEmpty() || custom.refresh == RefreshPreference.MANUAL) listOf(RefreshPreference.MANUAL to "指定") else emptyList()
            SettingsChoices(choices, custom.refresh) { change(custom.copy(refresh = it)) }
            ExperienceReveal(custom.refresh == RefreshPreference.MANUAL) {
                if (manualRates.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    manualRates.forEach { rate ->
                        MotionTextButton(onClick = { change(custom.copy(refreshHz = rate)) },
                            enabled = active && expanded == "display" && custom.refresh == RefreshPreference.MANUAL,
                            modifier = Modifier.heightIn(min = 44.dp)) {
                            Text(rate.toString() + " Hz" + if (custom.refreshHz == rate) " ✓" else "")
                        }
                    }
                } else ExperienceHint("设备暂未提供可选刷新率，已保留指定请求")
            }
            ExperienceBudgetRow("当前刷新请求", budget.refreshHz?.let { it.toString() + " Hz" }
                ?: if (budget.preferHighRefresh) "优先高刷" else "跟随系统")
            ExperienceTools("display", "显示与交互", active && expanded == "display", detail, ::toggleDetail,
                custom.refresh != null || custom.refreshHz != null || custom.readerStatusPlacement != ReaderStatusPlacement.BOTTOM, onRestore = {
                    closeEditor(); change(custom.copy(refresh = null, refreshHz = null, readerStatusPlacement = ReaderStatusPlacement.BOTTOM))
                }) {
                ExperienceHint(if (platformKind == PlatformKind.ANDROID)
                    "交互结束后释放高刷请求；系统温度、省电和显示能力决定最终刷新率。"
                    else "屏幕刷新由系统调度；应用减少后台工作以优先交互，当前平台不提供精确刷新率控制。")
                ExperienceHint("动效遵循应用与系统的减少动态效果设置。帧工作耗时不等于屏幕 FPS。")
            }
        }

        if (expanded == null || expanded == "resources") SettingsDisclosure("设备与资源保护", if (budget.protections.isEmpty()) "按需" else budget.protections.size.toString() + " 项保护",
            Icons.Outlined.Tune, expanded == "resources", { toggle("resources") },
            compact = true, modified = custom.allowMeteredPrefetch || !custom.batteryProtection) {
            SettingsToggle("计费网络也可预取", custom.allowMeteredPrefetch, Icons.Outlined.NetworkCheck) { change(custom.copy(allowMeteredPrefetch = it)) }
            SettingsToggle("低电量减少后台工作", custom.batteryProtection, Icons.Outlined.BatterySaver) { change(custom.copy(batteryProtection = it)) }
            ExperienceTools("resources", "设备与资源保护", active && expanded == "resources", detail, ::toggleDetail,
                custom.allowMeteredPrefetch || !custom.batteryProtection, onRestore = {
                    closeEditor(); change(custom.copy(allowMeteredPrefetch = false, batteryProtection = true))
                }, extraLabel = "运行详情", extraIcon = Icons.Outlined.Speed, extra = {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("本次实际观测", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        MotionIconButton(onClick = store::refreshResources, enabled = active,
                            modifier = Modifier.size(44.dp), tooltip = "重新读取设备状态") {
                            Icon(Icons.Outlined.Refresh, "重新读取设备状态", Modifier.size(18.dp))
                        }
                    }
                    ExperienceBudgetRow("执行", usage.activeChapters.toString() + " 章 · " + usage.activeBackgroundPages + " 下载 · " + usage.activePrefetch + " 预取")
                    ExperienceBudgetRow("当前页 / 排队", usage.activeReaderImages.toString() + " / " + usage.queuedTasks)
                    ExperienceBudgetRow("解析 / 解码执行", usage.activeResolves.toString() + " / " + usage.activeDecodes)
                    ExperienceBudgetRow("页面解码 P95", usage.decodeP95Millis?.let { it.toString() + " ms" } ?: "—")
                    ExperienceBudgetRow("解码排队 P95", usage.decodeQueueP95Millis?.let { it.toString() + " ms" } ?: "—")
                    ExperienceBudgetRow("解码累计输出", experienceCacheSize(usage.decodedBytes))
                    ExperienceBudgetRow("限流来源", usage.throttledSources.toString())
                    ExperienceBudgetRow("内存 / 磁盘命中", usage.memoryHits.toString() + " / " + usage.diskHits)
                    ExperienceBudgetRow("预取复用 / 新取", usage.prefetchHits.toString() + " 次 / " + usage.prefetchedPages + " 页")
                    ExperienceBudgetRow("请求 P95", usage.requestP95Millis?.let { it.toString() + " ms" } ?: "—")
                    ExperienceBudgetRow("帧工作 P95 / P99", if (usage.frameP95Millis != null && usage.frameP99Millis != null)
                        frameMillis(usage.frameP95Millis!!) + " / " + frameMillis(usage.frameP99Millis!!)
                        else if (platformKind == PlatformKind.ANDROID) "尚无样本" else "平台未提供")
                    ExperienceBudgetRow("实际传输", experienceCacheSize(usage.transferredBytes))
                    ExperienceBudgetRow("脚本缓冲上限", experienceCacheSize(budget.materializationBytes))
                    ExperienceBudgetRow("应用堆内存", cacheUsage(usage.resources.processHeapBytes, usage.resources.processHeapLimitBytes))
                    ExperienceBudgetRow("物理内存 / 核心", (usage.resources.totalMemoryBytes?.let(::experienceCacheSize) ?: "—") + " / " + (usage.resources.processorCount?.toString() ?: "—"))
                    ExperienceBudgetRow("可用系统内存", usage.resources.availableMemoryBytes?.let(::experienceCacheSize) ?: "—")
                    ExperienceBudgetRow("可用磁盘", usage.resources.freeDiskBytes?.let(::experienceCacheSize) ?: "—")
                    ExperienceBudgetRow("处理器负载", usage.resources.cpuLoad?.let { (it * 100).roundToInt().toString() + "%" } ?: "—")
                    ExperienceBudgetRow("网络计费", when (usage.resources.metered) { true -> "计费"; false -> "非计费"; null -> "未知" })
                    ExperienceBudgetRow("电量 / 温度", (usage.resources.batteryPercent?.let { it.toString() + "%" } ?: "未知") + " / " +
                        when (usage.resources.thermalPressure) { 0 -> "正常"; 1 -> "升高"; 2 -> "较高"; 3 -> "严重"; else -> "未知" })
                    ExperienceHint("请求耗时包含排队与解码。解码累计输出不是驻留内存；分块有独立开销。帧工作耗时不等于屏幕 FPS，未知不代表正常。")
                }) {
                ExperienceHint(budget.reason)
                if (budget.protections.isNotEmpty()) ExperienceHint(budget.protections.joinToString(" · "))
                ExperienceHint("自动项按实际资源调整；手动值保留。断网、系统暂停或存储失败会显示真实原因；计费网络按你选择的开关执行。")
            }
        }
    }
}

internal class ExperienceEditor {
    var field by mutableStateOf<String?>(null)
}

/** The same retained reveal/measurement owner used by SettingsDisclosure. */
@Composable
private fun ExperienceReveal(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalMotionEnabled.current
    val reveal = animateFloatAsState(if (expanded) 1f else 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(), label = "experience-edit")
    Column(Modifier.fillMaxWidth().clipToBounds().motionInputGate(expanded)
        .layout { measurable, constraints ->
            if (reveal.value <= 0f) layout(constraints.minWidth.coerceAtLeast(0), 0) {}
            else {
                val body = measurable.measure(constraints.copy(minHeight = 0))
                layout(body.width, (body.height * reveal.value).toInt().coerceAtLeast(0)) { body.placeRelative(0, 0) }
            }
        }.graphicsLayer { alpha = reveal.value },
        verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ExperienceNumber(title: String, value: Int?, automatic: Int?, range: IntRange, unit: String,
    editor: ExperienceEditor, active: Boolean, effective: Int? = null, automaticLabel: String = "模式目标", onChange: (Int?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val selected = value ?: automatic
    val current = (selected ?: range.first).coerceAtLeast(range.first)
    // The slider is a shortcut; the numeric editor accepts every representable value.
    val sliderMaximum = maxOf(range.last, current)
    val editing = active && editor.field == title
    val focus = LocalFocusManager.current
    var draft by remember { mutableStateOf("") }
    var sliding by remember { mutableFloatStateOf(current.toFloat()) }
    var invalid by remember { mutableStateOf(false) }
    fun acceptsInput() = active && editor.field == title
    fun commit() {
        if (!acceptsInput()) return
        val parsed = draft.toIntOrNull()
        if (parsed == null || parsed < range.first) { invalid = true; return }
        val accepted = parsed
        draft = accepted.toString()
        sliding = accepted.toFloat()
        invalid = false
        onChange(accepted)
        focus.clearFocus(force = true)
    }
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 300.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
            val valueControl: @Composable () -> Unit = {
                MotionTextButton(onClick = {
                    if (active) {
                        focus.clearFocus(force = true)
                        if (editing) editor.field = null else {
                            draft = selected?.toString().orEmpty()
                            sliding = current.toFloat()
                            invalid = false
                            editor.field = title
                        }
                    }
                }, enabled = active, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.widthIn(max = maxWidth).heightIn(min = 44.dp).semantics(mergeDescendants = true) {
                        contentDescription = title + "，" + (selected?.toString()?.plus(" " + unit) ?: "自动值尚未知") +
                            (if (value == null) "，跟随" + automaticLabel else "，手动值") +
                            (effective?.let { "，当前有效值 " + it + " " + unit } ?: "") +
                            (if (editing) "，点击取消未应用的编辑" else "，点击编辑")
                    }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text((selected?.toString() ?: "—") + " " + unit, Modifier.weight(1f, fill = false),
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
                            Icon(if (editing) Icons.Outlined.Close else if (value == null) Icons.Outlined.Sync else Icons.Outlined.Edit,
                                null, Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                        }
                        if (selected != null && effective != null && effective != selected)
                            Text("当前 " + effective, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (stacked) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { valueControl() }
                }
            } else Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                valueControl()
            }
        }
        ExperienceReveal(editing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicTextField(draft, onValueChange = {
                    if (acceptsInput() && it.length <= Int.MAX_VALUE.toString().length && it.all(Char::isDigit)) { draft = it; invalid = false }
                }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit() }),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp))
                        .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 10.dp, vertical = 12.dp)
                        .semantics { contentDescription = title + "数值，单位" + unit },
                    decorationBox = { input ->
                        Box {
                            if (draft.isEmpty()) Text("输入数值", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            input()
                        }
                    })
                Text(unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                MotionIconButton(onClick = { commit() }, enabled = editing,
                    modifier = Modifier.size(44.dp), tooltip = "应用" + title) {
                    Icon(Icons.Outlined.Check, "应用" + title, Modifier.size(18.dp))
                }
                MotionIconButton(onClick = {
                    if (acceptsInput()) { editor.field = null; focus.clearFocus(force = true) }
                }, enabled = editing, modifier = Modifier.size(44.dp), tooltip = "取消未应用的" + title + "编辑") {
                    Icon(Icons.Outlined.Close, "取消编辑", Modifier.size(18.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (value == null) "跟随" + automaticLabel else "手动值 · 直接输入不设业务上限",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(automaticLabel + " " + (automatic?.toString()?.plus(" " + unit) ?: "尚未取得"),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text("当前值 " + (effective?.toString()?.plus(" " + unit) ?: "尚未取得"),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = {
                    if (acceptsInput()) {
                        editor.field = null
                        focus.clearFocus(force = true)
                        onChange(null)
                    }
                }, enabled = editing, modifier = Modifier.size(44.dp), tooltip = "恢复" + title + "跟随" + automaticLabel) {
                    Icon(Icons.Outlined.Sync, "恢复" + title + "跟随" + automaticLabel, Modifier.size(18.dp))
                }
            }
            if (invalid) Text("请输入不小于 " + range.first + " 的有效整数", style = MaterialTheme.typography.labelSmall, color = colors.error)
            MotionSlider(sliding, onValueChange = {
                if (acceptsInput()) { sliding = it; draft = it.roundToInt().toString(); invalid = false }
            }, valueRange = range.first.toFloat()..sliderMaximum.toFloat(), onValueChangeFinished = {
                if (acceptsInput()) {
                    val accepted = sliding.roundToInt().coerceAtLeast(range.first)
                    draft = accepted.toString()
                    onChange(accepted)
                }
            }, formatValue = { it.roundToInt().toString() + " " + unit },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title + "滑块" })
        }
    }
}

/** Existing icon presses and retained disclosure share one local, fixed-height tool row. */
@Composable
private fun ExperienceTools(key: String, title: String, active: Boolean, detail: String?, onDetail: (String) -> Unit,
    modified: Boolean, onRestore: () -> Unit, extraLabel: String = "更多参数",
    extraIcon: ImageVector = Icons.Outlined.Tune, extra: (@Composable ColumnScope.() -> Unit)? = null,
    help: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val helpOpen = detail == key + "-help"
    val extraOpen = detail == key + "-advanced"
    Column(Modifier.fillMaxWidth().motionInputGate(active)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            MotionIconButton(onClick = { onDetail(key + "-help") }, enabled = active,
                modifier = Modifier.size(44.dp).semantics { stateDescription = if (helpOpen) "已展开" else "已收起" },
                tooltip = title + "说明") {
                Icon(Icons.Outlined.Info, title + "说明", Modifier.size(18.dp),
                    tint = if (helpOpen) colors.onSurface else colors.onSurfaceVariant)
            }
            if (extra != null) MotionIconButton(onClick = { onDetail(key + "-advanced") }, enabled = active,
                modifier = Modifier.size(44.dp).semantics { stateDescription = if (extraOpen) "已展开" else "已收起" },
                tooltip = title + "：" + extraLabel) {
                Icon(extraIcon, title + "：" + extraLabel, Modifier.size(18.dp),
                    tint = if (extraOpen) colors.onSurface else colors.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            MotionSwap(modified, Modifier.size(44.dp)) { shown ->
                if (shown) MotionIconButton(onClick = onRestore, enabled = active && modified,
                    modifier = Modifier.size(44.dp), tooltip = "恢复" + title + "默认设置") {
                    Icon(Icons.Outlined.RestartAlt, "恢复" + title + "默认设置", Modifier.size(18.dp),
                        tint = colors.onSurfaceVariant)
                }
            }
        }
        ExperienceReveal(extraOpen) { extra?.invoke(this) }
        ExperienceReveal(helpOpen, help)
    }
}

private fun experienceReadingCustom(value: ExperienceOverrides) =
    value.prefetchMode != PrefetchMode.SMART || value.ahead != null || value.behind != null ||
        value.prefetchRequests != null || value.prefetchMiB != null || !value.crossChapter ||
        value.readerRequests != null || value.resolveRequests != null || !value.browseWarmup

private fun experienceDownloadCustom(value: ExperienceOverrides) =
    value.downloadPages != null || value.sourcePages != null || value.chapters != null ||
        !value.yieldToReading || !value.allowBackgroundDownloads

private fun sourceRecoveryLabel(value: SourceRecoveryMode) = when (value) {
    SourceRecoveryMode.OFF -> "按需切源"
    SourceRecoveryMode.BALANCED -> "稳健预热"
    SourceRecoveryMode.AGGRESSIVE -> "积极预热"
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
private fun cacheUsage(used: Long?, limit: Long?) = (used?.let(::experienceCacheSize) ?: "—") + " / " + (limit?.let(::experienceCacheSize) ?: "—")
private fun frameMillis(value: Double) = ((value * 10).roundToInt() / 10.0).toString() + " ms"
internal fun experienceModeLabel(mode: ExperienceMode) = when (mode) { ExperienceMode.AUTO -> "自动"; ExperienceMode.ECO -> "节能"; ExperienceMode.PERFORMANCE -> "流畅优先" }
internal fun experienceCacheSize(bytes: Long): String = when {
    bytes >= 1024L * EXPERIENCE_MIB -> ((bytes.toDouble() / (1024L * EXPERIENCE_MIB) * 10).roundToInt() / 10.0).toString() + " GB"
    bytes >= EXPERIENCE_MIB -> ((bytes.toDouble() / EXPERIENCE_MIB * 10).roundToInt() / 10.0).toString() + " MB"
    else -> (bytes.coerceAtLeast(0L) / 1024L).toString() + " KB"
}
