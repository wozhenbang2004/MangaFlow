package dev.mangaflow.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Manga
import dev.mangaflow.tracking.*
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.NativeTrackingWebsiteLogin
import dev.mangaflow.ui.platform.trackingWebsiteLoginSupported

/** Retained Ovio panel: account, explicit identity review, then one visible sync direction. */
@Composable
internal fun MangaTrackingSheet(
    visible: Boolean,
    state: MangaTrackingState,
    controller: MangaTrackingController,
    backdrop: HazeState,
    onContinueRead: (Manga, Chapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val loginVisible by rememberUpdatedState(visible)
    if (!visible) return
    val colors = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    var providerIndex by remember(state.ready) {
        val linked = state.bindings.firstOrNull { binding ->
            binding.mangaId == state.manga?.id && binding.ownerId == state.accounts[binding.provider]?.ownerId
        }?.provider
        val initial = linked ?: MangaTrackingProvider.entries.firstOrNull { it in state.accounts }
        mutableIntStateOf(initial?.ordinal ?: 0)
    }
    val provider = MangaTrackingProvider.entries[providerIndex]
    val account = state.accounts[provider]
    val work = state.manga
    val bound = state.bindings.firstOrNull {
        it.provider == provider && it.ownerId == account?.ownerId && it.mangaId == work?.id
    }
    val review = state.reviews[provider]?.takeIf { it.remote.ownerId == account?.ownerId && it.mangaId == work?.id }
    val preview = state.preview?.takeIf { it.provider == provider && it.ownerId == account?.ownerId }
    val pending = state.pending.filter { it.expected.provider == provider && it.expected.ownerId == account?.ownerId &&
        (work == null || it.bindingKey == bound?.key) }
    val busy = state.busy || state.syncing
    val enabled = visible && state.ready && !busy
    var token by remember(provider, account?.ownerId) { mutableStateOf("") }
    var showCredential by remember(provider, account?.ownerId) { mutableStateOf(false) }
    var manualCredential by remember(provider, account?.ownerId) { mutableStateOf(false) }
    var authorization by remember { mutableStateOf<MangaTrackingAuthorization?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var showSearch by remember(provider, account?.ownerId, work?.id) { mutableStateOf(false) }
    var query by remember(provider, work?.id) { mutableStateOf(work?.title.orEmpty()) }
    var remoteId by remember(provider, work?.id) { mutableStateOf("") }
    var selectedNumbering by remember(preview?.subject?.id, preview?.ownerId, work?.id) {
        mutableStateOf<MangaTrackingNumbering?>(null)
    }
    var changeNumbering by remember(bound?.key) { mutableStateOf(false) }
    var externalError by remember { mutableStateOf<String?>(null) }
    val draftKey = review?.token
    var chaptersText by remember(draftKey) { mutableStateOf(review?.local?.chapters?.toString().orEmpty()) }
    var volumesText by remember(draftKey) { mutableStateOf(review?.local?.volumes?.toString().orEmpty()) }
    var chosenStatus by remember(draftKey) { mutableStateOf(review?.local?.status) }
    val draftChapters = chaptersText.toIntOrNull()?.takeIf { it >= 0 }
    val draftVolumes = volumesText.toIntOrNull()?.takeIf { it >= 0 }
    val selectedProgress = if (draftChapters != null && draftVolumes != null && chosenStatus != null)
        MangaTrackingProgress(chosenStatus, draftChapters, draftVolumes) else null
    val continuation = remember(controller, provider, bound, review, state.chapters) {
        controller.continuationTarget(provider)
    }
    val openLink: (String) -> Unit = { url ->
        externalError = runCatching { uri.openUri(url) }.exceptionOrNull()?.let { "无法打开浏览器，请检查设备的默认浏览器。" }
    }
    val cancelWebsite: (String) -> Unit = { id ->
        controller.cancelWebsiteAuthorization(id)
        if (authorization?.id == id) authorization = null
    }
    val dismissPanel: () -> Unit = {
        authorization?.let { cancelWebsite(it.id) }
        token = ""; showCredential = false; manualCredential = false
        onDismiss()
    }
    DisposableEffect(authorization?.id, lifecycle) {
        val owned = authorization
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && owned != null && authorization?.id == owned.id) {
                cancelWebsite(owned.id)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            owned?.let { controller.cancelWebsiteAuthorization(it.id) }
        }
    }
    LaunchedEffect(bound?.subject?.id) { if (bound != null) showSearch = false }

    MotionBottomSheet(onDismissRequest = dismissPanel, backdrop = backdrop) { dismiss ->
        val website = authorization
        Column(Modifier.fillMaxWidth().then(if (website != null)
            Modifier.height(LocalMangaWindow.current.panelHeight.coerceAtMost(720.dp))
            else Modifier.heightIn(max = LocalMangaWindow.current.panelHeight))) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (website != null) MotionIconButton(onClick = {
                    cancelWebsite(website.id)
                }, modifier = Modifier.size(44.dp), tooltip = "返回账号同步") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(website?.let { "连接 " + it.provider.label } ?: "漫画进度同步",
                        style = MaterialTheme.typography.titleMedium)
                    if (website != null) TrackingHint(if (website.provider == MangaTrackingProvider.BANGUMI)
                        "Bangumi 官方页面 · next.bgm.tv" else "AniList 官方页面 · anilist.co")
                    else work?.let { Text(it.title, style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                MotionIconButton(onClick = {
                    authorization?.let { cancelWebsite(it.id) }
                    dismiss()
                }, modifier = Modifier.size(48.dp), tooltip = "关闭漫画进度同步") {
                    Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp))
                }
            }
            if (website != null) key(website.id) {
                var loading by remember { mutableStateOf(true) }
                if (loading) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MotionBusyIndicator(Modifier.size(18.dp))
                    TrackingHint("正在连接官方页面…")
                }
                NativeTrackingWebsiteLogin(authorization = website,
                    onCancel = cancelWebsite,
                    onCredential = { id, credential ->
                        if (loginVisible && authorization?.id == id) {
                            controller.completeWebsiteAuthorization(id, credential)
                            authorization = null
                            showCredential = false; manualCredential = false; token = ""
                        } else controller.cancelWebsiteAuthorization(id)
                    },
                    onLoading = { if (authorization?.id == website.id) loading = it },
                    onError = { message ->
                        if (loginVisible && authorization?.id == website.id) {
                            externalError = message
                            cancelWebsite(website.id)
                        }
                    }, modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp))
            } else {
                MotionTabs(providerIndex, MangaTrackingProvider.entries.map { it.label }, onSelect = {
                    providerIndex = it; externalError = null
                }, modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                    controlHeight = 48.dp, fullWidthIndicator = true,
                    track = colors.onSurface.copy(alpha = .045f))
                val list = key(provider, work?.id, account?.ownerId) { rememberLazyListState() }
                LazyColumn(Modifier.weight(1f, fill = false), state = list,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (busy) item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.8.dp)
                            TrackingHint(if (state.syncing) "正在核对待同步进度…" else "正在连接或核对 " +
                                (state.checkingProvider?.label ?: provider.label) + "…")
                        }
                    }
                    val feedback = externalError ?: state.error ?: state.message
                    if (feedback != null) item {
                        Text(feedback, style = MaterialTheme.typography.bodySmall,
                            color = if (externalError != null || state.error != null) colors.error else colors.onSurfaceVariant,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (account != null) item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(account.displayName.ifBlank { account.ownerId }, style = MaterialTheme.typography.titleSmall)
                                TrackingHint(provider.label + " · 账号 " + account.ownerId)
                            }
                            MotionTextButton(onClick = { showCredential = !showCredential }, enabled = enabled) { Text("更新授权") }
                            MotionTextButton(onClick = { controller.disconnect(provider) }, enabled = enabled) { Text("断开") }
                        }
                    }
                    if (account == null || showCredential) {
                        item {
                            val websiteConfigured = provider == MangaTrackingProvider.BANGUMI || state.aniListClientId.isNotBlank()
                            if (trackingWebsiteLoginSupported() && websiteConfigured) {
                                TrackingHint("同步收藏、话数和卷数，本机页码会保留。")
                                val interaction = remember { MutableInteractionSource() }
                                LiquidGlassSurface(backdrop, Modifier.fillMaxWidth().padding(top = 10.dp),
                                    cornerRadius = 18.dp, role = GlassSurfaceRole.CONTROL,
                                    interactionSource = interaction, elevation = 1.dp) {
                                    MotionButton(onClick = {
                                        externalError = null; token = ""
                                        authorization = controller.beginWebsiteAuthorization(provider)
                                    }, enabled = enabled, interactionSource = interaction,
                                        shape = RoundedCornerShape(18.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.surface.copy(alpha = .54f), contentColor = colors.onSurface),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        ReviewSourceMark(provider.id, size = 17.dp)
                                        Text(if (account == null) "登录 " + provider.label else "重新登录 " + provider.label,
                                            Modifier.weight(1f).padding(horizontal = 10.dp))
                                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                                    }
                                }
                                TrackingHint(if (provider == MangaTrackingProvider.BANGUMI)
                                    "在 Bangumi 官网确认创建授权后会自动返回。"
                                else "在 AniList 官网授权后会自动返回。",
                                    Modifier.padding(top = 8.dp))
                            } else {
                                TrackingHint(if (!websiteConfigured) "当前版本尚未配置 AniList 登录，可先连接 Bangumi。"
                                    else "当前平台暂不支持软件内登录，可使用备用方式连接。")
                            }
                        }
                        item {
                            MotionTextButton(onClick = { manualCredential = !manualCredential; token = "" }, enabled = enabled) {
                                Text("使用已有令牌")
                                Icon(if (manualCredential) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                    if (manualCredential) "收起" else "展开", Modifier.size(16.dp))
                            }
                        }
                        if (manualCredential) item {
                            TrackingInput(token, { token = it }, "已有访问令牌", enabled = enabled, secret = true)
                            MotionButton(onClick = {
                                controller.connect(provider, token); token = ""; showCredential = false; manualCredential = false
                            }, enabled = enabled && token.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) {
                                Text("连接并核对账号")
                            }
                        }
                    }
                    if (account != null && work == null) item {
                        TrackingHint("账号连接后，可在任意普通漫画的详情页打开进度同步，确认条目后对照。")
                    }
                    if (account != null && work != null) {
                        item { HorizontalDivider(color = colors.outline.copy(alpha = .12f)) }
                        if (bound != null) {
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(bound.subject.title, style = MaterialTheme.typography.titleSmall)
                                        TrackingHint(provider.label + " · " + (bound.numbering?.label ?: "手动核对话／卷"))
                                    }
                                    MotionIconButton(onClick = { openLink(bound.subject.url) }, modifier = Modifier.size(44.dp),
                                        tooltip = "打开已绑定的漫画条目") {
                                        Icon(Icons.Outlined.OpenInNew, "打开漫画条目", Modifier.size(18.dp))
                                    }
                                }
                            }
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    MotionOutlinedButton(onClick = { controller.review(provider) }, enabled = enabled,
                                        modifier = Modifier.weight(1f).heightIn(min = 44.dp)) { Text("重新对照") }
                                    MotionTextButton(onClick = { showSearch = !showSearch }, enabled = enabled) { Text("更正条目") }
                                    MotionTextButton(onClick = { changeNumbering = !changeNumbering }, enabled = enabled) { Text("编号方式") }
                                }
                            }
                            if (changeNumbering) item {
                                TrackingNumberingChoices(state.numberings, bound.numbering, enabled) {
                                    controller.setNumbering(provider, it); changeNumbering = false
                                }
                            }
                        }
                        if (bound == null || showSearch) {
                            item {
                                TrackingInput(query, { query = it }, "搜索漫画名称", enabled = enabled)
                                MotionOutlinedButton(onClick = { controller.search(provider, query) },
                                    enabled = enabled && query.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    Text("搜索 " + provider.label + " 漫画")
                                }
                            }
                            items(state.candidates[provider].orEmpty(), key = { "candidate-" + provider.id + "-" + it.id }) { subject ->
                                MotionTextButton(onClick = { controller.review(provider, subject.id) }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(subject.title, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        TrackingHint(subject.subtitle)
                                    }
                                }
                            }
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TrackingInput(remoteId, { remoteId = it.filter { char -> char in '0'..'9' }.take(10) },
                                        "原站漫画条目 ID", enabled = enabled, number = true, modifier = Modifier.weight(1f))
                                    MotionTextButton(onClick = { controller.review(provider, remoteId) },
                                        enabled = enabled && remoteId.toIntOrNull()?.let { it > 0 } == true) { Text("读取") }
                                }
                                state.suggestedIds[provider]?.let { suggested ->
                                    MotionTextButton(onClick = { controller.review(provider, suggested) }, enabled = enabled) {
                                        Text("核对已有评分条目 · " + suggested)
                                    }
                                }
                            }
                        }
                        if (preview != null) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("确认漫画版本", style = MaterialTheme.typography.titleSmall)
                                    Text(preview.subject.title, style = MaterialTheme.typography.bodyLarge)
                                    TrackingHint(preview.subject.subtitle)
                                    TrackingHint("云端：" + progressLabel(preview.progress))
                                    MotionTextButton(onClick = { openLink(preview.subject.url) }, enabled = visible) {
                                        Text("查看原站条目")
                                        Icon(Icons.Outlined.OpenInNew, null, Modifier.padding(start = 6.dp).size(16.dp))
                                    }
                                }
                            }
                            item {
                                TrackingNumberingChoices(state.numberings, selectedNumbering, enabled) { selectedNumbering = it }
                                TrackingHint("请选择与该版本一致的话数或卷数。分话、番外和跳号会留给你手动核对。",
                                    Modifier.padding(top = 6.dp))
                            }
                            item {
                                MotionButton(onClick = { controller.confirmBinding(provider, selectedNumbering) },
                                    enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    Text("确认漫画与编号方式")
                                }
                            }
                        }
                        if (review != null && bound != null) {
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        TrackingHint("本机对照")
                                        Text(progressLabel(review.local), style = MaterialTheme.typography.bodyMedium)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        TrackingHint(provider.label + " 云端")
                                        Text(progressLabel(review.remote.progress), style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                            item {
                                Text("准备回写的进度", style = MaterialTheme.typography.titleSmall)
                                TrackingHint("本机建议包含已完读章节的最大编号；跳读过的话请先核对。", Modifier.padding(top = 5.dp))
                                val statuses = MangaTrackingStatus.entries.filter {
                                    provider != MangaTrackingProvider.BANGUMI || it != MangaTrackingStatus.REPEATING
                                }
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                                    statuses.forEach { status ->
                                        MotionTextButton(onClick = { chosenStatus = status }, enabled = enabled,
                                            colors = ButtonDefaults.textButtonColors(
                                                contentColor = if (chosenStatus == status) colors.primary else colors.onSurfaceVariant)) {
                                            Text(status.label, fontWeight = if (chosenStatus == status) FontWeight.SemiBold else FontWeight.Normal)
                                        }
                                    }
                                }
                            }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    TrackingInput(chaptersText, { chaptersText = it.filter { char -> char in '0'..'9' }.take(10) },
                                        "已读话数", enabled = enabled, number = true, modifier = Modifier.weight(1f))
                                    TrackingInput(volumesText, { volumesText = it.filter { char -> char in '0'..'9' }.take(10) },
                                        "已读卷数", enabled = enabled, number = true, modifier = Modifier.weight(1f))
                                }
                                val totals = listOfNotNull(review.remote.subject.chapters?.let { "全 " + it + " 话" },
                                    review.remote.subject.volumes?.let { "全 " + it + " 卷" })
                                if (totals.isNotEmpty()) TrackingHint(totals.joinToString(" · "), Modifier.padding(top = 6.dp))
                            }
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MotionButton(onClick = { controller.applyLocal(provider, selectedProgress, review.token) },
                                        enabled = enabled && selectedProgress != null,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("回写所选进度到 " + provider.label) }
                                    MotionOutlinedButton(onClick = { controller.applyRemote(provider, review.token) },
                                        enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text("采用云端进度")
                                    }
                                }
                            }
                            item {
                                if (continuation != null) {
                                    MotionTextButton(onClick = { onDismiss(); onContinueRead(work, continuation) },
                                        enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                        Text("按云端继续 · " + continuation.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                } else TrackingHint("当前目录没有唯一对应的下一话／卷，可从目录选择续读章节。")
                            }
                        }
                        if (bound != null) {
                            item {
                                SettingsToggle("读完后自动同步", bound.autoSync, Icons.Outlined.Sync,
                                    enabled = enabled && bound.numbering != null,
                                    onCheckedChange = { controller.setAutoSync(provider, it) })
                                TrackingHint(if (bound.numbering == null) "先确认目录编号方式，才能开启自动同步。"
                                    else "实际读完连续的下一话／卷才会推进。跳号、分话与番外先保留在本机；重读已完成话不会改动收藏状态。")
                            }
                            item {
                                MotionTextButton(onClick = { controller.unbind(provider) }, enabled = enabled) { Text("解除本机绑定") }
                            }
                        }
                    }
                    if (pending.isNotEmpty()) {
                        item {
                            HorizontalDivider(color = colors.outline.copy(alpha = .12f))
                            Text("待核对的同步记录 · " + pending.size, style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 12.dp))
                        }
                        items(pending, key = { "pending-" + it.token }) { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.expected.subject.title, style = MaterialTheme.typography.bodyMedium)
                                TrackingHint(item.message ?: if (item.submitted) "请求已发出，等待核对云端结果。" else "进度已保存，等待回写。")
                                TrackingHint("所选：" + progressLabel(item.desired))
                            }
                        }
                        item {
                            MotionOutlinedButton(onClick = { controller.retryPending(provider) }, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text("重新核对待同步记录") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingNumberingChoices(
    choices: List<MangaTrackingNumbering>,
    selected: MangaTrackingNumbering?,
    enabled: Boolean,
    onSelect: (MangaTrackingNumbering?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        (listOf<MangaTrackingNumbering?>(null) + choices).forEach { choice ->
            MotionTextButton(onClick = { onSelect(choice) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Icon(if (selected == choice) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                    null, Modifier.size(18.dp))
                Text(choice?.label ?: "手动核对话／卷", Modifier.weight(1f).padding(start = 10.dp))
            }
        }
    }
}

@Composable
private fun TrackingHint(value: String, modifier: Modifier = Modifier) {
    Text(value, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TrackingInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    secret: Boolean = false,
    number: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedTextField(value, onValueChange, modifier = modifier, enabled = enabled, singleLine = true,
        label = { Text(label) }, shape = RoundedCornerShape(16.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else if (number) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant,
            disabledContainerColor = colors.surfaceVariant, disabledTextColor = colors.onSurfaceVariant,
            focusedBorderColor = colors.outline.copy(alpha = .25f),
            unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent))
}

private fun progressLabel(value: MangaTrackingProgress): String =
    (value.status?.label ?: "未收藏") + " · " + value.chapters + " 话 · " + value.volumes + " 卷"
