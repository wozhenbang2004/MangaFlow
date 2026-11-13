@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.mangaflow.typography

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import dev.mangaflow.platform.storage.StorageFile
import org.jetbrains.skia.Data
import org.jetbrains.skia.FontMgr

/** Desktop and Apple targets share Compose's Skia byte-array font loader and explicit variable axes. */
internal actual fun loadMangaFontFamily(identity: String, files: List<MangaFontFile>): FontFamily {
    require(identity.isNotBlank() && files.isNotEmpty() && files.size <= 8)
    val fonts = files.flatMap { face ->
        val file = StorageFile(face.path)
        require(file.isFile) { "字库文件不存在，请重新下载。" }
        val bytes = file.readBytes()
        val data = Data.makeFromBytes(bytes)
        try {
            val checked = requireNotNull(FontMgr.default.makeFromData(data)) { "字库无法加载，请重新下载。" }
            checked.close()
        } finally {
            data.close()
        }
        val weights = face.weight?.let { listOf(it) } ?: listOf(400, 500, 600, 700)
        weights.map { value ->
            require(value in 1..1000)
            Font(
                identity = "mangaflow:$identity:${face.path}:$value",
                data = bytes,
                weight = FontWeight(value),
                style = FontStyle.Normal,
                variationSettings = if (face.weight == null)
                    FontVariation.Settings(FontVariation.weight(value)) else FontVariation.Settings(),
            )
        }
    }
    return FontFamily(fonts)
}
