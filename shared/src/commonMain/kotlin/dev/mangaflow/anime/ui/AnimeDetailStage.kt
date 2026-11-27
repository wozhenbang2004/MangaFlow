package dev.mangaflow.anime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.glass.LocalGlassAccessibilitySettings
import dev.mangaflow.anime.AnimeDiscoveryFormat
import dev.mangaflow.anime.AnimeSourceScore
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.anime.canonicalDoubanId
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.ui.glass.mangaHazeSource
import dev.mangaflow.ui.motion.MangaMotion
import dev.mangaflow.ui.motion.MotionTextButton
import dev.mangaflow.ui.platform.softwareBitmap

/** The transfer follows the actual button as it passes behind the pinned back bar. */
@Stable
internal class AnimeDetailDockTransfer {
    private var pageTop by mutableFloatStateOf(Float.NaN)
    private var homeTop by mutableFloatStateOf(Float.NaN)
    private var homeHeight by mutableIntStateOf(0)

    fun recordPage(coordinates: LayoutCoordinates) {
        pageTop = coordinates.positionInRoot().y
    }

    fun recordHome(coordinates: LayoutCoordinates) {
        homeTop = coordinates.positionInRoot().y
        homeHeight = coordinates.size.height
    }

    fun toolbarFraction(topInset: Int, collapsed: Boolean): Float {
        if (!homeTop.isFinite() || !pageTop.isFinite() || homeHeight <= 0) return if (collapsed) 1f else 0f
        val hidden = (pageTop + topInset - homeTop) / homeHeight.toFloat()
        return MangaMotion.EnterEasing.transform(((hidden - .08f) / .78f).coerceIn(0f, 1f))
    }
}

internal fun Modifier.animeDetailDockPresence(toolbar: Boolean, ownsAction: Boolean, motion: Boolean,
    fraction: () -> Float): Modifier = graphicsLayer {
    val presence = if (!motion) {
        if (ownsAction) 1f else 0f
    } else {
        val position = fraction()
        if (toolbar) position else 1f - position
    }
    alpha = presence
    translationY = if (toolbar && motion) (1f - presence) * 6.dp.toPx() else 0f
}.then(if (ownsAction) Modifier else Modifier.clearAndSetSemantics {})

internal fun animeDetailFacts(subject: AnimeSubject, metadata: AnimeSubject?): String = listOfNotNull(
    (subject.year ?: metadata?.year)?.toString(),
    when (subject.format ?: metadata?.format) {
        AnimeDiscoveryFormat.SERIES -> "剧集"
        AnimeDiscoveryFormat.MOVIE -> "剧场版"
        AnimeDiscoveryFormat.SPECIAL -> "特别篇"
        AnimeDiscoveryFormat.SHORT -> "短片"
        null -> null
    },
    (subject.episodeCount ?: metadata?.episodeCount)?.takeIf { it > 0 }?.let { "$it 集" },
).joinToString(" · ")

@Composable
internal fun AnimeDetailRatingLinks(subject: AnimeSubject, metadata: AnimeSubject?, scores: List<AnimeSourceScore>,
    active: Boolean, modifier: Modifier = Modifier) {
    val ratings = LocalAnimeRatings.current
    val open = { ratings.open(AnimeRatingRequest(subject.id, subject.title, subject, scores)) }
    if (scores.isNotEmpty()) AnimeRatingBadges(subject.id, scores, modifier,
        onExpand = open.takeIf { active && ratings.active })
    else if (metadata != null || subject.canonicalDoubanId() != null) MotionTextButton(
        onClick = open, enabled = active && ratings.active, contentPadding = PaddingValues(0.dp),
        modifier = modifier.heightIn(min = 44.dp)) {
        Icon(Icons.Outlined.StarOutline, null, Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text("各站评分", style = MaterialTheme.typography.labelMedium)
    }
}

/** The provider can supply a poster or a landscape image. Keep faces clear of the controls in either case. */
@Composable
internal fun AnimeDetailStage(subject: AnimeSubject, metadata: AnimeSubject?, scores: List<AnimeSourceScore>,
    backdrop: HazeState, topInset: Dp, compact: Boolean, active: Boolean, collapsed: Boolean,
    play: (@Composable (Modifier) -> Unit)?, follow: @Composable () -> Unit, onSummary: () -> Unit) {
    val artwork = metadata?.takeIf { subject.coverUrl.isBlank() && it.coverUrl.isNotBlank() } ?: subject
    val source = animePosterSourceId(artwork)
    val url = artwork.coverUrl
    val context = LocalMangaContext.current
    // The same decoded artwork serves the whole range of wide-window sizes.
    val request = remember(context, source, url) {
        if (url.isBlank()) null else ImageRequest.Builder(imagePlatformContext(context))
            .animeArtwork(url, source).softwareBitmap().size(1600, 1080).crossfade(false).build()
    }
    val painter = key(source, url) { rememberAsyncImagePainter(request, contentScale = ContentScale.Fit) }
    val imageState by painter.state.collectAsState()
    val image = (imageState as? AsyncImagePainter.State.Success)?.result?.image
    val landscape = image != null && image.width > 0 && image.height > 0 && image.width >= image.height * 1.16f
    val hasArt = url.isNotBlank() && imageState !is AsyncImagePainter.State.Error
    val hue = rememberAnimePosterTint(url, source, image)
    val accessibility = LocalGlassAccessibilitySettings.current
    val colors = MaterialTheme.colorScheme
    val opaque = accessibility.reduceTransparency || accessibility.increaseContrast
    val paper = if (opaque || hue == null) colors.surface else lerp(colors.surface, hue, .10f)
    val facts = remember(subject, metadata) { animeDetailFacts(subject, metadata) }
    val alternate = listOf(subject.originalTitle, metadata?.originalTitle.orEmpty(), metadata?.title.orEmpty())
        .firstOrNull { it.isNotBlank() && it != subject.title }
    val summary = subject.description.ifBlank { metadata?.description.orEmpty() }
    val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().clipToBounds().then(if (collapsed) Modifier.clearAndSetSemantics {} else Modifier)) {
        Box(Modifier.matchParentSize().mangaHazeSource(backdrop).background(paper)) {
            if (hasArt) {
                val artWidth = if (landscape && !opaque) .64f else .46f
                Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(artWidth).fillMaxHeight().clipToBounds()
                    .drawWithCache {
                        val rtl = layoutDirection == LayoutDirection.Rtl
                        val dissolve = if (opaque || !landscape) null else Brush.horizontalGradient(
                            0f to paper, .26f to paper.copy(alpha = .9f), .43f to paper.copy(alpha = .22f),
                            .53f to paper.copy(alpha = 0f),
                            startX = if (rtl) size.width else 0f, endX = if (rtl) 0f else size.width)
                        onDrawWithContent { drawContent(); if (dissolve != null) drawRect(dissolve) }
                    }) {
                    Image(painter, contentDescription = null, modifier = Modifier.fillMaxSize(),
                        alignment = if (landscape) BiasAlignment(0f, -.30f) else Alignment.Center,
                        contentScale = if (landscape) ContentScale.Crop else ContentScale.Fit)
                }
            }
            if (!opaque) Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .height(if (compact) 12.dp else 22.dp).drawWithCache {
                    val edge = Brush.verticalGradient(listOf(colors.surface.copy(alpha = 0f), colors.surface))
                    onDrawBehind { drawRect(edge) }
                })
        }
        Column(Modifier.fillMaxWidth(if (hasArt) .54f else .82f)
            .padding(start = if (compact) 22.dp else 30.dp, end = 14.dp,
                top = topInset + if (compact) 8.dp else 22.dp, bottom = if (compact) 13.dp else 23.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp)) {
            Text(subject.title, Modifier.semantics { heading() }, color = colors.onSurface,
                style = animeWorkTitleStyle(large = true).copy(fontSize = if (compact) 28.sp else 34.sp,
                    lineHeight = if (compact) 34.sp else 41.sp),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!compact && alternate != null) Text(alternate, color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (facts.isNotEmpty()) Text(facts, color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimeDetailRatingLinks(subject, metadata, scores, active && !collapsed, Modifier.weight(1f, fill = false))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (play != null) Box(Modifier.weight(1f, fill = false).widthIn(max = 278.dp * scale)) {
                    play(Modifier.fillMaxWidth())
                }
                follow()
            }
            if (!compact && summary.isNotBlank()) Text(summary,
                Modifier.clickable(enabled = active && !collapsed, role = Role.Button,
                    onClickLabel = "查看完整简介", onClick = onSummary).padding(vertical = 4.dp),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
