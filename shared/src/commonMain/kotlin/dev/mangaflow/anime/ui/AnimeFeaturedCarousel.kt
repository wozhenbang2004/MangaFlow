package dev.mangaflow.anime.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.Image
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.anime.AnimeSourceTitle
import dev.mangaflow.experience.sourceNetwork
import dev.mangaflow.platform.*
import dev.mangaflow.ui.FeaturedCoverColors
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.mangaCoverCard
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

/** The native/discovery object remains the only authority for opening a gallery item. */
internal data class AnimeFeaturedEntry(
    val key: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val sourceLabel: String,
    val caption: String,
    val sourceTitle: AnimeSourceTitle? = null,
    val discoveryItem: AnimeDiscoveryItem? = null,
)

/**
 * Adapts ui/FeaturedCarousel.kt at 625a108: centered Foundation pager, real-aspect
 * poster, shared painter/color atmosphere and the adopted GALLERY glass seam.
 * This is a view of existing feed items, not a recommendation or playback claim.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnimeFeaturedCarousel(
    entries: List<AnimeFeaturedEntry>,
    active: Boolean,
    visible: Boolean,
    selectedKey: String?,
    onSelected: (String) -> Unit,
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
        val galleryHeight = if (window.shortHeight)
            (window.height * .5f).coerceIn(164.dp, 220.dp)
        else (window.height / 3f).coerceIn(220.dp, 280.dp)
        val preferredWidth = minOf(galleryWidth,
            (galleryWidth * .84f).coerceAtMost(if (desktop) 440.dp else 304.dp))
        val footerHeight = if (desktop) 44.dp else 12.dp
        val itemHeight = galleryHeight - footerHeight - 14.dp
        val inlineInformation = preferredWidth >= 224.dp && fontScale <= 1.3f
        val titleLines = if (window.shortHeight || fontScale > 1.3f) 1 else 2
        val captionHeight = if (inlineInformation) 0.dp else
            (8f + (20f * titleLines + 15f) * fontScale).dp.coerceAtMost(itemHeight - 36.dp)
        val artworkHeight = (itemHeight - captionHeight).coerceAtLeast(1.dp)
        val edgePadding = ((galleryWidth - preferredWidth) / 2f).coerceAtLeast(0.dp)
        val keys = entries.map { it.key }
        val state = rememberPagerState(
            initialPage = entries.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0),
        ) { entries.size }
        val latestOnSelected by rememberUpdatedState(onSelected)
        LaunchedEffect(state, keys) {
            val restored = keys.indexOf(selectedKey).takeIf { it >= 0 } ?: 0
            if (state.currentPage != restored) state.scrollToPage(restored)
            snapshotFlow { state.settledPage }.collect { page ->
                keys.getOrNull(page)?.let(latestOnSelected)
            }
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
        val coverUrls = entries.map { it.coverUrl }
        val extracted = remember(coverUrls) { mutableStateMapOf<String, Color>() }
        val readyCovers = remember(coverUrls) { mutableStateMapOf<String, Boolean>() }
        val currentCoverReady = entries.getOrNull(state.currentPage)?.coverUrl?.let { readyCovers[it] } == true
        val fallback = MaterialTheme.colorScheme.surfaceVariant
        val colors = entries.map { entry ->
            key(entry.key, entry.coverUrl) {
                animateColorAsState(extracted[entry.coverUrl] ?: FeaturedCoverColors.get(entry.coverUrl) ?: fallback,
                    if (motion) tween(420, easing = MangaMotion.EnterEasing) else snap(), label = "anime-featured-color")
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
                contentPadding = PaddingValues(start = edgePadding, end = edgePadding, top = 6.dp, bottom = 8.dp),
                pageSpacing = 10.dp,
                beyondViewportPageCount = if (visible && currentCoverReady) 1 else 0,
                flingBehavior = fling, userScrollEnabled = active,
                key = { entries[it].key },
                modifier = Modifier.fillMaxWidth().height(itemHeight + 14.dp)
                    .semantics { stateDescription = "番剧 " + (state.currentPage + 1) + " / " + entries.size }) { index ->
                val emphasis = { (1f - abs(index - position.value)).coerceIn(0f, 1f) }
                AnimeFeaturedCard(entries[index], captionHeight, titleLines, inlineInformation,
                    imageWidthPx, imageHeightPx, color = colors[index], atmosphere = atmosphere,
                    onColor = { extracted[entries[index].coverUrl] = it },
                    onReady = { readyCovers[entries[index].coverUrl] = true },
                    modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                        val scale = if (motion) .92f + .08f * emphasis() else 1f
                        scaleX = scale; scaleY = scale
                        alpha = .72f + .28f * emphasis()
                    }.mangaCoverCard(22.dp),
                    emphasis = emphasis,
                    offset = { if (motion) (index - position.value).coerceIn(-1f, 1f) *
                        (if (rtl) -1f else 1f) else 0f },
                    enabled = active, onOpen = onOpen)
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
    enabled: Boolean,
    onOpen: (AnimeFeaturedEntry) -> Unit,
) {
    val context = LocalMangaContext.current
    val motion = LocalMotionEnabled.current
    val interaction = remember(entry.key) { MutableInteractionSource() }
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
    var decoded by remember(entry.coverUrl) { mutableStateOf<Image?>(null) }
    val latestOnColor by rememberUpdatedState(onColor)
    val latestOnReady by rememberUpdatedState(onReady)
    LaunchedEffect(entry.coverUrl, decoded) {
        decoded?.let { bitmap ->
            FeaturedCoverColors.get(entry.coverUrl)?.let { latestOnColor(it); return@LaunchedEffect }
            withContext(Dispatchers.Default) { FeaturedCoverColors.extract(entry.coverUrl, bitmap) }
                ?.let { latestOnColor(it) }
        }
    }
    val request = remember(context, entry.coverUrl, entry.sourceId, imageWidthPx, imageHeightPx, motion) {
        ImageRequest.Builder(imagePlatformContext(context)).data(entry.coverUrl)
            .sourceNetwork(entry.sourceId).softwareBitmap().size(imageWidthPx, imageHeightPx)
            .crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    // The sharp poster and blurred field share the same bounded decode.
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit,
        onSuccess = { decoded = it.result.image; latestOnReady() })
    val surface = if (color.value.luminance() > .28f) lerp(color.value, Color.White, .56f)
        else lerp(color.value, Color.Black, .22f)
    val onDark = surface.luminance() <= .5f
    val ink by animateColorAsState(if (onDark) Color.White else Color(0xFF222329),
        if (motion) tween(420, easing = MangaMotion.EnterEasing) else snap(), label = "anime-featured-ink")
    val information = {
        val t = ((emphasis() - .55f) / .45f).coerceIn(0f, 1f)
        t * t * (3f - 2f * t)
    }
    Box(modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button,
        enabled = enabled, onClickLabel = "查看" + entry.title, onClick = { onOpen(entry) })
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(entry.title, entry.sourceLabel, entry.caption)
                .filter(String::isNotBlank).joinToString("，")
        }.motionPress(interaction, enabled = enabled, pressedScale = .985f)) {
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
            val infoLeft = if (inlineInformation) 6.dp + pictureRoom + 12.dp else 0.dp
            val infoTop = if (inlineInformation) 6.dp else maxHeight - captionHeight
            val infoWidth = (maxWidth - infoLeft - if (inlineInformation) 8.dp else 0.dp).coerceAtLeast(1.dp)
            val infoHeight = if (inlineInformation) maxHeight - 12.dp else captionHeight
            val mergeStart = ((if (inlineInformation) infoLeft - 24.dp else infoTop - 4.dp) /
                (if (inlineInformation) maxWidth else maxHeight)).coerceIn(0f, .94f)
            val mergeEnd = ((if (inlineInformation) infoLeft + 8.dp else infoTop + 12.dp) /
                (if (inlineInformation) maxWidth else maxHeight)).coerceIn(mergeStart + .01f, 1f)
            val shape = remember { RoundedCornerShape(22.dp) }
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
                    mergeEnd to Color.Black, 1f to Color.Black), blendMode = BlendMode.DstIn)
            }, cornerRadius = 22.dp, role = GlassSurfaceRole.GALLERY,
                softCard = false, surfaceColor = surface, elevation = 0.dp) {}
            if (decoded == null) Icon(Icons.Outlined.Tv, null,
                tint = ink.copy(alpha = .22f), modifier = Modifier.offset(posterLeft, posterTop)
                    .size(posterWidth, posterHeight).padding(20.dp))
            Image(painter, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.offset(posterLeft, posterTop).size(posterWidth, posterHeight)
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
                    if (inlineInformation) Text(entry.sourceLabel, Modifier.clearAndSetSemantics { },
                        color = ink.copy(alpha = .68f),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.title, Modifier.clearAndSetSemantics { }, color = ink,
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        fontWeight = FontWeight.Medium, minLines = if (inlineInformation) 1 else titleLines,
                        maxLines = if (inlineInformation) titleLines + 1 else titleLines, overflow = TextOverflow.Ellipsis)
                    val caption = if (inlineInformation) entry.caption
                        else listOf(entry.sourceLabel, entry.caption).filter(String::isNotBlank).joinToString(" · ")
                    if (caption.isNotBlank()) Text(caption, Modifier.clearAndSetSemantics { },
                        color = ink.copy(alpha = .76f),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
