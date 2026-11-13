package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
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
) {
    var query by remember(state.work.id) { mutableStateOf(state.query) }
    var source by remember(state.work.id) { mutableStateOf(state.sources.firstOrNull()?.first.orEmpty()) }
    var candidate by remember(state.work.id, state.candidates) { mutableStateOf<Manga?>(null) }
    var sameSystem by remember(candidate?.id) { mutableStateOf(false) }
    var edition by remember(candidate?.id) { mutableStateOf("连载版") }
    val colors = MaterialTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .78f).dp
    val search: () -> Unit = {
        if (source.isNotBlank() && query.isNotBlank() && !state.connecting) {
            keyboard?.hide(); focus.clearFocus()
            candidate = null
            onSearch(source, query)
            scope.launch { list.scrollToItem(0) }
        }
    }
    LaunchedEffect(state.work.id) {
        if (source.isNotBlank() && query.isNotBlank() && state.candidates.isEmpty() &&
            !state.loading && !state.connecting && state.error == null) onSearch(source, query)
    }
    LaunchedEffect(state.linked) {
        if (state.linked.any { it.id == candidate?.id }) candidate = null
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
                item(key = "linked-title") {
                    Text("已关联", fontSize = 12.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                }
                items(listOf(state.work) + state.linked, key = { "linked:" + it.id }) { manga ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChapterSourceLogo(manga.sourceId, sourceDisplayName(manga.sourceId), Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sourceDisplayName(manga.sourceId), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            if (manga.title != state.work.title) Text(manga.title, fontSize = 12.sp,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        MotionTextButton(enabled = !state.connecting, onClick = { onPrefer(manga.sourceId) }) {
                            if (state.preferredSource == manga.sourceId) Icon(Icons.Outlined.Check, null,
                                Modifier.size(13.dp), tint = colors.onSurfaceVariant)
                            Text(if (state.preferredSource == manga.sourceId) "优先" else "设为优先",
                                fontSize = 12.sp, color = colors.onSurfaceVariant)
                        }
                        if (manga.id != state.work.id) MotionIconButton(enabled = !state.connecting,
                            onClick = { onDetach(manga) }) {
                            Icon(Icons.Outlined.Close, "取消关联，保留阅读历史与下载", Modifier.size(17.dp))
                        }
                    }
                }
                item(key = "add-title") {
                    Text("关联其他版本", fontSize = 12.sp, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    MotionTabs(selected = state.sources.indexOfFirst { it.first == source }.coerceAtLeast(0),
                        labels = state.sources.map { it.second }, modifier = Modifier.fillMaxWidth(),
                        controlHeight = 38.dp, fontSize = 12.sp, treatment = MotionTabTreatment.Soft,
                        indicator = colors.onSurface.copy(alpha = .065f), onSelect = { index ->
                            if (!state.connecting) {
                                source = state.sources[index].first
                                search()
                            }
                        })
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
                state.error?.let { error -> item(key = "source-error") {
                    Text(error, color = colors.error, fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(vertical = 8.dp))
                    if (!state.connecting) MotionTextButton(onClick = search) { Text("重试搜索") }
                } }
                val matchesQuery = state.sourceId == source && state.query.trim() == query.trim()
                items((candidate?.let { listOf(it) } ?: if (matchesQuery) state.candidates else emptyList()).filter { result ->
                    state.linked.none { it.id == result.id }
                }, key = { "candidate:" + it.id }) { manga ->
                    val selected = candidate?.id == manga.id
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.surfaceVariant.copy(alpha = .45f) else colors.surface.copy(alpha = 0f))
                        .clickable(enabled = !state.connecting) {
                            keyboard?.hide(); focus.clearFocus(); candidate = manga
                            scope.launch { list.scrollToItem(0) }
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(manga.coverUrl, null, Modifier.size(width = 36.dp, height = 48.dp)
                            .clip(RoundedCornerShape(5.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(manga.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(manga.authors.joinToString(" · "), color = colors.onSurfaceVariant,
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
                item(key = "source-bottom") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
