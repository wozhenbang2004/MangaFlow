package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.AccountUiState
import dev.mangaflow.account.CloudHistoryEntry
import dev.mangaflow.account.CopyLoginRoute
import dev.mangaflow.account.CopyShelfMetadata
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.platform.*
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.ChapterProgress
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.motionInputGate
import dev.mangaflow.ui.motion.MotionButton as Button
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import dev.mangaflow.ui.motion.MotionIconButton as IconButton
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import dev.mangaflow.ui.platform.*

@Composable
fun AccountScreen(
    state: AccountUiState,
    repository: MangaRepository,
    onLoginWithPassword: (String, String, CopyLoginRoute) -> Unit,
    active: Boolean,
    onLogout: () -> Unit,
    onCancelLogin: () -> Unit,
    onRefresh: () -> Unit,
    onMoreFavorites: () -> Unit,
    onMoreHistory: () -> Unit,
    onOpen: (Manga) -> Unit,
    onContinue: (CloudHistoryEntry) -> Unit,
    onSetFavorite: (Manga, Boolean) -> Unit,
    onRetrySync: () -> Unit,
    backdrop: HazeState,
    navigation: @Composable () -> Unit,
    localFavorites: List<Manga>,
    localHistory: List<ReadingProgress>,
    chapterHistoryRevision: Long,
    readChapterProgress: suspend (String, String) -> ChapterProgress?,
    onAddToLibrary: (Manga) -> Unit,
    onContinueLocal: (ReadingProgress) -> Unit,
    onRefreshFavorites: () -> Unit,
    onRefreshHistory: () -> Unit,
    onSearch: (() -> Unit)? = null,
    favoriteAddedAt: (String) -> Long? = { null },
    onOpenSync: (() -> Unit)? = null,
    cloudMetadata: Map<String, CopyShelfMetadata> = emptyMap(),
    metadataLoading: Boolean = false,
    metadataError: String? = null,
    onRetryMetadata: (() -> Unit)? = null,
    onGoShelf: () -> Unit = {},
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val profile = state.profile
    val bottomInset = screenFitContentInsets().asPaddingValues().calculateBottomPadding()
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var loginRoute by remember { mutableStateOf(CopyLoginRoute.COPY_SETTINGS) }
    val clearSecrets = {
        password = ""
        showSecret = false
    }
    val clearInputs = {
        clearSecrets()
        keyboard?.hide()
        focus.clearFocus()
    }
    LaunchedEffect(state.busy) { if (!state.busy) submitting = false }
    LaunchedEffect(active, profile?.username) {
        if (!active || profile != null) {
            loginRoute = CopyLoginRoute.COPY_SETTINGS
            clearInputs()
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) clearSecrets()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); clearSecrets() }
    }
    val busy = state.busy || submitting
    val submitPassword = {
        if (active && !state.busy && !submitting && username.isNotBlank() && password.isNotEmpty()) {
            val submittedUsername = username.trim()
            val submittedPassword = password
            val submittedRoute = loginRoute
            submitting = true
            clearInputs()
            onLoginWithPassword(submittedUsername, submittedPassword, submittedRoute)
        }
    }

    val contentTopPadding = HeaderContentGap
    // Measure the actual, font-aware header and collection in one pass, without a one-frame inset jump.
    Layout(modifier = Modifier.fillMaxSize().background(colors.background.copy(alpha = .98f)), content = {
    CloudAccountHeader(
        state = state, backdrop = backdrop, navigation = navigation, active = active,
        refreshing = state.favoritesLoading,
        refreshError = state.favoritesError,
        onSearch = onSearch,
        onRefresh = if (profile == null) onRefresh else onRefreshFavorites,
        onRetrySync = onRetrySync,
        onLogout = { clearInputs(); onLogout() },
        modifier = Modifier.windowInsetsPadding(screenFitContentInsets().only(WindowInsetsSides.Top)),
        onOpenSync = onOpenSync,
    )
    CopyShelfArrival(profile?.username, active) {
    if (profile == null) {
        AccountWorkspace(Modifier.fillMaxSize().mangaHazeSource(backdrop).imePadding(), mobileWidth = Dp.Infinity,
            top = contentTopPadding + 18.dp, bottom = 16.dp + bottomInset, sideInset = 24.dp,
            overview = { wide ->
                AccountIdentity("匿名阅读已启用", "连接账号可导入拷贝收藏。阅读进度可在漫画详情中选择采用。", wide)
            }) {
            if (state.savedCredentialPresent) {
                Text(if (state.autoRestorePending)
                    "账号凭据已安全保留，客户端会自动重试恢复连接，无需重复登录。"
                    else "旧凭据仍安全保留，请用密码登录确认身份；不会自动验证或同步。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp))
                if (state.autoRestorePending) Button(onClick = onRefresh, enabled = active && !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(16.dp)) { Text("立即重试（可选）") }
                TextButton(onClick = { clearInputs(); onLogout() }, enabled = active && !busy) {
                    Text("退出并切换账号", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!state.savedCredentialPresent || !state.autoRestorePending) Column(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val formActive = active
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    OutlinedTextField(
                        value = username, onValueChange = { if (formActive) username = it },
                        modifier = Modifier.fillMaxWidth(), enabled = formActive && !busy, singleLine = true,
                        label = { Text("账号") },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 5.dp, bottomEnd = 5.dp),
                        colors = settingsInputColors(),
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { if (formActive) password = it },
                        modifier = Modifier.fillMaxWidth(), enabled = formActive && !busy, singleLine = true,
                        label = { Text("密码") },
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { SecretVisibility(showSecret, formActive && !busy) { showSecret = !showSecret } },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                        shape = RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                        colors = settingsInputColors(),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("登录线路", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    MotionTabs(loginRoute.ordinal, CopyLoginRoute.entries.map { it.label },
                        onSelect = { if (formActive && !busy) loginRoute = CopyLoginRoute.entries[it] },
                        modifier = Modifier.motionInputGate(formActive && !busy),
                        track = colors.surfaceVariant, indicator = colors.surface,
                        controlHeight = 44.dp, treatment = MotionTabTreatment.Soft,
                        fullWidthIndicator = true, role = Role.RadioButton)
                    if (loginRoute == CopyLoginRoute.SYSTEM) Text("仅用于密码提交。设备的系统 VPN 仍可能接管。",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Button(
                    onClick = submitPassword,
                    enabled = formActive && !busy && username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary,
                        disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.onSurfaceVariant),
                ) { Text("登录") }
            }
            CopyShelfReveal(state.busy) {
                Box(Modifier.padding(bottom = 12.dp)) {
                    AccountBusyRow("正在登录账号…") { if (active && state.busy) { clearInputs(); onCancelLogin() } }
                }
            }
            CopyShelfValue(state.error) { message ->
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp).then(if (desktop) Modifier.accountReviewRow()
                    else Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))) {
                    AccountError(message, state.busy, onRetry = if (state.canRetryConnection && !state.savedCredentialPresent) onRefresh else null)
                }
            }

        }
    } else {
        key(profile.username) {
            AccountWorkspace(Modifier.fillMaxSize().mangaHazeSource(backdrop), mobileWidth = 480.dp,
                top = contentTopPadding + 18.dp, bottom = 16.dp + bottomInset, sideInset = 24.dp,
                overview = { wide ->
                    AccountIdentity(if (profile.locallyRestored) "本地会话已恢复" else "拷贝已连接",
                        if (profile.locallyRestored) "尚未联网确认有效性，收藏和历史请求会显示各自实际结果。"
                        else "本机与拷贝收藏已汇入同一个书架。云端独有作品可直接查看，删除、同步与阅读进度仍分别管理。", wide)
                }) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onGoShelf, enabled = active,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp)) { Text("打开统一书架") }
                    if (onOpenSync != null) TextButton(onClick = onOpenSync, enabled = active,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                        Text("查看拷贝同步差异")
                    }
                    if (state.favoritesError != null) TextButton(onClick = onRefreshFavorites,
                        enabled = active && !state.favoritesLoading) {
                        Text("拷贝收藏读取失败 · 重试", color = colors.error)
                    }
                }
            }
        }
    }
    }
    }) { measurables, constraints ->
        val header = measurables[0].measure(constraints.copy(minHeight = 0))
        val contentHeight = (constraints.maxHeight - header.height).coerceAtLeast(0)
        val content = measurables[1].measure(constraints.copy(minHeight = contentHeight, maxHeight = contentHeight))
        layout(constraints.maxWidth, constraints.maxHeight) {
            content.placeRelative(0, header.height)
            header.placeRelative(0, 0)
        }
    }

}

@Composable
private fun SecretVisibility(visible: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        MotionSwap(visible, Modifier.size(20.dp)) { shown ->
            Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (shown) "隐藏输入" else "显示输入", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AccountIdentity(title: String, detail: String, wide: Boolean) {
    if (wide) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChapterSourceLogo("copy", "拷贝", Modifier.size(44.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        ChapterSourceLogo("copy", "拷贝", Modifier.size(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PreferenceHeading(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccountBusyRow(message: String, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 1.6.dp)
        Text(message, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onCancel) { Text("取消") }
    }
}

@Composable
internal fun AccountError(message: String, busy: Boolean, onRetry: (() -> Unit)? = null) {
    val clipboard = LocalClipboardManager.current
    val versionName = platformVersion(LocalContext.current)
    var copied by remember(message) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString("MangaFlow ${versionName}\n$message"))
                copied = true
            }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                MotionSwap(copied, scaleContent = false) { done -> Text(if (done) "已复制" else "复制错误") }
            }
            if (onRetry != null) TextButton(onClick = onRetry, enabled = !busy,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) { Text("重试") }
        }
    }
}

