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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.Image as CoilImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.discoveryProvider
import dev.mangaflow.platform.*
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.mangaCoverCard
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.*
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
        val peek = if (entries.size > 1) 18.dp else 0.dp
        val preferredWidth = (galleryWidth - galleryInset * 2 - peek).coerceAtLeast(1.dp)
        val compactLayout = preferredWidth < 272.dp * fontScale || fontScale > 1.35f
        val footerHeight = if (entries.size == 1) 0.dp else if (desktop) 44.dp else 10.dp
        val itemHeight = when {
            compactLayout -> 110.dp + 82.dp * fontScale
            window.shortHeight -> (window.height * .42f).coerceIn(136.dp, 192.dp)
            else -> (preferredWidth * .63f).coerceIn(174.dp, 270.dp)
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
                AnimeFeaturedCard(entries[index], compactLayout, imageWidthPx, imageHeightPx,
                    onReady = { readyCovers[entries[index].coverUrl] = true },
                    modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                        scaleX = if (motion) .985f + .015f * emphasis() else 1f
                        scaleY = if (motion) .94f + .06f * emphasis() else 1f
                        alpha = .72f + .28f * emphasis()
                    }.mangaCoverCard(20.dp, shadowRadius = 11.dp),
                    offset = { if (motion) (index - position.value).coerceIn(-1f, 1f) *
                        (if (rtl) -1f else 1f) else 0f },
                    useGlass = visible && acceptTap && settled && readyCovers[entries[index].coverUrl] == true,
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
                    Canvas(Modifier.size(width = (entries.size * 14).dp, height = 3.dp)
                        .clearAndSetSemantics { }) {
                        // Keep each position visible over wallpaper, even when it is not selected.
                        val slot = size.width / entries.size
                        repeat(entries.size) { index ->
                            val visualIndex = if (rtl) entries.lastIndex - index else index
                            val weight = (1f - abs(position.value - index)).coerceIn(0f, 1f)
                            val width = size.height + (slot * .88f - size.height) * weight
                            drawRoundRect(color.copy(alpha = .25f + .48f * weight),
                                topLeft = Offset((visualIndex + .5f) * slot - width / 2f, 0f),
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
    stacked: Boolean,
    imageWidthPx: Int,
    imageHeightPx: Int,
    onReady: () -> Unit,
    modifier: Modifier,
    offset: () -> Float,
    useGlass: Boolean,
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
    var decoded by remember(entry.sourceId, entry.coverUrl) { mutableStateOf<CoilImage?>(null) }
    val request = remember(context, entry.coverUrl, entry.sourceId, imageWidthPx, imageHeightPx, motion) {
        ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(entry.coverUrl, entry.sourceId)
            .softwareBitmap().size(imageWidthPx, imageHeightPx).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit,
        onSuccess = { decoded = it.result.image; latestOnReady() })
    val colors = MaterialTheme.colorScheme
    val accessibility = LocalGlassAccessibilitySettings.current
    val hue = rememberAnimePosterTint(entry.coverUrl, entry.sourceId, decoded)
    val paper = hue?.let { lerp(colors.surface, it, if (colors.surface.luminance() > .5f) .085f else .12f) }
        ?: colors.surface
    val imageGround = hue?.let { lerp(paper, it, .32f) } ?: paper
    val coverGlass = rememberHazeState()
    val frame = painter.intrinsicSize
    val landscape = frame != Size.Unspecified && frame.width.isFinite() && frame.height > 0f &&
        frame.width / frame.height >= 1.18f
    val panoramic = !stacked && landscape && !accessibility.reduceTransparency && !accessibility.increaseContrast
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(modifier.background(paper).clickable(interactionSource = interaction, indication = null, role = Role.Button,
        enabled = enabled, onClickLabel = actionLabel, onClick = { onOpen(entry) })
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(entry.title, entry.caption).filter(String::isNotBlank).joinToString("，")
        }.motionPress(interaction, enabled = enabled, pressedScale = .985f)) {
        val art: @Composable (Modifier) -> Unit = { area ->
            Box(area.clipToBounds().background(imageGround), contentAlignment = Alignment.Center) {
                if (decoded == null) Icon(Icons.Outlined.Tv, null, Modifier.size(26.dp), tint = colors.onSurfaceVariant)
                Image(painter, contentDescription = null, contentScale = if (landscape) ContentScale.Crop else ContentScale.Fit,
                    alignment = if (landscape) BiasAlignment(0f, -.35f) else Alignment.Center,
                    modifier = Modifier.matchParentSize()
                        .then(if (useGlass && !panoramic) Modifier.mangaHazeSource(coverGlass) else Modifier)
                        .graphicsLayer {
                            if (motion && landscape) {
                                scaleX = 1.03f; scaleY = 1.03f
                                translationX = offset() * 3.dp.toPx()
                            }
                        })
                // A narrow optical seam catches the actual picture. The lettering sits on its own paper.
                if (useGlass && !panoramic && !accessibility.reduceTransparency && !accessibility.increaseContrast) {
                    val seam = if (stacked) Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(16.dp)
                        else Modifier.align(Alignment.CenterStart).fillMaxHeight().width(14.dp)
                    LiquidGlassSurface(coverGlass, modifier = seam.graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }.drawWithCache {
                        val feather = when {
                            stacked -> Brush.verticalGradient(listOf(Color.Transparent, Color.Black))
                            rtl -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Black))
                            else -> Brush.horizontalGradient(listOf(Color.Black, Color.Transparent))
                        }
                        onDrawWithContent {
                            drawContent()
                            drawRect(feather, blendMode = BlendMode.DstIn)
                        }
                    }, cornerRadius = 0.dp, role = GlassSurfaceRole.GALLERY, elevation = 0.dp,
                        softCard = false, surfaceColor = paper, preferSource = true,
                        contentColor = colors.onSurface) {}
                }
            }
        }
        val information: @Composable (Modifier) -> Unit = { area ->
            Column(area.padding(horizontal = if (stacked) 14.dp else 17.dp, vertical = if (stacked) 8.dp else 14.dp),
                verticalArrangement = if (stacked) Arrangement.spacedBy(3.dp) else Arrangement.Center) {
                if (!stacked && entry.caption.isNotBlank()) {
                    Text(entry.caption, Modifier.clearAndSetSemantics { }, color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(7.dp))
                }
                Text(entry.title, Modifier.fillMaxWidth().clearAndSetSemantics { }, color = colors.onSurface,
                    style = animeWorkTitleStyle(large = true).copy(fontSize = if (stacked) 21.sp else 24.sp,
                        lineHeight = if (stacked) 26.sp else 30.sp), maxLines = if (stacked) 2 else 3,
                    overflow = TextOverflow.Ellipsis)
                if (primaryScore != null || stacked && entry.caption.isNotBlank()) {
                    if (!stacked) Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (stacked && entry.caption.isNotBlank()) Text(entry.caption,
                            Modifier.weight(1f, fill = false).clearAndSetSemantics { },
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (primaryScore != null) AnimeDiscoveryPrimaryScore(primaryScore, foreground = colors.onSurfaceVariant,
                            onScores = ({ onOpenScores(entry.copy(discoveryItem = item.copy(sourceScores = scores))) }).takeIf { scoresEnabled })
                    }
                }
            }
        }
        if (stacked) Column(Modifier.fillMaxSize()) {
            art(Modifier.fillMaxWidth().weight(1f))
            information(Modifier.fillMaxWidth())
        } else {
            if (panoramic) {
                art(Modifier.matchParentSize())
                Box(Modifier.matchParentSize().drawWithCache {
                    val edge = Brush.horizontalGradient(0f to paper, .43f to paper, .53f to paper.copy(alpha = .95f),
                        .74f to paper.copy(alpha = 0f),
                        startX = if (rtl) size.width else 0f, endX = if (rtl) 0f else size.width)
                    onDrawBehind { drawRect(edge) }
                })
            }
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                information(Modifier.weight(1f).fillMaxHeight())
                if (panoramic) Spacer(Modifier.weight(1f)) else art(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}
