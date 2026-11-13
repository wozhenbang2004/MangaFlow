package dev.mangaflow.ui

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselItemInfo
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.BitmapImage
import coil3.compose.rememberAsyncImagePainter
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.data.Manga
import dev.mangaflow.data.MangaRepository
import dev.mangaflow.ui.motion.LocalMotionEnabled
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.motionPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor

/** Keep Material's keylines, masks and fling owner; every visual reads its live expansion. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun FeaturedCarousel(manga: List<Manga>, repository: MangaRepository, visible: Boolean, onOpen: (Manga) -> Unit) {
    if (manga.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val galleryWidth = maxWidth
        val density = LocalDensity.current
        val peekWidth = (galleryWidth * .14f).coerceIn(40.dp, 72.dp)
        val peekPixels = with(density) { peekWidth.toPx() }
        val motion = LocalMotionEnabled.current
        val state = rememberCarouselState { manga.size }
        val fling = CarouselDefaults.singleAdvanceFlingBehavior(state,
            snapAnimationSpec = if (motion) spring(dampingRatio = MangaMotion.GestureDamping,
                stiffness = MangaMotion.GestureStiffness) else snap())
        val identities = manga.map { it.id }
        val items = remember(identities) { mutableStateMapOf<Int, CarouselItemInfo>() }
        val focused by remember(items, manga.size) {
            derivedStateOf { items.entries.filter { it.key in manga.indices }.maxByOrNull { it.value.size }?.key ?: 0 }
        }
        val position = remember(items, manga.size, peekPixels) {
            derivedStateOf {
                var total = 0f
                var weighted = 0f
                items.forEach { (index, info) ->
                    if (index in manga.indices) {
                        val weight = featuredExpansion(info, peekPixels)
                        total += weight
                        weighted += index * weight
                    }
                }
                (if (total > 0f) weighted / total else focused.toFloat()).coerceIn(0f, manga.lastIndex.toFloat())
            }
        }
        val extracted = remember(manga.map { it.coverUrl }) { mutableStateMapOf<String, Color>() }
        val fallback = MaterialTheme.colorScheme.surfaceVariant
        val colors = manga.map { entry ->
            key(entry.id, entry.coverUrl) {
                animateColorAsState(extracted[entry.coverUrl] ?: FeaturedCoverColors.get(entry.coverUrl) ?: fallback,
                    if (motion) tween(420, easing = MangaMotion.EnterEasing) else snap(), label = "featured-cover-color")
            }
        }
        // Only a newly decoded palette is timed. Finger movement interpolates the cached colors
        // directly, so a reversing drag cannot leave the atmosphere or dots behind the artwork.
        val atmosphere = remember(position, colors) {
            derivedStateOf {
                val page = position.value
                val from = floor(page).toInt().coerceIn(colors.indices)
                val to = (from + 1).coerceAtMost(colors.lastIndex)
                lerp(colors[from].value, colors[to].value, page - from)
            }
        }
        var touched by remember { mutableStateOf(false) }
        var direction by remember { mutableIntStateOf(1) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val accessibility = LocalContext.current.getSystemService(AccessibilityManager::class.java)
        val speed = with(density) { 900.dp.toPx() }
        val autoAdvance = motion && visible && !touched && manga.size > 1 && accessibility?.isTouchExplorationEnabled != true
        LaunchedEffect(autoAdvance, state, fling, identities, lifecycle) {
            if (autoAdvance) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(6_000)
                    if (!state.isScrollInProgress) {
                        if (focused >= manga.lastIndex) direction = -1
                        if (focused <= 0) direction = 1
                        state.scroll { with(fling) { performFling(speed * direction) } }
                    }
                }
            }
        }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth()) {
                val fontScale = density.fontScale.coerceAtLeast(1f)
                // Text has reserved space below the fitted cover, so it never masks its title/art.
                val captionHeight = (24f + 58f * fontScale).dp
                val itemHeight = (galleryWidth * .76f).coerceIn(230.dp, 250.dp) + (captionHeight - 82.dp)
                val preferredWidth = if (manga.size == 1) galleryWidth else (galleryWidth - peekWidth - 8.dp).coerceAtLeast(180.dp)
                val imageWidthPx = with(density) { preferredWidth.roundToPx() }.coerceAtLeast(1)
                val imageHeightPx = with(density) { itemHeight.roundToPx() }.coerceAtLeast(1)
                HorizontalMultiBrowseCarousel(state = state,
                    preferredItemWidth = preferredWidth, itemSpacing = 8.dp,
                    minSmallItemWidth = peekWidth, maxSmallItemWidth = peekWidth,
                    contentPadding = PaddingValues(0.dp), flingBehavior = fling,
                    modifier = Modifier.fillMaxWidth().height(itemHeight)
                        .semantics { stateDescription = "推荐 ${focused + 1} / ${manga.size}" }
                        .pointerInput(manga.size) {
                            awaitEachGesture {
                                try {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    touched = true
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                    } while (event.changes.any { it.pressed })
                                } finally { touched = false }
                            }
                        },
                ) { index ->
                    val info = carouselItemInfo
                    DisposableEffect(items, index, info) {
                        items[index] = info
                        onDispose { if (items[index] === info) items.remove(index) }
                    }
                    key(manga[index].id, manga[index].coverUrl) {
                        FeaturedManga(manga[index], repository, captionHeight, imageWidthPx, imageHeightPx,
                            color = colors[index], atmosphere = atmosphere,
                            onColor = { extracted[manga[index].coverUrl] = it },
                            modifier = Modifier.fillMaxWidth().height(itemHeight).graphicsLayer {
                                val scale = if (motion) .96f + .04f * featuredExpansion(info, peekPixels) else 1f
                                scaleX = scale; scaleY = scale
                            }.mangaCoverCard(22.dp).maskClip(RoundedCornerShape(22.dp)),
                            emphasis = { featuredExpansion(info, peekPixels) },
                            offset = { if (motion) (index - position.value).coerceIn(-1f, 1f) * (if (rtl) -1f else 1f) else 0f },
                            onOpen = onOpen)
                    }
                }
            }
            if (manga.size > 1) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val color = MaterialTheme.colorScheme.onSurface
                Canvas(Modifier.size(width = (manga.size * 16).dp, height = 4.dp).clearAndSetSemantics { }) {
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

@OptIn(ExperimentalMaterial3Api::class)
private fun featuredExpansion(info: CarouselItemInfo, peekPixels: Float): Float =
    ((info.size - peekPixels) / (info.maxSize - peekPixels).coerceAtLeast(1f)).coerceIn(0f, 1f)

@Composable
private fun FeaturedManga(
    manga: Manga,
    repository: MangaRepository,
    captionHeight: Dp,
    imageWidthPx: Int,
    imageHeightPx: Int,
    color: State<Color>,
    atmosphere: State<Color>,
    onColor: (Color) -> Unit,
    modifier: Modifier,
    emphasis: () -> Float,
    offset: () -> Float,
    onOpen: (Manga) -> Unit,
) {
    val context = LocalContext.current
    val motion = LocalMotionEnabled.current
    val interaction = remember(manga.id) { MutableInteractionSource() }
    val transition = LocalMangaCoverTransition.current
    val heroBackdrop = rememberHazeState()
    var decoded by remember(manga.coverUrl) { mutableStateOf<Bitmap?>(null) }
    val latestOnColor by rememberUpdatedState(onColor)
    LaunchedEffect(manga.coverUrl, decoded) {
        decoded?.let { bitmap ->
            FeaturedCoverColors.get(manga.coverUrl)?.let { latestOnColor(it); return@LaunchedEffect }
            withContext(Dispatchers.Default) { FeaturedCoverColors.extract(manga.coverUrl, bitmap) }
                ?.let { latestOnColor(it) }
        }
    }
    val request = remember(context, manga.id, manga.coverUrl, repository, imageWidthPx, imageHeightPx, motion) {
        ImageRequest.Builder(context).data(manga.coverUrl).allowHardware(false)
            .size(imageWidthPx, imageHeightPx)
            .memoryCacheKey("featured-cover:" + manga.coverUrl)
            .placeholderMemoryCacheKey("manga-cover:" + manga.coverUrl)
            .httpHeaders(NetworkHeaders.Builder().apply {
                repository.imageHeaders(manga.id, "").forEach { (name, value) -> set(name, value) }
            }.build()).crossfade(if (motion) MangaMotion.Quick else 0).build()
    }
    // Both artwork layers share one bounded Coil decode and the original request headers.
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Fit,
        onSuccess = { decoded = (it.result.image as? BitmapImage)?.bitmap })
    val surface = if (color.value.luminance() > .28f) lerp(color.value, Color.White, .56f)
        else lerp(color.value, Color.Black, .22f)
    val onDark = surface.luminance() <= .5f
    val ink by animateColorAsState(if (onDark) Color.White else Color(0xFF222329),
        if (motion) tween(420, easing = MangaMotion.EnterEasing) else snap(), label = "featured-ink")
    val information = {
        val t = ((emphasis() - .55f) / .45f).coerceIn(0f, 1f)
        t * t * (3f - 2f * t)
    }
    Box(modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button,
        onClickLabel = "查看${manga.title}") {
            transition?.select?.invoke(MangaCoverKey(manga.id, "discovery-featured"))
            onOpen(manga)
        }.semantics(mergeDescendants = true) { contentDescription = manga.title }
        .motionPress(interaction, pressedScale = .985f)) {
        Box(Modifier.matchParentSize().hazeSource(heroBackdrop).drawBehind { drawRect(atmosphere.value) }) {
            Image(painter, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer {
                    scaleX = 1.16f; scaleY = 1.16f
                    translationX = offset() * 10.dp.toPx()
                    alpha = if (Build.VERSION.SDK_INT >= 31) .72f else .16f
                }.blur(28.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded))
            Box(Modifier.matchParentSize().drawBehind {
                drawRect(Brush.verticalGradient(0f to atmosphere.value.copy(alpha = .05f),
                    .55f to Color.Transparent, 1f to surface.copy(alpha = .62f)))
            })
            Box(Modifier.fillMaxSize().padding(top = 6.dp, bottom = captionHeight), contentAlignment = Alignment.Center) {
                if (decoded == null) Icon(Icons.Outlined.MenuBook, null,
                    tint = ink.copy(alpha = .22f), modifier = Modifier.size(28.dp))
                Image(painter, contentDescription = null, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().mangaSharedCover(manga.id, "discovery-featured")
                        .graphicsLayer {
                            translationX = offset() * 4.dp.toPx()
                            compositingStrategy = CompositingStrategy.Offscreen
                        }.drawWithContent {
                            drawContent()
                            val intrinsic = painter.intrinsicSize
                            if (intrinsic.width.isFinite() && intrinsic.height.isFinite() &&
                                intrinsic.width > 0f && intrinsic.height > 0f && size.minDimension > 0f) {
                                val fit = minOf(size.width / intrinsic.width, size.height / intrinsic.height)
                                val width = intrinsic.width * fit
                                val height = intrinsic.height * fit
                                val left = (size.width - width) * .5f
                                val top = (size.height - height) * .5f
                                val horizontal = (1.5.dp.toPx() / width).coerceAtMost(.05f)
                                val vertical = (1.5.dp.toPx() / height).coerceAtMost(.05f)
                                drawRect(Brush.horizontalGradient(0f to Color.Transparent,
                                    horizontal to Color.Black, (1f - horizontal) to Color.Black,
                                    1f to Color.Transparent, startX = left, endX = left + width),
                                    blendMode = BlendMode.DstIn)
                                drawRect(Brush.verticalGradient(0f to Color.Transparent,
                                    vertical to Color.Black, (1f - vertical) to Color.Black,
                                    1f to Color.Transparent, startY = top, endY = top + height),
                                    blendMode = BlendMode.DstIn)
                            }
                        })
            }
        }
        // Only the material fades at its upper edge. Labels stay sharp and unmasked.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(captionHeight)
            .graphicsLayer {
                alpha = information()
                translationY = if (motion) (1f - information()) * 6.dp.toPx() else 0f
            }) {
            LiquidGlassSurface(heroBackdrop, Modifier.matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Transparent, .3f to Color.Black,
                        1f to Color.Black), blendMode = BlendMode.DstIn)
                }, cornerRadius = 0.dp, role = GlassSurfaceRole.GALLERY,
                softCard = false, surfaceColor = surface, elevation = 0.dp) {}
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(top = 7.dp, bottom = 11.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom)) {
                Text(manga.title, modifier = Modifier.clearAndSetSemantics { }, color = ink, style = MaterialTheme.typography.titleSmall.copy(
                    shadow = Shadow(if (onDark) Color.Black.copy(alpha = .16f) else Color.White.copy(alpha = .18f),
                        Offset(0f, 1f), 3f)), fontSize = 16.sp, lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                MangaMetricBadges(manga, Modifier.fillMaxWidth(), foreground = ink, compact = true, plain = true)
            }
        }
    }
}

/** Color values only: no bitmap copies, full-size readback, per-frame sampling or disk records. */
internal object FeaturedCoverColors {
    private val cache = LruCache<String, Color>(96)
    fun get(url: String): Color? = cache.get(url)

    fun extract(url: String, bitmap: Bitmap): Color? {
        cache.get(url)?.let { return it }
        return runCatching {
            if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) return null
            var red = 0f; var green = 0f; var blue = 0f; var total = 0f
            // A small stratified grid favors the actual chromatic artwork over white margins.
            repeat(20) { row -> repeat(20) { column ->
                val x = ((column + .5f) * bitmap.width / 20f).toInt().coerceIn(0, bitmap.width - 1)
                val y = ((row + .5f) * bitmap.height / 20f).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                val r = ((pixel shr 16) and 255) / 255f
                val g = ((pixel shr 8) and 255) / 255f
                val b = (pixel and 255) / 255f
                val chroma = maxOf(r, g, b) - minOf(r, g, b)
                val weight = ((pixel ushr 24) / 255f) * (.12f + .88f * chroma)
                red += r * weight; green += g * weight; blue += b * weight; total += weight
            } }
            if (total <= 0f) null else Color(red / total, green / total, blue / total)
                .also { cache.put(url, it) }
        }.getOrNull()
    }
}
