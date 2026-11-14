package dev.mangaflow.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.SteamWallpaperItem
import dev.mangaflow.storage.SteamWallpaperKind
import dev.mangaflow.storage.SteamWallpaperState
import dev.mangaflow.storage.createSteamWallpaperClient
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionDialog
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.platform.openUrl
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

/**
 * Adapts the existing PC panel, tabs and transfer feedback. Preview images never enter the store.
 * Keep this composed while closed: MotionDialog owns the outgoing content's lifetime.
 */
@Composable
internal fun SteamWallpaperSheet(
    onDismiss: () -> Unit,
    visible: Boolean = true,
    backdrop: HazeState? = null,
) {
    val context = LocalMangaContext.current
    var opened by remember(context) { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) opened = true }
    // Merely composing Settings must not restore Steam or download an optional component.
    if (!opened) return

    val client = remember(context) { createSteamWallpaperClient(context) }
    val state by client.state.collectAsStateWithLifecycle()
    var retained by remember(client) { mutableStateOf(state) }
    SideEffect { if (visible) retained = state }
    val display = if (visible) state else retained
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestState by rememberUpdatedState(state)
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var query by remember(client) { mutableStateOf(state.query) }
    var requested by remember(client) { mutableStateOf(false) }
    var searchAfterLogin by remember(client) { mutableStateOf(false) }
    var retryLogin by remember(client) { mutableStateOf(false) }
    var retryItem by remember(client) { mutableStateOf<SteamWallpaperItem?>(null) }
    var notice by remember(client) { mutableStateOf<String?>(null) }

    fun search(value: String, kind: SteamWallpaperKind, page: Int = 1) {
        requested = true
        retryLogin = false
        retryItem = null
        notice = null
        focus.clearFocus(force = true)
        keyboard?.hide()
        client.search(value.trim(), kind, page)
    }
    fun login() {
        searchAfterLogin = true
        retryLogin = true
        retryItem = null
        notice = null
        focus.clearFocus(force = true)
        keyboard?.hide()
        client.login()
    }
    fun download(item: SteamWallpaperItem) {
        retryLogin = false
        retryItem = item
        notice = null
        focus.clearFocus(force = true)
        keyboard?.hide()
        client.download(item)
    }
    fun cancel() {
        searchAfterLogin = false
        notice = null
        client.cancel()
    }
    val dismiss = {
        searchAfterLogin = false
        focus.clearFocus(force = true)
        keyboard?.hide()
        client.cancel()
        onDismiss()
    }

    DisposableEffect(client, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                // Steam's own app may be the foreground screen approving this same-phone login.
                // The bounded native login must survive that handoff; other work stays foreground.
                if (!(latestState.authenticating && !latestState.preparing)) client.cancel()
            } else if (event == Lifecycle.Event.ON_START) foreground = true
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            client.close()
        }
    }
    LaunchedEffect(client, visible) {
        if (!visible) {
            searchAfterLogin = false
            client.cancel()
        }
    }
    LaunchedEffect(visible, foreground, state.account?.steamId, state.authenticating, state.preparing) {
        if (visible && foreground && searchAfterLogin && state.account != null &&
            !state.authenticating && !state.preparing) {
            searchAfterLogin = false
            // Restore/open never triggers a search. This follows only an explicit login action.
            if (state.items.isEmpty()) search(query, state.kind)
        }
    }

    MotionDialog(visible = visible, onDismissRequest = dismiss) {
        val panel: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth()
                .heightIn(max = minOf(LocalMangaWindow.current.height * .88f, 640.dp))
                .padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Storefront, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text("创意工坊", style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(display.account?.accountName ?: "Wallpaper Engine",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (display.account != null) MotionIconButton(onClick = {
                        searchAfterLogin = false
                        retryItem = null
                        notice = null
                        client.logout()
                    }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Logout, "退出 Steam 登录", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                    } else if (display.supported && !display.restoring && !display.preparing &&
                        !display.authenticating && (requested || display.items.isNotEmpty())) {
                        MotionTextButton(onClick = ::login) { Text("登录") }
                    }
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "关闭创意工坊", Modifier.size(20.dp))
                    }
                }

                MotionTabContent(selected = display,
                    contentKey = { value -> when {
                        !value.supported -> "unsupported"
                        value.restoring -> "restore"
                        value.preparing -> "prepare"
                        value.authenticating -> "login"
                        else -> "browse"
                    } },
                    order = { value -> when {
                        !value.supported -> 0
                        value.restoring -> 1
                        value.preparing -> 2
                        value.authenticating -> 3
                        else -> 4
                    } },
                    modifier = Modifier.weight(1f, fill = false), fillContent = false) { page ->
                    when {
                        !page.supported -> Text(page.message ?: "此平台暂不支持 Steam 创意工坊。",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp))
                        page.restoring -> SteamWallpaperWaiting("正在恢复 Steam 登录…")
                        page.preparing -> Column(Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("正在准备 Steam 组件", style = MaterialTheme.typography.bodyMedium)
                            SteamWallpaperProgress(page.componentBytes, page.componentTotalBytes,
                                if (page.componentTotalBytes > 0L) null else "正在获取下载信息…")
                            Text("组件独立保存，完成后继续本次操作。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                MotionTextButton(onClick = ::cancel) { Text("取消") }
                            }
                        }
                        page.authenticating -> Column(Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            val url = page.qrUrl?.takeIf { it.startsWith("https://") && it.length <= 2048 }
                            if (url == null) {
                                SteamWallpaperWaiting("正在请求 Steam 登录…")
                            } else {
                                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    val edge = minOf(maxWidth, 220.dp)
                                    Image(painter = rememberQrCodePainter(data = url),
                                        contentDescription = "用 Steam 扫描并确认登录",
                                        modifier = Modifier.size(edge).clip(RoundedCornerShape(10.dp))
                                            .background(Color.White).padding(edge * .15f))
                                }
                                Text("用 Steam 扫码确认", style = MaterialTheme.typography.bodyMedium)
                                MotionTextButton(onClick = {
                                    if (!openUrl(context, url))
                                        notice = "未能打开登录链接，可用另一台设备的 Steam 扫码。"
                                }) {
                                    Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("在本设备打开登录链接")
                                }
                            }
                            Text("账号需拥有 Wallpaper Engine。在 Steam 确认后，回到此页即可。",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            MotionTextButton(onClick = ::cancel) { Text("取消登录") }
                        }
                        else -> SteamWallpaperBrowser(page, query, onQuery = { query = it.take(120) },
                            requested = requested,
                            onSearch = { search(query, page.kind) },
                            onKind = { kind -> search(query, kind) },
                            onPage = { number -> search(page.query, page.kind, number) },
                            onLogin = ::login, onDownload = ::download, onCancel = ::cancel)
                    }
                }

                val problem = notice ?: display.error
                val message = problem ?: display.message?.takeUnless { !display.supported }
                if (!message.isNullOrBlank()) Row(Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall, maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        color = if (problem != null) colors.error else colors.onSurfaceVariant)
                    if (problem != null && display.supported && !display.restoring &&
                        !display.preparing && !display.authenticating && !display.loading &&
                        display.transferId == null) {
                        MotionTextButton(onClick = {
                            when {
                                retryLogin -> login()
                                retryItem != null -> retryItem?.let(::download)
                                else -> search(display.query, display.kind, display.page)
                            }
                        }) { Text("重试") }
                    }
                } else if (display.importedCount > 0) Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.CheckCircle, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    Text("已加入壁纸库", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        if (backdrop != null) LiquidGlassSurface(state = backdrop, modifier = Modifier.fillMaxWidth(),
            cornerRadius = 22.dp, role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
        else Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow, tonalElevation = 0.dp) { panel() }
    }
}

@Composable
private fun SteamWallpaperBrowser(
    state: SteamWallpaperState,
    query: String,
    onQuery: (String) -> Unit,
    requested: Boolean,
    onSearch: () -> Unit,
    onKind: (SteamWallpaperKind) -> Unit,
    onPage: (Int) -> Unit,
    onLogin: () -> Unit,
    onDownload: (SteamWallpaperItem) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val available = state.transferId == null && !state.loading
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.account == null && !requested && state.items.isEmpty() && !state.loading) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("直接从创意工坊获取壁纸", style = MaterialTheme.typography.bodyMedium)
                Text("需要拥有 Wallpaper Engine 的 Steam 账号。首次使用会下载独立组件。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = onSearch) { Text("先浏览") }
                    MotionTextButton(onClick = onLogin) { Text("Steam 登录") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value = query, onValueChange = onQuery, enabled = available,
                    singleLine = true, modifier = Modifier.weight(1f).padding(vertical = 13.dp)
                        .semantics { contentDescription = "搜索创意工坊壁纸" },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (available) onSearch() }),
                    decorationBox = { input -> Box {
                        if (query.isEmpty()) Text("搜索壁纸", style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant)
                        input()
                    } })
                if (query.isNotEmpty()) MotionIconButton(onClick = { onQuery("") },
                    enabled = available, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, "清空壁纸搜索词", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                }
                MotionIconButton(onClick = onSearch, enabled = available, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Search, "搜索创意工坊", Modifier.size(20.dp))
                }
            }
            MotionTabs(selected = if (state.kind == SteamWallpaperKind.IMAGE) 0 else 1,
                labels = listOf("图片", "视频"), onSelect = { index ->
                    if (available) onKind(if (index == 0) SteamWallpaperKind.IMAGE else SteamWallpaperKind.VIDEO)
                }, modifier = Modifier.fillMaxWidth().motionInputGate(available),
                treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 40.dp,
                track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)

            if (state.transferId != null) Column(Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.transferName ?: "正在下载壁纸", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    MotionIconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "取消壁纸下载", Modifier.size(18.dp))
                    }
                }
                SteamWallpaperProgress(state.receivedBytes, state.totalBytes,
                    if (state.totalBytes > 0L && state.receivedBytes >= state.totalBytes)
                        "正在校验并加入壁纸库…" else null)
            } else if (state.loading) Row(Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                Text("正在获取壁纸…", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                MotionIconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, "取消壁纸搜索", Modifier.size(17.dp))
                }
            }

            MotionTabContent(selected = state, contentKey = { it.kind }, order = { it.kind.ordinal },
                modifier = Modifier.weight(1f, fill = false), fillContent = false) { result ->
                if (result.items.isEmpty()) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!result.loading) Text(
                            if (result.error != null) "暂时无法获取壁纸"
                            else if (!requested) "搜索或浏览创意工坊壁纸"
                            else if (result.kind == SteamWallpaperKind.IMAGE) "这一页没有可直接导入的图片"
                            else "这一页没有可直接导入的视频",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        Text("仅导入原图片和视频；实时场景暂不支持。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        if (!requested && !result.loading) MotionTextButton(onClick = onSearch) { Text("浏览壁纸") }
                    }
                } else {
                    val grid = rememberLazyGridState()
                    LaunchedEffect(result.query, result.kind, result.page) { grid.scrollToItem(0) }
                    LazyVerticalGrid(columns = GridCells.Adaptive(138.dp), state = grid,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                            .graphicsLayer { alpha = if (result.loading) .65f else 1f },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(result.items, key = { it.id }) { item ->
                            SteamWallpaperPreview(item, enabled = result.account != null &&
                                result.transferId == null && !result.loading, onDownload = { onDownload(item) })
                        }
                    }
                }
            }
            if (state.page > 1 || state.hasMore) Row(Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                MotionIconButton(onClick = { onPage(state.page - 1) }, enabled = available && state.page > 1,
                    modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.ChevronLeft, "上一页壁纸", Modifier.size(20.dp))
                }
                Text(state.page.toString(), style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
                MotionIconButton(onClick = { onPage(state.page + 1) }, enabled = available && state.hasMore,
                    modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.ChevronRight, "下一页壁纸", Modifier.size(20.dp))
                }
            }
            Text(if (state.account == null) "预览仅供浏览，登录后可下载。"
                else "下载原文件后加入壁纸库，预览图不作为壁纸导入。",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SteamWallpaperPreview(
    item: SteamWallpaperItem,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = .045f))) {
            AsyncImage(model = item.previewUrl, contentDescription = "${item.title}，创意工坊预览",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(item.title, style = MaterialTheme.typography.bodySmall, minLines = 2,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(item.sizeBytes?.takeIf { it > 0L }?.let(::steamWallpaperByteLabel).orEmpty(),
                Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            MotionIconButton(onClick = onDownload, enabled = enabled, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Download, "下载 ${item.title} 并加入壁纸库", Modifier.size(19.dp),
                    tint = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = .45f))
            }
        }
    }
}

@Composable
private fun SteamWallpaperWaiting(message: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MotionBusyIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SteamWallpaperProgress(received: Long, total: Long, stage: String?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (total > 0L) LinearProgressIndicator(
            progress = { (received.coerceAtLeast(0L).toDouble() / total).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.onSurface, trackColor = colors.onSurface.copy(alpha = .08f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (total <= 0L) MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            Text(stage ?: if (total > 0L)
                "${steamWallpaperByteLabel(received)} / ${steamWallpaperByteLabel(total)}"
                else if (received > 0L) "已下载 ${steamWallpaperByteLabel(received)}" else "正在下载…",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun steamWallpaperByteLabel(value: Long): String = when {
    value < 1024L -> "${value.coerceAtLeast(0L)} B"
    value < 1024L * 1024L -> "${value / 1024L} KB"
    else -> "${value / (1024L * 1024L)} MB"
}
