package dev.mangaflow.ui

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
    if (platformKind != PlatformKind.ANDROID) return
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val runtimes = remember(context) { PanelRuntimeStore.get(context) }
    val runtimeStates by runtimes.states.collectAsStateWithLifecycle()
    val engine = runtimeStates[PanelRuntimeId.QUICKJS] ?: PanelRuntimeState()
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
    var busy by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var completedName by remember { mutableStateOf<String?>(null) }
    var showFormats by remember { mutableStateOf(false) }
    var showEngineDetails by remember { mutableStateOf(false) }
    var showPackageDetails by remember(prepared?.digest) { mutableStateOf(false) }
    val motion = LocalMotionEnabled.current
    val engineReady = engine.installed && !engine.downloading && !engine.pendingRemoval
    val engineReveal = animateFloatAsState(if (showEngineDetails) 1f else 0f,
        tween(if (motion) MangaMotion.Content else 0, easing = MangaMotion.EnterEasing), label = "source-engine-disclosure")

    fun operate(label: String, block: suspend () -> Unit) {
        if (busy != null || completedName != null) return
        focus.clearFocus(force = true)
        keyboard?.hide()
        failure = null
        busy = label
        activeJob = scope.launch {
            try {
                block()
            } catch (timeout: TimeoutCancellationException) {
                failure = "操作超时，请检查网络或稍后重试。"
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failure = error.message?.takeIf(String::isNotBlank) ?: "来源导入未完成，请重试。"
            } finally {
                busy = null
                activeJob = null
            }
        }
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
    }, backdrop = backdrop, containerColor = colors.surface.copy(alpha = .97f)) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.height * .84f)
            .imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (prepared == null) "导入第三方源" else "确认来源", Modifier.weight(1f),
                    fontSize = 16.sp, fontWeight = FontWeight.Medium)
                MotionIconButton(onClick = { activeJob?.cancel(); dismiss() }) {
                    Icon(Icons.Outlined.Close, "关闭来源导入", Modifier.size(20.dp))
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                failure?.let { message -> item(key = "failure") {
                    Text(message, fontSize = 12.sp, lineHeight = 17.sp, color = colors.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                } }
                if (busy != null) item(key = "busy") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp)
                        Text(busy.orEmpty(), fontSize = 12.sp, color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f))
                        MotionTextButton(onClick = { activeJob?.cancel() }) { Text("取消", fontSize = 11.sp) }
                    }
                }
                val bundle = prepared
                if (bundle == null) {
                    item(key = "formats") {
                        Column {
                            Text("JS 漫画源 · 支持源仓库或文件", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            MotionTextButton(onClick = { showFormats = !showFormats },
                                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                                Text(if (showFormats) "收起导入说明" else "支持格式与导入说明", fontSize = 11.sp)
                            }
                            AnimatedVisibility(showFormats,
                                enter = fadeIn(tween(if (motion) MangaMotion.Quick else 0)) +
                                    expandVertically(tween(if (motion) MangaMotion.Content else 0)),
                                exit = fadeOut(tween(if (motion) MangaMotion.Quick else 0)) +
                                    shrinkVertically(tween(if (motion) MangaMotion.Content else 0))) {
                                Text("可添加公开仓库，挑选需要的漫画源；读取目录不会运行或安装脚本。目前支持 Mangayomi 格式的 JS 漫画源，APK、Dart 和阅读规则书源暂不支持。",
                                    fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                    item(key = "import-mode") {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            MotionTextButton(enabled = busy == null, onClick = {
                                repositoryMode = true; catalog = null; candidates = emptyList(); candidateQuery = ""; failure = null
                            }) { Text("源仓库", fontSize = 12.sp,
                                fontWeight = if (repositoryMode) FontWeight.Medium else FontWeight.Normal,
                                color = if (repositoryMode) colors.onSurface else colors.onSurfaceVariant) }
                            MotionTextButton(enabled = busy == null, onClick = {
                                repositoryMode = false; catalog = null; candidates = emptyList(); candidateQuery = ""; failure = null
                            }) { Text("文件或脚本", fontSize = 12.sp,
                                fontWeight = if (!repositoryMode) FontWeight.Medium else FontWeight.Normal,
                                color = if (!repositoryMode) colors.onSurface else colors.onSurfaceVariant) }
                        }
                    }
                    if (repositoryMode && repositories.isNotEmpty()) {
                        item(key = "repositories-title") {
                            Text("已添加的仓库", fontSize = 11.sp, color = colors.onSurfaceVariant)
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
                                Text("填入公开 JS 仓库示例", fontSize = 11.sp)
                            }
                            Text("只保存你选择的仓库地址；移除仓库不影响已安装的源。",
                                fontSize = 10.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant)
                        }
                        }
                    }
                    if (entries.isNotEmpty()) item(key = "candidate-count") {
                        Text(if (catalog != null) "仓库 ${entries.size} 项 · ${entries.count { it.candidate != null }} 项 JS 漫画源可预览"
                            else "选择要导入的来源 · ${entries.size}", fontSize = 11.sp,
                            color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
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
                                role = Role.Button, onClickLabel = "添加${entry.name}", onClick = {
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
                                Text(entry.unavailableReason ?: "JS 漫画源 · 尚未运行，导入时校验",
                                    fontSize = 10.sp, lineHeight = 14.sp, color = colors.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (candidate != null) "添加" else "不兼容", fontSize = 11.sp, color = colors.onSurfaceVariant)
                        }
                    }
                } else {
                    item(key = "review") {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(bundle.metadata.optString("name"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            ImportDetail("网站", bundle.metadata.optString("baseUrl"))
                            ImportDetail("版本", listOf(bundle.metadata.optString("version"), bundle.metadata.optString("lang"))
                                .filter(String::isNotBlank).joinToString(" · ").ifBlank { "未提供" })
                            ImportDetail("来自", displayImportOrigin(bundle.origin))
                            if (installed.any { it.sourceId == bundle.sourceId && it.digest == bundle.digest }) {
                                Text("同一版本已安装，可重新启用；不会重复添加。", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            }
                            Text("脚本可以请求网络，请确认来源可信。", color = colors.onSurfaceVariant,
                                fontSize = 11.sp, lineHeight = 17.sp)
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
                                    Text("仅支持当前 JS 宿主；网页验证、APK、Dart 和阅读规则暂不兼容。目录识别不代表网站已验证可读，不支持的功能会明确报错。",
                                        color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp)
                                }
                            }
                        }
                    }
                    if (!engineReady || engine.error != null) item(key = "required-engine") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("导入此源需要脚本运行组件", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            ScriptEngineControl(runtimes, engine, busy == null, onFailure = { failure = it })
                        }
                    }
                    item(key = "install") {
                        MotionButton(enabled = busy == null && engineReady,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface),
                            onClick = {
                                operate("正在校验并安装…") {
                                    store.install(bundle)
                                    // The ViewModel installs the actual adapter before the registry publishes it.
                                    try {
                                        withTimeout(10_000L) {
                                            registry.entries.first { entries -> entries.any {
                                                it.isInstalled && it.source.id == bundle.sourceId &&
                                                    it.source.revision == bundle.digest
                                            } }
                                        }
                                    } catch (_: TimeoutCancellationException) {
                                        error("来源已保存，但尚未接入来源列表，请重新打开源管理重试。")
                                    }
                                    if (markAdult) registry.setAdult(bundle.sourceId, true)
                                    registry.restore(bundle.sourceId)
                                    completedName = bundle.metadata.optString("name")
                                    dismiss()
                                }
                            }) { Text("导入并启用", fontSize = 13.sp) }
                        MotionTextButton(enabled = busy == null, modifier = Modifier.fillMaxWidth(),
                            onClick = { prepared = null; failure = null }) { Text("重新选择", fontSize = 12.sp) }
                    }
                }
                if (bundle == null || engineReady && engine.error == null) item(key = "engine-maintenance") {
                    Column {
                        MotionTextButton(onClick = { showEngineDetails = !showEngineDetails },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                            Text(if (showEngineDetails) "收起运行组件" else "运行组件 · " +
                                (if (engineReady) "已就绪" else "导入时下载"), fontSize = 11.sp,
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
        }
    }
}

@Composable
private fun ImportDetail(label: String, value: String) {
    if (value.isBlank()) return
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(32.dp), color = colors.onSurfaceVariant, fontSize = 11.sp)
        Text(value, Modifier.weight(1f), color = colors.onSurface, fontSize = 12.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
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
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("脚本引擎 ${spec.version}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(when {
                    state.pendingRemoval -> "重启后完成移除"
                    state.downloading -> "下载中 · ${((state.progress ?: 0f) * 100).toInt()}%"
                    state.installed -> "已下载 · 仅在使用脚本源时加载"
                    else -> "首次使用下载本机架构组件"
                }, fontSize = 10.sp, color = colors.onSurfaceVariant)
            }
            when {
                state.downloading -> MotionTextButton(onClick = { store.cancel(PanelRuntimeId.QUICKJS) }) {
                    Text("取消下载", fontSize = 11.sp)
                }
                state.pendingRemoval -> Unit
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
private const val PUBLIC_JS_REPOSITORY = "https://raw.githubusercontent.com/entityJY/mangayomi-extensions-eJ/main/index.json"
