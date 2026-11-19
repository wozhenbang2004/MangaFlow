package dev.mangaflow.anime.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.anime.sources.ANILIST_PROVIDER
import dev.mangaflow.anime.sources.BANGUMI_PROVIDER
import dev.mangaflow.ui.ReviewSourceMark
import dev.mangaflow.ui.TextSourceWordmark
import dev.mangaflow.ui.reviewSourceMarkWidth
import dev.mangaflow.ui.motion.motionPress

/** App-owned source identities shared between the work, its sources and playback. */
@Composable
internal fun AnimeSourceMark(sourceId: String, sourceName: String, modifier: Modifier = Modifier,
    size: Dp = 18.dp, ink: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val reviewId = animeReviewSourceId(sourceId)
    if (reviewId != null) {
        ReviewSourceMark(reviewId, modifier, size = size, onDark = ink.luminance() > .5f)
        return
    }
    val glyph = when (sourceId) {
        "anime-native-jinpai", "anime-lanerc-jinpai" -> GoldMark
        "anime-lanerc-gugu" -> YuzuMark
        "anime-lanerc-nmeng" -> LemonMark
        "anime-native-dmbus", "anime-lanerc-dmbus" -> Icons.Outlined.DirectionsBus
        "anime-lanerc-sd" -> Icons.Outlined.Bolt
        else -> null
    }
    if (glyph != null) {
        Icon(glyph, null, modifier.size(size), tint = ink)
    } else {
        val text = animeSourceWordmark(sourceId, sourceName)
        TextSourceWordmark(text, modifier.size(animeSourceWordmarkWidth(text, size), size)
            .clearAndSetSemantics { }, ink)
    }
}

/** One work-local disclosure target. A compact strip keeps its real glyphs on narrow covers. */
@Composable
internal fun AnimeSourceBadge(sources: List<Pair<String, String>>, modifier: Modifier = Modifier,
    selectedId: String? = null, ink: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    compact: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val unique = remember(sources, selectedId) {
        sources.distinctBy { it.first }.sortedByDescending { it.first == selectedId }
    }
    if (unique.isEmpty()) return
    val interaction = remember { MutableInteractionSource() }
    val action = if (selectedId == null) "查看来源" else "切换来源"
    val names = unique.joinToString("、") { it.second }
    val selected = unique.firstOrNull { it.first == selectedId }?.second
    val fontScale = LocalDensity.current.fontScale
    val markSize = if (compact) 16.dp else 18.dp
    val gap = 4.dp
    BoxWithConstraints(modifier.defaultMinSize(minWidth = if (compact) 30.dp else 44.dp,
        minHeight = if (compact) 28.dp else 44.dp)
        .clip(RoundedCornerShape(8.dp))
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = action, onClick = onClick)
        .motionPress(interaction, enabled = enabled, pressedScale = .96f)
        .semantics(mergeDescendants = true) {
            contentDescription = if (selected == null) "来源：$names" else "当前来源：$selected，来源：$names"
        }.padding(end = gap), contentAlignment = Alignment.CenterStart) {
        // The arrow opens the whole list if the cover can display only a few marks.
        val markRoom = maxWidth - 12.dp - gap
        var used = 0.dp
        val visible = unique.take(3).takeWhile { (id, name) ->
            val width = if (animeReviewSourceId(id) != null) reviewSourceMarkWidth(markSize, fontScale)
                else animeSourceWordmarkWidth(animeSourceWordmark(id, name), markSize)
            val fits = used == 0.dp || used + gap + width <= markRoom
            if (fits) used += if (used == 0.dp) width else gap + width
            fits
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
            visible.forEach { (id, name) -> AnimeSourceMark(id, name, size = markSize, ink = ink) }
            if (used + gap + 12.dp <= maxWidth) {
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(12.dp), tint = ink.copy(alpha = .70f))
            }
        }
    }
}

private fun animeReviewSourceId(id: String): String? = when (id) {
    BANGUMI_PROVIDER, "bangumi" -> "bangumi"
    ANILIST_PROVIDER, "anilist" -> "anilist"
    else -> null
}

private fun animeSourceWordmark(id: String, name: String): String = when (id) {
    "anime-age" -> "AGE"
    "anime-native-yzx", "anime-lanerc-yzx" -> "yzx"
    "anime-native-akianime", "anime-lanerc-akianime" -> "AKI"
    "anime-native-ani", "anime-lanerc-ani" -> "Ani"
    "anime-lmm", "anime-lanerc-lmm85" -> "LMM"
    "anime-tvtfun" -> "TvT"
    "anime-girigiri" -> "GGL"
    "anime-lanerc-lanerc" -> "Lr"
    "anime-lanerc-23" -> "23"
    "anime-lanerc-ain" -> "Cain"
    else -> name.trim().take(1).ifBlank { "?" }
}

private fun animeSourceWordmarkWidth(text: String, size: Dp): Dp =
    size * (if (text.length == 1) 1f else .66f * text.length)

private fun animeSourceGlyph(name: String, draw: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
    name, 24.dp, 24.dp, 24f, 24f,
).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
}.build()

private val GoldMark = animeSourceGlyph("AnimeGold") {
    moveTo(7f, 3f); lineTo(10f, 9.5f); moveTo(17f, 3f); lineTo(14f, 9.5f)
    moveTo(10.5f, 3f); lineTo(13.5f, 3f); lineTo(12f, 6.5f); close()
    moveTo(18f, 15.5f)
    curveTo(18f, 18.8f, 15.3f, 21.5f, 12f, 21.5f); curveTo(8.7f, 21.5f, 6f, 18.8f, 6f, 15.5f)
    curveTo(6f, 12.2f, 8.7f, 9.5f, 12f, 9.5f); curveTo(15.3f, 9.5f, 18f, 12.2f, 18f, 15.5f)
    moveTo(12f, 13.3f); lineTo(12f, 17.7f)
}

private val YuzuMark = animeSourceGlyph("AnimeYuzu") {
    moveTo(12f, 8f); curveTo(15.3f, 6.2f, 20.1f, 8.9f, 20.1f, 14.6f)
    curveTo(20.1f, 19.1f, 16.2f, 21f, 12f, 21f); curveTo(7.8f, 21f, 3.9f, 19.1f, 3.9f, 14.6f)
    curveTo(3.9f, 8.9f, 8.7f, 6.2f, 12f, 8f); close()
    moveTo(12f, 8f); curveTo(12f, 4.1f, 14.3f, 2.6f, 18.9f, 2.9f)
    curveTo(18f, 5.5f, 15.7f, 7f, 12f, 7f)
    moveTo(8.6f, 13.7f); lineTo(8.7f, 13.7f); moveTo(11f, 16.8f); lineTo(11.1f, 16.8f)
}

private val LemonMark = animeSourceGlyph("AnimeLemon") {
    moveTo(21f, 4.4f); curveTo(21.1f, 6.1f, 20.2f, 7f, 19.8f, 7.6f)
    curveTo(21.1f, 15.7f, 15.1f, 21.3f, 7.5f, 19.7f)
    curveTo(6.8f, 20.2f, 6f, 21f, 4.4f, 20.8f); curveTo(4.2f, 19.2f, 5f, 18.4f, 5.5f, 17.7f)
    curveTo(3.9f, 10.1f, 9.5f, 4.1f, 17.6f, 5.4f); curveTo(18.2f, 5f, 19.3f, 4.2f, 21f, 4.4f); close()
    moveTo(9.4f, 14.2f); curveTo(9.7f, 11.8f, 11.8f, 9.7f, 14.2f, 9.4f)
}
