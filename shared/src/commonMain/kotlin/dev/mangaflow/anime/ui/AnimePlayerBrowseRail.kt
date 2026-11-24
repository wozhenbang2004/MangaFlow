package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.anime.AnimeController
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.ui.DesktopVerticalScrollbar
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate

/** One local presentation, so Workspace Back still understands a preview even without keyboard focus. */
internal class AnimePlayerBrowseVisit(val subjectId: String) {
    var previewId by mutableStateOf<String?>(null)
        private set
    var focusRevision by mutableIntStateOf(1)
        private set

    fun preview(id: String) {
        previewId = id
        focusRevision++
    }

    fun returnToResults(): Boolean {
        if (previewId == null) return false
        focusSearch()
        return true
    }

    fun focusSearch() {
        previewId = null
        focusRevision++
    }
}

/** A dedicated catalogue visit. Selecting a row never opens an active anime detail or a player. */
@Composable
internal fun AnimePlayerBrowseRail(
    controller: AnimeController,
    visit: AnimePlayerBrowseVisit,
    active: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val catalogue = remember(controller, scope) { controller.newPlaybackCatalogueSearch(scope) }
    val search by catalogue.state.collectAsStateWithLifecycle()
    val isActive by rememberUpdatedState(active)
    var draft by remember { mutableStateOf("") }
    val previewId = visit.previewId
    val term = draft.trim()
    val ownsResults = term.isNotEmpty() && search.query == term
    val rows = remember(ownsResults, search.index.items) {
        if (ownsResults) search.index.items.filter { it.canonicalDoubanId() != null }.distinctBy { it.id }
        else emptyList()
    }
    val preview = previewId?.let { id -> rows.firstOrNull { it.id == id } }
    val inputFocus = remember { FocusRequester() }
    val previewFocus = remember { FocusRequester() }
    var focusTargetPlaced by remember(preview?.id) { mutableStateOf(false) }
    var appliedFocus by remember { mutableIntStateOf(0) }
    val list = rememberLazyListState()

    LaunchedEffect(catalogue) { catalogue.observeRoutes() }
    LaunchedEffect(previewId, preview?.id) {
        if (previewId != null && preview == null && visit.previewId == previewId) visit.returnToResults()
    }
    val focusRevision = visit.focusRevision
    LaunchedEffect(focusRevision, active, focusTargetPlaced, preview?.id) {
        if (!active || !focusTargetPlaced || focusRevision == appliedFocus || preview?.id != visit.previewId) {
            return@LaunchedEffect
        }
        val target = if (preview == null) inputFocus else previewFocus
        if (target.requestFocus()) appliedFocus = focusRevision
    }
    LaunchedEffect(search.query) { list.scrollToItem(0) }

    fun ownsLiveResults() = isActive && draft.trim() == term && term.isNotEmpty() &&
        catalogue.state.value.query == term
    fun submit() {
        if (!isActive || draft.isBlank()) return
        catalogue.submit(draft)
    }

    Column(modifier.motionInputGate(active).onPreviewKeyEvent { event ->
        if (!isActive || event.key != Key.Escape) false
        else {
            if (event.type == KeyEventType.KeyUp) {
                if (!visit.returnToResults()) onClose()
            }
            true
        }
    }.semantics { paneTitle = "边看边逛" }.padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            if (preview != null) MotionIconButton(onClick = { visit.returnToResults() }, enabled = active,
                modifier = Modifier.size(40.dp).focusRequester(previewFocus).onPlaced { focusTargetPlaced = true },
                tooltip = "返回搜索结果") {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回搜索结果", Modifier.size(19.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("边看边逛", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                AnimeQuietLabel("番剧目录 · 豆瓣")
            }
            MotionIconButton(onClick = onClose, enabled = active, modifier = Modifier.size(42.dp), tooltip = "收起边看边逛") {
                Icon(Icons.Outlined.Close, "收起边看边逛", Modifier.size(19.dp))
            }
        }
        if (preview == null) {
            AnimeTextField(draft, { draft = it }, placeholder = "搜索番剧",
                enabled = active, onSubmit = ::submit, emptyHint = "作品名或别名",
                inputModifier = Modifier.focusRequester(inputFocus).onPlaced { focusTargetPlaced = true })
            MotionTextButton(onClick = ::submit, enabled = active && term.isNotEmpty(),
                modifier = Modifier.align(Alignment.End).heightIn(min = 40.dp)) { Text("查找") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (preview != null) key(preview.id) {
                LazyColumn(Modifier.fillMaxSize(), userScrollEnabled = active,
                    contentPadding = PaddingValues(top = 10.dp, end = 6.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AnimePoster(preview, Modifier.size(64.dp, 92.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(preview.title, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 4, overflow = TextOverflow.Ellipsis)
                                preview.originalTitle.takeIf { it.isNotBlank() && it != preview.title }?.let { AnimeQuietLabel(it) }
                                playbackCatalogueFacts(preview).takeIf(String::isNotBlank)?.let { AnimeQuietLabel(it) }
                            }
                        }
                    }
                    if (preview.description.isNotBlank()) item {
                        Text(preview.description, style = MaterialTheme.typography.bodyMedium)
                    }
                    item { AnimeQuietLabel("仅查看资料，不切换当前播放。") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = list, userScrollEnabled = active,
                    contentPadding = PaddingValues(top = 4.dp, end = 6.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (!ownsResults) item(key = "catalogue-draft") {
                        AnimeQuietLabel("输入作品名查找，不会中断播放。", Modifier.padding(vertical = 8.dp))
                    } else {
                        if (search.index.loading && rows.isEmpty()) item(key = "catalogue-loading") {
                            AnimeQuietLabel("正在查找番剧", Modifier.padding(vertical = 12.dp))
                        }
                        if (search.index.error != null) item(key = "catalogue-error") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AnimeQuietLabel("目录未能完成查找", Modifier.weight(1f))
                                MotionTextButton(onClick = { if (ownsLiveResults()) catalogue.retry() }, enabled = active) {
                                    Text("重试")
                                }
                            }
                        }
                        if (!search.index.loading && search.index.error == null && rows.isEmpty()) item(key = "catalogue-empty") {
                            AnimeQuietLabel("未找到匹配作品。试试原名或别名。", Modifier.padding(vertical = 12.dp))
                        }
                        items(rows, key = { it.id }, contentType = { "catalogue-result" }) { row ->
                            AnimeSearchWorkRow(row, playbackCatalogueFacts(row),
                                row.originalTitle.takeIf { it.isNotBlank() && it != row.title }, emptyList(),
                                active, onOpen = {
                                    if (ownsLiveResults() && catalogue.state.value.index.items.any { it == row }) {
                                        visit.preview(row.id)
                                    }
                                }, openLabel = "查看作品资料，不切换播放")
                        }
                        if (search.index.canLoadMore || search.index.loading && search.index.page > 0) item(key = "catalogue-more") {
                            if (search.index.loading) AnimeQuietLabel("正在加载更多", Modifier.padding(vertical = 10.dp))
                            else MotionTextButton(onClick = { if (ownsLiveResults()) catalogue.loadMore() }, enabled = active) {
                                Text("更多结果")
                            }
                        }
                    }
                }
                DesktopVerticalScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(5.dp))
            }
        }
    }
}

private fun playbackCatalogueFacts(subject: AnimeSubject): String =
    listOfNotNull(subject.year?.toString(), subject.episodeCount?.takeIf { it > 0 }?.let { "$it 集" })
        .joinToString(" · ")
