package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.SourceManagementState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.MotionTabs
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberFeelFeedback
import kotlinx.coroutines.launch

/** Same retained sheet, quiet rows and input composition as the existing author/tag workflows. */
@Composable
internal fun UnifiedSourceManager(
    state: SourceManagementState,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onSearch: (String, String) -> Unit,
    onAttach: (Manga, Boolean, String) -> Unit,
    onDetach: (Manga) -> Unit,
    onPrefer: (String) -> Unit,
    onRetryAll: () -> Unit = {},
    onChooseDomain: (String) -> Unit = {},
    onChooseSource: (String) -> Unit = {},
) {
    var query by remember(state.work.id) { mutableStateOf(state.query) }
    var source by remember(state.work.id) { mutableStateOf(state.sourceId) }
    var manual by remember(state.work.id) { mutableStateOf(false) }
    var expandedSource by remember(state.work.id) { mutableStateOf<String?>(null) }
    var requestedSearch by remember(state.work.id) { mutableStateOf<Pair<String, String>?>(null) }
    var domainChanged by remember(state.work.id) { mutableStateOf(false) }
    var candidate by remember(state.work.id, state.candidates) { mutableStateOf<Manga?>(null) }
    var sameSystem by remember(candidate?.id) { mutableStateOf(false) }
    var edition by remember(candidate?.id) { mutableStateOf("连载版") }
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val feedback = rememberFeelFeedback()
    val maxHeight = LocalMangaWindow.current.height * .78f
    val associated = (listOf(state.work) + state.linked).distinctBy { it.id }
    val overview = remember(state.discovery, associated, state.sources, state.scanning) {
        val rows = linkedMapOf<String, SourceOverviewEntry>()
        state.discovery.forEach { result ->
            rows[result.sourceId] = SourceOverviewEntry(result.sourceId, result.sourceName,
                result.status.name, result.chapters, result.message, result.candidates)
        }
        associated.forEach { manga ->
            if (manga.sourceId !in rows) rows[manga.sourceId] =
                SourceOverviewEntry(manga.sourceId, sourceDisplayName(manga.sourceId), "LINKED")
        }
        state.sources.forEach { (id, name) ->
            if (id !in rows) rows[id] = SourceOverviewEntry(id, name,
                if (state.scanning) "SEARCHING" else "NOT_CHECKED")
        }
        rows.values.toList()
    }
    val checking = state.scanning || overview.any { it.status == "SEARCHING" }
    val selectedOverview = overview.firstOrNull { it.id == source }
    val matchesQuery = state.sourceId == source && state.query.trim() == query.trim()
    val searched = requestedSearch == (source to query.trim()) && matchesQuery && !domainChanged
    val results = (if (searched) state.candidates else if (!domainChanged && query.trim() == state.work.title.trim())
        selectedOverview?.candidates.orEmpty() else emptyList()).filter { result ->
        associated.none { it.id == result.id }
    }.distinctBy { it.id }
    val manualError = if (!domainChanged) state.error?.takeIf {
        it.isNotBlank() && (matchesQuery || candidate != null)
    } ?: selectedOverview?.takeIf { !searched && it.status == "FAILED" }?.message?.takeIf(String::isNotBlank)
        else null
    val search: () -> Unit = {
        if (source.isNotBlank() && query.isNotBlank() && !state.connecting && !state.loading) {
            keyboard?.hide(); focus.clearFocus()
            candidate = null
            domainChanged = false
            requestedSearch = source to query.trim()
            onSearch(source, query)
        }
    }
    LaunchedEffect(state.linked) {
        if (state.linked.any { it.id == candidate?.id }) {
            expandedSource = candidate?.sourceId
            candidate = null
            manual = false
        }
    }
    LaunchedEffect(state.sourceDomain, state.sourceId, state.query, state.loading) {
        // The domain callback may explicitly re-run the selected source search.
        // Accept only that matching request, never candidates from the previous line.
        if (domainChanged && state.loading && state.sourceId == source && state.query.trim() == query.trim()) {
            requestedSearch = source to query.trim()
            domainChanged = false
        }
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide(); focus.clearFocus() } }
    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop,
        containerColor = colors.surface.copy(alpha = .97f)) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("正文来源", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(state.work.title, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                MotionIconButton(onClick = { keyboard?.hide(); focus.clearFocus(); dismiss() }) {
                    Icon(Icons.Outlined.Close, "关闭正文来源", Modifier.size(20.dp))
                }
            }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(), state = list,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item(key = "source-overview") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (checking) MotionBusyIndicator(Modifier.size(12.dp), strokeWidth = 1.2.dp)
                        Text(if (checking) "正在自动检查来源" else
                            "已关联 ${associated.map { it.sourceId }.distinct().size} 个来源",
                            fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                }
                items(overview, key = { "source:" + it.id }) { row ->
                    val linked = associated.filter { it.sourceId == row.id }
                    val expanded = expandedSource == row.id && linked.isNotEmpty()
                    val interaction = remember(row.id) { MutableInteractionSource() }
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (expanded) colors.onSurface.copy(alpha = .04f) else colors.surface.copy(alpha = 0f))
                            .clickable(interactionSource = interaction, indication = null,
                                enabled = !state.connecting && row.status != "DISABLED") {
                                if (row.id == state.work.sourceId && row.status == "FAILED") onRetryAll()
                                else if (linked.isNotEmpty() && row.status !in setOf("FAILED", "NEEDS_CONFIRMATION"))
                                    expandedSource = if (expanded) null else row.id
                                else {
                                    source = row.id; manual = true; candidate = null
                                    requestedSearch = null; domainChanged = false
                                    expandedSource = row.id.takeIf { linked.isNotEmpty() }
                                    onChooseSource(row.id)
                                    scope.launch { list.animateScrollToItem((overview.size + 1)
                                        .coerceAtMost((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))) }
                                }
                            }.motionPress(interaction, enabled = !state.connecting && row.status != "DISABLED")
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            ChapterSourceLogo(row.id, row.name, Modifier.size(21.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(row.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    if (state.preferredSource == row.id) Icon(Icons.Outlined.Check, "作品优先来源",
                                        Modifier.size(12.dp), tint = colors.onSurfaceVariant)
                                }
                                val description = when {
                                    row.status == "LINKED" && row.chapters > 0 -> "${row.chapters} 话"
                                    row.status == "NEEDS_CONFIRMATION" && row.candidates.isNotEmpty() ->
                                        "${row.candidates.size} 个候选版本"
                                    row.message.isNotBlank() && row.message != "尚未检查" -> row.message
                                    else -> ""
                                }
                                if (description.isNotBlank()) Text(description, fontSize = 11.sp,
                                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (row.status == "SEARCHING") MotionBusyIndicator(Modifier.size(13.dp),
                                strokeWidth = 1.3.dp, color = colors.onSurfaceVariant)
                            else Text(if (row.message == "尚未检查") "尚未检查" else sourceStatusLabel(row.status), fontSize = 11.sp,
                                color = when (row.status) {
                                    "FAILED" -> colors.error
                                    "NEEDS_CONFIRMATION" -> colors.primary
                                    else -> colors.onSurfaceVariant
                                })
                        }
                        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 28.dp)) {
                            MotionTextButton(enabled = !state.connecting && state.preferredSource != row.id,
                                onClick = { onPrefer(row.id) }) {
                                Text(if (state.preferredSource == row.id) "已优先使用" else "优先使用", fontSize = 12.sp)
                            }
                            linked.filter { it.id != state.work.id }.forEach { manga ->
                                MotionTextButton(enabled = !state.connecting, onClick = { onDetach(manga) }) {
                                    Text(if (linked.size > 1) "取消关联：${manga.title}" else "取消关联，保留历史和下载",
                                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                item(key = "source-overview-actions") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        MotionTextButton(enabled = !checking && !state.connecting, onClick = onRetryAll) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (overview.any { it.status == "FAILED" }) "重试检查" else "重新检查", fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        MotionTextButton(enabled = state.sources.isNotEmpty() && !state.connecting,
                            onClick = {
                                manual = !manual; candidate = null
                                if (manual) onChooseSource(source)
                            }) {
                            Icon(Icons.Outlined.Search, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(if (manual) "收起查找" else "手动查找", fontSize = 12.sp)
                        }
                    }
                }
                if (manual) {
                item(key = "add-title") {
                    Text("手动查找版本", fontSize = 12.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    if (state.sources.size > 1) MotionTabs(selected = state.sources.indexOfFirst { it.first == source }.coerceAtLeast(0),
                        labels = state.sources.map { it.second }, modifier = Modifier.fillMaxWidth(),
                        controlHeight = 38.dp, fontSize = 12.sp, treatment = MotionTabTreatment.Soft,
                        indicator = colors.onSurface.copy(alpha = .065f), onSelect = { index ->
                            if (!state.connecting && !state.loading) {
                                source = state.sources[index].first
                                candidate = null
                                requestedSearch = null
                                domainChanged = false
                                onChooseSource(source)
                            }
                        })
                    if (source == "komiic" && state.domainOptions.size > 1) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("线路", color = colors.onSurfaceVariant, fontSize = 11.sp,
                                modifier = Modifier.padding(end = 8.dp))
                            MotionTabs(selected = state.domainOptions.indexOf(state.sourceDomain).coerceAtLeast(0),
                                labels = state.domainOptions.map { it.removePrefix("https://").trimEnd('/') },
                                modifier = Modifier.weight(1f), controlHeight = 32.dp, fontSize = 11.sp,
                                treatment = MotionTabTreatment.Soft,
                                indicator = colors.onSurface.copy(alpha = .065f), onSelect = { index ->
                                    if (!state.connecting && !state.loading) {
                                        candidate = null; requestedSearch = null; domainChanged = true
                                        onChooseDomain(state.domainOptions[index])
                                    }
                                })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // The panel already owns frosting; an inner input must not resample detail text.
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                        .background(colors.onSurface.copy(alpha = .045f)).padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        BasicTextField(query, onValueChange = { query = it; candidate = null },
                            modifier = Modifier.weight(1f).semantics { contentDescription = "搜索所选正文来源的作品" },
                            enabled = !state.connecting, singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { search() }),
                            decorationBox = { inner ->
                                Box { if (query.isBlank()) Text("作品名或别名", color = colors.onSurfaceVariant); inner() }
                            })
                        MotionIconButton(enabled = query.isNotBlank() && !state.loading && !state.connecting,
                            onClick = search) { Icon(Icons.Outlined.Search, "搜索这个来源", Modifier.size(19.dp)) }
                    }
                }
                if (state.loading || state.connecting) item(key = "source-busy") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        MotionBusyIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
                        Text(if (state.connecting) "正在读取并关联目录…" else "正在搜索…",
                            color = colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                manualError?.let { error -> item(key = "source-error") {
                    Text(error, color = colors.error, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(vertical = 8.dp))
                    if (!state.connecting) MotionTextButton(onClick = search) { Text("重试搜索") }
                } }
                if (searched && !state.loading && !state.connecting && manualError == null && results.isEmpty())
                    item(key = "manual-empty") {
                        Text("这个名称未匹配到作品，可换别名再试。", color = colors.onSurfaceVariant,
                            fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                    }
                items(candidate?.let { listOf(it) } ?: results, key = { "candidate:" + it.id }) { manga ->
                    val selected = candidate?.id == manga.id
                    val interaction = remember(manga.id) { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.surfaceVariant.copy(alpha = .45f) else colors.surface.copy(alpha = 0f))
                        .clickable(interactionSource = interaction, indication = null,
                            enabled = !state.connecting && !state.loading, role = Role.Button,
                            onClickLabel = "选择这个版本") {
                            if (!selected) feedback.perform(FeelEvent.Selection)
                            keyboard?.hide(); focus.clearFocus(); candidate = manga
                        }.motionPress(interaction, enabled = !state.connecting && !state.loading)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(manga.coverUrl, null, Modifier.size(width = 36.dp, height = 48.dp)
                            .clip(RoundedCornerShape(5.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(manga.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (manga.authors.isNotEmpty()) Text(manga.authors.joinToString(" · "), color = colors.onSurfaceVariant,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (selected) Icon(Icons.Outlined.Check, "已选择", Modifier.size(18.dp))
                    }
                }
                candidate?.let { chosen -> item(key = "source-confirm:" + chosen.id) {
                    Text("确认是同一作品后关联；原有历史和下载保留。", fontSize = 12.sp,
                        color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    Row {
                        listOf("连载版", "单行本").forEach { value ->
                            MotionTextButton(enabled = !state.connecting, onClick = { edition = value }) {
                                Text(value, color = if (edition == value) colors.onSurface else colors.onSurfaceVariant)
                            }
                        }
                    }
                    MotionTextButton(enabled = !state.connecting, onClick = { sameSystem = !sameSystem }) {
                        if (sameSystem) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
                        Text(if (sameSystem) "已确认同一章节体系：合并对应话号" else "先保留独立目录；点此确认同一章节体系",
                            fontSize = 12.sp)
                    }
                    MotionTextButton(enabled = !state.connecting, onClick = { onAttach(chosen, sameSystem, edition) }) {
                        Icon(Icons.Outlined.Link, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("关联这部作品", fontWeight = FontWeight.Medium)
                    }
                    MotionTextButton(enabled = !state.connecting, onClick = { candidate = null }) {
                        Text("返回搜索结果", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                } }
                }
                item(key = "source-bottom") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

private data class SourceOverviewEntry(
    val id: String, val name: String, val status: String,
    val chapters: Int = 0, val message: String = "", val candidates: List<Manga> = emptyList(),
)

private fun sourceStatusLabel(status: String): String = when (status) {
    "SEARCHING" -> "检查中"
    "LINKED" -> "已关联"
    "NO_MATCH" -> "未匹配"
    "NEEDS_CONFIRMATION" -> "待确认"
    "FAILED" -> "检查失败"
    "DISABLED" -> "已停用"
    else -> "尚未检查"
}
