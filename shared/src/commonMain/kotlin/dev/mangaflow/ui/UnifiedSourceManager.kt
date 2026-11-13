package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import dev.mangaflow.platform.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import dev.mangaflow.ui.motion.BackHandler
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.semantics.stateDescription
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
import dev.mangaflow.ui.motion.MotionTabContent
import dev.mangaflow.ui.motion.MotionTabTreatment
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberFeelFeedback

/** One source overview; uncertain matches open only that source's directly selectable candidates. */
@Composable
internal fun UnifiedSourceManager(
    state: SourceManagementState,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onSearch: (String, String) -> Unit,
    onAttach: (Manga) -> Unit,
    onRetryAll: () -> Unit = {},
    onChooseDomain: (String) -> Unit = {},
    onChooseSource: (String) -> Unit = {},
) {
    var sourcePage by remember(state.work.id) { mutableStateOf<String?>(null) }
    var source by remember(state.work.id) { mutableStateOf(state.sourceId) }
    var query by remember(state.work.id) { mutableStateOf(state.query) }
    var manual by remember(state.work.id) { mutableStateOf(false) }
    var requestedSearch by remember(state.work.id) { mutableStateOf<Pair<String, String>?>(null) }
    var domainChanged by remember(state.work.id) { mutableStateOf(false) }
    var attaching by remember(state.work.id) { mutableStateOf<Manga?>(null) }
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val feedback = rememberFeelFeedback()
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
        rows.values.sortedBy { if (it.status == "NEEDS_CONFIRMATION") 0 else 1 }
    }
    val checking = state.scanning || overview.any { it.status == "SEARCHING" }
    val pendingCount = overview.count { it.status == "NEEDS_CONFIRMATION" }
    val selectedOverview = overview.firstOrNull { it.id == source }
    val matchesQuery = state.sourceId == source && state.query.trim() == query.trim()
    val searched = requestedSearch == (source to query.trim()) && matchesQuery && !domainChanged
    val results = (if (searched) state.candidates else if (!domainChanged && query.trim() == state.work.title.trim())
        selectedOverview?.candidates.orEmpty() else emptyList()).filter { result ->
        associated.none { it.id == result.id }
    }.distinctBy { it.id }
    val currentError = if (!domainChanged) state.error?.takeIf {
        it.isNotBlank() && (matchesQuery || attaching != null)
    } ?: selectedOverview?.takeIf { !searched && it.status == "FAILED" }?.message?.takeIf(String::isNotBlank)
        else null
    val busy = state.loading || state.connecting
    val search: () -> Unit = {
        if (source.isNotBlank() && query.isNotBlank() && !busy) {
            keyboard?.hide(); focus.clearFocus()
            domainChanged = false
            requestedSearch = source to query.trim()
            onSearch(source, query)
        }
    }
    val showOverview: () -> Unit = {
        if (!state.connecting) {
            keyboard?.hide(); focus.clearFocus()
            sourcePage = null; manual = false; attaching = null
        }
    }
    val openSource: (String, Boolean) -> Unit = { id, needsSearch ->
        source = id; sourcePage = id; manual = needsSearch
        query = state.work.title; requestedSearch = null; domainChanged = false; attaching = null
        onChooseSource(id)
    }
    LaunchedEffect(state.linked, attaching?.id) {
        if (attaching != null && state.linked.any { it.id == attaching?.id }) {
            sourcePage = null; manual = false; attaching = null
        }
    }
    LaunchedEffect(state.sourceDomain, state.sourceId, state.query, state.loading) {
        if (domainChanged && state.loading && state.sourceId == source && state.query.trim() == query.trim()) {
            requestedSearch = source to query.trim()
            domainChanged = false
        }
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide(); focus.clearFocus() } }
    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop,
        containerColor = colors.surface.copy(alpha = .97f)) { dismiss ->
        BackHandler(enabled = sourcePage != null && !state.connecting, onBack = showOverview)
        Column(Modifier.fillMaxWidth().heightIn(max = LocalMangaWindow.current.height * .72f)) {
            Row(Modifier.fillMaxWidth().padding(start = if (sourcePage == null) 20.dp else 4.dp, end = 8.dp)
                .heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                if (sourcePage != null) MotionIconButton(enabled = !state.connecting, onClick = showOverview) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回来源列表", Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (sourcePage == null) "来源" else sourceDisplayName(source),
                        fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(state.work.title, color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                if (sourcePage == null && overview.any { it.status == "FAILED" })
                    MotionIconButton(enabled = !checking && !state.connecting, onClick = onRetryAll) {
                        Icon(Icons.Outlined.Refresh, "重试失败的来源检查", Modifier.size(19.dp))
                    }
                MotionIconButton(onClick = { keyboard?.hide(); focus.clearFocus(); dismiss() }) {
                    Icon(Icons.Outlined.Close, "关闭正文来源", Modifier.size(20.dp))
                }
            }
            key(state.work.id) {
            MotionTabContent(selected = sourcePage ?: "overview",
                contentKey = { it }, order = { if (it == "overview") 0 else 1 },
                fillContent = false, soft = true,
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) { page ->
                val pageActive = page == (sourcePage ?: "overview")
                LazyColumn(Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (page == "overview") {
                        item(key = "summary") {
                            Row(Modifier.fillMaxWidth().heightIn(min = 28.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (checking) MotionBusyIndicator(Modifier.size(12.dp), strokeWidth = 1.2.dp)
                                Text(if (pendingCount > 0) "待确认 $pendingCount" else if (checking) "检查中" else
                                    "${associated.map { it.sourceId }.distinct().size} 源",
                                    fontSize = 12.sp,
                                    color = if (pendingCount > 0) colors.primary else colors.onSurfaceVariant)
                            }
                        }
                        items(overview, key = { "source:" + it.id }) { row ->
                            val canChoose = row.status !in setOf("LINKED", "SEARCHING", "DISABLED") &&
                                !state.connecting && pageActive
                            val interaction = remember(row.id) { MutableInteractionSource() }
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(interactionSource = interaction, indication = null, enabled = canChoose,
                                    role = Role.Button, onClickLabel = if (row.status == "NEEDS_CONFIRMATION")
                                        "查看${row.name}的待确认版本" else "查找${row.name}的版本") {
                                    if (row.id == state.work.sourceId) onRetryAll()
                                    else {
                                        feedback.perform(FeelEvent.Selection)
                                        openSource(row.id, row.status != "NEEDS_CONFIRMATION")
                                    }
                                }.motionPress(interaction, enabled = canChoose).padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                ChapterSourceLogo(row.id, row.name, Modifier.size(21.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(row.name, Modifier.weight(1f), fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(8.dp))
                                Row(Modifier.widthIn(max = 140.dp).semantics(mergeDescendants = true) {
                                    stateDescription = listOfNotNull(
                                        "${row.chapters}话".takeIf { row.chapters > 0 },
                                        "${row.candidates.size}个候选".takeIf { row.status == "NEEDS_CONFIRMATION" },
                                        sourceStatusLabel(row.status),
                                        row.message.takeIf { row.status == "FAILED" && it.isNotBlank() },
                                    ).joinToString("，")
                                }, horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    val count = when {
                                        row.status == "LINKED" && row.chapters > 0 -> "${row.chapters}话"
                                        row.status == "NEEDS_CONFIRMATION" -> "${row.candidates.size}个"
                                        else -> ""
                                    }
                                    if (count.isNotBlank()) Text(count, fontSize = 11.sp,
                                        color = colors.onSurfaceVariant, maxLines = 1)
                                    if (row.status == "SEARCHING") MotionBusyIndicator(Modifier.size(13.dp),
                                        strokeWidth = 1.3.dp, color = colors.onSurfaceVariant)
                                    else Text(if (row.message == "尚未检查") "尚未检查" else sourceStatusLabel(row.status),
                                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = when (row.status) {
                                            "FAILED" -> colors.error
                                            "NEEDS_CONFIRMATION" -> colors.primary
                                            else -> colors.onSurfaceVariant
                                        })
                                }
                            }
                        }
                        item(key = "manual-entry") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                MotionTextButton(enabled = pageActive && state.sources.isNotEmpty() && !busy,
                                    onClick = { openSource(state.sources.firstOrNull { it.first == source }?.first
                                        ?: state.sources.first().first, true) }) {
                                    Icon(Icons.Outlined.Search, null, Modifier.size(15.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("查找其他版本", fontSize = 12.sp)
                                }
                            }
                        }
                    } else {
                        if (manual) item(key = "search") {
                            if (state.sources.size > 1) MotionTabs(
                                selected = state.sources.indexOfFirst { it.first == source }.coerceAtLeast(0),
                                labels = state.sources.map { it.second }, modifier = Modifier.fillMaxWidth(),
                                controlHeight = 36.dp, fontSize = 12.sp, treatment = MotionTabTreatment.Soft,
                                indicator = colors.onSurface.copy(alpha = .065f), onSelect = { index ->
                                    if (!busy && pageActive) openSource(state.sources[index].first, true)
                                })
                            if (source == "komiic" && state.domainOptions.size > 1)
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("线路", fontSize = 11.sp, color = colors.onSurfaceVariant)
                                    MotionTabs(selected = state.domainOptions.indexOf(state.sourceDomain).coerceAtLeast(0),
                                        labels = state.domainOptions.map { it.removePrefix("https://").trimEnd('/') },
                                        modifier = Modifier.weight(1f), controlHeight = 32.dp, fontSize = 11.sp,
                                        treatment = MotionTabTreatment.Soft,
                                        indicator = colors.onSurface.copy(alpha = .065f), onSelect = { index ->
                                            if (!busy && pageActive) {
                                                requestedSearch = null; domainChanged = true; attaching = null
                                                onChooseDomain(state.domainOptions[index])
                                            }
                                        })
                                }
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(12.dp)).background(colors.onSurface.copy(alpha = .045f))
                                .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                BasicTextField(query, onValueChange = { query = it; attaching = null },
                                    modifier = Modifier.weight(1f).semantics { contentDescription = "搜索所选正文来源的作品" },
                                    enabled = pageActive && !state.connecting, singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                                    cursorBrush = SolidColor(colors.onSurface),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    keyboardActions = KeyboardActions(onSearch = { if (pageActive) search() }),
                                    decorationBox = { inner ->
                                        Box { if (query.isBlank()) Text("作品名或别名", color = colors.onSurfaceVariant); inner() }
                                    })
                                MotionIconButton(enabled = pageActive && query.isNotBlank() && !busy, onClick = search) {
                                    Icon(Icons.Outlined.Search, "搜索这个来源", Modifier.size(19.dp))
                                }
                            }
                        }
                        if (state.loading && matchesQuery) item(key = "search-busy") {
                            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                MotionBusyIndicator(Modifier.size(15.dp), strokeWidth = 1.4.dp)
                                Text("正在搜索…", fontSize = 12.sp, color = colors.onSurfaceVariant)
                            }
                        }
                        items(results, key = { "candidate:" + it.id }) { manga ->
                            val interaction = remember(manga.id) { MutableInteractionSource() }
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(interactionSource = interaction, indication = null,
                                    enabled = pageActive && !busy, role = Role.Button, onClickLabel = "关联这个版本") {
                                    keyboard?.hide(); focus.clearFocus()
                                    feedback.perform(FeelEvent.Selection)
                                    attaching = manga
                                    onAttach(manga)
                                }.motionPress(interaction, enabled = pageActive && !busy)
                                .padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(manga.coverUrl, null, Modifier.size(width = 32.dp, height = 44.dp)
                                    .clip(RoundedCornerShape(5.dp)))
                                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                                    Text(manga.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (manga.authors.isNotEmpty()) Text(manga.authors.joinToString(" · "),
                                        color = colors.onSurfaceVariant, fontSize = 11.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (state.connecting && attaching?.id == manga.id)
                                    MotionBusyIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
                            }
                        }
                        currentError?.let { error -> item(key = "error") {
                            Text(error, color = colors.error, fontSize = 12.sp, lineHeight = 18.sp,
                                modifier = Modifier.padding(vertical = 8.dp))
                            if (manual && !state.connecting) MotionTextButton(enabled = pageActive && !state.loading,
                                onClick = search) { Text("重试搜索", fontSize = 12.sp) }
                        } }
                        if (!busy && currentError == null && results.isEmpty()) item(key = "empty") {
                            Text(if (searched) "未找到匹配作品，可换别名再试。" else "暂无候选，可换个名称查找。",
                                color = colors.onSurfaceVariant, fontSize = 12.sp,
                                modifier = Modifier.padding(vertical = 12.dp))
                        }
                        if (!manual) item(key = "manual-fallback") {
                            MotionTextButton(enabled = pageActive && !busy, onClick = { manual = true }) {
                                Icon(Icons.Outlined.Search, null, Modifier.size(15.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("换个名称查找", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
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
