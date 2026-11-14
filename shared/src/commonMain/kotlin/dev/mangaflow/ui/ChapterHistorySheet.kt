package dev.mangaflow.ui

import dev.mangaflow.typography.MangaFontScene

import kotlinx.coroutines.IO

import dev.mangaflow.ui.platform.*

import androidx.compose.foundation.clickable
import dev.mangaflow.ui.motion.motionPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import dev.mangaflow.ui.motion.MotionBusyIndicator as CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import dev.mangaflow.ui.motion.MotionTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mangaflow.data.Manga
import dev.mangaflow.data.SourceChapterRef
import dev.mangaflow.data.SourceKeys
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.storage.DownloadStore.Companion.LOCAL_SOURCE_URL
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReaderCatalogStore
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class ChapterHistoryRow(
    val progress: ReadingProgress,
    val source: SourceChapterRef?,
    val sourceName: String,
)

/** Exact native identity only: titles and page counts never establish equivalent records. */
private fun historyChapterSource(manga: Manga, chapterId: String): SourceChapterRef? = runCatching {
    when {
        SourceKeys.isEncoded(chapterId) -> SourceKeys.decodeChapter(manga.id, chapterId)
        manga.sourceUrl == LOCAL_SOURCE_URL -> SourceChapterRef("local", manga.id, chapterId)
        SourceKeys.isEncoded(manga.id) -> SourceKeys.decodeChapter(manga.id, chapterId)
        else -> SourceChapterRef(manga.sourceId, manga.sourceMangaId.ifBlank { manga.id }, chapterId)
    }
}.getOrNull()

/** The existing sheet/list language, backed solely by local chapter positions and cached titles. */
@Composable
internal fun ChapterHistorySheet(manga: Manga, library: LibraryStore,
    onDismiss: () -> Unit, onContinue: (ReadingProgress) -> Unit) {
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background) { dismiss ->
        ChapterHistoryContent(manga, library, onContinue = { onContinue(it); dismiss() })
    }
}

@Composable
internal fun ChapterHistoryContent(manga: Manga, library: LibraryStore,
    onContinue: (ReadingProgress) -> Unit, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current.applicationContext
    val revision by library.chapterHistoryRevision.collectAsStateWithLifecycle()
    var records by remember(manga.id) { mutableStateOf<List<ChapterHistoryRow>?>(null) }
    var failed by remember(manga.id) { mutableStateOf(false) }
    var retry by remember(manga.id) { mutableStateOf(0) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val repeatedTitles = remember(records) {
        records.orEmpty().groupingBy { it.progress.chapterTitle }.eachCount().filterValues { it > 1 }.keys
    }
    LaunchedEffect(manga.id, revision, retry) {
        failed = false
        try {
            records = withContext(Dispatchers.IO) {
                val chapters = ReaderCatalogStore(context).read(manga.id)?.chapters.orEmpty()
                val titles = buildMap {
                    chapters.forEach { chapter ->
                        put(chapter.id, chapter.title)
                        chapter.variants.forEach { put(it.storageId, it.title) }
                    }
                }
                val order = buildMap {
                    chapters.forEach { chapter ->
                        put(chapter.id, chapter.index)
                        chapter.variants.forEach { put(it.storageId, chapter.index) }
                    }
                }
                val sources = buildMap {
                    put(manga.sourceId, manga.sourceName.ifBlank { sourceDisplayName(manga.sourceId) })
                    chapters.forEach { chapter -> chapter.variants.forEach { variant ->
                        if (variant.sourceName.isNotBlank()) put(variant.sourceId, variant.sourceName)
                    } }
                }
                val identities = buildMap<String, SourceChapterRef> {
                    chapters.forEach { chapter -> chapter.variants.forEach { variant ->
                        put(variant.storageId, SourceChapterRef(variant.sourceId, variant.mangaId, variant.chapterId))
                    } }
                }
                library.chapterHistoryFor(manga).map { record ->
                    val title = record.chapterTitle.takeUnless { it.isBlank() || it == "正在阅读" }
                        ?: titles[record.chapterId].orEmpty().takeUnless { it == "正在阅读" }.orEmpty()
                    val source = identities[record.chapterId] ?: historyChapterSource(manga, record.chapterId)
                    ChapterHistoryRow(record.copy(chapterTitle = title), source,
                        source?.sourceId?.let { sources[it] ?: sourceDisplayName(it) } ?: "来源未确认")
                }.sortedWith(compareByDescending<ChapterHistoryRow> { it.progress.updatedAt }
                    .thenByDescending { order[it.progress.chapterId] ?: -1 }.thenBy { it.progress.chapterTitle })
                    // Keep the latest exact-source position without rewriting any original cursor.
                    .distinctBy { it.source ?: it.progress.chapterId }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 16.dp else 8.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) MotionIconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回最近阅读", Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("章节记录", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), fontSize = 12.sp, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            records?.let { Text(it.size.toString(), color = secondary, fontSize = 12.sp) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            if (failed) item(key = "retry") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("章节记录暂未读取", fontSize = 13.sp, color = secondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { retry++ }) { Text("重试") }
                }
            } else if (records == null) item(key = "loading") {
                CircularProgressIndicator(Modifier.padding(vertical = 16.dp).size(20.dp), strokeWidth = 1.5.dp)
            } else if (records.orEmpty().isEmpty()) item(key = "empty") {
                Text("还没有章节记录", Modifier.padding(vertical = 24.dp), color = secondary, fontSize = 13.sp)
            }
            items(records.orEmpty(), key = { it.progress.chapterId }) { row ->
                val progress = row.progress
                val interaction = remember(progress.chapterId) { MutableInteractionSource() }
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                    interactionSource = interaction, indication = null,
                    onClickLabel = "从保存的位置继续阅读", onClick = { onContinue(progress) })
                    .motionPress(interaction, pressedScale = .985f)
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(progress.chapterTitle.ifBlank { "已保存的章节" }, fontSize = 14.sp, lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val pageLabel = if (progress.pageCount > 0) "${progress.pageIndex + 1}/${progress.pageCount} 页"
                            else "第 ${progress.pageIndex + 1} 页"
                        val sourceLabel = if (progress.chapterTitle in repeatedTitles) row.sourceName + " · " else ""
                        Text(mangaNumberText("$sourceLabel$pageLabel · ${historyTime(progress.updatedAt)}"),
                            fontSize = 12.sp, lineHeight = 16.sp, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp), tint = secondary)
                }
            }
        }
    }
}

private fun historyTime(time: Long): String {
    if (time <= 0L) return "早期记录"
    val format = if (localDateAt(time).year == localToday().year) "MM-dd HH:mm" else "yyyy-MM-dd"
    return formatInstantMillis(time, format)
}
