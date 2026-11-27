package dev.mangaflow.ui

import dev.mangaflow.net.HttpUrl.Companion.toHttpUrlOrNull
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import dev.mangaflow.typography.MangaFontScene
import dev.mangaflow.data.federatedMangaIdentity
import dev.mangaflow.data.sourceDisplayName
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.MotionBottomSheet
import dev.mangaflow.ui.motion.MotionBusyIndicator
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionSwap
import dev.mangaflow.ui.motion.motionPress

/** The search hit's actual body sources, kept separate from rating-site marks. */
@Composable
internal fun SearchSourceMarks(
    matches: List<Manga>,
    modifier: Modifier = Modifier,
    onSource: ((List<Manga>) -> Unit)? = null,
    alignedSources: List<Manga> = emptyList(),
    tagSearch: Boolean = false,
    showNames: Boolean = false,
) {
    val grouped = remember(matches, alignedSources) {
        (matches + alignedSources).distinctBy(::federatedMangaIdentity).groupBy { it.sourceId }.values.toList()
    }
    val directSources = remember(matches) { matches.mapTo(mutableSetOf()) { it.sourceId } }
    val sources = grouped.map { it.first() }
    if (sources.isEmpty()) return
    if (showNames && onSource != null) {
        val first = sources.first()
        val name = first.sourceName.ifBlank { sourceDisplayName(first.sourceId) }
        val additional = sources.size - 1
        val interaction = remember(first.sourceId) { MutableInteractionSource() }
        Row(modifier.heightIn(min = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).heightIn(min = 32.dp).clip(RoundedCornerShape(6.dp))
                .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = "查看${name}的正文条目", onClick = { onSource(grouped.first()) })
                .motionPress(interaction, pressedScale = .98f).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ChapterSourceLogo(first.sourceId, name, Modifier.size(15.dp), muted = true)
                Text(name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (first.sourceId !in directSources) Icon(Icons.Outlined.Link, null, Modifier.size(12.dp))
                if (grouped.first().size > 1) DesktopIndexNote(grouped.first().size.toString())
            }
            if (additional > 0) {
                val more = remember { MutableInteractionSource() }
                Text("+$additional", Modifier.clip(RoundedCornerShape(6.dp)).clickable(interactionSource = more,
                    indication = null, role = Role.Button, onClickLabel = "选择其他正文来源",
                    onClick = { onSource(grouped.flatten()) }).motionPress(more, pressedScale = .97f)
                    .semantics { contentDescription = "另有 $additional 个正文来源，选择条目" }
                    .padding(horizontal = 7.dp, vertical = 8.dp),
                    style = mangaMetricValueStyle(12.sp, 16.sp), color = MaterialTheme.colorScheme.primary)
            }
        }
        return
    }
    if (onSource != null) {
        val configuration = LocalViewConfiguration.current
        val compactTargets = remember(configuration) {
            object : ViewConfiguration by configuration {
                override val minimumTouchTargetSize = DpSize(24.dp, 24.dp)
            }
        }
        CompositionLocalProvider(LocalViewConfiguration provides compactTargets) {
            Row(modifier.height(28.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                grouped.forEach { sourceMatches ->
                    val source = sourceMatches.first()
                    val name = source.sourceName.ifBlank { sourceDisplayName(source.sourceId) }
                    val interaction = remember(source.sourceId) { MutableInteractionSource() }
                    val linkedOnly = source.sourceId !in directSources
                    val description = if (linkedOnly) "$name，已核对同一作品，点击查看正文来源"
                        else if (tagSearch && sourceMatches.size == 1) "打开${name}的标签命中结果"
                        else if (sourceMatches.size == 1) "打开${name}的搜索结果"
                        else "$name，${sourceMatches.size}条搜索结果，选择条目"
                    Row(Modifier.height(28.dp).widthIn(min = 26.dp).clip(RoundedCornerShape(6.dp))
                        .clickable(interactionSource = interaction, indication = null, role = Role.Button,
                            onClickLabel = description, onClick = { onSource(sourceMatches) })
                        .motionPress(interaction, pressedScale = .94f)
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        ChapterSourceLogo(source.sourceId, name, Modifier.size(14.dp), muted = true)
                        if (linkedOnly) Icon(Icons.Outlined.Link, null,
                            Modifier.size(11.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sourceMatches.size > 1) Text(sourceMatches.size.toString(),
                            modifier = Modifier.clearAndSetSemantics {},
                            style = mangaMetricValueStyle(12.sp, 16.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private enum class SearchSourcePhase { WAITING, READY, FAILED }

/** Source state stays on its own mark; diagnostics never become a second result list. */
@Composable
internal fun SearchSourceSummary(
    sources: Map<String, FederatedSearchSource>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    showNames: Boolean = false,
) {
    if (sources.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val busy = sources.values.any { it.loading }
    val desktop = platformKind == PlatformKind.DESKTOP
    val meter = desktop && sources.size > 1
    Row(modifier.heightIn(min = 28.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (meter) {
            val returned = sources.values.count { !it.loading }
            val amount = animateFloatAsState(returned.toFloat() / sources.size,
                if (LocalMotionEnabled.current) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
                label = "search-source-returns")
            Row(Modifier.padding(end = 3.dp).clearAndSetSemantics {
                contentDescription = "${sources.size} 个来源里已有 $returned 个结束查询"
            }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MotionBusyIndicator(progress = { amount.value }, modifier = Modifier.size(13.dp),
                    color = colors.primary, trackColor = colors.onSurface.copy(alpha = .12f), strokeWidth = 1.5.dp)
                Text("$returned/${sources.size}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        sources.values.forEach { source -> key(source.sourceId) {
            val phase = when {
                source.loading -> SearchSourcePhase.WAITING
                source.error != null -> SearchSourcePhase.FAILED
                else -> SearchSourcePhase.READY
            }
            val failed = phase == SearchSourcePhase.FAILED
            val interaction = remember(source.sourceId) { MutableInteractionSource() }
            Row(Modifier.then(if (showNames) Modifier.heightIn(min = 32.dp).padding(end = 8.dp) else Modifier.size(24.dp))
                .then(if (failed) Modifier.clickable(enabled = !busy,
                    interactionSource = interaction, indication = null, role = Role.Button,
                    onClickLabel = "重试搜索", onClick = onRetry)
                    .motionPress(interaction, enabled = !busy, pressedScale = .94f) else Modifier)
                .semantics(mergeDescendants = true) {
                    contentDescription = source.sourceName + when {
                        source.loading -> "，查询中"
                        failed -> "，查询失败，" + source.error + "，点按重试搜索"
                        else -> "，已完成"
                    }
                }, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    ChapterSourceLogo(source.sourceId, source.sourceName, Modifier.size(15.dp), muted = source.loading)
                    val status: @Composable (SearchSourcePhase) -> Unit = { current ->
                        when (current) {
                            SearchSourcePhase.WAITING -> if (meter) Icon(Icons.Outlined.MoreHoriz, null,
                                Modifier.size(9.dp), tint = colors.onSurfaceVariant)
                            else MotionBusyIndicator(Modifier.size(7.dp), color = colors.onSurfaceVariant, strokeWidth = 1.dp)
                            SearchSourcePhase.READY -> Icon(Icons.Outlined.Check, null, Modifier.size(9.dp),
                                tint = colors.onSurfaceVariant)
                            SearchSourcePhase.FAILED -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(9.dp),
                                tint = colors.error)
                        }
                    }
                    Box(Modifier.align(Alignment.BottomEnd).size(9.dp), contentAlignment = Alignment.Center) {
                        if (desktop) MotionSwap(phase, content = status) else status(phase)
                    }
                }
                // Keep each source at its measured position as its neighbours finish.
                // The mark carries the status; the semantic action still announces retry.
                if (showNames) Text(source.sourceName, color = if (failed) colors.error else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        } }
        if (showNames && sources.values.any { !it.loading && it.error != null }) {
            val retry = remember { MutableInteractionSource() }
            Box(Modifier.heightIn(min = 32.dp)
                .clickable(enabled = !busy, role = Role.Button, interactionSource = retry, indication = null,
                    onClickLabel = "重试搜索", onClick = onRetry)
                .motionPress(retry, enabled = !busy).padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center) {
                Text("重试搜索", style = MaterialTheme.typography.labelSmall,
                    color = if (busy) colors.onSurfaceVariant else colors.primary)
            }
        }
    }
}

/** Choose an actual native result without implying that same-title records are versions. */
@OptIn(ExperimentalFoundationApi::class)
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
    val frozen = remember(matches) { matches.distinctBy(::federatedMangaIdentity) }
    val entries = remember(frozen) { searchEntryPresentations(frozen) }
    val desktop = platformKind == PlatformKind.DESKTOP
    val list = rememberLazyListState()
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
                Text(heading, modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                    style = browseTitleStyle(20.sp, 26.sp),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                MotionIconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "关闭搜索结果选择", Modifier.size(20.dp)) }
            }
            Box(Modifier.weight(1f, fill = false).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth(), state = list,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries, key = { federatedMangaIdentity(it.manga) }, contentType = { "native-search-choice" }) { entry ->
                    val manga = entry.manga
                    var identityVisible by remember(federatedMangaIdentity(manga)) { mutableStateOf(false) }
                    val interaction = remember(manga.id) { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
                        .combinedClickable(interactionSource = interaction, indication = null, role = Role.Button,
                            onClickLabel = "打开${entry.sourceName}的${manga.title}",
                            onLongClickLabel = if (identityVisible) "收起来源标识" else "查看来源标识",
                            onLongClick = { identityVisible = !identityVisible },
                            onClick = { pendingOpen = manga; dismiss() })
                        .motionPress(interaction, pressedScale = .99f)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (entry.sameSourceCount > 1) MangaCover(manga, repository,
                            Modifier.size(28.dp, 40.dp).mangaCoverCard(4.dp))
                        else ChapterSourceLogo(manga.sourceId, entry.sourceName, Modifier.size(16.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                if (entry.sameSourceCount > 1)
                                    ChapterSourceLogo(manga.sourceId, entry.sourceName, Modifier.size(14.dp))
                                Text(entry.primary,
                                    fontFamily = if (entry.primaryIsTitle) mangaFontFamily(MangaFontScene.WORK_TITLE) else null,
                                    fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (entry.secondary.isNotBlank()) Text(entry.secondary,
                                fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            // Only genuinely indistinguishable metadata needs a native identifier up front.
                            if (entry.ambiguous || identityVisible) Text(entry.identity,
                                fontSize = 10.sp, lineHeight = 13.sp, color = colors.onSurfaceVariant,
                                maxLines = if (identityVisible) 3 else 1,
                                overflow = if (identityVisible) TextOverflow.Ellipsis else TextOverflow.MiddleEllipsis)
                        }
                        if (desktop) MotionIconButton(onClick = { identityVisible = !identityVisible },
                            modifier = Modifier.size(34.dp), tooltip = if (identityVisible) "收起来源标识" else "查看来源标识") {
                            Icon(Icons.Outlined.Info, if (identityVisible) "收起来源标识" else "查看来源标识", Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (desktop) Box(Modifier.matchParentSize()) {
                DesktopVerticalScrollbar(list, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(5.dp))
            }
            }
        }
    }
}

private data class SearchEntryPresentation(
    val manga: Manga,
    val sourceName: String,
    val sameSourceCount: Int,
    val primary: String,
    val primaryIsTitle: Boolean,
    val secondary: String,
    val ambiguous: Boolean,
    val identity: String,
)

/** Disclose the fields that actually distinguish the supplied native entries, without another lookup. */
private fun searchEntryPresentations(matches: List<Manga>): List<SearchEntryPresentation> {
    val grouped = matches.groupBy { it.sourceId }
    fun authors(manga: Manga) = manga.authors.map(String::trim).filter(String::isNotBlank).joinToString(" · ")
    fun signature(manga: Manga) = listOf(manga.title.trim(), authors(manga),
        manga.latestChapter.trim(), manga.status.trim())
    val signatures = matches.associate { federatedMangaIdentity(it) to signature(it) }
    val fieldValues = grouped.mapValues { (_, peers) ->
        List(4) { index -> peers.map { signatures.getValue(federatedMangaIdentity(it))[index] }.toSet() }
    }
    val signatureCounts = grouped.mapValues { (_, peers) ->
        peers.groupingBy { signatures.getValue(federatedMangaIdentity(it)) }.eachCount()
    }
    return matches.map { manga ->
        val peers = grouped.getValue(manga.sourceId)
        val name = manga.sourceName.ifBlank { sourceDisplayName(manga.sourceId) }
        val fields = signatures.getValue(federatedMangaIdentity(manga))
        val differing = if (peers.size < 2) -1 else fields.indices.firstOrNull { index ->
            fields[index].isNotBlank() && fieldValues.getValue(manga.sourceId)[index].size > 1
        } ?: -1
        val primaryIsTitle = differing == 0 || peers.size > 1 && differing < 0 && manga.title.isNotBlank()
        val primary = if (peers.size == 1) name else
            fields.getOrNull(differing)?.takeIf(String::isNotBlank) ?: manga.title.ifBlank { name }
        val secondary = fields.drop(1).filter { it.isNotBlank() && it != primary }.distinct().joinToString(" · ")
        SearchEntryPresentation(manga, name, peers.size, primary, primaryIsTitle, secondary,
            ambiguous = (signatureCounts.getValue(manga.sourceId)[fields] ?: 0) > 1,
            identity = searchEntryPath(manga))
    }
}

/** Read only the supplied native identity; no detail request is needed to distinguish entries. */
private fun searchEntryPath(manga: Manga): String {
    val path = manga.sourceUrl.takeIf { it.isNotBlank() }?.let { url ->
        url.toHttpUrlOrNull()?.encodedPath?.takeIf { it.isNotBlank() && it != "/" }
    }
    return path ?: "条目 ID · ${federatedMangaIdentity(manga).second}"
}
