package dev.mangaflow.ui

import androidx.compose.ui.unit.Dp

import androidx.compose.foundation.layout.asPaddingValues

import dev.mangaflow.platform.LocalMangaContext as LocalContext

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import dev.mangaflow.ui.motion.MotionButton as Button
import androidx.compose.material3.ButtonDefaults
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import dev.mangaflow.ui.motion.MotionIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mangaflow.AccountUiState
import dev.mangaflow.account.CloudHistoryEntry
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.ui.motion.MotionTabContent

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
    contentTopPadding: Dp = 0.dp,
) {
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
    var showHistory by remember(profile?.username) { mutableStateOf(false) }
    var removeFavorite by remember(profile?.username) { mutableStateOf<Manga?>(null) }
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

    if (profile == null) {
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val accountHeight = maxHeight
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = accountHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = contentTopPadding + 4.dp, bottom = 16.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("匿名阅读已启用", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text("连接账号，同步个人收藏与阅读记录。", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
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
        }
    } else {
        key(profile.username) {
            val favoritesPosition = rememberLazyGridState()
            val historyPosition = rememberLazyGridState()
            BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
                // Long account errors remain reachable without consuming the tabs or list viewport.
                val profileMaxHeight = (maxHeight - 48.dp).coerceAtLeast(0.dp) * .45f
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = profileMaxHeight)
                        .verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = contentTopPadding + 4.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(42.dp).clip(CircleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.PersonOutline, null, Modifier.size(22.dp), tint = colors.onSurfaceVariant)
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(profile.nickname.ifBlank { profile.username }, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (profile.nickname.isNotBlank() && profile.nickname != profile.username) {
                                        Text(profile.username, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                IconButton(onClick = onRefresh, enabled = !state.busy) {
                                    Icon(Icons.Outlined.Refresh, "刷新账号", Modifier.size(21.dp))
                                }
                                TextButton(onClick = { clearInputs(); removeFavorite = null; onLogout() }) { Text("退出") }
                            }
                            if (state.busy) AccountBusyRow("正在刷新账号…") { clearInputs(); onLogout() }
                            state.error?.let { AccountError(it, state.busy, onRefresh) }
                            state.syncMessage?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            if (state.canRetrySync && !state.busy) {
                                TextButton(onClick = onRetrySync, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                                    Text("重试同步", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    BrowseTabs(
                        selected = if (showHistory) 1 else 0,
                        labels = listOf("云端收藏", "云端记录"),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    ) { showHistory = it == 1 }
                    MotionTabContent(if (showHistory) 1 else 0, Modifier.weight(1f).fillMaxWidth()) { tab ->
                        val historyTab = tab == 1
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(144.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                            state = if (historyTab) historyPosition else favoritesPosition,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp + bottomInset),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            if (historyTab) {
                                if (state.history.isEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "history-status") {
                                        when {
                                            state.historyLoading -> QuietLoading("正在读取云端记录…")
                                            state.historyError != null -> AccountError(state.historyError, false, onRefresh)
                                            else -> EmptyMessage("还没有云端记录", "账号中的阅读章节会显示在这里。", "刷新", onRefresh)
                                        }
                                    }
                                } else {
                                    itemsIndexed(state.history, key = { index, entry -> "cloud-history-${entry.manga.id}-${entry.chapterId}-$index" },
                                        span = { _, _ -> GridItemSpan(maxLineSpan) }) { _, entry ->
                                        CloudContinueRow(entry, repository) { onContinue(entry) }
                                    }
                                    if (state.historyLoading || state.historyError != null || state.historyHasMore) {
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "history-more") {
                                            AccountListFooter(state.historyLoading, state.historyError, onMoreHistory)
                                        }
                                    }
                                }
                            } else {
                                if (state.favorites.isEmpty()) {
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "favorites-status") {
                                        when {
                                            state.favoritesLoading -> QuietLoading("正在读取云端收藏…")
                                            state.favoritesError != null -> AccountError(state.favoritesError, false, onRefresh)
                                            else -> EmptyMessage("云端书架还是空的", "账号收藏的漫画会显示在这里。", "刷新", onRefresh)
                                        }
                                    }
                                } else {
                                    items(state.favorites.distinctBy(Manga::id), key = { "cloud-favorite-${it.id}" }) { manga ->
                                        val syncing = manga.id in state.syncingFavorites
                                        Column {
                                            CoverTile(manga, repository) { onOpen(manga) }
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                                TextButton(onClick = { removeFavorite = manga }, enabled = !syncing,
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                                                    if (syncing) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.4.dp, color = colors.onSurfaceVariant)
                                                    else Icon(Icons.Outlined.Close, null, Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(if (syncing) "同步中" else "移除", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                    if (state.favoritesLoading || state.favoritesError != null || state.favoritesHasMore) {
                                        item(span = { GridItemSpan(maxLineSpan) }, key = "favorites-more") {
                                            AccountListFooter(state.favoritesLoading, state.favoritesError, onMoreFavorites)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    removeFavorite?.let { manga ->
        AlertDialog(
            onDismissRequest = { removeFavorite = null },
            shape = RoundedCornerShape(24.dp), containerColor = colors.surface,
            title = { Text("移除云端收藏？", style = MaterialTheme.typography.titleLarge) },
            text = { Text(manga.title, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) },
            confirmButton = {
                TextButton(enabled = manga.id !in state.syncingFavorites, onClick = {
                    removeFavorite = null
                    onSetFavorite(manga, false)
                }) { Text("移除", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { removeFavorite = null }) { Text("取消") } },
        )
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
private fun AccountError(message: String, busy: Boolean, onRetry: (() -> Unit)? = null) {
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

@Composable
private fun AccountListFooter(loading: Boolean, error: String?, onMore: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            error != null -> AccountError(error, false, onMore)
            else -> TextButton(onClick = onMore) { Text("加载更多") }
        }
    }
}

@Composable
private fun CloudContinueRow(entry: CloudHistoryEntry, repository: MangaRepository, onContinue: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).clickable(onClick = onContinue).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MangaCover(entry.manga, repository, Modifier.width(45.dp).height(62.dp).mangaCoverCard(8.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(if (entry.chapterId.isBlank()) "查看漫画" else "继续阅读", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(entry.manga.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MangaMetricBadges(entry.manga)
            if (entry.chapterTitle.isNotBlank()) Text(entry.chapterTitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.PlayArrow, "打开阅读记录", Modifier.size(23.dp))
    }
}


