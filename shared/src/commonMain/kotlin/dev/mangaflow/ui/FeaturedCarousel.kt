package dev.mangaflow.ui

import dev.mangaflow.experience.sourceNetwork

import dev.mangaflow.typography.MangaFontScene

import dev.mangaflow.ui.platform.*

import coil3.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.MotionTabContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.layout.layout
import dev.mangaflow.platform.LocalMangaContext as LocalContext
import dev.mangaflow.platform.*
import dev.mangaflow.platform.storage.applicationContext
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.FeelEvent
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.motion.rememberFeelFeedback
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Foundation Pager's equal edge padding keeps first, middle and last recommendations centered. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FeaturedCarousel(manga: List<Manga>, repository: MangaRepository, visible: Boolean,
    onOpen: (Manga) -> Unit, onRatings: ((Manga) -> Unit)? = null, loading: Boolean = false,
    showSource: Boolean = false,
    desktopActions: ((Manga) -> List<DesktopMenuAction>)? = null) {
    if (manga.isEmpty() && !loading) return
    if (platformKind == PlatformKind.DESKTOP) {
        DesktopFeaturedRecommendations(manga, repository, visible, onOpen, onRatings, loading, showSource, desktopActions)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val galleryWidth = maxWidth
        val density = LocalDensity.current
        val motion = LocalMotionEnabled.current
        val window = LocalMangaWindow.current
        val fontScale = density.fontScale.coerceAtLeast(1f)
        // This is the complete gallery budget: artwork, labels, shadow and dots.
        // Image decode and score arrivals never resize the surrounding list.
        val androidEditorial = platformKind == PlatformKind.ANDROID && !showSource
        val galleryHeight = when {
            window.shortHeight -> (window.height * .5f).coerceIn(164.dp, 220.dp)
            androidEditorial -> (window.height * .30f).coerceIn(192.dp, 232.dp)
            else -> (window.height / 3f).coerceIn(220.dp, 280.dp)
        }
        val preferredWidth = (galleryWidth * .85f).coerceAtMost(352.dp)
        val inlineInformation = preferredWidth >= 224.dp * fontScale
        val titleLines = if (window.shortHeight || fontScale > 1.3f) 1 else 2
        val itemHeight = galleryHeight - 22.dp
        val captionHeight = if (inlineInformation) 0.dp else featuredCaptionHeight(itemHeight, titleLines, fontScale)
        val artworkHeight = (itemHeight - captionHeight).coerceAtLeast(1.dp)
        val edgePadding = ((galleryWidth - preferredWidth) / 2f).coerceAtLeast(0.dp)
        if (manga.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(galleryHeight), contentAlignment = Alignment.Center) {
                if (androidEditorial) {
                    val colors = MaterialTheme.colorScheme
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                        .size(width = preferredWidth, height = itemHeight)
                        .background(colors.onSurface.copy(alpha = .045f), RoundedCornerShape(22.dp))) {
                        val cover = if (inlineInformation) {
                            Modifier.align(Alignment.CenterStart).padding(8.dp)
                                .fillMaxWidth(.53f).fillMaxHeight()
                        } else {
                            Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                                .height((artworkHeight - 16.dp).coerceAtLeast(1.dp)).aspectRatio(2f / 3f)
                        }
                        Box(cover.background(colors.onSurface.copy(alpha = .07f), RoundedCornerShape(14.dp)))
                        Text("正在加载推荐",
                            modifier = if (inlineInformation) Modifier.align(Alignment.CenterEnd)
                                .fillMaxWidth(.44f).padding(horizontal = 8.dp)
                            else Modifier.align(Alignment.BottomCenter).padding(horizontal = 8.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else QuietLoading("正在翻开书页…")
            }
            return@BoxWithConstraints
        }
        val state = rememberPagerState { manga.size }
        val scope = rememberCoroutineScope()
        val feedback = rememberFeelFeedback()
        val dragged by state.interactionSource.collectIsDraggedAsState()
        LaunchedEffect(state, visible, manga) {
            if (!visible) return@LaunchedEffect
            var dragOrigin: Int? = null
            snapshotFlow { Triple(dragged, state.isScrollInProgress, state.settledPage) }
                .collect { (dragging, moving, page) ->
                    if (dragging && dragOrigin == null) dragOrigin = page
                    if (!dragging && !moving) {
                        if (dragOrigin != null && dragOrigin != page) feedback.perform(FeelEvent.Selection)
                        dragOrigin = null
                    }
                }
        }
        val fling = PagerDefaults.flingBehavior(state,
            snapAnimationSpec = if (motion) spring(dampingRatio = MangaMotion.GestureDamping,
                stiffness = MangaMotion.GestureStiffness) else snap())
        val position = remember(state, manga.size) {
            derivedStateOf { (state.currentPage + state.currentPageOffsetFraction)
                .coerceIn(0f, manga.lastIndex.toFloat()) }
        }
        val coverUrls = remember(manga) { manga.map { it.coverUrl } }
        val extracted = remember(coverUrls) { mutableStateMapOf<String, Color>() }
        val readyCovers = remember(coverUrls) { mutableStateMapOf<String, Boolean>() }
        val currentCoverReady = manga.getOrNull(state.currentPage)?.coverUrl?.let { readyCovers[it] } == true
        val fallback = MaterialTheme.colorScheme.surfaceVariant
        val colors = manga.map { entry ->
            key(entry.id, entry.coverUrl) {
                animateColorAsState(extracted[entry.coverUrl] ?: FeaturedCoverColors.get(entry.coverUrl) ?: fallback,
                    if (motion) tween(420, easing = MangaMotion.EnterEasing) else snap(), label = "featured-cover-color")
            }
        }
        val atmosphere = remember(position, colors) {
            derivedStateOf {
                val page = position.value
                val from = floor(page).toInt().coerceIn(colors.indices)
                val to = (from + 1).coerceAtMost(colors.lastIndex)
                lerp(colors[from].value, colors[to].value, page - from)
            }
        }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val imageWidthPx = with(density) { preferredWidth.roundToPx() }.coerceAtLeast(1)
        val imageHeightPx = with(density) { artworkHeight.roundToPx() }.coerceAtLeast(1)
        Column(Modifier.height(galleryHeight).padding(bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            HorizontalPager(state = state, pageSize = PageSize.Fixed(preferredWidth),
                // The adopted cover shadow draws outside its mask. Give its vertical
                // falloff room without changing the pager's equal horizontal insets.
                contentPadding = PaddingValues(start = edgePadding, end = edgePadding, top = 6.dp, bottom = 8.dp),
                pageSpacing = 10.dp,
                // Visible peeks are always composed; extra offscreen bytes wait for the current cover.
                beyondViewportPageCount = if (visible && currentCoverReady) 1 else 0,
                flingBehavior = fling, userScrollEnabled = visible,
                key = { manga[it].id },
                modifier = Modifier.fillMaxWidth().height(itemHeight + 14.dp)
                    .semantics { stateDescription = "推荐 ${state.settledPage.coerceIn(manga.indices) + 1} / ${manga.size}" }) { index ->
                val emphasis = { (1f - abs(index - position.value)).coerceIn(0f, 1f) }
                val settled = state.settledPage == index
                FeaturedManga(manga[index], repository, captionHeight, titleLines, inlineInformation, imageWidthPx, imageHeightPx,
                    color = colors[index], atmosphere = atmosphere,
                    onColor = { extracted[manga[index].coverUrl] = it },
                    onReady = { readyCovers[manga[index].coverUrl] = true },
                    modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                        val scale = if (motion) .92f + .08f * emphasis() else 1f
                        scaleX = scale; scaleY = scale
                        alpha = .72f + .28f * emphasis()
                    }.mangaCoverCard(22.dp, shadowRadius = 16.dp),
                    emphasis = emphasis, settled = settled, scrolling = state.isScrollInProgress,
                    offset = { (index - position.value).coerceIn(-1f, 1f) },
                    onPreview = { scope.launch {
                        if (motion) state.animateScrollToPage(index,
                            animationSpec = spring(MangaMotion.GestureDamping, MangaMotion.GestureStiffness))
                        else state.scrollToPage(index)
                    } }, enabled = visible, onOpen = onOpen,
                    onRatings = onRatings, showSource = showSource)
            }
            Box(Modifier.fillMaxWidth().height(4.dp), contentAlignment = Alignment.Center) {
                val ink = MaterialTheme.colorScheme.onSurface
                val strongContrast = LocalGlassAccessibilitySettings.current.increaseContrast
                if (manga.size > 1) Canvas(Modifier.size(width = (manga.size * 14 + 4).dp, height = 4.dp).clearAndSetSemantics { }) {
                    val slot = size.width / manga.size
                    val page = position.value
                    val visualPage = if (rtl) manga.lastIndex - page else page
                    repeat(manga.size) { index ->
                        val distance = abs(index - visualPage).coerceAtMost(1f)
                        drawCircle(ink.copy(alpha = (if (strongContrast) .48f else .22f) * distance),
                            radius = if (strongContrast) size.height / 2f else 1.4.dp.toPx(),
                            center = Offset((index + .5f) * slot, size.height / 2f))
                    }
                    val base = floor(visualPage)
                    val fraction = visualPage - base
                    val origin = (base + .5f) * slot
                    val half = minOf(7.dp.toPx(), slot / 2f)
                    val left = origin + slot * (if (motion) fraction * fraction else fraction) - half
                    val right = origin + slot * (if (motion) fraction * (2f - fraction) else fraction) + half
                    val activeInk = if (strongContrast) ink else lerp(ink, atmosphere.value, .24f)
                    drawRoundRect(activeInk, topLeft = Offset(left, 0f),
                        size = Size(right - left, size.height), cornerRadius = CornerRadius(size.height / 2f))
                }
            }
        }
    }
}

/**
 * Desktop combines the existing rounded artwork/material card with explicit groups.
 * Every visible book opens in one click; only the arrows and page controls change groups.
 */
@Composable
private fun DesktopFeaturedRecommendations(
    manga: List<Manga>,
    repository: MangaRepository,
    visible: Boolean,
    onOpen: (Manga) -> Unit,
    onRatings: ((Manga) -> Unit)?,
    loading: Boolean,
    showSource: Boolean,
    desktopActions: ((Manga) -> List<DesktopMenuAction>)?,
) {
    val window = LocalMangaWindow.current
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val coverUrls = remember(manga) { manga.map { it.coverUrl } }
    val extracted = remember(coverUrls) { mutableStateMapOf<String, Color>() }
    val fallback = MaterialTheme.colorScheme.surfaceVariant
    var firstIndex by rememberSaveable { mutableStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val perGroup = when {
            maxWidth >= 960.dp * fontScale -> 3
            maxWidth >= 600.dp * fontScale -> 2
            else -> 1
        }
        val pageCount = ((manga.size + perGroup - 1) / perGroup).coerceAtLeast(1)
        val page = (firstIndex / perGroup).coerceIn(0, pageCount - 1)
        val selectPage: (Int) -> Unit = { firstIndex = it.coerceIn(0, pageCount - 1) * perGroup }
        LaunchedEffect(manga.size) {
            if (manga.isNotEmpty()) firstIndex = firstIndex.coerceIn(0, manga.lastIndex)
        }
        val cardHeight = if (window.shortHeight) 152.dp
            else (window.height * .24f).coerceIn(176.dp, 224.dp)
        val gap = 12.dp
        val galleryHeight = cardHeight + 20.dp
        val cardWidth = ((maxWidth - 16.dp - gap * (perGroup - 1)) / perGroup).coerceAtLeast(1.dp)
        // Decode dimensions have upward buckets; visible geometry still follows every resize.
        fun decodePixels(size: Dp): Int = with(density) {
            ((size.roundToPx().coerceAtLeast(1).toLong() + 127L) / 128L * 128L)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val decodeWidth = decodePixels(cardWidth)
        val decodeHeight = decodePixels(cardHeight)
        val inlineInformation = cardWidth >= 224.dp * fontScale
        val titleLines = if (window.shortHeight || fontScale > 1.3f) 1 else 2
        val captionHeight = if (inlineInformation) 0.dp else featuredCaptionHeight(cardHeight, titleLines, fontScale)
        val inlinePages = maxWidth >= 480.dp
        Column(Modifier.fillMaxWidth().semantics { paneTitle = "精选推荐" }
            .onPreviewKeyEvent { event ->
                if (!visible || pageCount <= 1 || event.type != KeyEventType.KeyDown || event.isAltPressed ||
                    event.isCtrlPressed || event.isMetaPressed) false
                else when (event.key) {
                    Key.DirectionLeft -> { selectPage(page + if (rtl) 1 else -1); true }
                    Key.DirectionRight -> { selectPage(page + if (rtl) -1 else 1); true }
                    else -> false
                }
            }.focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("精选", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (pageCount > 1) {
                    MotionIconButton(onClick = { selectPage(page - 1) }, enabled = visible && page > 0,
                        modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一组推荐", Modifier.size(18.dp))
                    }
                    if (inlinePages) DesktopRecommendationPages(pageCount, page, visible, selectPage)
                    MotionIconButton(onClick = { selectPage(page + 1) }, enabled = visible && page < pageCount - 1,
                        modifier = Modifier.size(44.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, "下一组推荐", Modifier.size(18.dp))
                    }
                }
            }
            if (manga.isEmpty() && loading) Box(Modifier.fillMaxWidth().height(galleryHeight),
                contentAlignment = Alignment.Center) { QuietLoading("正在翻开书页…") }
            else MotionTabContent(selected = page to perGroup, modifier = Modifier.fillMaxWidth().height(galleryHeight),
                contentKey = { it }, order = { it.first }, fillContent = false) { group ->
                val entries = manga.drop(group.first * group.second).take(group.second)
                Row(Modifier.fillMaxWidth().height(galleryHeight).padding(horizontal = 8.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(group.second) { index ->
                        val entry = entries.getOrNull(index)
                        if (entry == null) Spacer(Modifier.weight(1f).fillMaxHeight())
                        else key(entry.id, entry.coverUrl) {
                            val color = animateColorAsState(
                                extracted[entry.coverUrl] ?: FeaturedCoverColors.get(entry.coverUrl) ?: fallback,
                                if (LocalMotionEnabled.current) tween(420, easing = MangaMotion.EnterEasing) else snap(),
                                label = "desktop-featured-color")
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                DesktopBrowseActions(desktopActions?.invoke(entry).orEmpty(), visible) {
                                    FeaturedManga(entry, repository, captionHeight, titleLines, inlineInformation,
                                        decodeWidth, decodeHeight,
                                        color = color, atmosphere = color,
                                        onColor = { extracted[entry.coverUrl] = it }, onReady = {},
                                        modifier = Modifier.fillMaxSize().mangaCoverCard(22.dp, shadowRadius = 16.dp),
                                        emphasis = { 1f }, settled = true, scrolling = false, offset = { 0f }, onPreview = {},
                                        enabled = visible, onOpen = onOpen, onRatings = onRatings, showSource = showSource)
                                }
                            }
                        }
                    }
                }
            }
            if (!inlinePages && pageCount > 1) Row(Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                DesktopRecommendationPages(pageCount, page, visible, selectPage)
            }
        }
    }
}

@Composable
private fun DesktopRecommendationPages(count: Int, page: Int, enabled: Boolean, onPage: (Int) -> Unit) {
    repeat(count) { index ->
        MotionIconButton(onClick = { onPage(index) }, enabled = enabled, modifier = Modifier.size(44.dp)
            .semantics {
                contentDescription = "第${index + 1}组推荐，共${count}组"
                selected = page == index
            }) {
            Box(Modifier.size(width = if (page == index) 16.dp else 4.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (page == index) .58f else .16f),
                    RoundedCornerShape(2.dp)))
        }
    }
}

private fun featuredCaptionHeight(cardHeight: Dp, titleLines: Int, fontScale: Float): Dp =
    (10f + (23f * titleLines + 22f) * fontScale).dp.coerceAtMost((cardHeight - 36.dp).coerceAtLeast(0.dp))

@Composable
private fun FeaturedManga(
    manga: Manga,
    repository: MangaRepository,
    captionHeight: Dp,
    titleLines: Int,
    inlineInformation: Boolean,
    imageWidthPx: Int,
    imageHeightPx: Int,
    color: State<Color>,
    atmosphere: State<Color>,
    onColor: (Color) -> Unit,
    onReady: () -> Unit,
    modifier: Modifier,
    emphasis: () -> Float,
    settled: Boolean,
    scrolling: Boolean,
    offset: () -> Float,
    onPreview: () -> Unit,
    enabled: Boolean,
    onOpen: (Manga) -> Unit,
    onRatings: ((Manga) -> Unit)?,
    showSource: Boolean,
) {
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val pageDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    val accessibility = LocalGlassAccessibilitySettings.current
    val solidCaption = accessibility.reduceTransparency || accessibility.increaseContrast
    var decoded by remember(manga.coverUrl) { mutableStateOf<Image?>(null) }
    val latestOnColor by rememberUpdatedState(onColor)
    val latestOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(manga.coverUrl, decoded) {
        decoded?.let { bitmap ->
            FeaturedCoverColors.get(manga.coverUrl)?.let { latestOnColor(it); return@LaunchedEffect }
            withContext(Dispatchers.Default) { FeaturedCoverColors.extract(manga.coverUrl, bitmap) }
                ?.let { latestOnColor(it) }
        }
    }
    val identity = mangaCoverIdentity(manga, repository)
    val request = remember(context, manga.id, manga.coverUrl, manga.sourceUrl, repository, identity, imageWidthPx, imageHeightPx, motion) {
        ImageRequest.Builder(imagePlatformContext(context)).data(identity.url)
            .sourceNetwork(dev.mangaflow.data.SourceKeys.decodeManga(manga.id).sourceId).softwareBitmap()
            .size(imageWidthPx, imageHeightPx)
            .memoryCacheKey("featured-cover:" + identity.cacheKey)
            .placeholderMemoryCacheKey("manga-cover:" + identity.cacheKey)
            .diskCacheKey(identity.cacheKey)
            .httpHeaders(NetworkHeaders.Builder().apply {
                identity.headers.forEach { (name, value) -> set(name, value) }
            }.build()).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    // The peek and fitted poster share one bounded Coil decode and the original request headers.
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit,
        onSuccess = { decoded = it.result.image; latestOnReady() })
    val currentEmphasis by rememberUpdatedState(emphasis)
    val travelingPoster = remember {
        object : ContentScale {
            override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
                if (srcSize.width <= 0f || srcSize.height <= 0f) return ScaleFactor(1f, 1f)
                val crop = ContentScale.Crop.computeScaleFactor(srcSize, dstSize).scaleX
                val fit = ContentScale.Fit.computeScaleFactor(srcSize, dstSize).scaleX
                val fraction = ((currentEmphasis() - .45f) / .55f).coerceIn(0f, 1f)
                val eased = fraction * fraction * (3f - 2f * fraction)
                val scale = crop + (fit - crop) * eased
                return ScaleFactor(scale, scale)
            }
        }
    }
    val theme = MaterialTheme.colorScheme
    val paper = lerp(theme.background, theme.surface, .65f)
    val ink = theme.onSurface
    val secondaryInk = if (accessibility.increaseContrast) ink else lerp(ink, paper, .23f)
    val lightPaper = paper.luminance() > .5f
    val information = {
        val t = ((emphasis() - .16f) / .56f).coerceIn(0f, 1f)
        t * t * (3f - 2f * t)
    }
    val acceptsTap = enabled && !scrolling
    Box(modifier.browseIntentWarmup(manga, repository, interaction, acceptsTap && settled)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = acceptsTap,
        onClickLabel = if (settled) "查看详情" else "选中这部推荐") {
            if (!settled) onPreview() else {
                transition?.select?.invoke(MangaCoverKey(manga.id, "discovery-featured"))
                onOpen(manga)
            }
        }.semantics(mergeDescendants = true) { contentDescription = manga.title }
        .motionPress(interaction, enabled = acceptsTap, pressedScale = .985f)) {
        BoxWithConstraints(Modifier.matchParentSize()) {
            val density = LocalDensity.current
            val fontScale = density.fontScale.coerceAtLeast(1f)
            val intrinsic = painter.intrinsicSize
            val aspect = if (intrinsic != Size.Unspecified && intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
                intrinsic.width > 0f && intrinsic.height > 0f)
                intrinsic.width / intrinsic.height else 2f / 3f
            val pictureHeight = (maxHeight - if (inlineInformation) 12.dp else captionHeight + 8.dp).coerceAtLeast(1.dp)
            val pictureRoom = if (inlineInformation)
                minOf(maxWidth * .53f, (maxWidth - 30.dp - 102.dp * fontScale).coerceAtLeast(1.dp))
                else (maxWidth - 12.dp).coerceAtLeast(1.dp)
            val posterWidth = minOf(pictureRoom, pictureHeight * aspect)
            val posterHeight = posterWidth / aspect
            val posterLeft = if (inlineInformation) 6.dp + (pictureRoom - posterWidth) / 2f
                else (maxWidth - posterWidth) / 2f
            val posterTop = 6.dp + (pictureHeight - posterHeight) / 2f
            // Keep the text column stationary while the real aspect becomes known.
            val infoLeft = if (inlineInformation) 6.dp + pictureRoom + 11.dp else 12.dp
            val infoTop = if (inlineInformation) 6.dp else maxHeight - captionHeight
            val infoWidth = (maxWidth - infoLeft - 12.dp).coerceAtLeast(1.dp)
            val infoHeight = if (inlineInformation) maxHeight - 12.dp else captionHeight
            Box(Modifier.matchParentSize().drawWithCache {
                val rtl = layoutDirection == LayoutDirection.Rtl
                val right = Offset(size.width, 0f)
                val start = if (inlineInformation && rtl) right else Offset.Zero
                val end = if (!inlineInformation) Offset(0f, size.height) else if (rtl) Offset.Zero else right
                val ground = Brush.linearGradient(listOf(paper.copy(alpha = .56f), paper.copy(alpha = .95f)), start, end)
                val pigment = lerp(color.value, atmosphere.value, .12f)
                val seam = (6.dp + pictureRoom).toPx().coerceIn(0f, size.width)
                val center = if (inlineInformation)
                    Offset(if (rtl) size.width - seam else seam, size.height * .54f)
                else Offset(size.width * .5f, infoTop.toPx())
                val radius = (size.width * if (inlineInformation) .52f else .72f).coerceAtLeast(1f)
                val reachY = size.height * if (inlineInformation) .94f else .34f
                val glow = Brush.radialGradient(0f to pigment.copy(alpha = if (lightPaper) .42f else .31f),
                    .48f to pigment.copy(alpha = if (lightPaper) .20f else .13f),
                    1f to pigment.copy(alpha = 0f), center = center, radius = radius)
                onDrawBehind {
                    if (solidCaption || size.width <= 0f || size.height <= 0f) drawRect(paper) else {
                        drawRect(ground)
                        scale(1f, reachY / radius, center) { drawCircle(glow, radius, center) }
                    }
                }
            })
            // A single artwork window grows from a cropped edge preview to the complete poster.
            // Only this child remeasures as the finger moves. The decode and the caption stay fixed.
            Box(Modifier.matchParentSize().layout { measurable, bounds ->
                val weight = emphasis()
                val fit = if (motion) weight * (2f - weight) else if (settled) 1f else 0f
                val width = (bounds.maxWidth + (posterWidth.toPx() - bounds.maxWidth) * fit)
                    .roundToInt().coerceIn(0, bounds.maxWidth)
                val height = (bounds.maxHeight + (posterHeight.toPx() - bounds.maxHeight) * fit)
                    .roundToInt().coerceIn(0, bounds.maxHeight)
                val frame = measurable.measure(Constraints.fixed(width, height))
                layout(bounds.maxWidth, bounds.maxHeight) {
                    val shift = if (motion) offset() * 4.dp.toPx() * fit else 0f
                    val x = (posterLeft.toPx() * fit + shift).roundToInt().coerceIn(0, bounds.maxWidth - width)
                    val y = (posterTop.toPx() * fit).roundToInt().coerceIn(0, bounds.maxHeight - height)
                    frame.placeRelative(x, y)
                }
            }.mangaSharedCover(manga.id, "discovery-featured").clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center) {
                if (decoded == null) Icon(Icons.AutoMirrored.Outlined.MenuBook, null,
                    tint = ink.copy(alpha = .22f), modifier = Modifier.size(28.dp))
                Image(painter, contentDescription = null, modifier = Modifier.matchParentSize(),
                    contentScale = if (motion) travelingPoster else if (settled) ContentScale.Fit else ContentScale.Crop)
            }
            val titleSize = when {
                !inlineInformation -> 18.sp
                infoWidth >= 148.dp * fontScale -> 25.sp
                infoWidth >= 108.dp * fontScale -> 22.sp
                else -> 19.sp
            }
            val lineHeight = if (inlineInformation) (titleSize.value * 1.25f).sp else 23.sp
            val captionInset = if (inlineInformation) 8.dp else 4.dp
            val author = remember(manga.authors) {
                manga.authors.filter(String::isNotBlank).distinct().joinToString("、")
            }
            val showAuthor = inlineInformation && !showSource && author.isNotEmpty() &&
                infoWidth >= 102.dp * fontScale && infoHeight >= 155.dp * fontScale
            val footerRoom = 22.dp * fontScale + 2.dp
            val authorRoom = if (showAuthor) 4.dp + 17.dp * fontScale else 0.dp
            val titleRoom = (infoHeight - captionInset * 2 - footerRoom - authorRoom).coerceAtLeast(0.dp)
            val lines = (titleRoom / with(density) { lineHeight.toDp() }).toInt()
                .coerceIn(1, if (inlineInformation) 3 else titleLines)
            Box(Modifier.matchParentSize().drawWithCache {
                // When the poster travels under the copy, feather the page under its ink.
                // The resting spread already has a clean text column.
                val edge = if (inlineInformation) infoLeft.toPx() else infoTop.toPx()
                val before = edge - 18.dp.toPx()
                val after = edge + 7.dp.toPx()
                fun point(distance: Float): Offset = when {
                    !inlineInformation -> Offset(0f, distance)
                    layoutDirection == LayoutDirection.Rtl -> Offset(size.width - distance, 0f)
                    else -> Offset(distance, 0f)
                }
                val protection = Brush.linearGradient(listOf(paper.copy(alpha = 0f), paper), point(before), point(after))
                onDrawBehind {
                    val overlap = ((1f - emphasis()) * 4f).coerceIn(0f, 1f)
                    val amount = overlap * information()
                    if (amount > 0f) drawRect(protection, alpha = amount)
                }
            }) {
                Column(Modifier.offset(infoLeft, infoTop).size(infoWidth, infoHeight).graphicsLayer {
                    alpha = information()
                    translationX = if (motion) pageDirection * offset() * 9.dp.toPx() else 0f
                    translationY = if (motion) (1f - information()) * 3.dp.toPx() else 0f
                }.padding(vertical = captionInset)) {
                    if (inlineInformation) Spacer(Modifier.weight(.38f))
                    Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), modifier = Modifier.clearAndSetSemantics { }, color = ink,
                        style = MaterialTheme.typography.titleMedium.copy(lineBreak = LineBreak.Heading),
                        fontSize = titleSize, lineHeight = lineHeight,
                        letterSpacing = (-.20f).sp, fontWeight = FontWeight.SemiBold,
                        maxLines = lines, overflow = TextOverflow.Ellipsis)
                    if (showAuthor) {
                        Spacer(Modifier.height(4.dp))
                        Text(author, color = secondaryInk, style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Normal, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (inlineInformation) Spacer(Modifier.weight(.62f)) else Spacer(Modifier.height(2.dp))
                    if (showSource) MangaBrowseSource(manga, foreground = secondaryInk)
                    else MangaMetricBadges(manga, Modifier.fillMaxWidth(), foreground = secondaryInk, compact = true, plain = true,
                        onExpand = if (acceptsTap && settled && onRatings != null) ({ onRatings(manga) }) else null)
                }
            }
        }
    }
}

/** Color values only: no bitmap copies, full-size readback, per-frame sampling or disk records. */
internal object FeaturedCoverColors {
    private val cache = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Color>>(emptyMap())
    fun get(url: String): Color? = cache.value[url]

    private class Swatch {
        var weight = 0f
            private set
        private var red = 0f
        private var green = 0f
        private var blue = 0f

        fun add(r: Float, g: Float, b: Float, amount: Float) {
            red += r * amount
            green += g * amount
            blue += b * amount
            weight += amount
        }

        fun color() = Color(red / weight, green / weight, blue / weight)
    }

    fun extract(url: String, bitmap: Image): Color? {
        get(url)?.let { return it }
        return runCatching {
            if (bitmap.width <= 0 || bitmap.height <= 0) return null
            val fullCover = Swatch()
            val hues = Array(12) { Swatch() }
            // Adjacent hue buckets share a sample so reds straddling the hue seam stay together.
            // A monochrome cover or a tiny coloured logo retains the overall paper tone.
            repeat(20) { row -> repeat(20) { column ->
                val x = ((column + .5f) * bitmap.width / 20f).toInt().coerceIn(0, bitmap.width - 1)
                val y = ((row + .5f) * bitmap.height / 20f).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = imagePixelAt(bitmap, x, y) ?: return null
                val r = ((pixel shr 16) and 255) / 255f
                val g = ((pixel shr 8) and 255) / 255f
                val b = (pixel and 255) / 255f
                val alpha = (pixel ushr 24) / 255f
                fullCover.add(r, g, b, alpha)
                val peak = maxOf(r, g, b)
                val chroma = peak - minOf(r, g, b)
                if (chroma >= .10f) {
                    val sector = when (peak) {
                        r -> (g - b) / chroma
                        g -> (b - r) / chroma + 2f
                        else -> (r - g) / chroma + 4f
                    }
                    val position = ((sector + 6f) % 6f) * 2f
                    val index = position.toInt()
                    val fraction = position - index
                    val weight = alpha * (.35f + .65f * chroma)
                    hues[index].add(r, g, b, weight * (1f - fraction))
                    hues[(index + 1) % hues.size].add(r, g, b, weight * fraction)
                }
            } }
            if (fullCover.weight <= 0f) return null
            val strongest = hues.maxBy { it.weight }
            val swatch = if (strongest.weight >= fullCover.weight * .065f) strongest else fullCover
            swatch.color().also { color ->
                cache.update { old ->
                    (old + (url to color)).let { if (it.size <= 96) it else it.entries.drop(it.size - 96).associate { e -> e.toPair() } }
                }
            }
        }.getOrNull()
    }
}
