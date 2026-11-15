package dev.mangaflow.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaDetail
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.reader.ReaderDialogSurface
import dev.mangaflow.reader.panels.*
import dev.mangaflow.storage.*
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class StorageClearPlan(val items: List<StoredCacheItem>, val legacy: Boolean, val data: Boolean,
    val estimatedBytes: Long, val label: String)
private data class StoragePane(val area: CacheArea?, val book: String?, val review: StorageClearPlan?, val snapshot: StorageCacheSnapshot?)

/** One retained panel; overview, drill-down and review share the same header and scroll owner. */
@Composable
internal fun StorageManagementDialog(visible: Boolean, backdrop: HazeState, library: LibraryStore,
    repository: dev.mangaflow.data.UnifiedMangaRepository?,
    clearBrowse: suspend () -> Unit, clearMetadata: suspend () -> Unit,
    onDismiss: () -> Unit, onClosed: () -> Unit, onDownloads: () -> Unit, onModels: () -> Unit,
) {
    val context = LocalMangaContext.current
    val store = remember(context) { StorageCacheStore.get(context) }
    val downloads = remember(context) { DownloadStore.get(context) }
    val entries by downloads.entries.collectAsStateWithLifecycle()
    val models = remember(context) { PanelModelStore.get(context) }
    val runtimes = remember(context) { PanelRuntimeStore.get(context) }
    val modelStates by models.states.collectAsStateWithLifecycle()
    val runtimeStates by runtimes.states.collectAsStateWithLifecycle()
    val favorites by library.favorites.collectAsStateWithLifecycle()
    val history by library.history.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var snapshot by remember { mutableStateOf<StorageCacheSnapshot?>(null) }
    var area by remember { mutableStateOf<CacheArea?>(null) }
    var book by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var details by remember { mutableStateOf<Map<String, MangaDetail>>(emptyMap()) }
    var plan by remember { mutableStateOf<StorageClearPlan?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var offlineBytes by remember { mutableLongStateOf(0L) }
    var revision by remember { mutableIntStateOf(0) }
    val imageLoader = remember(context) { SingletonImageLoader.get(imagePlatformContext(context)) }
    val known = remember(favorites, history, entries, details) {
        (details.values.map { it.manga } + history.map { it.manga } + entries.map { it.manga } + favorites)
            .associateBy { it.id }
    }
    LaunchedEffect(visible, revision) {
        if (!visible || busy) return@LaunchedEffect
        loading = true
        try {
            snapshot = store.snapshot()
            val ids = snapshot?.items.orEmpty().map { it.resource.mangaId }.filter { it.isNotBlank() }.distinct()
            details = buildMap { for (id in ids) ReaderCatalogStore(context).read(id)?.let { put(id, it) } }
            offlineBytes = withContext(Dispatchers.IO) { downloads.storageBytes() }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { notice = "用量读取未完成，点按刷新重试" }
        finally { loading = false }
    }
    fun back() {
        when {
            plan != null -> plan = null
            book != null -> { book = null }
            area != null -> { area = null; selected = emptySet() }
            else -> onDismiss()
        }
    }
    val scoped = snapshot?.items.orEmpty().filter { item ->
        (area == null || item.resource.area == area) && (book == null || item.resource.mangaId.ifBlank { UNCLASSIFIED } == book)
    }
    val selectedInScope = scoped.filter { it.resource.id in selected }
    val chosen = if (selectedInScope.isEmpty()) scoped else selectedInScope
    val reclaimable = chosen.filterNot { it.protected }
    val includeLegacy = area == null && book == null && selected.isEmpty() && snapshot?.legacyReclaimable == true
    val includeData = (area == null || area == CacheArea.DATA) && selected.isEmpty()
    val scopeBytes = reclaimable.sumOf { it.bytes } +
        (if (includeLegacy) snapshot?.coilUnclassifiedBytes ?: 0L else 0L) +
        (if (includeData) (snapshot?.auxiliaryBytes ?: 0L) + (snapshot?.catalogReclaimableBytes ?: 0L) else 0L)
    fun previewClear() {
        if (busy || loading || scopeBytes <= 0L) return
        plan = StorageClearPlan(reclaimable.toList(), includeLegacy, includeData, scopeBytes,
            if (selectedInScope.isNotEmpty()) "已选内容" else book?.let { known[it]?.title ?: "当前作品" } ?: area?.label ?: "可回收缓存")
    }
    fun applyClear(reviewed: StorageClearPlan) {
        if (busy) return
        busy = true
        scope.launch {
            var freed = 0L
            try {
                val result = store.clear(reviewed.items, reviewed.legacy)
                freed += result.freedBytes
                if (reviewed.data) {
                    val before = store.snapshot()
                    clearBrowse()
                    clearMetadata()
                    val after = store.snapshot()
                    freed += ((before.auxiliaryBytes + before.catalogBytes) -
                        (after.auxiliaryBytes + after.catalogBytes)).coerceAtLeast(0L)
                }
                // Remove only the decoded variants of reviewed, unprotected originals.
                val keys = result.removedKeys
                imageLoader.memoryCache?.let { memory -> memory.keys.filter {
                    it.key in keys || it.key.removePrefix("manga-cover:") in keys
                }.forEach { memory.remove(it) } }
                snapshot = store.snapshot()
                selected = emptySet()
                plan = null
                notice = "已释放 ${storageBytes(freed)}" +
                    if (result.failed > 0) " · 部分内容未清理，请重试" else if (result.skipped > 0) " · 使用中的内容已保留" else ""
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                notice = if (freed > 0) "已释放 ${storageBytes(freed)} · 其余内容未清理，请刷新重试" else "清理未完成，请刷新重试"
                plan = null
            } finally { busy = false; revision++ }
        }
    }
    MotionDialog(visible, onDismissRequest = { if (!busy) back() },
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 560.dp, onClosed = onClosed) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            val maximum = minOf(maxHeight, LocalMangaWindow.current.height * .9f, 680.dp)
            ReaderDialogSurface(Modifier.fillMaxWidth(), backdrop) {
                Column(Modifier.fillMaxWidth().heightIn(max = maximum).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (area != null || plan != null) MotionIconButton({ if (!busy) back() }, enabled = !busy,
                            modifier = Modifier.size(44.dp), tooltip = "返回") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                        }
                        Text(if (plan != null) "清理预览" else book?.let { known[it]?.title ?: "未归类图片" }
                            ?: area?.label ?: "存储管理", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MotionIconButton({ revision++ }, enabled = !busy && !loading && plan == null, modifier = Modifier.size(44.dp), tooltip = "刷新用量") {
                            MotionSwap(loading, Modifier.size(18.dp)) { working ->
                                if (working) MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                                else Icon(Icons.Outlined.Refresh, "刷新用量", Modifier.size(18.dp))
                            }
                        }
                        MotionIconButton(onDismiss, enabled = !busy, modifier = Modifier.size(44.dp), tooltip = "关闭存储管理") {
                            Icon(Icons.Outlined.Close, "关闭存储管理", Modifier.size(20.dp))
                        }
                    }
                    val review = plan
                    if (review == null) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(snapshot?.let { storageBytes(it.totalBytes + offlineBytes + modelStates.values.sumOf { s -> s.bytes } +
                                    runtimeStates.values.sumOf { s -> s.bytes }) } ?: "统计中…", style = MaterialTheme.typography.headlineSmall)
                                Text(if (selectedInScope.isNotEmpty()) "已选 ${selectedInScope.size} 项 · 可释放 ${storageBytes(scopeBytes)}"
                                    else "可回收 ${storageBytes(snapshot?.reclaimableBytes ?: 0L)}",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            MotionButton(::previewClear, enabled = !busy && !loading && scopeBytes > 0L) { Text("释放空间") }
                        }
                    }
                    MotionTabContent(StoragePane(area, book, plan, snapshot),
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                        contentKey = { Triple(it.area, it.book, it.review != null) },
                        order = { if (it.review != null) 3 else if (it.book != null) 2 else if (it.area != null) 1 else 0 },
                        fillContent = false, soft = true) { shown ->
                    val paneArea = shown.area
                    val paneBook = shown.book
                    val paneReview = shown.review
                    val paneSnapshot = shown.snapshot
                    val paneItems = paneSnapshot?.items.orEmpty().filter { item ->
                        (paneArea == null || item.resource.area == paneArea) &&
                            (paneBook == null || item.resource.mangaId.ifBlank { UNCLASSIFIED } == paneBook)
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
                                    if (paneReview.data) StorageLine("资料与列表", "保留离线所需目录", storageBytes((paneSnapshot?.auxiliaryBytes ?: 0L) +
                                        (paneSnapshot?.catalogReclaimableBytes ?: 0L)))
                                    Text("当前阅读、使用中的图片、下载断点与离线资料会保留。离线章节和模型由各自入口管理。",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        } else if (paneArea == null) {
                            items(CacheArea.entries, key = { it.name }) { kind ->
                                StorageNavigationRow(kind.label, when (kind) {
                                    CacheArea.READING -> "原图 · 脚本与解密图片"
                                    CacheArea.ARTWORK -> "封面 · 用户头像"
                                    CacheArea.DATA -> "推荐 · 榜单 · 目录 · 评分与评论"
                                    CacheArea.PANELS -> "识别坐标，可重新生成"
                                }, storageBytes(paneSnapshot?.areaBytes(kind) ?: 0L), areaIcon(kind), !busy) {
                                    area = kind; selected = emptySet()
                                }
                            }
                            if ((paneSnapshot?.coilUnclassifiedBytes ?: 0L) > 0L) item("legacy") {
                                StorageLine("旧版图片缓存", if (paneSnapshot?.legacyReclaimable == true) "归属会在再次使用后补齐" else "正在使用，稍后可清理",
                                    storageBytes(paneSnapshot?.coilUnclassifiedBytes ?: 0L))
                            }
                            item("offline") { StorageNavigationRow("离线下载", "独立保存", storageBytes(offlineBytes), Icons.Outlined.Download, !busy, onDownloads) }
                            if (PanelReadingAvailable) item("models") { StorageNavigationRow("模型与组件", "按需下载", storageBytes(modelStates.values.sumOf { it.bytes } +
                                runtimeStates.values.sumOf { it.bytes }), Icons.Outlined.Extension, !busy, onModels) }
                            item("budget") {
                                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("索引与写入 ${storageBytes((paneSnapshot?.indexBytes ?: 0L) + (paneSnapshot?.temporaryBytes ?: 0L))}",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Text("缓存预算 ${storageBytes(paneSnapshot?.budgetBytes ?: 0L)} · 各类共享",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Text("内存图片 ${storageBytes(imageLoader.memoryCache?.size ?: 0L)} · 随阅读自动回收",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    if (paneSnapshot?.pending == true || (paneSnapshot?.totalBytes ?: 0L) > (paneSnapshot?.budgetBytes ?: Long.MAX_VALUE))
                                        Text("使用中的内容保留，释放后继续回收", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        } else if (paneArea == CacheArea.DATA) {
                            item("data") { StorageLine("资料与列表", "离线目录受保护", storageBytes(paneSnapshot?.areaBytes(CacheArea.DATA) ?: 0L)) }
                        } else if (paneBook == null) {
                            val groups = paneItems.groupBy { it.resource.mangaId.ifBlank { UNCLASSIFIED } }.entries.sortedByDescending { it.value.sumOf { row -> row.bytes } }
                            if (groups.isEmpty()) item("empty") { StorageEmpty(loading) }
                            items(groups, key = { it.key }) { group ->
                                val manga = known[group.key]
                                val chapters = group.value.map { it.resource.chapterId }.filter { it.isNotBlank() }.distinct().size
                                StorageBookRow(manga, repository, if (group.key == UNCLASSIFIED) "未归类图片" else manga?.title ?: "已缓存作品",
                                    if (chapters > 0) "$chapters 章 · ${group.value.size} 张" else "${group.value.size} 项",
                                    storageBytes(group.value.sumOf { it.bytes }), group.value.filterNot { it.protected }.let { rows -> rows.isNotEmpty() && rows.all { it.resource.id in selected } },
                                    group.value.all { it.protected }, onSelect = {
                                        val ids = group.value.filterNot { it.protected }.map { it.resource.id }.toSet()
                                        selected = if (ids.all { it in selected }) selected - ids else selected + ids
                                    }, onOpen = { book = group.key })
                            }
                        } else {
                            val groups = paneItems.groupBy { it.resource.chapterId to it.resource.sourceId }.entries
                                .sortedBy { details[paneBook]?.chapters?.firstOrNull { ch -> ch.id == it.key.first }?.index ?: Int.MAX_VALUE }
                            items(groups, key = { it.key.first + "\u0000" + it.key.second }) { group ->
                                val chapter = details[paneBook]?.chapters?.firstOrNull { it.id == group.key.first || it.variants.any { variant -> variant.storageId == group.key.first } }
                                val title = chapter?.title ?: if (group.key.first.isBlank()) "未归类图片" else "缓存章节"
                                val source = repository?.sources?.firstOrNull { it.id == group.key.second }?.name ?: group.key.second.ifBlank { "未归类来源" }
                                StorageSelectionRow(title, "$source · ${group.value.size} 张", storageBytes(group.value.sumOf { it.bytes }),
                                    group.value.filterNot { it.protected }.all { it.resource.id in selected }, group.value.all { it.protected }) {
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
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Text(size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(size, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    MotionIconButton(onSelect, enabled = !protected, modifier = Modifier.size(44.dp), tooltip = if (protected) "正在使用，已保护" else if (selected) "取消选择" else "选择清理") {
        Icon(if (protected) Icons.Outlined.Lock else if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            if (protected) "受保护" else if (selected) "已选择" else "未选择", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StorageEmpty(loading: Boolean) { Text(if (loading) "正在统计…" else "暂无缓存", Modifier.padding(vertical = 24.dp),
    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

private fun areaIcon(area: CacheArea) = when (area) {
    CacheArea.READING -> Icons.Outlined.Image
    CacheArea.ARTWORK -> Icons.Outlined.AccountCircle
    CacheArea.DATA -> Icons.Outlined.Storage
    CacheArea.PANELS -> Icons.Outlined.CropFree
}
internal fun storageBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${bytes * 10L / (1024L * 1024 * 1024) / 10.0} GB"
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
private const val UNCLASSIFIED = "\u0000unclassified"
