package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.FontStore
import dev.mangaflow.storage.FontStoreState
import dev.mangaflow.typography.MangaFontCatalog
import dev.mangaflow.typography.MangaFontOption
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.delay

/** One role at a time, using the settings' existing Ovio tabs and retained content transition. */
@Composable
internal fun FontSettings(modifier: Modifier = Modifier) {
    val context = LocalMangaContext.current
    val store = remember(context) { FontStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var sceneName by rememberSaveable { mutableStateOf(MangaFontScene.INTERFACE.name) }
    var managedId by rememberSaveable { mutableStateOf<String?>(null) }
    val scene = MangaFontScene.entries.firstOrNull { it.name == sceneName } ?: MangaFontScene.INTERFACE
    val background = state.pendingSelections.entries.firstOrNull { (target, id) ->
        target != scene && state.transfers[id]?.active == true
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MotionTabs(selected = scene.ordinal, labels = MangaFontScene.entries.map(::fontSceneLabel),
            onSelect = { index ->
                sceneName = MangaFontScene.entries[index].name
                managedId = null
            }, modifier = Modifier.fillMaxWidth(), controlHeight = 44.dp, fontSize = 13.sp,
            selectedFontWeight = FontWeight.Medium, unselectedFontWeight = FontWeight.Normal)
        MotionTabContent(selected = scene, modifier = Modifier.fillMaxWidth(),
            contentKey = { it.name }, order = { it.ordinal }, fillContent = false, soft = true) { shown ->
            val currentId = state.selections[shown] ?: MangaFontCatalog.defaultId(shown)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (state.loading) "正在读取字库…" else shown.description,
                    color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 10.dp))
                Column(Modifier.fillMaxWidth().selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (option in MangaFontCatalog.forScene(shown)) {
                        FontChoiceRow(shown, option, currentId == option.id, state, store,
                            managedId == option.id,
                            onManage = { managedId = if (managedId == option.id) null else option.id })
                    }
                }
            }
        }
        if (background != null) {
            val transfer = state.transfers[background.value]
            val total = (transfer?.total ?: 0L).coerceAtLeast(1L)
            val received = (transfer?.received ?: 0L).coerceIn(0L, total)
            val verifying = received >= total
            val label = "${fontSceneLabel(background.key)}字库 · " +
                if (verifying) "校验中" else "${received * 100L / total}%"
            Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                .semantics(mergeDescendants = true) { contentDescription = label },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (verifying) MotionBusyIndicator(Modifier.size(12.dp),
                    color = colors.onSurfaceVariant, strokeWidth = 1.dp)
                else MotionBusyIndicator(progress = { received.toFloat() / total }, modifier = Modifier.size(12.dp),
                    color = colors.onSurfaceVariant, strokeWidth = 1.dp,
                    trackColor = colors.onSurfaceVariant.copy(alpha = .14f))
                Text(label, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            }
        } else Text("场景独立设置 · 字库按需下载 · 长按管理",
            color = colors.onSurfaceVariant.copy(alpha = .8f), fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp))
    }
}

private fun fontSceneLabel(scene: MangaFontScene): String = when (scene) {
    MangaFontScene.INTERFACE -> "界面"
    MangaFontScene.WORK_TITLE -> "书名"
    MangaFontScene.READING -> "正文"
    MangaFontScene.NUMBERS -> "数字"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FontChoiceRow(
    scene: MangaFontScene,
    option: MangaFontOption,
    selected: Boolean,
    state: FontStoreState,
    store: FontStore,
    managementOpen: Boolean,
    onManage: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    val interaction = remember(scene, option.id) { MutableInteractionSource() }
    val transfer = state.transfers[option.id]
    val downloading = transfer?.active == true
    val transferTotal = (transfer?.total ?: option.byteCount).coerceAtLeast(1L)
    val transferReceived = (transfer?.received ?: 0L).coerceIn(0L, transferTotal)
    val downloaded = option.id in state.installed
    val builtIn = option.assets.isEmpty()
    var completionSeen by remember(option.id) { mutableStateOf(downloaded || state.loading) }
    val justInstalled = downloaded && !completionSeen
    LaunchedEffect(downloaded, state.loading) {
        if (!state.loading) {
            if (!downloaded) completionSeen = false
            else if (!completionSeen) {
                delay(800L)
                completionSeen = true
            }
        }
    }
    val selectionTone by animateColorAsState(
        if (selected) colors.onSurface.copy(alpha = .045f) else Color.Transparent,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
        label = "font-choice-selection",
    )
    val available = builtIn || downloaded
    val pending = state.pendingSelections[scene] == option.id
    val enabled = !state.loading
    val family = when (option.id) {
        "system" -> FontFamily.SansSerif
        "inherit" -> mangaFontFamily(MangaFontScene.INTERFACE)
        else -> store.family(option.id)
    }
    val previewReady = available && family != null
    val previewWeight = option.assets.singleOrNull()?.weight?.let { FontWeight(it) }
        ?: if (scene == MangaFontScene.WORK_TITLE) FontWeight.Medium else FontWeight.Normal
    val requestUse: () -> Unit = {
        if (available) store.select(scene, option.id)
        else store.download(option.id, scene)
    }
    val selectionLabel = when {
        selected -> "当前${scene.title}字体：${option.name}"
        available -> "将${scene.title}字体设为${option.name}"
        downloading -> "下载完成后将${option.name}用于${scene.title}"
        else -> "下载并用于${scene.title}：${option.name}，${fontDownloadBytes(option.byteCount)}"
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(selectionTone)
            .combinedClickable(enabled = enabled, role = Role.RadioButton,
                interactionSource = interaction, indication = null, onClick = requestUse,
                onLongClickLabel = "查看${option.name}详情与字库管理",
                onLongClick = onManage.takeIf { !builtIn })
            .semantics { contentDescription = selectionLabel; this.selected = selected }
            .motionPress(interaction, enabled = enabled, pressedScale = .99f)
            .heightIn(min = 68.dp).padding(start = 10.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(option.name, Modifier.weight(1f, fill = false),
                        color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                    MotionSwap(selected, Modifier.size(16.dp)) { chosen ->
                        if (chosen) Icon(Icons.Outlined.Check, "已选择", Modifier.size(16.dp), tint = colors.onSurface)
                    }
                }
                if (previewReady) {
                    Text(if (scene == MangaFontScene.NUMBERS) "8.7 / 10   12:34   299.4" else scene.sample,
                        color = colors.onSurface, style = TextStyle(
                            fontFamily = family, fontSize = when (scene) {
                                MangaFontScene.WORK_TITLE, MangaFontScene.NUMBERS -> 20.sp
                                MangaFontScene.INTERFACE -> 18.sp
                                MangaFontScene.READING -> 15.sp
                            },
                            lineHeight = if (scene == MangaFontScene.READING) 22.sp else 26.sp,
                            fontWeight = previewWeight,
                            fontFeatureSettings = if (scene == MangaFontScene.NUMBERS) "tnum" else null),
                        maxLines = if (scene == MangaFontScene.READING) 3 else 2, overflow = TextOverflow.Ellipsis)
                } else if (!downloading) {
                    Text(when {
                        available -> "正在准备字体…"
                        selected -> "暂用系统 · ${fontDownloadBytes(option.byteCount)}"
                        else -> "下载并使用 · ${fontDownloadBytes(option.byteCount)}"
                    }, color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                }
                if (downloading) {
                    val total = (transfer?.total ?: option.byteCount).coerceAtLeast(1L)
                    val received = (transfer?.received ?: 0L).coerceIn(0L, total)
                    Text(if (received >= total) "正在校验字库…"
                        else "${received * 100L / total}% · ${fontDownloadBytes(received)} / ${fontDownloadBytes(total)}${if (pending) " · 完成后使用" else ""}",
                        color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            if (!builtIn) {
                val downloadState = when {
                    downloading && transferReceived >= transferTotal -> CompactDownloadState.WAITING
                    downloading -> CompactDownloadState.DOWNLOADING
                    downloaded -> CompactDownloadState.COMPLETE
                    transfer?.error != null -> CompactDownloadState.ERROR
                    else -> CompactDownloadState.AVAILABLE
                }
                val actionLabel = when {
                    downloading -> "取消下载${option.name}，当前${transferReceived * 100L / transferTotal}%"
                    downloaded -> if (managementOpen) "收起${option.name}字库管理" else "管理${option.name}字库"
                    transfer?.error != null -> "重试下载并使用${option.name}"
                    else -> "下载并使用${option.name}，${fontDownloadBytes(option.byteCount)}"
                }
                MotionIconButton(
                    onClick = {
                        when {
                            downloading -> store.cancel(option.id)
                            downloaded -> onManage()
                            else -> requestUse()
                        }
                    },
                    enabled = enabled, modifier = Modifier.size(44.dp),
                ) {
                    MotionSwap(downloaded && !justInstalled, Modifier.size(24.dp)) { showManage ->
                        if (showManage) Icon(Icons.Outlined.MoreHoriz, actionLabel,
                            Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        else CompactDownloadIcon(downloadState,
                            progress = transfer?.let { (it.received.toDouble() / it.total.coerceAtLeast(1L)).toFloat() },
                            contentDescription = actionLabel,
                            activeIcon = Icons.Outlined.Close, tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
        transfer?.error?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, color = colors.error, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
        AnimatedVisibility(managementOpen && !builtIn,
            enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                expandVertically(tween(if (motion) MangaMotion.Enter else 0, easing = MangaMotion.EnterEasing)),
            exit = fadeOut(tween(if (motion) MangaMotion.Content else 0)) +
                shrinkVertically(tween(if (motion) MangaMotion.Enter else 0, easing = MangaMotion.EnterEasing))) {
            Box(Modifier.motionInputGate(managementOpen)) {
                FontDetails(option, state, onRemove = { store.remove(option.id); onManage() })
            }
        }
    }
}

@Composable
private fun FontDetails(option: MangaFontOption, state: FontStoreState, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    var licenseOpen by rememberSaveable(option.id) { mutableStateOf(false) }
    val interfaceId = state.selections[MangaFontScene.INTERFACE] ?: MangaFontCatalog.defaultId(MangaFontScene.INTERFACE)
    val uses = MangaFontScene.entries.filter { scene ->
        val id = state.selections[scene] ?: MangaFontCatalog.defaultId(scene)
        id == option.id || id == "inherit" && interfaceId == option.id
    }
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (option.description.isNotBlank()) Text(option.description, color = colors.onSurfaceVariant,
            fontSize = 12.sp, lineHeight = 18.sp)
        Text(listOfNotNull(
            fontDownloadBytes(option.byteCount),
            uses.takeIf { it.isNotEmpty() }?.joinToString("、", postfix = "已选用", transform = ::fontSceneLabel),
        ).joinToString(" · "), color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            MotionTextButton(onClick = { licenseOpen = !licenseOpen },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Text(if (licenseOpen) "收起许可与来源" else "许可与来源", fontSize = 12.sp)
            }
            if (option.id in state.installed) MotionTextButton(
                onClick = onRemove, enabled = !state.loading && state.transfers[option.id]?.active != true,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(15.dp), tint = colors.onSurfaceVariant)
                Text("移除字库", Modifier.padding(start = 4.dp), color = colors.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        if (option.id in state.installed && uses.isNotEmpty())
            Text("移除后，使用它的场景恢复系统字体。", color = colors.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 18.sp)
        AnimatedVisibility(licenseOpen,
            enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                expandVertically(tween(if (motion) MangaMotion.Enter else 0, easing = MangaMotion.EnterEasing)),
            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                shrinkVertically(tween(if (motion) MangaMotion.Enter else 0, easing = MangaMotion.EnterEasing))) {
            Box(Modifier.motionInputGate(licenseOpen)) { FontLicense(option) }
        }
    }
}

@Composable
private fun FontLicense(option: MangaFontOption) {
    val colors = MaterialTheme.colorScheme
    val uriHandler = LocalUriHandler.current
    var linkFailure by remember(option.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${fontDownloadBytes(option.byteCount)} · ${option.licenseName}",
            color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        MotionTextButton(
            onClick = {
                linkFailure = false
                runCatching { uriHandler.openUri(option.sourceUrl) }.onFailure { linkFailure = true }
            },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text("字体来源", fontSize = 12.sp)
        }
        if (linkFailure) Text("无法打开来源链接。", color = colors.error, fontSize = 12.sp,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        SelectionContainer {
            Text(MangaFontCatalog.licenseText(option.id), color = colors.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun fontDownloadBytes(bytes: Long): String {
    val value = bytes.coerceAtLeast(0)
    val unit = when {
        value >= 1_000_000L -> 1_000_000L
        value >= 1_000L -> 1_000L
        else -> return "$value B"
    }
    val suffix = if (unit == 1_000_000L) "MB" else "KB"
    val tenths = (value * 10L + unit / 2L) / unit
    return if (tenths % 10L == 0L) "${tenths / 10L} $suffix"
        else "${tenths / 10L}.${tenths % 10L} $suffix"
}
