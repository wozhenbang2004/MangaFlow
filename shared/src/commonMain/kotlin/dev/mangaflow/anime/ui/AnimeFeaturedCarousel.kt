package dev.mangaflow.anime.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import coil3.Image
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.mangaflow.anime.AnimeDiscoveryItem
import dev.mangaflow.platform.*
import dev.mangaflow.ui.FeaturedCoverColors
import dev.mangaflow.ui.LocalMangaWindow
import dev.mangaflow.ui.mangaCoverCard
import dev.mangaflow.ui.mangaFontFamily
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionIconButton
import dev.mangaflow.ui.motion.motionPress
import dev.mangaflow.ui.platform.*
import dev.mangaflow.typography.MangaFontScene
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

internal data class AnimeFeaturedEntry(
    val key: String,
    val title: String,
    val coverUrl: String,
    val sourceId: String,
    val caption: String,
    val discoveryItem: AnimeDiscoveryItem,
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
        val galleryHeight = if (window.shortHeight)
            (window.height * .54f).coerceIn(166.dp, 238.dp)
        else (window.height * .35f).coerceIn(228.dp, 312.dp)
        val preferredWidth = minOf(galleryWidth, (galleryWidth * .89f).coerceAtMost(660.dp))
        val footerHeight = if (desktop) 44.dp else 18.dp
        val itemHeight = galleryHeight - footerHeight - 8.dp
        val compactLayout = preferredWidth < 250.dp || fontScale > 1.4f
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
        val imageHeightPx = with(density) { itemHeight.roundToPx() }.coerceAtLeast(1)
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
                AnimeFeaturedCard(entries[index], compactLayout, imageWidthPx, imageHeightPx, atmosphere = atmosphere,
                    onColor = { extracted[entries[index].coverUrl] = it },
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
    atmosphere: State<Color>,
    onColor: (Color) -> Unit,
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
    val atmosphereBlur = remember {
        HazeBlurStyle {
            blurRadius(16.dp)
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
        ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(entry.coverUrl, entry.sourceId)
            .softwareBitmap().size(imageWidthPx, imageHeightPx)
            .crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit,
        onSuccess = { decoded = it.result.image; latestOnReady() })
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val ink = Color.White
    val information: @Composable ColumnScope.() -> Unit = {
        Text(entry.title, Modifier.clearAndSetSemantics { }, color = ink,
            fontFamily = mangaFontFamily(MangaFontScene.WORK_TITLE),
            style = if (compactLayout) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (entry.caption.isNotBlank()) Text(entry.caption, Modifier.clearAndSetSemantics { },
            color = ink.copy(alpha = .78f), style = MaterialTheme.typography.labelSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (scores.isNotEmpty()) AnimeRatingBadges(entry.key, scores, Modifier.fillMaxWidth(), foreground = ink,
            onExpand = ({ onOpenScores(entry.copy(discoveryItem = item.copy(sourceScores = scores))) }).takeIf { scoresEnabled })
        if (!compactLayout) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { }) {
            Text("查看详情", color = ink, style = MaterialTheme.typography.labelMedium)
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(14.dp), tint = ink)
        }
    }
    BoxWithConstraints(modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button,
        enabled = enabled, onClickLabel = "查看详情", onClick = { onOpen(entry) })
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(entry.title, entry.caption).filter(String::isNotBlank).joinToString("，")
        }.motionPress(interaction, enabled = enabled, pressedScale = .985f)) {
        val fieldWidth = maxWidth
        val fieldHeight = maxHeight
        val posterHeight = if (compactLayout) (fieldHeight * .47f).coerceAtLeast(1.dp)
            else (fieldHeight - 24.dp).coerceAtLeast(1.dp)
        val night = Color(0xFF15161A)
        Box(Modifier.matchParentSize().drawBehind { drawRect(lerp(night, atmosphere.value, .25f)) }) {
            Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer {
                    scaleX = 1.12f; scaleY = 1.12f
                    translationX = offset() * 9.dp.toPx()
                    alpha = if (modernPlatformBlur) .40f else .23f
                }.hazeBlur(input = HazeInput.Content, style = atmosphereBlur))
            Box(Modifier.matchParentSize().drawBehind {
                val opaque = night.copy(alpha = .92f)
                val edge = night.copy(alpha = .14f)
                val falloff = if (compactLayout) Brush.verticalGradient(0f to edge, .39f to edge, .70f to opaque)
                else Brush.horizontalGradient(0f to opaque, .30f to opaque, .71f to edge,
                    startX = if (rtl) size.width else 0f, endX = if (rtl) 0f else size.width)
                drawRect(falloff)
            })
        }
        val posterWidth = minOf(if (compactLayout) fieldWidth - 24.dp else fieldWidth * .43f,
            posterHeight * .76f).coerceAtLeast(1.dp)
        Box(Modifier.align(if (compactLayout) Alignment.TopCenter else Alignment.CenterEnd)
            .padding(top = if (compactLayout) 8.dp else 0.dp, end = if (compactLayout) 0.dp else 12.dp)
            .size(posterWidth, posterHeight).clip(RoundedCornerShape(12.dp))
            .background(ink.copy(alpha = .07f)), contentAlignment = Alignment.Center) {
            if (decoded == null) Icon(Icons.Outlined.Tv, null, Modifier.size(25.dp), tint = ink.copy(alpha = .52f))
            Image(painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.align(if (compactLayout) Alignment.BottomStart else Alignment.CenterStart)
            .width(if (compactLayout) fieldWidth else fieldWidth * .56f)
            .padding(horizontal = if (compactLayout) 10.dp else 16.dp)
            .padding(bottom = if (compactLayout) 8.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactLayout) 3.dp else 6.dp),
            content = information)
    }
}
