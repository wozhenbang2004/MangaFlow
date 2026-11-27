package dev.mangaflow.ui

import dev.mangaflow.experience.sourceNetwork

import dev.mangaflow.typography.MangaFontScene

import dev.mangaflow.ui.platform.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.Page
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.data.plugins.resolveScriptPage
import dev.mangaflow.data.plugins.materializeScriptPage
import dev.mangaflow.storage.DownloadStore
import kotlinx.coroutines.CancellationException
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.storage.PageCollection
import dev.mangaflow.storage.ReadingMode
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress

@Composable
internal fun PageCollectionsSheet(
    collections: List<PageCollection>,
    repository: MangaRepository,
    onOpen: (PageCollection) -> Unit,
    onRemove: (PageCollection) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background, desktopWidth = 1050.dp) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("画面收藏", style = MaterialTheme.typography.titleLarge)
            Text(collections.size.toString(), modifier = Modifier.padding(start = 8.dp).weight(1f),
                style = mangaMetricValueStyle(), color = colors.onSurfaceVariant)
            MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, "关闭画面收藏", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
        if (platformKind == PlatformKind.DESKTOP && collections.isNotEmpty()) {
            DesktopPageGallery(collections, repository, onOpen = { onOpen(it); dismiss() }, onRemove = onRemove)
        } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (collections.isEmpty()) item(key = "empty-pictures") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("还没有收藏的画面", style = MaterialTheme.typography.titleSmall)
                    Text("阅读时点爱心，保存单页或整组跨页。", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
            }
            items(collections, key = PageCollection::key) { collection ->
                SwipeToRemove(onRemove = { onRemove(collection) }, label = "移除画面收藏") {
                    PageCollectionRow(collection, repository) {
                        onOpen(collection)
                        dismiss()
                    }
                }
            }
        }
    }
}

/** Single pages and real two-page spreads share a gallery in their saved order. */
@Composable
private fun DesktopPageGallery(collections: List<PageCollection>, repository: MangaRepository,
    onOpen: (PageCollection) -> Unit, onRemove: (PageCollection) -> Unit) {
    val window = LocalMangaWindow.current
    val columns = MangaLayout.columns(window.width, 4, 210.dp * window.fontScale, 16.dp, 22.dp)
    val grid = rememberLazyGridState()
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().heightIn(max = (window.height - 64.dp).coerceIn(1.dp, 780.dp))) {
        LazyVerticalGrid(GridCells.Fixed(columns), state = grid,
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 5.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(17.dp)) {
            items(collections, key = PageCollection::key,
                span = { GridItemSpan(if (it.spread && columns > 1) 2 else 1) }) { entry ->
                val actions = listOf(
                    DesktopMenuAction("阅读收藏画面", icon = Icons.AutoMirrored.Outlined.MenuBook) { onOpen(entry) },
                    DesktopMenuAction("移除画面收藏", icon = Icons.Outlined.DeleteOutline, destructive = true) { onRemove(entry) },
                )
                val interaction = remember(entry.key) { MutableInteractionSource() }
                DesktopContextMenuArea(actions) {
                    Column(Modifier.fillMaxWidth().clickable(interactionSource = interaction, indication = null,
                        role = Role.Button, onClickLabel = "阅读收藏画面", onClick = { onOpen(entry) })
                        .motionPress(interaction, pressedScale = .99f)) {
                        val ratio = if (!entry.spread) 1.24f else if (columns > 1) 2.55f else 1.72f
                        CollectionPreview(entry, repository, Modifier.fillMaxWidth().aspectRatio(ratio)
                            .mangaCoverCard(8.dp, shadowRadius = 4.dp).background(colors.surfaceContainerLow))
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp),
                            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(entry.manga.title, style = MaterialTheme.typography.titleMedium,
                                    fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                Text(entry.chapter.title, style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val date = remember(entry.savedAt) { if (entry.savedAt > 0L)
                                    formatInstantMillis(entry.savedAt, "M月d日 HH:mm") else null }
                                Text(mangaNumberText(listOfNotNull(pageCollectionLocation(entry), date).joinToString("  ·  ")),
                                    style = mangaMetricValueStyle(), color = colors.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            DesktopMoreButton(actions, "收藏画面操作")
                        }
                    }
                }
            }
        }
        Box(Modifier.matchParentSize()) {
            DesktopVerticalScrollbar(grid, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

private fun pageCollectionLocation(collection: PageCollection) = if (collection.spread)
    "第 ${collection.firstPage + 1}–${collection.lastPage + 1} 页 · 跨页" else "第 ${collection.firstPage + 1} 页"

@Composable
private fun PageCollectionRow(collection: PageCollection, repository: MangaRepository, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(collection.key) { MutableInteractionSource() }
    val savedAt = remember(collection.savedAt) {
        if (collection.savedAt <= 0L) null else formatInstantMillis(collection.savedAt, "M月d日 HH:mm")
    }
    val pages = pageCollectionLocation(collection)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val previewWidth = (maxWidth * .38f).coerceIn(64.dp, 164.dp)
        val previewHeight = (previewWidth * .80f).coerceIn(60.dp, 112.dp)
        Row(Modifier.fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = "阅读收藏画面", onClick = onClick)
            .motionPress(interaction, pressedScale = .99f)
            .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CollectionPreview(collection, repository,
                Modifier.width(previewWidth).height(previewHeight).mangaCoverCard(9.dp, shadowRadius = 5.dp)
                    .background(colors.surfaceContainerLow))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(collection.manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), color = colors.onSurface,
                    style = MaterialTheme.typography.titleMedium, fontSize = 16.sp, lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(collection.chapter.title, color = colors.onSurfaceVariant,
                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(mangaNumberText(pages), color = colors.primary,
                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (savedAt != null) Text(mangaNumberText(savedAt), color = colors.onSurfaceVariant,
                    fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CollectionPreview(collection: PageCollection, repository: MangaRepository, modifier: Modifier) {
    val context = LocalContext.current
    val owner = chapterSourceMangaId(collection.manga.id, collection.chapter)
    val script = remember(owner, collection.chapter.id) {
        SourceKeys.decodeChapter(owner, collection.chapter.id).sourceId.startsWith("js_")
    }
    var scriptPreviews by remember(repository, collection.key) { mutableStateOf<List<Page>?>(null) }
    LaunchedEffect(repository, collection.key, script) {
        if (!script) return@LaunchedEffect
        try {
            val downloaded = DownloadStore.get(context).downloadedPages(collection.manga.id, collection.chapter.id)
            val values = downloaded ?: repository.cachedPages(owner, collection.chapter.id)
                ?: repository.pages(owner, collection.chapter.id)
            scriptPreviews = (collection.firstPage..collection.lastPage).map { index ->
                val page = values.getOrNull(index) ?: error("已收藏的图片页码暂不可用。")
                materializeScriptPage(context, owner, collection.chapter.id,
                    resolveScriptPage(context, owner, collection.chapter.id, page))
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { scriptPreviews = emptyList() }
    }
    val headers = remember(repository, collection.manga.id, collection.manga.sourceUrl, collection.chapter.id,
        collection.chapter.sourceId, collection.chapter.sourceMangaId) {
        if (collection.manga.sourceUrl == LOCAL_SOURCE_URL) emptyMap()
        else repository.imageHeaders(dev.mangaflow.data.chapterSourceMangaId(collection.manga.id, collection.chapter), collection.chapter.id)
    }
    val slots = if (collection.spread) listOf(0, 1) else listOf(0)
    val order = if (collection.spread && collection.mode == ReadingMode.RIGHT_TO_LEFT) slots.reversed() else slots
    Row(modifier, horizontalArrangement = Arrangement.Absolute.Left, verticalAlignment = Alignment.CenterVertically) {
        order.forEach { slot ->
            val resolved = scriptPreviews?.getOrNull(slot)
            val url = if (script) resolved?.url else collection.previewUrls.getOrNull(slot)?.takeIf(String::isNotBlank)
            val pageNumber = collection.firstPage + slot + 1
            val request = remember(context, url, headers, resolved?.headers) {
                url?.let {
                    ImageRequest.Builder(imagePlatformContext(context)).data(it)
                        .sourceNetwork(dev.mangaflow.data.SourceKeys.decodeChapter(dev.mangaflow.data.chapterSourceMangaId(collection.manga.id, collection.chapter), collection.chapter.id).sourceId)
                        .httpHeaders(NetworkHeaders.Builder().apply {
                            headers.forEach { (name, value) -> set(name, value) }
                            resolved?.headers.orEmpty().forEach { (name, value) -> set(name, value) }
                        }.build()).crossfade(0).build()
                }
            }
            var failed by remember(request) { mutableStateOf(false) }
            Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
                if (request == null || failed) Text("预览不可用", fontSize = 10.sp, lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp), maxLines = 2)
                if (request != null) AsyncImage(model = request, contentDescription = "第 $pageNumber 页",
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    onError = { failed = true }, onSuccess = { failed = false })
            }
        }
    }
}
