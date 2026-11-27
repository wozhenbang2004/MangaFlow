package dev.mangaflow.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.data.SourceException
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.BodySourceInfo
import dev.mangaflow.data.sources.PicacgRegistrationIssue
import dev.mangaflow.data.sources.picacgRegistrationIssue
import dev.mangaflow.data.sources.SourceAccountCleanupException
import dev.mangaflow.data.sources.SourceAccountState
import dev.mangaflow.data.sources.SourceCapabilities
import dev.mangaflow.data.sources.SourceRegistrationRequest
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class SourceAccountPanel { LOGIN, REGISTER }

private enum class SourceAccountAction(val pending: String) {
    LOGIN("正在登录…"), VERIFY("正在验证并保存凭据…"), REGISTER("正在提交注册申请…"),
    LOGOUT("正在退出账号…"), CLEAR("正在清除凭据…");

    fun receipt(account: SourceAccountState): PreferenceNotice = when (this) {
        REGISTER -> PreferenceNotice("站点已接受注册请求，请使用新账号登录。如需邮箱验证，请按站点提示操作。", PreferenceNoticeKind.DONE)
        LOGIN, VERIFY -> if (account.signedIn) PreferenceNotice("已登录。", PreferenceNoticeKind.DONE)
            else PreferenceNotice(if (account.hasCredentials) "凭据已保存，仍需重新验证。" else "站点尚未确认登录。", PreferenceNoticeKind.INFO)
        LOGOUT, CLEAR -> if (!account.signedIn && !account.hasCredentials)
            PreferenceNotice(if (this == CLEAR) "本机凭据已清除。" else "已退出账号。", PreferenceNoticeKind.DONE)
            else PreferenceNotice("账号仍有登录状态或本机凭据。", PreferenceNoticeKind.INFO)
    }
}

@Composable
internal fun SourceAccountLoading(message: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MotionBusyIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One account form for the account destination and source settings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourceAccountControls(
    source: BodySourceInfo, repository: UnifiedMangaRepository, capabilities: SourceCapabilities,
    account: SourceAccountState, enabled: Boolean, onBusy: (Boolean) -> Unit,
    onAccountChanged: (SourceAccountState) -> Unit,
    onOpenShelf: (() -> Unit)? = null,
    initiallyShowLogin: Boolean = false,
    active: Boolean = true,
) = key(source.id, source.revision) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val desktop = platformKind == PlatformKind.DESKTOP
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val uriHandler = LocalUriHandler.current
    var panel by remember { mutableStateOf(SourceAccountPanel.LOGIN) }
    var expanded by remember { mutableStateOf(initiallyShowLogin && !account.signedIn &&
        (capabilities.passwordLogin || capabilities.cookieFields.isNotEmpty())) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var values by remember { mutableStateOf<List<String>>(emptyList()) }
    var cookieMode by remember { mutableStateOf(!capabilities.passwordLogin) }
    var registerStage by remember { mutableIntStateOf(0) }
    var registerValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var registerIssue by remember { mutableStateOf<PicacgRegistrationIssue?>(null) }
    var registerConsent by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var activeAction by remember { mutableStateOf(SourceAccountAction.LOGIN) }
    var notice by remember { mutableStateOf<PreferenceNotice?>(null) }
    var present by remember { mutableStateOf(true) }
    var generation by remember { mutableLongStateOf(0L) }
    val currentAccountChanged by rememberUpdatedState(onAccountChanged)
    val currentBusy by rememberUpdatedState(onBusy)
    val canLogin = capabilities.passwordLogin || capabilities.cookieFields.isNotEmpty()
    val web = capabilities.browserAccount
    fun clearInput() {
        password = ""; values = emptyList()
        registerValues = emptyMap()
        registerConsent = false
        registerStage = 0
        registerIssue = null
    }
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
                if (present && generation == expected) {
                    currentAccountChanged(refreshed)
                    if (desktop) notice = PreferenceNotice(when {
                        refreshed.signedIn -> "操作已结束，当前账号已登录。"
                        refreshed.hasCredentials -> "操作已结束，本机仍有登录凭据。"
                        else -> "操作已结束，当前账号未登录。"
                    }, PreferenceNoticeKind.INFO)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (present && generation == expected) notice = PreferenceNotice(
                    "账号状态未能重新读取，请重新打开来源设置。", PreferenceNoticeKind.ERROR)
            }
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
    LaunchedEffect(active) { if (!active) { cancel(); expanded = false } }
    fun selectPanel(next: SourceAccountPanel) {
        clearInput(); notice = null
        expanded = !expanded || panel != next
        panel = next
    }
    fun selectLoginMethod(credentials: Boolean) {
        if (cookieMode == credentials) return
        cookieMode = credentials
        password = ""
        values = emptyList()
        notice = null
    }
    fun openWebsite(url: String) {
        try { uriHandler.openUri(url) }
        catch (_: Exception) { notice = PreferenceNotice("无法打开浏览器，请稍后重试。", PreferenceNoticeKind.ERROR) }
    }
    fun operate(operation: SourceAccountAction, action: suspend () -> Unit) {
        if (running || !enabled || !present || !active) return
        activeAction = operation; running = true; currentBusy(true); notice = null
        val expected = ++generation
        job = scope.launch {
            var succeeded = false
            try {
                action()
                succeeded = true
                val refreshed = repository.sourceAccountState(source.id)
                if (present && generation == expected) {
                    currentAccountChanged(refreshed); expanded = false
                    if (desktop || operation == SourceAccountAction.REGISTER) notice = operation.receipt(refreshed)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (succeeded && (desktop || operation == SourceAccountAction.REGISTER)) {
                    if (present && generation == expected) {
                        expanded = false
                        notice = if (operation == SourceAccountAction.REGISTER) operation.receipt(account)
                            else PreferenceNotice("操作已提交，暂时无法确认账号状态。请重新打开来源设置查看。", PreferenceNoticeKind.INFO)
                    }
                    return@launch
                }
                val original = failure.message.orEmpty()
                val jmValidation = setOf("用户名需要2至80个字符。", "请填写有效邮箱地址。",
                    "两次密码不一致或密码过短。", "请选择站点支持的性别字段。",
                    "禁漫未接受注册请求，请核对资料和站点提示。", "禁漫注册响应无法确认，请勿连续重复提交。")
                val failureMessage = when {
                    failure is SourceAccountCleanupException -> failure.message.orEmpty()
                    source.id == "picacg" && failure is SourceException &&
                        (original.startsWith("哔咔注册：") || original.startsWith("哔咔登录：")) -> original
                    source.id == "picacg" && panel == SourceAccountPanel.REGISTER ->
                        (picacgRegistrationIssue(registerValues)
                            ?: picacgRegistrationIssue(registerValues, securityQuestions = true))
                            ?.message ?: repository.sourceHealth.value[source.id]?.message
                            ?: "暂未收到注册结果，请稍后查看站点状态。"
                    source.id == "jmcomic" && original in jmValidation -> original
                    else -> repository.sourceHealth.value[source.id]?.message ?: "验证未完成，请稍后重试。"
                }
                // An unsuccessful logout may still have cleared its local credential in finally.
                try {
                    val refreshed = repository.sourceAccountState(source.id)
                    if (present && generation == expected) currentAccountChanged(refreshed)
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Do not invent a new account state from a failed read. */ }
                if (present && generation == expected) {
                    val clearedFields = when {
                        operation != SourceAccountAction.REGISTER -> ""
                        source.id == "picacg" -> " 请重新填写密码和安全答案。"
                        else -> " 请重新填写密码。"
                    }
                    notice = PreferenceNotice(failureMessage + clearedFields, PreferenceNoticeKind.ERROR)
                }
            } finally {
                if (present && generation == expected) {
                    if (operation == SourceAccountAction.REGISTER && !succeeded) {
                        // Keep editable non-secret fields for a corrected retry without
                        // retaining passwords or recovery answers from a failed signup.
                        registerValues = registerValues.filterKeys { id ->
                            capabilities.registrationFields.none { it.id == id && it.secret }
                        }
                        registerConsent = false
                        registerStage = 0
                        registerIssue = null
                        password = ""; values = emptyList()
                    } else clearInput()
                    job = null; running = false; currentBusy(false)
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (desktop) 10.dp else 6.dp)) {
        val stateText = when {
            account.signedIn -> "已登录"
            account.hasCredentials -> "已保存凭据，需重新验证"
            capabilities.loginRequired -> "浏览和阅读需要登录"
            else -> "未登录"
        }
        val identity: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(if (desktop) 4.dp else 2.dp)) {
                Text(if (desktop && account.signedIn) account.displayName.ifBlank { "账号" } else "账号",
                    style = if (desktop) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                val label = if (!desktop && account.signedIn) account.displayName.ifBlank { stateText } else stateText
                if (desktop) Text(label, style = MaterialTheme.typography.labelLarge,
                    color = if (account.signedIn) mangaStatusColors().success else colors.onSurfaceVariant)
                else Text(label, fontSize = 11.sp, color = colors.onSurfaceVariant)
            }
        }
        val actions: @Composable () -> Unit = {
            if (running) MotionTextButton(onClick = ::cancel, enabled = !cancelling) { Text(if (cancelling) "正在取消…" else "取消", fontSize = 12.sp) }
            else if (account.signedIn) {
                if (onOpenShelf != null) MotionTextButton(onClick = onOpenShelf, enabled = enabled) {
                    Text("站点收藏", fontSize = 12.sp)
                }
                MotionTextButton(onClick = { operate(SourceAccountAction.LOGOUT) { repository.sourceLogout(source.id) } }, enabled = enabled) {
                    Text("退出", fontSize = 12.sp)
                }
            } else if (expanded) {
                MotionTextButton(onClick = { selectPanel(panel) }, enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                    Text("收起", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (desktop) BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            val stacked = maxWidth < 360.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                identity()
                if (running || account.signedIn || expanded) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { identity() }
                actions()
            }
        } else Row(Modifier.fillMaxWidth().accountReviewRow(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { identity() }
            actions()
        }
        if (!running && !account.signedIn) {
            val localPanels = buildList {
                if (canLogin) add(SourceAccountPanel.LOGIN)
                if (capabilities.registrationFields.isNotEmpty()) add(SourceAccountPanel.REGISTER)
            }
            val panelLabels = localPanels.map {
                when (it) {
                    SourceAccountPanel.LOGIN -> if (account.hasCredentials) "重新登录" else "登录"
                    SourceAccountPanel.REGISTER -> "注册"
                }
            }
            if (localPanels.size > 1) MotionTabs(
                selected = localPanels.indexOf(panel).coerceAtLeast(0),
                labels = panelLabels,
                onSelect = { if (enabled && (!expanded || panel != localPanels[it])) selectPanel(localPanels[it]) },
                modifier = Modifier.fillMaxWidth().motionInputGate(enabled),
                controlHeight = 44.dp, treatment = MotionTabTreatment.Soft,
                selectionVisible = expanded,
            )
            else if (localPanels.size == 1 && !expanded) MotionTextButton(
                onClick = { selectPanel(localPanels.single()) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface,
                    containerColor = colors.onSurface.copy(alpha = .055f))) {
                Text(panelLabels.single(), style = MaterialTheme.typography.labelLarge)
            }
            val hasWebActions = (!canLogin && web != null) ||
                capabilities.registrationFields.isEmpty() && (web?.registerUrl != null || web?.registrationHelpUrl != null)
            if (hasWebActions || account.hasCredentials) Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!canLogin && web != null) MotionTextButton(onClick = { openWebsite(web.loginUrl) }, enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) {
                    Text("网页登录", fontSize = 12.sp)
                }
                if (capabilities.registrationFields.isEmpty()) {
                    web?.registerUrl?.let { url ->
                        MotionTextButton(onClick = { openWebsite(url) }, enabled = enabled,
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) { Text("网页注册", fontSize = 12.sp) }
                    } ?: web?.registrationHelpUrl?.let { url ->
                        MotionTextButton(onClick = { openWebsite(url) }, enabled = enabled,
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) { Text("注册说明", fontSize = 12.sp) }
                    }
                }
                if (account.hasCredentials) MotionTextButton(onClick = { operate(SourceAccountAction.CLEAR) { repository.sourceLogout(source.id) } }, enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                    Text("清除凭据", fontSize = 12.sp)
                }
            }
        }
        if (desktop) PreferenceOperationFeedback(
            if (running) PreferenceNotice(if (cancelling) "正在取消并核对账号状态…" else activeAction.pending, PreferenceNoticeKind.WORKING)
            else notice
        ) else notice?.let { Text(it.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, fontSize = 12.sp,
            color = if (it.kind == PreferenceNoticeKind.ERROR) colors.error else colors.onSurfaceVariant) }
        AccountFormReveal(expanded && !account.signedIn) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val registerMode = panel == SourceAccountPanel.REGISTER
                if (!registerMode && capabilities.passwordLogin && capabilities.cookieFields.isNotEmpty()) {
                    MotionTabs(if (cookieMode) 1 else 0, listOf("账号密码", capabilities.credentialLabel),
                        onSelect = { if (enabled && !running) selectLoginMethod(it == 1) },
                        modifier = Modifier.fillMaxWidth().motionInputGate(enabled && !running),
                        treatment = MotionTabTreatment.Underline, controlHeight = 44.dp)
                }
                val credentialMode = capabilities.cookieFields.isNotEmpty() && (!capabilities.passwordLogin || cookieMode)
                if (registerMode && capabilities.registrationFields.isNotEmpty()) {
                    val fields = capabilities.registrationFields
                    val secondPage = fields.size > 6
                    val visible = if (!secondPage) fields else if (registerStage == 0) fields.take(6) else fields.drop(6)
                    val fieldFocus = remember(visible) {
                        visible.filter { it.choices.isEmpty() }.associate { it.id to FocusRequester() }
                    }
                    LaunchedEffect(registerIssue, fieldFocus) {
                        fieldFocus[registerIssue?.fieldId]?.requestFocus()
                    }
                    Text(if (!secondPage) "注册账号" else if (registerStage == 0) "注册 · 基本资料" else "注册 · 安全问题",
                        fontSize = 12.sp, color = colors.onSurfaceVariant)
                    visible.forEach { field ->
                        val value = registerValues[field.id].orEmpty()
                        if (field.choices.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(field.title, fontSize = 11.sp, color = colors.onSurfaceVariant)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    field.choices.forEach { choice ->
                                        SourceAccountChoice(choice.title, value.ifBlank { "bot" } == choice.id,
                                            enabled && !running) {
                                            registerValues = registerValues + (field.id to choice.id)
                                            if (registerIssue?.fieldId == field.id) registerIssue = null
                                        }
                                    }
                                }
                            }
                        } else {
                            SourceAccountField(field.title, value, enabled && !running, field.secret,
                                modifier = fieldFocus[field.id]?.let { Modifier.focusRequester(it) } ?: Modifier) {
                                if (it.length <= 512 && it.none { ch -> ch == '\r' || ch == '\n' }) {
                                    registerValues = registerValues + (field.id to it)
                                    if (registerIssue?.fieldId == field.id) registerIssue = null
                                }
                            }
                        }
                        registerIssue?.takeIf { it.fieldId == field.id }?.let { issue ->
                            Text(issue.message, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                fontSize = 12.sp, color = colors.error)
                        }
                    }
                    if (registerStage == 0 && secondPage) {
                        MotionButton(onClick = {
                            registerIssue = if (source.id == "picacg") picacgRegistrationIssue(registerValues) else null
                            if (registerIssue == null) registerStage = 1
                        }, enabled = enabled && !running && (source.id == "picacg" ||
                            fields.take(5).all { registerValues[it.id].orEmpty().isNotBlank() })) {
                            Text("下一步", fontSize = 12.sp)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = registerConsent, onCheckedChange = { registerConsent = it },
                                enabled = enabled && !running)
                            Text("我确认已年满18岁，并同意向该站点提交注册资料",
                                fontSize = 11.sp, color = colors.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (secondPage) MotionTextButton(onClick = {
                                registerStage = 0
                                registerIssue = null
                            }, enabled = enabled && !running) {
                                Text("上一步", fontSize = 12.sp)
                            }
                            MotionButton(onClick = {
                                val issue = if (source.id == "picacg") picacgRegistrationIssue(registerValues)
                                    ?: picacgRegistrationIssue(registerValues, securityQuestions = true) else null
                                if (issue != null) {
                                    if (visible.none { it.id == issue.fieldId }) registerStage = 0
                                    registerIssue = issue
                                } else {
                                    val captured = SourceRegistrationRequest(registerValues.toMap(), registerConsent, registerConsent)
                                    operate(SourceAccountAction.REGISTER) {
                                        repository.sourceRegister(source.id, captured)
                                    }
                                }
                            }, enabled = enabled && !running && registerConsent && (source.id == "picacg" ||
                                fields.all { field -> registerValues[field.id].orEmpty().isNotBlank() ||
                                    (field.id == "gender" && field.choices.any { it.id == "bot" }) })) {
                                Text("提交注册", fontSize = 12.sp)
                            }
                        }
                    }
                } else if (credentialMode) {
                    web?.let { links ->
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            MotionTextButton(onClick = { openWebsite(links.loginUrl) }, enabled = enabled && !running) {
                                Text("网页登录", fontSize = 12.sp)
                            }
                            links.credentialSettingsUrl?.let { url ->
                                MotionTextButton(onClick = { openWebsite(url) }, enabled = enabled && !running) {
                                    Text("获取 ${capabilities.credentialLabel}", fontSize = 12.sp)
                                }
                            }
                        }
                    }
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
                        operate(SourceAccountAction.VERIFY) { repository.sourceLoginCookies(source.id, submitted) }
                    }, enabled = enabled && !running && values.any(String::isNotBlank)) {
                        Text(if (running) "验证中…" else "验证并保存", fontSize = 12.sp)
                    }
                } else if (capabilities.passwordLogin) {
                    val canSubmit = enabled && !running && username.isNotBlank() && password.isNotBlank()
                    val submit: () -> Unit = {
                        if (canSubmit) {
                            val user = username
                            val secret = password
                            clearInput()
                            operate(SourceAccountAction.LOGIN) { repository.sourceLogin(source.id, user, secret) }
                        }
                    }
                    SourceAccountField("账号", username, enabled && !running, false) { if (it.length <= 160) username = it }
                    SourceAccountField("密码", password, enabled && !running, true,
                        onSubmit = if (platformKind == PlatformKind.DESKTOP) submit else null) { if (it.length <= 512) password = it }
                    Text("密码只用于本次提交，不保存密码。", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    MotionButton(onClick = submit, enabled = canSubmit) {
                        Text(if (running) "登录中…" else "登录", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountFormReveal(visible: Boolean, content: @Composable () -> Unit) {
    if (platformKind == PlatformKind.DESKTOP) CopyShelfReveal(visible, content = content)
    else AnimatedVisibility(visible) { content() }
}

@Composable
private fun SourceAccountChoice(label: String, chosen: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    MotionTextButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { selected = chosen },
        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface,
            containerColor = if (chosen) colors.surfaceVariant else Color.Transparent),
        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (chosen)
            FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
internal fun SourceAccountField(
    label: String, value: String, enabled: Boolean, secret: Boolean,
    singleLine: Boolean = true,
    modifier: Modifier = Modifier, onSubmit: (() -> Unit)? = null, onChange: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val desktop = platformKind == PlatformKind.DESKTOP
    var focused by remember { mutableStateOf(false) }
    BasicTextField(value, onChange, modifier = modifier.fillMaxWidth().semantics { contentDescription = label }
        .onFocusChanged { focused = it.isFocused }.then(if (desktop) Modifier else Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(lerp(colors.surface, if (focused) colors.primary else colors.onSurface, if (focused) .095f else .045f))
            .heightIn(min = 50.dp).padding(horizontal = 14.dp, vertical = 8.dp)), singleLine = singleLine,
        maxLines = if (singleLine) 1 else 6, enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
            imeAction = if (onSubmit != null) ImeAction.Done else if (singleLine) ImeAction.Next else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { if (enabled) onSubmit?.invoke() }),
        cursorBrush = SolidColor(colors.primary), textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        decorationBox = { inner -> Column(verticalArrangement = Arrangement.spacedBy(if (desktop) 5.dp else 2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (focused) colors.primary else colors.onSurfaceVariant)
            if (desktop) Box(Modifier.fillMaxWidth().background(if (focused) colors.primary.copy(alpha = .09f)
                else colors.onSurface.copy(alpha = .05f), RoundedCornerShape(9.dp)).heightIn(min = 42.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.CenterStart) { inner() }
            else inner()
        } })
}
