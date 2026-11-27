package dev.mangaflow.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionButton
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.Chapter
import dev.mangaflow.data.ChapterVariant
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.chapterSourceMangaId
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.net.DownloadCategory
import dev.mangaflow.net.DownloadRouteMode
import dev.mangaflow.storage.DownloadEntry
import dev.mangaflow.storage.DownloadStatus
import dev.mangaflow.storage.ChapterPreparationPhase
import dev.mangaflow.storage.ChapterPreparationState

/** The exact available variants fan out at the chapter, with one-tap queue semantics. */
@Composable
internal fun ChapterDownloadSourceSheet(
    manga: Manga,
    chapter: Chapter,
    entries: Map<String, DownloadEntry>,
    backdrop: HazeState,
    onSelect: (ChapterVariant, DownloadRouteMode?) -> Unit,
    onDismiss: () -> Unit,
    anchor: Rect? = null,
    repository: MangaRepository? = null,
    preparations: Map<String, ChapterPreparationState> = emptyMap(),
) {
    val variants = remember(manga.id, manga.sourceName, chapter) { downloadSourceVariants(manga, chapter) }
    var submitted by remember(manga.id, chapter.logicalId, chapter.id) { mutableStateOf(false) }
    var inspected by remember(manga.id, chapter.logicalId, chapter.id) { mutableStateOf<String?>(null) }
    var route by remember(manga.id, chapter.logicalId, chapter.id) { mutableStateOf<DownloadRouteMode?>(null) }
    val colors = MaterialTheme.colorScheme
    ChapterSourceOrbit("download:${manga.id}:${chapter.logicalId.ifBlank { chapter.id }}",
        "下载来源", chapter.title, anchor, backdrop, variants.size, onDismiss,
        hasStatus = true,
        status = { routeActive ->
            TaskDownloadRouteControl(DownloadCategory.CHAPTERS, route, { route = it },
                enabled = routeActive && !submitted, backdrop = backdrop)
            if (variants.isEmpty()) Text("本章暂无可用来源，更新目录后重试。",
                color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp)
            inspected?.let { Text(it, color = colors.onSurfaceVariant, fontSize = 11.sp, lineHeight = 17.sp) }
        }) { index, modifier, dismiss, reveal, sourceActive ->
        val variant = variants[index]
        val entry = entries[variant.storageId]
        val preparation = preparations[variant.storageId]
        val preparing = preparation != null && preparation.phase != ChapterPreparationPhase.FAILED
        val sourceMangaId = remember(variant.sourceId, variant.mangaId) {
            runCatching { SourceKeys.manga(variant.sourceId, variant.mangaId) }.getOrNull()
        }
        val resolution = rememberChapterResolution(
            repository = repository.takeIf { sourceMangaId != null },
            mangaId = sourceMangaId.orEmpty(), chapterId = variant.storageId,
            entry = entry, declaredPages = variant.pageCount,
            autoMeasure = true, active = sourceActive)
        val enabled = chapterDownloadAvailable(entry, preparation) && !submitted
        val name = variant.sourceName.ifBlank { sourceDisplayName(variant.sourceId) }
        val status = chapterDownloadLabel(entry, preparation)
        val peers = variants.filter { it.sourceId == variant.sourceId }
        val sameSource = peers.size > 1
        val edition = listOfNotNull(
            variant.language.takeIf { sameSource && it.isNotBlank() },
            variant.edition.takeIf { sameSource && it.isNotBlank() },
            variant.scanlator.takeIf { sameSource && it.isNotBlank() },
            variant.title.takeIf { sameSource && it.isNotBlank() && it != chapter.title },
        ).distinct().joinToString(" · ")
        val details = listOf(edition, status, resolution.detail).filter(String::isNotBlank).joinToString(" · ")
        val secondary = when {
            sameSource -> listOf(chapterVariantDistinction(variant, peers), status)
                .filter(String::isNotBlank).joinToString(" · ")
            status.isNotBlank() -> status
            else -> (entry?.totalPages?.takeIf { it > 0 } ?: variant.pageCount)?.let { "$it 页" }.orEmpty()
        }
        ChapterSourceDisc(variant.copy(sourceName = name), backdrop, modifier,
            selected = variant.storageId == chapter.id, enabled = enabled,
            details = details, secondary = secondary, entry = entry, resolution = resolution,
            revealProgress = reveal,
            actionLabel = "从${name}下载${chapter.title}",
            onInspect = { inspected = "$name · $details" },
            onClick = {
                if (!submitted && !preparing) {
                    submitted = true
                    onSelect(variant, route)
                    dismiss()
                }
            })
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

private fun chapterDownloadAvailable(entry: DownloadEntry?, preparation: ChapterPreparationState?): Boolean =
    (preparation == null || preparation.phase == ChapterPreparationPhase.FAILED) && when (entry?.status) {
        DownloadStatus.COMPLETED, DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.WAITING -> false
        else -> true
    }

private fun chapterDownloadLabel(entry: DownloadEntry?, preparation: ChapterPreparationState?): String =
    when (preparation?.phase) {
        ChapterPreparationPhase.WAITING -> "等待准备"
        ChapterPreparationPhase.PREPARING -> "准备中"
        ChapterPreparationPhase.COMMITTING -> "加入下载中"
        ChapterPreparationPhase.FAILED -> "准备失败 · 重试"
        null -> when (entry?.status) {
            DownloadStatus.COMPLETED -> "已下载"
            DownloadStatus.QUEUED -> "已排队"
            DownloadStatus.RUNNING -> entry?.takeIf { it.totalPages > 0 }?.let { "下载中 ${it.completedPages}/${it.totalPages}" } ?: "下载中"
            DownloadStatus.WAITING -> "等待中"
            DownloadStatus.PAUSED -> "可继续"
            DownloadStatus.FAILED -> "下载失败 · 重试"
            null -> ""
        }
    }

private data class ChapterDownloadChoice(val chapter: Chapter, val variants: List<ChapterVariant>, val current: ChapterVariant?)

@Composable
internal fun ChapterBatchDownloadSheet(
    manga: Manga, chapters: List<Chapter>, entries: Map<String, DownloadEntry>, preparations: Map<String, ChapterPreparationState>,
    backdrop: HazeState, onSubmit: (List<Pair<Chapter, ChapterVariant>>, DownloadRouteMode?) -> Unit, onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var route by remember(manga.id) { mutableStateOf<DownloadRouteMode?>(null) }
    var chosenVariants by remember(manga.id) { mutableStateOf(mapOf<String, String>()) }
    var selected by remember(manga.id) { mutableStateOf(setOf<String>()) }
    var submitted by remember(manga.id) { mutableStateOf(false) }
    val list = rememberLazyListState()
    val options = remember(manga.id, manga.sourceName, chapters) { chapters.map { it to downloadSourceVariants(manga, it) } }
    val choices = remember(options, chosenVariants) {
        options.map { (chapter, variants) ->
            val preferred = chosenVariants[chapter.id]
            val exact = if (preferred != null) variants.firstOrNull { it.storageId == preferred }
                else variants.firstOrNull { it.storageId == chapter.id } ?: variants.singleOrNull()
            ChapterDownloadChoice(chapter, variants, exact)
        }
    }
    val eligible = remember(choices, entries, preparations) {
        choices.mapNotNull { row -> row.current?.takeIf {
            chapterDownloadAvailable(entries[it.storageId], preparations[it.storageId])
        }?.let { row.chapter to it } }.distinctBy { it.second.storageId }
    }
    val picked = remember(eligible, selected) { eligible.filter { it.second.storageId in selected } }
    val allIds = remember(eligible) { eligible.mapTo(linkedSetOf()) { it.second.storageId } }
    MotionBottomSheet(onDismissRequest = onDismiss, desktopWidth = 704.dp, backdrop = backdrop,
        containerColor = colors.surface, contentColor = colors.onSurface) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = 674.dp).padding(horizontal = 16.dp, vertical = 9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("多选下载", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(manga.title, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MotionIconButton(onClick = dismiss, modifier = Modifier.size(36.dp), tooltip = "关闭多选下载") {
                    Icon(Icons.Outlined.Close, "关闭多选下载", Modifier.size(18.dp))
                }
            }
            Box(Modifier.weight(1f, fill = false).fillMaxWidth().onPreviewKeyEvent { event ->
                if (!submitted && event.type == KeyEventType.KeyDown && event.key == Key.A && event.isCtrlPressed) {
                    selected = allIds
                    true
                } else false
            }) {
                LazyColumn(state = list, contentPadding = PaddingValues(top = 6.dp, end = 14.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item("route") {
                        TaskDownloadRouteControl(DownloadCategory.CHAPTERS, route, { route = it },
                            enabled = !submitted, backdrop = backdrop)
                    }
                    item("scope") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("目录中 ${choices.size} 章", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant)
                            val allChosen = picked.isNotEmpty() && picked.size == allIds.size
                            MotionTextButton(onClick = { selected = if (allChosen) emptySet() else allIds },
                                enabled = !submitted && eligible.isNotEmpty()) {
                                Text(if (allChosen) "清除选择" else "全选可下载章节")
                            }
                        }
                    }
                    items(choices, key = { it.chapter.id }) { row ->
                        val variant = row.current
                        val id = variant?.storageId
                        val checked = id != null && id in selected && id in allIds
                        val available = !submitted && id != null && id in allIds
                        val interaction = remember(row.chapter.id) { MutableInteractionSource() }
                        val source = variant?.let { it.sourceName.ifBlank { sourceDisplayName(it.sourceId) } }
                        val edition = variant?.let { item -> chapterVariantDistinction(item, row.variants.filter { it.sourceId == item.sourceId }) }
                        val status = if (id == null) "" else chapterDownloadLabel(entries[id], preparations[id])
                        val message = listOfNotNull(source, edition?.takeIf(String::isNotBlank), status.takeIf(String::isNotBlank))
                            .joinToString(" · ").ifBlank { if (row.variants.isEmpty()) "暂无来源" else "请选择具体版本" }
                        val fill by animateColorAsState(colors.onSurface.copy(alpha = if (checked) .075f else .028f),
                            if (LocalMotionEnabled.current) tween(MangaMotion.Quick) else snap(), label = "chapter-batch-selection")
                        Row(Modifier.fillMaxWidth().background(fill, RoundedCornerShape(11.dp)).padding(end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).toggleable(value = checked, enabled = available,
                                interactionSource = interaction, indication = null, role = Role.Checkbox, onValueChange = { on ->
                                    if (id != null) selected = if (on) selected + id else selected - id
                                }).motionPress(interaction, enabled = available, pressedScale = .995f)
                                .heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                Box(Modifier.size(19.dp).background(if (checked) colors.primary else
                                    colors.onSurface.copy(alpha = if (available) .11f else .045f), RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center) {
                                    if (checked) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = colors.onPrimary)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(row.chapter.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (row.variants.size > 1) {
                                var open by remember(row.chapter.id) { mutableStateOf(false) }
                                val actions = row.variants.map { candidate ->
                                    val other = candidate.storageId
                                    val qualified = chapterVariantDistinction(candidate, row.variants.filter { it.sourceId == candidate.sourceId })
                                    val info = chapterDownloadLabel(entries[other], preparations[other])
                                    val label = listOf(candidate.sourceName.ifBlank { sourceDisplayName(candidate.sourceId) }, qualified, info)
                                        .filter(String::isNotBlank).joinToString(" · ")
                                    DesktopMenuAction(label, enabled = !submitted && chapterDownloadAvailable(entries[other], preparations[other]),
                                        selected = other == id) {
                                        chosenVariants = chosenVariants + (row.chapter.id to other)
                                        if (checked && id != null) selected = selected - id + other
                                    }
                                }
                                Box {
                                    MotionIconButton(onClick = { open = true }, enabled = !submitted, modifier = Modifier.size(36.dp),
                                        tooltip = "为${row.chapter.title}选择来源版本") {
                                        Icon(Icons.Outlined.KeyboardArrowDown, "选择来源版本", Modifier.size(18.dp))
                                    }
                                    DesktopActionPopup(open && !submitted, actions, { open = false })
                                }
                            }
                        }
                    }
                }
                Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                    DesktopVerticalScrollbar(list, Modifier.fillMaxHeight())
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已选 ${picked.size} 章", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant)
                MotionButton(onClick = {
                    if (!submitted && picked.isNotEmpty()) {
                        submitted = true
                        onSubmit(picked, route)
                        dismiss()
                    }
                }, enabled = !submitted && picked.isNotEmpty()) { Text("加入下载") }
            }
        }
    }
}
