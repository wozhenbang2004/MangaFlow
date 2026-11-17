package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadNetworkEnvironment
import dev.mangaflow.net.DownloadProxyEndpoint
import dev.mangaflow.net.DownloadProxyProvider
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.SourceNetworkSettings
import dev.mangaflow.net.RelayLinkController
import dev.mangaflow.net.currentRelayBinding
import dev.mangaflow.net.NetworkAutoCheckController
import dev.mangaflow.net.NetworkAutoCheckState
import dev.mangaflow.platform.formatInstantMillis
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.motionInputGate

private val selectableDownloadRoutes = listOf(DownloadRouteMode.DIRECT, DownloadRouteMode.PROXY)

/** The retained scenes share route storage; detected settings only fill an editable draft. */
@Composable
internal fun NetworkDownloadSettings(
    scene: NetworkDownloadScene,
    backdrop: HazeState? = null,
    active: Boolean = true,
    environment: DownloadNetworkEnvironment? = null,
    suggestedProxy: DownloadProxyEndpoint? = null,
    suggestionRevision: Int = 0,
    onSuggestionConsumed: () -> Unit = {},
    autoCheck: NetworkAutoCheckController,
    onStartCheck: () -> Unit,
    onCheckHistory: () -> Unit,
    onCheckEvidence: (String?) -> Unit,
    onCheckSettings: () -> Unit,
    onCheckReceipt: (String) -> Unit,
    proxyEditing: Boolean = false,
    onProxyEditing: (Boolean) -> Unit = {},
    connectionSummary: @Composable () -> Unit = {},
    relaySummary: @Composable () -> Unit = {},
    repository: dev.mangaflow.data.UnifiedMangaRepository? = null,
    registry: dev.mangaflow.storage.BodySourceRegistry? = null,
) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val checkState by autoCheck.state.collectAsStateWithLifecycle()
    when (scene) {
        NetworkDownloadScene.CONNECTION -> DownloadConnectionSettings(
            store, active, environment, suggestedProxy, suggestionRevision, onSuggestionConsumed,
            proxyEditing, onProxyEditing, connectionSummary, relaySummary, onStartCheck, onCheckHistory, checkState, autoCheck)
        NetworkDownloadScene.CATEGORIES -> NetworkRouteDirectory(repository, registry, autoCheck, active, backdrop)
        NetworkDownloadScene.CHECK -> NetworkAutoCheckWorkspace(autoCheck, active,
            onCheckEvidence, onCheckSettings, onCheckReceipt)
    }
}

@Composable
private fun DownloadConnectionSettings(
    store: DownloadRoutingStore, active: Boolean, environment: DownloadNetworkEnvironment?,
    suggestedProxy: DownloadProxyEndpoint?, suggestionRevision: Int, onSuggestionConsumed: () -> Unit,
    proxyEditing: Boolean, onProxyEditing: (Boolean) -> Unit, connectionSummary: @Composable () -> Unit,
    relaySummary: @Composable () -> Unit,
    onStartCheck: () -> Unit, onCheckHistory: () -> Unit, checkState: NetworkAutoCheckState, autoCheck: NetworkAutoCheckController,
) {
    val settings by store.state.collectAsStateWithLifecycle()
    val context = LocalMangaContext.current
    val relay = remember(context) { RelayLinkController.get(context) }
    val relayState by relay.state.collectAsStateWithLifecycle()
    val relayDirect = remember(relayState, settings.proxyProvider) {
        settings.proxyProvider == DownloadProxyProvider.RELAY && currentRelayBinding(DownloadRouteMode.DIRECT) != null
    }
    val schedule by autoCheck.schedule.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var host by remember(settings.proxy) { mutableStateOf(settings.proxy?.host.orEmpty()) }
    var port by remember(settings.proxy) { mutableStateOf(settings.proxy?.port?.toString().orEmpty()) }
    var type by remember(settings.proxy) { mutableStateOf(settings.proxy?.type ?: DownloadProxyType.SOCKS) }
    var overviewNotice by remember { mutableStateOf<String?>(null) }
    var editorNotice by remember { mutableStateOf<String?>(null) }
    var useAfterSave by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun resetDraft() {
        host = settings.proxy?.host.orEmpty()
        port = settings.proxy?.port?.toString().orEmpty()
        type = settings.proxy?.type ?: DownloadProxyType.SOCKS
    }
    fun startEditing(use: Boolean) {
        resetDraft()
        useAfterSave = use
        editorNotice = null
        overviewNotice = null
        onProxyEditing(true)
    }
    fun finishEditing() {
        onProxyEditing(false)
        useAfterSave = false
        editorNotice = null
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    fun saveAddress() {
        try {
            if (host.isBlank()) throw DownloadRouteException("请填写代理地址")
            val number = port.toIntOrNull()
            if (number == null || number !in 1..65535) throw DownloadRouteException("端口需为 1–65535")
            if (platformKind == PlatformKind.IOS && type == DownloadProxyType.HTTP)
                throw DownloadRouteException("当前平台请使用 SOCKS5")
            val using = useAfterSave || store.state.value.defaultMode == DownloadRouteMode.PROXY
            store.setProxy(DownloadProxyEndpoint(host.trim(), number, type), useAsDefault = useAfterSave)
            overviewNotice = if (using) "已使用指定代理" else "代理地址已保存"
            finishEditing()
        } catch (failure: DownloadRouteException) {
            editorNotice = failure.message ?: "请检查代理地址和端口"
        } catch (_: Exception) { editorNotice = "设置未能保存，请重试" }
    }
    fun cancelEditing() { resetDraft(); overviewNotice = null; finishEditing() }
    LaunchedEffect(proxyEditing) {
        if (!proxyEditing) {
            useAfterSave = false
            editorNotice = null
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    LaunchedEffect(suggestionRevision, suggestedProxy, active) {
        if (active && suggestionRevision > 0 && suggestedProxy != null) {
            host = suggestedProxy.host
            port = suggestedProxy.port.toString()
            type = suggestedProxy.type
            useAfterSave = false
            editorNotice = null
            overviewNotice = null
            onProxyEditing(true)
            onSuggestionConsumed()
        }
    }
    // Both real, compact scenes are measured together. Switching to the editor cannot
    // append its height to the overview or move the centered window's heading.
    NetworkSceneContent(proxyEditing, listOf(false, true), Modifier.fillMaxWidth().motionInputGate(active),
        stableSize = true) { editing ->
        val interactive = active && editing == proxyEditing
        if (!editing) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            connectionSummary()
            Text("代理提供方", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            MotionTabs(if (settings.proxyProvider == DownloadProxyProvider.RELAY) 0 else 1,
                listOf("Relay", "自定义地址"), onSelect = { index ->
                    if (interactive) try {
                        store.setProxyProvider(if (index == 0) DownloadProxyProvider.RELAY else DownloadProxyProvider.CUSTOM)
                        overviewNotice = null
                    } catch (_: Exception) { overviewNotice = "切换未保存，请重试" }
                }, modifier = Modifier.fillMaxWidth(), role = Role.RadioButton,
                treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 44.dp,
                foreground = colors.primary, track = colors.onSurface.copy(alpha = .035f), indicator = colors.primary.copy(alpha = .10f),
                itemContent = { index, selected, color ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = color)
                        Text(if (index == 0) "Relay" else "自定义地址", style = MaterialTheme.typography.labelLarge, color = color)
                    }
                })
            if (settings.proxyProvider == DownloadProxyProvider.RELAY) relaySummary()
            Text("默认线路 · 仅用于未单独设置的项目", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            DownloadModeChoices(settings.defaultMode, enabled = interactive,
                onSelect = { chosen ->
                    if (interactive) {
                        overviewNotice = null
                        try { store.setDefault(chosen) }
                        catch (_: DownloadRouteException) {
                            if (settings.proxyProvider == DownloadProxyProvider.CUSTOM) startEditing(chosen == DownloadRouteMode.PROXY)
                            else overviewNotice = "请先授权并连接 Relay，再选择代理"
                        } catch (_: Exception) { overviewNotice = "线路未能保存，请重试" }
                    }
                })
            if (settings.defaultMode == DownloadRouteMode.SYSTEM)
                Text("沿用旧线路 · 点选后切换", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            if (settings.defaultMode == DownloadRouteMode.DIRECT && !relayDirect && environment?.defaultNetworkUsesVpn == true)
                Text("VPN 仍可能接管直连", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MotionTextButton(onClick = onStartCheck, enabled = interactive,
                modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                Icon(Icons.Outlined.SwapVert, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(when {
                    checkState.restoring -> "读取检测记录"
                    checkState.running -> "检测在后台继续"
                    else -> if (schedule.autoApply) "检测并优化" else "检测线路"
                }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                val checkTime = checkState.finishedAtMillis ?: checkState.startedAtMillis
                val checkValue = if (checkState.running) "${checkState.completed}/${checkState.total}"
                    else checkTime?.let { formatInstantMillis(it, "MM-dd HH:mm") }
                checkValue?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 1, softWrap = false); Spacer(Modifier.width(6.dp)) }
                Icon(Icons.Outlined.ChevronRight, "开始网络检测", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
            if (checkState.rows.isNotEmpty() && !checkState.running) MotionIconButton(onClick = onCheckHistory,
                enabled = interactive, modifier = Modifier.size(44.dp), tooltip = "查看上次检测") {
                Icon(Icons.Outlined.History, "查看上次检测", Modifier.size(18.dp))
            }
            }
            if (settings.proxyProvider == DownloadProxyProvider.CUSTOM) MotionTextButton(onClick = { startEditing(false) }, enabled = interactive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("代理地址", style = MaterialTheme.typography.bodyMedium)
                    Text(settings.proxy?.let(::proxyAddressLabel) ?: "点此设置",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ChevronRight, "编辑代理地址", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
            SettingsToggle("定期检测", schedule.enabled, Icons.Outlined.Schedule) {
                if (interactive && !autoCheck.updateSchedule(schedule.copy(enabled = it))) overviewNotice = "设置未保存，请重试"
            }
            SettingsToggle("检测后自动优化", schedule.autoApply, Icons.Outlined.AutoMode) {
                if (interactive && !autoCheck.updateSchedule(schedule.copy(autoApply = it))) overviewNotice = "设置未保存，请重试"
            }
            Text("只调整已验证且未锁定的项目。", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            overviewNotice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error) }
            Text("切换影响后续请求；已在途不强切，旧任务可能需要重试。", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                MotionIconButton(onClick = { resetDraft(); finishEditing() }, enabled = interactive,
                    modifier = Modifier.size(44.dp), tooltip = "返回连接设置") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回连接设置", Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("代理地址", style = MaterialTheme.typography.bodyMedium)
                    Text(if (useAfterSave) "保存后使用" else "默认线路不变",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                if (settings.proxy != null) MotionIconButton(onClick = {
                    try {
                        store.setProxy(null)
                        resetDraft()
                        overviewNotice = "代理地址已移除"
                        finishEditing()
                    } catch (_: Exception) { editorNotice = "地址未能移除，请重试" }
                }, enabled = interactive, modifier = Modifier.size(44.dp), tooltip = "移除代理地址") {
                    Icon(Icons.Outlined.DeleteOutline, "移除代理地址", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
            }
            MotionTabs(if (type == DownloadProxyType.SOCKS) 0 else 1, listOf("SOCKS5", "HTTP"),
                onSelect = { if (interactive) {
                    type = if (it == 0) DownloadProxyType.SOCKS else DownloadProxyType.HTTP
                    editorNotice = null
                } }, modifier = Modifier.fillMaxWidth(), treatment = MotionTabTreatment.Soft,
                fullWidthIndicator = true, controlHeight = 36.dp,
                track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 280.dp * LocalMangaWindow.current.fontScale.coerceAtLeast(1f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProxyValueField(host, { host = it; editorNotice = null }, "地址", Modifier.fillMaxWidth(),
                            placeholder = "主机名或 IP")
                        ProxyValueField(port, { port = it.filter(Char::isDigit).take(5); editorNotice = null }, "端口",
                            Modifier.fillMaxWidth(), KeyboardType.Number, "1–65535")
                    }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProxyValueField(host, { host = it; editorNotice = null }, "地址", Modifier.weight(1f),
                        placeholder = "主机名或 IP")
                    ProxyValueField(port, { port = it.filter(Char::isDigit).take(5); editorNotice = null }, "端口",
                        Modifier.width(88.dp), KeyboardType.Number, "1–65535")
                }
            }
            // Validation has one bounded slot; a failed save cannot resize the centered panel.
            Text(editorNotice.orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.error,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth < 240.dp * LocalMangaWindow.current.fontScale.coerceAtLeast(1f)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MotionButton(onClick = ::saveAddress, enabled = interactive, modifier = Modifier.fillMaxWidth()) {
                            Text(if (useAfterSave) "保存并使用" else "保存地址")
                        }
                        MotionTextButton(onClick = ::cancelEditing, enabled = interactive, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = ::cancelEditing, enabled = interactive) { Text("取消") }
                    MotionButton(onClick = ::saveAddress, enabled = interactive) {
                        Text(if (useAfterSave) "保存并使用" else "保存地址")
                    }
                }
            }
        }
    }
}


/** Per-source drafts stay private until an explicit save; running tasks retain their route snapshot. */
@Composable
internal fun SourceNetworkSettingsControl(sourceId: String, enabled: Boolean = true,
    category: DownloadCategory? = null, title: String = "连接线路") {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val relay = remember(context) { RelayLinkController.get(context) }
    val relayState by relay.state.collectAsStateWithLifecycle()
    val relaySelected = settings.proxyProvider == DownloadProxyProvider.RELAY
    val relayProxy = remember(relayState, relaySelected) { relaySelected && currentRelayBinding(DownloadRouteMode.PROXY) != null }
    val relayDirect = remember(relayState, relaySelected) { relaySelected && currentRelayBinding(DownloadRouteMode.DIRECT) != null }
    val saved = settings.sourceOverrides[sourceId]
    val colors = MaterialTheme.colorScheme
    var expanded by remember(sourceId) { mutableStateOf(false) }
    fun initialMode() = (saved?.mode ?: category?.let { settings.categoryOverrides[it] } ?: settings.defaultMode).takeIf { it in selectableDownloadRoutes }
    fun initialProxy() = if (saved == null) settings.proxy else saved.proxy
    var mode by remember(sourceId, saved) { mutableStateOf(initialMode()) }
    var host by remember(sourceId, saved) { mutableStateOf(initialProxy()?.host.orEmpty()) }
    var port by remember(sourceId, saved) { mutableStateOf(initialProxy()?.port?.toString().orEmpty()) }
    var type by remember(sourceId, saved) { mutableStateOf(initialProxy()?.type ?: DownloadProxyType.SOCKS) }
    var useManualProxy by remember(sourceId, saved) { mutableStateOf(saved?.proxy != null) }
    var notice by remember(sourceId) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun resetDraft() {
        mode = initialMode()
        host = initialProxy()?.host.orEmpty()
        port = initialProxy()?.port?.toString().orEmpty()
        type = initialProxy()?.type ?: DownloadProxyType.SOCKS
        useManualProxy = saved?.proxy != null
        notice = null
    }
    fun close() { expanded = false; focus.clearFocus(force = true); keyboard?.hide() }
    val routeLabel = runCatching { store.sourceSnapshot(sourceId, category) }.fold(
        onSuccess = { downloadRouteSummary(it.mode) },
        onFailure = { "待配置" })
    Column(Modifier.fillMaxWidth().motionInputGate(enabled), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RouteDisclosure(title, (if (saved == null) "跟随 · " else "") + routeLabel, expanded) {
            if (expanded) { resetDraft(); close() } else { resetDraft(); expanded = true }
        }
        // This lazy source-details row owns its single retained editor expansion.
        CopyShelfReveal(expanded) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DownloadModeChoices(mode, enabled = enabled, onSelect = { mode = it; notice = null })
                if (mode == null) Text("选择直连或代理后保存", style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant)
                if (mode == DownloadRouteMode.PROXY && !relaySelected) {
                    MotionTabs(if (useManualProxy) 1 else 0, listOf("全局地址", "此源地址"),
                        onSelect = { useManualProxy = it == 1; notice = null }, modifier = Modifier.fillMaxWidth(),
                        treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 36.dp,
                        fontSize = 12.sp, track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                }
                if (mode == DownloadRouteMode.PROXY && !relaySelected && useManualProxy) {
                    MotionTabs(if (type == DownloadProxyType.SOCKS) 0 else 1, listOf("SOCKS5", "HTTP"),
                        onSelect = { type = if (it == 0) DownloadProxyType.SOCKS else DownloadProxyType.HTTP; notice = null },
                        modifier = Modifier.fillMaxWidth(), treatment = MotionTabTreatment.Soft,
                        fullWidthIndicator = true, controlHeight = 36.dp, fontSize = 12.sp,
                        track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth < 260.dp * LocalMangaWindow.current.fontScale.coerceAtLeast(1f)) Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProxyValueField(host, { host = it; notice = null }, "主机名或 IP", Modifier.fillMaxWidth())
                            ProxyValueField(port, { port = it.filter(Char::isDigit).take(5); notice = null },
                                "端口", Modifier.fillMaxWidth(), KeyboardType.Number)
                        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProxyValueField(host, { host = it; notice = null }, "主机名或 IP", Modifier.weight(1f))
                            ProxyValueField(port, { port = it.filter(Char::isDigit).take(5); notice = null },
                                "端口", Modifier.width(80.dp), KeyboardType.Number)
                        }
                    }
                    if (sourceId.startsWith("js_") || platformKind == PlatformKind.IOS) Text(
                        "此来源的指定代理使用 SOCKS5；HTTP 暂不支持。",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                if (mode == DownloadRouteMode.DIRECT && !relayDirect) Text("直连跳过 HTTP / SOCKS，系统 VPN 仍可能接管。",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if ((mode == DownloadRouteMode.DIRECT && relayDirect) ||
                    (mode == DownloadRouteMode.PROXY && relaySelected))
                    Text(if (relayProxy || mode == DownloadRouteMode.DIRECT) "使用全局 Relay · 无需端口" else "全局 Relay 尚未就绪", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                Text("用于该源后续请求与新下载；本次下载显式线路优先。网页认证仍遵循系统网络。",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                if (!relaySelected) saved?.proxy?.let { Text("已保存 · " + proxyAddressLabel(it), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (saved != null) MotionTextButton(onClick = {
                        try { store.setSource(sourceId, null); close() }
                        catch (_: Exception) { notice = "设置未保存，请重试" }
                    }, enabled = enabled) { Text("清除此源设置") }
                    MotionTextButton(onClick = { resetDraft(); close() }, enabled = enabled) { Text("取消") }
                    MotionTextButton(onClick = {
                        try {
                            val selectedMode = mode?.takeIf { it in selectableDownloadRoutes }
                                ?: throw DownloadRouteException("请选择直连或代理。")
                            val manualProxy = !relaySelected && useManualProxy
                            if (selectedMode == DownloadRouteMode.PROXY && manualProxy && type == DownloadProxyType.HTTP &&
                                (sourceId.startsWith("js_") || platformKind == PlatformKind.IOS))
                                throw DownloadRouteException("此来源暂不支持 HTTP 代理，请选择 SOCKS5。")
                            val endpoint = if (selectedMode == DownloadRouteMode.PROXY && manualProxy)
                                DownloadProxyEndpoint(host.trim(), port.toIntOrNull() ?: 0, type)
                                else if (relaySelected || selectedMode == DownloadRouteMode.DIRECT) saved?.proxy else null
                            store.setSource(sourceId, SourceNetworkSettings(selectedMode, endpoint))
                            notice = null
                            close()
                        } catch (failure: DownloadRouteException) { notice = failure.message ?: "连接配置未能保存" }
                        catch (_: Exception) { notice = "连接配置未能保存，请重试" }
                    }, enabled = enabled) { Text("保存") }
                }
            }
        }
    }
}

/** This controls the category default for subsequent transfers; active jobs keep their snapshot. */
@Composable
internal fun DownloadRouteControl(
    category: DownloadCategory,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: HazeState? = null,
    compact: Boolean = false,
) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val relay = remember(context) { RelayLinkController.get(context) }
    val relayState by relay.state.collectAsStateWithLifecycle()
    val relaySelected = settings.proxyProvider == DownloadProxyProvider.RELAY
    val relayProxy = remember(relayState, relaySelected) { relaySelected && currentRelayBinding(DownloadRouteMode.PROXY) != null }
    val relayDirect = remember(relayState, relaySelected) { relaySelected && currentRelayBinding(DownloadRouteMode.DIRECT) != null }
    DownloadRouteSelector(
        category = category, selected = settings.categoryOverrides[category],
        inherited = settings.defaultMode, hasProxy = if (relaySelected) relayProxy else settings.proxy != null,
        relayManaged = relayDirect || relayProxy,
        taskOnly = false, onSelect = { store.setCategory(category, it) }, compact = compact,
        modifier = modifier, enabled = enabled, backdrop = backdrop,
    )
}

/** One-off selection only. The caller freezes snapshot(category, selected) before preparation. */
@Composable
internal fun TaskDownloadRouteControl(
    category: DownloadCategory,
    selected: DownloadRouteMode?,
    onSelect: (DownloadRouteMode?) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: HazeState? = null,
) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val relay = remember(context) { RelayLinkController.get(context) }
    val relayState by relay.state.collectAsStateWithLifecycle()
    val relaySelected = settings.proxyProvider == DownloadProxyProvider.RELAY
    val relayProxy = remember(relayState, relaySelected) { relaySelected && currentRelayBinding(DownloadRouteMode.PROXY) != null }
    val relayDirect = remember(relayState, relaySelected) { relaySelected && currentRelayBinding(DownloadRouteMode.DIRECT) != null }
    DownloadRouteSelector(
        category = category, selected = selected,
        inherited = settings.categoryOverrides[category] ?: settings.defaultMode,
        hasProxy = if (relaySelected) relayProxy else settings.proxy != null, relayManaged = relayDirect || relayProxy, taskOnly = true, onSelect = onSelect,
        modifier = modifier, enabled = enabled, backdrop = backdrop,
    )
}

@Composable
private fun DownloadRouteSelector(
    category: DownloadCategory,
    selected: DownloadRouteMode?,
    inherited: DownloadRouteMode,
    hasProxy: Boolean,
    relayManaged: Boolean,
    taskOnly: Boolean,
    onSelect: (DownloadRouteMode?) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    backdrop: HazeState?,
    compact: Boolean = false,
) {
    val effective = selected ?: inherited
    val followsSource = taskOnly && category == DownloadCategory.CHAPTERS && selected == null
    val inheritLabel = if (taskOnly && category == DownloadCategory.CHAPTERS) "跟随漫画源"
        else if (taskOnly) "跟随类型设置" else "跟随默认"
    var expanded by remember(category, taskOnly) { mutableStateOf(false) }
    var notice by remember(category, taskOnly) { mutableStateOf<String?>(null) }
    var showHelp by remember(category, taskOnly) { mutableStateOf(false) }
    var pendingHelp by remember(category, taskOnly) { mutableStateOf(false) }
    var popupPresent by remember(category, taskOnly) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(enabled) { if (!enabled) { expanded = false; pendingHelp = false; showHelp = false } }
    LaunchedEffect(enabled, pendingHelp, popupPresent, expanded) {
        if (enabled && pendingHelp && !popupPresent && !expanded) {
            pendingHelp = false
            showHelp = true
        }
    }
    val heading = if (taskOnly) "本次下载线路" else "下载线路"
    val scopeHelp = if (taskOnly && category == DownloadCategory.CHAPTERS) "默认按所选漫画源确定；显式选择只用于本次下载。"
        else if (taskOnly) "只用于本次下载，不更改默认线路。"
        else "新任务使用此线路；已有任务需选择“使用当前设置重试”。"
    val currentHelp = if (followsSource) "当前沿用所选漫画源的线路；选定后只用于本次下载。"
        else (if (selected == null) "$inheritLabel：" else "单独设置：") + downloadRouteLabel(effective) +
            if (effective == DownloadRouteMode.SYSTEM) "（旧配置，点选后切换）" else ""
    val directHelp = if (relayManaged) "直连与未指定地址的代理交给 Relay；联动中断时明确失败，不会自动换线。"
        else "直连只跳过 HTTP / SOCKS，系统 VPN 仍可能接管。"
    val panel: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(downloadCategoryLabel(category), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MotionIconButton(onClick = {
                    if (backdrop == null) showHelp = !showHelp
                    else { pendingHelp = true; expanded = false }
                }, enabled = enabled,
                    modifier = Modifier.size(44.dp), tooltip = if (showHelp) "收起线路说明" else "线路说明") {
                    Icon(Icons.Outlined.Info, "线路说明", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                }
                if (backdrop == null) MotionIconButton(onClick = { expanded = false }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, "关闭线路选择", Modifier.size(20.dp))
                }
            }
            RouteChoiceRow(
                mode = inherited, label = inheritLabel, selected = selected == null, enabled = enabled,
                detail = if (taskOnly && category == DownloadCategory.CHAPTERS) "按所选漫画源确定"
                    else downloadRouteLabel(inherited),
                onClick = {
                    notice = null
                    try { onSelect(null); expanded = false }
                    catch (failure: DownloadRouteException) { notice = failure.message }
                    catch (_: Exception) { notice = "线路未能保存，请重试。" }
                },
            )
            selectableDownloadRoutes.forEach { choice ->
                val needsProxy = choice == DownloadRouteMode.PROXY
                RouteChoiceRow(
                    mode = choice, label = downloadRouteLabel(choice),
                    selected = selected == choice, enabled = enabled && (!needsProxy || hasProxy),
                    detail = if (needsProxy && !hasProxy) "尚未配置" else null,
                    onClick = {
                        notice = null
                        try { onSelect(choice); expanded = false }
                        catch (failure: DownloadRouteException) { notice = failure.message }
                        catch (_: Exception) { notice = "线路未能保存，请重试。" }
                    },
                )
            }
            if (showHelp && backdrop == null) {
                Text(currentHelp, modifier = Modifier.padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(scopeHelp,
                    modifier = Modifier.padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(directHelp, modifier = Modifier.padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            notice?.let { Text(it, modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
    Box(modifier) {
        MotionTextButton(onClick = { notice = null; pendingHelp = false; expanded = !expanded }, enabled = enabled,
            contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 10.dp, vertical = 6.dp),
            modifier = Modifier.heightIn(min = 44.dp).semantics {
                contentDescription = downloadCategoryLabel(category) + "的" + heading + "，" +
                    currentHelp
            }) {
            Icon(Icons.Outlined.SwapVert, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(5.dp))
            Text(if (followsSource) "跟随漫画源" else if (selected == null) "默认 · ${downloadRouteSummary(effective)}"
                else downloadRouteSummary(effective), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (backdrop != null) {
            CompactGlassPopover(visible = expanded && enabled, backdrop = backdrop,
                onDismiss = { expanded = false }, onPresenceChange = { popupPresent = it },
                maximumWidth = 216.dp * LocalMangaWindow.current.fontScale.coerceIn(1f, 1.35f)) { panel() }
        }
    }
    if (backdrop != null) MotionDialog(visible = showHelp && enabled, onDismissRequest = { showHelp = false },
        maximumWidth = 360.dp) {
        LiquidGlassSurface(backdrop, Modifier.fillMaxWidth(), cornerRadius = 22.dp,
            role = GlassSurfaceRole.PANEL, softCard = true) {
            Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.panelHeight)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(heading, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    MotionIconButton(onClick = { showHelp = false }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "关闭线路说明", Modifier.size(20.dp))
                    }
                }
                Text(currentHelp, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(scopeHelp, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(directHelp, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
        }
    }
    if (backdrop == null) {
        MotionDialog(visible = expanded && enabled, onDismissRequest = { expanded = false }, maximumWidth = 360.dp) {
            Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow,
                tonalElevation = 0.dp) {
                Column(Modifier.fillMaxWidth()
                    .heightIn(max = LocalMangaWindow.current.height * .86f)
                    .verticalScroll(rememberScrollState()).padding(10.dp)) { panel() }
            }
        }
    }
}

@Composable
private fun DownloadModeChoices(
    selectedMode: DownloadRouteMode?, enabled: Boolean, onSelect: (DownloadRouteMode) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(colors.onSurface.copy(alpha = .035f)).padding(3.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        selectableDownloadRoutes.forEach { choice ->
            RouteChoiceRow(mode = choice, label = downloadRouteLabel(choice), selected = selectedMode == choice,
                enabled = enabled, detail = null, onClick = { onSelect(choice) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun RouteChoiceRow(
    mode: DownloadRouteMode,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    detail: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
        .background(colors.onSurface.copy(alpha = if (selected) .065f else 0f))
        .semantics(mergeDescendants = true) { this.selected = selected }
        .clickable(enabled = enabled, interactionSource = interaction, indication = null,
            role = Role.RadioButton, onClick = onClick).motionPress(interaction, enabled)
        .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (mode == DownloadRouteMode.DIRECT) Icons.Outlined.Language else Icons.Outlined.SwapVert,
            null, Modifier.size(16.dp), tint = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface.copy(alpha = if (enabled) 1f else .5f))
            detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant) }
        }
        if (selected) Icon(Icons.Outlined.Check, "已选", Modifier.size(18.dp), tint = colors.onSurface)
        else Spacer(Modifier.size(18.dp))
    }
}

@Composable
internal fun RouteDisclosure(label: String, detail: String, expanded: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Icon(if (expanded) Icons.Outlined.Close else Icons.Outlined.ChevronRight,
            if (expanded) "收起" else "展开", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun ProxyValueField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier,
    keyboardType: KeyboardType = KeyboardType.Ascii,
    placeholder: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 10.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart) {
            BasicTextField(value, onValueChange = onValue, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "代理" + label },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface),
                decorationBox = { input ->
                    Box(Modifier.fillMaxWidth()) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder,
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant.copy(alpha = .55f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false,
                    imeAction = ImeAction.Done))
        }
    }
}

internal fun downloadRouteLabel(mode: DownloadRouteMode): String = when (mode) {
    DownloadRouteMode.SYSTEM -> "系统线路"
    DownloadRouteMode.DIRECT -> "直连"
    DownloadRouteMode.PROXY -> "代理"
}

/** A legacy/late-bound route is not falsely presented as an explicit direct selection. */
internal fun downloadRouteSummary(mode: DownloadRouteMode): String =
    if (mode == DownloadRouteMode.SYSTEM) "线路" else downloadRouteLabel(mode)

internal fun downloadCategoryLabel(category: DownloadCategory): String = when (category) {
    DownloadCategory.CHAPTERS -> "漫画章节"
    DownloadCategory.STEAM_WALLPAPERS -> "Steam 连接与壁纸"
    DownloadCategory.STEAM_COMPONENT -> "Steam 连接组件"
    DownloadCategory.FONTS -> "字体"
    DownloadCategory.AI_MODELS -> "AI 模型"
    DownloadCategory.COMPONENTS -> "运行组件"
    DownloadCategory.SOURCE_PACKAGES -> "扩展源安装"
    DownloadCategory.UPDATES -> "应用更新"
    DownloadCategory.WEBDAV -> "WebDAV 同步"
    DownloadCategory.ANIME -> "动漫源、播放与下载"
}

internal fun proxyAddressLabel(endpoint: DownloadProxyEndpoint): String =
    (if (endpoint.type == DownloadProxyType.HTTP) "HTTP " else "SOCKS5 ") +
        (if (':' in endpoint.host) "[" + endpoint.host + "]" else endpoint.host) + ":" + endpoint.port
