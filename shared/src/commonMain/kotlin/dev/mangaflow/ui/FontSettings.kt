package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.delay

/** Lives inside the existing appearance disclosure; choices affect one text role at a time. */
@Composable
internal fun FontSettings(modifier: Modifier = Modifier) {
    val context = LocalMangaContext.current
    val store = remember(context) { FontStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    var expandedScene by rememberSaveable { mutableStateOf<String?>(MangaFontScene.INTERFACE.name) }
    var licenseId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("按用途分别设置，字库按需下载。漫画图片中的文字保持原样。",
            color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(bottom = 6.dp))
        if (state.loading) Text("正在读取已下载字体…",
            color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
        for (scene in MangaFontScene.entries) {
            val open = expandedScene == scene.name
            val currentId = state.selections[scene] ?: MangaFontCatalog.defaultId(scene)
            val currentName = MangaFontCatalog.find(currentId)?.name ?: "系统字体"
            val pendingId = state.pendingSelections[scene]
            val pending = pendingId?.let(MangaFontCatalog::find)
            val pendingTransfer = pendingId?.let { state.transfers[it] }
            val headerValue = when {
                pending != null && pendingTransfer?.active == true -> {
                    val total = pendingTransfer.total.coerceAtLeast(1L)
                    val received = pendingTransfer.received.coerceIn(0L, total)
                    if (received >= total) "${pending.name} · 校验中"
                    else "${pending.name} · ${received * 100L / total}%"
                }
                pendingTransfer?.error != null -> "$currentName · 下载失败"
                !state.loading && MangaFontCatalog.find(currentId)?.assets?.isNotEmpty() == true &&
                    currentId !in state.installed -> "$currentName · 暂用系统"
                else -> currentName
            }
            val interaction = remember(scene) { MutableInteractionSource() }
            val disclosureRotation by animateFloatAsState(if (open) -180f else 0f,
                if (motion) tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing) else snap(),
                label = "font-scene-disclosure")
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .semantics { stateDescription = "$currentName，${if (open) "已展开" else "已收起"}" }
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                        onClickLabel = if (open) "收起${scene.title}字体" else "选择${scene.title}字体",
                        onClick = {
                            expandedScene = if (open) null else scene.name
                            licenseId = null
                        })
                    .motionPress(interaction, pressedScale = .99f)
                    .heightIn(min = 56.dp).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(scene.title, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium, color = colors.onSurface)
                        Text(scene.description, color = colors.onSurfaceVariant,
                            fontSize = 12.sp, lineHeight = 18.sp)
                    }
                    Text(headerValue,
                        Modifier.weight(.8f, fill = false).widthIn(max = 136.dp),
                        color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (pendingTransfer?.active == true) {
                        CompactDownloadIcon(CompactDownloadState.DOWNLOADING,
                            progress = (pendingTransfer.received.toDouble() / pendingTransfer.total.coerceAtLeast(1L)).toFloat(),
                            modifier = Modifier.size(20.dp), tint = colors.onSurfaceVariant,
                            contentDescription = "字库下载进度")
                    }
                    Icon(Icons.Outlined.ExpandMore, null,
                        Modifier.size(18.dp).rotate(disclosureRotation), tint = colors.onSurfaceVariant)
                }
                AnimatedVisibility(open,
                    enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                        expandVertically(tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing)),
                    exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                        shrinkVertically(tween(if (motion) MangaMotion.Enter else 0, easing = MangaMotion.EnterEasing))) {
                    Column(Modifier.fillMaxWidth().motionInputGate(open).selectableGroup().padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (option in MangaFontCatalog.forScene(scene)) {
                            FontChoiceRow(scene, option, currentId == option.id, state, store,
                                licenseId == option.id,
                                onLicense = { licenseId = if (licenseId == option.id) null else option.id })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FontChoiceRow(
    scene: MangaFontScene,
    option: MangaFontOption,
    selected: Boolean,
    state: FontStoreState,
    store: FontStore,
    licenseOpen: Boolean,
    onLicense: () -> Unit,
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
        else -> "下载并使用${option.name}，${option.byteCount}字节，仅用于${scene.title}"
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(selectionTone)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton,
                interactionSource = interaction, indication = null, onClick = requestUse)
            .semantics { contentDescription = selectionLabel }
            .motionPress(interaction, enabled = enabled, pressedScale = .99f)
            .heightIn(min = 68.dp).padding(start = 10.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(option.name, Modifier.weight(1f, fill = false),
                        color = colors.onSurface, fontSize = 14.sp, lineHeight = 20.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                    MotionSwap(selected, Modifier.size(16.dp)) { chosen ->
                        if (chosen) Icon(Icons.Outlined.Check, "已选择", Modifier.size(16.dp), tint = colors.onSurface)
                    }
                }
                if (previewReady) {
                    Text(if (scene == MangaFontScene.NUMBERS) "8.7 / 10   12:34   299.4" else scene.sample,
                        color = colors.onSurface, style = TextStyle(
                            fontFamily = family, fontSize = if (scene == MangaFontScene.WORK_TITLE) 18.sp else 14.sp,
                            lineHeight = if (scene == MangaFontScene.WORK_TITLE) 26.sp else 22.sp,
                            fontWeight = previewWeight,
                            fontFeatureSettings = if (scene == MangaFontScene.NUMBERS) "tnum" else null),
                        maxLines = if (scene == MangaFontScene.READING) 3 else 2, overflow = TextOverflow.Ellipsis)
                } else if (!downloading) {
                    Text("下载后预览 · ${fontDownloadBytes(option.byteCount)}",
                        color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                }
                if (option.description.isNotBlank()) {
                    Text(option.description, color = colors.onSurfaceVariant,
                        fontSize = 12.sp, lineHeight = 18.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    downloaded -> "删除${option.name}字库；使用它的场景将恢复默认"
                    transfer?.error != null -> "重试下载并使用${option.name}"
                    else -> "下载并使用${option.name}，${fontDownloadBytes(option.byteCount)}"
                }
                MotionIconButton(
                    onClick = {
                        when {
                            downloading -> store.cancel(option.id)
                            downloaded -> store.remove(option.id)
                            else -> requestUse()
                        }
                    },
                    enabled = enabled, modifier = Modifier.size(44.dp),
                ) {
                    MotionSwap(downloaded && !justInstalled, Modifier.size(24.dp)) { showDelete ->
                        if (showDelete) Icon(Icons.Outlined.DeleteOutline, actionLabel,
                            Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        else CompactDownloadIcon(downloadState,
                            progress = transfer?.let { (it.received.toDouble() / it.total.coerceAtLeast(1L)).toFloat() },
                            contentDescription = actionLabel,
                            activeIcon = Icons.Outlined.Close, tint = colors.onSurfaceVariant)
                    }
                }
                MotionIconButton(onClick = onLicense, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Info, if (licenseOpen) "收起${option.name}许可" else "查看${option.name}许可与来源",
                        Modifier.size(17.dp), tint = colors.onSurfaceVariant.copy(alpha = .8f))
                }
            }
        }
        transfer?.error?.takeIf { it.isNotBlank() }?.let { message ->
            Text(message, color = colors.error, fontSize = 12.sp, lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 6.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite })
        }
        AnimatedVisibility(licenseOpen && !builtIn,
            enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                expandVertically(tween(if (motion) MangaMotion.Enter else 0, easing = MangaMotion.EnterEasing)),
            exit = fadeOut(tween(if (motion) MangaMotion.Content else 0)) +
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
        Text("${option.byteCount} 字节 · ${option.licenseName}",
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
