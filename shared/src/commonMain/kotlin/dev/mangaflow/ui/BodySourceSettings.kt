package dev.mangaflow.ui

import androidx.compose.material.icons.outlined.Tune

import dev.mangaflow.data.UnifiedMangaRepository

import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.storage.BodySourceRegistryEntry
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwitchThumb
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Registry edits affect source discovery, while the source's existing records stay intact. */
@Composable
internal fun BodySourceSettingsSheet(
    registry: BodySourceRegistry,
    backdrop: HazeState?,
    plugins: SourcePluginStore? = null,
    onDismiss: () -> Unit,
    importAsAdult: Boolean = false,
    repository: UnifiedMangaRepository? = null,
) {
    val scriptImportSupported = true
    val entries by registry.entries.collectAsStateWithLifecycle()
    val packages = if (plugins != null) {
        val installed by plugins.installed.collectAsStateWithLifecycle()
        installed
    } else emptyList()
    val sourceLoadError = if (plugins != null) {
        val error by plugins.loadError.collectAsStateWithLifecycle()
        error
    } else null
    val scriptPackages = packages.associateBy { it.sourceId }
    val added = entries.filter { !it.removed || it.source.id in scriptPackages }
        .sortedBy { if (it.enabled) 0 else 1 }
    val removed = entries.filter { it.removed && it.isInstalled && !it.source.id.startsWith("js_") }
    val canImport = plugins != null && scriptImportSupported
    val moveCandidates = if (importAsAdult) entries.filter {
        it.isInstalled && !it.removed && !it.adult && it.source.id.startsWith("js_") &&
            it.source.id in scriptPackages
    } else emptyList()
    val hasAddChoices = removed.isNotEmpty() || moveCandidates.isNotEmpty()
    val motion = LocalMotionEnabled.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inspecting by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var movingExisting by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun change(entry: BodySourceRegistryEntry, action: String, operation: suspend () -> Unit) {
        if (busyId != null) return
        busyId = entry.source.id
        notice = null
        failed = false
        val firstVisibleIndex = listState.firstVisibleItemIndex
        val firstVisibleOffset = listState.firstVisibleItemScrollOffset
        scope.launch {
            try {
                operation()
                if (action == "toggle") listState.scrollToItem(firstVisibleIndex, firstVisibleOffset)
                notice = when (action) {
                    "restore" -> "已添加${entry.source.name}。"
                    "move" -> "已将${entry.source.name}移入当前分区。"
                    else -> null
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                failed = true
                notice = failure.message?.takeIf { it.isNotBlank() } ?: "来源设置未完成，请重试。"
            } finally {
                busyId = null
            }
        }
    }

    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop,
        containerColor = colors.surface.copy(alpha = .97f)) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.height * .78f)
            .navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("漫画源", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("已启用 ${added.count { it.enabled }} · 已添加 ${added.size}", fontSize = 11.sp,
                        color = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = { showHelp = !showHelp }) {
                    Icon(Icons.Outlined.Info, if (showHelp) "收起漫画源说明" else "漫画源使用说明",
                        Modifier.size(19.dp), tint = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = dismiss) {
                    Icon(Icons.Outlined.Close, "关闭漫画源设置", Modifier.size(20.dp))
                }
            }
            AnimatedVisibility(showHelp,
                enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                    expandVertically(tween(if (motion) MangaMotion.Content else 0)),
                exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                    shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
                Text("关闭后不参与搜索与自动匹配，并排在列表末尾；阅读记录和下载保留。" +
                    if (!scriptImportSupported) "\n第三方脚本源暂仅 Android 支持。" else "",
                    fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp))
            }
            notice?.let { message ->
                Text(message, fontSize = 11.sp, lineHeight = 17.sp,
                    color = if (failed) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite })
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), state = listState,
                contentPadding = PaddingValues(start = 20.dp, end = 12.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                sourceLoadError?.let { message -> item(key = "source-storage-error") {
                    Text(message, fontSize = 11.sp, lineHeight = 16.sp, color = colors.error,
                        modifier = Modifier.padding(end = 8.dp, bottom = 6.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite })
                } }
                items(added, key = { "added:${it.source.id}" }) { entry ->
                    BodySourceSettingsRow(entry, enabled = busyId == null,
                        onDetails = if (repository != null) { { inspecting = entry.source.id } } else null,
                        modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null,
                            placementSpec = if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap()),
                        kind = scriptPackages[entry.source.id]?.let {
                            (if (it.metadata.optString("protocol") == "venera") "Venera" else "Mangayomi") + it.metadata.optString("version").takeIf(String::isNotBlank)?.let { version -> " · $version" }.orEmpty()
                        },
                        onToggle = { value -> change(entry, "toggle") {
                            if (value && entry.removed) registry.restore(entry.source.id)
                            else registry.setEnabled(entry.source.id, value)
                        } })
                }
                if (canImport || hasAddChoices) item(key = "add-sources") {
                    val interaction = remember { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = busyId == null, interactionSource = interaction, indication = null,
                            role = Role.Button, onClickLabel = "添加漫画源",
                            onClick = {
                                if (!hasAddChoices) importing = true else adding = !adding
                            })
                        .motionPress(interaction, enabled = busyId == null, pressedScale = .99f).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
                        Text("添加源", Modifier.weight(1f), fontSize = 13.sp)
                        if (hasAddChoices) Icon(if (adding) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                }
                if (adding && hasAddChoices) {
                    if (canImport) item(key = "import-script-source") {
                        MotionTextButton(enabled = busyId == null, onClick = {
                            adding = false
                            importing = true
                        }, modifier = Modifier.padding(start = 28.dp)) {
                            Text("导入文件或源库链接", fontSize = 12.sp)
                        }
                    }
                    if (moveCandidates.isNotEmpty()) item(key = "move-existing-sources") {
                        MotionTextButton(enabled = busyId == null, onClick = {
                            movingExisting = !movingExisting
                        }, modifier = Modifier.padding(start = 28.dp)) {
                            Text("从已有来源移入", fontSize = 12.sp)
                            Icon(if (movingExisting) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                null, Modifier.padding(start = 6.dp).size(16.dp),
                                tint = colors.onSurfaceVariant)
                        }
                    }
                    if (movingExisting) items(moveCandidates, key = { "move:${it.source.id}" }) { entry ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChapterSourceLogo(entry.source.id, entry.source.name, Modifier.size(24.dp))
                            Text(entry.source.name, Modifier.weight(1f), fontSize = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (busyId == entry.source.id) Box(Modifier.size(48.dp),
                                contentAlignment = Alignment.Center) {
                                MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                            } else MotionTextButton(enabled = busyId == null, onClick = {
                                change(entry, "move") { registry.setAdult(entry.source.id, true) }
                            }) { Text("移入", fontSize = 12.sp) }
                        }
                    }
                    items(removed, key = { "removed:${it.source.id}" }) { entry ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ChapterSourceLogo(entry.source.id, entry.source.name, Modifier.size(24.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.source.name, fontSize = 13.sp)
                                Text(entry.source.language, fontSize = 11.sp, color = colors.onSurfaceVariant)
                            }
                            if (busyId == entry.source.id) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                            } else MotionTextButton(enabled = busyId == null,
                                onClick = { change(entry, "restore") { registry.restore(entry.source.id) } }) {
                                Text("添加", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    if (repository != null) inspecting?.let { id -> entries.firstOrNull { it.source.id == id }?.let { entry ->
        SourceCapabilityDetailsSheet(entry.source, repository, plugins, backdrop, onDismiss = { inspecting = null })
    } }
    if (importing && plugins != null && scriptImportSupported) SourceImportSheet(plugins, registry, backdrop,
        onDismiss = { importing = false }, markAdult = importAsAdult, onImported = { name ->
            notice = "已导入并启用$name。"
            failed = false
        })
}

@Composable
private fun BodySourceSettingsRow(
    entry: BodySourceRegistryEntry,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    kind: String? = null,
    onToggle: (Boolean) -> Unit,
    onDetails: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(entry.source.id) { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ChapterSourceLogo(entry.source.id, entry.source.name, Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(entry.source.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(entry.source.language, kind ?: "内置")
                .filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.sp, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onDetails != null) MotionIconButton(onClick = onDetails, enabled = enabled,
            modifier = Modifier.size(40.dp), tooltip = "来源状态与设置") {
            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Tune, "来源状态与设置", Modifier.size(18.dp))
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            .toggleable(entry.enabled, enabled = enabled, interactionSource = interaction, indication = null,
                role = Role.Switch, onValueChange = onToggle)
            .semantics { contentDescription = "${entry.source.name}参与搜索与自动匹配" },
            contentAlignment = Alignment.Center) {
            MotionSwitchThumb(entry.enabled, interaction)
        }
    }
}
