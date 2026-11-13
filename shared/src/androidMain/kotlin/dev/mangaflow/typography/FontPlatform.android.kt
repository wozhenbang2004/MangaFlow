@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.mangaflow.typography

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/** Preload native faces here so resolving a selected family performs no disk reads on the UI thread. */
internal actual fun loadMangaFontFamily(identity: String, files: List<MangaFontFile>): FontFamily {
    require(identity.isNotBlank() && files.isNotEmpty() && files.size <= 8)
    val fonts = files.flatMap { face ->
        val file = File(face.path)
        require(file.isFile) { "字库文件不存在，请重新下载。" }
        val weights = face.weight?.let { listOf(it) } ?: listOf(400, 500, 600, 700)
        weights.map { value ->
            require(value in 1..1000)
            val builder = Typeface.Builder(file).setWeight(value).setItalic(false)
            val variation = if (face.weight == null) {
                builder.setFontVariationSettings("'wght' $value")
                FontVariation.Settings(FontVariation.weight(value))
            } else FontVariation.Settings()
            val loaded = requireNotNull(builder.build()) { "字库无法加载，请重新下载。" }
            InstalledAndroidFont(FontWeight(value), variation, loaded)
        }
    }
    return FontFamily(fonts)
}

private class InstalledAndroidFont(
    override val weight: FontWeight,
    variation: FontVariation.Settings,
    val typeface: Typeface,
) : AndroidFont(FontLoadingStrategy.Blocking, InstalledAndroidTypefaceLoader, variation) {
    override val style: FontStyle = FontStyle.Normal
}

private object InstalledAndroidTypefaceLoader : AndroidFont.TypefaceLoader {
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface =
        (font as InstalledAndroidFont).typeface

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
        loadBlocking(context, font)
}
