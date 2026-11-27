package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.anime.AnimeLibraryEntry
import dev.mangaflow.anime.canonicalBangumiId
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.PlatformKind
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.platform.platformKind
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.launch

@Composable
internal fun AnimeDiscoveryWatchShelf(entries: List<AnimeLibraryEntry>, scroll: LazyListState, active: Boolean,
    onOpenHistory: () -> Unit, continuationLabel: (AnimeLibraryEntry) -> String,
    onResume: (AnimeLibraryEntry) -> Unit, besideGallery: Boolean) {
    if (entries.isEmpty()) return
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val rows = if (besideGallery) 2 else 1
    val groups = (entries.size + rows - 1) / rows
    val rail = if (besideGallery) rememberLazyListState() else scroll
    val motion = LocalMotionEnabled.current
    val scope = rememberCoroutineScope()
    fun turn(direction: Int) {
        scope.launch {
            val layout = rail.layoutInfo
            val visible = layout.visibleItemsInfo.count {
                it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset
            }.coerceAtLeast(1)
            val leading = rail.firstVisibleItemIndex + if (direction < 0 && rail.firstVisibleItemScrollOffset > 0) 1 else 0
            val target = (leading + visible * direction).coerceIn(0, groups - 1)
            if (motion) rail.animateScrollToItem(target) else rail.scrollToItem(target)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 10.dp
        val targetWidth = 264.dp * scale
        val across = ((maxWidth + gap) / (targetWidth + gap)).toInt().coerceAtLeast(1)
        val cardWidth = when {
            besideGallery -> maxWidth
            groups == 1 -> minOf(maxWidth, 344.dp * scale)
            across > 1 -> (maxWidth - gap * (across - 1)) / across
            else -> (maxWidth - 64.dp).coerceAtLeast(targetWidth).coerceAtMost(344.dp * scale).coerceAtMost(maxWidth)
        }
        val paged = platformKind == PlatformKind.DESKTOP && groups > (if (besideGallery) 1 else across)
        val separateControls = paged && maxWidth < 200.dp * scale
        val pageControls: @Composable () -> Unit = {
            MotionIconButton({ turn(-1) }, Modifier.size(40.dp), enabled = active && rail.canScrollBackward,
                tooltip = "较新的观看记录") {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "较新的观看记录", Modifier.size(18.dp))
            }
            MotionIconButton({ turn(1) }, Modifier.size(40.dp), enabled = active && rail.canScrollForward,
                tooltip = "较早的观看记录") {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, "较早的观看记录", Modifier.size(18.dp))
            }
        }
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("最近观看", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (paged && !separateControls) pageControls()
                if (maxWidth < 160.dp * scale || paged && maxWidth < 250.dp * scale) {
                    MotionIconButton(onOpenHistory, Modifier.size(44.dp), enabled = active, tooltip = "全部观看历史") {
                        Icon(Icons.Outlined.History, "全部观看历史", Modifier.size(20.dp))
                    }
                } else MotionTextButton(onOpenHistory, enabled = active, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("全部历史", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (separateControls) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                pageControls()
            }
            LazyRow(state = rail, modifier = Modifier.fillMaxWidth(), userScrollEnabled = active,
                horizontalArrangement = Arrangement.spacedBy(gap), contentPadding = PaddingValues(bottom = 3.dp)) {
                items(groups, key = { entries[it * rows].subject.id }) { group ->
                    Column(Modifier.width(cardWidth), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (index in group * rows until minOf((group + 1) * rows, entries.size)) {
                            val entry = entries[index]
                            key(entry.subject.id) {
                                AnimeDiscoveryResumeCard(entry, continuationLabel(entry), active,
                                    compact = besideGallery && index % rows != 0,
                                    reflow = cardWidth < 205.dp * scale, onResume = { onResume(entry) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnimeDiscoveryResumeCard(entry: AnimeLibraryEntry, action: String, active: Boolean,
    compact: Boolean, reflow: Boolean, onResume: () -> Unit) {
    val progress = entry.resume ?: return
    val colors = MaterialTheme.colorScheme
    val accessibility = LocalGlassAccessibilitySettings.current
    val opaque = accessibility.increaseContrast || accessibility.reduceTransparency
    val interaction = remember(entry.subject.id) { MutableInteractionSource() }
    val coverWidth = if (compact || reflow) 43.dp else 55.dp
    val title = entry.subject.title.ifBlank { entry.subject.originalTitle.ifBlank { "未命名番剧" } }
    val details: @Composable () -> Unit = {
        Text(animeWatchedEpisodeName(entry, progress), color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, maxLines = if (reflow) 2 else 1, overflow = TextOverflow.Ellipsis)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(15.dp), tint = colors.primary)
                Text(action, color = colors.primary, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold)
            }
            Text(animeWatchPosition(progress), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
        }
        if (!progress.completed) animeWatchFraction(progress)?.let { fraction ->
            key(progress.episodeId) { AnimeWatchProgressLine(fraction, Modifier.padding(top = 2.dp)) }
        }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
        .background(colors.surfaceContainerLow.copy(alpha = if (opaque) 1f else if (compact) .48f else .77f))
        .clickable(interactionSource = interaction, indication = null, enabled = active, role = Role.Button,
            onClickLabel = action, onClick = onResume)
        .motionPress(interaction, enabled = active, pressedScale = .985f)
        .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            AnimeResumePoster(entry, title, coverWidth)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = animeWorkTitleStyle(), color = colors.onSurface,
                    maxLines = if (reflow) 3 else 2, overflow = TextOverflow.Ellipsis)
                if (!reflow) details()
            }
        }
        if (reflow) details()
    }
}

@Composable
private fun AnimeResumePoster(entry: AnimeLibraryEntry, title: String, width: Dp) {
    val subject = entry.subject
    val detail = entry.catalogDetail?.takeIf { it.subject.id == subject.id }
    val candidates = remember(subject, detail?.subject, entry.nativeMetadata, detail?.metadataSubject, entry.metadataAutoMatchDisabled) {
        val linked = if ("native-source" in subject.externalIds || subject.canonicalDoubanId() != null)
            entry.nativeMetadata ?: detail?.metadataSubject?.takeUnless { entry.metadataAutoMatchDisabled }
        else null
        listOfNotNull(subject, detail?.subject, linked?.takeIf { it.canonicalBangumiId() != null })
            .filter { it.coverUrl.isNotBlank() }.distinctBy { it.coverUrl to animePosterSourceId(it) }
    }
    var selection by remember(candidates) { mutableIntStateOf(0) }
    var imageVisible by remember(candidates) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val initial = remember(title) { title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() }
    Box(Modifier.width(width).aspectRatio(2f / 3f).clearAndSetSemantics { }
        .clip(RoundedCornerShape(9.dp)).background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
        if (!imageVisible) {
            if (initial != null) Text(initial, style = animeWorkTitleStyle(large = true), color = colors.onSecondaryContainer,
                maxLines = 1)
            else Icon(Icons.Outlined.Movie, null, Modifier.size(22.dp), tint = colors.onSecondaryContainer)
        }
        val index = selection
        candidates.getOrNull(index)?.let { artwork ->
            val context = LocalMangaContext.current
            val source = animePosterSourceId(artwork)
            val request = remember(context, source, artwork.coverUrl) {
                ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(artwork.coverUrl, source).crossfade(false).build()
            }
            key(request) {
                AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    onSuccess = { if (selection == index) imageVisible = true },
                    onError = {
                        if (selection == index) {
                            imageVisible = false
                            selection = index + 1
                        }
                    })
            }
        }
    }
}
