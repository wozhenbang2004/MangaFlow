package dev.mangaflow.reader.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.reader.ReaderDialogSurface
import dev.mangaflow.reader.ReaderPanelHeading
import dev.mangaflow.reader.ReaderToggle
import dev.mangaflow.storage.ReaderPreferences
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val ReaderPreferences.panelLayoutKey: String
    get() = "${panelEngine.name}:${panelDirection.name}:$panelLearnedOrder:${PanelModelRuntime.VERSION}"

/** Reuses the reader's existing Ovio selector and press-stretch switches, with progressive disclosure. */
@Composable
internal fun PanelSettings(
    preferences: ReaderPreferences,
    update: (ReaderPreferences) -> Unit,
    onModels: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val engines = listOf(PanelEngine.CLASSIC, PanelEngine.NANO, PanelEngine.HIGH_QUALITY)
    val store = PanelModelStore.get(LocalContext.current)
    val runtimes = PanelRuntimeStore.get(LocalContext.current)
    val models by store.states.collectAsStateWithLifecycle()
    val components by runtimes.states.collectAsStateWithLifecycle()
    ReaderToggle("分镜阅读", preferences.panelEngine != PanelEngine.OFF,
        "按格阅读，随时查看整页") {
        update(preferences.copy(panelEngine = if (it) PanelEngine.CLASSIC else PanelEngine.OFF))
    }
    if (preferences.panelEngine != PanelEngine.OFF) {
        MotionTabs(
            selected = engines.indexOf(preferences.panelEngine).coerceAtLeast(0),
            labels = listOf("传统", "轻量 AI", "高精度 AI"),
            onSelect = { update(preferences.copy(panelEngine = engines[it])) },
            foreground = colors.onSurface, muted = colors.onSurfaceVariant,
            track = colors.onSurface.copy(alpha = .04f), indicator = colors.surface,
            fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
            controlHeight = 44.dp, fontSize = 13.sp, role = Role.RadioButton,
            selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal,
        )
        val id = when (preferences.panelEngine) {
            PanelEngine.NANO -> PanelModelId.NANO
            PanelEngine.HIGH_QUALITY -> PanelModelId.HIGH_QUALITY
            else -> null
        }
        val component = when (preferences.panelEngine) {
            PanelEngine.NANO -> PanelRuntimeId.LITERT
            PanelEngine.HIGH_QUALITY -> PanelRuntimeId.ONNX
            else -> null
        }
        val explanation = if (component != null && components[component]?.installed != true) {
            "识别组件未下载，暂用传统识别"
        } else if (id != null && models[id]?.installed != true) {
            "模型未下载，暂用传统识别"
        } else when (preferences.panelEngine) {
            PanelEngine.NANO -> "YOLO26 Nano · 适合手机"
            PanelEngine.HIGH_QUALITY -> "Koharu 1152 · 更耗内存和电量"
            else -> "无需下载 · 适合边框清晰的页面"
        }
        Text(explanation, color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp)
        MotionTabs(
            selected = if (preferences.panelDirection == PanelDirection.RIGHT_TO_LEFT) 0 else 1,
            labels = listOf("从右往左", "从左往右"),
            onSelect = { update(preferences.copy(panelDirection = if (it == 0)
                PanelDirection.RIGHT_TO_LEFT else PanelDirection.LEFT_TO_RIGHT)) },
            foreground = colors.onSurface, muted = colors.onSurfaceVariant,
            track = colors.onSurface.copy(alpha = .04f), indicator = colors.surface,
            fullWidthIndicator = true, treatment = MotionTabTreatment.Soft,
            controlHeight = 44.dp, fontSize = 13.sp, role = Role.RadioButton,
            selectedFontWeight = FontWeight.SemiBold, unselectedFontWeight = FontWeight.Normal,
        )
        ReaderToggle("AI 阅读顺序", preferences.panelLearnedOrder,
            if (models[PanelModelId.ORDER]?.installed == true && components[PanelRuntimeId.ONNX]?.installed == true)
                "Inkwell · 辅助判断复杂分格"
            else "需下载排序模型和 ONNX 组件，未就绪时使用几何顺序") {
            update(preferences.copy(panelLearnedOrder = it))
        }
        TextButton(onClick = onModels) { Text("管理识别组件与模型") }
    }
}

@Composable
internal fun PanelModelsDialog(visible: Boolean, backdrop: HazeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val store = remember(context) { PanelModelStore.get(context) }
    val runtimes = remember(context) { PanelRuntimeStore.get(context) }
    val states by store.states.collectAsStateWithLifecycle()
    val components by runtimes.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 96).coerceAtLeast(160).dp
    MotionDialog(visible = visible, onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ReaderDialogSurface(Modifier.padding(horizontal = 16.dp).widthIn(max = 520.dp).fillMaxWidth(), backdrop) {
            Column(Modifier.heightIn(max = maxHeight).padding(horizontal = 18.dp)) {
                ReaderPanelHeading("识别组件与模型", "关闭下载管理", onDismiss)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("传统识别无需下载。AI 需下载对应组件和模型，下载后可离线使用。",
                        color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("运行组件", color = colors.onSurfaceVariant, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium)
                    PanelRuntimeId.entries.forEach { id ->
                        val spec = runtimes.spec(id)
                        val state = components[id] ?: PanelRuntimeState()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (id == PanelRuntimeId.LITERT) "轻量识别组件" else "高精度与排序组件",
                                        fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                                    Text("${if (id == PanelRuntimeId.LITERT) "LiteRT" else "ONNX"} ${spec.version} · ${runtimes.abi ?: "设备暂不支持"}",
                                        fontSize = 11.sp, color = colors.onSurfaceVariant)
                                }
                                TextButton(enabled = runtimes.abi != null && !state.pendingRemoval, onClick = {
                                    when {
                                        state.downloading -> runtimes.cancel(id)
                                        state.installed -> scope.launch { runtimes.remove(id) }
                                        else -> scope.launch { runtimes.download(id) }
                                    }
                                }) { Text(when {
                                    state.pendingRemoval -> "重启后删除"
                                    state.downloading -> "取消"
                                    state.installed -> "删除"
                                    state.error != null -> "重试"
                                    else -> "下载"
                                }) }
                            }
                            Text(if (id == PanelRuntimeId.LITERT) "供 YOLO 轻量模型使用，仅下载本机架构。"
                                else "高精度识别与 AI 排序共用，同版本无需重复下载。",
                                fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                            if (state.downloading) Text(state.progress?.let {
                                "正在下载 ${(it * 100).roundToInt()}%"
                            } ?: "正在核对下载信息", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            state.error?.let { Text(it, fontSize = 11.sp, lineHeight = 16.sp, color = colors.error) }
                        }
                    }
                    Text("模型", color = colors.onSurfaceVariant, fontSize = 12.sp,
                        fontWeight = FontWeight.Medium)
                    PanelModelId.entries.forEach { id ->
                        val spec = store.spec(id)
                        val state = states[id] ?: PanelModelState()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(spec.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        color = colors.onSurface)
                                    Text("${spec.sizeLabel} · ${spec.license}", fontSize = 11.sp,
                                        color = colors.onSurfaceVariant)
                                }
                                TextButton(onClick = {
                                    when {
                                        state.downloading -> store.cancel(id)
                                        state.installed -> scope.launch { store.remove(id) }
                                        else -> scope.launch { store.download(id) }
                                    }
                                }) { Text(when {
                                    state.downloading -> "取消"
                                    state.installed -> "删除"
                                    state.error != null -> "重试"
                                    else -> "下载"
                                }) }
                            }
                            Text(spec.description, fontSize = 12.sp, lineHeight = 18.sp,
                                color = colors.onSurfaceVariant)
                            TextButton(onClick = { uriHandler.openUri(spec.sourceUrl) }) { Text("来源与许可") }
                            if (state.downloading) Text(
                                state.progress?.let { "正在下载 ${(it * 100).roundToInt()}%" } ?: "正在连接",
                                color = colors.onSurfaceVariant, fontSize = 11.sp)
                            state.error?.let { Text(it, fontSize = 11.sp, lineHeight = 16.sp,
                                color = colors.error) }
                        }
                    }
                }
            }
        }
    }
}
