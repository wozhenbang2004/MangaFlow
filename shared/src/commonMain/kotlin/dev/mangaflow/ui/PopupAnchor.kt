package dev.mangaflow.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced

/** Keep the placed node. Only the activated item needs its current position on screen. */
internal class PopupAnchor {
    private var coordinates: LayoutCoordinates? = null
    val modifier = Modifier.onPlaced { coordinates = it }

    fun bounds(): Rect? {
        val layout = coordinates ?: return null
        if (!layout.isAttached || layout.size.width <= 0 || layout.size.height <= 0) return null
        val origin = layout.localToScreen(Offset.Zero)
        return if (origin.isSpecified) Rect(origin, Size(layout.size.width.toFloat(), layout.size.height.toFloat())) else null
    }
}
