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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.Image as CoilImage
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.discoveryProvider
import dev.mangaflow.platform.*
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.mangaCoverCard
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class AnimeFeaturedEntry(
    val key: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val caption: String,
    val discoveryItem: AnimeDiscoveryItem,
)

private data class FeaturedPosterInfo(val aspect: Float, val cacheKey: MemoryCache.Key?)

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
    hasCompanion: Boolean = false,
) {
    if (entries.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val motion = LocalMotionEnabled.current
        val window = LocalMangaWindow.current
        val desktop = platformKind == PlatformKind.DESKTOP
        val fontScale = density.fontScale.coerceAtLeast(1f)
        val indexVisible = desktop && !hasCompanion && entries.size > 1 && maxWidth >= 820.dp * fontScale
        val indexWidth = if (indexVisible) (maxWidth * .32f).coerceIn(246.dp, 348.dp) else 0.dp
        val indexSpace = if (indexVisible) indexWidth + 22.dp else 0.dp
        val galleryWidth = maxWidth - indexSpace
        val galleryLimit = if (desktop) 1020.dp else 700.dp
        val galleryInset = if (indexVisible || hasCompanion) 0.dp else ((galleryWidth - galleryLimit) / 2f).coerceAtLeast(0.dp)
        val peek = if (!desktop && entries.size > 1 && !indexVisible) 18.dp else 0.dp
        val preferredWidth = (galleryWidth - galleryInset * 2 - peek).coerceAtLeast(1.dp)
        val compactLayout = preferredWidth < 272.dp * fontScale || fontScale > 1.35f
        val footerHeight = if (entries.size == 1) 0.dp else if (desktop) 44.dp else 10.dp
        val itemHeight = when {
            compactLayout -> 110.dp + 82.dp * fontScale
            window.shortHeight -> (window.height * .42f).coerceIn(136.dp, 192.dp)
            desktop -> minOf((window.height * .39f).coerceIn(224.dp, 344.dp),
                (preferredWidth * .57f).coerceAtLeast(206.dp))
            indexVisible || hasCompanion -> (window.height * .33f).coerceIn(194.dp, 316.dp)
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
        val artworkKeys = remember(entries) { entries.map { it.sourceId to it.coverUrl } }
        val readyCovers = remember { mutableStateMapOf<Pair<String, String>, FeaturedPosterInfo>() }
        val coverTints = remember { mutableStateMapOf<Pair<String, String>, Color>() }
        LaunchedEffect(artworkKeys) {
            val current = artworkKeys.toSet()
            readyCovers.keys.filterNot { it in current }.forEach { readyCovers.remove(it) }
            coverTints.keys.filterNot { it in current }.forEach { coverTints.remove(it) }
        }
        val currentCoverReady = artworkKeys[state.currentPage.coerceIn(artworkKeys.indices)] in readyCovers
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        fun decodePixels(size: Dp): Int = with(density) {
            ((size.roundToPx().coerceAtLeast(1) + 63) / 64) * 64
        }
        val imageWidthPx = decodePixels(preferredWidth)
        val imageHeightPx = decodePixels(itemHeight)
        Column(Modifier.fillMaxWidth().padding(end = indexSpace).height(galleryHeight)
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
                    .semantics { stateDescription = "番剧 " + (state.settledPage.coerceIn(entries.indices) + 1) + " / " + entries.size }) { index ->
                val emphasis = { (1f - abs(index - position.value)).coerceIn(0f, 1f) }
                val settled = state.settledPage == index
                val acceptTap = active && !state.isScrollInProgress
                val key = artworkKeys[index]
                AnimeFeaturedCard(entries[index], compactLayout, imageWidthPx, imageHeightPx, readyCovers[key],
                    onReady = { readyCovers[key] = it }, onTint = { coverTints[key] = it },
                    modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                        val scale = if (motion) .978f + .022f * emphasis() else 1f
                        scaleX = scale
                        scaleY = scale
                    }.mangaCoverCard(if (desktop) 17.dp else 20.dp, shadowRadius = if (desktop) 4.dp else 11.dp),
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
                    val contrast = LocalGlassAccessibilitySettings.current.increaseContrast
                    Canvas(Modifier.size(width = (entries.size * 14).dp, height = 3.dp)
                        .clearAndSetSemantics { }) {
                        val slot = size.width / entries.size
                        fun x(index: Float) = (if (rtl) entries.lastIndex - index else index) * slot + slot / 2f
                        repeat(entries.size) { i ->
                            drawCircle(color.copy(alpha = if (contrast) .56f else .28f),
                                size.height / 2f, Offset(x(i.toFloat()), size.height / 2f))
                        }
                        // Both ends are driven by this drag's exact distance. Reversing the drag
                        // retracts the leading end before extending back toward the previous stop.
                        val page = if (motion) position.value else state.currentPage.toFloat().coerceIn(0f, entries.lastIndex.toFloat())
                        val start = page.toInt()
                        val next = (start + 1).coerceAtMost(entries.lastIndex)
                        val t = page - start
                        val rear = x(start + t * t)
                        val front = x(start + t * (2f - t))
                        val half = slot * .32f
                        val firstColor = coverTints[artworkKeys[start]] ?: color
                        val nextColor = coverTints[artworkKeys[next]] ?: color
                        val marker = if (contrast) color else lerp(color, lerp(firstColor, nextColor, t), .24f)
                        drawRoundRect(marker, Offset(minOf(rear, front) - half, 0f),
                            Size(abs(front - rear) + 2f * half, size.height), CornerRadius(size.height / 2f))
                    }
                }
                if (desktop) MotionIconButton(onClick = { selectPage(state.currentPage + 1) },
                    enabled = active && state.currentPage < entries.lastIndex, modifier = Modifier.size(44.dp),
                    tooltip = "下一部番剧") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, "下一部番剧", Modifier.size(18.dp))
                }
            }
        }
        if (indexVisible) {
            val colors = MaterialTheme.colorScheme
            val list = rememberLazyListState()
            LaunchedEffect(state.settledPage, entries.size) {
                val selected = state.settledPage.coerceIn(entries.indices)
                if (list.layoutInfo.visibleItemsInfo.none { it.index == selected }) list.scrollToItem(selected)
            }
            LazyColumn(Modifier.align(Alignment.TopEnd).width(indexWidth).height(galleryHeight).selectableGroup(),
                state = list, userScrollEnabled = active, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                    val selected = index == state.settledPage
                    val interaction = remember(entry.key) { MutableInteractionSource() }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .then(if (selected) Modifier.background(colors.surfaceContainerHigh) else Modifier)
                        .motionPress(interaction, enabled = active, pressedScale = .99f)
                        .selectable(selected, interaction, indication = null, enabled = active, role = Role.Tab,
                            onClick = { selectPage(index) }).padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AnimePoster(entry.coverUrl, Modifier.size(33.dp, 48.dp), sourceId = entry.sourceId, title = entry.title)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(entry.title, style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (entry.caption.isNotBlank()) Text(entry.caption, style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
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
    previousPoster: FeaturedPosterInfo?,
    onReady: (FeaturedPosterInfo) -> Unit,
    onTint: (Color) -> Unit,
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
    var decoded by remember(entry.sourceId, entry.coverUrl) { mutableStateOf<CoilImage?>(null) }
    // Only a new request needs a placeholder. Receiving its own cache key must not restart it.
    val request = remember(context, entry.coverUrl, entry.sourceId, imageWidthPx, imageHeightPx) {
        ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(entry.coverUrl, entry.sourceId)
            .softwareBitmap().size(imageWidthPx, imageHeightPx)
            .placeholderMemoryCacheKey(previousPoster?.cacheKey).crossfade(false).build()
    }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit, onSuccess = {
        val image = it.result.image
        decoded = image
        val ratio = if (image.height > 0 && image.width > 0) image.width.toFloat() / image.height else 2f / 3f
        latestOnReady(FeaturedPosterInfo(ratio, it.result.memoryCacheKey))
    })
    val imageState by painter.state.collectAsState()
    val showPlaceholder = imageState.painter == null && (previousPoster == null || imageState != AsyncImagePainter.State.Empty)
    val colors = MaterialTheme.colorScheme
    val accessibility = LocalGlassAccessibilitySettings.current
    val hue = rememberAnimePosterTint(entry.coverUrl, entry.sourceId, decoded)
    val reportTint by rememberUpdatedState(onTint)
    LaunchedEffect(entry.key, hue) { hue?.let(reportTint) }
    val paper = hue?.let { lerp(colors.surface, it, if (colors.surface.luminance() > .5f) .085f else .12f) }
        ?: colors.surface
    val desktopSpread = platformKind == PlatformKind.DESKTOP && !stacked
    val imageGround = hue?.let { lerp(paper, it, if (desktopSpread) .15f else .32f) } ?: paper
    val aspect = previousPoster?.aspect ?: 2f / 3f
    val landscape = aspect >= 1.18f
    val solidCaption = accessibility.reduceTransparency || accessibility.increaseContrast
    val captionInk = if (!stacked && !desktopSpread && !solidCaption)
        lerp(colors.onSurface, colors.onSurfaceVariant, .50f) else colors.onSurfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.background(paper).clickable(interactionSource = interaction, indication = null, role = Role.Button,
        enabled = enabled, onClickLabel = actionLabel, onClick = { onOpen(entry) })
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(entry.title, entry.caption).filter(String::isNotBlank).joinToString("，")
        }.motionPress(interaction, enabled = enabled, pressedScale = .985f)) {
        val textScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val spacious = desktopSpread && maxWidth >= 448.dp * textScale && maxHeight >= 220.dp * textScale
        val tallSpread = spacious && maxHeight >= 260.dp * textScale
        val art: @Composable (Modifier) -> Unit = { area ->
            Box(area.clipToBounds().background(imageGround), contentAlignment = Alignment.Center) {
                if (showPlaceholder) Icon(Icons.Outlined.Tv, null,
                    Modifier.size(26.dp), tint = colors.onSurfaceVariant)
                Image(painter, contentDescription = null, contentScale = if (landscape) ContentScale.Crop else ContentScale.Fit,
                    alignment = if (landscape) BiasAlignment(0f, -.35f) else Alignment.Center,
                    modifier = (if (desktopSpread && !landscape) Modifier.fillMaxHeight(.86f)
                        .aspectRatio(2f / 3f).mangaCoverCard(6.dp, shadowRadius = 4.dp) else Modifier.matchParentSize())
                        .graphicsLayer {
                            if (motion && landscape) {
                                val distance = offset()
                                val travel = minOf(size.width * .026f, 12.dp.toPx())
                                val scale = 1f + .055f * abs(distance)
                                scaleX = scale
                                scaleY = scale
                                translationX = -distance * travel
                            }
                        })
                if (stacked && !solidCaption) {
                    Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp).drawWithCache {
                        val fade = Brush.verticalGradient(listOf(paper.copy(alpha = 0f), paper))
                        onDrawBehind { drawRect(fade) }
                    })
                }
            }
        }
        val information: @Composable (Modifier) -> Unit = { area ->
            Column(area.graphicsLayer {
                if (motion) {
                    val distance = offset()
                    translationX = distance * (if (stacked) 6.dp else 10.dp).toPx()
                    alpha = if (accessibility.increaseContrast) 1f else 1f - .16f * abs(distance)
                }
            }.padding(horizontal = if (spacious) 24.dp else if (stacked) 14.dp else 17.dp,
                vertical = if (stacked) 8.dp else 14.dp),
                verticalArrangement = if (stacked) Arrangement.spacedBy(3.dp) else Arrangement.Center) {
                if (!stacked && entry.caption.isNotBlank()) {
                    Text(entry.caption, Modifier.clearAndSetSemantics { }, color = captionInk,
                        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(7.dp))
                }
                Text(entry.title, Modifier.fillMaxWidth().clearAndSetSemantics { }, color = colors.onSurface,
                    style = animeWorkTitleStyle(large = true).copy(fontSize = if (spacious) 34.sp else if (stacked) 21.sp else 24.sp,
                        lineHeight = if (spacious) 41.sp else if (stacked) 26.sp else 30.sp),
                    maxLines = if (stacked || spacious && !tallSpread) 2 else 3,
                    overflow = TextOverflow.Ellipsis)
                if (primaryScore != null || stacked && entry.caption.isNotBlank()) {
                    if (!stacked) Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (stacked && entry.caption.isNotBlank()) Text(entry.caption,
                            Modifier.weight(1f, fill = false).clearAndSetSemantics { },
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (primaryScore != null) AnimeDiscoveryPrimaryScore(primaryScore, foreground = captionInk,
                            onScores = ({ onOpenScores(entry.copy(discoveryItem = item.copy(sourceScores = scores))) }).takeIf { scoresEnabled })
                    }
                }
                if (tallSpread) {
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(15.dp), tint = colors.onSurface)
                    }
                }
            }
        }
        if (stacked) Column(Modifier.fillMaxSize()) {
            art(Modifier.fillMaxWidth().weight(1f))
            information(Modifier.fillMaxWidth())
        } else if (desktopSpread) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                art(Modifier.weight(if (landscape) 1.12f else .83f).fillMaxHeight())
                information(Modifier.weight(1f).fillMaxHeight())
            }
        } else if (solidCaption) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                information(Modifier.weight(1f).fillMaxHeight())
                art(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            if (landscape) art(Modifier.matchParentSize())
            else {
                Spacer(Modifier.matchParentSize().paintFeaturedPortrait(painter, aspect))
                if (showPlaceholder) Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(.5f),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Tv, null, Modifier.size(26.dp), tint = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.matchParentSize().drawWithCache {
                val edge = if (landscape) .5f else 1f - size.height * aspect / size.width.coerceAtLeast(1f)
                val clearAt = (edge + .14f).coerceIn(.64f, .96f)
                val shoulder = .49f + (clearAt - .49f) * .55f
                val veil = Brush.horizontalGradient(0f to paper.copy(alpha = .77f), .39f to paper.copy(alpha = .75f),
                    .49f to paper.copy(alpha = .74f), shoulder to paper.copy(alpha = .23f), clearAt to paper.copy(alpha = 0f),
                    startX = if (rtl) size.width else 0f, endX = if (rtl) 0f else size.width)
                onDrawBehind { drawRect(veil) }
            })
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                information(Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Extend only the facing edge beneath the title. The sharp poster and its reflection share a pixel boundary. */
private fun Modifier.paintFeaturedPortrait(painter: Painter, aspect: Float): Modifier = drawWithCache {
    val posterWidth = (size.height * aspect).roundToInt().coerceAtLeast(1).toFloat()
    val poster = Size(posterWidth, size.height)
    val rtl = layoutDirection == LayoutDirection.Rtl
    val posterLeft = if (rtl) 0f else size.width - posterWidth
    val edge = if (rtl) posterWidth else posterLeft
    val extension = (size.width - posterWidth).coerceAtLeast(0f)
    val sample = minOf(posterWidth * .26f, 46.dp.toPx()).coerceAtLeast(1f)
    onDrawBehind {
        if (extension > 0f) withTransform({
            if (rtl) clipRect(left = edge) else clipRect(right = edge)
            scale(-extension / sample, 1f, Offset(edge, 0f))
            translate(posterLeft, 0f)
        }) { with(painter) { draw(poster) } }
        withTransform({ translate(posterLeft, 0f) }) { with(painter) { draw(poster) } }
    }
}
