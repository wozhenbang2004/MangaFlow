package dev.mangaflow.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.mangaflow.storage.WallpaperKind
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One local chooser, one subtle intensity setting; preview never starts a second decoder. */
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            importing = true
            notice = null
            scope.launch {
                try { store.importMedia(uri) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { notice = failure.message ?: "壁纸未能导入，请重新选择。" }
                finally { importing = false }
            }
        }
    }
    Column(modifier.fillMaxWidth().animateContentSize(
        animationSpec = tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing)),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("壁纸", style = MaterialTheme.typography.bodyLarge)
                Text(if (importing) "正在导入…" else selected.name.ifBlank { "图片或静音视频" },
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (importing) CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 1.5.dp)
            else {
                MotionIconButton(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Image, "选择图片壁纸", Modifier.size(21.dp), tint = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = { picker.launch(arrayOf("video/*")) }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.VideoFile, "选择视频壁纸", Modifier.size(21.dp), tint = colors.onSurfaceVariant)
                }
                if (selected.mediaPath != null) MotionIconButton(onClick = { store.clear(); notice = null }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, "移除壁纸", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
        if (selected.mediaPath != null) {
            val preview = if (selected.kind == WallpaperKind.VIDEO) selected.posterPath else selected.mediaPath
            Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceContainerLow), contentAlignment = Alignment.Center) {
                if (preview != null) AsyncImage(model = File(preview), contentDescription = "当前壁纸静态预览",
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = selected.strength })
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

