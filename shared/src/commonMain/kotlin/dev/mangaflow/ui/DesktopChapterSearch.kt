package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.data.Chapter
import dev.mangaflow.platform.normalizeNfkc
import dev.mangaflow.ui.motion.MotionIconButton

private val chapterSearchNumbers = Regex("""\d+(?:\.\d+)?""")
private val chapterSearchSpaces = Regex("""\s+""")
private data class SearchableChapter(val chapter: Chapter, val title: String, val group: String, val numbers: Set<String>)

@Composable
internal fun rememberMatchingDesktopChapters(chapters: List<Chapter>, query: String): List<Chapter> {
    if (query.isBlank()) return emptyList()
    val index = remember(chapters) {
        chapters.map { chapter ->
            val title = normalizeNfkc(chapter.title).lowercase()
            SearchableChapter(chapter, title, normalizeNfkc(chapterGroupLabel(chapter.group)).lowercase(),
                chapterSearchNumbers.findAll(title).map { it.value }.toSet())
        }
    }
    return remember(index, query) {
        val terms = normalizeNfkc(query).lowercase().split(chapterSearchSpaces).filter(String::isNotBlank)
        val numbers = terms.filter(chapterSearchNumbers::matches)
        val words = terms.filterNot(chapterSearchNumbers::matches)
        index.filter { entry -> entry.numbers.containsAll(numbers) && words.all { it in entry.title || it in entry.group } }
            .map(SearchableChapter::chapter)
    }
}

@Composable
internal fun DesktopChapterSearchField(
    query: String,
    onQuery: (String) -> Unit,
    focusRequest: Int,
    active: Boolean,
    modifier: Modifier = Modifier,
    onOpenMatch: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodySmall
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var appliedRequest by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(focusRequest, active) {
        if (active && focusRequest > 0 && appliedRequest != focusRequest) {
            withFrameNanos { }
            requester.requestFocus()
            appliedRequest = focusRequest
        }
    }
    val shape = RoundedCornerShape(10.dp)
    Row(modifier.heightIn(min = 36.dp)
        .background(colors.onSurface.copy(alpha = .045f), shape)
        .border(1.dp, if (focused) colors.primary.copy(alpha = .70f) else colors.onSurface.copy(alpha = .10f), shape)
        .padding(start = 10.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
        BasicTextField(query, onValueChange = onQuery, enabled = active, singleLine = true,
            textStyle = style.copy(color = colors.onSurface), cursorBrush = SolidColor(colors.primary),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp)
                .focusRequester(requester).onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (!active || event.key != Key.Enter || onOpenMatch == null) false
                    else {
                        if (event.type == KeyEventType.KeyUp) onOpenMatch()
                        true
                    }
                }
                .semantics { contentDescription = "查找章节" },
            decorationBox = { input ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("查找章节", color = colors.onSurfaceVariant, style = style,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    input()
                }
            })
        if (query.isNotEmpty()) MotionIconButton(onClick = {
            onQuery("")
            requester.requestFocus()
        }, enabled = active, modifier = Modifier.size(30.dp), tooltip = "清除章节搜索") {
            Icon(Icons.Outlined.Close, "清除章节搜索", Modifier.size(16.dp))
        }
    }
}
