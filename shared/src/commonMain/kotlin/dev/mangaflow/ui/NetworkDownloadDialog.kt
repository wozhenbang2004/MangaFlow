package dev.mangaflow.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.net.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class NetworkDownloadScene(val label: String) {
    CONNECTION("连接"), CATEGORIES("按类型"), CHECK("检查"),
}

/** Retained dialog/scenes, with content-sized height inside a bounded scrolling viewport. */
@Composable
internal fun NetworkDownloadDialog(visible: Boolean, backdrop: HazeState?, onDismiss: () -> Unit) {
    val context = LocalMangaContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var scene by remember { mutableStateOf(NetworkDownloadScene.CONNECTION) }
    var showInfo by remember { mutableStateOf(false) }
    var environment by remember { mutableStateOf<DownloadNetworkEnvironment?>(null) }
    var environmentLoading by remember { mutableStateOf(false) }
    var environmentError by remember { mutableStateOf(false) }
    var environmentRevision by remember { mutableIntStateOf(0) }
    var environmentReadGeneration by remember { mutableIntStateOf(0) }
    var suggestedProxy by remember { mutableStateOf<DownloadProxyEndpoint?>(null) }
    var suggestionRevision by remember { mutableIntStateOf(0) }
    val active = visible && foreground
    DisposableEffect(active, context) {
        // Native callbacks can be off Main or race with close. This child lifetime
        // queues UI writes on Main and rejects them after unregistration/disposal.
        val events = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val release: () -> Unit = if (active) try {
            observeDownloadNetworkEnvironment(context) {
                events.launch(Dispatchers.Main.immediate) { environmentRevision++ }
            }
        } catch (_: Exception) { {} } else ({})
        onDispose { events.cancel(); release() }
    }
    LaunchedEffect(active, environmentRevision, context) {
        val generation = ++environmentReadGeneration
        if (active) {
            environmentLoading = true
            environmentError = false
            try {
                val observed = withContext(Dispatchers.IO) { readDownloadNetworkEnvironment(context) }
                if (generation == environmentReadGeneration) environment = observed
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { if (generation == environmentReadGeneration) environmentError = true
            } finally { if (generation == environmentReadGeneration) environmentLoading = false }
        } else environmentLoading = false
    }
    MotionDialog(visible, onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 520.dp) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(scene) { focus.clearFocus(force = true); keyboard?.hide() }
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            val maximumHeight = minOf(maxHeight, LocalMangaWindow.current.height * .9f, 640.dp)
            val panel: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("网络与下载", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MotionIconButton(onClick = { showInfo = !showInfo }, enabled = active,
                            modifier = Modifier.size(44.dp), tooltip = "连接与识别说明") {
                            Icon(Icons.Outlined.Info, "连接与识别说明", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        }
                        MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭网络与下载") {
                            Icon(Icons.Outlined.Close, "关闭网络与下载", Modifier.size(20.dp))
                        }
                    }
                    MotionTabs(scene.ordinal, NetworkDownloadScene.entries.map { it.label },
                        onSelect = { scene = NetworkDownloadScene.entries[it] }, modifier = Modifier.fillMaxWidth(),
                        treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 40.dp,
                        track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                    MotionTabContent(scene, Modifier.weight(1f, fill = false).fillMaxWidth(),
                        contentKey = { it }, order = { it.ordinal }, fillContent = false, soft = true) { shown ->
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(top = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (shown == NetworkDownloadScene.CONNECTION) NetworkEnvironmentSummary(
                                environment, loading = environmentLoading, failed = environmentError,
                                enabled = active && shown == scene, onRefresh = { environmentRevision++ },
                                onUse = { endpoint ->
                                    val current = environment
                                    val explicitSupported = platformKind != PlatformKind.IOS || endpoint.type != DownloadProxyType.HTTP
                                    if (active && !environmentLoading && !environmentError && current?.connected != false &&
                                        current?.proxies?.any { it.endpoint == endpoint } == true && explicitSupported) {
                                        suggestedProxy = endpoint
                                        suggestionRevision++
                                    }
                                })
                            NetworkDownloadSettings(shown, backdrop, active = active && shown == scene,
                                environment = environment, suggestedProxy = suggestedProxy,
                                suggestionRevision = suggestionRevision,
                                onSuggestionConsumed = { suggestedProxy = null })
                            MotionTabContent(showInfo, Modifier.fillMaxWidth(), contentKey = { it },
                                order = { if (it) 1 else 0 }, fillContent = false) { expanded ->
                                if (expanded) NetworkConnectionHelp()
                            }
                        }
                    }
                    Text("设置用于新建与手动重试的下载", Modifier.padding(top = 3.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            if (backdrop != null) LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
            else Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow, tonalElevation = 0.dp) { panel() }
        }
    }
}

@Composable
private fun NetworkEnvironmentSummary(
    environment: DownloadNetworkEnvironment?, loading: Boolean, failed: Boolean, enabled: Boolean,
    onRefresh: () -> Unit, onUse: (DownloadProxyEndpoint) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val source = when (environment?.observation) {
        DownloadNetworkObservation.SYSTEM_SETTINGS -> "系统网络配置"
        DownloadNetworkObservation.APP_RUNTIME -> "应用网络配置"
        else -> "当前网络"
    }
    val network = when {
        environment?.connected == false -> "无可用网络"
        environment == null -> if (failed) "暂未读取到系统状态" else "正在读取系统状态"
        else -> listOfNotNull(when (environment.transport) {
            DownloadNetworkTransport.WIFI -> "Wi-Fi"
            DownloadNetworkTransport.CELLULAR -> "移动网络"
            DownloadNetworkTransport.ETHERNET -> "以太网"
            DownloadNetworkTransport.OTHER -> "已连接"
            null -> null
        }, when (environment.defaultNetworkUsesVpn) {
            true -> "VPN"
            false -> "未报告 VPN"
            null -> "VPN 状态未知"
        }, when {
            environment.automaticProxyConfigured == true -> "自动代理"
            environment.systemProxyConfigured == true -> "系统代理"
            environment.systemProxyConfigured == false -> "未报告系统代理"
            else -> "代理状态未知"
        }).joinToString(" · ")
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(source, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(network, style = MaterialTheme.typography.bodySmall, color = colors.onSurface,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (failed && environment != null) Text("刷新失败 · 保留上次结果",
                    style = MaterialTheme.typography.labelSmall, color = colors.error)
            }
            if (loading) MotionBusyIndicator(Modifier.padding(13.dp).size(18.dp), strokeWidth = 1.5.dp)
            else MotionIconButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.size(44.dp),
                tooltip = "刷新系统状态，不发起连接检查") {
                Icon(Icons.Outlined.Refresh, "刷新系统网络状态", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
        environment?.proxies?.distinct()?.forEach { reported ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                val target = when (reported.scope) {
                    DownloadProxyScope.DEFAULT -> ""
                    DownloadProxyScope.HTTP -> "HTTP · "
                    DownloadProxyScope.HTTPS -> "HTTPS · "
                    DownloadProxyScope.SOCKS -> ""
                }
                Text(target + proxyAddressLabel(reported.endpoint), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (platformKind == PlatformKind.IOS && reported.endpoint.type == DownloadProxyType.HTTP) {
                    Text("跟随系统", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                } else MotionTextButton(onClick = { onUse(reported.endpoint) },
                    enabled = enabled && !loading && !failed && environment?.connected != false) {
                    Text("填入", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (environment?.automaticProxyConfigured == true) Text("自动代理按目标决定地址，不能作为固定端口填入。",
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun NetworkConnectionHelp() {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("连接与识别", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
        listOf(
            "识别系统公开的 VPN、代理地址和自动代理配置；不读取外部节点、分流规则或未公开端口。检测到配置不代表请求一定使用它。",
            "直连跳过 HTTP / SOCKS，系统 VPN 仍可能接管。需要按下载分别直连或代理时，外部软件须支持应用排除与本地代理监听。",
            "Steam 连接沿用系统网络；指定 HTTP / SOCKS 出口需配置地址。部分扩展安装与 iOS 连接不支持 HTTP 代理，会明确提示。",
            "这里不改变普通搜索、在线阅读和登录。进行中任务保留已选策略，系统网络变化仍可能影响传输。",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
}
