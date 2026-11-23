package dev.mangaflow.anime.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.AnimeLibraryEntry
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.ui.CompactMenuAction
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionIconButton

/** RecentReadingSheet's compact, task-first list; navigation waits for the shared sheet's exit. */
@Composable
internal fun AnimeRecentWatchingSheet(
    entries: List<AnimeLibraryEntry>,
    unavailable: Boolean,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onChoose: (AnimeLibraryEntry) -> Unit,
    continuationLabel: (AnimeLibraryEntry) -> String,
) {
    val scroll = rememberLazyListState()
    val recent = remember(entries) { entries.filter { it.resume != null }.sortedByDescending { it.resume?.updatedAt } }
    MotionBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.background,
        backdrop = backdrop) { dismiss ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("最近观看", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            if (!unavailable && recent.isNotEmpty()) Text("${recent.size} 部", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭最近观看") {
                Icon(Icons.Outlined.Close, "关闭最近观看", Modifier.size(18.dp))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(), state = scroll,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (unavailable) item(key = "unavailable") {
                AnimeMessage("观看记录暂时无法读取", "本地记录已保留。")
            } else if (recent.isEmpty()) item(key = "empty") {
                AnimeMessage("还没有观看记录", "打开一部番剧后，随时可以从这里接着看。")
            }
            items(recent, key = { it.subject.id }) { entry ->
                AnimeLibraryWorkRow(entry, continuationLabel(entry),
                    onOpen = { onChoose(entry); dismiss() })
            }
        }
    }
}

internal enum class AnimeWorkAction { OPEN, FOLLOW, UNFOLLOW, RATINGS }

/** The actual manga quick-action sheet, with anime-owned identity and explicit follow intent. */
@Composable
internal fun AnimeWorkActionsSheet(
    subject: AnimeSubject,
    followed: Boolean,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onChoose: (AnimeWorkAction) -> Unit,
) {
    MotionBottomSheet(onDismissRequest = onDismiss, backdrop = backdrop, desktopCloseButton = true) { dismiss ->
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AnimePoster(subject, Modifier.width(48.dp).height(72.dp))
                Text(subject.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            CompactMenuAction(Icons.Outlined.Info, "查看详情", onClick = {
                onChoose(AnimeWorkAction.OPEN); dismiss()
            })
            CompactMenuAction(Icons.Outlined.BookmarkBorder, if (followed) "取消追番" else "加入追番", onClick = {
                onChoose(if (followed) AnimeWorkAction.UNFOLLOW else AnimeWorkAction.FOLLOW); dismiss()
            })
            CompactMenuAction(Icons.Outlined.StarOutline, "各站评分", onClick = {
                onChoose(AnimeWorkAction.RATINGS); dismiss()
            })
        }
    }
}
