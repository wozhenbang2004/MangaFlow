package dev.mangaflow.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.Page
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberDelayedBusy

internal data class VolumeCoverState(
    val page: Page? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val attempt: Int = 0,
)

/** Adapt the existing MangaCover/flat collection and ChapterTile actions to one actual volume. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VolumeBookTile(
    mangaId: String,
    chapter: Chapter,
    cover: VolumeCoverState,
    imageHeaders: () -> Map<String, String>,
    entry: DownloadEntry?,
    progress: ReadingProgress?,
    progressKnown: Boolean,
    current: Boolean,
    downloadMode: Boolean,
    enabled: Boolean,
    onRead: () -> Unit,
    onDownload: () -> Unit,
    onActions: () -> Unit,
    onCoverError: () -> Unit,
    onRetryCover: () -> Unit,
) {
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val interaction = remember(mangaId, chapter.id) { MutableInteractionSource() }
    val request = remember(mangaId, chapter.id, cover.page, cover.attempt, motion) {
        cover.page?.let { page ->
            ImageRequest.Builder(context).data(page.url).apply {
                if (!page.url.startsWith("file:")) httpHeaders(NetworkHeaders.Builder().apply {
                    imageHeaders().forEach { (name, value) -> set(name, value) }
                }.build())
            }.crossfade(if (motion) MangaMotion.Quick else 0).build()
        }
    }
    var imageLoading by remember(request) { mutableStateOf(request != null) }
    val busy = rememberDelayedBusy(cover.loading || imageLoading)
    Column(
        Modifier.fillMaxWidth().combinedClickable(
            interactionSource = interaction, indication = null, enabled = enabled,
            role = Role.Button,
            onClickLabel = if (downloadMode) "下载这一卷" else "阅读这一卷",
            onLongClickLabel = "单行本详情和操作",
            onClick = if (downloadMode) onDownload else onRead,
            onLongClick = onActions,
        ).motionPress(interaction, enabled = enabled).semantics {
            contentDescription = chapter.title + "，" + chapter.group
            stateDescription = listOf(chapterReadingStatus(progress, progressKnown), chapterStatus(entry, null))
                .filter(String::isNotBlank).joinToString("，")
        },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(.7f).mangaCoverCard()
            .background(DetailSurface), contentAlignment = Alignment.Center) {
            if (!busy && cover.error == null) Icon(Icons.AutoMirrored.Outlined.MenuBook, null,
                Modifier.size(25.dp), tint = DetailMuted.copy(alpha = .35f))
            if (request != null && cover.error == null) AsyncImage(
                model = request, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onLoading = { imageLoading = true },
                onSuccess = { imageLoading = false },
                onError = { imageLoading = false; onCoverError() },
            )
            if (cover.error != null) DetailAction(Icons.Outlined.Refresh, "重试这一卷封面", onClick = onRetryCover)
            else if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = DetailMuted, strokeWidth = 1.5.dp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 1.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(chapter.title, color = if (current) DetailForeground else DetailSecondary,
                fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.weight(1f))
            ChapterDownloadMark(entry, downloadMode)
        }
        ChapterReadingStatus(progress, progressKnown)
    }
}

