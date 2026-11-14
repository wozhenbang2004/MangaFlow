package dev.mangaflow.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.SteamGuardMethod
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
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.openUrl
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

private enum class WorkshopPane { RESTORE, COMPONENT, LOGIN, ACCOUNT, BROWSE, PREVIEW, UNSUPPORTED }

/** Existing Ovio/PANEL components; live transfer data never becomes an animation target. */
@Composable
internal fun SteamWallpaperSheet(
    onDismiss: () -> Unit,
    visible: Boolean = true,
    backdrop: HazeState? = null,
) {
    val context = LocalMangaContext.current
    var opened by remember(context) { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) opened = true }
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
    val grid = rememberLazyGridState()
    LaunchedEffect(state.query, state.kind) { grid.scrollToItem(0) }
    var query by remember(client) { mutableStateOf(state.query) }
    var requested by remember(client) { mutableStateOf(false) }
    var showLogin by remember(client) { mutableStateOf(false) }
    var showAccount by remember(client) { mutableStateOf(false) }
    var loginMode by remember(client) { mutableStateOf(0) }
    var accountName by remember(client) { mutableStateOf("") }
    // Credentials are transient UI state, never saved to instance state or preferences.
    var password by remember(client) { mutableStateOf("") }
    var guardCode by remember(client) { mutableStateOf("") }
    var passwordVisible by remember(client) { mutableStateOf(false) }
    var searchAfterLogin by remember(client) { mutableStateOf(false) }
    var preview by remember(client) { mutableStateOf<SteamWallpaperItem?>(null) }
    var retryItem by remember(client) { mutableStateOf<SteamWallpaperItem?>(null) }
    var retryPage by remember(client) { mutableStateOf(1) }
    var notice by remember(client) { mutableStateOf<String?>(null) }

    fun clearFocus() {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    fun search(value: String, kind: SteamWallpaperKind, page: Int = 1) {
        requested = true
        retryPage = page
        retryItem = null
        notice = null
        clearFocus()
        client.search(value.trim(), kind, page)
    }
    fun openLogin() {
        searchAfterLogin = true
        showAccount = false
        showLogin = true
        loginMode = 0
        password = ""
        guardCode = ""
        passwordVisible = false
        notice = null
        clearFocus()
    }
    fun cancel() {
        searchAfterLogin = false
        password = ""
        guardCode = ""
        notice = null
        clearFocus()
        client.cancel()
    }
    fun download(item: SteamWallpaperItem) {
        if (state.account == null) {
            preview = item
            openLogin()
            return
        }
        retryItem = item
        notice = null
        clearFocus()
        client.download(item)
    }
    fun passwordLogin() {
        if (accountName.isBlank() || password.isEmpty() || state.authenticating || state.preparing) return
        val entered = password.toCharArray()
        password = ""
        guardCode = ""
        passwordVisible = false
        notice = null
        clearFocus()
        try { client.loginWithPassword(accountName.trim(), entered) }
        finally { entered.fill('\u0000') }
    }
    val dismiss = {
        cancel()
        onDismiss()
    }
    fun backToBrowse() {
        if (showLogin) {
            cancel()
            showLogin = false
        } else if (showAccount) showAccount = false
        else preview = null
        clearFocus()
    }

    DisposableEffect(client, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                password = ""
                guardCode = ""
                passwordVisible = false
                // A real login must survive approval in the Steam app on this phone.
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
            password = ""
            guardCode = ""
            passwordVisible = false
            searchAfterLogin = false
            client.cancel()
        }
    }
    LaunchedEffect(visible, foreground, state.account?.steamId, state.authenticating, state.preparing) {
        if (visible && foreground && searchAfterLogin && state.account != null &&
            !state.authenticating && !state.preparing) {
            searchAfterLogin = false
            showLogin = false
            password = ""
            guardCode = ""
            if (preview == null && state.items.isEmpty()) search(query, state.kind)
        }
    }
    val pane = when {
        !display.supported -> WorkshopPane.UNSUPPORTED
        display.restoring -> WorkshopPane.RESTORE
        display.preparing -> WorkshopPane.COMPONENT
        showLogin -> WorkshopPane.LOGIN
        showAccount && display.account != null -> WorkshopPane.ACCOUNT
        preview != null -> WorkshopPane.PREVIEW
        else -> WorkshopPane.BROWSE
    }

    MotionDialog(visible = visible, onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 864.dp) {
        val panel: @Composable () -> Unit = {
            // One bounded workspace. Progress, QR arrival, result count and errors cannot resize it.
            val panelHeight = minOf(LocalMangaWindow.current.height * .90f, 720.dp)
            Column(Modifier.fillMaxWidth().height(panelHeight)
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (pane == WorkshopPane.LOGIN || pane == WorkshopPane.ACCOUNT || pane == WorkshopPane.PREVIEW) {
                        MotionIconButton(onClick = ::backToBrowse, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.ChevronLeft, "返回壁纸浏览", Modifier.size(22.dp))
                        }
                    } else {
                        Icon(Icons.Outlined.Storefront, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(when (pane) {
                            WorkshopPane.LOGIN -> "Steam 登录"
                            WorkshopPane.ACCOUNT -> "Steam 账号"
                            WorkshopPane.PREVIEW -> "壁纸预览"
                            else -> "创意工坊"
                        }, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text("Wallpaper Engine", style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant, maxLines = 1)
                    }
                    if (pane == WorkshopPane.BROWSE || pane == WorkshopPane.PREVIEW) {
                        MotionTextButton(onClick = {
                            if (display.account == null) openLogin() else { showAccount = true; clearFocus() }
                        }, enabled = display.supported && display.transferId == null && !display.loading) {
                            Text(if (display.account == null) "登录" else "账号")
                        }
                    }
                    MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.Close, "关闭创意工坊", Modifier.size(20.dp))
                    }
                }

                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Steam 连接线路", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    DownloadRouteControl(DownloadCategory.STEAM_WALLPAPERS,
                        enabled = display.supported && !display.restoring && !display.preparing &&
                            !display.authenticating && !display.loading && display.transferId == null)
                }

                MotionTabContent(selected = pane, contentKey = { it }, order = { it.ordinal },
                    modifier = Modifier.weight(1f)) { page ->
                    when (page) {
                        WorkshopPane.UNSUPPORTED -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.Center) {
                            Text(display.message ?: "此平台暂不支持 Steam 创意工坊。",
                                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        WorkshopPane.RESTORE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            SteamWallpaperWaiting("正在恢复 Steam 登录…")
                        }
                        WorkshopPane.COMPONENT -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Spacer(Modifier.height(12.dp))
                            Text("首次准备 Steam 组件", style = MaterialTheme.typography.titleSmall)
                            Text("完成后继续本次操作。", style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                            SteamWallpaperProgress(display.componentBytes, display.componentTotalBytes,
                                if (display.componentTotalBytes > 0L) null else "正在获取下载信息…")
                        }
                        WorkshopPane.LOGIN -> Column(Modifier.fillMaxSize()) {
                            MotionTabs(selected = loginMode, labels = listOf("账号登录", "扫码登录"),
                                onSelect = { index ->
                                    if (index != loginMode) {
                                        cancel()
                                        showLogin = true
                                        searchAfterLogin = true
                                        loginMode = index
                                        if (index == 1) client.login()
                                    }
                                }, modifier = Modifier.fillMaxWidth(),
                                treatment = MotionTabTreatment.Soft, fullWidthIndicator = true,
                                controlHeight = 40.dp, track = colors.onSurface.copy(alpha = .045f),
                                indicator = colors.surface)
                            Spacer(Modifier.height(12.dp))
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (loginMode == 1) {
                                    SteamQrLogin(display, onOpen = { url ->
                                        if (!openUrl(context, url)) notice = "未能打开 Steam，请用另一台设备扫码。"
                                    }, onRetry = { notice = null; searchAfterLogin = true; client.login() })
                                } else if (display.authenticating && display.guardMethods.isNotEmpty()) {
                                    SteamGuardLogin(display, guardCode, onCode = { guardCode = it },
                                        onSubmit = { method ->
                                            val entered = guardCode
                                            guardCode = ""
                                            notice = null
                                            clearFocus()
                                            client.submitGuardCode(entered, method)
                                        })
                                } else if (display.authenticating) {
                                    SteamWallpaperWaiting("正在验证账号…")
                                } else {
                                    SteamLoginField(accountName, { accountName = it }, "Steam 账号名称",
                                        enabled = !display.preparing)
                                    SteamLoginField(password, { password = it }, "密码",
                                        password = !passwordVisible, secret = true, enabled = !display.preparing,
                                        imeAction = ImeAction.Go, onDone = ::passwordLogin,
                                        trailing = {
                                            MotionIconButton(onClick = { passwordVisible = !passwordVisible },
                                                modifier = Modifier.size(44.dp)) {
                                                Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                                    if (passwordVisible) "隐藏密码" else "显示密码", Modifier.size(19.dp),
                                                    tint = colors.onSurfaceVariant)
                                            }
                                        })
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        MotionTextButton(onClick = ::passwordLogin,
                                            enabled = accountName.isNotBlank() && password.isNotEmpty() && !display.preparing) {
                                            Text("登录 Steam")
                                        }
                                    }
                                    Text("账号需拥有 Wallpaper Engine。验证方式由 Steam 返回。",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                        WorkshopPane.ACCOUNT -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(display.account?.accountName.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text(display.account?.steamId.orEmpty(), style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                MotionTextButton(onClick = {
                                    cancel()
                                    showAccount = false
                                    showLogin = false
                                    retryItem = null
                                    client.logout()
                                }) {
                                    Icon(Icons.Outlined.Logout, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("退出登录")
                                }
                            }
                        }
                        WorkshopPane.BROWSE -> SteamWallpaperBrowser(display, grid, query, { query = it.take(120) },
                            requested, onSearch = { search(query, display.kind) },
                            onKind = { kind -> search(query, kind) }, onPreview = { preview = it; clearFocus() })
                        WorkshopPane.PREVIEW -> preview?.let { item ->
                            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(colors.onSurface.copy(alpha = .045f)), contentAlignment = Alignment.Center) {
                                    AsyncImage(model = item.previewUrl, contentDescription = item.title + "，创意工坊预览",
                                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                                }
                                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Text((if (item.kind == SteamWallpaperKind.IMAGE) "图片" else "视频") +
                                    (item.sizeBytes?.takeIf { it > 0L }?.let { " · " + steamWallpaperByteLabel(it) } ?: ""),
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }

                // All transient feedback occupies this one slot, below the retained content.
                Column(Modifier.fillMaxWidth().height(64.dp * LocalMangaWindow.current.fontScale), verticalArrangement = Arrangement.Center) {
                    val problem = notice ?: display.guardError ?: display.error
                    val activeTransfer = display.transferId != null
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val text = when {
                                problem != null -> problem
                                activeTransfer -> display.transferName ?: "正在下载壁纸"
                                display.preparing -> "组件独立保存"
                                display.authenticating -> if (loginMode == 1) "在 Steam 确认后返回此处" else "等待 Steam 验证"
                                display.loading -> "正在获取壁纸…"
                                pane == WorkshopPane.LOGIN -> "密码仅用于本次登录"
                                pane == WorkshopPane.PREVIEW -> "下载原文件并应用，预览图仅供查看"
                                pane == WorkshopPane.BROWSE -> if (display.account == null) "可先浏览，下载时登录" else "原图片 · 动态视频"
                                else -> ""
                            }
                            Text(text, style = MaterialTheme.typography.labelSmall,
                                color = if (problem != null) colors.error else colors.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            if (activeTransfer) SteamWallpaperProgress(display.receivedBytes, display.totalBytes,
                                if (display.totalBytes > 0L && display.receivedBytes >= display.totalBytes)
                                    "正在校验并应用…" else null)
                        }
                        when {
                            activeTransfer || display.preparing || display.loading || display.authenticating -> {
                                MotionIconButton(onClick = ::cancel, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Outlined.Close, "取消当前 Steam 操作", Modifier.size(18.dp))
                                }
                            }
                            pane == WorkshopPane.PREVIEW && preview != null -> {
                                val item = preview
                                MotionTextButton(onClick = { item?.let(::download) }) {
                                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(if (display.account == null) "登录后下载" else if (problem != null) "重试下载" else "下载并应用")
                                }
                            }
                            pane == WorkshopPane.BROWSE && problem != null -> {
                                MotionTextButton(onClick = {
                                    notice = null
                                    val failedItem = retryItem
                                    if (display.canResumeSession) client.login()
                                    else if (failedItem != null) download(failedItem)
                                    else search(display.query, display.kind, retryPage)
                                }) { Text(if (display.canResumeSession) "恢复登录" else "重试") }
                            }
                            pane == WorkshopPane.BROWSE && display.hasMore -> {
                                MotionTextButton(onClick = { search(display.query, display.kind, display.page + 1) }) {
                                    Text("加载更多")
                                }
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.padding(horizontal = 12.dp)) {
            if (backdrop != null) LiquidGlassSurface(state = backdrop, modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.dp, role = GlassSurfaceRole.PANEL, elevation = 0.dp) { panel() }
            else Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow,
                tonalElevation = 0.dp) { panel() }
        }
    }
}

@Composable
private fun SteamLoginField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    enabled: Boolean,
    password: Boolean = false,
    secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, onValueChange = onValue, enabled = enabled, singleLine = true,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp, horizontal = 2.dp)
                    .semantics { contentDescription = label },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii,
                    autoCorrectEnabled = false, imeAction = imeAction),
                keyboardActions = KeyboardActions(onGo = { onDone() }, onDone = { onDone() }))
            trailing()
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun SteamQrLogin(
    state: SteamWallpaperState,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val url = state.qrUrl?.takeIf { it.startsWith("https://") && it.length <= 2048 }
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val edge = minOf(maxWidth, 208.dp)
            Box(Modifier.size(edge).clip(RoundedCornerShape(12.dp))
                .background(if (url != null) Color.White else colors.onSurface.copy(alpha = .045f)),
                contentAlignment = Alignment.Center) {
                if (url != null) Image(painter = rememberQrCodePainter(data = url),
                    contentDescription = "用 Steam 扫描并确认登录",
                    modifier = Modifier.fillMaxSize().padding(edge * .15f))
                else if (state.authenticating) MotionBusyIndicator(Modifier.size(22.dp), strokeWidth = 1.5.dp)
                else MotionTextButton(onClick = onRetry) { Text("获取二维码") }
            }
        }
        Text("用另一台设备的 Steam 扫码", style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant)
        MotionTextButton(onClick = { url?.let(onOpen) }, enabled = url != null) {
            Icon(Icons.Outlined.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("在本设备打开 Steam")
        }
    }
}

@Composable
private fun SteamGuardLogin(
    state: SteamWallpaperState,
    code: String,
    onCode: (String) -> Unit,
    onSubmit: (SteamGuardMethod) -> Unit,
) {
    val methods = state.guardMethods
    var selected by remember(methods) { mutableStateOf(0) }
    val method = methods.getOrNull(selected) ?: return
    val colors = MaterialTheme.colorScheme
    if (methods.size > 1) MotionTabs(selected = selected, labels = methods.map {
        when (it) {
            SteamGuardMethod.EMAIL_CODE -> "邮箱码"
            SteamGuardMethod.AUTHENTICATOR_CODE -> "验证器"
            SteamGuardMethod.MOBILE_CONFIRMATION -> "手机确认"
            SteamGuardMethod.EMAIL_CONFIRMATION -> "邮箱确认"
        }
    }, onSelect = { selected = it; onCode("") }, modifier = Modifier.fillMaxWidth()
        .motionInputGate(!state.guardSubmitting), treatment = MotionTabTreatment.Soft,
        controlHeight = 40.dp, fullWidthIndicator = true,
        track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
    when (method) {
        SteamGuardMethod.EMAIL_CODE, SteamGuardMethod.AUTHENTICATOR_CODE -> {
            SteamLoginField(code, { value -> onCode(value.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
                .take(8).uppercase()) },
                if (method == SteamGuardMethod.EMAIL_CODE) "Steam 邮箱验证码" else "Steam Guard 验证码",
                enabled = !state.guardSubmitting, imeAction = ImeAction.Go,
                onDone = { if (code.isNotBlank() && !state.guardSubmitting) onSubmit(method) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MotionTextButton(onClick = { onSubmit(method) }, enabled = code.isNotBlank() && !state.guardSubmitting) {
                    Text(if (state.guardSubmitting) "正在验证…" else "验证")
                }
            }
        }
        SteamGuardMethod.MOBILE_CONFIRMATION -> SteamWallpaperWaiting("请在 Steam 手机应用中确认登录")
        SteamGuardMethod.EMAIL_CONFIRMATION -> SteamWallpaperWaiting("请打开 Steam 邮件中的确认链接")
    }
}

@Composable
private fun SteamWallpaperBrowser(
    state: SteamWallpaperState,
    grid: LazyGridState,
    query: String,
    onQuery: (String) -> Unit,
    requested: Boolean,
    onSearch: () -> Unit,
    onKind: (SteamWallpaperKind) -> Unit,
    onPreview: (SteamWallpaperItem) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val available = state.transferId == null && !state.loading
    val tileWidth = (if (LocalMangaWindow.current.width < 420.dp) 124.dp else 150.dp) *
        LocalMangaWindow.current.fontScale
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(query, onValueChange = onQuery, enabled = available, singleLine = true,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
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
                Icon(Icons.Outlined.Close, "清空搜索词", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = onSearch, enabled = available, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Search, "搜索创意工坊", Modifier.size(20.dp))
            }
        }
        MotionTabs(selected = if (state.kind == SteamWallpaperKind.IMAGE) 0 else 1,
            labels = listOf("图片", "视频"), onSelect = { index ->
                val kind = if (index == 0) SteamWallpaperKind.IMAGE else SteamWallpaperKind.VIDEO
                if (available && kind != state.kind) onKind(kind)
            }, modifier = Modifier.fillMaxWidth().motionInputGate(available),
            treatment = MotionTabTreatment.Soft, fullWidthIndicator = true, controlHeight = 40.dp,
            track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
        if (state.items.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(when {
                state.loading -> "正在寻找壁纸…"
                state.error != null -> "暂时无法获取壁纸"
                !requested -> "从创意工坊挑选壁纸"
                state.kind == SteamWallpaperKind.IMAGE -> "这一页没有可直接导入的图片"
                else -> "这一页没有可直接导入的视频"
            }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Text("仅接收原图片和视频，实时场景暂不支持。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (!requested && !state.loading) MotionTextButton(onClick = onSearch) { Text("浏览壁纸") }
        } else {
            // Keep the grid and its scroll owner through progress, errors and loading refreshes.
            LazyVerticalGrid(columns = GridCells.Adaptive(tileWidth), state = grid,
                modifier = Modifier.weight(1f).fillMaxWidth().motionInputGate(available),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.items, key = { it.id }) { item ->
                    val interaction = remember(item.id) { MutableInteractionSource() }
                    Column(Modifier.fillMaxWidth()
                        .clickable(enabled = available, interactionSource = interaction, indication = null,
                            role = Role.Button, onClickLabel = "预览 " + item.title) { onPreview(item) }
                        .motionPress(interaction, available),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(12.dp))
                            .background(colors.onSurface.copy(alpha = .045f))) {
                            AsyncImage(model = item.previewUrl, contentDescription = item.title,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Text(item.title, style = MaterialTheme.typography.bodySmall, minLines = 2,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.sizeBytes?.takeIf { it > 0L }?.let(::steamWallpaperByteLabel).orEmpty(),
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SteamWallpaperWaiting(message: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MotionBusyIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SteamWallpaperProgress(received: Long, total: Long, stage: String?) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (total > 0L) LinearProgressIndicator(
            progress = { (received.coerceAtLeast(0L).toDouble() / total).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.onSurface, trackColor = colors.onSurface.copy(alpha = .08f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (total <= 0L) MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            Text(stage ?: if (total > 0L)
                steamWallpaperByteLabel(received) + " / " + steamWallpaperByteLabel(total)
                else if (received > 0L) "已下载 " + steamWallpaperByteLabel(received) else "正在下载…",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun steamWallpaperByteLabel(value: Long): String = when {
    value < 1024L -> value.coerceAtLeast(0L).toString() + " B"
    value < 1024L * 1024L -> (value / 1024L).toString() + " KB"
    else -> (value / (1024L * 1024L)).toString() + " MB"
}
