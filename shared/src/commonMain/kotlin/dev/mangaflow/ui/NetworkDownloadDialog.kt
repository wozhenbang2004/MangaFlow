package dev.mangaflow.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.net.*
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.storage.BodySourceRegistry
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

/** Retained scenes share one content-size owner inside the bounded scrolling viewport. */
@Composable
internal fun NetworkDownloadDialog(visible: Boolean, backdrop: HazeState?, onDismiss: () -> Unit,
    repository: UnifiedMangaRepository? = null, registry: BodySourceRegistry? = null,
    initialScene: NetworkDownloadScene = NetworkDownloadScene.CONNECTION,
) {
    val context = LocalMangaContext.current
    val relay = remember(context) { RelayLinkController.get(context) }
    val relayState by relay.state.collectAsStateWithLifecycle()
    val autoCheck = remember(context) { NetworkAutoCheckController.get(context) }
    val autoState by autoCheck.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var scene by remember { mutableStateOf(initialScene) }
    var showInfo by remember { mutableStateOf(false) }
    var proxyEditing by remember { mutableStateOf(false) }
    var autoInfoRow by remember(autoCheck) { mutableStateOf<String?>(null) }
    var autoReceipt by remember(autoCheck) { mutableStateOf<String?>(null) }
    var resolvingOpen by remember { mutableStateOf(false) }
    var pendingReportedProxy by remember { mutableStateOf<DownloadProxyEndpoint?>(null) }
    var environment by remember { mutableStateOf<DownloadNetworkEnvironment?>(null) }
    var environmentLoading by remember { mutableStateOf(false) }
    var environmentError by remember { mutableStateOf(false) }
    var environmentRevision by remember { mutableIntStateOf(0) }
    var environmentReadGeneration by remember { mutableIntStateOf(0) }
    var suggestedProxy by remember { mutableStateOf<DownloadProxyEndpoint?>(null) }
    var suggestionRevision by remember { mutableIntStateOf(0) }
    val active = visible && foreground
    LaunchedEffect(active, environmentRevision, context) { if (active) relay.refresh() }
    LaunchedEffect(visible) {
        resolvingOpen = visible
        if (visible && initialScene != NetworkDownloadScene.CATEGORIES && autoCheck.state.value.restoring)
            scene = NetworkDownloadScene.CHECK
    }
    LaunchedEffect(visible, resolvingOpen, autoState.restoring) {
        if (visible && resolvingOpen && !autoState.restoring) {
            if (!proxyEditing && !showInfo) scene = when {
                initialScene == NetworkDownloadScene.CATEGORIES -> initialScene
                autoState.running || autoState.rows.isNotEmpty() -> NetworkDownloadScene.CHECK
                else -> initialScene
            }
            resolvingOpen = false
        }
    }
    LaunchedEffect(active, scene, autoState.complete, autoState.applied) {
        if (active && scene == NetworkDownloadScene.CHECK && (autoState.complete || autoState.applied)) showInfo = false
    }
    fun startCheck() {
        if (!active) return
        resolvingOpen = false
        proxyEditing = false
        scene = NetworkDownloadScene.CHECK
        autoInfoRow = null
        val current = autoCheck.state.value
        if (!current.restoring && !current.running && !current.applying && current.rows.isEmpty()) {
            autoReceipt = null
            autoCheck.start()
        }
    }
    fun useReportedProxy(endpoint: DownloadProxyEndpoint) {
        val current = environment
        val supported = platformKind != PlatformKind.IOS || endpoint.type != DownloadProxyType.HTTP
        if (active && !environmentLoading && !environmentError && current?.connected != false && supported &&
            current?.proxies?.any { it.endpoint == endpoint } == true) {
            resolvingOpen = false
            scene = NetworkDownloadScene.CONNECTION
            suggestedProxy = endpoint
            suggestionRevision++
        }
    }
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
    MotionDialog(visible, onDismissRequest = {
        if (scene == NetworkDownloadScene.CONNECTION && proxyEditing) proxyEditing = false
        else if (scene == NetworkDownloadScene.CHECK) { resolvingOpen = false; scene = NetworkDownloadScene.CONNECTION }
        else onDismiss()
    }, properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 520.dp,
        onClosed = {
            proxyEditing = false
            autoInfoRow = null
            showInfo = false
            pendingReportedProxy = null
            suggestedProxy = null
        }) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(scene) {
            focus.clearFocus(force = true)
            keyboard?.hide()
            if (scene != NetworkDownloadScene.CONNECTION) suggestedProxy = null
        }
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp)) {
            // MotionDialog already provides the safe, IME-adjusted viewport. Use it once.
            val maximumHeight = minOf(maxHeight, 640.dp)
            val panel: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (scene == NetworkDownloadScene.CHECK) MotionIconButton(onClick = {
                            resolvingOpen = false
                            scene = NetworkDownloadScene.CONNECTION
                        }, enabled = active, modifier = Modifier.size(44.dp), tooltip = "返回网络设置") {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回网络设置", Modifier.size(18.dp))
                        }
                        val title = if (scene == NetworkDownloadScene.CHECK)
                            if (autoState.historyOnly) "上次检测" else if (autoState.complete || autoState.applied) "检测结果"
                            else "网络检测" else "网络与下载"
                        MotionSwap(title, Modifier.weight(1f)) { heading ->
                            Text(heading, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MotionIconButton(onClick = { autoInfoRow = null; showInfo = !showInfo }, enabled = active,
                            modifier = Modifier.size(44.dp), tooltip = "连接与识别说明") {
                            Icon(Icons.Outlined.Info, "连接与识别说明", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        }
                        MotionIconButton(onClick = onDismiss, modifier = Modifier.size(44.dp), tooltip = "关闭网络与下载") {
                            Icon(Icons.Outlined.Close, "关闭网络与下载", Modifier.size(20.dp))
                        }
                    }
                    if (scene != NetworkDownloadScene.CHECK) {
                        val settingsScenes = listOf(NetworkDownloadScene.CONNECTION, NetworkDownloadScene.CATEGORIES)
                        MotionTabs(settingsScenes.indexOf(scene), settingsScenes.map { it.label },
                            onSelect = { resolvingOpen = false; scene = settingsScenes[it] }, modifier = Modifier.fillMaxWidth(),
                            treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 40.dp,
                            track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                    }
                    NetworkSceneContent(scene, NetworkDownloadScene.entries,
                        Modifier.weight(1f, fill = false).fillMaxWidth()) { shown ->
                        val contentModifier = if (shown == NetworkDownloadScene.CHECK) Modifier.fillMaxWidth()
                            else Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        Column(contentModifier.padding(top = 4.dp, bottom = 4.dp)) {
                            NetworkDownloadSettings(shown, backdrop, active = active && shown == scene,
                                environment = environment, suggestedProxy = suggestedProxy,
                                suggestionRevision = suggestionRevision,
                                onSuggestionConsumed = { suggestedProxy = null },
                                autoCheck = autoCheck, onStartCheck = ::startCheck,
                                onCheckEvidence = { autoInfoRow = it; showInfo = true },
                                onCheckSettings = { resolvingOpen = false; scene = NetworkDownloadScene.CONNECTION },
                                onCheckReceipt = { autoReceipt = it.takeIf(String::isNotBlank) },
                                proxyEditing = proxyEditing, onProxyEditing = { if (it) resolvingOpen = false; proxyEditing = it },
                                connectionSummary = {
                                    RelayLinkSummary(relayState, relay, enabled = active && shown == scene,
                                        onDetails = { showInfo = true })
                                    NetworkEnvironmentSummary(environment, environmentLoading, environmentError,
                                        enabled = active && shown == scene, compact = true,
                                        onRefresh = { environmentRevision++ }, onUse = ::useReportedProxy)
                                })
                        }
                    }
                }
            }
            if (backdrop != null) LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
                role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
            else Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow, tonalElevation = 0.dp) { panel() }
        }
    }
    MotionDialog(showInfo && visible, onDismissRequest = { showInfo = false },
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 480.dp,
        onClosed = {
            val endpoint = pendingReportedProxy
            pendingReportedProxy = null
            if (endpoint != null) useReportedProxy(endpoint)
        }) {
        val info: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (scene == NetworkDownloadScene.CHECK) "检测依据" else "网络信息",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    MotionIconButton(onClick = { showInfo = false }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "关闭网络信息", Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (scene == NetworkDownloadScene.CHECK) {
                        NetworkAutoEvidenceDetails(autoState, autoInfoRow, autoReceipt)
                    } else {
                        RelayLinkDetails(relayState, relay, enabled = active && showInfo)
                        NetworkEnvironmentSummary(environment, environmentLoading, environmentError,
                            enabled = active && showInfo, onRefresh = { environmentRevision++ }, onUse = { endpoint ->
                                pendingReportedProxy = endpoint
                                showInfo = false
                            })
                        NetworkConnectionHelp(relayState.installed)
                    }
                }
            }
        }
        if (backdrop != null) LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
            role = GlassSurfaceRole.PANEL, elevation = 0.dp) { info() }
        else Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow, tonalElevation = 0.dp) { info() }
    }
}

/** Adapt the existing retained scene motion; compact overview/editor share their real maximum. */
@Composable
internal fun <T : Any> NetworkSceneContent(
    selected: T, scenes: List<T>, modifier: Modifier = Modifier, stableSize: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val selectedIndex = scenes.indexOf(selected).coerceAtLeast(0)
    val sizing = if (stableSize) modifier else modifier.animateContentSize(
        if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap())
    Layout(modifier = sizing.clipToBounds(), content = {
        scenes.forEachIndexed { index, scene ->
            key(scene) {
                val alpha by animateFloatAsState(if (scene == selected) 1f else 0f,
                    if (motion) tween(MangaMotion.Quick) else snap(), label = "network-pane-alpha")
                val offset by animateFloatAsState(
                    if (scene == selected) 0f else if (index < selectedIndex) -10f else 10f,
                    if (motion) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
                    label = "network-pane-offset")
                Box(Modifier.fillMaxWidth().motionInputGate(scene == selected).graphicsLayer {
                    this.alpha = alpha
                    translationX = offset.dp.toPx()
                }) { content(scene) }
            }
        }
    }) { measurables, constraints ->
        val panes = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val target = panes[selectedIndex]
        val height = if (stableSize) panes.maxOf { it.height } else target.height
        layout(target.width, height) {
            panes.forEachIndexed { index, pane ->
                if (index != selectedIndex) pane.placeRelative(0, 0)
            }
            target.placeRelative(0, 0)
        }
    }
}

@Composable
private fun RelayLinkSummary(
    state: RelayLinkState, controller: RelayLinkController, enabled: Boolean, onDetails: () -> Unit,
) {
    if (!state.installed) return
    val colors = MaterialTheme.colorScheme
    val busy = state.phase == RelayLinkPhase.AUTHORIZING || state.phase == RelayLinkPhase.CONNECTING ||
        state.phase == RelayLinkPhase.SYNCING
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        MotionTextButton(onClick = onDetails, enabled = enabled, modifier = Modifier.weight(1f)) {
            Icon(Icons.Outlined.Link, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Relay 联动", style = MaterialTheme.typography.bodyMedium)
                Text(relayPhaseLabel(state), style = MaterialTheme.typography.labelSmall,
                    color = if (state.phase == RelayLinkPhase.ERROR || state.phase == RelayLinkPhase.CONFLICT)
                        colors.error else colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            busy -> MotionBusyIndicator(Modifier.padding(13.dp).size(18.dp), strokeWidth = 1.5.dp)
            !state.supported -> MotionTextButton(onClick = controller::openProvider, enabled = enabled) {
                Text("打开", style = MaterialTheme.typography.labelMedium)
            }
            !state.authorized -> MotionTextButton(onClick = controller::requestAuthorization, enabled = enabled) {
                Text("授权", style = MaterialTheme.typography.labelMedium)
            }
            else -> MotionIconButton(onClick = { if (state.enabled) controller.disable() else controller.enable() },
                enabled = enabled, modifier = Modifier.size(44.dp),
                tooltip = if (state.enabled) "暂停 Relay 联动" else "启用 Relay 联动") {
                Icon(if (state.enabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    if (state.enabled) "暂停 Relay 联动" else "启用 Relay 联动", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RelayLinkDetails(state: RelayLinkState, controller: RelayLinkController, enabled: Boolean) {
    if (!state.installed) return
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Relay 联动", style = MaterialTheme.typography.labelMedium)
                Text(relayPhaseLabel(state), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = controller::retry, enabled = enabled && state.supported,
                modifier = Modifier.size(44.dp), tooltip = "重新核对联动状态") {
                Icon(Icons.Outlined.Refresh, "重新核对联动状态", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
        if (state.authorized) {
            RelayEvidenceRow("规则保存", if (state.savedRevision > 0) "v${state.savedRevision} · ${state.ruleCount} 项" else "待同步",
                state.savedRevision > 0)
            RelayEvidenceRow("核心应用", if (state.phase == RelayLinkPhase.APPLIED && state.vpnConnected)
                "v${state.appliedRevision}" else if (state.appliedRevision > 0) "v${state.appliedRevision} · 待核对" else "未应用",
                state.phase == RelayLinkPhase.APPLIED && state.vpnConnected && state.savedRevision == state.appliedRevision)
            RelayEvidenceRow("实际请求", if (state.realRequestVerified) "联动入口已收到响应" else "尚未收到可验证响应",
                state.realRequestVerified)
            if (state.realRequestVerified) Text("响应只证明该次请求经过联动入口，不代表公网出口已经核实。",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        state.conflicts.forEach { conflict ->
            Text(relayConflictLabel(conflict), style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        if (state.phase == RelayLinkPhase.WAITING_FOR_VPN) Text("在 Relay 连接 VPN 后会自动恢复。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            MotionTextButton(onClick = controller::openProvider, enabled = enabled) {
                Text(if (state.supported) "打开 Relay" else "更新 Relay", style = MaterialTheme.typography.labelMedium)
            }
            if (state.supported && !state.authorized) MotionTextButton(onClick = controller::requestAuthorization, enabled = enabled) {
                Text("授权联动", style = MaterialTheme.typography.labelMedium)
            }
            if (state.authorized) MotionTextButton(onClick = controller::revoke, enabled = enabled) {
                Text("撤销授权", style = MaterialTheme.typography.labelMedium, color = colors.error)
            }
        }
    }
}

@Composable
private fun RelayEvidenceRow(label: String, value: String, confirmed: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (confirmed) Icons.Outlined.Check else Icons.Outlined.Info, null, Modifier.size(14.dp),
            tint = colors.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        Text(value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun relayPhaseLabel(state: RelayLinkState): String = when (state.phase) {
    RelayLinkPhase.NOT_AVAILABLE, RelayLinkPhase.NOT_INSTALLED -> "未检测到"
    RelayLinkPhase.INCOMPATIBLE -> "需要更新 Relay"
    RelayLinkPhase.AUTHORIZATION_REQUIRED -> "首次使用需要授权"
    RelayLinkPhase.AUTHORIZING -> "等待授权"
    RelayLinkPhase.DISABLED -> "已暂停"
    RelayLinkPhase.CONNECTING -> "正在核对"
    RelayLinkPhase.WAITING_FOR_VPN -> "等待 VPN 连接"
    RelayLinkPhase.SYNCING -> "正在同步规则"
    RelayLinkPhase.SAVED -> "规则已保存 · 等待应用"
    RelayLinkPhase.APPLIED -> "已应用 · ${state.ruleCount} 项规则"
    RelayLinkPhase.CONFLICT -> "规则冲突 · 查看详情"
    RelayLinkPhase.ERROR -> "联动中断 · 查看详情"
}

private fun relayConflictLabel(conflict: String): String = when {
    conflict.contains("bypass", ignoreCase = true) -> "MangaFlow 已被设为绕过 VPN，请在 Relay 调整应用规则。"
    conflict.contains("block", ignoreCase = true) -> "MangaFlow 已被禁止联网，请在 Relay 调整应用规则。"
    conflict.contains("global", ignoreCase = true) -> "全局模式需要额外的例外授权，请在 Relay 查看授权范围。"
    conflict.contains("revision", ignoreCase = true) -> "Relay 的规则已变化，请先核对后重试同步。"
    conflict.contains("app_policy", ignoreCase = true) -> "Relay 的应用强制出口与源级规则冲突，请在 Relay 调整。"
    else -> conflict
}

@Composable
private fun NetworkEnvironmentSummary(
    environment: DownloadNetworkEnvironment?, loading: Boolean, failed: Boolean, enabled: Boolean,
    onRefresh: () -> Unit, onUse: (DownloadProxyEndpoint) -> Unit, compact: Boolean = false,
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
            false -> if (compact) null else "未报告 VPN"
            null -> if (compact) null else "VPN 状态未知"
        }, when {
            environment.automaticProxyConfigured == true -> "自动代理"
            environment.systemProxyConfigured == true -> "系统代理"
            environment.systemProxyConfigured == false -> if (compact) null else "未报告系统代理"
            else -> if (compact) null else "代理状态未知"
        }).joinToString(" · ").ifBlank { "网络状态未知" }
    }
    if (compact) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Language, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(network, style = MaterialTheme.typography.bodySmall, color = colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (failed) Text("读取失败，点按重试", style = MaterialTheme.typography.labelSmall, color = colors.error)
            }
            if (loading) MotionBusyIndicator(Modifier.padding(13.dp).size(18.dp), strokeWidth = 1.5.dp)
            else MotionIconButton(onRefresh, enabled = enabled, modifier = Modifier.size(44.dp), tooltip = "刷新网络状态") {
                Icon(Icons.Outlined.Refresh, "刷新网络状态", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
        return
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
private fun NetworkConnectionHelp(relayInstalled: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("连接与识别", style = MaterialTheme.typography.labelMedium, color = colors.onSurface)
        listOf(
            if (relayInstalled) "Relay 联动只管理 MangaFlow 自己的规则。首次需要授权；规则保存、核心应用和实际请求响应分别记录。"
                else "识别系统公开的 VPN、代理地址和自动代理配置。检测到配置不代表请求一定使用它。",
            if (relayInstalled) "联动启用后的直连与代理由 Relay 执行，无需填写端口；共享图片域名使用专用入口。绕过 VPN、强制出口或授权变化会明确提示。"
                else "直连跳过 HTTP / SOCKS，系统 VPN 仍可能接管。外部软件需提供相应的分流能力。",
            "检测反映本次入口响应，不代表带宽或全部图片可用；可调整每项选择，确认后才修改配置。",
            "Steam 等独立连接会单独标记覆盖范围，普通接口可用不代表其所有连接都可用。",
            "每个漫画源可单独设置搜索、阅读、账号接口与新下载线路；网页认证仍遵循系统网络，进行中任务保留原线路。",
            "新请求使用新设置。已有下载保留原线路，需明确选择“使用当前设置重试”才更换。手工代理地址目前不支持账号密码。",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
    }
}
