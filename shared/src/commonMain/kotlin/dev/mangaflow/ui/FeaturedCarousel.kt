package dev.mangaflow.ui

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

/** Foundation Pager's equal edge padding keeps first, middle and last recommendations centered. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FeaturedCarousel(manga: List<Manga>, repository: MangaRepository, visible: Boolean,
    onOpen: (Manga) -> Unit, onRatings: ((Manga) -> Unit)? = null, loading: Boolean = false,
    desktopActions: ((Manga) -> List<DesktopMenuAction>)? = null) {
    if (manga.isEmpty() && !loading) return
    if (platformKind == PlatformKind.DESKTOP) {
        DesktopFeaturedRecommendations(manga, repository, visible, onOpen, onRatings, loading, desktopActions)
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
        val galleryHeight = if (window.shortHeight)
            (window.height * .5f).coerceIn(164.dp, 220.dp)
            else (window.height / 3f).coerceIn(220.dp, 280.dp)
        val preferredWidth = minOf(galleryWidth, (galleryWidth * .82f).coerceAtMost(304.dp))
        val inlineInformation = preferredWidth >= 224.dp && fontScale <= 1.3f
        val titleLines = if (window.shortHeight || fontScale > 1.3f) 1 else 2
        val captionHeight = if (inlineInformation) 0.dp else
            (10f + (17f * titleLines + 22f) * fontScale).dp.coerceAtMost(galleryHeight - 96.dp)
        val itemHeight = galleryHeight - 22.dp
        val artworkHeight = (itemHeight - captionHeight).coerceAtLeast(1.dp)
        val edgePadding = ((galleryWidth - preferredWidth) / 2f).coerceAtLeast(0.dp)
        if (manga.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(galleryHeight), contentAlignment = Alignment.Center) {
                QuietLoading("正在翻开书页…")
            }
            return@BoxWithConstraints
        }
        val state = rememberPagerState { manga.size }
        val scope = rememberCoroutineScope()
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
                    .semantics { stateDescription = "推荐 ${state.currentPage + 1} / ${manga.size}" }) { index ->
                val emphasis = { (1f - abs(index - position.value)).coerceIn(0f, 1f) }
                FeaturedManga(manga[index], repository, captionHeight, titleLines, inlineInformation, imageWidthPx, imageHeightPx,
                    color = colors[index], atmosphere = atmosphere,
                    onColor = { extracted[manga[index].coverUrl] = it },
                    onReady = { readyCovers[manga[index].coverUrl] = true },
                    modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                        val scale = if (motion) .92f + .08f * emphasis() else 1f
                        scaleX = scale; scaleY = scale
                        alpha = .72f + .28f * emphasis()
                    }.mangaCoverCard(22.dp),
                    emphasis = emphasis,
                    offset = { if (motion) (index - position.value).coerceIn(-1f, 1f) *
                        (if (rtl) -1f else 1f) else 0f },
                    onPreview = { scope.launch {
                        if (motion) state.animateScrollToPage(index,
                            animationSpec = spring(MangaMotion.GestureDamping, MangaMotion.GestureStiffness))
                        else state.scrollToPage(index)
                    } }, enabled = visible, onOpen = onOpen, onRatings = onRatings)
            }
            Box(Modifier.fillMaxWidth().height(4.dp), contentAlignment = Alignment.Center) {
                val color = MaterialTheme.colorScheme.onSurface
                if (manga.size > 1) Canvas(Modifier.size(width = (manga.size * 16).dp, height = 4.dp).clearAndSetSemantics { }) {
                    repeat(manga.size) { index ->
                        val visualIndex = if (rtl) manga.lastIndex - index else index
                        val weight = (1f - abs(position.value - index)).coerceIn(0f, 1f)
                        val width = (4f + 10f * weight).dp.toPx()
                        drawRoundRect(color.copy(alpha = .13f + .45f * weight),
                            topLeft = Offset((visualIndex * 16 + 8).dp.toPx() - width / 2f, 0f),
                            size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2f))
                    }
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
        val inlineInformation = cardWidth >= 224.dp && fontScale <= 1.3f
        val titleLines = if (window.shortHeight || fontScale > 1.3f) 1 else 2
        val captionHeight = if (inlineInformation) 0.dp else
            (10f + (17f * titleLines + 22f) * fontScale).dp.coerceAtMost(cardHeight - 96.dp)
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
                                        with(density) { cardWidth.roundToPx() }.coerceAtLeast(1),
                                        with(density) { cardHeight.roundToPx() }.coerceAtLeast(1),
                                        color = color, atmosphere = color,
                                        onColor = { extracted[entry.coverUrl] = it }, onReady = {},
                                        modifier = Modifier.fillMaxSize().mangaCoverCard(22.dp),
                                        emphasis = { 1f }, offset = { 0f }, onPreview = {},
                                        enabled = visible, onOpen = onOpen, onRatings = onRatings)
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
    offset: () -> Float,
    onPreview: () -> Unit,
    enabled: Boolean,
    onOpen: (Manga) -> Unit,
    onRatings: ((Manga) -> Unit)?,
) {
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    val heroBackdrop = rememberHazeState()
    val atmosphereBlur = remember {
        HazeBlurStyle {
            blurRadius(18.dp)
            backgroundColor(Color.Transparent)
            colorEffects(emptyList())
            noiseFactor(0f)
            blurredEdgeTreatment(BlurredEdgeTreatment.Unbounded)
        }
    }
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
    val request = remember(context, manga.id, manga.coverUrl, manga.sourceUrl, repository, imageWidthPx, imageHeightPx, motion) {
        ImageRequest.Builder(imagePlatformContext(context)).data(manga.coverUrl).softwareBitmap()
            .size(imageWidthPx, imageHeightPx)
            .memoryCacheKey("featured-cover:" + manga.coverUrl)
            .placeholderMemoryCacheKey("manga-cover:" + manga.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().apply {
                repository.imageHeaders(manga.id, "").forEach { (name, value) -> set(name, value) }
            }.build()).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    // Both artwork layers share one bounded Coil decode and the original request headers.
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit,
        onSuccess = { decoded = it.result.image; latestOnReady() })
    val surface = if (color.value.luminance() > .28f) lerp(color.value, Color.White, .56f)
        else lerp(color.value, Color.Black, .22f)
    val onDark = surface.luminance() <= .5f
    val ink by animateColorAsState(if (onDark) Color.White else Color(0xFF222329),
        if (motion) tween(420, easing = MangaMotion.EnterEasing) else snap(), label = "featured-ink")
    val information = {
        val t = ((emphasis() - .55f) / .45f).coerceIn(0f, 1f)
        t * t * (3f - 2f * t)
    }
    Box(modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, enabled = enabled,
        onClickLabel = "查看${manga.title}") {
            if (emphasis() < .98f) onPreview() else {
                transition?.select?.invoke(MangaCoverKey(manga.id, "discovery-featured"))
                onOpen(manga)
            }
        }.semantics(mergeDescendants = true) { contentDescription = manga.title }
        .motionPress(interaction, pressedScale = .985f)) {
        BoxWithConstraints(Modifier.matchParentSize()) {
            val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val intrinsic = painter.intrinsicSize
            val aspect = if (intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
                intrinsic.width > 0f && intrinsic.height > 0f)
                intrinsic.width / intrinsic.height else 2f / 3f
            val pictureHeight = (maxHeight - if (inlineInformation) 12.dp else captionHeight + 10.dp)
                .coerceAtLeast(1.dp)
            val pictureRoom = if (inlineInformation)
                minOf(maxWidth * .52f, (maxWidth - 24.dp - 88.dp * fontScale).coerceAtLeast(1.dp))
                else (maxWidth - 12.dp).coerceAtLeast(1.dp)
            val posterWidth = minOf(pictureRoom, pictureHeight * aspect)
            val posterHeight = posterWidth / aspect
            val posterLeft = if (inlineInformation) 6.dp + (pictureRoom - posterWidth) / 2f
                else (maxWidth - posterWidth) / 2f
            val posterTop = 6.dp + (pictureHeight - posterHeight) / 2f
            // Keep the text column stationary while the real aspect becomes known.
            val infoLeft = if (inlineInformation) 6.dp + pictureRoom + 12.dp else 0.dp
            val infoTop = if (inlineInformation) 6.dp else maxHeight - captionHeight
            val infoWidth = (maxWidth - infoLeft - if (inlineInformation) 8.dp else 0.dp).coerceAtLeast(1.dp)
            val infoHeight = if (inlineInformation) maxHeight - 12.dp else captionHeight
            val mergeStart = ((if (inlineInformation) infoLeft - 24.dp else infoTop - 4.dp) /
                (if (inlineInformation) maxWidth else maxHeight)).coerceIn(0f, .94f)
            val mergeEnd = ((if (inlineInformation) infoLeft + 8.dp else infoTop + 12.dp) /
                (if (inlineInformation) maxWidth else maxHeight)).coerceIn(mergeStart + .01f, 1f)
            val shape = remember { RoundedCornerShape(22.dp) }
            // One rounded artwork field, including the entire trailing edge. The
            // information material feathers across this field, never an inset rectangle.
            Box(Modifier.matchParentSize().clip(shape).mangaHazeSource(heroBackdrop)
                .drawBehind { drawRect(lerp(surface, atmosphere.value, .12f)) }) {
                Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().graphicsLayer {
                        scaleX = 1.16f; scaleY = 1.16f
                        translationX = offset() * 10.dp.toPx()
                        alpha = if (modernPlatformBlur) .48f else .18f
                    }.hazeBlur(input = HazeInput.Content, style = atmosphereBlur))
                Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = 1f - emphasis() })
                Box(Modifier.matchParentSize().drawBehind {
                    val startColor = surface.copy(alpha = .02f)
                    val endColor = surface.copy(alpha = .70f * emphasis())
                    drawRect(if (inlineInformation)
                        Brush.horizontalGradient(0f to startColor, mergeStart to startColor,
                            mergeEnd to surface.copy(alpha = .38f * emphasis()), 1f to endColor)
                    else Brush.verticalGradient(0f to startColor, mergeStart to startColor,
                        mergeEnd to surface.copy(alpha = .38f * emphasis()), 1f to endColor))
                })
            }
            LiquidGlassSurface(heroBackdrop, Modifier.matchParentSize().graphicsLayer {
                alpha = information()
                compositingStrategy = CompositingStrategy.Offscreen
            }.drawWithContent {
                drawContent()
                drawRect(if (inlineInformation)
                    Brush.horizontalGradient(0f to Color.Transparent, mergeStart to Color.Transparent,
                        mergeEnd to Color.Black, 1f to Color.Black)
                else Brush.verticalGradient(0f to Color.Transparent, mergeStart to Color.Transparent,
                    mergeEnd to Color.Black, 1f to Color.Black),
                    blendMode = BlendMode.DstIn)
            }, cornerRadius = 22.dp, role = GlassSurfaceRole.GALLERY,
                softCard = false, surfaceColor = surface, elevation = 0.dp) {}
            // Keep the full sharp poster above the feathered material. It shares
            // the original painter/decode and clips at its real fitted image bounds.
            if (decoded == null) Icon(Icons.AutoMirrored.Outlined.MenuBook, null,
                tint = ink.copy(alpha = .22f), modifier = Modifier.offset(posterLeft, posterTop)
                    .size(posterWidth, posterHeight).padding(20.dp))
            Image(painter, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.offset(posterLeft, posterTop).size(posterWidth, posterHeight)
                    .mangaSharedCover(manga.id, "discovery-featured")
                    .graphicsLayer {
                        translationX = offset() * 4.dp.toPx()
                        alpha = emphasis()
                    }.clip(RoundedCornerShape(16.dp)))
            Box(Modifier.offset(infoLeft, infoTop).size(infoWidth, infoHeight).graphicsLayer {
                alpha = information()
                translationY = if (motion) (1f - information()) * 4.dp.toPx() else 0f
            }) {
                Column(Modifier.fillMaxSize().padding(horizontal = if (inlineInformation) 2.dp else 12.dp)
                    .padding(vertical = if (inlineInformation) 6.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (inlineInformation) 8.dp else 2.dp,
                        if (inlineInformation) Alignment.CenterVertically else Alignment.Bottom)) {
                    Text(manga.title, fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE), modifier = Modifier.clearAndSetSemantics { }, color = ink,
                        style = MaterialTheme.typography.titleSmall.copy(
                            shadow = Shadow(if (onDark) Color.Black.copy(alpha = .16f) else Color.White.copy(alpha = .18f),
                                Offset(0f, 1f), 3f)), fontSize = 14.sp, lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium, minLines = if (inlineInformation) 1 else titleLines,
                        maxLines = if (inlineInformation) 3 else titleLines, overflow = TextOverflow.Ellipsis)
                    MangaMetricBadges(manga, Modifier.fillMaxWidth(), foreground = ink, compact = true, plain = true,
                        onExpand = if (enabled && information() >= .98f && onRatings != null) ({ onRatings(manga) }) else null)
                }
            }
        }
    }
}

/** Color values only: no bitmap copies, full-size readback, per-frame sampling or disk records. */
internal object FeaturedCoverColors {
    private val cache = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Color>>(emptyMap())
    fun get(url: String): Color? = cache.value[url]

    fun extract(url: String, bitmap: Image): Color? {
        get(url)?.let { return it }
        return runCatching {
            if (bitmap.width <= 0 || bitmap.height <= 0) return null
            var red = 0f; var green = 0f; var blue = 0f; var total = 0f
            // A small stratified grid favors the actual chromatic artwork over white margins.
            repeat(20) { row -> repeat(20) { column ->
                val x = ((column + .5f) * bitmap.width / 20f).toInt().coerceIn(0, bitmap.width - 1)
                val y = ((row + .5f) * bitmap.height / 20f).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = imagePixelAt(bitmap, x, y) ?: return null
                val r = ((pixel shr 16) and 255) / 255f
                val g = ((pixel shr 8) and 255) / 255f
                val b = (pixel and 255) / 255f
                val chroma = maxOf(r, g, b) - minOf(r, g, b)
                val weight = ((pixel ushr 24) / 255f) * (.12f + .88f * chroma)
                red += r * weight; green += g * weight; blue += b * weight; total += weight
            } }
            if (total <= 0f) null else Color(red / total, green / total, blue / total)
                .also { color -> cache.update { old ->
                    (old + (url to color)).let { if (it.size <= 96) it else it.entries.drop(it.size - 96).associate { e -> e.toPair() } }
                } }
        }.getOrNull()
    }
}
