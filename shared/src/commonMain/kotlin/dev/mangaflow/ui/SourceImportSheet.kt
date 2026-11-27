package dev.mangaflow.ui

import dev.mangaflow.background.ApplicationOperations
import dev.mangaflow.background.ApplicationOperationPhase
import dev.mangaflow.background.UserOperationKeys
import dev.mangaflow.background.installSource

import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.platform.documentName
import dev.mangaflow.ui.platform.readDocumentText
import dev.mangaflow.ui.platform.releasePickedDocuments
import dev.mangaflow.ui.platform.rememberOpenDocumentPicker
import kotlinx.coroutines.IO

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.plugins.ScriptSourcePackage
import dev.mangaflow.data.plugins.SourcePluginCandidate
import dev.mangaflow.data.plugins.SourcePluginStore
import dev.mangaflow.data.plugins.SourceRepositoryCatalog
import dev.mangaflow.data.plugins.SourceRepositoryEntry
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeState
import dev.mangaflow.reader.panels.PanelRuntimeStore
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A preview is inert metadata. Only the explicit final action validates and installs source code. */
@Composable
internal fun SourceImportSheet(
    store: SourcePluginStore,
    registry: BodySourceRegistry,
    backdrop: HazeState?,
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
    markAdult: Boolean = false,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val operations = remember(context) { ApplicationOperations.get(context) }
    val operationStates by operations.state.collectAsStateWithLifecycle()
    val installOperation = remember(operationStates) { operations.find(UserOperationKeys.SOURCE_INSTALL) }
    var submittedInstallId by remember { mutableStateOf<String?>(null) }
    var rejectedInstallId by remember { mutableStateOf<String?>(null) }
    var installNotice by remember { mutableStateOf<ManagementNotice?>(null) }
    val runtimes = remember(context) { PanelRuntimeStore.get(context) }
    val runtimeStates by runtimes.states.collectAsStateWithLifecycle()
    val engine = runtimeStates[PanelRuntimeId.QUICKJS] ?: PanelRuntimeState()
    val engineBundled = runtimes.spec(PanelRuntimeId.QUICKJS).bundled
    val repositories by store.repositories.collectAsStateWithLifecycle()
    val installed by store.installed.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf("") }
    var repositoryMode by remember { mutableStateOf(true) }
    var catalog by remember { mutableStateOf<SourceRepositoryCatalog?>(null) }
    var candidates by remember { mutableStateOf<List<SourcePluginCandidate>>(emptyList()) }
    var candidateQuery by remember { mutableStateOf("") }
    val entries = remember(candidates, catalog) {
        (catalog?.entries ?: candidates.map { candidate -> SourceRepositoryEntry(
            candidate.metadata.optString("name"), candidate.metadata.optString("lang"),
            candidate.metadata.optString("version"), candidate.metadata.optString("baseUrl"), candidate,
        ) }).sortedBy { it.candidate == null }
    }
    val visibleEntries = remember(entries, candidateQuery) {
        val term = candidateQuery.trim()
        if (term.isEmpty()) entries else entries.filter { entry ->
            listOf(entry.name, entry.language, entry.siteUrl).any { it.contains(term, ignoreCase = true) }
        }
    }
    var prepared by remember { mutableStateOf<ScriptSourcePackage?>(null) }
    var previewBusy by remember { mutableStateOf<String?>(null) }
    var cancelPreviewRequested by remember { mutableStateOf(false) }
    val activeInstall = installOperation?.takeIf { it.active }
    val busy = previewBusy?.let { if (cancelPreviewRequested) "正在取消预览" else it } ?: activeInstall?.let {
        when {
            it.id == rejectedInstallId -> "另一个安装任务正在执行，当前来源尚未提交"
            it.phase == ApplicationOperationPhase.QUEUED -> "安装已排队，可关闭此页"
            else -> "正在后台安装，可关闭此页"
        }
    }
    var failure by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var completedName by remember { mutableStateOf<String?>(null) }
    var showFormats by remember { mutableStateOf(false) }
    var showEngineDetails by remember { mutableStateOf(false) }
    var showPackageDetails by remember(prepared?.digest) { mutableStateOf(false) }
    val motion = LocalMotionEnabled.current
    val engineReady = platformKind != PlatformKind.ANDROID || engineBundled ||
        engine.installed && !engine.downloading && !engine.pendingRemoval
    val engineReveal = animateFloatAsState(if (showEngineDetails) 1f else 0f,
        tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing), label = "source-engine-disclosure")

    fun operate(label: String, block: suspend () -> Unit) {
        if (busy != null || completedName != null) return
        focus.clearFocus(force = true)
        keyboard?.hide()
        failure = null
        installNotice = null
        cancelPreviewRequested = false
        previewBusy = label
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
            } catch (timeout: TimeoutCancellationException) {
                failure = "操作超时，请检查网络或稍后重试。"
            } catch (cancel: CancellationException) {
                if (cancelPreviewRequested) installNotice = ManagementNotice("预览已取消，可以重新选择来源", ManagementSignal.CANCELLED)
                throw cancel
            } catch (error: Exception) {
                failure = error.message?.takeIf(String::isNotBlank) ?: "来源导入未完成，请重试。"
            } finally {
                previewBusy = null
                cancelPreviewRequested = false
                activeJob = null
            }
        }
        activeJob = job.takeIf { it.isActive }
    }

    val filePicker = rememberOpenDocumentPicker { uri ->
        if (uri != null) operate("正在读取源文件…") {
            try {
                val (text, name) = withContext(Dispatchers.IO) {
                    val fileName = documentName(context, uri).ifBlank { "本地源文件" }
                    readDocumentText(context, uri, maxBytes = MAX_IMPORT_BYTES) to fileName
                }
                val preview = store.preview(text, "local:$name")
                require(preview.isNotEmpty()) { "文件没有可导入的 JS 漫画源。" }
                candidates = preview
                repositoryMode = false
                catalog = null
                candidateQuery = ""
                prepared = null
            } finally {
                releasePickedDocuments(listOf(uri))
            }
        }
    }
    fun loadRepository(value: String) {
        operate("正在读取仓库目录…") {
            val result = store.fetchRepository(value)
            catalog = result
            url = result.repository.url
            repositoryMode = true
            candidates = emptyList()
            candidateQuery = ""
            prepared = null
        }
    }
    val loadUrl = {
        if (url.isNotBlank()) {
            if (repositoryMode) loadRepository(url.trim())
            else operate("正在读取来源…") {
                val preview = store.fetchPreview(url.trim())
                require(preview.isNotEmpty()) { "这个链接没有可导入的 JS 漫画源。" }
                candidates = preview
                catalog = null
                candidateQuery = ""
                prepared = null
            }
        }
    }

    MotionBottomSheet(onDismissRequest = {
        activeJob?.cancel()
        onDismiss()
        completedName?.let(onImported)
    }, backdrop = backdrop, containerColor = colors.surface.copy(alpha = .97f), desktopWidth = 920.dp) { dismiss ->
        LaunchedEffect(installOperation?.id, installOperation?.phase) {
            val operation = installOperation ?: return@LaunchedEffect
            if (operation.active || (operation.id != submittedInstallId && operations.result<String>(operation.id) == null)) return@LaunchedEffect
            when (operation.phase) {
                ApplicationOperationPhase.SUCCEEDED -> operations.result<String>(operation.id)?.let { result ->
                    val name = result.await()
                    installNotice = ManagementNotice("$name 已安装并启用", ManagementSignal.COMPLETE)
                    operations.consumeResult(operation.id)
                    if (submittedInstallId == operation.id) {
                        completedName = name
                        dismiss()
                    }
                }
                ApplicationOperationPhase.FAILED, ApplicationOperationPhase.INTERRUPTED -> {
                    failure = operation.message ?: "安装未完成，请重新提交"
                    operations.consumeResult(operation.id)
                }
                ApplicationOperationPhase.CANCELLED -> {
                    failure = null
                    installNotice = ManagementNotice("安装已取消，可以重新提交", ManagementSignal.CANCELLED)
                    operations.consumeResult(operation.id)
                }
                else -> Unit
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.height * .84f)
            .imePadding().navigationBarsPadding()) {
            val desktop = platformKind == PlatformKind.DESKTOP
            val font = LocalMangaWindow.current.fontScale.coerceAtLeast(1f)
            val split = desktop && maxWidth >= 710.dp * font && maxHeight >= 290.dp * font
            val bodyHeight = minOf(maxHeight, 620.dp)
            val scrollActions = !split && bodyHeight < 285.dp * font
            val setupWidth = (maxWidth * .41f).coerceIn(278.dp, 384.dp)
            val bundle = prepared
            val setupList = rememberLazyListState()
            val catalogueList = rememberLazyListState()
            val compactList = rememberLazyListState()
            val reviewList = key(bundle?.digest) { rememberLazyListState() }
            LaunchedEffect(catalog, candidates) {
                if (entries.isNotEmpty() && bundle == null) {
                    if (split) catalogueList.scrollToItem(0)
                    else {
                        val repositoryRows = if (repositoryMode && repositories.isNotEmpty()) 1 + repositories.size else 0
                        // Mode, input and help precede the catalogue on a single-column window.
                        compactList.scrollToItem(3 + repositoryRows)
                    }
                }
            }
            val setup: LazyListScope.() -> Unit = {
                item(key = "import-mode") {
                    MotionTabs(if (repositoryMode) 0 else 1, listOf("源仓库", "文件或脚本"), onSelect = { index ->
                        val next = index == 0
                        if (busy == null && next != repositoryMode) {
                            repositoryMode = next
                            catalog = null
                            candidates = emptyList()
                            candidateQuery = ""
                            failure = null
                            installNotice = null
                        }
                    }, modifier = Modifier.fillMaxWidth().motionInputGate(busy == null),
                        treatment = MotionTabTreatment.Soft, role = Role.RadioButton, fullWidthIndicator = true,
                        controlHeight = 40.dp, track = colors.onSurface.copy(alpha = .05f), indicator = colors.surface)
                }
                item(key = "input") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!repositoryMode) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MotionTextButton(enabled = busy == null, onClick = {
                            focus.clearFocus(force = true); keyboard?.hide()
                            filePicker.launch(arrayOf("*/*"))
                        }) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("选择文件", fontSize = 12.sp)
                        }
                        Text(".js / .json", fontSize = 11.sp, color = colors.onSurfaceVariant)
                    }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                        .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Link, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                        BasicTextField(url, onValueChange = { url = it }, enabled = busy == null,
                            modifier = Modifier.weight(1f).semantics { contentDescription = if (repositoryMode) "仓库 index.json 的 HTTPS 地址" else "HTTPS 源文件或脚本链接" },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface, fontSize = 12.sp),
                            cursorBrush = SolidColor(colors.onSurface),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { loadUrl() }),
                            decorationBox = { input -> Box {
                                if (url.isBlank()) Text(if (repositoryMode) "仓库 index.json 的 HTTPS 地址" else "HTTPS 源文件或脚本链接",
                                    color = colors.onSurfaceVariant, fontSize = 12.sp)
                                input()
                            } })
                        MotionTextButton(enabled = busy == null && url.isNotBlank(), onClick = { loadUrl() }) {
                            Text(if (repositoryMode) "添加" else "读取", fontSize = 12.sp)
                        }
                    }
                    if (repositoryMode) {
                        MotionTextButton(enabled = busy == null, onClick = { url = PUBLIC_JS_REPOSITORY }) {
                            Text("填入 Venera 仓库示例", fontSize = 11.sp)
                        }
                        MotionTextButton(enabled = busy == null, onClick = {
                            url = "https://raw.githubusercontent.com/entityJY/mangayomi-extensions-eJ/main/index.json"
                        }) { Text("填入 Mangayomi 仓库示例", fontSize = 11.sp) }
                    }
                    }
                }
                if (repositoryMode && repositories.isNotEmpty()) {
                    item(key = "repositories-title") {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("已添加的仓库", style = MaterialTheme.typography.titleSmall)
                            Text("移除仓库会保留已经安装的来源。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    itemsIndexed(repositories, key = { _, repository -> "repository:${repository.url}" }) { _, repository ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(repository.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(repository.url, fontSize = 10.sp, color = colors.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            MotionTextButton(enabled = busy == null, onClick = { loadRepository(repository.url) }) {
                                Text(if (catalog?.repository?.url == repository.url) "刷新" else "打开", fontSize = 11.sp)
                            }
                            MotionTextButton(enabled = busy == null, onClick = {
                                operate("正在移除仓库…") {
                                    store.removeRepository(repository.url)
                                    if (catalog?.repository?.url == repository.url) { catalog = null; candidateQuery = "" }
                                }
                            }) { Text("移除", fontSize = 11.sp) }
                        }
                    }
                }
                item(key = "formats") {
                    Column {
                        MotionTextButton(onClick = { showFormats = !showFormats },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                            Text(if (showFormats) "收起导入说明" else "支持格式与导入说明", fontSize = 11.sp)
                        }
                        AnimatedVisibility(showFormats,
                            enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                                expandVertically(tween(if (motion) MangaMotion.Content else 0)),
                            exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                                shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
                            Text("可添加公开仓库，挑选需要的漫画源；读取目录不会运行或安装脚本。支持 Venera ComicSource 和 Mangayomi JS 漫画源，APK、Dart 和阅读规则书源暂不支持。",
                                fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
            val entriesList: LazyListScope.() -> Unit = {
                if (entries.isNotEmpty()) item(key = "candidate-count") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(catalog?.repository?.name?.takeIf(String::isNotBlank) ?: "选择来源",
                            style = MaterialTheme.typography.titleMedium)
                        Text("${entries.size} 项 · ${entries.count { it.candidate != null }} 项可预览",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                if (entries.size > 12) item(key = "filter-candidates") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                        .background(colors.onSurface.copy(alpha = .045f)).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, null, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                        BasicTextField(candidateQuery, onValueChange = { candidateQuery = it }, enabled = busy == null,
                            modifier = Modifier.weight(1f).semantics { contentDescription = "按名称、语言或站点筛选来源" },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, color = colors.onSurface),
                            cursorBrush = SolidColor(colors.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(force = true); keyboard?.hide() }),
                            decorationBox = { input -> Box {
                                if (candidateQuery.isBlank()) Text("查找名称、语言或站点", fontSize = 12.sp, color = colors.onSurfaceVariant)
                                input()
                            } })
                    }
                }
                if (entries.isNotEmpty() && visibleEntries.isEmpty()) item(key = "no-candidate-match") {
                    Text("没有匹配的来源", fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
                itemsIndexed(visibleEntries, key = { index, entry ->
                    "$index:${entry.candidate?.codeUrl.orEmpty()}:${entry.name}:${entry.language}"
                }) { _, entry ->
                    val candidate = entry.candidate
                    val selectable = busy == null && candidate != null
                    val interaction = remember(entry) { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = selectable, interactionSource = interaction, indication = null,
                            role = Role.Button, onClickLabel = "预览${entry.name}", onClick = {
                                if (candidate != null) operate("正在准备所选来源…") { prepared = store.prepare(candidate) }
                            }).motionPress(interaction, enabled = selectable, pressedScale = .99f)
                        .padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(entry.name.ifBlank { "未命名来源" }, fontSize = 13.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOf(entry.language, entry.version,
                                displayImportOrigin(entry.siteUrl)).filter(String::isNotBlank).joinToString(" · "),
                                fontSize = 11.sp, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            entry.unavailableReason?.let { reason ->
                                Text(reason, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        if (candidate != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = colors.primary)
                        else Text("不兼容", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
                if (entries.isEmpty() && (split || catalog != null)) item("awaiting-catalog") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(27.dp), tint = colors.onSurfaceVariant)
                        Text(if (catalog != null) "仓库内没有来源" else "待选择来源", style = MaterialTheme.typography.titleMedium)
                        Text(if (catalog != null) "可以刷新仓库，或打开其他仓库。"
                            else if (repositoryMode) "打开已添加的仓库，或输入仓库地址以查看目录。" else "选择本地文件，或输入脚本地址以读取内容。",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            val review: LazyListScope.() -> Unit = {
                if (bundle != null) {
                    item(key = "review") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!split) SourceImportIdentity(bundle)
                            Text("安装与启用", style = MaterialTheme.typography.titleMedium)
                            val previous = installed.firstOrNull { it.sourceId == bundle.sourceId }
                            Text(when {
                                previous == null -> "导入后将添加到来源列表，并立即启用。"
                                previous.digest == bundle.digest -> "本机已安装这一版本，可重新启用。"
                                else -> "安装后会替换本机这个来源的现有版本。"
                            }, style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(10.dp))
                                .padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Info, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                                Text("脚本可以请求网络，请确认来源可信。", style = MaterialTheme.typography.bodySmall)
                            }
                            MotionTextButton(onClick = { showPackageDetails = !showPackageDetails },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                                Text(if (showPackageDetails) "收起来源信息" else "兼容性与来源信息", fontSize = 11.sp)
                            }
                            AnimatedVisibility(showPackageDetails,
                                enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                                    expandVertically(tween(if (motion) MangaMotion.Content else 0)),
                                exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                                    shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ImportDetail("来源", bundle.origin)
                                    ImportDetail("指纹", bundle.digest.take(16))
                                    bundle.metadata.optJSONArray("hostRepairs")?.length()?.takeIf { it > 0 }?.let {
                                        ImportDetail("兼容修订", "$it 项，保留上游版本与原始摘要")
                                    }
                                    if (bundle.metadata.optBoolean("locallyModified", false))
                                        Text("本机修订会保留；更新时需要先合并修改。", fontSize = 11.sp,
                                            color = colors.onSurfaceVariant)
                                    Text("仅支持当前 JS 宿主；网页验证、APK、Dart 和阅读规则暂不兼容。目录识别不代表网站已验证可读，不支持的功能会明确报错。",
                                        color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                    }
                    if (platformKind == PlatformKind.ANDROID && (!engineReady || !engineBundled && engine.error != null)) item(key = "required-engine") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("导入此源需要脚本运行组件", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            ScriptEngineControl(runtimes, engine, busy == null, onFailure = { failure = it })
                        }
                    }
                }
                if (platformKind == PlatformKind.ANDROID && (bundle == null || engineReady && (engineBundled || engine.error == null))) item(key = "engine-maintenance") {
                    Column {
                        MotionTextButton(onClick = { showEngineDetails = !showEngineDetails },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                            Text(if (showEngineDetails) "收起运行组件" else "运行组件 · " +
                                (if (engineBundled) "已内置" else if (engineReady) "已就绪" else "导入时下载"), fontSize = 11.sp,
                                color = colors.onSurfaceVariant)
                        }
                        // Match SettingsDisclosure: hiding maintenance must not cancel its running operation.
                        Column(Modifier.fillMaxWidth().clipToBounds().motionInputGate(showEngineDetails)
                            .layout { measurable, constraints ->
                                if (engineReveal.value <= 0f) layout(constraints.minWidth, 0) {}
                                else {
                                    val body = measurable.measure(constraints.copy(minHeight = 0))
                                    layout(body.width, (body.height * engineReveal.value).toInt().coerceAtLeast(0)) {
                                        body.placeRelative(0, 0)
                                    }
                                }
                            }.graphicsLayer { alpha = engineReveal.value }) {
                            ScriptEngineControl(runtimes, engine, busy == null, onFailure = { failure = it })
                        }
                    }
                }
            }
            val installActions: @Composable () -> Unit = {
                if (bundle != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    MotionTextButton(onClick = { prepared = null; failure = null; installNotice = null }, enabled = busy == null) { Text("重新选择") }
                    MotionButton(onClick = {
                        val handle = operations.installSource(store, registry, bundle, markAdult)
                        failure = null
                        if (handle.accepted) {
                            installNotice = null
                            rejectedInstallId = null
                            submittedInstallId = handle.id
                        } else {
                            rejectedInstallId = handle.id
                            installNotice = ManagementNotice("已有安装任务，当前来源尚未提交", ManagementSignal.INFORMATION)
                        }
                    }, enabled = busy == null && engineReady) { Text(if (installOperation?.active == true) "正在安装" else "导入并启用") }
                }
            }
            Column(Modifier.fillMaxWidth().then(if (split) Modifier.height(bodyHeight) else Modifier.heightIn(max = bodyHeight))) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    PreferenceHeading(if (prepared == null) "导入第三方源" else "确认来源", Modifier.weight(1f))
                    if (prepared == null) DownloadRouteControl(DownloadCategory.SOURCE_PACKAGES, enabled = busy == null, backdrop = backdrop)
                    MotionIconButton(onClick = { activeJob?.cancel(); dismiss() }, tooltip = "关闭来源导入") {
                        Icon(Icons.Outlined.Close, "关闭来源导入", Modifier.size(20.dp))
                    }
                }
                val status = when {
                    busy != null -> ManagementNotice(busy, if (previewBusy == null && activeInstall?.phase == ApplicationOperationPhase.QUEUED)
                        ManagementSignal.QUEUED else ManagementSignal.WORKING)
                    failure != null -> ManagementNotice(failure.orEmpty(), ManagementSignal.ATTENTION)
                    else -> installNotice
                }
                ManagementStatusRow(status, Modifier.padding(horizontal = 18.dp, vertical = 3.dp)) {
                    if (previewBusy != null) MotionTextButton(enabled = !cancelPreviewRequested, onClick = {
                        activeJob?.takeIf { it.isActive }?.let { job ->
                            cancelPreviewRequested = true
                            job.cancel()
                        }
                    }) { Text("取消") }
                    else if (activeInstall?.cancellable == true && activeInstall.id != rejectedInstallId) MotionTextButton(
                        onClick = { operations.cancel(activeInstall.id) }) { Text("取消") }
                }
                if (split) Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.width(setupWidth).fillMaxHeight().background(colors.surfaceContainerLow)) {
                        if (bundle == null) SourceImportList(setupList, true, setup)
                        else SourceImportList(key(bundle.digest) { rememberLazyListState() }, true) {
                            item("identity") { SourceImportIdentity(bundle) }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (bundle == null) SourceImportList(catalogueList, true, entriesList)
                        else SourceImportList(reviewList, true, review)
                    }
                } else Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    SourceImportList(if (bundle == null) compactList else reviewList, desktop) {
                        if (bundle == null) { setup(); entriesList() }
                        review()
                        if (scrollActions && bundle != null) item("install-actions") { installActions() }
                    }
                }
                if (!scrollActions) installActions()
            }
        }
    }
}

@Composable
private fun SourceImportList(state: LazyListState, desktop: Boolean, content: LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxWidth().padding(end = if (desktop) 8.dp else 0.dp), state = state,
            contentPadding = PaddingValues(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        if (desktop) DesktopVerticalScrollbar(state, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun SourceImportIdentity(bundle: ScriptSourcePackage) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Description, null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.primary)
        Text(bundle.metadata.optString("name").ifBlank { bundle.sourceId }, style = MaterialTheme.typography.titleLarge)
        ImportDetail("网站", bundle.metadata.optString("baseUrl"))
        ImportDetail("版本", listOf(bundle.metadata.optString("version"), bundle.metadata.optString("lang"))
            .filter(String::isNotBlank).joinToString(" · ").ifBlank { "未提供" })
        ImportDetail("来自", displayImportOrigin(bundle.origin))
    }
}

@Composable
private fun ImportDetail(label: String, value: String) {
    if (value.isBlank()) return
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(42.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)),
            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(1f), color = colors.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScriptEngineControl(
    store: PanelRuntimeStore,
    state: PanelRuntimeState,
    enabled: Boolean,
    onFailure: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val spec = store.spec(PanelRuntimeId.QUICKJS)
    var busy by remember { mutableStateOf(false) }
    fun change(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { operation() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { onFailure(failure.message ?: "脚本引擎操作失败，请重试。") }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (!spec.bundled)
            DownloadRouteControl(DownloadCategory.COMPONENTS, enabled = enabled && !busy && !state.downloading)
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("脚本引擎 ${spec.version}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(when {
                    spec.bundled && state.pendingRemoval -> "已内置 · 下载副本将在重启后移除"
                    spec.bundled -> "已内置 · 无需下载，仅在使用脚本源时加载"
                    state.pendingRemoval -> "重启后完成移除"
                    state.downloading -> state.progress?.takeIf { it.isFinite() && it in 0f..1f }
                        ?.let { "下载中 · ${(it * 100).toInt()}%" } ?: "正在接收组件"
                    state.installed -> "已下载 · 仅在使用脚本源时加载"
                    else -> "首次使用下载本机架构组件"
                }, fontSize = 10.sp, color = colors.onSurfaceVariant)
            }
            when {
                state.downloading -> MotionTextButton(onClick = { store.cancel(PanelRuntimeId.QUICKJS) }) {
                    Text("取消下载", fontSize = 11.sp)
                }
                state.pendingRemoval -> Unit
                spec.bundled -> {
                    if (state.installed || state.requiresUpdate || state.bytes > 0) {
                        MotionTextButton(enabled = enabled && !busy,
                            onClick = { change { store.remove(PanelRuntimeId.QUICKJS) } }) {
                            Text("清理下载副本", fontSize = 11.sp)
                        }
                    }
                }
                state.installed -> MotionTextButton(enabled = enabled && !busy,
                    onClick = { change { store.remove(PanelRuntimeId.QUICKJS) } }) {
                    Text("移除", fontSize = 11.sp)
                }
                else -> MotionTextButton(enabled = enabled && !busy, onClick = {
                    change { store.download(PanelRuntimeId.QUICKJS) }
                }) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (state.error != null) "重试" else "下载", fontSize = 11.sp)
                }
            }
        }
        state.error?.let {
            Text(it, fontSize = 11.sp, lineHeight = 15.sp, color = colors.error,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun displayImportOrigin(value: String): String =
    value.toHttpUrlOrNull()?.host?.takeIf(String::isNotBlank) ?: value

private const val MAX_IMPORT_BYTES = 2 * 1024 * 1024
// Public upstream metadata/script format was read; this is not an endorsed or runtime-verified catalogue.
private const val PUBLIC_JS_REPOSITORY = "https://raw.githubusercontent.com/venera-app/venera-configs/main/index.json"
