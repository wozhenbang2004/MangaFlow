package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.outlined.SwapVert
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
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.net.SourceNetworkSettings
import dev.mangaflow.net.RelayLinkController
import dev.mangaflow.net.currentRelayBinding
import dev.mangaflow.net.NetworkAutoCheckController
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
    proxyEditing: Boolean = false,
    onProxyEditing: (Boolean) -> Unit = {},
    connectionSummary: @Composable () -> Unit = {},
) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    when (scene) {
        NetworkDownloadScene.CONNECTION -> DownloadConnectionSettings(
            store, active, environment, suggestedProxy, suggestionRevision, onSuggestionConsumed,
            proxyEditing, onProxyEditing, connectionSummary, onStartCheck)
        NetworkDownloadScene.CATEGORIES -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 300.dp * LocalMangaWindow.current.fontScale.coerceAtLeast(1f)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("默认 · " + downloadRouteLabel(settings.defaultMode),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DownloadCategory.entries.forEach { category ->
                    if (stacked) Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(downloadCategoryLabel(category), style = MaterialTheme.typography.bodyMedium)
                        DownloadRouteControl(category, backdrop = backdrop, enabled = active, compact = true,
                            modifier = Modifier.align(Alignment.End))
                    } else Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(downloadCategoryLabel(category), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        DownloadRouteControl(category, backdrop = backdrop, enabled = active, compact = true)
                    }
                }
                Text("各源单独线路优先用于漫画章节。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        NetworkDownloadScene.CHECK -> NetworkAutoCheckWorkspace(autoCheck, active)
    }
}

@Composable
private fun DownloadConnectionSettings(
    store: DownloadRoutingStore, active: Boolean, environment: DownloadNetworkEnvironment?,
    suggestedProxy: DownloadProxyEndpoint?, suggestionRevision: Int, onSuggestionConsumed: () -> Unit,
    proxyEditing: Boolean, onProxyEditing: (Boolean) -> Unit, connectionSummary: @Composable () -> Unit,
    onStartCheck: () -> Unit,
) {
    val settings by store.state.collectAsStateWithLifecycle()
    val context = LocalMangaContext.current
    val relay = remember(context) { RelayLinkController.get(context) }
    val relayState by relay.state.collectAsStateWithLifecycle()
    val relayDirect = remember(relayState) { currentRelayBinding(DownloadRouteMode.DIRECT) != null }
    val relayProxy = remember(relayState) { currentRelayBinding(DownloadRouteMode.PROXY) != null }
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
            Text("默认线路", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            MotionTabs(settings.defaultMode.ordinal, DownloadRouteMode.entries.map(::downloadRouteLabel),
                onSelect = { index ->
                    if (interactive) {
                        overviewNotice = null
                        val chosen = DownloadRouteMode.entries[index]
                        try { store.setDefault(chosen) }
                        catch (_: DownloadRouteException) {
                            startEditing(chosen == DownloadRouteMode.PROXY)
                        } catch (_: Exception) { overviewNotice = "线路未能保存，请重试" }
                    }
                }, modifier = Modifier.fillMaxWidth(), treatment = MotionTabTreatment.Soft,
                fullWidthIndicator = true, controlHeight = 40.dp,
                track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
            if (settings.defaultMode == DownloadRouteMode.DIRECT && !relayDirect && environment?.defaultNetworkUsesVpn == true)
                Text("VPN 仍可能接管直连", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            MotionTextButton(onClick = onStartCheck, enabled = interactive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Icon(Icons.Outlined.SwapVert, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("一键检测", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Icon(Icons.Outlined.ChevronRight, "检测全部网络配置", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
            MotionTextButton(onClick = { startEditing(false) }, enabled = interactive,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (relayProxy) "指定代理" else "代理地址", style = MaterialTheme.typography.bodyMedium)
                    Text(overviewNotice ?: settings.proxy?.let(::proxyAddressLabel) ?:
                        if (relayProxy) "可选 · 当前由 Relay 自动管理" else "未设置",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ChevronRight, "编辑代理地址", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
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
internal fun SourceNetworkSettingsControl(sourceId: String, enabled: Boolean = true) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val relay = remember(context) { RelayLinkController.get(context) }
    val relayState by relay.state.collectAsStateWithLifecycle()
    val relayProxy = remember(relayState) { currentRelayBinding(DownloadRouteMode.PROXY) != null }
    val relayDirect = remember(relayState) { currentRelayBinding(DownloadRouteMode.DIRECT) != null }
    val saved = settings.sourceOverrides[sourceId]
    val colors = MaterialTheme.colorScheme
    var expanded by remember(sourceId) { mutableStateOf(false) }
    var mode by remember(sourceId, saved) { mutableStateOf(saved?.mode) }
    var host by remember(sourceId, saved) { mutableStateOf(saved?.proxy?.host.orEmpty()) }
    var port by remember(sourceId, saved) { mutableStateOf(saved?.proxy?.port?.toString().orEmpty()) }
    var type by remember(sourceId, saved) { mutableStateOf(saved?.proxy?.type ?: DownloadProxyType.SOCKS) }
    var useManualProxy by remember(sourceId, saved) { mutableStateOf(saved?.proxy != null) }
    var notice by remember(sourceId) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun resetDraft() {
        mode = saved?.mode
        host = saved?.proxy?.host.orEmpty()
        port = saved?.proxy?.port?.toString().orEmpty()
        type = saved?.proxy?.type ?: DownloadProxyType.SOCKS
        useManualProxy = saved?.proxy != null
        notice = null
    }
    fun close() { expanded = false; focus.clearFocus(force = true); keyboard?.hide() }
    val routeLabel = runCatching { store.sourceSnapshot(sourceId) }.fold(
        onSuccess = { if (saved == null) "默认 · " + downloadRouteLabel(it.mode) else downloadRouteLabel(it.mode) },
        onFailure = { "待配置" })
    Column(Modifier.fillMaxWidth().motionInputGate(enabled), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RouteDisclosure("连接线路", routeLabel, expanded) {
            if (expanded) { resetDraft(); close() } else { resetDraft(); expanded = true }
        }
        // This lazy source-details row owns its single retained editor expansion.
        CopyShelfReveal(expanded) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val choices: List<DownloadRouteMode?> = listOf(null) + DownloadRouteMode.entries
                MotionTabs(choices.indexOf(mode), listOf("默认", "系统", "直连", "代理"),
                    onSelect = { mode = choices[it]; notice = null }, modifier = Modifier.fillMaxWidth(),
                    treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 36.dp, fontSize = 12.sp,
                    track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                if (mode == DownloadRouteMode.PROXY && relayProxy) {
                    MotionTabs(if (useManualProxy) 1 else 0, listOf("Relay", "指定代理"),
                        onSelect = { useManualProxy = it == 1; notice = null }, modifier = Modifier.fillMaxWidth(),
                        treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 36.dp,
                        fontSize = 12.sp, track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
                }
                if (mode == DownloadRouteMode.PROXY && (!relayProxy || useManualProxy)) {
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
                    (mode == DownloadRouteMode.PROXY && relayProxy && !useManualProxy))
                    Text("使用 Relay 联动 · 无需填写端口", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                Text("用于该源后续请求与新下载；本次下载显式线路优先。网页认证仍遵循系统网络。",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                saved?.proxy?.let { Text("已保存 · " + proxyAddressLabel(it), style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                notice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MotionTextButton(onClick = { resetDraft(); close() }, enabled = enabled) { Text("取消") }
                    MotionTextButton(onClick = {
                        try {
                            val selectedMode = mode
                            val manualProxy = !relayProxy || useManualProxy
                            if (selectedMode == DownloadRouteMode.PROXY && manualProxy && type == DownloadProxyType.HTTP &&
                                (sourceId.startsWith("js_") || platformKind == PlatformKind.IOS))
                                throw DownloadRouteException("此来源暂不支持 HTTP 代理，请选择 SOCKS5。")
                            val endpoint = if (selectedMode == DownloadRouteMode.PROXY && manualProxy)
                                DownloadProxyEndpoint(host.trim(), port.toIntOrNull() ?: 0, type) else null
                            store.setSource(sourceId, selectedMode?.let { SourceNetworkSettings(it, endpoint) })
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
    val relayProxy = remember(relayState) { currentRelayBinding(DownloadRouteMode.PROXY) != null }
    val relayDirect = remember(relayState) { currentRelayBinding(DownloadRouteMode.DIRECT) != null }
    DownloadRouteSelector(
        category = category, selected = settings.categoryOverrides[category],
        inherited = settings.defaultMode, hasProxy = settings.proxy != null || relayProxy,
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
    val relayProxy = remember(relayState) { currentRelayBinding(DownloadRouteMode.PROXY) != null }
    val relayDirect = remember(relayState) { currentRelayBinding(DownloadRouteMode.DIRECT) != null }
    DownloadRouteSelector(
        category = category, selected = selected,
        inherited = settings.categoryOverrides[category] ?: settings.defaultMode,
        hasProxy = settings.proxy != null || relayProxy, relayManaged = relayDirect || relayProxy, taskOnly = true, onSelect = onSelect,
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
    val heading = if (taskOnly) "本次下载线路" else "本类下载默认线路"
    val scopeHelp = if (taskOnly && category == DownloadCategory.CHAPTERS) "默认按所选漫画源确定；显式选择只用于本次下载。"
        else if (taskOnly) "只用于本次下载，不更改默认线路。"
        else "新任务使用此线路；已有任务需选择“使用当前设置重试”。"
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
            val choices: List<DownloadRouteMode?> = listOf(null) + DownloadRouteMode.entries
            choices.forEach { choice ->
                val sourceDefault = taskOnly && category == DownloadCategory.CHAPTERS && choice == null
                val needsProxy = !sourceDefault && (choice ?: inherited) == DownloadRouteMode.PROXY
                RouteChoiceRow(
                    label = if (sourceDefault) "跟随所选源" else if (choice == null) "跟随默认 · " + downloadRouteLabel(inherited)
                        else downloadRouteLabel(choice),
                    selected = selected == choice, enabled = enabled && (!needsProxy || hasProxy),
                    detail = if (needsProxy && !hasProxy) "先在设置中配置代理" else null,
                    onClick = {
                        notice = null
                        try { onSelect(choice); expanded = false }
                        catch (failure: DownloadRouteException) { notice = failure.message }
                        catch (_: Exception) { notice = "线路未能保存，请重试。" }
                    },
                )
            }
            if (showHelp && backdrop == null) {
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
            modifier = Modifier.heightIn(min = 44.dp).semantics {
                contentDescription = downloadCategoryLabel(category) + "的" + heading + "，" +
                    (if (followsSource) "跟随所选源" else downloadRouteLabel(effective) + if (selected == null) "，跟随默认" else "")
            }) {
            Icon(Icons.Outlined.SwapVert, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(5.dp))
            Text(if (followsSource) "跟随所选源" else if (compact) if (selected == null) "默认" else downloadRouteLabel(effective)
                else (if (taskOnly) "本次 · " else if (selected == null) "默认 · " else "本类 · ") +
                    (if (taskOnly && selected == null) "默认（" + downloadRouteLabel(effective) + "）"
                    else downloadRouteLabel(effective)), style = MaterialTheme.typography.labelMedium,
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
private fun RouteChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    detail: String?,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(if (selected) colors.onSurface.copy(alpha = .045f) else colors.surfaceContainerLow)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null,
            role = Role.Button, onClick = onClick).motionPress(interaction, enabled)
        .heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
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
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
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
    DownloadRouteMode.SYSTEM -> "跟随系统"
    DownloadRouteMode.DIRECT -> "直连"
    DownloadRouteMode.PROXY -> "代理"
}

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
}

internal fun proxyAddressLabel(endpoint: DownloadProxyEndpoint): String =
    (if (endpoint.type == DownloadProxyType.HTTP) "HTTP " else "SOCKS5 ") +
        (if (':' in endpoint.host) "[" + endpoint.host + "]" else endpoint.host) + ":" + endpoint.port
