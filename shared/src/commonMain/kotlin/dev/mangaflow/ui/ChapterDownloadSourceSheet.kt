package dev.mangaflow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
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

/** The exact available variants fan out at the chapter, with one-tap queue semantics. */
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
    var inspected by remember(manga.id, chapter.logicalId, chapter.id) { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    ChapterSourceOrbit("download:${manga.id}:${chapter.logicalId.ifBlank { chapter.id }}",
        anchor, backdrop, variants.size, onDismiss,
        hasStatus = variants.isEmpty() || inspected != null,
        status = {
            if (variants.isEmpty()) Text("本章暂无可用来源，更新目录后重试。",
                color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            inspected?.let { Text(it, color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp) }
        }) { index, modifier, dismiss ->
        val variant = variants[index]
        val entry = entries[variant.storageId]
        val quality = remember(variant.storageId, variant.pageCount, entry?.id,
            entry?.totalPages, entry?.completedPages, entry?.status) {
            val retained = entry?.let { downloads.record(it.id) }
                ?.takeIf { it.entry.chapter.id == variant.storageId }?.pages.orEmpty()
            val samples = retained.take(CHAPTER_DIMENSION_SAMPLE_LIMIT).map { page ->
                // Keep the actual in-memory sample contract; no image or file request is added.
                ReaderDimensionCache.get(page.url)?.let { size ->
                    page.copy(width = size.width, height = size.height)
                } ?: page
            }
            chapterQualityInfo(samples, entry?.totalPages?.takeIf { it > 0 } ?: variant.pageCount)
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
        val edition = listOfNotNull(
            variant.language.takeIf { sameSource && it.isNotBlank() },
            variant.edition.takeIf { sameSource && it.isNotBlank() },
            variant.scanlator.takeIf { sameSource && it.isNotBlank() },
            variant.title.takeIf { sameSource && it.isNotBlank() && it != chapter.title },
        ).distinct().joinToString(" · ")
        val details = listOf(edition, status, qualityText).filter(String::isNotBlank).joinToString(" · ")
        val secondary = when {
            sameSource -> listOf(variant.language, variant.edition, variant.scanlator)
                .firstOrNull(String::isNotBlank).orEmpty()
            status.isNotBlank() -> status
            else -> quality.totalPages?.let { "$it 页" }.orEmpty()
        }
        ChapterSourceDisc(variant.copy(sourceName = name), backdrop, modifier,
            selected = variant.storageId == chapter.id, enabled = enabled,
            details = details, secondary = secondary, entry = entry,
            actionLabel = "从${name}下载${chapter.title}",
            onInspect = { inspected = "$name · $details" },
            onClick = {
                if (!submitted) {
                    submitted = true
                    onSelect(variant)
                    dismiss()
                }
            })
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
