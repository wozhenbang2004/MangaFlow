package dev.mangaflow.anime.ui

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.platform.*
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.mangaCoverCard
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.*
import dev.mangaflow.typography.MangaFontScene
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs

internal data class AnimeFeaturedEntry(
    val key: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val caption: String,
    val discoveryItem: AnimeDiscoveryItem,
)

/**
 * The MangaFlow gallery pager keeps the adjacent cover visible. Only the caption
 * samples the artwork for glass, and the image and caption belong to the same item.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimeFeaturedCarousel(
    entries: List<AnimeFeaturedEntry>,
    active: Boolean,
    visible: Boolean,
    selectedKey: String?,
    onSelected: (String) -> Unit,
    onOpenScores: (AnimeFeaturedEntry) -> Unit,
    onOpen: (AnimeFeaturedEntry) -> Unit,
) {
    if (entries.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val galleryWidth = maxWidth
        val density = LocalDensity.current
        val motion = LocalMotionEnabled.current
        val window = LocalMangaWindow.current
        val desktop = platformKind == PlatformKind.DESKTOP
        val fontScale = density.fontScale.coerceAtLeast(1f)
        val preferredWidth = if (entries.size == 1) galleryWidth.coerceAtMost(680.dp)
            else (galleryWidth * .93f).coerceAtMost(680.dp)
        val compactLayout = preferredWidth < 255.dp || fontScale > 1.35f
        val footerHeight = if (desktop) 44.dp else 14.dp
        val itemHeight = when {
            compactLayout -> (158.dp * fontScale).coerceIn(178.dp, 280.dp)
            window.shortHeight -> (window.height * .42f).coerceIn(136.dp, 192.dp)
            else -> (preferredWidth * .56f).coerceIn(162.dp, 242.dp)
        }
        val galleryHeight = itemHeight + footerHeight + 8.dp
        val edgePadding = ((galleryWidth - preferredWidth) / 2f).coerceAtLeast(0.dp)
        val state = rememberPagerState(
            initialPage = entries.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0),
        ) { entries.size }
        val latestOnSelected by rememberUpdatedState(onSelected)
        LaunchedEffect(state) {
            snapshotFlow {
                if (state.isScrollInProgress) null else state.layoutInfo.visiblePagesInfo
                    .firstOrNull { it.index == state.settledPage }?.key as? String
            }.collect { key -> key?.let(latestOnSelected) }
        }
        val scope = rememberCoroutineScope()
        fun selectPage(index: Int) {
            if (!active || index !in entries.indices) return
            scope.launch {
                if (motion) state.animateScrollToPage(index,
                    animationSpec = spring(MangaMotion.GestureDamping, MangaMotion.GestureStiffness))
                else state.scrollToPage(index)
            }
        }
        val fling = PagerDefaults.flingBehavior(state,
            snapAnimationSpec = if (motion) spring(dampingRatio = MangaMotion.GestureDamping,
                stiffness = MangaMotion.GestureStiffness) else snap())
        val position = remember(state, entries.size) {
            derivedStateOf { (state.currentPage + state.currentPageOffsetFraction)
                .coerceIn(0f, entries.lastIndex.toFloat()) }
        }
        val coverUrls = remember(entries) { entries.map { it.coverUrl } }
        val readyCovers = remember(coverUrls) { mutableStateMapOf<String, Boolean>() }
        val currentCoverReady = entries.getOrNull(state.currentPage)?.coverUrl?.let { readyCovers[it] } == true
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        fun decodePixels(size: Dp): Int = with(density) {
            ((size.roundToPx().coerceAtLeast(1) + 63) / 64) * 64
        }
        val imageWidthPx = decodePixels(preferredWidth)
        val imageHeightPx = decodePixels(itemHeight)
        Column(Modifier.fillMaxWidth().height(galleryHeight)
            .onPreviewKeyEvent { event ->
                if (!active || entries.size <= 1 || event.type != KeyEventType.KeyDown ||
                    event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) false
                else when (event.key) {
                    Key.DirectionLeft -> {
                        selectPage(state.currentPage + if (rtl) 1 else -1)
                        true
                    }
                    Key.DirectionRight -> {
                        selectPage(state.currentPage + if (rtl) -1 else 1)
                        true
                    }
                    else -> false
                }
            }.focusGroup()) {
            HorizontalPager(state = state, pageSize = PageSize.Fixed(preferredWidth),
                contentPadding = PaddingValues(start = edgePadding, end = edgePadding, top = 2.dp, bottom = 6.dp),
                pageSpacing = 10.dp,
                beyondViewportPageCount = if (visible && currentCoverReady) 1 else 0,
                flingBehavior = fling, userScrollEnabled = active,
                key = { entries[it].key },
                modifier = Modifier.fillMaxWidth().height(itemHeight + 8.dp)
                    .semantics { stateDescription = "番剧 " + (state.currentPage + 1) + " / " + entries.size }) { index ->
                val emphasis = { (1f - abs(index - position.value)).coerceIn(0f, 1f) }
                AnimeFeaturedCard(entries[index], compactLayout, imageWidthPx, imageHeightPx,
                    onReady = { readyCovers[entries[index].coverUrl] = true },
                    modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                        val scale = if (motion) .92f + .08f * emphasis() else 1f
                        scaleX = scale; scaleY = scale
                        alpha = .72f + .28f * emphasis()
                    }.mangaCoverCard(20.dp),
                    offset = { if (motion) (index - position.value).coerceIn(-1f, 1f) *
                        (if (rtl) -1f else 1f) else 0f },
                    enabled = active, scoresEnabled = active && index == state.settledPage && !state.isScrollInProgress,
                    onOpenScores = onOpenScores, onOpen = onOpen)
            }
            Row(Modifier.fillMaxWidth().height(footerHeight), verticalAlignment = Alignment.CenterVertically) {
                if (desktop) MotionIconButton(onClick = { selectPage(state.currentPage - 1) },
                    enabled = active && state.currentPage > 0, modifier = Modifier.size(44.dp),
                    tooltip = "上一部番剧") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一部番剧", Modifier.size(18.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val color = MaterialTheme.colorScheme.onSurface
                    if (entries.size > 1) Canvas(Modifier.size(width = (entries.size * 16).dp, height = 4.dp)
                        .clearAndSetSemantics { }) {
                        repeat(entries.size) { index ->
                            val visualIndex = if (rtl) entries.lastIndex - index else index
                            val weight = (1f - abs(position.value - index)).coerceIn(0f, 1f)
                            val width = (4f + 10f * weight).dp.toPx()
                            drawRoundRect(color.copy(alpha = .13f + .45f * weight),
                                topLeft = Offset((visualIndex * 16 + 8).dp.toPx() - width / 2f, 0f),
                                size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2f))
                        }
                    }
                }
                if (desktop) MotionIconButton(onClick = { selectPage(state.currentPage + 1) },
                    enabled = active && state.currentPage < entries.lastIndex, modifier = Modifier.size(44.dp),
                    tooltip = "下一部番剧") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "下一部番剧", Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun AnimeFeaturedCard(
    entry: AnimeFeaturedEntry,
    compactLayout: Boolean,
    imageWidthPx: Int,
    imageHeightPx: Int,
    onReady: () -> Unit,
    modifier: Modifier,
    offset: () -> Float,
    enabled: Boolean,
    scoresEnabled: Boolean,
    onOpenScores: (AnimeFeaturedEntry) -> Unit,
    onOpen: (AnimeFeaturedEntry) -> Unit,
) {
    val context = LocalMangaContext.current
    val item = entry.discoveryItem
    val scores = observedAnimeRatingSnapshot(item.subject, item.sourceScores, enabled = scoresEnabled).scores
    val motion = LocalMotionEnabled.current
    val interaction = remember(entry.key) { MutableInteractionSource() }
    val backdrop = rememberHazeState()
    val latestOnReady by rememberUpdatedState(onReady)
    val request = remember(context, entry.coverUrl, entry.sourceId, imageWidthPx, imageHeightPx, motion) {
        ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(entry.coverUrl, entry.sourceId)
            .size(imageWidthPx, imageHeightPx).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop,
        onSuccess = { latestOnReady() })
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button,
        enabled = enabled, onClickLabel = "查看详情", onClick = { onOpen(entry) })
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(entry.title, entry.caption).filter(String::isNotBlank).joinToString("，")
        }.motionPress(interaction, enabled = enabled, pressedScale = .985f)) {
        val captionWidth = if (compactLayout) maxWidth - 20.dp else (maxWidth * .69f).coerceAtMost(390.dp)
        Box(Modifier.matchParentSize().mangaHazeSource(backdrop).background(colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Tv, null, Modifier.size(24.dp), tint = colors.onSurfaceVariant)
            Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0f, -.65f),
                modifier = Modifier.matchParentSize().graphicsLayer {
                    if (motion) {
                        scaleX = 1.04f; scaleY = 1.04f
                        translationX = offset() * 4.dp.toPx()
                    }
                })
        }
        LiquidGlassSurface(backdrop, Modifier.align(Alignment.BottomStart).padding(10.dp)
            .width(captionWidth.coerceAtLeast(1.dp)), cornerRadius = 17.dp, role = GlassSurfaceRole.GALLERY,
            elevation = 0.dp) {
            Column(Modifier.fillMaxWidth().background(colors.surface.copy(alpha = .68f), RoundedCornerShape(17.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.title, Modifier.clearAndSetSemantics { }, color = colors.onSurface,
                    fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.caption.isNotBlank()) Text(entry.caption, Modifier.clearAndSetSemantics { },
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (scores.isNotEmpty()) AnimeRatingBadges(entry.key, scores, Modifier.fillMaxWidth(),
                    foreground = colors.onSurface, onExpand = ({
                        onOpenScores(entry.copy(discoveryItem = item.copy(sourceScores = scores)))
                    }).takeIf { scoresEnabled })
            }
        }
    }
}
