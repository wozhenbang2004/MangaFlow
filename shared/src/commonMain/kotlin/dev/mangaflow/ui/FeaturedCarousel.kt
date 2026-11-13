package dev.mangaflow.ui

import dev.mangaflow.ui.platform.*

import coil3.Image
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.clickable
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
    onOpen: (Manga) -> Unit, onRatings: ((Manga) -> Unit)? = null, loading: Boolean = false) {
    if (manga.isEmpty() && !loading) return
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
        val itemHeight = galleryHeight - 38.dp
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
        val extracted = remember(manga.map { it.coverUrl }) { mutableStateMapOf<String, Color>() }
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
        Column(Modifier.height(galleryHeight).padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HorizontalPager(state = state, pageSize = PageSize.Fixed(preferredWidth),
                // The adopted cover shadow draws outside its mask. Give its vertical
                // falloff room without changing the pager's equal horizontal insets.
                contentPadding = PaddingValues(start = edgePadding, end = edgePadding, top = 10.dp, bottom = 14.dp),
                pageSpacing = 10.dp,
                beyondViewportPageCount = 1, flingBehavior = fling, userScrollEnabled = visible,
                key = { manga[it].id },
                modifier = Modifier.fillMaxWidth().height(itemHeight + 24.dp)
                    .semantics { stateDescription = "推荐 ${state.currentPage + 1} / ${manga.size}" }) { index ->
                val emphasis = { (1f - abs(index - position.value)).coerceIn(0f, 1f) }
                FeaturedManga(manga[index], repository, captionHeight, titleLines, inlineInformation, imageWidthPx, imageHeightPx,
                    color = colors[index], atmosphere = atmosphere,
                    onColor = { extracted[manga[index].coverUrl] = it },
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
            blurRadius(28.dp)
            backgroundColor(Color.Transparent)
            colorEffects(emptyList())
            noiseFactor(0f)
            blurredEdgeTreatment(BlurredEdgeTreatment.Unbounded)
        }
    }
    var decoded by remember(manga.coverUrl) { mutableStateOf<Image?>(null) }
    val latestOnColor by rememberUpdatedState(onColor)
    LaunchedEffect(manga.coverUrl, decoded) {
        decoded?.let { bitmap ->
            FeaturedCoverColors.get(manga.coverUrl)?.let { latestOnColor(it); return@LaunchedEffect }
            withContext(Dispatchers.Default) { FeaturedCoverColors.extract(manga.coverUrl, bitmap) }
                ?.let { latestOnColor(it) }
        }
    }
    val request = remember(context, manga.id, manga.coverUrl, repository, imageWidthPx, imageHeightPx, motion) {
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
        onSuccess = { decoded = it.result.image })
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
            Box(Modifier.matchParentSize().mangaHazeSource(heroBackdrop).drawBehind { drawRect(atmosphere.value) }) {
                Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().graphicsLayer {
                        scaleX = 1.16f; scaleY = 1.16f
                        translationX = offset() * 10.dp.toPx()
                        alpha = if (modernPlatformBlur) .22f else .12f
                    }.hazeBlur(input = HazeInput.Content, style = atmosphereBlur))
                // Neighbors keep recognizable original cover pixels inside the same mask.
                Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().graphicsLayer { alpha = 1f - emphasis() })
                if (decoded == null) Icon(Icons.AutoMirrored.Outlined.MenuBook, null,
                    tint = ink.copy(alpha = .22f), modifier = Modifier.offset(posterLeft, posterTop)
                        .size(posterWidth, posterHeight).padding(20.dp))
                // The portrait fills the card's height beside its own information,
                // without cropping it or exposing a wide empty Fit container.
                Image(painter, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.offset(posterLeft, posterTop).size(posterWidth, posterHeight)
                        .mangaSharedCover(manga.id, "discovery-featured")
                        .graphicsLayer {
                            translationX = offset() * 4.dp.toPx()
                            alpha = emphasis()
                        }.clip(RoundedCornerShape(16.dp)))
                Box(Modifier.offset(infoLeft, infoTop).size(infoWidth, infoHeight).drawBehind {
                    drawRect(if (inlineInformation)
                        Brush.horizontalGradient(0f to Color.Transparent,
                            .35f to surface.copy(alpha = .16f), 1f to surface.copy(alpha = .76f))
                    else Brush.verticalGradient(0f to Color.Transparent,
                        .34f to surface.copy(alpha = .16f), 1f to surface.copy(alpha = .76f)))
                })
            }
            // A single local information surface shares the artwork's cached pixels.
            // Wide cards put it beside the full portrait; narrow/large-type cards
            // keep the former readable footer within the same fixed gallery budget.
            Box(Modifier.offset(infoLeft, infoTop).size(infoWidth, infoHeight).graphicsLayer {
                alpha = information()
                translationY = if (motion) (1f - information()) * 4.dp.toPx() else 0f
            }) {
                LiquidGlassSurface(heroBackdrop, Modifier.matchParentSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(if (inlineInformation)
                            Brush.horizontalGradient(0f to Color.Transparent, .4f to Color.Black, 1f to Color.Black)
                        else Brush.verticalGradient(0f to Color.Transparent, .3f to Color.Black, 1f to Color.Black),
                            blendMode = BlendMode.DstIn)
                    }, cornerRadius = 0.dp, role = GlassSurfaceRole.GALLERY,
                    softCard = false, surfaceColor = surface, elevation = 0.dp) {}
                Column(Modifier.fillMaxSize().padding(horizontal = if (inlineInformation) 2.dp else 12.dp)
                    .padding(vertical = if (inlineInformation) 6.dp else 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (inlineInformation) 8.dp else 2.dp,
                        if (inlineInformation) Alignment.CenterVertically else Alignment.Bottom)) {
                    Text(manga.title, modifier = Modifier.clearAndSetSemantics { }, color = ink,
                        style = MaterialTheme.typography.titleSmall.copy(
                            shadow = Shadow(if (onDark) Color.Black.copy(alpha = .16f) else Color.White.copy(alpha = .18f),
                                Offset(0f, 1f), 3f)), fontSize = 14.sp, lineHeight = 17.sp,
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
