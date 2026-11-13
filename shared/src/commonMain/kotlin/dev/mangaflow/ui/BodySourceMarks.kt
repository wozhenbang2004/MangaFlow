package dev.mangaflow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mangaflow.data.Chapter
import dev.mangaflow.ui.motion.motionPress

/** Local source identities, not official logos. Every path shares one 24-unit optical box. */
@Composable
internal fun BodySourceMark(sourceId: String, sourceName: String, modifier: Modifier = Modifier,
    muted: Boolean = false) {
    if (sourceId == "copy") {
        CopySourceWordmark(modifier)
        return
    }
    val colors = MaterialTheme.colorScheme
    val onDark = colors.onSurface.luminance() > .5f
    val tint = if (muted) colors.onSurfaceVariant else when (sourceId) {
        "baozi" -> if (onDark) Color(0xFFD2B8A8) else Color(0xFF92705D)
        "manhuagui" -> if (onDark) Color(0xFFBCC6B5) else Color(0xFF72796D)
        "mangabz" -> if (onDark) Color(0xFFD8B5B5) else Color(0xFF956A6A)
        "komiic" -> if (onDark) Color(0xFFC3BBDE) else Color(0xFF746D94)
        "mangadex" -> if (onDark) Color(0xFFD8BB99) else Color(0xFF9A7554)
        else -> colors.onSurfaceVariant
    }
    Icon(BodySourceVectors.forSource(sourceId), sourceName, modifier, tint = tint)
}

/** A reader source entry is one disclosure target. Only a row in its chooser changes source. */
@Composable
internal fun ReaderSourceStack(chapter: Chapter, modifier: Modifier = Modifier, enabled: Boolean = true,
    onOpen: () -> Unit) {
    val sources = remember(chapter.id, chapter.sourceId, chapter.variants) {
        val current = chapter.variants.firstOrNull { it.storageId == chapter.id }
            ?: chapter.variants.firstOrNull { it.sourceId == chapter.sourceId }
        val unique = chapter.variants.distinctBy { it.sourceId }
        if (current == null) unique else listOf(current) + unique.filter { it.sourceId != current.sourceId }
    }
    if (sources.isEmpty()) return
    val displayed = sources.take(3)
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val description = "当前来源：${sources.first().sourceName}，${sources.size}个来源，展开选择"
    Box(modifier.width(48.dp).heightIn(min = 44.dp)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button,
            onClickLabel = "选择本章来源", onClick = onOpen)
        .motionPress(interaction, enabled = enabled, pressedScale = .97f)
        .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center) {
        val badge = 22.dp
        val step = 10.dp
        Box(Modifier.width(badge + step * (displayed.size - 1)).height(badge)) {
            // Opaque neutral badges physically occlude the rear artwork. The selected
            // source is drawn last, with every centre kept on the same X-axis baseline.
            displayed.indices.reversed().forEach { index ->
                val source = displayed[index]
                val surface = lerp(colors.surface, colors.onSurface, .055f + .025f * index).copy(alpha = 1f)
                Box(Modifier.absoluteOffset(x = step * index).size(badge).background(surface, CircleShape),
                    contentAlignment = Alignment.Center) {
                    ChapterSourceLogo(source.sourceId, source.sourceName, Modifier.size(15.dp), muted = index != 0)
                }
            }
        }
    }
}

private object BodySourceVectors {
    private fun glyph(name: String, draw: PathBuilder.() -> Unit): ImageVector = ImageVector.Builder(
        name = "MangaFlowBody$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()

    // A pleated steamed bun. The top folds are distinct even at chapter-badge sizes.
    private val baozi = glyph("Baozi") {
        moveTo(4f, 10f); curveTo(5.8f, 7.9f, 8.4f, 6f, 12f, 5f)
        curveTo(15.6f, 6f, 18.2f, 7.9f, 20f, 10f)
        curveTo(21.8f, 12.4f, 21.3f, 16.9f, 18.7f, 18.4f)
        curveTo(15.6f, 20.3f, 8.4f, 20.3f, 5.3f, 18.4f)
        curveTo(2.7f, 16.9f, 2.2f, 12.4f, 4f, 10f); close()
        moveTo(12f, 5f); lineTo(12f, 10f)
        moveTo(8.5f, 6.7f); lineTo(9.7f, 10.5f)
        moveTo(15.5f, 6.7f); lineTo(14.3f, 10.5f)
        moveTo(8f, 16.8f); curveTo(10.5f, 17.5f, 13.5f, 17.5f, 16f, 16.8f)
    }

    // A geometric turtle shell, with a book-like centre seam.
    private val manhuagui = glyph("Manhuagui") {
        moveTo(12f, 5f); curveTo(6.3f, 5f, 4f, 8.3f, 4f, 12.7f)
        curveTo(4f, 17.2f, 7.3f, 20f, 12f, 20f)
        curveTo(16.7f, 20f, 20f, 17.2f, 20f, 12.7f)
        curveTo(20f, 8.3f, 17.7f, 5f, 12f, 5f); close()
        moveTo(9f, 5.2f); lineTo(9.6f, 2.8f); lineTo(14.4f, 2.8f); lineTo(15f, 5.2f)
        moveTo(8.4f, 10f); lineTo(12f, 8f); lineTo(15.6f, 10f)
        lineTo(15.6f, 15f); lineTo(12f, 17f); lineTo(8.4f, 15f); close()
        moveTo(12f, 8f); lineTo(12f, 17f)
        moveTo(4.8f, 17f); lineTo(3f, 19f)
        moveTo(19.2f, 17f); lineTo(21f, 19f)
    }

    // A folded comic page and a strong diagonal flash, rather than tiny BZ lettering.
    private val mangabz = glyph("MangaBZ") {
        moveTo(13.5f, 3f); lineTo(5f, 3f); lineTo(5f, 21f); lineTo(19f, 21f)
        lineTo(19f, 8.5f); lineTo(13.5f, 3f); lineTo(13.5f, 8.5f); lineTo(19f, 8.5f)
        moveTo(12.6f, 10.5f); lineTo(8.4f, 15.3f); lineTo(12f, 15.3f)
        lineTo(10.9f, 19f); lineTo(16f, 13.6f); lineTo(12.5f, 13.6f); close()
    }

    // Interleaved pages form a clear centre opening with two outward corners.
    private val komiic = glyph("Komiic") {
        moveTo(3f, 5f); lineTo(9f, 7f); lineTo(12f, 10f); lineTo(15f, 7f); lineTo(21f, 5f)
        lineTo(21f, 18f); lineTo(15f, 20f); lineTo(12f, 17f); lineTo(9f, 20f); lineTo(3f, 18f); close()
        moveTo(7f, 8f); lineTo(7f, 15.5f); lineTo(12f, 17f); lineTo(17f, 15.5f); lineTo(17f, 8f)
        moveTo(12f, 10f); lineTo(12f, 17f)
    }

    // Index leaves: a rear page edge plus three strong directory lines.
    private val mangadex = glyph("MangaDex") {
        moveTo(4f, 4f); lineTo(17f, 4f); lineTo(17f, 18f); lineTo(4f, 18f); close()
        moveTo(8f, 21f); lineTo(21f, 21f); lineTo(21f, 8f)
        moveTo(7.5f, 8f); lineTo(13.5f, 8f)
        moveTo(7.5f, 11f); lineTo(13.5f, 11f)
        moveTo(7.5f, 14f); lineTo(11.5f, 14f)
    }

    private val generic = glyph("Source") {
        moveTo(3f, 5f); lineTo(8f, 5f); curveTo(10f, 5f, 12f, 6.6f, 12f, 8f)
        curveTo(12f, 6.6f, 14f, 5f, 16f, 5f); lineTo(21f, 5f); lineTo(21f, 19f)
        lineTo(16f, 19f); curveTo(14f, 19f, 12f, 20f, 12f, 21f)
        curveTo(12f, 20f, 10f, 19f, 8f, 19f); lineTo(3f, 19f); close()
        moveTo(12f, 8f); lineTo(12f, 21f)
    }

    fun forSource(id: String): ImageVector = when (id) {
        "baozi" -> baozi
        "manhuagui" -> manhuagui
        "mangabz" -> mangabz
        "komiic" -> komiic
        "mangadex" -> mangadex
        else -> generic
    }
}
