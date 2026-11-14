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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteException
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.net.DownloadRoutingStore
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformContext
import dev.mangaflow.storage.SteamGuardMethod
import dev.mangaflow.storage.SteamWallpaperItem
import dev.mangaflow.storage.SteamWallpaperKind
import dev.mangaflow.storage.SteamWallpaperLibrary
import dev.mangaflow.storage.SteamWallpaperTransferPhase
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

private enum class WorkshopPane { LOGIN, ACCOUNT, BROWSE, PREVIEW, UNSUPPORTED }

/** The activity owner retains a pending challenge across host recreation; secrets stay in the view. */
private class SteamWorkshopOwner(context: PlatformContext) : ViewModel() {
    val client = createSteamWallpaperClient(context)
    val query = mutableStateOf("")
    var committedQuery = ""
    var acknowledgedImports = 0
    val requested = mutableStateOf(false)
    val library = mutableStateOf(SteamWallpaperLibrary.SEARCH)
    val kind = mutableStateOf(SteamWallpaperKind.VIDEO)
    val showLogin = mutableStateOf(client.state.value.authenticating)
    val showAccount = mutableStateOf(false)
    val loginMode = mutableStateOf(0)
    val accountName = mutableStateOf("")
    val pendingLibrary = mutableStateOf<SteamWallpaperLibrary?>(null)
    val preview = mutableStateOf<SteamWallpaperItem?>(null)
    val retryItem = mutableStateOf<SteamWallpaperItem?>(null)
    val nextTransferRoute = mutableStateOf<DownloadRouteMode?>(null)
    val retryPage = mutableStateOf(1)
    val notice = mutableStateOf<String?>(null)

    private var visibleView: Any? = null
    fun attach(view: Any, foreground: Boolean) {
        visibleView = view
        client.setForeground(foreground)
    }
    fun owns(view: Any): Boolean = visibleView === view
    fun foreground(view: Any, foreground: Boolean) {
        if (owns(view)) client.setForeground(foreground)
    }
    fun detach(view: Any) {
        if (owns(view)) {
            visibleView = null
            client.setForeground(false)
        }
    }

    override fun onCleared() { client.close() }
}

private data class SteamBrowserSnapshot(
    val items: List<SteamWallpaperItem>,
    val loading: Boolean,
    val error: String?,
    val query: String,
    val library: SteamWallpaperLibrary,
    val kind: SteamWallpaperKind,
)

/** Existing Ovio/PANEL workspace. Transfer bytes never select a page or replace the grid. */
@Composable
internal fun SteamWallpaperSheet(
    onDismiss: () -> Unit,
    visible: Boolean = true,
    backdrop: HazeState? = null,
) {
    val context = LocalMangaContext.current
    val owner = viewModel(key = "mangaflow-steam-workshop") { SteamWorkshopOwner(context) }
    val client = owner.client
    var opened by remember(context) { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) opened = true }
    if (!opened) return

    val state by client.state.collectAsStateWithLifecycle()
    var retained by remember(client) { mutableStateOf(state) }
    SideEffect { if (visible) retained = state }
    val display = if (visible) state else retained
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val viewLease = remember(client, lifecycle) { Any() }
    val latestVisible by rememberUpdatedState(visible)
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val gridPositions = rememberSaveableStateHolder()
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var query by owner.query
    var requested by owner.requested
    var selectedLibrary by owner.library
    var selectedKind by owner.kind
    var showLogin by owner.showLogin
    var showAccount by owner.showAccount
    var loginMode by owner.loginMode
    var accountName by owner.accountName
    var pendingLibrary by owner.pendingLibrary
    var preview by owner.preview
    var retryItem by owner.retryItem
    var nextTransferRoute by owner.nextTransferRoute
    var retryPage by owner.retryPage
    var notice by owner.notice
    // Passwords and Guard codes are not retained in the owner or in saveable state.
    var password by remember(client) { mutableStateOf("") }
    var guardCode by remember(client) { mutableStateOf("") }
    var passwordVisible by remember(client) { mutableStateOf(false) }

    fun clearFocus() {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
    fun search(value: String, kind: SteamWallpaperKind, page: Int = 1) {
        requested = true
        owner.committedQuery = value.trim()
        selectedLibrary = SteamWallpaperLibrary.SEARCH
        selectedKind = kind
        retryPage = page
        notice = null
        clearFocus()
        client.search(value.trim(), kind, page)
    }
    fun loadLibrary(library: SteamWallpaperLibrary, kind: SteamWallpaperKind, page: Int = 1) {
        if (library == SteamWallpaperLibrary.SEARCH) {
            search(owner.committedQuery, kind, page)
            return
        }
        if (client.state.value.account == null) {
            pendingLibrary = library
            showAccount = false
            showLogin = true
            clearFocus()
            return
        }
        requested = true
        selectedLibrary = library
        selectedKind = kind
        retryPage = page
        notice = null
        preview = null
        showAccount = false
        clearFocus()
        client.loadLibrary(library, kind, page)
    }
    fun openLogin() {
        showAccount = false
        showLogin = true
        password = ""
        guardCode = ""
        passwordVisible = false
        notice = null
        clearFocus()
        // A challenge already owned by the client must not be replaced on re-entry.
        if (client.state.value.authenticating) loginMode = if (client.state.value.qrUrl != null) 1 else 0
    }
    fun cancelAuthentication() {
        pendingLibrary = null
        password = ""
        guardCode = ""
        passwordVisible = false
        notice = null
        clearFocus()
        client.cancelAuthentication()
    }
    fun download(item: SteamWallpaperItem) {
        if (client.state.value.transferId != null) {
            notice = "已有壁纸正在下载，可先浏览或取消当前下载。"
            return
        }
        if (client.state.value.account == null) {
            preview = item
            openLogin()
            return
        }
        retryItem = item
        notice = null
        clearFocus()
        try {
            val route = DownloadRoutingStore.get(context).snapshot(DownloadCategory.STEAM_WALLPAPERS, nextTransferRoute)
            client.download(item, route)
        } catch (failure: DownloadRouteException) { notice = failure.message }
    }
    fun passwordLogin() {
        val current = client.state.value
        if (accountName.isBlank() || password.isEmpty() || current.authenticating ||
            current.preparing || current.restoring) return
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
        password = ""
        guardCode = ""
        passwordVisible = false
        showLogin = false
        showAccount = false
        pendingLibrary = null
        preview = null
        notice = null
        clearFocus()
        if (owner.owns(viewLease)) {
            client.cancelAuthentication()
            client.cancelBrowse()
        }
        onDismiss()
    }
    fun backToBrowse() {
        if (showLogin) {
            cancelAuthentication()
            showLogin = false
        } else if (showAccount) showAccount = false
        else preview = null
        clearFocus()
    }

    DisposableEffect(client, lifecycle) {
        if (visible) owner.attach(viewLease, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    password = ""
                    guardCode = ""
                    passwordVisible = false
                    owner.foreground(viewLease, false)
                }
                Lifecycle.Event.ON_START -> {
                    foreground = true
                    owner.foreground(viewLease, latestVisible)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            password = ""
            guardCode = ""
            owner.detach(viewLease)
            // The ViewModel, not disposal of this view, owns the live challenge.
        }
    }
    LaunchedEffect(client, visible) {
        if (visible) owner.attach(viewLease, foreground)
        else if (owner.owns(viewLease)) {
            password = ""
            guardCode = ""
            passwordVisible = false
            pendingLibrary = null
            showLogin = false
            // A closed panel releases its browsing/authentication work, not the retained transfer.
            client.cancelAuthentication()
            client.cancelBrowse()
            owner.detach(viewLease)
        }
    }
    LaunchedEffect(client, state.importedCount) {
        if (state.importedCount > owner.acknowledgedImports) {
            owner.acknowledgedImports = state.importedCount
            nextTransferRoute = null
            notice = "已加入壁纸库"
        }
    }
    LaunchedEffect(visible, foreground, state.account?.steamId, state.authenticating) {
        if (visible && foreground && state.account != null && !state.authenticating && showLogin) {
            showLogin = false
            password = ""
            guardCode = ""
            val selected = pendingLibrary
            pendingLibrary = null
            // Resume only the personal list explicitly chosen before login.
            if (selected != null) loadLibrary(selected, selectedKind)
        }
    }
    val pane = when {
        !display.supported -> WorkshopPane.UNSUPPORTED
        showLogin || display.authenticating && display.account == null -> WorkshopPane.LOGIN
        showAccount && display.account != null -> WorkshopPane.ACCOUNT
        preview != null -> WorkshopPane.PREVIEW
        else -> WorkshopPane.BROWSE
    }
    val browser = remember(display.items, display.loading, display.browseError, display.query,
        display.library, display.kind) {
        SteamBrowserSnapshot(display.items, display.loading, display.browseError, display.query,
            display.library, display.kind)
    }
    val latestSearch = rememberUpdatedState<(String, SteamWallpaperKind, Int) -> Unit>(::search)
    val latestLibrary = rememberUpdatedState<(SteamWallpaperLibrary, SteamWallpaperKind, Int) -> Unit>(::loadLibrary)
    val onQuery: (String) -> Unit = remember(owner) { { owner.query.value = it.take(120) } }
    val onSearch: () -> Unit = remember(owner, latestSearch) {
        { latestSearch.value(owner.query.value, owner.kind.value, 1) }
    }
    val onLibrary: (SteamWallpaperLibrary) -> Unit = remember(owner, latestLibrary) {
        { next -> if (next != owner.library.value) latestLibrary.value(next, owner.kind.value, 1) }
    }
    val onKind: (SteamWallpaperKind) -> Unit = remember(owner, latestLibrary) {
        { next ->
            if (next != owner.kind.value) {
                owner.kind.value = next
                if (owner.requested.value) latestLibrary.value(owner.library.value, next, 1)
            }
        }
    }
    val onPreview: (SteamWallpaperItem) -> Unit = remember(owner, focus, keyboard) {
        { item ->
            if (owner.preview.value?.id != item.id || owner.preview.value?.kind != item.kind)
                owner.nextTransferRoute.value = null
            owner.preview.value = item
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }

    MotionDialog(visible = visible, onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false), maximumWidth = 864.dp) {
        BoxWithConstraints(Modifier.padding(horizontal = 12.dp).imePadding()) {
            val panelHeight = minOf(maxHeight, LocalMangaWindow.current.height * .90f, 720.dp)
            val footerHeight = minOf(132.dp * LocalMangaWindow.current.fontScale, panelHeight * .30f)
            val panel: @Composable () -> Unit = {
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
                            if (panelHeight >= 340.dp) Text("Wallpaper Engine",
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
                        }
                        if (pane == WorkshopPane.BROWSE || pane == WorkshopPane.PREVIEW) {
                            MotionTextButton(onClick = {
                                if (display.account == null && display.canResumeSession) client.login()
                                else if (display.account == null) openLogin()
                                else { showAccount = true; clearFocus() }
                            }, enabled = display.supported && !display.restoring) {
                                val account = display.account
                                if (account != null) {
                                    SteamAccountAvatar(account.avatarUrl, Modifier.size(26.dp))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (account == null) {
                                    if (display.restoring) "恢复中" else if (display.canResumeSession) "恢复登录" else "登录"
                                } else account.personaName?.takeIf { it.isNotBlank() } ?: "账号",
                                    modifier = Modifier.widthIn(max = 112.dp), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                        MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Close, if (display.transferId != null)
                                "收起创意工坊，当前下载继续" else "关闭创意工坊", Modifier.size(20.dp))
                        }
                    }
                    if (pane == WorkshopPane.BROWSE || pane == WorkshopPane.ACCOUNT) Row(
                        Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Steam 连接线路", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                        DownloadRouteControl(DownloadCategory.STEAM_WALLPAPERS,
                            enabled = display.supported && !display.restoring && !display.preparing &&
                                !display.authenticating && !display.loading && !display.profileLoading && display.transferId == null)
                    }
                    if (display.restoring && pane == WorkshopPane.BROWSE) Row(Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically) {
                        SteamCompactWaiting("正在恢复登录，仍可搜索", Modifier.weight(1f))
                        MotionIconButton(onClick = client::cancelAuthentication, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Outlined.Close, "停止恢复登录", Modifier.size(17.dp))
                        }
                    }
                    MotionTabContent(selected = pane, contentKey = { it }, order = { it.ordinal },
                        modifier = Modifier.weight(1f)) { page ->
                        when (page) {
                            WorkshopPane.UNSUPPORTED -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.Center) {
                                Text(display.message ?: "此平台暂不支持 Steam 创意工坊。",
                                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                            WorkshopPane.LOGIN -> Column(Modifier.fillMaxSize()) {
                                MotionTabs(selected = loginMode, labels = listOf("账号登录", "扫码登录"),
                                    onSelect = { index ->
                                        if (index != loginMode) {
                                            cancelAuthentication()
                                            showLogin = true
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
                                    if (loginMode == 1) SteamQrLogin(display, onOpen = { url ->
                                        if (!openUrl(context, url)) notice = "未能打开 Steam，请用另一台设备扫码。"
                                    }, onRetry = { notice = null; client.login() })
                                    else if (display.authenticating && display.guardMethods.isNotEmpty()) {
                                        SteamGuardLogin(display, guardCode, onCode = { guardCode = it },
                                            onSubmit = { method ->
                                                val entered = guardCode
                                                guardCode = ""
                                                notice = null
                                                clearFocus()
                                                client.submitGuardCode(entered, method)
                                            })
                                    } else if (display.authenticating) SteamWallpaperWaiting("正在建立验证会话…")
                                    else {
                                        if (display.canResumeSession) Row(Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text("可恢复已保存的 Steam 登录", Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                            MotionTextButton(onClick = client::login,
                                                enabled = !display.preparing && !display.restoring) { Text("恢复登录") }
                                        }
                                        SteamLoginField(accountName, { accountName = it }, "Steam 账号名称",
                                            enabled = !display.preparing && !display.restoring)
                                        SteamLoginField(password, { password = it }, "密码",
                                            password = !passwordVisible, secret = true,
                                            enabled = !display.preparing && !display.restoring,
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
                                                enabled = accountName.isNotBlank() && password.isNotEmpty() &&
                                                    !display.preparing && !display.restoring) { Text("登录 Steam") }
                                        }
                                        Text("验证方式由 Steam 返回，密码仅用于本次登录。",
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                            WorkshopPane.ACCOUNT -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                val account = display.account
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    SteamAccountAvatar(account?.avatarUrl, Modifier.size(48.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        account?.personaName?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, style = MaterialTheme.typography.titleMedium)
                                        }
                                        Text(account?.accountName.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                                        Text(account?.steamId.orEmpty(), style = MaterialTheme.typography.labelSmall,
                                            color = colors.onSurfaceVariant)
                                    }
                                }
                                if (display.profileLoading) SteamCompactWaiting("正在获取账号资料…")
                                display.profileError?.let { error ->
                                    Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                                    MotionTextButton(onClick = client::refreshProfile, enabled = !display.profileLoading) {
                                        Text("重试账号资料")
                                    }
                                }
                                MotionTextButton(onClick = {
                                    cancelAuthentication()
                                    showAccount = false
                                    showLogin = false
                                    retryItem = null
                                    selectedLibrary = SteamWallpaperLibrary.SEARCH
                                    requested = false
                                    preview = null
                                    client.logout()
                                }) {
                                    Icon(Icons.Outlined.Logout, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("退出登录")
                                }
                            }
                            WorkshopPane.BROWSE -> {
                                val dataset = display.library.name + ":" + display.kind.name + ":" + display.query
                                gridPositions.SaveableStateProvider(dataset) {
                                    val grid = rememberLazyGridState()
                                    SteamWallpaperBrowser(browser, grid, query, onQuery, requested,
                                        selectedLibrary, selectedKind, onSearch, onLibrary, onKind, onPreview)
                                }
                            }
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
                    // Reserve the same task viewport before and after live status arrives.
                    Column(Modifier.fillMaxWidth().height(footerHeight).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (display.transferId != null || display.transferError != null) {
                            SteamTransferTask(display, onCancel = client::cancelTransfer,
                                onRetry = {
                                    retryItem?.let { item ->
                                        client.cancelTransfer()
                                        download(item)
                                    }
                                }, canRetry = retryItem != null)
                        }
                        val problem = notice ?: when (pane) {
                            WorkshopPane.BROWSE -> display.browseError ?: display.authError
                            WorkshopPane.LOGIN -> display.authError.takeUnless {
                                display.authenticating && display.guardMethods.isNotEmpty()
                            }
                            else -> null
                        }
                        if (problem != null) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(problem, style = MaterialTheme.typography.labelSmall,
                                color = if (notice != null) colors.onSurfaceVariant else colors.error,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                            if (notice != null) MotionIconButton(onClick = { notice = null }, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Outlined.Close, "收起提示", Modifier.size(17.dp))
                            } else if (pane == WorkshopPane.BROWSE) MotionTextButton(onClick = {
                                notice = null
                                if (display.canResumeSession) client.login()
                                else loadLibrary(display.library, display.kind, retryPage)
                            }) { Text(if (display.canResumeSession) "恢复登录" else "重试") }
                        }
                        if (pane == WorkshopPane.BROWSE && display.canResumeSession && !display.restoring) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (problem == null) display.message ?: "Steam 登录信息已保留，可恢复连接。"
                                    else "已保存的登录信息未删除。", Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                MotionTextButton(onClick = ::openLogin) { Text("账号登录") }
                            }
                        }
                        // The legacy general error is reserved for shared component/setup failures.
                        display.error?.takeUnless { error ->
                            error == display.authError || error == display.browseError ||
                                error == display.guardError || error == display.transferError ||
                                error == display.profileError
                        }?.let { error ->
                            Text(error, style = MaterialTheme.typography.labelSmall, color = colors.error,
                                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        if (display.preparing && display.transferId == null) {
                            SteamWallpaperProgress(display.componentBytes, display.componentTotalBytes,
                                if (display.componentBytes <= 0L) "正在准备 Steam 组件…" else null,
                                known = display.componentBytes > 0L)
                        }
                        if (display.loading && pane == WorkshopPane.BROWSE) Row(Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically) {
                            SteamCompactWaiting("正在载入壁纸…", Modifier.weight(1f))
                            MotionIconButton(onClick = client::cancelBrowse, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Outlined.Close, "取消本次查找", Modifier.size(18.dp))
                            }
                        }
                        if (pane == WorkshopPane.BROWSE && display.hasMore && !display.loading) {
                            MotionTextButton(onClick = {
                                loadLibrary(display.library, display.kind, display.page + 1)
                            }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                        }
                        if (pane == WorkshopPane.LOGIN && display.authenticating) Row(Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("验证会话保持中，可切到邮箱后返回", Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            MotionTextButton(onClick = ::cancelAuthentication) { Text("取消验证") }
                        }
                        if (pane == WorkshopPane.PREVIEW && preview != null) {
                            val item = preview
                            TaskDownloadRouteControl(DownloadCategory.STEAM_WALLPAPERS,
                                selected = nextTransferRoute, onSelect = { nextTransferRoute = it },
                                enabled = display.transferId == null && !display.preparing,
                                backdrop = backdrop)
                            MotionTextButton(onClick = { item?.let(::download) },
                                enabled = display.transferId == null && !display.preparing,
                                modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(if (display.transferId != null) "已有下载进行中"
                                    else if (display.account == null) "登录后下载" else "下载并应用")
                            }
                        }
                    }
                }
            }
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
    val problem = state.guardError ?: state.authError
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
            SteamGuardCodeField(code, onCode, enabled = !state.guardSubmitting,
                label = if (method == SteamGuardMethod.EMAIL_CODE) "Steam 邮箱验证码" else "Steam Guard 验证码",
                onDone = { if (code.length == 5 && !state.guardSubmitting) onSubmit(method) })
            if (problem == null && state.message?.startsWith("验证已提交") == true)
                Text(state.message.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MotionTextButton(onClick = { onSubmit(method) }, enabled = code.length == 5 && !state.guardSubmitting) {
                    Text(if (state.guardSubmitting) "正在验证…" else "验证")
                }
            }
        }
        SteamGuardMethod.MOBILE_CONFIRMATION -> SteamWallpaperWaiting("请在 Steam 手机应用中确认登录")
        SteamGuardMethod.EMAIL_CONFIRMATION -> SteamWallpaperWaiting("请打开 Steam 邮件中的确认链接")
    }
    problem?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** One real editable field owns paste, selection, backspace and platform autofill for all five cells. */
@Composable
private fun SteamGuardCodeField(
    code: String,
    onCode: (String) -> Unit,
    enabled: Boolean,
    label: String,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(code.length) }
    val input = TextFieldValue(code, TextRange(cursor.coerceIn(0, code.length)))
    LaunchedEffect(enabled) { if (enabled) requester.requestFocus() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        BasicTextField(
            value = input,
            onValueChange = { incoming ->
                fun Char.isCodeCharacter() = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
                val filtered = incoming.text.filter { it.isCodeCharacter() }.take(5).uppercase()
                cursor = incoming.text.take(incoming.selection.end).count { it.isCodeCharacter() }
                    .coerceAtMost(filtered.length)
                onCode(filtered)
            },
            enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label; contentType = ContentType.SmsOtpCode },
            textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onDone() }, onDone = { onDone() }),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(5) { index ->
                            val current = focused && input.selection.end.coerceAtMost(4) == index
                            Box(Modifier.weight(1f).heightIn(min = 52.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(colors.onSurface.copy(alpha = if (current) .095f else .045f))
                                .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center) {
                                Text(code.getOrNull(index)?.toString() ?: " ",
                                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                                    color = colors.onSurface.copy(alpha = if (enabled) 1f else .5f))
                            }
                        }
                    }
                    // Draw only the cells while retaining Foundation's full editable input/selection node.
                    Box(Modifier.matchParentSize().drawWithContent { }) { inner() }
                }
            },
        )
    }
}

@Composable
private fun SteamWallpaperBrowser(
    state: SteamBrowserSnapshot,
    grid: LazyGridState,
    query: String,
    onQuery: (String) -> Unit,
    requested: Boolean,
    library: SteamWallpaperLibrary,
    kind: SteamWallpaperKind,
    onSearch: () -> Unit,
    onLibrary: (SteamWallpaperLibrary) -> Unit,
    onKind: (SteamWallpaperKind) -> Unit,
    onPreview: (SteamWallpaperItem) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tileWidth = (if (LocalMangaWindow.current.width < 420.dp) 124.dp else 150.dp) *
        LocalMangaWindow.current.fontScale
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MotionTabs(selected = library.ordinal, labels = listOf("浏览", "收藏", "订阅"),
                onSelect = { SteamWallpaperLibrary.entries.getOrNull(it)?.let(onLibrary) },
                modifier = Modifier.weight(1f), treatment = MotionTabTreatment.Soft,
                fullWidthIndicator = true, controlHeight = 40.dp,
                track = colors.onSurface.copy(alpha = .045f), indicator = colors.surface)
            MotionIconButton(onClick = {
                onKind(if (kind == SteamWallpaperKind.IMAGE) SteamWallpaperKind.VIDEO else SteamWallpaperKind.IMAGE)
            }, modifier = Modifier.size(44.dp),
                tooltip = if (kind == SteamWallpaperKind.IMAGE) "当前图片，切换为视频" else "当前视频，切换为图片") {
                Icon(if (kind == SteamWallpaperKind.IMAGE) Icons.Outlined.Image else Icons.Outlined.PlayArrow,
                    if (kind == SteamWallpaperKind.IMAGE) "当前图片，切换为视频" else "当前视频，切换为图片",
                    Modifier.size(21.dp))
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
            .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(query, onValueChange = onQuery, singleLine = true,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                    .semantics { contentDescription = "搜索创意工坊壁纸" },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { input -> Box {
                    if (query.isEmpty()) Text("搜索创意工坊", style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant)
                    input()
                } })
            if (query.isNotEmpty()) MotionIconButton(onClick = { onQuery("") },
                modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, "清空搜索词", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = onSearch, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Search, "搜索创意工坊", Modifier.size(20.dp))
            }
        }
        if (state.items.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(when {
                state.loading -> "正在载入壁纸…"
                state.error != null && requested -> "暂时无法获取壁纸"
                !requested -> "输入关键词，或浏览创意工坊"
                library == SteamWallpaperLibrary.FAVORITES -> "当前没有可直接导入的收藏"
                library == SteamWallpaperLibrary.SUBSCRIPTIONS -> "当前没有可直接导入的订阅"
                kind == SteamWallpaperKind.IMAGE -> "没有找到可直接导入的图片"
                else -> "没有找到可直接导入的视频"
            }, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Text("原图片 · 动态视频，场景工程暂不支持。",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (!requested && !state.loading) MotionTextButton(onClick = onSearch) { Text("浏览壁纸") }
        } else LazyVerticalGrid(columns = GridCells.Adaptive(tileWidth), state = grid,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.items, key = { it.id }) { item ->
                val interaction = remember(item.id) { MutableInteractionSource() }
                Column(Modifier.fillMaxWidth()
                    .clickable(interactionSource = interaction, indication = null,
                        role = Role.Button, onClickLabel = "预览 " + item.title) { onPreview(item) }
                    .motionPress(interaction),
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

@Composable
private fun SteamAccountAvatar(url: String?, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .045f)),
        contentAlignment = Alignment.Center) {
        if (!url.isNullOrBlank() && !failed) AsyncImage(model = url, contentDescription = "Steam 头像",
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { failed = true })
        else Icon(Icons.Outlined.PersonOutline, null, Modifier.fillMaxSize().padding(4.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SteamCompactWaiting(message: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        MotionBusyIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
        Text(message, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SteamTransferTask(
    state: SteamWallpaperState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    canRetry: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    var idleSeconds by remember(state.transferId, state.transferLastActivity) { mutableIntStateOf(0) }
    LaunchedEffect(state.transferId, state.transferLastActivity) {
        if (state.transferId != null) while (true) {
            idleSeconds = state.transferLastActivity?.elapsedNow()?.inWholeSeconds
                ?.coerceAtLeast(0L)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
            delay(1_000L)
        }
    }
    val waitingForData = state.transferPhase == SteamWallpaperTransferPhase.DOWNLOADING &&
        (state.receivedBytes == 0L || idleSeconds >= 8)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.transferName ?: "壁纸下载", style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            state.transferRoute?.let { route ->
                Text("线路 · " + downloadRouteLabel(route.mode),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.transferError != null) Text(state.transferError.orEmpty(), style = MaterialTheme.typography.labelSmall,
                color = colors.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
            else SteamWallpaperProgress(state.receivedBytes, state.totalBytes, when (state.transferPhase) {
                SteamWallpaperTransferPhase.WAITING -> "等待 Steam 连接…"
                SteamWallpaperTransferPhase.CONNECTING -> "正在连接 Steam…"
                SteamWallpaperTransferPhase.RESOLVING -> "正在获取下载信息…"
                SteamWallpaperTransferPhase.DOWNLOADING ->
                    if (waitingForData) (if (state.receivedBytes == 0L) "等待首批数据" else "等待后续数据") +
                        " · ${idleSeconds}秒" else null
                SteamWallpaperTransferPhase.VERIFYING -> "正在校验原文件…"
                SteamWallpaperTransferPhase.IMPORTING -> "正在加入壁纸库…"
                SteamWallpaperTransferPhase.NONE -> "正在准备下载…"
            }, known = state.transferProgressKnown &&
                state.transferPhase == SteamWallpaperTransferPhase.DOWNLOADING)
        }
        if (state.transferId != null) {
            if (canRetry && waitingForData && idleSeconds >= 8) MotionTextButton(onClick = onRetry) { Text("重试") }
            MotionIconButton(onClick = onCancel, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, "取消当前壁纸下载", Modifier.size(18.dp))
            }
        } else if (canRetry) MotionTextButton(onClick = onRetry) { Text("重试") }
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
private fun SteamWallpaperProgress(received: Long, total: Long, stage: String?, known: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val determinate = known && total > 0L
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (determinate) LinearProgressIndicator(
            progress = { (received.coerceAtLeast(0L).toDouble() / total).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.onSurface, trackColor = colors.onSurface.copy(alpha = .08f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!determinate) MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            val bytes = if (determinate)
                steamWallpaperByteLabel(received) + " / " + steamWallpaperByteLabel(total)
                else if (known && received > 0L) "已接收 " + steamWallpaperByteLabel(received) else null
            Text(listOfNotNull(stage, bytes).joinToString(" · ").ifBlank { "正在获取下载信息…" },
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun steamWallpaperByteLabel(value: Long): String = when {
    value < 1024L -> value.coerceAtLeast(0L).toString() + " B"
    value < 1024L * 1024L -> (value / 1024L).toString() + " KB"
    else -> (value / (1024L * 1024L)).toString() + " MB"
}
