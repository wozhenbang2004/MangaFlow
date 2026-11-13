package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("画面收藏", style = MaterialTheme.typography.titleMedium)
            Text(collections.size.toString(), modifier = Modifier.padding(start = 8.dp).weight(1f),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, "关闭画面收藏", Modifier.size(20.dp), tint = colors.onSurfaceVariant)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(),
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

@Composable
private fun PageCollectionRow(collection: PageCollection, repository: MangaRepository, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember(collection.key) { MutableInteractionSource() }
    val savedAt = remember(collection.savedAt) {
        if (collection.savedAt <= 0L) null else formatInstantMillis(collection.savedAt, "M月d日 HH:mm")
    }
    val pages = if (collection.spread) "第 ${collection.firstPage + 1}–${collection.lastPage + 1} 页 · 跨页"
        else "第 ${collection.firstPage + 1} 页"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val previewWidth = (maxWidth * .28f).coerceIn(72.dp, 104.dp)
        Row(Modifier.fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                onClickLabel = "阅读收藏画面", onClick = onClick)
            .motionPress(interaction, pressedScale = .99f)
            .padding(horizontal = 4.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CollectionPreview(collection, repository,
                Modifier.width(previewWidth).height(76.dp).mangaCoverCard(6.dp)
                    .background(colors.onSurface.copy(alpha = .035f)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(collection.manga.title, color = colors.onSurface,
                    style = MaterialTheme.typography.titleSmall, fontSize = 13.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(collection.chapter.title, color = colors.onSurfaceVariant,
                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pages, color = colors.onSurfaceVariant,
                    fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (savedAt != null) Text(savedAt, color = colors.onSurfaceVariant.copy(alpha = .75f),
                    fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CollectionPreview(collection: PageCollection, repository: MangaRepository, modifier: Modifier) {
    val context = LocalContext.current
    val headers = remember(repository, collection.manga.id, collection.manga.sourceUrl, collection.chapter.id,
        collection.chapter.sourceId, collection.chapter.sourceMangaId) {
        if (collection.manga.sourceUrl == LOCAL_SOURCE_URL) emptyMap()
        else repository.imageHeaders(dev.mangaflow.data.chapterSourceMangaId(collection.manga.id, collection.chapter), collection.chapter.id)
    }
    val slots = if (collection.spread) listOf(0, 1) else listOf(0)
    val order = if (collection.spread && collection.mode == ReadingMode.RIGHT_TO_LEFT) slots.reversed() else slots
    Row(modifier, horizontalArrangement = Arrangement.Absolute.Left, verticalAlignment = Alignment.CenterVertically) {
        order.forEach { slot ->
            val url = collection.previewUrls.getOrNull(slot)?.takeIf(String::isNotBlank)
            val pageNumber = collection.firstPage + slot + 1
            val request = remember(context, url, headers) {
                url?.let {
                    ImageRequest.Builder(imagePlatformContext(context)).data(it)
                        .httpHeaders(NetworkHeaders.Builder().apply {
                            headers.forEach { (name, value) -> set(name, value) }
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
