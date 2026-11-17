package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.data.sources.SourceAccountCleanupException
import dev.mangaflow.data.sources.SourceAccountState
import dev.mangaflow.data.sources.SourceCapabilities
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Reuses the source-account page's fields and Motion controls in the source's own sheet. */
@Composable
internal fun SourceAccountControls(
    source: BodySourceInfo, repository: UnifiedMangaRepository, capabilities: SourceCapabilities,
    account: SourceAccountState, enabled: Boolean, onBusy: (Boolean) -> Unit,
    onAccountChanged: (SourceAccountState) -> Unit,
) = key(source.id) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var expanded by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var values by remember { mutableStateOf<List<String>>(emptyList()) }
    var cookieMode by remember { mutableStateOf(!capabilities.passwordLogin) }
    var running by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var present by remember { mutableStateOf(true) }
    var generation by remember { mutableLongStateOf(0L) }
    val currentAccountChanged by rememberUpdatedState(onAccountChanged)
    val currentBusy by rememberUpdatedState(onBusy)
    fun clearInput() { password = ""; values = emptyList() }
    fun cancel() {
        clearInput()
        if (cancelling) return
        val stopped = job ?: return
        val expected = ++generation
        cancelling = true
        stopped.cancel()
        // Keep the sheet locked until the previous operation actually exits. Cancellation
        // can race a secure-storage commit, so refresh the receipt before unlocking.
        if (present) scope.launch {
            try {
                stopped.join()
                val refreshed = repository.sourceAccountState(source.id)
                if (present && generation == expected) currentAccountChanged(refreshed)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (present && generation == expected) message = "账号状态未能重新读取，请重新打开来源设置。" }
            finally {
                if (present && generation == expected) {
                    job = null; running = false; cancelling = false; currentBusy(false)
                }
            }
        }
    }
    DisposableEffect(lifecycle) {
        present = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { cancel(); expanded = false }
        }
        lifecycle.addObserver(observer)
        onDispose {
            present = false; lifecycle.removeObserver(observer)
            generation++; job?.cancel(); job = null; clearInput()
            // The account owner is gone; never leave its parent sheet permanently locked.
            if (running) currentBusy(false)
            running = false; cancelling = false
        }
    }
    LaunchedEffect(source.revision) { clearInput(); expanded = false }
    fun operate(action: suspend () -> Unit) {
        if (running || !enabled || !present) return
        running = true; currentBusy(true); message = null
        val expected = ++generation
        job = scope.launch {
            try {
                action()
                val refreshed = repository.sourceAccountState(source.id)
                if (present && generation == expected) { currentAccountChanged(refreshed); expanded = false }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                val failureMessage = (failure as? SourceAccountCleanupException)?.message ?: repository.sourceHealth.value[source.id]?.message ?: "验证未完成，请稍后重试。"
                // An unsuccessful logout may still have cleared its local credential in finally.
                try {
                    val refreshed = repository.sourceAccountState(source.id)
                    if (present && generation == expected) currentAccountChanged(refreshed)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Do not invent a new account state from a failed read. */ }
                if (present && generation == expected) message = failureMessage
            } finally {
                if (present && generation == expected) { clearInput(); job = null; running = false; currentBusy(false) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("账号", style = MaterialTheme.typography.titleSmall)
                Text(if (account.signedIn) account.displayName.ifBlank { "已验证登录" }
                    else if (account.hasCredentials) "有已保存凭据，尚未验证账号"
                    else if (capabilities.loginRequired) "此来源需要登录" else "匿名浏览 · 登录可用账号功能",
                    fontSize = 11.sp, color = colors.onSurfaceVariant)
            }
            if (running) MotionTextButton(onClick = ::cancel, enabled = !cancelling) { Text(if (cancelling) "正在取消…" else "取消", fontSize = 12.sp) }
            else MotionTextButton(onClick = {
                if (account.signedIn || account.hasCredentials) operate { repository.sourceLogout(source.id) }
                else { expanded = !expanded; clearInput(); message = null }
            }, enabled = enabled) { Text(if (account.signedIn) "退出" else if (account.hasCredentials) "清除凭据" else if (expanded) "收起" else "登录", fontSize = 12.sp) }
        }
        AnimatedVisibility(expanded && !account.signedIn) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (capabilities.passwordLogin && capabilities.cookieFields.isNotEmpty()) Row {
                    MotionTextButton(onClick = { cookieMode = false; clearInput() }, enabled = enabled && !running) { Text("账号密码", fontSize = 12.sp) }
                    MotionTextButton(onClick = { cookieMode = true; clearInput() }, enabled = enabled && !running) { Text(capabilities.credentialLabel, fontSize = 12.sp) }
                }
                val credentialMode = capabilities.cookieFields.isNotEmpty() && (!capabilities.passwordLogin || cookieMode)
                if (credentialMode) {
                    Text("仅向此来源验证你的 ${capabilities.credentialLabel}；通过后保存在本机安全存储，退出时移除。",
                        fontSize = 11.sp, color = colors.onSurfaceVariant)
                    capabilities.cookieFields.forEachIndexed { index, name ->
                        SourceAccountField(name, values.getOrNull(index).orEmpty(), enabled && !running, true) { value ->
                            if (value.length <= 8192 && value.none { it == '\r' || it == '\n' || it == ';' })
                                values = capabilities.cookieFields.indices.map { if (it == index) value else values.getOrNull(it).orEmpty() }
                        }
                    }
                    MotionButton(onClick = {
                        val submitted = capabilities.cookieFields.indices.map { values.getOrNull(it).orEmpty() }
                        clearInput()
                        operate { repository.sourceLoginCookies(source.id, submitted) }
                    }, enabled = enabled && !running && values.any(String::isNotBlank)) {
                        Text(if (running) "验证中…" else "验证并保存", fontSize = 12.sp)
                    }
                } else if (capabilities.passwordLogin) {
                    SourceAccountField("账号", username, enabled && !running, false) { if (it.length <= 160) username = it }
                    SourceAccountField("密码", password, enabled && !running, true) { if (it.length <= 512) password = it }
                    Text("密码只用于本次提交，不保存密码。", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    MotionButton(onClick = {
                        val user = username; val secret = password; clearInput()
                        operate { repository.sourceLogin(source.id, user, secret) }
                    }, enabled = enabled && !running && username.isNotBlank() && password.isNotBlank()) {
                        Text(if (running) "登录中…" else "登录", fontSize = 12.sp)
                    }
                }
            }
        }
        message?.let { Text(it, fontSize = 11.sp, color = colors.error) }
    }
}

@Composable
private fun SourceAccountField(label: String, value: String, enabled: Boolean, secret: Boolean, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(value, onChange, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
        .background(colors.onSurface.copy(alpha = .045f)).padding(12.dp), singleLine = true, enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            imeAction = ImeAction.Next), textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
        decorationBox = { inner -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 11.sp, color = colors.onSurfaceVariant); inner()
        } })
}
