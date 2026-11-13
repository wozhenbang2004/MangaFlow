package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress

/** Selecting a real source is the only action that requests a new download. */
@Composable
internal fun ChapterDownloadSourceSheet(
    manga: Manga,
    chapter: Chapter,
    entries: Map<String, DownloadEntry>,
    backdrop: HazeState,
    onSelect: (ChapterVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    val variants = remember(manga.id, manga.sourceName, chapter) {
        downloadSourceVariants(manga, chapter)
    }
    var submitted by remember(manga.id, chapter.logicalId, chapter.id) { mutableStateOf(false) }
    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("选择下载来源", color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(chapter.title, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, lineHeight = 18.sp, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, "取消下载", modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (variants.isEmpty()) {
                Text("本章暂时没有可用的下载来源。请更新目录后重试。",
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp, lineHeight = 20.sp)
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(variants, key = { it.storageId }) { variant ->
                        val entry = entries[variant.storageId]
                        val canStart = entry?.status !in setOf(DownloadStatus.COMPLETED,
                            DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
                        val enabled = canStart && !submitted
                        val current = variant.storageId == chapter.id
                        val name = variant.sourceName.ifBlank { sourceDisplayName(variant.sourceId) }
                        val status = when (entry?.status) {
                            DownloadStatus.COMPLETED -> "已下载"
                            DownloadStatus.QUEUED -> "已排队"
                            DownloadStatus.RUNNING -> entry?.takeIf { it.totalPages > 0 }
                                ?.let { "下载中 ${it.completedPages}/${it.totalPages}" } ?: "下载中"
                            DownloadStatus.WAITING -> "等待下载"
                            DownloadStatus.PAUSED -> "继续下载"
                            DownloadStatus.FAILED -> "重试下载"
                            null -> ""
                        }
                        val details = listOfNotNull(
                            variant.title.takeIf { it.isNotBlank() && it != chapter.title },
                            variant.language.takeIf(String::isNotBlank),
                            variant.edition.takeIf { value -> value.isNotBlank() &&
                                variants.map { it.edition }.distinct().size > 1 },
                            variant.scanlator.takeIf(String::isNotBlank),
                            "当前来源".takeIf { current },
                        ).distinct().joinToString(" · ")
                        val interaction = remember(variant.storageId) { MutableInteractionSource() }
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
                            .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                                role = Role.Button, onClickLabel = "从${name}下载${chapter.title}",
                                onClick = {
                                    if (!submitted) {
                                        submitted = true
                                        onSelect(variant)
                                        dismiss()
                                    }
                                })
                            .motionPress(interaction, enabled = enabled, pressedScale = .99f)
                            .semantics { stateDescription = listOf(details, status)
                                .filter(String::isNotBlank).joinToString("，") }
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            BodySourceMark(variant.sourceId, name, Modifier.size(22.dp), muted = !canStart)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(name, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canStart) 1f else .6f),
                                    fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (details.isNotBlank()) Text(details,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(10.dp))
                            if (status.isNotBlank()) Text(status,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2,
                                modifier = Modifier.width(68.dp), overflow = TextOverflow.Ellipsis)
                            else Icon(Icons.Outlined.Download, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun downloadSourceVariants(manga: Manga, chapter: Chapter): List<ChapterVariant> {
    if (chapter.variants.isNotEmpty()) return chapter.variants.filter { variant ->
        runCatching { variant.storageId.isNotBlank() }.getOrDefault(false)
    }.distinctBy { it.storageId }
    val ref = runCatching {
        SourceKeys.decodeChapter(chapterSourceMangaId(manga.id, chapter), chapter.id)
    }.getOrNull() ?: return emptyList()
    val source = if (chapter.sourceId == "local") "local" else ref.sourceId
    val variant = ChapterVariant(
        sourceId = source,
        sourceName = manga.sourceName.takeIf { source == manga.sourceId && it.isNotBlank() }
            ?: sourceDisplayName(source),
        mangaId = chapter.sourceMangaId.ifBlank { ref.mangaId },
        chapterId = chapter.sourceChapterId.ifBlank { ref.chapterId },
        title = chapter.title,
        language = "", edition = "",
    )
    return if (runCatching { variant.storageId == chapter.id }.getOrDefault(false)) listOf(variant) else emptyList()
}
