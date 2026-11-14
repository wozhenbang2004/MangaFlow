package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import dev.mangaflow.ui.motion.MotionSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.storage.WallpaperItem
import dev.mangaflow.storage.WallpaperAdjustment
import dev.mangaflow.storage.WallpaperFit
import dev.mangaflow.storage.WallpaperKind
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwitchThumb
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberFeelFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The current preview keeps its place; thumbnails select, while management stays disclosed. */
@Composable
internal fun WallpaperSettings(
    store: WallpaperStore = WallpaperStore.get(LocalContext.current),
    modifier: Modifier = Modifier,
    backdrop: HazeState? = null,
) {
    val selected by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val feedback = rememberFeelFeedback()
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionEnabled.current
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var controlsExpanded by remember { mutableStateOf(false) }
    var formatDetails by remember { mutableStateOf(false) }
    var pcVisible by remember { mutableStateOf(false) }
    var steamVisible by remember { mutableStateOf(false) }
    var removal by remember { mutableStateOf<WallpaperItem?>(null) }
    var clearRequested by remember { mutableStateOf(false) }
    var removalVisible by remember { mutableStateOf(false) }
    val rotationInteraction = remember { MutableInteractionSource() }
    val emptyInteraction = remember { MutableInteractionSource() }
    var adjustmentTab by remember { mutableStateOf(0) }
    val editOwner = remember(store) { Any() }
    var editingPath by remember(store) { mutableStateOf<String?>(null) }
    val latestEditingPath by rememberUpdatedState(editingPath)
    DisposableEffect(store, editOwner) {
        onDispose {
            if (latestEditingPath != null) runCatching { store.saveAdjustment() }
            store.setEditing(editOwner, false)
        }
    }
    val current = selected.mediaPath?.let { path ->
        selected.items.firstOrNull { it.mediaPath == path }
            ?: WallpaperItem(path, selected.posterPath, selected.kind, selected.name, selected.adjustment)
    }
    fun change(event: FeelEvent? = FeelEvent.Selection, action: () -> Unit) {
        try {
            action()
            notice = null
            event?.let(feedback::perform)
        } catch (failure: Exception) {
            notice = failure.message ?: "壁纸设置未能保存，请重试。"
        }
    }
    fun finishAdjustment() {
        if (editingPath == null) return
        try { store.saveAdjustment(); notice = null }
        catch (failure: Exception) { notice = failure.message ?: "调节未能保存，请重试。" }
        finally { editingPath = null; store.setEditing(editOwner, false) }
    }
    fun adjust(transform: (WallpaperAdjustment) -> WallpaperAdjustment) {
        val path = editingPath ?: selected.mediaPath ?: return
        if (editingPath == null) { editingPath = path; store.setEditing(editOwner, true) }
        try { store.updateAdjustment(path, persist = false, transform = transform) }
        catch (failure: Exception) { notice = failure.message ?: "调节未能应用，请重试。" }
    }
    val picker = rememberOpenDocumentPicker() { uri ->
        if (uri != null && !importing) {
            importing = true
            notice = null
            scope.launch {
                try {
                    store.importMedia(uri)
                    feedback.perform(FeelEvent.Confirm)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { notice = failure.message ?: "壁纸未能导入，请重新选择。" }
                finally { releasePickedDocuments(listOf(uri)); importing = false }
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val preview = if (selected.kind == WallpaperKind.VIDEO) selected.posterPath else selected.mediaPath
        val previewModifier = Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(14.dp))
            .background(colors.onSurface.copy(alpha = .045f))
        Box(if (current == null) previewModifier
            .clickable(enabled = !importing, interactionSource = emptyInteraction, indication = null,
                role = Role.Button, onClickLabel = "添加壁纸") {
                picker.launch(arrayOf("image/*", "video/*"))
            }.motionPress(emptyInteraction, !importing)
            else previewModifier,
            contentAlignment = Alignment.Center) {
            if (current == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(28.dp), tint = colors.onSurfaceVariant)
                    Text("添加壁纸", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            } else {
                WallpaperPreview(preview, selected.kind, selected.adjustment, Modifier.fillMaxSize())
                if (selected.kind == WallpaperKind.VIDEO) Icon(Icons.Outlined.VideoFile, "视频壁纸",
                    Modifier.align(Alignment.BottomStart).padding(8.dp).size(22.dp)
                        .background(colors.surface.copy(alpha = .85f), RoundedCornerShape(6.dp)).padding(3.dp),
                    tint = colors.onSurface)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (importing) "正在添加…" else current?.name ?: "图片 · 静音视频",
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected.items.isNotEmpty()) Text("${selected.items.size} 张",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = { finishAdjustment(); steamVisible = true }, enabled = !importing,
                modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Storefront, "Steam 创意工坊", Modifier.size(21.dp), tint = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = { finishAdjustment(); pcVisible = true }, enabled = !importing,
                modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Computer, "连接电脑", Modifier.size(21.dp), tint = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = { finishAdjustment(); picker.launch(arrayOf("image/*", "video/*")) }, enabled = !importing,
                modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.AddPhotoAlternate, "添加图片或视频", Modifier.size(21.dp), tint = colors.onSurfaceVariant)
            }
            if (importing) Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                MotionBusyIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
            } else MotionIconButton(onClick = { finishAdjustment(); controlsExpanded = !controlsExpanded },
                enabled = current != null, modifier = Modifier.size(44.dp)
                    .semantics { stateDescription = if (controlsExpanded) "已展开" else "已收起" }) {
                Icon(Icons.Outlined.Tune, "壁纸设置", Modifier.size(21.dp),
                    tint = if (controlsExpanded) colors.primary else colors.onSurfaceVariant)
            }
        }
        if (selected.items.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selected.items, key = { it.mediaPath }) { item ->
                    val isCurrent = selected.mediaPath == item.mediaPath
                    val interaction = remember(item.mediaPath) { MutableInteractionSource() }
                    Column(Modifier.width(72.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp))
                            .background(colors.onSurface.copy(alpha = .045f))
                            .semantics { stateDescription = if (isCurrent) "当前壁纸" else "未使用" }
                            .selectable(selected = isCurrent, enabled = !importing, role = Role.RadioButton,
                                interactionSource = interaction, indication = null,
                                onClick = { if (!isCurrent) { finishAdjustment(); change { store.select(item.mediaPath) } } })
                            .motionPress(interaction, !importing, pressedScale = .96f)) {
                            AsyncImage(model = if (item.kind == WallpaperKind.VIDEO) item.posterPath else item.mediaPath,
                                contentDescription = item.name, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize())
                            if (isCurrent) Icon(Icons.Outlined.CheckCircle, null,
                                Modifier.align(Alignment.BottomEnd).padding(4.dp).size(18.dp)
                                    .background(colors.surface, RoundedCornerShape(9.dp)), tint = colors.onSurface)
                            if (item.kind == WallpaperKind.VIDEO) Icon(Icons.Outlined.VideoFile, null,
                                Modifier.align(Alignment.TopStart).padding(4.dp).size(16.dp)
                                    .background(colors.surface.copy(alpha = .8f), RoundedCornerShape(4.dp)).padding(2.dp),
                                tint = colors.onSurface)
                        }
                        Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp))
                            .background(if (isCurrent) colors.onSurface.copy(alpha = .65f) else colors.onSurface.copy(alpha = .04f)))
                    }
                }
            }
        }
        AnimatedVisibility(visible = controlsExpanded && current != null,
            enter = fadeIn(tween(if (motion) MangaMotion.Content else 0)) +
                expandVertically(tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing),
                    expandFrom = Alignment.Top),
            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                shrinkVertically(tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.ExitEasing),
                    shrinkTowards = Alignment.Top)) {
            Column(Modifier.fillMaxWidth().motionInputGate(controlsExpanded && !importing),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MotionTabs(selected = adjustmentTab, labels = listOf("外观", "摆放", "轮换"),
                        onSelect = { finishAdjustment(); adjustmentTab = it },
                        modifier = Modifier.weight(1f), treatment = MotionTabTreatment.Soft,
                        fullWidthIndicator = true, controlHeight = 44.dp,
                        track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                    MotionIconButton(enabled = !importing && current != null, onClick = {
                        finishAdjustment()
                        selected.mediaPath?.let { path -> change(FeelEvent.Confirm) { store.resetAdjustment(path) } }
                    }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Restore, "还原当前壁纸调节", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
                MotionTabContent(selected = adjustmentTab, contentKey = { it }, order = { it }, fillContent = false) { page ->
                    val adjustment = selected.adjustment
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        when (page) {
                            0 -> {
                                WallpaperAdjustmentSlider("强度", adjustment.strength, 0f..0.45f,
                                    "${(adjustment.strength * 100).roundToInt()}%",
                                    onChange = { value -> adjust { it.copy(strength = value) } }, onCommit = ::finishAdjustment)
                                WallpaperAdjustmentSlider("遮罩", adjustment.shade, 0f..0.6f,
                                    "${(adjustment.shade * 100).roundToInt()}%",
                                    onChange = { value -> adjust { it.copy(shade = value) } }, onCommit = ::finishAdjustment)
                                if (selected.kind == WallpaperKind.IMAGE) {
                                    WallpaperAdjustmentSlider("亮度", adjustment.brightness, .5f..1.5f,
                                        "${(adjustment.brightness * 100).roundToInt()}%",
                                        onChange = { value -> adjust { it.copy(brightness = value) } }, onCommit = ::finishAdjustment)
                                    WallpaperAdjustmentSlider("饱和度", adjustment.saturation, 0f..1.5f,
                                        "${(adjustment.saturation * 100).roundToInt()}%",
                                        onChange = { value -> adjust { it.copy(saturation = value) } }, onCommit = ::finishAdjustment)
                                    WallpaperAdjustmentSlider("对比度", adjustment.contrast, .5f..1.5f,
                                        "${(adjustment.contrast * 100).roundToInt()}%",
                                        onChange = { value -> adjust { it.copy(contrast = value) } }, onCommit = ::finishAdjustment)
                                    WallpaperAdjustmentSlider("模糊", adjustment.blur, 0f..24f,
                                        adjustment.blur.roundToInt().toString(), enabled = modernPlatformBlur,
                                        onChange = { value -> adjust { it.copy(blur = value) } }, onCommit = ::finishAdjustment)
                                    if (!modernPlatformBlur) Text("此设备暂不支持图片模糊",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                } else Text("视频可调强度与遮罩；色彩和模糊仅支持图片。",
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            1 -> if (selected.kind == WallpaperKind.IMAGE) {
                                MotionTabs(selected = if (adjustment.fit == WallpaperFit.FILL) 0 else 1,
                                    labels = listOf("填充", "完整"), onSelect = { index ->
                                        selected.mediaPath?.let { path -> change(null) {
                                            store.updateAdjustment(path) { it.copy(fit = if (index == 0) WallpaperFit.FILL else WallpaperFit.FIT) }
                                        } }
                                    }, treatment = MotionTabTreatment.Soft, role = Role.RadioButton,
                                    fullWidthIndicator = true, controlHeight = 44.dp,
                                    track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                                WallpaperAdjustmentSlider("缩放", adjustment.zoom, 1f..2.5f,
                                    "${(adjustment.zoom * 100).roundToInt()}%",
                                    onChange = { value -> adjust { it.copy(zoom = value) } }, onCommit = ::finishAdjustment)
                                WallpaperAdjustmentSlider("水平", adjustment.offsetX, -1f..1f,
                                    "${(adjustment.offsetX * 100).roundToInt()}",
                                    onChange = { value -> adjust { it.copy(offsetX = value) } }, onCommit = ::finishAdjustment)
                                WallpaperAdjustmentSlider("垂直", adjustment.offsetY, -1f..1f,
                                    "${(adjustment.offsetY * 100).roundToInt()}",
                                    onChange = { value -> adjust { it.copy(offsetY = value) } }, onCommit = ::finishAdjustment)
                            } else Text("视频保留原始填充；摆放调节仅支持图片。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            else -> {
                                val canRotate = selected.items.size > 1 && !importing
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .toggleable(value = selected.rotationEnabled, enabled = canRotate, role = Role.Switch,
                                        interactionSource = rotationInteraction, indication = null,
                                        onValueChange = { enabled -> change { store.setRotationEnabled(enabled) } })
                                    .motionPress(rotationInteraction, canRotate, pressedScale = .99f)
                                    .heightIn(min = 48.dp).graphicsLayer { alpha = if (canRotate) 1f else .5f },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Outlined.Autorenew, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                                    Text("轮换", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                                        MotionSwitchThumb(selected.rotationEnabled, rotationInteraction)
                                    }
                                }
                                if (!canRotate) Text("添加两张即可轮换", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                if (selected.rotationEnabled) {
                                    Text("间隔 · 分钟", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    MotionTabs(selected = WallpaperStore.ROTATION_INTERVALS.indexOf(selected.rotationMinutes),
                                        labels = WallpaperStore.ROTATION_INTERVALS.map(Int::toString),
                                        onSelect = { index -> change(null) { store.setRotationMinutes(WallpaperStore.ROTATION_INTERVALS[index]) } },
                                        role = Role.RadioButton, treatment = MotionTabTreatment.Soft,
                                        controlHeight = 44.dp, fullWidthIndicator = true,
                                        track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = { formatDetails = !formatDetails }) {
                        Icon(Icons.Outlined.Info, "支持的格式", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    MotionTextButton(enabled = !importing && current != null, onClick = { finishAdjustment(); removal = current; clearRequested = false; removalVisible = true }) {
                        Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        Text("移除", color = colors.onSurfaceVariant)
                    }
                    if (selected.items.size > 1) MotionIconButton(enabled = !importing,
                        onClick = { finishAdjustment(); clearRequested = true; removal = null; removalVisible = true }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.DeleteSweep, "清空全部壁纸", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    }
                }
                if (formatDetails) Text(
                    "支持设备可读取的图片与静音视频，单个最多 512 MB。视频切后台会暂停。\n创意工坊仅导入原图片或视频，实时场景暂不支持；也可通过「连接电脑」接收。本地选择仍可继续使用。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        val problem = notice ?: selected.error
        if (problem != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.weight(1f))
            if (selected.error != null && !importing) MotionTextButton(onClick = { store.retry(); notice = null }) { Text("重试") }
        }
    }
    WallpaperPcSheet(onDismiss = { pcVisible = false }, visible = pcVisible, backdrop = backdrop)
    SteamWallpaperSheet(onDismiss = { steamVisible = false }, visible = steamVisible, backdrop = backdrop)
    MotionDialog(visible = removalVisible,
        onDismissRequest = { removalVisible = false }) {
        Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow, tonalElevation = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (clearRequested) "清空全部壁纸？" else "移除这张壁纸？",
                    style = MaterialTheme.typography.titleMedium)
                if (!clearRequested) Text(removal?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MotionTextButton(onClick = { removalVisible = false }) { Text("取消") }
                    MotionTextButton(onClick = {
                        val target = removal
                        val all = clearRequested
                        change(FeelEvent.Confirm) {
                            if (all) store.clear() else if (target != null) store.remove(target.mediaPath)
                        }
                        removalVisible = false
                    }) { Text(if (clearRequested) "清空" else "移除", color = colors.error) }
                }
            }
        }
    }
}

/** Reuses the existing slider with one label/value row; commit belongs to the completed gesture. */
@Composable
private fun WallpaperAdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else .45f }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
        MotionSlider(value = value, valueRange = range, enabled = enabled, onValueChange = onChange,
            onValueChangeFinished = onCommit, formatValue = { valueText },
            modifier = Modifier.fillMaxWidth().semantics { stateDescription = "$label，$valueText" })
    }
}
