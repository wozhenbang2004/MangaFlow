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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.platform.*
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.storage.ChapterProgress
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.MotionButton as Button
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import dev.mangaflow.ui.motion.MotionIconButton as IconButton
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import dev.mangaflow.ui.platform.*

@Composable
fun AccountScreen(
    state: AccountUiState,
    repository: MangaRepository,
    onLoginWithPassword: (String, String) -> Unit,
    onLoginWithCookie: (String) -> Unit,
    active: Boolean,
    onLogout: () -> Unit,
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
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val profile = state.profile
    val bottomInset = screenFitContentInsets().asPaddingValues().calculateBottomPadding()
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var cookieMode by rememberSaveable { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var cookie by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val clearSecrets = {
        password = ""
        cookie = ""
        showSecret = false
    }
    val clearInputs = {
        clearSecrets()
        keyboard?.hide()
        focus.clearFocus()
    }
    LaunchedEffect(state.busy) { if (!state.busy) submitting = false }
    LaunchedEffect(active) { if (!active) clearInputs() }
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
            submitting = true
            clearInputs()
            onLoginWithPassword(submittedUsername, submittedPassword)
        }
    }
    val submitCookie = {
        if (active && !state.busy && !submitting && cookie.isNotBlank()) {
            val credential = cookie.trim()
            submitting = true
            clearInputs()
            onLoginWithCookie(credential)
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
    Box(Modifier.fillMaxSize()) {
    if (profile == null) {
        BoxWithConstraints(Modifier.fillMaxSize().mangaHazeSource(backdrop).imePadding()) {
        val accountHeight = maxHeight
        val loginScroll = rememberScrollState()
        Column(
            modifier = Modifier.widthIn(max = if (desktop) 480.dp else Dp.Infinity).fillMaxWidth()
                .align(Alignment.TopCenter).heightIn(max = accountHeight)
                .verticalScroll(loginScroll)
                .padding(start = 24.dp, end = 24.dp, top = contentTopPadding + 4.dp, bottom = 16.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("匿名阅读已启用", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("连接账号，导入拷贝收藏；阅读进度可在漫画详情中选择采用。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (cookieMode) {
                OutlinedTextField(
                    value = cookie, onValueChange = { cookie = it },
                    modifier = Modifier.fillMaxWidth(), enabled = active && !busy, singleLine = true,
                    label = { Text("Cookie 或 Token") },
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { SecretVisibility(showSecret, active && !busy) { showSecret = !showSecret } },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitCookie() }),
                    shape = RoundedCornerShape(18.dp), colors = accountInputColors(),
                )
                Button(
                    onClick = submitCookie, enabled = active && !busy && cookie.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary,
                        disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.onSurfaceVariant),
                ) { Text("导入登录状态") }
            } else {
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(), enabled = active && !busy, singleLine = true,
                    label = { Text("账号") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    shape = RoundedCornerShape(18.dp), colors = accountInputColors(),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(), enabled = active && !busy, singleLine = true,
                    label = { Text("密码") },
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { SecretVisibility(showSecret, active && !busy) { showSecret = !showSecret } },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submitPassword() }),
                    shape = RoundedCornerShape(18.dp), colors = accountInputColors(),
                )
                Button(
                    onClick = submitPassword,
                    enabled = active && !busy && username.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary,
                        disabledContainerColor = colors.surfaceVariant, disabledContentColor = colors.onSurfaceVariant),
                ) { Text("登录") }
            }
            if (state.busy) AccountBusyRow("正在验证账号…") { clearInputs(); onLogout() }
            state.error?.let { message ->
                Box(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    AccountError(message, state.busy, onRetry = if (state.canRetryConnection) onRefresh else null)
                }
            }
            TextButton(
                onClick = { clearInputs(); cookieMode = !cookieMode }, enabled = active && !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) { Text(if (cookieMode) "使用账号密码" else "导入 Cookie / Token", style = MaterialTheme.typography.bodySmall) }
        }
        if (desktop) Box(Modifier.widthIn(max = 480.dp).fillMaxSize().align(Alignment.TopCenter)) {
            DesktopVerticalScrollbar(loginScroll,
                Modifier.align(Alignment.CenterEnd).padding(top = contentTopPadding, bottom = bottomInset))
        }
        }
    } else {
        key(profile.username) {
            CloudAccountLibrary(
                state = state, repository = repository, backdrop = backdrop, active = active,
                localFavorites = localFavorites, localHistory = localHistory,
                contentTopPadding = contentTopPadding,
                onOpen = onOpen, onAddToLibrary = onAddToLibrary,
                onMoreFavorites = onMoreFavorites, onRefreshFavorites = onRefreshFavorites,
                onRefreshAccount = onRefresh, favoriteAddedAt = favoriteAddedAt, onOpenSync = onOpenSync,
            )
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
        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = if (visible) "隐藏输入" else "显示输入", modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun accountInputColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
    unfocusedBorderColor = Color.Transparent,
    disabledBorderColor = Color.Transparent,
)

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
            }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) { Text(if (copied) "已复制" else "复制错误") }
            if (onRetry != null) TextButton(onClick = onRetry, enabled = !busy,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) { Text("重试") }
        }
    }
}

