package dev.mangaflow.ui

import dev.mangaflow.background.ApplicationOperations
import dev.mangaflow.background.ApplicationOperationPhase
import dev.mangaflow.background.UserOperationKeys
import dev.mangaflow.background.StorageCleanupResult
import dev.mangaflow.background.StorageCleanupFailure
import dev.mangaflow.background.clearStorageCache

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.MangaApplicationServices
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.reader.ReaderDialogSurface
import dev.mangaflow.reader.panels.*
import dev.mangaflow.storage.*
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class StorageClearPlan(val items: List<StoredCacheItem>, val legacy: Boolean,
    val estimatedBytes: Long, val label: String, val legacyRevision: Long?, val legacyStamp: String?)
private data class StoragePane(val area: CacheArea?, val book: String?, val source: String?, val review: StorageClearPlan?, val snapshot: StorageCacheSnapshot?)

/** One retained panel; overview, drill-down and review share the same header and scroll owner. */
@Composable
internal fun StorageManagementDialog(visible: Boolean, backdrop: HazeState, library: LibraryStore,
    repository: dev.mangaflow.data.UnifiedMangaRepository?,
    active: Boolean = true,
    onDismiss: () -> Unit, onClosed: () -> Unit, onDownloads: () -> Unit, onModels: () -> Unit,
    onAnimeDownloads: (() -> Unit)? = null,
) {
    val context = LocalMangaContext.current
    val store = remember(context) { StorageCacheStore.get(context) }
    val experience = remember(context) { ExperiencePolicyStore.get(context) }
    val scope = rememberCoroutineScope()
    val downloads = remember(context) { DownloadStore.get(context) }
    val entries by downloads.entries.collectAsStateWithLifecycle()
    val animeDownloads = remember(context) { MangaApplicationServices.get(context).animeDownloads }
    val animeDownloadState by animeDownloads.state.collectAsStateWithLifecycle()
    val models = remember(context) { PanelModelStore.get(context) }
    val runtimes = remember(context) { PanelRuntimeStore.get(context) }
    val modelStates by models.states.collectAsStateWithLifecycle()
    val runtimeStates by runtimes.states.collectAsStateWithLifecycle()
    val favorites by library.favorites.collectAsStateWithLifecycle()
    val history by library.history.collectAsStateWithLifecycle()
    val operations = remember(context) { ApplicationOperations.get(context) }
    val operationStates by operations.state.collectAsStateWithLifecycle()
    val clearOperation = remember(operationStates) { operations.find(UserOperationKeys.CACHE) }
    val colors = MaterialTheme.colorScheme
    var snapshot by remember { mutableStateOf<StorageCacheSnapshot?>(null) }
    var area by remember { mutableStateOf<CacheArea?>(null) }
    var book by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var details by remember { mutableStateOf<Map<String, MangaDetail>>(emptyMap()) }
    var plan by remember { mutableStateOf<StorageClearPlan?>(null) }
    val busy = clearOperation?.active == true
    var loading by remember { mutableStateOf(false) }
    var clearingMemory by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var offlineBytes by remember { mutableStateOf<Long?>(null) }
    var animeOfflineBytes by remember { mutableStateOf<Long?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val imageLoader = remember(context) { SingletonImageLoader.get(imagePlatformContext(context)) }
    val known = remember(favorites, history, entries, details) {
        (details.values.map { it.manga } + history.map { it.manga } + entries.map { it.manga } + favorites)
            .associateBy { it.id }
    }
    LaunchedEffect(visible, active, revision, clearOperation?.id, clearOperation?.phase, animeDownloadState.loading) {
        if (!visible || !active || busy) return@LaunchedEffect
        loading = true
        try {
            val observed = store.snapshot()
            val ids = observed.items.map { it.resource.mangaId }.filter { it.isNotBlank() }.distinct()
            details = buildMap { for (id in ids) ReaderCatalogStore(context).read(id)?.let { put(id, it) } }
            // Title reads can restore owner memory; issue the review after those reads settle.
            snapshot = store.snapshot()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { notice = "用量读取未完成，点按刷新重试" }
        finally { loading = false }
    }
    LaunchedEffect(visible, active, revision, entries.map { it.id to it.status },
        animeDownloadState.entries.map { it.id to it.status }, animeDownloadState.loading) {
        if (!visible || !active) return@LaunchedEffect
        try { offlineBytes = withContext(Dispatchers.IO) { downloads.storageBytes() } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { offlineBytes = null; notice = "漫画离线用量未能读取，点按刷新重试" }
        try { animeOfflineBytes = animeDownloads.storageBytes() }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { animeOfflineBytes = null; notice = "番剧离线用量未能读取，点按刷新重试" }
    }
    fun back() {
        when {
            plan != null -> plan = null
            source != null -> { source = null }
            book != null -> { book = null }
            area != null -> { area = null; selected = emptySet() }
            else -> onDismiss()
        }
    }
    val scoped = snapshot?.items.orEmpty().filter { item ->
        (area == null || item.resource.area == area) && (book == null || item.storageBookId() == book) &&
            (source == null || item.resource.sourceId == source)
    }
    val selectedInScope = scoped.filter { it.resource.id in selected }
    val chosen = if (selectedInScope.isEmpty()) scoped else selectedInScope
    val reclaimable = chosen.filterNot { it.protected }
    val includeLegacy = area == null && book == null && selected.isEmpty() && snapshot?.legacyReclaimable == true
    val scopeBytes = reclaimable.sumOf { it.bytes } +
        (if (includeLegacy) snapshot?.coilUnclassifiedBytes ?: 0L else 0L)
    val hasSelection = selectedInScope.isNotEmpty()
    val bookTitle = book?.let { storageBookTitle(it, area, scoped, known) }
    val sourceTitle = source?.let { id -> animeDownloadState.entries.firstOrNull { it.sourceId == id }
        ?.sourceName?.takeIf { it.isNotBlank() } ?: id.ifBlank { "未归类来源" } }
    val scopeLabel = if (hasSelection) "已选 ${selectedInScope.size} 项"
        else if (sourceTitle != null) "$bookTitle · $sourceTitle" else bookTitle ?: area?.label ?: "全部缓存"
    val scopedArea = area
    val occupiedBytes = snapshot?.let { inventory ->
        when {
            hasSelection -> selectedInScope.sumOf { it.bytes }
            book != null -> scoped.sumOf { it.bytes }
            scopedArea != null -> inventory.areaBytes(scopedArea)
            offlineBytes != null && animeOfflineBytes != null -> inventory.totalBytes + offlineBytes!! + animeOfflineBytes!! +
                modelStates.values.sumOf { it.bytes } + runtimeStates.values.sumOf { it.bytes }
            else -> null
        }
    }
    fun previewClear() {
        if (busy || loading || scopeBytes <= 0L) return
        plan = StorageClearPlan(reclaimable.toList(), includeLegacy, scopeBytes,
            scopeLabel, snapshot?.legacyRevision, snapshot?.legacyStamp)
    }
    fun applyClear(reviewed: StorageClearPlan) {
        if (operations.find(UserOperationKeys.CACHE)?.active == true) return
        val handle = operations.clearStorageCache(store, imageLoader, reviewed.items,
            reviewed.legacy, reviewed.legacyRevision, reviewed.legacyStamp)
        if (handle.accepted) {
            plan = null
            selected = emptySet()
            notice = "已在后台清理，可关闭此页"
        }
    }
    LaunchedEffect(clearOperation?.id, clearOperation?.phase, visible, active) {
        val operation = clearOperation ?: return@LaunchedEffect
        if (!visible || !active || operation.active) return@LaunchedEffect
        fun showResult(result: StorageCleanupResult) {
            if (!result.accountingComplete) {
                notice = "可能已释放部分内容，请刷新用量核对"
                return
            }
            notice = "已释放 ${storageBytes(result.freedBytes)}" + when {
                result.failed > 0 -> " · 部分内容未清理，请重试"
                result.skipped > 0 -> " · 使用中的内容已保留"
                else -> ""
            }
        }
        when (operation.phase) {
            ApplicationOperationPhase.SUCCEEDED, ApplicationOperationPhase.FAILED -> {
                try {
                    val result = operations.result<StorageCleanupResult>(operation.id)
                    if (result != null) showResult(result.await()) else notice = operation.message
                } catch (failure: StorageCleanupFailure) {
                    showResult(failure.result)
                } catch (cancel: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    notice = operation.message
                } catch (_: Exception) { notice = operation.message }
            }
            ApplicationOperationPhase.CANCELLED -> notice = "清理已取消，可能已释放部分内容；正在刷新实际用量"
            else -> notice = operation.message
        }
        if (!operation.statusDurable) notice = notice.orEmpty() + " · 操作状态未保存"
        // This small outcome remains available across panel/page reopening; a new CACHE
        // operation or the registry's bounded terminal retention releases it.
    }
    MotionDialog(visible, onDismissRequest = ::back,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 560.dp, onClosed = onClosed) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            val maximum = minOf(maxHeight, LocalMangaWindow.current.height * .9f, 680.dp)
            ReaderDialogSurface(Modifier.fillMaxWidth(), backdrop) {
                Column(Modifier.fillMaxWidth().heightIn(max = maximum).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (area != null || plan != null) MotionIconButton(::back,
                            modifier = Modifier.size(44.dp), tooltip = "返回") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                        }
                        PreferenceHeading(if (plan != null) "清理预览" else bookTitle
                            ?: area?.label ?: "存储管理", Modifier.weight(1f))
                        MotionIconButton({ revision++ }, enabled = !busy && !loading && plan == null, modifier = Modifier.size(44.dp), tooltip = "刷新用量") {
                            MotionSwap(loading, Modifier.size(18.dp)) { working ->
                                if (working) MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                                else Icon(Icons.Outlined.Refresh, "刷新用量", Modifier.size(18.dp))
                            }
                        }
                        MotionIconButton(onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭存储管理") {
                            Icon(Icons.Outlined.Close, "关闭存储管理", Modifier.size(20.dp))
                        }
                    }
                    if (busy) Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                        Text(clearOperation?.message ?: "后台清理中 · 可关闭此页", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (clearOperation?.cancellable == true) MotionTextButton(
                            onClick = { operations.cancel(clearOperation.id) }) { Text("取消") }
                    }
                    val review = plan
                    if (review == null) {
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)
                            .background(colors.surfaceContainerLow, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(when {
                                        hasSelection -> scopeLabel
                                        area == null && book == null -> "本机已用"
                                        else -> "已用"
                                    }, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                    Text(occupiedBytes?.let(::storageBytes) ?: "—", style = mangaMetricValueStyle(28.sp, 34.sp),
                                        color = colors.onSurface)
                                    Text(if (snapshot == null) if (loading) "正在统计…" else "用量未就绪"
                                        else "可释放 ${storageBytes(scopeBytes)}",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                MotionButton(::previewClear, enabled = !busy && !loading && scopeBytes > 0L) {
                                    Text(if (hasSelection) "清理所选" else "清理缓存")
                                }
                            }
                            if (occupiedBytes != null && occupiedBytes > 0L) {
                                val share = (scopeBytes.toDouble() / occupiedBytes).coerceIn(0.0, 1.0).toFloat()
                                Spacer(Modifier.height(8.dp))
                                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                                    .background(colors.onSurface.copy(alpha = .12f))) {
                                    if (share > 0f) Spacer(Modifier.fillMaxWidth(share).fillMaxHeight()
                                        .background(colors.primary, CircleShape))
                                }
                            }
                        }
                    }
                    MotionTabContent(StoragePane(area, book, source, plan, snapshot),
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                        contentKey = { listOf(it.area, it.book, it.source, it.review != null) },
                        order = { if (it.review != null) 4 else if (it.source != null) 3 else if (it.book != null) 2 else if (it.area != null) 1 else 0 },
                        fillContent = false, soft = true) { shown ->
                    val paneArea = shown.area
                    val paneBook = shown.book
                    val paneSource = shown.source
                    val paneReview = shown.review
                    val paneSnapshot = shown.snapshot
                    val paneItems = paneSnapshot?.items.orEmpty().filter { item ->
                        (paneArea == null || item.resource.area == paneArea) &&
                            (paneBook == null || item.storageBookId() == paneBook) &&
                            (paneSource == null || item.resource.sourceId == paneSource)
                    }
                    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 8.dp)) {
                        if (paneReview != null) {
                            item("review") {
                                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(paneReview.label, style = MaterialTheme.typography.titleSmall)
                                    Text("预计释放 ${storageBytes(paneReview.estimatedBytes)}", style = MaterialTheme.typography.headlineSmall)
                                    paneReview.items.groupBy { it.resource.area }.forEach { (kind, items) ->
                                        StorageLine(kind.label, "${items.size} 项", storageBytes(items.sumOf { it.bytes }))
                                    }
                                    if (paneReview.legacy) StorageLine("旧版图片缓存", "无可靠作品归属，空闲时清理", storageBytes(paneSnapshot?.coilUnclassifiedBytes ?: 0L))
                                    Text("当前阅读、正在播放或准备的番剧片段、使用中的增强结果与处理文件会保留。漫画与番剧离线原片、下载断点和模型由各自入口管理。",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        } else if (paneArea == null) {
                            items(CacheArea.entries, key = { it.name }) { kind ->
                                StorageNavigationRow(kind.label, when (kind) {
                                    CacheArea.ANIME -> "按作品与剧集管理 · 使用中的片段保留"
                                    CacheArea.READING -> "原图 · 脚本与解密图片"
                                    CacheArea.ARTWORK -> "封面 · 用户头像"
                                    CacheArea.DATA -> "推荐 · 榜单 · 目录 · 评分与评论"
                                    CacheArea.PANELS -> "识别坐标，可重新生成"
                                    CacheArea.ENHANCEMENT -> "增强衍生内容 · 原始下载独立保留"
                                }, storageBytes(paneSnapshot?.areaBytes(kind) ?: 0L), areaIcon(kind), true) {
                                    area = kind; selected = emptySet()
                                }
                            }
                            if ((paneSnapshot?.coilUnclassifiedBytes ?: 0L) > 0L) item("legacy") {
                                StorageLine("旧版图片缓存", if (paneSnapshot?.legacyReclaimable == true) "归属会在再次使用后补齐" else "正在使用，稍后可清理",
                                    storageBytes(paneSnapshot?.coilUnclassifiedBytes ?: 0L))
                            }
                            item("offline") { StorageNavigationRow("漫画离线下载", "独立保存 · 不自动回收",
                                offlineBytes?.let(::storageBytes) ?: "—", Icons.Outlined.Download, !busy, onDownloads) }
                            item("anime-offline") { StorageNavigationRow("番剧离线下载", "原视频与下载中内容 · 不自动回收",
                                animeOfflineBytes?.let(::storageBytes) ?: "—", Icons.Outlined.Download,
                                !busy && onAnimeDownloads != null, { onAnimeDownloads?.invoke() }) }
                            if (PanelReadingAvailable) item("models") { StorageNavigationRow("模型与组件", "按需下载", storageBytes(modelStates.values.sumOf { it.bytes } +
                                runtimeStates.values.sumOf { it.bytes }), Icons.Outlined.Extension, !busy, onModels) }
                            item("backend-census") {
                                val inventory = paneSnapshot
                                if (inventory != null) {
                                    val buckets = listOf(
                                        "原图与封面" to (inventory.items.filter { it.resource.backend == CacheBackend.COIL }
                                            .sumOf { it.bytes } + inventory.coilUnclassifiedBytes),
                                        "脚本素材" to inventory.items.filter { it.resource.backend == CacheBackend.SCRIPT }.sumOf { it.bytes },
                                        "JM 解密" to inventory.items.filter { it.resource.backend == CacheBackend.JM }.sumOf { it.bytes },
                                        "分镜" to inventory.items.filter { it.resource.backend == CacheBackend.PANEL }.sumOf { it.bytes },
                                        "番剧" to inventory.areaBytes(CacheArea.ANIME),
                                        "目录" to (inventory.items.filter { it.resource.backend == CacheBackend.CATALOG }
                                            .sumOf { it.bytes } + inventory.catalogBytes),
                                        "增强" to inventory.areaBytes(CacheArea.ENHANCEMENT),
                                        "数据" to (inventory.items.filter { it.resource.backend == CacheBackend.AUXILIARY && it.resource.area == CacheArea.DATA }
                                            .sumOf { it.bytes } + inventory.auxiliaryBytes),
                                    )
                                    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text("存储构成", style = MaterialTheme.typography.labelMedium,
                                            color = colors.onSurfaceVariant)
                                        buckets.filter { it.second > 0L }.chunked(3).forEach { slice ->
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                slice.forEach { (name, bytes) ->
                                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                                        Text(name, style = MaterialTheme.typography.labelSmall,
                                                            color = colors.onSurfaceVariant)
                                                        Text(storageBytes(bytes), style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                                repeat(3 - slice.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        }
                                    }
                                }
                            }
                            item("budget") {
                                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("索引与写入 ${storageBytes((paneSnapshot?.indexBytes ?: 0L) + (paneSnapshot?.temporaryBytes ?: 0L))}",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Text("缓存预算 ${storageBytes(paneSnapshot?.budgetBytes ?: 0L)} · 各类共享",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("内存图片 ${imageLoader.memoryCache?.size?.let(::storageBytes) ?: "—"}", Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        MotionTextButton(onClick = {
                                            if (!active || !visible || clearingMemory) return@MotionTextButton
                                            clearingMemory = true
                                            scope.launch {
                                                try {
                                                    val released = experience.clearDecodedMemory().await()
                                                    notice = "已释放 ${storageBytes(released)} 缓存引用"
                                                } catch (cancel: CancellationException) { throw cancel }
                                                catch (_: Exception) { notice = "内存清理未完成，请重试" }
                                                finally { clearingMemory = false }
                                            }
                                        }, enabled = active && visible && !busy && !clearingMemory,
                                            modifier = Modifier.heightIn(min = 44.dp)) {
                                            Text(if (clearingMemory) "正在清理" else "清理解码缓存")
                                        }
                                    }
                                    if (paneSnapshot?.pending == true || (paneSnapshot?.totalBytes ?: 0L) > (paneSnapshot?.budgetBytes ?: Long.MAX_VALUE))
                                        Text("暂不能清理的内容已保留，条件允许后继续回收", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        } else if (paneArea == CacheArea.DATA) {
                            item("data") { StorageLine("资料与列表", "离线目录受保护", storageBytes(paneSnapshot?.areaBytes(CacheArea.DATA) ?: 0L)) }
                        } else if (paneBook == null) {
                            val groups = paneItems.groupBy { it.storageBookId() }.entries.sortedByDescending { it.value.sumOf { row -> row.bytes } }
                            if (groups.isEmpty()) item("empty") { StorageEmpty(loading) }
                            items(groups, key = { it.key }) { group ->
                                val anime = group.value.any { it.isAnimeStorage() }
                                val manga = if (anime) null else known[group.key]
                                val chapters = group.value.map { it.storagePartId() }.filter { it.isNotBlank() }.distinct().size
                                val sources = group.value.map { it.resource.sourceId }.filter { it.isNotBlank() }.distinct().size
                                val detail = when {
                                    anime -> listOfNotNull(chapters.takeIf { it > 0 }?.let { "$it 集" },
                                        sources.takeIf { it > 0 }?.let { "$it 个来源" }).joinToString(" · ").ifBlank { "缓存归属待恢复" }
                                    paneArea == CacheArea.ENHANCEMENT -> "${group.value.size} 项增强与处理文件"
                                    chapters > 0 -> "$chapters 章 · ${group.value.size} 张"
                                    else -> "${group.value.size} 项"
                                }
                                val held = group.value.filter { it.protected }.sumOf { it.bytes }
                                StorageBookRow(manga, repository, storageBookTitle(group.key, paneArea, group.value, known),
                                    detail + if (held > 0L) " · 保留 ${storageBytes(held)}" else "",
                                    storageBytes(group.value.sumOf { it.bytes }), group.value.filterNot { it.protected }.let { rows -> rows.isNotEmpty() && rows.all { it.resource.id in selected } },
                                    group.value.all { it.protected }, onSelect = {
                                        val ids = group.value.filterNot { it.protected }.map { it.resource.id }.toSet()
                                        selected = if (ids.all { it in selected }) selected - ids else selected + ids
                                    }, onOpen = { book = group.key; source = null })
                            }
                        } else if (paneSource == null && paneItems.any { it.isAnimeStorage() } &&
                            paneItems.map { it.resource.sourceId }.distinct().size > 1) {
                            val groups = paneItems.groupBy { it.resource.sourceId }.entries
                                .sortedByDescending { it.value.sumOf { row -> row.bytes } }
                            items(groups, key = { it.key }) { group ->
                                val title = animeDownloadState.entries.firstOrNull { it.sourceId == group.key }
                                    ?.sourceName?.takeIf { it.isNotBlank() } ?: group.key.ifBlank { "未归类来源" }
                                val episodes = group.value.map { it.storagePartId() }.filter { it.isNotBlank() }.distinct().size
                                val held = group.value.filter { it.protected }.sumOf { it.bytes }
                                val detail = (if (episodes > 0) "$episodes 集" else "未归类片段") +
                                    if (held > 0L) " · 保留 ${storageBytes(held)}" else ""
                                StorageBookRow(null, null, title, detail, storageBytes(group.value.sumOf { it.bytes }),
                                    group.value.filterNot { it.protected }.let { rows -> rows.isNotEmpty() && rows.all { it.resource.id in selected } },
                                    group.value.all { it.protected }, onSelect = {
                                        val ids = group.value.filterNot { it.protected }.map { it.resource.id }.toSet()
                                        selected = if (ids.all { it in selected }) selected - ids else selected + ids
                                    }, onOpen = { source = group.key })
                            }
                        } else {
                            val groups = paneItems.groupBy { it.storagePartId() to it.resource.sourceId }.entries
                                .sortedBy { details[paneBook]?.chapters?.firstOrNull { ch -> ch.id == it.key.first }?.index ?: Int.MAX_VALUE }
                            items(groups, key = { it.key.first + "\u0000" + it.key.second }) { group ->
                                val chapter = details[paneBook]?.chapters?.firstOrNull { it.id == group.key.first || it.variants.any { variant -> variant.storageId == group.key.first } }
                                val anime = group.value.any { it.isAnimeStorage() }
                                val title = if (anime) {
                                    group.value.firstNotNullOfOrNull { row ->
                                        row.animeEntry?.episodeTitle?.takeIf { it.isNotBlank() }
                                            ?: row.auxiliaryEntry?.partTitle?.takeIf { it.isNotBlank() }
                                    } ?: if (group.key.first.isBlank()) "未归类番剧片段" else "已缓存剧集"
                                } else {
                                    chapter?.title ?: if (paneArea == CacheArea.ENHANCEMENT) "增强与处理文件"
                                        else if (group.key.first.isBlank()) "未归类图片" else "缓存章节"
                                }
                                val source = (if (anime) animeDownloadState.entries.firstOrNull { it.sourceId == group.key.second }?.sourceName
                                    else repository?.sources?.firstOrNull { it.id == group.key.second }?.name)
                                    ?.takeIf { it.isNotBlank() } ?: group.key.second.ifBlank { "未归类来源" }
                                val temporary = group.value.filter { it.auxiliaryEntry?.temporary == true }.sumOf { it.bytes }
                                val held = group.value.filter { it.protected }.sumOf { it.bytes }
                                val detail = "$source · ${group.value.size} ${if (anime || paneArea == CacheArea.ENHANCEMENT) "项" else "张"}" +
                                    (if (temporary > 0L) " · 临时 ${storageBytes(temporary)}" else "") +
                                    (if (held > 0L && group.value.any { !it.protected }) " · 保留 ${storageBytes(held)}" else "")
                                StorageSelectionRow(title, detail, storageBytes(group.value.sumOf { it.bytes }),
                                    group.value.filterNot { it.protected }.let { rows -> rows.isNotEmpty() && rows.all { it.resource.id in selected } },
                                    group.value.all { it.protected }) {
                                    val ids = group.value.filterNot { it.protected }.map { it.resource.id }.toSet()
                                    selected = if (ids.all { it in selected }) selected - ids else selected + ids
                                }
                            }
                        }
                        notice?.let { message -> item("notice") { Text(message, Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) } }
                    }
                    }
                    if (review != null) Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        MotionButton({ applyClear(review) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            if (busy) MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp) else Text("确认释放")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageNavigationRow(title: String, detail: String, size: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, interactionSource = interaction,
        indication = null, onClick = onClick).motionPress(interaction, enabled)
        .padding(vertical = 10.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsRowIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(size, style = mangaMetricValueStyle(), color = MaterialTheme.colorScheme.onSurface)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StorageLine(title: String, detail: String, size: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(size, style = mangaMetricValueStyle(), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StorageBookRow(manga: Manga?, repository: dev.mangaflow.data.UnifiedMangaRepository?, title: String, detail: String, size: String, selected: Boolean, protected: Boolean,
    onSelect: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StorageSelect(selected, protected, onSelect)
        val interaction = remember { MutableInteractionSource() }
        Row(Modifier.weight(1f).clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .motionPress(interaction).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (manga != null && repository != null) MangaCover(manga, repository,
                Modifier.size(32.dp, 44.dp).clip(RoundedCornerShape(5.dp)), ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(size, style = mangaMetricValueStyle(), color = MaterialTheme.colorScheme.onSurface)
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp))
        }
    }
}

@Composable
private fun StorageSelectionRow(title: String, detail: String, size: String, selected: Boolean, protected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StorageSelect(selected, protected, onSelect)
        Box(Modifier.weight(1f)) { StorageLine(title, if (protected) "$detail · 受保护" else detail, size) }
    }
}

@Composable
private fun StorageSelect(selected: Boolean, protected: Boolean, onSelect: () -> Unit) {
    MotionIconButton(onSelect, enabled = !protected, modifier = Modifier.size(44.dp), tooltip = if (protected) "受保护，暂不能清理" else if (selected) "取消选择" else "选择清理") {
        Icon(if (protected) Icons.Outlined.Lock else if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            if (protected) "受保护" else if (selected) "已选择" else "未选择", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StorageEmpty(loading: Boolean) { Text(if (loading) "正在统计…" else "暂无缓存", Modifier.padding(vertical = 24.dp),
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

private fun StoredCacheItem.isAnimeStorage(): Boolean = resource.area == CacheArea.ANIME ||
    resource.auxiliaryOwnerId in setOf("enhanced-video-v1", "anime-online-windows") || auxiliaryEntry?.content == "anime"

private fun StoredCacheItem.storageBookId(): String = if (isAnimeStorage())
    ANIME_BOOK_PREFIX + (animeEntry?.subjectId?.takeIf { it.isNotBlank() } ?: auxiliaryEntry?.workId.orEmpty())
    else resource.mangaId.ifBlank { UNCLASSIFIED }

private fun StoredCacheItem.storagePartId(): String = animeEntry?.episodeId?.takeIf { it.isNotBlank() }
    ?: auxiliaryEntry?.partId?.takeIf { it.isNotBlank() } ?: resource.chapterId

private fun storageBookTitle(id: String, area: CacheArea?, rows: List<StoredCacheItem>, known: Map<String, Manga>): String {
    if (id.startsWith(ANIME_BOOK_PREFIX) || rows.any { it.isAnimeStorage() }) return rows.firstNotNullOfOrNull { row ->
        row.animeEntry?.subjectTitle?.takeIf { it.isNotBlank() }
            ?: row.auxiliaryEntry?.workTitle?.takeIf { it.isNotBlank() }
    } ?: if (id == ANIME_BOOK_PREFIX) "未归类番剧" else "已缓存番剧"
    return known[id]?.title ?: if (id == UNCLASSIFIED) {
        if (area == CacheArea.ENHANCEMENT) "未归类增强内容" else "未归类图片"
    } else "已缓存作品"
}

private fun areaIcon(area: CacheArea) = when (area) {
    CacheArea.ANIME -> Icons.Outlined.PlayCircleOutline
    CacheArea.READING -> Icons.Outlined.Image
    CacheArea.ARTWORK -> Icons.Outlined.AccountCircle
    CacheArea.DATA -> Icons.Outlined.Storage
    CacheArea.PANELS -> Icons.Outlined.CropFree
    CacheArea.ENHANCEMENT -> Icons.Outlined.AutoAwesome
}
internal fun storageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${bytes * 10L / (1024L * 1024 * 1024) / 10.0} GB"
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
private const val UNCLASSIFIED = "\u0000unclassified"
private const val ANIME_BOOK_PREFIX = "\u0000anime:"
