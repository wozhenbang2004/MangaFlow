package dev.mangaflow.ui

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.normalizeNfkc
import dev.mangaflow.platform.platformKind
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.*
import dev.mangaflow.ui.platform.formatUiDate
import dev.mangaflow.ui.platform.localDateAt
import dev.mangaflow.ui.platform.localToday
import dev.mangaflow.ui.platform.minusDays
import kotlinx.datetime.LocalDate

internal fun ReadingProgress.historyBookKey() = manga.sourceId + ":" + manga.id

internal fun readingHistoryByDay(history: List<ReadingProgress>) =
    history.groupBy { if (it.updatedAt > 0L) localDateAt(it.updatedAt) else null }

internal fun readingHistoryDay(date: LocalDate?, today: LocalDate): String = when {
    date == null -> "早期记录"
    date == today -> "今天"
    date == today.minusDays(1) -> "昨天"
    date.year != today.year -> formatUiDate(date, "yyyy/M/d")
    else -> "${date.monthNumber}月${date.dayOfMonth}日"
}

private val termBreak = Regex("\\s+")

@Composable
internal fun RecentReadingSheet(
    history: List<ReadingProgress>, repository: MangaRepository, library: LibraryStore,
    onDismiss: () -> Unit, onContinue: (ReadingProgress) -> Unit, onRemoveHistory: (String) -> Unit,
    backdrop: HazeState? = null,
) {
    val desktop = platformKind == PlatformKind.DESKTOP
    val snackbar = remember { SnackbarHostState() }
    val removals = rememberHistoryRemoval(snackbar)
    val records = remember(history, removals.pending) { history.filterNot { it.manga.id in removals.pending } }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var focusHeader by remember { mutableStateOf(false) }
    var resumeAfterClose by remember { mutableStateOf<ReadingProgress?>(null) }
    var browseHeightPx by remember { mutableIntStateOf(0) }
    val allPosition = rememberLazyListState()
    val matchesPosition = rememberLazyListState()
    val terms = remember(query) { normalizeNfkc(query).lowercase().split(termBreak).filter(String::isNotBlank) }
    val searchIndex = remember(records, searching) {
        if (searching) records.map { row -> normalizeNfkc(
            listOf(row.manga.title, row.manga.authors.joinToString(" "), row.chapterTitle).joinToString("\n")
        ).lowercase() } else emptyList()
    }
    val matches = remember(records, terms, searchIndex) {
        if (terms.isEmpty() || !searching) records
        else records.filterIndexed { index, _ -> terms.all { it in searchIndex[index] } }
    }
    val position = if (terms.isEmpty()) allPosition else matchesPosition
    LaunchedEffect(terms) { if (terms.isNotEmpty()) matchesPosition.scrollToItem(0) }
    fun openSearch() { searching = true; focusHeader = true }
    MotionBottomSheet(onDismissRequest = {
        val target = resumeAfterClose
        resumeAfterClose = null
        onDismiss()
        if (target != null && records.any { it.historyBookKey() == target.historyBookKey() }) onContinue(target)
    }, containerColor = MaterialTheme.colorScheme.background, backdrop = backdrop, desktopWidth = 930.dp) { dismiss ->
        val keyboard = LocalSoftwareKeyboardController.current
        fun closeSearch() {
            searching = false
            focusHeader = desktop
            query = ""
            keyboard?.hide()
        }
        val resume: (ReadingProgress) -> Unit = { record ->
            if (resumeAfterClose == null) {
                resumeAfterClose = record
                keyboard?.hide()
                dismiss()
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            val density = LocalDensity.current
            val split = desktop && maxWidth >= 630.dp * density.fontScale.coerceAtLeast(1f) &&
                maxHeight >= 340.dp && records.isNotEmpty()
            val selected = remember(matches, selection) { matches.firstOrNull { it.historyBookKey() == selection } }
            val chapter = selected?.takeIf { !split && showChapters }?.manga
            fun stepBack() { if (chapter != null) showChapters = false else closeSearch() }
            BackHandler(enabled = resumeAfterClose == null && (chapter != null || searching), onBack = ::stepBack)
            val shortcuts = if (desktop) Modifier.onPreviewKeyEvent { event ->
                val plain = !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed
                when {
                    !plain || resumeAfterClose != null -> false
                    event.key == Key.F && event.isCtrlPressed && chapter == null && records.isNotEmpty() -> {
                        if (event.type == KeyEventType.KeyUp) openSearch()
                        true
                    }
                    else -> false
                }
            } else Modifier
            val shell = when {
                split -> Modifier.height(maxHeight.coerceAtMost(568.dp))
                chapter != null -> Modifier
                searching && browseHeightPx > 0 -> Modifier.height(with(density) { browseHeightPx.toDp() }.coerceAtMost(maxHeight))
                !searching -> Modifier.onSizeChanged { browseHeightPx = it.height }
                else -> Modifier
            }
            Column(Modifier.fillMaxWidth().then(shell).then(shortcuts)) {
                if (chapter == null) RecentReadingHeader(matches.size, records.isNotEmpty(), desktop, searching, query,
                    focusHeader, onFocusHandled = { focusHeader = false },
                    onQuery = { query = it }, onSearch = ::openSearch, onBack = ::closeSearch, onClose = dismiss)
                val select: (String) -> Unit = { selection = it; showChapters = true; keyboard?.hide() }
                if (split && matches.isNotEmpty()) {
                    DesktopReadingHistory(matches, repository, library, position, true, resume,
                        onRemove = { removals.request(it, onRemoveHistory) },
                        modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        selection = selection, onSelect = select)
                } else MotionTabContent(selected = chapter, modifier = Modifier.fillMaxWidth(),
                    contentKey = { it?.let { work -> work.sourceId + ":" + work.id } ?: "recent-books" },
                    order = { if (it == null) 0 else 1 },
                    fillContent = false, soft = true) { work ->
                    if (work != null) ChapterHistoryContent(work, library, onContinue = resume,
                        onBack = { showChapters = false }, onClose = if (desktop) dismiss else null)
                    else RecentReadingList(matches, terms.isNotEmpty(), position, repository, resume, select,
                        onRemove = { removals.request(it, onRemoveHistory) })
                }
            }
        }
        MangaSnackbarHost(snackbar, Modifier.fillMaxWidth().padding(horizontal = 12.dp))
    }
}

@Composable
private fun RecentReadingHeader(
    count: Int, canSearch: Boolean, desktop: Boolean, searching: Boolean, query: String,
    requestFocus: Boolean, onFocusHandled: () -> Unit,
    onQuery: (String) -> Unit, onSearch: () -> Unit, onBack: () -> Unit, onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (searching) 4.dp else 16.dp, end = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (searching) {
            MotionIconButton(onClick = onBack, modifier = Modifier.size(48.dp), tooltip = "返回全部记录") {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回全部记录", Modifier.size(20.dp))
            }
            BasicTextField(query, onValueChange = onQuery, singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(requester).semantics { contentDescription = "查找阅读记录" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                decorationBox = { input ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("书名、作者或上次读的章节", color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                })
            if (query.isNotEmpty()) MotionIconButton(onClick = { onQuery(""); requester.requestFocus() },
                modifier = Modifier.size(48.dp), tooltip = "清除搜索") {
                Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(18.dp))
            }
        } else {
            Text("最近阅读", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count > 0) Text(mangaNumberText("$count 部"), style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant)
            if (canSearch) MotionIconButton(onClick = onSearch, modifier = Modifier.size(48.dp).focusRequester(requester),
                tooltip = if (desktop) "查找阅读记录 · Ctrl+F" else "查找阅读记录") {
                Icon(Icons.Outlined.Search, "查找阅读记录", Modifier.size(20.dp))
            }
        }
        if (desktop && !searching) MotionIconButton(onClick = onClose, modifier = Modifier.size(44.dp), tooltip = "关闭最近阅读") {
            Icon(Icons.Outlined.Close, "关闭最近阅读", Modifier.size(18.dp))
        }
    }
    LaunchedEffect(searching, requestFocus, canSearch) {
        if (requestFocus) {
            if (searching || canSearch) requester.requestFocus()
            if (searching && !desktop) keyboard?.show()
            onFocusHandled()
        }
    }
}

@Composable
private fun RecentReadingList(
    history: List<ReadingProgress>, filtered: Boolean, position: LazyListState, repository: MangaRepository,
    onContinue: (ReadingProgress) -> Unit, onSelect: (String) -> Unit, onRemove: (String) -> Unit,
) {
    val today = localToday()
    val groups = remember(history) { readingHistoryByDay(history) }
    val motion = LocalMotionEnabled.current
    val feedback = rememberFeelFeedback()
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), state = position,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        if (history.isEmpty()) item(key = "empty") {
            Text(if (filtered) "没有匹配的阅读记录" else "还没有阅读记录", Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        groups.forEach { (date, books) ->
            item(key = "date:$date", contentType = "date") {
                Text(readingHistoryDay(date, today), Modifier.padding(top = 8.dp, bottom = 3.dp).semantics { heading() },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(books, key = { "work:" + it.historyBookKey() }, contentType = { "book" }) { record ->
                SwipeToRemove(onRemove = { onRemove(record.manga.id) }, label = "删除阅读记录", transparentBackground = true,
                    modifier = Modifier.animateItem(fadeInSpec = if (motion) tween(MangaMotion.Content) else null,
                        placementSpec = if (motion) spring(MangaMotion.NavigationDamping, MangaMotion.NavigationStiffness) else null,
                        fadeOutSpec = if (motion) tween(MangaMotion.Quick) else null)) {
                    ContinueRow(record, repository, onContinue, onHistory = {
                        feedback.perform(FeelEvent.Selection)
                        onSelect(record.historyBookKey())
                    }, namedHistory = true)
                }
            }
        }
    }
}
