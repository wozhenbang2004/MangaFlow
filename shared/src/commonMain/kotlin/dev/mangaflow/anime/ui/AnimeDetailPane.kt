package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.mangaflow.anime.*
import dev.mangaflow.ui.*
import dev.mangaflow.ui.motion.*

@Composable
internal fun AnimeDetailPane(detail: AnimeDetail, state: AnimeScreenState, controller: AnimeController, backdrop: HazeState,
    scroll: LazyListState,
    onPlay: (AnimeEpisode) -> Unit, onOpen: (AnimeSubject) -> Unit,
    onSources: (AnimeEpisode?) -> Unit, onTracking: () -> Unit,
    modifier: Modifier = Modifier, active: Boolean = true) {
    val subject = detail.subject
    val entry = state.library.firstOrNull { it.subject.id == subject.id }
    var episodeTab by rememberSaveable(subject.id) { mutableIntStateOf(0) }
    var descriptionExpanded by rememberSaveable(subject.id) { mutableStateOf(false) }
    var episodeReversed by rememberSaveable(subject.id) { mutableStateOf(false) }
    var episodeQuery by rememberSaveable(subject.id) { mutableStateOf("") }
    var episodeSearch by rememberSaveable(subject.id) { mutableStateOf(false) }
    var statusOpen by remember { mutableStateOf(false) }
    var statusPresent by remember { mutableStateOf(false) }
    var episodeMenuOwner by remember { mutableStateOf<String?>(null) }
    var trackingAfterStatus by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { statusOpen = false; trackingAfterStatus = false } }
    val main = detail.episodes.filter { it.kind == AnimeEpisodeKind.MAIN }
    val extra = detail.episodes.filter { it.kind != AnimeEpisodeKind.MAIN }
    LaunchedEffect(main.isEmpty(), extra.isNotEmpty()) {
        if (main.isEmpty() && extra.isNotEmpty()) episodeTab = 1
    }
    val episodes = (if (episodeTab == 0 || extra.isEmpty()) main else extra)
        .let { if (episodeReversed) it.asReversed() else it }
        .filter { episodeQuery.isBlank() || animeEpisodeNumber(it.number).contains(episodeQuery.trim()) ||
            it.title.contains(episodeQuery.trim(), ignoreCase = true) }
    val resume = entry?.resume?.takeUnless { it.completed }?.let { progress -> detail.episodes.firstOrNull { it.id == progress.episodeId } }
    val first = resume ?: main.firstOrNull { it.id !in entry?.completedEpisodeIds.orEmpty() } ?: main.firstOrNull() ?: extra.firstOrNull()
    val sourceNames = state.sourceBindings.map { binding ->
        binding.catalog.sourceId to (state.sourceResults.firstOrNull { it.providerId == binding.catalog.sourceId }?.name ?:
            animeSourceName(binding.catalog.sourceId, state))
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 760.dp) 2 else 1
        LazyColumn(Modifier.fillMaxSize().motionInputGate(active && !statusPresent && episodeMenuOwner == null), state = scroll,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "identity") {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                    AnimePoster(subject, Modifier.width(96.dp).height(138.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(subject.title, style = MaterialTheme.typography.titleLarge)
                        if (subject.originalTitle.isNotBlank() && subject.originalTitle != subject.title)
                            Text(subject.originalTitle, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AnimeQuietLabel(animeSubjectSummary(subject))
                        if (subject.rating != null || subject.rank != null) AnimeQuietLabel(listOfNotNull(
                            subject.rating?.takeIf { it > 0 }?.let { it.toString() + " 分" },
                            subject.rank?.takeIf { it > 0 }?.let { "排名 #" + it }).joinToString(" · "))
                        if (state.detailFromCache) AnimeQuietLabel(if (state.detailLoading) "已保存的资料 · 正在更新" else "已保存的资料")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MotionTextButton(onClick = { controller.setFollowed(subject, entry?.followed != true) },
                                modifier = Modifier.heightIn(min = 44.dp)) {
                                Icon(if (entry?.followed == true) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                                    null, Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (entry?.followed == true) "已追番" else "追番")
                            }
                        }
                    }
                }
            }
            item(key = "continue") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    MotionButton(onClick = { first?.let(onPlay) }, enabled = first != null,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface)) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (resume != null) "继续 " + animeEpisodeLabel(resume) + " · " + animeTime(entry?.resume?.positionMs ?: 0L)
                            else first?.let { "播放 " + animeEpisodeLabel(it) } ?: if (state.detailLoading) "正在载入集数"
                            else if (state.detailError != null) "集数未取得" else "暂无集数",
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        MotionTextButton(onClick = { statusOpen = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                            Text(animeStatusLabel(entry?.status))
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(16.dp))
                        }
                        CompactGlassPopover(statusOpen, backdrop = backdrop,
                            onDismiss = { statusOpen = false; trackingAfterStatus = false },
                            onPresenceChange = { present ->
                                statusPresent = present
                                if (!present && trackingAfterStatus) {
                                    trackingAfterStatus = false
                                    if (active) onTracking()
                                }
                            }) {
                            AnimeWatchStatus.entries.forEach { status ->
                                CompactMenuAction(Icons.Outlined.Check, animeStatusLabel(status),
                                    onClick = { statusOpen = false; controller.setWatchStatus(subject.id, status) })
                            }
                            CompactMenuAction(Icons.Outlined.Sync, "核对追番进度",
                                onClick = { trackingAfterStatus = true; statusOpen = false })
                        }
                    }
                }
            }
            item(key = "playback-source") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("播放来源", style = MaterialTheme.typography.labelLarge)
                        AnimeQuietLabel(if (sourceNames.isEmpty()) "选择来源并核对集数" else
                            sourceNames.distinctBy { it.first }.joinToString(" · ") { it.second })
                    }
                    AnimeSourceStack(sourceNames, onClick = { onSources(null) })
                }
            }
            if (state.detailLoading) item(key = "episode-loading") {
                AnimeMessage("正在载入集数", "已保存的作品与续看记录会保留。")
            }
            if (state.detailError != null) item(key = "episode-error") {
                AnimeMessage("集数未取得", state.detailError, action = "重试",
                    onAction = { controller.open(subject) })
            }
            if (state.sourceError != null) item(key = "source-error") {
                AnimeSelection("来源暂未就绪", state.sourceError, false, onClick = { onSources(null) })
            }
            item(key = "episodes-heading") {
                AnimeSectionTitle("选集", action = {
                    Row {
                        MotionIconButton(onClick = { episodeSearch = !episodeSearch; if (!episodeSearch) episodeQuery = "" },
                            modifier = Modifier.size(44.dp), tooltip = "查找集数") {
                            Icon(Icons.Outlined.Search, "查找集数", Modifier.size(19.dp))
                        }
                        MotionIconButton(onClick = { episodeReversed = !episodeReversed }, modifier = Modifier.size(44.dp),
                            tooltip = if (episodeReversed) "改为正序" else "改为倒序") {
                            Icon(Icons.Outlined.SwapVert, if (episodeReversed) "倒序，点按切换" else "正序，点按切换", Modifier.size(19.dp))
                        }
                    }
                })
                if (episodeSearch) AnimeTextField(episodeQuery, { episodeQuery = it }, "集号或标题",
                    Modifier.padding(bottom = 6.dp))
                if (extra.isNotEmpty()) MotionTabs(episodeTab, listOf("正片 " + main.size, "特别篇等 " + extra.size),
                    onSelect = { episodeTab = it }, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
                    controlHeight = 44.dp, treatment = MotionTabTreatment.Underline)
            }
            if (episodes.isEmpty() && !state.detailLoading && state.detailError == null) item(key = "empty-episodes") {
                AnimeMessage(if (episodeQuery.isNotBlank()) "没有匹配的集数" else "这组还没有集数",
                    if (episodeQuery.isNotBlank()) "试试其他集号或标题。" else "作品资料尚未提供集数时，不按列表位置猜配播放资源。",
                    action = if (episodeQuery.isNotBlank()) "清除查找" else "查看来源",
                    onAction = { if (episodeQuery.isNotBlank()) episodeQuery = "" else onSources(null) })
            }
            items(episodes.chunked(columns), key = { row -> row.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { episode ->
                        AnimeEpisodeCell(episode, completed = episode.id in entry?.completedEpisodeIds.orEmpty(),
                            resume = episode.id == resume?.id,
                            matched = state.episodeOptions[episode.id].orEmpty().count { it.confirmed },
                            backdrop = backdrop, active = active,
                            onMenuPresenceChange = { present ->
                                if (present) episodeMenuOwner = episode.id
                                else if (episodeMenuOwner == episode.id) episodeMenuOwner = null
                            },
                            onPlay = { onPlay(episode) }, onSources = { onSources(episode) },
                            onCompleted = { controller.setEpisodeCompleted(episode.id, episode.id !in entry?.completedEpisodeIds.orEmpty()) },
                            modifier = Modifier.weight(1f))
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
            if (subject.description.isNotBlank()) item(key = "description") {
                Column {
                    AnimeSectionTitle("简介", action = {
                        MotionIconButton(onClick = { descriptionExpanded = !descriptionExpanded },
                            modifier = Modifier.size(44.dp), tooltip = if (descriptionExpanded) "收起简介" else "展开简介") {
                            Icon(if (descriptionExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                                if (descriptionExpanded) "收起简介" else "展开简介", Modifier.size(20.dp))
                        }
                    })
                    Text(subject.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.relatedSubjects.isNotEmpty()) {
                item(key = "related-heading") { AnimeSectionTitle("相关番剧") }
                items(state.relatedSubjects, key = { "related:" + it.id }) { related ->
                    AnimeSubjectRow(related, animeSubjectSummary(related), onOpen = { onOpen(related) })
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun AnimeEpisodeCell(episode: AnimeEpisode, completed: Boolean, resume: Boolean, matched: Int,
    backdrop: HazeState, active: Boolean, onPlay: () -> Unit, onSources: () -> Unit, onCompleted: () -> Unit,
    onMenuPresenceChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember(episode.id) { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    var menuVisible by remember(episode.id) { mutableStateOf(false) }
    var sourcesAfterMenu by remember(episode.id) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) { menuVisible = false; sourcesAfterMenu = false } }
    Row(modifier.heightIn(min = 60.dp).clip(RoundedCornerShape(12.dp))
        .background(if (resume) colors.onSurface.copy(alpha = .05f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 60.dp).motionPress(interaction, pressedScale = .99f)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onPlay)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(animeEpisodeNumber(episode.number), Modifier.widthIn(min = 28.dp),
                style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (episode.kind == AnimeEpisodeKind.MAIN) episode.title.ifBlank { animeEpisodeLabel(episode) }
                    else animeEpisodeLabel(episode) + " · " + episode.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                if (resume) AnimeQuietLabel("续看")
            }
            if (completed) Icon(Icons.Outlined.CheckCircle, "已看", Modifier.size(16.dp), tint = colors.onSurfaceVariant)
        }
        Box {
            MotionIconButton(onClick = { menuVisible = true }, modifier = Modifier.size(44.dp), tooltip = "本集操作") {
                Icon(Icons.Outlined.MoreHoriz, animeEpisodeLabel(episode) + "的操作", Modifier.size(19.dp), tint = colors.onSurfaceVariant)
            }
            CompactGlassPopover(menuVisible, backdrop,
                onDismiss = { menuVisible = false; sourcesAfterMenu = false },
                onPresenceChange = { present ->
                    onMenuPresenceChange(present)
                    if (!present && sourcesAfterMenu) {
                        sourcesAfterMenu = false
                        if (active) onSources()
                    }
                }) {
                AnimeQuietLabel(if (matched == 0) "尚未匹配播放来源" else "已匹配 " + matched + " 个来源",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                CompactMenuAction(if (completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    if (completed) "取消已看" else "标记已看", onClick = { menuVisible = false; onCompleted() })
                CompactMenuAction(Icons.Outlined.SwapHoriz, "本集来源与映射",
                    onClick = { sourcesAfterMenu = true; menuVisible = false })
            }
        }
    }
}
