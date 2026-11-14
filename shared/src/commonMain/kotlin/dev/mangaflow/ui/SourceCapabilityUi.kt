package dev.mangaflow.ui

import coil3.compose.AsyncImage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Job
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceHealth
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.data.plugins.StarterSourceInstaller
import dev.mangaflow.data.sources.*
import dev.mangaflow.json.JSONArray
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Existing source selector, compact tabs and sheets; no second content shell. */
@Composable
internal fun SourceCapabilityBrowseControls(
    controller: ScopedBrowseController,
    repository: UnifiedMangaRepository,
    onSources: () -> Unit,
    onInspect: (String) -> Unit,
    starters: StarterSourceInstaller? = null,
) {
    val current by controller.controls.collectAsStateWithLifecycle()
    val browse by controller.state.collectAsStateWithLifecycle()
    var feedPicker by remember(current.selectedSource) { mutableStateOf(false) }
    var pendingFeed by remember(current.selectedSource) { mutableStateOf<String?>(null) }
    var pendingOption by remember(current.selectedSource, current.feedId, browse.isSearch) { mutableStateOf<Pair<Int, String>?>(null) }
    var failuresOpen by remember(current.selectedSource, browse.isSearch) { mutableStateOf(false) }
    var pendingInspect by remember(current.selectedSource, browse.isSearch) { mutableStateOf<String?>(null) }
    var filterIndex by remember(current.selectedSource, current.feedId, browse.isSearch) { mutableStateOf<Int?>(null) }
    val failed = browse.searchSources.values.filter { it.error != null }
    val filters = if (browse.isSearch) current.searchFilters else current.feeds.firstOrNull { it.id == current.feedId }?.filters.orEmpty()
    val sourceName = current.sources.firstOrNull { it.id == current.selectedSource }?.name ?: "全部来源"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        starters?.let { StarterSourcePreparation(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MotionTextButton(onClick = onSources, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(sourceName, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 3.dp).size(16.dp))
            }
            Spacer(Modifier.weight(1f))
            if (current.loading) MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            if (current.selectedSource != "*") MotionIconButton(onClick = { onInspect(current.selectedSource) },
                modifier = Modifier.size(40.dp), tooltip = "来源状态与设置") {
                Icon(Icons.Outlined.Tune, "来源状态与设置", Modifier.size(17.dp))
            }
        }
        if (!browse.isSearch && current.feeds.size > 1) {
            if (current.feeds.size <= 4) Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                current.feeds.forEach { feed ->
                    MotionTextButton(onClick = { controller.selectFeed(feed.id) },
                        modifier = Modifier.semantics { selected = feed.id == current.feedId },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                        Text(feed.title, fontSize = 12.sp,
                            fontWeight = if (feed.id == current.feedId) FontWeight.Medium else FontWeight.Normal,
                            color = if (feed.id == current.feedId) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else MotionTextButton(onClick = { feedPicker = true },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(current.feeds.firstOrNull { it.id == current.feedId }?.title ?: "选择分类", fontSize = 12.sp)
                Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 3.dp).size(15.dp))
            }
        }
        if (filters.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            filters.forEachIndexed { index, filter ->
                val choice = filter.choices.firstOrNull { it.id == current.options.getOrNull(index) }
                MotionTextButton(onClick = { filterIndex = index }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp)) {
                    Text(if (filter.multiple) filter.title + " · " + filter.selectedIds(current.options.getOrNull(index)).size
                        else choice?.title ?: filter.title, fontSize = 11.sp)
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.size(14.dp))
                }
            }
        }
        current.error?.let { message ->
            MotionTextButton(onClick = controller::refresh, contentPadding = PaddingValues(0.dp)) {
                Text(message, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        if (failed.isNotEmpty()) MotionTextButton(onClick = { failuresOpen = true },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 3.dp)) {
            Text(if (failed.size == 1) failed.first().sourceName + " · 暂未读取" else "${failed.size} 个来源暂未读取",
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 3.dp).size(14.dp))
        }
    }
    if (failuresOpen) MotionBottomSheet(onDismissRequest = {
        failuresOpen = false
        pendingInspect?.let(onInspect); pendingInspect = null
    }) { dismiss ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("来源状态", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭来源状态") {
                    Icon(Icons.Outlined.Close, "关闭来源状态", Modifier.size(18.dp))
                }
            } }
            items(failed, key = { it.sourceId }) { lane ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = { pendingInspect = lane.sourceId; dismiss() },
                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(lane.sourceName, fontSize = 12.sp)
                            Text(lane.error.orEmpty(), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    MotionTextButton(onClick = { controller.retrySource(lane.sourceId) },
                        enabled = !browse.loading && !browse.loadingMore && !browse.refreshing) { Text("重试", fontSize = 11.sp) }
                }
            }
            if (failed.isEmpty()) item { Text(if (browse.loading || browse.loadingMore || browse.refreshing) "正在重试…" else "已完成读取",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (feedPicker) MotionBottomSheet(onDismissRequest = {
        feedPicker = false
        val chosen = pendingFeed; pendingFeed = null
        if (controller.sourceId == current.selectedSource && chosen != null) controller.selectFeed(chosen)
    }) { dismiss ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            item(key = "feed-picker-header") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sourceName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭分类") {
                        Icon(Icons.Outlined.Close, "关闭分类", Modifier.size(18.dp))
                    }
                }
            }
            current.feeds.groupBy { it.kind }.forEach { (kind, entries) ->
                item(key = "feed-kind:" + kind.name) {
                    Text(when (kind) { SourceFeedKind.LATEST -> "更新"; SourceFeedKind.EXPLORE -> "发现"
                        SourceFeedKind.RANKING -> "排行"; SourceFeedKind.CATEGORY -> "分类" },
                        Modifier.padding(top = 10.dp, bottom = 4.dp), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(entries, key = { "feed:" + it.id }) { feed ->
                    MotionTextButton(onClick = { pendingFeed = feed.id; dismiss() }, enabled = pendingFeed == null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).semantics { selected = feed.id == current.feedId },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                        Text(feed.title, Modifier.weight(1f), fontSize = 13.sp,
                            fontWeight = if (feed.id == current.feedId) FontWeight.Medium else FontWeight.Normal)
                        if (feed.id == current.feedId) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp))
                    }
                }
            }
        }
    }
    filterIndex?.let { index -> filters.getOrNull(index)?.let { filter ->
        var selectedIds by remember(current.selectedSource, current.feedId, browse.isSearch, filter.id) {
            mutableStateOf(filter.selectedIds(current.options.getOrNull(index)))
        }
        MotionBottomSheet(onDismissRequest = {
            filterIndex = null
            val chosen = pendingOption; pendingOption = null
            if (controller.sourceId == current.selectedSource && controller.controls.value.feedId == current.feedId &&
                controller.state.value.isSearch == browse.isSearch && chosen != null) controller.selectOption(chosen.first, chosen.second)
        }) { dismiss ->
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), contentPadding = PaddingValues(20.dp)) {
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(filter.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(bottom = 8.dp))
                    if (filter.multiple) MotionTextButton(onClick = {
                        pendingOption = index to JSONArray(filter.choices.filter { it.id in selectedIds }.map { it.id }).toString()
                        dismiss()
                    }, enabled = pendingOption == null) { Text("完成", fontSize = 12.sp) }
                } }
                items(filter.choices, key = { it.id }) { choice ->
                    MotionTextButton(onClick = {
                        if (filter.multiple) selectedIds = if (choice.id in selectedIds) selectedIds - choice.id else selectedIds + choice.id
                        else { pendingOption = index to choice.id; dismiss() }
                    }, enabled = pendingOption == null, modifier = Modifier.fillMaxWidth()
                        .semantics { selected = if (filter.multiple) choice.id in selectedIds else choice.id == current.options.getOrNull(index) }) {
                        Text(choice.title, Modifier.weight(1f), fontSize = 13.sp)
                        if (if (filter.multiple) choice.id in selectedIds else choice.id == current.options.getOrNull(index))
                            Icon(Icons.Outlined.Check, null, Modifier.size(17.dp))
                    }
                }
            }
        }
    } }
}

@Composable
internal fun StarterSourcePreparation(installer: StarterSourceInstaller) {
    val state by installer.state.collectAsStateWithLifecycle()
    val delayed = rememberDelayedBusy(state.running)
    val motion = LocalMotionEnabled.current
    AnimatedVisibility(delayed || state.error != null,
        enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) + expandVertically(tween(if (motion) MangaMotion.Content else 0)),
        exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) + shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.running) MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
        Text(state.error ?: (state.phase + (state.progress?.let { " · $it%" } ?: " · ${state.completed}/${state.total}")),
            Modifier.weight(1f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.running) MotionTextButton(onClick = installer::cancel) { Text("暂停", fontSize = 11.sp) }
        else if (state.error != null) MotionTextButton(onClick = { installer.start(retry = true) }) { Text("重试", fontSize = 11.sp) }
    }
    }
}

@Composable
internal fun SourceCapabilityComments(
    repository: UnifiedMangaRepository, manga: Manga, modifier: Modifier,
    bottomPadding: Dp, openRevision: Int, active: Boolean,
) {
    val scope = rememberCoroutineScope()
    var rows by remember(manga.id) { mutableStateOf<List<SourceComment>>(emptyList()) }
    var page by remember(manga.id) { mutableIntStateOf(0) }
    var more by remember(manga.id) { mutableStateOf(false) }
    var loading by remember(manga.id) { mutableStateOf(false) }
    var error by remember(manga.id) { mutableStateOf<String?>(null) }
    var failedPage by remember(manga.id) { mutableIntStateOf(0) }
    var job by remember(manga.id) { mutableStateOf<Job?>(null) }
    var generation by remember(manga.id) { mutableLongStateOf(0L) }
    fun load(next: Int) {
        if (loading) return
        loading = true; error = null
        generation++; val expected = generation
        job = scope.launch {
            try {
                val result = repository.sourceComments(manga.sourceId, manga.id, next)
                if (expected != generation) return@launch
                rows = (if (next == 0) result.items else rows + result.items).distinctBy { it.id }
                page = next; more = result.hasMore
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (expected == generation) { failedPage = next; error = repository.sourceHealth.value[manga.sourceId]?.message ?: "评论读取失败" } }
            finally { if (expected == generation) loading = false }
        }
    }
    LaunchedEffect(manga.id, openRevision, active) {
        generation++; job?.cancel(); loading = false
        if (active) load(0)
    }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding + 16.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        if (rows.isEmpty() && loading) item { QuietLoading("正在读取评论…") }
        if (rows.isEmpty() && !loading && error == null) item { Text("暂无评论", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(rows, key = { it.id }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (row.avatar.isNotBlank()) AsyncImage(model = row.avatar, contentDescription = null,
                    modifier = Modifier.size(28.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(listOf(row.author, manga.sourceName, row.time).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.content, style = MaterialTheme.typography.bodyMedium)
                if (row.likes > 0) Text("♡ ${row.likes}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        error?.let { message -> item {
            Text(message, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            MotionTextButton(onClick = { load(failedPage) }, enabled = !loading) { Text("重试") }
        } }
        if (more && error == null) item {
            MotionTextButton(onClick = { load(page + 1) }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text(if (loading) "正在读取…" else "更多评论", fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun SourceCapabilityDetailsSheet(
    source: BodySourceInfo, repository: UnifiedMangaRepository, plugins: SourcePluginStore?,
    backdrop: HazeState?, manga: Manga? = null, onDismiss: () -> Unit, onSourceChanged: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val health by repository.sourceHealth.collectAsStateWithLifecycle()
    var capabilities by remember(source.id) { mutableStateOf(SourceCapabilities(emptySet())) }
    var settings by remember(source.id) { mutableStateOf<List<SourceSetting>>(emptyList()) }
    var folders by remember(source.id) { mutableStateOf<List<SourceFavoriteFolder>>(emptyList()) }
    var account by remember(source.id) { mutableStateOf(SourceAccountState()) }
    var busy by remember(source.id) { mutableStateOf(false) }
    var message by remember(source.id) { mutableStateOf<String?>(null) }
    var updateAvailable by remember(source.id) { mutableStateOf(false) }
    fun operate(operation: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { operation() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { message = "操作未完成，已保留原来的来源与本机记录。" }
            finally { busy = false }
        }
    }
    LaunchedEffect(source.id, source.revision) {
        try {
            capabilities = repository.sourceCapabilities(source.id)
            settings = repository.sourceSettings(source.id)
            if (capabilities.supports(SourceFeature.ACCOUNT)) account = repository.sourceAccountState(source.id)
            if (manga != null && account.signedIn && capabilities.supports(SourceFeature.FAVORITES)) folders = repository.sourceFavoriteFolders(source.id)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message = "部分来源信息未能读取，可重试检测。" }
    }
    MotionBottomSheet(backdrop = backdrop, onDismissRequest = onDismiss) { dismiss ->
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = (LocalMangaWindow.current.height - 96.dp).coerceAtLeast(120.dp)),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(source.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭") { Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp)) }
                }
                SourceHealthSummary(health[source.id])
                Text(capabilities.features.joinToString(" · ") { when (it) {
                    SourceFeature.DISCOVERY -> "发现"; SourceFeature.SEARCH -> "搜索"; SourceFeature.CATEGORIES -> "分类"
                    SourceFeature.RANKING -> "排行"; SourceFeature.ACCOUNT -> "账号"; SourceFeature.FAVORITES -> "站点收藏"; SourceFeature.COMMENTS -> "评论"
                } }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MotionTextButton(onClick = { operate { repository.sourceProbe(source.id) } }, enabled = !busy) { Text(if (busy) "读取中…" else "检测目录", fontSize = 12.sp) }
            }
            if (source.id.startsWith("js_") && plugins != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = { operate { updateAvailable = plugins.checkUpdate(source.id) != null
                        message = if (updateAvailable) "有可用更新" else "已是当前仓库版本" } }, enabled = !busy) { Text("检查更新", fontSize = 12.sp) }
                    if (updateAvailable) MotionTextButton(onClick = { operate { plugins.update(source.id); updateAvailable = false; message = "来源已更新"; onSourceChanged() } }, enabled = !busy) { Text("更新", fontSize = 12.sp) }
                }
            }
            if (manga != null && account.signedIn && capabilities.supports(SourceFeature.FAVORITES)) {
                item { Text("站点收藏", style = MaterialTheme.typography.titleSmall)
                    Text("与本机书架分别保存", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(folders, key = { "favorite:" + it.id }) { folder ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(folder.title, Modifier.weight(1f), fontSize = 12.sp)
                        MotionTextButton(onClick = { operate { repository.sourceSetFavorite(source.id, manga.id, folder.id, true); message = "已加入站点收藏" } }, enabled = !busy) { Text("加入", fontSize = 11.sp) }
                        MotionTextButton(onClick = { operate { repository.sourceSetFavorite(source.id, manga.id, folder.id, false); message = "已移出站点收藏，本机书架保留" } }, enabled = !busy) { Text("移出", fontSize = 11.sp) }
                    }
                }
            }
            if (settings.isNotEmpty()) item { Text("来源设置", style = MaterialTheme.typography.titleSmall) }
            items(settings, key = { "setting:" + it.key }) { setting ->
                var value by remember(source.id, setting.key, setting.value) { mutableStateOf(setting.value) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(setting.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (setting.type == "switch") {
                        val interaction = remember { MutableInteractionSource() }
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                            .toggleable(value == "true", enabled = !busy, role = Role.Switch,
                                interactionSource = interaction, indication = null,
                                onValueChange = { value = it.toString() }),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (value == "true") "已开启" else "已关闭", fontSize = 12.sp,
                                modifier = Modifier.weight(1f))
                            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                MotionSwitchThumb(value == "true", interaction)
                            }
                        }
                    } else if (setting.choices.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        setting.choices.forEach { choice -> MotionTextButton(onClick = { value = choice.id }, enabled = !busy) {
                            Text(choice.title, fontSize = 12.sp, color = if (value == choice.id) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        } }
                    } else BasicTextField(value, { if (it.length <= 8192) value = it },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)).padding(12.dp),
                        singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        visualTransformation = if (setting.type.lowercase() in setOf("password", "secret", "token", "apikey") || Regex("(?i)token|password|secret|api.?key|cookie|authorization|auth.?key|bearer").containsMatchIn(setting.key)) PasswordVisualTransformation() else VisualTransformation.None)
                    if (value != setting.value) MotionTextButton(onClick = { operate {
                        repository.saveSourceSetting(source.id, setting.key, value); settings = repository.sourceSettings(source.id); message = "设置已保存"; onSourceChanged() } }, enabled = !busy) { Text("保存", fontSize = 12.sp) }
                }
            }
            message?.let { item { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        }
    }
}

@Composable
private fun SourceHealthSummary(health: SourceHealth?) {
    if (health == null) Text("尚未读取", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(if (health.checking) health.stage.label + " · 正在读取" else health.message.ifBlank { health.stage.label + " · 已读取" },
            fontSize = 11.sp, color = if (health.failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (health.lastSuccessAt > 0) Text("最近成功 · " + formatInstantMillis(health.lastSuccessAt, "MM-dd HH:mm"),
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
