package dev.mangaflow.ui

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
import dev.mangaflow.storage.LibraryStore
import dev.mangaflow.storage.ReaderCatalogStore
import dev.mangaflow.storage.ReadingProgress
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var records by remember(manga.id) { mutableStateOf<List<ReadingProgress>?>(null) }
    var failed by remember(manga.id) { mutableStateOf(false) }
    var retry by remember(manga.id) { mutableStateOf(0) }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(manga.id, revision, retry) {
        failed = false
        try {
            records = withContext(Dispatchers.IO) {
                val chapters = ReaderCatalogStore(context).read(manga.id)?.chapters.orEmpty()
                val titles = chapters.associate { it.id to it.title }
                val order = chapters.associate { it.id to it.index }
                library.chapterHistoryFor(manga).map { record ->
                    val title = record.chapterTitle.takeUnless { it.isBlank() || it == "正在阅读" }
                        ?: titles[record.chapterId].orEmpty().takeUnless { it == "正在阅读" }.orEmpty()
                    record.copy(chapterTitle = title)
                }.sortedWith(compareByDescending<ReadingProgress> { it.updatedAt }
                    .thenByDescending { order[it.chapterId] ?: -1 }.thenBy { it.chapterTitle })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = if (onBack == null) 24.dp else 12.dp, end = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) MotionIconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回最近阅读", Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("章节记录", fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                Text(manga.title, fontSize = 12.sp, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            records?.let { Text(it.size.toString(), color = secondary, fontSize = 12.sp) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 16.dp)) {
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
            items(records.orEmpty(), key = { it.chapterId }) { progress ->
                val interaction = remember(progress.chapterId) { MutableInteractionSource() }
                Row(Modifier.fillMaxWidth().clickable(role = Role.Button,
                    interactionSource = interaction, indication = null,
                    onClickLabel = "从保存的位置继续阅读", onClick = { onContinue(progress) })
                    .motionPress(interaction, pressedScale = .985f)
                    .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(progress.chapterTitle.ifBlank { "已保存的章节" }, fontSize = 14.sp, lineHeight = 20.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${progress.pageIndex + 1}/${progress.pageCount} 页 · ${historyTime(progress.updatedAt)}",
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
