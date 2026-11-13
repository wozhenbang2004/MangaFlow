package dev.mangaflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.ChapterQualityInfo
import dev.mangaflow.data.CHAPTER_DIMENSION_SAMPLE_LIMIT
import dev.mangaflow.data.chapterQualityInfo
import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.DownloadStore
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.reader.ReaderDimensionCache
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress

/** Each actual variant is one immediate download action in a compact anchored choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChapterDownloadSourceSheet(
    manga: Manga,
    chapter: Chapter,
    entries: Map<String, DownloadEntry>,
    backdrop: HazeState,
    onSelect: (ChapterVariant) -> Unit,
    onDismiss: () -> Unit,
    anchor: Rect? = null,
) {
    val context = LocalMangaContext.current
    val downloads = remember(context) { DownloadStore.get(context) }
    val variants = remember(manga.id, manga.sourceName, chapter) { downloadSourceVariants(manga, chapter) }
    var submitted by remember(manga.id, chapter.logicalId, chapter.id) { mutableStateOf(false) }
    val columns = variants.size.coerceIn(1, 3)
    val width = (columns * 64 + (columns - 1) * 4 + 12).dp
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    ChapterChoicePopover("download:${manga.id}:${chapter.logicalId.ifBlank { chapter.id }}",
        anchor, backdrop, onDismiss, maximumWidth = if (variants.isEmpty()) 212.dp else width) { dismiss, reveal ->
        Column(Modifier.fillMaxWidth()) {
            if (variants.size != 1) Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(chapter.title, color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "取消下载", Modifier.size(14.dp), tint = colors.onSurfaceVariant)
                }
            }
            if (variants.isEmpty()) Text("本章暂无可用来源，更新目录后重试。",
                Modifier.fillMaxWidth().padding(8.dp), color = colors.onSurfaceVariant,
                fontSize = 12.sp, lineHeight = 18.sp)
            else FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = 3,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                variants.forEachIndexed { index, variant ->
                    val entry = entries[variant.storageId]
                    val quality = remember(variant.storageId, variant.pageCount, entry?.id,
                        entry?.totalPages, entry?.completedPages, entry?.status) {
                        val retained = entry?.let { downloads.record(it.id) }
                            ?.takeIf { it.entry.chapter.id == variant.storageId }?.pages.orEmpty()
                        val samples = retained.take(CHAPTER_DIMENSION_SAMPLE_LIMIT).map { page ->
                            // In-memory header dimensions only; this never opens a file or image request.
                            ReaderDimensionCache.get(page.url)?.let { size ->
                                page.copy(width = size.width, height = size.height)
                            } ?: page
                        }
                        chapterQualityInfo(samples,
                            entry?.totalPages?.takeIf { it > 0 } ?: variant.pageCount)
                    }
                    val qualityText = chapterQualityLabel(quality)
                    val canStart = entry?.status !in setOf(DownloadStatus.COMPLETED,
                        DownloadStatus.QUEUED, DownloadStatus.RUNNING, DownloadStatus.WAITING)
                    val enabled = canStart && !submitted
                    val name = variant.sourceName.ifBlank { sourceDisplayName(variant.sourceId) }
                    val status = when (entry?.status) {
                        DownloadStatus.COMPLETED -> "已下载"
                        DownloadStatus.QUEUED -> "已排队"
                        DownloadStatus.RUNNING -> entry?.takeIf { it.totalPages > 0 }
                            ?.let { "${it.completedPages}/${it.totalPages}" } ?: "下载中"
                        DownloadStatus.WAITING -> "等待中"
                        DownloadStatus.PAUSED -> "继续"
                        DownloadStatus.FAILED -> "重试"
                        null -> ""
                    }
                    val sameSource = variants.count { it.sourceId == variant.sourceId } > 1
                    val details = listOfNotNull(
                        variant.language.takeIf { sameSource && it.isNotBlank() },
                        variant.edition.takeIf { sameSource && it.isNotBlank() },
                        variant.scanlator.takeIf { sameSource && it.isNotBlank() },
                        variant.title.takeIf { sameSource && it.isNotBlank() && it != chapter.title },
                    ).distinct().joinToString(" · ")
                    val interaction = remember(variant.storageId) { MutableInteractionSource() }
                    Column(Modifier.width(64.dp * fontScale.coerceAtMost(1.35f)).heightIn(min = 68.dp * fontScale)
                        .graphicsLayer {
                            val start = index.coerceAtMost(3) * .07f
                            val p = ((reveal() - start) / (1f - start)).coerceIn(0f, 1f)
                            alpha = p
                            translationY = (1f - p) * 8.dp.toPx()
                            scaleX = .92f + .08f * p; scaleY = scaleX
                        }.clip(RoundedCornerShape(12.dp))
                        .background(colors.onSurface.copy(alpha = if (enabled) .045f else .018f))
                        .clickable(enabled = enabled, interactionSource = interaction, indication = null,
                            role = Role.Button, onClickLabel = "从${name}下载${chapter.title}", onClick = {
                                if (!submitted) {
                                    submitted = true
                                    onSelect(variant)
                                    dismiss()
                                }
                            }).motionPress(interaction, enabled = enabled, pressedScale = .94f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${name}，${chapter.title}，下载"
                            stateDescription = listOf(details, status, qualityText,
                                "当前来源".takeIf { variant.storageId == chapter.id }.orEmpty())
                                .filter(String::isNotBlank).joinToString("，")
                        }.padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            BodySourceMark(variant.sourceId, name, Modifier.size(21.dp), muted = !canStart)
                        }
                        Text(name, color = colors.onSurface.copy(alpha = if (canStart) .92f else .48f),
                            fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (details.isNotBlank()) Text(details, color = colors.onSurfaceVariant,
                            fontSize = 9.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(qualityText, color = colors.onSurfaceVariant.copy(alpha = .82f),
                            fontSize = 9.sp, lineHeight = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (status.isNotBlank()) Text(status, color = colors.onSurfaceVariant,
                            fontSize = 9.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        else Icon(Icons.Outlined.Download, null, Modifier.size(12.dp),
                            tint = colors.onSurfaceVariant.copy(alpha = .7f))
                    }
                }
            }
        }
    }
}

private fun chapterQualityLabel(info: ChapterQualityInfo): String {
    val count = info.totalPages?.let { "$it 页 · " }.orEmpty()
    if (info.dimensionPages == 0) return count + "尺寸未知"
    fun range(minimum: Int?, maximum: Int?): String =
        if (minimum == maximum) minimum.toString() else "$minimum–$maximum"
    val dimensions = "${range(info.minWidth, info.maxWidth)} × ${range(info.minHeight, info.maxHeight)} px"
    val coverage = info.totalPages?.let { "${info.dimensionPages}/$it 页" }
        ?: "${info.dimensionPages} 页，整章页数未知"
    return "样本 $dimensions · 覆盖 $coverage"
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
