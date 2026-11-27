package dev.mangaflow.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyGridLayoutInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.mangaflow.BrowseState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.data.sources.repairPicacgCoverUrl
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.PrefetchByteBudget
import dev.mangaflow.experience.prefetchOriginalPage
import dev.mangaflow.experience.readerPageCacheKey
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.storage.ReadingProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The visible cover and its optional disk warm must use the same URL-bound credentials. */
internal data class MangaCoverIdentity(val url: String, val headers: Map<String, String>, val cacheKey: String)

private fun Manga.requestCoverUrl(): String = if (sourceId == "picacg") repairPicacgCoverUrl(coverUrl) else coverUrl

internal val LocalBrowseWarmupEnabled = staticCompositionLocalOf { false }

/** Distances from actual work keys exclude headers, loading cells and pagination footers. */
internal fun <T> browsePageTail(items: List<T>, key: (T) -> String): Map<String, Int> = buildMap {
    for (distance in 0 until minOf(items.size, 13)) put(key(items[items.lastIndex - distance]), distance)
}

internal inline fun <T> nearBrowsePage(visible: List<T>, tail: Map<String, Int>,
    isWork: (T) -> Boolean, key: (T) -> Any): Boolean {
    var works = 0
    var remaining = Int.MAX_VALUE
    for (item in visible) if (isWork(item)) {
        works++
        (key(item) as? String)?.let { tail[it] }?.let { remaining = minOf(remaining, it) }
    }
    return works > 0 && remaining <= works.coerceIn(3, 12)
}

internal inline fun LazyGridLayoutInfo.nearBrowsePage(tail: Map<String, Int>, isWork: (Any?) -> Boolean): Boolean =
    nearBrowsePage(visibleItemsInfo, tail, {
        it.size.height > 0 && it.offset.y < viewportEndOffset && it.offset.y + it.size.height > viewportStartOffset &&
            isWork(it.contentType)
    }, { it.key })

@Composable
internal fun BrowseGridPagination(grid: LazyGridState, owner: String, refreshedAt: Long, count: Int,
    tail: Map<String, Int>, type: String, enabled: Boolean, fillViewport: Boolean, onMore: () -> Unit) {
    var requested by remember(owner, refreshedAt) { mutableIntStateOf(-1) }
    val near by remember(grid, tail, type, fillViewport) { derivedStateOf {
        !grid.lastScrolledBackward && (grid.lastScrolledForward || fillViewport && !grid.canScrollForward) &&
            grid.layoutInfo.nearBrowsePage(tail) { it == type }
    } }
    LaunchedEffect(owner, refreshedAt, enabled, near, count) {
        if (enabled && near && count > 0 && requested != count) {
            requested = count
            onMore()
        }
    }
}

@Composable
internal fun PrepareDiscoveryPage(state: BrowseState, grid: LazyGridState, active: Boolean,
    prepare: suspend (BrowseState) -> Unit) {
    val context = LocalMangaContext.current
    val policy = remember(context) { ExperiencePolicyStore.get(context) }
    val tail = remember(state.items) { browsePageTail(state.items, Manga::id) }
    val eligible = active && !state.isSearch && state.hasMore && !state.loading && !state.refreshing &&
        !state.loadingMore && state.error == null && state.failedPage == null && tail.isNotEmpty()
    var attempted by remember(state.cacheKey, state.updatedAt, state.page, grid) { mutableStateOf(false) }
    if (!eligible) return
    LaunchedEffect(state.cacheKey, state.updatedAt, state.page, tail, grid, policy) {
        policy.state.map { it.browseWarmup }.distinctUntilChanged().collectLatest { allowed ->
            if (allowed) snapshotFlow {
                grid.lastScrolledForward && !grid.lastScrolledBackward &&
                    grid.layoutInfo.nearBrowsePage(tail) { it == "browse-book" || it == "ranking-book" }
            }.collectLatest { approaching ->
                if (approaching && !attempted) {
                    attempted = true
                    prepare(state)
                }
            }
        }
    }
}

@Composable
internal fun mangaCoverIdentity(manga: Manga, repository: MangaRepository): MangaCoverIdentity {
    val headers = if (manga.coverUrl.isBlank()) emptyMap() else repository.imageHeaders(manga.id, "")
    return remember(manga.coverUrl, manga.sourceId, headers) {
        val url = manga.requestCoverUrl()
        MangaCoverIdentity(url, headers, readerPageCacheKey(Page(0, url), headers, manga.sourceId))
    }
}

/** Observe the existing click target; warming never intercepts or delays its real action. */
@Composable
internal fun Modifier.browseIntentWarmup(
    manga: Manga, repository: MangaRepository, interaction: MutableInteractionSource, enabled: Boolean = true,
): Modifier {
    if (!enabled || !LocalBrowseWarmupEnabled.current) return this
    val context = LocalMangaContext.current
    val policy = remember(context) { ExperiencePolicyStore.get(context) }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val intent = pressed || hovered || focused
    var attempted by remember(repository, manga.id) { mutableStateOf(false) }
    LaunchedEffect(repository, manga.id, intent) {
        if (!intent || attempted || manga.sourceId == "local" || repository !is UnifiedMangaRepository) return@LaunchedEffect
        // A passing pointer, scroll start or quick tap must not fan out metadata requests.
        delay(180L)
        policy.state.map { it.browseWarmup }.distinctUntilChanged().collectLatest { allowed ->
            if (!allowed || attempted) return@collectLatest
            try {
                withTimeoutOrNull(2_500L) {
                    policy.withBrowseWarmupAdmission(manga.sourceId) {
                        attempted = true
                        withContext(Dispatchers.IO) { repository.prewarmDetail(manga.id) }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // A speculative catalogue failure never replaces the visible page with an error.
            }
        }
    }
    return this
}

private data class BrowseViewport(val first: Int, val last: Int, val backwards: Boolean)

@Composable
private fun rememberBrowseIndexes(items: List<Manga>, itemKey: (Manga) -> String) = remember(items, itemKey) {
    buildMap(items.size) { items.forEachIndexed { index, manga -> put(itemKey(manga), index) } }
}

private inline fun <T> coverViewport(
    visible: List<T>, indexes: Map<String, Int>, backwards: Boolean, key: (T) -> Any,
): BrowseViewport? {
    var first = Int.MAX_VALUE
    var last = -1
    for (item in visible) {
        val index = indexes[key(item) as? String ?: continue] ?: continue
        first = minOf(first, index)
        last = maxOf(last, index)
    }
    return if (last < 0) null else BrowseViewport(first, last, backwards)
}

@Composable
internal fun BrowseCoverWarmup(
    items: List<Manga>, repository: MangaRepository, active: Boolean, grid: LazyGridState, columns: Int,
    itemKey: (Manga) -> String = Manga::id,
) {
    if (!active || items.isEmpty()) return
    val indexes = rememberBrowseIndexes(items, itemKey)
    BrowseCoverWarmup(items, repository, grid, columns) {
        if (grid.isScrollInProgress) null
        else coverViewport(grid.layoutInfo.visibleItemsInfo, indexes, grid.lastScrolledBackward) { it.key }
    }
}

@Composable
internal fun BrowseCoverWarmup(
    items: List<Manga>, repository: MangaRepository, active: Boolean, list: LazyListState,
    itemKey: (Manga) -> String = Manga::id,
) {
    if (!active || items.isEmpty()) return
    val indexes = rememberBrowseIndexes(items, itemKey)
    BrowseCoverWarmup(items, repository, list, 2) {
        if (list.isScrollInProgress) null
        else coverViewport(list.layoutInfo.visibleItemsInfo, indexes, list.lastScrolledBackward) { it.key }
    }
}

@Composable
internal fun HistoryCoverWarmup(
    groups: Map<*, List<ReadingProgress>>, repository: MangaRepository, active: Boolean,
    list: LazyListState, itemKey: (Manga) -> String,
) {
    if (!active || groups.isEmpty()) return
    val covers = remember(groups) {
        buildList {
            for (day in groups.values) day.mapTo(this, ReadingProgress::manga)
        }
    }
    BrowseCoverWarmup(covers, repository, true, list, itemKey)
}

/** At most the next row of already-listed covers; this never discovers/fetches other works. */
@Composable
private fun BrowseCoverWarmup(
    items: List<Manga>, repository: MangaRepository, owner: Any, count: Int,
    viewport: () -> BrowseViewport?,
) {
    val context = LocalMangaContext.current
    val policy = remember(context) { ExperiencePolicyStore.get(context) }
    val currentViewport by rememberUpdatedState(viewport)
    val warmed = remember(context, repository, owner) { linkedSetOf<String>() }
    LaunchedEffect(context, repository, items, owner, count) {
        policy.state.map { it.browseWarmup }.distinctUntilChanged().collectLatest { allowed ->
            if (!allowed) return@collectLatest
            var previousFirst: Int? = null
            var wasMoving = false
            snapshotFlow { currentViewport() }.collectLatest window@{ visible ->
                if (visible == null) {
                    // Cancel pending work on scroll start. Ignore layout publications until it settles.
                    wasMoving = true
                    return@window
                }
                val old = previousFirst
                val backwards = if (!wasMoving && old != null && visible.first != old) visible.first < old
                    else visible.backwards
                wasMoving = false
                previousFirst = visible.first
                delay(180L)
                val budget = policy.state.value
                if (!budget.browseWarmup) return@window
                val maximum = minOf(budget.prefetchBytes / 8L, 2L * 1024L * 1024L)
                if (maximum <= 0L) return@window
                val bytes = PrefetchByteBudget(maximum)
                val indexes = if (backwards) (visible.first - 1 downTo 0)
                    else (visible.last + 1 until items.size)
                for (index in indexes.take(count.coerceIn(1, 4))) {
                    if (!policy.state.value.browseWarmup || bytes.remaining <= 0L) break
                    val manga = items[index]
                    val url = manga.requestCoverUrl()
                    if (!url.startsWith("https:")) continue
                    try {
                        withTimeoutOrNull(2_500L) {
                            val headers = repository.imageHeaders(manga.id, "")
                            val page = Page(0, url)
                            val cacheKey = readerPageCacheKey(page, headers, manga.sourceId)
                            if (cacheKey !in warmed && prefetchOriginalPage(context, page, headers, manga.sourceId, bytes)) {
                                // A cancelled or declined warm is still eligible when the list settles again.
                                warmed.add(cacheKey)
                                if (warmed.size > 24) warmed.remove(warmed.first())
                            }
                        }
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        // Visible requests keep their normal retry and error ownership.
                    }
                }
            }
        }
    }
}
