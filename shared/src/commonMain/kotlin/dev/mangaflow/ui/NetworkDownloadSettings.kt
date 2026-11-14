package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

/** Existing motion controls and quiet settings rows; no request is made to configure a route. */
@Composable
internal fun NetworkDownloadSettings(backdrop: HazeState? = null) {
    val context = LocalMangaContext.current
    val store = remember(context) { DownloadRoutingStore.get(context) }
    val settings by store.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var proxyExpanded by remember { mutableStateOf(false) }
    var categoriesExpanded by remember { mutableStateOf(false) }
    var host by remember(settings.proxy) { mutableStateOf(settings.proxy?.host.orEmpty()) }
    var port by remember(settings.proxy) { mutableStateOf(settings.proxy?.port?.toString().orEmpty()) }
    var type by remember(settings.proxy) { mutableStateOf(settings.proxy?.type ?: DownloadProxyType.SOCKS) }
    var notice by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("默认线路", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        MotionTabs(selected = settings.defaultMode.ordinal, labels = listOf("系统网络", "直连", "指定代理"),
            onSelect = { index ->
                notice = null
                try { store.setDefault(DownloadRouteMode.entries[index]) }
                catch (failure: DownloadRouteException) { notice = failure.message; proxyExpanded = true }
            }, modifier = Modifier.fillMaxWidth(), treatment = MotionTabTreatment.Soft,
            fullWidthIndicator = true, controlHeight = 40.dp,
            track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
        Text("直连会跳过应用和系统 HTTP 代理；外部 VPN 仍可能接管。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)

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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (settings.proxy != null) MotionTextButton(onClick = {
                    store.setProxy(null)
                    notice = "代理地址已移除，新的代理下载需配置后重试。"
                }) { Text("移除地址") }
                Spacer(Modifier.weight(1f))
                MotionTextButton(onClick = {
                    notice = null
                    try {
                        store.setProxy(DownloadProxyEndpoint(host.trim(), port.toIntOrNull() ?: 0, type))
                        proxyExpanded = false
                        notice = "代理地址已保存"
                    } catch (failure: DownloadRouteException) { notice = failure.message }
                }) { Text("保存") }
            }
            Text("填写外部代理软件提供的本地端口，当前支持无需账号密码的代理。SOCKS5 兼容更多下载类型；扩展源安装和 iOS HTTPS 下载暂不支持 HTTP 代理。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        RouteDisclosure("按下载类型设置", settings.categoryOverrides.size.takeIf { it > 0 }?.let {
            it.toString() + " 项独立设置"
        } ?: "全部跟随默认", categoriesExpanded, { categoriesExpanded = !categoriesExpanded })
        if (categoriesExpanded) {
            DownloadCategory.entries.forEach { category ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(downloadCategoryLabel(category), Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    DownloadRouteControl(category, backdrop = backdrop)
                }
            }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text("设置用于之后开始或手动重试的下载。外部代理支持分应用排除和本地端口时，可排除 MangaFlow，再让指定下载连接此端口。",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
    val selected = settings.categoryOverrides[category]
    val effective = selected ?: settings.defaultMode
    var expanded by remember(category) { mutableStateOf(false) }
    var notice by remember(category) { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        MotionTextButton(onClick = { notice = null; expanded = true }, enabled = enabled,
            modifier = Modifier.heightIn(min = 44.dp).semantics {
                contentDescription = downloadCategoryLabel(category) + "的后续下载默认线路，" +
                    downloadRouteLabel(effective) + if (selected == null) "，跟随默认" else ""
            }) {
            Icon(Icons.Outlined.SwapVert, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            Spacer(Modifier.width(5.dp))
            Text(if (selected == null) "默认 · " + downloadRouteLabel(effective)
                else "本类 · " + downloadRouteLabel(effective), style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    MotionDialog(visible = expanded, onDismissRequest = { expanded = false }, maximumWidth = 420.dp) {
        val panel: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth()
                .heightIn(max = LocalMangaWindow.current.height * .86f)
                .verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("下载线路", style = MaterialTheme.typography.titleMedium)
                        Text(downloadCategoryLabel(category), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant)
                    }
                    MotionIconButton(onClick = { expanded = false }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "关闭线路选择", Modifier.size(20.dp))
                    }
                }
                val choices: List<DownloadRouteMode?> = listOf(null) + DownloadRouteMode.entries
                choices.forEach { choice ->
                    val available = enabled && (choice != DownloadRouteMode.PROXY || settings.proxy != null)
                    RouteChoiceRow(
                        label = if (choice == null) "跟随默认 · " + downloadRouteLabel(settings.defaultMode)
                            else downloadRouteLabel(choice),
                        selected = selected == choice, enabled = available,
                        detail = if (choice == DownloadRouteMode.PROXY && settings.proxy == null) "先在设置中配置代理" else null,
                        onClick = {
                            notice = null
                            try { store.setCategory(category, choice); expanded = false }
                            catch (failure: DownloadRouteException) { notice = failure.message }
                        })
                }
                Text("用于之后开始或手动重试的本类下载。正在进行的任务保持原线路。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text("直连不保证绕过外部 VPN。", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
            }
        }
        if (backdrop != null) LiquidGlassSurface(state = backdrop, modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp, role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
        else Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow,
            tonalElevation = 0.dp) { panel() }
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
private fun RouteDisclosure(label: String, detail: String, expanded: Boolean, onClick: () -> Unit) {
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
    DownloadRouteMode.SYSTEM -> "系统网络"
    DownloadRouteMode.DIRECT -> "应用直连"
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
