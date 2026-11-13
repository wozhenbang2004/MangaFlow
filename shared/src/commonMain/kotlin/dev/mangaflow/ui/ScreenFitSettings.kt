@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.mangaflow.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.RoundedCorner
import androidx.compose.material.icons.outlined.SettingsOverscan
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.ScreenFitMode
import dev.mangaflow.storage.ScreenFitState
import dev.mangaflow.storage.ScreenFitStore
import dev.mangaflow.storage.WallpaperStore
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSlider
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionPress
import kotlin.math.min
import kotlin.math.roundToInt

/** Reuses the app's soft tabs, disclosure, press and source-adapted fluid slider inside one settings group. */
@Composable
internal fun ScreenFitSettings(modifier: Modifier = Modifier) {
    val context = LocalMangaContext.current
    val store = remember(context) { ScreenFitStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var advanced by rememberSaveable { mutableStateOf(false) }
    val disclosure = remember { MutableInteractionSource() }
    val reveal by animateFloatAsState(if (advanced) 1f else 0f,
        if (LocalMotionEnabled.current) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
        label = "screen-fit-disclosure")
    DisposableEffect(store) { onDispose { store.commit() } }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("屏幕显示", fontSize = 14.sp, modifier = Modifier.weight(1f), color = colors.onSurface)
            MotionIconButton(onClick = store::reset, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Restore, "还原屏幕设置", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
        ScreenFitPreview(state)
        MotionTabs(state.mode.ordinal, listOf("自动", "铺满", "避让", "安全"),
            onSelect = { store.setMode(ScreenFitMode.entries[it]) },
            modifier = Modifier.fillMaxWidth(), foreground = colors.onSurface,
            muted = colors.onSurfaceVariant, indicator = colors.onSurface.copy(alpha = .06f),
            treatment = MotionTabTreatment.Soft, fullWidthIndicator = true,
            controlHeight = 40.dp, indicatorHeight = 28.dp, fontSize = 12.sp)
        Text(when (state.mode) {
            ScreenFitMode.AUTO -> "内容铺满，操作自动避让系统栏。"
            ScreenFitMode.FILL -> "内容延伸至全部边缘，操作保留安全距离。"
            ScreenFitMode.AVOID_CUTOUT -> "内容避开系统报告的挖孔或刘海。"
            ScreenFitMode.SAFE -> "内容保留系统安全边距。"
        }, color = colors.onSurfaceVariant, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth().height(36.dp)
            .clickable(interactionSource = disclosure, indication = null, role = Role.Button,
                onClickLabel = if (advanced) "收起屏幕微调" else "展开屏幕微调",
                onClick = { advanced = !advanced })
            .motionPress(disclosure), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SettingsOverscan, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Text("微调", Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, color = colors.onSurfaceVariant)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp).graphicsLayer {
                rotationZ = reveal * 180f
            }, tint = colors.onSurfaceVariant)
        }
        MotionTabContent(advanced, Modifier.fillMaxWidth(), contentKey = { it },
            order = { if (it) 1 else 0 }, fillContent = false, soft = true) { visible ->
            if (visible) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScreenFitValue("内容边距", state.marginDp, Icons.Outlined.SettingsOverscan)
                MotionSlider(state.marginDp, { store.setMargin(it, persist = false) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    valueRange = 0f..32f, onValueChangeFinished = store::commit,
                    formatValue = { "${it.roundToInt()} dp" })
                ScreenFitValue("内容圆角", state.cornerDp, Icons.Outlined.RoundedCorner)
                MotionSlider(state.cornerDp, { store.setCorner(it, persist = false) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    valueRange = 0f..48f, onValueChangeFinished = store::commit,
                    formatValue = { "${it.roundToInt()} dp" })
                Text("圆角只裁切内容；背景始终铺满。", color = colors.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ScreenFitValue(label: String, value: Float, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        Text(label, Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, color = colors.onSurface)
        Text("${value.roundToInt()} dp", fontSize = 11.sp, color = colors.onSurfaceVariant)
    }
}

/** A schematic using the current wallpaper and real system inset ratios, not a claim about physical corner detection. */
@Composable
private fun ScreenFitPreview(state: ScreenFitState) {
    CompositionLocalProvider(LocalScreenFit provides state) {
        val context = LocalMangaContext.current
        val wallpaper = remember(context) { WallpaperStore.get(context) }
        val window = LocalMangaWindow.current
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val insets = screenFitViewportInsets()
        val left = insets.getLeft(density, direction)
        val right = insets.getRight(density, direction)
        val top = insets.getTop(density)
        val bottom = insets.getBottom(density)
        val windowWidth = with(density) { window.width.toPx() }.coerceAtLeast(1f)
        val windowHeight = with(density) { window.height.toPx() }.coerceAtLeast(1f)
        val margin = with(density) { state.marginDp.dp.toPx() }
        val corner = with(density) { state.cornerDp.dp.toPx() }
        val color = MaterialTheme.colorScheme.onSurface
        val modeName = listOf("自动", "铺满", "避让挖孔或刘海", "安全边距")[state.mode.ordinal]
        Box(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics { contentDescription = "内容区域预览；${modeName}，边距${state.marginDp.roundToInt()}，内容圆角${state.cornerDp.roundToInt()}" }) {
            AppWallpaper(wallpaper, Modifier.fillMaxSize(), active = false)
            Canvas(Modifier.fillMaxSize()) {
                val sx = size.width / windowWidth
                val sy = size.height / windowHeight
                val start = Offset((left + margin) * sx, (top + margin) * sy)
                val bounds = Size((size.width - (left + right + margin * 2) * sx).coerceAtLeast(0f),
                    (size.height - (top + bottom + margin * 2) * sy).coerceAtLeast(0f))
                val radius = CornerRadius(corner * min(sx, sy))
                drawRoundRect(color.copy(alpha = .045f), start, bounds, radius)
                drawRoundRect(color.copy(alpha = .25f), start, bounds, radius, style = Stroke(1.dp.toPx()))
            }
            Text("内容区域", Modifier.align(Alignment.Center), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
