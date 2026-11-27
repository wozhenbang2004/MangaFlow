package dev.mangaflow.anime.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import coil3.decode.DataSource
import coil3.request.crossfade
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.discoveryProvider
import dev.mangaflow.platform.*
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.mangaCoverCard
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.rememberFeelFeedback
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
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
    wide: Boolean,
    navigationInset: Dp,
    viewportHeight: Dp,
) {
    if (entries.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val motion = LocalMotionEnabled.current
        val window = LocalMangaWindow.current
        val desktop = platformKind == PlatformKind.DESKTOP
        val fontScale = density.fontScale.coerceAtLeast(1f)
        val galleryInset = if (wide) 0.dp else ((maxWidth - 700.dp) / 2f).coerceAtLeast(0.dp)
        val peek = if (!wide && !desktop && entries.size > 1) 18.dp else 0.dp
        val preferredWidth = (maxWidth - galleryInset * 2 - peek).coerceAtLeast(1.dp)
        val compactLayout = !wide && (preferredWidth < 272.dp * fontScale || fontScale > 1.35f)
        val footerHeight = when {
            entries.size == 1 -> 0.dp
            wide || desktop -> 48.dp
            else -> 10.dp
        }
        val itemHeight = when {
            wide -> navigationInset + ((viewportHeight - navigationInset) * .45f)
                .coerceIn(156.dp * fontScale, 360.dp * fontScale)
            compactLayout -> 110.dp + 82.dp * fontScale
            window.shortHeight -> (window.height * .42f).coerceIn(136.dp, 192.dp)
            else -> (preferredWidth * .63f).coerceIn(174.dp, 270.dp)
        }
        val galleryHeight = itemHeight + footerHeight + if (wide) 0.dp else 8.dp
        val state = rememberPagerState(
            initialPage = entries.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0),
        ) { entries.size }
        val latestOnSelected by rememberUpdatedState(onSelected)
        fun settledKey() = state.layoutInfo.visiblePagesInfo.firstOrNull { it.index == state.settledPage }?.key as? String
        LaunchedEffect(state) {
            snapshotFlow { if (state.isScrollInProgress) null else settledKey() }.collect { key -> key?.let(latestOnSelected) }
        }
        val scope = rememberCoroutineScope()
        val feedback = rememberFeelFeedback()
        val pageKeys = remember(entries) { entries.map { it.key } }
        var requestedKey by remember(state) { mutableStateOf<String?>(null) }
        var selectionJob by remember(state) { mutableStateOf<Job?>(null) }
        var commandGeneration by remember(state) { mutableIntStateOf(0) }
        fun navigationIndex(): Int = requestedKey?.let(pageKeys::indexOf)?.takeIf { it >= 0 } ?: state.currentPage
        fun selectPage(index: Int): Boolean {
            val target = pageKeys.getOrNull(index) ?: return false
            if (!active || requestedKey == target || requestedKey == null && !state.isScrollInProgress && settledKey() == target) return false
            val generation = ++commandGeneration
            selectionJob?.cancel()
            requestedKey = target
            feedback.perform(FeelEvent.Selection)
            selectionJob = scope.launch {
                try {
                    val distance = abs(index - state.currentPage)
                    if (motion) state.animateScrollToPage(index, animationSpec = tween(
                        durationMillis = if (distance <= 1) MangaMotion.Content else MangaMotion.Enter,
                        easing = MangaMotion.EnterEasing))
                    else state.scrollToPage(index)
                } finally {
                    if (generation == commandGeneration) requestedKey = null
                }
            }
            return true
        }
        LaunchedEffect(state, pageKeys, active) {
            if (!active) return@LaunchedEffect
            var drag: DragInteraction.Start? = null
            var origin: String? = null
            var generation = commandGeneration
            try {
                state.interactionSource.interactions.collectLatest { event ->
                    when (event) {
                        is DragInteraction.Start -> {
                            selectionJob?.cancel()
                            requestedKey = null
                            drag = event
                            origin = settledKey()
                            generation = commandGeneration
                        }
                        is DragInteraction.Cancel -> {
                            if (event.start !== drag) return@collectLatest
                            drag = null
                        }
                        is DragInteraction.Stop -> {
                            if (event.start !== drag) return@collectLatest
                            drag = null
                            val from = origin
                            // The fling starts after the pointer release, and a later gesture can cancel this wait.
                            withFrameNanos { }
                            val landed = snapshotFlow { if (state.isScrollInProgress) null else settledKey() }.first { it != null }
                            if (generation == commandGeneration && from != null && from != landed) feedback.perform(FeelEvent.Selection)
                        }
                        else -> Unit
                    }
                }
            } finally {
                selectionJob?.cancel()
                requestedKey = null
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
            val step = if (wide) 128 else 64
            ((size.roundToPx().coerceAtLeast(1) + step - 1) / step) * step
        }
        val imageWidthPx = decodePixels(preferredWidth)
        val imageHeightPx = decodePixels(if (wide) itemHeight * 1.7f else itemHeight)
        Column(Modifier.fillMaxWidth().height(galleryHeight)
            .onPreviewKeyEvent { event ->
                if (!active || entries.size <= 1 || event.type != KeyEventType.KeyDown ||
                    event.isAltPressed || event.isCtrlPressed || event.isMetaPressed) false
                else when (event.key) {
                    Key.DirectionLeft -> selectPage(navigationIndex() + if (rtl) 1 else -1)
                    Key.DirectionRight -> selectPage(navigationIndex() + if (rtl) -1 else 1)
                    else -> false
                }
            }.focusGroup()) {
            HorizontalPager(state = state, pageSize = PageSize.Fixed(preferredWidth),
                contentPadding = PaddingValues(start = galleryInset, end = galleryInset + peek,
                    top = if (wide) 0.dp else 2.dp, bottom = if (wide) 0.dp else 6.dp),
                pageSpacing = if (wide) 0.dp else 6.dp,
                beyondViewportPageCount = if (visible && currentCoverReady) 1 else 0,
                flingBehavior = fling, userScrollEnabled = active,
                key = { entries[it].key },
                modifier = Modifier.fillMaxWidth().height(itemHeight + if (wide) 0.dp else 8.dp)
                    .semantics { stateDescription = "番剧 " + (state.settledPage.coerceIn(entries.indices) + 1) + " / " + entries.size }) { index ->
                val emphasis = { (1f - abs(index - position.value)).coerceIn(0f, 1f) }
                val settled = state.settledPage == index
                val acceptTap = active && !state.isScrollInProgress
                val key = artworkKeys[index]
                AnimeFeaturedCard(entries[index], compactLayout, wide, navigationInset, imageWidthPx, imageHeightPx, readyCovers[key],
                    onReady = { readyCovers[key] = it }, onTint = { coverTints[key] = it },
                    modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                        val scale = if (motion && !wide) .978f + .022f * emphasis() else 1f
                        scaleX = scale
                        scaleY = scale
                    }.then(if (wide) Modifier.clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                        else Modifier.mangaCoverCard(if (desktop) 17.dp else 20.dp, shadowRadius = if (desktop) 4.dp else 11.dp)),
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
                if (desktop && !wide) MotionIconButton(onClick = { selectPage(navigationIndex() - 1) },
                    enabled = active && navigationIndex() > 0, modifier = Modifier.size(44.dp),
                    tooltip = "上一部番剧") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一部番剧", Modifier.size(18.dp))
                }
                Box(Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = if (wide) Alignment.CenterStart else Alignment.Center) {
                    val color = MaterialTheme.colorScheme.onSurface
                    val contrast = LocalGlassAccessibilitySettings.current.increaseContrast
                    val slotWidth = if (wide) 48.dp else 14.dp
                    Box(Modifier.width(slotWidth * entries.size).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxWidth().height(3.dp).clearAndSetSemantics { }) {
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
                            val half = minOf(slot * .32f, 8.dp.toPx())
                            val firstColor = coverTints[artworkKeys[start]] ?: color
                            val nextColor = coverTints[artworkKeys[next]] ?: color
                            val marker = if (contrast) color else lerp(color, lerp(firstColor, nextColor, t), .24f)
                            drawRoundRect(marker, Offset(minOf(rear, front) - half, 0f),
                                Size(abs(front - rear) + 2f * half, size.height), CornerRadius(size.height / 2f))
                        }
                        if (wide) Row(Modifier.fillMaxSize().selectableGroup()) {
                            entries.forEachIndexed { index, entry ->
                                Box(Modifier.weight(1f).fillMaxHeight().selectable(selected = index == state.settledPage,
                                    enabled = active, role = Role.Tab, onClick = { selectPage(index) })
                                    .semantics { contentDescription = entry.title })
                            }
                        }
                    }
                }
                if (desktop && wide) MotionIconButton(onClick = { selectPage(navigationIndex() - 1) },
                    enabled = active && navigationIndex() > 0, modifier = Modifier.size(44.dp), tooltip = "上一部番剧") {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一部番剧", Modifier.size(18.dp))
                }
                if (desktop) MotionIconButton(onClick = { selectPage(navigationIndex() + 1) },
                    enabled = active && navigationIndex() < entries.lastIndex, modifier = Modifier.size(44.dp),
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
    wide: Boolean,
    navigationInset: Dp,
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
    var deliveredPainter by remember(entry.sourceId, entry.coverUrl) { mutableStateOf<Painter?>(null) }
    var fadeFirstDelivery by remember(entry.sourceId, entry.coverUrl) { mutableStateOf(false) }
    // Only a new request needs a placeholder. Receiving its own cache key must not restart it.
    val request = remember(context, entry.coverUrl, entry.sourceId, imageWidthPx, imageHeightPx) {
        ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(entry.coverUrl, entry.sourceId)
            .softwareBitmap().size(imageWidthPx, imageHeightPx)
            .placeholderMemoryCacheKey(previousPoster?.cacheKey).crossfade(false).build()
    }
    val painter = key(entry.sourceId, entry.coverUrl) {
        rememberAsyncImagePainter(request, contentScale = ContentScale.Fit, onSuccess = {
            val image = it.result.image
            if (deliveredPainter == null) fadeFirstDelivery = it.result.dataSource != DataSource.MEMORY_CACHE && previousPoster == null
            deliveredPainter = it.painter
            decoded = image
            val ratio = if (image.height > 0 && image.width > 0) image.width.toFloat() / image.height else 2f / 3f
            latestOnReady(FeaturedPosterInfo(ratio, it.result.memoryCacheKey))
        })
    }
    val imageState by painter.state.collectAsState()
    val artwork = deliveredPainter ?: painter
    val showPlaceholder = deliveredPainter == null && imageState.painter == null &&
        (previousPoster == null || imageState != AsyncImagePainter.State.Empty)
    val colors = MaterialTheme.colorScheme
    val accessibility = LocalGlassAccessibilitySettings.current
    val solidCaption = accessibility.reduceTransparency || accessibility.increaseContrast
    val artworkAlpha = key(entry.sourceId, entry.coverUrl) {
        animateFloatAsState(if (deliveredPainter != null || previousPoster != null) 1f else 0f,
            if (fadeFirstDelivery && motion && !solidCaption) tween(MangaMotion.Content, easing = MangaMotion.EnterEasing) else snap(),
            label = "featured-art-arrival")
    }
    val hue = rememberAnimePosterTint(entry.coverUrl, entry.sourceId, decoded)
    val reportTint by rememberUpdatedState(onTint)
    LaunchedEffect(entry.key, hue) { hue?.let(reportTint) }
    val lightPaper = colors.surface.luminance() > .5f
    val paperTarget = hue?.let { lerp(colors.surface, it, if (wide) .27f else if (lightPaper) .085f else .12f) }
        ?: colors.surface
    val desktopSpread = platformKind == PlatformKind.DESKTOP && !stacked
    val paletteMotion = motion && !solidCaption
    val paper = animateColorAsState(paperTarget, if (paletteMotion) tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing)
        else snap(), label = "featured-paper")
    val imageGround = animateColorAsState(hue?.let { lerp(paperTarget, it, if (desktopSpread) .15f else .32f) } ?: paperTarget,
        if (paletteMotion) tween(MangaMotion.Enter, easing = MangaMotion.EnterEasing) else snap(), label = "featured-ground")
    val aspect = previousPoster?.aspect ?: 2f / 3f
    val landscape = aspect >= 1.18f
    val captionInk = if (!stacked && !desktopSpread && !solidCaption)
        lerp(colors.onSurface, colors.onSurfaceVariant, .50f) else colors.onSurfaceVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.drawBehind { drawRect(paper.value) }.clickable(interactionSource = interaction, indication = null, role = Role.Button,
        enabled = enabled, onClickLabel = actionLabel, onClick = { onOpen(entry) })
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(entry.title, entry.caption).filter(String::isNotBlank).joinToString("，")
        }.motionPress(interaction, enabled = enabled, pressedScale = .985f)) {
        val textScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (wide) {
            val bodyHeight = maxHeight - navigationInset
            val roomy = bodyHeight >= 200.dp * textScale
            val largeTitle = roomy && maxWidth >= 840.dp * textScale
            val titleWidth = minOf(maxWidth * .49f, 520.dp * textScale)
            // Portrait-only catalogues keep one sharp piece of art, anchored near its upper third.
            // Keep the text ground continuous with the art's sampled color on either theme.
            val artWidth = when {
                solidCaption -> maxWidth - titleWidth
                landscape -> maxWidth
                else -> minOf(maxWidth * .63f, maxHeight * aspect * 1.7f)
            }
            Box(Modifier.align(Alignment.CenterEnd).width(artWidth).fillMaxHeight().clipToBounds(),
                contentAlignment = Alignment.Center) {
                if (showPlaceholder) Icon(Icons.Outlined.Tv, null, Modifier.size(28.dp), tint = colors.onSurfaceVariant)
                Image(artwork, null, Modifier.matchParentSize().graphicsLayer {
                    alpha = artworkAlpha.value
                    if (motion && !solidCaption) {
                        val distance = offset()
                        val scale = 1f + .035f * abs(distance)
                        scaleX = scale
                        scaleY = scale
                        translationX = -distance * minOf(size.width * .013f, (if (landscape) 9.dp else 5.dp).toPx())
                    }
                }, alignment = BiasAlignment(0f, if (landscape) -.28f else -.58f),
                    contentScale = if (solidCaption && !landscape) ContentScale.Fit else ContentScale.Crop)
                if (!solidCaption && !landscape) Spacer(Modifier.align(Alignment.CenterStart)
                    .fillMaxHeight().fillMaxWidth(.26f).featuredTitleFade(paper))
            }
            if (landscape && !solidCaption) Spacer(Modifier.align(Alignment.CenterStart)
                .width(minOf(maxWidth, titleWidth * 1.42f)).fillMaxHeight().featuredTitleFade(paper))
            Box(Modifier.fillMaxSize().padding(top = navigationInset)) {
                Column(Modifier.align(Alignment.BottomStart).width(titleWidth).graphicsLayer {
                    if (motion) {
                        val distance = offset()
                        translationX = distance * (if (roomy) 16.dp else 9.dp).toPx()
                        alpha = if (solidCaption) 1f else 1f - .12f * abs(distance)
                    }
                }.padding(start = if (largeTitle) 34.dp else 20.dp, end = 10.dp,
                        top = 8.dp, bottom = if (roomy) 24.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (roomy) 8.dp else 4.dp)) {
                    Text(entry.title, Modifier.clearAndSetSemantics { }, color = colors.onSurface,
                        style = animeWorkTitleStyle(large = true).copy(
                            fontSize = if (largeTitle) 41.sp else 30.sp,
                            lineHeight = if (largeTitle) 48.sp else 36.sp),
                        maxLines = if (roomy) 2 else 1, overflow = TextOverflow.Ellipsis)
                    if (entry.caption.isNotBlank() || primaryScore != null) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (entry.caption.isNotBlank()) Text(entry.caption,
                            Modifier.weight(1f, fill = false).clearAndSetSemantics { },
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (primaryScore != null) AnimeDiscoveryPrimaryScore(primaryScore, foreground = colors.onSurface,
                            onScores = ({ onOpenScores(entry.copy(discoveryItem = item.copy(sourceScores = scores))) }).takeIf { scoresEnabled })
                    }
                    Row(Modifier.clearAndSetSemantics { }
                        .background(colors.primary, RoundedCornerShape(50))
                        .padding(horizontal = 13.dp, vertical = if (roomy) 7.dp else 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("查看详情", style = MaterialTheme.typography.labelLarge, color = colors.onPrimary, maxLines = 1)
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(15.dp), tint = colors.onPrimary)
                    }
                }
            }
            return@BoxWithConstraints
        }
        val spacious = desktopSpread && maxWidth >= 448.dp * textScale && maxHeight >= 220.dp * textScale
        val tallSpread = spacious && maxHeight >= 260.dp * textScale
        val art: @Composable (Modifier) -> Unit = { area ->
            Box(area.clipToBounds().drawBehind { drawRect(imageGround.value) }, contentAlignment = Alignment.Center) {
                if (showPlaceholder) Icon(Icons.Outlined.Tv, null,
                    Modifier.size(26.dp), tint = colors.onSurfaceVariant)
                Image(artwork, contentDescription = null, contentScale = if (landscape) ContentScale.Crop else ContentScale.Fit,
                    alignment = if (landscape) BiasAlignment(0f, -.35f) else Alignment.Center,
                    modifier = (if (desktopSpread && !landscape) Modifier.fillMaxHeight(.86f)
                        .aspectRatio(2f / 3f).mangaCoverCard(6.dp, shadowRadius = 4.dp) else Modifier.matchParentSize())
                        .graphicsLayer {
                            alpha = artworkAlpha.value
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
                        val tone = paper.value
                        val fade = Brush.verticalGradient(listOf(tone.copy(alpha = 0f), tone))
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
            else art(Modifier.align(Alignment.CenterEnd).width(minOf(maxWidth, maxHeight * aspect)).fillMaxHeight())
            Spacer(Modifier.matchParentSize().drawWithCache {
                val edge = if (landscape) .5f else 1f - size.height * aspect / size.width.coerceAtLeast(1f)
                val clearAt = (edge + .14f).coerceIn(.64f, .96f)
                val shoulder = .49f + (clearAt - .49f) * .55f
                val tone = paper.value
                val veil = Brush.horizontalGradient(0f to tone.copy(alpha = .77f), .39f to tone.copy(alpha = .75f),
                    .49f to tone.copy(alpha = .74f), shoulder to tone.copy(alpha = .23f), clearAt to tone.copy(alpha = 0f),
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

/** The far edge stays clear. Only the reading side of the artwork carries the title's ground. */
private fun Modifier.featuredTitleFade(paper: State<Color>): Modifier = drawWithCache {
    val color = paper.value
    val rtl = layoutDirection == LayoutDirection.Rtl
    val brush = Brush.horizontalGradient(0f to color, .46f to color.copy(alpha = .97f),
        .72f to color.copy(alpha = .83f), 1f to color.copy(alpha = 0f),
        startX = if (rtl) size.width else 0f, endX = if (rtl) 0f else size.width)
    onDrawBehind { drawRect(brush) }
}
