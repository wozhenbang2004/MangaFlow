package dev.mangaflow.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.data.UnifiedMangaRepository
import dev.mangaflow.experience.ExperiencePolicyStore
import dev.mangaflow.experience.PrefetchByteBudget
import dev.mangaflow.experience.prefetchOriginalPage
import dev.mangaflow.experience.readerPageCacheKey
import dev.mangaflow.platform.LocalMangaContext
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
internal data class MangaCoverIdentity(val headers: Map<String, String>, val cacheKey: String)

internal val LocalBrowseWarmupEnabled = staticCompositionLocalOf { false }

@Composable
internal fun mangaCoverIdentity(manga: Manga, repository: MangaRepository): MangaCoverIdentity {
    val headers = if (manga.coverUrl.isBlank()) emptyMap() else repository.imageHeaders(manga.id, "")
    return remember(manga.coverUrl, manga.sourceId, headers) {
        MangaCoverIdentity(headers, readerPageCacheKey(Page(0, manga.coverUrl), headers, manga.sourceId))
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

private data class BrowseViewport(val first: Int, val last: Int, val scrolling: Boolean)

@Composable
internal fun BrowseCoverWarmup(
    items: List<Manga>, repository: MangaRepository, active: Boolean, grid: LazyGridState, columns: Int,
) {
    val indexes = remember(items) { items.mapIndexed { index, manga -> manga.id to index }.toMap() }
    BrowseCoverWarmup(items, repository, active, grid, columns) {
        val visible = grid.layoutInfo.visibleItemsInfo.mapNotNull { item -> (item.key as? String)?.let(indexes::get) }
        BrowseViewport(visible.minOrNull() ?: -1, visible.maxOrNull() ?: -1, grid.isScrollInProgress)
    }
}

@Composable
internal fun BrowseCoverWarmup(
    items: List<Manga>, repository: MangaRepository, active: Boolean, list: LazyListState,
) {
    val indexes = remember(items) { items.mapIndexed { index, manga -> manga.id to index }.toMap() }
    BrowseCoverWarmup(items, repository, active, list, 2) {
        val visible = list.layoutInfo.visibleItemsInfo.mapNotNull { item -> (item.key as? String)?.let(indexes::get) }
        BrowseViewport(visible.minOrNull() ?: -1, visible.maxOrNull() ?: -1, list.isScrollInProgress)
    }
}

/** At most the next row of already-listed covers; this never discovers/fetches other works. */
@Composable
private fun BrowseCoverWarmup(
    items: List<Manga>, repository: MangaRepository, active: Boolean, owner: Any, count: Int,
    viewport: () -> BrowseViewport,
) {
    val context = LocalMangaContext.current
    val policy = remember(context) { ExperiencePolicyStore.get(context) }
    LaunchedEffect(context, repository, items, active, owner, count) {
        if (!active || items.isEmpty()) return@LaunchedEffect
        val warmed = linkedSetOf<String>()
        policy.state.map { it.browseWarmup }.distinctUntilChanged().collectLatest { allowed ->
            if (!allowed) return@collectLatest
            var previousFirst: Int? = null
            var backwards = false
            snapshotFlow(viewport).collectLatest window@{ visible ->
                if (visible.first < 0 || visible.last < visible.first) return@window
                previousFirst?.let { old ->
                    if (old != visible.first) backwards = visible.first < old
                }
                previousFirst = visible.first
                // Do not spend radio/decode work chasing a fling's transient positions.
                if (visible.scrolling) return@window
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
                    if (!manga.coverUrl.startsWith("https:") || !warmed.add(manga.id + "\u0000" + manga.coverUrl)) continue
                    while (warmed.size > 24) warmed.remove(warmed.first())
                    try {
                        withTimeoutOrNull(2_500L) {
                            val headers = repository.imageHeaders(manga.id, "")
                            prefetchOriginalPage(context, Page(0, manga.coverUrl), headers, manga.sourceId, bytes)
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
