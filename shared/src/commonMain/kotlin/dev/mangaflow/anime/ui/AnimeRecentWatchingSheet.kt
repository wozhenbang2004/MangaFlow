package dev.mangaflow.anime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
import dev.mangaflow.ui.motion.motionPress

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
            Text("最近观看", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(if (unavailable) "—" else recent.size.toString(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            MotionIconButton(onClick = dismiss, modifier = Modifier.size(44.dp), tooltip = "关闭最近观看") {
                Icon(Icons.Outlined.Close, "关闭最近观看", Modifier.size(18.dp))
            }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding(), state = scroll,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (unavailable) item {
                AnimeQuietLabel("观看记录暂时无法读取，原记录仍保留。", Modifier.padding(12.dp))
            } else if (recent.isEmpty()) item {
                AnimeQuietLabel("还没有观看记录", Modifier.padding(12.dp))
            }
            items(recent, key = { it.subject.id }) { entry ->
                val progress = entry.resume ?: return@items
                val interaction = remember(entry.subject.id) { MutableInteractionSource() }
                val actionLabel = continuationLabel(entry)
                Row(Modifier.fillMaxWidth()
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                        onClickLabel = actionLabel, onClick = { onChoose(entry); dismiss() })
                    .motionPress(interaction, pressedScale = .985f)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AnimePoster(entry.subject, Modifier.width(36.dp).height(54.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.subject.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(animeWatchLocation(entry, progress) + if (progress.completed) " · $actionLabel" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.PlayArrow, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
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
