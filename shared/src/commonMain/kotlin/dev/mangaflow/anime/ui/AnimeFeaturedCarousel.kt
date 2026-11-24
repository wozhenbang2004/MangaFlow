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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.discoveryProvider
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

/** Adjacent artwork peeks in without competing with the selected work's title. */
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
        val galleryInset = ((galleryWidth - 700.dp) / 2f).coerceAtLeast(0.dp)
        val peek = if (entries.size > 1) 22.dp else 0.dp
        val preferredWidth = (galleryWidth - galleryInset * 2 - peek).coerceAtLeast(1.dp)
        val compactLayout = preferredWidth < 255.dp || fontScale > 1.35f
        val footerHeight = if (entries.size == 1) 0.dp else if (desktop) 44.dp else 10.dp
        val itemHeight = when {
            compactLayout -> (158.dp * fontScale).coerceIn(178.dp, 280.dp)
            window.shortHeight -> (window.height * .42f).coerceIn(136.dp, 192.dp)
            else -> (preferredWidth * .61f).coerceIn(168.dp, 260.dp)
        }
        val galleryHeight = itemHeight + footerHeight + 8.dp
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
                contentPadding = PaddingValues(start = galleryInset, end = galleryInset + peek, top = 2.dp, bottom = 6.dp),
                pageSpacing = 6.dp,
                beyondViewportPageCount = if (visible && currentCoverReady) 1 else 0,
                flingBehavior = fling, userScrollEnabled = active,
                key = { entries[it].key },
                modifier = Modifier.fillMaxWidth().height(itemHeight + 8.dp)
                    .semantics { stateDescription = "番剧 " + (state.currentPage + 1) + " / " + entries.size }) { index ->
                val emphasis = { (1f - abs(index - position.value)).coerceIn(0f, 1f) }
                val settled = state.settledPage == index
                val acceptTap = active && !state.isScrollInProgress
                AnimeFeaturedCard(entries[index], preferredWidth < 420.dp, imageWidthPx, imageHeightPx,
                    onReady = { readyCovers[entries[index].coverUrl] = true },
                    modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                        scaleX = if (motion) .985f + .015f * emphasis() else 1f
                        scaleY = if (motion) .94f + .06f * emphasis() else 1f
                        alpha = .72f + .28f * emphasis()
                    }.mangaCoverCard(20.dp),
                    offset = { if (motion) (index - position.value).coerceIn(-1f, 1f) *
                        (if (rtl) -1f else 1f) else 0f },
                    enabled = acceptTap, scoresEnabled = acceptTap && settled,
                    actionLabel = if (settled) "查看详情" else "选中这部推荐番剧",
                    onOpenScores = onOpenScores, onOpen = { entry ->
                        if (active && !state.isScrollInProgress) {
                            if (state.settledPage == index) onOpen(entry) else selectPage(index)
                        }
                    })
            }
            if (entries.size > 1) Row(Modifier.fillMaxWidth().padding(end = peek).height(footerHeight),
                verticalAlignment = Alignment.CenterVertically) {
                if (desktop) MotionIconButton(onClick = { selectPage(state.currentPage - 1) },
                    enabled = active && state.currentPage > 0, modifier = Modifier.size(44.dp),
                    tooltip = "上一部番剧") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一部番剧", Modifier.size(18.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val color = MaterialTheme.colorScheme.onSurface
                    Canvas(Modifier.size(width = (entries.size * 16).dp, height = 4.dp)
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
    narrow: Boolean,
    imageWidthPx: Int,
    imageHeightPx: Int,
    onReady: () -> Unit,
    modifier: Modifier,
    offset: () -> Float,
    enabled: Boolean,
    scoresEnabled: Boolean,
    actionLabel: String,
    onOpenScores: (AnimeFeaturedEntry) -> Unit,
    onOpen: (AnimeFeaturedEntry) -> Unit,
) {
    val context = LocalMangaContext.current
    val item = entry.discoveryItem
    val scores = observedAnimeRatingSnapshot(item.subject, item.sourceScores, enabled = false).scores
    val primaryScore = scores.firstOrNull { it.provider == item.discoveryProvider } ?: scores.firstOrNull()
    val motion = LocalMotionEnabled.current
    val interaction = remember(entry.key) { MutableInteractionSource() }
    val latestOnReady by rememberUpdatedState(onReady)
    val request = remember(context, entry.coverUrl, entry.sourceId, imageWidthPx, imageHeightPx, motion) {
        ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(entry.coverUrl, entry.sourceId)
            .size(imageWidthPx, imageHeightPx).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop,
        onSuccess = { latestOnReady() })
    val colors = MaterialTheme.colorScheme
    val titleGradient = remember {
        Brush.verticalGradient(0f to Color.Transparent, .36f to Color.Black.copy(alpha = .57f),
            1f to Color.Black.copy(alpha = .82f))
    }
    Box(modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button,
        enabled = enabled, onClickLabel = actionLabel, onClick = { onOpen(entry) })
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(entry.title, entry.caption).filter(String::isNotBlank).joinToString("，")
        }.motionPress(interaction, enabled = enabled, pressedScale = .985f)) {
        Box(Modifier.matchParentSize().background(colors.surfaceContainerHigh),
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
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(titleGradient)
            .padding(start = 14.dp, top = 26.dp, end = 14.dp, bottom = 9.dp)) {
            Text(entry.title, Modifier.fillMaxWidth().clearAndSetSemantics { }, color = Color.White,
                fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = if (narrow) 19.sp else 22.sp, lineHeight = if (narrow) 24.sp else 28.sp,
                    lineBreak = LineBreak.Heading),
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (entry.caption.isNotBlank() || primaryScore != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (entry.caption.isNotBlank()) Text(entry.caption,
                        Modifier.weight(1f, fill = false).clearAndSetSemantics { },
                        color = Color.White.copy(alpha = .87f), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (primaryScore != null) AnimeDiscoveryPrimaryScore(primaryScore,
                        foreground = Color.White, onScores = ({
                            onOpenScores(entry.copy(discoveryItem = item.copy(sourceScores = scores)))
                        }).takeIf { scoresEnabled })
                }
            }
        }
    }
}
