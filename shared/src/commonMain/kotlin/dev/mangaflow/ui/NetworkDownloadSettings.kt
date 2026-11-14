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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadProxyEndpoint
import dev.mangaflow.net.DownloadProxyType
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.motionInputGate

/** The dialog's three scenes reuse the stored route and the existing task selectors. */
@Composable
internal fun NetworkDownloadSettings(
    scene: NetworkDownloadScene,
    backdrop: HazeState? = null,
    active: Boolean = true,
) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    when (scene) {
        NetworkDownloadScene.CONNECTION -> DownloadConnectionSettings(store, active)
        NetworkDownloadScene.CATEGORIES -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 300.dp * LocalMangaWindow.current.fontScale.coerceAtLeast(1f)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("未单独设置的类型跟随默认连接。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DownloadCategory.entries.forEach { category ->
                    if (stacked) Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(downloadCategoryLabel(category), style = MaterialTheme.typography.bodyMedium)
                        DownloadRouteControl(category, backdrop = backdrop, enabled = active,
                            modifier = Modifier.align(Alignment.End))
                    } else Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(downloadCategoryLabel(category), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        DownloadRouteControl(category, backdrop = backdrop, enabled = active)
                    }
                }
            }
        }
        NetworkDownloadScene.CHECK -> DownloadConnectionDiagnostics(store, active)
    }
}

@Composable
private fun DownloadConnectionSettings(store: DownloadRoutingStore, active: Boolean) {
    val settings by store.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var proxyExpanded by remember { mutableStateOf(false) }
    var collaborationExpanded by remember { mutableStateOf(false) }
    var host by remember(settings.proxy) { mutableStateOf(settings.proxy?.host.orEmpty()) }
    var port by remember(settings.proxy) { mutableStateOf(settings.proxy?.port?.toString().orEmpty()) }
    var type by remember(settings.proxy) { mutableStateOf(settings.proxy?.type ?: DownloadProxyType.SOCKS) }
    var notice by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().motionInputGate(active), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("默认连接", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        DownloadRouteMode.entries.forEach { mode ->
            RouteChoiceRow(
                label = downloadRouteLabel(mode), selected = settings.defaultMode == mode, enabled = active,
                detail = when (mode) {
                    DownloadRouteMode.SYSTEM -> "沿用设备当前网络与外部代理规则"
                    DownloadRouteMode.DIRECT -> "跳过 HTTP / SOCKS 代理，外部 VPN 仍可能接管"
                    DownloadRouteMode.PROXY -> settings.proxy?.let(::proxyAddressLabel) ?: "先配置外部软件提供的代理地址"
                },
                onClick = {
                    notice = null
                    try { store.setDefault(mode) }
                    catch (failure: DownloadRouteException) { notice = failure.message; proxyExpanded = true }
                },
            )
        }
        RouteDisclosure("代理地址", settings.proxy?.let(::proxyAddressLabel) ?: "未配置",
            proxyExpanded, { proxyExpanded = !proxyExpanded })
        if (proxyExpanded) {
            MotionTabs(selected = if (type == DownloadProxyType.SOCKS) 0 else 1,
                labels = listOf("SOCKS5", "HTTP"), onSelect = {
                    type = if (it == 0) DownloadProxyType.SOCKS else DownloadProxyType.HTTP
                }, modifier = Modifier.fillMaxWidth(), treatment = MotionTabTreatment.Soft,
                fullWidthIndicator = true, controlHeight = 40.dp,
                track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
            ProxyValueField(host, { host = it }, "主机名或 IP", Modifier.fillMaxWidth())
            ProxyValueField(port, { port = it.filter { c -> c in '0'..'9' }.take(5) }, "端口",
                Modifier.fillMaxWidth(), KeyboardType.Number)
            Text("从外部代理软件复制监听地址和端口，不要猜端口。当前支持无需账号密码的代理。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (settings.proxy != null) MotionTextButton(enabled = active, onClick = {
                    store.setProxy(null)
                    notice = "地址已移除，原有分类选择保留；指定代理需重新配置。"
                }) { Text("移除地址") }
                Spacer(Modifier.weight(1f))
                MotionTextButton(enabled = active, onClick = {
                    host = settings.proxy?.host.orEmpty()
                    port = settings.proxy?.port?.toString().orEmpty()
                    type = settings.proxy?.type ?: DownloadProxyType.SOCKS
                    proxyExpanded = false
                }) { Text("取消") }
                MotionTextButton(enabled = active, onClick = {
                    notice = null
                    try {
                        store.setProxy(DownloadProxyEndpoint(host.trim(), port.toIntOrNull() ?: 0, type))
                        proxyExpanded = false
                        notice = "代理地址已保存"
                    } catch (failure: DownloadRouteException) { notice = failure.message }
                }) { Text("保存") }
            }
            Text("SOCKS5 适用范围更广。扩展源安装和 iOS 的 HTTPS 下载暂不支持 HTTP 代理；不可用时会明确报错。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        RouteDisclosure("与外部代理配合", "查看两种方式", collaborationExpanded,
            { collaborationExpanded = !collaborationExpanded })
        if (collaborationExpanded) Column(Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("沿用外部代理 · 最省事", style = MaterialTheme.typography.labelLarge)
            Text("让 MangaFlow 保持在外部 VPN 的应用范围内，外部软件使用规则模式分流，这里选择跟随设备网络。是否直连由外部规则决定。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("按下载选择 · 需要外部软件支持", style = MaterialTheme.typography.labelLarge)
            Text("只有外部软件同时支持排除 MangaFlow 和本地 SOCKS 监听时，才可排除本应用，再将其实际监听地址填入这里。不同下载可选择不额外代理或指定代理，代理出口仍受外部规则控制。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("这些设置不改变普通搜索、在线阅读和账号登录。排除应用后，这些功能也需要当前网络能访问。MangaFlow 不会替你修改外部软件，也不能保证绕过 VPN。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    DownloadRouteSelector(
        category = category, selected = settings.categoryOverrides[category],
        inherited = settings.defaultMode, hasProxy = settings.proxy != null,
        taskOnly = false, onSelect = { store.setCategory(category, it) },
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
    DownloadRouteSelector(
        category = category, selected = selected,
        inherited = settings.categoryOverrides[category] ?: settings.defaultMode,
        hasProxy = settings.proxy != null, taskOnly = true, onSelect = onSelect,
        modifier = modifier, enabled = enabled, backdrop = backdrop,
    )
}

@Composable
private fun DownloadRouteSelector(
    category: DownloadCategory,
    selected: DownloadRouteMode?,
    inherited: DownloadRouteMode,
    hasProxy: Boolean,
    taskOnly: Boolean,
    onSelect: (DownloadRouteMode?) -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    backdrop: HazeState?,
) {
    val effective = selected ?: inherited
    var expanded by remember(category, taskOnly) { mutableStateOf(false) }
    var notice by remember(category, taskOnly) { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    val heading = if (taskOnly) "本次下载线路" else "本类下载默认线路"
    val panel: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(heading, style = MaterialTheme.typography.titleSmall)
                    Text(downloadCategoryLabel(category), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = { expanded = false }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, "关闭线路选择", Modifier.size(20.dp))
                }
            }
            val choices: List<DownloadRouteMode?> = listOf(null) + DownloadRouteMode.entries
            choices.forEach { choice ->
                val needsProxy = (choice ?: inherited) == DownloadRouteMode.PROXY
                RouteChoiceRow(
                    label = if (choice == null) "跟随默认 · " + downloadRouteLabel(inherited)
                        else downloadRouteLabel(choice),
                    selected = selected == choice, enabled = enabled && (!needsProxy || hasProxy),
                    detail = if (needsProxy && !hasProxy) "先在设置中配置代理" else null,
                    onClick = {
                        notice = null
                        try { onSelect(choice); expanded = false }
                        catch (failure: DownloadRouteException) { notice = failure.message }
                    },
                )
            }
            Text(if (taskOnly) "只用于本次下载，不更改默认线路。"
                else "用于之后开始或手动重试的本类下载。进行中的任务保持原线路。",
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("不额外代理只跳过 HTTP / SOCKS，外部 VPN 仍可能接管。", modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            notice?.let { Text(it, modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
    Box(modifier) {
        MotionTextButton(onClick = { notice = null; expanded = !expanded }, enabled = enabled,
            modifier = Modifier.heightIn(min = 44.dp).semantics {
                contentDescription = downloadCategoryLabel(category) + "的" + heading + "，" +
                    downloadRouteLabel(effective) + if (selected == null) "，跟随默认" else ""
            }) {
            Icon(Icons.Outlined.SwapVert, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(5.dp))
            Text((if (taskOnly) "本次 · " else if (selected == null) "默认 · " else "本类 · ") +
                (if (taskOnly && selected == null) "默认（" + downloadRouteLabel(effective) + "）"
                else downloadRouteLabel(effective)), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (backdrop != null) {
            CompactGlassPopover(visible = expanded && enabled, backdrop = backdrop,
                onDismiss = { expanded = false }, maximumWidth = 280.dp) { panel() }
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
        .heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
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
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 12.dp, vertical = 12.dp)) {
            BasicTextField(value, onValueChange = onValue, singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "代理" + label },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false,
                    imeAction = ImeAction.Done))
        }
    }
}

internal fun downloadRouteLabel(mode: DownloadRouteMode): String = when (mode) {
    DownloadRouteMode.SYSTEM -> if (dev.mangaflow.platform.platformKind == dev.mangaflow.platform.PlatformKind.ANDROID) "跟随手机网络" else "跟随系统网络"
    DownloadRouteMode.DIRECT -> "不额外代理"
    DownloadRouteMode.PROXY -> "指定代理"
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

private fun proxyAddressLabel(endpoint: DownloadProxyEndpoint): String =
    (if (endpoint.type == DownloadProxyType.HTTP) "HTTP " else "SOCKS5 ") +
        (if (':' in endpoint.host) "[" + endpoint.host + "]" else endpoint.host) + ":" + endpoint.port
