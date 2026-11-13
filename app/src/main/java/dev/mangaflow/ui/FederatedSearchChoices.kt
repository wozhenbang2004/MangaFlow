package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.FederatedSearchSource
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress

/** The search hit's actual body sources, kept separate from rating-site marks. */
@Composable
internal fun SearchSourceMarks(matches: List<Manga>, modifier: Modifier = Modifier) {
    val sources = remember(matches) { matches.distinctBy { it.sourceId } }
    if (sources.isEmpty()) return
    BoxWithConstraints(modifier.height(18.dp).semantics {
        contentDescription = "正文来源：" + sources.joinToString("、") { sourceDisplayName(it.sourceId) }
    }) {
        val mark = 13.dp
        val flat = maxWidth >= mark * sources.size + 5.dp * (sources.size - 1)
        val step = if (flat) 18.dp else if (sources.size > 1)
            minOf(8.dp, ((maxWidth - mark) / (sources.size - 1)).coerceAtLeast(0.dp)) else 0.dp
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            sources.indices.reversed().forEach { index ->
                val source = sources[index]
                ChapterSourceLogo(source.sourceId, sourceDisplayName(source.sourceId),
                    Modifier.absoluteOffset(x = step * index).size(mark), muted = true)
            }
        }
    }
}

/** Partial source failures disclose their own status without replacing successful results. */
@Composable
internal fun SearchSourceSummary(sources: Map<String, FederatedSearchSource>, onRetry: () -> Unit) {
    if (sources.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val loading = sources.values.count { it.loading }
    val failed = sources.values.count { !it.loading && it.error != null }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading > 0) {
                MotionBusyIndicator(Modifier.size(11.dp), color = colors.onSurfaceVariant, strokeWidth = 1.2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text("已查 ${sources.size - loading}/${sources.size} 源", fontSize = 11.sp,
                color = colors.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (failed > 0) MotionTextButton(onClick = onRetry, enabled = loading == 0) {
                Text("$failed 源未完成 · 重试", fontSize = 11.sp, color = colors.onSurfaceVariant)
            }
            MotionIconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(30.dp)) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    if (expanded) "收起来源状态" else "查看来源状态", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
            }
        }
        if (expanded) sources.values.forEach { source ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ChapterSourceLogo(source.sourceId, source.sourceName, Modifier.size(14.dp), muted = true)
                Text(source.sourceName, Modifier.padding(start = 7.dp).widthIn(min = 66.dp),
                    fontSize = 11.sp, color = colors.onSurfaceVariant)
                Text(if (source.loading) "检查中" else source.error ?: "已完成",
                    modifier = Modifier.weight(1f), fontSize = 11.sp, color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Choose an actual search hit only when the aggregated title has ambiguous versions. */
@Composable
internal fun FederatedSearchChoices(
    matches: List<Manga>,
    repository: MangaRepository,
    backdrop: HazeState?,
    onDismiss: () -> Unit,
    onOpen: (Manga) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val maximumHeight = LocalMangaWindow.current.height * .76f
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    val frozen = remember { matches.distinctBy(Manga::id) }
    var pendingOpen by remember { mutableStateOf<Manga?>(null) }
    MotionBottomSheet(onDismissRequest = {
        onDismiss()
        pendingOpen?.let(onOpen)
    }, backdrop = backdrop,
        containerColor = colors.surface.copy(alpha = .97f)) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("选择作品版本", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("同名结果尚未可靠对应，请选择要阅读的作品", color = colors.onSurfaceVariant,
                        fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
                }
                MotionIconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭版本选择", Modifier.size(20.dp)) }
            }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(frozen, key = Manga::id) { manga ->
                    val interaction = remember(manga.id) { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                            onClickLabel = "阅读${sourceDisplayName(manga.sourceId)}的${manga.title}", onClick = {
                                pendingOpen = manga; dismiss()
                            }).motionPress(interaction, pressedScale = .99f).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        MangaCover(manga, repository, Modifier.size(38.dp, 54.dp).mangaCoverCard(6.dp))
                        Column(Modifier.weight(1f).padding(start = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(manga.title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (manga.authors.isNotEmpty()) Text(manga.authors.joinToString(" · "), fontSize = 11.sp,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                ChapterSourceLogo(manga.sourceId, sourceDisplayName(manga.sourceId), Modifier.size(13.dp))
                                Text(sourceDisplayName(manga.sourceId), fontSize = 11.sp, color = colors.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
