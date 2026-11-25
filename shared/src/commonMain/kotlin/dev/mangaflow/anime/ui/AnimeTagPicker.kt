package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.*
import dev.mangaflow.data.MangaTag
import dev.mangaflow.data.MangaTagKind
import dev.mangaflow.data.SearchTarget
import dev.mangaflow.ui.SearchScopeBar
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionInputGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The manga selector owns the interaction; anime keys remain in their source's namespace. */
@Composable
internal fun AnimeTagPicker(controller: AnimeController, provider: AnimeDiscoveryProvider,
    selectedTags: List<String>, query: String, active: Boolean, maxChoicesHeight: Dp,
    onToggle: (String) -> Unit, onQuery: ((String) -> Unit)? = null,
    choosing: Boolean? = null, onChoosing: ((Boolean) -> Unit)? = null) {
    var catalogue by remember(provider) { mutableStateOf(emptyList<AnimeDiscoveryTag>()) }
    var loaded by remember(provider) { mutableStateOf(false) }
    var nextOffset by remember(provider) { mutableStateOf<Int?>(null) }
    var requestOffset by remember(provider) { mutableStateOf(0) }
    var retry by remember(provider) { mutableStateOf(0) }
    var completedRetry by remember(provider) { mutableStateOf(0) }
    var completedOffset by remember(provider) { mutableStateOf<Int?>(null) }
    var loading by remember(provider) { mutableStateOf(false) }
    var error by remember(provider) { mutableStateOf<String?>(null) }
    var expanded by remember(provider) { mutableStateOf(false) }
    val showChoices = choosing ?: (expanded || selectedTags.isEmpty())
    LaunchedEffect(controller, provider, active, showChoices, requestOffset, retry) {
        if (!active || !showChoices || loaded && completedOffset == requestOffset && completedRetry == retry)
            return@LaunchedEffect
        loading = true
        error = null
        try {
            val page = controller.readDiscoveryTagDirectory(provider, requestOffset, forceRefresh = retry != completedRetry)
            currentCoroutineContext().ensureActive()
            catalogue = (if (requestOffset == 0) page.tags else catalogue + page.tags).distinctBy { it.key }
            nextOffset = page.nextOffset
            completedOffset = requestOffset
            completedRetry = retry
            loaded = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message?.takeIf(String::isNotBlank) ?: "标签目录暂未载入，请重试。"
        } finally {
            loading = false
        }
    }
    val choices = remember(catalogue) {
        catalogue.map { MangaTag(name = it.name, path = it.key, count = it.count,
            kind = MangaTagKind.GENRE, aliases = it.aliases) }
    }
    val selected = remember(selectedTags, choices, provider) {
        val byKey = choices.associateBy { it.path }
        selectedTags.map { byKey[it] ?: MangaTag(name = animeDiscoveryTagLabel(provider, it),
            path = it, kind = MangaTagKind.GENRE) }
    }
    val literal = animeDiscoveryTagInput(provider, query)?.takeIf {
        provider == AnimeDiscoveryProvider.BANGUMI && it !in selectedTags && catalogue.none { tag -> tag.key == it }
    }
    fun choose(tag: String) {
        if (!active) return
        expanded = false
        onQuery?.invoke("")
        onChoosing?.invoke(false)
        onToggle(tag)
    }
    Column(Modifier.fillMaxWidth().motionInputGate(active)) {
        SearchScopeBar(target = SearchTarget.TAG, query = query, selectedTags = selected,
            availableTags = choices, tagsLoading = loading, tagsError = error,
            onTarget = {}, onTag = { choose(it.path) }, onRetryTags = { if (active && !loading) retry++ },
            tagsOnly = true, onTagQuery = onQuery, maxChoicesHeight = maxChoicesHeight,
            showScope = false, translationGroupAvailable = false, choicesExpanded = showChoices,
            onChoicesExpandedChange = {
                if (active) {
                    expanded = it
                    onChoosing?.invoke(it)
                }
            }, choicesFooter = {
                if (literal != null) MotionTextButton(onClick = { choose(literal) }, enabled = active,
                    modifier = Modifier.heightIn(min = 44.dp)) {
                    Text("添加「$literal」标签", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (nextOffset != null && error == null) {
                    AnimeQuietLabel("已载入 ${catalogue.size} 个标签，可继续载入更多")
                    MotionTextButton(onClick = {
                        if (active && !loading) nextOffset?.let { requestOffset = it }
                    }, enabled = active && !loading, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(if (loading) "正在载入标签" else "载入更多标签")
                    }
                }
            })
    }
}
