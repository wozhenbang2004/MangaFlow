package dev.mangaflow.anime.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import dev.mangaflow.anime.AnimeSubject
import dev.mangaflow.enhancement.ui.EnhancementPanelHost
import dev.mangaflow.experience.readerCacheOnly
import dev.mangaflow.platform.LocalMangaContext
import dev.mangaflow.platform.imagePlatformContext
import dev.mangaflow.ui.GlassSurfaceRole
import dev.mangaflow.ui.LiquidGlassSurface
import dev.mangaflow.ui.MangaDarkColorScheme
import dev.mangaflow.ui.glass.mangaHazeSource

/** Native movie surfaces are not Compose layers. Use the film's cached artwork for the glass. */
@Composable
internal fun AnimePlaybackPanel(
    visible: Boolean,
    overVideo: Boolean,
    subject: AnimeSubject?,
    backdrop: HazeState,
    onDismiss: () -> Unit,
    onClosed: () -> Unit,
    content: @Composable () -> Unit,
) {
    EnhancementPanelHost(visible, backdrop, onDismiss, onClosed, maximumWidth = 456.dp, playbackOverlay = true,
        surface = { modifier, body ->
            MaterialTheme(colorScheme = if (overVideo) MangaDarkColorScheme else MaterialTheme.colorScheme) {
                val material = rememberHazeState()
                val colors = MaterialTheme.colorScheme
                Box(modifier.clip(RoundedCornerShape(20.dp))) {
                    Box(Modifier.matchParentSize().mangaHazeSource(material)
                        .background(colors.surface.copy(alpha = if (overVideo) .62f else .76f))) {
                        if (subject != null && subject.coverUrl.isNotBlank()) {
                            val context = LocalMangaContext.current
                            val url = subject.coverUrl
                            val source = animePosterSourceId(subject)
                            val cover = remember(context, url, source) {
                                ImageRequest.Builder(imagePlatformContext(context)).animeArtwork(url, source)
                                    .size(160).diskCachePolicy(CachePolicy.READ_ONLY).readerCacheOnly().build()
                            }
                            AsyncImage(cover, contentDescription = null, modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop, alpha = .20f)
                        }
                    }
                    LiquidGlassSurface(material, Modifier.fillMaxWidth(), role = GlassSurfaceRole.PANEL,
                        cornerRadius = 20.dp, elevation = 0.dp) { body() }
                }
            }
        }, content = content)
}
