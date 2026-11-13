package dev.mangaflow.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mangaflow.R

internal fun reviewSourceAccent(id: String, onDark: Boolean): Color = when (id) {
    "copy" -> if (onDark) Color(0xFFA8BED2) else Color(0xFF567593)
    "bangumi" -> if (onDark) Color(0xFFE9B1C8) else Color(0xFF9D5C78)
    "anilist" -> if (onDark) Color(0xFFAFCBE9) else Color(0xFF53749A)
    "mangaupdates" -> if (onDark) Color(0xFFD4BFE8) else Color(0xFF7B638F)
    "mangadex" -> if (onDark) Color(0xFFE5C38D) else Color(0xFF96753E)
    "myanimelist" -> if (onDark) Color(0xFFB4C5EA) else Color(0xFF5C709C)
    "kitsu" -> if (onDark) Color(0xFFE4B8A0) else Color(0xFF9B6B52)
    "shikimori" -> if (onDark) Color(0xFFC8C4DE) else Color(0xFF77718E)
    else -> if (onDark) Color(0xFFCFCCD4) else Color(0xFF76717E)
}

/** Every app source glyph uses the same square optical box, independent of a site's initials. */
internal fun reviewSourceMarkWidth(size: Dp, fontScale: Float): Dp = size * (1.1f * fontScale.coerceAtLeast(1f))

/** One local typographic source mark, shared by compact badges and full source rows. */
@Composable
internal fun CopySourceWordmark(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val onDark = MaterialTheme.colorScheme.onSurface.luminance() > .5f
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Text("拷", color = reviewSourceAccent("copy", onDark),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium, fontSize = with(density) { (side * .86f).toSp() },
                lineHeight = with(density) { side.toSp() }, letterSpacing = 0.sp),
            maxLines = 1, softWrap = false)
    }
}

/** App-owned pictograms, not replacement official logos. Names retain exact source identity. */
@Composable
internal fun ReviewSourceMark(
    id: String,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    onDark: Boolean = MaterialTheme.colorScheme.onSurface.luminance() > .5f,
    heat: Boolean = false,
    alignment: Alignment = Alignment.Center,
) {
    val color = reviewSourceAccent(id, onDark)
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val resource = when (id) {
        "kitsu" -> R.drawable.ic_source_kitsu
        else -> null
    }
    Box(modifier.size(width = reviewSourceMarkWidth(size, fontScale), height = size * fontScale)
        .clearAndSetSemantics { }, contentAlignment = alignment) {
        when {
            heat -> Icon(Icons.Outlined.LocalFireDepartment, null, tint = color,
                modifier = Modifier.size(size * (.88f * fontScale)))
            id == "copy" -> CopySourceWordmark(Modifier.requiredSize(size * fontScale))
            resource != null -> {
                Icon(painterResource(resource), null, tint = color,
                    modifier = Modifier.size(size * (.92f * fontScale)))
            }
            else -> {
                val glyph = when (id) {
                    "bangumi" -> Icons.Outlined.Tv
                    "anilist" -> Icons.AutoMirrored.Outlined.MenuBook
                    "mangaupdates" -> Icons.Outlined.CollectionsBookmark
                    "myanimelist" -> Icons.AutoMirrored.Outlined.PlaylistPlay
                    "mangadex" -> Icons.Outlined.Bookmarks
                    "shikimori" -> Icons.Outlined.Theaters
                    "tencent" -> Icons.Outlined.SmartDisplay
                    "bahamut" -> Icons.Outlined.Forum
                    "manhuagui" -> Icons.AutoMirrored.Outlined.LibraryBooks
                    "mangabaka" -> Icons.Outlined.AutoStories
                    else -> Icons.Outlined.Language
                }
                Icon(glyph, null, tint = color, modifier = Modifier.size(size * (.92f * fontScale)))
            }
        }
    }
}
