package dev.mangaflow.ui.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mangaflow.ui.GlassSurfaceRole

/** One material response feeds capture, lens and light. Soft-card shadows are independent. */
@Immutable
internal data class GlassMaterial(
    val blurRadius: Dp,
    val tintOpacity: Float,
    val tintLimit: Float,
    val lensWidth: Dp = 0.dp,
    val lensStrength: Float = 0f,
    val highlightOpacity: Float = 0f,
)

internal fun glassMaterial(
    role: GlassSurfaceRole,
    light: Boolean,
    liquid: Boolean,
    softCard: Boolean,
): GlassMaterial {
    if (!liquid) {
        // Preserve the existing frosted skin and its readable unsupported-device fallback.
        val tint = if (softCard && role != GlassSurfaceRole.DRAWER) {
            if (light) .78f else .82f
        } else when (role) {
            GlassSurfaceRole.READING_DOCK -> if (light) .40f else .52f
            GlassSurfaceRole.POPOVER -> if (light) .64f else .72f
            GlassSurfaceRole.DRAWER -> if (light) .38f else .52f
            else -> if (light) .72f else .78f
        }
        val blur = when (role) {
            GlassSurfaceRole.THUMB -> 4.dp
            GlassSurfaceRole.READING_DOCK -> 18.dp
            GlassSurfaceRole.POPOVER, GlassSurfaceRole.PANEL -> 24.dp
            GlassSurfaceRole.NAVIGATION -> 28.dp
            else -> 20.dp
        }
        return GlassMaterial(blur, tint, tint)
    }
    // Bounded adaptations of Backdrop's edge lens/highlight, using the archived Haze
    // Regular/Clear distinction: compact controls transmit colour; long panels diffuse it.
    return when (role) {
        GlassSurfaceRole.CONTROL -> GlassMaterial(6.dp,
            if (light) .20f else .32f, if (light) .64f else .74f,
            9.dp, .85f, if (light) .34f else .26f)
        GlassSurfaceRole.ARTWORK -> GlassMaterial(9.dp,
            if (light) .22f else .34f, if (light) .62f else .72f,
            10.dp, .75f, if (light) .32f else .26f)
        GlassSurfaceRole.NAVIGATION -> GlassMaterial(12.dp,
            if (light) .28f else .40f, if (light) .66f else .76f,
            11.dp, .72f, if (light) .32f else .26f)
        GlassSurfaceRole.PANEL -> GlassMaterial(18.dp,
            if (light) .52f else .64f, if (light) .84f else .88f,
            14.dp, .38f, if (light) .16f else .14f)
        GlassSurfaceRole.THUMB -> GlassMaterial(1.dp,
            if (light) .10f else .18f, if (light) .10f else .18f,
            7.dp, 1.05f, if (light) .50f else .36f)
        GlassSurfaceRole.READING_DOCK -> GlassMaterial(10.dp,
            if (light) .26f else .38f, if (light) .64f else .76f,
            12.dp, .78f, if (light) .34f else .26f)
        GlassSurfaceRole.POPOVER -> GlassMaterial(20.dp,
            if (light) .48f else .60f, if (light) .82f else .88f,
            12.dp, .42f, if (light) .20f else .18f)
        GlassSurfaceRole.DRAWER -> GlassMaterial(20.dp,
            if (light) .42f else .56f, if (light) .82f else .88f,
            16.dp, .38f, if (light) .14f else .12f)
    }
}
