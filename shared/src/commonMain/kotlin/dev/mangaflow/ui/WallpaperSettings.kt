package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.VideoFile
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.mangaflow.storage.WallpaperKind
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionSwitchThumb
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Managed local choices and foreground rotation; previews never start another video decoder. */
@Composable
internal fun WallpaperSettings(
    store: WallpaperStore = WallpaperStore.get(LocalContext.current),
    modifier: Modifier = Modifier,
) {
    val selected by store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var formatDetails by remember { mutableStateOf(false) }
    val motion = LocalMotionEnabled.current
    val detailsRotation = animateFloatAsState(if (formatDetails) 180f else 0f,
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
        label = "wallpaper-format-details")
    val colors = MaterialTheme.colorScheme
    val rotationInteraction = remember { MutableInteractionSource() }
    fun change(action: () -> Unit) {
        try { action(); notice = null }
        catch (failure: Exception) { notice = failure.message ?: "壁纸设置未能保存，请重试。" }
    }
    val picker = rememberOpenDocumentPicker() { uri ->
        if (uri != null && !importing) {
            importing = true
            notice = null
            scope.launch {
                try { store.importMedia(uri) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { notice = failure.message ?: "壁纸未能导入，请重新选择。" }
                finally { releasePickedDocuments(listOf(uri)); importing = false }
            }
        }
    }
    Column(modifier.fillMaxWidth().animateContentSize(
        animationSpec = tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing)),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("壁纸", style = MaterialTheme.typography.bodyLarge)
                Text(if (importing) "正在添加…" else if (selected.items.size > 1)
                    "${selected.items.size} 张 · ${selected.name}" else selected.name.ifBlank { "添加图片或静音视频" },
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (importing) CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 1.5.dp)
            else {
                MotionIconButton(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Image, "添加图片壁纸", Modifier.size(21.dp), tint = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.VideoFile, "添加视频壁纸", Modifier.size(21.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
        if (selected.mediaPath != null) {
            if (selected.items.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(selected.items, key = { it.mediaPath }) { item ->
                        val current = selected.mediaPath == item.mediaPath
                        Column(Modifier.width(88.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceContainerLow)
                                .border(if (current) 2.dp else 0.dp,
                                    if (current) colors.primary else Color.Transparent, RoundedCornerShape(12.dp))
                                .semantics { stateDescription = if (current) "当前壁纸" else "未使用" }
                                .clickable(enabled = !importing, role = Role.Button,
                                    onClickLabel = "使用${item.name}") { change { store.select(item.mediaPath) } }) {
                                AsyncImage(model = if (item.kind == WallpaperKind.VIDEO) item.posterPath else item.mediaPath,
                                    contentDescription = item.name, contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize())
                                if (current) Icon(Icons.Outlined.CheckCircle, null,
                                    Modifier.align(Alignment.BottomStart).padding(5.dp).size(18.dp), tint = colors.primary)
                                MotionIconButton(enabled = !importing,
                                    onClick = { change { store.remove(item.mediaPath) } },
                                    modifier = Modifier.align(Alignment.TopEnd).size(44.dp)) {
                                    Icon(Icons.Outlined.DeleteOutline, "移除${item.name}", Modifier.size(18.dp), tint = colors.onSurface)
                                }
                            }
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            } else {
            val preview = if (selected.kind == WallpaperKind.VIDEO) selected.posterPath else selected.mediaPath
            Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceContainerLow), contentAlignment = Alignment.Center) {
                if (preview != null) AsyncImage(model = preview, contentDescription = "当前壁纸静态预览",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = selected.strength })
            }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val canRotate = selected.items.size > 1 && !importing
                Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .toggleable(value = selected.rotationEnabled, enabled = canRotate, role = Role.Switch,
                        interactionSource = rotationInteraction, indication = null,
                        onValueChange = { enabled -> change { store.setRotationEnabled(enabled) } })
                    .heightIn(min = 48.dp).graphicsLayer { alpha = if (canRotate) 1f else .5f },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Autorenew, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    Text("自动轮换", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        MotionSwitchThumb(selected.rotationEnabled, rotationInteraction)
                    }
                }
                TextButton(enabled = !importing, onClick = { change { store.clear() } }) { Text("清空") }
            }
            if (selected.items.size < 2) Text("再添加一张即可开启轮换", style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            if (selected.rotationEnabled) {
                Text("轮换间隔 · 分钟", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                MotionTabs(selected = WallpaperStore.ROTATION_INTERVALS.indexOf(selected.rotationMinutes),
                    labels = WallpaperStore.ROTATION_INTERVALS.map(Int::toString),
                    onSelect = { index -> change { store.setRotationMinutes(WallpaperStore.ROTATION_INTERVALS[index]) } },
                    modifier = Modifier.motionInputGate(!importing), role = Role.RadioButton,
                    treatment = MotionTabTreatment.Soft, controlHeight = 44.dp, fullWidthIndicator = true,
                    track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("背景浓度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${(selected.strength * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant)
            }
            Slider(value = selected.strength, valueRange = 0f..0.45f, enabled = !importing,
                onValueChange = { store.setStrength(it, persist = false) },
                onValueChangeFinished = { store.setStrength(store.state.value.strength) },
                modifier = Modifier.fillMaxWidth())
            if (selected.kind == WallpaperKind.VIDEO) Text("静音循环，切后台自动暂停",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        val problem = notice ?: selected.error
        if (problem != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.weight(1f))
            if (selected.error != null && !importing) TextButton(onClick = { store.retry(); notice = null }) { Text("重试") }
        }
        TextButton(onClick = { formatDetails = !formatDetails }) {
            Text(if (formatDetails) "收起格式说明" else "支持哪些格式", color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(16.dp).graphicsLayer { rotationZ = detailsRotation.value })
        }
        if (formatDetails) Text(
            "支持此设备可解码的图片、MP4 等视频，单个文件最多 512 MB。\nWallpaper Engine 请选原图片或视频；.pkg、.mpkg 和 project.json 不能直接导入。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

    }
}

