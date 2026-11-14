package dev.mangaflow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.net.DownloadNetworkEnvironment
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.readDownloadNetworkEnvironment
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

internal enum class NetworkDownloadScene(val label: String) {
    CONNECTION("连接"), CATEGORIES("按类型"), CHECK("检查"),
}

/** Bounded Steam-panel adaptation: stationary chrome and one independently scrolling scene. */
@Composable
internal fun NetworkDownloadDialog(
    visible: Boolean,
    backdrop: HazeState?,
    onDismiss: () -> Unit,
) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val colors = MaterialTheme.colorScheme
    var scene by remember { mutableStateOf(NetworkDownloadScene.CONNECTION) }
    var environment by remember { mutableStateOf<DownloadNetworkEnvironment?>(null) }
    var environmentRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible, foreground, environmentRevision, context) {
        if (visible && foreground) {
            environment = null
            environment = withContext(Dispatchers.IO) { readDownloadNetworkEnvironment(context) }
        }
    }
    MotionDialog(visible, onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 640.dp) {
        val panelFocus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(scene) { panelFocus.clearFocus(force = true); keyboard?.hide() }
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            val panelHeight = minOf(maxHeight, LocalMangaWindow.current.height * .92f, 660.dp)
            val panel: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().height(panelHeight)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.SwapVert, null, Modifier.size(21.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                        Text("网络与下载", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭网络与下载") {
                            Icon(Icons.Outlined.Close, "关闭网络与下载", Modifier.size(20.dp))
                        }
                    }
                    MotionTabs(scene.ordinal, NetworkDownloadScene.entries.map { it.label },
                        onSelect = { scene = NetworkDownloadScene.entries[it] },
                        modifier = Modifier.fillMaxWidth(), treatment = MotionTabTreatment.Soft,
                        fullWidthIndicator = true, controlHeight = 40.dp,
                        track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                    MotionTabContent(scene, Modifier.weight(1f).fillMaxWidth(),
                        contentKey = { it }, order = { it.ordinal }, soft = true) { shown ->
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            NetworkDownloadSettings(shown, backdrop, active = visible && foreground && shown == scene)
                            if (shown == NetworkDownloadScene.CONNECTION) {
                                NetworkEnvironmentSummary(environment,
                                    enabled = visible && foreground && shown == scene,
                                    onRefresh = { environmentRevision++ })
                            }
                        }
                    }
                    if (panelHeight >= 280.dp) Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("默认 · " + downloadRouteLabel(settings.defaultMode),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("新建或手动重试时生效，进行中的下载保留原连接。",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (backdrop != null) LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
            else Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow,
                tonalElevation = 0.dp) { panel() }
        }
    }
}

@Composable
private fun NetworkEnvironmentSummary(
    environment: DownloadNetworkEnvironment?, enabled: Boolean, onRefresh: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val vpn = when (environment?.defaultNetworkUsesVpn) {
        true -> "默认路径经 VPN"
        false -> "默认路径未报告 VPN"
        null -> "VPN 状态未知"
    }
    val proxy = when (environment?.systemProxyConfigured) {
        true -> "系统已配置代理"
        false -> "系统未报告代理"
        null -> "系统代理状态未知"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("本应用默认网络路径快照", style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant)
            Text("$vpn · $proxy", style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
            Text("只读取系统状态，不识别外部软件、真实出口或绕行结果。",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        MotionIconButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.size(44.dp)
            .semantics { contentDescription = "刷新系统网络状态，不发起连接检查" }, tooltip = "刷新系统状态") {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        }
    }
}
