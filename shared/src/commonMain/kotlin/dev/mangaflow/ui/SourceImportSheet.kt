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
import dev.mangaflow.reader.panels.PanelRuntimeId
import dev.mangaflow.reader.panels.PanelRuntimeState
import dev.mangaflow.reader.panels.PanelRuntimeStore
import dev.mangaflow.storage.BodySourceRegistry
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
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
    var url by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<SourcePluginCandidate>>(emptyList()) }
    var candidateQuery by remember { mutableStateOf("") }
    val visibleCandidates = remember(candidates, candidateQuery) {
        val term = candidateQuery.trim()
        if (term.isEmpty()) candidates else candidates.filter { candidate ->
            listOf("name", "lang", "baseUrl").any { candidate.metadata.optString(it).contains(term, ignoreCase = true) }
        }
    }
    var prepared by remember { mutableStateOf<ScriptSourcePackage?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var completedName by remember { mutableStateOf<String?>(null) }

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
                candidateQuery = ""
                prepared = null
            } finally {
                releasePickedDocuments(listOf(uri))
            }
        }
    }
    val loadUrl = {
        if (url.isNotBlank()) operate("正在读取来源…") {
            val preview = store.fetchPreview(url.trim())
            require(preview.isNotEmpty()) { "这个链接没有可导入的 JS 漫画源。" }
            candidates = preview
            candidateQuery = ""
            prepared = null
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
                item(key = "engine") {
                    ScriptEngineControl(runtimes, engine, busy == null,
                        onFailure = { failure = it })
                }
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
                        Text("支持 Mangayomi 风格 JS 漫画源与 JS 源库索引；单文件不超过 2 MiB，总源码不超过 8 MiB，最多 64 个源。暂不兼容 APK 扩展与阅读规则书源。",
                            fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
                    }
                    item(key = "input") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                            .background(colors.onSurface.copy(alpha = .045f)).padding(start = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Link, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                            BasicTextField(url, onValueChange = { url = it }, enabled = busy == null,
                                modifier = Modifier.weight(1f).semantics { contentDescription = "HTTPS 源文件或源库链接" },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface, fontSize = 12.sp),
                                cursorBrush = SolidColor(colors.onSurface),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { loadUrl() }),
                                decorationBox = { input -> Box {
                                    if (url.isBlank()) Text("HTTPS 源文件或源库链接", color = colors.onSurfaceVariant, fontSize = 12.sp)
                                    input()
                                } })
                            MotionTextButton(enabled = busy == null && url.isNotBlank(), onClick = { loadUrl() }) {
                                Text("读取", fontSize = 12.sp)
                            }
                        }
                        }
                    }
                    if (candidates.isNotEmpty()) item(key = "candidate-count") {
                        Text("选择要导入的来源 · ${candidates.size}", fontSize = 11.sp,
                            color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (candidates.size > 12) item(key = "filter-candidates") {
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
                    if (candidates.isNotEmpty() && visibleCandidates.isEmpty()) item(key = "no-candidate-match") {
                        Text("没有匹配的来源", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                    itemsIndexed(visibleCandidates, key = { index, candidate ->
                        "$index:${candidate.codeUrl.orEmpty()}:${candidate.metadata.optString("name")}"
                    }) { _, candidate ->
                        val metadata = candidate.metadata
                        val interaction = remember(candidate) { MutableInteractionSource() }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = busy == null, interactionSource = interaction, indication = null,
                                role = Role.Button, onClickLabel = "预览${metadata.optString("name")}", onClick = {
                                    operate("正在准备所选来源…") { prepared = store.prepare(candidate) }
                                }).motionPress(interaction, enabled = busy == null, pressedScale = .99f)
                            .padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(19.dp), tint = colors.onSurfaceVariant)
                            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(metadata.optString("name").ifBlank { "未命名来源" }, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(metadata.optString("lang"), metadata.optString("version"),
                                    displayImportOrigin(metadata.optString("baseUrl"))).filter(String::isNotBlank).joinToString(" · "),
                                    fontSize = 11.sp, color = colors.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("预览", fontSize = 11.sp, color = colors.onSurfaceVariant)
                        }
                    }
                } else {
                    item(key = "review") {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(bundle.metadata.optString("name"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            ImportDetail("网站", bundle.metadata.optString("baseUrl"))
                            ImportDetail("版本", bundle.metadata.optString("version").ifBlank { "未提供" })
                            ImportDetail("语言", bundle.metadata.optString("lang"))
                            ImportDetail("来自", bundle.origin)
                            ImportDetail("指纹", bundle.digest.take(16))
                            Text("脚本可以请求网络。确认来源可信后再导入；不支持的接口会明确报错。",
                                color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp)
                        }
                    }
                    item(key = "install") {
                        MotionButton(enabled = busy == null && engine.installed && !engine.downloading && !engine.pendingRemoval,
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
                                    registry.restore(bundle.sourceId)
                                    completedName = bundle.metadata.optString("name")
                                    dismiss()
                                }
                            }) { Text("导入并启用", fontSize = 13.sp) }
                        MotionTextButton(enabled = busy == null, modifier = Modifier.fillMaxWidth(),
                            onClick = { prepared = null; failure = null }) { Text("重新选择", fontSize = 12.sp) }
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
