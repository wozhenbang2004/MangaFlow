package dev.mangaflow.ui

import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.data.FederatedSearchSource
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.data.federatedMangaIdentity
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress

/** The search hit's actual body sources, kept separate from rating-site marks. */
@Composable
internal fun SearchSourceMarks(
    matches: List<Manga>,
    modifier: Modifier = Modifier,
    onSource: ((List<Manga>) -> Unit)? = null,
) {
    val grouped = remember(matches) {
        matches.distinctBy(::federatedMangaIdentity).groupBy { it.sourceId }.values.toList()
    }
    val sources = grouped.map { it.first() }
    if (sources.isEmpty()) return
    if (onSource != null) {
        val configuration = LocalViewConfiguration.current
        val compactTargets = remember(configuration) {
            object : ViewConfiguration by configuration {
                override val minimumTouchTargetSize = DpSize(24.dp, 24.dp)
            }
        }
        CompositionLocalProvider(LocalViewConfiguration provides compactTargets) {
            Row(modifier.height(24.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                grouped.forEach { sourceMatches ->
                    val source = sourceMatches.first()
                    val name = source.sourceName.ifBlank { sourceDisplayName(source.sourceId) }
                    val interaction = remember(source.sourceId) { MutableInteractionSource() }
                    val description = if (sourceMatches.size == 1) "打开${name}的搜索结果"
                        else "$name，${sourceMatches.size}条搜索结果，选择条目"
                    Box(Modifier.size(24.dp).clip(RoundedCornerShape(5.dp))
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                            onClickLabel = description, onClick = { onSource(sourceMatches) })
                        .motionPress(interaction, pressedScale = .94f)
                        .semantics { contentDescription = description },
                        contentAlignment = Alignment.Center) {
                        ChapterSourceLogo(source.sourceId, name, Modifier.size(14.dp), muted = true)
                        if (sourceMatches.size > 1) Text(sourceMatches.size.toString(),
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 1.dp),
                            fontSize = 7.sp, lineHeight = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        return
    }
    BoxWithConstraints(modifier.height(18.dp).semantics {
        contentDescription = "正文来源：" + sources.joinToString("、") { it.sourceName.ifBlank { sourceDisplayName(it.sourceId) } }
    }) {
        val mark = 13.dp
        val flat = maxWidth >= mark * sources.size + 5.dp * (sources.size - 1)
        val step = if (flat) 18.dp else if (sources.size > 1)
            minOf(8.dp, ((maxWidth - mark) / (sources.size - 1)).coerceAtLeast(0.dp)) else 0.dp
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            sources.indices.reversed().forEach { index ->
                val source = sources[index]
                ChapterSourceLogo(source.sourceId, source.sourceName.ifBlank { sourceDisplayName(source.sourceId) },
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
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                sources.values.forEach { source ->
                    Box(Modifier.size(24.dp).semantics(mergeDescendants = true) {
                        contentDescription = source.sourceName + if (source.loading) "，查询中"
                            else if (source.error != null) "，查询失败" else "，已完成"
                    }, contentAlignment = Alignment.Center) {
                        ChapterSourceLogo(source.sourceId, source.sourceName, Modifier.size(14.dp), muted = source.loading)
                        if (source.loading) MotionBusyIndicator(Modifier.align(Alignment.BottomEnd).size(7.dp),
                            color = colors.onSurfaceVariant, strokeWidth = 1.dp)
                        else if (source.error != null) Icon(Icons.Outlined.ErrorOutline, null,
                            Modifier.align(Alignment.BottomEnd).size(9.dp), tint = colors.error)
                    }
                }
            }
            if (failed > 0) MotionIconButton(onClick = onRetry, enabled = loading == 0,
                modifier = Modifier.size(30.dp)) {
                Icon(Icons.Outlined.Refresh, "重试${failed}个失败来源", Modifier.size(16.dp),
                    tint = colors.onSurfaceVariant)
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

/** Choose an actual native result without implying that same-title records are versions. */
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
    val maximumHeight = LocalMangaWindow.current.height * .72f
    LaunchedEffect(Unit) { focus.clearFocus(); keyboard?.hide() }
    val frozen = remember { matches.distinctBy(::federatedMangaIdentity) }
    val bySource = remember(frozen) { frozen.groupBy { it.sourceId } }
    val heading = frozen.firstOrNull()?.title.orEmpty()
    var pendingOpen by remember { mutableStateOf<Manga?>(null) }
    MotionBottomSheet(onDismissRequest = {
        onDismiss()
        pendingOpen?.let(onOpen)
    }, backdrop = backdrop,
        containerColor = colors.surface.copy(alpha = .97f)) { dismiss ->
        Column(Modifier.fillMaxWidth().heightIn(max = maximumHeight).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp).heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                Text(heading, modifier = Modifier.weight(1f).padding(start = 8.dp),
                    fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                MotionIconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭搜索结果选择", Modifier.size(20.dp)) }
            }
            LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(frozen, key = { federatedMangaIdentity(it) }) { manga ->
                    val sourceMatches = bySource.getValue(manga.sourceId)
                    val sourceName = manga.sourceName.ifBlank { sourceDisplayName(manga.sourceId) }
                    val entryNumber = sourceMatches.indexOf(manga) + 1
                    val authors = manga.authors.filter { it.isNotBlank() }.joinToString(" · ")
                    val details = listOfNotNull(
                        authors.takeIf { it.isNotBlank() },
                        manga.latestChapter.takeIf { it.isNotBlank() },
                        manga.status.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    val entryPath = if (sourceMatches.size > 1) searchEntryPath(manga) else null
                    val interaction = remember(manga.id) { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                            onClickLabel = "阅读${sourceName}的${manga.title}" +
                                if (sourceMatches.size > 1) "，条目$entryNumber" else "", onClick = {
                                pendingOpen = manga; dismiss()
                            }).motionPress(interaction, pressedScale = .99f)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (sourceMatches.size > 1) MangaCover(manga, repository,
                            Modifier.size(28.dp, 40.dp).mangaCoverCard(4.dp))
                        else ChapterSourceLogo(manga.sourceId, sourceName, Modifier.size(16.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (sourceMatches.size > 1) ChapterSourceLogo(manga.sourceId, sourceName, Modifier.size(14.dp))
                                Text(if (sourceMatches.size > 1) "$sourceName · $entryNumber" else sourceName,
                                    fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (details.isNotBlank()) Text(details,
                                fontSize = 11.sp, lineHeight = 14.sp, color = colors.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (entryPath != null) Text(entryPath, fontSize = 10.sp, lineHeight = 12.sp,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Read only the supplied native identity; no detail request is needed to distinguish entries. */
private fun searchEntryPath(manga: Manga): String {
    val path = manga.sourceUrl.takeIf { it.isNotBlank() }?.let { url ->
        url.toHttpUrlOrNull()?.encodedPath?.takeIf { it.isNotBlank() && it != "/" }
    }
    return path ?: "条目 ID · ${federatedMangaIdentity(manga).second}"
}
